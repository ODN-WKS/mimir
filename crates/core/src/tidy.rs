//! "Tidy projects…": a one-time look at every conversation and project
//! folder, proposing where each belongs. Nothing changes until the owner
//! has seen every change and said so.
//!
//! The rules, folder by folder:
//! - a folder outside the projects folder named like a project in it (a
//!   stand-in such as `~/Documents/BudgetApp` for `~/Projects/Budgetapp`):
//!   its conversations go to that project, and it leaves the list;
//! - the home folder: its conversations go to General, archived;
//! - the chats folder: each conversation goes where what it asks points,
//!   else to General;
//! - any other folder that isn't a project (`~/Documents`, a folder with no
//!   git): its conversations go to General, and it leaves the list;
//! - a project: a conversation that clearly belongs to another project goes
//!   there, and a short one that fits no project goes to General.
//!
//! Everywhere, a test chat (empty, "test", or one short message) is just
//! archived. Git projects beside the others that aren't listed are added.

use crate::route::{Placement, ProjectWords, place, scores};
use mimir_proto::{TidyKind, TidyRow};
use std::path::Path;

/// A conversation as the tidy sees it.
#[derive(Debug, Clone, Default)]
pub struct Conversation {
    pub task: Option<String>,
    pub session: Option<String>,
    pub folder: String,
    pub title: String,
    /// What the owner wrote in it.
    pub text: String,
    /// How many messages the owner sent.
    pub messages: usize,
    pub files_changed: usize,
    /// Claude is still on it.
    pub busy: bool,
}

/// What the tidy knows about the PC.
pub struct World<'a> {
    pub home: &'a Path,
    pub chats: &'a Path,
    /// Where the owner's projects live (`~/Projects`).
    pub parent: &'a Path,
    /// The listed project folders.
    pub listed: &'a [String],
    /// Git projects in `parent`, listed or not.
    pub repos: &'a [String],
    /// Each project as routing sees it.
    pub words: &'a [ProjectWords],
    /// General's folder (made when the tidy is done, if it isn't there).
    pub random: &'a str,
}

/// A chat that was only a try: empty, "test" or "hello", or one short
/// message.
pub fn test_like(c: &Conversation) -> bool {
    let t = c.text.trim().to_lowercase();
    let t = t.trim_end_matches(['.', '!', '?']);
    c.messages == 0
        || t.is_empty()
        || ["test", "testing", "hello", "hi"].contains(&t)
        || (c.messages == 1 && c.files_changed == 0 && t.chars().count() < 60)
}

fn squash(s: &str) -> String {
    s.chars()
        .filter(|c| c.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

fn name(p: &str) -> String {
    crate::paths::project_name(p)
}

/// The changes a tidy proposes, folders first.
pub fn plan(conversations: &[Conversation], w: &World) -> Vec<TidyRow> {
    use crate::paths::same;
    let row = |kind, folder: &str| TidyRow {
        kind,
        folder: folder.to_string(),
        task: None,
        session: None,
        title: String::new(),
        to: None,
        archive: false,
        why: String::new(),
        waits: false,
        on: true,
    };
    let mut out = vec![];
    for r in w.repos {
        if !w.listed.iter().any(|p| same(p, r)) {
            let mut a = row(TidyKind::Add, r);
            a.to = Some(r.clone());
            a.why = "A git project beside the others, not on the list yet.".into();
            out.push(a);
        }
    }
    // Every folder with conversations or on the list.
    let mut folders: Vec<String> = w.listed.to_vec();
    for c in conversations {
        if !folders.iter().any(|f| same(f, &c.folder)) {
            folders.push(c.folder.clone());
        }
    }
    let mut convs = vec![];
    for f in &folders {
        let path = Path::new(f);
        let mine: Vec<&Conversation> = conversations
            .iter()
            .filter(|c| same(&c.folder, f))
            .collect();
        let under = path.starts_with(w.parent) && path != w.parent;
        let stand_in = (!under)
            .then(|| {
                w.repos
                    .iter()
                    .find(|r| squash(&name(r)) == squash(&name(f)))
            })
            .flatten();
        let kind = if path == w.chats {
            Kind::Chats
        } else if path == w.home {
            Kind::Home
        } else if let Some(r) = stand_in {
            Kind::StandIn(r.clone())
        } else if under || path.join(".git").exists() {
            Kind::Project
        } else {
            Kind::Other
        };
        match &kind {
            Kind::StandIn(r) => {
                let mut m = row(TidyKind::Merge, f);
                m.to = Some(r.clone());
                m.why = format!("A stand-in for {}: it leaves the list.", name(r));
                out.push(m);
            }
            Kind::Home | Kind::Chats | Kind::Other if w.listed.iter().any(|p| same(p, f)) => {
                let mut u = row(TidyKind::Unlist, f);
                u.why =
                    "Not a project: it leaves the list. Archived tasks stay under Archived.".into();
                out.push(u);
            }
            _ => {}
        }
        for c in mine {
            if let Some(r) = decide(c, &kind, w) {
                convs.push(r);
            }
        }
    }
    out.extend(convs);
    out
}

enum Kind {
    Chats,
    Home,
    StandIn(String),
    Project,
    Other,
}

/// Where one conversation goes, if anywhere.
fn decide(c: &Conversation, kind: &Kind, w: &World) -> Option<TidyRow> {
    let row = |to: Option<String>, archive: bool, why: String| TidyRow {
        kind: TidyKind::Conversation,
        folder: c.folder.clone(),
        task: c.task.clone(),
        session: c.session.clone(),
        title: c.title.clone(),
        to,
        archive,
        why,
        waits: c.busy,
        on: true,
    };
    let random = || Some(w.random.to_string());
    // The projects' words without this conversation's own title.
    let words: Vec<ProjectWords> = w
        .words
        .iter()
        .map(|p| ProjectWords {
            words: p.words.iter().filter(|t| **t != c.title).cloned().collect(),
            ..p.clone()
        })
        .collect();
    let test = test_like(c);
    match kind {
        Kind::Home => Some(row(
            random(),
            true,
            "Started in the home folder, which is no project.".into(),
        )),
        Kind::StandIn(r) => Some(row(
            Some(r.clone()),
            test,
            format!("Its folder is a stand-in for {}.", name(r)),
        )),
        _ if test => Some(row(None, true, "A test chat.".into())),
        Kind::Chats => Some(match place(&c.text, &words) {
            Placement::Sure(p) => row(Some(p.clone()), false, format!("It's about {}.", name(&p))),
            Placement::Unsure { project, why } => row(Some(project), false, why),
            Placement::None => row(random(), false, "It fits no project.".into()),
        }),
        Kind::Other => Some(row(random(), false, "Its folder is no project.".into())),
        Kind::Project => {
            let own = scores(&c.text, &words)
                .into_iter()
                .find(|(p, _)| crate::paths::same(p, &c.folder))
                .map_or(0, |(_, s)| s);
            if own > 0 {
                return None;
            }
            match place(&c.text, &words) {
                Placement::Sure(p) if !crate::paths::same(&p, &c.folder) => Some(row(
                    Some(p.clone()),
                    false,
                    format!("It's about {}, not {}.", name(&p), name(&c.folder)),
                )),
                Placement::None if c.messages <= 3 && c.files_changed == 0 => Some(row(
                    random(),
                    false,
                    format!("A short chat that isn't about {}.", name(&c.folder)),
                )),
                _ => None,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn conv(folder: &str, title: &str, text: &str, messages: usize) -> Conversation {
        Conversation {
            task: Some(format!("t-{title}")),
            folder: folder.into(),
            title: title.into(),
            text: text.into(),
            messages,
            ..Default::default()
        }
    }

    fn words(path: &str, names: &[&str], titles: &[&str]) -> ProjectWords {
        ProjectWords {
            path: path.into(),
            names: names.iter().map(|s| s.to_string()).collect(),
            words: titles.iter().map(|s| s.to_string()).collect(),
        }
    }

    /// The owner's own tidy, as the brief lists it.
    #[test]
    fn the_owners_folders_tidy_as_expected() {
        let h = "/h";
        let listed: Vec<String> = [
            "/h/Projects/Garden-Planner",
            "/h/Documents/BudgetApp",
            "/h/Documents/S8 unlock",
            "/h/Projects/Recipe-Box",
            "/h",
            "/h/Downloads/Tune-Sorter",
            "/h/.local/share/mimir/chats",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        let repos: Vec<String> = [
            "/h/Projects/NotesApp",
            "/h/Projects/Garden-Planner",
            "/h/Projects/Map-Maker",
            "/h/Projects/Budgetapp",
            "/h/Projects/Tune-Sorter",
            "/h/Projects/General",
            "/h/Projects/Recipe-Box",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        let pw = vec![
            words(
                "/h/Projects/Garden-Planner",
                &["Garden-Planner", "Station"],
                &["Garden Planner", "Make Allow respond faster"],
            ),
            words(
                "/h/Projects/Recipe-Box",
                &["Recipe-Box"],
                &["Recipe Box", "Reset game files"],
            ),
            words(
                "/h/Projects/Tune-Sorter",
                &["Tune-Sorter"],
                &["Tune Sorter", "Video calls"],
            ),
            words("/h/Projects/Budgetapp", &["Budgetapp"], &["Budgetapp"]),
        ];
        let chats = "/h/.local/share/mimir/chats";
        let cs = "/h/Projects/Garden-Planner";
        let conversations = vec![
            conv(
                "/h/Documents/BudgetApp",
                "Take over",
                "take over the handoff",
                109,
            ),
            conv(
                "/h/Downloads/Tune-Sorter",
                "Take control",
                "take control",
                69,
            ),
            conv(
                "/h/Documents/S8 unlock",
                "Hello",
                "hello there, about the phone",
                105,
            ),
            conv(
                h,
                "SteamOS dev machine setup",
                "This is a fresh SteamOS install",
                3,
            ),
            conv(
                chats,
                "Tune 2",
                "Continue my claude.ai chat \"Tune 2\". It is saved in the file",
                57,
            ),
            conv(
                chats,
                "Monitor OC",
                "Continue my claude.ai chat \"Monitor OC app scaling issue\". It is saved",
                1,
            ),
            conv(
                "/h/Documents",
                "Trains",
                "Create notes.txt with three lines about trains",
                1,
            ),
            Conversation {
                busy: true,
                ..conv(
                    "/h/Projects/Recipe-Box",
                    "Station work",
                    "Garden Planner: the read aloud should work in Garden Planner",
                    35,
                )
            },
            conv(cs, "Test", "Test", 3),
            conv(cs, "New task", "", 0),
            conv(cs, "Force restart my pc", "Force restart my pc", 1),
            conv(
                cs,
                "TL;DR",
                "Give me the tldr of this video https://youtu.be/OUbEeJOxjyw and the key points",
                2,
            ),
            conv(cs, "Yes, it opened", "Yes, it opened. Now the pairing", 77),
        ];
        let w = World {
            home: Path::new(h),
            chats: Path::new(chats),
            parent: Path::new("/h/Projects"),
            listed: &listed,
            repos: &repos,
            words: &pw,
            random: "/h/Projects/General",
        };
        let rows = plan(&conversations, &w);
        let added: Vec<&str> = rows
            .iter()
            .filter(|r| r.kind == TidyKind::Add)
            .map(|r| r.folder.as_str())
            .collect();
        assert_eq!(
            added,
            [
                "/h/Projects/NotesApp",
                "/h/Projects/Map-Maker",
                "/h/Projects/Budgetapp",
                "/h/Projects/Tune-Sorter",
                "/h/Projects/General"
            ]
        );
        let folder = |f: &str| {
            rows.iter()
                .find(|r| r.kind != TidyKind::Conversation && r.folder == f)
        };
        assert_eq!(
            folder("/h/Documents/BudgetApp").map(|r| (r.kind, r.to.as_deref())),
            Some((TidyKind::Merge, Some("/h/Projects/Budgetapp")))
        );
        assert_eq!(
            folder("/h/Downloads/Tune-Sorter").map(|r| r.kind),
            Some(TidyKind::Merge)
        );
        for f in ["/h/Documents/S8 unlock", h, chats] {
            assert_eq!(folder(f).map(|r| r.kind), Some(TidyKind::Unlist), "{f}");
        }
        let conv = |t: &str| {
            rows.iter()
                .find(|r| r.title == t)
                .map(|r| (r.to.as_deref(), r.archive))
        };
        assert_eq!(
            conv("Take over"),
            Some((Some("/h/Projects/Budgetapp"), false))
        );
        assert_eq!(
            conv("Take control"),
            Some((Some("/h/Projects/Tune-Sorter"), false))
        );
        assert_eq!(conv("Hello"), Some((Some("/h/Projects/General"), false)));
        assert_eq!(
            conv("SteamOS dev machine setup"),
            Some((Some("/h/Projects/General"), true))
        );
        assert_eq!(
            conv("Tune 2"),
            Some((Some("/h/Projects/Tune-Sorter"), false))
        );
        assert_eq!(
            conv("Monitor OC"),
            Some((Some("/h/Projects/General"), false))
        );
        assert_eq!(conv("Trains"), Some((None, true)));
        assert_eq!(conv("Station work"), Some((Some(cs), false)));
        assert!(
            rows.iter()
                .find(|r| r.title == "Station work")
                .unwrap()
                .waits
        );
        assert_eq!(conv("Test"), Some((None, true)));
        assert_eq!(conv("New task"), Some((None, true)));
        assert_eq!(conv("Force restart my pc"), Some((None, true)));
        assert_eq!(conv("TL;DR"), Some((Some("/h/Projects/General"), false)));
        assert_eq!(conv("Yes, it opened"), None);
    }
}
