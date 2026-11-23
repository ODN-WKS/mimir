//! QA12 phone re-hunt (H2): what the PC does so the phone's words are true.

use mimir_core::runner::{Config, Runner};
use mimir_core::task::{Entry, Status, Task};
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

    /// Runs hosted, as on a real PC, so a second runner can take the run over.
    fn hosted(tag: &str) -> Env {
        // SAFETY: nextest runs each check in its own process.
        unsafe {
            std::env::set_var("MIMIR_HOSTS", "plain");
            std::env::set_var("MIMIR_TEST_PROGRAM", env!("CARGO_BIN_EXE_mimir"));
        }
        let env = Env::new(tag);
        // SAFETY: as above.
        unsafe {
            std::env::set_var("XDG_STATE_HOME", env.dir.join("state"));
            std::env::set_var("XDG_RUNTIME_DIR", env.dir.join("run"));
        }
        env
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

/// P3: after the service restarts its checks start over; a Claude Code killed
/// then, while the task still said it ran something in the background, is
/// Stopped unexpectedly (never Done with a cross and that line).
#[test]
fn p3_a_killed_claude_after_a_restart_is_stopped_unexpectedly() {
    let env = Env::new("h2-p3");
    let mut r = env.runner("background-long.jsonl");
    r.new_task(&env.proj(), "start a long sleep");
    until(&mut r, "working in the background", |r| {
        status(r) == Status::Done && r.tasks[0].working_now()
    });
    let id = r.tasks[0].task.id.clone();
    r.forget_background_for_test(&id);
    let kids = env.children();
    let stat = std::fs::read_to_string(format!("/proc/{}/stat", kids[0])).unwrap();
    let after = &stat[stat.rfind(')').unwrap() + 2..];
    let parent: i32 = after.split_whitespace().nth(1).unwrap().parse().unwrap();
    // SAFETY: the fake Claude Code this check started.
    unsafe { libc::kill(parent, libc::SIGKILL) };
    until(&mut r, "stopped unexpectedly", |r| {
        r.tasks[0].task.status == Status::Stopped
            || r.tasks[0].task.status == Status::Done && !r.tasks[0].running()
    });
    let t = &r.tasks[0].task;
    assert_eq!(t.status_words(), "Stopped unexpectedly");
    assert!(
        !t.entries
            .iter()
            .any(|e| matches!(e, Entry::Note(n) if n.contains("in the background"))),
        "the background line is still there"
    );
    // SAFETY: the sleep this check started.
    unsafe { libc::kill(kids[0] as i32, libc::SIGKILL) };
}

/// P1: a session from outside that is carried on here loses the two lines
/// that said it was elsewhere / to be carried on, and its status follows the
/// new run (not Stopped).
#[test]
fn p1_carrying_on_drops_the_old_outside_lines() {
    let env = Env::new("h2-p1");
    let store = mimir_core::store::Store::new(env.dir.join("tasks")).unwrap();
    let mut t = Task::new(
        "1700000000-1-1".into(),
        "Fix the latch".into(),
        env.proj().display().to_string(),
        1_700_000_000,
    );
    t.add_you("Fix the latch");
    t.entries.push(Entry::Claude("Looking at it.".into()));
    t.add_note(mimir_core::sessions::OPENED_NOTE);
    t.add_note(mimir_core::pcsessions::FOLLOW_NOTE);
    t.outside = true;
    t.status = Status::Stopped;
    store.save(&t).unwrap();
    let mut r = env.runner("resume.jsonl");
    assert_eq!(r.tasks.len(), 1);
    r.send("1700000000-1-1", "Thanks");
    let notes = |r: &Runner| {
        r.tasks[0]
            .task
            .entries
            .iter()
            .filter(|e| matches!(e, Entry::Note(n) if n == mimir_core::sessions::OPENED_NOTE || n == mimir_core::pcsessions::FOLLOW_NOTE))
            .count()
    };
    assert_eq!(notes(&r), 0, "the old lines are still there");
    assert_ne!(
        r.tasks[0].task.status,
        Status::Stopped,
        "the status still says Stopped after carrying on"
    );
}

/// P4: the service restarts while a task works in the background; the run is
/// taken over. When what it ran ends, the task is Done with no "still running"
/// line and no leftover open step.
#[test]
fn p4_after_a_restart_the_background_line_goes_when_the_work_ends() {
    let env = Env::hosted("h2-p4");
    let mut r = env.runner("background.jsonl");
    r.new_task(&env.proj(), "start a sleep in the background");
    until(&mut r, "working in the background", |r| {
        status(r) == Status::Done && r.tasks[0].working_now()
    });
    // The service goes away (the host keeps the run) and comes back.
    r.shutdown();
    drop(r);
    let mut r = env.runner("background.jsonl");
    until(&mut r, "the run taken over", |r| {
        !r.tasks.is_empty() && r.tasks[0].running()
    });
    until(&mut r, "done by itself", |r| {
        !r.tasks[0].running() && !r.tasks[0].working_now()
    });
    let t = &r.tasks[0].task;
    assert_eq!(t.status, Status::Done, "{:?}", t.entries);
    assert!(
        !t.entries
            .iter()
            .any(|e| matches!(e, Entry::Note(n) if n.contains("in the background"))),
        "the background line is still there: {:?}",
        t.entries
    );
}
