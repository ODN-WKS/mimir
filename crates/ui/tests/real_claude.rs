//! Real checks: the core features against the real Claude Code on this PC.
//! They use the owner's sign-in and a little weekly use, so they only run
//! when asked, through tools/real-check.sh:
//!   STATION_REAL_CLAUDE=$(command -v claude) target/debug/deps/real_claude-* --ignored --test-threads 1
//! STATION_REAL_MODEL picks the model (the smallest one keeps the use low).
//!
//! A check that can't say anything about Mímir (a usage limit, or
//! Claude didn't do what the prompt asked) prints `COULD_NOT_RUN: <reason>`
//! and passes; real-check.sh reads that line. Every session a check makes is
//! removed from Claude Code's history afterwards.

use mimir_core::approvals::{self, Answer};
use mimir_core::events::Event;
use mimir_core::process::{Launch, Output, Session};
use mimir_core::sessions;
use std::path::{Path, PathBuf};
use std::sync::mpsc::channel;
use std::time::{Duration, Instant};

struct Run {
    asked: Vec<String>,
    events: Vec<Event>,
}

impl Run {
    fn session(&self) -> Option<String> {
        self.events.iter().find_map(|e| match e {
            Event::Init(i) => Some(i.session_id.clone()),
            _ => None,
        })
    }

    fn text(&self) -> String {
        self.events
            .iter()
            .filter_map(|e| match e {
                Event::Text(t) => Some(t.as_str()),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// Why this run says nothing about Mímir: Claude Code ended
    /// with an error (a usage or rate limit, signed out) or never finished.
    fn could_not_run(&self) -> Option<String> {
        let fin = self.events.iter().find_map(|e| match e {
            Event::Finished(f) => Some(f),
            _ => None,
        });
        match fin {
            None => Some("Claude Code ended without a result".into()),
            Some(f) if f.is_error => Some(format!(
                "Claude Code ended with an error: {}",
                f.text.clone().unwrap_or_else(|| f.subtype.clone())
            )),
            _ => None,
        }
    }
}

fn claude() -> PathBuf {
    std::env::var("STATION_REAL_CLAUDE")
        .expect("set STATION_REAL_CLAUDE")
        .into()
}

fn model() -> Option<String> {
    std::env::var("STATION_REAL_MODEL")
        .ok()
        .filter(|m| !m.is_empty())
}

fn could_not_run(why: &str) {
    println!("COULD_NOT_RUN: {why}");
}

/// A fresh project folder for one check. Under ~/.cache so the "On this PC"
/// list (which leaves out /tmp) can see its sessions.
fn project(name: &str) -> PathBuf {
    let home = PathBuf::from(std::env::var_os("HOME").unwrap());
    let p = home
        .join(".cache/mimir-checks/real")
        .join(format!("{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    std::fs::create_dir_all(&p).unwrap();
    p
}

/// Removes the check's folder and only its own sessions from Claude Code's
/// history (`~/.claude/projects/<folder>/<id>.jsonl` and `<id>/`).
struct Cleanup {
    project: PathBuf,
    sessions: Vec<String>,
}

impl Drop for Cleanup {
    fn drop(&mut self) {
        let root = sessions::claude_home().join("projects");
        for dir in std::fs::read_dir(&root).into_iter().flatten().flatten() {
            let mut touched = false;
            for id in &self.sessions {
                if id.is_empty() || id.contains('/') {
                    continue;
                }
                let file = dir.path().join(format!("{id}.jsonl"));
                if file.exists() {
                    let _ = std::fs::remove_file(&file);
                    touched = true;
                }
                let sub = dir.path().join(id);
                if sub.is_dir() {
                    let _ = std::fs::remove_dir_all(&sub);
                    touched = true;
                }
            }
            // The folder Claude Code made for this check, once it's empty
            // (Claude Code also leaves an empty memory folder in it).
            if touched {
                let _ = std::fs::remove_dir(dir.path().join("memory"));
                let _ = std::fs::remove_dir(dir.path());
            }
        }
        let _ = std::fs::remove_dir_all(&self.project);
    }
}

fn real(project: &Path, prompt: &str, answer: Answer, resume: Option<String>) -> Run {
    let sock = project.join(format!(".sock-{answer:?}"));
    let (tx, rx) = channel();
    let _listener = approvals::serve(&sock, tx, || {}).unwrap();
    let launch = Launch {
        program: claude(),
        project: project.to_path_buf(),
        resume,
        settings: Some(approvals::hook_settings(
            &PathBuf::from(env!("CARGO_BIN_EXE_mimir")),
            "real",
            &sock,
        )),
        env: vec![],
        model: model(),
        permission_mode: Some("default".into()),
        ..Default::default()
    };
    let mut s = Session::start(&launch, || {}).unwrap();
    s.send(prompt).unwrap();
    let mut run = Run {
        asked: vec![],
        events: vec![],
    };
    let until = Instant::now() + Duration::from_secs(180);
    loop {
        assert!(Instant::now() < until, "timed out");
        if let Ok(p) = rx.recv_timeout(Duration::from_millis(50)) {
            run.asked.push(p.request.always_rule_text());
            p.answer(answer);
        }
        match s.output.try_recv() {
            Ok(Output::Offset(_) | Output::HostLost) => {}
            Ok(Output::Events(evs)) => {
                let done = evs.iter().any(|e| matches!(e, Event::Finished(_)));
                run.events.extend(evs);
                if done {
                    s.close_input();
                }
            }
            Ok(Output::Exited(_)) => break,
            Err(_) => {}
        }
    }
    let _ = std::fs::remove_file(&sock);
    run
}

#[test]
#[ignore]
fn cf1_real_reply() {
    let p = project("cf1");
    let mut clean = Cleanup {
        project: p.clone(),
        sessions: vec![],
    };
    let r = real(
        &p,
        "Reply with exactly one word: pineapple",
        Answer::No,
        None,
    );
    clean.sessions.extend(r.session());
    if let Some(why) = r.could_not_run() {
        return could_not_run(&why);
    }
    assert!(r.session().is_some(), "Claude Code never said it started");
    assert!(
        r.text().to_lowercase().contains("pineapple"),
        "the reply didn't reach Mímir: {:?}",
        r.text()
    );
}

#[test]
#[ignore]
fn cf2_real_resume() {
    let p = project("cf2");
    let mut clean = Cleanup {
        project: p.clone(),
        sessions: vec![],
    };
    let first = real(
        &p,
        "Remember the word walnut. Reply with just: OK",
        Answer::No,
        None,
    );
    clean.sessions.extend(first.session());
    if let Some(why) = first.could_not_run() {
        return could_not_run(&why);
    }
    let session = first.session();
    assert!(session.is_some(), "Claude Code never said it started");
    let again = real(
        &p,
        "Which word did I ask you to remember? Reply with just the word.",
        Answer::No,
        session.clone(),
    );
    clean.sessions.extend(again.session());
    if let Some(why) = again.could_not_run() {
        return could_not_run(&why);
    }
    assert_eq!(
        again.session(),
        session,
        "the follow-up started a new conversation"
    );
    assert!(
        again.text().to_lowercase().contains("walnut"),
        "the follow-up didn't carry on the conversation: {:?}",
        again.text()
    );
}

#[test]
#[ignore]
fn cf3_real_allow_no_always() {
    let p = project("cf3");
    let mut clean = Cleanup {
        project: p.clone(),
        sessions: vec![],
    };

    let a = real(
        &p,
        "Use the Bash tool to run exactly: touch allowed.txt   Then reply with one word.",
        Answer::Allow,
        None,
    );
    clean.sessions.extend(a.session());
    if let Some(why) = a.could_not_run() {
        return could_not_run(&why);
    }
    if a.asked.is_empty() {
        return could_not_run("Claude didn't run the command it was asked to");
    }
    assert_eq!(a.asked.len(), 1, "one question for one command");
    assert!(
        p.join("allowed.txt").exists(),
        "clicked Allow; the command didn't run"
    );

    let n = real(
        &p,
        "Use the Bash tool to run exactly: touch denied.txt   Then reply with one word.",
        Answer::No,
        None,
    );
    clean.sessions.extend(n.session());
    if let Some(why) = n.could_not_run() {
        return could_not_run(&why);
    }
    if n.asked.is_empty() {
        return could_not_run("Claude didn't try the command it was asked to");
    }
    assert!(
        !p.join("denied.txt").exists(),
        "clicked Don't allow; the command ran anyway"
    );
    assert!(n.events.iter().any(|e| matches!(e, Event::Denied { .. })));

    let w = real(
        &p,
        "Use the Bash tool twice, as two separate calls: first run exactly `touch always1.txt`, then run exactly `touch always2.txt`. Then reply with one word.",
        Answer::Always,
        None,
    );
    clean.sessions.extend(w.session());
    if let Some(why) = w.could_not_run() {
        return could_not_run(&why);
    }
    if !p.join("always1.txt").exists() || !p.join("always2.txt").exists() {
        return could_not_run("Claude didn't run both commands it was asked to");
    }
    assert_eq!(w.asked, ["Bash(touch *)"], "asked again after Always allow");
    let saved = std::fs::read_to_string(p.join(".claude/settings.local.json")).unwrap();
    assert!(saved.contains("Bash(touch *)"), "{saved}");
}

#[test]
#[ignore]
fn cf4_real_question() {
    let p = project("cf4");
    let mut clean = Cleanup {
        project: p.clone(),
        sessions: vec![],
    };
    let r = real(
        &p,
        "Don't do any work. Ask me one short question: which of red or blue I prefer. Write nothing else.",
        Answer::No,
        None,
    );
    clean.sessions.extend(r.session());
    if let Some(why) = r.could_not_run() {
        return could_not_run(&why);
    }
    let text = r.text();
    if !text.contains('?') {
        return could_not_run("Claude didn't ask a question");
    }
    assert!(
        mimir_core::task::question_split(&text).is_some(),
        "Claude's question didn't show in the question card: {text:?}"
    );
}

#[test]
#[ignore]
fn cf13_real_session_listed() {
    let p = project("cf13");
    let mut clean = Cleanup {
        project: p.clone(),
        sessions: vec![],
    };
    let r = real(&p, "Reply with just: OK", Answer::No, None);
    clean.sessions.extend(r.session());
    if let Some(why) = r.could_not_run() {
        return could_not_run(&why);
    }
    let id = r.session().expect("Claude Code never said it started");
    let listed = sessions::recent(200)
        .into_iter()
        .any(|s| s.session.id == id && s.project == p);
    assert!(listed, "a terminal session isn't listed under On this PC");
    let past = sessions::list(&p);
    assert!(
        past.iter().any(|s| s.id == id),
        "the session isn't listed under its project"
    );
}

/// Stops an Ollama this check started, with the model runner it started.
fn stop_ollama(started: Option<std::process::Child>) {
    if let Some(mut c) = started {
        // SAFETY: the process group the check created for it.
        unsafe { libc::kill(-(c.id() as libc::pid_t), libc::SIGTERM) };
        std::thread::sleep(Duration::from_secs(2));
        unsafe { libc::kill(-(c.id() as libc::pid_t), libc::SIGKILL) };
        let _ = c.wait();
    }
}

/// Starts this PC's Ollama if it isn't answering; the child to stop after.
fn ollama_up() -> Option<Option<std::process::Child>> {
    if mimir_core::models::installed().is_ok() {
        return Some(None);
    }
    let home = PathBuf::from(std::env::var_os("HOME")?);
    let bin = home.join(".local/share/ollama-dist/bin/ollama");
    if !bin.is_file() {
        return None;
    }
    let mut cmd = std::process::Command::new(bin);
    // Its own process group: stopping it also stops the model runner it starts.
    std::os::unix::process::CommandExt::process_group(&mut cmd, 0);
    let child = cmd
        .arg("serve")
        .env("OLLAMA_HOST", "127.0.0.1:11434")
        .env("OLLAMA_MODELS", home.join(".local/share/ollama-models"))
        .env(
            "OLLAMA_CONTEXT_LENGTH",
            mimir_core::models::CONTEXT.to_string(),
        )
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .ok()?;
    let until = Instant::now() + Duration::from_secs(30);
    while Instant::now() < until {
        if mimir_core::models::installed().is_ok() {
            return Some(Some(child));
        }
        std::thread::sleep(Duration::from_millis(300));
    }
    None
}

/// P-8: a task on a free model fixes the two bugs of the 2026-11-18 test
/// job (General/tasks/2026-11-18-local-models/job2) on this PC.
#[test]
#[ignore]
fn p8_real_free_model_fixes_job2() {
    const FREE: &str = "qwen3.6:35b-a3b-coding";
    let home = PathBuf::from(std::env::var_os("HOME").unwrap());
    let job = home.join("Projects/General/tasks/2026-11-18-local-models/job2");
    if !job.join("shop.py").is_file() {
        return could_not_run("the test job isn't on this PC");
    }
    let Some(started) = ollama_up() else {
        return could_not_run("Ollama isn't installed or didn't start");
    };
    if !mimir_core::models::installed().is_ok_and(|n| n.iter().any(|m| m == FREE)) {
        return could_not_run("the free model isn't downloaded");
    }
    let p = project("free-model");
    for f in ["shop.py", "test_shop.py"] {
        std::fs::copy(job.join(f), p.join(f)).unwrap();
    }
    let failing = std::process::Command::new("python3")
        .args(["-m", "unittest", "-q"])
        .current_dir(&p)
        .output()
        .unwrap();
    assert!(
        !failing.status.success(),
        "the job's tests already pass: nothing to fix"
    );
    let sock = p.join(".sock-free");
    let (tx, rx) = channel();
    let _listener = approvals::serve(&sock, tx, || {}).unwrap();
    let launch = Launch {
        program: claude(),
        project: p.clone(),
        settings: Some(approvals::hook_settings(
            &PathBuf::from(env!("CARGO_BIN_EXE_mimir")),
            "real",
            &sock,
        )),
        env: mimir_core::models::launch_env(),
        model: Some(FREE.into()),
        permission_mode: Some("default".into()),
        ..Default::default()
    };
    let mut s = Session::start(&launch, || {}).unwrap();
    s.send(
        "The tests in test_shop.py fail. Run them with python3 -m unittest, find the bugs in \
         shop.py and fix them so every test passes. Do not change test_shop.py.",
    )
    .unwrap();
    let mut session = None;
    let until = Instant::now() + Duration::from_secs(600);
    loop {
        assert!(
            Instant::now() < until,
            "the free model took over 10 minutes"
        );
        if let Ok(req) = rx.recv_timeout(Duration::from_millis(50)) {
            req.answer(Answer::Allow);
        }
        match s.output.try_recv() {
            Ok(Output::Offset(_) | Output::HostLost) => {}
            Ok(Output::Events(evs)) => {
                for e in &evs {
                    if let Event::Init(i) = e {
                        session = Some(i.session_id.clone());
                    }
                }
                if evs.iter().any(|e| matches!(e, Event::Finished(_))) {
                    s.close_input();
                }
            }
            Ok(Output::Exited(_)) => break,
            Err(_) => {}
        }
    }
    let _cleanup = Cleanup {
        project: p.clone(),
        sessions: session.into_iter().collect(),
    };
    stop_ollama(started);
    let passing = std::process::Command::new("python3")
        .args(["-m", "unittest", "-q"])
        .current_dir(&p)
        .output()
        .unwrap();
    let tests_same = std::fs::read(job.join("test_shop.py")).unwrap()
        == std::fs::read(p.join("test_shop.py")).unwrap();
    assert!(tests_same, "the free model changed the tests");
    assert!(
        passing.status.success(),
        "the job's tests still fail after the free model's task:\n{}",
        String::from_utf8_lossy(&passing.stderr)
    );
}

/// P-9: an Incognito task (free model, sealed space) fixes the test job with
/// the internet cut off, and leaves nothing in Claude Code's history.
#[test]
#[ignore]
fn p9_real_incognito_fixes_job2_sealed() {
    const FREE: &str = "qwen3.6:35b-a3b-coding";
    let home = PathBuf::from(std::env::var_os("HOME").unwrap());
    let job = home.join("Projects/General/tasks/2026-11-18-local-models/job2");
    if !job.join("shop.py").is_file() {
        return could_not_run("the test job isn't on this PC");
    }
    let Some(started) = ollama_up() else {
        return could_not_run("Ollama isn't installed or didn't start");
    };
    if !mimir_core::models::installed().is_ok_and(|n| n.iter().any(|m| m == FREE)) {
        return could_not_run("the free model isn't downloaded");
    }
    let p = project("incognito");
    for f in ["shop.py", "test_shop.py"] {
        std::fs::copy(job.join(f), p.join(f)).unwrap();
    }
    let folder_key = p.display().to_string().replace(['/', '.'], "-");
    let history = sessions::claude_home().join("projects");
    let task = format!("real-incognito-{}", std::process::id());
    mimir_core::incognito::prepare(&task).unwrap();
    let sock = mimir_core::incognito::approvals_socket(&task);
    let (tx, rx) = channel();
    let _listener = approvals::serve(&sock, tx, || {}).unwrap();
    let launch = Launch {
        program: claude(),
        project: p.clone(),
        settings: Some(mimir_core::incognito::deny_web(&approvals::hook_settings(
            &PathBuf::from(env!("CARGO_BIN_EXE_mimir")),
            "real",
            &sock,
        ))),
        env: mimir_core::incognito::env(&task),
        model: Some(FREE.into()),
        permission_mode: Some("default".into()),
        wrap: mimir_core::incognito::wrap(&PathBuf::from(env!("CARGO_BIN_EXE_mimir")), &task),
        ..Default::default()
    };
    let mut s = Session::start(&launch, || {}).unwrap();
    s.send(
        "The tests in test_shop.py fail. Run them with python3 -m unittest, find the bugs in \
         shop.py and fix them so every test passes. Do not change test_shop.py.",
    )
    .unwrap();
    let mut finished = None;
    let mut asked = 0;
    let until = Instant::now() + Duration::from_secs(600);
    loop {
        assert!(
            Instant::now() < until,
            "the sealed task took over 10 minutes"
        );
        if let Ok(req) = rx.recv_timeout(Duration::from_millis(50)) {
            println!("asked: {}", req.request.always_rule_text());
            asked += 1;
            req.answer(Answer::Allow);
        }
        match s.output.try_recv() {
            Ok(Output::Offset(_) | Output::HostLost) => {}
            Ok(Output::Events(evs)) => {
                for e in &evs {
                    if let Event::Finished(f) = e {
                        finished = Some(f.clone());
                    }
                }
                if finished.is_some() {
                    s.close_input();
                }
            }
            Ok(Output::Exited(_)) => break,
            Err(_) => {}
        }
    }
    s.kill();
    mimir_core::incognito::forget(&task);
    stop_ollama(started);
    let f = finished.expect("the sealed Claude Code never finished");
    println!(
        "sealed result: {:?} error={} asked={asked}",
        f.text, f.is_error
    );
    assert!(
        !f.text.clone().unwrap_or_default().contains("couldn't seal"),
        "the space didn't seal: {:?}",
        f.text
    );
    let passing = std::process::Command::new("python3")
        .args(["-m", "unittest", "-q"])
        .current_dir(&p)
        .output()
        .unwrap();
    let leaked: Vec<String> = std::fs::read_dir(&history)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.file_name().to_string_lossy().to_string())
        .filter(|n| n.contains(&folder_key) || n.ends_with("incognito"))
        .collect();
    let _cleanup = Cleanup {
        project: p.clone(),
        sessions: vec![],
    };
    assert!(
        leaked.is_empty(),
        "the Whisper task left history in ~/.claude: {leaked:?}"
    );
    assert!(
        !mimir_core::incognito::dir(&task).exists(),
        "its RAM folder is still there"
    );
    assert!(
        passing.status.success(),
        "the job's tests still fail after the Whisper task:\n{}",
        String::from_utf8_lossy(&passing.stderr)
    );
}
