//! The middle, for a general chat: the same chats the phone shows, with the
//! same look as a task (the owner's messages on the right, the answers as
//! text), a small mark under each answer saying where it ran, and a box to
//! reply in. The chats themselves live on the PC; this view only reads the
//! window's mirror of them and asks the PC to answer.

use crate::app::{Action, App};
use crate::theme::{self, CARD, JADE, JADE_DIM, LINE, MUTED, ORANGE, RED};
use eframe::egui::{self, Align, Color32, Key, Layout, RichText, ScrollArea, TextEdit};
use egui_commonmark::CommonMarkViewer;
use mimir_core::proto::{ChatRecord, ChatTurn, Ran};

/// How the PC's truth ends the line for chats on a paid model
/// ("… (your plan)"); a free model's line never does.
const PAID_LINE_END: &str = "(your plan)";

/// The most characters of a chat's first message that name it.
const TITLE_CHARS: usize = 44;

/// What a chat is called: the name the owner gave it, else the start of its
/// first message (the phone names it the same way).
pub(crate) fn chat_title(c: &ChatRecord) -> String {
    if let Some(n) = c.name.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
        return n.to_string();
    }
    let first = c
        .turns
        .iter()
        .find(|t| t.user)
        .map(|t| t.text.split_whitespace().collect::<Vec<_>>().join(" "))
        .unwrap_or_default();
    if first.is_empty() {
        return crate::labels::NEW_CHAT.to_string();
    }
    if first.chars().count() <= TITLE_CHARS {
        first
    } else {
        let cut: String = first.chars().take(TITLE_CHARS).collect();
        format!("{}…", cut.trim_end())
    }
}

/// Where an answer ran, in the words under it.
pub(crate) fn ran_words(t: &ChatTurn) -> String {
    let host = std::fs::read_to_string("/etc/hostname").unwrap_or_default();
    without_hostname(&ran_words_raw(t), host.trim())
}

/// The words with the system's own host name replaced by "This PC".
fn without_hostname(words: &str, host: &str) -> String {
    if host.len() < 3 {
        return words.to_string();
    }
    words.replace(host, "This PC")
}

fn ran_words_raw(t: &ChatTurn) -> String {
    match (t.ran, t.model_words.as_deref().filter(|w| !w.is_empty())) {
        (Some(Ran::Phone), Some(w)) => w.to_string(),
        (Some(Ran::Phone), None) => crate::labels::THIS_PHONE.to_string(),
        (Some(Ran::Paid), Some(w)) => w.to_string(),
        (Some(Ran::Paid), None) => "Online".to_string(),
        (_, Some(w)) => w.to_string(),
        (_, None) => crate::labels::YOUR_PC.to_string(),
    }
}

/// The small picture before "Your PC" or "This phone": a monitor or a phone,
/// drawn so nothing depends on a font having it.
fn ran_glyph(ui: &mut egui::Ui, ran: Option<Ran>) {
    let (rect, _) = ui.allocate_exact_size(egui::vec2(14.0, 14.0), egui::Sense::hover());
    let p = ui.painter();
    let st = egui::Stroke::new(1.2, MUTED);
    let c = rect.center();
    match ran {
        Some(Ran::Phone) => {
            p.rect_stroke(
                egui::Rect::from_center_size(c, egui::vec2(7.0, 12.0)),
                egui::CornerRadius::same(2),
                st,
                egui::StrokeKind::Inside,
            );
        }
        _ => {
            p.rect_stroke(
                egui::Rect::from_center_size(c - egui::vec2(0.0, 1.0), egui::vec2(12.0, 8.0)),
                egui::CornerRadius::same(1),
                st,
                egui::StrokeKind::Inside,
            );
            p.line_segment([c + egui::vec2(-3.0, 5.0), c + egui::vec2(3.0, 5.0)], st);
        }
    }
}

/// The widest the messages and the box get, at any window width.
pub(crate) const LANE: f32 = crate::labels::LANE_WIDTH;

/// Runs `add` in the centred column.
pub(crate) fn in_lane<R>(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui) -> R) -> R {
    let side = ((ui.available_width() - LANE) / 2.0).max(0.0);
    ui.horizontal_top(|ui| {
        ui.add_space(side);
        ui.vertical(|ui| {
            ui.set_max_width(LANE.min(ui.available_width()));
            add(ui)
        })
        .inner
    })
    .inner
}

/// The hour on the clock, read at most once a minute.
pub(crate) fn local_hour(ctx: &egui::Context) -> u32 {
    let id = egui::Id::unique("clock-hour");
    let now = ctx.input(|i| i.time);
    if let Some((at, h)) = ctx.data(|d| d.get_temp::<(f64, u32)>(id))
        && now - at < 60.0
    {
        return h;
    }
    let h = std::process::Command::new("date")
        .arg("+%H")
        .output()
        .ok()
        .and_then(|o| {
            String::from_utf8_lossy(&o.stdout)
                .trim()
                .parse::<u32>()
                .ok()
        })
        .unwrap_or(12);
    ctx.data_mut(|d| d.insert_temp(id, (now, h)));
    h
}

/// The calm greeting, centred in what is left of the page.
pub(crate) fn greeting_block(ui: &mut egui::Ui) {
    let hour = local_hour(ui.ctx());
    ui.add_space((ui.available_height() * 0.30).max(24.0));
    ui.vertical_centered(|ui| {
        ui.label(theme::title(crate::labels::greeting(hour), 33.0));
        ui.add_space(6.0);
        ui.label(
            RichText::new(crate::labels::ASK_HELP)
                .color(theme::DIM)
                .size(16.0),
        );
    });
}

/// One starter: a title and a dim line under it. True when clicked.
pub(crate) fn starter_chip(ui: &mut egui::Ui, title: &str, note: &str) -> bool {
    let mut job = egui::text::LayoutJob::default();
    job.append(
        title,
        0.0,
        egui::TextFormat::simple(egui::FontId::proportional(13.5), theme::TEXT),
    );
    job.append(
        &format!("\n{note}"),
        0.0,
        egui::TextFormat::simple(egui::FontId::proportional(12.0), theme::DIM),
    );
    ui.add(
        egui::Button::new(job)
            .fill(theme::BOX_FILL)
            .stroke(egui::Stroke::new(1.0, LINE))
            .corner_radius(egui::CornerRadius::same(12))
            .min_size(egui::vec2(0.0, 44.0)),
    )
    .on_hover_cursor(egui::CursorIcon::PointingHand)
    .clicked()
}

/// The small dim line of shortcuts under the box.
pub(crate) fn hint_line(ui: &mut egui::Ui, text: &str) {
    ui.add_space(4.0);
    ui.vertical_centered(|ui| {
        ui.add(egui::Label::new(RichText::new(text).color(theme::DIM).size(11.5)).truncate());
    });
}

/// The area at the bottom of a page: the box, its cards and the hint. It is
/// always as tall as what is in it: the height is measured from the contents
/// every frame and kept under our own key, never taken from a stored panel
/// size that could be stale, and a change asks for the next frame at once.
/// So the box and the hint are never cut off by the window's edge.
pub(crate) fn bottom_area<R>(
    ui: &mut egui::Ui,
    name: &'static str,
    add: impl FnOnce(&mut egui::Ui) -> R,
) -> R {
    const TOP: f32 = 8.0;
    let key = egui::Id::unique(("bottom-area-height", name));
    let known: Option<f32> = ui.ctx().data(|d| d.get_temp(key));
    let mut panel = egui::Panel::bottom(name).show_separator_line(false).frame(
        egui::Frame::new().inner_margin(egui::Margin {
            left: 0,
            right: 0,
            top: TOP as i8,
            bottom: 0,
        }),
    );
    if let Some(h) = known {
        panel = panel.exact_size(h);
    }
    let mut natural = 0.0;
    let out = panel
        .show(ui, |ui| {
            let r = add(ui);
            natural = ui.min_rect().height() + TOP;
            r
        })
        .inner;
    if known.is_none_or(|h| (h - natural).abs() > 0.5) {
        ui.ctx().data_mut(|d| d.insert_temp(key, natural));
        // Draw this frame again now: a window nobody touches gets no next
        // frame, and the box would stay cut off until it did.
        ui.ctx().request_discard("bottom area height");
        ui.ctx().request_repaint();
    }
    out
}

/// How the box's send button behaves.
#[derive(Clone, Copy, PartialEq)]
pub(crate) enum SendMode {
    /// Send.
    Send,
    /// An answer is being written: the button stops it.
    Stop,
    /// Claude is working: send now, or wait until it is done.
    Wait,
}

/// What the message box reports back.
pub(crate) struct ComposerOut {
    pub edit: egui::Response,
    pub send: bool,
    pub stop: bool,
    pub later: bool,
}

/// The message box, the same on every page: a rounded card with the text
/// on top; under it a plus (attach), the page's chips, then a microphone
/// and a round send button at the right.
#[allow(clippy::too_many_arguments)]
pub(crate) fn composer_card(
    ui: &mut egui::Ui,
    salt: &str,
    hint: &str,
    draft: &mut String,
    mode: SendMode,
    send_name: &str,
    chips: impl FnOnce(&mut egui::Ui, &mut Vec<Action>),
    actions: &mut Vec<Action>,
) -> ComposerOut {
    let mut out = None;
    egui::Frame::new()
        .fill(theme::BOX_FILL)
        .stroke(egui::Stroke::new(1.0, LINE))
        .corner_radius(egui::CornerRadius::same(16))
        .inner_margin(egui::Margin::symmetric(14, 10))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            // A Cut or Paste chosen from the box's menu is done now.
            crate::menus::before_edit(ui.ctx(), salt, draft);
            // A long message scrolls inside the box: the box never grows
            // taller than a third of the window, so Send stays in view.
            let draft_max = (ui.ctx().content_rect().height() * 0.33).clamp(60.0, 240.0);
            let edit = ScrollArea::vertical()
                .id_salt((salt, "draft"))
                .max_height(draft_max)
                .auto_shrink([false, true])
                .show(ui, |ui| {
                    ui.add(
                        TextEdit::multiline(draft)
                            .id_salt(salt)
                            .hint_text(hint)
                            .frame(egui::Frame::NONE)
                            .desired_width(ui.available_width())
                            .desired_rows(2)
                            .return_key(Some(egui::KeyboardShortcut::new(
                                egui::Modifiers::SHIFT,
                                Key::Enter,
                            ))),
                    )
                })
                .inner;
            crate::menus::edit_menu(&edit, salt);
            crate::menus::note_empty_box(&edit, draft);
            ui.add_space(4.0);
            let mut send = false;
            let mut stop = false;
            let mut later = false;
            // The chips wrap onto a second line in a narrow window; the
            // microphone and send stay at the top right.
            let right_w = if mode == SendMode::Wait { 170.0 } else { 80.0 };
            let left_w = (ui.available_width() - right_w - 8.0).max(60.0);
            ui.horizontal_top(|ui| {
                ui.spacing_mut().item_spacing.x = 6.0;
                ui.allocate_ui(egui::vec2(left_w, 0.0), |ui| {
                    ui.horizontal_wrapped(|ui| {
                        ui.spacing_mut().item_spacing.x = 6.0;
                        if theme::icon_button(ui, theme::Icon::Plus, 28.0, crate::labels::ATTACH_FILES, None, true)
                            .on_hover_text(
                                "Add pictures or files (or drop them on the window, or paste a picture with Ctrl+V)",
                            )
                            .clicked()
                        {
                            actions.push(Action::PickAttachments);
                        }
                        chips(ui, actions);
                    });
                });
                ui.with_layout(Layout::right_to_left(Align::Min), |ui| {
                    let blue = Color32::from_rgb(0x2F, 0x5B, 0xC4);
                    match mode {
                        SendMode::Stop => {
                            stop = theme::icon_button(
                                ui,
                                theme::Icon::Stop,
                                32.0,
                                crate::labels::STOP,
                                Some(blue),
                                true,
                            )
                            .on_hover_text("Stop the answer")
                            .clicked();
                        }
                        SendMode::Send | SendMode::Wait => {
                            send = theme::icon_button(
                                ui,
                                theme::Icon::Send,
                                32.0,
                                if mode == SendMode::Wait {
                                    crate::labels::SEND_NOW
                                } else {
                                    send_name
                                },
                                Some(blue),
                                !draft.trim().is_empty(),
                            )
                            .on_hover_text(if mode == SendMode::Wait {
                                "Mímir sees it while it works (Enter)"
                            } else {
                                "Send (Enter)"
                            })
                            .clicked();
                        }
                    }
                    mic_icon(ui, draft, actions);
                    if mode == SendMode::Wait
                        && ui
                            .add(egui::Button::new(RichText::new("When done").color(MUTED).size(12.5)).frame(false))
                            .on_hover_text(
                                "Waits here and is sent by itself when Mímir finishes what it is doing (Ctrl+Enter)",
                            )
                            .clicked()
                    {
                        later = true;
                    }
                });
            });
            out = Some(ComposerOut {
                edit,
                send,
                stop,
                later,
            });
        });
    out.expect("the box was drawn")
}

/// The microphone: dictation as before (Talk), drawn as a capsule on a
/// stand. Red while listening.
fn mic_icon(ui: &mut egui::Ui, draft: &mut String, actions: &mut Vec<Action>) {
    use crate::places::ListenStatus;
    if let Some(text) = crate::places::take_heard() {
        if !draft.is_empty() && !draft.ends_with(char::is_whitespace) {
            draft.push(' ');
        }
        draft.push_str(&text);
        actions.push(Action::FocusComposer);
    }
    let status = crate::places::listen_status();
    if status != crate::places::ListenStatus::Idle {
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_millis(300));
    }
    match &status {
        ListenStatus::Listening => {
            if theme::icon_button(
                ui,
                theme::Icon::Mic,
                30.0,
                crate::labels::DICTATE,
                Some(RED),
                true,
            )
            .on_hover_text(
                "Listening: click to stop and write it down (Ctrl+M). Esc throws it away.",
            )
            .clicked()
            {
                actions.push(Action::Mic);
            }
        }
        ListenStatus::Writing => {
            ui.spinner();
        }
        ListenStatus::Downloading(s) => {
            ui.spinner();
            ui.label(RichText::new(s).color(MUTED).size(12.0));
        }
        ListenStatus::Idle | ListenStatus::Failed(_) => {
            if theme::icon_button(
                ui,
                theme::Icon::Mic,
                30.0,
                crate::labels::DICTATE,
                None,
                true,
            )
            .on_hover_text(
                "Talk / voice typing (Ctrl+M): say it, click again, and your words \
                     appear in the message box for you to check. Your voice is turned into \
                     text on this PC; nothing is sent away.",
            )
            .clicked()
            {
                actions.push(Action::Mic);
            }
            if let ListenStatus::Failed(e) = &status {
                ui.label(RichText::new(e).color(ORANGE).size(12.0));
            }
        }
    }
}

/// A quiet text button under an answer.
/// The small line under an answer that used lookups.
pub(crate) const SOURCES_TAG: &str = "Sources:";

/// The sites an answer used, once each, with the page to open (a blocked search's results page
/// is not a source). Only web addresses.
pub(crate) fn source_links(t: &ChatTurn) -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = vec![];
    for s in &t.sources {
        let web = s.url.starts_with("https://") || s.url.starts_with("http://");
        if !s.results && web && !s.site.is_empty() && !out.iter().any(|(n, _)| *n == s.site) {
            out.push((s.site.clone(), s.url.clone()));
        }
    }
    out
}

pub(crate) fn quiet_link(ui: &mut egui::Ui, text: &str, color: Color32, hover: &str) -> bool {
    ui.add(
        egui::Button::new(RichText::new(text).size(12.5).color(color))
            .frame(false)
            .small(),
    )
    .on_hover_cursor(egui::CursorIcon::PointingHand)
    .on_hover_text(hover)
    .clicked()
}

impl App {
    /// The chat that is open, and a box to reply in (or, with none open,
    /// the calm greeting and a box to start one).
    pub(crate) fn chat_view(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let Ok(r) = &self.station else { return };
        let open = self.chat_open.clone();
        // Borrowed, not copied: a long chat is not cloned on every frame.
        let chat = open.as_ref().and_then(|id| r.chats.get(id));
        let busy = open.as_ref().is_some_and(|id| r.chat_busy(id));
        let no_note = mimir_core::client::ChatNote::default();
        let note = open
            .as_ref()
            .and_then(|id| r.chat_notes.get(id))
            .unwrap_or(&no_note);
        let partial = open.as_ref().and_then(|id| r.partials.get(id));
        let engine_line = r
            .truth
            .as_ref()
            .map(|t| t.engine.chats_model_words.as_str())
            .filter(|w| !w.is_empty());
        // Chats are paid only when the PC says they run on Claude: the
        // truth's `paid_on` is also set by paid project work.
        let paid = engine_line.is_some_and(|w| w.ends_with(PAID_LINE_END));
        // Nothing said yet; a chat just sent but not yet in the mirror is not empty.
        let empty = match &open {
            None => true,
            Some(_) => chat.is_some_and(|c| c.turns.is_empty()) && partial.is_none() && !busy,
        };

        let focus = std::mem::take(&mut self.focus_composer);
        let key = open.clone().unwrap_or_default();
        let mut draft = self.chat_drafts.remove(&key).unwrap_or_default();
        let pics = self.chat_pics.remove(&key).unwrap_or_default();
        let pics_note = self.pics_note.clone();
        let shrinking = self.pics_inflight;
        // Top: who answers, and what the old ⋯ held.
        let settings = &self.settings;
        let draft_ref = &mut draft;
        let panel = Some(self.details_shown(ui.ctx()));
        let confirm = &mut self.confirm_delete_chat;
        crate::view_top::mid_top(
            ui,
            &crate::view_top::MidTop {
                crumb: None,
                paid,
                panel,
                menu_name: "chat-menu",
            },
            actions,
            |ui, actions| {
                crate::extras::prompts_menu(
                    ui,
                    settings,
                    &crate::places::chats_dir().display().to_string(),
                    draft_ref,
                    actions,
                );
                ui.separator();
                if let Some(id) = &open {
                    if ui.button("Rename this chat…").clicked() {
                        actions.push(Action::AskRenameChat(id.clone()));
                        ui.close();
                    }
                    if ui
                        .button(RichText::new("Delete this chat…").color(RED))
                        .clicked()
                    {
                        *confirm = Some(id.clone());
                        ui.close();
                    }
                } else {
                    ui.label(RichText::new("A new chat").color(MUTED).size(12.5));
                }
            },
        );
        if busy {
            ui.label(
                RichText::new(crate::labels::ANSWERING)
                    .color(JADE)
                    .size(12.5),
            );
        }

        // The message box at the bottom.
        let mut picked: Vec<Action> = vec![];
        let waiting_task = r
            .tasks
            .iter()
            .find(|l| l.task.question_waiting())
            .map(|l| (l.task.id.clone(), l.task.home_project().to_string()));
        let has_projects = !self.settings.projects.is_empty();
        let mut starter_text: Option<&'static str> = None;
        bottom_area(ui, "chat-composer", |ui| {
            in_lane(ui, |ui| {
                // Starters, while nothing has been said.
                if empty {
                    ui.horizontal_wrapped(|ui| {
                        if let Some((task, project)) = &waiting_task {
                            let name = crate::places::display_name(&self.settings, project);
                            if starter_chip(
                                ui,
                                &format!("Answer {name}'s question"),
                                "Waiting for you",
                            ) {
                                actions.push(Action::Select(task.clone()));
                            }
                        }
                        if has_projects {
                            if starter_chip(ui, "Start a task", "In one of your projects") {
                                actions.push(Action::NewTask);
                            }
                        } else if starter_chip(ui, "What's the weather?", "Ask anything") {
                            starter_text = Some("What's the weather?");
                        }
                    });
                    ui.add_space(8.0);
                }
                // PCW-02, PCW-03: Whisper stays reachable from the box.
                if self.new_incognito {
                    ui.horizontal_wrapped(|ui| {
                        crate::theme::whisper_eye(ui, 18.0);
                        ui.label(
                            RichText::new(crate::labels::WHISPER_NOTE)
                                .color(JADE_DIM)
                                .size(12.0),
                        );
                    });
                }
                let mode = if busy { SendMode::Stop } else { SendMode::Send };
                let new_effort = self.new_effort.clone();
                let model_words = engine_line
                    .and_then(|w| w.split(" · ").next())
                    .unwrap_or("Auto-pick")
                    .to_string();
                let model_words = if self.settings.chats_paid() {
                    // Claude answers: the model it is set to ("Auto-pick" when Claude Code chooses).
                    mimir_core::talk::claude_choice_name(
                        self.settings.chats_model_alias().unwrap_or("auto"),
                    )
                    .to_string()
                } else if model_words.chars().count() > 24 {
                    "Auto-pick".to_string()
                } else {
                    model_words
                };
                if let Some(note) = &pics_note {
                    ui.label(RichText::new(note).color(ORANGE).size(12.5));
                    ui.add_space(4.0);
                }
                let out = composer_card(
                    ui,
                    "chat-composer-text",
                    crate::labels::BOX_CHAT,
                    &mut draft,
                    mode,
                    crate::labels::SEND,
                    |ui, actions| {
                        crate::chat_pics::box_thumbnails(ui, &pics, shrinking, actions);
                        if ui
                            .add(
                                theme::pill_button(
                                    RichText::new(format!("{model_words} ▾"))
                                        .color(MUTED)
                                        .size(13.0)
                                        .into(),
                                )
                                .stroke(egui::Stroke::new(1.0, theme::CHIP_EDGE)),
                            )
                            .on_hover_text("Who answers: choose it under Models")
                            .clicked()
                        {
                            actions.push(Action::OpenModels);
                        }
                        crate::view_task::effort_chip(ui, "chat", &None, &new_effort, &mut picked);
                    },
                    actions,
                );
                crate::shot::note_box(ui.ctx(), out.edit.rect);
                if focus || self.chat_focus_once {
                    out.edit.request_focus();
                    self.chat_focus_once = false;
                }
                let enter = out.edit.has_focus()
                    && ui.input(|i| i.key_pressed(Key::Enter) && !i.modifiers.shift);
                if out.stop
                    && let Some(id) = &open
                {
                    actions.push(Action::StopChat(id.clone()));
                }
                if (out.send || (enter && !busy)) && !draft.trim().is_empty() {
                    actions.push(Action::SendChat(open.clone(), draft.trim().to_string()));
                    draft.clear();
                }
                hint_line(ui, crate::labels::HINT_CHAT);
            });
        });
        for a in picked {
            if let Action::SetEffort(_, e) = a {
                self.new_effort = e;
            }
        }
        if let Some(t) = starter_text {
            draft = t.to_string();
            self.chat_focus_once = true;
        }
        if !pics.is_empty() {
            self.chat_pics.insert(key.clone(), pics);
        }
        if !draft.is_empty() {
            self.chat_drafts.insert(key, draft);
        }

        // The conversation.
        let scroll_down = self.scroll_down > 0;
        if !ui.ctx().will_discard() {
            self.scroll_down = self.scroll_down.saturating_sub(1);
        }
        if scroll_down {
            ui.ctx().request_repaint();
        }
        let md = &mut self.md;
        let last_user = chat.and_then(|c| {
            c.turns
                .iter()
                .rev()
                .find(|t| t.user)
                .map(|t| t.text.clone())
        });
        ScrollArea::vertical()
            .id_salt(("chat", &open))
            .stick_to_bottom(true)
            .auto_shrink([false, false])
            .show(ui, |ui| {
                if empty {
                    greeting_block(ui);
                    return;
                }
                in_lane(ui, |ui| {
                    ui.set_max_width(LANE.min(ui.available_width() - 12.0));
                    let Some(c) = chat else {
                        return;
                    };
                    let n_turns = c.turns.len();
                    for (n, t) in c.turns.iter().enumerate() {
                        if t.user {
                            let editing = self
                                .edit_turn
                                .as_ref()
                                .is_some_and(|(ch, id, _)| *ch == c.id && *id == t.id);
                            if editing {
                                if let Some((_, _, words)) = &mut self.edit_turn
                                    && let Some(done) = edit_box(ui, words)
                                {
                                    match done {
                                        Some(w) => actions.push(Action::EditChat(
                                            c.id.clone(),
                                            t.id.clone(),
                                            w,
                                        )),
                                        None => self.edit_turn = None,
                                    }
                                }
                                ui.add_space(12.0);
                                continue;
                            }
                            crate::chat_pics::sent_pictures(ui, &t.id, &t.pictures, actions);
                            let area = user_bubble(ui, &t.text);
                            let can_edit = !busy && !t.id.is_empty();
                            let edit_chat = c.id.clone();
                            let edit_id = t.id.clone();
                            let edit_words = t.text.clone();
                            let edit_fn = move || {
                                Action::StartEdit(
                                    edit_chat.clone(),
                                    edit_id.clone(),
                                    edit_words.clone(),
                                )
                            };
                            // A small pencil under the message, shown while the pointer is on it.
                            let hot = can_edit && area.container_contains_pointer();
                            ui.with_layout(Layout::right_to_left(Align::Min), |ui| {
                                let pencil = RichText::new("✎ Edit").size(12.0).color(if hot {
                                    MUTED
                                } else {
                                    egui::Color32::TRANSPARENT
                                });
                                if ui
                                    .add(egui::Button::new(pencil).frame(false))
                                    .on_hover_text("Edit this message and ask again from here")
                                    .clicked()
                                    && can_edit
                                {
                                    actions.push(edit_fn());
                                }
                            });
                            crate::menus::message(
                                ui,
                                &area,
                                &crate::menus::Msg {
                                    text: &t.text,
                                    answer: false,
                                    listen: egui::Id::unique(("chat-listen-you", &c.id, n)),
                                    again: None,
                                    edit: can_edit.then_some(&edit_fn as &dyn Fn() -> Action),
                                },
                                actions,
                            );
                        } else {
                            let area = ui
                                .push_id(("chat-answer", &c.id, n), |ui| {
                                    CommonMarkViewer::new().show(ui, md, &t.text);
                                })
                                .response;
                            let again_chat = c.id.clone();
                            let again_fn = move || Action::TryAgain(again_chat.clone());
                            crate::menus::message(
                                ui,
                                &area,
                                &crate::menus::Msg {
                                    text: &t.text,
                                    answer: true,
                                    listen: egui::Id::unique(("chat-listen", &c.id, n)),
                                    again: (n + 1 == n_turns && !busy && last_user.is_some())
                                        .then_some(&again_fn as &dyn Fn() -> Action),
                                    edit: None,
                                },
                                actions,
                            );
                            ui.add_space(2.0);
                            // Under an answer that used lookups: "Sources: site, site", each a link.
                            let sources = source_links(t);
                            if !sources.is_empty() {
                                ui.horizontal_wrapped(|ui| {
                                    ui.spacing_mut().item_spacing.x = 8.0;
                                    ui.label(RichText::new(SOURCES_TAG).color(MUTED).size(12.0));
                                    for (site, url) in &sources {
                                        ui.hyperlink_to(RichText::new(site).size(12.0), url)
                                            .on_hover_text(format!("Open {site}"));
                                    }
                                });
                            }
                            ui.horizontal(|ui| {
                                ui.spacing_mut().item_spacing.x = 10.0;
                                let dim = MUTED.gamma_multiply(0.7);
                                let sid = egui::Id::unique(("chat-listen", &c.id, n));
                                let speaking = crate::places::speaking() == Some(sid);
                                if quiet_link(
                                    ui,
                                    if speaking { "■ Stop" } else { "▶ Listen" },
                                    if speaking { JADE } else { dim },
                                    "Read this answer aloud with the desktop's voice",
                                ) {
                                    if speaking {
                                        crate::places::stop_speaking();
                                    } else {
                                        crate::places::speak(sid, &t.text);
                                    }
                                }
                                if speaking {
                                    ui.ctx().request_repaint_after(
                                        std::time::Duration::from_millis(300),
                                    );
                                }
                                if quiet_link(ui, "Copy", dim, "Copy this answer") {
                                    ui.ctx().copy_text(t.text.clone());
                                }
                                if n + 1 == n_turns
                                    && !busy
                                    && last_user.is_some()
                                    && quiet_link(ui, "Try again", dim, "Ask the same thing again")
                                {
                                    actions.push(Action::TryAgain(c.id.clone()));
                                }
                                ran_glyph(ui, t.ran);
                                ui.label(RichText::new(ran_words(t)).color(MUTED).size(12.0));
                            });
                            // A different model than the usual one answered: one dim line says so.
                            if let Some(note) = t.switch_note.as_deref().filter(|n| !n.is_empty()) {
                                ui.label(RichText::new(note).color(MUTED).size(12.0));
                            }
                        }
                        ui.add_space(12.0);
                    }
                    // The answer being written.
                    match partial {
                        Some(p) if !p.text.is_empty() => {
                            ui.push_id(("chat-live", &c.id), |ui| {
                                CommonMarkViewer::new().show(ui, md, &p.text);
                            });
                        }
                        _ if busy => {
                            ui.horizontal(|ui| {
                                ui.spinner();
                                let words = match &note.waiting {
                                    Some(w) => w.clone(),
                                    None if paid => "Working on it…".to_string(),
                                    None => "The free model is working…".to_string(),
                                };
                                ui.label(RichText::new(words).color(MUTED));
                            });
                        }
                        _ => {}
                    }
                    // The last edit cut later messages away: Undo for a few seconds.
                    if let Some((chat_id, until)) = &self.edit_undo {
                        if *chat_id == c.id && std::time::Instant::now() < *until {
                            ui.add_space(6.0);
                            ui.horizontal(|ui| {
                                ui.label(RichText::new("Later messages removed").color(MUTED));
                                if ui.add(theme::quiet("Undo")).clicked() {
                                    actions.push(Action::UndoEdit(c.id.clone()));
                                }
                            });
                            ui.ctx()
                                .request_repaint_after(std::time::Duration::from_millis(300));
                        } else if std::time::Instant::now() >= *until {
                            self.edit_undo = None;
                        }
                    }
                    if let Some((project, task)) = &note.filed {
                        let name = crate::places::display_name(&self.settings, project);
                        filed_card(ui, &name, task, actions);
                    }
                    if let Some(why) = &note.problem {
                        ui.add_space(6.0);
                        ui.label(RichText::new(why).color(ORANGE).size(13.5));
                    }
                    if scroll_down {
                        ui.scroll_to_cursor(Some(Align::BOTTOM));
                    }
                });
            });
        if busy {
            // Words arrive as they are written.
            ui.ctx()
                .request_repaint_after(std::time::Duration::from_millis(100));
        }
    }

    /// "Delete this chat?": a deleted chat never comes back.
    pub(crate) fn chat_windows(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        if let Some((id, text)) = &mut self.rename_chat {
            let mut open = true;
            let mut done = false;
            egui::Window::new("Rename this chat")
                .open(&mut open)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .collapsible(false)
                .resizable(false)
                .show(ctx, |ui| {
                    ui.set_max_width(380.0);
                    let resp = ui.add(egui::TextEdit::singleline(text).desired_width(340.0));
                    crate::menus::edit_menu(&resp, "chat-rename");
                    resp.request_focus();
                    let enter = ui.input(|i| i.key_pressed(egui::Key::Enter));
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        if (ui.add(theme::primary("Rename")).clicked() || enter)
                            && !text.trim().is_empty()
                        {
                            actions.push(Action::RenameChat(id.clone(), text.clone()));
                            done = true;
                        }
                        if ui.add(theme::quiet("Keep the name")).clicked() {
                            done = true;
                        }
                    });
                });
            if done || !open {
                self.rename_chat = None;
            }
        }
        let Some(id) = self.confirm_delete_chat.clone() else {
            return;
        };
        let title = self
            .station
            .as_ref()
            .ok()
            .and_then(|r| r.chats.get(&id))
            .map(chat_title)
            .unwrap_or_default();
        let mut open = true;
        egui::Window::new("Delete this chat?")
            .open(&mut open)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .collapsible(false)
            .resizable(false)
            .show(ctx, |ui| {
                ui.set_max_width(380.0);
                ui.label(format!(
                    "\"{title}\" will be deleted here and on your phone for good. \
                     Choose Keep it to leave it as it is."
                ));
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    if ui
                        .add(egui::Button::new(
                            RichText::new("Delete the chat").color(RED),
                        ))
                        .clicked()
                    {
                        actions.push(Action::DeleteChat(id.clone()));
                        self.confirm_delete_chat = None;
                    }
                    if ui.add(theme::quiet("Keep it")).clicked() {
                        self.confirm_delete_chat = None;
                    }
                });
            });
        if !open {
            self.confirm_delete_chat = None;
        }
    }
}

/// The owner's message: a bubble on the right, as in a task.
/// How long "Later messages removed · Undo" stays on offer.
pub(crate) const EDIT_UNDO: std::time::Duration = std::time::Duration::from_secs(8);

/// A message being edited in place: a box with Cancel and Send. `Some(None)`
/// is Cancel, `Some(Some(words))` is Send.
fn edit_box(ui: &mut egui::Ui, words: &mut String) -> Option<Option<String>> {
    let mut done = None;
    ui.with_layout(Layout::right_to_left(Align::Min), |ui| {
        egui::Frame::new()
            .fill(theme::BUBBLE)
            .corner_radius(egui::CornerRadius::same(theme::RADIUS))
            .inner_margin(egui::Margin::same(10))
            .show(ui, |ui| {
                ui.set_max_width((ui.available_width() * 0.85).min(560.0));
                ui.with_layout(Layout::top_down(Align::Min), |ui| {
                    let r = ui.add(
                        egui::TextEdit::multiline(words)
                            .desired_rows(2)
                            .desired_width(ui.available_width()),
                    );
                    crate::menus::edit_menu(&r, "edit-message");
                    r.request_focus();
                    ui.horizontal(|ui| {
                        if ui.add(theme::primary("Send the edit")).clicked()
                            && !words.trim().is_empty()
                        {
                            done = Some(Some(words.clone()));
                        }
                        if ui.add(theme::quiet("Cancel")).clicked() {
                            done = Some(None);
                        }
                    });
                });
            });
    });
    done
}

fn user_bubble(ui: &mut egui::Ui, text: &str) -> egui::Response {
    ui.with_layout(Layout::right_to_left(Align::Min), |ui| {
        egui::Frame::new()
            .fill(theme::BUBBLE)
            .corner_radius(egui::CornerRadius::same(theme::RADIUS))
            .inner_margin(egui::Margin::same(10))
            .show(ui, |ui| {
                ui.set_max_width((ui.available_width() * 0.75).min(560.0));
                ui.with_layout(Layout::top_down(Align::Min), |ui| {
                    ui.add(egui::Label::new(text).wrap().selectable(true));
                });
            })
            .response
    })
    .inner
}

/// A message that named a project started a task there: one card that
/// opens it.
fn filed_card(ui: &mut egui::Ui, project: &str, task: &str, actions: &mut Vec<Action>) {
    egui::Frame::new()
        .fill(CARD)
        .stroke(egui::Stroke::new(1.0, LINE))
        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
        .inner_margin(egui::Margin::symmetric(10, 6))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal_wrapped(|ui| {
                ui.label(RichText::new(format!("Started in {project}")).color(MUTED));
                if ui.add(theme::quiet("Open the task")).clicked() {
                    actions.push(Action::Select(task.to_string()));
                }
            });
        });
}

#[cfg(test)]
mod host_tests {
    use super::without_hostname;

    #[test]
    fn the_system_host_name_reads_this_pc() {
        assert_eq!(
            without_hostname("Qwen · homepc", "homepc"),
            "Qwen · This PC"
        );
        assert_eq!(
            without_hostname("Qwen · Kitchen PC", "homepc"),
            "Qwen · Kitchen PC"
        );
        assert_eq!(without_hostname("Qwen", ""), "Qwen");
    }
}

#[cfg(test)]
mod sources_tests {
    use super::*;
    use mimir_core::proto::ChatSource;

    fn src(site: &str, url: &str, results: bool) -> ChatSource {
        ChatSource {
            site: site.into(),
            url: url.into(),
            results,
            ..Default::default()
        }
    }

    #[test]
    fn sources_are_site_names_once_each_and_never_a_results_page_or_odd_link() {
        let t = ChatTurn {
            id: "t.a".into(),
            user: false,
            text: "It rains.".into(),
            sources: vec![
                src("open-meteo.com", "https://open-meteo.com/", false),
                src("open-meteo.com", "https://open-meteo.com/en", false),
                src("search", "https://search.example/?q=x", true),
                src("odd", "file:///etc/passwd", false),
                src("example.org", "http://example.org/a", false),
            ],
            ran: None,
            model_words: None,
            ..t_default()
        };
        assert_eq!(
            source_links(&t),
            vec![
                (
                    "open-meteo.com".to_string(),
                    "https://open-meteo.com/".to_string()
                ),
                (
                    "example.org".to_string(),
                    "http://example.org/a".to_string()
                ),
            ]
        );
        let none = ChatTurn {
            sources: vec![],
            ..t
        };
        assert!(source_links(&none).is_empty());
    }

    fn t_default() -> ChatTurn {
        serde_json::from_str(r#"{"id":"x","user":false,"text":"","at":0}"#).unwrap()
    }
}
