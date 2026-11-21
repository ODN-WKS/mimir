//! The chat area stays clean (owner, 2026-11-18): under the message box
//! there are only controls and small chips, no sentences. The mode and
//! where the task runs show as chips with their explanation on hover.

mod common;
use common::*;
use eframe::egui::accesskit::Role;
use egui_kittest::kittest::{NodeT, Queryable};

#[test]
fn no_sentences_under_the_message_box() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.display().to_string();
    // Don't ask, the mode that used to bring a red sentence.
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "defaults": { (project.clone()): { "mode": "NoAsk" } },
    }));
    home.play("run-1.jsonl", "near-limit.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt is sorted")
            && has_button(h, station_ui::labels::PILL_MENU)),
        "the task didn't finish\n{}",
        screen_text(&h)
    );
    for _ in 0..10 {
        h.step();
    }
    assert!(shows(&h, "Don't ask"), "no mode chip\n{}", screen_text(&h));
    let message_box = h
        .query_all_by_role(Role::MultilineTextInput)
        .next()
        .expect("no message box")
        .rect();
    let below: Vec<String> = h
        .query_all_by(|n| n.role() == Role::Label)
        .filter(|n| n.rect().min.y >= message_box.max.y - 1.0)
        .filter_map(|n| n.accesskit_node().label().map(|l| l.to_string()))
        .collect();
    for text in &below {
        // The one line of shortcuts under the box is allowed.
        if text == station_ui::labels::HINT_TASK {
            continue;
        }
        assert!(
            text.chars().count() <= 30,
            "a sentence under the message box: {text:?}\nall: {below:?}"
        );
    }
    assert!(
        !shows(&h, "Show Claude Code's screen") && !shows(&h, "Don't ask is on"),
        "old lines still shown\n{}",
        screen_text(&h)
    );
    close(h);
}
