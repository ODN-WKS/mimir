//! Mímir's settings file (`settings.json`).

use crate::paths::{clean_path, settings_path};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Default, Serialize, Deserialize)]
pub struct Settings {
    #[serde(default)]
    pub projects: Vec<String>,
    #[serde(default)]
    pub last_project: Option<String>,
    /// Slash commands and skills last reported by Claude Code.
    #[serde(default)]
    pub slash_commands: Vec<String>,
    /// Side panel shown (None: only when the window is wide).
    #[serde(default)]
    pub details: Option<bool>,
    /// The task that was open, to open it again next time.
    #[serde(default)]
    pub last_task: Option<String>,
    /// Mode, model and effort new tasks start with, per project folder.
    #[serde(default)]
    pub defaults: std::collections::HashMap<String, Defaults>,
    /// Per project folder: the hand-off file a fresh start reads first
    /// (none: the project's own hand-off document, wherever it keeps it).
    #[serde(default, skip_serializing_if = "std::collections::HashMap::is_empty")]
    pub handoff_files: std::collections::HashMap<String, String>,
    /// Connectors (MCP servers) Claude Code last reported, with status.
    #[serde(default)]
    pub connectors: Vec<(String, String)>,
    /// Project folders with Remote Control switched on (started again at
    /// the next start).
    #[serde(default)]
    pub remote_projects: Vec<String>,
    /// Read aloud: the desktop's own voice instead of the natural one.
    #[serde(default)]
    pub read_desktop_voice: bool,
    /// Read aloud speed, 0.7 to 1.3 (None: 1.0).
    #[serde(default)]
    pub read_speed: Option<f32>,
    /// Read aloud pauses, 0.5 to 2.0 (None: 1.0).
    #[serde(default)]
    pub read_cadence: Option<f32>,
    /// Text size: 1.0 is normal.
    #[serde(default)]
    pub zoom: Option<f32>,
    /// Groups of projects in the sidebar, in their order.
    #[serde(default)]
    pub groups: Vec<Group>,
    /// Names the owner gave projects (Mímir only; folders keep
    /// their own names).
    #[serde(default)]
    pub project_names: std::collections::HashMap<String, String>,
    /// Projects whose task list is folded away in the sidebar.
    #[serde(default)]
    pub collapsed_projects: Vec<String>,
    /// The sidebar's "Chats" section is folded away.
    #[serde(default)]
    pub chats_folded: bool,
    /// The sidebar's "Projects" section is folded away.
    #[serde(default)]
    pub projects_folded: bool,
    /// Per project: the owner's order of its tasks (ids).
    #[serde(default)]
    pub task_order: std::collections::HashMap<String, Vec<String>>,
    /// Tasks pinned to the top of their project.
    #[serde(default)]
    pub pinned_tasks: Vec<String>,
    /// Sessions of deleted tasks: not offered again under "On this PC".
    #[serde(default)]
    pub hidden_sessions: Vec<String>,
    /// The sidebar shows the claude.ai side (chats from the export), not
    /// the work on this PC.
    #[serde(default)]
    pub side_online: bool,
    /// Saved prompts for every project.
    #[serde(default)]
    pub prompts: Vec<Prompt>,
    /// Saved prompts of one project.
    #[serde(default)]
    pub project_prompts: std::collections::HashMap<String, Vec<Prompt>>,
    /// The starter prompts were added once (deleting them is final).
    #[serde(default)]
    pub prompts_seeded: bool,
    /// When a task finishes after doing work, ask Claude for a short summary.
    #[serde(default)]
    pub finish_summary: bool,
    /// Phones reach the PC through n0's relays when needed (None: on).
    /// Off: only direct connections.
    #[serde(default)]
    pub phone_relays: Option<bool>,
    /// A paired phone may switch a task to YOLO ("Don't ask"). Off by
    /// default; only the PC's Settings switch it.
    #[serde(default)]
    pub yolo_from_phone: bool,
    /// The name phones show for this PC (None or empty: the PC's own name).
    #[serde(default)]
    pub pc_name: Option<String>,
    /// Other names the phone's general chat knows a project by, per folder
    /// ("Recipes" for Recipe-Box).
    #[serde(default)]
    pub aliases: std::collections::HashMap<String, Vec<String>>,
    /// Before Stage 1: an older switch of the same name. Read once to
    /// migrate, never written again; `free_when_out` is the switch now.
    #[serde(default, skip_serializing)]
    pub switch_to_free: bool,
    /// When online use runs out, or passes 99% this week
    /// (`usage::FREE_AT_PERCENT`), new project work and online chats go to
    /// the PC's own model, and a task that hits the limit carries on there
    /// (None: on). Off: a task asks first, and a chat says online is at its
    /// limit.
    #[serde(default)]
    pub free_when_out: Option<bool>,
    /// "Use my API credits when my plan runs out" (None: off). It counts
    /// only while a key is saved (`api_key_hint`): see `api_credits_on`.
    #[serde(default)]
    pub api_credits: Option<bool>,
    /// The last 4 characters of the saved Anthropic key, written down when
    /// it was pasted, so Settings can show which key is saved. The key
    /// itself is only in the PC's password store (`api_key`), never here.
    #[serde(default)]
    pub api_key_hint: Option<String>,
    /// Before Stage 1: the free model tasks fell back to (read once).
    #[serde(default, skip_serializing)]
    pub free_model: Option<String>,
    /// Before Stage 1: "backup", "claude" or "free" (read once to migrate).
    #[serde(default, skip_serializing)]
    pub power: Option<String>,
    /// Before Stage 1: ask before moving to the free model (always off now).
    #[serde(default, skip_serializing)]
    pub ask_before_free: bool,
    /// Chats and lookups: "free" (the PC's own model, the default) or
    /// "paid" (Claude, through Claude Code, when it's signed in).
    #[serde(default)]
    pub chats_engine: Option<String>,
    /// Which Claude model answers chats: None or "auto" (Claude Code's own
    /// choice), else one of `talk::CLAUDE_CHOICES`.
    #[serde(default)]
    pub chats_model: Option<String>,
    /// Project work: "free" (a free model on this PC; a new install's
    /// default) or "claude" (the owner's Claude plan). A free model is always
    /// the backup when Claude can't answer.
    #[serde(default)]
    pub project_engine: Option<String>,
    /// The weekly look for a better phone model ("Worth switching?"); None: on.
    #[serde(default)]
    pub weekly_model_check: Option<bool>,
    /// Ground rules sent with every task (Settings › Models); None: the
    /// default text.
    #[serde(default)]
    pub ground_rules: Option<String>,
    /// Whether the ground rules are sent; None: on.
    #[serde(default)]
    pub ground_rules_on: Option<bool>,
    /// When the ground rules were last changed (milliseconds since 1970):
    /// a phone's edit counts when it is newer.
    #[serde(default)]
    pub ground_rules_at: Option<i64>,
    /// Labs: "Show what Claude is doing on this PC" (the Behind the scenes
    /// card, the don't-touch banner, and the line on a phone's Status).
    #[serde(default)]
    pub labs_pc_activity: bool,
    /// Labs: "Developer log" (the PC's technical log for fixing problems);
    /// None: off (private by default).
    #[serde(default)]
    pub labs_devlog: Option<bool>,
    /// Labs: "Include my normal chats" in the developer log; None: off.
    #[serde(default)]
    pub labs_devlog_chats: Option<bool>,
    /// Settings › Notifications: a desktop pop-up when a task is done.
    /// None: off (owner, 2026-11-23). A task that needs you always pops up.
    #[serde(default)]
    pub popup_done: Option<bool>,
    /// Keys this version doesn't know (from a newer one): kept as they are.
    #[serde(flatten)]
    pub other: serde_json::Map<String, serde_json::Value>,
}

/// What powers tasks (Settings › Models).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Power {
    /// Claude, and a free model on this PC when Claude's limit runs out.
    Backup,
    ClaudeOnly,
    FreeOnly,
}

/// Who does online work right now (`Settings::lane`), in the order they
/// are tried when the plan runs out.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Lane {
    /// The owner's Anthropic plan, as usual.
    Plan,
    /// The owner's Anthropic API credits: the same Claude, billed per answer.
    ApiCredits,
    /// A model on this PC.
    PcModel,
    /// Nothing takes over: the work stops and says online is at its limit.
    Stop,
}

/// The ground rules every task gets unless the owner changed them.
pub const DEFAULT_GROUND_RULES: &str = "Answer exactly what I ask, directly and fully, in the subject's own terms. \
Do not add other views, criticism, warnings or advice unless I ask for them. Tell the truth \
as best you know it; if you don't know, say \"I don't know\". Never invent names, numbers, \
quotes or dates. If facts were looked up, name the sources. If I push back, check again: \
keep the answer unless I give a real reason, and if you were wrong, say so. No flattery, no \
filler, no lecturing, no moralizing, no refusing because a topic is sensitive. \
Mímir looks things up for you, on the web and in the encyclopedias, when a question needs it, \
and puts the results in front of you; you do not search yourself, Mímir does, whether you run \
on this phone or on the PC. Never say you cannot look things up; if no results came, say that \
none were found.";

/// The default before 0.09 item 25 (the look-up sentence was added after it).
const DEFAULT_0_08: &str = "Answer exactly what I ask, directly and fully, in the subject's own terms. \
Do not add other views, criticism, warnings or advice unless I ask for them. Tell the truth \
as best you know it; if you don't know, say \"I don't know\". Never invent names, numbers, \
quotes or dates. If facts were looked up, name the sources. If I push back, check again: \
keep the answer unless I give a real reason, and if you were wrong, say so. No flattery, no \
filler, no lecturing, no moralizing, no refusing because a topic is sensitive.";

/// Earlier default texts of the ground rules. A phone that has not updated yet
/// still sends (and stores) the old default as if the owner had written it, which
/// would keep the PC answering by the old rules. Text equal to one of these counts
/// as "never edited": the current default applies. Add the replaced default here
/// whenever `DEFAULT_GROUND_RULES` changes.
pub const OLD_DEFAULTS: &[&str] = &[
    DEFAULT_0_08,
    "Tell the truth as best you know it. If you don't know, say \"I don't know\"; if you're \
unsure, say so with a short phrase like \"I'm fairly sure\" or \"I'm not sure\". Never \
invent names, numbers, quotes or dates. On disputed questions, give the strongest evidence \
and the main views fairly, and don't take a side on politics or values unless asked for \
your view, and then label it as yours. If I push back on an answer, check it again: keep \
it unless I give a real reason, and if you were wrong, say so and why. Don't change an \
answer just to please me. If facts were looked up, name the sources you used, and say \"I \
couldn't confirm this\" when they are thin or disagree. No flattery, no praise for the \
question, no filler. Answer what I ask directly and fully, without lecturing, moralizing, \
or refusing because a topic is sensitive.",
];

/// Whether this text is a previous default (and so not an edit by the owner).
pub fn is_old_default(text: &str) -> bool {
    OLD_DEFAULTS.contains(&text.trim())
}

/// Rules text from a phone or a saved file: None when it is empty or an old default.
pub fn without_old_default(text: Option<String>) -> Option<String> {
    text.filter(|t| !t.trim().is_empty() && !is_old_default(t))
}

/// Said under the ground rules wherever they are shown.
pub const GROUND_RULES_NOTE: &str = "Online answers follow their own safety rules on top of these; free \
and on-phone models follow these as written.";

impl Settings {
    /// The ground rules to send with a task, if they're on and not empty.
    pub fn ground_rules(&self) -> Option<String> {
        if self.ground_rules_on == Some(false) {
            return None;
        }
        let text = self
            .ground_rules
            .as_deref()
            .filter(|t| !is_old_default(t))
            .unwrap_or(DEFAULT_GROUND_RULES)
            .trim();
        (!text.is_empty()).then(|| text.to_string())
    }

    /// What powers project work: Claude with a free model as its backup,
    /// or free models only.
    pub fn power(&self) -> Power {
        if self.project_free() {
            Power::FreeOnly
        } else {
            Power::Backup
        }
    }

    pub fn set_power(&mut self, p: Power) {
        self.project_engine = Some(
            if p == Power::FreeOnly {
                "free"
            } else {
                "claude"
            }
            .into(),
        );
    }

    /// "When online use runs out, use your PC's model" (on unless turned off).
    pub fn free_when_out_on(&self) -> bool {
        self.free_when_out != Some(false) && !self.ask_before_free
    }

    /// "Use my API credits when my plan runs out": on, with a key saved.
    pub fn api_credits_on(&self) -> bool {
        self.api_credits == Some(true) && self.api_key_hint.is_some()
    }

    /// Who does work that isn't set to a model on the PC: the one place
    /// that decides the order when the plan runs out. `plan_out`: online
    /// use is out, or past 99% this week. `api_failed`: Anthropic refused
    /// the key or its credits ran out, so the next one in line takes over.
    /// The order: the plan; then the API credits (the switch on and a key
    /// saved); then the PC's own model ("When online use runs out, use your
    /// PC's model"); then stop with the plain message.
    pub fn lane(&self, plan_out: bool, api_failed: bool) -> Lane {
        if self.power() == Power::FreeOnly {
            Lane::PcModel
        } else if !plan_out {
            Lane::Plan
        } else if self.api_credits_on() && !api_failed {
            Lane::ApiCredits
        } else if self.free_when_out_on() {
            Lane::PcModel
        } else {
            Lane::Stop
        }
    }

    /// A new project task on `model` is moved to the PC's own model: the
    /// project work setting is "your PC's model", or online use is out (or
    /// past 99% this week: `claude_out` is either), the API credits don't
    /// take it, and the switch "When online use runs out, use your PC's
    /// model" is on. `lane` with the credits taken as working.
    pub fn moves_to_free(&self, model: Option<&str>, claude_out: bool) -> bool {
        !crate::models::is_free(model) && self.lane(claude_out, false) == Lane::PcModel
    }

    /// Where the words of a new project task go: online (Anthropic) or not.
    /// The one answer for the pill before sending, the service when it starts
    /// the task, and the line the task says afterwards. `free_model_here`:
    /// the PC has a model to move it to.
    pub fn task_goes_online(
        &self,
        model: Option<&str>,
        claude_out: bool,
        free_model_here: bool,
    ) -> bool {
        !crate::models::is_free(model)
            && !(self.moves_to_free(model, claude_out) && free_model_here)
    }

    /// What the pill, the panel and the task all say: do this task's words
    /// go online (Anthropic)? A session Mímir only follows (`outside`) is not
    /// answered by Mímir, so never. A chat follows the chats setting; a task
    /// with its own model follows that model; a task without one follows the
    /// project work setting (the rule the PC uses when it starts the task).
    pub fn task_shows_online(
        &self,
        model: Option<&str>,
        is_chat: bool,
        outside: bool,
        free_model_here: bool,
    ) -> bool {
        if outside {
            return false;
        }
        if is_chat {
            return self.chats_paid();
        }
        match model {
            Some(_) => !crate::models::is_free(model),
            None => self.task_goes_online(None, false, free_model_here),
        }
    }

    /// Online (Anthropic) is set up: chats or project work use it.
    pub fn online_set_up(&self) -> bool {
        self.chats_paid() || !self.project_free()
    }

    /// Project work runs on a free model on this PC.
    pub fn project_free(&self) -> bool {
        self.project_engine.as_deref() != Some("claude")
    }

    /// Whether a finished task gets a desktop pop-up (off until chosen).
    pub fn popup_done_on(&self) -> bool {
        self.popup_done.unwrap_or(false)
    }

    /// Whether the developer log is on.
    pub fn devlog_on(&self) -> bool {
        self.labs_devlog.unwrap_or(false)
    }

    /// Whether ordinary chats' text is in the developer log (only while it
    /// is on).
    pub fn devlog_chats(&self) -> bool {
        self.devlog_on() && self.labs_devlog_chats.unwrap_or(false)
    }

    /// Chats run on a paid model (Claude) instead of the PC's own.
    /// The `--model` alias chats use with Claude, or None for Claude Code's own choice.
    pub fn chats_model_alias(&self) -> Option<&'static str> {
        crate::talk::CLAUDE_CHOICES
            .iter()
            .find(|c| c.0 != "auto" && Some(c.0) == self.chats_model.as_deref())
            .map(|c| c.0)
    }

    pub fn chats_paid(&self) -> bool {
        self.chats_engine.as_deref() == Some("paid")
    }

    /// The Stage 1 settings from the keys before them (read once): an
    /// install that had them keeps Claude for project work (unless it ran on
    /// free models only); a new one starts free for both. Chats are free.
    pub fn migrate_engines(&mut self, raw: &serde_json::Value) {
        if self.project_engine.is_none() {
            let old = ["power", "switch_to_free", "ask_before_free", "free_model"]
                .iter()
                .any(|k| raw.get(k).is_some());
            self.project_engine = Some(
                if old && self.power.as_deref() != Some("free") {
                    "claude"
                } else {
                    "free"
                }
                .into(),
            );
        }
        if self.chats_engine.is_none() {
            self.chats_engine = Some("free".into());
        }
        self.power = None;
        self.switch_to_free = false;
        self.ask_before_free = false;
        self.free_model = None;
    }
}

/// A message the owner uses often.
#[derive(Default, Clone, PartialEq, Debug, Serialize, Deserialize)]
pub struct Prompt {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub text: String,
}

/// A named group of projects in the sidebar.
#[derive(Default, Clone, PartialEq, Debug, Serialize, Deserialize)]
pub struct Group {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub projects: Vec<String>,
    #[serde(default)]
    pub collapsed: bool,
}

#[derive(Default, Clone, PartialEq, Serialize, Deserialize)]
pub struct Defaults {
    #[serde(default)]
    pub mode: crate::task::Mode,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub effort: Option<String>,
}

/// Said when the settings file is damaged: `load` sets it aside and starts fresh.
const SETTINGS_UNREADABLE: &str = "Your settings couldn't be read. Restart Mímir: it sets the damaged file aside and starts fresh (your tasks are safe).";

impl Settings {
    fn path() -> PathBuf {
        settings_path()
    }

    /// The saved settings, without touching the file: a missing file gives
    /// the defaults, a damaged one an error (`load` sets it aside instead).
    pub fn read() -> Result<Self, String> {
        let path = Self::path();
        match std::fs::read(&path) {
            Ok(bytes) => serde_json::from_slice::<Self>(&bytes)
                .map(|mut s| {
                    s.migrate_engines(&serde_json::from_slice(&bytes).unwrap_or_default());
                    // An old default saved as if edited counts as not edited.
                    if s.ground_rules.as_deref().is_some_and(is_old_default) {
                        s.ground_rules = None;
                    }
                    s
                })
                .map_err(|e| {
                    eprintln!("mimir: {} can't be read: {e}", path.display());
                    SETTINGS_UNREADABLE.to_string()
                }),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Self::default()),
            Err(e) => {
                eprintln!("mimir: {} can't be read: {e}", path.display());
                Err(SETTINGS_UNREADABLE.to_string())
            }
        }
    }

    /// The saved settings, and a problem to show if the file was damaged.
    /// A damaged file is set aside (never silently overwritten).
    pub fn load() -> (Self, Option<String>) {
        let path = Self::path();
        let Ok(bytes) = std::fs::read(&path) else {
            return (Self::default(), None);
        };
        match serde_json::from_slice::<Self>(&bytes) {
            Ok(mut s) => {
                s.migrate_engines(&serde_json::from_slice(&bytes).unwrap_or_default());
                (s, None)
            }
            Err(_) => {
                let stamp = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                let aside = path.with_extension(format!("json.damaged-{stamp}"));
                let _ = std::fs::rename(&path, &aside);
                (
                    Self::default(),
                    Some(format!(
                        "Your settings couldn't be read, so they were reset (your tasks are \
                         safe). The old file is kept as {}.",
                        aside
                            .file_name()
                            .map(|n| n.to_string_lossy().to_string())
                            .unwrap_or_default()
                    )),
                )
            }
        }
    }

    pub fn save(&self) {
        let p = Self::path();
        if let Some(d) = p.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        if let Ok(data) = serde_json::to_vec_pretty(self) {
            let tmp = p.with_extension("json.tmp");
            if std::fs::write(&tmp, data).is_ok() {
                let _ = std::fs::rename(tmp, p);
            }
        }
    }

    pub fn add_project(&mut self, p: &Path) {
        // One spelling per folder: no "x" and "x/" side by side.
        let s = clean_path(&p.display().to_string());
        if !self.projects.contains(&s) {
            self.projects.push(s.clone());
        }
        self.last_project = Some(s);
    }

    /// Forgets what belongs to tasks and projects that are gone: pins and
    /// places of deleted tasks; names, orders and folding of projects no
    /// longer listed. `tasks` are the task ids there are; `projects` the
    /// project folders still in use (listed or with tasks, archived too).
    pub fn tidy(&mut self, tasks: &[String], projects: &[String]) -> bool {
        let before = (
            self.pinned_tasks.len(),
            self.task_order.values().map(Vec::len).sum::<usize>(),
            self.task_order.len(),
            self.project_names.len(),
            self.collapsed_projects.len(),
        );
        self.pinned_tasks.retain(|id| tasks.contains(id));
        self.task_order.retain(|p, ids| {
            ids.retain(|id| tasks.contains(id));
            projects.contains(p) && !ids.is_empty()
        });
        self.project_names.retain(|p, _| projects.contains(p));
        self.collapsed_projects.retain(|p| projects.contains(p));
        let after = (
            self.pinned_tasks.len(),
            self.task_order.values().map(Vec::len).sum::<usize>(),
            self.task_order.len(),
            self.project_names.len(),
            self.collapsed_projects.len(),
        );
        before != after
    }
}

/// Settings only the window uses and writes, kept in `window.json` beside
/// `settings.json`. The service writes every other key.
pub const WINDOW_KEYS: &[&str] = &[
    "zoom",
    "details",
    "collapsed_projects",
    "side_online",
    "last_task",
    "task_order",
    "pinned_tasks",
    "hidden_sessions",
    "read_desktop_voice",
    "read_speed",
    "read_cadence",
    "last_project",
];

/// Where the window's own preferences are kept.
pub fn window_path() -> PathBuf {
    crate::paths::config_dir().join("window.json")
}

fn object(s: &Settings) -> serde_json::Map<String, serde_json::Value> {
    match serde_json::to_value(s) {
        Ok(serde_json::Value::Object(m)) => m,
        _ => serde_json::Map::new(),
    }
}

/// The service's keys of these settings.
pub fn service_part(s: &Settings) -> serde_json::Value {
    let mut m = object(s);
    m.retain(|k, _| !WINDOW_KEYS.contains(&k.as_str()));
    serde_json::Value::Object(m)
}

/// The window's keys of these settings.
pub fn window_part(s: &Settings) -> serde_json::Value {
    let mut m = object(s);
    m.retain(|k, _| WINDOW_KEYS.contains(&k.as_str()));
    serde_json::Value::Object(m)
}

/// `base` with the keys of `part` put in.
pub fn with_part(base: &Settings, part: &serde_json::Value) -> Settings {
    let mut m = object(base);
    if let serde_json::Value::Object(p) = part {
        for (k, v) in p {
            m.insert(k.clone(), v.clone());
        }
    }
    serde_json::from_value(serde_json::Value::Object(m)).unwrap_or_default()
}

/// The window's preferences. The first time, they are copied out of
/// `settings.json` (where older versions kept them) into `window.json`.
pub fn load_window() -> serde_json::Value {
    let path = window_path();
    if let Ok(bytes) = std::fs::read(&path)
        && let Ok(v @ serde_json::Value::Object(_)) = serde_json::from_slice(&bytes)
    {
        return v;
    }
    let v = window_part(&Settings::read().unwrap_or_default());
    save_window(&v);
    v
}

/// Saves the window's preferences.
pub fn save_window(v: &serde_json::Value) {
    let p = window_path();
    if let Some(d) = p.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    if let Ok(data) = serde_json::to_vec_pretty(v) {
        let tmp = p.with_extension("json.tmp");
        if std::fs::write(&tmp, data).is_ok() {
            let _ = std::fs::rename(tmp, p);
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn p09_item25_the_built_in_rules_say_mimir_looks_things_up() {
        assert!(DEFAULT_GROUND_RULES.contains("Mímir looks things up for you"));
        assert!(DEFAULT_GROUND_RULES.contains("Never say you cannot look things up"));
        // The approved text is kept whole, in front of the added sentence.
        assert!(DEFAULT_GROUND_RULES.starts_with(DEFAULT_0_08));
        assert!(is_old_default(DEFAULT_0_08));
    }

    #[test]
    fn online_is_set_up_only_when_chats_or_project_work_use_it() {
        let mut s = Settings::default();
        assert!(!s.online_set_up());
        s.chats_engine = Some("paid".into());
        assert!(s.online_set_up());
        s.chats_engine = None;
        s.project_engine = Some("claude".into());
        assert!(s.online_set_up());
    }

    #[test]
    fn an_untouched_install_gets_the_new_rules_and_a_written_one_is_kept() {
        let s: Settings = serde_json::from_str("{}").unwrap();
        let r = s.ground_rules().unwrap();
        assert_eq!(r, DEFAULT_GROUND_RULES);
        assert!(r.starts_with("Answer exactly what I ask"));
        let s: Settings = serde_json::from_str(r#"{"ground_rules":"Be brief."}"#).unwrap();
        assert_eq!(s.ground_rules().as_deref(), Some("Be brief."));
    }

    #[test]
    fn the_done_pop_up_is_off_until_chosen() {
        assert!(!Settings::default().popup_done_on());
        let s: Settings = serde_json::from_str("{}").unwrap();
        assert!(!s.popup_done_on());
        let s: Settings = serde_json::from_str(r#"{"popup_done":true}"#).unwrap();
        assert!(s.popup_done_on());
        // It travels in a part like any setting.
        let s = with_part(
            &Settings::default(),
            &serde_json::json!({"popup_done": true}),
        );
        assert!(s.popup_done_on());
    }

    #[test]
    fn notes_for_fixing_problems_and_chats_in_them_are_off_until_chosen() {
        let s = Settings::default();
        assert!(!s.devlog_on() && !s.devlog_chats());
        // An old file with no choice reads as off.
        let s: Settings = serde_json::from_str("{}").unwrap();
        assert!(!s.devlog_on() && !s.devlog_chats());
        // An explicit earlier choice is kept.
        let s: Settings =
            serde_json::from_str(r#"{"labs_devlog":true,"labs_devlog_chats":true}"#).unwrap();
        assert!(s.devlog_on() && s.devlog_chats());
        let s: Settings = serde_json::from_str(r#"{"labs_devlog":true}"#).unwrap();
        assert!(s.devlog_on() && !s.devlog_chats());
    }

    /// The truth-seeking rules (R29): each rule is there, they stay short, and the phone's copy is
    /// the same words (one text on both sides).
    #[test]
    fn the_default_ground_rules_ask_for_honest_answers_and_match_the_phone() {
        let r = DEFAULT_GROUND_RULES;
        for must in [
            "I don't know",
            "Answer exactly what I ask",
            "in the subject's own terms",
            "unless I ask for them",
            "I push back",
            "name the sources",
            "No flattery",
            "no refusing because a topic is sensitive",
        ] {
            assert!(r.contains(must), "missing: {must}");
        }
        // The old hedges and "other views" lines are gone.
        for gone in [
            "main views",
            "fairly sure",
            "I'm not sure",
            "disputed",
            "I couldn't confirm",
        ] {
            assert!(!r.contains(gone), "still there: {gone}");
        }
        assert!(
            r.split_whitespace().count() < 190,
            "keep it short for small models"
        );
        let kt = std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../android/app/src/main/java/io/github/odnwks/mimir/data/GroundRules.kt"
        ))
        .unwrap();
        let start = kt.find("const val DEFAULT = ").unwrap();
        let mut lit = kt[start + "const val DEFAULT = \"".len()..].to_string();
        lit.truncate(lit.find("\"\n\n").unwrap());
        // Kotlin joins its pieces with `" +` and a new line; undo that and the escapes.
        let joined: Vec<&str> = lit.split("\" +").collect();
        let phone: String = joined
            .iter()
            .map(|p| p.trim_start().trim_start_matches('"'))
            .collect();
        let phone = phone.replace("\\\"", "\"");
        let strip = |s: &str| s.replace('\\', "");
        assert_eq!(strip(&phone), strip(r));
    }

    /// A phone that has not updated sends the old default: it counts as not edited.
    #[test]
    fn an_old_default_counts_as_not_edited() {
        for old in OLD_DEFAULTS {
            assert_ne!(*old, DEFAULT_GROUND_RULES);
            assert!(is_old_default(old));
            assert_eq!(without_old_default(Some(old.to_string())), None);
            assert_eq!(without_old_default(Some(format!(" {old}\n"))), None);
            let s = Settings {
                ground_rules: Some(old.to_string()),
                ..Default::default()
            };
            assert_eq!(s.ground_rules().as_deref(), Some(DEFAULT_GROUND_RULES));
        }
        assert_eq!(
            without_old_default(Some("Be brief.".into())).as_deref(),
            Some("Be brief.")
        );
        assert_eq!(without_old_default(Some(" ".into())), None);
        assert!(!is_old_default(DEFAULT_GROUND_RULES));
    }

    /// Ground rules: on with the default text until changed; off or empty
    /// sends none; with Plan mode both go in one appended prompt.
    #[test]
    fn ground_rules_default_changed_and_off() {
        let mut s = Settings::default();
        assert_eq!(s.ground_rules().as_deref(), Some(DEFAULT_GROUND_RULES));
        s.ground_rules = Some(" Be brief. ".into());
        assert_eq!(s.ground_rules().as_deref(), Some("Be brief."));
        s.ground_rules = Some("  ".into());
        assert_eq!(s.ground_rules(), None);
        s.ground_rules = None;
        s.ground_rules_on = Some(false);
        assert_eq!(s.ground_rules(), None);
        let launch = crate::process::Launch {
            ground_rules: Some("Be brief.".into()),
            permission_mode: Some("plan".into()),
            ..Default::default()
        };
        let a = launch.args();
        assert_eq!(
            a.iter().filter(|x| *x == "--append-system-prompt").count(),
            1
        );
        let i = a
            .iter()
            .position(|x| x == "--append-system-prompt")
            .unwrap();
        assert!(a[i + 1].starts_with("Be brief.\n\nWhen your plan is ready"));
    }

    use super::*;

    #[test]
    fn parts_split_and_join() {
        let s = Settings {
            projects: vec!["/p".into()],
            zoom: Some(1.5),
            last_task: Some("t".into()),
            finish_summary: true,
            ..Default::default()
        };
        let service = service_part(&s);
        let window = window_part(&s);
        assert!(service.get("zoom").is_none() && service.get("projects").is_some());
        assert!(window.get("zoom").is_some() && window.get("projects").is_none());
        let back = with_part(&with_part(&Settings::default(), &service), &window);
        assert_eq!(back.projects, s.projects);
        assert_eq!(back.zoom, Some(1.5));
        assert_eq!(back.last_task.as_deref(), Some("t"));
        assert!(back.finish_summary);
    }

    #[test]
    fn unknown_keys_are_kept() {
        let s: Settings =
            serde_json::from_str(r#"{"projects":["/a"],"from_newer":{"x":1}}"#).unwrap();
        let out = serde_json::to_value(&s).unwrap();
        assert_eq!(out["from_newer"]["x"], 1);
    }

    #[test]
    fn the_old_power_keys_become_the_two_stage_1_lines() {
        let v = |j: serde_json::Value| {
            let mut s: Settings = serde_json::from_value(j.clone()).unwrap();
            s.migrate_engines(&j);
            s
        };
        // This PC: Claude with free backup, "switch near my limit" on.
        let s =
            v(serde_json::json!({"power": null, "switch_to_free": true, "ask_before_free": false}));
        assert_eq!(s.project_engine.as_deref(), Some("claude"));
        assert_eq!(s.chats_engine.as_deref(), Some("free"));
        assert!(!s.switch_to_free && !s.chats_paid() && !s.project_free());
        assert_eq!(s.power(), Power::Backup);
        // Free only stays free; Claude only becomes Claude with free backup.
        assert!(v(serde_json::json!({"power": "free"})).project_free());
        assert!(!v(serde_json::json!({"power": "claude"})).project_free());
        // A new install: free for both.
        let s = v(serde_json::json!({"projects": []}));
        assert!(s.project_free() && !s.chats_paid());
        // Chosen once, kept; the old keys are never written again.
        let s = v(
            serde_json::json!({"project_engine": "claude", "chats_engine": "paid", "power": "free"}),
        );
        assert!(!s.project_free() && s.chats_paid());
        let out = serde_json::to_value(&s).unwrap();
        for k in ["power", "switch_to_free", "ask_before_free", "free_model"] {
            assert!(out.get(k).is_none(), "{k} is still written");
        }
    }

    /// P23: "When online use runs out, use your PC's model" is on until
    /// turned off, is kept in the service's part, and decides whether a new
    /// task moves to the PC's model while online is out.
    #[test]
    fn p23_free_when_out_is_on_by_default_kept_and_obeyed() {
        let s: Settings = serde_json::from_str("{}").unwrap();
        assert!(s.free_when_out_on());
        // An older file with the old key on (or off) reads as on: the old
        // switch never did this, so nobody turned this one off.
        for old in [true, false] {
            let j = serde_json::json!({"switch_to_free": old, "project_engine": "claude"});
            let mut s: Settings = serde_json::from_value(j.clone()).unwrap();
            s.migrate_engines(&j);
            assert!(s.free_when_out_on(), "old key {old}");
        }
        // Off is saved, travels in the service's part and comes back.
        let off = Settings {
            free_when_out: Some(false),
            ..Default::default()
        };
        let part = service_part(&off);
        assert_eq!(part["free_when_out"], false);
        assert!(!with_part(&Settings::default(), &part).free_when_out_on());
        let back: Settings = serde_json::from_str(&serde_json::to_string(&off).unwrap()).unwrap();
        assert!(!back.free_when_out_on());
        // Project work online: a new task moves to the PC's model only while
        // online is out and the switch is on.
        for on in [None, Some(true), Some(false)] {
            let s = Settings {
                project_engine: Some("claude".into()),
                free_when_out: on,
                ..Default::default()
            };
            let moves = on != Some(false);
            assert_eq!(s.moves_to_free(None, true), moves, "{on:?}");
            assert_eq!(s.moves_to_free(Some("auto"), true), moves, "{on:?}");
            assert!(!s.moves_to_free(None, false), "{on:?}");
            assert_eq!(s.task_goes_online(None, true, true), !moves, "{on:?}");
        }
    }

    /// ask the same rule, for every combination of the settings.
    #[test]
    fn where_the_words_go_is_one_rule_for_every_combination() {
        for engine in ["free", "claude"] {
            for model in [None, Some("ollama:qwen"), Some("claude-sonnet-5-5")] {
                for out in [false, true] {
                    for ask_first in [false, true] {
                        for free_here in [false, true] {
                            let s = Settings {
                                project_engine: Some(engine.into()),
                                ask_before_free: ask_first,
                                ..Settings::default()
                            };
                            let free_model = model.is_some_and(|m| m.starts_with("ollama:"));
                            // What the service does with a new task.
                            let moved = !free_model
                                && (engine == "free" || (out && !ask_first))
                                && free_here;
                            let online = !free_model && !moved;
                            assert_eq!(
                                s.task_goes_online(model, out, free_here),
                                online,
                                "{engine} {model:?} out={out} ask={ask_first} free={free_here}"
                            );
                        }
                    }
                }
            }
        }
        // The settings line "Your PC's model" never leaves a new task online
        // while a model is there to take it.
        let free = Settings::default();
        assert!(free.project_free());
        assert!(!free.task_goes_online(None, false, true));
        // With nothing to move it to, it is honest: online.
        assert!(free.task_goes_online(None, false, false));
    }

    #[test]
    fn the_pill_never_says_online_when_online_is_not_set_up() {
        for chats in [None, Some("paid"), Some("free")] {
            for project in [None, Some("free"), Some("claude")] {
                for model in [None, Some("ollama:qwen"), Some("sonnet")] {
                    for free_here in [false, true] {
                        for is_chat in [false, true] {
                            let s = Settings {
                                chats_engine: chats.map(Into::into),
                                project_engine: project.map(Into::into),
                                ..Settings::default()
                            };
                            let w =
                                format!("{chats:?} {project:?} {model:?} {free_here} {is_chat}");
                            // A followed session is never said to go online.
                            assert!(!s.task_shows_online(model, is_chat, true, free_here), "{w}");
                            let shown = s.task_shows_online(model, is_chat, false, free_here);
                            if is_chat {
                                assert_eq!(shown, s.chats_paid(), "{w}");
                            } else if model.is_none() && free_here && !s.online_set_up() {
                                // Online not set up and a model here: the task stays on the PC.
                                assert!(!shown, "{w}");
                            } else if model.is_none() {
                                assert_eq!(
                                    shown,
                                    s.task_goes_online(None, false, free_here),
                                    "{w}"
                                );
                            } else {
                                assert_eq!(shown, !crate::models::is_free(model), "{w}");
                            }
                        }
                    }
                }
            }
        }
        // The case from the field: nothing set up, a chat held as a task
        // with no model of its own is not "Online".
        let none = Settings::default();
        assert!(!none.task_shows_online(None, true, false, true));
        assert!(!none.task_shows_online(None, false, false, true));
    }

    fn online(api_credits: Option<bool>, hint: Option<&str>, free_when_out: bool) -> Settings {
        Settings {
            project_engine: Some("claude".into()),
            api_credits,
            api_key_hint: hint.map(str::to_string),
            free_when_out: Some(free_when_out),
            ..Default::default()
        }
    }

    /// P26: every row of the order when the plan runs out: the plan, then
    /// the API credits, then the PC's own model, then stop.
    #[test]
    fn p26_the_order_when_the_plan_runs_out() {
        let key = Some("1234");
        // The plan has room: always the plan, whatever else is set.
        for s in [
            online(Some(true), key, true),
            online(Some(true), key, false),
            online(None, None, true),
            online(None, None, false),
        ] {
            assert_eq!(s.lane(false, false), Lane::Plan);
            assert_eq!(s.lane(false, true), Lane::Plan);
        }
        // Out, the switch on and a key saved: the API credits, before the
        // PC's model.
        assert_eq!(
            online(Some(true), key, true).lane(true, false),
            Lane::ApiCredits
        );
        assert_eq!(
            online(Some(true), key, false).lane(true, false),
            Lane::ApiCredits
        );
        // Out, and the credits failed (out of credits, or the key refused):
        // the PC's model when its switch is on, else stop.
        assert_eq!(
            online(Some(true), key, true).lane(true, true),
            Lane::PcModel
        );
        assert_eq!(online(Some(true), key, false).lane(true, true), Lane::Stop);
        // Out, the credits switch off or never set: as before P26.
        for off in [Some(false), None] {
            assert_eq!(online(off, key, true).lane(true, false), Lane::PcModel);
            assert_eq!(online(off, key, false).lane(true, false), Lane::Stop);
        }
        // Out, the switch on but no key saved: the switch does not count.
        assert!(!online(Some(true), None, true).api_credits_on());
        assert_eq!(
            online(Some(true), None, true).lane(true, false),
            Lane::PcModel
        );
        assert_eq!(
            online(Some(true), None, false).lane(true, false),
            Lane::Stop
        );
        // The older "ask first" still turns the PC's model off.
        let mut ask = online(None, None, true);
        ask.ask_before_free = true;
        assert_eq!(ask.lane(true, false), Lane::Stop);
        ask.api_credits = Some(true);
        ask.api_key_hint = Some("1234".into());
        assert_eq!(ask.lane(true, false), Lane::ApiCredits);
        // Project work set to the PC's model never goes online.
        let mut pc = online(Some(true), key, true);
        pc.set_power(Power::FreeOnly);
        for (out, failed) in [(false, false), (true, false), (true, true)] {
            assert_eq!(pc.lane(out, failed), Lane::PcModel);
        }
    }

    /// P26: the yes/no the older callers ask follows the same order: with
    /// the API credits ready, a new task is not moved to the PC's model.
    #[test]
    fn p26_api_credits_come_before_the_pcs_model() {
        let s = online(Some(true), Some("1234"), true);
        assert!(!s.moves_to_free(None, true));
        assert!(s.task_goes_online(None, true, true));
        // The settings part the service keeps has the switch and the hint,
        // and they come back from a saved file.
        let part = service_part(&s);
        assert_eq!(part["api_credits"], true);
        assert_eq!(part["api_key_hint"], "1234");
        assert!(with_part(&Settings::default(), &part).api_credits_on());
    }
}
