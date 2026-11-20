//! The palette: one popup to find anything, in levels. A choice with choices behind it (a provider and its
//! models, a model and its effort, a chat and its actions, a command and its arguments) opens them in the
//! same popup, with a breadcrumb and a way back, instead of closing and making you search again.
//!
//! This module is the mechanism (levels, filtering, keys, drawing); what each level lists comes from the
//! app (`App::palette_items`), built again at every draw so lists that load later (models) appear in place.

use super::text::{fit, width};
use super::theme;
use crate::state::Mode;
use ratatui::text::{Line, Span};

/// What a level lists.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Node {
    Root,
    Providers,
    Models,
    /// Reasoning efforts; `after`: the model just picked (the first item keeps the effort as it is).
    Efforts {
        after: Option<String>,
    },
    ChatActions(String),
    ProjectActions {
        path: String,
        name: String,
    },
    /// Projects to work on: add the terminal's folder or another, and each project's actions.
    Projects,
    /// `/plan` or `/bypass`: on or off.
    Switch(&'static str),
    /// `/help`: the commands.
    HelpTopics,
    /// `/trace`: the answers with a trace.
    Traces,
    History,
    HistoryItem(String),
    /// A line of text to type: a folder to add.
    Input(InputKind),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputKind {
    AddProject,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    NewChat,
    Open(String),
    /// Puts `/name ` in the composer.
    Command(String),
    /// Runs a line as if typed.
    Send(String),
    Provider(String),
    /// A model, and whether it takes a reasoning effort.
    Model(String, bool),
    Effort(String),
    /// Leaves things as they are and closes.
    Done,
    Mode(Mode),
    Delete(String),
    Overview,
    /// The trace of the answer at this index among the traced ones.
    Trace(usize),
    AddProject(String),
    RemoveProject(String, String),
    NewChatIn(String),
    Continue,
    CopyLast,
    Copy(String),
    Help,
    /// A prompt from the history, put back in the composer.
    Recall(String),
}

#[derive(Debug, Clone)]
pub struct Item {
    /// The section it belongs to: "Chats", "Models", ...
    pub section: &'static str,
    pub label: String,
    pub detail: String,
    /// Drawn in the warning colour (a chat that needs you, a destructive action).
    pub alert: bool,
    /// What Enter does; None: Enter opens `children`.
    pub action: Option<Action>,
    /// The choices behind it, opened with → (or Enter when there is no action).
    pub children: Option<(Node, String)>,
    /// Shown with › although it has no `children`: its action opens the next level itself (a provider, a model).
    pub leads_on: bool,
}

impl Item {
    pub fn new(section: &'static str, label: impl Into<String>, detail: impl Into<String>, action: Action) -> Self {
        Item { section, label: label.into(), detail: detail.into(), alert: false, action: Some(action), children: None, leads_on: false }
    }

    /// An item whose only purpose is the level behind it.
    pub fn menu(section: &'static str, label: impl Into<String>, detail: impl Into<String>, node: Node, title: impl Into<String>) -> Self {
        Item {
            section,
            label: label.into(),
            detail: detail.into(),
            alert: false,
            action: None,
            children: Some((node, title.into())),
            leads_on: false,
        }
    }

    pub fn alert(mut self) -> Self {
        self.alert = true;
        self
    }

    pub fn with_children(mut self, node: Node, title: impl Into<String>) -> Self {
        self.children = Some((node, title.into()));
        self
    }

    pub fn leads_on(mut self) -> Self {
        self.leads_on = true;
        self
    }

    fn more(&self) -> bool {
        self.children.is_some() || self.leads_on
    }
}

#[derive(Debug, Clone)]
pub struct Level {
    pub node: Node,
    pub title: String,
    pub query: String,
    pub selected: usize,
}

pub struct Palette {
    pub stack: Vec<Level>,
    /// Models: only the free ones.
    pub free_only: bool,
}

/// How well `query` matches `text`: its letters in order, better when they are together and start words.
pub fn score(query: &str, text: &str) -> Option<i64> {
    if query.is_empty() {
        return Some(0);
    }
    let t: Vec<char> = text.to_lowercase().chars().collect();
    let mut s = 0i64;
    let mut ti = 0;
    let mut last: Option<usize> = None;
    for q in query.to_lowercase().chars().filter(|c| !c.is_whitespace()) {
        let found = (ti..t.len()).find(|&i| t[i] == q)?;
        s += 1;
        if last.is_some_and(|l| l + 1 == found) {
            s += 4;
        }
        if found == 0 || !t[found - 1].is_alphanumeric() {
            s += 3;
        }
        s -= (found - ti).min(10) as i64 / 3;
        last = Some(found);
        ti = found + 1;
    }
    Some(s)
}

/// What a key did to the palette, for the app to carry out.
#[derive(Debug, PartialEq, Eq)]
pub enum Step {
    Nothing,
    Close,
    Run(Action),
    /// The text typed in an input level.
    Typed(InputKind, String),
}

impl Palette {
    pub fn new(node: Node, title: impl Into<String>) -> Self {
        Palette { stack: vec![Level { node, title: title.into(), query: String::new(), selected: 0 }], free_only: false }
    }

    pub fn top(&self) -> &Level {
        self.stack.last().expect("a palette has a level")
    }

    fn top_mut(&mut self) -> &mut Level {
        self.stack.last_mut().expect("a palette has a level")
    }

    /// Opens a level. One already open further back (Model › Provider › openrouter, then Provider again) is
    /// gone back to instead, so switching back and forth never grows the breadcrumb.
    pub fn push(&mut self, node: Node, title: impl Into<String>) {
        let mut title = title.into();
        if let Some(i) = self.stack.iter().position(|l| l.node == node) {
            if i == 0 {
                title = self.stack[0].title.clone();
            }
            self.stack.truncate(i);
        }
        self.stack.push(Level { node, title, query: String::new(), selected: 0 });
    }

    /// Back one level; false at the first one.
    pub fn pop(&mut self) -> bool {
        if self.stack.len() > 1 {
            self.stack.pop();
            true
        } else {
            false
        }
    }

    /// "Go to › Provider › openrouter".
    pub fn breadcrumb(&self) -> String {
        self.stack.iter().map(|l| l.title.as_str()).collect::<Vec<_>>().join(" › ")
    }

    pub fn is_input(&self) -> bool {
        matches!(self.top().node, Node::Input(_))
    }

    /// The items matching the level's query, best first (in their sections' order when it is empty).
    pub fn matches<'a>(&self, items: &'a [Item]) -> Vec<&'a Item> {
        let query = self.top().query.trim();
        if query.is_empty() || self.is_input() {
            return items.iter().collect();
        }
        let mut scored: Vec<(i64, usize, &Item)> = items
            .iter()
            .enumerate()
            .filter_map(|(i, it)| {
                let hay = format!("{} {} {}", it.label, it.detail, it.section);
                score(query, &hay).map(|s| (s + score(query, &it.label).unwrap_or(0), i, it))
            })
            .collect();
        scored.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        scored.into_iter().map(|(_, _, it)| it).collect()
    }

    pub fn chosen<'a>(&self, items: &'a [Item]) -> Option<&'a Item> {
        self.matches(items).get(self.top().selected).copied()
    }

    fn move_by(&mut self, d: isize, n: usize) {
        if n == 0 {
            return;
        }
        let top = self.top_mut();
        top.selected = (top.selected as isize + d).rem_euclid(n as isize) as usize;
    }

    fn open(&mut self, it: &Item) {
        if let Some((node, title)) = &it.children {
            self.push(node.clone(), title.clone());
        }
    }

    /// A key on the palette, with the current level's items.
    pub fn key(&mut self, code: ratatui::crossterm::event::KeyCode, ctrl: bool, items: &[Item], page: usize) -> Step {
        use ratatui::crossterm::event::KeyCode as K;
        let n = self.matches(items).len();
        match code {
            K::Esc => Step::Close,
            K::Up => {
                self.move_by(-1, n);
                Step::Nothing
            }
            K::Down => {
                self.move_by(1, n);
                Step::Nothing
            }
            K::PageUp => {
                self.move_by(-(page as isize), n);
                Step::Nothing
            }
            K::PageDown => {
                self.move_by(page as isize, n);
                Step::Nothing
            }
            K::Left => {
                self.pop();
                Step::Nothing
            }
            K::Right | K::Tab if !self.is_input() => {
                if let Some(it) = self.chosen(items).cloned() {
                    match (&it.children, &it.action) {
                        (Some(_), _) => self.open(&it),
                        (None, Some(a)) if it.leads_on => return Step::Run(a.clone()),
                        _ => {}
                    }
                }
                Step::Nothing
            }
            K::Enter => {
                if let Node::Input(kind) = self.top().node {
                    let text = self.top().query.trim().to_string();
                    return if text.is_empty() { Step::Nothing } else { Step::Typed(kind, text) };
                }
                let Some(it) = self.chosen(items).cloned() else { return Step::Nothing };
                match it.action {
                    Some(a) => Step::Run(a),
                    None => {
                        self.open(&it);
                        Step::Nothing
                    }
                }
            }
            K::Backspace => {
                if self.top().query.is_empty() {
                    self.pop();
                } else {
                    let top = self.top_mut();
                    top.query.pop();
                    top.selected = 0;
                }
                Step::Nothing
            }
            K::Char('u') if ctrl => {
                let top = self.top_mut();
                top.query.clear();
                top.selected = 0;
                Step::Nothing
            }
            K::Char('f') if ctrl && self.top().node == Node::Models => {
                self.free_only = !self.free_only;
                self.top_mut().selected = 0;
                Step::Nothing
            }
            K::Char(c) if !ctrl => {
                let top = self.top_mut();
                top.query.push(c);
                top.selected = 0;
                Step::Nothing
            }
            _ => Step::Nothing,
        }
    }

    pub fn paste(&mut self, text: &str) {
        let top = self.top_mut();
        top.query.push_str(text.lines().next().unwrap_or(""));
        top.selected = 0;
    }

    /// The list as lines `w` wide and `h` tall (a note first), scrolled to keep the selection in view.
    pub fn lines(&self, items: &[Item], note: Option<Line<'static>>, w: usize, h: usize) -> Vec<Line<'static>> {
        let mut out: Vec<Line<'static>> = note.into_iter().collect();
        if self.is_input() {
            return out;
        }
        let h = h.saturating_sub(out.len());
        let list = self.matches(items);
        if list.is_empty() {
            out.push(Line::from(Span::styled("  Nothing matches.", theme::muted())));
            return out;
        }
        let show_sections = self.top().query.trim().is_empty() && list.iter().any(|i| i.section != list[0].section);
        let mut rows: Vec<(Option<usize>, Line<'static>)> = vec![];
        let mut section = "";
        let selected = self.top().selected.min(list.len() - 1);
        for (i, it) in list.iter().enumerate() {
            if show_sections && it.section != section {
                section = it.section;
                rows.push((None, Line::from(Span::styled(it.section.to_string(), theme::muted()))));
            }
            let here = i == selected;
            let label_style = if it.alert { theme::warn() } else { Default::default() };
            let arrow = if it.more() { " ›" } else { "  " };
            let label = fit(&it.label, w.saturating_sub(8).max(10) * 2 / 3);
            let detail = fit(&it.detail, w.saturating_sub(width(&label) + 8));
            let pad = w.saturating_sub(width(&label) + width(&detail) + 3 + 2);
            let mut line = Line::from(vec![
                Span::styled(if here { " › " } else { "   " }, theme::accent()),
                Span::styled(label, label_style),
                Span::raw(" ".repeat(pad)),
                Span::styled(detail, theme::muted()),
                Span::styled(arrow, theme::accent()),
            ]);
            if here {
                line = line.patch_style(theme::selected());
            }
            rows.push((Some(i), line));
        }
        let at = rows.iter().position(|(i, _)| *i == Some(selected)).unwrap_or(0);
        let start = if at < h { 0 } else { (at + 1 - h).min(rows.len().saturating_sub(h)) };
        out.extend(rows.into_iter().skip(start).take(h).map(|(_, l)| l));
        out
    }

    /// The hint under the list.
    pub fn hint(&self) -> &'static str {
        match self.top().node {
            Node::Input(_) => "Enter add · Esc close · ← back",
            Node::Models => "Enter pick · → more · ← back · Ctrl+F free only · Esc close",
            _ if self.stack.len() > 1 => "Enter pick · → more · ← or Backspace back · Esc close",
            _ => "Enter pick · → more · Esc close",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratatui::crossterm::event::KeyCode as K;

    #[test]
    fn scores_letters_in_order() {
        assert!(score("nc", "New chat").is_some());
        assert!(score("xyz", "New chat").is_none());
        assert!(score("son", "sonnet-5.5").unwrap() > score("son", "sentence on").unwrap());
    }

    fn root() -> Vec<Item> {
        vec![
            Item::new("Chats", "Fix the resume bug", "app · 5m", Action::Open("a".into()))
                .with_children(Node::ChatActions("a".into()), "Fix the resume bug"),
            Item::menu("Settings", "Provider", "mistral", Node::Providers, "Provider"),
            Item::new("Actions", "New chat", "Ctrl+N", Action::NewChat),
        ]
    }

    #[test]
    fn enter_runs_or_opens_and_back_returns() {
        let items = root();
        let mut p = Palette::new(Node::Root, "Go to");
        assert_eq!(p.key(K::Enter, false, &items, 5), Step::Run(Action::Open("a".into())), "a chat opens on Enter");
        assert_eq!(p.key(K::Right, false, &items, 5), Step::Nothing);
        assert_eq!(p.top().node, Node::ChatActions("a".into()), "→ shows the chat's actions");
        assert_eq!(p.breadcrumb(), "Go to › Fix the resume bug");
        p.key(K::Left, false, &items, 5);
        assert_eq!(p.top().node, Node::Root);
        p.key(K::Down, false, &items, 5);
        p.key(K::Enter, false, &items, 5);
        assert_eq!(p.top().node, Node::Providers, "a menu opens on Enter");
        assert_eq!(p.key(K::Backspace, false, &[], 5), Step::Nothing);
        assert_eq!(p.top().node, Node::Root, "Backspace on an empty query goes back");
        assert!(!p.pop(), "the first level stays");
    }

    #[test]
    fn each_level_filters_on_its_own() {
        let items = root();
        let mut p = Palette::new(Node::Root, "Go to");
        for c in "new".chars() {
            p.key(K::Char(c), false, &items, 5);
        }
        assert_eq!(p.chosen(&items).map(|i| i.label.as_str()), Some("New chat"));
        p.push(Node::Providers, "Provider");
        assert_eq!(p.top().query, "");
        p.pop();
        assert_eq!(p.top().query, "new", "going back keeps what was typed");
        p.key(K::Backspace, false, &items, 5);
        assert_eq!(p.top().query, "ne");
    }

    #[test]
    fn going_to_an_open_level_goes_back_to_it() {
        let mut p = Palette::new(Node::Models, "Model");
        p.push(Node::Providers, "Provider");
        p.push(Node::Models, "openrouter");
        assert_eq!(p.breadcrumb(), "Model", "the models are the first level again");
        p.push(Node::Providers, "Provider");
        assert_eq!(p.breadcrumb(), "Model › Provider");
    }

    #[test]
    fn an_input_level_hands_back_the_text() {
        let mut p = Palette::new(Node::Root, "Go to");
        p.push(Node::Input(InputKind::AddProject), "Add a folder");
        assert_eq!(p.key(K::Enter, false, &[], 5), Step::Nothing, "nothing typed yet");
        for c in "/w/app".chars() {
            p.key(K::Char(c), false, &[], 5);
        }
        assert_eq!(p.key(K::Enter, false, &[], 5), Step::Typed(InputKind::AddProject, "/w/app".into()));
    }

    #[test]
    fn an_item_that_leads_on_runs_on_right_too() {
        let items = vec![Item::new("Provider", "openrouter", "", Action::Provider("openrouter".into())).leads_on()];
        let mut p = Palette::new(Node::Providers, "Provider");
        assert_eq!(p.key(K::Right, false, &items, 5), Step::Run(Action::Provider("openrouter".into())));
        let lines = p.lines(&items, None, 40, 5);
        assert!(lines[0].spans.iter().any(|s| s.content == " ›"));
    }
}
