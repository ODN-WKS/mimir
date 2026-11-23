//! Settings › Phone: pair a phone (QR code or copied code), confirm its
//! words, see and remove paired phones.

use crate::app::App;
use crate::theme::{self, JADE, MUTED, RED};
use eframe::egui::{self, RichText};
use mimir_core::proto::Call;

/// What the Phone card keeps between frames.
#[derive(Default)]
pub struct PhonePanel {
    /// The code on screen and its QR picture.
    code: Option<(String, egui::TextureHandle)>,
    phones: Vec<serde_json::Value>,
    phones_seen: Option<u64>,
    problem: Option<String>,
    copied: bool,
    /// "Whose phone is this?" for the pairing waiting: Some(true) is the
    /// owner's own ("Mine"), Some(false) someone else's, None not chosen.
    whose: Option<bool>,
    /// The first name typed for "Someone else's".
    other_name: String,
    /// The pairing the two answers above belong to.
    pair_seen: Option<u64>,
    /// The PC said the pairing is over when it was confirmed.
    pair_ran_out: bool,
    /// The service didn't answer the last Yes; the pairing may still wait.
    pair_retry: bool,
    /// A removal waiting for its "Their chats on this PC are deleted."
    /// answer: the person's id, the phone's key (None: the whole person)
    /// and what to call it.
    confirm: Option<(String, Option<String>, String)>,
    /// A person being renamed: their id and the name typed so far.
    renaming: Option<(String, String)>,
}

impl PhonePanel {
    /// Asks for the paired phones again when the list may have changed.
    pub(crate) fn refresh(&mut self, st: &mut mimir_core::client::Station) {
        if self.phones_seen != Some(st.phones_changes) {
            self.phones_seen = Some(st.phones_changes);
            self.phones = st
                .call_wait(Call::Phones)
                .ok()
                .and_then(|v| v.as_array().cloned())
                .unwrap_or_default();
            if st.phones_changes > 0 {
                self.code = None;
            }
        }
    }

    /// How many phones there are: an old entry for a phone that has paired
    /// again (same name and model, not seen for over a day) isn't counted.
    pub(crate) fn count(&self) -> usize {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_secs());
        count_phones(&self.phones, now)
    }

    /// How many of them were seen in the last two minutes.
    pub(crate) fn online_count(&self) -> usize {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_secs());
        self.phones
            .iter()
            .filter(|p| p["last_seen"].as_u64().is_some_and(|t| t + 120 >= now))
            .count()
    }

    /// The paired phones' names.
    pub(crate) fn names(&self) -> Vec<String> {
        self.phones
            .iter()
            .filter_map(|p| p["name"].as_str().map(str::to_string))
            .collect()
    }
}

/// One paired phone as the list shows it.
#[derive(Debug, PartialEq, Clone)]
pub(crate) struct PhoneRow {
    pub name: String,
    pub key: String,
    /// "last seen 3 h ago", or "Not seen since 2026-11-18" for a stale twin.
    pub note: String,
    pub stale: bool,
    /// Whose phone it is: "owner" or another person's id.
    pub person: String,
    /// "You" for the owner, else the person's first name.
    pub person_name: String,
    /// The person may use the owner's online plan.
    pub online: bool,
}

/// "2026-11-18" from Unix seconds (UTC).
fn date_words(unix: u64) -> String {
    let z = (unix / 86400) as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}")
}

/// Each phone once: an older entry with the same name and model as a newer
/// one, not seen for over a day, is a stale twin and isn't counted.
pub(crate) fn count_phones(phones: &[serde_json::Value], now: u64) -> usize {
    phone_rows(phones, now).iter().filter(|r| !r.stale).count()
}

/// The phones in list order: an old entry with the same name as a newer
/// one, not seen for over a day, comes first and says when it was last
/// seen. Nothing is removed.
pub(crate) fn phone_rows(phones: &[serde_json::Value], now: u64) -> Vec<PhoneRow> {
    let seen = |p: &serde_json::Value| p["last_seen"].as_u64().unwrap_or(0);
    let mut rows: Vec<PhoneRow> = phones
        .iter()
        .map(|p| {
            let name = p["name"].as_str().unwrap_or("A phone").to_string();
            let last = seen(p);
            let twin_newer = phones.iter().any(|o| {
                o["key"] != p["key"]
                    && o["name"] == p["name"]
                    && o["model"] == p["model"]
                    && o["paired"].as_u64() > p["paired"].as_u64()
            });
            let stale = twin_newer && last + 86_400 < now;
            let note = if stale {
                format!(
                    "Not seen since {}",
                    date_words(last.max(p["paired"].as_u64().unwrap_or(0)))
                )
            } else if last > 0 {
                format!("last seen {}", crate::view_windows::ago(last as i64))
            } else {
                String::new()
            };
            PhoneRow {
                name,
                key: p["key"].as_str().unwrap_or("").to_string(),
                note,
                stale,
                person: p["person"].as_str().unwrap_or("owner").to_string(),
                person_name: p["person_name"].as_str().unwrap_or("You").to_string(),
                online: p["online"].as_bool().unwrap_or(false),
            }
        })
        .collect();
    rows.sort_by_key(|r| !r.stale);
    rows
}

impl App {
    pub(crate) fn phone_card(&mut self, ui: &mut egui::Ui) {
        let Ok(st) = &mut self.station else { return };
        let p = &mut self.phone;
        p.refresh(st);
        let online_ready = self.settings.online_set_up();
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Pair a phone");
            ui.label(
                RichText::new(
                    "Pair the Mímir phone app to follow and answer your work from \
                     anywhere. No account needed. Private by design.",
                )
                .color(MUTED)
                .size(13.0),
            );
            ui.add_space(4.0);
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            let rows = phone_rows(&p.phones, now);
            // The owner first, then each other person, in the order they came.
            let mut people: Vec<(String, String, bool)> = vec![];
            for r in &rows {
                if !people.iter().any(|x| x.0 == r.person) {
                    people.push((r.person.clone(), r.person_name.clone(), r.online));
                }
            }
            people.sort_by_key(|x| x.0 != "owner");
            for (person, person_name, online) in people {
                let mine = person == "owner";
                if !mine || rows.iter().any(|r| r.person != "owner") {
                    ui.add_space(4.0);
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(&person_name).strong());
                        if !mine {
                            if ui.add(theme::quiet("Rename")).clicked() {
                                p.renaming = Some((person.clone(), person_name.clone()));
                            }
                            if ui.add(theme::quiet("Remove person")).clicked() {
                                p.confirm = Some((person.clone(), None, person_name.clone()));
                            }
                        }
                    });
                    if let Some((who, name)) = &mut p.renaming
                        && *who == person
                    {
                        let mut done = false;
                        ui.horizontal(|ui| {
                            let r = ui.add(
                                egui::TextEdit::singleline(name)
                                    .char_limit(40)
                                    .desired_width(180.0),
                            );
                            crate::menus::edit_menu(&r, "phone-rename");
                            if ui.add(theme::primary("Save name")).clicked() {
                                let _ = st.call_wait(Call::RenamePerson {
                                    person: person.clone(),
                                    name: name.clone(),
                                });
                                done = true;
                            }
                            if ui.add(theme::quiet("Cancel")).clicked() {
                                done = true;
                            }
                        });
                        if done {
                            p.renaming = None;
                            p.phones_seen = None;
                        }
                    }
                    if !mine && (online_ready || online) {
                        let mut on = online;
                        if ui
                            .checkbox(&mut on, "Can use your online plan")
                            .on_hover_text(
                                "Off: their chats use your PC's free model. On: they can choose Online, which uses your plan.",
                            )
                            .changed()
                        {
                            let _ = st.call_wait(Call::PersonOnline {
                                person: person.clone(),
                                on,
                            });
                            p.phones_seen = None;
                        }
                    }
                }
                for row in rows.iter().filter(|r| r.person == person) {
                    let PhoneRow {
                        name,
                        key,
                        note,
                        stale,
                        ..
                    } = row.clone();
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(name.clone()).color(if stale {
                            MUTED
                        } else {
                            JADE
                        }));
                        if !note.is_empty() {
                            ui.label(RichText::new(note).color(MUTED).size(12.5));
                        }
                        if ui
                            .add(theme::quiet("Remove"))
                            .on_hover_text("Remove this phone. It loses access at once.")
                            .clicked()
                        {
                            // The last phone of someone else takes their chats with it.
                            let last = rows.iter().filter(|r| r.person == person).count() == 1;
                            if !mine && last {
                                p.confirm = Some((person.clone(), Some(key), person_name.clone()));
                            } else {
                                let _ = st.call_wait(Call::RemovePhone { key });
                                p.phones_seen = None;
                            }
                        }
                    });
                }
            }
            if let Some((person, key, who)) = p.confirm.clone() {
                ui.add_space(4.0);
                ui.label(
                    RichText::new(format!("Remove {who}? Their chats on this PC are deleted."))
                        .color(RED),
                );
                ui.horizontal(|ui| {
                    if ui.add(theme::primary("Yes, delete them")).clicked() {
                        let _ = match key {
                            Some(key) => st.call_wait(Call::RemovePhone { key }),
                            None => st.call_wait(Call::RemovePerson { person }),
                        };
                        p.confirm = None;
                        p.phones_seen = None;
                    }
                    if ui.add(theme::quiet("No, keep them")).clicked() {
                        p.confirm = None;
                    }
                });
            }
            ui.horizontal(|ui| {
                if ui.add(theme::primary("Pair a phone")).clicked() {
                    match st.call_wait(Call::PairCode) {
                        Ok(v) => {
                            let code = v.as_str().unwrap_or_default().to_string();
                            p.code = qr_texture(ui.ctx(), &code).map(|t| (code, t));
                            p.problem = None;
                            p.copied = false;
                        }
                        Err(e) => p.problem = Some(e),
                    }
                }
                if let Some((code, _)) = &p.code
                    && ui
                        .add(theme::quiet(if p.copied { "Copied" } else { "Copy code" }))
                        .clicked()
                {
                    ui.ctx().copy_text(code.clone());
                    p.copied = true;
                }
            });
            if let Some(e) = &p.problem {
                ui.label(RichText::new(e).color(RED).size(13.0));
            }
            if let Some((_, tex)) = &p.code {
                ui.label(
                    RichText::new(
                        "On the phone, open Mímir and scan this. The code works once, for 10 minutes.",
                    )
                    .size(13.0),
                );
                ui.add(egui::Image::new(tex).fit_to_exact_size(egui::vec2(240.0, 240.0)));
            }
        });
        ui.add_space(10.0);
        self.pc_card(ui);
    }

    /// The PC's name on phones, and whether phones may go through relays.
    fn pc_card(&mut self, ui: &mut egui::Ui) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Your PC");
            let mut name = self.settings.pc_name.clone().unwrap_or_default();
            ui.horizontal(|ui| {
                let said = ui.label("Name your phones see:");
                let r = ui
                    .add(
                        egui::TextEdit::singleline(&mut name)
                            .hint_text(mimir_core::service::pc_name(
                                &mimir_core::settings::Settings::default(),
                            ))
                            .char_limit(40)
                            .desired_width(240.0),
                    )
                    .labelled_by(said.id);
                crate::menus::edit_menu(&r, "pc-name");
                if r.changed() {
                    let n = name.trim().to_string();
                    self.settings.pc_name = (!n.is_empty()).then_some(name.clone());
                }
                if r.lost_focus() {
                    self.settings_dirty = true;
                }
            });
            ui.add_space(6.0);
            let mut relays = self.settings.phone_relays.unwrap_or(true);
            if ui
                .checkbox(&mut relays, "Let phones reach your PC from anywhere")
                .changed()
            {
                self.settings.phone_relays = Some(relays);
                self.settings_dirty = true;
            }
            ui.label(
                RichText::new(
                    "On: phones can reach your PC from anywhere, privately. Off: only on the same \
                     Wi-Fi. Applies the next time Mímir starts.",
                )
                .color(MUTED)
                .size(13.0),
            );
            ui.add_space(6.0);
            if ui
                .checkbox(
                    &mut self.settings.yolo_from_phone,
                    "Let my phone turn on Go ahead without asking",
                )
                .changed()
            {
                self.settings_dirty = true;
            }
            ui.label(
                RichText::new(
                    "Off: a phone can't switch a task to auto-approve. On: it can, after \
                     a warning and the phone's unlock. In auto-approve mode Mímir changes and deletes files and \
                     runs commands without asking, so a stolen phone could too. Each task \
                     goes back to Ask me first after 5 hours of work or 24 hours idle.",
                )
                .color(theme::RED)
                .size(13.0),
            );
        });
        ui.add_space(10.0);
        ui.label(
            RichText::new(
                "The phone app locks with a fingerprint or its PIN; change that on the phone.",
            )
            .color(MUTED)
            .size(13.0),
        );
    }

    /// "Do the words match?" and "Whose phone is this?" over everything
    /// while a phone waits. The answer to the second is asked only here, at
    /// the PC, and has no preset: Yes waits until it is chosen.
    pub(crate) fn pair_window(&mut self, ctx: &egui::Context) {
        let Ok(st) = &mut self.station else { return };
        let p = &mut self.phone;
        let Some((pending, name, words)) = st.pairing.clone() else {
            // Nothing waits: the next phone starts with no answer chosen.
            p.whose = None;
            p.other_name.clear();
            p.pair_seen = None;
            p.pair_ran_out = false;
            p.pair_retry = false;
            return;
        };
        if p.pair_seen != Some(pending) {
            p.whose = None;
            p.other_name.clear();
            p.pair_seen = Some(pending);
            p.pair_ran_out = false;
            p.pair_retry = false;
        }
        egui::Window::new("Pair this phone?")
            .collapsible(false)
            .resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0))
            .show(ctx, |ui| {
                if p.pair_ran_out {
                    ui.label(RichText::new("That pairing ran out, make a new code.").strong());
                    ui.add_space(8.0);
                    if ui.add(theme::primary("Close")).clicked() {
                        st.pairing = None;
                        p.whose = None;
                        p.other_name.clear();
                        p.pair_seen = None;
                        p.pair_ran_out = false;
                    }
                    return;
                }
                if p.pair_retry {
                    ui.label(
                        RichText::new("Mímir's service didn't answer. Try again.")
                            .color(MUTED)
                            .size(13.0),
                    );
                }
                ui.label(format!("{name} wants to pair with your PC."));
                ui.label("Check that the phone shows the same four words:");
                ui.add_space(4.0);
                ui.label(RichText::new(words.join("  ·  ")).size(20.0).strong());
                ui.add_space(8.0);
                ui.label(RichText::new("Whose phone is this?").strong());
                ui.horizontal(|ui| {
                    if ui.selectable_label(p.whose == Some(true), "Mine").clicked() {
                        p.whose = Some(true);
                    }
                    if ui
                        .selectable_label(p.whose == Some(false), "Someone else's")
                        .clicked()
                    {
                        p.whose = Some(false);
                    }
                });
                if p.whose == Some(false) {
                    ui.horizontal(|ui| {
                        ui.label("Their first name:");
                        let r = ui.add(
                            egui::TextEdit::singleline(&mut p.other_name)
                                .char_limit(40)
                                .desired_width(160.0),
                        );
                        crate::menus::edit_menu(&r, "guest-name");
                    });
                    ui.label(
                        RichText::new("They see only their own chats, never yours.")
                            .color(MUTED)
                            .size(13.0),
                    );
                } else if p.whose == Some(true) {
                    ui.label(
                        RichText::new("It shares your chats and your work.")
                            .color(MUTED)
                            .size(13.0),
                    );
                }
                ui.add_space(8.0);
                let ready = match p.whose {
                    Some(true) => true,
                    Some(false) => !p.other_name.trim().is_empty(),
                    None => false,
                };
                ui.horizontal(|ui| {
                    if ui
                        .add_enabled(ready, theme::primary("Yes, they match"))
                        .clicked()
                    {
                        let person = Some(if p.whose == Some(false) {
                            p.other_name.trim().to_string()
                        } else {
                            mimir_core::proto::WHOSE_MINE.to_string()
                        });
                        // Closes only when the PC took the answer.
                        match st.call_wait(Call::ConfirmPair {
                            pending,
                            yes: true,
                            person,
                        }) {
                            Ok(_) => {
                                st.pairing = None;
                                p.whose = None;
                                p.other_name.clear();
                                p.pair_seen = None;
                                p.pair_retry = false;
                            }
                            Err(e) => {
                                // Only the PC's refusal means it ran out.
                                (p.pair_ran_out, p.pair_retry) = pair_failure(&e);
                            }
                        }
                    }
                    if ui.add(theme::quiet("No, cancel")).clicked() {
                        let _ = st.call_wait(Call::ConfirmPair {
                            pending,
                            yes: false,
                            person: None,
                        });
                        st.pairing = None;
                        p.whose = None;
                        p.other_name.clear();
                        p.pair_seen = None;
                    }
                });
            });
    }
}

/// What a failed Yes means: (the pairing ran out, just try again). A stalled
/// or stopped service says nothing about the pairing, so it stays open.
fn pair_failure(err: &str) -> (bool, bool) {
    if mimir_core::client::is_transport_error(err) {
        (false, true)
    } else {
        (true, false)
    }
}

#[cfg(test)]
mod pair_failure_tests {
    use super::pair_failure;

    #[test]
    fn a_stalled_service_keeps_the_card_and_a_refusal_closes_it() {
        assert_eq!(
            pair_failure("Mímir's service didn't answer."),
            (false, true)
        );
        assert_eq!(pair_failure("Mímir's service stopped."), (false, true));
        assert_eq!(pair_failure("No pairing waits."), (true, false));
    }
}

/// The code as a QR picture, black on white with a quiet border.
fn qr_texture(ctx: &egui::Context, code: &str) -> Option<egui::TextureHandle> {
    let qr = qrcode::QrCode::new(code.as_bytes()).ok()?;
    let n = qr.width();
    let border = 4;
    let side = n + border * 2;
    let scale = 6;
    let px = side * scale;
    let mut img = egui::ColorImage::new([px, px], vec![egui::Color32::WHITE; px * px]);
    for y in 0..n {
        for x in 0..n {
            if qr[(x, y)] == qrcode::Color::Dark {
                for dy in 0..scale {
                    for dx in 0..scale {
                        let xx = (x + border) * scale + dx;
                        let yy = (y + border) * scale + dy;
                        img.pixels[yy * px + xx] = egui::Color32::BLACK;
                    }
                }
            }
        }
    }
    Some(ctx.load_texture("pair-code", img, egui::TextureOptions::NEAREST))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn stale_twin_is_listed_first_and_dated() {
        let now = 1_800_000_000u64;
        let phones = vec![
            json!({"key":"new","name":"Pixel","paired":now-100,"last_seen":now-10}),
            json!({"key":"old","name":"Pixel","paired":now-900_000,"last_seen":now-800_000}),
            json!({"key":"other","name":"Tablet","paired":now-5000,"last_seen":now-5000}),
        ];
        let rows = phone_rows(&phones, now);
        assert_eq!(rows[0].key, "old");
        assert!(rows[0].stale && rows[0].note.starts_with("Not seen since 20"));
        assert!(rows[1].note.starts_with("last seen"));
        assert_eq!(count_phones(&phones, now), 2);
        assert_eq!(count_phones(&[], now), 0);
        // Same name, different model: two phones. Both seen today: two.
        let two = vec![
            json!({"key":"a","name":"Pixel","model":"7","paired":now-900_000,"last_seen":now-800_000}),
            json!({"key":"b","name":"Pixel","model":"8","paired":now-100,"last_seen":now-10}),
            json!({"key":"c","name":"Tablet","paired":now-9000,"last_seen":now-60}),
            json!({"key":"d","name":"Tablet","paired":now-100,"last_seen":now-10}),
        ];
        assert_eq!(count_phones(&two, now), 4);
        assert_eq!(date_words(0), "1970-01-01");
        assert_eq!(date_words(1_800_000_000), "2027-01-15");
    }
}
