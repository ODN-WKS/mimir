//! Each task's processes in their own control group.
//!
//! systemd gives the service its own group (`Delegate=yes`). The service
//! first moves itself into a leaf, `service`, then starts every Claude Code
//! in a child group: `task-<id>` for a task (and its Phone too session),
//! `keeper-<hash>` for a project's Remote Control. A process started there
//! stays there, so the service can tell a task's process from its window
//! (see `sockets::refuse_peer`) and stop all of a task at once.

use std::cell::RefCell;
use std::ffi::CString;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::OnceLock;

static GROUPS: OnceLock<Groups> = OnceLock::new();

thread_local! {
    /// The group the next process started on this thread goes into.
    static NEXT: RefCell<Option<String>> = const { RefCell::new(None) };
}

#[derive(Debug, Clone)]
pub struct Groups {
    /// The service's own group, as `/proc/<pid>/cgroup` names it.
    pub name: String,
    /// The same, under `/sys/fs/cgroup`.
    pub dir: PathBuf,
}

/// This process's control group (cgroup v2).
fn own_group() -> Option<String> {
    crate::sockets::cgroup_of(std::process::id() as i32)
}

/// Sets up the groups: the service moves into its `service` leaf. Fails
/// when the service has no group of its own to manage (no `Delegate=yes`).
pub fn setup() -> Result<&'static Groups, String> {
    if let Some(g) = GROUPS.get() {
        return Ok(g);
    }
    let name = own_group().ok_or("this system has no control groups (cgroup v2)")?;
    // Started again (a restart in the same scope): the unit's group is
    // the parent of `service`.
    let name = name
        .strip_suffix("/service")
        .map(str::to_string)
        .unwrap_or(name);
    // The group must be one systemd handed over (Delegate=yes): every
    // group of the user's is writable, but only a delegated one is ours.
    let unit = name.rsplit('/').next().unwrap_or_default().to_string();
    if !delegated(&unit) {
        return Err(format!(
            "Mímir's service needs its own process group to keep each task's \
             processes apart, and its unit {unit} doesn't have one. Reinstall the Mímir service, then try again"
        ));
    }
    let dir = Path::new("/sys/fs/cgroup").join(name.trim_start_matches('/'));
    let leaf = dir.join("service");
    std::fs::create_dir_all(&leaf)
        .and_then(|_| std::fs::write(leaf.join("cgroup.procs"), "0"))
        .map_err(|e| {
            format!(
                "Mímir's service needs its own process group to keep each task's \
                 processes apart, and {} can't be used. Reinstall the Mímir service, then try again. ({e})",
                dir.display()
            )
        })?;
    // Groups of tasks that ended last time and are empty now.
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            if n.starts_with("task-") || n.starts_with("keeper-") {
                let _ = std::fs::remove_dir(e.path());
            }
        }
    }
    Ok(GROUPS.get_or_init(|| Groups { name, dir }))
}

/// Whether systemd delegated this unit's group (asks the user's systemd).
fn delegated(unit: &str) -> bool {
    if unit.is_empty() {
        return false;
    }
    let uid = unsafe { libc::getuid() };
    std::process::Command::new("systemctl")
        .args(["--user", "show", "-p", "Delegate", "--value", unit])
        // The user's systemd, whatever runtime folder this service uses.
        .env("XDG_RUNTIME_DIR", format!("/run/user/{uid}"))
        .env(
            "DBUS_SESSION_BUS_ADDRESS",
            format!("unix:path=/run/user/{uid}/bus"),
        )
        .stderr(std::process::Stdio::null())
        .output()
        .is_ok_and(|o| String::from_utf8_lossy(&o.stdout).trim() == "yes")
}

/// The service's group, once set up.
pub fn current() -> Option<&'static Groups> {
    GROUPS.get()
}

/// A group name that is safe as a folder name.
pub fn task_group(task: &str) -> String {
    let safe: String = task
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    format!("task-{safe}")
}

pub fn keeper_group(project: &str) -> String {
    use sha2::Digest;
    let h = sha2::Sha256::digest(project.as_bytes());
    let hex: String = h.iter().take(6).map(|b| format!("{b:02x}")).collect();
    format!("keeper-{hex}")
}

/// Runs `f` with processes it starts on this thread going into `group`.
pub fn with<T>(group: &str, f: impl FnOnce() -> T) -> T {
    NEXT.with(|n| *n.borrow_mut() = Some(group.to_string()));
    let out = f();
    NEXT.with(|n| *n.borrow_mut() = None);
    out
}

/// Makes `cmd`'s process join `group` (or the one `with` set) before it
/// runs. Without groups set up (a debug build tried by hand), nothing.
pub fn place(cmd: &mut Command, group: Option<&str>) {
    let Some(g) = current() else { return };
    let name = group
        .map(str::to_string)
        .or_else(|| NEXT.with(|n| n.borrow().clone()));
    let Some(name) = name else { return };
    let dir = g.dir.join(&name);
    if std::fs::create_dir_all(&dir).is_err() {
        return;
    }
    let Ok(procs) = CString::new(dir.join("cgroup.procs").as_os_str().as_encoded_bytes()) else {
        return;
    };
    // SAFETY: only async-signal-safe calls (open, write, close) between
    // fork and exec.
    unsafe {
        cmd.pre_exec(move || {
            let fd = libc::open(procs.as_ptr(), libc::O_WRONLY | libc::O_CLOEXEC);
            if fd >= 0 {
                libc::write(fd, b"0".as_ptr() as *const libc::c_void, 1);
                libc::close(fd);
            }
            Ok(())
        });
    }
}

/// The processes in a task's group (its Claude Code and what that started).
pub fn pids(group: &str) -> Vec<i32> {
    let Some(g) = current() else { return vec![] };
    std::fs::read_to_string(g.dir.join(group).join("cgroup.procs"))
        .unwrap_or_default()
        .lines()
        .filter_map(|l| l.trim().parse().ok())
        .collect()
}
