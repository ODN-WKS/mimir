//! A new person's first look at the PC window: a calm chat app, no empty
//! sections, no meters nobody asked for, no inside words.

mod common;
use common::*;
use station_ui::labels;

const INSIDE_WORDS: [&str; 6] = ["token", "commit", "branch", "merge", "git ", "ollama"];

fn new_install() -> Option<TestHome> {
    let home = TestHome::new()?;
    home.write_settings(serde_json::json!({ "projects": [], "remote_projects": [] }));
    Some(home)
}

#[test]
fn a_new_install_greets_with_chat_first_and_no_extra_sections() {
    let Some(home) = new_install() else { return };
    let h = home.open();
    let text = screen_text(&h);
    assert!(shows(&h, "Hi, I'm Mímir."), "{text}");
    assert!(
        shows(
            &h,
            "Ask anything. Answers come from your PC's model and stay on this PC."
        ),
        "{text}"
    );
    // Chat first, the project folder second.
    let chat = text.find("Start a chat").expect("no chat button");
    let folder = text
        .find("Choose a project folder")
        .expect("no folder button");
    assert!(chat < folder, "button order\n{text}");
    // No right panel, no empty "Needs you" or "Updates".
    for gone in [
        "THIS PC",
        "Processor",
        "Needs you",
        "Updates",
        "Test leftovers",
    ] {
        assert!(!shows(&h, gone), "{gone:?} on a new install\n{text}");
    }
    let lower = text.to_lowercase();
    for bad in INSIDE_WORDS {
        assert!(!lower.contains(bad), "{bad:?} on screen\n{text}");
    }
    close(h);
}

#[test]
fn the_right_panel_opens_by_itself_while_a_task_runs() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    home.hold_replies();
    home.let_through(1);
    let mut h = home.open();
    assert!(!has_button(&h, labels::HIDE_PANEL), "{}", screen_text(&h));
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "Stop this task")
            && has_button(h, labels::HIDE_PANEL)),
        "{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn a_pinned_panel_stays_open_when_nothing_runs() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project], "last_project": project, "details": true,
    }));
    let mut h = home.open();
    click(&mut h, labels::NEW_CHAT);
    for _ in 0..5 {
        h.step();
    }
    assert!(shows(&h, "THIS PC"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn the_shortcut_line_shows_only_in_an_empty_chat() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    click(&mut h, labels::NEW_CHAT);
    for _ in 0..5 {
        h.step();
    }
    assert!(shows(&h, "Enter send"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn needs_you_shows_when_a_task_waits() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "question.jsonl");
    let mut h = home.open();
    assert!(!has_button(&h, "Needs you"), "{}", screen_text(&h));
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| has_button(h, "Needs you")),
        "a waiting question didn't raise the Needs you row\n{}",
        screen_text(&h)
    );
    // It sits at the bottom, above Settings, so it moves nothing above it.
    let y = |h: &egui_kittest::Harness<'static, station_ui::App>, name: &str| {
        use egui_kittest::kittest::Queryable;
        h.query_all_by_role_and_label(eframe::egui::accesskit::Role::Button, name)
            .next()
            .map(|n| n.rect().top())
    };
    let (needs, settings) = (y(&h, "Needs you").unwrap(), y(&h, "Settings").unwrap());
    assert!(needs < settings, "Needs you is not above Settings");
    assert!(
        needs > y(&h, "Projects").unwrap(),
        "Needs you is above the lists"
    );
    close(h);
}

#[test]
fn updates_shows_when_an_update_is_ready() {
    use mimir_core::update;
    let Some(home) = TestHome::new() else { return };
    let key = ed25519_dalek::SigningKey::from_bytes(&[42u8; 32]);
    let program: &[u8] = b"#!/bin/sh\necho new\n";
    let name = update::asset_name("9.99");
    let releases = home.root.join("updates");
    std::fs::write(releases.join(&name), program).unwrap();
    std::fs::write(
        releases.join(format!("{name}.sig")),
        update::sign(&key, "9.99", program),
    )
    .unwrap();
    // SAFETY: as in window_update.rs; nothing else runs yet.
    unsafe {
        std::env::set_var(
            "MIMIR_TEST_KEY",
            update::to_hex(&key.verifying_key().to_bytes()),
        );
    };
    let mut h = home.open();
    assert!(
        wait_for(&mut h, |h| has_button(h, "Updates")),
        "no Updates row\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn a_short_task_ending_while_typing_leaves_the_panel_open() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::HIDE_PANEL)),
        "{}",
        screen_text(&h)
    );
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    type_message(&mut h, "Next, ");
    for _ in 0..30 {
        h.step();
    }
    // The task is over, a draft is in the box: the panel stays.
    assert!(has_button(&h, labels::HIDE_PANEL), "{}", screen_text(&h));
    close(h);
}
