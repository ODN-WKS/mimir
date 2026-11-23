//! The service: it owns the tasks (`Runner`) and every duty that must go on
//! while no window is open (Phone too, Remote Control keepers, messages
//! that wait until Claude is done, finish summaries, desktop notifications,
//! git facts). Windows and phones are clients: they send `Call`s and keep a
//! mirror of the state from the numbered `Event`s it sends.
//!
//! One thread owns everything. It wakes on a channel (process output,
//! permission requests, calls) or every 250 ms for its timers.

use crate::approvals::Answer;
use crate::needs::AskRecord;
use crate::queue::{SUMMARY_PROMPT, Waiting, waiting_now, wants_summary, with_files};
use crate::runner::{self, Notice, Runner, Wake};
use crate::settings::{self, Settings};
use crate::sockets::SessionMsg;
use crate::task::{Status, Task, WeeklyUse};
use mimir_proto::{
    Call, CallMsg, Choice, Entry, Event, EventMsg, Filed, GitInfo, ItemAnswer, NeedsKind,
    NeedsYouItem, PROTO, Placed, QueuedMessage, StatusFacts, StatusPage, TaskHeader, TidyKind,
    TidyRow,
};
use std::collections::{HashMap, HashSet, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender, SyncSender, channel, sync_channel};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

/// How often every task's conversation is looked at for writes from
/// elsewhere (the phone, claude.ai, the Claude app, a terminal).
const OUTSIDE_SCAN: Duration = Duration::from_secs(1);
/// How often running Claude Code processes are looked for.
const PROCESS_SCAN: Duration = Duration::from_secs(5);
/// A followed conversation unchanged this long has finished its turn.
const KEEPER_QUIET: Duration = Duration::from_secs(90);

/// How often the service looks at its timers while something is going on
/// (a window or phone connected, a task working or waiting).
const TICK: Duration = Duration::from_millis(250);
/// The longest an idle service sleeps: no window or phone, no task at work.
/// Any message wakes it at once (PCS-16, PCR-20).
const IDLE_WAIT: Duration = Duration::from_secs(10);
/// Durable events kept for clients that reconnect.
const RING: usize = 10_000;
/// Events queued for one window or phone at most (PCS-21, PCR-08). A
/// client that falls this far behind is disconnected; it reconnects and
/// gets the whole picture again (its resync).
const CLIENT_QUEUE: usize = 4096;
/// Git facts of the project a window shows are refreshed this often.
const GIT_EVERY: Duration = Duration::from_secs(5);
/// Every task is compared in full this often, besides the ones known to
/// have changed.
const FULL_EVERY: Duration = Duration::from_secs(5);
/// Projects' run logs are read this often, for checks that start failing.
const CHECKS_EVERY: Duration = Duration::from_secs(15);
/// How often what Claude is doing on this PC is looked at (Labs).
/// A finished task still counts as "this conversation" for this long.
const STILL_OPEN_S: i64 = 10 * 60;

pub struct Config {
    pub runner: runner::Config,
    /// Claude Code, for Phone too and Remote Control.
    pub claude: PathBuf,
    pub version: String,
}

/// What reaches the service thread.
pub enum Inbound {
    /// A client: its events go to `events`; `wake` tells it to look.
    Connect {
        client: u64,
        events: SyncSender<EventMsg>,
        wake: Wake,
    },
    Disconnect {
        client: u64,
    },
    Call {
        client: u64,
        msg: CallMsg,
    },
    /// Something happened (process output, a permission request).
    Wake,
    /// Stop (SIGTERM from systemd, or the check that started it).
    Stop,
    /// From the phone link: pairing, and paired phones' sessions.
    Link(mimir_link::server::LinkEvent),
    /// From a Claude session on the session socket (`task`: the asking
    /// Mímir task, if it is one); the reply goes to `reply`.
    Session {
        msg: SessionMsg,
        task: Option<String>,
        reply: Sender<String>,
    },
    /// A call answered later, off the service's thread (voice, listening).
    Later {
        client: u64,
        call_id: u64,
        result: Result<serde_json::Value, String>,
    },
    /// An update's audio is made (its file name), or why not.
    Audio {
        card: String,
        result: Result<String, String>,
    },
    /// The chat engine's next piece or finished answer.
    Talk(crate::talk::Out),
    /// A call that waited off the service's thread for the PC's model to
    /// wake: it is answered now.
    Woken {
        client: u64,
        msg: CallMsg,
    },
    /// The PC's model is asleep and is being woken for this task.
    Waking {
        task: String,
    },
}

/// The running service: send it `Inbound`s.
pub struct Handle {
    pub tx: Sender<Inbound>,
    thread: Option<JoinHandle<()>>,
}

impl Handle {
    /// Waits for the service thread to end (after `Call::Shutdown`).
    pub fn join(&mut self) {
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

/// Starts the service on its own thread. Fails as `Runner::new` does
/// (for example when another Mímir holds the approvals socket).
pub fn start(cfg: Config) -> std::io::Result<Handle> {
    let (tx, rx) = channel::<Inbound>();
    let wake_tx = tx.clone();
    let self_tx = tx.clone();
    let wake: Wake = Arc::new(move || {
        let _ = wake_tx.send(Inbound::Wake);
    });
    let store_dir = cfg.runner.store_dir.clone();
    let runner = Runner::new(cfg.runner, wake.clone())?;
    let claude = cfg.claude;
    let version = cfg.version;
    let thread = std::thread::Builder::new()
        .name("mimir-service".into())
        .spawn(move || {
            let mut s = Service::new(runner, claude, version, wake, rx, self_tx);
            s.store_dir = store_dir;
            s.run();
        })?;
    Ok(Handle {
        tx,
        thread: Some(thread),
    })
}

struct Client {
    events: SyncSender<EventMsg>,
    wake: Wake,
    /// A paired phone (its link key): it gets only phone events.
    phone: Option<String>,
    /// Whose it is: the owner (the window, and the owner's phones) or
    /// another person's id, read from the PC's own record of the paired
    /// phone when the link opened.
    person: String,
    /// It said Hello (so it gets events).
    ready: bool,
    focused: bool,
    /// The features it listed in its Hello: it gets only the events it knows.
    features: Vec<String>,
    /// For anyone but the owner, the number their events carry: their own
    /// count, never the service's, which would show how busy the owner is.
    own_seq: u64,
}

impl Client {
    /// Whether this client may get `event`: a phone only phone events, and
    /// anyone only the events whose feature it knows.
    /// The number an event carries for this client.
    fn number(&mut self, global: u64) -> u64 {
        if self.person == crate::people::OWNER {
            global
        } else {
            self.own_seq += 1;
            self.own_seq
        }
    }

    fn gets(&self, event: &Event) -> bool {
        (self.phone.is_none() || event.for_phone())
            && (self.person == crate::people::OWNER || event.for_other_people())
            && event
                .feature()
                .is_none_or(|f| self.features.iter().any(|k| k == f))
    }
}

impl Client {
    /// Whether this client takes a chat event of `person`'s (a chat's
    /// pieces, answer and changes go to that person's devices only).
    fn gets_chat(&self, person: &str, event: &Event) -> bool {
        self.person == person
            && (self.phone.is_none() || event.for_phone())
            && event
                .feature()
                .is_none_or(|f| self.features.iter().any(|k| k == f))
    }
}

/// Said when a picture is sent and no PC model (and no Online answer) can
/// look at it.
const NO_SIGHT_PC: &str = "None of the models on your PC can look at pictures right now, so this one wasn't read. Try again without the picture.";

/// A `Call::Talk`'s parts.
struct TalkArgs {
    /// Whose chat it is (the asking device's person).
    person: String,
    chat_id: String,
    turn_id: String,
    turns: Vec<mimir_proto::ChatTurn>,
    lookup: Option<String>,
    sources: Vec<mimir_proto::ChatSource>,
    effort: Option<String>,
    ground_rules: Option<String>,
    task: Option<String>,
    chat_only: bool,
}

/// The features this PC offers in its Welcome (a subset of
/// `mimir_proto::FEATURES` that grows as each part is in place).
const OFFERED: &[&str] = &[
    "search",
    "lookup",
    "talk",
    "chat_sync",
    "chat_only",
    "truth",
    "engines",
    "chat_cut",
    "chat_model",
    "people",
    "models_changed",
    "chat_pictures",
    "chat_files",
    "task_files",
    "finished_events",
];

/// How long a task must stay idle before its work counts as ended.
const FINISH_AFTER: Duration = Duration::from_secs(1);

/// What the service knows about whether a task is at work (for `Finished`).
struct FinishState {
    /// It was at work and its end isn't told yet.
    working: bool,
    idle_since: Option<Instant>,
}

/// An id for one end of work. Never the same twice: the time (so a restart
/// can't repeat one) and a count (so two in a second can't), not the task's
/// length, which an undo or a reread can bring back.
fn finished_id(task: &str, unix: i64, n: u64) -> String {
    format!("fin:{task}:{unix}:{n}")
}

/// How many ends of work a snapshot lists.
const FINISHED_KEPT: usize = 20;

/// What a client was last told about a task.
#[derive(Default)]
struct Sent {
    header: Option<TaskHeader>,
    entries: Vec<Entry>,
    live: String,
}

impl Drop for Service {
    fn drop(&mut self) {}
}

/// A permission request from a session Mímir doesn't run.
struct Outside {
    ask_id: String,
    ask: crate::approve::OutsideAsk,
    created: i64,
    /// To the waiting hook: "allow", "always", "deny" or "pc".
    reply: Sender<String>,
}

struct Service {
    /// The PC's chat engine (talk.rs).
    talk: crate::talk::Talk,
    /// The truth last sent (truth.rs): its revision, the hash of what it
    /// shows, when it was built, and the snapshot itself.
    truth: Option<mimir_proto::Truth>,
    truth_seen: u64,
    truth_at: Option<Instant>,
    /// Each project's core checks, as last read.
    checks_cache: HashMap<String, mimir_proto::CheckFacts>,
    /// The engine's line, read again every 10 s.
    engine_words: Option<(Instant, String, bool)>,
    /// The chats kept on this PC, opened when first needed (talk_store.rs).
    chats: Option<crate::talk_store::TalkStore>,
    chats_unlock: crate::talk_store::Unlock,
    /// When the engine's pick was last looked at, and whether a look runs.
    engine_looked: Option<Instant>,
    started: Instant,
    engine_busy: Arc<std::sync::atomic::AtomicBool>,
    runner: Runner,
    settings: Settings,
    claude: PathBuf,
    version: String,
    wake: Wake,
    rx: Receiver<Inbound>,
    clients: HashMap<u64, Client>,
    run_id: String,
    seq: u64,
    ring: VecDeque<EventMsg>,
    sent: HashMap<String, Sent>,
    /// Tasks a call or a process may have changed since the last look.
    touched: HashSet<String>,
    last_full: Instant,
    /// Calls that waited for the PC's model to wake and are now replayed
    /// (client, call id): they go straight through.
    replay_ok: HashSet<(u64, u64)>,
    /// The one thread that wakes the PC's model for waiting calls, in the
    /// order they came.
    /// The ModelManager: wakes the PC's model for calls that need it, one
    /// at a time, with a bounded queue (PCS-01).
    wake_worker: Option<mimir_hub::workers::Pool<(u64, CallMsg, Option<String>)>>,
    /// Calls waiting for it: (client, call id, task).
    wake_pending: Vec<(u64, u64, Option<String>)>,
    /// Waiting calls a Stop or Pause for their task cancelled.
    wake_cancelled: HashSet<(u64, u64)>,
    /// The model is being woken for work that came on its own (a limit that
    /// ran out): when it started, and whether the waiting is over.
    model_waker: Option<(Instant, Arc<std::sync::atomic::AtomicBool>)>,
    /// Claude's usage limit is out until then (seconds since 1970).
    claude_out_until: Option<i64>,
    /// Tasks whose conversation goes on outside this service: task id →
    /// how far its transcript was read.
    outside_seen: HashMap<String, u64>,
    /// Tasks whose conversation was followed from outside.
    outside_followed: HashSet<String>,
    /// Tasks this service ran Claude for at the last look.
    own_running: HashSet<String>,
    /// When every task's conversation was last looked at.
    outside_scan: Option<Instant>,
    /// Session ids of Claude Code processes running on this PC, and when
    /// they were looked for.
    procs: (HashSet<String>, Option<Instant>),
    queued: HashMap<String, QueuedMessage>,
    git: HashMap<String, GitInfo>,
    git_watch: Option<String>,
    git_checked: HashMap<String, Instant>,
    git_tx: Sender<(String, GitInfo)>,
    git_rx: Receiver<(String, GitInfo)>,
    /// Problems not yet dismissed in a window.
    problems: Vec<String>,
    usage_sent: (Option<WeeklyUse>, Option<WeeklyUse>, Option<i64>),
    commands_sent: (Vec<String>, Vec<(String, String)>),
    stop: bool,
    /// Set when it runs as its own process: removing it stops the service.
    pid_file: Option<PathBuf>,
    /// 75 after a new version was put in place: systemd starts it again.
    exit_code: i32,
    /// A downloaded new version waiting until no task works.
    /// The phone link (only in the service's own process).
    link: Option<mimir_link::server::Server>,
    /// Link connection → client number.
    link_conns: HashMap<u64, u64>,
    /// The task store's folder (the phone's per-file rules go beside it).
    store_dir: PathBuf,
    /// Asks from Claude sessions and failing checks, open and answered.
    asks: Vec<AskRecord>,
    /// Sessions waiting for an ask's answer.
    ask_waiters: HashMap<String, Vec<Sender<String>>>,
    /// Permission requests from Claude sessions Mímir doesn't run, each
    /// with its waiting hook (`mimir approve`).
    outside: Vec<Outside>,
    /// The needs-you list clients were last sent.
    needs_sent: Vec<NeedsYouItem>,
    /// Whether each task was working at the last look (None: not looked
    /// yet, so a restart tells nobody about work that ended before it).
    was_working: Option<HashMap<String, FinishState>>,
    /// Counts the ends of work this run, for their ids.
    finished_n: u64,
    /// The last few ends of work, newest last (a Whisper is never kept).
    finished: std::collections::VecDeque<mimir_proto::FinishedItem>,
    /// Message ids already done, and their replies.
    msg_ids: crate::chat::MsgIds,
    /// The general chat's previous message.
    last_chat: Option<crate::route::Previous>,
    /// When each task last finished a turn (seconds since 1970).
    finished_at: HashMap<String, i64>,
    /// "Tidy projects…" moves waiting for Claude to stop (None: not read
    /// from disk yet), and when they were last looked at.
    tidy_waiting: Option<Vec<TidyRow>>,
    tidy_checked: Option<Instant>,
    /// Each project's features: passing or not, last time the logs were read.
    check_state: HashMap<(String, String), bool>,
    checks_read: Option<Instant>,
    /// For answers and audio made on other threads.
    self_tx: Sender<Inbound>,
    /// How often phones search the web through this PC (kept gentle).
    searches: crate::searx::Gentle,
    /// The phone's weather and encyclopedia lookups (see `lookup`).
    lookups: crate::lookup::Gentle,
    /// Tasks whose next finished turn is the summary of the work.
    summary_pending: HashSet<String>,
    /// What each other person set for themselves (the owner's are in
    /// `settings`).
    people_book: crate::people::Book,
    /// Whose each recent chat answer is (answer id → person), so its pieces
    /// and its end go to that person's devices only, and no other person
    /// can stop it or ask how it stands.
    answer_person: HashMap<String, (String, Option<Instant>)>,
}

impl Service {
    fn new(
        runner: Runner,
        claude: PathBuf,
        version: String,
        wake: Wake,
        rx: Receiver<Inbound>,
        self_tx: Sender<Inbound>,
    ) -> Self {
        let talk = {
            let tx = std::sync::Mutex::new(self_tx.clone());
            let wake = wake.clone();
            crate::talk::Talk::start(move |o| {
                if let Ok(tx) = tx.lock() {
                    let _ = tx.send(Inbound::Talk(o));
                }
                wake();
            })
        };
        let (settings, settings_problem) = Settings::load();
        // The window's keys move out to window.json the first time.
        let _ = settings::load_window();
        let (git_tx, git_rx) = channel();
        let mut s = Service {
            runner,
            settings,
            claude,
            version,
            wake,
            rx,
            clients: HashMap::new(),
            run_id: run_id(),
            seq: 0,
            ring: VecDeque::new(),
            sent: HashMap::new(),
            touched: HashSet::new(),
            last_full: Instant::now(),
            replay_ok: HashSet::new(),
            wake_worker: None,
            wake_pending: vec![],
            wake_cancelled: HashSet::new(),
            model_waker: None,
            outside_seen: HashMap::new(),
            own_running: HashSet::new(),
            outside_followed: HashSet::new(),
            outside_scan: None,
            procs: (HashSet::new(), None),
            claude_out_until: None,
            queued: HashMap::new(),
            git: HashMap::new(),
            git_watch: None,
            git_checked: HashMap::new(),
            git_tx,
            git_rx,
            problems: vec![],
            usage_sent: (None, None, None),
            commands_sent: (vec![], vec![]),
            stop: false,
            pid_file: None,
            exit_code: 0,
            link: None,
            link_conns: HashMap::new(),
            store_dir: crate::store::Store::default_dir(),
            asks: crate::needs::load_asks(&crate::needs::asks_path()),
            ask_waiters: HashMap::new(),
            outside: vec![],
            needs_sent: vec![],
            was_working: None,
            finished_n: 0,
            finished: std::collections::VecDeque::new(),
            msg_ids: crate::chat::MsgIds::load(),
            last_chat: None,
            finished_at: HashMap::new(),
            tidy_waiting: None,
            tidy_checked: None,
            check_state: HashMap::new(),
            checks_read: None,
            talk,
            chats: None,
            // A locked store asks the keyring again on every request (PCE-05);
            // one ask at a time, off the service's thread.
            chats_unlock: crate::talk_store::Unlock::new(Duration::ZERO),
            truth: None,
            truth_seen: 0,
            truth_at: None,
            checks_cache: HashMap::new(),
            engine_words: None,
            engine_looked: None,
            started: Instant::now(),
            engine_busy: Arc::default(),
            self_tx,
            searches: Default::default(),
            lookups: Default::default(),
            summary_pending: HashSet::new(),
            people_book: crate::people::Book::load(),
            answer_person: HashMap::new(),
        };
        // Incognito tasks don't survive the service: their folders go.
        crate::incognito::forget_all();
        // Attachments of tasks that are gone (a crash, a missed delete).
        s.runner.sweep_attachments();
        if let Some(p) = settings_problem {
            s.runner.problem(&p);
        }
        s.runner.ground_rules = s.settings.ground_rules();
        // What clients start from: nothing is "sent" before a snapshot.
        for l in &s.runner.tasks {
            s.sent
                .insert(l.task.id.clone(), sent_of(&l.task, header(l, &s.queued)));
        }
        s
    }

    /// Starts the developer log of this run: the writer, the panic hook and
    /// the header (what this PC and this build are, gathered off this
    /// thread).
    fn start_devlog(&mut self) {
        crate::devlog::init(
            &self.run_id,
            crate::devlog::Config::service(crate::devlog::dir()),
            self.settings.devlog_on(),
            self.settings.devlog_chats(),
        );
        crate::devlog::install_panic_hook();
        if !crate::devlog::enabled() {
            return;
        }
        let version = self.version.clone();
        let phones: Vec<String> = self
            .clients
            .values()
            .filter_map(|c| c.phone.clone())
            .map(|k| k.chars().take(8).collect())
            .collect();
        let s = &self.settings;
        let snapshot = serde_json::json!({
            "power": format!("{:?}", s.power()),
            "chats_paid": s.chats_paid(),
            "project_free": s.project_free(),
            "ask_before_free": s.ask_before_free,
            "finish_summary": s.finish_summary,
            "devlog_chats": s.devlog_chats(),
        });
        let _ = std::thread::Builder::new()
            .name("devlog-header".into())
            .spawn(move || {
                let hw = crate::engine::read_hardware();
                let os = std::fs::read_to_string("/etc/os-release")
                    .ok()
                    .and_then(|t| {
                        t.lines()
                            .find_map(|l| l.strip_prefix("PRETTY_NAME="))
                            .map(|v| v.trim_matches('"').to_string())
                    })
                    .unwrap_or_default();
                let cpu = std::fs::read_to_string("/proc/cpuinfo")
                    .ok()
                    .and_then(|t| {
                        t.lines()
                            .find_map(|l| l.strip_prefix("model name"))
                            .map(|v| v.trim_start_matches([' ', '\t', ':']).to_string())
                    })
                    .unwrap_or_default();
                let gpu = hw
                    .gpu
                    .as_ref()
                    .map(|g| format!("{} {} GB", g.vendor, g.vram >> 30))
                    .unwrap_or_default();
                let ollama =
                    crate::ollama::version(&crate::models::ollama_url()).unwrap_or_default();
                let models = crate::models::installed().unwrap_or_default();
                crate::devlog::log(
                    "service",
                    "header",
                    vec![
                        ("version", version.into()),
                        (
                            "build",
                            (if cfg!(debug_assertions) {
                                "debug"
                            } else {
                                "release"
                            })
                            .into(),
                        ),
                        ("os", os.into()),
                        ("cpu", cpu.into()),
                        ("threads", hw.threads.into()),
                        ("gpu", gpu.into()),
                        ("ram_gb", (hw.ram >> 30).into()),
                        ("ollama", ollama.into()),
                        ("models", models.into()),
                        (
                            "engine",
                            crate::engine::words(crate::engine::load().as_ref()).into(),
                        ),
                        ("settings", snapshot),
                        ("phones", phones.into()),
                        ("window_connected", false.into()),
                    ],
                );
            });
        crate::devlog::log("service", "start", vec![("pid", std::process::id().into())]);
    }

    /// One message, timed: a `slow` event past 50 ms, and a `call` event
    /// for each call a client made.
    fn timed_handle(&mut self, m: Inbound) {
        let call = match &m {
            Inbound::Call { msg, .. } => Some(crate::devlog::name_of(&msg.call)),
            _ => None,
        };
        let kind = inbound_kind(&m);
        let t0 = Instant::now();
        // A check can make every message slow (the freeze detector's test).
        if call.is_some()
            && let Some(d) = test_delay()
        {
            std::thread::sleep(d);
        }
        self.handle(m);
        let took = t0.elapsed();
        crate::devlog::message_took(kind, call.as_deref(), took.as_millis() as u64);
        // The hub's watchdog: every message within 50 ms. The service warns
        // (in every build) rather than panics: its calls still include
        // some slow ones the window and phone checks drive on purpose.
        if call.is_none() || test_delay().is_none() {
            mimir_hub::watchdog::check_with(kind, took, false);
        }
    }

    /// The `call` event of a call that was answered: who, which, how long,
    /// and how it ended.
    fn log_call_result(
        &self,
        client: u64,
        call_id: u64,
        name: &str,
        task: Option<String>,
        took: Duration,
        result: &Result<serde_json::Value, String>,
    ) {
        let ms = took.as_millis() as u64;
        let mut f: Vec<(&'static str, serde_json::Value)> = vec![
            ("call", name.into()),
            ("call_id", call_id.into()),
            ("ms", ms.into()),
        ];
        match self.clients.get(&client).and_then(|c| c.phone.clone()) {
            Some(p) => f.push(("phone", p.chars().take(8).collect::<String>().into())),
            None => f.push(("from", "window".into())),
        }
        if let Some(t) = task {
            f.push(("task", t.into()));
        }
        match result {
            Ok(_) => f.push(("ok", true.into())),
            Err(e) => {
                f.push(("err", crate::log::cut(e).into()));
                f.push(("kind", "refused".into()));
            }
        }
        crate::devlog::log("call", "done", f);
    }

    fn run(&mut self) {
        self.start_devlog();
        mimir_hub::watchdog::set_warn(|name, took| {
            crate::devlog::log(
                "slow",
                "watchdog",
                vec![
                    ("msg", name.to_string().into()),
                    ("ms", (took.as_millis() as u64).into()),
                ],
            )
        });
        loop {
            match self.rx.recv_timeout(self.next_wait()) {
                Ok(m) => self.timed_handle(m),
                Err(RecvTimeoutError::Timeout) => {}
                Err(RecvTimeoutError::Disconnected) => self.stop = true,
            }
            while let Ok(m) = self.rx.try_recv() {
                self.timed_handle(m);
            }
            // Its folder gone (a check's home deleted): nothing to serve.
            if self.pid_file.as_ref().is_some_and(|p| !p.exists()) {
                self.stop = true;
            }
            if self.stop {
                break;
            }
            self.tick();
            self.publish();
        }
        self.runner.shutdown();
        self.publish();
        // Chat changes still waiting for the writer's next tick.
        crate::talk_store::flush();
        crate::devlog::log(
            "service",
            "stop",
            vec![("run_ms", crate::devlog::uptime_ms().into())],
        );
        crate::devlog::shutdown();
    }

    /// How long the loop may wait for a message before its timers are due:
    /// [`TICK`] while anything is going on, [`IDLE_WAIT`] when nothing is.
    fn next_wait(&self) -> Duration {
        let busy = !self.clients.is_empty()
            || !self.queued.is_empty()
            || !self.wake_pending.is_empty()
            || !self.outside.is_empty()
            || !self.summary_pending.is_empty()
            || self
                .was_working
                .as_ref()
                .is_some_and(|m| m.values().any(|s| s.working))
            || self.runner.tasks.iter().any(|l| {
                l.running() || matches!(l.task.status, Status::Working | Status::NeedsYou)
            });
        if busy { TICK } else { IDLE_WAIT }
    }

    fn handle(&mut self, m: Inbound) {
        match m {
            Inbound::Wake => {}
            Inbound::Stop => self.stop = true,
            Inbound::Connect {
                client,
                events,
                wake,
            } => {
                crate::devlog::log("window", "connect", vec![("client", client.into())]);
                self.clients.insert(
                    client,
                    Client {
                        events,
                        wake,
                        phone: None,
                        person: crate::people::OWNER.to_string(),
                        ready: false,
                        focused: false,
                        features: vec![],
                        own_seq: 0,
                    },
                );
            }
            Inbound::Link(e) => self.link_event(e),
            Inbound::Session { msg, task, reply } => self.session_msg(msg, task, reply),
            Inbound::Disconnect { client } => {
                crate::devlog::log("window", "disconnect", vec![("client", client.into())]);
                self.clients.remove(&client);
            }
            Inbound::Later {
                client,
                call_id,
                result,
            } => self.send_to(client, Event::Reply { call_id, result }),
            Inbound::Talk(o) => self.talk_out(o),
            Inbound::Waking { task } => self.runner.note(&task, crate::talk::WAKING),
            Inbound::Woken { client, msg } => {
                self.wake_pending
                    .retain(|(c, id, _)| (*c, *id) != (client, msg.call_id));
                if self.wake_cancelled.remove(&(client, msg.call_id)) {
                    self.send_to(
                        client,
                        Event::Reply {
                            call_id: msg.call_id,
                            result: Err("Stopped before it started.".into()),
                        },
                    );
                    return;
                }
                self.replay_ok.insert((client, msg.call_id));
                self.handle(Inbound::Call { client, msg });
            }
            Inbound::Audio { .. } => {}
            // Whose call it is decides whether it goes on at all.
            Inbound::Call { client, msg } if self.person_gate(client, &msg.call).is_some() => {
                let result = self
                    .person_gate(client, &msg.call)
                    .unwrap_or_else(|| Err(crate::people::NOT_YOURS.into()));
                self.send_to(
                    client,
                    Event::Reply {
                        call_id: msg.call_id,
                        result,
                    },
                );
            }
            Inbound::Call { client, msg }
                if matches!(
                    msg.call,
                    Call::Speak { .. }
                        | Call::Listen { .. }
                        | Call::WebSearch { .. }
                        | Call::Lookup { .. }
                ) =>
            {
                self.call_later(client, msg);
            }
            Inbound::Call { client, msg } => {
                let Some(msg) = self.defer_for_model(client, msg) else {
                    return;
                };
                let (name, task, call_id) = (
                    crate::devlog::name_of(&msg.call),
                    call_task(&msg.call),
                    msg.call_id,
                );
                let t0 = Instant::now();
                let result = self.call(client, msg.call);
                self.publish();
                self.log_call_result(client, call_id, &name, task, t0.elapsed(), &result);
                self.send_to(
                    client,
                    Event::Reply {
                        call_id: msg.call_id,
                        result,
                    },
                );
            }
        }
    }

    /// Whose a client is. Nobody's (an empty id) when it is not connected
    /// any more: it has no chats and may do nothing.
    fn person_of(&self, client: u64) -> String {
        self.clients
            .get(&client)
            .map(|c| c.person.clone())
            .unwrap_or_default()
    }

    /// The person gate: what a client's person may not do is answered here,
    /// before anything looks at the call. None: it may go on. The person
    /// is the one the PC kept for the paired phone's link key.
    fn person_gate(&self, client: u64, call: &Call) -> Option<Result<serde_json::Value, String>> {
        let Some(c) = self.clients.get(&client) else {
            return Some(Err("refused: not connected".into()));
        };
        if c.person == crate::people::OWNER || crate::people::other_may(call) {
            return None;
        }
        Some(match crate::people::empty_for_others(call) {
            Some(v) => Ok(v),
            None => Err(crate::people::NOT_YOURS.into()),
        })
    }

    /// Sends one event to one client (replies, snapshots).
    fn send_to(&mut self, client: u64, event: Event) {
        let global = self.seq;
        if let Some(c) = self.clients.get_mut(&client) {
            if !c.gets(&event) {
                return;
            }
            let seq = c.number(global);
            let ok = c.events.try_send(EventMsg { seq, event }).is_ok();
            (c.wake)();
            if !ok {
                self.drop_client(client);
            }
        }
    }

    /// Sends an event to every client that said Hello.
    fn broadcast(&mut self, event: Event) {
        self.seq += 1;
        let msg = EventMsg {
            seq: self.seq,
            event,
        };
        let mut gone = vec![];
        for (id, c) in &mut self.clients {
            if !c.gets(&msg.event) {
                continue;
            }
            let mut m = msg.clone();
            m.seq = c.number(msg.seq);
            if c.ready && c.events.try_send(m).is_err() {
                gone.push(*id);
            }
        }
        for id in gone {
            self.drop_client(id);
        }
        if msg.event.durable() {
            self.ring.push_back(msg);
            if self.ring.len() > RING {
                self.ring.pop_front();
            }
        }
    }

    /// Sends a chat event to the devices of the person it is for, and to
    /// nobody else.
    fn broadcast_chat(&mut self, person: &str, event: Event) {
        let mut gone = vec![];
        let global = self.seq;
        for (id, c) in &mut self.clients {
            if c.ready && c.gets_chat(person, &event) {
                let seq = c.number(global);
                if c.events
                    .try_send(EventMsg {
                        seq,
                        event: event.clone(),
                    })
                    .is_err()
                {
                    gone.push(*id);
                }
            }
        }
        for id in gone {
            self.drop_client(id);
        }
    }

    /// A client whose queue is full (or closed) is let go: closing its
    /// queue closes its connection, and it reconnects for a fresh picture.
    fn drop_client(&mut self, client: u64) {
        if self.clients.remove(&client).is_some() {
            crate::devlog::log("window", "dropped", vec![("client", client.into())]);
        }
    }

    fn wake_clients(&self) {
        for c in self.clients.values() {
            if c.ready {
                (c.wake)();
            }
        }
    }

    /// Everything a client needs to start from.
    fn snapshot(&mut self, client: u64) {
        let mut events = vec![Event::Snapshot {
            run_id: self.run_id.clone(),
        }];
        for l in &self.runner.tasks {
            events.push(Event::Task {
                header: Box::new(header(l, &self.queued)),
            });
            events.push(Event::Entries {
                task: l.task.id.clone(),
                from: 0,
                entries: l.task.entries.clone(),
            });
            if !l.task.live.is_empty() {
                events.push(Event::Live {
                    task: l.task.id.clone(),
                    text: l.task.live.clone(),
                });
            }
        }
        events.push(Event::Usage {
            weekly: self.runner.weekly.clone(),
            five_hour: self.runner.five_hour.clone(),
            updated_at: self.runner.usage_at,
        });
        events.push(Event::Commands {
            slash_commands: self.runner.slash_commands.clone(),
            connectors: self.runner.mcp_servers.clone(),
        });
        events.push(Event::Settings {
            settings: settings::service_part(&self.settings),
        });
        for (project, info) in &self.git {
            events.push(Event::GitInfo {
                project: project.clone(),
                info: info.clone(),
            });
        }
        for p in &self.problems {
            events.push(Event::Problem { text: p.clone() });
        }
        events.push(Event::NeedsYou {
            items: self.needs_items(),
        });
        events.push(Event::RecentFinished {
            items: self.finished.iter().cloned().collect(),
        });
        if let Some(t) = self.truth_now() {
            events.push(Event::Truth { truth: Box::new(t) });
        }
        events.push(Event::Ready);
        for e in events {
            self.send_to(client, e);
        }
    }

    fn task(&self, id: &str) -> Option<&runner::Live> {
        self.runner.tasks.iter().find(|l| l.task.id == id)
    }

    /// A call, done once per message id: a phone that sends again after
    /// its link dropped gets the first reply.
    fn call(&mut self, client: u64, call: Call) -> Result<serde_json::Value, String> {
        let msg_id = match &call {
            Call::NewTask { msg_id, .. } | Call::Send { msg_id, .. } => msg_id.clone(),
            Call::Chat { msg_id, .. } => Some(msg_id.clone()),
            _ => None,
        };
        if let Some(id) = &msg_id
            && let Some(first) = self.msg_ids.get(id)
        {
            return first;
        }
        let r = self.call_once(client, call);
        if let Some(id) = &msg_id {
            self.msg_ids.put(id, &r);
        }
        r
    }

    /// The projects the phone can pick, shows and has its chats routed to:
    /// one list, see [`crate::paths::project_list`].
    fn pickable_projects(&self) -> Vec<String> {
        crate::paths::project_list(&self.settings, self.runner.tasks.iter().map(|l| &l.task))
    }

    /// YOLO goes back to "Ask me first" after 5 hours of work or 24 hours
    /// idle, with a note in the task.
    fn yolo_tick(&mut self) {
        let now = crate::needs::now();
        let mut ended = vec![];
        for l in &mut self.runner.tasks {
            let busy = l.busy();
            let (end, save) = crate::yolo::check(l.task.mode, &mut l.task.yolo, busy, now);
            if save {
                l.dirty = true;
            }
            if let Some(e) = end {
                ended.push((l.task.id.clone(), e));
            }
        }
        for (id, e) in ended {
            self.runner.set_mode(&id, mimir_proto::Mode::Ask);
            self.runner.note(&id, e.note());
            self.touched.insert(id);
        }
    }

    fn call_once(&mut self, client: u64, call: Call) -> Result<serde_json::Value, String> {
        use serde_json::{Value, json};
        let ok = Ok(Value::Null);
        // Most calls change one task.
        match &call {
            Call::Send { task, .. }
            | Call::Queue { task, .. }
            | Call::Unqueue { task }
            | Call::SendQueued { task }
            | Call::Pause { task }
            | Call::Stop { task }
            | Call::Continue { task }
            | Call::Answer { task, .. }
            | Call::SetMode { task, .. }
            | Call::SetModel { task, .. }
            | Call::SetEffort { task, .. }
            | Call::ApprovePlan { task, .. }
            | Call::Rename { task, .. }
            | Call::Archive { task, .. }
            | Call::MoveTo { task, .. }
            | Call::ConfirmPlacement { task }
            | Call::Note { task, .. }
            | Call::DismissQuestion { task }
            | Call::BringIn { task }
            | Call::RemoveCopy { task }
            | Call::Undo { task, .. }
            | Call::Redo { task } => {
                self.touched.insert(task.clone());
            }
            _ => {}
        }
        match call {
            Call::Hello {
                proto,
                env,
                features,
                ..
            } => {
                let phone = self.clients.get(&client).and_then(|c| c.phone.clone());
                let env = if phone.is_some() { vec![] } else { env };
                // A service of its own takes the newest window's desktop for
                // the tasks it starts (one inside the window has it anyway).
                if self.pid_file.is_some() && !env.is_empty() {
                    self.runner.set_env(env);
                }
                if let Some(c) = self.clients.get_mut(&client) {
                    c.ready = true;
                    c.features = features;
                }
                self.send_to(
                    client,
                    Event::Welcome {
                        proto: PROTO,
                        pc_name: pc_name(&self.settings),
                        pc_version: self.version.clone(),
                        min_app: String::new(),
                        run_id: self.run_id.clone(),
                        features: OFFERED.iter().map(|f| f.to_string()).collect(),
                        person: if phone.is_some() {
                            self.person_of(client)
                        } else {
                            String::new()
                        },
                    },
                );
                if proto != PROTO {
                    return Err("Restart Mímir: its parts are different versions.".into());
                }
                self.snapshot(client);
                ok
            }
            // Anyone but the owner always gets the whole picture: the
            // service's event numbers are not theirs to see or ask after.
            Call::Sync { .. } if self.person_of(client) != crate::people::OWNER => {
                self.snapshot(client);
                ok
            }
            Call::Sync { since } => {
                let first = self.ring.front().map(|m| m.seq);
                let missed_n = self.ring.iter().filter(|m| m.seq > since).count();
                match first {
                    // More than half a queue missed: the whole picture is
                    // fewer events than the replay.
                    Some(f) if since + 1 >= f && missed_n <= CLIENT_QUEUE / 2 => {
                        let missed: Vec<EventMsg> = self
                            .ring
                            .iter()
                            .filter(|m| m.seq > since)
                            .cloned()
                            .collect();
                        let mut full = false;
                        if let Some(c) = self.clients.get(&client) {
                            for m in missed {
                                if c.gets(&m.event) && c.events.try_send(m).is_err() {
                                    full = true;
                                    break;
                                }
                            }
                        }
                        if full {
                            self.drop_client(client);
                        }
                    }
                    _ if since >= self.seq => {}
                    _ => self.snapshot(client),
                }
                ok
            }
            Call::NewTask {
                project,
                text,
                pictures,
                files,
                mode,
                model,
                effort,
                separate_copy,
                ground_rules,
                ..
            } => {
                // A project whose folder is gone can't take a task: refused
                // before anything is made, so the phone keeps the words.
                if Path::new(&project) != crate::paths::chats_dir() && !Path::new(&project).is_dir()
                {
                    return Err("That project's folder isn't on your PC any more.".into());
                }
                let attached = crate::task_files::prepare(&pictures, &files)?;
                // A phone starts tasks only in a project on the PC's list (or
                // a chat the PC files itself), as with MoveTo.
                if self.clients.get(&client).is_some_and(|c| c.phone.is_some())
                    && Path::new(&project) != crate::paths::chats_dir()
                    && !self
                        .pickable_projects()
                        .iter()
                        .any(|p| crate::paths::same(p, &project))
                {
                    return Err(
                        "A phone can only start a task in a project on the PC's list.".into(),
                    );
                }
                // The phone's own ground rules, for the task this starts.
                self.runner.next_rules = settings::without_old_default(ground_rules);
                // A chat with no project is filed where it belongs.
                let (project, placed) =
                    if Path::new(&project) == crate::paths::chats_dir() && !separate_copy {
                        match self.place_chat(&text) {
                            Ok((p, how)) => (p, how),
                            Err(e) => {
                                self.runner.next_rules = None;
                                return Err(e);
                            }
                        }
                    } else {
                        (project, None)
                    };
                let mut model = model;
                // Free models only, or Claude's limit is out: a free model.
                let claude_model = model.clone();
                let now = crate::needs::now();
                let out = self.claude_out_until.filter(|t| *t > now);
                let power = self.settings.power();
                let mut on_free = None;
                if !crate::models::is_free(model.as_deref())
                    && (power == settings::Power::FreeOnly
                        || (power == settings::Power::Backup
                            && out.is_some()
                            && !self.settings.ask_before_free))
                    && let Some(f) = self.free_choice()
                {
                    on_free = Some(f.name.clone());
                    model = Some(f.id);
                }
                let id = if separate_copy {
                    let made = self.runner.new_task_in_copy(
                        Path::new(&project),
                        &text,
                        &[],
                        &attached,
                        mode,
                        model,
                        effort,
                    );
                    self.runner.next_rules = None;
                    made?
                } else {
                    let made = self.runner.new_task_attached(
                        Path::new(&project),
                        &text,
                        &attached,
                        mode,
                        model,
                        effort,
                    );
                    if made.is_err() {
                        self.runner.next_rules = None;
                    }
                    made?
                };
                if let Some(name) = on_free {
                    match out {
                        Some(back_at) if power != settings::Power::FreeOnly => {
                            self.runner.set_ran_out(
                                &id,
                                Some(mimir_proto::RanOut {
                                    back_at,
                                    claude_model,
                                    on_free: true,
                                }),
                                false,
                            );
                            let name = crate::models::plain_name(&name);
                            self.runner.note(
                                &id,
                                &format!(
                                    "On {name} because the online limit ran out; online is back \
                                     at {}.",
                                    crate::models::clock(back_at)
                                ),
                            );
                        }
                        _ => self.runner.note(
                            &id,
                            &format!(
                                "Answered by {}, a free model on your PC. Your chats stay on \
                                 this PC.",
                                crate::models::plain_name(&name)
                            ),
                        ),
                    }
                }
                if let Some(how) = placed {
                    self.file_placed(&id, &project, how, None);
                }
                if self.clients.get(&client).is_some_and(|c| c.phone.is_some()) {
                    self.runner.set_started_on_phone(&id);
                }
                self.touched.insert(id.clone());
                Ok(json!(id))
            }
            Call::Send {
                task,
                text,
                pictures,
                files,
                ..
            } => {
                self.runner.send_attached(&task, &text, &pictures, &files)?;
                // Carrying on after a sure filing: the "Filed under" line has
                // done its work.
                if self
                    .task(&task)
                    .is_some_and(|l| l.task.placed == Some(Placed::Sure))
                {
                    self.runner.set_placed(&task, None, None);
                }
                ok
            }
            Call::ConfirmPlacement { task } => {
                self.runner.set_placed(&task, None, None);
                ok
            }
            Call::Queue { task, text, files } => {
                let q = self.queued.entry(task).or_default();
                if !q.text.is_empty() {
                    q.text.push('\n');
                }
                q.text.push_str(&text);
                q.files.extend(files);
                ok
            }
            Call::Unqueue { task } => Ok(self
                .queued
                .remove(&task)
                .and_then(|q| serde_json::to_value(q).ok())
                .unwrap_or(Value::Null)),
            Call::SendQueued { task } => {
                if let Some(q) = self.queued.remove(&task) {
                    let (text, pictures) = with_files(&q.text, &q.files);
                    self.runner.send_with(&task, &text, &pictures);
                }
                ok
            }
            Call::Pause { task } => {
                self.runner.pause(&task);
                ok
            }
            Call::Stop { task } => {
                self.runner.stop(&task);
                ok
            }
            Call::Continue { task } => {
                self.runner.resume(&task);
                ok
            }
            Call::Answer {
                task,
                choice,
                ask_id,
            } => {
                let phone = self.clients.get(&client).is_some_and(|c| c.phone.is_some());
                let waiting = self
                    .task(&task)
                    .and_then(|l| l.asks.front())
                    .map(|p| crate::needs::permission_id(&task, &p.request));
                match (&ask_id, &waiting) {
                    // A phone's answer must name its request: an older phone's
                    // late tap could otherwise allow whatever is waiting now.
                    (None, _) if phone => {
                        return Err("Update the Mímir app on your phone to answer from it.".into());
                    }
                    (Some(a), w) if w.as_ref() != Some(a) => {
                        return Err("That was already answered.".into());
                    }
                    _ => {}
                }
                self.runner.answer(
                    &task,
                    match choice {
                        Choice::Allow => Answer::Allow,
                        Choice::AlwaysProject => Answer::Always,
                        Choice::DontAllow => Answer::No,
                        Choice::Halted => Answer::Halted,
                    },
                );
                ok
            }
            Call::SetMode { task, mode } => {
                if mode == mimir_proto::Mode::NoAsk {
                    let phone = self.clients.get(&client).is_some_and(|c| c.phone.is_some());
                    let whisper = self.task(&task).is_none_or(|l| l.task.incognito);
                    // The window may always; a phone only when the PC allows it.
                    crate::yolo::phone_may(self.settings.yolo_from_phone || !phone, whisper)?;
                }
                self.runner.set_mode(&task, mode);
                ok
            }
            Call::SetModel { task, model } => {
                self.runner.set_model(&task, model);
                ok
            }
            Call::SetEffort { task, effort } => {
                self.runner.set_effort(&task, effort);
                ok
            }
            Call::ApprovePlan { task, mode } => {
                self.runner.approve_plan(&task, mode);
                ok
            }
            Call::Rename { task, title } => {
                self.runner.rename(&task, &title);
                ok
            }
            Call::Archive { task, archived } => {
                self.runner.set_archived(&task, archived);
                ok
            }
            Call::RemoveTask { task } => {
                let Some(l) = self.task(&task) else { return ok };
                if l.busy() {
                    return Err("Pause or stop the task before deleting it.".into());
                }
                if l.task.copy_branch.is_some() {
                    return Err(
                        "This task has a separate copy. Bring its changes in or remove \
                                the copy before deleting the task."
                            .into(),
                    );
                }
                self.queued.remove(&task);
                self.runner.remove(&task);
                crate::needs::remove_rules(&self.store_dir, &task);
                ok
            }
            Call::Note { task, text } => {
                self.runner.note(&task, &text);
                ok
            }
            Call::DismissQuestion { task } => {
                self.runner.dismiss_question(&task);
                ok
            }
            Call::PastSessions { project } => {
                let mut sessions = crate::sessions::list(Path::new(&project));
                // Sessions that are already tasks here are left out.
                sessions.retain(|s| {
                    !self
                        .runner
                        .tasks
                        .iter()
                        .any(|l| l.task.session_id.as_deref() == Some(s.id.as_str()))
                });
                serde_json::to_value(sessions).map_err(|e| e.to_string())
            }
            Call::ImportPast { project, session } => {
                let s: crate::sessions::PastSession =
                    serde_json::from_value(session).map_err(|e| e.to_string())?;
                let id = self
                    .runner
                    .open_past(Path::new(&project), &s)
                    .map_err(|e| format!("That session could not be opened: {e}"))?;
                // The project's saved defaults apply.
                if let Some(d) = self.settings.defaults.get(&project).cloned() {
                    self.runner.set_mode(&id, d.mode);
                    self.runner.set_model(&id, d.model);
                    self.runner.set_effort(&id, d.effort);
                }
                self.touched.insert(id.clone());
                Ok(json!(id))
            }
            Call::BringIn { task } => {
                let r = self.runner.bring_in(&task).map(|_| Value::Null);
                self.refresh_git_of(&task);
                r
            }
            Call::RemoveCopy { task } => self.runner.remove_copy(&task).map(|_| Value::Null),
            Call::UndoPreview { task, entry } => self
                .runner
                .undo_preview(&task, entry)
                .and_then(|c| serde_json::to_value(c).map_err(|e| e.to_string())),
            Call::RedoPreview { task } => self
                .runner
                .redo_preview(&task)
                .and_then(|c| serde_json::to_value(c).map_err(|e| e.to_string())),
            Call::Undo { task, entry } => {
                let r = self.runner.undo(&task, entry).map(|_| Value::Null);
                self.refresh_git_of(&task);
                r
            }
            Call::Redo { task } => {
                let r = self.runner.redo(&task).map(|_| Value::Null);
                self.refresh_git_of(&task);
                r
            }
            Call::SetSettings { settings } => {
                let rules = (
                    self.settings.ground_rules.clone(),
                    self.settings.ground_rules_on,
                );
                self.settings = settings::with_part(&self.settings, &settings);
                if rules
                    != (
                        self.settings.ground_rules.clone(),
                        self.settings.ground_rules_on,
                    )
                {
                    self.settings.ground_rules_at = Some(crate::talk::now_ms());
                }
                self.settings.save();
                crate::devlog::set_switches(
                    self.settings.devlog_on(),
                    self.settings.devlog_chats(),
                );
                self.runner.ground_rules = self.settings.ground_rules();
                self.broadcast(Event::Settings {
                    settings: settings::service_part(&self.settings),
                });
                ok
            }
            Call::Transcript { task } => Ok(self
                .runner
                .transcript(&task)
                .map(|p| json!(p))
                .unwrap_or(Value::Null)),
            Call::WatchGit { project } => {
                if let Some(p) = &project {
                    self.refresh_git(p, false);
                }
                self.git_watch = project;
                ok
            }
            Call::RefreshGit { project } => {
                self.refresh_git(&project, true);
                ok
            }
            Call::Focus { focused } => {
                if let Some(c) = self.clients.get_mut(&client) {
                    c.focused = focused;
                }
                ok
            }
            Call::DismissProblem { text } => {
                self.problems.retain(|p| p != &text);
                ok
            }
            // A window closing stops only a service inside its own process.
            Call::PairCode => match &self.link {
                Some(l) => Ok(json!(l.pair_code())),
                None => {
                    Err("Phones can't connect right now. Close Mímir and open it again.".into())
                }
            },
            Call::ConfirmPair {
                yes: true, person, ..
            } if person.as_deref().is_none_or(|n| n.trim().is_empty()) => {
                // Not said whose phone it is: never taken to be the owner's.
                Err("Say whose phone this is: Mine, or someone else's.".into())
            }
            Call::ConfirmPair {
                pending,
                yes,
                person,
            } => match &self.link {
                Some(l)
                    if l.confirm_as(
                        pending,
                        mimir_link::server::Verdict {
                            yes,
                            person: person.filter(|n| !n.trim().is_empty()),
                        },
                    ) =>
                {
                    ok
                }
                Some(_) => Err("That pairing already ended.".into()),
                None => {
                    Err("Phones can't connect right now. Close Mímir and open it again.".into())
                }
            },
            Call::Phones => match &self.link {
                Some(l) => {
                    let people = l.people();
                    // Each phone with whose it is (the owner's: "You").
                    let list: Vec<Value> = l
                        .phones()
                        .into_iter()
                        .map(|p| {
                            let who = people.iter().find(|x| x.id == p.person);
                            let mut v = serde_json::to_value(&p).unwrap_or_default();
                            v["person_name"] = json!(who.map_or("You", |x| x.name.as_str()));
                            v["online"] = json!(who.is_some_and(|x| x.online));
                            v
                        })
                        .collect();
                    Ok(Value::Array(list))
                }
                None => Ok(json!([])),
            },
            Call::RemovePhone { key } => match &self.link {
                Some(l) => {
                    // A person left with no phone goes with their chats.
                    if let Some(Some(gone)) = l.unpair(&key) {
                        self.forget_person(&gone);
                    }
                    ok
                }
                None => {
                    Err("Phones can't connect right now. Close Mímir and open it again.".into())
                }
            },
            Call::PersonOnline { person, on } => match &self.link {
                Some(l) if l.set_online(&person, on) => {
                    // Turned off: what they chose before no longer counts.
                    // Their phones ask again what they may choose, at once.
                    self.broadcast_chat(&person, Event::ModelsChanged);
                    ok
                }
                Some(_) => Err("That person is gone.".into()),
                None => {
                    Err("Phones can't connect right now. Close Mímir and open it again.".into())
                }
            },
            Call::RenamePerson { person, name } => match &self.link {
                Some(l) if l.rename_person(&person, &name) => ok,
                Some(_) => Err("That person is gone.".into()),
                None => {
                    Err("Phones can't connect right now. Close Mímir and open it again.".into())
                }
            },
            Call::RemovePerson { person } => match &self.link {
                Some(l) if l.remove_person(&person) => {
                    self.forget_person(&person);
                    ok
                }
                Some(_) => Err("That person is gone.".into()),
                None => {
                    Err("Phones can't connect right now. Close Mímir and open it again.".into())
                }
            },
            Call::LinkInfo => match &self.link {
                Some(l) => Ok(json!({
                    "id": l.id(),
                    "bound": l.bound().iter().map(|a| a.to_string()).collect::<Vec<_>>(),
                })),
                None => {
                    Err("Phones can't connect right now. Close Mímir and open it again.".into())
                }
            },
            Call::Talk {
                chat_id,
                turn_id,
                turns,
                lookup,
                sources,
                effort,
                ground_rules,
                task,
                chat_only,
            } => {
                let person = self.person_of(client);
                // Another person's message is never filed as work on the
                // owner's projects: it is always just a chat.
                let chat_only = chat_only || person != crate::people::OWNER;
                let f = self.talk_call(TalkArgs {
                    person,
                    chat_only,
                    chat_id,
                    turn_id,
                    turns,
                    lookup,
                    sources,
                    effort,
                    ground_rules,
                    task,
                });
                serde_json::to_value(f).map_err(|e| e.to_string())
            }
            Call::TalkStop { turn_id, .. } => {
                let answer = crate::talk::answer_id(&turn_id);
                // Another person's answer is not found: nothing happens.
                if self.answer_is_of(&answer, &self.person_of(client)) {
                    self.talk.stop(&answer);
                }
                ok
            }
            Call::TalkTurn { turn_id, .. } => {
                let answer = crate::talk::answer_id(&turn_id);
                let state = if self.answer_is_of(&answer, &self.person_of(client)) {
                    self.talk.state(&answer)
                } else {
                    mimir_proto::TalkState::Unknown
                };
                serde_json::to_value(state).map_err(|e| e.to_string())
            }
            Call::ChatPut {
                chat_id,
                name,
                turns,
                deleted,
            } => {
                let person = self.person_of(client);
                let done = self.chat_put(&person, &chat_id, name, turns, deleted)?;
                serde_json::to_value(done).map_err(|e| e.to_string())
            }
            Call::ChatsSince { seq } => {
                let person = self.person_of(client);
                serde_json::to_value(self.chats().since_for(&person, seq))
                    .map_err(|e| e.to_string())
            }
            Call::SetGroundRules { text, at } if self.person_of(client) != crate::people::OWNER => {
                // Another person's rules are their own.
                let person = self.person_of(client);
                if self
                    .people_book
                    .get(&person)
                    .ground_rules_at
                    .is_none_or(|t| at > t)
                {
                    self.people_book.edit(&person, |p| {
                        p.ground_rules = Some(text);
                        p.ground_rules_on = Some(true);
                        p.ground_rules_at = Some(at);
                    });
                }
                let p = self.people_book.get(&person);
                serde_json::to_value(mimir_proto::GroundRules {
                    text: p.rules().unwrap_or_default(),
                    at: p.ground_rules_at.unwrap_or(0),
                })
                .map_err(|e| e.to_string())
            }
            Call::SetGroundRules { text, at } => {
                if self.settings.ground_rules_at.is_none_or(|t| at > t) {
                    self.settings.ground_rules = Some(text);
                    self.settings.ground_rules_on = Some(true);
                    self.settings.ground_rules_at = Some(at);
                    self.settings.save();
                    self.runner.ground_rules = self.settings.ground_rules();
                    self.broadcast(Event::Settings {
                        settings: settings::service_part(&self.settings),
                    });
                }
                serde_json::to_value(mimir_proto::GroundRules {
                    text: self.settings.ground_rules().unwrap_or_default(),
                    at: self.settings.ground_rules_at.unwrap_or(0),
                })
                .map_err(|e| e.to_string())
            }
            Call::NewProject { name } => {
                let (dir, made) =
                    crate::paths::make_project_folder(&self.settings.projects, &name)?;
                self.settings.add_project(&dir);
                self.settings.save();
                self.broadcast(Event::Settings {
                    settings: settings::service_part(&self.settings),
                });
                let path = crate::paths::clean_path(&dir.display().to_string());
                Ok(json!({"path": path, "name": crate::paths::project_name(&path), "made": made}))
            }
            Call::Projects => {
                let all = self.pickable_projects();
                let names = crate::paths::list_names(&self.settings, &all);
                let projects: Vec<Value> = all
                    .into_iter()
                    .zip(names)
                    .map(|(p, name)| json!({"path": p, "name": name}))
                    .collect();
                let last = self
                    .runner
                    .tasks
                    .iter()
                    .filter(|l| Path::new(&l.task.project) != crate::paths::chats_dir())
                    .max_by_key(|l| l.task.created_unix)
                    .map(|l| l.task.project.clone())
                    .or_else(|| self.settings.last_project.clone());
                Ok(json!({"projects": projects, "last": last}))
            }
            Call::Chat {
                msg_id,
                text,
                ground_rules,
            } => {
                self.runner.next_rules = settings::without_old_default(ground_rules);
                let by = if self.clients.get(&client).is_some_and(|c| c.phone.is_some()) {
                    "phone"
                } else {
                    "window"
                };
                let _ = by;
                let f = self.chat(&msg_id, &text);
                self.runner.next_rules = None;
                let f = f?;
                serde_json::to_value(f).map_err(|e| e.to_string())
            }
            Call::FileInbox { inbox_id, project } => {
                let item = crate::chat::take_from_inbox(&inbox_id, &project)
                    .ok_or("That message was already filed.")?;
                let t = self.new_task_ask(&project, &item.text);
                self.touched.insert(t.clone());
                self.last_chat = Some(crate::route::Previous {
                    time: crate::needs::now(),
                    project: Some(project.clone()),
                    task: Some(t.clone()),
                });
                serde_json::to_value(Filed::NewTask { project, task: t }).map_err(|e| e.to_string())
            }
            Call::Inbox => serde_json::to_value(crate::chat::inbox()).map_err(|e| e.to_string()),
            Call::MoveTask { task, project } => {
                let id = self.move_task(&task, &project)?;
                Ok(json!(id))
            }
            Call::MoveTo { task, project } => {
                let phone = self.clients.get(&client).is_some_and(|c| c.phone.is_some());
                if phone {
                    // A phone moves only to a project already on the list (General
                    // is listed once made), and the task asks first there.
                    let listed = self
                        .settings
                        .projects
                        .iter()
                        .any(|p| crate::paths::same(p, &project));
                    if !listed || Path::new(&project) == crate::paths::chats_dir() {
                        return Err(
                            "A phone can only move a task to a project on the PC's list.".into(),
                        );
                    }
                }
                self.move_to(&task, &project)?;
                if phone
                    && self.task(&task).is_some_and(|l| {
                        !matches!(
                            l.task.mode,
                            mimir_proto::Mode::Ask | mimir_proto::Mode::Plan
                        )
                    })
                {
                    self.runner.set_mode(&task, mimir_proto::Mode::Ask);
                }
                ok
            }
            Call::TidyPlan => serde_json::to_value(self.tidy_plan()).map_err(|e| e.to_string()),
            Call::Tidy { rows } => Ok(self.tidy(rows)),
            Call::MergeProject { from, into } => {
                let n = self.merge_project(&from, &into)?;
                Ok(json!(n))
            }
            Call::UndoIdea { project, idea } => {
                if crate::chat::remove_idea(&project, &idea) {
                    ok
                } else {
                    Err("That idea is already gone.".into())
                }
            }
            Call::Ideas { project } => {
                serde_json::to_value(crate::chat::ideas(&project)).map_err(|e| e.to_string())
            }
            Call::Status => serde_json::to_value(self.status_page(true)).map_err(|e| e.to_string()),
            Call::Updates => {
                serde_json::to_value(Vec::<serde_json::Value>::new()).map_err(|e| e.to_string())
            }
            Call::GetFile { name } => {
                use base64::Engine as _;
                if !crate::audio::safe_name(&name) {
                    return Err("refused: not a file Mímir offers".into());
                }
                let bytes = std::fs::read(crate::audio::audio_dir().join(&name))
                    .map_err(|_| "That file is gone.".to_string())?;
                Ok(json!({
                    "name": name,
                    "base64": base64::engine::general_purpose::STANDARD.encode(bytes),
                }))
            }
            Call::Models => {
                let who = self.person_of(client);
                let (chats_paid, chats_model, project_free) = if who == crate::people::OWNER {
                    (
                        self.settings.chats_paid(),
                        self.settings.chats_model_alias(),
                        self.settings.project_free(),
                    )
                } else {
                    // Another person's own choices; online answers only
                    // while the owner allows them; no project work.
                    let p = self.people_book.get(&who);
                    (
                        p.chats_paid() && self.may_go_online(&who),
                        p.chats_model_alias(),
                        true,
                    )
                };
                let names = crate::models::installed();
                let free = names
                    .as_ref()
                    .map(|n| crate::models::catalogue(n))
                    .unwrap_or_default();
                let why = match &names {
                    Ok(_) => String::new(),
                    Err(e) if crate::models::unit_path().is_file() => {
                        eprintln!("mimir: model runner: {e}");
                        "Your PC's model runner isn't answering yet. It starts by itself when a free model is used.".to_string()
                    }
                    Err(e) => e.clone(),
                };
                // What the PC has installed and the owner's choices of free
                // model are the owner's: anyone else gets none of it.
                let owner = who == crate::people::OWNER;
                Ok(json!({
                    "free": if owner { json!(free) } else { json!([]) },
                    "free_model": if owner { json!(self.free_choice().map(|f| f.id)) } else { Value::Null },
                    "switch_to_free": owner && self.settings.switch_to_free,
                    "ollama": if owner { why } else { String::new() },
                    "refusals_removed_note": crate::models::REFUSALS_REMOVED,
                    "fit_note": crate::models::FIT_NOTE,
                    "standalone": crate::keep_current::standalone_catalogue(),
                    "weekly_check": !owner || self.settings.weekly_model_check != Some(false),
                    // Settings › Models "What answers" (feature "engines").
                    // Where online answers aren't allowed for this person the
                    // choice isn't offered at all (the phone offers Claude only
                    // when the PC reports an engine).
                    "chats_engine": if !self.may_go_online(&who) {
                        Value::Null
                    } else if chats_paid {
                        json!("paid")
                    } else {
                        json!("free")
                    },
                    "project_engine": if project_free { "free" } else { "claude" },
                    "chats_model": chats_model.unwrap_or("auto"),
                    "online_allowed": self.may_go_online(&who),
                    // Whether a picture can be answered for this person
                    // (feature "chat_pictures"): Online where they may use it,
                    // else any model on the PC that has sight (a picture
                    // goes to one that can look).
                    "sees_pictures": self.may_go_online(&who) || {
                        let base = crate::models::ollama_url();
                        crate::engine::chat_model()
                            .is_some_and(|p| crate::sight::claims(&base, &p.model))
                            || crate::models::installed()
                                .unwrap_or_default()
                                .iter()
                                .any(|m| crate::sight::claims(&base, m))
                    },
                    // The Claude models chats can use (feature "chat_model").
                    "chat_models": crate::talk::CLAUDE_CHOICES
                        .iter()
                        .map(|c| json!({"id": c.0, "name": c.1, "about": c.2}))
                        .collect::<Vec<_>>(),
                }))
            }
            Call::SetEngines {
                chats, chat_model, ..
            } if self.person_of(client) != crate::people::OWNER => {
                let who = self.person_of(client);
                let model = match chat_model.as_deref() {
                    None => None,
                    Some(m) if crate::talk::CLAUDE_CHOICES.iter().any(|c| c.0 == m) => {
                        Some(m.to_string())
                    }
                    Some(_) => return Err("refused: not a model chats can use".into()),
                };
                let engine = match chats.as_deref() {
                    None => None,
                    Some("free") => Some("free"),
                    // Online answers use the owner's plan: only when the
                    // owner allowed it for this person.
                    Some("paid") if self.may_go_online(&who) => Some("paid"),
                    Some("paid") => return Err(crate::people::NO_ONLINE.into()),
                    Some(_) => return Err("refused: chats are \"free\" or \"paid\"".into()),
                };
                self.people_book.edit(&who, |p| {
                    if let Some(m) = model {
                        p.chats_model = Some(m);
                    }
                    if let Some(e) = engine {
                        p.chats_engine = Some(e.into());
                    }
                });
                let p = self.people_book.get(&who);
                Ok(json!({
                    "chats_engine": if p.chats_paid() && self.may_go_online(&who) { "paid" } else { "free" },
                    "project_engine": "free",
                    "chats_model": p.chats_model_alias().unwrap_or("auto"),
                }))
            }
            Call::SetEngines {
                chats,
                project,
                chat_model,
            } => {
                match chat_model.as_deref() {
                    None => {}
                    Some(m) if crate::talk::CLAUDE_CHOICES.iter().any(|c| c.0 == m) => {
                        self.settings.chats_model = Some(m.into())
                    }
                    Some(_) => return Err("refused: not a model chats can use".into()),
                }
                match chats.as_deref() {
                    None => {}
                    Some(c @ ("free" | "paid")) => self.settings.chats_engine = Some(c.into()),
                    Some(_) => return Err("refused: chats are \"free\" or \"paid\"".into()),
                }
                match project.as_deref() {
                    None => {}
                    Some(p @ ("free" | "claude")) => self.settings.project_engine = Some(p.into()),
                    Some(_) => return Err("refused: project work is \"free\" or \"claude\"".into()),
                }
                self.settings.save();
                self.broadcast(Event::Settings {
                    settings: settings::service_part(&self.settings),
                });
                Ok(json!({
                    "chats_engine": if self.settings.chats_paid() { "paid" } else { "free" },
                    "project_engine": if self.settings.project_free() { "free" } else { "claude" },
                    "chats_model": self.settings.chats_model_alias().unwrap_or("auto"),
                }))
            }
            Call::SetFreeChoice {
                switch_to_free,
                free_model,
            } => {
                // "Switch near my limit" is gone (Stage 1): always off.
                if let Some(_on) = switch_to_free {
                    self.settings.switch_to_free = false;
                }
                if let Some(m) = free_model {
                    if !crate::models::is_free(Some(&m)) {
                        return Err("refused: not a free model".into());
                    }
                    self.settings.free_model = Some(m);
                }
                self.settings.save();
                self.broadcast(Event::Settings {
                    settings: settings::service_part(&self.settings),
                });
                ok
            }
            Call::ContinueOnClaude { task } => {
                let Some(l) = self.task(&task) else {
                    return Err("That task is gone.".into());
                };
                let t = &l.task;
                let did_work = t
                    .entries
                    .iter()
                    .any(|e| matches!(e, Entry::Claude(_) | Entry::Step(_)));
                let first = t.entries.iter().find_map(|e| match e {
                    Entry::You(x) => Some(x.clone()),
                    _ => None,
                });
                let text = if did_work {
                    crate::auto::brief(t)
                } else {
                    first.unwrap_or_default()
                };
                let (project, mode, rules) = (t.project.clone(), t.mode, t.ground_rules.clone());
                self.runner.next_rules = rules;
                self.runner.stop(&task);
                let id = self.runner.new_task_with(
                    Path::new(&project),
                    &text,
                    &[],
                    mode,
                    Some(crate::auto::Style::Efficient.value().into()),
                    None,
                );
                self.runner.note(&task, "Carried on in a new task online.");
                self.touched.insert(task);
                self.touched.insert(id.clone());
                Ok(json!(id))
            }
            Call::ContinueOnFree { task } => {
                let back_at = self
                    .task(&task)
                    .and_then(|l| l.task.ran_out.as_ref().map(|r| r.back_at))
                    .unwrap_or_else(|| self.limit_reset(crate::needs::now()));
                let claude_model = self.task(&task).and_then(|l| {
                    l.task
                        .ran_out
                        .as_ref()
                        .map(|r| r.claude_model.clone())
                        .unwrap_or(l.task.model.clone())
                });
                self.continue_on_free(&task, back_at, claude_model)?;
                self.touched.insert(task);
                ok
            }
            Call::BackToClaude { task } => {
                let Some(r) = self.task(&task).and_then(|l| l.task.ran_out.clone()) else {
                    return Err("This task is already online.".into());
                };
                if r.on_free {
                    self.runner.hand_to(&task, r.claude_model.clone());
                    self.runner.note(
                        &task,
                        "Back online. Your next message carries on from a short brief of \
                         the work done on the free model.",
                    );
                } else {
                    self.runner
                        .note(&task, "Online is back: carry on when you like.");
                }
                self.runner.set_ran_out(&task, None, false);
                self.touched.insert(task);
                ok
            }
            Call::SetWeeklyCheck { on } => {
                self.settings.weekly_model_check = Some(on);
                self.settings.save();
                self.broadcast(Event::Settings {
                    settings: settings::service_part(&self.settings),
                });
                ok
            }
            Call::PhoneSettings => Ok(json!({
                "pc_name": pc_name(&self.settings),
                "relays": self.settings.phone_relays.unwrap_or(true),
            })),
            Call::SetPcName { name } => {
                let name = name.trim();
                if name.chars().count() > 40 {
                    return Err("That name is too long: 40 letters at most.".into());
                }
                self.settings.pc_name = (!name.is_empty()).then(|| name.to_string());
                self.settings.save();
                self.broadcast(Event::Settings {
                    settings: settings::service_part(&self.settings),
                });
                Ok(json!(pc_name(&self.settings)))
            }
            Call::SetRelays { on } => {
                self.settings.phone_relays = Some(on);
                self.settings.save();
                self.broadcast(Event::Settings {
                    settings: settings::service_part(&self.settings),
                });
                ok
            }
            Call::AlwaysAllowed { task } => {
                Ok(json!(crate::needs::load_rules(&self.store_dir, &task)))
            }
            Call::StopAllowing { task, file } => {
                crate::needs::remove_rule(&self.store_dir, &task, &file);
                self.runner.note(
                    &task,
                    &format!("Edits to {file} ask first again (from the phone)."),
                );
                self.touched.insert(task);
                ok
            }
            Call::NewIncognito {
                project,
                text,
                model,
                mode,
                ground_rules,
            } => {
                // YOLO is never offered in Whisper.
                crate::yolo::phone_may(true, mode == mimir_proto::Mode::NoAsk)?;
                self.runner.next_rules = settings::without_old_default(ground_rules);
                let made = self
                    .runner
                    .new_incognito(Path::new(&project), &text, &model, mode);
                self.runner.next_rules = None;
                let id = made?;
                crate::log::write("incognito", "started", "");
                self.touched.insert(id.clone());
                Ok(json!(id))
            }
            Call::EndIncognito { task } => {
                if !self.runner.end_incognito(&task) {
                    return Err("That isn't a running Whisper task.".into());
                }
                crate::log::write("incognito", "ended", "");
                self.summary_pending.remove(&task);
                self.finished_at.remove(&task);
                ok
            }
            // Answered later, off this thread (see call_later).
            Call::Speak { .. }
            | Call::Listen { .. }
            | Call::WebSearch { .. }
            | Call::Lookup { .. } => Err("refused: not here".into()),
            Call::AnswerItem { ask_id, answer } => {
                let by = match self.clients.get(&client).and_then(|c| c.phone.clone()) {
                    Some(key) => format!("phone {}", key.chars().take(8).collect::<String>()),
                    None => "window".into(),
                };
                let phone = self.clients.get(&client).is_some_and(|c| c.phone.is_some());
                let r = self.answer_item(&ask_id, answer, &by, phone);
                self.publish();
                r
            }
            Call::Shutdown if self.pid_file.is_some() => ok,
            Call::Shutdown => {
                self.stop = true;
                ok
            }
            Call::PhoneKeys { .. } => ok,
        }
    }

    // ---- needs you ----

    /// Everything waiting for the owner, the same for every client: the
    /// oldest permission request of each task, Claude's questions, and
    /// asks from sessions and failing checks.
    fn needs_items(&self) -> Vec<NeedsYouItem> {
        let mut out = vec![];
        let now = crate::needs::now();
        for l in &self.runner.tasks {
            let t = &l.task;
            if t.archived {
                continue;
            }
            if let Some(p) = l.asks.front() {
                let r = &p.request;
                let preview = permission_preview(r);
                let ask_id = crate::needs::permission_id(&t.id, r);
                // PCS-12: stamp once at creation; never re-stamp on re-read.
                let created = crate::needs::stamp_or_now(&ask_id, &self.needs_sent, now);
                out.push(NeedsYouItem {
                    ask_id,
                    kind: NeedsKind::Permission,
                    project: t.project.clone(),
                    task: Some(t.id.clone()),
                    title: t.title.clone(),
                    text: r.words(),
                    preview,
                    choices: vec![],
                    // A Whisper is never marked serious (no sound on a phone).
                    serious: !t.incognito,
                    created,
                    link: None,
                });
            }
            if !l.busy()
                && t.question_waiting()
                && let Some(Entry::Claude(text)) = t.entries.last()
            {
                let question = crate::task::question_split(text)
                    .map(|(_, q)| q)
                    .unwrap_or_else(|| text.clone());
                let ask_id = crate::needs::question_id(&t.id, t.entries_dropped + t.entries.len());
                // PCS-12: stamp once at creation; never re-stamp on re-read.
                let created = crate::needs::stamp_or_now(&ask_id, &self.needs_sent, now);
                out.push(NeedsYouItem {
                    ask_id,
                    kind: NeedsKind::Question,
                    project: t.project.clone(),
                    task: Some(t.id.clone()),
                    title: t.title.clone(),
                    choices: crate::task::question_choices(&question),
                    text: question,
                    preview: vec![],
                    serious: !t.incognito,
                    created,
                    link: None,
                });
            }
        }
        for o in &self.outside {
            let r = &o.ask.request;
            out.push(NeedsYouItem {
                ask_id: o.ask_id.clone(),
                kind: NeedsKind::Permission,
                project: o.ask.project.clone(),
                task: None,
                title: o.ask.from_words(),
                text: format!("{}\n{}", r.words(), o.ask.from_words()),
                preview: permission_preview(r),
                choices: crate::approve::CHOICES
                    .iter()
                    .map(|c| c.to_string())
                    .collect(),
                serious: true,
                created: o.created,
                link: None,
            });
        }
        for a in self.asks.iter().filter(|a| a.answer.is_none()) {
            out.push(NeedsYouItem {
                ask_id: a.ask_id.clone(),
                kind: a.kind,
                project: a.project.clone(),
                task: a.task.clone(),
                title: match a.kind {
                    NeedsKind::CheckFix => "Check a fix".into(),
                    NeedsKind::Approve => "Approve".into(),
                    NeedsKind::Choose => "Choose".into(),
                    NeedsKind::Release => "Release?".into(),
                    NeedsKind::CheckFailing => "A core check started failing".into(),
                    _ => "A question for you".into(),
                },
                text: a.text.clone(),
                preview: vec![],
                choices: a.choices.clone(),
                serious: a.kind.serious(),
                created: a.created,
                link: a.link.clone(),
            });
        }
        out
    }

    /// Requests a phone's "always allow this file (this task)" rule covers
    /// are answered at once.
    fn allow_by_rules(&mut self) {
        let mut allow = vec![];
        for l in &self.runner.tasks {
            if let Some(p) = l.asks.front()
                && crate::needs::rule_allows(&self.store_dir, &l.task.id, &p.request)
            {
                allow.push((
                    l.task.id.clone(),
                    crate::needs::edited_file(&p.request).unwrap_or_default(),
                ));
            }
        }
        for (task, file) in allow {
            self.runner.answer(&task, Answer::Allow);
            crate::log::write(
                "needs",
                "allowed by the phone's rule",
                &format!("{task}: {file}"),
            );
            self.touched.insert(task);
        }
    }

    /// Tasks whose work ended since the last look: one item for each, kept
    /// for the snapshot's recent list. A task counts as ended only after it
    /// stayed idle for [`FINISH_AFTER`] (no false end between a turn and a
    /// background command it started). Nothing for a Whisper, for a task
    /// that waits for a permission, a plan or a question (those are
    /// needs-you items), or for a pause.
    fn note_finished(&mut self) -> Vec<mimir_proto::FinishedItem> {
        use mimir_proto::{FinishedHow, FinishedItem};
        let first = self.was_working.is_none();
        let mut state = self.was_working.take().unwrap_or_default();
        state.retain(|id, _| self.runner.tasks.iter().any(|l| &l.task.id == id));
        let at = crate::needs::now();
        let mut out = vec![];
        for l in &self.runner.tasks {
            let t = &l.task;
            if t.incognito {
                continue;
            }
            let active = l.working_now() || t.status == Status::NeedsYou || l.background_pending();
            let st = state.entry(t.id.clone()).or_insert(FinishState {
                working: active && !first,
                idle_since: None,
            });
            if active {
                st.working = true;
                st.idle_since = None;
                if l.working_now() {
                    self.runner.owner_stopped.remove(&t.id);
                }
                continue;
            }
            if !st.working {
                continue;
            }
            let since = *st.idle_since.get_or_insert_with(Instant::now);
            if since.elapsed() < FINISH_AFTER {
                continue;
            }
            st.working = false;
            st.idle_since = None;
            if t.needs_owner() {
                continue;
            }
            let stopped = self.runner.owner_stopped.remove(&t.id);
            let how = match t.status {
                Status::Done => FinishedHow::Ok,
                Status::Stopped if stopped => FinishedHow::Stopped,
                Status::Stopped => FinishedHow::Failed,
                _ => continue,
            };
            self.finished_n += 1;
            out.push(FinishedItem {
                id: finished_id(&t.id, at, self.finished_n),
                task: t.id.clone(),
                how,
                at,
            });
        }
        self.was_working = Some(state);
        for i in &out {
            self.finished.push_back(i.clone());
            if self.finished.len() > FINISHED_KEPT {
                self.finished.pop_front();
            }
        }
        out
    }

    /// Sends the needs-you list when it changed.
    fn publish_needs(&mut self) -> bool {
        let items = self.needs_items();
        let ids: Vec<&String> = items.iter().map(|i| &i.ask_id).collect();
        let sent: Vec<&String> = self.needs_sent.iter().map(|i| &i.ask_id).collect();
        // Created times are "now" for task items: compare without them.
        let changed = ids != sent
            || items
                .iter()
                .zip(&self.needs_sent)
                .any(|(a, b)| a.text != b.text || a.choices != b.choices || a.title != b.title);
        if changed {
            self.needs_sent = items.clone();
            self.broadcast(Event::NeedsYou { items });
        }
        changed
    }

    /// The owner's answer to a needs-you item; the first answer wins.
    fn answer_item(
        &mut self,
        ask_id: &str,
        answer: ItemAnswer,
        by: &str,
        phone: bool,
    ) -> Result<serde_json::Value, String> {
        let item = self
            .needs_items()
            .into_iter()
            .find(|i| i.ask_id == ask_id)
            .ok_or("That was already answered.")?;
        if let Some(i) = self.outside.iter().position(|o| o.ask_id == ask_id) {
            use crate::approve::Decision as D;
            let d = match answer {
                ItemAnswer::Allow | ItemAnswer::Option(0) => D::Allow,
                ItemAnswer::DontAllow | ItemAnswer::Option(1) => D::Deny,
                ItemAnswer::AlwaysProject | ItemAnswer::Option(2) => D::Always,
                ItemAnswer::KeepForLater | ItemAnswer::Option(3) => D::AskOnPc,
                _ => return Err("That answer doesn't fit this item.".into()),
            };
            // Always allow writes a lasting rule: it is given only on the PC.
            if phone && d == D::Always {
                return Err("Always allow can only be chosen on the PC.".into());
            }
            let o = self.outside.remove(i);
            let _ = o.reply.send(crate::approve::reply_word(d).into());
            crate::log::write(
                "needs",
                "answered",
                &format!("{ask_id} by {by}: {}", crate::approve::reply_word(d)),
            );
            self.publish();
            return Ok(serde_json::Value::Null);
        }
        if !answer.fits(item.kind) {
            return Err("That answer doesn't fit this item.".into());
        }
        let task = item.task.clone().unwrap_or_default();
        if !task.is_empty() {
            self.touched.insert(task.clone());
        }
        match item.kind {
            NeedsKind::Permission => {
                if answer == ItemAnswer::AllowFileThisTask {
                    let file = self
                        .task(&task)
                        .and_then(|l| l.asks.front())
                        .and_then(|p| crate::needs::edited_file(&p.request))
                        .ok_or("Only an edit to a file can be always allowed.")?;
                    crate::needs::add_rule(&self.store_dir, &task, &file);
                    self.runner.note(
                        &task,
                        &format!("Edits to {file} are allowed for the rest of this task (from the phone)."),
                    );
                }
                self.runner.answer(
                    &task,
                    match answer {
                        ItemAnswer::DontAllow => Answer::No,
                        ItemAnswer::AlwaysProject => Answer::Always,
                        _ => Answer::Allow,
                    },
                );
            }
            NeedsKind::Question => match &answer {
                ItemAnswer::KeepForLater => self.runner.dismiss_question(&task),
                _ => {
                    let text = answer.words(&item.choices);
                    if text.trim().is_empty() {
                        return Err("That choice isn't there.".into());
                    }
                    self.runner.send(&task, &text);
                }
            },
            _ => {
                if let ItemAnswer::Option(n) = answer
                    && n >= item.choices.len()
                {
                    return Err("That choice isn't there.".into());
                }
                let words = answer.words(&item.choices);
                if answer == ItemAnswer::AskClaudeToFix {
                    let prompt = format!(
                        "{}; find out why and fix it.",
                        item.text.trim_end_matches('.')
                    );
                    let id = self.new_task_ask(&item.project, &prompt);
                    self.touched.insert(id);
                }
                if let Some(a) = self.asks.iter_mut().find(|a| a.ask_id == ask_id) {
                    a.answer = Some(words.clone());
                    a.answered_by = Some(by.to_string());
                    a.answered_at = Some(crate::needs::now());
                    crate::needs::save_ask(&crate::needs::asks_path(), a);
                }
                let waiting = self.ask_waiters.remove(ask_id).unwrap_or_default();
                // A Mímir task that asked and isn't waiting is told
                // in its conversation.
                if waiting.is_empty()
                    && let Some(t) = item.task.as_ref()
                    && self.task(t).is_some()
                {
                    self.runner
                        .send(t, &format!("The owner answered \"{}\": {words}", item.text));
                }
                for w in waiting {
                    let _ = w.send(words.clone());
                }
            }
        }
        crate::log::write("needs", "answered", &format!("{ask_id} by {by}"));
        Ok(serde_json::Value::Null)
    }

    /// A new task in "Ask me first" with the project's model.
    fn new_task_ask(&mut self, project: &str, text: &str) -> String {
        let d = self
            .settings
            .defaults
            .get(project)
            .cloned()
            .unwrap_or_default();
        self.runner.new_task_with(
            Path::new(project),
            text,
            &[],
            crate::task::Mode::Ask,
            d.model,
            d.effort,
        )
    }

    /// A message from a Claude session on the session socket.
    fn session_msg(&mut self, msg: SessionMsg, task: Option<String>, reply: Sender<String>) {
        match msg {
            SessionMsg::Ask {
                text,
                choices,
                wait,
                project,
                kind,
                link,
            } => {
                let kind = match kind.as_deref() {
                    Some(k) => match crate::needs::kind_of(k) {
                        Some(k) => k,
                        None => {
                            let _ = reply.send(format!(
                                "refused: --kind is check-fix, approve, choose or release, not {k}"
                            ));
                            return;
                        }
                    },
                    None if choices.is_empty() => NeedsKind::Approve,
                    None => NeedsKind::Choose,
                };
                let project = project
                    .or_else(|| {
                        task.as_ref()
                            .and_then(|t| self.task(t))
                            .map(|l| l.task.project.clone())
                    })
                    .unwrap_or_default();
                let choices = if choices.is_empty() {
                    crate::needs::default_choices(kind)
                } else {
                    choices
                };
                let a = AskRecord {
                    ask_id: crate::needs::new_id("ask"),
                    kind,
                    project,
                    task: task.filter(|t| self.task(t).is_some()),
                    text,
                    choices,
                    link,
                    created: crate::needs::now(),
                    answer: None,
                    answered_by: None,
                    answered_at: None,
                };
                crate::needs::save_ask(&crate::needs::asks_path(), &a);
                let id = a.ask_id.clone();
                self.asks.push(a);
                if wait {
                    self.ask_waiters.entry(id).or_default().push(reply);
                } else {
                    let _ = reply.send(id);
                }
                self.publish();
            }
            SessionMsg::Permission(ask) => {
                // A Mímir task asks through its own hook.
                if task.is_some() {
                    let _ = reply
                        .send(crate::approve::reply_word(crate::approve::Decision::AskOnPc).into());
                    return;
                }
                let ask_id = crate::needs::new_id("perm-pc");
                crate::log::write(
                    "needs",
                    "permission from a session",
                    &format!("{ask_id}: {} in {}", ask.request.tool_name, ask.project),
                );
                self.outside.push(Outside {
                    ask_id,
                    ask: *ask,
                    created: crate::needs::now(),
                    reply,
                });
                self.publish();
            }
            SessionMsg::Wait { ask_id } => match self.asks.iter().find(|a| a.ask_id == ask_id) {
                Some(a) if a.answer.is_some() => {
                    let _ = reply.send(a.answer.clone().unwrap_or_default());
                }
                Some(_) => self.ask_waiters.entry(ask_id).or_default().push(reply),
                None => {
                    let _ = reply.send("refused: no such ask".into());
                }
            },
        }
    }

    // ---- the general chat ----

    /// Each project as routing sees it: its names and aliases, its README
    /// and CLAUDE.md titles and its last task titles.
    fn project_words(&self) -> Vec<crate::route::ProjectWords> {
        let tasks: Vec<&Task> = self.runner.tasks.iter().map(|l| &l.task).collect();
        crate::route::project_words(&self.settings, &tasks)
    }

    /// Whether a task is still "this conversation": working, waiting, or
    /// done less than 10 minutes ago, and not archived.
    fn still_open(&self, task: &str) -> bool {
        let Some(l) = self.task(task) else {
            return false;
        };
        if l.task.archived {
            return false;
        }
        match l.task.status {
            Status::Working | Status::NeedsYou => true,
            Status::Done => self
                .finished_at
                .get(task)
                .is_some_and(|t| crate::needs::now() - t < STILL_OPEN_S),
            _ => false,
        }
    }

    /// Files one general-chat message from a phone that doesn't talk yet
    /// (an older app): the strict router (a project only when named on
    /// purpose), a follow-up to the task it just started, else a task in
    /// the chats folder, never General.
    fn chat(&mut self, msg_id: &str, text: &str) -> Result<Filed, String> {
        use crate::route::{How, Kind};
        let now = crate::needs::now();
        let r = crate::route::strict(text, &self.project_words());
        let _kind = match r.kind {
            Kind::Status => "status",
            Kind::Idea => "idea",
            Kind::Request => "request",
        };
        // The task the conversation is already in, while it's open and no
        // other project is named.
        let same = self
            .last_chat
            .as_ref()
            .filter(|c| now - c.time <= crate::route::SAME_CONVERSATION_S)
            .filter(|c| {
                r.project
                    .as_ref()
                    .is_none_or(|p| c.project.as_ref().is_some_and(|q| crate::paths::same(p, q)))
            })
            .and_then(|c| {
                let t = c.task.clone().filter(|t| self.still_open(t));
                Some((c.project.clone()?, t))
            });
        let filed = match (&r.kind, &r.project) {
            (Kind::Status, project) => {
                let page = self.status_page(false);
                Filed::Status {
                    reply: crate::facts::reply(&page.projects, project.as_deref()),
                }
            }
            (Kind::Idea, Some(p)) => {
                let i = crate::chat::add_idea(p, &r.text, msg_id);
                Filed::Idea {
                    project: p.clone(),
                    idea: i.id,
                }
            }
            _ => match same {
                Some((project, Some(t))) => {
                    self.runner.send(&t, &r.text);
                    self.touched.insert(t.clone());
                    Filed::SameTask { project, task: t }
                }
                same => {
                    let p = match (r.project.as_ref(), same.as_ref()) {
                        (Some(p), _) | (None, Some((p, None))) => p.clone(),
                        (None, _) => {
                            let dir = crate::paths::chats_dir();
                            std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
                            dir.display().to_string()
                        }
                    };
                    let t = self.new_task_ask(&p, &r.text);
                    if r.how == How::Keywords {
                        self.file_placed(&t, &p, Placed::Sure, None);
                    }
                    self.touched.insert(t.clone());
                    Filed::NewTask {
                        project: p,
                        task: t,
                    }
                }
            },
        };
        let (project, task) = match &filed {
            Filed::NewTask { project, task } | Filed::SameTask { project, task } => {
                (Some(project.clone()), Some(task.clone()))
            }
            Filed::Idea { project, .. } => (Some(project.clone()), None),
            _ => (r.project.clone(), None),
        };
        self.last_chat = Some(crate::route::Previous {
            time: now,
            project,
            task,
        });
        Ok(filed)
    }

    /// "Move it": the task stops, and starts again in `project` with the
    /// message and the handover brief.
    fn move_task(&mut self, task: &str, project: &str) -> Result<String, String> {
        let l = self.task(task).ok_or("That task is gone.")?;
        if l.task.project == project {
            return Err("It's already in that project.".into());
        }
        let first = l
            .task
            .entries
            .iter()
            .find_map(|e| match e {
                Entry::You(t) => Some(t.clone()),
                _ => None,
            })
            .unwrap_or_default();
        let changed = !l.task.files_changed.is_empty();
        let from = crate::paths::display_name(&self.settings, &l.task.project);
        let brief: String = crate::auto::brief(&l.task)
            .lines()
            .take(12)
            .collect::<Vec<_>>()
            .join("\n");
        // A phone's ground rules go with it.
        let rules = l.task.ground_rules.clone();
        self.runner.stop(task);
        // Its guess is settled: the placement note goes.
        self.runner.set_placed(task, None, None);
        let text = format!(
            "{first}\n\n(This was first started in {from} by mistake. What happened there:\n{brief})"
        );
        self.runner.next_rules = rules;
        let id = self.new_task_ask(project, &text);
        self.runner.next_rules = None;
        let to = crate::paths::display_name(&self.settings, project);
        let note = if changed {
            format!(
                "Moved to {to}. Changed files here stay as they are: Undo in the window can \
                 take them back."
            )
        } else {
            format!("Moved to {to}.")
        };
        self.runner.note(task, &note);
        self.touched.insert(task.to_string());
        self.touched.insert(id.clone());
        Ok(id)
    }

    /// Where a task started in the chats folder goes: the project it names
    /// on purpose (strict router), else it stays a chat in the chats folder.
    fn place_chat(&mut self, text: &str) -> Result<(String, Option<Placed>), String> {
        let r = crate::route::strict(text, &self.project_words());
        Ok(match r.project {
            Some(p) => (p, Some(Placed::Sure)),
            None => {
                let dir = crate::paths::chats_dir();
                std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
                (dir.display().to_string(), None)
            }
        })
    }

    /// General, the home for anything that fits no project: the listed
    /// project of that name, else the folder beside the others (made if
    /// needed), added to the list.
    fn general_project(&mut self) -> Result<String, String> {
        if let Some(p) = self.settings.projects.iter().find(|p| {
            crate::paths::project_name(p).eq_ignore_ascii_case("General") && Path::new(p).is_dir()
        }) {
            return Ok(p.clone());
        }
        let (dir, _) = crate::paths::make_project_folder(&self.settings.projects, "General")?;
        let p = crate::paths::clean_path(&dir.display().to_string());
        if !self
            .settings
            .projects
            .iter()
            .any(|q| crate::paths::same(q, &p))
        {
            self.settings.projects.push(p.clone());
            self.settings.save();
            self.broadcast(Event::Settings {
                settings: settings::service_part(&self.settings),
            });
        }
        Ok(p)
    }

    /// Marks a task as filed by Mímir; a guess gets its placement
    /// note in the project.
    fn file_placed(&mut self, id: &str, project: &str, how: Placed, why: Option<&str>) {
        let note = match (how, why) {
            (Placed::Unsure, Some(why)) => {
                let title = self
                    .task(id)
                    .map(|l| l.task.title.clone())
                    .unwrap_or_default();
                crate::placement::write(Path::new(project), id, &title, why, crate::needs::now())
                    .map_err(|e| {
                        crate::log::write("service", "placement note not written", &e.to_string())
                    })
                    .ok()
            }
            _ => None,
        };
        self.runner.set_placed(id, Some(how), note);
        let _how_word = match how {
            Placed::Sure => "sure",
            Placed::Unsure => "unsure",
            Placed::General => "general",
        };
    }

    /// A guess whose task committed to its project is confirmed: the note
    /// goes.
    fn confirm_by_work(&mut self) {
        let done: Vec<String> = self
            .runner
            .tasks
            .iter()
            .filter(|l| l.task.placement_note.is_some() && crate::placement::committed(&l.task))
            .map(|l| l.task.id.clone())
            .collect();
        for id in done {
            self.runner.set_placed(&id, None, None);
            self.touched.insert(id);
        }
    }

    /// "Move to…": the task, conversation and all, carries on in `project`.
    fn move_to(&mut self, task: &str, project: &str) -> Result<(), String> {
        let from = self
            .task(task)
            .map(|l| crate::paths::display_name(&self.settings, &l.task.project))
            .ok_or("That task is gone.")?;
        self.runner.move_to(task, project)?;
        let to = crate::paths::display_name(&self.settings, project);
        self.runner.note(
            task,
            &format!(
                "Moved from {from} to {to}. It carries on here; changed files in {from} \
                 stay there."
            ),
        );
        // The project it went to is listed from now on.
        if !self
            .settings
            .projects
            .iter()
            .any(|p| crate::paths::same(p, project))
        {
            self.settings
                .projects
                .push(crate::paths::clean_path(project));
            self.settings.save();
            self.broadcast(Event::Settings {
                settings: settings::service_part(&self.settings),
            });
        }
        Ok(())
    }

    /// "Merge into…": every task of `from` carries on in `into`, and `from`
    /// leaves the list; its names find `into` from now on. All or nothing:
    /// a task that can't move yet stops the merge before anything moves.
    fn merge_project(&mut self, from: &str, into: &str) -> Result<usize, String> {
        use crate::paths::same;
        if same(from, into) {
            return Err("That's the same project.".into());
        }
        if !Path::new(into).is_dir() {
            return Err("That project folder isn't there any more.".into());
        }
        let ids: Vec<String> = self
            .runner
            .tasks
            .iter()
            .filter(|l| same(l.task.home_project(), from))
            .map(|l| l.task.id.clone())
            .collect();
        // Every task is asked first: one that can't move stops the merge
        // before anything moves.
        for id in &ids {
            let Some(l) = self.task(id) else { continue };
            if let Err(reason) = self.runner.can_move(id, into) {
                return Err(format!(
                    "\"{}\": {reason} Nothing was moved. Finish or stop that task, then try again.",
                    l.task.title
                ));
            }
        }
        let from_name = crate::paths::display_name(&self.settings, from);
        let into_name = crate::paths::display_name(&self.settings, into);
        let mut moved = 0;
        let mut failed: Vec<String> = vec![];
        // Never left half done: each task goes on, and what couldn't is said.
        for id in &ids {
            match self.runner.move_to(id, into) {
                Ok(()) => {
                    self.runner.note(
                        id,
                        &format!(
                            "Moved from {from_name} to {into_name} with the rest of {from_name}."
                        ),
                    );
                    self.touched.insert(id.clone());
                    moved += 1;
                }
                Err(e) => {
                    let title = self
                        .task(id)
                        .map(|l| l.task.title.clone())
                        .unwrap_or_default();
                    failed.push(format!("\"{title}\": {e}"));
                }
            }
        }
        if !failed.is_empty() {
            return Err(format!(
                "{moved} task(s) moved to {into_name}; {from_name} stays on the list because \
                 these couldn't move: {}. Try them again when they are free.",
                failed.join(" ")
            ));
        }
        self.fold_names(from, into);
        Ok(moved)
    }

    /// A project that went into another: its names find that one from now
    /// on, and it leaves the list.
    fn fold_names(&mut self, from: &str, into: &str) {
        use crate::paths::same;
        let from_name = crate::paths::display_name(&self.settings, from);
        let into_name = crate::paths::display_name(&self.settings, into);
        let mut names = vec![crate::paths::project_name(from), from_name];
        names.extend(self.settings.aliases.remove(from).unwrap_or_default());
        let mut known = vec![crate::paths::project_name(into), into_name.clone()];
        known.extend(self.settings.aliases.get(into).cloned().unwrap_or_default());
        let aliases = self.settings.aliases.entry(into.to_string()).or_default();
        for n in names {
            let lower = n.to_lowercase();
            if !n.trim().is_empty() && !known.iter().any(|k| k.to_lowercase() == lower) {
                known.push(n.clone());
                aliases.push(n);
            }
        }
        if aliases.is_empty() {
            self.settings.aliases.remove(into);
        }
        let s = &mut self.settings;
        s.projects.retain(|p| !same(p, from));
        if !s.projects.iter().any(|p| same(p, into)) {
            s.projects.push(crate::paths::clean_path(into));
        }
        s.project_names.remove(from);
        s.remote_projects.retain(|p| !same(p, from));
        s.defaults.remove(from);
        s.save();
        self.broadcast(Event::Settings {
            settings: settings::service_part(&self.settings),
        });
    }

    // ---- Tidy projects ----

    /// Every conversation on this PC: open tasks, and Claude Code sessions
    /// that aren't tasks (and weren't deleted as tasks).
    fn conversations(&self) -> Vec<crate::tidy::Conversation> {
        let mut out = vec![];
        for l in &self.runner.tasks {
            let t = &l.task;
            if t.archived || t.incognito || t.origin.is_some() {
                continue;
            }
            let text: String = t
                .entries
                .iter()
                .filter_map(|e| match e {
                    Entry::You(s) => Some(s.as_str()),
                    _ => None,
                })
                .collect::<Vec<_>>()
                .join("\n")
                .chars()
                .take(3000)
                .collect();
            let messages = t
                .entries
                .iter()
                .filter(|e| matches!(e, Entry::You(_)))
                .count();
            // Claude Code elsewhere (a terminal) may still be on it.
            let elsewhere = !l.running()
                && t.session_id.as_ref().is_some_and(|sid| {
                    crate::sessions::in_use(
                        &self.runner.transcript(&t.id).unwrap_or_default(),
                        sid,
                        Some(Path::new(&t.project)),
                        true,
                    )
                });
            out.push(crate::tidy::Conversation {
                task: Some(t.id.clone()),
                session: None,
                folder: t.project.clone(),
                title: t.title.clone(),
                text,
                messages,
                files_changed: t.files_changed.len(),
                busy: l.busy() || elsewhere,
            });
        }
        for o in crate::sessions::recent(500) {
            let sid = &o.session.id;
            if self.settings.hidden_sessions.contains(sid)
                || self
                    .runner
                    .tasks
                    .iter()
                    .any(|l| l.task.session_id.as_deref() == Some(sid.as_str()))
            {
                continue;
            }
            let (text, messages) = crate::sessions::owner_text(&o.session.path, 3000);
            out.push(crate::tidy::Conversation {
                task: None,
                session: Some(o.session.path.display().to_string()),
                folder: crate::paths::clean_path(&o.project.display().to_string()),
                title: o
                    .title
                    .clone()
                    .unwrap_or_else(|| crate::runner::title_from(&o.session.first)),
                text,
                messages,
                files_changed: 0,
                busy: crate::sessions::in_use(&o.session.path, sid, Some(&o.project), true),
            });
        }
        out
    }

    /// What "Tidy projects…" proposes now.
    fn tidy_plan(&self) -> Vec<TidyRow> {
        let listed = self.settings.projects.clone();
        let parent = crate::paths::projects_parent(&listed);
        let mut repos = crate::paths::unlisted_repos(&listed);
        repos.extend(
            listed
                .iter()
                .filter(|p| {
                    let p = Path::new(p);
                    p.parent() == Some(parent.as_path()) && p.join(".git").exists()
                })
                .cloned(),
        );
        repos.sort_by_key(|p| crate::paths::project_name(p).to_lowercase());
        let random = self
            .settings
            .projects
            .iter()
            .find(|p| crate::paths::project_name(p).eq_ignore_ascii_case("General"))
            .cloned()
            .unwrap_or_else(|| parent.join("General").display().to_string());
        // Routing knows every project the tidy may send to.
        let mut known = settings::with_part(&self.settings, &serde_json::Value::Null);
        for r in &repos {
            if !known.projects.iter().any(|p| crate::paths::same(p, r)) {
                known.projects.push(r.clone());
            }
        }
        let tasks: Vec<&Task> = self.runner.tasks.iter().map(|l| &l.task).collect();
        let words = crate::route::project_words(&known, &tasks);
        let home = crate::paths::home();
        let chats = crate::paths::chats_dir();
        crate::tidy::plan(
            &self.conversations(),
            &crate::tidy::World {
                home: &home,
                chats: &chats,
                parent: &parent,
                listed: &listed,
                repos: &repos,
                words: &words,
                random: &random,
            },
        )
    }

    /// Makes the ticked changes of a tidy. Conversations Claude is still on
    /// wait, and move when it stops.
    fn tidy(&mut self, rows: Vec<TidyRow>) -> serde_json::Value {
        use crate::paths::same;
        let rows: Vec<TidyRow> = rows.into_iter().filter(|r| r.on).collect();
        let mut problems: Vec<String> = vec![];
        let (mut done, mut waiting) = (0, 0);
        for r in rows.iter().filter(|r| r.kind == TidyKind::Add) {
            if Path::new(&r.folder).is_dir()
                && !self.settings.projects.iter().any(|p| same(p, &r.folder))
            {
                self.settings
                    .projects
                    .push(crate::paths::clean_path(&r.folder));
                done += 1;
            }
        }
        // General is made (and listed) when something goes there.
        let goes_to_random = rows.iter().any(|r| {
            r.to.as_deref()
                .is_some_and(|t| crate::paths::project_name(t).eq_ignore_ascii_case("General"))
                && r.to.as_deref().is_some_and(|t| !Path::new(t).is_dir())
        });
        let random = if goes_to_random {
            match self.general_project() {
                Ok(p) => Some(p),
                Err(e) => {
                    problems.push(e);
                    None
                }
            }
        } else {
            None
        };
        for mut r in rows
            .iter()
            .filter(|r| r.kind == TidyKind::Conversation)
            .cloned()
        {
            if let (Some(p), Some(t)) = (&random, &r.to)
                && crate::paths::project_name(t).eq_ignore_ascii_case("General")
            {
                r.to = Some(p.clone());
            }
            // A session becomes a task first (archived ones are kept so).
            if r.task.is_none()
                && let Some(path) = &r.session
            {
                let Some(past) = crate::sessions::past(Path::new(path)) else {
                    problems.push(format!("\"{}\" could not be read.", r.title));
                    continue;
                };
                match self.runner.open_past(Path::new(&r.folder), &past) {
                    Ok(id) => {
                        self.touched.insert(id.clone());
                        r.task = Some(id);
                    }
                    Err(_) => {
                        problems.push(format!(
                            "\"{}\" could not be opened. Check that its folder is still there.",
                            r.title
                        ));
                        continue;
                    }
                }
            }
            if self.tidy_waits(&r) {
                waiting += 1;
                self.tidy_waiting_mut().push(r);
                continue;
            }
            match self.tidy_one(&r) {
                Ok(()) => done += 1,
                Err(e) => problems.push(format!("\"{}\": {e}", r.title)),
            }
        }
        for r in rows.iter().filter(|r| r.kind != TidyKind::Conversation) {
            match (r.kind, &r.to) {
                (TidyKind::Merge, Some(into)) => {
                    self.fold_names(&r.folder, into);
                    done += 1;
                }
                (TidyKind::Unlist, _) => {
                    let s = &mut self.settings;
                    s.projects.retain(|p| !same(p, &r.folder));
                    s.remote_projects.retain(|p| !same(p, &r.folder));
                    s.aliases.retain(|p, _| !same(p, &r.folder));
                    s.defaults.retain(|p, _| !same(p, &r.folder));
                    s.project_names.retain(|p, _| !same(p, &r.folder));
                    done += 1;
                }
                _ => {}
            }
        }
        self.settings.save();
        self.broadcast(Event::Settings {
            settings: settings::service_part(&self.settings),
        });
        self.save_tidy_waiting();
        crate::log::write(
            "service",
            "tidy",
            &format!(
                "{done} done, {waiting} waiting, {} problems",
                problems.len()
            ),
        );
        serde_json::json!({"done": done, "waiting": waiting, "problems": problems})
    }

    /// Whether Claude is still on a tidy's conversation.
    fn tidy_waits(&self, r: &TidyRow) -> bool {
        let Some(l) = r.task.as_deref().and_then(|id| self.task(id)) else {
            return false;
        };
        let t = &l.task;
        l.busy()
            || (!l.running()
                && t.session_id.as_ref().is_some_and(|sid| {
                    crate::sessions::in_use(
                        &self.runner.transcript(&t.id).unwrap_or_default(),
                        sid,
                        Some(Path::new(&t.project)),
                        true,
                    )
                }))
    }

    /// Moves and archives one conversation.
    fn tidy_one(&mut self, r: &TidyRow) -> Result<(), String> {
        let id = r.task.clone().ok_or("It isn't a task.")?;
        let l = self.task(&id).ok_or("That task is gone.")?;
        if let Some(to) = &r.to
            && !crate::paths::same(&l.task.project, to)
        {
            let from = crate::paths::display_name(&self.settings, &l.task.project);
            self.runner.move_to(&id, to)?;
            let name = crate::paths::display_name(&self.settings, to);
            let note = format!("Moved from {from} to {name} by Tidy projects.");
            // A conversation opened from Claude Code's history is read again
            // (a terminal may have added to it); a task of Mímir's
            // own keeps its history and just gets the note.
            if r.session.is_none() || self.runner.reload_from_session(&id, &note).is_err() {
                self.runner.note(&id, &note);
            }
            if !self
                .settings
                .projects
                .iter()
                .any(|p| crate::paths::same(p, to))
            {
                self.settings.projects.push(crate::paths::clean_path(to));
                self.settings.save();
                self.broadcast(Event::Settings {
                    settings: settings::service_part(&self.settings),
                });
            }
        }
        if r.archive {
            self.runner.set_archived(&id, true);
        }
        self.touched.insert(id);
        Ok(())
    }

    fn tidy_waiting_mut(&mut self) -> &mut Vec<TidyRow> {
        self.tidy_waiting.get_or_insert_with(|| {
            std::fs::read(tidy_waiting_path())
                .ok()
                .and_then(|b| serde_json::from_slice(&b).ok())
                .unwrap_or_default()
        })
    }

    fn save_tidy_waiting(&mut self) {
        let list = self.tidy_waiting_mut().clone();
        let p = tidy_waiting_path();
        if list.is_empty() {
            let _ = std::fs::remove_file(p);
        } else if let Ok(b) = serde_json::to_vec_pretty(&list) {
            let _ = std::fs::write(p, b);
        }
    }

    /// Tidy moves that waited for Claude to stop, done once it has.
    fn tidy_when_stopped(&mut self) {
        if self
            .tidy_checked
            .is_some_and(|t| t.elapsed() < Duration::from_secs(30))
        {
            return;
        }
        self.tidy_checked = Some(Instant::now());
        let list = std::mem::take(self.tidy_waiting_mut());
        if list.is_empty() {
            return;
        }
        let mut still = vec![];
        for r in list {
            if r.task.as_deref().is_none_or(|id| self.task(id).is_none()) {
                continue;
            }
            if self.tidy_waits(&r) {
                still.push(r);
            } else if let Err(e) = self.tidy_one(&r) {
                eprintln!("mimir: tidy projects: \"{}\": {e}", r.title);
                self.runner.problem(&format!(
                    "Tidying \"{}\" didn't finish. It is tried again the next time Mímir tidies.",
                    r.title
                ));
            }
        }
        *self.tidy_waiting_mut() = still;
        self.save_tidy_waiting();
    }

    // ---- status from facts ----

    /// The status page now. With `with_git`, git state too (a little slower).
    fn status_page(&self, with_git: bool) -> StatusPage {
        let items = self.needs_items();
        let log_dir = crate::facts::log_dir();
        let list: Vec<String> = self
            .pickable_projects()
            .into_iter()
            .filter(|p| !crate::paths::same(p, &crate::paths::chats_dir().display().to_string()))
            .collect();
        let names = crate::paths::list_names(&self.settings, &list);
        let projects: Vec<StatusFacts> = list
            .iter()
            .zip(names)
            .map(|(p, name)| {
                let p = p.clone();
                let tasks: Vec<&runner::Live> = self
                    .runner
                    .tasks
                    .iter()
                    .filter(|l| {
                        crate::paths::project_in(&list, &l.task.project) == Some(p.as_str())
                    })
                    .collect();
                let work = crate::facts::Work {
                    name,
                    tasks_running: tasks.iter().filter(|l| l.working_now()).count(),
                    needs_you: items
                        .iter()
                        .filter(|i| crate::paths::project_in(&list, &i.project) == Some(p.as_str()))
                        .count(),
                    last_activity: tasks.iter().map(|l| l.task.created_unix).max(),
                    path: p,
                };
                crate::facts::project(&work, &log_dir, with_git)
            })
            .collect();

        crate::facts::page(
            projects,
            self.runner.weekly.clone(),
            self.runner.five_hour.clone(),
            crate::needs::now(),
        )
    }

    /// Reads the projects' run logs; a feature that goes from passing to
    /// failing adds a serious needs-you item.
    fn watch_checks(&mut self) {
        // Checks may read the logs more often.
        let every = std::env::var("MIMIR_CHECKS_EVERY_MS")
            .ok()
            .and_then(|v| v.parse().ok())
            .map(Duration::from_millis)
            .unwrap_or(CHECKS_EVERY);
        if self.checks_read.is_some_and(|t| t.elapsed() < every) {
            return;
        }
        self.checks_read = Some(Instant::now());
        let log_dir = crate::facts::log_dir();
        let projects =
            crate::paths::all_projects(&self.settings, self.runner.tasks.iter().map(|l| &l.task));
        let mut changed = false;
        let mut flips: Vec<(String, String)> = vec![];
        for p in projects {
            let Some(c) = crate::facts::checks(Path::new(&p), &crate::facts::run_log(&log_dir, &p))
            else {
                continue;
            };
            self.checks_cache.insert(p.clone(), c.clone());
            let commit = c.commit.clone().unwrap_or_default();
            for (id, sentence, passed) in &c.features {
                let key = (p.clone(), id.clone());
                let before = self.check_state.insert(key, *passed);
                let failing_now = c.failing.iter().any(|(f, _)| f == id);
                if before == Some(true) && failing_now {
                    let ask_id = crate::needs::check_id(&p, id, &commit);
                    if self.asks.iter().any(|a| a.ask_id == ask_id) {
                        continue;
                    }
                    let short: String = commit.chars().take(7).collect();
                    let a = AskRecord {
                        ask_id,
                        kind: NeedsKind::CheckFailing,
                        project: p.clone(),
                        task: None,
                        text: format!("The core check \"{sentence}\" started failing at {short}."),
                        choices: vec!["Ask to fix it".into()],
                        link: None,
                        created: crate::needs::now(),
                        answer: None,
                        answered_by: None,
                        answered_at: None,
                    };
                    crate::needs::save_ask(&crate::needs::asks_path(), &a);
                    self.asks.push(a);
                    flips.push((
                        p.clone(),
                        format!("A core check started failing: {sentence}."),
                    ));
                    changed = true;
                }
                if before == Some(false) && *passed {
                    flips.push((p.clone(), format!("A core check passes again: {sentence}.")));
                }
                // Passing again: its open item goes.
                if *passed {
                    for a in self.asks.iter_mut().filter(|a| {
                        a.kind == NeedsKind::CheckFailing
                            && a.answer.is_none()
                            && a.project == p
                            && a.text.contains(&format!("\"{sentence}\""))
                    }) {
                        a.answer = Some("Passing again".into());
                        a.answered_by = Some("checks".into());
                        a.answered_at = Some(crate::needs::now());
                        crate::needs::save_ask(&crate::needs::asks_path(), a);
                        changed = true;
                    }
                }
            }
        }
        // The failing check is a needs-you item already; Updates hold only
        // spoken summaries.
        if !flips.is_empty() {
            changed = true;
        }
        if changed {
            self.publish();
        }
    }

    /// Labs: what Claude is doing on this PC. Windows are told when it
    /// What the phone link reports.
    fn link_event(&mut self, e: mimir_link::server::LinkEvent) {
        use mimir_link::server::LinkEvent as L;
        match e {
            L::PairPending {
                pending,
                phone_name,
                words,
            } => self.broadcast(Event::PairPending {
                pending,
                phone_name,
                words,
            }),
            L::PairEnded { pending, paired } => {
                self.broadcast(Event::PairEnded { pending, paired })
            }
            L::Connected {
                conn,
                phone_key,
                person,
                events,
                ..
            } => {
                let client = 1_000_000 + conn;
                crate::devlog::log(
                    "link",
                    "connect",
                    vec![(
                        "phone",
                        phone_key.chars().take(8).collect::<String>().into(),
                    )],
                );
                self.link_conns.insert(conn, client);
                let events = bounded_link_queue(events);
                self.clients.insert(
                    client,
                    Client {
                        events,
                        wake: Arc::new(|| {}),
                        phone: Some(phone_key),
                        person,
                        ready: false,
                        focused: false,
                        features: vec![],
                        own_seq: 0,
                    },
                );
            }
            L::Call { conn, msg } => {
                let Some(&client) = self.link_conns.get(&conn) else {
                    return;
                };
                // What this person may ask is decided here, before anything
                // else looks at the call.
                if let Some(result) = self.person_gate(client, &msg.call) {
                    self.send_to(
                        client,
                        Event::Reply {
                            call_id: msg.call_id,
                            result,
                        },
                    );
                    return;
                }
                // Slow calls are answered off this thread, as from the window: a phone's
                // search or lookup through the link otherwise got "refused: not here".
                if msg.call.phone_may()
                    && matches!(
                        msg.call,
                        Call::Speak { .. }
                            | Call::Listen { .. }
                            | Call::WebSearch { .. }
                            | Call::Lookup { .. }
                    )
                {
                    self.call_later(client, msg);
                    return;
                }
                let msg = if msg.call.phone_may() {
                    match self.defer_for_model(client, msg) {
                        Some(m) => m,
                        None => return,
                    }
                } else {
                    msg
                };
                let (name, task, call_id) = (
                    crate::devlog::name_of(&msg.call),
                    call_task(&msg.call),
                    msg.call_id,
                );
                let t0 = Instant::now();
                let result = if msg.call.phone_may() {
                    let r = self.call(client, msg.call);
                    self.publish();
                    r
                } else {
                    Err("A phone can't do that. Use the Mímir window on your PC instead.".into())
                };
                self.log_call_result(client, call_id, &name, task, t0.elapsed(), &result);
                self.send_to(
                    client,
                    Event::Reply {
                        call_id: msg.call_id,
                        result,
                    },
                );
            }
            L::PersonGone { person } => self.forget_person(&person),
            L::Disconnected { conn } => {
                if let Some(client) = self.link_conns.remove(&conn) {
                    let phone = self
                        .clients
                        .get(&client)
                        .and_then(|c| c.phone.clone())
                        .map(|k| k.chars().take(8).collect::<String>())
                        .unwrap_or_default();
                    crate::devlog::log("link", "drop", vec![("phone", phone.into())]);
                    self.clients.remove(&client);
                }
            }
        }
    }

    fn refresh_git_of(&mut self, task: &str) {
        if let Some(p) = self.task(task).map(|l| l.task.project.clone()) {
            self.refresh_git(&p, true);
        }
    }

    /// Checks git in the background, at most every few seconds per project.
    fn refresh_git(&mut self, project: &str, force: bool) {
        let due = force
            || self
                .git_checked
                .get(project)
                .is_none_or(|t| t.elapsed() > GIT_EVERY);
        if !due {
            return;
        }
        self.git_checked.insert(project.to_string(), Instant::now());
        let tx = self.git_tx.clone();
        let wake = self.wake.clone();
        let project = project.to_string();
        std::thread::spawn(move || {
            let p = Path::new(&project);
            let info = GitInfo {
                branch: crate::git::branch(p),
                changes: crate::git::changes(p),
            };
            let _ = tx.send((project, info));
            wake();
        });
    }

    /// Sessions a project's Remote Control started (from the Claude app on
    /// the phone, or claude.ai) become tasks under that project within a
    /// second or so, and their conversation is followed as it grows.
    /// Every task's conversation, wherever it goes on: when it was last
    /// talked in (or a Claude process for it runs) is its activity time, and
    /// writes from outside this service (the phone, claude.ai, the Claude
    /// app, a terminal) are followed live, as for the keepers' sessions.
    fn follow_outside(&mut self) {
        let now = Instant::now();
        if self
            .outside_scan
            .is_some_and(|t| now.duration_since(t) < OUTSIDE_SCAN)
        {
            return;
        }
        self.outside_scan = Some(now);
        if self
            .procs
            .1
            .is_none_or(|t| now.duration_since(t) >= PROCESS_SCAN)
        {
            self.procs = (crate::sessions::running_sessions(), Some(now));
        }
        let unix = crate::needs::now();
        let ids: Vec<(String, bool, bool, bool, Option<String>)> = self
            .runner
            .tasks
            .iter()
            .filter(|l| !l.task.archived)
            .map(|l| {
                (
                    l.task.id.clone(),
                    l.running(),
                    l.busy(),
                    // Followed elsewhere already (keepers).
                    l.task.from_phone.is_some(),
                    l.task.session_id.clone(),
                )
            })
            .collect();
        for (id, running, busy, followed, sid) in ids {
            if self.runner.fix_title(&id) {
                self.touched.insert(id.clone());
            }
            let path = sid.as_ref().and_then(|_| self.runner.transcript(&id));
            let meta = path.as_ref().and_then(|p| std::fs::metadata(p).ok());
            // When the conversation itself last moved (not bookkeeping).
            let talk = path.as_deref().and_then(crate::sessions::last_talk);
            let size = meta.as_ref().map(|m| m.len()).unwrap_or(0);
            let written = meta
                .as_ref()
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs() as i64);
            let in_proc = sid.as_ref().is_some_and(|s| self.procs.0.contains(s));
            let active = if busy || in_proc { Some(unix) } else { talk };
            if self.runner.set_active(&id, active) {
                self.touched.insert(id.clone());
            }
            // Going on outside Mímir right now: replies go there.
            // (Only what was followed from outside: never this service's own
            // Claude, just finished.)
            let elsewhere = !running
                && !followed
                && (in_proc
                    || (self.outside_followed.contains(&id)
                        && talk.is_some_and(|t| unix - t < crate::task::LIVE_SECS)));
            if self.runner.set_elsewhere(&id, elsewhere) {
                self.touched.insert(id.clone());
            }
            if sid.is_none() || meta.is_none() {
                continue;
            }
            // What this service's own Claude writes is already shown.
            if running {
                self.own_running.insert(id.clone());
                self.outside_seen.insert(id.clone(), size);
                continue;
            }
            if self.own_running.remove(&id) || followed {
                self.outside_seen.insert(id.clone(), size);
                self.outside_followed.remove(&id);
                continue;
            }
            // Quiet a while: the turn there has finished.
            if self.outside_followed.contains(&id)
                && written.is_some_and(|w| unix - w >= KEEPER_QUIET.as_secs() as i64)
                && self.runner.settle_followed(&id)
            {
                self.touched.insert(id.clone());
            }
            let from = match self.outside_seen.get(&id) {
                Some(&seen) => seen,
                // First look: a conversation going on right now is read
                // afresh, so the window shows all of it, not an old copy.
                None if talk.is_some_and(|w| unix - w < crate::task::LIVE_SECS) => 0,
                None => {
                    self.outside_seen.insert(id.clone(), size);
                    continue;
                }
            };
            if from == size && from != 0 {
                continue;
            }
            let from = if size < from { 0 } else { from };
            if let Ok(at) = self.runner.follow_session(&id, from) {
                self.outside_seen.insert(id.clone(), at);
                self.outside_followed.insert(id.clone());
                self.runner.fix_title(&id);
                self.touched.insert(id);
            }
        }
    }

    fn focused(&self) -> bool {
        self.clients.values().any(|c| c.ready && c.focused)
    }

    /// The duties that go on with no window: Phone too, keepers, the tasks
    /// themselves, notifications, summaries and waiting messages.
    /// Keeps the chat model's pick current (engine.rs): a first look a
    /// minute after start, then every hour, off the service's thread.
    fn engine_tick(&mut self) {
        use std::sync::atomic::Ordering;
        let due = match self.engine_looked {
            None => self.started.elapsed() >= Duration::from_secs(60),
            // With no result yet (a busy PC), look again every minute.
            Some(t) => {
                t.elapsed() >= Duration::from_secs(60)
                    && (t.elapsed() >= Duration::from_secs(3600) || crate::engine::load().is_none())
            }
        };
        if !due || self.engine_busy.swap(true, Ordering::SeqCst) {
            return;
        }
        self.engine_looked = Some(Instant::now());
        let keep: Vec<String> = self
            .settings
            .free_model
            .iter()
            .filter_map(|m| crate::models::ollama_name(m).map(str::to_string))
            .collect();
        let busy = self.engine_busy.clone();
        let talk = self.talk.clone();
        let spawned = std::thread::Builder::new()
            .name("engine".into())
            .spawn(move || {
                crate::engine::keep_fresh(keep, &|| talk.answering());
                busy.store(false, Ordering::SeqCst);
            });
        if spawned.is_err() {
            self.engine_busy.store(false, Ordering::SeqCst);
        }
    }

    /// A general chat message: a project task when it names a project on
    /// purpose (or follows up the task the chat is in), a status answer, an
    /// idea, or else a chat answered on the PC (its free model, or Claude
    /// when chosen), or left to the phone when the PC can't.
    fn talk_call(&mut self, a: TalkArgs) -> Filed {
        use crate::route::{How, Kind};
        // A message with files is always answered as a chat: the files are
        // read for the answer, never turned into a project's task.
        let has_files = a
            .turns
            .iter()
            .any(|t| t.id == a.turn_id && !t.files.is_empty());
        if a.chat_only || has_files {
            return self.talk_answer(a);
        }
        let text = a
            .turns
            .iter()
            .find(|t| t.id == a.turn_id)
            .map(|t| t.text.clone())
            .unwrap_or_default();
        let r = crate::route::strict(&text, &self.project_words());
        // The task this chat is in, while it's still going.
        let open = a
            .task
            .clone()
            .filter(|t| self.still_open(t) && self.task(t).is_some_and(|l| !l.task.archived));
        let open_project = open
            .as_ref()
            .and_then(|t| self.task(t))
            .map(|l| l.task.project.clone());
        let rules = a.ground_rules.clone().filter(|r| !r.trim().is_empty());
        match (&r.kind, &r.project) {
            (Kind::Status, project) => {
                let page = self.status_page(false);
                let reply = crate::facts::reply(&page.projects, project.as_deref());
                self.keep_exchange(&a, &reply.join(" "));
                return Filed::Status { reply };
            }
            (Kind::Idea, Some(p)) => {
                let i = crate::chat::add_idea(p, &r.text, &a.turn_id);
                return Filed::Idea {
                    project: p.clone(),
                    idea: i.id,
                };
            }
            (_, Some(p)) => {
                if let (Some(t), Some(q)) = (&open, &open_project)
                    && crate::paths::same(p, q)
                {
                    self.runner.send(t, &r.text);
                    self.touched.insert(t.clone());
                    return Filed::SameTask {
                        project: p.clone(),
                        task: t.clone(),
                    };
                }
                self.runner.next_rules = rules;
                let t = self.new_task_ask(p, &r.text);
                self.runner.next_rules = None;
                if r.how == How::Keywords {
                    self.file_placed(&t, p, Placed::Sure, None);
                }
                self.touched.insert(t.clone());
                return Filed::NewTask {
                    project: p.clone(),
                    task: t,
                };
            }
            (_, None) => {
                if let (Some(t), Some(q)) = (&open, &open_project) {
                    self.runner.send(t, &text);
                    self.touched.insert(t.clone());
                    return Filed::SameTask {
                        project: q.clone(),
                        task: t.clone(),
                    };
                }
            }
        }
        self.talk_answer(a)
    }

    /// Keeps a message and an answer given without the model (a status
    /// reply) in the chat's history.
    fn keep_exchange(&mut self, a: &TalkArgs, answer: &str) {
        let Some(user) = a.turns.iter().find(|t| t.id == a.turn_id).cloned() else {
            return;
        };
        let reply = mimir_proto::ChatTurn {
            id: crate::talk::answer_id(&a.turn_id),
            user: false,
            text: answer.to_string(),
            sources: vec![],
            ran: Some(mimir_proto::Ran::Pc),
            model_words: Some(shown_pc_name(&self.settings)),
            at: crate::talk::now_ms(),
            version: 1,
            seq: 0,
            gone: false,
            switch_note: None,
            pictures: vec![],
            files: vec![],
        };
        let _ = self.chat_put(
            &a.person.clone(),
            &a.chat_id,
            None,
            vec![user, reply],
            false,
        );
    }

    /// The chat's turns for the model with the words read from files put
    /// in: the new message's files are read now (and what was read is kept
    /// with the chat), earlier messages' files are added from what was kept
    /// (a short part, so a follow-up question still has them). Also what the
    /// phone is told was read of each file, and pictures of a scan.
    fn file_words(
        &mut self,
        a: &TalkArgs,
        paid: bool,
        sees: bool,
    ) -> (
        Vec<mimir_proto::ChatTurn>,
        Vec<mimir_proto::FileNote>,
        Vec<mimir_proto::Picture>,
    ) {
        use crate::readfile as rf;
        let mut turns = a.turns.clone();
        let (mut notes, mut pics) = (vec![], vec![]);
        // Earlier messages: a share of what was read, newest first.
        let mut left = 12_000usize;
        let earlier: Vec<usize> = turns
            .iter()
            .enumerate()
            .filter(|(_, t)| t.user && !t.gone && t.id != a.turn_id)
            .map(|(i, _)| i)
            .rev()
            .take(8)
            .collect();
        for i in earlier {
            if left == 0 {
                break;
            }
            for k in self
                .chats()
                .files_for(&a.person, &a.chat_id, &turns[i].id.clone())
            {
                if k.text.trim().is_empty() || left == 0 {
                    continue;
                }
                let part: String = k.text.chars().take(left.min(6_000)).collect();
                left -= part.chars().count();
                let cut = part.chars().count() < k.text.chars().count();
                turns[i].text.push_str(&format!(
                    "\n\nFile \"{}\" ({}{}):\n{}",
                    k.name,
                    k.note.trim_end_matches('.').to_lowercase(),
                    if cut {
                        "; only its start is shown here"
                    } else {
                        ""
                    },
                    part
                ));
            }
        }
        if let Some(t) = turns.iter_mut().find(|t| t.id == a.turn_id) {
            let sent: Vec<mimir_proto::SentFile> =
                t.files.iter().take(rf::MAX_FILES).cloned().collect();
            if !sent.is_empty() {
                let read: Vec<rf::Extracted> = sent.iter().map(|f| rf::extract(f, sees)).collect();
                let fitted = rf::fit(&read, if paid { rf::PAID_CHARS } else { rf::FREE_CHARS });
                for e in &read {
                    pics.extend(e.pictures.iter().cloned());
                }
                let kept: Vec<mimir_store::talk::KeptFile> = fitted
                    .iter()
                    .map(|f| mimir_store::talk::KeptFile {
                        name: f.name.clone(),
                        text: f.text.clone(),
                        note: f.note.clone(),
                    })
                    .collect();
                let _ = self
                    .chats()
                    .put_files(&a.person, &a.chat_id, &a.turn_id, &kept);
                for f in &fitted {
                    if !t.text.trim().is_empty() {
                        t.text.push_str("\n\n");
                    }
                    t.text.push_str(&f.for_model());
                    notes.push(f.file_note());
                }
            }
            for f in t.files.iter().skip(rf::MAX_FILES) {
                notes.push(mimir_proto::FileNote {
                    name: f.name.clone(),
                    note: "Only 3 files go with one message, so this one wasn't read.".into(),
                });
            }
            t.files.clear();
        }
        (turns, notes, pics)
    }

    /// Which PC model looks at a picture (the usual one when it can), and
    /// whether Online may take it when none can.
    fn seeing(&self, usual: Option<&str>, online: bool) -> (crate::sight::Choice, bool) {
        let base = crate::models::ollama_url();
        let installed = crate::models::installed().unwrap_or_default();
        (crate::sight::choose(&base, usual, &installed), online)
    }

    /// Queues a chat answer on the chat engine.
    fn talk_answer(&mut self, a: TalkArgs) -> Filed {
        let mut pick = crate::engine::chat_model();
        let usual = pick.clone();
        let owner = a.person == crate::people::OWNER;
        // Online answers (the owner's plan): the owner's choice for the
        // owner; for anyone else their own choice, and only while the
        // owner allows it for them.
        let prefs = self.people_book.get(&a.person);
        let mut paid = if owner {
            self.settings.chats_paid()
        } else {
            prefs.chats_paid() && self.may_go_online(&a.person)
        };
        let claude_model = if owner {
            self.settings.chats_model_alias().map(str::to_string)
        } else {
            prefs.chats_model_alias().map(str::to_string)
        };
        if let Some((who, _)) = self.answer_person.get(&crate::talk::answer_id(&a.turn_id))
            && *who != a.person
        {
            return Filed::OnPhone {
                why: "That didn't work. Try again.".into(),
            };
        }
        if pick.is_none() && !paid {
            return Filed::OnPhone {
                why: no_model_words(owner, crate::engine::load().as_ref()),
            };
        }
        if let Err(e) = crate::ollama::address()
            && !paid
        {
            return Filed::OnPhone { why: e };
        }
        let rules = a
            .ground_rules
            .clone()
            .filter(|r| !r.trim().is_empty())
            .or_else(|| {
                if owner {
                    self.settings.ground_rules()
                } else {
                    prefs.rules()
                }
            });
        let system = crate::talk::system(rules.as_deref(), a.lookup.as_deref());
        let (think, max_tokens) = crate::talk::effort(a.effort.as_deref());
        let answer = crate::talk::answer_id(&a.turn_id);
        let (mut ran, mut model_words) = if paid {
            (mimir_proto::Ran::Paid, "Online · paid".to_string())
        } else {
            let title = pick.as_ref().map(|p| p.title.as_str()).unwrap_or("");
            (
                mimir_proto::Ran::Pc,
                format!(
                    "{} · {}",
                    crate::talk::short_title(title),
                    shown_pc_name(&self.settings)
                ),
            )
        };
        // Pictures: the new message's (kept with the chat), and those of
        // the user message before it, read back from the chat's own place.
        let user_ids: Vec<String> = a
            .turns
            .iter()
            .filter(|t| t.user && !t.gone)
            .map(|t| t.id.clone())
            .collect();
        let mut pictures: Vec<Vec<mimir_proto::Picture>> = vec![];
        let new_pics: Vec<mimir_proto::Picture> = a
            .turns
            .iter()
            .find(|t| t.id == a.turn_id)
            .map(|t| t.pictures.iter().take(4).cloned().collect())
            .unwrap_or_default();
        if !new_pics.is_empty() {
            let _ = self
                .chats()
                .put_pictures(&a.person, &a.chat_id, &a.turn_id, &new_pics);
        }
        // Files sent with the message ("chat_files"): read here, only the
        // words kept, and the model told how much was read.
        // (Only asked of the model when a file came: nothing else needs it here.)
        let has_files = a
            .turns
            .iter()
            .any(|t| t.id == a.turn_id && !t.files.is_empty());
        let mut sight: Option<(crate::sight::Choice, bool)> = None;
        let sees = has_files
            && (paid || {
                let online = self.may_go_online(&a.person);
                let c = self.seeing(usual.as_ref().map(|p| p.model.as_str()), online);
                let any = c.0.model.is_some() || c.1;
                sight = Some(c);
                any
            });
        let (turns, files_read, scan_pics) = self.file_words(&a, paid, sees);
        let mut new_pics = new_pics;
        for p in scan_pics {
            if new_pics.len() < mimir_store::talk::PICTURES_PER_TURN {
                new_pics.push(p);
            }
        }
        pictures.push(new_pics);
        if let Some(prev) = user_ids.iter().rev().find(|id| **id != a.turn_id).cloned() {
            let (person, chat) = (a.person.clone(), a.chat_id.clone());
            let before = self.chats().pictures_for(&person, &chat, &prev);
            if !before.is_empty() {
                pictures.push(before);
            }
        }
        if pictures.iter().all(|p| p.is_empty()) {
            pictures.clear();
        }
        // Pictures: a PC model that sees (the usual one when it does), else
        // Online where this person may, else plain words. Nothing here waits
        // on a model: a model that fails on a real picture is handled in the
        // chat worker.
        let mut switch_note = None;
        let mut backups: Vec<crate::talk::Backup> = vec![];
        let online_ok = self.may_go_online(&a.person);
        let paid_job = |model: Option<String>, this: &Self| crate::talk::Paid {
            model,
            program: this.claude.clone(),
            dir: crate::paths::data_dir().join("talk-paid"),
        };
        if !pictures.is_empty() {
            let (c, _) = sight.take().unwrap_or_else(|| {
                self.seeing(usual.as_ref().map(|p| p.model.as_str()), online_ok)
            });
            let plan = crate::sight::plan(&c, online_ok, paid);
            let usual_name = usual.as_ref().map(|p| p.model.as_str());
            let reason = "your usual model can't look at pictures";
            let pc = shown_pc_name(&self.settings);
            let pc_words = |m: &str| format!("{} · {pc}", crate::models::plain_name(m));
            let note_for = |m: &str| switch_words(usual_name, m, reason);
            let pick_for = |m: &str, base: &Option<crate::engine::Pick>| crate::engine::Pick {
                model: m.to_string(),
                title: crate::models::plain_name(m),
                ..base.clone().unwrap_or_else(|| crate::engine::Pick {
                    model: m.to_string(),
                    title: String::new(),
                    fit: crate::engine::Fit::Card,
                    tokens_per_s: 0.0,
                    first_token_ms: 0,
                    cold_ms: 0,
                    backend: String::new(),
                })
            };
            if plan.nobody {
                return Filed::OnPhone {
                    why: NO_SIGHT_PC.into(),
                };
            }
            if plan.online_first {
                if !paid {
                    switch_note = Some(format!("Answered online because {reason}."));
                }
                paid = true;
                ran = mimir_proto::Ran::Paid;
                model_words = "Online · paid".into();
                // If Online fails, the free backup is a PC model that sees,
                // never the usual one that can't; none: plain words.
                pick = plan.free_backup.as_deref().map(|m| pick_for(m, &usual));
            } else if let Some(m) = &plan.pc {
                if Some(m.as_str()) != usual_name {
                    switch_note = Some(note_for(m));
                    model_words = pc_words(m);
                    pick = Some(pick_for(m, &usual));
                }
                for n in &plan.next {
                    backups.push(crate::talk::Backup {
                        model: n.clone(),
                        model_words: pc_words(n),
                        note: note_for(n),
                        paid: None,
                    });
                }
                if plan.online_last {
                    backups.push(crate::talk::Backup {
                        model: String::new(),
                        model_words: "Online · paid".into(),
                        note: format!("Answered online because {reason}."),
                        paid: Some(paid_job(claude_model.clone(), self)),
                    });
                }
            }
        }
        // A model that isn't loaded yet takes a few seconds to wake.
        let cold = !paid
            && pick.as_ref().is_some_and(|p| {
                !crate::ollama::loaded(&crate::models::ollama_url())
                    .iter()
                    .any(|(n, _, _)| *n == p.model)
            });
        let job = crate::talk::Job {
            person: a.person.clone(),
            chat_id: a.chat_id.clone(),
            answer_id: answer.clone(),
            model: pick.as_ref().map(|p| p.model.clone()).unwrap_or_default(),
            model_words: model_words.clone(),
            messages: crate::talk::messages(system, &turns),
            think,
            max_tokens,
            context: crate::engine::CHAT_CONTEXT,
            sources: a.sources.clone(),
            paid: paid.then(|| paid_job(claude_model.clone(), self)),
            pictures,
            switch_note,
            backups,
        };
        // A chat counts as using the chat model, so nothing treats it as
        // unused.
        if !job.model.is_empty() {
            crate::keep_current::record_free_use(&job.model);
        }
        // The message is kept on the PC (the phone sends it too; it's
        // the same turn by its id).
        if let Some(t) = a.turns.iter().find(|t| t.id == a.turn_id && t.user) {
            let mut t = t.clone();
            t.pictures.clear();
            t.files.clear();
            let _ = self.chat_put(&a.person.clone(), &a.chat_id, None, vec![t], false);
        }
        self.answer_person
            .insert(answer.clone(), (a.person.clone(), None));
        match self.talk.submit(job) {
            Ok(waiting) => Filed::Talk {
                answer,
                ran,
                model_words,
                waiting: waiting.or_else(|| cold.then(|| crate::talk::WAKING.to_string())),
                files_read,
            },
            Err(why) => Filed::OnPhone { why },
        }
    }

    /// The chat engine's output, to every client that talks.
    fn talk_out(&mut self, o: crate::talk::Out) {
        match o {
            crate::talk::Out::Piece {
                chat_id,
                answer_id,
                n,
                text,
                thinking,
            } => {
                // Only to the devices of the person whose answer it is.
                if let Some(person) = self.answer_person.get(&answer_id).map(|a| a.0.clone()) {
                    self.broadcast_chat(
                        &person,
                        Event::TalkPiece {
                            chat_id,
                            turn_id: answer_id,
                            n,
                            text,
                            thinking,
                        },
                    );
                }
            }
            crate::talk::Out::Done {
                chat_id,
                answer_id,
                result,
            } => {
                let Some(person) = self.answer_person.get(&answer_id).map(|a| a.0.clone()) else {
                    return;
                };
                // Kept for a while after it ends, never while it still runs.
                if let Some(e) = self.answer_person.get_mut(&answer_id) {
                    e.1 = Some(Instant::now());
                }
                // Saved first, so the answer carries its change number.
                let result = match result {
                    Ok(mut turn) => {
                        // Asked again (Try again): the new answer takes the
                        // old one's place, here and on the phones.
                        if let Some(old) = self
                            .chats()
                            .get_for(&person, &chat_id)
                            .and_then(|c| c.turns.iter().find(|t| t.id == turn.id))
                        {
                            turn.version = turn.version.max(old.version + 1);
                        }
                        if self
                            .chat_put(&person, &chat_id, None, vec![turn.clone()], false)
                            .is_ok()
                            && let Some(saved) = self
                                .chats()
                                .get_for(&person, &chat_id)
                                .and_then(|c| c.turns.iter().find(|t| t.id == turn.id))
                        {
                            turn = saved.clone();
                        }
                        Ok(turn)
                    }
                    e => e,
                };
                self.broadcast_chat(
                    &person,
                    Event::TalkDone {
                        chat_id,
                        turn_id: answer_id,
                        result,
                    },
                )
            }
        }
    }

    /// Whether a person may use the owner's online plan for their chats:
    /// the owner always, anyone else only while the owner has allowed it.
    fn may_go_online(&self, person: &str) -> bool {
        person == crate::people::OWNER
            || self.link.as_ref().is_some_and(|l| l.may_go_online(person))
    }

    /// Whether a recent chat answer is this person's.
    fn answer_is_of(&self, answer: &str, person: &str) -> bool {
        self.answer_person
            .get(answer)
            .is_some_and(|(who, _)| who == person)
    }

    /// A person is gone: their chats, their settings, anything running for
    /// them. Nothing of them is kept.
    fn forget_person(&mut self, person: &str) {
        if person == crate::people::OWNER {
            return;
        }
        let mine: Vec<String> = self
            .answer_person
            .iter()
            .filter(|(_, (who, _))| who == person)
            .map(|(a, _)| a.clone())
            .collect();
        for a in mine {
            self.talk.stop(&a);
            self.answer_person.remove(&a);
        }
        self.chats().forget_person(person);
        self.people_book.forget(person);
        crate::talk::forget_sessions(&crate::paths::data_dir().join("talk-paid"), person);
        let gone: Vec<u64> = self
            .clients
            .iter()
            .filter(|(_, c)| c.person == person)
            .map(|(id, _)| *id)
            .collect();
        for id in gone {
            self.drop_client(id);
        }
    }

    /// The chat store, opened the first time it's needed (the keyring is
    /// asked then).
    fn chats(&mut self) -> &mut crate::talk_store::TalkStore {
        let s = self.chats.get_or_insert_with(|| {
            crate::talk_store::log_to_devlog();
            let (dir, ring) = (
                crate::talk_store::dir(),
                crate::talk_store::Keyring::from_env(),
            );
            if matches!(ring, crate::talk_store::Keyring::SecretService) {
                // The desktop's keyring is asked on the unlock thread, never
                // here: the store starts closed and is opened there.
                crate::talk_store::TalkStore::pending(&dir, &ring)
            } else {
                crate::talk_store::TalkStore::open(&dir, &ring)
            }
        });
        // A locked store asks the keyring again, off this thread.
        self.chats_unlock.step(s);
        s
    }

    /// Takes a chat change from a phone or the PC itself, and tells every
    /// other device what changed.
    fn chat_put(
        &mut self,
        person: &str,
        chat_id: &str,
        name: Option<String>,
        turns: Vec<mimir_proto::ChatTurn>,
        deleted: bool,
    ) -> Result<mimir_proto::ChatPutDone, String> {
        let now = crate::talk::now_ms();
        let said: Vec<(String, String)> = turns
            .iter()
            .filter(|t| t.user && crate::devlog::enabled())
            .map(|t| (t.id.clone(), t.text.clone()))
            .collect();
        // The app's own check ("Run checks" in Labs) leaves no chat behind,
        // for anyone.
        if crate::people::is_check_chat(chat_id) {
            return Ok(mimir_proto::ChatPutDone {
                seq: 0,
                deleted: false,
            });
        }
        let (done, changed) = self
            .chats()
            .put_for(person, chat_id, name, turns, deleted, now)?;
        for (id, text) in said.iter().filter(|_| changed.is_some() && !deleted) {
            crate::devlog::log_text(
                "talk",
                "turn",
                vec![
                    ("chat", chat_id.to_string().into()),
                    ("turn", id.clone().into()),
                ],
                text,
            );
        }
        if let Some(chat) = changed {
            self.broadcast_chat(person, Event::ChatChanged { chat });
        }
        Ok(done)
    }

    fn tick(&mut self) {
        self.yolo_tick();
        self.talk.expire();
        self.answer_person
            .retain(|_, (_, done)| done.is_none_or(|at| at.elapsed() < crate::talk::kept()));
        self.engine_tick();
        // A session's request goes once its hook stopped waiting (answered
        // on the PC, the session ended, or ten minutes passed).
        let before = self.outside.len();
        let late = crate::needs::now() - crate::approve::WAIT.as_secs() as i64 - 60;
        self.outside
            .retain(|o| o.created > late && Path::new(&format!("/proc/{}", o.ask.pid)).exists());
        if self.outside.len() != before {
            self.publish();
        }
        self.follow_outside();
        // The plan's usage is looked up only when Claude is in use.
        let wanted = self.settings.chats_paid() || !self.settings.project_free();
        if let Some(p) = self.runner.usage_poller.as_mut() {
            p.wanted = wanted;
        }
        self.runner.tick();
        self.claude_ran_out();
        self.allow_by_rules();
        self.watch_checks();
        self.tidy_when_stopped();
        let mut summaries = vec![];
        for n in std::mem::take(&mut self.runner.notices) {
            match n {
                Notice::NeedsYou { task, title, what } => {
                    // The desktop notification comes from the truth's change
                    // of state (publish_truth).
                    let _ = (&task, &title, &what);
                }
                Notice::Finished { task, title, ok } => {
                    // Incognito: no updates, summaries or notifications.
                    if self.task(&task).is_some_and(|l| l.task.incognito) {
                        continue;
                    }
                    self.finished_at.insert(task.clone(), crate::needs::now());
                    self.confirm_by_work();
                    self.refresh_git_of(&task);
                    let Some(l) = self.task(&task) else { continue };
                    let asks = l.task.question_waiting();
                    // A short summary of the work, when the owner wants one.
                    if ok
                        && !asks
                        && self.settings.finish_summary
                        && !self.queued.contains_key(&task)
                        && l.task.status == Status::Done
                        && !l.task.plan_waiting()
                        && wants_summary(&l.task.entries)
                    {
                        summaries.push(task.clone());
                    }
                    let _ = (&title, ok);
                    self.summary_pending.remove(&task);
                }
            }
        }
        for id in summaries {
            self.runner.send(&id, SUMMARY_PROMPT);
            self.summary_pending.insert(id.clone());
            self.touched.insert(id);
        }
        self.send_waiting();
        // Slash commands and connectors are kept in the settings.
        let r = &self.runner;
        if (!r.slash_commands.is_empty() && r.slash_commands != self.settings.slash_commands)
            || (!r.mcp_servers.is_empty() && r.mcp_servers != self.settings.connectors)
        {
            if !r.slash_commands.is_empty() {
                self.settings.slash_commands = r.slash_commands.clone();
            }
            if !r.mcp_servers.is_empty() {
                self.settings.connectors = r.mcp_servers.clone();
            }
            self.settings.save();
            self.broadcast(Event::Settings {
                settings: settings::service_part(&self.settings),
            });
        }
        if let Some(p) = self.git_watch.clone() {
            self.refresh_git(&p, false);
        }
    }

    /// Sends messages that waited for Claude to be done.
    fn send_waiting(&mut self) {
        let ids: Vec<String> = self.queued.keys().cloned().collect();
        for id in ids {
            let Some(l) = self.runner.tasks.iter().find(|l| l.task.id == id) else {
                self.queued.remove(&id);
                continue;
            };
            let now = waiting_now(
                l.task.status,
                !l.asks.is_empty(),
                l.task.question_waiting(),
                l.task.plan_waiting(),
            );
            if now == Waiting::Send
                && let Some(q) = self.queued.remove(&id)
            {
                let (text, pictures) = with_files(&q.text, &q.files);
                self.runner.send_with(&id, &text, &pictures);
                self.touched.insert(id);
            }
        }
    }

    /// Tells clients what changed since they were last told.
    /// The free model for near-limit tasks: the owner's pick if installed,
    /// else the best tested one.
    fn free_choice(&self) -> Option<crate::models::FreeModel> {
        let list = crate::models::installed()
            .map(|n| crate::models::catalogue(&n))
            .unwrap_or_default();
        if let Some(m) = &self.settings.free_model
            && let Some(f) = list.iter().find(|f| &f.id == m)
        {
            return Some(f.clone());
        }
        crate::models::default_free(&list).cloned()
    }

    /// When Claude's limit is likely back: the window that ran out (five-hour
    /// or weekly, whichever is used up), else in five hours.
    fn limit_reset(&self, now: i64) -> i64 {
        let full = |w: &Option<mimir_proto::WeeklyUse>| {
            w.as_ref()
                .filter(|w| w.used >= 0.99 && w.resets_at > now)
                .map(|w| w.resets_at)
        };
        full(&self.runner.five_hour)
            .or(full(&self.runner.weekly))
            .unwrap_or(now + 5 * 3600)
    }

    /// Claude Code reported that the plan's usage limit ran out: the task
    /// carries on on the free model (or asks first, or waits), as Settings ›
    /// Who answers says.
    fn claude_ran_out(&mut self) {
        if self.runner.tasks.iter().any(|l| l.task.limit_hit.is_some())
            && !self.model_ready_for_tick()
        {
            return;
        }
        let now = crate::needs::now();
        if self.claude_out_until.is_some_and(|t| t <= now) {
            self.claude_out_until = None;
        }
        for (id, resets) in self.runner.take_limit_hits() {
            let back_at = if resets > now {
                resets
            } else {
                self.limit_reset(now)
            };
            self.claude_out_until = Some(back_at);
            let Some(l) = self.task(&id) else { continue };
            if l.task.incognito || crate::models::is_free(l.task.model.as_deref()) {
                continue;
            }
            let claude_model = l.task.model.clone();
            let project = l.task.home_project().to_string();
            let title = l.task.title.clone();
            let when = crate::models::clock(back_at);
            let free = self.free_choice();
            let said = match (self.settings.power(), free) {
                // Asking first: the model isn't woken for the question, only
                // when the owner says yes.
                (settings::Power::Backup, f) if self.settings.ask_before_free => {
                    self.runner.set_ran_out(
                        &id,
                        Some(mimir_proto::RanOut {
                            back_at,
                            claude_model,
                            on_free: false,
                        }),
                        true,
                    );
                    let t = format!(
                        "The online limit ran out in \"{title}\". Online is back at {when}; it can \
                         carry on now with {}, a free model on your PC.",
                        f.map_or("a free model".to_string(), |f| crate::models::plain_name(
                            &f.name
                        ))
                    );
                    self.runner.note(&id, &t);
                    t
                }
                (settings::Power::Backup, Some(_)) => {
                    match self.continue_on_free(&id, back_at, claude_model) {
                        Ok(name) => format!(
                            "The online limit ran out in \"{title}\", so it carries on on {name} \
                             until online is back at {when}."
                        ),
                        Err(e) => e,
                    }
                }
                (settings::Power::Backup, None) => {
                    let t = format!(
                        "The online limit ran out in \"{title}\". Online is back at {when}. No \
                         free model is on this PC to carry on meanwhile: add one in Settings › \
                         Models."
                    );
                    self.runner.note(&id, &t);
                    t
                }
                _ => {
                    let t = format!(
                        "The online limit ran out in \"{title}\". Online is back at {when}; carry on \
                         then."
                    );
                    self.runner.note(&id, &t);
                    t
                }
            };
            // Said in the task; Updates hold only spoken summaries.
            let _ = (&project, &said);
            self.touched.insert(id);
        }
    }

    /// Whether a call needs the PC's model awake: free-model work.
    fn call_needs_model(&self, call: &Call) -> bool {
        if !crate::models::unit_path().is_file() {
            return false;
        }
        let free_task = |t: &str| {
            self.task(t)
                .is_some_and(|l| crate::models::is_free(l.task.model.as_deref()))
        };
        match call {
            Call::NewIncognito { .. } => true,
            Call::NewTask { model, .. } => {
                let power = self.settings.power();
                let out = self.claude_out_until.filter(|t| *t > crate::needs::now());
                crate::models::is_free(model.as_deref())
                    || power == settings::Power::FreeOnly
                    || (power == settings::Power::Backup
                        && out.is_some()
                        && !self.settings.ask_before_free)
            }
            Call::ContinueOnFree { .. } => true,
            Call::Send { task, .. } | Call::Continue { task } | Call::SendQueued { task } => {
                free_task(task)
            }
            _ => false,
        }
    }

    /// A call that needs the PC's model while it sleeps waits on a thread of
    /// its own (the service goes on answering) and comes back as `Woken`.
    /// None when it was taken; the call back when it can go ahead now.
    fn defer_for_model(&mut self, client: u64, msg: CallMsg) -> Option<CallMsg> {
        // Stopping or pausing a task cancels what waits for the model for
        // it (a Send must not start a turn the owner just stopped).
        if let Call::Stop { task } | Call::Pause { task } | Call::EndIncognito { task } = &msg.call
        {
            for (c, id, t) in &self.wake_pending {
                if t.as_deref() == Some(task.as_str()) {
                    self.wake_cancelled.insert((*c, *id));
                }
            }
        }
        if self.replay_ok.remove(&(client, msg.call_id)) || !self.call_needs_model(&msg.call) {
            return Some(msg);
        }
        let task = match &msg.call {
            Call::Send { task, .. } | Call::Continue { task } | Call::SendQueued { task } => {
                Some(task.clone())
            }
            _ => None,
        };
        let call_id = msg.call_id;
        if self.wake_worker.is_none() {
            let tx = self.self_tx.clone();
            let wake = self.wake.clone();
            // One at a time, in the order they came.
            let gate = std::sync::Mutex::new(WakeGate::default());
            self.wake_worker = Some(mimir_hub::workers::Pool::spawn(
                "model-wake",
                mimir_hub::workers::Sizes::MODEL_MANAGER,
                self.self_tx.clone(),
                move |(client, msg, task): (u64, CallMsg, Option<String>)| {
                    let mut gate = gate.lock().unwrap_or_else(|e| e.into_inner());
                    // Answers at once when it is awake already, and when a
                    // wake just failed (no waiting again).
                    if gate.should_wait(Instant::now()) && crate::models::sleeping() {
                        if let Some(task) = task {
                            let _ = tx.send(Inbound::Waking { task });
                            wake();
                        }
                        if crate::models::ensure_running(Duration::from_secs(20)) {
                            gate.worked();
                        } else {
                            gate.failed(Instant::now());
                        }
                    }
                    wake();
                    Inbound::Woken { client, msg }
                },
            ));
        }
        let sent = self
            .wake_worker
            .as_ref()
            .is_some_and(|w| w.submit((client, msg, task.clone())).is_ok());
        if sent {
            self.wake_pending.push((client, call_id, task));
        } else {
            self.send_to(
                client,
                Event::Reply {
                    call_id,
                    result: Err("The PC is too busy to start that.".into()),
                },
            );
        }
        None
    }

    /// For work that came on its own and needs the PC's model: true when
    /// it can go on now. A sleeping model is woken on a thread of its own;
    /// when that gives up, the work goes on (and finds none).
    fn model_ready_for_tick(&mut self) -> bool {
        if !crate::models::unit_path().is_file() {
            return true;
        }
        // Only when the free model is wanted at all: Claude's limit ran out
        // for a Claude task and free models are the backup.
        let hit = self.runner.tasks.iter().any(|l| {
            l.task.limit_hit.is_some()
                && !l.task.incognito
                && !crate::models::is_free(l.task.model.as_deref())
        });
        let needed = wake_for_limit(self.settings.power(), self.settings.ask_before_free, hit);
        if !needed {
            return true;
        }
        // Claude's limit is known to be out from now on, whatever the wait.
        let now = crate::needs::now();
        let hits: Vec<i64> = self
            .runner
            .tasks
            .iter()
            .filter_map(|l| l.task.limit_hit)
            .collect();
        for r in hits {
            self.claude_out_until = Some(if r > now { r } else { self.limit_reset(now) });
        }
        if let Some((_, done)) = &self.model_waker {
            if done.load(std::sync::atomic::Ordering::SeqCst) {
                self.model_waker = None;
                return true;
            }
            return false;
        }
        let done = Arc::new(std::sync::atomic::AtomicBool::new(false));
        self.model_waker = Some((Instant::now(), done.clone()));
        let wake = self.wake.clone();
        let _ = std::thread::Builder::new()
            .name("model-wake".into())
            .spawn(move || {
                // Looks and wakes off the service's thread.
                if crate::models::sleeping() {
                    crate::models::ensure_running(Duration::from_secs(20));
                }
                done.store(true, std::sync::atomic::Ordering::SeqCst);
                wake();
            });
        false
    }

    /// Carries a task on on the free model, from a short brief. The free
    /// model's name.
    fn continue_on_free(
        &mut self,
        id: &str,
        back_at: i64,
        claude_model: Option<String>,
    ) -> Result<String, String> {
        let f = self
            .free_choice()
            .ok_or("No free model is on this PC: add one in Settings › Who answers.")?;
        self.runner.hand_to(id, Some(f.id.clone()));
        self.runner.set_ran_out(
            id,
            Some(mimir_proto::RanOut {
                back_at,
                claude_model,
                on_free: true,
            }),
            false,
        );
        self.runner.note(
            id,
            &format!(
                "The online limit ran out, so this carries on on {} until online is back at {}. \
                 It starts from a short brief of the work so far.",
                f.name,
                crate::models::clock(back_at)
            ),
        );
        self.runner.send(
            id,
            "Claude's usage limit ran out partway through this task. Carry on from where it \
             stopped.",
        );
        Ok(f.name)
    }

    /// Calls that take a while (reading an entry aloud, listening): done on
    /// another thread, answered with `Inbound::Later`.
    fn call_later(&mut self, client: u64, msg: CallMsg) {
        if let Some(phone) = self.clients.get(&client).and_then(|c| c.phone.clone())
            && !msg.call.phone_may()
        {
            let _ = phone;
            self.send_to(
                client,
                Event::Reply {
                    call_id: msg.call_id,
                    result: Err("refused: phones can't do that".into()),
                },
            );
            return;
        }
        // A Whisper's Read aloud is refused and never logged.
        let info = later_info(&msg.call).filter(|(_, _, task, _)| {
            !task
                .as_deref()
                .is_some_and(|t| self.task(t).is_some_and(|l| l.task.incognito))
        });
        let job: Box<dyn FnOnce() -> Result<serde_json::Value, String> + Send> = match msg.call {
            Call::Speak { task, .. } if self.task(&task).is_some_and(|l| l.task.incognito) => {
                Box::new(|| Err(WHISPER_NO_SPEAK.to_string()))
            }
            Call::Speak { task, entry } => {
                let text = self
                    .task(&task)
                    .and_then(|l| match l.task.entries.get(entry) {
                        Some(Entry::Claude(t)) | Some(Entry::You(t)) | Some(Entry::Note(t)) => {
                            Some(t.clone())
                        }
                        _ => None,
                    });
                match text {
                    None => Box::new(|| Err("Nothing to read there.".to_string())),
                    Some(text) => Box::new(move || {
                        let name = format!("speak-{}.ogg", crate::needs::short_hash(&text));
                        let dir = crate::audio::audio_dir();
                        if dir.join(&name).is_file() {
                            return Ok(serde_json::Value::String(name));
                        }
                        crate::audio::render(&text, &dir, &name).map(serde_json::Value::String)
                    }),
                }
            }
            Call::WebSearch { query, max } => {
                // Gently: one search at a time per phone, a few a minute.
                if let Err(e) = self.searches.allow(std::time::Instant::now()) {
                    Box::new(move || Err(e))
                } else {
                    Box::new(move || {
                        crate::searx::search(crate::searx::ADDRESS, &query, max)
                            .and_then(|r| serde_json::to_value(r).map_err(|e| e.to_string()))
                    })
                }
            }
            Call::Lookup { url } => {
                // Only the fixed sites, and a few a minute.
                match crate::lookup::allowed(&url)
                    .and_then(|()| self.lookups.allow(std::time::Instant::now()))
                {
                    Err(e) => Box::new(move || Err(e)),
                    Ok(()) => Box::new(move || {
                        crate::lookup::fetch(&url)
                            .and_then(|r| serde_json::to_value(r).map_err(|e| e.to_string()))
                    }),
                }
            }
            Call::Listen { wav_base64 } => Box::new(move || {
                use base64::Engine as _;
                let bytes = base64::engine::general_purpose::STANDARD
                    .decode(wav_base64.as_bytes())
                    .map_err(|_| "The recording didn't arrive whole.".to_string())?;
                let root = crate::audio::voice_root();
                if !crate::listen::ready(&root) {
                    return Err("Listening isn't downloaded on the PC".into());
                }
                // In the private runtime folder under a random name, gone
                // when this ends (whatever the result).
                let heard = crate::listen::Heard::keep(&crate::listen::heard_dir(), &bytes)
                    .map_err(|e| e.to_string())?;
                crate::listen::transcribe(&root, heard.path()).map(serde_json::Value::String)
            }),
            _ => return,
        };
        let tx = self.self_tx.clone();
        let wake = self.wake.clone();
        let call_id = msg.call_id;
        let _ = std::thread::Builder::new()
            .name("call-later".into())
            .spawn(move || {
                let t0 = Instant::now();
                let result = job();
                if let Some((cat, ev, task, detail)) = info {
                    let mut f: Vec<(&'static str, serde_json::Value)> = vec![
                        ("call_id", call_id.into()),
                        ("ms", (t0.elapsed().as_millis() as u64).into()),
                    ];
                    if let Some(t) = task {
                        f.push(("task", t.into()));
                    }
                    if let Some(d) = detail {
                        f.push(("what", d.into()));
                    }
                    match &result {
                        Ok(v) => {
                            f.push(("ok", true.into()));
                            f.push(("bytes", v.to_string().len().into()));
                        }
                        Err(e) => {
                            f.push(("err", crate::log::cut(e).into()));
                            f.push(("kind", ev.into()));
                        }
                    }
                    crate::devlog::log(cat, ev, f);
                }
                let _ = tx.send(Inbound::Later {
                    client,
                    call_id,
                    result,
                });
                wake();
            });
    }

    /// The truth as it stands, built afresh (with the last revision).
    fn truth_now(&mut self) -> Option<mimir_proto::Truth> {
        let rev = self.truth.as_ref().map_or(1, |t| t.rev);
        let mut t = self.build_truth();
        t.rev = rev;
        self.truth = Some(t.clone());
        self.truth_seen = crate::truth::seen(&t);
        Some(t)
    }

    /// Everything the owner sees about the PC's work, from the one rule.
    fn build_truth(&mut self) -> mimir_proto::Truth {
        let now = crate::needs::now();
        let needs: Vec<String> = self.needs_items().into_iter().map(|i| i.project).collect();
        let tasks: Vec<crate::truth::TaskIn> = self
            .runner
            .tasks
            .iter()
            .map(|l| {
                let t = &l.task;
                crate::truth::TaskIn {
                    id: t.id.clone(),
                    project: t.home_project().to_string(),
                    title: t.title.clone(),
                    working: l.working_now(),
                    needs_you: t.needs_owner(),
                    live_elsewhere: t.live_elsewhere,
                    stopped: matches!(t.status, Status::Stopped | Status::Paused),
                    archived: t.archived,
                    background: t.live_elsewhere && !l.busy(),
                    since: self
                        .finished_at
                        .get(&t.id)
                        .copied()
                        .or(t.active_unix)
                        .or(Some(t.created_unix)),
                    from_phone: t.from_phone.is_some() || t.started_on_phone,
                }
            })
            .collect();
        let chats = crate::paths::chats_dir().display().to_string();
        let list: Vec<String> = self
            .pickable_projects()
            .into_iter()
            .filter(|p| !crate::paths::same(p, &chats))
            .collect();
        let names = crate::paths::list_names(&self.settings, &list);
        let projects = list
            .into_iter()
            .zip(names)
            .map(|(p, n)| {
                let c = self.checks_cache.get(&p).cloned();
                (p, n, c)
            })
            .collect();
        let paid = self.settings.chats_paid();
        let (words, has_pick) = match &self.engine_words {
            Some((at, w, has)) if at.elapsed() < Duration::from_secs(10) => (w.clone(), *has),
            _ => {
                let e = crate::engine::load();
                let has = e.as_ref().is_some_and(|e| e.chat.is_some());
                let w = crate::engine::words(e.as_ref());
                self.engine_words = Some((Instant::now(), w.clone(), has));
                (w, has)
            }
        };
        let pc = shown_pc_name(&self.settings);
        let engine = mimir_proto::TruthEngine {
            chats_where: if paid || has_pick {
                pc.clone()
            } else {
                "your phone".into()
            },
            chats_model_words: if paid {
                match self.settings.chats_model_alias() {
                    Some(a) => format!(
                        "Chats run on {} (your plan)",
                        crate::talk::claude_choice_name(a)
                    ),
                    None => "Chats run online (your plan)".into(),
                }
            } else {
                words
            },
            answering: self.talk.answering(),
            paid_on: paid || !self.settings.project_free(),
        };
        crate::truth::build(crate::truth::Input {
            run_id: &self.run_id,
            now,
            pc_name: pc,
            activity: None,
            tasks,
            needs,
            projects,
            engine,
            usage: (
                self.runner.weekly.clone(),
                self.runner.five_hour.clone(),
                self.runner.usage_at,
            ),
        })
    }

    /// Builds the truth at most every 250 ms, and pushes it to every client
    /// that knows it when what a person sees changed.
    fn publish_truth(&mut self) {
        if self
            .truth_at
            .is_some_and(|t| t.elapsed() < Duration::from_millis(250))
        {
            return;
        }
        self.truth_at = Some(Instant::now());
        let mut t = self.build_truth();
        let seen = crate::truth::seen(&t);
        if self.truth.is_some() && seen == self.truth_seen {
            return;
        }
        t.rev = self.truth.as_ref().map_or(1, |o| o.rev + 1);
        self.truth_seen = seen;
        // Desktop notifications follow the truth's changes of state, unless
        // the window is the one being looked at.
        if let Some(old) = self.truth.as_ref()
            && !self.focused()
        {
            let whisper = |id: &str| self.task(id).is_some_and(|l| l.task.incognito);
            for (title, body) in crate::truth::notices(old, &t, &whisper) {
                crate::notify::notify(&title, &body);
            }
        }
        self.truth = Some(t.clone());
        self.broadcast(Event::Truth { truth: Box::new(t) });
    }

    fn publish(&mut self) {
        self.publish_truth();
        let full = self.last_full.elapsed() >= FULL_EVERY;
        if full {
            self.last_full = Instant::now();
        }
        let mut events = vec![];
        let ids: HashSet<String> = self
            .runner
            .tasks
            .iter()
            .map(|l| l.task.id.clone())
            .collect();
        let gone: Vec<String> = self
            .sent
            .keys()
            .filter(|id| !ids.contains(*id))
            .cloned()
            .collect();
        for id in gone {
            self.sent.remove(&id);
            events.push(Event::Removed { task: id });
        }
        let touched = std::mem::take(&mut self.touched);
        for l in &self.runner.tasks {
            let id = &l.task.id;
            let h = header(l, &self.queued);
            let sent = self.sent.entry(id.clone()).or_default();
            let look = full
                || sent.header.is_none()
                || l.running()
                || touched.contains(id)
                || sent.header.as_ref().is_some_and(|s| s.running);
            if !look {
                continue;
            }
            if sent.header.as_ref() != Some(&h) {
                sent.header = Some(h.clone());
                events.push(Event::Task {
                    header: Box::new(h),
                });
            }
            let now = &l.task.entries;
            let same = sent
                .entries
                .iter()
                .zip(now.iter())
                .position(|(a, b)| a != b)
                .unwrap_or(sent.entries.len().min(now.len()));
            if same < now.len() || sent.entries.len() != now.len() {
                events.push(Event::Entries {
                    task: id.clone(),
                    from: same,
                    entries: now[same..].to_vec(),
                });
                sent.entries.truncate(same);
                sent.entries.extend_from_slice(&now[same..]);
            }
            if sent.live != l.task.live {
                sent.live = l.task.live.clone();
                events.push(Event::Live {
                    task: id.clone(),
                    text: l.task.live.clone(),
                });
            }
        }
        let usage = (
            self.runner.weekly.clone(),
            self.runner.five_hour.clone(),
            self.runner.usage_at,
        );
        if crate::usage::reading_changed(&self.usage_sent, &usage) {
            self.usage_sent = usage.clone();
            events.push(Event::Usage {
                weekly: usage.0,
                five_hour: usage.1,
                updated_at: usage.2,
            });
        }
        let commands = (
            self.runner.slash_commands.clone(),
            self.runner.mcp_servers.clone(),
        );
        if commands != self.commands_sent {
            self.commands_sent = commands.clone();
            events.push(Event::Commands {
                slash_commands: commands.0,
                connectors: commands.1,
            });
        }
        while let Ok((project, info)) = self.git_rx.try_recv() {
            if self.git.get(&project) != Some(&info) {
                self.git.insert(project.clone(), info.clone());
                events.push(Event::GitInfo { project, info });
            }
        }
        for p in std::mem::take(&mut self.runner.problems) {
            if !self.problems.contains(&p) {
                self.problems.push(p.clone());
                events.push(Event::Problem { text: p });
            }
        }
        let needs_changed = self.publish_needs();
        events.extend(
            self.note_finished()
                .into_iter()
                .map(|item| Event::Finished { item }),
        );
        if events.is_empty() {
            if needs_changed {
                self.wake_clients();
            }
            return;
        }
        for e in events {
            self.broadcast(e);
        }
        self.wake_clients();
    }
}

/// Tidy moves waiting for Claude to stop, kept across restarts.
fn tidy_waiting_path() -> PathBuf {
    crate::paths::data_dir().join("tidy-waiting.json")
}

/// A task as clients see it.
fn header(l: &runner::Live, queued: &HashMap<String, QueuedMessage>) -> TaskHeader {
    let t = &l.task;
    TaskHeader {
        id: t.id.clone(),
        title: t.title.clone(),
        title_by_owner: t.title_by_owner,
        project: t.project.clone(),
        origin: t.origin.clone(),
        copy_branch: t.copy_branch.clone(),
        from_phone: t.from_phone.clone(),
        session_id: t.session_id.clone(),
        status: t.status,
        mode: t.mode,
        model: t.model.clone(),
        auto_choice: t.auto_choice.clone(),
        effort: t.effort.clone(),
        plan: t.plan.clone(),
        plan_doc: t.plan_doc.clone(),
        files_changed: t.files_changed.clone(),
        cost_usd: t.cost_usd,
        weekly: t.weekly.clone(),
        context_used: t.context_used,
        context_window: t.context_window,
        created_unix: t.created_unix,
        brief: t.brief.clone(),
        checkpoints: t.checkpoints.iter().map(|(i, _)| *i).collect(),
        redo: t.redo.is_some(),
        question_dismissed: t.question_dismissed,
        archived: t.archived,
        incognito: t.incognito,
        entry_count: t.entries.len(),
        running: l.running(),
        busy: l.busy(),
        asks: l
            .asks
            .iter()
            .filter_map(|p| serde_json::to_value(&p.request).ok())
            .collect(),
        queued: queued.get(&t.id).cloned(),
        ran_out: t.ran_out.clone(),
        placed: t.placed,
        placed_why: t.placement_note.as_deref().and_then(crate::placement::why),
        active_unix: t.active_unix,
        live_elsewhere: t.live_elsewhere,
    }
}

/// A few lines to look at before answering a permission request: the
/// change, the new file's start, or the command.
fn permission_preview(r: &crate::approvals::Request) -> Vec<String> {
    let get = |k: &str| {
        r.tool_input
            .get(k)
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default()
    };
    let mut out: Vec<String> = match r.tool_name.as_str() {
        "Edit" => get("old_string")
            .lines()
            .map(|l| format!("-{l}"))
            .chain(get("new_string").lines().map(|l| format!("+{l}")))
            .collect(),
        "Write" => get("content").lines().map(|l| format!("+{l}")).collect(),
        "Bash" => vec![get("command").to_string()],
        _ => vec![],
    };
    out.truncate(40);
    out
}

fn sent_of(t: &Task, h: TaskHeader) -> Sent {
    Sent {
        header: Some(h),
        entries: t.entries.clone(),
        live: t.live.clone(),
    }
}

fn run_id() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("{now:x}-{}", std::process::id())
}

/// This PC's name, as phones show it: the one in settings, else the PC's own.
/// The PC as people see it: the name set in Mímir, else "Your PC" (never
/// the system's own name).
/// The note under an answer from a different model than the usual one.
/// Two models can share a plain name: then the name says nothing, so the
/// note leaves it out.
fn switch_words(usual: Option<&str>, model: &str, reason: &str) -> String {
    let name = crate::models::plain_name(model);
    if usual.is_some_and(|u| crate::models::plain_name(u) == name) {
        format!("Answered by another model on your PC because {reason}.")
    } else {
        format!("Answered by {name} because {reason}.")
    }
}

pub fn shown_pc_name(settings: &Settings) -> String {
    settings
        .pc_name
        .as_deref()
        .map(str::trim)
        .filter(|n| !n.is_empty())
        .map(|n| n.chars().take(60).collect())
        .unwrap_or_else(|| "Your PC".into())
}

pub fn pc_name(settings: &Settings) -> String {
    if let Some(name) = settings
        .pc_name
        .as_deref()
        .map(str::trim)
        .filter(|n| !n.is_empty())
    {
        return name.chars().take(60).collect();
    }
    std::fs::read_to_string("/etc/hostname")
        .map(|s| s.trim().to_string())
        .unwrap_or_default()
}

/// `mimir service`: the service as its own process, until systemd
/// (or whoever started it) stops it. Returns the exit code.
pub fn run_main(version: &str) -> i32 {
    run_main_opening(version, false)
}

/// As [`run_main`]; with `open_window` the service starts a window of its
/// own as soon as it listens (a launcher in a Flatpak becomes the service).
pub fn run_main_opening(version: &str, open_window: bool) -> i32 {
    use std::os::unix::net::UnixListener;
    // SIGTERM and SIGINT are taken by one thread (below), so every thread
    // started from here on leaves them to it.
    let mut set: libc::sigset_t = unsafe { std::mem::zeroed() };
    // SAFETY: plain signal-mask calls on a local set.
    unsafe {
        libc::sigemptyset(&mut set);
        libc::sigaddset(&mut set, libc::SIGTERM);
        libc::sigaddset(&mut set, libc::SIGINT);
        libc::pthread_sigmask(libc::SIG_BLOCK, &set, std::ptr::null_mut());
    }
    // systemd's socket, taken first; its variables then leave this
    // process's environment, so nothing it starts (Claude, its keepers,
    // the commands they run) thinks a socket was handed to it.
    let mut systemd_socket = listen_fd();
    // SAFETY: no other thread runs yet.
    for k in crate::process::SYSTEMD_ONLY {
        unsafe { std::env::remove_var(k) };
    }
    let from_systemd = systemd_socket.is_some();
    let say = |m: &str| eprintln!("mimir service: {m}");
    // Why the service ends early: said, and kept in Mímir's log
    // (which outlives a restart, unlike the runtime folder).
    let ends = |code: i32, m: &str| {
        say(m);
        crate::log::write("service", "ended", &format!("{m} (exit {code})"));
        code
    };
    // A home that is gone (a finished check's) has nothing to serve.
    if !crate::paths::home().is_dir() {
        say("the home folder is gone");
        return 1;
    }
    // Its messages go to a log beside its sockets when nobody watches
    // (under systemd the journal does).
    // SAFETY: isatty and dup2 on the process's own descriptors.
    if unsafe { libc::isatty(2) } == 0
        && std::env::var_os("JOURNAL_STREAM").is_none()
        && crate::sockets::private_dir(&crate::sockets::runtime_dir()).is_ok()
        && let Ok(f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(crate::sockets::runtime_dir().join("service.log"))
    {
        unsafe { libc::dup2(std::os::fd::AsRawFd::as_raw_fd(&f), 2) };
    }
    let dir = crate::sockets::runtime_dir();
    if let Err(e) = crate::sockets::private_dir(&dir) {
        return ends(1, &format!("{} can't be used: {e}", dir.display())); // lint:log-only
    }
    // One service at a time.
    let lock = match std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(crate::sockets::lock_path())
    {
        Ok(f) => f,
        Err(e) => return ends(1, &format!("the lock can't be opened: {e}")), // lint:log-only
    };
    let take_lock = |lock: &std::fs::File| {
        // SAFETY: flock on an open file we own.
        let rc = unsafe {
            libc::flock(
                std::os::fd::AsRawFd::as_raw_fd(lock),
                libc::LOCK_EX | libc::LOCK_NB,
            )
        };
        rc == 0
    };
    if !take_lock(&lock) {
        // Another service runs (the window may have started one before
        // systemd was ready). Without systemd's socket this one isn't
        // needed: it ends quietly, with success.
        if !from_systemd {
            return ends(
                0,
                "another Mímir service is already running; this one isn't needed",
            );
        }
        // systemd's socket now has the window's path: the running service
        // takes it over, so whoever connects reaches the one that runs.
        let fd = systemd_socket
            .as_ref()
            .map_or(-1, std::os::fd::AsRawFd::as_raw_fd);
        match hand_over_socket(fd) {
            Ok(()) => say("another service is running: systemd's socket was handed to it"), // lint:log-only
            Err(e) => say(&format!(
                "another service is running and couldn't take systemd's socket: {e}" // lint:log-only
            )),
        }
        // This one stays (systemd sees it running, never "failed") until
        // the other ends; then it asks systemd for a fresh start (75).
        loop {
            let wait = libc::timespec {
                tv_sec: 0,
                tv_nsec: 300_000_000,
            };
            // SAFETY: waits on the set blocked above.
            if unsafe { libc::sigtimedwait(&set, std::ptr::null_mut(), &wait) } > 0 {
                return ends(0, "stopped while another service was running");
            }
            if take_lock(&lock) {
                return ends(75, "the other service ended; starting afresh in its place");
            }
        }
    }
    let old = crate::approvals::default_socket();
    if std::os::unix::net::UnixStream::connect(&old).is_ok() {
        return ends(1, "close the older Mímir window first");
    }
    // A Flatpak has no systemd groups: tasks run on the PC through the host
    // portal and are stopped one by one.
    if !crate::flatpak::active()
        && let Err(e) = crate::groups::setup()
    {
        if cfg!(debug_assertions) {
            say(&format!("{e} (a debug build goes on without task groups)")); // lint:log-only
        } else {
            return ends(1, &e);
        }
    }
    let group = crate::groups::current().map(|g| g.name.clone());
    // The control socket: from systemd's socket unit, or bound here.
    let control: UnixListener = match systemd_socket.take() {
        Some(l) => l,
        None => match crate::sockets::bind_private(&crate::sockets::control_socket()) {
            Ok(l) => l,
            Err(e) => return ends(1, &format!("the window's socket can't be opened: {e}")), // lint:log-only
        },
    };
    // The path this service bound itself (systemd's socket unit may later
    // put its own there; that one is never removed here).
    let own_path = (!from_systemd)
        .then(|| path_id(&crate::sockets::control_socket()))
        .flatten();
    if let Err(e) = crate::sockets::guard_old_path(&old) {
        say(&format!("the old approvals path can't be guarded: {e}")); // lint:log-only
    }
    crate::log::prune();
    crate::log::write("service", "started", &format!("version {version}"));
    let cfg = Config {
        runner: runner::Config {
            claude: crate::paths::find_claude(),
            hook_exe: crate::paths::own_program(),
            socket: old.clone(),
            store_dir: crate::store::Store::default_dir(),
            env: vec![],
        },
        claude: crate::paths::find_claude(),
        version: version.to_string(),
    };
    let pid_file = crate::sockets::pid_path();
    let _ = std::fs::write(&pid_file, std::process::id().to_string());
    let (tx, rx) = channel::<Inbound>();
    let wake_tx = tx.clone();
    let wake: Wake = Arc::new(move || {
        let _ = wake_tx.send(Inbound::Wake);
    });
    // Claude sessions' asks: the socket hands each to the service thread.
    let session_tx = std::sync::Mutex::new(tx.clone());
    let handler: crate::sockets::SessionHandler = Arc::new(move |msg, task, reply| {
        if let Ok(t) = session_tx.lock() {
            let _ = t.send(Inbound::Session { msg, task, reply });
        }
    });
    if let Err(e) = crate::sockets::serve_session(&crate::sockets::session_socket(), handler) {
        say(&format!("the session socket can't be opened: {e}")); // lint:log-only
    }
    let runner = match Runner::new(cfg.runner, wake.clone()) {
        Ok(mut r) => {
            r.usage_poller = Some(crate::usage::Poller::new(
                crate::paths::home().join(".claude/.credentials.json"),
            ));
            r
        }
        Err(e) => {
            let _ = std::fs::remove_file(&pid_file);
            return ends(1, &format!("could not start: {e}")); // lint:log-only
        }
    };
    // Signals end the service cleanly.
    {
        let tx = tx.clone();
        std::thread::spawn(move || {
            let mut sig: libc::c_int = 0;
            // SAFETY: waits on the set blocked above.
            unsafe { libc::sigwait(&set, &mut sig) };
            let _ = tx.send(Inbound::Stop);
        });
    }
    // Each window that connects gets a reader and a writer.
    let clients = Arc::new(std::sync::atomic::AtomicU64::new(100));
    // In a Flatpak every window proves itself with a secret only this
    // process and the windows it starts have (see `sockets::Capability`).
    let cap = crate::sockets::cap_required().then(|| Arc::new(crate::sockets::Capability::new()));
    accept_windows(
        control,
        group.clone(),
        tx.clone(),
        clients.clone(),
        cap.clone(),
    );
    // A launcher that became the service opens the window now.
    if open_window
        && let Some(c) = &cap
        && let Err(e) = crate::sockets::start_window(c, &crate::sockets::display_env())
    {
        say(&format!("the window can't start: {e}")); // lint:log-only
    }
    // A service systemd starts while this one runs hands its socket over.
    match crate::sockets::bind_private(&crate::sockets::handover_socket()) {
        Ok(l) => {
            let tx = tx.clone();
            let hand_cap = cap.clone();
            std::thread::spawn(move || {
                for conn in l.incoming() {
                    let Ok(mut stream) = conn else { continue };
                    let pid = crate::sockets::peer_pid(&stream);
                    // Compared as the system names it, links resolved.
                    let real = |q: PathBuf| std::fs::canonicalize(&q).unwrap_or(q);
                    let ours = pid.and_then(crate::sockets::program_of).is_some_and(|p| {
                        p == real(crate::paths::own_program())
                            || p == real(crate::install::Layout::for_user().bin())
                    });
                    let in_group = crate::sockets::refuse_peer(pid, group.as_deref())
                        == Some("it runs inside Mímir (a task's process)");
                    if !ours || in_group {
                        let _ = crate::sockets::write_msg(&mut stream, &"refused");
                        continue;
                    }
                    match crate::sockets::recv_fd(&stream) {
                        Ok(fd)
                            if crate::sockets::is_unix_listener(
                                std::os::fd::AsRawFd::as_raw_fd(&fd),
                            ) =>
                        {
                            crate::log::write("service", "took systemd's socket", "");
                            accept_windows(
                                fd.into(),
                                group.clone(),
                                tx.clone(),
                                clients.clone(),
                                hand_cap.clone(),
                            );
                            let _ = crate::sockets::write_msg(&mut stream, &"ok");
                        }
                        _ => {
                            let _ = crate::sockets::write_msg(&mut stream, &"refused");
                        }
                    }
                }
            });
        }
        Err(e) => say(&format!("the handover socket can't be opened: {e}")), // lint:log-only
    }
    let mut s = Service::new(runner, cfg.claude, cfg.version, wake, rx, tx.clone());
    s.pid_file = Some(pid_file.clone());
    // The phone link, from anywhere (n0's relays unless switched off).
    {
        let (ltx, lrx) = channel::<mimir_link::server::LinkEvent>();
        match mimir_link::server::Server::start(
            mimir_link::server::ServerConfig {
                dir: crate::paths::config_dir().join("link"),
                relays: s.settings.phone_relays.unwrap_or(true),
                pc_name: pc_name(&s.settings),
            },
            ltx,
        ) {
            Ok(l) => {
                s.link = Some(l);
                let tx = tx.clone();
                std::thread::spawn(move || {
                    for e in lrx {
                        if tx.send(Inbound::Link(e)).is_err() {
                            break;
                        }
                    }
                });
            }
            Err(e) => say(&format!("{e} (phones can't connect)")), // lint:log-only
        }
    }
    // The tidy the weekly timer used to do: once a day from here.
    crate::keep_current::start_daily_tidy();
    s.run();
    let s_exit = s.exit_code;
    let _ = std::fs::remove_file(&pid_file);
    // systemd's socket stays for the next start; a path this service
    // bound goes, unless systemd has since put its own there.
    let path = crate::sockets::control_socket();
    if own_path.is_some() && path_id(&path) == own_path {
        let _ = std::fs::remove_file(&path);
    }
    let _ = std::fs::remove_file(crate::sockets::handover_socket());
    let _ = std::fs::remove_file(crate::sockets::session_socket());
    let _ = std::fs::remove_file(&old);
    crate::log::write("service", "stopped", "");
    drop(lock);
    s_exit
}

/// A file's identity (device and inode), to tell whether a path still
/// names the same socket.
fn path_id(p: &std::path::Path) -> Option<(u64, u64)> {
    use std::os::unix::fs::MetadataExt;
    std::fs::symlink_metadata(p)
        .ok()
        .map(|m| (m.dev(), m.ino()))
}

/// Accepts windows on a control socket, each with a reader and a writer.
/// With `cap` (a Flatpak: the peer can't be identified by its process) a
/// connection is a window only when its first message carries the
/// capability; anything else is refused before it can call anything.
fn accept_windows(
    control: std::os::unix::net::UnixListener,
    group: Option<String>,
    tx: Sender<Inbound>,
    clients: Arc<std::sync::atomic::AtomicU64>,
    cap: Option<Arc<crate::sockets::Capability>>,
) {
    std::thread::spawn(move || {
        let last_open: Arc<std::sync::Mutex<Option<Instant>>> = Arc::default();
        for conn in control.incoming() {
            let Ok(stream) = conn else { continue };
            if let Some(cap) = cap.clone() {
                let (tx, clients, last_open) = (tx.clone(), clients.clone(), last_open.clone());
                std::thread::spawn(move || admit_window(stream, &cap, tx, clients, &last_open));
                continue;
            }
            if let Some(why) =
                crate::sockets::refuse_peer(crate::sockets::peer_pid(&stream), group.as_deref())
            {
                let mut s = stream;
                let _ = crate::sockets::write_msg(&mut s, &format!("refused: {why}"));
                continue;
            }
            let next = clients.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
            serve_client(next, stream, tx.clone());
        }
    });
}

/// The capability check: the first message must be the capability (a
/// window) or a request to open one (a launcher).
fn admit_window(
    mut stream: std::os::unix::net::UnixStream,
    cap: &crate::sockets::Capability,
    tx: Sender<Inbound>,
    clients: Arc<std::sync::atomic::AtomicU64>,
    last_open: &std::sync::Mutex<Option<Instant>>,
) {
    use crate::sockets::Opening;
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let first = crate::sockets::read_msg::<Opening>(&mut stream)
        .ok()
        .flatten();
    let _ = stream.set_read_timeout(None);
    match first {
        Some(Opening::Cap(given)) if cap.accepts(&given) => {
            let next = clients.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
            serve_client(next, stream, tx);
        }
        Some(Opening::OpenWindow(env)) => {
            // Anyone may ask; at most one window every few seconds.
            let reply = {
                let mut last = last_open.lock().unwrap_or_else(|e| e.into_inner());
                if last.is_some_and(|t| t.elapsed() < Duration::from_secs(3)) {
                    "refused: a window was just opened".to_string()
                } else {
                    *last = Some(Instant::now());
                    match crate::sockets::start_window(cap, &env) {
                        Ok(()) => "ok".to_string(),
                        Err(e) => format!("refused: the window can't start ({e})"),
                    }
                }
            };
            let _ = crate::sockets::write_msg(&mut stream, &reply);
        }
        _ => {
            let _ = crate::sockets::write_msg(&mut stream, &"refused: it isn't Mímir's window");
        }
    }
}

/// A second service's side of the handover: systemd's listening socket
/// (fd 3) goes to the service that runs.
fn hand_over_socket(fd: std::os::fd::RawFd) -> Result<(), String> {
    let s = std::os::unix::net::UnixStream::connect(crate::sockets::handover_socket())
        .map_err(|e| format!("the running service can't be reached ({e})"))?;
    let _ = s.set_read_timeout(Some(std::time::Duration::from_secs(5)));
    crate::sockets::send_fd(&s, fd).map_err(|e| e.to_string())?;
    let mut r = std::io::BufReader::new(&s);
    match crate::sockets::read_msg::<String>(&mut r) {
        Ok(Some(a)) if a == "ok" => Ok(()),
        Ok(Some(a)) => Err(a),
        Ok(None) => Err("no answer".into()),
        Err(e) => Err(e.to_string()),
    }
}

fn listen_fd_given() -> bool {
    std::env::var("LISTEN_PID")
        .ok()
        .and_then(|p| p.parse::<u32>().ok())
        == Some(std::process::id())
        && std::env::var("LISTEN_FDS")
            .ok()
            .and_then(|n| n.parse::<u32>().ok())
            .is_some_and(|n| n >= 1)
}

/// The control socket systemd's socket unit hands over (fd 3).
fn listen_fd() -> Option<std::os::unix::net::UnixListener> {
    if !listen_fd_given() {
        return None;
    }
    // Programs this process starts never inherit it.
    // SAFETY: fcntl on fd 3, which systemd passed (checked above).
    unsafe { libc::fcntl(3, libc::F_SETFD, libc::FD_CLOEXEC) };
    // SAFETY: systemd passes the listening socket as fd 3.
    Some(unsafe { std::os::fd::FromRawFd::from_raw_fd(3) })
}

/// A phone's events go through a bounded queue too (the link's own queue
/// is unbounded): a thread of its own hands them on (its ClientWriter).
fn bounded_link_queue(link: Sender<EventMsg>) -> SyncSender<EventMsg> {
    let (tx, rx) = sync_channel::<EventMsg>(CLIENT_QUEUE);
    let _ = std::thread::Builder::new()
        .name("phone-writer".into())
        .spawn(move || {
            loop {
                match rx.recv_timeout(Duration::from_secs(3600)) {
                    Ok(m) => {
                        if link.send(m).is_err() {
                            return;
                        }
                    }
                    Err(RecvTimeoutError::Timeout) => {}
                    Err(RecvTimeoutError::Disconnected) => return,
                }
            }
        });
    tx
}

/// One window over the control socket: its calls in, its events out.
fn serve_client(client: u64, stream: std::os::unix::net::UnixStream, tx: Sender<Inbound>) {
    let (etx, erx) = sync_channel::<EventMsg>(CLIENT_QUEUE);
    let _ = tx.send(Inbound::Connect {
        client,
        events: etx,
        wake: Arc::new(|| {}),
    });
    let Ok(mut writer) = stream.try_clone() else {
        return;
    };
    std::thread::spawn(move || {
        for m in erx {
            if crate::sockets::write_msg(&mut writer, &m).is_err() {
                break;
            }
        }
        let _ = writer.shutdown(std::net::Shutdown::Both);
    });
    std::thread::spawn(move || {
        let mut r = std::io::BufReader::new(stream);
        while let Ok(Some(data)) = crate::sockets::read_frame(&mut r) {
            // Not a call: the connection is dropped.
            let Ok(msg) = serde_json::from_slice::<CallMsg>(&data) else {
                break;
            };
            let _ = tx.send(Inbound::Call { client, msg });
        }
        let _ = tx.send(Inbound::Disconnect { client });
    });
}

/// Whether a usage limit that ran out needs the PC's model awake now: only
/// to carry on at once, never while the owner is still being asked.
fn wake_for_limit(power: settings::Power, ask_before_free: bool, claude_task_hit: bool) -> bool {
    claude_task_hit && power == settings::Power::Backup && !ask_before_free
}

/// Why a chat can't be answered on the PC when no model is picked: the
/// owner gets the setup hint, anyone else is told what is missing.
fn no_model_words(owner: bool, e: Option<&crate::engine::EngineFile>) -> String {
    if owner {
        crate::engine::words(e)
    } else {
        crate::people::NO_FREE_MODEL.to_string()
    }
}

/// After a failed wake of the PC's model, waiting calls are not made to
/// wait again for a while: they go on at once (and find no model).
#[derive(Default)]
struct WakeGate {
    failed_at: Option<Instant>,
}

impl WakeGate {
    const PAUSE: Duration = Duration::from_secs(30);

    fn should_wait(&self, now: Instant) -> bool {
        self.failed_at
            .is_none_or(|t| now.saturating_duration_since(t) >= Self::PAUSE)
    }
    fn failed(&mut self, now: Instant) {
        self.failed_at = Some(now);
    }
    fn worked(&mut self) {
        self.failed_at = None;
    }
}

/// For checks: `MIMIR_TEST_DELAY_MS` slows every call.
fn test_delay() -> Option<Duration> {
    static D: std::sync::OnceLock<Option<Duration>> = std::sync::OnceLock::new();
    *D.get_or_init(|| {
        std::env::var("MIMIR_TEST_DELAY_MS")
            .ok()
            .and_then(|v| v.parse().ok())
            .map(Duration::from_millis)
    })
}

/// What a message is, for the slow-message log.
fn inbound_kind(m: &Inbound) -> &'static str {
    match m {
        Inbound::Connect { .. } => "Connect",
        Inbound::Disconnect { .. } => "Disconnect",
        Inbound::Call { .. } => "Call",
        Inbound::Wake => "Wake",
        Inbound::Stop => "Stop",
        Inbound::Link(_) => "Link",
        Inbound::Session { .. } => "Session",
        Inbound::Later { .. } => "Later",
        Inbound::Audio { .. } => "Audio",
        Inbound::Talk(_) => "Talk",
        Inbound::Woken { .. } => "Woken",
        Inbound::Waking { .. } => "Waking",
    }
}

/// The slow calls that run on a thread of their own: which log category and
/// event they belong to, their task, and a word about what they did (a
/// lookup's site, never its content).
#[allow(clippy::type_complexity)]
fn later_info(call: &Call) -> Option<(&'static str, &'static str, Option<String>, Option<String>)> {
    match call {
        Call::Speak { task, .. } => Some(("voice", "speak", Some(task.clone()), None)),
        Call::Listen { .. } => Some(("voice", "listen", None, None)),
        Call::WebSearch { query, .. } => Some((
            "lookup",
            "search",
            None,
            Some(format!("{} characters", query.chars().count())),
        )),
        Call::Lookup { url } => Some((
            "lookup",
            "fetch",
            None,
            Some(
                url.split('/')
                    .nth(2)
                    .unwrap_or("")
                    .chars()
                    .take(80)
                    .collect(),
            ),
        )),
        _ => None,
    }
}

/// The task a call is about, for the log.
fn call_task(call: &Call) -> Option<String> {
    match call {
        Call::Send { task, .. }
        | Call::SendQueued { task }
        | Call::Stop { task }
        | Call::Pause { task }
        | Call::Continue { task }
        | Call::Speak { task, .. }
        | Call::EndIncognito { task }
        | Call::ContinueOnFree { task }
        | Call::ContinueOnClaude { task }
        | Call::Queue { task, .. }
        | Call::Unqueue { task } => Some(task.clone()),
        _ => None,
    }
}

/// Why Read aloud is refused on a Whisper task.
const WHISPER_NO_SPEAK: &str =
    "A Whisper keeps nothing, so it isn't read aloud (that would leave the sound on your PC).";

#[cfg(test)]
mod pc_name_tests {
    /// An end of work never gets an id an earlier one had, even for the same
    /// task with the same number of entries (an undo brings the length back).
    #[test]
    fn a_finished_id_is_never_repeated() {
        let a = super::finished_id("t-1", 1_794_000_000, 1);
        let b = super::finished_id("t-1", 1_794_000_000, 2);
        let c = super::finished_id("t-1", 1_794_000_100, 1);
        assert!(a != b && a != c && b != c, "{a} {b} {c}");
        assert!(a.starts_with("fin:t-1:"));
    }

    #[test]
    fn two_models_with_one_plain_name_are_not_named_in_the_note() {
        let r = "your usual model can't look at pictures";
        let usual = Some("qwen3.6:35b-a3b-coding");
        assert_eq!(
            crate::models::plain_name("qwen3.6:35b-a3b-coding"),
            crate::models::plain_name("qwen3.6-coder-agent")
        );
        let note = super::switch_words(usual, "qwen3.6-coder-agent", r);
        assert_eq!(
            note,
            "Answered by another model on your PC because your usual model can't look at pictures."
        );
        let other = super::switch_words(usual, "llama3.2-vision", r);
        assert!(other.starts_with("Answered by Llama 3.2"), "{other}");
    }

    #[test]
    fn another_person_is_told_what_is_missing_when_no_model_is_set_up() {
        let other = super::no_model_words(false, None);
        assert!(other.contains("no free model set up yet"), "{other}");
        assert!(other.contains("owner"), "{other}");
        assert_eq!(
            super::no_model_words(true, None),
            crate::engine::words(None)
        );
    }

    #[test]
    fn the_model_is_not_woken_while_the_owner_is_asked_first() {
        use crate::settings::Power;
        assert!(super::wake_for_limit(Power::Backup, false, true));
        assert!(!super::wake_for_limit(Power::Backup, true, true));
        assert!(!super::wake_for_limit(Power::FreeOnly, false, true));
        assert!(!super::wake_for_limit(Power::Backup, false, false));
    }

    #[test]
    fn after_a_failed_wake_waiting_calls_do_not_wait_again_for_thirty_seconds() {
        use std::time::{Duration, Instant};
        let mut g = super::WakeGate::default();
        let t = Instant::now();
        assert!(g.should_wait(t));
        g.failed(t);
        assert!(!g.should_wait(t + Duration::from_secs(5)));
        assert!(g.should_wait(t + Duration::from_secs(31)));
        g.failed(t);
        g.worked();
        assert!(g.should_wait(t));
    }

    use super::*;

    #[test]
    fn a_name_in_settings_is_the_one_phones_see() {
        let named = Settings {
            pc_name: Some("  Test PC (Fixer) ".into()),
            ..Settings::default()
        };
        assert_eq!(pc_name(&named), "Test PC (Fixer)");
        // Empty or missing: the PC's own name, as before.
        let blank = Settings {
            pc_name: Some("  ".into()),
            ..Settings::default()
        };
        assert_eq!(pc_name(&blank), pc_name(&Settings::default()));
    }
}
