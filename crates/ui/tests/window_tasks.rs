//! Window checks for finding work again (CF-13): tasks listed under their
//! projects, and sessions from the terminal or the phone listed and carried
//! on here.

mod common;
use common::*;
use egui_kittest::kittest::Queryable;
use mimir_core::store::Store;
use mimir_core::task::{Entry, Status, Task};
use station_ui::labels;

/// Where a widget with exactly this spoken name or text is, top to bottom.
fn top_of(h: &egui_kittest::Harness<'static, station_ui::App>, name: &str) -> Option<f32> {
    h.query_all_by(|n| n.label().as_deref() == Some(name) || n.value().as_deref() == Some(name))
        .next()
        .map(|n| n.rect().min.y)
}

#[test]
fn cf13_tasks_listed_by_project() {
    let Some(home) = TestHome::new() else { return };
    let (alpha, beta) = (home.root.join("alpha"), home.root.join("beta"));
    for p in [&alpha, &beta] {
        std::fs::create_dir_all(p).unwrap();
    }
    home.write_settings(serde_json::json!({
        "projects": [alpha, beta],
        "last_project": alpha,
    }));
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let made = [
        ("t1", "Sort the receipts", &alpha),
        ("t2", "Rename the photos", &alpha),
        ("t3", "Plan the trip", &beta),
    ];
    for (n, (id, title, project)) in made.iter().enumerate() {
        let mut t = Task::new(
            id.to_string(),
            title.to_string(),
            project.display().to_string(),
            n as i64,
        );
        t.entries = vec![Entry::You(title.to_string()), Entry::Claude("Done.".into())];
        t.status = Status::Done;
        store.save(&t).unwrap();
    }
    home.tripwire();
    let mut h = home.open();
    let listed = wait_for(&mut h, |h| {
        made.iter().all(|(_, title, _)| {
            top_of(h, &labels::task_row(title, &project_of(title, &made))).is_some()
        })
    });
    // Each task sits below its project's heading and above the next one's.
    let heading = |h: &egui_kittest::Harness<'static, station_ui::App>, name: &str| {
        top_of(h, &labels::folder_row(name))
    };
    let (a, b) = (heading(&h, "alpha"), heading(&h, "beta"));
    let under_its_project = made.iter().all(|(_, title, project)| {
        let name = project.file_name().unwrap().to_string_lossy().to_string();
        let y = top_of(&h, &labels::task_row(title, &name));
        match (y, a, b) {
            (Some(y), Some(a), Some(b)) if name == "alpha" => y > a && y < b,
            (Some(y), Some(_), Some(b)) => y > b,
            _ => false,
        }
    });
    assert!(
        listed && under_its_project,
        "a task isn't listed under its project\n{}",
        screen_text(&h)
    );
    close(h);
}

/// The project folder name a made-up task belongs to.
fn project_of(title: &str, made: &[(&str, &str, &std::path::PathBuf)]) -> String {
    made.iter()
        .find(|(_, t, _)| *t == title)
        .map(|(_, _, p)| p.file_name().unwrap().to_string_lossy().to_string())
        .unwrap_or_default()
}

/// Puts a recorded Claude Code history file where Claude Code keeps this
/// project's sessions; returns its session id and first message.
fn put_session(home: &TestHome, file: &str) -> (String, String) {
    let project = home.project.display().to_string();
    let text = std::fs::read_to_string(session_file(file))
        .unwrap()
        .replace("@PROJECT@", &project);
    let mut id = String::new();
    let mut first = String::new();
    for line in text.lines() {
        let v: serde_json::Value = serde_json::from_str(line).unwrap();
        if id.is_empty() {
            id = v["sessionId"].as_str().unwrap_or_default().to_string();
        }
        if first.is_empty() && v["type"] == "user" {
            first = v["message"]["content"][0]["text"]
                .as_str()
                .unwrap_or_default()
                .to_string();
        }
    }
    let folder = home
        .root
        .join(".claude/projects")
        .join(folder_safe(&project));
    std::fs::create_dir_all(&folder).unwrap();
    std::fs::write(folder.join(format!("{id}.jsonl")), text).unwrap();
    (id, first)
}

/// A session from elsewhere is listed; clicking it brings it in, and the
/// next message carries the same conversation on.
fn listed_and_continued(file: &str, what: &str) {
    let Some(home) = TestHome::new() else { return };
    let (id, first) = put_session(&home, file);
    home.play("resume.jsonl", "resume.jsonl");
    let mut h = home.open();
    let row = labels::session_row(&mimir_core::runner::title_from(&first), "project");
    let listed = wait_for(&mut h, |h| has_label(h, &row));
    assert!(
        listed,
        "a {what} session isn't listed or can't be continued\n{}",
        screen_text(&h)
    );
    h.query_all_by_label(&row).next().unwrap().hover();
    for _ in 0..3 {
        h.step();
    }
    h.query_all_by_label(&row).next().unwrap().click();
    h.step();
    h.step();
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::SEND)),
        "a {what} session isn't listed or can't be continued (it didn't open)\n{}",
        screen_text(&h)
    );
    type_message(
        &mut h,
        "What file did you create earlier and what is in it? One line.",
    );
    click(&mut h, labels::SEND);
    let replied = wait_for(&mut h, |h| shows(h, "I created"));
    let runs = home.conversations();
    let resumed = runs
        .iter()
        .any(|a| a.windows(2).any(|w| w[0] == "--resume" && w[1] == id));
    assert!(
        replied && resumed,
        "a {what} session isn't listed or can't be continued\nruns: {runs:?}\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn cf13_terminal_session_listed_and_continued() {
    listed_and_continued("terminal.jsonl", "terminal");
}

#[test]
fn cf13_phone_session_listed_and_continued() {
    listed_and_continued("phone.jsonl", "phone");
}

/// The side panel runs This task, Conversation memory, Steps, then the changed
/// files and the permission: a long list of them must not push anything else out of view. The
/// memory and steps cards show only once they have something to say.
#[test]
fn side_panel_order_is_pc_memory_steps_changes() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.clone();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "details": true,
    }));
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(
        "t1".into(),
        "Sort the receipts".into(),
        project.display().to_string(),
        0,
    );
    t.entries = vec![
        Entry::You("Sort the receipts".into()),
        Entry::Claude("Done.".into()),
    ];
    t.status = Status::Done;
    t.context_window = 200_000;
    t.context_used = 40_000;
    t.plan = vec![mimir_core::proto::PlanItem {
        id: "1".into(),
        text: "Sort them".into(),
        done: true,
        doing: false,
    }];
    store.save(&t).unwrap();
    home.tripwire();
    let mut h = home.open();
    let name = project.file_name().unwrap().to_string_lossy().to_string();
    let row = labels::task_row("Sort the receipts", &name);
    wait_for(&mut h, |h| top_of(h, &row).is_some());
    click(&mut h, &row);
    let cards = [
        "THIS TASK",
        "Conversation memory",
        "Steps",
        "CHANGES SO FAR",
        "PERMISSION",
    ];
    let all = wait_for(&mut h, |h| cards.iter().all(|c| top_of(h, c).is_some()));
    let at: Vec<Option<f32>> = cards.iter().map(|c| top_of(&h, c)).collect();
    assert!(
        all && at.windows(2).all(|w| w[0] < w[1]),
        "the side panel's cards aren't in the order {cards:?} (tops: {at:?})\n{}",
        screen_text(&h)
    );
    close(h);
}

/// A fresh task's side panel has no placeholder cards: no "Conversation
/// memory" before there is any, no "Steps" without a checklist, and no
/// "steps ran on this PC" line.
#[test]
fn a_fresh_task_shows_no_placeholder_in_the_side_panel() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.clone();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "details": true,
    }));
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(
        "t1".into(),
        "Sort the receipts".into(),
        project.display().to_string(),
        0,
    );
    t.entries = vec![
        Entry::You("Sort the receipts".into()),
        Entry::Claude("Done.".into()),
    ];
    t.status = Status::Done;
    store.save(&t).unwrap();
    home.tripwire();
    let mut h = home.open();
    let name = project.file_name().unwrap().to_string_lossy().to_string();
    let row = labels::task_row("Sort the receipts", &name);
    wait_for(&mut h, |h| top_of(h, &row).is_some());
    click(&mut h, &row);
    assert!(
        wait_for(&mut h, |h| top_of(h, "THIS TASK").is_some()),
        "no side panel\n{}",
        screen_text(&h)
    );
    for _ in 0..10 {
        h.step();
    }
    for placeholder in [
        "Conversation memory",
        "Shown after the first reply",
        "Steps",
        "checklist appears here",
        "steps ran on this PC",
    ] {
        assert!(
            !shows(&h, placeholder),
            "a placeholder is shown: {placeholder:?}\n{}",
            screen_text(&h)
        );
    }
    close(h);
}

/// With the top bar gone, Settings and the weekly use sit at the foot of the
/// left side, below the tasks.
#[test]
fn sidebar_foot_has_settings_and_weekly_use() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    home.tripwire();
    let mut h = home.open();
    start_task(&mut h, "Say hello");
    let weekly = |h: &egui_kittest::Harness<'static, station_ui::App>| {
        h.query_all_by(|n| {
            n.label().is_some_and(|l| l.contains("% of weekly limit"))
                || n.value().is_some_and(|v| v.contains("% of weekly limit"))
        })
        .next()
        .map(|n| n.rect().min.y)
    };
    let shown = wait_for(&mut h, |h| weekly(h).is_some() && has_button(h, "Settings"));
    let (settings, new_task, use_) = (
        top_of(&h, "Settings"),
        top_of(&h, "+  New task"),
        weekly(&h),
    );
    assert!(
        shown
            && new_task < settings
            && (settings.unwrap_or(0.0) - use_.unwrap_or(-99.0)).abs() < 24.0,
        "Settings and the weekly use aren't together at the foot of the left side \
         (New task at {new_task:?}, Settings at {settings:?}, weekly use at {use_:?})\n{}",
        screen_text(&h)
    );
    assert!(
        !shows(&h, "Mímir"),
        "the top bar's title is still on screen\n{}",
        screen_text(&h)
    );
    close(h);
}

/// The side list keeps a folder short: five one-line rows, the rest under
/// "Show N older", and a task that needs you always in view and first.
#[test]
fn side_list_rows_are_one_line_and_older_tasks_fold() {
    let Some(home) = TestHome::new() else { return };
    let alpha = home.root.join("alpha");
    std::fs::create_dir_all(&alpha).unwrap();
    home.write_settings(serde_json::json!({
        "projects": [alpha],
        "last_project": alpha,
    }));
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let titles: Vec<String> = (1..=8).map(|n| format!("Job number {n}")).collect();
    for (n, title) in titles.iter().enumerate() {
        let mut t = Task::new(
            format!("t{n}"),
            title.clone(),
            alpha.display().to_string(),
            n as i64,
        );
        t.entries = vec![Entry::You(title.clone()), Entry::Claude("Done.".into())];
        // The oldest one waits for an answer.
        t.status = if n == 0 {
            Status::NeedsYou
        } else {
            Status::Done
        };
        store.save(&t).unwrap();
    }
    home.tripwire();
    let mut h = home.open();
    let row = |title: &str| labels::task_row(title, "alpha");
    let shown = |h: &egui_kittest::Harness<'static, station_ui::App>| {
        titles
            .iter()
            .filter(|t| top_of(h, &row(t)).is_some())
            .count()
    };
    let listed = wait_for(&mut h, |h| shown(h) > 0);
    assert!(listed, "no task is listed\n{}", screen_text(&h));
    assert_eq!(
        shown(&h),
        5,
        "a folder with 8 tasks should show 5 rows\n{}",
        screen_text(&h)
    );
    assert!(
        has_button(&h, "Show 3 older"),
        "the other three aren't under \"Show 3 older\"\n{}",
        screen_text(&h)
    );
    // The waiting task is the oldest, yet it is in view and first.
    let waiting = top_of(&h, &row("Job number 1"));
    let newest = top_of(&h, &row("Job number 8"));
    assert!(
        matches!((waiting, newest), (Some(w), Some(n)) if w < n),
        "the task that needs you isn't first\n{}",
        screen_text(&h)
    );
    // One line each: 30 high, 32 from one row to the next.
    let rect = |h: &egui_kittest::Harness<'static, station_ui::App>, title: &str| {
        h.query_all_by(|n| n.label().as_deref() == Some(row(title).as_str()))
            .next()
            .map(|n| n.rect())
            .unwrap()
    };
    let (first, second) = (rect(&h, "Job number 1"), rect(&h, "Job number 8"));
    assert!(
        (first.height() - 30.0).abs() < 0.5 && (second.min.y - first.min.y - 32.0).abs() < 0.5,
        "rows aren't 30 high and 32 apart: {first:?} then {second:?}"
    );
    click(&mut h, "Show 3 older");
    let all = wait_for(&mut h, |h| shown(h) == 8);
    assert!(
        all && has_button(&h, "Show fewer"),
        "\"Show 3 older\" didn't show every task\n{}",
        screen_text(&h)
    );
    close(h);
}
