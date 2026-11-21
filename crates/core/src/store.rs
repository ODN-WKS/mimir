//! Tasks on disk: one JSON file per task.

use crate::task::Task;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

pub struct Store {
    dir: PathBuf,
}

impl Store {
    pub fn new(dir: impl Into<PathBuf>) -> std::io::Result<Self> {
        let dir = dir.into();
        fs::create_dir_all(&dir)?;
        Ok(Store { dir })
    }

    /// `~/.local/share/mimir/tasks`, or under `$XDG_DATA_HOME`.
    pub fn default_dir() -> PathBuf {
        let base = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
            .unwrap_or_else(|| {
                PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".local/share")
            });
        base.join("mimir/tasks")
    }

    fn path(&self, id: &str) -> PathBuf {
        // Ids are made by the app, but never let one escape the folder.
        let safe: String = id
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
            .collect();
        self.dir.join(format!("{safe}.json"))
    }

    /// Writes the task safely: a half-written file can never replace a
    /// good one.
    pub fn save(&self, task: &Task) -> std::io::Result<()> {
        let path = self.path(&task.id);
        let tmp = path.with_extension("json.tmp");
        let data = serde_json::to_vec_pretty(task)?;
        let mut f = fs::File::create(&tmp)?;
        f.write_all(&data)?;
        f.sync_all()?;
        fs::rename(&tmp, &path)
    }

    pub fn remove(&self, id: &str) -> std::io::Result<()> {
        match fs::remove_file(self.path(id)) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }

    /// Every saved task, oldest first. Damaged files are skipped and
    /// reported, never deleted.
    pub fn load_all(&self) -> (Vec<Task>, Vec<PathBuf>) {
        let mut tasks = vec![];
        let mut bad = vec![];
        let Ok(rd) = fs::read_dir(&self.dir) else {
            return (tasks, bad);
        };
        for e in rd.flatten() {
            let p = e.path();
            if p.extension().and_then(|x| x.to_str()) != Some("json") {
                continue;
            }
            match load(&p) {
                Some(t) => tasks.push(t),
                None => bad.push(p),
            }
        }
        tasks.sort_by_key(|t| t.created_unix);
        (tasks, bad)
    }
}

fn load(p: &Path) -> Option<Task> {
    serde_json::from_slice(&fs::read(p).ok()?).ok()
}
