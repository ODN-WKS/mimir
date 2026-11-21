//! The settings document, kept in memory and written by the store's writer
//! (batched, atomic, retried). The service's typed `Settings` (in
//! mimir-core) is read from and saved through this document.

use crate::writer::Writer;
use serde_json::Value;
use std::path::{Path, PathBuf};

pub struct SettingsStore {
    path: PathBuf,
    doc: Value,
    writer: Writer,
    /// The file was there but couldn't be read: it's set aside, never
    /// written over.
    damaged: Option<String>,
}

impl SettingsStore {
    /// Reads `path` (a missing file is empty settings).
    pub fn open(path: &Path, writer: Writer) -> SettingsStore {
        let (doc, damaged) = match std::fs::read(path) {
            Ok(b) => match serde_json::from_slice::<Value>(&b) {
                Ok(v) if v.is_object() => (v, None),
                _ => {
                    let aside = path.with_extension("damaged");
                    let _ = std::fs::rename(path, &aside);
                    (
                        Value::Object(Default::default()),
                        Some(format!(
                            "{} was damaged; set aside as {}",
                            path.display(),
                            aside.display()
                        )),
                    )
                }
            },
            Err(_) => (Value::Object(Default::default()), None),
        };
        SettingsStore {
            path: path.to_path_buf(),
            doc,
            writer,
            damaged,
        }
    }

    /// The whole document.
    pub fn get(&self) -> &Value {
        &self.doc
    }

    /// One setting.
    pub fn value(&self, key: &str) -> Option<&Value> {
        self.doc.get(key)
    }

    /// Replaces the document; written within about a second.
    pub fn set(&mut self, doc: Value) {
        if doc != self.doc {
            self.doc = doc;
            self.save();
        }
    }

    /// Sets one setting.
    pub fn put(&mut self, key: &str, v: Value) {
        if self.doc.get(key) == Some(&v) {
            return;
        }
        if let Some(o) = self.doc.as_object_mut() {
            o.insert(key.to_string(), v);
        }
        self.save();
    }

    fn save(&self) {
        if let Ok(b) = serde_json::to_vec_pretty(&self.doc) {
            self.writer.put(&self.path, b);
        }
    }

    /// Why the file couldn't be read, if it couldn't.
    pub fn damaged(&self) -> Option<&str> {
        self.damaged.as_deref()
    }
}
