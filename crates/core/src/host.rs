//! A small host per job, so restarts never kill work (Stage 1 design, 4.1).
//! The service starts `mimir host <key>` in a systemd scope of
//! its own; the host starts the real child (Claude Code for a task) and
//! owns its input and output, so the service can stop, restart or update
//! while the child carries on. What the child prints goes to numbered 4 MB
//! chunk files on disk; the service reads them from the offset it saved and
//! talks to the host over a socket only the same user can use.
//!
//! Files, per job `<key>`:
//! *   `<state>/hosts/<key>.json`: the record (the job, the host's and the
//!     child's process ids and start times, the child's exit);
//! *   `<state>/hosts/<key>.out.<n>`: the child's output, bytes
//!     `n·4 MB ..`;
//! *   `<state>/hosts/<key>.log`: the host's own few lines;
//! *   `<runtime>/hosts/<key>.sock`: its socket (0600, same user only).

use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Read, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// The host protocol this build speaks, and the ones it still knows.
pub const PROTO: u32 = 1;
pub const KNOWN: [u32; 2] = [1, 2];
/// Output chunk size.
pub const CHUNK: u64 = 4 * 1024 * 1024;

/// What the host is asked to run.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct Job {
    /// "task" (a Claude Code task); later "phone" and "keeper".
    pub kind: String,
    pub task_id: String,
    pub program: PathBuf,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    pub env: Vec<(String, String)>,
    /// Variables the child must not get.
    pub env_remove: Vec<String>,
}

/// A host's record.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct Record {
    pub proto: u32,
    pub job: Job,
    pub host_pid: i32,
    pub host_started: Option<u64>,
    pub child_pid: Option<i32>,
    /// The child ended (its exit code, None by a signal), and all it
    /// printed is on disk.
    pub exit: Option<Option<i32>>,
    /// The service saved up to here.
    pub out_offset_saved: u64,
}

pub fn state_dir() -> PathBuf {
    std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .unwrap_or_else(|| crate::paths::home().join(".local/state"))
        .join("mimir/hosts")
}

pub fn socket_dir() -> PathBuf {
    crate::sockets::runtime_dir().join("hosts")
}

fn record_path(key: &str) -> PathBuf {
    state_dir().join(format!("{key}.json"))
}

fn chunk_path(key: &str, n: u64) -> PathBuf {
    state_dir().join(format!("{key}.out.{n}"))
}

pub fn socket_path(key: &str) -> PathBuf {
    socket_dir().join(format!("{key}.sock"))
}

fn private_dir(d: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::create_dir_all(d)?;
    std::fs::set_permissions(d, std::fs::Permissions::from_mode(0o700))
}

pub fn read_record(key: &str) -> Option<Record> {
    serde_json::from_str(&std::fs::read_to_string(record_path(key)).ok()?).ok()
}

fn write_record(key: &str, r: &Record) {
    let p = record_path(key);
    let tmp = p.with_extension("json.tmp");
    if std::fs::write(&tmp, serde_json::to_vec(r).unwrap_or_default()).is_ok() {
        let _ = std::fs::rename(tmp, p);
    }
}

/// A process's start time (field 22 of its stat), to tell it from a later
/// process with the same id.
pub fn started_at(pid: i32) -> Option<u64> {
    let s = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    let (_, rest) = s.rsplit_once(')')?;
    rest.split_whitespace().nth(19)?.parse().ok()
}

fn alive(pid: i32) -> bool {
    std::fs::read_to_string(format!("/proc/{pid}/stat"))
        .ok()
        .and_then(|s| {
            s.rsplit_once(')')
                .map(|(_, r)| r.trim_start().chars().next())
        })
        .flatten()
        .is_some_and(|c| c != 'Z' && c != 'X')
}

/// Whether the host of this record still runs.
pub fn host_alive(r: &Record) -> bool {
    alive(r.host_pid) && started_at(r.host_pid) == r.host_started && r.host_started.is_some()
}

/// How hosts are started on this PC: "scope" (systemd-run, the normal
/// way), "plain" (a detached process; checks), or "off" (no hosts: work
/// runs inside the service, as before Stage 1).
fn mode() -> String {
    // A Flatpak has no systemd of its own: hosts are plain processes
    // inside the sandbox.
    let default = if crate::flatpak::active() {
        "plain"
    } else {
        "scope"
    };
    std::env::var("MIMIR_HOSTS").unwrap_or_else(|_| default.into())
}

/// Whether jobs run in hosts.
pub fn enabled() -> bool {
    mode() != "off"
}

/// A fresh key for a task's next run.
pub fn new_key(task_id: &str) -> String {
    let ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    format!("t{}-{ms}", crate::needs::short_hash(task_id))
}

/// Starts a host for `job` under `key` and does not wait for it: the child
/// to hand to [`await_link`].
pub fn spawn(key: &str, job: &Job) -> std::io::Result<std::process::Child> {
    use std::process::{Command, Stdio};
    private_dir(&state_dir())?;
    private_dir(&socket_dir())?;
    write_record(
        key,
        &Record {
            proto: PROTO,
            job: job.clone(),
            ..Default::default()
        },
    );
    let me = crate::paths::own_program();
    let mut cmd = if mode() == "plain" {
        let mut c = Command::new(&me);
        c.args(["host", key]);
        c
    } else {
        let uid = unsafe { libc::getuid() };
        let mut c = Command::new("systemd-run");
        c.args(["--user", "--scope", "--quiet", "--collect"])
            .arg("--slice=mimir-sessions.slice")
            .arg(format!("--unit=mimir-host-{key}"))
            .arg("--")
            .arg(&me)
            .args(["host", key])
            .env("XDG_RUNTIME_DIR", format!("/run/user/{uid}"))
            .env(
                "DBUS_SESSION_BUS_ADDRESS",
                format!("unix:path=/run/user/{uid}/bus"),
            );
        // The host itself needs the service's runtime folder.
        if let Some(r) = std::env::var_os("XDG_RUNTIME_DIR") {
            c.env("MIMIR_HOST_RUNTIME", r);
        }
        c
    };
    crate::process::without_systemd(&mut cmd);
    std::os::unix::process::CommandExt::process_group(&mut cmd, 0);
    cmd.stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
}

/// Waits (up to 5 s) until the host started by [`spawn`] answers on its
/// socket. Slow: never call it on the service's thread. On failure the
/// host is ended; its record is left for the caller to remove.
pub fn await_link(key: &str, mut child: std::process::Child) -> std::io::Result<Link> {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if let Ok(l) = Link::connect(key) {
            // systemd-run becomes the host; a plain host is our child:
            // either way, collected when it ends.
            std::thread::spawn(move || {
                let _ = child.wait();
            });
            return Ok(l);
        }
        if child.try_wait().ok().flatten().is_some() || Instant::now() > deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(std::io::Error::other("the host didn't start"));
        }
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// Starts a host for `job` under `key`, and waits until its socket answers.
pub fn start(key: &str, job: &Job) -> std::io::Result<Link> {
    let r = spawn(key, job).and_then(|c| await_link(key, c));
    if r.is_err() {
        let _ = std::fs::remove_file(record_path(key));
    }
    r
}

/// The service's line to one host.
pub struct Link {
    pub key: String,
    stream: UnixStream,
    pub proto: u32,
}

impl Link {
    /// Connects to a running host (after a restart too).
    pub fn connect(key: &str) -> std::io::Result<Link> {
        let s = UnixStream::connect(socket_path(key))?;
        s.set_read_timeout(Some(Duration::from_secs(2)))?;
        let mut hello = String::new();
        BufReader::new(s.try_clone()?).read_line(&mut hello)?;
        let proto: u32 = hello
            .trim()
            .strip_prefix("mimir-host ")
            .and_then(|n| n.parse().ok())
            .ok_or_else(|| std::io::Error::other("not a host"))?;
        if !KNOWN.contains(&proto) {
            return Err(std::io::Error::other("a host this build doesn't know"));
        }
        Ok(Link {
            key: key.to_string(),
            stream: s,
            proto,
        })
    }

    fn say(&mut self, v: serde_json::Value) -> std::io::Result<()> {
        writeln!(self.stream, "{v}")?;
        self.stream.flush()
    }

    /// One line to the child's input.
    pub fn send_line(&mut self, line: &str) -> std::io::Result<()> {
        self.say(serde_json::json!({ "send": line }))
    }

    pub fn close_input(&mut self) -> std::io::Result<()> {
        self.say(serde_json::json!({ "close": true }))
    }

    /// A signal to the child (SIGINT ends Claude Code's turn).
    pub fn signal(&mut self, sig: i32) -> std::io::Result<()> {
        self.say(serde_json::json!({ "signal": sig }))
    }

    /// Ends the child and everything it started.
    pub fn kill(&mut self) -> std::io::Result<()> {
        self.say(serde_json::json!({ "kill": true }))
    }

    /// Everything before `offset` is saved: earlier chunks can go.
    pub fn saved(&mut self, offset: u64) -> std::io::Result<()> {
        self.say(serde_json::json!({ "saved": offset }))
    }

    /// The child's end was read: the host can tidy up and go.
    pub fn collected(&mut self) -> std::io::Result<()> {
        self.say(serde_json::json!({ "collected": true }))
    }
}

/// Reads a host's output from `offset` on as it grows: complete lines,
/// and the offset after them. None while nothing new is there.
pub fn read_from(key: &str, offset: u64) -> Option<(Vec<String>, u64)> {
    let mut buf = vec![];
    let mut at = offset;
    loop {
        let n = at / CHUNK;
        let Ok(mut f) = std::fs::File::open(chunk_path(key, n)) else {
            break;
        };
        use std::io::Seek;
        if f.seek(std::io::SeekFrom::Start(at - n * CHUNK)).is_err() {
            break;
        }
        let mut more = vec![];
        let _ = f.read_to_end(&mut more);
        if more.is_empty() {
            break;
        }
        at += more.len() as u64;
        buf.extend(more);
        if !at.is_multiple_of(CHUNK) {
            break;
        }
    }
    let end = buf.iter().rposition(|b| *b == b'\n')? + 1;
    let lines = String::from_utf8_lossy(&buf[..end])
        .lines()
        .map(str::to_string)
        .collect();
    Some((lines, offset + end as u64))
}

/// The total bytes the host has written.
pub fn written(key: &str) -> u64 {
    let mut n = 0;
    let mut total = 0;
    while let Ok(m) = std::fs::metadata(chunk_path(key, n)) {
        total = n * CHUNK + m.len();
        n += 1;
    }
    total
}

/// Removes a finished host's files.
pub fn remove_files(key: &str) {
    let mut n = 0;
    while std::fs::remove_file(chunk_path(key, n)).is_ok() || n < 1 {
        n += 1;
    }
    for p in [
        record_path(key),
        state_dir().join(format!("{key}.log")),
        socket_path(key),
    ] {
        let _ = std::fs::remove_file(p);
    }
}

/// Leftovers older than a day (a host gone without tidying up).
pub fn tidy_old() {
    let Ok(d) = std::fs::read_dir(state_dir()) else {
        return;
    };
    let day = Duration::from_secs(24 * 3600);
    for e in d.flatten() {
        let p = e.path();
        let Some(name) = p.file_name().map(|n| n.to_string_lossy().to_string()) else {
            continue;
        };
        let Some(key) = name.strip_suffix(".json") else {
            continue;
        };
        let old = e
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|a| a > day);
        let gone = read_record(key).is_none_or(|r| !host_alive(&r));
        if old && gone {
            remove_files(key);
        }
    }
}

// ---------- The host itself ----------

fn log(key: &str, what: &str) {
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(state_dir().join(format!("{key}.log")))
    {
        let _ = writeln!(f, "{} {what}", crate::needs::now());
    }
}

/// How long a host waits, after its child ended, for the service to read
/// the end (`STATION_HOST_LINGER_SECS` for checks).
fn linger() -> Duration {
    std::env::var("STATION_HOST_LINGER_SECS")
        .ok()
        .and_then(|s| s.parse().ok())
        .map(Duration::from_secs)
        .unwrap_or(Duration::from_secs(600))
}

/// `mimir host <key>`: runs the job in `<key>.json` until it ends
/// and its end is collected.
pub fn main(args: &[String]) -> i32 {
    let Some(key) = args.first().cloned() else {
        eprintln!("usage: mimir host <key>");
        return 2;
    };
    if let Some(r) = std::env::var_os("MIMIR_HOST_RUNTIME") {
        // SAFETY: before any thread starts.
        unsafe { std::env::set_var("XDG_RUNTIME_DIR", r) };
    }
    // Its own session: the service's end can't reach it through a terminal
    // or a signal to its group.
    unsafe { libc::setsid() };
    match run(&key) {
        Ok(()) => 0,
        Err(e) => {
            log(&key, &format!("stopped: {e}"));
            1
        }
    }
}

fn run(key: &str) -> std::io::Result<()> {
    use std::os::unix::process::CommandExt;
    use std::process::{Command, Stdio};
    let mut rec = read_record(key).ok_or_else(|| std::io::Error::other("no record"))?;
    let sock = socket_path(key);
    let _ = std::fs::remove_file(&sock);
    let listener = UnixListener::bind(&sock)?;
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&sock, std::fs::Permissions::from_mode(0o600))?;
    }
    let job = rec.job.clone();
    let mut cmd = Command::new(&job.program);
    cmd.args(&job.args)
        .current_dir(&job.cwd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .process_group(0);
    crate::process::without_systemd(&mut cmd);
    for k in &job.env_remove {
        cmd.env_remove(k);
    }
    for (k, v) in &job.env {
        cmd.env(k, v);
    }
    // SAFETY: prctl is async-signal-safe: the child never runs on with
    // nobody holding its pipes.
    unsafe {
        cmd.pre_exec(|| {
            libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM);
            Ok(())
        });
    }
    let mut child = cmd.spawn()?;
    let pid = child.id() as i32;
    rec.host_pid = std::process::id() as i32;
    rec.host_started = started_at(rec.host_pid);
    rec.child_pid = Some(pid);
    write_record(key, &rec);
    log(key, &format!("started {} ({pid})", job.program.display()));
    let stdin = Arc::new(Mutex::new(child.stdin.take()));
    let mut out = child.stdout.take().expect("piped");
    // The child's output, to the chunk files.
    let k = key.to_string();
    let copier = std::thread::spawn(move || {
        let mut at = 0u64;
        let mut buf = vec![0u8; 64 * 1024];
        let mut file: Option<(u64, std::fs::File)> = None;
        loop {
            let n = match out.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => n,
            };
            let mut data = &buf[..n];
            while !data.is_empty() {
                let c = at / CHUNK;
                if file.as_ref().is_none_or(|(fc, _)| *fc != c) {
                    use std::os::unix::fs::OpenOptionsExt;
                    match std::fs::OpenOptions::new()
                        .create(true)
                        .append(true)
                        .mode(0o600)
                        .open(chunk_path(&k, c))
                    {
                        Ok(f) => file = Some((c, f)),
                        Err(_) => return,
                    }
                }
                let room = ((c + 1) * CHUNK - at) as usize;
                let take = room.min(data.len());
                if let Some((_, f)) = &mut file
                    && f.write_all(&data[..take]).is_err()
                {
                    return;
                }
                at += take as u64;
                data = &data[take..];
            }
        }
    });
    let collected = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let saved = Arc::new(Mutex::new(0u64));
    // The service's commands.
    {
        let stdin = stdin.clone();
        let collected = collected.clone();
        let saved = saved.clone();
        let k = key.to_string();
        let me = unsafe { libc::getuid() };
        std::thread::spawn(move || {
            for c in listener.incoming().flatten() {
                if peer_uid(&c) != Some(me) {
                    continue;
                }
                let (stdin, collected, saved, k) =
                    (stdin.clone(), collected.clone(), saved.clone(), k.clone());
                std::thread::spawn(move || serve(c, pid, &stdin, &collected, &saved, &k));
            }
        });
    }
    let status = child.wait();
    let _ = copier.join();
    let code = status.ok().and_then(|s| s.code());
    let mut rec = read_record(key).unwrap_or(rec);
    rec.exit = Some(code);
    write_record(key, &rec);
    log(key, &format!("ended ({code:?})"));
    let until = Instant::now() + linger();
    while !collected.load(std::sync::atomic::Ordering::SeqCst) && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(100));
    }
    let _ = std::fs::remove_file(&sock);
    if collected.load(std::sync::atomic::Ordering::SeqCst) {
        remove_files(key);
    }
    Ok(())
}

fn peer_uid(s: &UnixStream) -> Option<u32> {
    let mut cred = libc::ucred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    // SAFETY: getsockopt writes at most `len` bytes into `cred`.
    let rc = unsafe {
        libc::getsockopt(
            std::os::fd::AsRawFd::as_raw_fd(s),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut _ as *mut libc::c_void,
            &mut len,
        )
    };
    (rc == 0).then_some(cred.uid)
}

type Input = Arc<Mutex<Option<std::process::ChildStdin>>>;

fn serve(
    mut c: UnixStream,
    pid: i32,
    stdin: &Input,
    collected: &std::sync::atomic::AtomicBool,
    saved: &Mutex<u64>,
    key: &str,
) {
    if writeln!(c, "mimir-host {PROTO}").is_err() {
        return;
    }
    let Ok(r) = c.try_clone() else { return };
    for line in BufReader::new(r).lines().map_while(Result::ok) {
        let Ok(v) = serde_json::from_str::<serde_json::Value>(&line) else {
            continue;
        };
        if let Some(t) = v["send"].as_str()
            && let Ok(mut g) = stdin.lock()
            && let Some(i) = g.as_mut()
        {
            let _ = writeln!(i, "{t}");
            let _ = i.flush();
        }
        if v["close"].as_bool() == Some(true)
            && let Ok(mut g) = stdin.lock()
        {
            *g = None;
        }
        if let Some(sig) = v["signal"].as_i64() {
            // SAFETY: a signal to the child this host started.
            unsafe { libc::kill(pid, sig as i32) };
        }
        if v["kill"].as_bool() == Some(true) {
            // SAFETY: the child's own process group.
            unsafe { libc::kill(-pid, libc::SIGTERM) };
            std::thread::sleep(Duration::from_millis(500));
            unsafe { libc::kill(-pid, libc::SIGKILL) };
        }
        if let Some(n) = v["saved"].as_u64()
            && let Ok(mut s) = saved.lock()
            && n > *s
        {
            // Whole chunks before it are done with; never cut a file still
            // being written.
            for c in (*s / CHUNK)..(n / CHUNK) {
                let _ = std::fs::remove_file(chunk_path(key, c));
            }
            *s = n;
            if let Some(mut r) = read_record(key) {
                r.out_offset_saved = n;
                write_record(key, &r);
            }
        }
        if v["collected"].as_bool() == Some(true) {
            collected.store(true, std::sync::atomic::Ordering::SeqCst);
        }
    }
}
