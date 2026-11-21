//! Settings › Labs: the notes switches, Run checks, and Report a problem
//! (its preview, Copy report, and the GitHub form's address). The browser
//! is never opened: MIMIR_NO_BROWSER=<file> gets the address instead.

mod common;
use common::*;
use eframe::egui::accesskit::Role;
use egui_kittest::kittest::Queryable;

fn open_labs(h: &mut egui_kittest::Harness<'static, station_ui::App>) {
    click(h, "Settings");
    click(h, "Labs");
    assert!(
        wait_for(h, |h| shows(h, "Report a problem")),
        "Labs doesn't show Report a problem\n{}",
        screen_text(h)
    );
}

#[test]
fn labs_has_the_notes_checks_and_report() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    open_labs(&mut h);
    for words in [
        "Keep notes for fixing problems",
        "Run checks",
        "Report a problem",
        "Include the last checks",
        "This is everything that will be in the report:",
        "Mímir problem report",
        "Copy report",
        "Report on GitHub",
    ] {
        assert!(
            wait_for(&mut h, |h| shows(h, words)),
            "Labs doesn't show {words:?}\n{}",
            screen_text(&h)
        );
    }
    close(h);
}

#[test]
fn run_checks_lists_each_check() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    open_labs(&mut h);
    click(&mut h, "Run checks");
    for words in [
        "Claude Code",
        "Free models on this PC",
        "Phones",
        "Voice",
        "Space on this PC",
        "App version",
    ] {
        assert!(
            wait_for(&mut h, |h| shows(h, words)),
            "Run checks doesn't show {words:?}\n{}",
            screen_text(&h)
        );
    }
    close(h);
}

#[test]
fn the_preview_has_what_was_typed_and_the_link_opens_the_form() {
    let Some(home) = TestHome::new() else { return };
    let file = home.root.join("browser.txt");
    set_env("MIMIR_NO_BROWSER", &file);
    let mut h = home.open();
    open_labs(&mut h);
    h.query_all_by_role(Role::MultilineTextInput)
        .next()
        .expect("no box for what happened")
        .click();
    h.step();
    h.query_all_by_role(Role::MultilineTextInput)
        .next()
        .unwrap()
        .type_text("It stops when I open a chat");
    h.step();
    assert!(
        wait_for(&mut h, |h| shows(
            h,
            "What happened: It stops when I open a chat"
        )),
        "the preview doesn't show what was typed\n{}",
        screen_text(&h)
    );
    click(&mut h, "Report on GitHub");
    assert!(
        wait_for(&mut h, |h| shows(
            h,
            "The report is copied: paste it into the form."
        )),
        "no note about the copied report\n{}",
        screen_text(&h)
    );
    let url = std::fs::read_to_string(&file).expect("the form's address wasn't opened");
    assert!(
        url.starts_with("https://github.com/ODN-WKS/mimir/issues/new?template=report-a-problem.yml&title=It%20stops"),
        "{url}"
    );
    assert!(
        url.contains("&what=It%20stops%20when%20I%20open%20a%20chat&report="),
        "{url}"
    );
    assert!(url.len() <= 2000, "{}", url.len());
    close(h);
}

#[test]
fn help_has_report_a_problem() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    click(&mut h, "Settings");
    click(&mut h, "About");
    click(&mut h, "Report a problem…");
    assert!(
        wait_for(&mut h, |h| shows(h, "Include the last checks")),
        "Report a problem… didn't open Labs\n{}",
        screen_text(&h)
    );
    close(h);
}
