//! A conversation going on anywhere (the phone, claude.ai, the Claude app,
//! a terminal) is listed once, under its project, shows as running and
//! follows along. There is no "Active now" section. Titles are never system
//! text.

mod common;
use common::*;
use egui_kittest::kittest::{NodeT, Queryable};
use station_ui::labels;
use std::io::Write;
use std::path::PathBuf;

const SESSION: &str = "4c1f2a33-5b6e-4d7f-8a9b-0c1d2e3f4a5b";

/// The outside session's transcript, its conversation `ago` seconds back.
fn transcript(home: &TestHome, ago: i64) -> String {
    let quoted = serde_json::to_string(&home.project.display().to_string()).unwrap();
    let at = iso(ago);
    [
        format!(
            r#"{{"type":"user","message":{{"role":"user","content":"Fix the garden gate latch"}},"uuid":"u1","cwd":{quoted},"sessionId":"{SESSION}","timestamp":"{at}"}}"#
        ),
        r#"{"type":"ai-title","aiTitle":"<local-command-stdout>","sessionId":"x"}"#.to_string(),
        format!(
            r#"{{"type":"assistant","message":{{"role":"assistant","content":[{{"type":"text","text":"Looking at the latch now."}}],"stop_reason":"end_turn"}},"uuid":"a1","timestamp":"{at}"}}"#
        ),
    ]
    .join("\n")
        + "\n"
}

/// A task the window knows, whose conversation goes on outside it, `ago`
/// seconds back: a title that is system text, a transcript Claude Code
/// writes.
fn outside_session(home: &TestHome, ago: i64) -> PathBuf {
    let project = home.project.display().to_string();
    let folder = home
        .root
        .join(".claude/projects")
        .join(mimir_core::sessions::folder_name(&home.project));
    std::fs::create_dir_all(&folder).unwrap();
    let file = folder.join(format!("{SESSION}.jsonl"));
    std::fs::write(&file, transcript(home, ago)).unwrap();
    let store = mimir_core::store::Store::new(mimir_core::store::Store::default_dir()).unwrap();
    let mut t = mimir_core::task::Task::new(
        "1700000000-1-1".into(),
        "<command-message>resume</command-message>".into(),
        project,
        1_700_000_000,
    );
    t.session_id = Some(SESSION.into());
    t.status = mimir_core::task::Status::Done;
    store.save(&t).unwrap();
    file
}

/// Buttons whose spoken name has these words.
fn rows_with(h: &egui_kittest::Harness<'static, station_ui::App>, words: &str) -> usize {
    h.query_all_by(|n| {
        n.role() == eframe::egui::accesskit::Role::Button
            && n.label().is_some_and(|l| l.contains(words))
    })
    .count()
}

fn append(file: &PathBuf, line: &str) {
    let mut f = std::fs::OpenOptions::new().append(true).open(file).unwrap();
    writeln!(f, "{line}").unwrap();
}

#[test]
fn a_live_outside_session_is_at_the_top_and_follows_along() {
    let Some(home) = TestHome::new() else { return };
    let file = outside_session(&home, 0);
    let mut h = home.open();
    // Under its project only, named by the real topic.
    let up = wait_for(&mut h, |h| rows_with(h, "Fix the garden gate latch") == 1);
    assert!(up, "not listed once\n{}", screen_text(&h));
    assert!(!shows(&h, "Active now"), "{}", screen_text(&h));
    assert!(
        !shows(&h, "<command-message>") && !shows(&h, "<local-command"),
        "system text used as a title\n{}",
        screen_text(&h)
    );
    assert!(
        wait_for(&mut h, |h| shows(h, labels::LIVE)),
        "not shown as live\n{}",
        screen_text(&h)
    );
    assert!(
        !shows(&h, "Done ·"),
        "a live task shows Done\n{}",
        screen_text(&h)
    );
    // What is said there arrives here as it happens.
    let label = h
        .query_all_by(|n| {
            n.role() == eframe::egui::accesskit::Role::Button
                && n.label()
                    .is_some_and(|l| l.contains("Fix the garden gate latch"))
        })
        .next()
        .and_then(|n| n.accesskit_node().label().map(|l| l.to_string()))
        .unwrap();
    click(&mut h, &label);
    assert!(wait_for(&mut h, |h| shows(h, "Looking at the latch now.")));
    let mut f = std::fs::OpenOptions::new()
        .append(true)
        .open(&file)
        .unwrap();
    writeln!(
        f,
        r#"{{"type":"assistant","message":{{"role":"assistant","content":[{{"type":"text","text":"The latch is fixed and oiled."}}],"stop_reason":"end_turn"}},"uuid":"a2"}}"#
    )
    .unwrap();
    drop(f);
    let live = wait_for(&mut h, |h| shows(h, "The latch is fixed and oiled."));
    assert!(
        live,
        "the new message didn't arrive live\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn after_two_hours_of_silence_it_sits_only_in_its_folder() {
    let Some(home) = TestHome::new() else { return };
    // Talked in 1 h 50 min ago: active, not live.
    let quiet = mimir_core::task::ACTIVE_SECS - 10 * 60;
    let file = outside_session(&home, quiet);
    let mut h = home.open();
    let up = wait_for(&mut h, |h| rows_with(h, "Fix the garden gate latch") == 1);
    assert!(up, "not listed once\n{}", screen_text(&h));
    assert!(!shows(&h, "Active now"));
    // Claude Code's bookkeeping written now (as when Remote Control starts)
    // doesn't make it live.
    append(
        &file,
        &format!(
            r#"{{"type":"system","subtype":"bridge_status","timestamp":"{}"}}"#,
            iso(0)
        ),
    );
    append(&file, r#"{"type":"artifact-autoreact-ledger"}"#);
    for _ in 0..80 {
        h.step();
        std::thread::sleep(std::time::Duration::from_millis(25));
    }
    assert!(
        !shows(&h, labels::LIVE),
        "bookkeeping made it live\n{}",
        screen_text(&h)
    );
    assert!(!shows(&h, labels::FOLLOWS_ALONG));
    // The clock moves past two hours of silence: still listed once.
    std::fs::write(&file, transcript(&home, mimir_core::task::ACTIVE_SECS + 60)).unwrap();
    let gone = wait_for(&mut h, |h| rows_with(h, "Fix the garden gate latch") == 1);
    assert!(gone, "not listed once after 2 h\n{}", screen_text(&h));
    close(h);
}

/// A conversation that finished a minute ago doesn't keep the window
/// redrawing: once it settles, the window asks for its next frame no sooner
/// than a turning mark needs (it used to ask for every frame, using most of
/// a processor core all the time).
#[test]
fn a_finished_answer_lets_the_window_rest() {
    let Some(home) = TestHome::new() else { return };
    outside_session(&home, 60);
    let mut h = home.open();
    assert!(wait_for(&mut h, |h| rows_with(
        h,
        "Fix the garden gate latch"
    ) == 1));
    // Let the rows settle where they go.
    for _ in 0..60 {
        h.step();
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    let mut eager = 0;
    for _ in 0..50 {
        h.step();
        std::thread::sleep(std::time::Duration::from_millis(20));
        let delay = h.output().viewport_output[&eframe::egui::ViewportId::ROOT].repaint_delay;
        if delay < std::time::Duration::from_millis(30) {
            eager += 1;
        }
    }
    assert_eq!(
        eager,
        0,
        "the window asked to be redrawn at once {eager} times in 50 frames\n{}",
        screen_text(&h)
    );
    close(h);
}
