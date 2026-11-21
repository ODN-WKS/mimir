//! Whole-file atomic writes: the new bytes go to a temp file with a name of
//! its own, the file is synced, renamed over the old one, and the folder is
//! synced. A crash at any point leaves either the old file or the new one,
//! never a half-written one.

use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

/// The ending of temp files; ones left by a crash are removed by [`sweep`].
pub const TMP_END: &str = ".mimir-tmp";

static NEXT: AtomicU64 = AtomicU64::new(0);

/// A temp name beside `path` that no other writer uses.
fn temp_for(path: &Path) -> PathBuf {
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let n = NEXT.fetch_add(1, Ordering::Relaxed);
    path.with_file_name(format!(".{name}.{}.{n}{TMP_END}", std::process::id()))
}

/// Writes `bytes` as the whole of `path` (mode 0600, folder 0700).
pub fn write(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let dir = path.parent().unwrap_or(Path::new("."));
    std::fs::create_dir_all(dir)?;
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700));
    }
    let tmp = temp_for(path);
    let done = (|| {
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        std::fs::rename(&tmp, path)?;
        std::fs::File::open(dir)?.sync_all()
    })();
    if done.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    done
}

/// Removes temp files a crashed writer left in `dir` (never one a live
/// process is still writing).
pub fn sweep(dir: &Path) {
    for e in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        let Some(rest) = name.strip_suffix(TMP_END) else {
            continue;
        };
        let pid = rest.rsplit('.').nth(1).and_then(|p| p.parse::<u32>().ok());
        let alive = pid.is_some_and(|p| Path::new(&format!("/proc/{p}")).exists());
        if !alive {
            let _ = std::fs::remove_file(e.path());
        }
    }
}
