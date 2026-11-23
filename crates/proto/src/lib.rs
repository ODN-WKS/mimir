//! The messages Mímir's service, its window and its phones send
//! each other, and the task data they carry. Wire types only: no window,
//! no async runtime.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Status {
    Working,
    NeedsYou,
    Paused,
    Stopped,
    Done,
}

impl Status {
    pub fn words(self) -> &'static str {
        match self {
            Status::Working => "Working",
            Status::NeedsYou => "Needs your OK",
            Status::Paused => "Paused",
            Status::Stopped => "Stopped",
            Status::Done => "Done",
        }
    }
}

/// How much Claude may do without asking (Claude Code's permission modes).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum Mode {
    /// Asks before edits and commands.
    #[default]
    Ask,
    /// Edits files without asking; still asks before commands.
    AcceptEdits,
    /// Looks around and writes a plan; changes nothing.
    Plan,
    /// Its own safety check decides.
    Auto,
    /// Never asks: everything Claude does runs straight away ("YOLO").
    NoAsk,
}

impl Mode {
    pub const ALL: [Mode; 5] = [
        Mode::Ask,
        Mode::AcceptEdits,
        Mode::Plan,
        Mode::Auto,
        Mode::NoAsk,
    ];

    /// The three the menu offers (the in-between ones only show for a task
    /// that already uses one).
    pub const MENU: [Mode; 3] = [Mode::Ask, Mode::NoAsk, Mode::Plan];

    /// The modes Ctrl+Shift+M steps through: never into "Don't ask" by a
    /// stray key press.
    pub const CYCLE: [Mode; 2] = [Mode::Ask, Mode::Plan];

    pub fn words(self) -> &'static str {
        match self {
            Mode::Ask => "Ask me first",
            Mode::AcceptEdits => "Edit without asking",
            Mode::Plan => "Plan first",
            Mode::Auto => "Safety check decides",
            Mode::NoAsk => "Don't ask (YOLO)",
        }
    }

    /// A few words for the menu, e.g. "safest".
    pub fn short(self) -> &'static str {
        match self {
            Mode::Ask => "asks before changing anything",
            Mode::AcceptEdits => "asks only before commands",
            Mode::Plan => "writes a plan, changes nothing",
            Mode::Auto => "asks only when it looks risky",
            Mode::NoAsk => "does everything without asking",
        }
    }

    pub fn explain(self) -> &'static str {
        match self {
            Mode::Ask => "Asks before changing files or running commands.",
            Mode::AcceptEdits => "Changes files without asking, and asks before commands.",
            Mode::Plan => "Looks around and writes a plan, without changing anything.",
            Mode::Auto => "Its own safety check decides what needs your OK.",
            Mode::NoAsk => {
                "Mímir changes files and runs commands without ever asking. Fastest, \
                            and nothing stops a mistake: undo still works for files."
            }
        }
    }

    /// The `--permission-mode` value.
    pub fn cli(self) -> &'static str {
        match self {
            Mode::Ask => "default",
            Mode::AcceptEdits => "acceptEdits",
            Mode::Plan => "plan",
            Mode::Auto => "auto",
            Mode::NoAsk => "bypassPermissions",
        }
    }
}

/// The full story of a step, shown when its card is opened.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct StepDetail {
    /// The command, file path or query.
    pub input: String,
    /// What came back, shortened if very long.
    pub output: String,
    /// Changed lines, each starting with ' ', '-' or '+'.
    pub patch: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Place {
    ThisPc,
    Online,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Step {
    pub tool_use_id: String,
    pub tool: String,
    /// Plain words, e.g. "Ran a command: cargo build".
    pub what: String,
    pub place: Place,
    pub started_ms: u64,
    pub took_ms: Option<u64>,
    /// None while running.
    pub ok: Option<bool>,
    /// Set when the owner said no.
    pub refused: bool,
    /// Set when the owner pressed Pause or Stop while it ran.
    #[serde(default)]
    pub stopped: bool,
    #[serde(default)]
    pub detail: StepDetail,
}

impl Step {
    /// One line for the chat, e.g. "Ran a command: cargo build · 2 min".
    /// Steps run here say nothing about place; quick ones no time; a
    /// success no word — only what needs noticing is added.
    pub fn line(&self) -> String {
        // A step that was not allowed never happened: "Write x", not "Wrote x".
        let what = if self.refused {
            not_yet(&self.what)
        } else {
            self.what.clone()
        };
        let mut parts = vec![what];
        if self.place == Place::Online {
            parts.push("online".into());
        }
        if self.refused {
            parts.push("not allowed".into());
        } else if self.stopped {
            parts.push("stopped".into());
        } else {
            match self.ok {
                None => parts.push("working…".into()),
                Some(ok) => {
                    if let Some(ms) = self.took_ms.filter(|ms| *ms >= 2000) {
                        parts.push(nice_time(ms));
                    }
                    if !ok {
                        parts.push("failed".into());
                    }
                }
            }
        }
        parts.join(" · ")
    }
}

/// "Wrote x" → "Write x": for a step that has not happened (yet).
pub fn not_yet(what: &str) -> String {
    for (done, todo) in [
        ("Wrote ", "Write "),
        ("Changed ", "Change "),
        ("Ran ", "Run "),
        ("Created ", "Create "),
        ("Deleted ", "Delete "),
        ("Opened ", "Open "),
        ("Searched ", "Search "),
    ] {
        if let Some(rest) = what.strip_prefix(done) {
            return format!("{todo}{rest}");
        }
    }
    what.to_string()
}

pub fn nice_time(ms: u64) -> String {
    let s = ms / 1000;
    if s < 1 {
        "under 1 s".into()
    } else if s < 60 {
        format!("{s} s")
    } else {
        format!("{} min", (s + 30) / 60)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Entry {
    You(String),
    Claude(String),
    Step(Step),
    /// Something the app itself says, e.g. "You stopped this task."
    Note(String),
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PlanItem {
    pub id: String,
    pub text: String,
    pub done: bool,
    pub doing: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct WeeklyUse {
    pub used: f64,
    pub resets_at: i64,
}

/// A picture to send with a message.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Picture {
    /// e.g. "image/png"
    pub media_type: String,
    pub base64: String,
}

/// A file to send with a chat message ("chat_files"): the PC reads what it
/// can of it (a PDF, a document, a sheet, a zip, a sound recording, plain text).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SentFile {
    /// The name as the person knows it, e.g. "Lease.pdf".
    pub name: String,
    /// e.g. "application/pdf" (may be empty: the PC goes by the name).
    #[serde(default)]
    pub media_type: String,
    pub base64: String,
}

/// How much of a file the PC read, in plain words for the phone to show
/// under the file ("Read all of it", "Read pages 1 to 40 of 120").
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FileNote {
    pub name: String,
    pub note: String,
}

/// A change git sees in a project.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GitChange {
    pub path: String,
    pub added: u32,
    pub removed: u32,
    /// "new", "changed", "deleted" or "renamed"
    pub kind: String,
}

/// A change between a snapshot and now: (path, what an undo does to it).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UndoChange {
    pub path: String,
    /// "put back" (changed or deleted since), "remove" (created since).
    pub action: String,
}

/// Git facts for a project's changes panel.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct GitInfo {
    pub branch: Option<String>,
    /// None when the project is not a git repository.
    pub changes: Option<Vec<GitChange>>,
}

/// A message that waits until Claude is done.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct QueuedMessage {
    pub text: String,
    pub files: Vec<std::path::PathBuf>,
}

/// The protocol number `Hello` and `Welcome` carry.
pub const PROTO: u32 = 2;

/// What this PC can do beyond [`PROTO`] (see `Event::Welcome`): "search"
/// (`Call::WebSearch`), "lookup" (`Call::Lookup`), "talk" (`Call::Talk`,
/// `TalkStop`, `TalkTurn` and the `TalkPiece` / `TalkDone` events),
/// "chat_sync" (`Call::ChatPut`, `ChatsSince`, `SetGroundRules` and
/// `Event::ChatChanged`), "truth" (`Event::Truth`) and "chat_only"
/// (`Call::Talk`'s `chat_only`) and "engines" (`Call::SetEngines`, and
/// `chats_engine` / `project_engine` in the `Models` reply) and "chat_cut"
/// (`ChatTurn::gone`: an edited message cuts the later turns away) and
/// "chat_model" (`Call::SetEngines`'s `chat_model`, and `chats_model` /
/// `chat_models` in the `Models` reply) and "people" (the PC tells phones
/// apart by person: `Event::Welcome`'s `person`, and what a person's phone
/// is shown follows from it) and "models_changed" (`Event::ModelsChanged`: what
/// a person may choose changed; the phone asks `Models` again) and
/// "chat_pictures" (`ChatTurn::pictures`: a chat message can carry pictures
/// for the PC's model, and the `Models` reply's `sees_pictures` says
/// whether the PC's answerer can look at them) and "chat_files"
/// (`ChatTurn::files`: a chat message can carry files for the PC to read, and
/// `Filed::Talk::files_read` says how much of each was read) and "task_files"
/// (`Call::NewTask::files` and `Call::Send::files`: a project task's message
/// can carry files, and its `pictures` are kept for the task too). A client lists the
/// ones it knows in `Hello`, and only gets the new events when it does.
pub const FEATURES: &[&str] = &[
    "search",
    "lookup",
    "talk",
    "chat_sync",
    "truth",
    "chat_only",
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

/// What `ConfirmPair.person` says for the owner's own phone. Anything else
/// is a first name (someone else's phone); nothing at all is refused, so
/// forgetting to say whose phone it is never makes it the owner's.
pub const WHOSE_MINE: &str = "\u{1}mine";

/// Where a chat answer ran.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Ran {
    /// The PC's own free model.
    Pc,
    /// The phone's own model.
    Phone,
    /// A paid model (Claude) through the PC.
    Paid,
}

/// A source under an answer: what was looked up for it.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ChatSource {
    pub site: String,
    pub url: String,
    /// The words that were sent to the model.
    #[serde(default)]
    pub words: String,
    /// A blocked search: this is the results page to open.
    #[serde(default)]
    pub results: bool,
    /// How it was found, when not from the phone itself.
    #[serde(default)]
    pub via: String,
}

fn one() -> u32 {
    1
}

/// One message of a general chat, the same on the phone and the PC. The
/// side where a message is typed makes its id (a UUID); an answer's id is
/// `<the message's id>.a`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ChatTurn {
    pub id: String,
    pub user: bool,
    /// The text form (pictures and files reduced to words, as the phone's
    /// `Turn.forModel()` does).
    pub text: String,
    #[serde(default)]
    pub sources: Vec<ChatSource>,
    /// Where an answer ran (None for the owner's own messages).
    #[serde(default)]
    pub ran: Option<Ran>,
    /// E.g. "Qwen 3.6 · Your PC".
    #[serde(default)]
    pub model_words: Option<String>,
    /// Milliseconds since 1970 on the device that made it.
    pub at: i64,
    /// An answer that replaces another (answered again on the phone) has a
    /// higher version; the highest is kept.
    #[serde(default = "one")]
    pub version: u32,
    /// The PC's change number for this turn (0 until the PC has it).
    #[serde(default)]
    pub seq: u64,
    /// Cut away: the owner edited an earlier message, so this turn (and
    /// every one after it) is gone. A tombstone has a higher version than
    /// the turn it replaces and no text; older apps simply keep showing
    /// the old turn.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub gone: bool,
    /// Pictures sent with a message ("chat_pictures"), already shrunk and
    /// without location. Only in a `Talk` call: the PC keeps them apart
    /// from the chat's turns and never sends them back.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub pictures: Vec<Picture>,
    /// Files sent with a message ("chat_files"), at most 3 of 10 MB. Only in
    /// a `Talk` call: the PC reads them, keeps only what it read (apart from
    /// the chat's turns, with the person's chat) and never sends them back.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub files: Vec<SentFile>,
    /// A short plain note under an answer that a different model than the
    /// usual one wrote ("Answered by Qwen 3.5 because your usual model
    /// can't look at pictures."). Older apps ignore it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub switch_note: Option<String>,
}

/// A general chat as the PC keeps it. In `ChatsSince` and `ChatChanged`,
/// `turns` holds only the turns changed after the asked-for number.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ChatRecord {
    pub id: String,
    /// The name the owner gave it (None: its topic is worked out from the
    /// first message).
    #[serde(default)]
    pub name: Option<String>,
    /// When a message was last sent in it (milliseconds since 1970).
    pub at: i64,
    /// The PC's change number of its newest change.
    pub seq: u64,
    pub turns: Vec<ChatTurn>,
    /// Deleted at (milliseconds since 1970): a deleted chat never comes
    /// back, and its turns are gone.
    #[serde(default)]
    pub deleted: Option<i64>,
}

/// The reply to `ChatsSince`: every chat changed after the asked-for
/// number, and the newest number.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ChatsPage {
    pub seq: u64,
    pub chats: Vec<ChatRecord>,
    /// Another page follows: ask again from `seq`.
    #[serde(default)]
    pub more: bool,
}

/// The reply to `ChatPut`: the PC's change number, and whether the chat
/// was deleted (then the phone deletes its copy too).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ChatPutDone {
    pub seq: u64,
    pub deleted: bool,
}

/// The ground rules and when they were last changed (milliseconds since
/// 1970): whichever side changed them last wins.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct GroundRules {
    pub text: String,
    pub at: i64,
}

/// The reply to `TalkTurn`: how an answer the PC was asked for stands.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum TalkState {
    /// Still being written: the text so far.
    Answering { text: String },
    /// Finished and saved.
    Done { turn: ChatTurn },
    /// It couldn't be answered, in plain words.
    Failed { why: String },
    /// The PC knows nothing of it (asked again after a restart, say).
    Unknown,
}

/// A task's state, worked out once on the PC for every surface.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TaskState {
    Working,
    NeedsYou,
    LiveElsewhere,
    Stopped,
    Done,
}

/// The PC in one line.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TruthPc {
    /// "Your PC" or the name the owner set.
    pub name: String,
    pub level: PcLevel,
    /// What's going on, in one line ("" when nothing).
    pub line: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TruthTask {
    pub id: String,
    pub project: String,
    pub title: String,
    pub state: TaskState,
    /// Claude works on it in the background (Stop shows; no reply box).
    pub background: bool,
    /// When it went into this state (seconds since 1970).
    #[serde(default)]
    pub since: Option<i64>,
    pub from_phone: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TruthCounts {
    pub working: u32,
    pub needs_you: u32,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TruthProject {
    pub path: String,
    pub name: String,
    #[serde(default)]
    pub last_activity: Option<i64>,
    pub working: u32,
    pub needs_you: u32,
    /// Only for a project with core checks.
    #[serde(default)]
    pub checks: Option<CheckFacts>,
}

/// Where chats run and on what.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TruthEngine {
    /// "Your PC" (or its name), or "your phone" when the PC can't.
    pub chats_where: String,
    /// One line for people, e.g. "Chats run on your PC's graphics card ·
    /// about 40 words a second".
    pub chats_model_words: String,
    /// The PC is answering a chat right now.
    pub answering: bool,
    /// A paid model (Claude) is chosen for something.
    pub paid_on: bool,
}

/// Claude's usage, in percent, only while a paid Claude choice is on.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TruthUsage {
    pub weekly: Option<WeeklyUse>,
    pub five_hour: Option<WeeklyUse>,
    /// When the figures were read (seconds since 1970).
    #[serde(default)]
    pub as_of: Option<i64>,
}

/// One snapshot of everything the owner sees about the PC's work: every
/// surface reads it and none works a state out itself. A client takes it
/// when `run_id` is new, or `rev` is higher for the same `run_id`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Truth {
    pub run_id: String,
    pub rev: u64,
    /// Seconds since 1970.
    pub as_of: i64,
    pub pc: TruthPc,
    pub tasks: Vec<TruthTask>,
    pub counts: TruthCounts,
    pub projects: Vec<TruthProject>,
    pub engine: TruthEngine,
    #[serde(default)]
    pub usage: Option<TruthUsage>,
}

/// A task as clients see it: everything but its conversation, plus what
/// the service works out for it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TaskHeader {
    pub id: String,
    pub title: String,
    pub title_by_owner: bool,
    pub project: String,
    pub origin: Option<String>,
    pub copy_branch: Option<String>,
    /// Started from the phone or claude.ai through a project's Remote
    /// Control: followed live, answered there. The address that opens it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub from_phone: Option<String>,
    pub session_id: Option<String>,
    pub status: Status,
    pub mode: Mode,
    pub model: Option<String>,
    pub auto_choice: Option<String>,
    pub effort: Option<String>,
    pub plan: Vec<PlanItem>,
    pub plan_doc: Option<String>,
    pub files_changed: Vec<String>,
    pub cost_usd: f64,
    pub weekly: Option<WeeklyUse>,
    pub context_used: u64,
    pub context_window: u64,
    pub created_unix: i64,
    pub brief: Option<String>,
    /// The owner's messages (entry indices) that can be undone to.
    pub checkpoints: Vec<usize>,
    pub redo: bool,
    pub question_dismissed: Option<usize>,
    pub archived: bool,
    /// Incognito tasks live only in the service's memory.
    pub incognito: bool,
    pub entry_count: usize,
    /// A Claude Code process runs for it.
    pub running: bool,
    /// Claude is working on it right now.
    pub busy: bool,
    /// Permission requests waiting, oldest first (the hook's request).
    pub asks: Vec<serde_json::Value>,
    /// A message waiting until Claude is done.
    pub queued: Option<QueuedMessage>,
    /// Claude's limit ran out during this task (and when it is back).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ran_out: Option<RanOut>,
    /// A chat started with no project that Mímir filed itself.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub placed: Option<Placed>,
    /// Why it was filed there, while that's a guess.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub placed_why: Option<String>,
    /// When its conversation last changed, wherever it was going on (the
    /// PC, the phone, claude.ai, a terminal); seconds since 1970.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_unix: Option<i64>,
    /// Its conversation is going on outside Mímir right now (a
    /// terminal, the Claude app): replies go there, not from here.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub live_elsewhere: bool,
}

/// One change "Tidy projects…" proposes, shown before anything is done.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TidyRow {
    pub kind: TidyKind,
    /// The folder it is about now.
    pub folder: String,
    /// A conversation that is a task here.
    #[serde(default)]
    pub task: Option<String>,
    /// A conversation that isn't a task yet: its Claude Code history file.
    #[serde(default)]
    pub session: Option<String>,
    /// The conversation's title ("" for a folder).
    #[serde(default)]
    pub title: String,
    /// The project it goes to (None: it stays where it is).
    #[serde(default)]
    pub to: Option<String>,
    /// It is archived too (hidden, kept under Archived).
    #[serde(default)]
    pub archive: bool,
    /// Why, in a few plain words.
    #[serde(default)]
    pub why: String,
    /// Claude is still on it: it moves when it stops.
    #[serde(default)]
    pub waits: bool,
    /// Ticked: done when the owner says so.
    pub on: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TidyKind {
    /// A git project beside the others that isn't listed: it is added.
    Add,
    /// A stand-in folder of a project: its names go to the project and it
    /// leaves the list (its conversations have their own rows).
    Merge,
    /// A folder that is no project (home, chats, Documents): it leaves the
    /// list once its conversations have gone.
    Unlist,
    /// A conversation that moves, is archived, or both.
    Conversation,
}

/// How a chat started with no project was filed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Placed {
    /// It named the project, or clearly pointed to it.
    Sure,
    /// The best guess: a placement note waits in the project until the
    /// owner confirms it or the work clearly belongs there.
    Unsure,
    /// Nothing fitted: it went to General.
    General,
}

/// Claude's usage limit ran out during a task.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RanOut {
    /// When Claude is back (seconds since 1970).
    pub back_at: i64,
    /// The Claude model the task was on, to go back to.
    pub claude_model: Option<String>,
    /// The task carries on on a free model meanwhile (false: it waits for
    /// the owner to choose).
    pub on_free: bool,
}

/// How a task's work ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FinishedHow {
    /// It finished its work.
    Ok,
    /// The owner stopped it.
    Stopped,
    /// It stopped with a problem.
    Failed,
}

/// A task's work ended. Carries no words from the task: the phone makes
/// its own generic ones.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FinishedItem {
    /// Stable for this end of work; a phone shows each id once.
    pub id: String,
    pub task: String,
    pub how: FinishedHow,
    /// Seconds since 1970.
    pub at: i64,
}

/// What a needs-you item is about.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum NeedsKind {
    /// Claude asks before a step (from the task's approvals socket).
    Permission,
    /// Claude's reply ends with a question.
    Question,
    /// A session asks the owner to check a fix ("It works" / "Still broken").
    CheckFix,
    /// A session asks the owner to approve something (a design, a plan).
    Approve,
    /// A session asks the owner to pick one of a few choices.
    Choose,
    /// A session asks before releasing.
    Release,
    /// A core check started failing.
    CheckFailing,
}

impl NeedsKind {
    /// Serious items are the ones a phone tells its person about with a
    /// sound (the others show quietly).
    pub fn serious(self) -> bool {
        matches!(
            self,
            NeedsKind::Permission
                | NeedsKind::Question
                | NeedsKind::Choose
                | NeedsKind::Release
                | NeedsKind::CheckFailing
        )
    }
}

/// Something waiting for the owner, the same on every phone and window:
/// the first answer from any of them wins.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NeedsYouItem {
    /// Stable while the item waits.
    pub ask_id: String,
    pub kind: NeedsKind,
    /// The project folder.
    pub project: String,
    pub task: Option<String>,
    /// A short title, e.g. "Change a file".
    pub title: String,
    /// What it's about, e.g. the file, the command or Claude's question.
    pub text: String,
    /// A few lines to look at first (a change, a command).
    pub preview: Vec<String>,
    /// The choices to tap (a question's listed options, an ask's choices).
    pub choices: Vec<String>,
    pub serious: bool,
    /// Seconds since 1970.
    pub created: i64,
    /// A link to look at (an ask's `--link`).
    #[serde(default)]
    pub link: Option<String>,
}

/// An answer to a needs-you item. Which ones fit each kind is in
/// `ItemAnswer::fits`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum ItemAnswer {
    Allow,
    DontAllow,
    /// Allow, and allow later edits to this one file in this task.
    AllowFileThisTask,
    /// Allow, and remember for this project (window only).
    AlwaysProject,
    /// One of the item's choices, by its number.
    Option(usize),
    /// Free text: a question's answer, what to change, a choice not listed.
    Text(String),
    KeepForLater,
    Works,
    StillBroken,
    Approve,
    Release,
    AskClaudeToFix,
}

impl ItemAnswer {
    /// Whether this answer fits an item of this kind.
    pub fn fits(&self, kind: NeedsKind) -> bool {
        use ItemAnswer as A;
        use NeedsKind as K;
        match kind {
            K::Permission => matches!(
                self,
                A::Allow | A::DontAllow | A::AllowFileThisTask | A::AlwaysProject
            ),
            K::Question => matches!(self, A::Option(_) | A::Text(_) | A::KeepForLater),
            K::CheckFix => matches!(self, A::Works | A::StillBroken),
            K::Approve => matches!(self, A::Approve | A::Text(_)),
            K::Choose => matches!(self, A::Option(_) | A::Text(_)),
            K::Release => matches!(self, A::Release | A::KeepForLater),
            K::CheckFailing => matches!(self, A::AskClaudeToFix | A::KeepForLater),
        }
    }

    /// The answer in a few words, for the asking session and the log.
    pub fn words(&self, choices: &[String]) -> String {
        match self {
            ItemAnswer::Allow => "Allow".into(),
            ItemAnswer::DontAllow => "Don't allow".into(),
            ItemAnswer::AllowFileThisTask => "Always allow edits to this file (this task)".into(),
            ItemAnswer::AlwaysProject => "Always allow in this project".into(),
            ItemAnswer::Option(n) => choices.get(*n).cloned().unwrap_or_default(),
            ItemAnswer::Text(t) => t.clone(),
            ItemAnswer::KeepForLater => "Keep for later".into(),
            ItemAnswer::Works => "It works".into(),
            ItemAnswer::StillBroken => "Still broken".into(),
            ItemAnswer::Approve => "Approved".into(),
            ItemAnswer::Release => "Release".into(),
            ItemAnswer::AskClaudeToFix => "Ask to fix it".into(),
        }
    }
}

/// The core checks of one project, from its run log.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct CheckFacts {
    /// Features whose checks all passed, and all features with checks.
    pub passing: usize,
    pub total: usize,
    /// Features that failed, unreliable, or could not run: (id, sentence).
    pub failing: Vec<(String, String)>,
    pub unreliable: Vec<(String, String)>,
    pub could_not_run: Vec<(String, String)>,
    /// Every feature with a result: (id, sentence, passed).
    pub features: Vec<(String, String, bool)>,
    /// The commit these results are for.
    pub commit: Option<String>,
    /// Seconds since 1970: the newest run counted.
    pub when: Option<i64>,
    /// Seconds since 1970: the first run of the failures still going on.
    pub failing_since: Option<i64>,
}

/// What the status page shows for one project, built from facts only (the
/// run log, the tasks, git): no Claude call.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct StatusFacts {
    /// The project folder and its name.
    pub project: String,
    pub name: String,
    pub version: Option<String>,
    /// None when the project has no run log (no checks set up).
    pub checks: Option<CheckFacts>,
    pub tasks_running: usize,
    pub needs_you: usize,
    /// None when the project isn't a git repository.
    pub uncommitted: Option<usize>,
    pub unpushed: Option<usize>,
    /// Seconds since 1970.
    pub last_activity: Option<i64>,
    /// One line in plain words, e.g. "1 of 5 failing: calls on cell signal".
    pub line: String,
}

/// The whole status page: projects (needs-you first, then failing, then by
/// last activity), usage, and the chat's reply in sentences.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct StatusPage {
    pub projects: Vec<StatusFacts>,
    pub weekly: Option<WeeklyUse>,
    pub five_hour: Option<WeeklyUse>,
    /// The chat's status reply, in short sentences.
    pub reply: Vec<String>,
    /// Seconds since 1970: when these facts were read.
    pub data_time: i64,
    /// What Claude is doing on the PC, in one line (only while the Labs
    /// switch "Show what Claude is doing on this PC" is on).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pc_line: Option<String>,
}

/// How free the PC is for its owner.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum PcLevel {
    /// Nothing of Claude's is running.
    #[default]
    Free,
    /// Work in the background: the PC is fine to use.
    Background,
    /// Something needs the screen, the mouse and keyboard, or a device.
    DontTouch,
}

/// One thing going on behind the scenes.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ActivityRow {
    /// Who: a worker's name, "Tasks", "Builds and tests", a device.
    pub name: String,
    /// What, in plain words.
    pub what: String,
    /// "worker", "task", "builds", "sessions", "device" or "screen".
    pub kind: String,
    /// Seconds since 1970 it started (None: not known).
    #[serde(default)]
    pub since: Option<i64>,
    /// What it needs left alone: "screen", "input", "device:<name>", "cpu".
    #[serde(default)]
    pub needs: Vec<String>,
    /// Until about when, as the worker said it.
    #[serde(default)]
    pub until: Option<String>,
}

/// What Claude is doing on this PC: the level, one sentence, and the rows.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct PcActivity {
    pub level: PcLevel,
    /// The sentence at the top of the card (and on the desktop banner).
    pub line: String,
    pub rows: Vec<ActivityRow>,
    /// Processor threads busy right now, and the most Claude's jobs may use.
    pub threads_busy: f32,
    pub threads_cap: u32,
    /// The same in one short line for a phone.
    pub phone_line: String,
}

/// How the general chat filed a message.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Filed {
    /// A new task started in `project`.
    NewTask { project: String, task: String },
    /// Sent to the task the conversation is already about.
    SameTask { project: String, task: String },
    /// Kept in the project's ideas.
    Idea { project: String, idea: String },
    /// Answered from facts.
    Status { reply: Vec<String> },
    /// A general chat the PC answers: the answer's id, where it runs, and
    /// a line to show while waiting ("Waking your PC's model…"), if any.
    Talk {
        answer: String,
        ran: Ran,
        model_words: String,
        #[serde(default)]
        waiting: Option<String>,
        /// What was read of each file sent with the message ("chat_files").
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        files_read: Vec<FileNote>,
    },
    /// The PC can't answer this one (busy, no model that fits): the phone
    /// answers it itself.
    OnPhone { why: String },
    /// Not clear which project: it waits in the Inbox with the likeliest
    /// projects to pick from.
    Inbox {
        inbox_id: String,
        candidates: Vec<String>,
    },
}

/// A message from the general chat waiting for its project.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct InboxItem {
    pub inbox_id: String,
    pub msg_id: String,
    pub text: String,
    pub candidates: Vec<String>,
    pub time: i64,
}

fn five() -> u32 {
    5
}

/// What a site answered to a `Lookup`: its status, where it redirects
/// (the phone checks that address and asks again), its type and its text.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LookupReply {
    pub status: u16,
    #[serde(default)]
    pub location: Option<String>,
    pub content_type: String,
    pub body: String,
}

/// One web search result from the PC's SearXNG.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebResult {
    pub title: String,
    pub url: String,
    pub snippet: String,
    /// The search engines SearXNG found it with ("brave", "google cse").
    #[serde(default)]
    pub engines: Vec<String>,
}

/// An idea kept for a project.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Idea {
    pub id: String,
    pub time: i64,
    pub text: String,
    #[serde(default)]
    pub msg_id: Option<String>,
    #[serde(default)]
    pub removed: bool,
}

/// An update for the owner to read or hear: a finished task's summary, a
/// check that changed, or a session's spoken update.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UpdateCard {
    pub id: String,
    /// The project folder ("" when it belongs to none).
    pub project: String,
    pub text: String,
    /// Seconds since 1970.
    pub time: i64,
    /// The task it is about, if any (the card opens it).
    #[serde(default)]
    pub task: Option<String>,
    /// Its audio (an Ogg Opus file the phone fetches with `GetFile`), once made.
    #[serde(default)]
    pub audio: Option<String>,
    /// Why there is no audio, in plain words (e.g. the voice isn't downloaded).
    #[serde(default)]
    pub audio_note: Option<String>,
}

/// An answer to a permission request.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Choice {
    Allow,
    /// Allow, and remember for this project (window only).
    AlwaysProject,
    DontAllow,
    /// The task was paused or stopped while it waited.
    Halted,
}

/// What a client asks the service to do. Every call gets one `Reply`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Call {
    Hello {
        proto: u32,
        app_version: String,
        client_name: String,
        /// The client's desktop environment, for the tasks it starts.
        env: Vec<(String, String)>,
        /// The [`FEATURES`] the client knows (empty for older clients).
        #[serde(default)]
        features: Vec<String>,
    },
    NewTask {
        project: String,
        text: String,
        #[serde(default)]
        pictures: Vec<Picture>,
        /// Files sent with the first message ("task_files"), at most 3 of
        /// 10 MB; kept for the task and named in the message to Claude Code.
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        files: Vec<SentFile>,
        mode: Mode,
        model: Option<String>,
        effort: Option<String>,
        separate_copy: bool,
        /// A phone's message id: sent again (after the link dropped), it
        /// gets the first reply instead of a second task.
        #[serde(default)]
        msg_id: Option<String>,
        /// The phone's ground rules, used for this task instead of the PC's.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        ground_rules: Option<String>,
    },
    Send {
        task: String,
        text: String,
        #[serde(default)]
        pictures: Vec<Picture>,
        /// Files sent with the message ("task_files"), at most 3 of 10 MB.
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        files: Vec<SentFile>,
        #[serde(default)]
        msg_id: Option<String>,
    },
    /// A message from the phone's general chat: the service files it (a
    /// status question, an idea, a request) in the right project. The
    /// reply is a `Filed`.
    Chat {
        msg_id: String,
        text: String,
        /// The phone's ground rules, for a task the message starts.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        ground_rules: Option<String>,
    },
    /// A general-chat message for the PC to answer ("talk"). `turns` is
    /// the conversation for the model, the new message (`turn_id`) last;
    /// `lookup` is what was looked up for it, `sources` where from. The
    /// PC files a message naming a project as a task instead (and answers
    /// a status question from the facts); the reply is a `Filed`.
    Talk {
        chat_id: String,
        turn_id: String,
        turns: Vec<ChatTurn>,
        #[serde(default)]
        lookup: Option<String>,
        #[serde(default)]
        sources: Vec<ChatSource>,
        #[serde(default)]
        effort: Option<String>,
        #[serde(default)]
        ground_rules: Option<String>,
        /// The project task this chat is in (its last project card's),
        /// until that task is done or the owner taps ✕: a follow-up goes
        /// there unless it names another project.
        #[serde(default)]
        task: Option<String>,
        /// "Just chat": answered as a general chat even when it names a
        /// project (and never sent to the task the chat was in).
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        chat_only: bool,
    },
    /// Settings › Models "What answers" ("engines"): chats "free" or
    /// "paid", project work "free" or "claude" (None: unchanged). The reply
    /// is `{"chats_engine", "project_engine"}`, as `Models` also gives.
    SetEngines {
        #[serde(default)]
        chats: Option<String>,
        #[serde(default)]
        project: Option<String>,
        /// Which Claude model answers chats ("chat_model"): "auto" (Claude
        /// Code's own choice) or one of the `chat_models` the `Models` reply
        /// lists; applies to every chat on this PC. None: unchanged.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        chat_model: Option<String>,
    },
    /// Stops the answer to `turn_id`.
    TalkStop {
        chat_id: String,
        turn_id: String,
    },
    /// How the answer to `turn_id` stands; the reply is a `TalkState`.
    TalkTurn {
        chat_id: String,
        turn_id: String,
    },
    /// New or changed turns of a chat ("chat_sync"), or its deletion. The
    /// reply is a `ChatPutDone`.
    ChatPut {
        chat_id: String,
        #[serde(default)]
        name: Option<String>,
        turns: Vec<ChatTurn>,
        #[serde(default)]
        deleted: bool,
    },
    /// Every chat changed after `seq`; the reply is a `ChatsPage`.
    ChatsSince {
        seq: u64,
    },
    /// The ground rules changed on the phone at `at`; the reply is the
    /// rules that count (`GroundRules`).
    SetGroundRules {
        text: String,
        at: i64,
    },
    /// Files an Inbox message in the project the owner picked.
    FileInbox {
        inbox_id: String,
        project: String,
    },
    /// The messages waiting in the Inbox; the reply is a list of `InboxItem`.
    Inbox,
    /// "Move it": stops a misfiled task and starts it again in `project`
    /// with the message and the handover summary. The reply is the new
    /// task's id.
    MoveTask {
        task: String,
        project: String,
    },
    /// "Move to…" on a task: the task, conversation and all, goes to
    /// another project folder and carries on there. Refused while Claude
    /// works on it ("Move it when it stops").
    MoveTo {
        task: String,
        project: String,
    },
    /// "That's right" on a chat Mímir filed by its best guess: the
    /// place is confirmed and the placement note goes.
    ConfirmPlacement {
        task: String,
    },
    /// Settings › Storage › "Tidy projects…": the changes it proposes; the
    /// reply is a list of `TidyRow`.
    TidyPlan,
    /// Makes the ticked changes. The reply is `{"done", "waiting",
    /// "problems"}`: those waiting move when Claude stops working on them.
    Tidy {
        rows: Vec<TidyRow>,
    },
    /// "Merge into…" on a project: every task of `from` (archived ones too)
    /// moves to `into`, conversation and all, and `from` leaves the list,
    /// its names kept as `into`'s aliases. Nothing moves while one of its
    /// tasks works. The reply is how many tasks moved.
    MergeProject {
        from: String,
        into: String,
    },
    /// "Undo" on an idea: removes it.
    UndoIdea {
        project: String,
        idea: String,
    },
    /// A project's ideas; the reply is a list of `Idea`.
    Ideas {
        project: String,
    },
    /// A web search for the phone's Mímir through the owner's own SearXNG on
    /// this PC (only that, never any other address); the reply is a list of
    /// `WebResult`, at most `max`. Nothing of it is kept.
    WebSearch {
        query: String,
        #[serde(default = "five")]
        max: u32,
    },
    /// One of the phone Mímir's lookups (the weather, an encyclopedia)
    /// fetched by the PC, so a slow or failed phone network isn't the
    /// answer. Only addresses on the PC's own fixed list of sites are
    /// fetched (never any other: the PC is no open proxy); the reply is a
    /// `LookupReply`. Nothing of it is kept.
    Lookup {
        url: String,
    },
    /// The status page; the reply is a `StatusPage`.
    Status,
    /// The updates of the last 90 days, newest first; the reply is a list
    /// of `UpdateCard`.
    Updates,
    /// A file the service offers (an update's audio): the reply is
    /// `{"name", "base64"}`.
    GetFile {
        name: String,
    },
    /// Audio of one conversation entry (a chat reply's play button): the
    /// reply is the file name for `GetFile`.
    Speak {
        task: String,
        entry: usize,
    },
    /// The models to offer: the reply is `{"free": [FreeModel…], "free_model",
    /// "switch_to_free", "ollama": "" or why free models can't run now}`.
    Models,
    /// "Switch to free near my limit" and the free model it uses.
    SetFreeChoice {
        switch_to_free: Option<bool>,
        free_model: Option<String>,
    },
    /// "Continue on Claude" when a free model ran out of room, or "Use Claude
    /// instead": a new task on Auto in the same project, from a summary of
    /// this one (its first message if nothing was done yet). The reply is
    /// the new task's id.
    ContinueOnClaude {
        task: String,
    },
    /// Claude's limit ran out: carry this task on on the free model.
    ContinueOnFree {
        task: String,
    },
    /// Claude is back: the task goes back to the Claude model it was on.
    BackToClaude {
        task: String,
    },
    /// The weekly look for a better phone model, on or off.
    SetWeeklyCheck {
        on: bool,
    },
    /// The PC's phone settings: the reply is `{"pc_name", "relays"}`.
    PhoneSettings,
    /// The name phones show for this PC (empty: the PC's own name).
    SetPcName {
        name: String,
    },
    /// Phones reach the PC through relays when they can't connect directly
    /// (from the PC's next start).
    SetRelays {
        on: bool,
    },
    /// The files a phone always allowed for a task: the reply is a list.
    AlwaysAllowed {
        task: String,
    },
    /// Stop always allowing edits to one of those files.
    StopAllowing {
        task: String,
        file: String,
    },
    /// An Incognito task: a free model in a sealed space on the PC, never
    /// saved. The reply is its id.
    NewIncognito {
        project: String,
        text: String,
        /// A free model (`ollama:<name>`).
        model: String,
        mode: Mode,
        /// The phone's ground rules, used instead of the PC's.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        ground_rules: Option<String>,
    },
    /// "End and delete history": ends the Incognito task and everything of it.
    EndIncognito {
        task: String,
    },
    /// A free-model task ran out of room, or the owner tapped "Use Claude
    /// instead": a new task on Auto in the same project, from a summary of
    /// this one (its first message if nothing was done yet). The reply is
    /// the new task's id.
    /// What the owner said: 16 kHz mono 16-bit WAV, base64. The reply is
    /// the text.
    Listen {
        wav_base64: String,
    },
    /// An answer to a needs-you item (the first answer wins).
    AnswerItem {
        ask_id: String,
        answer: ItemAnswer,
    },
    Queue {
        task: String,
        text: String,
        files: Vec<std::path::PathBuf>,
    },
    /// Takes the waiting message back; the reply carries it.
    Unqueue {
        task: String,
    },
    SendQueued {
        task: String,
    },
    Pause {
        task: String,
    },
    Stop {
        task: String,
    },
    Continue {
        task: String,
    },
    /// An answer to a task's permission request. `ask_id` names the
    /// request it answers (its needs-you id); the PC refuses the answer when
    /// that request is no longer the one waiting. A phone must send it.
    Answer {
        task: String,
        choice: Choice,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        ask_id: Option<String>,
    },
    SetMode {
        task: String,
        mode: Mode,
    },
    SetModel {
        task: String,
        model: Option<String>,
    },
    SetEffort {
        task: String,
        effort: Option<String>,
    },
    ApprovePlan {
        task: String,
        mode: Mode,
    },
    Rename {
        task: String,
        title: String,
    },
    Archive {
        task: String,
        archived: bool,
    },
    RemoveTask {
        task: String,
    },
    /// A note from Mímir in the conversation.
    Note {
        task: String,
        text: String,
    },
    DismissQuestion {
        task: String,
    },
    PastSessions {
        project: String,
    },
    ImportPast {
        project: String,
        session: serde_json::Value,
    },
    BringIn {
        task: String,
    },
    RemoveCopy {
        task: String,
    },
    UndoPreview {
        task: String,
        entry: usize,
    },
    RedoPreview {
        task: String,
    },
    Undo {
        task: String,
        entry: usize,
    },
    Redo {
        task: String,
    },
    PhoneKeys {
        task: String,
        bytes: Vec<u8>,
    },
    /// The service's settings keys, as they are now in the window.
    SetSettings {
        settings: serde_json::Value,
    },
    Transcript {
        task: String,
    },
    /// The project whose git facts the window shows (every 5 s).
    WatchGit {
        project: Option<String>,
    },
    RefreshGit {
        project: String,
    },
    /// Window focus, for desktop notifications.
    Focus {
        focused: bool,
    },
    /// The owner dismissed a problem.
    DismissProblem {
        text: String,
    },
    /// Everything after event `since` (or a snapshot).
    Sync {
        since: u64,
    },
    /// Stops the service (only an in-process one, as the window closes).
    Shutdown,
    /// A new pairing code for a phone (window only); the reply is the code.
    PairCode,
    /// The owner's answer to "Do the words match?" (window only).
    ConfirmPair {
        pending: u64,
        yes: bool,
        /// "Whose phone is this?": [`WHOSE_MINE`] is the owner's own
        /// ("Mine"), else the first name of someone else. None (or blank)
        /// with `yes` is refused.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        person: Option<String>,
    },
    /// Lets a person (not the owner) use the owner's online plan for their
    /// chats, or stops it (window only).
    PersonOnline {
        person: String,
        on: bool,
    },
    /// Gives a person (not the owner) another first name (window only).
    RenamePerson {
        person: String,
        name: String,
    },
    /// Removes a person with their phones and every chat of theirs on this
    /// PC (window only).
    RemovePerson {
        person: String,
    },
    /// The paired phones (window only); the reply is the list.
    Phones,
    /// Unpairs a phone and closes its connections (window only).
    RemovePhone {
        key: String,
    },
    /// The link's id and local sockets (window only; checks connect directly).
    LinkInfo,
    /// The project folders and the last one used; the reply is
    /// `{"projects": [{"path", "name"}], "last": path or null}`.
    Projects,
    /// Makes a project folder named `name` (a plain name, no path) beside the
    /// owner's other projects, or uses it if it's already there, and adds it
    /// to the project list. The reply is `{"path", "name", "made": bool}`.
    NewProject {
        name: String,
    },
}

impl Call {
    /// Calls a paired phone may make. Others are refused, and so are a
    /// phone's tasks in any mode but "Ask me first" or "Plan first".
    pub fn phone_may(&self) -> bool {
        match self {
            Call::Hello { .. }
            | Call::Sync { .. }
            | Call::Projects
            | Call::NewProject { .. }
            | Call::Chat { .. }
            | Call::Talk { .. }
            | Call::TalkStop { .. }
            | Call::TalkTurn { .. }
            | Call::ChatPut { .. }
            | Call::ChatsSince { .. }
            | Call::SetGroundRules { .. }
            | Call::SetEngines { .. }
            | Call::FileInbox { .. }
            | Call::Inbox
            | Call::UndoIdea { .. }
            | Call::Ideas { .. }
            | Call::WebSearch { .. }
            | Call::Lookup { .. }
            | Call::Status
            | Call::Updates
            | Call::GetFile { .. }
            | Call::Speak { .. }
            | Call::Listen { .. }
            | Call::Models
            | Call::SetFreeChoice { .. }
            | Call::SetWeeklyCheck { .. }
            | Call::PhoneSettings
            | Call::SetPcName { .. }
            | Call::SetRelays { .. }
            | Call::AlwaysAllowed { .. }
            | Call::StopAllowing { .. }
            | Call::ContinueOnClaude { .. }
            | Call::EndIncognito { .. }
            | Call::Send { .. }
            | Call::Pause { .. }
            | Call::Stop { .. }
            | Call::Continue { .. }
            | Call::Rename { .. }
            | Call::Archive { .. }
            | Call::SetModel { .. }
            | Call::DismissQuestion { .. } => true,
            Call::NewTask {
                mode,
                separate_copy,
                ..
            } => matches!(mode, Mode::Ask | Mode::Plan) && !separate_copy,
            // YOLO only when the PC's Settings allow it (the service checks).
            Call::SetMode { mode, .. } => matches!(mode, Mode::Ask | Mode::Plan | Mode::NoAsk),
            Call::NewIncognito { mode, .. } => matches!(mode, Mode::Ask | Mode::Plan),
            // A task moved from the phone starts again asking first.
            Call::MoveTask { .. } | Call::MoveTo { .. } => true,
            Call::AnswerItem { answer, .. } => !matches!(answer, ItemAnswer::AlwaysProject),
            Call::Answer { choice, .. } => matches!(choice, Choice::Allow | Choice::DontAllow),
            _ => false,
        }
    }
}

/// A call with its number.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CallMsg {
    pub call_id: u64,
    pub call: Call,
}

/// What the service tells its clients.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Event {
    Welcome {
        proto: u32,
        pc_name: String,
        pc_version: String,
        min_app: String,
        /// New for each start of the service.
        run_id: String,
        /// Calls this PC knows that an older one doesn't ([`FEATURES`]): a
        /// phone sends such a call only when its PC lists it, since an
        /// unknown call ends the phone's session on an older PC.
        #[serde(default)]
        features: Vec<String>,
        /// Whose phone this is (an id that only means "the same person as
        /// before"; "owner" for the PC owner's phones). Empty for the
        /// window and for an older PC. A phone that finds another person
        /// than the one it had clears what it kept of the old one.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        person: String,
    },
    /// Everything after this is a full snapshot (a client starts afresh).
    Snapshot {
        run_id: String,
    },
    /// The snapshot is complete.
    Ready,
    Task {
        header: Box<TaskHeader>,
    },
    Removed {
        task: String,
    },
    /// Conversation entries from `from` on (earlier ones are unchanged).
    Entries {
        task: String,
        from: usize,
        entries: Vec<Entry>,
    },
    /// Text Claude is writing right now.
    Live {
        task: String,
        text: String,
    },
    /// Phone too's terminal screen.
    Screen {
        task: String,
        lines: Vec<String>,
    },
    Usage {
        weekly: Option<WeeklyUse>,
        five_hour: Option<WeeklyUse>,
        /// When the figures were last refreshed (unix seconds).
        #[serde(default)]
        updated_at: Option<i64>,
    },
    /// A problem in plain words, shown once.
    Problem {
        text: String,
    },
    Commands {
        slash_commands: Vec<String>,
        connectors: Vec<(String, String)>,
    },
    /// The service's settings keys.
    Settings {
        settings: serde_json::Value,
    },
    GitInfo {
        project: String,
        info: GitInfo,
    },
    Reply {
        call_id: u64,
        result: Result<serde_json::Value, String>,
    },
    /// A phone gave the right pairing code: compare its words (window only).
    PairPending {
        pending: u64,
        phone_name: String,
        words: Vec<String>,
    },
    /// A pairing ended: paired, or refused (window only).
    PairEnded {
        pending: u64,
        paired: bool,
    },
    /// Everything waiting for the owner, the whole list each time it changes.
    NeedsYou {
        items: Vec<NeedsYouItem>,
    },
    /// The newest updates (up to 100), the whole list each time it changes.
    Updates {
        cards: Vec<UpdateCard>,
    },
    /// Labs: a test is using the phones. Each phone with the Labs switch on
    /// shows "<name> is testing — please don't touch" over its screen until
    /// about `until` (a clock time in words, "7:15 PM"). Only the PC sends it.
    TestNotice {
        name: String,
        what: String,
        until: String,
    },
    /// Labs: the test is over; the notice comes down.
    TestNoticeOff,
    /// What Claude is doing on this PC, each time it changes (window only;
    /// only while the Labs switch is on, else `None`).
    Activity {
        state: Option<PcActivity>,
    },
    /// The next words of a chat answer the PC is writing (`n` counts the
    /// pieces from 0; a client that missed one asks `TalkTurn`). Thinking
    /// comes as pieces with `thinking` set and is never the answer.
    TalkPiece {
        chat_id: String,
        turn_id: String,
        n: u32,
        text: String,
        #[serde(default)]
        thinking: bool,
    },
    /// A chat answer is finished and saved (or couldn't be given).
    TalkDone {
        chat_id: String,
        turn_id: String,
        result: Result<ChatTurn, String>,
    },
    /// A chat changed on the PC: its changed turns, its name, or its
    /// deletion, with the PC's change number.
    ChatChanged {
        chat: ChatRecord,
    },
    /// The whole truth, each time something a person sees changes.
    Truth {
        truth: Box<Truth>,
    },
    /// A task's work ended (the owner's phones only; never for a Whisper).
    /// Feature "finished_events".
    Finished {
        item: FinishedItem,
    },
    /// The last few `Finished` items, sent with a snapshot so a phone that
    /// connects late shows each once (it dedupes by `id`). Feature
    /// "finished_events".
    RecentFinished {
        items: Vec<FinishedItem>,
    },
    /// What this person may choose changed (the owner allowed or took away
    /// their use of the online plan): the phone asks `Models` again.
    ModelsChanged,
}

impl Event {
    /// Events a paired phone gets: never the terminal screen, commands,
    /// settings, git facts or pairing.
    pub fn for_phone(&self) -> bool {
        matches!(
            self,
            Event::Welcome { .. }
                | Event::Snapshot { .. }
                | Event::Ready
                | Event::Task { .. }
                | Event::Removed { .. }
                | Event::Entries { .. }
                | Event::Live { .. }
                | Event::Usage { .. }
                | Event::Problem { .. }
                | Event::Reply { .. }
                | Event::NeedsYou { .. }
                | Event::Updates { .. }
                | Event::TestNotice { .. }
                | Event::TestNoticeOff
                | Event::TalkPiece { .. }
                | Event::TalkDone { .. }
                | Event::ChatChanged { .. }
                | Event::Truth { .. }
                | Event::ModelsChanged
                | Event::Finished { .. }
                | Event::RecentFinished { .. }
        )
    }

    /// Events a person other than the owner gets from the general
    /// broadcast: never tasks, needs-you items, updates, usage, problems
    /// or the PC's truth about the owner's work. Their chats' events go
    /// only to them, one by one.
    pub fn for_other_people(&self) -> bool {
        matches!(
            self,
            Event::Welcome { .. }
                | Event::Snapshot { .. }
                | Event::Ready
                | Event::Reply { .. }
                | Event::TestNotice { .. }
                | Event::TestNoticeOff
        )
    }

    /// The feature a client must list in its `Hello` to get this event.
    pub fn feature(&self) -> Option<&'static str> {
        match self {
            Event::TalkPiece { .. } | Event::TalkDone { .. } => Some("talk"),
            Event::ChatChanged { .. } => Some("chat_sync"),
            Event::Truth { .. } => Some("truth"),
            Event::ModelsChanged => Some("models_changed"),
            Event::Finished { .. } | Event::RecentFinished { .. } => Some("finished_events"),
            _ => None,
        }
    }

    /// Events a reconnecting client can catch up on from the ring (the
    /// rest are sent as their latest value, or only to one caller).
    pub fn durable(&self) -> bool {
        !matches!(
            self,
            Event::Live { .. }
                | Event::Screen { .. }
                | Event::Reply { .. }
                // A phone that connects later must not show a notice for a test long over.
                | Event::TestNotice { .. }
                | Event::TestNoticeOff
                | Event::Activity { .. }
                // Sent with each snapshot.
                | Event::RecentFinished { .. }
                // Caught up by `TalkTurn`, `ChatsSince` and the truth sent on connect.
                | Event::TalkPiece { .. }
                | Event::TalkDone { .. }
                | Event::ChatChanged { .. }
                | Event::Truth { .. }
                | Event::ModelsChanged
        )
    }
}

/// An event with its number.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EventMsg {
    pub seq: u64,
    pub event: Event,
}
