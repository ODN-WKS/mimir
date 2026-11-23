//! Claude is only an option: a PC with no Claude at all shows nothing
//! about it in the main window, the settings pages lead with the PC's own
//! models, and nothing there says something untrue.

mod common;
use common::*;
use mimir_core::settings::{Power, Settings};

/// A PC that never had Claude Code: the program is not there.
fn no_claude(home: &TestHome) {
    set_env("MIMIR_CLAUDE", home.root.join("no-such-claude"));
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
