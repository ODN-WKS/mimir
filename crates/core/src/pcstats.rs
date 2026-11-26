//! "This PC right now": processor, memory and graphics load. One reading,
//! used by the window's This PC card and sent to the phone in the truth.

use std::path::Path;
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
    /// Processor temperature, whole degrees Celsius (the average of the
    /// core sensors), when a sensor can be read.
    pub cpu_temp_c: Option<i32>,
    /// Graphics chip temperature, whole degrees Celsius, when it can be read.
    pub gpu_temp_c: Option<i32>,
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
            cpu_temp_c: None,
            gpu_temp_c: None,
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
        self.cpu_temp_c = cpu_temp_in(Path::new(HWMON));
        self.gpu_temp_c = gpu_temp_in(Path::new(HWMON));
    }

    /// The reading as the truth carries it; None before memory was read.
    pub fn facts(&self) -> Option<mimir_proto::TruthStats> {
        (self.mem_total > 0).then(|| mimir_proto::TruthStats {
            cpu: self.cpu.clamp(0.0, 100.0),
            mem_used: self.mem_used,
            mem_total: self.mem_total,
            gpu: self.gpu.map(|g| g.clamp(0.0, 100.0)),
            threads: (self.threads > 0).then_some(self.threads),
            cpu_temp_c: self.cpu_temp_c,
            gpu_temp_c: self.gpu_temp_c,
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

/// Where Linux lists its sensor chips (readable inside the Flatpak too).
const HWMON: &str = "/sys/class/hwmon";

/// One temperature sensor of a chip: its label (empty when it has none) and
/// its reading in millidegrees.
fn sensors(chip: &Path) -> Vec<(String, i64)> {
    let Ok(rd) = std::fs::read_dir(chip) else {
        return vec![];
    };
    let mut out = vec![];
    for e in rd.flatten() {
        let f = e.file_name();
        let f = f.to_string_lossy();
        let Some(stem) = f
            .strip_prefix("temp")
            .and_then(|r| r.strip_suffix("_input"))
        else {
            continue;
        };
        let Some(v) = std::fs::read_to_string(e.path())
            .ok()
            .and_then(|s| s.trim().parse::<i64>().ok())
        else {
            continue;
        };
        // A zero or below is a sensor that isn't there, never a reading.
        if v <= 0 {
            continue;
        }
        let label = std::fs::read_to_string(chip.join(format!("temp{stem}_label")))
            .map(|s| s.trim().to_string())
            .unwrap_or_default();
        out.push((label, v));
    }
    out
}

/// The chips with one of these names (found by their `name` file, never by
/// number: the numbers change between boots).
fn chips(root: &Path, names: &[&str]) -> Vec<std::path::PathBuf> {
    let Ok(rd) = std::fs::read_dir(root) else {
        return vec![];
    };
    let mut out: Vec<_> = rd
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            std::fs::read_to_string(p.join("name")).is_ok_and(|n| names.contains(&n.trim()))
        })
        .collect();
    out.sort();
    out
}

/// Millidegrees to whole degrees.
fn whole(milli: f64) -> i32 {
    (milli / 1000.0).round() as i32
}

/// The processor's temperature: the average over Intel's core sensors
/// (`coretemp` "Core N"; the package sensor when there are no cores), or
/// AMD's Tdie/Tctl (`k10temp`, `zenpower`).
pub fn cpu_temp_in(root: &Path) -> Option<i32> {
    let intel: Vec<_> = chips(root, &["coretemp"])
        .iter()
        .flat_map(|c| sensors(c))
        .collect();
    let cores: Vec<i64> = intel
        .iter()
        .filter(|(l, _)| l.starts_with("Core "))
        .map(|(_, v)| *v)
        .collect();
    if !cores.is_empty() {
        return Some(whole(cores.iter().sum::<i64>() as f64 / cores.len() as f64));
    }
    if let Some((_, v)) = intel.iter().find(|(l, _)| l.starts_with("Package")) {
        return Some(whole(*v as f64));
    }
    for chip in chips(root, &["k10temp", "zenpower"]) {
        let s = sensors(&chip);
        let pick = ["Tdie", "Tctl"]
            .iter()
            .find_map(|want| s.iter().find(|(l, _)| l == want))
            .or_else(|| s.first());
        if let Some((_, v)) = pick {
            return Some(whole(*v as f64));
        }
    }
    None
}

/// The graphics chip's own temperature (`amdgpu`'s "edge" sensor; the
/// hottest when there are two graphics chips).
pub fn gpu_temp_in(root: &Path) -> Option<i32> {
    chips(root, &["amdgpu"])
        .iter()
        .flat_map(|c| sensors(c))
        .filter(|(l, _)| l == "edge")
        .map(|(_, v)| v)
        .max()
        .map(|v| whole(v as f64))
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

    /// A fresh fake `/sys/class/hwmon` in the temp folder.
    fn fake(tag: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("mimir-hwmon-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn chip(root: &Path, n: u32, name: &str, temps: &[(u32, &str, i64)]) {
        let c = root.join(format!("hwmon{n}"));
        std::fs::create_dir_all(&c).unwrap();
        std::fs::write(c.join("name"), format!("{name}\n")).unwrap();
        for (i, label, v) in temps {
            std::fs::write(c.join(format!("temp{i}_input")), format!("{v}\n")).unwrap();
            if !label.is_empty() {
                std::fs::write(c.join(format!("temp{i}_label")), format!("{label}\n")).unwrap();
            }
        }
    }

    #[test]
    fn four_cores_give_their_average_rounded() {
        let d = fake("cores");
        chip(&d, 1, "nvme", &[(1, "Composite", 90_000)]);
        chip(
            &d,
            5,
            "coretemp",
            &[
                (1, "Package id 0", 80_000),
                (2, "Core 0", 60_000),
                (6, "Core 4", 61_000),
                (10, "Core 8", 61_000),
                (14, "Core 12", 62_000),
            ],
        );
        // 244 / 4 = 61.0; the package sensor and the disk are left out.
        assert_eq!(cpu_temp_in(&d), Some(61));
        std::fs::write(d.join("hwmon5/temp14_input"), "63000\n").unwrap();
        // 245 / 4 = 61.25, whole degrees.
        assert_eq!(cpu_temp_in(&d), Some(61));
        std::fs::write(d.join("hwmon5/temp14_input"), "64000\n").unwrap();
        // 246 / 4 = 61.5 rounds up.
        assert_eq!(cpu_temp_in(&d), Some(62));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn no_core_labels_falls_back_to_the_package() {
        let d = fake("package");
        chip(&d, 0, "coretemp", &[(1, "Package id 0", 47_400)]);
        assert_eq!(cpu_temp_in(&d), Some(47));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn amd_processors_use_tdie_or_tctl() {
        let d = fake("k10");
        chip(
            &d,
            2,
            "k10temp",
            &[(1, "Tctl", 70_000), (2, "Tdie", 60_000)],
        );
        assert_eq!(cpu_temp_in(&d), Some(60));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_missing_folder_gives_none() {
        let d = std::env::temp_dir().join("mimir-hwmon-not-there-at-all");
        assert_eq!(cpu_temp_in(&d), None);
        assert_eq!(gpu_temp_in(&d), None);
    }

    #[test]
    fn no_known_chip_or_a_zero_reading_gives_none() {
        let d = fake("none");
        chip(&d, 0, "acpitz", &[(1, "", 27_800)]);
        chip(&d, 1, "coretemp", &[(2, "Core 0", 0)]);
        chip(&d, 7, "amdgpu", &[(2, "junction", 50_000)]);
        assert_eq!(cpu_temp_in(&d), None);
        assert_eq!(gpu_temp_in(&d), None);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn amdgpu_gives_its_edge_value() {
        let d = fake("amdgpu");
        chip(
            &d,
            7,
            "amdgpu",
            &[
                (1, "edge", 54_000),
                (2, "junction", 70_000),
                (3, "mem", 80_000),
            ],
        );
        assert_eq!(gpu_temp_in(&d), Some(54));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn this_pc_reading_prints_next_to_the_raw_files() {
        // For the report: what the new code reads on the machine running the tests.
        println!(
            "this PC: processor {:?} C, graphics {:?} C",
            cpu_temp_in(Path::new(HWMON)),
            gpu_temp_in(Path::new(HWMON))
        );
    }
}
