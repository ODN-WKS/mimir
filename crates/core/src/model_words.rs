//! "use opus 5.5 high": a message that is only a pick of model and effort.
//! Mímir applies it itself; it is never sent to the AI. Only a whole message
//! that says nothing else counts, so work that mentions a model is untouched.

use crate::auto::{AUTO, AUTO_CAPABLE, LADDER};

/// What a message picks. `None`: left as it is. `Some(None)`: the default.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Pick {
    pub model: Option<Option<String>>,
    pub effort: Option<Option<String>>,
    /// A model version Mímir doesn't offer ("opus 3"): nothing is changed.
    pub unknown: bool,
}

/// Said in a chat, which words can't change.
pub const CHAT_PICKER: &str = "I can't change a chat's model from words: tap the model button by the message box to pick the model and effort.";

/// Said when the model asked for isn't offered.
pub const NOT_OFFERED: &str =
    "That model isn't one Mímir offers: tap the model button by the message box to pick one.";

const POLITE: [&str; 9] = [
    "please", "pls", "now", "thanks", "instead", "ok", "okay", "hey", "just",
];

/// The pick, when `text` is nothing but one.
pub fn parse(text: &str) -> Option<Pick> {
    if text.len() > 80 || text.contains('\n') {
        return None;
    }
    let lower = text
        .to_lowercase()
        .replace("extra high", "xhigh")
        .replace("extra-high", "xhigh")
        .replace("x-high", "xhigh");
    let cleaned: String = lower
        .chars()
        .map(|c| match c {
            ',' | '!' | '?' | '·' | ':' | '-' => ' ',
            c => c,
        })
        .collect();
    let mut w: Vec<&str> = cleaned
        .split_whitespace()
        .map(|w| w.trim_end_matches('.'))
        .filter(|w| !w.is_empty() && !POLITE.contains(w))
        .collect();
    if w.starts_with(&["can", "you"]) || w.starts_with(&["could", "you"]) {
        w.drain(..2);
    }
    let verb = matches!(w.first(), Some(&("use" | "switch" | "change" | "set")));
    if verb {
        w.remove(0);
    }
    let mut pick = Pick::default();
    let mut effort_word = false;
    let mut i = 0;
    while i < w.len() {
        let next = w.get(i + 1).copied();
        match w[i] {
            "to" | "the" | "with" | "at" | "on" | "and" | "a" | "model" | "claude" | "mode" => {}
            "effort" | "thinking" => effort_word = true,
            "low" | "medium" | "high" | "xhigh" | "max" if pick.effort.is_none() => {
                pick.effort = Some(Some(w[i].to_string()));
            }
            "default" if pick.model.is_none() && next == Some("model") => {
                pick.model = Some(None);
            }
            "default" if pick.effort.is_none() && next == Some("effort") => {
                pick.effort = Some(None);
            }
            "auto" if pick.model.is_none() => {
                let strong = matches!(next, Some("heavy" | "capable" | "stronger" | "strong"));
                if strong || next == Some("efficient") {
                    i += 1;
                }
                pick.model = Some(Some(if strong { AUTO_CAPABLE } else { AUTO }.to_string()));
            }
            family @ ("opus" | "sonnet" | "haiku" | "fable") if pick.model.is_none() => {
                let mut id = LADDER
                    .iter()
                    .find(|m| m.contains(family))
                    .map(|m| m.to_string())?;
                if let Some(v) = next.filter(|v| v.starts_with(|c: char| c.is_ascii_digit())) {
                    pick.unknown = !id.ends_with(&format!("-{}", v.replace('.', "-")));
                    if pick.unknown {
                        id.clear();
                    }
                    i += 1;
                }
                pick.model = Some(Some(id));
            }
            _ => return None,
        }
        i += 1;
    }
    let both = pick.model.is_some() && pick.effort.is_some();
    // "high" or "opus" alone is not a command, and nor is "use max".
    let meant = both || (verb && (pick.model.is_some() || (effort_word && pick.effort.is_some())));
    meant.then_some(pick)
}

/// The one line said once `pick` is applied.
pub fn done_line(pick: &Pick, working: bool) -> String {
    let mut parts = vec![];
    match &pick.model {
        Some(Some(m)) => parts.push(model_name(m)),
        Some(None) => parts.push("the default model".to_string()),
        None => {}
    }
    match &pick.effort {
        Some(Some(e)) => parts.push(format!("{} effort", effort_name(e))),
        Some(None) => parts.push("the default effort".to_string()),
        None => {}
    }
    let when = if working {
        "from the next step after this one finishes"
    } else {
        "from your next message"
    };
    format!("Now using {}, {when}.", parts.join(" at "))
}

/// A model in the pickers' words.
pub fn model_name(id: &str) -> String {
    match id {
        AUTO => "Auto · efficient".to_string(),
        AUTO_CAPABLE => "Auto · capable".to_string(),
        _ => crate::auto::name(id).to_string(),
    }
}

fn effort_name(e: &str) -> &str {
    match e {
        "low" => "Low",
        "medium" => "Medium",
        "high" => "High",
        "xhigh" => "Extra high",
        "max" => "Max",
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn got(text: &str) -> Option<(Option<String>, Option<String>)> {
        parse(text).map(|p| (p.model.flatten(), p.effort.flatten()))
    }

    #[test]
    fn the_owners_words_and_their_cousins_are_a_pick() {
        let s = |x: &str| Some(x.to_string());
        let opus = s("claude-opus-5-5");
        for t in [
            "use opus 5.5 high",
            "Use Opus 5.5 High.",
            "please switch to opus 5.5 at high effort",
            "can you use Opus with high effort?",
            "opus 5.5 high",
        ] {
            assert_eq!(got(t), Some((opus.clone(), s("high"))), "{t}");
        }
        assert_eq!(got("use sonnet"), Some((s("claude-sonnet-5-5"), None)));
        assert_eq!(
            got("use auto heavy with high effort"),
            Some((s(AUTO_CAPABLE), s("high")))
        );
        assert_eq!(got("set effort to extra high"), Some((None, s("xhigh"))));
        assert_eq!(got("use max effort"), Some((None, s("max"))));
        assert!(parse("use opus 3").unwrap().unknown);
    }

    #[test]
    fn work_that_mentions_a_model_is_left_alone() {
        for t in [
            "high",
            "opus",
            "use max",
            "use a high contrast theme",
            "use opus 5.5 high to fix the sign-in page",
            "why is the effort high",
            "write a sonnet",
            "change the model",
            "",
        ] {
            assert_eq!(parse(t), None, "{t}");
        }
    }
}
