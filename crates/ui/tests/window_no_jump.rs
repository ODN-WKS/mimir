//! Window checks for notices that must not push the page around: when an
//! approval bar, a question card or the like appears, the first row of the
//! task list and the message box stay where they were (QA 2026-11-19).

mod common;
use common::*;
use eframe::egui::{self, Rect, Shape};
use egui_kittest::kittest::Queryable;

type Window = egui_kittest::Harness<'static, station_ui::App>;

/// Where the first list row and the message box are.
#[derive(Debug, PartialEq)]
struct Anchors {
    first_row: Option<egui::Pos2>,
    composer: Option<Rect>,
}

fn drawn_text(h: &Window) -> Vec<(String, Rect)> {
    fn walk(shape: &Shape, out: &mut Vec<(String, Rect)>) {
        match shape {
            Shape::Text(t) => out.push((t.galley.text().to_string(), t.visual_bounding_rect())),
            Shape::Vec(v) => v.iter().for_each(|s| walk(s, out)),
            _ => {}
        }
    }
    let mut out = vec![];
    for c in &h.output().shapes {
        walk(&c.shape, &mut out);
    }
    out
}

fn anchors(h: &Window, row_words: &str) -> Anchors {
    // The side list is the left strip; the row's text starts with the task.
    let first_row = drawn_text(h)
        .into_iter()
        .filter(|(t, r)| t.contains(row_words) && r.min.x < 400.0)
        .map(|(_, r)| r.min.round())
        .next();
    let composer = h
        .query_all_by_role(eframe::egui::accesskit::Role::MultilineTextInput)
        .next()
        .map(|n| n.rect());
    Anchors {
        first_row,
        composer,
    }
}

fn open(ppp: f32) -> Window {
    egui_kittest::Harness::builder()
        .with_size(egui::Vec2::new(1280.0, 800.0))
        .with_pixels_per_point(ppp)
        .with_max_steps(400)
        .allow_missing_glyphs()
        .with_accessibility_check(false)
        .build_eframe(|cc| station_ui::App::new(cc, "0.15", station_ui::public_key()))
}

/// Starts a task on `recording`, takes the anchors while it works, lets the
/// notice come (`appears`), and asserts neither anchor moved.
fn nothing_moves(
    recording: &str,
    prompt: &str,
    notice: &str,
    composer_too: bool,
    appears: impl Fn(&Window) -> bool,
) {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", recording);
    home.hold_replies();
    home.let_through(1);
    let mut h = open(1.25);
    settle(&mut h);
    start_task(&mut h, prompt);
    assert!(
        wait_for(&mut h, |h| shows(h, "Working")),
        "{notice}: the task never showed as working\n{}",
        screen_text(&h)
    );
    // P17: the task's row is named by its start time.
    let name = shown_time_name(&h).unwrap_or_default();
    let row = name.as_str();
    let before = anchors(&h, row);
    assert!(
        before.first_row.is_some() && before.composer.is_some(),
        "{notice}: could not find the first row or the message box before: {before:?}\n{}",
        screen_text(&h)
    );
    home.let_through(100);
    assert!(
        wait_for(&mut h, |h| appears(h)),
        "{notice}: it never showed\n{}",
        screen_text(&h)
    );
    let after = anchors(&h, row);
    assert_eq!(
        before.first_row, after.first_row,
        "{notice}: appearing moved the first list row"
    );
    if composer_too {
        assert_eq!(
            before.composer, after.composer,
            "{notice}: appearing moved the message box"
        );
    }
    close(h);
}

#[test]
fn the_approval_bar_moves_not_the_list() {
    approval(false);
}

#[test]
fn the_question_card_moves_not_the_list() {
    question(false);
}

// Found 2026-11-19: both cards push the message box down (the question card
// by 36 points, the approval bar by 165 at 125%). Run with
// `cargo nextest run --run-ignored only` until the app is fixed, then drop
// the `ignore`.
#[test]
#[ignore = "known: the approval bar moves the message box"]
fn the_approval_bar_moves_not_the_message_box() {
    approval(true);
}

#[test]
#[ignore = "known: the question card moves the message box"]
fn the_question_card_moves_not_the_message_box() {
    question(true);
}

fn approval(composer_too: bool) {
    nothing_moves(
        "allow.jsonl",
        "Use the Bash tool to run: touch made-by-test.txt",
        "the approval bar",
        composer_too,
        |h| has_button(h, station_ui::labels::ALLOW),
    );
}

fn question(composer_too: bool) {
    let card = station_ui::labels::question_card(
        "Should I also sort the notes alphabetically, or keep them in this order?",
    );
    nothing_moves(
        "question.jsonl",
        "Tidy up notes.txt.",
        "the question card",
        composer_too,
        move |h| has_label(h, &card),
    );
}
