//! Window checks for putting conversations where they belong: Move to… on
//! a task's menu carries the task, conversation and all, to another
//! project, and waits while Claude works on it.

mod common;
use common::*;
use eframe::egui;
use egui_kittest::Harness;
use egui_kittest::kittest::{NodeT, Queryable};
use station_ui::{App, labels};

/// The conversation `live.jsonl` starts.
const LIVE_SESSION: &str = "5f1c2e7a-6b3d-4c8e-9a10-11ee0c0f1a2b";

fn frames(h: &mut Harness<'static, App>) {
    for _ in 0..12 {
        h.step();
    }
}

/// The first task row in the side list, by its spoken name.
fn task_row(h: &Harness<'static, App>) -> String {
    h.query_all_by(|n| {
        n.role() == egui::accesskit::Role::Button
            && n.label().is_some_and(|l| l.starts_with("Task "))
    })
    .next()
    .and_then(|n| n.accesskit_node().label().map(|l| l.to_string()))
    .expect("no task row in the side list")
}

/// Opens Move to… from the task row's right-click menu.
fn open_move_to(h: &mut Harness<'static, App>) {
    scroll_side_list_down(h);
    let row = task_row(h);
    h.get_by_label(&row).click_secondary();
    frames(h);
    click(h, labels::MOVE_TO);
    frames(h);
    assert!(
        shows(h, labels::MOVE_TO_TITLE),
        "Move to… opened no picker\n{}",
        screen_text(h)
    );
}

/// Whether the widget with these words is wholly inside the window.
fn inside(h: &Harness<'static, App>, words: &str) -> bool {
    let screen = h.ctx.content_rect();
    h.query_all_by(|n| n.label().is_some_and(|l| l.contains(words)))
        .all(|n| screen.expand(0.5).contains_rect(n.rect()))
}

/// The project folder the store has for each task.
fn task_projects(home: &TestHome) -> Vec<String> {
    let dir = home.root.join(".local/share/mimir/tasks");
    std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| std::fs::read_to_string(e.path()).ok())
        .filter_map(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
        .filter_map(|v| v["project"].as_str().map(str::to_string))
        .collect()
}

/// A stand-in for `claude` started in a terminal in `folder`; it stops
/// when dropped.
struct Terminal(std::process::Child);

impl Drop for Terminal {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn terminal_claude(home: &TestHome, folder: &std::path::Path) -> Terminal {
    let bin = home.root.join("bin");
    std::fs::create_dir_all(&bin).unwrap();
    let claude = bin.join("claude");
    if !claude.exists() {
        std::os::unix::fs::symlink("/bin/sleep", &claude).unwrap();
    }
    Terminal(
        std::process::Command::new(&claude)
            .arg("60")
            .current_dir(folder)
            .spawn()
            .unwrap(),
    )
}

/// Claude Code's history of a conversation in a folder, as it writes it.
fn write_history(home: &TestHome, folder: &std::path::Path, session: &str) {
    let dir = history(home, folder);
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(format!("{session}.jsonl"));
    std::fs::write(&path, "{}\n").unwrap();
    // Written an hour ago: nothing is on it any more.
    let hour_ago = std::time::SystemTime::now() - std::time::Duration::from_secs(3600);
    std::fs::File::options()
        .write(true)
        .open(&path)
        .unwrap()
        .set_modified(hour_ago)
        .unwrap();
}

fn history(home: &TestHome, project: &std::path::Path) -> std::path::PathBuf {
    home.root
        .join(".claude/projects")
        .join(folder_safe(&project.display().to_string()))
}

#[test]
fn move_to_carries_a_task_and_its_conversation_to_another_project() {
    let Some(home) = TestHome::new() else { return };
    let other = home.root.join("Other-place");
    std::fs::create_dir_all(&other).unwrap();
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project, other.display().to_string()],
        "last_project": project,
    }));
    home.play("run-1.jsonl", "live.jsonl");
    home.play("resume.jsonl", "resume.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    assert!(wait_for(&mut h, |h| has_button(h, labels::SEND)));
    // With no history of the conversation to carry, nothing moves.
    open_move_to(&mut h);
    click(&mut h, "Other-place");
    assert!(
        wait_for(&mut h, |h| shows(
            h,
            "history of this conversation wasn't found"
        )),
        "a move with nothing to carry wasn't refused\n{}",
        screen_text(&h)
    );
    assert_eq!(task_projects(&home), vec![project.clone()]);
    // Claude Code's history of the conversation, where it keeps it.
    let old = history(&home, &home.project);
    std::fs::create_dir_all(old.join(LIVE_SESSION)).unwrap();
    std::fs::write(old.join(format!("{LIVE_SESSION}.jsonl")), "{}\n").unwrap();
    std::fs::write(old.join(LIVE_SESSION).join("part.json"), "{}").unwrap();

    // The picker fits at 100% and 150%, and Cancel changes nothing.
    for zoom in [1.0, 1.5] {
        h.ctx.set_zoom_factor(zoom);
        frames(&mut h);
        open_move_to(&mut h);
        assert!(
            has_button(&h, "Other-place") && inside(&h, "Other-place"),
            "at {}%: the other project isn't offered whole\n{}",
            zoom * 100.0,
            screen_text(&h)
        );
        click(&mut h, "Cancel");
        frames(&mut h);
        assert!(!shows(&h, labels::MOVE_TO_TITLE));
    }
    assert_eq!(task_projects(&home), vec![project.clone()]);

    // A Claude Code in a terminal in that folder (`claude --continue`) may
    // be on it: nothing moves while it runs.
    let terminal = terminal_claude(&home, &home.project);
    open_move_to(&mut h);
    click(&mut h, "Other-place");
    assert!(
        wait_for(&mut h, |h| shows(h, "still open in a terminal")),
        "a move under a terminal's Claude wasn't refused\n{}",
        screen_text(&h)
    );
    assert_eq!(task_projects(&home), vec![project.clone()]);
    assert!(old.join(format!("{LIVE_SESSION}.jsonl")).exists());
    drop(terminal);

    open_move_to(&mut h);
    click(&mut h, "Other-place");
    let other_s = other.display().to_string();
    let moved = wait_for(&mut h, |_| task_projects(&home) == vec![other_s.clone()]);
    assert!(moved, "the task stayed in {:?}", task_projects(&home));
    let new = history(&home, &other);
    assert!(
        new.join(format!("{LIVE_SESSION}.jsonl")).exists()
            && new.join(LIVE_SESSION).join("part.json").exists(),
        "the conversation's history didn't go with it"
    );
    assert!(!old.join(format!("{LIVE_SESSION}.jsonl")).exists());
    assert!(
        wait_for(&mut h, |h| shows(h, "Moved from project to Other-place")),
        "the task doesn't say where it came from\n{}",
        screen_text(&h)
    );

    // The next message carries the same conversation on, in the new folder.
    type_message(
        &mut h,
        "What file did you create earlier and what is in it? One line.",
    );
    click(&mut h, labels::SEND);
    let replied = wait_for(&mut h, |h| shows(h, "I created"));
    let runs = home.conversations();
    let resumed = runs.last().is_some_and(|a| {
        a.windows(2)
            .any(|w| w[0] == "--resume" && w[1] == LIVE_SESSION)
    });
    let folders = home.claude_folders();
    assert!(
        replied && resumed && folders.last() == Some(&other_s),
        "the moved task didn't carry on there\nruns: {runs:?}\nfolders: {folders:?}"
    );
    close(h);
}

#[test]
fn a_working_task_moves_when_it_stops() {
    let Some(home) = TestHome::new() else { return };
    let other = home.root.join("Other-place");
    std::fs::create_dir_all(&other).unwrap();
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project, other.display().to_string()],
        "last_project": project,
    }));
    home.play("run-1.jsonl", "live.jsonl");
    home.hold_replies();
    home.let_through(1);
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::SEND_NOW)),
        "the task never showed as working\n{}",
        screen_text(&h)
    );
    open_move_to(&mut h);
    assert!(
        shows(&h, labels::MOVE_WHEN_STOPPED) && !has_button(&h, "Other-place"),
        "a working task was offered a move\n{}",
        screen_text(&h)
    );
    click(&mut h, "Cancel");
    home.let_through(100);
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    assert_eq!(task_projects(&home), vec![project]);
    close(h);
}

/// The project folders in the settings file.
fn listed(home: &TestHome) -> Vec<String> {
    std::fs::read_to_string(home.settings_path())
        .ok()
        .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
        .and_then(|v| {
            v["projects"].as_array().map(|a| {
                a.iter()
                    .filter_map(|p| p.as_str().map(str::to_string))
                    .collect()
            })
        })
        .unwrap_or_default()
}

#[test]
fn a_stand_in_folder_merges_into_its_project_and_unlisted_ones_are_added() {
    let Some(home) = TestHome::new() else { return };
    let real = home.root.join("Projects/Budgetapp");
    let stand_in = home.root.join("Documents/BudgetApp");
    let ally = home.root.join("Projects/NotesApp");
    for d in [real.join(".git"), stand_in.clone(), ally.join(".git")] {
        std::fs::create_dir_all(d).unwrap();
    }
    let (real_s, stand_s) = (real.display().to_string(), stand_in.display().to_string());
    home.write_settings(serde_json::json!({
        "projects": [real_s, stand_s],
        "last_project": stand_s,
    }));
    home.play("run-1.jsonl", "live.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    assert!(wait_for(&mut h, |h| has_button(h, labels::SEND)));
    assert_eq!(task_projects(&home), vec![stand_s.clone()]);
    write_history(&home, &stand_in, LIVE_SESSION);

    // A git project beside the others, not listed: offered, and added.
    let offer = labels::add_projects(1);
    assert!(
        wait_for(&mut h, |h| has_button(h, &offer)),
        "the unlisted project isn't offered\n{}",
        screen_text(&h)
    );
    click(&mut h, &offer);
    frames(&mut h);
    assert!(shows(&h, labels::ADD_PROJECTS_TITLE) && shows(&h, "NotesApp"));
    click(&mut h, "Add 1 project");
    let ally_s = ally.display().to_string();
    assert!(
        wait_for(&mut h, |_| listed(&home).contains(&ally_s)),
        "NotesApp wasn't added: {:?}",
        listed(&home)
    );

    // Merge into…, on the stand-in's menu, fits at 100% and 150%.
    let folder = labels::folder_row("BudgetApp");
    for zoom in [1.0, 1.5] {
        h.ctx.set_zoom_factor(zoom);
        frames(&mut h);
        h.get_by_label(&folder).click_secondary();
        frames(&mut h);
        click(&mut h, labels::MERGE_INTO);
        frames(&mut h);
        assert!(
            shows(&h, labels::MERGE_TITLE)
                && has_button(&h, "Budgetapp")
                && inside(&h, "Budgetapp"),
            "at {}%: Merge into… doesn't offer Budgetapp whole\n{}",
            zoom * 100.0,
            screen_text(&h)
        );
        click(&mut h, "Cancel");
        frames(&mut h);
    }
    // The project already has this conversation's history: nothing moves,
    // and the stand-in stays.
    write_history(&home, &real, LIVE_SESSION);
    h.get_by_label(&folder).click_secondary();
    frames(&mut h);
    click(&mut h, labels::MERGE_INTO);
    frames(&mut h);
    click(&mut h, "Budgetapp");
    assert!(
        wait_for(&mut h, |h| shows(h, "already has this conversation")),
        "a merge that can't finish wasn't refused\n{}",
        screen_text(&h)
    );
    assert_eq!(task_projects(&home), vec![stand_s.clone()]);
    assert!(listed(&home).contains(&stand_s));
    std::fs::remove_file(history(&home, &real).join(format!("{LIVE_SESSION}.jsonl"))).unwrap();

    h.get_by_label(&folder).click_secondary();
    frames(&mut h);
    click(&mut h, labels::MERGE_INTO);
    frames(&mut h);
    click(&mut h, "Budgetapp");
    let merged = wait_for(&mut h, |_| {
        task_projects(&home) == vec![real_s.clone()] && !listed(&home).contains(&stand_s)
    });
    assert!(
        merged,
        "the stand-in wasn't merged: tasks in {:?}, listed {:?}",
        task_projects(&home),
        listed(&home)
    );
    assert!(wait_for(&mut h, |h| !has_label(h, &folder)));
    close(h);
}

/// The New task page's project picker, as it reads now, and where it is.
fn picked_project(h: &Harness<'static, App>) -> Option<(String, egui::Rect)> {
    h.query_all_by(|n| n.role() == egui::accesskit::Role::ComboBox)
        .map(|n| {
            let a = n.accesskit_node();
            let t = a
                .value()
                .or_else(|| a.label())
                .unwrap_or_default()
                .to_string();
            (t, n.rect())
        })
        .find(|(t, _)| t.contains("Tune") || t.contains("project") || t.contains("Chats"))
}

#[test]
fn new_task_picks_the_project_the_request_points_to() {
    let Some(home) = TestHome::new() else { return };
    let tune = home.root.join("Tune-Sorter");
    std::fs::create_dir_all(&tune).unwrap();
    std::fs::write(tune.join("README.md"), "# Tune Sorter: family calls\n").unwrap();
    let (project, tune_s) = (
        home.project.display().to_string(),
        tune.display().to_string(),
    );
    home.write_settings(serde_json::json!({
        "projects": [project, tune_s],
        "last_project": project,
    }));
    home.play("run-1.jsonl", "live.jsonl");
    for zoom in [1.0, 1.5] {
        let mut h = home.open();
        h.ctx.set_zoom_factor(zoom);
        frames(&mut h);
        // At first the last project used.
        let (shown, _) = picked_project(&h).expect("no project picker");
        assert_eq!(shown, "project");
        // Writing about another project picks it, on the same one row.
        type_message(&mut h, "The Tune-Sorter calls drop on cell signal");
        frames(&mut h);
        let (shown, rect) = picked_project(&h).expect("no project picker");
        assert_eq!(shown, "Tune-Sorter", "at {}%", zoom * 100.0);
        assert!(h.ctx.content_rect().contains_rect(rect));
        if zoom == 1.0 {
            // Picked by hand, it stays whatever is written next.
            click_combo(&mut h, rect);
            click(&mut h, "project");
            frames(&mut h);
            type_message(&mut h, " and Tune too");
            frames(&mut h);
            assert_eq!(picked_project(&h).unwrap().0, "project");
            close(h);
            continue;
        }
        click(&mut h, labels::START_TASK);
        assert!(
            wait_for(&mut h, |_| task_projects(&home) == vec![tune_s.clone()]),
            "the task started in {:?}",
            task_projects(&home)
        );
        close(h);
    }
}

fn click_combo(h: &mut Harness<'static, App>, rect: egui::Rect) {
    h.hover_at(rect.center());
    frames(h);
    for pressed in [true, false] {
        h.event(egui::Event::PointerButton {
            pos: rect.center(),
            button: egui::PointerButton::Primary,
            pressed,
            modifiers: egui::Modifiers::NONE,
        });
        h.step();
    }
    frames(h);
}

/// A chat started in the window with no project goes to a project only
/// when it names one on purpose; everything else stays a chat, never a
/// guess and never General (Stage 1, 2.3).
#[test]
fn a_chat_with_no_project_goes_to_a_project_only_when_it_names_one() {
    let Some(home) = TestHome::new() else { return };
    let projects = home.root.join("Projects");
    let budgetapp = projects.join("Budgetapp");
    let tune = projects.join("Tune-Sorter");
    std::fs::create_dir_all(&budgetapp).unwrap();
    std::fs::create_dir_all(&tune).unwrap();
    std::fs::write(tune.join("README.md"), "# Tune Sorter: kids caller tune\n").unwrap();
    let chats = home.root.join(".local/share/mimir/chats");
    let s = |p: &std::path::Path| p.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [s(&home.project), s(&budgetapp), s(&tune)],
        "last_project": s(&chats),
    }));
    for n in 1..=6 {
        home.play(&format!("run-{n}.jsonl"), "live.jsonl");
    }
    let mut runs = 0;
    for zoom in [1.0, 1.5] {
        let mut h = home.open();
        h.ctx.set_zoom_factor(zoom);
        frames(&mut h);
        let mut chat = |h: &mut Harness<'static, App>, text: &str, line: Option<&str>| {
            click(h, "+  New task");
            frames(h);
            assert_eq!(picked_project(h).map(|p| p.0).as_deref(), Some("Chats"));
            type_message(h, text);
            click(h, labels::START_CHAT);
            runs += 1;
            let n = runs;
            assert!(
                wait_for(h, |h| home.conversations().len() >= n
                    && line.is_none_or(|l| shows(h, l))),
                "at {}%: {text:?} isn't shown as {line:?}\n{}",
                zoom * 100.0,
                screen_text(h)
            );
            if let Some(l) = line {
                assert!(inside(h, l), "at {}%: {l:?} is cut off", zoom * 100.0);
            }
            assert!(wait_for(h, |h| has_button(h, labels::SEND)));
        };
        // Named: filed there.
        chat(
            &mut h,
            "Budgetapp: the dock icon is blurry",
            Some("Filed under Budgetapp ·"),
        );
        assert!(has_label(&h, "change"));
        // Words that only point to a project: a chat, not a guess.
        chat(&mut h, "The kids tune looks blurry", None);
        assert!(!shows(&h, "not sure"));
        // Nothing fits: a chat, and no General is made.
        chat(&mut h, "Plan a weekend trip to Lisbon", None);
        assert!(!shows(&h, "Filed under General"));
        assert!(!projects.join("General").exists());
        close(h);
    }
    // No placement note was written, and nothing in the projects.
    assert!(!home.root.join(".local/share/mimir/placement").exists());
    assert_eq!(std::fs::read_dir(&tune).unwrap().count(), 1);
    // The chats ran in the chats folder.
    let folders = home.claude_folders();
    assert_eq!(
        folders.iter().filter(|f| f.starts_with(&s(&chats))).count(),
        4,
        "{folders:?}"
    );
}

/// A Claude Code session written in a folder, as a terminal leaves it
/// (last changed an hour ago, so nothing is on it any more).
fn old_session(home: &TestHome, folder: &std::path::Path, id: &str, said: &str) {
    let dir = history(home, folder);
    std::fs::create_dir_all(&dir).unwrap();
    let line = serde_json::json!({
        "type": "user",
        "cwd": folder.display().to_string(),
        "sessionId": id,
        "message": {"role": "user", "content": said},
    });
    let path = dir.join(format!("{id}.jsonl"));
    std::fs::write(&path, format!("{line}\n")).unwrap();
    let hour_ago = std::time::SystemTime::now() - std::time::Duration::from_secs(3600);
    std::fs::File::options()
        .write(true)
        .open(&path)
        .unwrap()
        .set_modified(hour_ago)
        .unwrap();
}

/// Every task in the store: (title, project, archived).
fn stored_tasks(home: &TestHome) -> Vec<(String, String, bool)> {
    let dir = home.root.join(".local/share/mimir/tasks");
    let mut v: Vec<(String, String, bool)> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| std::fs::read_to_string(e.path()).ok())
        .filter_map(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
        .map(|v| {
            (
                v["title"].as_str().unwrap_or_default().to_string(),
                v["project"].as_str().unwrap_or_default().to_string(),
                v["archived"].as_bool().unwrap_or(false),
            )
        })
        .collect();
    v.sort();
    v
}

#[test]
fn tidy_projects_shows_every_change_then_makes_it() {
    let Some(home) = TestHome::new() else { return };
    let projects = home.root.join("Projects");
    let station = projects.join("Garden-Planner");
    let budgetapp = projects.join("Budgetapp");
    let ally = projects.join("NotesApp");
    let stand_in = home.root.join("Documents/BudgetApp");
    for d in [
        station.join(".git"),
        budgetapp.join(".git"),
        ally.join(".git"),
    ] {
        std::fs::create_dir_all(d).unwrap();
    }
    std::fs::create_dir_all(&stand_in).unwrap();
    let s = |p: &std::path::Path| p.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [s(&station), s(&stand_in), s(&home.root)],
        "last_project": s(&stand_in),
    }));
    old_session(
        &home,
        &home.root,
        "0b6c39a1-1111-4c8e-9a10-11ee0c0f1a2b",
        "This is a fresh SteamOS install dedicated to development, set it up",
    );
    old_session(
        &home,
        &station,
        "0b6c39a1-2222-4c8e-9a10-11ee0c0f1a2b",
        "Test",
    );
    // One a terminal is still on (changed just now): it waits.
    old_session(
        &home,
        &home.root,
        "0b6c39a1-3333-4c8e-9a10-11ee0c0f1a2b",
        "Keep this one going while I look at the setup of this PC",
    );
    let busy = history(&home, &home.root).join("0b6c39a1-3333-4c8e-9a10-11ee0c0f1a2b.jsonl");
    std::fs::File::options()
        .write(true)
        .open(&busy)
        .unwrap()
        .set_modified(std::time::SystemTime::now())
        .unwrap();
    home.play("run-1.jsonl", "live.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    assert!(wait_for(&mut h, |h| has_button(h, labels::SEND)));

    write_history(&home, &stand_in, LIVE_SESSION);
    for zoom in [1.0, 1.5] {
        h.ctx.set_zoom_factor(zoom);
        frames(&mut h);
        if has_button(&h, "Settings") {
            click(&mut h, "Settings");
        }
        click(&mut h, "Storage");
        click(&mut h, labels::TIDY);
        frames(&mut h);
        let go = labels::tidy_go(8);
        assert!(
            shows(&h, labels::TIDY_TITLE) && has_button(&h, &go),
            "at {}%: the tidy doesn't show its 8 changes\n{}",
            zoom * 100.0,
            screen_text(&h)
        );
        for words in [
            "~/Projects/NotesApp",
            "~/Documents/BudgetApp",
            "Budgetapp (it leaves the list)",
            "leaves the list",
            "A test chat.",
            "Started in the home folder",
            "when Mímir stops",
        ] {
            assert!(
                shows(&h, words),
                "at {}%: no {words:?}\n{}",
                zoom * 100.0,
                screen_text(&h)
            );
        }
        assert!(inside(&h, &go), "at {}%: {go:?} is cut off", zoom * 100.0);
        if zoom == 1.0 {
            click(&mut h, "Cancel");
            frames(&mut h);
            assert!(!has_button(&h, &go));
            continue;
        }
        click(&mut h, &go);
    }
    let random = s(&projects.join("General"));
    // P17: the task started above is named by its start time.
    let [named, named_before] = time_names();
    let want = [
        ("Test".to_string(), s(&station), true),
        (
            "This is a fresh SteamOS install dedicated to development, set it up".to_string(),
            random.clone(),
            true,
        ),
        // One short message: a test chat, archived as it goes.
        (named, s(&budgetapp), true),
        (named_before, s(&budgetapp), true),
    ];
    let tidied = wait_for(&mut h, |_| {
        let waiting = stored_tasks(&home)
            .iter()
            .any(|(t, p, a)| t.starts_with("Keep this one") && p == &s(&home.root) && !a);
        let got = stored_tasks(&home);
        waiting
            && got.len() == 4
            && got
                .iter()
                .filter(|(t, _, _)| !t.starts_with("Keep"))
                .all(|(t, p, a)| {
                    want.iter().any(|(wt, wp, wa)| {
                        t.starts_with(&wt[..wt.len().min(20)]) && p == wp && a == wa
                    })
                })
    });
    assert!(tidied, "after the tidy: {:?}", stored_tasks(&home));
    // Mímir's own task keeps its conversation as it was (its
    // history file here is nearly empty), with a note of the move.
    let kept = std::fs::read_dir(home.root.join(".local/share/mimir/tasks"))
        .unwrap()
        .flatten()
        .filter_map(|e| std::fs::read_to_string(e.path()).ok())
        .find(|t| t.contains("Tidy up notes.txt."))
        .unwrap();
    assert!(
        kept.contains("notes.txt is sorted") && kept.contains("by Tidy projects"),
        "the tidy lost the task's own history"
    );
    let listed = listed(&home);
    assert!(
        listed.contains(&s(&ally))
            && listed.contains(&s(&budgetapp))
            && listed.contains(&random)
            && !listed.contains(&s(&stand_in))
            && !listed.contains(&s(&home.root)),
        "listed after the tidy: {listed:?}"
    );
    // The one still in use waits, its history where it was.
    assert!(busy.exists());
    assert!(
        home.root
            .join(".local/share/mimir/tidy-waiting.json")
            .exists()
    );
    assert!(
        shows(
            &h,
            "Tidied: 7 changes made; 1 wait until Mímir stops working on them."
        ),
        "{}",
        screen_text(&h)
    );
    close(h);
}
