//! Where general chats live on the PC: the store itself is in
//! `mimir-store` (`mimir_store::talk`); this names its folder, sends its
//! notes to the developer log, and is `mimir chats`.

pub use mimir_store::keyring::Keyring;
pub use mimir_store::talk::*;

use std::path::PathBuf;

/// The folder the chats live in.
pub fn dir() -> PathBuf {
    crate::paths::data_dir().join("talk")
}

/// Sends the store's notes to the developer log (once; later calls do
/// nothing).
pub fn log_to_devlog() {
    mimir_store::log::set(|area, what, f, err| {
        if err.is_empty() {
            crate::devlog::log(area, what, f);
        } else {
            crate::devlog::error(area, what, f, err, "io");
        }
    });
}

/// Writes every chat change still waiting (the service's shutdown).
pub fn flush() {
    let _ = mimir_store::Writer::global().flush(std::time::Duration::from_secs(10));
}

/// `mimir chats`: the chats on this PC, read through the keyring
/// (for checking what reached the PC).
pub fn command(args: &[String]) -> i32 {
    let s = TalkStore::open(&dir(), &Keyring::from_env());
    if args.first().map(String::as_str) == Some("--json") {
        let list: Vec<_> = s.list().into_iter().cloned().collect();
        println!(
            "{}",
            serde_json::json!({
                "encrypted": s.encrypted(),
                "locked": s.locked(),
                "notice": s.notice(),
                "seq": s.seq(),
                "chats": list,
            })
        );
        return if s.locked() { 1 } else { 0 };
    }
    println!(
        "{} chats on this PC, {}; newest change {}",
        s.list().len(),
        if s.encrypted() {
            "encrypted"
        } else {
            "not encrypted"
        },
        s.seq()
    );
    let full = args.first().map(String::as_str) == Some("--full");
    for c in s.list() {
        println!(
            "{} · {} turns · {}",
            c.id,
            c.turns.len(),
            c.name.as_deref().unwrap_or("")
        );
        if full {
            for t in &c.turns {
                println!("  {}: {}", if t.user { "you" } else { "answer" }, t.text);
            }
        }
    }
    0
}
