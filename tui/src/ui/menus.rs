//! What each level of the palette lists, and what its choices do. A choice with choices behind it keeps the
//! palette open on them: a provider shows its models, a reasoning model its efforts, a chat or a project its
//! actions, a command its arguments.

use super::palette::{Action, InputKind, Item, Node, Palette, Step};
use super::{App, Focus, Out, View, theme};
use crate::api::{Model, SettingsChange, quote_arg};
use crate::core::conversations::listed;
use crate::core::format::{ago, compact};
use crate::core::reply::{Role, can_continue};
use crate::state::Mode;
use ratatui::crossterm::event::{KeyEvent, KeyModifiers};
use ratatui::text::{Line, Span};

pub const EFFORTS: [&str; 5] = ["off", "minimal", "low", "medium", "high"];

/// "free · reasoning · 200k context".
pub fn model_tags(m: &Model) -> Vec<String> {
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
    tags
}

/// The level behind a command's argument, when its values are known here.
pub fn arg_node(command: &str) -> Option<(Node, &'static str)> {
    Some(match command {
        "provider" => (Node::Providers, "Provider"),
        "model" => (Node::Models, "Model"),
        "effort" => (Node::Efforts { after: None }, "Reasoning effort"),
        "plan" => (Node::Switch("plan"), "Plan mode"),
        "bypass" => (Node::Switch("bypass"), "Auto-run"),
        "workspace" => (Node::Projects, "Projects"),
        "help" => (Node::HelpTopics, "Help"),
        "trace" => (Node::Traces, "Traces"),
        _ => return None,
    })
}

impl App {
    pub(super) fn open_menu(&mut self, node: Node, title: &str) {
        self.store.load_models();
        self.view = View::Palette(Palette::new(node, title));
    }

    pub(super) fn open_palette(&mut self) {
        self.open_menu(Node::Root, "Go to");
    }

    pub(super) fn open_history(&mut self) {
        self.open_menu(Node::History, "History");
    }

    pub(super) fn open_models(&mut self) {
        self.open_menu(Node::Models, "Model");
    }

    fn push_level(&mut self, node: Node, title: impl Into<String>) {
        if let View::Palette(p) = &mut self.view {
            p.push(node, title);
        }
    }

    pub(super) fn palette_key(&mut self, k: KeyEvent) {
        let View::Palette(p) = &self.view else { return };
        let (items, _) = self.palette_items(&p.top().node, p);
        let page = self.page.saturating_sub(6).max(1);
        let ctrl = k.modifiers.contains(KeyModifiers::CONTROL);
        let View::Palette(p) = &mut self.view else { return };
        match p.key(k.code, ctrl, &items, page) {
            Step::Nothing => {}
            Step::Close => self.view = View::None,
            Step::Typed(InputKind::AddProject, path) => {
                self.view = View::None;
                self.store.workspace(format!("workspace add {}", quote_arg(&path)));
                self.notify(&format!("Adding {path}…"));
            }
            Step::Run(a) => self.palette_run(a),
        }
    }

    /// A choice made in the palette: some lead to the next level, the others close it and act.
    fn palette_run(&mut self, a: Action) {
        let state = self.store.state.clone().unwrap_or_default();
        match a {
            Action::Provider(id) => {
                if state.provider.as_deref() != Some(&id) {
                    self.store.settings(SettingsChange { provider: Some(id.clone()), ..Default::default() });
                }
                self.push_level(Node::Models, id);
            }
            Action::Model(id, reasoning) => {
                if state.model.as_deref() != Some(&id) {
                    self.store.settings(SettingsChange { model: Some(id.clone()), ..Default::default() });
                }
                if reasoning && state.effort_supported != Some(false) {
                    self.push_level(Node::Efforts { after: Some(id) }, "Reasoning effort");
                } else {
                    self.view = View::None;
                }
            }
            Action::Effort(e) => {
                let current = state.reasoning_effort.clone().unwrap_or_else(|| "off".into());
                if current != e {
                    self.store.settings(SettingsChange { reasoning_effort: Some(e), ..Default::default() });
                }
                self.view = View::None;
            }
            Action::Done => self.view = View::None,
            other => {
                self.view = View::None;
                self.act(other);
            }
        }
    }

    pub(super) fn act(&mut self, a: Action) {
        match a {
            Action::NewChat => self.new_chat(),
            Action::Open(id) => self.open_chat(&id),
            Action::Command(name) => {
                self.focus = Focus::Composer;
                self.composer.set(&format!("/{name} "));
            }
            Action::Send(line) => {
                self.focus = Focus::Composer;
                self.scroll = 0;
                self.store.send(&line);
            }
            Action::Model(id, _) => self.store.settings(SettingsChange { model: Some(id), ..Default::default() }),
            Action::Effort(e) => self.store.settings(SettingsChange { reasoning_effort: Some(e), ..Default::default() }),
            Action::Provider(p) => self.store.settings(SettingsChange { provider: Some(p), ..Default::default() }),
            Action::Done => {}
            Action::Mode(m) => {
                if self.store.conv().busy() {
                    self.notify("Switch the mode once the answer ends.");
                } else if m == Mode::Auto && self.store.conv().mode() != Some(Mode::Auto) {
                    self.view = View::Confirm(super::Confirm::AutoRun(None));
                } else {
                    self.store.set_mode(m);
                }
            }
            Action::Delete(id) => {
                let title = self.store.conversations.iter().find(|c| c.id == id).map(|c| c.title.clone()).unwrap_or_default();
                self.view = View::Confirm(super::Confirm::Delete(id, title));
            }
            Action::Overview => self.view = View::Overview { scroll: 0 },
            Action::Trace(i) => self.open_trace(i),
            Action::AddProject(path) => self.store.workspace(format!("workspace add {}", quote_arg(&path))),
            Action::RemoveProject(path, name) => self.view = View::Confirm(super::Confirm::RemoveProject(path, name)),
            Action::NewChatIn(path) => {
                self.new_chat();
                self.store.workspace(format!("workspace use {}", quote_arg(&path)));
            }
            Action::Continue => self.store.send(crate::core::reply::CONTINUE_TEXT),
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
            Action::Copy(t) => {
                self.out.push(Out::Copy(t));
                self.notify("Copied.");
            }
            Action::Help => self.view = View::Help,
            Action::Recall(t) => {
                self.focus = Focus::Composer;
                self.composer.set(&t);
            }
        }
    }

    /// The items of a level and a line shown above them (loading, a warning), from what the store holds now.
    pub(super) fn palette_items(&self, node: &Node, p: &Palette) -> (Vec<Item>, Option<Line<'static>>) {
        let s = &self.store;
        let state = s.state.clone().unwrap_or_default();
        let provider = state.provider.clone().unwrap_or_default();
        let model = state.model.clone().unwrap_or_default();
        let effort = state.reasoning_effort.clone().unwrap_or_else(|| "off".into());
        let mut note = None;
        let items = match node {
            Node::Root => self.root_items(&p.top().query, &state),
            Node::Providers => state
                .providers
                .iter()
                .map(|x| {
                    let mut d = vec![];
                    if x.id == provider {
                        d.push("current".to_string());
                    }
                    if !x.connected {
                        d.push("not connected: its API key is missing or it is disabled in panic.json".into());
                    }
                    Item::new("Provider", x.id.clone(), d.join(" · "), Action::Provider(x.id.clone())).leads_on()
                })
                .collect(),
            Node::Models => {
                let mut items = vec![
                    Item::menu("Settings", "Change provider", provider.clone(), Node::Providers, "Provider"),
                    Item::menu("Settings", "Reasoning effort", effort.clone(), Node::Efforts { after: None }, "Reasoning effort"),
                ];
                match s.current_models() {
                    Some(list) => {
                        if !model.is_empty() && !list.iter().any(|m| m.id == model) {
                            note =
                                Some(Line::from(Span::styled(format!(" ⚠ {model} is not a model of {provider}: pick one"), theme::warn())));
                        }
                        for m in list.iter().filter(|m| !p.free_only || m.free == Some(true)) {
                            items.push(self.model_item(m, &model));
                        }
                        if p.free_only {
                            note.get_or_insert_with(|| Line::from(Span::styled(" Free models only (Ctrl+F for all)", theme::muted())));
                        }
                    }
                    None => {
                        let why = match &s.models {
                            Some(Err(e)) if s.models_for == state.provider => format!(" The models of {provider} could not be listed: {e}"),
                            _ => format!(" Loading the models of {provider}…"),
                        };
                        note = Some(Line::from(Span::styled(why, theme::muted())));
                    }
                }
                items
            }
            Node::Efforts { after } => {
                let mut items = vec![];
                if let Some(m) = after {
                    items.push(Item::new("Effort", format!("Keep {effort}"), format!("for {m}"), Action::Done));
                }
                let mut levels: Vec<String> = EFFORTS.iter().map(|e| e.to_string()).collect();
                if !levels.contains(&effort) {
                    levels.push(effort.clone());
                }
                for e in levels {
                    let d = if e == effort { "current" } else { "" };
                    items.push(Item::new("Effort", e.clone(), d, Action::Effort(e)));
                }
                if state.effort_supported == Some(false) {
                    note = Some(Line::from(Span::styled(" This model takes no reasoning effort, so none is sent.", theme::muted())));
                }
                items
            }
            Node::ChatActions(id) => {
                let running = s.convs.get(id).is_some_and(|c| c.busy()) || s.conversations.iter().any(|c| &c.id == id && c.running);
                let mut items = vec![Item::new("Chat", "Open", "", Action::Open(id.clone()))];
                if !running {
                    items.push(Item::new("Chat", "Delete…", "with its plan and traces", Action::Delete(id.clone())).alert());
                }
                items.push(Item::new("Chat", "Copy its id", id.clone(), Action::Copy(id.clone())));
                items
            }
            Node::ProjectActions { path, name } => vec![
                Item::new("Project", "New chat here", path.clone(), Action::NewChatIn(path.clone())),
                Item::new("Project", "Copy the path", path.clone(), Action::Copy(path.clone())),
                Item::new(
                    "Project",
                    "Remove…",
                    "its chats are hidden; no files are touched",
                    Action::RemoveProject(path.clone(), name.clone()),
                )
                .alert(),
            ],
            Node::Projects => {
                let mut items = vec![];
                if let (Some(ws), Some(dir)) = (&s.workspaces, &s.cwd)
                    && ws.project_at(dir).is_none()
                {
                    items.push(Item::new("Add", "Add this folder", dir.clone(), Action::AddProject(dir.clone())));
                }
                items.push(Item::menu("Add", "Add a folder…", "type its path", Node::Input(InputKind::AddProject), "Add a folder"));
                for pr in s.workspaces.iter().flat_map(|w| &w.projects) {
                    items.push(
                        Item::new("Projects", format!("New chat in {}", pr.name), pr.path.clone(), Action::NewChatIn(pr.path.clone()))
                            .with_children(Node::ProjectActions { path: pr.path.clone(), name: pr.name.clone() }, pr.name.clone()),
                    );
                }
                if s.workspaces.is_none() {
                    note = Some(Line::from(Span::styled(" This server has no workspace plugin.", theme::muted())));
                }
                items
            }
            Node::Switch(name) => {
                let c = s.conv();
                let (on_mode, toggle) = if *name == "plan" { (Mode::Plan, c.mode) } else { (Mode::Auto, c.permissions) };
                if toggle.is_none() {
                    note = Some(Line::from(Span::styled(" This server has no plugin for it.", theme::muted())));
                }
                let on = toggle.is_some_and(|t| t.on);
                vec![
                    Item::new("Switch", "on", if on { "current" } else { on_mode.description() }, Action::Mode(on_mode)),
                    Item::new("Switch", "off", if on { "" } else { "current" }, Action::Mode(Mode::Ask)),
                ]
            }
            Node::HelpTopics => state
                .commands
                .iter()
                .map(|c| Item::new("Help", format!("/{}", c.usage), c.description.clone(), Action::Send(format!("/help {}", c.name))))
                .collect(),
            Node::Traces => self
                .traced()
                .iter()
                .enumerate()
                .map(|(i, m)| Item::new("Traces", format!("Answer {}", i + 1), m.meta.clone().unwrap_or_default(), Action::Trace(i)))
                .rev()
                .collect(),
            Node::History => self
                .composer
                .history()
                .iter()
                .rev()
                .map(|h| {
                    Item::new("History", h.replace('\n', " ⏎ "), "", Action::Recall(h.clone()))
                        .with_children(Node::HistoryItem(h.clone()), "Prompt")
                })
                .collect(),
            Node::HistoryItem(h) => vec![
                Item::new("Prompt", "Put it in the message box", "", Action::Recall(h.clone())),
                Item::new("Prompt", "Send it now", "", Action::Send(h.clone())),
                Item::new("Prompt", "Copy it", "", Action::Copy(h.clone())),
            ],
            Node::Input(InputKind::AddProject) => {
                note = Some(Line::from(Span::styled(" A folder on the computer that runs Panic, e.g. /home/me/app", theme::muted())));
                vec![]
            }
        };
        (items, note)
    }

    fn model_item(&self, m: &Model, current: &str) -> Item {
        let mut tags = model_tags(m);
        if m.id == current {
            tags.insert(0, "current".into());
        }
        let reasoning = m.reasoning == Some(true);
        let it = Item::new("Models", m.id.clone(), tags.join(" · "), Action::Model(m.id.clone(), reasoning));
        if reasoning { it.leads_on() } else { it }
    }

    fn root_items(&self, query: &str, state: &crate::api::State) -> Vec<Item> {
        let s = &self.store;
        let conv = s.conv();
        let now = s.now();
        let provider = state.provider.clone().unwrap_or_default();
        let model = state.model.clone().unwrap_or_else(|| "No model".into());
        let mut items = vec![Item::new("Actions", "New chat", "Ctrl+N", Action::NewChat)];
        if conv.messages.last().is_some_and(can_continue) {
            items.push(Item::new("Actions", "Continue where it stopped", "", Action::Continue));
        }
        items.push(Item::menu("Actions", "Model", format!("{model} · Ctrl+P"), Node::Models, "Model"));
        items.push(Item::menu("Actions", "Provider", provider, Node::Providers, "Provider"));
        items.push(Item::menu(
            "Actions",
            "Reasoning effort",
            state.reasoning_effort.clone().unwrap_or_else(|| "off".into()),
            Node::Efforts { after: None },
            "Reasoning effort",
        ));
        items.push(Item::new("Actions", "Run overview", "Ctrl+O", Action::Overview));
        let traced = self.traced().len();
        if traced > 0 {
            items.push(
                Item::new("Actions", "Trace of the last answer", "where the time went", Action::Trace(traced - 1))
                    .with_children(Node::Traces, "Traces"),
            );
        }
        items.push(Item::new("Actions", "Copy the last answer", "", Action::CopyLast));
        if !conv.fresh {
            items.push(Item::new("Actions", "Delete this chat…", "with its plan and traces", Action::Delete(conv.id.clone())).alert());
        }
        items.push(Item::menu("Actions", "Prompt history", "Ctrl+R", Node::History, "History"));
        items.push(Item::new("Actions", "Keys", "?", Action::Help));
        for id in s.needs_you() {
            let title = s.conversations.iter().find(|c| c.id == id).map(|c| c.title.clone()).unwrap_or_default();
            let title = if title.is_empty() { "New chat".to_string() } else { title };
            items.push(
                Item::new("Needs you", title.clone(), "waiting · Ctrl+J", Action::Open(id.clone()))
                    .alert()
                    .with_children(Node::ChatActions(id), title),
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
            items.push(
                Item::new("Chats", title.clone(), detail.join(" · "), Action::Open(l.chat.id.clone()))
                    .with_children(Node::ChatActions(l.chat.id), title),
            );
        }
        if let Some(current) = conv.mode() {
            for m in Mode::available(conv.mode, conv.permissions) {
                let detail = if m == current { format!("current · {}", m.description()) } else { m.description().to_string() };
                let it = Item::new("Mode", m.label(), detail, Action::Mode(m));
                items.push(if m == Mode::Auto { it.alert() } else { it });
            }
        }
        if s.workspaces.is_some() {
            items.push(Item::menu("Projects", "Projects", "add a folder, new chat in a project, remove", Node::Projects, "Projects"));
            for pr in s.workspaces.iter().flat_map(|w| &w.projects) {
                items.push(
                    Item::new("Projects", format!("New chat in {}", pr.name), pr.path.clone(), Action::NewChatIn(pr.path.clone()))
                        .with_children(Node::ProjectActions { path: pr.path.clone(), name: pr.name.clone() }, pr.name.clone()),
                );
            }
        }
        for c in state.commands.iter().filter(|c| c.name != "exit") {
            let label = format!("/{}", c.usage);
            items.push(match arg_node(&c.name) {
                Some((node, title)) => {
                    Item::new("Commands", label, c.description.clone(), Action::Command(c.name.clone())).with_children(node, title)
                }
                None => Item::new("Commands", label, c.description.clone(), Action::Command(c.name.clone())),
            });
        }
        // the models are many: they are searched, not listed
        if !query.trim().is_empty()
            && let Some(list) = s.current_models()
        {
            let current = state.model.clone().unwrap_or_default();
            items.extend(list.iter().map(|m| self.model_item(m, &current)));
        }
        items
    }

    /// Values for a command's argument as the composer suggests them: (the line to fill in, label, detail).
    pub(super) fn arg_suggestions(&self, command: &str, typed: &str) -> Vec<(String, String, String)> {
        let Some((node, _)) = arg_node(command) else { return vec![] };
        let p = Palette::new(node.clone(), "");
        let (items, _) = self.palette_items(&node, &p);
        items
            .into_iter()
            .filter_map(|it| {
                let value = match it.action? {
                    Action::Provider(v) | Action::Model(v, _) | Action::Effort(v) => v,
                    Action::Mode(_) => it.label.clone(),
                    Action::Send(line) => line.trim_start_matches("/help ").to_string(),
                    Action::NewChatIn(path) => format!("use {}", quote_arg(&path)),
                    Action::AddProject(path) => format!("add {}", quote_arg(&path)),
                    _ => return None,
                };
                value.starts_with(typed).then(|| (format!("/{command} {value}"), value, it.detail))
            })
            .take(8)
            .collect()
    }
}
