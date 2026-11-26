//! The plan's weekly and 5-hour use, kept fresh without spending any usage.
//!
//! Claude Code only reports the figures while a task runs, so they went
//! stale. This asks Anthropic's usage page (the one Claude Code's own
//! `/usage` reads, a plain read that costs nothing) with the login Claude
//! Code already keeps. Every 30 minutes while Claude is working, every 12
//! hours while it is not. The login is only read to send that one request;
//! it is never logged, printed or kept, and never refreshed here (Claude
//! Code renews it itself): an expired login just skips the check. It is
//! only looked at while Claude is in use here (see `claude_in_use`); a
//! person on their own models only never has it read.

use crate::task::WeeklyUse;
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::mpsc::{Receiver, channel};

/// How often to look while Claude is working, in seconds.
pub const ACTIVE_SECS: i64 = 30 * 60;
/// How often to look while it is not.
pub const IDLE_SECS: i64 = 12 * 3600;
/// How soon to look again when the last look did not work (no current login,
/// no network), so the figure appears soon after it can (0.09 item 11).
pub const RETRY_SECS: i64 = 10 * 60;
/// How long to wait after the first, second and later failed looks in a row
/// (10 minutes, 1 hour, 12 hours), so an expired login or no network never
/// means a request every 10 minutes forever.
pub const BACKOFF_SECS: [i64; 3] = [RETRY_SECS, 3600, 12 * 3600];
const URL: &str = "https://api.anthropic.com/api/oauth/usage";

/// Whether Claude is in use on this PC, so its login may be read: a paid
/// chat choice, Claude chosen for project work, or any project task that
/// runs on a Claude model (the default when no own model is picked).
pub fn claude_in_use(chats_paid: bool, project_on_claude: bool, task_on_claude: bool) -> bool {
    chats_paid || project_on_claude || task_on_claude
}

/// The one rule the service and the window both use: Claude is in use when
/// chats are paid, or Claude is the project engine and a project exists, or
/// a task that is working right now runs on Claude. `tasks` are each task's
/// model and whether it is busy now; a task with no model of its own runs on
/// the project engine's choice. Stopped, finished and archived tasks never
/// count.
pub fn claude_in_use_now<'a>(
    chats_paid: bool,
    project_on_claude: bool,
    has_project: bool,
    tasks: impl IntoIterator<Item = (Option<&'a str>, bool)>,
) -> bool {
    let on_claude = tasks.into_iter().any(|(model, busy)| {
        busy && match model {
            Some(m) => !crate::models::is_free(Some(m)),
            None => project_on_claude,
        }
    });
    claude_in_use(chats_paid, project_on_claude && has_project, on_claude)
}

/// "When online use passes 99% this week, or runs out, your PC's model
/// takes over": the one threshold, the same on the PC and the phone.
pub const FREE_AT_PERCENT: i64 = 99;

/// This week's online use is at or past [`FREE_AT_PERCENT`], rounded as the
/// PC window and the phone show it (98.5% shows as 99%, so it counts): when
/// the week resets. A reading whose reset time has passed is last week's,
/// and one with no reset time can't be placed in a week: neither counts.
pub fn past_free_threshold(weekly: Option<&WeeklyUse>, now: i64) -> Option<i64> {
    weekly
        .filter(|w| w.resets_at > now && (w.used * 100.0).round() as i64 >= FREE_AT_PERCENT)
        .map(|w| w.resets_at)
}

/// What one look found.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Reading {
    pub weekly: Option<WeeklyUse>,
    pub five_hour: Option<WeeklyUse>,
}

/// When the next look is due. Time is passed in, so tests use a fake clock.
#[derive(Debug, Default)]
pub struct Schedule {
    /// When we last tried (unix seconds); None: never.
    last_try: Option<i64>,
    /// Looks in a row that found nothing (see `RETRY_SECS`, `BACKOFF_SECS`).
    failures: u32,
}

impl Schedule {
    /// Is a look due now? The first one is due at once.
    pub fn due(&self, now: i64, active: bool) -> bool {
        let gap = if self.failures > 0 {
            BACKOFF_SECS[(self.failures as usize - 1).min(BACKOFF_SECS.len() - 1)]
        } else if active {
            ACTIVE_SECS
        } else {
            IDLE_SECS
        };
        match self.last_try {
            None => true,
            // A clock that went back must not stall the checks.
            Some(t) => now < t || now - t >= gap,
        }
    }

    pub fn tried(&mut self, now: i64) {
        self.last_try = Some(now);
    }

    /// Records how the look ended.
    pub fn ended(&mut self, worked: bool) {
        self.failures = if worked {
            0
        } else {
            self.failures.saturating_add(1)
        };
    }
}

/// "updated 5 min ago" for the hover; None when never updated.
pub fn updated_text(updated_at: Option<i64>, now: i64) -> Option<String> {
    let t = updated_at?;
    let mins = ((now - t).max(0)) / 60;
    Some(if mins < 1 {
        "updated just now".to_string()
    } else if mins < 60 {
        format!("updated {mins} min ago")
    } else {
        let h = mins / 60;
        format!("updated {h} h ago")
    })
}

/// Reads the usage page's answer. Tolerant: each window is optional, the
/// percentage may be a number or text, the reset time text or seconds.
pub fn parse(body: &str) -> Option<Reading> {
    let v: Value = serde_json::from_str(body).ok()?;
    let window = |name: &str| -> Option<WeeklyUse> {
        let w = v.get(name)?;
        let used = match w.get("utilization")? {
            Value::Number(n) => n.as_f64()?,
            Value::String(s) => s.trim().trim_end_matches('%').parse().ok()?,
            _ => return None,
        };
        let resets_at = match w.get("resets_at") {
            Some(Value::String(s)) => iso_seconds(s).unwrap_or(0),
            Some(Value::Number(n)) => n.as_i64().unwrap_or(0),
            _ => 0,
        };
        // The page speaks in percent; the app keeps 0 to 1.
        Some(WeeklyUse {
            used: (used / 100.0).clamp(0.0, 1.0),
            resets_at,
        })
    };
    let r = Reading {
        weekly: window("seven_day"),
        five_hour: window("five_hour"),
    };
    (r.weekly.is_some() || r.five_hour.is_some()).then_some(r)
}

/// "2025-11-04T04:59:59.943648+00:00" to unix seconds.
fn iso_seconds(s: &str) -> Option<i64> {
    let (date, rest) = s.split_once('T')?;
    let mut d = date.split('-');
    let (y, m, day): (i64, i64, i64) = (
        d.next()?.parse().ok()?,
        d.next()?.parse().ok()?,
        d.next()?.parse().ok()?,
    );
    let (time, off) = match rest.find(['+', '-', 'Z']) {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "Z"),
    };
    let mut t = time.split(':');
    let h: i64 = t.next()?.parse().ok()?;
    let mi: i64 = t.next()?.parse().ok()?;
    let sec: f64 = t.next().unwrap_or("0").parse().ok()?;
    let offset = if off == "Z" {
        0
    } else {
        let sign = if off.starts_with('-') { -1 } else { 1 };
        let mut p = off[1..].split(':');
        let oh: i64 = p.next()?.parse().ok()?;
        let om: i64 = p.next().unwrap_or("0").parse().ok()?;
        sign * (oh * 3600 + om * 60)
    };
    // Days since 1970-01-01 (civil calendar).
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    Some(days * 86400 + h * 3600 + mi * 60 + sec as i64 - offset)
}

/// The access token from Claude Code's login file, if it is still valid.
fn token(creds: &Path, now: i64) -> Option<String> {
    let v: Value = serde_json::from_str(&std::fs::read_to_string(creds).ok()?).ok()?;
    let o = v.get("claudeAiOauth")?;
    if let Some(exp) = o.get("expiresAt").and_then(Value::as_i64) {
        // Milliseconds; skip a login that is (nearly) out of date.
        if exp / 1000 <= now + 30 {
            return None;
        }
    }
    o.get("accessToken")?.as_str().map(str::to_string)
}

/// One look. Err is a short reason for the log (never the login).
pub fn fetch(creds: &Path, now: i64) -> Result<Reading, String> {
    use std::io::Write;
    let tok = token(creds, now).ok_or("no current login (it renews itself)")?;
    let mut curl = std::process::Command::new("curl");
    for var in ["SSL_CERT_FILE", "SSL_CERT_DIR", "CURL_CA_BUNDLE"] {
        if std::env::var_os(var).is_some_and(|p| !Path::new(&p).exists()) {
            curl.env_remove(var);
        }
    }
    // The login goes in on stdin, so it never shows in a process list.
    let mut child = curl
        .args([
            "-q",
            "-sS",
            "--proto",
            "=https",
            "--max-redirs",
            "0",
            "--connect-timeout",
            "10",
            "-m",
            "20",
            "--max-filesize",
            "200000",
            "-K",
            "-",
            "-w",
            "\n\x01%{http_code}",
            URL,
        ])
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|_| "curl is missing")?;
    let cfg = format!(
        "header = \"Authorization: Bearer {tok}\"\nheader = \"anthropic-beta: oauth-2025-04-20\"\nheader = \"Accept: application/json\"\nuser-agent = \"claude-cli\"\n"
    );
    if let Some(mut si) = child.stdin.take() {
        let _ = si.write_all(cfg.as_bytes());
    }
    let out = child.wait_with_output().map_err(|_| "curl failed")?;
    let text = String::from_utf8_lossy(&out.stdout).into_owned();
    let (body, code) = text.rsplit_once("\n\x01").ok_or("no answer")?;
    if code.trim() != "200" {
        return Err(format!("usage page answered {}", code.trim()));
    }
    parse(body).ok_or_else(|| "usage page answer not understood".to_string())
}

/// True when usage changed by 0.1 % or more (PCR-01).
/// A direct float comparison on 0–1 values rounds away sub-50% moves; this
/// compares the values rounded to the nearest 0.1 % (× 1000 and rounded).
pub fn reading_changed(
    old: &(Option<WeeklyUse>, Option<WeeklyUse>, Option<i64>),
    new: &(Option<WeeklyUse>, Option<WeeklyUse>, Option<i64>),
) -> bool {
    fn wu_changed(a: Option<&WeeklyUse>, b: Option<&WeeklyUse>) -> bool {
        match (a, b) {
            (None, None) => false,
            (Some(a), Some(b)) => {
                (a.used * 1000.0).round() as i64 != (b.used * 1000.0).round() as i64
                    || a.resets_at != b.resets_at
            }
            _ => true,
        }
    }
    wu_changed(old.0.as_ref(), new.0.as_ref())
        || wu_changed(old.1.as_ref(), new.1.as_ref())
        || old.2 != new.2
}

/// Runs the looks on a side thread so the service never waits.
pub struct Poller {
    creds: PathBuf,
    pub schedule: Schedule,
    pending: Option<Receiver<Result<Reading, String>>>,
    /// Looks only happen while Claude is in use (a paid chat choice, Claude
    /// for project work, or a project task on a Claude model); with own
    /// models only and no such task, the login is never read.
    pub wanted: bool,
}

impl Poller {
    pub fn new(creds: PathBuf) -> Self {
        Poller {
            creds,
            schedule: Schedule::default(),
            pending: None,
            wanted: true,
        }
    }

    /// Call often. Starts a look when one is due; returns a finished one.
    pub fn poll(&mut self, now: i64, active: bool) -> Option<Reading> {
        if !self.wanted {
            return None;
        }
        if let Some(rx) = &self.pending {
            match rx.try_recv() {
                Ok(res) => {
                    self.pending = None;
                    match res {
                        Ok(r) => {
                            self.schedule.ended(true);
                            return Some(r);
                        }
                        Err(e) => {
                            self.schedule.ended(false);
                            crate::log::write("usage", "skipped", &e)
                        }
                    }
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => return None,
                Err(_) => self.pending = None,
            }
        }
        if self.pending.is_none() && self.schedule.due(now, active) {
            self.schedule.tried(now);
            let (tx, rx) = channel();
            let creds = self.creds.clone();
            std::thread::spawn(move || {
                let _ = tx.send(fetch(&creds, now));
            });
            self.pending = Some(rx);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    /// P23 addendum: at 98% this week work stays online; at 99% and above
    /// it moves. Last week's reading, or one with no reset time, never moves it.
    #[test]
    fn p23_the_threshold_is_99_percent_this_week() {
        let now = 1_000;
        let w = |used: f64, resets_at: i64| WeeklyUse { used, resets_at };
        for (used, moves) in [
            (0.0, false),
            (0.86, false),
            (0.98, false),
            (0.984, false),
            (0.985, true),
            (0.99, true),
            (0.995, true),
            (1.0, true),
        ] {
            assert_eq!(
                past_free_threshold(Some(&w(used, 5_000)), now),
                moves.then_some(5_000),
                "{used}"
            );
        }
        assert_eq!(past_free_threshold(Some(&w(1.0, 900)), now), None);
        assert_eq!(past_free_threshold(Some(&w(1.0, 0)), now), None);
        assert_eq!(past_free_threshold(None, now), None);
        assert_eq!(FREE_AT_PERCENT, 99);
    }

    use super::*;

    #[test]
    fn usage_change_sensitivity() {
        // A 0.001 change in the 0–1 field (0.1 %) must be detected (PCR-01).
        let make = |used: f64| {
            (
                Some(WeeklyUse {
                    used,
                    resets_at: 1000,
                }),
                None::<WeeklyUse>,
                None::<i64>,
            )
        };
        let old = make(0.370);
        let new = make(0.371);
        assert!(reading_changed(&old, &new), "0.001 change must be detected");
        // Identical values must not trigger.
        assert!(!reading_changed(&old, &old));
    }

    #[test]
    fn p09d_fix_only_work_going_on_now_puts_claude_in_use() {
        let old_task = [(None, false), (Some("sonnet"), false)];
        assert!(
            !claude_in_use_now(false, false, true, old_task),
            "old tasks never count"
        );
        assert!(
            !claude_in_use_now(false, false, true, [(None, true)]),
            "no model: the project engine decides (free)"
        );
        assert!(claude_in_use_now(false, true, true, [(None, true)]));
        assert!(claude_in_use_now(
            false,
            false,
            false,
            [(Some("opus"), true)]
        ));
        assert!(!claude_in_use_now(
            false,
            false,
            true,
            [(Some("ollama:qwen"), true)]
        ));
        assert!(!claude_in_use_now(false, true, false, []), "no project");
        assert!(claude_in_use_now(false, true, true, []));
        assert!(claude_in_use_now(true, false, false, []));
    }

    #[test]
    fn p09d_fix_failed_looks_back_off_ten_minutes_an_hour_twelve_hours() {
        let mut s = Schedule::default();
        s.tried(0);
        s.ended(false);
        assert!(!s.due(599, true));
        assert!(s.due(600, true));
        s.tried(600);
        s.ended(false);
        assert!(!s.due(600 + 3599, true));
        assert!(s.due(600 + 3600, true));
        s.tried(5000);
        s.ended(false);
        assert!(!s.due(5000 + 12 * 3600 - 1, true));
        assert!(s.due(5000 + 12 * 3600, true));
        s.tried(100_000);
        s.ended(false);
        assert!(!s.due(100_000 + 3600, true), "stays at twelve hours");
        s.ended(true);
        assert!(!s.due(100_000 + 600, true), "back to the normal gap");
        assert!(s.due(100_000 + ACTIVE_SECS, true));
    }

    #[test]
    fn p09d_fix_a_missing_or_expired_login_sends_no_request() {
        let dir = std::env::temp_dir().join(format!("mimir-usage-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let missing = dir.join("none.json");
        assert!(fetch(&missing, 1000).is_err());
        let old = dir.join("old.json");
        std::fs::write(
            &old,
            r#"{"claudeAiOauth":{"accessToken":"x","expiresAt":900000}}"#,
        )
        .unwrap();
        assert!(token(&old, 1000).is_none(), "expired: no request");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn claude_counts_as_in_use_for_paid_chats_claude_projects_or_a_claude_task() {
        assert!(!claude_in_use(false, false, false), "own models only");
        assert!(claude_in_use(true, false, false), "paid chats");
        assert!(claude_in_use(false, true, false), "Claude for projects");
        assert!(claude_in_use(false, false, true), "a task on Claude");
    }

    #[test]
    fn nothing_is_looked_up_while_only_free_models_are_in_use() {
        let mut p = Poller::new(PathBuf::from("/nonexistent/credentials.json"));
        p.wanted = false;
        assert!(p.poll(1000, true).is_none());
        assert!(p.schedule.due(1000, true), "no look was even tried");
        p.wanted = true;
        let _ = p.poll(1000, true);
        assert!(!p.schedule.due(1000, true), "a look was tried");
    }

    #[test]
    fn p09_item11_a_failed_look_is_tried_again_in_ten_minutes_not_twelve_hours() {
        let mut s = Schedule::default();
        s.tried(1000);
        s.ended(false);
        assert!(!s.due(1000 + 9 * 60, false));
        assert!(s.due(1000 + RETRY_SECS, false));
        s.tried(1000 + RETRY_SECS);
        s.ended(true);
        assert!(
            !s.due(1000 + RETRY_SECS + 3600, false),
            "a good look waits 12 h"
        );
    }

    #[test]
    fn active_every_30_minutes_idle_every_12_hours() {
        let mut s = Schedule::default();
        assert!(s.due(1000, false), "the first look is due at once");
        s.tried(1000);
        assert!(!s.due(1000 + 29 * 60, true));
        assert!(s.due(1000 + 30 * 60, true));
        assert!(!s.due(1000 + 30 * 60, false), "idle waits longer");
        assert!(!s.due(1000 + 12 * 3600 - 1, false));
        assert!(s.due(1000 + 12 * 3600, false));
        // Work started after a long idle stretch: due right away.
        assert!(s.due(1000 + 5 * 3600, true));
        // A clock that jumped back does not stall.
        assert!(s.due(500, true));
    }

    #[test]
    fn staleness_text() {
        assert_eq!(updated_text(None, 100), None);
        assert_eq!(updated_text(Some(100), 130).unwrap(), "updated just now");
        assert_eq!(
            updated_text(Some(0), 5 * 60 + 3).unwrap(),
            "updated 5 min ago"
        );
        assert_eq!(
            updated_text(Some(0), 3 * 3600 + 60).unwrap(),
            "updated 3 h ago"
        );
        assert_eq!(updated_text(Some(500), 100).unwrap(), "updated just now");
    }

    #[test]
    fn reads_the_usage_page() {
        let r = parse(r#"{"five_hour":{"utilization":37.0,"resets_at":"2025-11-04T04:59:59.943648+00:00"},"seven_day":{"utilization":92,"resets_at":"2025-11-06T03:59:59+00:00"},"seven_day_opus":null}"#).unwrap();
        let w = r.weekly.unwrap();
        assert!((w.used - 0.92).abs() < 1e-9);
        assert_eq!(w.resets_at, 1_762_401_599);
        let f = r.five_hour.unwrap();
        assert!((f.used - 0.37).abs() < 1e-9);
        assert_eq!(f.resets_at, 1_762_232_399);
    }

    #[test]
    fn tolerant_reading() {
        let r = parse(r#"{"seven_day":{"utilization":"12%","resets_at":1762400000},"extra":1}"#)
            .unwrap();
        assert!((r.weekly.unwrap().used - 0.12).abs() < 1e-9);
        assert!(r.five_hour.is_none());
        assert!(parse(r#"{"error":"x"}"#).is_none());
        assert!(parse("not json").is_none());
        // Over 100% stays at 100%.
        assert_eq!(
            parse(r#"{"five_hour":{"utilization":130}}"#)
                .unwrap()
                .five_hour
                .unwrap()
                .used,
            1.0
        );
    }

    #[test]
    fn expired_login_is_skipped() {
        let dir = std::env::temp_dir().join(format!("usage-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let f = dir.join("c.json");
        std::fs::write(
            &f,
            r#"{"claudeAiOauth":{"accessToken":"x","expiresAt":1000000}}"#,
        )
        .unwrap();
        assert!(token(&f, 2000).is_none());
        std::fs::write(
            &f,
            r#"{"claudeAiOauth":{"accessToken":"x","expiresAt":9000000}}"#,
        )
        .unwrap();
        assert_eq!(token(&f, 2000).as_deref(), Some("x"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
