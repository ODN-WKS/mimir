//! A session still running in the Claude app or a terminal, and a task that
//! stopped by itself: what the window says matches what it can do (QA12 P1,
//! P2, P3, P4, P6, P10).

mod common;
use common::*;
use egui_kittest::kittest::{NodeT, Queryable};
use std::path::PathBuf;

const SESSION: &str = "4c1f2a33-5b6e-4d7f-8a9b-0c1d2e3f4a5b";

fn outside_session(home: &TestHome) -> PathBuf {
    let project = home.project.display().to_string();
    let quoted = serde_json::to_string(&project).unwrap();
    let at = iso(0);
    let folder = home
        .root
        .join(".claude/projects")
        .join(mimir_core::sessions::folder_name(&home.project));
    std::fs::create_dir_all(&folder).unwrap();
    let file = folder.join(format!("{SESSION}.jsonl"));
    let lines = [
        format!(
            r#"{{"type":"user","message":{{"role":"user","content":"Fix the garden gate latch"}},"uuid":"u1","cwd":{quoted},"sessionId":"{SESSION}","timestamp":"{at}"}}"#
        ),
        format!(
            r#"{{"type":"assistant","message":{{"role":"assistant","content":[{{"type":"text","text":"Looking at the latch now."}}],"stop_reason":"end_turn"}},"uuid":"a1","timestamp":"{at}"}}"#
        ),
    ];
    std::fs::write(&file, lines.join("\n") + "\n").unwrap();
    // Not a task yet: it is listed under From elsewhere, and opening it
    // makes the task (the way every session from an earlier time opens).
    file
}

fn open_row(h: &mut egui_kittest::Harness<'static, station_ui::App>, title: &str) {
    let listed = |h: &egui_kittest::Harness<'static, station_ui::App>| {
        h.query_all_by(|n| {
            n.role() == eframe::egui::accesskit::Role::Button
                && n.label().is_some_and(|l| l.contains(title))
        })
        .next()
        .is_some()
    };
    assert!(wait_for(h, listed), "{}", screen_text(h));
    let label = h
        .query_all_by(|n| {
            n.role() == eframe::egui::accesskit::Role::Button
                && n.label().is_some_and(|l| l.contains(title))
        })
        .next()
        .and_then(|n| n.accesskit_node().label().map(|l| l.to_string()))
        .unwrap();
    click(h, &label);
}

#[test]
fn a_followed_session_can_be_stopped_here_and_says_one_thing() {
    let Some(home) = TestHome::new() else { return };
    let file = outside_session(&home);
    let mut h = home.open();
    assert!(
        wait_for(&mut h, |h| shows(h, "From elsewhere")),
        "{}",
        screen_text(&h)
    );
    open_row(&mut h, "Fix the garden gate latch");
    assert!(
        wait_for(&mut h, |h| shows(h, "Still running in")),
        "{}",
        screen_text(&h)
    );
    // The session goes on: a new line in its transcript, followed here.
    {
        use std::io::Write;
        let at = iso(0);
        let mut f = std::fs::OpenOptions::new()
            .append(true)
            .open(&file)
            .unwrap();
        writeln!(
            f,
            r#"{{"type":"assistant","message":{{"role":"assistant","content":[{{"type":"text","text":"Sanding the latch."}}],"stop_reason":"end_turn"}},"uuid":"a2","timestamp":"{at}"}}"#
        )
        .unwrap();
    }
    assert!(
        wait_for(&mut h, |h| shows(h, "Sanding the latch.")),
        "{}",
        screen_text(&h)
    );
    // P1: the line that says to stop it has a Stop beside it.
    assert!(has_button(&h, "Stop it"), "{}", screen_text(&h));
    // P2: no "send a message" line while nothing can be sent.
    assert!(
        !shows(&h, "Send a message to carry on"),
        "{}",
        screen_text(&h)
    );
    // P3: one name, and nothing about permissions or changes of a session
    // Mimir does not run.
    for gone in [
        "Going on in another window",
        "Live elsewhere",
        "Ask me first",
        "No files changed yet",
    ] {
        assert!(!shows(&h, gone), "{gone}\n{}", screen_text(&h));
    }
    // P4: Online is not set up, and Mimir does not answer this session: the
    // pill's menu says so, not "Answers go to Anthropic".
    click(&mut h, station_ui::labels::PILL_MENU);
    assert!(!shows(&h, "Answers go to Anthropic"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn a_task_that_stopped_by_itself_has_the_continue_button_its_line_names() {
    let Some(home) = TestHome::new() else { return };
    let store = mimir_core::store::Store::new(mimir_core::store::Store::default_dir()).unwrap();
    let mut t = mimir_core::task::Task::new(
        "1700000000-2-1".into(),
        "Plan the spring planting".into(),
        home.project.display().to_string(),
        1_700_000_000,
    );
    t.add_you("Plan the spring planting");
    t.status = mimir_core::task::Status::Stopped;
    t.failed = true;
    t.add_note("This task stopped unexpectedly. Press Continue to carry on.");
    store.save(&t).unwrap();
    let mut h = home.open();
    open_row(&mut h, "Plan the spring planting");
    assert!(
        wait_for(&mut h, |h| shows(h, "Press Continue to carry on")),
        "{}",
        screen_text(&h)
    );
    assert!(has_button(&h, "Continue"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn new_task_opens_a_task_page_not_a_chat_page() {
    let Some(home) = TestHome::new() else { return };
    // The last thing done was a chat: Chats is the project picked.
    let chats = home.root.join(".local/share/mimir/chats");
    home.write_settings(serde_json::json!({
        "projects": [home.project.display().to_string()],
        "last_project": chats.display().to_string(),
    }));
    let mut h = home.open();
    settle(&mut h);
    click(&mut h, "+  New task");
    settle(&mut h);
    let exact = |h: &egui_kittest::Harness<'static, station_ui::App>, w: &str| {
        h.query_all_by(|n| {
            n.role() != eframe::egui::accesskit::Role::Button
                && (n.label().is_some_and(|l| l == w) || n.value().is_some_and(|v| v == w))
        })
        .next()
        .is_some()
    };
    assert!(exact(&h, "New task"), "{}", screen_text(&h));
    assert!(!exact(&h, "New chat"), "{}", screen_text(&h));
    close(h);
}
