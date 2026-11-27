//! Separate copies of a git project (git worktrees), so tasks that run at
//! the same time in one project never edit the same files. Each copy has
//! its own branch, `mimir/<name>`, and lives in Mímir's data
//! folder. "Bring the changes in" commits the copy's work on its branch and
//! merges that branch into the main folder; "Remove" deletes the copy and
//! its branch.

use std::path::{Path, PathBuf};
use std::process::Stdio;

fn git(dir: &Path, args: &[&str]) -> Result<String, String> {
    let out = crate::flatpak::host_cmd("git")
        .arg("-C")
        .arg(dir)
        .args(args)
        .stdin(Stdio::null())
        .output()
        .map_err(|e| format!("git could not start: {e}"))?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).to_string())
    } else {
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }
}

/// Whether a folder is a git project with at least one commit.
pub fn can_copy(project: &Path) -> bool {
    git(project, &["rev-parse", "--verify", "HEAD"]).is_ok()
}

/// A new copy of `project` for one task. Returns (copy folder, branch).
pub fn create(project: &Path, data_dir: &Path, name: &str) -> Result<(PathBuf, String), String> {
    if !can_copy(project) {
        return Err("A separate copy needs a git project with at least one commit.".into());
    }
    let short: String = name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
        .collect();
    let base = project
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "project".into());
    let copy = data_dir.join("copies").join(format!("{base}-{short}"));
    let branch = format!("mimir/{short}");
    std::fs::create_dir_all(data_dir.join("copies")).map_err(|e| e.to_string())?;
    let copy_s = copy.display().to_string();
    git(
        project,
        &["worktree", "add", "-q", "-b", &branch, &copy_s, "HEAD"],
    )
    .map_err(|e| format!("Could not make the copy: {e}"))?;
    Ok((copy, branch))
}

/// Commits the copy's work on its branch, then merges the branch into the
/// main folder. Nothing is left half-done: a merge that cannot finish
/// cleanly is taken back. Returns a short report.
pub fn bring_in(project: &Path, copy: &Path, branch: &str, title: &str) -> Result<String, String> {
    if !copy.is_dir() {
        return Err("The copy's folder is gone.".into());
    }
    // Not the copy's own "Always allow" rules: those are its settings, not work.
    git(
        copy,
        &[
            "add",
            "-A",
            "--",
            ".",
            ":(exclude).claude/settings.local.json",
        ],
    )?;
    let pending = git(copy, &["diff", "--cached", "--quiet"]).is_err();
    if pending {
        git(copy, &["commit", "-q", "-m", &format!("Mímir: {title}")])
            .map_err(|e| format!("Could not save the copy's work on its branch: {e}"))?;
    }
    // Never step into the owner's own unfinished merge, rebase or pick.
    let busy = ["MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD"]
        .iter()
        .any(|r| git(project, &["rev-parse", "-q", "--verify", r]).is_ok())
        || ["rebase-merge", "rebase-apply"].iter().any(|d| {
            git(project, &["rev-parse", "--git-path", d])
                .map(|p| {
                    let p = PathBuf::from(p.trim());
                    if p.is_absolute() { p } else { project.join(p) }
                })
                .is_ok_and(|p| p.exists())
        });
    if busy {
        return Err(
            "A merge or rebase is in progress in the main folder. Finish it first; nothing \
             was changed."
                .into(),
        );
    }
    let ahead = git(
        project,
        &["rev-list", "--count", &format!("HEAD..{branch}")],
    )?;
    if ahead.trim() == "0" {
        return Ok("Nothing new in the copy to bring in.".into());
    }
    match git(
        project,
        &[
            "merge",
            "--no-edit",
            "-m",
            &format!("Bring in: {title}"),
            branch,
        ],
    ) {
        Ok(_) => Ok(format!(
            "Brought the copy's changes into the main folder ({} commit{} on branch {branch}).",
            ahead.trim(),
            if ahead.trim() == "1" { "" } else { "s" }
        )),
        Err(_) => {
            // Only take back a merge this call started.
            if git(project, &["rev-parse", "-q", "--verify", "MERGE_HEAD"]).is_ok() {
                let _ = git(project, &["merge", "--abort"]);
            }
            Err(format!(
                "The changes could not be brought in cleanly, so nothing was changed. \
                 They stay on branch {branch}. Ask Mímir to bring them in for you.",
            ))
        }
    }
}

/// Deletes the copy and its branch.
/// Adds the copy's "Always allow" rules to the main folder's, so rules the
/// owner gave while working in the copy are kept.
fn keep_rules(project: &Path, copy: &Path) {
    use serde_json::Value;
    let read = |p: &Path| -> Option<Value> { serde_json::from_slice(&std::fs::read(p).ok()?).ok() };
    let Some(theirs) = read(&copy.join(".claude/settings.local.json")) else {
        return;
    };
    let Some(add) = theirs
        .pointer("/permissions/allow")
        .and_then(Value::as_array)
        .cloned()
    else {
        return;
    };
    let main_file = project.join(".claude/settings.local.json");
    let mut ours = read(&main_file).unwrap_or_else(|| serde_json::json!({}));
    if !ours.is_object() {
        return;
    }
    let allow = ours
        .as_object_mut()
        .map(|o| {
            o.entry("permissions")
                .or_insert_with(|| serde_json::json!({}))
        })
        .and_then(|p| p.as_object_mut())
        .map(|p| p.entry("allow").or_insert_with(|| serde_json::json!([])));
    let Some(Value::Array(list)) = allow else {
        return;
    };
    let before = list.len();
    for r in add {
        if !list.contains(&r) {
            list.push(r);
        }
    }
    if list.len() != before {
        let _ = std::fs::create_dir_all(project.join(".claude"));
        if let Ok(text) = serde_json::to_string_pretty(&ours) {
            let _ = std::fs::write(&main_file, text);
        }
    }
}

pub fn remove(project: &Path, copy: &Path, branch: &str) -> Result<(), String> {
    keep_rules(project, copy);
    let copy_s = copy.display().to_string();
    if copy.is_dir() {
        git(project, &["worktree", "remove", "--force", &copy_s])?;
    } else {
        let _ = git(project, &["worktree", "prune"]);
    }
    git(project, &["branch", "-D", branch]).map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn repo(root: &Path) -> PathBuf {
        let p = root.join("proj");
        std::fs::create_dir_all(&p).unwrap();
        git(&p, &["init", "-q"]).unwrap();
        git(&p, &["config", "user.name", "t"]).unwrap();
        git(&p, &["config", "user.email", "t@t"]).unwrap();
        std::fs::write(p.join("a.txt"), "one\n").unwrap();
        git(&p, &["add", "."]).unwrap();
        git(&p, &["commit", "-qm", "init"]).unwrap();
        p
    }

    #[test]
    fn copy_work_and_bring_in() {
        let root = std::env::temp_dir().join(format!("station-copies-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let p = repo(&root);
        let (copy, branch) = create(&p, &root.join("data"), "t1").unwrap();
        assert_eq!(branch, "mimir/t1");
        std::fs::write(copy.join("a.txt"), "two\n").unwrap();
        std::fs::write(copy.join("new.txt"), "new\n").unwrap();
        // The main folder is untouched until the changes are brought in.
        assert_eq!(std::fs::read_to_string(p.join("a.txt")).unwrap(), "one\n");
        let msg = bring_in(&p, &copy, &branch, "fix").unwrap();
        assert!(msg.contains("1 commit on branch"), "{msg}");
        assert_eq!(std::fs::read_to_string(p.join("a.txt")).unwrap(), "two\n");
        assert!(p.join("new.txt").exists());
        remove(&p, &copy, &branch).unwrap();
        assert!(!copy.exists());
        assert!(git(&p, &["rev-parse", "--verify", "mimir/t1"]).is_err());
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn an_unfinished_merge_of_the_owner_is_left_alone() {
        let root = std::env::temp_dir().join(format!("station-copies-m-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let p = repo(&root);
        let (copy, branch) = create(&p, &root.join("data"), "t3").unwrap();
        std::fs::write(copy.join("c.txt"), "c\n").unwrap();
        git(&p, &["checkout", "-qb", "feat"]).unwrap();
        std::fs::write(p.join("a.txt"), "feat\n").unwrap();
        git(&p, &["commit", "-qam", "feat"]).unwrap();
        git(&p, &["checkout", "-q", "-"]).unwrap();
        std::fs::write(p.join("a.txt"), "main\n").unwrap();
        git(&p, &["commit", "-qam", "main"]).unwrap();
        assert!(git(&p, &["merge", "feat"]).is_err());
        std::fs::write(p.join("a.txt"), "resolved\n").unwrap();
        let e = bring_in(&p, &copy, &branch, "x").unwrap_err();
        assert!(e.contains("in progress"), "{e}");
        assert!(git(&p, &["rev-parse", "-q", "--verify", "MERGE_HEAD"]).is_ok());
        assert_eq!(
            std::fs::read_to_string(p.join("a.txt")).unwrap(),
            "resolved\n"
        );
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn a_conflict_changes_nothing() {
        let root = std::env::temp_dir().join(format!("station-copies-c-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let p = repo(&root);
        let (copy, branch) = create(&p, &root.join("data"), "t2").unwrap();
        std::fs::write(copy.join("a.txt"), "from copy\n").unwrap();
        std::fs::write(p.join("a.txt"), "from main\n").unwrap();
        git(&p, &["commit", "-qam", "main moved"]).unwrap();
        let e = bring_in(&p, &copy, &branch, "x").unwrap_err();
        assert!(e.contains("nothing was changed"), "{e}");
        assert_eq!(
            std::fs::read_to_string(p.join("a.txt")).unwrap(),
            "from main\n"
        );
        assert!(
            git(&p, &["status", "--porcelain"])
                .unwrap()
                .trim()
                .is_empty()
        );
        std::fs::remove_dir_all(&root).unwrap();
    }
}
