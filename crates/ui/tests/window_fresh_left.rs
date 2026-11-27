//! Sessions a task holds (left behind by a fresh start): never listed from
//! elsewhere, opening one changes nothing about the task, and they stay out
//! of both lists once the task is removed.

mod common;
use common::*;
use eframe::egui;
use egui_kittest::kittest::Queryable;
use mimir_core::proto::Call;
use mimir_core::store::Store;
use mimir_core::task::{Entry, Mode, Status, Task};
use station_ui::labels;

const PAST: &str = "Plan the spring beds";
const KEPT: &str = "Sort the seed packets";
const OTHER: &str = "List the garden tools";

/// A session in Claude Code's history for the check's project.
fn put(home: &TestHome, id: &str, first: &str) {
    let project = home.project.display().to_string();
    let text = std::fs::read_to_string(session_file("terminal.jsonl"))
        .unwrap()
        .replace("@PROJECT@", &project)
        .replace("3d9f6b2e-1a4c-4e8b-b7d5-6c0a9e1f2b33", id)
        .replace("Add a line to notes.txt saying the build passed.", first);
    let folder = std::path::PathBuf::from(std::env::var_os("CLAUDE_CONFIG_DIR").unwrap())
        .join("projects")
        .join(folder_safe(&project));
    std::fs::create_dir_all(&folder).unwrap();
    std::fs::write(folder.join(format!("{id}.jsonl")), text).unwrap();
}

/// A task that left `left-past` and `left-kept` behind, those two sessions,
/// and one session nothing holds.
fn made(home: &TestHome) {
    put(home, "left-past", PAST);
    put(home, "left-kept", KEPT);
    put(home, "left-other", OTHER);
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(
        "t1".into(),
        "Garden planner".into(),
        home.project.display().to_string(),
        1,
    );
    t.entries = vec![Entry::You("Hello".into()), Entry::Claude("Done.".into())];
    t.status = Status::Done;
    t.session_id = Some("left-now".into());
    t.past_sessions = vec!["left-past".into()];
    t.kept_sessions = vec!["left-kept".into()];
    store.save(&t).unwrap();
}

fn row(first: &str) -> String {
    labels::session_row(&mimir_core::runner::title_from(first), "project")
}

fn assert_only_other_listed(h: &egui_kittest::Harness<'static, station_ui::App>, when: &str) {
    for first in [PAST, KEPT] {
        assert!(
            !has_label(h, &row(first)),
            "{when}: a session the task holds is listed ({first})\n{}",
            screen_text(h)
        );
    }
}

/// Shows the "From elsewhere" list, however many it holds.
fn show_elsewhere(h: &mut egui_kittest::Harness<'static, station_ui::App>) {
    for n in [1, 2, 3] {
        if has_label(h, &row(OTHER)) {
            return;
        }
        let words = labels::from_elsewhere(n);
        if has_label(h, &words) {
            h.query_all_by_label(&words).next().unwrap().click();
            h.step();
            h.step();
        }
    }
    assert!(
        wait_for(h, |h| has_label(h, &row(OTHER))),
        "{}",
        screen_text(h)
    );
}

#[test]
fn a_held_session_is_never_listed_from_elsewhere() {
    let Some(home) = TestHome::new() else { return };
    made(&home);
    let mut h = home.open();
    let task = labels::task_row("Garden planner", "project");
    assert!(
        wait_for(&mut h, |h| has_label(h, &task)
            && (1..=3).any(|n| has_label(h, &labels::from_elsewhere(n)))),
        "{}",
        screen_text(&h)
    );
    show_elsewhere(&mut h);
    assert_only_other_listed(&h, "with the task");

    // Removing the task keeps them out too.
    let at = h.query_all_by_label(&task).next().unwrap().rect().center();
    h.event(egui::Event::PointerMoved(at));
    h.step();
    for pressed in [true, false] {
        h.event(egui::Event::PointerButton {
            pos: at,
            button: egui::PointerButton::Secondary,
            pressed,
            modifiers: Default::default(),
        });
        h.step();
    }
    assert!(
        wait_for(&mut h, |h| has_label(h, "Delete task…")),
        "{}",
        screen_text(&h)
    );
    click(&mut h, "Delete task…");
    assert!(
        wait_for(&mut h, |h| has_button(h, "Delete")),
        "{}",
        screen_text(&h)
    );
    click(&mut h, "Delete");
    assert!(
        wait_for(&mut h, |h| !has_label(h, &task)),
        "{}",
        screen_text(&h)
    );
    for _ in 0..10 {
        h.step();
    }
    show_elsewhere(&mut h);
    assert_only_other_listed(&h, "after Remove");
    close(h);
}

/// What the service says of a task: mode, model, effort.
fn choices(
    st: &mut mimir_core::client::Station,
    id: &str,
) -> (Mode, Option<String>, Option<String>) {
    for _ in 0..20 {
        st.poll();
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    let t = &st
        .tasks
        .iter()
        .find(|v| v.task.id == id)
        .expect("the task")
        .task;
    (t.mode, t.model.clone(), t.effort.clone())
}

fn past(home: &TestHome, id: &str) -> mimir_core::sessions::PastSession {
    mimir_core::sessions::list(&home.project)
        .into_iter()
        .find(|s| s.id == id)
        .expect("the session")
}

#[test]
fn opening_a_held_session_leaves_the_tasks_choices_alone() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "defaults": { (project.clone()): { "mode": "NoAsk", "model": "opus", "effort": "high" } },
    }));
    made(&home);
    let mut st = home.service();
    let before = choices(&mut st, "t1");
    for sid in ["left-past", "left-kept"] {
        let id = st.open_past(&project, &past(&home, sid)).unwrap();
        assert_eq!(id, "t1");
        assert_eq!(choices(&mut st, "t1"), before, "opening {sid}");
    }
    assert!(home.conversations().is_empty(), "Claude was started");
    // A newly made task still gets the saved defaults.
    let id = st.open_past(&project, &past(&home, "left-other")).unwrap();
    assert_ne!(id, "t1");
    assert_eq!(
        choices(&mut st, &id),
        (Mode::NoAsk, Some("opus".into()), Some("high".into()))
    );
    home.stop_service();
}

#[test]
fn a_removed_tasks_left_behind_sessions_stay_off_the_phones_list() {
    let Some(home) = TestHome::new() else { return };
    made(&home);
    let mut st = home.service();
    st.remove("t1").unwrap();
    let list = st.call_wait(Call::PcSessions).unwrap().to_string();
    assert!(list.contains("left-other"), "{list}");
    assert!(
        !list.contains("left-past") && !list.contains("left-kept"),
        "{list}"
    );
    home.stop_service();
}
