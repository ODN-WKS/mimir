//! A task whose Claude Code stays alive for something it started in the
//! background is working, not done (the fake claude spawns a `sleep`).

use mimir_core::runner::{Config, Runner};
use mimir_core::task::{Entry, Status};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

struct Env {
    dir: PathBuf,
    args: PathBuf,
}

impl Env {
    fn new(tag: &str) -> Env {
        let dir = std::env::temp_dir().join(format!("station-run-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("proj")).unwrap();
        Env {
            args: dir.join("args.log"),
            dir,
        }
    }

    fn runner(&self, stream: &str) -> Runner {
        let exe = PathBuf::from(env!("CARGO_BIN_EXE_mimir"));
        let fake = exe.with_file_name("fake-claude");
        let stream = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../core/tests/streams")
            .join(stream);
        Runner::new(
            Config {
                claude: fake,
                hook_exe: exe,
                socket: self.dir.join("run/approvals.sock"),
                store_dir: self.dir.join("tasks"),
                env: vec![
                    ("FAKE_CLAUDE_STREAM".into(), stream.display().to_string()),
                    ("FAKE_CLAUDE_SPEED".into(), "1.0".into()),
                    ("FAKE_CLAUDE_ARGS".into(), self.args.display().to_string()),
                    (
                        "FAKE_CLAUDE_CHILDREN".into(),
                        self.dir.join("children.log").display().to_string(),
                    ),
                ],
            },
            Arc::new(|| {}),
        )
        .unwrap()
    }

    fn proj(&self) -> PathBuf {
        self.dir.join("proj")
    }

    fn children(&self) -> Vec<u32> {
        std::fs::read_to_string(self.dir.join("children.log"))
            .unwrap_or_default()
            .lines()
            .filter_map(|l| l.parse().ok())
            .collect()
    }
}

impl Drop for Env {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// Ticks until `done` is true for the first task.
fn until(r: &mut Runner, what: &str, done: impl Fn(&Runner) -> bool) {
    let end = Instant::now() + Duration::from_secs(20);
    loop {
        r.tick();
        if done(r) {
            return;
        }
        assert!(
            Instant::now() < end,
            "timed out waiting for: {what}\n{:#?}\nproblems: {:?}",
            r.tasks
                .first()
                .map(|l| (&l.task.status, &l.task.entries, l.running())),
            r.problems
        );
        std::thread::sleep(Duration::from_millis(10));
    }
}

fn status(r: &Runner) -> Status {
    r.tasks[0].task.status
}

fn alive(pid: u32) -> bool {
    // A zombie does not count: it runs nothing.
    std::fs::read_to_string(format!("/proc/{pid}/stat"))
        .map(|s| !s.contains(") Z "))
        .unwrap_or(false)
}

#[test]
fn background_work_shows_working_then_done_by_itself() {
    let env = Env::new("bg-done");
    let mut r = env.runner("background.jsonl");
    r.new_task(&env.proj(), "start a sleep in the background");
    // The turn ends, the process and its sleep stay: still working.
    until(&mut r, "working in the background", |r| {
        status(r) == Status::Done && r.tasks[0].working_now()
    });
    assert!(r.tasks[0].running());
    assert!(r.tasks[0].busy(), "Stop must show");
    assert!(
        r.tasks[0]
            .task
            .entries
            .iter()
            .any(|e| matches!(e, Entry::Note(n) if n.contains("in the background"))),
        "a plain note says why"
    );
    // The sleep ends: honest end state by itself, nothing pressed.
    until(&mut r, "done by itself", |r| {
        !r.tasks[0].working_now() && !r.tasks[0].running()
    });
    assert_eq!(status(&r), Status::Done);
}

#[test]
fn stop_ends_background_work_too() {
    let env = Env::new("bg-stop");
    let mut r = env.runner("background-long.jsonl");
    let id = r.new_task(&env.proj(), "start a long sleep");
    until(&mut r, "working in the background", |r| {
        status(r) == Status::Done && r.tasks[0].working_now()
    });
    let kids = env.children();
    assert_eq!(kids.len(), 1);
    assert!(alive(kids[0]));
    r.stop(&id);
    until(&mut r, "process gone", |r| !r.tasks[0].running());
    assert_eq!(status(&r), Status::Stopped);
    assert!(!r.tasks[0].working_now());
    let end = Instant::now() + Duration::from_secs(3);
    while alive(kids[0]) && Instant::now() < end {
        std::thread::sleep(Duration::from_millis(20));
    }
    assert!(!alive(kids[0]), "the sleep it started is gone too");
}

#[test]
fn a_helper_started_mid_turn_does_not_keep_the_task_working() {
    let env = Env::new("bg-helper");
    let mut r = env.runner("helper.jsonl");
    r.new_task(&env.proj(), "a turn with a helper of its own");
    until(&mut r, "the turn ended", |r| status(r) == Status::Done);
    // The helper outlives the turn for about two more seconds.
    let end = Instant::now() + Duration::from_millis(1500);
    while Instant::now() < end {
        r.tick();
        assert!(!r.tasks[0].working_now(), "a helper is not the task's work");
        assert!(
            !r.tasks[0]
                .task
                .entries
                .iter()
                .any(|e| matches!(e, Entry::Note(n) if n.contains("in the background")))
        );
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn the_note_is_said_once_and_goes_when_nothing_runs() {
    let env = Env::new("bg-note");
    let mut r = env.runner("background.jsonl");
    r.new_task(&env.proj(), "start a sleep in the background");
    let count = |r: &Runner| {
        r.tasks[0]
            .task
            .entries
            .iter()
            .filter(|e| matches!(e, Entry::Note(n) if n.contains("in the background")))
            .count()
    };
    until(&mut r, "working in the background", |r| {
        status(r) == Status::Done && r.tasks[0].working_now()
    });
    assert_eq!(count(&r), 1, "said once while it runs");
    until(&mut r, "done by itself", |r| {
        status(r) == Status::Done && !r.tasks[0].running()
    });
    // T4/F1: nothing runs any more, so the line is not there to disagree.
    assert_eq!(count(&r), 0, "the line stays after nothing runs");
}

/// T4 / P5: its Claude Code is killed while it still runs something in the
/// background: not Done. It is Stopped unexpectedly (a different thing from a
/// stop the owner made), and the background line is gone.
#[test]
fn a_killed_claude_is_stopped_unexpectedly_not_done() {
    let env = Env::new("bg-killed");
    let mut r = env.runner("background-long.jsonl");
    r.new_task(&env.proj(), "start a long sleep");
    until(&mut r, "working in the background", |r| {
        status(r) == Status::Done && r.tasks[0].working_now()
    });
    let kids = env.children();
    assert_eq!(kids.len(), 1);
    // Its Claude Code is the sleep's parent.
    let stat = std::fs::read_to_string(format!("/proc/{}/stat", kids[0])).unwrap();
    let after = &stat[stat.rfind(')').unwrap() + 2..];
    let parent: i32 = after.split_whitespace().nth(1).unwrap().parse().unwrap();
    // SAFETY: the fake Claude Code this check started.
    unsafe { libc::kill(parent, libc::SIGKILL) };
    until(&mut r, "stopped unexpectedly", |r| {
        r.tasks[0].task.stopped_unexpectedly()
    });
    let t = &r.tasks[0].task;
    assert_eq!(t.status, Status::Stopped);
    assert_eq!(t.status_words(), "Stopped unexpectedly");
    assert!(
        !t.entries
            .iter()
            .any(|e| matches!(e, Entry::Note(n) if n.contains("in the background"))),
        "the background line is still there"
    );
    // The owner's own stop is a different thing.
    let id = t.id.clone();
    r.stop(&id);
    assert!(!r.tasks[0].task.stopped_unexpectedly());
    // Cleanup: the sleep.
    // SAFETY: the sleep this check started.
    unsafe { libc::kill(kids[0] as i32, libc::SIGKILL) };
}

#[test]
fn a_finished_task_has_no_open_step_after_its_background_work_ends() {
    let env = Env::new("bg-steps");
    let mut r = env.runner("background.jsonl");
    r.new_task(&env.proj(), "start a sleep in the background");
    until(&mut r, "working in the background", |r| {
        status(r) == Status::Done && r.tasks[0].working_now()
    });
    until(&mut r, "done by itself", |r| {
        !r.tasks[0].working_now() && !r.tasks[0].running()
    });
    let steps: Vec<_> = r.tasks[0]
        .task
        .entries
        .iter()
        .filter_map(|e| match e {
            Entry::Step(s) => Some(s.ok),
            _ => None,
        })
        .collect();
    assert!(
        !steps.is_empty(),
        "the stream has a background command step"
    );
    assert!(
        steps.iter().all(|ok| *ok == Some(true)),
        "no step is left open or failed on a Done task: {steps:?}"
    );
}
