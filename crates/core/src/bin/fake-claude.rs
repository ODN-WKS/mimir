//! A stand-in for `claude` used by the automated tests. It replays a stream
//! recorded from the real Claude Code.
//!
//! Recording lines (JSON, one per line):
//!   {"t": 1.2, "line": "..."}   print this output line
//!   {"t": 0.0, "in": "..."}     wait for one input line from the window
//!   {"t": 5.1, "signal": "INT"} wait for SIGINT (Stop / Pause)
//!   {"t": 9.0, "exit": 0}       exit with this code (null = by signal)
//!   {"t": 2.0, "hook": {...}, "expect": "allow"}
//!                               run the PermissionRequest hook from
//!                               --settings with this input; exit 4 if its
//!                               answer (allow / always / deny) differs
//!
//! Settings by environment:
//!   FAKE_CLAUDE_STREAM  path of the recording to replay
//!   FAKE_CLAUDE_STREAMS a folder of recordings, for checks that start
//!                       Claude more than once (instead of FAKE_CLAUDE_STREAM):
//!                         the nth new conversation (`-p` without --resume)
//!                         plays run-<n>.jsonl;
//!                         a --resume run plays resume.jsonl, the second one
//!                         resume-second.jsonl when there is one; its
//!                         conversation id becomes the one asked to resume;
//!                         Phone too (`--resume <id> --remote-control`) adds
//!                         remote.jsonl's lines to that conversation's
//!                         history file, one every 200 ms.
//!                       The counts are kept in that folder, so give each
//!                       check its own copy.
//!   FAKE_CLAUDE_STEP_FILE  before printing its nth output line, wait until
//!                       this file holds a number of at least n (the check
//!                       raises it to let the reply go on)
//!   FAKE_CLAUDE_SPEED   1.0 = recorded pace, 0 = instant (default 0)
//!   FAKE_CLAUDE_ARGS    file to append the received arguments to
//!   FAKE_CLAUDE_CWDS    file to append, per run, the folder it ran in
//!   FAKE_CLAUDE_ENV     file to append, per run, the systemd-only
//!                       variables it was given and whether fd 3 was open
//!   FAKE_CLAUDE_HOOKS   file to append each hook answer to
//!   FAKE_CLAUDE_INPUT   file to append each message from the window to
//!   FAKE_CLAUDE_TRUST   (Phone too) first ask "Do you trust this folder?"
//!                       and wait for Down then Enter; the answer is logged
//!                       to FAKE_CLAUDE_INPUT as "trust: yes"
//!   @SOON@ in a recorded line is printed as the time 4 s from now
//!                       (seconds since 1970), for a limit that resets soon
//!   keeper.jsonl        (in FAKE_CLAUDE_STREAMS) `claude remote-control`
//!                       writes it, line by line, as a session the phone
//!                       started in that folder
//!   FAKE_CLAUDE_EXIT_ONCE  a file: the first Remote Control or Phone too
//!                       run creates it and exits after 300 ms, as if it
//!                       stopped on its own; later runs keep going
//!
//! Without any recording it still answers `--version`, `auth status
//! --json` (signed in), `remote-control` (a project's Remote Control) and
//! `--remote-control` (Phone too), which then wait for Stop.

use serde_json::Value;
use std::io::{BufRead, Read, Seek, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

static GOT_INT: AtomicBool = AtomicBool::new(false);

extern "C" fn on_int(_: libc::c_int) {
    GOT_INT.store(true, Ordering::SeqCst);
}

/// What `claude auth status --json` says when signed in (made-up account).
const SIGNED_IN: &str = r#"{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"checks@example.invalid","orgId":"00000000-0000-4000-8000-0000000000bb","orgName":"Checks","subscriptionType":"max"}"#;

/// The address a Phone too session shows (made-up conversation).
const SESSION_URL: &str = "https://claude.ai/code/session_01FakeCheckSession";

/// The address a project's Remote Control shows (made-up environment).
const PROJECT_URL: &str = "https://claude.ai/code?environment=env_01FakeCheckEnvironment";

/// A Remote Control left running by a check stops by itself after this.
const REMOTE_LIFETIME: Duration = Duration::from_secs(600);

fn main() {
    // SAFETY: installs a handler that only stores to an atomic.
    unsafe {
        libc::signal(libc::SIGINT, on_int as *const () as libc::sighandler_t);
    }
    let args: Vec<String> = std::env::args().skip(1).collect();
    if let Some(log) = std::env::var_os("FAKE_CLAUDE_ARGS") {
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log)
            .expect("args log");
        writeln!(f, "{}", serde_json::to_string(&args).unwrap()).unwrap();
    }
    // The folder each start ran in (a moved task carries on in its new one).
    if let Some(log) = std::env::var_os("FAKE_CLAUDE_CWDS") {
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log)
            .expect("folders log");
        let cwd = std::env::current_dir().unwrap_or_default();
        writeln!(f, "{}", cwd.display()).unwrap();
    }
    if let Some(log) = std::env::var_os("FAKE_CLAUDE_ENV") {
        let given: Vec<String> = std::env::vars()
            .map(|(k, _)| k)
            .filter(|k| {
                k.starts_with("LISTEN_") || k == "NOTIFY_SOCKET" || k.starts_with("WATCHDOG_")
            })
            .collect();
        // SAFETY: fcntl only asks about fd 3.
        let fd3 = unsafe { libc::fcntl(3, libc::F_GETFD) } != -1;
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log)
            .expect("env log");
        writeln!(f, "{}", serde_json::json!({"systemd": given, "fd3": fd3})).unwrap();
    }
    if args.iter().any(|a| a == "--version") {
        println!("2.1.325 (Claude Code)");
        return;
    }
    if args.first().map(String::as_str) == Some("auth")
        && args.get(1).map(String::as_str) == Some("status")
    {
        println!("{SIGNED_IN}");
        return;
    }
    let streams = std::env::var_os("FAKE_CLAUDE_STREAMS").map(PathBuf::from);
    if args.first().map(String::as_str) == Some("remote-control") {
        exit_once();
        project_remote();
        return;
    }
    if args.iter().any(|a| a == "--remote-control") {
        phone_session(flag(&args, "--resume"), streams.as_deref());
        return;
    }

    let (path, resume_as) = match &streams {
        Some(dir) => {
            if !args.iter().any(|a| a == "-p") {
                eprintln!("fake-claude: nothing recorded for {args:?}");
                std::process::exit(2);
            }
            pick(dir, flag(&args, "--resume"))
        }
        None => (
            PathBuf::from(std::env::var("FAKE_CLAUDE_STREAM").expect("FAKE_CLAUDE_STREAM not set")),
            None,
        ),
    };
    let speed: f64 = std::env::var("FAKE_CLAUDE_SPEED")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(0.0);
    let step_file = std::env::var_os("FAKE_CLAUDE_STEP_FILE").map(PathBuf::from);
    let text = std::fs::read_to_string(&path).expect("recording");
    let entries: Vec<Value> = text
        .lines()
        .filter_map(|l| serde_json::from_str(l).ok())
        .collect();
    // A resumed conversation keeps the id it was resumed with.
    let swap = resume_as.and_then(|new| recorded_session(&entries).map(|old| (old, new)));

    let stdin = std::io::stdin();
    let mut input = stdin.lock().lines();
    let mut out = std::io::stdout().lock();
    let mut last_t = 0.0;
    let mut printed: u64 = 0;
    let mut i = 0;
    while i < entries.len() {
        let e = &entries[i];
        i += 1;
        let t = e.get("t").and_then(Value::as_f64).unwrap_or(last_t);
        if speed > 0.0 && t > last_t {
            let wait = Duration::from_secs_f64((t - last_t) * speed);
            let until = Instant::now() + wait;
            while Instant::now() < until && !GOT_INT.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_millis(5));
            }
        }
        last_t = t;
        if e.get("line").is_some()
            && let Some(f) = &step_file
        {
            wait_for_step(f, printed + 1);
        }
        // A stop that comes before the recorded one jumps ahead to it.
        if GOT_INT.load(Ordering::SeqCst) && e.get("signal").is_none() {
            match entries[i - 1..]
                .iter()
                .position(|x| x.get("signal").is_some())
            {
                Some(p) => {
                    i = i - 1 + p;
                    continue;
                }
                None => std::process::exit(130),
            }
        }
        if let Some(line) = e.get("line").and_then(Value::as_str) {
            let line = match &swap {
                Some((old, new)) => line.replace(old.as_str(), new),
                None => line.to_string(),
            };
            // "@SOON@": a time 4 s from now (a limit that resets soon).
            let line = line.replace("@SOON@", &(now_unix() + 4).to_string());
            if writeln!(out, "{line}").and_then(|_| out.flush()).is_err() {
                std::process::exit(1);
            }
            printed += 1;
        } else if e.get("in").is_some() {
            match input.next() {
                Some(Ok(got)) => {
                    if let Some(log) = std::env::var_os("FAKE_CLAUDE_INPUT") {
                        let mut f = std::fs::OpenOptions::new()
                            .create(true)
                            .append(true)
                            .open(log)
                            .expect("input log");
                        writeln!(f, "{got}").unwrap();
                    }
                }
                // Window closed our input: behave like Claude Code, exit.
                _ => std::process::exit(0),
            }
        } else if e.get("signal").is_some() {
            let until = Instant::now() + Duration::from_secs(60);
            while !GOT_INT.load(Ordering::SeqCst) {
                if Instant::now() > until {
                    eprintln!("fake-claude: expected a stop signal");
                    std::process::exit(3);
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            GOT_INT.store(false, Ordering::SeqCst);
        } else if let Some(input) = e.get("hook") {
            let Some(cmd) = hook_command(&args) else {
                continue;
            };
            let got = run_hook(&cmd, input);
            if let Some(log) = std::env::var_os("FAKE_CLAUDE_HOOKS") {
                let mut f = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(log)
                    .expect("hooks log");
                writeln!(f, "{got}").unwrap();
            }
            let expect = e.get("expect").and_then(Value::as_str).unwrap_or("");
            if !expect.is_empty() && got != expect {
                eprintln!("fake-claude: hook answered {got}, recording expects {expect}");
                std::process::exit(4);
            }
        } else if let Some(code) = e.get("exit") {
            std::process::exit(code.as_i64().map(|c| c as i32).unwrap_or(130));
        }
    }
}

/// The value after `name` in the arguments.
fn flag(args: &[String], name: &str) -> Option<String> {
    let i = args.iter().position(|a| a == name)?;
    args.get(i + 1).cloned()
}

/// The recording for this run from a folder of them, and the conversation
/// id to resume as (for a --resume run).
fn pick(dir: &Path, resume: Option<String>) -> (PathBuf, Option<String>) {
    match resume {
        Some(id) => {
            let n = bump(&dir.join(".resume-runs"));
            let second = dir.join("resume-second.jsonl");
            let path = if n == 2 && second.is_file() {
                second
            } else {
                dir.join("resume.jsonl")
            };
            (path, Some(id))
        }
        None => {
            let n = bump(&dir.join(".new-runs"));
            let path = dir.join(format!("run-{n}.jsonl"));
            if !path.is_file() {
                eprintln!("fake-claude: run {n} has no {}", path.display());
                std::process::exit(5);
            }
            (path, None)
        }
    }
}

/// Adds one to the count kept in `file` (safely across processes) and
/// returns the new count.
fn bump(file: &Path) -> u64 {
    use std::os::fd::AsRawFd;
    let mut f = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(file)
        .expect("run count");
    // SAFETY: flock(2) on a file this process holds open.
    unsafe {
        libc::flock(f.as_raw_fd(), libc::LOCK_EX);
    }
    let mut s = String::new();
    let _ = f.read_to_string(&mut s);
    let n = s.trim().parse::<u64>().unwrap_or(0) + 1;
    let _ = f.set_len(0);
    let _ = f.rewind();
    let _ = write!(f, "{n}");
    let _ = f.flush();
    // SAFETY: as above.
    unsafe {
        libc::flock(f.as_raw_fd(), libc::LOCK_UN);
    }
    n
}

/// The conversation id in a recording's first line that has one.
fn recorded_session(entries: &[Value]) -> Option<String> {
    entries.iter().find_map(|e| {
        let v: Value = serde_json::from_str(e.get("line")?.as_str()?).ok()?;
        v.get("session_id")?.as_str().map(str::to_string)
    })
}

/// Waits until the step file holds a number of at least `n`, or Stop.
fn wait_for_step(file: &Path, n: u64) {
    let until = Instant::now() + Duration::from_secs(120);
    loop {
        let at = std::fs::read_to_string(file)
            .ok()
            .and_then(|s| s.trim().parse::<u64>().ok())
            .unwrap_or(0);
        if at >= n || GOT_INT.load(Ordering::SeqCst) {
            return;
        }
        if Instant::now() > until {
            eprintln!("fake-claude: step file never reached {n}");
            std::process::exit(3);
        }
        std::thread::sleep(Duration::from_millis(5));
    }
}

/// Claude Code's folder name for a project: every character other than a
/// letter or digit becomes "-".
fn folder_name(project: &Path) -> String {
    project
        .display()
        .to_string()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect()
}

fn claude_home() -> PathBuf {
    std::env::var_os("CLAUDE_CONFIG_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".claude")
        })
}

/// Sleeps until Stop (SIGINT), or until `until`.
fn until_stopped(until: Instant) {
    while !GOT_INT.load(Ordering::SeqCst) && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// `claude remote-control`: says it is connected, then waits for Stop.
fn project_remote() {
    let name = std::env::current_dir()
        .ok()
        .and_then(|d| d.file_name().map(|n| n.to_string_lossy().to_string()))
        .unwrap_or_default();
    let mut out = std::io::stdout().lock();
    let _ = write!(out, "·|· Connecting · {name}\r\n");
    let _ = write!(out, "·✔︎· Connected · {name}\r\n");
    let _ = write!(
        out,
        "Continue coding in the Claude mobile app or {PROJECT_URL}\r\n"
    );
    let _ = out.flush();
    drop(out);
    let ends = Instant::now() + REMOTE_LIFETIME;
    // With keeper.jsonl: a session started from the phone, written to its
    // history file line by line as it happens (named as Claude Code names
    // the sessions Remote Control starts).
    let streams = std::env::var_os("FAKE_CLAUDE_STREAMS").map(PathBuf::from);
    if let Some(lines) = streams.and_then(|d| std::fs::read_to_string(d.join("keeper.jsonl")).ok())
    {
        let project = std::env::current_dir().unwrap_or_default();
        let session = KEEPER_SESSION;
        let folder = claude_home().join("projects").join(folder_name(&project));
        std::fs::create_dir_all(&folder).expect("history folder");
        let file = folder.join(format!("{session}.jsonl"));
        let quoted = serde_json::to_string(&project.display().to_string()).unwrap_or_default();
        let cwd = quoted
            .strip_prefix('"')
            .and_then(|s| s.strip_suffix('"'))
            .unwrap_or_default();
        for line in lines.lines().filter(|l| !l.trim().is_empty()) {
            until_stopped(Instant::now() + Duration::from_millis(400));
            if GOT_INT.load(Ordering::SeqCst) {
                return;
            }
            let line = line.replace("@SESSION@", session).replace("@PROJECT@", cwd);
            let mut f = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&file)
                .expect("history file");
            writeln!(f, "{line}").expect("history line");
        }
    }
    until_stopped(ends);
}

/// The session a phone starts through Remote Control (keeper.jsonl).
const KEEPER_SESSION: &str = "0b5e5510-7e1e-5a0e-8000-00000000c0de";

/// `claude --resume <id> --remote-control <name>` (Phone too): shows the
/// conversation's address the way Claude Code does, then, with a folder of
/// recordings, adds remote.jsonl's lines to the conversation's history
/// file as if typed on the phone, and waits for Stop.
fn phone_session(session: Option<String>, streams: Option<&Path>) {
    let session = session.unwrap_or_else(|| "fake-remote".into());
    let project = std::env::current_dir().unwrap_or_default();
    if std::env::var_os("FAKE_CLAUDE_TRUST").is_some() {
        ask_trust();
    }
    exit_once();
    let mut out = std::io::stdout().lock();
    let _ = write!(
        out,
        "/remote-control is active · Continue here, on your phone, or at {SESSION_URL}\r\n"
    );
    let _ = out.flush();
    let ends = Instant::now() + REMOTE_LIFETIME;
    if let Some(lines) = streams.and_then(|d| std::fs::read_to_string(d.join("remote.jsonl")).ok())
    {
        let folder = claude_home().join("projects").join(folder_name(&project));
        std::fs::create_dir_all(&folder).expect("history folder");
        let file = folder.join(format!("{session}.jsonl"));
        // The folder, written the way JSON writes a string (without quotes).
        let quoted = serde_json::to_string(&project.display().to_string()).unwrap_or_default();
        let cwd = quoted
            .strip_prefix('"')
            .and_then(|s| s.strip_suffix('"'))
            .unwrap_or_default();
        for line in lines.lines().filter(|l| !l.trim().is_empty()) {
            until_stopped(Instant::now() + Duration::from_millis(200));
            if GOT_INT.load(Ordering::SeqCst) {
                return;
            }
            let line = line
                .replace("@SESSION@", &session)
                .replace("@PROJECT@", cwd);
            let mut f = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&file)
                .expect("history file");
            writeln!(f, "{line}").expect("history line");
        }
    }
    until_stopped(ends);
}

/// Asks whether the folder is trusted, as interactive Claude Code does,
/// and waits for Down then Enter (what Mímir types).
fn ask_trust() {
    use std::io::Read;
    {
        let mut out = std::io::stdout().lock();
        let _ = write!(
            out,
            "Do you trust this folder?\r\n  1. No, exit\r\n  2. Yes, I trust this folder\r\n"
        );
        let _ = out.flush();
    }
    let mut seen = Vec::new();
    let mut b = [0u8; 1];
    while !seen.ends_with(b"\x1b[B\r") && !seen.ends_with(b"\x1b[B\n") {
        match std::io::stdin().read(&mut b) {
            Ok(1) => seen.push(b[0]),
            _ => std::process::exit(6),
        }
    }
    if let Some(log) = std::env::var_os("FAKE_CLAUDE_INPUT") {
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log)
            .expect("input log");
        writeln!(f, "trust: yes").expect("input log line");
    }
}

/// With FAKE_CLAUDE_EXIT_ONCE: the first run stops on its own.
fn exit_once() {
    if let Some(mark) = std::env::var_os("FAKE_CLAUDE_EXIT_ONCE") {
        let mark = PathBuf::from(mark);
        if !mark.exists() {
            let _ = std::fs::write(&mark, "exited once");
            {
                let mut out = std::io::stdout().lock();
                let _ = write!(out, "Connected\r\n");
                let _ = out.flush();
            }
            std::thread::sleep(Duration::from_millis(300));
            std::process::exit(1);
        }
    }
}

/// The PermissionRequest hook command from `--settings`, if any.
fn hook_command(args: &[String]) -> Option<String> {
    let i = args.iter().position(|a| a == "--settings")?;
    let v: Value = serde_json::from_str(args.get(i + 1)?).ok()?;
    v.pointer("/hooks/PermissionRequest/0/hooks/0/command")?
        .as_str()
        .map(str::to_string)
}

/// Runs the hook like Claude Code does and sums up its answer.
fn run_hook(cmd: &str, input: &Value) -> String {
    let mut child = Command::new("sh")
        .arg("-c")
        .arg(cmd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .expect("start hook");
    let mut stdin = child.stdin.take().unwrap();
    writeln!(stdin, "{input}").unwrap();
    drop(stdin);
    let out = child.wait_with_output().expect("hook output");
    let v: Value = serde_json::from_slice(&out.stdout).unwrap_or(Value::Null);
    let d = &v["hookSpecificOutput"]["decision"];
    match d["behavior"].as_str() {
        Some("allow") if d.get("updatedPermissions").is_some() => "always".into(),
        Some("allow") => "allow".into(),
        Some("deny") => "deny".into(),
        _ => "none".into(),
    }
}

fn now_unix() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
