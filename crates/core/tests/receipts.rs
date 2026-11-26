//! P11 (owner, 2026-11-25): every message, reply and note carries a time;
//! the owner's messages read Sent, Delivered (written to Claude Code's live
//! input) and Read (Claude Code echoed it back as it took it in); each reply
//! says the model and effort that answered it, and why when they changed.

use mimir_core::runner::{Config, Runner};
use mimir_core::task::{Entry, EntryMeta, Receipt, Task, WHY_NOT_REPORTED, WHY_PICKED};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

fn until(r: &mut Runner, what: &str, done: impl Fn(&Runner) -> bool) {
    let end = Instant::now() + Duration::from_secs(20);
    while !done(r) {
        r.tick();
        assert!(Instant::now() < end, "timed out waiting for: {what}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// A runner on the stand-in Claude, playing `runs` (file name in
/// tests/streams → name it plays as) in a folder of its own.
fn runner(name: &str, runs: &[(&str, &str)]) -> (Runner, PathBuf) {
    runner_with(name, runs, vec![])
}

fn runner_with(
    name: &str,
    runs: &[(&str, &str)],
    more_env: Vec<(String, String)>,
) -> (Runner, PathBuf) {
    let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
    let dir = std::env::temp_dir().join(format!("mimir-receipts-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("proj")).unwrap();
    let streams = dir.join("streams");
    std::fs::create_dir_all(&streams).unwrap();
    let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
    for (from, to) in runs {
        std::fs::copy(tests.join(from), streams.join(to)).unwrap();
    }
    let r = Runner::new(
        Config {
            claude: fake.clone(),
            hook_exe: fake,
            socket: dir.join("run/approvals.sock"),
            store_dir: dir.join("tasks"),
            env: [
                ("FAKE_CLAUDE_STREAMS".into(), streams.display().to_string()),
                (
                    "FAKE_CLAUDE_ARGS".into(),
                    dir.join("args.log").display().to_string(),
                ),
            ]
            .into_iter()
            .chain(more_env)
            .collect(),
        },
        Arc::new(|| {}),
    )
    .unwrap();
    (r, dir)
}

fn task<'a>(r: &'a Runner, id: &str) -> &'a Task {
    &r.tasks.iter().find(|l| l.task.id == id).unwrap().task
}

/// The small print of the owner's `n`th message (from 0).
fn you(r: &Runner, id: &str, n: usize) -> EntryMeta {
    let t = task(r, id);
    let i = (0..t.entries.len())
        .filter(|i| matches!(t.entries[*i], Entry::You(_)))
        .nth(n)
        .expect("that message");
    t.meta(i)
}

/// The small print of Claude's `n`th reply (from 0).
fn reply(r: &Runner, id: &str, n: usize) -> Option<EntryMeta> {
    let t = task(r, id);
    (0..t.entries.len())
        .filter(|i| matches!(t.entries[*i], Entry::Claude(_)))
        .nth(n)
        .map(|i| t.meta(i))
}

fn args(dir: &Path) -> String {
    std::fs::read_to_string(dir.join("args.log")).unwrap_or_default()
}

#[test]
fn a_message_is_delivered_then_read_when_claude_code_echoes_it() {
    let hold = std::env::temp_dir().join(format!("mimir-receipts-hold-{}", std::process::id()));
    let _ = std::fs::remove_file(&hold);
    let (mut r, dir) = runner_with(
        "first",
        &[("resume.jsonl", "run-1.jsonl")],
        vec![("FAKE_CLAUDE_HOLD_ECHO".into(), hold.display().to_string())],
    );
    let before = mimir_core::task::unix_ms();
    let id = r.new_task(&dir.join("proj"), "Tidy up the notes folder.");
    // Sent: the PC has it (the run it started may still be coming up).
    let m = you(&r, &id, 0);
    assert!(
        matches!(m.receipt, Some(Receipt::Sent | Receipt::Delivered)),
        "{m:?}"
    );
    assert!(m.at.is_some_and(|at| at >= before), "{m:?}");
    // Written to the run's live input; Claude Code has not taken it in yet.
    until(&mut r, "the message reads Delivered", |r| {
        you(r, &id, 0).receipt == Some(Receipt::Delivered)
    });
    for _ in 0..20 {
        r.tick();
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(you(&r, &id, 0).receipt, Some(Receipt::Delivered));
    // Claude Code takes it in and echoes it back.
    std::fs::write(&hold, "").unwrap();
    until(&mut r, "the message reads Read", |r| {
        you(r, &id, 0).receipt == Some(Receipt::Read)
    });
    let _ = std::fs::remove_file(&hold);
    assert!(
        args(&dir).contains("--replay-user-messages"),
        "{}",
        args(&dir)
    );
    until(&mut r, "the reply", |r| reply(r, &id, 0).is_some());
    let a = reply(&r, &id, 0).unwrap();
    assert!(a.at.is_some_and(|at| at >= before), "{a:?}");
    assert_eq!(a.model.as_deref(), Some("claude-opus-5-5"), "{a:?}");
    assert_eq!(
        a.receipt, None,
        "only the owner's messages have receipts: {a:?}"
    );
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_message_sent_while_claude_works_goes_delivered_then_read() {
    let (mut r, dir) = runner("midturn", &[("midturn.jsonl", "run-1.jsonl")]);
    let id = r.new_task(&dir.join("proj"), "Run sleep 3 then echo slept.");
    until(&mut r, "the first message is read and a step runs", |r| {
        you(r, &id, 0).receipt == Some(Receipt::Read)
            && task(r, &id)
                .entries
                .iter()
                .any(|e| matches!(e, Entry::Step(_)))
    });
    r.send_with(&id, "Also, what is 2+2?", &[]);
    assert_eq!(you(&r, &id, 1).receipt, Some(Receipt::Delivered));
    until(&mut r, "the second message reads Read", |r| {
        you(r, &id, 1).receipt == Some(Receipt::Read)
    });
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn an_old_task_file_without_times_loads_and_shows_no_time() {
    let mut t = Task::new("1-1-1".into(), "Old".into(), "/p".into(), 0);
    // Entries as an older Mímir saved them: no small print at all.
    t.entries.push(Entry::You("Fix the build".into()));
    t.entries.push(Entry::Claude("Fixed.".into()));
    t.entries.push(Entry::Note("You stopped this task.".into()));
    let mut v = serde_json::to_value(&t).unwrap();
    let o = v.as_object_mut().unwrap();
    assert!(!o.contains_key("entry_meta"), "{v}");
    for k in ["last_model", "run_effort", "switch_why"] {
        o.remove(k);
    }
    let old: Task = serde_json::from_value(v).expect("an old task file loads");
    assert_eq!(old.entries.len(), 3);
    assert_eq!(old.metas().len(), 3);
    for (i, m) in old.metas().iter().enumerate() {
        assert_eq!(m.at, None, "entry {i} shows no time: {m:?}");
        assert_eq!(m.receipt, None, "entry {i} has no receipt: {m:?}");
        assert!(m.is_empty(), "{m:?}");
    }
    // A new message after the old ones gets its time where it belongs.
    let mut old = old;
    old.add_you("And the tests");
    assert_eq!(old.meta(2).at, None);
    assert!(old.meta(3).at.is_some());
    assert_eq!(old.meta(3).receipt, Some(Receipt::Sent));
}

#[test]
fn each_reply_says_its_model_and_effort_and_why_they_switched() {
    let (mut r, dir) = runner(
        "switch",
        &[
            ("resume.jsonl", "run-1.jsonl"),
            ("resume.jsonl", "resume.jsonl"),
        ],
    );
    let id = r.new_task(&dir.join("proj"), "What did you make?");
    until(&mut r, "the first reply", |r| reply(r, &id, 0).is_some());
    let first = reply(&r, &id, 0).unwrap();
    assert_eq!(first.model.as_deref(), Some("claude-opus-5-5"));
    assert_eq!(first.effort, None, "the PC's default effort");
    assert_eq!(first.switched, None, "nothing before it to switch from");
    // The owner picks a higher effort; the next run starts with it.
    r.set_effort(&id, Some("high".into()));
    r.send_with(&id, "What is in hello.txt?", &[]);
    let sent = you(&r, &id, 1);
    assert_eq!(sent.model.as_deref(), Some("claude-opus-5-5"), "{sent:?}");
    assert_eq!(sent.effort.as_deref(), Some("high"), "{sent:?}");
    until(&mut r, "the second reply", |r| reply(r, &id, 1).is_some());
    let second = reply(&r, &id, 1).unwrap();
    assert_eq!(
        second.model.as_deref(),
        Some("claude-opus-5-5"),
        "{second:?}"
    );
    assert_eq!(second.effort.as_deref(), Some("high"), "{second:?}");
    assert_eq!(second.switched.as_deref(), Some(WHY_PICKED), "{second:?}");
    assert!(
        second
            .switched_why
            .as_deref()
            .is_some_and(|w| w.contains("You picked"))
    );
    assert!(task(&r, &id).switch_why.is_none(), "the reason was used up");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_switch_mimir_did_not_make_says_reason_not_reported() {
    use mimir_core::events::Event;
    let mut t = Task::new("1-1-2".into(), "Switch".into(), "/p".into(), 0);
    t.apply(&Event::Model("claude-opus-5-5".into()), 0);
    t.apply(&Event::Text("One.".into()), 0);
    t.apply(&Event::Model("claude-opus-5-5".into()), 0);
    t.apply(&Event::Text("Two.".into()), 0);
    assert_eq!(t.meta(1).switched, None, "the same model: no switch");
    t.apply(&Event::Model("claude-sonnet-5-5".into()), 0);
    t.apply(&Event::Text("Three.".into()), 0);
    let m = t.meta(2);
    assert_eq!(m.model.as_deref(), Some("claude-sonnet-5-5"));
    assert_eq!(m.switched.as_deref(), Some(WHY_NOT_REPORTED), "{m:?}");
    assert!(m.switched_why.is_some());
    // The usage limit moved it: that reason is given.
    t.switch_why = Some(mimir_core::task::WHY_LIMIT.into());
    t.switch_armed = true; // the run it moved to starts
    t.apply(&Event::Model("qwen3.6:35b".into()), 0);
    t.apply(&Event::Text("Four.".into()), 0);
    assert_eq!(t.meta(3).switched.as_deref(), Some("usage limit"));
}

// Fix round 1 (review 18:32).

/// A message that never reached Claude Code (dropped by Stop) stays Sent
/// when a later one is echoed, even one with the same words ("continue").
#[test]
fn a_message_that_never_reached_claude_code_is_never_marked_read() {
    use mimir_core::events::Event;
    let mut t = Task::new("1-1-3".into(), "Lost".into(), "/p".into(), 0);
    t.add_you("continue"); // lost: never written to a run
    t.add_you("continue"); // sent again, and this one reached it
    t.mark_delivered(1);
    t.apply(&Event::Echo("continue".into()), 0);
    assert_eq!(t.meta(0).receipt, Some(Receipt::Sent), "{:?}", t.metas());
    assert_eq!(t.meta(1).receipt, Some(Receipt::Read), "{:?}", t.metas());
    // A lost message before one that is echoed is not read either.
    t.add_you("Tidy the notes folder."); // lost
    t.add_you("What is 2+2?");
    t.mark_delivered(3);
    t.apply(&Event::Echo("What is 2+2?".into()), 0);
    assert_eq!(t.meta(2).receipt, Some(Receipt::Sent), "{:?}", t.metas());
    assert_eq!(t.meta(3).receipt, Some(Receipt::Read), "{:?}", t.metas());
}

/// A place that no longer holds a waiting message marks nothing: never the
/// oldest message still Sent (one that was lost).
#[test]
fn a_stale_place_never_marks_a_lost_message_delivered() {
    let mut t = Task::new("1-1-4".into(), "Stale".into(), "/p".into(), 0);
    t.add_you("A message Stop dropped."); // lost
    t.add_note("A note.");
    t.mark_delivered(1);
    t.mark_delivered(7);
    assert_eq!(t.meta(0).receipt, Some(Receipt::Sent), "{:?}", t.metas());
}

/// A message held for a run whose host was still starting, when that run is
/// ended before it came up, is not marked Delivered by the next run.
#[test]
fn a_message_held_for_a_run_that_went_away_is_not_delivered_by_the_next() {
    let dir0 = std::env::temp_dir().join(format!("mimir-receipts-slowhost-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir0);
    std::fs::create_dir_all(&dir0).unwrap();
    // A host that never answers: up for a second, then gone, so a run waits
    // that long for it and then runs here.
    let slow = dir0.join("slow-host");
    std::fs::write(&slow, "#!/bin/sh\nsleep 1\n").unwrap();
    let mut p = std::fs::metadata(&slow).unwrap().permissions();
    std::os::unix::fs::PermissionsExt::set_mode(&mut p, 0o755);
    std::fs::set_permissions(&slow, p).unwrap();
    // SAFETY: nextest runs each check in its own process.
    unsafe {
        std::env::set_var("MIMIR_HOSTS", "plain");
        std::env::set_var("MIMIR_TEST_PROGRAM", &slow);
        std::env::set_var("XDG_STATE_HOME", dir0.join("state"));
        std::env::set_var("XDG_RUNTIME_DIR", dir0.join("run"));
    }
    let hold = dir0.join("hold");
    let (mut r, dir) = runner_with(
        "held",
        &[("resume.jsonl", "run-1.jsonl")],
        vec![("FAKE_CLAUDE_HOLD_ECHO".into(), hold.display().to_string())],
    );
    let id = r.new_task(&dir.join("proj"), "Tidy up the notes folder.");
    assert_eq!(you(&r, &id, 0).receipt, Some(Receipt::Sent));
    // Its run is ended while its host is still starting: the message is lost.
    r.end_session(&id);
    r.send_with(&id, "What is in hello.txt?", &[]);
    until(&mut r, "the second message reads Delivered", |r| {
        you(r, &id, 1).receipt == Some(Receipt::Delivered)
    });
    assert_eq!(you(&r, &id, 0).receipt, Some(Receipt::Sent));
    std::fs::write(&hold, "").unwrap();
    until(&mut r, "the second message reads Read", |r| {
        you(r, &id, 1).receipt == Some(Receipt::Read)
    });
    assert_eq!(you(&r, &id, 0).receipt, Some(Receipt::Sent));
    let _ = std::fs::remove_dir_all(&dir);
    let _ = std::fs::remove_dir_all(&dir0);
}

/// A reason saved for the next run is not used by a model change inside the
/// run still answering; that run's change reads "reason not reported".
#[test]
fn a_reason_for_the_next_run_is_not_used_by_the_current_one() {
    use mimir_core::events::Event;
    let mut t = Task::new("1-1-5".into(), "Next".into(), "/p".into(), 0);
    t.apply(&Event::Model("claude-opus-5-5".into()), 0);
    t.apply(&Event::Text("One.".into()), 0);
    // The owner picks another model; it applies from the next run.
    t.switch_why = Some(WHY_PICKED.into());
    t.apply(&Event::Model("claude-sonnet-5-5".into()), 0);
    t.apply(&Event::Text("Two.".into()), 0);
    let m = t.meta(1);
    assert_eq!(m.switched.as_deref(), Some(WHY_NOT_REPORTED), "{m:?}");
    assert_eq!(
        t.switch_why.as_deref(),
        Some(WHY_PICKED),
        "kept for the next run"
    );
    // The next run starts with it.
    t.switch_armed = true;
    t.apply(&Event::Model("claude-opus-5-5".into()), 0);
    t.apply(&Event::Text("Three.".into()), 0);
    assert_eq!(t.meta(2).switched.as_deref(), Some(WHY_PICKED));
}
