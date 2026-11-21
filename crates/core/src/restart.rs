//! "Restart Mímir to finish" after an update: the old background part stops
//! cleanly (after a running answer or task), the new one starts, then the
//! window opens. The order lives in [`restart`]; [`Host`] does it on this PC.

use std::process::{Command, Stdio};
use std::time::Duration;

/// What a restart needs, so the order can be checked without a real service.
pub trait Steps {
    /// Tasks working right now.
    fn working(&mut self) -> u32;
    /// Tells the person something (shown in the window).
    fn say(&mut self, words: &str);
    fn pause(&mut self);
    /// Stops the old background part and waits until it is gone.
    fn stop_service(&mut self) -> Result<(), String>;
    /// Starts the new one and waits until it answers.
    fn start_service(&mut self) -> Result<(), String>;
    fn open_window(&mut self) -> Result<(), String>;
}

pub const WAITING: &str = "Mímir restarts after the current answer.";

/// Waits for running work (up to `patience` checks), then stops the old
/// service, starts the new one, and only then opens the window.
pub fn restart(steps: &mut dyn Steps, patience: u32) -> Result<(), String> {
    let mut said = false;
    for _ in 0..patience {
        if steps.working() == 0 {
            break;
        }
        if !said {
            steps.say(WAITING);
            said = true;
        }
        steps.pause();
    }
    if steps.working() > 0 {
        return Err("Mímir is still busy. Restart it when the work is done.".into());
    }
    steps.stop_service()?;
    steps.start_service()?;
    steps.open_window()
}

/// This PC, for real. Inside a Flatpak the commands run on the PC through
/// the host helper, as new sandboxes with the updated files.
pub struct Host {
    pub say: Box<dyn FnMut(&str) + Send>,
}

/// Work that must finish first: tasks working, plus a chat answer being
/// written (it counts as one more).
fn busy(truth: &serde_json::Value) -> u32 {
    let tasks = truth["counts"]["working"].as_u64().unwrap_or(0) as u32;
    let answer = truth["engine"]["answering"].as_bool().unwrap_or(false) as u32;
    tasks + answer
}

fn host_status() -> Option<serde_json::Value> {
    let exe = std::env::current_exe().ok()?;
    let out = Command::new(exe)
        .args(["status", "--json"])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()
        .filter(|o| o.status.success())?;
    serde_json::from_slice(&out.stdout).ok()
}

/// The background part's process number on the PC: Mímir's own Flatpak
/// copy inside a Flatpak, otherwise the one in the runtime folder.
fn service_pid() -> Option<u32> {
    if let Some(list) = crate::flatpak::host_processes() {
        return list
            .into_iter()
            .find(|(_, a)| {
                a.first()
                    .is_some_and(|p| p == "mimir" || p.ends_with("/app/bin/mimir"))
                    && a.get(1).is_some_and(|s| s == "service")
            })
            .map(|(p, _)| p);
    }
    std::fs::read_to_string(crate::sockets::pid_path())
        .ok()?
        .trim()
        .parse()
        .ok()
}

fn alive(pid: u32) -> bool {
    crate::flatpak::host_cmd("kill")
        .args(["-0", &pid.to_string()])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|s| s.success())
}

fn detached(program: &str, args: &[&str]) -> Result<(), String> {
    let mut c = crate::flatpak::host_cmd("setsid");
    c.arg("-f").arg(program).args(args);
    c.stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    c.status()
        .ok()
        .filter(|s| s.success())
        .map(|_| ())
        .ok_or_else(|| "Mímir could not be started again.".to_string())
}

impl Steps for Host {
    fn working(&mut self) -> u32 {
        host_status().map(|t| busy(&t)).unwrap_or(0)
    }
    fn say(&mut self, words: &str) {
        (self.say)(words);
    }
    fn pause(&mut self) {
        std::thread::sleep(Duration::from_secs(3));
    }
    fn stop_service(&mut self) -> Result<(), String> {
        let Some(pid) = service_pid() else {
            return Ok(());
        };
        let _ = crate::flatpak::host_cmd("kill")
            .arg(pid.to_string())
            .status();
        for _ in 0..60 {
            if !alive(pid) {
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(500));
        }
        Err("Mímir's background part did not stop.".into())
    }
    fn start_service(&mut self) -> Result<(), String> {
        if crate::flatpak::active() {
            let id = crate::flatpak::app_id();
            detached("flatpak", &["run", "--command=mimir", &id, "service"])?;
        } else {
            let exe = std::env::current_exe().map_err(|e| e.to_string())?;
            crate::client::start_service(&exe).map_err(|_| "Mímir could not be started again.")?;
        }
        for _ in 0..40 {
            if host_status().is_some() {
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(500));
        }
        Err("Mímir's background part did not start.".into())
    }
    fn open_window(&mut self) -> Result<(), String> {
        if crate::flatpak::active() {
            let id = crate::flatpak::app_id();
            detached("flatpak", &["run", &id])
        } else {
            let exe = std::env::current_exe().map_err(|e| e.to_string())?;
            Command::new(exe)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .map(|_| ())
                .map_err(|_| "Mímir could not be started again.".into())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fake {
        log: Vec<String>,
        busy: Vec<u32>,
        fail_start: bool,
    }
    impl Steps for Fake {
        fn working(&mut self) -> u32 {
            let n = if self.busy.is_empty() {
                0
            } else {
                self.busy.remove(0)
            };
            self.log.push(format!("working {n}"));
            n
        }
        fn say(&mut self, w: &str) {
            self.log.push(format!("say {w}"));
        }
        fn pause(&mut self) {
            self.log.push("pause".into());
        }
        fn stop_service(&mut self) -> Result<(), String> {
            self.log.push("stop".into());
            Ok(())
        }
        fn start_service(&mut self) -> Result<(), String> {
            self.log.push("start".into());
            if self.fail_start {
                Err("no".into())
            } else {
                Ok(())
            }
        }
        fn open_window(&mut self) -> Result<(), String> {
            self.log.push("window".into());
            Ok(())
        }
    }

    #[test]
    fn a_chat_answer_being_written_counts_as_work() {
        let idle = serde_json::json!({"counts": {"working": 0}, "engine": {"answering": false}});
        assert_eq!(busy(&idle), 0);
        let writing = serde_json::json!({"counts": {"working": 0}, "engine": {"answering": true}});
        assert_eq!(busy(&writing), 1);
        let both = serde_json::json!({"counts": {"working": 2}, "engine": {"answering": true}});
        assert_eq!(busy(&both), 3);
    }

    fn steps(log: &[String]) -> Vec<&str> {
        log.iter()
            .map(String::as_str)
            .filter(|l| !l.starts_with("working"))
            .collect()
    }

    #[test]
    fn idle_restart_goes_stop_start_window() {
        let mut f = Fake {
            log: vec![],
            busy: vec![],
            fail_start: false,
        };
        restart(&mut f, 5).unwrap();
        assert_eq!(steps(&f.log), ["stop", "start", "window"]);
    }

    #[test]
    fn running_work_finishes_first_and_is_announced_once() {
        let mut f = Fake {
            log: vec![],
            busy: vec![2, 1, 1, 0, 0],
            fail_start: false,
        };
        restart(&mut f, 10).unwrap();
        assert_eq!(
            steps(&f.log),
            [
                &format!("say {WAITING}"),
                "pause",
                "pause",
                "pause",
                "stop",
                "start",
                "window"
            ]
        );
    }

    #[test]
    fn never_stops_while_work_is_running() {
        let mut f = Fake {
            log: vec![],
            busy: vec![1; 20],
            fail_start: false,
        };
        assert!(restart(&mut f, 3).is_err());
        assert!(!f.log.iter().any(|l| l == "stop" || l == "window"));
    }

    #[test]
    fn the_window_waits_for_the_new_service() {
        let mut f = Fake {
            log: vec![],
            busy: vec![],
            fail_start: true,
        };
        assert!(restart(&mut f, 3).is_err());
        assert_eq!(steps(&f.log), ["stop", "start"]);
    }
}
