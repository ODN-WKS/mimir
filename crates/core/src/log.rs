//! Mímir's log: `~/.local/state/mimir/log.jsonl`, one
//! JSON object per line (`time`, `job`, `what`, `detail`). A job ID follows
//! one piece of work (a phone message through routing, its task and its
//! approvals). Lines older than 30 days are dropped when the service starts.

use std::io::Write;
use std::path::PathBuf;

const KEEP_DAYS: i64 = 30;

pub fn log_path() -> PathBuf {
    std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .unwrap_or_else(|| crate::paths::home().join(".local/state"))
        .join("mimir")
        .join("log.jsonl")
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// A detail cut to a line's worth.
pub fn cut(s: &str) -> String {
    s.chars().take(300).collect()
}

/// Adds a line.
pub fn write(job: &str, what: &str, detail: &str) {
    // The developer log gets it too, under the category the job belongs to.
    let cat = match job {
        "engine" | "models" | "ollama" => "model",
        "host" | "keeper" | "incognito" | "task" => "task",
        "talk" => "store",
        "usage" | "update" | "install" => "service",
        _ => "service",
    };
    crate::devlog::log_raw(crate::devlog::Raw::new(
        cat,
        "note",
        vec![
            ("job", job.into()),
            ("what", what.into()),
            ("detail", crate::log::cut(detail).into()),
        ],
    ));
    let p = log_path();
    if let Some(d) = p.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    let line = serde_json::json!({"time": now(), "job": job, "what": what, "detail": detail});
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&p)
    {
        let _ = writeln!(f, "{line}");
    }
}

/// Drops lines older than 30 days.
pub fn prune() {
    let p = log_path();
    let Ok(text) = std::fs::read_to_string(&p) else {
        return;
    };
    let cutoff = now() - KEEP_DAYS * 24 * 3600;
    let kept: Vec<&str> = text
        .lines()
        .filter(|l| {
            serde_json::from_str::<serde_json::Value>(l)
                .ok()
                .and_then(|v| v["time"].as_i64())
                .is_none_or(|t| t >= cutoff)
        })
        .collect();
    if kept.len() != text.lines().count() {
        let tmp = p.with_extension("jsonl.tmp");
        if std::fs::write(&tmp, kept.join("\n") + "\n").is_ok() {
            let _ = std::fs::rename(tmp, p);
        }
    }
}

/// The lines of one job, oldest first.
pub fn job(job: &str) -> Vec<serde_json::Value> {
    std::fs::read_to_string(log_path())
        .unwrap_or_default()
        .lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .filter(|v| v["job"] == job)
        .collect()
}
