//! QA12 phone re-hunt (H2) P6 and P7: "Start fresh with a summary" gives the
//! new task a plain title and one short first message (the summary goes to
//! Claude, not into the conversation), and the old task says it was carried
//! on, not that the person stopped it.

mod common;
use common::*;
use mimir_core::proto::Call;
use mimir_core::store::Store;
use mimir_core::task::{Entry, Status, Task};

#[test]
fn p6_p7_start_fresh_reads_plainly_on_both_tasks() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "resume.jsonl");
    let project = home.project.clone();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
    }));
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(
        "t1".into(),
        "Sort the receipts".into(),
        project.display().to_string(),
        0,
    );
    t.entries = vec![
        Entry::You("Sort the receipts".into()),
        Entry::Claude("Done.".into()),
    ];
    t.status = Status::Done;
    t.context_window = 200_000;
    t.context_used = 160_000;
    store.save(&t).unwrap();
    let mut st = home.service();
    let id = st
        .call_wait(Call::StartFresh {
            task: "t1".into(),
            same_chat: false,
        })
        .expect("start fresh was refused");
    let id = id.as_str().unwrap().to_string();
    assert!(
        service_until(&mut st, |s| s.tasks.iter().any(|v| v.task.id == id)),
        "no new task"
    );
    let new = st
        .tasks
        .iter()
        .find(|v| v.task.id == id)
        .unwrap()
        .task
        .clone();
    // P6: a plain title, a short first message, a small note; no path or step.
    assert_eq!(new.title, "Sort the receipts (continued)");
    let all = format!("{:?}", new.entries);
    for bad in ["Project folder", "Step:", "taking over", "/"] {
        assert!(!all.contains(bad), "{bad:?} is shown: {all}");
    }
    assert!(
        matches!(&new.entries[0], Entry::You(x) if x == "Carry on with this task from the summary."),
        "{all}"
    );
    assert!(all.contains("Carried on from a short summary of"), "{all}");
    // The summary still goes to Claude.
    assert!(
        service_until(&mut st, |_| home.messages_to_claude().iter().any(|m| m
            .contains("Project folder")
            && m.contains("Now the owner says"))),
        "Claude never got the summary: {:?}",
        home.messages_to_claude()
    );
    // P7: the old task says it was carried on, did not get stopped, and its
    // long-conversation card is gone.
    let old = st
        .tasks
        .iter()
        .find(|v| v.task.id == "t1")
        .unwrap()
        .task
        .clone();
    assert!(
        matches!(old.entries.last(), Some(Entry::Note(n)) if n == "Carried on in a fresh task."),
        "{:?}",
        old.entries
    );
    assert!(!format!("{:?}", old.entries).contains("You stopped this task"));
    assert_eq!(old.status, Status::Done);
    assert!(
        mimir_core::long::notice(&old).is_none(),
        "the card is still offered"
    );
}
