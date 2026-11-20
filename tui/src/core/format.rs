//! Small formats shared by every view, ported from ui-v2 (`format.ts`, `reply.ts`, `summary.ts`) so the
//! terminal and the web page say the same words.

use serde_json::Value;

/// "1.2k" for counts the eye only needs to compare.
pub fn compact(n: u64) -> String {
    if n < 1000 {
        return n.to_string();
    }
    if n < 10_000 {
        return trim_zero(format!("{:.1}", n as f64 / 1000.0)) + "k";
    }
    if n < 1_000_000 {
        return format!("{}k", (n as f64 / 1000.0).round() as u64);
    }
    trim_zero(format!("{:.1}", n as f64 / 1_000_000.0)) + "M"
}

fn trim_zero(s: String) -> String {
    s.strip_suffix(".0").map(str::to_string).unwrap_or(s)
}

/// "4s", "1m 05s".
pub fn duration(ms: i64) -> String {
    let s = ms.max(0) / 1000;
    if s < 60 {
        return format!("{s}s");
    }
    format!("{}m {:02}s", s / 60, s % 60)
}

/// "812 B", "3.2 KB" (UTF-8 bytes).
pub fn size(text: &str) -> String {
    let n = text.len();
    if n < 1024 { format!("{n} B") } else { format!("{:.1} KB", n as f64 / 1024.0) }
}

/// The JSON object of a call's arguments; empty when they are not one.
pub fn args(json: &str) -> serde_json::Map<String, Value> {
    match serde_json::from_str::<Value>(json) {
        Ok(Value::Object(m)) => m,
        _ => serde_json::Map::new(),
    }
}

/// A shell command call (`{command, cwd?, background?}`) as the user reads it; None for other arguments.
pub fn shell_command(arguments: &str) -> Option<String> {
    let Ok(Value::Object(a)) = serde_json::from_str::<Value>(arguments) else {
        return None;
    };
    let command = a.get("command")?.as_str()?;
    let mut out = format!("$ {command}");
    if let Some(cwd) = a.get("cwd").and_then(Value::as_str).filter(|c| !c.is_empty()) {
        out.push_str(&format!("   (in {cwd})"));
    }
    if a.get("background") == Some(&Value::Bool(true)) {
        out.push_str("   [background]");
    }
    Some(out)
}

/// A short label of a call: a command, a file name, a server's tool, a sub-agent's name.
pub fn step_label(name: &str, arguments: &str) -> String {
    if let Some(command) = shell_command(arguments) {
        let cmd = command[2..].split("   ").next().unwrap_or("");
        return clip(cmd, 60);
    }
    let a = args(arguments);
    if let Some(path) = a.get("path").and_then(Value::as_str).filter(|p| !p.is_empty()) {
        return clip(base_name(path), 60);
    }
    if name.starts_with("mcp__") {
        let rest: Vec<&str> = name.split("__").skip(2).collect();
        let joined = rest.join("__");
        return if joined.is_empty() { name.to_string() } else { joined };
    }
    if name == "agent"
        && let Some(agent) = a.get("agent").and_then(Value::as_str)
    {
        return agent.to_string();
    }
    String::new()
}

/// "exit 1" from a failed command's output; "not run" or "failed" when it says no code.
pub fn failure(content: Option<&str>) -> String {
    let text = content.unwrap_or("");
    if let Some(code) = exit_code(text) {
        return format!("exit {code}");
    }
    if text == "not run" { "not run".into() } else { "failed".into() }
}

/// Matches `exit(?:ed)?(?: with)?(?: code| status)?[:= ]+(-?\d+)`, case-insensitively.
fn exit_code(text: &str) -> Option<String> {
    let lower = text.to_ascii_lowercase();
    let b = lower.as_bytes();
    let mut from = 0;
    while let Some(pos) = lower[from..].find("exit") {
        let start = from + pos;
        from = start + 4;
        let mut i = start + 4;
        let eat = |i: &mut usize, w: &str| {
            if lower[*i..].starts_with(w) {
                *i += w.len();
            }
        };
        eat(&mut i, "ed");
        eat(&mut i, " with");
        if lower[i..].starts_with(" code") {
            i += 5;
        } else if lower[i..].starts_with(" status") {
            i += 7;
        }
        let sep = i;
        while i < b.len() && matches!(b[i], b':' | b'=' | b' ') {
            i += 1;
        }
        if i == sep {
            continue;
        }
        let num = i;
        if i < b.len() && b[i] == b'-' {
            i += 1;
        }
        let digits = i;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        if i > digits {
            return Some(text[num..i].to_string());
        }
    }
    None
}

pub fn base_name(path: &str) -> &str {
    let trimmed = path.trim_end_matches(['/', '\\']);
    match trimmed.rsplit(['/', '\\']).next() {
        Some(b) if !b.is_empty() => b,
        _ => path,
    }
}

/// `text` cut to `n` characters, the last one an ellipsis.
pub fn clip(text: &str, n: usize) -> String {
    if text.chars().count() > n {
        let mut s: String = text.chars().take(n - 1).collect();
        s.push('…');
        s
    } else {
        text.to_string()
    }
}

/// `String(v ?? '')` of the web UI: a JSON value read as text.
pub fn text_of(v: Option<&Value>) -> String {
    match v {
        None | Some(Value::Null) => String::new(),
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        Some(other) => other.to_string(),
    }
}

/// `Number(v ?? 0)`: a JSON number, or 0.
pub fn num_of(v: Option<&Value>) -> f64 {
    match v {
        Some(Value::Number(n)) => n.as_f64().unwrap_or(0.0),
        Some(Value::String(s)) => s.trim().parse().unwrap_or(0.0),
        _ => 0.0,
    }
}

/// "just now", "5m", "3h", "yesterday", "4d", then "Nov 3": how long ago `at` was, at `now` (both ms).
pub fn ago(at: i64, now: i64) -> String {
    let s = ((now - at) / 1000).max(0);
    if s < 60 {
        return "just now".into();
    }
    if s < 3600 {
        return format!("{}m", s / 60);
    }
    if s < 86_400 {
        return format!("{}h", s / 3600);
    }
    let days = s / 86_400;
    if days == 1 {
        return "yesterday".into();
    }
    if days < 7 {
        return format!("{days}d");
    }
    let (_, month, day) = civil(at.div_euclid(86_400_000));
    const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    format!("{} {day}", MONTHS[(month - 1) as usize])
}

/// Year, month, day of a day count since 1970-01-01 (UTC), after Howard Hinnant's `civil_from_days`.
fn civil(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + i64::from(m <= 2), m, d)
}

/// "850µs", "12ms", "1.25s", "2m 05s".
pub fn ms(value: f64) -> String {
    if value < 1.0 {
        return format!("{}µs", (value * 1000.0).round() as i64);
    }
    if value < 1000.0 {
        return format!("{}ms", value.round() as i64);
    }
    if value < 60_000.0 {
        return format!("{:.2}s", value / 1000.0);
    }
    let s = (value / 1000.0).floor() as i64;
    format!("{}m {:02}s", s / 60, s % 60)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compact_shortens_large_counts() {
        assert_eq!([compact(999), compact(1200), compact(12_345), compact(2_500_000)], ["999", "1.2k", "12k", "2.5M"]);
        assert_eq!(compact(1000), "1k");
    }

    #[test]
    fn duration_reads_well() {
        assert_eq!(duration(4200), "4s");
        assert_eq!(duration(65000), "1m 05s");
    }

    #[test]
    fn size_counts_bytes() {
        assert_eq!(size("abc"), "3 B");
        assert_eq!(size(&"x".repeat(3277)), "3.2 KB");
    }

    #[test]
    fn shell_command_shows_an_exec_call() {
        assert_eq!(
            shell_command(r#"{"command":"npm run dev","cwd":"web","background":true}"#).as_deref(),
            Some("$ npm run dev   (in web)   [background]")
        );
        assert_eq!(shell_command(r#"{"command":"ls"}"#).as_deref(), Some("$ ls"));
        assert_eq!(shell_command(r#"{"path":"x"}"#), None);
        assert_eq!(shell_command("{oops"), None);
    }

    #[test]
    fn labels_shorten_calls() {
        assert_eq!(step_label("read_file", r#"{"path":"ui/src/lib/reply.ts"}"#), "reply.ts");
        assert_eq!(step_label("exec", r#"{"command":"npm test","cwd":"ui"}"#), "npm test");
        assert_eq!(step_label("mcp__github__search_code", "{}"), "search_code");
        assert_eq!(step_label("agent", r#"{"agent":"explore"}"#), "explore");
        assert_eq!(step_label("clock", "{}"), "");
    }

    #[test]
    fn failure_reads_exit_codes() {
        assert_eq!(failure(Some("Exited with code 2")), "exit 2");
        assert_eq!(failure(Some("exit status: -1")), "exit -1");
        assert_eq!(failure(Some("boom")), "failed");
        assert_eq!(failure(Some("not run")), "not run");
        assert_eq!(failure(None), "failed");
    }

    #[test]
    fn ago_gets_coarser() {
        let min = 60_000;
        let hour = 60 * min;
        let day = 24 * hour;
        let now = 1_794_571_200_000; // 2026-11-13T12:00Z
        assert_eq!(ago(now - 10_000, now), "just now");
        assert_eq!(ago(now + 5_000, now), "just now");
        assert_eq!(ago(now - 5 * min, now), "5m");
        assert_eq!(ago(now - 3 * hour, now), "3h");
        assert_eq!(ago(now - day - hour, now), "yesterday");
        assert_eq!(ago(now - 4 * day, now), "4d");
        assert_eq!(ago(now - 30 * day, now), "Oct 14");
    }

    #[test]
    fn ms_picks_a_unit() {
        assert_eq!(ms(0.85), "850µs");
        assert_eq!(ms(12.4), "12ms");
        assert_eq!(ms(1250.0), "1.25s");
        assert_eq!(ms(125_000.0), "2m 05s");
    }

    #[test]
    fn clip_and_base_name() {
        assert_eq!(clip("abcdef", 4), "abc…");
        assert_eq!(base_name("a/b/c.ts"), "c.ts");
        assert_eq!(base_name("C:\\x\\y\\"), "y");
    }
}
