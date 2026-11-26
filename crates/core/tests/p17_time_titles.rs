//! P17 (owner, 2026-11-25): a new task started in a project is named by its
//! start time on the PC's clock ("Nov 25, 5:50 PM"; the year only when it is
//! not this year). Chats outside projects keep their first-words names, and a
//! name the owner gave is never overwritten.

use mimir_core::runner::{ClockTime, Config, Runner, first_title, time_title, title_from};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

fn at(year: i32, month: u32, day: u32, hour: u32, minute: u32) -> ClockTime {
    ClockTime {
        year,
        month,
        day,
        hour,
        minute,
    }
}

#[test]
fn a_project_task_started_at_a_fixed_time_is_named_by_it() {
    let project = Path::new("/home/someone/Projects/garden-app");
    assert_eq!(
        first_title(
            project,
            "make the watering reminder louder",
            at(2026, 11, 25, 17, 50),
            2026
        ),
        "Nov 25, 5:50 PM"
    );
    // 12-hour style, both ends of the day.
    assert_eq!(time_title(at(2026, 11, 25, 0, 5), 2026), "Nov 25, 12:05 AM");
    assert_eq!(
        time_title(at(2026, 11, 25, 12, 0), 2026),
        "Nov 25, 12:00 PM"
    );
    assert_eq!(time_title(at(2026, 1, 3, 9, 31), 2026), "Jan 3, 9:31 AM");
}

#[test]
fn another_year_adds_the_year() {
    assert_eq!(
        time_title(at(2025, 12, 30, 17, 41), 2026),
        "Dec 30, 2025, 5:41 PM"
    );
    assert_eq!(
        first_title(
            Path::new("/home/someone/Projects/recipes-site"),
            "add a print button",
            at(2025, 12, 30, 17, 41),
            2026
        ),
        "Dec 30, 2025, 5:41 PM"
    );
}

#[test]
fn a_chat_outside_a_project_keeps_its_first_words_name() {
    let text = "which soil is best for tomatoes in pots";
    assert_eq!(
        first_title(
            &mimir_core::paths::chats_dir(),
            text,
            at(2026, 11, 25, 15, 2),
            2026
        ),
        title_from(text)
    );
    assert_eq!(
        first_title(
            Path::new("/somewhere/else/mimir/chats"),
            text,
            at(2026, 11, 25, 15, 2),
            2026
        ),
        title_from(text)
    );
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

/// A project task is named by its start time when the runner makes it, keeps
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
    let created = r
        .tasks
        .iter()
        .find(|l| l.task.id == id)
        .unwrap()
        .task
        .created_unix;
    let started = ClockTime::at(created);
    let named = time_title(started, started.year);
    assert_eq!(title(&r, &id), named, "a project task is named by its time");
    assert!(
        named.contains(", ") && (named.ends_with(" AM") || named.ends_with(" PM")),
        "{named}"
    );
    until(&mut r, "the first reply", |r| finished(r, &id, 1));
    assert_eq!(
        title(&r, &id),
        named,
        "Claude Code's own title never replaces the time name"
    );

    r.rename(&id, "Watering reminder");
    r.send(&id, "And the other folder too.");
    until(&mut r, "the second reply", |r| finished(r, &id, 2));
    assert_eq!(title(&r, &id), "Watering reminder");

    // A chat outside a project keeps today's first-words name.
    let chat = r.new_task(&chats, "which soil is best for tomatoes in pots");
    assert_eq!(
        title(&r, &chat),
        title_from("which soil is best for tomatoes in pots")
    );
    let _ = std::fs::remove_dir_all(&dir);
}
