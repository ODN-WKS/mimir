//! 0.07 hunt fixes, PC truth (F1): a followed session whose program ended
//! stops reading "running", and where the words go is said the same way
//! before sending and after.

mod common;
use common::*;
use station_ui::labels;
use std::path::PathBuf;

const SESSION: &str = "7b2e9c41-3d5a-4f60-9a1b-2c3d4e5f6a7b";

/// An outside session whose last line is a message nobody answered (it
/// reads as working), written `ago` seconds back, with no program on it.
fn ended_outside_session(home: &TestHome, ago: i64) -> PathBuf {
    let project = home.project.display().to_string();
    let quoted = serde_json::to_string(&project).unwrap();
    let folder = home
        .root
        .join(".claude/projects")
        .join(mimir_core::sessions::folder_name(&home.project));
    std::fs::create_dir_all(&folder).unwrap();
    let file = folder.join(format!("{SESSION}.jsonl"));
    let at = iso(ago);
    std::fs::write(
        &file,
        format!(
            r#"{{"type":"user","message":{{"role":"user","content":"Sort the seed packets"}},"uuid":"u1","cwd":{quoted},"sessionId":"{SESSION}","timestamp":"{at}"}}"#
        ) + "\n",
    )
    .unwrap();
    let old = std::time::SystemTime::now() - std::time::Duration::from_secs(ago as u64);
    std::fs::File::options()
        .write(true)
        .open(&file)
        .unwrap()
        .set_modified(old)
        .unwrap();
    let store = mimir_core::store::Store::new(mimir_core::store::Store::default_dir()).unwrap();
    let mut t = mimir_core::task::Task::new(
        "1700000000-2-1".into(),
        "Sort the seed packets".into(),
        project,
        1_700_000_000,
    );
    t.session_id = Some(SESSION.into());
    t.status = mimir_core::task::Status::Done;
    store.save(&t).unwrap();
    file
}

/// P1 / T25: the program ended and the conversation went quiet: within
/// seconds nothing says running or live, also when the service has just
/// started (the saved state said working).
#[test]
fn a_followed_session_whose_program_ended_stops_reading_running() {
    let Some(home) = TestHome::new() else { return };
    ended_outside_session(&home, 40);
    let mut h = home.open();
    assert!(
        wait_for(&mut h, |h| shows(h, "Sort the seed packets")),
        "not listed\n{}",
        screen_text(&h)
    );
    // The service's scans take a few seconds; about 12 s is the most.
    let end = std::time::Instant::now() + std::time::Duration::from_secs(12);
    wait_for(&mut h, |_| std::time::Instant::now() > end);
    assert!(
        !shows(&h, "follows along live") && !shows(&h, "Working"),
        "an ended session still reads running\n{}",
        screen_text(&h)
    );
    assert!(shows(&h, "Done"), "{}", screen_text(&h));
    close(h);
}

/// T4 / P5: a task whose helper died reads "Stopped unexpectedly" (one name,
/// with Continue), not a plain "Stopped" like a stop the owner made.
#[test]
fn a_task_whose_helper_died_reads_stopped_unexpectedly() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.play("resume.jsonl", "resume.jsonl");
    home.hold_replies();
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(wait_for(&mut h, |h| has_button(h, labels::SEND_NOW)));
    home.let_through(1);
    let named = std::time::Instant::now() + std::time::Duration::from_millis(800);
    wait_for(&mut h, |_| std::time::Instant::now() > named);
    let hosts = home.root.join(".local/state/mimir/hosts");
    let host: i32 = std::fs::read_dir(&hosts)
        .unwrap()
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|x| x == "json"))
        .find_map(|e| {
            let v: serde_json::Value =
                serde_json::from_str(&std::fs::read_to_string(e.path()).ok()?).ok()?;
            v["host_pid"].as_i64().map(|p| p as i32)
        })
        .expect("no host record");
    // SAFETY: a host this check's service started.
    unsafe { libc::kill(host, libc::SIGKILL) };
    assert!(
        wait_for(&mut h, |h| shows(h, "Stopped unexpectedly")),
        "the state wasn't named\n{}",
        screen_text(&h)
    );
    assert!(has_button(&h, "Continue"), "{}", screen_text(&h));
    assert!(
        !shows(&h, "You stopped this task"),
        "reads like the owner's stop\n{}",
        screen_text(&h)
    );
    home.let_through(1000);
    close(h);
}

/// Opens the New task page and the pill's menu, with project work on
/// `engine` ("free": your PC's model; "claude": online) and, if `model_here`,
/// a model on this PC. Returns the line the menu says.
fn pill_line(engine: &str, model_here: bool) -> String {
    let Some(home) = TestHome::new() else {
        return String::new();
    };
    let project = home.project.clone();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "project_engine": engine,
    }));
    if model_here {
        fake_ollama();
    } else {
        set_env("MIMIR_OLLAMA", "http://127.0.0.1:9");
    }
    let mut h = home.open();
    assert!(wait_for(&mut h, |h| has_button(h, "+  New task")));
    click(&mut h, "+  New task");
    assert!(wait_for(&mut h, |h| shows(h, "Start task")));
    assert!(wait_for(&mut h, |h| has_button(h, labels::PILL_MENU)));
    click(&mut h, labels::PILL_MENU);
    // Give the list of models on this PC its moment (it is read off the window's thread).
    let end = std::time::Instant::now() + std::time::Duration::from_secs(3);
    wait_for(&mut h, |_| std::time::Instant::now() > end);
    let line = if shows(&h, "Answers are made on this PC") {
        "this PC"
    } else if shows(&h, "Answers go to Anthropic") {
        "online"
    } else {
        panic!("the pill said nothing\n{}", screen_text(&h));
    };
    close(h);
    line.to_string()
}

/// P3: before sending, the top pill says where the words will go by the same
/// rule the PC uses when it starts the task: with project work on "Your PC's
/// model" and a model on this PC, a new task is never "Online"; with nothing
/// to move it to, or with project work online, it is.
#[test]
fn the_new_task_pill_follows_the_project_work_setting() {
    assert_eq!(pill_line("free", true), "this PC");
}

#[test]
fn the_new_task_pill_says_online_when_project_work_is_online() {
    assert_eq!(pill_line("claude", true), "online");
}

#[test]
fn the_new_task_pill_does_not_promise_this_pc_with_no_model_here() {
    assert_eq!(pill_line("free", false), "online");
}

fn fake_ollama() {
    use std::io::{Read, Write};
    use std::net::TcpListener;
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = r#"{"models":[{"name":"qwen3.6:35b-a3b-coding"}]}"#;
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
}

/// P2 (PC side): the PC's model program isn't running and can't be started:
/// the chat says so in one plain line that ends in a way out (try again), and
/// the PC's truth says the model is down, so the top pill is not the green
/// "This PC".
#[test]
fn a_model_program_that_will_not_start_is_said_and_flagged_down() {
    let Some(home) = TestHome::new() else { return };
    set_env("MIMIR_OLLAMA", "http://127.0.0.1:9");
    // The engine's pick, as if the probe had run.
    let dir = home.root.join(".local/share/mimir");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(
        dir.join("engine.json"),
        serde_json::json!({
            "fingerprint": "check", "at": 0,
            "chat": {"model": "huihui_ai/Qwen3.6-abliterated:35b-a3b", "title": "Qwen 3.6 35B",
                     "fit": "Split", "tokens_per_s": 64.0, "first_token_ms": 112,
                     "cold_ms": 11000, "backend": "ROCm"},
            "measures": []
        })
        .to_string(),
    )
    .unwrap();
    let mut h = home.open();
    click(&mut h, labels::NEW_CHAT);
    type_message(&mut h, "What is the capital of Norway?");
    click(&mut h, labels::SEND);
    assert!(
        wait_for(&mut h, |h| shows(h, mimir_core::models::MODEL_DOWN)),
        "the chat didn't say the model isn't running\n{}",
        screen_text(&h)
    );
    assert!(
        !shows(&h, "Settings › Models to set it up"),
        "points to a place that can't fix it\n{}",
        screen_text(&h)
    );
    let mut st = home.service();
    assert!(
        service_until(&mut st, |s| s
            .truth
            .as_ref()
            .is_some_and(|t| t.engine.model_down)),
        "the PC's truth doesn't say the model is down"
    );
    close(h);
}
