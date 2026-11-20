//! Incremental server-sent-events parser, as ui-v2's `sse.ts`: chunks may split events anywhere, comments
//! (`: ping`) are skipped, `data` is JSON when it parses (else kept as text, empty data is `{}`).

use serde_json::Value;

#[derive(Debug, Clone, PartialEq)]
pub struct SseEvent {
    pub event: String,
    pub data: Value,
    pub id: Option<String>,
}

#[derive(Default)]
pub struct SseParser {
    buffer: String,
}

impl SseParser {
    pub fn new() -> Self {
        Self::default()
    }

    /// Takes a chunk and returns the events it completed.
    pub fn feed(&mut self, chunk: &str) -> Vec<SseEvent> {
        self.buffer.push_str(&chunk.replace("\r\n", "\n").replace('\r', "\n"));
        let mut out = Vec::new();
        while let Some(end) = self.buffer.find("\n\n") {
            let block: String = self.buffer[..end].to_string();
            self.buffer.drain(..end + 2);
            if let Some(e) = dispatch(&block) {
                out.push(e);
            }
        }
        out
    }

    /// The end of the stream: what is left counts as a last event.
    pub fn end(&mut self) -> Vec<SseEvent> {
        let rest = std::mem::take(&mut self.buffer);
        if rest.trim().is_empty() { vec![] } else { dispatch(&rest).into_iter().collect() }
    }
}

fn dispatch(block: &str) -> Option<SseEvent> {
    let mut event = "message".to_string();
    let mut id = None;
    let mut data: Vec<&str> = Vec::new();
    for line in block.split('\n') {
        if line.is_empty() || line.starts_with(':') {
            continue;
        }
        let (field, value) = match line.find(':') {
            Some(c) => {
                let v = &line[c + 1..];
                (&line[..c], v.strip_prefix(' ').unwrap_or(v))
            }
            None => (line, ""),
        };
        match field {
            "event" => event = value.to_string(),
            "data" => data.push(value),
            "id" => id = Some(value.to_string()),
            _ => {}
        }
    }
    if data.is_empty() && event == "message" {
        return None;
    }
    let raw = data.join("\n");
    let data = if raw.is_empty() { Value::Object(Default::default()) } else { serde_json::from_str(&raw).unwrap_or(Value::String(raw)) };
    Some(SseEvent { event, data, id })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn ev(event: &str, data: Value, id: Option<&str>) -> SseEvent {
        SseEvent { event: event.into(), data, id: id.map(Into::into) }
    }

    #[test]
    fn handles_split_events_comments_and_crlf() {
        let mut p = SseParser::new();
        let mut got = p.feed("event: delta\ndata: {\"te");
        got.extend(p.feed("xt\":\"Hel\"}\n\n: ping\n\nevent: delta\r\ndata: {\"text\":\"lo\"}\r\n\r\n"));
        got.extend(p.feed("event: done\ndata: {}"));
        got.extend(p.end());
        assert_eq!(
            got,
            vec![ev("delta", json!({"text": "Hel"}), None), ev("delta", json!({"text": "lo"}), None), ev("done", json!({}), None)]
        );
    }

    #[test]
    fn keeps_non_json_data_as_text() {
        let mut p = SseParser::new();
        assert_eq!(p.feed("data: hello\ndata: world\n\n"), vec![ev("message", json!("hello\nworld"), None)]);
    }

    #[test]
    fn passes_the_id() {
        let mut p = SseParser::new();
        assert_eq!(p.feed("id: 7\nevent: delta\ndata: {\"text\":\"x\"}\n\n"), vec![ev("delta", json!({"text": "x"}), Some("7"))]);
    }
}
