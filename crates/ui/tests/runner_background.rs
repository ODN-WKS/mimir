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
