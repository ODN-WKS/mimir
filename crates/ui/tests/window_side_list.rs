//! Window checks for the side list staying put while the window just runs.
//! Seen on the owner's PC: the list's bottom crept up a little on every
//! frame (the foot under it grew by 2 points each time) until whole
//! projects were cut off, which looked like the list fading from the bottom
//! to the top.

mod common;
use common::*;
use eframe::egui::{self, Rect, Shape};

type Window = egui_kittest::Harness<'static, station_ui::App>;

/// Every piece of text the last frame drew, with the part of the window it
/// was allowed to show in.
fn drawn_text(h: &Window) -> Vec<(String, Rect, Rect)> {
    fn walk(shape: &Shape, clip: Rect, out: &mut Vec<(String, Rect, Rect)>) {
        match shape {
            Shape::Text(t) => {
                out.push((t.galley.text().to_string(), t.visual_bounding_rect(), clip))
            }
            Shape::Vec(v) => v.iter().for_each(|s| walk(s, clip, out)),
            _ => {}
        }
    }
    let mut out = vec![];
    for c in &h.output().shapes {
        walk(&c.shape, c.clip_rect, &mut out);
    }
    out
}

/// Where text with these words was drawn, if it was drawn whole (not cut
/// off by the edge of the part it shows in).
fn whole(h: &Window, words: &str) -> Option<egui::Pos2> {
    drawn_text(h)
        .into_iter()
        .filter(|(t, _, _)| t.contains(words))
        .find(|(_, r, clip)| clip.contains_rect(r.shrink(0.5)))
        .map(|(_, r, _)| r.min.round())
}

#[test]
fn the_side_list_stays_whole_while_the_window_runs() {
    let Some(home) = TestHome::new() else { return };
    // Several projects, the one with Remote Control last, so its rows sit
    // at the bottom of the list where the creep showed first.
    let mut projects = vec![];
    for name in ["Alpha-notes", "Beta-notes", "Gamma-notes"] {
        let p = home.root.join(name);
        std::fs::create_dir_all(&p).unwrap();
        projects.push(p.display().to_string());
    }
    let keeper = home.project.display().to_string();
    projects.push(keeper.clone());
    home.write_settings(serde_json::json!({
        "projects": projects,
        "last_project": keeper,
        "remote_projects": [],
    }));
    // A task running in it, written line by line (a phone's own session,
    // which this check used before, is gone with Phone too).
    home.play("run-1.jsonl", "live.jsonl");
    let mut h = egui_kittest::Harness::builder()
        .with_size(egui::Vec2::new(1280.0, 800.0))
        // A desktop scaled by 1.25, as many are.
        .with_pixels_per_point(1.25)
        .with_max_steps(400)
        .allow_missing_glyphs()
        .with_accessibility_check(false)
        .build_eframe(|cc| station_ui::App::new(cc, "0.15", station_ui::public_key()));
    settle(&mut h);
    click(&mut h, "+  New task");
    start_task(&mut h, "Tidy up notes.txt.");
    let row = "Tidy up notes.txt.";
    assert!(
        wait_for(&mut h, |h| whole(h, row).is_some()),
        "the task never showed whole in the side list\n{}",
        screen_text(&h)
    );
    let watched = [
        "Alpha-notes",
        "Beta-notes",
        "Gamma-notes",
        "project",
        row,
        "Open a project folder",
    ];
    let first: Vec<_> = watched.iter().map(|w| whole(&h, w)).collect();
    // Many frames, with real time passing, as on the owner's PC.
    for frame in 0..600 {
        h.step();
        if frame % 20 == 0 {
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        for (w, was) in watched.iter().zip(&first) {
            let now = whole(&h, w);
            assert!(
                now.is_some() && now == *was,
                "after {frame} frames, {w:?} is no longer drawn whole where it was \
                 ({was:?} then, {now:?} now)"
            );
        }
    }
    close(h);
}

/// The "N running" note sits on the project row in a slot that keeps its
/// height: when it appears, "New task" and Settings do not move. Checked at
/// 100% and 150%.
#[test]
fn the_status_pill_moves_nothing_at_100_percent() {
    pill_moves_nothing(1.0);
}

#[test]
fn the_status_pill_moves_nothing_at_150_percent() {
    pill_moves_nothing(1.5);
}

fn pill_moves_nothing(ppp: f32) {
    {
        let Some(home) = TestHome::new() else {
            return;
        };
        home.play("run-1.jsonl", "live.jsonl");
        home.hold_replies();
        home.let_through(1);
        let mut h = egui_kittest::Harness::builder()
            .with_size(egui::Vec2::new(1280.0, 800.0))
            .with_pixels_per_point(ppp)
            .with_max_steps(400)
            .allow_missing_glyphs()
            .with_accessibility_check(false)
            .build_eframe(|cc| station_ui::App::new(cc, "0.15", station_ui::public_key()));
        settle(&mut h);
        let watched = ["New task", "Settings"];
        assert!(
            wait_for(&mut h, |h| watched.iter().all(|w| whole(h, w).is_some())),
            "at {ppp}: not drawn whole\n{}",
            screen_text(&h)
        );
        let before: Vec<_> = watched.iter().map(|w| whole(&h, w)).collect();
        start_task(&mut h, "Tidy up notes.txt.");
        assert!(
            wait_for(&mut h, |h| shows(h, "Working")),
            "at {ppp}: the running note never showed\n{}",
            screen_text(&h)
        );
        let after: Vec<_> = watched.iter().map(|w| whole(&h, w)).collect();
        assert_eq!(before, after, "at {ppp}: the running note moved something");
        close(h);
    }
}
