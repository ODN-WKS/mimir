//! Faults found in "Fresh start": a new session that fails to start keeps the
//! hand-off, sessions left behind stay with their task, and the "in a row"
//! note shows once.

use mimir_core::runner::{Config, FRESH_BRIEF, FRESH_ROW_FULL, FreshWhy, Runner};
use mimir_core::task::{Entry, Status};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

const OLD: &str = "Tidy up notes.txt.";

struct Env {
    dir: PathBuf,
    streams: PathBuf,
    input: PathBuf,
    r: Runner,
}

impl Env {
    fn new(name: &str) -> Self {
        let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
        let dir = std::env::temp_dir().join(format!("mimir-faults-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("proj")).unwrap();
        let streams = dir.join("streams");
        std::fs::create_dir_all(&streams).unwrap();
        let step = dir.join("step");
        std::fs::write(&step, "1000").unwrap();
        let input = dir.join("input.log");
        let r = Runner::new(
            Config {
                claude: fake.clone(),
                hook_exe: fake,
                socket: dir.join("run/approvals.sock"),
                store_dir: dir.join("tasks"),
                env: vec![
                    ("FAKE_CLAUDE_STREAMS".into(), streams.display().to_string()),
                    ("FAKE_CLAUDE_STEP_FILE".into(), step.display().to_string()),
                    ("FAKE_CLAUDE_INPUT".into(), input.display().to_string()),
                ],
            },
            Arc::new(|| {}),
        )
        .unwrap();
        Self {
            dir,
            streams,
            input,
            r,
        }
    }

    /// The nth new conversation will work (without it, that start fails).
    fn works(&self, n: usize) {
        let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
        std::fs::copy(
            tests.join("live.jsonl"),
            self.streams.join(format!("run-{n}.jsonl")),
        )
        .unwrap();
    }

    fn settle(&mut self, want: Status) {
        let end = Instant::now() + Duration::from_secs(30);
        loop {
            self.r.tick();
            let l = &self.r.tasks[0];
            if l.task.status == want && !l.running() && l.task.fresh_pending.is_none() {
                return;
            }
            assert!(Instant::now() < end, "never {want:?}: {:?}", l.task.entries);
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn done_task(&mut self) -> String {
        self.works(1);
        let id = self.r.new_task(&self.dir.join("proj"), OLD);
        self.settle(Status::Done);
        id
    }

    fn inputs(&self) -> Vec<String> {
        std::fs::read_to_string(&self.input)
            .unwrap_or_default()
            .lines()
            .map(str::to_string)
            .collect()
    }

    /// The last message Claude was given carries the hand-off, and the old
    /// request went to Claude once only (in the first session).
    fn handed_off(&self) -> String {
        let all = self.inputs();
        let old = all.iter().filter(|l| l.contains(OLD)).count();
        assert_eq!(old, 1, "the old request went again: {all:?}");
        let last = all.last().unwrap().clone();
        assert!(last.contains(FRESH_BRIEF), "no hand-off: {all:?}");
        last
    }
}

#[test]
fn a_failed_self_started_fresh_start_keeps_the_hand_off() {
    let mut e = Env::new("self");
    let id = e.done_task();
    e.r.fresh_start(&id, FreshWhy::Session).unwrap();
    e.settle(Status::Stopped);
    assert_eq!(e.r.tasks[0].task.session_id, None);
    // Fails twice: still owed the third time.
    e.r.resume(&id);
    e.settle(Status::Stopped);
    e.works(4);
    e.r.resume(&id);
    e.settle(Status::Done);
    e.handed_off();
}

#[test]
fn continue_twice_after_the_owners_fresh_start_fails_never_repeats() {
    let mut e = Env::new("owner");
    let id = e.done_task();
    e.r.fresh_start(&id, FreshWhy::Owner).unwrap();
    e.r.resume(&id);
    e.settle(Status::Stopped);
    e.works(3);
    e.r.resume(&id);
    e.settle(Status::Done);
    e.handed_off();
}

#[test]
fn a_message_the_new_session_never_got_goes_again_with_the_hand_off() {
    let mut e = Env::new("message");
    let id = e.done_task();
    e.r.fresh_start(&id, FreshWhy::Owner).unwrap();
    e.r.send_with(&id, "Now sort them by date.", &[]);
    e.settle(Status::Stopped);
    e.works(3);
    e.r.resume(&id);
    e.settle(Status::Done);
    let last = e.handed_off();
    assert!(last.contains("Now sort them by date."), "{last}");
}

#[test]
fn the_row_full_note_shows_once() {
    let mut e = Env::new("note");
    let id = e.done_task();
    e.r.tasks[0].task.fresh_row = 3;
    for _ in 0..3 {
        let why = e.r.fresh_start(&id, FreshWhy::Session).unwrap_err();
        assert_eq!(why, FRESH_ROW_FULL);
    }
    let count = |r: &Runner| {
        r.tasks[0]
            .task
            .entries
            .iter()
            .filter(|x| matches!(x, Entry::Note(n) if n == FRESH_ROW_FULL))
            .count()
    };
    assert_eq!(count(&e.r), 1);
}

/// One session's history, where Claude Code would keep it.
fn transcript(project: &std::path::Path, id: &str) -> PathBuf {
    let file = mimir_core::sessions::session_file(project, id);
    std::fs::create_dir_all(file.parent().unwrap()).unwrap();
    let line = serde_json::json!({
        "type": "user",
        "cwd": project.display().to_string(),
        "message": {"role": "user", "content": "Plan the beds"},
    });
    std::fs::write(&file, format!("{line}\n")).unwrap();
    file
}

#[test]
fn a_session_left_behind_stays_with_its_task() {
    // Not under /tmp: sessions of projects there are left out of the list.
    let base = PathBuf::from(std::env::var_os("HOME").unwrap())
        .join(".cache")
        .join(format!("mimir-faults-left-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&base);
    let project = base.join("Recipe box");
    std::fs::create_dir_all(&project).unwrap();
    let claude = base.join("claude");
    unsafe { std::env::set_var("CLAUDE_CONFIG_DIR", &claude) };
    let mut e = Env::new("left");
    let id = e.done_task();
    e.r.tasks[0].task.session_id = Some("left-mine".into());
    let mine = transcript(&project, "left-mine");
    // A second task that came from a session the person already had.
    let theirs = transcript(&project, "left-theirs");
    let found = |sid: &str| {
        mimir_core::sessions::list(&project)
            .into_iter()
            .find(|s| s.id == sid)
            .unwrap()
    };
    let second = e.r.open_past(&project, &found("left-theirs")).unwrap();
    e.r.fresh_start(&id, FreshWhy::Owner).unwrap();
    e.r.fresh_start(&second, FreshWhy::Owner).unwrap();

    let none = Default::default();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;
    let listed = mimir_core::pcsessions::list_for(&e.r, &claude, &none, &none, now);
    assert!(listed.is_empty(), "{listed:?}");

    // Opening one gives the task that has it, never a second task, and does
    // not follow it, even while another app writes it.
    let running = ["left-mine".to_string()].into();
    for (sid, task) in [("left-mine", &id), ("left-theirs", &second)] {
        let o = mimir_core::pcsessions::open(&mut e.r, &claude, sid, &running, now).unwrap();
        assert_eq!((&o.task, o.follow), (task, false));
        assert_eq!(&e.r.open_past(&project, &found(sid)).unwrap(), task);
    }
    assert_eq!(e.r.tasks.len(), 2);

    // Kept by the store, so it holds after a restart.
    let saved = mimir_core::store::Store::new(e.dir.join("tasks"))
        .unwrap()
        .load_all()
        .0;
    assert!(saved.iter().any(|t| t.holds("left-theirs")));

    // Two tasks on one session (made before this was refused): deleting one
    // leaves the other its conversation.
    let other = e.r.tasks.iter().position(|l| l.task.id == second).unwrap();
    e.r.tasks[other].task.session_id = Some("left-mine".into());
    e.r.delete(&id);
    assert!(mine.exists(), "another task's conversation was deleted");
    e.r.delete(&second);
    assert!(theirs.exists(), "the person's own session was deleted");
    let _ = std::fs::remove_dir_all(&base);
    let _ = std::fs::remove_dir_all(&e.dir);
}
