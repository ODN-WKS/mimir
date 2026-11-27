//! Arranging the sidebar: project order, names, folding, task order and
//! pins, deleting a task, removing a project. All of it is Mímir's
//! own bookkeeping in the settings; project folders are never touched.

use crate::app::{App, View};
use crate::places::{self, Settings};
use mimir_core::client::{Station, TaskView};

/// What the sidebar asks for; applied after the frame like `Action`.
pub enum Organize {
    /// Fold or unfold a project's task list.
    ToggleProject(String),
    PinTask(String, bool),
    /// Open the task with its name ready to change.
    RenameTask(String),
    /// Ask for a project's name in Mímir.
    AskName(String),
    /// The name typed in that dialog.
    Named(String, String),
    AskRemoveProject(String),
    /// Remove a project from the list; true deletes its tasks, false
    /// archives them.
    RemoveProject(String, bool),
    /// Ask before deleting a task.
    AskRemoveTask(String),
    /// Open "Move to…" for a task.
    AskMove(String),
    /// Move a task, conversation and all, to another project.
    MoveTo(String, String),
    /// Open "Merge into…" for a project.
    AskMerge(String),
    /// Fold a project's tasks into another project (from, into).
    Merge(String, String),
    /// Open "Add projects" with these unlisted git projects.
    AskAdd(Vec<String>),
    /// Add these folders to the list.
    Add(Vec<String>),
}

/// Whether two spellings name the same folder.
pub use mimir_core::paths::same;

/// Task ids in sidebar order: pinned ones first, then tasks not in the
/// owner's order (newest first), then the ordered ones.
pub fn order_tasks(newest_first: &[String], order: &[String], pinned: &[String]) -> Vec<String> {
    let mut out: Vec<String> = newest_first
        .iter()
        .filter(|id| !order.contains(id))
        .cloned()
        .collect();
    for id in order {
        if newest_first.contains(id) && !out.contains(id) {
            out.push(id.clone());
        }
    }
    let (mut first, rest): (Vec<String>, Vec<String>) =
        out.into_iter().partition(|id| pinned.contains(id));
    first.extend(rest);
    first
}

/// A project's tasks (not archived) in sidebar order: pinned first, then
/// newest first by the person's last message.
pub fn project_tasks<'a>(r: &'a Station, settings: &Settings, project: &str) -> Vec<&'a TaskView> {
    let mut newest: Vec<&TaskView> = r
        .tasks
        .iter()
        .rev()
        .filter(|l| same(l.task.home_project(), project) && !l.task.archived)
        .collect();
    newest.sort_by_key(|l| std::cmp::Reverse(mimir_core::order::task_key(&l.task)));
    let ids: Vec<String> = newest.iter().map(|l| l.task.id.clone()).collect();
    order_tasks(&ids, &[], &settings.pinned_tasks)
        .iter()
        .filter_map(|id| newest.iter().find(|l| &l.task.id == id).copied())
        .collect()
}

/// Why a project can't be removed right now, if anything stands in the way.
pub fn removal_blocked(tasks: &[(String, bool, bool, bool)], delete: bool) -> Option<String> {
    // (title, busy, phone too, separate copy)
    for (title, busy, phone, copy) in tasks {
        if *busy {
            return Some(format!(
                "\"{title}\" is still working. Pause or stop it first; nothing was removed."
            ));
        }
        if *phone {
            return Some(format!(
                "\"{title}\" has Your phone switched on. Switch it off first; nothing was removed."
            ));
        }
        if delete && *copy {
            return Some(format!(
                "\"{title}\" has a separate copy. Bring its changes in or remove the copy \
                 first; nothing was removed."
            ));
        }
    }
    None
}

impl App {
    /// A project's open task ids in sidebar order.
    fn task_ids(&self, project: &str) -> Vec<String> {
        self.station.as_ref().map_or(vec![], |r| {
            project_tasks(r, &self.settings, project)
                .iter()
                .map(|l| l.task.id.clone())
                .collect()
        })
    }

    /// Open tasks in sidebar order, for next/previous; folded projects are
    /// skipped.
    pub(crate) fn sidebar_tasks(&self) -> Vec<String> {
        self.projects()
            .iter()
            .filter(|p| !self.settings.collapsed_projects.contains(p))
            .flat_map(|p| self.task_ids(p))
            .collect()
    }

    /// Deletes a task from Mímir (files stay). False, with a
    /// problem shown, when something stands in the way.
    pub(crate) fn delete_task(&mut self, id: &str) -> bool {
        let Ok(r) = &mut self.station else {
            return false;
        };
        let Some(l) = r.tasks.iter().find(|l| l.task.id == id) else {
            return false;
        };
        let sessions: Vec<String> = l
            .task
            .session_id
            .iter()
            .chain(l.task.left_sessions())
            .cloned()
            .collect();
        if let Err(e) = r.remove(id) {
            r.problem(&e);
            return false;
        }
        // Its conversation stays in Claude Code's history; it is not
        // offered again under "On this PC".
        for sid in sessions {
            if !self.settings.hidden_sessions.contains(&sid) {
                self.settings.hidden_sessions.push(sid);
            }
        }
        self.settings.pinned_tasks.retain(|x| x != id);
        for v in self.settings.task_order.values_mut() {
            v.retain(|x| x != id);
        }
        self.drafts.remove(id);
        if self.selected.as_deref() == Some(id) {
            self.selected = None;
            self.view = View::NewTask;
        }
        true
    }

    /// Forgets what belongs to tasks and projects that are gone.
    pub(crate) fn tidy_settings(&mut self) {
        let Ok(r) = &self.station else { return };
        let tasks: Vec<String> = r.tasks.iter().map(|l| l.task.id.clone()).collect();
        let mut projects = self.settings.projects.clone();
        for l in &r.tasks {
            let home = l.task.home_project();
            if !projects.iter().any(|p| same(p, home)) {
                projects.push(home.to_string());
            }
        }
        if self.settings.tidy(&tasks, &projects) {
            self.settings_dirty = true;
        }
    }

    pub(crate) fn organize(&mut self, o: Organize) {
        match o {
            Organize::ToggleProject(p) => {
                let c = &mut self.settings.collapsed_projects;
                if c.contains(&p) {
                    c.retain(|x| x != &p);
                } else {
                    c.push(p);
                }
            }
            Organize::PinTask(id, yes) => {
                self.settings.pinned_tasks.retain(|x| x != &id);
                if yes {
                    self.settings.pinned_tasks.push(id);
                }
            }
            Organize::RenameTask(id) => {
                let title = self.station.as_ref().ok().and_then(|r| {
                    r.tasks
                        .iter()
                        .find(|l| l.task.id == id)
                        .map(|l| l.task.title.clone())
                });
                if let Some(title) = title {
                    self.select_task(&id);
                    self.renaming = Some((id, title, 0));
                }
                return;
            }
            Organize::AskName(p) => {
                let text = places::display_name(&self.settings, &p);
                self.naming = Some((p, text));
                return;
            }
            Organize::Named(p, name) => {
                self.naming = None;
                let name = name.trim().to_string();
                if name.is_empty() || name == places::project_name(&p) {
                    self.settings.project_names.remove(&p);
                } else {
                    self.settings.project_names.insert(p, name);
                }
            }
            Organize::AskRemoveTask(id) => {
                self.confirm_remove = Some(id);
                return;
            }
            Organize::AskMove(id) => {
                self.moving = Some(id);
                return;
            }
            Organize::MoveTo(id, project) => {
                self.moving = None;
                let Ok(r) = &mut self.station else { return };
                if let Err(e) = r.move_to(&id, &project) {
                    r.problem(&e);
                    return;
                }
                // It goes to the top of its new project, as a new task does.
                for v in self.settings.task_order.values_mut() {
                    v.retain(|x| x != &id);
                }
                if !self.settings.projects.iter().any(|p| same(p, &project)) {
                    self.settings.projects.push(project.clone());
                }
                self.settings
                    .collapsed_projects
                    .retain(|x| !same(x, &project));
            }
            Organize::AskMerge(p) => {
                self.merging = Some(p);
                return;
            }
            Organize::Merge(from, into) => {
                self.merging = None;
                let Ok(r) = &mut self.station else { return };
                if let Err(e) = r.merge_project(&from, &into) {
                    r.problem(&e);
                    return;
                }
                let s = &mut self.settings;
                s.projects.retain(|x| !same(x, &from));
                if !s.projects.iter().any(|x| same(x, &into)) {
                    s.projects.push(into.clone());
                }
                s.collapsed_projects
                    .retain(|x| !same(x, &from) && !same(x, &into));
                s.task_order.remove(&from);
                if s.last_project.as_deref().is_some_and(|x| same(x, &from)) {
                    s.last_project = Some(into.clone());
                }
                if self.new_project.as_deref().is_some_and(|x| same(x, &from)) {
                    self.new_project = Some(into);
                }
            }
            Organize::AskAdd(repos) => {
                self.adding = Some(repos.into_iter().map(|r| (r, true)).collect());
                return;
            }
            Organize::Add(folders) => {
                self.adding = None;
                for f in folders {
                    if !self.settings.projects.iter().any(|x| same(x, &f)) {
                        self.settings.projects.push(f);
                    }
                }
                // The offer is looked at again with the new list.
                self.others.lock().unwrap().checked = None;
            }
            Organize::AskRemoveProject(p) => {
                self.confirm_remove_project = Some(p);
                return;
            }
            Organize::RemoveProject(p, delete) => {
                self.confirm_remove_project = None;
                let Ok(r) = &mut self.station else { return };
                let mine: Vec<&TaskView> = r
                    .tasks
                    .iter()
                    .filter(|l| same(l.task.home_project(), &p))
                    .filter(|l| delete || !l.task.archived)
                    .collect();
                let facts: Vec<(String, bool, bool, bool)> = mine
                    .iter()
                    .map(|l| {
                        (
                            l.task.title.clone(),
                            l.busy(),
                            false,
                            l.task.copy_branch.is_some(),
                        )
                    })
                    .collect();
                if let Some(why) = removal_blocked(&facts, delete) {
                    r.problem(&why);
                    return;
                }
                let ids: Vec<String> = mine.iter().map(|l| l.task.id.clone()).collect();
                for id in &ids {
                    if delete {
                        self.delete_task(id);
                    } else if let Ok(r) = &mut self.station {
                        r.set_archived(id, true);
                    }
                }
                if !delete && self.selected.as_ref().is_some_and(|s| ids.contains(s)) {
                    self.selected = None;
                    self.view = View::NewTask;
                }
                // Remote control for a project that is no longer listed stops
                // (the service follows the settings), and does not start
                // again next time.
                let s = &mut self.settings;
                s.remote_projects.retain(|x| x != &p);
                s.projects.retain(|x| !same(x, &p));
                s.collapsed_projects.retain(|x| x != &p);
                // Archived tasks keep the project's name and their order for
                // when one is unarchived.
                if delete {
                    s.task_order.remove(&p);
                    s.project_names.remove(&p);
                }
                if s.last_project.as_deref() == Some(p.as_str()) {
                    s.last_project = None;
                }
                if self.new_project.as_deref() == Some(p.as_str()) {
                    self.new_project = None;
                }
            }
        }
        self.settings_dirty = true;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(s: &[&str]) -> Vec<String> {
        s.iter().map(|x| x.to_string()).collect()
    }

    #[test]
    fn tasks_newest_first_then_ordered_with_pins_on_top() {
        let newest = v(&["e", "d", "c", "b", "a"]);
        assert_eq!(order_tasks(&newest, &[], &[]), newest);
        // Ordered ones after the new ones; gone ids are skipped.
        let order = v(&["b", "x", "c", "a"]);
        assert_eq!(
            order_tasks(&newest, &order, &[]),
            v(&["e", "d", "b", "c", "a"])
        );
        assert_eq!(
            order_tasks(&newest, &order, &v(&["a", "d"])),
            v(&["d", "a", "e", "b", "c"])
        );
    }

    #[test]
    fn removal_waits_for_busy_phone_and_copies() {
        let t = |busy, phone, copy| vec![("Fix".to_string(), busy, phone, copy)];
        assert!(removal_blocked(&t(false, false, false), true).is_none());
        assert!(
            removal_blocked(&t(true, false, false), false)
                .unwrap()
                .contains("working")
        );
        assert!(
            removal_blocked(&t(false, true, false), false)
                .unwrap()
                .contains("Your phone")
        );
        // A separate copy only stands in the way of deleting.
        assert!(removal_blocked(&t(false, false, true), false).is_none());
        assert!(
            removal_blocked(&t(false, false, true), true)
                .unwrap()
                .contains("copy")
        );
    }

    #[test]
    fn old_settings_still_load() {
        let s: Settings = serde_json::from_str(r#"{"projects":["/x"]}"#).unwrap();
        assert!(s.groups.is_empty() && s.pinned_tasks.is_empty() && s.hidden_sessions.is_empty());
    }
}
