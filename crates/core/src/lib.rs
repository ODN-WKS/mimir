//! Everything Mímir does that is not drawing the window: running
//! Claude Code, reading what it says, keeping tasks on disk.

pub mod api_key;
pub mod approvals;
pub mod approve;
pub mod audio;
pub mod auto;
pub mod chat;
pub mod checkpoints;
pub mod client;
pub mod copies;
pub mod devlog;
pub mod engine;
pub mod events;
pub mod facts;
pub mod flatpak;
pub mod git;
pub mod groups;
pub mod host;
pub mod import;
pub mod incognito;
pub mod install;
pub mod keep_current;
pub mod labs;
pub mod listen;
pub mod log;
pub mod long;
pub mod lookup;
pub mod models;
pub mod needs;
pub mod notify;
pub mod ollama;
pub mod order;
pub mod paths;
pub mod pcsessions;
pub mod pcstats;
pub mod people;
pub mod pictures;
pub mod placement;
pub mod process;
pub mod queue;
pub mod readfile;
pub mod restart;
pub mod route;
pub mod runner;
pub mod scrub;
pub mod searx;
pub mod service;
pub mod sessions;
pub mod settings;
pub mod setup;
pub mod sight;
pub mod snapshot;
pub mod sockets;
pub mod speech_text;
pub mod store;
pub mod talk;
pub mod talk_store;
pub mod task;
pub mod task_files;
pub mod tidy;
pub mod truth;
pub mod update;
pub mod usage;
pub mod voice;
pub mod wipe;
pub mod yolo;

pub use mimir_proto as proto;

/// Tests share one process under a plain `cargo test`, and HOME and the
/// Claude folder variable belong to all of it: a test that changes one, or
/// builds on it, holds this for its whole length.
#[cfg(test)]
pub(crate) mod test_env {
    use std::ffi::OsString;
    use std::sync::{Mutex, MutexGuard, PoisonError};

    static LOCK: Mutex<()> = Mutex::new(());
    const VARS: [&str; 2] = ["HOME", "CLAUDE_CONFIG_DIR"];

    pub struct Held {
        saved: Vec<Option<OsString>>,
        _lock: MutexGuard<'static, ()>,
    }

    pub fn hold() -> Held {
        let lock = LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        Held {
            saved: VARS.iter().map(std::env::var_os).collect(),
            _lock: lock,
        }
    }

    /// The variables go back before the lock is let go.
    impl Drop for Held {
        fn drop(&mut self) {
            for (var, was) in VARS.iter().zip(&self.saved) {
                // SAFETY: every test that changes these holds the lock.
                match was {
                    Some(v) => unsafe { std::env::set_var(var, v) },
                    None => unsafe { std::env::remove_var(var) },
                }
            }
        }
    }
}
