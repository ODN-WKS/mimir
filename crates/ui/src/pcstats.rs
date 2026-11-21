//! "This PC right now": processor, memory and graphics load.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use sysinfo::System;

pub struct PcStats {
    sys: Option<System>,
    last: Option<Instant>,
    pub cpu: f32,
    pub mem_used: u64,
    pub mem_total: u64,
    /// AMD graphics load in percent, when the driver reports it.
    pub gpu: Option<f32>,
    /// The local model is in memory (looked at every few seconds, off the
    /// window's thread).
    loaded: Arc<AtomicBool>,
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
            sys: System::new().ok(),
            last: None,
            cpu: 0.0,
            mem_used: 0,
            mem_total: 0,
            gpu: None,
        }
    }

    /// Whether the local model is loaded and ready.
    pub fn model_loaded(&self) -> bool {
        self.loaded.load(Ordering::Relaxed)
    }

    /// Refreshes at most once a second.
    pub fn refresh(&mut self) {
        if self
            .last
            .is_some_and(|t| t.elapsed() < Duration::from_secs(1))
        {
            return;
        }
        self.last = Some(Instant::now());
        if let Some(s) = self.sys.as_mut() {
            s.refresh_cpu_usage();
            s.refresh_memory();
            self.cpu = s.global_cpu_usage();
            self.mem_used = s.total_memory().saturating_sub(s.available_memory());
            self.mem_total = s.total_memory();
        }
        self.gpu = gpu_busy();
    }
}

fn gpu_busy() -> Option<f32> {
    let rd = std::fs::read_dir("/sys/class/drm").ok()?;
    let mut best: Option<f32> = None;
    for e in rd.flatten() {
        let name = e.file_name();
        let name = name.to_string_lossy();
        if !name.starts_with("card") || name.contains('-') {
            continue;
        }
        if let Ok(s) = std::fs::read_to_string(e.path().join("device/gpu_busy_percent"))
            && let Ok(v) = s.trim().parse::<f32>()
        {
            best = Some(best.map_or(v, |b: f32| b.max(v)));
        }
    }
    best
}
