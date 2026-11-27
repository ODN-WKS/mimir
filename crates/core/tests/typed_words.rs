//! Words a person typed reach Claude Code marked as typed (`origin` human);
//! everything else goes unmarked. Each check compares the exact input line.

use mimir_core::events::Picture;
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

fn marked(text: &str) -> String {
    format!(
        r#"{{"message":{{"content":"{text}","role":"user"}},"origin":{{"kind":"human"}},"type":"user"}}"#
    )
}

fn unmarked(text: &str) -> String {
    format!(r#"{{"message":{{"content":"{text}","role":"user"}},"type":"user"}}"#)
}

/// The `n`th line Claude Code was given, once it has it and the turn is over.
fn line(r: &mut Runner, log: &std::path::Path, n: usize) -> String {
    let end = Instant::now() + Duration::from_secs(20);
    while lines(log).len() < n || r.tasks[0].busy() {
        r.tick();
        assert!(Instant::now() < end, "no line {n}: {:?}", lines(log));
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(lines(log).len(), n, "{:?}", lines(log));
    lines(log)[n - 1].clone()
}

#[test]
fn only_typed_words_are_marked() {
    let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
    let dir = std::env::temp_dir().join(format!("mimir-typed-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("proj")).unwrap();
    let streams = dir.join("streams");
    std::fs::create_dir_all(&streams).unwrap();
    let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
    for (to, from) in [
        ("run-1.jsonl", "live.jsonl"),
        ("run-2.jsonl", "live.jsonl"),
        ("run-3.jsonl", "live.jsonl"),
        ("resume.jsonl", "resume.jsonl"),
    ] {
        std::fs::copy(tests.join(from), streams.join(to)).unwrap();
    }
    let log = dir.join("input.log");
    let mut r = Runner::new(
        Config {
            claude: fake.clone(),
            hook_exe: fake,
            socket: dir.join("run/approvals.sock"),
            store_dir: dir.join("tasks"),
            env: vec![
                ("FAKE_CLAUDE_STREAMS".into(), streams.display().to_string()),
                ("FAKE_CLAUDE_INPUT".into(), log.display().to_string()),
            ],
        },
        Arc::new(|| {}),
    )
    .unwrap();

    // A new task's typed first message.
    r.next_typed = true;
    let id = r.new_task(&dir.join("proj"), "Tidy up notes.txt.");
    assert_eq!(line(&mut r, &log, 1), marked("Tidy up notes.txt."));

    // The mark was for that one message: the next, from Mímir, has none.
    r.send(&id, "Carry on.");
    assert_eq!(line(&mut r, &log, 2), unmarked("Carry on."));

    // Typed, with a picture.
    r.next_typed = true;
    let picture = Picture {
        media_type: "image/png".into(),
        base64: "AAAA".into(),
    };
    r.send_with(&id, "What is this", &[picture]);
    assert_eq!(
        line(&mut r, &log, 3),
        concat!(
            r#"{"message":{"content":[{"source":{"data":"AAAA","media_type":"image/png","type":"base64"},"type":"image"},"#,
            r#"{"text":"What is this","type":"text"}],"role":"user"},"origin":{"kind":"human"},"type":"user"}"#
        )
    );

    // Mímir adds the undo notice to the typed words: the line is not marked.
    r.tasks[0].task.undo_told = Some("Files were put back.".into());
    r.next_typed = true;
    r.send(&id, "Try that again.");
    assert_eq!(
        line(&mut r, &log, 4),
        unmarked(r"Files were put back.\n\nTry that again.")
    );

    // The same for the summary brief after a switch.
    r.hand_to(&id, None, "a check");
    r.tasks[0].task.brief = Some("Summary so far.".into());
    r.tasks[0].task.brief_pending = true;
    r.next_typed = true;
    r.send(&id, "And the title.");
    assert_eq!(
        line(&mut r, &log, 5),
        unmarked(r"Summary so far.\n\nNow the owner says:\nAnd the title.")
    );

    // After it, typed words are marked again.
    r.next_typed = true;
    r.send(&id, "Thanks.");
    assert_eq!(line(&mut r, &log, 6), marked("Thanks."));

    // A carried-on task's first message is Mímir's.
    r.new_task_continued(
        &dir.join("proj"),
        "Notes (continued)",
        "Carry on from here.",
        None,
        mimir_core::task::Mode::Ask,
        None,
    );
    let end = Instant::now() + Duration::from_secs(20);
    while lines(&log).len() < 7 {
        r.tick();
        assert!(Instant::now() < end, "no line 7");
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(lines(&log)[6], unmarked("Carry on from here."));
    let _ = std::fs::remove_dir_all(&dir);
}
