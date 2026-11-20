//! The state layer against a scripted server: answers, cards, the queue, background chats, resuming.

mod common;

use common::{Fake, Reply, Step, settle, until};
use panic_tui::core::reply::{Part, Role, Status};
use panic_tui::state::Mode;
use serde_json::json;

fn chat(steps: Vec<Step>) -> impl Fn(&common::Request) -> Option<Reply> + Send + Sync {
    move |r| (r.method == "POST" && r.path == "/api/chat").then(|| Reply::Sse(steps.clone()))
}

#[test]
fn an_answer_streams_and_ends() {
    let fake = Fake::start().with_defaults();
    fake.on(chat(vec![
        Step::Event("start", json!({"question":"hi","traceId":"t"})),
        Step::Event("delta", json!({"text":"Hel"})),
        Step::Event("delta", json!({"text":"lo"})),
        Step::Event("done", json!({"model":"m","usage":{"promptTokens":3,"completionTokens":2}})),
    ]));
    let (mut s, rx) = fake.store(None);
    s.send("hi");
    assert!(s.conv().busy());
    until(&mut s, &rx, |s| !s.conv().busy());
    let m = &s.conv().messages;
    assert_eq!(m.len(), 2);
    assert_eq!((m[0].role, m[0].text.as_str()), (Role::User, "hi"));
    assert_eq!(m[1].parts, vec![Part::Text("Hello".into())]);
    assert!(m[1].meta.as_deref().unwrap().starts_with("m · 5 tokens · "), "{:?}", m[1].meta);
    assert!(!s.conv().fresh);
    assert_eq!(fake.requests("POST", "/api/chat")[0].body["text"], "hi");
}

#[test]
fn approvals_are_answered_and_cleared() {
    let fake = Fake::start().with_defaults();
    fake.on(chat(vec![
        Step::Event("start", json!({})),
        Step::Event("tool", json!({"id":"c","name":"exec","arguments":"{\"command\":\"ls\"}","status":"running"})),
        Step::Event("approve", json!({"id":"q","callId":"c","name":"exec","arguments":"{\"command\":\"ls\"}","risk":"exec"})),
        Step::Hold("answered"),
        Step::Event("tool", json!({"id":"c","name":"exec","status":"ok","content":"a"})),
        Step::Event("done", json!({"model":"m"})),
    ]));
    let (mut s, rx) = fake.store(None);
    s.send("list");
    until(&mut s, &rx, |s| s.conv().waiting().is_some());
    s.approve(true);
    assert!(s.conv().waiting().is_none(), "the card goes at once");
    until(&mut s, &rx, |_| !fake.requests("POST", "/api/approve").is_empty());
    assert_eq!(fake.requests("POST", "/api/approve")[0].body, json!({"session": s.current, "id": "q", "allow": true}));
    fake.release("answered");
    until(&mut s, &rx, |s| !s.conv().busy());
    let Part::Tool(t) = &s.conv().messages[1].parts[0] else { panic!() };
    assert_eq!(t.status, Status::Ok);
}

#[test]
fn messages_sent_while_busy_wait_and_go_in_order() {
    let fake = Fake::start().with_defaults();
    fake.on(chat(vec![Step::Event("start", json!({})), Step::Hold("first"), Step::Event("done", json!({"model":"m"}))]));
    let (mut s, rx) = fake.store(None);
    s.send("one");
    s.send("two");
    s.send("three");
    assert_eq!(s.conv().queue, vec!["two", "three"]);
    assert_eq!(s.unqueue().as_deref(), Some("three"));
    fake.release("first");
    until(&mut s, &rx, |s| !s.conv().busy() && s.conv().queue.is_empty() && s.conv().messages.len() == 4);
    let texts: Vec<String> = fake.requests("POST", "/api/chat").iter().map(|r| r.body["text"].as_str().unwrap().to_string()).collect();
    assert_eq!(texts, vec!["one", "two"]);
}

#[test]
fn a_chat_left_while_it_waits_for_the_user_says_so() {
    let fake = Fake::start().with_defaults();
    fake.on(chat(vec![
        Step::Event("start", json!({})),
        Step::Hold("away"),
        Step::Event("tool", json!({"id":"c","name":"write_file","arguments":"{}","status":"running"})),
        Step::Event("approve", json!({"id":"q","callId":"c","name":"write_file","arguments":"{}","risk":"write"})),
        Step::Hold("end"),
        Step::Event("done", json!({"model":"m"})),
    ]));
    let (mut s, rx) = fake.store(None);
    s.send("write it");
    let first = s.current.clone();
    until(&mut s, &rx, |s| !s.conv().fresh);
    s.new_chat();
    assert_ne!(s.current, first);
    fake.release("away");
    until(&mut s, &rx, |s| !s.needs_you().is_empty());
    assert_eq!(s.needs_you(), vec![first.clone()]);
    assert!(s.bell);
    s.open(&first);
    assert!(s.conv().waiting().is_some(), "the card is there when coming back");
    s.approve(false);
    until(&mut s, &rx, |_| !fake.requests("POST", "/api/approve").is_empty());
    assert_eq!(fake.requests("POST", "/api/approve")[0].body["allow"], false);
    fake.release("end");
    until(&mut s, &rx, |s| !s.conv().busy());
}

#[test]
fn a_dropped_stream_resumes_after_the_last_id() {
    let fake = Fake::start().with_defaults();
    fake.on(chat(vec![Step::Event("start", json!({})), Step::Event("delta", json!({"text":"a"})), Step::Drop]));
    fake.on(|r| {
        (r.method == "GET" && r.path.starts_with("/api/stream") && r.path.contains("after=2")).then(|| {
            Reply::Sse(vec![
                Step::Event("start", json!({})),
                Step::Event("delta", json!({"text":"a"})),
                Step::Event("delta", json!({"text":"b"})),
                Step::Event("done", json!({"model":"m"})),
            ])
        })
    });
    let (mut s, rx) = fake.store(None);
    s.send("q");
    until(&mut s, &rx, |s| !s.conv().busy());
    assert_eq!(s.conv().messages[1].text, "ab");
    assert!(!s.conv().messages[1].error);
    assert!(fake.requests("GET", "/api/stream").iter().any(|r| r.path.contains("after=2")));
}

#[test]
fn a_skill_line_is_asked_and_reset_clears() {
    let fake = Fake::start().with_defaults();
    fake.on(|r| {
        (r.method == "POST" && r.path == "/api/command" && r.body["line"] == "review x")
            .then(|| Reply::Sse(vec![Step::Event("prompt", json!({"line":"/review x"}))]))
    });
    fake.on(chat(vec![Step::Event("start", json!({})), Step::Event("done", json!({"model":"m"}))]));
    let (mut s, rx) = fake.store(None);
    s.send("/review x");
    until(&mut s, &rx, |s| !s.conv().busy() && s.conv().messages.len() == 2);
    assert_eq!(s.conv().messages[0].text, "/review x");
    assert_eq!(fake.requests("POST", "/api/chat")[0].body["text"], "/review x");
    s.send("/reset");
    until(&mut s, &rx, |s| !s.conv().busy());
    assert!(s.conv().messages.is_empty());
}

#[test]
fn command_errors_show_in_its_output() {
    let fake = Fake::start().with_defaults();
    fake.on(|r| (r.path == "/api/command").then(|| Reply::Sse(vec![Step::Event("unknown", json!({"name":"nope"}))])));
    let (mut s, rx) = fake.store(None);
    s.send("/nope");
    until(&mut s, &rx, |s| !s.conv().busy());
    let m = &s.conv().messages[0];
    assert!(m.error);
    assert_eq!(m.text, "Unknown command /nope. Type /help for the commands available now.");
}

#[test]
fn mode_switches_leave_the_old_mode_first() {
    let fake = Fake::start().with_defaults();
    fake.on(|r| (r.path.starts_with("/api/mode")).then(|| Reply::Json(200, json!({"plan":true,"default":false}))));
    let (mut s, rx) = fake.store(None);
    until(&mut s, &rx, |s| s.conv().mode() == Some(Mode::Plan));
    s.set_mode(Mode::Auto);
    until(&mut s, &rx, |_| fake.requests("POST", "/api/command").len() == 2);
    let lines: Vec<_> = fake.requests("POST", "/api/command").iter().map(|r| r.body["line"].as_str().unwrap().to_string()).collect();
    assert_eq!(lines, vec!["plan off", "bypass on"]);
}

#[test]
fn deleting_a_running_chat_is_refused_by_the_server() {
    let fake = Fake::start().with_defaults();
    fake.on(|r| (r.method == "DELETE").then(|| Reply::Json(409, json!({"error":"running"}))));
    fake.on(|r| (r.path == "/api/conversations").then(|| Reply::Json(200, json!([{"id":"other","title":"Other","updatedAt":1}]))));
    let (mut s, rx) = fake.store(None);
    until(&mut s, &rx, |s| s.conversations.len() == 1);
    s.remove("other");
    assert!(s.conversations.is_empty(), "removed from the list at once");
    until(&mut s, &rx, |s| !s.notices.is_empty());
    assert_eq!(s.take_notices(), vec!["An answer is still running in that chat."]);
}

#[test]
fn opening_a_chat_attaches_to_its_answer_in_flight() {
    let fake = Fake::start().with_defaults();
    fake.on(|r| {
        (r.path.starts_with("/api/history"))
            .then(|| Reply::Json(200, json!([{"role":"user","content":"old"},{"role":"assistant","content":"done"}])))
    });
    fake.on(|r| {
        (r.path.starts_with("/api/stream") && r.path.contains("after=0")).then(|| {
            Reply::Sse(vec![
                Step::Event("start", json!({"question":"new q","startedAt":5})),
                Step::Event("delta", json!({"text":"working"})),
                Step::Hold("end"),
                Step::Event("done", json!({"model":"m"})),
            ])
        })
    });
    let (mut s, rx) = fake.store(Some("abc"));
    until(&mut s, &rx, |s| s.conv().busy() && s.conv().messages.len() == 4 && s.conv().messages[3].text == "working");
    assert_eq!(s.conv().messages[2].text, "new q");
    assert_eq!(s.conv().messages[3].started_at, Some(5));
    fake.release("end");
    until(&mut s, &rx, |s| !s.conv().busy());
    settle(&mut s, &rx, 50);
}
