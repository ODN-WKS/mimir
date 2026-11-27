//! The phone link against the real service, with a fake phone on this PC
//! (relays off, loopback): pairing only after "Yes, they match" in the
//! window, the refusals, a message with its reply, Allow and Don't allow,
//! and a removed phone cut off.

mod common;
use common::*;
use egui_kittest::kittest::{NodeT, Queryable};
use mimir_core::proto::{Call, Choice, Event, Mode};
use mimir_link::client::{Client, generate_key};
use std::collections::HashMap;
use std::time::{Duration, Instant};

/// A home whose service keeps the link off n0's relays (loopback only).
fn home() -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.clone();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
    }));
    Some(home)
}

/// A fake phone that knows where this home's service listens.
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

fn code(st: &mut mimir_core::client::Station) -> String {
    st.call_wait(Call::PairCode)
        .expect("no pairing code")
        .as_str()
        .unwrap()
        .to_string()
}

fn phones(st: &mut mimir_core::client::Station) -> usize {
    st.call_wait(Call::Phones)
        .ok()
        .and_then(|v| v.as_array().map(|a| a.len()))
        .unwrap_or(0)
}

/// Pairs a fake phone with the service's own client saying yes.
fn paired(st: &mut mimir_core::client::Station) -> (Client, String) {
    let (p, pc) = phone(st);
    let c = code(st);
    p.pair_start(&c, "Fake phone")
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
    (p, pc)
}

/// What the fake phone knows: task asks and Claude's words, and replies.
#[derive(Default)]
struct Seen {
    asks: HashMap<String, usize>,
    /// Each task's first waiting request, by its needs-you id.
    ask_ids: HashMap<String, String>,
    claude: HashMap<String, Vec<String>>,
    live: HashMap<String, String>,
    replies: HashMap<u64, Result<serde_json::Value, String>>,
    ready: bool,
}

impl Seen {
    fn take(&mut self, p: &Client) {
        while let Some(m) = p.next_event(Duration::from_millis(20)) {
            match m.event {
                Event::Ready => self.ready = true,
                Event::Task { header } => {
                    self.asks.insert(header.id.clone(), header.asks.len());
                    if let Some(r) = header.asks.first().and_then(|a| {
                        serde_json::from_value::<mimir_core::approvals::Request>(a.clone()).ok()
                    }) {
                        let id = mimir_core::needs::permission_id(&header.id, &r);
                        self.ask_ids.insert(header.id.clone(), id);
                    }
                }
                Event::Entries {
                    task,
                    from,
                    entries,
                    ..
                } => {
                    let v = self.claude.entry(task).or_default();
                    let _ = from;
                    for e in entries {
                        if let mimir_core::proto::Entry::Claude(t) = e {
                            v.push(t);
                        }
                    }
                }
                Event::Live { task, text } => {
                    self.live.insert(task, text);
                }
                Event::Reply { call_id, result } => {
                    self.replies.insert(call_id, result);
                }
                Event::Screen { .. } | Event::Settings { .. } | Event::PairPending { .. } => {
                    panic!("a phone was sent an event it must never get")
                }
                _ => {}
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

    fn reply(&mut self, p: &Client, id: u64) -> Result<serde_json::Value, String> {
        assert!(self.until(p, |s| s.replies.contains_key(&id)), "no reply");
        self.replies.remove(&id).unwrap()
    }
}

fn hello(p: &Client, seen: &mut Seen) {
    p.call(Call::Hello {
        proto: mimir_core::proto::PROTO,
        app_version: "test".into(),
        client_name: "Fake phone".into(),
        env: vec![],
        features: vec![],
    })
    .unwrap();
    assert!(seen.until(p, |s| s.ready), "no snapshot after Hello");
}

#[test]
fn phone_pairs_only_after_yes_they_match_in_the_window() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    let mut st = home.service();
    let (p, _) = phone(&mut st);
    let c = code(&mut st);
    let words = p
        .pair_start(&c, "Galaxy test")
        .expect("the code was refused");
    assert!(
        wait_for(&mut h, |h| shows(h, "Pair this phone?")
            && shows(h, &words.join("  ·  "))),
        "the window didn't ask about the phone with its words"
    );
    assert_eq!(
        phones(&mut st),
        0,
        "the phone was paired before anyone said yes"
    );
    // Whose phone it is has no preset: Yes waits until it is chosen.
    assert!(
        shows(&h, "Whose phone is this?"),
        "the window didn't ask whose phone it is"
    );
    click(&mut h, "Yes, they match");
    settle(&mut h);
    assert_eq!(
        phones(&mut st),
        0,
        "paired before the owner said whose phone it is"
    );
    click(&mut h, "Mine");
    settle(&mut h);
    click(&mut h, "Yes, they match");
    settle(&mut h);
    p.pair_finish(Duration::from_secs(10))
        .expect("the phone wasn't told it's paired");
    assert_eq!(phones(&mut st), 1);
    close(h);
}

/// Whether the button with exactly these words is on screen but can't be pressed.
fn button_off(h: &egui_kittest::Harness<'static, station_ui::App>, words: &str) -> bool {
    h.query_all_by_role_and_label(eframe::egui::accesskit::Role::Button, words)
        .next()
        .is_some_and(|n| n.accesskit_node().is_disabled())
}

#[test]
fn a_second_pairing_starts_with_no_answer_chosen_after_the_first_ended() {
    let Some(home) = home() else { return };
    // The first phone is never answered: its pairing ends on its own.
    set_env("MIMIR_PAIR_CONFIRM_MS", "2500");
    let mut h = home.open();
    let mut st = home.service();
    let (p, _) = phone(&mut st);
    p.pair_start(&code(&mut st), "First phone").unwrap();
    assert!(
        wait_for(&mut h, |h| shows(h, "First phone wants to pair")),
        "the window didn't ask about the first phone\n{}",
        screen_text(&h)
    );
    click(&mut h, "Mine");
    settle(&mut h);
    assert!(!button_off(&h, "Yes, they match"), "Mine didn't count");
    // It ends (nobody confirmed in time); the card goes away.
    assert!(
        wait_for(&mut h, |h| !shows(h, "Pair this phone?")),
        "the first pairing's card stayed\n{}",
        screen_text(&h)
    );
    let (p2, _) = phone(&mut st);
    p2.pair_start(&code(&mut st), "Second phone").unwrap();
    assert!(
        wait_for(&mut h, |h| shows(h, "Second phone wants to pair")),
        "the window didn't ask about the second phone\n{}",
        screen_text(&h)
    );
    assert!(
        button_off(&h, "Yes, they match"),
        "Yes was ready before anyone said whose the second phone is\n{}",
        screen_text(&h)
    );
    click(&mut h, "Someone else's");
    settle(&mut h);
    assert!(
        button_off(&h, "Yes, they match"),
        "Yes was ready with no name typed"
    );
    close(h);
}

#[test]
fn phone_refused_with_a_wrong_used_or_expired_code_after_no_and_after_the_wait() {
    let Some(home) = home() else { return };
    set_env("MIMIR_PAIR_CONFIRM_MS", "1500");
    set_env("MIMIR_PAIR_LIFE_MS", "3000");
    let mut st = home.service();
    let (p, _) = phone(&mut st);

    let mut wrong = mimir_link::Code::parse(&code(&mut st)).unwrap();
    wrong.secret = [7; 32];
    let e = p.pair_start(&wrong.text(), "x").unwrap_err();
    assert!(e.contains("doesn't match"), "wrong code: {e}");

    let c = code(&mut st);
    p.pair_start(&c, "x").unwrap();
    assert!(service_until(&mut st, |s| s.pairing.is_some()));
    let pending = st.pairing.as_ref().unwrap().0;
    st.call_wait(Call::ConfirmPair {
        pending,
        yes: false,
        person: None,
    })
    .unwrap();
    let e = p.pair_finish(Duration::from_secs(10)).unwrap_err();
    assert!(e.contains("don't match"), "after No: {e}");
    let e = p.pair_start(&c, "x").unwrap_err();
    assert!(e.contains("no longer works"), "used code: {e}");

    let c = code(&mut st);
    p.pair_start(&c, "x").unwrap();
    let e = p.pair_finish(Duration::from_secs(10)).unwrap_err();
    assert!(e.contains("in time"), "after the wait: {e}");

    let c = code(&mut st);
    std::thread::sleep(Duration::from_millis(3300));
    let e = p.pair_start(&c, "x").unwrap_err();
    assert!(e.contains("expired"), "expired code: {e}");
    assert_eq!(phones(&mut st), 0);
}

#[test]
fn phone_sends_a_message_sees_the_reply_and_answers_allow_and_dont_allow() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "allow.jsonl");
    home.play("run-2.jsonl", "allow.jsonl");
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).expect("the paired phone couldn't connect");
    let mut seen = Seen::default();
    hello(&p, &mut seen);

    // Calls a phone may not make are refused.
    let id = p.call(Call::PairCode).unwrap();
    assert!(seen.reply(&p, id).is_err(), "a phone made a pairing code");
    let project = {
        let id = p.call(Call::Projects).unwrap();
        let v = seen.reply(&p, id).unwrap();
        v["last"].as_str().expect("no last project").to_string()
    };
    let id = p
        .call(Call::NewTask {
            typed: false,
            files: vec![],
            ground_rules: None,
            project: project.clone(),
            text: "x".into(),
            pictures: vec![],
            mode: Mode::NoAsk,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: None,
        })
        .unwrap();
    assert!(
        seen.reply(&p, id).is_err(),
        "a phone started a task that never asks"
    );

    for (choice, expect) in [(Choice::Allow, "allow"), (Choice::DontAllow, "no")] {
        let sent = Instant::now();
        let id = p
            .call(Call::NewTask {
                typed: false,
                files: vec![],
                ground_rules: None,
                project: project.clone(),
                text: "Run this Bash command: touch a.txt".into(),
                pictures: vec![],
                mode: Mode::Ask,
                model: None,
                effort: None,
                separate_copy: false,
                msg_id: None,
            })
            .unwrap();
        let task = seen.reply(&p, id).expect("the task didn't start");
        let task = task.as_str().unwrap().to_string();
        assert!(
            sent.elapsed() < Duration::from_secs(2),
            "starting a task took {:?}",
            sent.elapsed()
        );
        assert!(
            seen.until(&p, |s| s.asks.get(&task).copied().unwrap_or(0) == 1),
            "the permission request never reached the phone"
        );
        // An older phone's answer names no request: refused, as a late tap
        // could land on another request.
        let id = p
            .call(Call::Answer {
                task: task.clone(),
                choice,
                ask_id: None,
            })
            .unwrap();
        assert!(
            seen.reply(&p, id).is_err(),
            "an answer naming no request was taken"
        );
        // An answer naming another request is refused too.
        let id = p
            .call(Call::Answer {
                task: task.clone(),
                choice,
                ask_id: Some(format!("perm:{task}:000000000000")),
            })
            .unwrap();
        assert!(
            seen.reply(&p, id).is_err(),
            "an answer for another request was taken"
        );
        let ask_id = seen.ask_ids.get(&task).cloned();
        assert!(ask_id.is_some(), "the request had no id");
        let asked = Instant::now();
        let id = p
            .call(Call::Answer {
                task: task.clone(),
                choice,
                ask_id: ask_id.clone(),
            })
            .unwrap();
        seen.reply(&p, id).expect("the answer was refused");
        // The same tap again is refused: that request was answered.
        let id = p
            .call(Call::Answer {
                task: task.clone(),
                choice: Choice::Allow,
                ask_id,
            })
            .unwrap();
        assert!(seen.reply(&p, id).is_err(), "an answer was taken twice");
        assert!(
            asked.elapsed() < Duration::from_secs(1),
            "the answer took {:?} to be accepted",
            asked.elapsed()
        );
        let answers = || home.hook_answers();
        let n = if expect == "allow" { 1 } else { 2 };
        let until = Instant::now() + Duration::from_secs(20);
        while answers().len() < n && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(25));
        }
        assert_eq!(
            told(answers().get(n - 1)),
            expect,
            "the phone's answer didn't reach Claude: {:?}",
            answers()
        );
        // After Allow the recording goes on to Claude's reply.
        if expect == "allow" {
            assert!(
                seen.until(&p, |s| s.claude.get(&task).is_some_and(|v| !v.is_empty())),
                "Claude's reply never reached the phone"
            );
        }
    }
}

#[test]
fn phone_sees_the_reply_while_it_is_written() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.hold_replies();
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).unwrap();
    let mut seen = Seen::default();
    hello(&p, &mut seen);
    let id = p
        .call(Call::NewTask {
            typed: false,
            files: vec![],
            ground_rules: None,
            project: home.project.display().to_string(),
            text: "Tidy up notes.txt.".into(),
            pictures: vec![],
            mode: Mode::Ask,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: None,
        })
        .unwrap();
    let task = seen.reply(&p, id).unwrap().as_str().unwrap().to_string();
    home.let_through(3);
    assert!(
        seen.until(&p, |s| s.live.get(&task).is_some_and(|t| !t.is_empty())
            || s.claude.get(&task).is_some_and(|v| !v.is_empty())),
        "the reply didn't show on the phone while Claude was writing it"
    );
    home.let_through(100);
}

#[test]
fn phone_is_cut_off_when_removed() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).unwrap();
    let mut seen = Seen::default();
    hello(&p, &mut seen);
    st.call_wait(Call::RemovePhone { key: p.id() }).unwrap();
    let until = Instant::now() + Duration::from_secs(10);
    while p.connected() && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(25));
    }
    assert!(!p.connected(), "the removed phone stayed connected");
    let _ = p.connect(&pc);
    let until = Instant::now() + Duration::from_secs(10);
    while p.connected() && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(25));
    }
    assert!(!p.connected(), "a removed phone connected again");
    assert_eq!(phones(&mut st), 0);
}

/// Android can't tell iroh that the phone's network changed (Wi-Fi to mobile
/// data, the app back in front after Android cut its network), so the app
/// does. Telling it mustn't drop a working session, and a phone whose
/// session ended gets back in at once, not after a long wait (on the S25
/// the link went quiet and didn't come back).
#[test]
fn phone_keeps_its_session_through_a_network_change_and_reconnects_at_once() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).unwrap();
    let mut seen = Seen::default();
    hello(&p, &mut seen);
    for _ in 0..3 {
        p.network_changed();
    }
    let id = p.call(Call::Projects).unwrap();
    assert!(
        seen.reply(&p, id).is_ok(),
        "the session stopped working after a network change"
    );
    assert!(p.connected(), "a network change dropped the session");

    // The session ends; the phone tells the link and connects again.
    p.disconnect();
    p.network_changed();
    let start = Instant::now();
    p.connect(&pc).expect("the phone couldn't connect again");
    assert!(
        start.elapsed() < Duration::from_secs(3),
        "connecting again took {:?}",
        start.elapsed()
    );
    let mut seen = Seen::default();
    hello(&p, &mut seen);
}

#[test]
fn phone_makes_a_new_project_folder_on_the_pc_and_picks_an_existing_one() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).expect("the paired phone couldn't connect");
    let mut seen = Seen::default();
    hello(&p, &mut seen);
    // This home's one project sits right in the home folder, so new ones go
    // to ~/Projects (the home folder itself is never used).
    let root = std::path::Path::new(&home.project)
        .parent()
        .unwrap()
        .to_path_buf();
    let parent = root.join("Projects");

    // A new name makes the folder beside the other projects and lists it.
    let id = p
        .call(Call::NewProject {
            name: "Fresh Idea".into(),
        })
        .unwrap();
    let v = seen.reply(&p, id).expect("the PC refused a new project");
    assert_eq!(v["made"], true);
    let made = parent.join("Fresh Idea");
    assert!(made.is_dir(), "no folder at {}", made.display());
    let id = p.call(Call::Projects).unwrap();
    let list = seen.reply(&p, id).unwrap();
    assert!(
        list["projects"]
            .as_array()
            .unwrap()
            .iter()
            .any(|x| x["name"] == "Fresh Idea"),
        "the new project isn't listed: {list}"
    );

    // A git project beside it that isn't listed yet can be picked too; a
    // git worktree (its .git a file) can't.
    std::fs::create_dir_all(parent.join("Forge/.git")).unwrap();
    std::fs::create_dir_all(parent.join("Forge-copy")).unwrap();
    std::fs::write(parent.join("Forge-copy/.git"), "gitdir: elsewhere").unwrap();
    let id = p.call(Call::Projects).unwrap();
    let list = seen.reply(&p, id).unwrap();
    let names: Vec<&str> = list["projects"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|x| x["name"].as_str())
        .collect();
    assert!(
        names.contains(&"Forge"),
        "an unlisted git project is missing: {list}"
    );
    assert!(
        !names.contains(&"Forge-copy"),
        "a worktree is listed: {list}"
    );

    // The same name again just picks it; paths and odd names are refused.
    let id = p
        .call(Call::NewProject {
            name: "Fresh Idea".into(),
        })
        .unwrap();
    assert_eq!(seen.reply(&p, id).unwrap()["made"], false);
    for bad in ["../outside", "a/b", ".hidden", ""] {
        let id = p.call(Call::NewProject { name: bad.into() }).unwrap();
        assert!(seen.reply(&p, id).is_err(), "{bad:?} was taken");
    }
    assert!(!root.join("outside").exists() && !parent.join("outside").exists());
}

#[test]
fn phone_gets_the_pcs_free_models_and_the_phone_models_once_paired() {
    use std::io::{Read, Write};
    // A made-up Ollama on this PC with one ordinary and one uncensored model.
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = "{\"models\":[{\"name\":\"qwen3.6:35b-a3b-coding\"},{\"name\":\"huihui_ai/gpt-oss-abliterated:20b\"}]}";
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).expect("the paired phone couldn't connect");
    let mut seen = Seen::default();
    hello(&p, &mut seen);

    let id = p.call(Call::Models).unwrap();
    let v = seen.reply(&p, id).expect("the PC refused the model list");
    let free = v["free"].as_array().expect("no free models in the reply");
    let named = |n: &str| free.iter().find(|m| m["name"] == n);
    let plain = named("qwen3.6:35b-a3b-coding").expect("the free model didn't reach the phone");
    assert_eq!(plain["id"], "ollama:qwen3.6:35b-a3b-coding");
    assert_eq!(plain["refusals_removed"], false);
    let open = named("huihui_ai/gpt-oss-abliterated:20b")
        .expect("the abliterated model didn't reach the phone");
    assert_eq!(
        open["refusals_removed"], true,
        "the abliterated model isn't marked: {v}"
    );
    // The models that run on the phone itself come along too, for use with no PC.
    let phone = &v["standalone"];
    assert!(
        phone["models"].as_array().is_some_and(|m| !m.is_empty()),
        "the phone model list is empty: {phone}"
    );
}

#[test]
fn phone_turns_the_weekly_model_check_off_and_on() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).expect("the paired phone couldn't connect");
    let mut seen = Seen::default();
    hello(&p, &mut seen);
    let weekly = |seen: &mut Seen| {
        let id = p.call(Call::Models).unwrap();
        seen.reply(&p, id).unwrap()["weekly_check"].clone()
    };
    assert_eq!(weekly(&mut seen), true, "the weekly check starts on");
    let id = p.call(Call::SetWeeklyCheck { on: false }).unwrap();
    seen.reply(&p, id).expect("the phone may not turn it off");
    assert_eq!(weekly(&mut seen), false);
    let saved: serde_json::Value =
        serde_json::from_slice(&std::fs::read(home.settings_path()).unwrap()).unwrap();
    assert_eq!(saved["weekly_model_check"], false, "not saved: {saved}");
    let id = p.call(Call::SetWeeklyCheck { on: true }).unwrap();
    seen.reply(&p, id).unwrap();
    assert_eq!(weekly(&mut seen), true);
}

#[test]
fn phone_names_the_pc_and_turns_relays_off_and_on() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).expect("the paired phone couldn't connect");
    let mut seen = Seen::default();
    hello(&p, &mut seen);
    let read = |seen: &mut Seen| {
        let id = p.call(Call::PhoneSettings).unwrap();
        seen.reply(&p, id).unwrap()
    };
    // The checks' homes start with relays off (direct connections only).
    assert_eq!(read(&mut seen)["relays"], false);
    let id = p.call(Call::SetRelays { on: true }).unwrap();
    seen.reply(&p, id)
        .expect("the phone may not turn relays on");
    assert_eq!(read(&mut seen)["relays"], true);
    let id = p
        .call(Call::SetPcName {
            name: "  Desk PC ".into(),
        })
        .unwrap();
    assert_eq!(seen.reply(&p, id).unwrap(), "Desk PC");
    let id = p.call(Call::SetRelays { on: false }).unwrap();
    seen.reply(&p, id)
        .expect("the phone may not turn relays off");
    let now = read(&mut seen);
    assert_eq!(now["pc_name"], "Desk PC");
    assert_eq!(now["relays"], false);
    let saved: serde_json::Value =
        serde_json::from_slice(&std::fs::read(home.settings_path()).unwrap()).unwrap();
    assert_eq!(saved["pc_name"], "Desk PC", "not saved: {saved}");
    assert_eq!(saved["phone_relays"], false, "not saved: {saved}");
    let id = p
        .call(Call::SetPcName {
            name: "x".repeat(41),
        })
        .unwrap();
    assert!(seen.reply(&p, id).is_err(), "a 41-letter name was taken");
}

#[test]
fn a_phone_request_brings_its_own_ground_rules() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.play("run-2.jsonl", "live.jsonl");
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).expect("the paired phone couldn't connect");
    let mut seen = Seen::default();
    hello(&p, &mut seen);
    let project = home.project.display().to_string();
    let rules_of_run = |n: usize| -> Option<String> {
        let runs = home.conversations();
        let run = runs.get(n)?;
        let i = run.iter().position(|a| a == "--append-system-prompt")?;
        run.get(i + 1).cloned()
    };
    let wait_runs = |n: usize| {
        let until = Instant::now() + Duration::from_secs(10);
        while home.conversations().len() < n && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(50));
        }
    };
    // The phone's own rules are used for its task.
    for (rules, n) in [(Some("Phone rules: be short."), 1), (None, 2)] {
        let id = p
            .call(Call::NewTask {
                typed: false,
                files: vec![],
                ground_rules: rules.map(str::to_string),
                project: project.clone(),
                text: "Tidy up notes.txt.".into(),
                pictures: vec![],
                mode: Mode::Ask,
                model: None,
                effort: None,
                separate_copy: false,
                msg_id: None,
            })
            .unwrap();
        seen.reply(&p, id).expect("the task didn't start");
        wait_runs(n);
        let want = rules.unwrap_or(mimir_core::settings::DEFAULT_GROUND_RULES);
        // Without them, the PC's (the default) are used.
        assert_eq!(rules_of_run(n - 1).as_deref(), Some(want));
    }
}

/// A phone's search and lookups through the link are answered (off the
/// service's own thread), never "refused: not here": that refusal made
/// every search and lookup through the PC fail on the real phone. The PC
/// fetches only its fixed sites, never another address.
#[test]
fn phone_searches_and_lookups_through_the_pc_are_answered() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, pc) = paired(&mut st);
    p.connect(&pc).expect("the paired phone couldn't connect");
    let mut seen = Seen::default();
    hello(&p, &mut seen);
    let id = p
        .call(Call::Lookup {
            url: "https://example.com/v1/forecast".into(),
        })
        .unwrap();
    let why = seen.reply(&p, id).unwrap_err();
    assert!(why.contains("only looks up"), "{why}");
    let id = p
        .call(Call::WebSearch {
            query: "weather Portland".into(),
            max: 3,
        })
        .unwrap();
    // The PC's SearXNG may or may not be running here: either way, it was asked.
    if let Err(why) = seen.reply(&p, id) {
        assert!(!why.contains("not here"), "{why}");
    }
}
