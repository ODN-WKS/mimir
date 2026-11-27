//! `tools/relay.sh`, run by a session as its last step, against a Mímir in
//! a test home: the task named by `MIMIR_TASK_ID` starts afresh in the
//! same chat.

mod common;
use common::*;
use mimir_core::store::Store;
use mimir_core::task::{Entry, Status, Task};
use std::path::PathBuf;

fn relay(task: &str) -> std::process::Output {
    let script = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tools/relay.sh");
    std::process::Command::new(script)
        .env("MIMIR_BIN", station_program())
        .env("MIMIR_TASK_ID", task)
        .output()
        .expect("relay.sh didn't run")
}

#[test]
fn relay_sh_queues_a_fresh_start_for_its_own_task() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "resume.jsonl");
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(
        "t1".into(),
        "Sort the receipts".into(),
        home.project.display().to_string(),
        0,
    );
    t.entries = vec![
        Entry::You("Sort the receipts".into()),
        Entry::Claude("Done.".into()),
    ];
    t.status = Status::Done;
    t.session_id = Some("sess-old".into());
    t.effort = Some("high".into());
    store.save(&t).unwrap();
    let mut st = home.service();

    let none = relay("no-such-task");
    assert!(!none.status.success());
    assert!(
        String::from_utf8_lossy(&none.stderr).contains("no Mímir task"),
        "{none:?}"
    );

    let out = relay("t1");
    assert!(out.status.success(), "{out:?}");
    assert_eq!(
        String::from_utf8_lossy(&out.stdout).trim(),
        "Fresh start queued for Sort the receipts"
    );
    assert!(
        service_until(&mut st, |s| s.tasks.iter().any(|v| v.task.id == "t1"
            && v.task.entries.iter().any(|e| matches!(e,
                Entry::Note(n) if n.starts_with("Fresh start at "))))),
        "no divider in the chat"
    );
    // The new session was started by itself, with the hand-off message.
    assert!(
        service_until(&mut st, |_| home
            .messages_to_claude()
            .iter()
            .any(|m| m.starts_with("Continue from the hand-off."))),
        "{:?}",
        home.messages_to_claude()
    );
}
