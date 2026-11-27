//! Sessions started elsewhere (for example in a terminal): Claude Code keeps
//! each one in `~/.claude/projects/<folder>/<session id>.jsonl`. Opening
//! one turns it into a task that continues with `--resume`.

use crate::events::parse_line;
use crate::task::{Status, Task};
use serde_json::Value;
use std::path::{Path, PathBuf};

/// Claude Code's folder name for a project: every character that is not a
/// letter or digit becomes '-'.
pub fn folder_name(project: &Path) -> String {
    project
        .display()
        .to_string()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect()
}

pub fn claude_home() -> PathBuf {
    std::env::var_os("CLAUDE_CONFIG_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".claude")
        })
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct PastSession {
    pub id: String,
    pub path: PathBuf,
    /// The first thing the owner asked.
    pub first: String,
    pub modified_unix: i64,
    pub messages: usize,
}

/// What one `user` line in a Claude Code transcript is: the person's words,
/// or something Claude Code wrote there on its own.
#[derive(Debug, Clone, PartialEq)]
enum UserLine {
    /// The person typed it.
    Person(String),
    /// A helper finished (its name, when it has one).
    HelperFinished(String),
    /// A helper did not finish: it failed or was stopped.
    HelperStopped,
    /// A background command ended (true: it finished; false: it did not).
    CommandEnded(bool),
    /// A helper reported back (its words, tags removed).
    HelperReported(String),
    /// The person stopped the work at this point.
    Stopped,
    /// A slash command ran (its name, without the slash).
    Command(String),
    /// Bookkeeping that is never shown.
    Hidden,
}

/// The line a session opened from an earlier time ends with. The window
/// hides it while the session still runs elsewhere (nothing can be sent yet).
pub const OPENED_NOTE: &str = "Opened from an earlier session. Send a message to carry on.";

fn between<'a>(text: &'a str, open: &str, close: &str) -> Option<&'a str> {
    let a = text.find(open)? + open.len();
    let b = text[a..].find(close)? + a;
    Some(text[a..b].trim())
}

/// The helper's name from `Agent "X" finished`, `Background command "X"
/// completed` and the like; the whole summary when it has another shape.
fn helper_name(summary: &str) -> String {
    match (summary.find('"'), summary.rfind('"')) {
        (Some(a), Some(b)) if b > a => summary[a + 1..b].trim().to_string(),
        _ => summary.trim().to_string(),
    }
}

fn classify(e: &Value) -> Option<UserLine> {
    if e.get("type").and_then(Value::as_str) != Some("user")
        || e.get("isSidechain").and_then(Value::as_bool) == Some(true)
    {
        return None;
    }
    let c = e.pointer("/message/content")?;
    let text = match c {
        Value::String(s) => s.clone(),
        Value::Array(blocks) => {
            if blocks
                .iter()
                .any(|b| b.get("type").and_then(Value::as_str) == Some("tool_result"))
            {
                return None;
            }
            blocks
                .iter()
                .filter_map(|b| b.get("text").and_then(Value::as_str))
                .collect::<Vec<_>>()
                .join("\n")
        }
        _ => return None,
    };
    let kind = e.pointer("/origin/kind").and_then(Value::as_str);
    let meta = e.get("isMeta").and_then(Value::as_bool) == Some(true);
    let raw = text.trim();
    let helper_done = |raw: &str| {
        let summary = between(raw, "<summary>", "</summary>").unwrap_or("");
        // Only a helper that finished counts as finished; a failed one says
        // so, and a background command is not a helper.
        let ok = between(raw, "<status>", "</status>").is_none_or(|s| s == "completed");
        if summary.starts_with("Background command") {
            UserLine::CommandEnded(ok)
        } else if ok {
            UserLine::HelperFinished(helper_name(summary))
        } else {
            UserLine::HelperStopped
        }
    };
    let helper_said = |raw: &str| {
        let body = raw
            .strip_prefix("Another Claude session sent a message:")
            .unwrap_or(raw);
        UserLine::HelperReported(strip_tags(body))
    };
    match kind {
        Some("human") => {}
        Some("task-notification") => return Some(helper_done(raw)),
        Some("peer") => return Some(helper_said(raw)),
        Some(_) | None => {}
    }
    if kind != Some("human") {
        // Older transcripts have no origin: tell by the shape of the text.
        if raw.starts_with("<task-notification>") {
            return Some(helper_done(raw));
        }
        if raw.starts_with("Another Claude session sent a message") {
            return Some(helper_said(raw));
        }
        if meta {
            return Some(UserLine::Hidden);
        }
    }
    if raw.starts_with("[Request interrupted by user") {
        return Some(UserLine::Stopped);
    }
    if let Some(name) = between(raw, "<command-name>", "</command-name>")
        && raw.starts_with("<command-")
    {
        return Some(UserLine::Command(name.trim_start_matches('/').to_string()));
    }
    let text = clean_text(&text);
    let t = text.trim();
    // Slash-command bookkeeping, not something the owner typed.
    if t.is_empty()
        || t.starts_with("<command-")
        || t.starts_with("<local-command")
        || t.starts_with("Caveat:")
    {
        return Some(UserLine::Hidden);
    }
    Some(UserLine::Person(t.to_string()))
}

/// Text with every `<tag>` taken out and file paths under `/tmp` dropped, so
/// a helper's report reads as plain words.
fn strip_tags(text: &str) -> String {
    let mut out = String::new();
    let mut rest = text;
    while let Some(a) = rest.find('<') {
        out.push_str(&rest[..a]);
        match rest[a..].find('>') {
            Some(b) => rest = &rest[a + b + 1..],
            None => {
                rest = "";
                break;
            }
        }
    }
    out.push_str(rest);
    out.trim().to_string()
}

fn user_text(e: &Value) -> Option<String> {
    match classify(e)? {
        UserLine::Person(t) => Some(t),
        _ => None,
    }
}

/// Removes what Claude Code adds around the owner's words: whole
/// `<system-reminder>` blocks, and the `<pasted_content …>` tags (their
/// content is kept: it is what the owner pasted).
pub fn clean_text(text: &str) -> String {
    let mut s = text.to_string();
    while let Some(start) = s.find("<system-reminder>") {
        let end = s[start..]
            .find("</system-reminder>")
            .map(|e| start + e + "</system-reminder>".len())
            .unwrap_or(s.len());
        s.replace_range(start..end, "");
    }
    for tag in ["<pasted_content", "</pasted_content"] {
        while let Some(start) = s.find(tag) {
            let end = s[start..]
                .find('>')
                .map(|e| start + e + 1)
                .unwrap_or(s.len());
            s.replace_range(start..end, "");
        }
    }
    s.trim().to_string()
}

/// Past sessions for a project, newest first.
pub fn list(project: &Path) -> Vec<PastSession> {
    let dir = claude_home().join("projects").join(folder_name(project));
    let Ok(rd) = std::fs::read_dir(&dir) else {
        return vec![];
    };
    let mut out = vec![];
    for e in rd.flatten() {
        let path = e.path();
        if path.extension().and_then(|x| x.to_str()) != Some("jsonl") {
            continue;
        }
        let Some(id) = path
            .file_stem()
            .and_then(|s| s.to_str())
            .map(str::to_string)
        else {
            continue;
        };
        let modified_unix = e
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let Ok(text) = std::fs::read_to_string(&path) else {
            continue;
        };
        let mut first = None;
        let mut messages = 0;
        for line in text.lines() {
            let Ok(v) = serde_json::from_str::<Value>(line) else {
                continue;
            };
            if let Some(t) = user_text(&v) {
                messages += 1;
                first.get_or_insert(t);
            }
        }
        if let Some(first) = first {
            out.push(PastSession {
                id,
                path,
                first,
                modified_unix,
                messages,
            });
        }
    }
    out.sort_by_key(|s| std::cmp::Reverse(s.modified_unix));
    out
}

/// Claude Code's own short title for a session ("ai-title" lines; the
/// newest wins). Remembered per file, and a growing transcript is read only
/// from where the last look stopped (at a whole line, so a line still being
/// written is read next time, not lost).
pub fn ai_title(path: &Path) -> Option<String> {
    use std::collections::HashMap;
    use std::io::{BufRead, Seek};
    use std::sync::Mutex;
    /// How far the file was read, and the title found up to there.
    type Seen = HashMap<PathBuf, (u64, Option<String>)>;
    static SEEN: Mutex<Option<Seen>> = Mutex::new(None);
    let size = std::fs::metadata(path).ok()?.len();
    let (mut from, mut title) = SEEN
        .lock()
        .ok()?
        .get_or_insert_with(HashMap::new)
        .get(path)
        .cloned()
        .unwrap_or((0, None));
    if from == size {
        return title;
    }
    if from > size {
        // Written anew: read it all again.
        (from, title) = (0, None);
    }
    let mut f = std::fs::File::open(path).ok()?;
    f.seek(std::io::SeekFrom::Start(from)).ok()?;
    let mut reader = std::io::BufReader::new(f);
    let mut line = String::new();
    loop {
        line.clear();
        match reader.read_line(&mut line) {
            Ok(0) | Err(_) => break,
            // Still being written: left for next time.
            Ok(_) if !line.ends_with('\n') => break,
            Ok(n) => {
                from += n as u64;
                if line.contains("\"ai-title\"")
                    && let Ok(v) = serde_json::from_str::<Value>(&line)
                    && let Some(t) = v.get("aiTitle").and_then(Value::as_str)
                    && !crate::task::system_like(t)
                {
                    title = Some(t.trim().to_string());
                }
            }
        }
    }
    if let Ok(mut g) = SEEN.lock() {
        remember(
            g.get_or_insert_with(HashMap::new),
            path.to_path_buf(),
            (from, title.clone()),
        );
    }
    title
}

/// Most entries a remembered-per-file cache keeps; past that it starts over
/// (a long-running service must not grow without end).
const CACHE_MAX: usize = 4000;

/// Adds to a per-file cache, starting it over when it is full.
fn remember<V>(map: &mut std::collections::HashMap<PathBuf, V>, key: PathBuf, value: V) {
    if map.len() >= CACHE_MAX && !map.contains_key(&key) {
        map.clear();
    }
    map.insert(key, value);
}

/// A session from any project on this PC, for the "On this PC" list.
#[derive(Debug, Clone, PartialEq)]
pub struct OtherSession {
    /// The folder it ran in.
    pub project: PathBuf,
    /// Claude Code's own short title, when it made one.
    pub title: Option<String>,
    pub session: PastSession,
}

/// How far into a transcript to look for its folder and first message.
const HEAD_LINES: usize = 400;

/// The folder (`cwd`) and first thing asked, from the start of a transcript.
/// Remembered once found (the start of a transcript never changes), so a
/// big, busy transcript is not opened again and a failed look later can't
/// drop it from the list.
fn head(path: &Path) -> Option<(PathBuf, String)> {
    use std::collections::HashMap;
    use std::sync::Mutex;
    static FOUND: Mutex<Option<HashMap<PathBuf, (PathBuf, String)>>> = Mutex::new(None);
    if let Some(found) = FOUND
        .lock()
        .ok()
        .and_then(|mut g| g.get_or_insert_with(HashMap::new).get(path).cloned())
    {
        return Some(found);
    }
    let found = read_head(path)?;
    if let Ok(mut g) = FOUND.lock() {
        remember(
            g.get_or_insert_with(HashMap::new),
            path.to_path_buf(),
            found.clone(),
        );
    }
    Some(found)
}

fn read_head(path: &Path) -> Option<(PathBuf, String)> {
    use std::io::BufRead;
    let f = std::fs::File::open(path).ok()?;
    let (mut cwd, mut first) = (None, None);
    for line in std::io::BufReader::new(f).lines().take(HEAD_LINES) {
        let Ok(line) = line else { break };
        let Ok(v) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        if cwd.is_none() {
            cwd = v.get("cwd").and_then(Value::as_str).map(PathBuf::from);
        }
        if first.is_none() {
            first = user_text(&v);
        }
        if cwd.is_some() && first.is_some() {
            break;
        }
    }
    Some((cwd?, first?))
}

/// The newest sessions from every project on this PC (terminal, desktop
/// app, phone through Remote Control, and this window), newest first.
/// Sessions in temporary folders or folders that are gone are left out.
pub fn recent(limit: usize) -> Vec<OtherSession> {
    recent_in(&claude_home().join("projects"), limit)
}

fn recent_in(root: &Path, limit: usize) -> Vec<OtherSession> {
    recent_keeping(root, limit, &Default::default(), &Default::default())
}

/// The newest `limit` sessions, and also every one whose id is in `keep`
/// (the running ones), newest first. Ids in `skip` are left out before the
/// limit is counted.
pub(crate) fn recent_keeping(
    root: &Path,
    limit: usize,
    keep: &std::collections::HashSet<String>,
    skip: &std::collections::HashSet<String>,
) -> Vec<OtherSession> {
    use std::collections::HashMap;
    use std::sync::Mutex;
    use std::time::{Duration, Instant};
    /// Files left out (no folder or first message, a temporary folder, a
    /// folder that is gone): by size and time, and when they were decided.
    type Rejected = HashMap<PathBuf, (u64, i64, Instant)>;
    static REJECTED: Mutex<Option<Rejected>> = Mutex::new(None);
    const REJECT_FOR: Duration = Duration::from_secs(60);
    let mut files: Vec<(i64, u64, PathBuf)> = vec![];
    for dir in std::fs::read_dir(root).into_iter().flatten().flatten() {
        for e in std::fs::read_dir(dir.path())
            .into_iter()
            .flatten()
            .flatten()
        {
            let path = e.path();
            if path.extension().and_then(|x| x.to_str()) != Some("jsonl") {
                continue;
            }
            // Folders under /tmp are named "-tmp-…": left out unread.
            if dir.file_name().to_string_lossy().starts_with("-tmp-") {
                continue;
            }
            let size = e.metadata().map(|m| m.len()).unwrap_or(0);
            let modified = e
                .metadata()
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0);
            files.push((modified, size, path));
        }
    }
    files.sort_by_key(|(m, _, _)| std::cmp::Reverse(*m));
    let mut out: Vec<OtherSession> = vec![];
    for (modified_unix, size, path) in files {
        let Some(id) = path
            .file_stem()
            .and_then(|s| s.to_str())
            .map(str::to_string)
        else {
            continue;
        };
        if skip.contains(&id) || (out.len() >= limit && !keep.contains(&id)) {
            continue;
        }
        if REJECTED.lock().ok().is_some_and(|g| {
            g.as_ref()
                .and_then(|m| m.get(&path))
                .is_some_and(|(s, m, at)| {
                    *s == size && *m == modified_unix && at.elapsed() < REJECT_FOR
                })
        }) {
            continue;
        }
        let found = head(&path).filter(|(p, _)| !p.starts_with("/tmp") && p.is_dir());
        let Some((project, first)) = found else {
            if let Ok(mut g) = REJECTED.lock() {
                remember(
                    g.get_or_insert_with(HashMap::new),
                    path,
                    (size, modified_unix, Instant::now()),
                );
            }
            continue;
        };
        let title = ai_title(&path);
        out.push(OtherSession {
            project,
            title,
            session: PastSession {
                id,
                path,
                first,
                modified_unix,
                messages: 0,
            },
        });
    }
    out
}

/// Whether a session was started by a project's Remote Control (from the
/// Claude app on a phone, or claude.ai): Claude Code names those files with
/// a name-based (version 5) id, and the owner's messages in them say which
/// app they were typed in.
pub fn from_remote(path: &Path) -> bool {
    use std::io::BufRead;
    let v5 = path
        .file_stem()
        .and_then(|s| s.to_str())
        .is_some_and(|s| s.len() == 36 && s.as_bytes().get(14) == Some(&b'5'));
    if v5 {
        return true;
    }
    // Decided once the owner's first message is in (it says where it was
    // typed), so a folder of busy transcripts isn't read again each look.
    use std::collections::HashMap;
    use std::sync::Mutex;
    static DECIDED: Mutex<Option<HashMap<PathBuf, bool>>> = Mutex::new(None);
    if let Some(known) = DECIDED
        .lock()
        .ok()
        .and_then(|mut g| g.get_or_insert_with(HashMap::new).get(path).copied())
    {
        return known;
    }
    let Ok(f) = std::fs::File::open(path) else {
        return false;
    };
    let (mut remote, mut decided) = (false, false);
    for l in std::io::BufReader::new(f)
        .lines()
        .take(HEAD_LINES)
        .map_while(Result::ok)
    {
        if l.contains("\"clientPlatform\":\"android\"")
            || l.contains("\"clientPlatform\":\"ios\"")
            || l.contains("\"clientPlatform\":\"web_claude_ai\"")
        {
            (remote, decided) = (true, true);
            break;
        }
        if l.contains("\"type\":\"user\"") {
            decided = true;
        }
    }
    if decided && let Ok(mut g) = DECIDED.lock() {
        g.get_or_insert_with(HashMap::new)
            .insert(path.to_path_buf(), remote);
    }
    remote
}

/// Sessions in a project's folder that a Remote Control started, changed
/// at or after `since` (seconds since 1970).
pub fn remote_started(project: &Path, since: i64) -> Vec<PastSession> {
    let dir = claude_home().join("projects").join(folder_name(project));
    let mut out = vec![];
    for e in std::fs::read_dir(&dir).into_iter().flatten().flatten() {
        let path = e.path();
        if path.extension().and_then(|x| x.to_str()) != Some("jsonl") {
            continue;
        }
        let modified_unix = e
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        if modified_unix < since || !from_remote(&path) {
            continue;
        }
        let Some(id) = path
            .file_stem()
            .and_then(|s| s.to_str())
            .map(str::to_string)
        else {
            continue;
        };
        let first = head(&path).map(|(_, f)| f).unwrap_or_default();
        out.push(PastSession {
            id,
            path,
            first,
            modified_unix,
            messages: 0,
        });
    }
    out
}

/// A process on the PC, as far as finding Claude Code sessions needs.
#[derive(Debug, Clone)]
pub struct Proc {
    pub pid: u32,
    /// Seconds since 1970 when it started.
    pub started: i64,
    pub args: Vec<String>,
    pub cwd: Option<PathBuf>,
}

/// The sessions Claude Code processes on this PC have open right now.
#[derive(Debug, Clone, Default)]
pub struct Running {
    /// Session ids (transcript file names).
    pub ids: std::collections::HashSet<String>,
    /// Of those, the ones started from the Claude app (Remote Control).
    pub app: std::collections::HashSet<String>,
    /// The processes (number, start) that have each session open.
    pub pids: std::collections::HashMap<String, Vec<(u32, i64)>>,
}

impl Running {
    fn add(&mut self, id: String, app: bool, p: &Proc) {
        if app {
            self.app.insert(id.clone());
        }
        self.pids
            .entry(id.clone())
            .or_default()
            .push((p.pid, p.started));
        self.ids.insert(id);
    }
}

/// Session ids a Claude Code process on this PC has open (it was started
/// with `--resume <id>` / `--session-id <id>`, or runs in a folder whose
/// newest transcript it is writing): those conversations are going on
/// right now, wherever they are typed in.
pub fn running_sessions() -> std::collections::HashSet<String> {
    let r = running_scan();
    if let Ok(mut g) = APP_IDS.lock() {
        *g = Some(r.app.clone());
    }
    r.ids
}

/// Ids of the running sessions that came from the Claude app, as of the
/// last `running_sessions()`.
static APP_IDS: std::sync::Mutex<Option<std::collections::HashSet<String>>> =
    std::sync::Mutex::new(None);

/// Tests only: these sessions count as started from the Claude app.
#[cfg(test)]
pub fn mark_app_sessions_for_test(ids: &[&str]) {
    if let Ok(mut g) = APP_IDS.lock() {
        *g = Some(ids.iter().map(|s| s.to_string()).collect());
    }
}

/// Whether a running session was started from the Claude app.
pub fn started_in_app(id: &str) -> bool {
    APP_IDS
        .lock()
        .is_ok_and(|g| g.as_ref().is_some_and(|s| s.contains(id)))
}

/// Looks at the PC's processes (the PC's own `ps` inside a Flatpak).
pub fn running_scan() -> Running {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let procs: Vec<Proc> = if let Some(list) = crate::flatpak::host_processes_aged() {
        list.into_iter()
            .map(|h| Proc {
                pid: h.pid,
                started: now - h.age_secs,
                args: h.args,
                cwd: h.cwd,
            })
            .collect()
    } else {
        local_procs(now)
    };
    running_from(&procs, &claude_home())
}

/// Claude Code processes read from `/proc` (others are skipped unread).
pub(crate) fn local_procs(now: i64) -> Vec<Proc> {
    let uptime = std::fs::read_to_string("/proc/uptime")
        .ok()
        .and_then(|t| t.split_whitespace().next()?.parse::<f64>().ok())
        .unwrap_or(0.0);
    // SAFETY: sysconf only reads a system constant.
    let tick = unsafe { libc::sysconf(libc::_SC_CLK_TCK) }.max(1) as f64;
    let mut out = vec![];
    for e in std::fs::read_dir("/proc").into_iter().flatten().flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        if !name.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        let Ok(raw) = std::fs::read(e.path().join("cmdline")) else {
            continue;
        };
        let args: Vec<String> = raw
            .split(|b| *b == 0)
            .filter(|a| !a.is_empty())
            .map(|a| String::from_utf8_lossy(a).to_string())
            .collect();
        if !args.first().is_some_and(|a| a.contains("claude")) {
            continue;
        }
        // Field 22 of `stat` (after the program's name in brackets): when
        // the process started, in ticks since boot.
        let started = std::fs::read_to_string(e.path().join("stat"))
            .ok()
            .and_then(|t| {
                let rest = t.rsplit_once(')')?.1.to_string();
                rest.split_whitespace().nth(19)?.parse::<f64>().ok()
            })
            .map(|ticks| now - (uptime - ticks / tick).max(0.0) as i64)
            .unwrap_or(now);
        out.push(Proc {
            pid: name.parse().unwrap_or(0),
            started,
            args,
            cwd: std::fs::read_link(e.path().join("cwd")).ok(),
        });
    }
    out
}

/// Whether a command line is a Claude Code (the program, its versioned
/// binary, or its script).
fn claude_process(args: &[String]) -> bool {
    is_claude_code(args)
        || args
            .first()
            .is_some_and(|a| a.contains("/claude/versions/"))
}

/// The 36-character session id a command line names, if any.
fn id_on_line(args: &[String]) -> Option<String> {
    let id = |a: &str| (a.len() == 36).then(|| a.to_string());
    for (i, a) in args.iter().enumerate() {
        for key in ["--resume", "-r", "--session-id"] {
            if a == key
                && let Some(v) = args.get(i + 1).and_then(|v| id(v))
            {
                return Some(v);
            }
            if let Some(v) = a.strip_prefix(&format!("{key}=")).and_then(id) {
                return Some(v);
            }
        }
    }
    None
}

/// The sessions the given processes have open. A process with a session id
/// on its line names it; any other (a plain `claude` in a terminal, or one
/// the Claude app started under a `cse_` id) is matched by the folder it
/// runs in: the newest transcripts there written since it started, one per
/// process. What was matched is remembered by process number and start.
pub fn running_from(procs: &[Proc], home: &Path) -> Running {
    use std::collections::{HashMap, HashSet};
    use std::sync::Mutex;
    /// (process, start) → the session found for it and whether the Claude
    /// app started it.
    type Matched = HashMap<(u32, i64), (String, bool)>;
    static MATCHED: Mutex<Option<Matched>> = Mutex::new(None);
    let mut out = Running::default();
    let mut by_folder: HashMap<PathBuf, Vec<&Proc>> = HashMap::new();
    for p in procs {
        // A Remote Control kept running for the phone sits idle: not
        // activity.
        if !claude_process(&p.args)
            || p.args
                .iter()
                .any(|a| a == "--remote-control" || a == "remote-control")
        {
            continue;
        }
        let app = p
            .args
            .iter()
            .any(|a| a == "--sdk-url" || a.contains("cse_"));
        if let Some(id) = id_on_line(&p.args) {
            out.add(id, app, p);
        } else if let Some(c) = &p.cwd {
            by_folder.entry(c.clone()).or_default().push(p);
        }
    }
    for (cwd, mut ps) in by_folder {
        ps.sort_by_key(|p| (p.started, p.pid));
        let dir = home.join("projects").join(folder_name(&cwd));
        let mut files: Vec<(i64, String)> = vec![];
        for e in std::fs::read_dir(&dir).into_iter().flatten().flatten() {
            let path = e.path();
            if path.extension().and_then(|x| x.to_str()) != Some("jsonl") {
                continue;
            }
            let (Some(id), Some(m)) = (
                path.file_stem().and_then(|s| s.to_str()),
                e.metadata()
                    .and_then(|m| m.modified())
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()),
            ) else {
                continue;
            };
            files.push((m.as_secs() as i64, id.to_string()));
        }
        files.sort_by_key(|(m, _)| std::cmp::Reverse(*m));
        let mut cache = MATCHED.lock().ok();
        let known = |p: &Proc, cache: &Option<std::sync::MutexGuard<Option<HashMap<_, _>>>>| {
            cache
                .as_ref()
                .and_then(|g| g.as_ref())
                .and_then(|m| m.get(&(p.pid, p.started)).cloned())
        };
        let mut taken: HashSet<String> = HashSet::new();
        let mut open: Vec<&Proc> = vec![];
        for p in ps {
            match known(p, &cache) {
                Some((id, app)) => {
                    taken.insert(id.clone());
                    out.add(id, app, p);
                }
                None => open.push(p),
            }
        }
        // The newest transcripts written since each started, one each.
        let mut fresh = files.iter().filter(|(_, id)| !taken.contains(id));
        let all_app = open.iter().all(|p| {
            p.args
                .iter()
                .any(|a| a == "--sdk-url" || a.contains("cse_"))
        });
        for p in open {
            let Some((m, id)) = fresh.next() else { break };
            // A little slack: the clock of the file and the start differ.
            if *m < p.started - 2 {
                break;
            }
            let app = all_app || from_remote(&dir.join(format!("{id}.jsonl")));
            if let Some(g) = cache.as_mut() {
                let map = g.get_or_insert_with(HashMap::new);
                if map.len() >= CACHE_MAX {
                    map.clear();
                }
                map.insert((p.pid, p.started), (id.clone(), app));
            }
            out.add(id.clone(), app, p);
        }
    }
    out
}

/// One process now, if it is a Claude Code that is not a Remote Control
/// listener (read fresh, so a reused process number is told apart by its
/// start time).
fn claude_now(pid: u32) -> Option<Proc> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let p = if let Some(list) = crate::flatpak::host_processes_aged() {
        list.into_iter().find(|h| h.pid == pid).map(|h| Proc {
            pid,
            started: now - h.age_secs,
            args: h.args,
            cwd: h.cwd,
        })?
    } else {
        local_procs(now).into_iter().find(|p| p.pid == pid)?
    };
    (claude_process(&p.args)
        && !p
            .args
            .iter()
            .any(|a| a == "--remote-control" || a == "remote-control"))
    .then_some(p)
}

/// Ends the Claude Code processes that have `session` open: SIGINT now,
/// SIGTERM after 2 s and SIGKILL after 5 s if one is still there. Each is
/// checked (a Claude Code, and the same start time) before every signal, so
/// a reused process number is never hit. Where the processes were started:
/// `Ok(true)` for the Claude app. `Err` with a plain reason when none could
/// be found or signalled.
pub fn stop_session(session: &str) -> Result<bool, String> {
    let cant = || "Couldn't stop it here. Stop it where it runs.".to_string();
    let run = running_scan();
    let list = run.pids.get(session).cloned().unwrap_or_default();
    let app = run.app.contains(session);
    let mut sent = 0;
    let mut targets = vec![];
    for (pid, started) in list {
        if same_process(pid, started) && signal(pid, "INT") {
            sent += 1;
            targets.push((pid, started));
        }
    }
    if sent == 0 {
        return Err(cant());
    }
    std::thread::spawn(move || {
        for (after, name) in [(2u64, "TERM"), (3, "KILL")] {
            std::thread::sleep(std::time::Duration::from_secs(after));
            for (pid, started) in &targets {
                if same_process(*pid, *started) {
                    signal(*pid, name);
                }
            }
        }
    });
    Ok(app)
}

/// Whether `pid` is still the Claude Code that started at `started`.
fn same_process(pid: u32, started: i64) -> bool {
    claude_now(pid).is_some_and(|p| (p.started - started).abs() <= 2)
}

/// Sends a signal ("INT", "TERM", "KILL") to a process, on the PC itself
/// inside a Flatpak. True if it was sent.
fn signal(pid: u32, name: &str) -> bool {
    crate::flatpak::host_cmd("kill")
        .arg(format!("-{name}"))
        .arg(pid.to_string())
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .is_ok_and(|s| s.success())
}

/// When the conversation in a transcript last moved: the time of its
/// newest message, tool use or tool result (seconds since 1970). Bookkeeping
/// lines (Remote Control's status, ledgers, summaries, titles) don't count:
/// Claude Code writes those into idle sessions when it starts. Only the
/// file's tail is read, and only again when the file changed.
pub fn last_talk(path: &Path) -> Option<i64> {
    use std::collections::HashMap;
    use std::io::{Read, Seek};
    use std::sync::Mutex;
    type Seen = HashMap<PathBuf, (u64, Option<i64>)>;
    static SEEN: Mutex<Option<Seen>> = Mutex::new(None);
    const TAIL: u64 = 512 * 1024;
    let size = std::fs::metadata(path).ok()?.len();
    if let Some((s, t)) = SEEN.lock().ok()?.get_or_insert_with(HashMap::new).get(path)
        && *s == size
    {
        return *t;
    }
    let mut f = std::fs::File::open(path).ok()?;
    let start = size.saturating_sub(TAIL);
    f.seek(std::io::SeekFrom::Start(start)).ok()?;
    let mut buf = Vec::new();
    f.take(TAIL).read_to_end(&mut buf).ok()?;
    let text = String::from_utf8_lossy(&buf);
    let found = text
        .lines()
        .rev()
        .filter(|l| l.contains("\"type\":\"user\"") || l.contains("\"type\":\"assistant\""))
        .filter_map(|l| serde_json::from_str::<Value>(l).ok())
        .filter(|v| {
            matches!(
                v.get("type").and_then(Value::as_str),
                Some("user") | Some("assistant")
            ) && v.get("isMeta").and_then(Value::as_bool) != Some(true)
        })
        .find_map(|v| {
            v.get("timestamp")
                .and_then(Value::as_str)
                .and_then(iso_unix)
        });
    if let Ok(mut g) = SEEN.lock() {
        remember(
            g.get_or_insert_with(HashMap::new),
            path.to_path_buf(),
            (size, found),
        );
    }
    found
}

/// "2026-11-18T12:01:00.000Z" as seconds since 1970 (UTC).
fn iso_unix(s: &str) -> Option<i64> {
    let b = s.as_bytes();
    if b.len() < 19 || b[4] != b'-' || b[10] != b'T' {
        return None;
    }
    let n = |r: std::ops::Range<usize>| s.get(r)?.parse::<i64>().ok();
    let (y, m, d) = (n(0..4)?, n(5..7)?, n(8..10)?);
    let (hh, mm, ss) = (n(11..13)?, n(14..16)?, n(17..19)?);
    // Days from the civil date (Howard Hinnant's method).
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(days * 86_400 + hh * 3600 + mm * 60 + ss)
}

/// What the person typed on this transcript line, if they typed it.
pub(crate) fn person_words(v: &Value) -> Option<String> {
    match classify(v) {
        Some(UserLine::Person(words)) => Some(words),
        _ => None,
    }
}

/// When a transcript line was written (ms since 1970).
pub(crate) fn line_ms(v: &Value) -> Option<i64> {
    let ms = crate::devlog::parse_iso(v.get("timestamp")?.as_str()?)?;
    i64::try_from(ms).ok()
}

/// The first thing asked in a transcript.
pub fn first_ask(path: &Path) -> Option<String> {
    head(path).map(|(_, f)| f).filter(|f| !f.trim().is_empty())
}

/// One transcript line added to a task, as the conversation shows it.
fn add_line(t: &mut Task, line: &str) {
    let Ok(v) = serde_json::from_str::<Value>(line) else {
        return;
    };
    if v.get("isSidechain").and_then(Value::as_bool) == Some(true) {
        return;
    }
    match classify(&v) {
        Some(UserLine::Person(you)) => {
            let before = t.you_ms;
            t.add_you(&you);
            // Read from a file: sent when its line says, not now.
            if !crate::order::own_words(&you) {
                t.you_ms = before.max(line_ms(&v));
            }
            return;
        }
        Some(UserLine::HelperFinished(name)) => {
            t.add_helper_finished(&name);
            return;
        }
        Some(UserLine::HelperStopped) => {
            t.add_note("A helper stopped");
            return;
        }
        Some(UserLine::CommandEnded(ok)) => {
            t.add_note(if ok {
                "A background command finished"
            } else {
                "A background command stopped"
            });
            return;
        }
        Some(UserLine::HelperReported(_)) => {
            t.add_note("A helper reported back");
            return;
        }
        Some(UserLine::Stopped) => {
            t.add_note("You stopped it here.");
            return;
        }
        Some(UserLine::Command(name)) => {
            t.add_note(&match name.as_str() {
                "model" => "Changed the model".to_string(),
                n => format!("Ran /{n}"),
            });
            return;
        }
        Some(UserLine::Hidden) => return,
        None => {}
    }
    for ev in parse_line(line) {
        t.apply(&ev, 0);
    }
}

/// The most entries a followed session keeps in memory (the tail the
/// window shows).
pub const FOLLOW_TAIL: usize = 200;

/// A transcript line without its pictures: the base64 data of an image
/// block is emptied (a pasted screenshot is megabytes the window never
/// shows from a followed session).
pub fn without_images(line: &str) -> std::borrow::Cow<'_, str> {
    if !line.contains("\"base64\"") {
        return line.into();
    }
    fn strip(v: &mut Value) {
        match v {
            Value::Object(m) => {
                if m.get("type").and_then(Value::as_str) == Some("base64")
                    && let Some(d) = m.get_mut("data")
                {
                    *d = Value::String(String::new());
                }
                m.values_mut().for_each(strip);
            }
            Value::Array(a) => a.iter_mut().for_each(strip),
            _ => {}
        }
    }
    match serde_json::from_str::<Value>(line) {
        Ok(mut v) => {
            strip(&mut v);
            v.to_string().into()
        }
        Err(_) => line.into(),
    }
}

/// Keeps only the last [`FOLLOW_TAIL`] entries of a followed session. It
/// cuts in steps of [`FOLLOW_STEP`], so the window's list shifts only once
/// in a while, and counts what it dropped (`entries_dropped`).
pub fn keep_tail(t: &mut Task) {
    if t.entries.len() > FOLLOW_TAIL + FOLLOW_STEP {
        let cut = t.entries.len() - FOLLOW_TAIL;
        t.entries.drain(..cut);
        let m = cut.min(t.entry_meta.len());
        t.entry_meta.drain(..m);
        t.entries_dropped += cut;
    }
}

/// How far past the tail a followed session may grow before it is cut.
pub const FOLLOW_STEP: usize = 100;

/// Adds what a transcript gained since byte `from` to a task followed live
/// (whole lines only: one still being written waits for the next look).
/// Where the next look starts.
pub fn follow(t: &mut Task, path: &Path, from: u64) -> std::io::Result<u64> {
    use std::io::{BufRead, Seek};
    let mut f = std::fs::File::open(path)?;
    f.seek(std::io::SeekFrom::Start(from))?;
    let mut reader = std::io::BufReader::new(f);
    let (mut at, mut line) = (from, String::new());
    let first_new = t.entries.len();
    loop {
        line.clear();
        match reader.read_line(&mut line)? {
            0 => break,
            _ if !line.ends_with('\n') => break,
            n => {
                at += n as u64;
                add_line(t, &without_images(line.trim_end()));
            }
        }
    }
    // Read from a transcript, not seen made: no time rather than a wrong one.
    for m in t.entry_meta.iter_mut().skip(first_new) {
        *m = Default::default();
    }
    // "Send a message to carry on" belongs under the last line, never between
    // lines that came after it.
    if at > from && t.drop_note(OPENED_NOTE) {
        t.add_note(OPENED_NOTE);
    }
    Ok(at)
}

/// The owner's own words in a session (each message, joined, up to `max`
/// characters) and how many messages they sent.
pub fn owner_text(path: &Path, max: usize) -> (String, usize) {
    use std::io::BufRead;
    let Ok(f) = std::fs::File::open(path) else {
        return (String::new(), 0);
    };
    let (mut text, mut n) = (String::new(), 0);
    for line in std::io::BufReader::new(f).lines().map_while(Result::ok) {
        let Ok(v) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        if let Some(t) = user_text(&v) {
            n += 1;
            if text.chars().count() < max {
                if !text.is_empty() {
                    text.push('\n');
                }
                text.extend(t.chars().take(max - text.chars().count().min(max)));
            }
        }
    }
    (text, n)
}

/// Whether a Claude Code somewhere is still on a session: a `claude` runs
/// with it (its id on the command line), or runs in its folder that Claude
/// Station didn't start for another conversation (a terminal's `claude` or
/// `claude --continue`), or (with `by_time`) its history changed in the
/// last 10 minutes. Such a session is left where it is until it stops.
pub fn in_use(path: &Path, session: &str, folder: Option<&Path>, by_time: bool) -> bool {
    let recent = by_time
        && std::fs::metadata(path)
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age.as_secs() < 600);
    if recent {
        return true;
    }
    let folder = folder.map(|f| std::fs::canonicalize(f).unwrap_or_else(|_| f.to_path_buf()));
    // One process's words and folder: is it a Claude Code on this session?
    let on_it = |args: &[String], cwd: &dyn Fn() -> Option<PathBuf>| -> bool {
        // Mímir's own runs (they carry its approvals hook) are
        // known to it already.
        if args.iter().any(|a| a.contains("hook --task")) || !is_claude_code(args) {
            return false;
        }
        args.iter().any(|a| a.contains(session))
            || folder
                .as_ref()
                .is_some_and(|folder| cwd().is_some_and(|c| &c == folder))
    };
    // Inside a Flatpak the PC's processes come from its own `ps`.
    if let Some(list) = crate::flatpak::host_processes() {
        return list
            .iter()
            .any(|(pid, args)| on_it(args, &|| crate::flatpak::host_cwd(*pid)));
    }
    let me = std::process::id().to_string();
    std::fs::read_dir("/proc")
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| {
            let n = e.file_name().to_string_lossy().to_string();
            n.bytes().all(|b| b.is_ascii_digit()) && n != me
        })
        .any(|e| {
            let Ok(raw) = std::fs::read(e.path().join("cmdline")) else {
                return false;
            };
            let args: Vec<String> = raw
                .split(|b| *b == 0)
                .map(|a| String::from_utf8_lossy(a).to_string())
                .collect();
            on_it(&args, &|| std::fs::read_link(e.path().join("cwd")).ok())
        })
}

/// A Claude Code process (by its program's name).
fn is_claude_code(args: &[String]) -> bool {
    let base = |a: &String| {
        Path::new(a)
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default()
    };
    args.iter()
        .take(2)
        .any(|a| base(a) == "claude" || a.ends_with("claude-code/cli.js"))
}

/// A session file as an earlier session, ready to open as a task.
pub fn past(path: &Path) -> Option<PastSession> {
    let id = path.file_stem()?.to_str()?.to_string();
    let (_, first) = head(path)?;
    let modified_unix = std::fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_secs() as i64);
    Some(PastSession {
        id,
        path: path.to_path_buf(),
        first,
        modified_unix,
        messages: 0,
    })
}

/// Where Claude Code keeps a session of a project.
pub fn session_file(project: &Path, session: &str) -> PathBuf {
    claude_home()
        .join("projects")
        .join(folder_name(project))
        .join(format!("{session}.jsonl"))
}

/// Deletes Claude Code's own record of one session (its transcript and the folder of
/// its side files), wherever it keeps them. Only the exact id is matched.
pub fn delete_session(session: &str) {
    if session.is_empty() || session.contains(['/', '\\']) || session.starts_with('.') {
        return;
    }
    if let Some(file) = find_session(session) {
        let _ = std::fs::remove_file(&file);
        let _ = std::fs::remove_dir_all(file.with_extension(""));
    }
}

/// Finds a session's history wherever Claude Code keeps it (it names the
/// folder after the resolved path and shortens long ones, so the folder is
/// looked for, not worked out).
pub fn find_session(session: &str) -> Option<PathBuf> {
    find_in(&claude_home(), session)
}

fn find_in(home: &Path, session: &str) -> Option<PathBuf> {
    std::fs::read_dir(home.join("projects"))
        .into_iter()
        .flatten()
        .flatten()
        .map(|d| d.path().join(format!("{session}.jsonl")))
        .find(|p| p.is_file())
}

/// The folder Claude Code keeps a project's sessions in: the one named
/// after its resolved path if there, else one holding a session started
/// there, else that name. A path too long for the name (Claude Code
/// shortens those its own way) is refused rather than guessed.
fn history_folder(home: &Path, project: &Path) -> std::io::Result<PathBuf> {
    let real = std::fs::canonicalize(project).unwrap_or_else(|_| project.to_path_buf());
    let named = home.join("projects").join(folder_name(&real));
    if named.is_dir() {
        return Ok(named);
    }
    for d in std::fs::read_dir(home.join("projects"))
        .into_iter()
        .flatten()
        .flatten()
    {
        let found = std::fs::read_dir(d.path())
            .into_iter()
            .flatten()
            .flatten()
            .filter(|f| f.path().extension().is_some_and(|x| x == "jsonl"))
            .take(3)
            .any(|f| read_head(&f.path()).is_some_and(|(cwd, _)| cwd == real || cwd == project));
        if found {
            return Ok(d.path());
        }
    }
    if folder_name(&real).len() > 200 {
        return Err(std::io::Error::other(
            "that folder's path is too long to carry a conversation into",
        ));
    }
    Ok(named)
}

/// Moves a session's history (its `.jsonl` and the folder of the same name
/// beside it, which holds its sub-agents and long results) to where Claude
/// Code keeps `to`'s sessions, so `--resume` finds it when Claude Code runs
/// there; returns its new place. Moved, not copied, and all or nothing: if
/// the folder beside it can't move, the history goes back and no part-made
/// copy stays. Not finding the history is an error.
pub fn move_session(session: &str, from: &Path, to: &Path) -> std::io::Result<PathBuf> {
    move_session_in(&claude_home(), session, from, to)
}

/// The session's history: in `from`'s place first, else wherever it is.
fn locate(home: &Path, session: &str, from: &Path) -> Option<PathBuf> {
    history_folder(home, from)
        .ok()
        .map(|d| d.join(format!("{session}.jsonl")))
        .filter(|p| p.is_file())
        .or_else(|| find_in(home, session))
}

fn move_session_in(home: &Path, session: &str, from: &Path, to: &Path) -> std::io::Result<PathBuf> {
    let src = locate(home, session, from).ok_or_else(|| {
        std::io::Error::new(
            std::io::ErrorKind::NotFound,
            "The history of this conversation wasn't found",
        )
    })?;
    let dir = history_folder(home, to)?;
    let dst = dir.join(format!("{session}.jsonl"));
    if src == dst {
        return Ok(dst);
    }
    let (side, dst_side) = (src.with_extension(""), dst.with_extension(""));
    if dst.exists() || dst_side.exists() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::AlreadyExists,
            "that project already has this conversation",
        ));
    }
    std::fs::create_dir_all(&dir)?;
    move_path(&src, &dst)?;
    if side.is_dir()
        && let Err(e) = move_path(&side, &dst_side)
    {
        if side.is_dir() {
            let _ = std::fs::remove_dir_all(&dst_side);
        } else {
            let _ = move_path(&dst_side, &side);
        }
        move_path(&dst, &src)?;
        return Err(e);
    }
    Ok(dst)
}

/// N9: why a move is refused when the conversation's history is missing, and what to do instead.
pub const NO_HISTORY: &str = "The history of this conversation wasn't found. It can't be moved. Start a new task in the other project instead.";

/// Whether a session could move to `to` now: its history is found and the
/// project hasn't got it already.
pub fn can_move(session: &str, from: &Path, to: &Path) -> Result<(), String> {
    let home = claude_home();
    let src = locate(&home, session, from).ok_or(NO_HISTORY)?;
    let dir = history_folder(&home, to).map_err(|e| format!("{e}."))?;
    let dst = dir.join(format!("{session}.jsonl"));
    if src != dst && (dst.exists() || dst.with_extension("").exists()) {
        return Err("That project already has this conversation.".into());
    }
    Ok(())
}

/// Renames, or copies and then removes the original when a rename can't
/// cross disks.
fn move_path(src: &Path, dst: &Path) -> std::io::Result<()> {
    if std::fs::rename(src, dst).is_ok() {
        return Ok(());
    }
    copy_all(src, dst)?;
    if src.is_dir() {
        std::fs::remove_dir_all(src)
    } else {
        // The copy is whole before the original goes.
        if std::fs::metadata(dst)?.len() != std::fs::metadata(src)?.len() {
            return Err(std::io::Error::other("the copy came out short"));
        }
        std::fs::remove_file(src)
    }
}

fn copy_all(src: &Path, dst: &Path) -> std::io::Result<()> {
    if src.is_dir() {
        std::fs::create_dir_all(dst)?;
        for e in std::fs::read_dir(src)? {
            let e = e?;
            copy_all(&e.path(), &dst.join(e.file_name()))?;
        }
        Ok(())
    } else {
        std::fs::copy(src, dst).map(|_| ())
    }
}

/// When a transcript began (its first dated line), in seconds.
fn first_time(text: &str) -> Option<i64> {
    text.lines().take(50).find_map(|l| {
        let v: Value = serde_json::from_str(l).ok()?;
        let ms = crate::devlog::parse_iso(v.get("timestamp")?.as_str()?)?;
        i64::try_from(ms / 1000).ok()
    })
}

/// Rebuilds a past session as a task, ready to continue.
pub fn to_task(s: &PastSession, project: &Path, task_id: String) -> std::io::Result<Task> {
    let text = std::fs::read_to_string(&s.path)?;
    let title = ai_title(&s.path)
        .or_else(|| {
            (!crate::task::system_like(&s.first)).then(|| crate::runner::title_from(&s.first))
        })
        .unwrap_or_default();
    let mut t = Task::new(
        task_id,
        title,
        project.display().to_string(),
        // When it began (its first line says), not when it last changed.
        first_time(&text).unwrap_or(s.modified_unix),
    );
    for line in text.lines() {
        add_line(&mut t, &without_images(line));
    }
    // Read from a transcript, not seen made: no time rather than a wrong one.
    t.entry_meta.clear();
    if let Some(real) = t.real_title() {
        t.title = real;
    }
    // Steps never got an end time from a transcript; one with no result
    // at all was cut short.
    t.close_open_steps(0);
    for e in &mut t.entries {
        if let crate::task::Entry::Step(st) = e {
            st.took_ms = None;
        }
    }
    t.session_id = Some(s.id.clone());
    t.imported = true;
    t.status = Status::Done;
    t.add_note(OPENED_NOTE);
    Ok(t)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// N9: a move refused for a missing history says what to do instead.
    #[test]
    fn a_missing_history_says_what_to_do_instead() {
        assert!(NO_HISTORY.starts_with("The history of this conversation wasn't found."));
        assert!(
            NO_HISTORY
                .contains("It can't be moved. Start a new task in the other project instead.")
        );
    }

    /// N7: a followed session shows when it began, and its "send a message to
    /// carry on" line stays under the last line.
    #[test]
    fn an_opened_session_starts_when_it_began_and_keeps_its_note_last() {
        let dir = std::env::temp_dir().join(format!("station-open-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("s9.jsonl");
        let u = |ts: &str, text: &str| {
            format!(
                r#"{{"type":"user","timestamp":"{ts}","message":{{"role":"user","content":"{text}"}}}}"#
            )
        };
        std::fs::write(
            &path,
            format!("{}\n", u("2026-11-20T10:00:00.000Z", "first")),
        )
        .unwrap();
        let s = PastSession {
            id: "s9".into(),
            path: path.clone(),
            first: "first".into(),
            modified_unix: 1_800_000_000,
            messages: 1,
        };
        let mut t = to_task(&s, Path::new("/p"), "t9".into()).unwrap();
        assert_eq!(t.created_unix, 1_795_168_800, "the first line's time");
        let from = std::fs::metadata(&path).unwrap().len();
        let mut all = std::fs::read_to_string(&path).unwrap();
        all.push_str(&format!("{}\n", u("2026-11-20T10:05:00.000Z", "later")));
        std::fs::write(&path, all).unwrap();
        follow(&mut t, &path, from).unwrap();
        assert!(
            matches!(t.entries.last(), Some(crate::task::Entry::Note(n)) if n == OPENED_NOTE),
            "{:?}",
            t.entries
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_followed_session_keeps_the_tail_and_no_picture_data() {
        let big = "A".repeat(100_000);
        let line = format!(
            r#"{{"type":"user","message":{{"role":"user","content":[{{"type":"image","source":{{"type":"base64","media_type":"image/png","data":"{big}"}}}},{{"type":"text","text":"look"}}]}}}}"#
        );
        let stripped = without_images(&line);
        assert!(stripped.len() < 1000, "{}", stripped.len());
        assert!(stripped.contains("look"));
        // A line without pictures is left as it is.
        assert!(matches!(
            without_images(r#"{"type":"user"}"#),
            std::borrow::Cow::Borrowed(_)
        ));
        let mut t = Task::new("t".into(), "t".into(), "/p".into(), 0);
        for i in 0..(FOLLOW_TAIL + 50) {
            t.add_you(&format!("message {i}"));
        }
        keep_tail(&mut t);
        // Not cut yet: it may grow a step past the tail.
        assert_eq!(t.entries.len(), FOLLOW_TAIL + 50);
        for i in 250..(FOLLOW_TAIL + FOLLOW_STEP + 5) {
            t.add_you(&format!("message {i}"));
        }
        keep_tail(&mut t);
        assert_eq!(t.entries.len(), FOLLOW_TAIL);
        assert_eq!(t.entries_dropped, 105);
        assert!(format!("{:?}", t.entries.last().unwrap()).contains("message 304"));
        // A question's place counts what was dropped: it never repeats.
        let before = t.entries_dropped + t.entries.len();
        t.add_you("one more");
        assert!(t.entries_dropped + t.entries.len() > before);
    }

    /// Moving a conversation to another project takes its history and the
    /// folder beside it, leaves nothing behind, never overwrites, and says
    /// so when there is nothing to move.
    #[test]
    fn a_moved_session_is_found_under_its_new_project() {
        let home = std::env::temp_dir().join(format!("station-move-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        let (a, b) = (Path::new("/p/Old place"), Path::new("/p/New-place"));
        let old = home.join("projects").join(folder_name(a));
        std::fs::create_dir_all(old.join("s1/subagents")).unwrap();
        std::fs::write(old.join("s1.jsonl"), "{}\n").unwrap();
        std::fs::write(old.join("s1/subagents/x.jsonl"), "{}\n").unwrap();
        let at = move_session_in(&home, "s1", a, b).unwrap();
        let new = home.join("projects").join(folder_name(b));
        assert_eq!(at, new.join("s1.jsonl"));
        assert_eq!(
            std::fs::read_to_string(new.join("s1.jsonl")).unwrap(),
            "{}\n"
        );
        assert!(new.join("s1/subagents/x.jsonl").exists());
        assert!(!old.join("s1.jsonl").exists() && !old.join("s1").exists());
        // Found wherever it is, whatever its folder is called.
        let odd = home.join("projects").join("-some-shortened-name-1a2b");
        std::fs::create_dir_all(&odd).unwrap();
        std::fs::write(odd.join("s2.jsonl"), "{}\n").unwrap();
        move_session_in(&home, "s2", a, b).unwrap();
        assert!(new.join("s2.jsonl").exists() && !odd.join("s2.jsonl").exists());
        // Nothing to move is an error; so is a clash, which changes nothing.
        assert!(move_session_in(&home, "none", a, b).is_err());
        std::fs::write(old.join("s1.jsonl"), "other\n").unwrap();
        std::fs::remove_file(new.join("s1.jsonl")).unwrap();
        assert!(
            move_session_in(&home, "s1", a, b).is_err(),
            "its side folder is there"
        );
        assert_eq!(
            std::fs::read_to_string(old.join("s1.jsonl")).unwrap(),
            "other\n"
        );
        let _ = std::fs::remove_dir_all(&home);
    }

    /// A folder beside the history that can't move puts the history back,
    /// and leaves no part-made copy.
    #[test]
    fn a_half_move_is_undone() {
        use std::os::unix::fs::PermissionsExt;
        let home = std::env::temp_dir().join(format!("station-half-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        let (a, b) = (Path::new("/p/A"), Path::new("/p/B"));
        let old = home.join("projects").join(folder_name(a));
        std::fs::create_dir_all(old.join("s1")).unwrap();
        std::fs::write(old.join("s1.jsonl"), "{}\n").unwrap();
        std::fs::write(old.join("s1/x"), "x").unwrap();
        // A read-only folder can't be moved to another folder or emptied.
        std::fs::set_permissions(old.join("s1"), std::fs::Permissions::from_mode(0o500)).unwrap();
        let r = move_session_in(&home, "s1", a, b);
        std::fs::set_permissions(old.join("s1"), std::fs::Permissions::from_mode(0o700)).unwrap();
        let new = home.join("projects").join(folder_name(b));
        assert!(r.is_err());
        assert!(old.join("s1.jsonl").exists() && old.join("s1/x").exists());
        assert!(!new.join("s1.jsonl").exists() && !new.join("s1").exists());
        let _ = std::fs::remove_dir_all(&home);
    }

    #[test]
    fn a_growing_transcript_keeps_its_title() {
        use std::io::Write;
        let dir = std::env::temp_dir().join(format!("station-title-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("s.jsonl");
        let line = |t: &str| format!("{{\"type\":\"ai-title\",\"aiTitle\":\"{t}\"}}\n");
        std::fs::write(&path, line("First")).unwrap();
        assert_eq!(ai_title(&path).as_deref(), Some("First"));
        let mut f = std::fs::OpenOptions::new()
            .append(true)
            .open(&path)
            .unwrap();
        let next = line("Second");
        let (a, b) = next.split_at(20);
        f.write_all(a.as_bytes()).unwrap();
        // Half a line: the title so far stands.
        assert_eq!(ai_title(&path).as_deref(), Some("First"));
        f.write_all(b.as_bytes()).unwrap();
        assert_eq!(ai_title(&path).as_deref(), Some("Second"));
        // Written anew, shorter: read again from the start.
        std::fs::write(&path, line("New")).unwrap();
        assert_eq!(ai_title(&path).as_deref(), Some("New"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// On this PC's real transcripts (read only), with `SESSIONS_REAL=1`:
    /// every look lists the same sessions while they are being written.
    #[test]
    #[ignore = "reads this PC's own transcripts"]
    fn real_sessions_stay_listed() {
        if std::env::var_os("SESSIONS_REAL").is_none() {
            return;
        }
        let first: Vec<String> = recent(40).into_iter().map(|o| o.session.id).collect();
        assert!(!first.is_empty());
        for _ in 0..30 {
            std::thread::sleep(std::time::Duration::from_millis(500));
            let now: Vec<String> = recent(40).into_iter().map(|o| o.session.id).collect();
            for id in first.iter().take(10) {
                assert!(now.contains(id), "session {id} dropped out of the list");
            }
        }
        eprintln!("{} sessions stayed listed over 15 s", first.len());
    }

    #[test]
    fn iso_times_are_read() {
        assert_eq!(iso_unix("1970-01-01T00:00:00.000Z"), Some(0));
        assert_eq!(iso_unix("2026-11-18T12:01:00.000Z"), Some(1_795_003_260));
        assert_eq!(iso_unix("nonsense"), None);
    }

    /// Bookkeeping Claude Code writes into an idle session doesn't make it
    /// look busy: only messages and tool results count.
    #[test]
    fn bookkeeping_lines_are_not_talk() {
        let dir = std::env::temp_dir().join(format!("station-talk-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("s.jsonl");
        std::fs::write(
            &path,
            concat!(
                r#"{"type":"user","message":{"role":"user","content":"hi"},"timestamp":"2026-11-18T12:00:00.000Z"}"#,
                "\n",
                r#"{"type":"assistant","message":{"role":"assistant","content":[]},"timestamp":"2026-11-18T12:01:00.000Z"}"#,
                "\n",
                r#"{"type":"system","subtype":"bridge_status","timestamp":"2026-11-19T13:35:00.000Z"}"#,
                "\n",
                r#"{"type":"artifact-autoreact-ledger","timestamp":"2026-11-19T13:35:01.000Z"}"#,
                "\n",
                r#"{"type":"user","isMeta":true,"message":{"role":"user","content":"x"},"timestamp":"2026-11-19T13:35:02.000Z"}"#,
                "\n",
            ),
        )
        .unwrap();
        assert_eq!(last_talk(&path), Some(1_795_003_260));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn folder_names_match_claude_code() {
        assert_eq!(
            folder_name(Path::new("/home/user/Projects/mimir")),
            "-home-user-Projects-mimir"
        );
        assert_eq!(folder_name(Path::new("/tmp/a.b_c")), "-tmp-a-b-c");
    }

    fn user_line(origin: &str, meta: bool, text: &str) -> Value {
        let mut v = serde_json::json!({
            "type": "user",
            "message": {"role": "user", "content": text},
        });
        if !origin.is_empty() {
            v["origin"] = serde_json::json!({"kind": origin});
        }
        if meta {
            v["isMeta"] = Value::Bool(true);
        }
        v
    }

    const DONE: &str = "<task-notification>\n<task-id>abc</task-id>\n<tool-use-id>toolu_1</tool-use-id>\n<output-file>/tmp/x/out.output</output-file>\n<status>completed</status>\n<summary>Agent \"Garden planner fixes\" finished</summary>\n</task-notification>";

    fn shown(lines: &[Value]) -> Vec<String> {
        let mut t = Task::new("t".into(), "t".into(), "/work/proj".into(), 0);
        for l in lines {
            add_line(&mut t, &l.to_string());
        }
        t.entries.iter().map(|e| format!("{e:?}")).collect()
    }

    #[test]
    fn system_lines_become_small_notes_or_nothing() {
        let out = shown(&[
            user_line("human", false, "Fix the build"),
            user_line("task-notification", false, DONE),
            user_line(
                "peer",
                true,
                "Another Claude session sent a message:\n<msg from=\"x\">Done, all green</msg>",
            ),
            user_line("", true, "Base directory for this skill: /x"),
            user_line("", true, "<system-reminder>hi</system-reminder>"),
            user_line(
                "",
                false,
                "<command-name>/model</command-name>\n<command-args></command-args>",
            ),
            user_line("", false, "<command-name>/clear</command-name>"),
            user_line(
                "",
                false,
                "<local-command-stdout>Set model</local-command-stdout>",
            ),
            user_line("", false, "[Request interrupted by user]"),
        ]);
        assert_eq!(
            out,
            [
                "You(\"Fix the build\")",
                "Note(\"A helper finished: Garden planner fixes\")",
                "Note(\"A helper reported back\")",
                "Note(\"Changed the model\")",
                "Note(\"Ran /clear\")",
                "Note(\"You stopped it here.\")",
            ]
        );
        let all = out.join(" ");
        assert!(!all.contains('<') && !all.contains("/tmp") && !all.contains("toolu"));
    }

    #[test]
    fn an_old_transcript_without_origin_is_told_by_shape() {
        let out = shown(&[
            user_line("", false, DONE),
            user_line("", false, "Another Claude session sent a message: hi"),
            user_line("", false, "Please also add a note"),
        ]);
        assert_eq!(
            out,
            [
                "Note(\"A helper finished: Garden planner fixes\")",
                "Note(\"A helper reported back\")",
                "You(\"Please also add a note\")",
            ]
        );
    }

    #[test]
    fn a_person_message_with_summary_tags_stays_theirs() {
        let text = "Write a <summary>tag</summary> parser";
        let out = shown(&[user_line("human", false, text), user_line("", false, text)]);
        assert_eq!(out.len(), 2);
        assert!(out.iter().all(|e| e.starts_with("You(")));
    }

    #[test]
    fn only_helpers_that_finished_are_counted_as_finished() {
        let line = |status: &str, summary: &str| {
            format!(
                "<task-notification>\n<task-id>a</task-id>\n<status>{status}</status>\n<summary>{summary}</summary>\n</task-notification>"
            )
        };
        let out = shown(&[
            user_line(
                "task-notification",
                false,
                &line("completed", "Agent \"A\" finished"),
            ),
            user_line(
                "task-notification",
                false,
                &line("completed", "Agent \"B\" finished"),
            ),
            user_line(
                "task-notification",
                false,
                &line("failed", "Agent \"C\" failed"),
            ),
            user_line(
                "task-notification",
                false,
                &line("completed", "Background command \"ls\" completed"),
            ),
            user_line(
                "task-notification",
                false,
                &line("completed", "Agent \"D\" finished"),
            ),
        ]);
        assert_eq!(
            out,
            [
                "Note(\"2 helpers finished\")",
                "Note(\"A helper stopped\")",
                "Note(\"A background command finished\")",
                "Note(\"A helper finished: D\")",
            ]
        );
    }

    #[test]
    fn several_helpers_in_a_row_become_one_line() {
        let out = shown(&[
            user_line("task-notification", false, DONE),
            user_line("task-notification", false, DONE),
            user_line("task-notification", false, DONE),
        ]);
        assert_eq!(out, ["Note(\"3 helpers finished\")"]);
    }

    #[test]
    fn a_transcript_becomes_a_task() {
        let dir = std::env::temp_dir().join(format!("station-sess-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let project = Path::new("/work/proj");
        let folder = dir.join("projects").join(folder_name(project));
        std::fs::create_dir_all(&folder).unwrap();
        let lines = [
            r#"{"type":"session-owner","sessionId":"s1"}"#,
            r#"{"type":"user","isSidechain":false,"message":{"role":"user","content":"Fix the build"}}"#,
            r#"{"type":"user","isMeta":true,"message":{"role":"user","content":"Caveat: ignore"}}"#,
            r#"{"type":"assistant","isSidechain":false,"message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"cargo build","description":"Build"}}]}}"#,
            r#"{"type":"user","isSidechain":false,"message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]},"tool_use_result":{"stdout":"ok"}}"#,
            r#"{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"text","text":"helper chatter"}]}}"#,
            r#"{"type":"assistant","isSidechain":false,"message":{"content":[{"type":"text","text":"Built fine."}]}}"#,
        ];
        std::fs::write(folder.join("s1.jsonl"), lines.join("\n")).unwrap();
        // SAFETY: tests in this module do not read the variable concurrently.
        unsafe { std::env::set_var("CLAUDE_CONFIG_DIR", &dir) };
        let found = list(project);
        unsafe { std::env::remove_var("CLAUDE_CONFIG_DIR") };
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].first, "Fix the build");
        assert_eq!(found[0].messages, 1);
        let t = to_task(&found[0], project, "x".into()).unwrap();
        assert_eq!(t.session_id.as_deref(), Some("s1"));
        assert_eq!(t.title, "Fix the build");
        let shown: Vec<String> = t.entries.iter().map(|e| format!("{e:?}")).collect();
        assert!(shown[0].contains("Fix the build"));
        assert!(
            shown
                .iter()
                .any(|e| e.contains("Build") && e.contains("Step"))
        );
        assert!(shown.iter().any(|e| e.contains("Built fine.")));
        assert!(!shown.iter().any(|e| e.contains("helper chatter")));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn hidden_wrappers_are_removed() {
        assert_eq!(
            clean_text("<system-reminder>tz</system-reminder>\nfix it"),
            "fix it"
        );
        assert_eq!(
            clean_text("<pasted_content id=\"cb13\">\nYou are the lead\n</pasted_content>"),
            "You are the lead"
        );
        assert_eq!(clean_text("<system-reminder>only"), "");
    }

    #[test]
    fn recent_sessions_from_every_project() {
        let root = std::env::temp_dir().join(format!("station-recent-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let home = std::env::var("HOME").unwrap();
        let real = PathBuf::from(&home);
        for (folder, id, cwd, text) in [
            ("-home-a", "s1", home.as_str(), "fix the bug"),
            ("-home-b", "s2", home.as_str(), "plan a trip"),
            ("-tmp-x", "s3", "/tmp/x", "scratch"),
            ("-gone", "s4", "/no/such/folder", "old"),
        ] {
            let d = root.join(folder);
            std::fs::create_dir_all(&d).unwrap();
            let lines = format!(
                "{{\"type\":\"summary\"}}\n{{\"type\":\"user\",\"cwd\":\"{cwd}\",\"message\":{{\"role\":\"user\",\"content\":\"{text}\"}}}}\n"
            );
            std::fs::write(d.join(format!("{id}.jsonl")), lines).unwrap();
            std::thread::sleep(std::time::Duration::from_millis(1100));
        }
        let got = recent_in(&root, 10);
        let firsts: Vec<&str> = got.iter().map(|o| o.session.first.as_str()).collect();
        assert_eq!(firsts, ["plan a trip", "fix the bug"]);
        assert_eq!(got[0].project, real);
        assert_eq!(recent_in(&root, 1).len(), 1);
        std::fs::remove_dir_all(&root).unwrap();
    }
}
