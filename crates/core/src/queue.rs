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

/// The line a message uses to name a file of the person's own for Claude
/// Code to read (after a blank line).
pub const ATTACHED: &str = "Attached file: ";

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
            // A name with a line break can't be named on one line.
            None if f.to_string_lossy().contains(['\n', '\r']) => text.push_str(&format!(
                "\n\n{ATTACHED}a file (not shared, its name has a line break)"
            )),
            None => text.push_str(&format!("\n\n{ATTACHED}{}", f.display())),
        }
    }
    (text, pictures)
}

/// Rewrites the "Attached file: <path>" lines Mímir itself put at the end of
/// a message (one per blank-line block, last blocks only); anything the
/// person typed earlier in the message is left alone.
pub fn map_attached(text: &str, f: &mut dyn FnMut(&str) -> String) -> String {
    let mut blocks: Vec<String> = text.split("\n\n").map(str::to_string).collect();
    let mut at = blocks.len();
    while at > 1 && blocks[at - 1].starts_with(ATTACHED) && !blocks[at - 1].contains('\n') {
        at -= 1;
    }
    for b in &mut blocks[at..] {
        *b = f(&b[ATTACHED.len()..]);
    }
    blocks.join("\n\n")
}

#[cfg(test)]
mod m1b_tests {
    use super::*;

    #[test]
    fn m1b_4_only_the_lines_mimir_added_are_rewritten() {
        let text = "Attached file: /etc/passwd\n\nRead these\n\nAttached file: /a/one.pdf\n\nAttached file: /a/two.pdf";
        let mut seen = vec![];
        let out = map_attached(text, &mut |p| {
            seen.push(p.to_string());
            format!("{ATTACHED}copy-of-{p}")
        });
        assert_eq!(seen, ["/a/one.pdf", "/a/two.pdf"]);
        assert!(out.starts_with("Attached file: /etc/passwd\n\nRead these"));
        assert!(out.ends_with("copy-of-/a/two.pdf"));
        let (t, _) = with_files("Hi", &[std::path::PathBuf::from("/tmp/a\nb.pdf")]);
        assert!(t.contains("not shared") && !t.contains("a\nb"));
    }
}
