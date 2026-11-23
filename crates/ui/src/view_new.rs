//! New task: project, what to do, mode, model, effort and attachments.

use crate::app::{Action, App};
use crate::places::display_name;
use crate::theme::{self, MUTED};
use crate::view_chat::{SendMode, composer_card, hint_line};
use crate::view_task::attachment_chips;
use eframe::egui::{self, Key, RichText};
use mimir_core::task::Mode;

impl App {
    pub(crate) fn new_task_view(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let mut projects = self.projects();
        // A chat needs no project: offered first-run too once chosen.
        let chats = crate::places::chats_dir().display().to_string();
        let chat_chosen = self.new_project.as_deref() == Some(chats.as_str());
        if projects.is_empty() && !chat_chosen {
            // First run: say what this is and what to do.
            ui.add_space((ui.available_height() * 0.25).max(16.0));
            ui.vertical_centered(|ui| {
                ui.set_max_width(560.0);
                ui.label(theme::title("Hi, I'm Mímir.", 24.0));
                ui.add_space(10.0);
                ui.label(
                    RichText::new(
                        "Ask anything. Answers come from your PC's model and stay on this PC.",
                    )
                    .color(MUTED),
                );
                ui.add_space(16.0);
                if ui
                    .add_sized([240.0, 40.0], theme::primary("Start a chat"))
                    .on_hover_text("Questions, writing and planning that are not about a folder")
                    .clicked()
                {
                    actions.push(Action::NewTaskIn(
                        crate::places::chats_dir().display().to_string(),
                    ));
                }
                ui.add_space(6.0);
                if ui
                    .add_sized([240.0, 30.0], theme::quiet("Choose a project folder…"))
                    .on_hover_text(
                        "For work on the files in a folder. Mímir asks before it changes anything.",
                    )
                    .clicked()
                {
                    actions.push(Action::PickFolder);
                }
                ui.add_space(10.0);
                ui.label(
                    RichText::new(
                        "Tasks you had in the terminal or another app are listed on \
                         the left under From elsewhere.",
                    )
                    .color(MUTED)
                    .size(13.0),
                );
            });
            return;
        }
        if !projects.contains(&chats) {
            projects.insert(0, chats);
        }
        // Top: who answers, and the saved prompts (they used to sit in
        // the box's row).
        let panel = Some(self.details_shown(ui.ctx()));
        let paid = !mimir_core::models::is_free(self.new_model.as_deref());
        {
            let project = self.new_project.clone().unwrap_or_default();
            let settings = &self.settings;
            let draft = &mut self.new_text;
            crate::view_top::mid_top(
                ui,
                &crate::view_top::MidTop {
                    crumb: None,
                    paid,
                    panel,
                    menu_name: "new-menu",
                },
                actions,
                |ui, actions| {
                    crate::extras::prompts_menu(ui, settings, &project, draft, actions);
                },
            );
        }
        // A readable column on big screens.
        ui.add_space((ui.available_height() * 0.04).min(24.0));
        crate::view_chat::in_lane(ui, |ui| {
            self.new_task_form(ui, &projects, actions);
        });
    }

    fn new_task_form(&mut self, ui: &mut egui::Ui, projects: &[String], actions: &mut Vec<Action>) {
        // A project's saved defaults apply when it is chosen.
        if self.new_project != self.defaults_for {
            self.defaults_for = self.new_project.clone();
            if let Some(d) = self
                .new_project
                .as_ref()
                .and_then(|p| self.settings.defaults.get(p))
            {
                self.new_mode = d.mode;
                self.new_model = d.model.clone();
                self.new_effort = d.effort.clone();
            }
        }
        let chat = self.new_project.as_deref()
            == Some(crate::places::chats_dir().display().to_string().as_str());
        ui.heading(if chat { "New chat" } else { "New task" });
        ui.add_space(8.0);
        if self
            .new_project
            .as_ref()
            .is_none_or(|p| !projects.contains(p))
        {
            self.new_project = self
                .settings
                .last_project
                .clone()
                .filter(|p| projects.contains(p))
                .or_else(|| projects.first().cloned());
        }
        ui.horizontal_wrapped(|ui| {
            ui.label("Project");
            let current = self.new_project.clone().unwrap_or_default();
            let hint = if self.new_project_by_hand {
                "The project this task works in"
            } else {
                "Picked from what you write (or the last one used). Change it here."
            };
            egui::ComboBox::from_id_salt("project")
                .selected_text(display_name(&self.settings, &current))
                .width(260.0)
                .show_ui(ui, |ui| {
                    for p in projects {
                        ui.selectable_value(
                            &mut self.new_project,
                            Some(p.clone()),
                            display_name(&self.settings, p),
                        )
                        .on_hover_text(p);
                    }
                })
                .response
                .on_hover_text(hint);
            if self.new_project.as_deref() != Some(current.as_str()) {
                self.new_project_by_hand = true;
            }
            if ui.link("Add a folder…").clicked() {
                actions.push(Action::PickFolder);
            }
            if self.naming_project.is_none()
                && ui
                    .link("New project…")
                    .on_hover_text("Makes a new folder beside your other projects")
                    .clicked()
            {
                self.naming_project = Some(String::new());
            }
            if let Some(name) = self.naming_project.as_mut() {
                let field = ui.add(
                    egui::TextEdit::singleline(name)
                        .hint_text("Project name")
                        .desired_width(180.0),
                );
                crate::menus::edit_menu(&field, "new-project-name");
                let enter = field.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                if (ui.button("Make it").clicked() || enter) && !name.trim().is_empty() {
                    actions.push(Action::NewProject(name.trim().to_string()));
                }
                if ui.button("Cancel").clicked() {
                    self.naming_project = None;
                }
            }
            if let Some(p) = &self.new_project
                && ui
                    .link("Continue an earlier task…")
                    .on_hover_text("Carry on a task from the terminal or another app")
                    .clicked()
            {
                actions.push(Action::OpenPast(p.clone()));
            }
        });
        ui.add_space(8.0);
        let is_chat = self.new_project.as_deref()
            == Some(crate::places::chats_dir().display().to_string().as_str());
        let mut picked = vec![];
        if !mimir_core::models::is_free(self.new_model.as_deref())
            && !self.settings.project_free()
            && self.setup.claude_ready() == Some(false)
            && crate::setup_view::claude_missing_line(ui, actions)
            && let Some(f) = mimir_core::models::default_free(&mimir_core::models::cached())
        {
            self.new_model = Some(f.id.clone());
        }
        attachment_chips(ui, &mut self.attachments);
        let out = composer_card(
            ui,
            "new-task-text",
            if is_chat {
                crate::labels::BOX_CHAT
            } else {
                "What should Mímir do?"
            },
            &mut self.new_text,
            SendMode::Send,
            if is_chat {
                crate::labels::START_CHAT
            } else {
                crate::labels::START_TASK
            },
            |ui, _actions| {
                crate::view_task::options_row(
                    ui,
                    "new",
                    false,
                    !is_chat,
                    self.new_mode,
                    &self.new_model,
                    None,
                    &self.new_effort,
                    &mut picked,
                );
            },
            actions,
        );
        let text = out.edit.clone();
        hint_line(ui, crate::labels::HINT_CHAT);
        // Until the owner picks one, the project follows what they write:
        // the one it names or clearly points to, else the last one used.
        // Chats (no project) is left alone: Mímir files the chat
        // itself when it starts, and says where.
        let chats = crate::places::chats_dir().display().to_string();
        if text.changed()
            && !self.new_project_by_hand
            && self.new_project.as_deref() != Some(chats.as_str())
        {
            let guess = self.likely_project(&self.new_text);
            if let Some(p) = guess
                .or_else(|| self.settings.last_project.clone())
                .filter(|p| projects.contains(p))
            {
                self.new_project = Some(p);
            }
        }
        // Ready to type as soon as the page opens.
        if self.new_text.is_empty() && !ui.ctx().memory(|m| m.focused().is_some()) {
            text.request_focus();
        }
        crate::view_task::auto_model_note(ui, &self.new_model);
        // Whisper mode (incognito in the code): only with a free model on this PC.
        let free = mimir_core::models::is_free(self.new_model.as_deref());
        ui.horizontal_wrapped(|ui| {
            crate::theme::whisper_eye(ui, 22.0);
            ui.add_enabled_ui(free, |ui| {
                ui.checkbox(&mut self.new_incognito, crate::labels::WHISPER_SWITCH)
                    .on_hover_text(
                        "Your PC's model runs on this PC in a sealed space: no internet, no \
                     saved history, no updates. It won't search the web or go online.",
                    );
            });
            if !free {
                ui.label(
                    RichText::new("(pick a model on your PC for Whisper mode)")
                        .color(MUTED)
                        .size(13.0),
                );
            }
        });
        if !free {
            self.new_incognito = false;
        }
        if let Some(p) = self.new_project.clone() {
            let current = crate::places::Defaults {
                mode: self.new_mode,
                model: self.new_model.clone(),
                effort: self.new_effort.clone(),
            };
            let saved = self.settings.defaults.get(&p);
            if saved == Some(&current) {
                ui.label(
                    RichText::new(format!(
                        "These are the defaults for {}.",
                        display_name(&self.settings, &p)
                    ))
                    .color(MUTED)
                    .size(13.0),
                );
            } else if ui
                .link(
                    RichText::new(format!(
                        "Make these the defaults for {}",
                        display_name(&self.settings, &p)
                    ))
                    .color(theme::JADE)
                    .size(13.0),
                )
                .on_hover_text("New tasks in this project start with this mode, model and effort")
                .clicked()
            {
                actions.push(Action::SaveDefaults(p));
            }
        }
        for a in picked {
            match a {
                Action::SetMode(_, m) => self.new_mode = m,
                Action::SetModel(_, m) => self.new_model = m,
                Action::SetEffort(_, e) => self.new_effort = e,
                other => actions.push(other),
            }
        }
        // A separate copy, for git projects.
        let can_copy = match (&self.new_project, &self.copy_ok) {
            (Some(p), Some((q, ok))) if p == q => *ok,
            (Some(p), _) => {
                let ok = mimir_core::copies::can_copy(std::path::Path::new(p));
                self.copy_ok = Some((p.clone(), ok));
                ok
            }
            _ => false,
        };
        if can_copy && let Some(p) = &self.new_project {
            let busy_here = self.station.as_ref().is_ok_and(|r| {
                r.tasks
                    .iter()
                    .any(|l| l.busy() && l.task.home_project() == p.as_str())
            });
            ui.add_space(6.0);
            ui.checkbox(&mut self.new_separate, "Work in a separate copy")
                .on_hover_text(
                    "Mímir works in its own copy of the project, \
                     so tasks running at the same time never change the same files. \
                     The copy starts from your last saved version. Bring the changes in from the task's ⋯ menu.",
                );
            if busy_here && !self.new_separate {
                ui.label(
                    RichText::new(format!(
                        "Another task is working in {} right now. A separate copy keeps them apart.",
                        display_name(&self.settings, p)
                    ))
                    .color(theme::AMBER)
                    .size(13.0),
                );
            } else if self.new_separate {
                ui.label(
                    RichText::new(
                        "Starts from your last saved version: changes you haven't saved yet in the main folder are \
                         not in the copy.",
                    )
                    .color(MUTED)
                    .size(13.0),
                );
            }
        }
        ui.add_space(8.0);
        let enter =
            text.has_focus() && ui.input(|i| i.key_pressed(Key::Enter) && !i.modifiers.shift);
        let ready = !self.new_text.trim().is_empty() && self.new_project.is_some();
        if out.send && self.new_text.trim().is_empty() {
            text.request_focus();
        }
        if (out.send || enter)
            && ready
            && let Some(p) = &self.new_project
        {
            if self.new_incognito
                && let Some(m) = self.new_model.clone()
            {
                actions.push(Action::StartIncognito {
                    project: p.clone(),
                    text: self.new_text.clone(),
                    model: m,
                    mode: self.new_mode,
                });
            } else {
                actions.push(Action::Start {
                    project: p.clone(),
                    text: self.new_text.clone(),
                    files: self.attachments.clone(),
                    mode: self.new_mode,
                    model: self.new_model.clone(),
                    effort: self.new_effort.clone(),
                    separate: self.new_separate && can_copy,
                });
                // Each task chooses again.
                self.new_separate = false;
            }
        }
        ui.add_space(12.0);
        let note = match self.new_mode {
            _ if is_chat => "A general chat, not about a project folder.",
            Mode::Ask => {
                "Mímir works in this folder on this PC. It asks before changing files or running commands."
            }
            m => m.explain(),
        };
        ui.label(RichText::new(note).color(MUTED).size(13.0));
    }

    /// The project a request names or clearly points to, if any.
    pub(crate) fn likely_project(&self, text: &str) -> Option<String> {
        use mimir_core::route::{Placement, place, project_words};
        let r = self.station.as_ref().ok()?;
        let tasks: Vec<&mimir_core::task::Task> = r.tasks.iter().map(|l| &l.task).collect();
        match place(text, &project_words(&self.settings, &tasks)) {
            Placement::Sure(p) | Placement::Unsure { project: p, .. } => Some(p),
            Placement::None => None,
        }
    }
}
