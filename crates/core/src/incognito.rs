//! Incognito: a task on a free model in a sealed space, proven on 2026-11-18
//! (General/tasks/2026-11-18-incognito) and built in here.
//!
//! *   The task's folder, `$XDG_RUNTIME_DIR/mimir-incognito/<id>/`
//!     (RAM only), holds its approvals socket, `ollama.sock` (the service
//!     forwards it to Ollama) and Claude Code's settings folder.
//! *   Claude Code runs under `unshare -rnmp --fork --mount-proc`: its own
//!     network with only loopback, its own mounts and process numbers. The
//!     launcher (`mimir incognito-launch`) is process 1 there: it
//!     raises loopback, hides Mímir's own folders under empty
//!     in-memory ones, forwards 127.0.0.1:11434 to `ollama.sock`, checks the
//!     seal, then starts Claude Code as the normal user (`unshare -U`).
//! *   Nothing of it is saved: no task file, no undo snapshots, no updates,
//!     no wake-up push. Ending it ends process 1 (and with it every process
//!     in its space) and deletes its folder.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// What the owner sees when the seal can't be checked.
pub const NOT_SEALED: &str = "Whisper mode couldn't seal itself off. Nothing was started.";

/// Where Incognito tasks keep their folders (RAM, outside Mímir's own).
pub fn root() -> PathBuf {
    let run = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir);
    run.join("mimir-incognito")
}

/// One task's folder (short: socket paths are limited).
pub fn dir(task: &str) -> PathBuf {
    use sha2::Digest;
    let h = sha2::Sha256::digest(task.as_bytes());
    let hex: String = h.iter().take(6).map(|b| format!("{b:02x}")).collect();
    root().join(hex)
}

pub fn config_dir(task: &str) -> PathBuf {
    dir(task).join("config")
}

pub fn approvals_socket(task: &str) -> PathBuf {
    dir(task).join("approvals.sock")
}

pub fn ollama_socket(task: &str) -> PathBuf {
    dir(task).join("ollama.sock")
}

/// Makes the task's folder (owner only) and starts forwarding its
/// `ollama.sock` to Ollama. The forwarding stops when the folder goes.
pub fn prepare(task: &str) -> std::io::Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    let d = dir(task);
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(&d)?;
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(config_dir(task))?;
    let sock = ollama_socket(task);
    let _ = std::fs::remove_file(&sock);
    let l = UnixListener::bind(&sock)?;
    let target = crate::models::ollama_url()
        .trim_start_matches("http://")
        .trim_end_matches('/')
        .to_string();
    std::thread::Builder::new()
        .name("incognito-ollama".into())
        .spawn(move || {
            for c in l.incoming() {
                if !sock.exists() {
                    break;
                }
                let Ok(c) = c else { continue };
                let Ok(t) = TcpStream::connect(&target) else {
                    continue;
                };
                pipe_unix_tcp(c, t);
            }
        })?;
    Ok(())
}

/// Deletes a task's folder (its config, sockets, everything).
pub fn forget(task: &str) {
    let d = dir(task);
    let _ = std::fs::remove_dir_all(&d);
    // Wake the forwarder so it sees its socket gone.
    let _ = UnixStream::connect(d.join("ollama.sock"));
}

/// Removes folders left by a service that stopped (none survive a restart).
pub fn forget_all() {
    let _ = std::fs::remove_dir_all(root());
}

/// The command Claude Code runs under, before its own program and arguments.
pub fn wrap(own_program: &Path, task: &str) -> Vec<String> {
    // SAFETY: getuid/getgid can't fail.
    let (uid, gid) = unsafe { (libc::getuid(), libc::getgid()) };
    let mut w: Vec<String> = vec![
        "unshare".into(),
        "-rnmp".into(),
        "--fork".into(),
        "--mount-proc".into(),
    ];
    w.extend(crate::flatpak::own_command_words(own_program));
    w.extend([
        "incognito-launch".into(),
        dir(task).display().to_string(),
        "--".into(),
        "unshare".into(),
        "-U".into(),
        format!("--map-user={uid}"),
        format!("--map-group={gid}"),
    ]);
    w
}

/// The environment Claude Code gets: a free model through the forwarder,
/// settings in RAM, and nothing sent anywhere else.
pub fn env(task: &str) -> Vec<(String, String)> {
    let mut e = crate::models::launch_env();
    for (k, v) in e.iter_mut() {
        if k == "ANTHROPIC_BASE_URL" {
            *v = "http://127.0.0.1:11434".into();
        }
    }
    e.extend([
        (
            "CLAUDE_CONFIG_DIR".to_string(),
            config_dir(task).display().to_string(),
        ),
        ("DISABLE_TELEMETRY".into(), "1".into()),
        ("DISABLE_ERROR_REPORTING".into(), "1".into()),
        ("DISABLE_AUTOUPDATER".into(), "1".into()),
        (
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC".into(),
            "1".into(),
        ),
    ]);
    e
}

/// Claude Code's settings for the sealed task: no web tools at all.
pub fn deny_web(settings_json: &str) -> String {
    let mut v: serde_json::Value =
        serde_json::from_str(settings_json).unwrap_or_else(|_| serde_json::json!({}));
    v["permissions"]["deny"] = serde_json::json!(["WebFetch", "WebSearch"]);
    v.to_string()
}

fn pipe_unix_tcp(u: UnixStream, t: TcpStream) {
    let (mut u2, mut t2) = match (u.try_clone(), t.try_clone()) {
        (Ok(a), Ok(b)) => (a, b),
        _ => return,
    };
    let (mut u, mut t) = (u, t);
    std::thread::spawn(move || {
        let _ = std::io::copy(&mut u, &mut t);
        let _ = t.shutdown(std::net::Shutdown::Write);
    });
    std::thread::spawn(move || {
        let _ = std::io::copy(&mut t2, &mut u2);
        let _ = u2.shutdown(std::net::Shutdown::Write);
    });
}

// ---- inside the sealed space (process 1) ----

/// Raises the loopback device (the space starts with it down).
fn loopback_up() -> Result<(), String> {
    // SAFETY: an ordinary socket for the interface ioctls.
    let fd = unsafe { libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0) };
    if fd < 0 {
        return Err("no socket for loopback".into());
    }
    // SAFETY: a zeroed ifreq with the name "lo".
    let mut req: libc::ifreq = unsafe { std::mem::zeroed() };
    for (i, b) in b"lo".iter().enumerate() {
        req.ifr_name[i] = *b as libc::c_char;
    }
    // SAFETY: valid fd and ifreq.
    let ok = unsafe {
        libc::ioctl(fd, libc::SIOCGIFFLAGS, &mut req) == 0 && {
            req.ifr_ifru.ifru_flags |= (libc::IFF_UP | libc::IFF_RUNNING) as libc::c_short;
            libc::ioctl(fd, libc::SIOCSIFFLAGS, &req) == 0
        }
    };
    // SAFETY: closing our own fd.
    unsafe { libc::close(fd) };
    if ok {
        Ok(())
    } else {
        Err("loopback didn't come up".into())
    }
}

/// Covers `path` with an empty in-memory folder (only in this space).
fn hide(path: &Path) -> Result<(), String> {
    if !path.exists() {
        return Ok(());
    }
    let p =
        std::ffi::CString::new(path.as_os_str().as_encoded_bytes()).map_err(|e| e.to_string())?;
    let fs = std::ffi::CString::new("tmpfs").unwrap();
    let opts = std::ffi::CString::new("mode=700").unwrap();
    // SAFETY: valid C strings; a private tmpfs over a folder we own.
    let r = unsafe {
        libc::mount(
            fs.as_ptr(),
            p.as_ptr(),
            fs.as_ptr(),
            libc::MS_NOSUID | libc::MS_NODEV,
            opts.as_ptr() as *const libc::c_void,
        )
    };
    if r == 0 {
        Ok(())
    } else {
        // Only the developer log sees this: the caller says it in plain words. lint:log-only
        Err(format!("couldn't hide {}", path.display()))
    }
}

/// The places Mímir keeps its sockets, keys and data.
fn hidden_places() -> Vec<PathBuf> {
    let run = std::env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from);
    let mut v = vec![crate::paths::config_dir(), crate::paths::data_dir()];
    if let Some(r) = run {
        v.push(r.join("mimir"));
    }
    v
}

/// The seal holds: no way out to the internet, no control or session
/// socket, and the link's key can't be read. Err with the reason otherwise.
pub fn probe() -> Result<(), String> {
    for addr in ["1.1.1.1:443", "8.8.8.8:53"] {
        let a: SocketAddr = addr.parse().unwrap();
        if TcpStream::connect_timeout(&a, Duration::from_secs(2)).is_ok() {
            return Err(format!("{addr} can be reached"));
        }
    }
    let run = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_default();
    for s in ["control/control.sock", "session.sock"] {
        let p = run.join("mimir").join(s);
        if p.exists() || UnixStream::connect(&p).is_ok() {
            return Err(format!("{} is visible", p.display()));
        }
    }
    let key = crate::paths::config_dir().join("link/key");
    if std::fs::read(&key).is_ok() {
        return Err("the link's key can be read".into());
    }
    Ok(())
}

/// Forwards 127.0.0.1:11434 inside the space to the task's `ollama.sock`.
fn forward(sock: PathBuf) -> Result<(), String> {
    let l = TcpListener::bind("127.0.0.1:11434").map_err(|e| format!("forwarder: {e}"))?;
    std::thread::spawn(move || {
        for c in l.incoming() {
            let Ok(t) = c else { continue };
            let Ok(u) = UnixStream::connect(&sock) else {
                continue;
            };
            pipe_unix_tcp(u, t);
        }
    });
    Ok(())
}

/// `mimir incognito-launch <task folder> -- <command…>`: process 1
/// of the sealed space. Prints why on stderr and exits 3 if the seal fails.
pub fn launch_main(args: &[String]) -> i32 {
    let Some(dir) = args.first().map(PathBuf::from) else {
        eprintln!("usage: mimir incognito-launch <folder> -- <command…>");
        return 2;
    };
    let rest: Vec<&String> = args.iter().skip_while(|a| *a != "--").skip(1).collect();
    if rest.is_empty() {
        eprintln!("incognito-launch: no command");
        return 2;
    }
    // If whatever started it goes, so does the whole space.
    // SAFETY: prctl with a plain signal number.
    unsafe { libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) };
    let setup = (|| -> Result<(), String> {
        loopback_up()?;
        for p in hidden_places() {
            hide(&p)?;
        }
        forward(dir.join("ollama.sock"))?;
        probe()
    })();
    if let Err(why) = setup {
        eprintln!("{NOT_SEALED} ({why})");
        // Mímir reads this line from the stream.
        println!(
            "{}",
            serde_json::json!({"type": "result", "subtype": "error", "is_error": true,
                "result": format!("{NOT_SEALED} ({why})"), "session_id": "", "total_cost_usd": 0.0})
        );
        let _ = std::io::stdout().flush();
        return 3;
    }
    let mut child = match crate::process::without_systemd(&mut std::process::Command::new(rest[0]))
        .args(&rest[1..])
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!("incognito-launch: {e}");
            return 1;
        }
    };
    match child.wait() {
        Ok(s) => s.code().unwrap_or(1),
        Err(_) => 1,
    }
}

/// Reads all of a stream (checks).
pub fn read_all(mut s: impl Read) -> String {
    let mut out = String::new();
    let _ = s.read_to_string(&mut out);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wrap_seals_then_runs_as_the_owner() {
        let w = wrap(Path::new("/x/mimir"), "t1");
        assert_eq!(&w[..4], ["unshare", "-rnmp", "--fork", "--mount-proc"]);
        assert_eq!(w[5], "incognito-launch");
        assert!(w.iter().any(|a| a.starts_with("--map-user=")));
        assert_eq!(w[7], "--");
    }

    #[test]
    fn web_tools_are_denied_and_hook_kept() {
        let s = deny_web(r#"{"hooks":{"PermissionRequest":[]}}"#);
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["permissions"]["deny"][0], "WebFetch");
        assert!(v["hooks"].is_object());
    }

    #[test]
    fn env_points_at_the_forwarder_and_ram() {
        let e = env("t1");
        let get = |k: &str| e.iter().find(|(a, _)| a == k).map(|(_, b)| b.clone());
        assert_eq!(
            get("ANTHROPIC_BASE_URL").as_deref(),
            Some("http://127.0.0.1:11434")
        );
        assert!(
            get("CLAUDE_CONFIG_DIR")
                .unwrap()
                .contains("mimir-incognito")
        );
        assert_eq!(
            get("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC").as_deref(),
            Some("1")
        );
    }

    /// B6 / PHE-03, PHE-18: after a Whisper (Incognito) ends, its folder
    /// — which held all its in-RAM state — is gone. Nothing of the Whisper's
    /// words can be recalled.
    #[test]
    fn whisper_clear() {
        let task = "whisper-clear-test-task";
        let d = dir(task);
        // Create the folder so forget has something to delete.
        let _ = std::fs::create_dir_all(&d);
        assert!(d.exists(), "pre-condition: folder exists");
        forget(task);
        assert!(!d.exists(), "Whisper folder was not deleted after end");
    }
}
