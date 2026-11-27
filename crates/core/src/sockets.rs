//! The service's local sockets and who may use them.
//!
//! * `control/control.sock`: the window. Every call. The folder is the
//!   owner's only (0700), and each connection is checked: a process inside
//!   the service's own control group (every Claude Code it runs) is refused,
//!   and so is any program other than the installed Mímir.
//! * `session.sock`: `mimir ask`, `update` and `approve` from Claude
//!   sessions. It has no message for answering.
//! * `tasks/<hash>.sock`: one per task, for its permission hook (see
//!   `approvals`). The task is known from the socket, never from the hook.
//! * `approvals.sock`, the path older versions listened on: answered and
//!   closed at once, so an older window finds Mímir "already open".
//!
//! Messages on the control and session sockets are a 4-byte little-endian
//! length, then JSON (at most 4 MiB).

use std::io::{Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};

/// The largest message either side sends.
pub const MAX_MESSAGE: usize = 4 << 20;

/// Mímir's folder in the runtime directory.
pub fn runtime_dir() -> PathBuf {
    crate::approvals::default_socket()
        .parent()
        .map(Path::to_path_buf)
        .unwrap_or_else(|| PathBuf::from("/tmp/mimir"))
}

/// The window's socket.
pub fn control_socket() -> PathBuf {
    runtime_dir().join("control").join("control.sock")
}

/// The socket Claude sessions use for `ask` and `update`.
pub fn session_socket() -> PathBuf {
    runtime_dir().join("session.sock")
}

/// The lock only one service holds.
pub fn lock_path() -> PathBuf {
    runtime_dir().join("station.lock")
}

/// Where a second service, started by systemd while one already runs,
/// hands systemd's listening socket to the running one.
pub fn handover_socket() -> PathBuf {
    runtime_dir().join("handover.sock")
}

/// Sends an open file descriptor over a Unix socket (SCM_RIGHTS).
pub fn send_fd(s: &UnixStream, fd: std::os::fd::RawFd) -> std::io::Result<()> {
    use std::os::fd::AsRawFd;
    let mut byte = [1u8];
    let mut iov = libc::iovec {
        iov_base: byte.as_mut_ptr() as *mut libc::c_void,
        iov_len: 1,
    };
    // SAFETY: CMSG_SPACE only computes a size.
    let space = unsafe { libc::CMSG_SPACE(std::mem::size_of::<libc::c_int>() as u32) } as usize;
    let mut buf = vec![0u8; space];
    // SAFETY: a zeroed msghdr is valid; every pointer set below points into
    // locals that outlive the call.
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = buf.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = space as _;
    // SAFETY: the control buffer is CMSG_SPACE bytes for one int.
    unsafe {
        let c = libc::CMSG_FIRSTHDR(&msg);
        (*c).cmsg_level = libc::SOL_SOCKET;
        (*c).cmsg_type = libc::SCM_RIGHTS;
        (*c).cmsg_len = libc::CMSG_LEN(std::mem::size_of::<libc::c_int>() as u32) as _;
        std::ptr::write_unaligned(libc::CMSG_DATA(c) as *mut libc::c_int, fd);
    }
    // SAFETY: msg is fully set up above.
    if unsafe { libc::sendmsg(s.as_raw_fd(), &msg, 0) } < 0 {
        return Err(std::io::Error::last_os_error());
    }
    Ok(())
}

/// Receives one file descriptor sent with `send_fd`.
pub fn recv_fd(s: &UnixStream) -> std::io::Result<std::os::fd::OwnedFd> {
    use std::os::fd::{AsRawFd, FromRawFd};
    let mut byte = [0u8];
    let mut iov = libc::iovec {
        iov_base: byte.as_mut_ptr() as *mut libc::c_void,
        iov_len: 1,
    };
    // SAFETY: CMSG_SPACE only computes a size.
    let space = unsafe { libc::CMSG_SPACE(std::mem::size_of::<libc::c_int>() as u32) } as usize;
    let mut buf = vec![0u8; space];
    // SAFETY: as in `send_fd`.
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = buf.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = space as _;
    // SAFETY: msg points at the buffers above; descriptors received are
    // closed on exec.
    let n = unsafe { libc::recvmsg(s.as_raw_fd(), &mut msg, libc::MSG_CMSG_CLOEXEC) };
    if n < 0 {
        return Err(std::io::Error::last_os_error());
    }
    // SAFETY: walks the control messages recvmsg filled in.
    unsafe {
        let c = libc::CMSG_FIRSTHDR(&msg);
        if !c.is_null() && (*c).cmsg_level == libc::SOL_SOCKET && (*c).cmsg_type == libc::SCM_RIGHTS
        {
            let fd = std::ptr::read_unaligned(libc::CMSG_DATA(c) as *const libc::c_int);
            return Ok(std::os::fd::OwnedFd::from_raw_fd(fd));
        }
    }
    Err(std::io::Error::other("no socket was handed over"))
}

/// Whether a descriptor is a listening Unix stream socket.
pub fn is_unix_listener(fd: std::os::fd::RawFd) -> bool {
    let get = |opt: libc::c_int| -> Option<libc::c_int> {
        let mut v: libc::c_int = 0;
        let mut len = std::mem::size_of::<libc::c_int>() as libc::socklen_t;
        // SAFETY: getsockopt writes one int.
        let rc = unsafe {
            libc::getsockopt(
                fd,
                libc::SOL_SOCKET,
                opt,
                &mut v as *mut _ as *mut libc::c_void,
                &mut len,
            )
        };
        (rc == 0).then_some(v)
    };
    get(libc::SO_DOMAIN) == Some(libc::AF_UNIX)
        && get(libc::SO_TYPE) == Some(libc::SOCK_STREAM)
        && get(libc::SO_ACCEPTCONN) == Some(1)
}

/// The running service's process ID (for checks and `systemctl`-less stops).
pub fn pid_path() -> PathBuf {
    runtime_dir().join("service.pid")
}

/// Writes one message.
pub fn write_msg<T: serde::Serialize>(w: &mut impl Write, msg: &T) -> std::io::Result<()> {
    let mut data = serde_json::to_vec(msg).map_err(std::io::Error::other)?;
    if data.len() > MAX_MESSAGE {
        return Err(std::io::Error::other("message too large"));
    }
    let mut out = (data.len() as u32).to_le_bytes().to_vec();
    out.append(&mut data);
    w.write_all(&out)?;
    w.flush()
}

/// Reads one message's bytes; None at the end of the stream.
pub fn read_frame(r: &mut impl Read) -> std::io::Result<Option<Vec<u8>>> {
    let mut len = [0u8; 4];
    match r.read_exact(&mut len) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let n = u32::from_le_bytes(len) as usize;
    if n > MAX_MESSAGE {
        return Err(std::io::Error::other("message too large"));
    }
    let mut data = vec![0u8; n];
    r.read_exact(&mut data)?;
    Ok(Some(data))
}

/// Reads one message; None at the end of the stream or when it isn't one.
pub fn read_msg<T: serde::de::DeserializeOwned>(r: &mut impl Read) -> std::io::Result<Option<T>> {
    match read_frame(r)? {
        Some(data) => Ok(serde_json::from_slice(&data).ok()),
        None => Ok(None),
    }
}

/// A folder only the owner's user can open.
pub fn private_dir(dir: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)?;
    std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))
}

/// Binds a socket the owner's user alone can use, replacing a stale one.
pub fn bind_private(path: &Path) -> std::io::Result<UnixListener> {
    if let Some(dir) = path.parent() {
        private_dir(dir)?;
    }
    if UnixStream::connect(path).is_ok() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::AddrInUse,
            "Mímir is already open",
        ));
    }
    let _ = std::fs::remove_file(path);
    let l = UnixListener::bind(path)?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    Ok(l)
}

/// The process on the other end of a socket.
pub fn peer_pid(s: &UnixStream) -> Option<i32> {
    let mut cred = libc::ucred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    // SAFETY: getsockopt writes at most `len` bytes into `cred`.
    let rc = unsafe {
        libc::getsockopt(
            std::os::fd::AsRawFd::as_raw_fd(s),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut _ as *mut libc::c_void,
            &mut len,
        )
    };
    (rc == 0 && cred.pid > 0).then_some(cred.pid)
}

/// A process's control group, e.g. `/user.slice/.../mimir.service/task-x`.
pub fn cgroup_of(pid: i32) -> Option<String> {
    let s = std::fs::read_to_string(format!("/proc/{pid}/cgroup")).ok()?;
    s.lines()
        .find_map(|l| l.strip_prefix("0::"))
        .map(str::to_string)
}

/// The program a process runs, as installed (an updated program shows
/// " (deleted)" until it restarts).
pub fn program_of(pid: i32) -> Option<PathBuf> {
    let p = std::fs::read_link(format!("/proc/{pid}/exe")).ok()?;
    let s = p.to_string_lossy();
    Some(PathBuf::from(
        s.strip_suffix(" (deleted)").unwrap_or(&s).to_string(),
    ))
}

/// The program allowed on the control socket: the installed Mímir.
/// A debug build's checks name their own (`MIMIR_TEST_WINDOW`);
/// a release build never takes it.
pub fn window_program() -> PathBuf {
    if cfg!(debug_assertions)
        && let Some(p) = std::env::var_os("MIMIR_TEST_WINDOW")
    {
        return PathBuf::from(p);
    }
    // Inside a Flatpak the window and the service are the same program.
    if crate::flatpak::active() {
        return crate::paths::own_program();
    }
    crate::install::Layout::for_user().bin()
}

/// Whether the control socket asks every window for the capability (see
/// [`Capability`]). Always inside a Flatpak, where a process on the PC runs
/// as the same user, can open the 0700 folder and can't be told apart by
/// its process number. A debug build's checks switch it on with
/// `MIMIR_FAKE_CAP`.
pub fn cap_required() -> bool {
    crate::flatpak::active()
        || (cfg!(debug_assertions) && std::env::var_os("MIMIR_FAKE_CAP").is_some())
}

/// The first thing a connection to the control socket says when
/// [`cap_required`].
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub enum Opening {
    /// The window's capability.
    Cap(String),
    /// "Open a window for me": the launcher asks the service to start the
    /// window inside its own sandbox (so the window can be handed the
    /// capability). Harmless to anyone who sends it; the environment given
    /// is only the display's.
    OpenWindow(Vec<(String, String)>),
}

/// The secret that makes a connection a window. The service makes it at
/// start and keeps it in memory only; it goes to a window it starts itself,
/// over a socketpair the window inherits (descriptor 3), never through a
/// file, the environment or a path. A program the PC runs for a task is not
/// the service's child, can't read the service's memory (ptrace_scope), and
/// can't open a descriptor of a socket through /proc, so it never has it.
pub struct Capability(String);

impl Capability {
    pub fn new() -> Capability {
        let mut b = [0u8; 32];
        use ring::rand::SecureRandom;
        ring::rand::SystemRandom::new()
            .fill(&mut b)
            .expect("the system has no random numbers");
        Capability(b.iter().map(|x| format!("{x:02x}")).collect())
    }

    /// Whether `given` is this capability (compared in constant time).
    pub fn accepts(&self, given: &str) -> bool {
        let (a, b) = (self.0.as_bytes(), given.as_bytes());
        let mut diff = (a.len() != b.len()) as u8;
        for (i, x) in a.iter().enumerate() {
            diff |= x ^ b.get(i).copied().unwrap_or(0);
        }
        diff == 0
    }
}

impl Default for Capability {
    fn default() -> Self {
        Self::new()
    }
}

/// The capability this window process was started with.
static HELD: std::sync::OnceLock<String> = std::sync::OnceLock::new();

/// Set when the service refused this process (it holds a capability from an
/// older service, or none).
pub static REFUSED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// The capability this process holds, if the service started it as a window.
pub fn held() -> Option<&'static str> {
    HELD.get().map(String::as_str)
}

/// A window the service started: takes the capability from the descriptor
/// named by `MIMIR_CAP_FD`, then closes it. Call first thing in `main`.
/// False when there is none (a launcher, a terminal command).
pub fn take_held() -> bool {
    let Some(fd) = std::env::var("MIMIR_CAP_FD")
        .ok()
        .and_then(|v| v.parse::<i32>().ok())
    else {
        return false;
    };
    // SAFETY: called at the start of main, before any thread exists.
    unsafe { std::env::remove_var("MIMIR_CAP_FD") };
    // SAFETY: the service passed this descriptor to us and nothing else owns it.
    let mut f = unsafe { <std::fs::File as std::os::fd::FromRawFd>::from_raw_fd(fd) };
    let mut got = String::new();
    let _ = std::io::Read::take(&mut f, 256).read_to_string(&mut got);
    drop(f);
    let got = got.trim().to_string();
    if got.is_empty() {
        return false;
    }
    let _ = HELD.set(got);
    true
}

/// What the window sends first on a new connection, when it must.
pub fn send_opening(s: &mut UnixStream) -> std::io::Result<()> {
    if cap_required()
        && let Some(c) = held()
    {
        write_msg(s, &Opening::Cap(c.to_string()))?;
    }
    Ok(())
}

/// The display variables a started window needs.
const WINDOW_ENV: &[&str] = &[
    "DISPLAY",
    "WAYLAND_DISPLAY",
    "XAUTHORITY",
    "XDG_SESSION_TYPE",
    "XDG_CURRENT_DESKTOP",
    "LANG",
];

/// The display variables of this process, for [`Opening::OpenWindow`].
pub fn display_env() -> Vec<(String, String)> {
    WINDOW_ENV
        .iter()
        .filter_map(|k| std::env::var(k).ok().map(|v| (k.to_string(), v)))
        .collect()
}

/// Starts a window as a child of this process and hands it the capability
/// over a socketpair (descriptor 3). Used by the service.
pub fn start_window(cap: &Capability, env: &[(String, String)]) -> std::io::Result<()> {
    use std::os::fd::{FromRawFd, IntoRawFd};
    use std::os::unix::process::CommandExt;
    let program = if cfg!(debug_assertions)
        && let Some(p) = std::env::var_os("MIMIR_TEST_WINDOW_PROG")
    {
        PathBuf::from(p)
    } else {
        crate::paths::own_program()
    };
    let (mine, theirs) = UnixStream::pair()?;
    let theirs_fd = theirs.into_raw_fd();
    let mut cmd = std::process::Command::new(program);
    cmd.env("MIMIR_CAP_FD", "3");
    for (k, v) in env {
        if WINDOW_ENV.contains(&k.as_str()) {
            cmd.env(k, v);
        }
    }
    cmd.stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .process_group(0);
    // SAFETY: runs between fork and exec; only dup2/fcntl, which are safe there.
    unsafe {
        cmd.pre_exec(move || {
            if theirs_fd != 3 && libc::dup2(theirs_fd, 3) < 0 {
                return Err(std::io::Error::last_os_error());
            }
            let fl = libc::fcntl(3, libc::F_GETFD);
            libc::fcntl(3, libc::F_SETFD, fl & !libc::FD_CLOEXEC);
            Ok(())
        });
    }
    let child = cmd.spawn();
    // SAFETY: we made this descriptor above and own it.
    drop(unsafe { UnixStream::from_raw_fd(theirs_fd) });
    let mut child = child?;
    let mut mine = mine;
    mine.write_all(cap.0.as_bytes())?;
    drop(mine);
    // Nobody waits for a window: it is reaped when it ends.
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(())
}

/// Why a connection to the control socket is refused, if it is.
/// `service_group` is the group the service's unit (or scope) owns: every
/// Claude Code it runs is inside it.
pub fn refuse_peer(pid: Option<i32>, service_group: Option<&str>) -> Option<&'static str> {
    let Some(pid) = pid else {
        // Each Flatpak window is a sandbox of its own and can't see the
        // background part's process numbers. The folder is the owner's
        // only (0700), and a task's Claude Code is told apart by its own
        // sockets.
        if crate::flatpak::active() {
            return None;
        }
        return Some("who is connecting can't be told");
    };
    let group = cgroup_of(pid);
    if let (Some(root), Some(g)) = (service_group, group.as_deref())
        && (g == root || g.starts_with(&format!("{root}/")))
    {
        return Some("it runs inside Mímir (a task's process)");
    }
    // A session a kept Remote Control started runs outside the service's
    // group, in its own slice: still Mímir's.
    if group.is_some_and(|g| in_kept_slice(&g)) {
        return Some("it runs inside Mímir (a task's process)");
    }
    match program_of(pid) {
        Some(p) if p == window_program() => None,
        _ => Some("it isn't Mímir's window"),
    }
}

/// A control group inside the slice kept Remote Control runs in.
fn in_kept_slice(group: &str) -> bool {
    group.split('/').any(|part| part == "mimir-sessions.slice")
}

/// What a Claude session may send on the session socket.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub enum SessionMsg {
    /// A question for the owner: adds a needs-you item. The reply is its
    /// id, or with `wait` the owner's answer.
    Ask {
        text: String,
        choices: Vec<String>,
        wait: bool,
        /// The project folder (default: the asking task's).
        #[serde(default)]
        project: Option<String>,
        /// "check-fix", "approve", "choose" or "release" (default "choose"
        /// with choices, "approve" without).
        #[serde(default)]
        kind: Option<String>,
        #[serde(default)]
        link: Option<String>,
    },
    /// Waits for the answer to an earlier ask; the reply is the answer.
    Wait { ask_id: String },
    /// `mimir fresh-start` (tools/relay.sh): start the asking task afresh
    /// from its hand-off. `task` is the session's `MIMIR_TASK_ID`, used only
    /// when its control group does not already say which task asks.
    FreshStart { task: Option<String> },
    /// A permission request from a Claude session Mímir doesn't run
    /// (`mimir approve`). The reply is "allow", "always", "deny"
    /// or "pc" (ask on the PC as normal).
    Permission(Box<crate::approve::OutsideAsk>),
}

/// What the service does with a session message: `task` is the asking
/// Mímir task (from its control group), the reply goes to `reply`.
pub type SessionHandler = std::sync::Arc<
    dyn Fn(SessionMsg, Option<String>, std::sync::mpsc::Sender<String>) + Send + Sync,
>;

/// The task a process belongs to, from its control group (`task-<id>`).
pub fn task_of(pid: Option<i32>) -> Option<String> {
    let g = cgroup_of(pid?)?;
    g.rsplit('/')
        .find_map(|part| part.strip_prefix("task-"))
        .map(str::to_string)
}

/// Listens on the session socket. Each connection may send one
/// `SessionMsg`; anything else (an answer above all) is refused.
pub fn serve_session(path: &Path, handler: SessionHandler) -> std::io::Result<()> {
    let l = bind_private(path)?;
    std::thread::spawn(move || {
        for conn in l.incoming() {
            let Ok(mut s) = conn else { continue };
            let handler = handler.clone();
            std::thread::spawn(move || {
                let _ = s.set_read_timeout(Some(std::time::Duration::from_secs(5)));
                let msg = match read_msg::<SessionMsg>(&mut s) {
                    Ok(Some(m)) => m,
                    _ => {
                        let _ = write_msg(
                            &mut s,
                            &"refused: the session socket only takes Ask, Wait, FreshStart and Permission",
                        );
                        return;
                    }
                };
                let task = task_of(peer_pid(&s));
                let (tx, rx) = std::sync::mpsc::channel();
                handler(msg, task, tx);
                // An ask that waits may wait as long as the owner takes.
                let reply = rx
                    .recv_timeout(std::time::Duration::from_secs(7 * 86_400))
                    .unwrap_or_else(|_| "refused: Mímir stopped".into());
                let _ = write_msg(&mut s, &reply);
            });
        }
    });
    Ok(())
}

/// Answers and closes every connection on the old approvals path, so an
/// older Mímir window refuses to start while the service runs.
pub fn guard_old_path(path: &Path) -> std::io::Result<()> {
    let l = bind_private(path)?;
    std::thread::spawn(move || {
        for conn in l.incoming() {
            drop(conn);
        }
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_round_trip_and_size_is_limited() {
        let mut buf = vec![];
        write_msg(&mut buf, &"hello").unwrap();
        write_msg(&mut buf, &42).unwrap();
        let mut r = &buf[..];
        assert_eq!(
            read_msg::<String>(&mut r).unwrap().as_deref(),
            Some("hello")
        );
        assert_eq!(read_msg::<i32>(&mut r).unwrap(), Some(42));
        assert_eq!(read_msg::<i32>(&mut r).unwrap(), None);
        let mut big = ((MAX_MESSAGE + 1) as u32).to_le_bytes().to_vec();
        big.extend([0u8; 8]);
        assert!(read_frame(&mut &big[..]).is_err());
    }

    #[test]
    fn a_process_inside_the_service_group_is_refused() {
        let me = std::process::id() as i32;
        let mine = cgroup_of(me).unwrap_or_default();
        assert!(refuse_peer(Some(me), Some(&mine)).is_some());
        assert!(refuse_peer(None, None).is_some());
        assert!(in_kept_slice(
            "/user.slice/user-1000.slice/user@1000.service/claude.slice/mimir.slice/\
             mimir-sessions.slice/mimir-keeper-1-2-3.scope"
        ));
        assert!(!in_kept_slice(
            "/user.slice/user-1000.slice/user@1000.service/app.slice/mimir.service"
        ));
    }
}
