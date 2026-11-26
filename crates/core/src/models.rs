//! Free models: the Ollama models on this PC, offered beside Claude in the
//! window's and the phone's pickers. A task on a free model runs Claude Code
//! against Ollama's Anthropic-compatible endpoint, so nothing about the task
//! changes but where the model runs.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// A task's `model` for a free model: `ollama:<name>`.
pub const FREE: &str = "ollama:";

/// The one line abliterated (refusal-removed) models carry; it blocks nothing.
pub const REFUSALS_REMOVED: &str =
    "Uncensored: it does what you ask, including things other models won't.";

/// The context Ollama gives each request (the proven 2026-11-18 setting).
pub const CONTEXT: u64 = 32_768;

/// Free models fit small, clear jobs: Claude Code's own instructions take
/// about 20,000 of the 32,768 tokens.
pub const FIT_NOTE: &str = "Models on your PC fit small, clear jobs; a long task won't fit.";

/// What a task says when a free model ran out of room.
pub const OUT_OF_ROOM: &str = "This model ran out of room.";

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct FreeModel {
    /// The task's model value (`ollama:<name>`).
    pub id: String,
    /// The Ollama name, e.g. "qwen3.6:35b-a3b-coding".
    pub name: String,
    /// From the benchmark runs on this PC, e.g. "Fixed both test bugs in 30 s".
    pub note: String,
    pub refusals_removed: bool,
}

/// Is this task model a free one?
pub fn is_free(model: Option<&str>) -> bool {
    model.is_some_and(|m| m.starts_with(FREE))
}

/// The Ollama name in a free model's id.
pub fn ollama_name(model: &str) -> Option<&str> {
    model.strip_prefix(FREE)
}

/// Where Ollama answers (a check may point elsewhere).
pub fn ollama_url() -> String {
    std::env::var("MIMIR_OLLAMA").unwrap_or_else(|_| "http://127.0.0.1:11434".into())
}

/// How Claude Code is started for a free model: its environment.
pub fn launch_env() -> Vec<(String, String)> {
    vec![
        ("ANTHROPIC_BASE_URL".into(), ollama_url()),
        ("ANTHROPIC_AUTH_TOKEN".into(), "ollama".into()),
        ("ANTHROPIC_API_KEY".into(), String::new()),
        // Claude Code 2.1.327 doesn't know a custom model's window; this is
        // the window it compacts against (looked up in the installed Claude
        // Code: CLAUDE_CODE_MAX_CONTEXT_TOKENS only counts with compacting off).
        (
            "CLAUDE_CODE_AUTO_COMPACT_WINDOW".into(),
            CONTEXT.to_string(),
        ),
    ]
}

/// Notes from the benchmark runs on this PC (2026-11-18), kept in
/// `local-models.json` (written the first time; later runs may change it).
fn notes_file() -> PathBuf {
    crate::paths::data_dir().join("local-models.json")
}

fn first_notes() -> HashMap<String, String> {
    [
        (
            "qwen3.6:35b-a3b-coding",
            "Fixed both test bugs in 30 s · fastest",
        ),
        ("gpt-oss:20b", "Fixed both test bugs in 32 s"),
        ("gemma4:12b", "Fixed both test bugs in 37 s"),
        (
            "huihui_ai/Qwen3.6-abliterated:35b-a3b",
            "Fixed both test bugs in 90 s",
        ),
        (
            "huihui_ai/gpt-oss-abliterated:20b",
            "Fixed both test bugs in 3 min",
        ),
        ("qwen3-coder:30b", "Didn't fix the test bugs"),
    ]
    .into_iter()
    .map(|(a, b)| (a.to_string(), b.to_string()))
    .collect()
}

pub fn notes() -> HashMap<String, String> {
    let f = notes_file();
    if let Some(m) = std::fs::read_to_string(&f)
        .ok()
        .and_then(|t| serde_json::from_str::<HashMap<String, String>>(&t).ok())
    {
        return m;
    }
    let m = first_notes();
    if let Some(d) = f.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    let _ = std::fs::write(&f, serde_json::to_vec_pretty(&m).unwrap_or_default());
    m
}

pub fn refusals_removed(name: &str) -> bool {
    let n = name.to_lowercase();
    ["abliterated", "heretic", "uncensored"]
        .iter()
        .any(|w| n.contains(w))
}

/// A short plain name for an Ollama model id: family, version and one
/// word on what it is for. "qwen3.6:35b-a3b-coding" reads "Qwen 3.6
/// coding"; a name it can't make sense of stays as it is.
pub fn plain_name(raw: &str) -> String {
    let lower = raw.to_lowercase();
    let base = lower.rsplit('/').next().unwrap_or(&lower);
    let family: String = base
        .chars()
        .take_while(|c| c.is_ascii_alphabetic())
        .collect();
    let rest = &base[family.len()..];
    let version: String = rest
        .chars()
        .take_while(|c| c.is_ascii_digit() || *c == '.')
        .collect();
    if family.is_empty() || version.is_empty() {
        return raw.to_string();
    }
    let word = if refusals_removed(raw) || lower.contains("huihui") {
        Some("uncensored")
    } else if lower.contains("coder") || lower.contains("coding") {
        Some("coding")
    } else if lower.contains("instruct") {
        Some("instruct")
    } else {
        None
    };
    let mut name: Vec<char> = family.chars().collect();
    name[0] = name[0].to_ascii_uppercase();
    let mut out = format!("{} {}", name.into_iter().collect::<String>(), version);
    if let Some(w) = word {
        out.push(' ');
        out.push_str(w);
    }
    out
}

/// A stored note in today's plain words. Notes saved by older versions name
/// the model by its raw id and point at a settings page that is gone; they
/// are shown like new ones. The stored text is not changed.
pub fn plain_note(note: &str) -> std::borrow::Cow<'_, str> {
    const OLD: &str = ", a free model on this PC (Settings \u{203a} Models";
    if let Some(rest) = note.strip_prefix("On ")
        && let Some((id, _)) = rest.split_once(OLD)
        && !id.is_empty()
        && !id.contains(' ')
    {
        return format!(
            "Answered by {}, a model on your PC. Your work stays here.",
            plain_name(id)
        )
        .into();
    }
    if let Some(rest) = note.strip_prefix("On ")
        && let Some((id, tail)) = rest
            .split_once(" because the online limit ran out")
            .or_else(|| rest.split_once(" because Claude's limit ran out"))
        && !id.is_empty()
        && !id.contains(' ')
    {
        return format!(
            "On {} because the online limit ran out{tail}",
            plain_name(id)
        )
        .into();
    }
    // Notes saved by earlier versions said "a free model on your PC" and
    // "Your chats stay on this PC" (a task is not a chat).
    if note.starts_with("Answered by ")
        && (note.contains(", a free model on your PC.")
            || note.contains("Your chats stay on this PC."))
    {
        return note
            .replacen(", a free model on your PC.", ", a model on your PC.", 1)
            .replacen("Your chats stay on this PC.", "Your work stays here.", 1)
            .into();
    }
    note.into()
}

/// The models Ollama has (its `/api/tags`), or why not.
pub fn installed() -> Result<Vec<String>, String> {
    let out = std::process::Command::new("curl")
        .args(["-s", "-m", "3", &format!("{}/api/tags", ollama_url())])
        .output()
        .map_err(|_| "curl is missing".to_string())?;
    let v: serde_json::Value =
        serde_json::from_slice(&out.stdout).map_err(|_| "Ollama isn't running".to_string())?;
    Ok(v["models"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|m| m["name"].as_str().map(str::to_string))
                .collect()
        })
        .unwrap_or_default())
}

/// What the PC has, given what [installed] just found: the model program's
/// own list, or, while it doesn't answer, the models whose files are on
/// disk (so a sleeping program never reads as "nothing installed"). Only
/// where Mímir has a program it can start.
pub fn installed_or_on_disk(found: &Result<Vec<String>, String>) -> Vec<String> {
    match found {
        Ok(n) => n.clone(),
        Err(_) if unit_path().is_file() => on_disk(),
        Err(_) => vec![],
    }
}

/// The downloaded models by their files (`manifests/<from>/<who>/<model>/<tag>`,
/// there once a download is complete), newest first as the program lists them.
fn on_disk() -> Vec<String> {
    fn inside(dir: &std::path::Path) -> Vec<(String, std::fs::DirEntry)> {
        std::fs::read_dir(dir)
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|e| Some((e.file_name().into_string().ok()?, e)))
            .filter(|(n, _)| !n.starts_with('.'))
            .collect()
    }
    let mut found = vec![];
    for (from, f) in inside(&crate::engine::models_dir().join("manifests")) {
        for (who, w) in inside(&f.path()) {
            for (model, m) in inside(&w.path()) {
                for (tag, t) in inside(&m.path()) {
                    let name = match (from.as_str(), who.as_str()) {
                        ("registry.ollama.ai", "library") => format!("{model}:{tag}"),
                        ("registry.ollama.ai", _) => format!("{who}/{model}:{tag}"),
                        _ => format!("{from}/{who}/{model}:{tag}"),
                    };
                    let at = t.metadata().and_then(|d| d.modified()).ok();
                    found.push((at, name));
                }
            }
        }
    }
    found.sort_by(|a, b| b.cmp(a));
    found.into_iter().map(|(_, name)| name).collect()
}

/// A model in the Model Store's first page: the free models tested on this
/// PC (2026-11-18 benchmark: a coding job with two bugs to fix).
#[derive(Debug, Clone, PartialEq)]
pub struct StorePick {
    /// Ollama's name, e.g. "gpt-oss:20b".
    pub name: &'static str,
    /// A plain name.
    pub title: &'static str,
    /// What it's good at, in tags ("Uncensored" for refusal-removed).
    pub tags: &'static [&'static str],
    /// Download size in bytes (as Ollama lists it).
    pub bytes: u64,
    pub licence: Option<&'static str>,
    /// Our own test on this PC.
    pub tested: &'static str,
    /// A few lines for its details.
    pub about: &'static str,
    /// What the PC's engine may pick it for.
    pub role: Role,
    /// No safety filter (only these are picked for chats).
    pub uncensored: bool,
    /// For a mixture-of-experts model, the billions of weights that work
    /// on each word (it can run partly on the processor and stay usable).
    pub moe_active_b: Option<f32>,
    /// Our own order (1 best) from tests on this PC and the
    /// uncensored-models leaderboard the weekly check reads.
    pub rank: u32,
    /// Hidden from the store's page (the engine's small fallback).
    pub engine_only: bool,
}

/// What a model is picked for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    Chat,
    Code,
}

/// The store's tested picks, best for coding first.
pub const STORE: &[StorePick] = &[
    StorePick {
        name: "qwen3.6:35b-a3b-coding",
        title: "Qwen 3.6 35B coding",
        tags: &["Best for coding", "Fastest"],
        bytes: 22_621_315_909,
        licence: Some("Apache 2.0"),
        tested: "Fixed both test bugs in 30 s",
        about: "Alibaba's Qwen 3.6, tuned for code. It only works on a small part of itself \
                for each word, so it is quick even though it is big. The pick used when \
                the online limit runs out.",
        role: Role::Code,
        uncensored: false,
        moe_active_b: Some(3.0),
        rank: 1,
        engine_only: false,
    },
    StorePick {
        name: "gpt-oss:20b",
        title: "gpt-oss 20B",
        tags: &["Smart all-rounder", "Coding"],
        bytes: 13_793_442_617,
        licence: Some("Apache 2.0"),
        tested: "Fixed both test bugs in 32 s",
        about: "OpenAI's open model. Careful reasoning and good writing; fits in this PC's \
                graphics memory.",
        role: Role::Code,
        uncensored: false,
        moe_active_b: Some(3.6),
        rank: 2,
        engine_only: false,
    },
    StorePick {
        name: "gemma4:12b",
        title: "Gemma 4 12B",
        tags: &["Small", "Chat"],
        bytes: 8_021_620_710,
        licence: None,
        tested: "Fixed both test bugs in 37 s",
        about: "Google's Gemma 4. The smallest download here and quick to load; good for \
                everyday questions and small jobs.",
        role: Role::Code,
        uncensored: false,
        moe_active_b: None,
        rank: 3,
        engine_only: false,
    },
    StorePick {
        name: "huihui_ai/Qwen3.6-abliterated:35b-a3b",
        title: "Qwen 3.6 35B, uncensored",
        tags: &["Uncensored", "Coding"],
        bytes: 23_938_334_500,
        licence: Some("Apache 2.0"),
        tested: "Fixed both test bugs in 90 s",
        about: "Qwen 3.6 with its refusals removed: it does what you ask, including things \
                other models won't. Slower than the coding version.",
        role: Role::Chat,
        uncensored: true,
        moe_active_b: Some(3.0),
        rank: 1,
        engine_only: false,
    },
    StorePick {
        name: "huihui_ai/gpt-oss-abliterated:20b",
        title: "gpt-oss 20B, uncensored",
        tags: &["Uncensored"],
        bytes: 13_792_657_605,
        licence: Some("Apache 2.0"),
        tested: "Fixed both test bugs in 3 min",
        about: "gpt-oss with its refusals removed. Fits in graphics memory, but slow on \
                coding jobs.",
        role: Role::Chat,
        uncensored: true,
        moe_active_b: Some(3.6),
        rank: 2,
        engine_only: false,
    },
    // The phone's own uncensored model (`keep_current::STANDALONE`'s
    // first), for a PC with no usable graphics card and little memory.
    StorePick {
        name: SMALL_CHAT,
        title: "Qwen 3.5 4B, uncensored",
        tags: &["Uncensored", "Small"],
        bytes: 2_707_515_040,
        licence: Some("Apache 2.0"),
        tested: "The phone's own model",
        about: "The small uncensored model the phone uses, for a PC without a graphics card.",
        role: Role::Chat,
        uncensored: true,
        moe_active_b: None,
        rank: 9,
        engine_only: true,
    },
];

/// The phone-size uncensored model, fetched by Ollama from its Hugging Face
/// address (the same file as `keep_current::STANDALONE`'s first).
pub const SMALL_CHAT: &str = "hf.co/mradermacher/Qwen3.5-4B-SOMPOA-heresy-v2-i1-GGUF:Q4_K_M";

/// The SHA-256 the phone's list gives for that file: Ollama's copy must
/// match it before it is used.
pub const SMALL_CHAT_SHA256: &str =
    "dc781ccfea6f4e4e5f9bba656676798b70af8a69f2a601e9b46ff46094034115";

/// This PC's graphics memory in bytes (the biggest card's), if the driver
/// says.
pub fn graphics_memory() -> Option<u64> {
    let mut best = None;
    for e in std::fs::read_dir("/sys/class/drm")
        .into_iter()
        .flatten()
        .flatten()
    {
        let f = e.path().join("device/mem_info_vram_total");
        if let Some(n) = std::fs::read_to_string(f)
            .ok()
            .and_then(|t| t.trim().parse::<u64>().ok())
        {
            best = best.max(Some(n));
        }
    }
    best
}

/// Whether a model of this size fits in the graphics memory, in words.
pub fn fit_words(bytes: u64, vram: Option<u64>) -> (bool, String) {
    match vram {
        Some(v) if bytes + v / 10 <= v => (
            true,
            format!("Fits this PC's graphics memory ({:.0} GB)", v as f64 / 1e9),
        ),
        Some(v) => (
            false,
            format!(
                "Bigger than this PC's graphics memory ({:.0} GB): part of it runs on the \
                 processor, so it starts slower",
                v as f64 / 1e9
            ),
        ),
        None => (true, "This PC's graphics memory is unknown".into()),
    }
}

/// Downloads a free model into Ollama, telling `progress` how far along it
/// is (0 to 1) as it goes.
pub fn add_with_progress(name: &str, mut progress: impl FnMut(f32)) -> Result<(), String> {
    use std::io::BufRead;
    let name = name.trim();
    if name.is_empty()
        || !name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "-_.:/".contains(c))
    {
        return Err("That model name was not found. Check the spelling and try again. A name looks like gemma4:12b.".into());
    }
    if !ensure_running(Duration::from_secs(20)) {
        return Err(
            "The models on this PC could not be started on this PC. Try again in a minute.".into(),
        );
    }
    let mut child = std::process::Command::new("curl")
        .args([
            "-sN",
            "-m",
            "7200",
            "-X",
            "POST",
            &format!("{}/api/pull", ollama_url()),
            "-d",
            &serde_json::json!({ "model": name, "stream": true }).to_string(),
        ])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|_| "curl is missing".to_string())?;
    let mut error = None;
    let mut done = false;
    if let Some(out) = child.stdout.take() {
        for line in std::io::BufReader::new(out).lines().map_while(Result::ok) {
            let Ok(v) = serde_json::from_str::<serde_json::Value>(&line) else {
                continue;
            };
            if let Some(e) = v["error"].as_str() {
                error = Some(e.to_string());
            }
            if let (Some(t), Some(c)) = (v["total"].as_u64(), v["completed"].as_u64())
                && t > 0
            {
                progress((c as f32 / t as f32).clamp(0.0, 1.0));
            }
            if v["status"].as_str() == Some("success") {
                done = true;
            }
        }
    }
    let _ = child.wait();
    forget_cached();
    match error {
        Some(_) => Err(format!(
            "Could not download {name}. Check the name and your internet, then try again."
        )),
        None if done => {
            progress(1.0);
            Ok(())
        }
        None => Err(format!("The download of {name} stopped before the end.")),
    }
}

/// Downloads a free model into Ollama (starting it if it can), or says why
/// not. Waits for the whole download.
pub fn add(name: &str) -> Result<(), String> {
    add_with_progress(name, |_| {})
}

/// Deletes a free model from Ollama. True when it went.
pub fn remove(name: &str) -> bool {
    let gone = crate::keep_current::delete_ollama(name);
    forget_cached();
    gone
}

/// The next look at the free models asks Ollama afresh.
pub fn forget_cached() {
    if let Ok(mut g) = CACHE.lock()
        && let Some((t, _)) = g.as_mut()
    {
        // Old enough to be looked at again; the list shows meanwhile.
        *t = Instant::now()
            .checked_sub(Duration::from_secs(60))
            .unwrap_or(*t);
    }
}

/// The model runner's user unit on this PC (started only when a free model is used).
pub fn unit_path() -> PathBuf {
    crate::paths::home().join(".config/systemd/user/mimir-ollama.service")
}

/// What a chat says when the PC's model program isn't running and could not
/// be started: one plain line, and the way out is to try again.
pub const MODEL_DOWN: &str =
    "Your PC's model isn't running and didn't start. Try again in a moment.";

/// Whether Ollama would have to be woken first (its unit is there, and it
/// isn't answering): what a call must not wait for on the service's thread.
pub fn sleeping() -> bool {
    unit_path().is_file() && installed().is_err()
}

/// Starts Ollama if it is asleep, without waiting for it.
pub fn wake() {
    if sleeping() {
        start_unit();
    }
}

/// When the model program was last asked to start (by anyone): one start at
/// a time, and none again right after one.
static LAST_START: std::sync::Mutex<Option<Instant>> = std::sync::Mutex::new(None);

/// A look at the models list found the model program asleep: start it, without
/// waiting and without a second look. Not again while a start is under way
/// (10 s), nor for 30 s after a start that failed (the same pause a waiting
/// chat gets). True when a start was asked for now.
pub fn wake_for_list() -> bool {
    let pause = if failed_start() {
        Duration::from_secs(30)
    } else {
        Duration::from_secs(10)
    };
    {
        let mut last = LAST_START.lock().unwrap_or_else(|e| e.into_inner());
        if last.is_some_and(|t| t.elapsed() < pause) {
            return false;
        }
        *last = Some(Instant::now());
    }
    start_unit();
    true
}

/// The last try to start the model program failed and nothing has answered
/// since. Reads a flag only (no call), so the service's thread may ask.
pub fn failed_start() -> bool {
    DOWN.load(std::sync::atomic::Ordering::SeqCst)
}

/// Asks systemd to start Ollama's unit, without waiting (a check names its
/// own starter in `MIMIR_OLLAMA_START`).
fn start_unit() {
    *LAST_START.lock().unwrap_or_else(|e| e.into_inner()) = Some(Instant::now());
    let mut c = match std::env::var_os("MIMIR_OLLAMA_START") {
        Some(p) => std::process::Command::new(p),
        None => {
            let mut c = crate::flatpak::host_cmd("systemctl");
            c.args(["--user", "start", "--no-block", "mimir-ollama.service"]);
            c
        }
    };
    let _ = c
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map(|mut ch| std::thread::spawn(move || ch.wait()));
}

/// Starts Ollama (its user unit) if it isn't answering, and waits up to
/// `wait` for it. True when it answers.
pub fn ensure_running(wait: Duration) -> bool {
    let up = ensure_running_inner(wait);
    DOWN.store(!up, std::sync::atomic::Ordering::SeqCst);
    up
}

/// The last try to start the PC's model program failed (and nothing has
/// answered since): what the top pill and the chat say. Starting it on
/// demand is normal; only a failed start is "down".
static DOWN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Whether the PC's model program is down: a start failed and it still isn't
/// answering. Looks (a short call) only while a failure is on record.
pub fn down() -> bool {
    if !DOWN.load(std::sync::atomic::Ordering::SeqCst) {
        return false;
    }
    if installed().is_ok() {
        DOWN.store(false, std::sync::atomic::Ordering::SeqCst);
        return false;
    }
    true
}

fn ensure_running_inner(wait: Duration) -> bool {
    if installed().is_ok() {
        return true;
    }
    if !unit_path().is_file() {
        return false;
    }
    start_unit();
    let begun = Instant::now();
    let until = begun + wait;
    while Instant::now() < until {
        if installed().is_ok() {
            crate::devlog::log(
                "model",
                "wake",
                vec![
                    ("ms", (begun.elapsed().as_millis() as u64).into()),
                    ("ok", true.into()),
                ],
            );
            return true;
        }
        std::thread::sleep(Duration::from_millis(300));
    }
    crate::devlog::error(
        "model",
        "wake",
        vec![("ms", (begun.elapsed().as_millis() as u64).into())],
        "Your PC's model didn't start in time.",
        "timeout",
    );
    false
}

/// The free models to offer: the installed ones, best known first.
pub fn catalogue(names: &[String]) -> Vec<FreeModel> {
    let notes = notes();
    let order = |n: &str| match notes.get(n) {
        Some(note) if note.starts_with("Didn't") => 3,
        Some(_) => 0,
        None => 2,
    };
    let mut names: Vec<&String> = names.iter().collect();
    // Benchmarked ones first, then by the time in their note.
    names.sort_by_key(|n| {
        (
            order(n),
            seconds_in(notes.get(*n).map(String::as_str).unwrap_or("")),
        )
    });
    names
        .into_iter()
        .map(|n| FreeModel {
            id: format!("{FREE}{n}"),
            name: n.clone(),
            note: notes
                .get(n)
                .cloned()
                .unwrap_or_else(|| "Not tested on this PC yet".into()),
            refusals_removed: refusals_removed(n),
        })
        .collect()
}

fn seconds_in(note: &str) -> u64 {
    let w: Vec<&str> = note.split_whitespace().collect();
    w.windows(2)
        .find_map(|p| {
            let n: u64 = p[0].parse().ok()?;
            match p[1].trim_matches(|c: char| !c.is_alphabetic()) {
                "s" => Some(n),
                "min" => Some(n * 60),
                _ => None,
            }
        })
        .unwrap_or(u64::MAX)
}

/// The free model a near-limit task gets: the first one offered that
/// isn't refusal-removed.
pub fn default_free(models: &[FreeModel]) -> Option<&FreeModel> {
    models
        .iter()
        .find(|m| !m.refusals_removed && !m.note.starts_with("Didn't"))
}

/// What the window shows (read again at most every 30 s, off its thread).
static CACHE: Mutex<Option<(Instant, Vec<FreeModel>)>> = Mutex::new(None);
static LOOKING: Mutex<bool> = Mutex::new(false);
/// The last look found the PC's model program not answering. The list then
/// stays as it was last seen, and is not "empty".
static OFF: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Whether the last look found the PC's model program off.
pub fn program_off() -> bool {
    OFF.load(std::sync::atomic::Ordering::SeqCst)
}

/// Starts the PC's model program (without waiting) and has the next look
/// come soon, keeping the last known list meanwhile.
pub fn start_program() {
    start_unit();
    forget_cached();
}

/// Keeps what a look found: a program that doesn't answer leaves the list as
/// it was (never an empty list that reads "nothing installed"), and with
/// nothing kept yet, the models its files show.
fn keep_look(found: Result<Vec<String>, String>) {
    let off = found.is_err();
    OFF.store(off, std::sync::atomic::Ordering::SeqCst);
    let names = installed_or_on_disk(&found);
    if let Ok(mut g) = CACHE.lock() {
        let kept = g.take().map(|(_, l)| l).filter(|l| off && !l.is_empty());
        let list = kept.unwrap_or_else(|| catalogue(&names));
        *g = Some((Instant::now(), list));
    }
}

/// The free models as last seen; starts a look when older than 30 s.
pub fn cached() -> Vec<FreeModel> {
    let (fresh, list) = match CACHE.lock() {
        Ok(g) => match g.as_ref() {
            Some((t, l)) => (t.elapsed() < Duration::from_secs(30), l.clone()),
            // Before the first look: what the files show.
            None => (false, catalogue(&installed_or_on_disk(&Err(String::new())))),
        },
        Err(_) => (true, vec![]),
    };
    if !fresh {
        let mut busy = LOOKING.lock().map(|g| *g).unwrap_or(true);
        if !busy {
            if let Ok(mut g) = LOOKING.lock() {
                *g = true;
            }
            busy = std::thread::Builder::new()
                .name("free-models".into())
                .spawn(|| {
                    keep_look(installed());
                    if let Ok(mut g) = LOOKING.lock() {
                        *g = false;
                    }
                })
                .is_err();
        }
        let _ = busy;
    }
    list
}

/// How many words look like a file: a `/`, or a dot then 1 to 4 letters.
pub fn file_words(text: &str) -> usize {
    text.split_whitespace()
        .filter(|w| {
            let w = w.trim_matches(|c: char| ",;:!?()\"'".contains(c));
            if w.contains('/') {
                return true;
            }
            w.rsplit_once('.').is_some_and(|(a, b)| {
                !a.is_empty()
                    && (1..=4).contains(&b.len())
                    && b.chars().all(|c| c.is_ascii_alphabetic())
            })
        })
        .count()
}

/// Did Claude Code stop because the plan's usage limit ran out? Some with
/// when it resets (seconds since 1970; 0 when the message doesn't say).
pub fn claude_limit(error: &str) -> Option<i64> {
    let e = error.to_lowercase();
    let hit = e.contains("usage limit")
        || e.contains("hit your limit")
        || e.contains("limit reached")
        || e.contains("out of extra usage");
    if !hit {
        return None;
    }
    // "Claude AI usage limit reached|1763524800"
    let at = error
        .rsplit_once('|')
        .and_then(|(_, n)| n.trim().parse::<i64>().ok())
        .unwrap_or(0);
    Some(at)
}

/// A time of day in the PC's own time zone ("3:00 PM"), with the day when
/// it isn't today ("Fri 3:00 PM").
pub fn clock(unix: i64) -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let fmt = if (unix - now).abs() < 20 * 3600 {
        "+%-I:%M %p"
    } else {
        "+%a %-I:%M %p"
    };
    std::process::Command::new("date")
        .args(["-d", &format!("@{unix}"), fmt])
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| format!("in about {} h", ((unix - now).max(0) + 1800) / 3600))
}

/// Did a free model run out of room? (Claude Code's error, or Ollama's.)
pub fn out_of_room(error: &str) -> bool {
    let e = error.to_lowercase();
    (e.contains("context")
        && (e.contains("length")
            || e.contains("limit")
            || e.contains("window")
            || e.contains("exceed")))
        || e.contains("prompt is too long")
        || e.contains("too many tokens")
}

#[cfg(test)]
mod tests {
    /// 0.09 item 25: asking for the models list does not start the program again while a
    /// start is under way, nor soon after a failed one (these cases never start anything).
    #[test]
    fn the_models_list_starts_the_program_once_and_pauses_after_a_failure() {
        use std::time::{Duration, Instant};
        *super::LAST_START.lock().unwrap() = Some(Instant::now() - Duration::from_secs(5));
        super::DOWN.store(false, std::sync::atomic::Ordering::SeqCst);
        assert!(
            !super::wake_for_list(),
            "a start 5 s ago is still under way"
        );
        *super::LAST_START.lock().unwrap() = Some(Instant::now() - Duration::from_secs(20));
        super::DOWN.store(true, std::sync::atomic::Ordering::SeqCst);
        assert!(super::failed_start());
        assert!(
            !super::wake_for_list(),
            "a failed start is left alone for 30 s"
        );
        super::DOWN.store(false, std::sync::atomic::Ordering::SeqCst);
    }

    #[test]
    fn a_program_that_is_off_keeps_the_last_list() {
        super::keep_look(Ok(vec!["gpt-oss:20b".into()]));
        assert!(!super::program_off());
        let had = super::cached();
        assert!(!had.is_empty(), "the look found a model");
        super::keep_look(Err("Ollama isn't running".into()));
        assert!(super::program_off());
        assert_eq!(super::cached(), had, "still the last known list");
        super::keep_look(Ok(vec!["gpt-oss:20b".into()]));
        assert!(!super::program_off());
    }
    use super::*;

    #[test]
    fn file_looking_words_are_counted() {
        assert_eq!(file_words("fix the typo in README.md please"), 1);
        assert_eq!(file_words("compare src/a.rs and b.toml"), 2);
        assert_eq!(file_words("Hello. What is 2.5 times 3?"), 0);
    }

    #[test]
    fn claudes_limit_is_recognised_with_its_reset() {
        assert_eq!(
            claude_limit("Claude AI usage limit reached|1763524800"),
            Some(1763524800)
        );
        assert_eq!(
            claude_limit("You've hit your limit · resets 3pm (America/Los_Angeles)"),
            Some(0)
        );
        assert_eq!(claude_limit("API Error: 500 internal"), None);
    }

    #[test]
    fn out_of_room_is_recognised() {
        assert!(out_of_room(
            "API Error: 400 {\"error\":\"the input length exceeds the context length\"}"
        ));
        assert!(out_of_room("Prompt is too long"));
        assert!(!out_of_room("API Error: 500 internal"));
    }

    #[test]
    fn tested_models_come_first_and_abliterated_ones_say_so() {
        let names: Vec<String> = [
            "mystery:7b",
            "qwen3-coder:30b",
            "huihui_ai/gpt-oss-abliterated:20b",
            "qwen3.6:35b-a3b-coding",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        let n = first_notes();
        let order = |x: &str| n.get(x).cloned();
        assert!(order("qwen3.6:35b-a3b-coding").is_some());
        let mut sorted = names.clone();
        sorted.sort_by_key(|x| {
            (
                match n.get(x) {
                    Some(t) if t.starts_with("Didn't") => 3,
                    Some(_) => 0,
                    None => 2,
                },
                seconds_in(n.get(x).map(String::as_str).unwrap_or("")),
            )
        });
        assert_eq!(sorted[0], "qwen3.6:35b-a3b-coding");
        assert_eq!(sorted[1], "huihui_ai/gpt-oss-abliterated:20b");
        assert!(refusals_removed("huihui_ai/gpt-oss-abliterated:20b"));
        assert!(!refusals_removed("gpt-oss:20b"));
        assert_eq!(seconds_in("Fixed both test bugs in 3 min"), 180);
    }
}

#[cfg(test)]
mod plain_note_tests {
    use super::plain_note;

    #[test]
    fn an_old_note_reads_plainly_and_other_notes_stay() {
        assert_eq!(
            plain_note(
                "Answered by Qwen 3.6 coding, a free model on your PC. Your chats stay on this PC."
            ),
            "Answered by Qwen 3.6 coding, a model on your PC. Your work stays here."
        );
        assert_eq!(
            plain_note(
                "On qwen3.6:35b-a3b-coding, a free model on this PC (Settings \u{203a} Models \u{203a} What powers your tasks)."
            ),
            "Answered by Qwen 3.6 coding, a model on your PC. Your work stays here."
        );
        assert_eq!(
            plain_note(
                "On qwen3.6:35b-a3b-coding because the online limit ran out; online is back at 5 pm."
            ),
            "On Qwen 3.6 coding because the online limit ran out; online is back at 5 pm."
        );
        assert_eq!(
            plain_note(crate::runner::STOPPED_NOTE),
            crate::runner::STOPPED_NOTE
        );
    }
}
