//! The screen: one column (top bar, thread, what is pinned, composer or the card that waits, footer) and
//! views opened over it (palette, step, trace, overview, help, confirmations). Keys go to the view on top,
//! else to the card that waits, else to the thread or the composer.

pub mod cards;
pub mod composer;
pub mod palette;
pub mod term;
pub mod text;
pub mod theme;
pub mod thread;
pub mod views;

use crate::api::{SettingsChange, quote_arg};
use crate::core::conversations::listed;
use crate::core::format::{ago, compact, duration};
use crate::core::reply::{CONTINUE_TEXT, Message, Role, activity, can_continue};
use crate::core::summary::{TodoStatus, all_steps, summary};
use crate::state::{Mode, RunKind, Store, TraceLoad};
use cards::{PlanForm, QuestionForm};
use composer::Composer;
use palette::{Action, Item, Palette};
use ratatui::Frame;
use ratatui::crossterm::event::{Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers, MouseEventKind};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Clear, Paragraph};
use std::time::{Duration, Instant};
use text::{fit, width};
use thread::{Ctx, Folds, Row, Target};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    Composer,
    Thread,
}

enum Confirm {
    Delete(String, String),
    /// Switching to Auto-run; Some: Shift+Tab cycles on to this mode when it is declined.
    AutoRun(Option<Mode>),
    RemoveProject(String, String),
}

enum View {
    None,
    Palette(Palette),
    Step { index: usize, scroll: usize },
    Trace { answer: usize, row: usize, scroll: usize },
    Overview { scroll: usize },
    Help,
    Confirm(Confirm),
}

/// Effects only the terminal loop can do.
pub enum Out {
    Bell,
    Copy(String),
    Editor,
    Quit,
}

pub struct App {
    pub store: Store,
    pub composer: Composer,
    folds: Folds,
    /// Lines scrolled up from the bottom; 0 follows new output.
    scroll: usize,
    focus: Focus,
    selected: Option<Target>,
    /// The selection moved: scroll it into view at the next draw.
    keep_selection_visible: bool,
    view: View,
    plan: PlanForm,
    questions: Option<QuestionForm>,
    suggestion: usize,
    tick: usize,
    quit_armed: Option<Instant>,
    notice: Option<(String, Instant)>,
    last_poll: Instant,
    title: String,
    /// The thread's height at the last draw, for paging.
    page: usize,
    pub out: Vec<Out>,
    base: String,
}

const EFFORTS: [&str; 5] = ["off", "minimal", "low", "medium", "high"];

impl App {
    pub fn new(store: Store, base: &str) -> Self {
        App {
            store,
            composer: Composer::with_history(),
            folds: Folds::default(),
            scroll: 0,
            focus: Focus::Composer,
            selected: None,
            keep_selection_visible: false,
            view: View::None,
            plan: PlanForm::default(),
            questions: None,
            suggestion: 0,
            tick: 0,
            quit_armed: None,
            notice: None,
            last_poll: Instant::now(),
            title: String::new(),
            page: 20,
            out: vec![],
            base: base.to_string(),
        }
    }

    /// Something moves on screen (a spinner, a clock), so it is drawn again soon.
    pub fn animating(&self) -> bool {
        self.store.conv().busy() && self.store.conv().waiting().is_none()
    }

    /// Time passed with nothing to do: spinners turn, the state is polled every 15 s.
    pub fn tick(&mut self) {
        self.tick = self.tick.wrapping_add(1);
        if self.last_poll.elapsed() >= Duration::from_secs(15) {
            self.last_poll = Instant::now();
            self.store.refresh_state();
        }
        if self.notice.as_ref().is_some_and(|(_, at)| at.elapsed() > Duration::from_secs(6)) {
            self.notice = None;
        }
    }

    pub fn after_update(&mut self) {
        for n in self.store.take_notices() {
            self.notify(&n);
        }
        if std::mem::take(&mut self.store.bell) {
            self.out.push(Out::Bell);
        }
        // a new card resets the forms that answer it
        let conv = self.store.conv();
        if let Some(m) = conv.waiting() {
            if let Some(q) = &m.question
                && self.questions.as_ref().map(|f| &f.id) != Some(&q.id)
            {
                self.questions = Some(QuestionForm::new(q));
            }
            if let Some(p) = &m.plan
                && self.plan.plan_id != p.id
            {
                self.plan = PlanForm { plan_id: p.id.clone(), ..Default::default() };
            }
        }
    }

    fn notify(&mut self, text: &str) {
        self.notice = Some((text.to_string(), Instant::now()));
    }

    // ---------------------------------------------------------------- input

    pub fn input(&mut self, e: Event) {
        match e {
            Event::Key(k) if k.kind != KeyEventKind::Release => self.key(k),
            Event::Paste(text) => self.paste(&text),
            Event::Mouse(m) => match m.kind {
                MouseEventKind::ScrollUp => self.scroll_by(3),
                MouseEventKind::ScrollDown => self.scroll_by(-3),
                _ => {}
            },
            _ => {}
        }
    }

    fn paste(&mut self, text: &str) {
        match &mut self.view {
            View::Palette(p) => {
                p.query.push_str(text.lines().next().unwrap_or(""));
                p.typed();
            }
            View::None => {
                if let Some(f) = &mut self.plan.feedback {
                    f.push_str(text);
                } else if let Some(q) =
                    self.questions.as_mut().filter(|_| self.store.conv().waiting().is_some_and(|m| m.question.is_some()))
                {
                    let i = q.question;
                    q.other[i].push_str(text);
                    q.other_on[i] = true;
                } else {
                    self.focus = Focus::Composer;
                    self.composer.insert_str(text);
                }
            }
            _ => {}
        }
    }

    fn scroll_by(&mut self, d: isize) {
        self.scroll = (self.scroll as isize + d).max(0) as usize;
    }

    fn key(&mut self, k: KeyEvent) {
        let ctrl = k.modifiers.contains(KeyModifiers::CONTROL);
        if ctrl && k.code == KeyCode::Char('c') {
            return self.ctrl_c();
        }
        self.quit_armed = None;
        if !matches!(self.view, View::None) {
            return self.view_key(k);
        }
        // keys that work everywhere on the main screen
        match (ctrl, k.code) {
            (true, KeyCode::Char('k')) => return self.open_palette(),
            (true, KeyCode::Char('r')) => return self.open_history(),
            (true, KeyCode::Char('n')) => return self.new_chat(),
            (true, KeyCode::Char('o')) => return self.view = View::Overview { scroll: 0 },
            (true, KeyCode::Char('j')) => return self.jump_to_waiting(),
            (true, KeyCode::Char('g')) => return self.out.push(Out::Editor),
            (_, KeyCode::PageUp) => return self.scroll_by(self.page.saturating_sub(2) as isize),
            (_, KeyCode::PageDown) => return self.scroll_by(-(self.page.saturating_sub(2) as isize)),
            (_, KeyCode::BackTab) => return self.cycle_mode(),
            _ => {}
        }
        if self.store.conv().waiting().is_some() && self.focus == Focus::Composer {
            return self.card_key(k);
        }
        match self.focus {
            Focus::Thread => self.thread_key(k),
            Focus::Composer => self.composer_key(k),
        }
    }

    fn ctrl_c(&mut self) {
        if !matches!(self.view, View::None) {
            self.view = View::None;
            return;
        }
        if self.quit_armed.is_some_and(|t| t.elapsed() < Duration::from_secs(2)) {
            self.out.push(Out::Quit);
            return;
        }
        self.quit_armed = Some(Instant::now());
        if !self.composer.is_empty() {
            self.composer.set("");
        } else if self.store.conv().busy() {
            self.store.stop();
        }
        self.notify("Press Ctrl+C again to quit.");
    }

    fn new_chat(&mut self) {
        self.store.new_chat();
        self.reset_view();
    }

    fn open_chat(&mut self, id: &str) {
        self.store.open(id);
        self.reset_view();
    }

    fn reset_view(&mut self) {
        self.scroll = 0;
        self.selected = None;
        self.focus = Focus::Composer;
        self.folds = Folds::default();
    }

    fn jump_to_waiting(&mut self) {
        match self.store.needs_you().first().cloned() {
            Some(id) => self.open_chat(&id),
            None => self.notify("No chat waits for you."),
        }
    }

    fn cycle_mode(&mut self) {
        let c = self.store.conv();
        let Some(current) = c.mode() else {
            return self.notify("This server has no plan mode and no permissions plugin.");
        };
        if c.busy() {
            return self.notify("Switch the mode once the answer ends.");
        }
        let available = Mode::available(c.mode, c.permissions);
        let next = current.next(&available);
        if next == Mode::Auto {
            let after = Mode::Auto.next(&available);
            self.view = View::Confirm(Confirm::AutoRun((after != current).then_some(after)));
        } else {
            self.store.set_mode(next);
        }
    }

    fn composer_key(&mut self, k: KeyEvent) {
        let ctrl = k.modifiers.contains(KeyModifiers::CONTROL);
        let alt = k.modifiers.contains(KeyModifiers::ALT);
        let shift = k.modifiers.contains(KeyModifiers::SHIFT);
        let suggestions = self.suggestions();
        match k.code {
            KeyCode::Enter if shift || alt => self.composer.insert('\n'),
            KeyCode::Enter => {
                if !suggestions.is_empty() {
                    let name = &suggestions[self.suggestion.min(suggestions.len() - 1)].0;
                    if self.composer.text != format!("/{name}") {
                        self.composer.set(&format!("/{name} "));
                        return;
                    }
                }
                self.submit();
            }
            KeyCode::Tab if !suggestions.is_empty() => {
                let name = suggestions[self.suggestion.min(suggestions.len() - 1)].0.clone();
                self.composer.set(&format!("/{name} "));
            }
            KeyCode::Tab => self.enter_thread(),
            KeyCode::Esc => {
                if !suggestions.is_empty() {
                    self.composer.set("");
                } else if self.store.conv().busy() {
                    self.store.stop();
                } else if self.scroll > 0 {
                    self.scroll = 0;
                }
            }
            KeyCode::Up if !suggestions.is_empty() => self.suggestion = (self.suggestion + suggestions.len() - 1) % suggestions.len(),
            KeyCode::Down if !suggestions.is_empty() => self.suggestion = (self.suggestion + 1) % suggestions.len(),
            KeyCode::Up => {
                if !self.composer.up() {
                    if self.composer.is_empty()
                        && let Some(q) = self.store.unqueue()
                    {
                        return self.composer.set(&q);
                    }
                    self.composer.history_prev();
                }
            }
            KeyCode::Down => {
                if !self.composer.down() {
                    self.composer.history_next();
                }
            }
            KeyCode::Left => self.composer.left(),
            KeyCode::Right => self.composer.right(),
            KeyCode::Home => self.composer.home(),
            KeyCode::End => self.composer.end(),
            KeyCode::Backspace if alt || ctrl => self.composer.kill_word(),
            KeyCode::Backspace => self.composer.backspace(),
            KeyCode::Delete => self.composer.delete(),
            KeyCode::Char('a') if ctrl => self.composer.home(),
            KeyCode::Char('e') if ctrl => self.composer.end(),
            KeyCode::Char('u') if ctrl => self.composer.kill_line(),
            KeyCode::Char('w') if ctrl => self.composer.kill_word(),
            KeyCode::Char('?') if self.composer.is_empty() => self.view = View::Help,
            KeyCode::Char('r') if self.composer.is_empty() && self.can_retry() => self.store.retry(),
            KeyCode::Char(c) if !ctrl => {
                self.composer.insert(c);
                self.suggestion = 0;
            }
            _ => {}
        }
    }

    fn can_retry(&self) -> bool {
        self.store.offline.is_some() || self.store.conv().messages.last().is_some_and(|m| m.error && !m.streaming)
    }

    fn submit(&mut self) {
        let text = self.composer.take();
        let t = text.trim();
        if t.is_empty() {
            return;
        }
        self.scroll = 0;
        match t {
            "/exit" | "/quit" => self.out.push(Out::Quit),
            "/continue" => match self.store.conv().messages.last() {
                Some(m) if can_continue(m) => self.store.send(CONTINUE_TEXT),
                _ => self.notify("Nothing to continue: the last answer did not stop at a limit."),
            },
            _ => {
                if self.store.conv().busy() {
                    self.notify("Queued: it is sent when the answer ends (↑ to take it back).");
                }
                self.store.send(t);
            }
        }
    }

    /// Commands whose name starts with what is typed after `/` (not `exit`).
    fn suggestions(&self) -> Vec<(String, String, String)> {
        let Some(prefix) = self.composer.command_prefix() else { return vec![] };
        let Some(state) = &self.store.state else { return vec![] };
        state
            .commands
            .iter()
            .filter(|c| c.name != "exit" && c.name.starts_with(prefix))
            .map(|c| (c.name.clone(), c.usage.clone(), c.description.clone()))
            .take(8)
            .collect()
    }

    fn card_key(&mut self, k: KeyEvent) {
        let conv = self.store.conv();
        let Some(m) = conv.waiting() else { return };
        if m.approval.is_some() {
            match k.code {
                KeyCode::Char('y') | KeyCode::Char('Y') | KeyCode::Enter => self.store.approve(true),
                KeyCode::Char('n') | KeyCode::Char('N') | KeyCode::Esc => self.store.approve(false),
                KeyCode::Tab => self.enter_thread(),
                _ => {}
            }
            return;
        }
        if m.plan.is_some() {
            if let Some(f) = &mut self.plan.feedback {
                match k.code {
                    KeyCode::Esc => self.plan.feedback = None,
                    KeyCode::Enter if k.modifiers.intersects(KeyModifiers::SHIFT | KeyModifiers::ALT) => f.push('\n'),
                    KeyCode::Enter => {
                        let text = f.trim().to_string();
                        if !text.is_empty() {
                            self.plan.feedback = None;
                            self.store.answer_plan(false, &text);
                        }
                    }
                    KeyCode::Backspace => {
                        f.pop();
                    }
                    KeyCode::Char(c) => f.push(c),
                    _ => {}
                }
                return;
            }
            match k.code {
                KeyCode::Char('a') | KeyCode::Enter => self.store.answer_plan(true, ""),
                KeyCode::Char('f') => self.plan.feedback = Some(String::new()),
                KeyCode::Up | KeyCode::Char('k') => self.plan.scroll = self.plan.scroll.saturating_sub(1),
                KeyCode::Down | KeyCode::Char('j') => self.plan.scroll += 1,
                KeyCode::Tab => self.enter_thread(),
                _ => {}
            }
            return;
        }
        let Some(q) = m.question.clone() else { return };
        let Some(f) = self.questions.as_mut() else { return };
        match k.code {
            KeyCode::Up => f.up(&q),
            KeyCode::Down => f.down(&q),
            KeyCode::Char(' ') if !f.on_other(&q) || f.other[f.question].is_empty() => f.toggle(&q),
            KeyCode::Enter => {
                if f.complete() {
                    let answers = f.answers(&q);
                    self.store.answer_questions(answers);
                }
            }
            KeyCode::Esc => self.store.answer_questions(QuestionForm::skipped(&q)),
            KeyCode::Backspace if f.on_other(&q) => {
                f.other[f.question].pop();
            }
            KeyCode::Char(c) if f.on_other(&q) => {
                let i = f.question;
                if !f.other_on[i] {
                    f.toggle(&q);
                }
                f.other[i].push(c);
            }
            _ => {}
        }
    }

    // ---------------------------------------------------------------- thread selection

    fn rows(&self, w: usize) -> Vec<Row> {
        thread::build(self.store.conv(), &Ctx { width: w, now: self.store.now(), tick: self.tick, folds: &self.folds })
    }

    fn targets(&self) -> Vec<Target> {
        let mut out: Vec<Target> = vec![];
        for r in self.rows(80) {
            if let Some(t) = r.target
                && out.last() != Some(&t)
                && !out.contains(&t)
            {
                out.push(t);
            }
        }
        out
    }

    fn enter_thread(&mut self) {
        let targets = self.targets();
        if targets.is_empty() {
            return;
        }
        self.focus = Focus::Thread;
        if self.selected.as_ref().is_none_or(|s| !targets.contains(s)) {
            self.selected = targets.last().cloned();
        }
        self.keep_selection_visible = true;
    }

    fn move_selection(&mut self, d: isize) {
        let targets = self.targets();
        if targets.is_empty() {
            return;
        }
        let i = self.selected.as_ref().and_then(|s| targets.iter().position(|t| t == s)).unwrap_or(targets.len() - 1);
        let j = (i as isize + d).clamp(0, targets.len() as isize - 1) as usize;
        self.selected = Some(targets[j].clone());
        self.keep_selection_visible = true;
    }

    fn thread_key(&mut self, k: KeyEvent) {
        match k.code {
            KeyCode::Esc | KeyCode::Tab | KeyCode::Char('i') => self.focus = Focus::Composer,
            KeyCode::Down | KeyCode::Char('j') => self.move_selection(1),
            KeyCode::Up | KeyCode::Char('k') => self.move_selection(-1),
            KeyCode::Char('g') | KeyCode::Home => self.move_selection(-100_000),
            KeyCode::Char('G') | KeyCode::End => self.move_selection(100_000),
            KeyCode::Enter => self.open_target(),
            KeyCode::Char(' ') => self.toggle_target(),
            KeyCode::Char('c') => self.copy_target(false),
            KeyCode::Char('C') => self.copy_target(true),
            KeyCode::Char('?') => self.view = View::Help,
            KeyCode::Char(c) if !k.modifiers.contains(KeyModifiers::CONTROL) => {
                self.focus = Focus::Composer;
                self.composer.insert(c);
            }
            _ => {}
        }
    }

    fn toggle_target(&mut self) {
        let Some(t) = self.selected.clone() else { return };
        if let Some((key, default)) = thread::fold_key(self.store.conv(), &t) {
            self.folds.toggle(key, default);
        }
        // the agent line of a call folds its sub-agent
        if let Target::Tool { msg, step } = &t
            && let Some(s) = crate::core::reply::find_step(&self.store.conv().messages, *msg, step)
            && s.agent.is_some()
        {
            let running = s.status == crate::core::reply::Status::Running;
            self.folds.toggle(format!("agent:{msg}:{step}"), running);
        }
    }

    fn open_target(&mut self) {
        let Some(t) = self.selected.clone() else { return };
        match &t {
            Target::Tool { msg, step } => {
                let steps = all_steps(&self.store.conv().messages);
                if let Some(i) = steps.iter().position(|f| f.message_id == *msg && &f.step.id == step) {
                    self.view = View::Step { index: i, scroll: 0 };
                }
            }
            Target::Turn { msg } => {
                let answers = self.traced();
                match answers.iter().position(|m| m.id == *msg) {
                    Some(i) => self.open_trace(i),
                    None => self.toggle_target(),
                }
            }
            _ => self.toggle_target(),
        }
    }

    fn copy_target(&mut self, code: bool) {
        let Some(t) = self.selected.clone() else { return };
        let conv = self.store.conv();
        let text = match &t {
            Target::Tool { msg, step } => crate::core::reply::find_step(&conv.messages, *msg, step).map(|s| {
                crate::core::diff::step_diff(s)
                    .map(|_| s.diff.clone().unwrap_or_else(|| s.arguments.clone()))
                    .or_else(|| s.content.clone())
                    .unwrap_or_else(|| s.arguments.clone())
            }),
            Target::Message { msg } | Target::Turn { msg } | Target::Group { msg, .. } | Target::Reasoning { msg, .. } => {
                conv.messages.iter().find(|m| m.id == *msg).map(|m| {
                    if code {
                        let blocks = crate::core::markdown::parse(&m.text);
                        crate::core::markdown::code_blocks(&blocks).pop().unwrap_or_default()
                    } else {
                        m.text.clone()
                    }
                })
            }
        };
        match text.filter(|t| !t.is_empty()) {
            Some(t) => {
                self.out.push(Out::Copy(t));
                self.notify("Copied.");
            }
            None => self.notify(if code { "No code block there." } else { "Nothing to copy there." }),
        }
    }

    // ---------------------------------------------------------------- views

    /// Answers with a trace, oldest first.
    fn traced(&self) -> Vec<&Message> {
        self.store.conv().messages.iter().filter(|m| m.role == Role::Assistant && !m.streaming && m.trace_id.is_some()).collect()
    }

    fn open_trace(&mut self, i: usize) {
        let id = self.traced().get(i).and_then(|m| m.trace_id.clone());
        match id {
            Some(id) => {
                self.store.load_trace(&id);
                self.view = View::Trace { answer: i, row: 0, scroll: 0 };
            }
            None => self.notify("No traces yet. Each finished answer has one when the tracing plugin runs."),
        }
    }

    fn view_key(&mut self, k: KeyEvent) {
        let code = k.code;
        let page = self.page.saturating_sub(4).max(1);
        let traced = self.traced().len();
        match &mut self.view {
            View::Palette(p) => match code {
                KeyCode::Esc => self.view = View::None,
                KeyCode::Up => p.move_by(-1),
                KeyCode::Down | KeyCode::Tab => p.move_by(1),
                KeyCode::PageUp => p.move_by(-(page as isize)),
                KeyCode::PageDown => p.move_by(page as isize),
                KeyCode::Enter => {
                    let chosen = p.chosen();
                    self.view = View::None;
                    if let Some(a) = chosen {
                        self.act(a);
                    }
                }
                KeyCode::Backspace => {
                    p.query.pop();
                    p.typed();
                }
                KeyCode::Char('u') if k.modifiers.contains(KeyModifiers::CONTROL) => {
                    p.query.clear();
                    p.typed();
                }
                KeyCode::Char(c) => {
                    p.query.push(c);
                    p.typed();
                }
                _ => {}
            },
            View::Step { index, scroll } => {
                let n = all_steps(&self.store.conv().messages).len();
                match code {
                    KeyCode::Esc | KeyCode::Char('q') => self.view = View::None,
                    KeyCode::Left | KeyCode::Char('h') => {
                        *index = index.saturating_sub(1);
                        *scroll = 0;
                    }
                    KeyCode::Right | KeyCode::Char('l') => {
                        *index = (*index + 1).min(n.saturating_sub(1));
                        *scroll = 0;
                    }
                    KeyCode::Up | KeyCode::Char('k') => *scroll = scroll.saturating_sub(1),
                    KeyCode::Down | KeyCode::Char('j') => *scroll += 1,
                    KeyCode::PageUp => *scroll = scroll.saturating_sub(page),
                    KeyCode::PageDown | KeyCode::Char(' ') => *scroll += page,
                    KeyCode::Char('c') => {
                        let i = *index;
                        if let Some(f) = all_steps(&self.store.conv().messages).get(i) {
                            let t = f.step.content.clone().unwrap_or_else(|| f.step.arguments.clone());
                            self.out.push(Out::Copy(t));
                            self.notify("Copied the result.");
                        }
                    }
                    _ => {}
                }
            }
            View::Trace { answer, row, scroll } => {
                let n = traced;
                match code {
                    KeyCode::Esc | KeyCode::Char('q') => self.view = View::None,
                    KeyCode::Left | KeyCode::Char('h') if *answer > 0 => {
                        let i = *answer - 1;
                        self.open_trace(i);
                    }
                    KeyCode::Right | KeyCode::Char('l') if *answer + 1 < n => {
                        let i = *answer + 1;
                        self.open_trace(i);
                    }
                    KeyCode::Up | KeyCode::Char('k') => *row = row.saturating_sub(1),
                    KeyCode::Down | KeyCode::Char('j') => *row += 1,
                    KeyCode::PageUp => *scroll = scroll.saturating_sub(page),
                    KeyCode::PageDown => *scroll += page,
                    _ => {}
                }
            }
            View::Overview { scroll } => match code {
                KeyCode::Esc | KeyCode::Char('q') | KeyCode::Enter => self.view = View::None,
                KeyCode::Up | KeyCode::Char('k') => *scroll = scroll.saturating_sub(1),
                KeyCode::Down | KeyCode::Char('j') => *scroll += 1,
                _ => {}
            },
            View::Help => self.view = View::None,
            View::Confirm(c) => match code {
                KeyCode::Char('y') | KeyCode::Enter => {
                    let c = std::mem::replace(c, Confirm::AutoRun(None));
                    self.view = View::None;
                    match c {
                        Confirm::Delete(id, _) => {
                            self.store.remove(&id);
                            self.reset_view();
                        }
                        Confirm::AutoRun(_) => self.store.set_mode(Mode::Auto),
                        Confirm::RemoveProject(path, _) => self.store.workspace(format!("workspace remove {}", quote_arg(&path))),
                    }
                }
                KeyCode::Char('n') | KeyCode::Esc => {
                    let skip = if let Confirm::AutoRun(Some(m)) = c { Some(*m) } else { None };
                    self.view = View::None;
                    if let Some(m) = skip.filter(|_| code == KeyCode::Char('n')) {
                        self.store.set_mode(m);
                    }
                }
                _ => {}
            },
            View::None => {}
        }
    }

    fn act(&mut self, a: Action) {
        match a {
            Action::NewChat => self.new_chat(),
            Action::Open(id) => self.open_chat(&id),
            Action::Command(name) => {
                self.focus = Focus::Composer;
                self.composer.set(&format!("/{name} "));
            }
            Action::Model(id) => self.store.settings(SettingsChange { model: Some(id), ..Default::default() }),
            Action::Effort(e) => self.store.settings(SettingsChange { reasoning_effort: Some(e), ..Default::default() }),
            Action::Provider(p) => self.store.settings(SettingsChange { provider: Some(p), ..Default::default() }),
            Action::Mode(m) => {
                if self.store.conv().busy() {
                    self.notify("Switch the mode once the answer ends.");
                } else if m == Mode::Auto && self.store.conv().mode() != Some(Mode::Auto) {
                    self.view = View::Confirm(Confirm::AutoRun(None));
                } else {
                    self.store.set_mode(m);
                }
            }
            Action::Delete(id) => {
                let title = self.store.conversations.iter().find(|c| c.id == id).map(|c| c.title.clone()).unwrap_or_default();
                self.view = View::Confirm(Confirm::Delete(id, title));
            }
            Action::Overview => self.view = View::Overview { scroll: 0 },
            Action::Trace => {
                let n = self.traced().len();
                self.open_trace(n.saturating_sub(1));
            }
            Action::AddProject(path) => self.store.workspace(format!("workspace add {}", quote_arg(&path))),
            Action::RemoveProject(path, name) => self.view = View::Confirm(Confirm::RemoveProject(path, name)),
            Action::NewChatIn(path) => {
                self.new_chat();
                self.store.workspace(format!("workspace use {}", quote_arg(&path)));
            }
            Action::Continue => self.store.send(CONTINUE_TEXT),
            Action::CopyLast => {
                let last = self
                    .store
                    .conv()
                    .messages
                    .iter()
                    .rev()
                    .find(|m| m.role == Role::Assistant && !m.text.is_empty())
                    .map(|m| m.text.clone());
                match last {
                    Some(t) => {
                        self.out.push(Out::Copy(t));
                        self.notify("Copied the last answer.");
                    }
                    None => self.notify("No answer to copy yet."),
                }
            }
            Action::Help => self.view = View::Help,
            Action::Recall(t) => {
                self.focus = Focus::Composer;
                self.composer.set(&t);
            }
        }
    }

    fn open_history(&mut self) {
        let items = self
            .composer
            .history()
            .iter()
            .rev()
            .map(|h| Item::new("History", h.replace('\n', " ⏎ "), "", Action::Recall(h.clone())))
            .collect();
        self.view = View::Palette(Palette::new("History", items));
    }

    fn open_palette(&mut self) {
        self.store.load_models();
        let s = &self.store;
        let conv = s.conv();
        let now = s.now();
        let mut items = vec![Item::new("Actions", "New chat", "Ctrl+N", Action::NewChat)];
        if conv.messages.last().is_some_and(can_continue) {
            items.push(Item::new("Actions", "Continue where it stopped", "", Action::Continue));
        }
        items.push(Item::new("Actions", "Run overview", "Ctrl+O", Action::Overview));
        if !self.traced().is_empty() {
            items.push(Item::new("Actions", "Trace of the last answer", "where the time went", Action::Trace));
        }
        items.push(Item::new("Actions", "Copy the last answer", "", Action::CopyLast));
        if !conv.fresh {
            items.push(Item::new("Actions", "Delete this chat…", "with its plan and traces", Action::Delete(conv.id.clone())).alert());
        }
        items.push(Item::new("Actions", "Keys", "?", Action::Help));
        for id in s.needs_you() {
            let title = s.conversations.iter().find(|c| c.id == id).map(|c| c.title.clone()).unwrap_or_default();
            items.push(
                Item::new("Needs you", if title.is_empty() { "New chat".into() } else { title }, "waiting · Ctrl+J", Action::Open(id))
                    .alert(),
            );
        }
        for l in listed(&s.conversations, s.workspaces.as_ref(), &s.current, now) {
            if l.chat.id == s.current {
                continue;
            }
            let title = if l.chat.title.is_empty() { "New chat".to_string() } else { l.chat.title.clone() };
            let mut detail = vec![];
            if let Some(p) = &l.project {
                detail.push(p.clone());
            }
            if l.chat.running || s.convs.get(&l.chat.id).is_some_and(|c| c.busy()) {
                detail.push("running".into());
            } else if s.convs.get(&l.chat.id).is_some_and(|c| c.unseen) {
                detail.push("new answer".into());
            }
            detail.push(ago(l.chat.updated_at, now));
            items.push(Item::new("Chats", title, detail.join(" · "), Action::Open(l.chat.id)));
        }
        let current_mode = conv.mode();
        for m in Mode::available(conv.mode, conv.permissions) {
            if conv.mode().is_none() {
                break;
            }
            let detail = if Some(m) == current_mode { format!("current · {}", m.description()) } else { m.description().to_string() };
            let it = Item::new("Mode", m.label(), detail, Action::Mode(m));
            items.push(if m == Mode::Auto { it.alert() } else { it });
        }
        if let Some(state) = &s.state {
            match &s.models {
                Some(Ok(models)) => {
                    for m in models {
                        let mut tags = vec![];
                        if m.free == Some(true) {
                            tags.push("free".to_string());
                        }
                        if m.reasoning == Some(true) {
                            tags.push("reasoning".into());
                        }
                        if m.context_length > 0 {
                            tags.push(format!("{} context", compact(m.context_length)));
                        }
                        if state.model.as_deref() == Some(&m.id) {
                            tags.insert(0, "current".into());
                        }
                        items.push(Item::new("Models", m.id.clone(), tags.join(" · "), Action::Model(m.id.clone())));
                    }
                }
                Some(Err(e)) => items.push(Item::new("Models", "Models could not be listed", e.clone(), Action::Help)),
                None => {}
            }
            let current = state.reasoning_effort.clone().unwrap_or_else(|| "off".into());
            let mut levels: Vec<String> = EFFORTS.iter().map(|e| e.to_string()).collect();
            if !levels.contains(&current) {
                levels.push(current.clone());
            }
            for e in levels {
                let mut detail = if e == current { "current".to_string() } else { String::new() };
                if state.effort_supported == Some(false) && e != "off" {
                    detail = "this model takes no reasoning effort".into();
                }
                items.push(Item::new("Reasoning effort", e.clone(), detail, Action::Effort(e)));
            }
            for p in &state.providers {
                let mut detail = if p.connected {
                    String::new()
                } else {
                    "not connected: its API key is missing or it is disabled in panic.json".into()
                };
                if state.provider.as_deref() == Some(&p.id) {
                    detail = format!("current{}", if detail.is_empty() { String::new() } else { format!(" · {detail}") });
                }
                items.push(Item::new("Provider", p.id.clone(), detail, Action::Provider(p.id.clone())));
            }
            for c in &state.commands {
                if c.name == "exit" {
                    continue;
                }
                items.push(Item::new("Commands", format!("/{}", c.usage), c.description.clone(), Action::Command(c.name.clone())));
            }
        }
        if let Some(ws) = &s.workspaces {
            if let Some(dir) = &s.cwd
                && ws.project_at(dir).is_none()
            {
                items.push(Item::new("Projects", "Add this folder as a project", dir.clone(), Action::AddProject(dir.clone())));
            }
            for p in &ws.projects {
                items.push(Item::new("Projects", format!("New chat in {}", p.name), p.path.clone(), Action::NewChatIn(p.path.clone())));
            }
            for p in &ws.projects {
                items.push(
                    Item::new(
                        "Projects",
                        format!("Remove project {}…", p.name),
                        p.path.clone(),
                        Action::RemoveProject(p.path.clone(), p.name.clone()),
                    )
                    .alert(),
                );
            }
        }
        self.view = View::Palette(Palette::new("Go to", items));
    }

    // ---------------------------------------------------------------- drawing

    pub fn window_title(&mut self) -> Option<String> {
        let c = self.store.conv();
        let title = if c.fresh {
            "New chat · Panic".to_string()
        } else {
            let t = self.store.conversations.iter().find(|x| x.id == c.id).map(|x| x.title.clone()).unwrap_or_default();
            if t.is_empty() { "Chat · Panic".into() } else { format!("{t} · Panic") }
        };
        (title != self.title).then(|| {
            self.title = title.clone();
            title
        })
    }

    pub fn draw(&mut self, f: &mut Frame) {
        let area = f.area();
        let w = area.width as usize;
        let waiting = self.store.conv().waiting().is_some();
        let plan_waiting = self.store.conv().waiting().is_some_and(|m| m.plan.is_some());

        // the bottom: the card that waits, or the composer
        let bottom_lines = self.bottom(w, area.height as usize);
        let max_bottom = (area.height as usize * 3 / 5).max(3);
        let bottom_h = bottom_lines.0.len().clamp(1, max_bottom) as u16;
        let pinned = self.pinned(w);
        let banner = self.banner();
        let [top, banner_area, thread_area, pinned_area, sep, bottom_area, footer] = Layout::vertical([
            Constraint::Length(1),
            Constraint::Length(banner.is_some() as u16),
            Constraint::Fill(1),
            Constraint::Length(pinned.len() as u16),
            Constraint::Length(1),
            Constraint::Length(bottom_h),
            Constraint::Length(1),
        ])
        .areas(area);

        f.render_widget(Paragraph::new(self.top_bar(w)), top);
        if let Some(b) = banner {
            f.render_widget(Paragraph::new(b), banner_area);
        }
        self.draw_thread(f, thread_area);
        f.render_widget(Paragraph::new(pinned), pinned_area);
        let sep_style = if waiting { theme::warn() } else { theme::muted() };
        f.render_widget(Paragraph::new(Line::from(Span::styled("─".repeat(w), sep_style))), sep);

        let (lines, cursor) = bottom_lines;
        let scroll = if plan_waiting {
            self.plan.scroll.min(lines.len().saturating_sub(bottom_h as usize))
        } else {
            lines.len().saturating_sub(bottom_h as usize)
        };
        f.render_widget(Paragraph::new(lines).scroll((scroll as u16, 0)), bottom_area);
        if let Some((row, col)) = cursor
            && matches!(self.view, View::None)
            && self.focus == Focus::Composer
            && row >= scroll
        {
            let y = bottom_area.y + (row - scroll) as u16;
            if y < bottom_area.y + bottom_area.height {
                f.set_cursor_position((bottom_area.x + col as u16, y));
            }
        }
        f.render_widget(Paragraph::new(self.footer(w)), footer);

        // command suggestions float over the bottom of the thread
        let sugg = self.suggestions();
        if !sugg.is_empty() && matches!(self.view, View::None) && !waiting {
            let h = sugg.len() as u16;
            let r = Rect { x: area.x, y: sep.y.saturating_sub(h), width: area.width, height: h.min(sep.y) };
            let lines: Vec<Line> = sugg
                .iter()
                .enumerate()
                .map(|(i, (_, usage, desc))| {
                    let here = i == self.suggestion.min(sugg.len() - 1);
                    let u = format!(" /{usage}");
                    let line = Line::from(vec![
                        Span::styled(fit(&u, w / 2), theme::accent()),
                        Span::styled(format!("  {}", fit(desc, w.saturating_sub(width(&u) + 3))), theme::muted()),
                    ]);
                    if here { line.patch_style(theme::selected()) } else { line }
                })
                .collect();
            f.render_widget(Clear, r);
            f.render_widget(Paragraph::new(lines), r);
        }
        self.draw_view(f, area);
    }

    fn top_bar(&self, w: usize) -> Line<'static> {
        let s = &self.store;
        let c = s.conv();
        let place = s
            .workspaces
            .as_ref()
            .and_then(|ws| ws.project_of(&c.id).or_else(|| s.cwd.as_deref().and_then(|d| ws.project_at(d))))
            .map(|p| p.name.clone())
            .or_else(|| s.cwd.as_deref().map(|d| crate::core::format::base_name(d).to_string()))
            .unwrap_or_default();
        let title = if c.fresh {
            "New chat".to_string()
        } else {
            s.conversations
                .iter()
                .find(|x| x.id == c.id)
                .map(|x| x.title.clone())
                .filter(|t| !t.is_empty())
                .unwrap_or_else(|| "Chat".into())
        };
        let mut right: Vec<Span<'static>> = vec![];
        match (&s.offline, &s.state) {
            (Some(_), _) => right.push(Span::styled("offline", theme::err())),
            (None, Some(st)) => {
                let model = st.model.clone().unwrap_or_else(|| "No model".into());
                right.push(Span::styled(model, if st.connected { Style::default() } else { theme::err() }));
                if let Some(e) = &st.reasoning_effort {
                    right.push(Span::styled(format!(" · {e}"), theme::muted()));
                }
                if let Some(u) = s.context_use() {
                    let pct = u.used * 100 / u.window.max(1);
                    right.push(Span::styled(" · ctx ", theme::muted()));
                    right.push(Span::styled(format!("{pct}%"), if pct >= 80 { theme::warn() } else { theme::muted() }));
                }
            }
            (None, None) => right.push(Span::styled("connecting…", theme::muted())),
        }
        let right_w: usize = right.iter().map(|s| width(&s.content)).sum();
        let left = fit(&format!(" · {place} · {title}"), w.saturating_sub(right_w + 9));
        let pad = w.saturating_sub(6 + width(&left) + right_w + 1);
        let mut spans = vec![Span::styled(" Panic", theme::bold()), Span::styled(left, theme::muted()), Span::raw(" ".repeat(pad))];
        spans.extend(right);
        spans.push(Span::raw(" "));
        Line::from(spans)
    }

    fn banner(&self) -> Option<Line<'static>> {
        let s = &self.store;
        if let Some(e) = &s.offline {
            return Some(Line::from(vec![
                Span::styled(format!(" {e} "), theme::err()),
                Span::styled(format!("Is `panic --serve` running at {}? Press r to retry.", self.base), theme::muted()),
            ]));
        }
        if s.state.as_ref().is_some_and(|st| !st.connected) {
            return Some(Line::from(vec![
                Span::styled(" No model is connected. ", theme::warn()),
                Span::styled("Commands still work. Ctrl+K → Provider or Models.", theme::muted()),
            ]));
        }
        None
    }

    fn draw_thread(&mut self, f: &mut Frame, area: Rect) {
        let w = area.width as usize;
        let h = area.height as usize;
        self.page = h;
        let conv = self.store.conv();
        if conv.messages.is_empty() {
            let msg = if conv.loading { "Loading…" } else { "What should Panic do?" };
            let y = area.y + area.height / 2;
            let line = Line::from(Span::styled(msg, if conv.loading { theme::muted() } else { theme::bold() }));
            let x = area.x + (area.width.saturating_sub(width(msg) as u16)) / 2;
            f.render_widget(Paragraph::new(line), Rect { x, y, width: area.width.saturating_sub(x - area.x), height: 1 });
            return;
        }
        let rows = self.rows(w.saturating_sub(1));
        let total = rows.len();
        let max_scroll = total.saturating_sub(h);
        // keep the selection in view
        if self.focus == Focus::Thread
            && std::mem::take(&mut self.keep_selection_visible)
            && let Some(sel) = &self.selected
            && let Some(first) = rows.iter().position(|r| r.target.as_ref() == Some(sel))
        {
            let last = rows.iter().rposition(|r| r.target.as_ref() == Some(sel)).unwrap_or(first);
            let start = total.saturating_sub(h + self.scroll);
            if first < start {
                self.scroll = total.saturating_sub(h + first);
            } else if last >= start + h {
                self.scroll = total.saturating_sub((last + 1).max(h));
            }
        }
        self.scroll = self.scroll.min(max_scroll);
        let start = total.saturating_sub(h + self.scroll);
        let lines: Vec<Line> = rows
            .into_iter()
            .skip(start)
            .take(h)
            .map(|r| {
                let sel = self.focus == Focus::Thread && r.target.is_some() && r.target == self.selected;
                let mut line = r.line;
                line.spans.insert(0, Span::styled(if sel { "▌" } else { " " }, theme::accent()));
                if sel { line.patch_style(Style::default().add_modifier(Modifier::BOLD)) } else { line }
            })
            .collect();
        f.render_widget(Paragraph::new(lines), area);
        if self.scroll > 0 {
            let label = format!(" ↓ {} more lines · Esc ", self.scroll);
            let r = Rect {
                x: area.x + area.width.saturating_sub(width(&label) as u16 + 1),
                y: area.y + area.height - 1,
                width: width(&label) as u16,
                height: 1,
            };
            f.render_widget(Paragraph::new(Span::styled(label, theme::accent().add_modifier(Modifier::REVERSED))), r);
        }
    }

    /// The todo list and what the answer does, pinned above the composer, then the queued messages.
    fn pinned(&self, w: usize) -> Vec<Line<'static>> {
        let s = &self.store;
        let c = s.conv();
        let mut out = vec![];
        let todo = s.todo();
        if !todo.is_empty() && (c.busy() || todo.iter().any(|t| t.status != TodoStatus::Done)) {
            let done = todo.iter().filter(|t| t.status == TodoStatus::Done).count();
            let mut spans = vec![Span::styled(format!(" Todo {done}/{}", todo.len()), theme::bold())];
            for t in &todo {
                let (mark, style) = match t.status {
                    TodoStatus::Done => ("✓", theme::muted()),
                    TodoStatus::InProgress => ("◐", theme::accent()),
                    TodoStatus::Pending => ("○", Style::default()),
                };
                spans.push(Span::styled(format!("  {mark} {}", fit(&t.text, 30)), style));
            }
            let mut line = Line::from(spans);
            // one line: what does not fit is cut
            let mut used = 0;
            line.spans.retain(|sp| {
                used += width(&sp.content);
                used <= w
            });
            out.push(line);
        }
        if let Some(run) = &c.run {
            let m = &c.messages[run.reply];
            let text = match &run.kind {
                RunKind::Ask => activity(m),
                RunKind::Command(l) => format!("Running {l}…"),
            };
            let elapsed = m.started_at.map(|t| duration(s.now() - t)).unwrap_or_default();
            let wait = m.needs_user();
            let lead = if wait {
                Span::styled(" ◆ ", theme::warn())
            } else {
                Span::styled(format!(" {} ", theme::SPINNER[self.tick % theme::SPINNER.len()]), theme::accent())
            };
            let mut spans = vec![
                lead,
                Span::styled(text, if wait { theme::warn() } else { theme::accent() }),
                Span::styled(format!(" {elapsed}"), theme::muted()),
            ];
            spans.push(Span::styled(if wait { "" } else { "  Esc to stop" }, theme::muted()));
            out.push(Line::from(spans));
        }
        for (i, q) in c.queue.iter().enumerate() {
            if i == 2 && c.queue.len() > 3 {
                out.push(Line::from(Span::styled(format!("   +{} more queued", c.queue.len() - 2), theme::muted())));
                break;
            }
            out.push(Line::from(vec![
                Span::styled(" ⏎ queued  ", theme::muted()),
                Span::raw(fit(&q.replace('\n', " "), w.saturating_sub(12))),
            ]));
        }
        out
    }

    /// What is at the bottom, and where the cursor goes: the waiting card, else the composer.
    fn bottom(&self, w: usize, h: usize) -> (Vec<Line<'static>>, Option<(usize, usize)>) {
        let conv = self.store.conv();
        if let Some(m) = conv.waiting() {
            if let Some(a) = &m.approval {
                return (cards::approval(a, m.waiting.len(), w, (h / 3).max(3)), None);
            }
            if let Some(p) = &m.plan {
                let mut lines = cards::plan(p, &self.plan, w);
                lines.push(cards::plan_keys(&self.plan));
                if let Some(fb) = &self.plan.feedback {
                    let row = lines.len();
                    for l in fb.split('\n') {
                        lines.push(Line::from(vec![Span::styled("❯ ", theme::accent()), Span::raw(l.to_string())]));
                    }
                    let col = 2 + width(fb.rsplit('\n').next().unwrap_or(""));
                    return (lines, Some((row + fb.matches('\n').count(), col)));
                }
                return (lines, None);
            }
            if let (Some(q), Some(f)) = (&m.question, &self.questions) {
                return (cards::questions(q, f, w), None);
            }
        }
        let (rows, (r, c)) = self.composer.layout(w.saturating_sub(3));
        let placeholder = self.composer.is_empty();
        let lines = rows
            .into_iter()
            .enumerate()
            .map(|(i, t)| {
                let lead = if i == 0 { Span::styled("❯ ", theme::accent()) } else { Span::raw("  ") };
                if placeholder {
                    let connected = self.store.state.as_ref().is_none_or(|s| s.connected);
                    let hint = if conv.busy() {
                        "Write the next message: it is sent when the answer ends"
                    } else if connected {
                        "Message Panic"
                    } else {
                        "Type / for commands"
                    };
                    Line::from(vec![lead, Span::styled(hint, theme::muted())])
                } else {
                    Line::from(vec![lead, Span::raw(t)])
                }
            })
            .collect();
        (lines, Some((r, c + 2)))
    }

    fn footer(&self, w: usize) -> Line<'static> {
        let c = self.store.conv();
        let mut left = vec![];
        if let Some(m) = c.mode() {
            let style = if m == Mode::Auto { theme::err().add_modifier(Modifier::BOLD) } else { Style::default() };
            left.push(Span::styled(format!(" {}", m.label().to_lowercase()), style));
            left.push(Span::styled(" (⇧Tab)", theme::muted()));
        } else {
            left.push(Span::styled(" ask first", Style::default()));
        }
        let waiting = self.store.needs_you().len();
        let mut middle = vec![];
        if waiting > 0 {
            middle.push(Span::styled(
                format!("  {waiting} {} you (^J)", if waiting == 1 { "chat needs" } else { "chats need" }),
                theme::warn().add_modifier(Modifier::BOLD),
            ));
        }
        if let Some((n, _)) = &self.notice {
            middle.push(Span::styled(format!("  {n}"), theme::warn()));
        } else if waiting == 0 && self.focus == Focus::Thread {
            middle.push(Span::styled("  ↵ open · space fold · c copy · esc back", theme::muted()));
        }
        let right = Span::styled("^K palette · ? keys ", theme::muted());
        let room = w.saturating_sub(left.iter().map(|s| width(&s.content)).sum::<usize>() + width(&right.content) + 1);
        let mut used_mid = 0;
        for sp in &mut middle {
            let left_room = room.saturating_sub(used_mid);
            if width(&sp.content) > left_room {
                sp.content = fit(&sp.content, left_room).into();
            }
            used_mid += width(&sp.content);
        }
        let used: usize = left.iter().chain(middle.iter()).map(|s| width(&s.content)).sum::<usize>() + width(&right.content);
        let mut spans = left;
        spans.extend(middle);
        spans.push(Span::raw(" ".repeat(w.saturating_sub(used))));
        spans.push(right);
        Line::from(spans)
    }

    fn draw_view(&mut self, f: &mut Frame, area: Rect) {
        let popup = |f: &mut Frame, title: String, w_pct: u16, h_pct: u16| -> Rect {
            let w = (area.width * w_pct / 100).max(40).min(area.width);
            let h = (area.height * h_pct / 100).max(8).min(area.height);
            let r = Rect { x: area.x + (area.width - w) / 2, y: area.y + (area.height - h) / 2, width: w, height: h };
            f.render_widget(Clear, r);
            let block = Block::default()
                .borders(Borders::ALL)
                .border_style(theme::muted())
                .title(Span::styled(format!(" {title} "), theme::bold()));
            let inner = block.inner(r);
            f.render_widget(block, r);
            inner
        };
        let full = |f: &mut Frame, title: String, hint: &str| -> Rect {
            f.render_widget(Clear, area);
            let block = Block::default()
                .borders(Borders::TOP | Borders::BOTTOM)
                .border_style(theme::muted())
                .title(Span::styled(format!(" {title} "), theme::bold()))
                .title_bottom(Span::styled(format!(" {hint} "), theme::muted()));
            let inner = block.inner(area);
            f.render_widget(block, area);
            Rect { x: inner.x + 1, width: inner.width.saturating_sub(2), ..inner }
        };
        match &mut self.view {
            View::None => {}
            View::Palette(p) => {
                let inner = popup(f, p.title.to_string(), 70, 70);
                let [q, list] = Layout::vertical([Constraint::Length(2), Constraint::Fill(1)]).areas(inner);
                let query = Line::from(vec![
                    Span::styled("❯ ", theme::accent()),
                    Span::raw(p.query.clone()),
                    Span::styled(if p.query.is_empty() { "  type to search" } else { "" }, theme::muted()),
                ]);
                f.render_widget(Paragraph::new(vec![query, Line::from(Span::styled("─".repeat(inner.width as usize), theme::muted()))]), q);
                f.set_cursor_position((q.x + 2 + width(&p.query) as u16, q.y));
                f.render_widget(Paragraph::new(p.lines(list.width as usize, list.height as usize)), list);
            }
            View::Step { index, scroll } => {
                let steps = all_steps(&self.store.conv().messages);
                let n = steps.len();
                let Some(fl) = steps.get((*index).min(n.saturating_sub(1))) else {
                    self.view = View::None;
                    return;
                };
                let inner = full(f, "Step".into(), "←/→ previous/next · ↑↓ scroll · c copy result · Esc close");
                let lines = views::step(fl, *index, n, inner.width as usize);
                *scroll = (*scroll).min(lines.len().saturating_sub(inner.height as usize));
                f.render_widget(Paragraph::new(lines).scroll((*scroll as u16, 0)), inner);
            }
            View::Trace { answer, row, scroll } => {
                let traced: Vec<(String, String)> = self
                    .store
                    .conv()
                    .messages
                    .iter()
                    .filter(|m| m.role == Role::Assistant && !m.streaming && m.trace_id.is_some())
                    .map(|m| (m.trace_id.clone().unwrap_or_default(), m.meta.clone().unwrap_or_default()))
                    .collect();
                let n = traced.len();
                let title = format!("Trace · Answer {} of {n}", *answer + 1);
                let inner = full(f, title, "←/→ answers · ↑↓ spans · PgUp/PgDn scroll · Esc close");
                let Some((id, meta)) = traced.get(*answer) else { return };
                let key = (self.store.current.clone(), id.clone());
                let (lines, at) = match self.store.traces.get(&key) {
                    Some(TraceLoad::Ready(spans)) => {
                        let count = crate::core::trace::layout(spans).len();
                        *row = (*row).min(count.saturating_sub(1));
                        let (mut lines, at) = views::trace(spans, *row, inner.width as usize);
                        lines.insert(0, Line::from(Span::styled(meta.clone(), theme::muted())));
                        lines.insert(1, Line::default());
                        (lines, at + 2)
                    }
                    Some(TraceLoad::Failed(e)) => (vec![Line::from(Span::styled(e.clone(), theme::muted()))], 0),
                    _ => (vec![Line::from(Span::styled("Loading trace…", theme::muted()))], 0),
                };
                let h = inner.height as usize;
                if at < *scroll {
                    *scroll = at;
                } else if at >= *scroll + h {
                    *scroll = at + 1 - h;
                }
                f.render_widget(Paragraph::new(lines).scroll((*scroll as u16, 0)), inner);
            }
            View::Overview { scroll } => {
                let st = &self.store;
                let s = summary(&st.conv().messages, st.now());
                let lines = views::overview(&s, st.conv().mode(), st.state.as_ref().and_then(|x| x.model.as_deref()), st.context_use(), 60);
                let inner = popup(f, "Run overview".into(), 60, 70);
                *scroll = (*scroll).min(lines.len().saturating_sub(inner.height as usize));
                f.render_widget(Paragraph::new(lines).scroll((*scroll as u16, 0)), inner);
            }
            View::Help => {
                let inner = popup(f, "Keys".into(), 80, 80);
                f.render_widget(Paragraph::new(views::help(inner.width as usize)), inner);
            }
            View::Confirm(c) => {
                let (title, body, yes) = match c {
                    Confirm::Delete(_, t) => (
                        "Delete chat?",
                        format!("“{}” and its plan and traces will be deleted. This cannot be undone.", if t.is_empty() { "New chat" } else { t }),
                        "Delete",
                    ),
                    Confirm::AutoRun(_) => (
                        "Run tools without asking?",
                        "In this chat the agent will write files, run commands and use the network without stopping for you. Tools denied in the config stay denied. You can switch back at any time.".to_string(),
                        "Auto-run",
                    ),
                    Confirm::RemoveProject(_, name) => (
                        "Remove project?",
                        format!("“{name}” leaves the list and its chats are hidden until you add the folder again. No files are touched."),
                        "Remove",
                    ),
                };
                let no = match c {
                    Confirm::AutoRun(Some(m)) => format!("Skip to {}", m.label()),
                    _ => "Cancel".into(),
                };
                let inner = popup(f, title.into(), 50, 30);
                let mut lines = text::wrap(&[(body, Style::default())], inner.width as usize, Span::raw(""), Span::raw(""));
                lines.push(Line::default());
                lines.push(Line::from(vec![
                    Span::styled("[y] ", theme::err().add_modifier(Modifier::BOLD)),
                    Span::styled(yes, theme::err()),
                    Span::raw("    "),
                    Span::styled("[n] ", theme::accent()),
                    Span::raw(no),
                ]));
                f.render_widget(Paragraph::new(lines), inner);
            }
        }
    }
}
