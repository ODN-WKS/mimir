//! Setup: install or update Claude Code, sign in to Claude and GitHub.
//! Long commands run as background jobs whose output the window shows.

use serde_json::Value;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;

#[derive(Debug, Clone, PartialEq)]
pub enum JobState {
    Running,
    Done { ok: bool },
}

/// A command running in the background, with its output lines.
#[derive(Clone)]
pub struct Job {
    pub title: String,
    inner: Arc<Mutex<(Vec<String>, JobState)>>,
}

impl Job {
    /// Runs `sh -c script`. `wake` is called on every output line.
    pub fn start(title: &str, script: &str, wake: impl Fn() + Send + Sync + 'static) -> Job {
        let inner = Arc::new(Mutex::new((vec![], JobState::Running)));
        let job = Job {
            title: title.to_string(),
            inner: inner.clone(),
        };
        let script = script.to_string();
        let wake = Arc::new(wake);
        thread::spawn(move || {
            let child = Command::new("sh")
                .arg("-c")
                // Output of both streams, in order, through one pipe.
                .arg(format!("exec 2>&1\n{script}"))
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .spawn();
            let mut child = match child {
                Ok(c) => c,
                Err(_) => {
                    let mut g = inner.lock().unwrap();
                    g.0.push("This did not start. Try again.".to_string());
                    g.1 = JobState::Done { ok: false };
                    wake();
                    return;
                }
            };
            if let Some(out) = child.stdout.take() {
                for line in BufReader::new(out).lines().map_while(Result::ok) {
                    let clean = strip_ansi(&line);
                    inner.lock().unwrap().0.push(clean);
                    wake();
                }
            }
            let ok = child.wait().map(|s| s.success()).unwrap_or(false);
            inner.lock().unwrap().1 = JobState::Done { ok };
            wake();
        });
        job
    }

    pub fn state(&self) -> JobState {
        self.inner.lock().unwrap().1.clone()
    }

    pub fn lines(&self) -> Vec<String> {
        self.inner.lock().unwrap().0.clone()
    }

    pub fn running(&self) -> bool {
        self.state() == JobState::Running
    }
}

/// Removes terminal colour codes from command output.
pub fn strip_ansi(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\u{1b}' {
            if chars.peek() == Some(&'[') {
                chars.next();
                for d in chars.by_ref() {
                    if d.is_ascii_alphabetic() {
                        break;
                    }
                }
            }
            continue;
        }
        if c != '\r' {
            out.push(c);
        }
    }
    out
}

/// Anthropic's official installer for the native Claude Code.
pub const INSTALL_CLAUDE: &str = "curl -fsSL https://claude.ai/install.sh | bash";

#[derive(Debug, Clone, Default, PartialEq)]
pub struct ClaudeStatus {
    /// e.g. "2.1.325"
    pub version: Option<String>,
    pub signed_in: bool,
    /// e.g. "Max", from the subscription type.
    pub plan: Option<String>,
    /// Which account is signed in (its organization id, not the email),
    /// to notice a change of account.
    pub account: Option<String>,
}

/// `claude --version` output → "2.1.325".
pub fn parse_claude_version(out: &str) -> Option<String> {
    out.split_whitespace()
        .next()
        .filter(|v| v.chars().next().is_some_and(|c| c.is_ascii_digit()))
        .map(str::to_string)
}

/// `claude auth status --json` → (signed in, plan). The email is not kept.
pub fn parse_claude_auth(json: &str) -> (bool, Option<String>) {
    let v: Value = serde_json::from_str(json).unwrap_or(Value::Null);
    let signed_in = v.get("loggedIn").and_then(Value::as_bool).unwrap_or(false);
    let plan = v
        .get("subscriptionType")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(|s| {
            let mut c = s.chars();
            c.next()
                .map(|f| f.to_uppercase().collect::<String>() + c.as_str())
                .unwrap_or_default()
        });
    (signed_in, plan)
}

/// `claude auth status --json` → the signed-in organization's id.
pub fn parse_claude_account(json: &str) -> Option<String> {
    let v: Value = serde_json::from_str(json).ok()?;
    v.get("orgId")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

pub fn claude_status(claude: &std::path::Path) -> ClaudeStatus {
    let version = crate::flatpak::host_cmd(claude)
        .arg("--version")
        .output()
        .ok()
        .filter(|o| o.status.success())
        .and_then(|o| parse_claude_version(&String::from_utf8_lossy(&o.stdout)));
    let auth = version
        .as_ref()
        .and_then(|_| {
            crate::flatpak::host_cmd(claude)
                .args(["auth", "status", "--json"])
                .output()
                .ok()
        })
        .map(|o| String::from_utf8_lossy(&o.stdout).to_string())
        .unwrap_or_default();
    let (signed_in, plan) = parse_claude_auth(&auth);
    ClaudeStatus {
        version,
        signed_in,
        plan,
        account: parse_claude_account(&auth).filter(|_| signed_in),
    }
}

/// Quotes a value for `sh`.
pub fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// Folders that hold programs' `.desktop` files, in the order the desktop
/// searches them. Flatpak's folders are always included: programs started
/// from Steam may lack them, and then a browser installed as a Flatpak
/// (like Chrome on SteamOS) cannot be found.
pub fn data_dirs() -> Vec<PathBuf> {
    let home = PathBuf::from(std::env::var_os("HOME").unwrap_or_default());
    let from_env = std::env::var("XDG_DATA_DIRS").unwrap_or_default();
    merge_data_dirs(&home, &from_env)
}

fn merge_data_dirs(home: &Path, from_env: &str) -> Vec<PathBuf> {
    let mut dirs: Vec<PathBuf> = vec![
        home.join(".local/share/flatpak/exports/share"),
        PathBuf::from("/var/lib/flatpak/exports/share"),
    ];
    let listed: Vec<PathBuf> = from_env
        .split(':')
        .filter(|d| !d.is_empty())
        .map(PathBuf::from)
        .collect();
    let rest = if listed.is_empty() {
        vec![
            PathBuf::from("/usr/local/share"),
            PathBuf::from("/usr/share"),
        ]
    } else {
        listed
    };
    for d in rest {
        if !dirs.contains(&d) {
            dirs.push(d);
        }
    }
    dirs
}

fn data_dirs_value() -> String {
    data_dirs()
        .iter()
        .map(|d| d.display().to_string())
        .collect::<Vec<_>>()
        .join(":")
}

/// The `Exec=` line of a `.desktop` file's main section.
fn desktop_exec(text: &str) -> Option<String> {
    let mut in_main = false;
    for line in text.lines() {
        let line = line.trim();
        if line.starts_with('[') {
            in_main = line == "[Desktop Entry]";
        } else if in_main && let Some(v) = line.strip_prefix("Exec=") {
            return Some(v.trim().to_string());
        }
    }
    None
}

/// A program that opens a web page when given its address as the only
/// argument (what Claude Code expects in `BROWSER`), for one `.desktop` id.
/// SteamOS's stand-in Firefox, which only opens the app store, is skipped.
fn browser_for(id: &str, dirs: &[PathBuf]) -> Option<String> {
    let id = id.trim();
    if !id.ends_with(".desktop") {
        return None;
    }
    for dir in dirs {
        let Ok(text) = std::fs::read_to_string(dir.join("applications").join(id)) else {
            continue;
        };
        let exec = desktop_exec(&text)?;
        if exec.contains("appstream:") {
            return None;
        }
        // A Flatpak app has a ready launcher next to its exported files.
        if dir.ends_with("exports/share") {
            let launcher = dir
                .parent()?
                .join("bin")
                .join(id.trim_end_matches(".desktop"));
            if launcher.exists() {
                return Some(launcher.display().to_string());
            }
        }
        let words: Vec<&str> = exec
            .split_whitespace()
            .filter(|w| !(w.len() == 2 && w.starts_with('%')))
            .collect();
        return match words.as_slice() {
            [one] => Some(one.trim_matches('"').to_string()),
            _ => None,
        };
    }
    None
}

/// Programs listed for web links in the folders' `mimeinfo.cache`, in order.
fn listed_browsers(dirs: &[PathBuf]) -> Vec<String> {
    let mut ids = vec![];
    for dir in dirs {
        let Ok(text) = std::fs::read_to_string(dir.join("applications/mimeinfo.cache")) else {
            continue;
        };
        for line in text.lines() {
            if let Some(v) = line.strip_prefix("x-scheme-handler/https=") {
                ids.extend(v.split(';').filter(|s| !s.is_empty()).map(str::to_string));
            }
        }
    }
    ids
}

/// The browser to open sign-in pages in: the desktop's default, or else
/// the first real browser installed. None leaves it to `xdg-open`.
pub fn browser() -> Option<String> {
    let dirs = data_dirs();
    let default = crate::flatpak::host_cmd("xdg-settings")
        .args(["get", "default-web-browser"])
        .env("XDG_DATA_DIRS", data_dirs_value())
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty());
    default
        .into_iter()
        .chain(listed_browsers(&dirs))
        .find_map(|id| browser_for(&id, &dirs))
}

/// `VAR=value` words that make a command find the right browser.
fn browser_env() -> String {
    let mut env = format!("XDG_DATA_DIRS={}", shell_quote(&data_dirs_value()));
    if let Some(b) = browser() {
        env.push_str(&format!(" BROWSER={}", shell_quote(&b)));
    }
    env
}

/// Opens a web page in the default browser.
pub fn open_url(url: &str) {
    let mut cmd = match browser() {
        Some(b) => Command::new(b),
        None => Command::new("xdg-open"),
    };
    let _ = cmd
        .arg(url)
        .env("XDG_DATA_DIRS", data_dirs_value())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();
}

/// Claude Code's sign-in, which opens the default browser.
pub fn claude_login(claude: &Path) -> String {
    format!(
        "{} {} auth login",
        browser_env(),
        shell_quote(&claude.display().to_string())
    )
}

/// Signs out of Claude, then signs in again (to change account).
pub fn claude_switch_account(claude: &Path) -> String {
    format!(
        "{} auth logout; {}",
        shell_quote(&claude.display().to_string()),
        claude_login(claude)
    )
}

/// Signs out of Claude on this PC.
pub fn claude_logout(claude: &Path) -> String {
    format!("{} auth logout", shell_quote(&claude.display().to_string()))
}

/// Claude Code's Remote Control for one project: start and drive sessions
/// in that folder from claude.ai/code or the Claude phone app. It only
/// works in Claude Code's own terminal mode, not the one this window uses.
pub fn remote_control(claude: &Path, name: &str) -> String {
    format!(
        "{} {} remote-control --spawn=same-dir --name {}",
        browser_env(),
        shell_quote(&claude.display().to_string()),
        shell_quote(name)
    )
}

/// Opens a terminal window in `dir` running `cmd` (for commands that show
/// things or ask questions). Returns false if no terminal exists.
pub fn in_terminal(cmd: &str, dir: Option<&Path>) -> bool {
    let wrapped = format!("{cmd}; echo; echo 'You can close this window.'; read -r _");
    let home = PathBuf::from(std::env::var_os("HOME").unwrap_or_default());
    let dir = dir.unwrap_or(&home);
    let workdir = dir.display().to_string();
    let tries: [(&str, Vec<&str>); 3] = [
        ("konsole", vec!["--workdir", &workdir, "-e", "sh", "-c"]),
        (
            "gnome-terminal",
            vec!["--working-directory", &workdir, "--", "sh", "-c"],
        ),
        ("xterm", vec!["-e", "sh", "-c"]),
    ];
    for (prog, args) in tries {
        let mut term = Command::new(prog);
        term.args(args).arg(&wrapped).current_dir(dir);
        if crate::flatpak::to_host(term)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .is_ok()
        {
            return true;
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_and_sign_in() {
        assert_eq!(
            parse_claude_version("2.1.325 (Claude Code)\n").as_deref(),
            Some("2.1.325")
        );
        assert_eq!(parse_claude_version("command not found"), None);
        let (ok, plan) =
            parse_claude_auth(r#"{"loggedIn":true,"subscriptionType":"max","email":"x"}"#);
        assert!(ok);
        assert_eq!(plan.as_deref(), Some("Max"));
        assert_eq!(parse_claude_auth("garbage"), (false, None));
        assert_eq!(
            parse_claude_account(r#"{"loggedIn":true,"orgId":"d5-1"}"#).as_deref(),
            Some("d5-1")
        );
        assert_eq!(parse_claude_account("{}"), None);
    }

    #[test]
    fn jobs_report_output_and_result() {
        let j = Job::start("t", "echo one; echo two >&2; exit 3", || {});
        let end = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while j.running() && std::time::Instant::now() < end {
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        assert_eq!(j.state(), JobState::Done { ok: false });
        assert_eq!(j.lines(), ["one", "two"]);
        assert_eq!(strip_ansi("\u{1b}[1;32mok\u{1b}[0m\r"), "ok");
    }

    #[test]
    fn browsers() {
        let home = std::env::temp_dir().join(format!("station-browser-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        // Steam's list without Flatpak's folders: they are put first anyway.
        let dirs = merge_data_dirs(&home, "/usr/share");
        assert_eq!(dirs[0], home.join(".local/share/flatpak/exports/share"));
        assert_eq!(dirs.last().unwrap(), Path::new("/usr/share"));
        assert_eq!(merge_data_dirs(&home, "").len(), 4);

        let flat = home.join("flatpak/exports/share");
        let sys = home.join("usr/share");
        for d in [&flat, &sys] {
            std::fs::create_dir_all(d.join("applications")).unwrap();
        }
        std::fs::create_dir_all(home.join("flatpak/exports/bin")).unwrap();
        std::fs::write(home.join("flatpak/exports/bin/com.google.Chrome"), "").unwrap();
        std::fs::write(
            flat.join("applications/com.google.Chrome.desktop"),
            "[Desktop Entry]\nExec=/usr/bin/flatpak run com.google.Chrome @@u %U @@\n",
        )
        .unwrap();
        std::fs::write(
            flat.join("applications/mimeinfo.cache"),
            "[MIME Cache]\nx-scheme-handler/https=com.google.Chrome.desktop;\n",
        )
        .unwrap();
        // SteamOS's stand-in Firefox only opens the store.
        std::fs::write(
            sys.join("applications/org.mozilla.firefox.desktop"),
            "[Desktop Entry]\nExec=sh -c 'xdg-open appstream://org.mozilla.firefox' %U\n",
        )
        .unwrap();
        std::fs::write(
            sys.join("applications/mimeinfo.cache"),
            "[MIME Cache]\nx-scheme-handler/https=org.mozilla.firefox.desktop;\n",
        )
        .unwrap();
        std::fs::write(
            sys.join("applications/falkon.desktop"),
            "[Desktop Entry]\nExec=falkon %u\n[Desktop Action new]\nExec=falkon -n\n",
        )
        .unwrap();
        let dirs = vec![sys.clone(), flat.clone()];
        let chrome = home
            .join("flatpak/exports/bin/com.google.Chrome")
            .display()
            .to_string();
        assert_eq!(
            browser_for("com.google.Chrome.desktop", &dirs),
            Some(chrome.clone())
        );
        assert_eq!(browser_for("org.mozilla.firefox.desktop", &dirs), None);
        assert_eq!(
            browser_for("falkon.desktop", &dirs).as_deref(),
            Some("falkon")
        );
        assert_eq!(browser_for("missing.desktop", &dirs), None);
        // Firefox is listed first but skipped; Chrome is used.
        let found = listed_browsers(&dirs)
            .into_iter()
            .find_map(|id| browser_for(&id, &dirs));
        assert_eq!(found, Some(chrome));
        std::fs::remove_dir_all(&home).unwrap();
    }

    #[test]
    fn sign_in_commands() {
        let claude = Path::new("/home/x/.local/bin/claude");
        assert!(claude_login(claude).ends_with("'/home/x/.local/bin/claude' auth login"));
        assert!(claude_login(claude).starts_with("XDG_DATA_DIRS="));
        assert!(
            claude_switch_account(claude).starts_with("'/home/x/.local/bin/claude' auth logout; ")
        );
        assert_eq!(
            claude_logout(claude),
            "'/home/x/.local/bin/claude' auth logout"
        );
        assert!(
            remote_control(claude, "it's")
                .ends_with(" remote-control --spawn=same-dir --name 'it'\\''s'")
        );
    }
}
