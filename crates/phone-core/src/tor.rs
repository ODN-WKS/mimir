//! Tor for a Whisper with internet (owner, 2026-11-19): the Tor project's own
//! Rust client (Arti), built in. It starts on the Whisper's first lookup and
//! stops when the Whisper ends. Only Tor's public list of relays is kept
//! between Whispers (the cache folder); its state (the chosen entry relays)
//! lives in a folder the app wipes each time. Lookups only read: one HTTPS
//! GET at a time, through Tor, with the site's name resolved by Tor (never by
//! the phone).

use arti_client::{TorClient, TorClientConfig, config::TorClientConfigBuilder};
use std::io::Write as _;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tor_rtcompat::PreferredRuntime;

/// The honest app name sites see, the same as without Tor.
pub const AGENT: &str = "Mimir (phone app; no personal data)";

pub struct Tor {
    rt: tokio::runtime::Runtime,
    client: Arc<TorClient<PreferredRuntime>>,
    cancel: Arc<tokio::sync::Notify>,
    stopped: AtomicBool,
}

impl Tor {
    /// A Tor client keeping its state in `state` and its relay list in `cache` (not connected yet).
    pub fn new(state: &str, cache: &str) -> Result<Tor, String> {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("tor")
            .build()
            .map_err(|e| e.to_string())?;
        let mut builder = TorClientConfigBuilder::from_directories(state, cache);
        // Android keeps an app's own folders private by itself; Arti's check of every folder above
        // them (owned by the system) would refuse to start.
        builder.storage().permissions().dangerously_trust_everyone();
        let config: TorClientConfig = builder.build().map_err(|e| e.to_string())?;
        let client = rt.block_on(async {
            let runtime = PreferredRuntime::current().map_err(|e| e.to_string())?;
            TorClient::with_runtime(runtime)
                .config(config)
                .create_unbootstrapped()
                .map_err(|e| e.to_string())
        })?;
        Ok(Tor {
            rt,
            client,
            cancel: Arc::new(tokio::sync::Notify::new()),
            stopped: AtomicBool::new(false),
        })
    }

    /// Connects to the Tor network: Ok once it can be used; an error after
    /// `timeout`, on [`Tor::cancel`], or when it can't.
    pub fn bootstrap(&self, timeout: Duration) -> Result<(), String> {
        let cancel = self.cancel.clone();
        self.rt.block_on(async {
            tokio::select! {
                r = tokio::time::timeout(timeout, self.client.bootstrap()) => match r {
                    Ok(Ok(())) => Ok(()),
                    Ok(Err(e)) => Err(format!("Couldn't reach Tor: {e}")),
                    Err(_) => Err("Couldn't reach Tor in time".into()),
                },
                _ = cancel.notified() => Err("Cancelled".into()),
            }
        })
    }

    /// How far connecting has got, 0 to 1.
    pub fn progress(&self) -> f32 {
        self.client.bootstrap_status().as_frac()
    }

    /// Stops what's waiting (connecting or a lookup).
    pub fn cancel(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        self.cancel.notify_waiters();
    }

    /// Reads `url` (HTTPS only) through Tor: the status, a redirect's address,
    /// the type and the body as text, as JSON; or `{"error"}`.
    pub fn get(
        &self,
        url: &str,
        headers: &[(String, String)],
        timeout: Duration,
        max: usize,
    ) -> serde_json::Value {
        let cancel = self.cancel.clone();
        let r = self.rt.block_on(async {
            tokio::select! {
                r = tokio::time::timeout(timeout, self.fetch(url, headers, max)) => r.unwrap_or_else(|_| Err("Tor took too long".into())),
                _ = cancel.notified() => Err("Cancelled".into()),
            }
        });
        match r {
            Ok(v) => v,
            Err(e) => serde_json::json!({ "error": e }),
        }
    }

    async fn fetch(
        &self,
        url: &str,
        headers: &[(String, String)],
        max: usize,
    ) -> Result<serde_json::Value, String> {
        let (host, port, path) = parse(url)?;
        let stream = self
            .client
            .connect((host.as_str(), port))
            .await
            .map_err(|e| format!("Tor couldn't reach {host}: {e}"))?;
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .with_root_certificates(roots)
        .with_no_client_auth();
        let name = rustls::pki_types::ServerName::try_from(host.clone())
            .map_err(|_| "bad site name".to_string())?;
        let mut s = tokio_rustls::TlsConnector::from(Arc::new(tls))
            .connect(name, stream)
            .await
            .map_err(|e| format!("{host}'s secure connection failed: {e}"))?;
        let mut req = Vec::new();
        write!(
            req,
            "GET {path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n"
        )
        .ok();
        for (k, v) in headers {
            if !k.contains(['\r', '\n', ':']) && !v.contains(['\r', '\n']) {
                write!(req, "{k}: {v}\r\n").ok();
            }
        }
        req.extend_from_slice(b"\r\n");
        s.write_all(&req).await.map_err(|e| e.to_string())?;
        s.flush().await.map_err(|e| e.to_string())?;
        let mut raw = Vec::new();
        let mut buf = [0u8; 16 * 1024];
        loop {
            if self.stopped.load(Ordering::SeqCst) {
                return Err("Cancelled".into());
            }
            match s.read(&mut buf).await {
                Ok(0) => break,
                Ok(n) => {
                    raw.extend_from_slice(&buf[..n]);
                    if raw.len() > max + 64 * 1024 {
                        return Err("the page is too big".into());
                    }
                }
                // Many sites end the TLS session without saying so: what came is kept.
                Err(e) if !raw.is_empty() && e.kind() == std::io::ErrorKind::UnexpectedEof => break,
                Err(e) => return Err(e.to_string()),
            }
        }
        response(&raw)
    }
}

/// "https://host[:port]/path?q" → (host, port, "/path?q"). Only HTTPS.
pub fn parse(url: &str) -> Result<(String, u16, String), String> {
    let rest = url
        .strip_prefix("https://")
        .ok_or("only secure (https) pages")?;
    let (hostport, path) = match rest.find(['/', '?', '#']) {
        Some(i) if rest.as_bytes()[i] == b'/' => (&rest[..i], rest[i..].to_string()),
        Some(i) => (&rest[..i], format!("/{}", &rest[i..])),
        None => (rest, "/".to_string()),
    };
    let path = path.split('#').next().unwrap_or("/").to_string();
    if hostport.contains('@') {
        return Err("no logins in addresses".into());
    }
    let (host, port) = match hostport.rsplit_once(':') {
        Some((h, p)) if !h.contains(']') || h.ends_with(']') => {
            (h.to_string(), p.parse::<u16>().map_err(|_| "bad port")?)
        }
        _ => (hostport.to_string(), 443),
    };
    if host.is_empty() {
        return Err("no site".into());
    }
    Ok((host.to_ascii_lowercase(), port, path))
}

/// An HTTP/1.1 answer as JSON: status, location, type and body text.
pub fn response(raw: &[u8]) -> Result<serde_json::Value, String> {
    let end = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or("no answer")?;
    let head = String::from_utf8_lossy(&raw[..end]).to_string();
    let status = head
        .split_whitespace()
        .nth(1)
        .and_then(|c| c.parse::<u16>().ok())
        .ok_or("odd answer")?;
    let header = |name: &str| {
        head.lines().skip(1).find_map(|l| {
            let (k, v) = l.split_once(':')?;
            k.trim()
                .eq_ignore_ascii_case(name)
                .then(|| v.trim().to_string())
        })
    };
    let body = &raw[end + 4..];
    let body = if header("transfer-encoding")
        .is_some_and(|v| v.to_ascii_lowercase().contains("chunked"))
    {
        dechunk(body)?
    } else {
        body.to_vec()
    };
    Ok(serde_json::json!({
        "status": status,
        "location": header("location"),
        "type": header("content-type").unwrap_or_default(),
        "body": String::from_utf8_lossy(&body),
    }))
}

fn dechunk(mut b: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = vec![];
    loop {
        let Some(nl) = b.windows(2).position(|w| w == b"\r\n") else {
            return Ok(out);
        };
        let size = usize::from_str_radix(
            String::from_utf8_lossy(&b[..nl])
                .split(';')
                .next()
                .unwrap_or("")
                .trim(),
            16,
        )
        .map_err(|_| "bad chunk")?;
        b = &b[nl + 2..];
        if size == 0 || b.len() < size {
            out.extend_from_slice(&b[..size.min(b.len())]);
            return Ok(out);
        }
        out.extend_from_slice(&b[..size]);
        b = b.get(size + 2..).unwrap_or(&[]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_https_addresses_and_no_logins() {
        assert_eq!(
            parse("https://lite.duckduckgo.com/lite/?q=x").unwrap(),
            ("lite.duckduckgo.com".into(), 443, "/lite/?q=x".into())
        );
        assert_eq!(
            parse("https://Example.org:8443").unwrap(),
            ("example.org".into(), 8443, "/".into())
        );
        assert_eq!(parse("https://example.org?q=1#top").unwrap().2, "/?q=1");
        assert!(parse("http://example.org/").is_err());
        assert!(parse("https://me:secret@example.org/").is_err());
    }

    #[test]
    fn answers_are_read_plain_or_chunked() {
        let v = response(
            b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 5\r\n\r\nhello",
        )
        .unwrap();
        assert_eq!(v["status"], 200);
        assert_eq!(v["type"], "text/html");
        assert_eq!(v["body"], "hello");
        let v = response(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nhel\r\n2\r\nlo\r\n0\r\n\r\n").unwrap();
        assert_eq!(v["body"], "hello");
        let v = response(b"HTTP/1.1 301 Moved\r\nLocation: https://example.org/b\r\n\r\n").unwrap();
        assert_eq!(v["location"], "https://example.org/b");
    }
}

#[cfg(test)]
mod real {
    /// Through the real Tor network (`cargo test -p mimir-phone-core -- --ignored --nocapture`).
    #[test]
    #[ignore]
    fn reads_a_page_through_tor() {
        let dir = std::env::temp_dir().join(format!("tor-check-{}", std::process::id()));
        let (state, cache) = (dir.join("state"), dir.join("cache"));
        let t = super::Tor::new(state.to_str().unwrap(), cache.to_str().unwrap()).unwrap();
        let start = std::time::Instant::now();
        t.bootstrap(std::time::Duration::from_secs(120)).unwrap();
        eprintln!("connected in {:?}", start.elapsed());
        let start = std::time::Instant::now();
        let v = t.get(
            "https://check.torproject.org/api/ip",
            &[("User-Agent".into(), super::AGENT.into())],
            std::time::Duration::from_secs(60),
            100_000,
        );
        eprintln!("read in {:?}: {v}", start.elapsed());
        assert!(v["body"].as_str().unwrap_or("").contains("\"IsTor\":true"));
        drop(t);
        let _ = std::fs::remove_dir_all(dir);
    }
}
