//! What the top bar used to hold, now in the left sidebar so tasks get the
//! full height: the "needs you / working" pill at the top, and at the bottom
//! updates, setup, Settings and the plan's weekly and 5-hour use.

use crate::app::{Action, View};
use crate::app_view::{AppPanel, UpdateState};
use crate::theme::{self, JADE, MUTED, ORANGE};
use eframe::egui::{self, RichText};
use mimir_core::client::Station;

/// What the sidebar's pill and foot show.
pub(crate) struct Foot<'a> {
    pub r: Option<&'a Station>,
    pub panel: &'a AppPanel,
    pub view: View,
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

impl Foot<'_> {
    fn activity_slot(&self, ui: &mut egui::Ui, w: f32) {
        ui.allocate_exact_size(egui::vec2(w, 22.0), egui::Sense::hover());
    }

    /// Lays the foot out at the bottom of `ui`, from the bottom up, and
    /// returns the room left above it for the side list. Measured in the
    /// same frame, so nothing creeps: as a bottom panel filled from the
    /// bottom up, the foot kept 2 points more on every frame until it hid
    /// the side list from the bottom to the top.
    pub(crate) fn place(
        &self,
        ui: &mut egui::Ui,
        open_folder: bool,
        actions: &mut Vec<Action>,
    ) -> egui::Rect {
        let full = ui.available_rect_before_wrap();
        let mut foot = ui.new_child(
            egui::UiBuilder::new()
                .id_salt("side-foot")
                .max_rect(full)
                .layout(egui::Layout::bottom_up(egui::Align::Min)),
        );
        foot.add_space(8.0);
        self.foot(&mut foot, open_folder, actions);
        let top = (foot.min_rect().top() - 8.0).max(full.top());
        egui::Rect::from_min_max(full.min, egui::pos2(full.right(), top))
    }

    /// The bottom of the sidebar: a waiting update and setup (only when they
    /// apply), Open a project folder, then Settings with the weekly use.
    pub(crate) fn foot(&self, ui: &mut egui::Ui, open_folder: bool, actions: &mut Vec<Action>) {
        // Built from the bottom up: Settings keeps its place at the very
        // bottom, and a row that comes or goes above it moves nothing below.
        ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
            let w = ui.available_width();
            // Settings is the very bottom row: its name on the left, what
            // it holds on the right.
            let open_settings = self.view == View::Settings;
            let (rect, resp) =
                ui.allocate_exact_size(egui::vec2(w, 30.0), egui::Sense::click());
            let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
            resp.widget_info(|| {
                egui::WidgetInfo::labeled(egui::accesskit::Role::Button, true, "Settings")
            });
            if ui.is_rect_visible(rect) {
                let fill = if open_settings {
                    Some(egui::Color32::from_rgb(0x1F, 0x23, 0x2B))
                } else if resp.hovered() {
                    Some(egui::Color32::from_rgb(0x1B, 0x1F, 0x26))
                } else {
                    None
                };
                let p = ui.painter();
                p.hline(
                    rect.x_range(),
                    rect.top() - 3.0,
                    egui::Stroke::new(1.0, egui::Color32::from_rgb(0x2B, 0x2F, 0x37)),
                );
                if let Some(fill) = fill {
                    p.rect_filled(rect, egui::CornerRadius::same(8), fill);
                }
                let dim = ui.painter().layout_no_wrap(
                    "voice, models, privacy".into(),
                    egui::FontId::proportional(12.5),
                    MUTED,
                );
                let name = ui.painter().layout_no_wrap(
                    "Settings".into(),
                    egui::FontId::proportional(14.5),
                    theme::TEXT,
                );
                let p = ui.painter();
                p.galley(
                    egui::pos2(rect.left() + 8.0, rect.center().y - name.size().y / 2.0),
                    name,
                    theme::TEXT,
                );
                if rect.width() > 230.0 {
                    p.galley(
                        egui::pos2(
                            rect.right() - 8.0 - dim.size().x,
                            rect.center().y - dim.size().y / 2.0,
                        ),
                        dim,
                        MUTED,
                    );
                }
            }
            if resp.clicked() {
                actions.push(if open_settings {
                    Action::CloseSettings
                } else {
                    Action::OpenSettings
                });
            }
            ui.add_space(1.0);
            // The plan's weekly use, above Settings.
            if let Some(use_) = self.r.and_then(|r| r.weekly.clone()) {
                let pct = (use_.used * 100.0).round();
                let color = if pct >= 80.0 { ORANGE } else { MUTED };
                if ui
                    .add(
                        egui::Label::new(
                            RichText::new(crate::labels::weekly_use(pct))
                                .color(color)
                                .size(13.0),
                        )
                        .truncate()
                        .sense(egui::Sense::click()),
                    )
                    .on_hover_cursor(egui::CursorIcon::PointingHand)
                    .on_hover_text(format!(
                        "{pct:.0}% of weekly limit · resets {}\nYour Anthropic plan's 7-day use, as reported{}.\nClick: what happens when it runs out (Settings › Models › Who answers)",
                        resets_in(use_.resets_at),
                        updated_line(self.r.and_then(|r| r.usage_at))
                    ))
                    .clicked()
                {
                    actions.push(Action::OpenModels);
                }
            }
            // The 5-hour window, while it is current.
            if let Some(use_) = self
                .r
                .and_then(|r| r.five_hour.clone())
                .filter(|u| u.resets_at > now())
            {
                let pct = (use_.used * 100.0).round();
                let color = if pct >= 80.0 { ORANGE } else { MUTED };
                let text = format!(
                    "{pct:.0}% of 5-hour limit · resets {}",
                    resets_in(use_.resets_at)
                );
                ui.add(egui::Label::new(RichText::new(&text).color(color).size(12.5)).truncate())
                    .on_hover_text(format!(
                        "{text}\nYour plan's 5-hour use, as reported{}",
                        crate::view_top::updated_line(self.r.and_then(|r| r.usage_at))
                    ));
            }
            if open_folder {
                ui.add_space(6.0);
                if ui
                    .add_sized([w, 32.0], theme::quiet("Open a project folder"))
                    .clicked()
                {
                    actions.push(Action::PickFolder);
                }
            }
            self.update_row(ui, w);
            ui.add_space(6.0);
            // Transient status lives here, in slots that keep their height
            // whether or not they show anything, so no button or list row
            // ever moves when one comes or goes.
            self.activity_slot(ui, w);
        });
    }

    /// The update button, its progress or its "waits" note. The row eases
    /// open and shut, so nothing below it jumps.
    fn update_row(&self, ui: &mut egui::Ui, w: f32) {
        const ROW: f32 = 38.0;
        let update = self.panel.update_state();
        let shown = matches!(
            update,
            UpdateState::Available(_)
                | UpdateState::Opened(_)
                | UpdateState::Installing(_)
                | UpdateState::Updating(..)
                | UpdateState::RestartNeeded(_)
                | UpdateState::Restarting(_)
        );
        let open = ui
            .ctx()
            .animate_bool_with_time(egui::Id::unique("side-update-row"), shown, 0.2);
        if open <= 0.0 {
            return;
        }
        let (rect, _) = ui.allocate_exact_size(egui::vec2(w, ROW * open), egui::Sense::hover());
        let mut row = ui.new_child(
            egui::UiBuilder::new()
                .max_rect(egui::Rect::from_min_size(
                    rect.min,
                    egui::vec2(w, ROW - 6.0),
                ))
                .layout(egui::Layout::left_to_right(egui::Align::Center)),
        );
        row.set_clip_rect(rect.intersect(ui.clip_rect()));
        let ui = &mut row;
        match update {
            UpdateState::Available(v) => {
                if ui
                    .add_sized([w, 32.0], theme::primary(&crate::labels::update_to(&v)))
                    .clicked()
                {
                    self.panel.start_update(v);
                }
            }
            UpdateState::Opened(v) => {
                let text = crate::labels::update_opened(&v);
                ui.add(egui::Label::new(RichText::new(&text).color(MUTED).size(13.0)).truncate())
                    .on_hover_text(text);
            }
            UpdateState::Installing(v) => {
                ui.spinner();
                ui.add(
                    egui::Label::new(
                        RichText::new(format!("Updating to {v}…"))
                            .color(MUTED)
                            .size(13.0),
                    )
                    .truncate(),
                );
            }
            UpdateState::Updating(v, p) => {
                ui.spinner();
                ui.add(
                    egui::Label::new(
                        RichText::new(crate::labels::updating(&v, p))
                            .color(MUTED)
                            .size(13.0),
                    )
                    .truncate(),
                );
            }
            UpdateState::RestartNeeded(_)
                if ui
                    .add_sized([w, 32.0], theme::primary(crate::labels::RESTART))
                    .clicked() =>
            {
                let ctx = ui.ctx().clone();
                self.panel.restart(&ctx);
            }
            UpdateState::Restarting(words) => {
                ui.spinner();
                let words = if words.is_empty() {
                    "Restarting Mímir…".to_string()
                } else {
                    words
                };
                ui.add(egui::Label::new(RichText::new(words).color(MUTED).size(13.0)).truncate());
            }
            _ => {}
        }
    }
}

fn resets_in(unix: i64) -> String {
    let s = (unix - now()).max(0);
    match s {
        0..=3599 => "within the hour".into(),
        3600..=86399 => format!("in {} h", s / 3600),
        _ => format!("in {} days", (s + 43200) / 86400),
    }
}

/// Permission requests from Claude sessions Mímir doesn't run (a terminal,
/// claude.ai), under the pill: what it wants, which project and session,
/// and the same four answers the phone has. The card fades in.
pub(crate) fn outside_asks(ui: &mut egui::Ui, r: &Station, actions: &mut Vec<Action>) {
    use mimir_core::proto::{ItemAnswer, NeedsKind};
    let items = r
        .needs
        .iter()
        .filter(|i| i.kind == NeedsKind::Permission && i.task.is_none());
    for item in items {
        // Fades in from when it was first seen; with animations removed
        // (no animation time) it is simply there.
        let shown_at = egui::Id::unique(("outside-ask-seen", &item.ask_id));
        let t = ui.input(|i| i.time);
        let first = ui.data_mut(|d| *d.get_temp_mut_or_insert_with(shown_at, || t));
        let dur = ui.style().animation_time.max(0.0) as f64;
        let opacity = if dur <= 0.0 {
            1.0
        } else {
            ((t - first) / dur).clamp(0.0, 1.0) as f32
        };
        if opacity < 1.0 {
            ui.ctx().request_repaint();
        }
        let project = std::path::Path::new(&item.project)
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| item.project.clone());
        ui.scope(|ui| {
            ui.set_opacity(opacity);
            egui::Frame::new()
                .fill(theme::ORANGE_DIM)
                .stroke(egui::Stroke::new(1.0, ORANGE))
                .corner_radius(egui::CornerRadius::same(theme::RADIUS))
                .inner_margin(egui::Margin::same(10))
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.label(
                        RichText::new(format!("Allow? · {project}"))
                            .color(ORANGE)
                            .size(12.5)
                            .strong(),
                    );
                    let what = item.text.lines().next().unwrap_or_default();
                    ui.add(egui::Label::new(RichText::new(what).size(13.5)).wrap());
                    ui.label(RichText::new(&item.title).color(MUTED).size(12.0));
                    let mut answer = |a: ItemAnswer| {
                        actions.push(Action::AnswerItem(item.ask_id.clone(), a));
                    };
                    ui.horizontal_wrapped(|ui| {
                        if ui.add(theme::primary("Allow")).clicked() {
                            answer(ItemAnswer::Allow);
                        }
                        if ui.add(theme::quiet("Don't allow")).clicked() {
                            answer(ItemAnswer::DontAllow);
                        }
                        if ui
                            .add(theme::quiet("Always allow"))
                            .on_hover_text("Allow, and add a rule to this project's own settings")
                            .clicked()
                        {
                            answer(ItemAnswer::AlwaysProject);
                        }
                        if ui
                            .add(theme::quiet("Answer on the PC"))
                            .on_hover_text("It asks in its own window as usual")
                            .clicked()
                        {
                            answer(ItemAnswer::KeepForLater);
                        }
                    });
                });
        });
        ui.add_space(6.0);
    }
}

/// "\nupdated N min ago" for a hover, or nothing before the first update.
pub(crate) fn updated_line(at: Option<i64>) -> String {
    mimir_core::usage::updated_text(at, now())
        .map(|t| format!("\n{t}"))
        .unwrap_or_default()
}

/// What the top of the middle shows.
pub(crate) struct MidTop<'a> {
    /// "<Project> › <task title>", on a task.
    pub crumb: Option<String>,
    /// Whether the answers go to Claude (else they stay on this PC).
    pub paid: bool,
    /// The side panel's state, when this page has one to toggle.
    pub panel: Option<bool>,
    /// The shot name for the pill's menu (debug pictures).
    pub menu_name: &'a str,
}

/// The top of the middle: a dim breadcrumb on the left, the pill that says
/// who answers in the centre (its menu holds the page's actions), and the
/// side panel's toggle on the right.
pub(crate) fn mid_top(
    ui: &mut egui::Ui,
    top: &MidTop,
    actions: &mut Vec<Action>,
    menu: impl FnOnce(&mut egui::Ui, &mut Vec<Action>),
) -> Option<egui::Response> {
    const H: f32 = 32.0;
    let (rect, _) =
        ui.allocate_exact_size(egui::vec2(ui.available_width(), H), egui::Sense::hover());
    // Right: the panel toggle.
    let mut right = ui.new_child(
        egui::UiBuilder::new()
            .max_rect(rect)
            .layout(egui::Layout::right_to_left(egui::Align::Center)),
    );
    let mut toggle_w = 0.0;
    if let Some(shown) = top.panel {
        let words = if shown {
            crate::labels::HIDE_PANEL
        } else {
            crate::labels::SHOW_PANEL
        };
        let r = right
            .add(theme::pill_button(
                RichText::new(words).color(MUTED).size(12.5).into(),
            ))
            .on_hover_text("Show or hide the side panel (Ctrl+B)");
        toggle_w = r.rect.width();
        if r.clicked() {
            actions.push(Action::ToggleDetails(shown));
        }
    }
    // Left: where this task lives.
    let mut crumb_resp = None;
    if let Some(crumb) = &top.crumb {
        let room = (rect.width() / 2.0 - 140.0).max(0.0);
        if room > 40.0 {
            let mut left = ui.new_child(
                egui::UiBuilder::new()
                    .max_rect(egui::Rect::from_min_size(rect.min, egui::vec2(room, H)))
                    .layout(egui::Layout::left_to_right(egui::Align::Center)),
            );
            crumb_resp = Some(
                left.add(
                    egui::Label::new(RichText::new(crumb).color(theme::DIM).size(12.5))
                        .truncate()
                        .sense(egui::Sense::click()),
                )
                .on_hover_text(format!("{crumb}\nDouble-click to rename")),
            );
        }
    }
    // Centre: who answers.
    let mut mid = ui.new_child(
        egui::UiBuilder::new()
            .max_rect(egui::Rect::from_min_max(
                egui::pos2(rect.left() + toggle_w * 0.0, rect.top()),
                rect.max,
            ))
            .layout(egui::Layout::top_down(egui::Align::Center)),
    );
    let (dot, words) = if top.paid {
        (JADE, crate::labels::PILL_CLAUDE)
    } else {
        (JADE, crate::labels::PILL_PC)
    };
    let mut job = egui::text::LayoutJob::default();
    let font = egui::FontId::proportional(13.5);
    job.append("● ", 0.0, egui::TextFormat::simple(font.clone(), dot));
    job.append(
        words,
        0.0,
        egui::TextFormat::simple(font.clone(), theme::TEXT),
    );
    job.append(" ▾", 0.0, egui::TextFormat::simple(font, MUTED));
    let (resp, _) = egui::containers::menu::MenuButton::from_button(theme::pill_button(job.into()))
        .ui(&mut mid, |ui| {
            theme::menu_fits(ui, |ui| {
                ui.set_min_width(250.0);
                ui.label(
                    RichText::new(if top.paid {
                        "Answers go to Anthropic (your Anthropic plan)"
                    } else {
                        "Answers are made on this PC"
                    })
                    .color(MUTED)
                    .size(12.5),
                );
                if ui.button("Choose who answers…").clicked() {
                    actions.push(Action::OpenModels);
                    ui.close();
                }
                ui.separator();
                menu(ui, actions);
            })
        });
    resp.widget_info(|| {
        egui::WidgetInfo::labeled(
            egui::accesskit::Role::Button,
            true,
            crate::labels::PILL_MENU,
        )
    });
    crate::shot::open_menu(ui.ctx(), top.menu_name, &resp);
    crumb_resp
}
