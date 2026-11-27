//! The window's task logic driven end to end with the fake claude:
//! new task, approve, deny, stop, close and reopen, resume.

use mimir_core::approvals::Answer;
use mimir_core::runner::{Config, Runner};
use mimir_core::task::{Entry, Status};
use std::path::{Path, PathBuf};
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
                    ("FAKE_CLAUDE_ARGS".into(), self.args.display().to_string()),
                ],
            },
            Arc::new(|| {}),
        )
        .unwrap()
    }

    fn proj(&self) -> PathBuf {
        self.dir.join("proj")
    }

    fn last_args(&self) -> String {
        let all = std::fs::read_to_string(&self.args).unwrap_or_default();
        let last = all.lines().last().unwrap_or("[]").to_string();
        let v: Vec<String> = serde_json::from_str(&last).unwrap();
        v.join(" ")
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

#[test]
fn new_task_approve_and_finish() {
    let env = Env::new("approve");
    let mut r = env.runner("plan_approve.jsonl");
    let id = r.new_task(&env.proj(), "Make a plan\nwith details");
    // A task is named by the first words of its first message.
    assert_eq!(
        r.tasks[0].task.title,
        mimir_core::runner::title_from("Make a plan\nwith details")
    );
    until(&mut r, "a question", |r| status(r) == Status::NeedsYou);
    assert!(r.tasks[0].asks[0].request.words().contains("echo hi"));
    r.answer(&id, Answer::Allow);
    until(&mut r, "done and closed", |r| {
        status(r) == Status::Done && !r.tasks[0].running()
    });
    let t = &r.tasks[0].task;
    assert_eq!(t.plan_percent(), Some(100));
    assert!(r.weekly.is_some());
    assert!(env.last_args().contains("--settings"));
    assert!(
        !env.last_args().contains("--resume"),
        "a new task starts fresh"
    );
}

#[test]
fn deny() {
    let env = Env::new("deny");
    let mut r = env.runner("deny.jsonl");
    let id = r.new_task(&env.proj(), "touch it");
    until(&mut r, "a question", |r| status(r) == Status::NeedsYou);
    r.answer(&id, Answer::No);
    until(&mut r, "done", |r| {
        status(r) == Status::Done && !r.tasks[0].running()
    });
    let refused = r.tasks[0]
        .task
        .entries
        .iter()
        .any(|e| matches!(e, Entry::Step(s) if s.refused));
    assert!(refused);
}

#[test]
fn always_is_noted() {
    let env = Env::new("always");
    let mut r = env.runner("always.jsonl");
    let id = r.new_task(&env.proj(), "touch two");
    until(&mut r, "a question", |r| status(r) == Status::NeedsYou);
    r.answer(&id, Answer::Always);
    until(&mut r, "done", |r| {
        status(r) == Status::Done && !r.tasks[0].running()
    });
    let noted = r.tasks[0]
        .task
        .entries
        .iter()
        .any(|e| matches!(e, Entry::Note(n) if n.contains("Bash(touch *)")));
    assert!(noted);
}

#[test]
fn stop_a_running_task() {
    let env = Env::new("stop");
    let mut r = env.runner("interrupt.jsonl");
    let id = r.new_task(&env.proj(), "long job");
    until(&mut r, "a question", |r| status(r) == Status::NeedsYou);
    r.answer(&id, Answer::Allow);
    until(&mut r, "working", |r| status(r) == Status::Working);
    r.stop(&id);
    until(&mut r, "process gone", |r| !r.tasks[0].running());
    assert_eq!(status(&r), Status::Stopped);
}

#[test]
fn message_while_working() {
    let env = Env::new("mid");
    let mut r = env.runner("midturn.jsonl");
    let id = r.new_task(&env.proj(), "run sleep");
    until(&mut r, "a step", |r| {
        r.tasks[0]
            .task
            .entries
            .iter()
            .any(|e| matches!(e, Entry::Step(_)))
    });
    assert!(r.tasks[0].running());
    r.send(&id, "also 2+2");
    until(&mut r, "done", |r| {
        status(r) == Status::Done && !r.tasks[0].running()
    });
    let you = r.tasks[0]
        .task
        .entries
        .iter()
        .filter(|e| matches!(e, Entry::You(_)))
        .count();
    assert_eq!(you, 2);
}

#[test]
fn close_reopen_and_resume() {
    let env = Env::new("reopen");
    let session;
    {
        let mut r = env.runner("plan_approve.jsonl");
        let id = r.new_task(&env.proj(), "Make a plan");
        until(&mut r, "a question", |r| status(r) == Status::NeedsYou);
        r.answer(&id, Answer::Allow);
        until(&mut r, "done", |r| {
            status(r) == Status::Done && !r.tasks[0].running()
        });
        session = r.tasks[0].task.session_id.clone().unwrap();
        r.shutdown();
    }
    let mut r = env.runner("resume.jsonl");
    assert_eq!(r.tasks.len(), 1, "the task came back");
    assert_eq!(status(&r), Status::Done);
    let id = r.tasks[0].task.id.clone();
    r.send(&id, "What file did you create?");
    until(&mut r, "done", |r| {
        status(r) == Status::Done && !r.tasks[0].running()
    });
    assert!(env.last_args().contains(&format!("--resume {session}")));
    let last = r.tasks[0].task.entries.last().unwrap().clone();
    assert!(matches!(last, Entry::Claude(t) if t.contains("hello.txt")));
}

#[test]
fn closed_while_working_comes_back_paused() {
    let env = Env::new("closed");
    {
        let mut r = env.runner("plan_approve.jsonl");
        r.new_task(&env.proj(), "Make a plan");
        until(&mut r, "a question", |r| status(r) == Status::NeedsYou);
        r.shutdown();
        assert_eq!(status(&r), Status::Paused);
    }
    let r = env.runner("resume.jsonl");
    assert_eq!(status(&r), Status::Paused);
    let noted = r.tasks[0]
        .task
        .entries
        .iter()
        .any(|e| matches!(e, Entry::Note(n) if n.contains("Continue")));
    assert!(noted);
}

#[test]
fn missing_claude_is_explained() {
    let env = Env::new("missing");
    let cfg_runner = || {
        Runner::new(
            Config {
                claude: Path::new("/nonexistent/claude").into(),
                hook_exe: PathBuf::from(env!("CARGO_BIN_EXE_mimir")),
                socket: env.dir.join("run/approvals.sock"),
                store_dir: env.dir.join("tasks"),
                env: vec![],
            },
            Arc::new(|| {}),
        )
        .unwrap()
    };
    let mut r = cfg_runner();
    assert!(r.problems.iter().any(|p| p.contains("not installed")));
    let id = r.new_task(&env.proj(), "hi");
    assert_eq!(r.tasks[0].task.status, Status::Stopped);
    assert!(
        r.tasks[0]
            .task
            .entries
            .iter()
            .any(|e| matches!(e, Entry::Note(n) if n.contains("could not start")))
    );
    r.remove(&id);
    assert!(r.tasks.is_empty());
}

/// N8: Continue on a task whose run never got a conversation hands the
/// request over again, without a second copy of the person's message.
#[test]
fn continue_without_a_conversation_does_not_repeat_the_message() {
    let env = Env::new("cont-dup");
    let mut r = env.runner("plan_approve.jsonl");
    let id = r.new_task(&env.proj(), "Make a plan");
    until(&mut r, "a question", |r| status(r) == Status::NeedsYou);
    r.stop(&id);
    until(&mut r, "process gone", |r| !r.tasks[0].running());
    r.tasks[0].task.session_id = None;
    r.resume(&id);
    let you: Vec<_> = r.tasks[0]
        .task
        .entries
        .iter()
        .filter_map(|e| match e {
            Entry::You(t) => Some(t.clone()),
            _ => None,
        })
        .collect();
    assert_eq!(you, vec!["Make a plan".to_string()], "one bubble only");
    assert_eq!(status(&r), Status::Working);
}
