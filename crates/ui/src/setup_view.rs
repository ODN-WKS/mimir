//! The optional Claude setup in Settings › Models: install and sign in to
//! Claude Code, for people who want a Claude plan. Nothing here shows up
//! until a person opens that section.

use crate::theme::{self, JADE, MUTED, RED};
use eframe::egui::{self, RichText};
use mimir_core::setup::{self, ClaudeStatus, INSTALL_CLAUDE, Job, JobState};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Waiting for a sign-in in a terminal window to finish.
struct Watch {
    started: Instant,
    last_check: Instant,
    /// The account signed in when it started.
    before: Option<String>,
    /// Signed out was seen since (switching account signs out first).
    saw_signed_out: bool,
}

/// How often, and for how long, to check while a sign-in is open.
/// What the card says first: Claude is an option, and where the words go.
pub(crate) const OPTIONAL_NOTE: &str =
    "Optional: connect a Claude plan for heavy coding. Those words then go to Anthropic.";

const WATCH_EVERY: Duration = Duration::from_secs(3);
const WATCH_FOR: Duration = Duration::from_secs(15 * 60);

#[derive(Default)]
struct Snapshot {
    claude: Option<ClaudeStatus>,
    checking: bool,
}

pub struct SetupPanel {
    claude: PathBuf,
    snap: Arc<Mutex<Snapshot>>,
    job: Option<Job>,
    job_seen: bool,
    /// A sign-in is open in a terminal window; checked until it finishes.
    watching: Option<Watch>,
    ctx: egui::Context,
}

impl SetupPanel {
    pub fn new(ctx: &egui::Context, claude: PathBuf) -> Self {
        let p = SetupPanel {
            claude,
            snap: Arc::new(Mutex::new(Snapshot::default())),
            job: None,
            job_seen: false,
            watching: None,
            ctx: ctx.clone(),
        };
        p.refresh();
        p
    }

    /// Checks Claude Code in the background.
    pub fn refresh(&self) {
        {
            let mut s = self.snap.lock().unwrap();
            if s.checking {
                return;
            }
            s.checking = true;
        }
        let snap = self.snap.clone();
        let claude = self.claude.clone();
        let ctx = self.ctx.clone();
        std::thread::spawn(move || {
            let c = setup::claude_status(&claude);
            let mut s = snap.lock().unwrap();
            s.claude = Some(c);
            s.checking = false;
            ctx.request_repaint();
        });
    }

    /// Whether Claude Code is installed (None while the first check runs).
    pub fn claude_installed(&self) -> Option<bool> {
        let s = self.snap.lock().unwrap();
        s.claude.as_ref().map(|c| c.version.is_some())
    }

    /// Whether Claude Code is installed and signed in (None while the
    /// first check runs). Only Settings reads this; nothing else in the
    /// window asks a person to set Claude up.
    pub fn claude_ready(&self) -> Option<bool> {
        let s = self.snap.lock().unwrap();
        s.claude
            .as_ref()
            .map(|c| c.version.is_some() && c.signed_in)
    }

    fn wake(&self) -> impl Fn() + Send + Sync + 'static {
        let ctx = self.ctx.clone();
        move || ctx.request_repaint()
    }

    /// Claude Code's sign-in asks questions, so it gets a terminal window
    /// and is checked on until it finishes; without a terminal it runs
    /// here and its output is shown.
    fn start_sign_in(&mut self, cmd: String) {
        if setup::in_terminal(&cmd, None) {
            let (signed_in, before) = {
                let s = self.snap.lock().unwrap();
                let c = s.claude.as_ref();
                (
                    c.is_some_and(|c| c.signed_in),
                    c.and_then(|c| c.account.clone()),
                )
            };
            let now = Instant::now();
            self.watching = Some(Watch {
                started: now,
                last_check: now,
                before,
                saw_signed_out: !signed_in,
            });
            self.ctx.request_repaint_after(WATCH_EVERY);
        } else {
            self.job_seen = false;
            self.job = Some(Job::start("Signing in to Claude Code", &cmd, self.wake()));
        }
    }

    /// Called every frame, whichever page is showing: while a sign-in is
    /// open in a terminal, checks every few seconds and stops once it
    /// finished, so the window shows the new account without a restart.
    pub fn tick(&mut self) {
        let Some(w) = &mut self.watching else {
            return;
        };
        let claude = self.snap.lock().unwrap().claude.clone();
        if let Some(c) = claude {
            if !c.signed_in {
                w.saw_signed_out = true;
            } else if w.saw_signed_out || c.account != w.before {
                self.watching = None;
                return;
            }
        }
        if w.started.elapsed() > WATCH_FOR {
            self.watching = None;
            return;
        }
        if w.last_check.elapsed() >= WATCH_EVERY {
            w.last_check = Instant::now();
            self.refresh();
        }
        self.ctx.request_repaint_after(Duration::from_secs(1));
    }

    pub fn show(&mut self, ui: &mut egui::Ui) {
        // A finished job changes what is installed or signed in: check
        // again once for each.
        let mut finished = false;
        if let Some(j) = &self.job
            && !j.running()
            && !self.job_seen
        {
            self.job_seen = true;
            finished = true;
        }
        if finished {
            self.refresh();
        }
        let (claude, checking) = {
            let s = self.snap.lock().unwrap();
            (s.claude.clone(), s.checking)
        };
        let busy = self.job.as_ref().is_some_and(Job::running);

        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                theme::heading(ui, "Optional: a Claude plan");

                if checking {
                    ui.spinner();
                } else if ui.link("Check again").clicked() {
                    self.refresh();
                }
            });
            ui.label(RichText::new(OPTIONAL_NOTE).color(MUTED).size(13.0));
            match &claude {
                None => {
                    ui.label(RichText::new("Checking…").color(MUTED));
                }
                Some(c) => {
                    match &c.version {
                        Some(v) => ui.label(format!("Installed: version {v}")),
                        None => ui.label(
                            RichText::new(
                                "Not set up. Project work on your PC's own models also needs Claude Code installed here.",
                            )
                            .color(MUTED),
                        ),
                    };
                    if c.version.is_some() {
                        if c.signed_in {
                            let plan = c
                                .plan
                                .as_deref()
                                .map(|p| format!(" · {p} plan"))
                                .unwrap_or_default();
                            ui.label(
                                RichText::new(format!("Signed in to Claude Code{plan}"))
                                    .color(JADE),
                            );
                        } else {
                            ui.label(RichText::new("Installed, not signed in yet.").color(MUTED));
                        }
                    }
                    ui.horizontal(|ui| {
                        if c.version.is_none() {
                            if ui
                                .add_enabled(!busy, theme::quiet("Install Claude Code"))
                                .clicked()
                            {
                                self.job_seen = false;
                                self.job = Some(Job::start(
                                    "Installing Claude Code",
                                    INSTALL_CLAUDE,
                                    self.wake(),
                                ));
                            }
                        } else {
                            let cmd = format!(
                                "{} update",
                                setup::shell_quote(&self.claude.display().to_string())
                            );
                            if ui
                                .add_enabled(!busy, theme::quiet("Update Claude Code"))
                                .clicked()
                            {
                                self.job_seen = false;
                                self.job =
                                    Some(Job::start("Updating Claude Code", &cmd, self.wake()));
                            }
                            if c.signed_in {
                                if ui
                                    .add_enabled(!busy, theme::quiet("Sign out"))
                                    .on_hover_text("Signs this PC out of Claude Code")
                                    .clicked()
                                {
                                    self.job_seen = false;
                                    self.job = Some(Job::start(
                                        "Signing out of Claude Code",
                                        &setup::claude_logout(&self.claude),
                                        self.wake(),
                                    ));
                                }
                                if ui
                                    .add_enabled(!busy, theme::quiet("Use a different account"))
                                    .on_hover_text(
                                        "Signs out, then opens Claude Code's sign-in in a \
                                         terminal window and your default browser",
                                    )
                                    .clicked()
                                {
                                    self.start_sign_in(setup::claude_switch_account(&self.claude));
                                }
                            } else if ui
                                .add_enabled(!busy, theme::quiet("Sign in to Claude Code"))
                                .on_hover_text(
                                    "Opens Claude Code's sign-in in a terminal window and \
                                     your default browser",
                                )
                                .clicked()
                            {
                                self.start_sign_in(setup::claude_login(&self.claude));
                            }
                        }
                    });
                    if self.watching.is_some() {
                        ui.horizontal(|ui| {
                            ui.spinner();
                            ui.label(
                                RichText::new(
                                    "Finish signing in in the browser; this updates by itself.",
                                )
                                .color(MUTED)
                                .size(13.0),
                            );
                        });
                    }
                }
            }
        });
        if let Some(j) = &self.job {
            ui.add_space(8.0);
            theme::card().show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    theme::heading(ui, &j.title);
                    match j.state() {
                        JobState::Running => {
                            ui.spinner();
                        }
                        JobState::Done { ok: true } => {
                            ui.label(RichText::new("Done").color(JADE));
                        }
                        JobState::Done { ok: false } => {
                            ui.label(RichText::new("Did not work").color(RED));
                        }
                    }
                });
                job_output(ui, &j.lines());
            });
        }
    }
}

fn job_output(ui: &mut egui::Ui, lines: &[String]) {
    let tail: Vec<&str> = lines
        .iter()
        .rev()
        .filter(|l| !l.trim().is_empty())
        .take(8)
        .map(String::as_str)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    if !tail.is_empty() {
        ui.add(
            egui::Label::new(
                RichText::new(tail.join("\n"))
                    .monospace()
                    .size(13.0)
                    .color(MUTED),
            )
            .wrap(),
        );
    }
}

/// The one line shown right in a chat or task that is set to Claude while
/// Claude Code is missing or signed out: what is wrong, where to fix it,
/// and a way out on the PC's own model. Returns true when the way out was
/// chosen.
pub(crate) fn claude_missing_line(
    ui: &mut egui::Ui,
    actions: &mut Vec<crate::app::Action>,
) -> bool {
    let mut own = false;
    ui.horizontal_wrapped(|ui| {
        ui.label(RichText::new(CLAUDE_MISSING).color(MUTED).size(13.0));
        if ui.add(theme::quiet("Open Settings › Models")).clicked() {
            actions.push(crate::app::Action::OpenModels);
        }
        if ui
            .add(theme::quiet("Use your PC's own model instead"))
            .clicked()
        {
            own = true;
        }
    });
    own
}

/// What the line says (Claude is the one chosen, and it can't answer yet).
pub(crate) const CLAUDE_MISSING: &str =
    "This is set to Online, but no Anthropic plan is connected on this PC.";
