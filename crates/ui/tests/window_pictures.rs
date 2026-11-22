//! P1: pictures in the window's chat. A picture is attached (paste, drop),
//! shown small in the box with a way to take it out, goes to the PC's model
//! shrunk, and shows in the sent message. A model that can't look at
//! pictures says so in one plain line, before anything is sent.

mod common;
use common::*;
use eframe::egui;
use egui::accesskit::Role;
use egui_kittest::kittest::Queryable;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::time::Duration;

fn home() -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
    }));
    Some(home)
}

/// A stand-in Ollama that can (or can't) look at pictures; every chat body is kept.
fn fake_ollama(sees: bool) -> Arc<Mutex<Vec<serde_json::Value>>> {
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
                    "/api/show" => vec![if sees {
                        r#"{"capabilities":["completion","vision"]}"#.into()
                    } else {
                        r#"{"capabilities":["completion"]}"#.into()
                    }],
                    _ => {
                        if let Ok(v) = serde_json::from_slice(&body) {
                            kept.lock().unwrap().push(v);
                        }
                        vec![
                            serde_json::json!({"message": {"content": "A green square."}, "done": false}).to_string(),
                            r#"{"message":{"content":""},"done":true,"prompt_eval_count":50}"#.into(),
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

/// A big green picture, as pixels (what a paste brings).
fn big_green() -> egui::ColorImage {
    egui::ColorImage::filled([3000, 2000], egui::Color32::from_rgb(20, 180, 40))
}

fn has_role(h: &egui_kittest::Harness<'static, station_ui::App>, role: Role, label: &str) -> bool {
    h.query_all_by_role_and_label(role, label).next().is_some()
}

#[test]
fn a_pasted_picture_is_shown_small_removable_sent_shrunk_and_shown_in_the_message() {
    let Some(home) = home() else { return };
    let asked = fake_ollama(true);
    picked(&home);
    let mut h = home.open();
    click(&mut h, station_ui::labels::NEW_CHAT);
    // Ctrl+V with a picture on the clipboard.
    h.event(egui::Event::PasteImage(Arc::new(big_green())));
    assert!(
        wait_for(&mut h, |h| has_role(h, Role::Image, "Attached picture")),
        "no thumbnail in the box\n{}",
        screen_text(&h)
    );
    // The ✕ takes it out again.
    click(&mut h, station_ui::labels::REMOVE_PICTURE);
    assert!(wait_for(&mut h, |h| !has_role(
        h,
        Role::Image,
        "Attached picture"
    )));
    // Paste again and send it with a question.
    h.event(egui::Event::PasteImage(Arc::new(big_green())));
    assert!(wait_for(&mut h, |h| has_role(
        h,
        Role::Image,
        "Attached picture"
    )));
    type_message(&mut h, "What is this?");
    click(&mut h, station_ui::labels::SEND);
    assert!(
        wait_for(&mut h, |h| shows(h, "A green square.")),
        "no answer\n{}",
        screen_text(&h)
    );
    // The sent message shows the picture, and the box is empty again.
    assert!(
        has_role(&h, Role::Image, "Sent picture"),
        "{}",
        screen_text(&h)
    );
    assert!(!has_role(&h, Role::Image, "Attached picture"));
    // The model got it as a picture, shrunk to 1568 on its long side.
    let body = asked.lock().unwrap().last().cloned().expect("no request");
    let images: Vec<String> = body["messages"]
        .as_array()
        .unwrap()
        .iter()
        .flat_map(|m| m["images"].as_array().cloned().unwrap_or_default())
        .filter_map(|i| i.as_str().map(str::to_string))
        .collect();
    assert_eq!(images.len(), 1, "{body}");
    let bytes = {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD
            .decode(&images[0])
            .unwrap()
    };
    let img = image::load_from_memory(&bytes).unwrap();
    assert_eq!((img.width(), img.height()), (1568, 1045));
    close(h);
}

#[test]
fn a_model_that_cannot_look_says_so_instead_of_taking_the_picture() {
    let Some(home) = home() else { return };
    let _asked = fake_ollama(false);
    picked(&home);
    let mut h = home.open();
    click(&mut h, station_ui::labels::NEW_CHAT);
    h.event(egui::Event::PasteImage(Arc::new(big_green())));
    assert!(
        wait_for(&mut h, |h| shows(h, station_ui::chat_pics::CANT_SEE)),
        "no plain line\n{}",
        screen_text(&h)
    );
    assert!(!has_role(&h, Role::Image, "Attached picture"));
    // The attach button explains too, and opens no picker.
    click(&mut h, station_ui::labels::ATTACH_FILES);
    assert!(shows(&h, station_ui::chat_pics::CANT_SEE));
    std::thread::sleep(Duration::from_millis(10));
    close(h);
}

#[test]
fn only_four_pictures_go_in_one_message() {
    let Some(home) = home() else { return };
    let _asked = fake_ollama(true);
    picked(&home);
    let mut h = home.open();
    click(&mut h, station_ui::labels::NEW_CHAT);
    for _ in 0..5 {
        h.event(egui::Event::PasteImage(Arc::new(egui::ColorImage::filled(
            [40, 30],
            egui::Color32::RED,
        ))));
        h.step();
    }
    assert!(wait_for(&mut h, |h| shows(
        h,
        station_ui::chat_pics::TOO_MANY
    )));
    assert!(wait_for(&mut h, |h| h
        .query_all_by_role_and_label(Role::Image, "Attached picture")
        .count()
        == 4));
    close(h);
}
