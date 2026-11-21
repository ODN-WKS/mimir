//! Where Mímir's own files live, the icon, and a safe way to write a file.
//! Installing and removing the app is the software centre's job (Mímir is
//! a Flatpak); nothing here installs anything.

use std::fs;
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

/// The menu entry's icon, Mímir's round stone well, at each size the menus
/// ask for (made from docs/designs/mimir/icon/mimir-app-icon-1024.png).
pub const ICONS: [(u32, &[u8]); 8] = [
    (16, include_bytes!("../../../assets/icons/mimir-16.png")),
    (24, include_bytes!("../../../assets/icons/mimir-24.png")),
    (32, include_bytes!("../../../assets/icons/mimir-32.png")),
    (48, include_bytes!("../../../assets/icons/mimir-48.png")),
    (64, include_bytes!("../../../assets/icons/mimir-64.png")),
    (128, include_bytes!("../../../assets/icons/mimir-128.png")),
    (256, include_bytes!("../../../assets/icons/mimir-256.png")),
    (512, include_bytes!("../../../assets/icons/mimir-512.png")),
];
/// Where things are, under one home folder.
#[derive(Debug, Clone)]
pub struct Layout {
    pub home: PathBuf,
}

impl Layout {
    pub fn for_user() -> Self {
        Layout {
            home: PathBuf::from(std::env::var_os("HOME").unwrap_or_default()),
        }
    }

    /// The program of an older copy that installed itself (before Mímir
    /// became a Flatpak).
    pub fn bin(&self) -> PathBuf {
        self.home.join(".local/bin/mimir")
    }
    pub fn data_dir(&self) -> PathBuf {
        self.home.join(".local/share/mimir")
    }
    pub fn config_dir(&self) -> PathBuf {
        self.home.join(".config/mimir")
    }
}

/// Writes `data` to `path` through a temporary file, so a half-written file
/// never replaces a good one.
pub fn write_atomic(path: &Path, data: &[u8], mode: u32) -> std::io::Result<()> {
    if let Some(d) = path.parent() {
        fs::create_dir_all(d)?;
    }
    let tmp = path.with_extension("new");
    {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(data)?;
        f.sync_all()?;
    }
    fs::set_permissions(&tmp, fs::Permissions::from_mode(mode))?;
    fs::rename(&tmp, path)
}
