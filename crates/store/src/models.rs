//! The models on this PC and the current pick. The list never removes the
//! model that is the pick (PCE-22: a catalogue change once deleted it), and
//! in guest mode nothing is removed at all.

use crate::writer::Writer;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Kept {
    #[serde(default)]
    installed: Vec<String>,
    #[serde(default)]
    pick: Option<String>,
}

pub struct ModelList {
    path: PathBuf,
    kept: Kept,
    guest: bool,
    writer: Writer,
}

impl ModelList {
    pub fn open(path: &Path, writer: Writer) -> ModelList {
        let kept = std::fs::read(path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        ModelList {
            path: path.to_path_buf(),
            kept,
            guest: false,
            writer,
        }
    }

    fn save(&self) {
        if let Ok(b) = serde_json::to_vec_pretty(&self.kept) {
            self.writer.put(&self.path, b);
        }
    }

    pub fn installed(&self) -> &[String] {
        &self.kept.installed
    }

    pub fn pick(&self) -> Option<&str> {
        self.kept.pick.as_deref()
    }

    pub fn set_pick(&mut self, pick: Option<String>) {
        if self.kept.pick != pick {
            self.kept.pick = pick;
            self.save();
        }
    }

    /// What Ollama says is on the PC now.
    pub fn set_installed(&mut self, names: Vec<String>) {
        if self.kept.installed != names {
            self.kept.installed = names;
            self.save();
        }
    }

    /// Guest mode: nothing is removed while it's on.
    pub fn set_guest(&mut self, on: bool) {
        self.guest = on;
    }

    /// Whether `name` may be removed now.
    pub fn may_remove(&self, name: &str) -> bool {
        !self.guest && self.kept.pick.as_deref() != Some(name)
    }

    /// The models to remove when the catalogue now lists `catalogue`: the
    /// installed ones it dropped, never the pick, none in guest mode.
    pub fn removals(&self, catalogue: &[String]) -> Vec<String> {
        self.kept
            .installed
            .iter()
            .filter(|m| !catalogue.contains(m) && self.may_remove(m))
            .cloned()
            .collect()
    }

    /// Notes that `name` was removed (refused for the pick and in guest
    /// mode: false).
    pub fn removed(&mut self, name: &str) -> bool {
        if !self.may_remove(name) {
            return false;
        }
        let before = self.kept.installed.len();
        self.kept.installed.retain(|m| m != name);
        if self.kept.installed.len() != before {
            self.save();
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pick_is_never_removed_and_guest_mode_removes_nothing() {
        let d = std::env::temp_dir().join(format!("models-{}", std::process::id()));
        let w = Writer::spawn(std::time::Duration::from_millis(10));
        let mut l = ModelList::open(&d.join("models.json"), w.clone());
        l.set_installed(vec!["a".into(), "b".into(), "c".into()]);
        l.set_pick(Some("b".into()));
        // The catalogue dropped b (the pick) and c.
        assert_eq!(l.removals(&["a".into()]), vec!["c".to_string()]);
        assert!(!l.removed("b"));
        assert!(l.removed("c"));
        l.set_guest(true);
        assert!(l.removals(&[]).is_empty());
        assert!(!l.removed("a"));
        w.flush(std::time::Duration::from_secs(5)).unwrap();
        let again = ModelList::open(&d.join("models.json"), w);
        assert_eq!(again.installed(), ["a".to_string(), "b".to_string()]);
        assert_eq!(again.pick(), Some("b"));
        let _ = std::fs::remove_dir_all(&d);
    }
}
