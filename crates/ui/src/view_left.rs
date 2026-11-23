//! Left: New task, search, tasks grouped by project, archived tasks, and
//! Open a project folder.

use crate::app::{Action, App, View};
use crate::organize::{Organize, ProjectDrag, TaskDrag, project_tasks, same};
use crate::places::{Settings, display_name, project_name};
use crate::theme::{self, CARD, TEXT};
use eframe::egui::{self, RichText, ScrollArea, TextEdit};
use mimir_core::client::{Station, TaskView};
use mimir_core::sessions::OtherSession;
use std::path::PathBuf;

/// The left sidebar's panel.
fn side_panel() -> egui::Panel {
    egui::Panel::left("tasks")
        .resizable(true)
        .default_size(270.0)
        .min_size(200.0)
        .max_size(420.0)
        .frame(
            egui::Frame::new()
                .fill(MENU_BG)
                .inner_margin(egui::Margin::same(12)),
        )
}

impl App {
    /// How many phones are paired, read again only when the list changed.
    fn paired_phones(&mut self) -> usize {
        let Ok(st) = &mut self.station else { return 0 };
        let p = &mut self.phone;
        p.refresh(st);
        p.count()
    }

    pub(crate) fn left(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        // The Chats folder holds the PC chats: they live under CHATS, so it
        // is never listed (or counted) as a project.
        let projects: Vec<String> = self
            .projects()
            .into_iter()
            .filter(|p| !same(p, &crate::places::chats_dir().display().to_string()))
            .collect();
        self.refresh_others(ui.ctx());
        let phones = self.paired_phones();
        let (others, repos) = {
            let o = self.others.lock().unwrap();
            (o.list.clone(), o.repos.clone())
        };
        let update_waiting = matches!(
            self.app_panel.update_state(),
            crate::app_view::UpdateState::Available(_)
                | crate::app_view::UpdateState::Opened(_)
                | crate::app_view::UpdateState::RestartNeeded(_)
        );
        let foot = crate::view_top::Foot {
            r: self.station.as_ref().ok(),
            panel: &self.app_panel,
            view: self.view,
        };
        let Ok(r) = &self.station else {
            // Settings stays in reach even with nothing else to show.
            side_panel().show(ui, |ui| {
                foot.place(ui, false, actions);
            });
            return;
        };
        let remote_on: std::collections::HashSet<String> = std::collections::HashSet::new();
        // The search index of the chats, kept while they don't change.
        if !self.search.trim().is_empty() && self.chat_index.0 != Some(r.chats_rev) {
            self.chat_index = (
                Some(r.chats_rev),
                r.chats
                    .values()
                    .map(|c| {
                        let mut all = crate::view_chat::chat_title(c).to_lowercase();
                        for t in &c.turns {
                            all.push('\n');
                            all.push_str(&t.text.to_lowercase());
                        }
                        (c.id.clone(), all)
                    })
                    .collect(),
            );
        }
        let chat_index = &self.chat_index.1;
        let search = &mut self.search;
        let settings = &self.settings;
        let selected = self.selected.clone();
        let chat_open = self.chat_open.clone();
        let view = self.view;
        let (_, waiting) = r.counts();
        side_panel().show(ui, |ui| {
            // The foot first, so it keeps its place under everything.
            let rest = foot.place(ui, false, actions);
            // "Needs you" and "Updates" sit just above the foot, only when
            // there is something: a row that comes or goes here moves
            // nothing above it.
            let rest = if waiting > 0 || update_waiting {
                let mut nav = ui.new_child(
                    egui::UiBuilder::new()
                        .id_salt("side-nav")
                        .max_rect(rest)
                        .layout(egui::Layout::bottom_up(egui::Align::Min)),
                );
                nav.spacing_mut().item_spacing.y = ROW_GAP;
                if update_waiting
                    && nav_row(
                        &mut nav,
                        "Updates",
                        Right::Badge(1, BLUE_BADGE, egui::Color32::WHITE),
                        false,
                    )
                    .on_hover_text("A new version of Mímir is ready")
                    .clicked()
                {
                    actions.push(Action::OpenUpdates);
                }
                if waiting > 0
                    && nav_row(
                        &mut nav,
                        "Needs you",
                        Right::Badge(waiting, AMBER_BADGE, AMBER_ON),
                        false,
                    )
                    .on_hover_text("Go to what is waiting for you (Ctrl+J)")
                    .clicked()
                {
                    actions.push(Action::GoToWaiting);
                }
                let top = (nav.min_rect().top() - 4.0).max(rest.top());
                egui::Rect::from_min_max(rest.min, egui::pos2(rest.right(), top))
            } else {
                rest
            };
            ui.scope_builder(egui::UiBuilder::new().max_rect(rest), |ui| {
                // Other Claude sessions' requests, answered here or on the phone.
                crate::view_top::outside_asks(ui, r, actions);
                // In a small window the box and buttons scroll with the list,
                // so the list keeps some room.
                let short = rest.height() < 330.0;
                if !short {
                    top_block(ui, search, actions);
                }
                ScrollArea::vertical().show(ui, |ui| {
                    if short {
                        top_block(ui, search, actions);
                    }
                    if egui::DragAndDrop::has_any_payload(ui.ctx()) {
                        ui.ctx().set_cursor_icon(egui::CursorIcon::Grabbing);
                    }
                    ui.spacing_mut().item_spacing.y = ROW_GAP;
                    let paired = match phones {
                        0 => "not paired".to_string(),
                        1 => "1 phone".to_string(),
                        n => format!("{n} phones"),
                    };
                    if nav_row(ui, "Phones", Right::Dim(paired), false)
                        .on_hover_text("Pair a phone, or see which are paired")
                        .clicked()
                    {
                        actions.push(Action::OpenPhones);
                    }
                    if nav_row(
                        ui,
                        "Whisper",
                        Right::Dim("a chat that's never kept".into()),
                        false,
                    )
                    .on_hover_text(crate::labels::WHISPER_SWITCH)
                    .clicked()
                    {
                        actions.push(Action::NewWhisper);
                    }
                    let needle = search.trim().to_lowercase();
                    let matches = |l: &&TaskView| matches(&needle, l);
                    let side = Side {
                        r,
                        settings,
                        projects: &projects,
                        needle: &needle,
                        selected: selected.as_deref(),
                        view,
                        remote_on: &remote_on,
                        chat: chat_open.as_deref(),
                        chat_index,
                    };
                    side.chats(ui, actions);
                    side.projects_section(ui, actions);
                    let archived: Vec<&TaskView> = r
                        .tasks
                        .iter()
                        .rev()
                        .filter(|l| l.task.archived)
                        .filter(matches)
                        .collect();
                    let elsewhere: Vec<&OtherSession> = others
                        .iter()
                        .filter(|o| !settings.hidden_sessions.contains(&o.session.id))
                        .filter(|o| {
                            !r.tasks.iter().any(|l| {
                                l.task.session_id.as_deref() == Some(o.session.id.as_str())
                            })
                        })
                        .filter(|o| {
                            needle.is_empty()
                                || o.session.first.to_lowercase().contains(&needle)
                                || o.title
                                    .as_ref()
                                    .is_some_and(|t| t.to_lowercase().contains(&needle))
                        })
                        .take(15)
                        .collect();
                    // One quiet line at the end: archived tasks, and work from
                    // the terminal or the Claude app. Each opens its list.
                    let arch_id = egui::Id::unique("side-archived-open");
                    let else_id = egui::Id::unique("side-elsewhere-open");
                    let mut arch_open = ui.data(|d| d.get_temp::<bool>(arch_id).unwrap_or(false));
                    // Shown at once while there are no tasks of your own.
                    let mut else_open = ui
                        .data(|d| d.get_temp::<bool>(else_id))
                        .unwrap_or(r.tasks.is_empty());
                    // Nothing from elsewhere: no section, no toggle.
                    if elsewhere.is_empty() {
                        else_open = false;
                    }
                    ui.add_space(10.0);
                    ui.horizontal_wrapped(|ui| {
                        ui.spacing_mut().item_spacing.x = 6.0;
                        let quiet = |ui: &mut egui::Ui, words: String, open: &mut bool| {
                            let resp = ui
                                .add(
                                    egui::Label::new(
                                        RichText::new(&words)
                                            .color(if *open { TEXT } else { MUTED })
                                            .size(12.5),
                                    )
                                    .selectable(false)
                                    .sense(egui::Sense::click()),
                                )
                                .on_hover_cursor(egui::CursorIcon::PointingHand);
                            resp.widget_info(|| {
                                egui::WidgetInfo::labeled(
                                    egui::accesskit::Role::Button,
                                    true,
                                    &words,
                                )
                            });
                            if resp.clicked() {
                                *open = !*open;
                            }
                        };
                        if !archived.is_empty() {
                            quiet(ui, crate::labels::archived(archived.len()), &mut arch_open);
                            if !elsewhere.is_empty() {
                                ui.label(RichText::new("·").color(MUTED).size(12.5));
                            }
                        }
                        if !elsewhere.is_empty() {
                            quiet(
                                ui,
                                crate::labels::from_elsewhere(elsewhere.len()),
                                &mut else_open,
                            );
                        }
                        if !projects.is_empty() {
                            ui.label(RichText::new("·").color(MUTED).size(12.5));
                            let mut open = false;
                            quiet(ui, "Open a project folder".to_string(), &mut open);
                            if open {
                                actions.push(Action::PickFolder);
                            }
                        }
                        // Git projects beside the others that aren't listed.
                        if !repos.is_empty() {
                            ui.label(RichText::new("·").color(MUTED).size(12.5));
                            let mut open = false;
                            quiet(ui, crate::labels::add_projects(repos.len()), &mut open);
                            if open {
                                actions.push(Action::Organize(Organize::AskAdd(repos.clone())));
                            }
                        }
                    });
                    ui.data_mut(|d| {
                        d.insert_temp(arch_id, arch_open);
                        d.insert_temp(else_id, else_open);
                    });
                    let arch = ease(ui.ctx().animate_bool_with_time(
                        arch_id.with("anim"),
                        arch_open && !archived.is_empty(),
                        FOLD_SECS,
                    ));
                    if arch > 0.0 {
                        ui.scope(|ui| {
                            ui.multiply_opacity(arch);
                            for l in &archived {
                                task_row(ui, l, selected.as_deref(), view, None, false, actions);
                            }
                        });
                    }
                    let other = ease(ui.ctx().animate_bool_with_time(
                        else_id.with("anim"),
                        else_open,
                        FOLD_SECS,
                    ));
                    if other > 0.0 {
                        ui.scope(|ui| {
                            ui.multiply_opacity(other);
                            ui.spacing_mut().item_spacing.y = 6.0;
                            ui.label(
                                RichText::new(
                                    "Chats you had in the terminal, another app or on your phone. \
                                 Click one to carry on here.",
                                )
                                .color(MUTED)
                                .size(13.0),
                            );
                            for o in elsewhere {
                                other_row(ui, o, actions);
                            }
                        });
                    }
                });
            });
        });
    }
}

/// A pre-lowercased search index for one task: built once per task revision,
/// never lowercased again on each keystroke.
#[allow(dead_code)] // kept for its test; the side list searches with `matches`
pub(crate) struct TaskIndex {
    pub id: String,
    pub lowered: String,
}

#[allow(dead_code)]
impl TaskIndex {
    /// Build the index for a task (lowercased once here, never again).
    pub fn new(task: &mimir_core::task::Task) -> Self {
        let mut lowered = task.title.to_lowercase();
        for e in &task.entries {
            match e {
                mimir_core::task::Entry::You(t) | mimir_core::task::Entry::Claude(t) => {
                    lowered.push('\n');
                    lowered.push_str(&t.to_lowercase());
                }
                _ => {}
            }
        }
        TaskIndex {
            id: task.id.clone(),
            lowered,
        }
    }
}

/// Whether a task matches what is typed in the search box.
/// `needle` must already be lowercased by the caller (once per keystroke,
/// not once per task).
fn matches(needle: &str, l: &TaskView) -> bool {
    needle.is_empty()
        || l.task.title.to_lowercase().contains(needle)
        || l.task.entries.iter().any(|e| match e {
            mimir_core::task::Entry::You(t) | mimir_core::task::Entry::Claude(t) => {
                t.to_lowercase().contains(needle)
            }
            _ => false,
        })
}

/// Whether a pre-built `TaskIndex` matches the already-lowercased needle.
/// No `.to_lowercase()` calls here — lowercasing happened once at index time.
#[allow(dead_code)]
pub(crate) fn index_matches(needle: &str, idx: &TaskIndex) -> bool {
    needle.is_empty() || idx.lowered.contains(needle)
}

/// What the project list is drawn from.
struct Side<'a> {
    r: &'a Station,
    settings: &'a Settings,
    projects: &'a [String],
    needle: &'a str,
    selected: Option<&'a str>,
    view: View,
    remote_on: &'a std::collections::HashSet<String>,
    /// The general chat on screen.
    chat: Option<&'a str>,
    /// Each chat's text in lower case, for the search.
    chat_index: &'a std::collections::HashMap<String, String>,
}

/// A line across the sidebar where a dragged row will land.
fn drop_line(ui: &egui::Ui, rect: egui::Rect, below: bool) {
    let y = if below {
        rect.bottom() + 2.0
    } else {
        rect.top() - 2.0
    };
    ui.painter()
        .hline(rect.x_range(), y, egui::Stroke::new(2.0, GREEN));
}

impl Side<'_> {
    /// "Chats": the general chats, newest first, the same ones the phone
    /// shows. A folding section; its count is on the right.
    fn chats(&self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let now_ms = mimir_core::needs::now() * 1000;
        let chats: Vec<&mimir_core::proto::ChatRecord> = self
            .r
            .chats
            .values()
            .filter(|c| {
                self.needle.is_empty()
                    || self
                        .chat_index
                        .get(&c.id)
                        .is_some_and(|h| h.contains(self.needle))
            })
            .collect();
        // PC chats: tasks in the Chats folder, mixed in newest first.
        let pc: Vec<&TaskView> = self
            .r
            .tasks
            .iter()
            .filter(|l| !l.task.archived && l.task.is_chat())
            .filter(|l| matches(self.needle, l))
            .collect();
        let mut rows: Vec<(i64, Result<&mimir_core::proto::ChatRecord, &TaskView>)> = chats
            .into_iter()
            .map(|c| (c.at / 1000, Ok(c)))
            .chain(pc.into_iter().map(|l| (last_change(&l.task), Err(l))))
            .collect();
        rows.sort_by_key(|r| std::cmp::Reverse(r.0));
        let searching = !self.needle.is_empty();
        if searching && rows.is_empty() {
            return;
        }
        let folded = !searching && self.settings.chats_folded;
        ui.add_space(2.0);
        let head = section_head(
            ui,
            crate::labels::CHATS,
            folded,
            &[(rows.len().to_string(), MUTED)],
        );
        if head.clicked() {
            actions.push(Action::ToggleSection(true));
        }
        if folded {
            return;
        }
        // Five rows at most; "Show N older" opens the rest.
        let all_id = egui::Id::unique("side-chats-all");
        let show_all = searching || ui.data(|d| d.get_temp::<bool>(all_id).unwrap_or(false));
        let shown = if show_all {
            rows.len()
        } else {
            rows.len().min(MAX_ROWS)
        };
        for (_, row) in &rows[..shown] {
            match row {
                Ok(c) => self.chat_row(ui, c, now_ms, actions),
                Err(l) => task_row(ui, l, self.selected, self.view, None, true, actions),
            }
        }
        if rows.len() > shown {
            let words = format!("Show {} older", rows.len() - shown);
            let resp = ui
                .add(
                    egui::Label::new(RichText::new(&words).color(MUTED).size(12.5))
                        .selectable(false)
                        .sense(egui::Sense::click()),
                )
                .on_hover_cursor(egui::CursorIcon::PointingHand);
            resp.widget_info(|| {
                egui::WidgetInfo::labeled(egui::accesskit::Role::Button, true, &words)
            });
            if resp.clicked() {
                ui.data_mut(|d| d.insert_temp(all_id, true));
            }
        }
    }

    /// One chat: its title, and when the last message was.
    fn chat_row(
        &self,
        ui: &mut egui::Ui,
        c: &mimir_core::proto::ChatRecord,
        now_ms: i64,
        actions: &mut Vec<Action>,
    ) {
        let title = crate::view_chat::chat_title(c);
        let is_sel = self.view == View::Chat && self.chat == Some(c.id.as_str());
        let answering = self.r.chat_busy(&c.id);
        let w = ui.available_width();
        let (rect, resp) = ui.allocate_exact_size(egui::vec2(w, ROW_H), egui::Sense::click());
        resp.widget_info(|| {
            egui::WidgetInfo::labeled(
                egui::accesskit::Role::Button,
                true,
                crate::labels::chat_row(&title),
            )
        });
        let id = egui::Id::unique(("chat-row", &c.id));
        let under = ui.rect_contains_pointer(rect);
        if ui.is_rect_visible(rect) {
            let sel = ui.ctx().animate_bool_with_time(id.with("sel"), is_sel, 0.1);
            let hov = ui.ctx().animate_bool_with_time(id.with("hov"), under, 0.1);
            let p = ui.painter();
            row_fill(p, rect, sel, hov);
            let (note, color) = if answering {
                (crate::labels::ANSWERING.to_string(), GREEN)
            } else {
                (chat_when(c.at / 1000, now_ms / 1000), MUTED)
            };
            let g = elided(ui, &note, 12.5, color, false, 90.0);
            let right = rect.right() - 8.0 - g.size().x;
            p.galley(
                egui::pos2(right, rect.center().y - g.size().y / 2.0),
                g,
                color,
            );
            let tcolor = if is_sel {
                TEXT
            } else {
                TEXT.gamma_multiply(0.92)
            };
            let g = elided(
                ui,
                &title,
                ROW_TEXT,
                tcolor,
                false,
                right - 8.0 - (rect.left() + 8.0),
            );
            p.galley(
                egui::pos2(rect.left() + 8.0, rect.center().y - g.size().y / 2.0),
                g,
                tcolor,
            );
        }
        if answering {
            ui.ctx().request_repaint_after(SPIN_EVERY);
        }
        if resp.clicked() {
            actions.push(Action::OpenChat(c.id.clone()));
        }
        crate::shot::note_chat_row(ui.ctx(), rect);
        crate::menus::open(&resp, |ui| {
            if crate::menus::item(ui, "Open", "") {
                actions.push(Action::OpenChat(c.id.clone()));
            }
            if crate::menus::item(ui, "Copy the whole chat", "") {
                ui.ctx().copy_text(crate::menus::chat_text(c));
            }
            if crate::menus::item(ui, "Rename chat…", "") {
                actions.push(Action::AskRenameChat(c.id.clone()));
            }
            crate::menus::sep(ui);
            if crate::menus::danger(ui, "Delete chat…", if is_sel { "Del" } else { "" }) {
                actions.push(Action::ConfirmDeleteChat(c.id.clone()));
            }
        });
    }

    /// "Projects": a folding section below Chats, each project with its
    /// state and, indented under it, its tasks.
    fn projects_section(&self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let searching = !self.needle.is_empty();
        let folded = !searching && self.settings.projects_folded;
        let (_, waiting) = self.r.counts();
        ui.add_space(2.0);
        let head = section_head(
            ui,
            "Projects",
            folded,
            &projects_note(self.projects.len(), waiting, folded),
        );
        if head.clicked() {
            actions.push(Action::ToggleSection(false));
        }
        if folded {
            return;
        }
        for (i, project) in self.projects.iter().enumerate() {
            self.project(ui, i, project, actions);
        }
    }

    /// One project: its header (fold, drag, +, right-click menu) and its
    /// tasks, one line each.
    fn project(
        &self,
        ui: &mut egui::Ui,
        index: usize,
        project: &String,
        actions: &mut Vec<Action>,
    ) {
        let org = |o: Organize| Action::Organize(o);
        let searching = !self.needle.is_empty();
        let all = project_tasks(self.r, self.settings, project);
        let pinned = &self.settings.pinned_tasks;
        let mut tasks: Vec<&TaskView> = all
            .iter()
            .copied()
            .filter(|l| matches(self.needle, l))
            .collect();
        if searching && tasks.is_empty() {
            return;
        }
        // Pinned, needs you, working, paused, then the rest as they were.
        tasks.sort_by_key(|l| rank(l, pinned.contains(&l.task.id)));
        let folded = !searching && self.settings.collapsed_projects.contains(project);
        let waiting = all
            .iter()
            .filter(|l| l.shown_state() == mimir_core::proto::TaskState::NeedsYou)
            .count();
        let name = display_name(self.settings, project);
        ui.add_space(2.0);
        let w = ui.available_width();
        let (rect, head) =
            ui.allocate_exact_size(egui::vec2(w, HEAD_H), egui::Sense::click_and_drag());
        let head = head
            .on_hover_cursor(egui::CursorIcon::PointingHand)
            .on_hover_text(format!(
                "{project}\nClick to open or close. Drag to move it. Right-click for more."
            ));
        head.widget_info(|| {
            egui::WidgetInfo::labeled(
                egui::accesskit::Role::Button,
                true,
                crate::labels::folder_row(&name),
            )
        });
        let open = ui.ctx().animate_bool_with_time(
            egui::Id::unique(("folder-open", project)),
            !folded,
            FOLD_SECS,
        );
        let open = ease(open);
        let missing = !std::path::Path::new(project).is_dir();
        let running = all
            .iter()
            .filter(|l| {
                matches!(
                    l.shown_state(),
                    mimir_core::proto::TaskState::Working
                        | mimir_core::proto::TaskState::LiveElsewhere
                )
            })
            .count();
        let under = ui.rect_contains_pointer(rect) && !egui::DragAndDrop::has_any_payload(ui.ctx());
        if ui.is_rect_visible(rect) {
            let p = ui.painter();
            if head.hovered() {
                p.rect_filled(rect, egui::CornerRadius::same(8), HOVER);
            }
            // The arrow turns from pointing right (closed) to down (open).
            if !all.is_empty() {
                let c = egui::pos2(rect.left() + 14.0, rect.center().y);
                let turn = std::f32::consts::FRAC_PI_2
                    * ease(ui.ctx().animate_bool_with_time(
                        egui::Id::unique(("folder-arrow", project)),
                        !folded,
                        0.16,
                    ));
                let rot = egui::emath::Rot2::from_angle(turn);
                let pts = [
                    egui::vec2(-2.0, -4.0),
                    egui::vec2(2.0, 0.0),
                    egui::vec2(-2.0, 4.0),
                ]
                .map(|v| c + rot * v);
                p.add(egui::Shape::line(
                    pts.to_vec(),
                    egui::Stroke::new(1.5, MUTED),
                ));
            }
            // Right: the project's state in a few words. The "+" takes its
            // place while the pointer is on the row.
            let (note, note_color) = project_state(missing, waiting, running);
            let note = elided(ui, &note, 12.5, note_color, false, w * 0.5);
            let edge = if under { PLUS + 6.0 } else { 8.0 };
            let note_x = rect.right() - edge - note.size().x;
            if note_color == GREEN {
                // A green dot before "N running".
                ui.painter()
                    .circle_filled(egui::pos2(note_x - 8.0, rect.center().y), 3.5, GREEN);
            }
            ui.painter().galley(
                egui::pos2(note_x, rect.center().y - note.size().y / 2.0),
                note,
                note_color,
            );
            let title = elided(
                ui,
                &name,
                ROW_TEXT,
                TEXT,
                false,
                note_x - 20.0 - (rect.left() + 26.0),
            );
            ui.painter().galley(
                egui::pos2(rect.left() + 26.0, rect.center().y - title.size().y / 2.0),
                title,
                TEXT,
            );
        }
        // + : a new task in this folder, shown while the pointer is on the row.
        let plus = egui::Rect::from_center_size(
            egui::pos2(rect.right() - PLUS / 2.0 - 4.0, rect.center().y),
            egui::vec2(PLUS, PLUS),
        );
        let mut plus_clicked = false;
        {
            let plus_resp = ui
                .interact(
                    plus,
                    egui::Id::unique(("folder-plus", project)),
                    egui::Sense::click(),
                )
                .on_hover_text("New task in this folder");
            plus_resp.widget_info(|| {
                egui::WidgetInfo::labeled(
                    egui::accesskit::Role::Button,
                    true,
                    crate::labels::new_task_in(&name),
                )
            });
            if under {
                if plus_resp.hovered() {
                    ui.painter()
                        .rect_filled(plus, egui::CornerRadius::same(6), theme::LINE);
                }
                let c = plus.center();
                let col = if plus_resp.hovered() { TEXT } else { MUTED };
                let st = egui::Stroke::new(1.5, col);
                ui.painter()
                    .line_segment([c - egui::vec2(4.5, 0.0), c + egui::vec2(4.5, 0.0)], st);
                ui.painter()
                    .line_segment([c - egui::vec2(0.0, 4.5), c + egui::vec2(0.0, 4.5)], st);
                plus_clicked = plus_resp.clicked();
            }
        }
        if plus_clicked {
            actions.push(Action::NewTaskIn(project.clone()));
        } else if head.clicked() && !all.is_empty() {
            actions.push(org(Organize::ToggleProject(project.clone())));
        }
        if head.drag_started() {
            egui::DragAndDrop::set_payload(ui.ctx(), ProjectDrag(project.clone()));
        }
        crate::menus::open(&head, |ui| self.project_menu(ui, index, project, actions));
        // A dragged project dropped anywhere on this header lands here.
        let over = ui.rect_contains_pointer(rect);
        if over
            && let Some(d) = egui::DragAndDrop::payload::<ProjectDrag>(ui.ctx())
            && &d.0 != project
        {
            let from = self.projects.iter().position(|p| p == &d.0);
            drop_line(ui, rect, from.is_some_and(|f| f < index));
            if ui.input(|i| i.pointer.any_released()) {
                actions.push(org(Organize::DropProject(d.0.clone(), project.clone())));
            }
        }
        if open <= 0.0 || tasks.is_empty() {
            return;
        }
        // Five rows at most; the rest fold under "Show N older". A task
        // that needs you, is working, is pinned or is open always shows.
        let older_id = egui::Id::unique(("folder-older", project));
        let show_all = searching || ui.data(|d| d.get_temp::<bool>(older_id).unwrap_or(false));
        let always = |l: &TaskView| {
            rank(l, pinned.contains(&l.task.id)) <= 2 || self.selected == Some(l.task.id.as_str())
        };
        let mut shown: Vec<&TaskView> = Vec::new();
        let mut hidden = 0;
        for l in &tasks {
            if show_all || always(l) || shown.len() < MAX_ROWS {
                shown.push(l);
            } else {
                hidden += 1;
            }
        }
        let foldable = !searching && tasks.len() > MAX_ROWS && (hidden > 0 || show_all);
        let full = shown.len() as f32 * (ROW_H + ROW_GAP) - ROW_GAP
            + if foldable { OLDER_H + ROW_GAP } else { 0.0 };
        let mut rows = |ui: &mut egui::Ui| {
            for (i, l) in shown.iter().enumerate() {
                let is_pinned = pinned.contains(&l.task.id);
                // Up and down stay among the pinned, or among the rest.
                let at = tasks
                    .iter()
                    .position(|o| o.task.id == l.task.id)
                    .unwrap_or(i);
                let peer = |j: Option<usize>| {
                    j.and_then(|j| tasks.get(j))
                        .is_some_and(|o| pinned.contains(&o.task.id) == is_pinned)
                };
                let place = Place {
                    project,
                    index: at,
                    pinned: is_pinned,
                    up: !searching && peer(at.checked_sub(1)),
                    down: !searching && peer(Some(at + 1)),
                    order: &tasks,
                };
                task_row(ui, l, self.selected, self.view, Some(place), false, actions);
            }
            if foldable {
                let w = ui.available_width();
                let (r, resp) =
                    ui.allocate_exact_size(egui::vec2(w, OLDER_H), egui::Sense::click());
                let words = if show_all {
                    "Show fewer".to_string()
                } else {
                    format!("Show {hidden} older")
                };
                let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
                resp.widget_info(|| {
                    egui::WidgetInfo::labeled(egui::accesskit::Role::Button, true, &words)
                });
                if ui.is_rect_visible(r) {
                    let col = if resp.hovered() { TEXT } else { MUTED };
                    let g = elided(ui, &words, 12.5, col, false, w - TITLE_X - 8.0);
                    ui.painter().galley(
                        egui::pos2(r.left() + TITLE_X, r.center().y - g.size().y / 2.0),
                        g,
                        col,
                    );
                }
                if resp.clicked() {
                    ui.data_mut(|d| d.insert_temp(older_id, !show_all));
                }
            }
        };
        if open >= 1.0 {
            rows(ui);
        } else {
            // Opening or closing: the rows slide and fade, and the folders
            // below glide with them.
            let (clip, _) =
                ui.allocate_exact_size(egui::vec2(w, full * open), egui::Sense::hover());
            let mut child = ui.new_child(
                egui::UiBuilder::new()
                    .max_rect(egui::Rect::from_min_size(
                        egui::pos2(clip.left(), clip.bottom() - full),
                        egui::vec2(w, full),
                    ))
                    .layout(egui::Layout::top_down(egui::Align::Min)),
            );
            child.set_clip_rect(clip.intersect(ui.clip_rect()));
            child.multiply_opacity(open);
            rows(&mut child);
        }
    }

    fn project_menu(
        &self,
        ui: &mut egui::Ui,
        index: usize,
        project: &String,
        actions: &mut Vec<Action>,
    ) {
        use crate::menus::{danger, item, item_if, sep};
        let org = |o: Organize| Action::Organize(o);
        if item(ui, "New task here", "") {
            actions.push(Action::NewTaskIn(project.clone()));
        }
        if item(ui, "Continue an earlier task…", "") {
            actions.push(Action::OpenPast(project.clone()));
        }
        sep(ui);
        if item(ui, "Open the folder", "") {
            actions.push(Action::OpenPath(PathBuf::from(project)));
        }
        if item(ui, "Open a terminal here", "") {
            actions.push(Action::OpenTerminal(PathBuf::from(project)));
        }
        if std::path::Path::new(project) != crate::places::chats_dir()
            && item(
                ui,
                if self.remote_on.contains(project) {
                    "Turn remote control off"
                } else {
                    "Turn on remote control"
                },
                "",
            )
        {
            actions.push(Action::ToggleRemote(project.clone()));
        }
        sep(ui);
        if item(ui, "Rename…", "") {
            actions.push(org(Organize::AskName(project.clone())));
        }
        if item(ui, crate::labels::MERGE_INTO, "") {
            actions.push(org(Organize::AskMerge(project.clone())));
        }
        if item_if(ui, index > 0, "Move up", "") {
            actions.push(org(Organize::MoveProject(project.clone(), true)));
        }
        if item_if(ui, index + 1 < self.projects.len(), "Move down", "") {
            actions.push(org(Organize::MoveProject(project.clone(), false)));
        }
        sep(ui);
        // Takes it off the list only: its files stay, as the question says.
        if danger(ui, crate::labels::REMOVE_FROM_LIST, "") {
            actions.push(org(Organize::AskRemoveProject(project.clone())));
        }
    }
}

/// Where an open task sits in its project's list.
struct Place<'a> {
    project: &'a str,
    index: usize,
    pinned: bool,
    up: bool,
    down: bool,
    order: &'a [&'a TaskView],
}

/// Sizes of the side list (docs/designs/pc-window, boards 8 and 9), in
/// points at 100% text.
const ROW_H: f32 = 30.0;
const ROW_GAP: f32 = 2.0;
const HEAD_H: f32 = 26.0;
const OLDER_H: f32 = 26.0;
const PLUS: f32 = 22.0;
/// From the row's left edge to the mark's box, and on to the title.
const MARK_X: f32 = 22.0;
const TITLE_X: f32 = 46.0;
const MAX_ROWS: usize = 5;
const FOLD_SECS: f32 = 0.2;
/// A row under the pointer.
const HOVER: egui::Color32 = egui::Color32::from_rgb(0x1C, 0x23, 0x20);

/// Colours of the menu, from the design (mockups/pc-v2.html).
const MENU_BG: egui::Color32 = egui::Color32::from_rgb(0x15, 0x18, 0x1D);
/// Dim text.
pub(crate) const MUTED: egui::Color32 = egui::Color32::from_rgb(0x9A, 0xA1, 0xAD);
pub(crate) const AMBER: egui::Color32 = egui::Color32::from_rgb(0xF3, 0xC9, 0x69);
const GREEN: egui::Color32 = egui::Color32::from_rgb(0x7F, 0xD6, 0xA4);
const BORDER: egui::Color32 = egui::Color32::from_rgb(0x2B, 0x2F, 0x37);
/// The open row.
const ROW_SEL: egui::Color32 = egui::Color32::from_rgb(0x1F, 0x23, 0x2B);
const SEARCH_FILL: egui::Color32 = egui::Color32::from_rgb(0x1A, 0x1D, 0x23);
const SECTION_TEXT: egui::Color32 = egui::Color32::from_rgb(0xC3, 0xC8, 0xD1);
const CHAT_FILL: egui::Color32 = egui::Color32::from_rgb(0x1D, 0x2A, 0x45);
const CHAT_TEXT: egui::Color32 = egui::Color32::from_rgb(0xCF, 0xE0, 0xFF);
const TASK_FILL: egui::Color32 = egui::Color32::from_rgb(0x17, 0x32, 0x29);
const TASK_TEXT: egui::Color32 = egui::Color32::from_rgb(0xBF, 0xEA, 0xD6);
const AMBER_BADGE: egui::Color32 = egui::Color32::from_rgb(0xE0, 0xA9, 0x2B);
const AMBER_ON: egui::Color32 = egui::Color32::from_rgb(0x1B, 0x14, 0x00);
const BLUE_BADGE: egui::Color32 = egui::Color32::from_rgb(0x3B, 0x82, 0xF6);
/// The search box's widget id (Ctrl+K puts the cursor there).
pub(crate) fn search_id() -> egui::Id {
    egui::Id::unique("side-search")
}
/// Text size of a menu row.
const ROW_TEXT: f32 = 14.5;
const NAV_H: f32 = 26.0;

/// A row under the pointer or the open one: a soft fill.
fn row_fill(p: &egui::Painter, rect: egui::Rect, sel: f32, hov: f32) {
    let fill = if sel > 0.0 {
        HOVER
            .lerp_to_gamma(ROW_SEL, sel)
            .gamma_multiply(sel.max(hov))
    } else {
        HOVER.gamma_multiply(hov)
    };
    if sel > 0.0 || hov > 0.0 {
        p.rect_filled(rect, egui::CornerRadius::same(8), fill);
    }
}

/// The search box, with its "Ctrl K" hint while it is empty.
fn search_box(ui: &mut egui::Ui, search: &mut String, w: f32) {
    let resp = ui
        .scope(|ui| {
            let v = ui.visuals_mut();
            v.extreme_bg_color = SEARCH_FILL;
            for wv in [
                &mut v.widgets.inactive,
                &mut v.widgets.hovered,
                &mut v.widgets.active,
            ] {
                wv.corner_radius = egui::CornerRadius::same(12);
            }
            v.widgets.inactive.bg_stroke = egui::Stroke::new(1.0, BORDER);
            crate::menus::before_edit(ui.ctx(), "side-search", search);
            ui.add_sized(
                [w, 30.0],
                TextEdit::singleline(search)
                    .id(search_id())
                    .hint_text("Search chats and tasks")
                    .margin(egui::Margin::symmetric(12, 6)),
            )
        })
        .inner;
    crate::menus::edit_menu(&resp, "side-search");
    if search.is_empty() && !resp.has_focus() && ui.is_rect_visible(resp.rect) {
        let g =
            ui.painter()
                .layout_no_wrap("Ctrl K".into(), egui::FontId::proportional(11.5), MUTED);
        let r = egui::Rect::from_center_size(
            egui::pos2(
                resp.rect.right() - 12.0 - (g.size().x + 10.0) / 2.0,
                resp.rect.center().y,
            ),
            egui::vec2(g.size().x + 10.0, g.size().y + 2.0),
        );
        ui.painter().rect_stroke(
            r,
            egui::CornerRadius::same(5),
            egui::Stroke::new(1.0, BORDER),
            egui::StrokeKind::Inside,
        );
        ui.painter().galley(
            egui::pos2(r.left() + 5.0, r.center().y - g.size().y / 2.0),
            g,
            MUTED,
        );
    }
}

/// What a menu row shows on its right.
enum Right {
    Dim(String),
    /// A count in a coloured pill (nothing while it is 0): fill, text.
    Badge(usize, egui::Color32, egui::Color32),
}

/// One of the four rows under the buttons (Needs you, Updates, Phones,
/// Whisper): its name on the left, a count or a few dim words on the right.
fn nav_row(ui: &mut egui::Ui, name: &str, right: Right, open: bool) -> egui::Response {
    let w = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(w, NAV_H), egui::Sense::click());
    let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
    resp.widget_info(|| egui::WidgetInfo::labeled(egui::accesskit::Role::Button, true, name));
    if ui.is_rect_visible(rect) {
        let id = egui::Id::unique(("nav-row", name));
        let sel = ui.ctx().animate_bool_with_time(id.with("sel"), open, 0.1);
        let hov = ui
            .ctx()
            .animate_bool_with_time(id.with("hov"), resp.hovered(), 0.1);
        row_fill(ui.painter(), rect, sel, hov);
        let g = elided(ui, name, ROW_TEXT, TEXT, false, w * 0.6);
        let p = ui.painter();
        p.galley(
            egui::pos2(rect.left() + 8.0, rect.center().y - g.size().y / 2.0),
            g,
            TEXT,
        );
        match right {
            Right::Dim(words) => {
                let g = elided(ui, &words, 12.5, MUTED, false, w * 0.5);
                ui.painter().galley(
                    egui::pos2(
                        rect.right() - 8.0 - g.size().x,
                        rect.center().y - g.size().y / 2.0,
                    ),
                    g,
                    MUTED,
                );
            }
            Right::Badge(n, fill, on) if n > 0 => {
                let g = elided(ui, &n.to_string(), 11.5, on, true, 40.0);
                let size = egui::vec2((g.size().x + 14.0).max(20.0), 18.0);
                let pill = egui::Rect::from_center_size(
                    egui::pos2(rect.right() - 8.0 - size.x / 2.0, rect.center().y),
                    size,
                );
                let p = ui.painter();
                p.rect_filled(pill, egui::CornerRadius::same(9), fill);
                p.galley(pill.center() - g.size() / 2.0, g, on);
            }
            Right::Badge(..) => {}
        }
    }
    resp
}

/// A project's state on its row: "needs you" (amber) before "N running"
/// (green) before "idle" (dim).
fn project_state(missing: bool, waiting: usize, running: usize) -> (String, egui::Color32) {
    if missing {
        ("folder is missing".to_string(), AMBER)
    } else if waiting > 0 {
        ("needs you".to_string(), AMBER)
    } else if running > 0 {
        (format!("{running} running"), GREEN)
    } else {
        ("idle".to_string(), MUTED)
    }
}

/// The right of the Projects heading: how many, and while it is folded and
/// something waits for the owner, "· 1 needs you" in amber.
fn projects_note(total: usize, waiting: usize, folded: bool) -> Vec<(String, egui::Color32)> {
    let mut note = vec![(total.to_string(), MUTED)];
    if folded && waiting > 0 {
        note.push((" · ".to_string(), MUTED));
        note.push((crate::labels::need_you(waiting), AMBER));
    }
    note
}

/// The search box and the two start buttons.
fn top_block(ui: &mut egui::Ui, search: &mut String, actions: &mut Vec<Action>) {
    let w = ui.available_width();
    search_box(ui, search, w);
    ui.add_space(4.0);
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 8.0;
        let half = (w - 8.0) / 2.0;
        let chat = egui::Button::new(
            RichText::new("+  New chat")
                .color(CHAT_TEXT)
                .size(14.0)
                .strong(),
        )
        .fill(CHAT_FILL)
        .corner_radius(egui::CornerRadius::same(12));
        let resp = ui.add_sized([half, 32.0], chat);
        resp.widget_info(|| {
            egui::WidgetInfo::labeled(egui::accesskit::Role::Button, true, crate::labels::NEW_CHAT)
        });
        if resp
            .on_hover_text("A general chat your PC answers, the same as on your phone (Ctrl+N)")
            .clicked()
        {
            actions.push(Action::NewChat);
        }
        let task = egui::Button::new(
            RichText::new("+  New task")
                .color(TASK_TEXT)
                .size(14.0)
                .strong(),
        )
        .fill(TASK_FILL)
        .corner_radius(egui::CornerRadius::same(12));
        if ui
            .add_sized([half, 32.0], task)
            .on_hover_text("Work on a project folder")
            .clicked()
        {
            actions.push(Action::NewTask);
        }
    });
    ui.add_space(4.0);
}

/// The heading of a folding section: an arrow, the name in capitals, and
/// a few words on the right, over a thin line.
fn section_head(
    ui: &mut egui::Ui,
    name: &str,
    folded: bool,
    note: &[(String, egui::Color32)],
) -> egui::Response {
    let w = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(w, NAV_H), egui::Sense::click());
    let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
    let words = if folded {
        format!("{name}, folded")
    } else {
        name.to_string()
    };
    resp.widget_info(|| egui::WidgetInfo::labeled(egui::accesskit::Role::Button, !folded, &words));
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        p.hline(rect.x_range(), rect.top(), egui::Stroke::new(1.0, BORDER));
        let c = egui::pos2(rect.left() + 12.0, rect.center().y);
        // › closed, ⌄ open.
        let pts = if folded {
            [
                egui::vec2(-2.0, -4.0),
                egui::vec2(2.0, 0.0),
                egui::vec2(-2.0, 4.0),
            ]
        } else {
            [
                egui::vec2(-4.0, -2.0),
                egui::vec2(0.0, 2.0),
                egui::vec2(4.0, -2.0),
            ]
        }
        .map(|v| c + v);
        p.add(egui::Shape::line(
            pts.to_vec(),
            egui::Stroke::new(1.5, SECTION_TEXT),
        ));
        // The note, right to left.
        let mut right = rect.right() - 8.0;
        for (text, color) in note.iter().rev() {
            let g = elided(ui, text, 12.5, *color, false, w * 0.6);
            right -= g.size().x;
            ui.painter().galley(
                egui::pos2(right, rect.center().y - g.size().y / 2.0),
                g,
                *color,
            );
        }
        let g = elided(
            ui,
            &name.to_uppercase(),
            12.5,
            SECTION_TEXT,
            true,
            right - 8.0 - (rect.left() + 26.0),
        );
        ui.painter().galley(
            egui::pos2(rect.left() + 26.0, rect.center().y - g.size().y / 2.0),
            g,
            SECTION_TEXT,
        );
    }
    resp
}

/// When a chat last changed, in little room: "now", "5 min", "3 h",
/// "yesterday", "Tue", "Nov 9".
pub(crate) fn chat_when(unix: i64, now: i64) -> String {
    let s = (now - unix).max(0);
    match s {
        0..=86_399 => short_ago(unix, now),
        86_400..=172_799 => "yesterday".into(),
        172_800..=518_399 => short_ago(unix, now),
        _ => {
            // Days since 1970-01-01 to a calendar date (UTC).
            let z = unix.div_euclid(86_400) + 719_468;
            let doe = z.rem_euclid(146_097);
            let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
            let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
            let mp = (5 * doy + 2) / 153;
            let day = doy - (153 * mp + 2) / 5 + 1;
            let month = if mp < 10 { mp + 3 } else { mp - 9 };
            const NAMES: [&str; 12] = [
                "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
            ];
            format!("{} {day}", NAMES[(month - 1) as usize])
        }
    }
}

/// A quick start and a soft landing.
fn ease(t: f32) -> f32 {
    1.0 - (1.0 - t.clamp(0.0, 1.0)).powi(3)
}

/// One line of text, cut with "…" when it is wider than `width`.
fn elided(
    ui: &egui::Ui,
    text: &str,
    size: f32,
    color: egui::Color32,
    strong: bool,
    width: f32,
) -> std::sync::Arc<egui::Galley> {
    let mut job = egui::text::LayoutJob::default();
    // "Strong" is the section name: heading font, a little smaller.
    let mut rich = RichText::new(text).size(size).color(color);
    if strong {
        rich = theme::title(text, size * 0.92).color(color);
    }
    rich.append_to(
        &mut job,
        ui.style(),
        egui::FontSelection::Default,
        egui::Align::Center,
    );
    job.wrap = egui::text::TextWrapping {
        max_width: width.max(0.0),
        max_rows: 1,
        break_anywhere: true,
        overflow_character: Some('…'),
    };
    ui.painter().layout_job(job)
}

/// Where a task sorts inside its folder: pinned, needs you, working,
/// paused, then everything else.
fn rank(l: &TaskView, pinned: bool) -> u8 {
    use mimir_core::proto::TaskState;
    use mimir_core::task::Status;
    let state = l.shown_state();
    if pinned {
        0
    } else if state == TaskState::NeedsYou {
        1
    } else if matches!(state, TaskState::Working | TaskState::LiveElsewhere) {
        2
    } else if l.task.status == Status::Paused {
        3
    } else {
        4
    }
}

/// When a task last changed: its newest step, or when it was made.
fn last_change(t: &mimir_core::task::Task) -> i64 {
    t.entries
        .iter()
        .rev()
        .find_map(|e| match e {
            mimir_core::task::Entry::Step(s) => {
                Some(((s.started_ms + s.took_ms.unwrap_or(0)) / 1000) as i64)
            }
            _ => None,
        })
        .unwrap_or(0)
        .max(t.created_unix)
}

/// "now", "5 min", "3 h", "Tue", "12 Nov": how long ago, in little room.
pub(crate) fn short_ago(unix: i64, now: i64) -> String {
    let s = (now - unix).max(0);
    match s {
        0..=59 => "now".into(),
        60..=3599 => format!("{} min", s / 60),
        3600..=86_399 => format!("{} h", s / 3600),
        86_400..=518_399 => {
            // 1970-01-01 was a Thursday.
            const DAYS: [&str; 7] = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"];
            DAYS[(unix.div_euclid(86_400)).rem_euclid(7) as usize].into()
        }
        _ => format!("{} d", s / 86_400),
    }
}

/// How often a turning mark is redrawn.
pub(crate) const SPIN_EVERY: std::time::Duration = std::time::Duration::from_millis(33);

/// The mark before a task's title. Each state has its own shape, so colour
/// is never the only sign.
fn mark(ui: &egui::Ui, c: egui::Pos2, l: &TaskView) {
    use mimir_core::proto::TaskState;
    use mimir_core::task::Status;
    let p = ui.painter();
    let state = l.shown_state();
    if state == TaskState::NeedsYou {
        p.circle_filled(c, 4.5, AMBER);
    } else if state == TaskState::Working {
        // A turning arc: Claude works on it.
        let t = ui.input(|i| i.time) as f32;
        let start = t * 4.0;
        let pts: Vec<egui::Pos2> = (0..=12)
            .map(|i| {
                let a = start + i as f32 / 12.0 * 4.4;
                c + 5.0 * egui::vec2(a.cos(), a.sin())
            })
            .collect();
        p.add(egui::Shape::line(pts, egui::Stroke::new(1.6, GREEN)));
        // About 30 turns of the arc a second: smooth, without redrawing the
        // whole window as fast as the screen allows.
        ui.ctx().request_repaint_after(SPIN_EVERY);
    } else if state == TaskState::LiveElsewhere {
        // A still ring: going on elsewhere, nothing turning here.
        p.circle_stroke(c, 4.5, egui::Stroke::new(1.6, GREEN));
    } else {
        match l.task.status {
            Status::Paused => {
                for dx in [-2.5, 2.5] {
                    p.rect_filled(
                        egui::Rect::from_center_size(c + egui::vec2(dx, 0.0), egui::vec2(2.5, 9.0)),
                        egui::CornerRadius::ZERO,
                        MUTED,
                    );
                }
            }
            Status::Stopped => {
                p.rect_filled(
                    egui::Rect::from_center_size(c, egui::vec2(8.0, 8.0)),
                    egui::CornerRadius::same(1),
                    MUTED,
                );
            }
            _ => {
                let st = egui::Stroke::new(1.6, MUTED);
                let a = c + egui::vec2(-4.0, 0.5);
                let b = c + egui::vec2(-1.2, 3.3);
                let d = c + egui::vec2(4.2, -3.2);
                p.line_segment([a, b], st);
                p.line_segment([b, d], st);
            }
        }
    }
}

/// A row that changed its place glides there instead of jumping: returns
/// how far from its real place to draw it this frame.
fn glide(ui: &egui::Ui, id: egui::Id, index: usize) -> f32 {
    let now = ui.input(|i| i.time);
    let at = |(_, start, from): (usize, f64, f32)| {
        let t = ((now - start) / FOLD_SECS as f64).clamp(0.0, 1.0) as f32;
        from * (1.0 - ease(t))
    };
    let mut st: (usize, f64, f32) = ui
        .data(|d| d.get_temp(id))
        .unwrap_or((index, now - 1.0, 0.0));
    if st.0 != index {
        let step = ROW_H + ROW_GAP;
        st = (index, now, at(st) + (st.0 as f32 - index as f32) * step);
    }
    ui.data_mut(|d| d.insert_temp(id, st));
    let off = at(st);
    if off.abs() > 0.05 {
        ui.ctx().request_repaint();
    }
    off
}

/// The right-click and ⋯ menu of a task. `del` is the shortcut shown on
/// Delete (the open task answers to the Delete key).
fn task_menu(
    ui: &mut egui::Ui,
    l: &TaskView,
    place: Option<&Place>,
    del: &str,
    actions: &mut Vec<Action>,
) {
    use crate::menus::{danger, item, item_if, sep};
    let t = &l.task;
    let org = |o: Organize| Action::Organize(o);
    if item(ui, "Open", "") {
        actions.push(Action::Select(t.id.clone()));
    }
    if item(ui, "Rename…", "") {
        actions.push(org(Organize::RenameTask(t.id.clone())));
    }
    if let Some(p) = place {
        if item(ui, if p.pinned { "Unpin" } else { "Pin to top" }, "") {
            actions.push(org(Organize::PinTask(t.id.clone(), !p.pinned)));
        }
        if item_if(ui, p.up, "Move up", "") {
            actions.push(org(Organize::MoveTask(t.id.clone(), true)));
        }
        if item_if(ui, p.down, "Move down", "") {
            actions.push(org(Organize::MoveTask(t.id.clone(), false)));
        }
    }
    if item(ui, crate::labels::MOVE_TO, "") {
        actions.push(org(Organize::AskMove(t.id.clone())));
    }
    sep(ui);
    if item(ui, "Copy the whole chat", "") {
        let md =
            crate::extras::markdown(t, &project_name(t.home_project()), &crate::extras::today());
        ui.ctx().copy_text(md);
    }
    if item(ui, "Open its folder", "") {
        actions.push(Action::OpenPath(PathBuf::from(&t.project)));
    }
    if item(ui, if t.archived { "Unarchive" } else { "Archive" }, "") {
        actions.push(Action::Archive(t.id.clone(), !t.archived));
    }
    sep(ui);
    if l.running() && item(ui, "Stop", "") {
        actions.push(Action::Stop(t.id.clone()));
    }
    if danger(ui, "Delete task…", del) {
        actions.push(org(Organize::AskRemoveTask(t.id.clone())));
    }
}

/// One task: a mark, its title, and a short note on the right.
fn task_row(
    ui: &mut egui::Ui,
    l: &TaskView,
    selected: Option<&str>,
    view: View,
    place: Option<Place>,
    as_chat: bool,
    actions: &mut Vec<Action>,
) {
    let org = |o: Organize| Action::Organize(o);
    let t = &l.task;
    let is_sel = selected == Some(&t.id) && view == View::Task;
    let pinned = place.as_ref().is_some_and(|p| p.pinned);
    let state = l.shown_state();
    let needs = state == mimir_core::proto::TaskState::NeedsYou;
    let w = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(
        egui::vec2(w, ROW_H),
        if place.is_some() {
            egui::Sense::click_and_drag()
        } else {
            egui::Sense::click()
        },
    );
    resp.widget_info(|| {
        egui::WidgetInfo::labeled(
            egui::accesskit::Role::Button,
            true,
            crate::labels::task_row(&t.title, &project_name(t.home_project())),
        )
    });
    let row_id = egui::Id::unique(("task-row", &t.id));
    let menu_id = row_id.with("menu");
    let menu_open = ui.data(|d| d.get_temp::<bool>(menu_id).unwrap_or(false));
    let under = ui.rect_contains_pointer(rect) && !egui::DragAndDrop::has_any_payload(ui.ctx());
    let off = match &place {
        Some(p) => glide(ui, row_id.with("glide"), p.index),
        None => 0.0,
    };
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let changed = last_change(t);
    let paint = rect.translate(egui::vec2(0.0, off));
    if ui.is_rect_visible(paint) {
        let p = ui.painter();
        // Colours fade over 100 ms.
        let sel = ui
            .ctx()
            .animate_bool_with_time(row_id.with("sel"), is_sel, 0.1);
        let hov = ui
            .ctx()
            .animate_bool_with_time(row_id.with("hov"), under || menu_open, 0.1);
        row_fill(p, paint, sel, hov);
        mark(
            ui,
            egui::pos2(paint.left() + MARK_X + 8.0, paint.center().y),
            l,
        );
        // The right note: what it wants, or when it last changed.
        let show_dots = under || menu_open;
        let mut right = paint.right() - 8.0;
        if show_dots {
            right -= 22.0;
        } else {
            let (note, color) = match state {
                mimir_core::proto::TaskState::NeedsYou => ("waiting".to_string(), AMBER),
                mimir_core::proto::TaskState::Working
                | mimir_core::proto::TaskState::LiveElsewhere => ("running".to_string(), GREEN),
                mimir_core::proto::TaskState::Done => ("done".to_string(), MUTED),
                _ if t.from_phone.is_some() => (crate::labels::FROM_PHONE_SHORT.to_string(), MUTED),
                _ => (short_ago(changed, now), MUTED),
            };
            let g = elided(ui, &note, 12.5, color, needs, 70.0);
            right -= g.size().x;
            p.galley(
                egui::pos2(right, paint.center().y - g.size().y / 2.0),
                g,
                color,
            );
        }
        let color = if needs || is_sel {
            TEXT
        } else if rank(l, false) >= 3 {
            MUTED
        } else {
            TEXT.gamma_multiply(0.92)
        };
        let title = if pinned {
            format!("★ {}", t.title)
        } else {
            t.title.clone()
        };
        let g = elided(
            ui,
            &title,
            14.0,
            color,
            false,
            right - 8.0 - (paint.left() + TITLE_X),
        );
        p.galley(
            egui::pos2(paint.left() + TITLE_X, paint.center().y - g.size().y / 2.0),
            g,
            color,
        );
    }
    // ⋯ takes the note's place while the pointer rests on the row.
    if under || menu_open {
        let dots = egui::Rect::from_center_size(
            egui::pos2(rect.right() - 8.0 - 11.0, rect.center().y),
            egui::vec2(22.0, 22.0),
        );
        let mut child = ui.new_child(egui::UiBuilder::new().max_rect(dots).layout(
            egui::Layout::centered_and_justified(egui::Direction::LeftToRight),
        ));
        let v = &mut child.visuals_mut().widgets;
        v.inactive.weak_bg_fill = egui::Color32::TRANSPARENT;
        v.inactive.bg_stroke = egui::Stroke::NONE;
        child.spacing_mut().button_padding = egui::vec2(2.0, 0.0);
        let open = child
            .menu_button(RichText::new("⋯").color(MUTED), |ui| {
                crate::theme::menu_fits(ui, |ui| {
                    if as_chat {
                        pc_chat_menu(ui, l, if is_sel { "Del" } else { "" }, actions)
                    } else {
                        task_menu(
                            ui,
                            l,
                            place.as_ref(),
                            if is_sel { "Del" } else { "" },
                            actions,
                        )
                    }
                })
            })
            .inner
            .is_some();
        ui.data_mut(|d| d.insert_temp(menu_id, open));
    }
    // A task just opened or started is brought into view.
    let seen_id = egui::Id::unique("side-last-selected");
    if is_sel {
        let last: Option<String> = ui.data(|d| d.get_temp(seen_id));
        if last.as_deref() != Some(t.id.as_str()) {
            ui.data_mut(|d| d.insert_temp(seen_id, t.id.clone()));
            resp.scroll_to_me(None);
        }
    }
    // No tooltip while a menu is open: it would cover the menu.
    let menu_shown = egui::Popup::is_any_open(ui.ctx());
    let resp = if menu_shown {
        resp
    } else {
        resp.on_hover_ui(|ui| {
            ui.set_max_width(320.0);
            ui.label(RichText::new(&t.title).strong());
            let state = if needs {
                format!("Needs you: {}", t.status_words())
            } else {
                t.status_words().to_string()
            };
            let mut line = format!(
                "{state} · {} · {}",
                project_name(t.home_project()),
                crate::view_windows::ago(changed)
            );
            if t.copy_branch.is_some() {
                line.push_str(" · separate copy");
            }
            if let Some(pc) = t.plan_percent() {
                line.push_str(&format!(" · {pc}% of the plan"));
            }
            ui.label(RichText::new(line).color(MUTED).size(13.0));
        })
    };
    if resp.clicked() {
        actions.push(Action::Select(t.id.clone()));
    }
    if let Some(p) = &place {
        if resp.drag_started() {
            egui::DragAndDrop::set_payload(ui.ctx(), TaskDrag(t.id.clone(), p.project.to_string()));
        }
        // A dragged task of the same project dropped here takes this place.
        if ui.rect_contains_pointer(resp.rect)
            && let Some(d) = egui::DragAndDrop::payload::<TaskDrag>(ui.ctx())
            && d.0 != t.id
            && same(&d.1, p.project)
        {
            let from = p.order.iter().position(|o| o.task.id == d.0);
            drop_line(ui, resp.rect, from.is_some_and(|f| f < p.index));
            if ui.input(|i| i.pointer.any_released()) {
                actions.push(org(Organize::DropTask(d.0.clone(), t.id.clone())));
            }
        }
    }
    crate::menus::open(&resp, |ui| {
        if as_chat {
            pc_chat_menu(ui, l, if is_sel { "Del" } else { "" }, actions)
        } else {
            task_menu(
                ui,
                l,
                place.as_ref(),
                if is_sel { "Del" } else { "" },
                actions,
            )
        }
    });
}

/// The chat menu for a chat started on this PC (stored as a task).
fn pc_chat_menu(ui: &mut egui::Ui, l: &TaskView, del: &str, actions: &mut Vec<Action>) {
    use crate::menus::{danger, item, sep};
    let t = &l.task;
    if item(ui, "Open", "") {
        actions.push(Action::Select(t.id.clone()));
    }
    if item(ui, "Copy the whole chat", "") {
        let md =
            crate::extras::markdown(t, &project_name(t.home_project()), &crate::extras::today());
        ui.ctx().copy_text(md);
    }
    sep(ui);
    if danger(ui, "Delete chat…", del) {
        actions.push(Action::Organize(Organize::AskRemoveTask(t.id.clone())));
    }
}

/// Where a session ran, in plain words.
fn place(project: &std::path::Path) -> String {
    let p = project.display().to_string();
    let home = std::env::var_os("HOME").map(std::path::PathBuf::from);
    if p.contains("/scratch-workspaces/") {
        "Other app".to_string()
    } else if home.as_deref() == Some(project) {
        "Home folder".to_string()
    } else {
        project_name(&p)
    }
}

fn other_row(ui: &mut egui::Ui, o: &OtherSession, actions: &mut Vec<Action>) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let in_use = now - o.session.modified_unix < 90;
    // Never system text as a title: Claude Code's own title instead.
    let mut name = mimir_core::runner::title_from(&o.session.first);
    if mimir_core::task::system_like(&name) {
        name = o
            .title
            .clone()
            .filter(|t| !mimir_core::task::system_like(t))
            .unwrap_or_else(|| "A task".to_string());
    }
    let resp = egui::Frame::new()
        .fill(CARD)
        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
        .inner_margin(egui::Margin::symmetric(10, 6))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.add(egui::Label::new(&name).truncate());
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(format!(
                        "{} · {}",
                        place(&o.project),
                        crate::view_windows::ago(o.session.modified_unix)
                    ))
                    .color(MUTED)
                    .size(13.0),
                );
                if in_use {
                    ui.label(RichText::new("· in use").color(AMBER).size(13.0));
                }
            });
        })
        .response
        .interact(egui::Sense::click())
        .on_hover_text(if in_use {
            format!(
                "{}\nStill running somewhere else. Carrying on here as well starts a \
                 second copy that will not see later messages from there.",
                o.project.display()
            )
        } else {
            format!(
                "{}\nClick to open it here and carry on.",
                o.project.display()
            )
        });
    resp.widget_info(|| {
        egui::WidgetInfo::labeled(
            egui::accesskit::Role::Button,
            true,
            crate::labels::session_row(&name, &place(&o.project)),
        )
    });
    if resp.clicked() {
        actions.push(Action::ImportPast(
            o.project.display().to_string(),
            o.session.clone(),
        ));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_folded_projects_heading_says_who_needs_you() {
        let words =
            |n: Vec<(String, egui::Color32)>| n.into_iter().map(|(t, _)| t).collect::<String>();
        assert_eq!(words(projects_note(3, 1, true)), "3 · 1 needs you");
        assert_eq!(words(projects_note(3, 2, true)), "3 · 2 need you");
        // Open, or nothing waiting: only the count.
        assert_eq!(words(projects_note(3, 1, false)), "3");
        assert_eq!(words(projects_note(3, 0, true)), "3");
        assert_eq!(projects_note(3, 1, true)[2].1, AMBER);
    }

    #[test]
    fn a_project_row_says_its_state() {
        assert_eq!(project_state(false, 0, 0), ("idle".to_string(), MUTED));
        assert_eq!(project_state(false, 0, 1), ("1 running".to_string(), GREEN));
        assert_eq!(project_state(false, 1, 2), ("needs you".to_string(), AMBER));
        assert_eq!(
            project_state(true, 0, 0),
            ("folder is missing".to_string(), AMBER)
        );
    }

    #[test]
    fn chat_times_read_short() {
        let now = 1_700_000_000; // Tue 14 Nov 2023, 22:13 UTC
        assert_eq!(chat_when(now - 30, now), "now");
        assert_eq!(chat_when(now - 3 * 3600, now), "3 h");
        assert_eq!(chat_when(now - 30 * 3600, now), "yesterday");
        assert_eq!(chat_when(now - 3 * 86_400, now), "Sat");
        assert_eq!(chat_when(now - 40 * 86_400, now), "Oct 5");
    }

    #[test]
    fn search_no_lowercase_per_frame() {
        // Build an index for a fake task.
        let mut task = mimir_core::task::Task::new(
            "t1".to_string(),
            "Find the Bug".to_string(),
            String::new(),
            0,
        );
        task.entries.push(mimir_core::task::Entry::You(
            "Look in the Config File".to_string(),
        ));
        task.entries.push(mimir_core::task::Entry::Claude(
            "Check the YAML Settings".to_string(),
        ));
        let idx = TaskIndex::new(&task);
        // The index stores everything lowercased.
        assert!(idx.lowered.contains("find the bug"), "title lowercased");
        assert!(
            idx.lowered.contains("look in the config file"),
            "you entry lowercased"
        );
        assert!(
            idx.lowered.contains("check the yaml settings"),
            "claude entry lowercased"
        );
        // Matching against a pre-lowercased needle does not re-lowercase.
        let needle = "bug";
        assert!(index_matches(needle, &idx), "needle found in index");
        assert!(!index_matches("missing", &idx), "missing needle not found");
        assert!(index_matches("", &idx), "empty needle matches all");
    }
}
