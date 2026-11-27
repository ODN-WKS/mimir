//! N4a: a task whose work ends sends exactly one `Finished` to the owner's
//! phones that list "finished_events", none to another person's phone, none
//! to an older phone, and none for a Whisper task; a phone that connects
//! again gets the recent list, each end once. Fake phones on this PC (relays
//! off, loopback); the stand-in Claude plays a recorded run.

mod common;
use common::*;
use mimir_core::proto::{Call, Event, FinishedHow, FinishedItem, Mode};
use mimir_link::client::{Client, generate_key};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::time::{Duration, Instant};

const NEW: &[&str] = &["finished_events"];

fn fake_ollama() {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = "{\"models\":[{\"name\":\"qwen3.6:35b-a3b-coding\"}]}";
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
}

struct Phone {
    p: Client,
    pc: String,
    finished: Vec<FinishedItem>,
    recent: Option<Vec<FinishedItem>>,
    ready: bool,
}

/// Pairs a fake phone (the owner's when `who` is None) and connects it.
fn paired(
    st: &mut mimir_core::client::Station,
    name: &str,
    who: Option<&str>,
    features: &[&str],
) -> Phone {
    let info = st.call_wait(Call::LinkInfo).expect("no link");
    let pc = info["id"].as_str().unwrap().to_string();
    let c = Client::new(generate_key(), false).unwrap();
    for a in info["bound"].as_array().unwrap() {
        let a: std::net::SocketAddr = a.as_str().unwrap().parse().unwrap();
        let ip: std::net::IpAddr = if a.is_ipv4() {
            [127, 0, 0, 1].into()
        } else {
            std::net::Ipv6Addr::LOCALHOST.into()
        };
        c.add_hint(std::net::SocketAddr::new(ip, a.port()));
    }
    let code = st
        .call_wait(Call::PairCode)
        .unwrap()
        .as_str()
        .unwrap()
        .to_string();
    c.pair_start(&code, name).expect("the code was refused");
    assert!(service_until(st, |s| s.pairing.is_some()));
    let pending = st.pairing.as_ref().unwrap().0;
    st.call_wait(Call::ConfirmPair {
        pending,
        yes: true,
        person: Some(who.map_or(mimir_core::proto::WHOSE_MINE.to_string(), str::to_string)),
    })
    .unwrap();
    c.pair_finish(Duration::from_secs(10)).expect("not paired");
    let mut ph = Phone {
        p: c,
        pc,
        finished: vec![],
        recent: None,
        ready: false,
    };
    ph.hello(features);
    ph
}

impl Phone {
    fn hello(&mut self, features: &[&str]) {
        self.p.connect(&self.pc).expect("couldn't connect");
        self.ready = false;
        self.recent = None;
        self.p
            .call(Call::Hello {
                proto: mimir_core::proto::PROTO,
                app_version: "test".into(),
                client_name: "Fake phone".into(),
                env: vec![],
                features: features.iter().map(|f| f.to_string()).collect(),
            })
            .unwrap();
        self.listen_until(|p| p.ready);
        assert!(self.ready, "no snapshot after Hello");
    }

    fn take(&mut self) {
        while let Some(m) = self.p.next_event(Duration::from_millis(20)) {
            match m.event {
                Event::Ready => self.ready = true,
                Event::Finished { item } => self.finished.push(item),
                Event::RecentFinished { items } => self.recent = Some(items),
                _ => {}
            }
        }
    }

    fn listen_until(&mut self, done: impl Fn(&Phone) -> bool) {
        let until = Instant::now() + Duration::from_secs(20);
        while Instant::now() < until && !done(self) {
            self.take();
        }
    }

    /// Listens for `ms` more.
    fn listen(&mut self, ms: u64) {
        let until = Instant::now() + Duration::from_millis(ms);
        while Instant::now() < until {
            self.take();
        }
    }
}

fn settings(home: &TestHome) {
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
    }));
}

#[test]
fn n4a_one_finished_for_the_owner_none_for_others_or_a_whisper() {
    let Some(home) = TestHome::new() else { return };
    settings(&home);
    fake_ollama();
    home.play("run-1.jsonl", "live.jsonl");
    home.play("run-2.jsonl", "live.jsonl");
    let mut st = home.service();
    let mut owner = paired(&mut st, "Owner phone", None, NEW);
    let mut guest = paired(&mut st, "Ana's phone", Some("Ana"), NEW);
    let mut old = paired(&mut st, "Old owner phone", None, &[]);
    assert_eq!(
        owner.recent,
        Some(vec![]),
        "the snapshot has no recent list"
    );
    assert_eq!(old.recent, None, "an older phone got the recent list");

    let id = st
        .call_wait(Call::NewTask {
            typed: false,
            ground_rules: None,
            project: home.project.display().to_string(),
            text: "Tidy up notes.txt.".into(),
            pictures: vec![],
            files: vec![],
            mode: Mode::Ask,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: None,
        })
        .expect("the task didn't start");
    let id = id.as_str().unwrap().to_string();
    assert!(
        service_until(&mut st, |s| s.tasks.iter().any(|v| v.task.id == id
            && !v.busy()
            && v.task
                .entries
                .iter()
                .any(|e| matches!(e, mimir_core::task::Entry::Claude(_))))),
        "the task never ended"
    );
    owner.listen_until(|p| !p.finished.is_empty());
    owner.listen(1500);
    guest.listen(500);
    old.listen(500);
    assert_eq!(owner.finished.len(), 1, "owner: {:?}", owner.finished);
    assert_eq!(owner.finished[0].task, id);
    assert_eq!(owner.finished[0].how, FinishedHow::Ok);
    assert!(
        guest.finished.is_empty(),
        "a guest got {:?}",
        guest.finished
    );
    assert!(old.finished.is_empty(), "an older phone got one");

    // A phone that connects again gets the recent list, the end once.
    owner.hello(NEW);
    assert_eq!(owner.recent.as_ref().map(|r| r.len()), Some(1));
    assert_eq!(owner.recent.as_ref().unwrap()[0].id, owner.finished[0].id);
    guest.hello(NEW);
    assert_eq!(guest.recent, None, "a guest got the owner's recent list");

    // A Whisper task ends without a trace: no event, not in the list.
    let w = st
        .call_wait(Call::NewIncognito {
            typed: false,
            ground_rules: None,
            project: home.project.display().to_string(),
            text: "Tidy up notes.txt.".into(),
            model: "ollama:qwen3.6:35b-a3b-coding".into(),
            mode: Mode::Ask,
        })
        .expect("the Whisper task didn't start");
    let w = w.as_str().unwrap().to_string();
    assert!(
        service_until(&mut st, |s| s.tasks.iter().any(|v| v.task.id == w
            && !v.busy()
            && v.task
                .entries
                .iter()
                .any(|e| matches!(e, mimir_core::task::Entry::Claude(_))))),
        "the Whisper task never ended"
    );
    owner.listen(2500);
    assert_eq!(
        owner.finished.len(),
        1,
        "a Whisper sent: {:?}",
        owner.finished
    );
    owner.hello(NEW);
    assert_eq!(owner.recent.as_ref().map(|r| r.len()), Some(1));
}

/// Starts a task from the window and waits until `ended` says so.
fn start(st: &mut mimir_core::client::Station, home: &TestHome) -> String {
    let id = st
        .call_wait(Call::NewTask {
            typed: false,
            ground_rules: None,
            project: home.project.display().to_string(),
            text: "Do the thing.".into(),
            pictures: vec![],
            files: vec![],
            mode: Mode::Ask,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: None,
        })
        .expect("the task didn't start");
    id.as_str().unwrap().to_string()
}

/// A command the turn left running in the background is still work: no
/// `Finished` while it runs, exactly one when it ends.
#[test]
fn n4a_no_finished_while_a_background_command_runs() {
    let Some(home) = TestHome::new() else { return };
    settings(&home);
    home.play("run-1.jsonl", "background.jsonl");
    set_env("FAKE_CLAUDE_SPEED", "1.0");
    let mut st = home.service();
    let mut owner = paired(&mut st, "Owner phone", None, NEW);
    let id = start(&mut st, &home);
    // The turn is over and the sleep (about 3 s) is still going.
    let ok = service_until(&mut st, |s| {
        s.tasks.iter().any(|v| {
            v
        .task
        .id
        == id
        && v.task.entries.iter().any(
            |e| matches!(e, mimir_core::task::Entry::Note(n) if n.contains("in the background"))
        )
        })
    });
    assert!(ok);
    owner.listen(1200);
    assert!(
        owner.finished.is_empty(),
        "told it finished while it still worked: {:?}",
        owner.finished
    );
    owner.listen_until(|p| !p.finished.is_empty());
    owner.listen(2500);
    assert_eq!(owner.finished.len(), 1, "{:?}", owner.finished);
    assert_eq!(owner.finished[0].how, FinishedHow::Ok);
}

/// Stop reads as Stopped, even with the background command's late output.
#[test]
fn n4a_stop_reads_as_stopped() {
    let Some(home) = TestHome::new() else { return };
    settings(&home);
    home.play("run-1.jsonl", "background-long.jsonl");
    set_env("FAKE_CLAUDE_SPEED", "1.0");
    let mut st = home.service();
    let mut owner = paired(&mut st, "Owner phone", None, NEW);
    let id = start(&mut st, &home);
    assert!(service_until(&mut st, |s| s.tasks.iter().any(|v| v
        .task
        .id
        == id
        && v.task.entries.iter().any(
            |e| matches!(e, mimir_core::task::Entry::Note(n) if n.contains("in the background"))
        ))));
    st.call_wait(Call::Stop { task: id.clone() }).unwrap();
    owner.listen_until(|p| !p.finished.is_empty());
    owner.listen(2500);
    assert_eq!(owner.finished.len(), 1, "{:?}", owner.finished);
    assert_eq!(owner.finished[0].how, FinishedHow::Stopped);
}

/// A task that ends by asking the owner something gets the needs-you alert
/// only, not a "finished" too.
#[test]
fn n4a_a_question_waiting_is_not_a_finished() {
    let Some(home) = TestHome::new() else { return };
    settings(&home);
    home.play("run-1.jsonl", "question.jsonl");
    let mut st = home.service();
    let mut owner = paired(&mut st, "Owner phone", None, NEW);
    let id = start(&mut st, &home);
    assert!(service_until(&mut st, |s| s.tasks.iter().any(|v| v
        .task
        .id
        == id
        && v.task.question_waiting())));
    owner.listen(3000);
    assert!(owner.finished.is_empty(), "{:?}", owner.finished);
}
