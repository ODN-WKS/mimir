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

fn memory() -> &'static Mutex<HashMap<String, bool>> {
    static M: OnceLock<Mutex<HashMap<String, bool>>> = OnceLock::new();
    M.get_or_init(|| Mutex::new(load_from(&file())))
}

/// The small file (none while testing, so a check never writes to the
/// real settings folder).
fn file() -> std::path::PathBuf {
    if cfg!(test) {
        return std::path::PathBuf::new();
    }
    crate::paths::config_dir().join("sight.json")
}

fn load_from(path: &std::path::Path) -> HashMap<String, bool> {
    std::fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn save_to(path: &std::path::Path, map: &HashMap<String, bool>) {
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

fn key(base: &str, model: &str) -> String {
    format!(
        "{model}@{}",
        crate::ollama::version(base).unwrap_or_default()
    )
}

fn remember(base: &str, model: &str, sees: bool) {
    let mut m = memory().lock().unwrap_or_else(|e| e.into_inner());
    m.insert(key(base, model), sees);
    save_to(&file(), &m);
}

fn known(base: &str, model: &str) -> Option<bool> {
    memory()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&key(base, model))
        .copied()
}

/// A picture answer failed on `model`: it can't see here.
pub fn mark_blind(base: &str, model: &str) {
    remember(base, model, false);
}

/// A picture answer worked on `model`.
pub fn mark_sees(base: &str, model: &str) {
    if known(base, model) != Some(true) {
        remember(base, model, true);
    }
}

/// Whether `model` says it sees and has not been found blind here.
fn claims(base: &str, model: &str) -> bool {
    known(base, model) != Some(false) && crate::ollama::sees_pictures(base, model)
}

/// Whether `model` can really look at pictures: it says so, and (once) a
/// tiny real picture got an answer.
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
    let ok = crate::ollama::post_stream_at(base, "/api/chat", &body, Duration::from_secs(120))
        .ok()
        .and_then(|mut s| s.next_json().ok().flatten())
        .is_some();
    remember(base, model, ok);
    ok
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

/// The usual model first, then the other installed ones.
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
        if first.is_none() {
            if can_see(base, m) {
                first = Some(m.to_string());
            }
        } else if claims(base, m) {
            next.push(m.to_string());
        }
    }
    Choice { model: first, next }
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
        let c = choose(
            &b,
            Some("sb-usual"),
            &["sb-usual".into(), "sb-other".into()],
        );
        assert_eq!(c.model.as_deref(), Some("sb-other"));
        assert!(c.next.is_empty());
        assert_eq!(known(&b, "sb-usual"), Some(false));
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
        m.insert("x@1".to_string(), false);
        save_to(&f, &m);
        assert_eq!(load_from(&f), m);
        let _ = std::fs::remove_dir_all(dir);
    }
}
