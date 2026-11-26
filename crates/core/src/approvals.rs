//! Approvals: Claude Code's PermissionRequest hook runs
//! `mimir hook --task <id>`, which asks the window over a private
//! Unix socket and prints the owner's answer back to Claude Code.
//!
//! Hook input and output shapes are the documented ones, checked against
//! Claude Code 2.1.325.

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::thread;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Answer {
    Allow,
    /// Allow, and remember for this project.
    Always,
    No,
    /// The owner paused or stopped the task (or closed the window) while
    /// this was waiting: not a "no" to the step itself.
    Halted,
}

/// One question waiting for the owner.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Request {
    pub task: String,
    pub tool_name: String,
    pub tool_input: Value,
    #[serde(default)]
    pub permission_suggestions: Value,
}

impl Request {
    /// Plain words for the orange bar, e.g. "Run a command: cargo build".
    pub fn words(&self) -> String {
        let get = |k: &str| {
            self.tool_input
                .get(k)
                .and_then(Value::as_str)
                .unwrap_or_default()
        };
        match self.tool_name.as_str() {
            "Bash" => format!("Run a command on this PC: {}", get("command")),
            "Edit" | "MultiEdit" => format!("Change {}", get("file_path")),
            "Write" => format!("Write {}", get("file_path")),
            "NotebookEdit" => format!("Change {}", get("notebook_path")),
            "WebFetch" => format!("Open a web page: {}", get("url")),
            "WebSearch" => format!("Search the web for: {}", get("query")),
            other => format!("Use {}", other.trim_start_matches("mcp__")),
        }
    }

    /// The path in the request, relative to the project when inside it.
    fn short_path(&self, key: &str, project: &Path) -> String {
        let full = self
            .tool_input
            .get(key)
            .and_then(Value::as_str)
            .unwrap_or_default();
        Path::new(full)
            .strip_prefix(project)
            .map(|p| p.display().to_string())
            .unwrap_or_else(|_| full.to_string())
    }

    /// A short headline for the OK bar, e.g. "Mímir wants to change app.py".
    pub fn title(&self, project: &Path) -> String {
        match self.tool_name.as_str() {
            // Claude Code gives each command a plain description; lead with it.
            "Bash" => match self
                .tool_input
                .get("description")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|d| !d.is_empty())
            {
                Some(d) => {
                    let mut c = d.chars();
                    let first = c.next().map(|f| f.to_lowercase().collect::<String>());
                    format!(
                        "Mímir wants to {}{}",
                        first.unwrap_or_default(),
                        c.as_str().trim_end_matches('.')
                    )
                }
                None => "Mímir wants to run a command".into(),
            },
            "Edit" | "MultiEdit" => {
                format!(
                    "Mímir wants to change {}",
                    self.short_path("file_path", project)
                )
            }
            "Write" => {
                let full = self.tool_input.get("file_path").and_then(Value::as_str);
                let verb = if full.is_some_and(|f| Path::new(f).exists()) {
                    "replace"
                } else {
                    "create"
                };
                format!(
                    "Mímir wants to {verb} {}",
                    self.short_path("file_path", project)
                )
            }
            "NotebookEdit" => format!(
                "Mímir wants to change {}",
                self.short_path("notebook_path", project)
            ),
            "WebFetch" => "Mímir wants to open a web page".into(),
            "WebSearch" => "Mímir wants to search the web".into(),
            other => format!("Mímir wants to use {}", other.trim_start_matches("mcp__")),
        }
    }

    /// The detail under the headline: the command, address or search.
    pub fn detail(&self) -> Option<String> {
        let key = match self.tool_name.as_str() {
            "Bash" => "command",
            "WebFetch" => "url",
            "WebSearch" => "query",
            _ => return None,
        };
        self.tool_input
            .get(key)
            .and_then(Value::as_str)
            .map(str::to_string)
    }

    /// The lines an edit would take out (-) and put in (+), for a preview.
    pub fn preview(&self) -> Vec<String> {
        let get = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_string();
        let pair = |old: &str, new: &str| {
            let mut out: Vec<String> = old.lines().map(|l| format!("-{l}")).collect();
            out.extend(new.lines().map(|l| format!("+{l}")));
            out
        };
        let mut lines = match self.tool_name.as_str() {
            "Edit" => pair(
                &get(&self.tool_input, "old_string"),
                &get(&self.tool_input, "new_string"),
            ),
            "MultiEdit" => self
                .tool_input
                .get("edits")
                .and_then(Value::as_array)
                .map(|edits| {
                    let mut out = vec![];
                    for (n, e) in edits.iter().enumerate() {
                        if n > 0 {
                            out.push("@@".to_string());
                        }
                        out.extend(pair(&get(e, "old_string"), &get(e, "new_string")));
                    }
                    out
                })
                .unwrap_or_default(),
            "Write" => get(&self.tool_input, "content")
                .lines()
                .map(|l| format!("+{l}"))
                .collect(),
            _ => vec![],
        };
        const MAX: usize = 400;
        if lines.len() > MAX {
            let more = lines.len() - MAX;
            lines.truncate(MAX);
            lines.push(format!("@@ and {more} more lines"));
        }
        lines
    }

    /// The rule "Always for this project" saves, in Claude Code's rule
    /// syntax, e.g. `Bash(cargo build *)`.
    pub fn always_rule(&self) -> (String, Option<String>) {
        // A risky or compound command is only ever allowed exactly as asked,
        // whatever broader rule Claude Code suggests.
        if self.tool_name == "Bash" {
            let cmd = self
                .tool_input
                .get("command")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .trim();
            if bash_rule(cmd) == cmd {
                return ("Bash".into(), Some(cmd.to_string()));
            }
        }
        // Otherwise prefer a rule Claude Code itself suggested, but reject
        // any Bash rule that is too broad (just `tool *` for a subcommand
        // tool, with no subcommand) — PCS-13.
        if let Some(list) = self.permission_suggestions.as_array() {
            for s in list {
                if s.get("type").and_then(Value::as_str) == Some("addRules")
                    && let Some(r) = s.pointer("/rules/0")
                {
                    let tool = r
                        .get("toolName")
                        .and_then(Value::as_str)
                        .unwrap_or(&self.tool_name);
                    let content = r
                        .get("ruleContent")
                        .and_then(Value::as_str)
                        .map(str::to_string);
                    // Reject a Bash rule that is just "tool *" (too broad for
                    // a subcommand tool — no subcommand specified).
                    if tool == "Bash"
                        && let Some(ref c) = content
                        && suggestion_too_broad(c)
                    {
                        // Fall through to bash_rule(cmd) below.
                        break;
                    }
                    return (tool.to_string(), content);
                }
            }
        }
        if self.tool_name == "Bash" {
            let cmd = self
                .tool_input
                .get("command")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .trim();
            return ("Bash".into(), Some(bash_rule(cmd)));
        }
        (self.tool_name.clone(), None)
    }

    /// Whether a command reaches beyond the project folder in a way that
    /// can't be taken back: it sends something off this PC (push, publish,
    /// upload, another machine), or changes the PC outside the project
    /// (administrator rights, installing, deleting elsewhere).
    pub fn reach(&self, project: &Path) -> Option<Reach> {
        if self.tool_name != "Bash" {
            return None;
        }
        let cmd = self.tool_input.get("command").and_then(Value::as_str)?;
        command_reach(cmd, project)
    }

    pub fn always_rule_text(&self) -> String {
        match self.always_rule() {
            (t, Some(c)) => format!("{t}({c})"),
            (t, None) => t,
        }
    }
}

/// How far a command reaches (see `Request::reach`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reach {
    /// Sends something off this PC.
    LeavesPc,
    /// Changes this PC outside the project folder.
    OutsideProject,
}

impl Reach {
    /// The note shown with the command.
    pub fn words(self) -> &'static str {
        match self {
            Reach::LeavesPc => "Leaves this PC · can't be undone",
            Reach::OutsideProject => "Reaches outside this project · can't be undone",
        }
    }
}

/// `Request::reach` for a command line. Each part of a chain (`&&`, `;`,
/// `|`) is looked at; the widest reach wins.
pub fn command_reach(cmd: &str, project: &Path) -> Option<Reach> {
    let mut found = None;
    for part in cmd.split(['&', ';', '|', '\n']) {
        let words: Vec<&str> = part.split_whitespace().collect();
        // Skip `VAR=value` prefixes and `sudo` itself (which already counts).
        let mut i = 0;
        let mut sudo = false;
        while i < words.len()
            && (words[i] == "sudo"
                || words[i] == "env"
                || (words[i].contains('=') && !words[i].starts_with('-')))
        {
            sudo |= words[i] == "sudo";
            i += 1;
        }
        let Some(tool) = words.get(i).map(|w| w.rsplit('/').next().unwrap_or(w)) else {
            continue;
        };
        let rest = &words[i + 1..];
        let has = |w: &str| rest.contains(&w);
        let sub = rest.iter().find(|w| !w.starts_with('-')).copied();
        let leaves = match tool {
            "git" => matches!(sub, Some("push")),
            "gh" => {
                matches!(
                    sub,
                    Some("release" | "pr" | "issue" | "repo" | "gist" | "workflow" | "api")
                ) && rest.iter().any(|w| {
                    matches!(
                        *w,
                        "create"
                            | "merge"
                            | "close"
                            | "delete"
                            | "edit"
                            | "upload"
                            | "comment"
                            | "run"
                            | "-X"
                            | "--method"
                            | "-f"
                            | "-F"
                    )
                })
            }
            "npm" | "pnpm" | "yarn" | "cargo" | "twine" | "gem" => {
                matches!(sub, Some("publish" | "upload" | "push"))
            }
            "docker" | "podman" => matches!(sub, Some("push" | "login")),
            "scp" | "sftp" | "ssh" | "rsync" if tool != "rsync" => true,
            "rsync" => rest.iter().any(|w| w.contains(':') && !w.starts_with('-')),
            "curl" => rest.iter().any(|w| {
                matches!(
                    *w,
                    "-d" | "--data"
                        | "--data-binary"
                        | "--data-raw"
                        | "-F"
                        | "--form"
                        | "-T"
                        | "--upload-file"
                ) || (*w == "-X" || *w == "--request")
            }),
            "wget" => rest
                .iter()
                .any(|w| w.starts_with("--post") || w.starts_with("--method")),
            _ => false,
        };
        let outside = sudo
            || match tool {
                "apt" | "apt-get" | "dnf" | "yum" | "zypper" | "brew" | "flatpak" | "snap" => {
                    matches!(
                        sub,
                        Some("install" | "remove" | "uninstall" | "purge" | "upgrade")
                    )
                }
                "pacman" => rest
                    .iter()
                    .any(|w| w.starts_with("-S") || w.starts_with("-R")),
                "pip" | "pip3" | "pipx" => matches!(sub, Some("install" | "uninstall")),
                "npm" | "pnpm" | "yarn" => {
                    matches!(sub, Some("install" | "add" | "i")) && (has("-g") || has("--global"))
                }
                "cargo" => matches!(sub, Some("install" | "uninstall")),
                "rm" | "rmdir" | "shred" | "mv" | "chmod" | "chown" | "dd" | "truncate" => rest
                    .iter()
                    .filter(|w| !w.starts_with('-'))
                    .any(|w| outside_project(w, project)),
                "systemctl" | "loginctl" | "reboot" | "shutdown" | "poweroff" | "mkfs"
                | "mount" | "umount" => true,
                _ => false,
            };
        if leaves {
            return Some(Reach::LeavesPc);
        }
        if outside {
            found = Some(Reach::OutsideProject);
        }
    }
    found
}

/// Whether a path a command names lies outside the project folder.
fn outside_project(word: &str, project: &Path) -> bool {
    let w = word.trim_matches(['"', '\'']);
    if w.starts_with('~') || w.starts_with('$') || w.split('/').any(|p| p == "..") {
        return true;
    }
    if let Some(of) = w.strip_prefix("of=") {
        return outside_project(of, project);
    }
    Path::new(w).is_absolute() && !Path::new(w).starts_with(project)
}

/// Tools whose second word picks what they do (`cargo build`, `git status`).
const SUBCOMMAND_TOOLS: &[&str] = &[
    "cargo",
    "git",
    "npm",
    "pnpm",
    "yarn",
    "gh",
    "rustup",
    "make",
    "go",
    "pip",
    "flatpak",
    "distrobox",
    "podman",
    "docker",
    "systemctl",
    "just",
];

/// Commands that only look or make harmless things.
const HARMLESS: &[&str] = &[
    "ls", "cat", "head", "tail", "grep", "rg", "echo", "touch", "mkdir", "pwd", "wc", "sort",
    "diff", "stat", "file", "which", "tree", "du", "df",
];

/// (tool, subcommand) pairs never widened to "any arguments".
const RISKY_SUBCOMMANDS: &[(&str, &str)] = &[
    ("git", "reset"),
    ("git", "checkout"),
    ("git", "restore"),
    ("git", "clean"),
    ("git", "push"),
    ("git", "rebase"),
    ("git", "stash"),
    ("git", "branch"),
    ("git", "rm"),
    ("docker", "run"),
    ("docker", "exec"),
    ("docker", "rm"),
    ("podman", "run"),
    ("podman", "exec"),
    ("distrobox", "enter"),
    ("distrobox", "rm"),
    ("systemctl", "stop"),
    ("systemctl", "disable"),
    ("cargo", "publish"),
    ("npm", "publish"),
];

/// True when a suggested Bash rule is too broad to accept as "Always allow"
/// (PCS-13): a subcommand tool with only `*` as its argument, e.g. `make *`.
fn suggestion_too_broad(rule: &str) -> bool {
    let words: Vec<&str> = rule.split_whitespace().collect();
    // Pattern: `tool *` with no subcommand between tool and `*`.
    words.len() == 2 && words[1] == "*" && SUBCOMMAND_TOOLS.contains(&words[0])
}

/// A rule broad enough to be useful and never broader than safe: anything
/// unfamiliar or with shell tricks is allowed only exactly as asked.
fn bash_rule(cmd: &str) -> String {
    let simple = !cmd.contains(['&', '|', ';', '>', '<', '`', '$', '\n']);
    let words: Vec<&str> = cmd.split_whitespace().collect();
    match words.as_slice() {
        // Subcommands that can throw work away, publish it or run anything:
        // allowed only exactly as asked.
        [tool, sub, ..] if RISKY_SUBCOMMANDS.contains(&(*tool, *sub)) => cmd.to_string(),
        [tool, sub, ..] if simple && SUBCOMMAND_TOOLS.contains(tool) && !sub.starts_with('-') => {
            format!("{tool} {sub} *")
        }
        [tool, ..] if simple && HARMLESS.contains(tool) => format!("{tool} *"),
        _ => cmd.to_string(),
    }
}

/// A saved rule in plain words, for "Always allow stops asking about …":
/// `Edit` → "file changes", `Bash(git status *)` → "running git status …".
pub fn rule_in_words(rule: &str) -> String {
    let (tool, inner) = match rule.split_once('(') {
        Some((t, rest)) => (t, Some(rest.trim_end_matches(')'))),
        None => (rule, None),
    };
    match (tool, inner) {
        ("Edit" | "Write" | "MultiEdit" | "NotebookEdit", None) => "file changes".into(),
        ("Edit" | "Write" | "MultiEdit", Some(p)) => format!("changes to {p}"),
        ("Read", Some(p)) => format!("reading {}", p.trim_start_matches('/')),
        ("Read", None) => "reading files".into(),
        ("Bash", Some(c)) => {
            let c = c.trim_end_matches(" *").trim_end_matches(":*");
            let short: String = c.chars().take(50).collect();
            let more = if c.chars().count() > 50 || rule.ends_with(" *)") {
                " …"
            } else {
                ""
            };
            format!("running {short}{more}")
        }
        ("Bash", None) => "running commands".into(),
        ("WebFetch", _) => "opening web pages".into(),
        ("WebSearch", _) => "web searches".into(),
        (other, _) => format!("using {}", other.trim_start_matches("mcp__")),
    }
}

/// What the hook prints for Claude Code.
pub fn hook_output(req: &Request, answer: Answer, why_no: &str) -> Value {
    let decision = match answer {
        Answer::Allow => json!({ "behavior": "allow" }),
        Answer::Always => {
            let (tool, content) = req.always_rule();
            let mut rule = json!({ "toolName": tool });
            if let Some(c) = content {
                rule["ruleContent"] = json!(c);
            }
            json!({
                "behavior": "allow",
                "updatedPermissions": [{
                    "type": "addRules",
                    "rules": [rule],
                    "behavior": "allow",
                    "destination": "localSettings"
                }]
            })
        }
        Answer::No | Answer::Halted => json!({ "behavior": "deny", "message": why_no }),
    };
    json!({
        "hookSpecificOutput": {
            "hookEventName": "PermissionRequest",
            "decision": decision
        }
    })
}

/// Where the window listens: `$XDG_RUNTIME_DIR/mimir/approvals.sock`.
pub fn default_socket() -> PathBuf {
    crate::flatpak::runtime_base()
        .join("mimir")
        .join("approvals.sock")
}

/// The `--settings` JSON that makes Claude Code ask us.
pub fn hook_settings(exe: &Path, task: &str, socket: &Path) -> String {
    // Claude Code runs on the PC itself, so inside a Flatpak the hook is
    // started through `flatpak run`.
    let program = crate::flatpak::own_command_words(exe)
        .iter()
        .map(|w| shell_quote(w))
        .collect::<Vec<_>>()
        .join(" ");
    let cmd = format!(
        "{program} hook --task {} --socket {}",
        shell_quote(task),
        shell_quote(&socket.display().to_string())
    );
    json!({
        "hooks": { "PermissionRequest": [{
            "matcher": "*",
            // A day: the owner may be away from the PC.
            "hooks": [{ "type": "command", "command": cmd, "timeout": 86400 }]
        }]}
    })
    .to_string()
}

fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// A question that reached the window, with the way to answer it.
pub struct Pending {
    pub request: Request,
    stream: UnixStream,
}

impl Pending {
    pub fn answer(mut self, answer: Answer) {
        let line = serde_json::to_string(&answer).unwrap_or_default();
        // If the hook already gave up, there is no one to tell.
        let _ = writeln!(self.stream, "{line}");
    }
}

/// Keeps the socket open; dropping it stops listening and removes the
/// socket, so no question can wait on a window that is gone.
pub struct Listener {
    socket: PathBuf,
    stop: Arc<AtomicBool>,
}

impl Drop for Listener {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        // Wake the listening thread so it sees the stop.
        let _ = UnixStream::connect(&self.socket);
        let _ = std::fs::remove_file(&self.socket);
    }
}

/// Listens for hook questions. Each one arrives on `tx`; `wake` lets the
/// window redraw.
pub fn serve(
    socket: &Path,
    tx: Sender<Pending>,
    wake: impl Fn() + Send + 'static,
) -> std::io::Result<Listener> {
    serve_for(socket, None, tx, wake)
}

/// Listens for one task's hook questions on its own socket. Whatever task
/// a request names, it is taken as this task's.
pub fn serve_task(
    socket: &Path,
    task: &str,
    tx: Sender<Pending>,
    wake: impl Fn() + Send + 'static,
) -> std::io::Result<Listener> {
    serve_for(socket, Some(task.to_string()), tx, wake)
}

fn serve_for(
    socket: &Path,
    task: Option<String>,
    tx: Sender<Pending>,
    wake: impl Fn() + Send + 'static,
) -> std::io::Result<Listener> {
    let dir = socket.parent().unwrap_or(Path::new("/"));
    std::fs::create_dir_all(dir)?;
    std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))?;
    if UnixStream::connect(socket).is_ok() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::AddrInUse,
            "Mímir is already open",
        ));
    }
    let _ = std::fs::remove_file(socket);
    let listener = UnixListener::bind(socket)?;
    std::fs::set_permissions(socket, std::fs::Permissions::from_mode(0o600))?;
    let stop = Arc::new(AtomicBool::new(false));
    let stopped = stop.clone();
    thread::spawn(move || {
        for conn in listener.incoming() {
            if stopped.load(Ordering::SeqCst) {
                return;
            }
            let Ok(stream) = conn else { continue };
            let Ok(read_half) = stream.try_clone() else {
                continue;
            };
            // A caller that never says anything cannot block the others.
            let _ = read_half.set_read_timeout(Some(Duration::from_secs(5)));
            let mut line = String::new();
            if BufReader::new(read_half.take(4 << 20))
                .read_line(&mut line)
                .is_err()
            {
                continue;
            }
            let Ok(mut request) = serde_json::from_str::<Request>(&line) else {
                continue;
            };
            if let Some(t) = &task {
                request.task = t.clone();
            }
            let _ = stream.set_read_timeout(None);
            if tx.send(Pending { request, stream }).is_err() {
                return;
            }
            wake();
        }
    });
    Ok(Listener {
        socket: socket.to_path_buf(),
        stop,
    })
}

/// The hook side: ask the window, wait for the owner. Any trouble means No,
/// so nothing runs without the owner's OK.
pub fn ask(socket: &Path, request: &Request) -> (Answer, &'static str) {
    let Ok(mut s) = UnixStream::connect(socket) else {
        return (Answer::No, "Mímir is closed, so nobody could say yes.");
    };
    let line = serde_json::to_string(request).unwrap_or_default();
    if writeln!(s, "{line}").is_err() {
        return (Answer::No, "Mímir could not be asked.");
    }
    let mut reply = String::new();
    match BufReader::new(s).read_line(&mut reply) {
        Ok(n) if n > 0 => match serde_json::from_str::<Answer>(reply.trim()) {
            Ok(Answer::No) => (Answer::No, "The owner said no."),
            Ok(Answer::Halted) => (
                Answer::Halted,
                "The owner paused this task before answering. This is not a no: \
                 when they continue, ask again before doing this step.",
            ),
            Ok(a) => (a, ""),
            Err(_) => (Answer::No, "Mímir gave an unclear answer."),
        },
        _ => (Answer::No, "Mímir closed before the owner answered."),
    }
}

/// `mimir hook --task <id> --socket <path>`: reads the hook input
/// on stdin and prints the decision. Returns the exit code.
pub fn run_hook(args: &[String]) -> i32 {
    let mut task = String::new();
    let mut socket = default_socket();
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--task" => task = it.next().cloned().unwrap_or_default(),
            "--socket" => socket = it.next().map(PathBuf::from).unwrap_or(socket),
            _ => {}
        }
    }
    let mut input = String::new();
    if std::io::stdin().read_to_string(&mut input).is_err() {
        return 1;
    }
    let v: Value = serde_json::from_str(&input).unwrap_or(Value::Null);
    let request = Request {
        task,
        tool_name: v
            .get("tool_name")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string(),
        tool_input: v.get("tool_input").cloned().unwrap_or(Value::Null),
        permission_suggestions: v
            .get("permission_suggestions")
            .cloned()
            .unwrap_or(Value::Null),
    };
    let (answer, why) = ask(&socket, &request);
    println!("{}", hook_output(&request, answer, why));
    0
}

#[cfg(test)]
mod tests {
    #[test]
    fn commands_that_reach_out_are_marked() {
        let p = Path::new("/home/me/proj");
        let leaves = |c| command_reach(c, p) == Some(Reach::LeavesPc);
        let outside = |c| command_reach(c, p) == Some(Reach::OutsideProject);
        let stays = |c| command_reach(c, p).is_none();
        assert!(leaves("git push origin main"));
        assert!(leaves(
            "git tag -a v0.68 -m 'Version 0.68' && git push origin v0.68"
        ));
        assert!(leaves("gh release create v1 --notes x"));
        assert!(leaves("cargo publish"));
        assert!(leaves("curl -X POST https://example.com -d a=b"));
        assert!(leaves("scp out.zip host:/tmp/"));
        assert!(outside("sudo systemctl restart foo"));
        assert!(outside("pip install requests"));
        assert!(outside("npm install -g typescript"));
        assert!(outside("rm -rf ~/Downloads/old"));
        assert!(outside("rm -rf /home/me/other"));
        assert!(outside("rm ../notes.txt"));
        assert!(stays("git status"));
        assert!(stays("git tag -a v1 -m x"));
        assert!(stays("cargo build --release"));
        assert!(stays("npm install"));
        assert!(stays("rm -rf target"));
        assert!(stays("rm /home/me/proj/old.txt"));
        assert!(stays("curl https://example.com/file.txt"));
        assert!(stays("gh release list"));
        assert!(stays("touch made-by-test.txt"));
    }

    use super::*;

    fn bash(cmd: &str) -> Request {
        Request {
            task: "t".into(),
            tool_name: "Bash".into(),
            tool_input: json!({ "command": cmd }),
            permission_suggestions: Value::Null,
        }
    }

    #[test]
    fn always_rules() {
        assert_eq!(
            bash("cargo build --release").always_rule_text(),
            "Bash(cargo build *)"
        );
        assert_eq!(bash("ls").always_rule_text(), "Bash(ls *)");
        assert_eq!(
            bash("git reset HEAD f").always_rule_text(),
            "Bash(git reset HEAD f)"
        );
        assert_eq!(
            bash("git status -s").always_rule_text(),
            "Bash(git status *)"
        );
        assert_eq!(
            bash("rm -rf x && ls").always_rule_text(),
            "Bash(rm -rf x && ls)"
        );
        let mut r = bash("x");
        r.permission_suggestions = json!([
            { "type": "setMode", "mode": "acceptEdits" },
            { "type": "addRules", "rules": [{ "toolName": "Bash", "ruleContent": "npm test" }] }
        ]);
        assert_eq!(r.always_rule_text(), "Bash(x)");
        let mut g = bash("git branch new-feature");
        g.permission_suggestions = json!([
            { "type": "addRules", "rules": [{ "toolName": "Bash", "ruleContent": "git branch *" }] }
        ]);
        assert_eq!(g.always_rule_text(), "Bash(git branch new-feature)");
        let mut ok = bash("npm test");
        ok.permission_suggestions = json!([
            { "type": "addRules", "rules": [{ "toolName": "Bash", "ruleContent": "npm test" }] }
        ]);
        assert_eq!(ok.always_rule_text(), "Bash(npm test)");
        let w = Request {
            tool_name: "WebFetch".into(),
            ..bash("")
        };
        assert_eq!(w.always_rule_text(), "WebFetch");
    }

    #[test]
    fn outputs_match_the_documented_shape() {
        let r = bash("touch a");
        let no = hook_output(&r, Answer::No, "The owner said no.");
        assert_eq!(
            no["hookSpecificOutput"]["hookEventName"],
            "PermissionRequest"
        );
        assert_eq!(no["hookSpecificOutput"]["decision"]["behavior"], "deny");
        let always = hook_output(&r, Answer::Always, "");
        let up = &always["hookSpecificOutput"]["decision"]["updatedPermissions"][0];
        assert_eq!(up["destination"], "localSettings");
        assert_eq!(up["rules"][0]["ruleContent"], "touch *");
    }

    #[test]
    fn closed_window_means_no() {
        let (a, why) = ask(Path::new("/nonexistent/sock"), &bash("ls"));
        assert_eq!(a, Answer::No);
        assert!(why.contains("closed"));
    }

    #[test]
    fn rules_in_words() {
        assert_eq!(rule_in_words("Edit"), "file changes");
        assert_eq!(rule_in_words("Bash(git status *)"), "running git status …");
        assert_eq!(
            rule_in_words("Bash(python3 price.py)"),
            "running python3 price.py"
        );
        assert_eq!(rule_in_words("WebFetch"), "opening web pages");
    }

    #[test]
    fn ok_bar_words() {
        let req = |tool: &str, input: Value| Request {
            task: "t".into(),
            tool_name: tool.into(),
            tool_input: input,
            permission_suggestions: Value::Null,
        };
        let project = Path::new("/home/x/app");
        let e = req(
            "Edit",
            json!({"file_path": "/home/x/app/src/main.py", "old_string": "a\nb", "new_string": "c"}),
        );
        assert_eq!(e.title(project), "Mímir wants to change src/main.py");
        assert_eq!(e.preview(), ["-a", "-b", "+c"]);
        assert_eq!(e.detail(), None);
        let w = req(
            "Write",
            json!({"file_path": "/nowhere/new.txt", "content": "hi"}),
        );
        assert_eq!(w.title(project), "Mímir wants to create /nowhere/new.txt");
        assert_eq!(w.preview(), ["+hi"]);
        let b = req("Bash", json!({"command": "pytest -q"}));
        assert_eq!(b.title(project), "Mímir wants to run a command");
        let d = req(
            "Bash",
            json!({"command": "ls -la | head", "description": "List the files in the project."}),
        );
        assert_eq!(
            d.title(project),
            "Mímir wants to list the files in the project"
        );
        assert_eq!(b.detail().as_deref(), Some("pytest -q"));
        let m = req(
            "MultiEdit",
            json!({"file_path": "/home/x/app/a", "edits": [{"old_string": "1", "new_string": "2"}, {"old_string": "3", "new_string": "4"}]}),
        );
        assert_eq!(m.preview(), ["-1", "+2", "@@", "-3", "+4"]);
    }

    /// PCS-13: `make *` from a Claude Code suggestion is rejected; `make build` accepted.
    #[test]
    fn always_allow_no_glob_widening() {
        // A Claude Code suggestion of `make *` must be rejected (too broad).
        let req_broad = Request {
            task: "t".into(),
            tool_name: "Bash".into(),
            tool_input: json!({"command": "make build"}),
            permission_suggestions: json!([{
                "type": "addRules",
                "rules": [{"toolName": "Bash", "ruleContent": "make *"}]
            }]),
        };
        let rule = req_broad.always_rule_text();
        assert!(
            !rule.ends_with("make *)") && rule != "Bash(make *)",
            "broad 'make *' suggestion must be rejected, got: {rule}"
        );
        // Should fall back to bash_rule("make build") = "make build *" or exact.
        assert!(
            rule.contains("make build"),
            "make build must appear in rule: {rule}"
        );

        // Without a broad suggestion, `make build` produces `make build *`.
        let req_ok = Request {
            task: "t".into(),
            tool_name: "Bash".into(),
            tool_input: json!({"command": "make build"}),
            permission_suggestions: json!([]),
        };
        let rule2 = req_ok.always_rule_text();
        assert!(rule2.contains("make build"), "make build accepted: {rule2}");
    }
}
