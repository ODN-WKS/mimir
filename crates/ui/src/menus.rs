//! Right-click menus, one style everywhere (owner: "wherever I right-click
//! in the PC app I get a menu with the common things"). The look: dark
//! ground, thin border, rounded rows, the shortcut dim on the right, a
//! divider between groups, the red destructive items last.
//!
//! The pieces:
//! - [`open`] shows a menu for a response (a row, a card) and [`open_in`]
//!   for a whole area (a message, the empty middle);
//! - [`item`], [`danger`] and [`sep`] draw the rows;
//! - [`edit_menu`] is the typing box's menu (Cut, Copy, Paste, ...);
//! - [`message`] is the menu of a message, with "Select all" and the
//!   selected words in Copy, Quote and Search.

use crate::app::Action;
use eframe::egui::{
    self, Color32, Event, Id, Key, Modifiers, OutputCommand, Popup, Response, Sense,
    SetOpenCommand, text_selection::LabelSelectionState,
};

pub const BG: Color32 = Color32::from_rgb(0x1B, 0x1E, 0x25);
pub const EDGE: Color32 = Color32::from_rgb(0x3A, 0x3F, 0x49);
pub const RED: Color32 = Color32::from_rgb(0xF2, 0x8B, 0x82);
const ROW_HOVER: Color32 = Color32::from_rgb(0x2B, 0x30, 0x3B);
const HINT: Color32 = Color32::from_rgb(0x7D, 0x85, 0x92);
const TEXT: Color32 = Color32::from_rgb(0xE3, 0xE6, 0xEB);
const OFF: Color32 = Color32::from_rgb(0x6A, 0x70, 0x7B);
const ROW_H: f32 = 28.0;
/// The widest a menu grows, and the narrowest it starts.
const MIN_W: f32 = 230.0;

/// How a row looks and what it does.
#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Normal,
    Danger,
    Off,
}

/// The popup's own look (the border, the round corners).
fn frame() -> egui::Frame {
    egui::Frame::new()
        .fill(BG)
        .stroke(egui::Stroke::new(1.0, EDGE))
        .corner_radius(egui::CornerRadius::same(10))
        .inner_margin(egui::Margin::same(5))
        .shadow(egui::Shadow {
            offset: [0, 6],
            blur: 18,
            spread: 0,
            color: Color32::from_black_alpha(110),
        })
}

fn row(ui: &mut egui::Ui, label: &str, hint: &str, kind: Kind) -> bool {
    let w = ui.available_width().clamp(MIN_W, 480.0);
    let sense = if kind == Kind::Off {
        Sense::hover()
    } else {
        Sense::click()
    };
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(w, ROW_H), sense);
    resp.widget_info(|| {
        egui::WidgetInfo::labeled(egui::accesskit::Role::Button, kind != Kind::Off, label)
    });
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        if resp.hovered() && kind != Kind::Off {
            p.rect_filled(rect, egui::CornerRadius::same(8), ROW_HOVER);
        }
        let color = match kind {
            Kind::Normal => TEXT,
            Kind::Danger => RED,
            Kind::Off => OFF,
        };
        let font = egui::FontId::proportional(14.0);
        let g = p.layout_no_wrap(label.to_string(), font.clone(), color);
        p.galley(
            egui::pos2(rect.left() + 10.0, rect.center().y - g.size().y / 2.0),
            g,
            color,
        );
        if !hint.is_empty() {
            let h = p.layout_no_wrap(
                hint.to_string(),
                egui::FontId::proportional(12.5),
                if kind == Kind::Off { OFF } else { HINT },
            );
            p.galley(
                egui::pos2(
                    rect.right() - 10.0 - h.size().x,
                    rect.center().y - h.size().y / 2.0,
                ),
                h,
                HINT,
            );
        }
    }
    if kind != Kind::Off {
        resp.clone().on_hover_cursor(egui::CursorIcon::PointingHand);
    }
    let clicked = kind != Kind::Off && resp.clicked();
    if clicked {
        ui.close();
    }
    clicked
}

/// A menu row: its words, and the shortcut that does the same ("" for none).
pub fn item(ui: &mut egui::Ui, label: &str, hint: &str) -> bool {
    row(ui, label, hint, Kind::Normal)
}

/// A row that is there but cannot be used now.
pub fn item_if(ui: &mut egui::Ui, on: bool, label: &str, hint: &str) -> bool {
    // The hint says why it can't be used, so it shows only then.
    row(
        ui,
        label,
        if on { "" } else { hint },
        if on { Kind::Normal } else { Kind::Off },
    )
}

/// A destructive row: red, and last in its menu.
pub fn danger(ui: &mut egui::Ui, label: &str, hint: &str) -> bool {
    row(ui, label, hint, Kind::Danger)
}

/// The divider between two groups.
pub fn sep(ui: &mut egui::Ui) {
    let (rect, _) = ui.allocate_exact_size(
        egui::vec2(ui.available_width().max(MIN_W), 9.0),
        Sense::hover(),
    );
    let y = rect.center().y;
    ui.painter().line_segment(
        [
            egui::pos2(rect.left() + 6.0, y),
            egui::pos2(rect.right() - 6.0, y),
        ],
        egui::Stroke::new(1.0, EDGE),
    );
}

/// What is inside every menu: the one style, and room for a short window.
fn fill(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    ui.spacing_mut().item_spacing.y = 1.0;
    ui.set_min_width(MIN_W);
    crate::theme::menu_fits(ui, add);
}

/// The pass in which a menu was asked for, so the empty-space menu keeps
/// quiet when a more specific one opened.
fn claim(ctx: &egui::Context) {
    let now = ctx.cumulative_pass_nr();
    ctx.data_mut(|d| d.insert_temp(Id::unique("menu-claimed"), now));
}

fn claimed(ctx: &egui::Context) -> bool {
    ctx.data(|d| d.get_temp::<u64>(Id::unique("menu-claimed"))) == Some(ctx.cumulative_pass_nr())
}

/// The right-click menu of a widget that senses clicks (a row, a card).
pub fn open(resp: &Response, add: impl FnOnce(&mut egui::Ui)) {
    if resp.secondary_clicked() {
        claim(&resp.ctx);
    }
    Popup::context_menu(resp)
        .frame(frame())
        .show(|ui| fill(ui, add));
}

/// The right-click menu of a whole area, even where a child widget took
/// the click (a message with selectable words in it).
pub fn open_in(area: &Response, add: impl FnOnce(&mut egui::Ui)) {
    open_area(area, true, add)
}

fn open_area(area: &Response, claim_it: bool, add: impl FnOnce(&mut egui::Ui)) {
    let clicked = area.container_secondary_clicked();
    if clicked && claim_it {
        claim(&area.ctx);
    }
    Popup::menu(area)
        .open_memory(if clicked && (claim_it || !claimed(&area.ctx)) {
            Some(SetOpenCommand::Bool(true))
        } else if area.container_clicked() {
            Some(SetOpenCommand::Bool(false))
        } else {
            None
        })
        .at_pointer_fixed()
        .frame(frame())
        .show(|ui| fill(ui, add));
}

/// The clipboard's words (a made-up clipboard stands in while testing).
pub fn clipboard_text(ctx: &egui::Context) -> Option<String> {
    if let Some(t) = ctx.data(|d| d.get_temp::<String>(Id::unique("test-clipboard"))) {
        return Some(t).filter(|t| !t.is_empty());
    }
    arboard::Clipboard::new()
        .ok()?
        .get_text()
        .ok()
        .filter(|t| !t.is_empty())
}

/// A picture on the clipboard (a made-up one stands in while testing).
pub fn clipboard_image(ctx: &egui::Context) -> Option<std::sync::Arc<egui::ColorImage>> {
    if let Some(i) = ctx.data(|d| {
        d.get_temp::<std::sync::Arc<egui::ColorImage>>(Id::unique("test-clipboard-image"))
    }) {
        return Some(i);
    }
    let img = arboard::Clipboard::new().ok()?.get_image().ok()?;
    if img.width == 0 || img.height == 0 {
        return None;
    }
    Some(std::sync::Arc::new(
        egui::ColorImage::from_rgba_unmultiplied([img.width, img.height], &img.bytes),
    ))
}

/// Hand a clipboard picture to the window, which attaches it the way
/// Ctrl V does.
fn paste_image(ctx: &egui::Context, img: std::sync::Arc<egui::ColorImage>) {
    ctx.data_mut(|d| d.insert_temp(Id::unique("pending-paste-image"), img));
    ctx.request_repaint();
}

/// A picture the menu's Paste handed over, if any (taken once).
pub fn take_pasted_image(ctx: &egui::Context) -> Option<std::sync::Arc<egui::ColorImage>> {
    ctx.data_mut(|d| {
        d.remove_temp::<std::sync::Arc<egui::ColorImage>>(Id::unique("pending-paste-image"))
    })
}

// ---------------------------------------------------------------------
// The typing box
// ---------------------------------------------------------------------

#[derive(Clone)]
enum Edit {
    Cut,
    Copy,
    Paste(String),
    SelectAll,
    Undo,
    Redo,
}

fn key(key: Key, modifiers: Modifiers) -> Event {
    Event::Key {
        key,
        physical_key: Some(key),
        pressed: true,
        repeat: false,
        modifiers,
    }
}

/// Call just before the box is drawn; `slot` names the box (its salt). A
/// choice made last frame in the box's menu (Cut, Paste, ...) is done now,
/// with the cursor back in the box.
pub fn before_edit(ctx: &egui::Context, slot: &str, text: &mut String) {
    let slot = Id::unique(("edit-slot", slot));
    // What is selected before this frame's click can change it: egui moves
    // the cursor on a right-click too, and the menu wants the selection.
    if let Some(id) = ctx.data(|d| d.get_temp::<Id>(slot.with("id"))) {
        let range = egui::TextEdit::load_state(ctx, id).and_then(|s| s.cursor.char_range());
        ctx.data_mut(|d| d.insert_temp(slot.with("before"), range));
    }
    let pending_id = Id::unique("edit-pending");
    let Some((at, what)) = ctx.data(|d| d.get_temp::<(Id, Edit)>(pending_id)) else {
        return;
    };
    if at != slot {
        return;
    }
    // The box's own id, as seen when it was last drawn.
    let Some(id) = ctx.data(|d| d.get_temp::<Id>(slot.with("id"))) else {
        return;
    };
    ctx.data_mut(|d| d.remove::<(Id, Edit)>(pending_id));
    ctx.memory_mut(|m| m.request_focus(id));
    // The box forgets its selection while it has no cursor, so Cut, Copy
    // and Paste work on the selection the menu opened with.
    let range = ctx
        .data(|d| d.get_temp::<Option<egui::text_selection::CCursorRange>>(slot.with("range")))
        .flatten();
    let len = text.chars().count();
    let (lo, hi) = range
        .map(|r| {
            let [a, b] = r.sorted_cursors();
            (a.index.0.min(len), b.index.0.min(len))
        })
        .unwrap_or((len, len));
    let byte = |n: usize| text.char_indices().nth(n).map_or(text.len(), |(i, _)| i);
    let caret = match what {
        Edit::Copy => {
            ctx.copy_text(text[byte(lo)..byte(hi)].to_string());
            Some(hi)
        }
        Edit::Cut => {
            ctx.copy_text(text[byte(lo)..byte(hi)].to_string());
            text.replace_range(byte(lo)..byte(hi), "");
            Some(lo)
        }
        Edit::Paste(t) => {
            text.replace_range(byte(lo)..byte(hi), &t);
            Some(lo + t.chars().count())
        }
        Edit::SelectAll => {
            ctx.input_mut(|i| i.events.push(key(Key::A, Modifiers::COMMAND)));
            None
        }
        Edit::Undo => {
            ctx.input_mut(|i| i.events.push(key(Key::Z, Modifiers::COMMAND)));
            None
        }
        Edit::Redo => {
            ctx.input_mut(|i| {
                i.events
                    .push(key(Key::Z, Modifiers::COMMAND | Modifiers::SHIFT))
            });
            None
        }
    };
    if let Some(at) = caret
        && let Some(mut state) = egui::TextEdit::load_state(ctx, id)
    {
        let c = egui::text_selection::CCursorRange::one(egui::text::CCursor::new(at));
        state.cursor.set_char_range(Some(c));
        state.store(ctx, id);
    }
}

fn queue(ctx: &egui::Context, slot: Id, what: Edit) {
    ctx.data_mut(|d| d.insert_temp(Id::unique("edit-pending"), (slot, what)));
    ctx.request_repaint();
}

/// The menu of a typing box: Cut, Copy, Paste, Paste as plain text,
/// Select all, Undo, Redo. `edit` is the box's response, `slot` its salt.
pub fn edit_menu(edit: &Response, slot: &str) {
    edit_menu_for(edit, slot, false);
}

/// The same menu for the message box: with only a picture on the
/// clipboard, Paste attaches it to the message.
pub fn message_edit_menu(edit: &Response, slot: &str) {
    edit_menu_for(edit, slot, true);
}

fn edit_menu_for(edit: &Response, slot: &str, takes_pictures: bool) {
    let ctx = edit.ctx.clone();
    let id = edit.id;
    let slot = Id::unique(("edit-slot", slot));
    ctx.data_mut(|d| d.insert_temp(slot.with("id"), id));
    // A right-click on the box keeps what was selected.
    if edit.hovered() && ctx.input(|i| i.pointer.button_pressed(egui::PointerButton::Secondary)) {
        let before = ctx
            .data(|d| d.get_temp::<Option<egui::text_selection::CCursorRange>>(slot.with("before")))
            .flatten();
        if let (Some(r), Some(mut state)) = (before, egui::TextEdit::load_state(&ctx, id))
            && r.primary != r.secondary
        {
            state.cursor.set_char_range(Some(r));
            state.store(&ctx, id);
        }
    }
    let (sel_id, clip_id) = (id.with("menu-selection"), id.with("menu-clip"));
    if edit.secondary_clicked() {
        // What is selected and what is on the clipboard, as the menu opens.
        let range = egui::TextEdit::load_state(&ctx, id).and_then(|s| s.cursor.char_range());
        let selected = range.is_some_and(|r| r.primary != r.secondary);
        let clip = clipboard_text(&ctx);
        // The picture is read only when there are no words, and only for
        // the message box (it is the one that can hold a picture).
        let pic = if takes_pictures && clip.is_none() {
            clipboard_image(&ctx)
        } else {
            None
        };
        ctx.data_mut(|d| {
            d.insert_temp(slot.with("range"), range);
            d.insert_temp(sel_id, selected);
            d.insert_temp(clip_id, clip);
            d.insert_temp(slot.with("menu-pic"), pic);
        });
    }
    open(edit, |ui| {
        let selected = ui.data(|d| d.get_temp::<bool>(sel_id)).unwrap_or(false);
        let clip: Option<String> = ui.data(|d| d.get_temp::<Option<String>>(clip_id)).flatten();
        if item_if(ui, selected, "Cut", "Ctrl X") {
            queue(&ctx, slot, Edit::Cut);
        }
        if item_if(ui, selected, "Copy", "Ctrl C") {
            queue(&ctx, slot, Edit::Copy);
        }
        let pic: Option<std::sync::Arc<egui::ColorImage>> = ui
            .data(|d| d.get_temp::<Option<std::sync::Arc<egui::ColorImage>>>(slot.with("menu-pic")))
            .flatten();
        if item_if(ui, clip.is_some() || pic.is_some(), "Paste", "Ctrl V") {
            match (&clip, pic) {
                (Some(t), _) => queue(&ctx, slot, Edit::Paste(t.clone())),
                (None, Some(img)) => paste_image(&ctx, img),
                (None, None) => {}
            }
        }
        if item_if(ui, clip.is_some(), "Paste as plain text", "") {
            // Words only: no formatting marks, and one kind of line end.
            let plain = clip.clone().unwrap_or_default().replace("\r\n", "\n");
            queue(&ctx, slot, Edit::Paste(plain));
        }
        if item(ui, "Select all", "Ctrl A") {
            queue(&ctx, slot, Edit::SelectAll);
        }
        sep(ui);
        if item(ui, "Undo", "Ctrl Z") {
            queue(&ctx, slot, Edit::Undo);
        }
        if item(ui, "Redo", "Ctrl Shift Z") {
            queue(&ctx, slot, Edit::Redo);
        }
    });
}

/// Notes that the message box has the cursor and nothing in it: Delete
/// then asks to delete the open chat or task instead of doing nothing.
pub fn note_empty_box(edit: &Response, text: &str) {
    if edit.has_focus() && text.is_empty() {
        let now = edit.ctx.cumulative_pass_nr();
        edit.ctx
            .data_mut(|d| d.insert_temp(Id::unique("empty-box"), now));
    }
}

/// Whether a box with the cursor in it should keep the keys to itself.
pub fn box_wants_keys(ctx: &egui::Context) -> bool {
    let empty = ctx
        .data(|d| d.get_temp::<u64>(Id::unique("empty-box")))
        .is_some_and(|at| ctx.cumulative_pass_nr().saturating_sub(at) <= 2);
    ctx.egui_wants_keyboard_input() && !empty
}

// ---------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------

/// Notes the words of a label selection when it settles (egui sends them
/// out when the pointer lets go); a menu cannot ask for them later,
/// because pressing the menu ends the selection.
#[derive(Default)]
struct Tracker {
    words: String,
    /// The message the pointer was over when it settled.
    over: Option<Id>,
}

impl egui::Plugin for Tracker {
    fn debug_name(&self) -> &'static str {
        "mimir-selection"
    }
    fn output_hook(&mut self, ctx: &egui::Context, output: &mut egui::FullOutput) {
        for c in &output.platform_output.commands {
            if let OutputCommand::TextSelectionSettled(t) = c {
                self.words = t.clone();
                self.over = hovered_now(ctx).map(|(id, _)| id);
            }
        }
    }
}

/// Starts the notes above; call every frame, it does nothing twice.
pub fn install(ctx: &egui::Context) {
    ctx.add_plugin(Tracker::default());
}

fn hovered_now(ctx: &egui::Context) -> Option<(Id, String)> {
    let (at, id, text) = ctx.data(|d| d.get_temp::<(u64, Id, String)>(Id::unique("msg-hover")))?;
    (ctx.cumulative_pass_nr().saturating_sub(at) <= 2).then_some((id, text))
}

/// The words selected in one message, if the selection was made there.
fn selected_words(ctx: &egui::Context, msg: Id) -> Option<String> {
    let has = ctx.plugin::<LabelSelectionState>().lock().has_selection();
    if !has {
        return None;
    }
    ctx.with_plugin::<Tracker, _>(|t| (t.over == Some(msg)).then(|| t.words.clone()))
        .flatten()
        .filter(|w| !w.trim().is_empty())
}

/// Everything of one message the menu needs.
pub struct Msg<'a> {
    /// The message's own words, as plain text.
    pub text: &'a str,
    /// Claude's answer (not the owner's message).
    pub answer: bool,
    /// What reading it aloud is called.
    pub listen: Id,
    /// "Try again" sends this (answers only).
    pub again: Option<&'a dyn Fn() -> Action>,
    /// "Edit" sends this (the owner's own messages only).
    pub edit: Option<&'a dyn Fn() -> Action>,
}

/// "Select all" on a message: the whole message is marked, and Copy takes
/// all of it.
fn mark_all(ctx: &egui::Context, id: Id, text: &str) {
    ctx.plugin::<LabelSelectionState>().lock().clear_selection();
    ctx.data_mut(|d| d.insert_temp(Id::unique("msg-all"), (id, text.to_string())));
}

fn unmark(ctx: &egui::Context) {
    ctx.data_mut(|d| d.remove::<(Id, String)>(Id::unique("msg-all")));
}

/// The message menu, over the message's whole area. `area` is the
/// response of what holds the message.
pub fn message(ui: &egui::Ui, area: &Response, m: &Msg, actions: &mut Vec<Action>) {
    let ctx = ui.ctx().clone();
    let id = area.id;
    crate::shot::note_message(&ctx, m.answer, area.rect);
    if area.container_contains_pointer() {
        let now = ctx.cumulative_pass_nr();
        ctx.data_mut(|d| d.insert_temp(Id::unique("msg-hover"), (now, id, m.text.to_string())));
    }
    let all = ctx.data(|d| d.get_temp::<(Id, String)>(Id::unique("msg-all")));
    if all.as_ref().is_some_and(|(a, _)| *a == id) {
        ui.painter().rect_filled(
            area.rect.expand(3.0),
            egui::CornerRadius::same(8),
            Color32::from_rgba_unmultiplied(0x4F, 0x8C, 0xFF, 40),
        );
    }
    let (words_id, whole_id) = (id.with("menu-words"), id.with("menu-whole"));
    if area.container_secondary_clicked() {
        // Chosen words, or all of it, as the menu opens (pressing in the
        // menu ends both).
        let words = selected_words(&ctx, id);
        let whole = all.as_ref().is_some_and(|(a, _)| *a == id);
        ctx.data_mut(|d| {
            d.insert_temp(words_id, words);
            d.insert_temp(whole_id, whole);
        });
    }
    open_in(area, |ui| {
        let words: Option<String> = ui
            .data(|d| d.get_temp::<Option<String>>(words_id))
            .flatten();
        let whole: bool = ui.data(|d| d.get_temp::<bool>(whole_id)).unwrap_or(false);
        // The chosen words, unless the whole message was marked.
        let part: Option<&str> = words.as_deref().filter(|_| !whole);
        let what = part.unwrap_or(m.text).to_string();
        // Opening the menu ends the highlight in the message, so the
        // chosen words are named here.
        if let Some(p) = part {
            let flat: String = p.split_whitespace().collect::<Vec<_>>().join(" ");
            let short: String = if flat.chars().count() > 34 {
                format!("{}…", flat.chars().take(33).collect::<String>())
            } else {
                flat
            };
            ui.label(
                egui::RichText::new(format!("Chosen words: {short}"))
                    .color(HINT)
                    .size(12.0),
            );
            sep(ui);
        }
        if let Some(edit) = m.edit {
            if item(ui, "Edit", "") {
                actions.push(edit());
            }
            sep(ui);
        }
        if item(ui, "Copy", "Ctrl C") {
            ctx.copy_text(what.clone());
        }
        if item(ui, "Copy whole message", "") {
            ctx.copy_text(m.text.to_string());
        }
        if item(ui, "Select all", "Ctrl A") {
            mark_all(&ctx, id, m.text);
        }
        sep(ui);
        if item(ui, "Quote in my reply", "") {
            actions.push(Action::Insert(quoted(&what)));
        }
        let speaking = crate::places::speaking() == Some(m.listen);
        if item(ui, if speaking { "Stop listening" } else { "Listen" }, "") {
            if speaking {
                crate::places::stop_speaking();
            } else {
                crate::places::speak(m.listen, m.text);
            }
        }
        if let Some(again) = m.again {
            sep(ui);
            if item(ui, "Try again", "") {
                actions.push(again());
            }
        }
    });
}

/// "> " before every line, and a blank line after, so the reply starts
/// under the quote.
pub fn quoted(text: &str) -> String {
    let mut out: String = text.trim().lines().map(|l| format!("> {l}\n")).collect();
    out.push('\n');
    out
}

/// Keys that belong to messages, and the end of a "Select all". Call once
/// a frame before the page is drawn.
pub fn keys(ctx: &egui::Context) {
    install(ctx);
    let (pressed, ctrl, esc, copy, all_key) = ctx.input(|i| {
        (
            i.pointer.button_pressed(egui::PointerButton::Primary),
            i.modifiers.command && !i.modifiers.shift,
            i.key_pressed(Key::Escape),
            i.events.iter().any(|e| matches!(e, Event::Copy)),
            i.key_pressed(Key::A),
        )
    });
    let marked = ctx.data(|d| d.get_temp::<(Id, String)>(Id::unique("msg-all")));
    if let Some((_, text)) = &marked
        && copy
    {
        // Ctrl+C with a whole message marked takes all of it.
        ctx.copy_text(text.clone());
    }
    if marked.is_some() && (pressed || esc) {
        unmark(ctx);
    }
    // Ctrl+A over a message (and no box with the cursor in it) marks it.
    if ctrl
        && all_key
        && !ctx.egui_wants_keyboard_input()
        && let Some((id, text)) = hovered_now(ctx)
    {
        mark_all(ctx, id, &text);
    }
}

/// The menu of the empty middle: New chat, New task, Search, Paste.
pub fn empty_space(ui: &egui::Ui, area: &Response, actions: &mut Vec<Action>) {
    let ctx = ui.ctx().clone();
    let clip_id = area.id.with("menu-clip");
    if area.container_secondary_clicked() && !claimed(&ctx) {
        let clip = clipboard_text(&ctx);
        let pic = if clip.is_none() {
            clipboard_image(&ctx)
        } else {
            None
        };
        ctx.data_mut(|d| {
            d.insert_temp(clip_id, clip);
            d.insert_temp(clip_id.with("pic"), pic);
        });
    }
    open_area(area, false, |ui| {
        let clip: Option<String> = ui.data(|d| d.get_temp::<Option<String>>(clip_id)).flatten();
        if item(ui, "New chat", "Ctrl N") {
            actions.push(Action::NewChat);
        }
        if item(ui, "New task", "") {
            actions.push(Action::NewTask);
        }
        if item(ui, "Search chats", "Ctrl K") {
            ctx.memory_mut(|m| m.request_focus(crate::view_left::search_id()));
        }
        sep(ui);
        let pic: Option<std::sync::Arc<egui::ColorImage>> = ui
            .data(|d| d.get_temp::<Option<std::sync::Arc<egui::ColorImage>>>(clip_id.with("pic")))
            .flatten();
        if item_if(
            ui,
            clip.is_some() || pic.is_some(),
            "Paste into the message box",
            "",
        ) {
            match (&clip, pic) {
                (Some(t), _) => actions.push(Action::Insert(t.clone())),
                (None, Some(img)) => paste_image(&ctx, img),
                (None, None) => {}
            }
        }
    });
}

/// A chat as plain text, to copy.
pub fn chat_text(c: &mimir_core::proto::ChatRecord) -> String {
    let mut out = String::new();
    for t in &c.turns {
        if !out.is_empty() {
            out.push_str("\n\n");
        }
        out.push_str(if t.user { "You:\n" } else { "Answer:\n" });
        out.push_str(t.text.trim());
    }
    out
}
