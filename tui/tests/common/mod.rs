//! A scripted fake of the `web` plugin's API on a local port (no Panic, no network, no keys), like ui-v2's
//! `e2e/fake-api.ts`. When the API changes (an endpoint, an event, a field), change this file in the same commit.

#![allow(dead_code)]

use panic_tui::api::HttpBackend;
use panic_tui::state::{Msg, Store};
use serde_json::{Value, json};
use std::collections::HashSet;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::mpsc::{Receiver, channel};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

/// One step of a scripted SSE body.
#[derive(Clone)]
pub enum Step {
    Event(&'static str, Value),
    /// Waits until the test calls `release` with this name.
    Hold(&'static str),
    /// Closes the connection here, as a dropped network does.
    Drop,
}

pub enum Reply {
    Json(u16, Value),
    Empty(u16),
    Sse(Vec<Step>),
}

#[derive(Debug, Clone)]
pub struct Request {
    pub method: String,
    pub path: String,
    pub body: Value,
}

type Route = Box<dyn Fn(&Request) -> Option<Reply> + Send + Sync>;

pub struct Fake {
    pub url: String,
    routes: Arc<Mutex<Vec<Route>>>,
    pub requests: Arc<Mutex<Vec<Request>>>,
    released: Arc<(Mutex<HashSet<&'static str>>, Condvar)>,
}

impl Fake {
    pub fn start() -> Fake {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let fake = Fake {
            url,
            routes: Arc::new(Mutex::new(vec![])),
            requests: Arc::new(Mutex::new(vec![])),
            released: Arc::new((Mutex::new(HashSet::new()), Condvar::new())),
        };
        let (routes, requests, released) = (fake.routes.clone(), fake.requests.clone(), fake.released.clone());
        thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let (routes, requests, released) = (routes.clone(), requests.clone(), released.clone());
                thread::spawn(move || serve(stream, &routes, &requests, &released));
            }
        });
        fake
    }

    /// Answers requests `route` matches; routes added later win.
    pub fn on(&self, route: impl Fn(&Request) -> Option<Reply> + Send + Sync + 'static) {
        self.routes.lock().unwrap().insert(0, Box::new(route));
    }

    pub fn release(&self, name: &'static str) {
        let (set, cv) = &*self.released;
        set.lock().unwrap().insert(name);
        cv.notify_all();
    }

    pub fn requests(&self, method: &str, prefix: &str) -> Vec<Request> {
        self.requests.lock().unwrap().iter().filter(|r| r.method == method && r.path.starts_with(prefix)).cloned().collect()
    }

    /// A store on this server, and the channel its workers answer on.
    pub fn store(&self, session: Option<&str>) -> (Store, Receiver<Msg>) {
        let (tx, rx) = channel();
        let store = Store::new(Arc::new(HttpBackend::new(&self.url)), tx, session.map(Into::into));
        (store, rx)
    }

    /// The defaults of a server with every plugin and nothing stored.
    pub fn with_defaults(self) -> Self {
        self.on(|r| match (r.method.as_str(), r.path.split('?').next().unwrap()) {
            ("GET", "/api/state") => Some(Reply::Json(
                200,
                json!({"provider":"openrouter","model":"m","reasoningEffort":null,"connected":true,"effortSupported":null,
                       "providers":[{"id":"openrouter","connected":true}],"commands":[{"name":"help","usage":"help","description":"List commands"}]}),
            )),
            ("GET", "/api/conversations") => Some(Reply::Json(200, json!([]))),
            ("GET", "/api/workspaces") => Some(Reply::Json(200, json!({"projects":[],"hidden":[]}))),
            ("GET", "/api/permissions") => Some(Reply::Json(200, json!({"bypass":false,"default":false}))),
            ("GET", "/api/mode") => Some(Reply::Json(200, json!({"plan":false,"default":false}))),
            ("GET", "/api/models") => Some(Reply::Json(200, json!([{"id":"m","name":"M","contextLength":1000,"maxOutput":0,"free":true,"reasoning":null}]))),
            ("GET", "/api/history") => Some(Reply::Json(200, json!([]))),
            ("GET", "/api/stream") => Some(Reply::Empty(204)),
            ("GET", "/api/trace") => Some(Reply::Json(404, json!({"error":"tracing is off"}))),
            ("POST", "/api/approve" | "/api/plan" | "/api/answer" | "/api/stop") => Some(Reply::Json(200, json!({"ok":true}))),
            ("POST", "/api/command") => Some(Reply::Sse(vec![Step::Event("done", json!({}))])),
            ("DELETE", "/api/conversations") => Some(Reply::Json(200, json!({"ok":true}))),
            _ => None,
        });
        self
    }
}

fn serve(
    stream: TcpStream,
    routes: &Mutex<Vec<Route>>,
    requests: &Mutex<Vec<Request>>,
    released: &(Mutex<HashSet<&'static str>>, Condvar),
) {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut line = String::new();
    if reader.read_line(&mut line).unwrap_or(0) == 0 {
        return;
    }
    let mut parts = line.split_whitespace();
    let (method, path) = (parts.next().unwrap_or("").to_string(), parts.next().unwrap_or("").to_string());
    let mut length = 0;
    loop {
        let mut h = String::new();
        reader.read_line(&mut h).unwrap();
        if h.trim().is_empty() {
            break;
        }
        if let Some((k, v)) = h.split_once(':')
            && k.eq_ignore_ascii_case("content-length")
        {
            length = v.trim().parse().unwrap_or(0);
        }
    }
    let mut body = vec![0; length];
    reader.read_exact(&mut body).unwrap();
    let body = serde_json::from_slice(&body).unwrap_or(Value::Null);
    let req = Request { method, path, body };
    requests.lock().unwrap().push(req.clone());
    let reply = routes.lock().unwrap().iter().find_map(|r| r(&req)).unwrap_or(Reply::Json(404, json!({"error":"not found"})));
    let mut out = stream;
    match reply {
        Reply::Json(status, v) => {
            let b = v.to_string();
            let _ = write!(
                out,
                "HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{b}",
                b.len()
            );
        }
        Reply::Empty(status) => {
            let _ = write!(out, "HTTP/1.1 {status} X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }
        Reply::Sse(steps) => {
            let _ = write!(out, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n");
            // like the server, a re-attach gets only the events after the last id it saw
            let after: u64 = req.path.split("after=").nth(1).and_then(|v| v.split('&').next()?.parse().ok()).unwrap_or(0);
            let mut id = 0;
            for s in steps {
                match s {
                    Step::Event(name, data) => {
                        id += 1;
                        if id <= after {
                            continue;
                        }
                        if write!(out, "id: {id}\nevent: {name}\ndata: {data}\n\n").and_then(|_| out.flush()).is_err() {
                            return;
                        }
                    }
                    Step::Hold(name) => {
                        let (set, cv) = released;
                        let mut g = set.lock().unwrap();
                        while !g.contains(name) {
                            g = cv.wait(g).unwrap();
                        }
                    }
                    Step::Drop => return,
                }
            }
        }
    }
}

/// Applies the workers' messages until `done` holds (or fails after 5 s).
pub fn until(store: &mut Store, rx: &Receiver<Msg>, done: impl Fn(&Store) -> bool) {
    let end = Instant::now() + Duration::from_secs(5);
    while !done(store) {
        let left = end.saturating_duration_since(Instant::now());
        assert!(!left.is_zero(), "timed out");
        if let Ok(m) = rx.recv_timeout(left.min(Duration::from_millis(50))) {
            store.update(m);
        }
    }
}

/// Applies messages for a while, for checks that something does not happen.
pub fn settle(store: &mut Store, rx: &Receiver<Msg>, ms: u64) {
    let end = Instant::now() + Duration::from_millis(ms);
    while Instant::now() < end {
        if let Ok(m) = rx.recv_timeout(Duration::from_millis(10)) {
            store.update(m);
        }
    }
}
