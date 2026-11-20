//! The only layer that calls the [`Backend`]: conversations, their answers in flight and what the server
//! says, ported from ui-v2's `state/chat.svelte.ts` and extended:
//!
//! - every chat with an answer in flight stays in memory with its stream open, so leaving a chat never
//!   loses it, and one that waits for the user is known at once (`needs_you`);
//! - a message sent while an answer runs waits in the chat's queue and goes when it ends.
//!
//! Calls run on worker threads and come back as [`Msg`]s through one channel; [`Store::update`] applies them.
//! A stream's events carry the generation of the run they belong to, so a run that was left behind (an
//! aborted command) can never touch the chat again.

mod mode;

pub use mode::{Mode, mode_commands};

use crate::api::{Answers, ApiError, Backend, Events, Model, SettingsChange, State, Toggle, quote_arg};
use crate::core::conversations::{ConversationInfo, Workspaces, without};
use crate::core::format::duration;
use crate::core::reply::{self, Answer, Effect, HistoryMessage, Message, Part, Role, from_history};
use crate::core::session::new_id;
use crate::core::sse::SseEvent;
use crate::core::trace::Span;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

/// Results of work done off the main thread.
pub enum Msg {
    Event {
        session: String,
        generation: u64,
        event: SseEvent,
    },
    /// The stream ended; `error` when it broke or could not start.
    StreamEnd {
        session: String,
        generation: u64,
        error: Option<ApiError>,
    },
    /// A re-attach found the answer (`Some`) or found it over (`None`).
    Reattached {
        session: String,
        generation: u64,
        result: Result<Option<Events>, ApiError>,
    },
    State(Result<State, ApiError>),
    Conversations(Result<Vec<ConversationInfo>, ApiError>),
    Workspaces(Result<Option<Workspaces>, ApiError>),
    History {
        session: String,
        result: Result<Vec<HistoryMessage>, ApiError>,
    },
    /// An answer in flight found when opening a chat.
    Running {
        session: String,
        events: Events,
    },
    Toggles {
        session: String,
        permissions: Result<Option<Toggle>, ApiError>,
        mode: Result<Option<Toggle>, ApiError>,
    },
    Models(Result<Vec<Model>, ApiError>),
    Trace {
        session: String,
        id: String,
        result: Result<Vec<Span>, ApiError>,
    },
    /// A card answer was sent; `error` when it failed (404s are silent).
    Answered {
        session: String,
        error: Option<ApiError>,
    },
    Deleted {
        session: String,
        result: Result<(), ApiError>,
    },
    /// Commands run for the user without a message (mode switches, projects); `error` is the first failure.
    Quiet {
        session: String,
        error: Option<String>,
        then: Then,
    },
    Settings(Result<State, ApiError>),
}

/// What to reload after quiet commands.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Then {
    Toggles,
    Workspaces,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RunKind {
    Ask,
    Command(String),
}

/// An answer or a command in flight in a chat.
pub struct Run {
    pub generation: u64,
    pub kind: RunKind,
    /// The index of the message it fills.
    pub reply: usize,
    answer: Answer,
    attempts: u32,
    /// The question of a resumed answer, filled from `start`.
    question: Option<usize>,
}

/// One chat: its messages and what runs in it.
pub struct Conv {
    pub id: String,
    /// No message was sent yet (the server does not know it).
    pub fresh: bool,
    pub messages: Vec<Message>,
    pub run: Option<Run>,
    /// Messages sent while it ran, sent in order once it ends.
    pub queue: Vec<String>,
    pub permissions: Option<Toggle>,
    pub mode: Option<Toggle>,
    pub loading: bool,
    /// An answer ended while the user was elsewhere.
    pub unseen: bool,
    next_id: u64,
}

impl Conv {
    fn new(id: &str, fresh: bool) -> Self {
        Conv {
            id: id.into(),
            fresh,
            messages: vec![],
            run: None,
            queue: vec![],
            permissions: None,
            mode: None,
            loading: !fresh,
            unseen: false,
            next_id: 1,
        }
    }

    /// An empty chat, for views' tests.
    #[doc(hidden)]
    pub fn for_tests(id: &str) -> Self {
        Conv::new(id, true)
    }

    pub fn busy(&self) -> bool {
        self.run.is_some()
    }

    fn push(&mut self, mut m: Message) -> usize {
        m.id = self.next_id;
        self.next_id += 1;
        self.messages.push(m);
        self.messages.len() - 1
    }

    /// The message whose cards wait for the user, if any.
    pub fn waiting(&self) -> Option<&Message> {
        self.run.as_ref().map(|r| &self.messages[r.reply]).filter(|m| m.needs_user())
    }

    pub fn mode(&self) -> Option<Mode> {
        Mode::of(self.mode, self.permissions)
    }
}

/// Context use of the current chat: tokens of its last model call against the model's window.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ContextUse {
    pub used: u64,
    pub window: u64,
}

pub enum TraceLoad {
    Loading,
    Ready(Vec<Span>),
    Failed(String),
}

/// Where workers hand their results; false once nobody listens.
pub type Sink = Arc<dyn Fn(Msg) -> bool + Send + Sync>;

pub struct Store {
    backend: Arc<dyn Backend>,
    tx: Sink,
    clock: fn() -> i64,
    generation: u64,
    pub current: String,
    pub convs: HashMap<String, Conv>,
    pub state: Option<State>,
    /// Why the server cannot be reached, while it cannot.
    pub offline: Option<String>,
    pub conversations: Vec<ConversationInfo>,
    pub workspaces: Option<Workspaces>,
    pub models: Option<Result<Vec<Model>, String>>,
    pub traces: HashMap<(String, String), TraceLoad>,
    /// Tokens of the last model call of each traced answer, by trace id.
    last_call: HashMap<String, u64>,
    /// Short messages for the user (a failed delete, a failed mode switch).
    pub notices: Vec<String>,
    /// Ring the terminal bell: a chat in the background now needs the user.
    pub bell: bool,
    /// The folder the terminal runs in: a chat started here works in its project.
    pub cwd: Option<String>,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// Text of an error for the user: an API error's message, "Cannot reach the Panic server." for the network.
fn message(e: &ApiError) -> String {
    e.message.clone()
}

impl Store {
    pub fn new(backend: Arc<dyn Backend>, tx: Sink, session: Option<String>) -> Self {
        Self::with_clock(backend, tx, session, now_ms)
    }

    pub fn with_clock(backend: Arc<dyn Backend>, tx: Sink, session: Option<String>, clock: fn() -> i64) -> Self {
        let mut s = Store {
            backend,
            tx,
            clock,
            generation: 0,
            current: String::new(),
            convs: HashMap::new(),
            state: None,
            offline: None,
            conversations: vec![],
            workspaces: None,
            models: None,
            traces: HashMap::new(),
            last_call: HashMap::new(),
            notices: vec![],
            bell: false,
            cwd: std::env::current_dir().ok().map(|d| d.to_string_lossy().into_owned()),
        };
        match session {
            Some(id) => s.open(&id),
            None => s.new_chat(),
        }
        s.refresh_state();
        s.refresh_list();
        s
    }

    pub fn now(&self) -> i64 {
        (self.clock)()
    }

    pub fn conv(&self) -> &Conv {
        &self.convs[&self.current]
    }

    fn conv_mut(&mut self) -> &mut Conv {
        self.convs.get_mut(&self.current).expect("the current chat is always loaded")
    }

    fn spawn(&self, job: impl FnOnce(&dyn Backend) -> Msg + Send + 'static) {
        let (backend, tx) = (self.backend.clone(), self.tx.clone());
        thread::spawn(move || {
            tx(job(backend.as_ref()));
        });
    }

    /// Forwards every event of `events` as [`Msg::Event`], then [`Msg::StreamEnd`].
    fn pump(tx: &Sink, session: &str, generation: u64, events: Events) {
        for e in events {
            match e {
                Ok(event) => {
                    if !tx(Msg::Event { session: session.into(), generation, event }) {
                        return;
                    }
                }
                Err(error) => {
                    tx(Msg::StreamEnd { session: session.into(), generation, error: Some(error) });
                    return;
                }
            }
        }
        tx(Msg::StreamEnd { session: session.into(), generation, error: None });
    }

    fn stream(&mut self, session: &str, open: impl FnOnce(&dyn Backend) -> Result<Events, ApiError> + Send + 'static) -> u64 {
        self.generation += 1;
        let generation = self.generation;
        let (backend, tx, session) = (self.backend.clone(), self.tx.clone(), session.to_string());
        thread::spawn(move || match open(backend.as_ref()) {
            Ok(events) => Self::pump(&tx, &session, generation, events),
            Err(error) => {
                tx(Msg::StreamEnd { session, generation, error: Some(error) });
            }
        });
        generation
    }

    pub fn refresh_state(&self) {
        self.spawn(|b| Msg::State(b.state()));
    }

    pub fn refresh_list(&self) {
        self.spawn(|b| Msg::Conversations(b.conversations()));
        self.spawn(|b| Msg::Workspaces(b.workspaces()));
    }

    fn refresh_toggles(&self, session: &str) {
        let s = session.to_string();
        self.spawn(move |b| Msg::Toggles { permissions: b.permissions(&s), mode: b.mode(&s), session: s });
    }

    // ---- chats ----

    /// Starts a new chat, in the project of the terminal's folder when it is one.
    pub fn new_chat(&mut self) {
        if let Some(c) = self.convs.get(&self.current)
            && c.fresh
            && c.messages.is_empty()
            && !c.busy()
        {
            self.refresh_toggles(&self.current.clone());
            return;
        }
        let id = new_id();
        self.leave();
        self.convs.insert(id.clone(), Conv::new(&id, true));
        self.current = id.clone();
        self.refresh_toggles(&id);
        if let Some(path) = self.workspaces.as_ref().zip(self.cwd.as_ref()).and_then(|(w, d)| w.project_at(d)).map(|p| p.path.clone()) {
            self.quiet(&id, vec![format!("workspace use {}", quote_arg(&path))], Then::Workspaces);
        }
    }

    /// Shows chat `id`; one kept in memory (its answer runs) is shown as it is.
    pub fn open(&mut self, id: &str) {
        if self.current == id && self.convs.contains_key(id) {
            return;
        }
        self.leave();
        self.current = id.to_string();
        if let Some(c) = self.convs.get_mut(id) {
            c.unseen = false;
            return;
        }
        self.convs.insert(id.into(), Conv::new(id, false));
        self.load(id);
    }

    /// Loads a stored chat: its history, its switches, and the answer in flight if one runs.
    fn load(&mut self, id: &str) {
        let s = id.to_string();
        self.spawn(move |b| Msg::History { result: b.history(&s), session: s });
        self.refresh_toggles(id);
    }

    /// Leaving a chat keeps it in memory only while something runs in it.
    fn leave(&mut self) {
        if let Some(c) = self.convs.get(&self.current)
            && !c.busy()
            && c.queue.is_empty()
        {
            self.convs.remove(&self.current.clone());
        }
    }

    /// Reloads the current chat (after the server came back).
    pub fn retry(&mut self) {
        self.offline = None;
        self.refresh_state();
        self.refresh_list();
        let id = self.current.clone();
        let c = self.conv();
        if !c.fresh && !c.busy() {
            self.load(&id);
        }
    }

    /// Chats in the background whose answer waits for the user, oldest question first.
    pub fn needs_you(&self) -> Vec<String> {
        let mut ids: Vec<&Conv> = self.convs.values().filter(|c| c.id != self.current && c.waiting().is_some()).collect();
        ids.sort_by_key(|c| c.run.as_ref().map(|r| r.generation));
        ids.iter().map(|c| c.id.clone()).collect()
    }

    pub fn remove(&mut self, id: &str) {
        if id == self.current && self.conv().busy() {
            self.notices.push("Stop the answer before deleting this chat.".into());
            return;
        }
        self.conversations = without(&self.conversations, id);
        if id == self.current {
            self.convs.remove(id);
            self.current.clear();
            self.new_chat();
        }
        let s = id.to_string();
        self.spawn(move |b| Msg::Deleted { result: b.delete_conversation(&s), session: s });
    }

    // ---- sending ----

    /// Sends what the user typed: a `/command`, or a question. While something runs it waits in the queue.
    pub fn send(&mut self, text: &str) {
        let text = text.trim();
        if text.is_empty() {
            return;
        }
        if self.conv().busy() {
            self.conv_mut().queue.push(text.into());
            return;
        }
        if text.starts_with('/') { self.command(text) } else { self.ask(text) }
    }

    fn ask(&mut self, text: &str) {
        let now = self.now();
        let c = self.conv_mut();
        c.push(Message::new(0, Role::User, text));
        let mut reply = Message::new(0, Role::Assistant, "");
        reply.streaming = true;
        reply.started_at = Some(now);
        let reply = c.push(reply);
        let (session, t) = (self.current.clone(), text.to_string());
        let s2 = session.clone();
        let generation = self.stream(&session, move |b| b.chat(&s2, &t));
        self.conv_mut().run = Some(Run { generation, kind: RunKind::Ask, reply, answer: Answer::new(false), attempts: 0, question: None });
    }

    fn command(&mut self, line: &str) {
        let c = self.conv_mut();
        let mut m = Message::new(0, Role::Command, "");
        m.meta = Some(line.into());
        m.streaming = true;
        let reply = c.push(m);
        let (session, l) = (self.current.clone(), line.trim_start_matches('/').to_string());
        let s2 = session.clone();
        let generation = self.stream(&session, move |b| b.command(&s2, &l));
        self.conv_mut().run =
            Some(Run { generation, kind: RunKind::Command(line.into()), reply, answer: Answer::new(false), attempts: 0, question: None });
    }

    /// Takes the last queued message back into the composer.
    pub fn unqueue(&mut self) -> Option<String> {
        self.conv_mut().queue.pop()
    }

    /// Stops what runs in the current chat: the server stops an answer (its stream then ends `cancelled`);
    /// a command is left behind.
    pub fn stop(&mut self) {
        let id = self.current.clone();
        let Some(run) = self.conv().run.as_ref() else { return };
        match run.kind {
            RunKind::Ask => {
                let s = id.clone();
                self.spawn(move |b| match b.stop(&s) {
                    Ok(()) => Msg::Answered { session: s, error: None },
                    // nothing to stop on the server: end it here
                    Err(_) => Msg::StreamEnd { session: s, generation: 0, error: None },
                });
            }
            RunKind::Command(_) => self.abandon(&id, " · stopped"),
        }
    }

    /// Ends the chat's run here, without the server: its later events are ignored.
    fn abandon(&mut self, session: &str, note: &str) {
        let now = self.now();
        let Some(c) = self.convs.get_mut(session) else { return };
        let Some(mut run) = c.run.take() else { return };
        let m = &mut c.messages[run.reply];
        run.answer.finish(m, now);
        m.streaming = false;
        m.clear_cards();
        m.meta = Some(format!("{}{note}", m.meta.clone().unwrap_or_default()));
        self.after_run(session);
    }

    // ---- cards ----

    fn card_message(&mut self) -> Option<&mut Message> {
        let c = self.convs.get_mut(&self.current)?;
        let r = c.run.as_ref()?.reply;
        Some(&mut c.messages[r])
    }

    pub fn approve(&mut self, allow: bool) {
        let session = self.current.clone();
        let Some(m) = self.card_message() else { return };
        let Some(a) = m.approval.clone() else { return };
        reply::clear_approval(m, |x| x.id == a.id);
        self.spawn(move |b| Msg::Answered { error: b.approve(&session, &a.id, allow).err(), session });
    }

    pub fn answer_plan(&mut self, approve: bool, feedback: &str) {
        let session = self.current.clone();
        let Some(m) = self.card_message() else { return };
        let Some(p) = m.plan.take() else { return };
        let f = feedback.to_string();
        self.spawn(move |b| Msg::Answered { error: b.plan(&session, &p.id, approve, &f).err(), session });
    }

    pub fn answer_questions(&mut self, answers: Answers) {
        let session = self.current.clone();
        let Some(m) = self.card_message() else { return };
        let Some(q) = m.question.take() else { return };
        self.spawn(move |b| Msg::Answered { error: b.answer(&session, &q.id, &answers).err(), session });
    }

    // ---- switches and settings ----

    /// Runs commands for the user without showing them, one after the other.
    fn quiet(&self, session: &str, lines: Vec<String>, then: Then) {
        let s = session.to_string();
        self.spawn(move |b| {
            let mut error = None;
            for line in lines {
                match b.command(&s, &line) {
                    Ok(events) => {
                        for e in events {
                            match e {
                                Ok(ev) => match ev.event.as_str() {
                                    "error" => error = Some(crate::core::format::text_of(ev.data.get("message"))),
                                    "usage" => error = Some(format!("Usage: /{}", crate::core::format::text_of(ev.data.get("usage")))),
                                    "unknown" => {
                                        error = Some(format!("Unknown command /{}.", crate::core::format::text_of(ev.data.get("name"))))
                                    }
                                    _ => {}
                                },
                                Err(e) => error = Some(message(&e)),
                            }
                        }
                    }
                    Err(e) => error = Some(message(&e)),
                }
                if error.is_some() {
                    break;
                }
            }
            Msg::Quiet { session: s, error, then }
        });
    }

    /// Switches the current chat to `target`, leaving the old mode first.
    pub fn set_mode(&mut self, target: Mode) {
        let Some(from) = self.conv().mode() else { return };
        let lines = mode_commands(from, target);
        if !lines.is_empty() {
            self.quiet(&self.current.clone(), lines, Then::Toggles);
        }
    }

    pub fn workspace(&mut self, line: String) {
        self.quiet(&self.current.clone(), vec![line], Then::Workspaces);
    }

    pub fn settings(&mut self, change: SettingsChange) {
        self.spawn(move |b| Msg::Settings(b.put_settings(&change)));
    }

    pub fn load_models(&mut self) {
        if matches!(self.models, Some(Ok(_))) {
            return;
        }
        self.spawn(|b| Msg::Models(b.models("")));
    }

    pub fn load_trace(&mut self, id: &str) {
        let key = (self.current.clone(), id.to_string());
        if matches!(self.traces.get(&key), Some(TraceLoad::Ready(_) | TraceLoad::Loading)) {
            return;
        }
        self.traces.insert(key.clone(), TraceLoad::Loading);
        let (s, i) = key;
        self.spawn(move |b| Msg::Trace { result: b.trace(&s, &i), session: s, id: i });
    }

    /// Tokens of the current chat's last model call against the window of the model in use.
    pub fn context_use(&self) -> Option<ContextUse> {
        let model = self.state.as_ref()?.model.as_deref()?;
        let window = match &self.models {
            Some(Ok(list)) => list.iter().find(|m| m.id == model)?.context_length,
            _ => return None,
        };
        let c = self.convs.get(&self.current)?;
        let trace = c.messages.iter().rev().find(|m| m.role == Role::Assistant && m.trace_id.is_some())?.trace_id.as_ref()?;
        let used = *self.last_call.get(trace)?;
        (window > 0).then_some(ContextUse { used, window })
    }

    // ---- messages from workers ----

    pub fn update(&mut self, msg: Msg) {
        match msg {
            Msg::Event { session, generation, event } => self.on_event(&session, generation, event),
            Msg::StreamEnd { session, generation, error } => self.on_end(&session, generation, error),
            Msg::Reattached { session, generation, result } => self.on_reattached(&session, generation, result),
            Msg::State(r) => match r {
                Ok(s) => {
                    self.offline = None;
                    let model_changed = self.state.as_ref().map(|o| &o.model) != Some(&s.model);
                    self.state = Some(s);
                    if model_changed {
                        self.models = None;
                        self.load_models();
                    }
                }
                Err(e) => self.offline = Some(message(&e)),
            },
            Msg::Conversations(r) => match r {
                Ok(list) => self.conversations = list,
                Err(e) if e.is_network() => self.offline = Some(message(&e)),
                Err(_) => {}
            },
            Msg::Workspaces(r) => {
                if let Ok(w) = r {
                    let first = self.workspaces.is_none();
                    self.workspaces = w;
                    // the first answer may come after the chat started: put it in the terminal's project now
                    if first {
                        let c = self.conv();
                        if c.fresh
                            && c.messages.is_empty()
                            && let Some(p) = self.workspaces.as_ref().zip(self.cwd.as_ref()).and_then(|(w, d)| w.project_at(d))
                        {
                            let line = format!("workspace use {}", quote_arg(&p.path));
                            self.quiet(&self.current.clone(), vec![line], Then::Workspaces);
                        }
                    }
                }
            }
            Msg::History { session, result } => self.on_history(&session, result),
            Msg::Running { session, events } => self.on_running(&session, events),
            Msg::Toggles { session, permissions, mode } => {
                if let Some(c) = self.convs.get_mut(&session) {
                    if let Ok(p) = permissions {
                        c.permissions = p;
                    }
                    if let Ok(m) = mode {
                        c.mode = m;
                    }
                }
            }
            Msg::Models(r) => self.models = Some(r.map_err(|e| message(&e))),
            Msg::Trace { session, id, result } => {
                let load = match result {
                    Ok(spans) => {
                        if let Some(t) = last_call_tokens(&spans) {
                            self.last_call.insert(id.clone(), t);
                        }
                        TraceLoad::Ready(spans)
                    }
                    Err(e) if e.status == 404 && e.message == "tracing is off" => {
                        TraceLoad::Failed("Tracing is off: enable the tracing plugin to see where the time went.".into())
                    }
                    Err(e) => TraceLoad::Failed(format!("No trace: {}", e.message)),
                };
                self.traces.insert((session, id), load);
            }
            Msg::Answered { session, error } => {
                if let Some(e) = error.filter(|e| e.status != 404)
                    && let Some(c) = self.convs.get_mut(&session)
                    && let Some(r) = &c.run
                {
                    reply::fail_answer(&mut c.messages[r.reply], &e.message);
                }
                self.refresh_toggles(&session);
            }
            Msg::Deleted { session: _, result } => {
                match result {
                    Err(e) if e.status == 409 => self.notices.push("An answer is still running in that chat.".into()),
                    Err(e) => self.notices.push(e.message),
                    Ok(()) => {}
                }
                self.refresh_list();
            }
            Msg::Quiet { session, error, then } => {
                if let Some(e) = error {
                    self.notices.push(e);
                }
                match then {
                    Then::Toggles => self.refresh_toggles(&session),
                    Then::Workspaces => self.spawn(|b| Msg::Workspaces(b.workspaces())),
                }
            }
            Msg::Settings(r) => match r {
                Ok(s) => self.update(Msg::State(Ok(s))),
                Err(e) => self.notices.push(e.message),
            },
        }
    }

    fn on_history(&mut self, session: &str, result: Result<Vec<HistoryMessage>, ApiError>) {
        let Some(c) = self.convs.get_mut(session) else { return };
        c.loading = false;
        match result {
            Ok(h) => {
                if c.busy() {
                    return; // an answer was attached meanwhile: its events are the newer truth
                }
                let mut n = 0;
                let messages = from_history(&h, || {
                    n += 1;
                    n
                });
                c.next_id = n + 1;
                c.messages = messages;
                let last_trace = c.messages.iter().rev().find_map(|m| m.trace_id.clone());
                if let Some(t) = last_trace {
                    self.load_trace(&t);
                }
                let s = session.to_string();
                let (backend, tx) = (self.backend.clone(), self.tx.clone());
                thread::spawn(move || {
                    if let Ok(Some(events)) = backend.follow(&s, 0) {
                        tx(Msg::Running { session: s, events });
                    }
                });
            }
            Err(e) => {
                if session == self.current {
                    self.offline = Some(message(&e));
                }
            }
        }
    }

    /// Attaches to an answer found running when the chat was opened: its events replay from the start.
    fn on_running(&mut self, session: &str, events: Events) {
        self.generation += 1;
        let generation = self.generation;
        let Some(c) = self.convs.get_mut(session) else { return };
        if c.busy() {
            return;
        }
        // the history may already hold the question, but not yet the answer: the replay brings both
        if matches!(c.messages.last(), Some(m) if m.role == Role::User) {
            c.messages.pop();
        }
        let question = c.push(Message::new(0, Role::User, ""));
        let mut reply = Message::new(0, Role::Assistant, "");
        reply.streaming = true;
        let reply = c.push(reply);
        c.run = Some(Run { generation, kind: RunKind::Ask, reply, answer: Answer::new(true), attempts: 0, question: Some(question) });
        let (tx, s) = (self.tx.clone(), session.to_string());
        thread::spawn(move || Self::pump(&tx, &s, generation, events));
    }

    fn on_event(&mut self, session: &str, generation: u64, event: SseEvent) {
        let now = self.now();
        let current = self.current.clone();
        let Some(c) = self.convs.get_mut(session) else { return };
        let Some(run) = c.run.as_mut().filter(|r| r.generation == generation) else { return };
        let waited = c.messages[run.reply].needs_user();
        if let RunKind::Command(_) = run.kind {
            let m = &mut c.messages[run.reply];
            let d = &event.data;
            let text = |k: &str| crate::core::format::text_of(d.get(k));
            match event.event.as_str() {
                "delta" => m.text.push_str(&text("text")),
                "approve" => reply::ask_approval(m, d),
                "plan" => reply::ask_plan(m, d),
                "question" => reply::ask_user(m, d),
                "tool" => reply::tool_step(m, d, Some(now)),
                "unknown" => fail_command(m, &format!("Unknown command /{}. Type /help for the commands available now.", text("name"))),
                "usage" => fail_command(m, &format!("Usage: /{} ({})", text("usage"), text("message"))),
                "error" => fail_command(m, &text("message")),
                "cancelled" => m.meta = Some(format!("{} · stopped", m.meta.clone().unwrap_or_default())),
                "prompt" => {
                    // a skill: the line is a question, which the chat expands
                    let line = text("line");
                    let idx = run.reply;
                    c.run = None;
                    c.messages.remove(idx);
                    let back = std::mem::replace(&mut self.current, session.to_string());
                    self.ask(&line);
                    self.current = back;
                    return;
                }
                _ => {}
            }
        } else {
            let effect = run.answer.on_event(&mut c.messages[run.reply], &event, now);
            if let Effect::Started(question) = effect {
                if let (Some(q), Some(i)) = (question, run.question)
                    && c.messages[i].text.is_empty()
                {
                    c.messages[i].text = q;
                }
                if c.fresh {
                    c.fresh = false;
                }
                self.spawn(|b| Msg::Conversations(b.conversations()));
            }
        }
        let c = &self.convs[session];
        if session != current && !waited && c.waiting().is_some() {
            self.bell = true;
        }
    }

    fn on_end(&mut self, session: &str, generation: u64, error: Option<ApiError>) {
        let now = self.now();
        let Some(c) = self.convs.get_mut(session) else { return };
        let Some(run) = c.run.as_mut() else { return };
        // generation 0: a stop the server could not do ends the current run
        if generation != 0 && run.generation != generation {
            return;
        }
        // a stream that closed before its last event broke off, as a dropped connection does
        let error = match error {
            None if run.kind == RunKind::Ask && !run.answer.ended() && generation != 0 => Some(ApiError::network()),
            e => e,
        };
        if let Some(e) = &error {
            let resumable = run.kind == RunKind::Ask && run.answer.seq() > 0 && !run.answer.ended() && e.is_network();
            if resumable {
                if run.attempts < 5 {
                    let wait = 500u64 << run.attempts;
                    run.attempts += 1;
                    let (s, after) = (session.to_string(), run.answer.seq());
                    self.spawn(move |b| {
                        thread::sleep(Duration::from_millis(wait));
                        Msg::Reattached { result: b.follow(&s, after), session: s, generation }
                    });
                    return;
                }
                reply::fail_answer(&mut c.messages[run.reply], "Lost the connection to the answer. Press r to try again.");
            } else if !run.answer.ended() {
                let text = if e.is_network() { "Cannot reach the Panic server.".to_string() } else { e.message.clone() };
                let m = &mut c.messages[run.reply];
                if m.role == Role::Command { fail_command(m, &text) } else { reply::fail_answer(m, &text) }
            }
        }
        let run = c.run.take().unwrap();
        let m = &mut c.messages[run.reply];
        let mut answer = run.answer;
        answer.finish(m, now);
        m.streaming = false;
        m.clear_cards();
        if m.role == Role::Assistant
            && let Some(start) = m.started_at
        {
            let took = duration(m.ended_at.unwrap_or(now) - start);
            m.meta = Some(match m.meta.take().filter(|x| !x.is_empty()) {
                Some(meta) => format!("{meta} · {took}"),
                None => took,
            });
        }
        let trace = m.trace_id.clone().filter(|_| m.role == Role::Assistant);
        let reset = matches!(&run.kind, RunKind::Command(l) if l.trim() == "/reset") && !m.error;
        if reset {
            c.messages.clear();
        }
        if session != self.current {
            c.unseen = true;
        }
        if let Some(t) = trace
            && session == self.current
        {
            self.load_trace(&t);
        }
        if error.as_ref().is_some_and(ApiError::is_network) && session == self.current {
            self.offline = Some("Cannot reach the Panic server.".into());
        }
        self.after_run(session);
    }

    /// After a run: the list, the state and the switches may have changed; the next queued message goes.
    fn after_run(&mut self, session: &str) {
        self.refresh_state();
        self.spawn(|b| Msg::Conversations(b.conversations()));
        self.refresh_toggles(session);
        let Some(c) = self.convs.get_mut(session) else { return };
        if c.queue.is_empty() {
            if session != self.current {
                // nothing runs and nobody looks at it: the server holds it now
                if !c.unseen {
                    self.convs.remove(session);
                }
            }
            return;
        }
        let next = c.queue.remove(0);
        let back = std::mem::replace(&mut self.current, session.to_string());
        self.send(&next);
        self.current = back;
    }

    fn on_reattached(&mut self, session: &str, generation: u64, result: Result<Option<Events>, ApiError>) {
        let Some(c) = self.convs.get_mut(session) else { return };
        let Some(run) = c.run.as_mut().filter(|r| r.generation == generation) else { return };
        match result {
            Ok(Some(events)) => {
                run.attempts = 0;
                let (tx, s) = (self.tx.clone(), session.to_string());
                thread::spawn(move || Self::pump(&tx, &s, generation, events));
            }
            Ok(None) => {
                // it ended while we were away: the history holds it
                c.run = None;
                c.loading = true;
                self.load(session);
                self.after_run(session);
            }
            Err(e) => self.on_end(session, generation, Some(e)),
        }
    }

    /// The items of the last `todo_write` of the current chat, while it is useful to show them.
    pub fn todo(&self) -> Vec<crate::core::summary::TodoItem> {
        let c = self.conv();
        let steps = crate::core::summary::all_steps(&c.messages);
        crate::core::summary::todos(steps.iter().map(|f| f.step))
    }

    /// Takes the notices to show.
    pub fn take_notices(&mut self) -> Vec<String> {
        std::mem::take(&mut self.notices)
    }
}

fn fail_command(m: &mut Message, text: &str) {
    m.error = true;
    if !m.text.is_empty() {
        m.text.push_str("\n\n");
    }
    m.text.push_str(text);
}

/// Prompt + completion tokens of the main agent's last model call: how full its context was.
fn last_call_tokens(spans: &[Span]) -> Option<u64> {
    let root = spans.iter().find(|s| s.parent_id.is_none())?;
    let num = |s: &Span, k: &str| s.attrs.get(k).and_then(Value::as_f64).unwrap_or(0.0);
    spans
        .iter()
        .filter(|s| s.name == "llm" && s.parent_id.as_deref() == Some(&root.id))
        .max_by(|a, b| a.start_micros.total_cmp(&b.start_micros))
        .map(|s| (num(s, "promptTokens") + num(s, "completionTokens")) as u64)
}

/// Every part of the current chat's messages, for counting (tests and views).
pub fn parts(c: &Conv) -> impl Iterator<Item = &Part> {
    c.messages.iter().flat_map(|m| m.parts.iter())
}
