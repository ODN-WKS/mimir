//! The right panel (how this PC is doing, what is running, a task's own
//! state), the chat box's plain-word chips, the removed bottom pill and the
//! service's plain description.

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

#[test]
fn an_empty_chat_shows_this_pc_and_what_is_running() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    click(&mut h, labels::NEW_CHAT);
    for _ in 0..5 {
        h.step();
    }
    for want in ["THIS PC", "Processor", "Memory", "DOING NOW"] {
        assert!(shows(&h, want), "missing {want:?}\n{}", screen_text(&h));
    }
    assert!(
        shows(&h, "Your PC's model is ready") || shows(&h, "Your PC's model starts when you send"),
        "no model line\n{}",
        screen_text(&h)
    );
    assert!(!shows(&h, "THIS TASK"), "{}", screen_text(&h));
    // The toggle is there too.
    assert!(has_button(&h, labels::HIDE_PANEL), "{}", screen_text(&h));
    close(h);
}

#[test]
fn a_finished_task_shows_its_state_changes_and_permission() {
    let Some(home) = home() else { return };
    let project = home.project.clone();
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
    store.save(&t).unwrap();
    home.tripwire();
    let mut h = home.open();
    let name = project.file_name().unwrap().to_string_lossy().to_string();
    let row = labels::task_row("Sort the receipts", &name);
    wait_for(&mut h, |h| shows(h, &row));
    click(&mut h, &row);
    for want in [
        "THIS TASK",
        "Done",
        "CHANGES SO FAR",
        "No files changed yet",
        "PERMISSION",
        "Ask me first",
    ] {
        assert!(
            wait_for(&mut h, |h| shows(h, want)),
            "missing {want:?}\n{}",
            screen_text(&h)
        );
    }
    // Nothing to stop, and no machine bars on a task.
    assert!(!shows(&h, "Stop this task"), "{}", screen_text(&h));
    assert!(!shows(&h, "THIS PC"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn a_running_task_offers_stop_and_the_chat_box_uses_plain_words() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    home.hold_replies();
    home.let_through(1);
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "Stop this task")
            && shows(h, "Working")),
        "{}",
        screen_text(&h)
    );
    for bad in ["Default effort", "Model: "] {
        assert!(!shows(&h, bad), "old words {bad:?}\n{}", screen_text(&h));
    }
    assert!(shows(&h, "Effort: Medium"), "{}", screen_text(&h));
    assert!(shows(&h, "Auto-pick"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn the_chat_box_chips_are_plain_words_and_the_old_pill_is_gone() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    click(&mut h, labels::NEW_CHAT);
    for _ in 0..5 {
        h.step();
    }
    for bad in [
        "Default effort",
        "Model: ",
        "Nothing needs you",
        "need you",
        "working",
    ] {
        // "Needs you" (the row) is fine; the pill's words are not.
        assert!(
            !shows(&h, bad) || bad == "need you" || bad == "working",
            "old words {bad:?}\n{}",
            screen_text(&h)
        );
    }
    assert!(shows(&h, "Effort: Medium"), "{}", screen_text(&h));
    assert!(!shows(&h, "Nothing needs you"));
    assert!(!shows(&h, "Needs you"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn the_apps_menu_entry_and_description_are_in_plain_words() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../packaging/flatpak");
    for f in [
        "io.github.odnwks.Mimir.desktop",
        "io.github.odnwks.Mimir.metainfo.xml.in",
    ] {
        let t = std::fs::read_to_string(dir.join(f)).unwrap();
        for bad in [concat!("Claude", " Station"), "Phone too", "Remote Control"] {
            assert!(!t.contains(bad), "{f} says {bad:?}");
        }
    }
    let desktop = std::fs::read_to_string(dir.join("io.github.odnwks.Mimir.desktop")).unwrap();
    assert!(
        desktop
            .lines()
            .any(|l| l == "Comment=Ask anything. Private by design."),
        "{desktop}"
    );
}
