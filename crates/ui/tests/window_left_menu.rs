//! The window's left menu (redesign R5a): search, the two start buttons,
//! Phones, Whisper, then Chats and Projects as folding sections, and
//! Needs you and Updates (only when there is something) and Settings at the
//! bottom. A running task is listed once.

mod common;
use common::*;
use eframe::egui::{self, accesskit::Role};
use egui_kittest::Harness;
use egui_kittest::kittest::Queryable;
use station_ui::App;

/// Where the button with exactly these words sits on screen (top edge).
fn top(h: &Harness<'static, App>, words: &str) -> Option<f32> {
    h.query_all_by_role_and_label(Role::Button, words)
        .next()
        .map(|n| n.rect().top())
}

/// The buttons that are task rows with this title in their spoken name.
fn task_rows(h: &Harness<'static, App>, title: &str) -> usize {
    h.query_all_by(|n| {
        n.role() == egui::accesskit::Role::Button
            && n.label()
                .is_some_and(|l| l.starts_with("Task ") && l.contains(title))
    })
    .count()
}

#[test]
fn the_menu_runs_in_the_designed_order() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    // "Needs you" and "Updates" show only when there is something.
    let names = ["Phones", "Whisper", "Chats", "Projects", "Settings"];
    assert!(
        wait_for(&mut h, |h| names.iter().all(|n| top(h, n).is_some())),
        "the menu is incomplete\n{}",
        screen_text(&h)
    );
    // The two start buttons share a line, above the rows.
    let chat = top(&h, "New chat").expect("no New chat button");
    let task = top(&h, "+  New task").expect("no New task button");
    assert_eq!(chat, task, "the two start buttons are not side by side");
    let mut at = vec![chat];
    at.extend(names.iter().map(|n| top(&h, n).unwrap()));
    assert!(
        at.windows(2).all(|w| w[0] < w[1]),
        "wrong order: {names:?} at {at:?}"
    );
    // No "Active now" section, no tabs for the two sides.
    assert!(!shows(&h, "Active now"), "{}", screen_text(&h));
    assert!(!has_button(&h, "claude.ai"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn sections_fold_and_stay_folded() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.display().to_string();
    let mut h = home.open();
    assert!(wait_for(&mut h, |h| top(h, "Projects").is_some()));
    let rows_before = top(&h, "Settings").unwrap();
    click(&mut h, "Projects");
    assert!(
        wait_for(&mut h, |h| has_button(h, "Projects, folded")),
        "Projects did not fold\n{}",
        screen_text(&h)
    );
    // Remembered between runs.
    let saved = wait_for(&mut h, |_| {
        std::fs::read_to_string(home.settings_path())
            .map(|t| {
                t.contains("\"projects_folded\": true") || t.contains("\"projects_folded\":true")
            })
            .unwrap_or(false)
    });
    assert!(saved, "the folded state was not saved for {project}");
    click(&mut h, "Projects, folded");
    assert!(wait_for(&mut h, |h| has_button(h, "Projects")));
    assert_eq!(top(&h, "Settings").unwrap(), rows_before);
    // Chats folds on its own.
    click(&mut h, "Chats");
    assert!(wait_for(&mut h, |h| has_button(h, "Chats, folded")));
    close(h);
}

#[test]
fn a_running_task_is_listed_once() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.hold_replies();
    home.let_through(1);
    let mut h = home.open();
    settle(&mut h);
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "Working")),
        "never working\n{}",
        screen_text(&h)
    );
    for _ in 0..20 {
        h.step();
    }
    assert_eq!(
        task_rows(&h, "Tidy up notes.txt"),
        1,
        "the task is not listed exactly once\n{}",
        screen_text(&h)
    );
    assert!(!shows(&h, "Active now"));
    close(h);
}

/// Not a check: makes the kept home `r5a` (run it alone with
/// `--run-ignored only` and CHECK_HOME_NAME=r5a) with chats, three
/// projects and tasks in each state, for a picture of the left menu.
#[test]
#[ignore]
fn make_the_picture_home() {
    use mimir_core::proto::{Call, ChatTurn};
    use mimir_core::task::{Entry, Status, Task};
    let Some(home) = TestHome::new() else { return };
    let mut projects = vec![];
    for name in ["Garden planner", "Recipe box", "Photo sorter"] {
        let p = home.root.join(name);
        std::fs::create_dir_all(&p).unwrap();
        projects.push(p.display().to_string());
    }
    home.write_settings(serde_json::json!({
        "projects": projects,
        "last_project": projects[0],
        "remote_projects": [],
        "phone_relays": false,
    }));
    let store = mimir_core::store::Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;
    for (n, (p, title, status)) in [
        (0, "Tidy the watering schedule", Status::Working),
        (0, "Add a frost warning", Status::Done),
        (1, "Scale the pancake recipe", Status::NeedsYou),
        (1, "Fix the shopping list export", Status::Done),
    ]
    .into_iter()
    .enumerate()
    {
        let project = projects[p].clone();
        let mut t = Task::new(
            format!("t{n}"),
            title.into(),
            project,
            now - 3600 * (n as i64 + 1),
        );
        t.entries = vec![Entry::You(title.into()), Entry::Claude("Done.".into())];
        t.status = status;
        store.save(&t).unwrap();
    }
    let mut st = home.service();
    let day = 86_400_000;
    let at = now * 1000;
    for (i, (title, ago)) in [
        ("Grocery list for the week", day),
        ("Why is my fan loud?", 3 * day),
        ("Birthday message for Sam", 5 * day),
        ("Trip ideas for spring", 12 * day),
    ]
    .into_iter()
    .enumerate()
    {
        st.call_wait(Call::ChatPut {
            chat_id: format!("c{i}"),
            name: None,
            turns: vec![ChatTurn {
                id: format!("m{i}"),
                user: true,
                text: title.into(),
                sources: vec![],
                ran: None,
                model_words: None,
                at: at - ago,
                version: 1,
                seq: 0,
                gone: false,
                switch_note: None,
                pictures: vec![],
                files: vec![],
            }],
            deleted: false,
        })
        .unwrap();
    }
    std::thread::sleep(std::time::Duration::from_secs(1));
    home.stop_service();
}
