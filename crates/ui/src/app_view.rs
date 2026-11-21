//! "This app" in Settings: version and updates. Mímir installs and
//! uninstalls like any app, from the software centre.

use crate::theme::{self, JADE, MUTED, ORANGE};
use eframe::egui::{self, RichText};
use mimir_core::install::Layout;
use mimir_core::update::{self, Origin, Source};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

/// The update key this program checks releases with: the built-in one,
/// or, in debug builds only, `MIMIR_TEST_KEY` (the checks sign
/// their test releases with a throwaway key).
pub fn key_in_use(built_in: &str, test_key: Option<String>, debug: bool) -> String {
    match test_key {
        Some(k) if debug && !k.trim().is_empty() => k.trim().to_string(),
        _ => built_in.trim().to_string(),
    }
}

#[derive(Clone, PartialEq)]
pub enum UpdateState {
    Checking,
    UpToDate,
    Available(String),
    Installing(String),
    /// Downloaded and checked: the window opens it with the software centre.
    Downloaded(String, PathBuf),
    /// Opened in the software centre, which finishes the update.
    Opened(String),
    /// Asking before moving to automatic updates (an app installed from a file).
    ConfirmSwitch(String),
    /// Switching to automatic updates, or updating through Flatpak: the
    /// version and how far it has got.
    Updating(String, Option<u8>),
    /// Installed; the running copy is the old one until Mímir restarts.
    RestartNeeded(String),
    /// Restarting: the words to show ("Mímir restarts after the current answer." while work runs).
    Restarting(String),
    Problem(String),
}

pub struct AppPanel {
    version: &'static str,
    /// The key updates must be signed with (hex).
    public_key: String,
    layout: Layout,
    update: Arc<Mutex<UpdateState>>,
    /// Where this copy was installed from (asked in the background).
    origin: Arc<Mutex<Origin>>,
    ctx: egui::Context,
}

impl AppPanel {
    pub fn new(ctx: &egui::Context, version: &'static str, public_key: String) -> Self {
        let p = AppPanel {
            version,
            public_key,
            layout: Layout::for_user(),
            update: Arc::new(Mutex::new(UpdateState::Checking)),
            origin: Arc::new(Mutex::new(Origin::NotFlatpak)),
            ctx: ctx.clone(),
        };
        p.find_origin();
        p.check_for_update();
        p
    }

    /// Asks the releases for a newer version, in the background.
    pub fn check_for_update(&self) {
        *self.update.lock().unwrap() = UpdateState::Checking;
        let state = self.update.clone();
        let current = self.version.to_string();
        let public_key = self.public_key.clone();
        let ctx = self.ctx.clone();
        std::thread::spawn(move || {
            let s = match update::public_from_hex(&public_key) {
                None => UpdateState::Problem("The built-in update key is damaged.".into()),
                Some(key) => match Source::from_env().latest(&key) {
                    Ok(Some(v)) if update::is_newer(&v, &current) => UpdateState::Available(v),
                    Ok(_) => UpdateState::UpToDate,
                    Err(e) => UpdateState::Problem(e),
                },
            };
            *state.lock().unwrap() = s;
            ctx.request_repaint();
        });
    }

    /// Asks Flatpak where this copy came from, in the background.
    fn find_origin(&self) {
        if !mimir_core::flatpak::active() {
            return;
        }
        let (origin, ctx) = (self.origin.clone(), self.ctx.clone());
        std::thread::spawn(move || {
            *origin.lock().unwrap() = update::origin();
            ctx.request_repaint();
        });
    }

    pub fn origin(&self) -> Origin {
        self.origin.lock().unwrap().clone()
    }

    pub fn update_state(&self) -> UpdateState {
        self.update.lock().unwrap().clone()
    }

    pub fn set_update_state(&self, s: UpdateState) {
        *self.update.lock().unwrap() = s;
    }

    /// "Update now" / "Get it": through Flatpak when the app came from
    /// Mímir's source, otherwise by fetching the checked file.
    pub fn start_update(&self, version: String) {
        if matches!(self.origin(), Origin::Source(_)) {
            self.run_flatpak(version, false);
            return;
        }
        *self.update.lock().unwrap() = UpdateState::Installing(version.clone());
        let state = self.update.clone();
        let layout = self.layout.clone();
        let current = self.version.to_string();
        let public_key = self.public_key.clone();
        let ctx = self.ctx.clone();
        std::thread::spawn(move || {
            let s = match update::public_from_hex(&public_key) {
                None => UpdateState::Problem("The built-in update key is damaged.".into()),
                Some(key) => {
                    match update::download(&Source::from_env(), &layout, &key, &current, &version) {
                        Ok(file) => UpdateState::Downloaded(version, file),
                        Err(e) => UpdateState::Problem(e),
                    }
                }
            };
            *state.lock().unwrap() = s;
            ctx.request_repaint();
        });
    }

    /// Runs `flatpak update` (or the switch to automatic updates) on the PC in
    /// the background, with progress.
    pub fn run_flatpak(&self, version: String, switch: bool) {
        *self.update.lock().unwrap() = UpdateState::Updating(version.clone(), None);
        let (state, ctx) = (self.update.clone(), self.ctx.clone());
        let origin = self.origin.clone();
        std::thread::spawn(move || {
            let (s2, c2, v2) = (state.clone(), ctx.clone(), version.clone());
            let mut progress = move |p: u8| {
                *s2.lock().unwrap() = UpdateState::Updating(v2.clone(), Some(p));
                c2.request_repaint();
            };
            let r = if switch {
                let url = std::env::var("MIMIR_REPO_FILE_URL")
                    .unwrap_or_else(|_| update::DEFAULT_REPO_FILE_URL.to_string());
                update::switch_to_source(&url, &mut progress)
            } else {
                update::flatpak_update(&mut progress)
            };
            *state.lock().unwrap() = match r {
                Ok(()) => {
                    if switch {
                        *origin.lock().unwrap() = Origin::Source(update::FLATPAK_REMOTE.into());
                    }
                    UpdateState::RestartNeeded(version)
                }
                Err(e) => UpdateState::Problem(e),
            };
            ctx.request_repaint();
        });
    }

    /// Stops the old background part (after running work), starts the new
    /// one, opens the new window, then closes this one.
    pub fn restart(&self, ctx: &egui::Context) {
        if matches!(self.update_state(), UpdateState::Restarting(_)) {
            return;
        }
        let version = match self.update_state() {
            UpdateState::RestartNeeded(v) => v,
            _ => String::new(),
        };
        *self.update.lock().unwrap() = UpdateState::Restarting(String::new());
        let (state, ctx) = (self.update.clone(), ctx.clone());
        std::thread::spawn(move || {
            let (s2, c2) = (state.clone(), ctx.clone());
            let mut host = mimir_core::restart::Host {
                say: Box::new(move |w| {
                    *s2.lock().unwrap() = UpdateState::Restarting(w.to_string());
                    c2.request_repaint();
                }),
            };
            match mimir_core::restart::restart(&mut host, 1200) {
                Ok(()) => ctx.send_viewport_cmd(egui::ViewportCommand::Close),
                Err(_) => {
                    *state.lock().unwrap() = UpdateState::RestartNeeded(version);
                    ctx.request_repaint();
                }
            }
        });
    }

    pub fn show(&mut self, ui: &mut egui::Ui) {
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "Mímir");
            ui.label(format!("Version {}", self.version));
            ui.label(RichText::new("Font: Cinzel (SIL Open Font License 1.1)").color(MUTED));
            ui.add_space(6.0);
            match self.update_state() {
                UpdateState::Checking => {
                    ui.horizontal(|ui| {
                        ui.spinner();
                        ui.label("Checking for updates…");
                    });
                }
                UpdateState::UpToDate => {
                    ui.horizontal(|ui| {
                        ui.label(
                            RichText::new(format!(
                                "You have the newest version ({}).",
                                self.version
                            ))
                            .color(JADE),
                        );
                        if ui.link("Check again").clicked() {
                            self.check_for_update();
                        }
                    });
                }
                UpdateState::Available(v) => {
                    ui.vertical(|ui| {
                        ui.label(RichText::new(crate::labels::update_ready(&v)).color(JADE));
                        match self.origin() {
                            Origin::Source(_) => {
                                if ui
                                    .add(theme::primary(crate::labels::UPDATE_NOW))
                                    .on_hover_text(
                                        "Mímir gets it from its own source, like any other app",
                                    )
                                    .clicked()
                                {
                                    self.start_update(v.clone());
                                }
                            }
                            Origin::File => {
                                ui.label(
                                    RichText::new(crate::labels::SWITCH_WHY)
                                        .color(MUTED)
                                        .size(13.0),
                                );
                                ui.horizontal(|ui| {
                                    if ui
                                        .add(theme::primary(crate::labels::SWITCH_BUTTON))
                                        .clicked()
                                    {
                                        self.set_update_state(UpdateState::ConfirmSwitch(
                                            v.clone(),
                                        ));
                                    }
                                    if ui.link("Get this one as a file").clicked() {
                                        self.start_update(v.clone());
                                    }
                                });
                            }
                            Origin::NotFlatpak => {
                                if ui
                                    .add(theme::primary("Get it"))
                                    .on_hover_text(
                                        "Downloads it, then opens it in your software centre",
                                    )
                                    .clicked()
                                {
                                    self.start_update(v.clone());
                                }
                            }
                        }
                    });
                }
                UpdateState::ConfirmSwitch(v) => {
                    ui.vertical(|ui| {
                        ui.label(crate::labels::SWITCH_CONFIRM);
                        ui.horizontal(|ui| {
                            if ui.add(theme::primary(crate::labels::SWITCH_YES)).clicked() {
                                self.run_flatpak(v.clone(), true);
                            }
                            if ui.button(crate::labels::SWITCH_NO).clicked() {
                                self.set_update_state(UpdateState::Available(v.clone()));
                            }
                        });
                    });
                }
                UpdateState::Restarting(words) => {
                    ui.horizontal(|ui| {
                        ui.spinner();
                        ui.label(if words.is_empty() {
                            "Restarting Mímir…"
                        } else {
                            words.as_str()
                        });
                    });
                }
                UpdateState::Updating(v, p) => {
                    ui.horizontal(|ui| {
                        ui.spinner();
                        ui.label(crate::labels::updating(&v, p));
                    });
                }
                UpdateState::RestartNeeded(v) => {
                    ui.vertical(|ui| {
                        ui.label(RichText::new(crate::labels::update_installed(&v)).color(JADE));
                        if ui.add(theme::primary(crate::labels::RESTART)).clicked() {
                            let ctx = ui.ctx().clone();
                            self.restart(&ctx);
                        }
                    });
                }
                UpdateState::Installing(v) => {
                    ui.horizontal(|ui| {
                        ui.spinner();
                        ui.label(format!("Getting {v} and checking it…"));
                    });
                }
                UpdateState::Downloaded(v, _) => {
                    ui.horizontal(|ui| {
                        ui.spinner();
                        ui.label(format!("Getting {v} ready…"));
                    });
                }
                UpdateState::Opened(v) => {
                    ui.label(RichText::new(crate::labels::update_opened(&v)).color(JADE));
                }
                UpdateState::Problem(e) => {
                    // The reason stays out of sight: one plain line.
                    let _ = e;
                    ui.label(RichText::new("Couldn't check just now.").color(ORANGE));
                    if ui.link("Check again").clicked() {
                        self.check_for_update();
                    }
                }
            }
        });
    }
}

/// `mimir --check-home`: loads the tasks and settings of the home
/// the usual folder variables point at, without a window and without
/// starting anything, and prints "N projects, N tasks, N messages".
/// Exit code 0 only if everything loads and the counts match the home's
/// `expect.json` (`{"projects": N, "tasks": N, "messages": N}`).
pub fn check_home() -> i32 {
    use mimir_core::task::Entry;
    let mut ok = true;
    let settings = match crate::places::Settings::read() {
        Ok(s) => s,
        Err(e) => {
            eprintln!("settings: {e}");
            ok = false;
            crate::places::Settings::default()
        }
    };
    let dir = mimir_core::store::Store::default_dir();
    // A missing task folder is an empty one; it isn't created.
    let (tasks, bad) = if dir.is_dir() {
        match mimir_core::store::Store::new(&dir) {
            Ok(store) => store.load_all(),
            Err(e) => {
                eprintln!("tasks: {}: {e}", dir.display());
                return 1;
            }
        }
    } else {
        (vec![], vec![])
    };
    for b in &bad {
        eprintln!("task file can't be read: {}", b.display());
        ok = false;
    }
    let projects = crate::places::all_projects(&settings, &tasks).len();
    let messages: usize = tasks
        .iter()
        .map(|t| {
            t.entries
                .iter()
                .filter(|e| matches!(e, Entry::You(_) | Entry::Claude(_)))
                .count()
        })
        .sum();
    println!(
        "{projects} projects, {} tasks, {messages} messages",
        tasks.len()
    );
    let expect_path = crate::places::home().join("expect.json");
    let expect: Option<serde_json::Value> = std::fs::read(&expect_path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok());
    let Some(expect) = expect else {
        eprintln!("{} is missing or can't be read", expect_path.display());
        return 1;
    };
    for (name, got) in [
        ("projects", projects),
        ("tasks", tasks.len()),
        ("messages", messages),
    ] {
        let want = expect.get(name).and_then(serde_json::Value::as_u64);
        if want != Some(got as u64) {
            eprintln!(
                "{name}: expected {}, found {got}",
                want.map_or("nothing".to_string(), |w| w.to_string())
            );
            ok = false;
        }
    }
    if ok { 0 } else { 1 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_key_only_in_debug_builds() {
        let built_in = "aa\n";
        assert_eq!(key_in_use(built_in, None, true), "aa");
        assert_eq!(key_in_use(built_in, Some("bb".into()), true), "bb");
        // A release build never takes the key from the environment.
        assert_eq!(key_in_use(built_in, Some("bb".into()), false), "aa");
        assert_eq!(key_in_use(built_in, Some(" ".into()), true), "aa");
    }
}
