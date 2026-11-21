//! The PC's developer log: one place Claude reads when something goes
//! wrong. `~/.local/state/mimir/devlog/` (0700, files 0600), JSON
//! Lines: one event a line with stable keys, in order, with timings, ids
//! and causes. Written by one background thread (a logging call costs a few
//! microseconds on the caller's), buffered (flushed every 5 s or 64 KB; only
//! errors and shutdown are fsynced).
//!
//! Every line: `t` (ISO, UTC, milliseconds), `mono` (monotonic ms), `run`
//! (the service run), `cat`, `ev`, the ids that apply (`task`, `chat`,
//! `turn`, `phone`, `call_id`, `job`), `ms` for what ends, `ok` or `err`.
//! Categories: service, call, link, talk, model, task, store, lookup,
//! voice, window, slow (any service message over 50 ms: the freeze
//! detector), crash.
//!
//! Not cluttered: no per-token lines, identical events collapse into
//! `"repeat": N` (10 s), a summary line every 10 minutes while anything
//! happened. Errors, crashes, slow events and the start headers also go to
//! `important-*.jsonl` (50 MB, 30 days), so a flood of ordinary events never
//! pushes the evidence out. The main log is 10 MB files, at most 50, nothing
//! older than 14 days; a `log-busy` event says when it fills faster than a
//! file an hour.
//!
//! Privacy: never keys, tokens, passwords or file contents; never anything
//! of a Whisper (it says "Whisper: not logged" once); the text of ordinary
//! chats only while the Labs switch "Include my normal chats" is on, cut at
//! 4000 characters. Switching the log off deletes the files.

use serde_json::Value;
use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender, SyncSender, channel, sync_channel};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

/// A service message slower than this is a `slow` event.
pub const SLOW_MS: u64 = 50;
const FLUSH_EVERY: Duration = Duration::from_secs(5);
const FLUSH_BYTES: usize = 64 * 1024;
const SUMMARY_EVERY: Duration = Duration::from_secs(600);
const REPEAT_WINDOW: Duration = Duration::from_secs(10);
const MAX_TEXT: usize = 4000;

/// Where the files are, and how much is kept.
#[derive(Clone, Debug)]
pub struct Config {
    pub dir: PathBuf,
    /// The role's file prefix: `devlog` (the service) or `window`.
    pub prefix: &'static str,
    pub main_bytes: u64,
    pub main_files: usize,
    pub main_days: u64,
    pub important_bytes: u64,
    pub important_files: usize,
    pub important_days: u64,
    /// Whether this role writes `important-*` files.
    pub important: bool,
}

impl Config {
    pub fn service(dir: PathBuf) -> Config {
        Config {
            dir,
            prefix: "devlog",
            main_bytes: 10 << 20,
            main_files: 50,
            main_days: 14,
            important_bytes: 5 << 20,
            important_files: 10,
            important_days: 30,
            important: true,
        }
    }

    pub fn window(dir: PathBuf) -> Config {
        Config {
            prefix: "window",
            main_bytes: 5 << 20,
            main_files: 4,
            main_days: 14,
            important: false,
            ..Config::service(dir)
        }
    }
}

/// The folder of the log.
pub fn dir() -> PathBuf {
    std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .unwrap_or_else(|| crate::paths::home().join(".local/state"))
        .join("mimir")
        .join("devlog")
}

// ---------- Time ----------

fn unix_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// "2026-11-20T08:05:12.345Z" from milliseconds since 1970.
pub fn iso(ms: u64) -> String {
    let secs = (ms / 1000) as i64;
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // Civil date from days since 1970 (Howard Hinnant's algorithm).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.{:03}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60,
        ms % 1000
    )
}

/// Milliseconds since 1970 from [`iso`]'s form.
pub fn parse_iso(s: &str) -> Option<u64> {
    let b = s.as_bytes();
    if b.len() < 20 || b[4] != b'-' || b[10] != b'T' {
        return None;
    }
    let n = |a: usize, z: usize| s.get(a..z)?.parse::<i64>().ok();
    let (y, m, d) = (n(0, 4)?, n(5, 7)?, n(8, 10)?);
    let (h, mi, sec) = (n(11, 13)?, n(14, 16)?, n(17, 19)?);
    let ms = s
        .get(20..23)
        .and_then(|x| x.parse::<i64>().ok())
        .unwrap_or(0);
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2.rem_euclid(400);
    let mp = if m > 2 { m - 3 } else { m + 9 };
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    let total = (days * 86_400 + h * 3600 + mi * 60 + sec) * 1000 + ms;
    u64::try_from(total).ok()
}

// ---------- An event, as the caller builds it ----------

/// One event on its way to the writer thread.
pub struct Raw {
    unix_ms: u64,
    mono_ms: u64,
    cat: Cow<'static, str>,
    ev: Cow<'static, str>,
    fields: Vec<(&'static str, Value)>,
}

fn started() -> Instant {
    static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    *START.get_or_init(Instant::now)
}

impl Raw {
    pub fn new(
        cat: impl Into<Cow<'static, str>>,
        ev: impl Into<Cow<'static, str>>,
        fields: Vec<(&'static str, Value)>,
    ) -> Raw {
        Raw {
            unix_ms: unix_ms(),
            mono_ms: started().elapsed().as_millis() as u64,
            cat: cat.into(),
            ev: ev.into(),
            fields,
        }
    }

    fn important(&self) -> bool {
        matches!(&*self.cat, "crash" | "slow")
            || matches!(&*self.ev, "start" | "header")
            || self.fields.iter().any(|(k, _)| *k == "err")
    }

    fn has_id(&self, set: &HashSet<String>) -> bool {
        self.fields.iter().any(|(k, v)| {
            matches!(*k, "task" | "chat" | "turn" | "job")
                && v.as_str().is_some_and(|s| set.contains(s))
        })
    }
}

/// Key names whose values are never written.
fn secret_key(k: &str) -> bool {
    let k = k.to_ascii_lowercase();
    [
        "key",
        "token",
        "password",
        "passwd",
        "secret",
        "auth",
        "cookie",
        "bearer",
        "credential",
    ]
    .iter()
    .any(|w| k.contains(w))
}

/// A string value with anything that looks like a credential taken out.
/// Words are split at every character that can't be part of a key (spaces,
/// new lines, quotes, `:`, `=` before a letter, commas, brackets), so a key
/// inside JSON, after `token=` or on its own line is still found.
fn scrub(s: &str) -> String {
    fn token_char(c: char) -> bool {
        c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '+' | '/' | '.' | '=')
    }
    fn looks_secret(w: &str) -> bool {
        // `token=abc...`: judge the part after the last '='-run start.
        let w = w.trim_matches(|c: char| matches!(c, '.' | '/' | '='));
        let w = match w.find('=') {
            Some(i) if w[..i].chars().all(|c| c.is_ascii_alphabetic() || c == '_') => &w[i + 1..],
            _ => w,
        };
        w.starts_with("sk-")
            || w.starts_with("ghp_")
            || w.starts_with("gho_")
            || w.starts_with("github_pat_")
            || w.starts_with("xox")
            || w.starts_with("eyJ")
            || (w.len() >= 32
                && w.chars().all(|c| {
                    c.is_ascii_alphanumeric() || matches!(c, '+' | '/' | '=' | '_' | '-' | '.')
                })
                && w.chars().any(|c| c.is_ascii_digit())
                && w.chars().any(|c| c.is_ascii_alphabetic()))
    }
    let mut out = String::with_capacity(s.len().min(MAX_TEXT + 16));
    let mut mask_next = false;
    let mut rest = s;
    while !rest.is_empty() {
        let n = rest.find(|c: char| !token_char(c)).unwrap_or(rest.len());
        if n == 0 {
            let c = rest.chars().next().unwrap_or(' ');
            out.push(c);
            rest = &rest[c.len_utf8()..];
            continue;
        }
        let word = &rest[..n];
        rest = &rest[n..];
        if mask_next || looks_secret(word) {
            out.push_str("[hidden]");
            mask_next = false;
        } else {
            out.push_str(word);
        }
        if word.eq_ignore_ascii_case("bearer") {
            mask_next = true;
        }
    }
    out
}

fn clean(v: &Value) -> Value {
    match v {
        Value::String(s) => Value::String(scrub(s)),
        Value::Array(a) => Value::Array(a.iter().map(clean).collect()),
        Value::Object(m) => Value::Object(
            m.iter()
                .map(|(k, v)| {
                    let v = if secret_key(k) {
                        Value::String("[hidden]".into())
                    } else {
                        clean(v)
                    };
                    (k.clone(), v)
                })
                .collect(),
        ),
        other => other.clone(),
    }
}

/// The line as written.
fn render(r: &Raw, run: &str) -> String {
    let mut s = String::with_capacity(160);
    s.push_str("{\"t\":\"");
    s.push_str(&iso(r.unix_ms));
    s.push_str("\",\"mono\":");
    s.push_str(&r.mono_ms.to_string());
    s.push_str(",\"run\":");
    s.push_str(&Value::String(run.to_string()).to_string());
    s.push_str(",\"cat\":");
    s.push_str(&Value::String(r.cat.to_string()).to_string());
    s.push_str(",\"ev\":");
    s.push_str(&Value::String(r.ev.to_string()).to_string());
    for (k, v) in &r.fields {
        s.push_str(",\"");
        s.push_str(k);
        s.push_str("\":");
        if secret_key(k) {
            s.push_str("\"[hidden]\"");
        } else {
            s.push_str(&clean(v).to_string());
        }
    }
    s.push('}');
    s
}

// ---------- Files ----------

/// A set of files `<prefix>-<n>.jsonl`, written one after the other.
struct Rolling {
    dir: PathBuf,
    prefix: String,
    bytes: u64,
    files: usize,
    days: u64,
    n: u64,
    file: Option<std::io::BufWriter<std::fs::File>>,
    size: u64,
    pending: usize,
    /// When each file was started (for `log-busy`).
    rolled: Vec<Instant>,
}

impl Rolling {
    fn new(dir: &Path, prefix: &str, bytes: u64, files: usize, days: u64) -> Rolling {
        let mut r = Rolling {
            dir: dir.to_path_buf(),
            prefix: prefix.to_string(),
            bytes,
            files,
            days,
            n: 0,
            file: None,
            size: 0,
            pending: 0,
            rolled: vec![],
        };
        r.n = r.numbered().last().map_or(0, |(n, _)| *n);
        r.prune();
        r
    }

    fn path(&self, n: u64) -> PathBuf {
        self.dir.join(format!("{}-{n}.jsonl", self.prefix))
    }

    /// The files there are, oldest first.
    fn numbered(&self) -> Vec<(u64, PathBuf)> {
        let mut v: Vec<(u64, PathBuf)> = std::fs::read_dir(&self.dir)
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|e| {
                let name = e.file_name().to_string_lossy().to_string();
                let n = name
                    .strip_prefix(&format!("{}-", self.prefix))?
                    .strip_suffix(".jsonl")?
                    .parse::<u64>()
                    .ok()?;
                Some((n, e.path()))
            })
            .collect();
        v.sort();
        v
    }

    /// Takes away files over the count and files older than the age.
    fn prune(&mut self) {
        let all = self.numbered();
        let keep_from = all.len().saturating_sub(self.files);
        let cutoff = std::time::SystemTime::now()
            .checked_sub(Duration::from_secs(self.days * 86_400))
            .unwrap_or(std::time::UNIX_EPOCH);
        for (i, (n, p)) in all.iter().enumerate() {
            let old = std::fs::metadata(p)
                .and_then(|m| m.modified())
                .is_ok_and(|t| t < cutoff);
            // The one being written stays.
            if (i < keep_from || old) && !(self.file.is_some() && *n == self.n) {
                let _ = std::fs::remove_file(p);
            }
        }
    }

    fn open_current(&mut self) -> bool {
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
        if self.file.is_some() {
            return true;
        }
        if std::fs::create_dir_all(&self.dir).is_err() {
            return false;
        }
        let _ = std::fs::set_permissions(&self.dir, std::fs::Permissions::from_mode(0o700));
        if self.n == 0 {
            self.n = 1;
        }
        let p = self.path(self.n);
        let Ok(f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .mode(0o600)
            .open(&p)
        else {
            return false;
        };
        self.size = f.metadata().map_or(0, |m| m.len());
        self.file = Some(std::io::BufWriter::with_capacity(FLUSH_BYTES * 2, f));
        true
    }

    /// Writes a line; true when it started a new file.
    fn write(&mut self, line: &str, sync: bool) -> bool {
        if !self.open_current() {
            return false;
        }
        let mut rolled = false;
        if self.size > 0 && self.size + line.len() as u64 + 1 > self.bytes {
            self.flush(true);
            self.file = None;
            self.n += 1;
            self.rolled.push(Instant::now());
            self.rolled
                .retain(|t| t.elapsed() < Duration::from_secs(3600));
            if !self.open_current() {
                return false;
            }
            self.prune();
            rolled = true;
        }
        if let Some(f) = self.file.as_mut()
            && writeln!(f, "{line}").is_ok()
        {
            self.size += line.len() as u64 + 1;
            self.pending += line.len() + 1;
        }
        if sync || self.pending >= FLUSH_BYTES {
            self.flush(sync);
        }
        rolled
    }

    fn flush(&mut self, sync: bool) {
        if let Some(f) = self.file.as_mut() {
            let _ = f.flush();
            if sync {
                let _ = f.get_ref().sync_data();
            }
        }
        self.pending = 0;
    }

    fn purge(&mut self) {
        self.file = None;
        for (_, p) in self.numbered() {
            let _ = std::fs::remove_file(p);
        }
    }
}

// ---------- The writer ----------

#[derive(Default)]
struct Tally {
    by_cat: HashMap<String, u64>,
    errors: u64,
    slowest: Option<(u64, String)>,
}

/// What the writer thread owns. Also used straight in tests.
pub struct Writer {
    run: String,
    cfg: Config,
    main: Rolling,
    important: Option<Rolling>,
    /// The last line written and how many times it came again since.
    last: Option<(String, Instant, u64, Raw)>,
    tally: Tally,
    since_roll: HashMap<String, u64>,
    busy_said: Option<Instant>,
}

impl Writer {
    pub fn new(run: &str, cfg: Config) -> Writer {
        let main = Rolling::new(
            &cfg.dir,
            cfg.prefix,
            cfg.main_bytes,
            cfg.main_files,
            cfg.main_days,
        );
        let important = cfg.important.then(|| {
            Rolling::new(
                &cfg.dir,
                "important",
                cfg.important_bytes,
                cfg.important_files,
                cfg.important_days,
            )
        });
        Writer {
            run: run.to_string(),
            cfg,
            main,
            important,
            last: None,
            tally: Tally::default(),
            since_roll: HashMap::new(),
            busy_said: None,
        }
    }

    fn key_of(r: &Raw) -> String {
        let mut k = format!("{}\u{1}{}", r.cat, r.ev);
        for (n, v) in &r.fields {
            if !matches!(*n, "ms" | "bytes" | "n") {
                k.push('\u{1}');
                k.push_str(n);
                k.push('=');
                k.push_str(&v.to_string());
            }
        }
        k
    }

    /// Writes one event (collapsing an identical one that comes again
    /// within ten seconds).
    pub fn event(&mut self, r: Raw) {
        let key = Self::key_of(&r);
        if let Some((k, at, n, _)) = self.last.as_mut()
            && *k == key
            && at.elapsed() < REPEAT_WINDOW
        {
            *n += 1;
            return;
        }
        self.settle_repeat();
        self.emit(&r);
        self.last = Some((key, Instant::now(), 0, r));
    }

    /// Writes the "repeat" line for what was collapsed.
    fn settle_repeat(&mut self) {
        if let Some((_, _, n, r)) = self.last.take()
            && n > 0
        {
            let rep = Raw {
                unix_ms: unix_ms(),
                mono_ms: started().elapsed().as_millis() as u64,
                cat: r.cat.clone(),
                ev: r.ev.clone(),
                fields: {
                    let mut f: Vec<(&'static str, Value)> = r
                        .fields
                        .iter()
                        .filter(|(k, _)| {
                            matches!(
                                *k,
                                "task" | "chat" | "phone" | "job" | "call" | "err" | "kind"
                            )
                        })
                        .cloned()
                        .collect();
                    f.push(("repeat", Value::from(n)));
                    f
                },
            };
            self.emit(&rep);
        }
    }

    fn emit(&mut self, r: &Raw) {
        let line = render(r, &self.run);
        *self.tally.by_cat.entry(r.cat.to_string()).or_default() += 1;
        *self.since_roll.entry(r.cat.to_string()).or_default() += 1;
        let is_err = r.fields.iter().any(|(k, _)| *k == "err");
        if is_err {
            self.tally.errors += 1;
        }
        if &*r.cat == "call"
            && let Some(ms) = r
                .fields
                .iter()
                .find(|(k, _)| *k == "ms")
                .and_then(|(_, v)| v.as_u64())
            && self.tally.slowest.as_ref().is_none_or(|(m, _)| ms > *m)
        {
            let name = r
                .fields
                .iter()
                .find(|(k, _)| *k == "call")
                .and_then(|(_, v)| v.as_str())
                .unwrap_or("?");
            self.tally.slowest = Some((ms, name.to_string()));
        }
        let rolled = self.main.write(&line, false);
        if r.important()
            && let Some(imp) = self.important.as_mut()
        {
            imp.write(&line, true);
        }
        if rolled {
            self.busy_check();
        }
    }

    /// A second file inside an hour: one `log-busy` event, naming the
    /// category that flooded.
    fn busy_check(&mut self) {
        if self.main.rolled.len() >= 2
            && self
                .busy_said
                .is_none_or(|t| t.elapsed() > Duration::from_secs(3600))
        {
            let top = self
                .since_roll
                .iter()
                .max_by_key(|(_, n)| **n)
                .map(|(c, n)| (c.clone(), *n));
            self.busy_said = Some(Instant::now());
            let (cat, n) = top.unwrap_or_default();
            let r = Raw::new(
                "service",
                "log-busy",
                vec![("flooding", Value::from(cat)), ("events", Value::from(n))],
            );
            let line = render(&r, &self.run);
            self.main.write(&line, false);
        }
        self.since_roll.clear();
    }

    /// The summary of what happened since the last one (nothing if nothing).
    pub fn summary(&mut self) {
        self.settle_repeat();
        if self.tally.by_cat.is_empty() {
            return;
        }
        let t = std::mem::take(&mut self.tally);
        let mut counts: Vec<(String, u64)> = t.by_cat.into_iter().collect();
        counts.sort();
        let mut f: Vec<(&'static str, Value)> = vec![
            (
                "counts",
                Value::Object(
                    counts
                        .into_iter()
                        .map(|(k, v)| (k, Value::from(v)))
                        .collect(),
                ),
            ),
            ("errors", Value::from(t.errors)),
        ];
        if let Some((ms, name)) = t.slowest {
            f.push(("slowest_ms", Value::from(ms)));
            f.push(("slowest_call", Value::from(name)));
        }
        let r = Raw::new("service", "summary", f);
        let line = render(&r, &self.run);
        self.main.write(&line, false);
        self.main.prune();
        if let Some(i) = self.important.as_mut() {
            i.prune();
        }
    }

    pub fn flush(&mut self, sync: bool) {
        self.settle_repeat();
        self.main.flush(sync);
        if let Some(i) = self.important.as_mut() {
            i.flush(sync);
        }
    }

    pub fn purge(&mut self) {
        self.last = None;
        self.main.purge();
        if let Some(i) = self.important.as_mut() {
            i.purge();
        }
        // Anything else of ours in the folder (an older run's, the window's).
        for e in std::fs::read_dir(&self.cfg.dir)
            .into_iter()
            .flatten()
            .flatten()
        {
            let name = e.file_name().to_string_lossy().to_string();
            if name.ends_with(".jsonl") {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
}

enum Msg {
    Line(Raw),
    Summary,
    Flush(SyncSender<()>),
    /// A line written and synced at once (a crash), acknowledged.
    Crash(Raw, SyncSender<()>),
    Purge(SyncSender<()>),
    Stop(SyncSender<()>),
}

fn writer_loop(mut w: Writer, rx: Receiver<Msg>) {
    let mut next_summary = Instant::now() + SUMMARY_EVERY;
    let mut next_flush = Instant::now() + FLUSH_EVERY;
    loop {
        let wait = next_flush
            .min(next_summary)
            .saturating_duration_since(Instant::now());
        match rx.recv_timeout(wait) {
            Ok(Msg::Line(r)) => w.event(r),
            Ok(Msg::Summary) => w.summary(),
            Ok(Msg::Flush(ack)) => {
                w.flush(true);
                let _ = ack.send(());
            }
            Ok(Msg::Crash(r, ack)) => {
                w.event(r);
                w.flush(true);
                let _ = ack.send(());
            }
            Ok(Msg::Purge(ack)) => {
                w.purge();
                let _ = ack.send(());
            }
            Ok(Msg::Stop(ack)) => {
                w.summary();
                w.flush(true);
                let _ = ack.send(());
                return;
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => {
                w.flush(true);
                return;
            }
        }
        let now = Instant::now();
        if now >= next_flush {
            w.flush(false);
            next_flush = now + FLUSH_EVERY;
        }
        if now >= next_summary {
            w.summary();
            next_summary = now + SUMMARY_EVERY;
        }
    }
}

// ---------- The log of this process ----------

struct Global {
    tx: Sender<Msg>,
    config: Config,
}

static GLOBAL: RwLock<Option<Arc<Global>>> = RwLock::new(None);
static ENABLED: AtomicBool = AtomicBool::new(false);
static CHATS: AtomicBool = AtomicBool::new(false);
static WHISPER: Mutex<Option<HashSet<String>>> = Mutex::new(None);
static WHISPER_SAID: AtomicBool = AtomicBool::new(false);

/// Starts the log of this process (the service: `Config::service(dir())`).
/// Switched off by settings, nothing is written.
pub fn init(run: &str, cfg: Config, on: bool, chats: bool) {
    shutdown();
    let w = Writer::new(run, cfg.clone());
    let (tx, rx) = channel();
    let spawned = std::thread::Builder::new()
        .name("devlog".into())
        .spawn(move || writer_loop(w, rx));
    if spawned.is_err() {
        return;
    }
    if let Ok(mut g) = GLOBAL.write() {
        *g = Some(Arc::new(Global { tx, config: cfg }));
    }
    WHISPER_SAID.store(false, Ordering::SeqCst);
    CHATS.store(chats, Ordering::SeqCst);
    ENABLED.store(on, Ordering::SeqCst);
    if !on {
        purge();
    }
}

fn global() -> Option<Arc<Global>> {
    GLOBAL.read().ok().and_then(|g| g.clone())
}

pub fn enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

/// The settings changed: the switches. Off deletes the files.
pub fn set_switches(on: bool, chats: bool) {
    CHATS.store(chats && on, Ordering::SeqCst);
    let was = ENABLED.swap(on, Ordering::SeqCst);
    if was && !on {
        purge();
    }
}

fn purge() {
    if let Some(g) = global() {
        let (ack, wait) = sync_channel(1);
        if g.tx.send(Msg::Purge(ack)).is_ok() {
            let _ = wait.recv_timeout(Duration::from_secs(2));
        }
    }
}

/// Marks a task or chat as a Whisper: nothing that names it is ever written.
pub fn mark_whisper(id: &str) {
    if let Ok(mut w) = WHISPER.lock() {
        w.get_or_insert_with(HashSet::new).insert(id.to_string());
    }
}

/// Logs one event. Cheap: it builds the event and hands it to the writer.
pub fn log(cat: &'static str, ev: &'static str, fields: Vec<(&'static str, Value)>) {
    log_raw(Raw::new(cat, ev, fields));
}

pub fn log_raw(r: Raw) {
    if !ENABLED.load(Ordering::Relaxed) {
        return;
    }
    let Some(g) = global() else { return };
    let is_whisper = WHISPER
        .lock()
        .ok()
        .and_then(|w| w.as_ref().map(|s| r.has_id(s)))
        .unwrap_or(false);
    if is_whisper {
        // Said once, and nothing else.
        if !WHISPER_SAID.swap(true, Ordering::SeqCst) {
            let _ = g.tx.send(Msg::Line(Raw::new(
                "service",
                "whisper",
                vec![("note", "Whisper: not logged".into())],
            )));
        }
        return;
    }
    let _ = g.tx.send(Msg::Line(r));
}

/// An event with a text of an ordinary chat in it: only while "Include my
/// normal chats" is on, cut at 4000 characters.
pub fn log_text(
    cat: &'static str,
    ev: &'static str,
    mut fields: Vec<(&'static str, Value)>,
    text: &str,
) {
    if !CHATS.load(Ordering::Relaxed) {
        return log(cat, ev, fields);
    }
    let cut: String = text.chars().take(MAX_TEXT).collect();
    fields.push(("text", Value::from(cut)));
    log(cat, ev, fields);
}

/// `ev` with an error in plain words and its kind.
pub fn error(
    cat: &'static str,
    ev: &'static str,
    mut fields: Vec<(&'static str, Value)>,
    err: &str,
    kind: &str,
) {
    fields.push(("err", Value::from(err)));
    fields.push(("kind", Value::from(kind)));
    log(cat, ev, fields);
}

/// A service message that took `ms`: a `slow` event when over 50 ms.
pub fn message_took(kind: &str, call: Option<&str>, ms: u64) {
    if ms <= SLOW_MS {
        return;
    }
    let mut f = vec![("msg", Value::from(kind)), ("ms", Value::from(ms))];
    if let Some(c) = call {
        f.push(("call", Value::from(c)));
    }
    log("slow", "service-message", f);
}

/// A panic, with its backtrace, written and synced before the process goes.
pub fn crash(what: &str, backtrace: &str) {
    let Some(g) = global() else { return };
    let r = Raw::new(
        "crash",
        "panic",
        vec![
            ("err", Value::from(what)),
            ("backtrace", Value::from(backtrace)),
        ],
    );
    let (ack, wait) = sync_channel(1);
    if g.tx.send(Msg::Crash(r, ack)).is_ok() {
        let _ = wait.recv_timeout(Duration::from_secs(2));
    }
}

/// Makes panics log a `crash` event, then run the usual hook.
pub fn install_panic_hook() {
    let old = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        crash(
            &info.to_string(),
            &std::backtrace::Backtrace::force_capture().to_string(),
        );
        old(info);
    }));
}

/// Writes what is buffered (and syncs it).
pub fn flush() {
    if let Some(g) = global() {
        let (ack, wait) = sync_channel(1);
        if g.tx.send(Msg::Flush(ack)).is_ok() {
            let _ = wait.recv_timeout(Duration::from_secs(2));
        }
    }
}

/// Asks for the 10-minute summary now (a check).
pub fn summary_now() {
    if let Some(g) = global() {
        let _ = g.tx.send(Msg::Summary);
    }
}

/// Ends the writer: what is buffered is written and synced.
pub fn shutdown() {
    let g = GLOBAL.write().ok().and_then(|mut g| g.take());
    if let Some(g) = g {
        let (ack, wait) = sync_channel(1);
        if g.tx.send(Msg::Stop(ack)).is_ok() {
            let _ = wait.recv_timeout(Duration::from_secs(2));
        }
    }
    ENABLED.store(false, Ordering::SeqCst);
}

/// Times one frame of the window: dropped, it logs a `slow-frame` event when
/// the frame took over 50 ms, with what was on show.
pub struct FrameTimer {
    start: Instant,
    pub what: String,
}

impl FrameTimer {
    pub fn new(what: impl Into<String>) -> FrameTimer {
        FrameTimer {
            start: Instant::now(),
            what: what.into(),
        }
    }
}

impl FrameTimer {
    /// How long the frame took, when that is slow.
    pub fn slow_ms(&self) -> Option<u64> {
        let ms = self.start.elapsed().as_millis() as u64;
        (ms > SLOW_MS).then_some(ms)
    }
}

impl Drop for FrameTimer {
    fn drop(&mut self) {
        if let Some(ms) = self.slow_ms() {
            log(
                "window",
                "slow-frame",
                vec![("ms", ms.into()), ("what", self.what.clone().into())],
            );
        }
    }
}

/// Milliseconds since this process started logging.
pub fn uptime_ms() -> u64 {
    started().elapsed().as_millis() as u64
}

/// The folder this process writes to, if it is on.
pub fn active_dir() -> Option<PathBuf> {
    global().map(|g| g.config.dir.clone())
}

/// `Call::Send { .. }` as "Send": the name of a debug-printed value.
pub fn name_of(v: &dyn std::fmt::Debug) -> String {
    struct Head(String);
    impl std::fmt::Write for Head {
        fn write_str(&mut self, s: &str) -> std::fmt::Result {
            for c in s.chars() {
                if c.is_alphanumeric() || c == '_' {
                    self.0.push(c);
                } else {
                    return Err(std::fmt::Error);
                }
            }
            if self.0.len() > 48 {
                return Err(std::fmt::Error);
            }
            Ok(())
        }
    }
    let mut h = Head(String::new());
    let _ = std::fmt::write(&mut h, format_args!("{v:?}"));
    h.0
}

// ---------- Reading it ----------

/// Every event in `dir`, oldest first.
pub fn read_all(dir: &Path) -> Vec<Value> {
    read_since(dir, None)
}

/// Every event in `dir`, oldest first, from files written to at or after
/// `from_ms` (a file last written before then holds nothing newer).
pub fn read_since(dir: &Path, from_ms: Option<u64>) -> Vec<Value> {
    let mut out: Vec<Value> = vec![];
    for e in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        if !name.ends_with(".jsonl") {
            continue;
        }
        if let Some(from) = from_ms {
            let written = e
                .metadata()
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64);
            if written.is_some_and(|w| w < from) {
                continue;
            }
        }
        let Ok(text) = std::fs::read_to_string(e.path()) else {
            continue;
        };
        // The important file repeats lines the main one has: they are kept
        // once.
        let important = name.starts_with("important");
        for l in text.lines() {
            if let Ok(v) = serde_json::from_str::<Value>(l) {
                out.push(if important { tag_important(v) } else { v });
            }
        }
    }
    out.sort_by_key(key);
    // A line the important file and the main one both hold is shown once.
    let mut seen: HashSet<String> = HashSet::new();
    out.retain_mut(|v| {
        if let Value::Object(m) = v {
            m.remove("_imp");
        }
        seen.insert(v.to_string())
    });
    out
}

fn tag_important(mut v: Value) -> Value {
    if let Value::Object(m) = &mut v {
        m.insert("_imp".into(), Value::Bool(true));
    }
    v
}

fn key(v: &Value) -> (String, u64) {
    (
        v["t"].as_str().unwrap_or("").to_string(),
        v["mono"].as_u64().unwrap_or(0),
    )
}

/// What the CLI was asked for.
#[derive(Default, Debug)]
pub struct Query {
    pub since_ms: Option<u64>,
    pub cats: Vec<String>,
    pub task: Option<String>,
    pub chat: Option<String>,
    pub errors: bool,
    pub slow: bool,
    pub raw: bool,
    pub path: bool,
    pub last: usize,
}

fn parse_since(s: &str) -> Option<u64> {
    let (n, unit) = s.split_at(s.len().checked_sub(1)?);
    let n: u64 = n.parse().ok()?;
    Some(
        n * match unit {
            "s" => 1,
            "m" => 60,
            "h" => 3600,
            "d" => 86_400,
            _ => return None,
        } * 1000,
    )
}

pub fn parse_args(args: &[String]) -> Result<Query, String> {
    let mut q = Query {
        last: 50,
        ..Default::default()
    };
    let mut it = args.iter();
    while let Some(a) = it.next() {
        let mut val = |name: &str| {
            it.next()
                .cloned()
                .ok_or_else(|| format!("{name} needs a value"))
        };
        match a.as_str() {
            "--since" => {
                q.since_ms =
                    Some(parse_since(&val("--since")?).ok_or("--since is like 10m, 2h or 1d")?)
            }
            "--cat" => q.cats = val("--cat")?.split(',').map(str::to_string).collect(),
            "--task" => q.task = Some(val("--task")?),
            "--chat" => q.chat = Some(val("--chat")?),
            "--errors" => q.errors = true,
            "--slow" => q.slow = true,
            "--raw" => q.raw = true,
            "--path" => q.path = true,
            "--last" => q.last = val("--last")?.parse().map_err(|_| "--last is a number")?,
            other => return Err(format!("Unknown option {other}")),
        }
    }
    Ok(q)
}

/// The events that match, oldest first.
pub fn select(all: &[Value], q: &Query, now_ms: u64) -> Vec<Value> {
    let from = q.since_ms.map(|s| now_ms.saturating_sub(s));
    all.iter()
        .filter(|v| {
            if let Some(f) = from
                && v["t"].as_str().and_then(parse_iso).is_some_and(|t| t < f)
            {
                return false;
            }
            if !q.cats.is_empty() && !q.cats.iter().any(|c| v["cat"].as_str() == Some(c)) {
                return false;
            }
            if q.task
                .as_deref()
                .is_some_and(|t| v["task"].as_str() != Some(t))
            {
                return false;
            }
            if q.chat
                .as_deref()
                .is_some_and(|c| v["chat"].as_str() != Some(c))
            {
                return false;
            }
            if q.errors && v.get("err").is_none() {
                return false;
            }
            if q.slow && v["cat"].as_str() != Some("slow") {
                return false;
            }
            true
        })
        .cloned()
        .collect()
}

fn short(v: &Value) -> String {
    let s = match v {
        Value::String(s) => {
            if s.contains(' ') || s.is_empty() {
                format!("\"{s}\"")
            } else {
                s.clone()
            }
        }
        other => other.to_string(),
    };
    if s.chars().count() > 120 {
        format!("{}…", s.chars().take(119).collect::<String>())
    } else {
        s
    }
}

/// One event as an aligned line of plain text.
pub fn line_text(v: &Value) -> String {
    let t = v["t"].as_str().unwrap_or("");
    let clock = t.get(11..23).unwrap_or(t);
    let mut rest = String::new();
    if let Value::Object(m) = v {
        for (k, val) in m {
            if matches!(k.as_str(), "t" | "mono" | "run" | "cat" | "ev") {
                continue;
            }
            rest.push(' ');
            rest.push_str(k);
            rest.push('=');
            rest.push_str(&short(val));
        }
    }
    format!(
        "{clock} {:<8} {:<16}{rest}",
        v["cat"].as_str().unwrap_or(""),
        v["ev"].as_str().unwrap_or("")
    )
}

/// The few lines that say what is in `events`.
pub fn summary_text(events: &[Value]) -> String {
    let mut by: std::collections::BTreeMap<&str, u64> = Default::default();
    let mut errors = 0;
    let mut slowest: Option<(u64, String)> = None;
    for v in events {
        *by.entry(v["cat"].as_str().unwrap_or("?")).or_default() += 1;
        if v.get("err").is_some() {
            errors += 1;
        }
        if v["cat"] == "call"
            && let Some(ms) = v["ms"].as_u64()
            && slowest.as_ref().is_none_or(|(m, _)| ms > *m)
        {
            slowest = Some((ms, v["call"].as_str().unwrap_or("?").to_string()));
        }
    }
    let cats: Vec<String> = by.iter().map(|(c, n)| format!("{c} {n}")).collect();
    let mut s = format!(
        "{} events ({}); {} with errors",
        events.len(),
        cats.join(", "),
        errors
    );
    if let Some((ms, name)) = slowest {
        s.push_str(&format!("; slowest call {name} {ms} ms"));
    }
    s
}

/// `mimir devlog [options]`.
pub fn command(args: &[String]) -> i32 {
    let q = match parse_args(args) {
        Ok(q) => q,
        Err(e) => {
            eprintln!("{e}");
            eprintln!(
                "devlog [--since 10m] [--cat talk,model] [--task id] [--chat id] [--errors] [--slow] [--raw] [--path] [--last N]"
            );
            return 2;
        }
    };
    let d = dir();
    if q.path {
        println!("{}", d.display());
        return 0;
    }
    let now = unix_ms();
    let all = read_since(&d, q.since_ms.map(|s| now.saturating_sub(s)));
    if all.is_empty() {
        println!("The developer log is empty or off (Settings › Labs › Developer log).");
        return 0;
    }
    let picked = select(&all, &q, now);
    let from = picked.len().saturating_sub(q.last);
    if q.raw {
        for v in &picked[from..] {
            println!("{v}");
        }
        return 0;
    }
    println!("{}", summary_text(&picked));
    for v in &picked[from..] {
        println!("{}", line_text(v));
    }
    0
}

/// Deletes the log's files (switching it off).
pub fn delete_files(dir: &Path) {
    for e in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        if e.file_name().to_string_lossy().ends_with(".jsonl") {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("devlog-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    fn cfg(d: &Path) -> Config {
        Config::service(d.to_path_buf())
    }

    fn raw(cat: &'static str, ev: &'static str, f: Vec<(&'static str, Value)>) -> Raw {
        Raw::new(cat, ev, f)
    }

    fn lines(d: &Path, prefix: &str) -> Vec<Value> {
        let mut out = vec![];
        for e in std::fs::read_dir(d).unwrap().flatten() {
            if e.file_name().to_string_lossy().starts_with(prefix) {
                for l in std::fs::read_to_string(e.path()).unwrap().lines() {
                    out.push(serde_json::from_str(l).unwrap());
                }
            }
        }
        out
    }

    #[test]
    fn times_go_round_trip() {
        let ms = 1_795_000_000_123;
        assert_eq!(parse_iso(&iso(ms)), Some(ms));
        assert_eq!(iso(0), "1970-01-01T00:00:00.000Z");
        assert_eq!(iso(951_782_400_000), "2000-02-29T00:00:00.000Z");
    }

    #[test]
    fn every_line_has_the_stable_keys_and_files_are_private() {
        use std::os::unix::fs::PermissionsExt;
        let d = tmp("keys");
        let mut w = Writer::new("run1", cfg(&d));
        w.event(raw(
            "call",
            "done",
            vec![("task", "t1".into()), ("ms", 12.into())],
        ));
        w.flush(true);
        let l = &lines(&d, "devlog")[0];
        for k in ["t", "mono", "run", "cat", "ev", "task", "ms"] {
            assert!(l.get(k).is_some(), "{k} missing in {l}");
        }
        assert_eq!(l["run"], "run1");
        assert_eq!(
            std::fs::metadata(&d).unwrap().permissions().mode() & 0o777,
            0o700
        );
        let f = d.join("devlog-1.jsonl");
        assert_eq!(
            std::fs::metadata(f).unwrap().permissions().mode() & 0o777,
            0o600
        );
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn files_roll_at_the_size_and_only_the_newest_are_kept() {
        let d = tmp("roll");
        let mut c = cfg(&d);
        c.main_bytes = 600;
        c.main_files = 3;
        let mut w = Writer::new("r", c);
        for i in 0..60 {
            w.event(raw(
                "task",
                "start",
                vec![("n", i.into()), ("task", format!("t{i}").into())],
            ));
        }
        w.flush(true);
        let files: Vec<_> = std::fs::read_dir(&d)
            .unwrap()
            .flatten()
            .filter(|e| e.file_name().to_string_lossy().starts_with("devlog-"))
            .collect();
        assert!(files.len() <= 3, "{}", files.len());
        assert!(files.iter().all(|f| f.metadata().unwrap().len() <= 700));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn files_older_than_the_age_are_removed() {
        let d = tmp("age");
        std::fs::create_dir_all(&d).unwrap();
        let old = d.join("devlog-1.jsonl");
        std::fs::write(&old, "{}\n").unwrap();
        let f = std::fs::File::options().write(true).open(&old).unwrap();
        f.set_modified(std::time::SystemTime::now() - Duration::from_secs(15 * 86_400))
            .unwrap();
        let imp = d.join("important-1.jsonl");
        std::fs::write(&imp, "{}\n").unwrap();
        let f = std::fs::File::options().write(true).open(&imp).unwrap();
        f.set_modified(std::time::SystemTime::now() - Duration::from_secs(20 * 86_400))
            .unwrap();
        let mut w = Writer::new("r", cfg(&d));
        w.event(raw("service", "x", vec![]));
        w.flush(true);
        // The old file went (a new one of the same name may have started).
        assert!(
            !std::fs::read_to_string(&old)
                .unwrap_or_default()
                .contains("{}\n"),
            "a 15-day-old main file stayed (14 days)"
        );
        assert!(imp.exists(), "a 20-day-old important file went (30 days)");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn errors_crashes_slow_and_headers_also_go_to_the_important_file() {
        let d = tmp("imp");
        let mut w = Writer::new("r", cfg(&d));
        w.event(raw("talk", "answer", vec![("ms", 5.into())]));
        w.event(raw("model", "wake", vec![("err", "no answer".into())]));
        w.event(raw("slow", "service-message", vec![("ms", 80.into())]));
        w.event(raw("service", "start", vec![]));
        w.event(raw("crash", "panic", vec![("err", "boom".into())]));
        w.flush(true);
        let imp: Vec<String> = lines(&d, "important")
            .iter()
            .map(|v| v["ev"].as_str().unwrap().to_string())
            .collect();
        assert_eq!(imp, vec!["wake", "service-message", "start", "panic"]);
        assert_eq!(lines(&d, "devlog").len(), 5);
        // Read together, a line both files hold is shown once.
        assert_eq!(read_all(&d).len(), 5);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_flood_of_ordinary_events_cannot_push_out_the_evidence() {
        let d = tmp("flood");
        let mut c = cfg(&d);
        c.main_bytes = 500;
        c.main_files = 2;
        let mut w = Writer::new("r", c);
        w.event(raw("model", "wake", vec![("err", "the evidence".into())]));
        for i in 0..200 {
            w.event(raw(
                "task",
                "start",
                vec![("n", i.into()), ("task", format!("t{i}").into())],
            ));
        }
        w.flush(true);
        let main = lines(&d, "devlog");
        assert!(
            !main.iter().any(|v| v["err"] == "the evidence"),
            "still in the main log"
        );
        assert!(
            lines(&d, "important")
                .iter()
                .any(|v| v["err"] == "the evidence")
        );
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn identical_events_collapse_into_a_repeat_count() {
        let d = tmp("repeat");
        let mut w = Writer::new("r", cfg(&d));
        for _ in 0..5 {
            w.event(raw("link", "retry", vec![("phone", "p1".into())]));
        }
        w.event(raw("link", "up", vec![("phone", "p1".into())]));
        w.flush(true);
        let l = lines(&d, "devlog");
        let evs: Vec<&str> = l.iter().map(|v| v["ev"].as_str().unwrap()).collect();
        assert_eq!(evs, vec!["retry", "retry", "up"], "{l:?}");
        assert_eq!(l[1]["repeat"], 4);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn the_summary_counts_categories_errors_and_the_slowest_call() {
        let d = tmp("summary");
        let mut w = Writer::new("r", cfg(&d));
        w.event(raw(
            "call",
            "done",
            vec![("call", "Send".into()), ("ms", 30.into())],
        ));
        w.event(raw(
            "call",
            "done",
            vec![
                ("call", "NewTask".into()),
                ("ms", 90.into()),
                ("task", "a".into()),
            ],
        ));
        w.event(raw("model", "wake", vec![("err", "x".into())]));
        w.summary();
        w.flush(true);
        let s = lines(&d, "devlog")
            .into_iter()
            .find(|v| v["ev"] == "summary")
            .unwrap();
        assert_eq!(s["counts"]["call"], 2);
        assert_eq!(s["errors"], 1);
        assert_eq!(s["slowest_ms"], 90);
        assert_eq!(s["slowest_call"], "NewTask");
        // Nothing happened since: no second summary.
        let before = lines(&d, "devlog").len();
        w.summary();
        w.flush(true);
        assert_eq!(lines(&d, "devlog").len(), before);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_log_that_fills_faster_than_a_file_an_hour_says_log_busy_once() {
        let d = tmp("busy");
        let mut c = cfg(&d);
        c.main_bytes = 400;
        c.main_files = 50;
        let mut w = Writer::new("r", c);
        for i in 0..100 {
            w.event(raw(
                "talk",
                "piece",
                vec![("n", i.into()), ("chat", format!("c{i}").into())],
            ));
        }
        w.flush(true);
        let busy: Vec<Value> = read_all(&d)
            .into_iter()
            .filter(|v| v["ev"] == "log-busy")
            .collect();
        assert_eq!(busy.len(), 1);
        assert_eq!(busy[0]["flooding"], "talk");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn the_cli_filters_by_time_category_task_chat_errors_and_slow() {
        let now = 1_795_000_000_000u64;
        let ev = |ago: u64, cat: &str, f: Value| {
            let mut v = serde_json::json!({"t": iso(now - ago), "mono": 1, "run": "r", "cat": cat, "ev": "e"});
            v.as_object_mut()
                .unwrap()
                .extend(f.as_object().unwrap().clone());
            v
        };
        let all = vec![
            ev(3_600_000, "talk", serde_json::json!({"chat": "c1"})),
            ev(60_000, "talk", serde_json::json!({"chat": "c1", "ms": 5})),
            ev(50_000, "model", serde_json::json!({"err": "no"})),
            ev(40_000, "task", serde_json::json!({"task": "t9"})),
            ev(30_000, "slow", serde_json::json!({"ms": 80})),
        ];
        let q =
            |a: &[&str]| parse_args(&a.iter().map(|s| s.to_string()).collect::<Vec<_>>()).unwrap();
        assert_eq!(select(&all, &q(&["--since", "10m"]), now).len(), 4);
        assert_eq!(select(&all, &q(&["--cat", "talk,model"]), now).len(), 3);
        assert_eq!(select(&all, &q(&["--task", "t9"]), now).len(), 1);
        assert_eq!(select(&all, &q(&["--chat", "c1"]), now).len(), 2);
        assert_eq!(select(&all, &q(&["--errors"]), now).len(), 1);
        assert_eq!(select(&all, &q(&["--slow"]), now).len(), 1);
        assert!(parse_args(&["--since".into(), "soon".into()]).is_err());
        assert!(line_text(&all[2]).contains("model"));
        assert!(summary_text(&all).starts_with("5 events"));
    }

    #[test]
    fn a_slow_frame_is_logged_with_what_was_drawn_and_a_quick_one_is_not() {
        assert!(FrameTimer::new("Settings").slow_ms().is_none());
        let slow = FrameTimer {
            start: Instant::now() - Duration::from_millis(80),
            what: "Settings".into(),
        };
        assert!(slow.slow_ms().unwrap() >= 80);
    }

    #[test]
    fn the_name_of_a_call_is_its_first_word() {
        #[derive(Debug)]
        #[allow(dead_code)]
        enum C {
            Send { text: String },
            Stop(u8),
            Projects,
        }
        assert_eq!(
            name_of(&C::Send {
                text: "secret words".into()
            }),
            "Send"
        );
        assert_eq!(name_of(&C::Stop(1)), "Stop");
        assert_eq!(name_of(&C::Projects), "Projects");
    }

    // The log of this process is one thing: everything that touches it is
    // in this one test.
    #[test]
    fn secrets_are_hidden_inside_json_after_equals_and_on_new_lines() {
        let key = "sk-ant-api03-abcDEF123";
        for s in [
            format!("Here:\n{key}"),
            format!("{{\"api_key\":\"{key}\"}}"),
            "token=ghp_abcdef0123456789".to_string(),
            "(key: ghp_abcdef0123456789)".to_string(),
        ] {
            let out = scrub(&s);
            assert!(
                !out.contains("abcDEF123") && !out.contains("ghp_"),
                "{s} -> {out}"
            );
            assert!(out.contains("[hidden]"), "{out}");
        }
        assert_eq!(
            scrub("A normal line: 2 files, 3 ms."),
            "A normal line: 2 files, 3 ms."
        );
        // Nested values are scrubbed too, and a secret key name inside them.
        let v = serde_json::json!({"a": ["x sk-ant-zzz"], "b": {"password": "hunter2"}});
        let c = clean(&v).to_string();
        assert!(!c.contains("sk-ant-zzz") && !c.contains("hunter2"), "{c}");
    }

    #[test]
    fn a_collapsed_repeat_still_counts_as_an_error() {
        let d = tmp("repeat-err");
        let mut w = Writer::new("r1", cfg(&d));
        for _ in 0..3 {
            w.event(Raw::new(
                "call",
                "done",
                vec![("call", "X".into()), ("err", "boom".into())],
            ));
        }
        w.event(Raw::new("call", "done", vec![("call", "Y".into())]));
        w.flush(false);
        let all = read_all(&d);
        let rep: Vec<&Value> = all.iter().filter(|v| v.get("repeat").is_some()).collect();
        assert_eq!(rep.len(), 1, "{all:?}");
        assert_eq!(rep[0]["err"], "boom");
    }

    #[test]
    fn the_process_log_hides_secrets_and_whispers_and_obeys_its_switches_and_is_cheap() {
        let d = tmp("global");
        init("g1", cfg(&d), true, false);
        // Secrets: a key by name, and a token by its look, never appear.
        log(
            "call",
            "done",
            vec![
                ("token", "abc123secret".into()),
                ("api_key", "sk-ant-zzz".into()),
                ("note", "used Bearer eyJhbGciOi.secret.part here".into()),
                ("ms", 3.into()),
            ],
        );
        // Whisper: nothing that names its task, and one note.
        mark_whisper("w1");
        log(
            "task",
            "start",
            vec![("task", "w1".into()), ("ms", 1.into())],
        );
        log("task", "exit", vec![("task", "w1".into())]);
        // A normal chat's text: only with the switch.
        log_text(
            "talk",
            "answer",
            vec![("chat", "c1".into())],
            "off: no text",
        );
        set_switches(true, true);
        log_text(
            "talk",
            "answer",
            vec![("chat", "c2".into())],
            &"x".repeat(5000),
        );
        flush();
        let all = read_all(&d);
        let text = std::fs::read_to_string(d.join("devlog-1.jsonl")).unwrap();
        assert!(
            !text.contains("abc123secret") && !text.contains("sk-ant-zzz"),
            "{text}"
        );
        assert!(!text.contains("eyJhbGciOi"), "{text}");
        assert!(text.contains("[hidden]"));
        assert!(
            !all.iter().any(|v| v["task"] == "w1"),
            "a Whisper was logged"
        );
        assert_eq!(
            all.iter()
                .filter(|v| v["note"] == "Whisper: not logged")
                .count(),
            1
        );
        let c1 = all.iter().find(|v| v["chat"] == "c1").unwrap();
        assert!(c1.get("text").is_none());
        let c2 = all.iter().find(|v| v["chat"] == "c2").unwrap();
        assert_eq!(c2["text"].as_str().unwrap().chars().count(), 4000);
        // The message-took hook: only over 50 ms.
        message_took("Call", Some("Send"), 20);
        message_took("Call", Some("Send"), 80);
        flush();
        let slow: Vec<Value> = read_all(&d)
            .into_iter()
            .filter(|v| v["cat"] == "slow")
            .collect();
        assert_eq!(slow.len(), 1);
        assert_eq!(slow[0]["call"], "Send");
        assert!(lines(&d, "important").iter().any(|v| v["cat"] == "slow"));
        // Cost of one logging call (the build is a debug one: the limit is
        // the brief's 50 microseconds, not a release figure).
        let n = 2000;
        let t = Instant::now();
        for i in 0..n {
            log(
                "link",
                "bytes",
                vec![("phone", "p".into()), ("bytes", i.into())],
            );
        }
        let per = t.elapsed() / n;
        assert!(per < Duration::from_micros(50), "{per:?} a call");
        // Switching it off deletes the files; on starts again.
        flush();
        assert!(std::fs::read_dir(&d).unwrap().count() > 0);
        set_switches(false, false);
        assert!(
            std::fs::read_dir(&d)
                .map(|r| r
                    .flatten()
                    .all(|e| !e.file_name().to_string_lossy().ends_with(".jsonl")))
                .unwrap_or(true),
            "the files stayed after switching off"
        );
        log("service", "while-off", vec![]);
        flush();
        assert!(read_all(&d).is_empty());
        set_switches(true, false);
        log("service", "on-again", vec![]);
        flush();
        assert!(read_all(&d).iter().any(|v| v["ev"] == "on-again"));
        shutdown();
        let _ = std::fs::remove_dir_all(&d);
    }
}
