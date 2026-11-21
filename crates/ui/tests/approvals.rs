//! Approvals end to end: (fake or real) Claude Code runs
//! `mimir hook`, which asks a listening "window" over the socket.

use mimir_core::approvals::{self, Answer, Pending};
use mimir_core::process::{Launch, Output, Session};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{Receiver, channel};
use std::time::{Duration, Instant};

fn exe() -> PathBuf {
    PathBuf::from(env!("CARGO_BIN_EXE_mimir"))
}

fn fake() -> PathBuf {
    let p = exe().with_file_name("fake-claude");
    assert!(p.exists(), "build the workspace first: {}", p.display());
    p
}

fn temp(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("station-appr-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn window(sock: &Path) -> (Receiver<Pending>, approvals::Listener) {
    let (tx, rx) = channel();
    let l = approvals::serve(sock, tx, || {}).unwrap();
    (rx, l)
}

/// Runs a recording; the "window" gives `answer` to every question.
fn run(name: &str, answer: Answer) -> (Vec<String>, Vec<String>) {
    let dir = temp(name);
    let sock = dir.join("s/approvals.sock");
    let (rx, _listener) = window(&sock);
    let hooks = dir.join("hooks.log");
    let stream = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../core/tests/streams")
        .join(name);
    let launch = Launch {
        program: fake(),
        project: dir.clone(),
        resume: None,
        settings: Some(approvals::hook_settings(&exe(), "task-1", &sock)),
        env: vec![
            ("FAKE_CLAUDE_STREAM".into(), stream.display().to_string()),
            ("FAKE_CLAUDE_HOOKS".into(), hooks.display().to_string()),
        ],
        ..Default::default()
    };
    let mut s = Session::start(&launch, || {}).unwrap();
    s.send("go").unwrap();
    let mut asked = vec![];
    let until = Instant::now() + Duration::from_secs(20);
    loop {
        assert!(Instant::now() < until, "timed out");
        if let Ok(p) = rx.recv_timeout(Duration::from_millis(50)) {
            assert_eq!(p.request.task, "task-1");
            asked.push(p.request.words());
            p.answer(answer);
        }
        if let Ok(Output::Exited(_)) = s.output.try_recv() {
            break;
        }
        // Stop recordings need the Stop button.
        if name == "interrupt.jsonl" && !asked.is_empty() {
            std::thread::sleep(Duration::from_millis(100));
            s.interrupt();
        }
    }
    let code = loop {
        if let Some(c) = s.poll_exit() {
            break c;
        }
        std::thread::sleep(Duration::from_millis(20));
    };
    assert_ne!(code, Some(4), "hook answer did not match the recording");
    let got = std::fs::read_to_string(&hooks)
        .unwrap_or_default()
        .lines()
        .map(str::to_string)
        .collect();
    std::fs::remove_dir_all(dir).unwrap();
    (asked, got)
}

#[test]
fn allow_reaches_claude() {
    let (asked, got) = run("plan_approve.jsonl", Answer::Allow);
    assert_eq!(asked, ["Run a command on this PC: echo hi > hello.txt"]);
    assert_eq!(got, ["allow"]);
}

#[test]
fn no_reaches_claude() {
    let (asked, got) = run("deny.jsonl", Answer::No);
    assert_eq!(asked.len(), 1);
    assert_eq!(got, ["deny"]);
}

#[test]
fn always_reaches_claude() {
    let (_, got) = run("always.jsonl", Answer::Always);
    assert_eq!(got, ["always"]);
}

#[test]
fn approve_then_stop() {
    let (_, got) = run("interrupt.jsonl", Answer::Allow);
    assert_eq!(got, ["allow"]);
}

#[test]
fn closed_window_means_no() {
    let dir = temp("closed");
    let out = std::process::Command::new(exe())
        .args(["hook", "--task", "x", "--socket"])
        .arg(dir.join("none.sock"))
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .spawn()
        .and_then(|mut c| {
            use std::io::Write;
            writeln!(
                c.stdin.take().unwrap(),
                r#"{{"tool_name":"Bash","tool_input":{{"command":"ls"}}}}"#
            )?;
            c.wait_with_output()
        })
        .unwrap();
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(v["hookSpecificOutput"]["decision"]["behavior"], "deny");
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn second_window_is_refused() {
    let dir = temp("twice");
    let sock = dir.join("approvals.sock");
    let _first = window(&sock);
    let (tx, _rx) = channel();
    assert!(approvals::serve(&sock, tx, || {}).is_err());
    std::fs::remove_dir_all(dir).unwrap();
}
