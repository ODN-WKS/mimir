//! `mimir import-old`: migrate chats from an earlier app (its folder name is given with `--from`).
//!
//! Rules (PLAN.md §B9):
//! - **Read-only** on the old app's files; uses `secret-tool lookup` for the
//!   key and stops on a non-zero exit or empty output.
//! - **Backs up first**: copies `<data>/mimir/talk/` to
//!   `<data>/mimir/talk.bak-<timestamp>/` before writing anything.
//! - **Incremental and idempotent**: keyed by `chat_id + turn_id + seq`.
//!   A second run adds 0 turns.
//! - **Sources**: every chat in
//!   `<data>/<from>/chats-inventory.json`, plus the A5 bundle at
//!   `<data>/<from>/a5-export/` if present.
//! - **Pictures**: copied alongside the chat.
//! - **Dry-run**: `--dry-run` prints the summary without writing.

use std::collections::HashSet;
use std::path::{Path, PathBuf};

/// How the keyring is asked for a secret (injected for tests).
pub type KeyringLookup = Box<dyn Fn(&str, &str) -> Result<String, String>>;

/// One chat's import result.
#[derive(Debug, PartialEq)]
pub struct ChatSummary {
    pub chat_id: String,
    pub turns_imported: usize,
    pub pictures_imported: usize,
    pub turns_skipped: usize,
}

impl std::fmt::Display for ChatSummary {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "chat {}: {} turns, {} pictures (skipped {})",
            self.chat_id, self.turns_imported, self.pictures_imported, self.turns_skipped
        )
    }
}

/// A single imported turn, keyed for idempotency.
#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct ImportedTurn {
    pub chat_id: String,
    pub turn_id: String,
    pub seq: u64,
    pub user: bool,
    pub text: String,
    pub at: i64,
}

/// An old-app chat entry read from the inventory.
#[derive(Debug, serde::Deserialize)]
pub struct OldChat {
    pub id: String,
    #[serde(default)]
    pub turns_file: Option<String>,
    #[serde(default)]
    pub pictures_dir: Option<String>,
}

/// Options for `mimir import-old`.
pub struct ImportOptions {
    /// XDG_DATA_HOME (normally ~/.local/share).
    pub data_home: PathBuf,
    /// The earlier app's folder name under `data_home` (given with `--from`).
    pub old_dir: String,
    /// If true, print summaries but write nothing.
    pub dry_run: bool,
    /// Also import task files from the old app (--include-tasks flag).
    pub include_tasks: bool,
    /// How to run `secret-tool lookup` (injected for tests).
    pub keyring_lookup: KeyringLookup,
}

/// Result of importing one task file.
#[derive(Debug, PartialEq)]
pub struct TaskImportResult {
    pub file_name: String,
    pub imported: bool,
    /// Reason for skipping (schema validation failed or already exists).
    pub skip_reason: Option<String>,
}

/// Validate that a task file passes the minimum schema check:
/// must be a JSON object with at least `id` and `title` string fields.
pub fn task_file_valid(raw: &str) -> bool {
    let Ok(v) = serde_json::from_str::<serde_json::Value>(raw) else {
        return false;
    };
    let obj = match v.as_object() {
        Some(o) => o,
        None => return false,
    };
    obj.get("id").and_then(|v| v.as_str()).is_some()
        && obj.get("title").and_then(|v| v.as_str()).is_some()
}

/// Import task files from `<data>/<from>/tasks/` to
/// `<data>/mimir/tasks/`, skipping files that fail schema validation.
/// Returns one result per `.json` file found.
pub fn import_tasks(opts: &ImportOptions) -> Result<Vec<TaskImportResult>, String> {
    let old_tasks = opts.data_home.join(&opts.old_dir).join("tasks");
    let new_tasks = opts.data_home.join("mimir").join("tasks");
    let mut results = Vec::new();

    if !old_tasks.exists() {
        return Ok(results);
    }
    let rd = std::fs::read_dir(&old_tasks).map_err(|e| e.to_string())?;
    for entry in rd.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        let raw = match std::fs::read_to_string(&path) {
            Ok(r) => r,
            Err(e) => {
                results.push(TaskImportResult {
                    file_name: name,
                    imported: false,
                    skip_reason: Some(format!("read error: {e}")),
                });
                continue;
            }
        };
        if !task_file_valid(&raw) {
            results.push(TaskImportResult {
                file_name: name,
                imported: false,
                skip_reason: Some("schema validation failed: missing id or title".into()),
            });
            continue;
        }
        let dst = new_tasks.join(&name);
        if dst.exists() {
            results.push(TaskImportResult {
                file_name: name,
                imported: false,
                skip_reason: Some("already exists".into()),
            });
            continue;
        }
        if !opts.dry_run {
            std::fs::create_dir_all(&new_tasks).map_err(|e| e.to_string())?;
            std::fs::copy(&path, &dst).map_err(|e| e.to_string())?;
        }
        results.push(TaskImportResult {
            file_name: name,
            imported: true,
            skip_reason: None,
        });
    }
    Ok(results)
}

impl ImportOptions {
    /// Production options: real data home, real keyring.
    pub fn new(dry_run: bool, old_dir: &str) -> Self {
        let data_home = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                let mut h = dirs_path();
                h.push(".local/share");
                h
            });
        ImportOptions {
            data_home,
            old_dir: old_dir.to_string(),
            dry_run,
            include_tasks: false,
            keyring_lookup: Box::new(|service, key| {
                let out = crate::flatpak::host_cmd("secret-tool")
                    .args(["lookup", service, key])
                    .output()
                    .map_err(|e| format!("secret-tool not found: {e}"))?;
                if !out.status.success() {
                    return Err(format!(
                        "secret-tool exited {}: {}",
                        out.status,
                        String::from_utf8_lossy(&out.stderr).trim()
                    ));
                }
                let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
                if s.is_empty() {
                    return Err("secret-tool returned empty output".into());
                }
                Ok(s)
            }),
        }
    }
}

fn dirs_path() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/root"))
}

/// Run the import. Returns one [ChatSummary] per chat found.
/// Errors from the keyring stop the import immediately with no data written.
pub fn import_old(opts: &ImportOptions) -> Result<Vec<ChatSummary>, String> {
    // Check keyring first (stops on non-zero exit or empty output).
    (opts.keyring_lookup)("mimir", "chat-key")
        .map_err(|e| format!("Keyring check failed: {e}. Import stopped, nothing written."))?;

    let old_root = opts.data_home.join(&opts.old_dir);
    let new_talk = opts.data_home.join("mimir").join("talk");

    if !opts.dry_run {
        backup_talk(&opts.data_home.join("mimir"))?;
    }

    // Load existing turn keys to skip already-imported turns.
    let existing = load_existing_keys(&new_talk);

    let mut summaries = Vec::new();

    // 1. Inventory file.
    let inventory_path = old_root.join("chats-inventory.json");
    if inventory_path.exists() {
        let raw = std::fs::read_to_string(&inventory_path)
            .map_err(|e| format!("Could not read inventory: {e}"))?;
        let chats: Vec<OldChat> =
            serde_json::from_str(&raw).map_err(|e| format!("Could not parse inventory: {e}"))?;
        for chat in &chats {
            let s = import_chat(chat, &old_root, &new_talk, &existing, opts.dry_run)?;
            summaries.push(s);
        }
    }

    // 2. A5 bundle.
    let a5_dir = old_root.join("a5-export");
    if a5_dir.exists() {
        import_a5_bundle(&a5_dir, &new_talk, &existing, opts.dry_run, &mut summaries)?;
    }

    Ok(summaries)
}

/// Returns the set of `"<chat_id>|<turn_id>|<seq>"` already in the new store.
fn load_existing_keys(talk_dir: &Path) -> HashSet<String> {
    let mut keys = HashSet::new();
    let rd = match std::fs::read_dir(talk_dir) {
        Ok(r) => r,
        Err(_) => return keys,
    };
    for entry in rd.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        if let Ok(raw) = std::fs::read_to_string(&path)
            && let Ok(turns) = serde_json::from_str::<Vec<ImportedTurn>>(&raw)
        {
            for t in turns {
                keys.insert(format!("{}|{}|{}", t.chat_id, t.turn_id, t.seq));
            }
        }
    }
    keys
}

fn import_chat(
    chat: &OldChat,
    old_root: &Path,
    new_talk: &Path,
    existing: &HashSet<String>,
    dry_run: bool,
) -> Result<ChatSummary, String> {
    let turns_file = match &chat.turns_file {
        Some(f) => old_root.join(f),
        None => old_root.join(format!("chats/{}.json", chat.id)),
    };

    let turns: Vec<ImportedTurn> = if turns_file.exists() {
        let raw = std::fs::read_to_string(&turns_file)
            .map_err(|e| format!("Could not read {}: {e}", turns_file.display()))?;
        serde_json::from_str(&raw)
            .map_err(|e| format!("Could not parse {}: {e}", turns_file.display()))?
    } else {
        vec![]
    };

    let mut to_import = Vec::new();
    let mut skipped = 0usize;
    for turn in &turns {
        let key = format!("{}|{}|{}", turn.chat_id, turn.turn_id, turn.seq);
        if existing.contains(&key) {
            skipped += 1;
        } else {
            to_import.push(turn);
        }
    }

    // Pictures.
    let mut pics = 0usize;
    if let Some(pics_dir) = &chat.pictures_dir {
        let src_pics = old_root.join(pics_dir);
        if src_pics.exists() && !dry_run {
            let dst_pics = new_talk.join(format!("pics-{}", chat.id));
            if let Ok(rd) = std::fs::read_dir(&src_pics) {
                for entry in rd.flatten() {
                    std::fs::create_dir_all(&dst_pics).ok();
                    let dst = dst_pics.join(entry.file_name());
                    if std::fs::copy(entry.path(), &dst).is_ok() {
                        pics += 1;
                    }
                }
            }
        } else if src_pics.exists()
            && let Ok(rd) = std::fs::read_dir(&src_pics)
        {
            pics = rd.flatten().count();
        }
    }

    if !dry_run && !to_import.is_empty() {
        std::fs::create_dir_all(new_talk).map_err(|e| e.to_string())?;
        let dst = new_talk.join(format!("{}.json", chat.id));
        // Merge with any file already there.
        let mut all: Vec<ImportedTurn> = if dst.exists() {
            serde_json::from_str(&std::fs::read_to_string(&dst).unwrap_or_default())
                .unwrap_or_default()
        } else {
            vec![]
        };
        for t in &to_import {
            all.push(ImportedTurn {
                chat_id: t.chat_id.clone(),
                turn_id: t.turn_id.clone(),
                seq: t.seq,
                user: t.user,
                text: t.text.clone(),
                at: t.at,
            });
        }
        let data = serde_json::to_vec_pretty(&all).map_err(|e| e.to_string())?;
        std::fs::write(&dst, &data).map_err(|e| e.to_string())?;
    }

    Ok(ChatSummary {
        chat_id: chat.id.clone(),
        turns_imported: to_import.len(),
        pictures_imported: pics,
        turns_skipped: skipped,
    })
}

fn import_a5_bundle(
    a5_dir: &Path,
    new_talk: &Path,
    existing: &HashSet<String>,
    dry_run: bool,
    summaries: &mut Vec<ChatSummary>,
) -> Result<(), String> {
    let rd = std::fs::read_dir(a5_dir).map_err(|e| e.to_string())?;
    for entry in rd.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let stem = path
            .file_stem()
            .unwrap_or_default()
            .to_string_lossy()
            .to_string();
        let raw = std::fs::read_to_string(&path)
            .map_err(|e| format!("Could not read a5 file {}: {e}", path.display()))?;
        let _turns: Vec<ImportedTurn> = serde_json::from_str(&raw).unwrap_or_default();
        let chat = OldChat {
            id: stem,
            turns_file: Some(path.to_string_lossy().to_string()),
            pictures_dir: None,
        };
        let s = import_chat(&chat, a5_dir, new_talk, existing, dry_run)?;
        summaries.push(s);
    }
    Ok(())
}

/// Creates `<mimir_data>/talk.bak-<timestamp>/` with the contents of
/// `<mimir_data>/talk/`.
fn backup_talk(mimir_data: &Path) -> Result<(), String> {
    let talk = mimir_data.join("talk");
    if !talk.exists() {
        return Ok(());
    }
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let bak = mimir_data.join(format!("talk.bak-{stamp}"));
    copy_dir(&talk, &bak).map_err(|e| format!("Could not back up talk/: {e}"))
}

fn copy_dir(src: &Path, dst: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)?.flatten() {
        let d = dst.join(entry.file_name());
        if entry.path().is_dir() {
            copy_dir(&entry.path(), &d)?;
        } else {
            std::fs::copy(entry.path(), d)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    type InventoryChat<'a> = (&'a str, Vec<(&'a str, bool, &'a str)>);
    use super::*;
    use std::fs;

    fn tmp(name: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("mimir-import-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }

    fn good_keyring() -> KeyringLookup {
        Box::new(|_, _| Ok("aabbcc".to_string()))
    }

    fn bad_keyring() -> KeyringLookup {
        Box::new(|_, _| Err("secret-tool exited 1: not found".to_string()))
    }

    fn write_inventory(root: &Path, chats: &[InventoryChat]) {
        let old = root.join("earlier-app");
        fs::create_dir_all(&old).unwrap();
        let mut records = vec![];
        for (id, turns) in chats {
            let turns_data: Vec<_> = turns
                .iter()
                .enumerate()
                .map(|(i, (tid, user, text))| {
                    serde_json::json!({
                        "chat_id": id,
                        "turn_id": tid,
                        "seq": i as u64,
                        "user": user,
                        "text": text,
                        "at": 1_000_000i64 + i as i64,
                    })
                })
                .collect();
            let turns_file = format!("chats/{id}.json");
            fs::create_dir_all(old.join("chats")).unwrap();
            fs::write(
                old.join(&turns_file),
                serde_json::to_vec(&turns_data).unwrap(),
            )
            .unwrap();
            records.push(serde_json::json!({ "id": id, "turns_file": turns_file }));
        }
        fs::write(
            old.join("chats-inventory.json"),
            serde_json::to_vec(&records).unwrap(),
        )
        .unwrap();
    }

    #[test]
    fn import_idempotent() {
        let root = tmp("idempotent");
        let data_home = root.join("share");
        write_inventory(
            &data_home,
            &[(
                "chat1",
                vec![
                    ("t1", true, "hi"),
                    ("t2", false, "hello"),
                    ("t3", true, "bye"),
                ],
            )],
        );

        let run = |keyring: KeyringLookup| {
            import_old(&ImportOptions {
                data_home: data_home.clone(),
                old_dir: "earlier-app".into(),
                dry_run: false,
                include_tasks: false,
                keyring_lookup: keyring,
            })
            .unwrap()
        };

        let r1 = run(good_keyring());
        assert_eq!(r1[0].turns_imported, 3);
        assert_eq!(r1[0].turns_skipped, 0);

        let r2 = run(good_keyring());
        assert_eq!(r2[0].turns_imported, 0, "second run adds 0 turns");
        assert_eq!(r2[0].turns_skipped, 3);

        // Check store has exactly 3 turns.
        let stored_path = data_home.join("mimir/talk/chat1.json");
        let stored: Vec<ImportedTurn> =
            serde_json::from_str(&fs::read_to_string(&stored_path).unwrap()).unwrap();
        assert_eq!(stored.len(), 3);
    }

    #[test]
    fn import_dry_run() {
        let root = tmp("dry");
        let data_home = root.join("share");
        write_inventory(&data_home, &[("chat99", vec![("t1", true, "hi")])]);

        let r = import_old(&ImportOptions {
            data_home: data_home.clone(),
            old_dir: "earlier-app".into(),
            dry_run: true,
            include_tasks: false,
            keyring_lookup: good_keyring(),
        })
        .unwrap();

        assert_eq!(r[0].chat_id, "chat99");
        assert_eq!(r[0].turns_imported, 1);
        // No files written.
        assert!(!data_home.join("mimir/talk/chat99.json").exists());
    }

    #[test]
    fn import_backup() {
        let root = tmp("backup");
        let data_home = root.join("share");
        write_inventory(&data_home, &[("chatB", vec![("t1", true, "yo")])]);

        // Seed an existing talk file.
        let talk = data_home.join("mimir/talk");
        fs::create_dir_all(&talk).unwrap();
        fs::write(talk.join("old.json"), b"existing").unwrap();

        import_old(&ImportOptions {
            data_home: data_home.clone(),
            old_dir: "earlier-app".into(),
            dry_run: false,
            include_tasks: false,
            keyring_lookup: good_keyring(),
        })
        .unwrap();

        // A bak directory should exist.
        let bak: Vec<_> = fs::read_dir(data_home.join("mimir"))
            .unwrap()
            .flatten()
            .filter(|e| e.file_name().to_string_lossy().starts_with("talk.bak-"))
            .collect();
        assert!(!bak.is_empty(), "backup directory created");
        // The original file is in the backup.
        let bak_dir = bak[0].path();
        assert!(bak_dir.join("old.json").exists());
    }

    #[test]
    fn import_stops_on_keyring_fail() {
        let root = tmp("keyring");
        let data_home = root.join("share");
        write_inventory(&data_home, &[("chatK", vec![("t1", true, "hi")])]);

        let result = import_old(&ImportOptions {
            data_home: data_home.clone(),
            old_dir: "earlier-app".into(),
            dry_run: false,
            include_tasks: false,
            keyring_lookup: bad_keyring(),
        });

        assert!(result.is_err());
        let msg = result.unwrap_err();
        assert!(msg.contains("Keyring"), "error message: {msg}");
        // No data written.
        assert!(!data_home.join("mimir/talk").exists());
    }

    /// --include-tasks: invalid schema files skipped, valid files copied.
    #[test]
    fn import_tasks_valid_only() {
        let root = tmp("tasks");
        let data_home = root.join("share");
        let old_tasks = data_home.join("earlier-app/tasks");
        fs::create_dir_all(&old_tasks).unwrap();

        // A valid task file.
        fs::write(
            old_tasks.join("task-valid.json"),
            r#"{"id": "t1", "title": "My Task", "project": "/home/x/app", "status": "Done", "entries": [], "created_unix": 0, "cost_usd": 0}"#,
        ).unwrap();

        // An invalid task file (missing title).
        fs::write(
            old_tasks.join("task-invalid.json"),
            r#"{"id": "t2", "project": "/home/x/app"}"#,
        )
        .unwrap();

        // A non-JSON file (should be skipped automatically).
        fs::write(old_tasks.join("notes.txt"), b"not json").unwrap();

        let opts = ImportOptions {
            data_home: data_home.clone(),
            old_dir: "earlier-app".into(),
            dry_run: false,
            include_tasks: true,
            keyring_lookup: good_keyring(),
        };
        let results = import_tasks(&opts).unwrap();

        // Find the results for the two JSON files.
        let valid_r = results
            .iter()
            .find(|r| r.file_name == "task-valid.json")
            .unwrap();
        let invalid_r = results
            .iter()
            .find(|r| r.file_name == "task-invalid.json")
            .unwrap();

        assert!(valid_r.imported, "valid task must be copied");
        assert!(!invalid_r.imported, "invalid task must be skipped");
        assert!(
            invalid_r
                .skip_reason
                .as_deref()
                .unwrap_or("")
                .contains("schema"),
            "skip reason must mention schema: {:?}",
            invalid_r.skip_reason
        );

        // Valid file actually copied.
        assert!(
            data_home.join("mimir/tasks/task-valid.json").exists(),
            "valid task file must be in new location"
        );
        // Invalid file not copied.
        assert!(
            !data_home.join("mimir/tasks/task-invalid.json").exists(),
            "invalid task file must not be copied"
        );
    }
}
