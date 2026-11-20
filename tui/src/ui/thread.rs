//! The conversation as lines: one line per tool call, diffs open only in the turn that runs or came last,
//! older turns folded to their text and one line per run of calls, and a summary line under every answer.

use super::text::{fit, markdown, width, wrap};
use super::theme;
use crate::core::diff::{FileDiff, LineKind, describe_change, diff_stats, line_delta, step_diff};
use crate::core::format::{duration, failure, shell_command, size, step_label};
use crate::core::reply::{Message, Part, Role, Status, SubAgent, ToolStep, can_continue, reasoning_label, thought_tail};
use crate::core::summary::turn;
use crate::state::Conv;
use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};
use std::collections::HashSet;

/// What a row is about, for the keyboard: Enter opens it, Space folds it, `c` copies it.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum Target {
    Tool {
        msg: u64,
        step: String,
    },
    /// A run of tool calls folded into one line.
    Group {
        msg: u64,
        index: usize,
    },
    Reasoning {
        msg: u64,
        index: usize,
    },
    /// The summary line of an answer.
    Turn {
        msg: u64,
    },
    Message {
        msg: u64,
    },
}

pub struct Row {
    pub line: Line<'static>,
    pub target: Option<Target>,
}

/// Folds the user changed: open ones and closed ones win over the defaults.
#[derive(Default)]
pub struct Folds {
    pub open: HashSet<String>,
    pub closed: HashSet<String>,
}

impl Folds {
    fn is_open(&self, key: &str, default: bool) -> bool {
        if self.open.contains(key) {
            true
        } else if self.closed.contains(key) {
            false
        } else {
            default
        }
    }

    pub fn toggle(&mut self, key: String, default: bool) {
        let now = self.is_open(&key, default);
        self.open.remove(&key);
        self.closed.remove(&key);
        if now {
            self.closed.insert(key);
        } else {
            self.open.insert(key);
        }
    }
}

/// The fold key of a target and whether it is open by default, for Space.
pub fn fold_key(conv: &Conv, t: &Target) -> Option<(String, bool)> {
    let last = last_answer(conv);
    match t {
        Target::Tool { msg, step } => Some((format!("tool:{msg}:{step}"), true)),
        Target::Group { msg, index } => Some((format!("group:{msg}:{index}"), false)),
        Target::Reasoning { msg, index } => Some((format!("reason:{msg}:{index}"), false)),
        Target::Turn { msg } => Some((format!("turn:{msg}"), Some(*msg) == last)),
        _ => None,
    }
}

fn last_answer(conv: &Conv) -> Option<u64> {
    conv.messages.iter().rev().find(|m| m.role == Role::Assistant).map(|m| m.id)
}

pub struct Ctx<'a> {
    pub width: usize,
    pub now: i64,
    pub tick: usize,
    pub folds: &'a Folds,
}

/// Lines a diff shows in the thread before "N more lines": the first 20, when it has more than 22.
pub const DIFF_CUT: usize = 20;

pub fn build(conv: &Conv, cx: &Ctx) -> Vec<Row> {
    let mut rows = vec![];
    let last = last_answer(conv);
    for (i, m) in conv.messages.iter().enumerate() {
        if i > 0 {
            rows.push(Row { line: Line::default(), target: None });
        }
        match m.role {
            Role::User => {
                if m.text.is_empty() {
                    continue;
                }
                let t = Some(Target::Message { msg: m.id });
                for line in wrap(&[(m.text.clone(), theme::bold())], cx.width, Span::styled("❯ ", theme::accent()), Span::raw("  ")) {
                    rows.push(Row { line, target: t.clone() });
                }
            }
            Role::Command => command(m, cx, &mut rows),
            Role::Assistant => {
                let open = cx.folds.is_open(&format!("turn:{}", m.id), m.streaming || Some(m.id) == last);
                answer(m, open, cx, &mut rows);
            }
        }
    }
    if let Some(m) = conv.messages.last()
        && can_continue(m)
    {
        rows.push(Row { line: Line::from(Span::styled("  Stopped at a limit. Type /continue to go on.", theme::warn())), target: None });
    }
    rows
}

fn command(m: &Message, cx: &Ctx, rows: &mut Vec<Row>) {
    let t = Some(Target::Message { msg: m.id });
    let line = m.meta.clone().unwrap_or_default();
    rows.push(Row { line: Line::from(vec![Span::styled("$ ", theme::muted()), Span::styled(line, theme::muted())]), target: t.clone() });
    let style = if m.error { theme::err() } else { Style::default() };
    let out = m.text.strip_suffix('\n').unwrap_or(&m.text);
    if !out.is_empty() {
        for line in wrap(&[(out.to_string(), style)], cx.width, Span::raw("  "), Span::raw("  ")) {
            rows.push(Row { line, target: t.clone() });
        }
    }
    for p in &m.parts {
        if let Part::Tool(s) = p {
            rows.push(tool_line(m.id, s, cx, "  "));
        }
    }
}

fn answer(m: &Message, open: bool, cx: &Ctx, rows: &mut Vec<Row>) {
    let mut i = 0;
    let mut wrote = false;
    while i < m.parts.len() {
        match &m.parts[i] {
            Part::Reasoning { text, started_at, ended_at } => {
                let t = Target::Reasoning { msg: m.id, index: i };
                let shown = cx.folds.is_open(&format!("reason:{}:{i}", m.id), false);
                let label = reasoning_label(*started_at, *ended_at, cx.now);
                let marker = if shown { "▾" } else { "▸" };
                let live = ended_at.is_none() && m.streaming;
                let style = if live { theme::accent() } else { theme::muted() };
                rows.push(Row {
                    line: Line::from(vec![
                        Span::styled("∴ ", theme::muted()),
                        Span::styled(label, style.add_modifier(Modifier::ITALIC)),
                        Span::styled(format!(" {marker}"), theme::muted()),
                    ]),
                    target: Some(t.clone()),
                });
                let body = if shown {
                    text.clone()
                } else if live {
                    thought_tail(text, 3, cx.width.saturating_sub(4) * 3)
                } else {
                    String::new()
                };
                if !body.is_empty() {
                    let bar = Span::styled("  │ ", theme::muted());
                    for line in wrap(&[(body, theme::muted().add_modifier(Modifier::ITALIC))], cx.width, bar.clone(), bar) {
                        rows.push(Row { line, target: Some(t.clone()) });
                    }
                }
                i += 1;
            }
            Part::Text(text) => {
                let prefix = if wrote { Span::raw("  ") } else { Span::styled("● ", theme::accent()) };
                wrote = true;
                for line in markdown(text, cx.width, prefix, Span::raw("  ")) {
                    rows.push(Row { line, target: Some(Target::Message { msg: m.id }) });
                }
                i += 1;
            }
            Part::Tool(_) => {
                let start = i;
                let mut steps: Vec<&ToolStep> = vec![];
                while let Some(Part::Tool(s)) = m.parts.get(i) {
                    steps.push(s);
                    i += 1;
                }
                if open || steps.len() == 1 {
                    for s in &steps {
                        tools(m, s, open, cx, rows, "");
                    }
                } else {
                    group(m, start, &steps, cx, rows);
                }
            }
            Part::Review { pass, reason, error, .. } => {
                let text = match (error, pass) {
                    (Some(e), _) => Some(format!("Review failed: {e}")),
                    (None, false) => Some(format!("Review sent the answer back: {reason}")),
                    _ => None,
                };
                if let Some(t) = text {
                    for line in wrap(&[(t, theme::warn())], cx.width, Span::styled("◆ ", theme::warn()), Span::raw("  ")) {
                        rows.push(Row { line, target: None });
                    }
                }
                i += 1;
            }
            Part::Error(text) => {
                for line in wrap(&[(text.clone(), theme::err())], cx.width, Span::styled("✗ ", theme::err()), Span::raw("  ")) {
                    rows.push(Row { line, target: None });
                }
                i += 1;
            }
        }
    }
    if !m.streaming && (m.meta.is_some() || !m.parts.is_empty()) {
        rows.push(summary_line(m, cx));
    }
}

/// A run of calls in an old turn: "▸ 3 tool calls · +6 −2 · 1 failed"; Space opens it.
fn group(m: &Message, index: usize, steps: &[&ToolStep], cx: &Ctx, rows: &mut Vec<Row>) {
    let key = format!("group:{}:{index}", m.id);
    let open = cx.folds.is_open(&key, false);
    let (mut added, mut removed, mut failed) = (0, 0, 0);
    for s in steps {
        if s.status == Status::Error {
            failed += 1;
        }
        if s.status == Status::Ok && (s.name == "write_file" || s.name == "edit_file") {
            let (a, r) = line_delta(s);
            added += a;
            removed += r;
        }
    }
    let mut spans = vec![
        Span::styled(if open { "▾ " } else { "▸ " }, theme::muted()),
        Span::styled(format!("{} tool calls", steps.len()), theme::muted()),
    ];
    if added + removed > 0 {
        spans.push(Span::styled(" · ", theme::muted()));
        spans.push(Span::styled(format!("+{added}"), theme::ok()));
        spans.push(Span::styled(format!(" −{removed}"), theme::err()));
    }
    if failed > 0 {
        spans.push(Span::styled(format!(" · {failed} failed"), theme::err()));
    }
    rows.push(Row { line: Line::from(spans), target: Some(Target::Group { msg: m.id, index }) });
    if open {
        for s in steps {
            tools(m, s, false, cx, rows, "  ");
        }
    }
}

fn icon(s: &ToolStep, tick: usize) -> Span<'static> {
    match s.status {
        Status::Drafting | Status::Running => Span::styled(theme::SPINNER[tick % theme::SPINNER.len()], theme::accent()),
        Status::Ok => Span::styled("✓", theme::ok()),
        Status::Error => Span::styled("✗", theme::err()),
    }
}

/// One call: "✓ edit_file  reply.ts            +6 −2".
pub fn tool_line(msg: u64, s: &ToolStep, cx: &Ctx, indent: &str) -> Row {
    let name = if s.name.is_empty() { "tool".to_string() } else { s.name.clone() };
    let label = if s.status == Status::Drafting {
        "writing the call…".to_string()
    } else {
        let l = step_label(&s.name, &s.arguments);
        if l.is_empty() { shell_command(&s.arguments).unwrap_or_default() } else { l }
    };
    let mut right: Vec<Span<'static>> = vec![];
    match s.status {
        Status::Error => right.push(Span::styled(failure(s.content.as_deref()), theme::err())),
        Status::Ok => {
            let (a, r) = if s.name == "write_file" || s.name == "edit_file" { line_delta(s) } else { (0, 0) };
            if a + r > 0 {
                right.push(Span::styled(format!("+{a}"), theme::ok()));
                right.push(Span::styled(format!(" −{r}"), theme::err()));
            } else if let Some(c) = &s.content {
                right.push(Span::styled(size(c), theme::muted()));
            }
            if let (Some(st), Some(en)) = (s.started_at, s.ended_at)
                && en - st >= 1000
            {
                right.push(Span::styled(format!("  {}", duration(en - st)), theme::muted()));
            }
        }
        _ => {
            if let Some(st) = s.started_at
                && cx.now - st >= 1000
            {
                right.push(Span::styled(duration(cx.now - st), theme::muted()));
            }
        }
    }
    let right_w: usize = right.iter().map(|s| width(&s.content)).sum();
    let left_fixed = width(indent) + 2 + width(&name) + 2;
    let label_w = cx.width.saturating_sub(left_fixed + right_w + 2);
    let label = fit(&label, label_w);
    let pad = cx.width.saturating_sub(left_fixed + width(&label) + right_w);
    let mut spans = vec![
        Span::raw(indent.to_string()),
        icon(s, cx.tick),
        Span::raw(" "),
        Span::styled(name, theme::bold()),
        Span::raw("  "),
        Span::styled(label, theme::muted()),
    ];
    if !right.is_empty() {
        spans.push(Span::raw(" ".repeat(pad)));
        spans.extend(right);
    }
    Row { line: Line::from(spans), target: Some(Target::Tool { msg, step: s.id.clone() }) }
}

/// A call with what it changed (in an open turn) and its sub-agent.
fn tools(m: &Message, s: &ToolStep, open: bool, cx: &Ctx, rows: &mut Vec<Row>, indent: &str) {
    rows.push(tool_line(m.id, s, cx, indent));
    let target = Target::Tool { msg: m.id, step: s.id.clone() };
    if open
        && cx.folds.is_open(&format!("tool:{}:{}", m.id, s.id), true)
        && let Some(d) = step_diff(s).filter(|d| d.line_count() > 0)
    {
        diff_lines(&d, cx.width, Some(DIFF_CUT), &format!("{indent}    "), &target, rows);
    }
    if let Some(agent) = &s.agent {
        sub_agent(m, s, agent, cx, rows, indent);
    }
}

fn sub_agent(m: &Message, s: &ToolStep, agent: &SubAgent, cx: &Ctx, rows: &mut Vec<Row>, indent: &str) {
    let calls = agent.parts.iter().filter(|p| matches!(p, Part::Tool(_))).count();
    let key = format!("agent:{}:{}", m.id, s.id);
    let open = cx.folds.is_open(&key, s.status == Status::Running);
    let name = if agent.name.is_empty() { "sub-agent" } else { &agent.name };
    rows.push(Row {
        line: Line::from(vec![
            Span::raw(format!("{indent}    ")),
            Span::styled(format!("↳ {name} · {calls} {}", if calls == 1 { "call" } else { "calls" }), theme::muted()),
            Span::styled(if open { " ▾" } else { " ▸" }, theme::muted()),
        ]),
        target: Some(Target::Tool { msg: m.id, step: s.id.clone() }),
    });
    if !open {
        return;
    }
    let inner = format!("{indent}      ");
    for p in &agent.parts {
        match p {
            Part::Tool(t) => rows.push(tool_line(m.id, t, cx, &inner)),
            Part::Text(t) => {
                for line in wrap(&[(t.trim().to_string(), theme::muted())], cx.width, Span::raw(inner.clone()), Span::raw(inner.clone())) {
                    rows.push(Row { line, target: None });
                }
            }
            Part::Reasoning { text, .. } => {
                let tail = thought_tail(text, 2, cx.width * 2);
                for line in wrap(
                    &[(tail, theme::muted().add_modifier(Modifier::ITALIC))],
                    cx.width,
                    Span::raw(inner.clone()),
                    Span::raw(inner.clone()),
                ) {
                    rows.push(Row { line, target: None });
                }
            }
            _ => {}
        }
    }
}

/// Diff lines: "  12 + text", the first `cut` lines when it has more than `cut + 2`.
pub fn diff_lines(d: &FileDiff, w: usize, cut: Option<usize>, indent: &str, target: &Target, rows: &mut Vec<Row>) {
    let total = d.line_count();
    let limit = match cut {
        Some(c) if total > c + 2 => c,
        _ => total,
    };
    let num_w = d.hunks.iter().flatten().filter_map(|l| l.new.or(l.old)).max().unwrap_or(1).to_string().len();
    let mut shown = 0;
    'outer: for (h, hunk) in d.hunks.iter().enumerate() {
        if h > 0 {
            rows.push(Row {
                line: Line::from(Span::styled(format!("{indent}{}⋯", " ".repeat(num_w)), theme::muted())),
                target: Some(target.clone()),
            });
        }
        for l in hunk {
            if shown == limit {
                break 'outer;
            }
            shown += 1;
            let (sign, style) = match l.kind {
                LineKind::Add => ("+", theme::ok()),
                LineKind::Del => ("-", theme::err()),
                LineKind::Ctx => (" ", Style::default()),
            };
            let no = l.new.or(l.old).map(|n| n.to_string()).unwrap_or_default();
            let head = format!("{indent}{no:>num_w$} {sign} ");
            let text = fit(&l.text.replace('\t', "    "), w.saturating_sub(width(&head)));
            rows.push(Row {
                line: Line::from(vec![Span::styled(head, theme::muted()), Span::styled(text, style)]),
                target: Some(target.clone()),
            });
        }
    }
    if limit < total {
        rows.push(Row {
            line: Line::from(Span::styled(format!("{indent}… {} more lines (Enter)", total - limit), theme::muted())),
            target: Some(target.clone()),
        });
    } else if d.hidden != (0, 0) {
        let n = d.hidden.0 + d.hidden.1;
        rows.push(Row {
            line: Line::from(Span::styled(
                format!("{indent}{n} more changed {} not shown", if n == 1 { "line" } else { "lines" }),
                theme::muted(),
            )),
            target: Some(target.clone()),
        });
    }
}

/// "─ 1 file +6 −2 · m · 18k tokens · 3 tools · 41s ─"
fn summary_line(m: &Message, cx: &Ctx) -> Row {
    let t = turn(m);
    let mut spans = vec![Span::styled("  ─ ", theme::muted())];
    if t.files > 0 {
        spans.push(Span::styled(format!("{} {} ", t.files, if t.files == 1 { "file" } else { "files" }), theme::muted()));
        spans.push(Span::styled(format!("+{}", t.added), theme::ok()));
        spans.push(Span::styled(format!(" −{}", t.removed), theme::err()));
        spans.push(Span::styled(" · ", theme::muted()));
    }
    if t.failed > 0 {
        spans.push(Span::styled(format!("{} failed · ", t.failed), theme::err()));
    }
    // a stored answer has no meta line: say what it did instead
    let meta = m.meta.clone().filter(|x| !x.is_empty()).unwrap_or_else(|| match t.tools {
        0 => "answer".to_string(),
        1 => "1 tool".to_string(),
        n => format!("{n} tools"),
    });
    let used: usize = spans.iter().map(|s| width(&s.content)).sum();
    spans.push(Span::styled(fit(&meta, cx.width.saturating_sub(used + 4)), theme::muted()));
    if m.trace_id.is_some() {
        spans.push(Span::styled(" ─ trace ↵", theme::muted()));
    }
    Row { line: Line::from(spans), target: Some(Target::Turn { msg: m.id }) }
}

/// "Added 3 lines, removed 1 line" for a step's diff.
pub fn change_label(s: &ToolStep) -> Option<String> {
    let d = step_diff(s)?;
    let stats = diff_stats(Some(&d));
    (stats != (0, 0)).then(|| describe_change(stats))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::reply::{Message, Role};
    use serde_json::json;

    fn conv(messages: Vec<Message>) -> Conv {
        let mut c = crate::state::Conv::for_tests("c");
        c.messages = messages;
        c
    }
    fn text(rows: &[Row]) -> Vec<String> {
        rows.iter().map(|r| r.line.spans.iter().map(|s| s.content.as_ref()).collect::<String>().trim_end().to_string()).collect()
    }

    fn answer(id: u64, edit: bool) -> Message {
        let mut m = Message::new(id, Role::Assistant, "");
        crate::core::reply::append_text(&mut m, "Looking.");
        crate::core::reply::tool_step(
            &mut m,
            &json!({"id":"r","name":"read_file","arguments":"{\"path\":\"a/b.rs\"}","status":"running"}),
            None,
        );
        crate::core::reply::tool_step(&mut m, &json!({"id":"r","name":"read_file","status":"ok","content":"abc"}), None);
        if edit {
            crate::core::reply::tool_step(
                &mut m,
                &json!({"id":"e","name":"edit_file","arguments":"{\"path\":\"b.rs\",\"old_str\":\"x\",\"new_str\":\"y\"}","status":"running"}),
                None,
            );
            crate::core::reply::tool_step(
                &mut m,
                &json!({"id":"e","name":"edit_file","arguments":"{\"path\":\"b.rs\",\"old_str\":\"x\",\"new_str\":\"y\"}","status":"ok","content":"edited (first at line 3)"}),
                None,
            );
        }
        crate::core::reply::append_text(&mut m, "Done.");
        m.meta = Some("m · 5 tokens · 2s".into());
        m
    }

    #[test]
    fn the_last_turn_is_open_and_older_ones_fold() {
        let c = conv(vec![Message::new(1, Role::User, "fix it"), answer(2, true), Message::new(3, Role::User, "again"), answer(4, true)]);
        let folds = Folds::default();
        let rows = build(&c, &Ctx { width: 60, now: 0, tick: 0, folds: &folds });
        let t = text(&rows);
        assert_eq!(t[0], "❯ fix it");
        assert_eq!(t[1], "");
        assert_eq!(t[2], "● Looking.");
        assert_eq!(t[3], "▸ 2 tool calls · +1 −1");
        assert_eq!(t[4], "  Done.");
        assert!(t[5].starts_with("  ─ 1 file +1 −1 · m · 5 tokens · 2s"), "{}", t[5]);
        // the last turn: one line per call and the diff of the edit
        let last: Vec<&String> = t.iter().skip(9).collect();
        assert!(last[1].starts_with("✓ read_file  b.rs") && last[1].ends_with("3 B"), "{}", last[1]);
        assert!(last[2].starts_with("✓ edit_file  b.rs") && last[2].ends_with("+1 −1"), "{}", last[2]);
        assert_eq!(last[3], "    3 - x");
        assert_eq!(last[4], "    3 + y");
    }

    #[test]
    fn folds_follow_the_user() {
        let c = conv(vec![answer(1, true), answer(2, false)]);
        let mut folds = Folds::default();
        folds.toggle("turn:1".into(), false);
        folds.toggle("tool:1:e".into(), true);
        let t = text(&build(&c, &Ctx { width: 60, now: 0, tick: 0, folds: &folds }));
        assert!(t.iter().any(|l| l.starts_with("✓ edit_file")));
        assert!(!t.iter().any(|l| l.ends_with("+ y")), "the diff was closed: {t:?}");
    }

    #[test]
    fn long_diffs_are_cut() {
        let d =
            crate::core::diff::parse_unified(&format!("@@ -0,0 +1,30 @@\n{}", (1..=30).map(|i| format!("+l{i}\n")).collect::<String>()));
        let mut rows = vec![];
        diff_lines(&d, 40, Some(DIFF_CUT), "", &Target::Turn { msg: 1 }, &mut rows);
        assert_eq!(rows.len(), 21);
        assert_eq!(text(&rows)[20], "… 10 more lines (Enter)");
        let mut rows = vec![];
        let d =
            crate::core::diff::parse_unified(&format!("@@ -0,0 +1,22 @@\n{}", (1..=22).map(|i| format!("+l{i}\n")).collect::<String>()));
        diff_lines(&d, 40, Some(DIFF_CUT), "", &Target::Turn { msg: 1 }, &mut rows);
        assert_eq!(rows.len(), 22, "22 lines are not cut");
    }
}
