//! Window checks for the redesigned window (docs/designs/pc-window): the
//! failure card, notices, and sizes that fit.

mod common;
use common::*;
use station_ui::labels;

/// When Claude can't start, the task says so on a card with a Try again
/// button that works, and names no button that isn't there.
#[test]
fn a_failed_start_shows_a_card_and_try_again_works() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    // Claude Code is missing at first.
    let later = home.root.join("claude-later");
    set_env("MIMIR_CLAUDE", &later);
    let mut h = home.open();
    // With Claude Code missing the window opens on a chat; a task is one click away.
    click(&mut h, "+  New task");
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, labels::DIDNT_START)
            && has_button(h, labels::TRY_AGAIN)),
        "a failed start didn't show its card with Try again\n{}",
        screen_text(&h)
    );
    let text = screen_text(&h);
    assert!(
        !text.contains("Press Continue") && !text.contains("os error"),
        "the card names a missing button or shows raw error text\n{text}"
    );
    // The raw text is one click away.
    click(&mut h, "Show details");
    assert!(
        wait_for(&mut h, |h| screen_text(h).contains("os error")),
        "Show details didn't show the details\n{}",
        screen_text(&h)
    );
    // Claude Code is there now: Try again starts it with the request.
    std::os::unix::fs::symlink(fake_claude(), &later).unwrap();
    click(&mut h, labels::TRY_AGAIN);
    let started = wait_for(&mut h, |_| {
        home.messages_to_claude()
            .iter()
            .any(|m| m.contains("Tidy up notes.txt."))
    });
    assert!(
        started,
        "Try again didn't start Claude with the request\n{}",
        screen_text(&h)
    );
    close(h);
}
