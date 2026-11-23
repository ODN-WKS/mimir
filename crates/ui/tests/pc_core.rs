//! The PC's core finishing checks: the service never sleeps while the PC's
//! model wakes, and a Whisper task refuses Read aloud and Phone too.

mod common;
use common::*;
use mimir_core::proto::Call;
use mimir_core::task::Mode;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::time::{Duration, Instant};

/// Ollama's stand-in, answering the model list.
fn serve(l: TcpListener) {
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = "{\"models\":[{\"name\":\"qwen3.6:35b-a3b-coding\"}]}";
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
}

#[test]
fn the_service_answers_at_once_while_the_pcs_model_wakes() {
    let Some(home) = TestHome::new() else { return };
    // Ollama's unit exists but nothing answers on its port yet.
    let unit = home.root.join(".config/systemd/user/mimir-ollama.service");
    std::fs::create_dir_all(unit.parent().unwrap()).unwrap();
    std::fs::write(&unit, "[Service]\nExecStart=/bin/true\n").unwrap();
    let port = {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().port()
    };
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
    // The "starter" lets the stand-in come up two seconds later.
    let started = home.root.join("ollama-started");
    let starter = home.root.join("starter.sh");
    std::fs::write(
        &starter,
        format!("#!/bin/sh\ntouch '{}'\n", started.display()),
    )
    .unwrap();
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&starter, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    set_env("MIMIR_OLLAMA_START", &starter);
    let flag = started.clone();
    std::thread::spawn(move || {
        while !flag.exists() {
            std::thread::sleep(Duration::from_millis(20));
        }
        std::thread::sleep(Duration::from_secs(2));
        if let Ok(l) = TcpListener::bind(("127.0.0.1", port)) {
            serve(l);
        }
    });
    let mut slow = home.service();
    let mut quick = home.service();
    // A call that needs the model: waits (off the service's thread).
    let t0 = Instant::now();
    let waiting = std::thread::spawn(move || {
        let r = slow.call_wait(Call::NewIncognito {
            ground_rules: None,
            project: String::new(),
            text: "Hi".into(),
            model: "ollama:qwen3.6:35b-a3b-coding".into(),
            mode: Mode::Ask,
        });
        (r, t0.elapsed())
    });
    // While it waits, the service answers at once.
    std::thread::sleep(Duration::from_millis(500));
    let mut worst = Duration::ZERO;
    for _ in 0..5 {
        let t = Instant::now();
        quick.call_wait(Call::Projects).expect("the status call");
        worst = worst.max(t.elapsed());
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(
        worst < Duration::from_millis(100),
        "the service was held up {worst:?} while the model woke"
    );
    let (_, took) = waiting.join().unwrap();
    assert!(
        took >= Duration::from_secs(1),
        "the call didn't wait for the model ({took:?})"
    );
    home.stop_service();
}

#[test]
fn a_whisper_task_refuses_read_aloud() {
    let Some(home) = TestHome::new() else { return };
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    serve(l);
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let id = st
        .call_wait(Call::NewIncognito {
            ground_rules: None,
            project: home.project.display().to_string(),
            text: "Tidy up notes.txt.".into(),
            model: "ollama:qwen3.6:35b-a3b-coding".into(),
            mode: Mode::Ask,
        })
        .expect("the Whisper task didn't start");
    let id = id.as_str().unwrap().to_string();
    let r = st.call_wait(Call::Speak {
        task: id.clone(),
        entry: 0,
    });
    assert!(r.unwrap_err().contains("Whisper"));
    let _ = st.call_wait(Call::EndIncognito { task: id });
    home.stop_service();
}

/// A runner with the stand-in Claude, and the file it logs each start to.
fn runner_with(tag: &str, stream: &str) -> (mimir_core::runner::Runner, std::path::PathBuf) {
    use mimir_core::runner::{Config, Runner};
    use std::path::PathBuf;
    let dir = std::env::temp_dir().join(format!("pc-core-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("proj")).unwrap();
    let exe = PathBuf::from(env!("CARGO_BIN_EXE_mimir"));
    let args = dir.join("args.log");
    let stream = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../core/tests/streams")
        .join(stream);
    let r = Runner::new(
        Config {
            claude: exe.with_file_name("fake-claude"),
            hook_exe: exe,
            socket: dir.join("run/approvals.sock"),
            store_dir: dir.join("tasks"),
            env: vec![
                ("FAKE_CLAUDE_STREAM".into(), stream.display().to_string()),
                ("FAKE_CLAUDE_ARGS".into(), args.display().to_string()),
            ],
        },
        std::sync::Arc::new(|| {}),
    )
    .unwrap();
    (r, dir)
}

#[test]
fn messages_waiting_for_an_ended_run_never_kill_the_run_that_took_over() {
    use mimir_core::task::Entry;
    let (mut r, dir) = runner_with("resend", "midturn.jsonl");
    let id = r.new_task(&dir.join("proj"), "run sleep");
    let end = Instant::now() + Duration::from_secs(20);
    while !r.tasks[0]
        .task
        .entries
        .iter()
        .any(|e| matches!(e, Entry::Step(_)))
    {
        r.tick();
        assert!(Instant::now() < end, "no step");
        std::thread::sleep(Duration::from_millis(10));
    }
    assert!(r.tasks[0].running());
    let before = r.run_pid_for_test(&id);
    assert!(before.is_some());
    // Two messages that waited more than two seconds for a run that ended:
    // the run that is working now takes both; nothing is restarted (a new
    // run would have a new process).
    r.queue_resend_for_test(&id, "first", Duration::from_secs(3));
    r.queue_resend_for_test(&id, "second", Duration::from_secs(3));
    r.tick();
    assert_eq!(
        r.run_pid_for_test(&id),
        before,
        "a working run was replaced by a new one"
    );
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_stop_cancels_a_message_still_waiting_for_the_pcs_model() {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    let Some(home) = TestHome::new() else { return };
    let unit = home.root.join(".config/systemd/user/mimir-ollama.service");
    std::fs::create_dir_all(unit.parent().unwrap()).unwrap();
    std::fs::write(&unit, "[Service]\nExecStart=/bin/true\n").unwrap();
    // A stand-in Ollama that can be put to sleep and woken.
    let up = Arc::new(AtomicBool::new(true));
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    let u = up.clone();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            if !u.load(Ordering::SeqCst) {
                continue;
            }
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = "{\"models\":[{\"name\":\"qwen3.6:35b-a3b-coding\"}]}";
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
    let started = home.root.join("ollama-started");
    let starter = home.root.join("starter.sh");
    std::fs::write(
        &starter,
        format!("#!/bin/sh\ntouch '{}'\n", started.display()),
    )
    .unwrap();
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&starter, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    set_env("MIMIR_OLLAMA_START", &starter);
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let id = st
        .call_wait(Call::NewTask {
            files: vec![],
            project: home.project.display().to_string(),
            text: "Hello".into(),
            pictures: vec![],
            mode: Mode::Ask,
            model: Some("ollama:qwen3.6:35b-a3b-coding".into()),
            effort: None,
            separate_copy: false,
            msg_id: None,
            ground_rules: None,
        })
        .expect("the task didn't start");
    let id = id.as_str().unwrap().to_string();
    // Now the model goes to sleep, and wakes 2 s after it is asked to.
    up.store(false, Ordering::SeqCst);
    let u = up.clone();
    let flag = started.clone();
    std::thread::spawn(move || {
        while !flag.exists() {
            std::thread::sleep(Duration::from_millis(20));
        }
        std::thread::sleep(Duration::from_secs(2));
        u.store(true, Ordering::SeqCst);
    });
    let mut quick = home.service();
    let task = id.clone();
    let waiting = std::thread::spawn(move || {
        st.call_wait(Call::Send {
            files: vec![],
            task,
            text: "and one more thing".into(),
            pictures: vec![],
            msg_id: None,
        })
    });
    std::thread::sleep(Duration::from_millis(500));
    quick.call_wait(Call::Stop { task: id.clone() }).ok();
    let r = waiting.join().unwrap();
    assert!(
        r.as_ref().is_err_and(|e| e.contains("Stopped")),
        "the message went on after the Stop: {r:?}"
    );
    home.stop_service();
}

/// The developer log of a throwaway service: a chat, a task, a lookup and a
/// phone connect leave the events a reader needs, and a slow message shows
/// up as `slow`. Its `devlog` output is printed for the report.
#[test]
fn the_developer_log_tells_what_happened() {
    let Some(home) = TestHome::new() else { return };
    set_env("MIMIR_TEST_DELAY_MS", "80");
    home.play("run-1.jsonl", "live.jsonl");
    // The notes are off until a person turns them on.
    home.write_settings(serde_json::json!({"labs_devlog": true, "labs_devlog_chats": true}));
    let mut st = home.service();
    // A task.
    let id = st
        .call_wait(Call::NewTask {
            files: vec![],
            project: home.project.display().to_string(),
            text: "Hello".into(),
            pictures: vec![],
            mode: Mode::Ask,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: None,
            ground_rules: None,
        })
        .expect("task");
    let id = id.as_str().unwrap().to_string();
    // A chat turn kept on the PC, and a lookup that is refused (not a site
    // on the list).
    let _ = st.call_wait(Call::Lookup {
        url: "https://example.invalid/x".into(),
    });
    let _ = st.call_wait(Call::Projects);
    std::thread::sleep(Duration::from_millis(1500));
    let dir = home.root.join(".local/state/mimir/devlog");
    // Read it the way the owner's Claude would.
    let run = |args: &[&str]| {
        let out = std::process::Command::new(station_program())
            .arg("devlog")
            .args(args)
            .env("XDG_STATE_HOME", home.root.join(".local/state"))
            .output()
            .unwrap();
        String::from_utf8_lossy(&out.stdout).to_string()
    };
    // Written buffered: it reaches the disk by the flush at the latest.
    let t0 = Instant::now();
    let mut text = run(&["--since", "5m"]);
    while !text.contains(" call ") && t0.elapsed() < Duration::from_secs(12) {
        std::thread::sleep(Duration::from_millis(500));
        text = run(&["--since", "5m"]);
    }
    println!("--- mimir devlog --since 5m ---\n{text}");
    assert!(text.contains(" call "), "{text}");
    assert!(text.contains(&id), "the task isn't in the log:\n{text}");
    assert!(text.contains("lookup"), "{text}");
    let slow = run(&["--slow"]);
    assert!(
        slow.contains("service-message") && slow.contains("Projects"),
        "{slow}"
    );
    assert!(dir.join("important-1.jsonl").is_file());
    // Switching it off deletes the files.
    let mut settings = mimir_core::settings::Settings::read().unwrap_or_default();
    settings.labs_devlog = Some(false);
    let _ = st.call_wait(Call::SetSettings {
        settings: mimir_core::settings::service_part(&settings),
    });
    std::thread::sleep(Duration::from_millis(800));
    let left: Vec<_> = std::fs::read_dir(&dir)
        .map(|d| {
            d.flatten()
                .filter(|e| e.file_name().to_string_lossy().ends_with(".jsonl"))
                .collect()
        })
        .unwrap_or_default();
    assert!(
        left.is_empty(),
        "the log stayed after switching it off: {left:?}"
    );
    home.stop_service();
}
