//! D1: a working task always says what it is doing and for how long, never a bare spinner.

mod common;
use common::*;

#[test]
fn a_working_task_says_what_it_is_doing_and_for_how_long() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    home.hold_replies();
    home.let_through(1);
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "Working on it…")),
        "no working line\n{}",
        screen_text(&h)
    );
    assert!(
        wait_for(&mut h, |h| shows(h, "Thinking… ·")
            || shows(h, "Reading ")
            || shows(h, "Running a command")
            || shows(h, "Writing ")
            || shows(h, "Changing ")),
        "no line saying what it is doing\n{}",
        screen_text(&h)
    );
    home.let_through(100);
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    close(h);
}
