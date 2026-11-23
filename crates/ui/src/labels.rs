//! Words on screen that the window checks look for. The views use these, so
//! changing a word here changes the window and its checks together.

/// The New task page's button (for a project, and for a general chat).
pub const START_TASK: &str = "Start task";
pub const START_CHAT: &str = "Start chat";

/// The message box's button, and the one shown while Claude works.
pub const SEND: &str = "Send";
pub const SEND_NOW: &str = "Send now";

/// The window's chats (the same as the phone's).
pub const CHATS: &str = "Chats";
pub const NEW_CHAT: &str = "New chat";
pub const STOP: &str = "Stop";
pub const ANSWERING: &str = "Answering";
pub const YOUR_PC: &str = "Your PC";
pub const THIS_PHONE: &str = "This phone";
/// A chat's row in the side list, for a screen reader.
pub fn chat_row(title: &str) -> String {
    format!("Chat: {title}")
}
pub const CHATS_ON_PC: &str = "Chats are answered on this PC, and your phone shows the same ones.";
pub const CHAT_EMPTY: &str = "Ask anything. Your PC answers; no project is touched.";

/// Answers to "Claude wants to …".
pub const ALLOW: &str = "Allow";
pub const DONT_ALLOW: &str = "Don't allow";

/// "Always allow in <project>".
pub fn always_allow_in(project: &str) -> String {
    format!("Always allow in {project}")
}

/// The heading of a question Claude is waiting on.
pub const CLAUDE_ASKS_YOU: &str = "Needs you";

/// The question card's spoken name, with the question itself.
pub fn question_card(question: &str) -> String {
    format!("{CLAUDE_ASKS_YOU}: {}", question.trim())
}

/// The task's button that carries the conversation on the phone too.
pub const PHONE_TOO: &str = "Your phone";

/// Shown while the window can't reach Mímir's service.
pub const RECONNECTING: &str = "Reconnecting to Mímir…";

/// The sidebar's update button, and Settings' line once it is in.
pub fn update_to(version: &str) -> String {
    format!("Update to {version}")
}

/// The task menus' choice that moves a task to another project, and the
/// picker's title.
pub const MOVE_TO: &str = "Move to…";
pub const MOVE_TO_TITLE: &str = "Move to another project";

/// Said instead of the projects while Claude works on the task.
/// Where the owner pressed Stop in a conversation.
pub const STOPPED: &str = "Stopped";

pub const MOVE_WHEN_STOPPED: &str = "Working on it. Move it when it stops.";

/// The line on a chat Mímir filed itself, before its "change".
pub fn filed_under(project: &str, how: mimir_core::proto::Placed) -> String {
    match how {
        mimir_core::proto::Placed::Unsure => format!("Filed under {project}, not sure ·"),
        _ => format!("Filed under {project} ·"),
    }
}
pub const THATS_RIGHT: &str = "that's right";

/// Settings › Storage's one-time tidy, its dialog and its button.
pub const TIDY: &str = "Tidy projects…";
pub const TIDY_TITLE: &str = "Tidy projects";
pub fn tidy_go(n: usize) -> String {
    match n {
        1 => "Make this 1 change".to_string(),
        n => format!("Make these {n} changes"),
    }
}

/// The project menu's choices that tidy the list.
pub const MERGE_INTO: &str = "Merge into…";
pub const MERGE_TITLE: &str = "Merge into another project";
pub const REMOVE_FROM_LIST: &str = "Remove from the list…";

/// The side list's offer of git projects that aren't listed, and its dialog.
pub fn add_projects(n: usize) -> String {
    format!("Add projects ({n})")
}
pub const ADD_PROJECTS_TITLE: &str = "Add projects";

/// A task's row in the sidebar, by its spoken name.
/// A task's row under "Active now" (its project's row has [`task_row`]).
pub fn active_row(title: &str, project: &str) -> String {
    format!("Active now: {title}, in {project}")
}

pub fn task_row(title: &str, project: &str) -> String {
    format!("Task {title}, in {project}")
}

/// A session from elsewhere on this PC (terminal, phone), by its spoken
/// name.
pub fn session_row(title: &str, place: &str) -> String {
    format!("Session {title}, from {place}")
}

/// The plan's weekly use, beside Settings at the foot of the sidebar.
pub fn weekly_use(percent: f64) -> String {
    format!("{percent:.0}% of weekly limit")
}

/// Settings › About, for a copy that updates through Flatpak.
pub fn update_ready(version: &str) -> String {
    format!("Version {version} is ready.")
}
pub const UPDATE_NOW: &str = "Update now";
pub const SWITCH_WHY: &str = "This copy was installed from a file, so it can't update by itself.";
pub const SWITCH_BUTTON: &str = "Switch to automatic updates";
pub const SWITCH_CONFIRM: &str = "Mímir will update like your other apps from now on. \
    Your chats and settings stay. Mímir will need a restart.";
pub const SWITCH_YES: &str = "Yes, switch";
pub const SWITCH_NO: &str = "Not now";
pub const RESTART: &str = "Restart Mímir to finish";
pub fn update_installed(version: &str) -> String {
    format!("Version {version} is installed.")
}
pub fn updating(version: &str, percent: Option<u8>) -> String {
    match percent {
        Some(p) => format!("Getting {version}… {p}%"),
        None => format!("Getting {version}…"),
    }
}

/// A new version was opened in the software centre.
pub fn update_opened(version: &str) -> String {
    format!("{version} is open in your software centre")
}

/// Whisper mode (called incognito in the code): a free model on this PC, the
/// internet cut off for the task, nothing kept.
pub const WHISPER_SWITCH: &str = "Whisper mode: internet cut off, nothing kept after you end it";
pub const WHISPER_NOTE: &str = "Whisper mode · internet cut off · nothing is kept";
pub const END_AND_FORGET: &str = "End and forget";

/// A project folder's header in the sidebar, by its spoken name.
pub fn folder_row(name: &str) -> String {
    format!("Folder {name}")
}

/// The + on a folder's header.
pub fn new_task_in(name: &str) -> String {
    format!("New task in {name}")
}

/// The quiet last line of the sidebar.
pub fn archived(n: usize) -> String {
    format!("Archived ({n})")
}

pub fn from_elsewhere(n: usize) -> String {
    format!("From elsewhere ({n})")
}

/// "2 need you", in the Projects note.
pub fn need_you(n: usize) -> String {
    format!("{n} need{} you", if n == 1 { "s" } else { "" })
}

/// The keys that answer "Claude wants to …", shown on the card.
pub const ANSWER_KEYS: &str = "Ctrl+Enter allows · Ctrl+D doesn't allow";

/// "Don't allow", with a note for Claude on what to do instead.
pub const DONT_ALLOW_SAY_WHY: &str = "Don't allow and say why…";

/// The card's fold for rarely needed answers, and "Always allow" in it.
pub const MORE: &str = "More ▾";
pub const ALWAYS_ALLOW_MORE: &str = "Always allow…";

/// Failure cards in a task: what happened, in plain words. None of them
/// names a button; the card has its own.
pub const DIDNT_START: &str = "It didn't start. Nothing was changed.";
pub const CLOSED_EARLY: &str = "Work stopped before it finished. What was done so far is kept.";
pub const RESTARTED: &str = "Mímir restarted while this task was working. \
     What was done so far is kept.";
pub const TRY_AGAIN: &str = "Try again";
/// When the window can't reach Mímir's service.
pub const TRYING_AGAIN: &str = "Trying again…";
pub fn trying_again_in(secs: u64) -> String {
    format!("Trying again by itself in {secs} s.")
}

/// Speech to text: one name everywhere ("Whisper" is only the private mode).
pub const TALK: &str = "Talk / voice typing";

/// Claude's limit ran out and a free model carries the task on.
pub const ON_FREE_UNTIL_BACK: &str = "On your PC's model until online is back";
pub const CONTINUE_ON_FREE: &str = "Continue on your PC's model";
pub const BACK_TO_CLAUDE: &str = "Back online";
/// A session started from the phone or claude.ai through a project's
/// Remote Control: the window follows it, and replies go there.
pub const FROM_PHONE: &str = "From your phone";
pub const FROM_PHONE_SHORT: &str = "Phone";
/// The group at the very top of the side list: every task going on right
/// now, wherever it is typed in.
pub const ACTIVE_NOW: &str = "Active now";
/// What every name of a followed session still going on starts with.
pub const LIVE: &str = "Still running";
/// The one name for a followed session still going on outside Mímir, in
/// the header and the panel (`place`: "app" or "terminal").
pub fn running_state(place: Option<&str>) -> &'static str {
    match place {
        Some("app") => "Still running in the Claude app",
        Some("terminal") => "Still running in a terminal",
        _ => "Still running on your PC",
    }
}
/// The button beside that line: ends the session where it runs.
pub const STOP_ELSEWHERE: &str = "Stop";

/// A task's row while Claude works on it (here or elsewhere).
pub const WORKING: &str = "Working";
pub const FOLLOWS_ALONG: &str = "Reply from your phone; this view follows along live.";
/// A followed session still running somewhere else: where, and what to do.
/// `place` is the PC's "app" or "terminal"; absent (an older PC) says "your PC".
pub fn still_running_in(place: Option<&str>) -> String {
    let at = match place {
        Some("app") => "in the Claude app",
        Some("terminal") => "in a terminal",
        _ => "on your PC",
    };
    format!("Still running {at}. Stop it to carry on here.")
}
/// The link out is for a session that really started on claude.ai.
pub fn is_claude_ai_session(url: &str) -> bool {
    url.starts_with("https://claude.ai/")
}
pub const OPEN_ON_CLAUDE_AI: &str = "Open on claude.ai";
/// Settings › Models: the rules sent with every task.
pub const GROUND_RULES: &str = "Rules for every chat";
pub const GROUND_RULES_SEND: &str = "Send these with every chat and task";
pub const GROUND_RULES_NOTE: &str = "Online answers follow their own safety rules on top of these; your PC's model and the phone's models follow these as written.";
/// Settings: the card of experiments, and its first switch.
pub const LABS: &str = "Labs";
pub const LABS_PC_ACTIVITY: &str = "Show what Mímir is doing on this PC";
/// Labs: the developer log's two switches (the same words as the phone's).
pub const DEV_LOG_TITLE: &str = "Keep notes for fixing problems";
pub const DEV_LOG_CAPTION: &str = "Off until you choose. Notes stay on this PC for up to 30 days, only to fix problems. Never your Whispers.";
pub const DEV_CHATS_TITLE: &str = "Include my normal chats";
pub const DEV_CHATS_CAPTION: &str =
    "Off until you choose. Adds your normal chats to the notes, on this PC only. Never Whispers.";

/// The middle's top: the pill that says who answers, and the panel toggle.
pub const PILL_MENU: &str = "Who answers, and more";
pub const HIDE_PANEL: &str = "Hide panel ›";
pub const SHOW_PANEL: &str = "‹ Show panel";
pub const PILL_PC: &str = "This PC";
/// The pill while the PC's model program isn't running.
pub const PILL_PC_DOWN: &str = "Your PC's model is off";
pub const PC_DOWN_LINE: &str = "Your PC's model isn't running. Try again in the chat.";
/// The same line on a page that has no chat to try again in.
/// A "Try again" button next to it starts the model program.
pub const PC_DOWN_LINE_ELSEWHERE: &str = "Your PC's model isn't running. Press Try again.";
/// The pill menu's line while the PC's model is off: "Try again" only where a chat is.
pub fn pc_down_line(on_chat: bool) -> &'static str {
    if on_chat {
        PC_DOWN_LINE
    } else {
        PC_DOWN_LINE_ELSEWHERE
    }
}
pub const PILL_CLAUDE: &str = "Online · goes to Anthropic";
/// The same pills for a narrow middle.
pub const PILL_CLAUDE_SHORT: &str = "Online";
pub const PILL_PC_DOWN_SHORT: &str = "Model off";
/// A session going on in the Claude app or a terminal: Mímir only follows it.
pub const PILL_OUTSIDE: &str = "Not run by Mímir";
pub const PILL_OUTSIDE_SHORT: &str = "Outside Mímir";
pub const PILL_OUTSIDE_LINE: &str = "Mímir only follows this session. It answers where it runs.";

/// The message box.
pub const BOX_CHAT: &str = "Message Mímir";
pub const BOX_TASK: &str = "Reply, or type / for commands";
pub const ATTACH_FILES: &str = "Attach files";
/// The small ✕ on a picture waiting in the message box.
pub const REMOVE_PICTURE: &str = "Remove this picture";
pub const DICTATE: &str = "Dictate";
pub const HINT_CHAT: &str =
    "Enter send · Shift Enter new line · Ctrl N new chat · right-click for more";
pub const HINT_TASK: &str = "Enter send · Esc stop · right-click anything for more";

/// The calm greeting when nothing is open.
pub const ASK_HELP: &str = "What can I help with?";

/// The first line when nothing is open (the same words as the phone).
pub const GREETING: &str = "Hi, I'm Mímir.";

/// "Needs you · waiting N min" on a real waiting question.
pub fn needs_you_waiting(minutes: u64) -> String {
    if minutes == 0 {
        "Needs you".to_string()
    } else if minutes < 60 {
        format!("Needs you · waiting {minutes} min")
    } else {
        format!("Needs you · waiting {} h", minutes / 60)
    }
}

/// How wide the messages and the message box may get, in points.
pub const LANE_WIDTH: f32 = 680.0;

/// The side list's search found nothing.
pub fn nothing_found(search: &str) -> String {
    format!("Nothing found for “{search}”.")
}

/// Settings › Notifications, "On this PC": the pop-up for a finished task.
pub const POPUP_DONE_TITLE: &str = "Pop up when a task is done";
pub const POPUP_DONE_CAPTION: &str = "A task that needs you always pops up. A finished task only tells you in the window, unless you turn this on.";

/// The chat box's model chip when the PC's own model answers and none is named.
pub const CHIP_PC_MODEL: &str = "Your PC's model";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn try_again_is_said_only_where_a_chat_is() {
        assert!(pc_down_line(true).contains("Try again in the chat"));
        assert_eq!(
            pc_down_line(false),
            "Your PC's model isn't running. Press Try again."
        );
    }

    #[test]
    fn the_claude_ai_link_is_for_a_claude_ai_session_only() {
        assert!(is_claude_ai_session("https://claude.ai/code/session_1"));
        assert!(!is_claude_ai_session(""));
        assert!(!is_claude_ai_session("https://example.invalid/x"));
    }

    #[test]
    fn the_follow_along_line_names_only_the_phone() {
        assert_eq!(
            FOLLOWS_ALONG,
            "Reply from your phone; this view follows along live."
        );
        assert!(!FOLLOWS_ALONG.contains("claude.ai"));
    }

    #[test]
    fn the_rules_have_the_phones_name_and_no_emoji() {
        assert_eq!(GROUND_RULES, "Rules for every chat");
        assert!(!PHONE_TOO.contains('\u{1F4F1}'));
    }
}
