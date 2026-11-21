//! What the general chat keeps on the PC: each accepted message id (so a
//! message sent again after the link dropped isn't done twice), the
//! projects' ideas, the Inbox, and a log of how each message was filed.

use crate::needs::{append_line, now};
use mimir_proto::{Idea, InboxItem};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// How long an accepted message id is remembered.
pub const MSG_ID_DAYS: i64 = 30;

fn dir() -> PathBuf {
    crate::paths::data_dir()
}

/// A call's reply as kept for a message id.
type Kept = Result<serde_json::Value, String>;

/// Accepted message ids and the reply each got.
pub struct MsgIds {
    path: PathBuf,
    seen: HashMap<String, (i64, Kept)>,
}

#[derive(serde::Serialize, serde::Deserialize)]
struct MsgLine {
    msg_id: String,
    time: i64,
    reply: Result<serde_json::Value, String>,
}

impl MsgIds {
    pub fn load() -> MsgIds {
        Self::load_from(dir().join("msg_ids.jsonl"))
    }

    pub fn load_from(path: PathBuf) -> MsgIds {
        let cutoff = now() - MSG_ID_DAYS * 86_400;
        let mut seen = HashMap::new();
        if let Ok(text) = std::fs::read_to_string(&path) {
            for l in text.lines() {
                if let Ok(m) = serde_json::from_str::<MsgLine>(l)
                    && m.time >= cutoff
                {
                    seen.insert(m.msg_id, (m.time, m.reply));
                }
            }
        }
        let ids = MsgIds { path, seen };
        ids.rewrite();
        ids
    }

    /// Keeps only the last 30 days on disk.
    fn rewrite(&self) {
        let mut lines: Vec<(&String, &(i64, Kept))> = self.seen.iter().collect();
        lines.sort_by_key(|(_, (t, _))| *t);
        let text: String = lines
            .into_iter()
            .filter_map(|(id, (t, r))| {
                serde_json::to_string(&MsgLine {
                    msg_id: id.clone(),
                    time: *t,
                    reply: r.clone(),
                })
                .ok()
            })
            .map(|l| l + "\n")
            .collect();
        if let Some(d) = self.path.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        let _ = std::fs::write(&self.path, text);
    }

    /// The reply a message id already got.
    pub fn get(&self, msg_id: &str) -> Option<Result<serde_json::Value, String>> {
        self.seen.get(msg_id).map(|(_, r)| r.clone())
    }

    pub fn put(&mut self, msg_id: &str, reply: &Result<serde_json::Value, String>) {
        let t = now();
        self.seen.insert(msg_id.to_string(), (t, reply.clone()));
        append_line(
            &self.path,
            &MsgLine {
                msg_id: msg_id.to_string(),
                time: t,
                reply: reply.clone(),
            },
        );
    }
}

/// `ideas/<folder>.jsonl`: the folder path made safe as a file name.
pub fn ideas_path(project: &str) -> PathBuf {
    let safe: String = project
        .trim_end_matches('/')
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect();
    dir().join("ideas").join(format!("{safe}.jsonl"))
}

/// A project's ideas, oldest first, removed ones left out.
pub fn ideas(project: &str) -> Vec<Idea> {
    let Ok(text) = std::fs::read_to_string(ideas_path(project)) else {
        return vec![];
    };
    let mut out: Vec<Idea> = vec![];
    for l in text.lines() {
        if let Ok(i) = serde_json::from_str::<Idea>(l) {
            match out.iter_mut().find(|x| x.id == i.id) {
                Some(x) => *x = i,
                None => out.push(i),
            }
        }
    }
    out.retain(|i| !i.removed);
    out
}

pub fn add_idea(project: &str, text: &str, msg_id: &str) -> Idea {
    let i = Idea {
        id: crate::needs::new_id("idea"),
        time: now(),
        text: text.to_string(),
        msg_id: Some(msg_id.to_string()),
        removed: false,
    };
    append_line(&ideas_path(project), &i);
    i
}

/// "Undo": the idea is marked removed.
pub fn remove_idea(project: &str, id: &str) -> bool {
    let Some(mut i) = ideas(project).into_iter().find(|i| i.id == id) else {
        return false;
    };
    i.removed = true;
    append_line(&ideas_path(project), &i);
    true
}

/// The Inbox: messages waiting for the owner to pick their project.
pub fn inbox_path() -> PathBuf {
    dir().join("inbox.jsonl")
}

#[derive(serde::Serialize, serde::Deserialize)]
struct InboxLine {
    item: InboxItem,
    #[serde(default)]
    filed: Option<String>,
}

pub fn inbox() -> Vec<InboxItem> {
    inbox_in(&inbox_path())
}

fn inbox_in(path: &Path) -> Vec<InboxItem> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return vec![];
    };
    let mut out: Vec<(InboxItem, bool)> = vec![];
    for l in text.lines() {
        if let Ok(x) = serde_json::from_str::<InboxLine>(l) {
            let done = x.filed.is_some();
            match out.iter_mut().find(|(i, _)| i.inbox_id == x.item.inbox_id) {
                Some(e) => *e = (x.item, done),
                None => out.push((x.item, done)),
            }
        }
    }
    out.into_iter()
        .filter(|(_, done)| !done)
        .map(|(i, _)| i)
        .collect()
}

pub fn add_to_inbox(msg_id: &str, text: &str, candidates: Vec<String>) -> InboxItem {
    let item = InboxItem {
        inbox_id: crate::needs::new_id("inbox"),
        msg_id: msg_id.to_string(),
        text: text.to_string(),
        candidates,
        time: now(),
    };
    append_line(
        &inbox_path(),
        &InboxLine {
            item: item.clone(),
            filed: None,
        },
    );
    item
}

/// Takes a message out of the Inbox once it's filed.
pub fn take_from_inbox(inbox_id: &str, project: &str) -> Option<InboxItem> {
    let item = inbox().into_iter().find(|i| i.inbox_id == inbox_id)?;
    append_line(
        &inbox_path(),
        &InboxLine {
            item: item.clone(),
            filed: Some(project.to_string()),
        },
    );
    Some(item)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_message_id_keeps_its_first_reply() {
        let p = std::env::temp_dir().join(format!("msgids-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&p);
        let mut ids = MsgIds::load_from(p.clone());
        assert!(ids.get("m1").is_none());
        ids.put("m1", &Ok(serde_json::json!("task-1")));
        let again = MsgIds::load_from(p.clone());
        assert_eq!(again.get("m1"), Some(Ok(serde_json::json!("task-1"))));
        let _ = std::fs::remove_file(&p);
    }
}
