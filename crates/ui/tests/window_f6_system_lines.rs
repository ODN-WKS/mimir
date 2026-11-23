//! 0.07 hunt fix (F6): what Claude Code writes into a followed session on
//! its own (a helper finished) is one small line in the window, never the
//! raw tags, paths or ids.

mod common;
use common::*;

const SESSION: &str = "5c1d8e72-4b6f-4a39-8d20-7e9f0a1b2c3d";

#[test]
fn a_helper_finishing_in_a_followed_session_is_one_small_line() {
    let Some(home) = TestHome::new() else { return };
    let project = home.project.display().to_string();
    let folder = home
        .root
        .join(".claude/projects")
        .join(mimir_core::sessions::folder_name(&home.project));
    std::fs::create_dir_all(&folder).unwrap();
    let at = iso(30);
    let line = |origin: &str, content: &str| {
        serde_json::json!({
            "type": "user",
            "origin": {"kind": origin},
            "message": {"role": "user", "content": content},
            "cwd": project, "sessionId": SESSION, "timestamp": at,
        })
        .to_string()
    };
    let done = "<task-notification>\n<task-id>abc123</task-id>\n<tool-use-id>toolu_01XYZ</tool-use-id>\n<output-file>/tmp/claude-1000/out.output</output-file>\n<status>completed</status>\n<summary>Agent \"Seed packet sorting\" finished</summary>\n</task-notification>";
    let file = folder.join(format!("{SESSION}.jsonl"));
    std::fs::write(
        &file,
        [
            line("human", "Sort the seed packets"),
            line("task-notification", done),
        ]
        .join("\n")
            + "\n",
    )
    .unwrap();
    let store = mimir_core::store::Store::new(mimir_core::store::Store::default_dir()).unwrap();
    let mut t = mimir_core::task::Task::new(
        "1700000000-2-1".into(),
        "Sort the seed packets".into(),
        project,
        1_700_000_000,
    );
    t.session_id = Some(SESSION.into());
    t.status = mimir_core::task::Status::Done;
    store.save(&t).unwrap();
    let mut h = home.open();
    assert!(wait_for(&mut h, |h| shows(h, "Sort the seed packets")));
    click(&mut h, "Task Sort the seed packets, in project");
    assert!(
        wait_for(&mut h, |h| shows(
            h,
            "A helper finished: Seed packet sorting"
        )),
        "{}",
        screen_text(&h)
    );
    let text = screen_text(&h);
    for raw in ["task-notification", "/tmp/claude", "toolu_", "abc123"] {
        assert!(!text.contains(raw), "{raw} shows\n{text}");
    }
    close(h);
}
