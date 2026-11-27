//! What waits for the owner besides a task's own permission requests and
//! questions: asks from any Claude session (`mimir ask`), kept in
//! `asks.jsonl`, and the phone's "Always allow edits to this file (this
//! task)" rules, kept beside each task.
//!
//! Every needs-you item has a stable `ask_id`, so the first answer from any
//! phone or window wins and the others see the item go.

use crate::approvals::Request;
use mimir_proto::{ItemAnswer, NeedsKind};
use sha2::{Digest, Sha256};
use std::io::Write;
use std::path::{Path, PathBuf};

/// Seconds since 1970.
pub fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub fn short_hash(s: &str) -> String {
    let h = Sha256::digest(s.as_bytes());
    h.iter().take(6).map(|b| format!("{b:02x}")).collect()
}

/// The id of a permission request: the task and what it asks for, so it
/// stays the same while it waits and in every client.
pub fn permission_id(task: &str, r: &Request) -> String {
    let what = format!(
        "{}\n{}",
        r.tool_name,
        serde_json::to_string(&r.tool_input).unwrap_or_default()
    );
    format!("perm:{task}:{}", short_hash(&what))
}

/// The id of Claude's question: the task and the reply it ends.
pub fn question_id(task: &str, entry: usize) -> String {
    format!("q:{task}:{entry}")
}

/// The id of a failing core check: the project, the feature and the commit.
pub fn check_id(project: &str, feature: &str, commit: &str) -> String {
    format!(
        "check:{}",
        short_hash(&format!("{project}\n{feature}\n{commit}"))
    )
}

/// A new random-enough id for an ask or an Inbox message.
pub fn new_id(prefix: &str) -> String {
    let n = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!(
        "{prefix}:{}",
        short_hash(&format!("{n}-{}", std::process::id()))
    )
}

/// One ask from a Claude session, as kept in `asks.jsonl` (one line per
/// change; the newest line for an id wins).
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct AskRecord {
    pub ask_id: String,
    pub kind: NeedsKind,
    pub project: String,
    #[serde(default)]
    pub task: Option<String>,
    pub text: String,
    #[serde(default)]
    pub choices: Vec<String>,
    #[serde(default)]
    pub link: Option<String>,
    pub created: i64,
    #[serde(default)]
    pub answer: Option<String>,
    #[serde(default)]
    pub answered_by: Option<String>,
    #[serde(default)]
    pub answered_at: Option<i64>,
}

/// The asks file.
pub fn asks_path() -> PathBuf {
    crate::paths::data_dir().join("asks.jsonl")
}

/// Every ask, newest state of each, oldest first.
pub fn load_asks(path: &Path) -> Vec<AskRecord> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return vec![];
    };
    let mut out: Vec<AskRecord> = vec![];
    for line in text.lines() {
        let Ok(r) = serde_json::from_str::<AskRecord>(line) else {
            continue;
        };
        match out.iter_mut().find(|x| x.ask_id == r.ask_id) {
            Some(x) => *x = r,
            None => out.push(r),
        }
    }
    out
}

/// Appends one change.
pub fn save_ask(path: &Path, r: &AskRecord) {
    append_line(path, r);
}

/// Appends one JSON line to a file the owner alone can read.
pub fn append_line<T: serde::Serialize>(path: &Path, v: &T) {
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let Ok(line) = serde_json::to_string(v) else {
        return;
    };
    use std::os::unix::fs::OpenOptionsExt;
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .mode(0o600)
        .open(path)
    {
        let _ = writeln!(f, "{line}");
    }
}

/// What an ask's kind is, from the `--kind` word.
pub fn kind_of(word: &str) -> Option<NeedsKind> {
    Some(match word {
        "check-fix" => NeedsKind::CheckFix,
        "approve" => NeedsKind::Approve,
        "choose" => NeedsKind::Choose,
        "release" => NeedsKind::Release,
        _ => return None,
    })
}

/// The choices a kind shows when the ask gives none.
pub fn default_choices(kind: NeedsKind) -> Vec<String> {
    match kind {
        NeedsKind::CheckFix => vec!["It works".into(), "Still broken".into()],
        NeedsKind::Approve => vec!["Approve".into()],
        NeedsKind::Release => vec!["Release".into(), "Keep for later".into()],
        _ => vec![],
    }
}

/// The answer as the asking session reads it.
pub fn answer_text(a: &ItemAnswer, choices: &[String]) -> String {
    a.words(choices)
}

// ---- "Always allow edits to this file (this task)" ----

/// The tools whose requests a per-file rule covers, and the field naming
/// the file.
fn file_of(r: &Request) -> Option<String> {
    let key = match r.tool_name.as_str() {
        "Edit" | "Write" | "MultiEdit" => "file_path",
        "NotebookEdit" => "notebook_path",
        _ => return None,
    };
    r.tool_input
        .get(key)
        .and_then(|v| v.as_str())
        .map(str::to_string)
}

/// The file a request edits, if a per-file rule can cover it.
pub fn edited_file(r: &Request) -> Option<String> {
    file_of(r)
}

/// The rules file beside a task's file (not `.json`, so the task store
/// never reads it as a task).
pub fn rules_path(store_dir: &Path, task: &str) -> PathBuf {
    let safe: String = task
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
        .collect();
    store_dir.join(format!("{safe}.rules"))
}

/// The files the phone allowed for this task.
pub fn load_rules(store_dir: &Path, task: &str) -> Vec<String> {
    std::fs::read(rules_path(store_dir, task))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

pub fn add_rule(store_dir: &Path, task: &str, file: &str) {
    let mut rules = load_rules(store_dir, task);
    if !rules.iter().any(|f| f == file) {
        rules.push(file.to_string());
    }
    let _ = std::fs::create_dir_all(store_dir);
    if let Ok(b) = serde_json::to_vec(&rules) {
        let _ = std::fs::write(rules_path(store_dir, task), b);
    }
}

/// One file's rule goes: its edits ask first again.
pub fn remove_rule(store_dir: &Path, task: &str, file: &str) {
    let mut rules = load_rules(store_dir, task);
    rules.retain(|f| f != file);
    if rules.is_empty() {
        remove_rules(store_dir, task);
    } else if let Ok(b) = serde_json::to_vec(&rules) {
        let _ = std::fs::write(rules_path(store_dir, task), b);
    }
}

pub fn remove_rules(store_dir: &Path, task: &str) {
    let _ = std::fs::remove_file(rules_path(store_dir, task));
}

/// Whether a rule of this task allows this request.
pub fn rule_allows(store_dir: &Path, task: &str, r: &Request) -> bool {
    match file_of(r) {
        Some(f) => load_rules(store_dir, task).iter().any(|x| x == &f),
        None => false,
    }
}

/// Returns the existing `created` time for an item that is already in `sent`,
/// or `now` for a new one (PCS-12: stamp once, never re-stamp on re-read).
pub fn stamp_or_now(ask_id: &str, sent: &[mimir_proto::NeedsYouItem], now: i64) -> i64 {
    sent.iter()
        .find(|n| n.ask_id == ask_id)
        .map(|n| n.created)
        .unwrap_or(now)
}

/// `mimir fresh-start` (what tools/relay.sh runs, as a session's last
/// step): the task starts afresh from its hand-off once this run ends.
/// Prints "Fresh start queued for <task>" or the plain reason.
pub fn fresh_command() -> i32 {
    use crate::sockets::SessionMsg;
    let msg = SessionMsg::FreshStart {
        task: std::env::var("MIMIR_TASK_ID")
            .ok()
            .filter(|s| !s.is_empty()),
    };
    let reply = std::os::unix::net::UnixStream::connect(crate::sockets::session_socket())
        .ok()
        .and_then(|mut s| {
            crate::sockets::write_msg(&mut s, &msg).ok()?;
            crate::sockets::read_msg::<String>(&mut s).ok().flatten()
        });
    match reply {
        Some(r) => match r.strip_prefix("refused: ") {
            Some(why) => {
                eprintln!("{why}");
                1
            }
            None => {
                println!("{r}");
                0
            }
        },
        None => {
            eprintln!("Mímir isn't running, so there is no fresh start.");
            1
        }
    }
}

/// `mimir ask`: asks the owner through the service.
///
/// ```text
/// mimir ask --kind check-fix|approve|choose|release --text "…"
///     [--project <folder>] [--choice "It works"]… [--link <url>] [--wait]
/// mimir ask --wait <id>
/// ```
///
/// Prints the item's id (or, with `--wait`, the owner's answer).
pub fn ask_command(args: &[String]) -> i32 {
    use crate::sockets::SessionMsg;
    let mut text = None;
    let mut kind = None;
    let mut project = None;
    let mut link = None;
    let mut choices = vec![];
    let mut wait = false;
    let mut wait_for = None;
    let mut i = 0;
    while i < args.len() {
        let next = || args.get(i + 1).cloned();
        match args[i].as_str() {
            "--text" => text = next(),
            "--kind" => kind = next(),
            "--project" => project = next(),
            "--link" => link = next(),
            "--choice" => choices.extend(next()),
            "--wait" => {
                wait = true;
                if let Some(id) = next().filter(|n| !n.starts_with("--")) {
                    wait_for = Some(id);
                } else {
                    i += 1;
                    continue;
                }
            }
            other => {
                eprintln!("mimir ask: unknown {other}");
                return 2;
            }
        }
        i += 2;
    }
    let msg = match (wait_for, text) {
        (Some(ask_id), _) => SessionMsg::Wait { ask_id },
        (None, Some(text)) => SessionMsg::Ask {
            text,
            choices,
            wait,
            project: project.map(|p| crate::paths::clean_path(&p)),
            kind,
            link,
        },
        (None, None) => {
            eprintln!(
                "usage: mimir ask --kind check-fix|approve|choose|release --text \"…\" \
                 [--project <folder>] [--choice …] [--link <url>] [--wait]\n       \
                 mimir ask --wait <id>"
            );
            return 2;
        }
    };
    let mut s = match std::os::unix::net::UnixStream::connect(crate::sockets::session_socket()) {
        Ok(s) => s,
        Err(_) => {
            eprintln!("mimir ask: Mímir's service isn't running");
            return 1;
        }
    };
    if crate::sockets::write_msg(&mut s, &msg).is_err() {
        eprintln!("mimir ask: the service didn't take it");
        return 1;
    }
    match crate::sockets::read_msg::<String>(&mut s) {
        Ok(Some(r)) if r.starts_with("refused") || r.starts_with("not yet") => {
            eprintln!("mimir ask: {r}");
            1
        }
        Ok(Some(r)) => {
            println!("{r}");
            0
        }
        _ => {
            eprintln!("mimir ask: no answer from the service");
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mimir_proto::{NeedsKind, NeedsYouItem};
    use serde_json::json;

    #[test]
    fn needs_you_stamp_once() {
        // PCS-12: a "needs you" item already in `sent` keeps its original created time.
        let existing = NeedsYouItem {
            ask_id: "q:t-1:5".into(),
            kind: NeedsKind::Question,
            project: "/p".into(),
            task: Some("t-1".into()),
            title: "T".into(),
            text: "question".into(),
            preview: vec![],
            choices: vec![],
            serious: false,
            created: 100,
            link: None,
        };
        let sent = vec![existing.clone()];
        // Same id: use the stored created time, not now.
        assert_eq!(stamp_or_now("q:t-1:5", &sent, 200), 100);
        // New id: use now.
        assert_eq!(stamp_or_now("q:t-1:6", &sent, 200), 200);
    }

    fn req(tool: &str, input: serde_json::Value) -> Request {
        Request {
            task: "t".into(),
            tool_name: tool.into(),
            tool_input: input,
            permission_suggestions: json!([]),
        }
    }

    #[test]
    fn ids_are_stable_and_differ_by_request() {
        let a = req("Edit", json!({"file_path": "a.rs"}));
        let b = req("Edit", json!({"file_path": "b.rs"}));
        assert_eq!(permission_id("t", &a), permission_id("t", &a));
        assert_ne!(permission_id("t", &a), permission_id("t", &b));
        assert_ne!(permission_id("t", &a), permission_id("u", &a));
    }

    #[test]
    fn a_file_rule_covers_edits_to_that_file_only() {
        let dir = std::env::temp_dir().join(format!("needs-rules-{}", std::process::id()));
        add_rule(&dir, "t-1", "/p/a.rs");
        assert!(rule_allows(
            &dir,
            "t-1",
            &req("Edit", json!({"file_path": "/p/a.rs"}))
        ));
        assert!(rule_allows(
            &dir,
            "t-1",
            &req("Write", json!({"file_path": "/p/a.rs"}))
        ));
        assert!(!rule_allows(
            &dir,
            "t-1",
            &req("Edit", json!({"file_path": "/p/b.rs"}))
        ));
        assert!(!rule_allows(
            &dir,
            "t-1",
            &req("Bash", json!({"command": "rm /p/a.rs"}))
        ));
        assert!(!rule_allows(
            &dir,
            "t-2",
            &req("Edit", json!({"file_path": "/p/a.rs"}))
        ));
        remove_rules(&dir, "t-1");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn asks_keep_the_newest_state_of_each() {
        let p = std::env::temp_dir().join(format!("needs-asks-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        let mut a = AskRecord {
            ask_id: "ask:1".into(),
            kind: NeedsKind::CheckFix,
            project: "/p".into(),
            task: None,
            text: "Is the fix good?".into(),
            choices: default_choices(NeedsKind::CheckFix),
            link: None,
            created: 1,
            answer: None,
            answered_by: None,
            answered_at: None,
        };
        save_ask(&p, &a);
        a.answer = Some("It works".into());
        save_ask(&p, &a);
        let all = load_asks(&p);
        assert_eq!(all.len(), 1);
        assert_eq!(all[0].answer.as_deref(), Some("It works"));
        let _ = std::fs::remove_file(&p);
    }
}
