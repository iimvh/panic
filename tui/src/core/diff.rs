//! What a file tool changed, ported from ui-v2's `diff.ts`: the server's unified diff, else one rebuilt
//! from the call's arguments (a conversation stored before the server sent diffs).

use super::format::args;
use super::reply::{Status, ToolStep};
use serde_json::Value;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineKind {
    Ctx,
    Add,
    Del,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiffLine {
    pub kind: LineKind,
    pub old: Option<u64>,
    pub new: Option<u64>,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct FileDiff {
    pub hunks: Vec<Vec<DiffLine>>,
    /// Added and removed lines a long diff leaves out.
    pub hidden: (u64, u64),
}

impl FileDiff {
    pub fn line_count(&self) -> usize {
        self.hunks.iter().map(Vec::len).sum()
    }
}

fn header(line: &str) -> Option<(u64, Option<u64>, u64, Option<u64>)> {
    let rest = line.strip_prefix("@@ -")?;
    let (old, rest) = rest.split_once(" +")?;
    let (new, _) = rest.split_once(" @@")?;
    let range = |r: &str| -> Option<(u64, Option<u64>)> {
        match r.split_once(',') {
            Some((a, b)) => Some((a.parse().ok()?, Some(b.parse().ok()?))),
            None => Some((r.parse().ok()?, None)),
        }
    };
    let (a, b) = range(old)?;
    let (c, d) = range(new)?;
    Some((a, b, c, d))
}

fn note(line: &str) -> Option<(u64, u64)> {
    let rest = line.strip_prefix("\\ not shown: +")?;
    let (a, rest) = rest.split_once(" -")?;
    let digits: String = rest.chars().take_while(char::is_ascii_digit).collect();
    Some((a.parse().ok()?, digits.parse().ok()?))
}

/// The hunks of a unified diff as the server sends it.
pub fn parse_unified(diff: &str) -> FileDiff {
    let mut out = FileDiff::default();
    let (mut old_no, mut new_no) = (0u64, 0u64);
    for line in diff.split('\n') {
        if let Some((a, b, c, d)) = header(line) {
            // a count of 0 names the line before the (empty) range
            old_no = if b == Some(0) { a + 1 } else { a };
            new_no = if d == Some(0) { c + 1 } else { c };
            out.hunks.push(vec![]);
            continue;
        }
        if let Some(h) = note(line) {
            out.hidden = h;
            continue;
        }
        let Some(hunk) = out.hunks.last_mut() else { continue };
        if line.is_empty() {
            continue;
        }
        let text = line[1..].to_string();
        match line.as_bytes()[0] {
            b'+' => {
                hunk.push(DiffLine { kind: LineKind::Add, old: None, new: Some(new_no), text });
                new_no += 1;
            }
            b'-' => {
                hunk.push(DiffLine { kind: LineKind::Del, old: Some(old_no), new: None, text });
                old_no += 1;
            }
            b' ' => {
                hunk.push(DiffLine { kind: LineKind::Ctx, old: Some(old_no), new: Some(new_no), text });
                old_no += 1;
                new_no += 1;
            }
            _ => {}
        }
    }
    out
}

const MAX_LCS: usize = 200;

fn line_diff(before: &[&str], after: &[&str], old_start: Option<u64>, new_start: Option<u64>) -> Vec<DiffLine> {
    let (n, m) = (before.len(), after.len());
    let (mut o, mut w) = (old_start, new_start);
    let next = |c: &mut Option<u64>| {
        let v = *c;
        if let Some(x) = c {
            *x += 1;
        }
        v
    };
    let mut out = vec![];
    if n > MAX_LCS || m > MAX_LCS {
        for t in before {
            out.push(DiffLine { kind: LineKind::Del, old: next(&mut o), new: None, text: t.to_string() });
        }
        for t in after {
            out.push(DiffLine { kind: LineKind::Add, old: None, new: next(&mut w), text: t.to_string() });
        }
        return out;
    }
    let mut lcs = vec![vec![0u32; m + 1]; n + 1];
    for i in (0..n).rev() {
        for j in (0..m).rev() {
            lcs[i][j] = if before[i] == after[j] { lcs[i + 1][j + 1] + 1 } else { lcs[i + 1][j].max(lcs[i][j + 1]) };
        }
    }
    let (mut i, mut j) = (0, 0);
    while i < n || j < m {
        if i < n && j < m && before[i] == after[j] {
            let old = next(&mut o);
            out.push(DiffLine { kind: LineKind::Ctx, old, new: next(&mut w), text: before[i].to_string() });
            i += 1;
            j += 1;
        } else if i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]) {
            out.push(DiffLine { kind: LineKind::Del, old: next(&mut o), new: None, text: before[i].to_string() });
            i += 1;
        } else {
            out.push(DiffLine { kind: LineKind::Add, old: None, new: next(&mut w), text: after[j].to_string() });
            j += 1;
        }
    }
    out
}

fn lines(text: &str) -> Vec<String> {
    if text.is_empty() {
        return vec![];
    }
    let mut out: Vec<String> = text.replace("\r\n", "\n").split('\n').map(str::to_string).collect();
    if out.last().is_some_and(String::is_empty) {
        out.pop();
    }
    out
}

/// The change a file tool made, from its arguments, when the server sent no diff.
pub fn fallback_diff(name: &str, arguments: &str, content: Option<&str>) -> Option<FileDiff> {
    let a = args(arguments);
    let str_of = |k: &str| a.get(k).and_then(Value::as_str);
    let out = if name == "edit_file" {
        let (old, new) = (str_of("old_str")?, str_of("new_str")?);
        let line = content.and_then(|c| {
            let i = c.find("first at line ")?;
            c[i + 14..].chars().take_while(char::is_ascii_digit).collect::<String>().parse::<u64>().ok()
        });
        let (b, f) = (lines(old), lines(new));
        line_diff(&b.iter().map(String::as_str).collect::<Vec<_>>(), &f.iter().map(String::as_str).collect::<Vec<_>>(), line, line)
    } else if name == "write_file" {
        let content = str_of("content")?;
        let start = if a.get("append") == Some(&Value::Bool(true)) { None } else { Some(1) };
        let f = lines(content);
        line_diff(&[], &f.iter().map(String::as_str).collect::<Vec<_>>(), None, start)
    } else {
        return None;
    };
    if out.is_empty() { None } else { Some(FileDiff { hunks: vec![out], hidden: (0, 0) }) }
}

/// What a finished call changed in a file; None for none.
pub fn step_diff(step: &ToolStep) -> Option<FileDiff> {
    if step.status != Status::Ok {
        return None;
    }
    match &step.diff {
        Some(d) => Some(parse_unified(d)),
        None => fallback_diff(&step.name, &step.arguments, step.content.as_deref()),
    }
}

/// Lines a diff adds and removes, those it leaves out included.
pub fn diff_stats(diff: Option<&FileDiff>) -> (u64, u64) {
    let Some(d) = diff else { return (0, 0) };
    let (mut added, mut removed) = d.hidden;
    for l in d.hunks.iter().flatten() {
        match l.kind {
            LineKind::Add => added += 1,
            LineKind::Del => removed += 1,
            LineKind::Ctx => {}
        }
    }
    (added, removed)
}

/// Lines a write or an edit adds and removes: from the server's diff, else from its arguments (also while
/// it runs). A failed call changed nothing.
pub fn line_delta(step: &ToolStep) -> (u64, u64) {
    if step.status == Status::Error {
        return (0, 0);
    }
    match &step.diff {
        Some(d) => diff_stats(Some(&parse_unified(d))),
        None => diff_stats(fallback_diff(&step.name, &step.arguments, None).as_ref()),
    }
}

/// "Added 3 lines, removed 1 line".
pub fn describe_change((added, removed): (u64, u64)) -> String {
    let n = |k: u64| format!("{k} {}", if k == 1 { "line" } else { "lines" });
    if added > 0 && removed > 0 {
        format!("Added {}, removed {}", n(added), n(removed))
    } else if removed > 0 {
        format!("Removed {}", n(removed))
    } else {
        format!("Added {}", n(added))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn l(kind: LineKind, old: Option<u64>, new: Option<u64>, text: &str) -> DiffLine {
        DiffLine { kind, old, new, text: text.into() }
    }

    #[test]
    fn parses_hunks() {
        let d = parse_unified("@@ -4,3 +4,4 @@\n one\n-two\n+2\n+2b\n three\n");
        assert_eq!(
            d.hunks,
            vec![vec![
                l(LineKind::Ctx, Some(4), Some(4), "one"),
                l(LineKind::Del, Some(5), None, "two"),
                l(LineKind::Add, None, Some(5), "2"),
                l(LineKind::Add, None, Some(6), "2b"),
                l(LineKind::Ctx, Some(6), Some(7), "three"),
            ]]
        );
        let d = parse_unified("@@ -0,0 +1,1 @@\n+a\n@@ -9,1 +10,1 @@\n-x\n+y\n");
        assert_eq!(d.hunks[0][0], l(LineKind::Add, None, Some(1), "a"));
        assert_eq!(d.hunks[1].iter().map(|l| (l.old, l.new)).collect::<Vec<_>>(), vec![(Some(9), None), (None, Some(10))]);
        let d = parse_unified("@@ -0,0 +1,2 @@\n+a\n+b\n\\ not shown: +7 -1\n");
        assert_eq!(d.hidden, (7, 1));
        assert_eq!(diff_stats(Some(&d)), (9, 1));
    }

    #[test]
    fn rebuilds_from_arguments() {
        let d = fallback_diff(
            "edit_file",
            &serde_json::json!({"path":"a.ts","old_str":"x\ny\nz","new_str":"x\nY\nz"}).to_string(),
            Some("edited a.ts: replaced 1 occurrence (first at line 12)"),
        )
        .unwrap();
        assert_eq!(
            d.hunks[0],
            vec![
                l(LineKind::Ctx, Some(12), Some(12), "x"),
                l(LineKind::Del, Some(13), None, "y"),
                l(LineKind::Add, None, Some(13), "Y"),
                l(LineKind::Ctx, Some(14), Some(14), "z"),
            ]
        );
        let d = fallback_diff("write_file", r#"{"path":"n","content":"a\nb\n"}"#, None);
        assert_eq!(diff_stats(d.as_ref()), (2, 0));
        assert_eq!(d.unwrap().hunks[0][1], l(LineKind::Add, None, Some(2), "b"));
        assert!(fallback_diff("read_file", r#"{"path":"a"}"#, None).is_none());
    }

    #[test]
    fn step_diff_prefers_the_server() {
        let mut s = ToolStep::new("a", "edit_file", r#"{"old_str":"a","new_str":"b"}"#, Status::Ok);
        s.content = Some("edited".into());
        s.diff = Some("@@ -1,1 +1,2 @@\n-a\n+b\n+c\n".into());
        assert_eq!(diff_stats(step_diff(&s).as_ref()), (2, 1));
        s.status = Status::Error;
        assert!(step_diff(&s).is_none());
        s.status = Status::Running;
        assert!(step_diff(&s).is_none());
        let e = ToolStep::new("b", "edit_file", r#"{"old_str":"a\nb","new_str":"c"}"#, Status::Running);
        assert_eq!(line_delta(&e), (1, 2));
    }

    #[test]
    fn describes_changes() {
        assert_eq!(describe_change((18, 0)), "Added 18 lines");
        assert_eq!(describe_change((1, 2)), "Added 1 line, removed 2 lines");
        assert_eq!(describe_change((0, 1)), "Removed 1 line");
    }
}
