//! Settings › Models: every model in one place. What powers your tasks
//! (and what happens when Claude's limit runs out), Claude's models, the
//! free models on this PC, the Model Store's first page (tested picks to
//! download), the models the phone can download, and the weekly
//! "Worth switching?" look. The phone's Models screen points here.

use crate::app::App;
use crate::theme::{self, JADE, MUTED, ORANGE, RED};
use eframe::egui::{self, RichText};
use mimir_core::settings::{Power, Settings};
use std::sync::Mutex;

/// A free model being downloaded or removed, off the window's thread.
#[derive(Clone, PartialEq, Debug)]
enum Job {
    Adding(String, f32),
    Removing(String),
    Done(String),
    Failed(String),
}

static JOB: Mutex<Option<(Job, std::time::Instant)>> = Mutex::new(None);

/// How long "Added X." or a failure stays on the cards.
const NOTE_SECS: u64 = 8;

fn job() -> Option<Job> {
    let mut g = JOB.lock().ok()?;
    // A finished job's note goes by itself; one still under way stays.
    if let Some((Job::Done(_) | Job::Failed(_), at)) = &*g
        && at.elapsed().as_secs() >= NOTE_SECS
    {
        *g = None;
    }
    g.as_ref().map(|(j, _)| j.clone())
}

fn set_job(j: Option<Job>) {
    if let Ok(mut g) = JOB.lock() {
        *g = j.map(|j| (j, std::time::Instant::now()));
    }
}

/// Clears the note about the last download or removal (the page was left).
pub(crate) fn forget_job_note() {
    if let Ok(mut g) = JOB.lock()
        && matches!(&*g, Some((Job::Done(_) | Job::Failed(_), _)))
    {
        *g = None;
    }
}

fn busy() -> bool {
    matches!(job(), Some(Job::Adding(..) | Job::Removing(_)))
}

/// Downloads a model in the background, with its progress shown.
fn start_download(ctx: &egui::Context, name: String) {
    set_job(Some(Job::Adding(name.clone(), 0.0)));
    let ctx = ctx.clone();
    let _ = std::thread::Builder::new()
        .name("free-model".into())
        .spawn(move || {
            let n = name.clone();
            let c2 = ctx.clone();
            let r = mimir_core::models::add_with_progress(&name, move |f| {
                set_job(Some(Job::Adding(n.clone(), f)));
                c2.request_repaint();
            });
            set_job(Some(match r {
                Ok(()) => Job::Done(format!("Added {name}.")),
                Err(e) => Job::Failed(e),
            }));
            ctx.request_repaint();
        });
}

fn start_remove(ctx: &egui::Context, name: String) {
    set_job(Some(Job::Removing(name.clone())));
    let ctx = ctx.clone();
    let _ = std::thread::Builder::new()
        .name("free-model".into())
        .spawn(move || {
            set_job(Some(if mimir_core::models::remove(&name) {
                Job::Done(format!("Removed {name}."))
            } else {
                Job::Failed(format!(
                    "{name} could not be removed. Close what is using it, then try again."
                ))
            }));
            ctx.request_repaint();
        });
}

/// The engine's pick as last read (engine.json, read again every 10 s).
fn engine_pick() -> Option<mimir_core::engine::EngineFile> {
    use std::sync::Mutex;
    use std::time::{Duration, Instant};
    type Seen = Option<(Instant, Option<mimir_core::engine::EngineFile>)>;
    static SEEN: Mutex<Seen> = Mutex::new(None);
    let mut g = SEEN.lock().ok()?;
    if g.as_ref()
        .is_none_or(|(t, _)| t.elapsed() > Duration::from_secs(10))
    {
        *g = Some((Instant::now(), mimir_core::engine::load()));
    }
    g.as_ref().and_then(|(_, e)| e.clone())
}

/// Where chats run and how fast, and the model's name.
fn chats_card(ui: &mut egui::Ui) {
    theme::card().show(ui, |ui| {
        ui.set_width(ui.available_width());
        theme::heading(ui, "Chats");
        let e = engine_pick();
        ui.label(chats_words(e.as_ref()));
        if let Some(p) = e.as_ref().and_then(|e| e.chat.as_ref()) {
            ui.label(small(format!(
                "{} · first words in {:.1} s",
                p.title,
                p.first_token_ms as f32 / 1000.0
            )));
        }
    });
}

/// Where chats run. The coding check under "On this PC" is another test:
/// until the chat check has run, say so instead of "hasn't tested".
fn chats_words(e: Option<&mimir_core::engine::EngineFile>) -> String {
    let words = mimir_core::engine::words_shown(e);
    if words == "Your PC hasn't tested its models yet" {
        "Not checked for chats yet".into()
    } else {
        // "GPU + CPU" in plain words (the words come from the PC's engine).
        words.replace(
            "graphics card (GPU + CPU)",
            "graphics card and processor together",
        )
    }
}

fn small(text: impl Into<String>) -> RichText {
    RichText::new(text).color(MUTED).size(13.0)
}

fn gb(bytes: u64) -> String {
    format!("{:.1} GB", bytes as f64 / 1e9)
}

fn tag(ui: &mut egui::Ui, t: &str) {
    let color = if t == "Uncensored" { ORANGE } else { JADE };
    egui::Frame::new()
        .stroke(egui::Stroke::new(1.0, color))
        .corner_radius(egui::CornerRadius::same(6))
        .inner_margin(egui::Margin::symmetric(6, 1))
        .show(ui, |ui| {
            let r = ui.label(RichText::new(t).color(color).size(12.5));
            if t == "Uncensored" {
                r.on_hover_text(mimir_core::models::REFUSALS_REMOVED);
            }
        });
}

/// What powers chats and project work, in a few words (General shows it).
pub fn power_words(s: &Settings) -> String {
    let chats = if s.chats_paid() {
        "Chats online"
    } else {
        "Chats on your PC"
    };
    let work = if s.project_free() {
        "project work on your PC"
    } else {
        "project work online (your PC's model when it can't)"
    };
    format!("{chats}; {work}.")
}

/// The label of the choice that sends words out: it names where they go.
const ONLINE_CHOICE: &str = "Online (your words go to Anthropic)";

/// What "Remove" asks first, per model (temp data).
fn confirm_id(name: &str) -> egui::Id {
    egui::Id::unique(("remove-free-model", name))
}

/// A Remove… button that asks once more, in place.
fn remove_button(ui: &mut egui::Ui, name: &str, working: bool) {
    let asked = confirm_id(name);
    let asking = ui.data(|d| d.get_temp::<bool>(asked)).unwrap_or(false);
    if asking {
        ui.label(
            RichText::new("Remove it from your PC?")
                .color(RED)
                .size(13.0),
        );
        if ui.add_enabled(!working, theme::quiet("Remove")).clicked() {
            ui.data_mut(|d| d.remove::<bool>(asked));
            start_remove(ui.ctx(), name.to_string());
        }
        if ui.add(theme::quiet("Keep")).clicked() {
            ui.data_mut(|d| d.remove::<bool>(asked));
        }
    } else if ui
        .add_enabled(!working, theme::quiet("Remove…"))
        .on_hover_text("Frees up space. You can download it again.")
        .clicked()
    {
        ui.data_mut(|d| d.insert_temp(asked, true));
    }
}

/// The download or removal under way, or how the last one went.
fn job_line(ui: &mut egui::Ui) {
    match job() {
        Some(Job::Adding(n, f)) => {
            ui.label(small(format!("Downloading {n}… {:.0}%", f * 100.0)));
            ui.add(
                egui::ProgressBar::new(f)
                    .desired_height(6.0)
                    .fill(JADE)
                    .animate(f <= 0.0),
            );
        }
        Some(Job::Removing(n)) => {
            ui.horizontal(|ui| {
                ui.spinner();
                ui.label(small(format!("Removing {n}…")));
            });
        }
        Some(Job::Done(t)) => {
            ui.label(RichText::new(t).color(JADE).size(13.0));
            ui.ctx()
                .request_repaint_after(std::time::Duration::from_secs(NOTE_SECS));
        }
        Some(Job::Failed(e)) => {
            ui.label(RichText::new(e).color(RED).size(13.0));
            ui.ctx()
                .request_repaint_after(std::time::Duration::from_secs(NOTE_SECS));
        }
        None => {}
    }
}

impl App {
    pub(crate) fn models_page(&mut self, ui: &mut egui::Ui) {
        chats_card(ui);
        ui.add_space(10.0);
        self.power_card(ui);
        ui.add_space(10.0);
        self.ground_rules_card(ui);
        ui.add_space(10.0);
        self.on_this_pc_card(ui);
        ui.add_space(10.0);
        store_card(ui);
        ui.add_space(10.0);
        phone_models_card(ui);
        ui.add_space(10.0);
        self.weekly_card(ui);
        ui.add_space(10.0);
        // Claude is one optional section at the end: its setup, and its
        // models and prices only once a plan is connected.
        self.setup.show(ui);
        // Their models stay in view for anyone with Claude installed or
        // online chosen, also while signed out; the price list is only for
        // a connected plan.
        let online = self.settings.online_set_up();
        if self.setup.claude_installed() == Some(true) || online {
            ui.add_space(10.0);
            claude_card(ui, self.setup.claude_ready() == Some(true));
        }
    }

    /// Ground rules: sent with every task, Claude's and the free models'.
    fn ground_rules_card(&mut self, ui: &mut egui::Ui) {
        use mimir_core::settings::DEFAULT_GROUND_RULES;
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, crate::labels::GROUND_RULES);
            let mut on = self.settings.ground_rules_on != Some(false);
            if ui
                .checkbox(&mut on, crate::labels::GROUND_RULES_SEND)
                .changed()
            {
                self.settings.ground_rules_on = if on { None } else { Some(false) };
                self.settings_dirty = true;
            }
            let mut text = self
                .settings
                .ground_rules
                .clone()
                .unwrap_or_else(|| DEFAULT_GROUND_RULES.to_string());
            let edit = ui.add_enabled(
                on,
                egui::TextEdit::multiline(&mut text)
                    .desired_rows(5)
                    .desired_width(f32::INFINITY),
            );
            crate::menus::edit_menu(&edit, "ground-rules");
            if edit.changed() {
                self.settings.ground_rules = (text != DEFAULT_GROUND_RULES).then_some(text);
                self.settings_dirty = true;
            }
            ui.label(small(crate::labels::GROUND_RULES_NOTE));
            if self.settings.ground_rules.is_some()
                && ui.add(theme::quiet("Reset to default")).clicked()
            {
                self.settings.ground_rules = None;
                self.settings_dirty = true;
            }
        });
    }

    /// The two choices (Stage 1, 1.3): what answers chats, and what does
    /// project work. A free model is always the backup.
    fn power_card(&mut self, ui: &mut egui::Ui) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Who answers");
            let paid = self.settings.chats_paid();
            // While the first check runs, nothing is greyed out or hinted.
            let ready = self.setup.claude_ready() != Some(false);
            ui.horizontal_wrapped(|ui| {
                ui.label("Chats and look-ups:");
                if ui.radio(!paid, "Your PC's model").clicked() && paid {
                    self.settings.chats_engine = Some("free".into());
                    self.settings_dirty = true;
                }
                if ui
                    .add_enabled(ready || paid, egui::RadioButton::new(paid, ONLINE_CHOICE))
                    .clicked()
                    && !paid
                {
                    self.settings.chats_engine = Some("paid".into());
                    self.settings_dirty = true;
                }
            });
            if paid {
                // Which Claude model answers chats (every chat on this PC).
                ui.horizontal_wrapped(|ui| {
                    ui.label("Model for online chats:");
                    let now = self.settings.chats_model_alias().unwrap_or("auto");
                    for (id, name, about) in mimir_core::talk::CLAUDE_CHOICES {
                        if ui
                            .radio(now == id, name)
                            .on_hover_text(about)
                            .clicked()
                            && now != id
                        {
                            self.settings.chats_model = Some(id.into());
                            self.settings_dirty = true;
                        }
                    }
                });
                ui.label(small("Applies to all chats on this PC. Auto-pick lets Mímir choose."));
            }
            let free = self.settings.project_free();
            ui.horizontal_wrapped(|ui| {
                ui.label("Project work:");
                if ui.radio(free, "Your PC's model").clicked() && !free {
                    self.settings.set_power(Power::FreeOnly);
                    self.settings_dirty = true;
                }
                if ui
                    .add_enabled(ready || !free, egui::RadioButton::new(!free, ONLINE_CHOICE))
                    .clicked()
                    && free
                {
                    self.settings.set_power(Power::Backup);
                    self.settings_dirty = true;
                }
            });
            if !ready && free && !paid {
                ui.label(small(
                    "Online is optional. To use it, set up an Anthropic plan in the last section of this page.",
                ));
            }
            // One switch, shown the same on the phone's Status.
            ui.add_space(4.0);
            let has_model = !mimir_core::models::cached().is_empty();
            let mut on = has_model && self.settings.free_when_out_on();
            if ui
                .add_enabled(
                    has_model,
                    egui::Checkbox::new(&mut on, crate::labels::FREE_WHEN_OUT),
                )
                .changed()
            {
                self.settings.free_when_out = Some(on);
                self.settings_dirty = true;
            }
            ui.label(small(if has_model {
                crate::labels::FREE_WHEN_OUT_ABOUT
            } else {
                crate::labels::FREE_WHEN_OUT_NO_MODEL
            }));
        });
    }

    fn on_this_pc_card(&mut self, ui: &mut egui::Ui) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "On this PC");
            ui.label(small(format!(
                "Your PC's models run on this PC: private, and they cost nothing to use. Whisper mode \
                 needs one. {}",
                mimir_core::models::FIT_NOTE
            )));
            ui.add_space(4.0);
            let models = mimir_core::models::cached();
            if mimir_core::models::program_off() {
                program_off_line(ui, !models.is_empty());
            } else if models.is_empty() {
                ui.label(small(
                    "No models on your PC yet. Download one \
                     from the Model Store below.",
                ));
            }
            let sizes = sizes();
            let used = mimir_core::keep_current::last_used();
            let backup = self
                .settings
                .free_model
                .clone()
                .filter(|m| models.iter().any(|f| &f.id == m))
                .or_else(|| mimir_core::models::default_free(&models).map(|f| f.id.clone()));
            let working = busy();
            let online_used = self.settings.online_set_up();
            let names = crate::models::show_names(&models);
            let chat_pick = if self.settings.chats_paid() {
                None
            } else {
                engine_pick().and_then(|e| e.chat).map(|c| c.model)
            };
            for (m, (shown, apart)) in models.iter().zip(&names) {
                ui.add_space(4.0);
                ui.horizontal_wrapped(|ui| {
                    ui.label(RichText::new(shown).strong());
                    if let Some(a) = apart {
                        ui.label(small(format!("({a})")));
                    }
                    if m.refusals_removed {
                        tag(ui, "Uncensored");
                    }
                    if chat_pick.as_deref() == Some(m.name.as_str()) {
                        tag(ui, "Answers your chats");
                    }
                    if online_used && backup.as_deref() == Some(m.id.as_str()) {
                        tag(ui, "Used when your online plan runs out");
                    }
                });
                ui.horizontal_wrapped(|ui| {
                    let mut line = crate::models::plain_test_note(&m.note);
                    if let Some(b) = sizes.get(&m.name) {
                        line = format!("{} · {line}", gb(*b));
                    }
                    if let Some(t) = used.get(&m.name) {
                        line.push_str(&format!(" · used {}", crate::view_windows::ago(*t as i64)));
                    }
                    ui.label(small(line));
                    if online_used
                        && backup.as_deref() != Some(m.id.as_str())
                        && !m.note.starts_with("Didn't")
                        && ui
                            .add(theme::quiet("Use when your online plan runs out"))
                            .on_hover_text("The model on your PC that takes over when your Anthropic plan runs out")
                            .clicked()
                    {
                        self.settings.free_model = Some(m.id.clone());
                        self.settings_dirty = true;
                    }
                    remove_button(ui, &m.name, working);
                });
            }
            ui.add_space(6.0);
            job_line(ui);
            ui.label(small(format!(
                "One you don't use for {} days is removed to save space. It downloads again if you pick it.",
                mimir_core::keep_current::FREE_UNUSED_DAYS
            )));
        });
    }

    fn weekly_card(&mut self, ui: &mut egui::Ui) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Worth switching?");
            let mut on = self.settings.weekly_model_check != Some(false);
            if ui
                .checkbox(
                    &mut on,
                    "Once a week, look for a better model for the phone",
                )
                .changed()
            {
                self.settings.weekly_model_check = Some(on);
                self.settings_dirty = true;
            }
            ui.label(small(
                "Once a week Mímir checks whether a better small model exists for your phone. If \
                 one does, it tells you here and under Updates on the phone. Nothing changes \
                 until you say so.",
            ));
            ui.add_space(4.0);
            match mimir_core::keep_current::last_weekly() {
                None => {
                    ui.label(small("It hasn't run yet: the first look is within a week."));
                }
                Some(r) => {
                    let when = crate::view_windows::ago(r.at as i64);
                    match &r.found {
                        Some(t) => {
                            ui.label(small(format!("Last look, {when}:")));
                            ui.label(RichText::new(t).color(ORANGE));
                        }
                        None => {
                            let line = r
                                .said
                                .last()
                                .cloned()
                                .unwrap_or_else(|| "No better phone model.".into());
                            ui.label(small(format!("Last look, {when}: {line}")));
                        }
                    }
                }
            }
        });
    }
}

/// The installed models' sizes (looked up off the window's thread, at most
/// every 30 s).
fn sizes() -> std::collections::HashMap<String, u64> {
    use std::time::{Duration, Instant};
    type Seen = Option<(Instant, std::collections::HashMap<String, u64>)>;
    static SEEN: Mutex<Seen> = Mutex::new(None);
    static LOOKING: Mutex<bool> = Mutex::new(false);
    let (fresh, list) = match SEEN.lock() {
        Ok(g) => match g.as_ref() {
            Some((t, l)) => (t.elapsed() < Duration::from_secs(30), l.clone()),
            None => (false, Default::default()),
        },
        Err(_) => (true, Default::default()),
    };
    if !fresh
        && LOOKING
            .lock()
            .is_ok_and(|mut g| !std::mem::replace(&mut *g, true))
    {
        let _ = std::thread::Builder::new()
            .name("free-model-sizes".into())
            .spawn(|| {
                let s = mimir_core::keep_current::ollama_sizes();
                if let Ok(mut g) = SEEN.lock() {
                    *g = Some((Instant::now(), s));
                }
                if let Ok(mut g) = LOOKING.lock() {
                    *g = false;
                }
            });
    }
    list
}

fn claude_card(ui: &mut egui::Ui, connected: bool) {
    theme::card().show(ui, |ui| {
        ui.set_width(ui.available_width());
        theme::heading(ui, "Online models");
        ui.label(small(
            "Pick a model for each task under the message box (Model ▾), or let Auto pick. \
             Strongest first:",
        ));
        ui.add_space(4.0);
        for m in &crate::models::MODELS {
            ui.horizontal_wrapped(|ui| {
                ui.label(RichText::new(m.name).strong()).on_hover_text(m.id);
                ui.label(small(m.short));
            });
        }
        ui.horizontal_wrapped(|ui| {
            ui.label(RichText::new("Auto").strong());
            ui.label(small("picks one of these for each step"));
        });
        if !connected {
            ui.label(small(
                "Not connected yet. Use the plan section above to sign in.",
            ));
            return;
        }
        let open_id = egui::Id::unique("models-about-open");
        let open = ui
            .data_mut(|d| d.remove_temp::<bool>(open_id))
            .unwrap_or(false);
        egui::CollapsingHeader::new(
            RichText::new("Prices, and how fast each uses your Anthropic plan").size(13.5),
        )
        .id_salt("models-about")
        .open(open.then_some(true))
        .show(ui, crate::models::about);
    });
}

/// The Model Store's first page: the free models tested on this PC, as
/// cards with what each is good at, its size, whether it fits, our test,
/// details, and Download or Remove.
fn store_card(ui: &mut egui::Ui) {
    theme::card().show(ui, |ui| {
        ui.set_width(ui.available_width());
        theme::heading(ui, "Model Store");
        ui.label(small(
            "Models we tested on a PC like yours, best for coding first. Download one and it \
             shows under On this PC.",
        ));
        ui.add_space(6.0);
        let installed: Vec<String> = mimir_core::models::cached()
            .into_iter()
            .map(|m| m.name)
            .collect();
        // Off with nothing known: what is installed can't be told, so
        // nothing is offered to download.
        let off = mimir_core::models::program_off();
        if off {
            program_off_line(ui, !installed.is_empty());
        }
        let vram = mimir_core::models::graphics_memory();
        let working = busy();
        for p in mimir_core::models::STORE.iter().filter(|p| !p.engine_only) {
            egui::Frame::new()
                .stroke(egui::Stroke::new(1.0, theme::LINE))
                .corner_radius(egui::CornerRadius::same(theme::RADIUS))
                .inner_margin(egui::Margin::same(10))
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    let (fits, fit) = mimir_core::models::fit_words(p.bytes, vram);
                    ui.horizontal_wrapped(|ui| {
                        ui.label(RichText::new(p.title).strong().size(15.5));
                        for t in p.tags {
                            tag(ui, t);
                        }
                    });
                    let here = installed.iter().any(|n| n == p.name);
                    // A model already running here is never "too big" for it.
                    let fit_words = if here || fits {
                        "fits your PC"
                    } else {
                        "too big for your graphics card"
                    };
                    ui.label(small(format!(
                        "{} · {} · tested here: {}",
                        gb(p.bytes),
                        fit_words,
                        crate::models::plain_test_note(p.tested)
                    )));
                    ui.horizontal_wrapped(|ui| {
                        let have = installed.iter().any(|n| n == p.name);
                        let downloading =
                            matches!(job(), Some(Job::Adding(ref n, _)) if n == p.name);
                        if have {
                            ui.label(RichText::new("On this PC").color(JADE).size(13.0));
                            remove_button(ui, p.name, working);
                        } else if downloading {
                            ui.label(small("Downloading…"));
                        } else if ui
                            .add_enabled(!working && !off, theme::primary("Download"))
                            .on_hover_text(gb(p.bytes).to_string())
                            .clicked()
                        {
                            start_download(ui.ctx(), p.name.to_string());
                        }
                    });
                    egui::CollapsingHeader::new(RichText::new("Details").size(13.0))
                        .id_salt(("store-details", p.name))
                        .show(ui, |ui| {
                            ui.label(RichText::new(p.about).size(13.5));
                            ui.label(small(format!("Name to look up: {}", p.name)));
                            ui.label(small(fit));
                            ui.label(small(format!(
                                "Our test (a coding job with two bugs to fix): {}.",
                                crate::models::plain_test_note(p.tested)
                            )));
                            if let Some(l) = p.licence {
                                ui.label(small(format!("Licence: {l}")));
                            }
                        });
                });
            ui.add_space(6.0);
        }
        job_line(ui);
        // The store's next pages (0.20): categories, search and more from
        // Ollama's library. Until then, any model by its name.
        egui::CollapsingHeader::new(RichText::new("Add a model by name").size(13.5))
            .id_salt("store-by-name")
            .show(ui, |ui| add_by_name(ui, working));
    });
}

fn add_by_name(ui: &mut egui::Ui, working: bool) {
    let id = egui::Id::unique("add-free-model");
    let mut name: String = ui.data(|d| d.get_temp(id)).unwrap_or_default();
    ui.label(small(
        "Not tested by us. Type the name of a free model to add it.",
    ));
    ui.horizontal(|ui| {
        let said = ui.label("Add a model:");
        let edit = ui
            .add(
                egui::TextEdit::singleline(&mut name)
                    .hint_text("name of a free model")
                    .desired_width(240.0),
            )
            .labelled_by(said.id);
        crate::menus::edit_menu(&edit, "add-model-name");
        let go = ui
            .add_enabled(!working && !name.trim().is_empty(), theme::primary("Add"))
            .on_hover_text("Downloads the model to your PC. Big ones take a while.");
        let enter = edit.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
        if (go.clicked() || (enter && !working)) && !name.trim().is_empty() {
            start_download(ui.ctx(), name.trim().to_string());
            name.clear();
        }
    });
    ui.data_mut(|d| d.insert_temp(id, name));
}

/// Your PC's model program doesn't answer: say so, with the way to start it.
/// `known` when a list from before is still shown.
fn program_off_line(ui: &mut egui::Ui, known: bool) {
    ui.horizontal_wrapped(|ui| {
        ui.label(small(if known {
            "Your PC's model program is off. These are the models it had."
        } else {
            "Your PC's model program is off, so its models can't be listed. Start it to see them."
        }));
        if ui.add(theme::quiet("Start it")).clicked() {
            mimir_core::models::start_program();
        }
    });
}

fn phone_models_card(ui: &mut egui::Ui) {
    theme::card().show(ui, |ui| {
        ui.set_width(ui.available_width());
        theme::heading(ui, "On the phone");
        ui.label(small(
            "Models the phone can run by itself, with no PC and no internet. \
             Download, switch or delete them on the phone: Settings › Models › On this phone.",
        ));
        ui.add_space(4.0);
        let list = mimir_core::keep_current::standalone_catalogue();
        for m in list["models"].as_array().into_iter().flatten() {
            ui.horizontal_wrapped(|ui| {
                ui.label(RichText::new(m["name"].as_str().unwrap_or("A model")).strong());
                if m["refusals_removed"].as_bool() == Some(true) {
                    tag(ui, "Uncensored");
                }
                ui.label(small(format!(
                    "{} · {}",
                    m["note"].as_str().unwrap_or_default(),
                    gb(m["bytes"].as_u64().unwrap_or(0))
                )));
            });
        }
    });
}

/// Settings › Storage: everything Mímir downloaded, with its size
/// and Remove, and the rules that clear things by themselves.
pub fn storage(ui: &mut egui::Ui) {
    theme::card().show(ui, |ui| {
        ui.set_width(ui.available_width());
        theme::heading(ui, "Models on this PC");
        let models = mimir_core::models::cached();
        let sizes = sizes();
        let working = busy();
        if models.is_empty() {
            ui.label(small(if mimir_core::models::program_off() {
                "Not known while your PC's model program is off."
            } else {
                "None on this PC."
            }));
        }
        let mut total = 0;
        let names = crate::models::show_names(&models);
        for (m, (shown, apart)) in models.iter().zip(&names) {
            ui.horizontal_wrapped(|ui| {
                let b = sizes.get(&m.name).copied().unwrap_or(0);
                total += b;
                ui.label(RichText::new(shown).strong());
                if let Some(a) = apart {
                    ui.label(small(format!("({a})")));
                }
                if b > 0 {
                    ui.label(small(gb(b)));
                }
                remove_button(ui, &m.name, working);
            });
        }
        if total > 0 {
            ui.label(small(format!("Together {}.", gb(total))));
        }
        job_line(ui);
        ui.label(small(format!(
            "A model you don't use for {} days is removed to save space.",
            mimir_core::keep_current::FREE_UNUSED_DAYS
        )));
    });
    ui.add_space(10.0);
    theme::card().show(ui, |ui| {
        ui.set_width(ui.available_width());
        theme::heading(ui, "Voice and speech");
        let status = crate::places::voice_status();
        // A voice that isn't in this copy is not listed as if it were here.
        if !status.starts_with("Not in this copy") {
            ui.horizontal_wrapped(|ui| {
                ui.label(RichText::new("Natural voice (Ingrid)").strong());
                ui.label(small(&status));
                if status == "Ready" && ui.add(theme::quiet("Remove")).clicked() {
                    crate::places::delete_voice();
                }
            });
        }
        ui.horizontal_wrapped(|ui| {
            ui.label(RichText::new("Speech for Talk").strong());
            let ready = crate::places::listen_ready();
            ui.label(small(if ready {
                format!("Ready · about {} MB", mimir_core::listen::DOWNLOAD_MB)
            } else {
                "Not downloaded".into()
            }));
            if ready && ui.add(theme::quiet("Remove")).clicked() {
                crate::places::delete_listen();
            }
        });
        ui.label(small("Each downloads again when you next need it."));
    });
    ui.add_space(10.0);
    theme::card().show(ui, |ui| {
        ui.set_width(ui.available_width());
        theme::heading(ui, "Phone models");
        ui.label(small(
            "Models on the phone use the phone's space. See and delete them on the phone, under \
             Settings › Storage. One you don't use for 14 days is removed to save space.",
        ));
    });
}

#[cfg(test)]
mod note_tests {
    use super::*;
    use std::time::{Duration, Instant};

    /// PCW-17: "Added X." and a failure go by themselves; a download under
    /// way stays; leaving the page clears the note.
    #[test]
    fn a_finished_jobs_note_goes_away() {
        let long_ago = Instant::now()
            .checked_sub(Duration::from_secs(NOTE_SECS + 1))
            .expect("a clock that has run a few seconds");
        set_job(Some(Job::Done("Added x.".into())));
        assert_eq!(job(), Some(Job::Done("Added x.".into())), "gone at once");
        *JOB.lock().unwrap() = Some((Job::Done("Added x.".into()), long_ago));
        assert_eq!(job(), None, "the note stayed");
        *JOB.lock().unwrap() = Some((Job::Failed("no".into()), long_ago));
        assert_eq!(job(), None, "the failure stayed");
        // A download under way is never dropped by the clock.
        *JOB.lock().unwrap() = Some((Job::Adding("x".into(), 0.5), long_ago));
        assert_eq!(job(), Some(Job::Adding("x".into(), 0.5)));
        // Leaving the page clears a finished note, not a download.
        forget_job_note();
        assert!(job().is_some());
        set_job(Some(Job::Failed("no".into())));
        forget_job_note();
        assert_eq!(job(), None);
    }
}
