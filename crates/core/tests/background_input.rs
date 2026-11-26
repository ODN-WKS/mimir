//! Owner-found (2026-11-25): messages sent while Claude Code was still up
//! for background work it started were dropped. Mímir closed Claude Code's
//! input as soon as a turn ended, and a hosted run took later lines without
//! a word. Now the input stays open while background work runs, so such a
//! message reaches the same run.

use mimir_core::runner::{Config, Runner};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

fn lines(path: &std::path::Path) -> Vec<String> {
    std::fs::read_to_string(path)
        .unwrap_or_default()
        .lines()
        .map(str::to_string)
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
fn a_message_sent_while_background_work_runs_reaches_the_same_run() {
    let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
    let dir = std::env::temp_dir().join(format!("mimir-bg-input-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("proj")).unwrap();
    let streams = dir.join("streams");
    std::fs::create_dir_all(&streams).unwrap();
    let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
    std::fs::copy(
        tests.join("background-message.jsonl"),
        streams.join("run-1.jsonl"),
    )
    .unwrap();
    std::fs::copy(tests.join("resume.jsonl"), streams.join("resume.jsonl")).unwrap();
    let args = dir.join("args.log");
    let input = dir.join("input.log");
    let mut r = Runner::new(
        Config {
            claude: fake.clone(),
            hook_exe: fake,
            socket: dir.join("run/approvals.sock"),
            store_dir: dir.join("tasks"),
            env: vec![
                ("FAKE_CLAUDE_STREAMS".into(), streams.display().to_string()),
                ("FAKE_CLAUDE_ARGS".into(), args.display().to_string()),
                ("FAKE_CLAUDE_INPUT".into(), input.display().to_string()),
            ],
        },
        Arc::new(|| {}),
    )
    .unwrap();
    let id = r.new_task(&dir.join("proj"), "Start the long check in the background.");
    until(&mut r, "the first turn's answer", |_| {
        lines(&input).len() == 1
    });
    // Well past the quiet that closes an idle run's input.
    let wait = Instant::now() + Duration::from_secs(5);
    while Instant::now() < wait {
        r.tick();
        std::thread::sleep(Duration::from_millis(20));
    }
    r.send_with(&id, "A message while the check runs.", &[]);
    until(&mut r, "the message is read", |_| lines(&input).len() >= 2);
    assert!(
        lines(&input)[1].contains("A message while the check runs."),
        "{:?}",
        lines(&input)
    );
    assert_eq!(
        lines(&args).len(),
        1,
        "a second Claude Code was started instead: {:?}",
        lines(&args)
    );
    let _ = std::fs::remove_dir_all(&dir);
}
