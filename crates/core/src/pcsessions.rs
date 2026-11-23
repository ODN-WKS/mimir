//! "Running on your PC": every Claude Code session on this PC, wherever it
//! was started (a terminal, the Claude app, a scheduled run, Mímir), for the
//! owner's phones. The list is cheap to rebuild: transcripts are looked at
//! only by their size and time (`sessions` remembers what it read).

use crate::sessions;
use mimir_proto::{PcSession, PcSessionState};
use std::collections::HashSet;
use std::path::Path;

/// How many of the newest sessions are listed (running ones come on top).
pub const LISTED: usize = 30;

/// A Mímir task, as far as the list needs to know.
#[derive(Debug, Clone)]
pub struct TaskRef {
    pub task: String,
    pub session: Option<String>,
    pub title: String,
    /// Mímir's own Claude is working on it.
    pub busy: bool,
    /// Going on in another app right now.
    pub elsewhere: bool,
    /// A Whisper task: never listed.
    pub whisper: bool,
}

/// The sessions to list, newest first. `home` is Claude Code's folder;
/// `running` are the ids a Claude Code process was started with; `hidden`
/// are ids the owner removed.
pub fn list(
    home: &Path,
    running: &HashSet<String>,
    hidden: &HashSet<String>,
    tasks: &[TaskRef],
    now: i64,
) -> Vec<PcSession> {
    let secret: HashSet<&str> = tasks
        .iter()
        .filter(|t| t.whisper)
        .filter_map(|t| t.session.as_deref())
        .collect();
    let mut keep = running.clone();
    keep.extend(
        tasks
            .iter()
            .filter(|t| t.busy || t.elsewhere)
            .filter_map(|t| t.session.clone()),
    );
    let mut out: Vec<PcSession> = vec![];
    for o in sessions::recent_keeping(&home.join("projects"), LISTED, &keep) {
        let id = o.session.id.clone();
        if hidden.contains(&id) || secret.contains(id.as_str()) {
            continue;
        }
        let task = tasks.iter().find(|t| t.session.as_deref() == Some(&id));
        let title = match task {
            Some(t) if !t.title.trim().is_empty() => t.title.clone(),
            _ => o
                .title
                .clone()
                .unwrap_or_else(|| crate::runner::title_from(&o.session.first)),
        };
        let moved = o.session.modified_unix;
        let state = if task.is_some_and(|t| t.busy || t.elsewhere)
            || now - moved < crate::task::LIVE_SECS
        {
            PcSessionState::Working
        } else if running.contains(&id) {
            PcSessionState::Idle
        } else {
            PcSessionState::Ended
        };
        out.push(PcSession {
            id,
            title: short(&title),
            project: crate::paths::project_name(&o.project.display().to_string()),
            here: task.is_some(),
            task: task.map(|t| t.task.clone()),
            state,
            last: moved,
        });
    }
    out
}

/// What opening a session did.
#[derive(Debug, Clone, PartialEq)]
pub struct Opened {
    /// The Mímir task for it.
    pub task: String,
    /// The session is working in another app: the task follows it live.
    pub follow: bool,
}

/// What the task of a session working elsewhere says.
pub const FOLLOW_NOTE: &str = "Running in another app; you can follow it here.";

/// Opens a session as a task: one that is idle or ended continues (as the
/// PC window does), one working elsewhere is followed live. A session that
/// already is a task gives that task. `None` for a Whisper's session.
pub fn open(
    runner: &mut crate::runner::Runner,
    home: &Path,
    session: &str,
    running: &HashSet<String>,
    now: i64,
) -> Result<Opened, String> {
    let gone = || "That session could not be opened. It may have been removed.".to_string();
    if let Some(l) = runner
        .tasks
        .iter()
        .find(|l| l.task.session_id.as_deref() == Some(session))
    {
        if l.task.incognito {
            return Err(gone());
        }
        return Ok(Opened {
            task: l.task.id.clone(),
            follow: false,
        });
    }
    let keep: HashSet<String> = [session.to_string()].into();
    let found = sessions::recent_keeping(&home.join("projects"), 0, &keep)
        .into_iter()
        .find(|o| o.session.id == session)
        .ok_or_else(gone)?;
    let working =
        running.contains(session) || now - found.session.modified_unix < crate::task::LIVE_SECS;
    let id = runner
        .open_past(&found.project, &found.session)
        .map_err(|_| gone())?;
    if working {
        runner.set_elsewhere(&id, true);
        runner.note(&id, FOLLOW_NOTE);
    }
    Ok(Opened {
        task: id,
        follow: working,
    })
}

/// A title short enough for a phone row.
fn short(t: &str) -> String {
    let t = t.split_whitespace().collect::<Vec<_>>().join(" ");
    if t.chars().count() <= 80 {
        return t;
    }
    let cut: String = t.chars().take(79).collect();
    format!("{}…", cut.trim_end())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    /// A fake Claude folder with one project folder and its sessions.
    struct Home {
        /// Claude Code's folder (its `projects` folder holds the sessions).
        root: PathBuf,
        base: PathBuf,
        project: PathBuf,
    }

    fn home(tag: &str) -> Home {
        // Not under /tmp: sessions there are left out of the list.
        let base = std::env::var_os("HOME")
            .map(PathBuf::from)
            .unwrap_or_else(std::env::temp_dir)
            .join(".cache")
            .join(format!("mimir-pcs-test-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&base);
        let project = base.join("Recipe box");
        fs::create_dir_all(&project).unwrap();
        let root = base.join("claude");
        fs::create_dir_all(root.join("projects").join("one")).unwrap();
        Home {
            root,
            base,
            project,
        }
    }

    fn session(h: &Home, id: &str, ask: &str, title: Option<&str>, age: i64) {
        let cwd = h.project.display().to_string();
        let mut text = format!(
            "{{\"type\":\"user\",\"cwd\":{cwd:?},\"message\":{{\"role\":\"user\",\"content\":{ask:?}}}}}\n"
        );
        if let Some(t) = title {
            text += &format!("{{\"type\":\"ai-title\",\"aiTitle\":{t:?}}}\n");
        }
        let path = h
            .root
            .join("projects")
            .join("one")
            .join(format!("{id}.jsonl"));
        fs::write(&path, text).unwrap();
        let when = std::time::SystemTime::now() - std::time::Duration::from_secs(age as u64);
        fs::File::options()
            .write(true)
            .open(&path)
            .unwrap()
            .set_modified(when)
            .unwrap();
    }

    fn now() -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs() as i64
    }

    fn set(ids: &[&str]) -> HashSet<String> {
        ids.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn newest_first_with_titles_and_states() {
        let h = home("order");
        session(
            &h,
            "a",
            "Fix the shopping list",
            Some("Shopping list fix"),
            7200,
        );
        session(&h, "b", "Add a pie recipe", None, 3600);
        session(&h, "c", "Rename the folders", Some("Rename folders"), 10);
        let l = list(&h.root, &set(&["b"]), &set(&[]), &[], now());
        let ids: Vec<&str> = l.iter().map(|s| s.id.as_str()).collect();
        assert_eq!(ids, ["c", "b", "a"]);
        assert_eq!(l[0].title, "Rename folders");
        assert_eq!(l[1].title, "Add a pie recipe");
        assert_eq!(l[0].project, "Recipe box");
        assert_eq!(l[0].state, PcSessionState::Working);
        assert_eq!(l[1].state, PcSessionState::Idle);
        assert_eq!(l[2].state, PcSessionState::Ended);
        assert!(l.iter().all(|s| !s.here && s.task.is_none()));
    }

    #[test]
    fn only_the_newest_thirty_and_every_running_one() {
        let h = home("limit");
        for i in 0..35 {
            session(
                &h,
                &format!("s{i:02}"),
                &format!("Ask {i}"),
                None,
                1000 + i * 10,
            );
        }
        // The oldest one is running.
        let l = list(&h.root, &set(&["s34"]), &set(&[]), &[], now());
        assert_eq!(l.len(), 31);
        assert_eq!(l[0].id, "s00");
        assert_eq!(l[30].id, "s34");
        let l = list(&h.root, &set(&[]), &set(&[]), &[], now());
        assert_eq!(l.len(), 30);
    }

    #[test]
    fn a_mimir_task_appears_once_linked_by_session() {
        let h = home("dedupe");
        session(&h, "a", "Plan the beds", None, 600);
        session(&h, "b", "Other work", None, 700);
        let tasks = vec![
            TaskRef {
                task: "t-1".into(),
                session: Some("a".into()),
                title: "Garden beds".into(),
                busy: true,
                elsewhere: false,
                whisper: false,
            },
            TaskRef {
                task: "t-2".into(),
                session: Some("zz".into()),
                title: "No file yet".into(),
                busy: false,
                elsewhere: false,
                whisper: false,
            },
        ];
        let l = list(&h.root, &set(&[]), &set(&[]), &tasks, now());
        assert_eq!(l.len(), 2);
        let a = l.iter().find(|s| s.id == "a").unwrap();
        assert!(a.here);
        assert_eq!(a.task.as_deref(), Some("t-1"));
        assert_eq!(a.title, "Garden beds");
        assert_eq!(a.state, PcSessionState::Working);
        assert!(!l.iter().find(|s| s.id == "b").unwrap().here);
    }

    #[test]
    fn whisper_and_removed_sessions_are_never_listed() {
        let h = home("whisper");
        session(&h, "a", "Private thing", None, 60);
        session(&h, "b", "Removed thing", None, 60);
        session(&h, "c", "Fine thing", None, 60);
        let tasks = vec![TaskRef {
            task: "t-1".into(),
            session: Some("a".into()),
            title: "Whisper".into(),
            busy: false,
            elsewhere: false,
            whisper: true,
        }];
        let l = list(&h.root, &set(&[]), &set(&["b"]), &tasks, now());
        let ids: Vec<&str> = l.iter().map(|s| s.id.as_str()).collect();
        assert_eq!(ids, ["c"]);
    }

    fn runner(h: &Home) -> crate::runner::Runner {
        let exe = h.base.join("claude-stand-in");
        crate::runner::Runner::new(
            crate::runner::Config {
                claude: exe.clone(),
                hook_exe: exe,
                socket: h.base.join("run/approvals.sock"),
                store_dir: h.base.join("tasks"),
                env: vec![],
            },
            std::sync::Arc::new(|| {}),
        )
        .unwrap()
    }

    #[test]
    fn opening_an_idle_session_makes_a_task_that_continues_it() {
        let h = home("open");
        session(&h, "a", "Plan the beds", Some("Garden beds"), 7200);
        let mut r = runner(&h);
        let o = open(&mut r, &h.root, "a", &set(&[]), now()).unwrap();
        assert!(!o.follow);
        let t = &r.tasks.iter().find(|l| l.task.id == o.task).unwrap().task;
        assert_eq!(t.session_id.as_deref(), Some("a"));
        assert_eq!(t.title, "Garden beds");
        assert!(!t.live_elsewhere);
        // Once a task, it is listed once, as that task, and opening again
        // gives the same task.
        let tasks: Vec<TaskRef> = r
            .tasks
            .iter()
            .map(|l| TaskRef {
                task: l.task.id.clone(),
                session: l.task.session_id.clone(),
                title: l.task.title.clone(),
                busy: false,
                elsewhere: l.task.live_elsewhere,
                whisper: l.task.incognito,
            })
            .collect();
        let l = list(&h.root, &set(&[]), &set(&[]), &tasks, now());
        assert_eq!(l.len(), 1);
        assert_eq!(l[0].task.as_deref(), Some(o.task.as_str()));
        let again = open(&mut r, &h.root, "a", &set(&[]), now()).unwrap();
        assert_eq!(again.task, o.task);
        assert_eq!(r.tasks.len(), 1);
    }

    #[test]
    fn opening_a_session_working_elsewhere_follows_it_and_says_so() {
        let h = home("follow");
        session(&h, "w", "Sort the photos", None, 5);
        let mut r = runner(&h);
        let o = open(&mut r, &h.root, "w", &set(&["w"]), now()).unwrap();
        assert!(o.follow);
        let t = &r.tasks.iter().find(|l| l.task.id == o.task).unwrap().task;
        assert!(t.live_elsewhere);
        assert!(format!("{:?}", t.entries).contains(FOLLOW_NOTE));
    }

    #[test]
    fn opening_an_unknown_session_says_what_to_do() {
        let h = home("unknown");
        let mut r = runner(&h);
        let e = open(&mut r, &h.root, "nope", &set(&[]), now()).unwrap_err();
        assert!(e.contains("could not be opened"));
        assert!(r.tasks.is_empty());
    }

    #[test]
    fn guests_get_no_such_calls_and_no_such_events() {
        use mimir_proto::{Call, Event};
        assert!(!crate::people::other_may(&Call::PcSessions));
        assert!(!crate::people::other_may(&Call::OpenPcSession {
            session: "a".into()
        }));
        assert_eq!(
            crate::people::empty_for_others(&Call::PcSessions),
            Some(serde_json::json!([]))
        );
        let e = Event::PcSessions { sessions: vec![] };
        assert!(!e.for_other_people());
        assert_eq!(e.feature(), Some("pc_sessions"));
    }

    #[test]
    fn long_titles_are_cut() {
        assert_eq!(short("  a   b "), "a b");
        let long = "word ".repeat(40);
        assert!(short(&long).chars().count() <= 80);
        assert!(short(&long).ends_with('…'));
    }
}
