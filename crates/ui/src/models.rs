//! The Claude models offered in the pickers, with their exact versions,
//! prices and what each is best for, and the "About the models" chart.

use crate::theme::{self, JADE, LINE, MUTED};
use eframe::egui::{self, RichText};

pub struct Model {
    /// Name with version, e.g. "Opus 5.5".
    pub name: &'static str,
    /// Exact Claude Code `--model` value: the name always matches what runs.
    pub id: &'static str,
    /// API price in US dollars per million tokens read (input).
    pub input: f32,
    /// API price in US dollars per million tokens written (output).
    pub output: f32,
    pub best_for: &'static str,
    pub short: &'static str,
}

/// When the prices below were checked against Anthropic's model list.
pub const PRICES_CHECKED: &str = "3 November 2026";

/// Strongest first.
pub const MODELS: [Model; 4] = [
    Model {
        name: "Fable 5.2",
        id: "claude-fable-5-2",
        input: 8.0,
        output: 40.0,
        best_for: "The hardest problems and long, many-step work: big rebuilds, tricky \
                   problems, deep research. Slowest and uses the most.",
        short: "hardest, longest work",
    },
    Model {
        name: "Opus 5.5",
        id: "claude-opus-5-5",
        input: 4.0,
        output: 20.0,
        best_for: "Strong everyday choice for real work and careful writing, at half \
                   Fable's price.",
        short: "capable everyday choice",
    },
    Model {
        name: "Sonnet 5.5",
        id: "claude-sonnet-5-5",
        input: 2.0,
        output: 10.0,
        best_for: "Fast and balanced: good value for most tasks, coding or not.",
        short: "fast, balanced",
    },
    Model {
        name: "Haiku 5.5",
        id: "claude-haiku-5-5",
        input: 0.10,
        output: 0.50,
        best_for: "Quick answers, small edits, summaries and lookups. By far the lightest; \
                   prices shown are for messages up to about 75,000 words ($0.50 / $2.50 beyond).",
        short: "quick answers, lightest",
    },
];

pub use mimir_core::models::plain_name;

/// The picker's name for a saved `--model` value. Older tasks saved the
/// short aliases ("opus"), which run the newest of that family.
pub fn label(value: Option<&str>) -> String {
    if let Some(n) = value.and_then(mimir_core::models::ollama_name) {
        return plain_name(n);
    }
    match value {
        None => "Auto-pick".into(),
        Some(v) => MODELS
            .iter()
            .find(|m| m.id == v)
            .map(|m| m.name.to_string())
            .unwrap_or_else(|| match v {
                "fable" | "opus" | "sonnet" | "haiku" => {
                    let mut c = v.chars();
                    let first = c.next().map(|f| f.to_uppercase().collect::<String>());
                    format!("{}{} (newest)", first.unwrap_or_default(), c.as_str())
                }
                other => other.to_string(),
            }),
    }
}

fn money(x: f32) -> String {
    if x < 1.0 {
        format!("${x:.2}")
    } else {
        format!("${x:.0}")
    }
}

/// The calm second tone of the chart: what Claude writes.
const SOFT_BLUE: egui::Color32 = egui::Color32::from_rgb(0x8F, 0xB3, 0xC9);

/// How fast a model uses the limit, as dots out of five.
fn dots(m: &Model) -> &'static str {
    match m.output {
        x if x < 1.0 => "●○○○○",
        x if x <= 10.0 => "●●○○○",
        x if x <= 20.0 => "●●●○○",
        _ => "●●●●●",
    }
}

/// "About the models": how fast each uses the limit, prices as bars, and
/// what each is best for.
pub fn about(ui: &mut egui::Ui) {
    theme::heading(ui, "About the models");
    ui.label(
        RichText::new(format!(
            "How fast each model uses up your Anthropic plan. On a Pro or Max plan you are not billed \
             per message. The prices are Anthropic's, per 750,000 words, \
             checked {PRICES_CHECKED}."
        ))
        .color(MUTED)
        .size(13.0),
    );
    ui.add_space(6.0);
    ui.horizontal_wrapped(|ui| {
        ui.label(RichText::new("■").color(JADE));
        ui.label(RichText::new("What you send").color(MUTED).size(13.0));
        ui.add_space(8.0);
        ui.label(RichText::new("■").color(SOFT_BLUE));
        ui.label(
            RichText::new("What Mímir writes back")
                .color(MUTED)
                .size(13.0),
        );
    });
    ui.add_space(6.0);
    let max = MODELS.iter().map(|m| m.output).fold(0.0, f32::max);
    for m in &MODELS {
        ui.horizontal(|ui| {
            ui.label(RichText::new(m.name).strong()).on_hover_text(m.id);
            ui.label(
                RichText::new(format!("uses your plan {}", dots(m)))
                    .color(MUTED)
                    .size(13.0),
            );
        });
        bar(ui, "Send", m.input, max, JADE);
        bar(ui, "Writes", m.output, max, SOFT_BLUE);
        ui.label(RichText::new(m.best_for).size(13.5));
        ui.add_space(10.0);
    }
    ui.label(RichText::new("Auto").strong());
    ui.label(
        RichText::new(crate::view_task::AUTO_NOTE)
            .color(MUTED)
            .size(13.5),
    );
    for style in [
        mimir_core::auto::Style::Efficient,
        mimir_core::auto::Style::Capable,
    ] {
        ui.label(
            RichText::new(crate::view_task::auto_style_note(style))
                .color(MUTED)
                .size(13.0),
        );
    }
    ui.label(
        RichText::new(
            "In coding projects, a step that fails moves Auto up one model. Planning, \
             rebuilding, fixing bugs and design use Opus or stronger. Writing \"think hard\" asks \
             for Fable. Chats stay light. Mímir writes the short summaries itself, so they cost nothing.",
        )
        .color(MUTED)
        .size(13.0),
    );
    ui.add_space(6.0);
    ui.label(
        RichText::new(
            "Tip: Haiku costs about 40 times less than Opus for the same amount of text, \
             Sonnet half of Opus, Fable twice Opus. Start light and step up when a task \
             needs more thought.",
        )
        .color(MUTED)
        .size(13.0),
    );
}

fn bar(ui: &mut egui::Ui, what: &str, value: f32, max: f32, color: egui::Color32) {
    ui.horizontal(|ui| {
        ui.add_sized(
            [64.0, 18.0],
            egui::Label::new(RichText::new(what).color(MUTED).size(13.0)),
        );
        let w = (ui.available_width() - 70.0).clamp(60.0, 420.0);
        let (rect, _) = ui.allocate_exact_size(egui::vec2(w, 12.0), egui::Sense::hover());
        let p = ui.painter();
        p.rect_filled(rect, egui::CornerRadius::same(3), LINE);
        let mut fill = rect;
        // Tiny prices still get a visible sliver.
        fill.set_width((w * value / max).max(3.0));
        p.rect_filled(fill, egui::CornerRadius::same(3), color);
        ui.label(RichText::new(money(value)).size(13.0));
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_carry_versions() {
        assert_eq!(label(Some("claude-opus-5-5")), "Opus 5.5");
        assert_eq!(label(Some("haiku")), "Haiku (newest)");
        assert_eq!(label(Some("custom-x")), "custom-x");
        assert_eq!(label(None), "Auto-pick");
        assert!(
            MODELS
                .iter()
                .all(|m| m.name.chars().any(|c| c.is_ascii_digit()))
        );
    }
}

#[cfg(test)]
mod plain_name_tests {
    use super::plain_name;

    #[test]
    fn raw_ids_read_as_plain_names() {
        for (raw, want) in [
            ("qwen3.6:35b-a3b-coding", "Qwen 3.6 coding"),
            (
                "huihui_ai/Qwen3.6-abliterated:35b-a3b",
                "Qwen 3.6 uncensored",
            ),
            ("gemma4:12b", "Gemma 4"),
            ("weird", "weird"),
        ] {
            assert_eq!(plain_name(raw), want, "{raw}");
        }
    }

    #[test]
    fn the_chip_has_no_free_suffix() {
        let v = format!("{}qwen3.6:35b-a3b-coding", mimir_core::models::FREE);
        assert_eq!(super::label(Some(&v)), "Qwen 3.6 coding");
    }
}
