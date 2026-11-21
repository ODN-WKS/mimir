//! Checks of Mímir's service on its own, with no window: the
//! check's own program talks to it over the window's socket. Also the
//! socket refusals (S-1: a Claude session can't approve its own requests).

mod common;
use common::*;
use mimir_core::approvals::Answer;
use mimir_core::sockets::{SessionMsg, read_msg, write_msg};
use mimir_core::task::Mode;
use std::io::Write;
use std::os::unix::net::UnixStream;
use std::path::Path;
use std::time::Duration;

/// Starts a task on the service; its id.
fn start(st: &mut mimir_core::client::Station, home: &TestHome, text: &str) -> String {
    st.new_task(
        &home.project.display().to_string(),
        text,
        &[],
        Mode::Ask,
        None,
        None,
        false,
    )
    .expect("the task didn't start")
}

fn asks(st: &mimir_core::client::Station, id: &str) -> usize {
    st.tasks
        .iter()
        .find(|v| v.task.id == id)
        .map_or(0, |v| v.asks.len())
}

fn busy(st: &mimir_core::client::Station, id: &str) -> bool {
    st.tasks.iter().any(|v| v.task.id == id && v.busy())
}

fn shows(st: &mimir_core::client::Station, id: &str, text: &str) -> bool {
    st.tasks.iter().any(|v| {
        v.task.id == id
            && v.task
                .entries
                .iter()
                .any(|e| matches!(e, mimir_core::task::Entry::Claude(t) if t.contains(text)))
    })
}

#[test]
fn service_answer_reaches_claude_through_the_control_socket() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "allow.jsonl");
    let mut st = home.service();
    let id = start(&mut st, &home, "Run this Bash command: touch a.txt");
    assert!(
        service_until(&mut st, |s| asks(s, &id) == 1),
        "the permission request never reached the service's client"
    );
    st.answer(&id, Answer::Allow);
    let mut answered = false;
    service_until(&mut st, |_| {
        answered = home.hook_answers() == ["allow"];
        answered
    });
    assert!(
        answered,
        "the answer sent through the control socket didn't reach Claude: {:?}",
        home.hook_answers()
    );
}

#[test]
fn service_queued_message_goes_when_the_task_finishes() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.play("resume.jsonl", "resume.jsonl");
    home.hold_replies();
    let mut st = home.service();
    let id = start(&mut st, &home, "Tidy up notes.txt.");
    assert!(service_until(&mut st, |s| busy(s, &id)));
    st.queue(&id, "And then say what you made.", vec![]);
    home.let_through(1000);
    let mut sent = false;
    service_until(&mut st, |_| {
        sent = home
            .messages_to_claude()
            .iter()
            .any(|m| m.contains("And then say what you made."));
        sent
    });
    assert!(
        sent,
        "a message queued until done didn't go out when the task finished: {:?}",
        home.messages_to_claude()
    );
}

/// Runs `sh -c script` on the machine the service runs on (the host, when
/// the check runs in a distrobox), and gives its output.
fn on_service_host(script: &str) -> String {
    let in_box = Path::new("/run/.containerenv").exists();
    let mut cmd = if in_box {
        let mut c = std::process::Command::new("distrobox-host-exec");
        c.arg("sh");
        c
    } else {
        std::process::Command::new("sh")
    };
    let out = cmd.arg("-c").arg(script).output().expect("sh");
    String::from_utf8_lossy(&out.stdout).to_string() + &String::from_utf8_lossy(&out.stderr)
}

#[test]
fn sec1_a_task_process_is_refused_on_the_control_socket() {
    let Some(home) = TestHome::new() else { return };
    let _st = home.service();
    let pid = home.service_pid().expect("the service's pid");
    let socket = home.root.join("run/mimir/control/control.sock");
    // A process placed in a task's group, as every Claude Code is, connects
    // to the window's socket and reads what the service says.
    let script = format!(
        "g=$(cut -d: -f3 /proc/{pid}/cgroup); g=${{g%/service}}; d=/sys/fs/cgroup$g/task-fake; \
         mkdir -p $d && echo $$ > $d/cgroup.procs && exec python3 -c '\
import socket,struct,sys\n\
s=socket.socket(socket.AF_UNIX); s.connect(sys.argv[1]); s.settimeout(5)\n\
n=struct.unpack(\"<I\", s.recv(4))[0]; print(s.recv(n).decode())' {}",
        socket.display()
    );
    let said = on_service_host(&script);
    assert!(
        said.contains("inside Mímir"),
        "a process inside a task's group wasn't refused on the control socket: {said}"
    );
}

#[test]
fn sec2_a_made_up_answer_on_the_session_socket_is_refused() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "allow.jsonl");
    let mut st = home.service();
    let id = start(&mut st, &home, "Run this Bash command: touch a.txt");
    assert!(service_until(&mut st, |s| asks(s, &id) == 1));
    // A Claude session tries to answer its own request.
    let mut s = UnixStream::connect(home.root.join("run/mimir/session.sock")).unwrap();
    let answer = serde_json::json!({"Answer": {"task": id, "choice": "Allow"}});
    write_msg(&mut s, &answer).unwrap();
    let reply: Option<String> = read_msg(&mut s).unwrap_or(None);
    std::thread::sleep(Duration::from_millis(500));
    st.poll();
    assert!(
        reply.as_deref().is_some_and(|r| r.starts_with("refused"))
            && asks(&st, &id) == 1
            && home.hook_answers().is_empty(),
        "a made-up answer on the session socket was taken: {reply:?}, hooks {:?}",
        home.hook_answers()
    );
    // A real ask is understood (and not an answer).
    let mut s = UnixStream::connect(home.root.join("run/mimir/session.sock")).unwrap();
    write_msg(
        &mut s,
        &SessionMsg::Ask {
            text: "hello".into(),
            choices: vec![],
            wait: false,
            project: None,
            kind: None,
            link: None,
        },
    )
    .unwrap();
    let _: Option<String> = read_msg(&mut s).unwrap_or(None);
    st.answer(&id, Answer::No);
}

#[test]
fn sec3_a_made_up_answer_on_the_approvals_socket_is_refused() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "allow.jsonl");
    let mut st = home.service();
    let id = start(&mut st, &home, "Run this Bash command: touch a.txt");
    // The request waits for the owner (or, if something answered it
    // already, the check below says so).
    service_until(&mut st, |s| {
        asks(s, &id) == 1 || !home.hook_answers().is_empty()
    });
    // A command run by Claude tries to answer through the hook's socket.
    let mut s = UnixStream::connect(home.task_socket(&id)).unwrap();
    writeln!(s, "\"Allow\"").unwrap();
    drop(s);
    std::thread::sleep(Duration::from_millis(500));
    st.poll();
    assert!(
        asks(&st, &id) == 1 && home.hook_answers().is_empty(),
        "a made-up answer on the approvals socket was taken: asks {}, hooks {:?}",
        asks(&st, &id),
        home.hook_answers()
    );
    st.answer(&id, Answer::No);
}

#[test]
fn sec4_a_hook_naming_another_task_counts_for_its_own() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let a = start(&mut st, &home, "Tidy up notes.txt.");
    assert!(service_until(&mut st, |s| shows(
        s,
        &a,
        "notes.txt is sorted"
    ) && !busy(s, &a)));
    // Task A's hook asks, naming a task that isn't A.
    let request = serde_json::json!({
        "task": "some-other-task",
        "tool_name": "Bash",
        "tool_input": {"command": "rm -rf /tmp/x"},
    });
    let mut s = UnixStream::connect(home.task_socket(&a)).unwrap();
    writeln!(s, "{request}").unwrap();
    let mut seen = false;
    service_until(&mut st, |s| {
        seen = s
            .tasks
            .iter()
            .find(|v| v.task.id == a)
            .is_some_and(|v| v.asks.iter().any(|q| q.request.task == a));
        seen
    });
    assert!(
        seen,
        "a request on task A's socket wasn't treated as task A's"
    );
    st.answer(&a, Answer::No);
}

/// Reads every file under `dir` (small ones) and says whether `needle` is in any.
fn found_in_files(dir: &Path, needle: &str) -> Option<std::path::PathBuf> {
    for e in std::fs::read_dir(dir).ok()?.flatten() {
        let p = e.path();
        let Ok(t) = e.file_type() else { continue };
        if t.is_dir() {
            if let Some(f) = found_in_files(&p, needle) {
                return Some(f);
            }
        } else if t.is_file()
            && std::fs::read(&p).is_ok_and(|b| {
                b.len() < 4 << 20 && b.windows(needle.len()).any(|w| w == needle.as_bytes())
            })
        {
            return Some(p);
        }
    }
    None
}

/// In a Flatpak the control socket can't tell a window from a program the
/// PC runs for a task (same user, can open the 0700 folder, process number
/// not visible). The window proves itself with a secret the service hands
/// only to a window it starts, over an inherited socket. A program that
/// reads the folder, the service's files and its environment still can't
/// approve anything.
#[test]
fn sec5_a_host_process_that_reads_the_folder_is_refused_without_the_secret() {
    let Some(home) = TestHome::new() else { return };
    let socket = home.root.join("run/mimir/control/control.sock");
    let got = home.root.join("window-got-secret");
    let script = home.root.join("fake-window.sh");
    std::fs::write(
        &script,
        format!("#!/bin/sh\ncat <&3 > '{}'\n", got.display()),
    )
    .unwrap();
    std::fs::set_permissions(&script, std::os::unix::fs::PermissionsExt::from_mode(0o755)).unwrap();
    let mut service = std::process::Command::new(station_program())
        .arg("service")
        .env("MIMIR_FAKE_CAP", "1")
        .env("MIMIR_TEST_WINDOW_PROG", &script)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .expect("the service didn't start");
    let up = |_: ()| UnixStream::connect(&socket).is_ok();
    let began = std::time::Instant::now();
    while !up(()) && began.elapsed() < Duration::from_secs(15) {
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(up(()), "the service's control socket didn't open");

    // 1. A program with the same user: no message, a call, a wrong secret.
    let refused = |first: Option<serde_json::Value>| -> bool {
        let mut s = UnixStream::connect(&socket).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(8))).unwrap();
        if let Some(v) = first {
            write_msg(&mut s, &v).unwrap();
        }
        let r: Option<String> = read_msg(&mut s).unwrap_or(None);
        r.is_some_and(|r| r.starts_with("refused"))
    };
    assert!(refused(None), "a silent connection was let in");
    assert!(
        refused(Some(
            serde_json::json!({"call_id": 1, "call": {"Focus": {"focused": true}}})
        )),
        "a call without the secret was let in"
    );
    assert!(
        refused(Some(serde_json::json!({"Cap": "00"}))),
        "a wrong secret was let in"
    );

    // 2. A launcher asks for a window: the service starts it and hands it the secret.
    let mut s = UnixStream::connect(&socket).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(8))).unwrap();
    write_msg(&mut s, &mimir_core::sockets::Opening::OpenWindow(vec![])).unwrap();
    let r: Option<String> = read_msg(&mut s).unwrap_or(None);
    assert_eq!(r.as_deref(), Some("ok"), "the window wasn't started");
    let began = std::time::Instant::now();
    while std::fs::read_to_string(&got).map_or(true, |t| t.trim().len() < 64)
        && began.elapsed() < Duration::from_secs(10)
    {
        std::thread::sleep(Duration::from_millis(100));
    }
    let secret = std::fs::read_to_string(&got)
        .unwrap_or_default()
        .trim()
        .to_string();
    assert_eq!(secret.len(), 64, "the window wasn't handed the secret");
    // 3. The secret is nowhere a program of the same user can read it:
    // not in the folder, the home, the service's environment or its command line.
    std::fs::remove_file(&got).unwrap();
    let pid = service.id();
    for f in ["environ", "cmdline"] {
        let b = std::fs::read(format!("/proc/{pid}/{f}")).unwrap_or_default();
        assert!(
            !b.windows(64).any(|w| w == secret.as_bytes()),
            "the secret is in the service's {f}"
        );
    }
    assert_eq!(
        found_in_files(&home.root, &secret),
        None,
        "the secret is in a file"
    );
    // 4. A second request right away is held back.
    let mut s = UnixStream::connect(&socket).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(8))).unwrap();
    write_msg(&mut s, &mimir_core::sockets::Opening::OpenWindow(vec![])).unwrap();
    let r: Option<String> = read_msg(&mut s).unwrap_or(None);
    assert!(
        r.is_some_and(|r| r.starts_with("refused")),
        "windows can be opened in a flood"
    );

    // 5. The window with the secret is let in and can use the service.
    let mut fds = [0i32; 2];
    // SAFETY: pipe fills two descriptors.
    assert_eq!(unsafe { libc::pipe(fds.as_mut_ptr()) }, 0);
    let msg = secret.as_bytes();
    // SAFETY: writing to the pipe's write end, then closing it.
    unsafe {
        libc::write(fds[1], msg.as_ptr() as *const _, msg.len());
        libc::close(fds[1]);
        std::env::set_var("MIMIR_CAP_FD", fds[0].to_string());
        std::env::set_var("MIMIR_FAKE_CAP", "1");
    }
    assert!(mimir_core::sockets::take_held());
    let mut st = mimir_core::client::Station::connect(&socket, "0.15", std::sync::Arc::new(|| {}))
        .expect("the window with the secret wasn't let in");
    let seen = (0..100).any(|_| {
        st.poll();
        std::thread::sleep(Duration::from_millis(50));
        st.truth.is_some()
    });
    assert!(seen, "the window with the secret got no state");
    drop(st);
    home.stop_service();
    let _ = service.kill();
    let _ = service.wait();
}
