//! 0.09 item 3 review: Delete takes the task's Claude Code transcript too, so the
//! conversation can't come back through "earlier sessions"; a session the person
//! opened from their own earlier ones is left alone.

use mimir_core::runner::{Config, Runner};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

fn runner(dir: &std::path::Path) -> Runner {
    let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
    let streams = dir.join("streams");
    std::fs::create_dir_all(&streams).unwrap();
    let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
    std::fs::copy(tests.join("live.jsonl"), streams.join("run-1.jsonl")).unwrap();
    let step = dir.join("step");
    std::fs::write(&step, "1000").unwrap();
    Runner::new(
        Config {
            claude: fake.clone(),
            hook_exe: fake,
            socket: dir.join("run/approvals.sock"),
            store_dir: dir.join("tasks"),
            env: vec![
                ("FAKE_CLAUDE_STREAMS".into(), streams.display().to_string()),
                ("FAKE_CLAUDE_STEP_FILE".into(), step.display().to_string()),
            ],
        },
        Arc::new(|| {}),
    )
    .unwrap()
}

#[test]
fn p09_fix4_delete_takes_the_transcript_and_files_but_not_an_opened_session() {
    let dir = std::env::temp_dir().join(format!("mimir-p09a-fix4-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let proj = dir.join("proj");
    std::fs::create_dir_all(&proj).unwrap();
    unsafe { std::env::set_var("CLAUDE_CONFIG_DIR", dir.join("claude")) };
    let mut r = runner(&dir);
    let id = r.new_task(&proj, "Tidy up notes.txt.");
    let end = Instant::now() + Duration::from_secs(20);
    while r.tasks[0].busy() || r.tasks[0].task.session_id.is_none() {
        r.tick();
        assert!(Instant::now() < end, "the task never ran");
        std::thread::sleep(Duration::from_millis(10));
    }
    let sid = r.tasks[0].task.session_id.clone().unwrap();
    // What Claude Code keeps of this conversation, and the task's attachments.
    let line =
        "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Tidy up notes.txt.\"}}\n";
    let file = mimir_core::sessions::session_file(&proj, &sid);
    std::fs::create_dir_all(file.parent().unwrap()).unwrap();
    std::fs::write(&file, line).unwrap();
    // A different session of the person's own, opened as a task here.
    let own = mimir_core::sessions::session_file(&proj, "own-old-session");
    std::fs::write(&own, line).unwrap();
    let past = mimir_core::sessions::list(&proj);
    let opened = past
        .iter()
        .find(|s| s.id == "own-old-session")
        .unwrap()
        .clone();
    let opened_id = r.open_past(&proj, &opened).unwrap();
    let files = dir.join("task-files").join(&id);
    std::fs::create_dir_all(&files).unwrap();
    std::fs::write(files.join("1-a.txt"), "x").unwrap();

    r.delete(&id);
    assert!(
        !file.exists(),
        "the conversation's transcript is still on the PC"
    );
    assert!(!files.exists(), "its files are still on the PC");
    assert!(
        mimir_core::sessions::list(&proj)
            .iter()
            .all(|s| s.id != sid),
        "the conversation can still be opened from earlier sessions"
    );
    r.delete(&opened_id);
    assert!(
        own.exists(),
        "a session the person opened from their own was deleted"
    );
    let _ = std::fs::remove_dir_all(&dir);
}
