//! The automatic fresh start's hidden hand-off turn never asks the owner
//! anything they did not see (P41 fix 1, finding 5): a step it would need
//! the owner for is refused, no "needs you" shows, and the fresh start is
//! left to the owner with the one warning line.

use mimir_core::proto::Mode;
use mimir_core::runner::{
    AUTO_FRESH_LINE, AUTO_FRESH_OWNER_LINE, Config, HANDOFF_ASK, Notice, Runner,
};
use mimir_core::task::{Entry, Status};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

#[test]
fn a_step_the_hidden_hand_off_would_ask_about_is_refused_and_left_to_the_owner() {
    let dir = std::env::temp_dir().join(format!("mimir-autoask-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("proj")).unwrap();
    let streams = dir.join("streams");
    std::fs::create_dir_all(&streams).unwrap();
    let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../core/tests/streams");
    let first = std::fs::read_to_string(tests.join("live.jsonl"))
        .unwrap()
        .replace(
            "\\\"cache_read_input_tokens\\\":27443",
            "\\\"cache_read_input_tokens\\\":127443",
        );
    std::fs::write(streams.join("run-1.jsonl"), first).unwrap();
    // The hand-off turn edits a file and its hook asks; refused, it says so.
    let ask = std::fs::read_to_string(tests.join("edit_ask.jsonl"))
        .unwrap()
        .replace("\"expect\": \"allow\"", "\"expect\": \"deny\"");
    std::fs::write(streams.join("resume.jsonl"), ask).unwrap();
    let input = dir.join("input.log");
    let hooks = dir.join("hooks.log");
    let exe = PathBuf::from(env!("CARGO_BIN_EXE_mimir"));
    let mut r = Runner::new(
        Config {
            claude: exe.with_file_name("fake-claude"),
            hook_exe: exe,
            socket: dir.join("run/approvals.sock"),
            store_dir: dir.join("tasks"),
            env: vec![
                ("FAKE_CLAUDE_STREAMS".into(), streams.display().to_string()),
                ("FAKE_CLAUDE_INPUT".into(), input.display().to_string()),
                ("FAKE_CLAUDE_HOOKS".into(), hooks.display().to_string()),
            ],
        },
        Arc::new(|| {}),
    )
    .unwrap();
    let project = dir.join("proj");
    r.handoff_files
        .insert(project.display().to_string(), "HANDOFF.md".into());
    r.new_task_with(&project, "Tidy up notes.txt.", &[], Mode::NoAsk, None, None);
    let mut needs_you = 0;
    let mut finished = 0;
    let mut tick = |r: &mut Runner| {
        r.outside_scanned(&Default::default());
        r.tick();
        for n in std::mem::take(&mut r.notices) {
            match n {
                Notice::NeedsYou { .. } => needs_you += 1,
                Notice::Finished { .. } => finished += 1,
            }
        }
        assert_ne!(r.tasks[0].task.status, Status::NeedsYou, "asked the owner");
        std::thread::sleep(Duration::from_millis(10));
    };
    let end = Instant::now() + Duration::from_secs(30);
    while std::fs::read_to_string(&hooks)
        .unwrap_or_default()
        .is_empty()
    {
        tick(&mut r);
        assert!(Instant::now() < end, "the hand-off turn never asked");
    }
    let end = Instant::now() + Duration::from_secs(5);
    while Instant::now() < end {
        tick(&mut r);
    }
    assert!(
        std::fs::read_to_string(&input)
            .unwrap()
            .contains(HANDOFF_ASK)
    );
    assert_eq!(std::fs::read_to_string(&hooks).unwrap().trim(), "deny");
    assert_eq!(needs_you, 0);
    assert_eq!(finished, 1, "only the owner's own turn");
    assert!(r.tasks[0].asks.is_empty());
    let t = &r.tasks[0].task;
    assert!(
        t.session_id.is_some(),
        "started fresh without a saved hand-off"
    );
    let line = |text: &str| {
        t.entries
            .iter()
            .filter(|e| matches!(e, Entry::Note(n) if n == text))
            .count()
    };
    assert_eq!(line(AUTO_FRESH_LINE), 0, "still says it starts by itself");
    assert_eq!(line(AUTO_FRESH_OWNER_LINE), 1);
    assert!(
        !t.entries
            .iter()
            .any(|e| matches!(e, Entry::Note(n) if n.starts_with("Fresh start at "))),
    );
    r.shutdown();
    let _ = std::fs::remove_dir_all(&dir);
}
