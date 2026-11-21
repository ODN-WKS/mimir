//! The program's check commands: `--public-key` and `--check-home`, run
//! as the real program against made-up homes in a temp folder.

use mimir_core::store::Store;
use mimir_core::task::{Entry, Task};
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

const BUILT_IN_KEY: &str = include_str!("../src/release-key.pub");

fn station() -> Command {
    Command::new(env!("CARGO_BIN_EXE_mimir"))
}

/// A made-up home in a temp folder.
struct Home {
    root: PathBuf,
}

impl Home {
    fn new(tag: &str) -> Home {
        let root = std::env::temp_dir().join(format!("station-seam-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        Home { root }
    }

    fn settings(&self) -> PathBuf {
        self.root.join(".config/mimir/settings.json")
    }

    fn tasks(&self) -> PathBuf {
        self.root.join(".local/share/mimir/tasks")
    }

    /// Two projects, three tasks, five messages (notes aren't messages).
    fn fill(&self) {
        let (p1, p2) = (self.root.join("p1"), self.root.join("p2"));
        std::fs::create_dir_all(self.settings().parent().unwrap()).unwrap();
        std::fs::write(
            self.settings(),
            serde_json::json!({"projects": [p1, p2], "last_project": p1}).to_string(),
        )
        .unwrap();
        let store = Store::new(self.tasks()).unwrap();
        let mut t1 = Task::new("t1".into(), "one".into(), p1.display().to_string(), 1);
        t1.entries = vec![
            Entry::You("hi".into()),
            Entry::Claude("hello".into()),
            Entry::Note("You stopped this task.".into()),
        ];
        let mut t2 = Task::new("t2".into(), "two".into(), p2.display().to_string(), 2);
        t2.entries = vec![Entry::You("a".into()), Entry::Claude("b".into())];
        let mut t3 = Task::new("t3".into(), "three".into(), p1.display().to_string(), 3);
        t3.entries = vec![Entry::You("c".into())];
        for t in [t1, t2, t3] {
            store.save(&t).unwrap();
        }
    }

    fn expect(&self, projects: u64, tasks: u64, messages: u64) {
        std::fs::write(
            self.root.join("expect.json"),
            serde_json::json!({"projects": projects, "tasks": tasks, "messages": messages})
                .to_string(),
        )
        .unwrap();
    }

    /// `--check-home` with only this home's folders in its environment.
    /// The Claude it would find leaves a mark if it is ever run.
    fn check(&self) -> Output {
        let fake = self.root.join("claude");
        std::fs::write(
            &fake,
            format!(
                "#!/bin/sh\ntouch {}\n",
                self.root.join("claude-ran").display()
            ),
        )
        .unwrap();
        std::fs::set_permissions(&fake, std::os::unix::fs::PermissionsExt::from_mode(0o755))
            .unwrap();
        station()
            .arg("--check-home")
            .env_clear()
            .env("PATH", std::env::var_os("PATH").unwrap_or_default())
            .env("HOME", &self.root)
            .env("XDG_CONFIG_HOME", self.root.join(".config"))
            .env("XDG_DATA_HOME", self.root.join(".local/share"))
            .env("XDG_RUNTIME_DIR", self.root.join("run"))
            .env("CLAUDE_CONFIG_DIR", self.root.join(".claude"))
            .env("MIMIR_CLAUDE", &fake)
            .output()
            .unwrap()
    }
}

impl Drop for Home {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

fn text(o: &Output) -> (String, String) {
    (
        String::from_utf8_lossy(&o.stdout).to_string(),
        String::from_utf8_lossy(&o.stderr).to_string(),
    )
}

#[test]
fn public_key_is_the_built_in_one() {
    let o = station()
        .arg("--public-key")
        .env_remove("MIMIR_TEST_KEY")
        .output()
        .unwrap();
    assert!(o.status.success());
    assert_eq!(text(&o).0.trim(), BUILT_IN_KEY.trim());
}

#[test]
fn public_key_takes_the_test_key_in_a_debug_build() {
    let key = "ab".repeat(32);
    let o = station()
        .arg("--public-key")
        .env("MIMIR_TEST_KEY", &key)
        .output()
        .unwrap();
    assert!(o.status.success());
    // The checks run a debug build; a release build ignores the variable
    // (see app_view's test of key_in_use).
    assert_eq!(text(&o).0.trim(), key);
}

#[test]
fn check_home_counts_and_matches() {
    let h = Home::new("ok");
    h.fill();
    h.expect(2, 3, 5);
    let o = h.check();
    let (out, err) = text(&o);
    assert_eq!(o.status.code(), Some(0), "{out}{err}");
    assert_eq!(out.trim(), "2 projects, 3 tasks, 5 messages");
    assert!(!h.root.join("claude-ran").exists(), "it started Claude");
    assert!(
        !h.root.join("run").exists(),
        "it started the approvals socket"
    );
}

#[test]
fn check_home_fails_on_wrong_counts() {
    let h = Home::new("wrong");
    h.fill();
    h.expect(2, 3, 6);
    let o = h.check();
    let (out, err) = text(&o);
    assert_eq!(o.status.code(), Some(1), "{out}{err}");
    assert!(err.contains("messages: expected 6, found 5"), "{err}");
}

#[test]
fn check_home_fails_without_expect_json() {
    let h = Home::new("noexpect");
    h.fill();
    let o = h.check();
    assert_eq!(o.status.code(), Some(1));
    assert!(text(&o).1.contains("expect.json"));
}

#[test]
fn check_home_fails_on_a_damaged_task_and_keeps_it() {
    let h = Home::new("badtask");
    h.fill();
    h.expect(2, 3, 5);
    let bad = h.tasks().join("t9.json");
    std::fs::write(&bad, "{ not json").unwrap();
    let o = h.check();
    let (out, err) = text(&o);
    assert_eq!(o.status.code(), Some(1), "{out}{err}");
    assert!(err.contains("t9.json"), "{err}");
    assert!(bad.exists());
}

#[test]
fn check_home_fails_on_damaged_settings_and_leaves_them() {
    let h = Home::new("badsettings");
    h.fill();
    h.expect(0, 3, 5);
    std::fs::write(h.settings(), "{ not json").unwrap();
    let o = h.check();
    let (out, err) = text(&o);
    assert_eq!(o.status.code(), Some(1), "{out}{err}");
    assert!(err.contains("settings"), "{err}");
    // The window would set a damaged file aside; the check never does.
    assert_eq!(std::fs::read_to_string(h.settings()).unwrap(), "{ not json");
    let dir = h.settings().parent().unwrap().to_path_buf();
    assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 1);
}

#[test]
fn check_home_of_an_empty_home_creates_nothing() {
    let h = Home::new("empty");
    h.expect(0, 0, 0);
    let o = h.check();
    let (out, err) = text(&o);
    assert_eq!(o.status.code(), Some(0), "{out}{err}");
    assert_eq!(out.trim(), "0 projects, 0 tasks, 0 messages");
    let made: Vec<_> = std::fs::read_dir(&h.root)
        .unwrap()
        .flatten()
        .map(|e| e.file_name())
        .collect();
    assert!(
        made.iter().all(|n| n == "expect.json" || n == "claude"),
        "{made:?}"
    );
    assert!(!Path::new(&h.root.join(".local")).exists());
}
