//! The one writer thread: changes are kept as "dirty" whole files and
//! written about once a second ([`EVERY`]), each with an atomic write. A
//! write that fails stays dirty and is tried again on the next tick, so a
//! full disk loses nothing once space is freed. Callers never wait.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender, SyncSender, channel, sync_channel};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

/// How long changes gather before they're written.
pub const EVERY: Duration = Duration::from_secs(1);

/// Where the bytes go (the disk; checks give one that fails on purpose).
pub trait Sink: Send + 'static {
    fn write(&mut self, path: &Path, bytes: &[u8]) -> std::io::Result<()>;
}

/// The real disk, with atomic whole-file writes.
pub struct Disk;

impl Sink for Disk {
    fn write(&mut self, path: &Path, bytes: &[u8]) -> std::io::Result<()> {
        crate::atomic::write(path, bytes)
    }
}

enum Cmd {
    Put(PathBuf, Vec<u8>),
    Flush(SyncSender<Vec<String>>),
}

#[derive(Default)]
struct Status {
    /// Files whose last write failed (still dirty).
    failing: AtomicUsize,
    last_error: Mutex<Option<String>>,
}

/// A handle on the writer thread (cheap to clone).
#[derive(Clone)]
pub struct Writer {
    tx: Sender<Cmd>,
    status: Arc<Status>,
}

impl Writer {
    /// A writer to the disk, writing every `every`.
    pub fn spawn(every: Duration) -> Writer {
        Writer::with_sink(every, Disk)
    }

    /// A writer to `sink`.
    pub fn with_sink(every: Duration, sink: impl Sink) -> Writer {
        let (tx, rx) = channel();
        let status = Arc::new(Status::default());
        let st = status.clone();
        std::thread::Builder::new()
            .name("store-writer".into())
            .spawn(move || run(rx, every, sink, &st))
            .expect("the store's writer thread");
        Writer { tx, status }
    }

    /// The process's one writer to the disk.
    pub fn global() -> &'static Writer {
        static W: OnceLock<Writer> = OnceLock::new();
        W.get_or_init(|| Writer::spawn(EVERY))
    }

    /// Marks `path` to be written with `bytes` (the newest bytes win).
    /// Never waits.
    pub fn put(&self, path: &Path, bytes: Vec<u8>) {
        let _ = self.tx.send(Cmd::Put(path.to_path_buf(), bytes));
    }

    /// Writes everything dirty now and waits for it (at shutdown, and in
    /// checks). Err with what failed; the failures stay dirty.
    pub fn flush(&self, wait: Duration) -> Result<(), String> {
        let (tx, rx) = sync_channel(1);
        self.tx
            .send(Cmd::Flush(tx))
            .map_err(|_| "the writer stopped".to_string())?;
        match rx.recv_timeout(wait) {
            Ok(errs) if errs.is_empty() => Ok(()),
            Ok(errs) => Err(errs.join("; ")),
            Err(_) => Err("the writer didn't answer".into()),
        }
    }

    /// How many files are waiting after a failed write.
    pub fn failing(&self) -> usize {
        self.status.failing.load(Ordering::Relaxed)
    }

    /// The newest write error, if a write is failing.
    pub fn last_error(&self) -> Option<String> {
        if self.failing() == 0 {
            return None;
        }
        self.status
            .last_error
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }
}

fn write_all(
    dirty: &mut BTreeMap<PathBuf, Vec<u8>>,
    sink: &mut impl Sink,
    st: &Status,
) -> Vec<String> {
    let mut errs = vec![];
    dirty.retain(|path, bytes| match sink.write(path, bytes) {
        Ok(()) => false,
        Err(e) => {
            errs.push(format!("{}: {e}", path.display()));
            true
        }
    });
    st.failing.store(dirty.len(), Ordering::Relaxed);
    if let Some(e) = errs.last() {
        *st.last_error.lock().unwrap_or_else(|e| e.into_inner()) = Some(e.clone());
    }
    errs
}

fn run(rx: Receiver<Cmd>, every: Duration, mut sink: impl Sink, st: &Status) {
    let mut dirty: BTreeMap<PathBuf, Vec<u8>> = BTreeMap::new();
    let mut due: Option<Instant> = None;
    loop {
        // Asleep with nothing dirty: woken only by the next change.
        let wait = due.map_or(Duration::from_secs(3600), |d| {
            d.saturating_duration_since(Instant::now())
        });
        match rx.recv_timeout(wait) {
            Ok(Cmd::Put(p, b)) => {
                dirty.insert(p, b);
                due.get_or_insert_with(|| Instant::now() + every);
                continue;
            }
            Ok(Cmd::Flush(reply)) => {
                let errs = write_all(&mut dirty, &mut sink, st);
                let _ = reply.send(errs);
            }
            Err(RecvTimeoutError::Timeout) => {
                if due.is_some_and(|d| Instant::now() >= d) {
                    write_all(&mut dirty, &mut sink, st);
                }
            }
            Err(RecvTimeoutError::Disconnected) => {
                write_all(&mut dirty, &mut sink, st);
                return;
            }
        }
        due = (!dirty.is_empty()).then(|| Instant::now() + every);
    }
}
