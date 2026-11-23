//! One task: what the window shows for it, built up from Claude Code's
//! events. Saved to disk as JSON so tasks survive restarts.

use crate::events::{Event, Finished};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub use mimir_proto::{
    Entry, Mode, Place, PlanItem, Status, Step, StepDetail, WeeklyUse, nice_time, not_yet,
};

const MAX_OUTPUT: usize = 16_000;
const MAX_PATCH_LINES: usize = 600;

fn shorten(s: &str) -> String {
    if s.len() <= MAX_OUTPUT {
        return s.to_string();
    }
    let mut cut = MAX_OUTPUT;
    while !s.is_char_boundary(cut) {
        cut -= 1;
    }
    format!("{}\n… ({} more characters)", &s[..cut], s.len() - cut)
}

fn is_zero(n: &usize) -> bool {
    *n == 0
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Task {
    /// An Incognito task: never saved (only in the service's memory).
    #[serde(skip)]
    pub incognito: bool,
    /// Entries dropped from the front of a followed session (only its tail
    /// is kept): ids that count entries add this, so they stay stable.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub entries_dropped: usize,
    pub id: String,
    pub title: String,
    /// The owner named it: keep that name.
    #[serde(default)]
    pub title_by_owner: bool,
    /// Project folder Claude works in.
    pub project: String,
    /// "Phone too": the conversation also runs with Remote Control, so it
    /// can be used from the phone and here at the same time.
    #[serde(default)]
    pub phone_too: bool,
    /// For a task in a separate copy: the project it is a copy of.
    #[serde(default)]
    pub origin: Option<String>,
    /// A session a project's Remote Control started (from the phone or
    /// claude.ai): the window follows it live and can't write to it. The
    /// address that opens it on claude.ai.
    #[serde(default)]
    pub from_phone: Option<String>,
    /// Ground rules a phone sent with the request that started this task
    /// (used instead of the PC's own).
    #[serde(default)]
    pub ground_rules: Option<String>,
    /// For a task in a separate copy: the copy's branch.
    #[serde(default)]
    pub copy_branch: Option<String>,
    /// Claude's limit ran out during this task.
    #[serde(default)]
    pub ran_out: Option<mimir_proto::RanOut>,
    /// Claude Code just reported its usage limit (when it resets, 0 if it
    /// didn't say): the service decides what happens next.
    #[serde(skip)]
    pub limit_hit: Option<i64>,
    /// Claude Code's session id, used with `--resume`.
    pub session_id: Option<String>,
    pub status: Status,
    pub entries: Vec<Entry>,
    pub plan: Vec<PlanItem>,
    pub files_changed: Vec<String>,
    pub created_unix: i64,
    pub cost_usd: f64,
    #[serde(default)]
    pub weekly: Option<WeeklyUse>,
    /// TaskCreate calls waiting for the id Claude Code gives them.
    #[serde(default)]
    pending_plan: Vec<(String, String)>,
    /// File-changing calls waiting for their result: (tool_use_id, path).
    #[serde(default)]
    pending_files: Vec<(String, String)>,
    #[serde(default)]
    pub mode: Mode,
    /// e.g. "claude-opus-5-5", or "auto"; None uses Claude Code's default.
    #[serde(default)]
    pub model: Option<String>,
    /// With model "auto": the model Mímir last picked.
    #[serde(default)]
    pub auto_choice: Option<String>,
    /// The last handover brief (Auto model), for the owner to read.
    #[serde(default)]
    pub brief: Option<String>,
    /// The brief still has to go with the next message.
    #[serde(default)]
    pub brief_pending: bool,
    /// Snapshots for undo: (index of the owner's message in `entries`,
    /// snapshot taken just before it).
    #[serde(default)]
    pub checkpoints: Vec<(usize, String)>,
    /// After an undo: the snapshot to put the undone changes back.
    #[serde(default)]
    pub redo: Option<String>,
    /// The owner marked the waiting question as needing no reply (the
    /// number of entries at that moment).
    #[serde(default)]
    pub question_dismissed: Option<usize>,
    /// After an undo: what to tell Claude with the next message.
    #[serde(default)]
    pub undo_told: Option<String>,
    /// e.g. "high"; None uses Claude Code's default.
    #[serde(default)]
    pub effort: Option<String>,
    /// Text Claude is writing right now (not saved).
    #[serde(skip)]
    pub live: String,
    #[serde(default)]
    pub context_used: u64,
    #[serde(default)]
    pub context_window: u64,
    /// The plan Claude wrote in Plan mode, waiting for approval.
    #[serde(default)]
    pub plan_doc: Option<String>,
    #[serde(default)]
    pub archived: bool,
    /// A chat started with no project that Mímir filed itself
    /// (shown as "Filed under … · change" until moved or confirmed).
    #[serde(default)]
    pub placed: Option<mimir_proto::Placed>,
    /// The placement note written in the project while it's a guess.
    #[serde(default)]
    pub placement_note: Option<String>,
    /// A client's copy: why the guess was made (the note stays on the PC).
    #[serde(skip)]
    pub placed_why: Option<String>,
    /// YOLO ("Don't ask"): how long it has worked and sat idle since.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub yolo: Option<crate::yolo::Clock>,
    /// When its conversation last changed, wherever it was going on (the
    /// PC, the phone, claude.ai, a terminal); seconds since 1970.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_unix: Option<i64>,
    /// Its Claude Code runs in a host (host.rs): the host's key, and how
    /// far its output was read when the task was saved. A restart carries
    /// on from there.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host: Option<(String, u64)>,
    /// Its conversation is going on outside Mímir right now:
    /// replies go there (Claude Code has one writer). Not saved.
    #[serde(skip)]
    pub live_elsewhere: bool,
    /// Whether the last reply asks the owner something, kept so the window
    /// doesn't look again every frame. Not saved.
    #[serde(skip)]
    asks_memo: AsksMemo,
    /// Phone too: a summary turn is generated after each finished task.
    /// Off by default (PCS-09).
    #[serde(default)]
    pub summary_enabled: bool,
    /// Phone too: an active Phone too session is running on this task.
    /// Guard against double-starts (PCS-06). Not saved.
    #[serde(skip)]
    pub phone_too_active: bool,
    /// Whether this is an agentic task (not a plain chat). Used by B7 UI.
    #[serde(default)]
    pub is_agentic: bool,
}

/// The question check's last answer: for which reply (the entry count and
/// the reply's length) and what it was. Packed in one number so a task can
/// be shared between threads.
#[derive(Default)]
struct AsksMemo(std::sync::atomic::AtomicU64);

impl AsksMemo {
    fn key(entries: usize, len: usize) -> u64 {
        // Never 0, so an empty memo matches nothing.
        ((((entries as u64) & 0x7fff_ffff) << 32 | (len as u64 & 0x7fff_ffff)) << 1) | (1 << 63)
    }

    fn get(&self, key: u64) -> Option<bool> {
        let v = self.0.load(std::sync::atomic::Ordering::Relaxed);
        (v & !1 == key).then_some(v & 1 == 1)
    }

    fn set(&self, key: u64, asks: bool) {
        self.0
            .store(key | asks as u64, std::sync::atomic::Ordering::Relaxed);
    }
}

impl Clone for AsksMemo {
    fn clone(&self) -> Self {
        AsksMemo(self.0.load(std::sync::atomic::Ordering::Relaxed).into())
    }
}

impl PartialEq for AsksMemo {
    fn eq(&self, _: &Self) -> bool {
        true
    }
}

impl std::fmt::Debug for AsksMemo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("AsksMemo")
    }
}

/// Tools that only keep Claude's own notes; they get no step card.
const QUIET_TOOLS: &[&str] = &[
    "TaskCreate",
    "TaskUpdate",
    "TaskGet",
    "TaskList",
    "TodoWrite",
    "ToolSearch",
];

pub fn place_of(tool: &str) -> Place {
    if matches!(tool, "WebSearch" | "WebFetch" | "RemoteTrigger")
        || tool.starts_with("mcp__claude_ai_")
    {
        Place::Online
    } else {
        Place::ThisPc
    }
}

fn short(s: &str, n: usize) -> String {
    let one_line = s.lines().next().unwrap_or_default();
    if one_line.chars().count() > n || s.contains('\n') {
        let cut: String = one_line.chars().take(n).collect();
        format!("{cut}…")
    } else {
        one_line.to_string()
    }
}

fn file_name(path: &str) -> String {
    path.rsplit('/').next().unwrap_or(path).to_string()
}

/// Plain words for a tool call.
pub fn describe(tool: &str, input: &Value) -> String {
    let get = |k: &str| input.get(k).and_then(Value::as_str).unwrap_or_default();
    match tool {
        "Bash" => {
            let d = get("description");
            if d.is_empty() {
                format!("Ran {}", short(get("command"), 60))
            } else {
                short(d, 70)
            }
        }
        "Read" => format!("Read {}", file_name(get("file_path"))),
        "Edit" | "MultiEdit" => format!("Changed {}", file_name(get("file_path"))),
        "Write" => format!("Wrote {}", file_name(get("file_path"))),
        "NotebookEdit" => format!("Changed {}", file_name(get("notebook_path"))),
        "Grep" => format!("Searched files for {}", short(get("pattern"), 40)),
        "Glob" => format!("Looked for files {}", short(get("pattern"), 40)),
        "WebSearch" => format!("Searched the web for {}", short(get("query"), 50)),
        "WebFetch" => format!("Read web page {}", short(get("url"), 50)),
        "Task" | "Agent" => format!("Helper: {}", short(get("description"), 50)),
        _ => format!("Used {}", tool.trim_start_matches("mcp__")),
    }
}

/// Guard: start a Phone too session on a task (PCS-06).
/// Returns an error if the task already has an active Phone too session.
pub fn phone_too_start(task: &mut Task) -> Result<(), String> {
    if task.phone_too_active {
        return Err(format!(
            "Phone too is already active on task \"{}\". Stop the current session first.",
            task.id
        ));
    }
    task.phone_too_active = true;
    Ok(())
}

impl Task {
    pub fn new(id: String, title: String, project: String, now_unix: i64) -> Self {
        Task {
            incognito: false,
            entries_dropped: 0,
            id,
            title,
            title_by_owner: false,
            project,
            origin: None,
            from_phone: None,
            ground_rules: None,
            copy_branch: None,
            ran_out: None,
            limit_hit: None,
            phone_too: false,
            session_id: None,
            status: Status::Working,
            entries: vec![],
            plan: vec![],
            files_changed: vec![],
            created_unix: now_unix,
            cost_usd: 0.0,
            weekly: None,
            pending_plan: vec![],
            pending_files: vec![],
            mode: Mode::Ask,
            model: None,
            auto_choice: None,
            brief: None,
            brief_pending: false,
            checkpoints: vec![],
            redo: None,
            undo_told: None,
            question_dismissed: None,
            effort: None,
            live: String::new(),
            context_used: 0,
            context_window: 0,
            plan_doc: None,
            archived: false,
            placed: None,
            placement_note: None,
            placed_why: None,
            active_unix: None,
            host: None,
            live_elsewhere: false,
            yolo: None,
            asks_memo: AsksMemo::default(),
            summary_enabled: false,
            phone_too_active: false,
            is_agentic: false,
        }
    }

    /// A client's copy of a task from what the service sends: everything
    /// but the conversation, which comes on its own.
    pub fn set_header(&mut self, h: &mimir_proto::TaskHeader) {
        self.id = h.id.clone();
        self.title = h.title.clone();
        self.title_by_owner = h.title_by_owner;
        self.incognito = h.incognito;
        self.project = h.project.clone();
        self.origin = h.origin.clone();
        self.from_phone = h.from_phone.clone();
        self.copy_branch = h.copy_branch.clone();
        self.ran_out = h.ran_out.clone();
        self.session_id = h.session_id.clone();
        self.status = h.status;
        self.mode = h.mode;
        self.model = h.model.clone();
        self.auto_choice = h.auto_choice.clone();
        self.effort = h.effort.clone();
        self.plan = h.plan.clone();
        self.plan_doc = h.plan_doc.clone();
        self.files_changed = h.files_changed.clone();
        self.cost_usd = h.cost_usd;
        self.weekly = h.weekly.clone();
        self.context_used = h.context_used;
        self.context_window = h.context_window;
        self.created_unix = h.created_unix;
        self.brief = h.brief.clone();
        // A client only needs to know which messages can be undone to.
        self.checkpoints = h.checkpoints.iter().map(|i| (*i, String::new())).collect();
        self.redo = h.redo.then(String::new);
        self.question_dismissed = h.question_dismissed;
        self.archived = h.archived;
        self.placed = h.placed;
        self.placed_why = h.placed_why.clone();
        self.active_unix = h.active_unix;
        self.live_elsewhere = h.live_elsewhere;
    }

    /// Percent of the plan that is done, if there is a plan.
    pub fn plan_percent(&self) -> Option<u8> {
        if self.plan.is_empty() {
            return None;
        }
        let done = self.plan.iter().filter(|p| p.done).count();
        Some((done * 100 / self.plan.len()) as u8)
    }

    /// (steps on this PC, steps sent online)
    pub fn places(&self) -> (usize, usize) {
        let mut pc = 0;
        let mut online = 0;
        for e in &self.entries {
            if let Entry::Step(s) = e {
                match s.place {
                    Place::ThisPc => pc += 1,
                    Place::Online => online += 1,
                }
            }
        }
        (pc, online)
    }

    fn step_mut(&mut self, id: &str) -> Option<&mut Step> {
        self.entries.iter_mut().rev().find_map(|e| match e {
            Entry::Step(s) if s.tool_use_id == id => Some(s),
            _ => None,
        })
    }

    fn note_file(&mut self, path: &str) {
        if path.is_empty() {
            return;
        }
        let shown = path
            .strip_prefix(&self.project)
            .map(|p| p.trim_start_matches('/'))
            .unwrap_or(path)
            .to_string();
        if !self.files_changed.contains(&shown) {
            self.files_changed.push(shown);
        }
    }

    pub fn add_you(&mut self, text: &str) {
        self.entries.push(Entry::You(text.to_string()));
        self.status = Status::Working;
    }

    /// The owner just answered a question: time the waiting step from now,
    /// so step times show work, not how long the question waited.
    pub fn answered(&mut self, now_ms: u64) {
        if let Some(Entry::Step(s)) = self
            .entries
            .iter_mut()
            .rev()
            .find(|e| matches!(e, Entry::Step(s) if s.ok.is_none()))
        {
            s.started_ms = now_ms;
        }
    }

    pub fn add_note(&mut self, text: &str) {
        self.entries.push(Entry::Note(text.to_string()));
    }

    /// Applies one event. `now_ms` is a clock in milliseconds used to time
    /// steps.
    pub fn apply(&mut self, ev: &Event, now_ms: u64) {
        match ev {
            Event::Init(init) => {
                if !init.session_id.is_empty() {
                    self.session_id = Some(init.session_id.clone());
                }
            }
            Event::Text(t) => {
                self.live.clear();
                self.entries.push(Entry::Claude(t.clone()));
            }
            Event::TextDelta(t) => self.live.push_str(t),
            Event::ContextUsed(n) => self.context_used = *n,
            Event::Compacted { before, after } => {
                self.context_used = *after;
                let k = |n: u64| format!("{}k", (n + 500) / 1000);
                self.add_note(&format!(
                    "Compacted: Mímir summarised the task so far to free up space ({} → {} used).",
                    k(*before),
                    k(*after)
                ));
            }
            Event::ToolUse { id, name, input } => self.tool_use(id, name, input, now_ms),
            Event::ToolResult {
                id,
                is_error,
                text,
                detail,
            } => self.tool_result(id, *is_error, text, detail, now_ms),
            Event::Denied { tool_use_id, .. } => {
                // Refused because the owner paused or stopped: not a "no".
                let halted = matches!(self.status, Status::Paused | Status::Stopped);
                if let Some(s) = self.step_mut(tool_use_id) {
                    if halted {
                        s.stopped = true;
                    } else {
                        s.refused = true;
                    }
                    s.ok = Some(false);
                }
            }
            Event::WeeklyUse { used, resets_at } => {
                self.weekly = Some(WeeklyUse {
                    used: *used,
                    resets_at: *resets_at,
                })
            }
            Event::Finished(f) => self.finished(f, now_ms),
            Event::Unknown(_) | Event::FiveHourUse { .. } => {}
        }
    }

    fn tool_use(&mut self, id: &str, name: &str, input: &Value, now_ms: u64) {
        let get = |k: &str| input.get(k).and_then(Value::as_str).unwrap_or_default();
        match name {
            "TaskCreate" => {
                self.pending_plan
                    .push((id.to_string(), get("subject").to_string()));
                return;
            }
            "TaskUpdate" => {
                let pid = get("taskId");
                let status = get("status");
                if status == "deleted" {
                    self.plan.retain(|p| p.id != pid);
                } else if let Some(p) = self.plan.iter_mut().find(|p| p.id == pid) {
                    if !status.is_empty() {
                        p.done = status == "completed";
                        p.doing = status == "in_progress";
                    }
                    let subject = get("subject");
                    if !subject.is_empty() {
                        p.text = subject.to_string();
                    }
                }
                return;
            }
            "TodoWrite" => {
                if let Some(todos) = input.get("todos").and_then(Value::as_array) {
                    self.plan = todos
                        .iter()
                        .enumerate()
                        .map(|(i, t)| {
                            let st = t.get("status").and_then(Value::as_str).unwrap_or("");
                            PlanItem {
                                id: i.to_string(),
                                text: t
                                    .get("content")
                                    .and_then(Value::as_str)
                                    .unwrap_or_default()
                                    .to_string(),
                                done: st == "completed",
                                doing: st == "in_progress",
                            }
                        })
                        .collect();
                }
                return;
            }
            n if QUIET_TOOLS.contains(&n) => return,
            _ => {}
        }
        if !matches!(self.status, Status::Paused | Status::Stopped) {
            self.status = Status::Working;
        }
        let path_in = match name {
            "Edit" | "MultiEdit" | "Write" | "Read" => get("file_path"),
            "NotebookEdit" => get("notebook_path"),
            _ => "",
        };
        // In Plan mode Claude Code writes its plan to ~/.claude/plans/.
        let is_plan_file = name == "Write" && path_in.contains("/.claude/plans/");
        if is_plan_file {
            self.plan_doc = Some(get("content").to_string());
        }
        let input_text = match name {
            "Bash" => get("command").to_string(),
            "Grep" | "Glob" => get("pattern").to_string(),
            "WebSearch" => get("query").to_string(),
            "WebFetch" => get("url").to_string(),
            _ if !path_in.is_empty() => path_in.to_string(),
            _ => serde_json::to_string_pretty(input).unwrap_or_default(),
        };
        self.entries.push(Entry::Step(Step {
            tool_use_id: id.to_string(),
            tool: name.to_string(),
            what: if is_plan_file {
                "Wrote the plan".to_string()
            } else {
                describe(name, input)
            },
            place: place_of(name),
            started_ms: now_ms,
            took_ms: None,
            ok: None,
            refused: false,
            stopped: false,
            detail: StepDetail {
                input: input_text,
                ..Default::default()
            },
        }));
        if is_plan_file {
            return;
        }
        let path = match name {
            "Edit" | "MultiEdit" | "Write" => get("file_path"),
            "NotebookEdit" => get("notebook_path"),
            _ => "",
        };
        if !path.is_empty() {
            self.pending_files.push((id.to_string(), path.to_string()));
        }
    }

    fn tool_result(&mut self, id: &str, is_error: bool, text: &str, detail: &Value, now_ms: u64) {
        if let Some(pos) = self.pending_plan.iter().position(|(t, _)| t == id) {
            let (_, subject) = self.pending_plan.remove(pos);
            if let Some(pid) = detail.pointer("/task/id").and_then(Value::as_str) {
                self.plan.push(PlanItem {
                    id: pid.to_string(),
                    text: subject,
                    done: false,
                    doing: false,
                });
            }
            return;
        }
        if let Some(pos) = self.pending_files.iter().position(|(t, _)| t == id) {
            let (_, path) = self.pending_files.remove(pos);
            if !is_error {
                self.note_file(&path);
            }
        }
        let halted = matches!(self.status, Status::Paused | Status::Stopped);
        let patch = patch_lines(detail);
        if let Some(step) = self.step_mut(id) {
            step.detail.output = shorten(text);
            step.detail.patch = patch;
            step.took_ms = Some(now_ms.saturating_sub(step.started_ms));
            if step.ok.is_none() {
                step.ok = Some(!is_error);
                step.stopped = is_error && halted;
            }
        }
    }

    fn finished(&mut self, f: &Finished, now_ms: u64) {
        if !f.session_id.is_empty() {
            self.session_id = Some(f.session_id.clone());
        }
        self.cost_usd += f.cost_usd;
        self.live.clear();
        if f.context_window > 0 {
            self.context_window = f.context_window;
        }
        // Steps still running when a turn ended with a problem, or after Pause or Stop,
        // were cut short. After a clean end one may be a command left running in the
        // background: it stays open until the run ends (see `settle_open_steps`).
        if f.is_error || matches!(self.status, Status::Paused | Status::Stopped) {
            self.close_open_steps(now_ms);
        }
        match self.status {
            // The owner already chose Pause or Stop; keep that.
            Status::Paused | Status::Stopped => {}
            _ if f.is_error && f.terminal_reason != "aborted_tools" => {
                self.status = Status::Stopped;
                let why = f.text.clone().unwrap_or_else(|| f.subtype.clone());
                // Claude's last message may already say it.
                let said =
                    matches!(self.entries.last(), Some(Entry::Claude(t)) if t.trim() == why.trim());
                if let Some(resets) = crate::models::claude_limit(&why) {
                    // The service carries on (free model, or waits): see
                    // Service::claude_ran_out.
                    self.limit_hit = Some(resets);
                } else if crate::models::is_free(self.model.as_deref())
                    && crate::models::out_of_room(&why)
                {
                    self.add_note(&format!(
                        "{} Continue online carries on from a summary of the work.",
                        crate::models::OUT_OF_ROOM
                    ));
                } else if !said {
                    self.add_note(&format!("Work stopped with a problem: {why}"));
                }
            }
            _ => self.status = Status::Done,
        }
    }

    /// Start a "Phone too" session on this task (PCS-06).
    /// Returns an error if one is already active.
    pub fn start_phone_too(&mut self) -> Result<(), String> {
        phone_too_start(self)
    }

    /// Stop the active "Phone too" session on this task.
    pub fn stop_phone_too(&mut self) {
        self.phone_too_active = false;
    }

    /// Claude wrote a plan in Plan mode and waits for the owner to approve.
    pub fn plan_waiting(&self) -> bool {
        self.status == Status::Done && self.mode == Mode::Plan && self.plan_doc.is_some()
    }

    /// The project folder the owner sees this task under (the original
    /// for a task in a separate copy).
    pub fn home_project(&self) -> &str {
        self.origin.as_deref().unwrap_or(&self.project)
    }

    /// Claude finished its turn by asking the owner something.
    pub fn question_waiting(&self) -> bool {
        self.status == Status::Done
            && !self.is_chat()
            && !self.plan_waiting()
            && self.question_dismissed != Some(self.entries.len())
            && matches!(self.entries.last(), Some(Entry::Claude(t)) if self.reply_asks(t))
    }

    /// A general chat (it runs in the Chats folder): a conversation, so
    /// nobody is ever waiting on the owner. Only a task can need them.
    pub fn is_chat(&self) -> bool {
        let chats = crate::paths::chats_dir();
        let p = std::path::Path::new(&self.project);
        p == chats || self.project.ends_with("mimir/chats")
    }

    /// Whether this reply (the last entry) asks the owner something,
    /// worked out once per reply.
    fn reply_asks(&self, text: &str) -> bool {
        let key = AsksMemo::key(self.entries.len(), text.len());
        if let Some(asks) = self.asks_memo.get(key) {
            return asks;
        }
        let asks = question_split(text).is_some();
        self.asks_memo.set(key, asks);
        asks
    }

    /// Its conversation changed within [`ACTIVE_SECS`]: it sits in "Active
    /// now" at the top of the list.
    pub fn active_at(&self, now: i64) -> bool {
        !self.archived && self.active_unix.is_some_and(|a| now - a < ACTIVE_SECS)
    }

    /// A title with no system text in it: the title, or else the latest
    /// thing the owner asked that reads as words.
    pub fn real_title(&self) -> Option<String> {
        if !system_like(&self.title) {
            return None;
        }
        self.entries.iter().rev().find_map(|e| match e {
            Entry::You(t) if !system_like(t) => Some(crate::runner::title_from(t)),
            _ => None,
        })
    }

    /// Claude is working on it right now, here or elsewhere (a session
    /// followed from the phone, claude.ai or a terminal), and it doesn't
    /// wait for the owner. `busy`: this PC's Claude works on it.
    pub fn working_now(&self, busy: bool) -> bool {
        !self.needs_owner() && (busy || self.status == Status::Working)
    }

    /// Whether the owner is needed: an OK, a plan, or a question.
    pub fn needs_owner(&self) -> bool {
        self.status == Status::NeedsYou || self.plan_waiting() || self.question_waiting()
    }

    /// Status in words, with a waiting plan or question counted as needing
    /// the owner.
    pub fn status_words(&self) -> &'static str {
        if self.plan_waiting() {
            "Plan ready"
        } else if self.question_waiting() {
            "Question for you"
        } else if self.live_elsewhere && self.status != Status::Working {
            "Live elsewhere"
        } else {
            self.status.words()
        }
    }

    /// The end of a run: steps that never got a result are closed. On a task that
    /// finished (Done) they were a command left running in the background, which
    /// has ended too, so they count as done; otherwise they are stopped.
    pub fn settle_open_steps(&mut self, now_ms: u64) -> bool {
        if self.status != Status::Done {
            return self.close_open_steps(now_ms);
        }
        let mut changed = false;
        for e in &mut self.entries {
            if let Entry::Step(s) = e
                && s.ok.is_none()
            {
                s.ok = Some(true);
                if now_ms > 0 {
                    s.took_ms = Some(now_ms.saturating_sub(s.started_ms));
                }
                changed = true;
            }
        }
        changed
    }

    /// Marks steps that never got a result as stopped. Returns whether any
    /// changed.
    pub fn close_open_steps(&mut self, now_ms: u64) -> bool {
        let mut changed = false;
        for e in &mut self.entries {
            if let Entry::Step(s) = e
                && s.ok.is_none()
            {
                s.ok = Some(false);
                s.stopped = true;
                if now_ms > 0 {
                    s.took_ms = Some(now_ms.saturating_sub(s.started_ms));
                }
                changed = true;
            }
        }
        changed
    }
}

/// A conversation written to this recently (seconds) is live.
pub const LIVE_SECS: i64 = 5 * 60;
/// After this long with no new writes and no Claude running (seconds), a
/// task leaves "Active now" and sits only in its project folder.
pub const ACTIVE_SECS: i64 = 2 * 60 * 60;

/// Text Claude Code or the system put there, not words for a title:
/// anything starting with `<`, or carrying a tag like `<foo>` / `</foo>`.
pub fn system_like(text: &str) -> bool {
    let t = text.trim();
    if t.is_empty() || t.starts_with('<') {
        return true;
    }
    let b = t.as_bytes();
    b.windows(2).enumerate().any(|(i, w)| {
        w[0] == b'<'
            && (w[1].is_ascii_alphabetic() || w[1] == b'/')
            && t[i + 1..].find('>').is_some_and(|j| {
                t[i + 1..i + 1 + j]
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || "-_/: =\"'.".contains(c))
            })
    })
}

/// What a line of a reply is, for finding a question at its end.
#[derive(Clone, Copy, PartialEq)]
enum Line {
    Blank,
    /// Inside a code block (fenced or indented), or a fence itself.
    Code,
    /// A list item, or a line that carries one on.
    Item,
    /// A table row, a quote or a heading: never a question to the owner.
    Other,
    Prose,
}

fn list_item(l: &str) -> Option<&str> {
    let l = l.trim_start();
    if let Some(r) = l.strip_prefix("- ").or_else(|| l.strip_prefix("* ")) {
        return Some(r);
    }
    let digits = l.chars().take_while(|c| c.is_ascii_digit()).count();
    (digits > 0)
        .then(|| &l[digits..])
        .and_then(|r| r.strip_prefix(". ").or_else(|| r.strip_prefix(") ")))
}

fn line_kinds(text: &str) -> Vec<(&str, Line)> {
    let mut out: Vec<(&str, Line)> = vec![];
    let mut in_fence = false;
    for line in text.lines() {
        let t = line.trim();
        let prev = out
            .iter()
            .rev()
            .find(|(_, k)| *k != Line::Blank)
            .map(|(_, k)| *k);
        let after_blank = out.last().is_none_or(|(_, k)| *k == Line::Blank);
        let indented = line.starts_with("    ") || line.starts_with('\t');
        let kind = if t.starts_with("```") || t.starts_with("~~~") {
            in_fence = !in_fence;
            Line::Code
        } else if in_fence {
            Line::Code
        } else if t.is_empty() {
            Line::Blank
        } else if list_item(line).is_some() {
            Line::Item
        } else if indented && prev == Some(Line::Item) {
            // Carries a list item on.
            Line::Item
        } else if indented && (after_blank || prev == Some(Line::Code)) {
            Line::Code
        } else if t.starts_with('|') || t.starts_with('>') || t.starts_with('#') {
            Line::Other
        } else if prev == Some(Line::Item) && !after_blank {
            // A lazy continuation of the item above.
            Line::Item
        } else {
            Line::Prose
        };
        out.push((line, kind));
    }
    out
}

/// A line's last sentence ends in "?": inline code taken out, and bold,
/// a closing bracket or an emoji after it ignored. A question inside
/// quotes ("she asked 'why?'") is not asking.
fn ends_asking(line: &str) -> bool {
    let mut plain = String::new();
    let mut in_code = false;
    for c in line.chars() {
        if c == '`' {
            in_code = !in_code;
        } else if !in_code {
            plain.push(c);
        }
    }
    let end = plain.trim_end_matches(|c: char| {
        c.is_whitespace() || matches!(c, '*' | '_' | ')') || (!c.is_ascii() && !c.is_alphanumeric())
    });
    if !end.ends_with('?') {
        return false;
    }
    // Filter rhetorical questions (PCS-11): a sentence must contain a
    // question-word that invites a reply; short sentences like "Why not?" or
    // "Isn't it?" are considered rhetorical and do not wake the phone.
    // Only the line's last sentence counts ("All done. Why not?" is "Why not?").
    let last_sentence = end
        .trim_end_matches('?')
        .rsplit(['.', '!', '?'])
        .next()
        .unwrap_or_default();
    let content = last_sentence
        .chars()
        .filter(|c| c.is_alphabetic() || c.is_whitespace())
        .collect::<String>()
        .to_lowercase();
    let words: Vec<&str> = content.split_whitespace().collect();
    // A one- or two-word sentence is never an explicit question.
    if words.len() <= 2 {
        return false;
    }
    // A trailing offer ("Want me to ...?") after finished work is not a
    // blocker, however many things it lists. "Do you want A or B?" is a
    // choice and still waits.
    let flat = words.join(" ");
    const OFFERS: &[&str] = &[
        "want me to",
        "do you want me to",
        "would you like me to",
        "would you like to have me",
        "should i also offer",
    ];
    if OFFERS.iter().any(|o| flat.starts_with(o)) {
        return false;
    }
    const QUESTION_WORDS: &[&str] = &[
        "who", "what", "where", "when", "why", "how", "which", "would", "could", "should", "can",
        "is", "are", "do", "did", "want", "shall", "will",
    ];
    words.iter().any(|w| QUESTION_WORDS.contains(w))
}

/// Splits a reply into (the rest, the question to the owner) when it ends
/// by asking something. Only the reply's last sentence outside lists,
/// quotes, tables and code counts: it must end in "?" and be the end of the
/// reply, or be followed only by a list of options (then the line before
/// the list asks, as in "Which one? Options:").
pub fn question_split(text: &str) -> Option<(String, String)> {
    let lines = line_kinds(text);
    let mut end = lines.len();
    while end > 0 && lines[end - 1].1 == Line::Blank {
        end -= 1;
    }
    // Options after the question: a list, as the reply's very end.
    let mut at = end;
    while at > 0 && matches!(lines[at - 1].1, Line::Item | Line::Blank) {
        at -= 1;
    }
    let has_list = at < end;
    let last = at.checked_sub(1)?;
    let (line, kind) = lines[last];
    if kind != Line::Prose {
        return None;
    }
    let asks = if has_list {
        let l = line.trim_end();
        ends_asking(l)
            || l.strip_suffix(':')
                .and_then(|l| l.rsplit_once('?'))
                .is_some_and(|(_, after)| !after.contains(['.', '!', '?']))
    } else {
        ends_asking(line)
    };
    if !asks {
        return None;
    }
    // The question's paragraph starts after the last blank or non-prose line.
    let mut start = last;
    while start > 0 && lines[start - 1].1 == Line::Prose {
        start -= 1;
    }
    let join = |ls: &[(&str, Line)]| {
        ls.iter()
            .map(|(l, _)| *l)
            .collect::<Vec<_>>()
            .join("\n")
            .trim()
            .to_string()
    };
    Some((join(&lines[..start]), join(&lines[start..end])))
}

/// The choices a question offers ("- A", "1. B", "2) C"), as plain text,
/// for answer buttons. Empty unless the question asks the owner to choose
/// ("Which…", "… or …", "pick", "prefer", an option) and lists 2 to 8
/// short items.
pub fn question_choices(question: &str) -> Vec<String> {
    let lead: String = question
        .lines()
        .take_while(|l| list_item(l).is_none())
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase();
    let lead_end = lead.trim_end();
    let choosing = (lead_end.ends_with('?') || lead_end.ends_with(':'))
        && [
            "which", " or ", "prefer", "choose", "pick", "option", "rather",
        ]
        .iter()
        .any(|w| lead.contains(w));
    if !choosing {
        return vec![];
    }
    let mut out = vec![];
    for line in question.lines() {
        if let Some(item) = list_item(line) {
            let clean: String = item.replace("**", "").replace('`', "").trim().to_string();
            if !clean.is_empty() {
                out.push(clean);
            }
        }
    }
    if out.len() < 2 || out.len() > 8 || out.iter().any(|c| c.chars().count() > 160) {
        return vec![];
    }
    out
}

/// Changed lines from Claude Code's result for an edit or a new file.
fn patch_lines(detail: &Value) -> Vec<String> {
    let mut out: Vec<String> = vec![];
    if let Some(hunks) = detail.get("structuredPatch").and_then(Value::as_array) {
        for (i, h) in hunks.iter().enumerate() {
            if i > 0 {
                out.push(" …".into());
            }
            for l in h
                .get("lines")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                if let Some(l) = l.as_str() {
                    out.push(l.to_string());
                }
            }
        }
    }
    if out.is_empty()
        && detail.get("type").and_then(Value::as_str) == Some("create")
        && let Some(c) = detail.get("content").and_then(Value::as_str)
    {
        out = c.lines().map(|l| format!("+{l}")).collect();
    }
    if out.len() > MAX_PATCH_LINES {
        let more = out.len() - MAX_PATCH_LINES;
        out.truncate(MAX_PATCH_LINES);
        out.push(format!(" … {more} more lines"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn system_text_is_never_a_title() {
        for t in [
            "<command-message>resume</command-message>",
            "  <system-reminder>",
            "Caveat <local-command-stdout>x",
            "",
        ] {
            assert!(system_like(t), "{t:?}");
        }
        for t in ["Fix the gate", "Is 3 < 4?", "Use a<b in the loop"] {
            assert!(!system_like(t), "{t:?}");
        }
    }

    #[test]
    fn questions_are_found() {
        let (rest, q) = question_split("I fixed it.\n\nShould I also add a test?").unwrap();
        assert_eq!(rest, "I fixed it.");
        assert_eq!(q, "Should I also add a test?");
        // Options after the question stay with it.
        let (rest, q) = question_split("Done.\n\nWhich do you want?\n\n- **A**\n- **B**").unwrap();
        assert_eq!(rest, "Done.");
        assert!(q.starts_with("Which do you want?") && q.ends_with("- **B**"));
        let (_, q) = question_split("Done.\nWhich do you want?\n- A\n- B").unwrap();
        assert!(q.starts_with("Done.\nWhich"), "{q}");
        // A bold question still counts, and so does one with an emoji.
        // A trailing offer is done work, not a question (R2b).
        assert!(question_split("**Want me to push it?**").is_none());
        assert!(question_split("Shall I push it? 🙂").is_some());
        assert!(question_split("Shall I run `make`?").is_some());
        // "…? Options:" before a list asks.
        assert!(question_split("Which first? Pick one:\n\n- A\n- B").is_some());
        // Not a question to the owner.
        assert!(question_split("All done.").is_none());
        assert!(question_split("```\nlet x = a?;\n```").is_none());
        // A drafted email with a question inside is not a question to the owner.
        assert!(
            question_split(
                "Here's a draft:\n\nHi Dana,\n\nCould someone take a look at the heater?\n\nThanks so much,\nSam"
            )
            .is_none()
        );
        assert!(question_split("Could you check it?\n\nSam").is_none());
        // A heading that is a question is not asking the owner.
        assert!(
            question_split("## Why did the build fail?\n\nThe path was wrong, so I fixed it.")
                .is_none()
        );
        // A question far up in a long reply is not the ending.
        assert!(question_split("Why? Because.\n\na\n\nb\n\nc\n\nd\n\ne\n\nf\n\ng").is_none());
    }

    /// PCS-11: rhetorical questions do not wake the phone.
    #[test]
    fn needs_you_rhetorical() {
        // "Why not?" is a 2-word rhetorical challenge — not a real question.
        assert!(question_split("All done. Why not?").is_none());
        // "What should I do next?" asks the owner for a decision.
        assert!(question_split("What should I do next?").is_some());
    }

    /// The reply that made the owner's phone say "Claude asks you": a list
    /// whose last item is a rhetorical question.
    #[test]
    fn a_list_ending_in_a_question_is_not_asking() {
        let reply = "- Rust programs are fast and use little memory.\n- \
                     Isn't it nice when the compiler catches your mistakes before you do?";
        assert!(question_split(reply).is_none());
        assert!(question_split(&format!("Here you go:\n\n{reply}")).is_none());
        assert!(question_split(&format!("Two points:\n{reply}")).is_none());
        assert!(question_split("1. Done\n2. Is that it?").is_none());
    }

    #[test]
    fn only_the_last_sentence_counts() {
        for t in [
            // A question, then more words.
            "Does that make sense? I think so.",
            "Why did it fail? The path was wrong.\n\nHope that helps.",
            // Code, tables, quotes.
            "The check is `a ? b : c`",
            "Use `a ? b : c?`",
            "| Q? | yes |\n| --- | --- |",
            "She said:\n\n> Is it done?",
            "She asked 'why?'",
            "    let y = x?;",
            "Here:\n\n    if ready?\n",
            "The answer:\n\n```\nok?\n```",
            // A heading at the end.
            "## Want more?",
        ] {
            assert!(question_split(t).is_none(), "{t:?}");
        }
    }

    /// R2b: trailing offers are done; real decisions still wait.
    #[test]
    fn offers_are_done_and_decisions_wait() {
        for t in [
            "Hi there! Want me to tweak the tone, make it shorter or longer, or personalize it with specific details?",
            "Fixed it.\n\nWould you like me to add a test?",
            "Done. Do you want me to push it?",
        ] {
            assert!(question_split(t).is_none(), "{t:?}");
        }
        for t in [
            "Should I delete the old builds?",
            "Which folder should I use?",
            "Do you want A or B?",
            "Fixed. Shall I commit?",
        ] {
            assert!(question_split(t).is_some(), "{t:?}");
        }
    }

    /// R2b: a chat never waits on the owner; a task with the same text does.
    #[test]
    fn a_chat_never_needs_you() {
        let ask = "Should I delete the old builds?";
        let mut chat = Task::new(
            "c".into(),
            "hi".into(),
            crate::paths::chats_dir().to_string_lossy().into_owned(),
            0,
        );
        chat.add_you("hi");
        chat.entries.push(Entry::Claude(ask.into()));
        chat.status = Status::Done;
        assert!(chat.is_chat());
        assert!(!chat.question_waiting() && !chat.needs_owner());
        let mut task = Task::new("t".into(), "t".into(), "/p".into(), 0);
        task.add_you("hi");
        task.entries.push(Entry::Claude(ask.into()));
        task.status = Status::Done;
        assert!(task.question_waiting() && task.needs_owner());
    }

    #[test]
    fn choices_become_answers() {
        assert_eq!(
            question_choices("Which first?\n\n- **Budget**: money\n- Destination\n1. x"),
            ["Budget: money", "Destination", "x"]
        );
        assert_eq!(
            question_choices("Should I keep it or drop it?\n- Keep\n- Drop"),
            ["Keep", "Drop"]
        );
        assert!(question_choices("Shall I commit?").is_empty());
        assert!(question_choices("Pick:\n- only one").is_empty());
        // A list that isn't a choice gives no answer buttons.
        assert!(question_choices("What's in it? Please include:\n\n- name\n- amount").is_empty());
        assert!(question_choices("- a statement\n- a question?").is_empty());
    }

    /// One state per task, the same words everywhere: a finished answer is
    /// Done at once (not Live for minutes), Live elsewhere only for a
    /// conversation going on outside, and Working counts work elsewhere.
    #[test]
    fn one_state_per_task() {
        let mut t = Task::new("t".into(), "t".into(), "/p".into(), 0);
        t.add_you("What is the capital of Norway?");
        t.entries.push(Entry::Claude("Oslo.".into()));
        t.status = Status::Done;
        t.active_unix = Some(crate::needs::now());
        assert_eq!(t.status_words(), "Done");
        assert!(!t.working_now(false));
        t.live_elsewhere = true;
        assert_eq!(t.status_words(), "Live elsewhere");
        assert!(!t.working_now(false));
        // A followed session whose turn goes on: working, though this PC's
        // Claude isn't.
        t.status = Status::Working;
        assert_eq!(t.status_words(), "Working");
        assert!(t.working_now(false));
        // Waiting for the owner is never also working.
        t.status = Status::NeedsYou;
        assert!(!t.working_now(true));
    }

    #[test]
    fn a_waiting_question_needs_the_owner() {
        let mut t = Task::new("t".into(), "t".into(), "/p".into(), 0);
        t.add_you("fix it");
        t.entries
            .push(Entry::Claude("Fixed. Shall I commit?".into()));
        t.status = Status::Done;
        assert!(t.question_waiting() && t.needs_owner());
        assert_eq!(t.status_words(), "Question for you");
        t.add_you("yes");
        assert!(!t.question_waiting());
    }

    /// PCS-09: summary turn is OFF by default for new tasks.
    #[test]
    fn summary_off_by_default() {
        let t = Task::new("t1".into(), "My task".into(), "/project".into(), 0);
        assert!(
            !t.summary_enabled,
            "summary_enabled must be false for a new task"
        );
    }

    /// PCS-06: a second Phone too start on an already-active conversation
    /// returns an error and no new process is started.
    #[test]
    fn phone_too_no_double() {
        let mut t = Task::new("t2".into(), "My task".into(), "/project".into(), 0);
        t.phone_too = true;

        // First activation succeeds.
        let r1 = phone_too_start(&mut t);
        assert!(r1.is_ok(), "first start should succeed");
        assert!(t.phone_too_active);

        // Second activation fails.
        let r2 = phone_too_start(&mut t);
        assert!(r2.is_err(), "second start must return error");
        assert!(r2.unwrap_err().contains("already active"));
    }
}
