//! Takes anything private out of a line before it goes into a problem
//! report: message text, chat titles, names, email addresses, phone
//! numbers, IP addresses, the query part of web addresses, file paths, keys
//! and tokens, device numbers and the PC's name. It cuts too much on
//! purpose: a report that says `<name>` too often is better than one that
//! says a name once.
//!
//! The phone has the same function in Kotlin (`labs/Scrub.kt`) with the
//! same rules and the same test lines: change both together.

use regex::{Captures, Regex};
use std::sync::OnceLock;

pub const MESSAGE: &str = "<message removed>";

struct Rules {
    email: Regex,
    url: Regex,
    file_url: Regex,
    path: Regex,
    win_path: Regex,
    mac: Regex,
    ipv4: Regex,
    ipv6_full: Regex,
    ipv6_short: Regex,
    uuid: Regex,
    token: Regex,
    bearer: Regex,
    secret_pair: Regex,
    id_pair: Regex,
    name_pair: Regex,
    place_pair: Regex,
    text_pair: Regex,
    json_text: Regex,
    long_hex: Regex,
    long_blob: Regex,
    long_digits: Regex,
    phone: Regex,
    chat_line: Regex,
    date: Regex,
    userinfo: Regex,
}

fn re(p: &str) -> Regex {
    Regex::new(p).unwrap_or_else(|e| panic!("scrub pattern {p}: {e}"))
}

fn rules() -> &'static Rules {
    static R: OnceLock<Rules> = OnceLock::new();
    R.get_or_init(|| Rules {
        email: re(r"[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+"),
        url: re(r#"(?i)\b(?:https?|wss?|ftp)://[^\s<>"')\]]+"#),
        file_url: re(r"(?i)\bfile://\S*"),
        path: re(
            r#"(^|[^\w/.:~-])(/(?:home|storage|data|sdcard|Users|root|mnt|run|tmp|var|proc|opt|media|Download|Android)(?:/[^\s'",;)\]}]*)?)"#,
        ),
        win_path: re(r#"(?i)(^|\W)([a-z]:\\[^\s'",;)\]}]+)"#),
        mac: re(r"(?i)(^|[^\w:])((?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})($|[^\w:])"),
        ipv4: re(
            r"(^|[^\w.])((?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d))($|\W)",
        ),
        ipv6_full: re(r"(?i)(^|[^\w:])((?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4})($|[^\w:])"),
        ipv6_short: re(
            r"(?i)(^|[^\w:])((?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?)($|[^\w:])",
        ),
        uuid: re(r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"),
        token: re(
            r"\b(?:sk-[A-Za-z0-9_-]{6,}|gsk_[A-Za-z0-9]{6,}|ghp_[A-Za-z0-9]{6,}|gho_[A-Za-z0-9]{6,}|github_pat_[A-Za-z0-9_]{6,}|xox[abprs]-[A-Za-z0-9-]{6,}|AIza[A-Za-z0-9_-]{10,}|AKIA[A-Z0-9]{12,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_.-]+)",
        ),
        bearer: re(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}"),
        secret_pair: re(
            r#"(?i)\b((?:api[_-]?key|apikey|key|secret|token|access[_-]?token|auth|authorization|password|passwd|pwd|pass|passphrase|cookie|session)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)"#,
        ),
        id_pair: re(
            r#"(?i)\b((?:serial(?:[_ -]?(?:no|number))?|ro\.serialno|imei|imsi|meid|android[_-]?id|ssaid|device[_-]?id|iccid|advertising[_-]?id)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)"#,
        ),
        name_pair: re(
            r#"(?i)\b((?:title|chat[_ -]?title|pc[_-]?name|phone[_-]?name|device[_-]?name|user[_-]?name|username|user|owner|nickname|project[_-]?name|project|folder|host[_-]?name|hostname|host|ssid|bssid|account|email|e-mail|contact|author)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)"#,
        ),
        place_pair: re(
            r#"(?i)\b((?:lat|lon|lng|latitude|longitude|place|city|town|location|address|postcode|zip)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)"#,
        ),
        text_pair: re(
            r#"(?i)\b((?:text|message|msg|prompt|body|content|query|question|answer|reply|transcript)\s*[=:]\s*)("(?:[^"\\]|\\.)*"|'[^']*'|\S.*)"#,
        ),
        json_text: re(
            r#"(?i)("(?:text|message|content|prompt|body|title|name|query|question|answer|reply|transcript|user|owner|email)"\s*:\s*)"(?:[^"\\]|\\.)*""#,
        ),
        long_hex: re(r"(?i)(^|\W)([0-9a-f]{16,})($|\W)"),
        long_blob: re(r"(^|\W)([A-Za-z0-9+/_-]{28,}={0,2})($|\W)"),
        long_digits: re(r"(^|[^\w.])(\d{14,})($|\W)"),
        phone: re(r"(^|[^\w.:/-])(\+?\(?\d[\d ()\-.]{6,}\d)($|[^\w:])"),
        chat_line: re(r"^\s*CHAT\b"),
        date: re(r"^\d{4}-\d{2}-\d{2}$"),
        userinfo: re(r"(?i)^([a-z]+://)[^/@]*@"),
    })
}

/// Replaces the middle group of a bounded match, keeping both edges.
fn mid(re: &Regex, s: &str, with: &str) -> String {
    mid_if(re, s, with, |_| true)
}

/// Same, when the middle must also pass `ok`.
fn mid_if(re: &Regex, s: &str, with: &str, ok: impl Fn(&str) -> bool) -> String {
    // Twice: a match uses up the edge its neighbour needs.
    let mut out = s.to_string();
    for _ in 0..2 {
        out = re
            .replace_all(&out, |c: &Captures| {
                if ok(&c[2]) {
                    format!("{}{}{}", &c[1], with, &c[3])
                } else {
                    c[0].to_string()
                }
            })
            .into_owned();
    }
    out
}

fn keep_first(re: &Regex, s: &str, with: &str) -> String {
    re.replace_all(s, |c: &Captures| format!("{}{}", &c[1], with))
        .into_owned()
}

/// A web address without its user, query and fragment.
fn strip_query(url: &str) -> String {
    let body = url.trim_end_matches(['.', ',', ';', ':', '!']);
    let tail = &url[body.len()..];
    let u = body.split('?').next().unwrap_or("");
    let u = u.split('#').next().unwrap_or("");
    let u = rules().userinfo.replace(u, "$1");
    format!("{u}{tail}")
}

fn looks_like_phone(text: &str) -> bool {
    let digits = text.chars().filter(char::is_ascii_digit).count();
    if !(9..=15).contains(&digits) {
        return false;
    }
    if rules().date.is_match(text.trim()) {
        return false;
    }
    let plain = text.chars().all(|c| c.is_ascii_digit());
    !plain || (10..=11).contains(&digits)
}

fn has_digit_and_letter(s: &str) -> bool {
    s.chars().any(|c| c.is_ascii_digit()) && s.chars().any(|c| c.is_ascii_alphabetic())
}

/// One line, scrubbed. `names` are words to hide wherever they appear (the
/// PC's name, chat titles, project names).
pub fn line(raw: &str, names: &[String]) -> String {
    let r = rules();
    if r.chat_line.is_match(raw) {
        return format!("CHAT {MESSAGE}");
    }
    let mut s = raw.to_string();
    s = r.file_url.replace_all(&s, "<path>").into_owned();
    s = r
        .url
        .replace_all(&s, |c: &Captures| strip_query(&c[0]))
        .into_owned();
    s = r
        .bearer
        .replace_all(&s, |c: &Captures| format!("{} <key>", &c[1]))
        .into_owned();
    s = keep_first(&r.json_text, &s, "\"<removed>\"");
    s = keep_first(&r.text_pair, &s, MESSAGE);
    s = keep_first(&r.id_pair, &s, "<id>");
    s = keep_first(&r.secret_pair, &s, "<key>");
    s = keep_first(&r.name_pair, &s, "<name>");
    s = keep_first(&r.place_pair, &s, "<place>");
    s = r.email.replace_all(&s, "<email>").into_owned();
    s = r.token.replace_all(&s, "<key>").into_owned();
    s = r
        .win_path
        .replace_all(&s, |c: &Captures| format!("{}<path>", &c[1]))
        .into_owned();
    s = r
        .path
        .replace_all(&s, |c: &Captures| format!("{}<path>", &c[1]))
        .into_owned();
    s = r.uuid.replace_all(&s, "<id>").into_owned();
    s = mid(&r.mac, &s, "<id>");
    s = mid(&r.ipv4, &s, "<ip>");
    s = mid(&r.ipv6_full, &s, "<ip>");
    s = mid(&r.ipv6_short, &s, "<ip>");
    s = mid_if(&r.long_hex, &s, "<id>", has_hex_mix);
    s = mid_if(&r.long_blob, &s, "<key>", has_digit_and_letter);
    s = mid(&r.long_digits, &s, "<id>");
    s = mid_if(&r.phone, &s, "<phone>", looks_like_phone);
    let mut hide: Vec<&str> = names
        .iter()
        .map(|n| n.trim())
        .filter(|n| n.chars().count() >= 2)
        .collect();
    hide.sort_by_key(|n| std::cmp::Reverse(n.chars().count()));
    for n in hide {
        if let Ok(rx) = Regex::new(&format!("(?i){}", regex::escape(n))) {
            s = rx.replace_all(&s, "<name>").into_owned();
        }
    }
    s
}

/// Hex with at least one digit and one a-f letter (so a long word isn't cut).
fn has_hex_mix(s: &str) -> bool {
    s.chars().any(|c| c.is_ascii_digit())
        && s.chars()
            .any(|c| matches!(c.to_ascii_lowercase(), 'a'..='f'))
}

/// The newest `max` lines worth keeping from `raw`, scrubbed; lines of chat
/// text are dropped, blank ones too.
pub fn lines(raw: &[String], names: &[String], max: usize) -> Vec<String> {
    let r = rules();
    let kept: Vec<&String> = raw
        .iter()
        .filter(|l| !l.trim().is_empty() && !r.chat_line.is_match(l))
        .collect();
    let from = kept.len().saturating_sub(max);
    kept[from..]
        .iter()
        .map(|l| line(l, names).chars().take(400).collect())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn gone(l: &str, secrets: &[&str]) {
        gone_with(l, secrets, &[]);
    }

    fn gone_with(l: &str, secrets: &[&str], names: &[&str]) {
        let names: Vec<String> = names.iter().map(|s| s.to_string()).collect();
        let out = line(l, &names);
        for s in secrets {
            assert!(!out.contains(s), "'{s}' still in: {out}");
        }
    }

    fn kept(l: &str, words: &[&str]) {
        let out = line(l, &[]);
        for w in words {
            assert!(out.contains(w), "'{w}' missing from: {out}");
        }
    }

    #[test]
    fn emails_go() {
        gone(
            "sent to jane.doe+work@example.co.uk now",
            &["jane", "example.co.uk"],
        );
        gone("owner=\"bob_smith@mail.example.com\"", &["bob_smith"]);
    }

    #[test]
    fn addresses_go() {
        gone("connect to 10.20.30.40:5555 failed", &["10.20.30.40"]);
        gone("GET http://10.0.0.5:8080/api", &["10.0.0.5"]);
        gone(
            "peer 2001:0db8:85a3:0000:0000:8a2e:0370:7334 up",
            &["2001:0db8"],
        );
        gone("peer fe80::1ff:fe23:4567:890a up", &["fe80"]);
        gone("listening on ::1 port 5", &["::1"]);
        gone("wifi mac 3C:5A:B4:12:9F:01 joined", &["3C:5A:B4"]);
    }

    #[test]
    fn phone_numbers_go() {
        gone("call +44 7700 900123 failed", &["7700 900123"]);
        gone("number (555) 123-4567 saved", &["123-4567"]);
        gone("number 07700900123 saved", &["07700900123"]);
    }

    #[test]
    fn urls_lose_query_and_user() {
        gone(
            "opened https://example.com/search?q=my+secret+thing&user=bob#top",
            &["secret", "bob", "#top"],
        );
        kept(
            "opened https://example.com/wiki/Moon?x=1",
            &["https://example.com/wiki/Moon"],
        );
        gone("https://alice:hunter2@example.com/x", &["alice", "hunter2"]);
    }

    #[test]
    fn paths_go() {
        gone(
            "wrote /home/alex/Projects/Garden planner/notes.txt ok",
            &["alex", "Garden"],
        );
        gone(
            "read /storage/emulated/0/Download/taxes.pdf",
            &["taxes", "emulated"],
        );
        gone(
            "open /data/user/0/io.github.odnwks.mimir.dev/files/link.vault",
            &["link.vault"],
        );
        gone("saved to /sdcard/DCIM/me.jpg", &["DCIM"]);
        gone("opened C:\\Users\\Someone\\Documents\\a.txt", &["Someone"]);
        gone("opened file:///home/alex/x.txt", &["alex"]);
    }

    #[test]
    fn keys_go() {
        gone(
            "key sk-ant-api03-AbCdEfGhIjKlMnOpQrStUv failed",
            &["AbCdEf"],
        );
        gone("using gsk_9aB8cD7eF6gH5iJ4kL3m", &["9aB8cD"]);
        gone(
            &format!("{}abcdefghijklmnopqrstuvwxyz0123456789", "ghp_"),
            &["abcdefghij"],
        );
        gone("Authorization: Bearer abc123.def456.ghi789", &["abc123"]);
        gone("api_key=Zx81QpLm0912 more", &["Zx81QpLm0912"]);
        gone("password: correct-horse-battery", &["correct-horse"]);
        gone(
            "hash 3f786850e387550fdab836ed7e6dc881de23001b done",
            &["3f786850e387"],
        );
        gone(
            "blob dGhpcyBpcyBhIHNlY3JldCB2YWx1ZSAxMjM0NTY3OA== end",
            &["dGhpcyBpcyBh"],
        );
    }

    #[test]
    fn device_numbers_go() {
        gone("serial=4c1f09ab7d3e2658a1 attached", &["4c1f09ab7d3e"]);
        gone("device 4c1f09ab7d3e2658a1 attached", &["4c1f09ab7d3e"]);
        gone("IMEI: 356938035643809", &["356938035643809"]);
        gone("android_id=9774d56d682e549c", &["9774d56d"]);
        gone(
            "chat 123e4567-e89b-12d3-a456-426614174000 saved",
            &["123e4567"],
        );
    }

    #[test]
    fn message_text_goes() {
        assert_eq!(
            line("CHAT abc user 2026-11-21 10:00:00 my private diary", &[]),
            format!("CHAT {MESSAGE}")
        );
        gone(
            "sending text=\"buy a surprise gift for Sam\" now",
            &["surprise", "Sam"],
        );
        gone("send message: what is my bank pin 1234", &["bank pin"]);
        gone(r#"{"text":"my secret plan","id":7}"#, &["secret plan"]);
        gone(r#"{"title":"Tax return 2025","id":7}"#, &["Tax return"]);
    }

    #[test]
    fn names_and_places_go() {
        gone("chat title=Holiday plans saved", &["Holiday"]);
        gone("pc_name=OFFICE-PC online", &["OFFICE"]);
        gone("hostname: mypc.local up", &["mypc"]);
        gone("joined ssid=\"Home Wifi 5G\"", &["Home Wifi"]);
        gone("weather lat=51.5072 lon=-0.1276", &["51.5072", "0.1276"]);
        gone_with(
            "Opened Garden planner for Rowan on Rowan's PC",
            &["Garden planner", "Rowan"],
            &["Garden planner", "Rowan"],
        );
        gone_with(
            "garden PLANNER opened",
            &["garden"],
            &["Garden planner", "garden planner"],
        );
        assert_eq!(line("a b c", &["a".to_string()]), "a b c");
    }

    #[test]
    fn what_must_survive() {
        kept(
            "11-21 12:34:56.789  1234  5678 E MimirLookup: lookup timed out",
            &["11-21 12:34:56.789", "lookup timed out"],
        );
        kept(
            "app 0.01.2 (dev) built 2026-11-21",
            &["0.01.2", "2026-11-21"],
        );
        kept(
            "E/Net: connection refused after 3 tries",
            &["connection refused", "3 tries"],
        );
        kept("at 1763712000000 stalled", &["1763712000000"]);
        kept(
            "io.github.odnwks.mimir.link.Wire failed",
            &["io.github.odnwks.mimir.link.Wire"],
        );
        kept("Choreographer: Skipped 45 frames!", &["Skipped 45 frames"]);
    }

    #[test]
    fn lines_drop_chat_and_blank_and_keep_the_newest_50() {
        let mut raw: Vec<String> = (1..=80).map(|i| format!("line {i} error")).collect();
        raw.push(String::new());
        raw.push("CHAT x user t secret words".into());
        let out = lines(&raw, &[], 50);
        assert_eq!(out.len(), 50);
        assert_eq!(out.last().unwrap(), "line 80 error");
        assert!(out.iter().all(|l| !l.contains("secret words")));
    }

    #[test]
    fn long_lines_are_cut() {
        let out = lines(&["x".repeat(2000)], &[], 50);
        assert!(out[0].chars().count() <= 400);
    }
}
