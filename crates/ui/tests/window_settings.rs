//! Settings, a page of its own: each section opens from the list on the
//! left and has what the findability audit put there; Settings › Models
//! has every model (the phone sends people there), the Model Store, and what
//! happens when Claude's limit runs out.

mod common;
use common::*;
use eframe::egui::accesskit::Role;
use egui_kittest::kittest::{NodeT, Queryable};
use std::io::{Read, Write};
use std::net::TcpListener;

const FREE: &str = "qwen3.6:35b-a3b-coding";

/// A made-up Ollama: lists one free model and says yes to everything else
/// (a download, a removal). Every request is logged to the returned file.
fn fake_ollama(home: &TestHome) -> std::path::PathBuf {
    let log = home.root.join("ollama.log");
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    let to = log.clone();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 4096];
            let n = s.read(&mut buf).unwrap_or(0);
            let req = String::from_utf8_lossy(&buf[..n]).to_string();
            let first = req.lines().next().unwrap_or_default().to_string();
            let body_in = req.split("\r\n\r\n").nth(1).unwrap_or_default();
            let mut f = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&to)
                .unwrap();
            let _ = writeln!(f, "{first} {body_in}");
            let body = if first.contains("/api/tags") {
                format!("{{\"models\":[{{\"name\":\"{FREE}\"}}]}}")
            } else {
                "{\"status\":\"success\"}".to_string()
            };
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
    log
}

fn ollama_asked(log: &std::path::Path, what: &str) -> bool {
    std::fs::read_to_string(log)
        .unwrap_or_default()
        .lines()
        .any(|l| l.contains(what))
}

#[test]
fn settings_models_has_every_model_and_the_weekly_look() {
    let Some(home) = TestHome::new() else { return };
    let log = fake_ollama(&home);
    // The weekly look ran and found a better phone model.
    let data = mimir_core::paths::data_dir();
    std::fs::create_dir_all(&data).unwrap();
    std::fs::write(
        data.join("weekly-last.json"),
        serde_json::json!({
            "at": 0,
            "found": "Worth switching? A new small uncensored model, Tiny/Better, scores 50.2.",
            "said": [],
        })
        .to_string(),
    )
    .unwrap();
    let mut h = home.open();
    click(&mut h, "Settings");
    click(&mut h, "Models");
    // What powers tasks, Claude's models, the free ones on this PC, the
    // store, the phone's, the weekly look.
    for words in [
        "Who answers",
        "Chats and look-ups:",
        "Project work:",
        "Online (your words go to Anthropic)",
        "Optional: a Claude plan",
        "On this PC",
        "Model Store",
        "gpt-oss 20B",
        "Fixed both test bugs in 32 s",
        "Uncensored",
        "On the phone",
        "Gemma 4 E2B heretic",
        "Tiny/Better",
    ] {
        assert!(
            wait_for(&mut h, |h| shows(h, words)),
            "Settings › Models doesn't show {words:?}\n{}",
            screen_text(&h)
        );
    }
    assert!(
        wait_for(&mut h, |h| shows(h, "Qwen 3.6 coding")),
        "the free model on this PC isn't listed\n{}",
        screen_text(&h)
    );
    // The weekly look can be turned off, and that is saved.
    let weekly = "Once a week, look for a better model for the phone";
    h.query_by_label(weekly)
        .expect("no weekly switch")
        .scroll_to_me();
    for _ in 0..30 {
        h.step();
    }
    h.query_by_label(weekly).unwrap().hover();
    for _ in 0..3 {
        h.step();
    }
    h.query_by_label(weekly).unwrap().click();
    h.step();
    let saved = wait_for(&mut h, |_| {
        std::fs::read_to_string(home.settings_path())
            .unwrap_or_default()
            .contains("\"weekly_model_check\": false")
    });
    assert!(saved, "turning the weekly look off wasn't saved");
    // A model from the store downloads with its progress, and is then on
    // this PC.
    let store = h
        .query_all_by_role_and_label(Role::Button, "Download")
        .next()
        .expect("no Download in the store");
    store.scroll_to_me();
    for _ in 0..30 {
        h.step();
    }
    click(&mut h, "Download");
    assert!(
        wait_for(&mut h, |h| shows(h, "Added gpt-oss:20b.")),
        "downloading from the store didn't finish\n{}",
        screen_text(&h)
    );
    assert!(ollama_asked(&log, "/api/pull") && ollama_asked(&log, "gpt-oss:20b"));
    // A free model is added by its name...
    let by_name = "Add a model by name";
    h.query_by_label(by_name)
        .expect("no way to add by name")
        .scroll_to_me();
    for _ in 0..30 {
        h.step();
    }
    h.query_by_label(by_name).unwrap().click();
    for _ in 0..30 {
        h.step();
    }
    let input = "Add a model:";
    h.query_by_label(input)
        .expect("no box for a model's name")
        .scroll_to_me();
    for _ in 0..30 {
        h.step();
    }
    let boxes = || Role::TextInput;
    let name_box = |h: &egui_kittest::Harness<'static, station_ui::App>| {
        h.query_all_by_role(boxes())
            .find(|n| n.accesskit_node().label().as_deref() == Some(input))
            .is_some()
    };
    assert!(
        name_box(&h),
        "the model name box has no name\n{}",
        screen_text(&h)
    );
    let typing = h
        .query_all_by_role(boxes())
        .find(|n| n.accesskit_node().label().as_deref() == Some(input))
        .unwrap();
    typing.click();
    h.step();
    h.query_all_by_role(boxes())
        .find(|n| n.accesskit_node().label().as_deref() == Some(input))
        .unwrap()
        .type_text("qwen3:4b");
    h.step();
    click(&mut h, "Add");
    assert!(
        wait_for(&mut h, |h| shows(h, "Added qwen3:4b.")),
        "adding a free model didn't finish\n{}",
        screen_text(&h)
    );
    assert!(ollama_asked(&log, "/api/pull") && ollama_asked(&log, "qwen3:4b"));
    // ...and one is removed after a second click that says what happens.
    assert!(wait_for(&mut h, |h| has_button(h, "Remove…")));
    h.query_all_by_role_and_label(Role::Button, "Remove…")
        .next()
        .unwrap()
        .scroll_to_me();
    for _ in 0..30 {
        h.step();
    }
    click(&mut h, "Remove…");
    assert!(shows(&h, "Remove it from your PC?"));
    click(&mut h, "Remove");
    assert!(
        wait_for(&mut h, |h| shows(h, &format!("Removed {FREE}."))),
        "removing a free model didn't finish\n{}",
        screen_text(&h)
    );
    assert!(ollama_asked(&log, "/api/delete"));
    close(h);
}

/// Each section opens from the list, and holds what people look for there.
#[test]
fn every_settings_section_opens_with_its_settings() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    click(&mut h, "Settings");
    for (section, words) in [
        (
            "General",
            vec!["Who answers", "Text size", "Keyboard shortcuts"],
        ),
        (
            "Models",
            vec!["Model Store", "On the phone", "Worth switching?"],
        ),
        (
            "Phones",
            vec![
                "Pair a phone",
                "Name your phones see:",
                "Let phones reach your PC from anywhere",
            ],
        ),
        ("Voice", vec!["Read aloud", station_ui::labels::TALK]),
        ("Notifications", vec!["Settings › Notifications"]),
        ("Storage", vec!["Models on this PC", "Speech for Talk"]),
        (
            "Labs",
            // The don't-touch notice and the PC activity strip are gone; the
            // developer log's two switches are what Labs holds now.
            vec![
                station_ui::labels::DEV_LOG_TITLE,
                station_ui::labels::DEV_CHATS_TITLE,
            ],
        ),
        ("About", vec!["Help"]),
    ] {
        click(&mut h, section);
        for w in words {
            assert!(
                wait_for(&mut h, |h| shows(h, w)),
                "Settings › {section} doesn't show {w:?}\n{}",
                screen_text(&h)
            );
        }
    }
    // "Whisper" names only the private mode.
    click(&mut h, "Voice");
    assert!(
        !shows(&h, "Whisper"),
        "Voice still says Whisper\n{}",
        screen_text(&h)
    );
    // Search narrows the list; Back returns.
    let search = "Search Settings";
    h.query_by_label(search).expect("no search").click();
    h.step();
    h.query_by_label(search).unwrap().type_text("connections");
    for _ in 0..5 {
        h.step();
    }
    assert!(has_button(&h, "Phones") && !has_button(&h, "Voice"));
    click(&mut h, "‹ Back");
    assert!(wait_for(&mut h, |h| !shows(h, "Search Settings")));
    close(h);
}

/// The week's use at the foot of the task list opens Settings › Models,
/// where what happens when it runs out is chosen.
#[test]
fn the_weekly_use_opens_what_happens_when_it_runs_out() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    let words = station_ui::labels::weekly_use(86.0);
    assert!(
        wait_for(&mut h, |h| shows(h, &words)),
        "the week's use isn't shown\n{}",
        screen_text(&h)
    );
    h.query_by_label(&words).unwrap().hover();
    for _ in 0..3 {
        h.step();
    }
    h.query_by_label(&words).unwrap().click();
    assert!(
        wait_for(&mut h, |h| shows(h, "Who answers")
            && shows(h, "Project work:")),
        "Settings › Models didn't open\n{}",
        screen_text(&h)
    );
    close(h);
}

/// The `--append-system-prompt` text of Claude Code's newest conversation.
fn appended(home: &TestHome) -> Option<String> {
    let runs = home.conversations();
    let last = runs.last()?;
    let i = last.iter().position(|a| a == "--append-system-prompt")?;
    last.get(i + 1).cloned()
}

#[test]
fn ground_rules_go_with_every_task_and_can_be_changed_or_turned_off() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "ground_rules": "Be brief and plain.",
    }));
    home.play("run-1.jsonl", "live.jsonl");
    let mut h = home.open();
    // Changed rules are sent as they are.
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(wait_for(&mut h, |_| home.conversations().len() == 1));
    assert_eq!(appended(&home).as_deref(), Some("Be brief and plain."));
    // Settings › Models › Ground rules shows them, with the note and Reset.
    click(&mut h, "Settings");
    click(&mut h, "Models");
    assert!(shows(&h, station_ui::labels::GROUND_RULES));
    assert!(shows(&h, mimir_core::settings::GROUND_RULES_NOTE));
    assert!(
        h.query_all_by(|n| n.value().is_some_and(|v| v == "Be brief and plain."))
            .next()
            .is_some(),
        "the owner's rules aren't in the box\n{}",
        screen_text(&h)
    );
    click(&mut h, "Reset to default");
    let default = mimir_core::settings::DEFAULT_GROUND_RULES;
    assert!(
        wait_for(&mut h, |h| h
            .query_all_by(|n| n.value().is_some_and(|v| v == default))
            .next()
            .is_some()),
        "Reset to default didn't bring the default back\n{}",
        screen_text(&h)
    );
    assert!(
        !has_button(&h, "Reset to default"),
        "Reset is offered on the default"
    );
    h.key_press(eframe::egui::Key::Escape);
    for _ in 0..10 {
        h.step();
    }
    home.play("run-1.jsonl", "live.jsonl");
    click(&mut h, "+  New task");
    start_task(&mut h, "Tidy up notes.txt again.");
    assert!(wait_for(&mut h, |_| home.conversations().len() == 2));
    assert_eq!(appended(&home).as_deref(), Some(default));
    // Turned off: nothing is added.
    click(&mut h, "Settings");
    click(&mut h, "Models");
    h.get_by_role_and_label(Role::CheckBox, "Send these with every task")
        .click();
    for _ in 0..5 {
        h.step();
    }
    h.key_press(eframe::egui::Key::Escape);
    for _ in 0..10 {
        h.step();
    }
    click(&mut h, "+  New task");
    start_task(&mut h, "Tidy up notes.txt once more.");
    assert!(wait_for(&mut h, |_| home.conversations().len() == 3));
    assert_eq!(appended(&home), None, "ground rules sent while off");
    close(h);
}
