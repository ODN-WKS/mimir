//! Phase 9 checks: Whisper mode (incognito in the code). The stand-in Claude runs inside the sealed
//! space exactly as Claude Code would.

mod common;
use common::*;
use mimir_core::proto::Call;
use mimir_core::task::Mode;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::process::Command;

fn fake_ollama() {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
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
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
}

/// Runs `script` inside a sealed space set up the way an Incognito task's is.
fn inside(home: &TestHome, script: &str) -> (i32, String) {
    let dir = home.root.join("run/mimir-incognito/check");
    std::fs::create_dir_all(&dir).unwrap();
    let out = Command::new("unshare")
        .args(["-rnmp", "--fork", "--mount-proc"])
        .arg(station_program())
        .arg("incognito-launch")
        .arg(&dir)
        .args(["--", "bash", "-c", script])
        .output()
        .unwrap();
    (
        out.status.code().unwrap_or(-1),
        format!(
            "{}{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        ),
    )
}

#[test]
fn p9_the_sealed_space_has_no_internet_sockets_or_keys() {
    let Some(home) = TestHome::new() else { return };
    // What must stay out of reach: the service's sockets and the link's key.
    let run = home.root.join("run/mimir");
    std::fs::create_dir_all(run.join("control")).unwrap();
    let _a = std::os::unix::net::UnixListener::bind(run.join("session.sock")).unwrap();
    let _b = std::os::unix::net::UnixListener::bind(run.join("control/control.sock")).unwrap();
    let key = home.root.join(".config/mimir/link/key");
    std::fs::create_dir_all(key.parent().unwrap()).unwrap();
    std::fs::write(&key, "secret").unwrap();
    let data = home.root.join(".local/share/mimir/tasks");
    std::fs::create_dir_all(&data).unwrap();
    std::fs::write(data.join("a.json"), "{}").unwrap();

    let (code, out) = inside(
        &home,
        &format!(
            "test ! -e {run}/session.sock && test ! -e {run}/control/control.sock \
             && ! cat {key} 2>/dev/null && test ! -e {data}/a.json \
             && ! (exec 3<>/dev/tcp/1.1.1.1/443) 2>/dev/null && echo SEALED",
            run = run.display(),
            key = key.display(),
            data = data.display()
        ),
    );
    assert!(
        code == 0 && out.contains("SEALED"),
        "not sealed: {code} {out}"
    );
    // Outside the space everything is still there.
    assert!(std::fs::read(&key).is_ok() && run.join("session.sock").exists());

    // The launcher's own probe passed (it starts nothing otherwise) and the
    // internet can't be reached from inside.
    let (code, out) = inside(
        &home,
        "curl -s -m 3 -o /dev/null https://example.com && echo REACHED || echo BLOCKED",
    );
    assert_eq!(code, 0, "{out}");
    assert!(out.contains("BLOCKED"), "the internet was reachable: {out}");
}

#[test]
fn p9_not_sealed_means_nothing_starts() {
    let Some(home) = TestHome::new() else { return };
    // Run without the sealed space: loopback and the hiding can't be done.
    let dir = home.root.join("run/mimir-incognito/x");
    std::fs::create_dir_all(&dir).unwrap();
    let out = Command::new(station_program())
        .arg("incognito-launch")
        .arg(&dir)
        .args(["--", "sh", "-c", "echo STARTED"])
        .output()
        .unwrap();
    let text =
        String::from_utf8_lossy(&out.stdout).to_string() + &String::from_utf8_lossy(&out.stderr);
    assert_eq!(out.status.code(), Some(3), "{text}");
    assert!(!text.contains("STARTED"), "it started anyway: {text}");
    assert!(text.contains("couldn't seal itself off"), "{text}");
}

#[test]
fn p9_incognito_task_is_never_saved_and_ends_without_a_trace() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama();
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
    let replied = service_until(&mut st, |s| {
        s.tasks.iter().any(|v| {
            v.task.id == id
                && !v.busy()
                && v.task
                    .entries
                    .iter()
                    .any(|e| matches!(e, mimir_core::task::Entry::Claude(_)))
        })
    });
    let entries: Vec<String> = st
        .tasks
        .iter()
        .find(|v| v.task.id == id)
        .map(|v| v.task.entries.iter().map(|e| format!("{e:?}")).collect())
        .unwrap_or_default();
    assert!(replied, "the sealed Claude never replied: {entries:?}");
    let header_incognito = st.tasks.iter().any(|v| v.task.id == id && v.task.incognito);
    assert!(
        header_incognito,
        "the task isn't marked as a Whisper (incognito) for clients"
    );
    // It ran inside the space: under unshare, with its settings in RAM.
    let runs = home.claude_runs();
    assert!(!runs.is_empty());
    // Nothing saved: no task file, no update, no needs-you push.
    let store = home.root.join(".local/share/mimir/tasks");
    let saved: Vec<String> = std::fs::read_dir(&store)
        .map(|r| {
            r.flatten()
                .map(|e| e.file_name().to_string_lossy().to_string())
                .collect()
        })
        .unwrap_or_default();
    assert!(
        !saved.iter().any(|f| f.contains(&id)),
        "the task was saved: {saved:?}"
    );
    assert!(
        st.updates
            .iter()
            .all(|u| u.task.as_deref() != Some(id.as_str())),
        "it made an update"
    );
    let folder = mimir_core::incognito::dir(&id);
    assert!(
        folder.is_dir(),
        "its RAM folder isn't there: {}",
        folder.display()
    );
    // End and forget.
    st.call_wait(Call::EndIncognito { task: id.clone() })
        .expect("end failed");
    assert!(
        service_until(&mut st, |s| !s.tasks.iter().any(|v| v.task.id == id)),
        "the task is still listed"
    );
    assert!(!folder.exists(), "its folder is still there");
    let left = Command::new("pgrep")
        .args(["-f", folder.to_str().unwrap()])
        .output()
        .unwrap();
    assert!(
        left.stdout.is_empty(),
        "processes of it are left: {}",
        String::from_utf8_lossy(&left.stdout)
    );
    // Only free models.
    let r = st.call_wait(Call::NewIncognito {
        ground_rules: None,
        project: home.project.display().to_string(),
        text: "Hi".into(),
        model: "claude-opus-5-5".into(),
        mode: Mode::Ask,
    });
    assert!(r.is_err(), "Whisper mode took a Claude model");
}
