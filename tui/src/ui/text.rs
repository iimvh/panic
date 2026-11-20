//! Wrapping styled text to the terminal's width, and Markdown blocks to lines.

use super::theme;
use crate::core::markdown::{self, Block};
use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};
use unicode_width::UnicodeWidthChar;

/// The width of `s` in terminal cells.
pub fn width(s: &str) -> usize {
    s.chars().map(|c| c.width().unwrap_or(0)).sum()
}

/// `s` cut to `w` cells, the last one an ellipsis when cut.
pub fn fit(s: &str, w: usize) -> String {
    if width(s) <= w {
        return s.to_string();
    }
    let mut out = String::new();
    let mut used = 0;
    for c in s.chars() {
        let cw = c.width().unwrap_or(0);
        if used + cw + 1 > w {
            break;
        }
        out.push(c);
        used += cw;
    }
    out.push('…');
    out
}

/// Wraps styled pieces to `w` cells, breaking at spaces when it can. Each line starts with `first` (the
/// first) or `rest` (the others), so a bullet or an indent stays in front.
pub fn wrap(pieces: &[(String, Style)], w: usize, first: Span<'static>, rest: Span<'static>) -> Vec<Line<'static>> {
    let indent = width(&first.content).max(width(&rest.content));
    let avail = w.saturating_sub(indent).max(8);
    let mut lines: Vec<Vec<(String, Style)>> = vec![vec![]];
    let mut used = 0;
    for (text, style) in pieces {
        for (k, segment) in text.split('\n').enumerate() {
            if k > 0 {
                lines.push(vec![]);
                used = 0;
            }
            // words keep their following spaces, so a break never starts a line with a space
            let mut word = String::new();
            let flush = |word: &mut String, lines: &mut Vec<Vec<(String, Style)>>, used: &mut usize| {
                if word.is_empty() {
                    return;
                }
                let ww = width(word.trim_end());
                if *used > 0 && *used + ww > avail {
                    lines.push(vec![]);
                    *used = 0;
                }
                // a word longer than a line is cut where the line ends
                let mut chunk = String::new();
                let mut cw = *used;
                for c in word.chars() {
                    let x = c.width().unwrap_or(0);
                    if cw + x > avail && !chunk.is_empty() && !c.is_whitespace() {
                        lines.last_mut().unwrap().push((std::mem::take(&mut chunk), *style));
                        lines.push(vec![]);
                        cw = 0;
                    }
                    chunk.push(c);
                    cw += x;
                }
                lines.last_mut().unwrap().push((chunk, *style));
                *used = cw;
                word.clear();
            };
            for c in segment.chars() {
                word.push(c);
                if c == ' ' {
                    flush(&mut word, &mut lines, &mut used);
                }
            }
            flush(&mut word, &mut lines, &mut used);
        }
    }
    lines
        .into_iter()
        .enumerate()
        .map(|(i, pieces)| {
            let mut spans = vec![if i == 0 { first.clone() } else { rest.clone() }];
            spans.extend(pieces.into_iter().map(|(t, s)| Span::styled(t, s)));
            Line::from(spans)
        })
        .collect()
}

fn span_style(s: &markdown::Span, base: Style) -> Style {
    let mut st = base;
    if s.style.bold {
        st = st.add_modifier(Modifier::BOLD);
    }
    if s.style.italic {
        st = st.add_modifier(Modifier::ITALIC);
    }
    if s.style.strike {
        st = st.add_modifier(Modifier::CROSSED_OUT);
    }
    if s.style.code {
        st = st.fg(theme::CODE);
    }
    if s.link.is_some() {
        st = st.fg(theme::ACCENT).add_modifier(Modifier::UNDERLINED);
    }
    st
}

fn pieces(line: &markdown::Line, base: Style) -> Vec<(String, Style)> {
    let mut out = vec![];
    for s in line {
        out.push((s.text.clone(), span_style(s, base)));
        if let Some(url) = &s.link
            && url != &s.text
        {
            out.push((format!(" ({url})"), theme::muted()));
        }
    }
    out
}

/// Markdown as lines `w` cells wide, each starting with `prefix` (the first) or `indent`.
pub fn markdown(src: &str, w: usize, prefix: Span<'static>, indent: Span<'static>) -> Vec<Line<'static>> {
    let blocks = markdown::parse(src);
    let mut out = vec![];
    let mut first = true;
    let lead = |first: &mut bool| {
        let p = if *first { prefix.clone() } else { indent.clone() };
        *first = false;
        p
    };
    for (i, b) in blocks.iter().enumerate() {
        if i > 0 {
            out.push(Line::from(indent.clone()));
        }
        render_block(b, w, &mut out, &mut || lead(&mut first), &indent);
    }
    out
}

fn render_block(b: &Block, w: usize, out: &mut Vec<Line<'static>>, lead: &mut dyn FnMut() -> Span<'static>, indent: &Span<'static>) {
    let plain = Style::default();
    match b {
        Block::Heading(_, line) => {
            out.extend(wrap(&pieces(line, plain.add_modifier(Modifier::BOLD)), w, lead(), indent.clone()));
        }
        Block::Para(lines) => {
            for l in lines {
                out.extend(wrap(&pieces(l, plain), w, lead(), indent.clone()));
            }
        }
        Block::List { ordered, start, items } => {
            for (k, item) in items.iter().enumerate() {
                let marker = if *ordered { format!("{}. ", start + k as u64) } else { "• ".into() };
                let mark_w = width(&marker);
                let l = lead();
                let first = Span::styled(format!("{}{marker}", l.content), l.style);
                let rest = Span::raw(format!("{}{}", indent.content, " ".repeat(mark_w)));
                out.extend(wrap(&pieces(item, plain), w, first, rest));
            }
        }
        Block::Quote(inner) => {
            let bar = Span::styled(format!("{}│ ", indent.content), theme::muted());
            let mut first_line = true;
            let mut sub = vec![];
            for (i, q) in inner.iter().enumerate() {
                if i > 0 {
                    sub.push(Line::from(bar.clone()));
                }
                let mut l = || bar.clone();
                render_block(q, w, &mut sub, &mut l, &bar);
            }
            for line in sub {
                if first_line {
                    let _ = lead();
                    first_line = false;
                }
                out.push(line.patch_style(theme::muted()));
            }
        }
        Block::Rule => {
            let l = lead();
            out.push(Line::from(vec![l, Span::styled("─".repeat(w.saturating_sub(4).min(40)), theme::muted())]));
        }
        Block::Table { head, rows } => {
            let cols = head.len().max(rows.iter().map(Vec::len).max().unwrap_or(0));
            let text = |l: &markdown::Line| l.iter().map(|s| s.text.as_str()).collect::<String>();
            let mut widths = vec![0; cols];
            for r in std::iter::once(head).chain(rows.iter()) {
                for (i, c) in r.iter().enumerate() {
                    widths[i] = widths[i].max(width(&text(c)));
                }
            }
            let avail = w.saturating_sub(width(&indent.content) + 3 * cols);
            let total: usize = widths.iter().sum();
            if total > avail && total > 0 {
                for x in &mut widths {
                    *x = (*x * avail / total).max(3);
                }
            }
            let row = |cells: &Vec<markdown::Line>, style: Style, lead: Span<'static>| {
                let mut spans = vec![lead];
                for (i, wd) in widths.iter().enumerate() {
                    let t = cells.get(i).map(text).unwrap_or_default();
                    let t = fit(&t, *wd);
                    let pad = wd.saturating_sub(width(&t));
                    spans.push(Span::styled(format!("{t}{}", " ".repeat(pad)), style));
                    if i + 1 < cols {
                        spans.push(Span::styled(" │ ", theme::muted()));
                    }
                }
                Line::from(spans)
            };
            out.push(row(head, plain.add_modifier(Modifier::BOLD), lead()));
            let sep: Vec<String> = widths.iter().map(|w| "─".repeat(*w)).collect();
            out.push(Line::from(vec![indent.clone(), Span::styled(sep.join("─┼─"), theme::muted())]));
            for r in rows {
                out.push(row(r, plain, indent.clone()));
            }
        }
        Block::Code { lang, code } => {
            let l = lead();
            let label = if lang.is_empty() { "code".to_string() } else { lang.clone() };
            out.push(Line::from(vec![l, Span::styled(format!("┌ {label}"), theme::muted())]));
            for line in code.split('\n') {
                let bar = Span::styled(format!("{}│ ", indent.content), theme::muted());
                out.extend(wrap(&[(line.to_string(), Style::default().fg(theme::CODE))], w, bar.clone(), bar));
            }
            out.push(Line::from(vec![indent.clone(), Span::styled("└", theme::muted())]));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(lines: &[Line]) -> Vec<String> {
        lines.iter().map(|l| l.spans.iter().map(|s| s.content.as_ref()).collect()).collect()
    }

    #[test]
    fn wraps_at_spaces_with_prefixes() {
        let l = wrap(&[("one two three four".into(), Style::default())], 11, Span::raw("> "), Span::raw("  "));
        assert_eq!(text(&l), vec!["> one two ", "  three ", "  four"]);
        let l = wrap(&[("a\nb".into(), Style::default())], 20, Span::raw("* "), Span::raw("  "));
        assert_eq!(text(&l), vec!["* a", "  b"]);
    }

    #[test]
    fn cuts_long_words_and_fits() {
        let l = wrap(&[("abcdefghijklmnopqrstuvwxyz".into(), Style::default())], 10, Span::raw(""), Span::raw(""));
        assert_eq!(text(&l), vec!["abcdefghij", "klmnopqrst", "uvwxyz"]);
        assert_eq!(fit("hello world", 6), "hello…");
        assert_eq!(width("日本"), 4);
    }

    #[test]
    fn renders_markdown_blocks() {
        let l = markdown("# Hi\n\n- a\n- b\n\n```rs\nx\n```", 40, Span::raw("● "), Span::raw("  "));
        assert_eq!(text(&l), vec!["● Hi", "  ", "  • a", "  • b", "  ", "  ┌ rs", "  │ x", "  └"]);
    }
}
