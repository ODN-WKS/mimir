//! The middle of the PC window: a calm greeting when nothing is open, a
//! centred column, and the same message box as the phone.

mod common;
use common::*;
use eframe::egui::accesskit::Role;
use egui_kittest::kittest::{NodeT, Queryable};
use station_ui::labels;

fn home() -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
    }));
    Some(home)
}

fn box_width(h: &egui_kittest::Harness<'static, station_ui::App>) -> f32 {
    h.query_all_by_role(Role::MultilineTextInput)
        .next()
        .expect("no message box")
        .rect()
        .width()
}

#[test]
fn the_greeting_is_the_phones_hello() {
    assert_eq!(labels::GREETING, "Hi, I'm Mímir.");
}

#[test]
fn a_question_that_waits_says_for_how_long() {
    assert_eq!(labels::needs_you_waiting(0), "Needs you");
    assert_eq!(labels::needs_you_waiting(12), "Needs you · waiting 12 min");
    assert_eq!(labels::needs_you_waiting(120), "Needs you · waiting 2 h");
}

#[test]
fn the_empty_chat_is_a_greeting_with_no_eye_and_no_app_name() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    click(&mut h, labels::NEW_CHAT);
    for _ in 0..5 {
        h.step();
    }
    assert!(
        shows(&h, labels::GREETING),
        "no greeting\n{}",
        screen_text(&h)
    );
    assert!(shows(&h, labels::ASK_HELP), "{}", screen_text(&h));
    // No app name as a heading or label besides the hello (the box's own hint says it).
    let named: Vec<String> = h
        .query_all_by(|n| n.role() == Role::Label)
        .filter_map(|n| n.accesskit_node().label().map(|l| l.to_string()))
        .filter(|l| l.contains("Mímir") && l != labels::GREETING)
        .collect();
    assert!(named.is_empty(), "app name on screen: {named:?}");
    // The shortcut hint under the box.
    assert!(shows(&h, labels::HINT_CHAT), "{}", screen_text(&h));
    close(h);
}

#[test]
fn the_box_is_the_phones_box_and_the_column_is_capped() {
    let Some(home) = home() else { return };
    let mut h = home.open_at(1800.0, 900.0);
    click(&mut h, labels::NEW_CHAT);
    for _ in 0..5 {
        h.step();
    }
    assert!(
        has_button(&h, labels::ATTACH_FILES),
        "no plus\n{}",
        screen_text(&h)
    );
    assert!(
        has_button(&h, labels::DICTATE),
        "no microphone\n{}",
        screen_text(&h)
    );
    assert!(has_button(&h, labels::SEND), "no send\n{}", screen_text(&h));
    assert!(shows(&h, "Effort"), "no effort chip\n{}", screen_text(&h));
    assert!(shows(&h, "▾"), "no model chip\n{}", screen_text(&h));
    // No word buttons for what the icons do, and no Prompts button.
    for old in ["Talk", "🎤 Talk", "Attach", "Prompts ▾"] {
        assert!(!has_button(&h, old), "old button {old:?}");
    }
    let sends: Vec<_> = h
        .query_all_by(|n| n.role() == Role::Label && n.label().is_some_and(|l| l == "Send"))
        .collect();
    assert!(sends.is_empty(), "a Send word on screen");
    // On a window this wide the box is at most the lane.
    let w = box_width(&h);
    assert!(w <= labels::LANE_WIDTH + 40.0, "the box is {w} wide");
    assert!(w > 400.0, "the box is only {w} wide");
    close(h);
}

#[test]
fn a_task_box_adds_the_permission_chip_and_its_own_hint() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    let mut h = home.open_at(1800.0, 900.0);
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt is sorted")),
        "the task didn't finish\n{}",
        screen_text(&h)
    );
    for _ in 0..10 {
        h.step();
    }
    assert!(
        shows(&h, "Ask me first"),
        "no permission chip\n{}",
        screen_text(&h)
    );
    assert!(has_button(&h, labels::ATTACH_FILES));
    assert!(has_button(&h, labels::DICTATE));
    assert!(shows(&h, labels::HINT_TASK), "{}", screen_text(&h));
    assert!(!shows(&h, labels::HINT_CHAT));
    // The pill that says who answers, with the actions behind it.
    assert!(has_button(&h, labels::PILL_MENU), "{}", screen_text(&h));
    assert!(box_width(&h) <= labels::LANE_WIDTH + 40.0);
    close(h);
}

#[test]
fn a_narrow_window_keeps_the_box_inside_it() {
    let Some(home) = home() else { return };
    let mut h = home.open_at(760.0, 600.0);
    click(&mut h, labels::NEW_CHAT);
    for _ in 0..5 {
        h.step();
    }
    let right = h
        .query_all_by_role(Role::MultilineTextInput)
        .next()
        .expect("no message box")
        .rect()
        .right();
    assert!(
        right <= h.ctx.content_rect().right(),
        "the box runs off the window"
    );
    assert!(has_button(&h, labels::SEND), "{}", screen_text(&h));
    close(h);
}
