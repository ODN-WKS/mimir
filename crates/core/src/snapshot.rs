//! Lazy project snapshot: taken only when Claude requests project context,
//! not at message send time (PCR-04). Git status runs only on file-watcher
//! events, not on a timer (PCR-05).

use std::path::{Path, PathBuf};

/// A snapshot of a project (directory listing + git status).
#[derive(Debug, Clone, Default)]
pub struct ProjectSnapshot {
    pub git_status: String,
    pub taken: bool,
}

/// Lazy snapshot holder: the snapshot is taken only when first requested.
pub struct LazySnapshot {
    project: PathBuf,
    inner: Option<ProjectSnapshot>,
}

impl LazySnapshot {
    pub fn new(project: impl Into<PathBuf>) -> Self {
        LazySnapshot {
            project: project.into(),
            inner: None,
        }
    }

    /// Whether the snapshot has been taken yet.
    pub fn is_taken(&self) -> bool {
        self.inner.is_some()
    }

    /// Take the snapshot now (called only when Claude requests project context).
    pub fn take(&mut self) -> &ProjectSnapshot {
        if self.inner.is_none() {
            self.inner = Some(ProjectSnapshot {
                git_status: run_git_status(&self.project),
                taken: true,
            });
        }
        self.inner.as_ref().unwrap()
    }
}

/// Run `git status` once for a project directory.
pub fn run_git_status(project: &Path) -> String {
    crate::flatpak::host_cmd("git")
        .arg("-C")
        .arg(project)
        .args(["status", "--porcelain=v1"])
        .output()
        .ok()
        .and_then(|o| {
            o.status
                .success()
                .then(|| String::from_utf8_lossy(&o.stdout).to_string())
        })
        .unwrap_or_default()
}

/// A git-status tracker that only runs when a watcher event fires (PCR-05).
pub struct EventDrivenGitStatus {
    project: PathBuf,
    /// How many times git status has been called.
    pub call_count: usize,
}

impl EventDrivenGitStatus {
    pub fn new(project: impl Into<PathBuf>) -> Self {
        EventDrivenGitStatus {
            project: project.into(),
            call_count: 0,
        }
    }

    /// Call when the file watcher fires a change event. Runs git status once.
    pub fn on_event(&mut self) -> String {
        self.call_count += 1;
        run_git_status(&self.project)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// PCR-04: snapshot is not taken until Claude requests project context.
    #[test]
    fn snapshot_is_lazy() {
        let mut snap = LazySnapshot::new("/nonexistent-project-path");
        // Before any request, snapshot is not taken.
        assert!(
            !snap.is_taken(),
            "snapshot must not be taken before Claude requests context"
        );
        // Taking it now marks it as taken.
        let _ = snap.take();
        assert!(snap.is_taken(), "snapshot must be taken after request");
    }

    /// PCR-05: git status is called exactly N times for N watcher events.
    #[test]
    fn git_status_on_event_only() {
        let mut tracker = EventDrivenGitStatus::new("/nonexistent-project-path");
        assert_eq!(tracker.call_count, 0, "no calls before any event");
        // Fire 3 events.
        for _ in 0..3 {
            tracker.on_event();
        }
        assert_eq!(
            tracker.call_count, 3,
            "git status called exactly N times for N events"
        );
    }
}
