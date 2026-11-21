//! Window checks of the service split: tasks outlive the window, a
//! restarted service is said plainly, the window reconnects, and a new
//! version waits until Claude is done.

mod common;
use common::*;
use ed25519_dalek::SigningKey;
use mimir_core::update;
use station_ui::labels;

#[test]
fn closing_the_window_leaves_the_task_working() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.hold_replies();
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::SEND_NOW)),
        "the task never showed as working\n{}",
        screen_text(&h)
    );
    close(h);
    // Claude goes on while no window is open.
    home.let_through(1000);
    let mut st = home.service();
    let done = service_until(&mut st, |s| {
        s.tasks.iter().any(|v| {
            v.task.status == mimir_core::task::Status::Done
                && v.task.entries.iter().any(|e| {
                    matches!(e, mimir_core::task::Entry::Claude(t) if t.contains("notes.txt is sorted"))
                })
        })
    });
    assert!(done, "the task stopped when the window closed");
}

/// P7a's owner-way test, automatic: a restart (a crash or a new version)
/// in the middle of a task leaves its Claude Code working in its host; the
/// service started again follows it, and the answer arrives.
#[test]
fn a_service_restart_mid_task_leaves_the_work_going() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.hold_replies();
    let mut h = home.open();
    start_task(&mut h, "Run sleep 120, then say done.");
    assert!(wait_for(&mut h, |h| has_button(h, labels::SEND_NOW)));
    home.let_through(1);
    let named = std::time::Instant::now() + std::time::Duration::from_millis(800);
    wait_for(&mut h, |_| std::time::Instant::now() > named);
    let before = home.service_pid();
    home.stop_service();
    // Still working after the restart, and the answer comes.
    let back = wait_for(&mut h, |h| {
        !shows(h, labels::RECONNECTING)
            && home.service_pid().is_some()
            && home.service_pid() != before
    });
    assert!(back, "the service didn't come back\n{}", screen_text(&h));
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::SEND_NOW)),
        "the task isn't working after the restart\n{}",
        screen_text(&h)
    );
    home.let_through(1000);
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt is sorted")),
        "the answer didn't arrive after the restart\n{}",
        screen_text(&h)
    );
    assert!(!shows(&h, "restarted while this task was working"));
    // One Claude Code run: nothing was started again.
    assert_eq!(home.conversations().len(), 1, "{:?}", home.conversations());
    close(h);
}

/// The host of a working task dies: the task says so and stops, and
/// Continue carries it on.
#[test]
fn a_task_whose_host_dies_stops_and_continue_carries_on() {
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
        wait_for(&mut h, |h| shows(
            h,
            "helper for this task stopped unexpectedly"
        )),
        "the lost host wasn't said\n{}",
        screen_text(&h)
    );
    home.let_through(1000);
    click(&mut h, "Continue");
    assert!(
        wait_for(&mut h, |h| shows(h, "I created")),
        "Continue didn't carry on\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn the_window_reconnects_after_the_service_restarts() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(wait_for(&mut h, |h| shows(h, "notes.txt is sorted")));
    home.stop_service();
    assert!(
        wait_for(&mut h, |h| shows(h, labels::RECONNECTING)),
        "the window didn't say it lost the service\n{}",
        screen_text(&h)
    );
    // The last state stays on screen, and comes back the same.
    assert!(shows(&h, "notes.txt is sorted"));
    let back = wait_for(&mut h, |h| {
        !shows(h, labels::RECONNECTING) && shows(h, "notes.txt is sorted")
    });
    assert!(
        back && home.service_pid().is_some(),
        "the window didn't reconnect to a restarted service\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn a_new_version_opens_while_a_task_works() {
    let Some(home) = TestHome::new() else { return };
    const NEW: &str = "0.16";
    const PROGRAM: &[u8] = b"a made-up flatpak file\n";
    let key = SigningKey::from_bytes(&[42u8; 32]);
    let name = update::asset_name(NEW);
    let releases = home.root.join("updates");
    std::fs::write(releases.join(&name), PROGRAM).unwrap();
    std::fs::write(
        releases.join(format!("{name}.sig")),
        update::sign(&key, NEW, PROGRAM),
    )
    .unwrap();
    set_env(
        "MIMIR_TEST_KEY",
        update::to_hex(&key.verifying_key().to_bytes()),
    );
    set_env(
        "MIMIR_TEST_OPEN",
        home.root.join("opened.txt").display().to_string(),
    );
    home.play("run-1.jsonl", "live.jsonl");
    home.hold_replies();
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(wait_for(&mut h, |h| has_button(h, labels::SEND_NOW)));
    let offer = labels::update_to(NEW);
    assert!(wait_for(&mut h, |h| has_button(h, &offer)));
    click(&mut h, &offer);
    // The update opens in the software centre at once; the task goes on
    // working and the program is not restarted.
    let opened = wait_for(&mut h, |h| shows(h, &labels::update_opened(NEW)));
    assert!(
        opened && has_button(&h, labels::SEND_NOW) && home.service_pid().is_some(),
        "the update disturbed the working task\n{}",
        screen_text(&h)
    );
    home.let_through(1000);
    close(h);
}

/// systemd's socket unit put in place after a service already runs (the
/// race after a restart): a new listening socket at the window's path.
fn systemd_socket_over(home: &TestHome) -> std::os::unix::net::UnixListener {
    use std::os::unix::fs::PermissionsExt;
    let path = home.root.join("run/mimir/control/control.sock");
    for d in [
        path.parent().unwrap(),
        path.parent().unwrap().parent().unwrap(),
    ] {
        std::fs::create_dir_all(d).unwrap();
        std::fs::set_permissions(d, std::fs::Permissions::from_mode(0o700)).unwrap();
    }
    let _ = std::fs::remove_file(&path);
    std::os::unix::net::UnixListener::bind(&path).unwrap()
}

/// A service started the way systemd starts it: the socket as fd 3, with
/// LISTEN_PID and LISTEN_FDS.
fn service_from_systemd(socket: &std::os::unix::net::UnixListener) -> std::process::Child {
    use std::os::fd::AsRawFd;
    use std::os::unix::process::CommandExt;
    let fd = socket.as_raw_fd();
    let mut cmd = std::process::Command::new("sh");
    cmd.arg("-c")
        .arg(r#"LISTEN_PID=$$ LISTEN_FDS=1 LISTEN_FDNAMES=mimir.socket LISTEN_PIDFDID=1 NOTIFY_SOCKET=/nonexistent exec "$0" service"#)
        .arg(station_program())
        .stdin(std::process::Stdio::null());
    // SAFETY: dup2 and fcntl only, between fork and exec.
    unsafe {
        cmd.pre_exec(move || {
            // dup2 onto itself would leave it closed on exec.
            if fd == 3 {
                libc::fcntl(3, libc::F_SETFD, 0);
            } else if libc::dup2(fd, 3) < 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    cmd.spawn().unwrap()
}

/// Waits up to `secs` for a process to end; its exit code.
fn ends_within(child: &mut std::process::Child, secs: u64) -> Option<i32> {
    let until = std::time::Instant::now() + std::time::Duration::from_secs(secs);
    while std::time::Instant::now() < until {
        if let Ok(Some(st)) = child.try_wait() {
            return st.code();
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    None
}

#[test]
fn a_service_systemd_starts_while_one_runs_hands_over_and_never_fails() {
    let Some(home) = TestHome::new() else { return };
    // The window started a service before systemd was ready.
    let first = home.service();
    let first_pid = home.service_pid().expect("the first service's pid");
    // systemd's socket appears, and a window connecting there starts a
    // second service.
    let socket = systemd_socket_over(&home);
    let mut second = service_from_systemd(&socket);
    let path = home.root.join("run/mimir/control/control.sock");
    // Whoever connects reaches the service that runs.
    let mut reached = None;
    let until = std::time::Instant::now() + std::time::Duration::from_secs(10);
    while reached.is_none() && std::time::Instant::now() < until {
        reached =
            mimir_core::client::Station::connect(&path, "0.15", std::sync::Arc::new(|| {})).ok();
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    let reached = reached.expect("a window couldn't reach the running service");
    assert_eq!(
        reached.run_id, first.run_id,
        "the window reached a different service than the one running"
    );
    // The second neither failed nor stormed: it waits, as systemd sees it.
    assert!(
        second.try_wait().unwrap().is_none(),
        "the second service ended while the first ran"
    );
    assert_eq!(home.service_pid(), Some(first_pid));
    // The first ends: the second asks systemd for a fresh start in its place.
    drop(first);
    drop(reached);
    home.stop_service();
    assert_eq!(
        ends_within(&mut second, 5),
        Some(75),
        "the second service didn't ask for a fresh start when the first ended"
    );
    // systemd's socket file is still there (the first didn't remove it).
    assert!(path.exists(), "the first service removed systemd's socket");
    // The fresh start serves the window on systemd's socket.
    let mut fresh = service_from_systemd(&socket);
    let mut back = false;
    let until = std::time::Instant::now() + std::time::Duration::from_secs(10);
    while !back && std::time::Instant::now() < until {
        back =
            mimir_core::client::Station::connect(&path, "0.15", std::sync::Arc::new(|| {})).is_ok();
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    assert!(back, "the fresh start didn't serve the window");
    // SAFETY: a signal to a process this check started.
    unsafe { libc::kill(fresh.id() as i32, libc::SIGTERM) };
    assert_eq!(ends_within(&mut fresh, 10), Some(0));
    // Why each service ended is kept in the log.
    let log =
        std::fs::read_to_string(home.root.join(".local/state/mimir/log.jsonl")).unwrap_or_default();
    assert!(
        log.contains("the other service ended"),
        "the reason wasn't logged\n{log}"
    );
}

#[test]
fn a_second_service_without_systemd_ends_quietly_with_success() {
    let Some(home) = TestHome::new() else { return };
    let first = home.service();
    let mut second = std::process::Command::new(station_program())
        .arg("service")
        .stdin(std::process::Stdio::null())
        .spawn()
        .unwrap();
    assert_eq!(
        ends_within(&mut second, 10),
        Some(0),
        "a second service didn't end quietly with success"
    );
    // The first goes on serving.
    let path = home.root.join("run/mimir/control/control.sock");
    let again = mimir_core::client::Station::connect(&path, "0.15", std::sync::Arc::new(|| {}))
        .expect("the running service stopped serving");
    assert_eq!(again.run_id, first.run_id);
}

#[test]
fn the_window_tries_again_when_the_service_cant_start() {
    let Some(home) = TestHome::new() else { return };
    // The service can't start at all.
    set_env("MIMIR_TEST_PROGRAM", home.root.join("no-such-program"));
    let mut h = home.open();
    assert!(
        shows(&h, "Mímir could not start") && has_button(&h, labels::TRY_AGAIN),
        "no Try again on the error screen\n{}",
        screen_text(&h)
    );
    assert!(shows(&h, "Trying again by itself in"));
    // Once it can, Try again connects.
    set_env("MIMIR_TEST_PROGRAM", station_program());
    click(&mut h, labels::TRY_AGAIN);
    let back = wait_for(&mut h, |h| !shows(h, "could not start"));
    assert!(
        back,
        "the window didn't connect after Try again\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn the_window_tries_again_by_itself() {
    let Some(home) = TestHome::new() else { return };
    set_env("MIMIR_TEST_PROGRAM", home.root.join("no-such-program"));
    let mut h = home.open();
    assert!(shows(&h, "Mímir could not start"));
    set_env("MIMIR_TEST_PROGRAM", station_program());
    // No click: the window tries again on its own within a few seconds.
    let until = std::time::Instant::now() + std::time::Duration::from_secs(20);
    let mut back = false;
    while !back && std::time::Instant::now() < until {
        back = wait_for(&mut h, |h| !shows(h, "could not start"));
    }
    assert!(
        back,
        "the window didn't try again by itself\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn what_the_service_starts_never_gets_systemds_socket() {
    let Some(home) = TestHome::new() else { return };
    let env_log = home.root.join("claude-env.log");
    set_env("FAKE_CLAUDE_ENV", &env_log);
    // The service as systemd starts it: its socket as fd 3, and systemd's
    // variables.
    let socket = systemd_socket_over(&home);
    let mut service = service_from_systemd(&socket);
    drop(socket);
    home.play("run-1.jsonl", "live.jsonl");
    let mut h = home.open();
    assert!(
        !shows(&h, "could not start"),
        "the window didn't reach the service\n{}",
        screen_text(&h)
    );
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt is sorted")),
        "the task didn't run\n{}",
        screen_text(&h)
    );
    close(h);
    let log = std::fs::read_to_string(&env_log).unwrap_or_default();
    let runs: Vec<serde_json::Value> = log
        .lines()
        .filter_map(|l| serde_json::from_str(l).ok())
        .collect();
    assert!(!runs.is_empty(), "Claude never ran");
    for r in &runs {
        assert_eq!(
            r["systemd"],
            serde_json::json!([]),
            "Claude was given systemd's variables: {r}"
        );
        assert_eq!(r["fd3"], false, "Claude was given systemd's socket: {r}");
    }
    // SAFETY: a signal to a process this check started.
    unsafe { libc::kill(service.id() as i32, libc::SIGTERM) };
    assert_eq!(ends_within(&mut service, 10), Some(0));
}

#[test]
fn another_sessions_permission_request_is_answered_in_the_window() {
    let Some(home) = TestHome::new() else { return };
    let mut h = home.open();
    let project = home.project.display().to_string();
    let hook = approve_hook(&project, false);
    assert!(
        wait_for(&mut h, |h| has_button(h, "Answer on the PC")),
        "the request never showed in the window\n{}",
        screen_text(&h)
    );
    assert!(shows(&h, "hello.txt"), "{}", screen_text(&h));
    assert!(has_button(&h, "Always allow") && has_button(&h, "Don't allow"));
    click(&mut h, "Allow");
    let out: serde_json::Value = serde_json::from_str(&hook_printed(hook)).unwrap();
    assert_eq!(out["hookSpecificOutput"]["decision"]["behavior"], "allow");
    assert!(
        wait_for(&mut h, |h| !has_button(h, "Answer on the PC")),
        "the answered request stayed"
    );
    close(h);
}
