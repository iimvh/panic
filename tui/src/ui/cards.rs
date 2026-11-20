//! What the agent asks the user, pinned where the composer is until it is answered: an approval (y/n), a
//! plan (a/f), or questions (↑↓, Space, Enter). Nothing here moves while it waits.

use super::text::{markdown, wrap};
use super::theme;
use crate::core::format::shell_command;
use crate::core::reply::{Approval, PlanProposal, Questions};
use ratatui::style::Modifier;
use ratatui::text::{Line, Span};

pub fn risk_label(risk: &str) -> String {
    match risk {
        "read" => "Reads files".into(),
        "write" => "Writes files".into(),
        "exec" => "Runs a command".into(),
        "network" => "Uses the network".into(),
        other => other.into(),
    }
}

pub fn approval(a: &Approval, waiting: usize, w: usize, max_body: usize) -> Vec<Line<'static>> {
    let who = a.agent.clone().unwrap_or_else(|| "Panic".into());
    let risk_style = if a.risk == "read" { theme::muted() } else { theme::warn() };
    let mut out = vec![Line::from(vec![
        Span::styled(format!("{who} wants to run "), theme::bold()),
        Span::styled(a.name.clone(), theme::bold().fg(theme::ACCENT)),
        Span::styled(" · ", theme::muted()),
        Span::styled(risk_label(&a.risk), risk_style),
    ])];
    // a file change is shown as the change it would make, as Claude Code's prompt does
    let mut lines = match crate::core::diff::fallback_diff(&a.name, &a.arguments, None) {
        Some(d) => {
            let path = crate::core::format::args(&a.arguments).get("path").and_then(|p| p.as_str()).unwrap_or("").to_string();
            let mut rows = vec![];
            let target = super::thread::Target::Message { msg: 0 };
            super::thread::diff_lines(&d, w, None, "  ", &target, &mut rows);
            let mut out = vec![Line::from(Span::styled(format!("  {path}"), theme::muted()))];
            out.extend(rows.into_iter().map(|r| r.line));
            out
        }
        None => {
            let body = shell_command(&a.arguments).unwrap_or_else(|| pretty(&a.arguments));
            wrap(&[(body, ratatui::style::Style::default())], w, Span::raw("  "), Span::raw("  "))
        }
    };
    if lines.len() > max_body {
        let more = lines.len() - max_body + 1;
        lines.truncate(max_body - 1);
        lines.push(Line::from(Span::styled(format!("  … {more} more lines"), theme::muted())));
    }
    out.extend(lines);
    let mut keys = vec![
        Span::styled("[y]", theme::accent().add_modifier(Modifier::BOLD)),
        Span::raw(" Allow   "),
        Span::styled("[n]", theme::accent().add_modifier(Modifier::BOLD)),
        Span::raw(" Deny"),
    ];
    if waiting > 0 {
        keys.push(Span::styled(format!("   {waiting} more waiting"), theme::muted()));
    }
    out.push(Line::from(keys));
    out
}

/// Arguments as indented JSON, or as they came when they are not JSON.
pub fn pretty(args: &str) -> String {
    serde_json::from_str::<serde_json::Value>(args)
        .ok()
        .and_then(|v| serde_json::to_string_pretty(&v).ok())
        .unwrap_or_else(|| args.to_string())
}

/// The feedback the user writes to send a plan back.
#[derive(Default)]
pub struct PlanForm {
    pub plan_id: String,
    pub feedback: Option<String>,
    pub scroll: usize,
}

pub fn plan(p: &PlanProposal, _form: &PlanForm, w: usize) -> Vec<Line<'static>> {
    let mut out =
        vec![Line::from(vec![Span::styled("Proposed plan", theme::bold()), Span::styled(" · Nothing has changed yet", theme::muted())])];
    out.extend(markdown(&p.plan, w, Span::raw("  "), Span::raw("  ")));
    if !p.items.is_empty() {
        out.push(Line::from(Span::styled("  Steps", theme::bold())));
        for (i, item) in p.items.iter().enumerate() {
            out.extend(wrap(&[(item.clone(), Default::default())], w, Span::raw(format!("  {}. ", i + 1)), Span::raw("     ")));
        }
    }
    if !p.criteria.is_empty() {
        out.push(Line::from(Span::styled("  Done when", theme::bold())));
        for c in &p.criteria {
            out.extend(wrap(&[(c.clone(), Default::default())], w, Span::raw("  • "), Span::raw("    ")));
        }
    }
    out
}

pub fn plan_keys(form: &PlanForm) -> Line<'static> {
    match &form.feedback {
        None => Line::from(vec![
            Span::styled("[a]", theme::accent().add_modifier(Modifier::BOLD)),
            Span::raw(" Approve and start   "),
            Span::styled("[f]", theme::accent().add_modifier(Modifier::BOLD)),
            Span::raw(" Keep planning   "),
            Span::styled("↑↓ scroll", theme::muted()),
        ]),
        Some(_) => Line::from(vec![
            Span::styled("What should change in the plan?  ", theme::bold()),
            Span::styled("Enter send back · Esc cancel", theme::muted()),
        ]),
    }
}

/// The answers being picked for an agent's questions.
pub struct QuestionForm {
    pub id: String,
    /// The question and the row (an option, or `options.len()` for Other) the keyboard is on.
    pub question: usize,
    pub row: usize,
    pub picked: Vec<Vec<bool>>,
    pub other: Vec<String>,
    /// Whether the Other text of each question is chosen.
    pub other_on: Vec<bool>,
}

impl QuestionForm {
    pub fn new(q: &Questions) -> Self {
        QuestionForm {
            id: q.id.clone(),
            question: 0,
            row: 0,
            picked: q.questions.iter().map(|x| vec![false; x.options.len()]).collect(),
            other: vec![String::new(); q.questions.len()],
            other_on: vec![false; q.questions.len()],
        }
    }

    fn rows(q: &Questions, i: usize) -> usize {
        q.questions[i].options.len() + 1
    }

    pub fn up(&mut self, q: &Questions) {
        if self.row > 0 {
            self.row -= 1;
        } else if self.question > 0 {
            self.question -= 1;
            self.row = Self::rows(q, self.question) - 1;
        }
    }

    pub fn down(&mut self, q: &Questions) {
        if self.row + 1 < Self::rows(q, self.question) {
            self.row += 1;
        } else if self.question + 1 < q.questions.len() {
            self.question += 1;
            self.row = 0;
        }
    }

    pub fn on_other(&self, q: &Questions) -> bool {
        self.row == q.questions[self.question].options.len()
    }

    /// Space: picks the option (one, or toggles one of several), or chooses Other.
    pub fn toggle(&mut self, q: &Questions) {
        let (i, uq) = (self.question, &q.questions[self.question]);
        if self.on_other(q) {
            self.other_on[i] = !self.other_on[i] || !uq.multi_select;
            if !uq.multi_select {
                self.picked[i].iter_mut().for_each(|p| *p = false);
            }
            return;
        }
        if uq.multi_select {
            self.picked[i][self.row] = !self.picked[i][self.row];
        } else {
            self.picked[i].iter_mut().enumerate().for_each(|(k, p)| *p = k == self.row);
            self.other_on[i] = false;
        }
    }

    /// Every question has an answer: an option, or Other text.
    pub fn complete(&self) -> bool {
        (0..self.picked.len()).all(|i| self.picked[i].iter().any(|p| *p) || (self.other_on[i] && !self.other[i].trim().is_empty()))
    }

    pub fn answers(&self, q: &Questions) -> crate::api::Answers {
        q.questions
            .iter()
            .enumerate()
            .map(|(i, uq)| {
                let sel = uq.options.iter().zip(&self.picked[i]).filter(|(_, p)| **p).map(|(o, _)| o.0.clone()).collect();
                (sel, if self.other_on[i] { self.other[i].trim().to_string() } else { String::new() })
            })
            .collect()
    }

    /// Skip: an empty answer for every question.
    pub fn skipped(q: &Questions) -> crate::api::Answers {
        q.questions.iter().map(|_| (vec![], String::new())).collect()
    }
}

pub fn questions(q: &Questions, f: &QuestionForm, w: usize) -> Vec<Line<'static>> {
    let mut out = vec![Line::from(Span::styled("Panic needs your input", theme::bold()))];
    for (i, uq) in q.questions.iter().enumerate() {
        let mut head = vec![Span::raw("  ")];
        if !uq.header.is_empty() {
            head.push(Span::styled(format!("{} · ", uq.header), theme::muted()));
        }
        head.push(Span::styled(uq.question.clone(), theme::bold()));
        if uq.multi_select {
            head.push(Span::styled(" · pick any", theme::muted()));
        }
        out.push(Line::from(head));
        for (k, (label, desc)) in
            uq.options.iter().enumerate().chain(std::iter::once((uq.options.len(), &("Other".to_string(), String::new()))))
        {
            let here = f.question == i && f.row == k;
            let on = if k < uq.options.len() { f.picked[i][k] } else { f.other_on[i] };
            let mark = match (uq.multi_select, on) {
                (true, true) => "[x]",
                (true, false) => "[ ]",
                (false, true) => "(•)",
                (false, false) => "( )",
            };
            let cursor = if here { "›" } else { " " };
            let mut spans = vec![
                Span::styled(format!("  {cursor} "), theme::accent()),
                Span::raw(format!("{mark} ")),
                Span::styled(label.clone(), if here { theme::bold() } else { Default::default() }),
            ];
            if k == uq.options.len() && (on || here) {
                spans.push(Span::styled(": ", theme::muted()));
                spans.push(Span::raw(f.other[i].clone()));
                if here {
                    spans.push(Span::styled("▏", theme::accent()));
                }
            } else if !desc.is_empty() {
                spans.push(Span::styled(format!("  {desc}"), theme::muted()));
            }
            out.push(Line::from(spans));
        }
    }
    let _ = w;
    let submit = if f.complete() { theme::accent().add_modifier(Modifier::BOLD) } else { theme::muted() };
    out.push(Line::from(vec![
        Span::styled("↑↓ ", theme::muted()),
        Span::raw("move  "),
        Span::styled("Space ", theme::muted()),
        Span::raw("pick  "),
        Span::styled("Enter ", submit),
        Span::styled("submit  ", submit),
        Span::styled("Esc ", theme::muted()),
        Span::raw("skip"),
    ]));
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::reply::UserQuestion;

    fn qs() -> Questions {
        Questions {
            id: "x".into(),
            questions: vec![
                UserQuestion {
                    question: "Which?".into(),
                    header: "Cache".into(),
                    multi_select: false,
                    options: vec![("Redis".into(), "shared".into()), ("Memory".into(), String::new())],
                },
                UserQuestion {
                    question: "Also?".into(),
                    header: String::new(),
                    multi_select: true,
                    options: vec![("A".into(), String::new()), ("B".into(), String::new())],
                },
            ],
        }
    }

    #[test]
    fn picks_answers_and_submits_when_complete() {
        let q = qs();
        let mut f = QuestionForm::new(&q);
        assert!(!f.complete());
        f.down(&q);
        f.toggle(&q); // Memory
        f.down(&q);
        f.toggle(&q); // Other on a single choice clears Memory
        assert!(!f.complete(), "Other needs text");
        f.other[0] = "disk".into();
        f.down(&q); // to question 2, A
        f.toggle(&q);
        f.down(&q);
        f.toggle(&q);
        assert!(f.complete());
        assert_eq!(f.answers(&q), vec![(vec![], "disk".to_string()), (vec!["A".to_string(), "B".to_string()], String::new())]);
        assert_eq!(QuestionForm::skipped(&q), vec![(vec![], String::new()), (vec![], String::new())]);
    }

    #[test]
    fn labels_risks() {
        assert_eq!(risk_label("exec"), "Runs a command");
        assert_eq!(risk_label("odd"), "odd");
    }
}
