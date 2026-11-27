//! P34: lists are ordered by the person's last message. Replies, steps,
//! finishes and Mímir's own messages never move anything.

use mimir_core::order;
use mimir_core::proto::{ChatRecord, ChatTurn, TaskHeader};
use mimir_core::task::{Entry, Task};

fn task(id: &str, created: i64) -> Task {
    Task::new(id.into(), id.into(), "/work/Garden planner".into(), created)
}

fn pause() {
    std::thread::sleep(std::time::Duration::from_millis(5));
}

#[test]
fn only_the_persons_message_moves_a_task() {
    let (mut a, mut b) = (task("a", 100), task("b", 200));
    b.add_you("Plan the beds");
    pause();
    a.add_you("Water the herbs");
    let sent = a.you_ms.expect("the person's message is remembered");
    pause();
    b.add_claude("Here is a plan.".into());
    b.add_note("A helper finished");
    assert!(order::task_key(&a) > order::task_key(&b), "A stays on top");
    // Mímir's own words, though they stand where the person's do.
    pause();
    let before = b.you_ms;
    b.add_you("Please continue where you left off.");
    b.add_you(mimir_core::queue::SUMMARY_PROMPT);
    b.add_own("The limit ran out. Carry on.");
    assert_eq!(b.you_ms, before);
    assert_eq!(a.you_ms, Some(sent));
}

#[test]
fn no_message_of_the_person_means_creation_time() {
    assert_eq!(order::task_key(&task("a", 100)), 100_000);
}

#[test]
fn a_project_counts_the_newest_message_in_any_of_its_tasks() {
    let (mut old, mut new, none) = (task("a", 1), task("b", 2), task("c", 9_000));
    old.you_ms = Some(5_000);
    new.you_ms = Some(7_000);
    assert_eq!(order::project_key([&old, &new, &none]), 7_000);
    // Only without any message does creation count.
    assert_eq!(order::project_key([&none]), 9_000_000);
    assert_eq!(order::project_key([]), 0);
}

fn turn(id: &str, user: bool, at: i64) -> ChatTurn {
    serde_json::from_value(serde_json::json!({
        "id": id, "user": user, "text": "x", "at": at, "version": 1, "seq": 1
    }))
    .unwrap()
}

#[test]
fn a_chat_is_as_new_as_the_persons_last_message() {
    let c = ChatRecord {
        at: 9_000,
        turns: vec![turn("m", true, 1_000), turn("m.a", false, 9_000)],
        ..Default::default()
    };
    assert_eq!(order::chat_key(&c), 1_000);
    let none = ChatRecord {
        at: 9_000,
        turns: vec![turn("m.a", false, 2_000), turn("n.a", false, 9_000)],
        ..Default::default()
    };
    assert_eq!(order::chat_key(&none), 2_000, "when it began");
}

/// Existing data: tasks saved before this have no remembered time.
#[test]
fn saved_tasks_order_from_their_history() {
    // Times beside the messages; the reply's later time doesn't count.
    let mut a = task("a", 100);
    a.add_you("Water the herbs");
    a.add_claude("Done.".into());
    a.add_own("Carry on.");
    a.entry_meta[0].at = Some(500_000);
    a.entry_meta[1].at = Some(900_000);
    a.entry_meta[2].at = Some(950_000);
    a.entries[2] = Entry::You(mimir_core::queue::SUMMARY_PROMPT.into());
    a.you_ms = None;
    order::backfill(&mut a, None);
    assert_eq!(a.you_ms, Some(500_000));

    // Read from a session's own file: no times kept, but the file has them.
    let mut b = task("b", 100);
    b.entries = vec![
        Entry::You("first".into()),
        Entry::You("second".into()),
        Entry::Claude("ok".into()),
    ];
    let line = |ts: &str, who: &str, text: &str| {
        serde_json::json!({"type": who, "timestamp": ts,
            "message": {"role": who, "content": text}})
        .to_string()
    };
    let transcript = [
        line("2026-11-20T10:00:00.000Z", "user", "first"),
        line("2026-11-20T11:00:00.000Z", "user", "second"),
        line("2026-11-20T12:00:00.000Z", "assistant", "second"),
        line(
            "2026-11-20T13:00:00.000Z",
            "user",
            "Please continue where you left off.",
        ),
    ]
    .join("\n");
    order::backfill(&mut b, Some(&transcript));
    assert_eq!(b.you_ms, Some(1_795_172_400_000));

    // Nothing says when: creation time stands.
    let mut c = task("c", 100);
    c.entries = vec![Entry::You("first".into())];
    order::backfill(&mut c, None);
    assert_eq!(c.you_ms, None);
    assert_eq!(order::task_key(&c), 100_000);
}

#[test]
fn the_time_is_saved_and_older_files_and_pcs_still_read() {
    let mut a = task("a", 100);
    a.add_you("hello");
    let saved = serde_json::to_value(&a).unwrap();
    let back: Task = serde_json::from_value(saved.clone()).unwrap();
    assert_eq!(back.you_ms, a.you_ms);
    let mut old = saved;
    old.as_object_mut().unwrap().remove("you_ms");
    let back: Task = serde_json::from_value(old).unwrap();
    assert_eq!(back.you_ms, None);
    // An older PC sends no such field in a task's header.
    let h: TaskHeader = serde_json::from_value(serde_json::json!({
        "id": "a", "title": "a", "title_by_owner": false, "project": "/p",
        "origin": null, "copy_branch": null, "session_id": null, "status": "Done",
        "mode": "Ask", "model": null, "auto_choice": null, "effort": null, "plan": [],
        "plan_doc": null, "files_changed": [], "cost_usd": 0.0, "weekly": null,
        "context_used": 0, "context_window": 0, "created_unix": 100, "brief": null,
        "checkpoints": [], "redo": false, "question_dismissed": null, "archived": false,
        "incognito": false, "entry_count": 0, "running": false, "busy": false,
        "asks": [], "queued": null
    }))
    .expect("an older header reads");
    assert_eq!(h.you_ms, None);
}
