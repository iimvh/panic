//! [`Backend`] over HTTP with ureq: blocking calls, each made from a worker thread so the view never waits.

use super::{Answers, ApiError, Backend, Events, Model, Result, SettingsChange, State, Toggle};
use crate::core::conversations::{ConversationInfo, Workspaces};
use crate::core::reply::HistoryMessage;
use crate::core::sse::{SseEvent, SseParser};
use crate::core::trace::Span;
use serde_json::{Value, json};
use std::collections::VecDeque;
use std::io::Read;
use std::time::Duration;
use ureq::Agent;
use ureq::http::Response;

pub struct HttpBackend {
    base: String,
    agent: Agent,
}

fn enc(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

impl HttpBackend {
    /// `base`: where Panic serves the web API, e.g. `http://127.0.0.1:8080`.
    pub fn new(base: &str) -> Self {
        let config = Agent::config_builder().http_status_as_error(false).timeout_connect(Some(Duration::from_secs(5))).build();
        HttpBackend { base: base.trim_end_matches('/').to_string(), agent: Agent::new_with_config(config) }
    }

    fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base)
    }

    fn check(res: std::result::Result<Response<ureq::Body>, ureq::Error>) -> Result<Response<ureq::Body>> {
        let mut res = res.map_err(|_| ApiError::network())?;
        let status = res.status().as_u16();
        if (200..300).contains(&status) {
            return Ok(res);
        }
        let reason = res.status().canonical_reason().unwrap_or("");
        let mut message = format!("{status} {reason}").trim().to_string();
        if let Ok(text) = res.body_mut().read_to_string()
            && let Ok(v) = serde_json::from_str::<Value>(&text)
            && let Some(e) = v.get("error").and_then(Value::as_str)
        {
            message = e.to_string();
        }
        Err(ApiError { status, message })
    }

    fn json<T: serde::de::DeserializeOwned>(res: Response<ureq::Body>) -> Result<T> {
        let mut res = res;
        let text = res.body_mut().with_config().limit(64 * 1024 * 1024).read_to_string().map_err(|_| ApiError::network())?;
        serde_json::from_str(&text).map_err(|e| ApiError { status: 500, message: format!("bad answer from the server: {e}") })
    }

    fn get<T: serde::de::DeserializeOwned>(&self, path: &str) -> Result<T> {
        Self::json(Self::check(self.agent.get(&self.url(path)).call())?)
    }

    /// A GET whose 503 means "no plugin answers it".
    fn optional<T: serde::de::DeserializeOwned>(&self, path: &str) -> Result<Option<T>> {
        match self.get(path) {
            Ok(v) => Ok(Some(v)),
            Err(e) if e.status == 503 => Ok(None),
            Err(e) => Err(e),
        }
    }

    fn post(&self, path: &str, body: Value) -> Result<Response<ureq::Body>> {
        Self::check(self.agent.post(&self.url(path)).header("Content-Type", "application/json").send(body.to_string()))
    }

    fn post_ok(&self, path: &str, body: Value) -> Result<()> {
        self.post(path, body).map(|_| ())
    }

    fn toggle(&self, path: &str, key: &str) -> Result<Option<Toggle>> {
        Ok(self
            .optional::<Value>(path)?
            .map(|v| Toggle { on: v.get(key) == Some(&Value::Bool(true)), default: v.get("default") == Some(&Value::Bool(true)) }))
    }
}

/// Server-sent events read off a response body as they arrive.
struct Stream {
    body: Box<dyn Read + Send>,
    parser: SseParser,
    pending: VecDeque<SseEvent>,
    /// Bytes of a UTF-8 sequence a read cut in two, kept for the next read.
    carry: Vec<u8>,
    done: bool,
}

impl Stream {
    fn events(res: Response<ureq::Body>) -> Events {
        Box::new(Stream {
            body: Box::new(res.into_body().into_reader()),
            parser: SseParser::new(),
            pending: VecDeque::new(),
            carry: vec![],
            done: false,
        })
    }
}

impl Iterator for Stream {
    type Item = Result<SseEvent>;

    fn next(&mut self) -> Option<Self::Item> {
        let mut buf = [0u8; 8192];
        loop {
            if let Some(e) = self.pending.pop_front() {
                return Some(Ok(e));
            }
            if self.done {
                return None;
            }
            match self.body.read(&mut buf) {
                Ok(0) => {
                    self.done = true;
                    self.pending.extend(self.parser.end());
                }
                Ok(n) => {
                    self.carry.extend_from_slice(&buf[..n]);
                    let valid = match std::str::from_utf8(&self.carry) {
                        Ok(_) => self.carry.len(),
                        Err(e) => e.valid_up_to(),
                    };
                    let text = String::from_utf8_lossy(&self.carry[..valid]).into_owned();
                    self.carry.drain(..valid);
                    self.pending.extend(self.parser.feed(&text));
                }
                Err(_) => {
                    self.done = true;
                    return Some(Err(ApiError::network()));
                }
            }
        }
    }
}

impl Backend for HttpBackend {
    fn state(&self) -> Result<State> {
        self.get("/api/state")
    }

    fn history(&self, session: &str) -> Result<Vec<HistoryMessage>> {
        let list: Vec<Value> = self.get(&format!("/api/history?session={}", enc(session)))?;
        Ok(list.iter().filter_map(HistoryMessage::from_json).collect())
    }

    fn conversations(&self) -> Result<Vec<ConversationInfo>> {
        self.get("/api/conversations")
    }

    fn delete_conversation(&self, session: &str) -> Result<()> {
        match Self::check(self.agent.delete(&self.url(&format!("/api/conversations?session={}", enc(session)))).call()) {
            Ok(_) => Ok(()),
            Err(e) if e.status == 404 => Ok(()),
            Err(e) => Err(e),
        }
    }

    fn workspaces(&self) -> Result<Option<Workspaces>> {
        self.optional("/api/workspaces")
    }

    fn permissions(&self, session: &str) -> Result<Option<Toggle>> {
        self.toggle(&format!("/api/permissions?session={}", enc(session)), "bypass")
    }

    fn mode(&self, session: &str) -> Result<Option<Toggle>> {
        self.toggle(&format!("/api/mode?session={}", enc(session)), "plan")
    }

    fn trace(&self, session: &str, id: &str) -> Result<Vec<Span>> {
        let v: Value = self.get(&format!("/api/trace?session={}&id={}", enc(session), enc(id)))?;
        Ok(v.get("spans").and_then(Value::as_array).map(|a| a.iter().filter_map(Span::from_json).collect()).unwrap_or_default())
    }

    fn models(&self, filter: &str) -> Result<Vec<Model>> {
        self.get(&format!("/api/models?filter={}", enc(filter)))
    }

    fn put_settings(&self, change: &SettingsChange) -> Result<State> {
        let mut body = serde_json::Map::new();
        if let Some(p) = &change.provider {
            body.insert("provider".into(), json!(p));
        }
        if let Some(m) = &change.model {
            body.insert("model".into(), json!(m));
        }
        if let Some(e) = &change.reasoning_effort {
            body.insert("reasoningEffort".into(), json!(e));
        }
        let res =
            self.agent.put(&self.url("/api/settings")).header("Content-Type", "application/json").send(Value::Object(body).to_string());
        Self::json(Self::check(res)?)
    }

    fn approve(&self, session: &str, id: &str, allow: bool) -> Result<()> {
        self.post_ok("/api/approve", json!({"session": session, "id": id, "allow": allow}))
    }

    fn plan(&self, session: &str, id: &str, approve: bool, feedback: &str) -> Result<()> {
        self.post_ok("/api/plan", json!({"session": session, "id": id, "approve": approve, "feedback": feedback}))
    }

    fn answer(&self, session: &str, id: &str, answers: &Answers) -> Result<()> {
        let list: Vec<Value> = answers.iter().map(|(selected, other)| json!({"selected": selected, "other": other})).collect();
        self.post_ok("/api/answer", json!({"session": session, "id": id, "answers": list}))
    }

    fn stop(&self, session: &str) -> Result<()> {
        self.post_ok("/api/stop", json!({"session": session}))
    }

    fn chat(&self, session: &str, text: &str) -> Result<Events> {
        Ok(Stream::events(self.post("/api/chat", json!({"session": session, "text": text}))?))
    }

    fn command(&self, session: &str, line: &str) -> Result<Events> {
        Ok(Stream::events(self.post("/api/command", json!({"session": session, "line": line}))?))
    }

    fn follow(&self, session: &str, after: u64) -> Result<Option<Events>> {
        let res = Self::check(self.agent.get(&self.url(&format!("/api/stream?session={}&after={after}", enc(session)))).call())?;
        if res.status().as_u16() == 204 {
            return Ok(None);
        }
        Ok(Some(Stream::events(res)))
    }
}

#[cfg(test)]
mod tests {
    use super::enc;

    #[test]
    fn encodes_query_values() {
        assert_eq!(enc("a b/c&d"), "a%20b%2Fc%26d");
        assert_eq!(enc("abc-_.~"), "abc-_.~");
    }
}
