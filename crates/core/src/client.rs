//! A client of the service (the window): its connection, the mirror of the
//! service's state it keeps from the events, and calls with replies.
//!
//! The mirror reads like the `Runner` the window used to own (`tasks`,
//! `weekly`, `problems`, …), and the actions are calls with the same names,
//! so the window's drawing code reads the same as before.

use crate::approvals::{Answer, Request};
use crate::runner::Wake;
use crate::task::{Mode, Task, WeeklyUse};
use mimir_proto::{
    Call, CallMsg, ChatRecord, ChatTurn, ChatsPage, Choice, Event, EventMsg, Filed, GitInfo,
    NeedsYouItem, PROTO, Picture, QueuedMessage, TalkState, TaskState,
};
use serde_json::Value;
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, RecvTimeoutError, channel};
use std::time::{Duration, Instant};

/// How long a call may take before the window gives up on it.
const PATIENCE: Duration = Duration::from_secs(10);

/// What `call_wait` says when the service never answered.
const NO_ANSWER: &str = "Mímir's service didn't answer.";
/// What `call_wait` says when the service went away mid-call.
const SERVICE_STOPPED: &str = "Mímir's service stopped.";

/// True when a `call_wait` error came from the road to the service (it
/// stalled or stopped), not from the service refusing the call.
pub fn is_transport_error(e: &str) -> bool {
    e == NO_ANSWER || e == SERVICE_STOPPED
}

/// A permission request waiting for the owner.
pub struct AskView {
    pub request: Request,
}

/// A task in the mirror.
pub struct TaskView {
    pub task: Task,
    pub asks: VecDeque<AskView>,
    running: bool,
    busy: bool,
    /// Phone too's terminal screen.
    pub screen: Vec<String>,
    /// A message waiting until Claude is done.
    pub queued: Option<QueuedMessage>,
    /// The state the PC's truth gives this task (None until it has).
    truth_state: Option<TaskState>,
    /// What Claude is doing right now, as the PC says (None: not working).
    doing: Option<String>,
    /// When the working turn began (seconds since 1970), as the PC says.
    turn_unix: Option<i64>,
    /// When anything last came in for this task.
    news: Instant,
}

impl TaskView {
    /// What Claude is doing right now and for how long, in plain words
    /// ("Reading main.rs · 12 s"); when nothing came in for
    /// [`mimir_proto::QUIET_AFTER_SECS`] while it works, a line that says
    /// so instead of looking frozen. None when it is not working.
    pub fn live_line(&self, now_unix: i64) -> Option<String> {
        let doing = self.doing.as_ref()?;
        let quiet = self.news.elapsed().as_secs();
        if quiet >= mimir_proto::QUIET_AFTER_SECS {
            return Some(format!("Still working; no news for {quiet} s"));
        }
        Some(match self.turn_unix {
            Some(t) if now_unix >= t => format!(
                "{} · {}",
                doing,
                mimir_proto::worked_for((now_unix - t) as u64)
            ),
            _ => doing.clone(),
        })
    }

    /// The task's one state for every surface of the window: the PC's
    /// truth when it has told, else the same rule worked out here.
    pub fn shown_state(&self) -> TaskState {
        self.truth_state.unwrap_or_else(|| {
            if self.task.needs_owner() {
                TaskState::NeedsYou
            } else if self.working_now() {
                TaskState::Working
            } else if self.task.live_elsewhere {
                TaskState::LiveElsewhere
            } else if matches!(
                self.task.status,
                crate::task::Status::Paused | crate::task::Status::Stopped
            ) {
                TaskState::Stopped
            } else {
                TaskState::Done
            }
        })
    }

    /// A session going on in another app or a terminal that Mímir only
    /// follows: it can be stopped there, not carried on here until then.
    pub fn outside_now(&self) -> bool {
        self.task.live_elsewhere && !self.running
    }

    /// A Claude Code process runs for this task.
    pub fn running(&self) -> bool {
        self.running
    }

    /// Claude is working on this task right now (not just finishing up).
    pub fn busy(&self) -> bool {
        self.busy
    }

    /// Claude works on it right now, here or in a session followed from
    /// elsewhere (what "working" counts and what turns the mark).
    pub fn working_now(&self) -> bool {
        self.task.working_now(self.busy)
    }
}

/// A chat answer being written, as far as it has come.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Partial {
    /// The answer's id (`<message id>.a`).
    pub turn_id: String,
    pub text: String,
    /// The model is thinking: no words of the answer yet.
    pub thinking: bool,
    /// The next piece wanted (pieces count from 0).
    next: u32,
    /// A piece was missed and the answer so far is on its way.
    catching_up: bool,
    /// The next piece taken is the one that follows what was caught up.
    resync: bool,
}

/// What the PC said about the last message sent from the window's chat.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ChatNote {
    /// A line to show while waiting ("Waking your PC's model…").
    pub waiting: Option<String>,
    /// Why nothing came, in plain words.
    pub problem: Option<String>,
    /// The message started a project task: (project folder, task id).
    pub filed: Option<(String, String)>,
    /// The message was a status question, or an idea kept in a project.
    pub kept: Option<String>,
}

/// A call whose reply the chat mirror waits for.
enum ChatCall {
    Since,
    Talk {
        chat_id: String,
    },
    /// How an answer stands. `adopt`: asked about a chat nobody here sent
    /// a message in (after a reconnect or a start), so an answer found
    /// under way is taken up, and "unknown" is no problem.
    Turn {
        chat_id: String,
        adopt: bool,
        /// A message this window sent: if the PC knows nothing of it, the
        /// message was lost and is given back to be sent again.
        own: bool,
    },
}

enum Conn {
    /// The service is its own process; the window talks over its socket.
    Socket {
        path: PathBuf,
        stream: Option<std::os::unix::net::UnixStream>,
        rx: Receiver<EventMsg>,
        wake: Wake,
        /// When the window last tried to reach the service again.
        tried: Instant,
    },
}

/// Connects to the service's socket; events come in on the channel.
fn open_socket(
    path: &std::path::Path,
    wake: Wake,
) -> std::io::Result<(std::os::unix::net::UnixStream, Receiver<EventMsg>)> {
    let mut stream = std::os::unix::net::UnixStream::connect(path)?;
    crate::sockets::send_opening(&mut stream)?;
    let mut reader = std::io::BufReader::new(stream.try_clone()?);
    let (tx, rx) = channel();
    std::thread::spawn(move || {
        while let Ok(Some(data)) = crate::sockets::read_frame(&mut reader) {
            // A refusal is plain text, not an event: the connection ends.
            let Ok(m) = serde_json::from_slice::<EventMsg>(&data) else {
                // "refused: …" with a capability held: the service that
                // runs isn't the one that started this window.
                if crate::sockets::held().is_some()
                    && serde_json::from_slice::<String>(&data)
                        .is_ok_and(|t| t.starts_with("refused"))
                {
                    crate::sockets::REFUSED.store(true, std::sync::atomic::Ordering::Relaxed);
                }
                break;
            };
            if tx.send(m).is_err() {
                break;
            }
            wake();
        }
        wake();
    });
    Ok((stream, rx))
}

/// The window's view of the service.
pub struct Station {
    pub tasks: Vec<TaskView>,
    pub weekly: Option<WeeklyUse>,
    pub five_hour: Option<WeeklyUse>,
    /// When they were last refreshed (unix seconds).
    pub usage_at: Option<i64>,
    /// Plain-word problems to show until dismissed.
    pub problems: Vec<String>,
    /// Problems the service raised (dismissing them tells it).
    from_service: HashSet<String>,
    pub slash_commands: Vec<String>,
    pub mcp_servers: Vec<(String, String)>,
    pub git: HashMap<String, GitInfo>,
    /// The service's settings keys, and a count that goes up when they change.
    pub settings: Value,
    pub settings_changes: u64,
    conn: Conn,
    next_call: u64,
    replies: HashMap<u64, Result<Value, String>>,
    /// Calls nobody waits for: a failure becomes a problem.
    unwatched: HashSet<u64>,
    pub seq: u64,
    pub run_id: String,
    /// The PC's one truth about its work (states, counts, projects).
    pub truth: Option<mimir_proto::Truth>,
    version: String,
    /// The program that starts the service again when systemd doesn't.
    program: Option<PathBuf>,
    /// Since when the service can't be reached.
    lost: Option<Instant>,
    /// A phone waiting for "Yes, they match": its number, name and words.
    pub pairing: Option<(u64, String, Vec<String>)>,
    /// Goes up when a pairing ends (the phones list may have changed).
    pub phones_changes: u64,
    /// Everything waiting for the owner (permissions, questions, asks).
    pub needs: Vec<NeedsYouItem>,
    /// The newest updates, newest first.
    pub updates: Vec<mimir_proto::UpdateCard>,
    /// Labs: what Claude is doing on this PC (None while switched off).
    pub activity: Option<mimir_proto::PcActivity>,
    /// The general chats, the same as the phone's (deleted ones are gone).
    pub chats: BTreeMap<String, ChatRecord>,
    /// Where `ChatsSince` asks from: moved only by its own pages, never by a
    /// live event (a live change would skip what happened while away).
    chats_seq: u64,
    /// Goes up when a chat's content changes (not for each piece of an
    /// answer): what a search index of the chats is rebuilt on.
    pub chats_rev: u64,
    /// Chats deleted here or on the PC: nothing late brings them back.
    deleted_chats: HashSet<String>,
    /// Turns cut away by an edit (id to the tombstone's version): an older copy never brings one back.
    cuts: HashMap<String, u32>,
    /// The last edit's cut-away turns, for Undo (chat id, the turns as they were).
    last_cut: Option<(String, Vec<ChatTurn>)>,
    /// When a failed load of the chats is tried again, and the wait after it.
    chats_retry: Option<Instant>,
    chats_backoff: Duration,
    /// The chats are to be asked for again (a new connection).
    chats_stale: bool,
    /// Answers being written, per chat.
    pub partials: HashMap<String, Partial>,
    /// What the PC said to the last message, per chat.
    pub chat_notes: HashMap<String, ChatNote>,
    /// Chats where a message was sent and its answer hasn't come yet.
    asking: HashSet<String>,
    /// Messages the PC never got, waiting to go back in the message box.
    unsent: HashMap<String, String>,
    /// Goes up whenever a chat, a piece of an answer or a note changed.
    pub chats_changes: u64,
    chat_calls: HashMap<u64, ChatCall>,
}

impl Station {
    /// A mirror with nothing in it yet, over this connection.
    fn blank(conn: Conn, version: String) -> Station {
        Station {
            tasks: vec![],
            weekly: None,
            five_hour: None,
            usage_at: None,
            problems: vec![],
            from_service: HashSet::new(),
            slash_commands: vec![],
            mcp_servers: vec![],
            git: HashMap::new(),
            settings: Value::Null,
            settings_changes: 0,
            conn,
            next_call: 0,
            replies: HashMap::new(),
            unwatched: HashSet::new(),
            seq: 0,
            run_id: String::new(),
            truth: None,
            version,
            program: None,
            lost: None,
            pairing: None,
            phones_changes: 0,
            needs: vec![],
            updates: vec![],
            activity: None,
            chats: BTreeMap::new(),
            chats_seq: 0,
            chats_rev: 0,
            deleted_chats: HashSet::new(),
            cuts: HashMap::new(),
            last_cut: None,
            chats_retry: None,
            chats_backoff: Duration::from_secs(1),
            chats_stale: true,
            partials: HashMap::new(),
            chat_notes: HashMap::new(),
            asking: HashSet::new(),
            unsent: HashMap::new(),
            chats_changes: 0,
            chat_calls: HashMap::new(),
        }
    }

    /// Connects to the service running as its own process.
    pub fn connect(path: &std::path::Path, version: &str, wake: Wake) -> std::io::Result<Station> {
        let (stream, rx) = open_socket(path, wake.clone())?;
        let conn = Conn::Socket {
            path: path.to_path_buf(),
            stream: Some(stream),
            rx,
            wake,
            tried: Instant::now(),
        };
        let mut s = Station::blank(conn, version.to_string());
        s.hello().map_err(std::io::Error::other)?;
        Ok(s)
    }

    /// The window's first words to the service; a full picture comes back.
    fn hello(&mut self) -> Result<Value, String> {
        // A new connection: what changed meanwhile comes by `ChatsSince`.
        self.chats_stale = true;
        // Replies to the old connection never come.
        self.chat_calls.clear();
        self.chats_retry = None;
        // Answers that were being written are asked after: the chat stays
        // "answering" while the PC still works on it.
        self.partials.clear();
        let asked: Vec<(String, String)> = self
            .asking
            .iter()
            .filter_map(|c| Some((c.clone(), self.last_user_turn(c)?)))
            .collect();
        self.asking.retain(|c| asked.iter().any(|(a, _)| a == c));
        for (chat_id, turn_id) in asked {
            let id = self.send(Call::TalkTurn {
                chat_id: chat_id.clone(),
                turn_id,
            });
            self.chat_calls.insert(
                id,
                ChatCall::Turn {
                    chat_id,
                    adopt: true,
                    own: true,
                },
            );
        }
        // The window's desktop, for the tasks the service starts for it.
        let env = [
            "PATH",
            "SSH_AUTH_SOCK",
            "DISPLAY",
            "WAYLAND_DISPLAY",
            "DBUS_SESSION_BUS_ADDRESS",
        ]
        .iter()
        .filter_map(|k| std::env::var(k).ok().map(|v| (k.to_string(), v)))
        .collect();
        self.call_wait(Call::Hello {
            proto: PROTO,
            app_version: self.version.clone(),
            client_name: "window".into(),
            env,
            features: mimir_proto::FEATURES
                .iter()
                .map(|f| f.to_string())
                .collect(),
        })
    }

    /// The service can't be reached right now (the window shows
    /// "Reconnecting…" and keeps the last state on screen).
    pub fn reconnecting(&self) -> bool {
        matches!(&self.conn, Conn::Socket { stream: None, .. })
    }

    /// After the service stopped: tries again, at most once a second.
    fn reconnect(&mut self) {
        let Conn::Socket {
            path,
            stream,
            rx,
            wake,
            tried,
        } = &mut self.conn;
        if tried.elapsed() < Duration::from_secs(1) {
            wake();
            return;
        }
        *tried = Instant::now();
        let lost = *self.lost.get_or_insert_with(Instant::now);
        relaunch_if_refused();
        match open_socket(path, wake.clone()) {
            Ok((s, r)) => {
                *stream = Some(s);
                *rx = r;
                self.lost = None;
                let _ = self.hello();
            }
            Err(_) => {
                // Nobody started it again: the window does, after a few seconds.
                if lost.elapsed() > Duration::from_secs(3)
                    && let Some(p) = &self.program
                {
                    relaunch_if_holding();
                    if let Ok(mut c) = start_service(p) {
                        std::thread::spawn(move || {
                            let _ = c.wait();
                        });
                    }
                    self.lost = Some(Instant::now());
                }
                // Not back yet: look again in a second.
                let w = wake.clone();
                std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_secs(1));
                    w();
                });
            }
        }
    }

    fn send(&mut self, call: Call) -> u64 {
        self.next_call += 1;
        let id = self.next_call;
        let msg = CallMsg { call_id: id, call };
        if let Conn::Socket { stream, .. } = &mut self.conn
            && let Some(s) = stream
            && crate::sockets::write_msg(s, &msg).is_err()
        {
            *stream = None;
        }
        id
    }

    /// A call nobody waits for; if it fails, the reason shows as a problem.
    pub fn call(&mut self, call: Call) {
        let id = self.send(call);
        self.unwatched.insert(id);
    }

    /// A call whose answer is needed now. Events that arrive meanwhile are
    /// taken in as usual.
    pub fn call_wait(&mut self, call: Call) -> Result<Value, String> {
        let id = self.send(call);
        let until = Instant::now() + PATIENCE;
        loop {
            if let Some(r) = self.replies.remove(&id) {
                return r;
            }
            let left = until.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err(NO_ANSWER.into());
            }
            let got = match &self.conn {
                Conn::Socket { rx, .. } => rx.recv_timeout(left),
            };
            match got {
                Ok(m) => self.take(m),
                Err(RecvTimeoutError::Timeout) => {}
                Err(RecvTimeoutError::Disconnected) => {
                    return Err(SERVICE_STOPPED.into());
                }
            }
        }
    }

    /// Takes in every event that has arrived. True if there were any.
    pub fn poll(&mut self) -> bool {
        let mut any = false;
        if self.reconnecting() {
            self.reconnect();
        } else {
            self.ask_chats();
        }
        loop {
            let got = match &self.conn {
                Conn::Socket { rx, .. } => rx.try_recv(),
            };
            match got {
                Ok(m) => {
                    any = true;
                    self.take(m);
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => return any,
                Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    if let Conn::Socket { stream, .. } = &mut self.conn
                        && stream.is_some()
                    {
                        *stream = None;
                        self.reconnect();
                    }
                    return any;
                }
            }
        }
    }

    fn view(&mut self, id: &str) -> Option<&mut TaskView> {
        self.tasks.iter_mut().find(|v| v.task.id == id)
    }

    #[cfg(test)]
    fn take_event_for_test(&mut self, e: Event) {
        self.take(EventMsg { seq: 0, event: e });
    }

    fn take(&mut self, m: EventMsg) {
        self.seq = self.seq.max(m.seq);
        match m.event {
            Event::Welcome { run_id, .. } => self.run_id = run_id,
            Event::Snapshot { run_id } => {
                self.run_id = run_id;
                self.tasks.clear();
                self.git.clear();
            }
            Event::Ready => {}
            Event::Task { header } => {
                let asks: VecDeque<AskView> = header
                    .asks
                    .iter()
                    .filter_map(|v| serde_json::from_value(v.clone()).ok())
                    .map(|request| AskView { request })
                    .collect();
                match self.tasks.iter_mut().find(|v| v.task.id == header.id) {
                    Some(v) => {
                        v.task.set_header(&header);
                        v.asks = asks;
                        v.running = header.running;
                        v.busy = header.busy;
                        v.queued = header.queued.clone();
                        v.doing = header.doing.clone();
                        v.turn_unix = header.turn_unix;
                        v.news = Instant::now();
                    }
                    None => {
                        let mut task = Task::new(
                            header.id.clone(),
                            header.title.clone(),
                            header.project.clone(),
                            header.created_unix,
                        );
                        task.set_header(&header);
                        self.tasks.push(TaskView {
                            task,
                            asks,
                            running: header.running,
                            busy: header.busy,
                            screen: vec![],
                            queued: header.queued.clone(),
                            doing: header.doing.clone(),
                            turn_unix: header.turn_unix,
                            news: Instant::now(),
                            truth_state: self
                                .truth
                                .as_ref()
                                .and_then(|t| t.tasks.iter().find(|x| x.id == header.id))
                                .map(|x| x.state),
                        });
                    }
                }
            }
            Event::Removed { task } => self.tasks.retain(|v| v.task.id != task),
            Event::Entries {
                task,
                from,
                entries,
                meta,
            } => {
                if let Some(v) = self.view(&task) {
                    v.task.entries.truncate(from);
                    v.task.entries.extend(entries);
                    v.task.set_metas_from(from, meta);
                    v.news = Instant::now();
                }
            }
            Event::Live { task, text } => {
                if let Some(v) = self.view(&task) {
                    v.task.live = text;
                    v.news = Instant::now();
                }
            }
            Event::Screen { task, lines } => {
                if let Some(v) = self.view(&task) {
                    v.screen = lines;
                }
            }
            Event::Usage {
                weekly,
                five_hour,
                updated_at,
            } => {
                self.usage_at = updated_at;
                self.weekly = weekly;
                self.five_hour = five_hour;
            }
            Event::Problem { text } => {
                self.from_service.insert(text.clone());
                if !self.problems.contains(&text) {
                    self.problems.push(text);
                }
            }
            Event::Commands {
                slash_commands,
                connectors,
            } => {
                self.slash_commands = slash_commands;
                self.mcp_servers = connectors;
            }
            Event::Settings { settings } => {
                self.settings = settings;
                self.settings_changes += 1;
            }
            Event::GitInfo { project, info } => {
                self.git.insert(project, info);
            }
            Event::PairPending {
                pending,
                phone_name,
                words,
            } => self.pairing = Some((pending, phone_name, words)),
            Event::PairEnded { pending, .. } => {
                if self.pairing.as_ref().is_some_and(|p| p.0 == pending) {
                    self.pairing = None;
                }
                self.phones_changes += 1;
            }
            Event::NeedsYou { items } => self.needs = items,
            Event::Updates { cards } => self.updates = cards,
            // Labs: only phones show the test notice.
            Event::TestNotice { .. } | Event::TestNoticeOff => {}
            // The phone's alerts; the window has nothing to show for them.
            Event::Finished { .. } | Event::RecentFinished { .. } => {}
            // The phone's "Running on your PC" list.
            Event::PcSessions { .. } => {}
            Event::Activity { state } => self.activity = state,
            Event::Truth { truth } => {
                if crate::truth::newer(self.truth.as_ref(), &truth) {
                    for v in &mut self.tasks {
                        v.truth_state = truth
                            .tasks
                            .iter()
                            .find(|x| x.id == v.task.id)
                            .map(|x| x.state);
                    }
                    self.truth = Some(*truth);
                }
            }
            Event::ChatChanged { chat } => self.take_chat(chat),
            // Only phones act on it (they ask `Models` again).
            Event::ModelsChanged => {}
            Event::TalkPiece {
                chat_id,
                turn_id,
                n,
                text,
                thinking,
            } => self.take_piece(chat_id, turn_id, n, text, thinking),
            Event::TalkDone {
                chat_id,
                turn_id,
                result,
            } => {
                if self
                    .partials
                    .get(&chat_id)
                    .is_some_and(|p| p.turn_id == turn_id)
                {
                    self.partials.remove(&chat_id);
                }
                self.asking.remove(&chat_id);
                match result {
                    Ok(turn) => self.take_chat(ChatRecord {
                        id: chat_id.clone(),
                        at: turn.at,
                        seq: turn.seq,
                        turns: vec![turn],
                        ..ChatRecord::default()
                    }),
                    // Stopped by the owner: not a problem.
                    Err(why) if why == "Stopped." => {}
                    Err(why) => {
                        if !self.deleted_chats.contains(&chat_id) {
                            self.chat_notes.entry(chat_id).or_default().problem = Some(why);
                        }
                    }
                }
                self.chats_changes += 1;
            }
            Event::Reply { call_id, result } => {
                if let Some(call) = self.chat_calls.remove(&call_id) {
                    self.chat_reply(call, result);
                } else if self.unwatched.remove(&call_id) {
                    if let Err(e) = result {
                        self.problem(&e);
                    }
                } else {
                    self.replies.insert(call_id, result);
                }
            }
        }
    }

    /// "N working" and "N need you", as the PC's truth counts them (the
    /// window's own count until the truth has come).
    pub fn counts(&self) -> (usize, usize) {
        match &self.truth {
            Some(t) => (t.counts.working as usize, t.counts.needs_you as usize),
            None => (
                self.tasks
                    .iter()
                    .filter(|l| l.shown_state() == TaskState::Working)
                    .count(),
                self.tasks.iter().filter(|l| l.task.needs_owner()).count(),
            ),
        }
    }

    /// Asks for the chats changed since the last one taken in, once a
    /// connection (or a first start) needs it. The reply is taken in by
    /// `chat_reply`, so the window never waits for it.
    fn ask_chats(&mut self) {
        if !self.chats_stale
            || self
                .chat_calls
                .values()
                .any(|c| matches!(c, ChatCall::Since))
            || self.chats_retry.is_some_and(|t| Instant::now() < t)
        {
            return;
        }
        self.chats_stale = false;
        let id = self.send(Call::ChatsSince {
            seq: self.chats_seq,
        });
        self.chat_calls.insert(id, ChatCall::Since);
    }

    /// Takes a changed chat (or a page's worth of them) into the mirror.
    fn take_chat(&mut self, chat: ChatRecord) {
        self.chats_changes += 1;
        self.chats_rev += 1;
        if chat.deleted.is_some() {
            self.deleted_chats.insert(chat.id.clone());
            self.chats.remove(&chat.id);
            self.partials.remove(&chat.id);
            self.chat_notes.remove(&chat.id);
            self.asking.remove(&chat.id);
            return;
        }
        // A late answer or change never brings a deleted chat back.
        if self.deleted_chats.contains(&chat.id) {
            return;
        }
        let mine = self
            .chats
            .entry(chat.id.clone())
            .or_insert_with(|| ChatRecord {
                id: chat.id.clone(),
                ..ChatRecord::default()
            });
        mine.at = mine.at.max(chat.at);
        mine.seq = mine.seq.max(chat.seq);
        if chat.name.is_some() {
            mine.name = chat.name;
        }
        for t in chat.turns {
            // A turn cut away by an edit (here or on another device) goes, and an older copy never returns.
            if t.gone {
                if self.cuts.get(&t.id).is_none_or(|v| *v < t.version) {
                    self.cuts.insert(t.id.clone(), t.version);
                }
                mine.turns
                    .retain(|m| m.id != t.id || m.version >= t.version);
                continue;
            }
            match self.cuts.get(&t.id) {
                Some(v) if *v >= t.version => continue,
                Some(_) => {
                    self.cuts.remove(&t.id);
                }
                None => {}
            }
            match mine.turns.iter_mut().find(|m| m.id == t.id) {
                // A higher version replaces a lower one; the same version
                // is the PC's saved copy of the same turn.
                Some(m) if t.version >= m.version => {
                    // The PC never sends pictures back: the copy made here keeps them.
                    let pictures = std::mem::take(&mut m.pictures);
                    *m = t;
                    if m.pictures.is_empty() {
                        m.pictures = pictures;
                    }
                }
                Some(_) => {}
                None => mine.turns.push(t),
            }
        }
        mine.turns.sort_by(|a, b| (a.at, &a.id).cmp(&(b.at, &b.id)));
        // A saved answer ends the wait that went with it.
        let answered = mine.turns.last().is_some_and(|t| !t.user);
        if answered {
            // A saved answer (also one the PC wrote without the model, a
            // status reply) ends the wait that went with it.
            self.asking.remove(&chat.id);
            if let Some(n) = self.chat_notes.get_mut(&chat.id) {
                n.waiting = None;
            }
        }
    }

    /// The next words of an answer. A piece that is not the next one
    /// means one was missed: the whole answer so far is asked for.
    fn take_piece(&mut self, chat: String, turn: String, n: u32, text: String, thinking: bool) {
        if self.deleted_chats.contains(&chat) {
            return;
        }
        self.chats_changes += 1;
        if let Some(note) = self.chat_notes.get_mut(&chat) {
            note.waiting = None;
        }
        let p = self.partials.entry(chat.clone()).or_default();
        if p.turn_id != turn {
            *p = Partial {
                turn_id: turn.clone(),
                ..Partial::default()
            };
        }
        if p.catching_up {
            return;
        }
        if p.resync {
            p.resync = false;
            p.next = n;
            // The caught-up text may already hold this very piece (it was
            // added before the piece was sent): never twice.
            if !thinking && !text.is_empty() && p.text.ends_with(&text) {
                p.next = n + 1;
                return;
            }
        }
        if n != p.next {
            if n > p.next {
                // Missed some: ask how the answer stands.
                p.catching_up = true;
                let id = self.send(Call::TalkTurn {
                    chat_id: chat.clone(),
                    turn_id: turn,
                });
                self.chat_calls.insert(
                    id,
                    ChatCall::Turn {
                        chat_id: chat,
                        adopt: false,
                        own: false,
                    },
                );
            }
            return;
        }
        // Thinking pieces use up numbers too, but are never the answer.
        p.next = n + 1;
        if thinking {
            if p.text.is_empty() {
                p.thinking = true;
            }
            return;
        }
        p.thinking = false;
        p.text.push_str(&text);
    }

    /// The reply to a call the chat mirror made.
    fn chat_reply(&mut self, call: ChatCall, result: Result<Value, String>) {
        self.chats_changes += 1;
        match call {
            ChatCall::Since => {
                let Ok(page) = result.and_then(|v| {
                    serde_json::from_value::<ChatsPage>(v).map_err(|e| e.to_string())
                }) else {
                    // Tried again, a little later each time.
                    self.chats_stale = true;
                    self.chats_retry = Some(Instant::now() + self.chats_backoff);
                    self.chats_backoff = (self.chats_backoff * 2).min(Duration::from_secs(30));
                    return;
                };
                self.chats_backoff = Duration::from_secs(1);
                self.chats_retry = None;
                let now = ms_now();
                let nothing = page.seq == 0 && page.chats.is_empty();
                for c in page.chats {
                    let id = c.id.clone();
                    self.take_chat(c);
                    // A message waiting for its answer, newly seen here (the
                    // window just started, or the link was cut): ask how
                    // the PC stands with it.
                    if !self.asking.contains(&id)
                        && let Some(t) = self.chats.get(&id).and_then(|c| c.turns.last())
                        && t.user
                        && now - t.at < 15 * 60 * 1000
                    {
                        let turn_id = t.id.clone();
                        let call = self.send(Call::TalkTurn {
                            chat_id: id.clone(),
                            turn_id,
                        });
                        self.chat_calls.insert(
                            call,
                            ChatCall::Turn {
                                chat_id: id,
                                adopt: true,
                                own: false,
                            },
                        );
                    }
                }
                self.chats_seq = self.chats_seq.max(page.seq);
                if page.more {
                    self.chats_stale = true;
                } else if nothing {
                    // Nothing at all yet: the PC's chat history may still be
                    // locked (the wallet answers a moment after start-up).
                    // Asked again soon, so the chats show once it opens.
                    self.chats_stale = true;
                    self.chats_retry = Some(Instant::now() + Duration::from_secs(5));
                }
            }
            ChatCall::Talk { chat_id } => {
                if self.deleted_chats.contains(&chat_id) {
                    return;
                }
                // Only a chat answer the PC is writing stays "asking"; every
                // other reply ends the wait (an error, a refusal, a project
                // task, a status reply, an idea).
                let answering = matches!(
                    result
                        .as_ref()
                        .ok()
                        .and_then(|v| serde_json::from_value::<Filed>(v.clone()).ok()),
                    Some(Filed::Talk { .. })
                );
                if !answering {
                    self.asking.remove(&chat_id);
                }
                let mut keep = false;
                let note = self.chat_notes.entry(chat_id.clone()).or_default();
                match result {
                    Err(e) => note.problem = Some(e),
                    Ok(v) => match serde_json::from_value::<Filed>(v) {
                        Ok(Filed::Talk { waiting, .. }) => note.waiting = waiting,
                        Ok(Filed::NewTask { project, task })
                        | Ok(Filed::SameTask { project, task }) => {
                            note.filed = Some((project, task));
                        }
                        Ok(Filed::Idea { project, .. }) => note.kept = Some(project),
                        Ok(Filed::Status { .. }) => {}
                        Ok(Filed::OnPhone { why }) => {
                            note.problem =
                                Some(format!("Your PC can't answer this one now. {why}"));
                            keep = true;
                        }
                        Ok(Filed::Inbox { .. }) | Err(_) => {}
                    },
                }
                // The PC only keeps a chat it began to answer: one it could
                // not answer is saved there now, so it is still in the list
                // after a restart.
                if keep {
                    self.keep_chat(&chat_id);
                }
            }
            ChatCall::Turn {
                chat_id,
                adopt,
                own,
            } => {
                if self.deleted_chats.contains(&chat_id) {
                    return;
                }
                let state = result.and_then(|v| {
                    serde_json::from_value::<TalkState>(v).map_err(|e| e.to_string())
                });
                let Ok(state) = state else {
                    // No word: not answering, as far as is known.
                    if let Some(p) = self.partials.get_mut(&chat_id) {
                        p.catching_up = false;
                    }
                    self.asking.remove(&chat_id);
                    return;
                };
                match state {
                    TalkState::Answering { text } => {
                        if adopt {
                            // Under way: the chat shows it, and the pieces
                            // that follow add to it.
                            let turn = self.last_user_turn(&chat_id).unwrap_or_default();
                            self.asking.insert(chat_id.clone());
                            let p = self.partials.entry(chat_id.clone()).or_default();
                            p.turn_id = crate::talk::answer_id(&turn);
                        }
                        if let Some(p) = self.partials.get_mut(&chat_id) {
                            p.text = text;
                            p.thinking = false;
                            p.catching_up = false;
                            p.resync = true;
                        }
                    }
                    TalkState::Done { turn } => {
                        self.partials.remove(&chat_id);
                        self.asking.remove(&chat_id);
                        self.take_chat(ChatRecord {
                            id: chat_id,
                            at: turn.at,
                            seq: turn.seq,
                            turns: vec![turn],
                            ..ChatRecord::default()
                        });
                    }
                    TalkState::Failed { why } => {
                        self.partials.remove(&chat_id);
                        self.asking.remove(&chat_id);
                        self.chat_notes.entry(chat_id).or_default().problem = Some(why);
                    }
                    TalkState::Unknown => {
                        self.partials.remove(&chat_id);
                        self.asking.remove(&chat_id);
                        if own {
                            // The service died before it took the message:
                            // it is given back to be sent again.
                            self.give_back(&chat_id);
                            self.chat_notes.entry(chat_id).or_default().problem =
                                Some("Your PC didn't get this message. Send it again.".into());
                        } else if !adopt {
                            self.chat_notes.entry(chat_id).or_default().problem =
                                Some("Your PC lost this answer. Send the message again.".into());
                        }
                    }
                }
            }
        }
    }

    /// Takes the owner's newest message out of a chat and keeps its words
    /// for the draft (`take_unsent`).
    fn give_back(&mut self, chat: &str) {
        let Some(id) = self.last_user_turn(chat) else {
            return;
        };
        let Some(c) = self.chats.get_mut(chat) else {
            return;
        };
        if let Some(t) = c.turns.iter().find(|t| t.id == id) {
            self.unsent.insert(chat.to_string(), t.text.clone());
        }
        c.turns.retain(|t| t.id != id);
        if c.turns.is_empty() {
            self.chats.remove(chat);
        }
        self.chats_rev += 1;
    }

    /// Messages the PC never got, to go back in the message box
    /// (chat id, words).
    pub fn take_unsent(&mut self) -> Vec<(String, String)> {
        self.unsent.drain().collect()
    }

    /// The id of the owner's newest message in a chat.
    fn last_user_turn(&self, chat: &str) -> Option<String> {
        self.chats
            .get(chat)?
            .turns
            .iter()
            .rev()
            .find(|t| t.user)
            .map(|t| t.id.clone())
    }

    /// Sends the owner's message in a chat (a new one when `chat` is
    /// None) and asks the PC to answer it. The message shows at once;
    /// the answer streams in. Returns the chat's id.
    pub fn send_chat(
        &mut self,
        chat: Option<&str>,
        text: &str,
        pictures: Vec<mimir_proto::Picture>,
        effort: Option<&str>,
    ) -> Result<String, String> {
        if self.reconnecting() {
            return Err(NOT_SENT.into());
        }
        let chat_id = chat.map(str::to_string).unwrap_or_else(|| new_chat_id("c"));
        let now = ms_now();
        let user = ChatTurn {
            id: new_chat_id("m"),
            user: true,
            text: text.to_string(),
            sources: vec![],
            ran: None,
            model_words: None,
            at: now,
            version: 1,
            seq: 0,
            gone: false,
            switch_note: None,
            pictures,
            files: vec![],
        };
        let mut turns: Vec<ChatTurn> = self
            .chats
            .get(&chat_id)
            .map(|c| c.turns.clone())
            .unwrap_or_default();
        // Earlier messages' pictures stay here: the PC keeps its own copies.
        for t in &mut turns {
            t.pictures.clear();
        }
        turns.push(user.clone());
        let user_id = user.id.clone();
        // Shown at once; the PC's copy (with its number) replaces it.
        self.take_chat(ChatRecord {
            id: chat_id.clone(),
            at: now,
            turns: vec![user.clone()],
            ..ChatRecord::default()
        });
        self.chat_notes.remove(&chat_id);
        self.partials.remove(&chat_id);
        self.asking.insert(chat_id.clone());
        let id = self.send(Call::Talk {
            chat_id: chat_id.clone(),
            turn_id: user_id.clone(),
            turns,
            lookup: None,
            sources: vec![],
            effort: effort.map(str::to_string),
            ground_rules: None,
            task: None,
            // A message that names a project starts a task there (the PC
            // decides, as for the phone's chat); the chat then shows a card.
            chat_only: false,
        });
        // The link broke as it was written: nothing was sent.
        if self.reconnecting() {
            self.asking.remove(&chat_id);
            if let Some(c) = self.chats.get_mut(&chat_id) {
                c.turns.retain(|t| t.id != user_id);
                if c.turns.is_empty() {
                    self.chats.remove(&chat_id);
                }
            }
            return Err(NOT_SENT.into());
        }
        self.chat_calls.insert(
            id,
            ChatCall::Talk {
                chat_id: chat_id.clone(),
            },
        );
        Ok(chat_id)
    }

    /// Saves a chat's own messages on the PC (without pictures), so a chat
    /// the PC could not answer stays in the list.
    fn keep_chat(&mut self, chat: &str) {
        let Some(c) = self.chats.get(chat) else {
            return;
        };
        let mut turns = c.turns.clone();
        for t in &mut turns {
            t.pictures.clear();
        }
        if turns.is_empty() {
            return;
        }
        self.call(Call::ChatPut {
            chat_id: chat.to_string(),
            name: c.name.clone(),
            turns,
            deleted: false,
        });
    }

    /// Stops the answer being written in a chat.
    pub fn stop_chat(&mut self, chat: &str) {
        // The message being answered: its id (the answer's is `<id>.a`).
        let turn = match self.partials.get(chat) {
            Some(p) => p.turn_id.trim_end_matches(".a").to_string(),
            None => match self
                .chats
                .get(chat)
                .and_then(|c| c.turns.iter().rev().find(|t| t.user))
            {
                Some(t) => t.id.clone(),
                None => return,
            },
        };
        self.call(Call::TalkStop {
            chat_id: chat.to_string(),
            turn_id: turn,
        });
    }

    /// Deletes a chat everywhere (a deleted chat never comes back).
    pub fn delete_chat(&mut self, chat: &str) {
        // An answer being written is stopped; it must not bring the chat back.
        if self.chat_busy(chat) {
            self.stop_chat(chat);
        }
        self.take_chat(ChatRecord {
            id: chat.to_string(),
            deleted: Some(ms_now()),
            ..ChatRecord::default()
        });
        self.call(Call::ChatPut {
            chat_id: chat.to_string(),
            name: None,
            turns: vec![],
            deleted: true,
        });
    }

    /// Gives a chat a name, here and on the PC (and so the phones).
    pub fn rename_chat(&mut self, chat: &str, name: &str) {
        let name = name.trim();
        if name.is_empty() || self.deleted_chats.contains(chat) {
            return;
        }
        if let Some(c) = self.chats.get_mut(chat) {
            c.name = Some(name.to_string());
        }
        self.chats_rev += 1;
        self.chats_changes += 1;
        self.call(Call::ChatPut {
            chat_id: chat.to_string(),
            name: Some(name.to_string()),
            turns: vec![],
            deleted: false,
        });
    }

    /// Asks the last message of a chat again: the new answer takes the old
    /// one's place (it is not added after it).
    pub fn try_again(&mut self, chat: &str) -> Result<(), String> {
        if self.reconnecting() {
            return Err(NOT_SENT.into());
        }
        if self.chat_busy(chat) {
            return Ok(());
        }
        let Some(c) = self.chats.get_mut(chat) else {
            return Ok(());
        };
        let Some(pos) = c.turns.iter().rposition(|t| t.user) else {
            return Ok(());
        };
        let user_id = c.turns[pos].id.clone();
        // The old answer (and anything after the message) is no longer part
        // of what is asked; it leaves the screen until the new one comes.
        let turns: Vec<ChatTurn> = c.turns[..=pos].to_vec();
        c.turns.truncate(pos + 1);
        self.chats_rev += 1;
        self.chat_notes.remove(chat);
        self.partials.remove(chat);
        self.asking.insert(chat.to_string());
        let id = self.send(Call::Talk {
            chat_id: chat.to_string(),
            turn_id: user_id,
            turns,
            lookup: None,
            sources: vec![],
            effort: None,
            ground_rules: None,
            task: None,
            chat_only: false,
        });
        self.chat_calls.insert(
            id,
            ChatCall::Talk {
                chat_id: chat.to_string(),
            },
        );
        Ok(())
    }

    /// Edits the owner's message `turn` in a chat: its words are replaced,
    /// every later turn is cut away (here and, by tombstones, on the PC and
    /// the phones), and the PC answers again from there. The cut turns are
    /// kept for [`undo_edit`](Self::undo_edit).
    pub fn edit_chat(&mut self, chat: &str, turn: &str, text: &str) -> Result<(), String> {
        let text = text.trim();
        if self.reconnecting() {
            return Err(NOT_SENT.into());
        }
        if text.is_empty() || self.chat_busy(chat) {
            return Ok(());
        }
        let Some(c) = self.chats.get_mut(chat) else {
            return Ok(());
        };
        let Some(pos) = c.turns.iter().position(|t| t.id == turn && t.user) else {
            return Ok(());
        };
        let removed: Vec<ChatTurn> = c.turns[pos..].to_vec();
        let mut edited = removed[0].clone();
        edited.text = text.to_string();
        edited.version += 1;
        edited.seq = 0;
        edited.sources.clear();
        let mut put = vec![edited.clone()];
        for t in &removed[1..] {
            put.push(ChatTurn {
                id: t.id.clone(),
                user: t.user,
                text: String::new(),
                sources: vec![],
                ran: None,
                model_words: None,
                at: t.at,
                version: t.version + 1,
                seq: 0,
                gone: true,
                switch_note: None,
                pictures: vec![],
                files: vec![],
            });
        }
        // The old answer to the message is cut too: its new one takes a higher version.
        let answer = crate::talk::answer_id(turn);
        if !removed.iter().any(|t| t.id == answer) {
            // Not here (never saved, or not arrived): a tombstone still covers it.
            put.push(ChatTurn {
                id: answer.clone(),
                user: false,
                text: String::new(),
                sources: vec![],
                ran: None,
                model_words: None,
                at: edited.at,
                version: 2,
                seq: 0,
                gone: true,
                switch_note: None,
                pictures: vec![],
                files: vec![],
            });
        }
        for t in put.iter().filter(|t| t.gone) {
            self.cuts.insert(t.id.clone(), t.version);
        }
        let mut turns: Vec<ChatTurn> = c.turns[..pos].to_vec();
        turns.push(edited.clone());
        c.turns.truncate(pos);
        c.turns.push(edited);
        self.last_cut = Some((chat.to_string(), removed));
        self.chats_rev += 1;
        self.chat_notes.remove(chat);
        self.partials.remove(chat);
        self.asking.insert(chat.to_string());
        self.call(Call::ChatPut {
            chat_id: chat.to_string(),
            name: None,
            turns: put,
            deleted: false,
        });
        let user_id = turn.to_string();
        let id = self.send(Call::Talk {
            chat_id: chat.to_string(),
            turn_id: user_id,
            turns,
            lookup: None,
            sources: vec![],
            effort: None,
            ground_rules: None,
            task: None,
            chat_only: false,
        });
        self.chat_calls.insert(
            id,
            ChatCall::Talk {
                chat_id: chat.to_string(),
            },
        );
        Ok(())
    }

    /// Whether the last edit can still be undone in this chat.
    pub fn can_undo_edit(&self, chat: &str) -> bool {
        self.last_cut.as_ref().is_some_and(|(c, _)| c == chat)
    }

    /// Puts back what the last edit cut away (and the message's old words).
    pub fn undo_edit(&mut self, chat: &str) {
        let Some((c, removed)) = self.last_cut.take() else {
            return;
        };
        if c != chat {
            self.last_cut = Some((c, removed));
            return;
        }
        if self.chat_busy(chat) {
            self.stop_chat(chat);
        }
        let Some(mine) = self.chats.get_mut(chat) else {
            return;
        };
        let mut restored = vec![];
        for t in removed {
            let mut r = t.clone();
            // Higher than the tombstone and than any new answer that arrived meanwhile.
            let now = mine
                .turns
                .iter()
                .find(|m| m.id == t.id)
                .map_or(0, |m| m.version);
            r.version = (t.version + 3).max(now + 1);
            r.seq = 0;
            self.cuts.remove(&t.id);
            mine.turns.retain(|m| m.id != t.id);
            mine.turns.push(r.clone());
            restored.push(r);
        }
        let answer = crate::talk::answer_id(&restored[0].id);
        self.cuts.remove(&answer);
        mine.turns.sort_by(|a, b| (a.at, &a.id).cmp(&(b.at, &b.id)));
        self.chats_rev += 1;
        self.chat_notes.remove(chat);
        self.call(Call::ChatPut {
            chat_id: chat.to_string(),
            name: None,
            turns: restored,
            deleted: false,
        });
    }

    /// Whether the PC is writing an answer in this chat (or is about to:
    /// a message was sent and nothing has come back yet).
    pub fn chat_busy(&self, chat: &str) -> bool {
        self.partials.contains_key(chat) || self.asking.contains(chat)
    }

    /// Disconnects from the service (the service goes on without the window).
    pub fn shutdown(&mut self) {
        // The service goes on without the window.
        if let Conn::Socket { stream, .. } = &mut self.conn
            && let Some(s) = stream.take()
        {
            let _ = s.shutdown(std::net::Shutdown::Both);
        }
    }

    // What the window does, as calls. The names are the `Runner`'s.

    /// A problem to show (the window's own).
    pub fn problem(&mut self, msg: &str) {
        if !self.problems.iter().any(|p| p == msg) {
            self.problems.push(msg.to_string());
        }
    }

    /// The owner dismissed problem `i`.
    pub fn dismiss_problem(&mut self, i: usize) {
        if i < self.problems.len() {
            let p = self.problems.remove(i);
            if self.from_service.remove(&p) {
                self.call(Call::DismissProblem { text: p });
            }
        }
    }

    /// Starts a task; its id, or why not.
    #[allow(clippy::too_many_arguments)]
    pub fn new_task(
        &mut self,
        project: &str,
        text: &str,
        pictures: &[Picture],
        mode: Mode,
        model: Option<String>,
        effort: Option<String>,
        separate_copy: bool,
    ) -> Result<String, String> {
        let v = self.call_wait(Call::NewTask {
            ground_rules: None,
            project: project.to_string(),
            text: text.to_string(),
            pictures: pictures.to_vec(),
            files: vec![],
            mode,
            model,
            effort,
            separate_copy,
            msg_id: None,
        })?;
        v.as_str()
            .map(str::to_string)
            .ok_or_else(|| "The task did not start.".into())
    }

    pub fn send_with(&mut self, id: &str, text: &str, pictures: &[Picture]) {
        self.call(Call::Send {
            task: id.into(),
            text: text.into(),
            pictures: pictures.to_vec(),
            files: vec![],
            msg_id: None,
        });
    }

    pub fn queue(&mut self, id: &str, text: &str, files: Vec<PathBuf>) {
        self.call(Call::Queue {
            task: id.into(),
            text: text.into(),
            files,
        });
    }

    /// Takes the waiting message back.
    pub fn unqueue(&mut self, id: &str) -> Option<QueuedMessage> {
        self.call_wait(Call::Unqueue { task: id.into() })
            .ok()
            .and_then(|v| serde_json::from_value(v).ok())
    }

    pub fn send_queued(&mut self, id: &str) {
        self.call(Call::SendQueued { task: id.into() });
    }

    pub fn pause(&mut self, id: &str) {
        self.call(Call::Pause { task: id.into() });
    }

    pub fn stop(&mut self, id: &str) {
        self.call(Call::Stop { task: id.into() });
    }

    pub fn resume(&mut self, id: &str) {
        self.call(Call::Continue { task: id.into() });
    }

    pub fn answer(&mut self, id: &str, answer: Answer) {
        // The request leaves the screen at once, as the service will tell.
        // The answer names it, so it can't land on a request shown later.
        let ask_id = self
            .view(id)
            .and_then(|v| v.asks.pop_front())
            .map(|a| crate::needs::permission_id(id, &a.request));
        self.call(Call::Answer {
            ask_id,
            task: id.into(),
            choice: match answer {
                Answer::Allow => Choice::Allow,
                Answer::Always => Choice::AlwaysProject,
                Answer::No => Choice::DontAllow,
                Answer::Halted => Choice::Halted,
            },
        });
    }

    pub fn set_mode(&mut self, id: &str, mode: Mode) {
        self.call(Call::SetMode {
            task: id.into(),
            mode,
        });
    }

    pub fn set_model(&mut self, id: &str, model: Option<String>) {
        self.call(Call::SetModel {
            task: id.into(),
            model,
        });
    }

    pub fn set_effort(&mut self, id: &str, effort: Option<String>) {
        self.call(Call::SetEffort {
            task: id.into(),
            effort,
        });
    }

    pub fn approve_plan(&mut self, id: &str, mode: Mode) {
        self.call(Call::ApprovePlan {
            task: id.into(),
            mode,
        });
    }

    pub fn rename(&mut self, id: &str, title: &str) {
        self.call(Call::Rename {
            task: id.into(),
            title: title.into(),
        });
    }

    pub fn set_archived(&mut self, id: &str, archived: bool) {
        self.call(Call::Archive {
            task: id.into(),
            archived,
        });
    }

    /// Moves a task, conversation and all, to another project folder; why
    /// not, if not.
    pub fn move_to(&mut self, id: &str, project: &str) -> Result<(), String> {
        self.call_wait(Call::MoveTo {
            task: id.into(),
            project: project.into(),
        })
        .map(|_| ())
    }

    /// The owner confirms where Mímir filed a chat.
    pub fn confirm_placement(&mut self, id: &str) {
        self.call(Call::ConfirmPlacement { task: id.into() });
    }

    /// Moves every task of one project into another, which takes its place
    /// on the list; how many moved, or why nothing did.
    pub fn merge_project(&mut self, from: &str, into: &str) -> Result<usize, String> {
        self.call_wait(Call::MergeProject {
            from: from.into(),
            into: into.into(),
        })
        .map(|v| v.as_u64().unwrap_or(0) as usize)
    }

    /// Deletes a task from Mímir (files stay); why not, if not.
    pub fn remove(&mut self, id: &str) -> Result<(), String> {
        self.call_wait(Call::RemoveTask { task: id.into() })
            .map(|_| ())
    }

    pub fn note(&mut self, id: &str, text: &str) {
        self.call(Call::Note {
            task: id.into(),
            text: text.into(),
        });
    }

    pub fn dismiss_question(&mut self, id: &str) {
        self.call(Call::DismissQuestion { task: id.into() });
    }

    /// Past Claude Code sessions in a project that aren't tasks here yet.
    pub fn past_sessions(&mut self, project: &str) -> Vec<crate::sessions::PastSession> {
        self.call_wait(Call::PastSessions {
            project: project.into(),
        })
        .ok()
        .and_then(|v| serde_json::from_value(v).ok())
        .unwrap_or_default()
    }

    /// Opens a past session as a task; its id.
    pub fn open_past(
        &mut self,
        project: &str,
        s: &crate::sessions::PastSession,
    ) -> Result<String, String> {
        let v = self.call_wait(Call::ImportPast {
            project: project.into(),
            session: serde_json::to_value(s).map_err(|e| e.to_string())?,
        })?;
        v.as_str()
            .map(str::to_string)
            .ok_or_else(|| "That session could not be opened.".into())
    }

    pub fn bring_in(&mut self, id: &str) -> Result<(), String> {
        self.call_wait(Call::BringIn { task: id.into() })
            .map(|_| ())
    }

    pub fn remove_copy(&mut self, id: &str) -> Result<(), String> {
        self.call_wait(Call::RemoveCopy { task: id.into() })
            .map(|_| ())
    }

    /// Whether the owner's message at `entry` has a snapshot to undo to.
    pub fn can_undo(&self, id: &str, entry: usize) -> bool {
        self.tasks
            .iter()
            .find(|v| v.task.id == id)
            .is_some_and(|v| v.task.checkpoints.iter().any(|(at, _)| *at == entry))
    }

    pub fn undo_preview(
        &mut self,
        id: &str,
        entry: usize,
    ) -> Result<Vec<crate::checkpoints::Change>, String> {
        let v = self.call_wait(Call::UndoPreview {
            task: id.into(),
            entry,
        })?;
        serde_json::from_value(v).map_err(|e| e.to_string())
    }

    pub fn redo_preview(&mut self, id: &str) -> Result<Vec<crate::checkpoints::Change>, String> {
        let v = self.call_wait(Call::RedoPreview { task: id.into() })?;
        serde_json::from_value(v).map_err(|e| e.to_string())
    }

    pub fn undo(&mut self, id: &str, entry: usize) -> Result<(), String> {
        self.call_wait(Call::Undo {
            task: id.into(),
            entry,
        })
        .map(|_| ())
    }

    pub fn redo(&mut self, id: &str) -> Result<(), String> {
        self.call_wait(Call::Redo { task: id.into() }).map(|_| ())
    }

    /// The service's settings keys as the window has them now.
    pub fn set_settings(&mut self, settings: Value) {
        if settings != self.settings {
            self.settings = settings.clone();
            let _ = self.call_wait(Call::SetSettings { settings });
        }
    }

    /// The project whose git facts the window shows.
    pub fn watch_git(&mut self, project: Option<String>) {
        self.call(Call::WatchGit { project });
    }

    pub fn refresh_git(&mut self, project: &str) {
        self.call(Call::RefreshGit {
            project: project.into(),
        });
    }

    pub fn focus(&mut self, focused: bool) {
        self.call(Call::Focus { focused });
    }
}

/// A command run on the host (from inside a distrobox too), reaching the
/// user's systemd through the real runtime folder.
fn host_command() -> std::process::Command {
    use std::process::Command;
    let uid = unsafe { libc::getuid() };
    let in_box = std::path::Path::new("/run/.containerenv").exists()
        || std::env::var_os("CONTAINER_ID").is_some();
    let mut cmd = if in_box {
        let mut c = Command::new("distrobox-host-exec");
        c.arg("env");
        c
    } else {
        Command::new("env")
    };
    crate::process::without_systemd(&mut cmd);
    cmd.arg(format!("XDG_RUNTIME_DIR=/run/user/{uid}"))
        .arg(format!(
            "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/{uid}/bus"
        ));
    cmd
}

/// Starts the service as its own process in a control group it may manage
/// (`systemd-run --scope -p Delegate=yes`), when systemd's socket unit
/// isn't there to start it (not installed yet, or a check's home). From
/// inside a distrobox it starts on the host, where the groups are. The
/// process returned ends when the service does.
pub fn start_service(program: &std::path::Path) -> std::io::Result<std::process::Child> {
    use std::process::Stdio;
    if crate::flatpak::active() {
        // No systemd in a Flatpak: the background part is a process of the
        // sandbox, and the Background portal starts it with the session.
        let mut cmd = std::process::Command::new(program);
        cmd.arg("service");
        std::os::unix::process::CommandExt::process_group(&mut cmd, 0);
        return cmd
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn();
    }
    let mut cmd = host_command();
    cmd.args([
        "systemd-run",
        "--user",
        "--scope",
        "-p",
        "Delegate=yes",
        "--quiet",
        "--collect",
        "--",
        "env",
    ]);
    // The service gets the window's environment (its folders above all).
    for (k, v) in std::env::vars() {
        if matches!(k.as_str(), "CONTAINER_ID" | "container")
            || crate::process::SYSTEMD_ONLY.contains(&k.as_str())
            || k.starts_with("DISTROBOX_")
            || k.contains('=')
        {
            continue;
        }
        cmd.arg(format!("{k}={v}"));
    }
    cmd.arg(program).arg("service");
    std::os::unix::process::CommandExt::process_group(&mut cmd, 0);
    cmd.stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
}

/// Why the service last ended early, from Mímir's log (written
/// after `since`, seconds since 1970).
fn service_ended_since(since: i64) -> Option<String> {
    crate::log::job("service")
        .into_iter()
        .rev()
        .find(|v| v["what"] == "ended" && v["time"].as_i64().is_some_and(|t| t >= since))
        .and_then(|v| v["detail"].as_str().map(str::to_string))
}

/// A window in a Flatpak that the service refused (that service isn't the one
/// that started it): a new launcher takes over and this window ends.
fn relaunch_if_refused() {
    use std::sync::atomic::Ordering;
    if !crate::sockets::REFUSED.load(Ordering::Relaxed) {
        return;
    }
    relaunch();
}

/// A window that holds a capability never starts a service itself (the
/// capability would not be the new service's): a launcher does, and this
/// window ends.
fn relaunch_if_holding() {
    if crate::sockets::cap_required() && crate::sockets::held().is_some() {
        relaunch();
    }
}

fn relaunch() {
    let mut cmd = std::process::Command::new(crate::paths::own_program());
    cmd.env_remove("MIMIR_CAP_FD");
    std::os::unix::process::CommandExt::process_group(&mut cmd, 0);
    if cmd
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .is_ok()
    {
        std::process::exit(0);
    }
}

/// A bare `mimir` in a Flatpak: asks the running service to open the window
/// (the service starts it, so only a window it started holds the capability
/// the control socket needs). When no service runs, this process becomes it,
/// and opens the window. Returns the exit code.
pub fn open_window_through_service(version: &str) -> i32 {
    use crate::sockets::{Opening, read_msg, write_msg};
    let path = crate::sockets::control_socket();
    let began = Instant::now();
    loop {
        if let Ok(mut s) = std::os::unix::net::UnixStream::connect(&path) {
            let _ = s.set_read_timeout(Some(Duration::from_secs(10)));
            if write_msg(&mut s, &Opening::OpenWindow(crate::sockets::display_env())).is_ok() {
                match read_msg::<String>(&mut s) {
                    Ok(Some(r)) if r == "ok" => return 0,
                    Ok(Some(r)) => {
                        eprintln!("Mímir: {r}");
                        return 1;
                    }
                    _ => {}
                }
            }
        } else {
            let t = Instant::now();
            let code = crate::service::run_main_opening(version, true);
            // It ran as the service for a while and then stopped.
            if t.elapsed() > Duration::from_secs(5) {
                return code;
            }
        }
        if began.elapsed() > Duration::from_secs(20) {
            eprintln!("Mímir: the background part didn't start.");
            return 1;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

/// The window's connection: to the running service, or to one it starts.
/// Whichever runs, the window connects to it.
pub fn connect_or_start(
    program: &std::path::Path,
    version: &str,
    wake: Wake,
) -> std::io::Result<Station> {
    let path = crate::sockets::control_socket();
    let began = Instant::now();
    let since = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64 - 1)
        .unwrap_or(0);
    let until = began + Duration::from_secs(15);
    // The process the window started.
    let mut started: Option<std::process::Child> = None;
    let mut ended: Option<Instant> = None;
    loop {
        let err = match Station::connect(&path, version, wake.clone()) {
            Ok(mut s) => {
                s.program = Some(program.to_path_buf());
                if let Some(mut c) = started {
                    // It ends with the service; nobody waits for it later.
                    std::thread::spawn(move || {
                        let _ = c.wait();
                    });
                }
                return Ok(s);
            }
            Err(e) => e,
        };
        relaunch_if_refused();
        let now = Instant::now();
        // The service the window started ended (another may have won the
        // race: look a little longer), or time is up.
        let gave_up = ended.is_some_and(|t| now - t > Duration::from_millis(1500));
        if now > until || gave_up {
            if let Some(mut c) = started {
                let _ = c.try_wait();
            }
            return Err(match service_ended_since(since) {
                Some(why) => std::io::Error::other(why),
                None => err,
            });
        }
        if started.is_none() {
            relaunch_if_holding();
            // Nobody else brought it up (the desktop may have started it
            // with the session): the window starts it.
            started = Some(start_service(program)?);
        } else if ended.is_none()
            && let Some(c) = started.as_mut()
            && c.try_wait().ok().flatten().is_some()
        {
            ended = Some(now);
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

/// What the window says when a message could not be sent.
pub const NOT_SENT: &str = "Not sent: Mímir isn't running on your PC. Your message is kept.";

fn ms_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// A new chat or message id, different from every other made on any
/// device (the time, the process and a count).
fn new_chat_id(kind: &str) -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static N: AtomicU64 = AtomicU64::new(0);
    let n = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!(
        "{kind}{:x}-{:x}-{:x}",
        n,
        std::process::id(),
        N.fetch_add(1, Ordering::Relaxed)
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::task::Status;

    fn view(status: Status, truth: Option<TaskState>) -> TaskView {
        let mut task = Task::new("t".into(), "A task".into(), "/p".into(), 0);
        task.status = status;
        TaskView {
            task,
            asks: VecDeque::new(),
            running: false,
            busy: false,
            screen: vec![],
            queued: None,
            truth_state: truth,
            doing: None,
            turn_unix: None,
            news: Instant::now(),
        }
    }

    /// D1: the live line counts the time, and says plainly when nothing came for a while.
    #[test]
    fn the_live_line_counts_and_admits_silence() {
        let mut v = view(Status::Working, None);
        assert_eq!(v.live_line(100), None, "not working: nothing to say");
        v.doing = Some("Reading main.rs".into());
        v.turn_unix = Some(90);
        assert_eq!(v.live_line(100).as_deref(), Some("Reading main.rs · 10 s"));
        // A long think changes nothing for a while: not yet "no news".
        v.news = Instant::now() - std::time::Duration::from_secs(25);
        assert_eq!(v.live_line(100).as_deref(), Some("Reading main.rs · 10 s"));
        v.news = Instant::now() - std::time::Duration::from_secs(65);
        assert!(
            v.live_line(100)
                .unwrap()
                .starts_with("Still working; no news")
        );
    }

    /// P10a: every surface shows the one state the PC's truth gives; only
    /// until it has told does the window work one out itself.
    #[test]
    fn the_shown_state_is_the_truths_when_it_has_told() {
        assert_eq!(
            view(Status::Done, Some(TaskState::Working)).shown_state(),
            TaskState::Working
        );
        assert_eq!(
            view(Status::Working, Some(TaskState::LiveElsewhere)).shown_state(),
            TaskState::LiveElsewhere
        );
        assert_eq!(
            view(Status::Working, None).shown_state(),
            TaskState::Working
        );
        assert_eq!(view(Status::Done, None).shown_state(), TaskState::Done);
        assert_eq!(view(Status::Paused, None).shown_state(), TaskState::Stopped);
    }

    /// A mirror with no service behind it (its calls go nowhere).
    fn bare() -> Station {
        let (_tx, rx) = channel();
        Station::blank(
            Conn::Socket {
                path: PathBuf::new(),
                stream: None,
                rx,
                wake: std::sync::Arc::new(|| {}),
                tried: Instant::now(),
            },
            "test".into(),
        )
    }

    fn turn(id: &str, user: bool, text: &str, at: i64) -> ChatTurn {
        ChatTurn {
            id: id.into(),
            user,
            text: text.into(),
            sources: vec![],
            ran: None,
            model_words: None,
            at,
            version: 1,
            seq: 0,
            gone: false,
            switch_note: None,
            pictures: vec![],
            files: vec![],
        }
    }

    fn record(id: &str, seq: u64, turns: Vec<ChatTurn>) -> ChatRecord {
        ChatRecord {
            id: id.into(),
            at: turns.last().map(|t| t.at).unwrap_or(0),
            seq,
            turns,
            ..ChatRecord::default()
        }
    }

    /// A station whose socket is open (the other end is kept by the caller).
    fn connected() -> (Station, std::os::unix::net::UnixStream) {
        let mut s = bare();
        let (a, b) = std::os::unix::net::UnixStream::pair().unwrap();
        let Conn::Socket { stream, .. } = &mut s.conn;
        *stream = Some(a);
        (s, b)
    }

    fn with_message(s: &mut Station) {
        s.take_chat(record("c", 1, vec![turn("m", true, "Hi", ms_now())]));
        s.asking.insert("c".into());
    }

    /// F1: only a chat answer the PC is writing keeps a chat "answering";
    /// every other reply (a refusal, a task, an idea, an error) ends it.
    #[test]
    fn every_reply_but_a_chat_answer_ends_the_wait() {
        let filed = |f: Filed| serde_json::to_value(f).unwrap();
        for reply in [
            Ok(filed(Filed::OnPhone { why: "x".into() })),
            Ok(filed(Filed::NewTask {
                project: "/p".into(),
                task: "t".into(),
            })),
            Ok(filed(Filed::SameTask {
                project: "/p".into(),
                task: "t".into(),
            })),
            Ok(filed(Filed::Idea {
                project: "/p".into(),
                idea: "i".into(),
            })),
            Ok(filed(Filed::Status { reply: vec![] })),
            Ok(filed(Filed::Inbox {
                inbox_id: "i".into(),
                candidates: vec![],
            })),
            Err("nope".to_string()),
        ] {
            let mut s = bare();
            with_message(&mut s);
            s.chat_reply(
                ChatCall::Talk {
                    chat_id: "c".into(),
                },
                reply.clone(),
            );
            assert!(!s.chat_busy("c"), "still answering after {reply:?}");
        }
        let mut s = bare();
        with_message(&mut s);
        s.chat_reply(
            ChatCall::Talk {
                chat_id: "c".into(),
            },
            Ok(filed(Filed::Talk {
                answer: "m.a".into(),
                ran: mimir_proto::Ran::Pc,
                model_words: "x".into(),
                waiting: None,
                files_read: vec![],
            })),
        );
        assert!(s.chat_busy("c"));
    }

    /// A chat the PC could not answer (no model fits) is saved on the PC, so it
    /// is still in the list after a restart.
    #[test]
    fn a_chat_the_pc_could_not_answer_is_saved_there() {
        let (mut s, other) = connected();
        with_message(&mut s);
        other
            .set_read_timeout(Some(std::time::Duration::from_secs(2)))
            .unwrap();
        s.chat_reply(
            ChatCall::Talk {
                chat_id: "c".into(),
            },
            Ok(serde_json::to_value(Filed::OnPhone { why: "x".into() }).unwrap()),
        );
        let mut other = other;
        let frame = crate::sockets::read_frame(&mut other).unwrap().unwrap();
        let line = String::from_utf8_lossy(&frame).to_string();
        assert!(line.contains("\"chat_id\":\"c\""), "{line}");
        assert!(
            line.contains("Hi") && line.contains("\"deleted\":false"),
            "{line}"
        );
    }

    /// F2: a live change never moves the position `ChatsSince` asks from;
    /// only its own pages do.
    #[test]
    fn only_a_chats_page_moves_the_catch_up_position() {
        let mut s = bare();
        s.take_chat(record("y", 9, vec![turn("y1", true, "Hi", 1)]));
        assert_eq!(s.chats_seq, 0, "a live change moved the position");
        s.chat_reply(
            ChatCall::Since,
            Ok(serde_json::to_value(ChatsPage {
                seq: 5,
                chats: vec![record("x", 4, vec![turn("x1", true, "Yo", 1)])],
                more: false,
            })
            .unwrap()),
        );
        assert_eq!(s.chats_seq, 5);
        assert!(s.chats.contains_key("x") && s.chats.contains_key("y"));
    }

    /// A first answer with nothing in it (the PC's history still locked) is
    /// asked for again, so the chats show once it opens.
    #[test]
    fn an_empty_first_page_is_asked_for_again() {
        let mut s = bare();
        s.chats_stale = false;
        let empty = || {
            Ok(serde_json::to_value(ChatsPage {
                seq: 0,
                chats: vec![],
                more: false,
            })
            .unwrap())
        };
        s.chat_reply(ChatCall::Since, empty());
        assert!(s.chats_stale && s.chats_retry.is_some());
        s.chats_stale = false;
        s.chat_reply(
            ChatCall::Since,
            Ok(serde_json::to_value(ChatsPage {
                seq: 3,
                chats: vec![record("x", 4, vec![turn("x1", true, "Yo", 1)])],
                more: false,
            })
            .unwrap()),
        );
        assert!(!s.chats_stale && s.chats_retry.is_none(), "kept asking");
        assert!(s.chats.contains_key("x"));
    }

    /// F3: a chat deleted while it is answered is not brought back by the
    /// late answer or its pieces.
    #[test]
    fn a_deleted_chat_stays_deleted_when_its_answer_arrives() {
        let mut s = bare();
        with_message(&mut s);
        s.take_piece("c".into(), "m.a".into(), 0, "Hel".into(), false);
        let calls = s.unwatched.len();
        s.delete_chat("c");
        // Stop and the deletion both went out.
        assert_eq!(s.unwatched.len(), calls + 2, "no TalkStop on delete");
        assert!(!s.chats.contains_key("c"));
        s.take_piece("c".into(), "m.a".into(), 1, "lo".into(), false);
        s.take_event_for_test(Event::TalkDone {
            chat_id: "c".into(),
            turn_id: "m.a".into(),
            result: Ok(turn("m.a", false, "Hello", ms_now())),
        });
        assert!(!s.chats.contains_key("c"), "the chat came back");
        assert!(!s.partials.contains_key("c") && !s.chat_busy("c"));
        assert!(!s.chat_notes.contains_key("c"));
    }

    /// Editing an earlier message: its words change, everything after it is
    /// cut away (an old copy never brings it back), and Undo puts it all back.
    #[test]
    fn editing_a_message_cuts_the_rest_away_and_undo_puts_it_back() {
        let (mut s, _pc) = connected();
        s.take_chat(record(
            "c",
            1,
            vec![
                turn("m1", true, "One", 1),
                turn("m1.a", false, "Answer one", 2),
                turn("m2", true, "Two", 3),
                turn("m2.a", false, "Answer two", 4),
            ],
        ));
        let calls = s.unwatched.len();
        s.edit_chat("c", "m1", "One, said better").unwrap();
        assert_eq!(s.unwatched.len(), calls + 1, "the cut");
        assert_eq!(s.chat_calls.len(), 1, "the question");
        let words = |s: &Station| -> Vec<String> {
            s.chats["c"].turns.iter().map(|t| t.text.clone()).collect()
        };
        assert_eq!(words(&s), ["One, said better"]);
        assert_eq!(s.chats["c"].turns[0].version, 2);
        // A late, older copy of a cut turn from the PC does not bring it back.
        s.take_chat(record("c", 2, vec![turn("m2", true, "Two", 3)]));
        assert_eq!(words(&s), ["One, said better"]);
        // The tombstones themselves change nothing more.
        let mut gone = turn("m2", true, "", 3);
        gone.version = 2;
        gone.gone = true;
        s.take_chat(record("c", 3, vec![gone]));
        assert_eq!(words(&s), ["One, said better"]);
        // Undo.
        s.asking.clear();
        assert!(s.can_undo_edit("c"));
        s.undo_edit("c");
        assert_eq!(words(&s), ["One", "Answer one", "Two", "Answer two"]);
        assert!(s.chats["c"].turns.iter().all(|t| t.version >= 4));
        assert!(!s.can_undo_edit("c"));
    }

    /// An edit is refused while an answer is being written.
    #[test]
    fn a_message_cannot_be_edited_while_the_answer_is_written() {
        let (mut s, _pc) = connected();
        with_message(&mut s);
        let calls = s.unwatched.len();
        s.edit_chat("c", "m", "Changed").unwrap();
        assert_eq!(s.unwatched.len(), calls);
        assert_eq!(s.chats["c"].turns[0].text, "Hi");
    }

    /// F4: thinking pieces use up numbers without a catch-up, and the
    /// catch-up never repeats the piece it already holds.
    #[test]
    fn thinking_pieces_need_no_catch_up_and_a_catch_up_repeats_nothing() {
        let mut s = bare();
        with_message(&mut s);
        s.take_piece("c".into(), "m.a".into(), 0, "hmm".into(), true);
        s.take_piece("c".into(), "m.a".into(), 1, "Hi ".into(), false);
        s.take_piece("c".into(), "m.a".into(), 2, "there".into(), false);
        assert_eq!(s.partials["c"].text, "Hi there");
        assert!(s.chat_calls.is_empty(), "a catch-up was asked for");
        // A piece is missed; the answer so far already holds piece 5.
        s.take_piece("c".into(), "m.a".into(), 5, "x".into(), false);
        assert!(s.partials["c"].catching_up);
        s.chat_reply(
            ChatCall::Turn {
                chat_id: "c".into(),
                adopt: false,
                own: false,
            },
            Ok(serde_json::to_value(TalkState::Answering {
                text: "Hi there, friend".into(),
            })
            .unwrap()),
        );
        s.take_piece("c".into(), "m.a".into(), 5, " friend".into(), false);
        s.take_piece("c".into(), "m.a".into(), 6, "!".into(), false);
        assert_eq!(s.partials["c"].text, "Hi there, friend!");
    }

    /// F5: with no service to take it, a message is not sent, nothing shows
    /// as sent, and the caller is told (to keep the draft).
    #[test]
    fn a_message_with_no_service_is_not_sent() {
        let mut s = bare();
        assert_eq!(
            s.send_chat(None, "Hello", vec![], None),
            Err(NOT_SENT.to_string())
        );
        assert!(s.chats.is_empty() && s.asking.is_empty() && s.chat_calls.is_empty());
    }

    /// F6: after a reconnect an answer found under way keeps the chat
    /// "answering", and one the PC lost says so.
    #[test]
    fn a_reconnect_keeps_a_chat_answering_while_the_pc_works() {
        let mut s = bare();
        s.take_chat(record("c", 1, vec![turn("m", true, "Hi", ms_now())]));
        s.chat_reply(
            ChatCall::Turn {
                chat_id: "c".into(),
                adopt: true,
                own: false,
            },
            Ok(serde_json::to_value(TalkState::Answering { text: "So".into() }).unwrap()),
        );
        assert!(s.chat_busy("c"));
        assert_eq!(s.partials["c"].text, "So");
        s.chat_reply(
            ChatCall::Turn {
                chat_id: "c".into(),
                adopt: false,
                own: false,
            },
            Ok(serde_json::to_value(TalkState::Unknown).unwrap()),
        );
        assert!(!s.chat_busy("c"));
        assert!(s.chat_notes["c"].problem.is_some());
    }

    /// F11: a failed load of the chats is tried again, later each time.
    #[test]
    fn a_failed_chat_load_is_tried_again_later() {
        let mut s = bare();
        s.chat_reply(ChatCall::Since, Err("no".into()));
        assert!(s.chats_stale);
        s.ask_chats();
        assert!(s.chat_calls.is_empty(), "asked again at once");
        s.chats_retry = Some(Instant::now() - Duration::from_secs(1));
        s.ask_chats();
        assert!(s.chat_calls.values().any(|c| matches!(c, ChatCall::Since)));
    }

    /// F9: the foot's counts are the truth's, not the window's own.
    #[test]
    fn the_counts_are_the_truths_when_it_has_told() {
        let mut s = bare();
        s.tasks.push(view(Status::Done, None));
        assert_eq!(s.counts(), (0, 0));
        s.truth = Some(mimir_proto::Truth {
            counts: mimir_proto::TruthCounts {
                working: 3,
                needs_you: 2,
            },
            ..mimir_proto::Truth::default()
        });
        assert_eq!(s.counts(), (3, 2));
    }

    /// F5: the service died right after a write that never reached the
    /// model: the re-ask finds the PC knows nothing of the message, so it
    /// is given back for the box, with a word, never lost silently.
    #[test]
    fn a_message_the_pc_never_got_is_given_back() {
        let mut s = bare();
        with_message(&mut s);
        s.chat_reply(
            ChatCall::Turn {
                chat_id: "c".into(),
                adopt: true,
                own: true,
            },
            Ok(serde_json::to_value(TalkState::Unknown).unwrap()),
        );
        assert!(!s.chat_busy("c"));
        assert_eq!(
            s.chat_notes["c"].problem.as_deref(),
            Some("Your PC didn't get this message. Send it again.")
        );
        assert_eq!(s.take_unsent(), vec![("c".to_string(), "Hi".to_string())]);
        assert!(!s.chats.contains_key("c"), "the unsent message still shows");
        // A chat only found in a page: nothing said, nothing given back.
        let mut s = bare();
        s.take_chat(record("d", 1, vec![turn("m", true, "Hi", ms_now())]));
        s.chat_reply(
            ChatCall::Turn {
                chat_id: "d".into(),
                adopt: true,
                own: false,
            },
            Ok(serde_json::to_value(TalkState::Unknown).unwrap()),
        );
        assert!(s.take_unsent().is_empty() && !s.chat_notes.contains_key("d"));
    }

    /// F7: the words the window recognises a paid chat line by are the ones
    /// the service writes.
    #[test]
    fn the_paid_chat_line_ends_as_the_window_expects() {
        let src = include_str!("service.rs");
        assert!(
            src.contains("\"Chats run online (your plan)\""),
            "the service's paid chat line changed: the window's check \
             (view_chat.rs, PAID_LINE_END) must follow"
        );
    }
}
