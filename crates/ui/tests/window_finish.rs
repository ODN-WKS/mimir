//! Small window fixes from the audit: the rename box (PCW-19), voice
//! recordings that never stay on the disk (PCW-22), a free model's words
//! (PCW-12, PCK-03), and the window reading the PC's one truth (P10a).

mod common;
use common::*;
use eframe::egui::accesskit::Role;
use egui_kittest::kittest::Queryable;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

/// A made-up Ollama with one free model.
fn fake_ollama(model: &str) {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    let model = model.to_string();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = format!("{{\"models\":[{{\"name\":\"{model}\"}}]}}");
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
}

/// Every file with this name under `dir`.
fn find(dir: &Path, name: &str) -> Vec<PathBuf> {
    let mut out = vec![];
    let Ok(rd) = std::fs::read_dir(dir) else {
        return out;
    };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() {
            out.extend(find(&p, name));
        } else if p.file_name().is_some_and(|n| n == name) {
            out.push(p);
        }
    }
    out
}

/// Puts a made-up `pw-record` first on the path: it writes its file (the
/// last argument), then ends at once (`quick`) or keeps "recording".
fn fake_recorder(home: &TestHome, quick: bool) {
    let bin = home.root.join("fakebin");
    std::fs::create_dir_all(&bin).unwrap();
    let script = bin.join("pw-record");
    std::fs::write(
        &script,
        format!(
            "#!/bin/sh\nfor last; do :; done\necho voice > \"$last\"\n{}",
            if quick { "exit 1" } else { "exec sleep 600" }
        ),
    )
    .unwrap();
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
    let path = std::env::var("PATH").unwrap_or_default();
    set_env("PATH", format!("{}:{path}", bin.display()));
}

fn wait_until(secs: u64, mut done: impl FnMut() -> bool) -> bool {
    let until = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < until {
        if done() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    done()
}

/// PCW-22: a recording that fails (no microphone) leaves no file behind.
#[test]
fn a_recording_that_failed_leaves_no_file_on_the_disk() {
    let Some(home) = TestHome::new() else { return };
    fake_recorder(&home, true);
    let ctx = eframe::egui::Context::default();
    station_ui::places::listen_toggle(&ctx);
    // The recorder wrote its file, then ended.
    assert!(
        wait_until(5, || !find(&home.root, "listening.wav").is_empty()),
        "the recording never started"
    );
    assert!(
        wait_until(5, || matches!(
            station_ui::places::listen_status(),
            station_ui::places::ListenStatus::Failed(_)
        )),
        "the failed recording wasn't noticed"
    );
    assert_eq!(
        find(&home.root, "listening.wav"),
        Vec::<PathBuf>::new(),
        "the recording stayed on the disk after it failed"
    );
}

/// PCW-22: closing the window while recording stops the recorder and leaves
/// no file.
#[test]
fn closing_the_window_while_recording_leaves_no_file() {
    let Some(home) = TestHome::new() else { return };
    fake_recorder(&home, false);
    let ctx = eframe::egui::Context::default();
    station_ui::places::listen_toggle(&ctx);
    assert!(
        wait_until(5, || !find(&home.root, "listening.wav").is_empty()),
        "the recording never started"
    );
    // The window closes (what its exit does).
    station_ui::places::listen_cleanup();
    assert_eq!(
        find(&home.root, "listening.wav"),
        Vec::<PathBuf>::new(),
        "the recording stayed on the disk after the window closed"
    );
}

/// PCW-22: the window's own exit (not just a call to the cleanup) stops a
/// recording and leaves no file.
#[test]
fn the_window_closing_while_recording_leaves_no_file() {
    let Some(home) = TestHome::new() else { return };
    fake_recorder(&home, false);
    let h = home.open();
    let ctx = eframe::egui::Context::default();
    station_ui::places::listen_toggle(&ctx);
    assert!(
        wait_until(5, || !find(&home.root, "listening.wav").is_empty()),
        "the recording never started"
    );
    close(h);
    assert_eq!(
        find(&home.root, "listening.wav"),
        Vec::<PathBuf>::new(),
        "closing the window left the recording on the disk"
    );
}

/// PCW-19: the rename box takes the cursor once, and a click in the message
/// box ends it (the message box then keeps the cursor).
#[test]
fn the_rename_box_takes_focus_once_and_ends_on_blur() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt is sorted")
            && has_button(h, station_ui::labels::PILL_MENU)),
        "the task didn't finish\n{}",
        screen_text(&h)
    );
    // ⋯ › Rename.
    click(&mut h, station_ui::labels::PILL_MENU);
    click(&mut h, "Rename");
    // The rename box holds the task's title (the search box is the other
    // one-line box on the screen).
    let rename_box = |h: &egui_kittest::Harness<'static, station_ui::App>| {
        h.query_all_by_role(Role::TextInput)
            .any(|n| n.value().is_some_and(|v| v.starts_with("Tidy up notes")))
    };
    assert!(
        wait_for(&mut h, |h| rename_box(h)),
        "no rename box\n{}",
        screen_text(&h)
    );
    // It stays while nothing else is clicked, with the cursor in it.
    for _ in 0..10 {
        h.step();
    }
    assert!(rename_box(&h), "the rename box went by itself");
    // Click the message box: the rename ends, the message box has the cursor.
    h.query_all_by_role(Role::MultilineTextInput)
        .next()
        .expect("no message box")
        .click();
    for _ in 0..5 {
        h.step();
    }
    assert!(
        !rename_box(&h),
        "the rename box stayed after a click elsewhere\n{}",
        screen_text(&h)
    );
    assert!(
        h.query_all_by_role(Role::MultilineTextInput)
            .next()
            .is_some_and(|n| n.is_focused()),
        "the message box didn't keep the cursor"
    );
    close(h);
}

/// PCW-12, PCK-03: on a free model the window says "The free model is
/// working…", offers no Effort picker, and shows no cost or token line.
#[test]
fn a_free_model_task_has_no_claude_words_effort_or_cost() {
    let Some(home) = TestHome::new() else { return };
    let model = "qwen3.6:35b-a3b-coding";
    fake_ollama(model);
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "defaults": { (project.clone()): { "model": format!("ollama:{model}") } },
    }));
    home.play("run-1.jsonl", "near-limit.jsonl");
    // Held mid-reply, so it is working while the window is looked at.
    home.hold_replies();
    home.let_through(1);
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "The free model is working…")),
        "no free-model working line\n{}",
        screen_text(&h)
    );
    assert!(
        !shows(&h, "Working on it"),
        "Claude's name on a free model\n{}",
        screen_text(&h)
    );
    assert!(
        !shows(&h, "Effort: Medium"),
        "an Effort picker on a free model\n{}",
        screen_text(&h)
    );
    // R5d: the chip is there so the box looks the same, but it is off.
    assert!(
        shows(&h, "Effort: —"),
        "no disabled effort chip\n{}",
        screen_text(&h)
    );
    // The task ends: still no cost or token line for a free model.
    home.let_through(100);
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt is sorted")),
        "the task didn't finish\n{}",
        screen_text(&h)
    );
    for _ in 0..10 {
        h.step();
    }
    assert!(
        !shows(&h, "Estimated cost") && !shows(&h, "tokens used"),
        "a cost or token line on a free model\n{}",
        screen_text(&h)
    );
    close(h);
}

/// On Claude the same task shows Claude's words and the Effort picker (the
/// free-model check above would pass for the wrong reason without it).
#[test]
fn a_claude_task_keeps_its_words_and_effort() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    home.hold_replies();
    home.let_through(1);
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "Working on it…")),
        "no working line\n{}",
        screen_text(&h)
    );
    assert!(shows(&h, "Effort"), "no Effort picker\n{}", screen_text(&h));
    home.let_through(100);
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    close(h);
}

/// P10a: the foot's "N working" and the row's state come from the PC's
/// truth: the same numbers the truth carries.
#[test]
fn the_window_shows_what_the_pcs_truth_says() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "near-limit.jsonl");
    home.hold_replies();
    home.let_through(1);
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "Working")),
        "the task never showed as working\n{}",
        screen_text(&h)
    );
    // The window's own client has the truth and agrees.
    let mut st = home.service();
    assert!(
        service_until(&mut st, |s| s
            .truth
            .as_ref()
            .is_some_and(|t| t.counts.working == 1)),
        "the truth never said 1 working"
    );
    assert_eq!(st.counts().0, 1);
    home.let_through(100);
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt is sorted")),
        "the task didn't finish\n{}",
        screen_text(&h)
    );
    // Done in the truth, and in the window within a moment.
    assert!(
        wait_for(&mut h, |h| shows(h, "Needs you") && !shows(h, "Working")),
        "the Needs you row or the project still counts the task\n{}",
        screen_text(&h)
    );
    assert!(service_until(&mut st, |s| s
        .truth
        .as_ref()
        .is_some_and(|t| t.counts.working == 0)));
    close(h);
}

/// PCK-08: Claude Code's "[Request interrupted by user]" shows as a quiet
/// "Stopped", not as a message the owner wrote.
#[test]
fn the_stop_marker_shows_as_stopped_not_as_a_message() {
    use mimir_core::store::Store;
    use mimir_core::task::{Entry, Status, Task};
    let Some(home) = TestHome::new() else { return };
    let project = home.project.clone();
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(
        "t1".into(),
        "Count to ten".into(),
        project.display().to_string(),
        0,
    );
    t.entries = vec![
        Entry::You("Count to ten".into()),
        Entry::Claude("One, two,".into()),
        Entry::You("[Request interrupted by user]".into()),
    ];
    t.status = Status::Done;
    store.save(&t).unwrap();
    home.tripwire();
    let mut h = home.open();
    let name = project.file_name().unwrap().to_string_lossy().to_string();
    let row = station_ui::labels::task_row("Count to ten", &name);
    wait_for(&mut h, |h| shows(h, &row));
    click(&mut h, &row);
    assert!(
        wait_for(&mut h, |h| shows(h, "One, two,")),
        "the task didn't open\n{}",
        screen_text(&h)
    );
    for _ in 0..5 {
        h.step();
    }
    assert!(
        !shows(&h, "Request interrupted"),
        "the raw marker is shown\n{}",
        screen_text(&h)
    );
    assert!(
        h.query_all_by_label(station_ui::labels::STOPPED)
            .next()
            .is_some(),
        "no Stopped line\n{}",
        screen_text(&h)
    );
    close(h);
}
