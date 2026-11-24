//! All tasks and their Claude Code processes. The window calls these
//! methods and draws `tasks`; nothing here draws.

use crate::approvals::{self, Answer, Listener, Pending};
use crate::events::{Event, Picture, user_message_with};
use crate::process::{Launch, Output, Session};
use crate::store::Store;
use crate::task::{Mode, Status, Task, WeeklyUse};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::mpsc::{Receiver, channel};
use std::time::Instant;

pub type Wake = Arc<dyn Fn() + Send + Sync>;

#[derive(Debug, Clone, PartialEq)]
pub enum Notice {
    NeedsYou {
        task: String,
        title: String,
        what: String,
    },
    Finished {
        task: String,
        title: String,
        ok: bool,
    },
}

pub struct Live {
    pub task: Task,
    session: Option<Session>,
    /// Questions waiting for the owner, oldest first.
    pub asks: VecDeque<Pending>,
    /// Messages sent to the running process and turns it has finished, so
    /// input is only closed once every message has been answered.
    sent: u32,
    finished: u32,
    pub(crate) dirty: bool,
    /// Its turn ended but Claude Code is still alive with something it
    /// started (a command run in the background): still working.
    background: bool,
    /// What Claude Code already had running when it started (helpers of
    /// its own): not the task's work.
    baseline: Vec<crate::process::Pkey>,
    bg_checked: Option<Instant>,
    /// A command was started with `run_in_background`: what appears from
    /// now on is not taken for a helper of Claude Code's own.
    freeze: bool,
    /// Checks in a row that found something not in the baseline.
    bg_seen: u8,
    /// The note was said for this turn.
    bg_noted: bool,
    /// The turn's start time was set for this working stretch (a time from
    /// before a restart or a takeover is never shown).
    pub(crate) turn_clocked: bool,
}

impl Live {
    /// Tests only: a task with nothing running.
    #[cfg(test)]
    pub fn for_test(task: Task) -> Live {
        Live {
            task,
            session: None,
            asks: VecDeque::new(),
            sent: 0,
            finished: 0,
            dirty: false,
            background: false,
            baseline: Vec::new(),
            bg_checked: None,
            freeze: false,
            bg_seen: 0,
            turn_clocked: false,
            bg_noted: false,
        }
    }

    pub fn running(&self) -> bool {
        self.session.is_some()
    }

    /// Starts the turn's clock the first moment the run is seen working, and
    /// lets go of it when it stops (so a time from before a restart, or one
    /// a queued message would move, is never shown).
    pub fn clock_turn(&mut self, now: i64) {
        let busy = self.busy();
        clock(busy, &mut self.turn_clocked, &mut self.task.turn_unix, now);
    }

    /// Claude is working on this task right now (not just finishing up).
    pub fn busy(&self) -> bool {
        self.session.is_some()
            && (self.background || matches!(self.task.status, Status::Working | Status::NeedsYou))
    }

    /// A command it started in the background may still be running, or is
    /// just being noticed (seen once, not yet twice).
    pub fn background_pending(&self) -> bool {
        (self.background || self.bg_seen > 0)
            && !matches!(self.task.status, Status::Stopped | Status::Paused)
    }

    /// Something it started in the background is running (seen twice).
    pub fn in_background(&self) -> bool {
        self.background
    }

    /// Claude works on it right now, here or in a session followed from
    /// elsewhere (what "working" counts).
    pub fn working_now(&self) -> bool {
        self.task.working_now(self.busy())
    }
}

pub struct Config {
    pub claude: PathBuf,
    /// The program Claude Code runs for approvals (`<exe> hook`).
    pub hook_exe: PathBuf,
    pub socket: PathBuf,
    pub store_dir: PathBuf,
    /// Extra environment for Claude Code (tests use it for the fake).
    pub env: Vec<(String, String)>,
}

/// How long after its own Claude ended a task is still not "elsewhere": the
/// process list is looked at every few seconds and the process may linger.
pub const OWN_GRACE_SECS: i64 = 20;

pub struct Runner {
    /// Tasks the owner stopped since they last worked (so "stopped" is
    /// never guessed from the notes).
    pub owner_stopped: std::collections::HashSet<String>,
    /// Followed tasks the owner stopped: their transcript may still grow
    /// (the session may not have died), but they stay Stopped until the
    /// owner continues them.
    stopped_followed: std::collections::HashSet<String>,
    /// Task → when (unix seconds) Mímir's own Claude on it last ended. Its
    /// process may still show for a moment, and its last writes are fresh:
    /// neither is another app.
    own_ended: std::collections::HashMap<String, i64>,
    cfg: Config,
    store: Store,
    pub tasks: Vec<Live>,
    approvals: Receiver<Pending>,
    approvals_tx: std::sync::mpsc::Sender<Pending>,
    /// Each task's own approvals socket: its hook asks there, so the task
    /// is known from the socket, never from what the hook claims.
    listeners: std::collections::HashMap<String, Listener>,
    pub weekly: Option<WeeklyUse>,
    /// The PC's ground rules (Settings › Models), sent with every task.
    pub ground_rules: Option<String>,
    /// Ground rules a phone sent with the request being handled: the next
    /// new task keeps them as its own.
    pub next_rules: Option<String>,
    /// The 5-hour window, when Claude Code reports one.
    pub five_hour: Option<WeeklyUse>,
    /// When the figures last changed (unix seconds), for "updated N min ago".
    pub usage_at: Option<i64>,
    /// Messages for a run that just ended: they go once it is collected
    /// (task id, the input line, since when), never by waiting.
    resend: Vec<(String, String, Instant)>,
    /// Tasks told to stop or pause: when their Claude Code is ended if it
    /// has not left by itself (it may ignore the interrupt).
    kill_after: Vec<(String, Instant)>,
    /// Keeps the figures fresh between tasks; None in tests.
    pub usage_poller: Option<crate::usage::Poller>,
    /// Plain-word problems for the window to show once.
    pub problems: Vec<String>,
    /// Things worth a desktop notification; the window takes them.
    pub notices: Vec<Notice>,
    /// Slash commands and skills from the latest Claude Code start.
    pub slash_commands: Vec<String>,
    /// MCP servers from the latest Claude Code start: (name, status).
    pub mcp_servers: Vec<(String, String)>,
    wake: Wake,
    clock: Instant,
    next_id: u32,
}

/// How long a stopped task's Claude Code has to leave after the interrupt.
/// How often an idle task's Claude Code is looked at for work it left
/// running (a read of a few `/proc` files).
const BACKGROUND_LOOK: std::time::Duration = std::time::Duration::from_millis(200);

/// Said once in the task when its turn ended with work still going.
/// What the task says when the owner stops it.
pub const STOPPED_NOTE: &str = "You stopped this task.";

pub const BACKGROUND_NOTE: &str = "Still running something it started in the background.";

const STOP_GRACE: std::time::Duration = std::time::Duration::from_secs(5);

const HOST_LOST: &str = "This task stopped unexpectedly. Press Continue to carry on.";
const CLOSED_WHILE_WORKING: &str =
    "Mímir restarted while this task was working. Press Continue to carry on.";
/// Why a working task can't move yet.
pub const MOVE_WHEN_STOPPED: &str = "Move it when it stops.";
const CONTINUE_TEXT: &str = "Please continue where you left off.";

impl Runner {
    pub fn new(cfg: Config, wake: Wake) -> std::io::Result<Self> {
        let store = Store::new(&cfg.store_dir)?;
        let (tx, rx) = channel();
        let (loaded, bad) = store.load_all();
        let mut problems: Vec<String> = bad
            .iter()
            .map(|p| {
                format!(
                    "A saved task could not be read and was left alone: {}",
                    p.display()
                )
            })
            .collect();
        let mut tasks = vec![];
        let mut attach = vec![];
        for mut t in loaded {
            let mut dirty = false;
            // Claude Code still runs in its host: carried on, not paused.
            if let Some((key, offset)) = t.host.clone() {
                match crate::host::read_record(&key) {
                    Some(r) if crate::host::host_alive(&r) || r.exit.is_some() => {
                        attach.push((t.id.clone(), key, offset));
                        // It said it still ran something in the background:
                        // that stays so until a look finds nothing running
                        // (the line goes then), and is never said twice.
                        let said_busy = t.entries.iter().any(
                            |e| matches!(e, crate::task::Entry::Note(n) if n == BACKGROUND_NOTE),
                        );
                        tasks.push(Live {
                            task: t,
                            session: None,
                            asks: VecDeque::new(),
                            sent: 0,
                            finished: 0,
                            dirty,
                            background: said_busy,
                            baseline: Vec::new(),
                            bg_checked: None,
                            freeze: false,
                            bg_seen: 0,
                            turn_clocked: false,
                            bg_noted: said_busy,
                        });
                        continue;
                    }
                    _ => {
                        t.host = None;
                        dirty = true;
                        if matches!(t.status, Status::Working | Status::NeedsYou) {
                            t.status = Status::Stopped;
                            t.failed = true;
                            t.add_note(HOST_LOST);
                        }
                    }
                }
            }
            if matches!(t.status, Status::Working | Status::NeedsYou) {
                t.status = Status::Paused;
                t.add_note(CLOSED_WHILE_WORKING);
                dirty = true;
            }
            if t.settle_open_steps(0) {
                dirty = true;
            }
            tasks.push(Live {
                task: t,
                session: None,
                asks: VecDeque::new(),
                sent: 0,
                finished: 0,
                dirty,
                background: false,
                baseline: Vec::new(),
                bg_checked: None,
                freeze: false,
                bg_seen: 0,
                turn_clocked: false,
                bg_noted: false,
            });
        }
        let weekly = tasks.iter().rev().find_map(|l| l.task.weekly.clone());
        if !cfg.claude.exists() && cfg.claude.is_absolute() {
            problems.push(
                "Project work is not installed yet. Open Settings to install what it needs.".into(),
            );
        }
        let mut r = Runner {
            owner_stopped: Default::default(),
            stopped_followed: Default::default(),
            own_ended: Default::default(),
            cfg,
            store,
            tasks,
            approvals: rx,
            approvals_tx: tx,
            listeners: std::collections::HashMap::new(),
            weekly,
            ground_rules: None,
            next_rules: None,
            five_hour: None,
            usage_at: None,
            usage_poller: None,
            resend: vec![],
            kill_after: vec![],
            problems,
            notices: vec![],
            slash_commands: vec![],
            mcp_servers: vec![],
            wake,
            clock: Instant::now(),
            next_id: 0,
        };
        crate::host::tidy_old();
        for (id, key, offset) in attach {
            r.attach(&id, &key, offset);
        }
        r.save_dirty();
        Ok(r)
    }

    /// Carries on following a task whose Claude Code ran on in its host
    /// while the service restarted.
    fn attach(&mut self, id: &str, key: &str, offset: u64) {
        // Its permission requests come to this service's socket again.
        let _ = self.hook_settings_for(id);
        let w = self.wake.clone();
        let Some(i) = self.index(id) else { return };
        match Session::attach(key, offset, move || w()) {
            Ok(mut s) => {
                let l = &mut self.tasks[i];
                if matches!(l.task.status, Status::Working | Status::NeedsYou) {
                    // A turn is under way: its result closes the input.
                    l.sent = 1;
                } else {
                    s.close_input();
                }
                l.finished = 0;
                l.session = Some(s);
            }
            Err(_) => {
                let l = &mut self.tasks[i];
                l.task.host = None;
                if matches!(l.task.status, Status::Working | Status::NeedsYou) {
                    l.task.status = Status::Stopped;
                    l.task.failed = true;
                    l.task.add_note(HOST_LOST);
                }
                l.dirty = true;
            }
        }
    }

    /// Looks at the plan's usage page when due (30 min while a task works,
    /// 12 h otherwise) and takes in the answer.
    fn poll_usage(&mut self) {
        let active = self.tasks.iter().any(|l| l.busy());
        let Some(p) = self.usage_poller.as_mut() else {
            return;
        };
        let now = crate::needs::now();
        if let Some(r) = p.poll(now, active) {
            if r.weekly.is_some() {
                self.weekly = r.weekly;
            }
            if r.five_hour.is_some() {
                self.five_hour = r.five_hour;
            }
            self.usage_at = Some(now);
        }
    }

    fn now_ms(&self) -> u64 {
        self.clock.elapsed().as_millis() as u64
    }

    fn index(&self, id: &str) -> Option<usize> {
        self.tasks.iter().position(|l| l.task.id == id)
    }

    fn new_id(&mut self) -> String {
        self.next_id += 1;
        let secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        format!("{secs}-{}-{}", std::process::id(), self.next_id)
    }

    /// Starts a new task in `project` with the owner's first message.
    pub fn new_task(&mut self, project: &Path, text: &str) -> String {
        self.new_task_with(project, text, &[], Mode::Ask, None, None)
    }

    /// Starts a new task with pictures, a mode, a model and an effort.
    pub fn new_task_with(
        &mut self,
        project: &Path,
        text: &str,
        pictures: &[Picture],
        mode: Mode,
        model: Option<String>,
        effort: Option<String>,
    ) -> String {
        let id = self.new_id();
        self.make_task(&id, project, text, mode, model, effort);
        self.send_with(&id, text, pictures);
        id
    }

    /// The task that carries an earlier one on: a plain `title`, `text` as
    /// the first message people see, and `brief` (the summary of the earlier
    /// one) sent along with it, never shown as a message.
    pub fn new_task_continued(
        &mut self,
        project: &Path,
        title: &str,
        text: &str,
        brief: Option<String>,
        mode: Mode,
        model: Option<String>,
    ) -> String {
        let id = self.new_id();
        self.make_task(&id, project, text, mode, model, None);
        self.edit(&id, |t| {
            t.title = title.to_string();
            t.title_by_owner = true;
            t.brief_pending = brief.is_some();
            t.brief = brief;
        });
        self.send_with(&id, text, &[]);
        id
    }

    /// Starts a new task whose first message carries pictures and files
    /// (already checked and cleaned). They are kept first: if that fails, no
    /// task is made.
    pub fn new_task_attached(
        &mut self,
        project: &Path,
        text: &str,
        attached: &crate::task_files::Prepared,
        mode: Mode,
        model: Option<String>,
        effort: Option<String>,
    ) -> Result<String, String> {
        // A file that still has hidden details never goes to an online model.
        if attached.has_uncleaned() && !crate::models::is_free(model.as_deref()) {
            return Err(crate::wipe::NOT_SENT_ONLINE.into());
        }
        let id = self.new_id();
        let dir = crate::task_files::dir_for(&self.data_dir(), &id, false);
        let kept = crate::task_files::write(&dir, attached)?;
        self.make_task(&id, project, text, mode, model, effort);
        let text = crate::task_files::named(text.trim(), &kept.paths);
        self.send_with(&id, &text, &kept.pictures);
        self.note_cleaned(&id, attached);
        Ok(id)
    }

    fn make_task(
        &mut self,
        id: &str,
        project: &Path,
        text: &str,
        mode: Mode,
        model: Option<String>,
        effort: Option<String>,
    ) {
        let title = title_from(text);
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let mut task = Task::new(id.to_string(), title, project.display().to_string(), now);
        task.mode = mode;
        task.model = model;
        task.effort = effort;
        task.ground_rules = self.next_rules.take();
        self.tasks.push(Live {
            task,
            session: None,
            asks: VecDeque::new(),
            sent: 0,
            finished: 0,
            dirty: true,
            background: false,
            baseline: Vec::new(),
            bg_checked: None,
            freeze: false,
            bg_seen: 0,
            turn_clocked: false,
            bg_noted: false,
        });
    }

    /// Sends a message: into the running turn if Claude is working,
    /// otherwise by resuming the task's Claude Code session.
    pub fn send(&mut self, id: &str, text: &str) {
        self.send_with(id, text, &[]);
    }

    /// Sends a message whose pictures and files are kept for the task and
    /// named in the message ("Attached: ..."). If they can't be kept, nothing
    /// is sent, nothing stays on disk and the reason is returned.
    pub fn send_attached(
        &mut self,
        id: &str,
        text: &str,
        pictures: &[Picture],
        files: &[mimir_proto::SentFile],
    ) -> Result<(), String> {
        if pictures.is_empty() && files.is_empty() {
            self.send_with(id, text, pictures);
            return Ok(());
        }
        let incognito = self.index(id).is_some_and(|i| self.tasks[i].task.incognito);
        let prepared = crate::task_files::prepare(pictures, files)?;
        let online = self
            .index(id)
            .is_none_or(|i| !crate::models::is_free(self.tasks[i].task.model.as_deref()));
        if prepared.has_uncleaned() && online {
            return Err(crate::wipe::NOT_SENT_ONLINE.into());
        }
        let dir = crate::task_files::dir_for(&self.data_dir(), id, incognito);
        let kept = crate::task_files::write(&dir, &prepared)?;
        let text = crate::task_files::named(text.trim(), &kept.paths);
        self.send_with(id, &text, &kept.pictures);
        self.note_cleaned(id, &prepared);
        Ok(())
    }

    /// The message with each "Attached file: <path>" line pointing at a
    /// cleaned copy, when the task runs on an online model; a file that can't
    /// be cleaned is not named at all. Also the line to show for each.
    fn cleaned_attached_files(&self, i: usize, id: &str, text: &str) -> (String, Vec<String>) {
        let task = &self.tasks[i].task;
        if crate::models::is_free(task.model.as_deref()) || !text.contains(crate::queue::ATTACHED) {
            return (text.to_string(), vec![]);
        }
        let dir = crate::task_files::dir_for(&self.data_dir(), id, task.incognito);
        let mut notes = vec![];
        let out = crate::queue::map_attached(text, &mut |p| {
            let path = Path::new(p.trim());
            let name = path
                .file_name()
                .map_or_else(|| p.to_string(), |n| n.to_string_lossy().into_owned());
            match crate::task_files::cleaned_copy(&dir, path) {
                Ok((copy, note)) => {
                    notes.push(format!("{name}: {note}"));
                    format!("{}{}", crate::queue::ATTACHED, copy.display())
                }
                Err(why) => {
                    notes.push(format!("{name}: {why}"));
                    format!("{}{name} (not shared)", crate::queue::ATTACHED)
                }
            }
        });
        notes.dedup();
        (out, notes)
    }

    /// A task that moved to an online model: the files kept for it that
    /// still have hidden details are taken away.
    fn drop_local_only_files(&mut self, id: &str) {
        let Some(i) = self.index(id) else { return };
        let t = &self.tasks[i].task;
        if crate::models::is_free(t.model.as_deref()) {
            return;
        }
        let dir = crate::task_files::dir_for(&self.data_dir(), id, t.incognito);
        if crate::task_files::drop_local_only(&dir) > 0 {
            self.note(
                id,
                "Files that still had hidden details were taken away, because this task now \
                 runs online.",
            );
        }
    }

    /// Says under the message what was done to each file ("Lease.pdf: Hidden
    /// details removed.").
    fn note_cleaned(&mut self, id: &str, p: &crate::task_files::Prepared) {
        for (name, line) in p.notes() {
            self.note(id, &format!("{name}: {line}"));
        }
    }

    /// At start-up: deletes the attachments of tasks that no longer exist.
    pub fn sweep_attachments(&self) {
        let ids: Vec<String> = self.tasks.iter().map(|l| l.task.id.clone()).collect();
        crate::task_files::sweep(&self.data_dir(), &ids);
    }

    /// Sends a message with pictures.
    pub fn send_with(&mut self, id: &str, text: &str, pictures: &[Picture]) {
        self.send_inner(id, text, pictures, true);
    }

    /// `show`: the message is added to the conversation as the person's own
    /// words (not for a Continue that has to hand the request over again).
    fn send_inner(&mut self, id: &str, text: &str, pictures: &[Picture], show: bool) {
        let Some(i) = self.index(id) else { return };
        // The person carries on here: the task is theirs from now on.
        if self.tasks[i].task.outside {
            self.tasks[i].task.outside = false;
            // The notes that said it was elsewhere or still to be carried on
            // are no longer true.
            self.tasks[i].task.drop_note(crate::sessions::OPENED_NOTE);
            self.tasks[i].task.drop_note(crate::pcsessions::FOLLOW_NOTE);
            self.tasks[i].dirty = true;
        }
        // Files of the person's own, named in the message: an online model
        // reads a cleaned copy, never the original.
        let (cleaned, file_notes) = self.cleaned_attached_files(i, id, text);
        let text = cleaned.trim();
        if text.is_empty() {
            return;
        }
        let shown = if pictures.is_empty() {
            text.to_string()
        } else {
            let n = pictures.len();
            format!(
                "{text}\n[{n} picture{} attached]",
                if n == 1 { "" } else { "s" }
            )
        };
        // Auto model: pick for this message (it applies when Claude Code
        // starts for it).
        let starting = self.tasks[i].session.is_none();
        let style = crate::auto::Style::of(self.tasks[i].task.model.as_deref());
        if starting && let Some(style) = style {
            let t = &self.tasks[i].task;
            let is_chat = t.project.ends_with("mimir/chats");
            let pick = crate::auto::choose(t, text, is_chat, style);
            if t.auto_choice.as_deref() != Some(pick) {
                let note = format!("Auto model: {} for this.", crate::auto::name(pick));
                let switching = t.auto_choice.is_some();
                let hand_over = switching && crate::auto::hand_over(t, style);
                let t = &mut self.tasks[i].task;
                if hand_over {
                    // A fresh session that starts from a short brief instead
                    // of re-reading the whole conversation.
                    let b = crate::auto::brief(t);
                    t.add_note(&format!(
                        "{note} Mímir carried on with a short summary instead of the whole \
                         chat, to save your limit. See it in ⋯ › Show the summary."
                    ));
                    t.session_id = None;
                    t.brief = Some(b);
                    t.brief_pending = true;
                } else if switching {
                    t.add_note(&format!(
                        "{note} Mímir reads the whole chat again once on it, which uses some \
                         of your limit."
                    ));
                } else {
                    t.add_note(&note);
                }
                t.auto_choice = Some(pick.to_string());
            }
        }
        // New work: "put back" no longer applies (it would wipe it).
        self.tasks[i].task.redo = None;
        // A snapshot for "Undo changes from here".
        let shadow = self.shadow(i);
        let label = crate::runner::title_from(text);
        // Incognito keeps no undo snapshots.
        let snap = if self.tasks[i].task.incognito {
            None
        } else {
            shadow.snapshot(&label)
        };
        if let Some(snap) = snap {
            let at = self.tasks[i].task.entries.len();
            self.tasks[i].task.checkpoints.push((at, snap));
        }
        if show {
            self.tasks[i].task.add_you(&shown);
        } else {
            self.tasks[i].task.status = Status::Working;
            self.tasks[i].task.failed = false;
        }
        for n in &file_notes {
            self.tasks[i].task.add_note(n);
        }
        self.tasks[i].task.plan_doc = None;
        self.tasks[i].dirty = true;
        // After an undo, Claude hears about it with this message.
        let text_owned;
        let text = match self.tasks[i].task.undo_told.take() {
            Some(told) => {
                text_owned = format!("{told}\n\n{text}");
                text_owned.as_str()
            }
            None => text,
        };
        // After a handover the first message carries the brief.
        let line = match self.tasks[i].task.session_id.is_none() {
            true => match self.tasks[i].task.brief.clone() {
                Some(b) if self.tasks[i].task.brief_pending => {
                    self.tasks[i].task.brief_pending = false;
                    user_message_with(&format!("{b}\n\nNow the owner says:\n{text}"), pictures)
                }
                _ => user_message_with(text, pictures),
            },
            false => user_message_with(text, pictures),
        };
        if let Some(s) = self.tasks[i].session.as_mut()
            && s.send_line(&line).is_ok()
        {
            self.tasks[i].sent += 1;
            self.save_dirty();
            return;
        }
        // It just ended: what it wrote last (its conversation's id) is taken
        // in first, so the next start carries the same conversation on. The
        // message waits for that (a moment) instead of the service waiting.
        self.tick();
        // Behind any message already waiting for this task (they keep their
        // order); else into the run that is there now (a fresh one may have
        // started meanwhile); else a new run.
        if self.resend.iter().any(|(t, _, _)| t == id) {
            crate::devlog::log(
                "task",
                "resend",
                vec![("task", id.to_string().into()), ("why", "behind".into())],
            );
            self.resend.push((id.to_string(), line, Instant::now()));
            self.save_dirty();
            return;
        }
        if self.tasks[i].session.is_some() {
            let ok = self.tasks[i]
                .session
                .as_mut()
                .is_some_and(|s| s.send_line(&line).is_ok());
            if ok {
                self.tasks[i].sent += 1;
            } else {
                self.resend.push((id.to_string(), line, Instant::now()));
            }
            self.save_dirty();
            return;
        }
        self.start_and_send(i, &line);
    }

    /// Mímir's own Claude is on the task, or ended just now: whatever a
    /// scan of the PC's processes shows for it is not another app.
    pub fn own_recent(&self, id: &str, now: i64) -> bool {
        self.index(id).is_some_and(|i| self.tasks[i].running())
            || self
                .own_ended
                .get(id)
                .is_some_and(|t| now - t < OWN_GRACE_SECS)
    }

    /// For checks: Mímir's own Claude on a task ended at `at`.
    #[doc(hidden)]
    pub fn own_ended_for_test(&mut self, id: &str, at: i64) {
        self.own_ended.insert(id.to_string(), at);
    }

    /// For checks: what the service knows of background work in memory is
    /// gone, as after a restart of the service (the task's own lines stay).
    #[doc(hidden)]
    pub fn forget_background_for_test(&mut self, id: &str) {
        if let Some(i) = self.index(id) {
            let l = &mut self.tasks[i];
            l.background = false;
            l.bg_seen = 0;
            l.bg_noted = false;
        }
    }

    /// For checks: the process id of a task's run.
    #[doc(hidden)]
    pub fn run_pid_for_test(&self, id: &str) -> Option<u32> {
        let i = self.index(id)?;
        self.tasks[i].session.as_ref().map(|s| s.pid())
    }

    /// For checks: a message waiting for a run that ended, `age` ago.
    #[doc(hidden)]
    pub fn queue_resend_for_test(&mut self, id: &str, text: &str, age: std::time::Duration) {
        let line = user_message_with(text, &[]);
        self.resend.push((
            id.to_string(),
            line,
            Instant::now().checked_sub(age).unwrap_or_else(Instant::now),
        ));
    }

    /// Starts Claude Code again for task `i` and gives it `line`.
    fn start_and_send(&mut self, i: usize, line: &str) {
        // Not running (or input closed): start Claude Code again.
        self.tasks[i].session = None;
        let folder = self.tasks[i].task.project.clone();
        if !Path::new(&folder).is_dir() && self.tasks[i].task.origin.is_some() {
            self.fail(
                i,
                "This task's separate copy was deleted. Use ⋯ › Remove the copy… to carry on \
                 in the main folder.",
            );
            self.save_dirty();
            return;
        }
        if !Path::new(&folder).is_dir() {
            self.fail(
                i,
                &format!(
                    "The project folder {folder} is gone (moved or deleted). Put it back, \
                     or start a new task in its new place."
                ),
            );
            self.save_dirty();
            return;
        }
        let began = Instant::now();
        let started = self.start(i);
        {
            let t = &self.tasks[i].task;
            let mut f: Vec<(&'static str, serde_json::Value)> = vec![
                ("task", t.id.clone().into()),
                ("free", crate::models::is_free(t.model.as_deref()).into()),
                ("ms", (began.elapsed().as_millis() as u64).into()),
            ];
            match &started {
                Ok(s) => {
                    f.push(("host", s.host_key().is_some().into()));
                    f.push(("ok", true.into()));
                }
                Err(e) => {
                    f.push(("err", e.to_string().into()));
                    f.push(("kind", "start".into()));
                }
            }
            crate::devlog::log("task", "start", f);
        }
        match started {
            Ok(mut s) => {
                if s.send_line(line).is_ok() {
                    let l = &mut self.tasks[i];
                    l.task.host = s.host_key().map(|k| (k.to_string(), 0));
                    l.session = Some(s);
                    l.sent = 1;
                    l.finished = 0;
                } else {
                    self.fail(i, "Project work would not take the message.");
                }
            }
            // The card says it in plain words; the raw text sits behind its "Show details" (view_task). lint:log-only
            Err(e) => self.fail(i, &format!("Project work could not start: {e}")),
        }
        self.save_dirty();
    }

    /// Messages that waited for a run to be collected go now (or after two
    /// seconds, whatever its state).
    fn send_resends(&mut self) {
        if self.resend.is_empty() {
            return;
        }
        let mut keep = vec![];
        let mut blocked: std::collections::HashSet<String> = Default::default();
        for (id, line, since) in std::mem::take(&mut self.resend) {
            let Some(i) = self.index(&id) else { continue };
            if blocked.contains(&id) {
                keep.push((id, line, since));
                continue;
            }
            // The run that is there now takes it if it can (it may be a
            // fresh one an earlier message started): that run is never
            // replaced while it works.
            let taken = self.tasks[i]
                .session
                .as_mut()
                .is_some_and(|s| s.send_line(&line).is_ok());
            if taken {
                self.tasks[i].sent += 1;
                self.tasks[i].dirty = true;
            } else if self.tasks[i].session.is_none()
                || since.elapsed() >= std::time::Duration::from_secs(2)
            {
                // Only a run that has ended (or never answers) is replaced.
                self.start_and_send(i, &line);
            } else {
                blocked.insert(id.clone());
                keep.push((id, line, since));
            }
        }
        self.resend = keep;
    }

    fn start(&mut self, i: usize) -> std::io::Result<Session> {
        let id = self.tasks[i].task.id.clone();
        let settings = self.hook_settings_for(&id);
        let t = &self.tasks[i].task;
        // A free model: Claude Code talks to Ollama on this PC instead.
        let free = t.model.as_deref().and_then(crate::models::ollama_name);
        // The service wakes the PC's model before a call that needs it
        // (never here, where it would hold the service's thread).
        if free.is_some() {
            crate::models::wake();
        }
        let mut env = self.cfg.env.clone();
        let incognito = t.incognito;
        // When each free model was last used, so unused ones can go (never for
        // Incognito, which keeps nothing).
        if let (Some(name), false) = (free, incognito) {
            crate::keep_current::record_free_use(name);
        }
        if incognito {
            env.extend(crate::incognito::env(&t.id));
        } else if free.is_some() {
            env.extend(crate::models::launch_env());
        }
        let settings = if incognito {
            crate::incognito::deny_web(&settings)
        } else {
            settings
        };
        let wrap = if incognito {
            crate::incognito::wrap(&crate::paths::own_program(), &t.id)
        } else {
            vec![]
        };
        let launch = Launch {
            // Incognito keeps nothing, so it never outlives the service.
            host: (crate::host::enabled() && !incognito).then(|| crate::host::new_key(&t.id)),
            program: self.cfg.claude.clone(),
            project: PathBuf::from(&t.project),
            resume: t.session_id.clone(),
            settings: Some(settings),
            group: Some(crate::groups::task_group(&t.id)),
            env,
            model: match crate::auto::Style::of(t.model.as_deref()) {
                _ if free.is_some() => free.map(str::to_string),
                Some(_) => t
                    .auto_choice
                    .clone()
                    .or_else(|| Some(crate::auto::LADDER[1].to_string())),
                _ => t.model.clone(),
            },
            effort: t.effort.clone(),
            permission_mode: Some(t.mode.cli().to_string()),
            wrap,
            // A phone's own ground rules win over the PC's.
            ground_rules: t.ground_rules.clone().or_else(|| self.ground_rules.clone()),
        };
        let w = self.wake.clone();
        Session::start(&launch, move || w())
    }

    fn fail(&mut self, i: usize, why: &str) {
        let l = &mut self.tasks[i];
        l.task.add_note(why);
        l.task.status = Status::Stopped;
        l.task.failed = true;
        l.dirty = true;
    }

    /// Ends the current turn; the task waits for Continue.
    pub fn pause(&mut self, id: &str) {
        self.halt(id, Status::Paused, "Paused. Press Continue to carry on.");
    }

    /// Ends the current turn; the task is finished unless the owner writes
    /// again.
    pub fn stop(&mut self, id: &str) {
        self.owner_stopped.insert(id.to_string());
        self.halt(id, Status::Stopped, STOPPED_NOTE);
    }

    /// A task carried on in a new one ("Start fresh with a summary"): what
    /// still works on it ends, and it says `note`, never "You stopped this
    /// task" (the person did not). A task that was quiet keeps its state.
    pub fn carried_on(&mut self, id: &str, note: &str) {
        let Some(i) = self.index(id) else { return };
        if self.tasks[i].busy() {
            self.owner_stopped.insert(id.to_string());
            self.halt(id, Status::Stopped, note);
        } else {
            self.tasks[i].task.drop_note(BACKGROUND_NOTE);
            self.tasks[i].task.add_note(note);
            self.tasks[i].dirty = true;
            self.save_dirty();
        }
    }

    /// A task that followed a session running elsewhere, whose Claude Code
    /// the owner ended: it reads Stopped, with `note`.
    pub fn stopped_elsewhere(&mut self, id: &str, note: &str) {
        self.owner_stopped.insert(id.to_string());
        self.stopped_followed.insert(id.to_string());
        self.set_elsewhere(id, false);
        self.halt(id, Status::Stopped, note);
    }

    fn halt(&mut self, id: &str, status: Status, note: &str) {
        let Some(i) = self.index(id) else { return };
        let l = &mut self.tasks[i];
        while let Some(p) = l.asks.pop_front() {
            p.answer(Answer::Halted);
        }
        l.task.status = status;
        l.task.failed = false;
        l.task.drop_note(BACKGROUND_NOTE);
        // The reply it was writing comes first, then the note.
        if !l.task.live.trim().is_empty() {
            let text = std::mem::take(&mut l.task.live);
            l.task.entries.push(crate::task::Entry::Claude(text));
        }
        l.task.add_note(note);
        l.dirty = true;
        let had_background = std::mem::take(&mut l.background);
        if let Some(s) = &mut l.session {
            if had_background {
                // Nothing of a turn is left to wind down: end it all now.
                s.kill_tree();
            } else {
                s.interrupt();
            }
            self.kill_after.retain(|(t, _)| t != id);
            self.kill_after
                .push((id.to_string(), Instant::now() + STOP_GRACE));
        } else if let Some((key, _)) = l.task.host.clone() {
            // Its run is in a host the service has not (or not yet) joined.
            crate::host::kill_run(&key);
        }
        // A message that waited for a run to end must not start one now.
        self.resend.retain(|(t, _, _)| t != id);
        self.save_dirty();
    }

    /// Ends the Claude Code of a stopped or paused task that ignored the
    /// interrupt for [`STOP_GRACE`].
    fn end_stuck_runs(&mut self) {
        if self.kill_after.is_empty() {
            return;
        }
        let now = Instant::now();
        let due: Vec<String> = self
            .kill_after
            .iter()
            .filter(|(_, at)| *at <= now)
            .map(|(t, _)| t.clone())
            .collect();
        self.kill_after.retain(|(_, at)| *at > now);
        for id in due {
            let Some(i) = self.index(&id) else { continue };
            if matches!(self.tasks[i].task.status, Status::Stopped | Status::Paused)
                && let Some(s) = self.tasks[i].session.as_mut()
                && s.poll_exit().is_none()
            {
                s.kill();
            }
        }
    }

    /// The mode applies at once: a running Claude Code is told directly.
    pub fn set_mode(&mut self, id: &str, mode: Mode) {
        self.edit(id, |t| t.mode = mode);
        if let Some(i) = self.index(id)
            && let Some(s) = self.tasks[i].session.as_mut()
            && s.set_permission_mode(mode.cli()).is_err()
        {
            self.restart_when_idle(i);
        }
    }

    /// Model and effort apply from Claude Code's next start: an idle one is
    /// ended now so the next message starts it with them.
    pub fn set_model(&mut self, id: &str, model: Option<String>) {
        self.edit(id, |t| t.model = model);
        self.drop_local_only_files(id);
        if let Some(i) = self.index(id) {
            self.restart_when_idle(i);
        }
    }

    pub fn set_effort(&mut self, id: &str, effort: Option<String>) {
        self.edit(id, |t| t.effort = effort);
        if let Some(i) = self.index(id) {
            self.restart_when_idle(i);
        }
    }

    fn restart_when_idle(&mut self, i: usize) {
        let l = &mut self.tasks[i];
        if !l.busy()
            && let Some(mut s) = l.session.take()
        {
            s.kill();
        }
    }

    pub fn rename(&mut self, id: &str, title: &str) {
        let title = title.trim();
        if !title.is_empty() {
            self.edit(id, |t| {
                t.title = title.to_string();
                t.title_by_owner = true;
            });
        }
    }

    /// Claude Code's history file for the task's conversation.
    pub fn transcript(&self, id: &str) -> Option<PathBuf> {
        let t = &self.tasks[self.index(id)?].task;
        let sid = t.session_id.as_ref()?;
        let p = crate::sessions::session_file(Path::new(&t.project), sid);
        if p.exists() {
            return Some(p);
        }
        crate::sessions::find_session(sid).or(Some(p))
    }

    /// Moves a task to another project folder, conversation and all: its
    /// Claude Code history moves to the new folder's place, so the next
    /// message carries on there with `--resume`. Never while Claude works.
    /// Undo points stay behind: they were of the old folder's files.
    pub fn move_to(&mut self, id: &str, project: &str) -> Result<(), String> {
        let project = crate::paths::clean_path(project);
        let project = project.as_str();
        self.can_move(id, project)?;
        let t = &self.tasks[self.index(id).ok_or("That task is gone.")?].task;
        let session = t.session_id.clone();
        let from = t.project.clone();
        // An idle Claude Code still runs in the old folder: it ends, and the
        // next message starts one in the new folder.
        self.end_session(id);
        if let Some(sid) = &session {
            crate::sessions::move_session(sid, Path::new(&from), Path::new(project))
                .map_err(|e| format!("The conversation could not be moved: {e}."))?;
        }
        self.edit(id, |t| {
            t.project = project.to_string();
            t.checkpoints.clear();
            t.redo = None;
            // Moved by the owner: the place is theirs, the guess's note goes.
            if let Some(n) = t.placement_note.take() {
                crate::placement::remove(&n);
            }
            t.placed = None;
        });
        Ok(())
    }

    /// Why a task can't move to `project` now, if anything stands in the
    /// way; nothing is changed.
    pub fn can_move(&self, id: &str, project: &str) -> Result<(), String> {
        let i = self.index(id).ok_or("That task is gone.")?;
        let l = &self.tasks[i];
        let t = &l.task;
        if crate::paths::same(&t.project, project) {
            return Err("It's already in that project.".into());
        }
        if l.busy() {
            return Err(MOVE_WHEN_STOPPED.into());
        }
        if t.incognito {
            return Err("A Whisper task stays where it is.".into());
        }
        if t.origin.is_some() {
            return Err(
                "This task works in a separate copy. Bring its changes in or \
                        remove the copy first, then move it."
                    .into(),
            );
        }
        if t.from_phone.is_some() {
            return Err("This conversation runs with the project's Remote Control, \
                        so it stays in that project."
                .into());
        }
        if t.phone_too {
            return Err("Turn off Phone too for this task first.".into());
        }
        if !Path::new(project).is_dir() {
            return Err("That project folder isn't there any more.".into());
        }
        if let Some(sid) = &t.session_id {
            // A Claude Code in a terminal still on it would lose its place.
            if !l.running()
                && crate::sessions::in_use(
                    &self.transcript(id).unwrap_or_default(),
                    sid,
                    Some(Path::new(&t.project)),
                    false,
                )
            {
                return Err("It is still open in a terminal. Move it when \
                     it stops."
                    .into());
            }
            crate::sessions::can_move(sid, Path::new(&t.project), Path::new(project))?;
        }
        Ok(())
    }

    /// The environment Claude Code gets from now on (the desktop's, as the
    /// window reports it).
    pub fn set_env(&mut self, env: Vec<(String, String)>) {
        self.cfg.env = env;
    }

    /// This task's approvals socket: `tasks/<first 12 hex of SHA-256(id)>.sock`
    /// beside the old shared one (short, as socket paths are limited).
    pub fn task_socket(&self, id: &str) -> PathBuf {
        if self.index(id).is_some_and(|i| self.tasks[i].task.incognito) {
            return crate::incognito::approvals_socket(id);
        }
        use sha2::Digest;
        let h = sha2::Sha256::digest(id.as_bytes());
        let hex: String = h.iter().take(6).map(|b| format!("{b:02x}")).collect();
        self.cfg
            .socket
            .parent()
            .unwrap_or(Path::new("/tmp"))
            .join("tasks")
            .join(format!("{hex}.sock"))
    }

    /// The `--settings` that sends this task's OK requests to its own
    /// socket, which listens from now on.
    pub fn hook_settings_for(&mut self, id: &str) -> String {
        let socket = self.task_socket(id);
        if !self.listeners.contains_key(id) {
            let w = self.wake.clone();
            match approvals::serve_task(&socket, id, self.approvals_tx.clone(), move || w()) {
                Ok(l) => {
                    self.listeners.insert(id.to_string(), l);
                }
                Err(e) => self.problem(&format!(
                    "Mímir can't ask you about this task right now. Restart Mímir to try again. ({})",
                    e
                )),
            }
        }
        approvals::hook_settings(&self.cfg.hook_exe, id, &socket)
    }

    /// Reads the task's conversation again from Claude Code's history file
    /// (after it was carried on elsewhere, such as on the phone). With a
    /// note, the task ends as Done with it; without, the status follows the
    /// conversation (Working while Claude has not finished).
    pub fn reload_from_session(&mut self, id: &str, note: &str) -> Result<(), String> {
        let path = self
            .transcript(id)
            .ok_or("This task has no conversation yet.")?;
        let i = self.index(id).ok_or("That task is gone.")?;
        let t = &self.tasks[i].task;
        let past = crate::sessions::PastSession {
            id: t.session_id.clone().unwrap_or_default(),
            path,
            first: String::new(),
            modified_unix: 0,
            messages: 0,
        };
        let fresh = crate::sessions::to_task(&past, Path::new(&t.project), t.id.clone())
            .map_err(|e| format!("The conversation could not be read back: {e}"))?;
        let t = &mut self.tasks[i].task;
        t.entries = fresh.entries;
        // to_task ends with its own "opened from an earlier session" note.
        if matches!(t.entries.last(), Some(crate::task::Entry::Note(_))) {
            t.entries.pop();
        }
        // Undo points were for the old list of entries.
        t.checkpoints.clear();
        if note.is_empty() {
            let waiting = !self.tasks[i].asks.is_empty();
            let t = &mut self.tasks[i].task;
            t.status = if waiting {
                Status::NeedsYou
            } else if matches!(t.entries.last(), Some(crate::task::Entry::Claude(_)) | None) {
                Status::Done
            } else {
                Status::Working
            };
        } else {
            t.add_note(note);
            t.status = Status::Done;
        }
        self.tasks[i].dirty = true;
        self.save_dirty();
        Ok(())
    }

    /// Marks a task a phone asked the PC to start.
    pub fn set_started_on_phone(&mut self, id: &str) {
        self.edit(id, |t| t.started_on_phone = true);
    }

    /// Marks a task as a session a keeper started (from the phone or
    /// claude.ai), opened at `url`; its "opened from an earlier session"
    /// note goes, as it carries on live.
    pub fn follow_from_phone(&mut self, id: &str, url: &str) {
        self.edit(id, |t| {
            t.from_phone = Some(url.to_string());
            if matches!(t.entries.last(), Some(crate::task::Entry::Note(_))) {
                t.entries.pop();
            }
        });
    }

    /// True while a followed task the owner stopped is still Stopped (it
    /// lets go of the mark once the task moves on).
    pub fn held_stopped(&mut self, id: &str) -> bool {
        if !self.stopped_followed.contains(id) {
            return false;
        }
        let stopped = self
            .index(id)
            .is_some_and(|i| self.tasks[i].task.status == Status::Stopped);
        if !stopped {
            self.stopped_followed.remove(id);
        }
        stopped
    }

    /// Reads what a followed session's transcript gained since byte `from`
    /// (from 0: all of it, afresh). Where the next look starts.
    pub fn follow_session(&mut self, id: &str, from: u64) -> Result<u64, String> {
        let path = self
            .transcript(id)
            .ok_or("This task has no conversation yet.")?;
        let i = self.index(id).ok_or("That task is gone.")?;
        // The owner stopped it: it reads Stopped until they continue it.
        if self.held_stopped(id) {
            return Ok(std::fs::metadata(&path).map(|m| m.len()).unwrap_or(from));
        }
        let waiting = !self.tasks[i].asks.is_empty();
        let t = &mut self.tasks[i].task;
        if from == 0 {
            t.entries_dropped = 0;
            t.entries.clear();
            t.checkpoints.clear();
        }
        let at = crate::sessions::follow(t, &path, from)
            .map_err(|e| format!("The conversation could not be read: {e}"))?;
        // Only the tail the window shows stays in memory.
        crate::sessions::keep_tail(t);
        if at == from && from != 0 {
            return Ok(at);
        }
        t.status = if waiting {
            Status::NeedsYou
        } else if matches!(t.entries.last(), Some(crate::task::Entry::Claude(_)) | None) {
            Status::Done
        } else {
            Status::Working
        };
        self.tasks[i].dirty = true;
        self.save_dirty();
        Ok(at)
    }

    /// When the task's conversation last changed. True if that changed it
    /// (kept in memory; saved with the task's next change).
    pub fn set_active(&mut self, id: &str, unix: Option<i64>) -> bool {
        let Some(i) = self.index(id) else {
            return false;
        };
        let t = &mut self.tasks[i].task;
        // Moved on by less than 15 s: not worth telling every window and
        // phone again.
        let close = match (t.active_unix, unix) {
            (Some(o), Some(n)) => n >= o && n - o < 15,
            (None, None) => true,
            _ => false,
        };
        if close {
            return false;
        }
        t.active_unix = unix;
        true
    }

    /// Marks a task whose conversation is going on outside Mímir
    /// (or no longer). True if that changed it.
    pub fn set_elsewhere(&mut self, id: &str, on: bool) -> bool {
        let Some(i) = self.index(id) else {
            return false;
        };
        let t = &mut self.tasks[i].task;
        let changed = t.live_elsewhere != on;
        t.live_elsewhere = on;
        // Found outside Mímir: it stays so until the person acts on it here.
        if on && !t.outside {
            t.outside = true;
            self.tasks[i].dirty = true;
        }
        changed
    }

    /// A title that is system text gives way to Claude Code's own title for
    /// the conversation, or the latest real thing asked. True if it changed.
    pub fn fix_title(&mut self, id: &str) -> bool {
        let Some(i) = self.index(id) else {
            return false;
        };
        if !crate::task::system_like(&self.tasks[i].task.title) {
            return false;
        }
        let better = self
            .transcript(id)
            .and_then(|p| crate::sessions::ai_title(&p))
            .or_else(|| self.tasks[i].task.real_title())
            .or_else(|| {
                self.transcript(id)
                    .and_then(|p| crate::sessions::first_ask(&p))
                    .filter(|f| !crate::task::system_like(f))
                    .map(|f| title_from(&f))
            });
        match better {
            Some(t) if !t.is_empty() => {
                self.edit(id, |task| task.title = t);
                true
            }
            _ => false,
        }
    }

    /// A followed session that went quiet while it seemed to work has
    /// finished. True if that changed its state.
    pub fn settle_followed(&mut self, id: &str) -> bool {
        let Some(i) = self.index(id) else {
            return false;
        };
        if self.tasks[i].task.status != Status::Working {
            return false;
        }
        self.edit(id, |t| t.status = Status::Done);
        true
    }

    /// Ends Claude Code for this task here (another session takes over).
    pub fn end_session(&mut self, id: &str) {
        if let Some(i) = self.index(id)
            && let Some(mut s) = self.tasks[i].session.take()
        {
            s.kill();
        }
    }

    /// Moves a task to another model, starting afresh from a short brief of
    /// the work so far (a free model's room is too small for the whole
    /// conversation, and another model's history can't be resumed).
    pub fn hand_to(&mut self, id: &str, model: Option<String>) {
        self.edit(id, |t| {
            let did_work = t.entries.iter().any(|e| {
                matches!(
                    e,
                    crate::task::Entry::Claude(_) | crate::task::Entry::Step(_)
                )
            });
            t.model = model;
            t.auto_choice = None;
            if did_work {
                t.brief = Some(crate::auto::brief(t));
                t.brief_pending = true;
            }
            t.session_id = None;
        });
        self.drop_local_only_files(id);
        if let Some(i) = self.index(id)
            && let Some(mut s) = self.tasks[i].session.take()
        {
            s.kill();
        }
    }

    /// Claude's limit ran out in these tasks (each with when it resets, 0
    /// if Claude Code didn't say); each is told once.
    pub fn take_limit_hits(&mut self) -> Vec<(String, i64)> {
        self.tasks
            .iter_mut()
            .filter_map(|l| l.task.limit_hit.take().map(|r| (l.task.id.clone(), r)))
            .collect()
    }

    /// Sets what Claude's limit running out means for a task.
    pub fn set_ran_out(&mut self, id: &str, r: Option<mimir_proto::RanOut>, needs_you: bool) {
        self.edit(id, |t| {
            t.ran_out = r;
            if needs_you {
                t.status = Status::NeedsYou;
            }
        });
    }

    /// Adds a note to a task's conversation.
    pub fn note(&mut self, id: &str, text: &str) {
        self.edit(id, |t| t.add_note(text));
    }

    /// Turns "Phone too" on or off for a task (saved with it).
    pub fn set_phone_too(&mut self, id: &str, on: bool) {
        self.edit(id, |t| t.phone_too = on);
    }

    /// The owner says Claude's question needs no reply.
    pub fn dismiss_question(&mut self, id: &str) {
        self.edit(id, |t| t.question_dismissed = Some(t.entries.len()));
    }

    /// Archiving hides a task, so not while it works or waits for an OK.
    pub fn set_archived(&mut self, id: &str, archived: bool) {
        if archived && self.index(id).is_some_and(|i| self.tasks[i].busy()) {
            self.problem("Pause or stop the task before archiving it.");
            return;
        }
        self.edit(id, |t| {
            t.archived = archived;
            // A guess nobody confirmed leaves no note behind.
            if archived && let Some(n) = t.placement_note.take() {
                crate::placement::remove(&n);
                t.placed = None;
            }
        });
    }

    /// A chat with no project was filed in this task's project.
    pub fn set_placed(
        &mut self,
        id: &str,
        placed: Option<mimir_proto::Placed>,
        note: Option<String>,
    ) {
        self.edit(id, |t| {
            if let Some(old) = t.placement_note.take()
                && note.as_deref() != Some(old.as_str())
            {
                crate::placement::remove(&old);
            }
            t.placed = placed;
            t.placement_note = note;
        });
    }

    /// Adds a plain-word problem for the window, once.
    pub fn problem(&mut self, msg: &str) {
        if !self.problems.iter().any(|p| p == msg) {
            self.problems.push(msg.to_string());
        }
    }

    /// Approves the plan Claude wrote and lets it work in `mode`.
    pub fn approve_plan(&mut self, id: &str, mode: Mode) {
        self.edit(id, |t| t.mode = mode);
        self.send(id, "The plan is approved. Carry it out now.");
    }

    /// Opens a session started elsewhere as a task. Returns its id, or the
    /// id of the task that already has that session.
    pub fn open_past(
        &mut self,
        project: &Path,
        s: &crate::sessions::PastSession,
    ) -> std::io::Result<String> {
        if let Some(l) = self
            .tasks
            .iter()
            .find(|l| l.task.session_id.as_deref() == Some(s.id.as_str()))
        {
            return Ok(l.task.id.clone());
        }
        let id = self.new_id();
        let task = crate::sessions::to_task(s, project, id.clone())?;
        self.tasks.push(Live {
            task,
            session: None,
            asks: VecDeque::new(),
            sent: 0,
            finished: 0,
            dirty: true,
            background: false,
            baseline: Vec::new(),
            bg_checked: None,
            freeze: false,
            bg_seen: 0,
            turn_clocked: false,
            bg_noted: false,
        });
        self.save_dirty();
        Ok(id)
    }

    fn edit(&mut self, id: &str, f: impl FnOnce(&mut Task)) {
        if let Some(i) = self.index(id) {
            f(&mut self.tasks[i].task);
            self.tasks[i].dirty = true;
            self.save_dirty();
        }
    }

    pub fn resume(&mut self, id: &str) {
        // A run that never got a conversation has nothing to carry on: it
        // gets the person's request again, without a second copy of their
        // message in the conversation.
        let again = self.index(id).and_then(|i| {
            let t = &self.tasks[i].task;
            if t.session_id.is_some() {
                return None;
            }
            t.entries.iter().rev().find_map(|e| match e {
                crate::task::Entry::You(x) if !x.trim().is_empty() && x.trim() != CONTINUE_TEXT => {
                    Some(x.clone())
                }
                _ => None,
            })
        });
        match again {
            Some(text) => self.send_inner(id, &text, &[], false),
            None => self.send(id, CONTINUE_TEXT),
        }
    }

    fn data_dir(&self) -> PathBuf {
        self.cfg
            .store_dir
            .parent()
            .unwrap_or(&self.cfg.store_dir)
            .to_path_buf()
    }

    /// A new task that works in its own copy of `project` (a git worktree
    /// on its own branch), so it never edits the same files as another task.
    #[allow(clippy::too_many_arguments)]
    pub fn new_task_in_copy(
        &mut self,
        project: &Path,
        text: &str,
        pictures: &[Picture],
        attached: &crate::task_files::Prepared,
        mode: Mode,
        model: Option<String>,
        effort: Option<String>,
    ) -> Result<String, String> {
        let name = format!("{}", self.tasks.len() + 1)
            + &std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| format!("-{}", d.as_secs() % 100_000))
                .unwrap_or_default();
        let (copy, branch) = crate::copies::create(project, &self.data_dir(), &name)?;
        let made = if attached.is_empty() {
            Ok(self.new_task_with(&copy, text, pictures, mode, model, effort))
        } else {
            self.new_task_attached(&copy, text, attached, mode, model, effort)
        };
        let id = match made {
            Ok(id) => id,
            Err(e) => {
                let _ = crate::copies::remove(project, &copy, &branch);
                return Err(e);
            }
        };
        if let Some(i) = self.index(&id) {
            let t = &mut self.tasks[i].task;
            t.origin = Some(project.display().to_string());
            t.copy_branch = Some(branch.clone());
            let name = project
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            t.add_note(&format!(
                "Working in a separate copy of {name} (branch {branch}). Changes you haven't \
                 committed in {name} aren't in it. When done, use \"Bring the changes into \
                 {name}\" below the conversation."
            ));
            self.tasks[i].dirty = true;
            self.save_dirty();
        }
        Ok(id)
    }

    /// Commits the copy's work and merges it into the main folder.
    pub fn bring_in(&mut self, id: &str) -> Result<String, String> {
        let i = self.index(id).ok_or("That task is gone.")?;
        if self.tasks[i].busy() {
            return Err("Pause or stop the task first.".into());
        }
        let t = &self.tasks[i].task;
        let (Some(origin), Some(branch)) = (t.origin.clone(), t.copy_branch.clone()) else {
            return Err("This task is not in a separate copy.".into());
        };
        let report =
            crate::copies::bring_in(Path::new(&origin), Path::new(&t.project), &branch, &t.title)?;
        self.tasks[i].task.add_note(&report);
        self.tasks[i].dirty = true;
        self.save_dirty();
        Ok(report)
    }

    /// Deletes the task's copy and its branch; the task carries on in the
    /// main folder.
    pub fn remove_copy(&mut self, id: &str) -> Result<(), String> {
        let i = self.index(id).ok_or("That task is gone.")?;
        if self.tasks[i].busy() {
            return Err("Pause or stop the task first.".into());
        }
        let t = &self.tasks[i].task;
        let (Some(origin), Some(branch)) = (t.origin.clone(), t.copy_branch.clone()) else {
            return Err("This task is not in a separate copy.".into());
        };
        crate::copies::remove(Path::new(&origin), Path::new(&t.project), &branch)?;
        // Its undo snapshots were of the copy, which is gone.
        let _ = std::fs::remove_dir_all(self.data_dir().join("checkpoints").join(format!(
            "{}.git",
            crate::sessions::folder_name(Path::new(&t.project))
        )));
        let t = &mut self.tasks[i].task;
        t.checkpoints.clear();
        t.redo = None;
        t.project = origin;
        t.origin = None;
        t.copy_branch = None;
        // A new folder: Claude starts a fresh session there next time,
        // from a brief of the task so far.
        t.session_id = None;
        t.brief = Some(crate::auto::brief(t));
        t.brief_pending = true;
        t.add_note(&format!(
            "Removed the separate copy (branch {branch}). This task now works in the main folder."
        ));
        self.tasks[i].dirty = true;
        self.save_dirty();
        Ok(())
    }

    fn shadow(&self, i: usize) -> crate::checkpoints::Shadow {
        crate::checkpoints::Shadow::new(&self.data_dir(), Path::new(&self.tasks[i].task.project))
    }

    fn checkpoint(&self, i: usize, entry: usize) -> Option<String> {
        self.tasks[i]
            .task
            .checkpoints
            .iter()
            .find(|(at, _)| *at == entry)
            .map(|(_, c)| c.clone())
    }

    /// Whether the owner's message at `entry` has a snapshot to undo to.
    pub fn can_undo(&self, id: &str, entry: usize) -> bool {
        self.index(id)
            .is_some_and(|i| self.checkpoint(i, entry).is_some())
    }

    /// What "Undo changes from here" would change, file by file.
    pub fn undo_preview(
        &self,
        id: &str,
        entry: usize,
    ) -> Result<Vec<crate::checkpoints::Change>, String> {
        let i = self.index(id).ok_or("That task is gone.")?;
        let snap = self
            .checkpoint(i, entry)
            .ok_or("There is no snapshot from before that message.")?;
        self.shadow(i)
            .changes_since(&snap)
            .ok_or_else(|| "Could not compare the files.".to_string())
    }

    /// Puts the project's files back as they were just before the owner's
    /// message at `entry`.
    pub fn undo(&mut self, id: &str, entry: usize) -> Result<usize, String> {
        let i = self.index(id).ok_or("That task is gone.")?;
        if self.tasks[i].busy() {
            return Err("Pause or stop the task before undoing.".into());
        }
        let snap = self
            .checkpoint(i, entry)
            .ok_or("There is no snapshot from before that message.")?;
        let (before, n) = self.shadow(i).restore(&snap)?;
        let said = match self.tasks[i].task.entries.get(entry) {
            Some(crate::task::Entry::You(t)) => title_from(t),
            _ => String::new(),
        };
        let t = &mut self.tasks[i].task;
        t.redo = Some(before);
        t.add_note(&format!(
            "Undid {n} file change{} made since your message \"{said}\". \
             Put them back from ⋯ › Put back the undone changes.",
            if n == 1 { "" } else { "s" }
        ));
        t.undo_told = Some(format!(
            "(Note from Mímir: the owner undid the file changes made after their \
             message \"{said}\". Those files are back as they were then. Read files again \
             before relying on earlier work.)"
        ));
        self.tasks[i].dirty = true;
        self.save_dirty();
        Ok(n)
    }

    /// What "Put back the undone changes" would change.
    pub fn redo_preview(&self, id: &str) -> Result<Vec<crate::checkpoints::Change>, String> {
        let i = self.index(id).ok_or("That task is gone.")?;
        let snap = self.tasks[i]
            .task
            .redo
            .clone()
            .ok_or("Nothing was undone.")?;
        self.shadow(i)
            .changes_since(&snap)
            .ok_or_else(|| "Could not compare the files.".to_string())
    }

    /// Puts back what the last undo took away.
    pub fn redo(&mut self, id: &str) -> Result<usize, String> {
        let i = self.index(id).ok_or("That task is gone.")?;
        if self.tasks[i].busy() {
            return Err("Pause or stop the task first.".into());
        }
        let snap = self.tasks[i]
            .task
            .redo
            .clone()
            .ok_or("Nothing was undone.")?;
        let (_, n) = self.shadow(i).restore(&snap)?;
        let t = &mut self.tasks[i].task;
        t.redo = None;
        t.add_note(&format!(
            "Put back {n} undone file change{}.",
            if n == 1 { "" } else { "s" }
        ));
        t.undo_told = Some(
            "(Note from Mímir: the owner put back the file changes they had undone. \
             Read files again before relying on earlier work.)"
                .into(),
        );
        self.tasks[i].dirty = true;
        self.save_dirty();
        Ok(n)
    }

    /// Answers the oldest question for this task.
    pub fn answer(&mut self, id: &str, answer: Answer) {
        let Some(i) = self.index(id) else { return };
        let now = self.now_ms();
        let l = &mut self.tasks[i];
        l.task.answered(now);
        if let Some(p) = l.asks.pop_front() {
            if answer == Answer::Always {
                let rule = p.request.always_rule_text();
                l.task
                    .add_note(&format!("Always allowed in this project: {rule}"));
            }
            p.answer(answer);
        }
        if l.asks.is_empty() && l.task.status == Status::NeedsYou {
            l.task.status = Status::Working;
        }
        l.dirty = true;
        self.save_dirty();
    }

    /// Starts an Incognito task (a free model in a sealed space). Its id.
    pub fn new_incognito(
        &mut self,
        project: &Path,
        text: &str,
        model: &str,
        mode: Mode,
    ) -> Result<String, String> {
        if !crate::models::is_free(Some(model)) {
            return Err("Whisper mode runs only models on this PC.".into());
        }
        let id = self.new_id();
        crate::devlog::mark_whisper(&id);
        crate::incognito::prepare(&id).map_err(|e| format!("Whisper mode couldn't start: {e}"))?;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let mut task = Task::new(
            id.clone(),
            title_from(text),
            project.display().to_string(),
            now,
        );
        task.incognito = true;
        task.ground_rules = self.next_rules.take();
        task.mode = mode;
        task.model = Some(model.to_string());
        self.tasks.push(Live {
            task,
            session: None,
            asks: VecDeque::new(),
            sent: 0,
            finished: 0,
            dirty: false,
            background: false,
            baseline: Vec::new(),
            bg_checked: None,
            freeze: false,
            bg_seen: 0,
            turn_clocked: false,
            bg_noted: false,
        });
        self.send_with(&id, text, &[]);
        Ok(id)
    }

    /// "End and delete history": ends every process of the task's sealed
    /// space and deletes everything it had.
    pub fn end_incognito(&mut self, id: &str) -> bool {
        let Some(i) = self.index(id) else {
            return false;
        };
        if !self.tasks[i].task.incognito {
            return false;
        }
        let mut l = self.tasks.remove(i);
        self.listeners.remove(id);
        while let Some(p) = l.asks.pop_front() {
            p.answer(Answer::Halted);
        }
        if let Some(s) = l.session.as_mut() {
            s.kill();
        }
        crate::incognito::forget(id);
        true
    }

    /// Removes the task and, as a phone's Delete promises, the conversation: Claude Code's
    /// transcript of the task's own session goes too (never one the person opened from their
    /// own earlier sessions, or a followed one).
    pub fn delete(&mut self, id: &str) {
        let session = self.index(id).and_then(|i| {
            let t = &self.tasks[i].task;
            (!t.imported && t.host.is_none() && t.from_phone.is_none())
                .then(|| t.session_id.clone())
                .flatten()
        });
        self.remove(id);
        if let Some(s) = session {
            crate::sessions::delete_session(&s);
        }
    }

    pub fn remove(&mut self, id: &str) {
        if let Some(i) = self.index(id) {
            let mut l = self.tasks.remove(i);
            if let Some(n) = l.task.placement_note.take() {
                crate::placement::remove(&n);
            }
            self.listeners.remove(id);
            while let Some(p) = l.asks.pop_front() {
                p.answer(Answer::Halted);
            }
            if let Some(s) = l.session.as_mut() {
                s.kill();
            }
            let _ = self.store.remove(id);
            crate::task_files::remove(&self.data_dir(), id);
        }
    }

    /// Takes in everything that happened since the last call. Call it every
    /// frame; it never blocks.
    pub fn tick(&mut self) {
        self.end_stuck_runs();
        self.tick_inner();
        self.send_resends();
    }

    fn tick_inner(&mut self) {
        while let Ok(p) = self.approvals.try_recv() {
            match self.index(&p.request.task) {
                Some(i)
                    if !matches!(self.tasks[i].task.status, Status::Paused | Status::Stopped) =>
                {
                    let l = &mut self.tasks[i];
                    l.task.status = Status::NeedsYou;
                    self.notices.push(Notice::NeedsYou {
                        task: l.task.id.clone(),
                        title: l.task.title.clone(),
                        what: p.request.words(),
                    });
                    l.asks.push_back(p);
                    l.dirty = true;
                }
                // Unknown, paused or stopped task: nothing may run.
                _ => p.answer(Answer::Halted),
            }
        }
        self.poll_usage();
        let now = self.now_ms();
        for l in &mut self.tasks {
            let Some(s) = l.session.as_mut() else {
                continue;
            };
            let mut exited = false;
            // A host that was still starting is taken in (or the run moves
            // here); never waits.
            s.settle();
            // A run that moved here from a host that never started has no
            // host to follow.
            if s.host_key().is_none() && l.task.host.is_some() {
                l.task.host = None;
                l.dirty = true;
            }
            while let Ok(out) = s.output.try_recv() {
                match out {
                    Output::Events(evs) => {
                        for e in &evs {
                            if matches!(e, Event::FiveHourUse { .. } | Event::WeeklyUse { .. }) {
                                self.usage_at = Some(crate::needs::now());
                            }
                            if let Event::FiveHourUse { used, resets_at } = e {
                                self.five_hour = Some(WeeklyUse {
                                    used: *used,
                                    resets_at: *resets_at,
                                });
                            }
                            if let Event::WeeklyUse { used, resets_at } = e {
                                self.weekly = Some(WeeklyUse {
                                    used: *used,
                                    resets_at: *resets_at,
                                });
                            }
                            if let Event::ToolUse { input, .. } = e
                                && input.get("run_in_background").and_then(|v| v.as_bool())
                                    == Some(true)
                            {
                                l.freeze = true;
                            }
                            if let Event::Init(init) = e {
                                l.baseline = s.descendants();
                                l.freeze = false;
                                self.slash_commands = init.slash_commands.clone();
                                self.mcp_servers = init.mcp_servers.clone();
                            }
                            if let Event::Finished(f) = e {
                                // A result answers everything sent so far:
                                // notes sent while Claude worked are folded
                                // into the same turn.
                                l.finished = l.sent.max(l.finished + 1);
                                if l.finished >= l.sent {
                                    self.notices.push(Notice::Finished {
                                        task: l.task.id.clone(),
                                        title: l.task.title.clone(),
                                        ok: !f.is_error,
                                    });
                                }
                            }
                            l.task.apply(e, now);
                            // Claude Code's own short title for the session.
                            if matches!(e, Event::Finished(_))
                                && !l.task.title_by_owner
                                && let Some(sid) = &l.task.session_id
                            {
                                let path = crate::sessions::claude_home()
                                    .join("projects")
                                    .join(crate::sessions::folder_name(Path::new(&l.task.project)))
                                    .join(format!("{sid}.jsonl"));
                                if let Some(t) = crate::sessions::ai_title(&path) {
                                    l.task.title = t;
                                }
                            }
                        }
                        l.dirty = true;
                    }
                    Output::Offset(n) => {
                        if let Some(h) = &mut l.task.host {
                            h.1 = n;
                        }
                        l.dirty = true;
                    }
                    Output::HostLost => {
                        l.task.add_note(HOST_LOST);
                        l.task.status = Status::Stopped;
                        l.task.failed = true;
                    }
                    Output::Exited(code) => {
                        exited = true;
                        let mut f: Vec<(&'static str, serde_json::Value)> =
                            vec![("task", l.task.id.clone().into())];
                        match code {
                            Some(0) | None => f.push(("ok", true.into())),
                            Some(c) => {
                                f.push(("err", format!("Project work ended with code {c}").into()));
                                f.push(("kind", "exit".into()));
                            }
                        }
                        crate::devlog::log("task", "exit", f);
                    }
                }
            }
            // Every message answered: let Claude Code exit. The next
            // message resumes the session.
            if l.finished >= l.sent && l.sent > 0 {
                s.close_input();
            }
            // The turn ended but Claude Code stays up for what it started
            // in the background: the task is still working, and says so.
            // (A run taken over after the service restarted has sent nothing
            // since: its task being Done while it still lives says the same.)
            let taken_over = l.sent == 0 && l.finished == 0 && l.task.status == Status::Done;
            let idle = !exited
                && ((l.finished >= l.sent && l.sent > 0) || taken_over)
                && l.asks.is_empty()
                && !matches!(
                    l.task.status,
                    Status::Working | Status::NeedsYou | Status::Paused | Status::Stopped
                );
            if l.bg_checked
                .is_none_or(|at| at.elapsed() >= BACKGROUND_LOOK)
                && !exited
            {
                l.bg_checked = Some(Instant::now());
                let now_kids = s.descendants();
                if !idle {
                    // A turn is on: helpers Claude Code starts for itself
                    // (plugins, language servers) are not the task's work,
                    // unless a background command was asked for.
                    if !l.freeze {
                        for k in now_kids {
                            if !l.baseline.contains(&k) {
                                l.baseline.push(k);
                            }
                        }
                    }
                    l.bg_seen = 0;
                    if std::mem::take(&mut l.bg_noted) {
                        l.task.drop_note(BACKGROUND_NOTE);
                        l.dirty = true;
                    }
                    if l.background {
                        l.background = false;
                        l.dirty = true;
                    }
                } else {
                    // Something new, seen on two checks in a row, is work
                    // the turn left running.
                    let extra = now_kids.iter().any(|k| !l.baseline.contains(k));
                    l.bg_seen = if extra { (l.bg_seen + 1).min(2) } else { 0 };
                    let now_bg = l.bg_seen >= 2;
                    if now_bg != l.background {
                        l.background = now_bg;
                        if now_bg && !l.bg_noted {
                            l.bg_noted = true;
                            l.task.add_note(BACKGROUND_NOTE);
                        }
                        if !now_bg {
                            l.freeze = false;
                            l.bg_noted = false;
                            // Nothing runs any more: the line is no longer true.
                            l.task.drop_note(BACKGROUND_NOTE);
                        }
                        l.dirty = true;
                    }
                }
            }
            // A question may arrive before the step it is about; the
            // owner's answer is still needed either way.
            match (l.asks.is_empty(), l.task.status) {
                (true, Status::NeedsYou) => l.task.status = Status::Working,
                (false, Status::Working) => l.task.status = Status::NeedsYou,
                _ => {}
            }
            if exited {
                let ended_early = l.finished < l.sent;
                // Its Claude Code ended while something it started was still
                // running, and nobody stopped it: it died, the work is not done.
                // (After a restart of the service the checks start over, but the
                // line it said before is still in the task.)
                let said_busy = l
                    .task
                    .entries
                    .iter()
                    .any(|e| matches!(e, crate::task::Entry::Note(n) if n == BACKGROUND_NOTE));
                // Ended by a signal nobody here sent (a kill from outside): the
                // work is not done, whatever the background look last saw. Mímir's
                // own stops set Stopped or Paused first, and its other kills take
                // the run away before this is read.
                let killed_outside = l
                    .session
                    .as_mut()
                    .is_some_and(|s| s.poll_exit() == Some(None))
                    && !matches!(l.task.status, Status::Stopped | Status::Paused);
                let died_busy = (l.background || l.bg_seen > 0 || said_busy || killed_outside)
                    && !matches!(l.task.status, Status::Stopped | Status::Paused);
                l.session = None;
                self.own_ended
                    .insert(l.task.id.clone(), crate::needs::now());
                l.background = false;
                l.bg_seen = 0;
                l.bg_noted = false;
                l.task.drop_note(BACKGROUND_NOTE);
                l.task.host = None;
                if ended_early && matches!(l.task.status, Status::Working | Status::NeedsYou) {
                    l.task.status = Status::Stopped;
                    l.task.failed = true;
                    l.task.add_note(
                        "Project work closed before it finished. Press Continue to try again.",
                    );
                } else if died_busy {
                    l.task.status = Status::Stopped;
                    l.task.failed = true;
                    l.task.add_note(HOST_LOST);
                }
                while let Some(p) = l.asks.pop_front() {
                    p.answer(Answer::Halted);
                }
                // Steps it was in the middle of will never finish now.
                l.task.settle_open_steps(now);
                l.dirty = true;
            }
        }
        self.save_dirty();
    }

    fn save_dirty(&mut self) {
        for l in &mut self.tasks {
            // Incognito tasks are never written anywhere.
            if l.task.incognito {
                l.dirty = false;
                continue;
            }
            if l.dirty {
                match self.store.save(&l.task) {
                    Ok(()) => {
                        l.dirty = false;
                        // What's saved needn't be kept by the host.
                        if let (Some(s), Some((_, at))) = (l.session.as_mut(), &l.task.host) {
                            s.saved(*at);
                        }
                    }
                    Err(_) => {
                        let msg = "A task could not be saved. Check that this PC has free space."
                            .to_string();
                        if !self.problems.contains(&msg) {
                            self.problems.push(msg);
                        }
                    }
                }
            }
        }
    }

    /// Stops every Claude Code process (window closing). Tasks are saved and
    /// resume next time.
    pub fn shutdown(&mut self) {
        self.refuse_all_questions();
        for l in &mut self.tasks {
            // A hosted Claude Code carries on; the next service follows it.
            if let Some(s) = l.session.as_mut()
                && s.detach()
            {
                l.dirty = true;
                drop(l.session.take());
                continue;
            }
            while let Some(p) = l.asks.pop_front() {
                p.answer(Answer::Halted);
            }
            if let Some(s) = &mut l.session {
                s.interrupt();
            }
        }
        let deadline = Instant::now() + std::time::Duration::from_secs(3);
        while Instant::now() < deadline
            && self
                .tasks
                .iter_mut()
                .any(|l| l.session.as_mut().is_some_and(|s| s.poll_exit().is_none()))
        {
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        for l in &mut self.tasks {
            if l.session.take().is_some()
                && matches!(l.task.status, Status::Working | Status::NeedsYou)
            {
                l.task.status = Status::Paused;
                l.task.add_note(CLOSED_WHILE_WORKING);
                l.dirty = true;
            }
        }
        self.save_dirty();
    }

    /// Stops listening and answers No to every question, queued or shown.
    fn refuse_all_questions(&mut self) {
        self.listeners.clear();
        while let Ok(p) = self.approvals.try_recv() {
            p.answer(Answer::Halted);
        }
        for l in &mut self.tasks {
            while let Some(p) = l.asks.pop_front() {
                p.answer(Answer::Halted);
            }
        }
    }
}

impl Drop for Runner {
    fn drop(&mut self) {
        self.refuse_all_questions();
    }
}

/// A file path at the start of a line (`/a/b.png`, `~/a/b`, `@"/a/b c.png"`):
/// its file name and the text after it. A slash command is not a path.
fn leading_path(line: &str) -> Option<(String, &str)> {
    let t = line.strip_prefix('@').unwrap_or(line);
    let (path, rest) = if let Some(q) = t.strip_prefix('"') {
        let end = q.find('"')?;
        (&q[..end], &q[end + 1..])
    } else {
        let end = t.find(char::is_whitespace).unwrap_or(t.len());
        (&t[..end], &t[end..])
    };
    let rooted = path.starts_with('/') || path.starts_with("~/");
    if !rooted || path.matches('/').count() < 2 {
        return None;
    }
    let name = path.trim_end_matches('/').rsplit('/').next()?;
    if name.is_empty() {
        return None;
    }
    Some((name.chars().take(60).collect(), rest.trim()))
}

/// A short title from the first message.
pub fn title_from(text: &str) -> String {
    // The first sentence of the first line, without polite filler.
    let first = text.trim().lines().next().unwrap_or("").trim();
    // A message that starts with a pasted file: the words after it, or the
    // file's name when there is nothing else.
    if let Some((name, rest)) = leading_path(first) {
        let more = text.trim()[first.len()..].trim();
        let rest = if rest.is_empty() { more } else { rest };
        return if rest.is_empty() {
            name
        } else {
            title_from(rest)
        };
    }
    let mut t = first;
    for filler in [
        "can you please ",
        "could you please ",
        "can you ",
        "could you ",
        "please ",
        "i want you to ",
        "i'd like you to ",
        "i would like you to ",
        "hey claude, ",
        "hi claude, ",
        "claude, ",
    ] {
        if t.get(..filler.len())
            .is_some_and(|s| s.eq_ignore_ascii_case(filler))
        {
            t = &t[filler.len()..];
        }
    }
    let sentence = t
        .char_indices()
        .find(|&(i, c)| {
            matches!(c, '.' | '!' | '?' | ':' | ';')
                && t[i + c.len_utf8()..].starts_with(' ')
                && (i > 12 || (c == '?' && i > 3))
        })
        .map(|(i, c)| &t[..i + if c == '?' { 1 } else { 0 }])
        .unwrap_or(t)
        .trim();
    let mut out: String = sentence.chars().take(60).collect();
    if sentence.chars().count() > 60 {
        out.push('…');
    }
    // A capital only for a real word, not for "cd ~/x" or "@file".
    let first_word = out.split_whitespace().next().unwrap_or("");
    let out = if first_word.len() > 2 && first_word.chars().all(char::is_alphabetic) {
        let mut c = out.chars();
        match c.next() {
            Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
            None => String::new(),
        }
    } else {
        out
    };
    if out.is_empty() {
        "New task".into()
    } else {
        out
    }
}

/// The rule of [`Live::clock_turn`].
fn clock(busy: bool, clocked: &mut bool, turn: &mut Option<i64>, now: i64) {
    if !busy {
        *clocked = false;
    } else if !*clocked {
        *clocked = true;
        *turn = Some(now);
    }
}

#[cfg(test)]
mod tests {
    /// The turn's clock starts when the run is first seen working, and a
    /// time saved before a restart is replaced; later looks leave it be.
    #[test]
    fn the_turn_clock_starts_at_the_first_look_at_a_working_run() {
        let (mut clocked, mut turn) = (false, Some(10));
        super::clock(false, &mut clocked, &mut turn, 5000);
        assert_eq!(turn, Some(10), "not working: nothing touched");
        super::clock(true, &mut clocked, &mut turn, 5000);
        assert_eq!(turn, Some(5000), "an hours-old saved time is replaced");
        super::clock(true, &mut clocked, &mut turn, 5007);
        assert_eq!(turn, Some(5000), "it keeps counting from the start");
        super::clock(false, &mut clocked, &mut turn, 5010);
        super::clock(true, &mut clocked, &mut turn, 9000);
        assert_eq!(turn, Some(9000), "a new working stretch is a new turn");
    }

    use super::*;

    #[test]
    fn short_titles() {
        assert_eq!(
            title_from("Can you please fix the login bug. It fails on Monday."),
            "Fix the login bug"
        );
        assert_eq!(
            title_from("why does the build fail on SteamOS? I tried twice"),
            "Why does the build fail on SteamOS?"
        );
        assert_eq!(title_from(""), "New task");
        assert_eq!(
            title_from("What is 2+2? Answer, then ask me."),
            "What is 2+2?"
        );
        assert_eq!(
            title_from("cd ~/Projects/x and test"),
            "cd ~/Projects/x and test"
        );
        assert!(title_from(&"word ".repeat(40)).ends_with('…'));
        // A pasted file: its name, or the words after it.
        assert_eq!(
            title_from("@\"/mnt/user-data/uploads/5d5 a.png\""),
            "5d5 a.png"
        );
        assert_eq!(
            title_from("/home/user/shot.png why is this red? thanks"),
            "Why is this red?"
        );
        assert_eq!(
            title_from("@\"/a/b/c.png\"\nfix the colours"),
            "Fix the colours"
        );
        assert_eq!(title_from("/btw is this ok"), "/btw is this ok");
        // Multi-byte chars near a filler-length boundary must not panic.
        assert!(!title_from("I would like you to Mímir something").is_empty());
        assert!(!title_from("Mímir, can you help").is_empty());
    }
}
