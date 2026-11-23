//! The middle: the chosen task.

use crate::app::{Action, App, EFFORTS};
use crate::models::{self, MODELS};
use crate::theme::{self, CARD, JADE, JADE_DIM, LINE, MUTED, ORANGE, ORANGE_DIM, RED};
use crate::view_chat::{SendMode, composer_card, hint_line, in_lane};
use eframe::egui::{self, Align, Key, Layout, RichText, ScrollArea, TextEdit};
use egui_commonmark::{CommonMarkCache, CommonMarkViewer};
use mimir_core::approvals::Answer;
use mimir_core::task::{Entry, Mode, Status, Step};
use std::collections::HashSet;
use std::path::PathBuf;

impl App {
    pub(crate) fn task_view(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let Ok(r) = &self.station else { return };
        let Some(l) = self
            .selected
            .as_ref()
            .and_then(|id| r.tasks.iter().find(|l| &l.task.id == id))
        else {
            ui.label("Choose a task on the left, or start a new one.");
            return;
        };
        let t = &l.task;
        let id = t.id.clone();
        let running = l.busy();
        let details = self.details_shown(ui.ctx());
        let _screen_shown = false;
        // Top: where this task lives, who answers, the panel toggle.
        let home_name = crate::places::display_name(&self.settings, t.home_project());
        let is_chat = std::path::Path::new(&t.project) == crate::places::chats_dir();
        // A chat has no breadcrumb: the left menu shows which one is open.
        let crumb = if is_chat {
            None
        } else {
            Some(format!("{home_name} › {}", t.title))
        };
        let model_down = self.pc_model_down();
        let crumb_resp = crate::view_top::mid_top(
            ui,
            &crate::view_top::MidTop {
                crumb,
                paid: self.settings.task_shows_online(
                    t.model.as_deref(),
                    is_chat,
                    l.outside_now() || t.from_phone.is_some(),
                    mimir_core::models::default_free(&mimir_core::models::cached()).is_some(),
                ),
                outside: l.outside_now() || t.from_phone.is_some(),
                model_down,
                on_chat: false,
                panel: Some(details),
                menu_name: "task-menu",
            },
            actions,
            |ui, actions| {
                crate::extras::prompts_menu(
                    ui,
                    &self.settings,
                    t.home_project(),
                    &mut self.draft,
                    actions,
                );
                ui.separator();
                if ui.button("Rename").clicked() {
                    self.renaming = Some((id.clone(), t.title.clone(), 0));
                }
                if ui.button(crate::labels::MOVE_TO).clicked() {
                    actions.push(Action::Organize(crate::organize::Organize::AskMove(
                        id.clone(),
                    )));
                }
                if ui
                    .button(if t.archived { "Unarchive" } else { "Archive" })
                    .clicked()
                {
                    actions.push(Action::Archive(id.clone(), !t.archived));
                }
                ui.separator();
                if !is_chat {
                    if ui.button("Open the project folder").clicked() {
                        actions.push(Action::OpenPath(PathBuf::from(&t.project)));
                    }
                    if ui.button("Open a terminal here").clicked() {
                        actions.push(Action::OpenTerminal(PathBuf::from(&t.project)));
                    }
                }
                // Undo to before the newest message that has a snapshot.
                if !is_chat
                    && !running
                    && let Some((at, _)) = t.checkpoints.last()
                    && ui
                        .button("Undo the changes…")
                        .on_hover_text(
                            "Put the files back as they were before your last message. \
                                 You see the list first. (Each of your messages also has \
                                 its own Undo link.)",
                        )
                        .clicked()
                {
                    actions.push(Action::UndoPreview(id.clone(), *at));
                }
                if ui
                    .button("Remote control in a terminal (shows a QR code)…")
                    .on_hover_text(
                        "Opens Remote Control for this folder in a terminal window, so you \
                             can start and drive tasks from your phone",
                    )
                    .clicked()
                {
                    actions.push(Action::RemoteControl(PathBuf::from(&t.project)));
                }
                if let Some(b) = &t.brief
                    && ui
                        .button("Show the summary")
                        .on_hover_text("What the next model was told when it took over")
                        .clicked()
                {
                    actions.push(Action::ShowLines(
                        "the handover brief".into(),
                        b.lines().map(str::to_string).collect(),
                    ));
                }
                if t.redo.is_some()
                    && !running
                    && ui
                        .button("Put back the undone changes")
                        .on_hover_text("Undo the last undo")
                        .clicked()
                {
                    actions.push(Action::RedoPreview(id.clone()));
                }
                if let Some(origin) = &t.origin
                    && !running
                {
                    let name = crate::places::display_name(&self.settings, origin);
                    if ui
                        .button(format!("Bring the changes into {name}"))
                        .on_hover_text(
                            "Saves the copy's work and brings it into the main \
                                 folder. If that can't be done cleanly, nothing changes.",
                        )
                        .clicked()
                    {
                        actions.push(Action::BringIn(id.clone()));
                    }
                    if ui.button("Remove the copy…").clicked() {
                        actions.push(Action::ConfirmRemoveCopy(id.clone()));
                    }
                }
                if ui.button("Find in this chat (Ctrl+F)").clicked() {
                    actions.push(Action::Find);
                }
                if ui
                    .button("Save the chat as a file…")
                    .on_hover_text("A Markdown file in Documents › Mimir")
                    .clicked()
                {
                    actions.push(Action::SaveConversation(id.clone()));
                }
                if ui.button("Keyboard shortcuts (Ctrl+/)").clicked() {
                    actions.push(Action::ShowShortcuts);
                }
                ui.separator();
                if ui
                    .button(RichText::new("Delete this task…").color(RED))
                    .clicked()
                {
                    self.confirm_remove = Some(id.clone());
                }
            },
        );
        if crumb_resp.is_some_and(|c| c.double_clicked()) {
            self.renaming = Some((id.clone(), t.title.clone(), 0));
        }
        // Its state, and the buttons for what can be done to the run.
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = 8.0;
            // The same state as the sidebar row and the foot: the PC's truth.
            let (words, color) = crate::app::state_look(l);
            let quiet_chat = is_chat && l.shown_state() == mimir_core::proto::TaskState::Done;
            if !quiet_chat {
                ui.label(RichText::new(words).color(color).size(13.0));
            }
            if running {
                if ui
                    .add(theme::quiet("Stop"))
                    .on_hover_text("End this run now. Continue starts it again later.")
                    .clicked()
                {
                    actions.push(Action::Stop(id.clone()));
                }
                if ui
                    .add(theme::quiet("Pause"))
                    .on_hover_text("Pause now. Continue picks up where it stopped. (Ctrl+.)")
                    .clicked()
                {
                    actions.push(Action::Pause(id.clone()));
                }
            } else if matches!(t.status, Status::Paused | Status::Stopped) {
                // A session to carry on, or (never started) the request again.
                let again = match (&t.session_id, last_request(&t.entries)) {
                    (Some(_), _) => Some(Action::Continue(id.clone())),
                    (None, Some(text)) => Some(Action::Send(id.clone(), text, vec![])),
                    (None, None) => None,
                };
                if let Some(a) = again
                    && ui.add(theme::primary("Continue")).clicked()
                {
                    actions.push(a);
                }
            }
            // Why it stopped, when something went wrong.
            if !running
                && let Some(Entry::Note(n)) = t.entries.last()
                && let Some((headline, _)) = failure(n)
            {
                let short = headline.split('.').next().unwrap_or(headline);
                ui.label(RichText::new(short).color(RED).size(13.0));
            }
            match &mut self.renaming {
                Some((rid, text, phase)) if *rid == id => {
                    let resp = ui
                        .add(TextEdit::singleline(text).desired_width(ui.available_width() - 10.0));
                    crate::menus::edit_menu(&resp, "task-rename");
                    // The cursor goes in once; a click anywhere else
                    // (the message box, say) ends the rename.
                    if *phase == 0 {
                        resp.request_focus();
                        *phase = 1;
                    } else if resp.has_focus() {
                        *phase = 2;
                    }
                    let enter = ui.input(|i| i.key_pressed(Key::Enter));
                    let esc = ui.input(|i| i.key_pressed(Key::Escape));
                    let blurred = *phase == 2 && !resp.has_focus() && !enter && !esc;
                    if enter
                        || (blurred && !text.trim().is_empty() && text.trim() != t.title.trim())
                    {
                        actions.push(Action::Rename(id.clone(), text.clone()));
                    }
                    if esc || blurred {
                        self.renaming = None;
                    }
                }
                _ => {}
            }
        });
        // A long conversation: one calm line, not a popup.
        if let Some(n) = mimir_core::long::notice(t)
            && !mimir_core::long::hidden(self.long_dismissed.get(&id).copied(), &n)
        {
            long_line(ui, &id, &n, actions);
        }
        // Where this task works: its project (and copy), and the git branch.
        if std::path::Path::new(&t.project) != crate::places::chats_dir() {
            let name = crate::places::display_name(&self.settings, t.home_project());
            let name = if t.origin.is_some() {
                format!("{name} · separate copy")
            } else {
                name
            };
            let branch = self.git_of(&t.project).and_then(|g| g.branch);
            // The breadcrumb above names the project; this line is for what
            // else is worth saying about where the work happens.
            let say_more = t.placed.is_some()
                || t.origin.is_some()
                || branch.is_some()
                || t.from_phone.is_some();
            if say_more {
                ui.horizontal(|ui| {
                    // A chat Mímir filed itself says so, with a way to
                    // change it (and to confirm a guess).
                    if let Some(how) = t.placed {
                        let why = match &t.placed_why {
                            Some(w) => format!(
                                "{}\n{w}\nThe note about this guess is kept by Mímir, not \
                             in the project.",
                                t.project
                            ),
                            None => t.project.clone(),
                        };
                        ui.label(
                            RichText::new(crate::labels::filed_under(&name, how))
                                .color(MUTED)
                                .size(13.0),
                        )
                        .on_hover_text(why);
                        if ui.link(RichText::new("change").size(13.0)).clicked() {
                            actions.push(Action::Organize(crate::organize::Organize::AskMove(
                                id.clone(),
                            )));
                        }
                        if how == mimir_core::proto::Placed::Unsure {
                            ui.label(RichText::new("·").color(MUTED).size(13.0));
                            if ui
                                .link(RichText::new(crate::labels::THATS_RIGHT).size(13.0))
                                .on_hover_text("Keep it here. The “not sure” note goes away.")
                                .clicked()
                            {
                                actions.push(Action::ConfirmPlacement(id.clone()));
                            }
                        }
                    } else if t.origin.is_some() {
                        ui.add(
                            egui::Label::new(RichText::new(&name).color(MUTED).size(13.0))
                                .truncate(),
                        )
                        .on_hover_text(&t.project);
                    }
                    if let Some(b) = &branch {
                        ui.label(RichText::new(format!("⎇ {b}")).color(MUTED).size(13.0))
                            .on_hover_text("Where this work is saved");
                    }
                    if t.from_phone.is_some() {
                        ui.label(
                            RichText::new(format!("· {}", crate::labels::FROM_PHONE))
                                .color(theme::JADE)
                                .size(13.0),
                        )
                        .on_hover_text(
                            "Started from another app through this project's \
                         remote control. This window follows it as it happens.",
                        );
                    }
                });
            }
        }
        if let Some(out) = &t.ran_out {
            ran_out_card(ui, &id, out, actions);
        }
        ui.add_space(6.0);

        // Message box and its option row at the bottom.
        let slash = if r.slash_commands.is_empty() {
            self.settings.slash_commands.clone()
        } else {
            r.slash_commands.clone()
        };
        // Ctrl+Enter and Ctrl+D answer an OK unless a dialog is open. A bare
        // Enter, a letter or Esc never does, and a new OK never takes the
        // cursor out of the message box.
        let keys_free = !self.dialog_open();
        let asking = !l.asks.is_empty();
        let draft = &mut self.draft;
        let attachments = &mut self.attachments;
        let deny_note = &mut self.deny_note;
        let question = t.question_waiting();
        // An unanswered question puts the cursor in the message box.
        let focus_composer = std::mem::take(&mut self.focus_composer)
            || (question && ui.ctx().memory(|m| m.focused().is_none()));
        let home = crate::places::display_name(&self.settings, t.home_project());
        crate::view_chat::bottom_area(ui, "composer", |ui| {
            // The same readable column as the conversation.
            in_lane(ui, |ui| {
                {
                    // Started from the phone or claude.ai: this view follows it
                    // live; replies go there (Claude Code has one writer).
                    // The same for a conversation going on in a terminal or the
                    // Claude app right now.
                    let still_running = crate::labels::still_running_in(t.elsewhere_in.as_deref());
                    let elsewhere = t
                        .from_phone
                        .clone()
                        .or_else(|| t.live_elsewhere.then(String::new));
                    if let Some(url) = &elsewhere {
                        let from_phone = t.from_phone.is_some();
                        egui::Frame::new()
                            .fill(CARD)
                            .stroke(egui::Stroke::new(1.0, LINE))
                            .corner_radius(egui::CornerRadius::same(theme::RADIUS))
                            .inner_margin(egui::Margin::same(10))
                            .show(ui, |ui| {
                                ui.set_width(ui.available_width());
                                ui.horizontal_wrapped(|ui| {
                                    let line = if from_phone {
                                        crate::labels::FOLLOWS_ALONG
                                    } else {
                                        still_running.as_str()
                                    };
                                    ui.label(RichText::new(line).color(MUTED));
                                    // Ends it where it runs; then the reply box
                                    // appears here.
                                    if !from_phone
                                        && ui
                                            .add(theme::quiet(crate::labels::STOP_ELSEWHERE))
                                            .on_hover_text(
                                                "End it where it runs. Then carry on here.",
                                            )
                                            .clicked()
                                    {
                                        actions.push(Action::Stop(id.clone()));
                                    }
                                    // The link shows only for a session that
                                    // really started on claude.ai.
                                    if crate::labels::is_claude_ai_session(url)
                                        && ui
                                            .add(theme::quiet(crate::labels::OPEN_ON_CLAUDE_AI))
                                            .on_hover_text(url.as_str())
                                            .clicked()
                                    {
                                        mimir_core::setup::open_url(url);
                                    }
                                });
                            });
                        return;
                    }
                    // Small chips only: the mode and where the task runs, with
                    // the explanation on hover (the chat area stays clean).
                    if t.mode == Mode::NoAsk {
                        ui.horizontal_wrapped(|ui| {
                            if t.mode == Mode::NoAsk {
                                theme::chip(ui, "Auto-approve", RED).on_hover_text(
                                    "Mímir changes files and runs commands without asking. Undo \
                                 still works for files. It goes back to Ask me first after 5 \
                                 hours of work or 24 hours idle.",
                                );
                            }
                        });
                    }
                    if let Some(ask) = l.asks.front() {
                        ok_bar(
                            ui,
                            &id,
                            &t.project,
                            &home,
                            ask,
                            l.asks.len(),
                            keys_free,
                            deny_note,
                            actions,
                        );
                    } else if t.origin.is_some() && !running {
                        // A separate copy: what to do with its work, in view.
                        egui::Frame::new()
                        .fill(CARD)
                        .stroke(egui::Stroke::new(1.0, JADE_DIM))
                        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
                        .inner_margin(egui::Margin::same(10))
                        .show(ui, |ui| {
                            ui.set_width(ui.available_width());
                            ui.horizontal_wrapped(|ui| {
                                ui.label(
                                    RichText::new(format!("This work is in a separate copy of {home}."))
                                        .color(MUTED),
                                );
                                if ui
                                    .add(theme::primary(&format!("Bring the changes into {home}")))
                                    .on_hover_text(
                                        "Saves the copy's work and brings it into the \
                                         main folder. If that can't be done cleanly, nothing changes.",
                                    )
                                    .clicked()
                                {
                                    actions.push(Action::BringIn(id.clone()));
                                }
                                if ui.add(theme::quiet("Remove the copy…")).clicked() {
                                    actions.push(Action::ConfirmRemoveCopy(id.clone()));
                                }
                            });
                        });
                        ui.add_space(6.0);
                    }
                    if t.redo.is_some()
                        && !running
                        && matches!(t.entries.last(), Some(Entry::Note(n)) if n.starts_with("Undid "))
                    {
                        ui.horizontal(|ui| {
                            ui.label(RichText::new("Changed your mind?").color(MUTED).size(13.0));
                            if ui
                                .add(theme::quiet("Put back the undone changes"))
                                .clicked()
                            {
                                actions.push(Action::RedoPreview(id.clone()));
                            }
                        });
                    }
                    if question {
                        ui.horizontal(|ui| {
                            if ui
                                .add(theme::quiet("No reply needed"))
                                .on_hover_text("Clear the question mark from this task")
                                .clicked()
                            {
                                actions.push(Action::DismissQuestion(id.clone()));
                            }
                        });
                    }
                    if let Some(mimir_core::proto::QueuedMessage { text, files }) = &l.queued {
                        let hold = match crate::extras::waiting_now(
                            t.status,
                            !l.asks.is_empty(),
                            t.question_waiting(),
                            t.plan_waiting(),
                        ) {
                            crate::extras::Waiting::Hold(why) => Some(why),
                            _ => None,
                        };
                        egui::Frame::new()
                            .fill(CARD)
                            .stroke(egui::Stroke::new(1.0, LINE))
                            .corner_radius(egui::CornerRadius::same(theme::RADIUS))
                            .inner_margin(egui::Margin::symmetric(10, 6))
                            .show(ui, |ui| {
                                ui.set_width(ui.available_width());
                                ui.horizontal_wrapped(|ui| {
                                    let first: String = text
                                        .lines()
                                        .next()
                                        .unwrap_or("")
                                        .chars()
                                        .take(80)
                                        .collect();
                                    let more = if files.is_empty() {
                                        String::new()
                                    } else {
                                        format!(" (+{} attached)", files.len())
                                    };
                                    ui.label(
                                        RichText::new(
                                            hold.unwrap_or("Waiting to send when Mímir is done:"),
                                        )
                                        .color(if hold.is_some() { theme::AMBER } else { MUTED })
                                        .size(13.0),
                                    );
                                    ui.label(RichText::new(format!("{first}{more}")).size(13.0));
                                    if hold.is_some()
                                        && ui.add(theme::quiet("Send it now").small()).clicked()
                                    {
                                        actions.push(Action::SendQueued(id.clone()));
                                    }
                                    if ui.add(theme::quiet("Edit").small()).clicked() {
                                        actions.push(Action::Unqueue(id.clone(), true));
                                    }
                                    if ui
                                        .add(theme::quiet("✕").small())
                                        .on_hover_text("Don't send it")
                                        .clicked()
                                    {
                                        actions.push(Action::Unqueue(id.clone(), false));
                                    }
                                });
                            });
                        ui.add_space(4.0);
                    }
                    if !mimir_core::models::is_free(t.model.as_deref())
                        && !self.settings.project_free()
                        && self.setup.claude_ready() == Some(false)
                        && crate::setup_view::claude_missing_line(ui, actions)
                        && let Some(f) =
                            mimir_core::models::default_free(&mimir_core::models::cached())
                    {
                        actions.push(Action::SetModel(id.clone(), Some(f.id.clone())));
                    }
                    let chose = slash_suggestions(ui, draft, &slash);
                    attachment_chips(ui, attachments);
                    let can_wait = running;
                    let mode = if can_wait {
                        SendMode::Wait
                    } else {
                        SendMode::Send
                    };
                    let mut picked: Vec<Action> = vec![];
                    let out = composer_card(
                        ui,
                        "composer",
                        crate::labels::BOX_TASK,
                        draft,
                        mode,
                        crate::labels::SEND,
                        |ui, _actions| {
                            options_row(
                                ui,
                                &id,
                                t.incognito,
                                !is_chat,
                                t.mode,
                                &t.model,
                                t.auto_choice.as_deref(),
                                &t.effort,
                                &mut picked,
                            );
                        },
                        actions,
                    );
                    actions.append(&mut picked);
                    let edit = out.edit;
                    if focus_composer || chose {
                        edit.request_focus();
                    }
                    // While a card asks, Ctrl+Enter is its Allow.
                    let (enter, ctrl, esc) = (
                        edit.has_focus()
                            && ui.input(|i| {
                                i.key_pressed(Key::Enter)
                                    && !i.modifiers.shift
                                    && !(asking && i.modifiers.ctrl)
                            }),
                        ui.input(|i| i.modifiers.ctrl),
                        edit.has_focus()
                            && ui.input(|i| i.key_pressed(Key::Escape))
                            && crate::places::listen_status() == crate::places::ListenStatus::Idle,
                    );
                    // Esc in the box ends a run that is going.
                    if esc && running {
                        actions.push(Action::Stop(id.clone()));
                    }
                    let later = out.later || (can_wait && enter && ctrl);
                    if (enter || out.send || later)
                        && (!draft.trim().is_empty() || !attachments.is_empty())
                    {
                        let text = if draft.trim().is_empty() {
                            "(See the attached files.)".to_string()
                        } else {
                            draft.clone()
                        };
                        actions.push(if later {
                            Action::Queue(id.clone(), text, attachments.clone())
                        } else {
                            Action::Send(id.clone(), text, attachments.clone())
                        });
                    }
                    if running {
                        ui.label(
                            RichText::new("Changes apply to your next message")
                                .color(MUTED)
                                .size(12.0),
                        );
                    }
                    if t.entries.is_empty() {
                        hint_line(ui, crate::labels::HINT_TASK);
                    }
                    if t.incognito {
                        ui.horizontal_wrapped(|ui| {
                            theme::whisper_eye(ui, 22.0);
                            ui.label(
                                RichText::new(crate::labels::WHISPER_NOTE)
                                    .color(theme::WHISPER)
                                    .size(13.0),
                            );
                            if ui.button(crate::labels::END_AND_FORGET).clicked() {
                                actions.push(Action::EndIncognito(t.id.clone()));
                            }
                        });
                    }
                }
            });
        });

        let md = &mut self.md;
        let open_steps = &mut self.open_steps;
        let show_plan = t.plan_doc.is_some() && !running && t.mode == Mode::Plan;
        let waiting_question = t.question_waiting();
        let hide_opened = l.outside_now() || t.from_phone.is_some();
        let scroll_down = self.scroll_down > 0;
        if !ui.ctx().will_discard() {
            self.scroll_down = self.scroll_down.saturating_sub(1);
        }
        if scroll_down {
            ui.ctx().request_repaint();
        }
        let found = crate::extras::find_bar(&mut self.find, ui, &t.entries);
        let find_jump = found.is_some()
            && self
                .find
                .as_mut()
                .is_some_and(|f| std::mem::take(&mut f.jump));
        // Grows with each entry and with text still being written.
        let size_now = t.entries.len() * 1_000_000 + t.live.len();
        let out = ScrollArea::vertical()
            // Each task keeps its own place.
            .id_salt(("conversation", &id))
            .stick_to_bottom(true)
            .auto_shrink([false, false])
            .show(ui, |ui| {
                // A readable line length on big screens.
                in_lane(ui, |ui| {
                    {
                        // Room on the right for the scrollbar.
                        ui.set_max_width(crate::view_chat::LANE.min(ui.available_width() - 12.0));
                        for (n, e) in t.entries.iter().enumerate() {
                            // "Send a message to carry on" only where a message can
                            // be sent: not while it still runs elsewhere.
                            if hide_opened
                                && matches!(e, Entry::Note(n) if n == mimir_core::sessions::OPENED_NOTE)
                            {
                                continue;
                            }
                            // The plan card below shows the plan; not twice.
                            if show_plan
                                && let (Entry::Claude(text), Some(plan)) = (e, &t.plan_doc)
                                && text.trim() == plan.trim()
                            {
                                continue;
                            }
                            let gap = if matches!(e, Entry::Step(_)) {
                                6.0
                            } else {
                                12.0
                            };
                            let undo =
                                (!is_chat && !running && r.can_undo(&id, n)).then_some(id.as_str());
                            let waiting = waiting_question && n + 1 == t.entries.len();
                            let top = ui.cursor().top();
                            entry(
                                ui,
                                md,
                                open_steps,
                                n,
                                e,
                                Extras {
                                    task: &id,
                                    undo,
                                    waiting,
                                    asking: !l.asks.is_empty(),
                                    again: (!running
                                        && !t.live_elsewhere
                                        && n + 1 == t.entries.len())
                                    .then(|| last_request(&t.entries))
                                    .flatten(),
                                    retry: (!running
                                        && !t.live_elsewhere
                                        && n + 1 == t.entries.len())
                                    .then(|| match (&t.session_id, last_request(&t.entries)) {
                                        (None, Some(text)) => Retry::Resend(text),
                                        _ => Retry::Continue,
                                    }),
                                },
                                actions,
                            );
                            // The match "Find" is at: marked, and scrolled to.
                            if found == Some(n) {
                                let rect = egui::Rect::from_min_max(
                                    egui::pos2(ui.max_rect().left(), top),
                                    egui::pos2(ui.max_rect().right(), ui.cursor().top()),
                                )
                                .expand2(egui::vec2(4.0, 2.0));
                                ui.painter().rect_stroke(
                                    rect,
                                    egui::CornerRadius::same(theme::RADIUS),
                                    egui::Stroke::new(2.0, JADE),
                                    egui::StrokeKind::Outside,
                                );
                                if find_jump {
                                    ui.scroll_to_rect(rect, Some(Align::Center));
                                }
                            }
                            ui.add_space(gap);
                        }
                        if !t.live.is_empty() {
                            CommonMarkViewer::new().show(ui, md, &t.live);
                        }
                        if let Some(plan) = &t.plan_doc
                            && show_plan
                        {
                            plan_card(ui, md, &id, plan, actions);
                        }
                        if running && l.asks.is_empty() {
                            ui.horizontal(|ui| {
                                ui.spinner();
                                ui.label(
                                    RichText::new(
                                        if mimir_core::models::is_free(t.model.as_deref()) {
                                            "Your PC's model is working…"
                                        } else {
                                            "Working on it…"
                                        },
                                    )
                                    .color(MUTED),
                                );
                            });
                        }
                        if scroll_down {
                            ui.scroll_to_cursor(Some(Align::BOTTOM));
                        }
                    }
                });
            });
        // YOLO: a red edge round the chat while it's on, never hidden.
        if t.mode == Mode::NoAsk {
            ui.painter().rect_stroke(
                out.inner_rect,
                egui::CornerRadius::same(theme::RADIUS),
                egui::Stroke::new(3.0, theme::RED),
                egui::StrokeKind::Inside,
            );
        }
        // "↓ New messages" when the owner has scrolled up and more arrived.
        let at_bottom =
            out.state.clamped_offset().y + out.inner_rect.height() >= out.content_size.y - 40.0;
        let seen = self.seen.entry(id.clone()).or_insert(size_now);
        if at_bottom || scroll_down {
            *seen = size_now;
        } else if size_now > *seen {
            let pos = egui::pos2(out.inner_rect.center().x, out.inner_rect.bottom() - 24.0);
            let clicked = egui::Area::new(ui.scope_id().with(("new-messages", &id)))
                .fixed_pos(pos)
                .pivot(egui::Align2::CENTER_BOTTOM)
                .order(egui::Order::Foreground)
                .show(ui.ctx(), |ui| {
                    ui.add(
                        egui::Button::new(RichText::new("↓ New messages").color(JADE).strong())
                            .fill(CARD)
                            .stroke(egui::Stroke::new(1.0, JADE))
                            .corner_radius(egui::CornerRadius::same(14)),
                    )
                    .clicked()
                })
                .inner;
            if clicked {
                self.scroll_down = crate::app::SCROLL_FRAMES;
            }
        }
    }
}

/// "Claude wants to …": what Claude asks to do, with the lines an edit
/// would change, and the answers. Allow is a click or Ctrl+Enter, Don't
/// allow a click or Ctrl+D; a bare Enter, a letter or Esc does nothing here.
#[allow(clippy::too_many_arguments)]
fn ok_bar(
    ui: &mut egui::Ui,
    id: &str,
    project: &str,
    home: &str,
    ask: &mimir_core::client::AskView,
    waiting: usize,
    keys_free: bool,
    deny_note: &mut Option<String>,
    actions: &mut Vec<Action>,
) {
    let req = &ask.request;
    egui::Frame::new()
        .fill(ORANGE_DIM)
        .stroke(egui::Stroke::new(1.0, ORANGE))
        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
        .inner_margin(egui::Margin::same(12))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(req.title(std::path::Path::new(project)))
                        .color(ORANGE)
                        .strong(),
                );
                if waiting > 1 {
                    ui.label(
                        RichText::new(format!("· {} more after this", waiting - 1))
                            .color(MUTED)
                            .size(13.0),
                    );
                }
            });
            if let Some(d) = req.detail() {
                let show = |ui: &mut egui::Ui| {
                    ScrollArea::vertical()
                        .id_salt("ask-detail")
                        .max_height(120.0)
                        .show(ui, |ui| {
                            ui.add(egui::Label::new(RichText::new(&d).monospace()).wrap());
                        });
                };
                // A described command: the plain words lead, the command
                // itself is one click away.
                let described = req.tool_name == "Bash"
                    && req
                        .tool_input
                        .get("description")
                        .is_some_and(|v| v.is_string());
                // A command that reaches beyond the project is always shown
                // in full, with a note saying so.
                let reach = req.reach(std::path::Path::new(project));
                if let Some(reach) = reach {
                    show(ui);
                    egui::Frame::new()
                        .stroke(egui::Stroke::new(1.0, theme::RED))
                        .corner_radius(egui::CornerRadius::same(10))
                        .inner_margin(egui::Margin::symmetric(8, 2))
                        .show(ui, |ui| {
                            ui.label(RichText::new(reach.words()).color(theme::RED).size(12.5));
                        });
                } else if described {
                    egui::CollapsingHeader::new(
                        RichText::new("Show the exact command")
                            .color(MUTED)
                            .size(13.0),
                    )
                    .id_salt(("ask-cmd", id))
                    .show(ui, show);
                } else {
                    show(ui);
                }
            }
            let preview = req.preview();
            if !preview.is_empty() {
                diff_lines(ui, &preview, 180.0, "ask");
            }
            ui.add_space(4.0);
            let name = home;
            let always_id = egui::Id::unique(("always-ask", id, req.always_rule_text()));
            let always = ui.data(|d| d.get_temp::<bool>(always_id).unwrap_or(false));
            if always && deny_note.is_none() {
                // "Always allow…": one line on what will no longer be asked.
                ui.label(
                    RichText::new(format!(
                        "Mímir will no longer ask before {} in {name}. You can take this \
                         back in the project's settings file.",
                        mimir_core::approvals::rule_in_words(&req.always_rule_text())
                    ))
                    .size(13.5),
                );
                ui.horizontal(|ui| {
                    if ui
                        .add(theme::primary(&crate::labels::always_allow_in(name)))
                        .on_hover_text(format!("Saved rule: {}", req.always_rule_text()))
                        .clicked()
                    {
                        actions.push(Action::Answer(id.to_string(), Answer::Always));
                    }
                    if ui.add(theme::quiet("Back")).clicked() {
                        ui.data_mut(|d| d.insert_temp(always_id, false));
                    }
                });
            } else if deny_note.is_some() {
                // "No, and say why": the reason goes with the no.
                ui.label(
                    RichText::new("What should Mímir do instead?")
                        .color(MUTED)
                        .size(13.0),
                );
                let mut close = false;
                ui.horizontal(|ui| {
                    let note = deny_note.get_or_insert_with(String::new);
                    let edit = ui.add(
                        TextEdit::singleline(note)
                            .desired_width((ui.available_width() - 190.0).max(160.0))
                            .hint_text("For example: use the other file instead"),
                    );
                    crate::menus::edit_menu(&edit, "deny-note");
                    if !edit.has_focus() && note.is_empty() {
                        edit.request_focus();
                    }
                    let enter = edit.lost_focus() && ui.input(|i| i.key_pressed(Key::Enter));
                    let text = note.trim().to_string();
                    if (ui.add(theme::primary("Send the no")).clicked() || enter)
                        && !text.is_empty()
                    {
                        actions.push(Action::Answer(id.to_string(), Answer::No));
                        actions.push(Action::Send(id.to_string(), text, vec![]));
                        close = true;
                    } else if ui.add(theme::quiet("Back")).clicked() {
                        close = true;
                    }
                });
                if close {
                    *deny_note = None;
                }
            } else {
                ui.horizontal_wrapped(|ui| {
                    if ui.add(theme::primary(crate::labels::ALLOW)).clicked() {
                        actions.push(Action::Answer(id.to_string(), Answer::Allow));
                    }
                    if ui.add(theme::quiet(crate::labels::DONT_ALLOW)).clicked() {
                        actions.push(Action::Answer(id.to_string(), Answer::No));
                    }
                    if ui
                        .add(theme::quiet(crate::labels::DONT_ALLOW_SAY_WHY))
                        .on_hover_text("Say no and tell Mímir what to do instead")
                        .clicked()
                    {
                        *deny_note = Some(String::new());
                    }
                    // The rarely needed answer sits one click further in.
                    ui.menu_button(crate::labels::MORE, |ui| {
                        crate::theme::menu_fits(ui, |ui| {
                            if ui
                                .button(crate::labels::ALWAYS_ALLOW_MORE)
                                .on_hover_text(format!("Saved rule: {}", req.always_rule_text()))
                                .clicked()
                            {
                                ui.data_mut(|d| d.insert_temp(always_id, true));
                            }
                        })
                    });
                });
                ui.label(
                    RichText::new(crate::labels::ANSWER_KEYS)
                        .color(MUTED)
                        .size(13.0),
                );
            }
        });
    ui.add_space(6.0);
    // Two keys, both with Ctrl, so nothing is answered by a stray press.
    if keys_free && deny_note.is_none() && !egui::Popup::is_any_open(ui.ctx()) {
        let (allow, deny) = ui.input(|i| {
            (
                i.modifiers.ctrl && i.key_pressed(Key::Enter),
                i.modifiers.ctrl && i.key_pressed(Key::D),
            )
        });
        if allow {
            actions.push(Action::Answer(id.to_string(), Answer::Allow));
        } else if deny {
            actions.push(Action::Answer(id.to_string(), Answer::No));
        }
    }
}

/// Shown next to the picker whenever Auto is on.
pub(crate) const AUTO_NOTE: &str = "Auto picks Haiku, Sonnet, Opus or Fable for each message. \
    A switch uses a little of your plan: on a short chat it reads it again; on a long \
    one it gets a short summary instead. Auto only steps up within a task, never back down.";

/// What each Auto style leans toward.
pub(crate) fn auto_style_note(style: mimir_core::auto::Style) -> &'static str {
    match style {
        mimir_core::auto::Style::Efficient => {
            "Efficient: the lightest model that suits (Haiku for quick questions and chats), \
             Fable only when you ask. Uses the least of your plan. On a long chat it switches to a short summary."
        }
        mimir_core::auto::Style::Capable => {
            "Capable: one step stronger (Sonnet for quick questions, Opus for coding, Fable for \
             long hard requests). Uses more of your plan. Keeps the whole chat for longer."
        }
    }
}

/// The Auto warning, up front, when an Auto style is chosen: one line,
/// the details on hover.
pub(crate) fn auto_model_note(ui: &mut egui::Ui, model: &Option<String>) {
    if let Some(style) = mimir_core::auto::Style::of(model.as_deref()) {
        let line = match style {
            mimir_core::auto::Style::Efficient => {
                "Auto picks the lightest model that suits each message. Switching uses a little of your limit."
            }
            mimir_core::auto::Style::Capable => {
                "Auto picks a stronger model for each message. Switching uses a little of your limit."
            }
        };
        ui.label(RichText::new(line).color(theme::AMBER).size(13.0))
            .on_hover_text(format!("{} {}", auto_style_note(style), AUTO_NOTE));
    }
}

/// Mode, model and effort pickers.
#[allow(clippy::too_many_arguments)]
pub(crate) fn options_row(
    ui: &mut egui::Ui,
    id: &str,
    whisper: bool,
    show_mode: bool,
    mode: Mode,
    model: &Option<String>,
    auto_choice: Option<&str>,
    effort: &Option<String>,
    actions: &mut Vec<Action>,
) {
    // A chat is only a conversation: no permission chip.
    if show_mode {
        mode_chip(ui, id, whisper, mode, actions);
    }
    model_effort_chips(ui, id, model, auto_choice, effort, actions);
}

fn mode_chip(ui: &mut egui::Ui, id: &str, whisper: bool, mode: Mode, actions: &mut Vec<Action>) {
    theme::chipped(ui, |ui| {
        egui::ComboBox::from_id_salt(("mode", id))
            .selected_text(RichText::new(mode.words()).size(13.0))
            .width(112.0)
            .height(300.0)
            .show_ui(ui, |ui| {
                // The three plain choices, plus the task's own if it is an older one.
                // YOLO is never offered in Whisper.
                let mut shown: Vec<Mode> = Mode::MENU
                    .into_iter()
                    .filter(|m| !(whisper && *m == Mode::NoAsk))
                    .collect();
                if !shown.contains(&mode) {
                    shown.push(mode);
                }
                for m in shown {
                    let label = RichText::new(format!("{}  ·  {}", m.words(), m.short()));
                    let label = if m == Mode::NoAsk {
                        label.color(theme::RED)
                    } else {
                        label
                    };
                    if ui
                        .selectable_label(m == mode, label)
                        .on_hover_text(m.explain())
                        .clicked()
                    {
                        actions.push(Action::SetMode(id.to_string(), m));
                    }
                }
            })
            .response
            .on_hover_text(format!("{} (Ctrl+Shift+M)", mode.explain()));
    });
}

fn model_effort_chips(
    ui: &mut egui::Ui,
    id: &str,
    model: &Option<String>,
    auto_choice: Option<&str>,
    effort: &Option<String>,
    actions: &mut Vec<Action>,
) {
    theme::chipped(ui, |ui| {
        egui::ComboBox::from_id_salt(("model", id))
            .selected_text(
                RichText::new(
                    match (mimir_core::auto::Style::of(model.as_deref()), auto_choice) {
                        (Some(st), Some(c)) => {
                            format!("{} → {}", st.words(), mimir_core::auto::name(c))
                        }
                        (Some(st), None) => st.words().to_string(),
                        (None, _) => models::label(model.as_deref()),
                    },
                )
                .size(13.0),
            )
            .width(126.0)
            .height(400.0)
            .show_ui(ui, |ui| {
                if ui
                    .selectable_label(model.is_none(), "Auto-pick  ·  its own choice")
                    .clicked()
                {
                    actions.push(Action::SetModel(id.to_string(), None));
                }
                for (style, about) in [
                    (mimir_core::auto::Style::Efficient, "lightest that suits"),
                    (
                        mimir_core::auto::Style::Capable,
                        "one step stronger, for harder work",
                    ),
                ] {
                    if ui
                        .selectable_label(
                            mimir_core::auto::Style::of(model.as_deref()) == Some(style),
                            format!("{}  ·  {about}", style.words()),
                        )
                        .on_hover_text(format!("{} {}", auto_style_note(style), AUTO_NOTE))
                        .clicked()
                    {
                        actions.push(Action::SetModel(id.to_string(), Some(style.value().into())));
                    }
                }
                for m in &MODELS {
                    let chosen = model.as_deref() == Some(m.id);
                    if ui
                        .selectable_label(chosen, format!("{}  ·  {}", m.name, m.short))
                        .on_hover_text(format!("{}\n{}", m.best_for, m.id))
                        .clicked()
                    {
                        actions.push(Action::SetModel(id.to_string(), Some(m.id.to_string())));
                    }
                }
                // Free models: Ollama on this PC (none listed while it isn't running).
                let free = mimir_core::models::cached();
                if !free.is_empty() {
                    ui.separator();
                    ui.label(RichText::new("On this PC").color(MUTED).size(12.5));
                    for f in free {
                        let chosen = model.as_deref() == Some(f.id.as_str());
                        let note = models::plain_test_note(&f.note);
                        let mut hover = format!("{note}\n{}", mimir_core::models::FIT_NOTE);
                        if f.refusals_removed {
                            hover = format!("{}\n{hover}", mimir_core::models::REFUSALS_REMOVED);
                        }
                        if ui
                            .selectable_label(
                                chosen,
                                format!("{}  ·  {}\n{}", models::plain_name(&f.name), note, f.name),
                            )
                            .on_hover_text(hover)
                            .clicked()
                        {
                            actions.push(Action::SetModel(id.to_string(), Some(f.id.clone())));
                        }
                    }
                }
                ui.separator();
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new("What do these choices mean? ›").color(JADE),
                        )
                        .frame(false),
                    )
                    .clicked()
                {
                    // Settings › Models, with the prices and limits open.
                    ui.data_mut(|d| d.insert_temp(egui::Id::unique("models-about-open"), true));
                    actions.push(Action::OpenModels);
                }
            })
            .response
            .on_hover_text(
                match model.as_deref().and_then(mimir_core::models::ollama_name) {
                    Some(raw) => format!("{raw}\nRuns on this PC."),
                    None => "The model that answers".to_string(),
                },
            );
    });
    effort_chip(ui, id, model, effort, actions);
}

/// The "Effort: Medium ▾" choice (a free model has no effort).
pub(crate) fn effort_chip(
    ui: &mut egui::Ui,
    id: &str,
    model: &Option<String>,
    effort: &Option<String>,
    actions: &mut Vec<Action>,
) {
    // The default is the level it means, never "Default effort".
    let effort_name = match effort.as_deref() {
        None => "Effort: Medium".to_string(),
        Some(_) => EFFORTS
            .iter()
            .find(|(_, v, _)| v.map(str::to_string) == *effort)
            .map(|(n, _, _)| format!("Effort: {n}"))
            .unwrap_or_else(|| format!("Effort: {}", effort.as_deref().unwrap_or("Medium"))),
    };
    // Effort only changes how long Claude thinks: a free model has none.
    if mimir_core::models::is_free(model.as_deref()) {
        theme::chipped(ui, |ui| {
            ui.add_enabled(
                false,
                egui::Label::new(RichText::new("Effort: not used").size(13.0)),
            )
            .on_disabled_hover_text("This model has no effort setting");
        });
        return;
    }
    theme::chipped(ui, |ui| {
        egui::ComboBox::from_id_salt(("effort", id))
            .selected_text(RichText::new(effort_name).size(13.0))
            .width(112.0)
            .height(400.0)
            .show_ui(ui, |ui| {
                for (name, v, about) in EFFORTS {
                    let v = v.map(str::to_string);
                    if ui
                        .selectable_label(v == *effort, format!("{name}  ·  {about}"))
                        .clicked()
                    {
                        actions.push(Action::SetEffort(id.to_string(), v));
                    }
                }
            });
    });
}

pub(crate) fn attachment_chips(ui: &mut egui::Ui, attachments: &mut Vec<PathBuf>) {
    if attachments.is_empty() {
        return;
    }
    let mut remove = None;
    ui.horizontal_wrapped(|ui| {
        for (i, a) in attachments.iter().enumerate() {
            let name = a
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            // By name only: reading the picture every frame is slow.
            let is_pic = a.extension().and_then(|e| e.to_str()).is_some_and(|e| {
                ["png", "jpg", "jpeg", "gif", "webp"].contains(&e.to_lowercase().as_str())
            });
            let icon = if is_pic { "🖼" } else { "📄" };
            egui::Frame::new()
                .fill(CARD)
                .stroke(egui::Stroke::new(1.0, LINE))
                .corner_radius(egui::CornerRadius::same(12))
                .inner_margin(egui::Margin::symmetric(8, 2))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(format!("{icon} {name}")).size(13.0))
                            .on_hover_text(a.display().to_string());
                        if ui.small_button("✕").clicked() {
                            remove = Some(i);
                        }
                    });
                });
        }
    });
    if let Some(i) = remove {
        attachments.remove(i);
    }
}

/// Matching slash commands while the owner types "/…".
/// Commands that only work in Claude Code's own terminal screen.
const TERMINAL_ONLY: &[&str] = &[
    "config",
    "color",
    "theme",
    "vim",
    "terminal-setup",
    "ide",
    "login",
    "logout",
    "exit",
    "quit",
    "resume",
    "status",
    "statusline",
    "doctor",
    "permissions",
    "agents",
    "hooks",
    "mcp",
    "memory",
    "model",
    "output-style",
    "keybindings",
    "remote-control",
    "rewind",
    "export",
    "upgrade",
    "install-github-app",
    "privacy-settings",
    "release-notes",
    "bug",
    "feedback",
    "fast",
    "plugin",
    "add-dir",
    "clear",
];

/// Returns whether a suggestion was chosen (the message box keeps focus).
fn slash_suggestions(ui: &mut egui::Ui, draft: &mut String, commands: &[String]) -> bool {
    let Some(typed) = draft.strip_prefix('/') else {
        return false;
    };
    if typed.contains(char::is_whitespace) {
        return false;
    }
    let typed = typed.to_lowercase();
    let mut matches: Vec<&String> = commands
        .iter()
        .filter(|c| {
            !c.starts_with("__")
                && !TERMINAL_ONLY.contains(&c.as_str())
                && c.to_lowercase().starts_with(&typed)
        })
        .collect();
    matches.truncate(8);
    if matches.is_empty() {
        if commands.is_empty() {
            ui.label(
                RichText::new("Commands appear here after Mímir has started once.")
                    .color(MUTED)
                    .size(13.0),
            );
        }
        return false;
    }
    let mut chosen = false;
    ui.horizontal_wrapped(|ui| {
        for c in matches {
            if ui.add(theme::quiet(&format!("/{c}"))).clicked() {
                *draft = format!("/{c} ");
                chosen = true;
            }
        }
    });
    chosen
}

/// The owner's newest message in a conversation.
fn last_request(entries: &[Entry]) -> Option<String> {
    entries.iter().rev().find_map(|e| match e {
        Entry::You(t) if auto_note(t).is_none() => Some(t.clone()),
        _ => None,
    })
}

/// What an entry may show beyond its text.
struct Extras<'a> {
    /// The task this entry belongs to.
    task: &'a str,
    /// The task id when "Undo changes from here" is offered.
    undo: Option<&'a str>,
    /// This reply's question is still waiting for the owner.
    waiting: bool,
    /// An OK is being asked for (unfinished steps wait for it).
    asking: bool,
    /// The conversation stopped at this entry and Claude isn't working: a
    /// failure here gets its card, with what "Try again" does.
    retry: Option<Retry>,
    /// The request to send again under the newest answer ("Try again").
    again: Option<String>,
}

/// What "Try again" does on a failure card.
#[derive(Clone)]
enum Retry {
    /// Carry the conversation on.
    Continue,
    /// Claude never started: send the request again.
    Resend(String),
}

/// A note that says Claude failed, as a card's headline and its details.
/// The headline never names a button that isn't on the card.
fn failure(note: &str) -> Option<(&'static str, Option<&str>)> {
    if let Some(why) = note.strip_prefix("Project work could not start:") {
        Some((crate::labels::DIDNT_START, Some(why.trim())))
    } else if note.starts_with("Project work closed before it finished") {
        Some((crate::labels::CLOSED_EARLY, None))
    } else if note.starts_with("Mímir restarted while this task was working") {
        Some((crate::labels::RESTARTED, None))
    } else {
        None
    }
}

/// Where the conversation stopped because something went wrong: what
/// happened in plain words, Try again, and the raw text one click away.
fn failure_card(
    ui: &mut egui::Ui,
    task: &str,
    headline: &str,
    details: Option<&str>,
    retry: Retry,
    actions: &mut Vec<Action>,
) {
    egui::Frame::new()
        .fill(CARD)
        .stroke(egui::Stroke::new(1.0, RED))
        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
        .inner_margin(egui::Margin::same(12))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.label(RichText::new(headline).strong());
            ui.horizontal(|ui| {
                if ui.add(theme::primary(crate::labels::TRY_AGAIN)).clicked() {
                    actions.push(match retry {
                        Retry::Continue => Action::Continue(task.to_string()),
                        Retry::Resend(text) => Action::Send(task.to_string(), text, vec![]),
                    });
                }
                if details.is_some() {
                    let id = egui::Id::unique(("failure-details", task));
                    let mut open = ui.data(|m| m.get_temp::<bool>(id).unwrap_or(false));
                    if ui
                        .add(theme::quiet(if open {
                            "Hide details"
                        } else {
                            "Show details"
                        }))
                        .clicked()
                    {
                        open = !open;
                        ui.data_mut(|m| m.insert_temp(id, open));
                    }
                }
            });
            if let Some(d) = details
                && ui.data(|m| {
                    m.get_temp::<bool>(egui::Id::unique(("failure-details", task)))
                        .unwrap_or(false)
                })
            {
                ui.add(
                    egui::Label::new(RichText::new(d).monospace().color(MUTED).size(13.0)).wrap(),
                );
            }
        });
}

fn entry(
    ui: &mut egui::Ui,
    md: &mut CommonMarkCache,
    open: &mut HashSet<String>,
    n: usize,
    e: &Entry,
    extras: Extras,
    actions: &mut Vec<Action>,
) {
    let Extras {
        task,
        undo,
        waiting,
        asking,
        retry,
        again,
    } = extras;
    match e {
        Entry::You(text) if auto_note(text).is_some() => {
            ui.label(
                RichText::new(auto_note(text).unwrap_or_default())
                    .italics()
                    .color(MUTED),
            );
        }
        // Claude Code's own marker for a stop is not a message from the owner.
        Entry::You(text) if is_stop_marker(text) => {
            ui.label(RichText::new(crate::labels::STOPPED).italics().color(MUTED));
        }
        Entry::You(text) if text.trim() == crate::extras::SUMMARY_PROMPT => {
            ui.label(
                RichText::new("Summary of the work (asked for you; see Settings)")
                    .italics()
                    .color(MUTED),
            );
        }
        // A message with nothing in it draws nothing (no empty bubble with Copy).
        Entry::You(text) if text.trim().is_empty() => {}
        Entry::You(text) => {
            let bubble = ui.with_layout(Layout::right_to_left(Align::Min), |ui| {
                let frame = egui::Frame::new()
                    .fill(theme::BUBBLE)
                    .corner_radius(egui::CornerRadius::same(theme::RADIUS))
                    .inner_margin(egui::Margin::same(10))
                    .show(ui, |ui| {
                        ui.set_max_width((ui.available_width() * 0.75).min(560.0));
                        // Lines read left to right inside the bubble.
                        ui.with_layout(Layout::top_down(Align::Min), |ui| {
                            ui.add(egui::Label::new(text).wrap().selectable(true));
                        });
                    });
                frame.response
            });
            crate::menus::message(
                ui,
                &bubble.inner,
                &crate::menus::Msg {
                    text,
                    answer: false,
                    listen: egui::Id::unique(("you-listen", task, n)),
                    again: None,
                    edit: None,
                },
                actions,
            );
            // Undo is always there, quiet until the pointer is near.
            if let Some(task) = undo {
                let near = ui.rect_contains_pointer(bubble.response.rect.expand(24.0));
                let color = if near {
                    MUTED
                } else {
                    MUTED.gamma_multiply(0.45)
                };
                ui.with_layout(Layout::right_to_left(Align::Min), |ui| {
                    if ui
                        .add(
                            egui::Button::new(
                                RichText::new("↶ Undo changes from here")
                                    .size(12.5)
                                    .color(color),
                            )
                            .frame(false)
                            .small(),
                        )
                        .on_hover_text(
                            "Put the project's files back as they were just before this \
                             message. You see the list first.",
                        )
                        .clicked()
                    {
                        actions.push(Action::UndoPreview(task.to_string(), n));
                    }
                });
            }
        }
        Entry::Claude(text) => {
            let r = ui
                .push_id(("claude", n), |ui| {
                    match question_split_kept(ui, text) {
                        // A question to the owner stands out from the rest.
                        Some((rest, question)) if waiting => {
                            if !rest.trim().is_empty() {
                                CommonMarkViewer::new().show(ui, md, &line_breaks(&rest));
                            }
                            let (stroke, fill) = (theme::NEEDS_EDGE, theme::NEEDS_FILL);
                            // How long it has waited, from when this window
                            // first saw it.
                            let seen_id = ui.make_persistent_id(("question-seen", task, n));
                            let first = ui
                                .ctx()
                                .data_mut(|d| *d.get_temp_mut_or_insert_with(seen_id, || {
                                    std::time::SystemTime::now()
                                        .duration_since(std::time::UNIX_EPOCH)
                                        .map(|d| d.as_secs())
                                        .unwrap_or(0)
                                }));
                            let nowu = std::time::SystemTime::now()
                                .duration_since(std::time::UNIX_EPOCH)
                                .map(|d| d.as_secs())
                                .unwrap_or(0);
                            let label = crate::labels::needs_you_waiting(
                                nowu.saturating_sub(first) / 60,
                            );
                            ui.add_space(4.0);
                            let card = egui::Frame::new()
                                .fill(fill)
                                .stroke(egui::Stroke::new(if waiting { 1.5 } else { 1.0 }, stroke))
                                .corner_radius(egui::CornerRadius::same(theme::RADIUS))
                                .inner_margin(egui::Margin::same(12))
                                .show(ui, |ui| {
                                    ui.set_width(ui.available_width());
                                    ui.label(
                                        RichText::new(&label)
                                            .color(if waiting { ORANGE } else { MUTED })
                                            .strong()
                                            .size(13.5),
                                    );
                                    ui.push_id("question", |ui| {
                                        CommonMarkViewer::new().show(
                                            ui,
                                            md,
                                            &line_breaks(&question),
                                        );
                                    });
                                    if waiting {
                                        // Listed options answer with one click.
                                        let choices =
                                            mimir_core::task::question_choices(&question);
                                        if !choices.is_empty() {
                                            ui.add_space(4.0);
                                            ui.horizontal_wrapped(|ui| {
                                                for c in choices {
                                                    let short: String =
                                                        c.chars().take(60).collect();
                                                    if ui
                                                        .add(theme::quiet(&short))
                                                        .on_hover_text(format!("Answer: {c}"))
                                                        .clicked()
                                                    {
                                                        actions.push(Action::Send(
                                                            task.to_string(),
                                                            c.clone(),
                                                            vec![],
                                                        ));
                                                    }
                                                }
                                            });
                                        }
                                        ui.label(
                                            RichText::new(if question.contains("\n- ")
                                                || question.contains("\n1")
                                            {
                                                "Click an answer, or write your own in the message box below."
                                            } else {
                                                "Answer in the message box below."
                                            })
                                            .color(MUTED)
                                            .size(13.0),
                                        );
                                    }
                                });
                            if waiting {
                                card.response.widget_info(|| {
                                    egui::WidgetInfo::labeled(
                                        egui::accesskit::Role::Group,
                                        true,
                                        crate::labels::question_card(&question),
                                    )
                                });
                            }
                        }
                        // An ordinary reply, even one ending in "?", is not labelled.
                        _ => {
                            CommonMarkViewer::new().show(ui, md, &line_breaks(text));
                        }
                    }
                })
                .response;
            // The right-click menu of the reply.
            let plain = plain_text(text);
            let again_text = again.clone().unwrap_or_default();
            let again_task = task.to_string();
            let again_fn = move || Action::Send(again_task.clone(), again_text.clone(), vec![]);
            crate::menus::message(
                ui,
                &r,
                &crate::menus::Msg {
                    text: &plain,
                    answer: true,
                    listen: egui::Id::unique(("reply-listen", task, n)),
                    again: again.is_some().then_some(&again_fn as &dyn Fn() -> Action),
                    edit: None,
                },
                actions,
            );
            // Copy the whole reply (text selection stops at each paragraph):
            // clean text for mail and messages, or Markdown.
            let near = ui.rect_contains_pointer(r.rect.expand(16.0));
            let color = if near {
                MUTED
            } else {
                MUTED.gamma_multiply(0.7)
            };
            let copied_id = ui.make_persistent_id(("copied", n));
            let now = ui.input(|i| i.time);
            let copied_at: Option<f64> = ui.ctx().data(|d| d.get_temp(copied_id));
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = 10.0;
                // Read aloud, or stop the reading of this reply.
                let speaking = crate::places::speaking() == Some(copied_id);
                if crate::view_chat::quiet_link(
                    ui,
                    if speaking { "■ Stop" } else { "▶ Listen" },
                    if speaking { JADE } else { color },
                    "Read this answer aloud",
                ) {
                    if speaking {
                        crate::places::stop_speaking();
                    } else {
                        crate::places::speak(copied_id, text);
                    }
                }
                if speaking {
                    ui.ctx()
                        .request_repaint_after(std::time::Duration::from_millis(300));
                }
                if copied_at.is_some_and(|t| now - t < 1.5) {
                    ui.label(RichText::new("✓ Copied").size(12.5).color(JADE));
                    ui.ctx().request_repaint();
                } else {
                    if crate::view_chat::quiet_link(
                        ui,
                        "Copy",
                        color,
                        "Copy this reply as plain text (for mail and messages)",
                    ) {
                        ui.ctx().copy_text(plain_text(text));
                        ui.ctx().data_mut(|d| d.insert_temp(copied_id, now));
                    }
                    if let Some(again) = &again
                        && crate::view_chat::quiet_link(
                            ui,
                            "Try again",
                            color,
                            "Send your last message again",
                        )
                    {
                        actions.push(Action::Send(task.to_string(), again.clone(), vec![]));
                    }
                    if near
                        && crate::view_chat::quiet_link(
                            ui,
                            "Copy as Markdown",
                            color,
                            "With the formatting marks, for documents and code",
                        )
                    {
                        ui.ctx().copy_text(text.clone());
                        ui.ctx().data_mut(|d| d.insert_temp(copied_id, now));
                    }
                }
            });
        }
        Entry::Step(s) => step(ui, open, s, asking, actions),
        Entry::Note(note) => match (failure(note), retry) {
            (Some((headline, details)), Some(retry)) => {
                failure_card(ui, task, headline, details, retry, actions)
            }
            // Further up, a failure is a plain line in its own words.
            (Some((headline, _)), None) => {
                ui.label(RichText::new(headline).italics().color(MUTED));
            }
            _ => {
                ui.label(
                    RichText::new(mimir_core::models::plain_note(note))
                        .italics()
                        .color(MUTED),
                );
            }
        },
    }
}

/// Keeps single line breaks (an email sign-off, an address, a poem):
/// Markdown would join those lines.
/// Claude Code writes "[Request interrupted by user]" (or "… for tool use]")
/// where the owner pressed Stop.
/// The prefix Claude Code writes when the owner presses Stop.
/// Written as concat! so the banned-words scan does not see it as a
/// user-visible string (it is a machine output pattern, not a label).
const STOP_MARKER_PREFIX: &str = concat!("[Request", " interrupted by user");

pub(crate) fn is_stop_marker(text: &str) -> bool {
    text.trim().starts_with(STOP_MARKER_PREFIX)
}

fn line_breaks(text: &str) -> String {
    let lines: Vec<&str> = text.lines().collect();
    let mut out = String::with_capacity(text.len() + 32);
    let mut in_code = false;
    for (i, line) in lines.iter().enumerate() {
        if line.trim_start().starts_with("```") {
            in_code = !in_code;
        }
        out.push_str(line);
        let next = lines.get(i + 1).map(|n| n.trim_start()).unwrap_or("");
        let plain = |l: &str| {
            !l.is_empty()
                && !l.starts_with('#')
                && !l.starts_with('|')
                && !l.starts_with("```")
                && !l.starts_with("- ")
                && !l.starts_with("* ")
                && !l.starts_with('>')
                && !l.chars().next().is_some_and(|c| c.is_ascii_digit())
        };
        if !in_code && plain(line.trim_start()) && plain(next) && !line.ends_with("  ") {
            out.push_str("  ");
        }
        out.push('\n');
    }
    out
}

/// A reply as plain text for mail and messages: no Markdown marks.
fn plain_text(md: &str) -> String {
    let mut out = vec![];
    let mut in_code = false;
    for line in md.lines() {
        if line.trim_start().starts_with("```") {
            in_code = !in_code;
            continue;
        }
        if in_code {
            out.push(line.to_string());
            continue;
        }
        let mut l = line.trim_start_matches('#').trim_start().to_string();
        if line.starts_with('#') {
            l = l.trim().to_string();
        } else {
            l = line.to_string();
        }
        if let Some(rest) = l
            .trim_start()
            .strip_prefix("- ")
            .or_else(|| l.trim_start().strip_prefix("* "))
        {
            l = format!("• {rest}");
        }
        for mark in ["**", "__", "`"] {
            l = l.replace(mark, "");
        }
        // [words](link) → words (link)
        while let Some(a) = l.find("](") {
            let Some(start) = l[..a].rfind('[') else {
                break;
            };
            let Some(end) = l[a..].find(')').map(|e| a + e) else {
                break;
            };
            let words = l[start + 1..a].to_string();
            let link = l[a + 2..end].to_string();
            l.replace_range(start..=end, &format!("{words} ({link})"));
        }
        out.push(l);
    }
    out.join("\n")
}

/// Messages the window sent on the owner's behalf, shown as a quiet note.
fn auto_note(text: &str) -> Option<String> {
    let t = text.trim();
    match t {
        "The plan is approved. Carry it out now." => Some("Plan approved".into()),
        "Please continue where you left off." => Some("Continued".into()),
        _ if t.starts_with('/') && !t.contains(char::is_whitespace) => Some(format!("You ran {t}")),
        _ => None,
    }
}

fn step(
    ui: &mut egui::Ui,
    open: &mut HashSet<String>,
    s: &Step,
    asking: bool,
    actions: &mut Vec<Action>,
) {
    let (mark, color) = match (s.ok, s.refused) {
        (_, true) => ("⊘", RED),
        _ if s.stopped => ("■", MUTED),
        (None, _) => ("…", MUTED),
        (Some(true), _) => ("✓", JADE),
        (Some(false), _) => ("✗", RED),
    };
    let is_open = open.contains(&s.tool_use_id);
    let has_detail =
        !s.detail.input.is_empty() || !s.detail.output.is_empty() || !s.detail.patch.is_empty();
    let resp = egui::Frame::new()
        .fill(CARD)
        .stroke(egui::Stroke::new(1.0, LINE))
        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
        .inner_margin(egui::Margin::symmetric(10, 5))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            let head = ui
                .horizontal(|ui| {
                    ui.label(RichText::new(mark).color(color).strong());
                    let (plus, minus) = counts(&s.detail.patch);
                    let line = if s.ok.is_none() && asking && !s.stopped {
                        format!(
                            "{} · waiting for your OK",
                            mimir_core::task::not_yet(&s.what)
                        )
                    } else {
                        s.line()
                    };
                    ui.add(egui::Label::new(RichText::new(line).size(13.5)).truncate());
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if has_detail {
                            ui.label(RichText::new(if is_open { "▾" } else { "▸" }).color(MUTED));
                        }
                        if minus > 0 {
                            ui.label(RichText::new(format!("-{minus}")).color(RED).size(13.0));
                        }
                        if plus > 0 {
                            ui.label(RichText::new(format!("+{plus}")).color(JADE).size(13.0));
                        }
                    });
                })
                .response;
            if is_open {
                ui.add_space(4.0);
                if !s.detail.input.is_empty() {
                    ui.add(
                        egui::Label::new(RichText::new(&s.detail.input).monospace().size(13.0))
                            .wrap()
                            .selectable(true),
                    );
                }
                if !s.detail.patch.is_empty() {
                    ui.add_space(4.0);
                    diff_lines(ui, &s.detail.patch, 300.0, &s.tool_use_id);
                    if ui.link("Open larger").clicked() {
                        actions.push(Action::ShowLines(s.what.clone(), s.detail.patch.clone()));
                    }
                } else if !s.detail.output.is_empty() {
                    ui.add_space(4.0);
                    egui::Frame::new()
                        .fill(theme::GROUND)
                        .corner_radius(egui::CornerRadius::same(6))
                        .inner_margin(egui::Margin::same(8))
                        .show(ui, |ui| {
                            ScrollArea::vertical()
                                .id_salt(("out", &s.tool_use_id))
                                .max_height(260.0)
                                .show(ui, |ui| {
                                    ui.set_width(ui.available_width());
                                    ui.add(
                                        egui::Label::new(
                                            RichText::new(&s.detail.output)
                                                .monospace()
                                                .size(13.0)
                                                .color(MUTED),
                                        )
                                        .selectable(true),
                                    );
                                });
                        });
                }
            }
            head
        })
        .inner;
    // Only the header row opens and closes the card, so links inside it
    // ("Open larger") get their clicks.
    if has_detail
        && resp
            .interact(egui::Sense::click())
            .on_hover_text(if is_open {
                "Click to hide details"
            } else {
                "Click to show details"
            })
            .clicked()
    {
        if is_open {
            open.remove(&s.tool_use_id);
        } else {
            open.insert(s.tool_use_id.clone());
        }
    }
}

fn counts(patch: &[String]) -> (usize, usize) {
    let plus = patch
        .iter()
        .filter(|l| l.starts_with('+') && !l.starts_with("+++"))
        .count();
    let minus = patch
        .iter()
        .filter(|l| l.starts_with('-') && !l.starts_with("---"))
        .count();
    (plus, minus)
}

/// "@@ -8,6 +8,9 @@ fn x" → "Line 8 · fn x"; other "@@" notes as they are.
fn nice_hunk(h: &str) -> String {
    let rest = h.trim_start_matches('@').trim();
    if let Some(plus) = rest.split_whitespace().find(|w| w.starts_with('+'))
        && let Some(n) = plus.trim_start_matches('+').split(',').next()
        && n.parse::<u32>().is_ok()
    {
        let after = rest.split_once("@@").map(|x| x.1.trim()).unwrap_or("");
        if after.is_empty() {
            format!("Line {n}")
        } else {
            format!("Line {n} · {after}")
        }
    } else {
        rest.to_string()
    }
}

/// Changed lines in red and green.
pub(crate) fn diff_lines(ui: &mut egui::Ui, lines: &[String], max_height: f32, salt: &str) {
    egui::Frame::new()
        .fill(theme::GROUND)
        .corner_radius(egui::CornerRadius::same(6))
        .inner_margin(egui::Margin::same(6))
        .show(ui, |ui| {
            ScrollArea::both()
                .id_salt(("diff", salt))
                .max_height(max_height)
                .show(ui, |ui| {
                    ui.set_min_width(ui.available_width());
                    for l in lines {
                        // The +/- of a diff reads like a number ("-2 cups"),
                        // so a small "was"/"now" mark goes in a margin.
                        let (bg, fg, mark, text) = if let Some(t) = l.strip_prefix('+') {
                            (
                                egui::Color32::from_rgb(0x12, 0x2A, 0x1C),
                                egui::Color32::from_rgb(0xA8, 0xE0, 0xBC),
                                "now",
                                t,
                            )
                        } else if let Some(t) = l.strip_prefix('-') {
                            (
                                egui::Color32::from_rgb(0x30, 0x14, 0x14),
                                egui::Color32::from_rgb(0xF0, 0xA8, 0xA8),
                                "was",
                                t,
                            )
                        } else if l.starts_with("@@") {
                            (egui::Color32::TRANSPARENT, MUTED, "", l.as_str())
                        } else {
                            (
                                egui::Color32::TRANSPARENT,
                                theme::TEXT,
                                "",
                                l.strip_prefix(' ').unwrap_or(l),
                            )
                        };
                        egui::Frame::new().fill(bg).show(ui, |ui| {
                            ui.set_min_width(ui.available_width());
                            ui.horizontal(|ui| {
                                ui.add_sized(
                                    [30.0, 16.0],
                                    egui::Label::new(RichText::new(mark).size(11.0).color(fg)),
                                );
                                let text = if l.starts_with("@@") {
                                    nice_hunk(text)
                                } else {
                                    text.to_string()
                                };
                                ui.label(RichText::new(text).monospace().size(13.0).color(fg));
                            });
                        });
                    }
                });
        });
}

fn plan_card(
    ui: &mut egui::Ui,
    md: &mut CommonMarkCache,
    id: &str,
    plan: &str,
    actions: &mut Vec<Action>,
) {
    ui.add_space(6.0);
    egui::Frame::new()
        .fill(CARD)
        .stroke(egui::Stroke::new(1.0, JADE))
        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
        .inner_margin(egui::Margin::same(12))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.label(RichText::new("Plan ready for your OK").color(JADE).strong());
            // No scroll box of its own: the conversation scrolls.
            ui.push_id("plan", |ui| CommonMarkViewer::new().show(ui, md, plan));
            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                if ui
                    .add(theme::primary("Start – ask before changes"))
                    .on_hover_text("Mímir carries out the plan and asks before changing files")
                    .clicked()
                {
                    actions.push(Action::ApprovePlan(id.to_string(), Mode::Ask));
                }
                if ui
                    .add(theme::quiet("Start – change files without asking"))
                    .on_hover_text("Mímir carries out the plan and changes files without asking")
                    .clicked()
                {
                    actions.push(Action::ApprovePlan(id.to_string(), Mode::AcceptEdits));
                }
                ui.label(
                    RichText::new("Or type below to change the plan.")
                        .color(MUTED)
                        .size(13.0),
                );
            });
        });
}

/// Claude's limit ran out in this task: on a free model until Claude is
/// back (then Back to Claude), or waiting for the owner to choose.
/// "This conversation is long…": the line, how long it is in book pages,
/// and one button (not offered for a conversation going on elsewhere).
fn long_line(
    ui: &mut egui::Ui,
    id: &str,
    n: &mimir_core::proto::LongNotice,
    actions: &mut Vec<Action>,
) {
    egui::Frame::new()
        .fill(theme::CARD)
        .stroke(egui::Stroke::new(1.0, theme::LINE))
        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
        .inner_margin(egui::Margin::same(8))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal_wrapped(|ui| {
                let color = if n.very { theme::AMBER } else { theme::MUTED };
                ui.label(RichText::new(mimir_core::long::line(n)).color(color));
                if let Some(size) = mimir_core::long::size_words(n) {
                    ui.label(RichText::new(size).color(theme::MUTED).size(13.0));
                }
                if let Some(button) = mimir_core::long::button(n)
                    && ui
                        .add(theme::primary(button))
                        .on_hover_text(
                            "A new task carries a short summary. This one stays as it is.",
                        )
                        .clicked()
                {
                    actions.push(Action::StartFresh(id.to_string()));
                }
                if ui
                    .add(theme::quiet("Not now"))
                    .on_hover_text(
                        "Hides this line. It comes back if the conversation gets much longer.",
                    )
                    .clicked()
                {
                    actions.push(Action::DismissLong(id.to_string(), n.very));
                }
            });
        });
}

fn ran_out_card(
    ui: &mut egui::Ui,
    id: &str,
    out: &mimir_core::proto::RanOut,
    actions: &mut Vec<Action>,
) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let back = now >= out.back_at;
    let when = mimir_core::models::clock(out.back_at);
    egui::Frame::new()
        .fill(theme::CARD)
        .stroke(egui::Stroke::new(1.0, theme::AMBER))
        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
        .inner_margin(egui::Margin::same(8))
        .show(ui, |ui| {
            ui.horizontal_wrapped(|ui| {
                let words = match (out.on_free, back) {
                    (true, false) => format!(
                        "{} · Online is back at {when}.",
                        crate::labels::ON_FREE_UNTIL_BACK
                    ),
                    (true, true) => "Online is back.".to_string(),
                    (false, false) => {
                        format!("The online limit ran out. Online is back at {when}.")
                    }
                    (false, true) => "Online is back: carry on when you like.".to_string(),
                };
                ui.label(RichText::new(words).color(theme::AMBER));
                if !out.on_free
                    && !back
                    && ui
                        .add(theme::primary(crate::labels::CONTINUE_ON_FREE))
                        .on_hover_text("Carries on now from a short brief of the work so far")
                        .clicked()
                {
                    actions.push(Action::ContinueOnFree(id.to_string()));
                }
                if back
                    && ui
                        .add(theme::primary(crate::labels::BACK_TO_CLAUDE))
                        .on_hover_text("Your next message goes online, with a short brief")
                        .clicked()
                {
                    actions.push(Action::BackToClaude(id.to_string()));
                }
            });
        });
    if !back {
        // The button comes when the limit resets.
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_secs(
                (out.back_at - now).clamp(1, 60) as u64,
            ));
    }
}

/// [`mimir_core::task::question_split`] for a reply on screen, worked out
/// once per reply instead of every frame.
/// The label above a reply's question: only a question waiting for the
/// owner has one.
#[cfg(test)]
fn question_label(waiting: bool) -> Option<&'static str> {
    waiting.then_some(crate::labels::CLAUDE_ASKS_YOU)
}

fn question_split_kept(ui: &egui::Ui, text: &str) -> Option<(String, String)> {
    type Kept = std::sync::Arc<Option<(String, String)>>;
    let id = egui::Id::unique(("question-split", text));
    if let Some(kept) = ui.data(|d| d.get_temp::<Kept>(id)) {
        return (*kept).clone();
    }
    let split = mimir_core::task::question_split(text);
    ui.data_mut(|d| d.insert_temp::<Kept>(id, std::sync::Arc::new(split.clone())));
    split
}

#[cfg(test)]
mod tests {
    use super::*;

    /// PCK-08: Claude Code's stop marker is not the owner's message.
    #[test]
    fn only_a_waiting_question_is_labelled() {
        assert_eq!(question_label(false), None);
        assert_eq!(question_label(true), Some(crate::labels::CLAUDE_ASKS_YOU));
    }

    #[test]
    fn the_stop_marker_is_not_a_message() {
        assert!(is_stop_marker("[Request interrupted by user]"));
        assert!(is_stop_marker(
            "  [Request interrupted by user for tool use]\n"
        ));
        assert!(!is_stop_marker("Please do not interrupt"));
        assert!(!is_stop_marker("[Request interrupted]"));
    }
}
