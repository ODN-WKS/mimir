//! The five store scenarios B3 asks for.

use crate::keyring::{Keyring, encode, fresh_key};
use crate::talk::{TalkStore, Unlock};
use crate::writer::{Sink, Writer};
use mimir_proto::ChatTurn;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

fn tmp(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("mimir-store-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn turn(id: &str, text: &str, at: i64) -> ChatTurn {
    ChatTurn {
        id: id.into(),
        user: true,
        text: text.into(),
        sources: vec![],
        ran: None,
        model_words: None,
        at,
        version: 1,
        seq: 0,
        gone: false,
    }
}

/// A stand-in `secret-tool`: `lookup` runs `lookup`, `store` keeps its
/// input in `<dir>/stored`.
fn fake_tool(d: &Path, lookup: &str) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    let f = d.join("secret-tool");
    std::fs::write(
        &f,
        format!(
            "#!/bin/sh\ncase \"$1\" in\nlookup) {lookup};;\nstore) cat > \"{}/stored\"; exit 0;;\nesac\n",
            d.display()
        ),
    )
    .unwrap();
    std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o755)).unwrap();
    f
}

fn wait_until(mut f: impl FnMut() -> bool) -> bool {
    let t0 = Instant::now();
    while t0.elapsed() < Duration::from_secs(10) {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    false
}

/// The child half of `a_writer_killed_mid_write_leaves_the_file_whole`:
/// writes a big chat over and over until it is killed.
#[test]
#[ignore = "run by the crash scenario as a child process"]
fn child_writer() {
    let Some(dir) = std::env::var_os("MIMIR_STORE_CHILD_DIR") else {
        return;
    };
    let dir = PathBuf::from(dir);
    let w = Writer::spawn(Duration::from_millis(1));
    let mut s = TalkStore::open_with(&dir, &Keyring::Off, w);
    for i in 1..=i64::MAX {
        // One turn of 400 kB, replaced by a newer version each time, so a
        // kill lands mid-write.
        let mut t = turn("t", &format!("{i:08}").repeat(50_000), i);
        t.version = i as u32 + 1;
        s.put("c", None, vec![t], false, i).unwrap();
        s.flush().unwrap();
    }
}

#[test]
fn a_writer_killed_mid_write_leaves_the_file_whole() {
    let d = tmp("crash");
    {
        let mut s =
            TalkStore::open_with(&d, &Keyring::Off, Writer::spawn(Duration::from_millis(1)));
        s.put("c", None, vec![turn("t0", "before", 0)], false, 0)
            .unwrap();
        s.flush().unwrap();
    }
    for round in 0..5 {
        let mut child = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "scenarios::child_writer",
                "--ignored",
                "--nocapture",
            ])
            .env("MIMIR_STORE_CHILD_DIR", &d)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap();
        std::thread::sleep(Duration::from_millis(150 + 70 * round));
        child.kill().unwrap(); // SIGKILL: no clean-up runs.
        child.wait().unwrap();
        // Re-opened: the chat is there and every turn is whole.
        let s = TalkStore::open_with(&d, &Keyring::Off, Writer::spawn(Duration::from_millis(1)));
        let c = s.get("c").expect("the chat file survived the kill");
        assert!(c.turns.iter().any(|t| t.text == "before"));
        assert!(
            c.turns.iter().any(|t| t.id == "t"),
            "the child process never wrote"
        );
        for t in &c.turns[1..] {
            assert_eq!(t.text.len(), 400_000, "a turn was cut short");
        }
        // The killed writer's temp file is swept away at open.
        let left: Vec<_> = std::fs::read_dir(&d)
            .unwrap()
            .flatten()
            .filter(|e| {
                e.file_name()
                    .to_string_lossy()
                    .ends_with(crate::atomic::TMP_END)
            })
            .collect();
        assert!(left.is_empty(), "temp files left: {left:?}");
    }
    let _ = std::fs::remove_dir_all(&d);
}

#[test]
fn a_locked_keyring_then_unlocked_makes_the_store_readable() {
    let _g = crate::TEST_TOOL.lock().unwrap_or_else(|e| e.into_inner());
    let d = tmp("unlock");
    let key = fresh_key().unwrap();
    std::fs::write(d.join("key"), encode(&key)).unwrap();
    // Encrypted chats on disk, with that key.
    let w = Writer::spawn(Duration::from_millis(1));
    let mut s = TalkStore::open_with(&d.join("talk"), &Keyring::File(d.join("key")), w.clone());
    s.put("c1", None, vec![turn("u1", "hello", 1)], false, 1)
        .unwrap();
    s.flush().unwrap();
    // The wallet is locked until `unlocked` exists.
    let tool = fake_tool(
        &d,
        &format!(
            "if [ -e {0}/unlocked ]; then cat {0}/key; exit 0; else echo 'Cannot get secret of a locked object' >&2; exit 1; fi",
            d.display()
        ),
    );
    unsafe { std::env::set_var("MIMIR_SECRET_TOOL", &tool) };
    let mut locked = TalkStore::open_with(&d.join("talk"), &Keyring::SecretService, w.clone());
    assert!(locked.locked());
    assert!(locked.get("c1").is_none());
    assert!(locked.put("c1", None, vec![], false, 2).is_err());
    // Asked again on each request (gap zero), not only at start.
    let mut un = Unlock::new(Duration::ZERO);
    for _ in 0..5 {
        un.step(&mut locked);
        std::thread::sleep(Duration::from_millis(30));
    }
    assert!(locked.locked(), "still locked while the wallet is");
    std::fs::write(d.join("unlocked"), "").unwrap();
    assert!(wait_until(|| {
        un.step(&mut locked);
        !locked.locked()
    }));
    assert_eq!(locked.get("c1").unwrap().turns[0].text, "hello");
    assert!(!d.join("stored").exists(), "no new key was made");
    unsafe { std::env::remove_var("MIMIR_SECRET_TOOL") };
    let _ = std::fs::remove_dir_all(&d);
}

#[test]
fn an_empty_lookup_with_status_0_makes_no_key() {
    let _g = crate::TEST_TOOL.lock().unwrap_or_else(|e| e.into_inner());
    let d = tmp("empty0");
    let tool = fake_tool(&d, "exit 0");
    unsafe { std::env::set_var("MIMIR_SECRET_TOOL", &tool) };
    assert!(Keyring::SecretService.key(true).is_err());
    assert!(!d.join("stored").exists(), "a key was made");
    let s = TalkStore::open_with(
        &d.join("talk"),
        &Keyring::SecretService,
        Writer::spawn(Duration::from_millis(1)),
    );
    assert!(s.locked(), "an empty answer is 'locked', never plain");
    unsafe { std::env::remove_var("MIMIR_SECRET_TOOL") };
    let _ = std::fs::remove_dir_all(&d);
}

#[test]
fn an_empty_lookup_with_status_1_makes_a_key() {
    let _g = crate::TEST_TOOL.lock().unwrap_or_else(|e| e.into_inner());
    let d = tmp("empty1");
    // "Not found" until a key is stored; then the stored key.
    let tool = fake_tool(
        &d,
        &format!(
            "if [ -e {0}/stored ]; then cat {0}/stored; exit 0; else exit 1; fi",
            d.display()
        ),
    );
    unsafe { std::env::set_var("MIMIR_SECRET_TOOL", &tool) };
    let k = Keyring::SecretService.key(true).expect("a new key");
    assert_eq!(
        std::fs::read_to_string(d.join("stored")).unwrap(),
        encode(&k)
    );
    // And the same key next time (the attributes match).
    assert_eq!(Keyring::SecretService.key(true).unwrap(), k);
    unsafe { std::env::remove_var("MIMIR_SECRET_TOOL") };
    let _ = std::fs::remove_dir_all(&d);
}

/// A disk that is full while `full` is set.
struct FullDisk(Arc<AtomicBool>);

impl Sink for FullDisk {
    fn write(&mut self, path: &Path, bytes: &[u8]) -> std::io::Result<()> {
        if self.0.load(Ordering::SeqCst) {
            return Err(std::io::Error::from_raw_os_error(libc::ENOSPC));
        }
        crate::atomic::write(path, bytes)
    }
}

#[test]
fn a_full_disk_keeps_the_change_dirty_and_writes_it_once_freed() {
    let d = tmp("full");
    let full = Arc::new(AtomicBool::new(true));
    let w = Writer::with_sink(Duration::from_millis(50), FullDisk(full.clone()));
    let mut s = TalkStore::open_with(&d, &Keyring::Off, w.clone());
    s.put("c1", None, vec![turn("u1", "kept", 1)], false, 1)
        .unwrap();
    assert!(s.flush().is_err());
    assert_eq!(w.failing(), 2, "the chat and the counter wait");
    assert!(s.save_failing().unwrap().contains("No space left"));
    // Still failing a few ticks later: nothing lost, nothing written.
    std::thread::sleep(Duration::from_millis(200));
    assert!(!d.join("c1.json").exists());
    full.store(false, Ordering::SeqCst);
    // The next tick writes it, with no new change.
    assert!(wait_until(|| d.join("c1.json").exists() && w.failing() == 0));
    let again = TalkStore::open_with(&d, &Keyring::Off, w);
    assert_eq!(again.get("c1").unwrap().turns[0].text, "kept");
    assert_eq!(again.seq(), 1);
    let _ = std::fs::remove_dir_all(&d);
}

#[test]
fn only_core_and_hub_depend_on_the_store() {
    let crates = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap();
    for e in std::fs::read_dir(crates).unwrap().flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        let Ok(toml) = std::fs::read_to_string(e.path().join("Cargo.toml")) else {
            continue;
        };
        if toml.contains("mimir-store") && !["core", "hub", "store"].contains(&name.as_str()) {
            panic!("crates/{name} depends on mimir-store; only core and hub may");
        }
    }
}
