//! Web search for the phone's Mímir through the owner's own SearXNG on this
//! PC (owner, 2026-11-19). Only that one local address is ever asked, so the
//! phone can't use the PC as a proxy; the search words are passed on and
//! nothing of them is kept or logged.

use mimir_proto::WebResult;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::{Duration, Instant};

/// Where the owner's SearXNG listens (a podman quadlet, on this PC only).
pub const ADDRESS: &str = "127.0.0.1:8888";

/// The longest search a phone may ask for.
pub const MAX_QUERY: usize = 200;
/// The most results one search returns.
pub const MAX_RESULTS: u32 = 10;
/// Searches allowed in a minute, and the least time between two.
pub const PER_MINUTE: usize = 12;
pub const GAP: Duration = Duration::from_millis(800);

/// Keeps phone searches gentle: a few a minute, never two at once.
#[derive(Default)]
pub struct Gentle {
    times: Vec<Instant>,
}

impl Gentle {
    /// Whether a search may start `now`; it's counted when it may.
    pub fn allow(&mut self, now: Instant) -> Result<(), String> {
        self.times
            .retain(|t| now.saturating_duration_since(*t) < Duration::from_secs(60));
        if self.times.len() >= PER_MINUTE {
            return Err("Too many searches just now: try again in a minute.".into());
        }
        if self
            .times
            .last()
            .is_some_and(|t| now.saturating_duration_since(*t) < GAP)
        {
            return Err("One search at a time: try again in a moment.".into());
        }
        self.times.push(now);
        Ok(())
    }
}

/// The query as it goes in an address: letters and digits as they are,
/// everything else %-encoded.
fn encode(q: &str) -> String {
    let mut out = String::new();
    for b in q.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// Searches the SearXNG at `addr` (always this PC's own) for `query`.
pub fn search(addr: &str, query: &str, max: u32) -> Result<Vec<WebResult>, String> {
    let q: String = query
        .chars()
        .filter(|c| !c.is_control())
        .collect::<String>()
        .trim()
        .to_string();
    if q.is_empty() {
        return Err("Nothing to search for.".into());
    }
    if q.chars().count() > MAX_QUERY {
        return Err("That search is too long.".into());
    }
    let max = max.clamp(1, MAX_RESULTS) as usize;
    let body = get(addr, &format!("/search?format=json&q={}", encode(&q)))
        .map_err(|e| format!("Your PC's search isn't answering ({e})."))?;
    parse(&body, max)
}

/// The results in SearXNG's JSON, at most `max`, plain web pages only.
pub fn parse(body: &str, max: usize) -> Result<Vec<WebResult>, String> {
    let v: serde_json::Value = serde_json::from_str(body)
        .map_err(|_| "Your PC's search gave an odd answer.".to_string())?;
    let list = v["results"].as_array().cloned().unwrap_or_default();
    Ok(list
        .iter()
        .filter_map(|r| {
            let url = r["url"].as_str()?;
            if !(url.starts_with("https://") || url.starts_with("http://")) {
                return None;
            }
            Some(WebResult {
                title: r["title"].as_str().unwrap_or("").trim().to_string(),
                url: url.to_string(),
                snippet: r["content"].as_str().unwrap_or("").trim().to_string(),
                engines: r["engines"]
                    .as_array()
                    .map(|a| {
                        a.iter()
                            .filter_map(|e| e.as_str().map(str::to_string))
                            .collect()
                    })
                    .unwrap_or_default(),
            })
        })
        .take(max)
        .collect())
}

/// A plain GET to the local SearXNG; the body (Content-Length or chunked).
fn get(addr: &str, path: &str) -> Result<String, String> {
    let mut s = TcpStream::connect_timeout(
        &addr.parse().map_err(|_| "bad address".to_string())?,
        Duration::from_secs(2),
    )
    .map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(Duration::from_secs(12)))
        .map_err(|e| e.to_string())?;
    // One write: the whole request at once.
    s.write_all(
        format!("GET {path} HTTP/1.1\r\nHost: {addr}\r\nAccept: application/json\r\nConnection: close\r\n\r\n")
            .as_bytes(),
    )
    .map_err(|e| e.to_string())?;
    let mut raw = vec![];
    s.take(4_000_000)
        .read_to_end(&mut raw)
        .map_err(|e| e.to_string())?;
    let end = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or("no answer")?;
    let head = String::from_utf8_lossy(&raw[..end]).to_string();
    let status = head
        .split_whitespace()
        .nth(1)
        .and_then(|c| c.parse::<u16>().ok())
        .unwrap_or(0);
    if status != 200 {
        return Err(format!("it answered {status}"));
    }
    let body = &raw[end + 4..];
    let chunked = head.lines().any(|l| {
        l.to_ascii_lowercase().starts_with("transfer-encoding:")
            && l.to_ascii_lowercase().contains("chunked")
    });
    let bytes = if chunked {
        dechunk(body)?
    } else {
        body.to_vec()
    };
    String::from_utf8(bytes).map_err(|_| "not text".to_string())
}

fn dechunk(mut b: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = vec![];
    loop {
        let nl = b.windows(2).position(|w| w == b"\r\n").ok_or("bad chunk")?;
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
        if size == 0 {
            return Ok(out);
        }
        if b.len() < size {
            return Err("cut short".into());
        }
        out.extend_from_slice(&b[..size]);
        b = b.get(size + 2..).unwrap_or(&[]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    /// A stand-in SearXNG: answers one request with `reply`, and hands back what it was asked.
    fn fake(reply: &'static str) -> (String, std::thread::JoinHandle<String>) {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap().to_string();
        let h = std::thread::spawn(move || {
            let (mut c, _) = l.accept().unwrap();
            let mut got = vec![];
            let mut buf = [0u8; 4096];
            while !got.windows(4).any(|w| w == b"\r\n\r\n") {
                let n = c.read(&mut buf).unwrap();
                if n == 0 {
                    break;
                }
                got.extend_from_slice(&buf[..n]);
            }
            c.write_all(reply.as_bytes()).unwrap();
            String::from_utf8_lossy(&got).to_string()
        });
        (addr, h)
    }

    const JSON: &str = r#"{"query":"weather portland","results":[
        {"title":"Portland, CA Weather","url":"https://www.accuweather.com/portland","content":"Current conditions.","engines":["brave","google cse"]},
        {"title":"Not a page","url":"javascript:alert(1)","content":"x","engines":["brave"]},
        {"title":"NWS Portland","url":"https://forecast.weather.gov/portland","content":"Forecast.","engines":["duckduckgo"]}]}"#;

    #[test]
    fn asks_only_the_local_searxng_and_reads_its_results() {
        let reply = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{JSON}",
            JSON.len()
        );
        let (addr, h) = fake(Box::leak(reply.into_boxed_str()));
        let r = search(&addr, "weather portland & more", 5).unwrap();
        let asked = h.join().unwrap();
        assert!(asked.starts_with("GET /search?format=json&q=weather+portland+%26+more HTTP/1.1"));
        assert_eq!(r.len(), 2);
        assert_eq!(r[0].engines, vec!["brave", "google cse"]);
        assert_eq!(r[1].url, "https://forecast.weather.gov/portland");
    }

    #[test]
    fn a_chunked_answer_is_read_whole() {
        let reply = format!(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n{:x}\r\n{}\r\n{:x}\r\n{}\r\n0\r\n\r\n",
            10,
            &JSON[..10],
            JSON.len() - 10,
            &JSON[10..]
        );
        let (addr, h) = fake(Box::leak(reply.into_boxed_str()));
        assert_eq!(search(&addr, "weather portland", 1).unwrap().len(), 1);
        h.join().unwrap();
    }

    #[test]
    fn empty_long_and_failing_searches_are_refused_plainly() {
        assert!(search(ADDRESS, "  ", 5).is_err());
        assert!(search(ADDRESS, &"x".repeat(MAX_QUERY + 1), 5).is_err());
        let (addr, h) = fake("HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\n\r\n");
        assert!(search(&addr, "x", 5).unwrap_err().contains("429"));
        h.join().unwrap();
        // Nothing listening: said plainly, not a hang.
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let dead = l.local_addr().unwrap().to_string();
        drop(l);
        assert!(search(&dead, "x", 5).is_err());
    }

    #[test]
    fn searches_stay_gentle() {
        let mut g = Gentle::default();
        let t0 = Instant::now();
        assert!(g.allow(t0).is_ok());
        assert!(g.allow(t0 + Duration::from_millis(100)).is_err());
        for i in 1..PER_MINUTE {
            assert!(
                g.allow(t0 + GAP * i as u32 + Duration::from_millis(1))
                    .is_ok()
            );
        }
        assert!(g.allow(t0 + Duration::from_secs(30)).is_err());
        assert!(g.allow(t0 + Duration::from_secs(61)).is_ok());
    }

    #[test]
    fn a_phone_may_search() {
        let c = mimir_proto::Call::WebSearch {
            query: "x".into(),
            max: 3,
        };
        assert!(c.phone_may());
        let back: mimir_proto::Call =
            serde_json::from_str(r#"{"WebSearch":{"query":"x"}}"#).unwrap();
        assert_eq!(
            back,
            mimir_proto::Call::WebSearch {
                query: "x".into(),
                max: 5
            }
        );
    }
}

#[cfg(test)]
mod real {
    /// The owner's own SearXNG, when it's running (`cargo test -- --ignored`).
    #[test]
    #[ignore]
    fn the_pcs_own_searxng_answers() {
        let r = super::search(super::ADDRESS, "weather Portland", 5).unwrap();
        assert!(!r.is_empty());
        eprintln!("{r:#?}");
    }
}
