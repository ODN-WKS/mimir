//! Undo for Claude's file changes: a private snapshot of the project before
//! each message the owner sends.
//!
//! Snapshots live in a separate git store in Mímir's own data
//! folder (one per project), so the project's own git history, branch and
//! staged files are never touched, and folders that are not git projects
//! work too. Files the project's `.gitignore` excludes, and heavy build or
//! dependency folders, are not covered.

use std::path::{Path, PathBuf};
use std::process::Stdio;

/// Projects with more files than this get no snapshots (it would be slow).
pub const MAX_FILES: usize = 20_000;

/// Files bigger than this are not snapshotted (a game data file or a disk
/// image would make every message slow and fill the disk).
pub const MAX_FILE_BYTES: u64 = 50 * 1024 * 1024;

/// Snapshots kept per project; older ones are let go.
const KEEP: usize = 200;

/// Never snapshotted: rebuilt from source, often huge.
const HEAVY: &[&str] = &[
    "target/",
    "node_modules/",
    ".venv/",
    "venv/",
    "__pycache__/",
    ".cache/",
    "dist/",
    "build/",
    ".gradle/",
    ".next/",
    "*.iso",
    "*.img",
    "*.qcow2",
    "*.vdi",
];

pub use mimir_proto::UndoChange as Change;

pub struct Shadow {
    git_dir: PathBuf,
    work: PathBuf,
}

impl Shadow {
    /// The snapshot store for one project, under `data_dir`.
    pub fn new(data_dir: &Path, project: &Path) -> Self {
        let name = crate::sessions::folder_name(project);
        Shadow {
            git_dir: data_dir.join("checkpoints").join(format!("{name}.git")),
            work: project.to_path_buf(),
        }
    }

    fn git(&self, args: &[&str]) -> Option<String> {
        let out = crate::flatpak::host_cmd("git")
            .arg("--git-dir")
            .arg(&self.git_dir)
            .arg("--work-tree")
            .arg(&self.work)
            .args([
                "-c",
                "user.name=Mímir",
                "-c",
                "user.email=station@localhost",
                "-c",
                "core.autocrlf=false",
                "-c",
                "core.quotepath=false",
                "-c",
                "gc.auto=0",
            ])
            .args(args)
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output()
            .ok()?;
        out.status
            .success()
            .then(|| String::from_utf8_lossy(&out.stdout).to_string())
    }

    fn ensure(&self) -> bool {
        if self.git_dir.join("HEAD").exists() {
            return true;
        }
        if std::fs::create_dir_all(&self.git_dir).is_err() {
            return false;
        }
        let ok = crate::flatpak::host_cmd("git")
            .args(["init", "-q", "--bare"])
            .arg(&self.git_dir)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .is_ok_and(|s| s.success());
        if ok {
            let _ = std::fs::create_dir_all(self.git_dir.join("info"));
            let _ = std::fs::write(self.git_dir.join("info/exclude"), HEAVY.join("\n") + "\n");
        }
        ok
    }

    /// Takes a snapshot of the project now. None when it cannot (not
    /// writable, git missing, or too many files).
    pub fn snapshot(&self, label: &str) -> Option<String> {
        if !self.work.is_dir() || !self.ensure() {
            return None;
        }
        let files = self.git(&["ls-files", "-co", "--exclude-standard", "-z"])?;
        let files: Vec<&str> = files.split('\0').filter(|f| !f.is_empty()).collect();
        if files.len() > MAX_FILES {
            return None;
        }
        // Big files stay out: listed in the store's own exclude file.
        let big: Vec<String> = files
            .iter()
            .filter(|f| {
                std::fs::metadata(self.work.join(f)).is_ok_and(|m| m.len() > MAX_FILE_BYTES)
            })
            .map(|f| {
                let escaped: String = f
                    .chars()
                    .flat_map(|c| match c {
                        '*' | '?' | '[' | '\\' | '!' | '#' | ' ' => vec!['\\', c],
                        c => vec![c],
                    })
                    .collect();
                format!("/{escaped}")
            })
            .collect();
        let mut exclude = HEAVY.join("\n") + "\n";
        for b in &big {
            exclude.push_str(b);
            exclude.push('\n');
        }
        let _ = std::fs::write(self.git_dir.join("info/exclude"), exclude);
        if !big.is_empty() {
            // Forget big files that an earlier snapshot still lists.
            let mut args = vec!["rm", "-q", "--cached", "--ignore-unmatch", "--"];
            let paths: Vec<String> = big.iter().map(|b| b[1..].replace('\\', "")).collect();
            args.extend(paths.iter().map(String::as_str));
            let _ = self.git(&args);
        }
        self.git(&["add", "-A", "--ignore-errors", "."])?;
        let tree = self.git(&["write-tree"])?;
        let commit = self.git(&["commit-tree", tree.trim(), "-m", label])?;
        let commit = commit.trim().to_string();
        self.git(&["update-ref", &format!("refs/snapshots/{commit}"), &commit])?;
        self.prune();
        Some(commit)
    }

    /// What an undo to `commit` would change, compared with the project now.
    pub fn changes_since(&self, commit: &str) -> Option<Vec<Change>> {
        let now = self.snapshot("now")?;
        let diff = self.git(&["diff", "--name-status", "--no-renames", "-z", commit, &now])?;
        // When the snapshot was taken: a file older than that which the
        // snapshot does not have was there already (it was left out, for
        // example by .gitignore), so undo must not remove it.
        let taken: i64 = self
            .git(&["show", "-s", "--format=%ct", commit])
            .and_then(|t| t.trim().parse().ok())
            .unwrap_or(0);
        let mut out = vec![];
        let mut it = diff.split('\0');
        while let (Some(status), Some(path)) = (it.next(), it.next()) {
            if status.is_empty() {
                break;
            }
            let created_since = || {
                std::fs::metadata(self.work.join(path))
                    .and_then(|m| m.modified())
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .is_none_or(|d| d.as_secs() as i64 + 1 >= taken)
            };
            if status.starts_with('A') {
                if created_since() {
                    out.push(Change {
                        path: path.to_string(),
                        action: "remove".into(),
                    });
                }
            } else {
                out.push(Change {
                    path: path.to_string(),
                    action: "put back".into(),
                });
            }
        }
        Some(out)
    }

    /// Puts the project's files back as they were at `commit`. Returns the
    /// snapshot taken just before (to put the undone changes back) and how
    /// many files changed.
    pub fn restore(&self, commit: &str) -> Result<(String, usize), String> {
        let before = self
            .snapshot("before undo")
            .ok_or("Could not take a safety snapshot first, so nothing was changed.")?;
        let changes = self
            .changes_since(commit)
            .ok_or("Could not compare the files.")?;
        for c in &changes {
            let full = self.work.join(&c.path);
            if c.action == "remove" {
                let _ = std::fs::remove_file(&full);
            } else {
                if let Some(d) = full.parent() {
                    let _ = std::fs::create_dir_all(d);
                }
                // Binary-safe: git writes the file itself.
                let ok = crate::flatpak::host_cmd("git")
                    .arg("--git-dir")
                    .arg(&self.git_dir)
                    .arg("--work-tree")
                    .arg(&self.work)
                    .args(["checkout", commit, "--", &c.path])
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .status()
                    .is_ok_and(|s| s.success());
                if !ok {
                    return Err(format!(
                        "Could not put back {}. Check that the file is not open elsewhere, then try again.",
                        c.path
                    ));
                }
            }
        }
        Ok((before, changes.len()))
    }

    /// Lets go of the oldest snapshots beyond the ones kept.
    fn prune(&self) {
        let Some(refs) = self.git(&[
            "for-each-ref",
            "--sort=committerdate",
            "--format=%(refname)",
            "refs/snapshots/",
        ]) else {
            return;
        };
        let refs: Vec<&str> = refs.lines().collect();
        if refs.len() <= KEEP {
            return;
        }
        for r in &refs[..refs.len() - KEEP] {
            let _ = self.git(&["update-ref", "-d", r]);
        }
        let _ = self.git(&["gc", "--prune=now", "--quiet"]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn undo_and_put_back() {
        let root = std::env::temp_dir().join(format!("station-undo-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let project = root.join("my project");
        std::fs::create_dir_all(project.join("src")).unwrap();
        std::fs::create_dir_all(project.join("target")).unwrap();
        std::fs::write(project.join("src/a.txt"), "one\n").unwrap();
        std::fs::write(project.join("gone.txt"), "keep me\n").unwrap();
        std::fs::write(project.join("target/big.bin"), "built\n").unwrap();
        let s = Shadow::new(&root.join("data"), &project);
        let before = s.snapshot("before message").unwrap();

        // Claude changes, deletes and creates files.
        std::fs::write(project.join("src/a.txt"), "two\n").unwrap();
        std::fs::remove_file(project.join("gone.txt")).unwrap();
        std::fs::write(project.join("new é.txt"), "new\n").unwrap();
        std::fs::write(project.join("target/big.bin"), "rebuilt\n").unwrap();

        let mut c = s.changes_since(&before).unwrap();
        c.sort_by(|a, b| a.path.cmp(&b.path));
        let got: Vec<(&str, &str)> = c
            .iter()
            .map(|c| (c.path.as_str(), c.action.as_str()))
            .collect();
        assert_eq!(
            got,
            [
                ("gone.txt", "put back"),
                ("new é.txt", "remove"),
                ("src/a.txt", "put back")
            ]
        );

        let (after, n) = s.restore(&before).unwrap();
        assert_eq!(n, 3);
        assert_eq!(
            std::fs::read_to_string(project.join("src/a.txt")).unwrap(),
            "one\n"
        );
        assert_eq!(
            std::fs::read_to_string(project.join("gone.txt")).unwrap(),
            "keep me\n"
        );
        assert!(!project.join("new é.txt").exists());
        // Heavy folders are left alone.
        assert_eq!(
            std::fs::read_to_string(project.join("target/big.bin")).unwrap(),
            "rebuilt\n"
        );

        // Put the undone changes back.
        s.restore(&after).unwrap();
        assert_eq!(
            std::fs::read_to_string(project.join("src/a.txt")).unwrap(),
            "two\n"
        );
        assert!(project.join("new é.txt").exists());
        assert!(!project.join("gone.txt").exists());
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn older_files_left_out_are_never_removed() {
        let root = std::env::temp_dir().join(format!("station-undo-ign-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let project = root.join("p");
        std::fs::create_dir_all(&project).unwrap();
        std::fs::write(project.join(".gitignore"), "*.env\n").unwrap();
        std::fs::write(project.join("secret.env"), "KEY=1\n").unwrap();
        // The file is older than the snapshot.
        std::thread::sleep(std::time::Duration::from_millis(2100));
        let s = Shadow::new(&root.join("data"), &project);
        let snap = s.snapshot("x").unwrap();
        std::thread::sleep(std::time::Duration::from_millis(2100));
        // .gitignore changes so secret.env is no longer ignored.
        std::fs::write(project.join(".gitignore"), "").unwrap();
        std::fs::write(project.join("new.txt"), "n\n").unwrap();
        let c = s.changes_since(&snap).unwrap();
        assert!(c.iter().all(|c| c.path != "secret.env"), "{c:?}");
        assert!(
            c.iter()
                .any(|c| c.path == "new.txt" && c.action == "remove")
        );
        s.restore(&snap).unwrap();
        assert!(project.join("secret.env").exists());
        assert!(!project.join("new.txt").exists());
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn big_files_are_left_out() {
        let root = std::env::temp_dir().join(format!("station-undo-big-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let project = root.join("p");
        std::fs::create_dir_all(&project).unwrap();
        let f = std::fs::File::create(project.join("game.db")).unwrap();
        f.set_len(MAX_FILE_BYTES + 1).unwrap();
        std::fs::write(project.join("a.txt"), "a\n").unwrap();
        let s = Shadow::new(&root.join("data"), &project);
        let snap = s.snapshot("x").unwrap();
        let listed = s.git(&["ls-tree", "-r", "--name-only", &snap]).unwrap();
        assert!(
            listed.contains("a.txt") && !listed.contains("game.db"),
            "{listed}"
        );
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn a_project_git_repo_is_untouched() {
        let root = std::env::temp_dir().join(format!("station-undo-git-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let project = root.join("p");
        std::fs::create_dir_all(&project).unwrap();
        let git = |args: &[&str]| {
            crate::flatpak::host_cmd("git")
                .arg("-C")
                .arg(&project)
                .args(args)
                .output()
                .unwrap()
        };
        git(&["init", "-q"]);
        std::fs::write(project.join("a"), "1\n").unwrap();
        git(&["add", "a"]);
        let status_before = git(&["status", "--porcelain"]).stdout;
        let s = Shadow::new(&root.join("data"), &project);
        let snap = s.snapshot("x").unwrap();
        std::fs::write(project.join("a"), "2\n").unwrap();
        s.restore(&snap).unwrap();
        assert_eq!(git(&["status", "--porcelain"]).stdout, status_before);
        std::fs::remove_dir_all(&root).unwrap();
    }
}
