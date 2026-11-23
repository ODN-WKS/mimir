//! Right: how this PC is doing and what is running; on a task, its state,
//! its changes and its permission.

use crate::app::{Action, App, View};
use crate::theme::{self, AMBER, JADE, MUTED, ORANGE, RED, TEAL};
use eframe::egui::{self, Align, Layout, RichText, ScrollArea};
use mimir_core::client::Station;
use mimir_core::proto::TaskState;

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// "Started 5 min ago".
pub(crate) fn started_words(secs: i64) -> String {
    let m = secs.max(0) / 60;
    match m {
        0 => "Started just now".into(),
        1..=59 => format!("Started {m} min ago"),
        60..=1439 => format!("Started {} h ago", m / 60),
        _ => format!("Started {} d ago", m / 1440),
    }
}

/// The processor's word: calm under 20%, busy over 70%.
pub(crate) fn cpu_words(pct: f32) -> String {
    let how = if pct < 20.0 {
        " · calm"
    } else if pct > 70.0 {
        " · busy"
    } else {
        ""
    };
    format!("{pct:.0}%{how}")
}

/// "31 of 62 GB".
pub(crate) fn memory_words(used: u64, total: u64) -> String {
    let g = |b: u64| (b as f64 / 1_073_741_824.0).round();
    format!("{:.0} of {:.0} GB", g(used), g(total))
}

/// Cut a question to a card's size.
fn shorten(s: &str, n: usize) -> String {
    let one: String = s.split_whitespace().collect::<Vec<_>>().join(" ");
    if one.chars().count() > n {
        format!("{}…", one.chars().take(n).collect::<String>().trim_end())
    } else {
        one
    }
}

impl App {
    pub(crate) fn right(&self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let Ok(r) = &self.station else { return };
        // A task's own panel only while a task is open; on New chat, a
        // chat and New task it is about this PC.
        let task = if self.view == View::Task {
            self.selected
                .as_ref()
                .and_then(|id| r.tasks.iter().find(|l| &l.task.id == id))
                // A chat is just a conversation: the PC panel, not a task's.
                .filter(|l| std::path::Path::new(&l.task.project) != crate::places::chats_dir())
        } else {
            None
        };
        let git = task.and_then(|l| self.git_of(&l.task.project));
        egui::Panel::right("side")
            .resizable(true)
            .default_size(270.0)
            .min_size(240.0)
            .max_size(460.0)
            .frame(
                egui::Frame::new()
                    .fill(theme::PANEL)
                    .inner_margin(egui::Margin::same(12)),
            )
            .show(ui, |ui| {
                ScrollArea::vertical().show(ui, |ui| match task {
                    None => self.pc_panel(ui, r, actions),
                    Some(l) => self.task_panel(ui, l, git.as_ref(), r, actions),
                });
            });
    }

    /// "This PC" and "Doing now".
    fn pc_panel(&self, ui: &mut egui::Ui, r: &Station, actions: &mut Vec<Action>) {
        theme::caps(ui, "This PC");
        let s = &self.stats;
        card(ui, |ui| {
            stat(ui, "Processor", cpu_words(s.cpu), s.cpu / 100.0);
            if s.mem_total > 0 {
                stat(
                    ui,
                    "Memory",
                    memory_words(s.mem_used, s.mem_total),
                    s.mem_used as f32 / s.mem_total as f32,
                );
            }
            if let Some(g) = s.gpu {
                stat(ui, "Graphics", format!("{g:.0}%"), g / 100.0);
            }
        });
        theme::caps(ui, "Doing now");
        let mut any = false;
        for l in &r.tasks {
            let t = &l.task;
            let state = l.shown_state();
            if state != TaskState::Working && state != TaskState::NeedsYou {
                continue;
            }
            any = true;
            let project = crate::places::display_name(&self.settings, t.home_project());
            let (resp, _) = if state == TaskState::NeedsYou {
                let q = l
                    .asks
                    .front()
                    .map(|a| a.request.words())
                    .unwrap_or_else(|| t.status_words().to_string());
                task_card(
                    ui,
                    true,
                    &format!("{project} is waiting for you"),
                    &shorten(&q, 70),
                )
            } else {
                let mins = ((now() - t.created_unix).max(60)) / 60;
                task_card(
                    ui,
                    false,
                    &t.title,
                    &format!("{project} · {mins} min · going well"),
                )
            };
            if resp.clicked() {
                actions.push(Action::Select(t.id.clone()));
            }
            crate::menus::open(&resp, |ui| {
                if crate::menus::item(ui, "Open", "") {
                    actions.push(Action::Select(t.id.clone()));
                }
                if l.running() {
                    crate::menus::sep(ui);
                    if crate::menus::danger(ui, "Stop", "") {
                        actions.push(Action::Stop(t.id.clone()));
                    }
                }
            });
        }
        if !any {
            ui.label(
                RichText::new("Nothing is running right now.")
                    .color(MUTED)
                    .size(13.0),
            );
        }
        ui.add_space(10.0);
        ui.label(
            RichText::new(if self.pc_model_down() {
                crate::labels::PC_DOWN_LINE_ELSEWHERE
            } else if self.stats.model_loaded() {
                "Your PC's model is ready, so the first answer is quick."
            } else {
                "Your PC's model starts when you send something, so the first answer takes longer."
            })
            .color(theme::DIM)
            .size(12.5),
        );
    }

    /// "This task", its changes and its permission.
    fn task_panel(
        &self,
        ui: &mut egui::Ui,
        l: &mimir_core::client::TaskView,
        git: Option<&mimir_core::proto::GitInfo>,
        r: &Station,
        actions: &mut Vec<Action>,
    ) {
        let t = &l.task;
        theme::caps(ui, "This task");
        card(ui, |ui| {
            let (state, color) = match l.shown_state() {
                TaskState::NeedsYou => ("Needs you", ORANGE),
                TaskState::Working => ("Working", TEAL),
                TaskState::Stopped if t.stopped_unexpectedly() => {
                    ("Stopped unexpectedly", crate::theme::AMBER)
                }
                TaskState::Stopped => ("Stopped", MUTED),
                TaskState::LiveElsewhere => (
                    crate::labels::running_state(t.elsewhere_in.as_deref()),
                    MUTED,
                ),
                _ => ("Done", MUTED),
            };
            ui.label(RichText::new(state).color(color).strong());
            ui.label(
                RichText::new(started_words(now() - t.created_unix))
                    .color(MUTED)
                    .size(13.0),
            );
        });
        {
            let free = mimir_core::models::is_free(t.model.as_deref());
            if t.context_window > 0 && !free {
                card(ui, |ui| {
                    ui.horizontal(|ui| {
                        theme::heading(ui, "Conversation memory");
                        if t.context_window > 0 {
                            let pct = t.context_used as f32 / t.context_window as f32;
                            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                ui.label(
                                    RichText::new(format!("{:.0}% used", pct * 100.0))
                                        .color(if pct > 0.8 { AMBER } else { MUTED }),
                                );
                            });
                        }
                    });
                    if t.context_window > 0 {
                        let pct = t.context_used as f32 / t.context_window as f32;
                        theme::meter(ui, pct, if pct > 0.8 { AMBER } else { JADE });
                        ui.label(
                                    RichText::new(format!("{} of {} used", short_num(t.context_used), short_num(t.context_window)))
                                        .color(MUTED)
                                        .size(13.0),
                                )
                                .on_hover_text("How much of this chat Mímir can keep in mind at once. When it is nearly full, Compact sums up the earlier part.");
                    }
                    // On an Anthropic plan (weekly limits reported) it is not a bill.
                    if t.cost_usd > 0.0
                        && r.weekly.is_none()
                        && !mimir_core::models::is_free(t.model.as_deref())
                    {
                        ui.label(
                            RichText::new(format!("Estimated cost: ${:.2}", t.cost_usd))
                                .color(MUTED)
                                .size(13.0),
                        )
                        .on_hover_text("A rough figure for this task, not a bill.");
                    }
                    // Only worth it once the memory is filling up.
                    let filling = t.context_window > 0
                        && t.context_used as f32 / t.context_window as f32 >= 0.5;
                    if t.session_id.is_some()
                        && filling
                        && !l.busy()
                        && ui
                            .add(theme::quiet("Compact (free up memory)"))
                            .on_hover_text(
                                "Mímir summarises the task so far, freeing working memory",
                            )
                            .clicked()
                    {
                        actions.push(Action::Send(t.id.clone(), "/compact".into(), vec![]));
                    }
                });
            }

            // The checklist shows only when Claude has made one.
            if let Some(pct) = t.plan_percent() {
                card(ui, |ui| {
                    ui.horizontal(|ui| {
                        theme::heading(ui, "Steps");
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                            ui.label(RichText::new(format!("{pct}% done")).color(JADE));
                        });
                    });
                    theme::meter(ui, pct as f32 / 100.0, JADE);
                    ui.add_space(4.0);
                    for p in &t.plan {
                        let (mark, c) = if p.done {
                            ("✓", JADE)
                        } else if p.doing {
                            ("▸", ORANGE)
                        } else {
                            ("○", MUTED)
                        };
                        ui.horizontal_wrapped(|ui| {
                            ui.label(RichText::new(mark).color(c));
                            let text = RichText::new(&p.text).size(13.5);
                            ui.label(if p.done { text.color(MUTED) } else { text });
                        });
                    }
                });
            }
        }
        // A session Mímir only follows has no changes list or permission of
        // its own here: it runs by the rules of the place it runs in.
        let outside = l.outside_now();
        if !outside {
            self.task_panel_changes(ui, l, git, actions);
        }
        if l.running() || outside || l.shown_state() == TaskState::NeedsYou {
            ui.add_space(6.0);
            let b = egui::Button::new(RichText::new("Stop this task").color(RED))
                .fill(egui::Color32::TRANSPARENT)
                .stroke(egui::Stroke::new(1.0, RED))
                .corner_radius(egui::CornerRadius::same(theme::RADIUS));
            if ui.add_sized([ui.available_width(), 34.0], b).clicked() {
                actions.push(Action::Stop(t.id.clone()));
            }
        }
        let _ = r;
    }

    /// "Changes so far" and "Permission" for a task Mímir runs.
    fn task_panel_changes(
        &self,
        ui: &mut egui::Ui,
        l: &mimir_core::client::TaskView,
        git: Option<&mimir_core::proto::GitInfo>,
        actions: &mut Vec<Action>,
    ) {
        let t = &l.task;
        theme::caps(ui, "Changes so far");
        card(ui, |ui| {
            let n = changes_list(ui, t, git, actions);
            if n == 0 {
                ui.label(
                    RichText::new("No files changed yet")
                        .color(MUTED)
                        .size(13.0),
                );
            }
            if let Some((at, _)) = t.checkpoints.first()
                && ui
                    .add(
                        egui::Button::new(
                            RichText::new("Undo everything from this task")
                                .color(TEAL)
                                .size(13.0),
                        )
                        .frame(false),
                    )
                    .on_hover_text(
                        "Put the files back as they were before this task. You see the list first.",
                    )
                    .clicked()
            {
                actions.push(Action::UndoPreview(t.id.clone(), *at));
            }
        });
        theme::caps(ui, "Permission");
        card(ui, |ui| {
            ui.label(RichText::new(t.mode.words()).strong());
            ui.label(RichText::new(t.mode.explain()).color(MUTED).size(13.0));
        });
    }
}

/// The right-click menu of a changed file. Undoing one file alone is not
/// something the PC can do, so it is not offered; "Undo everything from
/// this task" sits below the list.
fn file_menu(ui: &mut egui::Ui, project: &str, path: &str, actions: &mut Vec<Action>) {
    let full = std::path::Path::new(project).join(path);
    if crate::menus::item_if(ui, full.exists(), "Open file", "") {
        actions.push(Action::OpenPath(full.clone()));
    }
    if crate::menus::item_if(
        ui,
        full.parent().is_some_and(|p| p.exists()),
        "Open folder",
        "",
    ) {
        actions.push(Action::OpenPath(
            full.parent().map(|p| p.to_path_buf()).unwrap_or_default(),
        ));
    }
    if crate::menus::item(ui, "Copy path", "") {
        ui.ctx().copy_text(full.display().to_string());
    }
}

/// The changed files (clickable); how many there are.
fn changes_list(
    ui: &mut egui::Ui,
    t: &mimir_core::task::Task,
    git: Option<&mimir_core::proto::GitInfo>,
    actions: &mut Vec<Action>,
) -> usize {
    match git.and_then(|g| g.changes.as_ref()) {
        Some(changes) => {
            // Only what this task changed, never the rest of the folder's
            // waiting changes.
            let own: Vec<_> = changes
                .iter()
                .filter(|c| t.files_changed.iter().any(|f| f == &c.path))
                .collect();
            for c in own.iter().take(30) {
                ui.horizontal(|ui| {
                    let resp = ui
                        .add(
                            egui::Label::new(RichText::new(&c.path).monospace().size(13.0))
                                .truncate()
                                .sense(egui::Sense::click()),
                        )
                        .on_hover_text(format!("{} · click to see the change", c.kind));
                    if resp.clicked() {
                        actions.push(Action::ShowDiff(t.project.clone(), c.path.clone()));
                    }
                    crate::menus::open(&resp, |ui| {
                        if crate::menus::item(ui, "See the change", "") {
                            actions.push(Action::ShowDiff(t.project.clone(), c.path.clone()));
                        }
                        file_menu(ui, &t.project, &c.path, actions);
                    });
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if c.removed > 0 {
                            ui.label(
                                RichText::new(format!("-{}", c.removed))
                                    .color(RED)
                                    .size(13.0),
                            );
                        }
                        ui.label(
                            RichText::new(format!("+{}", c.added))
                                .color(JADE)
                                .size(13.0),
                        );
                    });
                });
            }
            if own.len() > 30 {
                ui.label(RichText::new(format!("and {} more", own.len() - 30)).color(MUTED));
            }
            own.len()
        }
        None => {
            // Not a git project: what Claude changed, while it exists.
            let present: Vec<&String> = t
                .files_changed
                .iter()
                .filter(|f| std::path::Path::new(&t.project).join(f).exists())
                .collect();
            for f in present.iter().take(15) {
                let resp = ui
                    .add(
                        egui::Label::new(RichText::new(f.as_str()).monospace().size(13.0))
                            .truncate()
                            .sense(egui::Sense::click()),
                    )
                    .on_hover_text("Click to open");
                if resp.clicked() {
                    actions.push(Action::OpenPath(std::path::Path::new(&t.project).join(f)));
                }
                crate::menus::open(&resp, |ui| file_menu(ui, &t.project, f, actions));
            }
            if present.len() > 15 {
                ui.label(RichText::new(format!("and {} more", present.len() - 15)).color(MUTED));
            }
            present.len()
        }
    }
}

/// A "Doing now" card: a running task, or (amber) one waiting for the owner.
fn task_card(ui: &mut egui::Ui, waiting: bool, title: &str, line: &str) -> (egui::Response, ()) {
    let (fill, stroke, head) = if waiting {
        (
            theme::NEEDS_FILL,
            egui::Stroke::new(1.0, theme::NEEDS_EDGE),
            ORANGE,
        )
    } else {
        (theme::CARD, egui::Stroke::NONE, theme::TEXT)
    };
    let resp = egui::Frame::new()
        .fill(fill)
        .stroke(stroke)
        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
        .inner_margin(egui::Margin::same(10))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.add(
                egui::Label::new(RichText::new(title).color(head).strong())
                    .truncate()
                    .selectable(false),
            );
            ui.add(egui::Label::new(RichText::new(line).color(MUTED).size(13.0)).selectable(false));
        })
        .response
        .interact(egui::Sense::click())
        .on_hover_cursor(egui::CursorIcon::PointingHand);
    ui.add_space(8.0);
    (resp, ())
}

fn card(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    theme::card().show(ui, |ui| {
        ui.set_width(ui.available_width());
        add(ui);
    });
    ui.add_space(8.0);
}

fn short_num(n: u64) -> String {
    if n >= 1_000_000 {
        format!("{:.1}M", n as f64 / 1e6).replace(".0M", "M")
    } else if n >= 1_000 {
        format!("{}k", n / 1000)
    } else {
        n.to_string()
    }
}

fn stat(ui: &mut egui::Ui, name: &str, value: String, frac: f32) {
    ui.horizontal(|ui| {
        ui.label(RichText::new(name).size(13.5));
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            ui.label(RichText::new(value).color(MUTED).size(13.0));
        });
    });
    theme::pc_meter(ui, frac, if frac > 0.85 { AMBER } else { TEAL });
    ui.add_space(4.0);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_processor_says_calm_or_busy() {
        assert_eq!(cpu_words(4.0), "4% · calm");
        assert_eq!(cpu_words(45.0), "45%");
        assert_eq!(cpu_words(88.0), "88% · busy");
    }

    #[test]
    fn memory_is_in_whole_gigabytes() {
        let gb = 1_073_741_824u64;
        assert_eq!(memory_words(31 * gb, 62 * gb), "31 of 62 GB");
    }

    #[test]
    fn a_task_says_how_long_ago_it_started() {
        assert_eq!(started_words(20), "Started just now");
        assert_eq!(started_words(5 * 60), "Started 5 min ago");
        assert_eq!(started_words(3 * 3600), "Started 3 h ago");
    }

    #[test]
    fn a_long_question_is_cut_short() {
        let q = "word ".repeat(40);
        assert!(shorten(&q, 70).chars().count() <= 71);
        assert!(shorten(&q, 70).ends_with('…'));
        assert_eq!(shorten("Short?", 70), "Short?");
    }
}
