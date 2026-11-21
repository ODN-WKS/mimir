//! Mímir's storage: chats, settings, identity and the model list, all
//! written by one writer thread with whole-file atomic writes about once a
//! second ([`writer`]). Only `mimir-core` and `mimir-hub` depend on it.

pub mod atomic;
pub mod identity;
pub mod keyring;
pub mod log;
pub mod models;
pub mod settings;
pub mod talk;
pub mod writer;

pub use identity::IdentityStore;
pub use keyring::Keyring;
pub use models::ModelList;
pub use settings::SettingsStore;
pub use talk::{OWNER, TalkStore, Unlock};
pub use writer::Writer;

use std::path::{Path, PathBuf};

/// Everything the PC keeps, under one folder.
pub struct Store {
    base: PathBuf,
    writer: Writer,
    talks: TalkStore,
    settings: SettingsStore,
    identity: IdentityStore,
    models: ModelList,
}

impl Store {
    /// Opens the store in `base` (normally [`data_dir`]), with the chats'
    /// key from the keyring this PC uses.
    pub fn open(base: &Path) -> std::io::Result<Store> {
        Store::open_with(base, &Keyring::from_env(), Writer::global().clone())
    }

    /// [`Store::open`] with a named keyring and writer (checks).
    pub fn open_with(base: &Path, ring: &Keyring, writer: Writer) -> std::io::Result<Store> {
        std::fs::create_dir_all(base)?;
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(base, std::fs::Permissions::from_mode(0o700))?;
        }
        atomic::sweep(base);
        Ok(Store {
            base: base.to_path_buf(),
            talks: TalkStore::open_with(&base.join("talk"), ring, writer.clone()),
            settings: SettingsStore::open(&base.join("settings.json"), writer.clone()),
            identity: IdentityStore::open(&base.join("identity.json"), writer.clone()),
            models: ModelList::open(&base.join("models.json"), writer.clone()),
            writer,
        })
    }

    pub fn base(&self) -> &Path {
        &self.base
    }

    pub fn talks(&self) -> &TalkStore {
        &self.talks
    }
    pub fn talks_mut(&mut self) -> &mut TalkStore {
        &mut self.talks
    }
    pub fn settings(&self) -> &SettingsStore {
        &self.settings
    }
    pub fn settings_mut(&mut self) -> &mut SettingsStore {
        &mut self.settings
    }
    pub fn identity(&self) -> &IdentityStore {
        &self.identity
    }
    pub fn identity_mut(&mut self) -> &mut IdentityStore {
        &mut self.identity
    }
    pub fn models(&self) -> &ModelList {
        &self.models
    }
    pub fn models_mut(&mut self) -> &mut ModelList {
        &mut self.models
    }

    /// Writes everything waiting now (at shutdown).
    pub fn flush(&self) -> Result<(), String> {
        self.writer.flush(std::time::Duration::from_secs(10))
    }
}

/// `$XDG_DATA_HOME/mimir` (or `~/.local/share/mimir`).
pub fn data_dir() -> PathBuf {
    std::env::var_os("XDG_DATA_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("mimir")
}

/// `$XDG_RUNTIME_DIR/mimir`, made 0700: where audio goes (never on disk).
pub fn audio_dir() -> std::io::Result<PathBuf> {
    use std::os::unix::fs::PermissionsExt;
    let run = std::env::var_os("XDG_RUNTIME_DIR")
        .filter(|v| !v.is_empty())
        .ok_or_else(|| std::io::Error::other("no XDG_RUNTIME_DIR"))?;
    let d = PathBuf::from(run).join("mimir");
    std::fs::create_dir_all(&d)?;
    std::fs::set_permissions(&d, std::fs::Permissions::from_mode(0o700))?;
    Ok(d)
}

/// Checks that change `MIMIR_SECRET_TOOL` take turns.
#[cfg(test)]
pub(crate) static TEST_TOOL: std::sync::Mutex<()> = std::sync::Mutex::new(());

#[cfg(test)]
mod scenarios;
