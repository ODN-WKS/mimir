//! The 0.06 gate's window fixes: Settings › Models, Voice and Storage say
//! what is true in plain words, and the empty chat's box fits a narrow window.

mod common;
use common::*;
use egui_kittest::kittest::{NodeT, Queryable};
use std::io::{Read, Write};
use std::net::TcpListener;

const CODING: &str = "qwen3.6:35b-a3b-coding";
const AGENT: &str = "qwen3.6-coder-agent:latest";

/// A made-up Ollama that lists these models.
fn fake_ollama(names: &'static [&'static str]) {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 4096];
            let _ = s.read(&mut buf).unwrap_or(0);
            let list: Vec<String> = names
                .iter()
                .map(|n| format!("{{\"name\":\"{n}\"}}"))
                .collect();
            let body = format!("{{\"models\":[{}]}}", list.join(","));
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
}

fn models_page(h: &mut egui_kittest::Harness<'static, station_ui::App>) {
    click(h, "Settings");
    click(h, "Models");
}

fn want(h: &mut egui_kittest::Harness<'static, station_ui::App>, words: &str) {
    assert!(
        wait_for(h, |h| shows(h, words)),
        "no {words:?} on screen\n{}",
        screen_text(h)
    );
}

#[test]
fn the_rules_say_chat_and_task_and_not_free() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama(&[CODING]);
    let mut h = home.open();
    models_page(&mut h);
    want(&mut h, "Send these with every chat and task");
    want(
        &mut h,
        "your PC's model and the phone's models follow these as written.",
    );
    assert!(!shows(&h, "free and on-phone"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn the_chats_card_and_the_list_agree_and_speak_plainly() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama(&[CODING, "gpt-oss:20b"]);
    let mut h = home.open();
    models_page(&mut h);
    want(&mut h, "Not checked for chats yet");
    want(&mut h, "Passed our coding check in 32 s");
    assert!(!shows(&h, "hasn't tested"), "{}", screen_text(&h));
    assert!(!shows(&h, "test bugs"), "{}", screen_text(&h));
    close(h);
}

#[test]
fn two_models_never_show_one_name() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama(&[AGENT, CODING]);
    let mut h = home.open();
    models_page(&mut h);
    // Both read "Qwen 3.6 coding"; what tells them apart is beside each.
    want(&mut h, AGENT);
    want(&mut h, CODING);
    close(h);
    // And in Storage.
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    click(&mut h, "Settings");
    click(&mut h, "Storage");
    want(&mut h, AGENT);
    want(&mut h, CODING);
    close(h);
    let _ = home;
}

#[test]
fn the_pc_model_that_answers_chats_says_so_before_any_backup_tag() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama(&[CODING]);
    home.write_settings(serde_json::json!({
        "project_engine": "free", "chats_engine": "free",
    }));
    let data = mimir_core::paths::data_dir();
    std::fs::create_dir_all(&data).unwrap();
    std::fs::write(
        data.join("engine.json"),
        serde_json::json!({
            "fingerprint": "test", "at": 0, "measures": [],
            "chat": {"model": CODING, "title": "Qwen", "fit": "Card",
                     "tokens_per_s": 40.0, "first_token_ms": 500, "cold_ms": 900,
                     "backend": "Vulkan"},
        })
        .to_string(),
    )
    .unwrap();
    // Online is not set up: the backup tag has no business showing.
    let mut h = home.open();
    models_page(&mut h);
    want(&mut h, "Answers your chats");
    assert!(
        !shows(&h, "Used when your online plan runs out"),
        "{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn a_voice_that_is_not_here_does_not_look_chosen() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    click(&mut h, "Settings");
    click(&mut h, "Voice");
    want(&mut h, "Not in this copy of Mímir");
    let selected = |h: &egui_kittest::Harness<'static, station_ui::App>, name: &str| {
        h.query_all_by_role_and_label(eframe::egui::accesskit::Role::Button, name)
            .next()
            .unwrap_or_else(|| panic!("no {name:?}\n{}", screen_text(h)))
            .accesskit_node()
            .toggled()
            == Some(eframe::egui::accesskit::Toggled::True)
    };
    assert!(!selected(&h, "Natural voice (Ingrid)"));
    assert!(selected(&h, "Desktop voice"));
    close(h);
}

/// 1280x800 at scale 1.5 is 853x533 points.
#[test]
fn the_empty_chat_fits_a_narrow_window() {
    let Some(home) = TestHome::new() else { return };
    // Online answers, so the Effort chip is a choice, as on the gate's PC.
    home.write_settings(serde_json::json!({
        "projects": [home.project.display().to_string()],
        "last_project": home.project.display().to_string(),
        "remote_projects": [],
        "chats_engine": "paid",
    }));
    let mut h = home.open_at(853.0, 533.0);
    // The panel open, as in the gate's shot: the middle is at its narrowest.
    click(&mut h, station_ui::labels::NEW_CHAT);
    click(&mut h, station_ui::labels::SHOW_PANEL);
    for _ in 0..30 {
        h.step();
    }
    // The shortcut line is whole: all its words fit on one line, or it wraps.
    let hint = station_ui::labels::HINT_CHAT;
    let node = h
        .query_all_by(|n| {
            n.label().is_some_and(|l| l.contains(hint))
                || n.value().is_some_and(|v| v.contains(hint))
        })
        .next()
        .unwrap_or_else(|| panic!("no shortcut line\n{}", screen_text(&h)));
    let r = node.rect();
    let whole = h.ctx.fonts_mut(|f| {
        f.layout_no_wrap(
            hint.to_string(),
            eframe::egui::FontId::proportional(11.5),
            eframe::egui::Color32::WHITE,
        )
        .size()
    });
    assert!(
        r.width() >= whole.x - 1.0 || r.height() > whole.y * 1.5,
        "the shortcut line is cut: {r:?}, whole line {whole:?}"
    );
    assert!(r.max.x <= 853.5, "{r:?}");
    // The model and effort chips share one row.
    let y = |name: &str| {
        h.query_all_by(|n| {
            n.label().is_some_and(|l| l.contains(name))
                || n.value().is_some_and(|v| v.contains(name))
        })
        .next()
        .unwrap_or_else(|| panic!("no {name:?}\n{}", screen_text(&h)))
        .rect()
        .min
        .y
    };
    let (a, b) = (y("Auto-pick"), y("Effort: Medium"));
    assert!(
        (a - b).abs() < 2.0,
        "the chips are on two rows: {a} and {b}"
    );
    close(h);
}
