//! When a conversation has run long: the one rule behind the calm line at
//! the top of a task (the PC's window and the phone both read it).
//!
//! "Long" starts at the size the owner uses for his own sessions, "very
//! long" at 80% of the model's room. Online, every reply re-reads the whole
//! conversation, so length costs plan; on the PC's own model it costs speed
//! and the start may be forgotten.

use crate::task::Task;
use mimir_proto::{LongNotice, LongWhy};

/// Conversation size (in tokens) from which a task is long.
pub const LONG_FROM: u64 = 150_000;

/// What Claude Code's own start takes of a small window.
const OWN_BASE: u64 = 20_000;

/// A book page holds about this many tokens (a 300-page book is roughly
/// 130,000 of them).
const TOKENS_PER_PAGE: u64 = 440;

/// The notice a task calls for, or None while it is not long.
pub fn notice(t: &Task) -> Option<LongNotice> {
    // A Whisper keeps nothing: no line, no way to copy it into a saved task.
    if t.incognito {
        return None;
    }
    let own = crate::models::is_free(t.model.as_deref());
    // The PC's own model has a small room (the one Mímir gives it, unless the
    // model reported its own); Claude Code's start takes about 20,000 of it,
    // so "long" is 60% of the room left for the conversation itself.
    let window = if t.context_window > 0 {
        t.context_window
    } else if own {
        crate::models::CONTEXT
    } else {
        0
    };
    let base = if own && window > OWN_BASE {
        OWN_BASE
    } else {
        0
    };
    let room = window.saturating_sub(base);
    let long_from = if own && room > 0 {
        LONG_FROM.min(base + room * 3 / 5)
    } else {
        LONG_FROM
    };
    if t.context_used < long_from {
        return None;
    }
    let very = window > 0 && (t.context_used - base.min(t.context_used)) * 5 >= room * 4;
    let why = if own { LongWhy::Own } else { LongWhy::Online };
    // Rounded to ten pages, so it doesn't change with every reply.
    let pages = ((t.context_used / TOKENS_PER_PAGE + 5) / 10 * 10) as u32;
    Some(LongNotice {
        very,
        why,
        elsewhere: t.live_elsewhere,
        pages,
    })
}

/// The line itself, in plain words (the phone has the same words).
pub fn line(n: &LongNotice) -> String {
    let mut s = String::from("This conversation is long. ");
    s.push_str(match n.why {
        LongWhy::Online => "Each reply re-reads all of it, so it uses more of your plan.",
        LongWhy::Own => "Answers get slower, and the start may be forgotten.",
    });
    if n.very {
        s.push_str(" Starting fresh soon will save a lot.");
    }
    if n.elsewhere {
        s.push_str(" It is going on in another app, so it can't be started fresh from here.");
    }
    s
}

/// "About the length of a 340-page book." (None while it is short of a book).
pub fn size_words(n: &LongNotice) -> Option<String> {
    (n.pages >= 100).then(|| format!("About the length of a {}-page book.", n.pages))
}

/// The button's words: absent for a conversation going on elsewhere.
pub fn button(n: &LongNotice) -> Option<&'static str> {
    (!n.elsewhere).then_some("Start fresh with a summary")
}

/// Why a task can't be started fresh from here, in plain words.
pub fn start_fresh_refusal(t: &Task) -> Option<&'static str> {
    if t.incognito {
        Some("A Whisper keeps nothing, so it can't be carried into a new task.")
    } else if t.live_elsewhere {
        Some("It is going on in another app: start fresh there.")
    } else {
        None
    }
}

/// The mode of a fresh task: a phone never starts one that doesn't ask.
pub fn fresh_mode(from_phone: bool, mode: mimir_proto::Mode) -> mimir_proto::Mode {
    use mimir_proto::Mode;
    if from_phone && !matches!(mode, Mode::Ask | Mode::Plan) {
        Mode::Ask
    } else {
        mode
    }
}

/// Whether a notice stays hidden: `dismissed` says it was dismissed (and
/// whether at "very long"). A dismissal holds until the conversation turns
/// very long, once; one made at very long holds for good.
pub fn hidden(dismissed: Option<bool>, n: &LongNotice) -> bool {
    dismissed.is_some_and(|at_very| at_very || !n.very)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn task(model: Option<&str>, used: u64, window: u64) -> Task {
        let mut t = Task::new("t1".into(), "Plan the garden".into(), "/p".into(), 0);
        t.model = model.map(String::from);
        t.context_used = used;
        t.context_window = window;
        t
    }

    #[test]
    fn long_from_150k_and_very_long_from_80_percent() {
        assert!(notice(&task(None, 149_999, 200_000)).is_none());
        let n = notice(&task(None, 150_000, 1_000_000)).unwrap();
        assert!(!n.very);
        let n = notice(&task(None, 150_000, 200_000)).unwrap();
        assert!(!n.very, "75% is not yet very long");
        let n = notice(&task(None, 160_000, 200_000)).unwrap();
        assert!(n.very, "80% is");
        // No window known: long, never very long.
        assert!(!notice(&task(None, 400_000, 0)).unwrap().very);
    }

    #[test]
    fn own_and_online_say_their_own_reason() {
        let on = notice(&task(Some("claude-sonnet-5-5"), 160_000, 1_000_000)).unwrap();
        assert_eq!(on.why, LongWhy::Online);
        assert!(line(&on).contains("uses more of your plan"));
        let own = notice(&task(
            Some("ollama:qwen3.6:35b-a3b-coding"),
            160_000,
            1_000_000,
        ))
        .unwrap();
        assert_eq!(own.why, LongWhy::Own);
        let words = line(&own);
        assert!(words.contains("slower") && words.contains("start may be forgotten"));
        assert!(!words.contains("plan"));
    }

    #[test]
    fn a_followed_session_gets_it_but_no_button() {
        let mut t = task(None, 170_000, 1_000_000);
        t.live_elsewhere = true;
        let n = notice(&t).unwrap();
        assert!(n.elsewhere);
        assert!(button(&n).is_none());
        assert!(line(&n).contains("another app"));
        // Without usage in its transcript there is nothing to say.
        t.context_used = 0;
        assert!(notice(&t).is_none());
        t.context_used = 170_000;
        t.live_elsewhere = false;
        assert_eq!(
            button(&notice(&t).unwrap()),
            Some("Start fresh with a summary")
        );
    }

    #[test]
    fn very_long_is_a_little_stronger_and_no_token_counts_show() {
        let n = notice(&task(None, 170_000, 200_000)).unwrap();
        assert!(line(&n).ends_with("Starting fresh soon will save a lot."));
        let all = format!("{} {:?}", line(&n), size_words(&n));
        assert!(!all.contains("170") && !all.to_lowercase().contains("token"));
        assert_eq!(
            size_words(&n).as_deref(),
            Some("About the length of a 390-page book.")
        );
        assert!(size_words(&LongNotice { pages: 0, ..n }).is_none());
    }

    #[test]
    fn dismissed_stays_dismissed_until_very_long() {
        let long = notice(&task(None, 160_000, 1_000_000)).unwrap();
        let very = notice(&task(None, 900_000, 1_000_000)).unwrap();
        assert!(!hidden(None, &long));
        assert!(hidden(Some(false), &long));
        assert!(!hidden(Some(false), &very), "comes back at very long");
        assert!(hidden(Some(true), &very), "dismissed there, it stays");
    }

    #[test]
    fn a_whisper_task_never_gets_the_line_or_a_fresh_start() {
        let mut t = task(None, 900_000, 1_000_000);
        t.incognito = true;
        assert!(notice(&t).is_none());
        assert!(start_fresh_refusal(&t).unwrap().contains("Whisper"));
        t.incognito = false;
        assert!(start_fresh_refusal(&t).is_none());
        t.live_elsewhere = true;
        assert!(start_fresh_refusal(&t).unwrap().contains("another app"));
    }

    #[test]
    fn the_pcs_own_model_is_long_well_before_150k() {
        let own = Some("ollama:qwen3.6:35b-a3b-coding");
        // The window Mímir gives it is 32,768, about 20,000 of it Claude Code's own start.
        assert!(
            notice(&task(own, 20_500, 0)).is_none(),
            "a fresh task is not long"
        );
        assert!(notice(&task(own, 28_000, 0)).is_some());
        assert!(!notice(&task(own, 28_000, 0)).unwrap().very);
        assert!(notice(&task(own, 31_000, 0)).unwrap().very);
        // A window the model reported is used instead.
        assert!(notice(&task(own, 28_000, 200_000)).is_none());
        // Online stays at 150,000.
        assert!(notice(&task(None, 28_000, 0)).is_none());
    }

    #[test]
    fn a_phone_never_starts_a_task_that_doesnt_ask() {
        use mimir_proto::Mode;
        assert_eq!(fresh_mode(true, Mode::NoAsk), Mode::Ask);
        assert_eq!(fresh_mode(true, Mode::Auto), Mode::Ask);
        assert_eq!(fresh_mode(true, Mode::Plan), Mode::Plan);
        assert_eq!(fresh_mode(false, Mode::NoAsk), Mode::NoAsk);
    }

    /// Writes the words for every case, for the phone's test to compare with.
    #[test]
    fn the_words_for_every_case_are_written_for_the_phone() {
        let mut rows = vec![];
        for very in [false, true] {
            for own in [false, true] {
                for elsewhere in [false, true] {
                    for pages in [340, 50] {
                        let n = LongNotice {
                            very,
                            why: if own { LongWhy::Own } else { LongWhy::Online },
                            elsewhere,
                            pages,
                        };
                        rows.push(serde_json::json!({
                            "very": very, "own": own, "elsewhere": elsewhere, "pages": pages,
                            "line": line(&n), "size": size_words(&n),
                        }));
                    }
                }
            }
        }
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../proto/tests/fixtures");
        std::fs::write(
            dir.join("long_lines.json"),
            serde_json::to_string_pretty(&rows).unwrap() + "\n",
        )
        .unwrap();
    }

    #[test]
    fn the_task_status_carries_it_only_when_long() {
        let long = notice(&task(None, 160_000, 200_000)).unwrap();
        let json = serde_json::to_value(long).unwrap();
        assert_eq!(json["very"], true);
        assert_eq!(json["why"], "Online");
        let back: LongNotice = serde_json::from_value(json).unwrap();
        assert_eq!(back, long);
    }
}
