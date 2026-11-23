//! Settings › Labs, the troubleshooting half: Run checks, and Report a
//! problem (copy it, or open GitHub's form with a few boxes filled in).
//! Mímir never sends a report itself.

use crate::app::{App, SettingsPage};
use crate::theme::{self, JADE, MUTED, RED};
use eframe::egui::{self, RichText};
use mimir_core::labs::{self, CheckLine, Outcome};
use std::sync::mpsc::{Receiver, channel};
use std::time::{Duration, Instant};

pub const CHECKS_TITLE: &str = "Run checks";
pub const CHECKS_HINT: &str =
    "Looks at Claude Code, the models on this PC, your phones, the voice and the space.";
pub const REPORT_TITLE: &str = "Report a problem";
pub const REPORT_HINT: &str = "What happened? What did you expect?";
pub const GITHUB_NOTE: &str =
    "Mímir never sends this itself. You send it on GitHub, which needs a free account.";

/// What the Labs page keeps between frames.
pub struct LabsPanel {
    pub(crate) what: String,
    pub(crate) with_checks: bool,
    pub(crate) with_errors: bool,
    pub(crate) checks: Option<Vec<CheckLine>>,
    running: Option<Receiver<Vec<CheckLine>>>,
    /// The scrubbed notes, read when the page first shows and after Run checks.
    errors: Option<Vec<String>>,
    note: Option<(String, Instant)>,
    /// The address "Report on GitHub" opened (kept for the window checks).
    pub(crate) opened: Option<String>,
}

impl Default for LabsPanel {
    fn default() -> Self {
        LabsPanel {
            what: String::new(),
            with_checks: true,
            with_errors: true,
            checks: None,
            running: None,
            errors: None,
            note: None,
            opened: None,
        }
    }
}

/// The PC's real answers for Run checks. Slow: made on a helper thread.
struct PcProbes {
    claude: mimir_core::setup::ClaudeStatus,
    phones: (usize, usize),
}

impl labs::Probes for PcProbes {
    fn version(&self) -> String {
        crate::VERSION.trim().to_string()
    }
    fn claude_version(&self) -> Option<String> {
        self.claude.version.clone()
    }
    fn claude_signed_in(&self) -> bool {
        self.claude.signed_in
    }
    fn runner(&self) -> Option<(usize, usize)> {
        let base = mimir_core::models::ollama_url();
        mimir_core::ollama::version(&base)?;
        let installed = mimir_core::ollama::get_at(&base, "/api/tags")
            .ok()
            .and_then(|v| v["models"].as_array().map(Vec::len))
            .unwrap_or(0);
        Some((installed, mimir_core::ollama::loaded(&base).len()))
    }
    fn phones(&self) -> (usize, usize) {
        self.phones
    }
    fn voice_ready(&self) -> bool {
        mimir_core::voice::ready(&mimir_core::voice::root(&mimir_core::paths::data_dir()))
    }
    fn free_bytes(&self) -> u64 {
        labs::free_bytes(&mimir_core::paths::data_dir()).unwrap_or(u64::MAX)
    }
}

fn system_words() -> String {
    let name = std::fs::read_to_string("/etc/os-release")
        .ok()
        .and_then(|t| {
            t.lines()
                .find_map(|l| l.strip_prefix("NAME="))
                .map(|n| n.trim_matches('"').to_string())
        })
        .unwrap_or_else(|| "Linux".into());
    format!("{name}, {}", std::env::consts::ARCH)
}

impl App {
    /// Words to hide in a report: this PC's and the user's name, the phones' and the projects'.
    fn labs_names(&self) -> Vec<String> {
        let mut n: Vec<String> = vec![];
        if let Ok(h) = std::fs::read_to_string("/etc/hostname") {
            n.push(h.trim().to_string());
        }
        for k in ["USER", "LOGNAME"] {
            if let Ok(v) = std::env::var(k) {
                n.push(v);
            }
        }
        n.extend(self.phone.names());
        n.extend(self.settings.project_names.values().cloned());
        n.extend(self.settings.projects.iter().filter_map(|p| {
            std::path::Path::new(p)
                .file_name()
                .map(|f| f.to_string_lossy().to_string())
        }));
        n.retain(|s| s.trim().chars().count() >= 3);
        n.sort();
        n.dedup();
        n
    }

    fn labs_facts(&self) -> labs::Facts {
        labs::Facts {
            version: crate::VERSION.trim().to_string(),
            how_installed: if mimir_core::flatpak::active() {
                "Flatpak".into()
            } else {
                "Installed program".into()
            },
            system: system_words(),
            phones_paired: self.phone.count(),
            who_answers: if self.settings.chats_paid() {
                "Online".into()
            } else {
                "On this PC".into()
            },
        }
    }

    fn labs_report_text(&self) -> String {
        let p = &self.labs;
        let on = self.settings.devlog_on();
        labs::build_report(
            &self.labs_facts(),
            p.with_checks
                .then(|| p.checks.clone().unwrap_or_default())
                .as_deref(),
            (p.with_errors && on)
                .then(|| p.errors.clone().unwrap_or_default())
                .as_deref(),
            &p.what,
        )
    }

    fn labs_read_errors(&mut self) {
        let names = self.labs_names();
        self.labs.errors = Some(labs::recent_errors(&mimir_core::devlog::dir(), &names));
    }

    fn labs_run_checks(&mut self, ctx: &egui::Context) {
        if self.labs.running.is_some() {
            return;
        }
        let phones = (self.phone.count(), self.phone.online_count());
        let (tx, rx) = channel();
        self.labs.running = Some(rx);
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let claude = mimir_core::setup::claude_status(&mimir_core::paths::find_claude());
            let lines = labs::run_checks(&PcProbes { claude, phones });
            let _ = tx.send(lines);
            ctx.request_repaint();
        });
        self.labs_read_errors();
    }

    fn labs_open(&mut self, url: &str) {
        self.labs.opened = Some(url.to_string());
        // The window checks never open a browser: MIMIR_NO_BROWSER=<file> gets the address instead.
        match std::env::var_os("MIMIR_NO_BROWSER") {
            Some(file) => {
                let _ = std::fs::write(file, url);
            }
            None => mimir_core::setup::open_url(url),
        }
    }

    /// The two cards under Labs' notes switches: Run checks, and Report a problem.
    pub(crate) fn labs_cards(&mut self, ui: &mut egui::Ui) {
        if self.labs.errors.is_none() {
            self.labs_read_errors();
        }
        if let Some(rx) = &self.labs.running
            && let Ok(lines) = rx.try_recv()
        {
            self.labs.checks = Some(lines);
            self.labs.running = None;
        }
        if self.labs.running.is_some() {
            ui.ctx().request_repaint_after(Duration::from_millis(200));
        }
        ui.add_space(8.0);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, CHECKS_TITLE);
            match (&self.labs.checks, self.labs.running.is_some()) {
                (None, false) => {
                    ui.label(RichText::new(CHECKS_HINT).color(MUTED).size(13.0));
                }
                (Some(list), _) => {
                    for c in list {
                        check_row(ui, c);
                    }
                }
                _ => {}
            }
            if self.labs.running.is_some() {
                ui.label(
                    RichText::new("Checking… this can take a little while.")
                        .color(MUTED)
                        .size(13.0),
                );
            }
            let label = if self.labs.checks.is_some() {
                "Run checks again"
            } else {
                "Run checks"
            };
            if ui
                .add_enabled(self.labs.running.is_none(), theme::quiet(label))
                .clicked()
            {
                let ctx = ui.ctx().clone();
                self.labs_run_checks(&ctx);
            }
        });
        ui.add_space(8.0);
        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, REPORT_TITLE);
            let report = ui.add(
                egui::TextEdit::multiline(&mut self.labs.what)
                    .hint_text(REPORT_HINT)
                    .desired_rows(4)
                    .desired_width(f32::INFINITY),
            );
            crate::menus::edit_menu(&report, "labs-report");
            ui.checkbox(&mut self.labs.with_checks, "Include the last checks");
            let on = self.settings.devlog_on();
            ui.add_enabled_ui(on, |ui| {
                ui.checkbox(
                    &mut self.labs.with_errors,
                    "Include recent errors from the notes",
                );
            });
            if !on {
                ui.label(
                    RichText::new("Turn the notes on above first.")
                        .color(MUTED)
                        .size(13.0),
                );
            }
            let text = self.labs_report_text();
            ui.label(
                RichText::new("This is everything that will be in the report:")
                    .color(MUTED)
                    .size(13.0),
            );
            egui::Frame::new()
                .fill(theme::GROUND)
                .corner_radius(egui::CornerRadius::same(theme::RADIUS))
                .inner_margin(egui::Margin::same(8))
                .show(ui, |ui| {
                    egui::ScrollArea::vertical()
                        .id_salt("labs-report-preview")
                        .max_height(220.0)
                        .show(ui, |ui| {
                            ui.set_width(ui.available_width());
                            ui.label(RichText::new(&text).monospace().size(12.0));
                        });
                });
            ui.horizontal(|ui| {
                if ui.add(theme::primary("Copy report")).clicked() {
                    ui.ctx().copy_text(text.clone());
                    self.labs.note = Some((labs::COPIED.into(), Instant::now()));
                }
                if ui.add(theme::quiet("Report on GitHub")).clicked() {
                    // The whole report is always copied first: the form's address can only carry a short part of it.
                    ui.ctx().copy_text(text.clone());
                    let url = labs::github_link(
                        &labs::title_from(&self.labs.what),
                        &self.labs.what,
                        &labs::summary(&self.labs_facts()),
                    );
                    self.labs_open(&url);
                    self.labs.note = Some((labs::COPIED_PASTE.into(), Instant::now()));
                }
                if let Some((note, at)) = &self.labs.note
                    && at.elapsed() < Duration::from_secs(8)
                {
                    ui.label(RichText::new(note).color(JADE));
                    ui.ctx().request_repaint_after(Duration::from_secs(1));
                }
            });
            ui.label(RichText::new(GITHUB_NOTE).color(MUTED).size(13.0));
        });
    }

    /// "Report a problem…" from the Help card: opens Labs.
    pub(crate) fn open_labs(&mut self) {
        self.view = crate::app::View::Settings;
        self.settings_page = SettingsPage::Labs;
    }
}

fn check_row(ui: &mut egui::Ui, c: &CheckLine) {
    let color = match c.result {
        Outcome::Good => JADE,
        Outcome::Problem => RED,
        Outcome::Skipped => MUTED,
    };
    ui.horizontal_wrapped(|ui| {
        ui.label(RichText::new(c.mark()).color(color).strong());
        ui.label(RichText::new(&c.name).strong());
        ui.label(RichText::new(&c.line).color(MUTED));
    });
    if let Some(n) = &c.next {
        ui.label(RichText::new(n).color(theme::AMBER).size(13.0));
    }
}
