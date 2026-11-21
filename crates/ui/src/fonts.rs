//! The heading font: Cinzel. Everything else keeps egui's own text font.
//! Font: Cinzel (SIL Open Font License 1.1, text in assets/fonts).

use eframe::egui::{self, FontData, FontDefinitions, FontFamily};
use std::sync::Arc;

pub const CINZEL: &str = "Cinzel";
const CINZEL_TTF: &[u8] = include_bytes!("../../../assets/fonts/Cinzel.ttf");

/// The family used for titles.
pub fn cinzel() -> FontFamily {
    FontFamily::Name(CINZEL.into())
}

/// egui's defaults plus a named Cinzel family; the default text font
/// stays behind it only for glyphs Cinzel lacks.
pub fn definitions() -> FontDefinitions {
    let mut defs = FontDefinitions::default();
    defs.font_data.insert(
        CINZEL.to_owned(),
        Arc::new(FontData::from_static(CINZEL_TTF)),
    );
    let mut list = vec![CINZEL.to_owned()];
    list.extend(
        defs.families
            .get(&FontFamily::Proportional)
            .cloned()
            .unwrap_or_default(),
    );
    defs.families.insert(cinzel(), list);
    defs
}

pub fn install(ctx: &egui::Context) {
    ctx.set_fonts(definitions());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cinzel_is_registered_and_body_text_is_untouched() {
        let d = definitions();
        assert_eq!(d.families[&cinzel()][0], CINZEL);
        let body = &d.families[&FontFamily::Proportional];
        assert_ne!(body[0], CINZEL);
        assert_eq!(
            body,
            &FontDefinitions::default().families[&FontFamily::Proportional]
        );
    }

    #[test]
    fn mimir_glyphs_render_in_cinzel() {
        use ab_glyph::Font;
        let f = ab_glyph::FontRef::try_from_slice(CINZEL_TTF).unwrap();
        for c in "Mímir".chars() {
            assert_ne!(f.glyph_id(c).0, 0, "missing {c}");
        }
    }
}
