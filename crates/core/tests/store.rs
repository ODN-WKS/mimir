use mimir_core::store::Store;
use mimir_core::task::{Status, Task};

fn temp(tag: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("station-store-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    d
}

#[test]
fn tasks_survive_a_restart() {
    let dir = temp("restart");
    let store = Store::new(&dir).unwrap();
    let mut t = Task::new("a-1".into(), "Fix build".into(), "/p".into(), 5);
    t.session_id = Some("s1".into());
    t.add_you("hello");
    t.status = Status::Paused;
    store.save(&t).unwrap();
    let mut t2 = Task::new("a-0".into(), "Older".into(), "/p".into(), 1);
    t2.status = Status::Done;
    store.save(&t2).unwrap();

    let again = Store::new(&dir).unwrap();
    let (tasks, bad) = again.load_all();
    assert!(bad.is_empty());
    assert_eq!(tasks.len(), 2);
    assert_eq!(tasks[0].id, "a-0", "oldest first");
    assert_eq!(tasks[1], t);
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn damaged_files_are_reported_not_deleted() {
    let dir = temp("bad");
    let store = Store::new(&dir).unwrap();
    std::fs::write(dir.join("broken.json"), "{ not json").unwrap();
    let (tasks, bad) = store.load_all();
    assert!(tasks.is_empty());
    assert_eq!(bad.len(), 1);
    assert!(dir.join("broken.json").exists());
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn ids_cannot_escape_the_folder() {
    let dir = temp("escape");
    let store = Store::new(&dir).unwrap();
    let t = Task::new("../../evil".into(), "x".into(), "/p".into(), 0);
    store.save(&t).unwrap();
    assert!(dir.join("evil.json").exists());
    store.remove("../../evil").unwrap();
    assert!(!dir.join("evil.json").exists());
    std::fs::remove_dir_all(dir).unwrap();
}
