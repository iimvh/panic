//! Colours: neutral greys carry the interface, one accent marks what you can act on, and green, amber and
//! red only ever mean status (a diff's added and removed lines included), as ui-v2's principles say.
//! Only the 16 ANSI colours are used, so the terminal's own palette (light or dark) decides how they look.

use ratatui::style::{Color, Modifier, Style};

pub const ACCENT: Color = Color::Cyan;
pub const OK: Color = Color::Green;
pub const WARN: Color = Color::Yellow;
pub const ERR: Color = Color::Red;
pub const MUTED: Color = Color::DarkGray;
pub const CODE: Color = Color::Magenta;

pub fn muted() -> Style {
    Style::default().fg(MUTED)
}

pub fn accent() -> Style {
    Style::default().fg(ACCENT)
}

pub fn bold() -> Style {
    Style::default().add_modifier(Modifier::BOLD)
}

pub fn ok() -> Style {
    Style::default().fg(OK)
}

pub fn warn() -> Style {
    Style::default().fg(WARN)
}

pub fn err() -> Style {
    Style::default().fg(ERR)
}

/// The row the keyboard is on.
pub fn selected() -> Style {
    Style::default().add_modifier(Modifier::REVERSED)
}

pub const SPINNER: [&str; 10] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];
