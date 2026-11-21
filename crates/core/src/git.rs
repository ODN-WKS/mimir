//! Git facts for the window: branch and real changes in the project.

use std::path::Path;

pub use mimir_proto::GitChange as Change;

fn git(project: &Path, args: &[&str]) -> Option<String> {
    let out = crate::flatpak::host_cmd("git")
        .arg("-C")
        .arg(project)
        .args(args)
        .output()
        .ok()?;
    out.status
        .success()
        .then(|| String::from_utf8_lossy(&out.stdout).to_string())
}

pub fn branch(project: &Path) -> Option<String> {
    let b = git(project, &["branch", "--show-current"])?;
    let b = b.trim();
    (!b.is_empty()).then(|| b.to_string())
}

/// Untracked files bigger than this get no line count (a huge log or
/// build output must not be read every few seconds).
const COUNT_LIMIT: u64 = 2 * 1024 * 1024;

/// Counts lines of a small file without loading big ones.
fn count_lines(path: &Path) -> u32 {
    use std::io::Read;
    let Ok(f) = std::fs::File::open(path) else {
        return 0;
    };
    if f.metadata().map(|m| m.len()).unwrap_or(u64::MAX) > COUNT_LIMIT {
        return 0;
    }
    let mut buf = Vec::new();
    if f.take(COUNT_LIMIT).read_to_end(&mut buf).is_err() {
        return 0;
    }
    let n = buf.iter().filter(|b| **b == b'\n').count();
    let last_unended = buf.last().is_some_and(|b| *b != b'\n');
    (n + usize::from(last_unended)) as u32
}

/// Uncommitted changes, compared with the last commit. None when the folder
/// is not a git repository.
pub fn changes(project: &Path) -> Option<Vec<Change>> {
    // -z: names exactly as they are (no quoting, no escapes), one per NUL.
    let status = git(
        project,
        &["status", "--porcelain=v1", "-z", "--untracked-files=all"],
    )?;
    let numstat = git(project, &["diff", "--numstat", "-z", "HEAD"]).unwrap_or_default();
    let counts = parse_numstat_z(&numstat);
    let mut out = vec![];
    let mut fields = status.split('\0');
    while let Some(entry) = fields.next() {
        if entry.len() < 4 {
            continue;
        }
        let code = &entry[..2];
        let path = entry[3..].to_string();
        // A rename is followed by its old name, which is not needed.
        if code.contains('R') || code.contains('C') {
            fields.next();
        }
        let kind = match code {
            "??" => "new",
            c if c.contains('A') => "new",
            c if c.contains('D') => "deleted",
            c if c.contains('R') => "renamed",
            _ => "changed",
        };
        let (mut added, mut removed) = counts
            .iter()
            .find(|(p, _, _)| *p == path)
            .map(|(_, a, r)| (*a, *r))
            .unwrap_or((0, 0));
        if code == "??" {
            added = count_lines(&project.join(&path));
            removed = 0;
        }
        out.push(Change {
            path,
            added,
            removed,
            kind: kind.to_string(),
        });
    }
    Some(out)
}

/// `git diff --numstat -z` → (path, added, removed). A rename is
/// "a\tr\t" followed by the old and the new name as separate fields.
fn parse_numstat_z(text: &str) -> Vec<(String, u32, u32)> {
    let mut out = vec![];
    let mut fields = text.split('\0');
    while let Some(f) = fields.next() {
        let mut parts = f.splitn(3, '\t');
        let (Some(a), Some(r), Some(p)) = (parts.next(), parts.next(), parts.next()) else {
            continue;
        };
        let path = if p.is_empty() {
            fields.next();
            fields.next().unwrap_or_default().to_string()
        } else {
            p.to_string()
        };
        out.push((path, a.parse().unwrap_or(0), r.parse().unwrap_or(0)));
    }
    out
}

/// The change to one file as diff lines (' ', '-', '+'), against the last
/// commit; a new file shows all its lines as added.
/// Commits not pushed to the branch's upstream; None without one.
pub fn unpushed(project: &Path) -> Option<usize> {
    git(project, &["rev-list", "--count", "@{upstream}..HEAD"])?
        .trim()
        .parse()
        .ok()
}

/// The newest tag, e.g. "v0.16".
pub fn newest_tag(project: &Path) -> Option<String> {
    let t = git(project, &["describe", "--tags", "--abbrev=0"])?;
    let t = t.trim();
    (!t.is_empty()).then(|| t.to_string())
}

pub fn diff(project: &Path, path: &str) -> Vec<String> {
    let d = git(project, &["diff", "HEAD", "--", path]).unwrap_or_default();
    if d.trim().is_empty() {
        use std::io::BufRead;
        return std::fs::File::open(project.join(path))
            .map(|f| {
                std::io::BufReader::new(f)
                    .lines()
                    .map_while(Result::ok)
                    .take(2000)
                    .map(|l| format!("+{l}"))
                    .collect()
            })
            .unwrap_or_default();
    }
    d.lines()
        .skip_while(|l| !l.starts_with("@@"))
        .take(4000)
        .map(str::to_string)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn changes_in_a_real_repository() {
        let dir = std::env::temp_dir().join(format!("station-git-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let run = |args: &[&str]| {
            assert!(
                crate::flatpak::host_cmd("git")
                    .arg("-C")
                    .arg(&dir)
                    .args(args)
                    .output()
                    .unwrap()
                    .status
                    .success(),
                "{args:?}"
            );
        };
        run(&["init", "-q", "-b", "main"]);
        run(&[
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@t",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "start",
        ]);
        std::fs::write(dir.join("a.txt"), "one\ntwo\n").unwrap();
        run(&["add", "a.txt"]);
        run(&[
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@t",
            "commit",
            "-q",
            "-m",
            "a",
        ]);
        std::fs::write(dir.join("a.txt"), "one\n2\n").unwrap();
        std::fs::write(dir.join("b.txt"), "x\ny\nz\n").unwrap();
        assert_eq!(branch(&dir).as_deref(), Some("main"));
        let c = changes(&dir).unwrap();
        let a = c.iter().find(|c| c.path == "a.txt").unwrap();
        assert_eq!((a.added, a.removed, a.kind.as_str()), (1, 1, "changed"));
        let b = c.iter().find(|c| c.path == "b.txt").unwrap();
        assert_eq!((b.added, b.kind.as_str()), (3, "new"));
        let d = diff(&dir, "a.txt");
        assert!(
            d.contains(&"-two".to_string()) && d.contains(&"+2".to_string()),
            "{d:?}"
        );
        assert_eq!(diff(&dir, "b.txt"), ["+x", "+y", "+z"]);
        assert!(changes(&std::env::temp_dir().join("not-a-repo-xyz")).is_none());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn odd_file_names_and_big_files() {
        let dir = std::env::temp_dir().join(format!("station-gitz-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let run = |args: &[&str]| {
            assert!(
                crate::flatpak::host_cmd("git")
                    .arg("-C")
                    .arg(&dir)
                    .args(args)
                    .output()
                    .unwrap()
                    .status
                    .success()
            )
        };
        run(&["init", "-q"]);
        std::fs::write(dir.join("café.txt"), "a\n").unwrap();
        run(&["add", "."]);
        run(&[
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@t",
            "commit",
            "-qm",
            "x",
        ]);
        std::fs::write(dir.join("café.txt"), "a\nb\n").unwrap();
        std::fs::write(dir.join("arrow -> name.txt"), "1\n2\n3").unwrap();
        std::fs::write(
            dir.join("big.log"),
            vec![b'\n'; (COUNT_LIMIT + 10) as usize],
        )
        .unwrap();
        let mut c = changes(&dir).unwrap();
        c.sort_by(|a, b| a.path.cmp(&b.path));
        let got: Vec<(&str, u32, u32)> = c
            .iter()
            .map(|c| (c.path.as_str(), c.added, c.removed))
            .collect();
        assert_eq!(
            got,
            [
                ("arrow -> name.txt", 3, 0),
                ("big.log", 0, 0),
                ("café.txt", 1, 0)
            ]
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
