//! Staying current, and keeping nothing that isn't used (owner, 2026-11-18:
//! "I don't want stuff to just sit there unused on any application").
//!
//! * The phone's standalone models come from here (the list ships with
//!   Mímir, so phones get a new list without a new phone app).
//! * Free models in Ollama not used for [FREE_UNUSED_DAYS] days are removed.
//! * Check leftovers under `~/.cache/mimir-checks` untouched for
//!   [CACHE_UNUSED_DAYS] days are removed.
//! * Once a week (`mimir weekly`, from a systemd timer) the above
//!   runs, and the uncensored-models leaderboard is read for a small model
//!   that beats the phone's best; if one does, an update card asks
//!   "Worth switching?". Nothing changes without the owner.

use serde_json::{Value, json};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub const FREE_UNUSED_DAYS: u64 = 30;
pub const CACHE_UNUSED_DAYS: u64 = 14;
const DAY: u64 = 24 * 60 * 60;

/// The phone's standalone models (Apache 2.0; sizes and SHA-256 from the
/// repositories' own listings, 2026-11-18). The first is the default.
/// `source` is the model's own repository and `ugi` its score on the
/// uncensored-models leaderboard, used by the weekly check.
pub const STANDALONE: &str = r#"{"models": [
  {"id": "qwen35-4b-sompoa-heresy2-q4km", "name": "Qwen 3.5 4B SOMPOA heresy",
   "note": "The default: the most open answers of any model this size",
   "url": "https://huggingface.co/mradermacher/Qwen3.5-4B-SOMPOA-heresy-v2-i1-GGUF/resolve/main/Qwen3.5-4B-SOMPOA-heresy-v2.i1-Q4_K_M.gguf",
   "bytes": 2707515040, "sha256": "dc781ccfea6f4e4e5f9bba656676798b70af8a69f2a601e9b46ff46094034115",
   "format": "chatml", "refusals_removed": true, "thinking": true,
   "source": "MuXodious/Qwen3.5-4B-SOMPOA-heresy-v2", "ugi": 44.7},
  {"id": "qwen35-9b-huihui-abliterated-q4km", "name": "Huihui Qwen 3.5 9B abliterated",
   "note": "Bigger and stronger; for phones with plenty of memory",
   "url": "https://huggingface.co/mradermacher/Huihui-Qwen3.5-9B-abliterated-i1-GGUF/resolve/main/Huihui-Qwen3.5-9B-abliterated.i1-Q4_K_M.gguf",
   "bytes": 5627045536, "sha256": "1b251677521c3452c4e2eb8cdb1504d40d0c2e64f77cd4222cfd17cbe2d195fc",
   "format": "chatml", "refusals_removed": true, "thinking": true, "min_ram_gb": 10,
   "source": "huihui-ai/Huihui-Qwen3.5-9B-abliterated", "ugi": 43.2},
  {"id": "qwen35-9b-heretic2-q4km", "name": "Qwen 3.5 9B heretic v2",
   "note": "The smartest one that fits a phone; for phones with plenty of memory",
   "url": "https://huggingface.co/mradermacher/Qwen3.5-9B-heretic-v2-i1-GGUF/resolve/main/Qwen3.5-9B-heretic-v2.i1-Q4_K_M.gguf",
   "bytes": 5629109952, "sha256": "e188c0208369b1906067d18b207f10f3bd2aa78cb6a8637fa0537da9146eaa2f",
   "format": "chatml", "refusals_removed": true, "thinking": true, "min_ram_gb": 10,
   "source": "trohrbaugh/Qwen3.5-9B-heretic-v2", "ugi": 40.5},
  {"id": "gemma4-e2b-heretic-q4km", "name": "Gemma 4 E2B heretic", "note": "The quickest",
   "url": "https://huggingface.co/gabrielmtzcarrillo/gemma-4-E2B-it-heretic-gguf/resolve/main/google--gemma-4-E2B-it-heretic-Q4_K_M.gguf",
   "bytes": 3427879968, "sha256": "d54afc6f6c4b4f0bc04352f9f6dfc17566ef6440a41746c3c5326ec0cbc5ce20",
   "format": "gemma4", "refusals_removed": true, "thinking": false,
   "vision": {"url": "https://huggingface.co/gabrielmtzcarrillo/gemma-4-E2B-it-heretic-gguf/resolve/main/google--gemma-4-E2B-it-heretic-mmproj-F16.gguf",
              "bytes": 985653600, "sha256": "7faa59eb5822cc442b65a5f30b8ced7e1326a0721ab4dc2d07e70d452e1cfc5c"},
   "source": "gabrielmtzcarrillo/gemma-4-E2B-it-heretic"},
  {"id": "gemma4-e4b-ultra-heretic-q4km", "name": "Gemma 4 E4B ultra-uncensored heretic", "note": "Gemma at its most open, and slower; it can think first",
   "url": "https://huggingface.co/llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic-GGUF/resolve/main/gemma-4-E4B-it-ultra-uncensored-heretic-Q4_K_M.gguf",
   "bytes": 5335289696, "sha256": "f9dbbe3bdf396f65aec4e801516a1b868a510d96225417a8aa06763e3c5b97dc",
   "format": "gemma4", "refusals_removed": true, "thinking": true,
   "vision": {"url": "https://huggingface.co/llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic-GGUF/resolve/main/gemma-4-E4B-it-mmproj-BF16.gguf",
              "bytes": 991552000, "sha256": "ffa64aebf7144bbadd5a1e143b77794d88e76a81cc0168d91de0770eb49e88e0"},
   "source": "llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic", "ugi": 38.5}
]}"#;

/// A list the owner chose to use instead (kept in the data folder).
pub fn standalone_file() -> PathBuf {
    crate::paths::data_dir().join("standalone-models.json")
}

/// Whether a list is one a phone can use: every entry complete, from https,
/// with a real checksum, and no id twice.
pub fn valid_standalone(v: &Value) -> bool {
    let Some(a) = v["models"].as_array() else {
        return false;
    };
    let mut ids = vec![];
    !a.is_empty()
        && a.iter().all(|m| {
            let id = m["id"].as_str().unwrap_or("");
            let sha = m["sha256"].as_str().unwrap_or("");
            let ok = !id.is_empty()
                && id
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || "-_.".contains(c))
                && !ids.contains(&id)
                && m["name"].as_str().is_some_and(|n| !n.is_empty())
                && m["url"].as_str().is_some_and(|u| u.starts_with("https://"))
                && m["bytes"].as_u64().is_some_and(|b| b > 0)
                && sha.len() == 64
                && sha.chars().all(|c| c.is_ascii_hexdigit());
            ids.push(id);
            ok
        })
}

/// The list phones get: the owner's chosen list if there is a usable one,
/// else the one that ships with this version.
pub fn standalone_catalogue() -> Value {
    std::fs::read(standalone_file())
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .filter(valid_standalone)
        .unwrap_or_else(|| serde_json::from_str(STANDALONE).expect("the built-in list is valid"))
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

// ---------- Free models in Ollama ----------

fn used_file() -> PathBuf {
    crate::paths::data_dir().join("free-models-used.json")
}

fn read_used() -> HashMap<String, u64> {
    std::fs::read(used_file())
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn write_used(m: &HashMap<String, u64>) {
    let f = used_file();
    if let Some(d) = f.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    let tmp = f.with_extension("json.tmp");
    if std::fs::write(&tmp, serde_json::to_vec_pretty(m).unwrap_or_default()).is_ok() {
        let _ = std::fs::rename(&tmp, &f);
    }
}

/// A free model was just used (a task started on it).
pub fn record_free_use(name: &str) {
    let mut m = read_used();
    m.insert(name.to_string(), now_secs());
    write_used(&m);
}

/// Whether the weekly run may remove free models. Off: Mímir's models are
/// shared with the new Mímir, and nothing here deletes a model any more.
const REMOVES_FREE_MODELS: bool = false;

/// The installed models not used for `days` days, never one in `keep` (the
/// chat model, the project model, the phone-size chat model). A model seen
/// for the first time starts its clock now (so nothing goes the first week
/// this runs).
pub fn stale_free_models(
    installed: &[String],
    used: &mut HashMap<String, u64>,
    now: u64,
    days: u64,
    keep: &[String],
) -> Vec<String> {
    used.retain(|k, _| installed.contains(k));
    let mut stale = vec![];
    for name in installed {
        let last = *used.entry(name.clone()).or_insert(now);
        if now.saturating_sub(last) > days * DAY && !keep.contains(name) {
            stale.push(name.clone());
        }
    }
    stale
}

/// When each free model was last used (seconds since 1970).
pub fn last_used() -> HashMap<String, u64> {
    read_used()
}

/// Bytes of each installed model (Ollama's `/api/tags`).
pub fn ollama_sizes() -> HashMap<String, u64> {
    let out = std::process::Command::new("curl")
        .args([
            "-s",
            "-m",
            "5",
            &format!("{}/api/tags", crate::models::ollama_url()),
        ])
        .output();
    let v: Value = out
        .ok()
        .and_then(|o| serde_json::from_slice(&o.stdout).ok())
        .unwrap_or_default();
    v["models"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|m| Some((m["name"].as_str()?.to_string(), m["size"].as_u64()?)))
                .collect()
        })
        .unwrap_or_default()
}

pub fn delete_ollama(name: &str) -> bool {
    std::process::Command::new("curl")
        .args([
            "-s",
            "-m",
            "30",
            "-X",
            "DELETE",
            &format!("{}/api/delete", crate::models::ollama_url()),
            "-d",
            &json!({ "model": name }).to_string(),
            "-o",
            "/dev/null",
            "-w",
            "%{http_code}",
        ])
        .output()
        .is_ok_and(|o| String::from_utf8_lossy(&o.stdout).trim() == "200")
}

/// Removes free models unused for [FREE_UNUSED_DAYS] days; returns
/// (name, bytes) of each one removed. Starts Ollama only if it has models
/// to look at, and stops nothing it didn't start.
pub fn tidy_free_models() -> Vec<(String, u64)> {
    // Never starts or stops the Ollama unit, and removes nothing.
    if !REMOVES_FREE_MODELS {
        return vec![];
    }
    if !crate::models::unit_path().is_file() && crate::models::installed().is_err() {
        return vec![];
    }
    let started = crate::models::installed().is_err()
        && crate::models::ensure_running(Duration::from_secs(20));
    let Ok(installed) = crate::models::installed() else {
        return vec![];
    };
    let sizes = ollama_sizes();
    let mut used = read_used();
    let mut keep: Vec<String> = vec![crate::models::SMALL_CHAT.to_string()];
    keep.extend(crate::engine::chat_model().map(|p| p.model));
    keep.extend(
        crate::settings::Settings::read()
            .ok()
            .into_iter()
            .flat_map(|s| s.free_model)
            .filter_map(|m| crate::models::ollama_name(&m).map(str::to_string)),
    );
    let stale = stale_free_models(&installed, &mut used, now_secs(), FREE_UNUSED_DAYS, &keep);
    let mut removed = vec![];
    for name in stale {
        if delete_ollama(&name) {
            used.remove(&name);
            removed.push((name.clone(), sizes.get(&name).copied().unwrap_or(0)));
        }
    }
    write_used(&used);
    if started {
        let _ = crate::flatpak::host_cmd("systemctl")
            .args(["--user", "stop", "mimir-ollama.service"])
            .status();
    }
    removed
}

// ---------- Check leftovers ----------

/// The newest change anywhere under `p` (a folder's own time only changes
/// when entries are added or removed, so its files are looked at too).
fn newest(p: &Path) -> SystemTime {
    let mut t = std::fs::symlink_metadata(p)
        .and_then(|m| m.modified())
        .unwrap_or(UNIX_EPOCH);
    if p.is_dir()
        && !p.is_symlink()
        && let Ok(rd) = std::fs::read_dir(p)
    {
        for e in rd.flatten() {
            t = t.max(newest(&e.path()));
        }
    }
    t
}

/// Entries directly under `dir` with nothing changed for `days` days.
pub fn stale_entries(dir: &Path, now: SystemTime, days: u64) -> Vec<PathBuf> {
    let cutoff = now
        .checked_sub(Duration::from_secs(days * DAY))
        .unwrap_or(UNIX_EPOCH);
    let mut out: Vec<PathBuf> = std::fs::read_dir(dir)
        .map(|rd| {
            rd.flatten()
                .map(|e| e.path())
                .filter(|p| newest(p) < cutoff)
                .collect()
        })
        .unwrap_or_default();
    out.sort();
    out
}

fn size_of(p: &Path) -> u64 {
    match std::fs::symlink_metadata(p) {
        Ok(m) if m.is_dir() => std::fs::read_dir(p)
            .map(|rd| rd.flatten().map(|e| size_of(&e.path())).sum())
            .unwrap_or(0),
        Ok(m) => m.len(),
        Err(_) => 0,
    }
}

/// Removes check leftovers untouched for [CACHE_UNUSED_DAYS] days; returns
/// the bytes freed. Git work copies are taken away through git, so the
/// repository forgets them too.
pub fn tidy_checks() -> u64 {
    let dir = crate::paths::home().join(".cache/mimir-checks");
    let mut freed = 0;
    for p in stale_entries(&dir, SystemTime::now(), CACHE_UNUSED_DAYS) {
        let bytes = size_of(&p);
        let gone = if p.join(".git").is_file() {
            crate::flatpak::host_cmd("git")
                .args([
                    "-C",
                    &p.to_string_lossy(),
                    "worktree",
                    "remove",
                    "--force",
                    ".",
                ])
                .status()
                .is_ok_and(|s| s.success())
                || std::fs::remove_dir_all(&p).is_ok()
        } else if p.is_dir() {
            std::fs::remove_dir_all(&p).is_ok()
        } else {
            std::fs::remove_file(&p).is_ok()
        };
        if gone {
            freed += bytes;
        }
    }
    freed
}

// ---------- The leaderboard ----------

/// Where the uncensored-models leaderboard keeps its table.
pub const UGI_CSV: &str = "https://huggingface.co/spaces/DontPlanToEnd/UGI-Leaderboard/resolve/main/ugi-leaderboard-data.csv";

/// Splits a CSV table into rows of fields (quotes, doubled quotes and line
/// breaks inside quotes allowed).
pub fn parse_csv(text: &str) -> Vec<Vec<String>> {
    let mut rows = vec![];
    let mut row = vec![];
    let mut field = String::new();
    let mut quoted = false;
    let mut chars = text.trim_start_matches('\u{feff}').chars().peekable();
    while let Some(c) = chars.next() {
        match (c, quoted) {
            ('"', true) if chars.peek() == Some(&'"') => {
                field.push('"');
                chars.next();
            }
            ('"', true) => quoted = false,
            ('"', false) if field.is_empty() => quoted = true,
            (',', false) => row.push(std::mem::take(&mut field)),
            ('\n', false) => {
                row.push(std::mem::take(&mut field));
                rows.push(std::mem::take(&mut row));
            }
            ('\r', false) => {}
            (c, _) => field.push(c),
        }
    }
    if !field.is_empty() || !row.is_empty() {
        row.push(field);
        rows.push(row);
    }
    rows
}

/// A model from the leaderboard that may be worth adding to the phone.
#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    pub name: String,
    pub link: String,
    pub ugi: f64,
    pub params_b: f64,
}

/// Leaderboard models small enough for the phone (at most `max_params_b`
/// billion parameters) that score at least `margin` above the best model
/// the phone offers, best first, leaving out ones already offered or already
/// suggested (`seen`, by link).
pub fn better_candidates(
    csv: &str,
    offered: &Value,
    seen: &[String],
    max_params_b: f64,
    margin: f64,
) -> Vec<Candidate> {
    let rows = parse_csv(csv);
    let Some(head) = rows.first() else {
        return vec![];
    };
    let col = |starts: &str| head.iter().position(|h| h.trim().starts_with(starts));
    let (Some(name_c), Some(link_c), Some(ugi_c), Some(p_c)) = (
        col("author/model_name"),
        col("Model Link"),
        col("UGI"),
        col("Total Parameters"),
    ) else {
        return vec![];
    };
    let sources: Vec<String> = offered["models"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|m| m["source"].as_str().map(str::to_lowercase))
                .collect()
        })
        .unwrap_or_default();
    let best = offered["models"]
        .as_array()
        .and_then(|a| a.iter().filter_map(|m| m["ugi"].as_f64()).reduce(f64::max))
        .unwrap_or(0.0);
    let mut out: Vec<Candidate> = rows[1..]
        .iter()
        .filter_map(|r| {
            let link = r.get(link_c)?.trim().to_string();
            let c = Candidate {
                name: r.get(name_c)?.trim().to_string(),
                ugi: r.get(ugi_c)?.trim().parse().ok()?,
                params_b: r.get(p_c)?.trim().parse().ok()?,
                link: link.clone(),
            };
            let repo = link
                .trim_start_matches("https://huggingface.co/")
                .trim_end_matches('/')
                .to_lowercase();
            (c.params_b > 0.0
                && c.params_b <= max_params_b
                && c.ugi >= best + margin
                && !sources.contains(&repo)
                && !seen.contains(&link))
            .then_some(c)
        })
        .collect();
    out.sort_by(|a, b| b.ugi.total_cmp(&a.ugi));
    // One entry per model, its best score (the board lists thinking and
    // no-thinking apart).
    let mut links = vec![];
    out.retain(|c| {
        let new = !links.contains(&c.link);
        links.push(c.link.clone());
        new
    });
    out
}

/// What the last weekly look found, for Settings › Models on the PC.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct WeeklyResult {
    /// When it ran (seconds since 1970).
    pub at: u64,
    /// The "Worth switching?" text, when a better phone model was found.
    pub found: Option<String>,
    /// Every line it said (what it tidied, and what it found).
    pub said: Vec<String>,
}

fn last_weekly_file() -> PathBuf {
    crate::paths::data_dir().join("weekly-last.json")
}

/// The last weekly look's result, if it ever ran.
pub fn last_weekly() -> Option<WeeklyResult> {
    std::fs::read(last_weekly_file())
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
}

fn save_weekly(found: Option<String>, said: &[String]) {
    let r = WeeklyResult {
        at: now_secs(),
        found,
        said: said.to_vec(),
    };
    let f = last_weekly_file();
    if let Some(d) = f.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    let _ = std::fs::write(&f, serde_json::to_vec_pretty(&r).unwrap_or_default());
}

fn seen_file() -> PathBuf {
    crate::paths::data_dir().join("weekly-suggested.json")
}

// ---------- The weekly run ----------

/// Sends an update card through the service (removed: update cards are gone).
fn card(_text: &str) {}

fn gb(bytes: u64) -> String {
    format!("{:.1} GB", bytes as f64 / 1e9)
}

/// `mimir weekly`: tidy, then look for a better phone model.
/// Prints what it did; 0 unless something it needed failed outright.
pub fn weekly_main() -> i32 {
    let mut said = vec![];
    for (name, bytes) in tidy_free_models() {
        let t = format!(
            "Removed the free model {name} from this PC: unused for {FREE_UNUSED_DAYS} days. That freed {}. It downloads again if you pick it.",
            gb(bytes)
        );
        card(&t);
        said.push(t);
    }
    let freed = tidy_checks();
    if freed > 0 {
        said.push(format!("Removed old check leftovers ({}).", gb(freed)));
    }
    // The owner can turn the weekly look for a better phone model off (Models, on the phone).
    if crate::settings::Settings::read().is_ok_and(|s| s.weekly_model_check == Some(false)) {
        said.push("The weekly look for a better phone model is off.".into());
        save_weekly(None, &said);
        for s in &said {
            println!("{s}");
        }
        return 0;
    }
    let csv = std::process::Command::new("curl")
        .args(["-sL", "-m", "60", UGI_CSV])
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).to_string());
    let mut worth = None;
    match csv {
        None => said.push("Couldn't read the uncensored-models leaderboard this week.".into()),
        Some(csv) => {
            let mut seen: Vec<String> = std::fs::read(seen_file())
                .ok()
                .and_then(|b| serde_json::from_slice(&b).ok())
                .unwrap_or_default();
            let found = better_candidates(&csv, &standalone_catalogue(), &seen, 9.0, 2.0);
            if let Some(c) = found.first() {
                let t = format!(
                    "Worth switching? A new small uncensored model, {}, scores {:.1} on the uncensored-models leaderboard, above your phone's best. Ask me to try it and add it to On this phone; nothing changes until you do.",
                    c.name, c.ugi
                );
                card(&t);
                worth = Some(t.clone());
                said.push(t);
                seen.push(c.link.clone());
                let f = seen_file();
                if let Some(d) = f.parent() {
                    let _ = std::fs::create_dir_all(d);
                }
                let _ = std::fs::write(&f, serde_json::to_vec(&seen).unwrap_or_default());
            } else {
                said.push("No better phone model this week.".into());
            }
        }
    }
    save_weekly(worth, &said);
    for s in &said {
        println!("{s}");
    }
    0
}

// ---------- Once a day, from the background part ----------

/// The tidy runs at most this often.
const TIDY_EVERY_SECS: u64 = 24 * 3600;

/// How long after the service starts the first tidy waits, so the start-up
/// work comes first.
const TIDY_FIRST_SECS: u64 = 120;

fn tidy_stamp() -> PathBuf {
    crate::paths::data_dir().join("tidy-last")
}

/// Whether a tidy is due: none ever ran, or the last one is a day old.
pub fn tidy_due(last: Option<u64>, now: u64) -> bool {
    last.is_none_or(|l| now.saturating_sub(l) >= TIDY_EVERY_SECS)
}

/// Runs `work` and writes the time to `stamp`, when a tidy is due by that
/// stamp. Gives whether it ran.
pub fn tidy_if_due(stamp: &Path, now: u64, work: impl FnOnce()) -> bool {
    let last = std::fs::read_to_string(stamp)
        .ok()
        .and_then(|t| t.trim().parse().ok());
    if !tidy_due(last, now) {
        return false;
    }
    work();
    if let Some(d) = stamp.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    let _ = std::fs::write(stamp, now.to_string());
    true
}

/// Lowers this thread's processor and disk priority, so the tidy never
/// gets in the way of the owner's work (the programs it starts inherit it).
fn lower_priority() {
    // SAFETY: plain system calls on this thread; ioprio class 3 is "idle".
    unsafe {
        let tid = libc::syscall(libc::SYS_gettid) as libc::id_t;
        libc::setpriority(libc::PRIO_PROCESS, tid, 19);
        libc::syscall(libc::SYS_ioprio_set, 1, 0, 3 << 13);
    }
}

/// The background part's daily tidy (the weekly timer is gone): old models
/// and check leftovers go, and the look for a better phone model runs
/// ([`weekly_main`]), once when the service starts if a day has passed, then
/// again every 24 hours while it runs, at low priority. A debug build only
/// does it when `MIMIR_TEST_TIDY` is set.
pub fn start_daily_tidy() {
    if cfg!(debug_assertions) && std::env::var_os("MIMIR_TEST_TIDY").is_none() {
        return;
    }
    let _ = std::thread::Builder::new()
        .name("daily-tidy".into())
        .spawn(|| {
            lower_priority();
            std::thread::sleep(std::time::Duration::from_secs(TIDY_FIRST_SECS));
            loop {
                tidy_if_due(&tidy_stamp(), now_secs(), || {
                    weekly_main();
                });
                // Until a day after the last one (a restart doesn't repeat it).
                let last = std::fs::read_to_string(tidy_stamp())
                    .ok()
                    .and_then(|t| t.trim().parse::<u64>().ok())
                    .unwrap_or(0);
                let wait = (last + TIDY_EVERY_SECS)
                    .saturating_sub(now_secs())
                    .clamp(60, TIDY_EVERY_SECS);
                std::thread::sleep(std::time::Duration::from_secs(wait));
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_tidy_runs_at_most_once_a_day() {
        assert!(tidy_due(None, 1000));
        assert!(!tidy_due(Some(1000), 1000 + TIDY_EVERY_SECS - 1));
        assert!(tidy_due(Some(1000), 1000 + TIDY_EVERY_SECS));
        let d = PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(format!(
            ".cache/mimir-checks/tidy-test-{}",
            std::process::id()
        ));
        let stamp = d.join("tidy-last");
        let mut runs = 0;
        assert!(tidy_if_due(&stamp, 5000, || runs += 1));
        assert!(!tidy_if_due(&stamp, 5000 + 3600, || runs += 1));
        assert!(tidy_if_due(&stamp, 5000 + TIDY_EVERY_SECS, || runs += 1));
        assert_eq!(runs, 2);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn the_built_in_list_is_valid_and_the_default_comes_first() {
        let v: Value = serde_json::from_str(STANDALONE).unwrap();
        assert!(valid_standalone(&v));
        assert_eq!(v["models"][0]["id"], "qwen35-4b-sompoa-heresy2-q4km");
    }

    #[test]
    fn the_list_says_uncensored_plainly_and_big_ones_ask_for_memory() {
        let v: Value = serde_json::from_str(STANDALONE).unwrap();
        let ids: Vec<&str> = v["models"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| m["id"].as_str().unwrap())
            .collect();
        assert_eq!(
            ids,
            [
                "qwen35-4b-sompoa-heresy2-q4km",
                "qwen35-9b-huihui-abliterated-q4km",
                "qwen35-9b-heretic2-q4km",
                "gemma4-e2b-heretic-q4km",
                "gemma4-e4b-ultra-heretic-q4km"
            ]
        );
        for m in v["models"].as_array().unwrap() {
            assert_eq!(m["refusals_removed"], true);
            assert!(m["source"].is_string());
        }
        assert_eq!(v["models"][1]["min_ram_gb"], 10);
        assert_eq!(v["models"][2]["min_ram_gb"], 10);
        assert!(v["models"][0]["min_ram_gb"].is_null());
        // A list from before min_ram_gb still passes.
        let old = json!({"models": [{"id": "x", "name": "X", "url": "https://a", "bytes": 5, "sha256": "a".repeat(64)}]});
        assert!(valid_standalone(&old));
    }

    #[test]
    fn a_broken_list_is_not_used() {
        let bad = json!({"models": [{"id": "x", "name": "X", "url": "http://plain", "bytes": 5, "sha256": "ab"}]});
        assert!(!valid_standalone(&bad));
        let twice = json!({"models": [
            {"id": "x", "name": "X", "url": "https://a", "bytes": 5, "sha256": "a".repeat(64)},
            {"id": "x", "name": "Y", "url": "https://b", "bytes": 5, "sha256": "b".repeat(64)}]});
        assert!(!valid_standalone(&twice));
    }

    #[test]
    fn a_free_model_goes_after_thirty_unused_days_and_new_ones_start_their_clock() {
        let now = 100 * DAY;
        let mut used = HashMap::from([
            ("old:latest".to_string(), now - 31 * DAY),
            ("recent:latest".to_string(), now - 3 * DAY),
            ("uninstalled:latest".to_string(), 0),
        ]);
        let installed = vec![
            "old:latest".into(),
            "recent:latest".into(),
            "new:latest".into(),
        ];
        let stale = stale_free_models(&installed, &mut used, now, 30, &[]);
        assert_eq!(stale, vec!["old:latest".to_string()]);
        assert_eq!(used.get("new:latest"), Some(&now));
        assert!(!used.contains_key("uninstalled:latest"));
    }

    #[test]
    fn a_kept_model_is_never_stale_and_the_weekly_tidy_removes_nothing() {
        let now = 100 * DAY;
        let mut used = HashMap::from([
            ("chat:latest".to_string(), 0),
            ("other:latest".to_string(), 0),
        ]);
        let installed = vec!["chat:latest".to_string(), "other:latest".to_string()];
        let stale = stale_free_models(&installed, &mut used, now, 30, &["chat:latest".into()]);
        assert_eq!(stale, vec!["other:latest".to_string()]);
        assert!(tidy_free_models().is_empty());
    }

    #[test]
    fn check_leftovers_untouched_for_two_weeks_are_found() {
        let d = std::env::temp_dir().join(format!("kc-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(d.join("old/inner")).unwrap();
        std::fs::create_dir_all(d.join("fresh")).unwrap();
        std::fs::write(d.join("old/inner/f"), b"x").unwrap();
        std::fs::write(d.join("fresh/f"), b"x").unwrap();
        let later = SystemTime::now() + Duration::from_secs(15 * DAY);
        // Fifteen days on, both are stale; today neither is.
        assert_eq!(stale_entries(&d, later, 14).len(), 2);
        assert!(stale_entries(&d, SystemTime::now(), 14).is_empty());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn csv_quotes_and_commas_are_read() {
        let rows = parse_csv("\u{feff}a,\"b, c\",\"say \"\"hi\"\"\"\r\n1,2,3\n");
        assert_eq!(rows[0], vec!["a", "b, c", "say \"hi\""]);
        assert_eq!(rows[1], vec!["1", "2", "3"]);
    }

    #[test]
    fn only_small_models_well_above_the_phones_best_are_suggested_once() {
        let csv = "author/model_name,Model Link,UGI 🏆,Total Parameters\n\
            Big/Model,https://huggingface.co/Big/Model,60,70\n\
            Small/Better (thinking),https://huggingface.co/Small/Better,50.2,4\n\
            Small/Better (no thinking),https://huggingface.co/Small/Better,40,4\n\
            Small/Close,https://huggingface.co/Small/Close,45.5,3\n\
            MuXodious/Qwen3.5-4B-SOMPOA-heresy-v2,https://huggingface.co/MuXodious/Qwen3.5-4B-SOMPOA-heresy-v2,44.7,4\n";
        let offered: Value = serde_json::from_str(STANDALONE).unwrap();
        let found = better_candidates(csv, &offered, &[], 9.0, 2.0);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].link, "https://huggingface.co/Small/Better");
        assert_eq!(found[0].ugi, 50.2);
        let again = better_candidates(
            csv,
            &offered,
            &["https://huggingface.co/Small/Better".into()],
            9.0,
            2.0,
        );
        assert!(again.is_empty());
    }
}
