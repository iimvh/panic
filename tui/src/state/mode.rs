//! How Panic runs tools in a chat, and the commands that switch it (the same order as ui-v2's ModeSelect).

use crate::api::Toggle;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    /// Tools ask before they write, run commands or use the network.
    Ask,
    /// Research read-only, then propose a plan to approve.
    Plan,
    /// Tools run without asking.
    Auto,
}

impl Mode {
    /// The chat's mode; None when neither switch exists (only Ask first is possible).
    pub fn of(plan: Option<Toggle>, bypass: Option<Toggle>) -> Option<Mode> {
        if plan.is_none() && bypass.is_none() {
            return None;
        }
        Some(if plan.is_some_and(|t| t.on) {
            Mode::Plan
        } else if bypass.is_some_and(|t| t.on) {
            Mode::Auto
        } else {
            Mode::Ask
        })
    }

    pub fn label(self) -> &'static str {
        match self {
            Mode::Ask => "Ask first",
            Mode::Plan => "Plan",
            Mode::Auto => "Auto-run",
        }
    }

    pub fn description(self) -> &'static str {
        match self {
            Mode::Ask => "Tools ask before they write, run commands or use the network",
            Mode::Plan => "Research read-only, then propose a plan to approve",
            Mode::Auto => "Tools run without asking",
        }
    }

    /// The modes a chat can switch to: Plan needs the planner, Auto-run the permissions plugin.
    pub fn available(plan: Option<Toggle>, bypass: Option<Toggle>) -> Vec<Mode> {
        let mut out = vec![Mode::Ask];
        if plan.is_some() {
            out.push(Mode::Plan);
        }
        if bypass.is_some() {
            out.push(Mode::Auto);
        }
        out
    }

    /// The next mode for Shift+Tab, among the available ones.
    pub fn next(self, available: &[Mode]) -> Mode {
        let i = available.iter().position(|m| *m == self).unwrap_or(0);
        available[(i + 1) % available.len().max(1)]
    }
}

/// Leave the old mode first (`plan off`, or `bypass off` unless staying in Auto-run), then enter the new one.
pub fn mode_commands(from: Mode, to: Mode) -> Vec<String> {
    if from == to {
        return vec![];
    }
    let mut out = vec![];
    if from == Mode::Plan {
        out.push("plan off".into());
    }
    if from == Mode::Auto && to != Mode::Auto {
        out.push("bypass off".into());
    }
    match to {
        Mode::Plan => out.push("plan on".into()),
        Mode::Auto => out.push("bypass on".into()),
        Mode::Ask => {}
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(on: bool) -> Option<Toggle> {
        Some(Toggle { on, default: false })
    }

    #[test]
    fn reads_and_switches_the_mode() {
        assert_eq!(Mode::of(None, None), None);
        assert_eq!(Mode::of(t(true), t(true)), Some(Mode::Plan));
        assert_eq!(Mode::of(t(false), t(true)), Some(Mode::Auto));
        assert_eq!(Mode::of(t(false), None), Some(Mode::Ask));
        assert_eq!(mode_commands(Mode::Plan, Mode::Auto), vec!["plan off", "bypass on"]);
        assert_eq!(mode_commands(Mode::Auto, Mode::Plan), vec!["bypass off", "plan on"]);
        assert_eq!(mode_commands(Mode::Auto, Mode::Ask), vec!["bypass off"]);
        assert_eq!(mode_commands(Mode::Ask, Mode::Ask), Vec::<String>::new());
        let all = Mode::available(t(false), t(false));
        assert_eq!(Mode::Ask.next(&all), Mode::Plan);
        assert_eq!(Mode::Auto.next(&all), Mode::Ask);
        assert_eq!(Mode::Ask.next(&Mode::available(None, t(false))), Mode::Auto);
    }
}
