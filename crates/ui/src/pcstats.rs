//! "This PC right now": the window's reading (the same meter the phone gets)
//! plus whether the local model is in memory.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

pub struct PcStats {
    meter: mimir_core::pcstats::Meter,
    /// The local model is in memory (looked at every few seconds, off the
    /// window's thread).
    loaded: Arc<AtomicBool>,
}

impl std::ops::Deref for PcStats {
    type Target = mimir_core::pcstats::Meter;
    fn deref(&self) -> &Self::Target {
        &self.meter
    }
}

impl PcStats {
    pub fn new() -> Self {
        let loaded = Arc::new(AtomicBool::new(false));
        let flag = Arc::downgrade(&loaded);
        std::thread::spawn(move || {
            // Ends with the window (the flag is gone then).
            while let Some(f) = flag.upgrade() {
                let any = !mimir_core::ollama::loaded(&mimir_core::models::ollama_url()).is_empty();
                f.store(any, Ordering::Relaxed);
                drop(f);
                std::thread::sleep(Duration::from_secs(10));
            }
        });
        PcStats {
            loaded,
            meter: mimir_core::pcstats::Meter::new(),
        }
    }

    /// Whether the local model is loaded and ready.
    pub fn model_loaded(&self) -> bool {
        self.loaded.load(Ordering::Relaxed)
    }

    /// Refreshes at most once a second.
    pub fn refresh(&mut self) {
        self.meter.refresh();
    }
}
