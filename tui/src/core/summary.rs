//! The conversation at a glance, ported from ui-v2's `summary.ts`, plus the per-turn summary line the
//! terminal folds an old answer into.

use super::diff::line_delta;
use super::format::args;
use super::reply::{ASK_USER_TOOL, EXIT_PLAN_TOOL, Message, Part, Role, Status, ToolStep};
use serde_json::Value;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Effect {
    Read,
    Write,
    Command,
    Network,
    Ask,
    Agent,
    Other,
}

/// What a tool does to the world, by its name.
pub fn effect(name: &str) -> Effect {
    match name {
        "read_file" | "list_dir" | "memory_view" | "skill" | "todo_read" => Effect::Read,
        "write_file" | "edit_file" => Effect::Write,
        "exec" | "exec_output" | "exec_stop" | "check" => Effect::Command,
        n if n.starts_with("mcp__") => Effect::Network,
        n if n == EXIT_PLAN_TOOL || n == ASK_USER_TOOL => Effect::Ask,
        "agent" => Effect::Agent,
        _ => Effect::Other,
    }
}

/// One tool call, with the sub-agent that made it.
pub struct Flat<'a> {
    pub step: &'a ToolStep,
    pub agent: Option<String>,
    pub message_id: u64,
}

/// Every tool call of `parts`, a sub-agent's calls right after the call that runs it.
pub fn flatten<'a>(parts: &'a [Part], message_id: u64, agent: Option<&str>) -> Vec<Flat<'a>> {
    let mut out = vec![];
    for p in parts {
        if let Part::Tool(t) = p {
            out.push(Flat { step: t, agent: agent.map(Into::into), message_id });
            if let Some(a) = &t.agent {
                let name = if a.name.is_empty() { "sub-agent" } else { &a.name };
                out.extend(flatten(&a.parts, message_id, Some(name)));
            }
        }
    }
    out
}

pub fn all_steps(messages: &[Message]) -> Vec<Flat<'_>> {
    messages.iter().filter(|m| m.role == Role::Assistant).flat_map(|m| flatten(&m.parts, m.id, None)).collect()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TodoStatus {
    Pending,
    InProgress,
    Done,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TodoItem {
    pub text: String,
    pub status: TodoStatus,
}

/// The items of the last `todo_write` call; empty when there is none.
pub fn todos<'a>(steps: impl DoubleEndedIterator<Item = &'a ToolStep>) -> Vec<TodoItem> {
    for s in steps.rev() {
        if s.name != "todo_write" {
            continue;
        }
        let Some(Value::Array(items)) = args(&s.arguments).get("items").cloned() else { continue };
        return items
            .iter()
            .map(|it| TodoItem {
                text: super::format::text_of(it.get("text")),
                status: match it.get("status").and_then(Value::as_str) {
                    Some("done") => TodoStatus::Done,
                    Some("in_progress") => TodoStatus::InProgress,
                    _ => TodoStatus::Pending,
                },
            })
            .collect();
    }
    vec![]
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Summary {
    pub answers: usize,
    pub steps: usize,
    pub failed: usize,
    pub reads: usize,
    pub writes: usize,
    pub added: u64,
    pub removed: u64,
    pub commands: usize,
    pub network: usize,
    pub agents: usize,
    pub tokens: Option<u64>,
    pub elapsed: Option<i64>,
    /// Files touched, most recent first, without repeats; `true` when written.
    pub files: Vec<(String, bool)>,
    pub todo: Vec<TodoItem>,
}

fn add_steps(s: &mut Summary, flat: &[Flat], files: &mut Vec<(String, bool)>) {
    for f in flat {
        let step = f.step;
        if step.status == Status::Error {
            s.failed += 1;
        }
        if step.status == Status::Drafting {
            continue;
        }
        let e = effect(&step.name);
        match e {
            Effect::Read => s.reads += 1,
            Effect::Write => {
                s.writes += 1;
                let (a, r) = line_delta(step);
                s.added += a;
                s.removed += r;
            }
            Effect::Command => s.commands += 1,
            Effect::Network => s.network += 1,
            Effect::Agent => s.agents += 1,
            _ => {}
        }
        if matches!(e, Effect::Read | Effect::Write)
            && let Some(path) = args(&step.arguments).get("path").and_then(Value::as_str).filter(|p| !p.is_empty())
        {
            let wrote = files.iter().find(|(p, _)| p == path).is_some_and(|(_, w)| *w) || e == Effect::Write;
            files.retain(|(p, _)| p != path);
            files.push((path.to_string(), wrote));
        }
    }
}

pub fn summary(messages: &[Message], now: i64) -> Summary {
    let flat = all_steps(messages);
    let mut s = Summary {
        answers: messages.iter().filter(|m| m.role == Role::Assistant).count(),
        steps: flat.len(),
        todo: todos(flat.iter().map(|f| f.step)),
        ..Default::default()
    };
    let mut files = vec![];
    add_steps(&mut s, &flat, &mut files);
    files.reverse();
    s.files = files;
    for m in messages.iter().filter(|m| m.role == Role::Assistant) {
        if let Some(u) = m.usage {
            s.tokens = Some(s.tokens.unwrap_or(0) + u.prompt_tokens + u.completion_tokens);
        }
        let Some(start) = m.started_at else { continue };
        let end = m.ended_at.or(if m.streaming { Some(now) } else { None });
        if let Some(end) = end {
            s.elapsed = Some(s.elapsed.unwrap_or(0) + (end - start).max(0));
        }
    }
    s
}

/// What one answer did, for the line an answer folds into: "2 files +6 −2 · 3 tools · 1 failed".
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Turn {
    pub files: usize,
    pub added: u64,
    pub removed: u64,
    pub tools: usize,
    pub failed: usize,
}

pub fn turn(m: &Message) -> Turn {
    let flat = flatten(&m.parts, m.id, None);
    let mut s = Summary::default();
    let mut files = vec![];
    add_steps(&mut s, &flat, &mut files);
    Turn { files: files.iter().filter(|(_, w)| *w).count(), added: s.added, removed: s.removed, tools: flat.len(), failed: s.failed }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::reply::{SubAgent, Usage};

    fn step(name: &str, a: serde_json::Value, status: Status) -> ToolStep {
        ToolStep::new(name, name, &a.to_string(), status)
    }
    fn answer(id: u64, parts: Vec<Part>) -> Message {
        let mut m = Message::new(id, Role::Assistant, "");
        m.parts = parts;
        m
    }

    #[test]
    fn classifies() {
        assert_eq!(effect("read_file"), Effect::Read);
        assert_eq!(effect("edit_file"), Effect::Write);
        assert_eq!(effect("exec"), Effect::Command);
        assert_eq!(effect("mcp__github__search"), Effect::Network);
        assert_eq!(effect("ask_user"), Effect::Ask);
        assert_eq!(effect("agent"), Effect::Agent);
        assert_eq!(effect("whatever"), Effect::Other);
    }

    #[test]
    fn todos_of_the_last_write() {
        let items = |t: &str| serde_json::json!({"items":[{"text":t,"status":"in_progress"},{"text":"b","status":"odd"}]});
        let steps = [
            step("todo_write", items("a"), Status::Ok),
            step("todo_write", items("c"), Status::Ok),
            step("exec", serde_json::json!({}), Status::Ok),
        ];
        assert_eq!(
            todos(steps.iter()),
            vec![TodoItem { text: "c".into(), status: TodoStatus::InProgress }, TodoItem { text: "b".into(), status: TodoStatus::Pending }]
        );
    }

    #[test]
    fn adds_up_the_conversation() {
        let mut sub = step("agent", serde_json::json!({"agent":"reviewer"}), Status::Ok);
        sub.agent = Some(SubAgent {
            name: "reviewer".into(),
            text: String::new(),
            parts: vec![Part::Tool(step("read_file", serde_json::json!({"path":"b.ts"}), Status::Ok))],
        });
        let mut a2 = answer(
            2,
            vec![
                Part::Tool(step("read_file", serde_json::json!({"path":"a.ts"}), Status::Ok)),
                Part::Tool(step("edit_file", serde_json::json!({"path":"a.ts","old_str":"x","new_str":"y\nz"}), Status::Ok)),
            ],
        );
        a2.usage = Some(Usage { prompt_tokens: 100, completion_tokens: 20, reasoning_tokens: 0 });
        a2.started_at = Some(0);
        a2.ended_at = Some(4000);
        let mut a3 = answer(
            3,
            vec![
                Part::Tool(step("exec", serde_json::json!({"command":"npm test"}), Status::Error)),
                Part::Tool(sub),
                Part::Tool(step("read_file", serde_json::json!({}), Status::Drafting)),
            ],
        );
        a3.started_at = Some(10_000);
        a3.streaming = true;
        let messages = vec![Message::new(1, Role::User, "hi"), a2.clone(), a3];
        let s = summary(&messages, 12_000);
        assert_eq!((s.answers, s.steps, s.failed, s.reads, s.writes, s.added, s.removed), (2, 6, 1, 2, 1, 2, 1));
        assert_eq!((s.commands, s.agents, s.tokens, s.elapsed), (1, 1, Some(120), Some(6000)));
        assert_eq!(s.files, vec![("b.ts".to_string(), false), ("a.ts".to_string(), true)]);
        assert_eq!(turn(&a2), Turn { files: 1, added: 2, removed: 1, tools: 2, failed: 0 });
        let empty = summary(&[], 0);
        assert_eq!((empty.steps, empty.tokens, empty.elapsed, empty.files.len(), empty.todo.len()), (0, None, None, 0, 0));
    }
}
