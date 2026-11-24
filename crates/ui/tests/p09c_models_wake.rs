//! 0.09 item 25: a phone asking which models the PC has wakes the PC's model program when it
//! is asleep, instead of leaving the phone to answer with its own model.

mod common;
use common::*;
use mimir_core::proto::Call;
use std::time::{Duration, Instant};

#[test]
fn p09_item25_asking_for_the_models_starts_the_sleeping_model_program() {
    let Some(home) = TestHome::new() else { return };
    let unit = home.root.join(".config/systemd/user/mimir-ollama.service");
    std::fs::create_dir_all(unit.parent().unwrap()).unwrap();
    std::fs::write(&unit, "[Service]\nExecStart=/bin/true\n").unwrap();
    // Nothing listens here: the model program is asleep.
    let port = {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().port()
    };
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
    let mut st = home.service();
    let v = st.call_wait(Call::Models).expect("no model list");
    assert!(
        v["ollama"].as_str().is_some_and(|s| !s.is_empty()),
        "the model program is asleep, and the list says so: {v}"
    );
    let t0 = Instant::now();
    while !started.exists() && t0.elapsed() < Duration::from_secs(5) {
        std::thread::sleep(Duration::from_millis(20));
    }
    assert!(
        started.exists(),
        "asking for the models did not start the model program"
    );
}
