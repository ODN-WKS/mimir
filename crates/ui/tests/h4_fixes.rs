//! QA13 phone re-check (H4): what the PC does so the phone's words are true.

use mimir_core::runner::{Config, Runner};
use mimir_core::task::Status;
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

/// The fake Claude Code's pid: the parent of the sleep it started.
fn claude_pid(env: &Env) -> (i32, u32) {
    let kids = env.children();
    let stat = std::fs::read_to_string(format!("/proc/{}/stat", kids[0])).unwrap();
    let after = &stat[stat.rfind(')').unwrap() + 2..];
    (
        after.split_whitespace().nth(1).unwrap().parse().unwrap(),
        kids[0],
    )
}

/// P3: a hosted Claude Code killed from outside (not Mímir's Stop) reads
/// "Stopped unexpectedly" within seconds, never Done, never after the host's
/// ten minutes. Both when the background line is still up and when the
/// background look already dropped it.
fn killed_outside(tag: &str, line_dropped: bool, note_dropped: bool) {
    let env = Env::hosted(tag);
    let mut r = env.runner("background-long.jsonl");
    r.new_task(&env.proj(), "start a long sleep");
    until(&mut r, "working in the background", |r| {
        status(r) == Status::Done && r.tasks[0].working_now()
    });
    if line_dropped {
        let id = r.tasks[0].task.id.clone();
        r.forget_background_for_test(&id);
        if note_dropped {
            r.tasks[0]
                .task
                .drop_note(mimir_core::runner::BACKGROUND_NOTE);
        }
    }
    let (parent, sleep) = claude_pid(&env);
    // SAFETY: the fake Claude Code this check started.
    unsafe { libc::kill(parent, libc::SIGKILL) };
    until(&mut r, "stopped unexpectedly", |r| {
        r.tasks[0].task.stopped_unexpectedly()
    });
    let t = &r.tasks[0].task;
    assert_eq!(t.status_words(), "Stopped unexpectedly");
    // The owner's own stop afterwards is a different thing.
    let id = t.id.clone();
    r.stop(&id);
    assert!(!r.tasks[0].task.stopped_unexpectedly());
    // SAFETY: the sleep this check started.
    unsafe { libc::kill(sleep as i32, libc::SIGKILL) };
}

#[test]
fn p3_hosted_outside_kill_with_the_background_line_is_stopped_unexpectedly_at_once() {
    killed_outside("h4-p3a", false, false);
}

#[test]
fn p3_hosted_outside_kill_after_the_line_was_dropped_is_stopped_unexpectedly_at_once() {
    killed_outside("h4-p3b", true, false);
}

/// The background look dropped its line before the end was read: still not Done.
#[test]
fn p3_hosted_outside_kill_after_the_line_and_note_were_dropped_is_stopped_unexpectedly() {
    killed_outside("h4-p3c", true, true);
}
