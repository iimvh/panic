//! A small Markdown parser for chat answers, after ui-v2's `markdown.ts` (same blocks, same rules), but
//! into blocks and styled spans for the terminal instead of HTML. Only http(s)/mailto links are links; an
//! unclosed fence (an answer still streaming) is code up to the end.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Style {
    pub bold: bool,
    pub italic: bool,
    pub strike: bool,
    pub code: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Span {
    pub text: String,
    pub style: Style,
    pub link: Option<String>,
}

pub type Line = Vec<Span>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Block {
    Heading(usize, Line),
    /// Lines of a paragraph: single newlines are kept as line breaks.
    Para(Vec<Line>),
    List {
        ordered: bool,
        start: u64,
        items: Vec<Line>,
    },
    Quote(Vec<Block>),
    Rule,
    Table {
        head: Vec<Line>,
        rows: Vec<Vec<Line>>,
    },
    Code {
        lang: String,
        code: String,
    },
}

fn plain(text: &str, style: Style) -> Span {
    Span { text: text.into(), style, link: None }
}

fn safe_url(url: &str) -> bool {
    let u = url.trim().to_ascii_lowercase();
    u.starts_with("http://") || u.starts_with("https://") || u.starts_with("mailto:")
}

/// Inline markup on one line of raw text.
pub fn inline(raw: &str) -> Line {
    let mut out = vec![];
    let mut text = String::new();
    let b: Vec<char> = raw.chars().collect();
    let mut i = 0;
    let flush = |text: &mut String, out: &mut Line| {
        if !text.is_empty() {
            out.extend(emphasis(text, Style::default()));
            text.clear();
        }
    };
    while i < b.len() {
        if b[i] == '`'
            && let Some(end) = (i + 1..b.len()).find(|&j| b[j] == '`')
            && end > i + 1
        {
            flush(&mut text, &mut out);
            out.push(plain(&b[i + 1..end].iter().collect::<String>(), Style { code: true, ..Default::default() }));
            i = end + 1;
            continue;
        }
        if b[i] == '['
            && let Some(link) = parse_link(&b, i)
        {
            let (label, url, end) = link;
            if safe_url(&url) {
                flush(&mut text, &mut out);
                out.push(Span { text: label, style: Style::default(), link: Some(url.trim().into()) });
                i = end;
                continue;
            }
        }
        text.push(b[i]);
        i += 1;
    }
    flush(&mut text, &mut out);
    merge(out)
}

/// `[text](url)` at `i`: its label, url and the index after it.
fn parse_link(b: &[char], i: usize) -> Option<(String, String, usize)> {
    let close = (i + 1..b.len()).find(|&j| b[j] == ']')?;
    if close == i + 1 || b.get(close + 1) != Some(&'(') {
        return None;
    }
    let end = (close + 2..b.len()).find(|&j| b[j] == ')')?;
    let url: String = b[close + 2..end].iter().collect();
    if url.is_empty() || url.chars().any(char::is_whitespace) {
        return None;
    }
    Some((b[i + 1..close].iter().collect(), url, end + 1))
}

fn is_word(c: Option<char>) -> bool {
    c.is_some_and(|c| c.is_alphanumeric() || c == '_')
}

/// Bold (`**`, `__`), italic (`*`, `_`) and strikethrough (`~~`), nested, as the web UI's rules read them.
fn emphasis(text: &str, style: Style) -> Line {
    let b: Vec<char> = text.chars().collect();
    let mut out = vec![];
    let mut start = 0;
    let mut i = 0;
    while i < b.len() {
        if let Some((marker, close, inner_style)) = opener(&b, i, style) {
            if start < i {
                out.push(plain(&b[start..i].iter().collect::<String>(), style));
            }
            let inner: String = b[i + marker..close].iter().collect();
            out.extend(emphasis(&inner, inner_style));
            i = close + marker;
            start = i;
            continue;
        }
        i += 1;
    }
    if start < b.len() {
        out.push(plain(&b[start..].iter().collect::<String>(), style));
    }
    out
}

/// A marker opening at `i` with a valid closer: its length, where it closes, and the style inside.
fn opener(b: &[char], i: usize, style: Style) -> Option<(usize, usize, Style)> {
    let at = |k: usize| b.get(k).copied();
    let starts = |s: &str| s.chars().enumerate().all(|(k, c)| at(i + k) == Some(c));
    for (m, kind) in [("**", 'b'), ("__", 'b'), ("~~", 's')] {
        if starts(m) && at(i + 2).is_some_and(|c| !c.is_whitespace()) {
            let mc: Vec<char> = m.chars().collect();
            let close = (i + 3..b.len().saturating_sub(1)).find(|&j| b[j] == mc[0] && b[j + 1] == mc[1] && !b[j - 1].is_whitespace())?;
            let mut st = style;
            if kind == 'b' {
                st.bold = true;
            } else {
                st.strike = true;
            }
            return Some((2, close, st));
        }
    }
    let c = at(i)?;
    if (c == '*' || c == '_') && at(i + 1).is_some_and(|n| !n.is_whitespace() && n != c) {
        let before = if i == 0 { None } else { at(i - 1) };
        let open_ok = if c == '*' { !(before == Some('*') || is_word(before)) } else { !(before == Some('_') || is_word(before)) };
        if !open_ok {
            return None;
        }
        let close = (i + 2..b.len())
            .find(|&j| b[j] == c && !b[j - 1].is_whitespace() && if c == '*' { at(j + 1) != Some('*') } else { !is_word(at(j + 1)) })?;
        if b[i + 1..close].contains(&c) {
            return None;
        }
        let mut st = style;
        st.italic = true;
        return Some((1, close, st));
    }
    None
}

fn merge(spans: Line) -> Line {
    let mut out: Line = vec![];
    for s in spans {
        match out.last_mut() {
            Some(l) if l.style == s.style && l.link.is_none() && s.link.is_none() => l.text.push_str(&s.text),
            _ => out.push(s),
        }
    }
    out
}

fn list_item(line: &str) -> Option<(bool, u64, String)> {
    let t = line.trim_start();
    let (marker, rest) = t.split_once(char::is_whitespace)?;
    let rest = rest.trim_start();
    if rest.is_empty() && !t.ends_with(' ') {
        return None;
    }
    if matches!(marker, "-" | "*" | "+") {
        return Some((false, 1, rest.into()));
    }
    let digits = marker.strip_suffix('.').or_else(|| marker.strip_suffix(')'))?;
    if !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit()) {
        return Some((true, digits.parse().unwrap_or(1), rest.into()));
    }
    None
}

fn heading(line: &str) -> Option<(usize, &str)> {
    let level = line.bytes().take_while(|&b| b == b'#').count();
    if !(1..=6).contains(&level) {
        return None;
    }
    let rest = &line[level..];
    if !rest.starts_with(char::is_whitespace) {
        return None;
    }
    let text = rest.trim();
    // a closing run of #s goes
    let text = match text.rfind(|c: char| c != '#') {
        Some(k) if text[k + 1..].starts_with('#') && text[..=k].ends_with(char::is_whitespace) => text[..=k].trim_end(),
        _ => text,
    };
    Some((level, text))
}

fn is_rule(line: &str) -> bool {
    let t: String = line.chars().filter(|c| !c.is_whitespace()).collect();
    t.len() >= 3 && (t.chars().all(|c| c == '-') || t.chars().all(|c| c == '*') || t.chars().all(|c| c == '_'))
}

fn is_quote(line: &str) -> bool {
    line.trim_start().starts_with('>')
}

fn is_table_row(line: &str) -> bool {
    let t = line.trim();
    t.len() >= 2 && t.starts_with('|') && t.ends_with('|')
}

fn is_table_sep(line: &str) -> bool {
    let t = line.trim_start();
    let t = t.strip_prefix('|').unwrap_or(t).trim_start();
    let t = t.strip_prefix(':').unwrap_or(t);
    t.starts_with("---")
}

fn cells(line: &str) -> Vec<Line> {
    let t = line.trim();
    let t = t.strip_prefix('|').unwrap_or(t);
    let t = t.strip_suffix('|').unwrap_or(t);
    t.split('|').map(|c| inline(c.trim())).collect()
}

fn blocks(text: &str) -> Vec<Block> {
    let lines: Vec<&str> = text.split('\n').collect();
    let mut out = vec![];
    let mut i = 0;
    while i < lines.len() {
        let line = lines[i];
        if line.trim().is_empty() {
            i += 1;
            continue;
        }
        if let Some((level, t)) = heading(line) {
            out.push(Block::Heading(level, inline(t)));
            i += 1;
            continue;
        }
        if is_rule(line) {
            out.push(Block::Rule);
            i += 1;
            continue;
        }
        if is_quote(line) {
            let mut quoted = vec![];
            while i < lines.len() && is_quote(lines[i]) {
                let t = lines[i].trim_start().strip_prefix('>').unwrap_or("");
                quoted.push(t.strip_prefix(' ').unwrap_or(t));
                i += 1;
            }
            out.push(Block::Quote(blocks(&quoted.join("\n"))));
            continue;
        }
        if is_table_row(line) && i + 1 < lines.len() && is_table_sep(lines[i + 1]) {
            let head = cells(line);
            i += 2;
            let mut rows = vec![];
            while i < lines.len() && is_table_row(lines[i]) {
                rows.push(cells(lines[i]));
                i += 1;
            }
            out.push(Block::Table { head, rows });
            continue;
        }
        if let Some((ordered, start, _)) = list_item(line) {
            let mut items: Vec<String> = vec![];
            while i < lines.len() {
                let m = list_item(lines[i]);
                if let Some((o, _, t)) = m.as_ref().filter(|m| m.0 == ordered) {
                    let _ = o;
                    items.push(t.clone());
                    i += 1;
                } else if !lines[i].trim().is_empty() && lines[i].starts_with("  ") && !items.is_empty() {
                    let last = items.last_mut().unwrap();
                    last.push(' ');
                    last.push_str(lines[i].trim());
                    i += 1;
                } else if lines[i].trim().is_empty() && !items.is_empty() {
                    let mut j = i;
                    while j < lines.len() && lines[j].trim().is_empty() {
                        j += 1;
                    }
                    let next_same = j < lines.len() && list_item(lines[j]).is_some_and(|n| n.0 == ordered);
                    let indented = j < lines.len() && lines[j].starts_with("  ") && !lines[j].trim().is_empty();
                    if next_same || indented {
                        i = j;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            out.push(Block::List { ordered, start: if ordered { start } else { 1 }, items: items.iter().map(|t| inline(t)).collect() });
            continue;
        }
        let mut para = vec![];
        while i < lines.len()
            && !lines[i].trim().is_empty()
            && heading(lines[i]).is_none()
            && !is_quote(lines[i])
            && list_item(lines[i]).is_none()
        {
            para.push(inline(lines[i]));
            i += 1;
        }
        if para.is_empty() {
            // a line no block takes (e.g. "#hashtag" under a rule check): keep it as text
            para.push(inline(lines[i]));
            i += 1;
        }
        out.push(Block::Para(para));
    }
    out
}

fn fence(line: &str) -> Option<(&'static str, String)> {
    let indent = line.len() - line.trim_start_matches(' ').len();
    if indent > 3 {
        return None;
    }
    let t = &line[indent..];
    let marker = if t.starts_with("```") {
        "```"
    } else if t.starts_with("~~~") {
        "~~~"
    } else {
        return None;
    };
    let lang: String = t[3..].trim_start().chars().take_while(|c| c.is_alphanumeric() || "_+#.-".contains(*c)).collect();
    Some((marker, lang))
}

pub fn parse(src: &str) -> Vec<Block> {
    let src = src.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = src.split('\n').collect();
    let mut out = vec![];
    let mut text: Vec<&str> = vec![];
    let mut i = 0;
    while i < lines.len() {
        let Some((marker, lang)) = fence(lines[i]) else {
            text.push(lines[i]);
            i += 1;
            continue;
        };
        out.extend(blocks(&text.join("\n")));
        text.clear();
        let mut code = vec![];
        i += 1;
        while i < lines.len() {
            let l = lines[i];
            let indent = l.len() - l.trim_start_matches(' ').len();
            if indent <= 3 && l[indent..].starts_with(marker) && l[indent + 3..].trim().is_empty() {
                break;
            }
            code.push(l);
            i += 1;
        }
        i += 1; // closing fence (or end of a still-streaming answer)
        out.push(Block::Code { lang, code: code.join("\n") });
    }
    out.extend(blocks(&text.join("\n")));
    out
}

/// The text of every code block, for copying.
pub fn code_blocks(blocks: &[Block]) -> Vec<String> {
    blocks.iter().filter_map(|b| if let Block::Code { code, .. } = b { Some(code.clone()) } else { None }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(s: &str) -> Span {
        plain(s, Style::default())
    }
    fn st(s: &str, f: impl Fn(&mut Style)) -> Span {
        let mut style = Style::default();
        f(&mut style);
        plain(s, style)
    }

    #[test]
    fn keeps_only_safe_links() {
        assert_eq!(
            inline("[ok](https://a.b/c) [bad](javascript:alert(1))"),
            vec![Span { text: "ok".into(), style: Style::default(), link: Some("https://a.b/c".into()) }, t(" [bad](javascript:alert(1))")]
        );
    }

    #[test]
    fn leaves_inline_code_alone() {
        assert_eq!(
            inline("use `**x**` and **y**"),
            vec![t("use "), st("**x**", |s| s.code = true), t(" and "), st("y", |s| s.bold = true)]
        );
    }

    #[test]
    fn emphasis_rules() {
        assert_eq!(
            inline("*a* _b_ ~~c~~"),
            vec![st("a", |s| s.italic = true), t(" "), st("b", |s| s.italic = true), t(" "), st("c", |s| s.strike = true)]
        );
        assert_eq!(inline("snake_case_name and 2*3*4"), vec![t("snake_case_name and 2*3*4")]);
        assert_eq!(
            inline("**bold _both_**"),
            vec![
                st("bold ", |s| s.bold = true),
                st("both", |s| {
                    s.bold = true;
                    s.italic = true
                })
            ]
        );
    }

    #[test]
    fn renders_blocks() {
        let b = parse("# Title\n\n- a\n- b\n\n1. one\n2. two\n\n> quote\n\ntext\nmore");
        assert_eq!(
            b,
            vec![
                Block::Heading(1, vec![t("Title")]),
                Block::List { ordered: false, start: 1, items: vec![vec![t("a")], vec![t("b")]] },
                Block::List { ordered: true, start: 1, items: vec![vec![t("one")], vec![t("two")]] },
                Block::Quote(vec![Block::Para(vec![vec![t("quote")]])]),
                Block::Para(vec![vec![t("text")], vec![t("more")]]),
            ]
        );
    }

    #[test]
    fn loose_and_numbered_lists() {
        let ol = |start, items: &[&str]| Block::List { ordered: true, start, items: items.iter().map(|s| vec![t(s)]).collect() };
        assert_eq!(parse("1. one\n\n2. two\n\n3. three"), vec![ol(1, &["one", "two", "three"])]);
        assert_eq!(parse("1. one\n\n   more about one\n\n2. two"), vec![ol(1, &["one more about one", "two"])]);
        assert_eq!(parse("3. three\n4. four"), vec![ol(3, &["three", "four"])]);
        assert_eq!(parse("1. one\n\ntext\n\n2. two"), vec![ol(1, &["one"]), Block::Para(vec![vec![t("text")]]), ol(2, &["two"])]);
        assert_eq!(
            parse("- a\n\n- b\n\ntext"),
            vec![Block::List { ordered: false, start: 1, items: vec![vec![t("a")], vec![t("b")]] }, Block::Para(vec![vec![t("text")]])]
        );
    }

    #[test]
    fn fenced_code_also_while_streaming() {
        assert_eq!(parse("```js\nlet a = 1;\n```"), vec![Block::Code { lang: "js".into(), code: "let a = 1;".into() }]);
        assert_eq!(
            parse("see:\n```py\nprint(1)"),
            vec![Block::Para(vec![vec![t("see:")]]), Block::Code { lang: "py".into(), code: "print(1)".into() }]
        );
    }

    #[test]
    fn tables_and_rules() {
        assert_eq!(
            parse("| a | b |\n|---|---|\n| 1 | **2** |\n\n---"),
            vec![
                Block::Table { head: vec![vec![t("a")], vec![t("b")]], rows: vec![vec![vec![t("1")], vec![st("2", |s| s.bold = true)]]] },
                Block::Rule
            ]
        );
    }
}
