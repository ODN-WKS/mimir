//! A new task is named by the first words of its first message (owner,
//! 2026-11-27; it replaced the start-time names of 2026-11-25). A task still
//! carrying a start-time name is renamed the same way when it is loaded, and
//! a name the owner gave is never overwritten.

use mimir_core::runner::{ClockTime, Config, Runner, time_title, title_from};
use mimir_core::store::Store;
use mimir_core::task::{Entry, Task};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

#[test]
fn a_task_saved_under_its_start_time_is_named_by_its_first_words() {
    let dir = std::env::temp_dir().join(format!("mimir-old-titles-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let store = Store::new(dir.join("tasks")).unwrap();
    let created = 1_795_000_000;
    let c = ClockTime::at(created);
    let dated = time_title(c, c.year);
    for (id, title, by_owner) in [
        ("old", dated.as_str(), false),
        ("kept", dated.as_str(), true),
        ("named", "Watering reminder", false),
    ] {
        let mut t = Task::new(
            id.into(),
            title.into(),
            "/home/someone/garden".into(),
            created,
        );
        t.title_by_owner = by_owner;
        t.entries = vec![Entry::You("make the reminder louder".into())];
        store.save(&t).unwrap();
    }
    let config = || Config {
        claude: PathBuf::from("/nonexistent"),
        hook_exe: PathBuf::from("/nonexistent"),
        socket: dir.join("run/approvals.sock"),
        store_dir: dir.join("tasks"),
        env: vec![],
    };
    let mut r = Runner::new(config(), Arc::new(|| {})).unwrap();
    assert_eq!(title(&r, "old"), title_from("make the reminder louder"));
    assert_eq!(title(&r, "kept"), dated);
    assert_eq!(title(&r, "named"), "Watering reminder");
    r.tick();
    drop(r);
    let (saved, _) = Store::new(dir.join("tasks")).unwrap().load_all();
    let old = saved.iter().find(|t| t.id == "old").unwrap();
    assert_eq!(
        old.title,
        title_from("make the reminder louder"),
        "saved too"
    );
    let _ = std::fs::remove_dir_all(&dir);
}

fn until(r: &mut Runner, what: &str, done: impl Fn(&Runner) -> bool) {
    let end = Instant::now() + Duration::from_secs(20);
    while !done(r) {
        r.tick();
        assert!(Instant::now() < end, "timed out waiting for: {what}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

fn title(r: &Runner, id: &str) -> String {
    r.tasks
        .iter()
        .find(|l| l.task.id == id)
        .unwrap()
        .task
        .title
        .clone()
}

fn finished(r: &Runner, id: &str, replies: usize) -> bool {
    let l = r.tasks.iter().find(|l| l.task.id == id).unwrap();
    !l.busy()
        && l.task
            .entries
            .iter()
            .filter(|e| matches!(e, mimir_core::task::Entry::Claude(_)))
            .count()
            >= replies
}

/// A project task is named by its first words when the runner makes it, keeps
/// that name when Claude Code gives the session a title of its own, and a
/// name the owner gives it stays after more messages.
#[test]
fn a_renamed_task_keeps_its_name_after_more_messages() {
    let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
    let dir = std::env::temp_dir().join(format!("mimir-p17-titles-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let proj = dir.join("proj");
    std::fs::create_dir_all(&proj).unwrap();
    let chats = dir.join("mimir/chats");
    std::fs::create_dir_all(&chats).unwrap();
    let streams = dir.join("streams");
    std::fs::create_dir_all(&streams).unwrap();
    let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
    std::fs::copy(tests.join("live.jsonl"), streams.join("run-1.jsonl")).unwrap();
    std::fs::copy(tests.join("live.jsonl"), streams.join("run-2.jsonl")).unwrap();
    std::fs::copy(tests.join("resume.jsonl"), streams.join("resume.jsonl")).unwrap();
    // Claude Code's own title for the session, as it writes it in its history.
    let claude = dir.join("claude");
    // SAFETY: set before the runner starts; nothing else in this file reads it.
    unsafe { std::env::set_var("CLAUDE_CONFIG_DIR", &claude) };
    let history = claude
        .join("projects")
        .join(mimir_core::sessions::folder_name(&proj));
    std::fs::create_dir_all(&history).unwrap();
    std::fs::write(
        history.join("5f1c2e7a-6b3d-4c8e-9a10-11ee0c0f1a2b.jsonl"),
        "{\"type\":\"ai-title\",\"aiTitle\":\"Tidy the notes folder\"}\n",
    )
    .unwrap();
    let step = dir.join("step");
    std::fs::write(&step, "100000").unwrap();
    let mut r = Runner::new(
        Config {
            claude: fake.clone(),
            hook_exe: fake,
            socket: dir.join("run/approvals.sock"),
            store_dir: dir.join("tasks"),
            env: vec![("FAKE_CLAUDE_STREAMS".into(), streams.display().to_string())],
        },
        Arc::new(|| {}),
    )
    .unwrap();

    let id = r.new_task(&proj, "Tidy up notes.txt.");
    let named = title_from("Tidy up notes.txt.");
    assert_eq!(title(&r, &id), named, "a task is named by its first words");
    until(&mut r, "the first reply", |r| finished(r, &id, 1));
    assert_eq!(
        title(&r, &id),
        named,
        "Claude Code's own title never replaces a project task's name"
    );

    r.rename(&id, "Watering reminder");
    r.send(&id, "And the other folder too.");
    until(&mut r, "the second reply", |r| finished(r, &id, 2));
    assert_eq!(title(&r, &id), "Watering reminder");

    // A chat outside a project is named the same way.
    let chat = r.new_task(&chats, "which soil is best for tomatoes in pots");
    assert_eq!(
        title(&r, &chat),
        title_from("which soil is best for tomatoes in pots")
    );
    let _ = std::fs::remove_dir_all(&dir);
}
