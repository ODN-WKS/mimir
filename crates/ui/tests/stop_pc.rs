//! Stop really stops a task on the PC: a Claude Code that ignores the
//! interrupt is ended soon after, and nothing that was waiting restarts it.

use mimir_core::runner::{Config, Runner};
use mimir_core::task::Status;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

struct Env {
    dir: PathBuf,
}

impl Env {
    fn new(tag: &str, ignore_int: bool, hosts: &str) -> (Env, Runner) {
        // SAFETY: nextest runs each check in its own process.
        unsafe { std::env::set_var("MIMIR_HOSTS", hosts) };
        let dir = std::env::temp_dir().join(format!("mimir-stoppc-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("proj")).unwrap();
        // One message in, then a long silence: a task that keeps working.
        let stream = dir.join("long.jsonl");
        std::fs::write(
            &stream,
            concat!(
                "{\"t\":0,\"in\":\"x\"}\n",
                "{\"t\":0.1,\"line\":\"{\\\"type\\\":\\\"system\\\",\\\"subtype\\\":\\\"init\\\",\\\"session_id\\\":\\\"s1\\\",\\\"tools\\\":[],\\\"model\\\":\\\"m\\\"}\"}\n",
                "{\"t\":300,\"line\":\"{}\"}\n",
            ),
        )
        .unwrap();
        let exe = PathBuf::from(env!("CARGO_BIN_EXE_mimir"));
        let mut env = vec![
            (
                "FAKE_CLAUDE_STREAM".to_string(),
                stream.display().to_string(),
            ),
            ("FAKE_CLAUDE_SPEED".into(), "1".into()),
            (
                "FAKE_CLAUDE_PIDFILE".into(),
                dir.join("pid").display().to_string(),
            ),
            (
                "FAKE_CLAUDE_INPUT".into(),
                dir.join("input.log").display().to_string(),
            ),
            (
                "FAKE_CLAUDE_ARGS".into(),
                dir.join("args.log").display().to_string(),
            ),
        ];
        if ignore_int {
            env.push(("FAKE_CLAUDE_IGNORE_INT".into(), "1".into()));
        }
        let r = Runner::new(
            Config {
                claude: exe.with_file_name("fake-claude"),
                hook_exe: exe,
                socket: dir.join("run/approvals.sock"),
                store_dir: dir.join("tasks"),
                env,
            },
            Arc::new(|| {}),
        )
        .unwrap();
        (Env { dir }, r)
    }

    fn pid(&self) -> Option<i32> {
        std::fs::read_to_string(self.dir.join("pid"))
            .ok()
            .and_then(|s| s.trim().parse().ok())
    }

    fn runs(&self) -> usize {
        std::fs::read_to_string(self.dir.join("args.log"))
            .map(|s| s.lines().count())
            .unwrap_or(0)
    }
}

impl Drop for Env {
    fn drop(&mut self) {
        if let Some(p) = self.pid() {
            // SAFETY: tidies the stand-in this check started.
            unsafe { libc::kill(p, libc::SIGKILL) };
        }
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn alive(pid: i32) -> bool {
    // SAFETY: signal 0 only asks whether the process exists.
    unsafe { libc::kill(pid, 0) == 0 }
}

fn tick_for(r: &mut Runner, secs: f64) {
    let end = Instant::now() + Duration::from_secs_f64(secs);
    while Instant::now() < end {
        r.tick();
        std::thread::sleep(Duration::from_millis(20));
    }
}

fn wait_pid(env: &Env, r: &mut Runner) -> i32 {
    let end = Instant::now() + Duration::from_secs(10);
    loop {
        r.tick();
        if let Some(p) = env.pid() {
            return p;
        }
        assert!(
            Instant::now() < end,
            "the stand-in never started: {:?} {:?}",
            r.tasks.first().map(|l| (&l.task.status, &l.task.entries)),
            r.problems
        );
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn a_claude_that_ignores_the_interrupt_is_ended_within_about_six_seconds() {
    let (env, mut r) = Env::new("ignore", true, "off");
    let id = r.new_task(&env.dir.join("proj"), "work");
    let pid = wait_pid(&env, &mut r);
    r.stop(&id);
    tick_for(&mut r, 8.0);
    assert!(!alive(pid), "Claude Code kept running after Stop");
    assert_eq!(r.tasks[0].task.status, Status::Stopped);
}

#[test]
fn a_hosted_claude_that_ignores_the_interrupt_is_ended_too() {
    let (env, mut r) = Env::new("hosted", true, "plain");
    let id = r.new_task(&env.dir.join("proj"), "work");
    let pid = wait_pid(&env, &mut r);
    r.stop(&id);
    tick_for(&mut r, 8.0);
    assert!(
        !alive(pid),
        "the hosted Claude Code kept running after Stop"
    );
    assert_eq!(r.tasks[0].task.status, Status::Stopped);
}

#[test]
fn a_message_waiting_for_a_run_never_restarts_a_stopped_task() {
    let (env, mut r) = Env::new("resend", false, "off");
    let id = r.new_task(&env.dir.join("proj"), "work");
    let pid = wait_pid(&env, &mut r);
    // The run ends just as a message is waiting for it; then Stop comes.
    r.queue_resend_for_test(&id, "late message", Duration::ZERO);
    // SAFETY: ends the stand-in this check started.
    unsafe { libc::kill(pid, libc::SIGKILL) };
    std::thread::sleep(Duration::from_millis(200));
    r.stop(&id);
    tick_for(&mut r, 4.0);
    assert_eq!(env.runs(), 1, "Claude Code was started again after Stop");
    let input = std::fs::read_to_string(env.dir.join("input.log")).unwrap_or_default();
    assert!(
        !input.contains("late message"),
        "a waiting message ran after Stop"
    );
    assert_eq!(r.tasks[0].task.status, Status::Stopped);
}
