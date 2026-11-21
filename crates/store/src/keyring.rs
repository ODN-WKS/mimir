//! The key for the chat files, from the desktop's keyring (`secret-tool`)
//! or a key file (checks), and the sealing with it (AES-256-GCM).
//!
//! Rules (fixes PCE-05, PCE-06):
//! - A key is made only when `secret-tool lookup` exits 1 and prints
//!   nothing at all ("not found").
//! - Anything else (a timeout, a crash, odd or empty output with exit 0)
//!   is "locked": the key that may be there is never overwritten.
//! - A locked store is asked again on later requests ([`crate::talk::Unlock`]).

use std::path::{Path, PathBuf};

/// The keyring's attributes for the chats' key (lookup and store use the
/// same ones: before B3 the key was stored as "talk" and looked up as
/// "chats", so a made key was never found again).
const ATTRS: [&str; 4] = ["app", "mimir", "key", "chats"];
/// What a key lookup says when no keyring program is installed.
pub(crate) const NO_PROGRAM: &str = "no keyring program";
pub(crate) const MAGIC: &[u8] = b"MIMIR1";

/// Where the key for the chat files comes from.
#[derive(Debug, Clone)]
pub enum Keyring {
    /// The desktop's Secret Service (`secret-tool`; the KDE Wallet here).
    SecretService,
    /// A key file (checks).
    File(PathBuf),
    /// No key: plain files, 0600.
    Off,
}

impl Keyring {
    /// The keyring this PC uses (a check names a key file instead).
    pub fn from_env() -> Keyring {
        match std::env::var_os("MIMIR_TALK_KEYFILE") {
            Some(f) if f == "off" => Keyring::Off,
            Some(f) => Keyring::File(PathBuf::from(f)),
            None => Keyring::SecretService,
        }
    }

    /// The key, made the first time. Err when no keyring answers.
    pub(crate) fn key(&self, may_create: bool) -> Result<[u8; 32], String> {
        match self {
            Keyring::Off => Err("off".into()),
            Keyring::File(f) => {
                if let Some(k) = std::fs::read_to_string(f).ok().and_then(|t| decode(&t)) {
                    return Ok(k);
                }
                let k = fresh_key()?;
                write_private(f, encode(&k).as_bytes()).map_err(|e| e.to_string())?;
                Ok(k)
            }
            Keyring::SecretService => {
                // A key is made only when the keyring says plainly "not
                // found" (exit 1, nothing printed). Anything else (a locked
                // wallet, a failing bus, odd output) means "locked": the
                // key that may already be there is never overwritten.
                // The real tool also exits 1 on errors, but then it says why
                // on stderr: "not found" is exit 1 with nothing at all
                // printed. And with encrypted chats on disk, no key is ever
                // made (the old one would be lost).
                let (code, found, err) = run(&lookup_args(), None)?;
                match (code, found.trim(), err.trim()) {
                    (Some(0), t, _) => {
                        return decode(t)
                            .ok_or_else(|| "the keyring gave an odd answer".to_string());
                    }
                    (Some(1), "", "") if may_create => {}
                    _ => return Err("the keyring is locked".into()),
                }
                let k = fresh_key()?;
                let (code, _, _) = run(&store_args(), Some(&encode(&k)))?;
                if code != Some(0) {
                    return Err("the keyring didn't keep the key".into());
                }
                // Read back: only a key the keyring really keeps is used.
                let (code, back, _) = run(&lookup_args(), None)?;
                decode(back.trim())
                    .filter(|b| *b == k && code == Some(0))
                    .ok_or_else(|| "the keyring didn't keep the key".to_string())
            }
        }
    }
}

/// The keyring program (a check may name a stand-in).
fn tool() -> std::ffi::OsString {
    std::env::var_os("MIMIR_SECRET_TOOL").unwrap_or_else(|| "secret-tool".into())
}

/// Runs `secret-tool` with a time limit (a locked wallet may wait for its
/// password). The exit code and what it printed.
fn run(args: &[&str], input: Option<&str>) -> Result<(Option<i32>, String, String), String> {
    use std::io::Write;
    use std::process::{Command, Stdio};
    let mut c = Command::new(tool())
        .args(args)
        .stdin(if input.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|_| NO_PROGRAM.to_string())?;
    if let (Some(i), Some(mut w)) = (input, c.stdin.take()) {
        let _ = w.write_all(i.as_bytes());
    }
    let until = std::time::Instant::now() + std::time::Duration::from_secs(15);
    let code;
    loop {
        match c.try_wait() {
            Ok(Some(st)) => {
                code = st.code();
                break;
            }
            Ok(None) if std::time::Instant::now() < until => {
                std::thread::sleep(std::time::Duration::from_millis(50))
            }
            _ => {
                let _ = c.kill();
                let _ = c.wait();
                return Err("the keyring didn't answer".into());
            }
        }
    }
    let mut out = String::new();
    if let Some(mut o) = c.stdout.take() {
        use std::io::Read;
        let _ = o.read_to_string(&mut out);
    }
    let mut err = String::new();
    if let Some(mut e) = c.stderr.take() {
        use std::io::Read;
        let _ = e.read_to_string(&mut err);
    }
    Ok((code, out, err))
}

pub(crate) fn fresh_key() -> Result<[u8; 32], String> {
    use ring::rand::SecureRandom;
    let mut k = [0u8; 32];
    ring::rand::SystemRandom::new()
        .fill(&mut k)
        .map_err(|_| "no randomness".to_string())?;
    Ok(k)
}

pub(crate) fn encode(k: &[u8; 32]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.encode(k)
}

pub(crate) fn decode(t: &str) -> Option<[u8; 32]> {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD
        .decode(t.trim())
        .ok()?
        .try_into()
        .ok()
}

pub(crate) fn seal(key: &[u8; 32], plain: &[u8]) -> Result<Vec<u8>, String> {
    use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
    use ring::rand::SecureRandom;
    let mut n = [0u8; 12];
    ring::rand::SystemRandom::new()
        .fill(&mut n)
        .map_err(|_| "no randomness".to_string())?;
    let k = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).map_err(|_| "bad key")?);
    let mut data = plain.to_vec();
    k.seal_in_place_append_tag(Nonce::assume_unique_for_key(n), Aad::from(MAGIC), &mut data)
        .map_err(|_| "couldn't encrypt".to_string())?;
    let mut out = MAGIC.to_vec();
    out.extend_from_slice(&n);
    out.extend(data);
    Ok(out)
}

pub(crate) fn open_sealed(key: &[u8; 32], bytes: &[u8]) -> Option<Vec<u8>> {
    use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
    let rest = bytes.strip_prefix(MAGIC)?;
    if rest.len() < 12 + 16 {
        return None;
    }
    let (n, data) = rest.split_at(12);
    let k = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).ok()?);
    let mut data = data.to_vec();
    let plain = k
        .open_in_place(
            Nonce::try_assume_unique_for_key(n).ok()?,
            Aad::from(MAGIC),
            &mut data,
        )
        .ok()?;
    Some(plain.to_vec())
}

fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    crate::atomic::write(path, bytes)
}

fn lookup_args() -> Vec<&'static str> {
    let mut a = vec!["lookup"];
    a.extend(ATTRS);
    a
}

fn store_args() -> Vec<&'static str> {
    let mut a = vec!["store", "--label=Mímir chats"];
    a.extend(ATTRS);
    a
}
