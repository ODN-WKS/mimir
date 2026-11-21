//! The setup part of Settings: Claude Code and GitHub.

use crate::theme::{self, JADE, MUTED, ORANGE, RED};
use eframe::egui::{self, RichText};
use mimir_core::setup::{
    self, ClaudeStatus, GITHUB_DEVICE_PAGE, GITHUB_LOGIN, GithubStatus, INSTALL_CLAUDE, Job,
    JobState,
};
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
const WATCH_EVERY: Duration = Duration::from_secs(3);
const WATCH_FOR: Duration = Duration::from_secs(15 * 60);

#[derive(Default)]
struct Snapshot {
    claude: Option<ClaudeStatus>,
    github: Option<GithubStatus>,
    checking: bool,
}

pub struct SetupPanel {
    claude: PathBuf,
    snap: Arc<Mutex<Snapshot>>,
    job: Option<Job>,
    github_login: Option<Job>,
    job_seen: bool,
    login_seen: bool,
    opened_device_page: bool,
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
            github_login: None,
            job_seen: false,
            login_seen: false,
            opened_device_page: false,
            watching: None,
            ctx: ctx.clone(),
        };
        p.refresh();
        p
    }

    /// Checks Claude Code and GitHub in the background.
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
            let g = setup::github_status();
            let mut s = snap.lock().unwrap();
            s.claude = Some(c);
            s.github = Some(g);
            s.checking = false;
            ctx.request_repaint();
        });
    }

    /// Something the owner must do before tasks can run, in plain words.
    pub fn needs_setup(&self) -> Option<&'static str> {
        let s = self.snap.lock().unwrap();
        match &s.claude {
            Some(c) if c.version.is_none() => Some("Install Claude Code"),
            Some(c) if !c.signed_in => Some("Sign in to Claude Code"),
            _ => None,
        }
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
        for (job, seen) in [
            (&self.job, &mut self.job_seen),
            (&self.github_login, &mut self.login_seen),
        ] {
            if let Some(j) = job
                && !j.running()
                && !*seen
            {
                *seen = true;
                finished = true;
            }
        }
        if finished {
            self.refresh();
        }
        let (claude, github, checking) = {
            let s = self.snap.lock().unwrap();
            (s.claude.clone(), s.github.clone(), s.checking)
        };
        let busy = self.job.as_ref().is_some_and(Job::running);

        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                theme::heading(ui, "Claude Code");
                if checking {
                    ui.spinner();
                } else if ui.link("Check again").clicked() {
                    self.refresh();
                }
            });
            match &claude {
                None => {
                    ui.label(RichText::new("Checking…").color(MUTED));
                }
                Some(c) => {
                    match &c.version {
                        Some(v) => ui.label(format!("Installed: version {v}")),
                        None => ui.label(RichText::new("Not installed yet").color(ORANGE)),
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
                            ui.label(RichText::new("Not signed in to Claude Code").color(ORANGE));
                        }
                    }
                    ui.horizontal(|ui| {
                        if c.version.is_none() {
                            if ui
                                .add_enabled(!busy, theme::primary("Install Claude Code"))
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
                                .add_enabled(!busy, theme::primary("Sign in to Claude Code"))
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
        ui.add_space(8.0);

        theme::card().show(ui, |ui| {
            ui.set_width(ui.available_width());
            theme::heading(ui, "GitHub");
            match &github {
                None => {
                    ui.label(RichText::new("Checking…").color(MUTED));
                }
                Some(g) if !g.gh_installed => {
                    ui.label(RichText::new("GitHub's sign-in tool (gh) is not installed.").color(ORANGE));
                }
                Some(g) => {
                    match &g.account {
                        Some(a) => ui.label(RichText::new(format!("Signed in to GitHub as {a}")).color(JADE)),
                        None => ui.label(RichText::new("Not signed in to GitHub").color(ORANGE)),
                    };
                    ui.label(
                        RichText::new("Used to get Mímir updates from its private repository.")
                            .color(MUTED)
                            .size(13.0),
                    );
                    let signing = self.github_login.as_ref().is_some_and(Job::running);
                    if !signing {
                        let label = if g.account.is_some() { "Sign in again" } else { "Sign in to GitHub" };
                        let button = if g.account.is_some() { theme::quiet(label) } else { theme::primary(label) };
                        if ui.add(button).clicked() {
                            self.opened_device_page = false;
                            self.login_seen = false;
                            self.github_login = Some(Job::start("Signing in to GitHub", GITHUB_LOGIN, self.wake()));
                        }
                    }
                }
            }
            if let Some(j) = &self.github_login {
                let lines = j.lines();
                match (j.state(), setup::parse_device_code(&lines)) {
                    (JobState::Running, Some(code)) => {
                        if !self.opened_device_page {
                            self.opened_device_page = true;
                            ui.ctx().copy_text(code.clone());
                            setup::open_url(GITHUB_DEVICE_PAGE);
                        }
                        ui.add_space(6.0);
                        ui.label("Your browser is opening GitHub. Enter this code there:");
                        ui.horizontal(|ui| {
                            ui.label(RichText::new(&code).monospace().size(26.0).color(JADE).strong());
                            if ui.add(theme::quiet("Copy code")).clicked() {
                                ui.ctx().copy_text(code.clone());
                            }
                            if ui.link("Open the page again").clicked() {
                                setup::open_url(GITHUB_DEVICE_PAGE);
                            }
                        });
                        ui.label(RichText::new("The code is already copied. This updates by itself when you finish.").color(MUTED).size(13.0));
                    }
                    (JobState::Running, None) => {
                        ui.horizontal(|ui| {
                            ui.spinner();
                            ui.label("Asking GitHub for a sign-in code…");
                        });
                    }
                    (JobState::Done { ok: true }, _) => {
                        ui.label(RichText::new("GitHub sign-in finished.").color(JADE));
                    }
                    (JobState::Done { ok: false }, _) => {
                        ui.label(RichText::new("GitHub sign-in did not finish.").color(RED));
                        job_output(ui, &lines);
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
