//! The hub's checks: the watchdog, the idle loop, workers and client queues.

use mimir_hub::client::{Out, Pushed, client_queue};
use mimir_hub::workers::Pool;
use mimir_hub::{Backends, HubEvent, World};
use mimir_proto::ChatTurn;
use mimir_store::{Keyring, TalkStore, Writer};
use std::sync::mpsc::channel;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const WAIT: Duration = Duration::from_secs(10);

fn tmp(name: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("mimir-hub-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    d
}

fn store(name: &str) -> TalkStore {
    TalkStore::open_with(
        &tmp(name),
        &Keyring::Off,
        Writer::spawn(Duration::from_millis(50)),
    )
}

fn turn(id: &str, text: &str) -> ChatTurn {
    ChatTurn {
        id: id.into(),
        user: true,
        text: text.into(),
        sources: vec![],
        ran: None,
        model_words: None,
        at: 1,
        version: 1,
        seq: 0,
        gone: false,
        switch_note: None,
        pictures: vec![],
        files: vec![],
    }
}

/// Backends that take `slow` each and note what ran in `log`.
fn fakes(slow: Duration, log: Arc<Mutex<Vec<String>>>) -> Backends {
    let (l1, l2) = (log.clone(), log.clone());
    Backends {
        talk: Box::new(move |turns| {
            std::thread::sleep(slow);
            l1.lock().unwrap().push("answered".into());
            Ok(format!(
                "you said {}",
                turns.last().map_or("", |t| t.text.as_str())
            ))
        }),
        probe: Box::new(move || {
            l2.lock().unwrap().push("probed".into());
            Ok("qwen fits".into())
        }),
        lookup: Box::new(move |u| {
            std::thread::sleep(slow);
            Ok(format!("page {u}"))
        }),
        voice: Box::new(|t| Ok(format!("{}.ogg", t.len()))),
    }
}

/// A world whose one message takes `took`.
struct Slow {
    took: Duration,
    done: bool,
}

impl World for Slow {
    type Msg = ();
    fn name(_: &()) -> &'static str {
        "slow"
    }
    fn handle(&mut self, _: ()) {
        let t0 = Instant::now();
        // Busy, as a blocking call on the hub's thread would be.
        while t0.elapsed() < self.took {
            std::hint::spin_loop();
        }
        self.done = true;
    }
    fn tick(&mut self, _: Instant) {}
    fn next_deadline(&self, _: Instant) -> Option<Instant> {
        None
    }
    fn done(&self) -> bool {
        self.done
    }
}

#[test]
#[should_panic(expected = "hub watchdog: slow took")]
fn a_60_ms_handler_trips_the_watchdog_in_test_builds() {
    let (tx, rx) = channel();
    tx.send(()).unwrap();
    mimir_hub::run(
        &mut Slow {
            took: Duration::from_millis(60),
            done: false,
        },
        &rx,
    );
}

#[test]
fn a_quick_handler_passes_and_release_builds_only_warn() {
    let (tx, rx) = channel();
    tx.send(()).unwrap();
    let s = mimir_hub::run(
        &mut Slow {
            took: Duration::from_millis(5),
            done: false,
        },
        &rx,
    );
    assert_eq!(s.messages, 1);
    // Release: a warning, no panic.
    assert!(!mimir_hub::watchdog::check_with(
        "slow",
        Duration::from_millis(60),
        false
    ));
}

#[test]
fn slow_work_runs_on_workers_and_the_hub_stays_quick() {
    let log = Arc::new(Mutex::new(vec![]));
    let (h, t) = mimir_hub::start(
        store("talk"),
        fakes(Duration::from_millis(300), log.clone()),
    );
    let (q, events) = client_queue(16);
    h.connect(1, q);
    let done = h
        .put("c1", None, vec![turn("u1", "hello")], false, 1)
        .wait(WAIT)
        .unwrap()
        .unwrap();
    assert_eq!(done.seq, 1);
    // A 300 ms answer: the hub answers other messages meanwhile.
    let answer = h.answer("c1");
    let t0 = Instant::now();
    let page = h.since(0).wait(WAIT).unwrap();
    assert!(
        t0.elapsed() < Duration::from_millis(100),
        "the hub waited for the worker"
    );
    assert_eq!(page.chats.len(), 1);
    // A probe asked mid-answer waits for it (PCE-09, PCE-10).
    let probe = h.probe();
    assert_eq!(answer.wait(WAIT).unwrap().unwrap(), "you said hello");
    assert_eq!(probe.wait(WAIT).unwrap().unwrap(), "qwen fits");
    assert_eq!(*log.lock().unwrap(), vec!["answered", "probed"]);
    // Four lookups at once take about one lookup's time.
    let t0 = Instant::now();
    let looks: Vec<_> = (0..4).map(|i| h.lookup(&format!("u{i}"))).collect();
    for l in looks {
        assert!(l.wait(WAIT).unwrap().is_ok());
    }
    assert!(t0.elapsed() < Duration::from_millis(900));
    assert_eq!(h.say("hi").wait(WAIT).unwrap().unwrap(), "2.ogg");
    // The client heard of the chat and the answer.
    let got: Vec<_> = events.try_iter().collect();
    assert!(matches!(&got[0], Out::Event(HubEvent::ChatChanged(c)) if c.id == "c1"));
    assert!(
        got.iter()
            .any(|e| matches!(e, Out::Event(HubEvent::Answered { .. })))
    );
    h.stop();
    t.join().unwrap();
}

#[test]
fn an_idle_hub_does_not_wake() {
    let log = Arc::new(Mutex::new(vec![]));
    let (h, t) = mimir_hub::start(store("idle"), fakes(Duration::ZERO, log));
    std::thread::sleep(Duration::from_millis(1500));
    h.stop();
    let s = t.join().unwrap();
    assert_eq!(
        s,
        mimir_hub::Stats {
            messages: 1,
            ticks: 0
        }
    );
}

#[test]
fn a_full_client_queue_drops_and_then_asks_for_a_resync() {
    let (mut q, rx) = client_queue::<u32>(2);
    assert_eq!(q.push(1), Pushed::Sent);
    assert_eq!(q.push(2), Pushed::Sent);
    assert_eq!(q.push(3), Pushed::Dropped);
    assert!(q.owes_resync());
    assert_eq!(rx.try_recv().unwrap(), Out::Event(1));
    // Room for one: the resync takes it (event 4 is covered by it).
    assert_eq!(q.push(4), Pushed::Dropped);
    assert!(!q.owes_resync());
    assert_eq!(rx.try_recv().unwrap(), Out::Event(2));
    assert_eq!(rx.try_recv().unwrap(), Out::Resync);
    assert_eq!(q.push(5), Pushed::Sent);
    assert_eq!(rx.try_recv().unwrap(), Out::Event(5));
    drop(rx);
    assert_eq!(q.push(6), Pushed::Gone);
}

#[test]
fn a_full_pool_says_busy_at_once() {
    let (tx, rx) = channel::<u32>();
    let gate = Arc::new(Mutex::new(()));
    let held = gate.lock().unwrap();
    let g = gate.clone();
    let pool = Pool::spawn("check", (1, 1), tx, move |j: u32| {
        drop(g.lock().unwrap());
        j
    });
    pool.submit(1).unwrap(); // taken by the worker, which waits on the gate
    std::thread::sleep(Duration::from_millis(100));
    pool.submit(2).unwrap(); // queued
    let t0 = Instant::now();
    assert!(pool.submit(3).is_err());
    assert!(t0.elapsed() < Duration::from_millis(10));
    drop(held);
    assert_eq!(rx.recv_timeout(WAIT).unwrap(), 1);
    assert_eq!(rx.recv_timeout(WAIT).unwrap(), 2);
}

/// Done-check 1: nothing in the hub's code sleeps, unwraps a lock or waits
/// on a channel without a deadline.
#[test]
fn the_hub_code_has_no_blocking_calls() {
    let src = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    for e in std::fs::read_dir(src).unwrap().flatten() {
        let text = std::fs::read_to_string(e.path()).unwrap();
        for bad in ["std::thread::sleep", ".lock().unwrap()", "recv()"] {
            assert!(!text.contains(bad), "{} has {bad}", e.path().display());
        }
    }
}
