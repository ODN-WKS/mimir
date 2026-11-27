//! Through the service, as the window and a phone call it: which messages
//! reach Claude Code marked as typed. Each check compares the exact line.

mod common;
use common::*;
use mimir_core::client::Station;
use mimir_core::proto::{Call, ChatTurn, SentFile};
use mimir_core::task::Mode;

fn marked(text: &str) -> String {
    format!(
        r#"{{"message":{{"content":"{text}","role":"user"}},"origin":{{"kind":"human"}},"type":"user"}}"#
    )
}

fn unmarked(text: &str) -> String {
    format!(r#"{{"message":{{"content":"{text}","role":"user"}},"type":"user"}}"#)
}

fn home() -> Option<TestHome> {
    let home = TestHome::new()?;
    home.play("run-1.jsonl", "live.jsonl");
    home.play("run-2.jsonl", "live.jsonl");
    home.play("run-3.jsonl", "live.jsonl");
    home.play("resume.jsonl", "resume.jsonl");
    Some(home)
}

/// The `n`th line Claude Code was given, with the turn over.
fn line(st: &mut Station, home: &TestHome, n: usize) -> String {
    assert!(
        service_until(st, |s| home.input_lines().len() >= n
            && !s.tasks.iter().any(|v| v.busy())),
        "no line {n}: {:?}",
        home.input_lines()
    );
    home.input_lines()[n - 1].clone()
}

fn start(st: &mut Station, home: &TestHome, text: &str) -> String {
    st.new_task(
        &home.project.display().to_string(),
        text,
        &[],
        Mode::Ask,
        None,
        None,
        false,
    )
    .expect("the task didn't start")
}

fn send(task: &str, text: &str, typed: bool, files: Vec<SentFile>) -> Call {
    Call::Send {
        task: task.into(),
        text: text.into(),
        pictures: vec![],
        files,
        msg_id: None,
        typed,
    }
}

#[test]
fn a_new_task_and_replies_are_marked_only_when_typed() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    st.next_typed = true;
    let id = start(&mut st, &home, "Tidy up notes.txt.");
    assert_eq!(line(&mut st, &home, 1), marked("Tidy up notes.txt."));

    // What a program (or an older app) sends carries no mark.
    st.send_with(&id, "From a script.", &[]);
    assert_eq!(line(&mut st, &home, 2), unmarked("From a script."));

    st.call(send(&id, "Typed on the phone.", true, vec![]));
    assert_eq!(line(&mut st, &home, 3), marked("Typed on the phone."));

    // A file with no words: the line holds only Mímir's "Attached" line.
    let file = SentFile {
        name: "list.txt".into(),
        media_type: "text/plain".into(),
        base64: "aGkK".into(),
    };
    st.call(send(&id, "", true, vec![file]));
    let l = line(&mut st, &home, 4);
    assert!(l.contains("list.txt") && !l.contains("origin"), "{l}");

    // A follow-up typed in the chat, filed into the open task.
    st.call(Call::Talk {
        chat_id: "c1".into(),
        turn_id: "m1".into(),
        turns: vec![ChatTurn {
            id: "m1".into(),
            user: true,
            text: "and add a heading".into(),
            sources: vec![],
            ran: None,
            model_words: None,
            at: 1,
            version: 1,
            seq: 0,
            pictures: vec![],
            files: vec![],
            switch_note: None,
            gone: Default::default(),
        }],
        lookup: None,
        sources: vec![],
        effort: None,
        ground_rules: None,
        task: Some(id.clone()),
        chat_only: false,
        typed: true,
    });
    assert_eq!(line(&mut st, &home, 5), marked("and add a heading"));
}

#[test]
fn a_general_chat_message_carries_its_mark_into_the_task_it_starts() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    st.call(Call::Chat {
        msg_id: "m-1".into(),
        text: "write a short poem".into(),
        ground_rules: None,
        typed: true,
    });
    assert_eq!(line(&mut st, &home, 1), marked("write a short poem"));
    st.call(Call::Chat {
        msg_id: "m-2".into(),
        text: "make it shorter".into(),
        ground_rules: None,
        typed: false,
    });
    assert_eq!(line(&mut st, &home, 2), unmarked("make it shorter"));
}

/// Queued while Claude works: marked only if every joined part was typed.
fn queued(parts: &[(&str, bool)], expect: String) {
    let Some(home) = home() else { return };
    home.hold_replies();
    let mut st = home.service();
    let id = start(&mut st, &home, "Tidy up notes.txt.");
    assert!(service_until(&mut st, |s| s.tasks.iter().any(|v| v.busy())));
    for (text, typed) in parts {
        st.next_typed = *typed;
        st.queue(&id, text, vec![]);
    }
    home.let_through(1000);
    assert_eq!(line(&mut st, &home, 2), expect);
}

#[test]
fn a_queued_reply_of_typed_parts_is_marked() {
    queued(
        &[("First.", true), ("Second.", true)],
        marked(r"First.\nSecond."),
    );
}

#[test]
fn a_queued_reply_with_an_untyped_first_part_is_not_marked() {
    queued(
        &[("First.", false), ("Second.", true)],
        unmarked(r"First.\nSecond."),
    );
}

#[test]
fn a_queued_reply_with_an_untyped_last_part_is_not_marked() {
    queued(
        &[("First.", true), ("Second.", false)],
        unmarked(r"First.\nSecond."),
    );
}
