//! "This PC right now": processor, memory and graphics load. One reading,
//! used by the window's This PC card and sent to the phone in the truth.

use std::time::{Duration, Instant};
use sysinfo::System;

pub struct Meter {
    sys: Option<System>,
    last: Option<Instant>,
    pub cpu: f32,
    pub mem_used: u64,
    pub mem_total: u64,
    /// AMD graphics load in percent, when the driver reports it.
    pub gpu: Option<f32>,
    /// Processor threads (logical processors).
    pub threads: u32,
}

impl Default for Meter {
    fn default() -> Self {
        Self::new()
    }
}

impl Meter {
    pub fn new() -> Self {
        Meter {
            sys: System::new().ok(),
            last: None,
            cpu: 0.0,
            mem_used: 0,
            mem_total: 0,
            gpu: None,
            threads: 0,
        }
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
            self.threads = s.cpus().len() as u32;
        }
        self.gpu = gpu_busy();
    }

    /// The reading as the truth carries it; None before memory was read.
    pub fn facts(&self) -> Option<mimir_proto::TruthStats> {
        (self.mem_total > 0).then(|| mimir_proto::TruthStats {
            cpu: self.cpu.clamp(0.0, 100.0),
            mem_used: self.mem_used,
            mem_total: self.mem_total,
            gpu: self.gpu.map(|g| g.clamp(0.0, 100.0)),
            threads: (self.threads > 0).then_some(self.threads),
        })
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_reading_has_memory_and_stays_within_percent() {
        let mut m = Meter::new();
        m.refresh();
        let f = m.facts().expect("memory is read");
        assert!(f.mem_total > 0 && f.mem_used <= f.mem_total);
        assert!((0.0..=100.0).contains(&f.cpu));
    }
}
