//! Placement notes: when a chat started with no project is filed under its
//! best guess, a short note says why and that it isn't confirmed yet. Notes
//! live in Mímir's own data folder, one folder per project (never
//! in the project, where they could be committed and pushed), and hold only
//! the task's title, not what was asked. A note deletes itself once the
//! owner confirms, the task moves or is archived, or the work clearly
//! belongs (it commits there).

use std::path::{Path, PathBuf};

/// "2026-11-19" for seconds since 1970 (UTC).
pub fn date(unix: i64) -> String {
    // Howard Hinnant's civil-from-days.
    let z = unix.div_euclid(86_400) + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}")
}

/// Mímir's folder of placement notes.
pub fn root() -> PathBuf {
    crate::paths::data_dir().join("placement")
}

/// Where a task's placement note about `project` goes.
pub fn note_path(root: &Path, project: &Path, task: &str, unix: i64) -> PathBuf {
    let safe: String = task
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    root.join(crate::sessions::folder_name(project))
        .join(format!("{}-{safe}.md", date(unix)))
}

/// Writes the note; its path.
pub fn write(
    project: &Path,
    task: &str,
    title: &str,
    why: &str,
    unix: i64,
) -> std::io::Result<String> {
    write_in(&root(), project, task, title, why, unix)
}

fn write_in(
    root: &Path,
    project: &Path,
    task: &str,
    title: &str,
    why: &str,
    unix: i64,
) -> std::io::Result<String> {
    let path = note_path(root, project, task, unix);
    if let Some(d) = path.parent() {
        std::fs::create_dir_all(d)?;
    }
    let text = format!(
        "# Placement: {title}\n\n\
         Mímir filed this chat under {} on {} as its best guess, not yet \
         confirmed.\n\n\
         - Why: {why}\n\
         - Task: {task}\n\n\
         This note deletes itself when the owner confirms the place, moves or archives \
         the task, or the task commits to this project.\n",
        project.display(),
        date(unix),
    );
    std::fs::write(&path, text)?;
    Ok(path.display().to_string())
}

/// Why a note says the chat was filed where it is.
pub fn why(note: &str) -> Option<String> {
    std::fs::read_to_string(note)
        .ok()?
        .lines()
        .find_map(|l| l.strip_prefix("- Why: ").map(str::to_string))
}

/// Deletes a note, and its project's folder of notes if that leaves it
/// empty. Only a note inside Mímir's notes folder is ever removed.
pub fn remove(note: &str) {
    remove_in(&root(), note);
}

fn remove_in(root: &Path, note: &str) {
    let p = Path::new(note);
    if !p.starts_with(root) || p.extension().is_none_or(|x| x != "md") {
        return;
    }
    let _ = std::fs::remove_file(p);
    if let Some(dir) = p.parent()
        && dir != root
    {
        // Fails, harmlessly, while other notes are in it.
        let _ = std::fs::remove_dir(dir);
    }
}

/// The task committed to its project (a git commit ran in it).
pub fn committed(t: &crate::task::Task) -> bool {
    t.entries.iter().any(|e| match e {
        crate::task::Entry::Step(s) => {
            s.ok == Some(true)
                && s.tool == "Bash"
                && (s.detail.input.contains("git commit") || s.what.contains("git commit"))
        }
        _ => false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates_read_as_the_calendar_does() {
        assert_eq!(date(0), "1970-01-01");
        assert_eq!(date(1_795_000_000), "2026-11-18");
        assert_eq!(date(951_782_400), "2000-02-29");
    }

    #[test]
    fn a_task_that_committed_belongs_there() {
        let step = |input: &str, ok| {
            crate::task::Entry::Step(mimir_proto::Step {
                tool_use_id: "s".into(),
                tool: "Bash".into(),
                what: format!("Ran a command: {input}"),
                place: mimir_proto::Place::ThisPc,
                started_ms: 0,
                took_ms: None,
                ok: Some(ok),
                refused: false,
                stopped: false,
                detail: mimir_proto::StepDetail {
                    input: input.into(),
                    ..Default::default()
                },
            })
        };
        let mut t = crate::task::Task::new("t".into(), "T".into(), "/p".into(), 0);
        t.entries.push(step("git status", true));
        t.entries.push(step("git commit -m x", false));
        assert!(!committed(&t), "a failed commit isn't one");
        t.entries.push(step("git add -A && git commit -m x", true));
        assert!(committed(&t));
    }

    #[test]
    fn a_note_is_kept_by_the_app_and_leaves_nothing_behind() {
        let root = std::env::temp_dir().join(format!("placement-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let project = root.join("Tune-Sorter");
        std::fs::create_dir_all(&project).unwrap();
        let notes = root.join("data/placement");
        let note = write_in(
            &notes,
            &project,
            "17-1-2",
            "Fix calls",
            "It mentions “calls”.",
            0,
        )
        .unwrap();
        assert!(note.starts_with(&notes.display().to_string()));
        assert!(note.ends_with("/1970-01-01-17-1-2.md"));
        // Nothing in the project, so nothing can be committed or pushed.
        assert_eq!(std::fs::read_dir(&project).unwrap().count(), 0);
        let text = std::fs::read_to_string(&note).unwrap();
        assert!(text.contains("not yet confirmed") && text.contains("Fix calls"));
        assert_eq!(why(&note).as_deref(), Some("It mentions “calls”."));
        remove_in(&notes, &note);
        assert_eq!(std::fs::read_dir(&notes).unwrap().count(), 0);
        // Never anything outside the notes folder.
        std::fs::write(project.join("x.md"), "x").unwrap();
        remove_in(&notes, &project.join("x.md").display().to_string());
        assert!(project.join("x.md").exists());
        let _ = std::fs::remove_dir_all(&root);
    }
}
