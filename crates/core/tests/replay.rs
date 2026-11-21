//! Replays streams recorded from the real Claude Code 2.1.325 on this PC, and
//! hand-written ones made from them (see fake-claude.rs for the folders).

use mimir_core::events::{Event, parse_line};
use mimir_core::process::{Launch, Output, Session};
use mimir_core::task::{Entry, Place, Status, Task};
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

fn stream(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/streams")
        .join(name)
}

fn entries(name: &str) -> Vec<Value> {
    std::fs::read_to_string(stream(name))
        .unwrap()
        .lines()
        .map(|l| serde_json::from_str(l).unwrap())
        .collect()
}

/// Feeds a recording straight into a task, as the window would.
fn replay(name: &str, task: &mut Task) {
    for e in entries(name) {
        let ms = (e["t"].as_f64().unwrap_or(0.0) * 1000.0) as u64;
        if let Some(line) = e["line"].as_str() {
            for ev in parse_line(line) {
                task.apply(&ev, ms);
            }
        }
        if e.get("signal").is_some() {
            // The owner pressed Stop at this moment.
            task.status = Status::Stopped;
        }
    }
}

fn new_task() -> Task {
    Task::new("t1".into(), "Test".into(), "/work/proj".into(), 0)
}

fn steps(t: &Task) -> Vec<&mimir_core::task::Step> {
    t.entries
        .iter()
        .filter_map(|e| match e {
            Entry::Step(s) => Some(s),
            _ => None,
        })
        .collect()
}

fn claude_text(t: &Task) -> String {
    t.entries
        .iter()
        .filter_map(|e| match e {
            Entry::Claude(s) => Some(s.as_str()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

const ALL: &[&str] = &[
    "plan_approve.jsonl",
    "deny.jsonl",
    "always.jsonl",
    "midturn.jsonl",
    "interrupt.jsonl",
    "resume.jsonl",
    "plan_mode.jsonl",
    "edit.jsonl",
    // Hand-written from the ones above.
    "question.jsonl",
    "resume-second.jsonl",
];

#[test]
fn every_recorded_line_is_read_without_trouble() {
    for name in ALL {
        let mut init = 0;
        let mut finished = 0;
        for e in entries(name) {
            if let Some(line) = e["line"].as_str() {
                for ev in parse_line(line) {
                    match ev {
                        Event::Init(i) => {
                            init += 1;
                            assert_eq!(i.version, "2.1.325");
                            assert!(!i.session_id.is_empty());
                            assert!(i.capabilities.iter().any(|c| c == "interrupt_receipt_v1"));
                        }
                        Event::Finished(_) => finished += 1,
                        _ => {}
                    }
                }
            }
        }
        assert_eq!(init, 1, "{name}");
        assert_eq!(finished, 1, "{name}");
    }
}

#[test]
fn plan_steps_and_weekly_use() {
    let mut t = new_task();
    replay("plan_approve.jsonl", &mut t);
    assert_eq!(t.plan.len(), 3);
    assert!(t.plan.iter().all(|p| p.done));
    assert_eq!(t.plan[0].text, "Create hello.txt with echo");
    assert_eq!(t.plan_percent(), Some(100));
    let s = steps(&t);
    assert_eq!(s.len(), 2, "Bash and Read only; plan tools are quiet");
    assert_eq!(s[0].tool, "Bash");
    assert_eq!(s[0].ok, Some(true));
    assert_eq!(s[0].place, Place::ThisPc);
    assert_eq!(s[0].place, Place::ThisPc);
    assert!(!s[0].line().contains("online"), "{}", s[0].line());
    assert_eq!(s[1].what, "Read hello.txt");
    assert_eq!(t.places(), (2, 0));
    assert_eq!(t.status, Status::Done);
    assert!(t.session_id.is_some());
    let w = t.weekly.clone().unwrap();
    assert!(w.used > 0.0 && w.used < 1.0 && w.resets_at > 0);
    assert!(claude_text(&t).contains("hello.txt"));
}

#[test]
fn refused_step_says_you_said_no() {
    let mut t = new_task();
    replay("deny.jsonl", &mut t);
    let s = steps(&t);
    assert_eq!(s.len(), 1);
    assert!(s[0].refused);
    assert!(s[0].line().ends_with("not allowed"), "{}", s[0].line());
    assert_eq!(t.status, Status::Done);
}

#[test]
fn always_allowed_steps_both_pass() {
    let mut t = new_task();
    replay("always.jsonl", &mut t);
    let s = steps(&t);
    assert_eq!(s.len(), 2);
    assert!(s.iter().all(|s| s.ok == Some(true)));
}

#[test]
fn message_during_a_turn_is_answered_in_that_turn() {
    let mut t = new_task();
    replay("midturn.jsonl", &mut t);
    assert!(claude_text(&t).contains('4'));
    assert_eq!(t.status, Status::Done);
}

#[test]
fn stop_keeps_stopped_and_marks_the_cut_step() {
    let mut t = new_task();
    replay("interrupt.jsonl", &mut t);
    assert_eq!(t.status, Status::Stopped);
    let s = steps(&t);
    assert_eq!(s.last().unwrap().ok, Some(false));
    assert!(
        s.last().unwrap().line().ends_with("stopped"),
        "{}",
        s.last().unwrap().line()
    );
    assert!(!t.entries.iter().any(|e| matches!(e, Entry::Note(_))));
}

#[test]
fn resume_continues_the_same_session() {
    let mut first = new_task();
    replay("plan_approve.jsonl", &mut first);
    let mut again = first.clone();
    replay("resume.jsonl", &mut again);
    assert_eq!(first.session_id, again.session_id);
    assert!(claude_text(&again).contains("hello.txt"));
}

// ---- The real process manager driving the fake claude ----

fn launch(name: &str, args_log: &Path) -> Launch {
    Launch {
        program: PathBuf::from(env!("CARGO_BIN_EXE_fake-claude")),
        project: std::env::temp_dir(),
        resume: Some("abc".into()),
        settings: Some("{}".into()),
        env: vec![
            (
                "FAKE_CLAUDE_STREAM".into(),
                stream(name).display().to_string(),
            ),
            ("FAKE_CLAUDE_ARGS".into(), args_log.display().to_string()),
        ],
        ..Default::default()
    }
}

fn args_log(tag: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("station-args-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_file(&p);
    p
}

/// Collects events until `stop` says so or the process ends.
fn collect(s: &Session, mut stop: impl FnMut(&Event) -> bool) -> (Vec<Event>, bool) {
    let mut all = vec![];
    let until = Instant::now() + Duration::from_secs(20);
    while Instant::now() < until {
        match s.output.recv_timeout(Duration::from_millis(200)) {
            Ok(Output::Events(evs)) => {
                for e in evs {
                    let done = stop(&e);
                    all.push(e);
                    if done {
                        return (all, false);
                    }
                }
            }
            Ok(Output::Exited(_)) => return (all, true),
            Ok(Output::Offset(_) | Output::HostLost) | Err(_) => {}
        }
    }
    panic!("timed out");
}

#[test]
fn process_runs_a_task_and_passes_the_right_flags() {
    let log = args_log("plan");
    let mut s = Session::start(&launch("plan_approve.jsonl", &log), || {}).unwrap();
    s.send("make a plan").unwrap();
    let (evs, exited) = collect(&s, |_| false);
    assert!(exited);
    assert!(evs.iter().any(|e| matches!(e, Event::Finished(_))));
    let args: Vec<String> = serde_json::from_str(
        std::fs::read_to_string(&log)
            .unwrap()
            .lines()
            .next()
            .unwrap(),
    )
    .unwrap();
    let joined = args.join(" ");
    assert!(
        joined.starts_with("-p --input-format stream-json --output-format stream-json --verbose")
    );
    assert!(joined.contains("--resume abc"));
    assert!(joined.contains("--settings {}"));
    assert!(joined.contains("--include-partial-messages"));
    let _ = std::fs::remove_file(log);
}

#[test]
fn process_takes_a_second_message_mid_turn() {
    let log = args_log("mid");
    let mut s = Session::start(&launch("midturn.jsonl", &log), || {}).unwrap();
    s.send("run sleep").unwrap();
    let (_, exited) = collect(&s, |e| matches!(e, Event::ToolUse { .. }));
    assert!(!exited);
    s.send("also 2+2").unwrap();
    let (evs, _) = collect(&s, |e| matches!(e, Event::Finished(_)));
    assert!(
        evs.iter()
            .any(|e| matches!(e, Event::Text(t) if t.contains('4')))
    );
    s.close_input();
    let _ = std::fs::remove_file(log);
}

#[test]
fn process_stop_ends_the_turn_and_the_process() {
    let log = args_log("stop");
    let mut s = Session::start(&launch("interrupt.jsonl", &log), || {}).unwrap();
    s.send("long job").unwrap();
    collect(&s, |e| matches!(e, Event::ToolUse { .. }));
    s.interrupt();
    let (evs, _) = collect(&s, |e| matches!(e, Event::Finished(_)));
    let fin = evs
        .iter()
        .find_map(|e| match e {
            Event::Finished(f) => Some(f.clone()),
            _ => None,
        })
        .unwrap();
    assert_eq!(fin.terminal_reason, "aborted_tools");
    let (_, exited) = collect(&s, |_| false);
    assert!(exited);
    let _ = std::fs::remove_file(log);
}

#[test]
fn a_late_step_does_not_undo_stop() {
    let mut t = new_task();
    t.status = Status::Stopped;
    replay("interrupt.jsonl", &mut t);
    assert_eq!(t.status, Status::Stopped);
}

#[test]
fn waiting_for_the_owner_is_not_counted_as_work() {
    let mut t = new_task();
    t.apply(
        &Event::ToolUse {
            id: "a".into(),
            name: "Bash".into(),
            input: serde_json::json!({ "command": "ls" }),
        },
        1_000,
    );
    t.answered(60_000);
    t.apply(
        &Event::ToolResult {
            id: "a".into(),
            is_error: false,
            text: String::new(),
            detail: Value::Null,
        },
        62_000,
    );
    assert!(
        steps(&t)[0].line().contains("2 s"),
        "{}",
        steps(&t)[0].line()
    );
}

#[test]
fn plan_mode_keeps_the_plan_for_approval() {
    let mut t = new_task();
    let mut saw_live = false;
    for e in entries("plan_mode.jsonl") {
        if let Some(line) = e["line"].as_str() {
            for ev in parse_line(line) {
                t.apply(&ev, 0);
                saw_live |= !t.live.is_empty();
            }
        }
    }
    assert!(saw_live, "text showed while Claude wrote it");
    assert!(
        t.live.is_empty(),
        "live text cleared once the message is complete"
    );
    let plan = t.plan_doc.clone().unwrap();
    assert!(plan.contains("README"), "{plan}");
    assert!(
        t.files_changed.is_empty(),
        "the plan file is not a project change"
    );
    assert!(steps(&t).iter().any(|s| s.what == "Wrote the plan"));
    assert_eq!(t.context_window, 1_000_000);
    assert!(t.context_used > 10_000);
}

#[test]
fn edits_show_their_changed_lines() {
    let mut t = new_task();
    replay("edit.jsonl", &mut t);
    let s = steps(&t);
    let edit = s.iter().find(|s| s.tool == "Edit").unwrap();
    assert_eq!(
        edit.detail.patch,
        [" line one", "-line two", "+line 2", " line three"]
    );
    assert!(edit.detail.input.ends_with("notes.txt"));
    let write = s.iter().find(|s| s.tool == "Write").unwrap();
    assert_eq!(write.detail.patch, ["+ok"]);
    assert_eq!(t.files_changed, ["notes.txt", "done.txt"]);
    let read = s.iter().find(|s| s.tool == "Read").unwrap();
    assert!(
        read.detail.output.contains("line two"),
        "{}",
        read.detail.output
    );
}

#[test]
fn compact_frees_context_and_says_so() {
    let mut t = new_task();
    replay("compact.jsonl", &mut t);
    assert_eq!(t.context_used, 6599);
    assert!(
        t.entries
            .iter()
            .any(|e| matches!(e, Entry::Note(n) if n.contains("21k → 7k")))
    );
    // The summary Claude Code feeds itself is not shown as chat.
    assert!(
        !t.entries
            .iter()
            .any(|e| matches!(e, Entry::Claude(c) if c.contains("continued from a previous")))
    );
}

// ---- Hand-written recordings and the fake's modes for window checks ----

#[test]
fn a_reply_that_asks_waits_for_the_owner() {
    let mut t = new_task();
    let mut saw_live = false;
    for e in entries("question.jsonl") {
        if let Some(line) = e["line"].as_str() {
            for ev in parse_line(line) {
                t.apply(&ev, 0);
                saw_live |= !t.live.is_empty();
            }
        }
    }
    assert!(saw_live, "the reply showed while Claude wrote it");
    assert_eq!(t.status, Status::Done);
    assert!(t.question_waiting() && t.needs_owner());
    let (_, q) = mimir_core::task::question_split(&claude_text(&t)).unwrap();
    assert!(q.contains("sort the notes"), "{q}");
}

#[test]
fn a_second_resume_carries_on_the_same_conversation() {
    let mut t = new_task();
    replay("plan_approve.jsonl", &mut t);
    let first = t.session_id.clone();
    replay("resume.jsonl", &mut t);
    replay("resume-second.jsonl", &mut t);
    assert_eq!(t.session_id, first);
    assert!(claude_text(&t).contains("still there"));
}

/// A fresh folder for one check.
fn scratch(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("station-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// A folder of recordings, named the way FAKE_CLAUDE_STREAMS reads them.
fn streams(tag: &str, files: &[(&str, &str)]) -> PathBuf {
    let d = scratch(tag);
    for (to, from) in files {
        std::fs::copy(stream(from), d.join(to)).unwrap();
    }
    d
}

fn streams_launch(dir: &Path, resume: Option<&str>) -> Launch {
    Launch {
        program: PathBuf::from(env!("CARGO_BIN_EXE_fake-claude")),
        project: std::env::temp_dir(),
        resume: resume.map(str::to_string),
        env: vec![("FAKE_CLAUDE_STREAMS".into(), dir.display().to_string())],
        ..Default::default()
    }
}

/// Sends one message and collects everything until the process ends.
fn one_turn(launch: &Launch, message: &str) -> (Vec<Event>, Option<i32>) {
    let mut s = Session::start(launch, || {}).unwrap();
    let _ = s.send(message);
    let mut all = vec![];
    let until = Instant::now() + Duration::from_secs(20);
    while Instant::now() < until {
        match s.output.recv_timeout(Duration::from_millis(200)) {
            Ok(Output::Events(evs)) => all.extend(evs),
            Ok(Output::Offset(_) | Output::HostLost) => {}
            // The output ended; the exit code comes from the process.
            Ok(Output::Exited(_)) => {
                while Instant::now() < until {
                    if let Some(code) = s.poll_exit() {
                        return (all, code);
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
            }
            Err(_) => {}
        }
    }
    panic!("timed out");
}

fn finished(evs: &[Event]) -> mimir_core::events::Finished {
    evs.iter()
        .find_map(|e| match e {
            Event::Finished(f) => Some(f.clone()),
            _ => None,
        })
        .expect("no result")
}

#[test]
fn each_new_conversation_plays_the_next_recording() {
    let dir = streams(
        "runs",
        &[
            ("run-1.jsonl", "plan_approve.jsonl"),
            ("run-2.jsonl", "question.jsonl"),
        ],
    );
    let (evs, code) = one_turn(&streams_launch(&dir, None), "make a plan");
    assert_eq!(code, Some(0));
    assert!(finished(&evs).text.unwrap().contains("hello.txt"));
    let (evs, _) = one_turn(&streams_launch(&dir, None), "tidy up");
    assert!(finished(&evs).text.unwrap().contains("sort the notes"));
    // A third has nothing recorded: it says so and fails.
    let (evs, code) = one_turn(&streams_launch(&dir, None), "again");
    assert!(evs.is_empty());
    assert_eq!(code, Some(5));
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn a_resumed_run_keeps_the_conversation_it_was_asked_to_resume() {
    let dir = streams(
        "resumes",
        &[
            ("resume.jsonl", "resume.jsonl"),
            ("resume-second.jsonl", "resume-second.jsonl"),
        ],
    );
    let ids = |evs: &[Event]| -> Vec<String> {
        evs.iter()
            .filter_map(|e| match e {
                Event::Init(i) => Some(i.session_id.clone()),
                Event::Finished(f) => Some(f.session_id.clone()),
                _ => None,
            })
            .collect()
    };
    let (evs, _) = one_turn(&streams_launch(&dir, Some("sess-check-1")), "what file?");
    assert_eq!(ids(&evs), ["sess-check-1", "sess-check-1"]);
    assert!(finished(&evs).text.unwrap().contains("hello.txt"));
    let (evs, _) = one_turn(&streams_launch(&dir, Some("sess-check-1")), "still there?");
    assert_eq!(ids(&evs), ["sess-check-1", "sess-check-1"]);
    assert!(finished(&evs).text.unwrap().contains("still there"));
    // Later ones play resume.jsonl again.
    let (evs, _) = one_turn(&streams_launch(&dir, Some("sess-check-1")), "and now?");
    assert!(finished(&evs).text.unwrap().contains("which contains"));
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn the_step_file_holds_the_reply_until_raised() {
    let dir = scratch("step");
    let step = dir.join("step");
    std::fs::write(&step, "3").unwrap();
    let launch = Launch {
        program: PathBuf::from(env!("CARGO_BIN_EXE_fake-claude")),
        project: std::env::temp_dir(),
        env: vec![
            (
                "FAKE_CLAUDE_STREAM".into(),
                stream("question.jsonl").display().to_string(),
            ),
            ("FAKE_CLAUDE_STEP_FILE".into(), step.display().to_string()),
        ],
        ..Default::default()
    };
    let mut s = Session::start(&launch, || {}).unwrap();
    s.send("tidy up").unwrap();
    // Lines 1-3: the start, the message start and its text block. Then it waits.
    let mut held = vec![];
    let until = Instant::now() + Duration::from_millis(1500);
    while Instant::now() < until {
        if let Ok(Output::Events(evs)) = s.output.recv_timeout(Duration::from_millis(50)) {
            held.extend(evs);
        }
    }
    assert!(held.iter().any(|e| matches!(e, Event::Init(_))));
    assert!(
        !held
            .iter()
            .any(|e| matches!(e, Event::Text(_) | Event::Finished(_))),
        "{held:?}"
    );
    std::fs::write(&step, "1000").unwrap();
    let (evs, exited) = collect(&s, |_| false);
    assert!(exited);
    assert!(finished(&evs).text.unwrap().contains("sort the notes"));
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn sign_in_and_version_answer_without_a_recording() {
    let fake = env!("CARGO_BIN_EXE_fake-claude");
    let out = std::process::Command::new(fake)
        .args(["auth", "status", "--json"])
        .env_remove("FAKE_CLAUDE_STREAM")
        .output()
        .unwrap();
    assert!(out.status.success());
    let text = String::from_utf8_lossy(&out.stdout);
    assert_eq!(
        mimir_core::setup::parse_claude_auth(&text),
        (true, Some("Max".into()))
    );
    assert!(mimir_core::setup::parse_claude_account(&text).is_some());
    let out = std::process::Command::new(fake)
        .arg("--version")
        .output()
        .unwrap();
    assert!(
        mimir_core::setup::parse_claude_version(&String::from_utf8_lossy(&out.stdout)).is_some()
    );
}

/// Copies a hand-written history file (tests/sessions) into a Claude home,
/// where Claude Code keeps it, with the project folder filled in. Returns
/// its conversation id.
fn install_session(name: &str, home: &Path, project: &Path) -> String {
    let text = std::fs::read_to_string(
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests/sessions")
            .join(name),
    )
    .unwrap();
    let id = text
        .lines()
        .find_map(|l| {
            let v: Value = serde_json::from_str(l).ok()?;
            v.get("sessionId")?.as_str().map(str::to_string)
        })
        .unwrap();
    let folder = home
        .join("projects")
        .join(mimir_core::sessions::folder_name(project));
    std::fs::create_dir_all(&folder).unwrap();
    let cwd = serde_json::to_string(&project.display().to_string()).unwrap();
    std::fs::write(
        folder.join(format!("{id}.jsonl")),
        text.replace("\"@PROJECT@\"", &cwd),
    )
    .unwrap();
    id
}

/// Runs `sessions_child` again in its own process with CLAUDE_CONFIG_DIR
/// set (changing this process's environment would race the other tests).
#[test]
fn terminal_and_phone_sessions_are_listed_and_open_as_tasks() {
    let out = std::process::Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "sessions_child",
            "--nocapture",
            "--test-threads=1",
        ])
        .env("STATION_SESSIONS_CHILD", "1")
        .output()
        .unwrap();
    let text = String::from_utf8_lossy(&out.stdout);
    assert!(
        out.status.success() && text.contains("1 passed"),
        "{text}{}",
        String::from_utf8_lossy(&out.stderr)
    );
}

#[test]
fn sessions_child() {
    if std::env::var_os("STATION_SESSIONS_CHILD").is_none() {
        return;
    }
    // Under the checks' own folder: the list leaves out projects in /tmp.
    let home = PathBuf::from(std::env::var("HOME").unwrap());
    let root = home
        .join(".cache/mimir-checks/t")
        .join(format!("replay-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let project = root.join("proj");
    let claude = root.join("claude");
    std::fs::create_dir_all(&project).unwrap();
    // SAFETY: this process runs only this test.
    unsafe { std::env::set_var("CLAUDE_CONFIG_DIR", &claude) };
    let term = install_session("terminal.jsonl", &claude, &project);
    let phone = install_session("phone.jsonl", &claude, &project);

    let listed = mimir_core::sessions::list(&project);
    let ids: Vec<&str> = listed.iter().map(|s| s.id.as_str()).collect();
    assert_eq!(listed.len(), 2, "{ids:?}");
    assert!(ids.contains(&term.as_str()) && ids.contains(&phone.as_str()));
    let recent = mimir_core::sessions::recent(10);
    assert_eq!(recent.len(), 2);
    assert!(recent.iter().all(|o| o.project == project));
    let titles: Vec<_> = recent.iter().filter_map(|o| o.title.clone()).collect();
    assert!(titles.contains(&"Note that the build passed".to_string()));

    for (id, you, claude_says) in [
        (&term, "build passed", "now ends with"),
        (&phone, "What's in notes.txt", "three notes"),
    ] {
        let s = listed.iter().find(|s| &s.id == id).unwrap();
        assert!(s.first.contains(you), "{}", s.first);
        let t = mimir_core::sessions::to_task(s, &project, "t".into()).unwrap();
        assert_eq!(t.session_id.as_deref(), Some(id.as_str()));
        assert!(claude_text(&t).contains(claude_says), "{}", claude_text(&t));
    }
    let t = mimir_core::sessions::to_task(
        listed.iter().find(|s| s.id == term).unwrap(),
        &project,
        "t".into(),
    )
    .unwrap();
    assert_eq!(steps(&t).len(), 1);
    assert_eq!(steps(&t)[0].tool, "Bash");
    std::fs::remove_dir_all(&root).unwrap();
}
