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
    let mut skip: HashSet<String> = hidden.clone();
    skip.extend(
        tasks
            .iter()
            .filter(|t| t.whisper)
            .filter_map(|t| t.session.clone()),
    );
    let mut keep = running.clone();
    keep.extend(
        tasks
            .iter()
            .filter(|t| t.busy)
            .filter_map(|t| t.session.clone()),
    );
    let mut out: Vec<PcSession> = vec![];
    for o in sessions::recent_keeping(&home.join("projects"), LISTED, &keep, &skip) {
        let id = o.session.id.clone();
        let task = tasks.iter().find(|t| t.session.as_deref() == Some(&id));
        let title = match task {
            Some(t) if !t.title.trim().is_empty() => t.title.clone(),
            _ => o
                .title
                .clone()
                .unwrap_or_else(|| crate::runner::title_from(&o.session.first)),
        };
        let app = sessions::started_in_app(&id) || sessions::from_remote(&o.session.path);
        let moved = o.session.modified_unix;
        let state = state_of(
            task.is_some_and(|t| t.busy),
            running.contains(&id),
            moved,
            now,
        );
        out.push(PcSession {
            id,
            title: short(&title),
            project: crate::paths::project_name(&o.project.display().to_string()),
            // Mímir's own Claude runs it only while nothing else has it open.
            here: task.is_some_and(|t| !t.elsewhere),
            app,
            task: task.map(|t| t.task.clone()),
            state,
            last: moved,
        });
    }
    out
}

/// A session written to this recently, with a Claude process on it, is at
/// work.
pub const WRITE_SECS: i64 = 120;

/// One rule for every place: working = Mímir's own Claude is on it, or a
/// Claude process has it and it was written to just now; idle = a process
/// has it and it is quiet; ended = nothing has it.
pub fn state_of(busy: bool, process: bool, modified: i64, now: i64) -> PcSessionState {
    if busy || (process && now - modified < WRITE_SECS) {
        PcSessionState::Working
    } else if process {
        PcSessionState::Idle
    } else {
        PcSessionState::Ended
    }
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

/// Opens a session as a task: one that is idle or ended becomes a normal
/// task that continues it (as the PC window does), one working elsewhere is
/// followed live. A session that already is a task gives that task, and
/// follows if the session is working elsewhere (so the phone never starts a
/// second Claude on it).
pub fn open(
    runner: &mut crate::runner::Runner,
    home: &Path,
    session: &str,
    running: &HashSet<String>,
    now: i64,
) -> Result<Opened, String> {
    let gone = || "That session could not be opened. It may have been removed.".to_string();
    let keep: HashSet<String> = [session.to_string()].into();
    let found = sessions::recent_keeping(&home.join("projects"), 0, &keep, &HashSet::new())
        .into_iter()
        .find(|o| o.session.id == session);
    let working_elsewhere = |modified: i64| {
        state_of(false, running.contains(session), modified, now) == PcSessionState::Working
    };
    if let Some(l) = runner
        .tasks
        .iter()
        .find(|l| l.task.session_id.as_deref() == Some(session))
    {
        if l.task.incognito {
            return Err(gone());
        }
        let id = l.task.id.clone();
        // Mímir's own Claude on it is no "elsewhere".
        let own = l.running() || l.busy();
        let modified = found.as_ref().map(|o| o.session.modified_unix).unwrap_or(0);
        let follow = !own && working_elsewhere(modified);
        if follow && runner.set_elsewhere(&id, true) {
            runner.note(&id, FOLLOW_NOTE);
        }
        return Ok(Opened { task: id, follow });
    }
    let found = found.ok_or_else(gone)?;
    let follow = working_elsewhere(found.session.modified_unix);
    let id = runner
        .open_past(&found.project, &found.session)
        .map_err(|_| gone())?;
    if follow {
        runner.set_elsewhere(&id, true);
        runner.note(&id, FOLLOW_NOTE);
    }
    Ok(Opened { task: id, follow })
}

/// Stop on a task that follows a session running elsewhere: that Claude
/// Code ends (see `sessions::stop_session`) and the task reads Stopped.
pub fn stop_elsewhere(runner: &mut crate::runner::Runner, task: &str) -> Result<(), String> {
    let session = runner
        .tasks
        .iter()
        .find(|l| l.task.id == task)
        .and_then(|l| l.task.session_id.clone())
        .ok_or("Couldn't stop it here. Stop it where it runs.")?;
    let app = sessions::stop_session(&session)?;
    let place = if app { "the Claude app" } else { "a terminal" };
    runner.stopped_elsewhere(
        task,
        &format!("You stopped this session. It was running in {place}."),
    );
    Ok(())
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
        fs::create_dir_all(root.join("projects").join(sessions::folder_name(&project))).unwrap();
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
            .join(sessions::folder_name(&h.project))
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
        let l = list(&h.root, &set(&["b", "c"]), &set(&[]), &[], now());
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
    fn a_session_from_the_claude_app_says_so() {
        let h = home("app");
        // Claude Code gives Remote Control sessions a version 5 id.
        let app = "5f1c0a52-2222-5d2e-9a10-0123456789ab";
        session(&h, app, "Plan the beds", None, 20);
        session(
            &h,
            "5f1c0a52-3333-4d2e-9a10-0123456789ab",
            "Fix a typo",
            None,
            30,
        );
        let l = list(&h.root, &set(&[]), &set(&[]), &[], now());
        assert!(l.iter().find(|s| s.id == app).unwrap().app);
        assert!(l.iter().filter(|s| s.id != app).all(|s| !s.app));
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

    #[test]
    fn working_needs_a_process_and_a_fresh_write() {
        let h = home("rule");
        session(&h, "fresh", "Ask one", None, 10);
        session(&h, "quiet", "Ask two", None, 600);
        let l = list(&h.root, &set(&["quiet"]), &set(&[]), &[], now());
        let st = |id: &str| l.iter().find(|s| s.id == id).unwrap().state;
        // Written just now, but no process on it: not at work.
        assert_eq!(st("fresh"), PcSessionState::Ended);
        // A process, quiet for ten minutes: open but idle.
        assert_eq!(st("quiet"), PcSessionState::Idle);
        let l = list(&h.root, &set(&["fresh"]), &set(&[]), &[], now());
        assert_eq!(
            l.iter().find(|s| s.id == "fresh").unwrap().state,
            PcSessionState::Working
        );
        // A task that says "elsewhere" is no reason to call it working.
        let t = TaskRef {
            task: "t".into(),
            session: Some("quiet".into()),
            title: "x".into(),
            busy: false,
            elsewhere: true,
            whisper: false,
        };
        let l = list(&h.root, &set(&[]), &set(&[]), &[t], now());
        assert_eq!(
            l.iter().find(|s| s.id == "quiet").unwrap().state,
            PcSessionState::Ended
        );
    }

    #[test]
    fn the_limit_counts_after_hidden_and_whisper_are_left_out() {
        let h = home("skip");
        for i in 0..32 {
            session(
                &h,
                &format!("s{i:02}"),
                &format!("Ask {i}"),
                None,
                1000 + i * 10,
            );
        }
        let hidden = set(&["s00", "s01"]);
        let l = list(&h.root, &set(&[]), &hidden, &[], now());
        assert_eq!(l.len(), 30);
        assert_eq!(l[0].id, "s02");
    }

    #[test]
    fn an_idle_session_opens_as_a_normal_task_and_a_task_working_elsewhere_follows() {
        let h = home("idle");
        session(&h, "i", "Sort the notes", None, 30);
        let mut r = runner(&h);
        // A process has it but it is quiet enough? No: written 30 s ago is
        // fresh, so use an old one for idle.
        session(&h, "q", "Old notes", None, 900);
        let o = open(&mut r, &h.root, "q", &set(&["q"]), now()).unwrap();
        assert!(!o.follow);
        let t = &r.tasks.iter().find(|l| l.task.id == o.task).unwrap().task;
        assert!(!t.live_elsewhere);
        // The same session is later written to by another app.
        session(&h, "q", "Old notes", None, 5);
        let again = open(&mut r, &h.root, "q", &set(&["q"]), now()).unwrap();
        assert_eq!(again.task, o.task);
        assert!(again.follow);
        let t = &r.tasks.iter().find(|l| l.task.id == o.task).unwrap().task;
        assert!(t.live_elsewhere);
        // No second note when asked again.
        let notes = |t: &crate::task::Task| format!("{:?}", t.entries).matches(FOLLOW_NOTE).count();
        let _ = open(&mut r, &h.root, "q", &set(&["q"]), now()).unwrap();
        let t = &r.tasks.iter().find(|l| l.task.id == o.task).unwrap().task;
        assert_eq!(notes(t), 1);
    }

    #[test]
    fn following_from_the_current_size_keeps_the_note() {
        let h = home("seed");
        session(&h, "w", "Sort the photos", None, 5);
        // The runner finds transcripts through the Claude folder (each test
        // runs in its own process).
        unsafe { std::env::set_var("CLAUDE_CONFIG_DIR", &h.root) };
        let mut r = runner(&h);
        let o = open(&mut r, &h.root, "w", &set(&["w"]), now()).unwrap();
        let size = std::fs::metadata(r.transcript(&o.task).unwrap())
            .unwrap()
            .len();
        r.follow_session(&o.task, size).unwrap();
        let t = &r.tasks.iter().find(|l| l.task.id == o.task).unwrap().task;
        assert!(format!("{:?}", t.entries).contains(FOLLOW_NOTE));
    }

    #[test]
    fn a_stopped_followed_task_stays_stopped_when_its_transcript_grows() {
        let h = home("stopheld");
        session(&h, "s", "Sort the photos", None, 5);
        unsafe { std::env::set_var("CLAUDE_CONFIG_DIR", &h.root) };
        let mut r = runner(&h);
        let o = open(&mut r, &h.root, "s", &set(&["s"]), now()).unwrap();
        let path = r.transcript(&o.task).unwrap();
        let size = std::fs::metadata(&path).unwrap().len();
        r.stopped_elsewhere(&o.task, "You stopped this session.");
        // The session wrote on after the stop.
        let mut text = std::fs::read_to_string(&path).unwrap();
        text += "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"More.\"}]}}\n";
        std::fs::write(&path, text).unwrap();
        let at = r.follow_session(&o.task, size).unwrap();
        assert!(at > size);
        assert!(r.held_stopped(&o.task));
        let t = &r.tasks.iter().find(|l| l.task.id == o.task).unwrap().task;
        assert_eq!(t.status, crate::task::Status::Stopped);
    }

    /// A stand-in Claude Code: a `sleep` whose program name is "claude",
    /// running in `cwd`. Killed when dropped.
    struct Stand(std::process::Child);
    impl Stand {
        fn start(cwd: &Path) -> Stand {
            let c = std::process::Command::new("bash")
                .args(["-c", "exec -a /opt/x/claude sleep 60"])
                .current_dir(cwd)
                .spawn()
                .unwrap();
            // Until bash has become the stand-in.
            for _ in 0..500 {
                let line = std::fs::read(format!("/proc/{}/cmdline", c.id())).unwrap_or_default();
                if line.starts_with(b"/opt/x/claude") {
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
            Stand(c)
        }
        fn stop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    impl Drop for Stand {
        fn drop(&mut self) {
            self.stop();
        }
    }

    /// The scan, as `running_scan` does it, for the stand-ins only.
    fn scan(h: &Home, pids: &[u32]) -> sessions::Running {
        let procs: Vec<sessions::Proc> = sessions::local_procs(now())
            .into_iter()
            .filter(|p| pids.contains(&p.pid))
            .collect();
        sessions::running_from(&procs, &h.root)
    }

    fn state_of_id(h: &Home, r: &sessions::Running, id: &str) -> PcSessionState {
        let l = list(&h.root, &r.ids, &set(&[]), &[], now());
        l.iter().find(|s| s.id == id).unwrap().state
    }

    #[test]
    fn a_plain_claude_in_a_terminal_is_found_by_its_folder() {
        let h = home("proc");
        let mut p = Stand::start(&h.project);
        session(&h, "t1", "Sort the notes", None, 0);
        let r = scan(&h, &[p.0.id()]);
        assert!(r.ids.contains("t1"), "{r:?}");
        assert!(r.app.is_empty());
        // Writing now: at work.
        assert_eq!(state_of_id(&h, &r, "t1"), PcSessionState::Working);
        // Quiet: open but idle.
        session(&h, "t1", "Sort the notes", None, 600);
        assert_eq!(state_of_id(&h, &r, "t1"), PcSessionState::Idle);
        // The process is gone: ended.
        p.stop();
        let r = scan(&h, &[p.0.id()]);
        assert!(r.ids.is_empty());
        assert_eq!(state_of_id(&h, &r, "t1"), PcSessionState::Ended);
    }

    #[test]
    fn two_in_one_folder_take_the_two_newest_sessions() {
        let h = home("two");
        let a = Stand::start(&h.project);
        let b = Stand::start(&h.project);
        session(&h, "old", "Ancient", None, 0);
        session(&h, "n1", "First", None, 0);
        session(&h, "n2", "Second", None, 0);
        // Older than both processes: never theirs.
        session(&h, "stale", "Stale", None, 3600);
        let r = scan(&h, &[a.0.id(), b.0.id()]);
        assert_eq!(r.ids.len(), 2, "{r:?}");
        assert!(!r.ids.contains("stale"));
    }

    #[test]
    fn the_claude_app_is_told_by_its_command_line() {
        let h = home("app-line");
        session(&h, "u1", "From the app", None, 0);
        session(&h, "u2", "From a terminal", None, 0);
        std::fs::create_dir_all(h.base.join("other")).unwrap();
        let started = now() - 5;
        let app = sessions::Proc {
            pid: 4_000_001,
            started,
            args: [
                "/home/x/.local/share/claude/versions/2.1.329",
                "--print",
                "--sdk-url",
                "https://example.invalid/v1/code/sessions/cse_01Abc",
                "--session-id",
                "cse_01Abc",
            ]
            .map(String::from)
            .to_vec(),
            cwd: Some(h.project.clone()),
        };
        let r = sessions::running_from(&[app], &h.root);
        // Newest transcript in its folder (both are new: one of them).
        assert_eq!(r.ids.len(), 1);
        assert_eq!(r.app, r.ids);
        // A plain terminal one in another folder is not the app's.
        let term = sessions::Proc {
            pid: 4_000_002,
            started,
            args: vec!["/home/x/.local/bin/claude".into()],
            cwd: Some(h.project.clone()),
        };
        let r = sessions::running_from(&[term], &h.root);
        assert_eq!(r.ids.len(), 1);
        assert!(r.app.is_empty());
        // A session id on the line is used as it is.
        let id = "5f1c0a52-3333-4d2e-9a10-0123456789ab";
        let with_id = sessions::Proc {
            pid: 4_000_003,
            started,
            args: ["claude", "--resume", id].map(String::from).to_vec(),
            cwd: None,
        };
        let r = sessions::running_from(&[with_id], &h.root);
        assert!(r.ids.contains(id));
        // The phone's listener and look-alike programs are not sessions.
        let listener = sessions::Proc {
            pid: 4_000_004,
            started,
            args: ["/home/x/.local/bin/claude", "remote-control"]
                .map(String::from)
                .to_vec(),
            cwd: Some(h.project.clone()),
        };
        let other = sessions::Proc {
            pid: 4_000_005,
            started,
            args: vec!["/home/x/.local/bin/claude-helper".into()],
            cwd: Some(h.project.clone()),
        };
        assert!(
            sessions::running_from(&[listener, other], &h.root)
                .ids
                .is_empty()
        );
    }

    /// Read-only proof on this PC: prints the real list.
    /// `cargo test -p mimir-core real_home -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn real_home_list() {
        let running = sessions::running_scan();
        println!(
            "running: {} ids, {} app",
            running.ids.len(),
            running.app.len()
        );
        for p in sessions::local_procs(now())
            .iter()
            .filter(|p| p.args[0].contains("versions"))
        {
            println!(
                "proc {} started {}s ago cwd {:?}",
                p.pid,
                now() - p.started,
                p.cwd
            );
        }
        let l = list(
            &sessions::claude_home(),
            &running.ids,
            &HashSet::new(),
            &[],
            now(),
        );
        // Only the shape is printed, with titles cut to three words.
        for s in l.iter().take(12) {
            let words: Vec<&str> = s.title.split_whitespace().take(3).collect();
            println!(
                "{:?} | {} | {} | app={} | {}s ago",
                s.state,
                s.project,
                words.join(" "),
                s.app,
                now() - s.last
            );
        }
    }

    fn exited(s: &mut Stand) -> bool {
        for _ in 0..300 {
            if s.0.try_wait().unwrap().is_some() {
                return true;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        false
    }

    #[test]
    fn stop_ends_the_session_running_elsewhere_and_only_it() {
        let h = home("stop");
        unsafe { std::env::set_var("CLAUDE_CONFIG_DIR", &h.root) };
        let mut mine = Stand::start(&h.project);
        session(&h, "s1", "Sort the notes", None, 0);
        // Another Claude Code, in another folder.
        let other_dir = h.base.join("Garden planner");
        fs::create_dir_all(&other_dir).unwrap();
        let mut other = Stand::start(&other_dir);
        let mut r = runner(&h);
        let run = scan(&h, &[mine.0.id()]);
        assert!(run.ids.contains("s1"));
        let o = open(&mut r, &h.root, "s1", &run.ids, now()).unwrap();
        assert!(o.follow);
        stop_elsewhere(&mut r, &o.task).unwrap();
        assert!(exited(&mut mine), "the session's process is still there");
        assert!(other.0.try_wait().unwrap().is_none(), "another one was hit");
        let t = &r.tasks.iter().find(|l| l.task.id == o.task).unwrap().task;
        assert_eq!(t.status, crate::task::Status::Stopped);
        assert!(!t.live_elsewhere);
        let words = format!("{:?}", t.entries);
        assert!(words.contains("You stopped this session. It was running in a terminal."));
    }

    #[test]
    fn stop_with_no_process_says_why() {
        let h = home("stop-none");
        unsafe { std::env::set_var("CLAUDE_CONFIG_DIR", &h.root) };
        session(&h, "s2", "Old notes", None, 900);
        let mut r = runner(&h);
        let o = open(&mut r, &h.root, "s2", &set(&[]), now()).unwrap();
        r.set_elsewhere(&o.task, true);
        let e = stop_elsewhere(&mut r, &o.task).unwrap_err();
        assert_eq!(e, "Couldn't stop it here. Stop it where it runs.");
        let t = &r.tasks.iter().find(|l| l.task.id == o.task).unwrap().task;
        assert_ne!(t.status, crate::task::Status::Stopped);
        assert!(stop_elsewhere(&mut r, "no-such-task").is_err());
    }

    #[test]
    fn a_task_live_elsewhere_keeps_its_real_origin_in_the_list() {
        let h = home("origin");
        let app_id = "5f1c0a52-2222-5d2e-9a10-0123456789ab";
        session(&h, app_id, "From the app", None, 5);
        session(&h, "t9", "From a terminal", None, 5);
        let mut r = runner(&h);
        let run = set(&[app_id, "t9"]);
        for id in [app_id, "t9"] {
            assert!(open(&mut r, &h.root, id, &run, now()).unwrap().follow);
        }
        let refs = |r: &crate::runner::Runner| -> Vec<TaskRef> {
            r.tasks
                .iter()
                .map(|l| TaskRef {
                    task: l.task.id.clone(),
                    session: l.task.session_id.clone(),
                    title: l.task.title.clone(),
                    busy: false,
                    elsewhere: l.task.live_elsewhere,
                    whisper: false,
                })
                .collect()
        };
        let l = list(&h.root, &run, &set(&[]), &refs(&r), now());
        let a = l.iter().find(|s| s.id == app_id).unwrap();
        let t = l.iter().find(|s| s.id == "t9").unwrap();
        assert!(!a.here && a.app && a.task.is_some());
        assert!(!t.here && !t.app && t.task.is_some());
        // Once nothing else has it open it is the task's own again.
        for l in 0..r.tasks.len() {
            let id = r.tasks[l].task.id.clone();
            r.set_elsewhere(&id, false);
        }
        let l = list(&h.root, &set(&[]), &set(&[]), &refs(&r), now());
        assert!(l.iter().all(|s| s.here));
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
