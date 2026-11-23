//! The window's state and what each click does. Drawing lives in the
//! `view_*` modules; they only record actions, applied after each frame.

use crate::app_view::AppPanel;
use crate::pcstats::PcStats;
use crate::places::{self, Settings};
use crate::setup_view::SetupPanel;
use crate::theme;
use eframe::egui::{self, Key};
use egui_commonmark::CommonMarkCache;
use mimir_core::approvals::Answer;
use mimir_core::client::Station;
use mimir_core::events::Picture;
use mimir_core::sessions::PastSession;
use mimir_core::task::{Mode, Status};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{Receiver, Sender};

/// A picture the background finished shrinking: the box's key, and the picture (None: unreadable).
pub(crate) type ShrunkPic = (String, Option<Picture>);
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Settings' sections, in the order the section list shows them.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub enum SettingsPage {
    #[default]
    General,
    Models,
    Phone,
    Voice,
    Notifications,
    Storage,
    Labs,
    About,
}

impl SettingsPage {
    pub const ALL: [SettingsPage; 8] = [
        SettingsPage::General,
        SettingsPage::Models,
        SettingsPage::Phone,
        SettingsPage::Voice,
        SettingsPage::Notifications,
        SettingsPage::Storage,
        SettingsPage::Labs,
        SettingsPage::About,
    ];

    pub fn name(self) -> &'static str {
        match self {
            SettingsPage::General => "General",
            SettingsPage::Models => "Models",
            SettingsPage::Phone => "Phones",
            SettingsPage::Voice => "Voice",
            SettingsPage::Notifications => "Notifications",
            SettingsPage::Storage => "Storage",
            SettingsPage::Labs => "Labs",
            SettingsPage::About => "About",
        }
    }

    /// The section's one-line explanation, under its heading.
    pub fn about(self) -> &'static str {
        match self {
            SettingsPage::General => "Who answers, how the window looks, and project work.",
            SettingsPage::Models => {
                "Who answers, the free models on your PC, the phone's models, and an optional online plan."
            }
            SettingsPage::Phone => "Pair a phone, and how phones reach your PC.",
            SettingsPage::Voice => "The voice that reads replies aloud, and Talk.",
            SettingsPage::Notifications => "How you hear that a task needs you or is done.",
            SettingsPage::Storage => {
                "Everything Mímir keeps on your PC, how big it is, and how to remove it."
            }
            SettingsPage::Labs => "Notes for fixing problems, checks, and reporting a problem.",
            SettingsPage::About => "Your version, updates, and help.",
        }
    }

    /// Words a search finds this section by (besides its name).
    fn words(self) -> &'static str {
        match self {
            SettingsPage::General => {
                "powers online anthropic claude free backup text size zoom summary finish shortcuts keyboard claude code github sign in connectors mcp start"
            }
            SettingsPage::Models => {
                "model models online anthropic claude haiku sonnet opus fable auto free download store limit backup runs out uncensored phone worth switching weekly instructions"
            }
            SettingsPage::Phone => {
                "phone phones pair pairing qr unpair remove connections pc name android app"
            }
            SettingsPage::Voice => {
                "voice read aloud speed pauses alba talk voice typing speech microphone mic dictation"
            }
            SettingsPage::Notifications => "notifications alerts sound battery push",
            SettingsPage::Storage => {
                "storage space disk delete remove size free models voice speech data folder clean tidy organize organise projects sort move merge archive"
            }
            SettingsPage::Labs => {
                "labs notice test don't touch experiment behind the scenes activity doing busy bug report problem troubleshoot log notes checks github"
            }
            SettingsPage::About => "about version update help install repair uninstall",
        }
    }

    /// Whether a search finds this section (an empty search finds all).
    pub fn matches(self, search: &str) -> bool {
        let s = search.trim().to_lowercase();
        s.is_empty()
            || self.name().to_lowercase().contains(&s)
            || s.split_whitespace()
                .all(|w| self.words().split(' ').any(|k| k.starts_with(w)))
    }

    /// A section by its name, any case (debug pictures open one by name).
    pub fn named(name: &str) -> Option<SettingsPage> {
        let n = name.trim().to_lowercase();
        Self::ALL.into_iter().find(|p| {
            let own = p.name().to_lowercase();
            own == n || own.split(' ').next() == Some(n.as_str())
        })
    }
}

#[derive(PartialEq, Eq, Clone, Copy, Debug)]
pub enum View {
    Task,
    /// A general chat (or the box to start one).
    Chat,
    NewTask,
    Settings,
}

/// Effort choices: (shown name, `--effort` value, one-line description).
pub const EFFORTS: [(&str, Option<&str>, &str); 6] = [
    ("Default (Medium)", None, "Its own choice"),
    ("Low", Some("low"), "quick answers"),
    ("Medium", Some("medium"), "a little thinking"),
    ("High", Some("high"), "thinks it through"),
    ("Extra high", Some("xhigh"), "thinks longer"),
    ("Max", Some("max"), "thinks longest, uses the most"),
];

pub enum Action {
    Select(String),
    /// An answer to a needs-you item (another Claude session's request).
    AnswerItem(String, mimir_core::proto::ItemAnswer),
    /// "That's right" on a chat filed by its best guess.
    ConfirmPlacement(String),
    /// Settings › Storage › Tidy projects…: ask what it would change.
    TidyPlan,
    /// Make the tidy's ticked changes.
    Tidy(Vec<mimir_core::proto::TidyRow>),
    NewTask,
    OpenSettings,
    /// Settings › Models.
    OpenModels,
    CloseSettings,
    Start {
        project: String,
        text: String,
        files: Vec<PathBuf>,
        mode: Mode,
        model: Option<String>,
        effort: Option<String>,
        /// Work in a separate copy of the project.
        separate: bool,
    },
    /// An Incognito task: free model, sealed space, never saved.
    StartIncognito {
        project: String,
        text: String,
        model: String,
        mode: Mode,
    },
    /// "End and delete history" for an Incognito task.
    EndIncognito(String),
    Send(String, String, Vec<PathBuf>),
    Pause(String),
    Stop(String),
    Continue(String),
    Answer(String, Answer),
    Remove(String),
    PickFolder,
    AddTypedFolder(String),
    /// Makes a new project folder beside the other projects (on this PC).
    NewProject(String),
    SetMode(String, Mode),
    SetModel(String, Option<String>),
    /// Claude's limit ran out: carry on on the free model.
    ContinueOnFree(String),
    /// Claude is back: the task goes back to it.
    BackToClaude(String),
    SetEffort(String, Option<String>),
    ApprovePlan(String, Mode),
    Rename(String, String),
    Archive(String, bool),
    PickAttachments,
    OpenPast(String),
    ImportPast(String, PastSession),
    ShowDiff(String, String),
    ShowLines(String, Vec<String>),
    OpenPath(PathBuf),
    OpenTerminal(PathBuf),
    RemoteControl(PathBuf),
    /// Show or hide the side panel; carries whether it is shown now.
    ToggleDetails(bool),
    /// The New task page with this project chosen.
    NewTaskIn(String),
    /// The first task that needs an answer.
    GoToWaiting,
    ShowShortcuts,
    /// The microphone button: talk instead of type.
    Mic,
    /// Find in this conversation (Ctrl+F).
    Find,
    /// Send this when Claude is done with what it is doing.
    Queue(String, String, Vec<PathBuf>),
    /// Take the waiting message back; true puts it in the message box.
    Unqueue(String, bool),
    /// Send the waiting message right away.
    SendQueued(String),
    SaveConversation(String),
    /// Save this text as a prompt (project, text).
    AskSavePrompt(String, String),
    /// The saved-prompts window (for this project).
    ManagePrompts(String),
    /// Arranging the sidebar (see `organize`).
    Organize(crate::organize::Organize),
    DismissProblem(usize),
    /// The waiting question needs no reply.
    DismissQuestion(String),
    BringIn(String),
    /// Ask before deleting a task's copy.
    ConfirmRemoveCopy(String),
    RemoveCopy(String),
    /// Save the new-task choices as this project's defaults.
    SaveDefaults(String),
    /// Show what undoing to before the owner's message would change.
    UndoPreview(String, usize),
    Undo(String, usize),
    Redo(String),
    RedoPreview(String),
    /// Put the cursor in the message box.
    FocusComposer,
    /// Switch background Remote Control for a project folder on or off.
    ToggleRemote(String),
    NextTask(bool),
    /// A general chat, opened from the sidebar.
    OpenChat(String),
    /// The box to start a new chat.
    NewChat,
    /// The box to start a new chat, with Whisper switched on when a free
    /// model is chosen.
    NewWhisper,
    /// Settings › Phones (the sidebar's "Phones").
    OpenPhones,
    /// Settings › About, where updates are (the sidebar's "Updates").
    OpenUpdates,
    /// Fold or open a sidebar section: true is Chats, false is Projects.
    ToggleSection(bool),
    /// Send a message in this chat (None: a new chat).
    SendChat(Option<String>, String),
    /// A picture in a sent message: copy it, save it somewhere, or open it.
    PictureCopy(Picture),
    PictureSave(Picture, String),
    PictureOpen(Picture, String),
    /// Take a picture out of the box (by its number).
    DropChatPicture(u64),
    StopChat(String),
    /// Ask the last message of a chat again; the new answer replaces the old.
    TryAgain(String),
    /// Start editing one of the owner's messages (chat, message, its words).
    StartEdit(String, String, String),
    /// Send the edited message: later messages are cut away, and it is asked again from there.
    EditChat(String, String, String),
    /// Put back what the last edit cut away.
    UndoEdit(String),
    /// Ask for a new name for a chat.
    AskRenameChat(String),
    /// Give a chat this name (here and on the phones).
    RenameChat(String, String),
    DeleteChat(String),
    /// Ask before deleting a chat.
    ConfirmDeleteChat(String),
    /// Put these words in the message box of the page that is open (a
    /// quote, a paste from the empty-space menu).
    Insert(String),
}

pub use mimir_core::proto::GitInfo;

/// When the service couldn't be reached: the window tries again by itself.
pub(crate) struct StartRetry {
    pub version: &'static str,
    /// The next try by itself.
    pub next: Instant,
    /// Tries so far (the wait between them grows, up to 30 s).
    pub tries: u32,
    /// A try under way, in the background.
    pub pending: Option<std::sync::mpsc::Receiver<Result<Station, String>>>,
}

/// The first wait before trying again; it doubles up to `RETRY_MAX`.
const RETRY_FIRST: Duration = Duration::from_secs(3);
const RETRY_MAX: Duration = Duration::from_secs(30);

pub struct App {
    pub(crate) station: Result<Station, String>,
    /// Trying to reach the service again (while `station` is an error).
    pub(crate) start_retry: StartRetry,
    pub(crate) settings: Settings,
    /// The settings changed this frame: saved at its end.
    pub(crate) settings_dirty: bool,
    /// The service's settings changes taken in so far.
    settings_seen: u64,
    /// The window's focus as last told to the service.
    focus_told: Option<bool>,
    /// The project whose git facts were last asked for.
    git_watched: Option<String>,
    pub(crate) selected: Option<String>,
    pub(crate) view: View,
    /// The Settings page shown.
    pub(crate) settings_page: SettingsPage,
    pub(crate) draft: String,
    pub(crate) attachments: Vec<PathBuf>,
    /// Whose unsent message and attachments are in `draft`/`attachments`:
    /// a task id, or NEW_TASK for the New task page.
    pub(crate) buffers_of: Option<String>,
    /// Unsent messages of the other tasks, kept while switching.
    pub(crate) drafts: HashMap<String, (String, Vec<PathBuf>)>,
    pub(crate) new_text: String,
    pub(crate) new_project: Option<String>,
    /// The owner chose the New task page's project: what they type no longer
    /// changes it.
    pub(crate) new_project_by_hand: bool,
    pub(crate) new_mode: Mode,
    pub(crate) new_model: Option<String>,
    /// New task: Incognito (a free model in a sealed space, nothing kept).
    pub(crate) new_incognito: bool,
    pub(crate) new_effort: Option<String>,
    /// The project whose defaults the new-task choices were last set from.
    pub(crate) defaults_for: Option<String>,
    pub(crate) picking: Option<Receiver<Option<PathBuf>>>,
    pub(crate) attaching: Option<Receiver<Vec<PathBuf>>>,
    pub(crate) typed_path: Option<String>,
    /// The name being typed for "New project…", while it's open.
    pub(crate) naming_project: Option<String>,
    pub(crate) stats: PcStats,
    pub(crate) setup: SetupPanel,
    pub(crate) app_panel: AppPanel,
    pub(crate) md: CommonMarkCache,
    pub(crate) search: String,
    /// The task being renamed: its id, the name typed, and how far the
    /// box has got (0 new, 1 asked for the cursor, 2 had it). Set afresh
    /// whenever a rename starts, so one never starts half-way.
    pub(crate) renaming: Option<(String, String, u8)>,
    pub(crate) open_steps: HashSet<String>,
    /// (title, diff lines) shown in a window.
    pub(crate) diff: Option<(String, Vec<String>)>,
    /// Past sessions offered for a project.
    pub(crate) past: Option<(String, Vec<PastSession>)>,
    pub(crate) shortcuts_open: bool,
    /// Settings › Phone.
    pub(crate) phone: crate::view_phone::PhonePanel,
    pub(crate) confirm_remove: Option<String>,
    /// A project waiting for "Remove project" to be confirmed.
    pub(crate) confirm_remove_project: Option<String>,
    /// The task whose "Move to…" picker is open.
    pub(crate) moving: Option<String>,
    /// The project whose "Merge into…" picker is open.
    pub(crate) merging: Option<String>,
    /// Settings › Labs: Run checks and Report a problem.
    pub(crate) labs: crate::view_labs::LabsPanel,
    /// "Add projects": git projects not listed yet, each ticked or not.
    pub(crate) adding: Option<Vec<(String, bool)>>,
    /// "Tidy projects…": the changes it proposes, while its dialog is open.
    pub(crate) tidying: Option<Vec<mimir_core::proto::TidyRow>>,
    /// What the last tidy did, in a line for Settings › Storage.
    pub(crate) tidy_result: Option<String>,
    /// The small name dialog: what is named, and the name typed so far.
    pub(crate) naming: Option<(String, String)>,
    /// Find in the conversation, when its bar is open.
    pub(crate) find: Option<crate::extras::Find>,
    /// Per task: a message that waits until Claude is done.
    pub(crate) prompt_save: Option<crate::extras::SavePrompt>,
    /// "Download the speech model?" is being asked.
    pub(crate) listen_ask: bool,
    /// The saved-prompts window, opened from this project.
    pub(crate) prompts_open: Option<String>,
    /// Scroll the conversation to the newest message on the next frame.
    /// Frames left in which to scroll the conversation to the newest
    /// message (a long one needs a frame or two to lay out first).
    pub(crate) scroll_down: u8,
    /// Undo waiting for the owner's OK: task, message, what would change.
    /// Undo (or put back, when the flag is set) waiting for the owner's OK.
    pub(crate) undo_confirm: Option<(String, usize, Vec<mimir_core::checkpoints::Change>, bool)>,
    /// A task whose separate copy waits for "Remove" to be confirmed.
    pub(crate) confirm_remove_copy: Option<String>,
    /// New task: whether the chosen project can have a separate copy.
    pub(crate) copy_ok: Option<(String, bool)>,
    /// New task: work in a separate copy.
    pub(crate) new_separate: bool,
    /// For starting background work that needs to redraw the window.
    pub(crate) ctx: egui::Context,
    /// "No, and say why": the reason being typed in the OK bar.
    pub(crate) deny_note: Option<String>,
    /// Per task: how many entries the owner has seen at the bottom.
    pub(crate) seen: HashMap<String, usize>,
    /// The saved text size has been applied once.
    pub(crate) zoom_applied: bool,
    /// Put the cursor in the message box on the next frame.
    pub(crate) focus_composer: bool,
    /// The general chat on screen (its id); None is the box for a new one.
    pub(crate) chat_open: Option<String>,
    /// Unsent chat messages, per chat ("" is the new chat's).
    pub(crate) chat_drafts: HashMap<String, String>,
    /// Pictures waiting in each chat's box (keyed like `chat_drafts`).
    pub(crate) chat_pics: HashMap<String, Vec<crate::chat_pics::Pending>>,
    /// Pictures shrunk in the background: (box key, picture or None when unreadable).
    pub(crate) pics_chan: (Sender<ShrunkPic>, Receiver<ShrunkPic>),
    pub(crate) pics_inflight: usize,
    /// A picture to put on the clipboard at the next frame.
    pub(crate) pic_copy: Option<Picture>,
    pub(crate) pics_next: u64,
    /// One plain line about the pictures (can't look, too many, unreadable).
    pub(crate) pics_note: Option<String>,
    /// The file picker was opened from a chat.
    pub(crate) attach_to_chat: bool,
    /// Where to save a picture: the dialog, and what is saved.
    pub(crate) saving_pic: Option<(Receiver<Option<PathBuf>>, Picture)>,
    /// Put the cursor in the chat's message box once.
    pub(crate) chat_focus_once: bool,
    /// A chat waiting for "Delete the chat" to be confirmed.
    pub(crate) confirm_delete_chat: Option<String>,
    /// A chat being renamed: its id and the name typed so far.
    pub(crate) rename_chat: Option<(String, String)>,
    /// A message being edited in place: chat, message, the words typed so far.
    pub(crate) edit_turn: Option<(String, String, String)>,
    /// The last edit's "Undo" is on offer in this chat until this moment.
    pub(crate) edit_undo: Option<(String, std::time::Instant)>,
    /// What a search looks through, per chat (lower case): rebuilt only
    /// when the chats' content changed (their `chats_rev`).
    pub(crate) chat_index: (Option<u64>, HashMap<String, String>),
    /// Settings was opened from a chat: closing it goes back there.
    pub(crate) back_to_chat: bool,
    /// Sessions from elsewhere on this PC, found in the background.
    pub(crate) others: Arc<Mutex<Others>>,
}

/// Claude Code sessions started outside this window, for the sidebar.
#[derive(Default)]
pub(crate) struct Others {
    pub list: Vec<mimir_core::sessions::OtherSession>,
    /// Git projects beside the listed ones that aren't listed yet.
    pub repos: Vec<String>,
    pub loading: bool,
    pub checked: Option<Instant>,
}

/// The key for the New task page's own unsent attachments.
pub(crate) const NEW_TASK: &str = "\u{0}new-task";

/// Frames to keep scrolling to the newest message after opening a task.
pub(crate) const SCROLL_FRAMES: u8 = 3;

/// How often the sidebar looks for sessions started elsewhere.
const OTHERS_EVERY: Duration = Duration::from_secs(20);

impl App {
    pub fn new(
        cc: &eframe::CreationContext<'_>,
        version: &'static str,
        public_key: String,
    ) -> Self {
        theme::apply(&cc.egui_ctx);
        crate::chat_pics::clear_opened();
        let ctx = cc.egui_ctx.clone();
        let exe = places::own_program();
        let claude = places::find_claude();
        let wake: mimir_core::runner::Wake = Arc::new(move || ctx.request_repaint());
        // The service runs on its own; the window connects to it (and
        // starts it when systemd hasn't).
        let station = mimir_core::client::connect_or_start(&exe, version, wake);
        let start_retry = StartRetry {
            version,
            next: Instant::now() + RETRY_FIRST,
            tries: 0,
            pending: None,
        };
        let station = station
            .map_err(|_| "Mímir could not start yet. It keeps trying by itself.".to_string());
        // The service's settings, with the window's own preferences.
        let service_part = station
            .as_ref()
            .map(|s| s.settings.clone())
            .unwrap_or_else(|_| {
                mimir_core::settings::service_part(&Settings::read().unwrap_or_default())
            });
        let settings = mimir_core::settings::with_part(
            &mimir_core::settings::with_part(&Settings::default(), &service_part),
            &mimir_core::settings::load_window(),
        );
        let settings_seen = station.as_ref().map_or(0, |s| s.settings_changes);
        let runner = station;
        // The task that was open last time, else the newest one.
        let selected = runner.as_ref().ok().and_then(|r| {
            settings
                .last_task
                .as_ref()
                .filter(|id| {
                    r.tasks
                        .iter()
                        .any(|l| &&l.task.id == id && !l.task.archived)
                })
                .cloned()
                .or_else(|| {
                    r.tasks
                        .iter()
                        .rev()
                        .find(|l| !l.task.archived)
                        .map(|l| l.task.id.clone())
                })
        });
        let view = startup_view(selected.is_some(), claude.exists());
        let mut app = App {
            new_project: settings.last_project.clone(),
            new_project_by_hand: false,
            station: runner,
            start_retry,
            settings,
            settings_dirty: false,
            settings_seen,
            focus_told: None,
            git_watched: None,
            selected: selected.clone(),
            view,
            settings_page: SettingsPage::default(),
            draft: String::new(),
            attachments: vec![],
            drafts: HashMap::new(),
            buffers_of: if view == View::NewTask {
                Some(NEW_TASK.to_string())
            } else {
                selected.clone()
            },
            new_text: String::new(),
            new_mode: Mode::Ask,
            new_model: None,
            new_incognito: false,
            new_effort: None,
            defaults_for: None,
            picking: None,
            attaching: None,
            typed_path: None,
            naming_project: None,
            stats: PcStats::new(),
            setup: SetupPanel::new(&cc.egui_ctx, claude),
            app_panel: AppPanel::new(&cc.egui_ctx, version, public_key),
            md: CommonMarkCache::default(),
            search: String::new(),
            renaming: None,
            open_steps: HashSet::new(),
            diff: None,
            past: None,
            others: Arc::new(Mutex::new(Others::default())),
            // Open at the newest message.
            scroll_down: SCROLL_FRAMES,
            focus_composer: false,
            chat_open: None,
            chat_drafts: HashMap::new(),
            chat_pics: HashMap::new(),
            pics_chan: std::sync::mpsc::channel(),
            pics_inflight: 0,
            pic_copy: None,
            pics_next: 1,
            pics_note: None,
            attach_to_chat: false,
            saving_pic: None,
            chat_focus_once: false,
            confirm_delete_chat: None,
            rename_chat: None,
            edit_turn: None,
            edit_undo: None,
            back_to_chat: false,
            chat_index: (None, HashMap::new()),
            zoom_applied: false,
            seen: HashMap::new(),
            deny_note: None,
            ctx: cc.egui_ctx.clone(),
            confirm_remove_copy: None,
            copy_ok: None,
            new_separate: false,
            undo_confirm: None,
            shortcuts_open: false,
            phone: Default::default(),
            confirm_remove: None,
            confirm_remove_project: None,
            moving: None,
            merging: None,
            adding: None,
            tidying: None,
            tidy_result: None,
            naming: None,
            find: None,
            prompt_save: None,
            listen_ask: false,
            prompts_open: None,
            labs: Default::default(),
        };
        if !app.settings.prompts_seeded {
            app.settings.prompts_seeded = true;
            if app.settings.prompts.is_empty() {
                app.settings.prompts = crate::extras::starter_prompts();
            }
            app.settings_dirty = true;
        }
        app.tidy_settings();
        // Debug pictures can open on a Settings page
        // (MIMIR_SHOT_OPEN=settings/models).
        if cfg!(debug_assertions)
            && let Ok(open) = std::env::var("MIMIR_SHOT_OPEN")
            && let Some(page) = open.strip_prefix("settings")
        {
            app.view = View::Settings;
            app.settings_page =
                SettingsPage::named(page.trim_start_matches('/')).unwrap_or_default();
        }
        app
    }

    /// Tries to reach the service again, in the background: now (`now`,
    /// the Try again button) or when the wait is over.
    pub(crate) fn retry_start(&mut self, ctx: &egui::Context, now: bool) {
        if self.station.is_ok() {
            return;
        }
        let r = &mut self.start_retry;
        if let Some(rx) = &r.pending {
            match rx.try_recv() {
                Ok(Ok(st)) => {
                    r.pending = None;
                    self.adopt_station(st);
                    return;
                }
                Ok(Err(_)) => {
                    r.pending = None;
                    r.tries += 1;
                    let wait = RETRY_FIRST
                        .saturating_mul(1 << r.tries.min(4))
                        .min(RETRY_MAX);
                    r.next = Instant::now() + wait;
                    self.station =
                        Err("Mímir could not start yet. It keeps trying by itself.".to_string());
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => return,
                Err(std::sync::mpsc::TryRecvError::Disconnected) => r.pending = None,
            }
        }
        let r = &mut self.start_retry;
        if !now && Instant::now() < r.next {
            ctx.request_repaint_after(r.next.saturating_duration_since(Instant::now()));
            return;
        }
        let (tx, rx) = std::sync::mpsc::channel();
        r.pending = Some(rx);
        let program = places::own_program();
        let version = r.version;
        let c = ctx.clone();
        let wake: mimir_core::runner::Wake = Arc::new(move || c.request_repaint());
        let c = ctx.clone();
        std::thread::spawn(move || {
            let st = mimir_core::client::connect_or_start(&program, version, wake)
                .map_err(|e| e.to_string());
            let _ = tx.send(st);
            c.request_repaint();
        });
    }

    /// The service was reached after all: the window takes its state as at
    /// a normal start.
    fn adopt_station(&mut self, st: Station) {
        self.settings = mimir_core::settings::with_part(&self.settings, &st.settings);
        self.settings_seen = st.settings_changes;
        if self.selected.is_none() {
            self.selected = st
                .tasks
                .iter()
                .rev()
                .find(|l| !l.task.archived)
                .map(|l| l.task.id.clone());
            if self.selected.is_some() {
                self.view = View::Task;
                self.buffers_of = self.selected.clone();
            }
        }
        self.start_retry.tries = 0;
        self.station = Ok(st);
    }

    /// Every project folder: saved ones in the owner's order, plus any an
    /// open (not archived) task uses.
    pub(crate) fn projects(&self) -> Vec<String> {
        let tasks = self.station.as_ref().ok().map(|r| &r.tasks);
        places::all_projects(&self.settings, tasks.into_iter().flatten().map(|l| &l.task))
    }

    /// Looks again for sessions started elsewhere, in the background, at
    /// most every few seconds.
    pub(crate) fn refresh_others(&self, ctx: &egui::Context) {
        {
            let mut o = self.others.lock().unwrap();
            if o.loading || o.checked.is_some_and(|c| c.elapsed() < OTHERS_EVERY) {
                return;
            }
            o.loading = true;
        }
        let others = self.others.clone();
        ctx.request_repaint_after(OTHERS_EVERY);
        let ctx = ctx.clone();
        let projects = self.projects();
        std::thread::spawn(move || {
            let list = mimir_core::sessions::recent(40);
            let repos = mimir_core::paths::unlisted_repos(&projects);
            let mut o = others.lock().unwrap();
            o.list = list;
            o.repos = repos;
            o.loading = false;
            o.checked = Some(Instant::now());
            ctx.request_repaint();
        });
    }

    /// Whether one of the window's own dialogs is open.
    pub(crate) fn dialog_open(&self) -> bool {
        self.diff.is_some()
            || self.past.is_some()
            || self.typed_path.is_some()
            || self.confirm_remove.is_some()
            || self.confirm_remove_project.is_some()
            || self.moving.is_some()
            || self.merging.is_some()
            || self.adding.is_some()
            || self.tidying.is_some()
            || self.naming.is_some()
            || self.listen_ask
            || self.prompt_save.is_some()
            || self.prompts_open.is_some()
            || self.undo_confirm.is_some()
            || self.confirm_remove_copy.is_some()
            || self.shortcuts_open
            || self.renaming.is_some()
            || self.rename_chat.is_some()
    }

    /// Whether the side panel shows: the owner's choice, else only when
    /// the window is wide enough for it.
    pub(crate) fn details_shown(&self, ctx: &egui::Context) -> bool {
        self.settings
            .details
            .unwrap_or(ctx.content_rect().width() >= 1180.0)
    }

    pub(crate) fn selected_project(&self) -> Option<String> {
        let r = self.station.as_ref().ok()?;
        let id = self.selected.as_ref()?;
        r.tasks
            .iter()
            .find(|l| &l.task.id == id)
            .map(|l| l.task.project.clone())
    }

    /// Saves the settings: the window's own preferences in window.json,
    /// the rest through the service, which keeps settings.json.
    pub(crate) fn save_settings(&mut self) {
        self.settings_dirty = false;
        mimir_core::settings::save_window(&mimir_core::settings::window_part(&self.settings));
        if let Ok(st) = &mut self.station {
            st.set_settings(mimir_core::settings::service_part(&self.settings));
        }
    }

    /// The git facts of a project, as the service last reported them.
    pub(crate) fn git_of(&self, project: &str) -> Option<GitInfo> {
        self.station.as_ref().ok()?.git.get(project).cloned()
    }

    /// Opens a task.
    pub(crate) fn select_task(&mut self, id: &str) {
        swap_buffers(
            &mut self.drafts,
            &mut self.draft,
            &mut self.attachments,
            &mut self.buffers_of,
            id,
        );
        self.settings.last_task = Some(id.to_string());
        self.settings_dirty = true;
        self.selected = Some(id.to_string());
        self.view = View::Task;
        self.scroll_down = SCROLL_FRAMES;
    }

    fn visible_tasks(&self) -> Vec<String> {
        self.sidebar_tasks()
    }

    pub(crate) fn apply(&mut self, actions: Vec<Action>) {
        for a in actions {
            let Ok(r) = &mut self.station else { return };
            match a {
                Action::Select(id) => self.select_task(&id),
                Action::OpenChat(id) => {
                    self.chat_open = Some(id);
                    self.view = View::Chat;
                    self.scroll_down = SCROLL_FRAMES;
                    self.chat_focus_once = true;
                }
                Action::NewChat => {
                    self.chat_open = None;
                    self.view = View::Chat;
                    self.chat_focus_once = true;
                }
                Action::NewWhisper => {
                    self.chat_open = None;
                    self.view = View::Chat;
                    self.chat_focus_once = true;
                    if mimir_core::models::is_free(self.new_model.as_deref())
                        && self.attachments.is_empty()
                    {
                        self.new_incognito = true;
                    }
                }
                Action::OpenPhones => {
                    self.back_to_chat = self.view == View::Chat;
                    self.view = View::Settings;
                    self.settings_page = SettingsPage::Phone;
                }
                Action::OpenUpdates => {
                    self.back_to_chat = self.view == View::Chat;
                    self.view = View::Settings;
                    self.settings_page = SettingsPage::About;
                }
                Action::ToggleSection(chats) => {
                    if chats {
                        self.settings.chats_folded = !self.settings.chats_folded;
                    } else {
                        self.settings.projects_folded = !self.settings.projects_folded;
                    }
                    self.settings_dirty = true;
                }
                Action::SendChat(chat, text) => {
                    let key = chat.clone().unwrap_or_default();
                    let pending = self.chat_pics.remove(&key).unwrap_or_default();
                    let pictures: Vec<Picture> = pending.iter().map(|p| p.pic.clone()).collect();
                    match r.send_chat(chat.as_deref(), &text, pictures, None) {
                        Ok(id) => {
                            self.pics_note = None;
                            self.chat_drafts.remove(&key);
                            self.chat_open = Some(id);
                            self.view = View::Chat;
                            self.scroll_down = SCROLL_FRAMES;
                            self.chat_focus_once = true;
                        }
                        // Not sent: the message stays in the box.
                        Err(why) => {
                            if !pending.is_empty() {
                                self.chat_pics.insert(key.clone(), pending);
                            }
                            self.chat_drafts.insert(key, text);
                            r.problem(&why);
                        }
                    }
                }
                Action::Insert(text) => {
                    match self.view {
                        View::Task => self.draft.push_str(&text),
                        View::NewTask => self.new_text.push_str(&text),
                        View::Chat => self
                            .chat_drafts
                            .entry(self.chat_open.clone().unwrap_or_default())
                            .or_default()
                            .push_str(&text),
                        View::Settings => {}
                    }
                    self.focus_composer = true;
                    self.chat_focus_once = true;
                }
                Action::StopChat(id) => r.stop_chat(&id),
                Action::TryAgain(id) => {
                    self.scroll_down = SCROLL_FRAMES;
                    if let Err(why) = r.try_again(&id) {
                        r.problem(&why);
                    }
                }
                Action::StartEdit(chat, turn, text) => self.edit_turn = Some((chat, turn, text)),
                Action::EditChat(chat, turn, text) => {
                    self.edit_turn = None;
                    self.scroll_down = SCROLL_FRAMES;
                    match r.edit_chat(&chat, &turn, &text) {
                        Ok(()) if r.can_undo_edit(&chat) => {
                            self.edit_undo = Some((
                                chat,
                                std::time::Instant::now() + crate::view_chat::EDIT_UNDO,
                            ));
                        }
                        Ok(()) => {}
                        Err(why) => r.problem(&why),
                    }
                }
                Action::UndoEdit(chat) => {
                    self.edit_undo = None;
                    r.undo_edit(&chat);
                }
                Action::AskRenameChat(id) => {
                    let title = r.chats.get(&id).map(crate::view_chat::chat_title);
                    self.rename_chat = title.map(|t| (id, t));
                }
                Action::RenameChat(id, name) => {
                    r.rename_chat(&id, &name);
                    self.rename_chat = None;
                }
                Action::ConfirmDeleteChat(id) => self.confirm_delete_chat = Some(id),
                Action::DeleteChat(id) => {
                    r.delete_chat(&id);
                    if self.chat_open.as_deref() == Some(id.as_str()) {
                        self.chat_open = None;
                    }
                }
                Action::ConfirmPlacement(id) => r.confirm_placement(&id),
                Action::TidyPlan => match r.call_wait(mimir_core::proto::Call::TidyPlan) {
                    Ok(v) => match serde_json::from_value(v) {
                        Ok(rows) => self.tidying = Some(rows),
                        Err(_) => r.problem("Tidying did not work. Nothing was moved. Try again."),
                    },
                    Err(e) => r.problem(&e),
                },
                Action::Tidy(rows) => {
                    self.tidying = None;
                    match r.call_wait(mimir_core::proto::Call::Tidy { rows }) {
                        Ok(v) => {
                            let n = |k: &str| v[k].as_u64().unwrap_or(0);
                            let mut line = format!("Tidied: {} changes made", n("done"));
                            if n("waiting") > 0 {
                                line.push_str(&format!(
                                    "; {} wait until Mímir stops working on them",
                                    n("waiting")
                                ));
                            }
                            line.push('.');
                            for p in v["problems"].as_array().into_iter().flatten() {
                                if let Some(p) = p.as_str() {
                                    r.problem(p);
                                }
                            }
                            self.tidy_result = Some(line);
                            // Projects added or gone show at once.
                            self.others.lock().unwrap().checked = None;
                        }
                        Err(e) => r.problem(&e),
                    }
                }
                Action::Organize(o) => self.organize(o),
                Action::Mic => {
                    if places::listen_ready() {
                        places::listen_toggle(&self.ctx);
                    } else if places::listen_status() == places::ListenStatus::Idle
                        || matches!(places::listen_status(), places::ListenStatus::Failed(_))
                    {
                        self.listen_ask = true;
                    }
                }
                Action::Find => {
                    if self.view == View::Task {
                        let f = self.find.get_or_insert_with(Default::default);
                        f.focus = true;
                    }
                }
                Action::Queue(id, text, files) => {
                    r.queue(&id, &text, files);
                    self.draft.clear();
                    self.attachments.clear();
                }
                Action::Unqueue(id, edit) => {
                    if let Some(q) = r.unqueue(&id)
                        && edit
                    {
                        if !self.draft.trim().is_empty() {
                            self.draft.push('\n');
                        }
                        self.draft.push_str(&q.text);
                        self.attachments.extend(q.files);
                        self.focus_composer = true;
                    }
                }
                Action::SendQueued(id) => {
                    r.send_queued(&id);
                    self.scroll_down = SCROLL_FRAMES;
                }
                Action::SaveConversation(id) => self.save_conversation(&id),
                Action::AskSavePrompt(project, text) => {
                    self.prompt_save = Some(crate::extras::SavePrompt {
                        project,
                        text,
                        name: String::new(),
                        for_project: false,
                    });
                }
                Action::ManagePrompts(project) => self.prompts_open = Some(project),
                Action::NewTask => {
                    swap_buffers(
                        &mut self.drafts,
                        &mut self.draft,
                        &mut self.attachments,
                        &mut self.buffers_of,
                        NEW_TASK,
                    );
                    self.view = View::NewTask;
                    self.new_project_by_hand = false;
                }
                Action::OpenSettings => {
                    self.back_to_chat = self.view == View::Chat;
                    self.view = View::Settings;
                }
                Action::OpenModels => {
                    self.back_to_chat = self.view == View::Chat;
                    self.view = View::Settings;
                    self.settings_page = SettingsPage::Models;
                }
                Action::CloseSettings => {
                    crate::view_models::forget_job_note();
                    if std::mem::take(&mut self.back_to_chat) {
                        self.view = View::Chat;
                        continue;
                    }
                    self.view = if self.selected.is_some() {
                        View::Task
                    } else {
                        View::NewTask
                    };
                    let to = if self.view == View::Task {
                        self.selected.clone().unwrap_or_default()
                    } else {
                        NEW_TASK.to_string()
                    };
                    swap_buffers(
                        &mut self.drafts,
                        &mut self.draft,
                        &mut self.attachments,
                        &mut self.buffers_of,
                        &to,
                    );
                }
                Action::PickFolder => self.picking = Some(places::pick_folder()),
                Action::DropChatPicture(id) => {
                    for v in self.chat_pics.values_mut() {
                        v.retain(|p| p.id != id);
                    }
                    self.chat_pics.retain(|_, v| !v.is_empty());
                    self.pics_note = None;
                }
                Action::PictureCopy(p) => self.pic_copy = Some(p),
                Action::PictureSave(p, name) => {
                    self.saving_pic = Some((crate::chat_pics::pick_save(&name), p));
                }
                Action::PictureOpen(p, name) => match crate::chat_pics::write_for_open(&p, &name) {
                    Some(path) => places::open_path(&path),
                    None => r.problem("That picture couldn't be opened. Try Save as… instead."),
                },
                Action::PickAttachments => {
                    self.attach_to_chat = self.view == View::Chat;
                    if self.attach_to_chat && !sees_pictures(r) {
                        // Explains instead of opening a picker that leads nowhere.
                        self.pics_note = Some(crate::chat_pics::CANT_SEE.into());
                    } else if places::have("kdialog") || places::have("zenity") {
                        self.attaching = Some(places::pick_files("Attach files or pictures"));
                    } else {
                        r.problem(
                            "No file picker is installed (kdialog or zenity), so Attach cannot \
                             open one. Drop files on the window, or paste a picture with Ctrl+V.",
                        );
                    }
                }
                Action::NewProject(name) => {
                    match r.call_wait(mimir_core::proto::Call::NewProject { name }) {
                        Ok(v) => {
                            if let Some(path) = v["path"].as_str() {
                                self.settings.add_project(Path::new(path));
                                self.new_project = Some(path.to_string());
                                self.naming_project = None;
                                self.view = View::NewTask;
                            }
                        }
                        Err(e) => r.problem(&e),
                    }
                }
                Action::AddTypedFolder(p) => {
                    let p = places::expand_home(p.trim());
                    let p = std::fs::canonicalize(&p).unwrap_or(p);
                    if p.is_dir() {
                        self.settings.add_project(&p);
                        self.new_project = Some(p.display().to_string());
                        self.typed_path = None;
                        self.view = View::NewTask;
                    }
                }
                Action::Start {
                    project,
                    text,
                    files,
                    mode,
                    model,
                    effort,
                    separate,
                } => {
                    let (text, pictures) = with_files(&text, &files);
                    let id = match r
                        .new_task(&project, &text, &pictures, mode, model, effort, separate)
                    {
                        Ok(id) => id,
                        Err(e) => {
                            r.problem(&e);
                            continue;
                        }
                    };
                    self.scroll_down = SCROLL_FRAMES;
                    self.settings.last_task = Some(id.clone());
                    // A chat with no project was filed: its project is the
                    // one used last.
                    let project = r
                        .tasks
                        .iter()
                        .find(|l| l.task.id == id)
                        .map(|l| l.task.project.clone())
                        .unwrap_or(project);
                    // The New task page's attachments went with the task.
                    self.attachments.clear();
                    swap_buffers(
                        &mut self.drafts,
                        &mut self.draft,
                        &mut self.attachments,
                        &mut self.buffers_of,
                        &id,
                    );
                    self.selected = Some(id);
                    self.view = View::Task;
                    self.new_text.clear();
                    self.new_project_by_hand = false;
                    self.settings.add_project(Path::new(&project));
                }
                Action::Send(id, text, files) => {
                    self.scroll_down = SCROLL_FRAMES;
                    let (text, pictures) = with_files(&text, &files);
                    r.send_with(&id, &text, &pictures);
                    self.draft.clear();
                    self.attachments.clear();
                }
                Action::Pause(id) => r.pause(&id),
                Action::Stop(id) => r.stop(&id),
                Action::Continue(id) => {
                    self.scroll_down = SCROLL_FRAMES;
                    r.resume(&id)
                }
                Action::Answer(id, a) => r.answer(&id, a),
                Action::SetMode(id, m) => r.set_mode(&id, m),
                Action::SetModel(id, m) => r.set_model(&id, m),
                Action::StartIncognito {
                    project,
                    text,
                    model,
                    mode,
                } => match r.call_wait(mimir_core::proto::Call::NewIncognito {
                    ground_rules: None,
                    project,
                    text,
                    model,
                    mode,
                }) {
                    Ok(v) => {
                        self.new_text.clear();
                        self.new_incognito = false;
                        if let Some(id) = v.as_str() {
                            let id = id.to_string();
                            self.select_task(&id);
                        }
                    }
                    Err(e) => r.problem(&e),
                },
                Action::EndIncognito(id) => {
                    if let Err(e) = r.call_wait(mimir_core::proto::Call::EndIncognito { task: id })
                    {
                        r.problem(&e);
                    }
                }
                Action::ContinueOnFree(id) => {
                    if let Err(e) =
                        r.call_wait(mimir_core::proto::Call::ContinueOnFree { task: id })
                    {
                        r.problem(&e);
                    }
                }
                Action::BackToClaude(id) => {
                    if let Err(e) = r.call_wait(mimir_core::proto::Call::BackToClaude { task: id })
                    {
                        r.problem(&e);
                    }
                }
                Action::SetEffort(id, e) => r.set_effort(&id, e),
                Action::ApprovePlan(id, m) => {
                    self.scroll_down = SCROLL_FRAMES;
                    r.approve_plan(&id, m)
                }
                Action::Rename(id, t) => {
                    r.rename(&id, &t);
                    self.renaming = None;
                }
                Action::Archive(id, yes) => {
                    r.set_archived(&id, yes);
                    if yes && self.selected.as_deref() == Some(&id) {
                        self.selected = None;
                        self.view = View::NewTask;
                    }
                }
                Action::Remove(id) => {
                    self.confirm_remove = None;
                    self.delete_task(&id);
                    self.settings_dirty = true;
                }
                Action::OpenPast(project) => {
                    // Sessions that are already tasks here are left out.
                    let sessions = r.past_sessions(&project);
                    self.past = Some((project, sessions));
                }
                Action::ImportPast(project, s) => {
                    match r.open_past(&project, &s) {
                        Ok(id) => {
                            swap_buffers(
                                &mut self.drafts,
                                &mut self.draft,
                                &mut self.attachments,
                                &mut self.buffers_of,
                                &id,
                            );
                            self.selected = Some(id);
                            self.view = View::Task;
                            self.scroll_down = SCROLL_FRAMES;
                        }
                        Err(e) => r.problem(&e),
                    }
                    self.past = None;
                }
                Action::ShowDiff(project, path) => {
                    let lines = mimir_core::git::diff(Path::new(&project), &path);
                    self.diff = Some((path, lines));
                }
                Action::ShowLines(title, lines) => self.diff = Some((title, lines)),
                Action::OpenPath(p) => places::open_path(&p),
                Action::OpenTerminal(p) => {
                    if !places::open_terminal(&p) {
                        r.problem("No terminal program (Konsole or xterm) was found.");
                    }
                }
                Action::ToggleDetails(shown) => {
                    self.settings.details = Some(!shown);
                    self.settings_dirty = true;
                }
                Action::NewTaskIn(project) => {
                    swap_buffers(
                        &mut self.drafts,
                        &mut self.draft,
                        &mut self.attachments,
                        &mut self.buffers_of,
                        NEW_TASK,
                    );
                    self.new_project = Some(project);
                    self.new_project_by_hand = true;
                    self.view = View::NewTask;
                }
                Action::ShowShortcuts => self.shortcuts_open = true,
                Action::DismissQuestion(id) => r.dismiss_question(&id),
                Action::AnswerItem(ask_id, answer) => {
                    r.call(mimir_core::proto::Call::AnswerItem { ask_id, answer })
                }
                Action::BringIn(id) => {
                    if let Err(e) = r.bring_in(&id) {
                        r.problem(&e);
                    }
                    self.scroll_down = SCROLL_FRAMES;
                }
                Action::ConfirmRemoveCopy(id) => self.confirm_remove_copy = Some(id),
                Action::RemoveCopy(id) => {
                    self.confirm_remove_copy = None;
                    if let Err(e) = r.remove_copy(&id) {
                        r.problem(&e);
                    }
                    self.scroll_down = SCROLL_FRAMES;
                }
                Action::SaveDefaults(project) => {
                    self.settings.defaults.insert(
                        project,
                        places::Defaults {
                            mode: self.new_mode,
                            model: self.new_model.clone(),
                            effort: self.new_effort.clone(),
                        },
                    );
                    self.settings_dirty = true;
                }
                Action::UndoPreview(id, n) => match r.undo_preview(&id, n) {
                    Ok(changes) => self.undo_confirm = Some((id, n, changes, false)),
                    Err(e) => r.problem(&e),
                },
                Action::Undo(id, n) => {
                    self.undo_confirm = None;
                    if let Err(e) = r.undo(&id, n) {
                        r.problem(&e);
                    }
                    self.scroll_down = SCROLL_FRAMES;
                }
                Action::RedoPreview(id) => match r.redo_preview(&id) {
                    Ok(changes) => self.undo_confirm = Some((id, 0, changes, true)),
                    Err(e) => r.problem(&e),
                },
                Action::FocusComposer => {
                    self.focus_composer = true;
                }
                Action::ToggleRemote(project) => {
                    if self.settings.remote_projects.contains(&project) {
                        self.settings.remote_projects.retain(|p| p != &project);
                    } else {
                        self.settings.remote_projects.push(project);
                    }
                    self.settings_dirty = true;
                }
                Action::Redo(id) => {
                    self.undo_confirm = None;
                    if let Err(e) = r.redo(&id) {
                        r.problem(&e);
                    }
                    self.scroll_down = SCROLL_FRAMES;
                }
                Action::DismissProblem(i) => r.dismiss_problem(i),
                Action::GoToWaiting => {
                    if let Some(l) = r.tasks.iter().find(|l| l.task.needs_owner()) {
                        let to = l.task.id.clone();
                        swap_buffers(
                            &mut self.drafts,
                            &mut self.draft,
                            &mut self.attachments,
                            &mut self.buffers_of,
                            &to,
                        );
                        self.selected = Some(to);
                        self.view = View::Task;
                        self.scroll_down = SCROLL_FRAMES;
                    }
                }
                Action::RemoteControl(p) => {
                    let name = places::display_name(&self.settings, &p.display().to_string());
                    let cmd = mimir_core::setup::remote_control(&places::find_claude(), &name);
                    if !mimir_core::setup::in_terminal(&cmd, Some(&p)) {
                        r.problem("Remote control needs a terminal window, and none was found.");
                    }
                }
                Action::NextTask(back) => {
                    let ids = self.visible_tasks();
                    if ids.is_empty() {
                        continue;
                    }
                    let pos = self
                        .selected
                        .as_ref()
                        .and_then(|s| ids.iter().position(|i| i == s))
                        .unwrap_or(0);
                    let next = if back {
                        (pos + ids.len() - 1) % ids.len()
                    } else {
                        (pos + 1) % ids.len()
                    };
                    swap_buffers(
                        &mut self.drafts,
                        &mut self.draft,
                        &mut self.attachments,
                        &mut self.buffers_of,
                        &ids[next],
                    );
                    self.selected = Some(ids[next].clone());
                    self.scroll_down = SCROLL_FRAMES;
                    self.view = View::Task;
                }
            }
        }
    }

    fn shortcuts(&self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let (ctrl, shift) = ctx.input(|i| (i.modifiers.ctrl, i.modifiers.shift));
        let pressed = |k| ctx.input(|i| i.key_pressed(k));
        crate::menus::keys(ctx);
        // Delete asks to delete the chat or task that is open (never while
        // a box has the cursor, or a menu or window is open).
        if pressed(Key::Delete)
            && !crate::menus::box_wants_keys(ctx)
            && !egui::Popup::is_any_open(ctx)
            && !self.dialog_open()
            && self.confirm_delete_chat.is_none()
        {
            match (self.view, &self.chat_open, &self.selected) {
                (View::Chat, Some(id), _) => actions.push(Action::ConfirmDeleteChat(id.clone())),
                (View::Task, _, Some(id)) => actions.push(Action::Organize(
                    crate::organize::Organize::AskRemoveTask(id.clone()),
                )),
                _ => {}
            }
        }
        if ctrl && pressed(Key::N) {
            actions.push(Action::NewChat);
        }
        if ctrl && pressed(Key::K) {
            ctx.memory_mut(|m| m.request_focus(crate::view_left::search_id()));
        }
        if ctrl && pressed(Key::Tab) {
            actions.push(Action::NextTask(shift));
        }
        // Talk is Ctrl+M (Ctrl+D answers "Don't allow" on a card).
        if ctrl
            && !shift
            && pressed(Key::M)
            && matches!(self.view, View::Task | View::NewTask)
            && !self.dialog_open()
        {
            actions.push(Action::Mic);
        }
        if pressed(Key::Escape) && places::listen_cancel() {
            // Esc while listening only throws away what was said.
            return;
        }
        if ctrl && pressed(Key::F) {
            actions.push(Action::Find);
        }
        if ctrl && pressed(Key::L) {
            actions.push(Action::FocusComposer);
        }
        if ctrl && pressed(Key::J) {
            actions.push(Action::GoToWaiting);
        }
        if ctrl && pressed(Key::B) {
            actions.push(Action::ToggleDetails(self.details_shown(ctx)));
        }
        let running = self
            .station
            .as_ref()
            .ok()
            .zip(self.selected.as_ref())
            .and_then(|(r, id)| r.tasks.iter().find(|l| &l.task.id == id))
            .map(|l| {
                (
                    l.task.id.clone(),
                    l.busy() && l.asks.is_empty(),
                    l.task.mode,
                )
            });
        // Esc only closes things. Pause is Ctrl+. (never while a menu or
        // window is open).
        let something_open = egui::Popup::is_any_open(ctx)
            || self.diff.is_some()
            || self.past.is_some()
            || self.typed_path.is_some()
            || self.confirm_remove.is_some()
            || self.confirm_remove_project.is_some()
            || self.moving.is_some()
            || self.merging.is_some()
            || self.adding.is_some()
            || self.tidying.is_some()
            || self.naming.is_some()
            || self.undo_confirm.is_some()
            || self.confirm_remove_copy.is_some()
            || self.shortcuts_open
            || self.find.is_some()
            || self.listen_ask
            || self.prompt_save.is_some()
            || self.prompts_open.is_some()
            || self.renaming.is_some();
        if let Some((id, true, _)) = &running
            && ctrl
            && pressed(Key::Period)
            && self.view == View::Task
            && !something_open
        {
            actions.push(Action::Pause(id.clone()));
        }
        if let Some((id, _, mode)) = &running
            && self.view == View::Task
            && ctrl
            && shift
            && pressed(Key::M)
        {
            let i = Mode::CYCLE.iter().position(|m| m == mode).unwrap_or(0);
            actions.push(Action::SetMode(
                id.clone(),
                Mode::CYCLE[(i + 1) % Mode::CYCLE.len()],
            ));
        }
    }
}

/// Whether the PC's answerer can look at pictures (true when it can't be asked).
fn sees_pictures(r: &mut Station) -> bool {
    r.call_wait(mimir_core::proto::Call::Models)
        .ok()
        .and_then(|v| v["sees_pictures"].as_bool())
        .unwrap_or(true)
}

/// Where a picture for the chat box came from.
pub(crate) enum PicSource {
    File(PathBuf),
    Pixels(Arc<egui::ColorImage>),
}

impl App {
    /// The key of the chat box that is showing (like `chat_drafts`).
    pub(crate) fn chat_key(&self) -> String {
        self.chat_open.clone().unwrap_or_default()
    }

    /// Puts pictures in the chat box: shrunk in the background, at most four.
    pub(crate) fn add_chat_pictures(&mut self, ctx: &egui::Context, mut items: Vec<PicSource>) {
        use crate::chat_pics::{CANT_SEE, MAX_PICTURES, TOO_MANY};
        if items.is_empty() {
            return;
        }
        if let Ok(r) = &mut self.station
            && !sees_pictures(r)
        {
            self.pics_note = Some(CANT_SEE.into());
            return;
        }
        let key = self.chat_key();
        let have = self.chat_pics.get(&key).map_or(0, |v| v.len()) + self.pics_inflight;
        let room = MAX_PICTURES.saturating_sub(have);
        if items.len() > room {
            self.pics_note = Some(TOO_MANY.into());
            items.truncate(room);
        } else {
            self.pics_note = None;
        }
        if items.is_empty() {
            return;
        }
        self.pics_inflight += items.len();
        let tx = self.pics_chan.0.clone();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            for item in items {
                let pic = match item {
                    PicSource::File(p) => crate::chat_pics::shrink_file(&p),
                    PicSource::Pixels(img) => crate::chat_pics::shrink_pixels(&img),
                };
                let _ = tx.send((key.clone(), pic));
                ctx.request_repaint();
            }
        });
    }

    /// Takes in pictures the background has finished, and does the copy and
    /// save a picture's menu asked for.
    pub(crate) fn pictures_tick(&mut self, ctx: &egui::Context) {
        while let Ok((key, pic)) = self.pics_chan.1.try_recv() {
            self.pics_inflight = self.pics_inflight.saturating_sub(1);
            match pic {
                Some(pic) => {
                    let id = self.pics_next;
                    self.pics_next += 1;
                    let v = self.chat_pics.entry(key).or_default();
                    if v.len() < crate::chat_pics::MAX_PICTURES {
                        v.push(crate::chat_pics::Pending { id, pic });
                    }
                }
                None => self.pics_note = Some(crate::chat_pics::NOT_A_PICTURE.into()),
            }
        }
        if let Some(p) = self.pic_copy.take()
            && let Some(img) = crate::chat_pics::pixels_of(&p, 8192)
        {
            ctx.copy_image(img);
        }
        if let Some((rx, pic)) = &self.saving_pic
            && let Ok(path) = rx.try_recv()
        {
            if let Some(path) = path
                && let Some(bytes) = crate::chat_pics::bytes_of(pic)
                && std::fs::write(&path, bytes).is_err()
                && let Ok(r) = &mut self.station
            {
                r.problem("The picture couldn't be saved there. Choose another folder.");
            }
            self.saving_pic = None;
        }
    }
}

/// Splits attachments into pictures (sent to Claude) and other files
/// (named in the message, so Claude can read them).
pub(crate) fn with_files(text: &str, files: &[PathBuf]) -> (String, Vec<Picture>) {
    let mut pictures = vec![];
    let mut text = text.to_string();
    for f in files {
        match mimir_core::events::picture_from_file(f) {
            Some(p) => pictures.push(p),
            None => text.push_str(&format!("\n\nAttached file: {}", f.display())),
        }
    }
    (text, pictures)
}

impl eframe::App for App {
    fn raw_input_hook(&mut self, ctx: &egui::Context, raw: &mut egui::RawInput) {
        crate::shot::inject(ctx, raw);
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        crate::shot::log_frame(ui.ctx());
        // A frame over 50 ms goes in the developer log, with the page on show.
        let _frame_time = mimir_core::devlog::FrameTimer::new(match self.view {
            View::Task => "task",
            View::Chat => "chat",
            View::NewTask => "new task",
            View::Settings => "settings",
        });
        self.setup.tick();
        // Debug builds: a picture and frame times for the looks checks.
        if crate::shot::tick(ui.ctx())
            && let Some(first) = self.settings.projects.first().cloned()
        {
            let c = &mut self.settings.collapsed_projects;
            match c.iter().position(|p| p == &first) {
                Some(i) => {
                    c.remove(i);
                }
                None => c.push(first),
            }
        }
        // Debug pictures can open a task that appears after the start
        // (MIMIR_SHOT_OPEN=from-phone: the first one from the phone).
        if crate::shot::once(ui.ctx(), "from-phone")
            && let Some(id) = self.station.as_ref().ok().and_then(|r| {
                r.tasks
                    .iter()
                    .find(|l| l.task.from_phone.is_some())
                    .map(|l| l.task.id.clone())
            })
        {
            self.select_task(&id);
        }
        // MIMIR_SHOT_OPEN=task: the newest task, once listed.
        if crate::shot::once(ui.ctx(), "task")
            && let Some(id) = self.station.as_ref().ok().and_then(|r| {
                r.tasks
                    .iter()
                    .max_by_key(|l| l.task.created_unix)
                    .map(|l| l.task.id.clone())
            })
        {
            self.select_task(&id);
        }
        // MIMIR_SHOT_OPEN=chat: the newest general chat, once listed.
        if crate::shot::once(ui.ctx(), "chat")
            && let Some(id) = self
                .station
                .as_ref()
                .ok()
                .and_then(|r| r.chats.values().max_by_key(|c| c.at).map(|c| c.id.clone()))
        {
            self.chat_open = Some(id);
            self.view = View::Chat;
            self.scroll_down = SCROLL_FRAMES;
        }
        // MIMIR_SHOT_OPEN=message-menu and left-menu: a made-up chat, open.
        // MIMIR_SHOT_OPEN=long-chat: the same, with long answers.
        let long = crate::shot::once(ui.ctx(), "long-chat");
        if (crate::shot::once(ui.ctx(), "message-menu")
            || crate::shot::once(ui.ctx(), "left-menu")
            || long)
            && let Ok(r) = &mut self.station
        {
            let long_answer = (0..9)
                .map(|i| {
                    format!(
                        "Part {}. Lettuce, spinach and kale do well with only a few hours of \
                         sun. Water them in the morning so the leaves dry before night.",
                        i + 1
                    )
                })
                .collect::<Vec<_>>()
                .join("\n\n");
            let turn = |id: &str, user: bool, text: &str| mimir_core::proto::ChatTurn {
                id: id.into(),
                user,
                text: text.into(),
                sources: vec![],
                ran: (!user).then_some(mimir_core::proto::Ran::Pc),
                model_words: None,
                at: mimir_core::needs::now() * 1000 + if user { 0 } else { 1000 },
                version: 1,
                seq: 0,
                gone: false,
                switch_note: None,
                pictures: vec![],
                files: vec![],
            };
            let _ = r.call_wait(mimir_core::proto::Call::ChatPut {
                chat_id: "shot".into(),
                name: None,
                turns: if long {
                    let mut v = vec![];
                    for i in 0..4 {
                        v.push(turn(
                            &format!("shot.q{i}"),
                            true,
                            "Which vegetables can I plant in the shade of the fence?",
                        ));
                        v.push(turn(&format!("shot.a{i}"), false, &long_answer));
                    }
                    v
                } else {
                    vec![
                        turn(
                            "shot.q",
                            true,
                            "Which vegetables can I plant in the shade of the fence?",
                        ),
                        turn(
                            "shot.a",
                            false,
                            "Lettuce, spinach and kale do well with only a few hours of sun. \
                             Water them in the morning so the leaves dry before night.",
                        ),
                    ]
                },
                deleted: false,
            });
            self.chat_open = Some("shot".into());
            self.view = View::Chat;
            self.scroll_down = SCROLL_FRAMES;
        }
        // MIMIR_SHOT_OPEN=new-chat: the box for a new chat.
        if crate::shot::once(ui.ctx(), "new-chat") {
            self.chat_open = None;
            self.view = View::Chat;
        }
        places::set_voice_prefs(places::VoicePrefs {
            natural: !self.settings.read_desktop_voice,
            speed: self.settings.read_speed.unwrap_or(1.1), // PCE-12: default 110%
            cadence: self.settings.read_cadence.unwrap_or(1.0),
        });
        // What the service says, and its settings when they changed.
        if let Ok(st) = &mut self.station {
            st.poll();
            // A message the PC never got goes back in its box.
            for (chat, text) in st.take_unsent() {
                self.chat_drafts.insert(chat, text);
            }
            // A checked new version opens in the software centre, which
            // installs it. Mímir never replaces itself.
            if let crate::app_view::UpdateState::Downloaded(v, file) = self.app_panel.update_state()
            {
                places::open_path(&file);
                self.app_panel
                    .set_update_state(crate::app_view::UpdateState::Opened(v));
            }
            if st.settings_changes != self.settings_seen {
                self.settings_seen = st.settings_changes;
                self.settings = mimir_core::settings::with_part(&self.settings, &st.settings);
            }
        }
        // Text size: the saved one, and keep Ctrl+plus/minus changes.
        let zoom = ui.ctx().zoom_factor();
        match self.settings.zoom {
            Some(z) if (z - zoom).abs() > 0.001 && !self.zoom_applied => {
                // A damaged value must not make the window unusable.
                let z = if z.is_finite() {
                    z.clamp(0.5, 3.0)
                } else {
                    1.0
                };
                ui.ctx().set_zoom_factor(z);
            }
            _ if self.zoom_applied
                && self.settings.zoom.is_none_or(|z| (z - zoom).abs() > 0.001) =>
            {
                self.settings.zoom = Some(zoom);
                self.settings_dirty = true;
            }
            _ => {}
        }
        self.zoom_applied = true;
        if self.station.is_err() {
            let ctx = ui.ctx().clone();
            self.retry_start(&ctx, false);
        }
        if let Err(msg) = &self.station {
            let msg = msg.clone();
            let trying = self.start_retry.pending.is_some();
            let left = self
                .start_retry
                .next
                .saturating_duration_since(Instant::now())
                .as_secs()
                + 1;
            let mut again = false;
            egui::CentralPanel::default().show(ui, |ui| {
                ui.vertical_centered(|ui| {
                    ui.add_space(120.0);
                    ui.heading(msg.as_str());
                    ui.add_space(8.0);
                    if trying {
                        ui.horizontal(|ui| {
                            ui.add(egui::Spinner::new());
                            ui.label(crate::labels::TRYING_AGAIN);
                        });
                    } else {
                        ui.label(crate::labels::trying_again_in(left));
                        ui.add_space(8.0);
                        again = ui.add(theme::primary(crate::labels::TRY_AGAIN)).clicked();
                    }
                });
            });
            if again {
                let ctx = ui.ctx().clone();
                self.retry_start(&ctx, true);
            }
            if self.station.is_err() {
                return;
            }
        }
        let ctx = ui.ctx().clone();
        // Desktop notifications come from the service when no window has
        // the focus.
        let focused = ctx.input(|i| i.viewport().focused.unwrap_or(true));
        let project = self.selected_project();
        if let Ok(st) = &mut self.station {
            if self.focus_told != Some(focused) {
                self.focus_told = Some(focused);
                st.focus(focused);
            }
            // The service keeps the shown project's git facts fresh.
            if self.git_watched != project {
                self.git_watched = project.clone();
                st.watch_git(project);
            }
        }
        if let Some(rx) = &self.picking
            && let Ok(choice) = rx.try_recv()
        {
            self.picking = None;
            match choice {
                Some(p) if p.as_os_str().is_empty() => self.typed_path = Some(String::new()),
                Some(p) => {
                    self.settings.add_project(&p);
                    self.new_project = Some(p.display().to_string());
                    self.view = View::NewTask;
                }
                None => {}
            }
        }
        if let Some(rx) = &self.attaching
            && let Ok(files) = rx.try_recv()
        {
            self.attaching = None;
            if self.attach_to_chat {
                self.add_chat_pictures(&ctx, files.into_iter().map(PicSource::File).collect());
            } else {
                self.attachments.extend(files);
            }
        }
        // Files dropped on the window become attachments.
        let dropped: Vec<PathBuf> = ctx.input(|i| {
            i.raw
                .dropped_files
                .iter()
                .map(|f| f.path().to_path_buf())
                .filter(|p| !p.as_os_str().is_empty())
                .collect()
        });
        // Only where a message is written; elsewhere (Settings) they are ignored.
        if self.view == View::Chat {
            self.add_chat_pictures(&ctx, dropped.into_iter().map(PicSource::File).collect());
        } else if matches!(self.view, View::Task | View::NewTask) {
            self.attachments.extend(dropped);
        }
        // Ctrl+V with a picture on the clipboard attaches it.
        let mut pasted: Vec<std::sync::Arc<egui::ColorImage>> = ctx.input(|i| {
            i.events
                .iter()
                .filter_map(|e| match e {
                    egui::Event::PasteImage(img) => Some(img.clone()),
                    _ => None,
                })
                .collect()
        });
        // A picture pasted from the right-click menu.
        pasted.extend(crate::menus::take_pasted_image(&ctx));
        if matches!(self.view, View::Task | View::NewTask) {
            for img in pasted {
                if let Some(p) = places::save_pasted(&img) {
                    self.attachments.push(p);
                }
            }
        } else if self.view == View::Chat {
            self.add_chat_pictures(&ctx, pasted.into_iter().map(PicSource::Pixels).collect());
        }
        self.pictures_tick(&ctx);
        self.stats.refresh();
        let busy = self
            .station
            .as_ref()
            .is_ok_and(|r| r.tasks.iter().any(|l| l.busy()));
        let visible = ctx.input(|i| i.viewport().focused.unwrap_or(true));
        // While hidden and idle, skip background repaints (0 wakes while hidden).
        if should_wake(visible, busy) {
            ctx.request_repaint_after(Duration::from_secs(1));
            if busy {
                // Smooth spinner and live text.
                ctx.request_repaint_after(Duration::from_millis(100));
            }
        }

        let mut actions = vec![];
        self.shortcuts(&ctx, &mut actions);
        self.problems_bar(ui, &mut actions);
        // Settings is a page of its own: no task list beside it.
        if self.view != View::Settings {
            self.left(ui, &mut actions);
        }
        // The side panel needs room: on a small window it is off unless
        // the owner turned it on (Details, Ctrl+B). It shows on a task, a
        // chat and New task: how this PC is doing and what is running.
        if self.view != View::Settings && self.details_shown(ui.ctx()) {
            self.right(ui, &mut actions);
        }
        egui::CentralPanel::default()
            .frame(
                egui::Frame::new()
                    .fill(theme::GROUND)
                    .inner_margin(egui::Margin::same(16)),
            )
            .show(ui, |ui| {
                match self.view {
                    View::Settings => self.settings_view(ui, &mut actions),
                    View::NewTask => self.new_task_view(ui, &mut actions),
                    View::Task => self.task_view(ui, &mut actions),
                    View::Chat => self.chat_view(ui, &mut actions),
                }
                // Right-click anywhere else in the middle.
                if self.view != View::Settings {
                    let area = ui.interact(
                        ui.max_rect(),
                        egui::Id::unique("middle-area"),
                        egui::Sense::hover(),
                    );
                    crate::menus::empty_space(ui, &area, &mut actions);
                }
            });
        self.chat_windows(&ctx, &mut actions);
        self.windows(&ctx, &mut actions);
        self.pair_window(&ctx);
        self.apply(actions);
        if self.settings_dirty {
            self.save_settings();
        }
    }

    fn on_exit(&mut self) {
        crate::places::listen_cleanup();
        if self.settings_dirty {
            self.save_settings();
        }
        if let Ok(r) = &mut self.station {
            r.shutdown();
        }
    }
}

/// Moves the message box's contents to their owner and loads those of `to`.
fn swap_buffers(
    drafts: &mut HashMap<String, (String, Vec<PathBuf>)>,
    draft: &mut String,
    attachments: &mut Vec<PathBuf>,
    buffers_of: &mut Option<String>,
    to: &str,
) {
    switch_draft(drafts, draft, attachments, buffers_of.as_deref(), to);
    *buffers_of = Some(to.to_string());
}

/// Keeps the unsent message of the task being left, and brings back the
/// one of the task being opened.
fn switch_draft(
    drafts: &mut HashMap<String, (String, Vec<PathBuf>)>,
    draft: &mut String,
    attachments: &mut Vec<PathBuf>,
    from: Option<&str>,
    to: &str,
) {
    if from == Some(to) {
        return;
    }
    if let Some(from) = from {
        let kept = (std::mem::take(draft), std::mem::take(attachments));
        if kept.0.is_empty() && kept.1.is_empty() {
            drafts.remove(from);
        } else {
            drafts.insert(from.to_string(), kept);
        }
    }
    let (d, a) = drafts.remove(to).unwrap_or_default();
    *draft = d;
    *attachments = a;
}

impl App {
    /// Problems Mímir ran into, until the owner dismisses them.
    fn problems_bar(&self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let Ok(r) = &self.station else { return };
        // The service restarted or stopped: the last state stays on screen.
        if r.reconnecting() {
            egui::Panel::top("reconnecting")
                .frame(
                    egui::Frame::new()
                        .fill(theme::ORANGE_DIM)
                        .inner_margin(egui::Margin::symmetric(16, 6)),
                )
                .show(ui, |ui| {
                    ui.label(crate::labels::RECONNECTING);
                });
        }
        if r.problems.is_empty() {
            return;
        }
        egui::Panel::top("problems")
            .frame(
                egui::Frame::new()
                    .fill(theme::PANEL)
                    .inner_margin(egui::Margin::symmetric(16, 6)),
            )
            .show(ui, |ui| {
                for (i, p) in r.problems.iter().enumerate() {
                    egui::Frame::new()
                        .fill(theme::ORANGE_DIM)
                        .stroke(egui::Stroke::new(1.0, theme::ORANGE))
                        .corner_radius(egui::CornerRadius::same(theme::RADIUS))
                        .inner_margin(egui::Margin::symmetric(10, 6))
                        .show(ui, |ui| {
                            ui.set_width(ui.available_width());
                            ui.horizontal(|ui| {
                                ui.with_layout(
                                    egui::Layout::right_to_left(egui::Align::Center),
                                    |ui| {
                                        if ui.small_button("✕").on_hover_text("Dismiss").clicked()
                                        {
                                            actions.push(Action::DismissProblem(i));
                                        }
                                        ui.with_layout(
                                            egui::Layout::left_to_right(egui::Align::Center),
                                            |ui| {
                                                ui.add(egui::Label::new(p.as_str()).wrap());
                                            },
                                        );
                                    },
                                );
                            });
                        });
                    ui.add_space(4.0);
                }
            });
    }
}

/// A task's status colour; a plan waiting for approval needs the owner.
/// A task's state in words and colour, from the one state every surface
/// shows (the PC's truth). A waiting plan or question keeps its own words.
pub fn state_look(l: &mimir_core::client::TaskView) -> (&'static str, egui::Color32) {
    use mimir_core::proto::TaskState;
    let t = &l.task;
    match l.shown_state() {
        TaskState::NeedsYou => (t.status_words(), theme::ORANGE),
        TaskState::Working => ("Working", theme::JADE),
        TaskState::LiveElsewhere => ("Live elsewhere", theme::JADE),
        TaskState::Stopped => (
            if t.status == Status::Paused {
                "Paused"
            } else {
                "Stopped"
            },
            theme::MUTED,
        ),
        TaskState::Done => ("Done", theme::JADE),
    }
}

/// The initial view when the window opens:
/// - Has active tasks → show the most recent task.
/// - No tasks, Claude available → new task form (the user can start work).
/// - No tasks, no Claude → chat view (free-first: chat box shown first).
pub fn startup_view(has_active_tasks: bool, claude_available: bool) -> View {
    if has_active_tasks {
        View::Task
    } else if claude_available {
        View::NewTask
    } else {
        View::Chat
    }
}

/// Returns true if the window should wake (request a background repaint).
/// A hidden, idle window should never wake — 0 repaints per second.
pub fn should_wake(window_visible: bool, has_busy_tasks: bool) -> bool {
    window_visible || has_busy_tasks
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn free_first_screen() {
        // No tasks and no Claude login → chat view (free-first).
        assert_eq!(startup_view(false, false), View::Chat);
        // No tasks but Claude available → new task form.
        assert_eq!(startup_view(false, true), View::NewTask);
        // Has tasks → task view regardless.
        assert_eq!(startup_view(true, false), View::Task);
        assert_eq!(startup_view(true, true), View::Task);
    }

    #[test]
    fn frame_wake_hidden() {
        // A hidden window with no busy tasks must never request a repaint
        // (0 wakes per second while hidden for 60 s).
        for _ in 0..60 {
            assert!(
                !should_wake(false, false),
                "hidden idle window should not wake"
            );
        }
        // A visible window with busy tasks should wake.
        assert!(should_wake(true, true));
        // A visible idle window should still wake (spinner, clock updates).
        assert!(should_wake(true, false));
        // A hidden window that has busy tasks must still wake (task may finish).
        assert!(should_wake(false, true));
    }
}
