//! Talk instead of type: the microphone is recorded to a file with
//! PipeWire's `pw-record` (or `arecord`), and the speech is turned into
//! text on this PC by the same sherpa-onnx 1.13.8 engine Read aloud uses,
//! with OpenAI's Whisper "base.en" model. The model is downloaded once and
//! checked against its SHA-256; nothing that is said leaves this PC.

use crate::voice;
use std::ffi::{CStr, CString, c_char, c_void};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};

const MODEL_URL: &str = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en.tar.bz2";
const MODEL_SHA: &str = "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542";
const MODEL_DIR: &str = "sherpa-onnx-whisper-base.en";
/// The files kept from the download (the rest are bigger twins of them).
const KEEP: [&str; 3] = [
    "base.en-encoder.int8.onnx",
    "base.en-decoder.int8.onnx",
    "base.en-tokens.txt",
];

/// About how much is downloaded, for the owner to know before.
pub const DOWNLOAD_MB: u32 = 200;

// ---- sherpa-onnx 1.13.8 C API (c-api.h): the start of
// SherpaOnnxOfflineRecognizerConfig, field for field, up to the last field
// set here. Everything after it stays zero in a much bigger buffer.

#[repr(C)]
struct Feat {
    sample_rate: i32,
    feature_dim: i32,
}
#[repr(C)]
struct Transducer {
    encoder: *const c_char,
    decoder: *const c_char,
    joiner: *const c_char,
}
#[repr(C)]
struct Whisper {
    encoder: *const c_char,
    decoder: *const c_char,
    language: *const c_char,
    task: *const c_char,
    tail_paddings: i32,
    enable_token_timestamps: i32,
    enable_segment_timestamps: i32,
}
#[repr(C)]
struct ConfigStart {
    feat: Feat,
    transducer: Transducer,
    paraformer: *const c_char,
    nemo_ctc: *const c_char,
    whisper: Whisper,
    tdnn: *const c_char,
    tokens: *const c_char,
    num_threads: i32,
    debug: i32,
    provider: *const c_char,
}
/// The start of SherpaOnnxOfflineRecognizerResult.
#[repr(C)]
struct ResultStart {
    text: *const c_char,
}

type CreateFn = unsafe extern "C" fn(*const c_void) -> *const c_void;
type DestroyFn = unsafe extern "C" fn(*const c_void);
type StreamFn = unsafe extern "C" fn(*const c_void) -> *const c_void;
type AcceptFn = unsafe extern "C" fn(*const c_void, i32, *const f32, i32);
type DecodeFn = unsafe extern "C" fn(*const c_void, *const c_void);
type ResultFn = unsafe extern "C" fn(*const c_void) -> *const ResultStart;
type FreeResultFn = unsafe extern "C" fn(*const ResultStart);

struct Engine {
    recognizer: *const c_void,
    destroy: DestroyFn,
    stream: StreamFn,
    free_stream: DestroyFn,
    accept: AcceptFn,
    decode: DecodeFn,
    result: ResultFn,
    free_result: FreeResultFn,
}

// SAFETY: the engine is used from one thread at a time (behind a Mutex).
unsafe impl Send for Engine {}

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

fn model_dir(root: &Path) -> PathBuf {
    root.join("listen")
}

/// The speech model (and the engine) are downloaded, checked and unpacked.
pub fn ready(root: &Path) -> bool {
    model_dir(root).join("ok-1").is_file() && voice::engine_ready(root)
}

/// Downloads, checks and unpacks the speech model, and the engine if Read
/// aloud has not fetched it yet. Runs a while: call it off the window's
/// thread. `step` reports what it is doing.
pub fn download(root: &Path, step: impl Fn(&str)) -> Result<(), String> {
    if ready(root) {
        return Ok(());
    }
    voice::ensure_engine(root, &step)?;
    let incoming = root.join("incoming-listen");
    let _ = std::fs::remove_dir_all(&incoming);
    std::fs::create_dir_all(&incoming).map_err(|e| e.to_string())?;
    let done = (|| {
        let archive = incoming.join("model.tar.bz2");
        step("Downloading the speech model…");
        voice::fetch_with(MODEL_URL, MODEL_SHA, &archive, &|got| {
            step(&format!(
                "Downloading the speech model… {} of {DOWNLOAD_MB} MB",
                got / 1_000_000
            ));
        })?;
        step("Unpacking…");
        voice::untar(&archive, &incoming)?;
        let to = model_dir(root);
        let _ = std::fs::remove_dir_all(&to);
        std::fs::create_dir_all(&to).map_err(|e| e.to_string())?;
        for f in KEEP {
            std::fs::rename(incoming.join(MODEL_DIR).join(f), to.join(f))
                .map_err(|_| "The speech model's download was not as expected.".to_string())?;
        }
        std::fs::write(to.join("ok-1"), "whisper base.en (int8)\n").map_err(|e| e.to_string())
    })();
    let _ = std::fs::remove_dir_all(&incoming);
    done
}

/// Deletes the speech model, and the engine too unless Read aloud uses it.
pub fn delete(root: &Path) {
    if let Ok(mut g) = ENGINE.lock()
        && let Some(e) = g.take()
    {
        // SAFETY: the recognizer came from this library and is not in use.
        unsafe { (e.destroy)(e.recognizer) };
    }
    let _ = std::fs::remove_dir_all(model_dir(root));
    voice::drop_unused_engine(root);
}

fn load(root: &Path) -> Result<Engine, String> {
    let lib = voice::open_engine(root)?;
    let create: CreateFn = voice::sym(lib, "SherpaOnnxCreateOfflineRecognizer")?;
    let destroy: DestroyFn = voice::sym(lib, "SherpaOnnxDestroyOfflineRecognizer")?;
    let stream: StreamFn = voice::sym(lib, "SherpaOnnxCreateOfflineStream")?;
    let free_stream: DestroyFn = voice::sym(lib, "SherpaOnnxDestroyOfflineStream")?;
    let accept: AcceptFn = voice::sym(lib, "SherpaOnnxAcceptWaveformOffline")?;
    let decode: DecodeFn = voice::sym(lib, "SherpaOnnxDecodeOfflineStream")?;
    let result: ResultFn = voice::sym(lib, "SherpaOnnxGetOfflineStreamResult")?;
    let free_result: FreeResultFn = voice::sym(lib, "SherpaOnnxDestroyOfflineRecognizerResult")?;
    let dir = model_dir(root);
    let s = |p: PathBuf| CString::new(p.to_string_lossy().as_bytes()).unwrap_or_default();
    let encoder = s(dir.join(KEEP[0]));
    let decoder = s(dir.join(KEEP[1]));
    let tokens = s(dir.join(KEEP[2]));
    // PCE-25: pass the system locale's language to the recogniser.
    // LANG=fr_FR.UTF-8 → "fr"; falls back to "en".
    let lang_code = std::env::var("LANG")
        .unwrap_or_default()
        .split('.')
        .next()
        .unwrap_or("en")
        .split('_')
        .next()
        .unwrap_or("en")
        .to_string();
    let lang_str = if lang_code.len() == 2 || lang_code.len() == 3 {
        lang_code
    } else {
        "en".to_string()
    };
    let language = CString::new(lang_str.as_str()).unwrap_or_else(|_| CString::new("en").unwrap());
    let task = CString::new("transcribe").unwrap_or_default();
    let provider = CString::new("cpu").unwrap_or_default();
    let threads = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2)
        .clamp(1, 4) as i32;
    // A zeroed buffer much bigger than the whole struct, so the library
    // never reads past it and every field not set here is empty.
    let mut buf = vec![0u8; std::mem::size_of::<ConfigStart>() + 16 * 1024];
    let cfg = buf.as_mut_ptr() as *mut ConfigStart;
    // SAFETY: the buffer is big enough, zeroed and aligned for pointers
    // (a Vec<u8> of this size comes from the allocator with 16-byte
    // alignment on this platform; checked below).
    if !(cfg as usize).is_multiple_of(std::mem::align_of::<ConfigStart>()) {
        return Err("Talk could not start. Try again, or remove the speech model in Settings and download it again.".into());
    }
    unsafe {
        (*cfg).feat.sample_rate = 16000;
        (*cfg).feat.feature_dim = 80;
        (*cfg).whisper.encoder = encoder.as_ptr();
        (*cfg).whisper.decoder = decoder.as_ptr();
        (*cfg).whisper.language = language.as_ptr();
        (*cfg).whisper.task = task.as_ptr();
        (*cfg).whisper.tail_paddings = -1;
        (*cfg).tokens = tokens.as_ptr();
        (*cfg).num_threads = threads;
        (*cfg).provider = provider.as_ptr();
    }
    // SAFETY: a valid config; the strings live until create returns.
    let recognizer = unsafe { create(cfg as *const c_void) };
    if recognizer.is_null() {
        return Err("Talk could not start. Try again, or remove the speech model in Settings and download it again.".into());
    }
    Ok(Engine {
        recognizer,
        destroy,
        stream,
        free_stream,
        accept,
        decode,
        result,
        free_result,
    })
}

/// The sound in a 16-bit WAV file: its rate and its samples (one channel,
/// -1 to 1). A file whose sizes were never filled in (the recorder was
/// stopped) is read to its end.
pub fn samples(wav: &[u8]) -> Result<(u32, Vec<f32>), String> {
    let bad = || "The recording could not be read.".to_string();
    if wav.len() < 12 || &wav[..4] != b"RIFF" || &wav[8..12] != b"WAVE" {
        return Err(bad());
    }
    let u16_at = |i: usize| u16::from_le_bytes([wav[i], wav[i + 1]]);
    let u32_at = |i: usize| u32::from_le_bytes([wav[i], wav[i + 1], wav[i + 2], wav[i + 3]]);
    let mut at = 12;
    let mut format = None;
    while at + 8 <= wav.len() {
        let id = &wav[at..at + 4];
        let size = u32_at(at + 4) as usize;
        let body = at + 8;
        if id == b"fmt " && body + 16 <= wav.len() {
            format = Some((
                u16_at(body),
                u16_at(body + 2),
                u32_at(body + 4),
                u16_at(body + 14),
            ));
        } else if id == b"data" {
            let (kind, channels, rate, bits) = format.ok_or_else(bad)?;
            // 1 is plain PCM; 0xFFFE is the same with a longer header.
            if !(kind == 1 || kind == 0xFFFE) || bits != 16 || channels == 0 || rate == 0 {
                return Err(bad());
            }
            let end = if size == 0 || body + size > wav.len() {
                wav.len()
            } else {
                body + size
            };
            let frame = 2 * channels as usize;
            let out = wav[body..end]
                .chunks_exact(frame)
                .map(|f| {
                    let sum: i32 = f
                        .as_chunks::<2>()
                        .0
                        .iter()
                        .map(|s| i16::from_le_bytes(*s) as i32)
                        .sum();
                    sum as f32 / channels as f32 / 32768.0
                })
                .collect();
            return Ok((rate, out));
        }
        // Chunks are padded to an even size.
        at = body + size + (size & 1);
    }
    Err(bad())
}

/// Whisper hears at most 30 seconds at once: cuts long sound into pieces
/// of at most 28 s, each cut at the quietest spot of its last 8 s.
pub fn pieces(samples: &[f32], rate: u32) -> Vec<std::ops::Range<usize>> {
    let rate = rate.max(1) as usize;
    let (most, look, frame) = (28 * rate, 8 * rate, rate / 5);
    let mut out = vec![];
    let mut from = 0;
    while samples.len() - from > most {
        let (lo, hi) = (from + most - look, from + most);
        let mut best = (f32::MAX, hi);
        let mut i = lo;
        while i + frame <= hi {
            let energy: f32 = samples[i..i + frame].iter().map(|s| s * s).sum();
            if energy < best.0 {
                best = (energy, i + frame / 2);
            }
            i += frame.max(1);
        }
        out.push(from..best.1);
        from = best.1;
    }
    out.push(from..samples.len());
    out
}

/// What Whisper wrote, tidied: its notes about silence or noise are
/// dropped, and spaces are single.
pub fn tidy(text: &str) -> String {
    let mut out = String::new();
    let mut depth = 0usize;
    // "[BLANK_AUDIO]", "(silence)", "[Music]" and the like are not words.
    for c in text.chars() {
        match c {
            '[' | '(' => depth += 1,
            ']' | ')' => depth = depth.saturating_sub(1),
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// A phone's dictated audio on this PC: in a folder only this user can
/// enter, under a name no one can guess, and gone when this is dropped.
pub struct Heard {
    path: PathBuf,
}

impl Heard {
    /// Writes `bytes` into `dir` (made 0700), as a 0600 file with a random
    /// name.
    pub fn keep(dir: &Path, bytes: &[u8]) -> std::io::Result<Heard> {
        use ring::rand::SecureRandom;
        use std::io::Write;
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
        std::fs::create_dir_all(dir)?;
        std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))?;
        let mut r = [0u8; 16];
        ring::rand::SystemRandom::new()
            .fill(&mut r)
            .map_err(|_| std::io::Error::other("no randomness"))?;
        let name: String = r.iter().map(|b| format!("{b:02x}")).collect();
        let path = dir.join(format!("heard-{name}.wav"));
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)?;
        // Dropped from here on: a failed write leaves nothing either.
        let h = Heard { path };
        f.write_all(bytes)?;
        Ok(h)
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for Heard {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

/// Where a phone's dictated audio waits to be heard.
pub fn heard_dir() -> PathBuf {
    crate::sockets::runtime_dir().join("heard")
}

/// Turns a recorded WAV file into text. Runs a while: call it off the
/// window's thread.
pub fn transcribe(root: &Path, wav: &Path) -> Result<String, String> {
    let bytes = std::fs::read(wav).map_err(|_| "The recording could not be read.".to_string())?;
    let (rate, sound) = samples(&bytes)?;
    if sound.len() < rate as usize / 3 {
        return Err("Nothing was recorded. Is the microphone on?".into());
    }
    let mut g = ENGINE
        .lock()
        .map_err(|_| "The speech model is busy.".to_string())?;
    if g.is_none() {
        *g = Some(load(root)?);
    }
    let e = g.as_ref().ok_or("The speech model is not loaded.")?;
    let mut parts = vec![];
    for piece in pieces(&sound, rate) {
        let piece = &sound[piece];
        // SAFETY: the recognizer is alive; the samples outlive the calls;
        // the stream and the result are freed by the library that made them.
        let text = unsafe {
            let stream = (e.stream)(e.recognizer);
            if stream.is_null() {
                return Err(
                    "Talk could not hear you. Check the microphone, then try again.".into(),
                );
            }
            (e.accept)(stream, rate as i32, piece.as_ptr(), piece.len() as i32);
            (e.decode)(e.recognizer, stream);
            let r = (e.result)(stream);
            let text = if r.is_null() || (*r).text.is_null() {
                String::new()
            } else {
                CStr::from_ptr((*r).text).to_string_lossy().to_string()
            };
            if !r.is_null() {
                (e.free_result)(r);
            }
            (e.free_stream)(stream);
            text
        };
        let text = tidy(&text);
        if !text.is_empty() {
            parts.push(text);
        }
    }
    let text = parts.join(" ");
    if text.is_empty() {
        Err("No words were heard. Is the microphone on and close enough?".into())
    } else {
        Ok(text)
    }
}

/// The microphone being recorded to a file.
pub struct Recording {
    child: Child,
    file: PathBuf,
    started: Instant,
}

/// Starts recording the microphone into `dir`.
pub fn record(dir: &Path) -> Result<Recording, String> {
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let file = dir.join("listening.wav");
    let _ = std::fs::remove_file(&file);
    let quiet = |c: &mut Command| {
        use std::os::unix::process::CommandExt;
        // SAFETY: only an async-signal-safe call between fork and exec. The
        // recorder ends with Mímir, however that ends.
        unsafe {
            c.pre_exec(|| {
                libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM);
                Ok(())
            });
        }
        c.stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
    };
    let child = quiet(
        Command::new("pw-record")
            .args(["--rate", "16000", "--channels", "1", "--format", "s16"])
            .args(["--media-role", "Communication"])
            .arg(&file),
    )
    .or_else(|_| {
        quiet(
            Command::new("arecord")
                .args(["-q", "-t", "wav", "-f", "S16_LE", "-r", "16000", "-c", "1"])
                .arg(&file),
        )
    })
    .map_err(|_| "No recorder was found (pw-record or arecord).".to_string())?;
    Ok(Recording {
        child,
        file,
        started: Instant::now(),
    })
}

impl Recording {
    fn end(&mut self) {
        // SAFETY: a signal to our own child process.
        unsafe { libc::kill(self.child.id() as i32, libc::SIGINT) };
        let until = Instant::now() + Duration::from_secs(2);
        while Instant::now() < until {
            if matches!(self.child.try_wait(), Ok(Some(_))) {
                return;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
    }

    /// Stops recording; the file with what was said.
    pub fn stop(mut self) -> PathBuf {
        self.end();
        self.file.clone()
    }

    /// How long it has been recording.
    pub fn seconds(&self) -> u64 {
        self.started.elapsed().as_secs()
    }

    /// The recorder ended by itself (no microphone, for one).
    pub fn failed(&mut self) -> bool {
        matches!(self.child.try_wait(), Ok(Some(_)) | Err(_))
    }
}

impl Drop for Recording {
    fn drop(&mut self) {
        if matches!(self.child.try_wait(), Ok(None)) {
            self.end();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dictated_audio_is_private_randomly_named_and_deleted_after_use() {
        use std::os::unix::fs::PermissionsExt;
        let d = std::env::temp_dir().join(format!("heard-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        let a = Heard::keep(&d, b"one").unwrap();
        let b = Heard::keep(&d, b"one").unwrap();
        assert_ne!(a.path(), b.path());
        assert_eq!(
            std::fs::metadata(&d).unwrap().permissions().mode() & 0o777,
            0o700
        );
        assert_eq!(
            std::fs::metadata(a.path()).unwrap().permissions().mode() & 0o777,
            0o600
        );
        let (pa, pb) = (a.path().to_path_buf(), b.path().to_path_buf());
        assert!(pa.is_file());
        drop(a);
        drop(b);
        assert!(!pa.exists() && !pb.exists());
        let _ = std::fs::remove_dir_all(&d);
    }

    fn wav(channels: u16, rate: u32, data: &[i16], sizes: bool) -> Vec<u8> {
        let bytes: Vec<u8> = data.iter().flat_map(|s| s.to_le_bytes()).collect();
        let n = if sizes { bytes.len() as u32 } else { 0 };
        let mut w = b"RIFF".to_vec();
        w.extend((36 + n).to_le_bytes());
        w.extend(b"WAVEfmt ");
        w.extend(16u32.to_le_bytes());
        w.extend(1u16.to_le_bytes());
        w.extend(channels.to_le_bytes());
        w.extend(rate.to_le_bytes());
        w.extend((rate * 2 * channels as u32).to_le_bytes());
        w.extend((2 * channels).to_le_bytes());
        w.extend(16u16.to_le_bytes());
        w.extend(b"data");
        w.extend(n.to_le_bytes());
        w.extend(bytes);
        w
    }

    #[test]
    fn wav_files_are_read() {
        let (rate, s) = samples(&wav(1, 16000, &[0, 16384, -32768], true)).unwrap();
        assert_eq!(rate, 16000);
        assert_eq!(s, vec![0.0, 0.5, -1.0]);
        // Two channels become one.
        let (_, s) = samples(&wav(2, 48000, &[16384, 0, -16384, -16384], true)).unwrap();
        assert_eq!(s, vec![0.25, -0.5]);
        // A recorder that was stopped before it wrote the sizes.
        let (_, s) = samples(&wav(1, 16000, &[16384, 16384], false)).unwrap();
        assert_eq!(s.len(), 2);
        assert!(samples(b"not a wav file at all").is_err());
        assert!(samples(&[]).is_err());
    }

    #[test]
    fn long_sound_is_cut_at_a_quiet_spot() {
        let rate = 100;
        assert_eq!(pieces(&vec![0.5; 2000], rate), vec![0..2000]);
        // 60 s of sound with one quiet stretch at 25 s.
        let mut s = vec![0.5f32; 6000];
        for x in &mut s[2500..2520] {
            *x = 0.0;
        }
        let p = pieces(&s, rate);
        assert_eq!(p.len(), 3);
        assert_eq!(p[0], 0..2510);
        assert!(p.iter().all(|r| r.len() <= 2800));
        assert_eq!(p.last().unwrap().end, 6000);
        assert!(p.windows(2).all(|w| w[0].end == w[1].start));
        assert_eq!(pieces(&[], rate), vec![0..0]);
    }

    #[test]
    fn whisper_notes_are_not_words() {
        assert_eq!(
            tidy(" Fix the  login page. [BLANK_AUDIO] "),
            "Fix the login page."
        );
        assert_eq!(tidy("(silence)"), "");
        assert_eq!(tidy("Hello [Music] there"), "Hello there");
    }

    /// Needs the engine and the model on this PC (Settings › Voice instead
    /// of type, or STATION_LISTEN_ROOT). STATION_LISTEN_WAV names the file.
    #[test]
    #[ignore]
    fn hears_a_real_file() {
        let root = std::env::var("STATION_LISTEN_ROOT")
            .map(PathBuf::from)
            .unwrap_or_else(|_| {
                PathBuf::from(std::env::var("HOME").unwrap()).join(".local/share/mimir/voice")
            });
        let wav = std::env::var("STATION_LISTEN_WAV").unwrap();
        let t = Instant::now();
        let text = transcribe(&root, Path::new(&wav)).unwrap();
        println!("heard in {:?}: {text}", t.elapsed());
        assert!(!text.is_empty());
    }
}
