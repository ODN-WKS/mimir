//! Talking to the PC's own Ollama straight over HTTP (no curl, no new
//! crates), the same way `searx.rs` talks to SearXNG: a GET for facts
//! (`/api/ps`, `/api/version`) and a streamed POST whose answer comes as
//! one JSON object a line (`/api/chat`). Only a plain `http://` address is
//! accepted: anything else is refused rather than half-working.

use serde_json::Value;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

/// `host:port` of the Ollama in `models::ollama_url()`, or why it can't be
/// used.
pub fn address() -> Result<String, String> {
    address_of(&crate::models::ollama_url())
}

fn address_of(url: &str) -> Result<String, String> {
    let rest = url.trim().strip_prefix("http://").ok_or_else(|| {
        format!("Mímir talks to its models only at a plain local http:// address, not {url}.")
    })?;
    let hostport = rest.trim_end_matches('/');
    if hostport.is_empty() || hostport.contains('/') || hostport.contains('@') {
        return Err(format!(
            "{url} isn't an address Mímir can use for its models."
        ));
    }
    Ok(if hostport.contains(':') {
        hostport.to_string()
    } else {
        format!("{hostport}:80")
    })
}

fn connect(addr: &str, read: Duration) -> Result<TcpStream, String> {
    let a = addr
        .to_socket_addrs()
        .map_err(|e| e.to_string())?
        .next()
        .ok_or("no address")?;
    let s = TcpStream::connect_timeout(&a, Duration::from_secs(2)).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(read)).map_err(|e| e.to_string())?;
    let _ = s.set_nodelay(true);
    Ok(s)
}

/// Reads the head of an answer: its status and whether its body is chunked.
fn head(r: &mut BufReader<TcpStream>) -> Result<(u16, bool, Option<usize>), String> {
    let mut status = 0;
    let mut chunked = false;
    let mut length = None;
    let mut first = true;
    loop {
        let mut line = String::new();
        if r.read_line(&mut line).map_err(|e| e.to_string())? == 0 {
            return Err("no answer".into());
        }
        let l = line.trim_end();
        if first {
            status = l
                .split_whitespace()
                .nth(1)
                .and_then(|c| c.parse().ok())
                .unwrap_or(0);
            first = false;
            continue;
        }
        if l.is_empty() {
            return Ok((status, chunked, length));
        }
        let lower = l.to_ascii_lowercase();
        if lower.starts_with("transfer-encoding:") && lower.contains("chunked") {
            chunked = true;
        }
        if let Some(n) = lower.strip_prefix("content-length:") {
            length = n.trim().parse().ok();
        }
    }
}

/// A GET whose answer is JSON.
pub fn get(path: &str) -> Result<Value, String> {
    get_at(&crate::models::ollama_url(), path)
}

/// A GET to the Ollama at `base`.
pub fn get_at(base: &str, path: &str) -> Result<Value, String> {
    let addr = address_of(base)?;
    let mut s = connect(&addr, Duration::from_secs(5))?;
    s.write_all(
        format!("GET {path} HTTP/1.1\r\nHost: {addr}\r\nConnection: close\r\n\r\n").as_bytes(),
    )
    .map_err(|e| e.to_string())?;
    let mut body = Body::open(s)?;
    let mut text = String::new();
    while let Some(piece) = body.next_bytes()? {
        text.push_str(&String::from_utf8_lossy(&piece));
        if text.len() > 4_000_000 {
            return Err("too big".into());
        }
    }
    serde_json::from_str(&text)
        .map_err(|_| "The free model gave an answer Mímir could not read. Try again.".into())
}

/// An answer's body, read piece by piece (chunked or not).
struct Body {
    r: BufReader<TcpStream>,
    chunked: bool,
    left: Option<usize>,
    done: bool,
}

impl Body {
    fn open(s: TcpStream) -> Result<Body, String> {
        let mut r = BufReader::new(s);
        let (status, chunked, left) = head(&mut r)?;
        let mut b = Body {
            r,
            chunked,
            left,
            done: false,
        };
        if status != 200 {
            // Ollama says why in a JSON `error`.
            let mut text = String::new();
            while let Ok(Some(p)) = b.next_bytes() {
                text.push_str(&String::from_utf8_lossy(&p));
                if text.len() > 64_000 {
                    break;
                }
            }
            let why = serde_json::from_str::<Value>(&text)
                .ok()
                .and_then(|v| v["error"].as_str().map(str::to_string))
                .unwrap_or_else(|| format!("it answered {status}"));
            return Err(why);
        }
        Ok(b)
    }

    fn next_bytes(&mut self) -> Result<Option<Vec<u8>>, String> {
        if self.done {
            return Ok(None);
        }
        if self.chunked {
            let mut line = String::new();
            if self.r.read_line(&mut line).map_err(|e| e.to_string())? == 0 {
                self.done = true;
                return Ok(None);
            }
            let size = usize::from_str_radix(line.split(';').next().unwrap_or("").trim(), 16)
                .map_err(|_| "bad chunk".to_string())?;
            if size == 0 {
                self.done = true;
                return Ok(None);
            }
            let mut buf = vec![0; size];
            self.r.read_exact(&mut buf).map_err(|e| e.to_string())?;
            let mut crlf = [0; 2];
            self.r.read_exact(&mut crlf).map_err(|e| e.to_string())?;
            return Ok(Some(buf));
        }
        let want = self.left.map_or(8192, |l| l.min(8192));
        if want == 0 {
            self.done = true;
            return Ok(None);
        }
        let mut buf = vec![0; want];
        let n = self.r.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            self.done = true;
            return Ok(None);
        }
        buf.truncate(n);
        if let Some(l) = &mut self.left {
            *l -= n;
        }
        Ok(Some(buf))
    }
}

/// A streamed answer: one JSON object a line.
pub struct Stream {
    body: Body,
    pending: Vec<u8>,
    closer: Option<TcpStream>,
}

impl Stream {
    /// Something another thread can use to end the stream at once (Ollama
    /// stops generating when the connection closes).
    pub fn closer(&mut self) -> Option<TcpStream> {
        self.closer.take()
    }

    /// The next JSON line, None at the end.
    pub fn next_json(&mut self) -> Result<Option<Value>, String> {
        loop {
            if let Some(i) = self.pending.iter().position(|b| *b == b'\n') {
                let line: Vec<u8> = self.pending.drain(..=i).collect();
                let t = String::from_utf8_lossy(&line);
                let t = t.trim();
                if t.is_empty() {
                    continue;
                }
                let v: Value = serde_json::from_str(t).map_err(|_| {
                    "The free model gave an answer Mímir could not read. Try again.".to_string()
                })?;
                if let Some(e) = v["error"].as_str() {
                    return Err(e.to_string());
                }
                return Ok(Some(v));
            }
            match self.body.next_bytes()? {
                Some(b) => self.pending.extend(b),
                None if self.pending.iter().any(|b| !b.is_ascii_whitespace()) => {
                    self.pending.push(b'\n');
                }
                None => return Ok(None),
            }
        }
    }
}

/// POSTs `body` to `path` and returns its streamed answer. `read` is the
/// longest wait for the next piece (a model loading takes a while).
pub fn post_stream(path: &str, body: &Value, read: Duration) -> Result<Stream, String> {
    post_stream_at(&crate::models::ollama_url(), path, body, read)
}

/// A streamed POST to the Ollama at `base`.
pub fn post_stream_at(
    base: &str,
    path: &str,
    body: &Value,
    read: Duration,
) -> Result<Stream, String> {
    let addr = address_of(base)?;
    let mut s = connect(&addr, read)?;
    let data = body.to_string();
    s.write_all(
        format!(
            "POST {path} HTTP/1.1\r\nHost: {addr}\r\nContent-Type: application/json\r\n\
             Content-Length: {}\r\nConnection: close\r\n\r\n{data}",
            data.len()
        )
        .as_bytes(),
    )
    .map_err(|e| e.to_string())?;
    let closer = s.try_clone().ok();
    Ok(Stream {
        body: Body::open(s)?,
        pending: vec![],
        closer,
    })
}

/// Ollama's version, e.g. "0.40.16".
pub fn version(base: &str) -> Option<String> {
    get_at(base, "/api/version").ok()?["version"]
        .as_str()
        .map(str::to_string)
}

/// Whether `model` can look at pictures (`/api/show` lists "vision" among
/// its capabilities). False when Ollama can't say.
pub fn sees_pictures(base: &str, model: &str) -> bool {
    post_stream_at(
        base,
        "/api/show",
        &serde_json::json!({"model": model}),
        Duration::from_secs(5),
    )
    .ok()
    .and_then(|mut s| s.next_json().ok().flatten())
    .and_then(|v| v["capabilities"].as_array().cloned())
    .is_some_and(|c| c.iter().any(|x| x.as_str() == Some("vision")))
}

/// The loaded models (`/api/ps`): name, size, and how much of it is on the
/// graphics card.
pub fn loaded(base: &str) -> Vec<(String, u64, u64)> {
    get_at(base, "/api/ps")
        .ok()
        .and_then(|v| v["models"].as_array().cloned())
        .unwrap_or_default()
        .iter()
        .filter_map(|m| {
            Some((
                m["name"].as_str()?.to_string(),
                m["size"].as_u64().unwrap_or(0),
                m["size_vram"].as_u64().unwrap_or(0),
            ))
        })
        .collect()
}

#[cfg(test)]
pub mod fake {
    //! A stand-in Ollama for checks: answers each request from a function.
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::TcpListener;

    /// Starts it; `answer(path, body)` gives the JSON lines to send.
    pub fn start(answer: impl Fn(&str, &str) -> Vec<String> + Send + Sync + 'static) -> String {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap().to_string();
        let answer = std::sync::Arc::new(answer);
        std::thread::spawn(move || {
            for c in l.incoming().flatten() {
                let answer = answer.clone();
                std::thread::spawn(move || {
                    let mut r = BufReader::new(c.try_clone().unwrap());
                    let mut first = String::new();
                    r.read_line(&mut first).unwrap_or(0);
                    let path = first.split_whitespace().nth(1).unwrap_or("").to_string();
                    let mut len = 0;
                    loop {
                        let mut l = String::new();
                        if r.read_line(&mut l).unwrap_or(0) == 0 || l.trim().is_empty() {
                            break;
                        }
                        if let Some(n) = l.to_ascii_lowercase().strip_prefix("content-length:") {
                            len = n.trim().parse().unwrap_or(0);
                        }
                    }
                    let mut body = vec![0; len];
                    let _ = r.read_exact(&mut body);
                    let lines = answer(&path, &String::from_utf8_lossy(&body));
                    let mut w = c;
                    let _ = w.write_all(
                        b"HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nTransfer-Encoding: chunked\r\n\r\n",
                    );
                    for line in lines {
                        if line == "<pause>" {
                            std::thread::sleep(std::time::Duration::from_millis(50));
                            continue;
                        }
                        let data = format!("{line}\n");
                        if w.write_all(format!("{:x}\r\n{data}\r\n", data.len()).as_bytes())
                            .is_err()
                        {
                            return;
                        }
                        let _ = w.flush();
                    }
                    let _ = w.write_all(b"0\r\n\r\n");
                });
            }
        });
        format!("http://{addr}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_plain_http_address_is_used() {
        assert_eq!(
            address_of("http://127.0.0.1:11434").unwrap(),
            "127.0.0.1:11434"
        );
        assert_eq!(address_of("http://localhost/").unwrap(), "localhost:80");
        assert!(address_of("https://127.0.0.1:11434").is_err());
        assert!(address_of("unix:///run/ollama.sock").is_err());
        assert!(address_of("http://user@host:1").is_err());
    }
}
