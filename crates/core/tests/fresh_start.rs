//! "Fresh start": the task carries on in the same chat with a new Claude
//! session that first reads the project's hand-off.

use mimir_core::runner::{Config, FRESH_BRIEF, FRESH_ROW_FULL, FreshWhy, Runner};
use mimir_core::task::{Entry, Status};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

struct Env {
    dir: PathBuf,
    step: PathBuf,
    args: PathBuf,
    r: Runner,
}

impl Env {
    fn new(name: &str) -> Self {
        let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
        let dir = std::env::temp_dir().join(format!("mimir-fresh-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("proj")).unwrap();
        let streams = dir.join("streams");
        std::fs::create_dir_all(&streams).unwrap();
        let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
        // Every fresh session is a new conversation: run 1, 2, 3...
        for n in 1..=5 {
            std::fs::copy(
                tests.join("live.jsonl"),
                streams.join(format!("run-{n}.jsonl")),
            )
            .unwrap();
        }
        std::fs::copy(tests.join("resume.jsonl"), streams.join("resume.jsonl")).unwrap();
        let step = dir.join("step");
        std::fs::write(&step, "1000").unwrap();
        let args = dir.join("args.log");
        let r = Runner::new(
            Config {
                claude: fake.clone(),
                hook_exe: fake,
                socket: dir.join("run/approvals.sock"),
                store_dir: dir.join("tasks"),
                env: vec![
                    ("FAKE_CLAUDE_STREAMS".into(), streams.display().to_string()),
                    ("FAKE_CLAUDE_STEP_FILE".into(), step.display().to_string()),
                    ("FAKE_CLAUDE_ARGS".into(), args.display().to_string()),
                ],
            },
            Arc::new(|| {}),
        )
        .unwrap();
        Self { dir, step, args, r }
    }

    fn runs(&self) -> usize {
        std::fs::read_to_string(&self.args)
            .unwrap_or_default()
            .lines()
            .count()
    }

    fn until(&mut self, what: &str, done: impl Fn(&Runner) -> bool) {
        let end = Instant::now() + Duration::from_secs(30);
        while !done(&self.r) {
            self.r.tick();
            assert!(Instant::now() < end, "timed out waiting for: {what}");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    /// A task whose first turn has ended and whose Claude Code has exited.
    fn done_task(&mut self) -> String {
        let id = self
            .r
            .new_task(&self.dir.join("proj"), "Tidy up notes.txt.");
        self.until("the first turn ends", |r| {
            r.tasks[0].task.status == Status::Done && !r.tasks[0].running()
        });
        id
    }
}

fn dividers(r: &Runner) -> usize {
    r.tasks[0]
        .task
        .entries
        .iter()
        .filter(|e| matches!(e, Entry::Note(n) if n.starts_with("Fresh start at ")))
        .count()
}

#[test]
fn an_idle_task_starts_afresh_with_the_same_model_and_effort() {
    let mut e = Env::new("idle");
    let id = e.done_task();
    e.r.set_model(&id, Some("haiku".into()));
    e.r.set_effort(&id, Some("high".into()));
    assert!(e.r.tasks[0].task.session_id.is_some());
    e.r.fresh_start(&id, FreshWhy::Owner).unwrap();
    let t = &e.r.tasks[0].task;
    assert_eq!(t.session_id, None);
    assert_eq!(t.brief.as_deref(), Some(FRESH_BRIEF));
    assert!(t.brief_pending);
    assert_eq!(t.model.as_deref(), Some("haiku"));
    assert_eq!(t.effort.as_deref(), Some("high"));
    assert_eq!(dividers(&e.r), 1);
    let line = t.entries.iter().rev().find_map(|x| match x {
        Entry::Note(n) => Some(n.clone()),
        _ => None,
    });
    assert!(
        line.unwrap().ends_with("· continues from the hand-off"),
        "the divider's words"
    );
    // The owner's own press only gets ready: nothing runs until they write.
    assert_eq!(e.runs(), 1);
    // Asked twice, it is still one fresh start.
    e.r.fresh_start(&id, FreshWhy::Owner).unwrap();
    assert_eq!(dividers(&e.r), 1);
}

#[test]
fn a_projects_own_hand_off_file_is_named() {
    let mut e = Env::new("named");
    let id = e.done_task();
    let project = e.r.tasks[0].task.project.clone();
    e.r.handoff_files.insert(project, "notes/NEXT.md".into());
    e.r.fresh_start(&id, FreshWhy::Owner).unwrap();
    let brief = e.r.tasks[0].task.brief.clone().unwrap();
    assert!(brief.contains("Read notes/NEXT.md first"), "{brief}");
}

#[test]
fn a_working_task_finishes_its_turn_first_and_the_new_session_then_runs() {
    let mut e = Env::new("working");
    std::fs::write(&e.step, "0").unwrap();
    let id = e.r.new_task(&e.dir.join("proj"), "Tidy up notes.txt.");
    let end = Instant::now() + Duration::from_secs(20);
    while !(e.r.tasks[0].busy() && e.runs() == 1) {
        e.r.tick();
        assert!(Instant::now() < end);
        std::thread::sleep(Duration::from_millis(10));
    }
    e.r.fresh_start(&id, FreshWhy::Session).unwrap();
    for _ in 0..50 {
        e.r.tick();
        std::thread::sleep(Duration::from_millis(10));
    }
    assert!(e.r.tasks[0].busy(), "the running turn was ended");
    assert_eq!(dividers(&e.r), 0);
    std::fs::write(&e.step, "1000").unwrap();
    let args = e.args.clone();
    e.until("the fresh session runs", |_| {
        std::fs::read_to_string(&args)
            .unwrap_or_default()
            .lines()
            .count()
            >= 2
    });
    let second = std::fs::read_to_string(&e.args).unwrap();
    let second = second.lines().nth(1).unwrap();
    assert!(!second.contains("--resume"), "{second}");
    assert_eq!(dividers(&e.r), 1);
    // Nothing was added to the chat as the owner's own words.
    let own = e.r.tasks[0]
        .task
        .entries
        .iter()
        .filter(|x| matches!(x, Entry::You(_)))
        .count();
    assert_eq!(own, 1);
}

#[test]
fn the_fourth_in_a_row_waits_for_the_owner() {
    let mut e = Env::new("row");
    let id = e.done_task();
    for n in 1..=3 {
        e.r.fresh_start(&id, FreshWhy::Session).unwrap();
        e.until("the fresh session ends", |r| {
            r.tasks[0].task.status == Status::Done && !r.tasks[0].running()
        });
        assert_eq!(dividers(&e.r), n);
    }
    let why = e.r.fresh_start(&id, FreshWhy::Session).unwrap_err();
    assert_eq!(why, FRESH_ROW_FULL);
    assert_eq!(why, "Waiting for you: 3 fresh starts in a row.");
    assert_eq!(dividers(&e.r), 3);
    e.r.send_with(&id, "Thanks, carry on.", &[]);
    e.until("the turn ends", |r| {
        r.tasks[0].task.status == Status::Done && !r.tasks[0].running()
    });
    e.r.fresh_start(&id, FreshWhy::Session).unwrap();
}

#[test]
fn a_whisper_task_refuses() {
    let mut e = Env::new("whisper");
    let id = e.done_task();
    e.r.tasks[0].task.incognito = true;
    let session = e.r.tasks[0].task.session_id.clone();
    let why = e.r.fresh_start(&id, FreshWhy::Owner).unwrap_err();
    assert!(why.contains("Whisper"), "{why}");
    assert_eq!(e.r.tasks[0].task.session_id, session);
    assert_eq!(dividers(&e.r), 0);
}

#[test]
fn a_stopped_task_does_not_run_on() {
    let mut e = Env::new("stopped");
    std::fs::write(&e.step, "0").unwrap();
    let id = e.r.new_task(&e.dir.join("proj"), "Tidy up notes.txt.");
    let end = Instant::now() + Duration::from_secs(20);
    while !(e.r.tasks[0].busy() && e.runs() == 1) {
        e.r.tick();
        assert!(Instant::now() < end);
        std::thread::sleep(Duration::from_millis(10));
    }
    e.r.fresh_start(&id, FreshWhy::Session).unwrap();
    e.r.stop(&id);
    e.until("the run ends", |r| !r.tasks[0].running());
    for _ in 0..50 {
        e.r.tick();
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(e.runs(), 1);
    assert_eq!(dividers(&e.r), 0);
}

#[test]
fn continue_after_a_fresh_start_does_not_repeat_the_old_request() {
    let mut e = Env::new("continue");
    let id = e.done_task();
    e.r.fresh_start(&id, FreshWhy::Owner).unwrap();
    e.r.resume(&id);
    e.until("the new session ends", |r| {
        r.tasks[0].task.status == Status::Done && !r.tasks[0].running()
    });
    let own: Vec<_> = e.r.tasks[0]
        .task
        .entries
        .iter()
        .filter_map(|x| match x {
            Entry::You(t) => Some(t.as_str()),
            _ => None,
        })
        .collect();
    assert_eq!(own.len(), 2, "{own:?}");
    assert_ne!(own[1], own[0]);
}
