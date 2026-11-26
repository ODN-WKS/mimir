//! Where things live: Claude Code, project folders, Mímir's own
//! files. Used by the service and the window alike.

use crate::settings::Settings;
use std::path::{Path, PathBuf};

pub fn home() -> PathBuf {
    PathBuf::from(std::env::var_os("HOME").unwrap_or_default())
}

pub fn config_dir() -> PathBuf {
    std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .unwrap_or_else(|| home().join(".config"))
        .join("mimir")
}

/// Claude Code as installed by Anthropic's installer, or on PATH.
/// `MIMIR_CLAUDE` points somewhere else (used for testing).
pub fn find_claude() -> PathBuf {
    if let Some(p) = std::env::var_os("MIMIR_CLAUDE") {
        return PathBuf::from(p);
    }
    let native = home().join(".local/bin/claude");
    if native.exists() {
        return native;
    }
    if let Some(paths) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&paths) {
            let p = dir.join("claude");
            if p.is_file() {
                return p;
            }
        }
    }
    native
}

/// This program, which Claude Code runs as the approvals hook. In debug
/// builds `MIMIR_TEST_PROGRAM` can name another: in the window
/// checks the running program is the test, not Mímir.
pub fn own_program() -> PathBuf {
    if cfg!(debug_assertions)
        && let Some(p) = std::env::var_os("MIMIR_TEST_PROGRAM")
    {
        return PathBuf::from(p);
    }
    std::env::current_exe().unwrap_or_else(|_| PathBuf::from("mimir"))
}

/// Where the settings are kept.
pub fn settings_path() -> PathBuf {
    config_dir().join("settings.json")
}

/// Two spellings of the same project folder.
pub fn same(a: &str, b: &str) -> bool {
    a == b || clean_path(a) == clean_path(b)
}

/// One spelling per folder: the real path when the folder is there (no
/// trailing slash, links resolved), else the path without a trailing slash.
/// Remembered, as the sidebar asks for it many times a frame.
pub fn clean_path(p: &str) -> String {
    use std::collections::HashMap;
    use std::sync::{Mutex, OnceLock};
    static SEEN: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
    let seen = SEEN.get_or_init(|| Mutex::new(HashMap::new()));
    if let Some(c) = seen.lock().unwrap().get(p) {
        return c.clone();
    }
    let c = match std::fs::canonicalize(p) {
        Ok(c) => c.display().to_string(),
        Err(_) => {
            let t = p.trim_end_matches('/');
            if t.is_empty() && p.starts_with('/') {
                "/".to_string()
            } else {
                t.to_string()
            }
        }
    };
    // Only folders that are there are remembered: a missing one may appear.
    if Path::new(&c).is_dir() {
        seen.lock().unwrap().insert(p.to_string(), c.clone());
    }
    c
}

/// Short name of a project folder for the window.
/// A typed path with a leading `~` turned into the home folder.
pub fn expand_home(p: &str) -> PathBuf {
    match p.strip_prefix('~') {
        Some(rest) if rest.is_empty() || rest.starts_with('/') => {
            home().join(rest.trim_start_matches('/'))
        }
        _ => PathBuf::from(p),
    }
}

/// The folder general chats (not about one project) run in.
pub fn chats_dir() -> PathBuf {
    let base = std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join(".local/share"));
    base.join("mimir/chats")
}

/// Mímir's data folder: tasks, pastes, voice, checkpoints, copies,
/// claude.ai's window and chats.
pub fn data_dir() -> PathBuf {
    let tasks = crate::store::Store::default_dir();
    tasks.parent().map(Path::to_path_buf).unwrap_or(tasks)
}

/// Where "Save conversation" puts its files.
pub fn saved_conversations_dir() -> PathBuf {
    home().join("Documents").join("Mimir")
}

/// Every project folder: saved ones in the owner's order, plus any an open
/// (not archived) task uses.
pub fn all_projects<'a>(
    settings: &Settings,
    tasks: impl IntoIterator<Item = &'a crate::task::Task>,
) -> Vec<String> {
    let mut all = settings.projects.clone();
    // Owner's hard rule (2026-11-26): projects are only ones the owner makes. A
    // session Mímir found running elsewhere (a helper, a script) never adds one.
    for t in tasks
        .into_iter()
        .filter(|t| !t.archived && !t.imported && !t.outside)
    {
        let home = t.home_project().to_string();
        if !all.iter().any(|p| same(p, &home)) && !helper_copy(&home) {
            all.push(home);
        }
    }
    all
}

/// A folder that is not a project of its own (owner-found 2026-11-25:
/// helper copies filled the phone's project list): gone, or a git worktree
/// (its .git a file). A task there
/// keeps working; the folder is just not listed as a project. A project the
/// owner saved is always listed.
pub fn helper_copy(p: &str) -> bool {
    let path = Path::new(p);
    if path == chats_dir() {
        return false;
    }
    !path.is_dir() || path.join(".git").is_file()
}

/// The one project list the service shows phones (Status, To:) and routes
/// to: the PC's list, its open tasks' projects and git projects beside them
/// that aren't listed yet (Map-Maker in ~/Projects), with stand-ins
/// and doubles taken out (see [`tidy_projects`]). A git worktree (its .git
/// a file) is a copy, not a project.
pub fn project_list<'a>(
    settings: &Settings,
    tasks: impl IntoIterator<Item = &'a crate::task::Task>,
) -> Vec<String> {
    let mut all = all_projects(settings, tasks);
    let remotes: Vec<String> = all.iter().filter_map(|p| origin(p)).collect();
    for r in unlisted_repos(&settings.projects) {
        // An old clone of a listed project (Garden-Planner-0.11) is a copy.
        if Path::new(&r).join(".git").is_dir()
            && !all.iter().any(|p| same(p, &r))
            && origin(&r).is_none_or(|o| !remotes.contains(&o))
        {
            all.push(r);
        }
    }
    tidy_projects(all)
}

/// A git project's "origin" address, read from its .git/config, without
/// a trailing ".git" or slash.
fn origin(p: &str) -> Option<String> {
    let config = std::fs::read_to_string(Path::new(p).join(".git/config")).ok()?;
    let mut in_origin = false;
    for l in config.lines() {
        let l = l.trim();
        if l.starts_with('[') {
            in_origin = l == "[remote \"origin\"]";
        } else if in_origin
            && let Some((k, v)) = l.split_once('=')
            && k.trim() == "url"
        {
            let v = v.trim().trim_end_matches('/');
            return Some(v.strip_suffix(".git").unwrap_or(v).to_lowercase());
        }
    }
    None
}

/// Takes out a folder listed twice (spelled two ways), and a stand-in: a
/// folder with nothing in it but hidden files (or gone) that has the same
/// name as a real project (an empty Documents/BudgetApp beside the real
/// Projects/Budgetapp). The chats folder always stays.
pub fn tidy_projects(all: Vec<String>) -> Vec<String> {
    let stand_in = |p: &str| {
        Path::new(p) != chats_dir()
            && std::fs::read_dir(p).map_or(true, |mut d| {
                d.all(|e| e.is_ok_and(|e| e.file_name().to_string_lossy().starts_with('.')))
            })
    };
    let name = |p: &str| project_name(p).to_lowercase();
    let mut out: Vec<String> = vec![];
    for p in &all {
        if out.iter().any(|q| same(q, p)) {
            continue;
        }
        if stand_in(p)
            && all
                .iter()
                .any(|q| !same(q, p) && name(q) == name(p) && !stand_in(q))
        {
            continue;
        }
        out.push(p.clone());
    }
    out
}

/// The project in `list` a task's folder belongs to: the folder itself, or
/// the real project a stand-in of the same name was taken out for.
pub fn project_in<'a>(list: &'a [String], folder: &str) -> Option<&'a str> {
    list.iter()
        .find(|p| same(p, folder))
        .or_else(|| {
            let n = project_name(folder).to_lowercase();
            list.iter().find(|p| project_name(p).to_lowercase() == n)
        })
        .map(String::as_str)
}

/// Each project's name for a list: its shown name, with its parent folder
/// after it when two have the same name ("Notes (Work)", "Notes (Home)").
pub fn list_names(settings: &Settings, list: &[String]) -> Vec<String> {
    let names: Vec<String> = list.iter().map(|p| display_name(settings, p)).collect();
    names
        .iter()
        .zip(list)
        .map(|(n, p)| {
            let clash = names
                .iter()
                .filter(|m| m.to_lowercase() == n.to_lowercase())
                .count()
                > 1;
            match Path::new(p).parent().and_then(Path::file_name) {
                Some(parent) if clash => format!("{n} ({})", parent.to_string_lossy()),
                _ => n.clone(),
            }
        })
        .collect()
}

/// Every place Mímir reads or writes, named, and the Claude Code
/// it runs ("Project work program", last). Window checks stop unless all of it
/// is inside their test folder and the program is the fake one. Reads the
/// settings and tasks; changes nothing.
pub fn paths_in_use() -> Vec<(String, PathBuf)> {
    let data = data_dir();
    let tasks = crate::store::Store::default_dir();
    let layout = crate::install::Layout::for_user();
    let mut v: Vec<(String, PathBuf)> = vec![
        ("task store".into(), tasks.clone()),
        ("settings".into(), settings_path()),
        ("window preferences".into(), crate::settings::window_path()),
        ("service folder".into(), crate::sockets::runtime_dir()),
        ("control socket".into(), crate::sockets::control_socket()),
        ("session socket".into(), crate::sockets::session_socket()),
        ("service lock".into(), crate::sockets::lock_path()),
        ("log".into(), crate::log::log_path()),
        (
            "approvals socket".into(),
            crate::approvals::default_socket(),
        ),
        ("Session folder".into(), crate::sessions::claude_home()),
        ("older self-installed program".into(), layout.bin()),
        ("installed data folder".into(), layout.data_dir()),
        ("installed settings folder".into(), layout.config_dir()),
        ("data folder".into(), data.clone()),
        ("chats".into(), chats_dir()),
        ("pastes".into(), data.join("pastes")),
        ("voice".into(), crate::voice::root(&data)),
        ("checkpoints".into(), data.join("checkpoints")),
        ("separate copies".into(), data.join("copies")),
        ("old web window".into(), data.join("claude-ai-web")),
        ("saved conversations".into(), saved_conversations_dir()),
        (
            "phone link keys and paired phones".into(),
            config_dir().join("link"),
        ),
    ];
    let settings = Settings::read().unwrap_or_default();
    let saved: Vec<crate::task::Task> = if tasks.is_dir() {
        crate::store::Store::new(&tasks)
            .map(|s| s.load_all().0)
            .unwrap_or_default()
    } else {
        vec![]
    };
    let mut projects = all_projects(&settings, &saved);
    projects.extend(settings.remote_projects.iter().cloned());
    projects.extend(settings.last_project.iter().cloned());
    for t in &saved {
        projects.push(t.project.clone());
        projects.extend(t.origin.iter().cloned());
    }
    projects.sort();
    projects.dedup();
    v.extend(
        projects
            .into_iter()
            .map(|p| ("project folder".to_string(), PathBuf::from(p))),
    );
    v.push(("Project work program".into(), find_claude()));
    v
}

/// A project's name as the owner sees it: the one given in Mímir,
/// else the folder's.
pub fn display_name(settings: &Settings, p: &str) -> String {
    settings
        .project_names
        .get(p)
        .or_else(|| settings.project_names.get(&clean_path(p)))
        .map(|n| n.trim())
        .filter(|n| !n.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| project_name(p))
}

pub fn project_name(p: &str) -> String {
    if Path::new(p) == chats_dir() {
        return "Chats".to_string();
    }
    Path::new(p)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| p.to_string())
}

/// Whether a program can be found on PATH.
pub fn have(program: &str) -> bool {
    std::env::var_os("PATH")
        .is_some_and(|paths| std::env::split_paths(&paths).any(|d| d.join(program).is_file()))
}

/// A new project's name is a plain folder name: letters, digits, spaces and
/// `-_.`, not starting with a dot, at most 64 characters. Why not, if not.
pub fn plain_project_name(name: &str) -> Result<String, String> {
    let n = name.trim();
    if n.is_empty() {
        return Err("Give the project a name.".into());
    }
    if n.chars().count() > 64 {
        return Err("That name is too long (64 characters at most).".into());
    }
    if n.starts_with('.') || n.contains("..") {
        return Err("A project name can't start with a dot or contain \"..\".".into());
    }
    if !n.chars().all(|c| c.is_alphanumeric() || " -_.".contains(c)) {
        return Err("Use letters, digits, spaces and - _ . only (no / or other marks).".into());
    }
    Ok(n.to_string())
}

/// Where new projects go: the folder most of the owner's projects live in
/// (not the home folder itself, nor the chats folder), else ~/Projects.
pub fn projects_parent(projects: &[String]) -> PathBuf {
    let home = home();
    let mut counts: Vec<(PathBuf, usize)> = vec![];
    for p in projects {
        let p = Path::new(p);
        if p == chats_dir() {
            continue;
        }
        let Some(parent) = p.parent() else { continue };
        if parent == home || !parent.starts_with(&home) {
            continue;
        }
        match counts.iter_mut().find(|(d, _)| d == parent) {
            Some((_, n)) => *n += 1,
            None => counts.push((parent.to_path_buf(), 1)),
        }
    }
    // Most used first; on a tie, the one listed first.
    counts
        .into_iter()
        .rev()
        .max_by_key(|(_, n)| *n)
        .map(|(d, _)| d)
        .unwrap_or_else(|| home.join("Projects"))
}

/// Folders beside the owner's projects (under [projects_parent]) that are
/// git projects but not on the list yet, by name.
pub fn unlisted_repos(projects: &[String]) -> Vec<String> {
    let parent = projects_parent(projects);
    let mut found: Vec<String> = std::fs::read_dir(&parent)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            p.is_dir()
                && p.join(".git").exists()
                && !p
                    .file_name()
                    .is_some_and(|n| n.to_string_lossy().starts_with('.'))
        })
        .map(|p| clean_path(&p.display().to_string()))
        .filter(|p| !projects.iter().any(|q| same(q, p)))
        .collect();
    found.sort_by_key(|p| project_name(p).to_lowercase());
    found
}

/// Makes (or finds) the project folder `name` under [projects_parent].
/// Returns the folder and whether it was made now. A file of that name is
/// never replaced.
pub fn make_project_folder(projects: &[String], name: &str) -> Result<(PathBuf, bool), String> {
    let name = plain_project_name(name)?;
    // A project of that name already on the list, wherever its folder is, is
    // the one meant: no second folder (and no second row) of the same name.
    if let Some(same) = projects.iter().map(Path::new).find(|p| {
        p.is_dir()
            && p.file_name()
                .is_some_and(|n| n.to_string_lossy().to_lowercase() == name.to_lowercase())
    }) {
        return Ok((same.to_path_buf(), false));
    }
    let dir = projects_parent(projects).join(&name);
    if dir.is_dir() {
        return Ok((dir, false));
    }
    if dir.exists() {
        return Err(format!(
            "{} is already there and isn't a folder.",
            dir.display()
        ));
    }
    std::fs::create_dir_all(&dir).map_err(|e| format!("Couldn't make {}: {e}", dir.display()))?;
    Ok((dir, true))
}

#[cfg(test)]
mod new_project_tests {
    use super::*;

    #[test]
    fn helper_copies_are_not_listed_as_projects() {
        // Owner-found 2026-11-25: helper worktrees (gone or still there) showed as projects on the phone.
        let root = std::env::temp_dir().join(format!("hc-{}", std::process::id()));
        let real = root.join("Garden");
        std::fs::create_dir_all(real.join(".git")).unwrap();
        let copy = root.join("wt-p12");
        std::fs::create_dir_all(&copy).unwrap();
        std::fs::write(copy.join(".git"), "gitdir: elsewhere").unwrap();
        let gone = root.join("wt-p13");
        let plain = root.join("Notes");
        std::fs::create_dir_all(&plain).unwrap();
        let s = |p: &Path| p.display().to_string();
        assert!(!helper_copy(&s(&real)) && !helper_copy(&s(&plain)));
        assert!(helper_copy(&s(&copy)) && helper_copy(&s(&gone)));
        let tasks: Vec<crate::task::Task> = [&real, &copy, &gone, &plain]
            .iter()
            .enumerate()
            .map(|(i, p)| crate::task::Task::new(format!("t{i}"), "x".into(), s(p), 0))
            .collect();
        assert_eq!(
            all_projects(&Settings::default(), &tasks),
            vec![s(&real), s(&plain)]
        );
        // Owner's hard rule (2026-11-26): a session found running elsewhere never adds a project.
        let mut found = crate::task::Task::new("t9".into(), "x".into(), s(&plain), 0);
        found.imported = true;
        found.outside = true;
        assert!(all_projects(&Settings::default(), [&found]).is_empty());
        // A copy the owner saved on purpose stays.
        let saved = Settings {
            projects: vec![s(&copy)],
            ..Settings::default()
        };
        assert!(all_projects(&saved, &tasks).contains(&s(&copy)));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn only_plain_names_are_taken() {
        assert_eq!(plain_project_name("  My App ").unwrap(), "My App");
        assert!(plain_project_name("").is_err());
        assert!(plain_project_name("../etc").is_err());
        assert!(plain_project_name("a/b").is_err());
        assert!(plain_project_name(".hidden").is_err());
        assert!(plain_project_name("a..b").is_err());
        assert!(plain_project_name(&"x".repeat(65)).is_err());
        assert!(plain_project_name("Budget-App_2.0").is_ok());
    }

    #[test]
    fn new_projects_go_where_most_projects_are() {
        let h = home();
        let p = |s: &str| h.join(s).display().to_string();
        let list = vec![p("Projects/A"), p("Projects/B"), p("Downloads/C"), p("D")];
        assert_eq!(projects_parent(&list), h.join("Projects"));
        assert_eq!(projects_parent(&[]), h.join("Projects"));
        assert_eq!(projects_parent(&[p("Code/One")]), h.join("Code"));
    }

    #[test]
    fn a_folder_is_made_once_and_then_just_chosen() {
        let root = std::env::temp_dir().join(format!("np-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("Work/Old")).unwrap();
        // The parent is found from the listed projects (under the home folder).
        let old_home = std::env::var_os("HOME");
        unsafe { std::env::set_var("HOME", &root) };
        let list = vec![root.join("Work/Old").display().to_string()];
        let (dir, made) = make_project_folder(&list, "Fresh").unwrap();
        assert_eq!(dir, root.join("Work/Fresh"));
        assert!(made && dir.is_dir());
        let (again, made_again) = make_project_folder(&list, "Fresh").unwrap();
        assert_eq!(again, dir);
        assert!(!made_again);
        // A project of that name kept somewhere else is picked, not doubled.
        std::fs::create_dir_all(root.join("Elsewhere/Notes")).unwrap();
        let list2 = vec![
            root.join("Work/Old").display().to_string(),
            root.join("Work/Fresh").display().to_string(),
            root.join("Elsewhere/Notes").display().to_string(),
        ];
        let (found, made_found) = make_project_folder(&list2, "notes").unwrap();
        assert_eq!(found, root.join("Elsewhere/Notes"));
        assert!(!made_found && !root.join("Work/notes").exists());
        std::fs::write(root.join("Work/AFile"), b"x").unwrap();
        assert!(make_project_folder(&list, "AFile").is_err());
        match old_home {
            Some(h) => unsafe { std::env::set_var("HOME", h) },
            None => unsafe { std::env::remove_var("HOME") },
        }
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn the_project_list_has_no_stand_ins_or_doubles() {
        let root = std::env::temp_dir().join(format!("pl-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let d = |s: &str| root.join(s).display().to_string();
        // The real projects, with files in them.
        for real in [
            "Projects/Budgetapp",
            "Projects/Tune-Sorter",
            "Work/Notes",
            "Home/Notes",
        ] {
            std::fs::create_dir_all(root.join(real).join(".git")).unwrap();
            std::fs::write(root.join(real).join("README.md"), "# x").unwrap();
        }
        // Stand-ins: empty but for hidden files, named like a real one.
        std::fs::create_dir_all(root.join("Documents/BudgetApp")).unwrap();
        std::fs::write(root.join("Documents/BudgetApp/.directory"), "").unwrap();
        std::fs::create_dir_all(root.join("Downloads/Tune-Sorter/.claude")).unwrap();
        // An empty folder with a name of its own stays.
        std::fs::create_dir_all(root.join("Documents/S8 unlock")).unwrap();
        let list = tidy_projects(vec![
            d("Documents/BudgetApp"),
            d("Downloads/Tune-Sorter"),
            d("Documents/S8 unlock"),
            d("Projects/Budgetapp"),
            d("Projects/Tune-Sorter"),
            format!("{}/", d("Projects/Budgetapp")),
            d("Work/Notes"),
            d("Home/Notes"),
        ]);
        assert_eq!(
            list,
            [
                d("Documents/S8 unlock"),
                d("Projects/Budgetapp"),
                d("Projects/Tune-Sorter"),
                d("Work/Notes"),
                d("Home/Notes"),
            ]
        );
        // A task in a stand-in counts under the real project.
        assert_eq!(
            project_in(&list, &d("Documents/BudgetApp")),
            Some(d("Projects/Budgetapp").as_str())
        );
        // Two real projects of one name are told apart by their parent.
        let names = list_names(&Settings::default(), &list);
        assert_eq!(names[3], "Notes (Work)");
        assert_eq!(names[4], "Notes (Home)");
        assert_eq!(names[1], "Budgetapp");
        let _ = std::fs::remove_dir_all(&root);
    }
}
