//! One PC, several people (R13): a phone belongs to the person the PC's
//! owner said when pairing it, and everything the PC keeps or sends is
//! filtered by that person on the PC. Each check below fails when its
//! filter is taken out (`checks/proofs/sec*_*.patch`).
//!
//! The "people" here are fake phones on this PC (relays off, loopback), one
//! paired as "Mine" (the owner's), others as "Someone else's".

mod common;
use common::*;
use mimir_core::proto::{
    Call, ChatPutDone, ChatRecord, ChatTurn, ChatsPage, Event, Filed, TalkState,
};
use mimir_link::client::{Client, generate_key};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const FEATS: &[&str] = &["talk", "chat_sync", "people", "models_changed"];

fn home() -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
    }));
    Some(home)
}

/// A stand-in Ollama: `/api/chat` streams `words` (one piece each), and every
/// chat request's body is kept.
fn fake_ollama(words: &'static [&'static str]) -> Arc<Mutex<Vec<serde_json::Value>>> {
    let asked = Arc::new(Mutex::new(vec![]));
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    set_env(
        "MIMIR_OLLAMA",
        format!("http://{}", l.local_addr().unwrap()),
    );
    let kept = asked.clone();
    std::thread::spawn(move || {
        for c in l.incoming().flatten() {
            let kept = kept.clone();
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
                let lines: Vec<String> = match path.as_str() {
                    "/api/version" => vec![r#"{"version":"0.40.16"}"#.into()],
                    "/api/ps" => vec![r#"{"models":[]}"#.into()],
                    "/api/tags" => vec![r#"{"models":[]}"#.into()],
                    _ => {
                        if let Ok(v) = serde_json::from_slice(&body) {
                            kept.lock().unwrap().push(v);
                        }
                        let mut out: Vec<String> = words
                            .iter()
                            .map(|w| {
                                serde_json::json!({"message": {"content": w}, "done": false})
                                    .to_string()
                            })
                            .collect();
                        out.push(
                            r#"{"message":{"content":""},"done":true,"prompt_eval_count":50}"#
                                .into(),
                        );
                        out
                    }
                };
                let mut w = c;
                let _ = w.write_all(
                    b"HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nTransfer-Encoding: chunked\r\n\r\n",
                );
                for line in lines {
                    let data = format!("{line}\n");
                    let _ = w.write_all(format!("{:x}\r\n{data}\r\n", data.len()).as_bytes());
                    let _ = w.flush();
                    std::thread::sleep(Duration::from_millis(5));
                }
                let _ = w.write_all(b"0\r\n\r\n");
            });
        }
    });
    asked
}

/// The engine's pick, as if the probe had run.
fn picked(home: &TestHome) {
    let dir = home.root.join(".local/share/mimir");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(
        dir.join("engine.json"),
        serde_json::json!({
            "fingerprint": "check",
            "at": 0,
            "chat": {
                "model": "huihui_ai/Qwen3.6-abliterated:35b-a3b",
                "title": "Qwen 3.6 35B, uncensored",
                "fit": "Split",
                "tokens_per_s": 64.0,
                "first_token_ms": 112,
                "cold_ms": 11000,
                "backend": "ROCm"
            },
            "measures": []
        })
        .to_string(),
    )
    .unwrap();
}

/// A fake phone's own key and what it is called, kept to connect again.
struct Phone {
    p: Client,
    seen: Seen,
    key: [u8; 32],
    pc: String,
}

/// Pairs a fake phone as the owner's ("Mine": `who` None) or as someone
/// else's (their first name), connects it and takes its snapshot.
fn paired_as(st: &mut mimir_core::client::Station, who: Option<&str>) -> Phone {
    paired_with(st, generate_key(), who.unwrap_or("Owner phone"), who)
}

/// The same, for a phone with this key and name (it pairs again).
fn paired_with(
    st: &mut mimir_core::client::Station,
    key: [u8; 32],
    name: &str,
    who: Option<&str>,
) -> Phone {
    let info = st
        .call_wait(Call::LinkInfo)
        .expect("the link isn't running");
    let pc = info["id"].as_str().unwrap().to_string();
    let c = Client::new(key, false).unwrap();
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
    let phone_words = c.pair_start(&code, name).expect("the code was refused");
    assert!(service_until(st, |s| s.pairing.is_some()));
    let pending = st.pairing.as_ref().unwrap().0;
    // Both screens show the same four words (the PC mixes in the code's secret).
    assert_eq!(
        st.pairing.as_ref().unwrap().2,
        phone_words,
        "the PC and the phone show different words"
    );
    assert_eq!(phone_words.len(), 4);
    st.call_wait(Call::ConfirmPair {
        pending,
        yes: true,
        person: Some(who.map_or(mimir_core::proto::WHOSE_MINE.to_string(), str::to_string)),
    })
    .unwrap();
    c.pair_finish(Duration::from_secs(10)).expect("not paired");
    let mut ph = Phone {
        p: c,
        seen: Seen::default(),
        key,
        pc,
    };
    ph.hello();
    ph
}

impl Phone {
    /// After the PC restarted: its link listens on other ports now.
    fn hints(&self, st: &mut mimir_core::client::Station) {
        let info = st.call_wait(Call::LinkInfo).unwrap();
        for a in info["bound"].as_array().unwrap() {
            let a: std::net::SocketAddr = a.as_str().unwrap().parse().unwrap();
            let ip: std::net::IpAddr = if a.is_ipv4() {
                [127, 0, 0, 1].into()
            } else {
                std::net::Ipv6Addr::LOCALHOST.into()
            };
            self.p.add_hint(std::net::SocketAddr::new(ip, a.port()));
        }
    }

    fn hello(&mut self) {
        self.p
            .connect(&self.pc)
            .expect("the paired phone couldn't connect");
        self.seen = Seen::default();
        self.p
            .call(Call::Hello {
                proto: mimir_core::proto::PROTO,
                app_version: "test".into(),
                client_name: "Fake phone".into(),
                env: vec![],
                features: FEATS.iter().map(|f| f.to_string()).collect(),
            })
            .unwrap();
        assert!(
            self.seen.until(&self.p, |s| s.ready),
            "no snapshot after Hello"
        );
    }

    fn call(&mut self, call: Call) -> Result<serde_json::Value, String> {
        self.seen.call(&self.p, call)
    }

    fn put(
        &mut self,
        chat: &str,
        name: Option<&str>,
        turns: Vec<ChatTurn>,
        deleted: bool,
    ) -> ChatPutDone {
        serde_json::from_value(
            self.call(Call::ChatPut {
                chat_id: chat.into(),
                name: name.map(str::to_string),
                turns,
                deleted,
            })
            .unwrap(),
        )
        .unwrap()
    }

    fn chats(&mut self) -> Vec<ChatRecord> {
        let page: ChatsPage =
            serde_json::from_value(self.call(Call::ChatsSince { seq: 0 }).unwrap()).unwrap();
        page.chats
    }

    fn talk(&mut self, chat: &str, id: &str, text: &str, chat_only: bool) -> Filed {
        serde_json::from_value(
            self.call(Call::Talk {
                chat_id: chat.into(),
                turn_id: id.into(),
                turns: vec![user(id, text)],
                lookup: None,
                sources: vec![],
                effort: None,
                ground_rules: None,
                task: None,
                chat_only,
            })
            .unwrap(),
        )
        .unwrap()
    }

    /// Listens for `ms` more.
    fn listen(&mut self, ms: u64) {
        let until = Instant::now() + Duration::from_millis(ms);
        while Instant::now() < until {
            self.seen.take(&self.p);
        }
    }
}

/// The person id the PC gave a phone, as the window's list shows it.
fn person_of(st: &mut mimir_core::client::Station, phone_name: &str) -> String {
    let list = st.call_wait(Call::Phones).unwrap();
    list.as_array()
        .unwrap()
        .iter()
        .find(|p| p["name"] == phone_name)
        .and_then(|p| p["person"].as_str())
        .expect("the phone isn't listed")
        .to_string()
}

fn text_of(chats: &[ChatRecord], chat: &str, turn: &str) -> Option<String> {
    chats
        .iter()
        .find(|c| c.id == chat)
        .and_then(|c| c.turns.iter().find(|t| t.id == turn))
        .map(|t| t.text.clone())
}

/// What a fake phone knows.
#[derive(Default)]
struct Seen {
    replies: HashMap<u64, Result<serde_json::Value, String>>,
    pieces: HashMap<String, String>,
    done: HashMap<String, Result<ChatTurn, String>>,
    changed: Vec<ChatRecord>,
    other: Vec<Event>,
    /// The numbers the PC put on the events this phone got.
    seqs: Vec<u64>,
    ready: bool,
}

impl Seen {
    fn take(&mut self, p: &Client) {
        while let Some(m) = p.next_event(Duration::from_millis(20)) {
            self.seqs.push(m.seq);
            match m.event {
                Event::Ready => self.ready = true,
                Event::Welcome { .. } => {}
                Event::Reply { call_id, result } => {
                    self.replies.insert(call_id, result);
                }
                Event::TalkPiece {
                    turn_id,
                    text,
                    thinking: false,
                    ..
                } => self.pieces.entry(turn_id).or_default().push_str(&text),
                Event::TalkDone {
                    turn_id, result, ..
                } => {
                    self.done.insert(turn_id, result);
                }
                Event::ChatChanged { chat } => self.changed.push(chat),
                e => self.other.push(e),
            }
        }
    }

    fn until(&mut self, p: &Client, mut done: impl FnMut(&Seen) -> bool) -> bool {
        let until = Instant::now() + Duration::from_secs(30);
        while Instant::now() < until {
            self.take(p);
            if done(self) {
                return true;
            }
        }
        false
    }

    fn call(&mut self, p: &Client, call: Call) -> Result<serde_json::Value, String> {
        let id = p.call(call).unwrap();
        assert!(self.until(p, |s| s.replies.contains_key(&id)), "no reply");
        self.replies.remove(&id).unwrap()
    }
}

fn user(id: &str, text: &str) -> ChatTurn {
    ChatTurn {
        id: id.into(),
        user: true,
        text: text.into(),
        sources: vec![],
        ran: None,
        model_words: None,
        at: 1_794_000_000_000,
        version: 1,
        seq: 0,
        gone: false,
    }
}

/// The owner's phone and Ana's: Ana's list never holds the owner's chat,
/// even when she asks for everything from the start, and a chat she makes
/// with the same id is her own, not the owner's.
#[test]
fn sec6_another_persons_chats_are_never_listed_or_read() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    mine.put(
        "c-secret",
        Some("Garden plans"),
        vec![user("u1", "my secret plan")],
        false,
    );
    assert_eq!(
        text_of(&mine.chats(), "c-secret", "u1").as_deref(),
        Some("my secret plan")
    );
    let hers = ana.chats();
    assert!(
        hers.is_empty(),
        "another person's chat was listed to Ana: {hers:?}"
    );
    // By the exact id: her own chat with that id starts empty, and writing to it
    // never reaches the owner's.
    ana.put("c-secret", None, vec![user("u2", "ana's note")], false);
    let hers = ana.chats();
    assert_eq!(hers.len(), 1);
    assert!(
        text_of(&hers, "c-secret", "u1").is_none(),
        "another person's turn was read by its exact id"
    );
    assert_eq!(
        text_of(&hers, "c-secret", "u2").as_deref(),
        Some("ana's note")
    );
    let owners = mine.chats();
    assert!(
        text_of(&owners, "c-secret", "u2").is_none(),
        "Ana's turn reached the owner's chat"
    );
    // The window (the owner) sees only the owner's.
    let page: ChatsPage =
        serde_json::from_value(st.call_wait(Call::ChatsSince { seq: 0 }).unwrap()).unwrap();
    assert!(text_of(&page.chats, "c-secret", "u2").is_none());
    assert!(text_of(&page.chats, "c-secret", "u1").is_some());
}

/// Ana can't edit, cut, rename or delete the owner's chat by its exact id.
#[test]
fn sec7_another_person_cannot_edit_cut_rename_or_delete_a_chat_by_its_id() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    mine.put(
        "c1",
        Some("Garden plans"),
        vec![user("u1", "keep me"), user("u3", "and me")],
        false,
    );
    // An edit with a higher version, a cut, a new name, and a delete.
    let mut edit = user("u1", "changed by someone else");
    edit.version = 9;
    let mut cut = user("u3", "");
    cut.version = 9;
    cut.gone = true;
    ana.put("c1", Some("Hacked"), vec![edit, cut], false);
    ana.put("c1", None, vec![], true);
    let owners = mine.chats();
    let c = owners
        .iter()
        .find(|c| c.id == "c1")
        .expect("the owner's chat was lost");
    assert!(
        c.deleted.is_none(),
        "another person deleted the owner's chat"
    );
    assert_eq!(
        c.name.as_deref(),
        Some("Garden plans"),
        "renamed by another person"
    );
    assert_eq!(
        text_of(&owners, "c1", "u1").as_deref(),
        Some("keep me"),
        "another person edited the owner's turn"
    );
    assert!(
        c.turns.iter().all(|t| !t.gone),
        "another person cut the owner's turn"
    );
}

/// Ana hears nothing of the owner's chat: no pieces, no answer, no change.
#[test]
fn sec8_another_person_gets_no_events_of_a_chat_that_is_not_theirs() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Oslo is ", "the capital."]);
    picked(&home);
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    mine.talk("c1", "t1", "What's the capital of Norway?", true);
    assert!(
        mine.seen.until(&mine.p, |s| s.done.contains_key("t1.a")),
        "no answer"
    );
    assert!(
        !mine.seen.pieces.is_empty(),
        "the owner's own phone got no pieces"
    );
    ana.listen(800);
    assert!(
        ana.seen.pieces.is_empty() && ana.seen.done.is_empty() && ana.seen.changed.is_empty(),
        "Ana was sent the owner's chat events: {:?} {:?} {:?}",
        ana.seen.pieces,
        ana.seen.done,
        ana.seen.changed
    );
    // And hers go to her alone.
    ana.talk("c9", "t9", "Hello", true);
    assert!(
        ana.seen.until(&ana.p, |s| s.done.contains_key("t9.a")),
        "no answer for Ana"
    );
    mine.listen(800);
    assert!(
        !mine.seen.done.contains_key("t9.a") && mine.seen.changed.iter().all(|c| c.id != "c9"),
        "the owner was sent Ana's chat events"
    );
}

/// Ana can't ask how the owner's answer stands, or stop it, by its id.
#[test]
fn sec9_another_person_cannot_ask_after_or_stop_an_answer_that_is_not_theirs() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Oslo."]);
    picked(&home);
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    mine.talk("c1", "t1", "Capital of Norway?", true);
    assert!(
        mine.seen.until(&mine.p, |s| s.done.contains_key("t1.a")),
        "no answer"
    );
    let asked = |p: &mut Phone| -> TalkState {
        serde_json::from_value(
            p.call(Call::TalkTurn {
                chat_id: "c1".into(),
                turn_id: "t1".into(),
            })
            .unwrap(),
        )
        .unwrap()
    };
    assert!(matches!(asked(&mut mine), TalkState::Done { .. }));
    let hers = asked(&mut ana);
    assert!(
        matches!(hers, TalkState::Unknown),
        "Ana was told how the owner's answer stands: {hers:?}"
    );
}

/// Ana can't start or change a task, read the owner's projects or updates.
#[test]
fn sec10_another_person_cannot_start_tasks_or_read_the_owners_work() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    let project = home.project.display().to_string();
    let new_task = |project: &str| Call::NewTask {
        project: project.into(),
        text: "Tidy the notes".into(),
        pictures: vec![],
        mode: mimir_core::task::Mode::Ask,
        model: None,
        effort: None,
        separate_copy: false,
        msg_id: None,
        ground_rules: None,
    };
    let r = ana.call(new_task(&project));
    assert!(r.is_err(), "Ana started a task: {r:?}");
    for call in [
        Call::Send {
            task: "x".into(),
            text: "hi".into(),
            pictures: vec![],
            msg_id: None,
        },
        Call::Status,
        Call::Transcript { task: "x".into() },
        Call::Speak {
            task: "x".into(),
            entry: 0,
        },
        Call::GetFile {
            name: "update.ogg".into(),
        },
        Call::NewIncognito {
            project: project.clone(),
            text: "x".into(),
            model: "ollama:x".into(),
            mode: mimir_core::task::Mode::Ask,
            ground_rules: None,
        },
        Call::SetSettings {
            settings: serde_json::json!({"yolo_from_phone": true}),
        },
        Call::PersonOnline {
            person: "owner".into(),
            on: true,
        },
        Call::RemovePerson {
            person: "owner".into(),
        },
        Call::Phones,
    ] {
        let name = format!("{call:?}");
        let r = ana.call(call);
        assert!(
            r.is_err(),
            "Ana may do {}: {r:?}",
            &name[..name.len().min(40)]
        );
    }
    // The lists a phone reads on opening are simply empty for her.
    assert_eq!(ana.call(Call::Updates).unwrap(), serde_json::json!([]));
    let projects = ana.call(Call::Projects).unwrap();
    assert_eq!(
        projects["projects"].as_array().map(Vec::len),
        Some(0),
        "{projects}"
    );
    // The owner's phone still can.
    let r = mine.call(new_task(&project));
    assert!(r.is_ok(), "the owner's phone couldn't start a task: {r:?}");
    let tasks = st.call_wait(Call::Status);
    assert!(tasks.is_ok());
}

/// What the PC pushes about the owner's work never reaches Ana.
#[test]
fn sec11_another_person_is_pushed_nothing_of_the_owners_work() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let project = home.project.display().to_string();
    let id = st
        .new_task(
            &project,
            "Tidy the notes",
            &[],
            mimir_core::task::Mode::Ask,
            None,
            None,
            false,
        )
        .expect("the task didn't start");
    assert!(service_until(&mut st, |s| s
        .tasks
        .iter()
        .any(|v| v.task.id == id)));
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    // The owner's phone is told of the task (so the check can tell).
    assert!(
        mine.seen
            .other
            .iter()
            .any(|e| matches!(e, Event::Task { .. })),
        "the owner's phone wasn't told of its task"
    );
    // Something changes while both are connected.
    st.call_wait(Call::Rename {
        task: id.clone(),
        title: "Notes tidy".into(),
    })
    .unwrap();
    mine.listen(500);
    ana.listen(1200);
    let leaked: Vec<String> = ana
        .seen
        .other
        .iter()
        .filter(|e| {
            !matches!(
                e,
                Event::Snapshot { .. } | Event::TestNotice { .. } | Event::TestNoticeOff
            )
        })
        .map(|e| format!("{e:?}").chars().take(60).collect())
        .collect();
    assert!(
        leaked.is_empty(),
        "Ana was sent the owner's work: {leaked:?}"
    );
}

/// Online answering uses the owner's plan: Ana's only when the owner allows
/// it; a new person (and a new PC) answers on the PC's free model.
#[test]
fn sec12_online_answers_need_the_owners_ok_and_the_default_is_free() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Oslo."]);
    picked(&home);
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    // Nothing chose Claude: free for the owner and for her.
    let m = mine.call(Call::Models).unwrap();
    assert_eq!(
        m["chats_engine"], "free",
        "a new PC answers online by default"
    );
    // Not allowed: Claude isn't even offered to her (no engine is reported).
    assert!(
        ana.call(Call::Models).unwrap()["chats_engine"].is_null(),
        "Ana switched to the owner's online plan: Claude is offered to her"
    );
    let paid = || Call::SetEngines {
        chats: Some("paid".into()),
        project: None,
        chat_model: None,
    };
    let r = ana.call(paid());
    assert!(r.is_err(), "Ana switched to the owner's online plan: {r:?}");
    assert!(ana.call(Call::Models).unwrap()["chats_engine"].is_null());
    // She can't allow herself.
    let who = person_of(&mut st, "Ana");
    let r = ana.call(Call::PersonOnline {
        person: who.clone(),
        on: true,
    });
    assert!(r.is_err(), "Ana allowed herself: {r:?}");
    // The owner allows it; now she can choose it.
    st.call_wait(Call::PersonOnline {
        person: who.clone(),
        on: true,
    })
    .unwrap();
    assert_eq!(
        ana.call(Call::Models).unwrap()["chats_engine"],
        "free",
        "allowed, but the default isn't the free model"
    );
    assert!(ana.call(paid()).is_ok(), "allowed, but refused");
    assert_eq!(ana.call(Call::Models).unwrap()["chats_engine"], "paid");
    // Her choice is hers: the owner's stays free.
    assert_eq!(mine.call(Call::Models).unwrap()["chats_engine"], "free");
    // The owner takes it away again: her chats go back to the free model at once.
    st.call_wait(Call::PersonOnline {
        person: who,
        on: false,
    })
    .unwrap();
    assert!(ana.call(Call::Models).unwrap()["chats_engine"].is_null());
    let f = ana.talk("c1", "t1", "Capital of Norway?", true);
    assert!(
        matches!(
            f,
            Filed::Talk {
                ran: mimir_core::proto::Ran::Pc,
                ..
            }
        ),
        "after the owner took it away her chat didn't run on the PC's model: {f:?}"
    );
}

/// A person's phone is told at once when the owner allows or takes away
/// the online plan (no restart), and nobody else's phone is.
#[test]
fn sec12b_the_phone_is_told_when_the_owner_changes_online_for_its_person() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Oslo."]);
    picked(&home);
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    let who = person_of(&mut st, "Ana");
    for on in [true, false] {
        ana.seen.other.clear();
        mine.seen.other.clear();
        st.call_wait(Call::PersonOnline {
            person: who.clone(),
            on,
        })
        .unwrap();
        assert!(
            ana.seen
                .until(&ana.p, |s| s.other.contains(&Event::ModelsChanged)),
            "Ana's phone wasn't told online was switched {on}"
        );
        mine.listen(300);
        assert!(
            !mine.seen.other.contains(&Event::ModelsChanged),
            "the owner's phone was told about Ana's permission"
        );
    }
}

/// Phones paired before people were told apart (no person in their record,
/// and chats with none) are the owner's, and keep their chats.
/// The same phone paired again (a new code) is shown other words, and each
/// pairing ends paired with a working session.
#[test]
fn pairing_the_same_phone_again_shows_new_words_and_still_works() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Oslo."]);
    picked(&home);
    let mut st = home.service();
    let key = generate_key();
    let first = paired_with(&mut st, key, "Same phone", Some("Ana"));
    let w1 = st.pairing.as_ref().map(|p| p.2.clone());
    let who = person_of(&mut st, "Same phone");
    drop(first);
    let _ = who;
    let mut second = paired_with(&mut st, key, "Same phone", Some("Ana"));
    let w2 = st.pairing.as_ref().map(|p| p.2.clone());
    assert_ne!(w1, w2, "the same words for a new pairing");
    assert!(
        second.call(Call::Models).is_ok(),
        "the session doesn't work"
    );
}

#[test]
fn sec13_phones_and_chats_from_before_people_belong_to_the_owner() {
    let Some(home) = home() else { return };
    // A chat kept by an earlier version: no person in its file.
    let dir = home.root.join(".local/share/mimir/talk");
    std::fs::create_dir_all(&dir).unwrap();
    let mut old = user("u1", "from before");
    old.seq = 1;
    std::fs::write(
        dir.join("old1.json"),
        serde_json::json!({
            "chat": {"id": "old1", "name": "Old", "at": old.at, "seq": 1, "turns": [old]},
            "meta_seq": 1
        })
        .to_string(),
    )
    .unwrap();
    let mut st = home.service();
    let mut ph = paired_as(&mut st, None);
    drop(st);
    home.stop_service();
    // The phone's record as an earlier version wrote it.
    let file = home.root.join(".config/mimir/link/phones.json");
    let mut list: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
    for p in list.as_array_mut().unwrap() {
        p.as_object_mut().unwrap().remove("person");
    }
    std::fs::write(&file, serde_json::to_vec(&list).unwrap()).unwrap();
    let mut st = home.service();
    ph.p.disconnect();
    ph.hints(&mut st);
    ph.hello();
    let chats = ph.chats();
    assert_eq!(
        text_of(&chats, "old1", "u1").as_deref(),
        Some("from before"),
        "a phone paired before people were told apart lost the owner's chats"
    );
    assert_eq!(person_of(&mut st, "Owner phone"), "owner");
    // The window (the owner) has them too.
    let page: ChatsPage =
        serde_json::from_value(st.call_wait(Call::ChatsSince { seq: 0 }).unwrap()).unwrap();
    assert!(text_of(&page.chats, "old1", "u1").is_some());
}

/// "Mine" joins the owner's space: a second phone of the owner's sees the
/// first one's chats, and the window does; someone else's phone does not.
#[test]
fn sec14_a_phone_paired_as_mine_shares_the_owners_chats() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let mut s25 = paired_as(&mut st, None);
    s25.put("c1", Some("Recipes"), vec![user("u1", "pancakes")], false);
    let mut tablet = paired_with(&mut st, generate_key(), "Tablet", None);
    assert_eq!(
        text_of(&tablet.chats(), "c1", "u1").as_deref(),
        Some("pancakes"),
        "a phone paired as Mine doesn't see the owner's chats"
    );
    let page: ChatsPage =
        serde_json::from_value(st.call_wait(Call::ChatsSince { seq: 0 }).unwrap()).unwrap();
    assert!(
        text_of(&page.chats, "c1", "u1").is_some(),
        "the window doesn't see them"
    );
    let mut ana = paired_as(&mut st, Some("Ana"));
    assert!(ana.chats().is_empty());
    assert_eq!(person_of(&mut st, "Tablet"), "owner");
}

/// Removing someone's last phone deletes their chats from the PC (memory
/// and files); pairing the phone again as someone else brings nothing back.
#[test]
fn sec15_removing_a_person_deletes_their_chats_and_pairing_again_brings_nothing() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    mine.put("c1", None, vec![user("u1", "the owner's")], false);
    let mut ana = paired_as(&mut st, Some("Ana"));
    let key = ana.key;
    ana.put(
        "a1",
        Some("Ana's trip"),
        vec![user("u1", "Ana's secret trip")],
        false,
    );
    assert_eq!(ana.chats().len(), 1);
    // Her chat is on the PC's disk now, apart from the owner's.
    let flush = |home: &TestHome| {
        let dir = home.root.join(".local/share/mimir/talk");
        let mut found = vec![];
        let mut stack = vec![dir];
        while let Some(d) = stack.pop() {
            for e in std::fs::read_dir(d).into_iter().flatten().flatten() {
                let p = e.path();
                if p.is_dir() {
                    stack.push(p);
                } else if p.extension().is_some_and(|x| x == "json") {
                    found.push(p);
                }
            }
        }
        found
    };
    let t0 = Instant::now();
    while flush(&home).len() < 2 && t0.elapsed() < Duration::from_secs(10) {
        std::thread::sleep(Duration::from_millis(100));
    }
    let hers = person_of(&mut st, "Ana");
    assert!(
        flush(&home)
            .iter()
            .any(|p| p.to_string_lossy().contains(&format!("people/{hers}"))),
        "her chat isn't kept apart: {:?}",
        flush(&home)
    );
    // Rename, then remove her phone.
    st.call_wait(Call::RenamePerson {
        person: hers.clone(),
        name: "Anna".into(),
    })
    .unwrap();
    let list = st.call_wait(Call::Phones).unwrap();
    assert!(
        list.as_array()
            .unwrap()
            .iter()
            .any(|p| p["person_name"] == "Anna")
    );
    let phone_key = list
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["person"] == hers.as_str())
        .unwrap()["key"]
        .as_str()
        .unwrap()
        .to_string();
    st.call_wait(Call::RemovePhone { key: phone_key }).unwrap();
    assert!(
        flush(&home)
            .iter()
            .all(|p| !p.to_string_lossy().contains("people/")),
        "her chats are still on the PC's disk: {:?}",
        flush(&home)
    );
    // The same phone pairs again, as someone else: nothing comes back.
    let mut again = paired_with(&mut st, key, "Ana", Some("Bo"));
    let chats = again.chats();
    assert!(
        chats.is_empty(),
        "pairing again brought chats back: {chats:?}"
    );
    // The owner's are untouched.
    assert_eq!(
        text_of(&mine.chats(), "c1", "u1").as_deref(),
        Some("the owner's")
    );
}

/// The app's own check (Labs "Run checks") leaves no chat behind.
#[test]
fn sec16_the_labs_check_chat_is_not_kept_for_anyone() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Yes."]);
    picked(&home);
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    for (ph, turn) in [(&mut mine, "check1"), (&mut ana, "check2")] {
        ph.talk("labs-check", turn, "Reply with one word: yes", true);
        let answer = format!("{turn}.a");
        assert!(
            ph.seen.until(&ph.p, |s| s.done.contains_key(&answer)),
            "no answer"
        );
        assert!(
            ph.chats().iter().all(|c| c.id != "labs-check"),
            "the check left a chat in the list"
        );
    }
    let page: ChatsPage =
        serde_json::from_value(st.call_wait(Call::ChatsSince { seq: 0 }).unwrap()).unwrap();
    assert!(page.chats.iter().all(|c| c.id != "labs-check"));
}

/// Another person's chat id never resumes the owner's Claude conversation.
#[test]
fn sec17_another_persons_chat_never_resumes_the_owners_claude_conversation() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Free answer."]);
    picked(&home);
    let mut s: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(home.settings_path()).unwrap()).unwrap();
    s["chats_engine"] = "paid".into();
    std::fs::write(home.settings_path(), s.to_string()).unwrap();
    home.play("run-1.jsonl", "live.jsonl");
    home.play("run-2.jsonl", "live.jsonl");
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    let who = person_of(&mut st, "Ana");
    st.call_wait(Call::PersonOnline {
        person: who,
        on: true,
    })
    .unwrap();
    ana.call(Call::SetEngines {
        chats: Some("paid".into()),
        project: None,
        chat_model: None,
    })
    .unwrap();
    mine.talk("c1", "t1", "Hello", true);
    assert!(
        mine.seen.until(&mine.p, |s| s.done.contains_key("t1.a")),
        "no answer"
    );
    // The owner's chat now has a Claude conversation kept under its id.
    ana.talk("c1", "t2", "Hello", true);
    assert!(
        ana.seen.until(&ana.p, |s| s.done.contains_key("t2.a")),
        "no answer for Ana"
    );
    let runs = home.conversations();
    assert_eq!(runs.len(), 2, "{runs:?}");
    assert!(
        !runs[1].iter().any(|a| a == "--resume"),
        "another person's chat resumed the owner's Claude conversation: {:?}",
        runs[1]
    );
}

/// Pairing a phone again as someone else leaves nothing of its old person
/// for a later person of the same name: chats, online switch, settings.
#[test]
fn sec18_pairing_again_as_someone_else_clears_the_old_person() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["x"]);
    picked(&home);
    let mut st = home.service();
    let mut ana = paired_as(&mut st, Some("Ana"));
    let key = ana.key;
    ana.put("a1", None, vec![user("u1", "Ana's secret")], false);
    let who = person_of(&mut st, "Ana");
    st.call_wait(Call::PersonOnline {
        person: who,
        on: true,
    })
    .unwrap();
    // The same phone is paired again, as Bo: its old connection ends.
    let mut bo = paired_with(&mut st, key, "Ana", Some("Bo"));
    let ended = Instant::now() + Duration::from_secs(10);
    while ana.p.connected() && Instant::now() < ended {
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(bo.chats().is_empty());
    // A later phone called Ana inherits nothing.
    let mut again = paired_as(&mut st, Some("Ana"));
    let chats = again.chats();
    assert!(
        chats.is_empty(),
        "a later Ana inherited the old Ana's chats: {chats:?}"
    );
    let list = st.call_wait(Call::Phones).unwrap();
    let newest = list
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["key"] != hex_key(&key) && p["person_name"] == "Ana")
        .expect("the new Ana isn't listed");
    assert_eq!(
        newest["online"], false,
        "a later Ana inherited the old Ana's online switch"
    );
}

fn hex_key(key: &[u8; 32]) -> String {
    // The phone's link id is its public key; the list shows that id.
    Client::new(*key, false).unwrap().id()
}

/// A long answer (past the time finished ones are remembered) still reaches
/// its person: it expires after it ends, not after it starts.
#[test]
fn sec19_a_long_answer_is_not_lost_while_it_is_still_being_written() {
    let Some(home) = home() else { return };
    set_env("MIMIR_TALK_KEPT_MS", "300");
    let words: &'static [&'static str] = Box::leak(vec!["w "; 300].into_boxed_slice());
    let _ = fake_ollama(words);
    picked(&home);
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    mine.talk("c1", "t1", "Say a lot", true);
    assert!(
        mine.seen.until(&mine.p, |s| s.done.contains_key("t1.a")),
        "a long answer was lost while it was being written"
    );
    assert!(
        mine.seen.done["t1.a"]
            .as_ref()
            .is_ok_and(|t| t.text.len() > 300),
        "the answer came short"
    );
}

/// What the PC has installed and the owner's model choices go to the owner.
#[test]
fn sec20_the_pcs_model_lists_are_for_the_owner() {
    let Some(home) = home() else { return };
    // No model server answers here: the owner is told why, nobody else is.
    set_env("MIMIR_OLLAMA", "http://127.0.0.1:1");
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    let owners = mine.call(Call::Models).unwrap();
    assert!(
        owners["ollama"] != "",
        "the owner was told nothing of the PC's models: {owners}"
    );
    let hers = ana.call(Call::Models).unwrap();
    assert!(
        hers["free"].as_array().is_some_and(Vec::is_empty)
            && hers["free_model"].is_null()
            && hers["switch_to_free"] == false
            && hers["ollama"] == "",
        "Ana was given the PC's model lists: {hers}"
    );
}

/// Each person counts their own changes: nobody can watch how busy the
/// owner is from the numbers they are given.
#[test]
fn sec21_one_persons_numbers_do_not_show_anothers_activity() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let mut mine = paired_as(&mut st, None);
    let mut ana = paired_as(&mut st, Some("Ana"));
    for i in 0..6 {
        mine.put(&format!("c{i}"), None, vec![user("u", "busy")], false);
    }
    let done = ana.put("a1", None, vec![user("u1", "hello")], false);
    assert_eq!(
        done.seq, 1,
        "Ana can see how busy the owner is: her first change was number {}",
        done.seq
    );
    let page: ChatsPage =
        serde_json::from_value(ana.call(Call::ChatsSince { seq: 0 }).unwrap()).unwrap();
    assert_eq!(
        page.seq, 1,
        "Ana can see how busy the owner is: {}",
        page.seq
    );
    let top = ana.seen.seqs.iter().copied().max().unwrap_or(0);
    assert!(
        top <= ana.seen.seqs.len() as u64,
        "Ana can see how busy the owner is from event numbers: {:?}",
        ana.seen.seqs
    );
}

/// Whose phone it is is always said: not saying it, or saying nothing
/// readable, never makes the phone the owner's.
#[test]
fn sec22_a_phone_is_never_the_owners_unless_it_is_said_so() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let info = st.call_wait(Call::LinkInfo).unwrap();
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
    c.pair_start(&code, "Unsaid").expect("the code was refused");
    assert!(service_until(&mut st, |s| s.pairing.is_some()));
    let pending = st.pairing.as_ref().unwrap().0;
    // Nothing said, or blanks: refused here, the pairing still waiting.
    for person in [None, Some("   ".to_string())] {
        let r = st.call_wait(Call::ConfirmPair {
            pending,
            yes: true,
            person,
        });
        assert!(
            r.is_err(),
            "a phone was paired with nobody saying whose it is: {r:?}"
        );
    }
    // Something that reads as no name at all: the link refuses it too.
    st.call_wait(Call::ConfirmPair {
        pending,
        yes: true,
        person: Some("\u{7}".into()),
    })
    .unwrap();
    let e = c
        .pair_finish(Duration::from_secs(10))
        .expect_err("it was paired as the owner's with no name said");
    assert!(e.contains("whose phone"), "{e}");
    let list = st.call_wait(Call::Phones).unwrap();
    assert!(list.as_array().unwrap().is_empty(), "{list}");
}

/// `mimir pair` with nobody answering (its input ends) is not a "No": the
/// phone is told the PC stopped waiting, not that the words don't match.
#[test]
fn a_pairing_nobody_answers_ends_as_a_timeout_not_a_no() {
    let Some(home) = home() else { return };
    // The PC's own wait, cut short for the check.
    set_env("MIMIR_PAIR_CONFIRM_MS", "1500");
    let mut st = home.service();
    let info = st.call_wait(Call::LinkInfo).unwrap();
    let pc = info["id"].as_str().unwrap().to_string();
    let c = Arc::new(Client::new(generate_key(), false).unwrap());
    for a in info["bound"].as_array().unwrap() {
        let a: std::net::SocketAddr = a.as_str().unwrap().parse().unwrap();
        let ip: std::net::IpAddr = if a.is_ipv4() {
            [127, 0, 0, 1].into()
        } else {
            std::net::Ipv6Addr::LOCALHOST.into()
        };
        c.add_hint(std::net::SocketAddr::new(ip, a.port()));
    }
    let _ = pc;
    let (tx, rx) = std::sync::mpsc::channel();
    let phone = c.clone();
    let mut asked = false;
    // The command line asks the questions with no answer coming.
    let code = station_ui::cli::pair_via(
        &mut st,
        &mut std::io::empty(),
        |code| {
            let (code, phone, tx) = (code.to_string(), phone.clone(), tx.clone());
            asked = true;
            std::thread::spawn(move || {
                phone
                    .pair_start(&code, "Nobody home")
                    .expect("the code was refused");
                let _ = tx.send(phone.pair_finish(Duration::from_secs(20)));
            });
        },
        20,
    );
    assert!(asked && code == 1, "the command ended with {code}");
    let e = rx
        .recv_timeout(Duration::from_secs(20))
        .expect("the phone was told nothing")
        .expect_err("it was paired with nobody there");
    assert!(
        !e.contains("don't match"),
        "an unanswered pairing was reported as a No: {e}"
    );
    assert!(e.contains("in time"), "not reported as a timeout: {e}");
}
