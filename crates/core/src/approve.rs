//! `mimir approve`: Claude Code's PermissionRequest hook for every
//! Claude session on this PC that Mímir doesn't run itself (a terminal, the
//! General session, one started from claude.ai). It hands the request to the
//! service, which shows it in Needs you on the phone and in the window, and
//! prints the owner's answer for Claude Code.
//!
//! It never allows on its own: the service being away, no answer within
//! ten minutes, an unclear reply or "Answer on the PC" all print nothing,
//! and Claude Code then shows its usual prompt.

use crate::approvals::{Answer, Request, hook_output};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::io::Read;
use std::path::Path;
use std::time::Duration;

/// Set on every Claude Code Mímir runs: those have their own approvals.
pub const MIMIR_TASK_ENV: &str = "MIMIR_TASK";

/// How long the owner has before the question goes back to the PC.
pub const WAIT: Duration = Duration::from_secs(600);

/// The buttons, in this order (the phone answers with their number).
pub const CHOICES: [&str; 4] = ["Allow", "Don't allow", "Always allow", "Answer on the PC"];

/// A request from a session outside Mímir, as the service gets it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct OutsideAsk {
    /// Claude Code's session id.
    pub session: String,
    /// The session's folder.
    pub project: String,
    /// Claude Code's own title for the session, when it has one yet.
    #[serde(default)]
    pub title: Option<String>,
    pub request: Request,
    /// The waiting hook: the service drops the request once it is gone.
    pub pid: u32,
}

impl OutsideAsk {
    /// Which session asks, in plain words.
    pub fn from_words(&self) -> String {
        let short: String = self.session.chars().take(8).collect();
        match self
            .title
            .as_deref()
            .map(str::trim)
            .filter(|t| !t.is_empty())
        {
            Some(t) => format!("From the session \"{t}\""),
            None if short.is_empty() => "From a session on this PC".into(),
            None => format!("From session {short}"),
        }
    }
}

/// What the hook does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    Allow,
    /// Allow, and add the rule to the project's local Claude settings.
    Always,
    Deny,
    /// Print nothing: Claude Code asks as normal.
    AskOnPc,
}

/// The service's reply word for an answer.
pub fn reply_word(d: Decision) -> &'static str {
    match d {
        Decision::Allow => "allow",
        Decision::Always => "always",
        Decision::Deny => "deny",
        Decision::AskOnPc => "pc",
    }
}

/// The answer in a reply; anything unclear goes back to the PC.
pub fn parse_reply(r: &str) -> Decision {
    match r.trim() {
        "allow" => Decision::Allow,
        "always" => Decision::Always,
        "deny" => Decision::Deny,
        _ => Decision::AskOnPc,
    }
}

/// The request in Claude Code's hook input, or None when it isn't one.
pub fn read_input(input: &str) -> Option<OutsideAsk> {
    let v: Value = serde_json::from_str(input).ok()?;
    let s = |k: &str| {
        v.get(k)
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string()
    };
    let tool_name = s("tool_name");
    if tool_name.is_empty() {
        return None;
    }
    let transcript = s("transcript_path");
    let title = (!transcript.is_empty())
        .then(|| crate::sessions::ai_title(Path::new(&transcript)))
        .flatten();
    Some(OutsideAsk {
        session: s("session_id"),
        project: s("cwd"),
        title,
        request: Request {
            task: String::new(),
            tool_name,
            tool_input: v.get("tool_input").cloned().unwrap_or(Value::Null),
            permission_suggestions: v
                .get("permission_suggestions")
                .cloned()
                .unwrap_or(Value::Null),
        },
        pid: std::process::id(),
    })
}

/// Asks the service on `socket` and waits up to `wait` for the owner.
/// `in_mimir`: this is a session Mímir runs, which asks its own way.
pub fn decide(
    input: &str,
    socket: &Path,
    wait: Duration,
    in_mimir: bool,
) -> (Decision, Option<Request>) {
    if in_mimir {
        return (Decision::AskOnPc, None);
    }
    let Some(ask) = read_input(input) else {
        return (Decision::AskOnPc, None);
    };
    let request = ask.request.clone();
    let Ok(mut s) = std::os::unix::net::UnixStream::connect(socket) else {
        return (Decision::AskOnPc, Some(request));
    };
    let _ = s.set_write_timeout(Some(Duration::from_secs(5)));
    let _ = s.set_read_timeout(Some(wait));
    let msg = crate::sockets::SessionMsg::Permission(Box::new(ask));
    if crate::sockets::write_msg(&mut s, &msg).is_err() {
        return (Decision::AskOnPc, Some(request));
    }
    let d = match crate::sockets::read_msg::<String>(&mut s) {
        Ok(Some(r)) => parse_reply(&r),
        _ => Decision::AskOnPc,
    };
    (d, Some(request))
}

/// What the hook prints: None for "ask as normal".
pub fn output(d: Decision, request: &Request) -> Option<Value> {
    match d {
        Decision::Allow => Some(hook_output(request, Answer::Allow, "")),
        Decision::Always => Some(hook_output(request, Answer::Always, "")),
        Decision::Deny => Some(hook_output(
            request,
            Answer::No,
            "The owner said no from Mímir. Don't do this step; ask what to do instead.",
        )),
        Decision::AskOnPc => None,
    }
}

/// `mimir approve`: reads the hook input on stdin. Always exits 0
/// so a problem here never blocks Claude Code; printing nothing means
/// Claude Code asks as normal.
pub fn command(_args: &[String]) -> i32 {
    let mut input = String::new();
    if std::io::stdin()
        .take(8 << 20)
        .read_to_string(&mut input)
        .is_err()
    {
        return 0;
    }
    let in_mimir = std::env::var_os(MIMIR_TASK_ENV).is_some();
    let (d, request) = decide(&input, &crate::sockets::session_socket(), WAIT, in_mimir);
    if let Some(r) = request
        && let Some(out) = output(d, &r)
    {
        println!("{out}");
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sockets::{SessionMsg, read_msg, write_msg};
    use std::os::unix::net::UnixListener;
    use std::path::PathBuf;

    const INPUT: &str = r#"{"session_id":"abcdef123456","transcript_path":"","cwd":"/home/me/General",
        "hook_event_name":"PermissionRequest","tool_name":"Write",
        "tool_input":{"file_path":"/home/me/General/a.txt","content":"hi"},"permission_suggestions":[]}"#;

    fn sock(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("approve-{}-{name}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let p = dir.join("s.sock");
        let _ = std::fs::remove_file(&p);
        p
    }

    /// A service that takes one request and answers with `reply` (None:
    /// never answers, keeps the line open).
    fn service(
        path: &Path,
        reply: Option<&'static str>,
    ) -> std::thread::JoinHandle<Option<OutsideAsk>> {
        let l = UnixListener::bind(path).unwrap();
        std::thread::spawn(move || {
            let (mut s, _) = l.accept().unwrap();
            let got = match read_msg::<SessionMsg>(&mut s) {
                Ok(Some(SessionMsg::Permission(a))) => Some(*a),
                _ => None,
            };
            match reply {
                Some(r) => {
                    let _ = write_msg(&mut s, &r.to_string());
                }
                None => std::thread::sleep(Duration::from_millis(800)),
            }
            got
        })
    }

    fn behavior(d: Decision) -> Option<String> {
        let r = read_input(INPUT).unwrap().request;
        output(d, &r).map(|v| {
            v["hookSpecificOutput"]["decision"]["behavior"]
                .as_str()
                .unwrap()
                .to_string()
        })
    }

    #[test]
    fn allow_is_sent_with_the_request() {
        let p = sock("allow");
        let svc = service(&p, Some("allow"));
        let (d, _) = decide(INPUT, &p, Duration::from_secs(5), false);
        assert_eq!(d, Decision::Allow);
        let got = svc.join().unwrap().unwrap();
        assert_eq!(got.session, "abcdef123456");
        assert_eq!(got.project, "/home/me/General");
        assert_eq!(got.request.tool_name, "Write");
        assert_eq!(got.pid, std::process::id());
        assert_eq!(behavior(d).as_deref(), Some("allow"));
    }

    #[test]
    fn deny_and_always() {
        let p = sock("deny");
        let svc = service(&p, Some("deny"));
        let (d, _) = decide(INPUT, &p, Duration::from_secs(5), false);
        svc.join().unwrap();
        assert_eq!(d, Decision::Deny);
        assert_eq!(behavior(d).as_deref(), Some("deny"));

        let p = sock("always");
        let svc = service(&p, Some("always"));
        let (d, r) = decide(INPUT, &p, Duration::from_secs(5), false);
        svc.join().unwrap();
        assert_eq!(d, Decision::Always);
        let out = output(d, &r.unwrap()).unwrap();
        let up = &out["hookSpecificOutput"]["decision"]["updatedPermissions"][0];
        assert_eq!(up["destination"], "localSettings");
        assert_eq!(up["rules"][0]["toolName"], "Write");
    }

    #[test]
    fn no_answer_in_time_asks_as_normal() {
        let p = sock("timeout");
        let svc = service(&p, None);
        let (d, _) = decide(INPUT, &p, Duration::from_millis(200), false);
        assert_eq!(d, Decision::AskOnPc);
        assert_eq!(behavior(d), None);
        svc.join().unwrap();
    }

    #[test]
    fn service_away_asks_as_normal() {
        let (d, _) = decide(
            INPUT,
            Path::new("/nonexistent/s.sock"),
            Duration::from_secs(1),
            false,
        );
        assert_eq!(d, Decision::AskOnPc);
        assert_eq!(behavior(d), None);
    }

    #[test]
    fn answer_on_the_pc_and_unclear_replies_ask_as_normal() {
        for reply in ["pc", "", "yes", "ALLOW", "refused: Mímir stopped"] {
            assert_eq!(parse_reply(reply), Decision::AskOnPc, "{reply}");
        }
        let p = sock("pc");
        let svc = service(&p, Some("pc"));
        let (d, _) = decide(INPUT, &p, Duration::from_secs(5), false);
        svc.join().unwrap();
        assert_eq!(d, Decision::AskOnPc);
    }

    #[test]
    fn mimir_sessions_and_bad_input_are_left_alone() {
        let p = sock("mimir");
        // Nothing listens: a session Mímir runs must not even connect.
        let (d, r) = decide(INPUT, &p, Duration::from_secs(1), true);
        assert_eq!((d, r), (Decision::AskOnPc, None));
        let (d, r) = decide("not json", &p, Duration::from_secs(1), false);
        assert_eq!((d, r), (Decision::AskOnPc, None));
    }

    #[test]
    fn never_allows_on_its_own() {
        // Only the two allowing replies allow; every other way ends in the
        // usual prompt or a no.
        for reply in ["pc", "deny", "", "maybe", "allow ", " always"] {
            let d = parse_reply(reply);
            let allows = matches!(d, Decision::Allow | Decision::Always);
            assert_eq!(
                allows,
                matches!(reply.trim(), "allow" | "always"),
                "{reply}"
            );
        }
        for (a, b) in [
            (Decision::Allow, "allow"),
            (Decision::Always, "always"),
            (Decision::Deny, "deny"),
            (Decision::AskOnPc, "pc"),
        ] {
            assert_eq!(parse_reply(reply_word(a)), a);
            assert_eq!(reply_word(a), b);
        }
    }

    #[test]
    fn from_words() {
        let mut a = read_input(INPUT).unwrap();
        assert_eq!(a.from_words(), "From session abcdef12");
        a.title = Some("Fix the build".into());
        assert_eq!(a.from_words(), "From the session \"Fix the build\"");
    }
}
