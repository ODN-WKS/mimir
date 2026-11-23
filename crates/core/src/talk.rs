//! The PC's chat engine (Stage 1 design, 1.1): a general chat is answered
//! straight from Ollama's `/api/chat` with the model the engine picked,
//! streamed piece by piece, with no Claude Code, project or git involved.
//! One answer at a time uses the graphics card; a few more wait their
//! turn, and one that would wait too long is left to the phone.

use crate::ollama;
use mimir_proto::{ChatSource, ChatTurn, Picture, Ran, TalkState};
use serde_json::{Value, json};
use std::collections::{HashMap, VecDeque};
use std::net::TcpStream;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

/// Requests that may wait behind the one being answered.
pub const QUEUE: usize = 4;
/// A request that has waited this long is answered by the phone.
pub const LONGEST_WAIT: Duration = Duration::from_secs(60);
/// A finished or failed answer can be fetched by its id this long.
pub const KEPT: Duration = Duration::from_secs(10 * 60);

/// How long a finished answer is remembered (checks cut it short).
pub fn kept() -> Duration {
    if cfg!(debug_assertions)
        && let Some(ms) = std::env::var("MIMIR_TALK_KEPT_MS")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
    {
        return Duration::from_millis(ms);
    }
    KEPT
}
/// The conversation is kept under this share of the context.
const ROOM: f32 = 0.75;

/// Where it runs, first in every chat's instructions (the same order as the
/// phone's `Prompt.build`).
pub const PLACE: &str = "You are Mímir, a private AI running on the owner's own PC.";
/// After the instructions when older turns had to be left out.
pub const LEFT_OUT: &str = "(Earlier parts of this chat were left out to fit.)";
/// Said when the phone should answer instead.
pub const BUSY: &str = "Your PC is busy; this phone answers instead.";
pub const WAITING: &str = "Your PC is answering another chat…";
pub const WAKING: &str = "Waking your PC's model…";

/// One answer to write.
#[derive(Debug, Clone)]
pub struct Job {
    /// Whose chat it is (the owner's, or another person's id).
    pub person: String,
    pub chat_id: String,
    /// The answer's id (`<message id>.a`).
    pub answer_id: String,
    pub model: String,
    /// e.g. "Qwen 3.6 · Your PC".
    pub model_words: String,
    /// (role, text), the instructions first.
    pub messages: Vec<(String, String)>,
    pub think: bool,
    pub max_tokens: u32,
    pub context: u64,
    pub sources: Vec<ChatSource>,
    /// Answered by Claude (a paid model) instead, with the free model as
    /// its backup (`model` empty: no backup).
    pub paid: Option<Paid>,
    /// Pictures for the newest user messages: `[0]` goes with the newest,
    /// `[1]` with the one before it (at most two, as the brief keeps it
    /// simple). Empty: none.
    pub pictures: Vec<Vec<Picture>>,
    /// The short plain note shown under the answer when a different model
    /// than the usual one answers.
    pub switch_note: Option<String>,
    /// Who tries next when this model fails on a picture (once each).
    pub backups: Vec<Backup>,
}

/// Another way to answer a picture message when the first one fails.
#[derive(Debug, Clone)]
pub struct Backup {
    /// The PC model (empty with `paid`).
    pub model: String,
    pub model_words: String,
    pub note: String,
    pub paid: Option<Paid>,
}

/// How a chat reaches Claude: one Claude Code turn with every tool,
/// customisation and memory left out, in a folder of its own.
#[derive(Debug, Clone)]
pub struct Paid {
    /// `--model` alias ("haiku", "sonnet", "opus", "fable"); None: Claude Code's own choice.
    pub model: Option<String>,
    /// The `claude` program.
    pub program: std::path::PathBuf,
    /// Where it runs (never a project or General).
    pub dir: std::path::PathBuf,
}

/// The Claude models a chat can use: (alias, plain name, what it is for).
/// "auto" passes no `--model`, so Claude Code picks.
pub const CLAUDE_CHOICES: [(&str, &str, &str); 5] = [
    ("auto", "Auto-pick", "Mímir chooses"),
    ("haiku", "Quick", "Short answers, fastest"),
    ("sonnet", "Balanced", "The usual"),
    ("opus", "Very capable", "For harder work"),
    ("fable", "Most capable", "The newest and strongest"),
];

/// The plain name of one of [`CLAUDE_CHOICES`] ("sonnet" is "Sonnet").
pub fn claude_choice_name(alias: &str) -> &'static str {
    CLAUDE_CHOICES
        .iter()
        .find(|c| c.0 == alias)
        .map_or("Auto-pick", |c| c.1)
}

/// A Claude model id in plain words: "claude-sonnet-4-6-20250929" is
/// "Sonnet 4.6", "claude-haiku" is "Haiku". No dates, no raw ids; None when
/// no family is named.
pub fn claude_words(id: &str) -> Option<String> {
    let t = id.to_lowercase();
    let family = ["sonnet", "opus", "haiku", "fable"]
        .into_iter()
        .find(|f| t.contains(f))?;
    let after = t.split_once(family)?.1;
    // Digits right after the family; an 8-digit date ends them.
    let mut nums: Vec<String> = vec![];
    let mut cur = String::new();
    for c in after.chars().chain(std::iter::once(' ')) {
        if c.is_ascii_digit() {
            cur.push(c);
        } else {
            if !cur.is_empty() {
                if cur.len() > 2 {
                    break;
                }
                nums.push(std::mem::take(&mut cur));
            }
            if nums.len() == 2 {
                break;
            }
        }
    }
    let mut name = family[..1].to_uppercase() + &family[1..];
    if !nums.is_empty() {
        name.push(' ');
        name.push_str(&nums.join("."));
    }
    Some(name)
}

/// What the online model is told it is. It is Anthropic's model answering and the person's words go to Anthropic,
/// so it must never call this chat private.
const PAID_PLACE: &str = "You are Mímir's online answerer: Anthropic's Claude, reached through \
                          the owner's own PC. This chat's words go to Anthropic, so do not \
                          call it private. You have no tools: answer from what you know.";

/// The instructions for the online model: the first message's place line is
/// swapped for the truthful one.
fn online_system(first: Option<(&str, &str)>) -> String {
    first
        .filter(|(r, _)| *r == "system")
        .map(|(_, t)| t.replacen(PLACE, PAID_PLACE, 1))
        .unwrap_or_else(|| PAID_PLACE.to_string())
}

/// What the engine reports, from its own thread.
#[derive(Debug, Clone)]
pub enum Out {
    Piece {
        chat_id: String,
        answer_id: String,
        n: u32,
        text: String,
        thinking: bool,
    },
    Done {
        chat_id: String,
        answer_id: String,
        result: Result<ChatTurn, String>,
    },
}

type Sink = Arc<dyn Fn(Out) + Send + Sync>;

#[derive(Default)]
struct State {
    queue: VecDeque<(Job, Instant)>,
    /// The answer being written: its id, its text so far, and its line to
    /// Ollama (closed by Stop).
    current: Option<(String, String, Option<TcpStream>)>,
    stopped: Vec<String>,
    /// Claude Code answering the current paid chat.
    child: Option<u32>,
    /// "Claude is at its limit" was said once.
    limit_said: bool,
    finished: HashMap<String, (Instant, TalkState)>,
    /// Tokens a character, per chat, from Ollama's own count.
    ratio: HashMap<String, f32>,
    /// When the first word of the current answer came (for the log).
    first_word: Option<Instant>,
}

/// The engine: a thread that answers one request at a time.
#[derive(Clone)]
pub struct Talk {
    state: Arc<(Mutex<State>, Condvar)>,
    out: Sink,
    base: Arc<dyn Fn() -> String + Send + Sync>,
}

impl Talk {
    /// Starts the engine; `out` gets its pieces and results.
    pub fn start(out: impl Fn(Out) + Send + Sync + 'static) -> Talk {
        Self::start_at(Arc::new(crate::models::ollama_url), out)
    }

    /// The same, with Ollama at the address `base` gives.
    pub fn start_at(
        base: Arc<dyn Fn() -> String + Send + Sync>,
        out: impl Fn(Out) + Send + Sync + 'static,
    ) -> Talk {
        let t = Talk {
            state: Arc::default(),
            out: Arc::new(out),
            base,
        };
        let w = t.clone();
        let _ = std::thread::Builder::new()
            .name("talk".into())
            .spawn(move || w.work());
        t
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.0.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Queues an answer. Ok with a line to show while it waits (None: it
    /// starts at once); Err when the phone should answer instead.
    pub fn submit(&self, job: Job) -> Result<Option<String>, String> {
        let mut s = self.lock();
        if s.queue.len() >= QUEUE {
            return Err(BUSY.into());
        }
        let busy = s.current.is_some() || !s.queue.is_empty();
        let ratio = s.ratio.get(&job.chat_id).copied();
        let mut job = job;
        job.messages = fit(&job.messages, job.context, ratio);
        s.finished.insert(
            job.answer_id.clone(),
            (
                Instant::now(),
                TalkState::Answering {
                    text: String::new(),
                },
            ),
        );
        s.queue.push_back((job, Instant::now()));
        self.state.1.notify_all();
        Ok(busy.then(|| WAITING.to_string()))
    }

    /// Stops an answer, waiting or being written.
    pub fn stop(&self, answer_id: &str) {
        let mut s = self.lock();
        if let Some(i) = s.queue.iter().position(|(j, _)| j.answer_id == answer_id) {
            let (j, _) = s.queue.remove(i).expect("found");
            s.finished.insert(
                answer_id.to_string(),
                (
                    Instant::now(),
                    TalkState::Failed {
                        why: "Stopped.".into(),
                    },
                ),
            );
            drop(s);
            (self.out)(Out::Done {
                chat_id: j.chat_id,
                answer_id: j.answer_id,
                result: Err("Stopped.".into()),
            });
            return;
        }
        if let Some((id, _, line)) = &mut s.current
            && id == answer_id
        {
            if let Some(l) = line.take() {
                let _ = l.shutdown(std::net::Shutdown::Both);
            }
            s.stopped.push(answer_id.to_string());
            if let Some(pid) = s.child {
                // SAFETY: a plain signal to the child this engine started.
                unsafe { libc::kill(pid as i32, libc::SIGTERM) };
            }
        }
    }

    /// How an answer stands.
    pub fn state(&self, answer_id: &str) -> TalkState {
        let s = self.lock();
        if let Some((id, text, _)) = &s.current
            && id == answer_id
        {
            return TalkState::Answering { text: text.clone() };
        }
        s.finished
            .get(answer_id)
            .map(|(_, st)| st.clone())
            .unwrap_or(TalkState::Unknown)
    }

    /// Whether an answer is being written or waits.
    pub fn answering(&self) -> bool {
        let s = self.lock();
        s.current.is_some() || !s.queue.is_empty()
    }

    /// Requests that waited too long go to the phone, and old results are
    /// forgotten. Called from the service's tick.
    pub fn expire(&self) {
        let mut late = vec![];
        {
            let mut s = self.lock();
            while s
                .queue
                .front()
                .is_some_and(|(_, at)| at.elapsed() >= LONGEST_WAIT)
            {
                let (j, _) = s.queue.pop_front().expect("checked");
                s.finished.insert(
                    j.answer_id.clone(),
                    (Instant::now(), TalkState::Failed { why: BUSY.into() }),
                );
                late.push(j);
            }
            s.finished.retain(|_, (at, _)| at.elapsed() < kept());
        }
        for j in late {
            (self.out)(Out::Done {
                chat_id: j.chat_id,
                answer_id: j.answer_id,
                result: Err(BUSY.into()),
            });
        }
    }

    fn work(&self) {
        loop {
            let (job, waited) = {
                let mut s = self.lock();
                loop {
                    if let Some((j, at)) = s.queue.pop_front() {
                        s.current = Some((j.answer_id.clone(), String::new(), None));
                        s.first_word = None;
                        break (j, at.elapsed());
                    }
                    s = self
                        .state
                        .1
                        .wait_timeout(s, Duration::from_secs(5))
                        .map(|(g, _)| g)
                        .unwrap_or_else(|e| e.into_inner().0);
                }
            };
            let began = Instant::now();
            let result = match &job.paid {
                None => self.answer(&job),
                Some(p) => match self.answer_paid(&job, p) {
                    Ok(t) => Ok((t, None)),
                    Err(_) if self.lock().stopped.contains(&job.answer_id) => {
                        Err("Stopped.".into())
                    }
                    // The free model is always Claude's backup.
                    Err(why) if !job.model.is_empty() => {
                        let mut free = job.clone();
                        free.paid = None;
                        // The "Answered online" note no longer fits.
                        free.switch_note = None;
                        free.model_words = "Free · Your PC".into();
                        let mut s = self.lock();
                        // PCE-33: show the reason on every free-model switch while
                        // at the limit, not just the first time.
                        if let Some(at) = crate::models::claude_limit(&why) {
                            s.limit_said = true;
                            let when = if at > 0 {
                                format!(" until {}", crate::models::clock(at))
                            } else {
                                String::new()
                            };
                            free.model_words = format!(
                                "Free · Your PC · Online is at its limit{when} · Your PC answered instead"
                            );
                        }
                        if let Some(c) = &mut s.current {
                            c.1.clear();
                        }
                        drop(s);
                        crate::log::write("talk", "Claude couldn't answer a chat", &why);
                        self.answer(&free)
                    }
                    Err(why) => Err(why),
                },
            };
            let result = self.retry_on_blind(&job, result);
            let mut s = self.lock();
            let stopped = s.stopped.contains(&job.answer_id);
            s.stopped.retain(|i| i != &job.answer_id);
            let text = s.current.take().map(|c| c.1).unwrap_or_default();
            let result = match result {
                Ok((turn, ratio)) => {
                    if let Some(r) = ratio {
                        s.ratio.insert(job.chat_id.clone(), r);
                    }
                    Ok(turn)
                }
                // Stopped part-way: what was written is kept.
                Err(_) if stopped && !text.trim().is_empty() => Ok(self.turn(&job, &text)),
                Err(_) if stopped => Err("Stopped.".to_string()),
                Err(e) => Err(e),
            };
            // The log: one line a chat answer (never a line a piece).
            {
                let first = s
                    .first_word
                    .map(|f| f.duration_since(began).as_millis() as u64);
                let mut f: Vec<(&'static str, serde_json::Value)> = vec![
                    ("chat", job.chat_id.clone().into()),
                    ("turn", job.answer_id.clone().into()),
                    (
                        "engine",
                        (if job.paid.is_some() { "paid" } else { "pc" }).into(),
                    ),
                    ("model", job.model.clone().into()),
                    ("queue_ms", (waited.as_millis() as u64).into()),
                    ("first_word_ms", first.into()),
                    ("ms", (began.elapsed().as_millis() as u64).into()),
                    ("chars", text.chars().count().into()),
                    ("stopped", stopped.into()),
                ];
                match &result {
                    Ok(_) => f.push(("ok", true.into())),
                    Err(e) => {
                        f.push(("err", crate::log::cut(e).into()));
                        f.push(("kind", "answer".into()));
                    }
                }
                match &result {
                    Ok(t) => crate::devlog::log_text("talk", "answer", f, &t.text),
                    Err(_) => crate::devlog::log("talk", "answer", f),
                }
            }
            let st = match &result {
                Ok(t) => TalkState::Done { turn: t.clone() },
                Err(e) => TalkState::Failed { why: e.clone() },
            };
            s.finished
                .insert(job.answer_id.clone(), (Instant::now(), st));
            drop(s);
            (self.out)(Out::Done {
                chat_id: job.chat_id.clone(),
                answer_id: job.answer_id.clone(),
                result,
            });
        }
    }

    /// A picture answer that failed on a PC model (a model or graphics card
    /// error, nothing written yet): that model can't see here. The same
    /// message goes once to each backup, in order; the last failure stands.
    fn retry_on_blind(
        &self,
        job: &Job,
        result: Result<(ChatTurn, Option<f32>), String>,
    ) -> Result<(ChatTurn, Option<f32>), String> {
        let base = (self.base)();
        let pictures = job.pictures.iter().any(|p| !p.is_empty());
        let failed = |r: &Result<(ChatTurn, Option<f32>), String>, j: &Job| {
            matches!(r, Err(why) if why != "Stopped.")
                && ollama::get_at(&base, "/api/version").is_ok()
                && !self.lock().stopped.contains(&j.answer_id)
                && self
                    .lock()
                    .current
                    .as_ref()
                    .is_none_or(|c| c.1.trim().is_empty())
        };
        if !pictures || job.paid.is_some() || job.model.is_empty() || !failed(&result, job) {
            if let Ok(_) = &result
                && pictures
                && job.paid.is_none()
                && !job.model.is_empty()
            {
                crate::sight::mark_sees(&base, &job.model);
            }
            return result;
        }
        let why = result.as_ref().err().cloned().unwrap_or_default();
        crate::log::write("talk", "a model failed on a picture", &why);
        crate::sight::mark_blind(&base, &job.model, &why);
        let mut result = result;
        for b in &job.backups {
            let mut next = job.clone();
            next.model = b.model.clone();
            next.model_words = b.model_words.clone();
            next.switch_note = Some(b.note.clone());
            next.paid = b.paid.clone();
            next.backups = vec![];
            if let Some(c) = &mut self.lock().current {
                c.1.clear();
            }
            result = match &b.paid {
                Some(p) => self.answer_paid(&next, p).map(|t| (t, None)),
                None => self.answer(&next),
            };
            if result.is_ok() {
                if b.paid.is_none() {
                    crate::sight::mark_sees(&base, &b.model);
                }
                break;
            }
            if !failed(&result, &next) {
                break;
            }
            if b.paid.is_none() {
                let why = result.as_ref().err().cloned().unwrap_or_default();
                crate::sight::mark_blind(&base, &b.model, &why);
            }
        }
        result
    }

    fn turn(&self, job: &Job, text: &str) -> ChatTurn {
        ChatTurn {
            id: job.answer_id.clone(),
            user: false,
            text: text.trim().to_string(),
            sources: job.sources.clone(),
            ran: Some(if job.paid.is_some() {
                Ran::Paid
            } else {
                Ran::Pc
            }),
            model_words: Some(job.model_words.clone()),
            at: now_ms(),
            version: 1,
            seq: 0,
            gone: false,
            switch_note: job.switch_note.clone(),
            pictures: vec![],
            files: vec![],
        }
    }

    /// Writes one answer, sending its pieces. The finished turn, and the
    /// tokens a character Ollama counted.
    fn answer(&self, job: &Job) -> Result<(ChatTurn, Option<f32>), String> {
        let base = (self.base)();
        if ollama::get_at(&base, "/api/version").is_err()
            && !crate::models::ensure_running(Duration::from_secs(30))
        {
            return Err("Your PC's model isn't running, and it didn't start by itself. Open Settings › Models to set it up, then try again.".into());
        }
        let users: Vec<usize> = job
            .messages
            .iter()
            .enumerate()
            .filter(|(_, (r, _))| r == "user")
            .map(|(i, _)| i)
            .collect();
        let messages: Vec<Value> = job
            .messages
            .iter()
            .enumerate()
            .map(|(i, (r, c))| {
                let mut m = json!({"role": r, "content": c});
                // The Nth user message from the end gets its pictures
                // (Ollama's `images`: plain base64).
                if let Some(back) = users.iter().rev().position(|u| *u == i)
                    && let Some(p) = job.pictures.get(back).filter(|p| !p.is_empty())
                {
                    m["images"] = json!(p.iter().map(|p| p.base64.as_str()).collect::<Vec<_>>());
                }
                m
            })
            .collect();
        let body = json!({
            "model": job.model,
            "messages": messages,
            "stream": true,
            "think": job.think,
            "keep_alive": "10m",
            "options": {"num_ctx": job.context, "num_predict": job.max_tokens},
        });
        let mut stream =
            ollama::post_stream_at(&base, "/api/chat", &body, Duration::from_secs(180)).map_err(
                |e| {
                    crate::log::write("talk", "model couldn't answer", &e.to_string());
                    "Your PC's model couldn't answer. Try again in a moment.".to_string()
                },
            )?;
        {
            let mut s = self.lock();
            let stopped = s.stopped.contains(&job.answer_id);
            if let Some(c) = &mut s.current {
                c.2 = stream.closer();
                if stopped && let Some(l) = c.2.take() {
                    let _ = l.shutdown(std::net::Shutdown::Both);
                }
            }
        }
        let mut split = Split::default();
        let mut n = 0;
        let mut ratio = None;
        let mut last_send = Instant::now() - Duration::from_secs(1);
        let mut held = (String::new(), String::new());
        let chars: usize = job.messages.iter().map(|(_, c)| c.chars().count()).sum();
        while let Some(v) = stream.next_json()? {
            let m = &v["message"];
            if let Some(t) = m["thinking"].as_str() {
                held.0.push_str(t);
            }
            if let Some(t) = m["content"].as_str() {
                let (think, say) = split.push(t);
                held.0.push_str(&think);
                held.1.push_str(&say);
            }
            let done = v["done"].as_bool() == Some(true);
            // Pieces go out at most every 60 ms, so a fast model doesn't
            // flood the link.
            if done || last_send.elapsed() >= Duration::from_millis(60) {
                for (text, thinking) in [
                    (std::mem::take(&mut held.0), true),
                    (std::mem::take(&mut held.1), false),
                ] {
                    if text.is_empty() {
                        continue;
                    }
                    if !thinking {
                        let mut s = self.lock();
                        if s.first_word.is_none() {
                            s.first_word = Some(Instant::now());
                        }
                        if let Some(c) = &mut s.current {
                            c.1.push_str(&text);
                        }
                    }
                    (self.out)(Out::Piece {
                        chat_id: job.chat_id.clone(),
                        answer_id: job.answer_id.clone(),
                        n,
                        text,
                        thinking,
                    });
                    n += 1;
                }
                last_send = Instant::now();
            }
            if done {
                if let Some(p) = v["prompt_eval_count"].as_f64()
                    && chars > 0
                {
                    ratio = Some((p / chars as f64) as f32);
                }
                break;
            }
        }
        let text = self
            .lock()
            .current
            .as_ref()
            .map(|c| c.1.clone())
            .unwrap_or_default();
        if self.lock().stopped.contains(&job.answer_id) {
            return Err("Stopped.".into());
        }
        if text.trim().is_empty() {
            // PCE-11: give the owner something to act on.
            return Err("The model finished without producing a reply. \
                 Try again, or switch to a different model."
                .into());
        }
        Ok((self.turn(job, &text), ratio))
    }
}

/// An answer's id from its message's (`<id>.a`); an answer's id stays.
pub fn answer_id(turn_id: &str) -> String {
    if turn_id.ends_with(".a") {
        turn_id.to_string()
    } else {
        format!("{turn_id}.a")
    }
}

/// A model's title without its size and "uncensored" ("Qwen 3.6").
pub fn short_title(title: &str) -> String {
    title
        .split(',')
        .next()
        .unwrap_or(title)
        .split_whitespace()
        .filter(|w| {
            !w.ends_with('B')
                || !w[..w.len() - 1]
                    .chars()
                    .all(|c| c.is_ascii_digit() || c == '.')
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Each chat's Claude Code conversation, carried on with `--resume`.
fn sessions_file(dir: &std::path::Path) -> std::path::PathBuf {
    dir.join("sessions.json")
}

/// Where a chat's Claude conversation is remembered: the owner's by the
/// chat id as before, anyone else's under their own person, so the same
/// chat id of another person never resumes it.
pub fn session_key(person: &str, chat_id: &str) -> String {
    if person == crate::people::OWNER {
        chat_id.to_string()
    } else {
        format!("{person}\u{1f}{chat_id}")
    }
}

/// A person is gone: so are their Claude conversations.
pub fn forget_sessions(dir: &std::path::Path, person: &str) {
    let file = sessions_file(dir);
    let Some(mut sessions) = std::fs::read_to_string(&file)
        .ok()
        .and_then(|t| serde_json::from_str::<HashMap<String, String>>(&t).ok())
    else {
        return;
    };
    let prefix = format!("{person}\u{1f}");
    let before = sessions.len();
    sessions.retain(|k, _| !k.starts_with(&prefix));
    if sessions.len() != before {
        let _ = write_file(&file, &serde_json::to_vec(&sessions).unwrap_or_default());
    }
}

impl Talk {
    /// Answers through Claude Code: the chat's own conversation (resumed),
    /// or, when it's gone, the chat so far replayed as the first message.
    fn answer_paid(&self, job: &Job, p: &Paid) -> Result<ChatTurn, String> {
        use std::io::{BufRead, Write};
        use std::process::{Command, Stdio};
        std::fs::create_dir_all(&p.dir).map_err(|e| e.to_string())?;
        let file = sessions_file(&p.dir);
        let mut sessions: HashMap<String, String> = std::fs::read_to_string(&file)
            .ok()
            .and_then(|t| serde_json::from_str(&t).ok())
            .unwrap_or_default();
        let key = session_key(&job.person, &job.chat_id);
        let session = sessions.get(&key).cloned();
        let turns: Vec<&(String, String)> =
            job.messages.iter().filter(|(r, _)| r != "system").collect();
        let last = turns.last().map(|(_, t)| t.clone()).unwrap_or_default();
        let message = match (&session, turns.len()) {
            (Some(_), _) | (None, 0..=1) => last,
            (None, _) => {
                let mut m = String::from("(Earlier in this chat:)\n");
                for (r, t) in &turns[..turns.len() - 1] {
                    let who = if r == "user" { "Me" } else { "You" };
                    m.push_str(&format!("{who}: {t}\n\n"));
                }
                m.push_str("(Now:)\n");
                m.push_str(&last);
                m
            }
        };
        let system = online_system(job.messages.first().map(|(r, t)| (r.as_str(), t.as_str())));
        let mut cmd = Command::new(&p.program);
        cmd.current_dir(&p.dir)
            .args([
                "-p",
                "--output-format",
                "stream-json",
                "--verbose",
                "--include-partial-messages",
                // No tools, MCP servers, hooks, memory or CLAUDE.md
                // (checked against Claude Code 2.1.329: tools [] and
                // mcp_servers []).
                "--safe-mode",
                "--tools",
                "",
                "--strict-mcp-config",
                "--system-prompt",
                &system,
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        // With pictures, the message goes in as one stream-json line (as a
        // project task's does), so Claude gets the pictures themselves:
        // no files, nothing left on disk.
        let pictures: &[Picture] = job.pictures.first().map(|p| p.as_slice()).unwrap_or(&[]);
        if !pictures.is_empty() {
            cmd.args(["--input-format", "stream-json"]);
        }
        if let Some(sid) = &session {
            cmd.args(["--resume", sid]);
        }
        if let Some(m) = &p.model {
            cmd.args(["--model", m]);
        }
        let mut child = cmd
            .spawn()
            .map_err(|_| "Project work isn't set up on this PC.".to_string())?;
        self.lock().child = Some(child.id());
        if let Some(mut i) = child.stdin.take() {
            if pictures.is_empty() {
                let _ = i.write_all(message.as_bytes());
            } else {
                let line = crate::events::user_message_with(&message, pictures);
                let _ = i.write_all(format!("{line}\n").as_bytes());
            }
        }
        let mut said = String::new();
        let mut whole = String::new();
        let mut error = None;
        let mut sid = None;
        let mut model = None;
        let mut n = 0;
        if let Some(out) = child.stdout.take() {
            for line in std::io::BufReader::new(out).lines().map_while(Result::ok) {
                let Ok(v) = serde_json::from_str::<Value>(&line) else {
                    continue;
                };
                match v["type"].as_str() {
                    Some("stream_event") => {
                        let e = &v["event"];
                        if e["type"] == "content_block_delta" && e["delta"]["type"] == "text_delta"
                        {
                            let t = e["delta"]["text"].as_str().unwrap_or("").to_string();
                            said.push_str(&t);
                            {
                                let mut s = self.lock();
                                if s.first_word.is_none() {
                                    s.first_word = Some(Instant::now());
                                }
                                if let Some(c) = &mut s.current {
                                    c.1.push_str(&t);
                                }
                            }
                            (self.out)(Out::Piece {
                                chat_id: job.chat_id.clone(),
                                answer_id: job.answer_id.clone(),
                                n,
                                text: t,
                                thinking: false,
                            });
                            n += 1;
                        }
                    }
                    Some("assistant") => {
                        if let Some(m) = v["message"]["model"].as_str() {
                            model = Some(m.to_string());
                        }
                        for b in v["message"]["content"].as_array().into_iter().flatten() {
                            if let Some(t) = b["text"].as_str() {
                                whole.push_str(t);
                            }
                        }
                    }
                    Some("result") => {
                        sid = v["session_id"].as_str().map(str::to_string);
                        if v["is_error"].as_bool() == Some(true) {
                            error = Some(
                                v["result"]
                                    .as_str()
                                    .unwrap_or("Online couldn't answer.")
                                    .to_string(),
                            );
                        } else if whole.is_empty() {
                            whole = v["result"].as_str().unwrap_or("").to_string();
                        }
                    }
                    _ => {}
                }
            }
        }
        let status = child.wait();
        self.lock().child = None;
        if let Some(sid) = sid.filter(|_| error.is_none()) {
            sessions.insert(key.clone(), sid);
            let _ = write_file(&file, &serde_json::to_vec(&sessions).unwrap_or_default());
        } else if session.is_some() && error.is_some() {
            // Its conversation is gone: the next message replays the chat.
            sessions.remove(&key);
            let _ = write_file(&file, &serde_json::to_vec(&sessions).unwrap_or_default());
        }
        if let Some(e) = error {
            return Err(e);
        }
        let text = if said.trim().is_empty() { whole } else { said };
        if text.trim().is_empty() {
            // PCE-11: give the owner something to act on.
            return Err(match status {
                Ok(s) if s.success() => "The model finished without producing a reply. \
                     Try again, or switch to a different model."
                    .into(),
                _ => "Project work stopped before answering.".into(),
            });
        }
        let mut turn = self.turn(job, &text);
        // The model that really answered, in plain words.
        if let Some(w) = model.as_deref().and_then(claude_words) {
            turn.model_words = Some(format!("{w} · Online"));
        }
        Ok(turn)
    }
}

fn write_file(path: &std::path::Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, bytes)?;
    std::fs::rename(tmp, path)
}

/// Milliseconds since 1970.
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Splits a model's `<think>…</think>` from its answer as it streams (for
/// models that write their thinking into the answer).
#[derive(Default)]
struct Split {
    seen: String,
    /// 0: not known yet, 1: inside thinking, 2: answering.
    phase: u8,
}

impl Split {
    /// (thinking, answer) of this piece.
    fn push(&mut self, piece: &str) -> (String, String) {
        if self.phase == 2 {
            return (String::new(), piece.to_string());
        }
        self.seen.push_str(piece);
        let t = self.seen.trim_start();
        if self.phase == 0 {
            if t.len() < 7 && "<think>".starts_with(t) {
                return Default::default();
            }
            if !t.starts_with("<think>") {
                self.phase = 2;
                return (String::new(), std::mem::take(&mut self.seen));
            }
            self.phase = 1;
        }
        let t = self.seen.trim_start().trim_start_matches("<think>");
        match t.find("</think>") {
            Some(end) => {
                let think = t[..end].to_string();
                let say = t[end + 8..].trim_start().to_string();
                self.phase = 2;
                self.seen.clear();
                (think, say)
            }
            None => {
                // Hold back what might be the start of "</think>".
                let keep = (1..8)
                    .rev()
                    .find(|k| t.len() >= *k && "</think>".starts_with(&t[t.len() - k..]))
                    .unwrap_or(0);
                let out = t[..t.len() - keep].to_string();
                self.seen = format!("<think>{}", &t[t.len() - keep..]);
                (out, String::new())
            }
        }
    }
}

/// The instructions: where it runs, the ground rules, and what was looked
/// up for this message.
pub fn system(ground_rules: Option<&str>, lookup: Option<&str>) -> String {
    let mut s = PLACE.to_string();
    for part in [ground_rules, lookup].into_iter().flatten() {
        if !part.trim().is_empty() {
            s.push_str("\n\n");
            s.push_str(part.trim());
        }
    }
    s
}

/// Keeps the conversation under 75% of the context: the instructions and
/// the newest turns stay; the oldest drop out, with a line saying so.
pub fn fit(
    messages: &[(String, String)],
    context: u64,
    ratio: Option<f32>,
) -> Vec<(String, String)> {
    let per_char = ratio.filter(|r| *r > 0.0 && *r < 4.0).unwrap_or(0.25);
    let tokens = |m: &(String, String)| (m.1.chars().count() as f32 * per_char) as u64 + 4;
    let room = (context as f32 * ROOM) as u64;
    let Some((first, rest)) = messages.split_first() else {
        return vec![];
    };
    let has_system = first.0 == "system";
    let (sys, turns) = if has_system {
        (Some(first), rest)
    } else {
        (None, messages)
    };
    let mut used = sys.map(tokens).unwrap_or(0) + 20;
    let mut keep = 0;
    for m in turns.iter().rev() {
        let t = tokens(m);
        // The newest message always goes, whatever its size.
        if keep > 0 && used + t > room {
            break;
        }
        used += t;
        keep += 1;
    }
    let mut out = vec![];
    let dropped = keep < turns.len();
    if let Some(s) = sys {
        let mut s = s.clone();
        if dropped {
            s.1.push_str("\n\n");
            s.1.push_str(LEFT_OUT);
        }
        out.push(s);
    } else if dropped {
        out.push(("system".into(), LEFT_OUT.into()));
    }
    let mut kept: Vec<(String, String)> = turns[turns.len() - keep..].to_vec();
    // A chat starts with the owner's message, not an answer.
    while kept.first().is_some_and(|m| m.0 == "assistant") && kept.len() > 1 {
        kept.remove(0);
    }
    out.extend(kept);
    out
}

/// The answer's size and thinking for the effort chip ("low", "medium",
/// "high"): (think, most tokens).
pub fn effort(e: Option<&str>) -> (bool, u32) {
    match e {
        Some("low") => (false, 512),
        Some("high") => (true, 8192),
        _ => (false, 2048),
    }
}

/// The chat's turns as messages for the model, after the instructions.
pub fn messages(system: String, turns: &[ChatTurn]) -> Vec<(String, String)> {
    let mut m = vec![("system".to_string(), system)];
    for t in turns {
        if t.text.trim().is_empty() && t.pictures.is_empty() {
            continue;
        }
        if t.text.trim().is_empty() {
            m.push(("user".to_string(), "[picture]".to_string()));
            continue;
        }
        let role = if t.user { "user" } else { "assistant" };
        m.push((role.to_string(), t.text.clone()));
    }
    m
}

#[cfg(test)]
mod tests {
    #[test]
    fn claude_model_ids_become_plain_words() {
        assert_eq!(
            claude_words("claude-sonnet-4-6-20250929").as_deref(),
            Some("Sonnet 4.6")
        );
        assert_eq!(claude_words("claude-opus-5-5").as_deref(), Some("Opus 5.5"));
        assert_eq!(
            claude_words("claude-sonnet-4-20250514").as_deref(),
            Some("Sonnet 4")
        );
        assert_eq!(claude_words("claude-haiku").as_deref(), Some("Haiku"));
        assert_eq!(claude_words("fable").as_deref(), Some("Fable"));
        assert_eq!(claude_words("qwen3.6"), None);
        assert_eq!(claude_choice_name("opus"), "Very capable");
    }

    use super::*;
    use std::sync::mpsc;

    fn job(id: &str, words: &str) -> Job {
        Job {
            person: "owner".into(),
            chat_id: "c".into(),
            answer_id: format!("{id}.a"),
            model: "m".into(),
            model_words: "M · Your PC".into(),
            messages: vec![
                ("system".into(), system(Some("Be brief."), None)),
                ("user".into(), words.into()),
            ],
            think: false,
            max_tokens: 100,
            context: 16_384,
            sources: vec![],
            paid: None,
            pictures: vec![],
            switch_note: None,
            backups: vec![],
        }
    }

    fn engine(lines: Vec<&'static str>) -> (Talk, mpsc::Receiver<Out>) {
        let base = crate::ollama::fake::start(move |path, _| {
            if path == "/api/version" {
                return vec![r#"{"version":"0.40.16"}"#.into()];
            }
            lines.iter().map(|l| l.to_string()).collect()
        });
        let (tx, rx) = mpsc::channel();
        let tx = Mutex::new(tx);
        let t = Talk::start_at(Arc::new(move || base.clone()), move |o| {
            let _ = tx.lock().unwrap().send(o);
        });
        (t, rx)
    }

    fn until_done(rx: &mpsc::Receiver<Out>) -> (String, String, Result<ChatTurn, String>) {
        let (mut said, mut thought) = (String::new(), String::new());
        loop {
            match rx.recv_timeout(Duration::from_secs(60)).expect("no answer") {
                Out::Piece { text, thinking, .. } => {
                    if thinking {
                        thought.push_str(&text)
                    } else {
                        said.push_str(&text)
                    }
                }
                Out::Done { result, .. } => return (said, thought, result),
            }
        }
    }

    fn pic(data: &str) -> Picture {
        Picture {
            media_type: "image/jpeg".into(),
            base64: data.into(),
        }
    }

    /// A fake Ollama: model "blind" fails every picture with a graphics card
    /// error, model "eyes" answers; versions and vision claims are listed.
    fn two_models() -> (Talk, mpsc::Receiver<Out>, Arc<Mutex<Vec<String>>>) {
        let asked = Arc::new(Mutex::new(vec![]));
        let a2 = asked.clone();
        let base = crate::ollama::fake::start(move |path, body| {
            let v: serde_json::Value = serde_json::from_str(body).unwrap_or_default();
            let model = v["model"].as_str().unwrap_or("").to_string();
            match path {
                "/api/version" => vec![r#"{"version":"0.40.16"}"#.into()],
                "/api/chat" => {
                    a2.lock().unwrap().push(model.clone());
                    if model == "tt-blind" {
                        vec![r#"{"error":"ROCm error: invalid device function"}"#.into()]
                    } else {
                        vec![
                            r#"{"message":{"content":"A red square."},"done":false}"#.into(),
                            r#"{"message":{"content":""},"done":true}"#.into(),
                        ]
                    }
                }
                _ => vec![],
            }
        });
        let (tx, rx) = mpsc::channel();
        let tx = Mutex::new(tx);
        let t = Talk::start_at(Arc::new(move || base.clone()), move |o| {
            let _ = tx.lock().unwrap().send(o);
        });
        (t, rx, asked)
    }

    fn backup_eyes() -> Backup {
        Backup {
            model: "tt-eyes".into(),
            model_words: "Eyes · Your PC".into(),
            note: "Answered by Eyes because your usual model can't look at pictures.".into(),
            paid: None,
        }
    }

    #[test]
    fn a_picture_that_fails_on_the_model_is_tried_once_on_the_next_one_with_a_note() {
        let (t, rx, asked) = two_models();
        let mut j = job("n1", "What is this?");
        j.model = "tt-blind".into();
        j.pictures = vec![vec![pic("PIC")]];
        j.backups = vec![backup_eyes()];
        t.submit(j).unwrap();
        let (said, _, result) = until_done(&rx);
        let turn = result.unwrap();
        assert_eq!(said, "A red square.");
        assert_eq!(
            turn.switch_note.as_deref(),
            Some("Answered by Eyes because your usual model can't look at pictures.")
        );
        assert_eq!(turn.model_words.as_deref(), Some("Eyes · Your PC"));
        assert_eq!(*asked.lock().unwrap(), vec!["tt-blind", "tt-eyes"]);
    }

    #[test]
    fn a_text_message_that_fails_is_not_retried_elsewhere() {
        let (t, rx, asked) = two_models();
        let mut j = job("n2", "Hello");
        j.model = "tt-blind".into();
        j.backups = vec![backup_eyes()];
        t.submit(j).unwrap();
        let (_, _, result) = until_done(&rx);
        assert!(result.is_err());
        assert_eq!(*asked.lock().unwrap(), vec!["tt-blind"]);
    }

    #[test]
    fn when_every_model_fails_the_last_failure_stands_and_nobody_is_asked_twice() {
        let (t, rx, asked) = two_models();
        let mut j = job("n3", "What is this?");
        j.model = "tt-blind".into();
        j.pictures = vec![vec![pic("PIC")]];
        let mut also = backup_eyes();
        also.model = "tt-blind".into();
        j.backups = vec![also];
        t.submit(j).unwrap();
        let (_, _, result) = until_done(&rx);
        assert!(result.is_err());
        assert_eq!(asked.lock().unwrap().len(), 2);
    }

    #[test]
    fn a_picture_reaches_the_models_request_for_the_last_two_user_messages() {
        let seen = Arc::new(Mutex::new(String::new()));
        let s2 = seen.clone();
        let base = crate::ollama::fake::start(move |path, body| {
            if path == "/api/version" {
                return vec![r#"{"version":"0.40.16"}"#.into()];
            }
            if path == "/api/chat" {
                *s2.lock().unwrap() = body.to_string();
            }
            vec![
                r#"{"message":{"content":"A cat."},"done":false}"#.into(),
                r#"{"message":{"content":""},"done":true}"#.into(),
            ]
        });
        let (tx, rx) = mpsc::channel();
        let tx = Mutex::new(tx);
        let t = Talk::start_at(Arc::new(move || base.clone()), move |o| {
            let _ = tx.lock().unwrap().send(o);
        });
        let mut j = job("p1", "What does this show?");
        j.messages = vec![
            ("system".into(), "Be brief.".into()),
            ("user".into(), "old one".into()),
            ("assistant".into(), "ok".into()),
            ("user".into(), "[picture]".into()),
            ("assistant".into(), "A dog.".into()),
            ("user".into(), "What does this show?".into()),
        ];
        j.pictures = vec![vec![pic("NEWPIC")], vec![pic("BEFOREPIC")]];
        assert_eq!(t.submit(j), Ok(None));
        let (said, _, result) = until_done(&rx);
        assert_eq!(said, "A cat.");
        assert!(result.is_ok());
        let body: Value = serde_json::from_str(&seen.lock().unwrap()).unwrap();
        let m = body["messages"].as_array().unwrap();
        assert_eq!(m.len(), 6);
        assert!(m[1].get("images").is_none());
        assert_eq!(m[3]["images"][0], "BEFOREPIC");
        assert_eq!(m[5]["images"][0], "NEWPIC");
        assert!(m[5]["images"].as_array().unwrap().len() == 1);
    }

    #[test]
    fn a_picture_goes_to_claude_as_one_stream_line_with_the_picture_in_it() {
        use std::os::unix::fs::PermissionsExt;
        let d = std::env::temp_dir().join(format!("talk-paid-pic-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        let prog = d.join("claude");
        std::fs::write(
            &prog,
            format!(
                "#!/bin/sh\necho \"$@\" > {0}/args\ncat > {0}/stdin\n\
                 echo '{{\"type\":\"result\",\"session_id\":\"s1\",\"result\":\"A cat.\"}}'\n",
                d.display()
            ),
        )
        .unwrap();
        std::fs::set_permissions(&prog, std::fs::Permissions::from_mode(0o755)).unwrap();
        let (t, rx) = engine(vec![]);
        let mut j = job("p2", "What does this show?");
        j.model = String::new();
        j.pictures = vec![vec![pic("NEWPIC")]];
        j.paid = Some(Paid {
            model: None,
            program: prog,
            dir: d.join("run"),
        });
        assert_eq!(t.submit(j), Ok(None));
        let (_, _, result) = until_done(&rx);
        assert_eq!(result.unwrap().text, "A cat.");
        let args = std::fs::read_to_string(d.join("args")).unwrap();
        assert!(args.contains("--input-format stream-json"), "{args}");
        let line = std::fs::read_to_string(d.join("stdin")).unwrap();
        let v: Value = serde_json::from_str(line.trim()).unwrap();
        let c = v["message"]["content"].as_array().unwrap();
        assert_eq!(c[0]["type"], "image");
        assert_eq!(c[0]["source"]["data"], "NEWPIC");
        assert!(
            c[1]["text"]
                .as_str()
                .unwrap()
                .contains("What does this show?")
        );
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn when_online_fails_the_free_answer_does_not_say_answered_online() {
        let d = std::env::temp_dir().join(format!("talk-paid-fail-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        let prog = d.join("claude");
        std::fs::write(&prog, "#!/bin/sh\ncat >/dev/null\nexit 1\n").unwrap();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&prog, std::fs::Permissions::from_mode(0o755)).unwrap();
        let (t, rx) = engine(vec![r#"{"message":{"content":"Hi"},"done":true}"#]);
        let mut j = job("p3", "What is this?");
        j.switch_note =
            Some("Answered online because your usual model can't look at pictures.".into());
        j.paid = Some(Paid {
            model: None,
            program: prog,
            dir: d.join("run"),
        });
        assert_eq!(t.submit(j), Ok(None));
        let (_, _, result) = until_done(&rx);
        let turn = result.unwrap();
        assert_eq!(turn.switch_note, None);
        assert!(turn.model_words.unwrap().starts_with("Free"));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn an_answer_streams_and_thinking_is_never_the_answer() {
        let (t, rx) = engine(vec![
            r#"{"message":{"content":"","thinking":"The owner wants "},"done":false}"#,
            r#"{"message":{"content":"","thinking":"a capital."},"done":false}"#,
            r#"{"message":{"content":"Oslo is "},"done":false}"#,
            "<pause>",
            r#"{"message":{"content":"the capital."},"done":false}"#,
            r#"{"message":{"content":""},"done":true,"prompt_eval_count":40,"eval_count":5,"eval_duration":100000000}"#,
        ]);
        assert_eq!(t.submit(job("t1", "Capital of Norway?")), Ok(None));
        let (said, thought, result) = until_done(&rx);
        assert_eq!(said, "Oslo is the capital.");
        assert_eq!(thought, "The owner wants a capital.");
        let turn = result.unwrap();
        assert_eq!(turn.id, "t1.a");
        assert_eq!(turn.text, "Oslo is the capital.");
        assert_eq!(turn.ran, Some(Ran::Pc));
        assert_eq!(turn.model_words.as_deref(), Some("M · Your PC"));
        assert!(matches!(t.state("t1.a"), TalkState::Done { .. }));
        assert_eq!(t.state("nope.a"), TalkState::Unknown);
    }

    #[test]
    fn ids_and_titles() {
        assert_eq!(answer_id("t1"), "t1.a");
        assert_eq!(answer_id("t1.a"), "t1.a");
        assert_eq!(short_title("Qwen 3.6 35B, uncensored"), "Qwen 3.6");
        assert_eq!(short_title("gpt-oss 20B, uncensored"), "gpt-oss");
        assert_eq!(short_title("Qwen 3.5 4B, uncensored"), "Qwen 3.5");
    }

    #[test]
    fn think_tags_in_the_answer_are_split_off() {
        let (t, rx) = engine(vec![
            r#"{"message":{"content":"<thi"},"done":false}"#,
            r#"{"message":{"content":"nk>hmm, Norway</th"},"done":false}"#,
            r#"{"message":{"content":"ink>\n\nOslo."},"done":false}"#,
            r#"{"message":{"content":""},"done":true}"#,
        ]);
        t.submit(job("t1", "Capital?")).unwrap();
        let (said, thought, _) = until_done(&rx);
        assert_eq!(said, "Oslo.");
        assert_eq!(thought, "hmm, Norway");
    }

    #[test]
    fn one_answer_at_a_time_four_wait_and_a_fifth_goes_to_the_phone() {
        let (t, rx) = engine(vec![
            "<pause>",
            "<pause>",
            r#"{"message":{"content":"ok"},"done":true}"#,
        ]);
        assert_eq!(t.submit(job("a", "1")), Ok(None));
        // The first is taken off the queue once it starts.
        let until = Instant::now() + Duration::from_secs(5);
        while t.lock().queue.len() == 1 && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(5));
        }
        for id in ["b", "c", "d", "e"] {
            assert_eq!(t.submit(job(id, "x")), Ok(Some(WAITING.into())), "{id}");
        }
        assert_eq!(t.submit(job("f", "x")), Err(BUSY.into()));
        // Stopping one that waits answers it at once.
        t.stop("e.a");
        let mut done = vec![];
        while done.len() < 5 {
            if let Out::Done {
                answer_id, result, ..
            } = rx.recv_timeout(Duration::from_secs(10)).unwrap()
            {
                done.push((answer_id, result.is_ok()));
            }
        }
        assert_eq!(done[0], ("e.a".into(), false));
        assert!(done[1..].iter().all(|(_, ok)| *ok), "{done:?}");
        assert!(!t.answering());
    }

    #[test]
    fn stop_ends_an_answer_and_keeps_what_was_written() {
        let mut lines = vec![r#"{"message":{"content":"Once upon a time"},"done":false}"#];
        lines.extend(std::iter::repeat_n("<pause>", 100));
        lines.push(r#"{"message":{"content":" the end"},"done":true}"#);
        let (t, rx) = engine(lines);
        t.submit(job("s", "A long story")).unwrap();
        // Wait for the first words, then stop.
        match rx.recv_timeout(Duration::from_secs(5)).unwrap() {
            Out::Piece { text, .. } => assert_eq!(text, "Once upon a time"),
            o => panic!("{o:?}"),
        }
        let started = Instant::now();
        t.stop("s.a");
        let (_, _, result) = until_done(&rx);
        assert!(
            started.elapsed() < Duration::from_secs(2),
            "Stop took too long"
        );
        assert_eq!(result.unwrap().text, "Once upon a time");
    }

    #[test]
    fn the_online_model_is_never_told_the_chat_is_private() {
        let own = system(Some(crate::settings::DEFAULT_GROUND_RULES), None);
        for s in [online_system(Some(("system", &own))), online_system(None)] {
            assert!(s.contains("Anthropic"), "{s}");
            assert!(!s.contains("private AI"), "{s}");
            assert!(!s.contains(PLACE), "{s}");
            assert!(s.contains("do not call it private"), "{s}");
        }
    }

    #[test]
    fn the_instructions_are_the_place_then_the_rules_then_what_was_looked_up() {
        let rules = crate::settings::DEFAULT_GROUND_RULES;
        let s = system(Some(rules), Some("[Looked up from example.org] Rain."));
        assert!(s.starts_with(PLACE));
        let (a, b, c) = (
            s.find(rules).unwrap(),
            s.find("name the sources").unwrap(),
            s.find("[Looked up").unwrap(),
        );
        assert!(
            a < b && b < c,
            "rules, then their source rule, then the lookup"
        );
        assert_eq!(system(None, None), PLACE);
        assert_eq!(system(Some("  "), None), PLACE);
    }

    #[test]
    fn a_200_turn_chat_fits_and_keeps_the_ground_rules() {
        let rules = "Tell the truth. Never lecture.";
        let mut turns = vec![];
        for i in 0..200 {
            turns.push(ChatTurn {
                id: format!("t{i}"),
                user: i % 2 == 0,
                text: format!("Turn {i}: {}", "words and more words ".repeat(40)),
                sources: vec![],
                ran: None,
                model_words: None,
                at: i,
                version: 1,
                seq: 0,
                gone: false,
                switch_note: None,
                pictures: vec![],
                files: vec![],
            });
        }
        turns.push(ChatTurn {
            id: "last".into(),
            user: true,
            text: "And the newest question?".into(),
            sources: vec![],
            ran: None,
            model_words: None,
            at: 300,
            version: 1,
            seq: 0,
            gone: false,
            switch_note: None,
            pictures: vec![],
            files: vec![],
        });
        let m = messages(
            system(Some(rules), Some("[Fetched: rain tomorrow]")),
            &turns,
        );
        let fitted = fit(&m, 16_384, None);
        assert!(fitted.len() < m.len());
        assert_eq!(fitted[0].0, "system");
        assert!(fitted[0].1.starts_with(PLACE));
        assert!(fitted[0].1.contains(rules));
        assert!(fitted[0].1.contains("rain tomorrow"));
        assert!(fitted[0].1.ends_with(LEFT_OUT));
        assert_eq!(fitted.last().unwrap().1, "And the newest question?");
        assert_eq!(fitted[1].0, "user");
        let chars: usize = fitted.iter().map(|(_, c)| c.chars().count()).sum();
        assert!(chars / 4 <= 16_384 * 3 / 4, "{chars} characters");
        // A short chat is left whole.
        let short = messages(system(Some(rules), None), &turns[195..]);
        assert_eq!(fit(&short, 16_384, None).len(), short.len() - 1);
        // Ollama's own count is used once known.
        assert!(fit(&m, 16_384, Some(0.1)).len() > fitted.len());
    }

    /// Against the PC's real Ollama (run by hand: `--ignored`): the first
    /// words of a warm answer within 2 s.
    #[test]
    #[ignore]
    fn the_real_model_answers_within_two_seconds() {
        let (tx, rx) = mpsc::channel();
        let tx = Mutex::new(tx);
        let t = Talk::start_at(Arc::new(|| "http://127.0.0.1:11434".into()), move |o| {
            let _ = tx.lock().unwrap().send(o);
        });
        let mut j = job("w", "Say hello.");
        j.model = "huihui_ai/Qwen3.6-abliterated:35b-a3b".into();
        t.submit(j).unwrap();
        let _ = until_done(&rx);
        let mut j = job("r", "What's the capital of Norway?");
        j.model = "huihui_ai/Qwen3.6-abliterated:35b-a3b".into();
        let started = Instant::now();
        t.submit(j).unwrap();
        let first = loop {
            if let Out::Piece {
                thinking: false, ..
            } = rx.recv_timeout(Duration::from_secs(60)).unwrap()
            {
                break started.elapsed();
            }
        };
        let (said, _, result) = until_done(&rx);
        eprintln!(
            "first words after {first:?}; answer: {:?}",
            result.as_ref().map(|t| &t.text)
        );
        let _ = said;
        assert!(first < Duration::from_secs(2), "{first:?}");
        assert!(result.unwrap().text.contains("Oslo"));
    }
}
