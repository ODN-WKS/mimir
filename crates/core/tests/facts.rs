//! Status from facts: given a made-up run log, tasks and git state, the
//! status page and the chat's reply match fixed expected text.

use mimir_core::facts::{self, Work};
use std::path::{Path, PathBuf};
use std::process::Command;

fn dir(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("facts-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn git(dir: &Path, args: &[&str]) {
    let ok = Command::new("git")
        .arg("-C")
        .arg(dir)
        .args([
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@t",
            "-c",
            "commit.gpgsign=false",
        ])
        .args(args)
        .output()
        .unwrap()
        .status
        .success();
    assert!(ok, "git {args:?}");
}

fn line(time: &str, commit: &str, results: serde_json::Value) -> String {
    serde_json::json!({"time": time, "kind": "window", "commit": commit, "results": results})
        .to_string()
        + "\n"
}

#[test]
fn the_status_page_and_reply_match_fixed_text() {
    let root = dir("page");
    let logs = root.join("keep-it-working");
    std::fs::create_dir_all(&logs).unwrap();

    // Tune-Sorter: 4 features, one failing since 10:00; a VERSION file;
    // a git repo with one uncommitted file.
    let hm = root.join("Tune-Sorter");
    std::fs::create_dir_all(hm.join("checks")).unwrap();
    std::fs::write(
        hm.join("checks/core-features.md"),
        "# Core features\n\n## CF-1\n\nMessages arrive in order.\n\nApproved: x\n\n\
         ## CF-2\n\nAudio calls work on Wi-Fi.\n\n## CF-3\n\nCalls on cell signal connect.\n\
         \n## CF-4\n\nPictures send.\n",
    )
    .unwrap();
    std::fs::write(hm.join("VERSION"), "1.53\n").unwrap();
    git(&hm, &["init", "-q"]);
    git(&hm, &["add", "-A"]);
    git(&hm, &["commit", "-qm", "first"]);
    std::fs::write(hm.join("new.txt"), "x").unwrap();
    std::fs::write(
        logs.join("tune-sorter.jsonl"),
        line("2026-11-18T08:00:00Z", "c1", serde_json::json!({
            "cf1_order": "passed", "cf2_wifi": "passed", "cf3_cell": "passed", "cf4_pics": "passed"
        })) + &line("2026-11-18T10:00:00Z", "c2", serde_json::json!({
            "cf1_order": "passed", "cf2_wifi": "passed", "cf3_cell": "failed", "cf4_pics": "passed"
        })) + &line("2026-11-18T11:00:00Z", "c3", serde_json::json!({
            "cf1_order": "passed", "cf2_wifi": "passed", "cf3_cell": "failed", "cf4_pics": "passed",
            "cf3_cell_again": "passed"
        })),
    )
    .unwrap();

    // Mímir: all 2 passing, one task running, 2 things need you.
    let cs = root.join("mimir");
    std::fs::create_dir_all(&cs).unwrap();
    std::fs::write(
        logs.join("mimir.jsonl"),
        line(
            "2026-11-18T12:00:00Z",
            "d1",
            serde_json::json!({
                "cf1_typed": "passed", "sec1_refused": "passed"
            }),
        ),
    )
    .unwrap();

    // Recipes: no checks at all.
    let tb = root.join("Recipes");
    std::fs::create_dir_all(&tb).unwrap();

    let work = |p: &Path, name: &str, running, needs, last| Work {
        path: p.display().to_string(),
        name: name.into(),
        tasks_running: running,
        needs_you: needs,
        last_activity: Some(last),
    };
    let projects = vec![
        facts::project(&work(&tb, "Recipes", 0, 0, 300), &logs, true),
        facts::project(&work(&hm, "Tune-Sorter", 0, 0, 100), &logs, true),
        facts::project(&work(&cs, "Mímir", 1, 2, 200), &logs, true),
    ];
    let page = facts::page(projects, None, None, 1000);

    let names: Vec<&str> = page.projects.iter().map(|f| f.name.as_str()).collect();
    assert_eq!(
        names,
        ["Mímir", "Tune-Sorter", "Recipes"],
        "needs-you first, then failing, then by last activity"
    );
    let lines: Vec<&str> = page.projects.iter().map(|f| f.line.as_str()).collect();
    assert_eq!(
        lines,
        [
            "All 2 passing · needs you: 2 · 1 task running",
            "1 of 4 failing: calls on cell signal connect · version 1.53",
            "No checks set up",
        ]
    );
    let hmf = &page.projects[1];
    let c = hmf.checks.as_ref().unwrap();
    assert_eq!(c.failing_since, facts::parse_time("2026-11-18T10:00:00Z"));
    assert_eq!(c.commit.as_deref(), Some("c3"));
    assert_eq!(hmf.uncommitted, Some(1));
    assert_eq!(hmf.unpushed, None, "no upstream");
    assert_eq!(
        page.reply,
        [
            "2 things need you.",
            "Mímir: 2 items waiting for you.",
            "Tune-Sorter has 1 failing check: calls on cell signal connect.",
            "Mímir: 1 task running.",
            "Everything else is passing or idle.",
        ]
    );
    // About one project only.
    assert_eq!(
        facts::reply(&page.projects, Some(&hm.display().to_string())),
        [
            "Nothing needs you.",
            "Tune-Sorter has 1 failing check: calls on cell signal connect.",
        ]
    );
    assert_eq!(
        facts::reply(&page.projects, Some(&tb.display().to_string())),
        ["Nothing needs you.", "Recipes: no checks set up."]
    );
    let _ = std::fs::remove_dir_all(&root);
}
