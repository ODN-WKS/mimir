//! Phase 8 checks: free models (Ollama on this PC). A made-up Ollama answers
//! the model list; the stand-in Claude shows how each task was started.

mod common;
use common::*;
use mimir_core::proto::Call;
use mimir_core::task::Mode;
use std::io::{Read, Write};
use std::net::TcpListener;

const FREE: &str = "qwen3.6:35b-a3b-coding";

/// A made-up Ollama that lists one model; its address goes to the service.
fn fake_ollama() {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = format!(
                "{{\"models\":[{{\"name\":\"{FREE}\"}},{{\"name\":\"huihui_ai/gpt-oss-abliterated:20b\"}}]}}"
            );
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
}

fn settings(home: &TestHome, switch_to_free: bool) {
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "switch_to_free": switch_to_free,
    }));
}

fn model_of(st: &mimir_core::client::Station, id: &str) -> Option<String> {
    st.tasks
        .iter()
        .find(|v| v.task.id == id)
        .and_then(|v| v.task.model.clone())
}

fn notes(st: &mimir_core::client::Station, id: &str) -> Vec<String> {
    st.tasks
        .iter()
        .find(|v| v.task.id == id)
        .map(|v| {
            v.task
                .entries
                .iter()
                .filter_map(|e| match e {
                    mimir_core::task::Entry::Note(n) => Some(n.clone()),
                    _ => None,
                })
                .collect()
        })
        .unwrap_or_default()
}

fn done(st: &mimir_core::client::Station, id: &str) -> bool {
    st.tasks.iter().any(|v| v.task.id == id && !v.busy())
}

fn new_task(
    st: &mut mimir_core::client::Station,
    home: &TestHome,
    text: &str,
    model: Option<&str>,
) -> String {
    st.new_task(
        &home.project.display().to_string(),
        text,
        &[],
        Mode::Ask,
        model.map(str::to_string),
        None,
        false,
    )
    .expect("the task didn't start")
}

#[test]
fn p8_free_task_keeps_its_model_and_switch_off_means_claude() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama();
    settings(&home, false);
    home.play("run-1.jsonl", "near-limit.jsonl");
    home.play("run-2.jsonl", "live.jsonl");
    home.play("resume.jsonl", "live.jsonl");
    let mut st = home.service();
    let first = new_task(&mut st, &home, "Tidy up notes.txt.", Some("auto"));
    assert!(service_until(&mut st, |s| done(s, &first)));
    // Switched off: near the limit, still Claude.
    let second = new_task(&mut st, &home, "What does this project do?", Some("auto"));
    assert!(service_until(&mut st, |s| done(s, &second)));
    assert_eq!(model_of(&st, &second).as_deref(), Some("auto"));

    // A task the owner put on a free model keeps it across turns.
    let free = format!("ollama:{FREE}");
    st.set_model(&second, Some(free.clone()));
    st.send_with(&second, "And the other button too.", &[]);
    assert!(service_until(&mut st, |s| {
        s.tasks
            .iter()
            .any(|v| v.task.id == second && !v.busy() && v.task.entries.len() > 4)
    }));
    assert_eq!(model_of(&st, &second).as_deref(), Some(free.as_str()));
    let runs = home.claude_runs();
    assert!(
        runs.last()
            .unwrap()
            .windows(2)
            .any(|w| w[0] == "--model" && w[1] == FREE),
        "the next turn wasn't on the free model: {:?}",
        runs.last()
    );
}

#[test]
fn p8_out_of_room_offers_continue_on_claude() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama();
    settings(&home, false);
    home.play("run-1.jsonl", "context-full.jsonl");
    home.play("run-2.jsonl", "live.jsonl");
    let mut st = home.service();
    let free = format!("ollama:{FREE}");
    let id = new_task(&mut st, &home, "Tidy up notes.txt.", Some(&free));
    assert!(service_until(&mut st, |s| done(s, &id)));
    assert!(
        notes(&st, &id)
            .iter()
            .any(|n| n.starts_with(mimir_core::models::OUT_OF_ROOM)),
        "running out of room wasn't said: {:?}",
        notes(&st, &id)
    );
    let new = st
        .call_wait(Call::ContinueOnClaude { task: id.clone() })
        .expect("Continue online failed");
    let new = new.as_str().unwrap().to_string();
    assert!(service_until(&mut st, |s| s
        .tasks
        .iter()
        .any(|v| v.task.id == new)));
    assert_eq!(model_of(&st, &new).as_deref(), Some("auto"));
}

#[test]
fn p8_models_list_free_ones_with_the_refusals_note() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama();
    settings(&home, false);
    let mut st = home.service();
    let v = st.call_wait(Call::Models).expect("no model list");
    let free = v["free"].as_array().unwrap();
    assert_eq!(
        free[0]["name"], FREE,
        "the tested model should come first: {v}"
    );
    let ab = free
        .iter()
        .find(|m| m["name"].as_str().unwrap().contains("abliterated"))
        .unwrap();
    assert_eq!(ab["refusals_removed"], true);
    assert_eq!(v["free_model"], format!("ollama:{FREE}"));
    assert!(
        v["refusals_removed_note"]
            .as_str()
            .unwrap()
            .starts_with("Uncensored")
    );
    st.call_wait(Call::SetFreeChoice {
        switch_to_free: Some(true),
        free_model: None,
    })
    .unwrap();
    // P23: the field now sets "When online use runs out, use your PC's
    // model", and true means on.
    let v = st.call_wait(Call::Models).unwrap();
    assert_eq!(v["switch_to_free"], true);
    let r = st.call_wait(Call::SetFreeChoice {
        switch_to_free: None,
        free_model: Some("claude-opus-5-5".into()),
    });
    assert!(r.is_err_and(|e| e.starts_with("refused")));
}
