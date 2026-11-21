//! Every hub message must be handled within [`BUDGET`]. In test and debug
//! builds a slower one panics (so a check finds it); in release builds it
//! is logged as a warning and the hub goes on.

use std::sync::OnceLock;
use std::time::Duration;

/// The most one message may take on the hub's thread.
pub const BUDGET: Duration = Duration::from_millis(50);

/// Where release builds report a slow message (name, time taken).
pub type Warn = fn(&str, Duration);

static WARN: OnceLock<Warn> = OnceLock::new();

/// Sets where slow messages are reported (the first call wins).
pub fn set_warn(f: Warn) {
    let _ = WARN.set(f);
}

/// Called with what a message took: panics (debug) or warns (release)
/// when it took longer than [`BUDGET`].
pub fn check(name: &str, took: Duration) {
    check_with(name, took, cfg!(debug_assertions));
}

/// [`check`] with the build kind named (`strict`: panic).
pub fn check_with(name: &str, took: Duration, strict: bool) -> bool {
    if took <= BUDGET {
        return true;
    }
    if strict {
        panic!(
            "hub watchdog: {name} took {} ms (budget {} ms)",
            took.as_millis(),
            BUDGET.as_millis()
        );
    }
    match WARN.get() {
        Some(w) => w(name, took),
        None => eprintln!(
            "mimir hub: {name} took {} ms (budget {} ms)",
            took.as_millis(),
            BUDGET.as_millis()
        ),
    }
    false
}

/// Times `$body` and hands the time to [`check`] under `$name`.
#[macro_export]
macro_rules! watched {
    ($name:expr, $body:expr) => {{
        let t0 = ::std::time::Instant::now();
        let out = $body;
        $crate::watchdog::check($name, t0.elapsed());
        out
    }};
}
