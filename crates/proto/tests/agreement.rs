//! The PC's half of "phone and PC agree" (the phone's half is
//! `AgreementTest.kt`): the same fixtures and `fixtures/agreement.json`
//! feed both, so the status words, the weekly percent and the project names
//! can't drift apart unnoticed.

use mimir_proto::Status;

fn agreement() -> serde_json::Value {
    let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/agreement.json");
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}

/// The status reply the PC sends (events.jsonl, the one with `weekly`).
fn status_page() -> serde_json::Value {
    let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/events.jsonl");
    std::fs::read_to_string(p)
        .unwrap()
        .lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .find_map(|v| {
            v["event"]["Reply"]["result"]["Ok"]
                .as_object()
                .filter(|o| o.contains_key("weekly"))
                .map(|_| v["event"]["Reply"]["result"]["Ok"].clone())
        })
        .expect("no status reply in the fixtures")
}

#[test]
fn the_pc_says_the_words_the_agreement_lists() {
    for s in agreement()["statuses"].as_array().unwrap() {
        let status: Status = serde_json::from_value(s["status"].clone()).unwrap();
        assert_eq!(status.words(), s["pc"], "the PC's word for {}", s["status"]);
    }
    // Every status is listed.
    let n = agreement()["statuses"].as_array().unwrap().len();
    assert_eq!(n, 5, "a status was added: list it in agreement.json");
}

#[test]
fn the_pc_shows_the_weekly_percent_and_project_names_of_the_fixture() {
    let page = status_page();
    // The window: (used * 100).round(), with used a 0..1 share.
    let used = page["weekly"]["used"].as_f64().unwrap();
    assert!(
        (0.0..=1.0).contains(&used),
        "weekly used is a 0..1 share, got {used}"
    );
    assert_eq!(
        (used * 100.0).round(),
        agreement()["weekly_percent"].as_f64().unwrap()
    );
    let strings = |v: &serde_json::Value| -> Vec<String> {
        v.as_array()
            .unwrap()
            .iter()
            .map(|n| n.as_str().unwrap().to_string())
            .collect()
    };
    let names: Vec<String> = page["projects"]
        .as_array()
        .unwrap()
        .iter()
        .map(|p| p["name"].as_str().unwrap().to_string())
        .collect();
    let want = strings(&agreement()["project_names"]);
    assert_eq!(names, want);
}

/// What the PC sends for the end of a task's work, the same words the phone
/// reads (`AgreementTest.kt`), and the feature a phone lists to get them.
#[test]
fn the_pc_names_the_ends_of_work_the_way_the_agreement_lists() {
    use mimir_proto::{FinishedHow, FinishedItem};
    let how = |h| serde_json::to_value(h).unwrap();
    assert_eq!(
        agreement()["finished_how"],
        serde_json::json!([
            how(FinishedHow::Ok),
            how(FinishedHow::Stopped),
            how(FinishedHow::Failed)
        ])
    );
    assert!(mimir_proto::FEATURES.contains(&agreement()["finished_feature"].as_str().unwrap()));
    // The recent list of the fixtures is what the snapshot carries, with ids.
    let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/events.jsonl");
    let text = std::fs::read_to_string(p).unwrap();
    let recent: Vec<FinishedItem> = text
        .lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .find_map(|v| v["event"]["RecentFinished"]["items"].as_array().cloned())
        .map(|a| {
            a.into_iter()
                .map(|i| serde_json::from_value(i).unwrap())
                .collect()
        })
        .expect("no recent-finished list in the fixtures");
    assert_eq!(recent.len(), 2);
    assert!(recent.iter().all(|i| i.id.starts_with("fin:") && i.at > 0));
}
