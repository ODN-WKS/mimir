//! The command line's scripted checks (PLAN.md 4.1): thin wrappers that
//! talk to the running service over its socket, so checks never need to
//! drive the window.
//!
//! - `mimir serve`            the service, in this terminal
//! - `mimir status --json`    the PC's truth as JSON
//! - `mimir pair`             a pairing code; the four words; asks to confirm
//! - `mimir chat "<text>"`    one message, its answer printed
//! - `mimir say "<text>"`     speech via Ingrid TTS at 110%
//! - `mimir chats [--json]`   and `mimir engine probe` are in mimir-core

use mimir_core::client::Station;
use mimir_core::proto::{Call, ChatTurn, TalkState};
use std::time::{Duration, Instant};

/// Runs a command; None when `args` names none of these.
pub fn command(args: &[String], version: &str) -> Option<i32> {
    let rest = &args[1.min(args.len())..];
    Some(match args.first().map(String::as_str)? {
        "serve" => mimir_core::service::run_main(version),
        "status" => status(rest, version),
        "pair" => pair(version),
        "chat" => chat(rest, version),
        "say" => say(rest),
        "import-old" => import_old(rest),
        _ => return None,
    })
}

fn connect(version: &str) -> Result<Station, i32> {
    let path = mimir_core::sockets::control_socket();
    Station::connect(&path, version, std::sync::Arc::new(|| {})).map_err(|_| {
        eprintln!("{}", connect_failure(&path));
        2
    })
}

/// What to say when the service didn't take this command: if its socket
/// still takes a connection, it IS running and refused this process (only
/// Mímir's own window may ask); otherwise it isn't running.
fn connect_failure(path: &std::path::Path) -> String {
    if std::os::unix::net::UnixStream::connect(path).is_ok() {
        "Mímir is running. For safety, this command only works from Mímir's own window.".into()
    } else {
        "Mímir's service isn't running. Start it with: mimir serve".into()
    }
}

/// Takes in events until `done` says so or `wait` passes.
fn poll_until(st: &mut Station, wait: Duration, mut done: impl FnMut(&Station) -> bool) -> bool {
    let until = Instant::now() + wait;
    while Instant::now() < until {
        st.poll();
        if done(st) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    false
}

fn status(args: &[String], version: &str) -> i32 {
    let mut st = match connect(version) {
        Ok(s) => s,
        Err(c) => return c,
    };
    poll_until(&mut st, Duration::from_secs(5), |s| s.truth.is_some());
    let Some(truth) = st.truth.as_ref() else {
        eprintln!("The service sent no status.");
        return 1;
    };
    if args.iter().any(|a| a == "--json") {
        println!("{}", serde_json::to_string(truth).unwrap_or_default());
    } else {
        println!(
            "{}",
            serde_json::to_string_pretty(truth).unwrap_or_default()
        );
    }
    0
}

fn pair(version: &str) -> i32 {
    let mut st = match connect(version) {
        Ok(s) => s,
        Err(c) => return c,
    };
    pair_via(&mut st, &mut std::io::stdin().lock(), |_| {}, 600)
}

/// `mimir pair` on a connected service: `input` answers the questions,
/// `on_code` is told the code (checks hand it to a fake phone), and a phone
/// has `wait_secs` to ask. Input that ends with no answer is not a "No".
pub fn pair_via(
    st: &mut Station,
    input: &mut dyn std::io::BufRead,
    mut on_code: impl FnMut(&str),
    wait_secs: u64,
) -> i32 {
    let code = match st.call_wait(Call::PairCode) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("{e}");
            return 1;
        }
    };
    let code = code.as_str().map_or(code.to_string(), str::to_string);
    println!("Pairing code: {code}");
    on_code(&code);
    println!("Enter it on the phone. Waiting up to 10 minutes...");
    if !poll_until(st, Duration::from_secs(wait_secs), |s| s.pairing.is_some()) {
        eprintln!("No phone asked to pair.");
        return 1;
    }
    let (pending, phone, words) = st.pairing.clone().expect("checked above");
    println!("{phone} shows: {}", words.join(" "));
    println!("Do the four words match on the phone? [y/N]");
    let mut line = String::new();
    // No input at all (a closed or empty stdin) is nobody answering, not a "No":
    // the PC reports its own timeout to the phone when its wait ends.
    if input.read_line(&mut line).unwrap_or(0) == 0 {
        eprintln!("No answer was given. The phone is told the PC stopped waiting.");
        return 1;
    }
    let yes = line.trim().eq_ignore_ascii_case("y") || line.trim().eq_ignore_ascii_case("yes");
    // Whose phone it is: asked only here, at the PC. Empty or "mine": the
    // owner's own; anything else is a first name (someone else's phone).
    // Only an explicit "mine" is the owner's; an empty answer asks again,
    // and no answer at all (the input ended) pairs nothing.
    let mut person = None;
    if yes {
        loop {
            println!("Whose phone is this? Type \"mine\", or their first name:");
            let mut who = String::new();
            if input.read_line(&mut who).unwrap_or(0) == 0 {
                eprintln!("No answer was given. The phone is told the PC stopped waiting.");
                return 1;
            }
            let who = who.trim();
            if who.eq_ignore_ascii_case("mine") {
                person = Some(mimir_core::proto::WHOSE_MINE.to_string());
            } else if !who.is_empty() {
                person = Some(who.to_string());
            }
            if person.is_some() {
                break;
            }
        }
    }
    match st.call_wait(Call::ConfirmPair {
        pending,
        yes,
        person,
    }) {
        Ok(_) if yes => {
            println!("Paired.");
            0
        }
        Ok(_) => {
            println!("Not paired.");
            1
        }
        Err(e) => {
            eprintln!("{e}");
            1
        }
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64)
}

fn chat(args: &[String], version: &str) -> i32 {
    let text = args.join(" ");
    if text.trim().is_empty() {
        eprintln!("Say what: mimir chat \"<text>\"");
        return 2;
    }
    let mut st = match connect(version) {
        Ok(s) => s,
        Err(c) => return c,
    };
    let stamp = now_ms();
    let (chat_id, turn_id) = (format!("cli-{stamp}"), format!("cli-{stamp}-1"));
    let turn = ChatTurn {
        id: turn_id.clone(),
        user: true,
        text,
        sources: vec![],
        ran: None,
        model_words: None,
        at: stamp,
        version: 1,
        seq: 0,
        gone: false,
    };
    if let Err(e) = st.call_wait(Call::Talk {
        chat_id: chat_id.clone(),
        turn_id: turn_id.clone(),
        turns: vec![turn],
        lookup: None,
        sources: vec![],
        effort: None,
        ground_rules: None,
        task: None,
        chat_only: true,
    }) {
        eprintln!("{e}");
        return 1;
    }
    let until = Instant::now() + Duration::from_secs(300);
    while Instant::now() < until {
        let state = st.call_wait(Call::TalkTurn {
            chat_id: chat_id.clone(),
            turn_id: turn_id.clone(),
        });
        match state.map(serde_json::from_value::<TalkState>) {
            Ok(Ok(TalkState::Done { turn })) => {
                println!("{}", turn.text);
                return 0;
            }
            Ok(Ok(TalkState::Failed { why })) => {
                eprintln!("{why}");
                return 1;
            }
            Ok(Ok(TalkState::Unknown)) => {
                eprintln!("The PC lost the message.");
                return 1;
            }
            Ok(Ok(TalkState::Answering { .. })) => {}
            Ok(Err(e)) => {
                eprintln!("An odd answer from the service: {e}");
                return 1;
            }
            Err(e) => {
                eprintln!("{e}");
                return 1;
            }
        }
        std::thread::sleep(Duration::from_millis(300));
    }
    eprintln!("No answer within 5 minutes.");
    1
}

fn say(args: &[String]) -> i32 {
    let text = args.join(" ");
    if text.trim().is_empty() {
        eprintln!("Say what: mimir say \"<text>\"");
        return 2;
    }
    // PCE-12: default voice (Ingrid) at 110% speed.
    let data_dir = mimir_core::paths::data_dir();
    let root = mimir_core::voice::root(&data_dir);
    if !mimir_core::voice::ready(&root) {
        eprintln!(
            "This copy of Mímir doesn't include the voice. Install Mímir again from the software centre."
        );
        return 1;
    }
    let phrases = mimir_core::speech_text::phrases(&text);
    let done = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let done2 = done.clone();
    let reading = mimir_core::voice::read(
        &root,
        phrases,
        1.1, // 110%
        1.0,
        move |e| {
            eprintln!("Could not speak: {e}");
            done2.store(true, std::sync::atomic::Ordering::Relaxed);
        },
    );
    while !reading.done() {
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    0
}

fn import_old(args: &[String]) -> i32 {
    let dry_run = args.iter().any(|a| a == "--dry-run");
    if dry_run {
        println!("Dry run — nothing will be written.");
    }
    let Some(from) = args
        .iter()
        .position(|a| a == "--from")
        .and_then(|i| args.get(i + 1))
    else {
        eprintln!(
            "Say which folder the earlier app used: mimir import-old --from <folder name> [--dry-run]"
        );
        return 1;
    };
    let opts = mimir_core::import::ImportOptions::new(dry_run, from);
    match mimir_core::import::import_old(&opts) {
        Ok(summaries) => {
            if summaries.is_empty() {
                println!("Nothing to import (no old chats found).");
            } else {
                for s in &summaries {
                    println!("{s}");
                }
            }
            0
        }
        Err(e) => {
            eprintln!("{e}");
            1
        }
    }
}

#[cfg(test)]
mod connect_failure_tests {
    use super::connect_failure;

    #[test]
    fn a_running_service_that_refuses_is_not_called_stopped() {
        let dir = std::env::temp_dir().join(format!("mimir-cf-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let sock = dir.join("c.sock");
        let _l = std::os::unix::net::UnixListener::bind(&sock).unwrap();
        let said = connect_failure(&sock);
        assert!(said.starts_with("Mímir is running."), "{said}");
        assert!(
            said.contains("only works from Mímir's own window"),
            "{said}"
        );
        let gone = connect_failure(&dir.join("none.sock"));
        assert!(gone.contains("isn't running"), "{gone}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
