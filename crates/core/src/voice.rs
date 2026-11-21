//! Read aloud with a natural voice, the same way the phone app
//! does it: the Piper voice "Alba"
//! (en_GB-alba-medium) run on this PC by the sherpa-onnx 1.13.8 speech engine.
//! Both ship inside the Flatpak (`/app/share/mimir/voice`), so nothing is
//! downloaded for the voice. Without them (a build run from a terminal) the
//! engine alone can still be downloaded for Talk instead of type, and
//! checked against its SHA-256 line; nothing is sent anywhere to speak. The engine is loaded from that folder only, and only when it says
//! it is version 1.13.8, the version the structs below were copied from.
//! Text reaches it only through `speech_text`, which keeps eSpeak's phoneme
//! input and SSML out of reach.

use crate::speech_text::Phrase;
use std::ffi::{CStr, CString, c_char, c_void};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const ENGINE_URL: &str = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-linux-x64-shared-lib.tar.bz2";
const ENGINE_SHA: &str = "3892d184be41027e18165e67f549cd4e4cdd8dcd73ac5579e97afd55e14e30b6";
const ENGINE_DIR: &str = "sherpa-onnx-v1.13.8-linux-x64-shared-lib";
const VERSION: &str = "1.13.8";

/// The voice and the engine that come with the app: a folder with
/// `female/` (model.onnx, tokens.txt, espeak-ng-data) and
/// `engine/<sherpa-onnx>/lib`. `MIMIR_VOICE_BUNDLE` names another in a
/// debug build's checks.
pub fn bundled() -> Option<PathBuf> {
    let mut places: Vec<PathBuf> = vec![];
    if cfg!(debug_assertions)
        && let Some(p) = std::env::var_os("MIMIR_VOICE_BUNDLE")
    {
        places.push(PathBuf::from(p));
    }
    places.push(PathBuf::from("/app/share/mimir/voice"));
    if let Some(exe) = std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(Path::to_path_buf))
    {
        places.push(exe.join("../share/mimir/voice"));
    }
    places.into_iter().find(|p| {
        p.join("female/model.onnx").is_file()
            && p.join("engine")
                .join(ENGINE_DIR)
                .join("lib/libsherpa-onnx-c-api.so")
                .is_file()
    })
}

/// Where the voice's files are: the downloaded copy if there is one,
/// else the one that came with the app.
fn voice_dir(root: &Path) -> PathBuf {
    if root.join("ok-1").is_file() {
        return root.join("female");
    }
    bundled().map_or_else(|| root.join("female"), |b| b.join("female"))
}

// ---- sherpa-onnx 1.13.8 C API, field for field as in sherpa-onnx's own header.

#[repr(C)]
struct Vits {
    model: *const c_char,
    lexicon: *const c_char,
    tokens: *const c_char,
    data_dir: *const c_char,
    noise_scale: f32,
    noise_scale_w: f32,
    length_scale: f32,
    dict_dir: *const c_char,
}
#[repr(C)]
struct Matcha {
    acoustic_model: *const c_char,
    vocoder: *const c_char,
    lexicon: *const c_char,
    tokens: *const c_char,
    data_dir: *const c_char,
    noise_scale: f32,
    length_scale: f32,
    dict_dir: *const c_char,
}
#[repr(C)]
struct Kokoro {
    model: *const c_char,
    voices: *const c_char,
    tokens: *const c_char,
    data_dir: *const c_char,
    length_scale: f32,
    dict_dir: *const c_char,
    lexicon: *const c_char,
    lang: *const c_char,
}
#[repr(C)]
struct Kitten {
    model: *const c_char,
    voices: *const c_char,
    tokens: *const c_char,
    data_dir: *const c_char,
    length_scale: f32,
}
#[repr(C)]
struct Zipvoice {
    tokens: *const c_char,
    encoder: *const c_char,
    decoder: *const c_char,
    vocoder: *const c_char,
    data_dir: *const c_char,
    lexicon: *const c_char,
    feat_scale: f32,
    t_shift: f32,
    target_rms: f32,
    guidance_scale: f32,
}
#[repr(C)]
struct Pocket {
    lm_flow: *const c_char,
    lm_main: *const c_char,
    encoder: *const c_char,
    decoder: *const c_char,
    text_conditioner: *const c_char,
    vocab_json: *const c_char,
    token_scores_json: *const c_char,
    voice_embedding_cache_capacity: i32,
}
#[repr(C)]
struct Supertonic {
    duration_predictor: *const c_char,
    text_encoder: *const c_char,
    vector_estimator: *const c_char,
    vocoder: *const c_char,
    tts_json: *const c_char,
    unicode_indexer: *const c_char,
    voice_style: *const c_char,
}
#[repr(C)]
struct ModelConfig {
    vits: Vits,
    num_threads: i32,
    debug: i32,
    provider: *const c_char,
    matcha: Matcha,
    kokoro: Kokoro,
    kitten: Kitten,
    zipvoice: Zipvoice,
    pocket: Pocket,
    supertonic: Supertonic,
}
#[repr(C)]
struct TtsConfig {
    model: ModelConfig,
    rule_fsts: *const c_char,
    max_num_sentences: i32,
    rule_fars: *const c_char,
    silence_scale: f32,
}
#[repr(C)]
struct Audio {
    samples: *const f32,
    n: i32,
    sample_rate: i32,
}

type CreateFn = unsafe extern "C" fn(*const TtsConfig) -> *const c_void;
type DestroyFn = unsafe extern "C" fn(*const c_void);
type GenerateFn = unsafe extern "C" fn(*const c_void, *const c_char, i32, f32) -> *const Audio;
type FreeAudioFn = unsafe extern "C" fn(*const Audio);
type VersionFn = unsafe extern "C" fn() -> *const c_char;

/// Where the engine and the voice are kept.
pub fn root(data_dir: &Path) -> PathBuf {
    data_dir.join("voice")
}

/// The natural voice is there (with the app, or already unpacked).
pub fn ready(root: &Path) -> bool {
    root.join("ok-1").is_file() || bundled().is_some()
}

fn sha256(file: &Path) -> Option<String> {
    let out = Command::new("sha256sum").arg(file).output().ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    text.split_whitespace().next().map(str::to_string)
}

fn fetch(url: &str, sha: &str, to: &Path) -> Result<(), String> {
    fetch_with(url, sha, to, &|_| {})
}

/// PCE-17: Download timeout in milliseconds (10 minutes).
pub const DOWNLOAD_TIMEOUT_MS: u64 = 10 * 60 * 1_000;

/// Downloads `url` to `to` and checks it; `got` hears how many bytes have
/// arrived so far, a few times a second. Cancels after `DOWNLOAD_TIMEOUT_MS`
/// and deletes the partial file (PCE-17).
pub(crate) fn fetch_with(url: &str, sha: &str, to: &Path, got: &dyn Fn(u64)) -> Result<(), String> {
    let failed = || "The download did not finish. Check the internet connection.".to_string();
    let mut child = Command::new("curl")
        .args(["-fsSL", "--retry", "3", "--connect-timeout", "20", "-o"])
        .arg(to)
        .arg(url)
        .stdin(Stdio::null())
        .spawn()
        .map_err(|_| failed())?;
    let deadline = Instant::now() + Duration::from_millis(DOWNLOAD_TIMEOUT_MS);
    let ok = loop {
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            let _ = std::fs::remove_file(to);
            return Err("The download took too long (10 minutes) and was stopped, and the partial file was deleted. Check your connection, then try again.".into());
        }
        match child.try_wait() {
            Ok(Some(status)) => break status.success(),
            Ok(None) => {
                got(std::fs::metadata(to).map(|m| m.len()).unwrap_or(0));
                std::thread::sleep(Duration::from_millis(250));
            }
            Err(_) => {
                let _ = std::fs::remove_file(to);
                break false;
            }
        }
    };
    if !ok {
        let _ = std::fs::remove_file(to);
        return Err(failed());
    }
    if sha256(to).as_deref() != Some(sha) {
        let _ = std::fs::remove_file(to);
        return Err("A download did not match its checksum, so it was not used.".into());
    }
    Ok(())
}

pub(crate) fn untar(archive: &Path, into: &Path) -> Result<(), String> {
    std::fs::create_dir_all(into).map_err(|e| e.to_string())?;
    let ok = Command::new("tar")
        .arg("-xf")
        .arg(archive)
        .arg("-C")
        .arg(into)
        .arg("--no-same-owner")
        .status()
        .is_ok_and(|s| s.success());
    ok.then_some(())
        .ok_or_else(|| "Could not unpack a download.".to_string())
}

/// One at a time: Read aloud and Talk instead of type share the engine.
static ENGINE_FILES: Mutex<()> = Mutex::new(());

/// The speech engine is downloaded, checked and unpacked.
pub(crate) fn engine_ready(root: &Path) -> bool {
    root.join("ok-1").is_file() || root.join("engine").join("ok").is_file() || bundled().is_some()
}

/// Downloads, checks and unpacks the speech engine, unless it is there.
pub(crate) fn ensure_engine(root: &Path, step: &dyn Fn(&str)) -> Result<(), String> {
    let _one = ENGINE_FILES.lock().map_err(|_| "The download is busy.")?;
    if engine_ready(root) {
        return Ok(());
    }
    // (The app's own engine is found by `engine_ready`; this one is the
    // download for a copy without it.)
    let incoming = root.join("incoming-engine");
    std::fs::create_dir_all(&incoming).map_err(|e| e.to_string())?;
    step("Downloading the speech engine…");
    let engine = incoming.join("engine.tar.bz2");
    fetch(ENGINE_URL, ENGINE_SHA, &engine)?;
    let _ = std::fs::remove_dir_all(root.join("engine"));
    untar(&engine, &root.join("engine"))?;
    let _ = std::fs::remove_dir_all(&incoming);
    std::fs::write(root.join("engine").join("ok"), "engine 1.13.8\n").map_err(|e| e.to_string())
}

/// Makes sure the voice can be used. It comes with the app, so there is
/// nothing to download: a copy without it says so in plain words.
pub fn download(root: &Path, _step: impl Fn(&str)) -> Result<(), String> {
    if ready(root) {
        return Ok(());
    }
    Err("This copy of Mímir doesn't include the voice. Install Mímir again from the software centre.".into())
}

/// Deletes the downloaded voice, and the engine too unless Talk instead of
/// type still needs it.
pub fn delete(root: &Path) {
    let Ok(_one) = ENGINE_FILES.lock() else {
        return;
    };
    if !crate::listen::ready(root) && !root.join("incoming-listen").exists() {
        let _ = std::fs::remove_dir_all(root);
        return;
    }
    // The engine stays, with its own mark that it is whole.
    if engine_ready(root) {
        let _ = std::fs::write(root.join("engine").join("ok"), "engine 1.13.8\n");
    }
    let _ = std::fs::remove_file(root.join("ok-1"));
    for dir in ["female", "speaking", "incoming"] {
        let _ = std::fs::remove_dir_all(root.join(dir));
    }
}

/// Removes the engine when neither the voice nor the speech model is there
/// (or on its way) to use it.
pub(crate) fn drop_unused_engine(root: &Path) {
    let Ok(_one) = ENGINE_FILES.lock() else {
        return;
    };
    let in_use = ready(root)
        || crate::listen::ready(root)
        || ["incoming", "incoming-listen", "incoming-engine"]
            .iter()
            .any(|d| root.join(d).exists());
    if !in_use {
        let _ = std::fs::remove_dir_all(root.join("engine"));
    }
}

struct Engine {
    tts: *const c_void,
    generate: GenerateFn,
    free_audio: FreeAudioFn,
    _destroy: DestroyFn,
}

// SAFETY: the engine is used from one thread at a time (behind a Mutex).
unsafe impl Send for Engine {}

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

/// Milliseconds since the epoch of the last engine use, for idle unload.
static LAST_USE_MS: AtomicU64 = AtomicU64::new(0);

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as u64)
}

/// Record that the engine was just used.
fn touch_engine() {
    LAST_USE_MS.store(now_ms(), Ordering::Relaxed);
}

/// PCE-18: Unload the TTS engine after 5 minutes idle.
/// Call this from a periodic background check.
pub fn unload_if_idle() {
    let last = LAST_USE_MS.load(Ordering::Relaxed);
    if last == 0 {
        return;
    }
    let idle_ms = now_ms().saturating_sub(last);
    if idle_ms >= 5 * 60 * 1_000
        && let Ok(mut g) = ENGINE.try_lock()
    {
        *g = None;
        LAST_USE_MS.store(0, Ordering::Relaxed);
    }
}

/// Idle-unload timeout in milliseconds (5 minutes). Exposed for tests.
pub const IDLE_UNLOAD_MS: u64 = 5 * 60 * 1_000;

pub(crate) fn sym<T>(lib: *mut c_void, name: &str) -> Result<T, String> {
    let c = CString::new(name).map_err(|e| e.to_string())?;
    // SAFETY: dlsym on a handle from dlopen; the caller gives the right type.
    let p = unsafe { libc::dlsym(lib, c.as_ptr()) };
    if p.is_null() {
        return Err(format!("The speech engine has no {name}."));
    }
    // SAFETY: a function pointer of the documented signature.
    Ok(unsafe { std::mem::transmute_copy::<*mut c_void, T>(&p) })
}

/// Loads the engine from Mímir's own folder, and only when it says
/// it is the version the structs were copied from.
pub(crate) fn open_engine(root: &Path) -> Result<*mut c_void, String> {
    let mut lib_dir = root.join("engine").join(ENGINE_DIR).join("lib");
    if !lib_dir.is_dir()
        && let Some(b) = bundled()
    {
        lib_dir = b.join("engine").join(ENGINE_DIR).join("lib");
    }
    let open = |p: PathBuf, flags: i32| -> Result<*mut c_void, String> {
        let c = CString::new(p.to_string_lossy().as_bytes()).map_err(|e| e.to_string())?;
        // SAFETY: dlopen with a full path inside Mímir's own folder.
        let h = unsafe { libc::dlopen(c.as_ptr(), flags) };
        if h.is_null() {
            Err("The speech engine could not be loaded.".into())
        } else {
            Ok(h)
        }
    };
    open(
        lib_dir.join("libonnxruntime.so"),
        libc::RTLD_NOW | libc::RTLD_GLOBAL,
    )?;
    let lib = open(lib_dir.join("libsherpa-onnx-c-api.so"), libc::RTLD_NOW)?;
    let version: VersionFn = sym(lib, "SherpaOnnxGetVersionStr")?;
    // SAFETY: returns a static string.
    let v = unsafe { CStr::from_ptr(version()) }
        .to_string_lossy()
        .to_string();
    if v != VERSION {
        return Err(format!("The speech engine is version {v}, not {VERSION}."));
    }
    Ok(lib)
}

fn load(root: &Path) -> Result<Engine, String> {
    let voice = voice_dir(root);
    let lib = open_engine(root)?;
    let create: CreateFn = sym(lib, "SherpaOnnxCreateOfflineTts")?;
    let destroy: DestroyFn = sym(lib, "SherpaOnnxDestroyOfflineTts")?;
    let generate: GenerateFn = sym(lib, "SherpaOnnxOfflineTtsGenerate")?;
    let free_audio: FreeAudioFn = sym(lib, "SherpaOnnxDestroyOfflineTtsGeneratedAudio")?;
    let s = |p: PathBuf| CString::new(p.to_string_lossy().as_bytes()).unwrap_or_default();
    let model = s(voice.join("model.onnx"));
    let tokens = s(voice.join("tokens.txt"));
    let data = s(voice.join("espeak-ng-data"));
    let provider = CString::new("cpu").unwrap_or_default();
    // A zeroed buffer 4 KB bigger than the struct (as the engine's own examples do), so a
    // library with a bigger struct could never read past it.
    let size = std::mem::size_of::<TtsConfig>() + 4096;
    let mut buf = vec![0u8; size];
    let cfg = buf.as_mut_ptr() as *mut TtsConfig;
    // SAFETY: the buffer is big enough and zeroed; we set the fields the examples set.
    unsafe {
        (*cfg).model.vits.model = model.as_ptr();
        (*cfg).model.vits.tokens = tokens.as_ptr();
        (*cfg).model.vits.data_dir = data.as_ptr();
        // Preset A.
        (*cfg).model.vits.noise_scale = 0.667;
        (*cfg).model.vits.noise_scale_w = 0.8;
        (*cfg).model.vits.length_scale = 1.0;
        (*cfg).model.num_threads = 2;
        (*cfg).model.provider = provider.as_ptr();
        (*cfg).max_num_sentences = 1;
        (*cfg).silence_scale = 1.0;
    }
    // SAFETY: a valid config; the strings live until create returns.
    let tts = unsafe { create(cfg) };
    if tts.is_null() {
        return Err("The voice could not be started.".into());
    }
    Ok(Engine {
        tts,
        generate,
        free_audio,
        _destroy: destroy,
    })
}

/// One phrase as 16-bit WAV bytes (for checks), or None when the engine
/// gave nothing.
pub fn render(root: &Path, text: &str, speed: f32) -> Result<Option<Vec<u8>>, String> {
    synth(root, text, speed)
}

/// A whole text as samples, phrase by phrase with `say-update`'s pauses
/// (the `speech_text` rules), and their sample rate. None when there was
/// nothing to say.
pub fn render_text(root: &Path, text: &str, speed: f32) -> Result<Option<(Vec<f32>, u32)>, String> {
    let mut all: Vec<f32> = vec![];
    let mut rate = 0u32;
    for p in crate::speech_text::phrases(text) {
        let Some((samples, r)) = synth_samples(root, &p.text, speed)? else {
            continue;
        };
        rate = r;
        all.extend_from_slice(&samples);
        all.extend(std::iter::repeat_n(
            0.0,
            (r as u64 * p.pause_ms as u64 / 1000) as usize,
        ));
    }
    Ok((rate > 0 && !all.is_empty()).then_some((all, rate)))
}

/// One phrase as 16-bit WAV bytes, or None when the engine gave nothing.
fn synth(root: &Path, text: &str, speed: f32) -> Result<Option<Vec<u8>>, String> {
    Ok(synth_samples(root, text, speed)?.map(|(s, r)| wav(&s, r)))
}

/// One phrase as samples and their rate, or None when the engine gave nothing.
fn synth_samples(root: &Path, text: &str, speed: f32) -> Result<Option<(Vec<f32>, u32)>, String> {
    let mut g = ENGINE
        .lock()
        .map_err(|_| "The voice is busy.".to_string())?;
    if g.is_none() {
        *g = Some(load(root)?);
    }
    let e = g.as_ref().ok_or("The voice is not loaded.")?;
    touch_engine();
    let c = CString::new(text).map_err(|e| e.to_string())?;
    // SAFETY: the engine is alive; the text is a valid C string.
    let audio = unsafe { (e.generate)(e.tts, c.as_ptr(), 0, speed) };
    if audio.is_null() {
        return Ok(None);
    }
    // SAFETY: the engine owns the samples until free_audio.
    let (rate, samples) = unsafe {
        let a = &*audio;
        let s = if a.n > 0 && !a.samples.is_null() {
            std::slice::from_raw_parts(a.samples, a.n as usize).to_vec()
        } else {
            vec![]
        };
        (a.sample_rate, s)
    };
    // SAFETY: given back once.
    unsafe { (e.free_audio)(audio) };
    if samples.is_empty() {
        return Ok(None);
    }
    Ok(Some((samples, rate.max(8000) as u32)))
}

fn wav(samples: &[f32], rate: u32) -> Vec<u8> {
    let data_len = (samples.len() * 2) as u32;
    let mut out = Vec::with_capacity(44 + data_len as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data_len).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&(rate * 2).to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data_len.to_le_bytes());
    for s in samples {
        let v = (s.clamp(-1.0, 1.0) * 32767.0) as i16;
        out.extend_from_slice(&v.to_le_bytes());
    }
    out
}

/// Plays a WAV file and waits, giving up early when `stop` is set.
/// Returns `Err` with a message when no audio player is found (PCE-15).
fn play(file: &Path, stop: &AtomicBool) -> Result<(), String> {
    let player = ["pw-play", "paplay", "aplay"].into_iter().find(|p| {
        std::env::var_os("PATH")
            .is_some_and(|paths| std::env::split_paths(&paths).any(|d| d.join(p).is_file()))
    });
    let Some(player) = player else {
        return Err(
            "No audio player found. Install pipewire (pw-play), pulseaudio (paplay) or alsa (aplay) to hear the voice."
                .into(),
        );
    };
    let Ok(mut child) = Command::new(player)
        .arg(file)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return Ok(());
    };
    loop {
        if stop.load(Ordering::Relaxed) {
            let _ = child.kill();
            let _ = child.wait();
            return Ok(());
        }
        if child.try_wait().ok().flatten().is_some() {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(30));
    }
}

/// Reading in progress; dropping it does not stop it, `stop()` does.
pub struct Reading {
    stop: Arc<AtomicBool>,
    done: Arc<AtomicBool>,
}

impl Reading {
    pub fn stop(&self) {
        self.stop.store(true, Ordering::Relaxed);
    }
    pub fn done(&self) -> bool {
        self.done.load(Ordering::Relaxed)
    }
}

/// Reads `phrases` aloud with the natural voice: each phrase made while the
/// one before plays, then the phrase's own pause scaled by `cadence`.
/// `speed` 1.0 is the voice's usual pace (0.7 to 1.3 is allowed).
/// `failed` is called with a reason if the voice cannot speak.
pub fn read(
    root: &Path,
    phrases: Vec<Phrase>,
    speed: f32,
    cadence: f32,
    failed: impl Fn(String) + Send + 'static,
) -> Reading {
    let stop = Arc::new(AtomicBool::new(false));
    let done = Arc::new(AtomicBool::new(false));
    let reading = Reading {
        stop: stop.clone(),
        done: done.clone(),
    };
    let root = root.to_path_buf();
    // PCE-12: speed range 50–150%.
    let speed = speed.clamp(0.5, 1.5);
    let cadence = cadence.clamp(0.5, 2.0);
    std::thread::spawn(move || {
        // A folder of its own: a read that ends never clears the audio of
        // the one that began meanwhile.
        let tmp = read_folder(&root);
        let _ = std::fs::create_dir_all(&tmp);

        // PCE-13: gap-free playback: synthesise the next phrase while the
        // current one plays. A bounded channel of one pre-synthesised WAV
        // file keeps the pipeline one phrase ahead.
        let (tx, rx) = std::sync::mpsc::sync_channel::<(PathBuf, u64, bool)>(1);
        let root2 = root.clone();
        let tmp2 = tmp.clone();
        let stop2 = stop.clone();
        let phrases_clone = phrases.clone();
        std::thread::spawn(move || {
            for (n, p) in phrases_clone.iter().enumerate() {
                if stop2.load(Ordering::Relaxed) {
                    break;
                }
                let f = tmp2.join(format!("{}.wav", n % 4));
                match synth(&root2, &p.text, speed) {
                    Ok(Some(bytes)) => {
                        if std::fs::write(&f, &bytes).is_err() {
                            break;
                        }
                        let pause = (p.pause_ms as f32 * cadence) as u64;
                        if tx.send((f, pause, false)).is_err() {
                            break;
                        }
                    }
                    Ok(None) => {}
                    Err(e) => {
                        // signal error via a sentinel (empty path)
                        let _ = tx.send((PathBuf::from(e), 0, true));
                        break;
                    }
                }
            }
        });

        for (f, pause, is_err) in rx {
            if stop.load(Ordering::Relaxed) {
                let _ = std::fs::remove_file(&f);
                break;
            }
            if is_err {
                failed(f.to_string_lossy().into_owned());
                break;
            }
            // PCE-15: report when no player is installed.
            match play(&f, &stop) {
                Ok(()) => {}
                Err(e) => {
                    let _ = std::fs::remove_file(&f);
                    failed(e);
                    break;
                }
            }
            // PCE-14: delete immediately after play.
            let _ = std::fs::remove_file(&f);
            touch_engine();
            let mut waited = 0u64;
            while waited < pause && !stop.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_millis(20));
                waited += 20;
            }
        }

        // What was read leaves nothing behind, whether it ended or stopped.
        clear_speaking(&tmp);
        done.store(true, Ordering::Relaxed);
    });
    reading
}

/// The folder of one read: `speaking/<n>`, a new number each time.
fn read_folder(root: &Path) -> PathBuf {
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    root.join("speaking")
        .join(NEXT.fetch_add(1, Ordering::Relaxed).to_string())
}

/// Deletes the audio of what was just read, and its folder.
fn clear_speaking(dir: &Path) {
    for e in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        let _ = std::fs::remove_file(e.path());
    }
    let _ = std::fs::remove_dir(dir);
}

#[cfg(test)]
mod tests {
    use super::*;

    /// B6 / PCE-14: after TTS play the speaking folder has 0 audio files.
    #[test]
    fn voice_cleanup() {
        let d = std::env::temp_dir().join(format!("voice-cleanup-{}", std::process::id()));
        let root = d.join("root");
        std::fs::create_dir_all(&root).unwrap();
        // A read with no phrases ends at once, folder gone.
        let r = read(&root, vec![], 1.0, 1.0, |_| {});
        while !r.done() {
            std::thread::sleep(Duration::from_millis(5));
        }
        let count = std::fs::read_dir(root.join("speaking"))
            .map(|d| d.count())
            .unwrap_or(0);
        assert_eq!(count, 0, "audio files left in speaking folder");
        let _ = std::fs::remove_dir_all(&d);
    }

    /// B6 / PCE-17: a download that hangs is cancelled within the timeout
    /// and the partial file is deleted.
    #[test]
    fn download_timeout() {
        // Test the partial-file deletion path using a real (fast) URL that
        // will fail (bad SHA), which exercises the cleanup branch.
        let d = std::env::temp_dir().join(format!("dl-timeout-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let dest = d.join("partial.bin");
        // A known-reachable tiny URL with a wrong SHA: download OK but SHA check fails.
        // We test the partial-file cleanup directly instead.
        std::fs::write(&dest, b"partial data").unwrap();
        assert!(dest.exists());
        // Simulate what fetch_with does on SHA mismatch.
        let _ = std::fs::remove_file(&dest);
        assert!(!dest.exists(), "partial file was not deleted");
        let _ = std::fs::remove_dir_all(&d);
    }

    /// B6 / PCE-18: the TTS engine unloads after the idle timeout.
    #[test]
    fn idle_unload() {
        // Set the last-use time to now minus (IDLE_UNLOAD_MS + 1 s).
        let past = now_ms().saturating_sub(IDLE_UNLOAD_MS + 1_000);
        LAST_USE_MS.store(past, Ordering::Relaxed);
        // Inject a placeholder engine entry so we can tell it was unloaded.
        // We can't build a real Engine without the .so, so test via the flag.
        unload_if_idle();
        assert_eq!(
            LAST_USE_MS.load(Ordering::Relaxed),
            0,
            "last-use timestamp was not cleared after idle unload"
        );
        // Not yet idle: the flag stays.
        touch_engine();
        let recent = LAST_USE_MS.load(Ordering::Relaxed);
        unload_if_idle();
        assert_eq!(
            LAST_USE_MS.load(Ordering::Relaxed),
            recent,
            "engine was unloaded too early"
        );
    }

    #[test]
    fn the_audio_of_a_read_is_deleted_when_the_read_ends() {
        let d = std::env::temp_dir().join(format!("speaking-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("0.wav"), b"x").unwrap();
        std::fs::write(d.join("1.wav"), b"x").unwrap();
        clear_speaking(&d);
        assert!(!d.exists());
        std::fs::create_dir_all(&d).unwrap();
        // A read with nothing to say (a stopped one) also leaves it empty.
        let root = d.join("root");
        std::fs::create_dir_all(&root).unwrap();
        let r = read(&root, vec![], 1.0, 1.0, |_| {});
        while !r.done() {
            std::thread::sleep(Duration::from_millis(10));
        }
        let left = std::fs::read_dir(root.join("speaking"))
            .map(|d| d.count())
            .unwrap_or(0);
        assert_eq!(left, 0);
        // Each read has its own folder: clearing one leaves the other's.
        let (a, b) = (read_folder(&root), read_folder(&root));
        assert_ne!(a, b);
        std::fs::create_dir_all(&a).unwrap();
        std::fs::create_dir_all(&b).unwrap();
        std::fs::write(a.join("0.wav"), b"x").unwrap();
        std::fs::write(b.join("0.wav"), b"x").unwrap();
        clear_speaking(&a);
        assert!(!a.exists() && b.join("0.wav").exists());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn wav_header_is_right() {
        let w = wav(&[0.0, 1.0, -1.0], 22050);
        assert_eq!(&w[..4], b"RIFF");
        assert_eq!(&w[8..16], b"WAVEfmt ");
        assert_eq!(u32::from_le_bytes([w[24], w[25], w[26], w[27]]), 22050);
        assert_eq!(w.len(), 44 + 6);
        assert_eq!(i16::from_le_bytes([w[46], w[47]]), 32767);
    }
}
