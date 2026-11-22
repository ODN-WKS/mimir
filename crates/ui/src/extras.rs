//! Smaller helpers of the task view: saved prompts, find in a
//! conversation, a message that waits until Claude is done, the summary
//! asked for when a task finishes, and saving a conversation as a file.

use crate::app::{Action, App};
use crate::places::{Prompt, Settings};
use crate::theme::{self, MUTED, RED};
use eframe::egui::{self, RichText};
use mimir_core::task::{Entry, Task};
use std::path::PathBuf;

pub use mimir_core::queue::{SUMMARY_PROMPT, Waiting, waiting_now};

/// Find in a conversation: the words, and which match is shown.
#[derive(Default)]
pub struct Find {
    pub text: String,
    pub at: usize,
    /// Put the cursor in the box on the next frame.
    pub focus: bool,
    /// Scroll to the match on the next frame.
    pub jump: bool,
}

/// The entries (by index) whose text has `needle` in it, any case.
pub fn find_matches(entries: &[Entry], needle: &str) -> Vec<usize> {
    let needle = needle.trim().to_lowercase();
    if needle.is_empty() {
        return vec![];
    }
    entries
        .iter()
        .enumerate()
        .filter(|(_, e)| match e {
            Entry::You(t) | Entry::Claude(t) => t.to_lowercase().contains(&needle),
            _ => false,
        })
        .map(|(n, _)| n)
        .collect()
}

/// The conversation as Markdown.
pub fn markdown(t: &Task, project: &str, date: &str) -> String {
    let mut out = format!("# {}\n\nProject: {project}  \nSaved: {date}\n", t.title);
    let mut in_steps = false;
    for e in &t.entries {
        match e {
            Entry::You(text) if text.trim() == SUMMARY_PROMPT => {
                out.push_str("\n## Summary asked for\n");
                in_steps = false;
            }
            Entry::You(text) if crate::view_task::is_stop_marker(text) => {
                out.push_str(&format!("\n*{}*\n", crate::labels::STOPPED));
                in_steps = false;
            }
            Entry::You(text) => {
                out.push_str(&format!("\n## You\n\n{}\n", text.trim()));
                in_steps = false;
            }
            Entry::Claude(text) => {
                out.push_str(&format!("\n## Mímir\n\n{}\n", text.trim()));
                in_steps = false;
            }
            Entry::Step(s) => {
                if !in_steps {
                    out.push('\n');
                }
                out.push_str(&format!("- {}\n", s.line().replace('\n', " ")));
                in_steps = true;
            }
            Entry::Note(n) => {
                out.push_str(&format!("\n*{}*\n", n.trim()));
                in_steps = false;
            }
        }
    }
    out
}

/// A file name from a title: letters, digits, spaces and a few marks.
pub fn safe_name(title: &str) -> String {
    let s: String = title
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || matches!(c, ' ' | '-' | '_' | '.' | '(' | ')') {
                c
            } else {
                ' '
            }
        })
        .collect();
    let s = s.split_whitespace().collect::<Vec<_>>().join(" ");
    let s: String = s.trim_matches('.').chars().take(60).collect();
    if s.trim().is_empty() {
        "Conversation".into()
    } else {
        s.trim().to_string()
    }
}

/// A path in `dir` for `name` that is not taken: "x.md", "x (2).md", …
pub fn free_path(dir: &std::path::Path, name: &str) -> PathBuf {
    let mut p = dir.join(format!("{name}.md"));
    let mut n = 2;
    while p.exists() {
        p = dir.join(format!("{name} ({n}).md"));
        n += 1;
    }
    p
}

pub(crate) fn today() -> String {
    std::process::Command::new("date")
        .arg("+%Y-%m-%d")
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "today".into())
}

/// The prompts that come with Mímir, once.
pub fn starter_prompts() -> Vec<Prompt> {
    [
        (
            "Explain what you changed",
            "Explain what you changed in plain words, and anything I should check.",
        ),
        (
            "Run the checks",
            "Run the project's checks and tests, and fix what fails.",
        ),
        (
            "What's next?",
            "What is left to do here? List it shortest first.",
        ),
    ]
    .into_iter()
    .map(|(name, text)| Prompt {
        name: name.into(),
        text: text.into(),
    })
    .collect()
}

/// The "Prompts" menu next to a message box. A chosen prompt goes into
/// `draft` (after what is there), not sent.
pub fn prompts_menu(
    ui: &mut egui::Ui,
    settings: &Settings,
    project: &str,
    draft: &mut String,
    actions: &mut Vec<Action>,
) {
    ui.menu_button("Saved messages ▾", |ui| {
        crate::theme::menu_fits(ui, |ui| {
            ui.set_max_width(320.0);
            let mine = settings.project_prompts.get(project);
            let mut put = |ui: &mut egui::Ui, p: &Prompt| {
                if ui
                    .button(&p.name)
                    .on_hover_text(p.text.chars().take(300).collect::<String>())
                    .clicked()
                {
                    if !draft.trim().is_empty() {
                        draft.push('\n');
                    }
                    draft.push_str(&p.text);
                    actions.push(Action::FocusComposer);
                }
            };
            if let Some(mine) = mine.filter(|m| !m.is_empty()) {
                ui.label(RichText::new("This project").color(MUTED).size(13.0));
                for p in mine {
                    put(ui, p);
                }
                ui.separator();
            }
            for p in &settings.prompts {
                put(ui, p);
            }
            if settings.prompts.is_empty() && mine.is_none_or(|m| m.is_empty()) {
                ui.label(RichText::new("No saved messages yet.").color(MUTED));
            }
            ui.separator();
            if ui
                .add_enabled(
                    !draft.trim().is_empty(),
                    egui::Button::new("Save what I typed as a message…"),
                )
                .clicked()
            {
                actions.push(Action::AskSavePrompt(project.to_string(), draft.clone()));
            }
            if ui.button("Manage saved messages…").clicked() {
                actions.push(Action::ManagePrompts(project.to_string()));
            }
        })
    })
    .response
    .on_hover_text("Messages you use often, one click away");
}

/// "Save what I typed as a prompt": the text, its name, and where it goes.
pub struct SavePrompt {
    pub project: String,
    pub text: String,
    pub name: String,
    pub for_project: bool,
}

impl App {
    /// Saves a task's conversation in Documents and says where.
    pub(crate) fn save_conversation(&mut self, id: &str) {
        let Ok(r) = &mut self.station else { return };
        let Some(l) = r.tasks.iter().find(|l| l.task.id == id) else {
            return;
        };
        let date = today();
        let project = crate::places::display_name(&self.settings, l.task.home_project());
        let text = markdown(&l.task, &project, &date);
        let dir = crate::places::saved_conversations_dir();
        let name = format!("{} {date}", safe_name(&l.task.title));
        let saved = std::fs::create_dir_all(&dir).and_then(|_| {
            let p = free_path(&dir, &name);
            std::fs::write(&p, text).map(|_| p)
        });
        match saved {
            Ok(p) => {
                r.note(id, &format!("Saved this conversation as {}", p.display()));
                crate::places::open_path(&dir);
            }
            Err(_) => r.problem("The conversation could not be saved. Check that the Documents folder has room, then try again."),
        }
    }

    /// "Save this message" and "Manage prompts".
    pub(crate) fn prompt_windows(&mut self, ctx: &egui::Context) {
        if let Some(s) = self.prompt_save.as_mut() {
            let mut open = true;
            let mut done = false;
            let mut cancel = false;
            egui::Window::new("Save this message")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .open(&mut open)
                .show(ctx, |ui| {
                    ui.label(
                        RichText::new(s.text.chars().take(200).collect::<String>())
                            .color(MUTED)
                            .size(13.0),
                    );
                    let edit = ui.add(
                        egui::TextEdit::singleline(&mut s.name)
                            .desired_width(320.0)
                            .hint_text("A short name"),
                    );
                    crate::menus::edit_menu(&edit, "shortcut-name");
                    if s.name.is_empty() && !edit.has_focus() {
                        edit.request_focus();
                    }
                    ui.horizontal(|ui| {
                        ui.radio_value(&mut s.for_project, false, "For everything");
                        ui.radio_value(
                            &mut s.for_project,
                            true,
                            format!(
                                "Only for {}",
                                crate::places::display_name(&self.settings, &s.project)
                            ),
                        );
                    });
                    let ok = !s.name.trim().is_empty();
                    let enter = ok && ui.input(|i| i.key_pressed(egui::Key::Enter));
                    ui.horizontal(|ui| {
                        if ui.add(theme::quiet("Cancel")).clicked() {
                            cancel = true;
                        }
                        if ui.add_enabled(ok, theme::primary("Save")).clicked() || enter {
                            done = true;
                        }
                    });
                });
            if done && let Some(s) = self.prompt_save.take() {
                let p = Prompt {
                    name: s.name.trim().to_string(),
                    text: s.text,
                };
                if s.for_project {
                    self.settings
                        .project_prompts
                        .entry(s.project)
                        .or_default()
                        .push(p);
                } else {
                    self.settings.prompts.push(p);
                }
                self.settings_dirty = true;
            } else if !open || cancel {
                self.prompt_save = None;
            }
        }

        if self.listen_ask {
            let mut open = true;
            egui::Window::new(crate::labels::TALK)
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .open(&mut open)
                .show(ctx, |ui| {
                    ui.set_max_width(420.0);
                    ui.label(format!(
                        "This needs a speech model: one download of about {} MB, kept on \
                         this PC.",
                        mimir_core::listen::DOWNLOAD_MB
                    ));
                    ui.label(
                        RichText::new(
                            "Your voice is turned into text on this PC. Nothing you say is \
                             sent anywhere. It is a small English speech model, run by \
                             the same engine as Read aloud. Remove it in Settings › Storage.",
                        )
                        .color(MUTED)
                        .size(13.0),
                    );
                    ui.horizontal(|ui| {
                        if ui.add(theme::quiet("Cancel")).clicked() {
                            self.listen_ask = false;
                        }
                        if ui.add(theme::primary("Download")).clicked() {
                            crate::places::download_listen(ctx);
                            self.listen_ask = false;
                        }
                    });
                });
            if !open {
                self.listen_ask = false;
            }
        }

        if let Some(project) = self.prompts_open.clone() {
            let mut open = true;
            let mut changed = false;
            let name = crate::places::display_name(&self.settings, &project);
            egui::Window::new("Saved messages")
                .collapsible(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .default_width(520.0)
                .open(&mut open)
                .show(ctx, |ui| {
                    egui::ScrollArea::vertical()
                        .max_height(ctx.content_rect().height() * 0.7)
                        .show(ui, |ui| {
                            theme::heading(ui, "For everything");
                            changed |= prompt_list(ui, "all", &mut self.settings.prompts);
                            ui.add_space(10.0);
                            theme::heading(ui, &format!("Only for {name}"));
                            let mine = self
                                .settings
                                .project_prompts
                                .entry(project.clone())
                                .or_default();
                            changed |= prompt_list(ui, "mine", mine);
                        });
                });
            if changed {
                self.settings.project_prompts.retain(|_, v| !v.is_empty());
                self.settings_dirty = true;
            }
            if !open {
                self.settings.project_prompts.retain(|_, v| !v.is_empty());
                self.prompts_open = None;
            }
        }
    }
}

/// The find bar above a conversation. Returns the entry to mark.
pub fn find_bar(slot: &mut Option<Find>, ui: &mut egui::Ui, entries: &[Entry]) -> Option<usize> {
    let find = slot.as_mut()?;
    let hits = find_matches(entries, &find.text);
    if find.at >= hits.len() {
        find.at = hits.len().saturating_sub(1);
    }
    let mut close = false;
    let mut step = 0i32;
    ui.horizontal(|ui| {
        ui.label(RichText::new("Find").color(MUTED));
        let before = find.text.clone();
        let edit = ui.add(
            egui::TextEdit::singleline(&mut find.text)
                .id_salt("find-box")
                .desired_width(260.0)
                .hint_text("Words in this conversation"),
        );
        crate::menus::edit_menu(&edit, "find-box");
        if std::mem::take(&mut find.focus) {
            edit.request_focus();
        }
        if find.text != before {
            // Start at the newest match.
            find.at = usize::MAX;
            find.jump = true;
        }
        let enter = edit.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
        if enter {
            step = if ui.input(|i| i.modifiers.shift) {
                -1
            } else {
                1
            };
            edit.request_focus();
        }
        if ui.input(|i| i.key_pressed(egui::Key::Escape)) {
            close = true;
        }
        let count = if find.text.trim().is_empty() {
            String::new()
        } else if hits.is_empty() {
            "Nothing found".to_string()
        } else {
            format!("{} of {}", find.at.min(hits.len() - 1) + 1, hits.len())
        };
        ui.label(RichText::new(count).color(MUTED).size(13.0));
        if ui
            .add_enabled(!hits.is_empty(), theme::quiet("↑").small())
            .on_hover_text("Earlier (Shift+Enter)")
            .clicked()
        {
            step = -1;
        }
        if ui
            .add_enabled(!hits.is_empty(), theme::quiet("↓").small())
            .on_hover_text("Later (Enter)")
            .clicked()
        {
            step = 1;
        }
        if ui
            .add(theme::quiet("✕").small())
            .on_hover_text("Close (Esc)")
            .clicked()
        {
            close = true;
        }
    });
    ui.add_space(4.0);
    if close {
        *slot = None;
        return None;
    }
    if hits.is_empty() {
        return None;
    }
    find.at = find.at.min(hits.len() - 1);
    if step != 0 {
        let n = hits.len() as i32;
        find.at = (find.at as i32 + step).rem_euclid(n) as usize;
        find.jump = true;
    }
    Some(hits[find.at])
}

/// One editable list of prompts. True when something changed.
fn prompt_list(ui: &mut egui::Ui, salt: &str, list: &mut Vec<Prompt>) -> bool {
    let mut changed = false;
    let mut remove = None;
    let mut swap = None;
    let n = list.len();
    for (i, p) in list.iter_mut().enumerate() {
        ui.horizontal(|ui| {
            let name_edit = ui.add(
                egui::TextEdit::singleline(&mut p.name)
                    .id_salt((salt, "name", i))
                    .desired_width(180.0)
                    .hint_text("Name"),
            );
            crate::menus::edit_menu(&name_edit, &format!("{salt}-name-{i}"));
            changed |= name_edit.changed();
            if ui
                .add_enabled(i > 0, theme::quiet("↑").small())
                .on_hover_text("Move up")
                .clicked()
            {
                swap = Some((i, i - 1));
            }
            if ui
                .add_enabled(i + 1 < n, theme::quiet("↓").small())
                .on_hover_text("Move down")
                .clicked()
            {
                swap = Some((i, i + 1));
            }
            if ui
                .add(egui::Button::new(RichText::new("Delete").color(RED)).small())
                .clicked()
            {
                remove = Some(i);
            }
        });
        let text_edit = ui.add(
            egui::TextEdit::multiline(&mut p.text)
                .id_salt((salt, "text", i))
                .desired_width(f32::INFINITY)
                .desired_rows(2),
        );
        crate::menus::edit_menu(&text_edit, &format!("{salt}-text-{i}"));
        changed |= text_edit.changed();
        ui.add_space(6.0);
    }
    if let Some((a, b)) = swap {
        list.swap(a, b);
        changed = true;
    }
    if let Some(i) = remove {
        list.remove(i);
        changed = true;
    }
    if ui.add(theme::quiet("+ Add a message")).clicked() {
        list.push(Prompt {
            name: "New message".into(),
            text: String::new(),
        });
        changed = true;
    }
    changed
}

#[cfg(test)]
mod tests {
    use super::*;
    use mimir_core::queue::wants_summary;
    use mimir_core::task::{Place, Status, Step, StepDetail};

    fn step(what: &str, refused: bool) -> Entry {
        Entry::Step(Step {
            tool_use_id: "x".into(),
            tool: "Bash".into(),
            what: what.into(),
            place: Place::ThisPc,
            started_ms: 0,
            took_ms: Some(10),
            ok: Some(true),
            refused,
            stopped: false,
            detail: StepDetail::default(),
        })
    }

    #[test]
    fn find_looks_in_messages_any_case() {
        let e = vec![
            Entry::You("Fix the Login page".into()),
            step("Ran a command: login test", false),
            Entry::Claude("The login works now.".into()),
            Entry::Note("login".into()),
        ];
        assert_eq!(find_matches(&e, "LOGIN "), vec![0, 2]);
        assert!(find_matches(&e, "  ").is_empty());
        assert!(find_matches(&e, "zebra").is_empty());
    }

    #[test]
    fn a_waiting_message_goes_only_when_claude_is_done() {
        use Status::*;
        assert_eq!(waiting_now(Working, false, false, false), Waiting::Wait);
        assert_eq!(waiting_now(NeedsYou, true, false, false), Waiting::Wait);
        assert_eq!(waiting_now(Done, false, false, false), Waiting::Send);
        assert!(matches!(
            waiting_now(Done, false, true, false),
            Waiting::Hold(_)
        ));
        assert!(matches!(
            waiting_now(Done, false, false, true),
            Waiting::Hold(_)
        ));
        assert!(matches!(
            waiting_now(Paused, false, false, false),
            Waiting::Hold(_)
        ));
        assert!(matches!(
            waiting_now(Stopped, false, false, false),
            Waiting::Hold(_)
        ));
    }

    #[test]
    fn a_summary_is_asked_after_work_and_never_twice() {
        let you = |t: &str| Entry::You(t.into());
        let claude = || Entry::Claude("Done.".into());
        // Only talk: no summary.
        assert!(!wants_summary(&[you("hi"), claude()]));
        assert!(wants_summary(&[
            you("fix it"),
            step("Changed a.rs", false),
            claude()
        ]));
        // A step that was not allowed is not work.
        assert!(!wants_summary(&[
            you("fix it"),
            step("Change a.rs", true),
            claude()
        ]));
        // Earlier work does not count for a later chat turn.
        assert!(!wants_summary(&[
            you("fix it"),
            step("Changed a.rs", false),
            claude(),
            you("thanks"),
            claude()
        ]));
        // The summary itself, even if Claude ran something for it.
        assert!(!wants_summary(&[
            you(SUMMARY_PROMPT),
            step("Ran a command: git status", false),
            claude()
        ]));
        assert!(!wants_summary(&[]));
    }

    #[test]
    fn a_conversation_as_markdown() {
        let mut t = Task::new("1".into(), "Fix login".into(), "/p".into(), 0);
        t.entries = vec![
            Entry::You("Fix it".into()),
            step("Ran a command: cargo test", false),
            step("Changed a.rs", false),
            Entry::Claude("It **works**.".into()),
            Entry::You(SUMMARY_PROMPT.into()),
            Entry::Claude("I changed a.rs.".into()),
        ];
        let md = markdown(&t, "My project", "2026-11-14");
        assert!(md.starts_with("# Fix login\n\nProject: My project  \nSaved: 2026-11-14\n"));
        assert!(md.contains("\n## You\n\nFix it\n\n- Ran a command: cargo test\n- Changed a.rs\n"));
        assert!(md.contains("\n## Mímir\n\nIt **works**.\n"));
        assert!(md.contains("## Summary asked for"));
        assert!(!md.contains(SUMMARY_PROMPT));
    }

    #[test]
    fn names_and_paths_for_saved_conversations() {
        assert_eq!(safe_name("Fix: the /login page?"), "Fix the login page");
        assert_eq!(safe_name("///"), "Conversation");
        assert_eq!(safe_name(&"a".repeat(100)).len(), 60);
        let dir = std::env::temp_dir().join(format!("cs-save-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let a = free_path(&dir, "x");
        assert_eq!(a, dir.join("x.md"));
        std::fs::write(&a, "1").unwrap();
        let b = free_path(&dir, "x");
        assert_eq!(b, dir.join("x (2).md"));
        std::fs::write(&b, "2").unwrap();
        assert_eq!(free_path(&dir, "x"), dir.join("x (3).md"));
        let _ = std::fs::remove_dir_all(dir);
    }
}
