//! A trace laid out as a waterfall, ported from ui-v2's `trace.ts`.

use super::format::{ms, text_of};
use serde_json::{Map, Value};

#[derive(Debug, Clone, PartialEq)]
pub struct Span {
    pub id: String,
    pub parent_id: Option<String>,
    pub name: String,
    pub start_micros: f64,
    pub duration_micros: f64,
    /// ok, error or cancelled.
    pub status: String,
    pub error: Option<String>,
    pub attrs: Map<String, Value>,
}

impl Span {
    pub fn from_json(v: &Value) -> Option<Span> {
        Some(Span {
            id: text_of(v.get("id")),
            parent_id: v.get("parentId").and_then(Value::as_str).map(Into::into),
            name: text_of(v.get("name")),
            start_micros: v.get("startMicros")?.as_f64()?,
            duration_micros: v.get("durationMicros").and_then(Value::as_f64).unwrap_or(0.0),
            status: text_of(v.get("status")),
            error: v.get("error").and_then(Value::as_str).map(Into::into),
            attrs: v.get("attrs").and_then(Value::as_object).cloned().unwrap_or_default(),
        })
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Row {
    pub id: String,
    pub depth: usize,
    pub label: String,
    /// Where the bar starts and how wide it is, in % of the root's duration.
    pub offset: f64,
    pub width: f64,
    pub start_ms: f64,
    pub duration_ms: f64,
    pub tokens: f64,
    pub status: String,
    pub error: Option<String>,
    pub detail: String,
    pub attrs: Map<String, Value>,
}

fn num(a: &Map<String, Value>, k: &str) -> f64 {
    a.get(k).and_then(Value::as_f64).filter(|v| v.is_finite()).unwrap_or(0.0)
}

/// Each span under its parent, children in start order, bars scaled to the root; orphans hang under the root.
pub fn layout(spans: &[Span]) -> Vec<Row> {
    if spans.is_empty() {
        return vec![];
    }
    let root = spans.iter().position(|s| s.parent_id.is_none()).unwrap_or(0);
    let ids: Vec<&str> = spans.iter().map(|s| s.id.as_str()).collect();
    let mut children: Vec<(String, Vec<usize>)> = vec![];
    for (i, s) in spans.iter().enumerate() {
        if i == root {
            continue;
        }
        let parent = match &s.parent_id {
            Some(p) if ids.contains(&p.as_str()) => p.clone(),
            _ => spans[root].id.clone(),
        };
        match children.iter_mut().find(|(p, _)| *p == parent) {
            Some((_, list)) => list.push(i),
            None => children.push((parent, vec![i])),
        }
    }
    for (_, list) in &mut children {
        list.sort_by(|a, b| spans[*a].start_micros.total_cmp(&spans[*b].start_micros));
    }
    let r = &spans[root];
    let total = r.duration_micros.max(1.0);
    let mut rows = vec![];
    let mut stack = vec![(root, 0usize)];
    while let Some((i, depth)) = stack.pop() {
        let s = &spans[i];
        let start = (s.start_micros - r.start_micros).max(0.0);
        let offset = (start / total * 100.0).clamp(0.0, 100.0);
        rows.push(Row {
            id: s.id.clone(),
            depth,
            label: label(s),
            offset,
            width: (100.0 - offset).min((s.duration_micros / total * 100.0).max(0.0)),
            start_ms: start / 1000.0,
            duration_ms: s.duration_micros / 1000.0,
            tokens: num(&s.attrs, "promptTokens") + num(&s.attrs, "completionTokens"),
            status: s.status.clone(),
            error: s.error.clone(),
            detail: detail(s),
            attrs: s.attrs.clone(),
        });
        if let Some((_, list)) = children.iter().find(|(p, _)| *p == s.id) {
            for c in list.iter().rev() {
                stack.push((*c, depth + 1));
            }
        }
    }
    rows
}

pub fn label(s: &Span) -> String {
    let a = &s.attrs;
    match s.name.as_str() {
        "llm" => format!("llm · step {}", num(a, "step")),
        "tool" => format!("tool · {}", a.get("tool").map(|v| text_of(Some(v))).unwrap_or_else(|| "?".into())),
        "review" => format!("review · {}", num(a, "attempt")),
        "turn" if a.get("agent").is_some_and(|v| !v.is_null() && v != "") => format!("agent · {}", text_of(a.get("agent"))),
        _ => s.name.clone(),
    }
}

fn detail(s: &Span) -> String {
    let a = &s.attrs;
    let mut parts: Vec<String> = vec![];
    let tokens = num(a, "promptTokens") + num(a, "completionTokens");
    if s.name == "turn" {
        let stop = text_of(a.get("stop"));
        if !stop.is_empty() && stop != "done" {
            parts.push(format!("stopped: {}", stop.replacen('_', " ", 1)));
        }
        if a.contains_key("steps") {
            let n = num(a, "steps");
            parts.push(if n == 1.0 { "1 step".into() } else { format!("{n} steps") });
        }
    }
    if tokens > 0.0 {
        parts.push(format!("{}+{} tok", num(a, "promptTokens"), num(a, "completionTokens")));
    }
    if s.name == "llm" && a.contains_key("firstTokenMicros") {
        parts.push(format!("first token {}", ms(num(a, "firstTokenMicros") / 1000.0)));
    }
    if s.name == "tool" {
        if num(a, "approvalMicros") >= 1000.0 {
            parts.push(format!("approval {}", ms(num(a, "approvalMicros") / 1000.0)));
        }
        if a.contains_key("outputChars") {
            let cut = a.get("truncated").is_some_and(|v| v == &Value::Bool(true));
            parts.push(format!("{} chars{}", num(a, "outputChars"), if cut { " (cut)" } else { "" }));
        }
        if a.get("refused").is_some_and(|v| v == &Value::Bool(true)) {
            parts.push("refused".into());
        }
    }
    if s.name == "review" {
        let v = text_of(a.get("verdict"));
        if !v.is_empty() {
            parts.push(v);
        }
    }
    if s.name == "compaction" {
        parts.push(format!("{} messages", num(a, "messages")));
    }
    parts.join(" · ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn span(id: &str, parent: Option<&str>, name: &str, start: f64, duration: f64, attrs: Value, status: &str) -> Span {
        Span {
            id: id.into(),
            parent_id: parent.map(Into::into),
            name: name.into(),
            start_micros: start,
            duration_micros: duration,
            status: status.into(),
            error: None,
            attrs: attrs.as_object().cloned().unwrap(),
        }
    }

    #[test]
    fn places_spans_under_parents() {
        let rows = layout(&[
            span("t2", Some("r"), "llm", 1_600_000.0, 400_000.0, json!({"step":2,"promptTokens":30,"completionTokens":5}), "ok"),
            span(
                "r",
                None,
                "turn",
                1_000_000.0,
                1_000_000.0,
                json!({"steps":2,"stop":"done","promptTokens":50,"completionTokens":10}),
                "ok",
            ),
            span(
                "t1",
                Some("r"),
                "llm",
                1_000_000.0,
                500_000.0,
                json!({"step":1,"firstTokenMicros":120_000,"promptTokens":20,"completionTokens":5}),
                "ok",
            ),
            span("x", Some("r"), "tool", 1_500_000.0, 100_000.0, json!({"tool":"read_file","outputChars":42}), "error"),
        ]);
        assert_eq!(
            rows.iter().map(|r| (r.label.as_str(), r.depth)).collect::<Vec<_>>(),
            vec![("turn", 0), ("llm · step 1", 1), ("tool · read_file", 1), ("llm · step 2", 1)]
        );
        assert_eq!((rows[0].offset, rows[0].width, rows[0].duration_ms, rows[0].tokens), (0.0, 100.0, 1000.0, 60.0));
        assert_eq!(rows[0].detail, "2 steps · 50+10 tok");
        assert_eq!(rows[1].detail, "20+5 tok · first token 120ms");
        assert_eq!((rows[2].offset, rows[2].width, rows[2].start_ms, rows[2].detail.as_str()), (50.0, 10.0, 500.0, "42 chars"));
        assert_eq!((rows[3].offset, rows[3].width), (60.0, 40.0));
    }

    #[test]
    fn zero_root_orphans_and_sub_agents() {
        let rows = layout(&[span("r", None, "turn", 0.0, 0.0, json!({}), "ok"), span("a", Some("r"), "llm", 0.0, 5.0, json!({}), "ok")]);
        assert!(rows.iter().all(|r| r.offset >= 0.0 && r.offset + r.width <= 100.0));
        let rows = layout(&[
            span("r", None, "turn", 0.0, 100.0, json!({}), "ok"),
            span("o", Some("gone"), "tool", 10.0, 5.0, json!({"tool":"x"}), "ok"),
            span("g", Some("o"), "sub", 11.0, 1.0, json!({}), "ok"),
        ]);
        assert_eq!(rows.iter().map(|r| (r.id.as_str(), r.depth)).collect::<Vec<_>>(), vec![("r", 0), ("o", 1), ("g", 2)]);
        let rows = layout(&[
            span("r", None, "turn", 0.0, 1000.0, json!({"steps":2}), "ok"),
            span("a", Some("r"), "tool", 100.0, 800.0, json!({"tool":"agent","promptTokens":40,"completionTokens":8}), "ok"),
            span("s", Some("a"), "turn", 110.0, 780.0, json!({"agent":"explore","steps":3}), "ok"),
            span("l", Some("s"), "llm", 120.0, 100.0, json!({"step":1}), "ok"),
        ]);
        assert_eq!(
            rows.iter().map(|r| (r.label.as_str(), r.depth)).collect::<Vec<_>>(),
            vec![("turn", 0), ("tool · agent", 1), ("agent · explore", 2), ("llm · step 1", 3)]
        );
        assert_eq!(rows[1].detail, "40+8 tok");
        let r = layout(&[span("r", None, "turn", 0.0, 1.0, json!({"stop":"max_steps","steps":1}), "ok")]);
        assert_eq!(r[0].detail, "stopped: max steps · 1 step");
        assert!(layout(&[]).is_empty());
    }
}
