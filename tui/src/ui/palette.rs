//! One place to find anything (Ctrl+K): chats, commands, models, effort, provider, mode and actions, matched
//! as you type. Ctrl+R opens it on the prompt history instead.

use super::text::{fit, width};
use super::theme;
use crate::state::Mode;
use ratatui::text::{Line, Span};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    NewChat,
    Open(String),
    /// Puts `/name ` in the composer.
    Command(String),
    Model(String),
    Effort(String),
    Provider(String),
    Mode(Mode),
    Delete(String),
    Overview,
    Trace,
    AddProject(String),
    RemoveProject(String, String),
    NewChatIn(String),
    Continue,
    CopyLast,
    Help,
    /// A prompt from the history, put back in the composer.
    Recall(String),
}

#[derive(Debug, Clone)]
pub struct Item {
    /// The section it belongs to: "Chats", "Models", ...
    pub section: &'static str,
    pub label: String,
    pub detail: String,
    /// Drawn in the warning colour (a chat that needs you, a destructive action).
    pub alert: bool,
    pub action: Action,
}

impl Item {
    pub fn new(section: &'static str, label: impl Into<String>, detail: impl Into<String>, action: Action) -> Self {
        Item { section, label: label.into(), detail: detail.into(), alert: false, action }
    }

    pub fn alert(mut self) -> Self {
        self.alert = true;
        self
    }
}

pub struct Palette {
    pub query: String,
    pub items: Vec<Item>,
    pub selected: usize,
    pub title: &'static str,
}

/// How well `query` matches `text`: its letters in order, better when they are together and start words.
pub fn score(query: &str, text: &str) -> Option<i64> {
    if query.is_empty() {
        return Some(0);
    }
    let t: Vec<char> = text.to_lowercase().chars().collect();
    let mut s = 0i64;
    let mut ti = 0;
    let mut last: Option<usize> = None;
    for q in query.to_lowercase().chars().filter(|c| !c.is_whitespace()) {
        let found = (ti..t.len()).find(|&i| t[i] == q)?;
        s += 1;
        if last.is_some_and(|l| l + 1 == found) {
            s += 4;
        }
        if found == 0 || !t[found - 1].is_alphanumeric() {
            s += 3;
        }
        s -= (found - ti).min(10) as i64 / 3;
        last = Some(found);
        ti = found + 1;
    }
    Some(s)
}

impl Palette {
    pub fn new(title: &'static str, items: Vec<Item>) -> Self {
        Palette { query: String::new(), items, selected: 0, title }
    }

    /// The items matching the query, best first (in their sections' order when the query is empty).
    pub fn matches(&self) -> Vec<&Item> {
        if self.query.trim().is_empty() {
            return self.items.iter().collect();
        }
        let mut scored: Vec<(i64, usize, &Item)> = self
            .items
            .iter()
            .enumerate()
            .filter_map(|(i, it)| {
                let hay = format!("{} {} {}", it.label, it.detail, it.section);
                score(&self.query, &hay).map(|s| (s + score(&self.query, &it.label).unwrap_or(0), i, it))
            })
            .collect();
        scored.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        scored.into_iter().map(|(_, _, it)| it).collect()
    }

    pub fn chosen(&self) -> Option<Action> {
        self.matches().get(self.selected).map(|i| i.action.clone())
    }

    pub fn move_by(&mut self, d: isize) {
        let n = self.matches().len();
        if n == 0 {
            return;
        }
        self.selected = (self.selected as isize + d).rem_euclid(n as isize) as usize;
    }

    pub fn typed(&mut self) {
        self.selected = 0;
    }

    /// The list as lines `w` wide and `h` tall, scrolled to keep the selection in view.
    pub fn lines(&self, w: usize, h: usize) -> Vec<Line<'static>> {
        let list = self.matches();
        if list.is_empty() {
            return vec![Line::from(Span::styled("  Nothing matches.", theme::muted()))];
        }
        let show_sections = self.query.trim().is_empty();
        let mut rows: Vec<(Option<usize>, Line<'static>)> = vec![];
        let mut section = "";
        for (i, it) in list.iter().enumerate() {
            if show_sections && it.section != section {
                section = it.section;
                rows.push((None, Line::from(Span::styled(it.section.to_string(), theme::muted()))));
            }
            let here = i == self.selected;
            let label_style = if it.alert { theme::warn() } else { Default::default() };
            let label = fit(&it.label, w.saturating_sub(6).max(10) * 2 / 3);
            let detail = fit(&it.detail, w.saturating_sub(width(&label) + 6));
            let pad = w.saturating_sub(width(&label) + width(&detail) + 4);
            let mut line = Line::from(vec![
                Span::styled(if here { " › " } else { "   " }, theme::accent()),
                Span::styled(label, label_style),
                Span::raw(" ".repeat(pad)),
                Span::styled(detail, theme::muted()),
            ]);
            if here {
                line = line.patch_style(theme::selected());
            }
            rows.push((Some(i), line));
        }
        let at = rows.iter().position(|(i, _)| *i == Some(self.selected)).unwrap_or(0);
        let start = at.saturating_sub(h.saturating_sub(1)).min(rows.len().saturating_sub(h));
        let start = if at < h { 0 } else { start };
        rows.into_iter().skip(start).take(h).map(|(_, l)| l).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scores_letters_in_order() {
        assert!(score("nc", "New chat").is_some());
        assert!(score("xyz", "New chat").is_none());
        assert!(score("son", "sonnet-5.5").unwrap() > score("son", "sentence on").unwrap());
    }

    #[test]
    fn matches_best_first_and_moves() {
        let mut p = Palette::new(
            "Go to",
            vec![
                Item::new("Chats", "Fix the resume bug", "app · 5m", Action::Open("a".into())),
                Item::new("Models", "anthropic/claude-sonnet", "200k", Action::Model("s".into())),
                Item::new("Actions", "New chat", "Ctrl+N", Action::NewChat),
            ],
        );
        assert_eq!(p.matches().len(), 3);
        p.query = "sonnet".into();
        assert_eq!(p.chosen(), Some(Action::Model("s".into())));
        p.query = String::new();
        p.move_by(-1);
        assert_eq!(p.chosen(), Some(Action::NewChat));
    }
}
