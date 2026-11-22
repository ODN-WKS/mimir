//! Files dropped on the window only attach where a message is written, and
//! the right-click Paste takes a picture off the clipboard too.

mod common;
use common::*;
use eframe::egui;
use egui_kittest::Harness;
use egui_kittest::kittest::Queryable;
use station_ui::{App, labels};
use std::sync::Arc;

fn home() -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
        "details": true,
    }));
    Some(home)
}

fn open_new_task(home: &TestHome, h: &mut Harness<'static, App>) {
    let name = home
        .project
        .file_name()
        .unwrap()
        .to_string_lossy()
        .to_string();
    let label = labels::new_task_in(&name);
    assert!(
        wait_for(h, |h| has_button(h, &label)),
        "no {label:?}\n{}",
        screen_text(h)
    );
    click(h, &label);
}

/// A made-up dropped file.
#[derive(Debug)]
struct Dropped(std::path::PathBuf);

impl egui::DroppedFile for Dropped {
    fn path(&self) -> &std::path::Path {
        &self.0
    }
    fn bytes(&self) -> Result<Vec<u8>, String> {
        std::fs::read(&self.0).map_err(|e| e.to_string())
    }
}

fn drop_file(h: &mut Harness<'static, App>, path: &std::path::Path) {
    h.input_mut()
        .dropped_files
        .push(Arc::new(Dropped(path.to_path_buf())));
    for _ in 0..3 {
        h.step();
    }
}

#[test]
fn a_file_dropped_on_settings_is_ignored_and_does_not_wait_for_a_task() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    let file = home.project.join("hidden-note.txt");
    std::fs::write(&file, "x").unwrap();
    click(&mut h, "Settings");
    drop_file(&mut h, &file);
    click(&mut h, "‹ Back");
    open_new_task(&home, &mut h);
    for _ in 0..5 {
        h.step();
    }
    assert!(
        !shows(&h, "hidden-note.txt"),
        "a file dropped on Settings turned up as an attachment\n{}",
        screen_text(&h)
    );
    // Dropped where a message is written, it does attach.
    drop_file(&mut h, &file);
    assert!(
        wait_for(&mut h, |h| shows(h, "hidden-note.txt")),
        "a file dropped on New task did not attach\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn paste_in_the_right_click_menu_takes_a_picture_off_the_clipboard() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    open_new_task(&home, &mut h);
    // Only a picture on the clipboard, no words.
    h.ctx.data_mut(|d| {
        d.insert_temp(
            egui::Id::unique("test-clipboard-image"),
            Arc::new(egui::ColorImage::filled([40, 30], egui::Color32::GREEN)),
        )
    });
    let at = h
        .query_all_by_role_and_label(egui::accesskit::Role::TextInput, "")
        .next()
        .map(|n| n.rect().center())
        .or_else(|| {
            h.query_all_by_role(egui::accesskit::Role::MultilineTextInput)
                .next()
                .map(|n| n.rect().center())
        })
        .unwrap_or_else(|| panic!("no typing box\n{}", screen_text(&h)));
    for pressed in [true, false] {
        h.hover_at(at);
        h.step();
        h.event(egui::Event::PointerButton {
            pos: at,
            button: egui::PointerButton::Secondary,
            pressed,
            modifiers: egui::Modifiers::NONE,
        });
        h.step();
    }
    for _ in 0..5 {
        h.step();
    }
    click(&mut h, "Paste");
    assert!(
        wait_for(&mut h, |h| shows(h, "pasted-")),
        "Paste did not attach the picture\n{}",
        screen_text(&h)
    );
    close(h);
}
