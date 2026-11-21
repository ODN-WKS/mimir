//! "Auto" model: Mímir picks the model for each message from what
//! is asked, without an extra call to Claude. Within a task it only ever
//! steps up (a switch makes Claude re-read the conversation once on the
//! new model, which uses more of the owner's limit), never back down.

use crate::task::{Entry, Mode, Task};

/// The `model` value for Auto · efficient ("auto" from before is the same).
pub const AUTO: &str = "auto";
/// The `model` value for Auto · capable.
pub const AUTO_CAPABLE: &str = "auto-capable";

/// The two ways Auto can lean.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Style {
    /// Lightest model that suits; switches to a brief sooner.
    Efficient,
    /// One step stronger; keeps the full history longer.
    Capable,
}

impl Style {
    /// The Auto style a task's `model` value asks for, if any.
    pub fn of(model: Option<&str>) -> Option<Style> {
        match model {
            Some(AUTO) => Some(Style::Efficient),
            Some(AUTO_CAPABLE) => Some(Style::Capable),
            _ => None,
        }
    }

    pub fn value(self) -> &'static str {
        match self {
            Style::Efficient => AUTO,
            Style::Capable => AUTO_CAPABLE,
        }
    }

    pub fn words(self) -> &'static str {
        match self {
            Style::Efficient => "Auto · efficient",
            Style::Capable => "Auto · capable",
        }
    }

    /// Conversation size from which a switch hands over with a brief.
    pub fn handover_from(self) -> u64 {
        match self {
            Style::Efficient => 40_000,
            Style::Capable => 100_000,
        }
    }
}

/// Lightest to strongest, as exact Claude Code model ids.
pub const LADDER: [&str; 4] = [
    "claude-haiku-5-5",
    "claude-sonnet-5-5",
    "claude-opus-5-5",
    "claude-fable-5-2",
];

const HARD_WORDS: &[&str] = &[
    "refactor",
    "architecture",
    "redesign",
    "design",
    "investigate",
    "debug",
    "root cause",
    "why does",
    "why is",
    "performance",
    "security",
    "migrate",
    "rewrite",
];
const CODE_WORDS: &[&str] = &[
    "fix",
    "build",
    "test",
    "error",
    "bug",
    "code",
    "function",
    "file",
    "compile",
    "install",
    "commit",
    "run",
    "add",
    "change",
    "implement",
    "script",
    ".rs",
    ".py",
    ".js",
    ".ts",
];
const STRUGGLE_WORDS: &[&str] = &[
    "still fails",
    "still broken",
    "still not",
    "didn't work",
    "doesn't work",
    "not working",
    "failed again",
    "same error",
    "broken",
];
const STRONGEST_WORDS: &[&str] = &["fable", "think hard", "ultrathink", "think very hard"];

// Below a style's handover size, re-reading on the new model is cheap:
// keep the full history. (Every session, even a fresh one, starts with
// about 20k tokens of Claude Code's own instructions, so a brief only saves
// what is above that.)

/// Whether a model switch should hand over with a brief instead of the full
/// history: only for long conversations, and never when a brief could lose
/// something that matters (planning, a plan waiting, a checklist under way).
pub fn hand_over(t: &Task, style: Style) -> bool {
    let checklist_open = t.plan.iter().any(|p| !p.done);
    t.session_id.is_some()
        && t.context_used >= style.handover_from()
        && t.mode != Mode::Plan
        && t.plan_doc.is_none()
        && !checklist_open
}

fn clip(text: &str, max: usize) -> String {
    let text = text.trim();
    if text.chars().count() <= max {
        return text.to_string();
    }
    let cut: String = text.chars().take(max).collect();
    format!("{cut} …[shortened]")
}

/// A short brief of the task so far, built from what the window already
/// has (no call to Claude): first request, checklist, changed files, recent
/// messages and steps.
pub fn brief(t: &Task) -> String {
    let mut out = String::from(
        "You are taking over this task from an earlier session, which was handed over \
         to save re-reading the whole conversation. Here is a brief of it. Files may \
         have changed: read them again rather than trusting earlier output.\n\n",
    );
    out.push_str(&format!("Project folder: {}\n", t.project));
    if let Some(first) = t.entries.iter().find_map(|e| match e {
        Entry::You(x) => Some(x),
        _ => None,
    }) {
        out.push_str(&format!("First request: {}\n", clip(first, 1500)));
    }
    if !t.plan.is_empty() {
        out.push_str("Checklist:\n");
        for p in &t.plan {
            out.push_str(&format!(
                "- [{}] {}\n",
                if p.done { "x" } else { " " },
                p.text
            ));
        }
    }
    if !t.files_changed.is_empty() {
        let files: Vec<&str> = t
            .files_changed
            .iter()
            .take(40)
            .map(String::as_str)
            .collect();
        out.push_str(&format!("Files changed so far: {}\n", files.join(", ")));
    }
    // The last few messages and steps, newest kept first when space runs
    // out, shown oldest first.
    const BUDGET: usize = 14_000;
    let mut room = BUDGET.saturating_sub(out.len()).max(3_000);
    let mut recent: Vec<String> = vec![];
    let mut exchanges = 0;
    for e in t.entries.iter().rev() {
        let line = match e {
            Entry::You(x) => {
                exchanges += 1;
                format!("Owner: {}", clip(x, 1200))
            }
            Entry::Claude(x) => format!("Claude: {}", clip(x, 1500)),
            Entry::Step(st) => format!(
                "Step: {}{}",
                st.what,
                match st.ok {
                    Some(false) if st.refused => " (owner said no)",
                    Some(false) => " (did not succeed)",
                    _ => "",
                }
            ),
            Entry::Note(_) => continue,
        };
        if line.len() + 1 > room {
            break;
        }
        room -= line.len() + 1;
        recent.push(line);
        if exchanges >= 6 {
            break;
        }
    }
    if !recent.is_empty() {
        recent.reverse();
        out.push_str("\nMost recent part of the conversation:\n");
        out.push_str(&recent.join("\n"));
        out.push('\n');
    }
    out
}

/// "Sonnet 5.5" for "claude-sonnet-5-5".
pub fn name(id: &str) -> String {
    let rest = id.trim_start_matches("claude-");
    let mut parts = rest.splitn(2, '-');
    let family = parts.next().unwrap_or(rest);
    let version = parts.next().unwrap_or("").replace('-', ".");
    let mut c = family.chars();
    let family = c
        .next()
        .map(|f| f.to_uppercase().collect::<String>() + c.as_str())
        .unwrap_or_default();
    format!("{family} {version}").trim().to_string()
}

/// Whether `text` holds one of `words` as whole words (so "plan" is not in
/// "planet", "fix" not in "prefix"). Words starting with "." match file
/// endings anywhere.
fn has(text: &str, words: &[&str]) -> bool {
    let is_word = |c: char| c.is_alphanumeric() || c == '_';
    words.iter().any(|w| {
        if w.starts_with('.') {
            return text.contains(w);
        }
        text.match_indices(w).any(|(i, _)| {
            let before = text[..i].chars().next_back();
            let after = text[i + w.len()..].chars().next();
            !before.is_some_and(is_word) && !after.is_some_and(is_word)
        })
    })
}

fn rung(model: &str) -> Option<usize> {
    LADDER.iter().position(|m| *m == model)
}

/// Whether the last turn went badly: a step that failed on its own (not
/// refused or stopped by the owner), or Claude ended with a problem.
/// One failed command is often just a look around (`which python`); two
/// in one turn, or Claude ending with a problem, means it is struggling.
fn last_turn_failed(t: &Task) -> bool {
    let mut failed = 0;
    for e in t.entries.iter().rev() {
        match e {
            Entry::You(_) => break,
            Entry::Note(n)
                if n.starts_with("Work stopped with a problem")
                    || n.starts_with("Claude stopped with a problem") =>
            {
                return true;
            }
            Entry::Step(s) if s.ok == Some(false) && !s.refused && !s.stopped => failed += 1,
            _ => {}
        }
    }
    failed >= 2
}

/// The model for the next message of `t`. `is_chat`: a general chat, not
/// about a project folder.
pub fn choose(t: &Task, text: &str, is_chat: bool, style: Style) -> &'static str {
    let lower = text.to_lowercase();
    let asked_strongest = has(&lower, STRONGEST_WORDS);
    // Chats are not about code: coding and "it still fails" words there are
    // everyday words ("plan a trip", "thanks again").
    let code = !is_chat && has(&lower, CODE_WORDS);
    let quick = text.len() < 120 && lower.trim_end().ends_with('?') && !code;
    let hard = t.mode == Mode::Plan || text.len() > 1500 || (!is_chat && has(&lower, HARD_WORDS));
    let wanted = match style {
        Style::Efficient => {
            if asked_strongest {
                3
            } else if hard {
                2
            } else if (is_chat && text.len() < 800) || quick {
                0
            } else {
                1
            }
        }
        Style::Capable => {
            if asked_strongest || (hard && text.len() > 1500) {
                3
            } else if hard || (!is_chat && !quick) {
                2
            } else {
                1
            }
        }
    };
    let previous = t.auto_choice.as_deref().and_then(rung);
    let struggling =
        !is_chat && (last_turn_failed(t) || (previous.is_some() && has(&lower, STRUGGLE_WORDS)));
    // Struggling: one step up from where it was. Efficient stops at Opus
    // unless the owner asks for Fable.
    let ceiling = if style == Style::Efficient && !asked_strongest {
        2
    } else {
        3
    };
    let wanted = if struggling {
        wanted.max((previous.unwrap_or(1) + 1).min(ceiling))
    } else {
        wanted
    };
    // Never back down within a task.
    LADDER[wanted.max(previous.unwrap_or(0)).min(LADDER.len() - 1)]
}

#[cfg(test)]
mod tests {
    use super::*;

    const E: Style = Style::Efficient;
    const C: Style = Style::Capable;

    #[test]
    fn capable_leans_one_step_stronger() {
        let t = task();
        assert_eq!(
            choose(&t, "What's the capital of France?", true, C),
            "claude-sonnet-5-5"
        );
        assert_eq!(
            choose(&t, "Fix the failing test in calc.py", false, C),
            "claude-opus-5-5"
        );
        let long = format!("Refactor the storage layer. {}", "detail ".repeat(300));
        assert_eq!(choose(&t, &long, false, C), "claude-fable-5-2");
        assert_eq!(choose(&t, &long, false, E), "claude-opus-5-5");
        assert_eq!(Style::of(Some("auto")), Some(E));
        assert_eq!(Style::of(Some("auto-capable")), Some(C));
        assert_eq!(Style::of(Some("claude-opus-5-5")), None);
    }

    fn task() -> Task {
        Task::new("t".into(), "t".into(), "/p".into(), 0)
    }

    #[test]
    fn picks_by_what_is_asked() {
        let t = task();
        assert_eq!(
            choose(&t, "What's the capital of France?", true, E),
            "claude-haiku-5-5"
        );
        assert_eq!(
            choose(&t, "What does this project do?", false, E),
            "claude-haiku-5-5"
        );
        assert_eq!(
            choose(&t, "Fix the failing test in calc.py", false, E),
            "claude-sonnet-5-5"
        );
        assert_eq!(
            choose(&t, "Refactor the storage layer", false, E),
            "claude-opus-5-5"
        );
        assert_eq!(
            choose(&t, "ultrathink about this bug", false, E),
            "claude-fable-5-2"
        );
        let mut p = task();
        p.mode = Mode::Plan;
        assert_eq!(choose(&p, "add a button", false, E), "claude-opus-5-5");
    }

    #[test]
    fn briefs_and_when_to_hand_over() {
        let mut t = task();
        t.add_you("Fix the parser");
        t.entries
            .push(Entry::Claude("I fixed the off-by-one in parse.rs.".into()));
        t.files_changed.push("parse.rs".into());
        let b = brief(&t);
        assert!(b.contains("First request: Fix the parser"));
        assert!(b.contains("Files changed so far: parse.rs"));
        assert!(b.contains("Claude: I fixed the off-by-one"));
        // Short conversations keep their full history.
        t.session_id = Some("s".into());
        t.context_used = 5_000;
        assert!(!hand_over(&t, Style::Efficient));
        t.context_used = 90_000;
        assert!(hand_over(&t, Style::Efficient));
        assert!(!hand_over(&t, Style::Capable));
        // Never while planning or with a checklist under way.
        t.mode = Mode::Plan;
        assert!(!hand_over(&t, Style::Efficient));
        t.mode = Mode::Ask;
        t.plan.push(crate::task::PlanItem {
            id: "1".into(),
            text: "x".into(),
            done: false,
            doing: true,
        });
        assert!(!hand_over(&t, Style::Efficient));
        assert!(brief(&t).contains("- [ ] x"));
    }

    #[test]
    fn everyday_words_are_not_hard_words() {
        let t = task();
        // A chat about a trip stays light.
        assert_eq!(
            choose(&t, "Help me plan a 4-day trip to Lisbon", true, E),
            "claude-haiku-5-5"
        );
        let mut t2 = task();
        t2.auto_choice = Some("claude-haiku-5-5".into());
        assert_eq!(
            choose(&t2, "Perfect, thanks again!", true, E),
            "claude-haiku-5-5"
        );
        // Whole words only.
        assert!(!has("the prefix is planet-sized", &["fix", "plan"]));
        assert!(has("please fix it", &["fix"]));
        // Efficient climbs to Opus at most unless Fable is asked for.
        let mut t3 = task();
        t3.auto_choice = Some("claude-opus-5-5".into());
        assert_eq!(choose(&t3, "it still fails", false, E), "claude-opus-5-5");
        assert_eq!(choose(&t3, "it still fails", false, C), "claude-fable-5-2");
    }

    #[test]
    fn names() {
        assert_eq!(name("claude-sonnet-5-5"), "Sonnet 5.5");
        assert_eq!(name("claude-fable-5-2"), "Fable 5.2");
    }

    #[test]
    fn only_steps_up() {
        let mut t = task();
        t.auto_choice = Some("claude-opus-5-5".into());
        assert_eq!(choose(&t, "thanks?", true, E), "claude-opus-5-5");
        t.auto_choice = Some("claude-sonnet-5-5".into());
        assert_eq!(choose(&t, "it still fails", false, E), "claude-opus-5-5");
        t.auto_choice = Some("claude-fable-5-2".into());
        assert_eq!(choose(&t, "again", false, E), "claude-fable-5-2");
    }
}
