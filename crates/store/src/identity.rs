//! The PC's identity document (its name and link identity), written by the
//! store's writer like everything else.

use crate::writer::Writer;
use serde_json::Value;
use std::path::{Path, PathBuf};

pub struct IdentityStore {
    path: PathBuf,
    doc: Option<Value>,
    writer: Writer,
}

impl IdentityStore {
    pub fn open(path: &Path, writer: Writer) -> IdentityStore {
        let doc = std::fs::read(path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok());
        IdentityStore {
            path: path.to_path_buf(),
            doc,
            writer,
        }
    }

    /// The identity, if one was made.
    pub fn get(&self) -> Option<&Value> {
        self.doc.as_ref()
    }

    /// Keeps a new identity.
    pub fn set(&mut self, v: Value) {
        if let Ok(b) = serde_json::to_vec_pretty(&v) {
            self.writer.put(&self.path, b);
        }
        self.doc = Some(v);
    }
}
