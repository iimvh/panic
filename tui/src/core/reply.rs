//! The answer timeline, ported from ui-v2's `reply.ts`: the server's events folded into what the user reads,
//! in the order they happened. The terminal applies every event as it comes and draws once per frame, so the
//! web UI's text batcher is not needed: the order of text and tool calls is kept by construction.

use super::format::{compact, duration, num_of, text_of};
use super::sse::SseEvent;
use serde_json::Value;

pub const EXIT_PLAN_TOOL: &str = "exit_plan_mode";
pub const ASK_USER_TOOL: &str = "ask_user";
/// The message the Continue action sends: the history holds the work, so a plain ask picks it up.
pub const CONTINUE_TEXT: &str = "Continue where you stopped.";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    /// The model is still writing the call (`tool_delta`); it has not run yet.
    Drafting,
    Running,
    Ok,
    Error,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ToolStep {
    pub id: String,
    pub name: String,
    pub arguments: String,
    pub status: Status,
    pub content: Option<String>,
    /// What the call changed in a file, as unified-diff hunks.
    pub diff: Option<String>,
    /// While drafting: the call's position in the model's reply, which pieces are matched by.
    pub draft: Option<i64>,
    /// When this call runs a sub-agent: what it writes and the tools it calls.
    pub agent: Option<SubAgent>,
    pub started_at: Option<i64>,
    pub ended_at: Option<i64>,
}

impl ToolStep {
    pub fn new(id: &str, name: &str, arguments: &str, status: Status) -> Self {
        ToolStep {
            id: id.into(),
            name: name.into(),
            arguments: arguments.into(),
            status,
            content: None,
            diff: None,
            draft: None,
            agent: None,
            started_at: None,
            ended_at: None,
        }
    }

    pub fn done(&self) -> bool {
        matches!(self.status, Status::Ok | Status::Error)
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct SubAgent {
    pub name: String,
    pub text: String,
    pub parts: Vec<Part>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Part {
    /// What the model thought; `ended_at` is None while it still thinks.
    Reasoning {
        text: String,
        started_at: i64,
        ended_at: Option<i64>,
    },
    Text(String),
    Tool(ToolStep),
    Review {
        attempt: i64,
        pass: bool,
        reason: String,
        error: Option<String>,
    },
    Error(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Usage {
    pub prompt_tokens: u64,
    pub completion_tokens: u64,
    pub reasoning_tokens: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Approval {
    pub id: String,
    /// The tool call it is about.
    pub call_id: String,
    pub name: String,
    pub arguments: String,
    /// read, write, exec or network.
    pub risk: String,
    pub agent: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PlanProposal {
    pub id: String,
    pub plan: String,
    pub items: Vec<String>,
    pub criteria: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct UserQuestion {
    pub question: String,
    pub header: String,
    pub multi_select: bool,
    pub options: Vec<(String, String)>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Questions {
    pub id: String,
    pub questions: Vec<UserQuestion>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    User,
    Assistant,
    Command,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Message {
    pub id: u64,
    pub role: Role,
    pub text: String,
    /// model · tokens, "stopped", ...; for a command: the typed line.
    pub meta: Option<String>,
    pub parts: Vec<Part>,
    pub started_at: Option<i64>,
    pub ended_at: Option<i64>,
    pub usage: Option<Usage>,
    pub approval: Option<Approval>,
    /// More approvals asked while `approval` is shown, in arrival order.
    pub waiting: Vec<Approval>,
    pub plan: Option<PlanProposal>,
    pub question: Option<Questions>,
    /// Why the answer ended (`done`, `max_steps`, ...).
    pub stop: Option<String>,
    pub error: bool,
    pub streaming: bool,
    pub trace_id: Option<String>,
    /// The model of the answer, from `done`.
    pub model: Option<String>,
}

impl Message {
    pub fn new(id: u64, role: Role, text: &str) -> Self {
        Message {
            id,
            role,
            text: text.into(),
            meta: None,
            parts: vec![],
            started_at: None,
            ended_at: None,
            usage: None,
            approval: None,
            waiting: vec![],
            plan: None,
            question: None,
            stop: None,
            error: false,
            streaming: false,
            trace_id: None,
            model: None,
        }
    }

    /// Whether something waits for the user (an approval, a plan, questions).
    pub fn needs_user(&self) -> bool {
        self.approval.is_some() || self.plan.is_some() || self.question.is_some()
    }

    /// Clears the cards: at the end of a request nothing can be answered any more.
    pub fn clear_cards(&mut self) {
        self.approval = None;
        self.waiting.clear();
        self.plan = None;
        self.question = None;
    }
}

/// Something that holds a timeline: an answer, or a sub-agent inside it.
pub trait Timeline {
    fn text_mut(&mut self) -> &mut String;
    fn parts_mut(&mut self) -> &mut Vec<Part>;
}

impl Timeline for Message {
    fn text_mut(&mut self) -> &mut String {
        &mut self.text
    }
    fn parts_mut(&mut self) -> &mut Vec<Part> {
        &mut self.parts
    }
}

impl Timeline for SubAgent {
    fn text_mut(&mut self) -> &mut String {
        &mut self.text
    }
    fn parts_mut(&mut self) -> &mut Vec<Part> {
        &mut self.parts
    }
}

/// Appends streamed text: to the last part when it is text, else as a new text part.
pub fn append_text(t: &mut impl Timeline, delta: &str) {
    if delta.is_empty() {
        return;
    }
    t.text_mut().push_str(delta);
    let parts = t.parts_mut();
    if let Some(Part::Text(last)) = parts.last_mut() {
        last.push_str(delta);
    } else {
        parts.push(Part::Text(delta.into()));
    }
}

/// Appends streamed reasoning: to the last part while it is unfinished reasoning, else as a new part.
pub fn append_reasoning(t: &mut impl Timeline, delta: &str, now: i64) {
    if delta.is_empty() {
        return;
    }
    let parts = t.parts_mut();
    if let Some(Part::Reasoning { text, ended_at: None, .. }) = parts.last_mut() {
        text.push_str(delta);
    } else {
        parts.push(Part::Reasoning { text: delta.into(), started_at: now, ended_at: None });
    }
}

/// The model stopped thinking: it writes, calls a tool, or the stream ended.
pub fn end_reasoning(t: &mut impl Timeline, now: i64) {
    for p in t.parts_mut() {
        if let Part::Reasoning { ended_at: e @ None, .. } = p {
            *e = Some(now);
        }
    }
}

/// "Thinking · 4s" while it lasts, "Thought for 4s" after; no time under a second (or when replayed).
pub fn reasoning_label(started_at: i64, ended_at: Option<i64>, now: i64) -> String {
    let ms = ended_at.unwrap_or(now) - started_at;
    match ended_at {
        None if ms >= 1000 => format!("Thinking · {}", duration(ms)),
        None => "Thinking".into(),
        Some(_) if ms >= 1000 => format!("Thought for {}", duration(ms)),
        Some(_) => "Thought".into(),
    }
}

/// The end of a thought as shown while it streams: its last `lines` non-empty lines, at most `max_chars`.
pub fn thought_tail(text: &str, lines: usize, max_chars: usize) -> String {
    let kept: Vec<&str> = text.split('\n').map(str::trim_end).filter(|l| !l.trim().is_empty()).collect();
    let kept = kept[kept.len().saturating_sub(lines)..].join("\n");
    let n = kept.chars().count();
    if n > max_chars {
        let tail: String = kept.chars().skip(n - max_chars + 1).collect();
        format!("…{}", tail.trim_start())
    } else {
        kept
    }
}

fn get<'a>(d: &'a Value, k: &str) -> Option<&'a Value> {
    d.get(k)
}

fn s(d: &Value, k: &str) -> String {
    text_of(get(d, k))
}

fn truthy(d: &Value, k: &str) -> bool {
    match get(d, k) {
        None | Some(Value::Null) => false,
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) => !s.is_empty(),
        Some(Value::Number(n)) => n.as_f64() != Some(0.0),
        Some(_) => true,
    }
}

/// Shows an approval; one already shown makes it wait, in arrival order.
pub fn ask_approval(m: &mut Message, d: &Value) {
    let a = Approval {
        id: s(d, "id"),
        call_id: s(d, "callId"),
        name: s(d, "name"),
        arguments: s(d, "arguments"),
        risk: s(d, "risk"),
        agent: truthy(d, "agent").then(|| s(d, "agent")),
    };
    match &m.approval {
        None => m.approval = Some(a),
        Some(cur) if cur.id == a.id => m.approval = Some(a),
        Some(_) => {
            if !m.waiting.iter().any(|w| w.id == a.id) {
                m.waiting.push(a);
            }
        }
    }
}

/// Removes the approvals `gone` matches; when the one shown goes, the next waiting one is shown.
pub fn clear_approval(m: &mut Message, gone: impl Fn(&Approval) -> bool) {
    m.waiting.retain(|a| !gone(a));
    if m.approval.as_ref().is_some_and(&gone) {
        m.approval = if m.waiting.is_empty() { None } else { Some(m.waiting.remove(0)) };
    }
}

fn strings(v: Option<&Value>) -> Vec<String> {
    match v {
        Some(Value::Array(a)) => a.iter().map(|x| text_of(Some(x))).collect(),
        _ => vec![],
    }
}

pub fn ask_plan(m: &mut Message, d: &Value) {
    m.plan =
        Some(PlanProposal { id: s(d, "id"), plan: s(d, "plan"), items: strings(get(d, "items")), criteria: strings(get(d, "criteria")) });
}

pub fn ask_user(m: &mut Message, d: &Value) {
    let list = match get(d, "questions") {
        Some(Value::Array(a)) => a.clone(),
        _ => vec![],
    };
    m.question = Some(Questions {
        id: s(d, "id"),
        questions: list
            .iter()
            .map(|q| UserQuestion {
                question: s(q, "question"),
                header: s(q, "header"),
                multi_select: q.get("multiSelect") == Some(&Value::Bool(true)),
                options: match q.get("options") {
                    Some(Value::Array(o)) => o.iter().map(|o| (s(o, "label"), s(o, "description"))).collect(),
                    _ => vec![],
                },
            })
            .collect(),
    });
}

/// Adds a piece of a call the model is still writing: a new drafting row, or more of its arguments.
pub fn tool_delta(m: &mut Message, d: &Value) {
    let index = num_of(get(d, "index")) as i64;
    let piece = s(d, "arguments");
    let id = truthy(d, "id").then(|| s(d, "id"));
    let found = m.parts.iter().position(|p| matches!(p, Part::Tool(t) if t.status == Status::Drafting && t.draft == Some(index)));
    let mut found = found;
    if let (Some(i), Some(id)) = (found, &id)
        && let Part::Tool(t) = &mut m.parts[i]
        && !t.id.is_empty()
        && &t.id != id
    {
        // a new call at the index of one that never ran (the model was cut off mid-call)
        t.status = Status::Error;
        t.content = Some("not run".into());
        t.draft = None;
        found = None;
    }
    if let Some(i) = found {
        if let Part::Tool(t) = &mut m.parts[i] {
            if let Some(id) = id {
                t.id = id;
            }
            if truthy(d, "name") {
                t.name = s(d, "name");
            }
            t.arguments.push_str(&piece);
        }
    } else {
        let mut step = ToolStep::new(&id.unwrap_or_default(), &s(d, "name"), &piece, Status::Drafting);
        step.draft = Some(index);
        m.parts.push(Part::Tool(step));
    }
}

/// Adds a call when it starts and updates it in place when it ends. A call that starts takes the place of
/// its draft (same id, else the first draft left). A sub-agent's call (`parent`) goes into the timeline of
/// the call that runs that sub-agent.
pub fn tool_step(m: &mut Message, d: &Value, now: Option<i64>) {
    let status = match get(d, "status").and_then(Value::as_str) {
        Some("ok") => Status::Ok,
        Some("error") => Status::Error,
        _ => Status::Running,
    };
    let mut step = ToolStep::new(&s(d, "id"), &s(d, "name"), &s(d, "arguments"), status);
    step.content = get(d, "content").filter(|v| !v.is_null()).map(|v| text_of(Some(v)));
    if let Some(Value::String(diff)) = get(d, "diff")
        && !diff.is_empty()
    {
        step.diff = Some(diff.clone());
    }
    let parent = truthy(d, "parent").then(|| s(d, "parent"));
    let (id, name) = (step.id.clone(), step.name.clone());
    match &parent {
        Some(p) => {
            if let Some(agent) = sub_agent(m, p, get(d, "agent")) {
                upsert_step(agent, step, now);
            }
        }
        None => upsert_step(m, step, now),
    }
    if status != Status::Running {
        clear_approval(m, |a| a.call_id == id);
        if parent.is_none() && name == EXIT_PLAN_TOOL {
            m.plan = None;
        }
        if parent.is_none() && name == ASK_USER_TOOL {
            m.question = None;
        }
    }
}

fn upsert_step(t: &mut impl Timeline, mut step: ToolStep, now: Option<i64>) {
    let parts = t.parts_mut();
    let find = |parts: &Vec<Part>, f: &dyn Fn(&ToolStep) -> bool| parts.iter().position(|p| matches!(p, Part::Tool(s) if f(s)));
    let i = if step.status == Status::Running {
        find(parts, &|s| s.status == Status::Drafting && s.id == step.id).or_else(|| find(parts, &|s| s.status == Status::Drafting))
    } else {
        find(parts, &|s| s.id == step.id && s.status == Status::Running)
    };
    let old = i.and_then(|i| match &parts[i] {
        Part::Tool(s) => Some(s.clone()),
        _ => None,
    });
    if let Some(old) = &old
        && old.agent.is_some()
    {
        step.agent = old.agent.clone(); // the sub-agent's work stays
    }
    if let Some(now) = now {
        step.started_at = if step.status == Status::Running { Some(now) } else { old.as_ref().and_then(|o| o.started_at) };
        if step.status != Status::Running {
            step.ended_at = Some(now);
        }
    }
    match i {
        Some(i) => parts[i] = Part::Tool(step),
        None => parts.push(Part::Tool(step)),
    }
}

/// The sub-agent run by call `parent` (created on its first event); None when there is no such call.
fn sub_agent<'a>(m: &'a mut Message, parent: &str, name: Option<&Value>) -> Option<&'a mut SubAgent> {
    let i = m.parts.iter().rposition(|p| matches!(p, Part::Tool(t) if t.id == parent))?;
    match &mut m.parts[i] {
        Part::Tool(t) => Some(t.agent.get_or_insert_with(|| SubAgent { name: text_of(name), ..Default::default() })),
        _ => None,
    }
}

/// Text a sub-agent wrote (`agent_delta`): what it thinks when `reasoning`, else its text.
pub fn agent_text(m: &mut Message, d: &Value, now: i64) {
    let reasoning = d.get("reasoning") == Some(&Value::Bool(true));
    let text = s(d, "text");
    let Some(agent) = sub_agent(m, &s(d, "parent"), get(d, "agent")) else {
        return;
    };
    if reasoning {
        append_reasoning(agent, &text, now);
    } else {
        end_reasoning(agent, now);
        append_text(agent, &text);
    }
}

/// At the end of a stream: calls still drafting never ran.
pub fn end_drafts(m: &mut Message) {
    for p in &mut m.parts {
        if let Part::Tool(t) = p
            && t.status == Status::Drafting
        {
            t.status = Status::Error;
            t.content = Some("not run".into());
            t.draft = None;
        }
    }
}

/// A reviewer's verdict; a reviewer that failed sends `error`: the answer stands, unchecked.
pub fn review(m: &mut Message, d: &Value) {
    let attempt = num_of(get(d, "attempt")) as i64;
    if let Some(Value::String(e)) = get(d, "error") {
        m.parts.push(Part::Review { attempt, pass: true, reason: String::new(), error: Some(e.clone()) });
        return;
    }
    m.parts.push(Part::Review { attempt, pass: d.get("pass") == Some(&Value::Bool(true)), reason: s(d, "reason"), error: None });
}

/// The error where it happened, after what was streamed.
pub fn fail_answer(m: &mut Message, text: &str) {
    m.error = true;
    m.parts.push(Part::Error(text.into()));
}

/// What a streaming answer is doing right now, for the status line.
pub fn activity(m: &Message) -> String {
    if m.approval.is_some() {
        return "Waiting for approval".into();
    }
    if m.plan.is_some() {
        return "Waiting for plan approval".into();
    }
    if m.question.is_some() {
        return "Waiting for your answer".into();
    }
    let running = m.parts.iter().filter(|p| matches!(p, Part::Tool(t) if t.status == Status::Running)).count();
    if running > 1 {
        return format!("Running {running} tools…");
    }
    match m.parts.last() {
        Some(Part::Tool(t)) if t.status == Status::Running => {
            if let Some(agent) = &t.agent {
                let what = match agent.parts.last() {
                    Some(Part::Tool(i)) if i.status == Status::Running => format!("running {}", i.name),
                    _ => "working".into(),
                };
                return format!("{}: {what}…", agent.name);
            }
            format!("Running {}…", t.name)
        }
        Some(Part::Tool(t)) if t.status == Status::Drafting => {
            format!("Drafting {} call…", if t.name.is_empty() { "a tool" } else { &t.name })
        }
        Some(Part::Text(_)) => "Writing…".into(),
        Some(Part::Reasoning { ended_at: None, .. }) => "Thinking…".into(),
        Some(Part::Review { pass: false, .. }) => "Trying again…".into(),
        _ => "Waiting for the model…".into(),
    }
}

/// Whether an answer ended on a hard limit and can be continued.
pub fn can_continue(m: &Message) -> bool {
    m.role == Role::Assistant && !m.streaming && matches!(m.stop.as_deref(), Some("max_steps" | "budget" | "truncated" | "stalled"))
}

pub fn usage(d: &Value) -> Option<Usage> {
    let u = d.get("usage")?.as_object()?;
    let n = |k: &str| u.get(k).and_then(Value::as_u64).unwrap_or(0);
    Some(Usage { prompt_tokens: n("promptTokens"), completion_tokens: n("completionTokens"), reasoning_tokens: n("reasoningTokens") })
}

/// The muted line under a finished answer: "model · 5 tokens · 2 tools · stopped: max steps".
pub fn meta(d: &Value) -> String {
    let mut parts = vec![s(d, "model")];
    let u = usage(d).unwrap_or_default();
    let tokens = u.prompt_tokens + u.completion_tokens;
    if tokens > 0 {
        parts.push(if u.reasoning_tokens > 0 {
            format!("{} tokens ({} thinking)", compact(tokens), compact(u.reasoning_tokens))
        } else {
            format!("{} tokens", compact(tokens))
        });
    }
    let calls = num_of(get(d, "toolCalls")) as i64;
    if calls > 0 {
        parts.push(if calls == 1 { "1 tool".into() } else { format!("{calls} tools") });
    }
    let stop = s(d, "stop");
    if !stop.is_empty() && stop != "done" {
        let why = if stop == "rejected" && truthy(d, "review") { format!(" ({})", s(d, "review")) } else { String::new() };
        parts.push(format!("stopped: {}{why}", stop.replacen('_', " ", 1)));
    } else {
        let finish = s(d, "finishReason");
        if !finish.is_empty() && finish != "stop" {
            parts.push(finish);
        }
    }
    parts.retain(|p| !p.is_empty());
    parts.join(" · ")
}

const LAST: [&str; 4] = ["done", "cancelled", "error", "unavailable"];

/// Folds the events of one answer (`POST /api/chat`, or `GET /api/stream` when resuming) into its message.
#[derive(Debug, Default)]
pub struct Answer {
    seq: u64,
    ended: bool,
    resumed: bool,
}

/// What an event told the caller beyond the message itself.
#[derive(Debug, PartialEq, Eq)]
pub enum Effect {
    None,
    /// `start`: the answer is stored from now on (the question text of a resumed answer, if any).
    Started(Option<String>),
    Ended,
}

impl Answer {
    /// `resumed`: the page attaches to an answer that started elsewhere (its question and start come from `start`).
    pub fn new(resumed: bool) -> Self {
        Answer { resumed, ..Default::default() }
    }

    pub fn seq(&self) -> u64 {
        self.seq
    }

    pub fn ended(&self) -> bool {
        self.ended
    }

    pub fn on_event(&mut self, reply: &mut Message, e: &SseEvent, now: i64) -> Effect {
        if let Some(id) = e.id.as_deref().and_then(|i| i.parse::<u64>().ok()) {
            self.seq = self.seq.max(id);
        }
        let d = &e.data;
        match e.event.as_str() {
            "reasoning" => {
                append_reasoning(reply, &s(d, "text"), now);
                return Effect::None;
            }
            "delta" => {
                end_reasoning(reply, now);
                append_text(reply, &s(d, "text"));
                return Effect::None;
            }
            "agent_delta" => {
                agent_text(reply, d, now);
                return Effect::None;
            }
            _ => {}
        }
        end_reasoning(reply, now);
        if LAST.contains(&e.event.as_str()) {
            self.ended = true;
        }
        if let Some(Value::String(t)) = d.get("traceId")
            && (e.event == "start" || e.event == "done")
        {
            reply.trace_id = Some(t.clone());
        }
        match e.event.as_str() {
            "start" => {
                if self.resumed {
                    if let Some(at) = d.get("startedAt").and_then(Value::as_i64) {
                        reply.started_at = Some(at);
                    }
                    return Effect::Started(Some(s(d, "question")));
                }
                return Effect::Started(None);
            }
            "tool_delta" => tool_delta(reply, d),
            "tool" => tool_step(reply, d, Some(now)),
            "review" => review(reply, d),
            "approve" => ask_approval(reply, d),
            "plan" => ask_plan(reply, d),
            "question" => ask_user(reply, d),
            "done" => {
                reply.meta = Some(meta(d));
                reply.usage = usage(d);
                reply.model = truthy(d, "model").then(|| s(d, "model"));
                if let Some(Value::String(stop)) = d.get("stop") {
                    reply.stop = Some(stop.clone());
                }
            }
            "unavailable" => fail_answer(reply, "No LLM is connected. Open the palette (Ctrl+K) to pick a connected provider."),
            "error" => fail_answer(reply, &s(d, "message")),
            "cancelled" => reply.meta = Some("stopped".into()),
            _ => {}
        }
        if self.ended { Effect::Ended } else { Effect::None }
    }

    /// The stream is over: times, thoughts and drafts close.
    pub fn finish(&mut self, reply: &mut Message, now: i64) {
        if reply.started_at.is_some() {
            reply.ended_at = Some(now);
        }
        end_reasoning(reply, now);
        end_drafts(reply);
    }
}

/// One stored message, as `GET /api/history` returns it.
#[derive(Debug, Clone, PartialEq)]
pub enum HistoryMessage {
    User { content: String, trace_id: Option<String> },
    Assistant { content: String, tool_calls: Vec<(String, String, String)>, reasoning: Option<String> },
    Tool { tool_call_id: String, status: Status, content: String, diff: Option<String> },
    Review { reason: String },
}

impl HistoryMessage {
    pub fn from_json(v: &Value) -> Option<Self> {
        Some(match v.get("role")?.as_str()? {
            "user" => HistoryMessage::User { content: s(v, "content"), trace_id: v.get("traceId").and_then(Value::as_str).map(Into::into) },
            "assistant" => HistoryMessage::Assistant {
                content: s(v, "content"),
                tool_calls: match v.get("toolCalls") {
                    Some(Value::Array(a)) => a.iter().map(|c| (s(c, "id"), s(c, "name"), s(c, "arguments"))).collect(),
                    _ => vec![],
                },
                reasoning: v.get("reasoning").and_then(Value::as_str).filter(|r| !r.is_empty()).map(Into::into),
            },
            "tool" => HistoryMessage::Tool {
                tool_call_id: s(v, "toolCallId"),
                status: if v.get("status").and_then(Value::as_str) == Some("error") { Status::Error } else { Status::Ok },
                content: s(v, "content"),
                diff: v.get("diff").and_then(Value::as_str).filter(|d| !d.is_empty()).map(Into::into),
            },
            "review" => HistoryMessage::Review { reason: s(v, "reason") },
            _ => return None,
        })
    }
}

/// Folds stored messages into what the user reads: each question, then one answer holding the thoughts,
/// text, tool calls, results and review rejections of that turn, in order.
pub fn from_history(history: &[HistoryMessage], mut id: impl FnMut() -> u64) -> Vec<Message> {
    let mut out: Vec<Message> = vec![];
    let mut answer: Option<usize> = None;
    let mut attempt = 0;
    let mut trace: Option<String> = None;
    for h in history {
        if let HistoryMessage::User { content, trace_id } = h {
            out.push(Message::new(id(), Role::User, content));
            answer = None;
            attempt = 0;
            trace = trace_id.clone();
            continue;
        }
        let i = match answer {
            Some(i) => i,
            None => {
                let mut m = Message::new(id(), Role::Assistant, "");
                m.trace_id = trace.clone();
                out.push(m);
                answer = Some(out.len() - 1);
                out.len() - 1
            }
        };
        let a = &mut out[i];
        match h {
            HistoryMessage::Assistant { content, tool_calls, reasoning } => {
                if let Some(r) = reasoning {
                    append_reasoning(a, r, 0);
                    end_reasoning(a, 0);
                }
                append_text(a, content);
                for (cid, name, arguments) in tool_calls {
                    tool_step(a, &serde_json::json!({"id": cid, "name": name, "arguments": arguments, "status": "running"}), None);
                }
            }
            HistoryMessage::Tool { tool_call_id, status, content, diff } => {
                let call = a.parts.iter().find_map(|p| match p {
                    Part::Tool(t) if &t.id == tool_call_id && t.status == Status::Running => Some((t.name.clone(), t.arguments.clone())),
                    _ => None,
                });
                let (name, arguments) = call.unwrap_or_default();
                let mut d = serde_json::json!({
                    "id": tool_call_id, "name": name, "arguments": arguments,
                    "status": if *status == Status::Error { "error" } else { "ok" }, "content": content,
                });
                if let Some(diff) = diff {
                    d["diff"] = Value::String(diff.clone());
                }
                tool_step(a, &d, None);
            }
            HistoryMessage::Review { reason } => {
                attempt += 1;
                review(a, &serde_json::json!({"attempt": attempt, "pass": false, "reason": reason}));
            }
            HistoryMessage::User { .. } => unreachable!(),
        }
    }
    out
}

/// The tool call `id` of message `message_id`, also among its sub-agents' calls; the latest when ids repeat.
pub fn find_step<'a>(messages: &'a [Message], message_id: u64, id: &str) -> Option<&'a ToolStep> {
    fn search<'a>(parts: &'a [Part], id: &str) -> Option<&'a ToolStep> {
        for p in parts.iter().rev() {
            if let Part::Tool(t) = p {
                if t.id == id {
                    return Some(t);
                }
                if let Some(inner) = t.agent.as_ref().and_then(|a| search(&a.parts, id)) {
                    return Some(inner);
                }
            }
        }
        None
    }
    search(&messages.iter().find(|m| m.id == message_id)?.parts, id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn msg() -> Message {
        Message::new(1, Role::Assistant, "")
    }
    fn steps(m: &Message) -> Vec<&ToolStep> {
        m.parts.iter().filter_map(|p| if let Part::Tool(t) = p { Some(t) } else { None }).collect()
    }
    fn kinds(parts: &[Part]) -> Vec<&'static str> {
        parts
            .iter()
            .map(|p| match p {
                Part::Reasoning { .. } => "reasoning",
                Part::Text(_) => "text",
                Part::Tool(_) => "tool",
                Part::Review { .. } => "review",
                Part::Error(_) => "error",
            })
            .collect()
    }
    fn ev(event: &str, data: Value, id: Option<&str>) -> SseEvent {
        SseEvent { event: event.into(), data, id: id.map(Into::into) }
    }

    #[test]
    fn tool_step_adds_and_updates() {
        let mut m = msg();
        tool_step(&mut m, &json!({"id":"a","name":"read_file","arguments":"{\"path\":\"x\"}","status":"running"}), None);
        tool_step(&mut m, &json!({"id":"b","name":"time","arguments":"{}","status":"running"}), None);
        tool_step(&mut m, &json!({"id":"a","name":"read_file","status":"error","content":"not found: x"}), None);
        let s: Vec<_> = steps(&m).iter().map(|t| (t.name.as_str(), t.status, t.content.clone())).collect();
        assert_eq!(s, vec![("read_file", Status::Error, Some("not found: x".into())), ("time", Status::Running, None)]);
    }

    #[test]
    fn repeated_ids_stay_apart() {
        let mut m = msg();
        for st in ["running", "ok", "running"] {
            tool_step(&mut m, &json!({"id":"a","name":"t","status":st}), None);
        }
        assert_eq!(steps(&m).iter().map(|t| t.status).collect::<Vec<_>>(), vec![Status::Ok, Status::Running]);
    }

    #[test]
    fn timeline_keeps_order() {
        let mut m = msg();
        append_text(&mut m, "Let me ");
        append_text(&mut m, "look.");
        tool_step(&mut m, &json!({"id":"a","name":"read_file","status":"running"}), None);
        tool_step(&mut m, &json!({"id":"a","name":"read_file","status":"ok"}), None);
        append_text(&mut m, "Done.");
        assert_eq!(kinds(&m.parts), vec!["text", "tool", "text"]);
        assert_eq!(m.parts[0], Part::Text("Let me look.".into()));
        assert_eq!(m.text, "Let me look.Done.");
        append_text(&mut m, "");
        assert_eq!(m.parts.len(), 3);
    }

    #[test]
    fn reviews() {
        let mut m = msg();
        review(&mut m, &json!({"attempt":1,"error":"the reviewer gave no verdict: maybe"}));
        assert_eq!(
            m.parts[0],
            Part::Review { attempt: 1, pass: true, reason: String::new(), error: Some("the reviewer gave no verdict: maybe".into()) }
        );
        assert_eq!(activity(&m), "Waiting for the model…");
        let mut m = msg();
        append_text(&mut m, "first");
        review(&mut m, &json!({"attempt":1,"pass":false,"reason":"no tests"}));
        append_text(&mut m, "second");
        review(&mut m, &json!({"attempt":2,"pass":true,"reason":"ok"}));
        assert_eq!(kinds(&m.parts), vec!["text", "review", "text", "review"]);
    }

    #[test]
    fn activity_says_what_happens() {
        let mut m = msg();
        assert_eq!(activity(&m), "Waiting for the model…");
        append_text(&mut m, "hi");
        assert_eq!(activity(&m), "Writing…");
        tool_step(&mut m, &json!({"id":"a","name":"write_file","status":"running"}), None);
        assert_eq!(activity(&m), "Running write_file…");
        ask_approval(&mut m, &json!({"id":"q","callId":"a","name":"write_file","risk":"write"}));
        assert_eq!(activity(&m), "Waiting for approval");
        tool_step(&mut m, &json!({"id":"a","name":"write_file","status":"ok"}), None);
        assert_eq!(activity(&m), "Waiting for the model…");
        review(&mut m, &json!({"attempt":1,"pass":false,"reason":"x"}));
        assert_eq!(activity(&m), "Trying again…");
    }

    #[test]
    fn meta_line() {
        assert_eq!(
            meta(&json!({"model":"m","usage":{"promptTokens":3,"completionTokens":2},"toolCalls":2,"stop":"done","finishReason":"stop"})),
            "m · 5 tokens · 2 tools"
        );
        assert_eq!(
            meta(&json!({"model":"m","toolCalls":1,"stop":"max_steps","finishReason":"tool_calls"})),
            "m · 1 tool · stopped: max steps"
        );
        assert_eq!(meta(&json!({"model":"m","finishReason":"length"})), "m · length");
        assert_eq!(meta(&json!({"model":"m","stop":"rejected","review":"no tests run"})), "m · stopped: rejected (no tests run)");
        assert_eq!(
            meta(&json!({"model":"m","usage":{"promptTokens":10,"completionTokens":40,"reasoningTokens":30}})),
            "m · 50 tokens (30 thinking)"
        );
    }

    #[test]
    fn approval_until_its_call_finishes() {
        let mut m = msg();
        tool_step(&mut m, &json!({"id":"a","name":"write_file","arguments":"{}","status":"running"}), None);
        ask_approval(&mut m, &json!({"id":"q1","callId":"a","name":"write_file","arguments":"{\"path\":\"x\"}","risk":"write"}));
        assert_eq!(m.approval.as_ref().unwrap().arguments, "{\"path\":\"x\"}");
        tool_step(&mut m, &json!({"id":"b","name":"time","status":"ok"}), None);
        assert_eq!(m.approval.as_ref().unwrap().id, "q1");
        tool_step(&mut m, &json!({"id":"a","name":"write_file","status":"error","content":"denied"}), None);
        assert!(m.approval.is_none());
    }

    #[test]
    fn plan_until_exit_plan_mode_finishes() {
        let mut m = msg();
        tool_step(&mut m, &json!({"id":"p","name":EXIT_PLAN_TOOL,"arguments":"{}","status":"running"}), None);
        ask_plan(&mut m, &json!({"id":"x","plan":"# Plan","items":["look","fix"],"criteria":["fixed"]}));
        assert_eq!(
            m.plan,
            Some(PlanProposal {
                id: "x".into(),
                plan: "# Plan".into(),
                items: vec!["look".into(), "fix".into()],
                criteria: vec!["fixed".into()]
            })
        );
        assert_eq!(activity(&m), "Waiting for plan approval");
        tool_step(&mut m, &json!({"id":"c","name":EXIT_PLAN_TOOL,"status":"error","parent":"p","agent":"explore"}), None);
        assert!(m.plan.is_some());
        tool_step(&mut m, &json!({"id":"p","name":EXIT_PLAN_TOOL,"status":"ok","content":"approved"}), None);
        assert!(m.plan.is_none());

        let mut r = msg();
        Answer::new(false).on_event(&mut r, &ev("plan", json!({"id":"y","plan":"p"}), Some("1")), 0);
        assert_eq!(r.plan, Some(PlanProposal { id: "y".into(), plan: "p".into(), items: vec![], criteria: vec![] }));
    }

    #[test]
    fn can_continue_after_a_hard_limit() {
        let mut r = msg();
        r.streaming = true;
        Answer::new(false).on_event(&mut r, &ev("done", json!({"model":"m","stop":"max_steps"}), Some("1")), 0);
        assert!(!can_continue(&r));
        r.streaming = false;
        assert!(can_continue(&r));
        for (stop, ok) in [("budget", true), ("truncated", true), ("stalled", true), ("done", false), ("rejected", false)] {
            r.stop = Some(stop.into());
            assert_eq!(can_continue(&r), ok, "{stop}");
        }
    }

    #[test]
    fn questions_until_ask_user_finishes() {
        let mut m = msg();
        tool_step(&mut m, &json!({"id":"q","name":ASK_USER_TOOL,"arguments":"{}","status":"running"}), None);
        ask_user(
            &mut m,
            &json!({"id":"x","questions":[{"question":"Which cache?","header":"Cache","multiSelect":false,"options":[{"label":"Redis","description":"shared"},{"label":"Memory"}]}]}),
        );
        let q = &m.question.as_ref().unwrap().questions[0];
        assert_eq!(q.options, vec![("Redis".into(), "shared".into()), ("Memory".into(), String::new())]);
        assert_eq!(activity(&m), "Waiting for your answer");
        tool_step(&mut m, &json!({"id":"q2","name":ASK_USER_TOOL,"status":"error","parent":"a","agent":"explore"}), None);
        assert!(m.question.is_some());
        tool_step(&mut m, &json!({"id":"q","name":ASK_USER_TOOL,"status":"ok"}), None);
        assert!(m.question.is_none());

        let mut r = msg();
        Answer::new(false).on_event(
            &mut r,
            &ev("question", json!({"id":"y","questions":[{"question":"q?","multiSelect":true}]}), Some("1")),
            0,
        );
        assert_eq!(
            r.question.unwrap().questions[0],
            UserQuestion { question: "q?".into(), header: String::new(), multi_select: true, options: vec![] }
        );
    }

    fn hist(v: Value) -> Vec<HistoryMessage> {
        v.as_array().unwrap().iter().map(|m| HistoryMessage::from_json(m).unwrap()).collect()
    }

    #[test]
    fn from_history_folds_turns() {
        let mut n = 0;
        let out = from_history(
            &hist(json!([
                {"role":"user","content":"q"},
                {"role":"assistant","content":"Let me look.","toolCalls":[{"id":"a","name":"read_file","arguments":"{}"},{"id":"b","name":"nope","arguments":"{}"}]},
                {"role":"tool","toolCallId":"a","status":"ok","content":"text"},
                {"role":"tool","toolCallId":"b","status":"error","content":"unknown tool"},
                {"role":"assistant","content":"first"},
                {"role":"review","reason":"no tests"},
                {"role":"assistant","content":"second"},
                {"role":"user","content":"q2"},
                {"role":"assistant","content":"a2"}
            ])),
            || {
                n += 1;
                n
            },
        );
        assert_eq!(out.iter().map(|m| m.role).collect::<Vec<_>>(), vec![Role::User, Role::Assistant, Role::User, Role::Assistant]);
        assert_eq!(kinds(&out[1].parts), vec!["text", "tool", "tool", "text", "review", "text"]);
        let s: Vec<_> = steps(&out[1]).iter().map(|t| (t.name.clone(), t.status, t.content.clone().unwrap())).collect();
        assert_eq!(s, vec![("read_file".into(), Status::Ok, "text".into()), ("nope".into(), Status::Error, "unknown tool".into())]);
        assert_eq!(out.iter().map(|m| m.id).collect::<Vec<_>>(), vec![1, 2, 3, 4]);
    }

    #[test]
    fn from_history_thoughts_and_traces() {
        let out = from_history(
            &hist(json!([
                {"role":"user","content":"q","traceId":"t1"},
                {"role":"assistant","content":"","reasoning":"read it first","toolCalls":[{"id":"a","name":"read_file","arguments":"{}"}]},
                {"role":"tool","toolCallId":"a","status":"ok","content":"text"},
                {"role":"assistant","content":"done","reasoning":"enough"},
                {"role":"user","content":"untraced"},
                {"role":"assistant","content":"b"}
            ])),
            || 0,
        );
        assert_eq!(kinds(&out[1].parts), vec!["reasoning", "tool", "reasoning", "text"]);
        assert_eq!(out[1].parts[0], Part::Reasoning { text: "read it first".into(), started_at: 0, ended_at: Some(0) });
        assert_eq!(reasoning_label(0, Some(0), 99), "Thought");
        assert_eq!(out.iter().map(|m| m.trace_id.clone()).collect::<Vec<_>>(), vec![None, Some("t1".into()), None, None]);
    }

    #[test]
    fn drafts_grow_until_the_call_starts() {
        let mut m = msg();
        append_text(&mut m, "Writing it.");
        tool_delta(&mut m, &json!({"index":0,"id":"a","name":"write_file","arguments":"{\"path\":"}));
        tool_delta(&mut m, &json!({"index":1,"id":"b","name":"time","arguments":"{}"}));
        tool_delta(&mut m, &json!({"index":0,"arguments":"\"x\"}"}));
        let s: Vec<_> = steps(&m).iter().map(|t| (t.name.clone(), t.status, t.arguments.clone())).collect();
        assert_eq!(
            s,
            vec![("write_file".into(), Status::Drafting, "{\"path\":\"x\"}".into()), ("time".into(), Status::Drafting, "{}".into())]
        );
        assert_eq!(activity(&m), "Drafting time call…");
        tool_step(&mut m, &json!({"id":"a","name":"write_file","arguments":"{\"path\":\"x\"}","status":"running"}), None);
        tool_step(&mut m, &json!({"id":"a","name":"write_file","status":"ok"}), None);
        tool_step(&mut m, &json!({"id":"b","name":"time","arguments":"{}","status":"running"}), None);
        assert_eq!(kinds(&m.parts), vec!["text", "tool", "tool"]);
        assert_eq!(steps(&m).iter().map(|t| t.status).collect::<Vec<_>>(), vec![Status::Ok, Status::Running]);
    }

    #[test]
    fn drafts_without_ids_and_left_over() {
        let mut m = msg();
        tool_delta(&mut m, &json!({"index":0,"name":"time","arguments":"{}"}));
        tool_step(&mut m, &json!({"id":"call_0","name":"time","status":"running"}), None);
        assert_eq!(steps(&m)[0].id, "call_0");
        assert_eq!(steps(&m)[0].status, Status::Running);

        let mut m = msg();
        tool_delta(&mut m, &json!({"index":0,"id":"a","name":"write_file","arguments":"{"}));
        end_drafts(&mut m);
        assert_eq!((steps(&m)[0].status, steps(&m)[0].content.as_deref()), (Status::Error, Some("not run")));

        let mut m = msg();
        tool_delta(&mut m, &json!({"index":0,"id":"a","name":"write_file","arguments":"{\"content\":\"lo"}));
        tool_delta(&mut m, &json!({"index":0,"id":"b","name":"edit_file","arguments":"{}"}));
        assert_eq!(
            steps(&m).iter().map(|t| (t.id.as_str(), t.status)).collect::<Vec<_>>(),
            vec![("a", Status::Error), ("b", Status::Drafting)]
        );
        tool_step(&mut m, &json!({"id":"b","name":"edit_file","arguments":"{}","status":"running"}), None);
        assert_eq!(
            steps(&m).iter().map(|t| (t.id.as_str(), t.status)).collect::<Vec<_>>(),
            vec![("a", Status::Error), ("b", Status::Running)]
        );
    }

    #[test]
    fn answer_builds_and_ends() {
        let mut r = msg();
        let mut a = Answer::new(false);
        assert_eq!(
            a.on_event(&mut r, &ev("start", json!({"question":"q","startedAt":5,"traceId":"abc"}), Some("1")), 0),
            Effect::Started(None)
        );
        assert_eq!(r.started_at, None);
        assert_eq!(r.trace_id.as_deref(), Some("abc"));
        a.on_event(&mut r, &ev("delta", json!({"text":"Hi"}), Some("2")), 0);
        a.on_event(&mut r, &ev("tool_delta", json!({"index":0,"id":"a","name":"time","arguments":"{}"}), Some("3")), 0);
        assert!(!a.ended());
        assert_eq!(a.on_event(&mut r, &ev("done", json!({"model":"m","traceId":"def"}), Some("4")), 0), Effect::Ended);
        a.finish(&mut r, 0);
        assert_eq!(a.seq(), 4);
        assert_eq!(kinds(&r.parts), vec!["text", "tool"]);
        assert_eq!(steps(&r)[0].status, Status::Error);
        assert_eq!(r.meta.as_deref(), Some("m"));
        assert_eq!(r.trace_id.as_deref(), Some("def"));
    }

    #[test]
    fn resumed_answer_takes_question_and_time() {
        let mut r = msg();
        let mut a = Answer::new(true);
        assert_eq!(
            a.on_event(&mut r, &ev("start", json!({"question":"q","startedAt":5}), Some("1")), 0),
            Effect::Started(Some("q".into()))
        );
        a.on_event(&mut r, &ev("delta", json!({"text":"a"}), Some("3")), 0);
        a.on_event(&mut r, &ev("delta", json!({"text":"b"}), Some("2")), 0);
        assert_eq!(r.started_at, Some(5));
        assert_eq!(a.seq(), 3);
    }

    #[test]
    fn errors_go_in_the_timeline() {
        let mut r = msg();
        let mut a = Answer::new(false);
        a.on_event(&mut r, &ev("delta", json!({"text":"part"}), None), 0);
        a.on_event(&mut r, &ev("error", json!({"message":"boom"}), None), 0);
        assert!(r.error);
        assert_eq!(r.parts.last(), Some(&Part::Error("boom".into())));
        assert!(a.ended());
    }

    #[test]
    fn sub_agents_nest() {
        let mut m = msg();
        tool_step(&mut m, &json!({"id":"a","name":"agent","arguments":"{\"agent\":\"explore\"}","status":"running"}), None);
        agent_text(&mut m, &json!({"parent":"a","agent":"explore","text":"Looking "}), 0);
        agent_text(&mut m, &json!({"parent":"a","agent":"explore","text":"around."}), 0);
        assert_eq!(activity(&m), "explore: working…");
        tool_step(&mut m, &json!({"id":"x","name":"read_file","status":"running","parent":"a","agent":"explore"}), None);
        assert_eq!(activity(&m), "explore: running read_file…");
        tool_step(&mut m, &json!({"id":"x","name":"read_file","status":"ok","content":"text","parent":"a","agent":"explore"}), None);
        tool_step(&mut m, &json!({"id":"a","name":"agent","arguments":"{\"agent\":\"explore\"}","status":"ok","content":"found it"}), None);
        assert_eq!(kinds(&m.parts), vec!["tool"]);
        let agent = steps(&m)[0].agent.clone().unwrap();
        assert_eq!(agent.name, "explore");
        assert_eq!(agent.text, "Looking around.");
        assert_eq!(kinds(&agent.parts), vec!["text", "tool"]);
        assert_eq!(steps(&m)[0].content.as_deref(), Some("found it"));
        assert_eq!(m.text, "");
        tool_step(&mut m, &json!({"id":"b","name":"agent","status":"running"}), None);
        tool_step(&mut m, &json!({"id":"c","name":"agent","status":"running"}), None);
        assert_eq!(activity(&m), "Running 2 tools…");
    }

    #[test]
    fn queued_approvals() {
        let mut m = msg();
        tool_step(&mut m, &json!({"id":"a","name":"agent","status":"running"}), None);
        tool_step(&mut m, &json!({"id":"x","name":"write_file","status":"running","parent":"a","agent":"explore"}), None);
        tool_step(&mut m, &json!({"id":"y","name":"write_file","status":"running"}), None);
        ask_approval(&mut m, &json!({"id":"q1","callId":"x","name":"write_file","risk":"write","agent":"explore"}));
        ask_approval(&mut m, &json!({"id":"q2","callId":"y","name":"write_file","risk":"write"}));
        assert_eq!(m.approval.as_ref().unwrap().agent.as_deref(), Some("explore"));
        assert_eq!(m.waiting.iter().map(|a| a.id.as_str()).collect::<Vec<_>>(), vec!["q2"]);
        tool_step(&mut m, &json!({"id":"x","name":"write_file","status":"ok","parent":"a","agent":"explore"}), None);
        assert_eq!(m.approval.as_ref().unwrap().id, "q2");
        assert!(m.waiting.is_empty());
        clear_approval(&mut m, |a| a.id == "q2");
        assert!(m.approval.is_none());
    }

    #[test]
    fn reasoning_apart_from_text() {
        let mut m = msg();
        let mut a = Answer::new(false);
        a.on_event(&mut m, &ev("reasoning", json!({"text":"17*23 "}), None), 1000);
        a.on_event(&mut m, &ev("reasoning", json!({"text":"is 391"}), None), 1000);
        assert_eq!(activity(&m), "Thinking…");
        a.on_event(&mut m, &ev("delta", json!({"text":"391"}), None), 5000);
        assert_eq!(m.parts[0], Part::Reasoning { text: "17*23 is 391".into(), started_at: 1000, ended_at: Some(5000) });
        assert_eq!(m.text, "391");
        assert_eq!(activity(&m), "Writing…");

        let mut m = msg();
        let mut a = Answer::new(false);
        a.on_event(&mut m, &ev("reasoning", json!({"text":"look first"}), None), 0);
        a.on_event(&mut m, &ev("tool", json!({"id":"x","name":"read_file","status":"running"}), None), 0);
        a.on_event(&mut m, &ev("tool", json!({"id":"x","name":"read_file","status":"ok"}), None), 0);
        assert_eq!(activity(&m), "Waiting for the model…");
        a.on_event(&mut m, &ev("reasoning", json!({"text":"now answer"}), None), 0);
        a.finish(&mut m, 0);
        assert_eq!(kinds(&m.parts), vec!["reasoning", "tool", "reasoning"]);

        let mut m = msg();
        let mut a = Answer::new(false);
        a.on_event(&mut m, &ev("tool", json!({"id":"a","name":"agent","status":"running"}), None), 0);
        a.on_event(&mut m, &ev("agent_delta", json!({"parent":"a","agent":"explore","text":"hmm","reasoning":true}), None), 0);
        a.on_event(&mut m, &ev("agent_delta", json!({"parent":"a","agent":"explore","text":"Found it."}), None), 0);
        let agent = steps(&m)[0].agent.clone().unwrap();
        assert_eq!(kinds(&agent.parts), vec!["reasoning", "text"]);
        assert_eq!(agent.text, "Found it.");
    }

    #[test]
    fn reasoning_labels_and_tails() {
        assert_eq!(reasoning_label(0, None, 500), "Thinking");
        assert_eq!(reasoning_label(0, None, 12_000), "Thinking · 12s");
        assert_eq!(reasoning_label(0, Some(65_000), 99_000), "Thought for 1m 05s");
        assert_eq!(thought_tail("", 5, 600), "");
        assert_eq!(thought_tail("one", 5, 600), "one");
        assert_eq!(thought_tail("a\n\nb\nc  \n\nd\n", 2, 600), "c\nd");
        let t = thought_tail(&format!("{} end", "x".repeat(50)), 1, 10);
        assert_eq!(t, "…xxxxx end");
        assert_eq!(t.chars().count(), 10);
    }

    #[test]
    fn find_step_searches_sub_agents() {
        let mut m = Message::new(7, Role::Assistant, "");
        tool_step(&mut m, &json!({"id":"a","name":"agent","status":"running"}), None);
        tool_step(&mut m, &json!({"id":"x","name":"read_file","status":"running","parent":"a","agent":"explore"}), None);
        tool_step(&mut m, &json!({"id":"x","name":"read_file","status":"ok","content":"text","parent":"a","agent":"explore"}), None);
        let mut other = Message::new(8, Role::Assistant, "");
        tool_step(&mut other, &json!({"id":"x","name":"exec","status":"ok"}), None);
        let all = [m, other];
        assert_eq!(find_step(&all, 7, "x").unwrap().name, "read_file");
        assert_eq!(find_step(&all, 7, "x").unwrap().status, Status::Ok);
        assert_eq!(find_step(&all, 8, "x").unwrap().name, "exec");
        assert!(find_step(&all, 9, "x").is_none());
    }
}
