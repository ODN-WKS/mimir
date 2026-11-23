//! "task_reply_options": a task's model and effort change from the next
//! start of Claude Code (a phone sends `SetModel` / `SetEffort` just before
//! its message). A busy task keeps running as it is; the task's state shows
//! the new values at once.

use mimir_core::runner::{Config, Runner};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

fn pair(run: &[String], flag: &str, value: &str) -> bool {
    run.windows(2).any(|w| w[0] == flag && w[1] == value)
}

fn runs(path: &std::path::Path) -> Vec<Vec<String>> {
    std::fs::read_to_string(path)
        .unwrap_or_default()
        .lines()
        .filter_map(|l| serde_json::from_str(l).ok())
        .collect()
}

fn until(r: &mut Runner, what: &str, done: impl Fn(&Runner) -> bool) {
    let end = Instant::now() + Duration::from_secs(20);
    while !done(r) {
        r.tick();
        assert!(Instant::now() < end, "timed out waiting for: {what}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn a_busy_task_keeps_its_settings_until_the_next_start_and_then_shows_and_uses_the_new_ones() {
    let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
    let dir = std::env::temp_dir().join(format!("mimir-reply-opts-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("proj")).unwrap();
    let streams = dir.join("streams");
    std::fs::create_dir_all(&streams).unwrap();
    let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
    std::fs::copy(tests.join("live.jsonl"), streams.join("run-1.jsonl")).unwrap();
    std::fs::copy(tests.join("resume.jsonl"), streams.join("resume.jsonl")).unwrap();
    let step = dir.join("step");
    std::fs::write(&step, "0").unwrap();
    let args = dir.join("args.log");
    let mut r = Runner::new(
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
    let id = r.new_task(&dir.join("proj"), "Tidy up notes.txt.");
    until(&mut r, "the task works", |r| {
        r.tasks[0].busy() && !runs(&args).is_empty()
    });
    r.set_model(&id, Some("haiku".into()));
    r.set_effort(&id, Some("high".into()));
    // The state shows the new values; the running Claude Code is untouched.
    assert_eq!(r.tasks[0].task.model.as_deref(), Some("haiku"));
    assert_eq!(r.tasks[0].task.effort.as_deref(), Some("high"));
    assert!(r.tasks[0].busy(), "a busy task was ended");
    let first = runs(&args);
    assert_eq!(first.len(), 1, "{first:?}");
    assert!(!pair(&first[0], "--effort", "high"), "{:?}", first[0]);
    std::fs::write(&step, "1000").unwrap();
    until(&mut r, "the turn ends", |r| !r.tasks[0].busy());
    r.send_with(&id, "And then say what you made.", &[]);
    until(&mut r, "the next start", |_| runs(&args).len() >= 2);
    let second = runs(&args)[1].clone();
    assert!(pair(&second, "--effort", "high"), "{second:?}");
    assert!(pair(&second, "--model", "haiku"), "{second:?}");
    let _ = std::fs::remove_dir_all(&dir);
}
