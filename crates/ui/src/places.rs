//! Where things live: Claude Code, project folders, the app's own files.

use std::path::{Path, PathBuf};

pub use mimir_core::notify::notify;
pub use mimir_core::paths::*;
pub use mimir_core::settings::{Defaults, Group, Prompt, Settings};

/// Asks the desktop for a folder (KDE's dialog on SteamOS, or zenity).
/// Runs in the background; the answer arrives on the returned channel.
pub fn pick_folder() -> std::sync::mpsc::Receiver<Option<PathBuf>> {
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let start = home().display().to_string();
        let tries: [(&str, Vec<&str>); 2] = [
            (
                "kdialog",
                vec![
                    "--getexistingdirectory",
                    &start,
                    "--title",
                    "Open a project folder",
                ],
            ),
            (
                "zenity",
                vec![
                    "--file-selection",
                    "--directory",
                    "--title=Open a project folder",
                ],
            ),
        ];
        for (prog, args) in tries {
            match mimir_core::flatpak::host_cmd(prog).args(&args).output() {
                Ok(out) => {
                    let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
                    let _ = tx.send(if out.status.success() && !s.is_empty() {
                        Some(PathBuf::from(s))
                    } else {
                        None
                    });
                    return;
                }
                Err(_) => continue,
            }
        }
        // No dialog program: the window falls back to typing a path.
        let _ = tx.send(Some(PathBuf::new()));
    });
    rx
}

pub fn pick_files(title: &'static str) -> std::sync::mpsc::Receiver<Vec<PathBuf>> {
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let start = home().display().to_string();
        let out = mimir_core::flatpak::host_cmd("kdialog")
            .args([
                "--getopenfilename",
                &start,
                "--multiple",
                "--separate-output",
                "--title",
                title,
            ])
            .output()
            .or_else(|_| {
                mimir_core::flatpak::host_cmd("zenity")
                    .args([
                        "--file-selection",
                        "--multiple",
                        "--separator=\n",
                        &format!("--title={title}"),
                    ])
                    .output()
            });
        let files = out
            .ok()
            .filter(|o| o.status.success())
            .map(|o| {
                String::from_utf8_lossy(&o.stdout)
                    .lines()
                    .filter(|l| !l.trim().is_empty())
                    .map(PathBuf::from)
                    .collect()
            })
            .unwrap_or_default();
        let _ = tx.send(files);
    });
    rx
}

/// Opens a file or folder with the desktop's default program.
pub fn open_path(p: &Path) {
    // A debug build's checks only note what would open.
    if cfg!(debug_assertions)
        && let Some(log) = std::env::var_os("MIMIR_TEST_OPEN")
    {
        use std::io::Write;
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log)
        {
            let _ = writeln!(f, "{}", p.display());
        }
        return;
    }
    let _ = std::process::Command::new("xdg-open")
        .arg(p)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn();
}

/// Opens a terminal in a folder.
/// Opens a terminal in a folder. Returns false when none could start.
pub fn open_terminal(dir: &Path) -> bool {
    mimir_core::flatpak::host_cmd("konsole")
        .arg("--workdir")
        .arg(dir)
        .spawn()
        .is_ok()
        || mimir_core::flatpak::host_cmd("xterm")
            .arg("-e")
            .arg("sh")
            .arg("-c")
            .arg(format!(
                "cd {} && exec \"$SHELL\"",
                mimir_core::setup::shell_quote(&dir.display().to_string())
            ))
            .spawn()
            .is_ok()
}

/// A pasted picture, saved as a PNG in Mímir's data folder so it
/// can go with a message.
pub fn save_pasted(img: &eframe::egui::ColorImage) -> Option<PathBuf> {
    let dir = data_dir().join("pastes");
    std::fs::create_dir_all(&dir).ok()?;
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let path = dir.join(format!("pasted-{stamp}.png"));
    let bytes: Vec<u8> = img
        .pixels
        .iter()
        .flat_map(|c| c.to_srgba_unmultiplied())
        .collect();
    image::save_buffer(
        &path,
        &bytes,
        img.size[0] as u32,
        img.size[1] as u32,
        image::ExtendedColorType::Rgba8,
    )
    .ok()?;
    Some(path)
}

/// How Read aloud speaks: chosen in Settings, read by `speak`.
#[derive(Clone, Copy, PartialEq)]
pub struct VoicePrefs {
    /// The natural voice (Alba); false: the desktop's own voice.
    pub natural: bool,
    /// 0.5 to 1.5 (1.0 the usual pace; default 1.1 = 110%).
    pub speed: f32,
    /// The pauses between phrases: 0.5 to 2.0.
    pub cadence: f32,
}

// PCE-12, PCK-02: default voice is Ingrid at 110%.
static PREFS: std::sync::Mutex<VoicePrefs> = std::sync::Mutex::new(VoicePrefs {
    natural: true,
    speed: 1.1,
    cadence: 1.0,
});

pub fn set_voice_prefs(p: VoicePrefs) {
    if let Ok(mut g) = PREFS.lock() {
        *g = p;
    }
}

fn voice_root() -> PathBuf {
    mimir_core::voice::root(&data_dir())
}

/// The natural voice's download: what it is doing ("" when idle).
static DOWNLOAD: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

/// Why the natural voice could not read ("" when it could). Kept apart
/// from the download's own status, so one failed reading never hides
/// "Ready" or the Delete button.
static VOICE_ERROR: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

/// A reading failed: its reason goes to its own slot, never into the
/// download's status.
fn note_voice_error(_why: &str) {
    if let Ok(mut d) = VOICE_ERROR.lock() {
        *d = "The natural voice could not read this aloud. Try again, or switch to the desktop voice."
            .to_string();
    }
}

/// The last reading's problem, in plain words ("" when none).
pub fn voice_error() -> String {
    VOICE_ERROR.lock().map(|e| e.clone()).unwrap_or_default()
}

/// Clears the last reading's problem (the owner has seen it).
pub fn clear_voice_error() {
    if let Ok(mut e) = VOICE_ERROR.lock() {
        e.clear();
    }
}

/// Downloading, ready, or not yet: one line for Settings.
pub fn voice_status() -> String {
    let d = DOWNLOAD.lock().map(|d| d.clone()).unwrap_or_default();
    if !d.is_empty() {
        d
    } else if mimir_core::voice::ready(&voice_root()) {
        "Ready".into()
    } else {
        "Not in this copy of Mímir".into()
    }
}

/// Starts the natural voice's download in the background (once).
pub fn download_voice() {
    let root = voice_root();
    if mimir_core::voice::ready(&root) {
        return;
    }
    {
        let Ok(mut d) = DOWNLOAD.lock() else { return };
        if !d.is_empty() && !d.starts_with("Download failed") {
            return;
        }
        *d = "Getting the voice ready…".into();
    }
    std::thread::spawn(move || {
        let step = |s: &str| {
            if let Ok(mut d) = DOWNLOAD.lock() {
                *d = s.to_string();
            }
        };
        let r = mimir_core::voice::download(&root, step);
        if let Ok(mut d) = DOWNLOAD.lock() {
            *d = match r {
                Ok(()) => String::new(),
                Err(_) => "This copy of Mímir doesn't include the voice.".to_string(),
            };
        }
    });
}

pub fn delete_voice() {
    stop_speaking();
    mimir_core::voice::delete(&voice_root());
}

/// Talk instead of type: what the microphone button is doing.
enum Listen {
    Idle,
    /// The speech model is on its way: what the download is doing.
    Downloading(String),
    Recording(mimir_core::listen::Recording),
    /// The words are being written down.
    Writing,
    /// The words, waiting to go into the message box.
    Heard(String),
    Failed(String),
}

/// Where the microphone's recording is kept while it is made.
fn recording_file() -> PathBuf {
    voice_root().join("listening").join("listening.wav")
}

/// The window is closing: a recording still on is stopped, and no
/// recording is left on the disk (nothing of what was said is kept).
pub fn listen_cleanup() {
    listen_cancel();
    let _ = std::fs::remove_file(recording_file());
}

static LISTEN: std::sync::Mutex<Listen> = std::sync::Mutex::new(Listen::Idle);

/// What the microphone button shows.
#[derive(Clone, PartialEq)]
pub enum ListenStatus {
    Idle,
    Downloading(String),
    Listening,
    Writing,
    Failed(String),
}

pub fn listen_ready() -> bool {
    mimir_core::listen::ready(&voice_root())
}

pub fn listen_status() -> ListenStatus {
    let Ok(mut l) = LISTEN.lock() else {
        return ListenStatus::Idle;
    };
    // A recorder that ended by itself: no microphone, most likely.
    if let Listen::Recording(r) = &mut *l {
        let why = if r.failed() {
            Some("Nothing was recorded. Check that a microphone is plugged in, then try again.")
        } else if r.seconds() > 600 {
            // Left on by mistake: nothing is kept.
            Some("Stopped listening after 10 minutes.")
        } else {
            None
        };
        if let Some(why) = why {
            // The recorder ends with the old state, and so does its file.
            drop(std::mem::replace(&mut *l, Listen::Failed(why.into())));
            let _ = std::fs::remove_file(recording_file());
        }
    }
    match &*l {
        Listen::Idle | Listen::Heard(_) => ListenStatus::Idle,
        Listen::Downloading(s) => ListenStatus::Downloading(s.clone()),
        Listen::Recording(_) => ListenStatus::Listening,
        Listen::Writing => ListenStatus::Writing,
        Listen::Failed(e) => ListenStatus::Failed(e.clone()),
    }
}

/// The words that were heard, once, for the message box on screen.
pub fn take_heard() -> Option<String> {
    let mut l = LISTEN.lock().ok()?;
    if let Listen::Heard(_) = &*l
        && let Listen::Heard(text) = std::mem::replace(&mut *l, Listen::Idle)
    {
        return Some(text);
    }
    None
}

/// Starts the speech model's download in the background.
pub fn download_listen(ctx: &eframe::egui::Context) {
    let root = voice_root();
    {
        let Ok(mut l) = LISTEN.lock() else { return };
        if !matches!(*l, Listen::Idle | Listen::Failed(_)) {
            return;
        }
        *l = Listen::Downloading("Starting the download…".into());
    }
    let ctx = ctx.clone();
    std::thread::spawn(move || {
        let step = |s: &str| {
            if let Ok(mut l) = LISTEN.lock() {
                *l = Listen::Downloading(s.to_string());
            }
        };
        let r = mimir_core::listen::download(&root, step);
        if let Ok(mut l) = LISTEN.lock() {
            *l = match r {
                Ok(()) => Listen::Idle,
                Err(_) => Listen::Failed(
                    "Download failed. Check your internet, then try again.".to_string(),
                ),
            };
        }
        ctx.request_repaint();
    });
}

pub fn delete_listen() {
    listen_cancel();
    mimir_core::listen::delete(&voice_root());
}

/// The microphone button: starts listening, or stops and writes it down.
pub fn listen_toggle(ctx: &eframe::egui::Context) {
    let root = voice_root();
    let Ok(mut l) = LISTEN.lock() else { return };
    match std::mem::replace(&mut *l, Listen::Idle) {
        Listen::Recording(r) => {
            *l = Listen::Writing;
            let ctx = ctx.clone();
            std::thread::spawn(move || {
                let file = r.stop();
                let heard = mimir_core::listen::transcribe(&root, &file);
                let _ = std::fs::remove_file(&file);
                if let Ok(mut l) = LISTEN.lock() {
                    *l = match heard {
                        Ok(text) => Listen::Heard(text),
                        Err(e) => Listen::Failed(e),
                    };
                }
                ctx.request_repaint();
            });
        }
        Listen::Idle | Listen::Failed(_) => {
            // What is said is read aloud by nobody meanwhile.
            stop_speaking();
            *l = match mimir_core::listen::record(&root.join("listening")) {
                Ok(r) => Listen::Recording(r),
                Err(e) => Listen::Failed(e),
            };
        }
        other => *l = other,
    }
}

/// Stops listening and throws away what was said. True if it was listening.
pub fn listen_cancel() -> bool {
    let Ok(mut l) = LISTEN.lock() else {
        return false;
    };
    match std::mem::replace(&mut *l, Listen::Idle) {
        Listen::Recording(r) => {
            let file = r.stop();
            let _ = std::fs::remove_file(file);
            true
        }
        Listen::Failed(_) => false,
        other => {
            *l = other;
            false
        }
    }
}

enum Speaker {
    Program(std::process::Child),
    Natural(mimir_core::voice::Reading),
}

/// The reply being read aloud (its id) and what is reading it.
static SPEAKING: std::sync::Mutex<Option<(eframe::egui::Id, Speaker)>> =
    std::sync::Mutex::new(None);

/// Reads Claude's reply (Markdown) aloud: with the natural voice when it is
/// chosen and downloaded (Photo's rules: phrase by phrase, with pauses),
/// otherwise with the desktop's voice while the natural one downloads.
pub fn speak(id: eframe::egui::Id, markdown: &str) {
    stop_speaking();
    clear_voice_error();
    let prefs = PREFS.lock().map(|p| *p).unwrap_or(VoicePrefs {
        natural: true,
        speed: 1.0,
        cadence: 1.0,
    });
    let root = voice_root();
    if prefs.natural && mimir_core::voice::ready(&root) {
        let reading = mimir_core::voice::read(
            &root,
            mimir_core::speech_text::phrases(markdown),
            prefs.speed,
            prefs.cadence,
            |e| note_voice_error(&e.to_string()),
        );
        if let Ok(mut g) = SPEAKING.lock() {
            *g = Some((id, Speaker::Natural(reading)));
        }
        return;
    }
    if prefs.natural {
        download_voice();
    }
    let text = mimir_core::speech_text::plain(markdown);
    // spd-say's rate: -100 to 100; 0 is its usual pace.  With 50-150% → -83 to 83.
    let rate = (((prefs.speed - 1.0) / 0.5) * 83.0).round() as i32;
    let child = if have("spd-say") {
        std::process::Command::new("spd-say")
            .args(["-w", "-r", &rate.to_string(), "--"])
            .arg(&text)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
    } else {
        std::process::Command::new("espeak-ng")
            .arg("--")
            .arg(&text)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
    };
    if let (Ok(c), Ok(mut g)) = (child, SPEAKING.lock()) {
        *g = Some((id, Speaker::Program(c)));
    }
}

/// The reply being read aloud now, if any.
pub fn speaking() -> Option<eframe::egui::Id> {
    let mut g = SPEAKING.lock().ok()?;
    let done = match g.as_mut() {
        Some((_, Speaker::Program(c))) => c.try_wait().ok().flatten().is_some(),
        Some((_, Speaker::Natural(r))) => r.done(),
        None => return None,
    };
    if done {
        *g = None;
        return None;
    }
    g.as_ref().map(|(id, _)| *id)
}

pub fn stop_speaking() {
    if let Ok(mut g) = SPEAKING.lock()
        && let Some((_, sp)) = g.take()
    {
        match sp {
            Speaker::Natural(r) => r.stop(),
            Speaker::Program(mut c) => {
                let _ = c.kill();
                let _ = c.wait();
                // spd-say hands the text to a service: tell it to stop too.
                if have("spd-say") {
                    let _ = std::process::Command::new("spd-say")
                        .arg("-C")
                        .stdout(std::process::Stdio::null())
                        .stderr(std::process::Stdio::null())
                        .status();
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_get_one_spelling() {
        let dir = std::env::temp_dir().join(format!("cs-clean-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("a")).unwrap();
        let real = std::fs::canonicalize(dir.join("a"))
            .unwrap()
            .display()
            .to_string();
        let d = dir.display().to_string();
        assert_eq!(clean_path(&format!("{d}/a/")), real);
        assert_eq!(clean_path(&format!("{d}/a/../a")), real);
        assert_eq!(clean_path(&real), real);
        // A missing folder only loses its trailing slash.
        assert_eq!(clean_path("/no/such/place//"), "/no/such/place");
        assert_eq!(clean_path("/"), "/");
        let _ = std::fs::remove_dir_all(dir);
    }

    /// Runs `tripwire_child` in its own process with every folder variable
    /// pointed into a test home (changing this process's environment would
    /// race the other tests).
    #[test]
    fn tripwire_lists_every_place() {
        let root = std::env::temp_dir().join(format!("cs-trip-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let root = std::fs::canonicalize(&root).unwrap();
        let mut cmd = std::process::Command::new(std::env::current_exe().unwrap());
        cmd.args([
            "--exact",
            "places::tests::tripwire_child",
            "--nocapture",
            "--test-threads=1",
        ])
        .env("CS_TRIP_ROOT", &root)
        .env("MIMIR_CLAUDE", root.join("fake-claude"));
        for (var, sub) in [
            ("HOME", "home"),
            ("XDG_CONFIG_HOME", "home/.config"),
            ("XDG_DATA_HOME", "home/.local/share"),
            ("XDG_STATE_HOME", "home/.local/state"),
            ("XDG_CACHE_HOME", "home/.cache"),
            ("XDG_RUNTIME_DIR", "run"),
            ("CLAUDE_CONFIG_DIR", "claude"),
        ] {
            cmd.env(var, root.join(sub));
        }
        let out = cmd.output().unwrap();
        let text = String::from_utf8_lossy(&out.stdout);
        let _ = std::fs::remove_dir_all(&root);
        assert!(
            out.status.success() && text.contains("1 passed"),
            "{text}{}",
            String::from_utf8_lossy(&out.stderr)
        );
    }

    #[test]
    fn tripwire_child() {
        use mimir_core::store::Store;
        let Some(root) = std::env::var_os("CS_TRIP_ROOT").map(PathBuf::from) else {
            return;
        };
        // Reading changes nothing: no task folder appears.
        let before = paths_in_use();
        assert!(!Store::default_dir().exists());
        assert!(!settings_path().exists());
        assert!(before.iter().all(|(n, _)| n != "project folder"));

        let inside = root.join("home/proj");
        std::fs::create_dir_all(&inside).unwrap();
        let s = Settings {
            projects: vec![inside.display().to_string()],
            remote_projects: vec![root.join("home/remote").display().to_string()],
            ..Default::default()
        };
        s.save();
        // A task working outside the test home, which a check must see.
        let store = Store::new(Store::default_dir()).unwrap();
        let t = mimir_core::task::Task::new("t1".into(), "x".into(), "/elsewhere/work".into(), 1);
        store.save(&t).unwrap();
        let saved = std::fs::read(settings_path()).unwrap();

        let paths = paths_in_use();
        for name in [
            "task store",
            "settings",
            "approvals socket",
            "Session folder",
            "older self-installed program",
            "data folder",
            "chats",
            "pastes",
            "voice",
            "checkpoints",
            "old web window",
            "saved conversations",
        ] {
            assert!(paths.iter().any(|(n, _)| n == name), "{name} not listed");
        }
        let elsewhere = Path::new("/elsewhere/work");
        for (n, p) in &paths {
            if p != elsewhere {
                assert!(p.starts_with(&root), "{n}: {} is outside", p.display());
            }
        }
        let projects: Vec<&PathBuf> = paths
            .iter()
            .filter(|(n, _)| n == "project folder")
            .map(|(_, p)| p)
            .collect();
        assert!(projects.contains(&&inside));
        assert!(projects.contains(&&root.join("home/remote")));
        assert!(projects.contains(&&elsewhere.to_path_buf()));
        assert_eq!(
            paths.last(),
            Some(&("Project work program".to_string(), root.join("fake-claude")))
        );
        assert_eq!(std::fs::read(settings_path()).unwrap(), saved);
    }

    #[test]
    fn tidy_forgets_what_is_gone() {
        let mut s = Settings {
            pinned_tasks: vec!["t1".into(), "gone".into()],
            collapsed_projects: vec!["/p".into(), "/old".into()],
            ..Default::default()
        };
        s.task_order
            .insert("/p".into(), vec!["gone".into(), "t1".into(), "t2".into()]);
        s.task_order.insert("/old".into(), vec!["t3".into()]);
        s.project_names.insert("/p".into(), "Mine".into());
        s.project_names.insert("/old".into(), "Old".into());
        let tasks = ["t1".to_string(), "t2".to_string(), "t3".to_string()];
        assert!(s.tidy(&tasks, &["/p".to_string()]));
        assert_eq!(s.pinned_tasks, vec!["t1".to_string()]);
        assert_eq!(s.task_order.len(), 1);
        assert_eq!(s.task_order["/p"], vec!["t1".to_string(), "t2".to_string()]);
        assert_eq!(s.project_names.len(), 1);
        assert_eq!(s.collapsed_projects, vec!["/p".to_string()]);
        assert!(!s.tidy(&tasks, &["/p".to_string()]));
        assert_eq!(display_name(&s, "/p"), "Mine");
        assert_eq!(display_name(&s, "/q/x"), "x");
    }
}

#[cfg(test)]
mod voice_tests {
    use super::*;

    /// PCW-21: one failed reading says so on a line of its own, and never
    /// hides the download's own status (Ready, Delete the voice).
    #[test]
    fn a_failed_reading_never_hides_the_voice_status() {
        let before = voice_status();
        note_voice_error("no sound card");
        assert_eq!(voice_status(), before, "the error took the status's place");
        assert!(voice_error().contains("could not read this aloud"));
        // A new reading starts clean.
        clear_voice_error();
        assert_eq!(voice_error(), "");
        // And a failed download can still be tried again.
        assert!(!voice_status().contains("could not read"));
    }
}
