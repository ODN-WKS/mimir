//! L2: a calm line at the top of a task whose conversation has run long,
//! with one button that carries on in a new task from a short summary.

mod common;
use common::*;
use mimir_core::store::Store;
use mimir_core::task::{Entry, Status, Task};
use station_ui::labels;

/// A task with `used` of 200,000 in its conversation, saved before the
/// window opens.
fn long_task(home: &TestHome, used: u64) -> (Store, String) {
    let project = home.project.clone();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
    }));
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(
        "t1".into(),
        "Sort the receipts".into(),
        project.display().to_string(),
        0,
    );
    t.entries = vec![
        Entry::You("Sort the receipts".into()),
        Entry::Claude("Done.".into()),
    ];
    t.status = Status::Done;
    t.context_window = 200_000;
    t.context_used = used;
    store.save(&t).unwrap();
    let name = project.file_name().unwrap().to_string_lossy().to_string();
    (store, labels::task_row("Sort the receipts", &name))
}

const LINE: &str = "This conversation is long.";
const BUTTON: &str = "Start fresh with a summary";

#[test]
fn l2_a_long_task_shows_the_line_and_the_button_starts_a_new_task() {
    let Some(home) = TestHome::new() else { return };
    home.play("resume.jsonl", "resume.jsonl");
    let (store, row) = long_task(&home, 160_000);
    home.tripwire();
    let mut h = home.open();
    wait_for(&mut h, |h| shows(h, &row));
    click(&mut h, &row);
    assert!(
        wait_for(&mut h, |h| shows(h, LINE) && has_button(h, BUTTON)),
        "a task at 160,000 shows no line and button\n{}",
        screen_text(&h)
    );
    let all = screen_text(&h);
    assert!(
        !all.contains("160,000") && !all.contains("160000"),
        "a token count is on screen\n{all}"
    );
    click(&mut h, BUTTON);
    let started = wait_for(&mut h, |_| store.load_all().0.len() == 2);
    assert!(
        started,
        "the button didn't start a new task\n{}",
        screen_text(&h)
    );
    // The old one is kept.
    assert!(store.load_all().0.iter().any(|t| t.id == "t1"));
    close(h);
}

#[test]
fn l2_a_short_task_shows_no_line() {
    let Some(home) = TestHome::new() else { return };
    let (_store, row) = long_task(&home, 40_000);
    home.tripwire();
    let mut h = home.open();
    wait_for(&mut h, |h| shows(h, &row));
    click(&mut h, &row);
    settle(&mut h);
    assert!(!shows(&h, LINE), "{}", screen_text(&h));
    close(h);
}

#[test]
fn l2_dismissed_stays_dismissed() {
    let Some(home) = TestHome::new() else { return };
    let (store, row) = long_task(&home, 160_000);
    // 160,000 of 200,000 is already 80%: use a bigger window for "long".
    let mut t = store.load_all().0.remove(0);
    t.context_window = 1_000_000;
    store.save(&t).unwrap();
    home.tripwire();
    let mut h = home.open();
    wait_for(&mut h, |h| shows(h, &row));
    click(&mut h, &row);
    assert!(wait_for(&mut h, |h| shows(h, LINE)));
    click(&mut h, "Not now");
    settle(&mut h);
    assert!(!shows(&h, LINE), "still shown after Not now");
    close(h);
}
