//! Automatic fresh start (owner, 2026-11-26 22:39): past 100,000 tokens a
//! task shows a warning line, lets the running turn end, has the session
//! save the hand-off, then carries on in a new session in the same chat.
//! Nothing running is cut, and the limit of 3 in a row stays.

use mimir_core::runner::{
    AUTO_FRESH_FROM, AUTO_FRESH_LINE, Config, FRESH_ROW_FULL, HANDOFF_ASK, Runner,
};
use mimir_core::task::{Entry, Status};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

struct Env {
    dir: PathBuf,
    step: PathBuf,
    args: PathBuf,
    input: PathBuf,
    r: Runner,
}

impl Env {
    /// `big`: every recorded reply reports a conversation of about 127,000
    /// tokens (the recordings say about 27,000).
    fn new(name: &str, big: bool) -> Self {
        let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
        let dir = std::env::temp_dir().join(format!("mimir-auto-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("proj")).unwrap();
        let streams = dir.join("streams");
        std::fs::create_dir_all(&streams).unwrap();
        let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
        let copy = |from: &str, to: &str| {
            let mut s = std::fs::read_to_string(tests.join(from)).unwrap();
            if big {
                s = s.replace(
                    "\\\"cache_read_input_tokens\\\":27443",
                    "\\\"cache_read_input_tokens\\\":127443",
                );
            }
            std::fs::write(streams.join(to), s).unwrap();
        };
        for n in 1..=5 {
            copy("live.jsonl", &format!("run-{n}.jsonl"));
        }
        copy("resume.jsonl", "resume.jsonl");
        let step = dir.join("step");
        std::fs::write(&step, "1000").unwrap();
        let args = dir.join("args.log");
        let input = dir.join("input.log");
        let r = Runner::new(
            Config {
                claude: fake.clone(),
                hook_exe: fake,
                socket: dir.join("run/approvals.sock"),
                store_dir: dir.join("tasks"),
                env: vec![
                    ("FAKE_CLAUDE_STREAMS".into(), streams.display().to_string()),
                    ("FAKE_CLAUDE_STEP_FILE".into(), step.display().to_string()),
                    ("FAKE_CLAUDE_ARGS".into(), args.display().to_string()),
                    ("FAKE_CLAUDE_INPUT".into(), input.display().to_string()),
                ],
            },
            Arc::new(|| {}),
        )
        .unwrap();
        Self {
            dir,
            step,
            args,
            input,
            r,
        }
    }

    fn runs(&self) -> usize {
        std::fs::read_to_string(&self.args)
            .unwrap_or_default()
            .lines()
            .count()
    }

    fn sent(&self) -> String {
        std::fs::read_to_string(&self.input).unwrap_or_default()
    }

    fn until(&mut self, what: &str, done: impl Fn(&Runner) -> bool) {
        let end = Instant::now() + Duration::from_secs(30);
        while !done(&self.r) {
            self.r.tick();
            assert!(Instant::now() < end, "timed out waiting for: {what}");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn ticks(&mut self, n: usize) {
        for _ in 0..n {
            self.r.tick();
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn notes(&self, words: &str) -> usize {
        self.r.tasks[0]
            .task
            .entries
            .iter()
            .filter(|e| matches!(e, Entry::Note(n) if n.starts_with(words)))
            .count()
    }

    fn own(&self) -> usize {
        self.r.tasks[0]
            .task
            .entries
            .iter()
            .filter(|e| matches!(e, Entry::You(_)))
            .count()
    }

    /// A task whose first turn has ended and whose Claude Code has exited.
    fn done_task(&mut self) -> String {
        let id = self
            .r
            .new_task(&self.dir.join("proj"), "Tidy up notes.txt.");
        self.until("the first turn ends", |r| {
            r.tasks[0].task.status == Status::Done && !r.tasks[0].running()
        });
        id
    }
}

const DIVIDER: &str = "Fresh start at ";

#[test]
fn the_limit_is_100k_and_the_words_are_plain() {
    assert_eq!(AUTO_FRESH_FROM, 100_000);
    let line = AUTO_FRESH_LINE.to_lowercase();
    for jargon in ["token", "context", "session", "100"] {
        assert!(!line.contains(jargon), "{jargon} in {AUTO_FRESH_LINE}");
    }
    assert!(HANDOFF_ASK.contains("hand-off"));
    assert!(HANDOFF_ASK.contains("background"));
}

#[test]
fn past_100k_it_warns_then_saves_the_hand_off_then_starts_fresh() {
    let mut e = Env::new("once", true);
    std::fs::write(&e.step, "0").unwrap();
    e.r.new_task(&e.dir.join("proj"), "Tidy up notes.txt.");
    // The reply with the big size is out; the turn has not ended yet.
    std::fs::write(&e.step, "5").unwrap();
    e.until("the warning line", |r| {
        r.tasks[0]
            .task
            .entries
            .iter()
            .any(|x| matches!(x, Entry::Note(n) if n == AUTO_FRESH_LINE))
    });
    e.ticks(30);
    assert!(e.r.tasks[0].busy(), "the running turn was cut");
    assert_eq!(e.runs(), 1);
    assert!(
        !e.sent().contains(HANDOFF_ASK),
        "asked before the turn ended"
    );
    assert_eq!(e.notes(DIVIDER), 0);

    // The turn ends: the same session is asked to save the hand-off.
    std::fs::write(&e.step, "1000").unwrap();
    let args = e.args.clone();
    e.until("the hand-off turn", |_| {
        std::fs::read_to_string(&args)
            .unwrap_or_default()
            .lines()
            .count()
            >= 2
    });
    let second = std::fs::read_to_string(&e.args).unwrap();
    assert!(
        second.lines().nth(1).unwrap().contains("--resume"),
        "the hand-off is saved by the session that knows the work: {second}"
    );
    assert!(e.sent().contains(HANDOFF_ASK), "{}", e.sent());
    assert_eq!(e.own(), 1, "Mímir's ask is not shown as the owner's words");

    // Once that turn has ended, the fresh start follows.
    e.until("the fresh start", |r| {
        r.tasks[0]
            .task
            .entries
            .iter()
            .any(|x| matches!(x, Entry::Note(n) if n.starts_with(DIVIDER)))
    });
    e.ticks(30);
    let t = &e.r.tasks[0].task;
    assert_eq!(t.session_id, None);
    assert!(t.brief_pending, "the next message reads the hand-off first");
    assert_eq!(t.context_used, 0);
    assert_eq!(e.notes(AUTO_FRESH_LINE), 1, "warned once");
    assert_eq!(e.notes(DIVIDER), 1);
    // Nothing starts by itself: the owner's next message goes to it.
    assert_eq!(e.runs(), 2);
    assert_eq!(e.r.tasks[0].task.status, Status::Done);
}

#[test]
fn it_keeps_doing_so_every_time() {
    let mut e = Env::new("again", true);
    let id = e.done_task();
    e.until("the first fresh start", |r| {
        r.tasks[0].task.session_id.is_none() && !r.tasks[0].running()
    });
    assert_eq!(e.notes(DIVIDER), 1);
    e.r.send_with(&id, "Thanks, carry on.", &[]);
    e.until("the second fresh start", |r| {
        r.tasks[0]
            .task
            .entries
            .iter()
            .filter(|x| matches!(x, Entry::Note(n) if n.starts_with(DIVIDER)))
            .count()
            == 2
    });
    e.ticks(30);
    assert_eq!(e.notes(AUTO_FRESH_LINE), 2);
    assert_eq!(e.notes(DIVIDER), 2);
    // The second new session read the hand-off: its first line says so.
    let runs = std::fs::read_to_string(&e.args).unwrap();
    let third = runs.lines().nth(2).unwrap();
    assert!(!third.contains("--resume"), "{third}");
    assert!(
        e.sent().contains("Continue from the hand-off"),
        "{}",
        e.sent()
    );
}

#[test]
fn below_100k_nothing_happens() {
    let mut e = Env::new("small", false);
    e.done_task();
    e.ticks(50);
    assert_eq!(e.notes(AUTO_FRESH_LINE), 0);
    assert_eq!(e.notes(DIVIDER), 0);
    assert_eq!(e.runs(), 1);
    assert!(e.r.tasks[0].task.session_id.is_some());
}

#[test]
fn three_in_a_row_still_waits_for_the_owner() {
    let mut e = Env::new("row", false);
    e.done_task();
    e.r.tasks[0].task.fresh_row = 3;
    e.r.tasks[0].task.context_used = 120_000;
    e.ticks(50);
    assert_eq!(e.notes(AUTO_FRESH_LINE), 1);
    assert_eq!(e.notes(FRESH_ROW_FULL), 1, "said once");
    assert_eq!(e.notes(DIVIDER), 0);
    assert_eq!(
        e.runs(),
        1,
        "no hand-off asked for a start that can't follow"
    );
    assert!(e.r.tasks[0].task.session_id.is_some());
}

#[test]
fn a_whisper_never_starts_fresh_by_itself() {
    let mut e = Env::new("whisper", false);
    e.done_task();
    e.r.tasks[0].task.incognito = true;
    e.r.tasks[0].task.context_used = 120_000;
    e.ticks(50);
    assert_eq!(e.notes(AUTO_FRESH_LINE), 0);
    assert_eq!(e.notes(DIVIDER), 0);
    assert_eq!(e.runs(), 1);
}

#[test]
fn a_stopped_hand_off_turn_is_not_followed_by_a_fresh_start() {
    let mut e = Env::new("stopped", false);
    let id = e.done_task();
    std::fs::write(&e.step, "0").unwrap();
    e.r.tasks[0].task.context_used = 120_000;
    e.until("the hand-off turn starts", |r| r.tasks[0].busy());
    e.r.stop(&id);
    e.until("the run ends", |r| !r.tasks[0].running());
    e.ticks(50);
    assert_eq!(e.notes(DIVIDER), 0);
    assert!(e.r.tasks[0].task.session_id.is_some());
    assert_eq!(e.runs(), 2, "not asked again until the owner writes");
}
