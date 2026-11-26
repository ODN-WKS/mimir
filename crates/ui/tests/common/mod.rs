//! What every window check shares: a private test home, the real window
//! driven through egui_kittest, and the stand-in Claude's logs.
//!
//! A window check changes this process's environment (HOME and the folder
//! variables), so it only runs under `cargo nextest`, which gives each
//! test its own process. A plain `cargo test` skips it.

#![allow(dead_code)]

use eframe::egui::accesskit::Role;
use egui_kittest::Harness;
use egui_kittest::kittest::{NodeT, Queryable};
use station_ui::App;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

/// How long a check waits for something to show before it fails.
pub const PATIENCE: Duration = Duration::from_secs(20);

/// This repository's `crates/` folder.
fn crates_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .to_path_buf()
}

/// The built Mímir program (the approvals hook).
pub fn station_program() -> PathBuf {
    PathBuf::from(env!("CARGO_BIN_EXE_mimir"))
}

/// The stand-in Claude, built with the workspace.
pub fn fake_claude() -> PathBuf {
    let p = station_program().with_file_name("fake-claude");
    assert!(
        p.exists(),
        "build the workspace first (cargo nextest run --workspace builds it): {}",
        p.display()
    );
    p
}

/// A recording from `crates/core/tests/streams`.
pub fn recording(name: &str) -> PathBuf {
    crates_dir().join("core/tests/streams").join(name)
}

/// A recorded Claude Code history file from `crates/core/tests/sessions`.
pub fn session_file(name: &str) -> PathBuf {
    crates_dir().join("core/tests/sessions").join(name)
}

/// `checks/homes`: one folder per older version's home.
pub fn homes_dir() -> PathBuf {
    crates_dir().parent().unwrap().join("checks/homes")
}

/// An older version's home from `checks/homes`.
pub fn check_home(version: &str) -> PathBuf {
    homes_dir().join(version)
}

/// Six letters and digits, different for each check.
fn random_name() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let mut x = nanos ^ ((std::process::id() as u128) << 64);
    let abc = b"abcdefghijklmnopqrstuvwxyz0123456789";
    (0..6)
        .map(|_| {
            x = x
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            abc[((x >> 64) % abc.len() as u128) as usize] as char
        })
        .collect()
}

/// Sets an environment variable. Only window checks call this, and each
/// runs alone in its own process (see the top of this file).
fn set(var: &str, value: impl AsRef<std::ffi::OsStr>) {
    // SAFETY: nextest runs this test alone in its process, before any
    // thread that reads the environment is started.
    unsafe { std::env::set_var(var, value) }
}

pub struct TestHome {
    /// `~/.cache/mimir-checks/t/<six letters>`; HOME points here.
    pub root: PathBuf,
    /// The one project folder in the settings.
    pub project: PathBuf,
    /// The stand-in Claude's recordings for this check (its own copy).
    pub streams: PathBuf,
    /// Raised by the check to let a reply go on (`FAKE_CLAUDE_STEP_FILE`).
    pub step_file: PathBuf,
    /// A named home stays when its check ends (`CHECK_HOME_NAME`).
    pub kept: bool,
}

impl TestHome {
    /// A fresh home with one project. None under a plain `cargo test`.
    pub fn new() -> Option<TestHome> {
        let home = Self::empty()?;
        let project = home.project.clone();
        home.write_settings(serde_json::json!({
            "projects": [project],
            "last_project": project,
            "remote_projects": [],
        }));
        home.tripwire();
        Some(home)
    }

    /// An older version's home: `checks/homes/<version>/` copied in, with
    /// `@ROOT@` replaced by this home in file contents and file names.
    pub fn with_home(version: &str) -> Option<TestHome> {
        let home = Self::empty()?;
        let src = check_home(version);
        assert!(src.is_dir(), "no older home at {}", src.display());
        copy_replacing(&src, &home.root, &home.root.display().to_string());
        home.tripwire();
        Some(home)
    }

    /// The folders and environment, without settings.
    fn empty() -> Option<TestHome> {
        if std::env::var_os("NEXTEST_RUN_ID").is_none() {
            eprintln!("Window check skipped: run it with `cargo nextest run`.");
            return None;
        }
        let real_home = PathBuf::from(std::env::var_os("HOME").expect("HOME"));
        let base = real_home.join(".cache/mimir-checks/t");
        std::fs::create_dir_all(&base).unwrap();
        // A named home (a device test's) is found again by its name, so its
        // phone link keeps its identity and its paired phones across runs.
        let kept = std::env::var("CHECK_HOME_NAME")
            .ok()
            .filter(|n| !n.is_empty() && n.chars().all(|c| c.is_ascii_alphanumeric() || c == '-'));
        let root = base.join(kept.clone().unwrap_or_else(random_name));
        std::fs::create_dir_all(&root).unwrap();
        let root = std::fs::canonicalize(&root).unwrap();
        // Claude Code's own variables would change how the window runs it.
        let inherited: Vec<String> = std::env::vars_os()
            .filter_map(|(k, _)| k.into_string().ok())
            .filter(|k| k == "MIMIRCODE" || k.starts_with("CLAUDE_CODE_"))
            .collect();
        for k in inherited {
            // SAFETY: as in `set`.
            unsafe { std::env::remove_var(k) }
        }
        set("HOME", &root);
        for (var, sub) in [
            ("XDG_CONFIG_HOME", ".config"),
            ("XDG_DATA_HOME", ".local/share"),
            ("XDG_STATE_HOME", ".local/state"),
            ("XDG_CACHE_HOME", ".cache"),
            ("XDG_RUNTIME_DIR", "run"),
            ("CLAUDE_CONFIG_DIR", ".claude"),
            ("MIMIR_UPDATES", "updates"),
        ] {
            let p = root.join(sub);
            std::fs::create_dir_all(&p).unwrap();
            set(var, &p);
        }
        // The runtime folder must be private, as on a real desktop.
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(root.join("run"), std::fs::Permissions::from_mode(0o700)).unwrap();
        let streams = root.join("streams");
        std::fs::create_dir_all(&streams).unwrap();
        let project = root.join("project");
        std::fs::create_dir_all(&project).unwrap();
        set("MIMIR_CLAUDE", fake_claude());
        set("MIMIR_TEST_PROGRAM", station_program());
        // The service (started by the first window) lets this check's
        // window connect: here the test program is the window.
        set(
            "MIMIR_TEST_WINDOW",
            std::env::current_exe().expect("the check's own program"),
        );
        // Phone too's and Remote Control's fixed waits, cut to 50 ms.
        set("STATION_REMOTE_FAST", "1");
        set("FAKE_CLAUDE_STREAMS", &streams);
        // Tasks run in hosts (as on a real PC), started as plain processes
        // (no systemd scopes from checks), which tidy up soon after.
        set("MIMIR_HOSTS", "plain");
        set("STATION_HOST_LINGER_SECS", "5");
        // The chat files' key: a file in this home, never the desktop's keyring.
        set("MIMIR_TALK_KEYFILE", root.join("talk.key"));
        set("FAKE_CLAUDE_ARGS", root.join("claude-args.log"));
        set("FAKE_CLAUDE_CWDS", root.join("claude-cwds.log"));
        set("FAKE_CLAUDE_HOOKS", root.join("claude-hooks.log"));
        set("FAKE_CLAUDE_INPUT", root.join("claude-input.log"));
        let step_file = root.join("step");
        Some(TestHome {
            root,
            project,
            streams,
            step_file,
            kept: kept.is_some(),
        })
    }

    pub fn settings_path(&self) -> PathBuf {
        self.root.join(".config/mimir/settings.json")
    }

    pub fn write_settings(&self, mut v: serde_json::Value) {
        // Checks run project work on Claude (the stand-in) unless they say
        // otherwise; a new install's own default is free.
        if let Some(m) = v.as_object_mut()
            && !m.contains_key("project_engine")
            && !m.contains_key("power")
        {
            m.insert("project_engine".into(), "claude".into());
        }
        let p = self.settings_path();
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, serde_json::to_string_pretty(&v).unwrap()).unwrap();
    }

    /// Stops the check unless everything Mímir would read or write
    /// is inside this home and the Claude it would run is the stand-in.
    pub fn tripwire(&self) {
        let fake = fake_claude();
        for (what, path) in station_ui::places::paths_in_use() {
            if what == "Project work program" {
                assert_eq!(
                    path,
                    fake,
                    "the window would run a real Claude: {}",
                    path.display()
                );
            } else {
                assert!(
                    path.starts_with(&self.root),
                    "the {what} is outside the test home: {}",
                    path.display()
                );
            }
        }
    }

    /// Gives the stand-in Claude a recording under the name it plays it by
    /// (`run-1.jsonl`, `resume.jsonl`, ...).
    pub fn play(&self, as_name: &str, recording_name: &str) {
        std::fs::copy(recording(recording_name), self.streams.join(as_name))
            .unwrap_or_else(|e| panic!("recording {recording_name}: {e}"));
    }

    /// Makes the stand-in Claude wait before each output line until the
    /// check lets it go on (`let_through`).
    pub fn hold_replies(&self) {
        std::fs::write(&self.step_file, "0").unwrap();
        set("FAKE_CLAUDE_STEP_FILE", &self.step_file);
    }

    /// Lets the stand-in Claude print up to its nth output line.
    pub fn let_through(&self, n: u64) {
        std::fs::write(&self.step_file, n.to_string()).unwrap();
    }

    /// Every start of the stand-in Claude, with its arguments.
    pub fn claude_runs(&self) -> Vec<Vec<String>> {
        std::fs::read_to_string(self.root.join("claude-args.log"))
            .unwrap_or_default()
            .lines()
            .filter_map(|l| serde_json::from_str(l).ok())
            .collect()
    }

    /// The folder each start of the stand-in Claude ran in, in order.
    pub fn claude_folders(&self) -> Vec<String> {
        std::fs::read_to_string(self.root.join("claude-cwds.log"))
            .unwrap_or_default()
            .lines()
            .map(str::to_string)
            .collect()
    }

    /// Runs that were conversations (`-p`), not version or sign-in checks.
    pub fn conversations(&self) -> Vec<Vec<String>> {
        self.claude_runs()
            .into_iter()
            .filter(|a| a.iter().any(|x| x == "-p"))
            .collect()
    }

    /// What the approvals hook told Claude, in order: allow, always, deny.
    pub fn hook_answers(&self) -> Vec<String> {
        std::fs::read_to_string(self.root.join("claude-hooks.log"))
            .unwrap_or_default()
            .lines()
            .map(str::to_string)
            .collect()
    }

    /// Every line the stand-in Claude logged as input (messages as JSON,
    /// and "trust: yes" for a trust answer).
    pub fn input_lines(&self) -> Vec<String> {
        std::fs::read_to_string(self.root.join("claude-input.log"))
            .unwrap_or_default()
            .lines()
            .map(str::to_string)
            .collect()
    }

    /// This home's service, started (if needed) and connected to, without a
    /// window: the check's own program is the client.
    pub fn service(&self) -> mimir_core::client::Station {
        mimir_core::client::connect_or_start(&station_program(), "0.15", std::sync::Arc::new(|| {}))
            .expect("the service didn't start")
    }

    /// A task's approvals socket (as `Runner::task_socket` names it).
    pub fn task_socket(&self, task: &str) -> PathBuf {
        use sha2::Digest;
        let h = sha2::Sha256::digest(task.as_bytes());
        let hex: String = h.iter().take(6).map(|b| format!("{b:02x}")).collect();
        self.root
            .join("run/mimir/tasks")
            .join(format!("{hex}.sock"))
    }

    /// The text of every message the stand-in Claude was sent.
    pub fn messages_to_claude(&self) -> Vec<String> {
        std::fs::read_to_string(self.root.join("claude-input.log"))
            .unwrap_or_default()
            .lines()
            .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
            .map(|v| {
                let c = &v["message"]["content"];
                match c.as_str() {
                    Some(t) => t.to_string(),
                    None => c
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(|b| b["text"].as_str())
                        .collect::<Vec<_>>()
                        .join("\n"),
                }
            })
            .collect()
    }

    /// The approvals socket the window listens on.
    pub fn socket(&self) -> PathBuf {
        self.root.join("run/mimir/approvals.sock")
    }

    /// The real window on this home, at the size Mímir opens at.
    pub fn open(&self) -> Harness<'static, App> {
        self.open_at(1280.0, 800.0)
    }

    /// The real window on this home, at another size.
    pub fn open_at(&self, w: f32, h: f32) -> Harness<'static, App> {
        let mut h = Harness::builder()
            .with_size(eframe::egui::Vec2::new(w, h))
            .with_max_steps(400)
            // The window's fonts come from the desktop; text checks need no glyphs.
            .allow_missing_glyphs()
            // Some pickers have no spoken name yet; the checks find widgets
            // by their words, so they don't need one.
            .with_accessibility_check(false)
            .build_eframe(|cc| App::new(cc, "0.15", station_ui::public_key()));
        settle(&mut h);
        h
    }
}

impl TestHome {
    /// The service the first window started for this home, if it runs.
    pub fn service_pid(&self) -> Option<i32> {
        std::fs::read_to_string(self.root.join("run/mimir/service.pid"))
            .ok()?
            .trim()
            .parse()
            .ok()
    }

    /// Stops this home's service the way systemd does (SIGTERM), and waits
    /// for it to save its tasks and end.
    pub fn stop_service(&self) {
        let Some(pid) = self.service_pid() else {
            return;
        };
        // SAFETY: a signal to a process this check started.
        unsafe { libc::kill(pid, libc::SIGTERM) };
        let until = Instant::now() + Duration::from_secs(10);
        while Path::new(&format!("/proc/{pid}")).exists() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(20));
        }
        if Path::new(&format!("/proc/{pid}")).exists() {
            unsafe { libc::kill(pid, libc::SIGKILL) };
        }
    }

    /// Ends everything in the service's systemd scope at once, as systemd
    /// does when it stops or restarts the service (KillMode=control-group).
    pub fn kill_service_group(&self) {
        let Some(pid) = self.service_pid() else {
            return;
        };
        // The service runs on the host (a check in the build box starts it
        // there), so the host ends its group.
        let script = format!(
            "g=$(sed -n 's/^0:://p' /proc/{pid}/cgroup); g=${{g%/service}}; \
             echo 1 > /sys/fs/cgroup$g/cgroup.kill"
        );
        let in_box = Path::new("/run/.containerenv").exists();
        let mut cmd = if in_box {
            let mut c = std::process::Command::new("distrobox-host-exec");
            c.arg("sh");
            c
        } else {
            std::process::Command::new("sh")
        };
        let ok = cmd
            .args(["-c", &script])
            .status()
            .is_ok_and(|s| s.success());
        assert!(ok, "the service's group can't be ended");
        let until = Instant::now() + Duration::from_secs(10);
        while Path::new(&format!("/proc/{pid}")).exists() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(20));
        }
    }
}

impl TestHome {
    /// Ends every process still running for this home: the task hosts and
    /// the stand-in Claude Code keep going when the service stops (that is
    /// their job), so a check must end them itself. Each host's record in
    /// this home names its process and its child's (a host's own
    /// environment can't be read, so the home's files are the way to them).
    pub fn kill_leftovers(&self) {
        let dir = self.root.join(".local/state/mimir/hosts");
        let mine = |pid: i32| {
            // Only a process of the program under test, never a reused id.
            std::fs::read(format!("/proc/{pid}/cmdline")).is_ok_and(|c| {
                let c = String::from_utf8_lossy(&c);
                c.contains("/debug/mimir\0host\0") || c.contains("fake-claude")
            })
        };
        for _ in 0..4 {
            let mut any = false;
            for e in std::fs::read_dir(&dir).into_iter().flatten().flatten() {
                let path = e.path();
                if path.extension().is_none_or(|x| x != "json") {
                    continue;
                }
                let Some(rec) = std::fs::read_to_string(&path)
                    .ok()
                    .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
                else {
                    continue;
                };
                for field in ["child_pid", "host_pid"] {
                    let Some(pid) = rec[field].as_i64().map(|p| p as i32).filter(|p| *p > 1) else {
                        continue;
                    };
                    if mine(pid) {
                        // SAFETY: a signal to a process this check started.
                        unsafe { libc::kill(pid, libc::SIGKILL) };
                        any = true;
                    }
                }
            }
            if !any {
                break;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }
}

impl Drop for TestHome {
    /// The service stops. A passed check leaves nothing behind; a failed
    /// one keeps its home for a look.
    fn drop(&mut self) {
        self.stop_service();
        self.kill_leftovers();
        if self.kept {
            eprintln!("This named home is kept at {}", self.root.display());
        } else if std::thread::panicking() {
            eprintln!("The failed check's home is kept at {}", self.root.display());
        } else {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }
}

/// Copies a folder, replacing `@ROOT@` in file contents and names.
fn copy_replacing(src: &Path, dst: &Path, root: &str) {
    for e in std::fs::read_dir(src).unwrap().flatten() {
        let name = e
            .file_name()
            .to_string_lossy()
            .replace("@ROOT@", &folder_safe(root));
        let to = dst.join(name);
        if e.file_type().unwrap().is_dir() {
            std::fs::create_dir_all(&to).unwrap();
            copy_replacing(&e.path(), &to, root);
        } else {
            let text = std::fs::read_to_string(e.path()).unwrap();
            std::fs::write(&to, text.replace("@ROOT@", root)).unwrap();
        }
    }
}

/// `@ROOT@` in a file or folder name stands for the root as Claude Code
/// and the checkpoints name a folder after a path: "/" becomes "-".
pub fn folder_safe(root: &str) -> String {
    root.chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect()
}

/// Takes in the service's events until `done` holds. False after
/// `PATIENCE`.
pub fn service_until(
    st: &mut mimir_core::client::Station,
    mut done: impl FnMut(&mimir_core::client::Station) -> bool,
) -> bool {
    let until = Instant::now() + PATIENCE;
    loop {
        st.poll();
        if done(st) {
            return true;
        }
        if Instant::now() > until {
            return false;
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}

/// Sets a variable for this check (and the service it starts).
pub fn set_env(var: &str, value: impl AsRef<std::ffi::OsStr>) {
    set(var, value);
}

/// Runs frames until the window has nothing more to do right now.
pub fn settle(h: &mut Harness<'static, App>) {
    let _ = h.run_ok();
    h.step();
}

/// Runs frames until `done` holds, with real time passing (Claude runs in
/// its own process). False after `PATIENCE`.
pub fn wait_for(
    h: &mut Harness<'static, App>,
    mut done: impl FnMut(&Harness<'static, App>) -> bool,
) -> bool {
    let until = Instant::now() + PATIENCE;
    loop {
        h.step();
        if done(h) {
            return true;
        }
        if Instant::now() > until {
            return false;
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}

/// Whether some text on screen contains `text`.
pub fn shows(h: &Harness<'static, App>, text: &str) -> bool {
    h.query_all_by(|n| {
        n.label().is_some_and(|l| l.contains(text)) || n.value().is_some_and(|v| v.contains(text))
    })
    .next()
    .is_some()
}

/// Every piece of text on screen, for a failed check's message.
pub fn screen_text(h: &Harness<'static, App>) -> String {
    let mut out = vec![];
    for n in h.query_all_by(|n| n.label().is_some() || n.value().is_some()) {
        let a = n.accesskit_node();
        let t = a.label().or_else(|| a.value()).unwrap_or_default();
        if !t.trim().is_empty() {
            out.push(format!("{:?}: {}", a.role(), t.trim()));
        }
    }
    out.join("\n")
}

/// Closes the window the way the desktop does: Mímir stops its
/// work and frees the approvals socket.
pub fn close(mut h: Harness<'static, App>) {
    eframe::App::on_exit(h.state_mut());
    drop(h);
}

/// The first text box of this kind on screen.
fn text_box<'a>(h: &'a Harness<'static, App>) -> egui_kittest::Node<'a> {
    h.query_all_by_role(Role::MultilineTextInput)
        .next()
        .expect("no message box on screen")
}

/// Types into the page's message box (New task, or the task's).
pub fn type_message(h: &mut Harness<'static, App>, text: &str) {
    // The box slides into place when a page opens; click it where it ends up.
    let mut last = None;
    for _ in 0..120 {
        let now = text_box(h).accesskit_node().bounding_box();
        if now == last {
            break;
        }
        last = now;
        h.step();
    }
    text_box(h).click();
    h.step();
    text_box(h).type_text(text);
    h.step();
}

/// Clicks the button with exactly these words.
pub fn click(h: &mut Harness<'static, App>, words: &str) {
    // Rest the pointer on it first, as a hand does: a button with a
    // tooltip ignores a press in the very frame the pointer arrives.
    button(h, words).hover();
    for _ in 0..3 {
        h.step();
    }
    button(h, words).click();
    h.step();
    h.step();
}

fn button<'a>(h: &'a Harness<'static, App>, words: &'a str) -> egui_kittest::Node<'a> {
    h.query_all_by_role_and_label(Role::Button, words)
        .next()
        .unwrap_or_else(|| panic!("no button {words:?} on screen\n{}", screen_text(h)))
}

/// Whether a button with exactly these words is on screen.
pub fn has_button(h: &Harness<'static, App>, words: &str) -> bool {
    h.query_all_by_role_and_label(Role::Button, words)
        .next()
        .is_some()
}

/// New task: types the request and presses Start task.
pub fn start_task(h: &mut Harness<'static, App>, text: &str) {
    type_message(h, text);
    click(h, station_ui::labels::START_TASK);
}

/// What the approvals hook told Claude, in the window's words.
pub fn told(answer: Option<&String>) -> &'static str {
    match answer.map(String::as_str) {
        Some("allow") => "allow",
        Some("always") => "always allow",
        Some("deny") => "no",
        Some(_) => "something unknown",
        None => "nothing",
    }
}

/// Whether a widget with exactly this spoken name is on screen.
pub fn has_label(h: &Harness<'static, App>, name: &str) -> bool {
    h.query_all_by_label(name).next().is_some()
}

/// Starts `mimir approve` the way Claude Code's hook does, for a
/// Write in `project`.
pub fn approve_hook(project: &str, in_mimir: bool) -> std::process::Child {
    let mut cmd = std::process::Command::new(station_program());
    cmd.arg("approve")
        .env_remove(mimir_core::approve::MIMIR_TASK_ENV)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped());
    if in_mimir {
        cmd.env(mimir_core::approve::MIMIR_TASK_ENV, "1");
    }
    let mut c = cmd.spawn().unwrap();
    let input = serde_json::json!({
        "session_id": "5e55104-outside",
        "transcript_path": "",
        "cwd": project,
        "hook_event_name": "PermissionRequest",
        "tool_name": "Write",
        "tool_input": {"file_path": format!("{project}/hello.txt"), "content": "hi"},
        "permission_suggestions": [],
    });
    std::io::Write::write_all(&mut c.stdin.take().unwrap(), input.to_string().as_bytes()).unwrap();
    c
}

pub fn hook_printed(c: std::process::Child) -> String {
    let out = c.wait_with_output().unwrap();
    assert!(out.status.success(), "the hook must never fail");
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}

/// A time `ago` seconds back, as Claude Code writes it in a transcript.
pub fn iso(ago: i64) -> String {
    let t = mimir_core::needs::now() - ago;
    let (days, secs) = (t.div_euclid(86_400), t.rem_euclid(86_400));
    // The civil date from days since 1970 (Howard Hinnant's method).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.000Z",
        secs / 3600,
        secs / 60 % 60,
        secs % 60
    )
}

/// Scrolls the side list to its end, as a hand on the wheel does, so rows
/// near the bottom are in reach even in a small window. The pointer rests
/// on the list's left part.
pub fn scroll_side_list_down(h: &mut Harness<'static, App>) {
    let pos = eframe::egui::pos2(100.0, 130.0);
    h.hover_at(pos);
    for _ in 0..3 {
        h.step();
    }
    for _ in 0..8 {
        h.event(eframe::egui::Event::MouseWheel {
            unit: eframe::egui::MouseWheelUnit::Point,
            delta: eframe::egui::vec2(0.0, -300.0),
            phase: eframe::egui::TouchPhase::Move,
            source: eframe::egui::MouseWheelSource::Wheel,
            modifiers: eframe::egui::Modifiers::NONE,
        });
        h.step();
    }
    for _ in 0..6 {
        h.step();
    }
}

/// P17 (owner, 2026-11-25): a new task in a project is named by its start
/// time on this PC's clock ("Nov 25, 5:50 PM"). The names for this minute
/// and the one before, as a check may start a task just before it turns.
pub fn time_names() -> [String; 2] {
    use mimir_core::runner::{ClockTime, time_title};
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;
    [now, now - 60].map(|t| {
        let c = ClockTime::at(t);
        time_title(c, c.year)
    })
}

/// The time name of the task a check just started, as the window shows it.
pub fn shown_time_name(h: &Harness<'static, App>) -> Option<String> {
    time_names().into_iter().find(|n| shows(h, n))
}
