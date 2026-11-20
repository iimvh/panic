//! The text the user is writing: a multi-line editor with the shell's keys and a prompt history.

use super::text::width;
use std::fs;
use std::path::PathBuf;
use unicode_width::UnicodeWidthChar;

#[derive(Default)]
pub struct Composer {
    pub text: String,
    /// Byte index of the cursor in `text`.
    pub cursor: usize,
    history: Vec<String>,
    /// Where ↑/↓ is in the history; None while editing a new prompt.
    browsing: Option<usize>,
    draft: String,
    file: Option<PathBuf>,
}

/// The history file: `$XDG_STATE_HOME/panic-tui/history`, else `~/.local/state/panic-tui/history`.
fn history_file() -> Option<PathBuf> {
    let base = std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/state")))?;
    Some(base.join("panic-tui").join("history"))
}

const KEEP: usize = 500;

impl Composer {
    /// A composer whose history is read from and saved to the history file.
    pub fn with_history() -> Self {
        let file = history_file();
        let history = file
            .as_ref()
            .and_then(|f| fs::read_to_string(f).ok())
            .map(|t| t.split('\u{0}').filter(|s| !s.is_empty()).map(str::to_string).collect())
            .unwrap_or_default();
        Composer { history, file, ..Default::default() }
    }

    pub fn is_empty(&self) -> bool {
        self.text.is_empty()
    }

    pub fn set(&mut self, text: &str) {
        self.text = text.to_string();
        self.cursor = self.text.len();
        self.browsing = None;
    }

    /// Takes the text out (to send it) and remembers it in the history.
    pub fn take(&mut self) -> String {
        let t = std::mem::take(&mut self.text);
        self.cursor = 0;
        self.browsing = None;
        let trimmed = t.trim();
        if !trimmed.is_empty() && self.history.last().map(String::as_str) != Some(trimmed) {
            self.history.push(trimmed.to_string());
            if self.history.len() > KEEP {
                self.history.drain(..self.history.len() - KEEP);
            }
            self.save();
        }
        t
    }

    fn save(&self) {
        let Some(f) = &self.file else { return };
        if let Some(dir) = f.parent() {
            let _ = fs::create_dir_all(dir);
        }
        let _ = fs::write(f, self.history.join("\u{0}"));
    }

    pub fn history(&self) -> &[String] {
        &self.history
    }

    pub fn insert(&mut self, c: char) {
        self.text.insert(self.cursor, c);
        self.cursor += c.len_utf8();
    }

    pub fn insert_str(&mut self, s: &str) {
        let s = s.replace("\r\n", "\n").replace('\r', "\n");
        self.text.insert_str(self.cursor, &s);
        self.cursor += s.len();
    }

    fn prev(&self) -> usize {
        self.text[..self.cursor].char_indices().next_back().map(|(i, _)| i).unwrap_or(0)
    }

    fn next(&self) -> usize {
        self.text[self.cursor..].chars().next().map(|c| self.cursor + c.len_utf8()).unwrap_or(self.cursor)
    }

    pub fn backspace(&mut self) {
        if self.cursor > 0 {
            let p = self.prev();
            self.text.replace_range(p..self.cursor, "");
            self.cursor = p;
        }
    }

    pub fn delete(&mut self) {
        if self.cursor < self.text.len() {
            let n = self.next();
            self.text.replace_range(self.cursor..n, "");
        }
    }

    pub fn left(&mut self) {
        self.cursor = self.prev();
    }

    pub fn right(&mut self) {
        self.cursor = self.next();
    }

    fn line_start(&self) -> usize {
        self.text[..self.cursor].rfind('\n').map(|i| i + 1).unwrap_or(0)
    }

    fn line_end(&self) -> usize {
        self.text[self.cursor..].find('\n').map(|i| self.cursor + i).unwrap_or(self.text.len())
    }

    pub fn home(&mut self) {
        self.cursor = self.line_start();
    }

    pub fn end(&mut self) {
        self.cursor = self.line_end();
    }

    /// Ctrl+U: deletes back to the start of the line.
    pub fn kill_line(&mut self) {
        let s = self.line_start();
        self.text.replace_range(s..self.cursor, "");
        self.cursor = s;
    }

    /// Ctrl+W: deletes the word before the cursor.
    pub fn kill_word(&mut self) {
        let before = &self.text[..self.cursor];
        let trimmed = before.trim_end();
        let start = trimmed.rfind(char::is_whitespace).map(|i| i + 1).unwrap_or(0);
        self.text.replace_range(start..self.cursor, "");
        self.cursor = start;
    }

    pub fn on_first_line(&self) -> bool {
        !self.text[..self.cursor].contains('\n')
    }

    pub fn on_last_line(&self) -> bool {
        !self.text[self.cursor..].contains('\n')
    }

    /// Moves up a line; false when already on the first one.
    pub fn up(&mut self) -> bool {
        if self.on_first_line() {
            return false;
        }
        let col = self.text[self.line_start()..self.cursor].chars().count();
        let start = self.line_start();
        let prev_start = self.text[..start - 1].rfind('\n').map(|i| i + 1).unwrap_or(0);
        self.cursor = Self::at_col(&self.text, prev_start, start - 1, col);
        true
    }

    pub fn down(&mut self) -> bool {
        if self.on_last_line() {
            return false;
        }
        let col = self.text[self.line_start()..self.cursor].chars().count();
        let next_start = self.line_end() + 1;
        let next_end = self.text[next_start..].find('\n').map(|i| next_start + i).unwrap_or(self.text.len());
        self.cursor = Self::at_col(&self.text, next_start, next_end, col);
        true
    }

    fn at_col(text: &str, start: usize, end: usize, col: usize) -> usize {
        text[start..end].char_indices().nth(col).map(|(i, _)| start + i).unwrap_or(end)
    }

    /// An older prompt from the history; false when there is none.
    pub fn history_prev(&mut self) -> bool {
        if self.history.is_empty() {
            return false;
        }
        let i = match self.browsing {
            None => {
                self.draft = self.text.clone();
                self.history.len() - 1
            }
            Some(0) => return false,
            Some(i) => i - 1,
        };
        self.browsing = Some(i);
        self.text = self.history[i].clone();
        self.cursor = self.text.len();
        true
    }

    pub fn history_next(&mut self) -> bool {
        let Some(i) = self.browsing else { return false };
        if i + 1 < self.history.len() {
            self.browsing = Some(i + 1);
            self.text = self.history[i + 1].clone();
        } else {
            self.browsing = None;
            self.text = std::mem::take(&mut self.draft);
        }
        self.cursor = self.text.len();
        true
    }

    /// The text hard-wrapped to `w` cells, and the row and column of the cursor in it.
    pub fn layout(&self, w: usize) -> (Vec<String>, (usize, usize)) {
        let w = w.max(1);
        let mut rows = vec![String::new()];
        let mut col = 0;
        let mut cur = (0, 0);
        for (i, c) in self.text.char_indices() {
            if i == self.cursor {
                cur = (rows.len() - 1, col);
            }
            if c == '\n' {
                rows.push(String::new());
                col = 0;
                continue;
            }
            let cw = c.width().unwrap_or(0);
            if col + cw > w {
                rows.push(String::new());
                col = 0;
            }
            rows.last_mut().unwrap().push(c);
            col += cw;
        }
        if self.cursor >= self.text.len() {
            if col >= w {
                rows.push(String::new());
                col = 0;
            }
            cur = (rows.len() - 1, col);
        }
        (rows, cur)
    }

    /// The `/name` typed so far, while it is the whole text (the command suggestions show then).
    pub fn command_prefix(&self) -> Option<&str> {
        let t = self.text.strip_prefix('/')?;
        (!t.contains(char::is_whitespace)).then_some(t)
    }

    pub fn width(&self) -> usize {
        width(&self.text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn edits_like_a_shell() {
        let mut c = Composer::default();
        for ch in "héllo world".chars() {
            c.insert(ch);
        }
        c.kill_word();
        assert_eq!(c.text, "héllo ");
        c.backspace();
        c.left();
        c.left();
        c.delete();
        assert_eq!(c.text, "hélo");
        c.home();
        c.insert('>');
        assert_eq!(c.text, ">hélo");
        c.end();
        c.insert('\n');
        c.insert_str("two");
        assert!(c.up());
        assert_eq!(&c.text[c.cursor..], "lo\ntwo");
        assert!(c.down());
        assert!(!c.down());
        c.kill_line();
        assert_eq!(c.text, ">hélo\n");
    }

    #[test]
    fn browses_the_history() {
        let mut c = Composer::default();
        c.set("a");
        c.take();
        c.set("b");
        c.take();
        c.set("draft");
        assert!(c.history_prev());
        assert_eq!(c.text, "b");
        assert!(c.history_prev());
        assert_eq!(c.text, "a");
        assert!(!c.history_prev());
        assert!(c.history_next());
        assert!(c.history_next());
        assert_eq!(c.text, "draft");
    }

    #[test]
    fn lays_out_with_the_cursor() {
        let mut c = Composer::default();
        c.set("abcdef\nxy");
        assert_eq!(c.layout(4), (vec!["abcd".into(), "ef".into(), "xy".into()], (2, 2)));
        c.cursor = 1;
        assert_eq!(c.layout(4).1, (0, 1));
        c.set("/he");
        assert_eq!(c.command_prefix(), Some("he"));
        c.set("/help me");
        assert_eq!(c.command_prefix(), None);
    }
}
