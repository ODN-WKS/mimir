//! Stage 1 on the PC, against the real service with a fake phone on this PC
//! (relays off, loopback) and a stand-in Ollama: general chats answered
//! straight from the PC's model, with no Claude Code started.

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

fn home() -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.display().to_string();
    let other = home.root.join("Recipe-Box");
    std::fs::create_dir_all(&other).unwrap();
    home.write_settings(serde_json::json!({
        "projects": [project, other],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
        "aliases": {project.clone(): ["Station"], other.display().to_string(): ["Recipes"]},
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

fn phone(st: &mut mimir_core::client::Station) -> (Client, String) {
    let info = st
        .call_wait(Call::LinkInfo)
        .expect("the link isn't running");
    let id = info["id"].as_str().unwrap().to_string();
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
    (c, id)
}

/// A fake phone that knows the Stage 1 features, paired and past its snapshot.
fn paired(st: &mut mimir_core::client::Station, features: &[&str]) -> (Client, Seen) {
    let (p, pc) = phone(st);
    let code = st
        .call_wait(Call::PairCode)
        .unwrap()
        .as_str()
        .unwrap()
        .to_string();
    p.pair_start(&code, "Fake phone")
        .expect("the code was refused");
    assert!(service_until(st, |s| s.pairing.is_some()));
    let pending = st.pairing.as_ref().unwrap().0;
    st.call_wait(Call::ConfirmPair {
        pending,
        yes: true,
        person: Some(mimir_core::proto::WHOSE_MINE.into()),
    })
    .unwrap();
    p.pair_finish(Duration::from_secs(10)).expect("not paired");
    p.connect(&pc).expect("the paired phone couldn't connect");
    let mut seen = Seen::default();
    p.call(Call::Hello {
        proto: mimir_core::proto::PROTO,
        app_version: "test".into(),
        client_name: "Fake phone".into(),
        env: vec![],
        features: features.iter().map(|f| f.to_string()).collect(),
    })
    .unwrap();
    assert!(seen.until(&p, |s| s.ready), "no snapshot after Hello");
    (p, seen)
}

/// What a fake phone knows.
#[derive(Default)]
struct Seen {
    replies: HashMap<u64, Result<serde_json::Value, String>>,
    features: Vec<String>,
    pieces: HashMap<String, String>,
    done: HashMap<String, Result<ChatTurn, String>>,
    changed: Vec<ChatRecord>,
    other: Vec<Event>,
    ready: bool,
}

impl Seen {
    fn take(&mut self, p: &Client) {
        while let Some(m) = p.next_event(Duration::from_millis(20)) {
            match m.event {
                Event::Ready => self.ready = true,
                Event::Welcome { features, .. } => self.features = features,
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
        switch_note: None,
        pictures: vec![],
        files: vec![],
    }
}

fn talk(chat: &str, id: &str, turns: Vec<ChatTurn>, rules: Option<&str>) -> Call {
    Call::Talk {
        chat_id: chat.into(),
        turn_id: id.into(),
        turns,
        lookup: None,
        sources: vec![],
        effort: None,
        ground_rules: rules.map(str::to_string),
        task: None,
        chat_only: false,
    }
}

/// P3's owner-way test, automatic: a chat is answered by the PC's own model,
/// streamed, with the ground rules; no Claude Code is started.
#[test]
fn p3_a_chat_is_answered_by_the_pcs_model_without_claude_code() {
    let Some(home) = home() else { return };
    let asked = fake_ollama(&["Oslo is ", "the capital ", "of Norway."]);
    picked(&home);
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, &["search", "lookup", "talk"]);
    assert!(
        seen.features.iter().any(|f| f == "talk"),
        "{:?}",
        seen.features
    );
    let f: Filed = serde_json::from_value(
        seen.call(
            &p,
            talk(
                "c1",
                "t1",
                vec![user("t1", "What's the capital of Norway?")],
                Some("Be brief."),
            ),
        )
        .unwrap(),
    )
    .unwrap();
    let Filed::Talk {
        answer,
        model_words,
        waiting,
        ..
    } = f
    else {
        panic!("not answered on the PC: {f:?}")
    };
    assert_eq!(answer, "t1.a");
    assert_eq!(model_words, "Qwen 3.6 · Your PC");
    assert_eq!(waiting.as_deref(), Some("Waking your PC's model…"));
    assert!(seen.until(&p, |s| s.done.contains_key("t1.a")), "no answer");
    assert_eq!(seen.pieces["t1.a"], "Oslo is the capital of Norway.");
    let turn = seen.done["t1.a"].clone().unwrap();
    assert_eq!(turn.text, "Oslo is the capital of Norway.");
    // Asked again by its id (after a dropped link): the finished turn.
    let st2: TalkState = serde_json::from_value(
        seen.call(
            &p,
            Call::TalkTurn {
                chat_id: "c1".into(),
                turn_id: "t1".into(),
            },
        )
        .unwrap(),
    )
    .unwrap();
    assert!(matches!(st2, TalkState::Done { turn } if turn.text.starts_with("Oslo")));
    // The model got the place line and the ground rules, then the message.
    let body = asked.lock().unwrap()[0].clone();
    let sys = body["messages"][0]["content"].as_str().unwrap();
    assert!(sys.starts_with("You are Mímir"), "{sys}");
    assert!(sys.contains("Be brief."), "{sys}");
    assert_eq!(
        body["messages"][1]["content"],
        "What's the capital of Norway?"
    );
    assert_eq!(body["keep_alive"], "10m");
    // Both turns are kept on the PC, numbered.
    assert!(turn.seq > 0);
    let page: ChatsPage =
        serde_json::from_value(seen.call(&p, Call::ChatsSince { seq: 0 }).unwrap()).unwrap();
    let ids: Vec<&str> = page.chats[0].turns.iter().map(|t| t.id.as_str()).collect();
    assert_eq!(ids, vec!["t1", "t1.a"]);
    // No Claude Code was started for it.
    assert!(
        home.conversations().is_empty(),
        "{:?}",
        home.conversations()
    );
}

#[test]
fn p3_with_no_model_picked_the_phone_answers() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["x"]);
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, &["talk"]);
    let f: Filed = serde_json::from_value(
        seen.call(&p, talk("c1", "t1", vec![user("t1", "Hi")], None))
            .unwrap(),
    )
    .unwrap();
    assert!(matches!(f, Filed::OnPhone { .. }), "{f:?}");
    assert!(home.conversations().is_empty());
}

/// An older phone (no features in its Hello) never gets the new events.
#[test]
fn p3_an_older_phone_gets_no_talk_events() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Hello ", "there."]);
    picked(&home);
    let mut st = home.service();
    let (old, mut old_seen) = paired(&mut st, &[]);
    let (new, mut seen) = paired(&mut st, &["talk"]);
    seen.call(&new, talk("c1", "t1", vec![user("t1", "Hi")], None))
        .unwrap();
    assert!(seen.until(&new, |s| s.done.contains_key("t1.a")));
    let until = Instant::now() + Duration::from_secs(1);
    while Instant::now() < until {
        old_seen.take(&old);
    }
    assert!(old_seen.pieces.is_empty() && old_seen.done.is_empty());
}

fn put(
    p: &Client,
    seen: &mut Seen,
    chat: &str,
    turns: Vec<ChatTurn>,
    deleted: bool,
) -> ChatPutDone {
    serde_json::from_value(
        seen.call(
            p,
            Call::ChatPut {
                chat_id: chat.into(),
                name: None,
                turns,
                deleted,
            },
        )
        .unwrap(),
    )
    .unwrap()
}

fn since(p: &Client, seen: &mut Seen, seq: u64) -> ChatsPage {
    serde_json::from_value(seen.call(p, Call::ChatsSince { seq }).unwrap()).unwrap()
}

/// P4's owner-way test, automatic: chats one phone had on its own reach
/// the PC when it reconnects, and the other phone sees them; a chat deleted
/// on one phone goes from the other and never comes back.
#[test]
fn p4_chats_sync_through_the_pc_and_deletes_stay_deleted() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["x"]);
    let mut st = home.service();
    let feats = ["talk", "chat_sync"];
    let (s8, mut s8_seen) = paired(&mut st, &feats);
    let (s25, mut s25_seen) = paired(&mut st, &feats);
    assert!(s8_seen.features.iter().any(|f| f == "chat_sync"));
    let start = since(&s25, &mut s25_seen, 0).seq;
    // Two chats the S8 had in airplane mode, sent when it's back.
    let mut answer = user("a1.a", "Oslo.");
    answer.user = false;
    answer.ran = Some(mimir_core::proto::Ran::Phone);
    put(
        &s8,
        &mut s8_seen,
        "chat-a",
        vec![user("a1", "Capital of Norway?"), answer],
        false,
    );
    put(
        &s8,
        &mut s8_seen,
        "chat-b",
        vec![user("b1", "A joke please")],
        false,
    );
    // The S25 hears of them at once, and finds them when it asks.
    assert!(
        s25_seen.until(&s25, |s| s.changed.len() >= 2),
        "no ChatChanged"
    );
    let page = since(&s25, &mut s25_seen, start);
    let mut ids: Vec<&str> = page.chats.iter().map(|c| c.id.as_str()).collect();
    ids.sort();
    assert_eq!(ids, vec!["chat-a", "chat-b"]);
    assert_eq!(page.chats.iter().map(|c| c.turns.len()).sum::<usize>(), 3);
    // Sent again (the S8 wasn't sure it arrived): nothing changes.
    let before = since(&s25, &mut s25_seen, 0).seq;
    put(
        &s8,
        &mut s8_seen,
        "chat-b",
        vec![user("b1", "A joke please")],
        false,
    );
    assert_eq!(since(&s25, &mut s25_seen, 0).seq, before);
    // Deleted on the S25: the S8 learns it, and its late copy can't bring it back.
    let done = put(&s25, &mut s25_seen, "chat-b", vec![], true);
    assert!(done.deleted);
    let page = since(&s8, &mut s8_seen, before);
    assert!(
        page.chats
            .iter()
            .any(|c| c.id == "chat-b" && c.deleted.is_some())
    );
    let done = put(
        &s8,
        &mut s8_seen,
        "chat-b",
        vec![user("b2", "Another")],
        false,
    );
    assert!(done.deleted, "a deleted chat came back");
    // Kept encrypted on the PC.
    // The store writes behind (a moment after the change), so wait for the file.
    let file = home.root.join(".local/share/mimir/talk/chat-a.json");
    let until = Instant::now() + Duration::from_secs(5);
    while !file.exists() && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(50));
    }
    let raw = std::fs::read(&file).unwrap();
    assert!(raw.starts_with(b"MIMIR1"));
    assert!(!String::from_utf8_lossy(&raw).contains("Norway"));
}

fn talked(seen: &mut Seen, p: &Client, call: Call) -> Filed {
    serde_json::from_value(seen.call(p, call).unwrap()).unwrap()
}

/// P5's owner-way test, automatic: everyday questions stay chats (nothing
/// lands in General), a project named on purpose starts a task there, a
/// follow-up goes to that task, and after ✕ (no task sent) or "Just chat"
/// it is a chat again.
#[test]
fn p5_only_a_project_named_on_purpose_starts_a_task() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Sure."]);
    picked(&home);
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, &["talk", "chat_sync"]);
    for (i, q) in [
        "What's the newest Android version?",
        "Give me a random number",
    ]
    .iter()
    .enumerate()
    {
        let id = format!("q{i}");
        let f = talked(&mut seen, &p, talk("c1", &id, vec![user(&id, q)], None));
        assert!(matches!(f, Filed::Talk { .. }), "{q:?}: {f:?}");
    }
    assert!(!home.root.join("Projects/General").exists());
    assert!(home.conversations().is_empty());
    // Named on purpose: a task in that project.
    let recipe = home.root.join("Recipe-Box").display().to_string();
    let f = talked(
        &mut seen,
        &p,
        talk(
            "c1",
            "q2",
            vec![user("q2", "In Recipe Box, what version is it?")],
            None,
        ),
    );
    let Filed::NewTask { project, task } = f else {
        panic!("not a task: {f:?}")
    };
    assert_eq!(project, recipe);
    assert!(service_until(&mut st, |s| s
        .tasks
        .iter()
        .any(|v| v.task.id == task)));
    // The follow-up, sent with the card's task, goes to it.
    let mut follow = talk(
        "c1",
        "q3",
        vec![user("q3", "And when was it released?")],
        None,
    );
    if let Call::Talk { task: t, .. } = &mut follow {
        *t = Some(task.clone());
    }
    let f = talked(&mut seen, &p, follow);
    assert!(
        matches!(&f, Filed::SameTask { task: t, .. } if *t == task),
        "{f:?}"
    );
    // After ✕ (no task): a chat again.
    let f = talked(
        &mut seen,
        &p,
        talk("c1", "q4", vec![user("q4", "And the weather?")], None),
    );
    assert!(matches!(f, Filed::Talk { .. }), "{f:?}");
    // "Just chat": even a project's name stays a chat.
    let mut just = talk(
        "c1",
        "q5",
        vec![user("q5", "Recipes: is that a good name?")],
        None,
    );
    if let Call::Talk { chat_only, .. } = &mut just {
        *chat_only = true;
    }
    let f = talked(&mut seen, &p, just);
    assert!(matches!(f, Filed::Talk { .. }), "{f:?}");
    // One conversation started (the task); the follow-up carried it on.
    let fresh = home
        .conversations()
        .iter()
        .filter(|a| !a.iter().any(|x| x == "--resume"))
        .count();
    assert_eq!(fresh, 1, "{:?}", home.conversations());
}

/// Chats on Claude (chosen in Settings): one Claude Code turn with no
/// tools, customisations or memory, in its own folder; when Claude can't
/// answer, the free model does, and says so.
#[test]
fn p5_a_paid_chat_runs_without_tools_and_falls_back_to_free() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["Free answer."]);
    picked(&home);
    let mut s: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(home.settings_path()).unwrap()).unwrap();
    s["chats_engine"] = "paid".into();
    std::fs::write(home.settings_path(), s.to_string()).unwrap();
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, &["talk", "chat_sync"]);
    let f = talked(
        &mut seen,
        &p,
        talk("c1", "t1", vec![user("t1", "Hello")], None),
    );
    assert!(
        matches!(&f, Filed::Talk { ran: mimir_core::proto::Ran::Paid, model_words, .. } if model_words == "Online · paid"),
        "{f:?}"
    );
    assert!(seen.until(&p, |s| s.done.contains_key("t1.a")));
    let t = seen.done["t1.a"].clone().unwrap();
    assert_eq!(t.text, "The tidy-up is under way: notes.txt is sorted.");
    assert_eq!(t.ran, Some(mimir_core::proto::Ran::Paid));
    let run = home.conversations().pop().unwrap();
    for flag in ["--safe-mode", "--strict-mcp-config", "--tools"] {
        assert!(run.iter().any(|a| a == flag), "{flag} missing: {run:?}");
    }
    let i = run.iter().position(|a| a == "--tools").unwrap();
    assert_eq!(run[i + 1], "", "tools weren't all denied: {run:?}");
    let dir = home.root.join(".local/share/mimir/talk-paid");
    assert_eq!(
        home.claude_folders().last().unwrap(),
        &dir.display().to_string()
    );
    // No second recording: Claude fails, and the free model answers.
    let f = talked(
        &mut seen,
        &p,
        talk("c1", "t2", vec![user("t2", "Again")], None),
    );
    assert!(matches!(f, Filed::Talk { .. }), "{f:?}");
    assert!(seen.until(&p, |s| s.done.contains_key("t2.a")));
    let t = seen.done["t2.a"].clone().unwrap();
    assert_eq!(t.text, "Free answer.");
    assert_eq!(t.ran, Some(mimir_core::proto::Ran::Pc));
    assert!(t.model_words.unwrap().starts_with("Free · Your PC"));
}

fn truth_of(seen: &Seen) -> Option<mimir_core::proto::Truth> {
    seen.other.iter().rev().find_map(|e| match e {
        Event::Truth { truth } => Some((**truth).clone()),
        _ => None,
    })
}

/// P6's owner-way test, automatic: a task started from a phone shows as
/// Working on the phone (from the truth) and in the window's own copy within
/// 2 s, and as Done within 2 s of its end; an older phone gets no truth.
#[test]
fn p6_the_truth_says_working_then_done_everywhere() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["x"]);
    home.play("run-1.jsonl", "live.jsonl");
    home.hold_replies();
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, &["talk", "chat_sync", "truth"]);
    let (old, mut old_seen) = paired(&mut st, &[]);
    assert!(truth_of(&seen).is_some(), "no truth on connect");
    let project = home.project.display().to_string();
    let id = seen
        .call(
            &p,
            Call::NewTask {
                files: vec![],
                ground_rules: None,
                project: project.clone(),
                text: "Run sleep 60 in bash, then say done.".into(),
                pictures: vec![],
                mode: mimir_core::proto::Mode::Ask,
                model: None,
                effort: None,
                separate_copy: false,
                msg_id: Some("m1".into()),
            },
        )
        .unwrap()
        .as_str()
        .unwrap()
        .to_string();
    let state =
        |s: &Seen| truth_of(s).and_then(|t| t.tasks.iter().find(|x| x.id == id).map(|x| x.state));
    let started = Instant::now();
    assert!(
        seen.until(&p, |s| state(s)
            == Some(mimir_core::proto::TaskState::Working)),
        "the phone never saw Working"
    );
    assert!(
        started.elapsed() < Duration::from_secs(2),
        "{:?}",
        started.elapsed()
    );
    let t = truth_of(&seen).unwrap();
    assert_eq!(t.counts.working, 1);
    assert!(
        t.projects
            .iter()
            .any(|x| x.path == project && x.working == 1)
    );
    // The window's own copy agrees.
    assert!(service_until(&mut st, |s| s
        .truth
        .as_ref()
        .is_some_and(|t| t.counts.working == 1)));
    // The work ends: Done within 2 s.
    home.let_through(1000);
    let ended = Instant::now();
    assert!(
        seen.until(&p, |s| state(s) == Some(mimir_core::proto::TaskState::Done)),
        "the phone never saw Done"
    );
    assert!(
        ended.elapsed() < Duration::from_secs(2),
        "{:?}",
        ended.elapsed()
    );
    assert_eq!(truth_of(&seen).unwrap().counts.working, 0);
    // An older phone never gets it.
    let until = Instant::now() + Duration::from_millis(500);
    while Instant::now() < until {
        old_seen.take(&old);
    }
    assert!(truth_of(&old_seen).is_none());
}

/// The phone reads and sets Settings › Models' two lines.
#[test]
fn p5_the_phone_reads_and_sets_what_answers() {
    let Some(home) = home() else { return };
    let _ = fake_ollama(&["x"]);
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, &["engines"]);
    assert!(seen.features.iter().any(|f| f == "engines"));
    let v = seen.call(&p, Call::Models).unwrap();
    assert_eq!(
        (v["chats_engine"].as_str(), v["project_engine"].as_str()),
        (Some("free"), Some("claude"))
    );
    let v = seen
        .call(
            &p,
            Call::SetEngines {
                chats: Some("paid".into()),
                project: Some("free".into()),
                chat_model: None,
            },
        )
        .unwrap();
    assert_eq!(
        (v["chats_engine"].as_str(), v["project_engine"].as_str()),
        (Some("paid"), Some("free"))
    );
    // Which Claude model chats use: an alias, applied to every chat; "auto" is Claude Code's own choice.
    let v = seen
        .call(
            &p,
            Call::SetEngines {
                chats: None,
                project: None,
                chat_model: Some("sonnet".into()),
            },
        )
        .unwrap();
    assert_eq!(v["chats_model"].as_str(), Some("sonnet"));
    let v = seen.call(&p, Call::Models).unwrap();
    assert_eq!(v["chats_model"].as_str(), Some("sonnet"));
    let ids: Vec<&str> = v["chat_models"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|m| m["id"].as_str())
        .collect();
    assert_eq!(ids, ["auto", "haiku", "sonnet", "opus", "fable"]);
    assert!(
        seen.call(
            &p,
            Call::SetEngines {
                chats: None,
                project: None,
                chat_model: Some("claude-sonnet-4-6-20250929".into()),
            },
        )
        .is_err(),
        "a dated id is refused: aliases only"
    );
    assert!(
        seen.call(
            &p,
            Call::SetEngines {
                chats: Some("x".into()),
                project: None,
                chat_model: None
            }
        )
        .is_err()
    );
}
