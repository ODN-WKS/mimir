//! How the PC picks the model its chats run on (Stage 1 design, 1.2): it
//! reads the hardware (no guessing from names), works out what fits, then
//! measures the candidates already on disk with a short probe when the PC
//! is calm, and keeps the best one that is fast enough. The result is kept
//! in `engine.json` with a fingerprint of the hardware and Ollama, and the
//! probe runs again when that changes or a new candidate arrives. The
//! runner-up stays on disk (nothing here removes a model).

use crate::models::{Role, STORE, StorePick};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::time::{Duration, Instant};

const GB: u64 = 1_000_000_000;

/// Left on the graphics card for the desktop and games.
pub const DESKTOP_RESERVE: u64 = 1_500_000_000;
/// About what a 16k context needs beside the weights.
pub const CONTEXT_ROOM: u64 = 2 * GB;
/// The context chats get (tokens).
pub const CHAT_CONTEXT: u64 = 16_384;
/// A card with less fast memory than this isn't used.
const USABLE_CARD: u64 = 3 * GB;
/// The pick must start writing within this (warm) and write this fast.
pub const FIRST_TOKEN_MS: u64 = 3_000;
pub const GOOD_TOKENS_S: f32 = 20.0;
/// Below this even the fastest isn't used.
pub const LEAST_TOKENS_S: f32 = 8.0;
/// Free disk kept beside a download.
pub const DISK_SPARE: u64 = 10 * GB;

/// The PC's graphics card, as the driver reports it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Gpu {
    /// "AMD", "NVIDIA", "Intel" or the vendor's number.
    pub vendor: String,
    /// Its own memory in bytes (0 when it shares the main memory).
    pub vram: u64,
}

/// What the probe and the pick need to know about the PC.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Hardware {
    pub gpu: Option<Gpu>,
    pub ram: u64,
    pub threads: u32,
    /// Free disk where Ollama keeps its models.
    pub disk_free: Option<u64>,
}

/// Reads the hardware from the system itself.
pub fn read_hardware() -> Hardware {
    Hardware {
        gpu: read_gpu(),
        ram: meminfo("MemTotal:").unwrap_or(0),
        threads: std::thread::available_parallelism()
            .map(|n| n.get() as u32)
            .unwrap_or(1),
        disk_free: disk_free(&models_dir()),
    }
}

fn read_gpu() -> Option<Gpu> {
    let mut best: Option<Gpu> = None;
    for e in std::fs::read_dir("/sys/class/drm")
        .into_iter()
        .flatten()
        .flatten()
    {
        let name = e.file_name().to_string_lossy().to_string();
        if !name.starts_with("card") || name.contains('-') {
            continue;
        }
        let dev = e.path().join("device");
        let vendor = std::fs::read_to_string(dev.join("vendor")).unwrap_or_default();
        let vendor = match vendor.trim() {
            "0x1002" => "AMD".to_string(),
            "0x10de" => "NVIDIA".to_string(),
            "0x8086" => "Intel".to_string(),
            "" => continue,
            v => v.to_string(),
        };
        let vram = std::fs::read_to_string(dev.join("mem_info_vram_total"))
            .ok()
            .and_then(|t| t.trim().parse::<u64>().ok())
            .or_else(|| (vendor == "NVIDIA").then(nvidia_memory).flatten())
            .unwrap_or(0);
        if best.as_ref().is_none_or(|b| vram > b.vram) {
            best = Some(Gpu { vendor, vram });
        }
    }
    best
}

fn nvidia_memory() -> Option<u64> {
    let out = std::process::Command::new("nvidia-smi")
        .args(["--query-gpu=memory.total", "--format=csv,noheader,nounits"])
        .output()
        .ok()?;
    let mib: u64 = String::from_utf8_lossy(&out.stdout)
        .lines()
        .filter_map(|l| l.trim().parse().ok())
        .max()?;
    Some(mib * 1024 * 1024)
}

fn meminfo(key: &str) -> Option<u64> {
    std::fs::read_to_string("/proc/meminfo")
        .ok()?
        .lines()
        .find(|l| l.starts_with(key))?
        .split_whitespace()
        .nth(1)?
        .parse::<u64>()
        .ok()
        .map(|kb| kb * 1024)
}

/// Where Ollama keeps its models (the unit's `OLLAMA_MODELS`).
pub fn models_dir() -> PathBuf {
    std::env::var_os("OLLAMA_MODELS")
        .map(PathBuf::from)
        .unwrap_or_else(|| crate::paths::home().join(".local/share/ollama-models"))
}

fn disk_free(dir: &std::path::Path) -> Option<u64> {
    let mut d = dir.to_path_buf();
    while !d.exists() {
        d = d.parent()?.to_path_buf();
    }
    let c = std::ffi::CString::new(d.as_os_str().as_encoded_bytes()).ok()?;
    // SAFETY: statvfs fills the struct we own from a valid C string.
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(c.as_ptr(), &mut st) } != 0 {
        return None;
    }
    Some(st.f_bavail as u64 * st.f_frsize as u64)
}

/// Fast memory (the card's, less what the desktop needs) and spill memory
/// (the smaller of half the RAM and RAM less 8 GB).
pub fn budgets(hw: &Hardware) -> (u64, u64) {
    let fast = hw
        .gpu
        .as_ref()
        .map(|g| g.vram.saturating_sub(DESKTOP_RESERVE))
        .filter(|f| *f >= USABLE_CARD)
        .unwrap_or(0);
    let spill = (hw.ram / 2).min(hw.ram.saturating_sub(8 * GB));
    (fast, spill)
}

/// How a model would run on this PC.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Fit {
    /// Wholly on the graphics card.
    Card,
    /// A mixture-of-experts model partly on the card, the rest on the processor.
    Split,
    /// On the processor only (no usable card).
    Processor,
}

/// Whether `m` qualifies for `role` here, and how it would run.
pub fn fit(m: &StorePick, role: Role, fast: u64, spill: u64) -> Option<Fit> {
    if m.role != role || (role == Role::Chat && !m.uncensored) {
        return None;
    }
    if fast > 0 && m.bytes + CONTEXT_ROOM <= fast {
        return Some(Fit::Card);
    }
    if fast > 0 && m.moe_active_b.is_some_and(|a| a <= 6.0) && m.bytes <= fast + spill {
        return Some(Fit::Split);
    }
    if fast == 0 && m.bytes + CONTEXT_ROOM <= spill {
        return Some(Fit::Processor);
    }
    None
}

/// The models that qualify for `role` on this hardware, best-ranked first.
pub fn candidates(hw: &Hardware, role: Role) -> Vec<(&'static StorePick, Fit)> {
    let (fast, spill) = budgets(hw);
    let mut c: Vec<(&'static StorePick, Fit)> = STORE
        .iter()
        .filter_map(|m| fit(m, role, fast, spill).map(|f| (m, f)))
        .collect();
    c.sort_by_key(|(m, _)| m.rank);
    c
}

/// What the probe measured for one model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Measure {
    pub model: String,
    /// Time to the first written piece, warm (the middle of three).
    pub first_token_ms: u64,
    pub tokens_per_s: f32,
    /// How much of it sat on the graphics card (0 to 1).
    pub on_card: f32,
    /// "ROCm", "Vulkan", "CUDA" or "processor".
    pub backend: String,
    /// The cold start: loading plus the first answer.
    #[serde(default)]
    pub cold_ms: u64,
}

/// The pick: the best-ranked one that starts within 3 s and writes at least
/// 20 tokens a second; else the fastest with at least 8; else none, and why.
pub fn choose<'a>(ranked: &[&'a Measure]) -> Result<&'a Measure, String> {
    if let Some(m) = ranked
        .iter()
        .find(|m| m.first_token_ms <= FIRST_TOKEN_MS && m.tokens_per_s >= GOOD_TOKENS_S)
    {
        return Ok(m);
    }
    ranked
        .iter()
        .filter(|m| m.tokens_per_s >= LEAST_TOKENS_S)
        .max_by(|a, b| a.tokens_per_s.total_cmp(&b.tokens_per_s))
        .copied()
        .ok_or_else(|| {
            if ranked.is_empty() {
                // PCE-23: give the owner a path forward.
                "No downloaded model runs fully on this PC's memory. \
                 You can still use a smaller model or add more RAM."
                    .into()
            } else {
                "Your PC's models answer too slowly for a chat (under 8 words a second).".into()
            }
        })
}

/// The chosen model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Pick {
    pub model: String,
    pub title: String,
    pub fit: Fit,
    pub tokens_per_s: f32,
    pub first_token_ms: u64,
    pub cold_ms: u64,
    pub backend: String,
}

/// What `engine.json` keeps.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct EngineFile {
    /// The card, RAM, Ollama's version and the backend.
    pub fingerprint: String,
    /// When the probe ran (seconds since 1970).
    pub at: i64,
    pub chat: Option<Pick>,
    /// Why there is no chat pick, in one plain line.
    #[serde(default)]
    pub why: Option<String>,
    pub measures: Vec<Measure>,
}

pub fn file() -> PathBuf {
    crate::paths::data_dir().join("engine.json")
}

pub fn load() -> Option<EngineFile> {
    serde_json::from_str(&std::fs::read_to_string(file()).ok()?).ok()
}

fn save(e: &EngineFile) {
    let f = file();
    if let Some(d) = f.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    let tmp = f.with_extension("json.tmp");
    if std::fs::write(&tmp, serde_json::to_vec_pretty(e).unwrap_or_default()).is_ok() {
        let _ = std::fs::rename(tmp, f);
    }
}

/// The fingerprint the pick holds for: the card, RAM (to the GB), Ollama's
/// version and the backend it last used.
pub fn fingerprint(hw: &Hardware, ollama: &str, backend: &str) -> String {
    let card = hw
        .gpu
        .as_ref()
        .map(|g| format!("{} {} GB", g.vendor, g.vram / GB))
        .unwrap_or_else(|| "no card".into());
    format!(
        "{card} · {} GB RAM · Ollama {ollama} · {backend}",
        hw.ram / GB
    )
}

/// Which backend Ollama used last, from its unit's log ("ROCm", "Vulkan",
/// "CUDA"), or "" when the log doesn't say.
pub fn backend_from_log() -> String {
    let out = crate::flatpak::host_cmd("journalctl")
        .args([
            "--user",
            "-u",
            "mimir-ollama.service",
            "-g",
            "ROCm0|Vulkan0|CUDA0|library=",
            "-n",
            "20",
            "--no-pager",
            "-o",
            "cat",
        ])
        .output();
    let text = out
        .map(|o| String::from_utf8_lossy(&o.stdout).to_string())
        .unwrap_or_default();
    backend_in(&text)
}

fn backend_in(log: &str) -> String {
    let mut last = (0, "");
    for (word, name) in [
        ("ROCm0", "ROCm"),
        ("library=ROCm", "ROCm"),
        ("Vulkan0", "Vulkan"),
        ("library=Vulkan", "Vulkan"),
        ("CUDA0", "CUDA"),
        ("library=CUDA", "CUDA"),
    ] {
        if let Some(i) = log.rfind(word)
            && i + 1 > last.0
        {
            last = (i + 1, name);
        }
    }
    last.1.to_string()
}

/// Whether the probe must run: no pick yet, the fingerprint changed, or a
/// candidate on disk hasn't been measured (the weekly check brought one).
pub fn needs_probe(e: Option<&EngineFile>, print: &str, on_disk: &[String]) -> bool {
    let Some(e) = e else { return true };
    if e.fingerprint != print {
        return true;
    }
    on_disk
        .iter()
        .any(|m| !e.measures.iter().any(|x| &x.model == m))
}

/// The three everyday questions the probe asks (after a warm-up).
const QUESTIONS: [&str; 3] = [
    "What's the capital of Norway? Answer in one sentence.",
    "Give me three tips for sleeping better.",
    "Explain in two sentences why the sky is blue.",
];

/// One timed question: time to the first piece, and tokens a second.
fn ask(base: &str, model: &str, q: &str, max: u32) -> Result<(u64, f32), String> {
    let body = serde_json::json!({
        "model": model,
        "messages": [{"role": "user", "content": q}],
        "stream": true,
        "think": false,
        "keep_alive": "10m",
        "options": {"num_ctx": CHAT_CONTEXT, "num_predict": max},
    });
    let started = Instant::now();
    let mut s = crate::ollama::post_stream_at(base, "/api/chat", &body, Duration::from_secs(300))?;
    let mut first = None;
    let mut tps = 0.0;
    while let Some(v) = s.next_json()? {
        let m = &v["message"];
        let wrote = m["content"].as_str().is_some_and(|t| !t.is_empty())
            || m["thinking"].as_str().is_some_and(|t| !t.is_empty());
        if wrote && first.is_none() {
            first = Some(started.elapsed().as_millis() as u64);
        }
        if v["done"].as_bool() == Some(true) {
            let n = v["eval_count"].as_f64().unwrap_or(0.0);
            let ns = v["eval_duration"].as_f64().unwrap_or(0.0);
            if ns > 0.0 {
                tps = (n / (ns / 1e9)) as f32;
            }
        }
    }
    Ok((first.unwrap_or(started.elapsed().as_millis() as u64), tps))
}

/// Measures one model on the Ollama at `base`: a cold load and warm-up,
/// then the three questions.
pub fn probe(base: &str, model: &str, backend: &str) -> Result<Measure, String> {
    let cold = Instant::now();
    ask(base, model, "Say hello.", 8)?;
    let cold_ms = cold.elapsed().as_millis() as u64;
    let mut firsts = vec![];
    let mut speeds = vec![];
    for q in QUESTIONS {
        let (f, t) = ask(base, model, q, 160)?;
        firsts.push(f);
        speeds.push(t);
    }
    firsts.sort();
    let on_card = crate::ollama::loaded(base)
        .into_iter()
        .find(|(n, _, _)| n == model)
        .map(|(_, size, vram)| {
            if size == 0 {
                0.0
            } else {
                vram as f32 / size as f32
            }
        })
        .unwrap_or(0.0);
    let backend = if on_card <= 0.01 {
        "processor".to_string()
    } else if backend.is_empty() {
        "graphics card".to_string()
    } else {
        backend.to_string()
    };
    Ok(Measure {
        model: model.to_string(),
        first_token_ms: firsts[firsts.len() / 2],
        tokens_per_s: speeds.iter().sum::<f32>() / speeds.len().max(1) as f32,
        on_card,
        backend,
        cold_ms,
    })
}

/// The graphics card's busy share (0 to 100), the busiest card's.
pub fn gpu_busy() -> f32 {
    let mut busy: f32 = 0.0;
    for e in std::fs::read_dir("/sys/class/drm")
        .into_iter()
        .flatten()
        .flatten()
    {
        if let Some(n) = std::fs::read_to_string(e.path().join("device/gpu_busy_percent"))
            .ok()
            .and_then(|t| t.trim().parse::<f32>().ok())
        {
            busy = busy.max(n);
        }
    }
    busy
}

fn load1() -> f32 {
    std::fs::read_to_string("/proc/loadavg")
        .ok()
        .and_then(|t| t.split_whitespace().next()?.parse().ok())
        .unwrap_or(0.0)
}

/// Whether the PC is calm enough for a fair probe: load under half the
/// threads and the graphics card under 20% busy for `over` (sampled every
/// 2 s).
pub fn calm(hw: &Hardware, over: Duration) -> bool {
    let until = Instant::now() + over;
    loop {
        if load1() >= hw.threads as f32 / 2.0 || gpu_busy() >= 20.0 {
            return false;
        }
        if Instant::now() >= until {
            return true;
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

/// Whether Ollama's copy of the small model is the very file the phone's
/// list names (its SHA-256 is one of the model's layers).
pub fn small_model_checked() -> bool {
    let (repo, tag) = crate::models::SMALL_CHAT
        .split_once(':')
        .unwrap_or((crate::models::SMALL_CHAT, "latest"));
    let manifest = models_dir().join("manifests").join(repo).join(tag);
    std::fs::read_to_string(manifest)
        .ok()
        .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
        .and_then(|v| v["layers"].as_array().cloned())
        .is_some_and(|l| {
            l.iter().any(|x| {
                x["digest"].as_str()
                    == Some(&format!("sha256:{}", crate::models::SMALL_CHAT_SHA256))
            })
        })
}

/// What a probe run may do on the PC (checks give stand-ins).
pub struct Hands<'a> {
    pub base: String,
    /// The models Ollama has.
    pub installed: &'a dyn Fn() -> Vec<String>,
    /// Downloads one; Err with why.
    pub download: &'a dyn Fn(&str) -> Result<(), String>,
    /// Removes one.
    pub remove: &'a dyn Fn(&str) -> bool,
    /// Models kept whatever happens (the project model).
    pub keep: Vec<String>,
}

/// The real PC's hands.
pub fn real_hands(keep: Vec<String>) -> Hands<'static> {
    Hands {
        base: crate::models::ollama_url(),
        installed: &|| crate::models::installed().unwrap_or_default(),
        download: &|m| {
            let r = crate::models::add(m);
            if r.is_err() {
                // A failed or cancelled download is removed at once.
                crate::models::remove(m);
            }
            r
        },
        remove: &crate::models::remove,
        keep,
    }
}

/// Picks the chat model: measures each qualifying model on disk (on a new
/// PC, first downloads the smallest that qualifies, so chat works within
/// minutes), chooses (the runner-ups stay).
pub fn run(hw: &Hardware, hands: &Hands, backend: &str) -> EngineFile {
    run_with(hw, hands, backend, None)
}

/// As `run`; with `only`, just that model is measured (nothing downloaded).
pub fn run_with(hw: &Hardware, hands: &Hands, backend: &str, only: Option<&str>) -> EngineFile {
    let mut cands = candidates(hw, Role::Chat);
    if let Some(o) = only {
        cands.retain(|(m, _)| m.name == o);
    }
    let mut on_disk: Vec<String> = (hands.installed)();
    let mut note = None;
    if !cands
        .iter()
        .any(|(m, _)| on_disk.iter().any(|d| d == m.name))
    {
        // The smallest first; stronger ones come later, one at a time.
        if let Some((m, _)) = cands.iter().min_by_key(|(m, _)| m.bytes) {
            if hw.disk_free.is_some_and(|f| f < m.bytes + DISK_SPARE) {
                note = Some("There isn't room on this PC's disk for a chat model.".to_string());
            } else {
                match (hands.download)(m.name) {
                    Ok(()) => on_disk = (hands.installed)(),
                    Err(e) => note = Some(e),
                }
            }
        }
    }
    let mut measures = vec![];
    for (m, _) in &cands {
        if !on_disk.iter().any(|d| d == m.name) {
            continue;
        }
        if m.name == crate::models::SMALL_CHAT && !small_model_checked() {
            crate::log::write("engine", "small model doesn't match its SHA-256", m.name);
            (hands.remove)(m.name);
            continue;
        }
        match probe(&hands.base, m.name, backend) {
            Ok(x) => measures.push(x),
            Err(e) => crate::log::write("engine", "probe failed", &format!("{}: {e}", m.name)),
        }
    }
    let ranked: Vec<&Measure> = measures.iter().collect();
    let picked = choose(&ranked);
    let chat = picked.as_ref().ok().map(|m| {
        let (s, f) = cands
            .iter()
            .find(|(c, _)| c.name == m.model)
            .map(|(c, f)| (*c, *f))
            .expect("measured models are candidates");
        Pick {
            model: m.model.clone(),
            title: s.title.to_string(),
            fit: f,
            tokens_per_s: m.tokens_per_s,
            first_token_ms: m.first_token_ms,
            cold_ms: m.cold_ms,
            backend: m.backend.clone(),
        }
    });
    // The runner-ups stay: no model is removed here any more (the models
    // are shared with the new Mímir).
    let used_backend = chat
        .as_ref()
        .map(|p| p.backend.clone())
        .unwrap_or_else(|| backend.to_string());
    EngineFile {
        fingerprint: fingerprint(
            hw,
            &crate::ollama::version(&hands.base).unwrap_or_default(),
            &used_backend,
        ),
        at: crate::needs::now(),
        why: picked.err().or(note).filter(|_| chat.is_none()),
        chat,
        measures,
    }
}

/// Where chats run, in one line for people.
pub fn words(e: Option<&EngineFile>) -> String {
    match e.and_then(|e| e.chat.as_ref()) {
        Some(p) => {
            // PCE-20: "Split" means layers overflow to the CPU.  Show "GPU + CPU"
            // so the owner knows it runs partly on the processor.
            let place = match p.fit {
                Fit::Card => "your PC's graphics card",
                Fit::Split => "your PC's graphics card (GPU + CPU)",
                Fit::Processor => "your PC's processor",
            };
            // About three words for every four tokens.
            let w = (p.tokens_per_s * 0.75 / 5.0).round() as u32 * 5;
            format!("Chats run on {place} · about {} words a second", w.max(5))
        }
        None => match e.and_then(|e| e.why.clone()) {
            Some(why) => format!("Chats run on your phone: {why}"),
            None => "Your PC hasn't tested its models yet".into(),
        },
    }
}

/// The model chats use now, if any. With no test result yet (a new PC, or
/// the PC was busy), the first chat still gets an answer: the smallest
/// chat model already on the PC, until the test picks a better one.
pub fn chat_model() -> Option<Pick> {
    let saved = load().and_then(|e| e.chat);
    if saved.is_some() {
        return saved;
    }
    // Looking costs a hardware read and a call to Ollama: once in 30 s.
    let mut cache = DEFAULT_CACHE.lock().unwrap();
    if let Some((at, p)) = cache.as_ref()
        && at.elapsed() < Duration::from_secs(30)
    {
        return p.clone();
    }
    let p = crate::models::installed()
        .ok()
        .and_then(|i| default_pick(&read_hardware(), &i, &backend_from_log()));
    *cache = Some((Instant::now(), p.clone()));
    p
}

static DEFAULT_CACHE: std::sync::Mutex<Option<(Instant, Option<Pick>)>> =
    std::sync::Mutex::new(None);

/// The stand-in pick while no test result exists: of the chat models on
/// the PC that fit it, the smallest; else the smallest chat model on the
/// PC at all (it may be slow, but it answers).
pub fn default_pick(hw: &Hardware, installed: &[String], backend: &str) -> Option<Pick> {
    let on_disk = |m: &StorePick| installed.iter().any(|d| d == m.name);
    let fitting = candidates(hw, Role::Chat)
        .into_iter()
        .filter(|(m, _)| on_disk(m))
        .min_by_key(|(m, _)| m.bytes);
    let (m, f) = match fitting {
        Some(x) => x,
        None => {
            let m = STORE
                .iter()
                .filter(|m| m.role == Role::Chat && m.uncensored && on_disk(m))
                .min_by_key(|m| m.bytes)?;
            (m, Fit::Processor)
        }
    };
    Some(Pick {
        model: m.name.to_string(),
        title: m.title.to_string(),
        fit: f,
        tokens_per_s: 0.0,
        first_token_ms: 0,
        cold_ms: 0,
        backend: backend.to_string(),
    })
}

/// How long a busy PC may keep the model test waiting before it runs anyway.
const PROBE_PATIENCE: Duration = Duration::from_secs(5 * 60);

/// When the test first found the PC busy (this run of Mímir).
static BUSY_SINCE: std::sync::Mutex<Option<Instant>> = std::sync::Mutex::new(None);

/// What the model test does now.
#[derive(Debug, PartialEq, Eq)]
pub enum Gate {
    /// The PC is calm: test everything.
    Run,
    /// Busy too long: test only the model chats already use.
    RunOne,
    /// Wait and look again.
    Wait,
}

/// Decides from how the PC is: never while an answer is being written;
/// when busy, only after the patience is used up.
pub fn gate(calm: bool, answering: bool, since: Option<Instant>, now: Instant) -> Gate {
    if answering {
        Gate::Wait
    } else if calm {
        Gate::Run
    } else if since.is_some_and(|s| waited_enough(s, now)) {
        Gate::RunOne
    } else {
        Gate::Wait
    }
}

/// Whether a PC that was busy since `since` has waited long enough.
pub fn waited_enough(since: Instant, now: Instant) -> bool {
    now.saturating_duration_since(since) >= PROBE_PATIENCE
}

/// Keeps the pick current: when the probe is needed and the PC is calm,
/// runs it. Returns whether it ran. `keep` names the project model.
pub fn keep_fresh(keep: Vec<String>, answering: &(dyn Fn() -> bool + Sync)) -> bool {
    // Only the Ollama Mímir installed (or one a check names): never a
    // stranger's on the same port.
    if !crate::models::unit_path().is_file() && std::env::var_os("MIMIR_OLLAMA").is_none() {
        return false;
    }
    // With a pick made, Ollama isn't woken just to look again.
    if load().is_some() && crate::models::installed().is_err() {
        return false;
    }
    if !crate::models::ensure_running(Duration::from_secs(30)) {
        return false;
    }
    let hw = read_hardware();
    let backend = backend_from_log();
    let version = crate::ollama::version(&crate::models::ollama_url()).unwrap_or_default();
    let have = load();
    let print = fingerprint(
        &hw,
        &version,
        have.as_ref()
            .and_then(|e| e.chat.as_ref())
            .map(|p| p.backend.as_str())
            .filter(|_| backend.is_empty())
            .unwrap_or(&backend),
    );
    let on_disk: Vec<String> = crate::models::installed()
        .unwrap_or_default()
        .into_iter()
        .filter(|m| candidates(&hw, Role::Chat).iter().any(|(c, _)| c.name == m))
        .collect();
    if !needs_probe(have.as_ref(), &print, &on_disk) {
        *BUSY_SINCE.lock().unwrap() = None;
        return false;
    }
    let calm_now = !answering() && calm(&hw, Duration::from_secs(30));
    let since = if calm_now {
        None
    } else {
        Some(*BUSY_SINCE.lock().unwrap().get_or_insert_with(Instant::now))
    };
    let only = match gate(calm_now, answering(), since, Instant::now()) {
        Gate::Run => None,
        Gate::Wait => {
            crate::log::write("engine", "probe waits", "the PC is busy");
            return false;
        }
        Gate::RunOne => {
            let Some(p) = default_pick(
                &hw,
                &crate::models::installed().unwrap_or_default(),
                &backend,
            ) else {
                return false;
            };
            crate::log::write(
                "engine",
                "probe runs anyway",
                "the PC stayed busy for 5 minutes; testing one model",
            );
            Some(p.model)
        }
    };
    *BUSY_SINCE.lock().unwrap() = None;
    let e = run_with(&hw, &real_hands(keep), &backend, only.as_deref());
    save(&e);
    crate::log::write("engine", "picked", &words(Some(&e)));
    true
}

/// `mimir engine [probe]`: says where chats run; `probe` measures
/// now (when the PC is calm) and prints what it found.
pub fn command(args: &[String]) -> i32 {
    match args.first().map(String::as_str) {
        None => {
            println!("{}", words(load().as_ref()));
            if let Some(e) = load() {
                for m in &e.measures {
                    println!(
                        "  {}: first words {:.1} s, {:.0} tokens a second, {:.0}% on the card, {}, cold {:.1} s",
                        m.model,
                        m.first_token_ms as f32 / 1000.0,
                        m.tokens_per_s,
                        m.on_card * 100.0,
                        m.backend,
                        m.cold_ms as f32 / 1000.0
                    );
                }
            }
            0
        }
        Some("probe") => {
            let keep = args[1..].to_vec();
            let _ = std::fs::remove_file(file());
            if keep_fresh(keep, &|| false) {
                command(&[])
            } else {
                eprintln!("The probe didn't run: Ollama isn't answering, or the PC is busy.");
                1
            }
        }
        Some("measure") => {
            // Measures what's on disk and says what it would pick: nothing
            // is saved, downloaded or removed.
            let hw = read_hardware();
            if !calm(&hw, Duration::from_secs(30)) {
                eprintln!("The PC is busy: a measure now wouldn't be fair.");
                return 1;
            }
            let none = |_: &str| false;
            let hands = Hands {
                base: crate::models::ollama_url(),
                installed: &|| crate::models::installed().unwrap_or_default(),
                download: &|_| Err("measure only".into()),
                remove: &none,
                keep: vec![],
            };
            let e = run(&hw, &hands, &backend_from_log());
            println!("{}", words(Some(&e)));
            for m in &e.measures {
                println!("  {m:?}");
            }
            0
        }
        Some(_) => {
            eprintln!("usage: mimir engine [probe [model to keep...] | measure]");
            2
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hw(vram_gb: u64, ram_gb: u64) -> Hardware {
        Hardware {
            gpu: (vram_gb > 0).then(|| Gpu {
                vendor: "AMD".into(),
                vram: vram_gb * GB,
            }),
            ram: ram_gb * GB,
            threads: 32,
            disk_free: Some(500 * GB),
        }
    }

    fn names(h: &Hardware) -> Vec<(&'static str, Fit)> {
        candidates(h, Role::Chat)
            .into_iter()
            .map(|(m, f)| (m.name, f))
            .collect()
    }

    #[test]
    fn the_rules_pick_what_fits_each_kind_of_pc() {
        // This PC: a 17.1 GB card (16 GB) and 62 GB of RAM.
        let this = Hardware {
            gpu: Some(Gpu {
                vendor: "AMD".into(),
                vram: 17_163_091_968,
            }),
            ram: 66_000_000_000,
            threads: 32,
            disk_free: Some(3000 * GB),
        };
        let (fast, spill) = budgets(&this);
        assert_eq!(fast, 15_663_091_968);
        assert_eq!(spill, 33_000_000_000);
        assert_eq!(
            names(&this),
            vec![
                ("huihui_ai/Qwen3.6-abliterated:35b-a3b", Fit::Split),
                ("huihui_ai/gpt-oss-abliterated:20b", Fit::Split),
                (crate::models::SMALL_CHAT, Fit::Card),
            ]
        );
        // 8 GB card: the 20B partly in RAM, or the small one on the card.
        let n = names(&hw(8, 32));
        assert!(
            n.contains(&("huihui_ai/gpt-oss-abliterated:20b", Fit::Split)),
            "{n:?}"
        );
        assert!(n.contains(&(crate::models::SMALL_CHAT, Fit::Card)), "{n:?}");
        // No card, 64 GB: mixture-of-experts on the processor.
        let n = names(&hw(0, 64));
        assert_eq!(
            n[0],
            ("huihui_ai/Qwen3.6-abliterated:35b-a3b", Fit::Processor)
        );
        // No card, 16 GB: only the phone-size model.
        assert_eq!(
            names(&hw(0, 16)),
            vec![(crate::models::SMALL_CHAT, Fit::Processor)]
        );
        // Nothing fits on a tiny PC.
        assert!(names(&hw(0, 8)).is_empty());
        // Coding models are never chat picks, and chats are always uncensored.
        assert!(
            candidates(&this, Role::Chat)
                .iter()
                .all(|(m, _)| m.uncensored)
        );
        assert_eq!(
            candidates(&this, Role::Code)[0].0.name,
            "qwen3.6:35b-a3b-coding"
        );
    }

    fn m(model: &str, first: u64, tps: f32) -> Measure {
        Measure {
            model: model.into(),
            first_token_ms: first,
            tokens_per_s: tps,
            on_card: 1.0,
            backend: "ROCm".into(),
            cold_ms: 9000,
        }
    }

    #[test]
    fn the_pick_is_the_best_ranked_fast_enough_one() {
        let a = m("a", 2500, 25.0);
        let b = m("b", 800, 90.0);
        assert_eq!(choose(&[&a, &b]).unwrap().model, "a");
        // Too slow to start: the next one.
        let a = m("a", 4000, 25.0);
        assert_eq!(choose(&[&a, &b]).unwrap().model, "b");
        // None passes: the fastest with at least 8.
        let a = m("a", 5000, 12.0);
        let b = m("b", 5000, 9.0);
        assert_eq!(choose(&[&a, &b]).unwrap().model, "a");
        // Nothing usable: why, in plain words.
        let a = m("a", 5000, 3.0);
        assert!(choose(&[&a]).unwrap_err().contains("too slowly"));
        assert!(choose(&[]).unwrap_err().contains("downloaded"));
    }

    #[test]
    fn the_probe_runs_again_when_the_pc_or_its_models_change() {
        let e = EngineFile {
            fingerprint: "AMD 17 GB · 66 GB RAM · Ollama 0.40.16 · ROCm".into(),
            measures: vec![m("a", 1, 30.0)],
            ..Default::default()
        };
        let same = "AMD 17 GB · 66 GB RAM · Ollama 0.40.16 · ROCm";
        assert!(!needs_probe(Some(&e), same, &["a".into()]));
        assert!(needs_probe(None, same, &[]));
        assert!(needs_probe(
            Some(&e),
            "AMD 17 GB · 66 GB RAM · Ollama 0.41.0 · ROCm",
            &[]
        ));
        assert!(needs_probe(
            Some(&e),
            "AMD 17 GB · 66 GB RAM · Ollama 0.40.16 · Vulkan",
            &[]
        ));
        assert!(needs_probe(Some(&e), same, &["a".into(), "b".into()]));
        assert_eq!(
            fingerprint(&hw(16, 62), "0.40.16", "ROCm"),
            "AMD 16 GB · 62 GB RAM · Ollama 0.40.16 · ROCm"
        );
    }

    #[test]
    fn the_backend_is_read_from_ollamas_log() {
        assert_eq!(backend_in("load_backend: ROCm0 … offloaded 41/41"), "ROCm");
        assert_eq!(
            backend_in("ROCm0 earlier\nthen library=Vulkan later"),
            "Vulkan"
        );
        assert_eq!(backend_in("nothing"), "");
    }

    #[test]
    fn a_fresh_pc_answers_its_first_chat_without_a_probe_result() {
        let hw = Hardware {
            gpu: Some(Gpu {
                vendor: "AMD".into(),
                vram: 17 * GB,
            }),
            ram: 64 * GB,
            threads: 32,
            disk_free: Some(500 * GB),
        };
        // Nothing installed: nothing to answer with.
        assert!(default_pick(&hw, &[], "").is_none());
        // Two chat models installed, no test result: the smallest answers.
        let (c, _) = candidates(&hw, Role::Chat).remove(0);
        let all: Vec<String> = candidates(&hw, Role::Chat)
            .iter()
            .map(|(m, _)| m.name.to_string())
            .collect();
        let small = candidates(&hw, Role::Chat)
            .into_iter()
            .min_by_key(|(m, _)| m.bytes)
            .unwrap()
            .0;
        let p = default_pick(&hw, &all, "ROCm").expect("a model answers");
        assert_eq!(p.model, small.name);
        assert_eq!(p.backend, "ROCm");
        let only = default_pick(&hw, &[c.name.to_string()], "").unwrap();
        assert_eq!(only.model, c.name);
    }

    #[test]
    fn the_test_never_starts_while_an_answer_is_being_written() {
        let t = Instant::now();
        let late = t + Duration::from_secs(3600);
        assert_eq!(gate(true, true, None, t), Gate::Wait);
        assert_eq!(gate(false, true, Some(t), late), Gate::Wait);
        assert_eq!(gate(true, false, None, t), Gate::Run);
        assert_eq!(gate(false, false, Some(t), t), Gate::Wait);
        assert_eq!(gate(false, false, Some(t), late), Gate::RunOne);
    }

    #[test]
    fn a_run_anyway_measures_only_the_one_model() {
        let base = crate::ollama::fake::start(|path, _| {
            if path == "/api/ps" {
                return vec![r#"{"models":[]}"#.into()];
            }
            if path == "/api/version" {
                return vec![r#"{"version":"0.40.16"}"#.into()];
            }
            vec![
                r#"{"message":{"role":"assistant","content":"Hi"},"done":false}"#.into(),
                r#"{"message":{"role":"assistant","content":""},"done":true,"eval_count":600,"eval_duration":10000000000}"#.into(),
            ]
        });
        let installed = || {
            vec![
                "huihui_ai/Qwen3.6-abliterated:35b-a3b".to_string(),
                "huihui_ai/gpt-oss-abliterated:20b".to_string(),
            ]
        };
        let hands = Hands {
            base,
            installed: &installed,
            download: &|_| Err("no downloads".into()),
            remove: &|_| true,
            keep: vec![],
        };
        let hw = Hardware {
            gpu: Some(Gpu {
                vendor: "AMD".into(),
                vram: 17 * GB,
            }),
            ram: 64 * GB,
            threads: 32,
            disk_free: Some(500 * GB),
        };
        let e = run_with(
            &hw,
            &hands,
            "ROCm",
            Some("huihui_ai/gpt-oss-abliterated:20b"),
        );
        assert_eq!(e.measures.len(), 1);
        assert_eq!(e.measures[0].model, "huihui_ai/gpt-oss-abliterated:20b");
    }

    #[test]
    fn a_busy_pc_makes_the_test_wait_five_minutes_at_most() {
        let t = Instant::now();
        assert!(!waited_enough(t, t + Duration::from_secs(299)));
        assert!(waited_enough(t, t + Duration::from_secs(300)));
    }

    #[test]
    fn a_probe_measures_picks_and_keeps_the_runner_up() {
        // A stand-in Ollama: the Qwen is slow to start, gpt-oss is quick.
        let base = crate::ollama::fake::start(|path, body| {
            if path == "/api/ps" {
                return vec![
                    r#"{"models":[{"name":"huihui_ai/gpt-oss-abliterated:20b","size":100,"size_vram":100},{"name":"huihui_ai/Qwen3.6-abliterated:35b-a3b","size":100,"size_vram":60}]}"#.into(),
                ];
            }
            if path == "/api/version" {
                return vec![r#"{"version":"0.40.16"}"#.into()];
            }
            let qwen = body.contains("Qwen");
            let mut out = vec![];
            if qwen {
                // Over 3 s before the first word is too slow; make it 3.1 s in "tokens" terms instead.
                out.push(r#"{"message":{"role":"assistant","content":"Hi"},"done":false}"#.into());
                out.push(r#"{"message":{"role":"assistant","content":""},"done":true,"eval_count":150,"eval_duration":10000000000}"#.into());
            } else {
                out.push(
                    r#"{"message":{"role":"assistant","content":"","thinking":"hm"},"done":false}"#
                        .into(),
                );
                out.push(r#"{"message":{"role":"assistant","content":"Hi"},"done":false}"#.into());
                out.push(r#"{"message":{"role":"assistant","content":""},"done":true,"eval_count":600,"eval_duration":10000000000}"#.into());
            }
            out
        });
        let removed = std::sync::Mutex::new(vec![]);
        let remove = |m: &str| {
            removed.lock().unwrap().push(m.to_string());
            true
        };
        let installed = || {
            vec![
                "huihui_ai/Qwen3.6-abliterated:35b-a3b".to_string(),
                "huihui_ai/gpt-oss-abliterated:20b".to_string(),
                "qwen3.6:35b-a3b-coding".to_string(),
            ]
        };
        let hands = Hands {
            base,
            installed: &installed,
            download: &|_| Err("no downloads in a check".into()),
            remove: &remove,
            keep: vec!["qwen3.6:35b-a3b-coding".into()],
        };
        let hw = Hardware {
            gpu: Some(Gpu {
                vendor: "AMD".into(),
                vram: 17 * GB,
            }),
            ram: 64 * GB,
            threads: 32,
            disk_free: Some(500 * GB),
        };
        let e = run(&hw, &hands, "ROCm");
        assert_eq!(e.measures.len(), 2, "{e:?}");
        // Qwen (best-ranked) writes only 15 tokens a second: gpt-oss is the
        // pick, and Qwen, the runner-up, stays: nothing is removed.
        let p = e.chat.clone().unwrap();
        assert_eq!(p.model, "huihui_ai/gpt-oss-abliterated:20b");
        assert_eq!(p.tokens_per_s, 60.0);
        assert_eq!(p.backend, "ROCm");
        assert!(removed.lock().unwrap().is_empty());
        assert_eq!(
            e.fingerprint,
            "AMD 17 GB · 64 GB RAM · Ollama 0.40.16 · ROCm"
        );
        assert!(e.why.is_none());
        // The measures: the split model sat 60% on the card.
        let q = probe(&hands.base, "huihui_ai/Qwen3.6-abliterated:35b-a3b", "ROCm").unwrap();
        assert!((q.on_card - 0.6).abs() < 0.01);
        assert_eq!(q.tokens_per_s, 15.0);
    }

    #[test]
    fn a_new_pc_downloads_the_smallest_model_that_fits_first() {
        let base = crate::ollama::fake::start(|path, _| {
            match path {
            "/api/version" => vec![r#"{"version":"0.40.16"}"#.into()],
            "/api/ps" => vec![r#"{"models":[]}"#.into()],
            _ => vec![r#"{"message":{"content":"Hi"},"done":true,"eval_count":30,"eval_duration":1000000000}"#.into()],
        }
        });
        let got = std::sync::Mutex::new(vec![]);
        let download = |m: &str| {
            got.lock().unwrap().push(m.to_string());
            Err("offline".to_string())
        };
        let hands = Hands {
            base,
            installed: &Vec::new,
            download: &download,
            remove: &|_| true,
            keep: vec![],
        };
        let e = run(&hw(16, 64), &hands, "");
        assert_eq!(
            *got.lock().unwrap(),
            vec![crate::models::SMALL_CHAT.to_string()]
        );
        assert!(e.chat.is_none());
        assert!(
            e.why
                .as_deref()
                .unwrap_or("")
                .starts_with("No downloaded model"),
            "got: {:?}",
            e.why
        );
        // No room on the disk: nothing is downloaded, and it says so.
        got.lock().unwrap().clear();
        let mut full = hw(16, 64);
        full.disk_free = Some(5 * GB);
        let e = run(&full, &hands, "");
        assert!(got.lock().unwrap().is_empty());
        assert!(e.why.is_some());
    }

    #[test]
    fn the_words_say_where_and_how_fast_without_model_names() {
        let e = EngineFile {
            chat: Some(Pick {
                model: "x".into(),
                title: "X".into(),
                fit: Fit::Card,
                tokens_per_s: 53.0,
                first_token_ms: 400,
                cold_ms: 5000,
                backend: "ROCm".into(),
            }),
            ..Default::default()
        };
        assert_eq!(
            words(Some(&e)),
            "Chats run on your PC's graphics card · about 40 words a second"
        );
        let none = EngineFile {
            why: Some(
                "No downloaded model runs fully on this PC's memory. \
                       You can still use a smaller model or add more RAM."
                    .into(),
            ),
            ..Default::default()
        };
        assert!(words(Some(&none)).starts_with("Chats run on your phone: "));
    }
}
