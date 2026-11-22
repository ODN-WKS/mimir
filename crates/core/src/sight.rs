//! Which PC model can really look at pictures. A model that says it can
//! (Ollama lists "vision") gets one tiny real picture the first time it is
//! needed; the result is kept per model name and Ollama version, in memory
//! and in a small file in the settings folder. A model that fails on a real
//! picture is marked "can't see here" and not asked again.

use serde_json::json;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

/// A 28 x 28 picture of a red square on white.
const TEST_PICTURE: &str = "iVBORw0KGgoAAAANSUhEUgAAABwAAAAcCAIAAAD9b0jDAAAAK0lEQVR42mP4TwPAMGroqKFo4IGCApFo1NBRQ0cNHTV0IAwdLflHDSUbAACCOboGDagcrgAAAABJRU5ErkJggg==";

/// What is kept about one model at one Ollama version.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
struct Entry {
    sees: bool,
    /// Seconds since 1970 when it was learned.
    at: u64,
}

/// "Can't see" is forgotten after this long (a new picture is tried again).
const BLIND_FOR: u64 = 7 * 24 * 3600;

fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn memory() -> &'static Mutex<HashMap<String, Entry>> {
    static M: OnceLock<Mutex<HashMap<String, Entry>>> = OnceLock::new();
    M.get_or_init(|| Mutex::new(load_from(&file())))
}

/// What a model "says" about pictures, per model and Ollama version (memory
/// only, so a Models request makes at most one lookup per model).
fn claim_cache() -> &'static Mutex<HashMap<String, bool>> {
    static M: OnceLock<Mutex<HashMap<String, bool>>> = OnceLock::new();
    M.get_or_init(Default::default)
}

/// The small file (none while testing, so a check never writes to the
/// real settings folder).
fn file() -> std::path::PathBuf {
    if cfg!(test) {
        return std::path::PathBuf::new();
    }
    crate::paths::config_dir().join("sight.json")
}

fn load_from(path: &std::path::Path) -> HashMap<String, Entry> {
    std::fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn save_to(path: &std::path::Path, map: &HashMap<String, Entry>) {
    if path.as_os_str().is_empty() {
        return;
    }
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let tmp = path.with_extension("tmp");
    if std::fs::write(&tmp, serde_json::to_vec(map).unwrap_or_default()).is_ok() {
        let _ = std::fs::rename(tmp, path);
    }
}

/// Ollama's version, looked up at most every half minute per address; a
/// failed lookup is kept for 10 seconds so a down or hung Ollama isn't asked
/// again for every model.
/// When each address's version was looked up, and what it said.
type VersionCache = Mutex<HashMap<String, (std::time::Instant, Option<String>)>>;

fn version(base: &str) -> Option<String> {
    static V: OnceLock<VersionCache> = OnceLock::new();
    let m = V.get_or_init(Default::default);
    if let Some((at, v)) = m.lock().unwrap_or_else(|e| e.into_inner()).get(base) {
        let ttl = if v.is_some() { 30 } else { 10 };
        if at.elapsed() < Duration::from_secs(ttl) {
            return v.clone();
        }
    }
    let v = crate::ollama::version(base).filter(|v| !v.is_empty());
    m.lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(base.to_string(), (std::time::Instant::now(), v.clone()));
    v
}

/// The key for a model, none while Ollama's version is unknown (nothing is
/// kept then).
fn key(base: &str, model: &str) -> Option<String> {
    Some(format!("{model}@{}", version(base)?))
}

fn remember(base: &str, model: &str, sees: bool) {
    let Some(k) = key(base, model) else {
        return;
    };
    let mut m = memory().lock().unwrap_or_else(|e| e.into_inner());
    m.insert(k, Entry { sees, at: now() });
    save_to(&file(), &m);
}

/// What a kept entry says at time `at_time`: a "can't see" runs out.
fn fresh(e: &Entry, at_time: u64) -> Option<bool> {
    (e.sees || at_time.saturating_sub(e.at) < BLIND_FOR).then_some(e.sees)
}

/// What is known (and still fresh) about `model`.
pub fn known(base: &str, model: &str) -> Option<bool> {
    let k = key(base, model)?;
    memory()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&k)
        .and_then(|e| fresh(e, now()))
}

/// Whether an error text means this model can't look at pictures on this
/// PC (the graphics card or the model's own picture support), not that it
/// was slow, short of memory or still loading.
pub fn is_sight_error(text: &str) -> bool {
    let t = text.to_lowercase();
    if ["timed", "timeout", "deadline", "memory", "oom", "context"]
        .iter()
        .any(|w| t.contains(w))
    {
        return false;
    }
    if ["image", "vision", "multimodal", "mmproj"]
        .iter()
        .any(|w| t.contains(w))
    {
        return true;
    }
    if t.contains("load") {
        return false;
    }
    t.contains("rocm")
        || t.contains("cuda")
        || t.contains("invalid device")
        || t.split(|c: char| !c.is_ascii_alphanumeric())
            .any(|w| w == "hip" || w.starts_with("hiperror") || w.starts_with("hipblas"))
}

/// A picture answer failed on `model` with `why`: when that is a sight
/// error, it can't see here.
pub fn mark_blind(base: &str, model: &str, why: &str) {
    if is_sight_error(why) {
        remember(base, model, false);
    }
}

/// A picture answer worked on `model`.
pub fn mark_sees(base: &str, model: &str) {
    if known(base, model) != Some(true) {
        remember(base, model, true);
    }
}

/// Whether `model` says it sees and has not been found blind here. Asks
/// Ollama once per model and version; the answer is kept in memory.
pub fn claims(base: &str, model: &str) -> bool {
    if known(base, model) == Some(false) {
        return false;
    }
    let Some(k) = key(base, model) else {
        return crate::ollama::sees_pictures(base, model);
    };
    if let Some(c) = claim_cache()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&k)
    {
        return *c;
    }
    let c = crate::ollama::sees_pictures(base, model);
    claim_cache()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(k, c);
    c
}

/// Whether `model` can really look at pictures: it says so, and (once) a
/// tiny real picture got an answer. This waits on the model, so it runs
/// only in the chat worker, never on the service thread.
pub fn can_see(base: &str, model: &str) -> bool {
    if !claims(base, model) {
        return false;
    }
    if let Some(k) = known(base, model) {
        return k;
    }
    let body = json!({
        "model": model,
        "messages": [{
            "role": "user",
            "content": "What colour is the square? One word.",
            "images": [TEST_PICTURE],
        }],
        "stream": false,
        "think": false,
        "keep_alive": "10m",
        "options": {"num_predict": 8},
    });
    let got = crate::ollama::post_stream_at(base, "/api/chat", &body, Duration::from_secs(120))
        .and_then(|mut s| s.next_json())
        .and_then(|v| match v {
            Some(v) => match v["error"].as_str() {
                Some(e) => Err(e.to_string()),
                None => Ok(()),
            },
            None => Err("no answer".into()),
        });
    match got {
        Ok(()) => {
            remember(base, model, true);
            true
        }
        Err(why) => {
            mark_blind(base, model, &why);
            false
        }
    }
}

/// Who answers a message with pictures.
#[derive(Debug, Clone, PartialEq)]
pub struct Choice {
    /// The first PC model that sees (the usual one when it does).
    pub model: Option<String>,
    /// Models to try next when that one fails on a real picture: the other
    /// installed models that say they see.
    pub next: Vec<String>,
}

/// The usual model first, then the other installed ones. Only reads what
/// is kept (and one lookup per model for what it says): a picture that
/// fails on the model is the real test, in the chat worker.
pub fn choose(base: &str, usual: Option<&str>, installed: &[String]) -> Choice {
    let mut order: Vec<&str> = usual.into_iter().collect();
    order.extend(
        installed
            .iter()
            .map(String::as_str)
            .filter(|m| Some(*m) != usual),
    );
    let mut first = None;
    let mut next = vec![];
    for m in order {
        if !claims(base, m) {
            continue;
        }
        if first.is_none() {
            first = Some(m.to_string());
        } else {
            next.push(m.to_string());
        }
    }
    Choice { model: first, next }
}

/// How a message with pictures is answered.
#[derive(Debug, Clone, PartialEq)]
pub struct Plan {
    /// Answered Online from the start (so the first label says Online).
    pub online_first: bool,
    /// The PC model to run first (none when Online goes first).
    pub pc: Option<String>,
    /// PC models to try next.
    pub next: Vec<String>,
    /// Online comes last when the PC models fail.
    pub online_last: bool,
    /// The PC model Online falls back to: a seeing one, never the usual
    /// model when that one can't see. None: plain words.
    pub free_backup: Option<String>,
    /// Nobody can answer.
    pub nobody: bool,
}

/// Who answers: Online when chosen (or when no PC model sees and the
/// person may go online), else the PC models that see.
pub fn plan(c: &Choice, online_allowed: bool, online_chosen: bool) -> Plan {
    let online_first = online_chosen || (c.model.is_none() && online_allowed);
    Plan {
        online_first,
        pc: if online_first { None } else { c.model.clone() },
        next: if online_first { vec![] } else { c.next.clone() },
        online_last: !online_first && online_allowed,
        free_backup: if online_first { c.model.clone() } else { None },
        nobody: !online_first && c.model.is_none(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A fake Ollama where `sees` models claim vision and `works` ones
    /// answer a picture.
    fn fake(sees: &'static [&'static str], works: &'static [&'static str]) -> String {
        crate::ollama::fake::start(move |path, body| {
            let v: serde_json::Value = serde_json::from_str(body).unwrap_or_default();
            let model = v["model"].as_str().unwrap_or("");
            match path {
                "/api/version" => vec![r#"{"version":"9.9.9"}"#.into()],
                "/api/show" if sees.contains(&model) => {
                    vec![r#"{"capabilities":["completion","vision"]}"#.into()]
                }
                "/api/show" => vec![r#"{"capabilities":["completion"]}"#.into()],
                "/api/chat" if works.contains(&model) => {
                    vec![r#"{"message":{"content":"Red"},"done":true}"#.into()]
                }
                _ => vec![r#"{"error":"ROCm error: invalid device function"}"#.into()],
            }
        })
    }

    #[test]
    fn a_failed_version_lookup_is_not_repeated_for_every_model() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        static N: AtomicUsize = AtomicUsize::new(0);
        let b = crate::ollama::fake::start(|path, _| {
            if path == "/api/version" {
                N.fetch_add(1, Ordering::SeqCst);
            }
            vec![r#"{"error":"down"}"#.into()]
        });
        assert_eq!(key(&b, "vf-a"), None);
        assert_eq!(key(&b, "vf-b"), None);
        assert_eq!(key(&b, "vf-c"), None);
        assert_eq!(N.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn the_usual_model_is_kept_when_it_really_sees() {
        let b = fake(&["sa-usual", "sa-other"], &["sa-usual", "sa-other"]);
        let c = choose(
            &b,
            Some("sa-usual"),
            &["sa-other".into(), "sa-usual".into()],
        );
        assert_eq!(c.model.as_deref(), Some("sa-usual"));
        assert_eq!(c.next, vec!["sa-other".to_string()]);
    }

    #[test]
    fn a_model_that_claims_vision_but_fails_is_skipped_and_remembered() {
        let b = fake(&["sb-usual", "sb-other"], &["sb-other"]);
        assert!(!can_see(&b, "sb-usual"));
        assert!(can_see(&b, "sb-other"));
        assert_eq!(known(&b, "sb-usual"), Some(false));
        let c = choose(
            &b,
            Some("sb-usual"),
            &["sb-usual".into(), "sb-other".into()],
        );
        assert_eq!(c.model.as_deref(), Some("sb-other"));
        assert!(c.next.is_empty());
    }

    #[test]
    fn nobody_that_sees_gives_none() {
        let b = fake(&[], &[]);
        let c = choose(&b, Some("sc-usual"), &["sc-usual".into(), "sc-b".into()]);
        assert_eq!(c.model, None);
    }

    #[test]
    fn the_test_picture_is_sent_once_per_model() {
        let hits = std::sync::Arc::new(Mutex::new(0));
        let h = hits.clone();
        let b = crate::ollama::fake::start(move |path, _| match path {
            "/api/version" => vec![r#"{"version":"1.0.0"}"#.into()],
            "/api/show" => vec![r#"{"capabilities":["vision"]}"#.into()],
            _ => {
                *h.lock().unwrap() += 1;
                vec![r#"{"message":{"content":"Red"},"done":true}"#.into()]
            }
        });
        assert!(can_see(&b, "sd-m"));
        assert!(can_see(&b, "sd-m"));
        assert_eq!(*hits.lock().unwrap(), 1);
    }

    #[test]
    fn the_answers_are_kept_in_a_small_file() {
        let dir = std::env::temp_dir().join(format!("mimir-sight-{}", std::process::id()));
        let f = dir.join("sight.json");
        let mut m = HashMap::new();
        m.insert("x@1".to_string(), Entry { sees: false, at: 5 });
        save_to(&f, &m);
        assert_eq!(load_from(&f), m);
        let _ = std::fs::remove_dir_all(dir);
    }

    fn counting(
        claim: &'static [&'static str],
        chat: &'static str,
        version: bool,
    ) -> (String, std::sync::Arc<Mutex<(u32, u32)>>) {
        let hits = std::sync::Arc::new(Mutex::new((0u32, 0u32)));
        let h = hits.clone();
        let b = crate::ollama::fake::start(move |path, body| {
            let v: serde_json::Value = serde_json::from_str(body).unwrap_or_default();
            let model = v["model"].as_str().unwrap_or("");
            match path {
                "/api/version" if version => vec![r#"{"version":"7.7.7"}"#.into()],
                "/api/show" => {
                    h.lock().unwrap().0 += 1;
                    if claim.contains(&model) {
                        vec![r#"{"capabilities":["vision"]}"#.into()]
                    } else {
                        vec![r#"{"capabilities":["completion"]}"#.into()]
                    }
                }
                "/api/chat" => {
                    h.lock().unwrap().1 += 1;
                    vec![chat.into()]
                }
                _ => vec![r#"{"error":"no"}"#.into()],
            }
        });
        (b, hits)
    }

    #[test]
    fn a_known_blind_model_that_claims_vision_does_not_count_and_claims_are_asked_once() {
        let (b, hits) = counting(&["se-a", "se-b"], "{}", true);
        assert!(claims(&b, "se-a"));
        assert!(claims(&b, "se-a"));
        assert!(claims(&b, "se-b"));
        assert_eq!(hits.lock().unwrap().0, 2, "one lookup per model");
        mark_blind(&b, "se-a", "ROCm error: invalid device function");
        assert!(!claims(&b, "se-a"));
        assert!(claims(&b, "se-b"));
    }

    #[test]
    fn choosing_never_sends_a_picture_or_waits_on_a_model() {
        let (b, hits) = counting(&["sf-a", "sf-b"], "{}", true);
        let c = choose(&b, Some("sf-a"), &["sf-a".into(), "sf-b".into()]);
        assert_eq!(c.model.as_deref(), Some("sf-a"));
        assert_eq!(c.next, vec!["sf-b".to_string()]);
        assert_eq!(hits.lock().unwrap().1, 0);
    }

    #[test]
    fn only_graphics_card_and_picture_errors_make_a_model_blind() {
        for yes in [
            "ROCm error: invalid device function",
            "CUDA error: an illegal instruction",
            "HIP error: invalid device ordinal",
            "this model does not support image input",
            "hipErrorInvalidDevice",
        ] {
            assert!(is_sight_error(yes), "{yes}");
        }
        for no in [
            "request timed out",
            "CUDA error: out of memory",
            "ROCm out of memory",
            "failed to load model",
            "the context is too long",
            "connection refused",
            "the ship is slow",
        ] {
            assert!(!is_sight_error(no), "{no}");
        }
    }

    #[test]
    fn a_timeout_or_memory_failure_is_not_remembered_but_a_card_error_is() {
        let (b, _) = counting(
            &["sg-a"],
            "{\"error\":\"model requires more system memory\"}",
            true,
        );
        assert!(!can_see(&b, "sg-a"));
        assert_eq!(known(&b, "sg-a"), None);
        mark_blind(&b, "sg-a", "request timed out");
        assert_eq!(known(&b, "sg-a"), None);
        mark_blind(&b, "sg-a", "ROCm error: invalid device function");
        assert_eq!(known(&b, "sg-a"), Some(false));
    }

    #[test]
    fn a_stored_blind_answer_runs_out_after_seven_days_but_a_seeing_one_stays() {
        let blind = Entry {
            sees: false,
            at: 1000,
        };
        assert_eq!(fresh(&blind, 1000 + BLIND_FOR - 1), Some(false));
        assert_eq!(fresh(&blind, 1000 + BLIND_FOR), None);
        let sees = Entry {
            sees: true,
            at: 1000,
        };
        assert_eq!(fresh(&sees, 1000 + BLIND_FOR * 10), Some(true));
    }

    #[test]
    fn nothing_is_kept_while_ollamas_version_is_unknown() {
        let (b, _) = counting(&["sh-a"], "{}", false);
        mark_blind(&b, "sh-a", "ROCm error: invalid device function");
        mark_sees(&b, "sh-a");
        assert_eq!(known(&b, "sh-a"), None);
        assert!(key(&b, "sh-a").is_none());
    }

    fn choice(model: Option<&str>) -> Choice {
        Choice {
            model: model.map(str::to_string),
            next: vec![],
        }
    }

    #[test]
    fn online_by_pictures_falls_back_to_a_seeing_pc_model_never_the_blind_usual_one() {
        // The usual model is blind and not in the choice; "eyes" sees.
        let p = plan(&choice(Some("eyes")), true, true);
        assert!(p.online_first);
        assert_eq!(p.free_backup.as_deref(), Some("eyes"));
        // No PC model sees: plain words after an Online failure.
        let p = plan(&choice(None), true, false);
        assert!(p.online_first);
        assert_eq!(p.free_backup, None);
        assert_eq!(p.pc, None);
    }

    #[test]
    fn online_from_the_start_is_what_the_label_says_and_a_pc_model_leads_otherwise() {
        let p = plan(&choice(None), true, false);
        assert!(p.online_first && !p.nobody && !p.online_last);
        let p = plan(&choice(Some("eyes")), true, false);
        assert!(!p.online_first && p.online_last);
        assert_eq!(p.pc.as_deref(), Some("eyes"));
        let p = plan(&choice(None), false, false);
        assert!(p.nobody);
    }
}
