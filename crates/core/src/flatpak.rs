//! Running as a Flatpak. Mímir ships only as a Flatpak (PC) and an APK
//! (phone), so everything here is about the sandbox: where it keeps its
//! runtime files, how it reaches programs that live on the PC itself
//! (Claude Code, git, the terminal), and how the background part starts
//! with the session. Outside a Flatpak (a test run, a build in a terminal)
//! every function here does nothing special.

use std::path::{Path, PathBuf};
use std::process::Command;

/// The app's name for Flatpak and the desktop.
pub const APP_ID: &str = "io.github.odnwks.Mimir";

/// Whether this process runs inside a Flatpak sandbox.
pub fn active() -> bool {
    if std::env::var_os("MIMIR_FAKE_FLATPAK").is_some() && cfg!(debug_assertions) {
        return true;
    }
    std::env::var_os("FLATPAK_ID").is_some() || Path::new("/.flatpak-info").exists()
}

/// The app's id inside the sandbox.
pub fn app_id() -> String {
    std::env::var("FLATPAK_ID").unwrap_or_else(|_| APP_ID.to_string())
}

/// Keeps the data and settings in their usual folders. Flatpak points the
/// folder variables at `~/.var/app/<id>/…`; the app's manifest opens the
/// real `~/.local/share/mimir` and `~/.config/mimir` instead, so nothing
/// moves and the old and new program see the same chats and settings.
/// Call once, first thing, before any other thread starts.
pub fn use_usual_folders() {
    if !active() || std::env::var_os("MIMIR_KEEP_FLATPAK_FOLDERS").is_some() {
        return;
    }
    let Some(home) = std::env::var_os("HOME").map(PathBuf::from) else {
        return;
    };
    // SAFETY: called at the start of main, before any thread exists.
    unsafe {
        std::env::set_var("XDG_DATA_HOME", home.join(".local/share"));
        std::env::set_var("XDG_CONFIG_HOME", home.join(".config"));
    }
}

/// Where the runtime files (sockets, lock) go. Inside a Flatpak only
/// `$XDG_RUNTIME_DIR/app/<id>` is shared with the next window and with the
/// PC's own programs, so that is where Mímir's folder lives.
pub fn runtime_base() -> PathBuf {
    let base = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            std::env::temp_dir().join(format!("mimir-{}", unsafe { libc::getuid() }))
        });
    if active() && std::env::var_os("XDG_RUNTIME_DIR").is_some() {
        base.join("app").join(app_id())
    } else {
        base
    }
}

/// A program that lives on the PC, not in the sandbox (git, Claude Code,
/// the terminal). Outside a Flatpak it is just the program; inside, it
/// runs through the host portal (`flatpak-spawn --host`). Arguments added
/// afterwards go to the program. For a program that needs a folder or
/// environment variables use [`to_host`].
pub fn host_cmd(program: impl AsRef<std::ffi::OsStr>) -> Command {
    if active() {
        let mut c = Command::new("flatpak-spawn");
        c.arg("--host").arg(program.as_ref());
        c
    } else {
        Command::new(program)
    }
}

/// Turns a finished command (program, arguments, folder and variables set)
/// into the same command run on the PC. Outside a Flatpak it comes back
/// unchanged. Set the pipes and other options on the result.
pub fn to_host(cmd: Command) -> Command {
    if !active() {
        return cmd;
    }
    let mut c = Command::new("flatpak-spawn");
    c.arg("--host");
    if let Some(dir) = cmd.get_current_dir() {
        c.arg(format!("--directory={}", dir.display()));
    }
    for (k, v) in cmd.get_envs() {
        match v {
            Some(v) => c.arg(format!(
                "--env={}={}",
                k.to_string_lossy(),
                v.to_string_lossy()
            )),
            None => c.arg(format!("--unset-env={}", k.to_string_lossy())),
        };
    }
    c.arg(cmd.get_program());
    c.args(cmd.get_args());
    c
}

/// The programs running on the PC: the process number and the words each
/// was started with. A sandbox can't see the PC's processes in `/proc`, so
/// inside a Flatpak the PC's own `ps` answers (through the host helper).
/// Outside a Flatpak, None: read `/proc` as usual.
pub fn host_processes() -> Option<Vec<(u32, Vec<String>)>> {
    if !active() {
        return None;
    }
    let out = host_cmd("ps")
        .args(["-eww", "-o", "pid=,args="])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .output()
        .ok()
        .filter(|o| o.status.success())?;
    Some(parse_ps(&String::from_utf8_lossy(&out.stdout)))
}

/// `ps -o pid=,args=` lines as (process number, words).
fn parse_ps(text: &str) -> Vec<(u32, Vec<String>)> {
    text.lines()
        .filter_map(|l| {
            let l = l.trim_start();
            let (pid, args) = l.split_once(char::is_whitespace)?;
            Some((
                pid.parse().ok()?,
                args.split_whitespace().map(str::to_string).collect(),
            ))
        })
        .collect()
}

/// The folder a process on the PC runs in (through the host helper).
pub fn host_cwd(pid: u32) -> Option<PathBuf> {
    let out = host_cmd("readlink")
        .arg(format!("/proc/{pid}/cwd"))
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .output()
        .ok()
        .filter(|o| o.status.success())?;
    let t = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (!t.is_empty()).then(|| PathBuf::from(t))
}

/// The words that start this program on the PC (for a program Claude Code
/// or the PC runs outside the sandbox, such as the permission hook).
pub fn own_command_words(exe: &Path) -> Vec<String> {
    if active() {
        vec![
            "flatpak".into(),
            "run".into(),
            "--command=mimir".into(),
            app_id(),
        ]
    } else {
        vec![exe.display().to_string()]
    }
}

/// Asks the desktop to keep Mímir's background part running and start it
/// with the session (the Background portal). Quiet when the desktop says
/// no: the window starts the background part itself anyway.
pub fn ask_to_run_in_background() {
    if !active() {
        return;
    }
    let mark = crate::paths::config_dir().join("background-asked");
    if mark.exists() {
        return;
    }
    let _ = std::fs::create_dir_all(crate::paths::config_dir());
    let _ = std::fs::write(&mark, "1\n");
    let options = "{'reason': <'Mímir keeps your phone connected and your tasks going.'>, \
                    'autostart': <true>, 'commandline': <['mimir', 'service']>}";
    let _ = Command::new("gdbus")
        .args([
            "call",
            "--session",
            "--dest",
            "org.freedesktop.portal.Desktop",
            "--object-path",
            "/org/freedesktop/portal/desktop",
            "--method",
            "org.freedesktop.portal.Background.RequestBackground",
            "",
            options,
        ])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pcs_process_list_is_read() {
        let v = parse_ps(
            "    1 /sbin/init\n 4242 claude --resume 0b1c2d3e-0000-4000-8000-123456789abc -p\nbad line\n",
        );
        assert_eq!(v.len(), 2);
        assert_eq!(v[1].0, 4242);
        assert_eq!(v[1].1[0], "claude");
        assert_eq!(v[1].1[2], "0b1c2d3e-0000-4000-8000-123456789abc");
        assert!(host_processes().is_none() || active());
    }

    #[test]
    fn outside_a_flatpak_commands_are_unchanged() {
        if active() {
            return;
        }
        let mut c = Command::new("git");
        c.arg("status").current_dir("/x").env("A", "b");
        let c = to_host(c);
        assert_eq!(c.get_program(), "git");
        assert_eq!(c.get_args().count(), 1);
        assert_eq!(host_cmd("git").get_program(), "git");
        assert_eq!(
            own_command_words(Path::new("/bin/mimir")),
            vec!["/bin/mimir".to_string()]
        );
    }
}
