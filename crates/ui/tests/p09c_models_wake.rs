//! 0.09 item 25: a phone asking which models the PC has wakes the PC's model program when it
//! is asleep, instead of leaving the phone to answer with its own model.

mod common;
use common::*;
use mimir_core::proto::Call;
use std::io::{Read, Write};
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
        format!("#!/bin/sh\necho x >> '{}'\n", started.display()),
    )
    .unwrap();
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&starter, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    set_env("MIMIR_OLLAMA_START", &starter);
    // The model program comes up (answering its model list) once it was asked to start.
    {
        let flag = started.clone();
        std::thread::spawn(move || {
            while !flag.exists() {
                std::thread::sleep(Duration::from_millis(20));
            }
            let l = std::net::TcpListener::bind(("127.0.0.1", port)).unwrap();
            for s in l.incoming().flatten() {
                let mut s = s;
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
    let mut st = home.service();
    let v = st.call_wait(Call::Models).expect("no model list");
    assert!(
        v["ollama"].as_str().is_some_and(|s| !s.is_empty()),
        "the model program is asleep, and the list says so: {v}"
    );
    // Asking again at once does not start it a second time.
    let _ = st.call_wait(Call::Models);
    let t0 = Instant::now();
    while !started.exists() && t0.elapsed() < Duration::from_secs(5) {
        std::thread::sleep(Duration::from_millis(20));
    }
    assert!(
        started.exists(),
        "asking for the models did not start the model program"
    );
    std::thread::sleep(Duration::from_millis(500));
    let starts = std::fs::read_to_string(&started).unwrap().lines().count();
    assert_eq!(starts, 1, "the model program was started {starts} times");
    // A later look finds the model, and no complaint.
    let t0 = Instant::now();
    loop {
        let v = st.call_wait(Call::Models).expect("no model list");
        let has = v["free"].as_array().is_some_and(|a| !a.is_empty());
        if has && v["ollama"].as_str() == Some("") {
            break;
        }
        assert!(
            t0.elapsed() < Duration::from_secs(15),
            "the list never showed the PC's model: {v}"
        );
        std::thread::sleep(Duration::from_millis(300));
    }
}
