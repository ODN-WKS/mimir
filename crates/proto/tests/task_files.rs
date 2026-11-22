//! "task_files": a task's message may carry pictures and files; an older
//! phone's message (neither field) still reads.

use mimir_proto::*;

#[test]
fn a_send_without_pictures_or_files_still_reads() {
    let old = r#"{"Send":{"task":"t","text":"hi"}}"#;
    let c: Call = serde_json::from_str(old).unwrap();
    match c {
        Call::Send {
            pictures, files, ..
        } => assert!(pictures.is_empty() && files.is_empty()),
        _ => panic!("not a Send"),
    }
    let old = r#"{"NewTask":{"project":"/p","text":"hi","mode":"Ask","model":null,"effort":null,"separate_copy":false}}"#;
    let c: Call = serde_json::from_str(old).unwrap();
    assert!(matches!(c, Call::NewTask { files, .. } if files.is_empty()));
}

#[test]
fn pictures_and_files_survive_the_trip_and_are_left_out_when_empty() {
    let c = Call::Send {
        task: "t".into(),
        text: "look".into(),
        pictures: vec![Picture {
            media_type: "image/png".into(),
            base64: "aGk=".into(),
        }],
        files: vec![SentFile {
            name: "Lease.pdf".into(),
            media_type: "application/pdf".into(),
            base64: "aGk=".into(),
        }],
        msg_id: None,
    };
    let s = serde_json::to_string(&c).unwrap();
    assert!(s.contains("\"files\""));
    assert_eq!(serde_json::from_str::<Call>(&s).unwrap(), c);
    let none = Call::Send {
        task: "t".into(),
        text: "look".into(),
        pictures: vec![],
        files: vec![],
        msg_id: None,
    };
    assert!(!serde_json::to_string(&none).unwrap().contains("files"));
    assert!(FEATURES.contains(&"task_files"));
}
