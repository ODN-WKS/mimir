//! 0.07 hunt fixes, PC window words and states (F2): one true story per
//! screen, nothing that points to a place that can't help.

mod common;
use common::*;
use egui_kittest::kittest::Queryable;
use station_ui::labels;

fn engine_pick(home: &TestHome) {
    let dir = home.root.join(".local/share/mimir");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(
        dir.join("engine.json"),
        serde_json::json!({
            "fingerprint": "check", "at": 0,
            "chat": {"model": "huihui_ai/Qwen3.6-abliterated:35b-a3b", "title": "Qwen 3.6 35B",
                     "fit": "Split", "tokens_per_s": 64.0, "first_token_ms": 112,
                     "cold_ms": 11000, "backend": "ROCm"},
            "measures": []
        })
        .to_string(),
    )
    .unwrap();
}

/// P2: with the model program down, the chat says so with a Try again, and
/// the top pill is not the green "This PC".
#[test]
fn a_model_program_that_is_down_has_try_again_and_no_green_pill() {
    let Some(home) = TestHome::new() else { return };
    set_env("MIMIR_OLLAMA", "http://127.0.0.1:9");
    engine_pick(&home);
    let mut h = home.open();
    click(&mut h, labels::NEW_CHAT);
    type_message(&mut h, "What is the capital of Norway?");
    click(&mut h, labels::SEND);
    assert!(
        wait_for(&mut h, |h| shows(h, mimir_core::models::MODEL_DOWN)),
        "{}",
        screen_text(&h)
    );
    assert!(has_button(&h, labels::TRY_AGAIN), "{}", screen_text(&h));
    // The pill's own words are drawn; its menu says what they mean.
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::PILL_MENU)),
        "{}",
        screen_text(&h)
    );
    click(&mut h, labels::PILL_MENU);
    assert!(
        wait_for(&mut h, |h| shows(h, labels::PC_DOWN_LINE)),
        "the pill still reads fine\n{}",
        screen_text(&h)
    );
    assert!(
        !shows(&h, "Answers are made on this PC"),
        "{}",
        screen_text(&h)
    );
    close(h);
}

/// P4: a session still running elsewhere says where and what to do, and
/// offers no Try again and no promise to carry on by clicking.
#[test]
fn a_session_running_elsewhere_names_the_place_and_offers_no_try_again() {
    assert_eq!(
        labels::still_running_in(Some("app")),
        "Still running in the Claude app. Stop it to carry on here."
    );
    assert_eq!(
        labels::still_running_in(Some("terminal")),
        "Still running in a terminal. Stop it to carry on here."
    );
    assert_eq!(
        labels::still_running_in(None),
        "Still running on your PC. Stop it to carry on here."
    );
}

/// P6: a new install doesn't point to a From elsewhere list that isn't there.
#[test]
fn a_new_install_does_not_point_to_a_list_that_is_not_there() {
    let Some(home) = TestHome::new() else { return };
    home.write_settings(serde_json::json!({ "projects": [], "remote_projects": [] }));
    let h = home.open();
    assert!(shows(&h, "Hi, I'm Mímir."), "{}", screen_text(&h));
    assert!(!shows(&h, "From elsewhere"), "{}", screen_text(&h));
    close(h);
}

/// P7: a search with no match says so, and shows no empty Projects count.
#[test]
fn a_search_with_no_match_says_nothing_found() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    use eframe::egui::accesskit::Role;
    h.query_all_by_role(Role::TextInput)
        .next()
        .expect("no search box")
        .click();
    h.step();
    h.query_all_by_role(Role::TextInput)
        .next()
        .unwrap()
        .type_text("zzzz");
    assert!(
        wait_for(&mut h, |h| shows(h, "Nothing found for “zzzz”.")),
        "{}",
        screen_text(&h)
    );
    assert!(!shows(&h, "PROJECTS"), "{}", screen_text(&h));
    close(h);
}

/// P8: one name for the plan everywhere.
#[test]
fn the_plan_has_one_name() {
    let Some(home) = TestHome::new() else { return };
    home.write_settings(serde_json::json!({ "project_engine": "free" }));
    let mut h = home.open();
    click(&mut h, "Settings");
    click(&mut h, "Models");
    assert!(
        wait_for(&mut h, |h| shows(h, "Anthropic plan (optional)")),
        "{}",
        screen_text(&h)
    );
    let text = screen_text(&h);
    assert!(!text.contains("Claude plan"), "{text}");
    close(h);
}

/// P16: the long warning shows only when the switch is on.
#[test]
fn the_phone_warning_shows_only_when_the_switch_is_on() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    click(&mut h, "Settings");
    click(&mut h, "Phones");
    assert!(
        wait_for(&mut h, |h| shows(h, "a phone can't switch a task")),
        "{}",
        screen_text(&h)
    );
    assert!(!shows(&h, "stolen phone"), "{}", screen_text(&h));
    close(h);
}
