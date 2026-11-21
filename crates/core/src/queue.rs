//! Messages that wait until Claude is done, and the short summary asked
//! for when a task finishes.

use crate::task::{Entry, Status};

/// Asked when a task finishes after doing work (when the owner wants it).
pub const SUMMARY_PROMPT: &str = "In a few plain sentences: what did you change, what did you \
                                  check, and is anything left for me to do?";

/// What to do with a message that waits for Claude to be done.
#[derive(Debug, PartialEq)]
pub enum Waiting {
    /// Claude is still at it.
    Wait,
    Send,
    /// It stays until the owner decides; why, in plain words.
    Hold(&'static str),
}

pub fn waiting_now(status: Status, asks: bool, question: bool, plan: bool) -> Waiting {
    match status {
        Status::Working | Status::NeedsYou => Waiting::Wait,
        _ if asks => Waiting::Wait,
        Status::Paused => Waiting::Hold("The task is paused, so this was not sent."),
        Status::Stopped => Waiting::Hold("The task stopped, so this was not sent."),
        Status::Done if plan => Waiting::Hold("Mímir wrote a plan first, so this was not sent."),
        Status::Done if question => {
            Waiting::Hold("Mímir asked you something first, so this was not sent.")
        }
        Status::Done => Waiting::Send,
    }
}

/// Whether the turn that just finished is worth a summary: Claude did
/// things (not only talked), and it was not itself a summary.
pub fn wants_summary(entries: &[Entry]) -> bool {
    let Some(last_you) = entries.iter().rposition(|e| matches!(e, Entry::You(_))) else {
        return false;
    };
    if matches!(&entries[last_you], Entry::You(t) if t.trim() == SUMMARY_PROMPT) {
        return false;
    }
    entries[last_you..]
        .iter()
        .any(|e| matches!(e, Entry::Step(s) if !s.refused))
}

/// Splits attachments into pictures (sent to Claude) and other files
/// (named in the message, so Claude can read them).
pub fn with_files(
    text: &str,
    files: &[std::path::PathBuf],
) -> (String, Vec<crate::events::Picture>) {
    let mut pictures = vec![];
    let mut text = text.to_string();
    for f in files {
        match crate::events::picture_from_file(f) {
            Some(p) => pictures.push(p),
            None => text.push_str(&format!("\n\nAttached file: {}", f.display())),
        }
    }
    (text, pictures)
}
