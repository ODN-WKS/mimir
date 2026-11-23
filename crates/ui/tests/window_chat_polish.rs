//! R5e: a chat looks like a chat. No task panel, no undo line, no
//! permission chip, no state line or breadcrumb; a task keeps all of it.

mod common;
use common::*;
use mimir_core::store::Store;
use mimir_core::task::{Entry, Status, Task};
use station_ui::labels;

fn home() -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "details": true,
    }));
    Some(home)
}

fn save(home: &TestHome, id: &str, title: &str, folder: &str, model: Option<&str>) {
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(id.into(), title.into(), folder.into(), 0);
    t.model = model.map(str::to_string);
    t.entries = vec![
        Entry::You(format!("Please help with {title}")),
        Entry::Claude("Sure.".into()),
    ];
    t.status = Status::Done;
    store.save(&t).unwrap();
}

#[test]
fn a_pc_chat_has_no_task_things_and_a_task_keeps_them() {
    let Some(home) = home() else { return };
    let chats = mimir_core::paths::chats_dir();
    std::fs::create_dir_all(&chats).unwrap();
    save(
        &home,
        "c1",
        "Recipe box ideas",
        &chats.display().to_string(),
        Some("ollama:qwen3.6:35b-a3b-coding"),
    );
    let project = home.project.clone();
    save(
        &home,
        "t1",
        "Sort the receipts",
        &project.display().to_string(),
        None,
    );
    home.tripwire();
    let mut h = home.open();
    let chat_row = labels::task_row("Recipe box ideas", "Chats");
    assert!(
        wait_for(&mut h, |h| shows(h, &chat_row)),
        "{}",
        screen_text(&h)
    );
    click(&mut h, &chat_row);
    assert!(
        wait_for(&mut h, |h| shows(h, "Sure.")),
        "{}",
        screen_text(&h)
    );
    for _ in 0..10 {
        h.step();
    }
    let text = screen_text(&h);
    for gone in [
        "THIS TASK",
        "CHANGES SO FAR",
        "PERMISSION",
        "Ask me first",
        "Undo changes from here",
        "Effort: none",
    ] {
        assert!(!shows(&h, gone), "chat still shows {gone:?}\n{text}");
    }
    assert!(!has_label(&h, "Done"), "state line on a chat\n{text}");
    assert!(
        !has_label(&h, "Recipe box ideas"),
        "breadcrumb on a chat\n{text}"
    );
    assert!(shows(&h, "THIS PC"), "no PC panel\n{text}");
    assert!(shows(&h, "Effort: not used"), "no dash effort\n{text}");

    // A task is unchanged.
    let name = project.file_name().unwrap().to_string_lossy().to_string();
    click(&mut h, &labels::task_row("Sort the receipts", &name));
    for want in ["THIS TASK", "Ask me first", "CHANGES SO FAR"] {
        assert!(
            wait_for(&mut h, |h| shows(h, want)),
            "task lost {want:?}\n{}",
            screen_text(&h)
        );
    }
    assert!(!shows(&h, "THIS PC"), "{}", screen_text(&h));
    close(h);
}
