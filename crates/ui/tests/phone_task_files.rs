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

fn tiny() -> image::RgbImage {
    image::RgbImage::from_fn(8, 8, |x, y| {
        image::Rgb([(x * 30) as u8, (y * 30) as u8, 90])
    })
}

/// A real JPEG with a pretend location in an APP1 block.
fn photo() -> Vec<u8> {
    let mut out = Vec::new();
    tiny()
        .write_to(
            &mut std::io::Cursor::new(&mut out),
            image::ImageFormat::Jpeg,
        )
        .unwrap();
    let note = b"Exif\0\0GPSLatitude=12.34";
    let mut seg = vec![0xFF, 0xE1];
    seg.extend_from_slice(&((note.len() + 2) as u16).to_be_bytes());
    seg.extend_from_slice(note);
    out.splice(2..2, seg);
    out
}

fn crc32(data: &[u8]) -> u32 {
    let mut c = !0u32;
    for b in data {
        c ^= *b as u32;
        for _ in 0..8 {
            c = if c & 1 == 1 {
                (c >> 1) ^ 0xEDB8_8320
            } else {
                c >> 1
            };
        }
    }
    !c
}

/// A real PNG with a text chunk holding a pretend place.
fn png_with_text() -> Vec<u8> {
    let mut out = Vec::new();
    tiny()
        .write_to(&mut std::io::Cursor::new(&mut out), image::ImageFormat::Png)
        .unwrap();
    let body = b"CommentGPSLatitude=12.34".to_vec();
    let mut chunk = (body.len() as u32).to_be_bytes().to_vec();
    let mut typed = b"tEXt".to_vec();
    typed.extend_from_slice(&body);
    chunk.extend_from_slice(&typed);
    chunk.extend_from_slice(&crc32(&typed).to_be_bytes());
    out.splice(33..33, chunk);
    out
}

fn has(bytes: &[u8], needle: &[u8]) -> bool {
    bytes.windows(needle.len()).any(|w| w == needle)
}

fn new_task(home: &TestHome, text: &str, pictures: Vec<Picture>, files: Vec<SentFile>) -> Call {
    Call::NewTask {
        project: home.project.display().to_string(),
        text: text.into(),
        pictures,
        files,
        mode: Mode::Ask,
        model: None,
        effort: None,
        separate_copy: false,
        msg_id: None,
        ground_rules: None,
    }
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

#[test]
fn leftovers_of_tasks_that_no_longer_exist_are_swept_at_start() {
    let Some(home) = TestHome::new() else { return };
    let ghost = home.root.join(".local/share/mimir/task-files/ghost-1");
    std::fs::create_dir_all(&ghost).unwrap();
    std::fs::write(ghost.join("1-Lease.pdf"), b"x").unwrap();
    let _st = home.service();
    assert!(!ghost.exists(), "a crashed task's files stayed");
    home.stop_service();
}

#[test]
fn every_kind_of_picture_loses_its_details_even_when_sent_as_a_file() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let id = st
        .call_wait(new_task(
            &home,
            "Clean these",
            vec![
                Picture {
                    media_type: "image/png".into(),
                    base64: b64(&png_with_text()),
                },
                Picture {
                    media_type: "image/jpeg".into(),
                    base64: b64(&photo()),
                },
            ],
            vec![SentFile {
                name: "Holiday.jpg".into(),
                media_type: "image/jpeg".into(),
                base64: b64(&photo()),
            }],
        ))
        .expect("the task didn't start");
    let id = id.as_str().unwrap().to_string();
    let dir = home.root.join(".local/share/mimir/task-files");
    let kept: Vec<_> = walk(&dir);
    assert_eq!(kept.len(), 3, "{kept:?}");
    for f in &kept {
        let b = std::fs::read(f).unwrap();
        assert!(
            !has(&b, b"GPSLatitude") && !has(&b, b"Exif") && !has(&b, b"tEXt"),
            "{} kept its details",
            f.display()
        );
    }
    // What Claude is sent has none either.
    assert!(service_until(&mut st, |_| {
        home.input_lines().iter().any(|l| l.contains("Clean these"))
    }));
    assert!(
        !home
            .input_lines()
            .iter()
            .any(|l| l.contains("R1BTTGF0aXR1ZGU"))
    );
    let _ = id;
    home.stop_service();
}

fn walk(d: &std::path::Path) -> Vec<std::path::PathBuf> {
    let mut out = vec![];
    for e in std::fs::read_dir(d).into_iter().flatten().flatten() {
        let p = e.path();
        if p.is_dir() {
            out.extend(walk(&p));
        } else {
            out.push(p);
        }
    }
    out
}

#[test]
fn a_picture_that_cannot_be_cleaned_starts_no_task() {
    let Some(home) = TestHome::new() else { return };
    let mut st = home.service();
    let before = st.tasks.len();
    let r = st.call_wait(new_task(
        &home,
        "Nope",
        vec![Picture {
            media_type: "image/heic".into(),
            base64: b64(b"\0\0\0\x18ftypheic not a real one"),
        }],
        vec![],
    ));
    assert!(r.is_err(), "a picture with its details on was kept: {r:?}");
    service_until(&mut st, |_| false);
    assert_eq!(st.tasks.len(), before, "a task started without its message");
    assert!(walk(&home.root.join(".local/share/mimir/task-files")).is_empty());
    home.stop_service();
}

#[test]
fn a_message_that_fails_leaves_no_half_written_files() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let id = st
        .call_wait(new_task(&home, "Hello", vec![], vec![]))
        .unwrap();
    let id = id.as_str().unwrap().to_string();
    let r = st.call_wait(Call::Send {
        task: id.clone(),
        text: "Two files".into(),
        pictures: vec![],
        files: vec![
            SentFile {
                name: "Good.txt".into(),
                media_type: String::new(),
                base64: b64(b"fine"),
            },
            SentFile {
                name: "Empty.txt".into(),
                media_type: String::new(),
                base64: String::new(),
            },
        ],
        msg_id: None,
    });
    assert!(r.is_err(), "{r:?}");
    assert!(walk(&home.root.join(".local/share/mimir/task-files")).is_empty());
    home.stop_service();
}

#[test]
fn a_failed_start_does_not_hand_its_files_to_the_next_task() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    // A separate copy of a folder that is not a git folder fails.
    let mut failing = new_task(
        &home,
        "First",
        vec![],
        vec![SentFile {
            name: "Secret.txt".into(),
            media_type: String::new(),
            base64: b64(b"secret"),
        }],
    );
    if let Call::NewTask { separate_copy, .. } = &mut failing {
        *separate_copy = true;
    }
    assert!(st.call_wait(failing).is_err());
    st.call_wait(new_task(&home, "Second", vec![], vec![]))
        .expect("the task didn't start");
    assert!(service_until(&mut st, |_| home
        .messages_to_claude()
        .iter()
        .any(|m| m.contains("Second"))));
    assert!(
        !home
            .messages_to_claude()
            .iter()
            .any(|m| m.contains("Secret.txt")),
        "a failed request's file went to another task"
    );
    home.stop_service();
}

#[test]
fn the_kept_folders_are_private_and_odd_names_are_made_plain() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let long = format!("{}.txt", "é".repeat(200));
    st.call_wait(new_task(
        &home,
        "Names",
        vec![],
        vec![
            SentFile {
                name: "a\u{202E}txt.exe, b.txt".into(),
                media_type: String::new(),
                base64: b64(b"x"),
            },
            SentFile {
                name: long,
                media_type: String::new(),
                base64: b64(b"x"),
            },
        ],
    ))
    .expect("the task didn't start");
    let root = home.root.join(".local/share/mimir/task-files");
    let kept = walk(&root);
    assert_eq!(kept.len(), 2);
    for f in &kept {
        let name = f.file_name().unwrap().to_string_lossy().to_string();
        assert!(name.len() <= 130, "{name}");
        assert!(!name.contains('\u{202E}') && !name.contains(','), "{name}");
    }
    use std::os::unix::fs::PermissionsExt;
    for d in [root.clone(), kept[0].parent().unwrap().to_path_buf()] {
        let mode = std::fs::metadata(&d).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o700, "{} is {mode:o}", d.display());
    }
    home.stop_service();
}
