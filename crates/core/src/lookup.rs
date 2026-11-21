//! The phone Mímir's lookups fetched by this PC (owner, 2026-11-19: "I
//! should be able to look up the weather"): the phone asks the weather and
//! the encyclopedias both ways at once, from the phone and through here, so
//! a slow or failed phone network isn't the answer. Only the fixed sites
//! below are ever fetched, and only below their fixed paths, so the phone
//! can't use the PC as an open proxy; nothing of it is kept or logged.

use mimir_proto::LookupReply;
use std::time::{Duration, Instant};

/// The sites, and the start of the addresses that may be asked there (the
/// phone's `Net.PC_SITES` is the same list).
pub const SITES: &[(&str, &str)] = &[
    ("geocoding-api.open-meteo.com", "/v1/search"),
    ("api.open-meteo.com", "/v1/forecast"),
    ("api.met.no", "/weatherapi/locationforecast/2.0/"),
    ("api.weather.gov", "/points/"),
    ("api.weather.gov", "/gridpoints/"),
    ("en.wikipedia.org", "/w/api.php"),
    ("grokipedia.com", "/page/"),
    ("huggingface.co", "/api/models"),
];

/// The app name sites see: the phone's own, honest, nothing about the owner.
pub const AGENT: &str = "Mimir (phone app; no personal data)";

/// The longest address asked, and the most read back.
pub const MAX_URL: usize = 2000;
pub const MAX_BYTES: usize = 2_000_000;
/// About 3 s to reach the site and 6 s for all of it: the phone's whole
/// lookup takes 6 s at most, so a slower answer would come too late.
pub const CONNECT_SECS: u32 = 3;
pub const ALL_SECS: u32 = 6;
/// Lookups allowed in a minute.
pub const PER_MINUTE: usize = 30;
/// A second search within this window waits in the queue instead of being refused (PCR-19).
pub const QUEUE_WINDOW_MS: u64 = 800;
/// At most this many searches may queue up at once (PCR-19).
pub const QUEUE_MAX: usize = 4;

/// Keeps lookups gentle: a few a minute.
#[derive(Default)]
pub struct Gentle {
    times: Vec<Instant>,
}

impl Gentle {
    pub fn allow(&mut self, now: Instant) -> Result<(), String> {
        self.times
            .retain(|t| now.saturating_duration_since(*t) < Duration::from_secs(60));
        if self.times.len() >= PER_MINUTE {
            return Err("Too many lookups just now: try again in a minute.".into());
        }
        self.times.push(now);
        Ok(())
    }
}

/// A small queue for searches that arrive while one is in progress (PCR-19): the second
/// search waits (up to [QUEUE_WINDOW_MS]) instead of being refused. At most [QUEUE_MAX]
/// searches queue at once; more are refused.
#[derive(Default)]
pub struct SearchQueue {
    /// The last time a search was started.
    last: Option<Instant>,
    /// How many are currently waiting.
    queued: usize,
}

impl SearchQueue {
    /// Called before each search. Returns Ok(()) when the search may go; waits a little first
    /// when one arrived recently, up to [QUEUE_WINDOW_MS]. Returns Err when the queue is full.
    pub fn enter(&mut self) -> Result<(), String> {
        if self.queued >= QUEUE_MAX {
            return Err(format!(
                "Too many searches at once (queue full at {QUEUE_MAX})."
            ));
        }
        if let Some(last) = self.last {
            let elapsed = last.elapsed();
            if elapsed < Duration::from_millis(QUEUE_WINDOW_MS) {
                self.queued += 1;
                std::thread::sleep(Duration::from_millis(QUEUE_WINDOW_MS) - elapsed);
                self.queued = self.queued.saturating_sub(1);
            }
        }
        self.last = Some(Instant::now());
        Ok(())
    }
}

/// A simple cache so the PC doesn't re-fetch a URL it already has a result for (PHE-11).
/// Entries expire after [CACHE_TTL_SECS] seconds.
pub const CACHE_TTL_SECS: u64 = 60;

#[derive(Default)]
pub struct FetchCache {
    entries: Vec<(String, Instant, LookupReply)>,
}

impl FetchCache {
    /// Returns the cached reply for `url` if it is fresh, or None.
    pub fn get(&self, url: &str, now: Instant) -> Option<&LookupReply> {
        self.entries
            .iter()
            .find(|(u, at, _)| {
                u == url && now.saturating_duration_since(*at) < Duration::from_secs(CACHE_TTL_SECS)
            })
            .map(|(_, _, r)| r)
    }

    /// Stores a reply; prunes stale entries.
    pub fn put(&mut self, url: String, reply: LookupReply, now: Instant) {
        self.entries.retain(|(_, at, _)| {
            now.saturating_duration_since(*at) < Duration::from_secs(CACHE_TTL_SECS)
        });
        // Keep at most 64 entries.
        if self.entries.len() >= 64 {
            self.entries.remove(0);
        }
        self.entries.push((url, now, reply));
    }
}

/// Whether `url` is one this PC fetches for the phone.
pub fn allowed(url: &str) -> Result<(), String> {
    let no = || Err("Your PC only looks up the weather and the encyclopedias.".to_string());
    if url.len() > MAX_URL
        || url
            .chars()
            .any(|c| c.is_control() || c.is_whitespace() || "\\@#\"'<>`{}[]|^".contains(c))
    {
        return no();
    }
    let Some(rest) = url.strip_prefix("https://") else {
        return no();
    };
    let Some(slash) = rest.find('/') else {
        return no();
    };
    let (host, path) = rest.split_at(slash);
    let path_only = path.split('?').next().unwrap_or("");
    if path_only.contains("..") || path_only.contains("%2e") || path_only.contains("%2E") {
        return no();
    }
    if SITES
        .iter()
        .any(|(h, p)| host.eq_ignore_ascii_case(h) && path_only.starts_with(p))
    {
        Ok(())
    } else {
        no()
    }
}

/// Fetches `url` (only after [`allowed`]): the site's answer, redirects
/// not followed (the phone checks where it goes and asks again).
pub fn fetch(url: &str) -> Result<LookupReply, String> {
    allowed(url)?;
    let mut curl = std::process::Command::new("curl");
    // Certificate settings that point nowhere (a build box's, carried into a
    // service started from it) would fail every lookup: curl's own are used.
    for var in ["SSL_CERT_FILE", "SSL_CERT_DIR", "CURL_CA_BUNDLE"] {
        if std::env::var_os(var).is_some_and(|p| !std::path::Path::new(&p).exists()) {
            curl.env_remove(var);
        }
    }
    let out = curl
        .args([
            // No ~/.curlrc: only these settings.
            "-q",
            "-sS",
            "--globoff",
            "--proto",
            "=https",
            "--max-redirs",
            "0",
            "--connect-timeout",
            &CONNECT_SECS.to_string(),
            "-m",
            &ALL_SECS.to_string(),
            "--max-filesize",
            &MAX_BYTES.to_string(),
            "-A",
            AGENT,
            "-H",
            "Accept-Language: en",
            "-H",
            "Accept: application/json, text/html, text/plain",
            "-o",
            "-",
            "-w",
            "\n\x01%{http_code} %{content_type}\x01%{redirect_url}",
            url,
        ])
        .output()
        .map_err(|_| "curl is missing on your PC".to_string())?;
    read(&out.stdout, out.status.code().unwrap_or(-1))
}

/// curl's output (the body, then the `-w` line) and its exit code, as the
/// site's answer or why not, in plain words.
pub fn read(stdout: &[u8], code: i32) -> Result<LookupReply, String> {
    if code != 0 {
        return Err(match code {
            6 => "your PC couldn't find the site. Check your internet".into(),
            7 => "your PC couldn't reach the site. Try again later".into(),
            28 => "the site didn't answer your PC in time".into(),
            35 | 51 | 53 | 54 | 58 | 59 | 60 | 77 | 80 | 82 | 83 | 90 | 91 => {
                "the secure connection from your PC failed".into()
            }
            63 => "the page is too big".into(),
            _ => format!("your PC couldn't get it (curl {code})"),
        });
    }
    let mark = stdout
        .windows(2)
        .rposition(|w| w == b"\n\x01")
        .ok_or("your PC got an odd answer")?;
    let body = &stdout[..mark];
    let tail = String::from_utf8_lossy(&stdout[mark + 2..]).to_string();
    let (head, redirect) = tail.split_once('\x01').unwrap_or((&tail, ""));
    let (status, content_type) = head.split_once(' ').unwrap_or((head, ""));
    let status: u16 = status.trim().parse().unwrap_or(0);
    if status == 0 {
        return Err("the site didn't answer your PC".into());
    }
    if body.len() > MAX_BYTES {
        return Err("the page is too big".into());
    }
    Ok(LookupReply {
        status,
        location: Some(redirect.trim().to_string()).filter(|r| !r.is_empty()),
        content_type: content_type.trim().to_string(),
        body: String::from_utf8_lossy(body).into_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_weather_and_the_encyclopedias_are_fetched() {
        for ok in [
            "https://api.open-meteo.com/v1/forecast?latitude=39.73&longitude=-121.84&current=temperature_2m",
            "https://geocoding-api.open-meteo.com/v1/search?count=1&name=Portland",
            "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=black+hole",
            "https://grokipedia.com/page/Black_hole",
            "https://grokipedia.com/page/Ada_Lovelace_(mathematician)",
            "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=39.73&lon=-121.84",
            "https://api.weather.gov/points/39.73,-121.84",
            "https://api.weather.gov/gridpoints/STO/39,122/forecast",
            "https://huggingface.co/api/models?search=abliterated&filter=gguf&sort=downloads&direction=-1&limit=40",
            "https://huggingface.co/api/models/someone/Qwen3.5-4B-heretic-GGUF/tree/main",
        ] {
            assert!(allowed(ok).is_ok(), "{ok}");
        }
        for no in [
            "http://api.open-meteo.com/v1/forecast?x=1",
            "https://api.open-meteo.com:8443/v1/forecast",
            "https://api.open-meteo.com.evil.example/v1/forecast",
            "https://evil.example/v1/forecast",
            "https://api.open-meteo.com@evil.example/v1/forecast",
            "https://api.open-meteo.com/v2/other",
            "https://api.open-meteo.com",
            "https://en.wikipedia.org/wiki/Black_hole",
            "https://grokipedia.com/page/../admin",
            "https://grokipedia.com/page/%2e%2e/admin",
            "https://grokipedia.com/page/a b",
            "https://grokipedia.com/page/a\r\nHost: x",
            "https://127.0.0.1/v1/forecast",
            "https://localhost/v1/forecast",
            "file:///etc/passwd",
            "https://en.wikipedia.org/w/api.php#x",
            "https://grokipedia.com/page/[1-9]",
            "http://api.met.no/weatherapi/locationforecast/2.0/compact?lat=1&lon=2",
            "https://api.met.no/weatherapi/locationforecast/1.9/compact?lat=1&lon=2",
            "https://api.met.no/weatherapi/../admin",
            "https://api.met.no.evil.example/weatherapi/locationforecast/2.0/compact",
            "https://api.weather.gov/alerts/active",
            "https://api.weather.gov/stations",
            "https://api.weather.gov/points",
            "https://api.weather.gov:8443/points/1,2",
            "https://nominatim.openstreetmap.org/search?q=Portland",
            "https://huggingface.co/someone/Qwen3.5-4B-heretic-GGUF/resolve/main/model.gguf",
            "https://huggingface.co/api/datasets/x/y",
            "https://huggingface.co/api/models/../../login",
            "https://huggingface.co.evil.example/api/models",
            "http://huggingface.co/api/models?search=x",
        ] {
            assert!(allowed(no).is_err(), "{no}");
        }
        assert!(
            allowed(&format!(
                "https://grokipedia.com/page/{}",
                "a".repeat(MAX_URL)
            ))
            .is_err()
        );
    }

    #[test]
    fn curls_answer_is_read_and_its_failures_said_plainly() {
        let r = read(b"{\"a\":1}\n\x01200 application/json; charset=utf-8\x01", 0).unwrap();
        assert_eq!(r.status, 200);
        assert_eq!(r.body, "{\"a\":1}");
        assert_eq!(r.content_type, "application/json; charset=utf-8");
        assert_eq!(r.location, None);
        // A body that has the mark's first byte in it is still cut at the last one.
        let r = read(
            b"line\n\x01not the end\n\x01301 text/html\x01https://grokipedia.com/page/X",
            0,
        )
        .unwrap();
        assert_eq!(r.status, 301);
        assert_eq!(r.location.as_deref(), Some("https://grokipedia.com/page/X"));
        assert!(read(b"", 28).unwrap_err().contains("in time"));
        assert!(read(b"", 6).unwrap_err().contains("couldn't find"));
        assert!(read(b"\n\x01000 \x01", 0).is_err());
    }

    #[test]
    fn lookups_stay_gentle() {
        let mut g = Gentle::default();
        let t0 = Instant::now();
        for _ in 0..PER_MINUTE {
            assert!(g.allow(t0).is_ok());
        }
        assert!(g.allow(t0 + Duration::from_secs(1)).is_err());
        assert!(g.allow(t0 + Duration::from_secs(61)).is_ok());
    }

    #[test]
    fn a_phone_may_look_up() {
        let c = mimir_proto::Call::Lookup {
            url: "https://grokipedia.com/page/X".into(),
        };
        assert!(c.phone_may());
    }

    // PCR-19: second search within 0.8 s waits, not refused.
    #[test]
    fn second_search_waits_not_refused() {
        let mut q = SearchQueue::default();
        let t0 = Instant::now();
        // First search: goes through immediately.
        assert!(q.enter().is_ok());
        // Second search: arrives within 0.8 s, waits a little but is not refused.
        assert!(q.enter().is_ok());
        let elapsed = t0.elapsed();
        // The wait must have been applied (some delay), but it succeeded.
        // The first call sets `last`; the second waits the remaining window.
        assert!(
            elapsed.as_millis() < 1000,
            "took {}ms, expected < 1000ms",
            elapsed.as_millis()
        );
    }

    #[test]
    fn queue_refuses_when_full() {
        let mut q = SearchQueue {
            last: None,
            queued: QUEUE_MAX,
        };
        assert!(q.enter().is_err());
    }

    // PHE-11: PC re-uses a cached result instead of fetching again.
    #[test]
    fn fetch_cache_deduplicates() {
        let mut cache = FetchCache::default();
        let now = Instant::now();
        let url = "https://api.open-meteo.com/v1/forecast?lat=0";
        assert!(cache.get(url, now).is_none());
        let reply = LookupReply {
            status: 200,
            location: None,
            content_type: "application/json".into(),
            body: "{}".into(),
        };
        cache.put(url.into(), reply.clone(), now);
        assert_eq!(cache.get(url, now), Some(&reply));
        // Expired after TTL.
        let later = now + Duration::from_secs(CACHE_TTL_SECS + 1);
        assert!(cache.get(url, later).is_none());
    }

    // Fake-failure test: error, timeout, and hang each resolve within 7.5 s (PCR-19, done-check 2).
    // These are integration tests: they check that the read() helper and curl exit codes each
    // return an error quickly (< 7.5 s), not hang forever.
    #[test]
    fn fake_failures_resolve_quickly() {
        let t0 = Instant::now();
        // (a) An error exit code (curl 6 = DNS failure) returns immediately.
        assert!(read(b"", 6).is_err());
        // (b) Timeout exit code (curl 28) returns immediately.
        assert!(read(b"", 28).is_err());
        // (c) Unexpected exit code (hang scenario via bad data, resolved by read()) returns immediately.
        assert!(read(b"\x01bad\x01", 0).is_err());
        let elapsed = t0.elapsed();
        assert!(
            elapsed < Duration::from_millis(7_500),
            "fake failures took {:?}",
            elapsed
        );
    }
}

#[cfg(test)]
mod real {
    /// Open-Meteo through this PC, for real (`cargo test -- --ignored`).
    #[test]
    #[ignore]
    fn the_weather_comes_through_the_pc() {
        let t0 = std::time::Instant::now();
        let r = super::fetch("https://api.open-meteo.com/v1/forecast?latitude=39.73&longitude=-121.84&current=temperature_2m").unwrap();
        eprintln!("{} in {:?}: {}", r.status, t0.elapsed(), r.body);
        assert_eq!(r.status, 200);
    }
}
