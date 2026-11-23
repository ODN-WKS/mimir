//! Claude is only an option: a PC with no Claude at all shows nothing
//! about it in the main window, the settings pages lead with the PC's own
//! models, and nothing there says something untrue.

mod common;
use common::*;
use egui_kittest::kittest::{NodeT, Queryable};
use mimir_core::settings::{Power, Settings};

/// A PC that never had Claude Code: the program is not there.
fn no_claude(home: &TestHome) {
    set_env("MIMIR_CLAUDE", home.root.join("no-such-claude"));
    own_models(home, serde_json::json!({}));
}

/// What a new install chooses: own models for chats and project work
/// (the checks' usual fixture runs project work on the stand-in Claude).
fn own_models(home: &TestHome, extra: serde_json::Value) {
    let project = home.project.clone();
    let mut v = serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "project_engine": "free",
    });
    for (k, x) in extra.as_object().unwrap() {
        v[k] = x.clone();
    }
    home.write_settings(v);
}

#[test]
fn a_new_pc_runs_projects_on_its_own_models() {
    let s: Settings = serde_json::from_value(serde_json::json!({})).unwrap();
    assert!(s.project_free() && !s.chats_paid());
    assert_eq!(s.power(), Power::FreeOnly);
}

#[test]
fn the_main_window_says_nothing_about_claude_on_a_new_pc() {
    let Some(home) = TestHome::new() else { return };
    no_claude(&home);
    let mut h = home.open();
    assert!(wait_for(&mut h, |h| has_button(h, "Settings")));
    // Let the background check of Claude Code finish.
    for _ in 0..60 {
        h.step();
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    let text = screen_text(&h);
    for words in [
        "Install Claude Code",
        "Sign in to Claude Code",
        "Needed before",
        "From elsewhere",
        "claude.ai",
    ] {
        assert!(!text.contains(words), "the window shows {words:?}\n{text}");
    }
    close(h);
}

#[test]
fn settings_lead_with_own_models_and_keep_claude_optional() {
    let Some(home) = TestHome::new() else { return };
    no_claude(&home);
    let mut h = home.open();
    click(&mut h, "Settings");
    // General: no company sign-in cards, no warnings.
    for _ in 0..30 {
        h.step();
    }
    let text = screen_text(&h);
    for words in [
        "GitHub",
        "Not signed in",
        "private repository",
        "Install Claude Code",
    ] {
        assert!(!text.contains(words), "General shows {words:?}\n{text}");
    }
    click(&mut h, "Models");
    assert!(
        wait_for(&mut h, |h| shows(h, "Online (your words go to Anthropic)")),
        "{}",
        screen_text(&h)
    );
    assert!(
        wait_for(&mut h, |h| shows(
            h,
            "Optional: connect a Claude plan for heavy coding. Those words then go to Anthropic."
        )),
        "{}",
        screen_text(&h)
    );
    let text = screen_text(&h);
    for words in [
        "Haiku",
        "Fable",
        "Opus",
        "Tip:",
        "words-ish",
        "Not signed in",
    ] {
        assert!(!text.contains(words), "Models shows {words:?}\n{text}");
    }
    // The own models come before the Claude section.
    let at = |w: &str| text.find(w).unwrap_or_else(|| panic!("no {w:?}\n{text}"));
    assert!(at("On this PC") < at("Optional: a Claude plan"));
    close(h);
}

#[test]
fn the_phones_page_does_not_promote_anthropics_phone_app() {
    let Some(home) = TestHome::new() else { return };
    no_claude(&home);
    let mut h = home.open();
    click(&mut h, "Settings");
    click(&mut h, "Phones");
    for _ in 0..30 {
        h.step();
    }
    let text = screen_text(&h);
    for words in [
        "Three different phone things",
        "Anthropic's own phone app",
        "claude.ai",
    ] {
        assert!(!text.contains(words), "Phones shows {words:?}\n{text}");
    }
    close(h);
}

const FREE: &str = "qwen3.6:35b-a3b-coding";

/// A made-up Ollama with one free model.
fn fake_ollama() {
    use std::io::{Read, Write};
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = format!("{{\"models\":[{{\"name\":\"{FREE}\"}}]}}");
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
}

/// Finding: project work on the PC's own models still runs through the
/// Claude Code program (no account needed), so with none installed it
/// cannot start. It says so plainly, and Settings › Models offers the install.
#[test]
fn project_work_without_claude_code_says_so_and_models_offers_the_install() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama();
    no_claude(&home);
    let mut h = home.open();
    assert!(
        wait_for(&mut h, |h| shows(h, "Project work is not installed yet")),
        "{}",
        screen_text(&h)
    );
    click(&mut h, "Settings");
    click(&mut h, "Models");
    assert!(
        wait_for(&mut h, |h| has_button(h, "Install Claude Code")
            && shows(h, "Project work on your PC's own models also needs")),
        "{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn a_connected_plan_shows_its_models_and_the_online_choices() {
    let Some(home) = TestHome::new() else { return };
    own_models(&home, serde_json::json!({}));
    let mut h = home.open();
    click(&mut h, "Settings");
    click(&mut h, "Models");
    assert!(
        wait_for(&mut h, |h| shows(h, "Haiku 5.5") && shows(h, "Fable 5.2")),
        "{}",
        screen_text(&h)
    );
    // The Online choices can be picked.
    let online = h
        .query_all_by_role_and_label(
            eframe::egui::accesskit::Role::RadioButton,
            "Online (your words go to Anthropic)",
        )
        .next()
        .expect("no Online choice");
    assert!(
        !online.accesskit_node().is_disabled(),
        "Online is greyed out"
    );
    close(h);
}

#[test]
fn a_chat_set_to_claude_without_it_says_so_and_offers_the_own_model() {
    let Some(home) = TestHome::new() else { return };
    no_claude(&home);
    own_models(&home, serde_json::json!({"chats_engine": "paid"}));
    let mut h = home.open();
    assert!(
        wait_for(&mut h, |h| shows(h, "no Anthropic plan is connected")),
        "{}",
        screen_text(&h)
    );
    assert!(has_button(&h, "Open Settings › Models"));
    click(&mut h, "Use your PC's own model instead");
    assert!(
        wait_for(&mut h, |_| std::fs::read_to_string(home.settings_path())
            .unwrap_or_default()
            .contains("\"chats_engine\": \"free\"")),
        "the own model was not chosen"
    );
    close(h);
}
