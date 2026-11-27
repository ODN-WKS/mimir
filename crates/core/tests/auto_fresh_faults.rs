//! The automatic fresh start's faults (P41 fix 1): it never starts by
//! itself at start-up or on an idle task, the owner's messages always come
//! first and are never lost, the hidden hand-off ask leaves the owner's undo
//! and plan alone, it never asks the owner anything they did not see, a
//! chat never reads or writes a shared hand-off, and nothing happens before
//! the look at sessions in other apps.

use mimir_core::proto::Mode;
use mimir_core::runner::{
    AUTO_FRESH_FROM, AUTO_FRESH_LINE, AUTO_FRESH_OWNER_LINE, Config, HANDOFF_ASK, Notice, Runner,
};
use mimir_core::task::{Entry, Status};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

struct Env {
    dir: PathBuf,
    streams: PathBuf,
    step: PathBuf,
    args: PathBuf,
    input: PathBuf,
    r: Runner,
    /// Look at sessions in other apps on every tick (the service does).
    scan: bool,
    /// Finished notices seen.
    finished: usize,
    /// A message the owner queued until Claude is done (the service's).
    queued: Option<(String, String)>,
}

/// The resumed recording with three harmless lines in front, so a hold at
/// six lines lets a first turn end (six lines) but not a resumed one.
fn resumed() -> String {
    let r = record("resume.jsonl");
    let pad = r
        .lines()
        .find(|l| l.contains("rate_limit_event"))
        .unwrap()
        .to_string();
    let mut lines: Vec<String> = r.lines().map(str::to_string).collect();
    for _ in 0..3 {
        lines.insert(1, pad.clone());
    }
    lines.join("\n") + "\n"
}

fn record(from: &str) -> String {
    let tests = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/streams");
    std::fs::read_to_string(tests.join(from)).unwrap().replace(
        "\\\"cache_read_input_tokens\\\":27443",
        "\\\"cache_read_input_tokens\\\":127443",
    )
}

impl Env {
    /// Every recorded reply reports a conversation of about 127,000 tokens.
    fn new(name: &str) -> Self {
        Self::with(name, "0")
    }

    /// `speed`: the fake's pace (1 = as recorded: it stays up a moment after
    /// its answer, as Claude Code does).
    fn with(name: &str, speed: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("mimir-autof-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("proj")).unwrap();
        std::fs::create_dir_all(dir.join("mimir/chats")).unwrap();
        let streams = dir.join("streams");
        std::fs::create_dir_all(&streams).unwrap();
        for n in 1..=5 {
            std::fs::write(streams.join(format!("run-{n}.jsonl")), record("live.jsonl")).unwrap();
        }
        std::fs::write(streams.join("resume.jsonl"), resumed()).unwrap();
        let step = dir.join("step");
        std::fs::write(&step, "6").unwrap();
        let args = dir.join("args.log");
        let input = dir.join("input.log");
        let r = Self::runner(&dir, &streams, &step, &args, &input, speed);
        Self {
            dir,
            streams,
            step,
            args,
            input,
            r,
            scan: true,
            finished: 0,
            queued: None,
        }
    }

    fn runner(
        dir: &std::path::Path,
        streams: &std::path::Path,
        step: &std::path::Path,
        args: &std::path::Path,
        input: &std::path::Path,
        speed: &str,
    ) -> Runner {
        let fake = PathBuf::from(env!("CARGO_BIN_EXE_fake-claude"));
        Runner::new(
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
                    ("FAKE_CLAUDE_SPEED".into(), speed.into()),
                ],
            },
            Arc::new(|| {}),
        )
        .unwrap()
    }

    /// Mímir closes and opens again.
    fn reopen(&mut self) {
        self.r.shutdown();
        self.r = Self::runner(
            &self.dir,
            &self.streams,
            &self.step,
            &self.args,
            &self.input,
            "0",
        );
    }

    fn runs(&self) -> usize {
        std::fs::read_to_string(&self.args)
            .unwrap_or_default()
            .lines()
            .count()
    }

    fn run_args(&self, n: usize) -> String {
        std::fs::read_to_string(&self.args)
            .unwrap_or_default()
            .lines()
            .nth(n)
            .unwrap_or_default()
            .to_string()
    }

    fn sent(&self) -> String {
        std::fs::read_to_string(&self.input).unwrap_or_default()
    }

    fn asked(&self) -> usize {
        self.sent().matches(HANDOFF_ASK).count()
    }

    /// One service tick: the look at other apps, the runner, then the
    /// messages that waited for Claude to be done.
    fn tick(&mut self) {
        let id = self.r.tasks.first().map(|l| l.task.id.clone());
        self.r.owner_waiting = self.queued.iter().map(|(t, _)| t.clone()).collect();
        if self.scan {
            self.r.outside_scanned(&Default::default());
        }
        self.r.tick();
        for n in std::mem::take(&mut self.r.notices) {
            if matches!(n, Notice::Finished { .. }) {
                self.finished += 1;
            }
        }
        if let (Some(id), Some((_, text))) = (id, self.queued.clone()) {
            let l = &self.r.tasks[0];
            let free = l.task.status == Status::Done
                && l.asks.is_empty()
                && !l.task.plan_waiting()
                && !l.holds_queued();
            if free {
                self.queued = None;
                self.r.send_with(&id, &text, &[]);
            }
        }
    }

    fn until(&mut self, what: &str, done: impl Fn(&Self) -> bool) {
        let end = Instant::now() + Duration::from_secs(30);
        while !done(self) {
            self.tick();
            assert!(Instant::now() < end, "timed out waiting for: {what}");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn ticks_for(&mut self, d: Duration) {
        let end = Instant::now() + d;
        while Instant::now() < end {
            self.tick();
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

    fn at(&self, f: impl Fn(&Entry) -> bool) -> Option<usize> {
        self.r.tasks[0].task.entries.iter().position(f)
    }

    /// A task in `folder` whose first turn (past 100,000) has ended; a
    /// resumed turn after it is held until [`Env::release`].
    fn done_task(&mut self, folder: &str, mode: Mode, handoff: bool) -> String {
        self.done_task_then(folder, mode, handoff, |_| {})
    }

    /// The same, with `during` done while the first turn is on.
    fn done_task_then(
        &mut self,
        folder: &str,
        mode: Mode,
        handoff: bool,
        during: impl FnOnce(&mut Self),
    ) -> String {
        let project = self.dir.join(folder);
        if handoff {
            self.r
                .handoff_files
                .insert(project.display().to_string(), "HANDOFF.md".into());
        }
        std::fs::write(&self.step, "0").unwrap();
        let id = self
            .r
            .new_task_with(&project, "Tidy up notes.txt.", &[], mode, None, None);
        self.until("the first turn starts", |e| e.r.tasks[0].busy());
        during(self);
        std::fs::write(&self.step, "6").unwrap();
        self.until("the first turn ends", |e| e.finished >= 1);
        id
    }

    fn release(&self) {
        std::fs::write(&self.step, "1000").unwrap();
    }
}

const DIVIDER: &str = "Fresh start at ";
/// Long enough for the automatic steps to have acted.
const SETTLE: Duration = Duration::from_secs(4);

#[test]
fn never_at_start_up_or_for_an_idle_or_archived_task() {
    let mut e = Env::new("idle");
    e.done_task("proj", Mode::NoAsk, true);
    // An old task (saved past the limit) that nobody is working on.
    let mut quiet = e.r.tasks[0].task.clone();
    e.ticks_for(Duration::from_millis(10));
    e.r.shutdown();
    quiet
        .entries
        .retain(|x| !matches!(x, Entry::Note(n) if n == AUTO_FRESH_LINE));
    quiet.auto_fresh = 0;
    assert!(quiet.context_used >= AUTO_FRESH_FROM, "not past the limit");
    let store = mimir_core::store::Store::new(e.dir.join("tasks")).unwrap();
    store.save(&quiet).unwrap();
    e.r = Env::runner(&e.dir, &e.streams, &e.step, &e.args, &e.input, "0");
    e.ticks_for(SETTLE);
    assert_eq!(e.notes(AUTO_FRESH_LINE), 0, "warned at start-up");
    assert_eq!(e.notes(DIVIDER), 0);
    assert_eq!(e.runs(), 1, "Claude started at start-up");
    // Archived, and opened again: still nothing.
    quiet.archived = true;
    e.r.shutdown();
    store.save(&quiet).unwrap();
    e.reopen();
    e.ticks_for(Duration::from_secs(1));
    assert_eq!(e.runs(), 1);
    assert_eq!(e.notes(DIVIDER), 0);
}

#[test]
fn a_message_queued_during_the_hand_off_goes_to_the_new_session_with_the_brief() {
    let mut e = Env::with("queued", "1");
    let id = e.done_task("proj", Mode::NoAsk, true);
    e.until("the hand-off turn", |e| e.runs() >= 2 && e.asked() == 1);
    e.queued = Some((id, "Also sort it by date.".into()));
    e.release();
    e.until("the queued message goes", |e| e.queued.is_none());
    e.ticks_for(Duration::from_secs(1));
    let divider = e.at(|x| matches!(x, Entry::Note(n) if n.starts_with(DIVIDER)));
    let mine = e.at(|x| matches!(x, Entry::You(t) if t == "Also sort it by date."));
    assert!(
        divider.is_some() && mine.is_some() && divider < mine,
        "not after the fresh start: {:?}",
        e.r.tasks[0].task.entries
    );
    let line = e
        .sent()
        .lines()
        .find(|l| l.contains("Also sort it by date."))
        .unwrap_or_default()
        .to_string();
    assert!(
        line.contains("Continue from the hand-off"),
        "no brief: {line}"
    );
    assert!(
        !e.run_args(2).contains("--resume"),
        "the old session took it"
    );
}

#[test]
fn a_message_typed_during_the_hand_off_is_answered_and_handed_off_too() {
    let mut e = Env::new("typed");
    // The hand-off turn takes in the owner's message too before it answers.
    let first = resumed().lines().next().unwrap().to_string();
    let two_in = resumed();
    std::fs::write(e.streams.join("resume.jsonl"), format!("{first}\n{two_in}")).unwrap();
    std::fs::write(e.streams.join("resume-second.jsonl"), resumed()).unwrap();
    let id = e.done_task("proj", Mode::NoAsk, true);
    e.until("the hand-off turn", |e| e.runs() >= 2 && e.asked() == 1);
    e.r.send_with(&id, "Also add a title.", &[]);
    e.release();
    e.until("the fresh start", |e| e.notes(DIVIDER) == 1);
    e.ticks_for(Duration::from_secs(1));
    let line = e
        .sent()
        .lines()
        .find(|l| l.contains("Also add a title."))
        .unwrap_or_default()
        .to_string();
    assert!(!line.is_empty(), "the message never reached Claude");
    assert!(
        !line.contains(HANDOFF_ASK),
        "it went in as part of the hand-off ask: {line}"
    );
    assert_eq!(
        e.asked(),
        2,
        "the hand-off was not saved again after the owner's message"
    );
    let mine = e
        .at(|x| matches!(x, Entry::You(t) if t == "Also add a title."))
        .unwrap();
    let divider = e
        .at(|x| matches!(x, Entry::Note(n) if n.starts_with(DIVIDER)))
        .unwrap();
    assert!(
        e.r.tasks[0].task.entries[mine..divider]
            .iter()
            .any(|x| matches!(x, Entry::Claude(_))),
        "unanswered"
    );
}

#[test]
fn the_hidden_ask_leaves_undo_redo_and_the_plan_alone() {
    let mut e = Env::new("sides");
    e.done_task_then("proj", Mode::NoAsk, true, |e| {
        let t = &mut e.r.tasks[0].task;
        t.undo_told = Some("The owner undid the last changes.".into());
        t.redo = Some("snap".into());
        t.plan_doc = Some("plan.md".into());
    });
    let points = e.r.tasks[0].task.checkpoints.len();
    e.release();
    e.until("the fresh start", |e| e.notes(DIVIDER) == 1);
    let t = &e.r.tasks[0].task;
    assert_eq!(
        t.undo_told.as_deref(),
        Some("The owner undid the last changes.")
    );
    assert_eq!(t.redo.as_deref(), Some("snap"));
    assert_eq!(t.plan_doc.as_deref(), Some("plan.md"));
    assert_eq!(
        t.checkpoints.len(),
        points,
        "an undo point for the hidden ask"
    );
    assert!(!e.sent().contains("undid"), "told with the hidden ask");
}

#[test]
fn in_ask_or_plan_mode_it_never_asks_by_itself_and_leaves_it_to_the_owner() {
    for (name, mode) in [("askmode", Mode::Ask), ("planmode", Mode::Plan)] {
        let mut e = Env::new(name);
        e.done_task("proj", mode, true);
        e.ticks_for(SETTLE);
        assert_eq!(e.runs(), 1, "{name}: the hand-off was asked unseen");
        assert_eq!(e.notes(DIVIDER), 0, "{name}");
        assert_eq!(
            e.notes(AUTO_FRESH_LINE),
            0,
            "{name}: says it starts by itself"
        );
        assert_eq!(e.notes(AUTO_FRESH_OWNER_LINE), 1, "{name}");
        assert_eq!(e.r.tasks[0].task.status, Status::Done, "{name}");
        assert!(e.r.tasks[0].asks.is_empty(), "{name}");
    }
}

#[test]
fn a_chat_never_reads_or_writes_a_shared_hand_off() {
    let mut e = Env::new("chat");
    let id = e.done_task("mimir/chats", Mode::NoAsk, true);
    e.until("the fresh start", |e| e.notes(DIVIDER) == 1);
    assert_eq!(e.runs(), 1, "the chat was asked to write a hand-off");
    assert_eq!(e.asked(), 0);
    let brief = e.r.tasks[0].task.brief.clone().unwrap_or_default();
    assert!(
        brief.contains("Tidy up notes.txt."),
        "not this chat's own: {brief}"
    );
    assert!(
        !brief.contains("HANDOFF.md") && !brief.contains("hand-off document"),
        "{brief}"
    );
    e.r.send_with(&id, "Next, the shopping list.", &[]);
    e.until("the next run", |e| e.runs() == 2);
    let line = e
        .sent()
        .lines()
        .find(|l| l.contains("Next, the shopping list."))
        .unwrap_or_default()
        .to_string();
    assert!(line.contains("Tidy up notes.txt."), "{line}");
    assert!(!line.contains("hand-off document"), "{line}");
}

#[test]
fn nothing_happens_before_the_look_at_other_apps() {
    let mut e = Env::new("scan");
    e.scan = false;
    e.done_task("proj", Mode::NoAsk, true);
    e.ticks_for(SETTLE);
    assert_eq!(e.runs(), 1, "asked before the look at other apps");
    e.scan = true;
    e.until("the hand-off turn", |e| e.asked() == 1);
}

#[test]
fn the_hidden_turn_sends_no_finished_notice_and_the_owners_turn_keeps_its_own() {
    let mut e = Env::new("notice");
    e.done_task("proj", Mode::NoAsk, true);
    assert_eq!(e.finished, 1, "the owner's own turn");
    e.release();
    e.until("the fresh start", |e| e.notes(DIVIDER) == 1);
    e.ticks_for(Duration::from_secs(1));
    assert_eq!(e.asked(), 1);
    assert_eq!(e.finished, 1, "a finished notice for the hidden turn");
}

#[test]
fn a_message_queued_during_the_owners_turn_goes_before_the_hand_off() {
    let mut e = Env::new("queuedfirst");
    e.done_task_then("proj", Mode::NoAsk, true, |e| {
        let id = e.r.tasks[0].task.id.clone();
        e.queued = Some((id, "Then the shopping list.".into()));
    });
    e.release();
    e.until("the hand-off turn", |e| e.asked() == 1);
    let sent = e.sent();
    let mine = sent.find("Then the shopping list.").expect("never sent");
    let ask = sent.find(HANDOFF_ASK).unwrap();
    assert!(mine < ask, "the hand-off went first");
    assert_eq!(e.notes(DIVIDER), 0);
}
