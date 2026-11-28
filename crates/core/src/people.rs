//! One PC, several people. A paired phone belongs to a person, decided at
//! the PC when it was paired and kept with the phone's link key
//! (`phones.json`); the person is read from that, never from anything a
//! phone sends. The owner's phones are the owner's person; anyone else gets
//! their own chats, their own settings and none of the owner's work.
//!
//! What another person may do is a list of calls ([`other_may`]); every
//! other call is refused before it reaches anything.

use mimir_proto::Call;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;

/// The PC's owner as a person (the window is always this one).
pub const OWNER: &str = mimir_store::OWNER;

/// Whether a person other than the owner may make this call. Everything
/// about projects, tasks, updates, files, the PC's settings and the other
/// phones is the owner's; chats, the PC's free model, lookups and a
/// person's own settings are for everyone.
pub fn other_may(call: &Call) -> bool {
    matches!(
        call,
        Call::Hello { .. }
            | Call::Sync { .. }
            | Call::Talk { .. }
            | Call::TalkStop { .. }
            | Call::TalkTurn { .. }
            | Call::ChatPut { .. }
            | Call::ChatsSince { .. }
            | Call::SetGroundRules { .. }
            | Call::SetEngines { .. }
            | Call::Models
            | Call::WebSearch { .. }
            | Call::Lookup { .. }
            | Call::Listen { .. }
    )
}

/// What a person other than the owner is told instead of an error when a
/// phone asks for something that is only the owner's but is asked for as a
/// matter of course (the lists a phone reads when it opens): an empty one.
pub fn empty_for_others(call: &Call) -> Option<serde_json::Value> {
    use serde_json::json;
    match call {
        Call::Projects => Some(json!({"projects": [], "last": null})),
        Call::Inbox | Call::Updates | Call::Ideas { .. } | Call::PcSessions => Some(json!([])),
        _ => None,
    }
}

/// The plain line for a call that is not theirs.
pub const NOT_YOURS: &str = "That is only for the PC's owner.";

/// Online answers use the owner's plan, so they are the owner's to allow.
pub const NO_ONLINE: &str =
    "The PC's owner hasn't turned on online answers for you. Chats use the PC's own model.";

/// What another person is told when the PC has no free model set up.
pub const NO_FREE_MODEL: &str =
    "Your PC has no model set up yet. Ask the PC's owner to add one, or to let you use Online.";

/// What one person sets for themselves on the PC.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Prefs {
    /// "free" (the PC's own model; the default) or "paid" (Claude).
    #[serde(default)]
    pub chats_engine: Option<String>,
    #[serde(default)]
    pub chats_model: Option<String>,
    #[serde(default)]
    pub ground_rules: Option<String>,
    #[serde(default)]
    pub ground_rules_on: Option<bool>,
    #[serde(default)]
    pub ground_rules_at: Option<i64>,
}

impl Prefs {
    pub fn chats_paid(&self) -> bool {
        self.chats_engine.as_deref() == Some("paid")
    }

    pub fn chats_model_alias(&self) -> Option<&'static str> {
        crate::talk::CLAUDE_CHOICES
            .iter()
            .find(|c| c.0 != "auto" && Some(c.0) == self.chats_model.as_deref())
            .map(|c| c.0)
    }

    /// The ground rules that count for this person, if they are on.
    pub fn rules(&self) -> Option<String> {
        if self.ground_rules_on == Some(false) {
            return None;
        }
        let text = self
            .ground_rules
            .as_deref()
            .filter(|t| !crate::settings::is_old_default(t))
            .unwrap_or(crate::settings::DEFAULT_GROUND_RULES)
            .trim();
        (!text.is_empty()).then(|| text.to_string())
    }
}

/// Every other person's settings, kept in a file beside the PC's settings.
#[derive(Debug, Default)]
pub struct Book {
    path: Option<PathBuf>,
    map: HashMap<String, Prefs>,
}

impl Book {
    pub fn load() -> Book {
        let path = crate::paths::settings_path().with_file_name("people-settings.json");
        let map = std::fs::read(&path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        Book {
            path: Some(path),
            map,
        }
    }

    /// A person's settings (the defaults for one who set nothing).
    pub fn get(&self, person: &str) -> Prefs {
        self.map.get(person).cloned().unwrap_or_default()
    }

    /// Changes a person's settings and keeps them.
    pub fn edit(&mut self, person: &str, f: impl FnOnce(&mut Prefs)) {
        f(self.map.entry(person.to_string()).or_default());
        self.save();
    }

    /// A person is gone: so are their settings.
    pub fn forget(&mut self, person: &str) {
        if self.map.remove(person).is_some() {
            self.save();
        }
    }

    fn save(&self) {
        let Some(p) = &self.path else { return };
        if let Some(d) = p.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        if let Ok(data) = serde_json::to_vec_pretty(&self.map) {
            let tmp = p.with_extension("json.tmp");
            if std::fs::write(&tmp, data).is_ok() {
                let _ = std::fs::rename(tmp, p);
            }
        }
    }
}

/// A chat id the phone's own check ("Run checks" in Labs) uses: its test
/// message is answered but never kept as a chat in anyone's list.
pub fn is_check_chat(id: &str) -> bool {
    id.starts_with("labs-check")
}

#[cfg(test)]
mod delete_project_tests {
    #[test]
    fn a_project_is_deleted_only_by_the_owners_phone() {
        let call = mimir_proto::Call::DeleteProject {
            project: "/tmp/recipe-box".into(),
        };
        assert!(call.phone_may());
        assert!(!super::other_may(&call));
    }
}
