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

/// A book page holds about this many tokens (a 300-page book is roughly
/// 130,000 of them).
const TOKENS_PER_PAGE: u64 = 440;

/// The notice a task calls for, or None while it is not long.
pub fn notice(t: &Task) -> Option<LongNotice> {
    if t.context_used < LONG_FROM {
        return None;
    }
    let very = t.context_window > 0 && t.context_used * 5 >= t.context_window * 4;
    let why = if crate::models::is_free(t.model.as_deref()) {
        LongWhy::Own
    } else {
        LongWhy::Online
    };
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
    fn the_task_status_carries_it_only_when_long() {
        let long = notice(&task(None, 160_000, 200_000)).unwrap();
        let json = serde_json::to_value(long).unwrap();
        assert_eq!(json["very"], true);
        assert_eq!(json["why"], "Online");
        let back: LongNotice = serde_json::from_value(json).unwrap();
        assert_eq!(back, long);
    }
}
