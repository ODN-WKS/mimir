//! 0.09 item 3 (P09A): a phone's Delete takes the task, its conversation and its
//! files off the PC (`Call::DeleteTask`, handled like `RemoveTask`).

use mimir_core::runner::{Config, Runner};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

#[test]
fn p09_item3_delete_task_takes_the_task_its_log_and_its_files() {
    let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
    let dir = std::env::temp_dir().join(format!("mimir-p09a-delete-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("proj")).unwrap();
    let streams = dir.join("streams");
    std::fs::create_dir_all(&streams).unwrap();
    let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
    std::fs::copy(tests.join("live.jsonl"), streams.join("run-1.jsonl")).unwrap();
    let step = dir.join("step");
    std::fs::write(&step, "1000").unwrap();
    let mut r = Runner::new(
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
    .unwrap();
    let id = r.new_task(&dir.join("proj"), "Tidy up notes.txt.");
    let end = Instant::now() + Duration::from_secs(20);
    while r.tasks[0].busy() || r.tasks[0].task.entries.is_empty() {
        r.tick();
        assert!(Instant::now() < end, "the task never finished its turn");
        std::thread::sleep(Duration::from_millis(10));
    }
    r.tick();
    let kept = |d: &std::path::Path| std::fs::read_dir(d).map(|e| e.count()).unwrap_or_default();
    assert!(
        kept(&dir.join("tasks")) > 0,
        "the task's log was never written"
    );
    r.remove(&id);
    assert!(r.tasks.is_empty(), "the task is still listed");
    assert_eq!(kept(&dir.join("tasks")), 0, "its log is still on the PC");
    let _ = std::fs::remove_dir_all(&dir);
}
