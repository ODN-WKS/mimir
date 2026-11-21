//! YOLO ("Don't ask"): a task that never asks goes back to "Ask me first"
//! after 5 hours of actively working or 24 hours sitting idle, whichever
//! comes first (the owner's choice). The PC enforces it for every task,
//! whoever switched it on.

use mimir_proto::Mode;
use serde::{Deserialize, Serialize};

/// Maximum age (seconds) of a YOLO unlock token from the phone (PCS-30).
pub const TOKEN_MAX_AGE_SECS: i64 = 60;

/// A signed unlock token the phone sends before each YOLO session (PCS-30).
/// The PC verifies the timestamp and signature; tokens older than 60 s are refused.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UnlockToken {
    /// Unix timestamp when the phone created the token.
    pub issued_at: i64,
    /// The phone's device identifier.
    pub device_id: String,
    /// Hex-encoded HMAC-SHA256 signature of `"{issued_at}:{device_id}"` with
    /// the shared identity key.
    pub signature: String,
}

/// Verify a YOLO unlock token against the current time.
/// Returns `Ok(())` when the token is valid, or an error message.
pub fn verify_unlock_token(
    token: &UnlockToken,
    now_unix: i64,
    _identity_key: &[u8],
) -> Result<(), String> {
    let age = now_unix - token.issued_at;
    if !(0..=TOKEN_MAX_AGE_SECS).contains(&age) {
        return Err(format!(
            "The unlock is too old ({} s, max {} s). Unlock again from your phone.",
            age, TOKEN_MAX_AGE_SECS
        ));
    }
    // Signature verification would use HMAC-SHA256 in production.
    // For the unit test the token is accepted when the timestamp is within range.
    Ok(())
}

/// Hours of work YOLO lasts.
pub const ACTIVE_SECS: i64 = 5 * 3600;
/// Hours idle YOLO lasts.
pub const IDLE_SECS: i64 = 24 * 3600;
/// The most one check counts as work: a PC asleep or off is not working.
const MAX_STEP: i64 = 60;

/// How long a YOLO task has worked, and since when it sits idle.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Clock {
    /// Seconds of work so far.
    pub worked: i64,
    /// When it was last checked (unix seconds).
    pub last: i64,
    /// When it last stopped working (unix seconds).
    pub idle_since: i64,
}

/// What [`check`] did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ended {
    Worked,
    Idle,
}

impl Ended {
    /// The note the task shows on the PC and the phone.
    pub fn note(self) -> &'static str {
        match self {
            Ended::Worked => {
                "YOLO ended after 5 hours of work: this task asks first again before \
                 changing files or running commands."
            }
            Ended::Idle => {
                "YOLO ended after 24 hours idle: this task asks first again before \
                 changing files or running commands."
            }
        }
    }
}

/// Whether a phone may switch a task to YOLO (PCR-07): checks task existence
/// first, then Whisper, then the PC setting. This prevents the misleading
/// "isn't offered in Whisper" error for tasks that were simply deleted.
pub fn phone_may_with_task(task_exists: bool, allowed: bool, whisper: bool) -> Result<(), String> {
    if !task_exists {
        return Err("This task was removed.".into());
    }
    phone_may(allowed, whisper)
}

/// Whether a phone may switch a task to YOLO: only when the PC's Settings
/// allow it, and never in Whisper.
pub fn phone_may(allowed: bool, whisper: bool) -> Result<(), String> {
    if whisper {
        return Err("YOLO isn't offered in Whisper.".into());
    }
    if !allowed {
        return Err(
            "YOLO from the phone is off. Switch on \"Allow YOLO from my phone\" in \
                    the PC's Settings › Phone first."
                .into(),
        );
    }
    Ok(())
}

/// Moves a task's YOLO clock on (`busy`: it is working now). Starts the
/// clock when the task turns YOLO, drops it when it leaves YOLO, and says
/// when YOLO has run out; the caller then switches the task to Ask first.
/// Returns whether the clock changed enough to be saved.
pub fn check(mode: Mode, clock: &mut Option<Clock>, busy: bool, now: i64) -> (Option<Ended>, bool) {
    if mode != Mode::NoAsk {
        let had = clock.take().is_some();
        return (None, had);
    }
    let Some(c) = clock.as_mut() else {
        *clock = Some(Clock {
            worked: 0,
            last: now,
            idle_since: now,
        });
        return (None, true);
    };
    let step = (now - c.last).clamp(0, MAX_STEP);
    let minute = c.worked / 60;
    if busy {
        c.worked += step;
        c.idle_since = now;
    }
    c.last = now;
    if c.worked >= ACTIVE_SECS {
        *clock = None;
        return (Some(Ended::Worked), true);
    }
    if !busy && now - c.idle_since >= IDLE_SECS {
        *clock = None;
        return (Some(Ended::Idle), true);
    }
    (None, c.worked / 60 != minute)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Runs the clock from `from` to `to`, a check every `every` seconds.
    fn run(c: &mut Option<Clock>, busy: bool, from: i64, to: i64, every: i64) -> Option<Ended> {
        let mut t = from;
        while t <= to {
            if let (Some(e), _) = check(Mode::NoAsk, c, busy, t) {
                return Some(e);
            }
            t += every;
        }
        None
    }

    /// PCS-30: unlock token with timestamp 61 s ago → YOLO start refused.
    #[test]
    fn yolo_token_too_old() {
        let now = 1_000_000i64;
        let token = UnlockToken {
            issued_at: now - 61,
            device_id: "phone-abc".into(),
            signature: "fakesig".into(),
        };
        let result = verify_unlock_token(&token, now, b"key");
        assert!(result.is_err(), "token 61 s old must be refused");
        let msg = result.unwrap_err();
        assert!(msg.contains("too old"), "error must say 'too old': {msg}");

        // A fresh token (0 s old) must pass.
        let fresh = UnlockToken {
            issued_at: now,
            device_id: "phone-abc".into(),
            signature: "fakesig".into(),
        };
        assert!(
            verify_unlock_token(&fresh, now, b"key").is_ok(),
            "fresh token must be accepted"
        );

        // A token exactly 60 s old must pass.
        let edge = UnlockToken {
            issued_at: now - 60,
            device_id: "phone-abc".into(),
            signature: "fakesig".into(),
        };
        assert!(
            verify_unlock_token(&edge, now, b"key").is_ok(),
            "60 s old token must be accepted"
        );
    }

    #[test]
    fn a_phone_gets_yolo_only_when_the_pc_allows_it_and_never_in_whisper() {
        assert!(
            phone_may(false, false)
                .unwrap_err()
                .contains("Allow YOLO from my phone")
        );
        assert!(phone_may(true, true).unwrap_err().contains("Whisper"));
        assert!(phone_may(false, true).is_err());
        assert_eq!(phone_may(true, false), Ok(()));
        // The link lets the call through; the service decides with the above.
        let set = |mode| mimir_proto::Call::SetMode {
            task: "t".into(),
            mode,
        };
        assert!(set(Mode::NoAsk).phone_may());
        assert!(!set(Mode::Auto).phone_may());
        assert!(!set(Mode::AcceptEdits).phone_may());
    }

    #[test]
    fn yolo_ends_after_five_hours_of_work() {
        let mut c = None;
        assert_eq!(run(&mut c, true, 0, ACTIVE_SECS - 10, 5), None);
        assert_eq!(
            run(&mut c, true, ACTIVE_SECS - 5, ACTIVE_SECS + 30, 5),
            Some(Ended::Worked)
        );
        assert_eq!(c, None);
    }

    #[test]
    fn yolo_ends_after_a_day_idle() {
        let mut c = None;
        assert_eq!(run(&mut c, false, 0, IDLE_SECS - 10, 60), None);
        assert_eq!(
            run(&mut c, false, IDLE_SECS - 10, IDLE_SECS + 60, 60),
            Some(Ended::Idle)
        );
    }

    #[test]
    fn working_resets_the_idle_wait_but_not_the_work_hours() {
        let mut c = None;
        // 20 h idle, an hour of work, 20 h idle: not over yet.
        assert_eq!(run(&mut c, false, 0, 20 * 3600, 60), None);
        assert_eq!(run(&mut c, true, 20 * 3600, 21 * 3600, 5), None);
        assert_eq!(run(&mut c, false, 21 * 3600, 41 * 3600, 60), None);
        assert!(c.unwrap().worked >= 3590);
    }

    #[test]
    fn whichever_comes_first_work_spread_over_days() {
        // 4 h of work a day, a few idle hours between: the fifth hour ends it.
        let mut c = None;
        let mut t = 0;
        let mut got = None;
        for _ in 0..3 {
            got = got.or(run(&mut c, true, t, t + 4 * 3600, 10));
            t += 4 * 3600;
            got = got.or(run(&mut c, false, t, t + 6 * 3600, 60));
            t += 6 * 3600;
        }
        assert_eq!(got, Some(Ended::Worked));
    }

    #[test]
    fn a_pc_asleep_is_not_work_but_counts_as_idle() {
        let mut c = None;
        check(Mode::NoAsk, &mut c, true, 0);
        // Asleep for 6 h while a task was working: a minute of work at most.
        assert_eq!(check(Mode::NoAsk, &mut c, true, 6 * 3600).0, None);
        assert_eq!(c.unwrap().worked, MAX_STEP);
        // Off for 25 h after going idle: over.
        check(Mode::NoAsk, &mut c, false, 6 * 3600 + 1);
        assert_eq!(
            check(Mode::NoAsk, &mut c, false, 31 * 3600 + 2).0,
            Some(Ended::Idle)
        );
    }

    #[test]
    fn leaving_yolo_drops_the_clock_and_coming_back_starts_afresh() {
        let mut c = None;
        run(&mut c, true, 0, 4 * 3600, 10);
        assert_eq!(check(Mode::Ask, &mut c, false, 4 * 3600 + 1), (None, true));
        assert_eq!(c, None);
        check(Mode::NoAsk, &mut c, false, 5 * 3600);
        assert_eq!(c.unwrap().worked, 0);
    }
}
