//! A task that Claude just answered says nothing about a missing plan; one
//! that has no good answer still does.

mod common;
use common::*;
use mimir_core::store::Store;
use mimir_core::task::{Entry, Status, Task};
use station_ui::labels;

#[test]
fn an_answered_task_says_nothing_about_a_missing_plan() {
    let Some(home) = TestHome::new() else { return };
    set_env("MIMIR_CLAUDE", home.root.join("no-such-claude"));
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let folder = home.project.display().to_string();
    for (id, title, failed) in [
        ("a", "Sort the receipts", false),
        ("b", "Fix the gate", true),
    ] {
        let mut t = Task::new(id.into(), title.into(), folder.clone(), 0);
        t.entries = vec![
            Entry::You("Please help".into()),
            Entry::Claude("Sure.".into()),
        ];
        t.status = if failed {
            Status::Stopped
        } else {
            Status::Done
        };
        t.failed = failed;
        store.save(&t).unwrap();
    }
    let mut h = home.open();
    let name = home
        .project
        .file_name()
        .unwrap()
        .to_string_lossy()
        .to_string();
    // The one that failed first: the line shows once Claude has been looked for.
    let bad = labels::task_row("Fix the gate", &name);
    assert!(wait_for(&mut h, |h| shows(h, &bad)), "{}", screen_text(&h));
    click(&mut h, &bad);
    assert!(
        wait_for(&mut h, |h| shows(h, "no Anthropic plan is connected")),
        "{}",
        screen_text(&h)
    );
    click(&mut h, &labels::task_row("Sort the receipts", &name));
    for _ in 0..20 {
        h.step();
    }
    assert!(shows(&h, "Sure."), "{}", screen_text(&h));
    assert!(
        !shows(&h, "no Anthropic plan is connected"),
        "{}",
        screen_text(&h)
    );
    close(h);
}
