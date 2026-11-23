//! P08-PW: every box where words are sent has what a chat has: attach, a
//! model to choose, an effort to choose, and what is chosen is what is used.

mod common;
use common::*;
use eframe::egui;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};

/// A stand-in Ollama; every chat request body is kept.
fn fake_ollama() -> Arc<Mutex<Vec<serde_json::Value>>> {
    let asked = Arc::new(Mutex::new(vec![]));
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    set_env(
        "MIMIR_OLLAMA",
        format!("http://{}", l.local_addr().unwrap()),
    );
    let kept = asked.clone();
    std::thread::spawn(move || {
        for c in l.incoming().flatten() {
            let kept = kept.clone();
            std::thread::spawn(move || {
                let mut r = BufReader::new(c.try_clone().unwrap());
                let mut first = String::new();
                r.read_line(&mut first).unwrap_or(0);
                let path = first.split_whitespace().nth(1).unwrap_or("").to_string();
                let mut len = 0;
                loop {
                    let mut l = String::new();
                    if r.read_line(&mut l).unwrap_or(0) == 0 || l.trim().is_empty() {
                        break;
                    }
                    if let Some(n) = l.to_ascii_lowercase().strip_prefix("content-length:") {
                        len = n.trim().parse().unwrap_or(0);
                    }
                }
                let mut body = vec![0; len];
                let _ = r.read_exact(&mut body);
                let lines: Vec<String> = match path.as_str() {
                    "/api/version" => vec![r#"{"version":"0.40.16"}"#.into()],
                    "/api/ps" | "/api/tags" => vec![r#"{"models":[]}"#.into()],
                    "/api/show" => vec![r#"{"capabilities":["completion"]}"#.into()],
                    _ => {
                        if let Ok(v) = serde_json::from_slice(&body) {
                            kept.lock().unwrap().push(v);
                        }
                        vec![
                            serde_json::json!({"message": {"content": "Fine."}, "done": false})
                                .to_string(),
                            r#"{"message":{"content":""},"done":true,"prompt_eval_count":5}"#
                                .into(),
                        ]
                    }
                };
                let mut w = c;
                let _ = w.write_all(
                    b"HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nTransfer-Encoding: chunked\r\n\r\n",
                );
                for line in lines {
                    let data = format!("{line}\n");
                    let _ = w.write_all(format!("{:x}\r\n{data}\r\n", data.len()).as_bytes());
                    let _ = w.flush();
                }
                let _ = w.write_all(b"0\r\n\r\n");
            });
        }
    });
    asked
}

fn picked(home: &TestHome) {
    let dir = home.root.join(".local/share/mimir");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(
        dir.join("engine.json"),
        serde_json::json!({
            "fingerprint": "check", "at": 0,
            "chat": {"model": "huihui_ai/Qwen3.6-abliterated:35b-a3b", "title": "Qwen 3.6 35B, uncensored",
                     "fit": "Split", "tokens_per_s": 64.0, "first_token_ms": 112, "cold_ms": 11000, "backend": "ROCm"},
            "measures": []
        })
        .to_string(),
    )
    .unwrap();
}

/// Opens the picker with this label and chooses the entry with these words.
fn pick(h: &mut egui_kittest::Harness<'static, station_ui::App>, picker: &str, entry: &str) {
    use egui_kittest::kittest::Queryable;
    h.query_all_by(|n| {
        (n.role() == egui::accesskit::Role::ComboBox || n.role() == egui::accesskit::Role::Button)
            && (n.label().as_deref() == Some(picker) || n.value().as_deref() == Some(picker))
    })
    .next()
    .unwrap_or_else(|| panic!("no picker {picker:?}\n{}", screen_text(h)))
    .click();
    for _ in 0..5 {
        h.step();
    }
    h.query_by_label(entry)
        .unwrap_or_else(|| panic!("no entry {entry:?}\n{}", screen_text(h)))
        .click();
    for _ in 0..5 {
        h.step();
    }
}

#[test]
fn the_effort_chosen_in_the_chat_box_is_the_effort_the_answer_uses() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({"projects": [project], "last_project": project}));
    let asked = fake_ollama();
    picked(&home);
    let mut h = home.open();
    click(&mut h, station_ui::labels::NEW_CHAT);
    pick(&mut h, "Effort: Medium", "High  ·  thinks it through");
    type_message(&mut h, "Plan my week");
    click(&mut h, station_ui::labels::SEND);
    assert!(
        wait_for(&mut h, |h| shows(h, "Fine.")),
        "no answer\n{}",
        screen_text(&h)
    );
    let body = asked.lock().unwrap().last().cloned().expect("no request");
    assert_eq!(body["think"], true, "{body}");
    assert_eq!(body["options"]["num_predict"], 8192, "{body}");
    close(h);
}

#[test]
fn the_chat_box_has_a_model_picker_that_sets_who_answers() {
    let Some(home) = TestHome::new() else { return };
    let _asked = fake_ollama();
    picked(&home);
    let mut h = home.open();
    click(&mut h, station_ui::labels::NEW_CHAT);
    // The chip is a real picker now, not a link to another page.
    pick(
        &mut h,
        station_ui::labels::CHIP_PC_MODEL,
        "Online: Quick  ·  Short answers, fastest",
    );
    assert!(
        wait_for(&mut h, |_| std::fs::read_to_string(home.settings_path())
            .unwrap_or_default()
            .contains("\"chats_model\": \"haiku\"")),
        "the choice was not kept: {}",
        std::fs::read_to_string(home.settings_path()).unwrap_or_default()
    );
    assert!(shows(&h, "Quick"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn the_side_panel_is_open_in_a_wide_window_with_no_choice_and_can_go_automatic() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({"projects": [project], "last_project": project}));
    let store = mimir_core::store::Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = mimir_core::task::Task::new("t1".into(), "Sort receipts".into(), project, 0);
    t.entries = vec![mimir_core::task::Entry::You("Sort them".into())];
    t.status = mimir_core::task::Status::Done;
    store.save(&t).unwrap();
    let mut h = home.open_at(1400.0, 800.0);
    assert!(
        wait_for(&mut h, |h| has_button(h, "Hide panel ›")),
        "the panel is closed\n{}",
        screen_text(&h)
    );
    assert!(has_button(&h, "Automatic"));
    click(&mut h, "Automatic");
    assert!(
        !has_button(&h, "Automatic"),
        "still offers Automatic\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn the_weekly_use_line_says_so_when_nothing_has_been_read() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open_at(1400.0, 800.0);
    assert!(
        wait_for(&mut h, |h| shows(h, "Weekly use: not read yet")),
        "{}",
        screen_text(&h)
    );
    close(h);
}
