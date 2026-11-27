//! P34: the PC window lists projects, chats and tasks newest first by the
//! person's last message.

mod common;
use common::*;
use mimir_core::store::Store;
use mimir_core::task::{Entry, EntryMeta, Status, Task};
use station_ui::labels;

fn saved(store: &Store, id: &str, title: &str, project: &str, you: i64, reply: i64) {
    let mut t = Task::new(id.into(), title.into(), project.into(), 100);
    t.entries = vec![Entry::You(title.into()), Entry::Claude("Done.".into())];
    t.entry_meta = [you, reply]
        .map(|s| EntryMeta {
            at: Some(s * 1000),
            ..Default::default()
        })
        .to_vec();
    t.status = Status::Done;
    store.save(&t).unwrap();
}

#[test]
fn projects_and_tasks_are_newest_first_by_the_persons_message() {
    let Some(home) = TestHome::new() else { return };
    let dir = |name: &str| {
        let p = home.root.join(name);
        std::fs::create_dir_all(&p).unwrap();
        p.display().to_string()
    };
    let (garden, recipes) = (dir("Garden planner"), dir("Recipe box"));
    // Listed the other way round, as a saved hand order would be.
    home.write_settings(serde_json::json!({
        "projects": [garden, recipes],
        "projects_folded": false,
        "remote_projects": [],
    }));
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    // Garden's reply is the newest thing of all; Recipe box has the newest
    // message of the person, in its first task.
    saved(&store, "t1", "Plan the beds", &garden, 200, 9_000);
    saved(&store, "t2", "Soup for six", &recipes, 500, 600);
    saved(&store, "t3", "Bread list", &recipes, 300, 8_000);
    home.tripwire();
    let mut h = home.open();
    let soup = labels::task_row("Soup for six", "Recipe box");
    assert!(wait_for(&mut h, |h| shows(h, &soup)), "{}", screen_text(&h));
    for _ in 0..30 {
        h.step();
    }
    use egui_kittest::kittest::Queryable;
    let y = |label: &str| h.get_by_label(label).rect().top();
    assert!(
        y(&labels::folder_row("Recipe box")) < y(&labels::folder_row("Garden planner")),
        "the project with the newest message is first"
    );
    assert!(
        y(&soup) < y(&labels::task_row("Bread list", "Recipe box")),
        "a later reply moves no task"
    );
    close(h);
}
