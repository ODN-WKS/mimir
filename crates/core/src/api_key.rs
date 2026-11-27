//! The owner's Anthropic key for "Use my API credits when my plan runs out".
//!
//! The key lives only in the PC's password store (the freedesktop Secret
//! Service, reached through `secret-tool` on the PC). It goes in on the
//! program's input, never in its arguments, and Mímir never writes it to a
//! file, a log, the developer log or a phone. Settings keeps only the last
//! 4 characters, written down when the key was pasted.
//!
//! Claude Code gets the key without Mímir ever putting it in an
//! environment: a task's `--settings` names `mimir api-key` as its
//! `apiKeyHelper`, and Claude Code runs that to read the key from the store
//! itself. Checked on this PC with Claude Code 2.1.332, a made-up key and a
//! stand-in server: the helper's key is the one sent, also when a plan
//! sign-in is present, for project work and for chats.

use std::io::Write;
use std::path::Path;
use std::process::{Command, Stdio};

/// What the entry in the password store is filed under.
const ATTRS: [&str; 4] = ["app", "mimir", "kind", "anthropic-api-key"];

/// Said when the pasted text can't be a key.
pub const NOT_A_KEY: &str = "That doesn't look like an Anthropic key (they start with sk-ant-). Copy it again and paste it here.";
/// Said when the PC's password store can't be used.
pub const NO_STORE: &str = "Your PC's password store didn't take the key. Unlock it (or sign in to your desktop again) and try again.";
/// Said after "Check key" when Anthropic accepts the key.
pub const KEY_WORKS: &str = "Key works";
const KEY_REFUSED: &str = "Anthropic doesn't accept this key. Paste it again, or make a new one at console.anthropic.com.";
const NOT_REACHED: &str = "Couldn't reach Anthropic. Check your connection and try again.";
const NOT_CHECKED: &str = "Anthropic couldn't check the key just now. Try again in a minute.";
const NO_KEY: &str = "No key is saved yet. Paste one first.";
/// Said to a phone that turns the switch on while the PC holds no key.
pub const PASTE_ON_PC: &str = "Paste your key on your PC first: Settings › Models.";

/// Why Anthropic turned down work on the API credits.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fail {
    /// The credits ran out.
    NoCredits,
    /// The key isn't accepted (wrong, or taken back).
    BadKey,
}

impl Fail {
    /// The reason, to follow "because" or "and".
    pub fn words(self) -> &'static str {
        match self {
            Fail::NoCredits => "your API credits ran out",
            Fail::BadKey => "Anthropic didn't accept your API key",
        }
    }

    /// The reason as the start of a line.
    pub fn headline(self) -> &'static str {
        match self {
            Fail::NoCredits => "Your API credits ran out",
            Fail::BadKey => "Anthropic didn't accept your API key",
        }
    }

    /// The one plain line a task says when it happens.
    pub fn sentence(self) -> &'static str {
        match self {
            Fail::NoCredits => {
                "Your API credits ran out. Add credits at console.anthropic.com to use them again."
            }
            Fail::BadKey => {
                "Anthropic didn't accept your API key. Paste it again in Settings › Models."
            }
        }
    }
}

/// Whether Claude Code's words for a failed run say the credits ran out
/// or the key was refused (as Claude Code 2.1.332 words them, and as
/// Anthropic's own error names read).
pub fn failure(why: &str) -> Option<Fail> {
    let w = why.to_ascii_lowercase();
    if w.contains("credit balance is too low") || w.contains("billing_error") {
        Some(Fail::NoCredits)
    } else if w.contains("invalid api key")
        || w.contains("invalid x-api-key")
        || w.contains("authentication_error")
        || w.contains("authentication_failed")
    {
        Some(Fail::BadKey)
    } else {
        None
    }
}

/// The store's program on the PC (a check points at a stand-in).
fn tool() -> Command {
    let program = std::env::var_os("MIMIR_SECRET_TOOL").unwrap_or_else(|| "secret-tool".into());
    crate::flatpak::host_cmd(program)
}

/// Whether the text can be an Anthropic key at all.
fn plausible(key: &str) -> bool {
    key.starts_with("sk-ant-") && key.len() >= 16 && !key.chars().any(char::is_whitespace)
}

/// The last 4 characters, to show which key is saved.
pub fn hint(key: &str) -> String {
    let chars: Vec<char> = key.trim().chars().collect();
    chars[chars.len().saturating_sub(4)..].iter().collect()
}

/// Saves the pasted key in the PC's password store. The hint to keep in
/// Settings.
pub fn save(key: &str) -> Result<String, String> {
    save_in(tool(), key)
}

fn save_in(mut store: Command, key: &str) -> Result<String, String> {
    let key = key.trim();
    if !plausible(key) {
        return Err(NOT_A_KEY.into());
    }
    let mut child = store
        .args(["store", "--label=Mímir: Anthropic API key"])
        .args(ATTRS)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| NO_STORE.to_string())?;
    // On its input, so it never shows in a list of running programs.
    let wrote = child
        .stdin
        .take()
        .is_some_and(|mut i| i.write_all(key.as_bytes()).is_ok());
    let ok = child.wait().is_ok_and(|s| s.success());
    if wrote && ok {
        Ok(hint(key))
    } else {
        Err(NO_STORE.into())
    }
}

/// The saved key, if there is one and the store is open.
pub fn read() -> Option<String> {
    read_from(tool())
}

fn read_from(mut store: Command) -> Option<String> {
    let out = store
        .arg("lookup")
        .args(ATTRS)
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    let key = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (out.status.success() && !key.is_empty()).then_some(key)
}

/// Takes the key out of the PC's password store.
pub fn remove() -> Result<(), String> {
    remove_from(tool())
}

fn remove_from(mut store: Command) -> Result<(), String> {
    let ok = store
        .arg("clear")
        .args(ATTRS)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|s| s.success());
    if ok { Ok(()) } else { Err(NO_STORE.into()) }
}

/// `mimir api-key`: prints the saved key, for Claude Code's `apiKeyHelper`.
pub fn print_main() -> i32 {
    match read() {
        Some(key) => {
            println!("{key}");
            0
        }
        None => 1,
    }
}

/// A run's `--settings` with the key helper added: Claude Code asks
/// `<Mímir> api-key` for the key, so the key is in no argument and no
/// environment. `exe`: Mímir's own program.
pub fn with_helper(settings_json: &str, exe: &Path) -> String {
    let mut v: serde_json::Value =
        serde_json::from_str(settings_json).unwrap_or_else(|_| serde_json::json!({}));
    let program = crate::flatpak::own_command_words(exe)
        .iter()
        .map(|w| crate::setup::shell_quote(w))
        .collect::<Vec<_>>()
        .join(" ");
    v["apiKeyHelper"] = serde_json::json!(format!("{program} api-key"));
    v.to_string()
}

/// Where Anthropic answers (a check points at a stand-in).
fn anthropic_url() -> String {
    std::env::var("MIMIR_ANTHROPIC").unwrap_or_else(|_| "https://api.anthropic.com".into())
}

/// "Check key": one small request to Anthropic with the saved key (its
/// list of models, which costs nothing). `Ok` is "Key works"; `Err` is the
/// plain reason.
pub fn check_saved() -> Result<&'static str, String> {
    let key = read().ok_or(NO_KEY)?;
    check_at(&anthropic_url(), &key)
}

fn check_at(base: &str, key: &str) -> Result<&'static str, String> {
    let mut child = Command::new("curl")
        .args([
            "-sS",
            "-m",
            "20",
            "-o",
            "/dev/null",
            "-w",
            "%{http_code}",
            // The key's header is read from the input, never an argument.
            "-H",
            "@-",
            "-H",
            "anthropic-version: 2023-06-01",
        ])
        .arg(format!("{}/v1/models?limit=1", base.trim_end_matches('/')))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| NOT_REACHED.to_string())?;
    if let Some(mut i) = child.stdin.take() {
        let _ = i.write_all(format!("x-api-key: {key}\n").as_bytes());
    }
    let out = child
        .wait_with_output()
        .map_err(|_| NOT_REACHED.to_string())?;
    match String::from_utf8_lossy(&out.stdout).trim() {
        "200" => Ok(KEY_WORKS),
        "401" | "403" => Err(KEY_REFUSED.into()),
        "000" | "" => Err(NOT_REACHED.into()),
        _ => Err(NOT_CHECKED.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};
    use std::path::PathBuf;

    /// A made-up key: never a real one.
    const KEY: &str = "sk-ant-test-0000000000000000WXYZ";

    fn tmp(name: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("mimir-api-key-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&p);
        std::fs::create_dir_all(&p).unwrap();
        p
    }

    /// A stand-in password store: keeps the secret in a file in `dir` and
    /// writes down the arguments of every call.
    fn fake_store(dir: &Path) -> PathBuf {
        use std::os::unix::fs::PermissionsExt;
        let prog = dir.join("secret-tool");
        std::fs::write(
            &prog,
            format!(
                "#!/bin/sh\necho \"$@\" >> '{d}/calls'\ncase \"$1\" in\n\
                 store) cat > '{d}/secret' ;;\n\
                 lookup) cat '{d}/secret' 2>/dev/null || exit 1 ;;\n\
                 clear) rm -f '{d}/secret' ;;\nesac\n",
                d = dir.display()
            ),
        )
        .unwrap();
        std::fs::set_permissions(&prog, std::fs::Permissions::from_mode(0o755)).unwrap();
        prog
    }

    #[test]
    fn the_key_goes_in_on_the_input_and_comes_back() {
        let d = tmp("store");
        let prog = fake_store(&d);
        assert_eq!(read_from(Command::new(&prog)), None);
        assert_eq!(
            save_in(Command::new(&prog), &format!("  {KEY}\n")),
            Ok("WXYZ".into())
        );
        assert_eq!(read_from(Command::new(&prog)).as_deref(), Some(KEY));
        // Never in the arguments (what a list of running programs shows).
        let calls = std::fs::read_to_string(d.join("calls")).unwrap();
        assert!(!calls.contains("sk-ant-"), "{calls}");
        assert!(
            calls.contains(
                "store --label=Mímir: Anthropic API key app mimir kind anthropic-api-key"
            )
        );
        assert!(calls.contains("lookup app mimir kind anthropic-api-key"));
        assert_eq!(remove_from(Command::new(&prog)), Ok(()));
        assert_eq!(read_from(Command::new(&prog)), None);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn text_that_is_no_key_is_never_stored() {
        let d = tmp("not-a-key");
        let prog = fake_store(&d);
        for bad in ["", "hello", "sk-ant-", "sk-ant-test with spaces 0000000000"] {
            assert_eq!(
                save_in(Command::new(&prog), bad),
                Err(NOT_A_KEY.into()),
                "{bad}"
            );
        }
        assert!(!d.join("calls").exists(), "the store was called");
        // No store program at all: plain words, not a crash.
        assert_eq!(
            save_in(Command::new(d.join("missing")), KEY),
            Err(NO_STORE.into())
        );
        assert_eq!(read_from(Command::new(d.join("missing"))), None);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn the_hint_is_the_last_four_characters() {
        assert_eq!(hint(KEY), "WXYZ");
        assert_eq!(hint("abc"), "abc");
    }

    #[test]
    fn the_helper_is_named_in_the_settings_and_the_key_is_not() {
        let hooks = r#"{"hooks":{"PermissionRequest":[]}}"#;
        let s = with_helper(hooks, Path::new("/opt/My Apps/mimir"));
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["apiKeyHelper"], "'/opt/My Apps/mimir' api-key");
        // What was there stays.
        assert!(v["hooks"]["PermissionRequest"].is_array());
        assert!(!s.contains("sk-ant-"));
    }

    #[test]
    fn claude_codes_words_for_a_refusal_are_told_apart() {
        // As Claude Code 2.1.332 words them (recorded on this PC).
        assert_eq!(failure("Credit balance is too low"), Some(Fail::NoCredits));
        assert_eq!(
            failure("Invalid API key · Fix external API key"),
            Some(Fail::BadKey)
        );
        // As Anthropic names them.
        assert_eq!(
            failure(r#"API Error: 402 {"type":"error","error":{"type":"billing_error"}}"#),
            Some(Fail::NoCredits)
        );
        assert_eq!(
            failure(
                r#"API Error: 401 {"error":{"type":"authentication_error","message":"invalid x-api-key"}}"#
            ),
            Some(Fail::BadKey)
        );
        assert_eq!(failure("authentication_failed"), Some(Fail::BadKey));
        // Not these.
        assert_eq!(failure("Claude AI usage limit reached|1763524800"), None);
        assert_eq!(failure("API Error: 500 internal"), None);
    }

    /// A stand-in for Anthropic: answers one request with `status` and
    /// hands back the request's head.
    fn fake_anthropic(status: u16) -> (String, std::sync::mpsc::Receiver<String>) {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", l.local_addr().unwrap());
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let Ok((c, _)) = l.accept() else { return };
            let mut r = BufReader::new(c.try_clone().unwrap());
            let mut head = String::new();
            loop {
                let mut line = String::new();
                if r.read_line(&mut line).unwrap_or(0) == 0 || line.trim().is_empty() {
                    break;
                }
                head.push_str(&line);
            }
            let mut w = c;
            let _ = write!(
                w,
                "HTTP/1.1 {status} X\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{{}}"
            );
            let _ = tx.send(head);
        });
        (base, rx)
    }

    #[test]
    fn check_key_says_key_works_or_the_plain_reason() {
        let (base, head) = fake_anthropic(200);
        assert_eq!(check_at(&base, KEY), Ok(KEY_WORKS));
        let head = head.recv().unwrap().to_ascii_lowercase();
        assert!(head.starts_with("get /v1/models?limit=1 "), "{head}");
        assert!(
            head.contains(&format!("x-api-key: {}", KEY.to_ascii_lowercase())),
            "{head}"
        );
        assert!(head.contains("anthropic-version: 2023-06-01"), "{head}");

        let (base, _head) = fake_anthropic(401);
        assert_eq!(check_at(&base, KEY), Err(KEY_REFUSED.into()));
        let (base, _head) = fake_anthropic(529);
        assert_eq!(check_at(&base, KEY), Err(NOT_CHECKED.into()));
        // Nothing listens there.
        assert_eq!(check_at("http://127.0.0.1:9", KEY), Err(NOT_REACHED.into()));
    }
}
