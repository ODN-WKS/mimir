//! One truth about the PC's work (Stage 1 design, 3.1): every surface (the
//! window, each phone) reads the same snapshot, so none counts, labels or
//! filters tasks on its own. The service builds it from its own state, and
//! pushes it only when something a person sees changes.

use mimir_proto::{
    CheckFacts, PcActivity, TaskState, Truth, TruthCounts, TruthEngine, TruthPc, TruthProject,
    TruthStats, TruthTask, TruthUsage, WeeklyUse,
};
use std::hash::{Hash, Hasher};

/// Done tasks stay in the truth this long, and at most this many.
pub const DONE_KEPT_S: i64 = 24 * 3600;
pub const DONE_KEPT: usize = 50;

/// A task as the truth needs it.
#[derive(Debug, Clone)]
pub struct TaskIn {
    pub id: String,
    /// Its home project folder.
    pub project: String,
    pub title: String,
    pub working: bool,
    pub needs_you: bool,
    pub live_elsewhere: bool,
    pub stopped: bool,
    /// Stopped unexpectedly (see `TruthTask::failed`).
    pub failed: bool,
    /// Where it runs while live elsewhere: "app" or "terminal".
    pub elsewhere_in: Option<String>,
    pub archived: bool,
    /// Claude works on it through another session (Phone too, a followed
    /// session): Stop shows, the reply box doesn't.
    pub background: bool,
    /// When it went into its state (seconds since 1970).
    pub since: Option<i64>,
    pub from_phone: bool,
}

/// Everything the truth is built from.
pub struct Input<'a> {
    pub run_id: &'a str,
    pub now: i64,
    pub pc_name: String,
    pub activity: Option<&'a PcActivity>,
    pub tasks: Vec<TaskIn>,
    /// Waiting for the owner, per project folder (tasks' and others').
    pub needs: Vec<String>,
    /// (folder, shown name, core checks) of each listed project.
    pub projects: Vec<(String, String, Option<CheckFacts>)>,
    pub engine: TruthEngine,
    pub usage: (Option<WeeklyUse>, Option<WeeklyUse>, Option<i64>),
    /// The PC's processor, memory and graphics load.
    pub stats: Option<TruthStats>,
}

/// A task's one state, from fix batch 1's rule: needs the owner, then
/// working, then live elsewhere, then stopped, else done.
pub fn state(t: &TaskIn) -> TaskState {
    if t.needs_you {
        TaskState::NeedsYou
    } else if t.working {
        TaskState::Working
    } else if t.live_elsewhere {
        TaskState::LiveElsewhere
    } else if t.stopped {
        TaskState::Stopped
    } else {
        TaskState::Done
    }
}

/// The truth (its `rev` is the caller's).
pub fn build(i: Input) -> Truth {
    let mut tasks: Vec<TruthTask> = i
        .tasks
        .iter()
        .filter(|t| !t.archived)
        .map(|t| TruthTask {
            id: t.id.clone(),
            project: t.project.clone(),
            title: t.title.clone(),
            state: state(t),
            background: t.background,
            since: t.since,
            from_phone: t.from_phone,
            failed: t.failed && state(t) == TaskState::Stopped,
            elsewhere_in: t
                .elsewhere_in
                .clone()
                .filter(|_| state(t) == TaskState::LiveElsewhere),
        })
        .collect();
    // Every task that isn't done, and the newest done ones of the last day.
    tasks.sort_by_key(|t| std::cmp::Reverse(t.since.unwrap_or(0)));
    let mut done = 0;
    tasks.retain(|t| {
        if t.state != TaskState::Done {
            return true;
        }
        done += 1;
        done <= DONE_KEPT && t.since.is_some_and(|s| i.now - s < DONE_KEPT_S)
    });
    let count = |s: TaskState| tasks.iter().filter(|t| t.state == s).count() as u32;
    let counts = TruthCounts {
        working: count(TaskState::Working),
        needs_you: i.needs.len() as u32,
    };
    let projects = i
        .projects
        .into_iter()
        .map(|(path, name, checks)| {
            let mine: Vec<&TaskIn> = i
                .tasks
                .iter()
                .filter(|t| !t.archived && crate::paths::same(&t.project, &path))
                .collect();
            TruthProject {
                last_activity: mine.iter().filter_map(|t| t.since).max(),
                working: mine
                    .iter()
                    .filter(|t| state(t) == TaskState::Working)
                    .count() as u32,
                needs_you: i
                    .needs
                    .iter()
                    .filter(|p| crate::paths::same(p, &path))
                    .count() as u32,
                checks: checks.filter(|c| c.total > 0),
                path,
                name,
            }
        })
        .collect();
    let pc = TruthPc {
        name: i.pc_name,
        level: i.activity.map(|a| a.level).unwrap_or_default(),
        line: i.activity.map(|a| a.phone_line.clone()).unwrap_or_default(),
    };
    // Whenever a reading exists, whatever model is chosen: the phone shows
    // the week's use where a person looks first (0.09 item 11).
    let usage = (i.usage.0.is_some() || i.usage.1.is_some()).then_some(TruthUsage {
        weekly: i.usage.0,
        five_hour: i.usage.1,
        as_of: i.usage.2,
    });
    Truth {
        run_id: i.run_id.to_string(),
        rev: 0,
        as_of: i.now,
        pc,
        tasks,
        counts,
        projects,
        engine: i.engine,
        usage,
        stats: i.stats,
    }
}

/// A hash of what a person sees: states, counts, titles, project rows, the
/// PC line, the engine line, and usage to the whole percent. Times and the
/// meters are left out (see [`meters_seen`]), so a task that keeps working
/// pushes nothing new.
pub fn seen_rest(t: &Truth) -> u64 {
    let mut h = std::collections::hash_map::DefaultHasher::new();
    t.pc.name.hash(&mut h);
    format!("{:?}", t.pc.level).hash(&mut h);
    t.pc.line.hash(&mut h);
    for x in &t.tasks {
        (
            &x.id,
            &x.project,
            &x.title,
            format!("{:?}", x.state),
            x.background,
            x.from_phone,
            x.failed,
            &x.elsewhere_in,
        )
            .hash(&mut h);
    }
    (t.counts.working, t.counts.needs_you).hash(&mut h);
    for p in &t.projects {
        (&p.path, &p.name, p.working, p.needs_you).hash(&mut h);
        p.checks
            .as_ref()
            .map(|c| (c.passing, c.total, c.failing.len()))
            .hash(&mut h);
    }
    (
        &t.engine.chats_where,
        &t.engine.chats_model_words,
        t.engine.answering,
        t.engine.paid_on,
        t.engine.model_down,
    )
        .hash(&mut h);
    if let Some(u) = &t.usage {
        u.weekly
            .as_ref()
            .map(|w| (w.used.round() as i64, w.resets_at))
            .hash(&mut h);
        u.five_hour
            .as_ref()
            .map(|w| (w.used.round() as i64, w.resets_at))
            .hash(&mut h);
    }
    h.finish()
}

/// The meters at a step a person can't tell apart: 5% for the processor and
/// graphics, 1 GB for memory.
pub fn meters_seen(t: &Truth) -> u64 {
    let mut h = std::collections::hash_map::DefaultHasher::new();
    t.stats
        .as_ref()
        .map(|s| {
            (
                (s.cpu / 5.0).round() as i64,
                s.gpu.map(|g| (g / 5.0).round() as i64),
                (s.mem_used as f64 / 1e9).round() as i64,
                s.mem_total,
            )
        })
        .hash(&mut h);
    h.finish()
}

/// Both together: what a person sees.
pub fn seen(t: &Truth) -> u64 {
    let mut h = std::collections::hash_map::DefaultHasher::new();
    (seen_rest(t), meters_seen(t)).hash(&mut h);
    h.finish()
}

/// How seldom a change of the meters alone goes to the phones.
pub const METER_PUSH_EVERY: std::time::Duration = std::time::Duration::from_secs(10);

/// Whether a new truth goes out: at once when anything but the meters
/// changed, and for a meters-only change at most every [`METER_PUSH_EVERY`]
/// (`since_push`: time since the last push; None: never pushed).
pub fn push_due(
    rest_changed: bool,
    meters_changed: bool,
    since_push: Option<std::time::Duration>,
) -> bool {
    rest_changed || (meters_changed && since_push.is_none_or(|d| d >= METER_PUSH_EVERY))
}

/// Whether a client should take `new` over what it has: a new run of the
/// service, or a higher revision of the same run.
pub fn newer(have: Option<&Truth>, new: &Truth) -> bool {
    have.is_none_or(|h| h.run_id != new.run_id || new.rev > h.rev)
}

/// The desktop notifications a change of the truth calls for, as (title,
/// body) in plain words: a task that went to Needs you, or to Done. `whisper`
/// says which task is a Whisper (its title never shows, and its Done is
/// silent). Nothing says what model runs it.
pub fn notices(old: &Truth, new: &Truth, whisper: &dyn Fn(&str) -> bool) -> Vec<(String, String)> {
    notices_with(old, new, whisper, true)
}

/// [`notices`], with the Done pop-ups left out when `done` is false (they are
/// off until chosen in Settings › Notifications; Needs you always shows).
pub fn notices_with(
    old: &Truth,
    new: &Truth,
    whisper: &dyn Fn(&str) -> bool,
    done: bool,
) -> Vec<(String, String)> {
    let mut out = vec![];
    for t in &new.tasks {
        let Some(before) = old.tasks.iter().find(|o| o.id == t.id) else {
            continue;
        };
        if before.state == t.state {
            continue;
        }
        let secret = whisper(&t.id);
        match t.state {
            TaskState::NeedsYou => out.push((
                if secret {
                    "A Whisper task needs you".to_string()
                } else {
                    format!("{} needs you", t.title)
                },
                "Open Mímir to answer.".to_string(),
            )),
            TaskState::Done if !secret && done => {
                out.push((format!("{} is done", t.title), "Mímir".to_string()))
            }
            _ => {}
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn task(id: &str, project: &str) -> TaskIn {
        TaskIn {
            id: id.into(),
            project: project.into(),
            title: format!("Task {id}"),
            working: false,
            needs_you: false,
            live_elsewhere: false,
            stopped: false,
            failed: false,
            elsewhere_in: None,
            archived: false,
            background: false,
            since: Some(1000),
            from_phone: false,
        }
    }

    fn input(tasks: Vec<TaskIn>, needs: Vec<String>) -> Input<'static> {
        Input {
            run_id: "r1",
            now: 2000,
            pc_name: "Your PC".into(),
            activity: None,
            tasks,
            needs,
            projects: vec![
                ("/p/a".into(), "a".into(), Some(CheckFacts::default())),
                ("/p/b".into(), "b".into(), None),
            ],
            engine: TruthEngine::default(),
            usage: (
                Some(WeeklyUse {
                    used: 62.4,
                    resets_at: 9,
                }),
                None,
                Some(1),
            ),
            stats: None,
        }
    }

    #[test]
    fn one_state_per_task_and_the_counts_follow_it() {
        let mut w = task("w", "/p/a");
        w.working = true;
        let mut n = task("n", "/p/a");
        n.working = true;
        n.needs_you = true;
        let mut e = task("e", "/p/b");
        e.live_elsewhere = true;
        let mut s = task("s", "/p/b");
        s.stopped = true;
        let d = task("d", "/p/b");
        let mut arch = task("x", "/p/b");
        arch.archived = true;
        arch.working = true;
        let t = build(input(vec![w, n, e, s, d, arch], vec!["/p/a".into()]));
        let st = |id: &str| t.tasks.iter().find(|x| x.id == id).map(|x| x.state);
        assert_eq!(st("w"), Some(TaskState::Working));
        assert_eq!(st("n"), Some(TaskState::NeedsYou));
        assert_eq!(st("e"), Some(TaskState::LiveElsewhere));
        assert_eq!(st("s"), Some(TaskState::Stopped));
        assert_eq!(st("d"), Some(TaskState::Done));
        assert_eq!(st("x"), None);
        assert_eq!((t.counts.working, t.counts.needs_you), (1, 1));
        assert_eq!((t.projects[0].working, t.projects[0].needs_you), (1, 1));
        // "No checks yet" is never sent: a project without checks has none.
        assert!(t.projects.iter().all(|p| p.checks.is_none()));
        // A reading is sent whichever model is chosen (0.09 item 11).
        assert!(t.usage.is_some());
    }

    #[test]
    fn old_done_tasks_drop_out_and_times_never_push() {
        let mut old = task("old", "/p/a");
        old.since = Some(2000 - DONE_KEPT_S - 1);
        let t = build(input(vec![old, task("new", "/p/a")], vec![]));
        assert_eq!(t.tasks.len(), 1);
        let mut later = t.clone();
        later.as_of += 5;
        later.tasks[0].since = Some(1999);
        assert_eq!(seen(&t), seen(&later));
        later.tasks[0].state = TaskState::Working;
        assert_ne!(seen(&t), seen(&later));
    }

    #[test]
    fn usage_shows_with_any_reading_and_pushes_only_by_whole_percents() {
        let mut i = input(vec![], vec![]);
        i.engine.paid_on = false;
        let a = build(i);
        assert_eq!(
            a.usage.as_ref().unwrap().weekly.as_ref().unwrap().used,
            62.4
        );
        let mut b = a.clone();
        b.usage.as_mut().unwrap().weekly.as_mut().unwrap().used = 62.2;
        assert_eq!(seen(&a), seen(&b));
        b.usage.as_mut().unwrap().weekly.as_mut().unwrap().used = 63.0;
        assert_ne!(seen(&a), seen(&b));
    }

    #[test]
    fn a_restart_always_wins_and_older_revisions_never_do() {
        let mut a = build(input(vec![], vec![]));
        a.rev = 5;
        let mut b = a.clone();
        b.rev = 4;
        assert!(!newer(Some(&a), &b));
        b.rev = 6;
        assert!(newer(Some(&a), &b));
        b.run_id = "r2".into();
        b.rev = 1;
        assert!(newer(Some(&a), &b));
        assert!(newer(None, &b));
    }

    #[test]
    fn a_task_going_to_needs_you_or_done_notifies_in_plain_words() {
        let task = |id: &str, state| TruthTask {
            id: id.into(),
            project: "/p".into(),
            title: format!("Task {id}"),
            state,
            background: false,
            since: None,
            from_phone: false,
            failed: false,
            elsewhere_in: None,
        };
        let truth = |tasks: Vec<TruthTask>| Truth {
            tasks,
            ..Default::default()
        };
        let old = truth(vec![
            task("a", TaskState::Working),
            task("b", TaskState::Working),
            task("w", TaskState::Working),
            task("c", TaskState::Working),
        ]);
        let new = truth(vec![
            task("a", TaskState::NeedsYou),
            task("b", TaskState::Done),
            task("w", TaskState::Done),
            task("c", TaskState::Working),
            task("n", TaskState::Working),
        ]);
        let n = notices(&old, &new, &|id| id == "w");
        assert_eq!(
            n,
            vec![
                (
                    "Task a needs you".to_string(),
                    "Open Mímir to answer.".to_string()
                ),
                ("Task b is done".to_string(), "Mímir".to_string()),
            ]
        );
        assert!(
            n.iter()
                .all(|(t, b)| !t.contains("Claude") && !b.contains("Claude"))
        );
        // A Whisper that needs you never names itself.
        let w = truth(vec![task("w", TaskState::NeedsYou)]);
        let before = truth(vec![task("w", TaskState::Working)]);
        assert_eq!(
            notices(&before, &w, &|_| true)[0].0,
            "A Whisper task needs you"
        );
    }

    /// A finished task's pop-up is off until chosen; Needs you always shows.
    #[test]
    fn done_pop_ups_can_be_left_out_and_needs_you_stays() {
        let mk = |id: &str, state| TruthTask {
            id: id.into(),
            project: "/p".into(),
            title: format!("Task {id}"),
            state,
            background: false,
            since: None,
            from_phone: false,
            failed: false,
            elsewhere_in: None,
        };
        let old = Truth {
            tasks: vec![mk("a", TaskState::Working), mk("b", TaskState::Working)],
            ..Default::default()
        };
        let new = Truth {
            tasks: vec![mk("a", TaskState::NeedsYou), mk("b", TaskState::Done)],
            ..Default::default()
        };
        let off = notices_with(&old, &new, &|_| false, false);
        assert_eq!(off.len(), 1, "{off:?}");
        assert!(off[0].0.ends_with("needs you"));
        assert_eq!(notices_with(&old, &new, &|_| false, true).len(), 2);
    }

    #[test]
    fn p09_item11_a_reading_is_sent_without_a_paid_model_and_none_when_unread() {
        let mut i = input(vec![], vec![]);
        i.engine.paid_on = false;
        assert!(build(i).usage.is_some());
        let mut i = input(vec![], vec![]);
        i.usage = (None, None, None);
        assert!(build(i).usage.is_none());
    }

    #[test]
    fn p09_item6_the_meters_are_in_the_truth_and_push_by_a_visible_step() {
        let stats = |cpu: f32, used: u64| TruthStats {
            cpu,
            mem_used: used,
            mem_total: 32_000_000_000,
            gpu: Some(10.0),
        };
        let mut i = input(vec![], vec![]);
        i.stats = Some(stats(20.0, 8_000_000_000));
        let a = build(i);
        assert_eq!(a.stats.as_ref().unwrap().cpu, 20.0);
        let mut b = a.clone();
        b.stats = Some(stats(21.0, 8_000_000_000));
        assert_eq!(seen(&a), seen(&b), "1% of processor is not news");
        b.stats = Some(stats(60.0, 8_000_000_000));
        assert_ne!(seen(&a), seen(&b), "a loaded PC is news");
    }
    #[test]
    fn p09d_fix_meters_alone_push_at_most_every_ten_seconds_in_coarse_steps() {
        use std::time::Duration;
        let stats = |cpu: f32, used: u64| TruthStats {
            cpu,
            mem_used: used,
            mem_total: 32_000_000_000,
            gpu: None,
        };
        let mut i = input(vec![], vec![]);
        i.stats = Some(stats(20.0, 8_000_000_000));
        let a = build(i);
        let mut b = a.clone();
        b.stats = Some(stats(20.0, 8_400_000_000));
        assert_eq!(meters_seen(&a), meters_seen(&b), "0.4 GB is not news");
        b.stats = Some(stats(20.0, 9_100_000_000));
        assert_ne!(meters_seen(&a), meters_seen(&b), "1 GB is news");
        assert_eq!(seen_rest(&a), seen_rest(&b), "meters are not the rest");
        assert!(!push_due(false, true, Some(Duration::from_secs(3))));
        assert!(push_due(false, true, Some(Duration::from_secs(10))));
        assert!(push_due(false, true, None));
        assert!(push_due(true, false, Some(Duration::from_secs(0))));
        assert!(!push_due(false, false, None));
    }
}
