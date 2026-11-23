//! Settings: a page of its own. A column of sections on the left (with a
//! search box), one section shown at a time on the right.

use crate::app::{Action, App, SettingsPage};
use crate::places;
use crate::theme::{self, JADE, MUTED, ORANGE};
use eframe::egui::{self, RichText, ScrollArea};

/// A section's heading and its one-line explanation.
fn intro(ui: &mut egui::Ui, page: SettingsPage) {
    ui.label(theme::title(page.name(), 20.0));
    ui.label(RichText::new(page.about()).color(MUTED));
    ui.add_space(10.0);
}

/// Space between groups of rows.
fn gap(ui: &mut egui::Ui) {
    ui.add_space(10.0);
}

impl App {
    pub(crate) fn settings_view(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let now = ui.input(|i| i.time);
        let at_id = egui::Id::unique("settings-page-at");
        // Esc goes back to where you were (unless a menu or box is open).
        if ui.input(|i| i.key_pressed(egui::Key::Escape))
            && !egui::Popup::is_any_open(ui.ctx())
            && ui.memory(|m| m.focused().is_none())
        {
            actions.push(Action::CloseSettings);
        }
        // Opened just now (not shown the frame before): it fades in.
        let frame = ui.ctx().cumulative_frame_nr();
        let seen_id = egui::Id::unique("settings-seen-frame");
        let seen: Option<u64> = ui.ctx().data(|d| d.get_temp(seen_id));
        if seen.is_none_or(|f| f + 1 < frame) {
            ui.ctx().data_mut(|d| d.insert_temp(at_id, now));
        }
        ui.ctx().data_mut(|d| d.insert_temp(seen_id, frame));
        let since: f64 = ui.ctx().data(|d| d.get_temp(at_id)).unwrap_or(f64::MIN);
        let fade = ui.style().animation_time.max(0.0) as f64 * 1.2;
        let shown = if fade <= 0.0 {
            1.0
        } else {
            ((now - since) / fade).clamp(0.0, 1.0) as f32
        };
        if shown < 1.0 {
            ui.ctx().request_repaint();
        }
        let search_id = egui::Id::unique("settings-search");
        let mut search: String = ui.ctx().data(|d| d.get_temp(search_id)).unwrap_or_default();
        egui::Panel::left("settings-sections")
            .resizable(false)
            .exact_size(220.0)
            .frame(
                egui::Frame::new()
                    .fill(theme::PANEL)
                    .inner_margin(egui::Margin::same(12)),
            )
            .show(ui, |ui| {
                if ui
                    .add(theme::quiet("‹ Back"))
                    .on_hover_text("Esc")
                    .clicked()
                {
                    actions.push(Action::CloseSettings);
                }
                ui.add_space(8.0);
                ui.heading("Settings");
                ui.add_space(8.0);
                let said = ui.label(RichText::new("Search Settings").color(MUTED).size(13.0));
                let edit = ui
                    .add(
                        egui::TextEdit::singleline(&mut search)
                            .hint_text("voice, phones, models")
                            .desired_width(f32::INFINITY),
                    )
                    .labelled_by(said.id);
                crate::menus::edit_menu(&edit, "settings-search");
                ui.add_space(8.0);
                let found: Vec<SettingsPage> = SettingsPage::ALL
                    .into_iter()
                    .filter(|p| p.matches(&search))
                    .collect();
                if found.is_empty() {
                    ui.label(
                        RichText::new("Nothing matches that.")
                            .color(MUTED)
                            .size(13.0),
                    );
                }
                // Enter in the search box opens the first section found.
                let enter = edit.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                for page in found.iter().copied() {
                    let on = self.settings_page == page;
                    let pick = ui
                        .add_sized(
                            [ui.available_width(), 30.0],
                            egui::Button::selectable(on, page.name()),
                        )
                        .on_hover_text(page.about());
                    if (pick.clicked() || (enter && Some(&page) == found.first())) && !on {
                        self.settings_page = page;
                        ui.ctx().data_mut(|d| d.insert_temp(at_id, now));
                    }
                }
            });
        ui.ctx().data_mut(|d| d.insert_temp(search_id, search));
        egui::CentralPanel::default()
            .frame(egui::Frame::new().inner_margin(egui::Margin::symmetric(24, 8)))
            .show(ui, |ui| {
                ScrollArea::vertical()
                    .id_salt(("settings", self.settings_page.name()))
                    .show(ui, |ui| {
                        ui.set_opacity(shown);
                        ui.set_max_width(780.0_f32.min(ui.available_width()));
                        let page = self.settings_page;
                        intro(ui, page);
                        match page {
                            SettingsPage::General => self.settings_general(ui, actions),
                            SettingsPage::Models => self.models_page(ui),
                            SettingsPage::Phone => self.phone_card(ui),
                            SettingsPage::Voice => self.settings_voice(ui),
                            SettingsPage::Notifications => self.settings_notifications(ui),
                            SettingsPage::Storage => self.settings_storage(ui, actions),
                            SettingsPage::Labs => self.settings_labs(ui),
                            SettingsPage::About => self.settings_about(ui),
                        }
                        ui.add_space(24.0);
                    });
            });
    }

    fn settings_general(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Who answers");
            ui.horizontal_wrapped(|ui| {
                ui.label(crate::view_models::power_words(&self.settings));
                if ui.add(theme::quiet("Change in Models")).clicked() {
                    self.settings_page = SettingsPage::Models;
                }
            });
        });
        gap(ui);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "When a task finishes");
            if ui
                .checkbox(
                    &mut self.settings.finish_summary,
                    "Ask for a short summary when it is done",
                )
                .changed()
            {
                self.settings_dirty = true;
            }
            let plan_note = if self.settings.online_set_up() {
                " This uses a little more of your Anthropic plan each time."
            } else {
                ""
            };
            ui.label(
                RichText::new(format!(
                    "After Mímir changed files or ran commands, it tells you what it changed, \
                         what it checked and what is left for you. Not after plain answers.{plan_note}"
                ))
                .color(MUTED)
                .size(13.0),
            );
        });
        gap(ui);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Text size");
            let now = ui.ctx().zoom_factor();
            ui.horizontal(|ui| {
                for (name, z) in [
                    ("Smaller", 0.9),
                    ("Normal", 1.0),
                    ("Larger", 1.15),
                    ("Largest", 1.3),
                ] {
                    let b = if (now - z).abs() < 0.01 {
                        theme::quiet(name).fill(theme::JADE_DIM)
                    } else {
                        theme::quiet(name)
                    };
                    if ui.add(b).clicked() {
                        ui.ctx().set_zoom_factor(z);
                    }
                }
            });
            ui.label(
                RichText::new(format!(
                    "Now {:.0}%. Ctrl+plus and Ctrl+minus also work; Ctrl+0 is normal.",
                    now * 100.0
                ))
                .color(MUTED)
                .size(13.0),
            );
        });
        gap(ui);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Keyboard shortcuts");
            ui.horizontal_wrapped(|ui| {
                ui.label(
                    RichText::new("Every shortcut in one list (also Ctrl+/).")
                        .color(MUTED)
                        .size(13.0),
                );
                if ui.add(theme::quiet("Show the shortcuts")).clicked() {
                    self.shortcuts_open = true;
                }
            });
        });
        gap(ui);
        // Only with Online project work set up and at least one listed.
        let online_work = !self.settings.project_free();
        if let Ok(r) = &self.station
            && online_work
            && !(if r.mcp_servers.is_empty() {
                &self.settings.connectors
            } else {
                &r.mcp_servers
            })
            .is_empty()
        {
            theme::card().show(ui, |ui| {
                ui.set_width(ui.available_width());
                theme::heading(ui, "Connectors");
                // The last ones Claude Code reported, also after a restart.
                let list = if r.mcp_servers.is_empty() {
                    &self.settings.connectors
                } else {
                    &r.mcp_servers
                };
                for (name, status) in list {
                    let c = if status == "connected" { JADE } else { ORANGE };
                    ui.horizontal(|ui| {
                        ui.label(name);
                        ui.label(RichText::new(status.replace('-', " ")).color(c).size(13.0));
                    });
                }
            });
        }
        let _ = actions;
    }

    fn settings_voice(&mut self, ui: &mut egui::Ui) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Read aloud");
            ui.horizontal(|ui| {
                // The voice that really reads: the natural one only when it is
                // here; otherwise the desktop voice reads and looks chosen.
                let natural =
                    !self.settings.read_desktop_voice && places::voice_status() == "Ready";
                fn pick(on: bool, text: &str) -> egui::Button<'_> {
                    if on {
                        theme::quiet(text).fill(theme::JADE_DIM).selected(true)
                    } else {
                        theme::quiet(text).selected(false)
                    }
                }
                if ui.add(pick(natural, "Natural voice (Ingrid)")).clicked() {
                    self.settings.read_desktop_voice = false;
                    self.settings_dirty = true;
                }
                if ui.add(pick(!natural, "Desktop voice")).clicked() {
                    self.settings.read_desktop_voice = true;
                    self.settings_dirty = true;
                }
            });
            if !self.settings.read_desktop_voice {
                let status = places::voice_status();
                ui.horizontal_wrapped(|ui| {
                    ui.label(
                        RichText::new(format!("Natural voice: {status}"))
                            .color(MUTED)
                            .size(13.0),
                    );
                    if status.starts_with("Not downloaded") || status.starts_with("Download failed")
                    {
                        if ui.add(theme::quiet("Download now")).clicked() {
                            places::download_voice();
                        }
                    } else if status == "Ready"
                        && ui.add(theme::quiet("Delete the voice")).clicked()
                    {
                        places::delete_voice();
                    }
                });
                if status != "Ready" {
                    ui.ctx()
                        .request_repaint_after(std::time::Duration::from_millis(500));
                }
                // A reading that failed says so on a line of its own.
                let error = places::voice_error();
                if !error.is_empty() {
                    ui.horizontal_wrapped(|ui| {
                        ui.label(RichText::new(error).color(theme::ORANGE).size(13.0));
                        if ui.add(theme::quiet("Dismiss")).clicked() {
                            places::clear_voice_error();
                        }
                    });
                }
            }
            let mut speed = self.settings.read_speed.unwrap_or(1.1) * 100.0; // PCE-12: default 110%
            let mut pauses = self.settings.read_cadence.unwrap_or(1.0) * 100.0;
            // PCE-12, PCK-02, PCW-03: range 50–150%, default 110%.
            let s1 = ui.add(
                egui::Slider::new(&mut speed, 50.0..=150.0)
                    .suffix("%")
                    .text("Speed"),
            );
            let s2 = ui.add(
                egui::Slider::new(&mut pauses, 50.0..=200.0)
                    .suffix("%")
                    .text("Pauses between phrases"),
            );
            if s1.changed() || s2.changed() {
                self.settings.read_speed = Some(speed / 100.0);
                self.settings.read_cadence = Some(pauses / 100.0);
            }
            if s1.drag_stopped()
                || s2.drag_stopped()
                || ((s1.changed() || s2.changed()) && !s1.dragged() && !s2.dragged())
            {
                self.settings_dirty = true;
            }
            let sample_id = ui.make_persistent_id("voice-sample");
            let speaking = places::speaking() == Some(sample_id);
            if ui
                .add(theme::quiet(if speaking {
                    "■ Stop"
                } else {
                    "Hear a sample"
                }))
                .clicked()
            {
                if speaking {
                    places::stop_speaking();
                } else {
                    places::set_voice_prefs(places::VoicePrefs {
                        natural: !self.settings.read_desktop_voice,
                        speed: self.settings.read_speed.unwrap_or(1.0),
                        cadence: self.settings.read_cadence.unwrap_or(1.0),
                    });
                    places::speak(
                        sample_id,
                        "Hello. This is how replies will sound, at 3:30 on a quiet afternoon. \
                             The voice stays on this PC.",
                    );
                }
            }
            if speaking {
                ui.ctx()
                    .request_repaint_after(std::time::Duration::from_millis(300));
            }
            ui.label(
                RichText::new("Spoken updates play on the phone, in its Updates tab.")
                    .color(MUTED)
                    .size(13.0),
            );
            ui.collapsing(RichText::new("More about the voice").size(13.0), |ui| {
                ui.label(
                    RichText::new(
                        "The natural voice (Ingrid) downloads once and stays on this PC. \
                         Until then the desktop voice reads.",
                    )
                    .color(MUTED)
                    .size(13.0),
                )
            });
        });
        gap(ui);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, crate::labels::TALK);
            let status = places::listen_status();
            ui.horizontal_wrapped(|ui| {
                let ready = places::listen_ready();
                let words = match &status {
                    places::ListenStatus::Downloading(s) => s.clone(),
                    places::ListenStatus::Failed(e) => e.clone(),
                    _ if ready => "Speech: ready".to_string(),
                    _ => format!(
                        "Speech: not downloaded yet (about {} MB)",
                        mimir_core::listen::DOWNLOAD_MB
                    ),
                };
                ui.label(RichText::new(words).color(MUTED).size(13.0));
                let busy = matches!(status, places::ListenStatus::Downloading(_));
                if ready {
                    if ui.add(theme::quiet("Delete the speech download")).clicked() {
                        places::delete_listen();
                    }
                } else if !busy && ui.add(theme::quiet("Download now")).clicked() {
                    places::download_listen(ui.ctx());
                }
                if busy {
                    ui.ctx()
                        .request_repaint_after(std::time::Duration::from_millis(500));
                }
            });
            ui.label(
                RichText::new(
                    "The Talk button next to the message box (Ctrl+M): say what you want, \
                         click again, and your words appear in the box for you to check before \
                         sending. Your voice is turned into text on this PC; nothing you say \
                         leaves it.",
                )
                .color(MUTED)
                .size(13.0),
            );
        });
    }

    fn settings_storage(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Projects and conversations");
            ui.label(
                RichText::new(
                    "Puts every conversation in its project, archives test chats, and sends \
                     anything with no clear home to a project called General. You see every change first.",
                )
                .color(MUTED)
                .size(13.0),
            );
            ui.add_space(4.0);
            if ui.add(theme::quiet(crate::labels::TIDY)).clicked() {
                actions.push(Action::TidyPlan);
            }
            if let Some(line) = &self.tidy_result {
                ui.label(RichText::new(line).color(MUTED).size(13.0));
            }
        });
        gap(ui);
        crate::view_models::storage(ui);
        gap(ui);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Where Mímir keeps your things");
            for p in [
                mimir_core::store::Store::default_dir(),
                places::config_dir(),
            ] {
                if ui
                    .add(
                        egui::Label::new(
                            RichText::new(p.display().to_string())
                                .monospace()
                                .size(13.0),
                        )
                        .sense(egui::Sense::click()),
                    )
                    .on_hover_text("Click to open")
                    .clicked()
                {
                    actions.push(Action::OpenPath(p));
                }
            }
        });
    }

    fn settings_notifications(&mut self, ui: &mut egui::Ui) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "On the phone");
            ui.label(
                RichText::new(
                    "Your paired phone tells you when a task needs you or is done. Choose \
                     which alerts, their sound and battery use on the phone, under \
                     Settings › Notifications.",
                )
                .color(MUTED)
                .size(13.0),
            );
        });
        gap(ui);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "On this PC");
            let mut on = self.settings.popup_done_on();
            if ui
                .checkbox(&mut on, crate::labels::POPUP_DONE_TITLE)
                .changed()
            {
                self.settings.popup_done = Some(on);
                self.settings_dirty = true;
            }
            ui.label(
                RichText::new(crate::labels::POPUP_DONE_CAPTION)
                    .color(MUTED)
                    .size(13.0),
            );
        });
        gap(ui);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "In this window");
            ui.label(
                RichText::new(
                    "Tasks that need you show at the top of the task list (Needs you, Ctrl+J), \
                     and a short notice says what happened.",
                )
                .color(MUTED)
                .size(13.0),
            );
        });
    }

    fn settings_labs(&mut self, ui: &mut egui::Ui) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            let mut on = self.settings.devlog_on();
            let mut chats = self.settings.labs_devlog_chats.unwrap_or(false);
            let mut changed = false;
            if ui.checkbox(&mut on, crate::labels::DEV_LOG_TITLE).changed() {
                self.settings.labs_devlog = Some(on);
                changed = true;
            }
            ui.label(
                RichText::new(crate::labels::DEV_LOG_CAPTION)
                    .color(MUTED)
                    .size(13.0),
            );
            ui.add_enabled_ui(on, |ui| {
                if ui
                    .checkbox(&mut chats, crate::labels::DEV_CHATS_TITLE)
                    .changed()
                {
                    self.settings.labs_devlog_chats = Some(chats);
                    changed = true;
                }
            });
            ui.label(
                RichText::new(crate::labels::DEV_CHATS_CAPTION)
                    .color(MUTED)
                    .size(13.0),
            );
            if changed {
                self.settings_dirty = true;
                mimir_core::devlog::set_switches(
                    self.settings.devlog_on(),
                    self.settings.devlog_chats(),
                );
            }
        });
        self.labs_cards(ui);
    }

    fn settings_about(&mut self, ui: &mut egui::Ui) {
        self.app_panel.show(ui);
        gap(ui);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Help");
            for (q, a) in [
                (
                    "Where are the models?",
                    "Settings › Models: your PC's models, the Model Store, the phone's models and the \
                     online models.",
                ),
                (
                    "The online limit ran out",
                    "With an online model and a backup on your PC (Settings › Models › Who answers), tasks carry on on your \
                     PC's model and offer Back online when the limit resets.",
                ),
                (
                    "Pair a phone",
                    "Settings › Phones › Pair a phone, then scan the code with the \
                     Mímir app.",
                ),
                (
                    "Where do my pictures go?",
                    "Pictures you send go to your PC (or to Anthropic when Online answers) \
                     and are kept with that chat.",
                ),
                (
                    "Space on this PC",
                    "Settings › Storage lists what Mímir keeps, with Remove.",
                ),
            ] {
                ui.label(RichText::new(q).strong());
                ui.label(RichText::new(a).color(MUTED).size(13.0));
                ui.add_space(4.0);
            }
            ui.label(RichText::new("Found a problem?").strong());
            if ui.add(theme::quiet("Report a problem…")).clicked() {
                self.open_labs();
            }
        });
    }
}
