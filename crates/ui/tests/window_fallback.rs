//! When Claude's limit runs out mid-task, the task carries on on the free
//! model on this PC from a short brief, says so, and offers Back to Claude
//! when the limit resets.

mod common;
use common::*;
use station_ui::labels;
use std::io::{Read, Write};
use std::net::TcpListener;

const FREE: &str = "qwen3.6:35b-a3b-coding";

/// A made-up Ollama with one free model.
fn fake_ollama() {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = format!("{{\"models\":[{{\"name\":\"{FREE}\"}}]}}");
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
}

#[test]
fn a_task_carries_on_on_the_free_model_when_claudes_limit_runs_out() {
    let Some(home) = TestHome::new() else { return };
    fake_ollama();
    // Claude starts, then its limit runs out (it resets 4 s later); the
    // free model carries on.
    home.play("run-1.jsonl", "limit.jsonl");
    home.play("run-2.jsonl", "near-limit.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, labels::ON_FREE_UNTIL_BACK)),
        "the task didn't move to the free model\n{}",
        screen_text(&h)
    );
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt is sorted")),
        "the free model didn't carry on\n{}",
        screen_text(&h)
    );
    let runs = home.conversations();
    assert_eq!(runs.len(), 2, "{runs:?}");
    assert!(
        runs[1].iter().any(|a| a.contains(FREE)),
        "the second run wasn't on the free model: {:?}",
        runs[1]
    );
    let told = home.messages_to_claude();
    assert!(
        told.last().is_some_and(|m| m.contains("taking over this task")
            && m.contains("Tidy up notes.txt")),
        "the free model didn't get the handover brief: {told:?}"
    );
    // When the limit resets, Back to Claude.
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::BACK_TO_CLAUDE)),
        "Back online never came\n{}",
        screen_text(&h)
    );
    click(&mut h, labels::BACK_TO_CLAUDE);
    assert!(
        wait_for(&mut h, |h| shows(h, "Back online")),
        "going back online wasn't said\n{}",
        screen_text(&h)
    );
    close(h);
}
