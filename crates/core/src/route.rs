//! Files a message from the phone's general chat without asking Claude:
//! what kind of message it is (a status question, an idea, a request) and
//! which project it's about. The rules and their starting values are in
//! the phone app design ("Routing the general chat", "Routing constants").

/// A project as routing sees it.
#[derive(Debug, Clone, Default)]
pub struct ProjectWords {
    /// The project folder.
    pub path: String,
    /// Its folder name, the name the owner gave it, and its aliases.
    pub names: Vec<String>,
    /// Its README title and its last task titles.
    pub words: Vec<String>,
}

/// Each project folder as routing sees it (the one list the phone sees,
/// unlisted git projects beside the others included): its folder name,
/// the name the owner gave it and its aliases; the titles of its README.md and
/// CLAUDE.md; and its 10 newest task titles. The chats folder is left out.
pub fn project_words(
    settings: &crate::settings::Settings,
    tasks: &[&crate::task::Task],
) -> Vec<ProjectWords> {
    use std::path::Path;
    crate::paths::project_list(settings, tasks.iter().copied())
        .into_iter()
        .filter(|p| Path::new(p) != crate::paths::chats_dir())
        .map(|p| {
            let mut names = vec![crate::paths::project_name(&p)];
            let shown = crate::paths::display_name(settings, &p);
            if !names.contains(&shown) {
                names.push(shown);
            }
            names.extend(settings.aliases.get(&p).cloned().unwrap_or_default());
            let mut words: Vec<String> = ["README.md", "CLAUDE.md"]
                .iter()
                .filter_map(|f| {
                    std::fs::read_to_string(Path::new(&p).join(f))
                        .ok()?
                        .lines()
                        .find(|l| l.starts_with("# "))
                        .map(|l| l[2..].trim().to_string())
                })
                .collect();
            let mut titles: Vec<&crate::task::Task> = tasks
                .iter()
                .copied()
                .filter(|t| crate::paths::same(t.home_project(), &p))
                .collect();
            titles.sort_by_key(|t| std::cmp::Reverse(t.created_unix));
            words.extend(titles.into_iter().take(10).map(|t| t.title.clone()));
            ProjectWords {
                path: p,
                names,
                words,
            }
        })
        .collect()
}

/// Where a chat with no project goes, from what it asks.
#[derive(Debug, Clone, PartialEq)]
pub enum Placement {
    /// Clearly this project: it is named, or far ahead of the others.
    Sure(String),
    /// The best guess, with why in a few words.
    Unsure { project: String, why: String },
    /// No project fits: it goes to General.
    None,
}

/// Places a chat with no project: sure when a project is named (as a
/// prefix, or as a name in the text with a clear lead), a best guess when
/// at least two of its words point to one project, else none.
pub fn place(text: &str, projects: &[ProjectWords]) -> Placement {
    if let Some((p, _)) = prefix(text, projects) {
        return Placement::Sure(p);
    }
    let ranked = scores(text, projects);
    let (Some((p, top)), next) = (ranked.first(), ranked.get(1).map_or(0, |n| n.1)) else {
        return Placement::None;
    };
    if *top >= 3 && *top >= 2 * next {
        return Placement::Sure(p.clone());
    }
    if *top < 2 {
        return Placement::None;
    }
    let shared = matched(text, projects.iter().find(|w| &w.path == p));
    let mut why = format!("It mentions {}", quoted(&shared));
    if let Some((q, s)) = ranked.get(1).filter(|n| n.1 > 0) {
        let name = projects
            .iter()
            .find(|w| &w.path == q)
            .and_then(|w| w.names.first().cloned())
            .unwrap_or_default();
        why.push_str(&format!(
            ", but {name} fits nearly as well ({s} against {top})"
        ));
    }
    why.push('.');
    Placement::Unsure {
        project: p.clone(),
        why,
    }
}

/// The message's words that point to a project (its names, or words of its
/// titles).
fn matched(text: &str, p: Option<&ProjectWords>) -> Vec<String> {
    let Some(p) = p else { return vec![] };
    let flat = norm(text);
    let mut out: Vec<String> = p
        .names
        .iter()
        .filter(|n| norm(n).chars().count() >= 3 && flat.contains(&norm(n)))
        .cloned()
        .collect();
    let msg = words(text);
    if out.is_empty() {
        for w in p.names.iter().flat_map(|n| words(n)) {
            if w.chars().count() >= 4
                && !GENERIC.contains(&w.as_str())
                && msg.contains(&w)
                && !out.contains(&w)
            {
                out.push(w);
            }
        }
    }
    let theirs: Vec<String> = p.words.iter().flat_map(|w| words(w)).collect();
    for w in msg {
        if theirs.contains(&w) && !out.contains(&w) {
            out.push(w);
        }
    }
    out
}

fn quoted(words: &[String]) -> String {
    let q: Vec<String> = words
        .iter()
        .take(4)
        .map(|w| format!("\u{201c}{w}\u{201d}"))
        .collect();
    match q.len() {
        0 => "nothing in particular".into(),
        1 => q[0].clone(),
        n => format!("{} and {}", q[..n - 1].join(", "), q[n - 1]),
    }
}

/// The previous general-chat message, for "the same conversation".
#[derive(Debug, Clone)]
pub struct Previous {
    /// Seconds since 1970.
    pub time: i64,
    pub project: Option<String>,
    pub task: Option<String>,
}

/// What a message is.
#[derive(Debug, Clone, PartialEq)]
pub enum Kind {
    Status,
    Idea,
    Request,
}

/// How the project was picked.
#[derive(Debug, Clone, PartialEq)]
pub enum How {
    Prefix,
    SameConversation,
    Keywords,
    /// No project fits well enough: the message waits in the Inbox.
    Unclear,
    /// A status question about all projects.
    AllProjects,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Routed {
    pub kind: Kind,
    pub project: Option<String>,
    pub how: How,
    /// The message without the project prefix (and without "Idea:").
    pub text: String,
    /// The top-scoring projects, best first (for the Inbox's buttons).
    pub candidates: Vec<String>,
}

/// The status phrases (starting values).
const STATUS: [&str; 9] = [
    "status",
    "what's running",
    "what is running",
    "what needs me",
    "what's going on",
    "what's up",
    "how's it going",
    "anything for me",
    "what's waiting",
];

/// Words ignored when scoring (starting values).
const STOP: [&str; 7] = ["the", "and", "for", "with", "this", "that", "please"];

/// Words of project names too common to point to one project by
/// themselves ("Claude" of "Garden-Planner" is in every chat about Claude).
const GENERIC: [&str; 6] = ["claude", "code", "project", "manager", "chat", "test"];

/// "The same conversation" lasts this long after the previous message.
pub const SAME_CONVERSATION_S: i64 = 10 * 60;

/// Lower-cased words, split on anything that isn't a letter or digit, with
/// short and stop words left out and a trailing "s" removed.
pub fn words(text: &str) -> Vec<String> {
    text.to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| w.chars().count() > 2 && !STOP.contains(w))
        .map(|w| w.strip_suffix('s').unwrap_or(w).to_string())
        .filter(|w| w.chars().count() > 2)
        .collect()
}

fn norm(s: &str) -> String {
    s.to_lowercase()
        .chars()
        .filter(|c| c.is_alphanumeric())
        .collect()
}

/// A leading "Name: …" or "@Name …" naming a project, by a name or alias.
fn prefix<'a>(text: &'a str, projects: &[ProjectWords]) -> Option<(String, &'a str)> {
    let t = text.trim_start();
    let (head, rest) = if let Some(after) = t.strip_prefix('@') {
        match after.find(char::is_whitespace) {
            Some(i) => (&after[..i], &after[i..]),
            None => (after, ""),
        }
    } else {
        let i = t.find(':')?;
        // A prefix is a name, not a sentence.
        if t[..i].split_whitespace().count() > 3 {
            return None;
        }
        (&t[..i], &t[i + 1..])
    };
    let h = norm(head);
    if h.is_empty() {
        return None;
    }
    projects
        .iter()
        .find(|p| p.names.iter().any(|n| norm(n) == h))
        .map(|p| (p.path.clone(), rest.trim_start()))
}

fn is_status(text: &str) -> bool {
    let t: String = text
        .to_lowercase()
        .replace('’', "'")
        .trim()
        .trim_end_matches(['?', '!', '.'])
        .to_string();
    STATUS.iter().any(|s| {
        t == *s
            || t.starts_with(&format!("{s} "))
            || t.ends_with(&format!(" {s}"))
            || t.contains(&format!(" {s} "))
    })
}

/// "Idea:" or "idea" in the first 3 words; returns the text without the
/// leading "Idea:".
fn idea(text: &str) -> Option<String> {
    let first: Vec<String> = text
        .split_whitespace()
        .take(3)
        .map(|w| {
            w.to_lowercase()
                .trim_matches(|c: char| !c.is_alphanumeric())
                .to_string()
        })
        .collect();
    if !first.iter().any(|w| w == "idea") {
        return None;
    }
    let t = text.trim_start();
    let lower = t.to_lowercase();
    let rest = if lower.starts_with("idea:") {
        t[5..].trim_start()
    } else if lower.starts_with("idea -") || lower.starts_with("idea –") {
        t[6..].trim_start()
    } else {
        t
    };
    Some(rest.to_string())
}

/// Scores each project: 3 for each of its names in the message (else 2 for
/// a long word of one), 1 for each other word that is also in its README
/// title or recent task titles.
pub fn scores(text: &str, projects: &[ProjectWords]) -> Vec<(String, u32)> {
    let msg = words(text);
    let flat = norm(text);
    let mut out: Vec<(String, u32)> = projects
        .iter()
        .map(|p| {
            let mut s = 0;
            let mut name_words: Vec<String> = vec![];
            let mut counted_names: Vec<String> = vec![];
            for n in &p.names {
                let nn = norm(n);
                // A name counts once, however it's written.
                if nn.chars().count() >= 3 && flat.contains(&nn) && !counted_names.contains(&nn) {
                    s += 3;
                    counted_names.push(nn);
                }
                name_words.extend(words(n));
            }
            // Part of a two-word name ("Photo" of "Tune-Sorter") counts
            // 2, when no whole name did.
            if counted_names.is_empty() {
                let part = name_words
                    .iter()
                    .filter(|w| w.chars().count() >= 4 && !GENERIC.contains(&w.as_str()))
                    .find(|w| msg.contains(w));
                if part.is_some() {
                    s += 2;
                }
            }
            let theirs: Vec<String> = p.words.iter().flat_map(|w| words(w)).collect();
            let mut counted: Vec<&String> = vec![];
            for w in &msg {
                if name_words.contains(w) || counted.contains(&w) {
                    continue;
                }
                if theirs.contains(w) {
                    s += 1;
                    counted.push(w);
                }
            }
            (p.path.clone(), s)
        })
        .collect();
    out.sort_by_key(|p| std::cmp::Reverse(p.1));
    out
}

/// Files one message. `now` and `previous` decide "the same conversation".
pub fn route(
    text: &str,
    projects: &[ProjectWords],
    previous: Option<&Previous>,
    now: i64,
) -> Routed {
    let (named, body) = match prefix(text, projects) {
        Some((p, rest)) => (Some(p), rest.to_string()),
        None => (None, text.trim().to_string()),
    };
    let (kind, body) = if is_status(&body) {
        (Kind::Status, body)
    } else if let Some(rest) = idea(&body) {
        (Kind::Idea, rest)
    } else {
        (Kind::Request, body)
    };
    let ranked = scores(&body, projects);
    let candidates: Vec<String> = ranked.iter().take(3).map(|(p, _)| p.clone()).collect();
    let routed = |project: Option<String>, how: How| Routed {
        kind: kind.clone(),
        project,
        how,
        text: body.clone(),
        candidates: candidates.clone(),
    };
    if let Some(p) = named {
        return routed(Some(p), How::Prefix);
    }
    // "What's the status?" with no project named covers them all.
    if kind == Kind::Status {
        let top = ranked.first().filter(|(_, s)| *s >= 3);
        return match top {
            Some((p, _)) => routed(Some(p.clone()), How::Keywords),
            None => routed(None, How::AllProjects),
        };
    }
    // A different project named in the message ("In Recipe Box,
    // …") wins over the same conversation.
    let named_other = match (ranked.first(), ranked.get(1)) {
        (Some((p, top)), next) if *top >= 3 && *top >= 2 * next.map(|n| n.1).unwrap_or(0) => {
            Some(p.clone())
        }
        _ => None,
    };
    if let Some(prev) = previous
        && now - prev.time <= SAME_CONVERSATION_S
        && let Some(p) = &prev.project
        && named_other
            .as_ref()
            .is_none_or(|n| crate::paths::same(n, p))
    {
        return routed(Some(p.clone()), How::SameConversation);
    }
    match (ranked.first(), ranked.get(1)) {
        (Some((p, top)), next) if *top >= 2 && *top >= 2 * next.map(|n| n.1).unwrap_or(0) => {
            routed(Some(p.clone()), How::Keywords)
        }
        _ => routed(None, How::Unclear),
    }
}

/// Project names that are everyday words: a chat never goes to one of
/// them unless it is named as a prefix ("General: …").
const EVERYDAY: [&str; 12] = [
    "random", "notes", "test", "tests", "misc", "chats", "chat", "scratch", "tmp", "temp", "stuff",
    "sandbox",
];

/// Verbs that make a named project the target of a message ("fix … in
/// Budgetapp", "build Recipes Hub").
const WORK_VERBS: [&str; 30] = [
    "fix",
    "build",
    "add",
    "change",
    "update",
    "make",
    "release",
    "test",
    "check",
    "run",
    "commit",
    "push",
    "refactor",
    "debug",
    "implement",
    "remove",
    "rename",
    "write",
    "edit",
    "deploy",
    "ship",
    "install",
    "improve",
    "clean",
    "tidy",
    "finish",
    "start",
    "redesign",
    "review",
    "merge",
];

/// A leading "In <name>, …", "In <name>: …" or "For <name>, …".
fn in_prefix<'a>(text: &'a str, projects: &[ProjectWords]) -> Option<(String, &'a str)> {
    let t = text.trim_start();
    let lower = t.to_lowercase();
    let after = ["in ", "for "]
        .iter()
        .find_map(|w| lower.starts_with(w).then(|| &t[w.len()..]))?;
    let end = after.find([',', ':'])?;
    let head = &after[..end];
    if head.split_whitespace().count() > 4 {
        return None;
    }
    let h = norm(head);
    if h.is_empty() {
        return None;
    }
    projects
        .iter()
        .find(|p| p.names.iter().any(|n| norm(n) == h))
        .map(|p| (p.path.clone(), after[end + 1..].trim_start()))
}

/// The message's lower-cased whole words.
fn plain_words(text: &str) -> Vec<String> {
    text.to_lowercase()
        .replace('’', "'")
        .split(|c: char| !(c.is_alphanumeric() || c == '\''))
        .map(|w| w.trim_matches('\'').to_string())
        .filter(|w| !w.is_empty())
        .collect()
}

/// Whether `name`'s words stand in `msg` as whole words, one after another.
fn has_name(msg: &[String], name: &str) -> Option<usize> {
    let n = plain_words(&name.replace(['-', '_'], " "));
    if n.is_empty() || n.len() > msg.len() {
        return None;
    }
    (0..=msg.len() - n.len()).find(|&i| msg[i..i + n.len()] == n[..])
}

/// Whether a project's folder name is an everyday word.
fn everyday(p: &ProjectWords) -> bool {
    let name = std::path::Path::new(&p.path)
        .file_name()
        .map(|n| n.to_string_lossy().to_lowercase())
        .unwrap_or_default();
    EVERYDAY.contains(&name.as_str())
}

/// Files a general-chat message the strict way: a project only when it is
/// named on purpose, as a prefix ("Recipes: …", "@Recipes …", "In Recipes
/// Mod Manager, …") or as a whole name or alias after a work verb ("fix …
/// in Budgetapp"). Everything else is a chat (`project` None, `How::Unclear`
/// for a request). No word scores, no guesses, and a project named with an
/// everyday word ("General") only by prefix.
pub fn strict(text: &str, projects: &[ProjectWords]) -> Routed {
    let (named, body) = match prefix(text, projects).or_else(|| in_prefix(text, projects)) {
        Some((p, rest)) => (Some(p), rest.to_string()),
        None => (None, text.trim().to_string()),
    };
    let (kind, body) = if is_status(&body) {
        (Kind::Status, body)
    } else if let Some(rest) = idea(&body) {
        (Kind::Idea, rest)
    } else {
        (Kind::Request, body)
    };
    let routed = |project: Option<String>, how: How| Routed {
        kind: kind.clone(),
        project,
        how,
        text: body.clone(),
        candidates: vec![],
    };
    if let Some(p) = named {
        return routed(Some(p), How::Prefix);
    }
    let msg = plain_words(&body);
    let verb = msg.iter().position(|w| WORK_VERBS.contains(&w.as_str()));
    let found = projects.iter().filter(|p| !everyday(p)).find_map(|p| {
        let at = p.names.iter().filter_map(|n| has_name(&msg, n)).min()?;
        // A status question or an idea names its project anywhere; a
        // request only after a work verb.
        (kind != Kind::Request || verb.is_some_and(|v| v < at)).then(|| p.path.clone())
    });
    match (found, &kind) {
        (Some(p), _) => routed(Some(p), How::Keywords),
        (None, Kind::Status) => routed(None, How::AllProjects),
        (None, _) => routed(None, How::Unclear),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn projects() -> Vec<ProjectWords> {
        vec![
            ProjectWords {
                path: "/p/Recipe-Box".into(),
                names: vec!["Recipe-Box".into(), "Recipes".into()],
                words: vec![
                    "Recipe Box".into(),
                    "Reset game files".into(),
                    "Mods folder for 7z archives".into(),
                ],
            },
            ProjectWords {
                path: "/p/Tune-Sorter".into(),
                names: vec!["Tune-Sorter".into(), "Sorter".into(), "calls".into()],
                words: vec![
                    "Tune Sorter".into(),
                    "Audio calls on cell signal".into(),
                    "Video calls".into(),
                    "Kids TV caller photo".into(),
                ],
            },
            ProjectWords {
                path: "/p/Garden-Planner".into(),
                names: vec!["Garden-Planner".into(), "Station".into()],
                words: vec![
                    "Mímir".into(),
                    "Make Allow respond faster".into(),
                    "Phone app pairing".into(),
                ],
            },
        ]
    }

    fn r(text: &str) -> Routed {
        route(text, &projects(), None, 1_000_000)
    }

    /// The routing test set: each message, what it is, and where it goes.
    #[test]
    fn the_routing_test_set_files_each_message_as_expected() {
        let p = projects();
        let set: &[(&str, Kind, Option<&str>, How)] = &[
            // Kinds.
            ("What's the status?", Kind::Status, None, How::AllProjects),
            ("status", Kind::Status, None, How::AllProjects),
            ("what needs me", Kind::Status, None, How::AllProjects),
            ("What's running?", Kind::Status, None, How::AllProjects),
            (
                "Recipes: status",
                Kind::Status,
                Some("/p/Recipe-Box"),
                How::Prefix,
            ),
            (
                "Idea: a dark mode switch in Recipes's settings",
                Kind::Idea,
                Some("/p/Recipe-Box"),
                How::Keywords,
            ),
            (
                "New idea for Sorter: big caller photo",
                Kind::Idea,
                Some("/p/Tune-Sorter"),
                How::Keywords,
            ),
            // Prefix and alias.
            (
                "Recipes: the reset button does nothing",
                Kind::Request,
                Some("/p/Recipe-Box"),
                How::Prefix,
            ),
            (
                "@Station make the pairing code bigger",
                Kind::Request,
                Some("/p/Garden-Planner"),
                How::Prefix,
            ),
            (
                "Sorter: audio cuts out",
                Kind::Request,
                Some("/p/Tune-Sorter"),
                How::Prefix,
            ),
            // Keywords.
            (
                "Video calls freeze on cell signal",
                Kind::Request,
                Some("/p/Tune-Sorter"),
                How::Keywords,
            ),
            (
                "Reset game files leaves mods behind",
                Kind::Request,
                Some("/p/Recipe-Box"),
                How::Keywords,
            ),
            // Unclear.
            ("Fix the bug", Kind::Request, None, How::Unclear),
            (
                "Look at this when you can",
                Kind::Request,
                None,
                How::Unclear,
            ),
        ];
        for (text, kind, project, how) in set {
            let got = route(text, &p, None, 1_000_000);
            assert_eq!(
                (&got.kind, got.project.as_deref(), &got.how),
                (kind, *project, how),
                "{text:?} was filed as {got:?}"
            );
        }
    }

    #[test]
    fn a_message_soon_after_the_last_goes_to_the_same_project() {
        let prev = Previous {
            time: 1_000_000 - 120,
            project: Some("/p/Garden-Planner".into()),
            task: Some("t-1".into()),
        };
        let got = route("and make it bigger", &projects(), Some(&prev), 1_000_000);
        assert_eq!(got.project.as_deref(), Some("/p/Garden-Planner"));
        assert_eq!(got.how, How::SameConversation);
        // After 10 minutes it doesn't.
        let got = route(
            "and make it bigger",
            &projects(),
            Some(&prev),
            1_000_000 + 600,
        );
        assert_eq!(got.how, How::Unclear);
        // A prefix still wins.
        let got = route("Recipes: and this", &projects(), Some(&prev), 1_000_000);
        assert_eq!(got.project.as_deref(), Some("/p/Recipe-Box"));
    }

    #[test]
    fn a_project_named_in_the_message_wins_over_the_same_conversation() {
        let prev = Previous {
            time: 1_000_000 - 60,
            project: Some("/p/Garden-Planner".into()),
            task: Some("t-1".into()),
        };
        let got = route(
            "In Recipe Box, what version is in the README?",
            &projects(),
            Some(&prev),
            1_000_000,
        );
        assert_eq!(got.project.as_deref(), Some("/p/Recipe-Box"));
        assert_eq!(got.how, How::Keywords);
        // Naming the same project keeps the conversation going.
        let got = route("Station: and this", &projects(), Some(&prev), 1_000_000);
        assert_eq!(got.project.as_deref(), Some("/p/Garden-Planner"));
        let got = route(
            "and the Mímir window too",
            &projects(),
            Some(&prev),
            1_000_000,
        );
        assert_eq!(got.how, How::SameConversation);
    }

    #[test]
    fn the_prefix_and_idea_word_are_taken_off() {
        assert_eq!(r("Recipes: fix it").text, "fix it");
        assert_eq!(r("Idea: a big photo").text, "a big photo");
        assert_eq!(r("@Station tidy up").text, "tidy up");
    }

    #[test]
    fn a_sentence_with_a_colon_is_not_a_prefix() {
        let got = r("Please look at the thing I said: it broke");
        assert_ne!(got.how, How::Prefix);
    }

    #[test]
    fn unclear_messages_list_the_likeliest_projects() {
        // Tune-Sorter (a name, 3) isn't twice Garden Planner (2 words).
        let got = r("Make the calls page faster");
        assert_eq!(got.how, How::Unclear);
        assert_eq!(got.candidates[0], "/p/Tune-Sorter");
        assert_eq!(got.candidates[1], "/p/Garden-Planner");
        assert_eq!(got.candidates.len(), 3);
    }

    #[test]
    fn a_chat_is_placed_sure_unsure_or_nowhere() {
        let p = projects();
        // Named: sure.
        assert_eq!(
            place("Sorter: the ringtone is too quiet", &p),
            Placement::Sure("/p/Tune-Sorter".into())
        );
        assert_eq!(
            place("Recipe Box should list mods by size", &p),
            Placement::Sure("/p/Recipe-Box".into())
        );
        // Two of its words, and another project close behind: a guess.
        match place("Make the calls page faster", &p) {
            Placement::Unsure { project, why } => {
                assert_eq!(project, "/p/Tune-Sorter");
                assert!(
                    why.contains("calls") && why.contains("nearly as well"),
                    "{why}"
                );
            }
            other => panic!("{other:?}"),
        }
        assert_eq!(
            place("Video freezes on cell signal", &p),
            Placement::Sure("/p/Tune-Sorter".into())
        );
        match place("The kids photo looks blurry", &p) {
            Placement::Unsure { project, why } => {
                assert_eq!(project, "/p/Tune-Sorter");
                assert_eq!(
                    why,
                    "It mentions \u{201c}kid\u{201d} and \u{201c}photo\u{201d}."
                );
            }
            other => panic!("{other:?}"),
        }
        // Nothing points anywhere: General.
        assert_eq!(place("Plan a trip to Lisbon", &p), Placement::None);
        assert_eq!(place("Force restart my pc", &p), Placement::None);
    }

    fn with_everyday() -> Vec<ProjectWords> {
        let mut p = projects();
        p.push(ProjectWords {
            path: "/p/General".into(),
            names: vec!["General".into()],
            words: vec!["General number picker".into()],
        });
        p.push(ProjectWords {
            path: "/p/Android-Tools".into(),
            names: vec!["Android-Tools".into()],
            words: vec!["Newest Android version".into()],
        });
        p
    }

    /// The chat fixture sentences (design 2.3): everyday questions stay
    /// chats, a project named on purpose is a project.
    #[test]
    fn the_strict_router_files_only_projects_named_on_purpose() {
        let p = with_everyday();
        let chats = [
            "give me a random number",
            "Give me a random number",
            "What's the newest Android version?",
            "Is Claude better than ChatGPT?",
            "What's the capital of Norway?",
            "Plan a trip to Lisbon",
            "Video calls freeze on cell signal",
            "The station near me is closed, what now?",
            "Tell me about the Tune Sorter myth",
            "Fix the bug",
        ];
        for t in chats {
            let got = strict(t, &p);
            assert_eq!(got.project, None, "{t:?} went to {got:?}");
            assert_eq!(got.kind, Kind::Request, "{t:?}");
        }
        let projects = [
            (
                "In Recipe Box, what version is it?",
                "/p/Recipe-Box",
                How::Prefix,
                "what version is it?",
            ),
            (
                "in recipe-box: check the README",
                "/p/Recipe-Box",
                How::Prefix,
                "check the README",
            ),
            (
                "Recipes: the reset button does nothing",
                "/p/Recipe-Box",
                How::Prefix,
                "the reset button does nothing",
            ),
            (
                "@Station make the pairing code bigger",
                "/p/Garden-Planner",
                How::Prefix,
                "make the pairing code bigger",
            ),
            (
                "General: give me a random number",
                "/p/General",
                How::Prefix,
                "give me a random number",
            ),
            (
                "Fix the reset button in Recipe Box",
                "/p/Recipe-Box",
                How::Keywords,
                "Fix the reset button in Recipe Box",
            ),
            (
                "build Tune-Sorter for the tablet",
                "/p/Tune-Sorter",
                How::Keywords,
                "build Tune-Sorter for the tablet",
            ),
        ];
        for (t, want, how, body) in projects {
            let got = strict(t, &p);
            assert_eq!(got.project.as_deref(), Some(want), "{t:?}");
            assert_eq!(got.how, how, "{t:?}");
            assert_eq!(got.text, body, "{t:?}");
        }
        // An everyday-word project isn't named by a verb either.
        assert_eq!(strict("make a random playlist", &p).project, None);
        // Status questions: all projects, or the one named.
        let got = strict("What's the status?", &p);
        assert_eq!((got.kind, got.how), (Kind::Status, How::AllProjects));
        let got = strict("Recipes: status", &p);
        assert_eq!(got.project.as_deref(), Some("/p/Recipe-Box"));
        // An idea with no project named is just a chat message.
        let got = strict("Idea: a game night every Friday", &p);
        assert_eq!((got.kind, got.project), (Kind::Idea, None));
        let got = strict("Idea: a dark mode for Recipes", &p);
        assert_eq!(got.project.as_deref(), Some("/p/Recipe-Box"));
    }

    #[test]
    fn words_are_lower_cased_trimmed_and_singular() {
        assert_eq!(words("The Calls, and THIS photos!"), vec!["call", "photo"]);
    }
}
