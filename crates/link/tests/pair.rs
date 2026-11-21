//! Pairing and a session over loopback, relays off: the link alone.

use mimir_link::client::{Client, generate_key};
use mimir_link::server::{LinkEvent, Server, ServerConfig};
use mimir_proto::{Call, Event, EventMsg};
use std::sync::mpsc::{Receiver, channel};
use std::time::Duration;

fn server() -> (Server, Receiver<LinkEvent>, std::path::PathBuf) {
    let dir = std::env::temp_dir().join(format!(
        "mimir-link-test-{}-{}",
        std::process::id(),
        rand_suffix()
    ));
    let (tx, rx) = channel();
    let s = Server::start(
        ServerConfig {
            dir: dir.clone(),
            relays: false,
            pc_name: "Test PC".into(),
        },
        tx,
    )
    .unwrap();
    (s, rx, dir)
}

fn rand_suffix() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos() as u64
}

fn phone(s: &Server) -> Client {
    let c = Client::new(generate_key(), false).unwrap();
    for a in s.bound() {
        let ip = if a.is_ipv4() {
            std::net::IpAddr::from([127, 0, 0, 1])
        } else {
            std::net::IpAddr::from(std::net::Ipv6Addr::LOCALHOST)
        };
        c.add_hint(std::net::SocketAddr::new(ip, a.port()));
    }
    c
}

fn pending(rx: &Receiver<LinkEvent>) -> (u64, Vec<String>) {
    loop {
        match rx
            .recv_timeout(Duration::from_secs(20))
            .expect("no pairing request")
        {
            LinkEvent::PairPending { pending, words, .. } => return (pending, words),
            _ => continue,
        }
    }
}

#[test]
fn pairs_after_yes_and_a_session_carries_calls_and_events() {
    let (s, rx, dir) = server();
    let code = s.pair_code();
    let p = phone(&s);
    let words = p.pair_start(&code, "Test phone").unwrap();
    let (id, pc_words) = pending(&rx);
    assert_eq!(words, pc_words, "both sides must show the same words");
    assert!(s.phones().is_empty(), "paired before the owner said yes");
    assert!(s.confirm(id, true));
    assert_eq!(p.pair_finish(Duration::from_secs(10)).unwrap(), "Test PC");
    assert_eq!(s.phones().len(), 1);

    p.connect(&s.id()).unwrap();
    let (conn, events) = loop {
        if let LinkEvent::Connected { conn, events, .. } =
            rx.recv_timeout(Duration::from_secs(20)).unwrap()
        {
            break (conn, events);
        }
    };
    let call_id = p.call(Call::Sync { since: 0 }).unwrap();
    loop {
        if let LinkEvent::Call { conn: c, msg } = rx.recv_timeout(Duration::from_secs(20)).unwrap()
        {
            assert_eq!(c, conn);
            assert_eq!(msg.call_id, call_id);
            break;
        }
    }
    events
        .send(EventMsg {
            seq: 1,
            event: Event::Problem {
                text: "hello phone".into(),
            },
        })
        .unwrap();
    let e = p.next_event(Duration::from_secs(10)).expect("no event");
    assert!(matches!(e.event, Event::Problem { ref text } if text == "hello phone"));

    // Removing the phone closes the session; it can't come back.
    assert!(s.remove_phone(&p.id()));
    for _ in 0..100 {
        if !p.connected() {
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(!p.connected(), "the session stayed open after removal");
    let _ = p.connect(&s.id());
    for _ in 0..100 {
        if !p.connected() {
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(!p.connected(), "a removed phone got a session");
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn refusals_used_wrong_no_and_no_answer() {
    unsafe { std::env::set_var("MIMIR_PAIR_CONFIRM_MS", "1500") };
    let (s, rx, dir) = server();
    // Wrong code: right PC, wrong secret.
    let mut wrong = mimir_link::Code::parse(&s.pair_code()).unwrap();
    wrong.secret = [9; 32];
    let p = phone(&s);
    let e = p.pair_start(&wrong.text(), "x").unwrap_err();
    assert!(e.contains("doesn't match"), "{e}");

    // "No" on the PC.
    let code = s.pair_code();
    let words = p.pair_start(&code, "x").unwrap();
    assert_eq!(words.len(), 4);
    let (id, _) = pending(&rx);
    s.confirm(id, false);
    let e = p.pair_finish(Duration::from_secs(10)).unwrap_err();
    assert!(e.contains("don't match"), "{e}");
    // The same code again: used.
    let e = p.pair_start(&code, "x").unwrap_err();
    assert!(e.contains("no longer works"), "{e}");

    // Nobody answers.
    let code = s.pair_code();
    p.pair_start(&code, "x").unwrap();
    let e = p.pair_finish(Duration::from_secs(10)).unwrap_err();
    assert!(e.contains("in time"), "{e}");
    assert!(s.phones().is_empty());
    let _ = std::fs::remove_dir_all(dir);
}

/// One real round trip through n0's relays and address lookup (needs the
/// internet; `tools/real-check.sh` runs it).
#[test]
#[ignore]
fn real_relay_round_trip() {
    let dir = std::env::temp_dir().join(format!("mimir-link-relay-{}", rand_suffix()));
    let (tx, rx) = channel();
    let s = Server::start(
        ServerConfig {
            dir: dir.clone(),
            relays: true,
            pc_name: "Relay check".into(),
        },
        tx,
    )
    .unwrap();
    assert!(
        s.wait_online(Duration::from_secs(30)),
        "the PC never got online"
    );
    let p = Client::new(generate_key(), true).unwrap();
    let code = s.pair_code();
    p.pair_start(&code, "Relay phone")
        .expect("pairing through the relays failed");
    let (id, _) = pending(&rx);
    s.confirm(id, true);
    p.pair_finish(Duration::from_secs(30)).unwrap();
    p.connect(&s.id())
        .expect("the session through the relays failed");
    let events = loop {
        if let LinkEvent::Connected { events, .. } =
            rx.recv_timeout(Duration::from_secs(30)).unwrap()
        {
            break events;
        }
    };
    events
        .send(EventMsg {
            seq: 1,
            event: Event::Ready,
        })
        .unwrap();
    assert!(
        p.next_event(Duration::from_secs(30)).is_some(),
        "no event through the relays"
    );
    let _ = std::fs::remove_dir_all(dir);
}

/// A connect to a PC that can't be reached ends within its budget, even
/// while the network keeps changing (on mobile data the phone once sat on
/// "connecting to the PC" for 233 s: every change gave it a fresh 30 s).
#[test]
fn a_connect_ends_soon_even_while_the_network_keeps_changing() {
    use mimir_link::client::CONNECT_BUDGET;
    let pc = Client::new(generate_key(), false).unwrap();
    let pc_id = pc.id();
    drop(pc);
    // An address nobody answers on: a UDP port that was just closed.
    let gone = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
    let addr = gone.local_addr().unwrap();
    drop(gone);
    let p = std::sync::Arc::new(Client::new(generate_key(), false).unwrap());
    p.add_hint(addr);
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let changer = {
        let (p, stop) = (p.clone(), stop.clone());
        std::thread::spawn(move || {
            while !stop.load(std::sync::atomic::Ordering::SeqCst) {
                p.network_changed();
                std::thread::sleep(Duration::from_millis(300));
            }
        })
    };
    let t0 = std::time::Instant::now();
    let r = p.connect(&pc_id);
    let took = t0.elapsed();
    stop.store(true, std::sync::atomic::Ordering::SeqCst);
    changer.join().unwrap();
    assert!(r.is_err(), "connected to nobody");
    assert!(
        took <= CONNECT_BUDGET + Duration::from_secs(2),
        "connect took {took:?}"
    );
    assert!(!p.connected());
}

/// Once the PC is back, the next connect gets through quickly.
#[test]
fn a_reconnect_after_the_pc_restarts_is_quick() {
    let (s, rx, dir) = server();
    let code = s.pair_code();
    let p = phone(&s);
    p.pair_start(&code, "Test phone").unwrap();
    let (id, _) = pending(&rx);
    assert!(s.confirm(id, true));
    p.pair_finish(Duration::from_secs(10)).unwrap();
    p.connect(&s.id()).unwrap();
    p.disconnect();
    let t0 = std::time::Instant::now();
    p.connect(&s.id()).unwrap();
    assert!(
        t0.elapsed() < Duration::from_secs(5),
        "reconnect took {:?}",
        t0.elapsed()
    );
    assert!(p.connected());
    let _ = std::fs::remove_dir_all(dir);
}

/// Phones paired before people were told apart (no person in their record)
/// are the owner's, and the owner's person can use the online plan.
#[test]
fn a_phone_kept_by_an_earlier_version_is_the_owners() {
    let dir = std::env::temp_dir().join(format!(
        "mimir-link-test-old-{}-{}",
        std::process::id(),
        rand_suffix()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(
        dir.join("phones.json"),
        r#"[{"key":"abc","name":"Old phone","paired":5,"last_seen":6}]"#,
    )
    .unwrap();
    let (tx, _rx) = channel();
    let s = Server::start(
        ServerConfig {
            dir: dir.clone(),
            relays: false,
            pc_name: "Test PC".into(),
        },
        tx,
    )
    .unwrap();
    assert_eq!(s.phones()[0].person, "owner");
    assert_eq!(s.person_of("abc").as_deref(), Some("owner"));
    assert!(s.may_go_online("owner"));
    assert!(!s.may_go_online("somebody"));
    drop(s);
    let _ = std::fs::remove_dir_all(&dir);
}
