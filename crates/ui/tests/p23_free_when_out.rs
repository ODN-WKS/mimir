//! P23: "When online use runs out, use your PC's model". One switch on the
//! PC, on by default, set from the PC window or a phone, shown the same
//! everywhere; while it is on and online use is out, project tasks (new and
//! running) and online chats go to the PC's model and say so. Off: they
//! don't.

mod common;
use common::*;
use egui_kittest::kittest::{NodeT, Queryable};
use mimir_core::proto::{Call, ChatTurn, Event, Filed};
use mimir_core::task::{Entry, Mode, Status};
use mimir_link::client::{Client, generate_key};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::time::{Duration, Instant};

/// The model project work moves to.
const FREE: &str = "qwen3.6:35b-a3b-coding";
/// When the stand-in online limit is back (far away, so it stays out for
/// the whole check).
const BACK_AT: i64 = 4_102_444_800;

fn home(extra: serde_json::Value) -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.display().to_string();
    let mut v = serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
    });
    for (k, x) in extra.as_object().unwrap() {
        v[k] = x.clone();
    }
    home.write_settings(v);
    picked(&home);
    Some(home)
}

/// A stand-in Ollama: lists one model and answers every chat with
/// "PC answer."
fn fake_ollama() {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    set_env(
        "MIMIR_OLLAMA",
        format!("http://{}", l.local_addr().unwrap()),
    );
    std::thread::spawn(move || {
        for c in l.incoming().flatten() {
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
                let mut w = c;
                let one = |w: &mut std::net::TcpStream, body: String| {
                    let _ = write!(
                        w,
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    );
                };
                match path.as_str() {
                    "/api/version" => one(&mut w, r#"{"version":"0.40.16"}"#.into()),
                    "/api/ps" => one(&mut w, r#"{"models":[]}"#.into()),
                    "/api/tags" => one(&mut w, format!("{{\"models\":[{{\"name\":\"{FREE}\"}}]}}")),
                    _ => {
                        let _ = w.write_all(
                            b"HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nTransfer-Encoding: chunked\r\n\r\n",
                        );
                        for line in [
                            r#"{"message":{"content":"PC answer."},"done":false}"#,
                            r#"{"message":{"content":""},"done":true,"prompt_eval_count":50}"#,
                        ] {
                            let data = format!("{line}\n");
                            let _ =
                                w.write_all(format!("{:x}\r\n{data}\r\n", data.len()).as_bytes());
                            let _ = w.flush();
                        }
                        let _ = w.write_all(b"0\r\n\r\n");
                    }
                }
            });
        }
    });
}

/// The PC's chat model, as if the probe had run.
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

/// Plays the recording where online use runs out, with a far-away reset.
fn play_limit(home: &TestHome, as_name: &str) {
    let text = std::fs::read_to_string(recording("limit.jsonl")).unwrap();
    std::fs::write(
        home.streams.join(as_name),
        text.replace("@SOON@", &BACK_AT.to_string()),
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

/// A fake phone of `person` (None: the owner's), paired and past its snapshot.
fn paired(st: &mut mimir_core::client::Station, person: Option<&str>) -> (Client, Seen) {
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
        person: Some(person.unwrap_or(mimir_core::proto::WHOSE_MINE).into()),
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
        features: ["talk", "chat_sync", "models_changed", "people"]
            .iter()
            .map(|f| f.to_string())
            .collect(),
    })
    .unwrap();
    assert!(seen.until(&p, |s| s.ready), "no snapshot after Hello");
    (p, seen)
}

/// What a fake phone knows.
#[derive(Default)]
struct Seen {
    replies: HashMap<u64, Result<serde_json::Value, String>>,
    done: HashMap<String, Result<ChatTurn, String>>,
    models_changed: usize,
    ready: bool,
}

impl Seen {
    fn take(&mut self, p: &Client) {
        while let Some(m) = p.next_event(Duration::from_millis(20)) {
            match m.event {
                Event::Ready => self.ready = true,
                Event::Reply { call_id, result } => {
                    self.replies.insert(call_id, result);
                }
                Event::TalkDone {
                    turn_id, result, ..
                } => {
                    self.done.insert(turn_id, result);
                }
                Event::ModelsChanged => self.models_changed += 1,
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

    fn call(&mut self, p: &Client, call: Call) -> Result<serde_json::Value, String> {
        let id = p.call(call).unwrap();
        assert!(self.until(p, |s| s.replies.contains_key(&id)), "no reply");
        self.replies.remove(&id).unwrap()
    }

    /// What this phone's Status switch shows (the PC's `Models` reply).
    fn switch(&mut self, p: &Client) -> bool {
        self.call(p, Call::Models).unwrap()["switch_to_free"]
            .as_bool()
            .expect("no switch_to_free in the Models reply")
    }
}

fn set_switch(on: bool) -> Call {
    Call::SetFreeChoice {
        switch_to_free: Some(on),
        free_model: None,
    }
}

fn saved(home: &TestHome) -> serde_json::Value {
    serde_json::from_str(&std::fs::read_to_string(home.settings_path()).unwrap()).unwrap()
}

fn chat(id: &str, text: &str) -> Call {
    Call::Talk {
        chat_id: "c1".into(),
        turn_id: id.into(),
        turns: vec![ChatTurn {
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
        }],
        lookup: None,
        sources: vec![],
        effort: None,
        ground_rules: None,
        task: None,
        chat_only: false,
    }
}

fn task<'a>(
    st: &'a mimir_core::client::Station,
    id: &str,
) -> Option<&'a mimir_core::client::TaskView> {
    st.tasks.iter().find(|v| v.task.id == id)
}

fn notes(st: &mimir_core::client::Station, id: &str) -> Vec<String> {
    task(st, id)
        .map(|v| {
            v.task
                .entries
                .iter()
                .filter_map(|e| match e {
                    Entry::Note(n) => Some(n.clone()),
                    _ => None,
                })
                .collect()
        })
        .unwrap_or_default()
}

fn new_task(st: &mut mimir_core::client::Station, home: &TestHome, text: &str) -> String {
    st.new_task(
        &home.project.display().to_string(),
        text,
        &[],
        Mode::Ask,
        None,
        None,
        false,
    )
    .expect("the task didn't start")
}

/// The switch is on by default; one phone turns it off and on (with the
/// field older phones send), the PC keeps it, and a second phone and the PC
/// window show the same at once. The window's own change reaches the phones
/// too. Someone else's phone can't change it.
#[test]
fn p23_the_switch_is_on_by_default_and_every_screen_shows_the_pcs_state() {
    let Some(home) = home(serde_json::json!({"project_engine": "claude"})) else {
        return;
    };
    fake_ollama();
    let mut st = home.service();
    let (a, mut a_seen) = paired(&mut st, None);
    let (b, mut b_seen) = paired(&mut st, None);
    assert!(a_seen.switch(&a), "the switch isn't on by default");
    assert!(b_seen.switch(&b));

    // Phone A turns it off: the PC keeps it, phone B is told and shows off,
    // and the PC window has it in its settings.
    a_seen.call(&a, set_switch(false)).unwrap();
    assert_eq!(saved(&home)["free_when_out"], false);
    assert!(
        b_seen.until(&b, |s| s.models_changed > 0),
        "phone B wasn't told"
    );
    assert!(!b_seen.switch(&b), "phone B still shows on");
    assert!(service_until(&mut st, |s| s.settings["free_when_out"] == false));

    // The old field from a phone that hasn't updated: true means on.
    let before = b_seen.models_changed;
    a_seen.call(&a, set_switch(true)).unwrap();
    assert_eq!(saved(&home)["free_when_out"], true);
    assert!(b_seen.until(&b, |s| s.models_changed > before));
    assert!(b_seen.switch(&b), "phone B still shows off");
    assert!(a_seen.switch(&a), "phone A's own change snapped back");

    // The PC window turns it off: both phones are told and show off.
    assert!(service_until(&mut st, |s| s.settings["free_when_out"] == true));
    let before = b_seen.models_changed;
    let mut part = st.settings.clone();
    part["free_when_out"] = false.into();
    st.set_settings(part);
    assert!(
        b_seen.until(&b, |s| s.models_changed > before),
        "the window's change didn't reach the phone"
    );
    assert!(!b_seen.switch(&b));
    assert!(!a_seen.switch(&a));
    assert_eq!(saved(&home)["free_when_out"], false);

    // Someone else's phone can't change the owner's switch.
    let (c, mut c_seen) = paired(&mut st, Some("Ana"));
    assert!(c_seen.call(&c, set_switch(true)).is_err());
    assert!(!a_seen.switch(&a));
}

/// On (the default) and online use runs out: the running task carries on
/// on the PC's model, a new task starts there, and an online chat is
/// answered by the PC's model without trying online; each says so.
#[test]
fn p23_switch_on_a_running_task_a_new_task_and_a_chat_go_to_the_pcs_model() {
    let Some(home) = home(serde_json::json!({
        "project_engine": "claude",
        "chats_engine": "paid",
    })) else {
        return;
    };
    fake_ollama();
    play_limit(&home, "run-1.jsonl");
    home.play("run-2.jsonl", "near-limit.jsonl");
    home.play("run-3.jsonl", "live.jsonl");
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, None);

    // A running task hits the limit and carries on on the PC's model.
    let first = new_task(&mut st, &home, "Tidy up notes.txt.");
    assert!(
        service_until(&mut st, |s| notes(s, &first).iter().any(
            |n| n.starts_with("The online limit ran out, so this carries on on")
        )),
        "the running task didn't carry on: {:?}",
        notes(&st, &first)
    );
    assert!(service_until(&mut st, |s| home.conversations().len() == 2
        && task(s, &first).is_some_and(|v| !v.busy())));
    assert!(
        home.conversations()[1].iter().any(|a| a.contains(FREE)),
        "the carry-on wasn't on the PC's model: {:?}",
        home.conversations()[1]
    );
    let out = task(&st, &first).unwrap().task.ran_out.clone().unwrap();
    assert!(out.on_free);
    assert_eq!(out.back_at, BACK_AT);

    // A new task while online is out starts on the PC's model and says why.
    let second = new_task(&mut st, &home, "What does this project do?");
    assert!(service_until(&mut st, |s| task(s, &second).is_some_and(|v| !v.busy())));
    assert_eq!(
        task(&st, &second).unwrap().task.model.as_deref(),
        Some(format!("ollama:{FREE}").as_str())
    );
    assert!(
        notes(&st, &second)
            .iter()
            .any(|n| n.starts_with("On ") && n.contains("because the online limit ran out")),
        "the new task didn't say why: {:?}",
        notes(&st, &second)
    );
    assert_eq!(home.conversations().len(), 3);

    // An online chat: the PC's model answers at once, and says why.
    let f: Filed = serde_json::from_value(seen.call(&p, chat("t1", "Hello")).unwrap()).unwrap();
    let Filed::Talk {
        ran, model_words, ..
    } = &f
    else {
        panic!("not answered on the PC: {f:?}")
    };
    assert_eq!(*ran, mimir_core::proto::Ran::Pc, "{f:?}");
    assert!(
        model_words.starts_with("Your PC · Online is at its limit until"),
        "{model_words}"
    );
    assert!(seen.until(&p, |s| s.done.contains_key("t1.a")));
    let turn = seen.done["t1.a"].clone().unwrap();
    assert_eq!(turn.text, "PC answer.");
    assert_eq!(turn.ran, Some(mimir_core::proto::Ran::Pc));
    assert_eq!(
        home.conversations().len(),
        3,
        "the chat tried online while it was out"
    );
}

/// Off and online use runs out: the running task stops and asks, a new
/// task stays online, and an online chat says online is at its limit; none
/// of them goes to the PC's model.
#[test]
fn p23_switch_off_nothing_moves_to_the_pcs_model() {
    let Some(home) = home(serde_json::json!({
        "project_engine": "claude",
        "chats_engine": "paid",
        "free_when_out": false,
    })) else {
        return;
    };
    fake_ollama();
    play_limit(&home, "run-1.jsonl");
    home.play("run-2.jsonl", "live.jsonl");
    play_limit(&home, "run-3.jsonl");
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, None);

    // The running task stops at the limit and offers to carry on.
    let first = new_task(&mut st, &home, "Tidy up notes.txt.");
    assert!(
        service_until(&mut st, |s| task(s, &first).is_some_and(|v| v
            .task
            .ran_out
            .is_some()
            && !v.busy())),
        "the task didn't stop and ask: {:?}",
        notes(&st, &first)
    );
    let out = task(&st, &first).unwrap().task.ran_out.clone().unwrap();
    assert!(!out.on_free);
    assert_eq!(task(&st, &first).unwrap().task.status, Status::Stopped);
    assert!(
        notes(&st, &first)
            .iter()
            .any(|n| n.starts_with("The online limit ran out in")),
        "{:?}",
        notes(&st, &first)
    );
    std::thread::sleep(Duration::from_millis(500));
    assert_eq!(home.conversations().len(), 1, "it carried on by itself");

    // A new task stays online.
    let second = new_task(&mut st, &home, "What does this project do?");
    assert!(service_until(&mut st, |s| task(s, &second).is_some_and(|v| !v.busy())));
    let model = task(&st, &second).unwrap().task.model.clone();
    assert!(
        !mimir_core::models::is_free(model.as_deref()),
        "the new task moved to the PC's model: {model:?}"
    );
    assert!(
        !home.conversations()[1].iter().any(|a| a.contains(FREE)),
        "{:?}",
        home.conversations()[1]
    );

    // An online chat tries online and says it is at its limit.
    let f: Filed = serde_json::from_value(seen.call(&p, chat("t1", "Hello")).unwrap()).unwrap();
    assert!(
        matches!(
            &f,
            Filed::Talk {
                ran: mimir_core::proto::Ran::Paid,
                ..
            }
        ),
        "{f:?}"
    );
    assert!(seen.until(&p, |s| s.done.contains_key("t1.a")));
    let why = seen.done["t1.a"].clone().unwrap_err();
    assert!(why.starts_with("Online is at its limit until"), "{why}");
    assert!(
        why.contains("When online use runs out, use your PC's model"),
        "{why}"
    );
}

/// The switch in the PC window, as `h` shows it: (found, on, can be changed).
fn window_switch(h: &egui_kittest::Harness<'static, station_ui::App>) -> (bool, bool, bool) {
    match h.query_by_label(station_ui::labels::FREE_WHEN_OUT) {
        Some(n) => {
            let a = n.accesskit_node();
            (
                true,
                a.toggled() == Some(eframe::egui::accesskit::Toggled::True),
                !a.is_disabled(),
            )
        }
        None => (false, false, false),
    }
}

/// Opens Settings › Models from the week's use, as its hover says.
fn open_who_answers(h: &mut egui_kittest::Harness<'static, station_ui::App>) {
    start_task(h, "Tidy up notes.txt.");
    let words = station_ui::labels::weekly_use(86.0);
    assert!(
        wait_for(h, |h| shows(h, &words)),
        "the week's use isn't shown\n{}",
        screen_text(h)
    );
    h.query_by_label(&words).unwrap().click();
    assert!(
        wait_for(h, |h| shows(h, "Who answers") && shows(h, "Project work:")),
        "Settings › Models didn't open\n{}",
        screen_text(h)
    );
}

/// The PC window: the switch sits under Who answers, on and changeable
/// when the PC has a model; turning it off is kept by the PC.
#[test]
fn p23_the_window_has_the_switch_under_who_answers() {
    let Some(home) = home(serde_json::json!({"project_engine": "claude"})) else {
        return;
    };
    fake_ollama();
    home.play("run-1.jsonl", "near-limit.jsonl");
    let mut h = home.open();
    open_who_answers(&mut h);
    assert!(
        wait_for(&mut h, |h| window_switch(h) == (true, true, true)),
        "the switch isn't there, on and changeable: {:?}\n{}",
        window_switch(&h),
        screen_text(&h)
    );
    assert!(shows(&h, station_ui::labels::FREE_WHEN_OUT_ABOUT));
    h.query_by_label(station_ui::labels::FREE_WHEN_OUT)
        .unwrap()
        .click();
    assert!(
        wait_for(&mut h, |_| saved(&home)["free_when_out"] == false),
        "turning it off wasn't kept"
    );
    assert!(wait_for(&mut h, |h| window_switch(h) == (true, false, true)));
    close(h);
}

/// No model on the PC: the switch shows off, can't be changed, and says why
/// and how to get one.
#[test]
fn p23_without_a_model_the_window_switch_is_off_and_says_why() {
    let Some(home) = home(serde_json::json!({"project_engine": "claude"})) else {
        return;
    };
    set_env("MIMIR_OLLAMA", "http://127.0.0.1:1");
    home.play("run-1.jsonl", "near-limit.jsonl");
    let mut h = home.open();
    open_who_answers(&mut h);
    assert!(
        wait_for(&mut h, |h| window_switch(h) == (true, false, false)),
        "{:?}\n{}",
        window_switch(&h),
        screen_text(&h)
    );
    assert!(shows(&h, station_ui::labels::FREE_WHEN_OUT_NO_MODEL));
    close(h);
}

/// The PC's model program is asleep: its unit is there, nothing answers,
/// and asking systemd to start it runs `starter` (a shell line) instead.
/// `installed` puts one downloaded model's files on disk. The port it would
/// answer on.
fn asleep(home: &TestHome, installed: bool, starter: &str) -> u16 {
    let unit = home.root.join(".config/systemd/user/mimir-ollama.service");
    std::fs::create_dir_all(unit.parent().unwrap()).unwrap();
    std::fs::write(&unit, "[Service]\nExecStart=/bin/true\n").unwrap();
    let port = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    set_env("MIMIR_OLLAMA", format!("http://127.0.0.1:{port}"));
    let models = home.root.join("ollama-models");
    set_env("OLLAMA_MODELS", &models);
    if installed {
        let (name, tag) = FREE.split_once(':').unwrap();
        let dir = models
            .join("manifests/registry.ollama.ai/library")
            .join(name);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(tag), "{}").unwrap();
    }
    let script = home.root.join("starter.sh");
    std::fs::write(&script, format!("#!/bin/sh\n{starter}\n")).unwrap();
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    set_env("MIMIR_OLLAMA_START", &script);
    port
}

/// Asleep but installed: the phone still gets the model, so its switch shows
/// the PC's setting and can be changed.
#[test]
fn p23_asleep_the_phone_still_gets_the_installed_model() {
    let Some(home) = home(serde_json::json!({"project_engine": "claude"})) else {
        return;
    };
    asleep(&home, true, "true");
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, None);
    let v = seen.call(&p, Call::Models).unwrap();
    let id = format!("ollama:{FREE}");
    assert_eq!(v["free"][0]["id"], id.as_str(), "{v}");
    assert_eq!(v["free"].as_array().unwrap().len(), 1, "{v}");
    assert_eq!(v["free_model"], id.as_str(), "{v}");
    assert_eq!(v["switch_to_free"], true, "{v}");
    seen.call(&p, set_switch(false)).unwrap();
    assert!(!seen.switch(&p));
    assert_eq!(saved(&home)["free_when_out"], false);
}

/// Asleep and nothing ever installed: no model is claimed.
#[test]
fn p23_asleep_with_nothing_installed_there_is_no_model() {
    let Some(home) = home(serde_json::json!({"project_engine": "claude"})) else {
        return;
    };
    asleep(&home, false, "true");
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, None);
    let v = seen.call(&p, Call::Models).unwrap();
    assert!(v["free"].as_array().unwrap().is_empty(), "{v}");
    assert!(v["free_model"].is_null(), "{v}");
}

/// Asking which models there are wakes the program; once it answers, the
/// phone is told to look again.
#[test]
fn p23_the_phone_is_told_once_the_woken_program_answers() {
    let Some(home) = home(serde_json::json!({"project_engine": "claude"})) else {
        return;
    };
    let started = home.root.join("ollama-started");
    let port = asleep(&home, false, &format!("echo x >> '{}'", started.display()));
    let flag = started.clone();
    std::thread::spawn(move || {
        while !flag.exists() {
            std::thread::sleep(Duration::from_millis(20));
        }
        // A slow start, as a real one is.
        std::thread::sleep(Duration::from_secs(1));
        let l = TcpListener::bind(("127.0.0.1", port)).unwrap();
        for mut s in l.incoming().flatten() {
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            let body = format!("{{\"models\":[{{\"name\":\"{FREE}\"}}]}}");
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        }
    });
    let mut st = home.service();
    let (p, mut seen) = paired(&mut st, None);
    let before = seen.models_changed;
    let v = seen.call(&p, Call::Models).unwrap();
    assert!(v["free"].as_array().unwrap().is_empty(), "{v}");
    assert!(
        seen.until(&p, |s| s.models_changed > before),
        "the phone wasn't told when the program came up"
    );
    let v = seen.call(&p, Call::Models).unwrap();
    assert!(!v["free"].as_array().unwrap().is_empty(), "{v}");
}

/// Asleep but installed: the PC window's switch is on and changeable.
#[test]
fn p23_asleep_the_window_switch_stays_on_and_changeable() {
    let Some(home) = home(serde_json::json!({"project_engine": "claude"})) else {
        return;
    };
    asleep(&home, true, "true");
    home.play("run-1.jsonl", "near-limit.jsonl");
    let mut h = home.open();
    open_who_answers(&mut h);
    assert!(
        wait_for(&mut h, |h| window_switch(h) == (true, true, true)),
        "{:?}\n{}",
        window_switch(&h),
        screen_text(&h)
    );
    // Still so after the window has looked and found the program off.
    assert!(wait_for(&mut h, |_| mimir_core::models::program_off()));
    h.run_steps(3);
    assert_eq!(window_switch(&h), (true, true, true), "{}", screen_text(&h));
    assert!(!shows(&h, station_ui::labels::FREE_WHEN_OUT_NO_MODEL));
    close(h);
}

/// The phone's Status says the same words as the PC window.
#[test]
fn p23_the_phone_and_the_pc_use_the_same_words() {
    let kt = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../android/app/src/main/java/io/github/odnwks/mimir/ui/StatusScreen.kt"
    ))
    .unwrap();
    for (name, words) in [
        ("TITLE", station_ui::labels::FREE_WHEN_OUT),
        ("ABOUT", station_ui::labels::FREE_WHEN_OUT_ABOUT),
    ] {
        assert!(
            kt.contains(&format!("const val {name} = \"{words}\"")),
            "the phone's {name} isn't \"{words}\""
        );
    } // One threshold: the words name the PC's own.
    assert!(
        station_ui::labels::FREE_WHEN_OUT_ABOUT.starts_with(&format!(
            "When online use passes {}% this week, or runs out, your PC's model takes over",
            mimir_core::usage::FREE_AT_PERCENT
        ))
    );
}

/// Plays a run that reports this week's online use at `used` (0 to 1),
/// with the week resetting far away.
fn play_week(home: &TestHome, as_name: &str, used: f64) {
    let text = std::fs::read_to_string(recording("near-limit.jsonl")).unwrap();
    assert!(text.contains("\\\"utilization\\\":0.86"));
    std::fs::write(
        home.streams.join(as_name),
        text.replace(
            "\\\"utilization\\\":0.86",
            &format!("\\\"utilization\\\":{used}"),
        )
        .replace("1795154400", &BACK_AT.to_string()),
    )
    .unwrap();
}

/// Owner, 2026-11-26: "can we make the 85% actually 99%". With the switch
/// on, at 98% this week a new task and an online chat stay online; at 99%
/// and above (online not yet out) they go to the PC's model and say why.
/// With the switch off, neither moves at 99% or 100%.
/// (One check per case: each needs a home of its own.)
fn week_case(used: f64, on: bool, moves: bool) {
    {
        let case = format!("{used} with the switch {}", if on { "on" } else { "off" });
        let Some(home) = home(serde_json::json!({
            "project_engine": "claude",
            "chats_engine": "paid",
            "free_when_out": on,
        })) else {
            return;
        };
        fake_ollama();
        play_week(&home, "run-1.jsonl", used);
        home.play("run-2.jsonl", "live.jsonl");
        home.play("run-3.jsonl", "live.jsonl");
        let mut st = home.service();
        let (p, mut seen) = paired(&mut st, None);

        // An online task reads this week's use.
        let first = new_task(&mut st, &home, "Tidy up notes.txt.");
        assert!(
            service_until(&mut st, |s| task(s, &first).is_some_and(|v| !v.busy())),
            "{case}"
        );
        assert!(
            !mimir_core::models::is_free(task(&st, &first).unwrap().task.model.as_deref()),
            "{case}: the first task wasn't online"
        );

        // A new task.
        let second = new_task(&mut st, &home, "What does this project do?");
        assert!(
            service_until(&mut st, |s| task(s, &second).is_some_and(|v| !v.busy())),
            "{case}"
        );
        let model = task(&st, &second).unwrap().task.model.clone();
        let said = notes(&st, &second).iter().any(|n| {
            n.starts_with("On ")
                && n.contains("because online use passed 99% this week; online is back at")
        });
        if moves {
            assert_eq!(
                model.as_deref(),
                Some(format!("ollama:{FREE}").as_str()),
                "{case}"
            );
            assert!(said, "{case}: it didn't say why: {:?}", notes(&st, &second));
            let out = task(&st, &second).unwrap().task.ran_out.clone().unwrap();
            assert!(out.on_free, "{case}");
            assert_eq!(out.back_at, BACK_AT, "{case}");
        } else {
            assert!(
                !mimir_core::models::is_free(model.as_deref()),
                "{case}: the new task moved to the PC's model: {model:?}"
            );
            assert!(!said, "{case}");
        }

        // An online chat.
        let f: Filed = serde_json::from_value(seen.call(&p, chat("t1", "Hello")).unwrap()).unwrap();
        let Filed::Talk {
            ran, model_words, ..
        } = &f
        else {
            panic!("{case}: not answered: {f:?}")
        };
        if moves {
            assert_eq!(*ran, mimir_core::proto::Ran::Pc, "{case}: {f:?}");
            assert_eq!(
                model_words, "Your PC · Online use passed 99% this week · Your PC answered instead",
                "{case}"
            );
        } else {
            assert_eq!(*ran, mimir_core::proto::Ran::Paid, "{case}: {f:?}");
        }
        assert!(seen.until(&p, |s| s.done.contains_key("t1.a")), "{case}");
        if moves {
            let turn = seen.done["t1.a"].clone().unwrap();
            assert_eq!(turn.text, "PC answer.", "{case}");
            assert_eq!(turn.ran, Some(mimir_core::proto::Ran::Pc), "{case}");
            // Only the two tasks went to Claude Code; the chat never tried online.
            assert_eq!(home.conversations().len(), 2, "{case}");
        }
    }
}

#[test]
fn p23_at_98_percent_with_the_switch_on_work_stays_online() {
    week_case(0.98, true, false);
}

#[test]
fn p23_at_99_percent_with_the_switch_on_work_moves_to_the_pcs_model() {
    week_case(0.99, true, true);
}

#[test]
fn p23_at_100_percent_with_the_switch_on_work_moves_to_the_pcs_model() {
    week_case(1.0, true, true);
}

#[test]
fn p23_at_99_percent_with_the_switch_off_work_stays_online() {
    week_case(0.99, false, false);
}

#[test]
fn p23_at_100_percent_with_the_switch_off_work_stays_online() {
    week_case(1.0, false, false);
}
