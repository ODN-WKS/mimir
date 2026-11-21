//! Window checks for the core features the owner uses most: starting a
//! task (CF-1), following up (CF-2), Allow / Don't allow (CF-3) and
//! Claude's questions (CF-4). Each drives the real window against the
//! stand-in Claude in a private home; see `common/mod.rs`.

mod common;
use common::*;
use station_ui::labels;

/// The conversation the reopened-task check starts (from `live.jsonl`).
const LIVE_SESSION: &str = "5f1c2e7a-6b3d-4c8e-9a10-11ee0c0f1a2b";

#[test]
fn cf1_typed_request_starts_claude() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    let started = wait_for(&mut h, |_| !home.conversations().is_empty());
    assert!(started, "pressed Send; Claude wasn't started");
    let request = wait_for(&mut h, |_| {
        home.messages_to_claude()
            .iter()
            .any(|m| m.contains("Tidy up notes.txt."))
    });
    assert!(
        request,
        "pressed Send; Claude wasn't started with the request"
    );
    close(h);
}

#[test]
fn cf1_reply_shows_while_written() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    // Lines 1-3 of 6: the start and the first two pieces of the reply.
    home.hold_replies();
    home.let_through(3);
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    let shown = wait_for(&mut h, |h| shows(h, "The tidy-up is under way"));
    assert!(
        shown && !shows(&h, "notes.txt is sorted"),
        "reply wasn't shown until Claude finished\n{}",
        screen_text(&h)
    );
    home.let_through(6);
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    close(h);
}

#[test]
fn cf2_message_while_working_is_answered() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "midturn.jsonl");
    let mut h = home.open();
    start_task(
        &mut h,
        "Run the Bash command: sleep 8 && echo slept . Then reply short.",
    );
    // Claude is working: the message box offers Send now.
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::SEND_NOW)),
        "the task never showed as working\n{}",
        screen_text(&h)
    );
    type_message(&mut h, "Also, after that, tell me what 2+2 is.");
    click(&mut h, labels::SEND_NOW);
    let answered = wait_for(&mut h, |h| shows(h, "2+2 is 4"));
    assert!(
        answered,
        "second message got no answer\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn cf2_reopened_task_continues() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.play("resume.jsonl", "resume.jsonl");
    let mut first = home.open();
    start_task(&mut first, "Tidy up notes.txt.");
    assert!(wait_for(&mut first, |h| shows(h, "notes.txt is sorted")));
    // Wait for Claude to finish before closing, as the owner would.
    assert!(wait_for(&mut first, |h| has_button(h, labels::SEND)));
    close(first);
    // The service goes on without the window, and the next window shows
    // the same task.
    assert!(
        home.service_pid().is_some(),
        "the service stopped with the window"
    );

    let mut second = home.open();
    type_message(
        &mut second,
        "What file did you create earlier and what is in it? One line.",
    );
    click(&mut second, labels::SEND);
    let replied = wait_for(&mut second, |h| shows(h, "I created"));
    let runs = home.conversations();
    let resumed = runs.last().is_some_and(|a| {
        a.windows(2)
            .any(|w| w[0] == "--resume" && w[1] == LIVE_SESSION)
    });
    assert!(
        replied && resumed && runs.len() == 2,
        "reopened task started a new conversation\nruns: {runs:?}\n{}",
        screen_text(&second)
    );
    close(second);
}

#[test]
fn cf3_allow_reaches_claude() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "allow.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Use the Bash tool to run: touch made-by-test.txt");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::ALLOW)),
        "Claude's request never showed\n{}",
        screen_text(&h)
    );
    click(&mut h, labels::ALLOW);
    wait_for(&mut h, |h| shows(h, "made-by-test.txt is there"));
    let answers = home.hook_answers();
    assert!(
        answers.first().map(String::as_str) == Some("allow"),
        "clicked Allow; Claude was told: {}",
        told(answers.first())
    );
    close(h);
}

#[test]
fn cf3_dont_allow_reaches_claude() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "deny.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Use the Bash tool to run: touch made-by-test.txt");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::DONT_ALLOW)),
        "Claude's request never showed\n{}",
        screen_text(&h)
    );
    click(&mut h, labels::DONT_ALLOW);
    wait_for(&mut h, |h| shows(h, "Denied."));
    let answers = home.hook_answers();
    assert!(
        answers.first().map(String::as_str) == Some("deny"),
        "clicked Don't allow; Claude went ahead (it was told: {})",
        told(answers.first())
    );
    close(h);
}

#[test]
fn cf3_always_allow_stops_asking() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "always.jsonl");
    let mut h = home.open();
    start_task(
        &mut h,
        "Run this Bash command: touch a1.txt  Then run: touch a2.txt",
    );
    // "Always allow" sits under More, and says what it stops asking about
    // before it is given.
    let always = labels::always_allow_in("project");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::MORE)),
        "Claude's request never showed\n{}",
        screen_text(&h)
    );
    click(&mut h, labels::MORE);
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::ALWAYS_ALLOW_MORE)),
        "More doesn't offer Always allow\n{}",
        screen_text(&h)
    );
    click(&mut h, labels::ALWAYS_ALLOW_MORE);
    assert!(
        wait_for(&mut h, |h| has_button(h, &always)
            && shows(h, "Mímir will no longer ask before")),
        "Always allow doesn't say what it stops asking about\n{}",
        screen_text(&h)
    );
    click(&mut h, &always);
    // The second command of the same kind runs without asking: Claude was
    // given the rule, and the window shows no second request.
    let mut asked_again = false;
    let done = wait_for(&mut h, |h| {
        asked_again |= has_button(h, labels::ALLOW);
        shows(h, "I created both files")
    });
    let answers = home.hook_answers();
    assert!(
        done && !asked_again && answers == ["always"],
        "asked again after Always allow (Claude was told: {})",
        told(answers.first())
    );
    close(h);
}

#[test]
fn cf4_question_card_shows() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "question.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    let card = labels::question_card(
        "Should I also sort the notes alphabetically, or keep them in this order?",
    );
    let shown = wait_for(&mut h, |h| has_label(h, &card));
    assert!(
        shown,
        "Claude's question didn't show in the question card\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn cf4_answer_reaches_claude() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "question.jsonl");
    home.play("resume.jsonl", "resume.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(wait_for(&mut h, |h| shows(h, labels::CLAUDE_ASKS_YOU)));
    assert!(wait_for(&mut h, |h| has_button(h, labels::SEND)));
    type_message(&mut h, "Keep them in this order.");
    click(&mut h, labels::SEND);
    let reached = wait_for(&mut h, |_| {
        home.messages_to_claude()
            .iter()
            .any(|m| m.contains("Keep them in this order."))
    });
    assert!(
        reached,
        "the answer didn't reach Claude\nClaude got: {:?}",
        home.messages_to_claude()
    );
    close(h);
}

/// A stray key never answers Claude's request: a bare Enter, a letter or
/// Esc does nothing to the card. Ctrl+Enter allows.
#[test]
fn a_stray_key_never_answers_a_request() {
    use eframe::egui::{Key, Modifiers};
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "allow.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Use the Bash tool to run: touch made-by-test.txt");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::ALLOW)),
        "Claude's request never showed\n{}",
        screen_text(&h)
    );
    for key in [Key::Enter, Key::A, Key::N, Key::Escape] {
        h.key_press(key);
        for _ in 0..4 {
            h.step();
        }
    }
    assert!(
        has_button(&h, labels::ALLOW) && home.hook_answers().is_empty(),
        "a bare key answered the request (Claude was told: {})",
        told(home.hook_answers().first())
    );
    assert!(
        shows(&h, labels::ANSWER_KEYS) || screen_text(&h).contains("Ctrl+Enter allows"),
        "the card doesn't say which keys answer it\n{}",
        screen_text(&h)
    );
    h.key_press_modifiers(Modifiers::CTRL, Key::Enter);
    wait_for(&mut h, |h| shows(h, "made-by-test.txt is there"));
    let answers = home.hook_answers();
    assert!(
        answers.first().map(String::as_str) == Some("allow"),
        "pressed Ctrl+Enter; Claude was told: {}",
        told(answers.first())
    );
    close(h);
}

/// Ctrl+D is "Don't allow" while a card asks.
#[test]
fn ctrl_d_says_dont_allow() {
    use eframe::egui::{Key, Modifiers};
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "deny.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Use the Bash tool to run: touch made-by-test.txt");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::DONT_ALLOW)),
        "Claude's request never showed\n{}",
        screen_text(&h)
    );
    h.key_press_modifiers(Modifiers::CTRL, Key::D);
    wait_for(&mut h, |h| shows(h, "Denied."));
    let answers = home.hook_answers();
    assert!(
        answers.first().map(String::as_str) == Some("deny"),
        "pressed Ctrl+D; Claude was told: {}",
        told(answers.first())
    );
    close(h);
}

/// A command that sends something off this PC is shown in full, with a note
/// that it can't be undone.
#[test]
fn a_command_that_leaves_the_pc_is_shown_and_marked() {
    let note = "Leaves this PC · can't be undone";
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "push.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Push the main branch");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::ALLOW)),
        "Claude's request never showed\n{}",
        screen_text(&h)
    );
    assert!(
        shows(&h, "git push origin main") && shows(&h, note),
        "a push isn't shown in full with its note\n{}",
        screen_text(&h)
    );
    click(&mut h, labels::ALLOW);
    wait_for(&mut h, |h| shows(h, "Pushed."));
    close(h);
}
