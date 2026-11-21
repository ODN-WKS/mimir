//! The status page, built from facts each time it's asked: each project's
//! keep-it-working run log (its core checks), its tasks, git and version,
//! and Claude's usage. No Claude call. The chat's "what's the status?"
//! reply is made from the same facts with fixed sentences.

use mimir_proto::{CheckFacts, StatusFacts, StatusPage, WeeklyUse};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// The keep-it-working folder (its run logs).
pub fn log_dir() -> PathBuf {
    let base = std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .unwrap_or_else(|| crate::paths::home().join(".local/share"));
    base.join("keep-it-working")
}

/// The run log of a project: its folder's last part, lower-cased.
pub fn run_log(log_dir: &Path, project: &str) -> PathBuf {
    let name = Path::new(project.trim_end_matches('/'))
        .file_name()
        .map(|n| n.to_string_lossy().to_lowercase())
        .unwrap_or_default();
    log_dir.join(format!("{name}.jsonl"))
}

/// "2026-11-18T23:19:24Z" → seconds since 1970.
pub fn parse_time(t: &str) -> Option<i64> {
    let b = t.as_bytes();
    if b.len() < 19 {
        return None;
    }
    let n = |r: std::ops::Range<usize>| t.get(r)?.parse::<i64>().ok();
    let (y, m, d) = (n(0..4)?, n(5..7)?, n(8..10)?);
    let (hh, mm, ss) = (n(11..13)?, n(14..16)?, n(17..19)?);
    // Days from the civil date (Howard Hinnant's algorithm).
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(days * 86_400 + hh * 3600 + mm * 60 + ss)
}

/// The feature sentences of a project: "## CF-3" headings in its
/// `checks/core-features.md`, each with its first paragraph.
pub fn sentences(project: &Path) -> HashMap<String, String> {
    let Ok(text) = std::fs::read_to_string(project.join("checks/core-features.md")) else {
        return HashMap::new();
    };
    let mut out = HashMap::new();
    let mut lines = text.lines().peekable();
    while let Some(l) = lines.next() {
        let Some(id) = l.strip_prefix("## ") else {
            continue;
        };
        let id = id.trim().to_string();
        let mut para = vec![];
        while let Some(&n) = lines.peek() {
            if n.starts_with("## ") {
                break;
            }
            lines.next();
            if n.trim().is_empty() {
                if para.is_empty() {
                    continue;
                }
                break;
            }
            para.push(n.trim());
        }
        out.insert(id, para.join(" "));
    }
    out
}

/// Which feature a check name belongs to: `cf3_…` is CF-3, `p5_…` is P-5,
/// the socket refusals `sec1_…` to `sec5_…` are S-1.
pub fn feature_of(check: &str) -> Option<String> {
    let digits = |rest: &str| -> Option<String> {
        let d: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        (!d.is_empty() && rest[d.len()..].starts_with('_')).then_some(d)
    };
    if let Some(rest) = check.strip_prefix("cf") {
        return digits(rest).map(|d| format!("CF-{d}"));
    }
    if let Some(rest) = check.strip_prefix("sec") {
        return digits(rest).map(|_| "S-1".to_string());
    }
    if let Some(rest) = check.strip_prefix('p') {
        return digits(rest).map(|d| format!("P-{d}"));
    }
    None
}

/// One run-log line we count.
struct Run {
    time: i64,
    commit: String,
    results: Vec<(String, String)>,
}

fn read_runs(path: &Path) -> Vec<Run> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return vec![];
    };
    text.lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .filter(|v| matches!(v["kind"].as_str(), Some("window" | "phone" | "real")))
        .filter_map(|v| {
            Some(Run {
                time: parse_time(v["time"].as_str()?)?,
                commit: v["commit"].as_str()?.to_string(),
                results: v["results"]
                    .as_object()?
                    .iter()
                    .filter_map(|(k, r)| Some((k.clone(), r.as_str()?.to_string())))
                    .collect(),
            })
        })
        .collect()
}

/// The core checks of a project, from its run log: the newest result of
/// each check at the newest commit, per feature (a feature passes when all
/// its checks passed). None when there's no run log.
pub fn checks(project: &Path, log: &Path) -> Option<CheckFacts> {
    let runs = read_runs(log);
    let newest = runs.iter().max_by_key(|r| r.time)?;
    let commit = newest.commit.clone();
    let mut latest: HashMap<String, (i64, String)> = HashMap::new();
    for r in runs.iter().filter(|r| r.commit == commit) {
        for (check, result) in &r.results {
            if latest.get(check).is_none_or(|(t, _)| *t <= r.time) {
                latest.insert(check.clone(), (r.time, result.clone()));
            }
        }
    }
    let said = sentences(project);
    let sentence = |id: &str| said.get(id).cloned().unwrap_or_else(|| id.to_string());
    // Feature → worst result of its checks.
    let rank = |r: &str| match r {
        "failed" => 3,
        "unreliable" => 2,
        "could_not_run" => 1,
        _ => 0,
    };
    let mut worst: HashMap<String, &str> = HashMap::new();
    for (check, (_, result)) in &latest {
        let Some(f) = feature_of(check) else { continue };
        let w = worst.entry(f).or_insert("passed");
        if rank(result) > rank(w) {
            *w = match result.as_str() {
                "failed" => "failed",
                "unreliable" => "unreliable",
                "could_not_run" => "could_not_run",
                _ => "passed",
            };
        }
    }
    let mut ids: Vec<String> = worst.keys().cloned().collect();
    ids.sort_by_key(|id| feature_key(id));
    let mut c = CheckFacts {
        commit: Some(commit.clone()),
        when: Some(newest.time),
        ..Default::default()
    };
    for id in &ids {
        let w = worst[id];
        c.total += 1;
        let pair = (id.clone(), sentence(id));
        match w {
            "passed" => c.passing += 1,
            "failed" => c.failing.push(pair.clone()),
            "unreliable" => c.unreliable.push(pair.clone()),
            _ => c.could_not_run.push(pair.clone()),
        }
        c.features.push((pair.0, pair.1, w == "passed"));
    }
    // "Failing since": the first run of the unbroken stretch of runs with a
    // failure, going back from the newest.
    if !c.failing.is_empty() {
        let mut by_time: Vec<&Run> = runs.iter().collect();
        by_time.sort_by_key(|r| r.time);
        let mut since = None;
        for r in by_time.iter().rev() {
            if r.results.iter().any(|(_, x)| x == "failed") {
                since = Some(r.time);
            } else if r.results.iter().any(|(_, x)| x == "passed") {
                break;
            }
        }
        c.failing_since = since;
    }
    Some(c)
}

/// CF before S before P, then by number.
fn feature_key(id: &str) -> (u8, u32) {
    let (kind, n) = id.split_once('-').unwrap_or((id, "0"));
    let k = match kind {
        "CF" => 0,
        "S" => 1,
        "P" => 2,
        _ => 3,
    };
    (k, n.parse().unwrap_or(0))
}

/// What the service knows about a project's work.
#[derive(Debug, Clone, Default)]
pub struct Work {
    pub path: String,
    pub name: String,
    pub tasks_running: usize,
    pub needs_you: usize,
    pub last_activity: Option<i64>,
}

/// "Make Allow respond faster" → "make Allow respond faster" (for mid-sentence).
fn lower_first(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        // Keep names and acronyms ("CF", "PC") as they are.
        Some(f) if c.clone().next().is_some_and(|n| n.is_lowercase()) => {
            f.to_lowercase().collect::<String>() + c.as_str()
        }
        Some(f) => f.to_string() + c.as_str(),
        None => String::new(),
    }
}

/// The first part of a feature sentence, for a short line.
fn short(sentence: &str) -> String {
    let s = sentence.split(['.', ';']).next().unwrap_or(sentence).trim();
    let s: String = s.chars().take(80).collect();
    lower_first(&s)
}

/// One project's facts, with its line in plain words.
pub fn project(w: &Work, log_dir: &Path, with_git: bool) -> StatusFacts {
    let p = Path::new(&w.path);
    let checks = checks(p, &run_log(log_dir, &w.path));
    let version = std::fs::read_to_string(p.join("VERSION"))
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .or_else(|| {
            if with_git {
                crate::git::newest_tag(p)
            } else {
                None
            }
        });
    let (uncommitted, unpushed) = if with_git {
        (
            crate::git::changes(p).map(|c| c.len()),
            crate::git::unpushed(p),
        )
    } else {
        (None, None)
    };
    let mut f = StatusFacts {
        project: w.path.clone(),
        name: w.name.clone(),
        version,
        checks,
        tasks_running: w.tasks_running,
        needs_you: w.needs_you,
        uncommitted,
        unpushed,
        last_activity: w.last_activity,
        line: String::new(),
    };
    f.line = line(&f);
    f
}

/// "All 7 passing", "1 of 5 failing", "No checks set up".
pub fn checks_words(c: &Option<CheckFacts>) -> String {
    match c {
        None => "No checks set up".into(),
        Some(c) if c.total == 0 => "No checks set up".into(),
        Some(c) if c.failing.is_empty() && c.passing == c.total => {
            format!("All {} passing", c.total)
        }
        Some(c) if !c.failing.is_empty() => format!("{} of {} failing", c.failing.len(), c.total),
        Some(c) => format!("{} of {} passing", c.passing, c.total),
    }
}

fn plural(n: usize, one: &str, many: &str) -> String {
    if n == 1 {
        format!("1 {one}")
    } else {
        format!("{n} {many}")
    }
}

/// A project's line: its checks, then what's going on.
pub fn line(f: &StatusFacts) -> String {
    let mut parts = vec![];
    match &f.checks {
        Some(c) if !c.failing.is_empty() => parts.push(format!(
            "{}: {}",
            checks_words(&f.checks),
            short(&c.failing[0].1)
        )),
        _ => parts.push(checks_words(&f.checks)),
    }
    if f.needs_you > 0 {
        parts.push(format!("needs you: {}", f.needs_you));
    }
    if f.tasks_running > 0 {
        parts.push(plural(f.tasks_running, "task running", "tasks running"));
    }
    if let Some(v) = &f.version {
        parts.push(format!("version {v}"));
    }
    if let Some(n) = f.unpushed.filter(|n| *n > 0) {
        parts.push(plural(n, "commit not pushed", "commits not pushed"));
    }
    parts.join(" · ")
}

/// The page: projects sorted with needs-you first, then failing checks,
/// then by last activity; and the chat's reply.
pub fn page(
    mut projects: Vec<StatusFacts>,
    weekly: Option<WeeklyUse>,
    five_hour: Option<WeeklyUse>,
    now: i64,
) -> StatusPage {
    projects.sort_by(|a, b| {
        let fail = |f: &StatusFacts| f.checks.as_ref().is_some_and(|c| !c.failing.is_empty());
        (b.needs_you > 0)
            .cmp(&(a.needs_you > 0))
            .then(fail(b).cmp(&fail(a)))
            .then(b.last_activity.cmp(&a.last_activity))
            .then(a.name.cmp(&b.name))
    });
    let reply = reply(&projects, None);
    StatusPage {
        projects,
        weekly,
        five_hour,
        reply,
        data_time: now,
        pc_line: None,
    }
}

/// The chat's status reply in fixed sentences: what needs the owner, what
/// fails, what runs. With `only`, about that one project.
pub fn reply(projects: &[StatusFacts], only: Option<&str>) -> Vec<String> {
    let list: Vec<&StatusFacts> = projects
        .iter()
        .filter(|f| only.is_none_or(|p| f.project == p))
        .collect();
    let mut out = vec![];
    let needs: usize = list.iter().map(|f| f.needs_you).sum();
    out.push(match needs {
        0 => "Nothing needs you.".to_string(),
        1 => "1 thing needs you.".to_string(),
        n => format!("{n} things need you."),
    });
    for f in list.iter().filter(|f| f.needs_you > 0) {
        out.push(format!(
            "{}: {} waiting for you.",
            f.name,
            plural(f.needs_you, "item", "items")
        ));
    }
    let mut failing = 0;
    for f in &list {
        if let Some(c) = &f.checks
            && !c.failing.is_empty()
        {
            failing += 1;
            let what: Vec<String> = c.failing.iter().map(|(_, s)| short(s)).collect();
            out.push(format!(
                "{} has {}: {}.",
                f.name,
                plural(c.failing.len(), "failing check", "failing checks"),
                what.join("; ")
            ));
        }
    }
    for f in list.iter().filter(|f| f.tasks_running > 0) {
        out.push(format!(
            "{}: {}.",
            f.name,
            plural(f.tasks_running, "task running", "tasks running")
        ));
    }
    if only.is_none() && list.len() > failing && failing > 0 {
        out.push("Everything else is passing or idle.".into());
    } else if only.is_none() && failing == 0 && list.iter().all(|f| f.tasks_running == 0) {
        out.push("Everything is passing or idle.".into());
    }
    if let Some(f) = only.and_then(|_| list.first())
        && failing == 0
    {
        out.push(format!(
            "{}: {}.",
            f.name,
            lower_first(&checks_words(&f.checks))
        ));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn times_are_read() {
        assert_eq!(parse_time("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(parse_time("2026-11-18T23:19:24Z"), Some(1_795_043_964));
    }

    #[test]
    fn checks_map_to_features() {
        assert_eq!(
            feature_of("cf13_tasks_listed_by_project").as_deref(),
            Some("CF-13")
        );
        assert_eq!(feature_of("sec3_made_up_answer").as_deref(), Some("S-1"));
        assert_eq!(feature_of("p5_push_arrives").as_deref(), Some("P-5"));
        assert_eq!(feature_of("phone_pairs"), None);
        assert_eq!(feature_of("cfx_"), None);
    }
}
