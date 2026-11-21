//! A throwaway Mímir for testing the phone app on a real phone:
//! a private test home with the stand-in Claude, the link on n0's relays
//! (the path a phone uses from anywhere), and pairing confirmed by itself.
//! Never the owner's own Mímir.
//!
//!     DEVICE_DIR=/some/folder DEVICE_MINUTES=10 \
//!       cargo nextest run -p mimir --test phone_device --run-ignored only
//!
//! It writes `code` (the pairing code) and `link.log` (pairings, phones
//! connecting and leaving) into DEVICE_DIR, and stops after DEVICE_MINUTES
//! or once DEVICE_DIR/stop exists.
//!
//! Several testers can run one each: `DEVICE_PC_NAME="Test PC (Breaker)"`
//! is the name its phones show, so anyone can tell whose test a device is
//! in. `CHECK_HOME_NAME=breaker` keeps the home under that name: started
//! again, it is the same PC to its phones (to test "the PC comes back").
//! Remove a kept home when the test is over.

mod common;
use common::*;
use mimir_core::proto::Call;
use std::io::Write;
use std::time::{Duration, Instant};

#[test]
#[ignore = "runs only for a test on a real phone"]
fn device_test_service() {
    let dir = std::path::PathBuf::from(std::env::var("DEVICE_DIR").expect("set DEVICE_DIR"));
    let minutes: u64 = std::env::var("DEVICE_MINUTES")
        .ok()
        .and_then(|m| m.parse().ok())
        .unwrap_or(10);
    std::fs::create_dir_all(&dir).unwrap();
    let Some(home) = TestHome::new() else { return };
    let project = home.project.clone();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": true,
        "pc_name": std::env::var("DEVICE_PC_NAME").ok(),
    }));
    for n in 1..=8 {
        home.play(&format!("run-{n}.jsonl"), "allow.jsonl");
    }
    home.play("run-9.jsonl", "question.jsonl");
    home.play("run-10.jsonl", "live.jsonl");
    for n in 11..=30 {
        home.play(&format!("run-{n}.jsonl"), "allow.jsonl");
    }
    // Follow-ups to a task resume it: every one gets a reply, so a second
    // message never stops the task.
    home.play("resume.jsonl", "resume.jsonl");
    let mut st = home.service();
    let mut log = std::fs::File::create(dir.join("link.log")).unwrap();
    let mut note = |s: &str| {
        let _ = writeln!(log, "{:>7.1}s {s}", start_secs());
    };
    let code = st
        .call_wait(Call::PairCode)
        .expect("no pairing code")
        .as_str()
        .unwrap()
        .to_string();
    std::fs::write(dir.join("code"), &code).unwrap();
    note("code written");
    let until = Instant::now() + Duration::from_secs(minutes * 60);
    // Each session a phone opens moves its "last seen".
    let mut seen = String::new();
    while Instant::now() < until && !dir.join("stop").exists() {
        st.poll();
        if let Some((pending, name, words)) = st.pairing.clone() {
            note(&format!("pairing {name}: {}", words.join(" ")));
            let _ = st.call_wait(Call::ConfirmPair {
                pending,
                yes: true,
                person: Some(mimir_core::proto::WHOSE_MINE.into()),
            });
            // A fresh code for the next phone (a code works once).
            if let Ok(c) = st.call_wait(Call::PairCode) {
                std::fs::write(dir.join("code"), c.as_str().unwrap_or_default()).unwrap();
                note("new code written");
            }
        }
        if let Ok(v) = st.call_wait(Call::Phones) {
            let now: String = v
                .as_array()
                .cloned()
                .unwrap_or_default()
                .iter()
                .map(|p| format!("{}@{} ", p["name"], p["last_seen"]))
                .collect();
            if now != seen {
                note(&format!("phones: {now}"));
                seen = now;
            }
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    note("stopped");
}

fn start_secs() -> f64 {
    static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_secs_f64()
}
