//! The store's notes for the developer log: the program that uses the
//! store hands in its logger once ([`set`]); without one, notes are dropped.

use std::sync::OnceLock;

pub type Fields = Vec<(&'static str, serde_json::Value)>;
/// (area, what, fields, error or "").
pub type Logger = fn(&'static str, &'static str, Fields, &str);

static LOGGER: OnceLock<Logger> = OnceLock::new();

/// Sets where notes go (the first call wins).
pub fn set(f: Logger) {
    let _ = LOGGER.set(f);
}

pub(crate) fn note(area: &'static str, what: &'static str, f: Fields) {
    if let Some(l) = LOGGER.get() {
        l(area, what, f, "");
    }
}

pub(crate) fn error(area: &'static str, what: &'static str, f: Fields, err: &str) {
    if let Some(l) = LOGGER.get() {
        l(area, what, f, err);
    }
}
