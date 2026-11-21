//! Window checks: every menu and pop-up opens fully inside the window, at
//! any size and at 150% zoom, and nothing (a tooltip) covers it.

mod common;
use common::*;
use eframe::egui;
use egui_kittest::Harness;
use egui_kittest::kittest::{NodeT, Queryable};
use station_ui::App;

/// Every pop-up open now: inside the window, and not under a tooltip.
fn popups_fit(h: &Harness<'static, App>, what: &str) -> usize {
    let screen = h.ctx.content_rect();
    let (popups, tips): (Vec<_>, Vec<_>) = h.ctx.memory(|m| {
        m.areas()
            .visible_layer_ids()
            .into_iter()
            .filter(|l| matches!(l.order, egui::Order::Foreground | egui::Order::Tooltip))
            .filter_map(|l| m.area_rect(l.id).map(|r| (l.order, r)))
            .partition(|(o, _)| *o == egui::Order::Foreground)
    });
    for (_, r) in &popups {
        assert!(
            screen.expand(0.5).contains_rect(*r),
            "{what}: a pop-up at {r:?} is cut off by the window ({screen:?})"
        );
        for (_, t) in &tips {
            assert!(
                !t.intersects(r.shrink(1.0)),
                "{what}: a tooltip at {t:?} covers the pop-up at {r:?}"
            );
        }
    }
    popups.len()
}

/// A click at a point, the pointer staying there.
fn click_at(h: &mut Harness<'static, App>, pos: egui::Pos2) {
    h.hover_at(pos);
    frames(h);
    for pressed in [true, false] {
        h.event(egui::Event::PointerButton {
            pos,
            button: egui::PointerButton::Primary,
            pressed,
            modifiers: egui::Modifiers::NONE,
        });
        h.step();
    }
}

/// Long enough with the pointer resting for any tooltip to show.
fn linger(h: &mut Harness<'static, App>) {
    for _ in 0..90 {
        h.step();
    }
}

/// Frames for a pop-up to open and settle.
fn frames(h: &mut Harness<'static, App>) {
    for _ in 0..12 {
        h.step();
    }
}

fn close_popups(h: &mut Harness<'static, App>) {
    h.key_press(egui::Key::Escape);
    frames(h);
}

fn task_row_label(h: &Harness<'static, App>) -> String {
    h.query_all_by(|n| {
        n.role() == egui::accesskit::Role::Button
            && n.label().is_some_and(|l| l.starts_with("Task "))
    })
    .next()
    .and_then(|n| n.accesskit_node().label().map(|l| l.to_string()))
    .expect("no task row in the side list")
}

/// The ⋯ buttons on screen, left to right.
fn dots(h: &Harness<'static, App>) -> Vec<egui::Rect> {
    let mut v: Vec<egui::Rect> = h
        .query_all_by(|n| {
            n.role() == egui::accesskit::Role::Button && n.label().is_some_and(|l| l.trim() == "⋯")
        })
        .map(|n| n.rect())
        .collect();
    v.sort_by(|a, b| a.left().total_cmp(&b.left()));
    v
}

fn open_each_menu(h: &mut Harness<'static, App>, what: &str) {
    // The list is longer than a small window: the row is at its end.
    scroll_side_list_down(h);
    let row = task_row_label(h);
    // The task row's right-click menu, opened at the row.
    h.get_by_label(&row).click_secondary();
    linger(h);
    assert!(
        popups_fit(h, &format!("{what}, row right-click")) > 0,
        "{what}: no row menu"
    );
    close_popups(h);
    // The row's ⋯ (shown while the pointer rests on the row).
    h.get_by_label(&row).hover();
    frames(h);
    let row_rect = h.get_by_label(&row).rect();
    if let Some(d) = dots(h).into_iter().find(|d| row_rect.intersects(*d)) {
        click_at(h, d.center());
        frames(h);
        assert!(
            popups_fit(h, &format!("{what}, row ⋯")) > 0,
            "{what}: no row ⋯ menu"
        );
        close_popups(h);
    }
    // The task header's ⋯ (the rightmost one outside the side list).
    if let Some(d) = dots(h).into_iter().rfind(|d| !row_rect.intersects(*d)) {
        click_at(h, d.center());
        frames(h);
        assert!(
            popups_fit(h, &format!("{what}, header ⋯")) > 0,
            "{what}: no header menu"
        );
        close_popups(h);
    }
    // The pill at the top of the middle: who answers, and the page's actions.
    let pill = h
        .query_all_by_role_and_label(egui::accesskit::Role::Button, station_ui::labels::PILL_MENU)
        .next()
        .map(|n| n.rect());
    if let Some(pill) = pill {
        click_at(h, pill.center());
        frames(h);
        assert!(
            popups_fit(h, &format!("{what}, who-answers pill")) > 0,
            "{what}: no pill menu"
        );
        close_popups(h);
    }
    // The mode, model and effort pickers at the bottom of the window.
    let combos: Vec<egui::Rect> = h
        .query_all_by(|n| n.role() == egui::accesskit::Role::ComboBox)
        .map(|n| n.rect())
        .collect();
    assert!(!combos.is_empty(), "{what}: no pickers\n{}", screen_text(h));
    for c in combos {
        click_at(h, c.center());
        frames(h);
        assert!(
            popups_fit(h, &format!("{what}, picker at {c:?}")) > 0,
            "{what}: the picker at {c:?} didn't open"
        );
        close_popups(h);
    }
}

#[test]
fn every_menu_opens_fully_inside_the_window() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    close(h);
    for (w, ht) in [(1280.0, 800.0), (900.0, 560.0), (760.0, 480.0)] {
        for zoom in [1.0, 1.5] {
            let mut h = home.open_at(w, ht);
            h.ctx.set_zoom_factor(zoom);
            frames(&mut h);
            open_each_menu(&mut h, &format!("{w}×{ht} at {}%", zoom * 100.0));
            close(h);
        }
    }
}
