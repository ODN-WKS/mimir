//! The look: black ground, jade accent, slightly rounded corners.

use eframe::egui::{self, Color32, CornerRadius, Stroke, Visuals};

pub const JADE: Color32 = Color32::from_rgb(0x6F, 0xAE, 0x8C);
pub const JADE_DIM: Color32 = Color32::from_rgb(0x2C, 0x46, 0x39);
pub const ORANGE: Color32 = Color32::from_rgb(0xE8, 0x96, 0x3C);
pub const ORANGE_DIM: Color32 = Color32::from_rgb(0x3A, 0x26, 0x10);
/// High load and other "worth a look" values; orange means "needs you".
pub const AMBER: Color32 = Color32::from_rgb(0xC9, 0xB4, 0x58);
pub const RED: Color32 = Color32::from_rgb(0xD9, 0x6C, 0x6C);
pub const GROUND: Color32 = Color32::from_rgb(0x05, 0x06, 0x06);
pub const PANEL: Color32 = Color32::from_rgb(0x0E, 0x11, 0x10);
pub const CARD: Color32 = Color32::from_rgb(0x16, 0x1A, 0x18);
pub const LINE: Color32 = Color32::from_rgb(0x24, 0x2A, 0x27);
pub const TEXT: Color32 = Color32::from_rgb(0xE6, 0xEA, 0xE8);
/// Whisper mode's deep sea-teal.
pub const WHISPER: Color32 = Color32::from_rgb(0x3B, 0xB7, 0xB0);
pub const MUTED: Color32 = Color32::from_rgb(0x8B, 0x94, 0x90);

pub const RADIUS: u8 = 8;
/// The right panel's bars: teal on a dark track.
pub const TEAL: Color32 = Color32::from_rgb(0x36, 0xD3, 0xBD);
pub const BAR_TRACK: Color32 = Color32::from_rgb(0x26, 0x2A, 0x31);
/// The border of the message box's chips.
pub const CHIP_EDGE: Color32 = Color32::from_rgb(0x2B, 0x2F, 0x37);

pub fn apply(ctx: &egui::Context) {
    crate::fonts::install(ctx);
    let mut v = Visuals::dark();
    v.panel_fill = PANEL;
    v.window_fill = PANEL;
    v.extreme_bg_color = GROUND;
    v.faint_bg_color = CARD;
    v.override_text_color = Some(TEXT);
    v.hyperlink_color = JADE;
    v.selection.bg_fill = JADE_DIM;
    v.selection.stroke = Stroke::new(1.0, JADE);
    // Inline code reads as a light tint, not a slab.
    v.code_bg_color = Color32::from_rgb(0x1C, 0x23, 0x20);
    v.window_corner_radius = CornerRadius::same(RADIUS + 4);
    v.window_stroke = Stroke::new(1.0, LINE);
    for w in [
        &mut v.widgets.noninteractive,
        &mut v.widgets.inactive,
        &mut v.widgets.hovered,
        &mut v.widgets.active,
        &mut v.widgets.open,
    ] {
        w.corner_radius = CornerRadius::same(RADIUS);
    }
    v.widgets.noninteractive.bg_stroke = Stroke::new(1.0, LINE);
    v.widgets.inactive.weak_bg_fill = CARD;
    // Checkboxes and sliders: visibly lighter than cards.
    v.widgets.inactive.bg_fill = Color32::from_rgb(0x2A, 0x33, 0x2E);
    v.widgets.inactive.bg_stroke = Stroke::new(1.0, Color32::from_rgb(0x4A, 0x55, 0x50));
    v.widgets.hovered.weak_bg_fill = Color32::from_rgb(0x1F, 0x26, 0x22);
    v.widgets.hovered.bg_stroke = Stroke::new(1.0, JADE_DIM);
    v.widgets.active.weak_bg_fill = JADE_DIM;
    ctx.set_visuals(v);
    ctx.all_styles_mut(|s| {
        s.spacing.item_spacing = egui::vec2(8.0, 6.0);
        s.spacing.button_padding = egui::vec2(12.0, 6.0);
        s.spacing.interact_size.y = 30.0;
        use egui::{FontId, TextStyle};
        s.text_styles
            .insert(TextStyle::Body, FontId::proportional(15.0));
        s.text_styles
            .insert(TextStyle::Button, FontId::proportional(15.0));
        s.text_styles
            .insert(TextStyle::Small, FontId::proportional(13.5));
        s.text_styles.insert(
            TextStyle::Heading,
            FontId::new(20.0, crate::fonts::cinzel()),
        );
        s.text_styles
            .insert(TextStyle::Monospace, FontId::monospace(13.5));
    });
}

/// A filled, rounded button in the accent colour.
pub fn primary(text: &str) -> egui::Button<'_> {
    egui::Button::new(egui::RichText::new(text).color(GROUND).strong())
        .fill(JADE)
        .corner_radius(CornerRadius::same(RADIUS))
}

pub fn quiet(text: &str) -> egui::Button<'_> {
    egui::Button::new(text)
        .fill(CARD)
        .stroke(Stroke::new(1.0, LINE))
        .corner_radius(CornerRadius::same(RADIUS))
}

pub fn card() -> egui::Frame {
    egui::Frame::new()
        .fill(CARD)
        .corner_radius(CornerRadius::same(RADIUS))
        .inner_margin(egui::Margin::same(10))
}

/// Text in the heading font (Cinzel), for titles only.
pub fn title(text: &str, size: f32) -> egui::RichText {
    egui::RichText::new(text).font(egui::FontId::new(size, crate::fonts::cinzel()))
}

/// Section heading in the side panels.
pub fn heading(ui: &mut egui::Ui, text: &str) {
    // In a row this would be a sideways gap and push the heading right.
    if !ui.layout().is_horizontal() {
        ui.add_space(4.0);
    }
    ui.label(title(text, 12.0).color(MUTED));
}

/// A thin bar from 0 to 1.
pub fn meter(ui: &mut egui::Ui, frac: f32, color: Color32) {
    let w = ui.available_width();
    let (rect, _) = ui.allocate_exact_size(egui::vec2(w, 6.0), egui::Sense::hover());
    let p = ui.painter();
    p.rect_filled(rect, CornerRadius::same(3), LINE);
    let mut fill = rect;
    fill.set_width(w * frac.clamp(0.0, 1.0));
    p.rect_filled(fill, CornerRadius::same(3), color);
}

/// A thin teal bar on a dark track.
pub fn pc_meter(ui: &mut egui::Ui, frac: f32, color: Color32) {
    let w = ui.available_width();
    let (rect, _) = ui.allocate_exact_size(egui::vec2(w, 5.0), egui::Sense::hover());
    let p = ui.painter();
    p.rect_filled(rect, CornerRadius::same(3), BAR_TRACK);
    let mut fill = rect;
    fill.set_width(w * frac.clamp(0.0, 1.0));
    p.rect_filled(fill, CornerRadius::same(3), color);
}

/// A small dim heading in capitals, above a group of cards.
pub fn caps(ui: &mut egui::Ui, text: &str) {
    ui.add_space(4.0);
    ui.label(title(&text.to_uppercase(), 11.0).color(DIM));
    ui.add_space(4.0);
}

/// Runs `add` with the pill look; the style is put back after, and the
/// widgets still wrap with their neighbours (a scope would not).
pub fn chipped(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    let old = ui.style().clone();
    chip_style(ui);
    add(ui);
    ui.set_style(old);
}

/// Makes the combo boxes in `ui` rounded pills with a quiet border, like
/// the phone's chips.
pub fn chip_style(ui: &mut egui::Ui) {
    let s = ui.style_mut();
    s.spacing.button_padding = egui::vec2(9.0, 5.0);
    let w = &mut s.visuals.widgets;
    for v in [&mut w.inactive, &mut w.hovered, &mut w.active, &mut w.open] {
        v.corner_radius = CornerRadius::same(15);
        v.bg_stroke = Stroke::new(1.0, CHIP_EDGE);
        v.weak_bg_fill = Color32::from_rgb(0x12, 0x15, 0x14);
    }
}

/// Whisper mode's sign: the closed eye with its lightning scar, exactly as
/// docs/designs/mimir/icon/in-app/make.py draws it (soft halo and glow
/// included), drawn from its SVG at the exact pixel size so it stays sharp.
const WHISPER_EYE_SVG: &str = include_str!("../../../assets/eyes/closed-dark.svg");

/// The Whisper eye, `size` points square.
pub fn whisper_eye(ui: &mut egui::Ui, size: f32) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(size, size), egui::Sense::hover());
    let px = (size * ui.ctx().pixels_per_point())
        .round()
        .clamp(1.0, 1024.0) as u32;
    let id = egui::Id::unique(("whisper-eye", px));
    let texture = ui
        .ctx()
        .data(|d| d.get_temp::<egui::TextureHandle>(id))
        .unwrap_or_else(|| {
            let t = ui.ctx().load_texture(
                format!("whisper-eye-{px}"),
                svg_image(WHISPER_EYE_SVG, px),
                egui::TextureOptions::LINEAR,
            );
            ui.ctx().data_mut(|d| d.insert_temp(id, t.clone()));
            t
        });
    ui.painter().image(
        texture.id(),
        rect,
        egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)),
        Color32::WHITE,
    );
    resp
}

/// An SVG drawn `px` pixels square (empty if it can't be read).
fn svg_image(svg: &str, px: u32) -> egui::ColorImage {
    let drawn = resvg::usvg::Tree::from_str(svg, &resvg::usvg::Options::default())
        .ok()
        .zip(resvg::tiny_skia::Pixmap::new(px, px))
        .map(|(tree, mut pixels)| {
            let k = px as f32 / tree.size().width().max(tree.size().height());
            resvg::render(
                &tree,
                resvg::tiny_skia::Transform::from_scale(k, k),
                &mut pixels.as_mut(),
            );
            pixels
        });
    match drawn {
        Some(p) => egui::ColorImage::from_rgba_premultiplied([px as usize; 2], p.data()),
        None => egui::ColorImage::filled([px as usize; 2], Color32::TRANSPARENT),
    }
}

/// A small outlined word (a mode, where a task runs): the sentence behind
/// it goes on hover, so the chat area stays clean.
pub fn chip(ui: &mut egui::Ui, text: &str, color: Color32) -> egui::Response {
    egui::Frame::new()
        .stroke(Stroke::new(1.0, color))
        .corner_radius(CornerRadius::same(6))
        .inner_margin(egui::Margin::symmetric(6, 1))
        .show(ui, |ui| {
            ui.label(egui::RichText::new(text).color(color).size(12.5))
        })
        .inner
}

/// A menu's contents, scrolling when the window is too short for all of
/// them, so no menu is ever cut off by the window's edge.
pub fn menu_fits<R>(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui) -> R) -> R {
    let room = (ui.ctx().content_rect().height() - 24.0).max(80.0);
    egui::ScrollArea::vertical()
        .max_height(room)
        .auto_shrink([true, true])
        .show(ui, add)
        .inner
}

/// The owner's message bubble in the middle.
pub const BUBBLE: Color32 = Color32::from_rgb(0x22, 0x35, 0x5C);
/// The amber card for a real waiting question: fill and edge.
pub const NEEDS_FILL: Color32 = Color32::from_rgb(0x24, 0x1D, 0x10);
pub const NEEDS_EDGE: Color32 = Color32::from_rgb(0x6B, 0x54, 0x20);
/// The message box's own surface, a touch lighter than the ground.
pub const BOX_FILL: Color32 = Color32::from_rgb(0x12, 0x15, 0x14);
/// Dim text for the hint line and greeting's second line.
pub const DIM: Color32 = Color32::from_rgb(0x6C, 0x75, 0x71);

/// Which small picture an icon button draws.
#[derive(Clone, Copy, PartialEq)]
pub enum Icon {
    Plus,
    Mic,
    Send,
    Stop,
}

/// A square or round button that is only a drawing, with a spoken name for
/// screen readers (and the window checks).
pub fn icon_button(
    ui: &mut egui::Ui,
    icon: Icon,
    size: f32,
    name: &str,
    filled: Option<Color32>,
    enabled: bool,
) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(size, size), egui::Sense::click());
    let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
    resp.widget_info(|| egui::WidgetInfo::labeled(egui::accesskit::Role::Button, enabled, name));
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        let hot = resp.hovered() && enabled;
        if let Some(fill) = filled {
            let fill = if enabled {
                fill
            } else {
                fill.gamma_multiply(0.35)
            };
            p.circle_filled(rect.center(), size / 2.0, fill);
            if hot {
                p.circle_stroke(rect.center(), size / 2.0, Stroke::new(1.5, Color32::WHITE));
            }
        } else if hot {
            p.circle_filled(
                rect.center(),
                size / 2.0,
                Color32::from_rgb(0x1F, 0x23, 0x2B),
            );
        }
        let ink = if filled.is_some() {
            Color32::WHITE
        } else if hot {
            TEXT
        } else {
            MUTED
        };
        paint_icon(p, icon, rect.center(), size, ink);
    }
    resp
}

fn paint_icon(p: &egui::Painter, icon: Icon, c: egui::Pos2, size: f32, ink: Color32) {
    let u = size / 28.0;
    let st = Stroke::new(1.6 * u.max(0.8), ink);
    let at = |x: f32, y: f32| c + egui::vec2(x * u, y * u);
    match icon {
        Icon::Plus => {
            p.line_segment([at(-6.0, 0.0), at(6.0, 0.0)], st);
            p.line_segment([at(0.0, -6.0), at(0.0, 6.0)], st);
        }
        Icon::Mic => {
            // A capsule, the cup under it, and the stand.
            p.rect_stroke(
                egui::Rect::from_min_max(at(-3.5, -9.0), at(3.5, 1.5)),
                CornerRadius::same((3.5 * u) as u8),
                st,
                egui::StrokeKind::Middle,
            );
            let cup: Vec<egui::Pos2> = (0..=12)
                .map(|i| {
                    let a = std::f32::consts::PI * i as f32 / 12.0;
                    at(-6.5 * a.cos(), 1.5 + 5.5 * a.sin())
                })
                .collect();
            p.add(egui::Shape::line(cup, st));
            p.line_segment([at(0.0, 7.0), at(0.0, 10.0)], st);
            p.line_segment([at(-3.0, 10.0), at(3.0, 10.0)], st);
        }
        Icon::Send => {
            p.line_segment([at(0.0, 7.0), at(0.0, -6.0)], st);
            p.line_segment([at(-5.5, -1.0), at(0.0, -6.5)], st);
            p.line_segment([at(5.5, -1.0), at(0.0, -6.5)], st);
        }
        Icon::Stop => {
            p.rect_filled(
                egui::Rect::from_center_size(c, egui::vec2(9.0 * u, 9.0 * u)),
                CornerRadius::same(2),
                ink,
            );
        }
    }
}

/// A rounded, quiet pill the width of its words: what the middle's top
/// uses for who answers and for the panel toggle.
pub fn pill_button(text: egui::WidgetText) -> egui::Button<'static> {
    egui::Button::new(text)
        .fill(Color32::from_rgb(0x12, 0x15, 0x14))
        .stroke(Stroke::new(1.0, LINE))
        .corner_radius(CornerRadius::same(15))
        .min_size(egui::vec2(0.0, 30.0))
}
