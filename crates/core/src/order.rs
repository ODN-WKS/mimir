//! The order of every list of projects, chats and tasks: newest first by
//! the person's last message. Replies, steps, finishes and Mímir's own
//! messages move nothing. With no message of the person, creation time.

use crate::proto::ChatRecord;
use crate::task::{Entry, Task};
use serde_json::Value;

/// What Mímir sends when the online limit runs out and the work carries on.
pub const LIMIT_CARRY_ON: &str =
    "Claude's usage limit ran out partway through this task. Carry on from where it stopped.";

/// Words Mímir sends in the person's place.
pub fn own_words(text: &str) -> bool {
    let text = text.trim();
    [
        crate::runner::CONTINUE_TEXT,
        crate::queue::SUMMARY_PROMPT,
        LIMIT_CARRY_ON,
        crate::long::CARRY_ON_TEXT,
    ]
    .contains(&text)
}

/// Where a task stands (ms since 1970; larger is higher in a list).
pub fn task_key(t: &Task) -> i64 {
    t.you_ms.unwrap_or(t.created_unix.saturating_mul(1000))
}

/// A project: the newest message of the person in any of its tasks; with
/// none, its newest task's creation; with no task, last.
pub fn project_key<'a>(tasks: impl IntoIterator<Item = &'a Task>) -> i64 {
    let (mut you, mut made) = (None, 0);
    for t in tasks {
        you = you.max(t.you_ms);
        made = made.max(t.created_unix.saturating_mul(1000));
    }
    you.unwrap_or(made)
}

/// A chat: the person's newest message; with none, when it began.
pub fn chat_key(c: &ChatRecord) -> i64 {
    let live = || c.turns.iter().filter(|t| !t.gone);
    live()
        .filter(|t| t.user)
        .map(|t| t.at)
        .max()
        .or_else(|| live().map(|t| t.at).min())
        .unwrap_or(c.at)
}

/// Fills in [`Task::you_ms`] for a task saved before it was kept: from the
/// times beside its messages, else the session's own file (`transcript`),
/// else the first step the last message started.
pub fn backfill(t: &mut Task, transcript: Option<&str>) {
    if t.you_ms.is_some() {
        return;
    }
    let mine: Vec<(usize, &str)> = t
        .entries
        .iter()
        .enumerate()
        .filter_map(|(i, e)| match e {
            Entry::You(x) if !own_words(x) => Some((i, x.as_str())),
            _ => None,
        })
        .collect();
    let Some(&(last, text)) = mine.last() else {
        return;
    };
    let at = |i: usize| t.entry_meta.get(i).and_then(|m| m.at);
    let stamped = mine.iter().filter_map(|(i, _)| at(*i)).max();
    let found = if at(last).is_some() {
        None
    } else {
        transcript.and_then(|all| said_at(all, text)).or_else(|| {
            t.entries[last..].iter().find_map(|e| match e {
                Entry::Step(s) if s.started_ms > 0 => i64::try_from(s.started_ms).ok(),
                _ => None,
            })
        })
    };
    t.you_ms = stamped.max(found);
}

/// Whether [`backfill`] could use the session's own file.
pub fn wants_transcript(t: &Task) -> bool {
    t.you_ms.is_none()
        && t.entries
            .iter()
            .enumerate()
            .rev()
            .find(|(_, e)| matches!(e, Entry::You(x) if !own_words(x)))
            .is_some_and(|(i, _)| t.entry_meta.get(i).and_then(|m| m.at).is_none())
}

/// When the session's file says these words of the person were last sent.
fn said_at(transcript: &str, text: &str) -> Option<i64> {
    let words = text
        .rsplit_once("\n[")
        .filter(|(_, tail)| tail.ends_with(" attached]"))
        .map_or(text, |(head, _)| head)
        .trim();
    transcript.lines().rev().find_map(|l| {
        let v: Value = serde_json::from_str(l).ok()?;
        let said = crate::sessions::person_words(&v)?;
        let said = said.trim();
        let same = said == words
            || said.starts_with(&format!("{words}\n"))
            || said.ends_with(&format!("\n\n{words}"));
        same.then(|| crate::sessions::line_ms(&v)).flatten()
    })
}
