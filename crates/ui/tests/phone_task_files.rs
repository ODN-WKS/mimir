//! "task_files": a project task's message carries a picture and a file. The
//! task's Claude Code is told where they are kept, they are there to read,
//! the picture's location is gone, and deleting the task deletes them.

mod common;
use common::*;
use mimir_core::events::Picture;
use mimir_core::proto::{Call, SentFile};
use mimir_core::task::Mode;

fn b64(bytes: &[u8]) -> String {
    mimir_core::events::base64(bytes)
}

/// A JPEG with a pretend location in it.
fn photo() -> Vec<u8> {
    let mut j = vec![0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x0A];
    j.extend_from_slice(b"Exif\0GPS");
    j.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x02, 9, 9, 0xFF, 0xD9]);
    j
}

fn attached_paths(line: &str) -> Vec<std::path::PathBuf> {
    line.rsplit_once("Attached: ")
        .map(|(_, rest)| {
            rest.split(", ")
                .map(|p| std::path::PathBuf::from(p.trim()))
                .collect()
        })
        .unwrap_or_default()
}

#[test]
fn a_task_message_with_a_picture_and_a_file_reaches_claude_and_goes_with_the_task() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let id = st
        .call_wait(Call::NewTask {
            project: home.project.display().to_string(),
            text: "See these".into(),
            pictures: vec![Picture {
                media_type: "image/jpeg".into(),
                base64: b64(&photo()),
            }],
            files: vec![SentFile {
                name: "Lease.pdf".into(),
                media_type: "application/pdf".into(),
                base64: b64(b"%PDF-1.4 pretend"),
            }],
            mode: Mode::Ask,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: None,
            ground_rules: None,
        })
        .expect("the task didn't start");
    let id = id.as_str().unwrap().to_string();
    let mut line = String::new();
    assert!(
        service_until(&mut st, |_| {
            line = home
                .messages_to_claude()
                .into_iter()
                .find(|m| m.contains("See these"))
                .unwrap_or_default();
            !line.is_empty()
        }),
        "Claude never got the message"
    );
    let paths = attached_paths(&line);
    assert_eq!(paths.len(), 2, "both are named: {line}");
    assert!(paths.iter().all(|p| p.exists()), "{paths:?}");
    assert!(paths.iter().all(|p| !p.starts_with(&home.project)));
    assert!(paths[1].to_string_lossy().ends_with("Lease.pdf"));
    assert!(
        !std::fs::read(&paths[0])
            .unwrap()
            .windows(4)
            .any(|w| w == b"Exif"),
        "the picture kept its location"
    );
    // The picture also reaches Claude itself.
    assert!(
        home.input_lines().iter().any(|l| l.contains("\"image\"")),
        "no picture in what Claude was sent"
    );
    // A later message with a file goes the same way.
    assert!(service_until(&mut st, |s| !s.tasks.iter().any(|v| v
        .task
        .id
        == id
        && v.busy())));
    st.call_wait(Call::Send {
        task: id.clone(),
        text: "And this one".into(),
        pictures: vec![],
        files: vec![SentFile {
            name: "Budget.csv".into(),
            media_type: String::new(),
            base64: b64(b"a,b\n1,2\n"),
        }],
        msg_id: None,
    })
    .expect("the message was refused");
    // (The stand-in Claude takes one turn; the task's own record shows it.)
    let mut second = String::new();
    assert!(
        service_until(&mut st, |s| {
            second = s
                .tasks
                .iter()
                .filter(|v| v.task.id == id)
                .flat_map(|v| v.task.entries.iter())
                .find_map(|e| match e {
                    mimir_core::task::Entry::You(t) if t.contains("And this one") => {
                        Some(t.clone())
                    }
                    _ => None,
                })
                .unwrap_or_default();
            !second.is_empty()
        }),
        "the later message never reached the task"
    );
    let later = attached_paths(&second);
    assert!(
        later.len() == 1 && later[0].exists() && later[0].starts_with(paths[0].parent().unwrap())
    );
    // Too many files: said in plain words, nothing sent.
    let f = || SentFile {
        name: "a.txt".into(),
        media_type: String::new(),
        base64: b64(b"hi"),
    };
    let r = st.call_wait(Call::Send {
        task: id.clone(),
        text: "Too many".into(),
        pictures: vec![],
        files: vec![f(), f(), f(), f()],
        msg_id: None,
    });
    assert!(r.is_err_and(|e| e.contains("Three files")));
    // Deleting the task deletes what was kept for it.
    let folder = paths[0].parent().unwrap().to_path_buf();
    st.call_wait(Call::Stop { task: id.clone() }).ok();
    service_until(&mut st, |s| {
        !s.tasks.iter().any(|v| v.task.id == id && v.busy())
    });
    st.remove(&id).expect("the task was not deleted");
    assert!(
        service_until(&mut st, |_| !folder.exists()),
        "the files stayed after the task was deleted"
    );
    home.stop_service();
}
