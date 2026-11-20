//! The whole screen drawn on a test terminal, against the scripted server, at a narrow and a wide size.

mod common;

use common::{Fake, Reply, Step, until};
use panic_tui::ui::App;
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use ratatui::crossterm::event::{Event, KeyCode, KeyEvent, KeyModifiers};
use serde_json::json;

fn screen(app: &mut App, w: u16, h: u16) -> String {
    let mut t = Terminal::new(TestBackend::new(w, h)).unwrap();
    t.draw(|f| app.draw(f)).unwrap();
    let buf = t.backend().buffer().clone();
    (0..h).map(|y| (0..w).map(|x| buf[(x, y)].symbol().to_string()).collect::<String>().trim_end().to_string()).collect::<Vec<_>>().join("\n")
}

fn key(app: &mut App, code: KeyCode, m: KeyModifiers) {
    app.input(Event::Key(KeyEvent::new(code, m)));
}

#[test]
fn the_card_that_waits_takes_the_composers_place() {
    let fake = Fake::start().with_defaults();
    let edit = json!({"path":"a.rs","old_str":"x","new_str":"y"}).to_string();
    let steps = vec![
        Step::Event("start", json!({})),
        Step::Event("delta", json!({"text":"Editing **a.rs**."})),
        Step::Event("tool", json!({"id":"e","name":"edit_file","arguments":edit,"status":"running"})),
        Step::Event("approve", json!({"id":"q","callId":"e","name":"edit_file","arguments":edit,"risk":"write"})),
        Step::Hold("allowed"),
        Step::Event("tool", json!({"id":"e","name":"edit_file","arguments":edit,"status":"ok","content":"edited (first at line 3)"})),
        Step::Event("done", json!({"model":"m","toolCalls":1})),
    ];
    fake.on(move |r| (r.path == "/api/chat").then(|| Reply::Sse(steps.clone())));
    let (store, rx) = fake.store(None);
    let mut app = App::new(store, &fake.url);
    for c in "edit it".chars() {
        key(&mut app, KeyCode::Char(c), KeyModifiers::NONE);
    }
    key(&mut app, KeyCode::Enter, KeyModifiers::NONE);
    until(&mut app.store, &rx, |s| s.conv().waiting().is_some());
    app.after_update();

    for (w, h) in [(80, 24), (160, 40)] {
        let s = screen(&mut app, w, h);
        assert!(s.contains("❯ edit it"), "{s}");
        assert!(s.contains("● Editing a.rs."), "{s}");
        assert!(s.contains("Panic wants to run edit_file · Writes files"), "{s}");
        assert!(s.contains("  - x") && s.contains("  + y"), "the change is previewed: {s}");
        assert!(s.contains("[y] Allow   [n] Deny"), "{s}");
        assert!(s.contains("ask first (⇧Tab)"), "{s}");
        assert!(!s.contains("Message Panic"), "the composer gives way to the card: {s}");
    }

    key(&mut app, KeyCode::Char('y'), KeyModifiers::NONE);
    until(&mut app.store, &rx, |_| !fake.requests("POST", "/api/approve").is_empty());
    fake.release("allowed");
    until(&mut app.store, &rx, |s| !s.conv().busy());
    let s = screen(&mut app, 80, 24);
    assert!(s.contains("✓ edit_file  a.rs"), "{s}");
    assert!(s.contains("3 - x"), "the last turn shows its diff: {s}");
    assert!(s.contains("─ 1 file +1 −1 · m · 1 tool"), "{s}");
    assert!(s.contains("❯ Message Panic"), "{s}");
}

#[test]
fn the_palette_finds_commands_and_actions() {
    let fake = Fake::start().with_defaults();
    let (store, rx) = fake.store(None);
    let mut app = App::new(store, &fake.url);
    until(&mut app.store, &rx, |s| s.state.is_some());
    key(&mut app, KeyCode::Char('k'), KeyModifiers::CONTROL);
    let s = screen(&mut app, 100, 30);
    assert!(s.contains("Go to") && s.contains("New chat") && s.contains("/help"), "{s}");
    for c in "help".chars() {
        key(&mut app, KeyCode::Char(c), KeyModifiers::NONE);
    }
    key(&mut app, KeyCode::Enter, KeyModifiers::NONE);
    assert_eq!(app.composer.text, "/help ");
}
