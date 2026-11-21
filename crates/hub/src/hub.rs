//! The hub: one thread that owns the chat world and handles one message at
//! a time, each within the watchdog's budget. It never sleeps on a timer:
//! it waits for the next message or the next deadline it has (none when
//! idle, so an idle hub doesn't wake at all; PCS-16, PCR-20). Slow work goes
//! to the bounded worker pools and comes back as a message.

use crate::client::{ClientQueue, Pushed};
use crate::oneshot::{Pending, Reply, oneshot};
use crate::workers::{Pool, Sizes};
use mimir_proto::{ChatPutDone, ChatRecord, ChatTurn, ChatsPage};
use mimir_store::{TalkStore, Unlock};
use std::collections::HashMap;
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender, channel};
use std::time::{Duration, Instant};

/// The longest an idle hub waits before it looks again (only so a hub
/// whose senders are all gone notices; any message wakes it at once).
pub const IDLE_WAIT: Duration = Duration::from_secs(3600);

/// A world the hub runs.
pub trait World {
    type Msg: Send + 'static;
    /// The message's name (for the watchdog).
    fn name(msg: &Self::Msg) -> &'static str;
    /// Handles one message: must finish within the watchdog's budget.
    fn handle(&mut self, msg: Self::Msg);
    /// Housekeeping at a deadline.
    fn tick(&mut self, now: Instant);
    /// The next time `tick` is needed (None: only messages wake the hub).
    fn next_deadline(&self, now: Instant) -> Option<Instant>;
    /// Stop after this message.
    fn done(&self) -> bool {
        false
    }
}

/// What a run did (checks count wake-ups).
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    pub messages: u64,
    pub ticks: u64,
}

/// Runs `world` until it is done or every sender is gone.
pub fn run<W: World>(world: &mut W, rx: &Receiver<W::Msg>) -> Stats {
    let mut stats = Stats::default();
    loop {
        let now = Instant::now();
        let wait = world
            .next_deadline(now)
            .map_or(IDLE_WAIT, |d| d.saturating_duration_since(now));
        match rx.recv_timeout(wait) {
            Ok(msg) => {
                stats.messages += 1;
                let name = W::name(&msg);
                crate::watched!(name, world.handle(msg));
            }
            Err(RecvTimeoutError::Timeout) => {
                let now = Instant::now();
                if world.next_deadline(now).is_some_and(|d| d <= now) {
                    stats.ticks += 1;
                    crate::watched!("tick", world.tick(now));
                }
            }
            Err(RecvTimeoutError::Disconnected) => break,
        }
        if world.done() {
            break;
        }
    }
    stats
}

type Answer = Result<String, String>;

/// The slow parts, run on the worker pools (checks give stand-ins).
pub struct Backends {
    /// Answers a chat (Claude or the PC's model): the conversation in, the
    /// answer out.
    pub talk: Box<dyn Fn(Vec<ChatTurn>) -> Answer + Send + Sync>,
    /// Probes the models (and loads one); a line on what was found.
    pub probe: Box<dyn Fn() -> Answer + Send + Sync>,
    /// Looks up a web page.
    pub lookup: Box<dyn Fn(String) -> Answer + Send + Sync>,
    /// Speaks text; the audio file's name.
    pub voice: Box<dyn Fn(String) -> Answer + Send + Sync>,
}

/// What the hub tells clients.
#[derive(Debug, Clone, PartialEq)]
pub enum HubEvent {
    ChatChanged(ChatRecord),
    Answered { chat_id: String, text: String },
}

/// The hub's messages.
pub enum Msg {
    ChatPut {
        chat_id: String,
        name: Option<String>,
        turns: Vec<ChatTurn>,
        deleted: bool,
        now_ms: i64,
        reply: Reply<Result<ChatPutDone, String>>,
    },
    ChatsSince {
        since: u64,
        reply: Reply<ChatsPage>,
    },
    /// Answer the chat's last turn (TalkWorker).
    Ask {
        chat_id: String,
        reply: Reply<Answer>,
    },
    /// Probe the models (ModelManager; waits while a chat is answered:
    /// PCE-09, PCE-10).
    Probe {
        reply: Reply<Answer>,
    },
    Lookup {
        url: String,
        reply: Reply<Answer>,
    },
    Say {
        text: String,
        reply: Reply<Answer>,
    },
    Connect {
        client: u64,
        queue: ClientQueue<HubEvent>,
    },
    Disconnect {
        client: u64,
    },
    Stop,
    /// A worker finished job `job`.
    Done {
        job: u64,
        result: Answer,
    },
}

enum Job {
    Answer {
        chat_id: String,
        reply: Reply<Answer>,
    },
    Probe {
        reply: Reply<Answer>,
    },
    Plain {
        reply: Reply<Answer>,
    },
}

/// The chat world.
pub struct ChatHub {
    talks: TalkStore,
    unlock: Unlock,
    clients: HashMap<u64, ClientQueue<HubEvent>>,
    next_job: u64,
    jobs: HashMap<u64, Job>,
    /// Probes asked for while a chat was being answered.
    probes_waiting: Vec<Reply<Answer>>,
    talk: Pool<(u64, Vec<ChatTurn>)>,
    model: Pool<u64>,
    lookups: Pool<(u64, String)>,
    voice: Pool<(u64, String)>,
    stop: bool,
}

impl ChatHub {
    /// The hub over `talks`, with its worker pools; their results come
    /// back on `tx`.
    pub fn new(talks: TalkStore, backends: Backends, tx: &Sender<Msg>) -> ChatHub {
        let Backends {
            talk,
            probe,
            lookup,
            voice,
        } = backends;
        let done = |job, result| Msg::Done { job, result };
        ChatHub {
            talks,
            unlock: Unlock::new(Duration::ZERO),
            clients: HashMap::new(),
            next_job: 0,
            jobs: HashMap::new(),
            probes_waiting: vec![],
            talk: Pool::spawn(
                "talk-worker",
                Sizes::TALK_WORKER,
                tx.clone(),
                move |(j, t)| done(j, talk(t)),
            ),
            model: Pool::spawn(
                "model-manager",
                Sizes::MODEL_MANAGER,
                tx.clone(),
                move |j| done(j, probe()),
            ),
            lookups: Pool::spawn(
                "lookup-pool",
                Sizes::LOOKUP_POOL,
                tx.clone(),
                move |(j, u)| done(j, lookup(u)),
            ),
            voice: Pool::spawn(
                "voice-worker",
                Sizes::VOICE_WORKER,
                tx.clone(),
                move |(j, t)| done(j, voice(t)),
            ),
            stop: false,
        }
    }

    fn job(&mut self, j: Job) -> u64 {
        self.next_job += 1;
        self.jobs.insert(self.next_job, j);
        self.next_job
    }

    fn answering(&self) -> bool {
        self.jobs.values().any(|j| matches!(j, Job::Answer { .. }))
    }

    fn broadcast(&mut self, ev: HubEvent) {
        self.clients
            .retain(|_, q| q.push(ev.clone()) != Pushed::Gone);
    }

    fn start_probe(&mut self, reply: Reply<Answer>) {
        let id = self.job(Job::Probe { reply });
        if self.model.submit(id).is_err()
            && let Some(Job::Probe { reply }) = self.jobs.remove(&id)
        {
            reply.send(Err("The PC is busy with its models.".into()));
        }
    }

    fn plain(&mut self, reply: Reply<Answer>, submit: impl FnOnce(&Self, u64) -> bool) {
        let id = self.job(Job::Plain { reply });
        if !submit(self, id)
            && let Some(Job::Plain { reply }) = self.jobs.remove(&id)
        {
            reply.send(Err("The PC is too busy for that now.".into()));
        }
    }
}

impl World for ChatHub {
    type Msg = Msg;

    fn name(m: &Msg) -> &'static str {
        match m {
            Msg::ChatPut { .. } => "ChatPut",
            Msg::ChatsSince { .. } => "ChatsSince",
            Msg::Ask { .. } => "Ask",
            Msg::Probe { .. } => "Probe",
            Msg::Lookup { .. } => "Lookup",
            Msg::Say { .. } => "Say",
            Msg::Connect { .. } => "Connect",
            Msg::Disconnect { .. } => "Disconnect",
            Msg::Stop => "Stop",
            Msg::Done { .. } => "Done",
        }
    }

    fn handle(&mut self, m: Msg) {
        // A locked store asks the keyring again with every request, on a
        // thread of its own (PCE-05).
        self.unlock.step(&mut self.talks);
        match m {
            Msg::ChatPut {
                chat_id,
                name,
                turns,
                deleted,
                now_ms,
                reply,
            } => {
                let r = self.talks.put(&chat_id, name, turns, deleted, now_ms);
                let r = r.map(|(done, changed)| {
                    if let Some(c) = changed {
                        self.broadcast(HubEvent::ChatChanged(c));
                    }
                    done
                });
                reply.send(r);
            }
            Msg::ChatsSince { since, reply } => reply.send(self.talks.since(since)),
            Msg::Ask { chat_id, reply } => {
                let Some(turns) = self.talks.get(&chat_id).map(|c| c.turns.clone()) else {
                    reply.send(Err("That chat isn't on this PC.".into()));
                    return;
                };
                let id = self.job(Job::Answer { chat_id, reply });
                if self.talk.submit((id, turns)).is_err()
                    && let Some(Job::Answer { reply, .. }) = self.jobs.remove(&id)
                {
                    reply.send(Err("The PC is answering too much at once.".into()));
                }
            }
            Msg::Probe { reply } => {
                if self.answering() {
                    self.probes_waiting.push(reply);
                } else {
                    self.start_probe(reply);
                }
            }
            Msg::Lookup { url, reply } => {
                self.plain(reply, |h, id| h.lookups.submit((id, url)).is_ok())
            }
            Msg::Say { text, reply } => {
                self.plain(reply, |h, id| h.voice.submit((id, text)).is_ok())
            }
            Msg::Connect { client, queue } => {
                self.clients.insert(client, queue);
            }
            Msg::Disconnect { client } => {
                self.clients.remove(&client);
            }
            Msg::Stop => self.stop = true,
            Msg::Done { job, result } => match self.jobs.remove(&job) {
                Some(Job::Answer { chat_id, reply }) => {
                    if let Ok(text) = &result {
                        self.broadcast(HubEvent::Answered {
                            chat_id,
                            text: text.clone(),
                        });
                    }
                    reply.send(result);
                    if !self.answering() {
                        for r in std::mem::take(&mut self.probes_waiting) {
                            self.start_probe(r);
                        }
                    }
                }
                Some(Job::Probe { reply }) | Some(Job::Plain { reply }) => reply.send(result),
                None => {}
            },
        }
    }

    fn tick(&mut self, _now: Instant) {
        // Only while the keyring is being asked again.
        self.unlock.step(&mut self.talks);
    }

    fn next_deadline(&self, now: Instant) -> Option<Instant> {
        // A locked store's keyring answer is collected soon; otherwise
        // nothing is timed: messages wake the hub.
        self.talks
            .locked()
            .then(|| now + Duration::from_millis(250))
    }

    fn done(&self) -> bool {
        self.stop
    }
}

/// The hub's address, for every other thread. The "task part" helpers send
/// a message and wait for its one-shot reply on the caller's thread, never
/// the hub's (PCS-01, PCS-02).
#[derive(Clone)]
pub struct Handle {
    tx: Sender<Msg>,
}

/// Starts a [`ChatHub`] on its own thread.
pub fn start(talks: TalkStore, backends: Backends) -> (Handle, std::thread::JoinHandle<Stats>) {
    let (tx, rx) = channel();
    let mut hub = ChatHub::new(talks, backends, &tx);
    let t = std::thread::Builder::new()
        .name("hub".into())
        .spawn(move || run(&mut hub, &rx))
        .expect("the hub's thread");
    (Handle { tx }, t)
}

impl Handle {
    pub fn sender(&self) -> &Sender<Msg> {
        &self.tx
    }

    fn ask<T>(&self, make: impl FnOnce(Reply<T>) -> Msg) -> Pending<T> {
        let (reply, pending) = oneshot();
        let _ = self.tx.send(make(reply));
        pending
    }

    /// Answers a chat's last turn.
    pub fn answer(&self, chat_id: &str) -> Pending<Answer> {
        let chat_id = chat_id.to_string();
        self.ask(|reply| Msg::Ask { chat_id, reply })
    }

    /// Wakes and probes the PC's models (after any chat being answered).
    pub fn probe(&self) -> Pending<Answer> {
        self.ask(|reply| Msg::Probe { reply })
    }

    pub fn lookup(&self, url: &str) -> Pending<Answer> {
        let url = url.to_string();
        self.ask(|reply| Msg::Lookup { url, reply })
    }

    pub fn say(&self, text: &str) -> Pending<Answer> {
        let text = text.to_string();
        self.ask(|reply| Msg::Say { text, reply })
    }

    pub fn put(
        &self,
        chat_id: &str,
        name: Option<String>,
        turns: Vec<ChatTurn>,
        deleted: bool,
        now_ms: i64,
    ) -> Pending<Result<ChatPutDone, String>> {
        let chat_id = chat_id.to_string();
        self.ask(|reply| Msg::ChatPut {
            chat_id,
            name,
            turns,
            deleted,
            now_ms,
            reply,
        })
    }

    pub fn since(&self, since: u64) -> Pending<ChatsPage> {
        self.ask(|reply| Msg::ChatsSince { since, reply })
    }

    pub fn connect(&self, client: u64, queue: ClientQueue<HubEvent>) {
        let _ = self.tx.send(Msg::Connect { client, queue });
    }

    pub fn stop(&self) {
        let _ = self.tx.send(Msg::Stop);
    }
}
