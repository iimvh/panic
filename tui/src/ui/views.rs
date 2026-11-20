//! Full-screen views opened from the thread: a step's details, an answer's trace, the run overview, help.

use super::cards::pretty;
use super::text::{fit, width, wrap};
use super::theme;
use super::thread::{Target, diff_lines};
use crate::core::diff::{diff_stats, step_diff};
use crate::core::format::{compact, duration, failure, ms, shell_command, size};
use crate::core::reply::Status;
use crate::core::summary::{Flat, Summary, TodoStatus};
use crate::core::trace::Span as TraceSpan;
use crate::core::trace::{Row as TraceRow, layout};
use crate::state::{ContextUse, Mode};
use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};

fn heading(text: &str) -> Line<'static> {
    Line::from(Span::styled(text.to_string(), theme::bold()))
}

fn block(text: &str, w: usize, style: Style) -> Vec<Line<'static>> {
    wrap(&[(text.to_string(), style)], w, Span::raw("  "), Span::raw("  "))
}

/// Everything about one call: what it changed, its arguments, its result, what its sub-agent wrote.
pub fn step(f: &Flat, index: usize, total: usize, w: usize) -> Vec<Line<'static>> {
    let s = f.step;
    let mut title = vec![Span::styled(if s.name.is_empty() { "tool".to_string() } else { s.name.clone() }, theme::bold())];
    if let Some(a) = &f.agent {
        title.push(Span::styled(format!(" · {a}"), theme::muted()));
    }
    title.push(Span::styled(format!("   Step {} of {total}", index + 1), theme::muted()));
    let mut out = vec![Line::from(title)];
    let status = match s.status {
        Status::Error => Span::styled(failure(s.content.as_deref()), theme::err()),
        Status::Ok => Span::styled("ok", theme::ok()),
        Status::Running => Span::styled("running", theme::accent()),
        Status::Drafting => Span::styled("drafting", theme::accent()),
    };
    let mut st = vec![status];
    if let (Some(a), Some(b)) = (s.started_at, s.ended_at) {
        st.push(Span::styled(format!(" · {}", duration(b - a)), theme::muted()));
    }
    out.push(Line::from(st));
    out.push(Line::default());
    let target = Target::Tool { msg: f.message_id, step: s.id.clone() };
    if let Some(d) = step_diff(s).filter(|d| d.line_count() > 0) {
        let (a, r) = diff_stats(Some(&d));
        out.push(Line::from(vec![
            Span::styled("Changes ", theme::bold()),
            Span::styled(format!("+{a}"), theme::ok()),
            Span::styled(format!(" −{r}"), theme::err()),
        ]));
        let mut rows = vec![];
        diff_lines(&d, w, None, "  ", &target, &mut rows);
        out.extend(rows.into_iter().map(|r| r.line));
        out.push(Line::default());
    }
    match shell_command(&s.arguments) {
        Some(cmd) => {
            out.push(heading("Command"));
            out.extend(block(&cmd, w, Style::default()));
        }
        None => {
            out.push(Line::from(vec![Span::styled("Arguments ", theme::bold()), Span::styled(size(&s.arguments), theme::muted())]));
            out.extend(block(&pretty(&s.arguments), w, Style::default()));
        }
    }
    if let Some(c) = &s.content {
        out.push(Line::default());
        out.push(Line::from(vec![Span::styled("Result ", theme::bold()), Span::styled(size(c), theme::muted())]));
        out.extend(block(c, w, if s.status == Status::Error { theme::err() } else { Style::default() }));
    }
    if let Some(a) = s.agent.as_ref().filter(|a| !a.text.is_empty()) {
        out.push(Line::default());
        out.push(heading(&format!("{} wrote", if a.name.is_empty() { "sub-agent" } else { &a.name })));
        out.extend(block(&a.text, w, Style::default()));
    }
    out
}

/// An answer's trace as a waterfall; the selected row shows its details.
pub fn trace(spans: &[TraceSpan], selected: usize, w: usize) -> (Vec<Line<'static>>, usize) {
    let rows = layout(spans);
    let label_w = rows.iter().map(|r| r.depth * 2 + width(&r.label)).max().unwrap_or(10).min(w / 3).max(10);
    let dur_w = 9;
    let bar_w = w.saturating_sub(label_w + dur_w + 4).max(10);
    let mut out = vec![];
    let mut selected_line = 0;
    for (i, r) in rows.iter().enumerate() {
        let here = i == selected;
        if here {
            selected_line = out.len();
        }
        let label = fit(&format!("{}{}", "  ".repeat(r.depth), r.label), label_w);
        let pad = label_w.saturating_sub(width(&label));
        let start = ((r.offset / 100.0) * bar_w as f64).round() as usize;
        let len = (((r.width / 100.0) * bar_w as f64).round() as usize).max(1).min(bar_w.saturating_sub(start).max(1));
        let colour = match r.status.as_str() {
            "error" => theme::err(),
            "cancelled" => theme::muted(),
            _ => theme::accent(),
        };
        let mut line = Line::from(vec![
            Span::styled(if here { "› " } else { "  " }, theme::accent()),
            Span::raw(label),
            Span::raw(" ".repeat(pad + 1)),
            Span::styled("·".repeat(start.min(bar_w)), theme::muted()),
            Span::styled("█".repeat(len), colour),
            Span::raw(" ".repeat(bar_w.saturating_sub(start + len))),
            Span::styled(format!(" {:>8}", ms(r.duration_ms)), theme::muted()),
        ]);
        if here {
            line = line.patch_style(Style::default().add_modifier(Modifier::BOLD));
        }
        out.push(line);
        if here {
            out.extend(details(r, w));
        }
    }
    (out, selected_line)
}

fn details(r: &TraceRow, w: usize) -> Vec<Line<'static>> {
    let mut out = vec![];
    let mut head = format!("starts +{}", ms(r.start_ms));
    if !r.detail.is_empty() {
        head.push_str(&format!(" · {}", r.detail));
    }
    out.extend(wrap(&[(head, theme::muted())], w, Span::raw("    "), Span::raw("    ")));
    if let Some(e) = &r.error {
        out.extend(wrap(&[(format!("{}: {e}", r.status), theme::err())], w, Span::raw("    "), Span::raw("    ")));
    }
    for (k, v) in &r.attrs {
        if k == "questionHash" {
            continue;
        }
        let value = match v {
            serde_json::Value::Number(n) if k.ends_with("Micros") => ms(n.as_f64().unwrap_or(0.0) / 1000.0),
            serde_json::Value::String(s) => s.clone(),
            other => other.to_string(),
        };
        out.extend(wrap(&[(format!("{k} "), theme::muted()), (value, Style::default())], w, Span::raw("    "), Span::raw("      ")));
    }
    out
}

pub fn overview(s: &Summary, mode: Option<Mode>, model: Option<&str>, ctx: Option<ContextUse>, w: usize) -> Vec<Line<'static>> {
    let mut out = vec![];
    let mut fact = |k: &str, v: Vec<Span<'static>>| {
        let mut spans = vec![Span::styled(format!("  {k:<12}"), theme::muted())];
        spans.extend(v);
        out.push(Line::from(spans));
    };
    if let Some(m) = mode {
        let style = if m == Mode::Auto { theme::err() } else { Style::default() };
        fact("Mode", vec![Span::styled(m.label(), style)]);
    }
    if let Some(m) = model {
        fact("Model", vec![Span::raw(m.to_string())]);
    }
    if let Some(c) = ctx {
        let pct = c.used * 100 / c.window.max(1);
        let style = if pct >= 80 { theme::warn() } else { Style::default() };
        fact(
            "Context",
            vec![
                Span::styled(format!("{pct}%"), style),
                Span::styled(format!("  {} of {}", compact(c.used), compact(c.window)), theme::muted()),
            ],
        );
    }
    if let Some(e) = s.elapsed {
        fact("Time", vec![Span::raw(duration(e))]);
    }
    if let Some(t) = s.tokens {
        fact("Tokens", vec![Span::raw(compact(t))]);
    }
    if s.steps > 0 {
        let mut v = vec![Span::raw(s.steps.to_string())];
        if s.failed > 0 {
            v.push(Span::styled(format!(" · {} failed", s.failed), theme::err()));
        }
        fact("Tool calls", v);
    }
    if s.reads > 0 {
        fact("Files read", vec![Span::raw(s.reads.to_string())]);
    }
    if s.writes > 0 {
        fact(
            "Edits",
            vec![
                Span::raw(format!("{} · ", s.writes)),
                Span::styled(format!("+{}", s.added), theme::ok()),
                Span::styled(format!(" −{}", s.removed), theme::err()),
            ],
        );
    }
    if s.commands > 0 {
        fact("Commands", vec![Span::raw(s.commands.to_string())]);
    }
    if s.network > 0 {
        fact("MCP calls", vec![Span::raw(s.network.to_string())]);
    }
    if s.agents > 0 {
        fact("Sub-agents", vec![Span::raw(s.agents.to_string())]);
    }
    if !s.todo.is_empty() {
        let done = s.todo.iter().filter(|t| t.status == TodoStatus::Done).count();
        out.push(Line::default());
        out.push(Line::from(vec![
            Span::styled("Checklist ", theme::bold()),
            Span::styled(format!("{done} of {}", s.todo.len()), theme::muted()),
        ]));
        for t in &s.todo {
            let (mark, style) = match t.status {
                TodoStatus::Done => ("✓", theme::muted().add_modifier(Modifier::CROSSED_OUT)),
                TodoStatus::InProgress => ("◐", theme::accent()),
                TodoStatus::Pending => ("○", Style::default()),
            };
            out.extend(wrap(&[(t.text.clone(), style)], w, Span::styled(format!("  {mark} "), style), Span::raw("    ")));
        }
    }
    if !s.files.is_empty() {
        out.push(Line::default());
        out.push(Line::from(vec![Span::styled("Files ", theme::bold()), Span::styled(s.files.len().to_string(), theme::muted())]));
        for (path, wrote) in s.files.iter().take(50) {
            out.push(Line::from(vec![
                Span::styled(if *wrote { "  M " } else { "    " }, theme::warn()),
                Span::raw(fit(path, w.saturating_sub(4))),
            ]));
        }
    }
    if out.is_empty() {
        out.push(Line::from(Span::styled("  Nothing has happened in this chat yet.", theme::muted())));
    }
    out
}

pub const KEYS: &[(&str, &str)] = &[
    ("Enter", "send (while an answer runs: queue it)"),
    ("Shift+Enter, Alt+Enter", "new line"),
    ("Esc", "close a view, else stop the answer"),
    ("Ctrl+C twice", "quit (once: clear the text, or stop)"),
    ("Shift+Tab", "next mode: Ask first, Plan, Auto-run"),
    ("Ctrl+K", "palette: chats, commands, models, effort, mode, actions"),
    ("Ctrl+J", "go to the chat that needs you"),
    ("Ctrl+N", "new chat"),
    ("Ctrl+O", "run overview"),
    ("Ctrl+G", "write the prompt in $EDITOR"),
    ("Ctrl+R", "search the prompt history"),
    ("↑ / ↓", "in an empty prompt: queued message, then history"),
    ("PgUp / PgDn, wheel", "scroll"),
    ("Tab", "select in the thread (j k ↑ ↓ g G move, Tab or Esc back)"),
    ("Enter on a line", "open: a step, the trace of a summary"),
    ("Space on a line", "fold or unfold it"),
    ("c / C on a line", "copy it / its last code block"),
    ("/continue", "go on after an answer stopped at a limit"),
    ("r", "retry when the server was unreachable (empty prompt)"),
    ("?", "this help (empty prompt)"),
];

pub fn help(w: usize) -> Vec<Line<'static>> {
    KEYS.iter()
        .map(|(k, v)| Line::from(vec![Span::styled(format!("  {:<24}", k), theme::accent()), Span::raw(fit(v, w.saturating_sub(26)))]))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::reply::ToolStep;
    use serde_json::json;

    fn text(lines: &[Line]) -> Vec<String> {
        lines.iter().map(|l| l.spans.iter().map(|s| s.content.as_ref()).collect::<String>().trim_end().to_string()).collect()
    }

    #[test]
    fn a_step_shows_its_change_arguments_and_result() {
        let mut s = ToolStep::new("e", "edit_file", r#"{"path":"a.rs","old_str":"x","new_str":"y"}"#, Status::Ok);
        s.content = Some("edited a.rs (first at line 2)".into());
        let f = Flat { step: &s, agent: Some("explore".into()), message_id: 1 };
        let t = text(&step(&f, 0, 3, 60));
        assert_eq!(t[0], "edit_file · explore   Step 1 of 3");
        assert_eq!(t[1], "ok");
        assert_eq!(t[3], "Changes +1 −1");
        assert_eq!(t[4], "  2 - x");
        assert!(t.iter().any(|l| l.starts_with("Arguments")));
        assert!(t.iter().any(|l| l == "  edited a.rs (first at line 2)"));
    }

    #[test]
    fn a_trace_is_a_waterfall_with_details() {
        let span = |v: serde_json::Value| TraceSpan::from_json(&v).unwrap();
        let spans = vec![
            span(
                json!({"id":"r","parentId":null,"name":"turn","startMicros":0,"durationMicros":1000000,"status":"ok","attrs":{"steps":1}}),
            ),
            span(
                json!({"id":"l","parentId":"r","name":"llm","startMicros":0,"durationMicros":500000,"status":"ok","attrs":{"step":1,"promptTokens":20,"completionTokens":5}}),
            ),
        ];
        let (lines, at) = trace(&spans, 1, 60);
        let t = text(&lines);
        assert!(t[0].starts_with("  turn"), "{}", t[0]);
        assert!(t[0].ends_with("1.00s"));
        assert!(t[1].starts_with("›   llm · step 1"), "{}", t[1]);
        assert_eq!(at, 1);
        assert!(t[2].contains("starts +0µs · 20+5 tok"), "{}", t[2]);
    }
}
