//! panic-tui: Panic in the terminal. See README.md.

use panic_tui::api::{Backend, HttpBackend};
use panic_tui::core::format::text_of;
use panic_tui::core::session::{new_id, valid_id};
use panic_tui::state::{Msg, Store};
use panic_tui::ui::{App, Out, term};
use ratatui::crossterm::event::{
    self, DisableBracketedPaste, DisableMouseCapture, EnableBracketedPaste, EnableMouseCapture, Event, KeyboardEnhancementFlags,
    PopKeyboardEnhancementFlags, PushKeyboardEnhancementFlags,
};
use ratatui::crossterm::execute;
use ratatui::crossterm::terminal::supports_keyboard_enhancement;
use std::io::{self, Write};
use std::process::{Child, Command, ExitCode, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{RecvTimeoutError, channel};
use std::thread;
use std::time::{Duration, Instant};

const USAGE: &str = "usage: panic-tui [--url URL] [--session ID] [--spawn PANIC]
       panic-tui [--url URL] ask TEXT...

  --url URL       where Panic serves its web API (default http://127.0.0.1:8080; run `panic --serve`)
  --session ID    open this chat instead of a new one
  --spawn PANIC   start `PANIC --serve` (the Panic launcher, e.g. app/build/install/app/bin/app) and stop it on exit
  ask TEXT...     ask once, print the answer and exit (tool calls that need approval are denied)";

struct Args {
    url: String,
    session: Option<String>,
    spawn: Option<String>,
    ask: Option<String>,
}

/// Err(text, ok): text to print, and whether that is a normal end (help, version).
fn args() -> Result<Args, (String, bool)> {
    let mut a = Args { url: "http://127.0.0.1:8080".into(), session: None, spawn: None, ask: None };
    let mut it = std::env::args().skip(1);
    let usage = || (USAGE.to_string(), false);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "-h" | "--help" => return Err((USAGE.into(), true)),
            "-V" | "--version" => return Err((format!("panic-tui {}", env!("CARGO_PKG_VERSION")), true)),
            "--url" => a.url = it.next().ok_or_else(usage)?,
            "--session" => {
                let s = it.next().ok_or_else(usage)?;
                if !valid_id(&s) {
                    return Err((format!("not a session id: {s}"), false));
                }
                a.session = Some(s);
            }
            "--spawn" => a.spawn = Some(it.next().ok_or_else(usage)?),
            "ask" => {
                let text: Vec<String> = it.by_ref().collect();
                if text.is_empty() {
                    return Err(usage());
                }
                a.ask = Some(text.join(" "));
            }
            other => return Err((format!("unknown argument {other}\n{USAGE}"), false)),
        }
    }
    Ok(a)
}

fn main() -> ExitCode {
    let a = match args() {
        Ok(a) => a,
        Err((msg, ok)) => {
            if ok {
                println!("{msg}");
                return ExitCode::SUCCESS;
            }
            eprintln!("{msg}");
            return ExitCode::from(2);
        }
    };
    let mut server = match &a.spawn {
        Some(cmd) => match spawn(cmd, &a.url) {
            Ok(c) => Some(c),
            Err(e) => {
                eprintln!("panic-tui: {e}");
                return ExitCode::FAILURE;
            }
        },
        None => None,
    };
    let code = match &a.ask {
        Some(text) => ask(&a.url, text),
        None => match run(&a) {
            Ok(()) => ExitCode::SUCCESS,
            Err(e) => {
                eprintln!("panic-tui: {e}");
                ExitCode::FAILURE
            }
        },
    };
    if let Some(c) = server.as_mut() {
        let _ = c.kill();
        let _ = c.wait();
    }
    code
}

/// Starts `cmd --serve` with its output in a log file, and waits until it answers.
fn spawn(cmd: &str, url: &str) -> Result<Child, String> {
    let log = std::env::temp_dir().join(format!("panic-tui-serve-{}.log", std::process::id()));
    let file = std::fs::File::create(&log).map_err(|e| e.to_string())?;
    let err = file.try_clone().map_err(|e| e.to_string())?;
    let mut child = Command::new(cmd)
        .arg("--serve")
        .stdin(Stdio::null())
        .stdout(file)
        .stderr(err)
        .spawn()
        .map_err(|e| format!("cannot start {cmd}: {e}"))?;
    let backend = HttpBackend::new(url);
    let end = Instant::now() + Duration::from_secs(90);
    eprint!("starting Panic…");
    while Instant::now() < end {
        if backend.state().is_ok() {
            eprintln!(" ready");
            return Ok(child);
        }
        if let Ok(Some(status)) = child.try_wait() {
            return Err(format!("{cmd} --serve exited ({status}); see {}", log.display()));
        }
        thread::sleep(Duration::from_millis(250));
    }
    let _ = child.kill();
    Err(format!("{cmd} --serve did not answer at {url} in 90 s; see {}", log.display()))
}

/// One question, its answer printed as it streams; tool calls go to stderr.
fn ask(url: &str, text: &str) -> ExitCode {
    let backend = HttpBackend::new(url);
    let session = new_id();
    let events = match backend.chat(&session, text) {
        Ok(e) => e,
        Err(e) => {
            eprintln!("panic-tui: {e}");
            return ExitCode::FAILURE;
        }
    };
    let mut out = io::stdout();
    let mut code = ExitCode::SUCCESS;
    for e in events {
        let Ok(e) = e else {
            eprintln!("\npanic-tui: lost the connection");
            return ExitCode::FAILURE;
        };
        let d = &e.data;
        match e.event.as_str() {
            "delta" => {
                let _ = write!(out, "{}", text_of(d.get("text")));
                let _ = out.flush();
            }
            "tool" if d.get("status").and_then(|s| s.as_str()) != Some("running") => {
                eprintln!("\n[{}] {}", text_of(d.get("name")), text_of(d.get("status")));
            }
            "approve" => {
                eprintln!("\n[denied] {} needs approval: run it in the TUI, or set permissions.bypassPermissions", text_of(d.get("name")));
                let _ = backend.approve(&session, &text_of(d.get("id")), false);
            }
            "plan" => {
                let _ = backend.plan(&session, &text_of(d.get("id")), false, "No one can approve a plan in one-shot mode.");
            }
            "question" => {
                let n = d.get("questions").and_then(|q| q.as_array()).map_or(0, Vec::len);
                let _ = backend.answer(&session, &text_of(d.get("id")), &vec![(vec![], String::new()); n]);
            }
            "done" => {
                let _ = writeln!(out);
                eprintln!("{}", panic_tui::core::reply::meta(d));
            }
            "error" | "unavailable" => {
                let why = if e.event == "error" { text_of(d.get("message")) } else { "no LLM is connected".into() };
                eprintln!("\npanic-tui: {why}");
                code = ExitCode::FAILURE;
            }
            _ => {}
        }
    }
    code
}

enum Input {
    Term(Event),
    App(Msg),
}

fn run(a: &Args) -> io::Result<()> {
    let (tx, rx) = channel::<Input>();
    let app_tx = tx.clone();
    let sink: panic_tui::state::Sink = Arc::new(move |m| app_tx.send(Input::App(m)).is_ok());
    let backend: Arc<dyn Backend> = Arc::new(HttpBackend::new(&a.url));
    let store = Store::new(backend, sink, a.session.clone());
    let mut app = App::new(store, &a.url);

    // keys are read on their own thread, which stands still while an editor owns the terminal
    let paused = Arc::new(AtomicBool::new(false));
    let p = paused.clone();
    thread::spawn(move || {
        loop {
            if p.load(Ordering::Relaxed) {
                thread::sleep(Duration::from_millis(50));
                continue;
            }
            match event::poll(Duration::from_millis(100)) {
                Ok(true) => match event::read() {
                    Ok(e) => {
                        if tx.send(Input::Term(e)).is_err() {
                            return;
                        }
                    }
                    Err(_) => return,
                },
                Ok(false) => {}
                Err(_) => return,
            }
        }
    });

    let mut terminal = start()?;
    let result = (|| -> io::Result<()> {
        loop {
            if let Some(t) = app.window_title() {
                term::title(&t);
            }
            terminal.draw(|f| app.draw(f))?;
            let wait = if app.animating() { Duration::from_millis(100) } else { Duration::from_secs(1) };
            match rx.recv_timeout(wait) {
                Ok(i) => handle(&mut app, i),
                Err(RecvTimeoutError::Timeout) => app.tick(),
                Err(RecvTimeoutError::Disconnected) => return Ok(()),
            }
            // what arrived meanwhile is applied before the next frame (a fast stream draws once per frame)
            let frame_end = Instant::now() + Duration::from_millis(30);
            while let Ok(i) = rx.try_recv() {
                handle(&mut app, i);
                if Instant::now() > frame_end {
                    break;
                }
            }
            if app.animating() {
                app.tick();
            }
            for o in std::mem::take(&mut app.out) {
                match o {
                    Out::Quit => return Ok(()),
                    Out::Bell => term::bell(),
                    Out::Copy(t) => term::copy(&t),
                    Out::Editor => {
                        paused.store(true, Ordering::Relaxed);
                        thread::sleep(Duration::from_millis(150));
                        stop();
                        let text = edit(&app.composer.text);
                        terminal = start()?;
                        terminal.clear()?;
                        paused.store(false, Ordering::Relaxed);
                        if let Some(t) = text {
                            app.composer.set(t.trim_end_matches('\n'));
                        }
                    }
                }
            }
        }
    })();
    stop();
    result
}

fn handle(app: &mut App, i: Input) {
    match i {
        Input::Term(e) => app.input(e),
        Input::App(m) => app.store.update(m),
    }
    app.after_update();
}

fn start() -> io::Result<ratatui::DefaultTerminal> {
    let terminal = ratatui::init();
    let mut out = io::stdout();
    execute!(out, EnableMouseCapture, EnableBracketedPaste)?;
    // terminals that can tell Shift+Enter from Enter (the kitty protocol) say so
    if supports_keyboard_enhancement().unwrap_or(false) {
        execute!(out, PushKeyboardEnhancementFlags(KeyboardEnhancementFlags::DISAMBIGUATE_ESCAPE_CODES))?;
    }
    Ok(terminal)
}

fn stop() {
    let mut out = io::stdout();
    if supports_keyboard_enhancement().unwrap_or(false) {
        let _ = execute!(out, PopKeyboardEnhancementFlags);
    }
    let _ = execute!(out, DisableMouseCapture, DisableBracketedPaste);
    ratatui::restore();
}

/// The prompt written in `$VISUAL` or `$EDITOR` (vi when neither is set); None when it was not saved.
fn edit(text: &str) -> Option<String> {
    let path = std::env::temp_dir().join(format!("panic-tui-{}.md", std::process::id()));
    std::fs::write(&path, text).ok()?;
    let editor = std::env::var("VISUAL").or_else(|_| std::env::var("EDITOR")).unwrap_or_else(|_| "vi".into());
    let mut parts = editor.split_whitespace();
    let status = Command::new(parts.next()?).args(parts).arg(&path).status().ok()?;
    let out = if status.success() { std::fs::read_to_string(&path).ok() } else { None };
    let _ = std::fs::remove_file(&path);
    out
}
