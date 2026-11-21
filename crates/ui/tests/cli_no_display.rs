//! Terminal commands never need a window: run from a Claude session or the
//! service there is often no display at all, and a command that opened the
//! window's toolkit failed with "neither WAYLAND_DISPLAY nor DISPLAY is set".
//! Each command runs here with no display, on a home of its own.

mod common;
use common::*;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// Runs `mimir <args>` with no display; (exit code, everything it printed).
fn run_without_display(args: &[&str]) -> (Option<i32>, String) {
    let mut child = Command::new(station_program())
        .args(args)
        .env_remove("WAYLAND_DISPLAY")
        .env_remove("DISPLAY")
        .env_remove("XDG_SESSION_TYPE")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let until = Instant::now() + Duration::from_secs(20);
    while child.try_wait().unwrap().is_none() {
        if Instant::now() > until {
            let _ = child.kill();
            let out = child.wait_with_output().unwrap();
            return (None, String::from_utf8_lossy(&out.stderr).to_string());
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let out = child.wait_with_output().unwrap();
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    (out.status.code(), text)
}

#[test]
fn every_terminal_command_runs_without_a_display() {
    let Some(home) = TestHome::new() else { return };
    let commands: &[&[&str]] = &[
        &["--help"],
        &["--version"],
        &["update", "A test update"],
        &["no-such-command"],
        &["status"],
        &["status", "--json"],
        &["pair"],
        &["chat", "hello"],
        &["say", "hello"],
        &["ask", "Which one do you want?"],
        &["approve"],
        &["chats"],
        &["engine"],
    ];
    for args in commands {
        let (code, text) = run_without_display(args);
        assert!(
            code.is_some(),
            "`mimir {}` did not end (it must not wait for a window)\n{text}",
            args.join(" ")
        );
        let lower = text.to_lowercase();
        for bad in ["wayland_display", "winit", "no display", "xopendisplay"] {
            assert!(
                !lower.contains(bad),
                "`mimir {}` needed a display: {text}",
                args.join(" ")
            );
        }
    }
    drop(home);
}

#[test]
fn a_word_that_is_no_command_says_so_and_opens_no_window() {
    let Some(_home) = TestHome::new() else { return };
    let (code, text) = run_without_display(&["update", "x"]);
    assert_eq!(code, Some(2), "{text}");
    assert!(text.contains("no command"), "{text}");
}
