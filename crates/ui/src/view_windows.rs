//! Pop-up windows: a change, past sessions, typed folder, shortcuts,
//! remove confirmation.

use crate::app::{Action, App};
use crate::organize::{Organize, same};
use crate::theme::{self, MUTED, RED};
use crate::view_task::diff_lines;
use eframe::egui::{self, RichText, ScrollArea};

impl App {
    pub(crate) fn windows(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        // Esc closes the dialog on top (menus close themselves first).
        if ctx.input(|i| i.key_pressed(egui::Key::Escape)) && !egui::Popup::is_any_open(ctx) {
            if self.listen_ask {
                self.listen_ask = false;
            } else if self.prompt_save.is_some() {
                self.prompt_save = None;
            } else if self.prompts_open.is_some() {
                self.prompts_open = None;
            } else if self.naming.is_some() {
                self.naming = None;
            } else if self.confirm_remove_project.is_some() {
                self.confirm_remove_project = None;
            } else if self.moving.is_some() {
                self.moving = None;
            } else if self.merging.is_some() {
                self.merging = None;
            } else if self.adding.is_some() {
                self.adding = None;
            } else if self.tidying.is_some() {
                self.tidying = None;
            } else if self.undo_confirm.is_some() {
                self.undo_confirm = None;
            } else if self.confirm_remove_copy.is_some() {
                self.confirm_remove_copy = None;
            } else if self.confirm_remove.is_some() {
                self.confirm_remove = None;
            } else if self.typed_path.is_some() {
                self.typed_path = None;
            } else if self.diff.is_some() {
                self.diff = None;
            } else if self.past.is_some() {
                self.past = None;
            } else if self.shortcuts_open {
                self.shortcuts_open = false;
            }
        }
        if let Some((title, lines)) = &self.diff {
            let mut open = true;
            let screen = ctx.content_rect().size();
            let heading = if title == "the handover brief" {
                "Handover brief".to_string()
            } else {
                format!("Changes to {title}")
            };
            egui::Window::new(heading)
                .open(&mut open)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .collapsible(false)
                .default_size([screen.x * 0.8, screen.y * 0.8])
                .resizable(true)
                .show(ctx, |ui| {
                    if lines.is_empty() {
                        ui.label(RichText::new("No changes to show.").color(MUTED));
                    }
                    diff_lines(ui, lines, ui.available_height(), "window");
                });
            if !open {
                self.diff = None;
            }
        }

        if let Some((project, sessions)) = &self.past {
            let mut open = true;
            egui::Window::new("Continue an earlier session")
                .open(&mut open)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .collapsible(false)
                .default_size([620.0, 460.0])
                .show(ctx, |ui| {
                    ui.label(
                        RichText::new(
                            "Earlier sessions in this folder, newest first. Open one to carry on.",
                        )
                        .color(MUTED)
                        .size(13.0),
                    );
                    ui.add_space(6.0);
                    if sessions.is_empty() {
                        ui.label("No earlier sessions in this folder.");
                    }
                    ScrollArea::vertical().show(ui, |ui| {
                        for s in sessions {
                            theme::card().show(ui, |ui| {
                                ui.set_width(ui.available_width());
                                ui.add(
                                    egui::Label::new(RichText::new(&s.first).strong()).truncate(),
                                );
                                ui.horizontal(|ui| {
                                    ui.label(
                                        RichText::new(format!(
                                            "{} message{} · {}",
                                            s.messages,
                                            if s.messages == 1 { "" } else { "s" },
                                            ago(s.modified_unix)
                                        ))
                                        .color(MUTED)
                                        .size(13.0),
                                    );
                                    ui.with_layout(
                                        egui::Layout::right_to_left(egui::Align::Center),
                                        |ui| {
                                            if ui.add(theme::primary("Open")).clicked() {
                                                actions.push(Action::ImportPast(
                                                    project.clone(),
                                                    s.clone(),
                                                ));
                                            }
                                        },
                                    );
                                });
                            });
                            ui.add_space(4.0);
                        }
                    });
                });
            if !open {
                self.past = None;
            }
        }

        if let Some(path) = self.typed_path.as_mut() {
            let mut open = true;
            egui::Window::new("Open a project folder")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .open(&mut open)
                .show(ctx, |ui| {
                    ui.label("Folder (you can start with ~):");
                    let edit = ui.add(
                        egui::TextEdit::singleline(path)
                            .desired_width(520.0)
                            .hint_text("~/Projects/my-app"),
                    );
                    if !edit.has_focus() && path.is_empty() {
                        edit.request_focus();
                    }
                    let full = crate::places::expand_home(path.trim());
                    let ok = full.is_dir();
                    if !path.trim().is_empty() && !ok {
                        ui.label(RichText::new("No folder found there.").color(RED));
                    }
                    let enter = edit.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                    if ui.add_enabled(ok, theme::primary("Open")).clicked() || (enter && ok) {
                        actions.push(Action::AddTypedFolder(full.display().to_string()));
                    }
                });
            if !open {
                self.typed_path = None;
            }
        }

        if let Some((id, n, changes, redo)) = self.undo_confirm.clone() {
            let mut open = true;
            egui::Window::new(if redo {
                "Put back the undone changes?"
            } else {
                "Undo changes from here?"
            })
            .collapsible(false)
            .resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .open(&mut open)
            .show(ctx, |ui| {
                if changes.is_empty() {
                    ui.label("No files changed since that message, so there is nothing to undo.");
                } else {
                    let when = if redo {
                        "before you undid them"
                    } else {
                        "just before your message"
                    };
                    ui.label(if changes.len() == 1 {
                        format!("This file goes back to how it was {when}:")
                    } else {
                        format!(
                            "These {} files go back to how they were {when}:",
                            changes.len()
                        )
                    });
                    ScrollArea::vertical().max_height(260.0).show(ui, |ui| {
                        for c in &changes {
                            ui.horizontal(|ui| {
                                let (word, color) = if c.action == "remove" {
                                    ("remove", RED)
                                } else {
                                    ("put back", theme::JADE)
                                };
                                ui.label(RichText::new(word).color(color).size(13.0));
                                ui.label(RichText::new(&c.path).monospace().size(13.0));
                            });
                        }
                    });
                    ui.label(
                        RichText::new(
                            "The conversation stays; Mímir is told with your next \
                                 message. You can put the changes back afterwards.",
                        )
                        .color(MUTED)
                        .size(13.0),
                    );
                }
                ui.horizontal(|ui| {
                    if ui.add(theme::quiet("Cancel")).clicked() {
                        self.undo_confirm = None;
                    }
                    let label = if redo {
                        "Put these back"
                    } else {
                        "Undo these changes"
                    };
                    let enter = ui.input(|i| i.key_pressed(egui::Key::Enter));
                    if !changes.is_empty() && (ui.add(theme::primary(label)).clicked() || enter) {
                        actions.push(if redo {
                            Action::Redo(id.clone())
                        } else {
                            Action::Undo(id.clone(), n)
                        });
                    }
                });
            });
            if !open {
                self.undo_confirm = None;
            }
        }

        if let Some(id) = self.confirm_remove_copy.clone() {
            let mut open = true;
            egui::Window::new("Remove the separate copy?")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .open(&mut open)
                .show(ctx, |ui| {
                    ui.label(
                        "The copy's folder and its branch are deleted. Anything not brought into \
                         the main folder is lost. The task carries on in the main folder.",
                    );
                    ui.horizontal(|ui| {
                        if ui.add(theme::quiet("Cancel")).clicked() {
                            self.confirm_remove_copy = None;
                        }
                        let red = egui::Button::new(
                            RichText::new("Remove the copy")
                                .color(theme::GROUND)
                                .strong(),
                        )
                        .fill(RED);
                        if ui.add(red).clicked() {
                            actions.push(Action::RemoveCopy(id.clone()));
                        }
                    });
                });
            if !open {
                self.confirm_remove_copy = None;
            }
        }

        if let Some(id) = self.confirm_remove.clone() {
            let mut open = true;
            egui::Window::new("Delete this task?")
                .collapsible(false)
                .resizable(false)
                .open(&mut open)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .show(ctx, |ui| {
                    ui.label(
                        "It disappears from Mímir. Changed files stay as they are. Its conversation stays in the tool's own history (the project's ⋯ \
                         menu › Continue an earlier session).",
                    );
                    ui.horizontal(|ui| {
                        if ui.add(theme::quiet("Cancel")).clicked() {
                            self.confirm_remove = None;
                        }
                        let red = egui::Button::new(
                            RichText::new("Delete").color(theme::GROUND).strong(),
                        )
                        .fill(RED);
                        if ui.add(red).clicked() {
                            actions.push(Action::Remove(id.clone()));
                        }
                    });
                });
            if !open {
                self.confirm_remove = None;
            }
        }

        self.organize_windows(ctx, actions);
        self.prompt_windows(ctx);

        if ctx.input(|i| i.modifiers.ctrl && i.key_pressed(egui::Key::Slash)) {
            self.shortcuts_open = !self.shortcuts_open;
        }
        if self.shortcuts_open {
            let mut open = true;
            egui::Window::new("Keyboard shortcuts")
                .open(&mut open)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .collapsible(false)
                .resizable(false)
                .show(ctx, |ui| {
                    egui::Grid::new("keys")
                        .num_columns(2)
                        .spacing([24.0, 6.0])
                        .show(ui, |ui| {
                            for (k, what) in [
                                ("Ctrl+N", "New chat"),
                                ("Ctrl+K", "Search chats and tasks"),
                                ("Enter", "Send (Shift+Enter for a new line)"),
                                (
                                    "Ctrl+Enter",
                                    "Start the new task; while Mímir works: send when it is done",
                                ),
                                ("Ctrl+F", "Find in this conversation"),
                                ("Ctrl+M", "Talk / voice typing (again to stop; Esc cancels)"),
                                ("Ctrl+.", "Pause"),
                                ("Esc", "Close what is open. Nothing else."),
                                ("Ctrl+Tab", "Next task (with Shift: previous)"),
                                ("Ctrl+Shift+M", "Change the mode"),
                                ("/", "Commands and skills"),
                                ("Ctrl+V", "Paste a picture into the message"),
                                ("Ctrl+L", "Go to the message box"),
                                ("Ctrl+J", "Go to the task that needs you"),
                                (
                                    "Ctrl+Enter / Ctrl+D",
                                    "Allow / Don't allow, while Mímir asks",
                                ),
                                ("Ctrl+B", "Show or hide the side panel"),
                                ("Ctrl+plus / minus", "Text size (Ctrl+0: normal)"),
                                ("Ctrl+/", "Show or hide this list"),
                            ] {
                                ui.label(RichText::new(k).monospace().strong());
                                ui.label(what);
                                ui.end_row();
                            }
                        });
                });
            if !open {
                self.shortcuts_open = false;
            }
        }
    }
}

pub(crate) fn ago(unix: i64) -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let s = (now - unix).max(0);
    match s {
        0..=119 => "just now".into(),
        120..=7199 => format!("{} min ago", s / 60),
        7200..=172_799 => format!("{} h ago", s / 3600),
        _ => format!("{} days ago", s / 86400),
    }
}

impl App {
    /// "Rename the project" and "Remove this project?".
    fn organize_windows(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        if let Some((p, text)) = self.naming.as_mut() {
            let mut open = true;
            let mut cancel = false;
            egui::Window::new("Rename the project")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .open(&mut open)
                .show(ctx, |ui| {
                    ui.label(
                        RichText::new(format!(
                            "Only in Mímir: the folder keeps its name ({}). \
                             Leave it empty to use the folder's name.",
                            crate::places::project_name(p)
                        ))
                        .color(MUTED)
                        .size(13.0),
                    );
                    let edit = ui.add(
                        egui::TextEdit::singleline(text)
                            .desired_width(320.0)
                            .hint_text("Project name"),
                    );
                    edit.request_focus();
                    let enter = ui.input(|i| i.key_pressed(egui::Key::Enter));
                    ui.horizontal(|ui| {
                        if ui.add(theme::quiet("Cancel")).clicked() {
                            cancel = true;
                        }
                        if ui.add(theme::primary("Rename")).clicked() || enter {
                            actions
                                .push(Action::Organize(Organize::Named(p.clone(), text.clone())));
                        }
                    });
                });
            if !open || cancel {
                self.naming = None;
            }
        }

        self.move_window(ctx, actions);
        self.merge_window(ctx, actions);
        self.add_window(ctx, actions);
        self.tidy_window(ctx, actions);

        if let Some(p) = self.confirm_remove_project.clone() {
            let mut open = true;
            let name = crate::places::display_name(&self.settings, &p);
            let (open_tasks, all_tasks) = self.station.as_ref().map_or((0, 0), |r| {
                let mine = r.tasks.iter().filter(|l| same(l.task.home_project(), &p));
                (
                    mine.clone().filter(|l| !l.task.archived).count(),
                    mine.count(),
                )
            });
            let archived = all_tasks - open_tasks;
            let s = |n: usize| if n == 1 { "" } else { "s" };
            egui::Window::new("Remove this project?")
                .collapsible(false)
                .resizable(false)
                .open(&mut open)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .show(ctx, |ui| {
                    ui.label(format!(
                        "Remove {name} from Mímir? Its files stay as they are."
                    ));
                    if all_tasks > 0 {
                        ui.label(
                            RichText::new(
                                "Archived tasks stay under Archived and can be opened again. \
                                 Deleted tasks are gone from Mímir.",
                            )
                            .color(MUTED)
                            .size(13.0),
                        );
                    }
                    ui.horizontal(|ui| {
                        if ui.add(theme::quiet("Cancel")).clicked() {
                            self.confirm_remove_project = None;
                        }
                        let remove =
                            |delete| Action::Organize(Organize::RemoveProject(p.clone(), delete));
                        if all_tasks == 0 {
                            if ui.add(theme::primary("Remove")).clicked() {
                                actions.push(remove(false));
                            }
                            return;
                        }
                        let keep = if open_tasks > 0 {
                            format!("Archive its {open_tasks} open task{}", s(open_tasks))
                        } else {
                            "Remove, keep the archived tasks".to_string()
                        };
                        if ui.add(theme::quiet(&keep)).clicked() {
                            actions.push(remove(false));
                        }
                        let label = if archived > 0 {
                            format!(
                                "Delete all {all_tasks} task{} ({archived} already archived)",
                                s(all_tasks)
                            )
                        } else {
                            format!("Delete its {all_tasks} task{}", s(all_tasks))
                        };
                        let red =
                            egui::Button::new(RichText::new(label).color(theme::GROUND).strong())
                                .fill(RED);
                        if ui.add(red).clicked() {
                            actions.push(remove(true));
                        }
                    });
                });
            if !open {
                self.confirm_remove_project = None;
            }
        }
    }

    /// "Move to…": the projects to move a task to, one click each. While
    /// Claude works on it, only why it waits.
    fn move_window(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let Some(id) = self.moving.clone() else {
            return;
        };
        let Some((title, home, busy)) = self.station.as_ref().ok().and_then(|r| {
            r.tasks.iter().find(|l| l.task.id == id).map(|l| {
                (
                    l.task.title.clone(),
                    l.task.home_project().to_string(),
                    l.busy(),
                )
            })
        }) else {
            self.moving = None;
            return;
        };
        let chats = crate::places::chats_dir().display().to_string();
        let targets: Vec<String> = self
            .projects()
            .into_iter()
            .filter(|p| !same(p, &home) && !same(p, &chats))
            .collect();
        let mut open = true;
        let mut cancel = false;
        egui::Window::new(crate::labels::MOVE_TO_TITLE)
            .collapsible(false)
            .resizable(false)
            .open(&mut open)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .show(ctx, |ui| {
                ui.set_max_width(420.0);
                ui.label(format!(
                    "\"{title}\" is in {}.",
                    crate::places::display_name(&self.settings, &home)
                ));
                ui.add_space(6.0);
                if busy {
                    ui.label(RichText::new(crate::labels::MOVE_WHEN_STOPPED).color(MUTED));
                } else if targets.is_empty() {
                    ui.label(
                        RichText::new("There is no other project yet. Add one first.").color(MUTED),
                    );
                } else {
                    ui.label(
                        RichText::new(
                            "It carries on there with its whole conversation. Changed files stay where they are.",
                        )
                        .color(MUTED)
                        .size(13.0),
                    );
                    ui.add_space(4.0);
                    let room = (ctx.content_rect().height() * 0.6).max(120.0);
                    ScrollArea::vertical().max_height(room).show(ui, |ui| {
                        for p in &targets {
                            let name = crate::places::display_name(&self.settings, p);
                            let b = egui::Button::new(&name)
                                .min_size(egui::vec2(ui.available_width(), 30.0));
                            if ui.add(b).on_hover_text(p).clicked() {
                                actions.push(Action::Organize(Organize::MoveTo(
                                    id.clone(),
                                    p.clone(),
                                )));
                            }
                        }
                    });
                }
                ui.add_space(6.0);
                if ui.add(theme::quiet("Cancel")).clicked() {
                    cancel = true;
                }
            });
        if !open || cancel {
            self.moving = None;
        }
    }

    /// "Merge into…": the projects a project's tasks can go into.
    fn merge_window(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let Some(from) = self.merging.clone() else {
            return;
        };
        let chats = crate::places::chats_dir().display().to_string();
        let targets: Vec<String> = self
            .projects()
            .into_iter()
            .filter(|p| !same(p, &from) && !same(p, &chats))
            .collect();
        let (open_tasks, all_tasks) = self.station.as_ref().map_or((0, 0), |r| {
            let mine = r
                .tasks
                .iter()
                .filter(|l| same(l.task.home_project(), &from));
            (
                mine.clone().filter(|l| !l.task.archived).count(),
                mine.count(),
            )
        });
        let name = crate::places::display_name(&self.settings, &from);
        let mut open = true;
        let mut cancel = false;
        egui::Window::new(crate::labels::MERGE_TITLE)
            .collapsible(false)
            .resizable(false)
            .open(&mut open)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .show(ctx, |ui| {
                ui.set_max_width(440.0);
                let s = |n: usize| if n == 1 { "" } else { "s" };
                ui.label(format!(
                    "{name} has {open_tasks} open task{} ({all_tasks} with archived ones). They \
                     all carry on in the project you pick, conversations and all, and {name} \
                     leaves the list. Its folder and files stay as they are.",
                    s(open_tasks)
                ));
                ui.add_space(6.0);
                if targets.is_empty() {
                    ui.label(
                        RichText::new("There is no other project yet. Add one first.").color(MUTED),
                    );
                }
                let room = (ctx.content_rect().height() * 0.6).max(120.0);
                ScrollArea::vertical().max_height(room).show(ui, |ui| {
                    for p in &targets {
                        let n = crate::places::display_name(&self.settings, p);
                        let b =
                            egui::Button::new(&n).min_size(egui::vec2(ui.available_width(), 30.0));
                        if ui.add(b).on_hover_text(p).clicked() {
                            actions
                                .push(Action::Organize(Organize::Merge(from.clone(), p.clone())));
                        }
                    }
                });
                ui.add_space(6.0);
                if ui.add(theme::quiet("Cancel")).clicked() {
                    cancel = true;
                }
            });
        if !open || cancel {
            self.merging = None;
        }
    }

    /// "Add projects": git projects beside the listed ones, ticked to add.
    fn add_window(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let Some(list) = self.adding.as_mut() else {
            return;
        };
        let mut open = true;
        let mut cancel = false;
        egui::Window::new(crate::labels::ADD_PROJECTS_TITLE)
            .collapsible(false)
            .resizable(false)
            .open(&mut open)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .show(ctx, |ui| {
                ui.set_max_width(440.0);
                ui.label(
                    "These folders beside your projects are git projects that aren't on \
                     the list yet.",
                );
                ui.add_space(6.0);
                let room = (ctx.content_rect().height() * 0.6).max(120.0);
                ScrollArea::vertical().max_height(room).show(ui, |ui| {
                    for (p, on) in list.iter_mut() {
                        ui.checkbox(on, crate::places::project_name(p))
                            .on_hover_text(p.as_str());
                    }
                });
                ui.add_space(6.0);
                let picked: Vec<String> = list
                    .iter()
                    .filter(|(_, on)| *on)
                    .map(|(p, _)| p.clone())
                    .collect();
                ui.horizontal(|ui| {
                    if ui.add(theme::quiet("Cancel")).clicked() {
                        cancel = true;
                    }
                    let words = match picked.len() {
                        1 => "Add 1 project".to_string(),
                        n => format!("Add {n} projects"),
                    };
                    if ui
                        .add_enabled(!picked.is_empty(), theme::primary(&words))
                        .clicked()
                    {
                        actions.push(Action::Organize(Organize::Add(picked.clone())));
                    }
                });
            });
        if !open || cancel {
            self.adding = None;
        }
    }

    /// "Tidy projects": every change it proposes, each one ticked to do,
    /// with where a conversation goes and whether it's archived to change.
    fn tidy_window(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        use mimir_core::proto::TidyKind;
        let Some(rows) = self.tidying.as_mut() else {
            return;
        };
        let settings = &self.settings;
        let name = |p: &str| crate::places::display_name(settings, p);
        let home = std::env::var("HOME").unwrap_or_default();
        let short = |p: &str| match p.strip_prefix(home.as_str()) {
            Some(rest) if !home.is_empty() => format!("~{rest}"),
            _ => p.to_string(),
        };
        // Where a conversation can go: listed projects, ones being added,
        // and General.
        let mut targets: Vec<String> = settings.projects.clone();
        for r in rows.iter() {
            for t in [&r.to].into_iter().flatten() {
                if !targets.iter().any(|x| same(x, t)) {
                    targets.push(t.clone());
                }
            }
        }
        let chats = crate::places::chats_dir().display().to_string();
        targets.retain(|t| !same(t, &chats) && Some(t.as_str()) != Some(home.as_str()));
        let mut open = true;
        let mut cancel = false;
        let screen = ctx.content_rect().size();
        egui::Window::new(crate::labels::TIDY_TITLE)
            .collapsible(false)
            .resizable(true)
            .open(&mut open)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .default_size([(screen.x * 0.8).min(900.0), screen.y * 0.75])
            .show(ctx, |ui| {
                if rows.is_empty() {
                    ui.label("Everything is where it belongs. Nothing to change.");
                    if ui.add(theme::quiet("Close")).clicked() {
                        cancel = true;
                    }
                    return;
                }
                ui.label(
                    RichText::new(
                        "Nothing changes until you say so. Untick what should stay as it is, \
                         or pick another place. Files stay in their folders.",
                    )
                    .color(MUTED)
                    .size(13.0),
                );
                ui.add_space(4.0);
                let room = (ui.available_height() - 48.0).max(120.0);
                ScrollArea::vertical().max_height(room).show(ui, |ui| {
                    for (i, r) in rows.iter_mut().enumerate() {
                        ui.horizontal_wrapped(|ui| {
                            let now = match r.kind {
                                TidyKind::Conversation => {
                                    format!("\u{201c}{}\u{201d} in {}", r.title, short(&r.folder))
                                }
                                _ => short(&r.folder),
                            };
                            ui.checkbox(&mut r.on, now);
                            ui.label(RichText::new("→").color(MUTED));
                            match r.kind {
                                TidyKind::Add => {
                                    ui.label(format!("added as {}", name(&r.folder)));
                                }
                                TidyKind::Merge => {
                                    let into = r.to.as_deref().map(name).unwrap_or_default();
                                    ui.label(format!("{into} (it leaves the list)"));
                                }
                                TidyKind::Unlist => {
                                    ui.label("leaves the list");
                                }
                                TidyKind::Conversation => {
                                    let shown =
                                        r.to.as_deref()
                                            .map(name)
                                            .unwrap_or_else(|| "stays".to_string());
                                    egui::ComboBox::from_id_salt(("tidy-to", i))
                                        .selected_text(shown)
                                        .show_ui(ui, |ui| {
                                            ui.selectable_value(&mut r.to, None, "stays");
                                            for t in &targets {
                                                ui.selectable_value(
                                                    &mut r.to,
                                                    Some(t.clone()),
                                                    name(t),
                                                );
                                            }
                                        });
                                    ui.checkbox(&mut r.archive, "archived");
                                    if r.waits {
                                        ui.label(
                                            RichText::new("when Mímir stops")
                                                .color(theme::ORANGE)
                                                .size(13.0),
                                        );
                                    }
                                }
                            }
                        })
                        .response
                        .on_hover_text(&r.why);
                        ui.label(RichText::new(&r.why).color(MUTED).size(12.5));
                        ui.add_space(4.0);
                    }
                });
                ui.add_space(6.0);
                let n = rows.iter().filter(|r| r.on).count();
                ui.horizontal(|ui| {
                    if ui.add(theme::quiet("Cancel")).clicked() {
                        cancel = true;
                    }
                    if ui
                        .add_enabled(n > 0, theme::primary(&crate::labels::tidy_go(n)))
                        .clicked()
                    {
                        actions.push(Action::Tidy(rows.clone()));
                    }
                });
            });
        if !open || cancel {
            self.tidying = None;
        }
    }
}
