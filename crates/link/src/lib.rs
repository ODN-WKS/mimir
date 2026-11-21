//! The phone link: an encrypted connection between a paired phone and the
//! PC's Mímir service, from anywhere, with no account or router
//! setup (iroh, with n0's relays when they are on).
//!
//! iroh and tokio stay inside this crate. The PC side ([`server::Server`])
//! hands connections, calls and pairing requests to the service over a
//! plain channel, the way `approvals::serve` does; the phone side
//! ([`client::Client`]) offers blocking calls for the phone core.

pub mod client;
pub mod server;

use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Pairing: one stream, the phone's request then the PC's answers.
pub const ALPN_PAIR: &[u8] = b"mimir/pair/1";
/// A paired phone's session: calls in, events out.
pub const ALPN_PHONE: &[u8] = b"mimir/phone/1";

/// The largest message either side accepts.
pub const MAX_MSG: usize = 4 * 1024 * 1024;
/// How long a pairing code works.
pub const CODE_LIFE: std::time::Duration = std::time::Duration::from_secs(10 * 60);
/// Wrong tries before a code is cancelled.
pub const CODE_TRIES: u32 = 5;
/// How long the PC waits for "Yes, they match".
pub const CONFIRM_WAIT: std::time::Duration = std::time::Duration::from_secs(2 * 60);

const CODE_PREFIX: &str = "mimir1";
static WORDS: &str = include_str!("words.txt");

/// The phone's first message on the pairing stream.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairRequest {
    /// What the PC shows: "Galaxy S25 Ultra".
    pub name: String,
    /// HMAC-SHA256(secret from the code, the phone's public key), as hex.
    pub hmac: String,
}

/// What the PC says during pairing: `Words`, then `Paired` or `Refused`
/// (or `Refused` at once).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum PairReply {
    /// The code is right: compare these with the PC's screen.
    Words { words: Vec<String> },
    /// The owner said "Yes, they match": the phone is paired.
    Paired { pc_name: String },
    /// Plain words: "the code was already used", "the code expired", …
    Refused { why: String },
}

/// The phone's first message on a session stream (QUIC shows a stream to
/// the other side only once something is written on it).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Greeting {
    pub link: u32,
}

/// A pairing code, as the QR code and "Copy code" carry it.
#[derive(Debug, Clone, PartialEq)]
pub struct Code {
    pub pc_id: String,
    pub secret: [u8; 32],
    pub pc_name: String,
}

impl Code {
    /// `mimir1:<PC key>:<secret, base32>:<PC name, percent-encoded>`.
    pub fn text(&self) -> String {
        format!(
            "{CODE_PREFIX}:{}:{}:{}",
            self.pc_id,
            data_encoding::BASE32_NOPAD.encode(&self.secret),
            pct_encode(&self.pc_name)
        )
    }

    pub fn parse(text: &str) -> Result<Code, String> {
        let bad = || "That isn't a Mímir pairing code.".to_string();
        let mut parts = text.trim().split(':');
        if parts.next() != Some(CODE_PREFIX) {
            return Err(bad());
        }
        let pc_id = parts.next().ok_or_else(bad)?.to_string();
        let secret = data_encoding::BASE32_NOPAD
            .decode(parts.next().ok_or_else(bad)?.as_bytes())
            .map_err(|_| bad())?;
        let secret: [u8; 32] = secret.try_into().map_err(|_| bad())?;
        let pc_name = pct_decode(parts.next().unwrap_or(""));
        if pc_id.is_empty() || parts.next().is_some() {
            return Err(bad());
        }
        Ok(Code {
            pc_id,
            secret,
            pc_name,
        })
    }
}

/// HMAC-SHA256(secret, phone key) as hex.
pub fn pair_hmac(secret: &[u8; 32], phone_key: &[u8]) -> String {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(secret).expect("any key length works");
    m.update(phone_key);
    data_encoding::HEXLOWER.encode(&m.finalize().into_bytes())
}

/// Checks a phone's HMAC in constant time.
pub fn pair_hmac_ok(secret: &[u8; 32], phone_key: &[u8], hex: &str) -> bool {
    let Ok(given) = data_encoding::HEXLOWER_PERMISSIVE.decode(hex.as_bytes()) else {
        return false;
    };
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(secret).expect("any key length works");
    m.update(phone_key);
    m.verify_slice(&given).is_ok()
}

/// The four words both screens show: the first 44 bits of
/// SHA-256(PC key ‖ phone key ‖ the code's secret), 11 bits per word, from
/// BIP-39's English list. The code is new for each pairing, so pairing the
/// same phone again shows other words.
pub fn four_words(pc_key: &[u8], phone_key: &[u8], code_secret: &[u8]) -> Vec<String> {
    let mut h = Sha256::new();
    h.update(pc_key);
    h.update(phone_key);
    h.update(code_secret);
    let d = h.finalize();
    let bits = u64::from_be_bytes(d[..8].try_into().expect("8 bytes"));
    let list: Vec<&str> = WORDS.lines().collect();
    (0..4)
        .map(|i| {
            let n = (bits >> (64 - 11 * (i + 1))) & 0x7ff;
            list[n as usize].to_string()
        })
        .collect()
}

fn pct_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~ ".contains(&b) {
            out.push(if b == b' ' { '+' } else { b as char });
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

fn pct_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'+' => out.push(b' '),
            b'%' if i + 2 < b.len() => {
                let hex = |c: u8| (c as char).to_digit(16);
                match (hex(b[i + 1]), hex(b[i + 2])) {
                    (Some(h), Some(l)) => {
                        out.push((h * 16 + l) as u8);
                        i += 2;
                    }
                    _ => out.push(b'%'),
                }
            }
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Writes one message: a 4-byte little-endian length, then JSON.
pub async fn write_json<W: AsyncWrite + Unpin, T: Serialize>(
    w: &mut W,
    msg: &T,
) -> std::io::Result<()> {
    let bytes = serde_json::to_vec(msg)?;
    if bytes.len() > MAX_MSG {
        return Err(std::io::Error::other("message too large"));
    }
    let mut buf = Vec::with_capacity(4 + bytes.len());
    buf.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
    buf.extend_from_slice(&bytes);
    w.write_all(&buf).await?;
    w.flush().await
}

/// Reads one message; `None` when the other side finished cleanly.
pub async fn read_json<R: AsyncRead + Unpin, T: for<'de> Deserialize<'de>>(
    r: &mut R,
) -> std::io::Result<Option<T>> {
    let mut len = [0u8; 4];
    match r.read_exact(&mut len).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u32::from_le_bytes(len) as usize;
    if len > MAX_MSG {
        return Err(std::io::Error::other("message too large"));
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await?;
    serde_json::from_slice(&buf)
        .map(Some)
        .map_err(std::io::Error::other)
}

/// A debug build's check may shorten a wait (pairing code life, confirm
/// wait) with an environment variable in milliseconds.
pub(crate) fn wait_from_env(var: &str, normal: std::time::Duration) -> std::time::Duration {
    if cfg!(debug_assertions)
        && let Some(ms) = std::env::var(var).ok().and_then(|v| v.parse::<u64>().ok())
    {
        return std::time::Duration::from_millis(ms);
    }
    normal
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_code_reads_back() {
        let c = Code {
            pc_id: "abc123".into(),
            secret: [7; 32],
            pc_name: "Gaming PC: upstairs 100%".into(),
        };
        let t = c.text();
        assert_eq!(
            t.matches(':').count(),
            3,
            "the name's ':' must be encoded: {t}"
        );
        assert_eq!(Code::parse(&t).unwrap(), c);
        assert!(Code::parse("budgetapp1:x:y").is_err());
        assert!(Code::parse("mimir1:abc:NOTBASE32!:pc").is_err());
    }

    #[test]
    fn the_hmac_checks_out_only_with_the_same_secret_and_key() {
        let h = pair_hmac(&[1; 32], b"phone");
        assert!(pair_hmac_ok(&[1; 32], b"phone", &h));
        assert!(!pair_hmac_ok(&[2; 32], b"phone", &h));
        assert!(!pair_hmac_ok(&[1; 32], b"other", &h));
        assert!(!pair_hmac_ok(&[1; 32], b"phone", "zz"));
    }

    #[test]
    fn four_words_are_stable_and_from_the_list() {
        assert_eq!(WORDS.lines().count(), 2048);
        let a = four_words(b"pc", b"phone", b"code");
        assert_eq!(a.len(), 4);
        assert_eq!(a, four_words(b"pc", b"phone", b"code"));
        assert_ne!(a, four_words(b"pc", b"other phone", b"code"));
        // The same phone pairing again (a new code) sees other words.
        assert_ne!(a, four_words(b"pc", b"phone", b"another code"));
        // All zero bits pick the first word four times.
        assert!(WORDS.lines().next() == Some("abandon"));
    }
}
