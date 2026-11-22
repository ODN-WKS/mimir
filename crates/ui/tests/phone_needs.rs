//! The phone app's Phases 4 to 6 against the real service, with fake
//! phones on this PC (relays off, loopback):
//!
//! *   needs you: one list for every phone, the first answer wins, the
//!     phone's "always allow this file (this task)" rule, asks from any
//!     Claude session, wake-up pushes to a fake Firebase server, and a
//!     message sent again being done once;
//! *   the general chat: ideas, the Inbox, requests, and Move;
//! *   status from facts, and a core check that starts failing.

mod common;
use common::*;
use mimir_core::proto::{
    Call, Event, Filed, ItemAnswer, Mode, NeedsKind, NeedsYouItem, StatusPage,
};
use mimir_core::sockets::{SessionMsg, read_msg, write_msg};
use mimir_link::client::{Client, generate_key};
use std::collections::HashMap;
use std::io::Write;
use std::time::{Duration, Instant};

/// A home whose service keeps the link off n0's relays, with a second
/// project and an alias for the first.
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

fn other_project(home: &TestHome) -> String {
    home.root.join("Recipe-Box").display().to_string()
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

/// Tells a fake phone where this service's link listens now.
fn rehint(st: &mut mimir_core::client::Station, p: &Client) {
    let info = st
        .call_wait(Call::LinkInfo)
        .expect("the link isn't running");
    for a in info["bound"].as_array().unwrap() {
        let a: std::net::SocketAddr = a.as_str().unwrap().parse().unwrap();
        let ip: std::net::IpAddr = if a.is_ipv4() {
            [127, 0, 0, 1].into()
        } else {
            std::net::Ipv6Addr::LOCALHOST.into()
        };
        p.add_hint(std::net::SocketAddr::new(ip, a.port()));
    }
}

/// A fake phone, paired (the check says yes in the service's own client),
/// connected and past its snapshot.
fn paired(st: &mut mimir_core::client::Station, token: Option<&str>) -> (Client, Seen, String) {
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
    hello(&p, &mut seen, token);
    (p, seen, pc)
}

fn hello(p: &Client, seen: &mut Seen, _token: Option<&str>) {
    seen.ready = false;
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

/// What a fake phone knows.
#[derive(Default)]
struct Seen {
    needs: Vec<NeedsYouItem>,
    replies: HashMap<u64, Result<serde_json::Value, String>>,
    firsts: HashMap<String, String>,
    ready: bool,
}

impl Seen {
    fn take(&mut self, p: &Client) {
        while let Some(m) = p.next_event(Duration::from_millis(20)) {
            match m.event {
                Event::Ready => self.ready = true,
                Event::NeedsYou { items } => self.needs = items,
                Event::Entries {
                    task,
                    from,
                    entries,
                } => {
                    if from == 0
                        && let Some(mimir_core::proto::Entry::You(t)) = entries.first()
                    {
                        self.firsts.insert(task, t.clone());
                    }
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

    fn call(&mut self, p: &Client, call: Call) -> Result<serde_json::Value, String> {
        let id = p.call(call).unwrap();
        assert!(self.until(p, |s| s.replies.contains_key(&id)), "no reply");
        self.replies.remove(&id).unwrap()
    }

    fn item(&self, kind: NeedsKind) -> Option<NeedsYouItem> {
        self.needs.iter().find(|i| i.kind == kind).cloned()
    }
}

fn new_task(
    p: &Client,
    seen: &mut Seen,
    project: &str,
    text: &str,
    msg_id: Option<&str>,
) -> String {
    let v = seen
        .call(
            p,
            Call::NewTask {
                files: vec![],
                ground_rules: None,
                project: project.into(),
                text: text.into(),
                pictures: vec![],
                mode: Mode::Ask,
                model: None,
                effort: None,
                separate_copy: false,
                msg_id: msg_id.map(str::to_string),
            },
        )
        .expect("the task didn't start");
    v.as_str().unwrap().to_string()
}

fn wait_hooks(home: &TestHome, n: usize) -> Vec<String> {
    let until = Instant::now() + Duration::from_secs(20);
    while home.hook_answers().len() < n && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(25));
    }
    home.hook_answers()
}

fn filed(v: serde_json::Value) -> Filed {
    serde_json::from_value(v).expect("not a Filed")
}

// ---- a fake Firebase server ----

#[allow(dead_code)]
fn until(mut f: impl FnMut() -> bool) -> bool {
    let end = Instant::now() + Duration::from_secs(20);
    while Instant::now() < end {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    false
}

// ---- Phase 4: needs you ----

#[test]
fn p4_answering_on_one_phone_clears_the_item_on_the_other() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "allow.jsonl");
    let mut st = home.service();
    let (a, mut sa, _) = paired(&mut st, None);
    let (b, mut sb, _) = paired(&mut st, None);
    let project = home.project.display().to_string();
    let task = new_task(
        &a,
        &mut sa,
        &project,
        "Run this Bash command: touch a.txt",
        None,
    );
    assert!(
        sa.until(&a, |s| s.item(NeedsKind::Permission).is_some())
            && sb.until(&b, |s| s.item(NeedsKind::Permission).is_some()),
        "the permission request didn't reach both phones"
    );
    let item = sb.item(NeedsKind::Permission).unwrap();
    assert_eq!(item.task.as_deref(), Some(task.as_str()));
    assert!(item.serious);
    sa.call(
        &a,
        Call::AnswerItem {
            ask_id: item.ask_id.clone(),
            answer: ItemAnswer::Allow,
        },
    )
    .expect("the answer was refused");
    assert!(
        sb.until(&b, |s| s.item(NeedsKind::Permission).is_none()),
        "the other phone still shows the answered item"
    );
    // The second answer is too late.
    let late = sb.call(
        &b,
        Call::AnswerItem {
            ask_id: item.ask_id,
            answer: ItemAnswer::DontAllow,
        },
    );
    assert!(late.is_err(), "a second answer was taken");
    assert_eq!(told(wait_hooks(&home, 1).first()), "allow");
}

#[test]
fn p4_always_allow_this_file_holds_on_the_next_turn() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "edit_ask.jsonl");
    home.play("resume.jsonl", "edit_ask.jsonl");
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    let project = home.project.display().to_string();
    let task = new_task(
        &p,
        &mut seen,
        &project,
        "Change old line to new line in notes.txt.",
        None,
    );
    assert!(seen.until(&p, |s| s.item(NeedsKind::Permission).is_some()));
    let item = seen.item(NeedsKind::Permission).unwrap();
    assert!(
        item.preview.iter().any(|l| l == "+new line"),
        "no change preview: {:?}",
        item.preview
    );
    seen.call(
        &p,
        Call::AnswerItem {
            ask_id: item.ask_id,
            answer: ItemAnswer::AllowFileThisTask,
        },
    )
    .expect("the answer was refused");
    assert_eq!(told(wait_hooks(&home, 1).first()), "allow");
    // Wait for the turn to end, then the next turn asks for the same file.
    assert!(service_until(&mut st, |s| s.tasks.iter().any(|v| v
        .task
        .id
        == task
        && !v.busy())));
    seen.call(
        &p,
        Call::Send {
            files: vec![],
            task: task.clone(),
            text: "And again.".into(),
            pictures: vec![],
            msg_id: None,
        },
    )
    .unwrap();
    let answers = wait_hooks(&home, 2);
    assert_eq!(
        told(answers.get(1)),
        "allow",
        "\"Always allow this file\" didn't hold on the next turn: {answers:?}"
    );
}

#[test]
fn p4b_the_phone_sees_what_it_always_allowed_and_can_take_it_back() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "edit_ask.jsonl");
    home.play("resume.jsonl", "edit_ask.jsonl");
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    let project = home.project.display().to_string();
    let task = new_task(
        &p,
        &mut seen,
        &project,
        "Change old line to new line in notes.txt.",
        None,
    );
    assert!(seen.until(&p, |s| s.item(NeedsKind::Permission).is_some()));
    let item = seen.item(NeedsKind::Permission).unwrap();
    seen.call(
        &p,
        Call::AnswerItem {
            ask_id: item.ask_id,
            answer: ItemAnswer::AllowFileThisTask,
        },
    )
    .expect("the answer was refused");
    assert_eq!(told(wait_hooks(&home, 1).first()), "allow");
    let listed = seen
        .call(&p, Call::AlwaysAllowed { task: task.clone() })
        .expect("the phone may not see what it allowed");
    let files: Vec<String> = serde_json::from_value(listed).unwrap();
    assert_eq!(files.len(), 1, "{files:?}");
    assert!(files[0].ends_with("notes.txt"), "{files:?}");
    seen.call(
        &p,
        Call::StopAllowing {
            task: task.clone(),
            file: files[0].clone(),
        },
    )
    .expect("the phone may not take it back");
    let listed = seen
        .call(&p, Call::AlwaysAllowed { task: task.clone() })
        .unwrap();
    assert_eq!(listed, serde_json::json!([]), "the rule is still there");
    // The next turn asks again instead of being allowed at once.
    assert!(service_until(&mut st, |s| s.tasks.iter().any(|v| v
        .task
        .id
        == task
        && !v.busy())));
    assert!(seen.until(&p, |s| s.item(NeedsKind::Permission).is_none()));
    seen.call(
        &p,
        Call::Send {
            files: vec![],
            task: task.clone(),
            text: "And again.".into(),
            pictures: vec![],
            msg_id: None,
        },
    )
    .unwrap();
    assert!(
        seen.until(&p, |s| s.item(NeedsKind::Permission).is_some()),
        "the edit wasn't asked again after the rule was taken back"
    );
}

#[test]
fn p6_a_message_sent_again_after_a_restart_is_done_once() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, mut seen, pc) = paired(&mut st, None);
    let project = home.project.display().to_string();
    let first = new_task(&p, &mut seen, &project, "Tidy up.", Some("m-once"));
    // The phone's link dropped before the reply arrived; the service
    // restarts meanwhile; the phone sends the message again.
    home.stop_service();
    let mut st = home.service();
    // The restarted link listens on new ports.
    let (_, pc2) = phone(&mut st);
    assert_eq!(pc, pc2, "the PC's link id changed");
    rehint(&mut st, &p);
    p.connect(&pc).expect("no reconnect");
    hello(&p, &mut seen, None);
    let again = new_task(&p, &mut seen, &project, "Tidy up.", Some("m-once"));
    assert_eq!(first, again, "the message was done twice");
    assert!(
        service_until(&mut st, |s| s.tasks.len() == 1),
        "a second task was started"
    );
    // Chat messages too.
    let a = seen.call(
        &p,
        Call::Chat {
            ground_rules: None,
            msg_id: "c-once".into(),
            text: "Station: idea - a tidy button".into(),
        },
    );
    let b = seen.call(
        &p,
        Call::Chat {
            ground_rules: None,
            msg_id: "c-once".into(),
            text: "Station: idea - a tidy button".into(),
        },
    );
    assert!(
        matches!(filed(a.clone().unwrap()), Filed::Idea { .. }),
        "{a:?}"
    );
    assert_eq!(a, b);
    let ideas = seen.call(&p, Call::Ideas { project }).unwrap();
    assert_eq!(
        ideas.as_array().map(Vec::len),
        Some(1),
        "the idea was kept twice: {ideas}"
    );
}

#[test]
fn p4_a_session_ask_reaches_the_phone_and_its_answer_comes_back() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    let sock = home.root.join("run/mimir/session.sock");
    let project = home.project.display().to_string();
    let asker = std::thread::spawn(move || {
        let mut s = std::os::unix::net::UnixStream::connect(sock).unwrap();
        write_msg(
            &mut s,
            &SessionMsg::Ask {
                text: "Does the new pairing screen work on your phone?".into(),
                choices: vec![],
                wait: true,
                project: Some(project),
                kind: Some("check-fix".into()),
                link: None,
            },
        )
        .unwrap();
        read_msg::<String>(&mut s).unwrap()
    });
    assert!(
        seen.until(&p, |s| s.item(NeedsKind::CheckFix).is_some()),
        "the ask never reached the phone"
    );
    let item = seen.item(NeedsKind::CheckFix).unwrap();
    assert_eq!(item.choices, vec!["It works", "Still broken"]);
    assert!(!item.serious, "a fix to check isn't serious");
    // A wrong kind of answer is refused.
    assert!(
        seen.call(
            &p,
            Call::AnswerItem {
                ask_id: item.ask_id.clone(),
                answer: ItemAnswer::Allow
            }
        )
        .is_err()
    );
    seen.call(
        &p,
        Call::AnswerItem {
            ask_id: item.ask_id,
            answer: ItemAnswer::Works,
        },
    )
    .expect("the answer was refused");
    let got = asker.join().unwrap();
    assert_eq!(got.as_deref(), Some("It works"));
    assert!(seen.until(&p, |s| s.item(NeedsKind::CheckFix).is_none()));
    // The command line says the same through the same socket.
    let out = std::process::Command::new(station_program())
        .args(["ask", "--kind", "nonsense", "--text", "x"])
        .output()
        .unwrap();
    assert!(!out.status.success());
    let out = std::process::Command::new(station_program())
        .args(["ask", "--kind", "release", "--text", "Release 0.17?"])
        .output()
        .unwrap();
    let id = String::from_utf8_lossy(&out.stdout).trim().to_string();
    assert!(id.starts_with("ask:"), "ask printed {id:?}");
    assert!(seen.until(&p, |s| {
        s.item(NeedsKind::Release)
            .is_some_and(|i| i.ask_id == id && i.serious)
    }));
}

// ---- Phase 5: the general chat ----

#[test]
fn p2_chat_files_ideas_requests_and_the_inbox() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    let project = home.project.display().to_string();
    let recipe = other_project(&home);

    // An idea, by alias; Undo removes it.
    let f = filed(
        seen.call(
            &p,
            Call::Chat {
                ground_rules: None,
                msg_id: "c1".into(),
                text: "Recipes: idea - a dark mode switch".into(),
            },
        )
        .unwrap(),
    );
    let Filed::Idea { project: ip, idea } = f else {
        panic!("not an idea: {f:?}")
    };
    assert_eq!(ip, recipe);
    let ideas = seen
        .call(
            &p,
            Call::Ideas {
                project: recipe.clone(),
            },
        )
        .unwrap();
    assert_eq!(ideas[0]["text"], "a dark mode switch");
    seen.call(
        &p,
        Call::UndoIdea {
            project: recipe.clone(),
            idea,
        },
    )
    .unwrap();
    let ideas = seen
        .call(
            &p,
            Call::Ideas {
                project: recipe.clone(),
            },
        )
        .unwrap();
    assert_eq!(ideas.as_array().map(Vec::len), Some(0));

    // A request with a prefix starts a task asking first.
    let f = filed(
        seen.call(
            &p,
            Call::Chat {
                ground_rules: None,
                msg_id: "c2".into(),
                text: "@Station tidy the notes".into(),
            },
        )
        .unwrap(),
    );
    let Filed::NewTask { project: tp, task } = f else {
        panic!("not a task: {f:?}")
    };
    assert_eq!(tp, project);
    assert!(service_until(&mut st, |s| s.tasks.iter().any(|v| v
        .task
        .id
        == task
        && v.task.mode == Mode::Ask)));

    // A follow-up within 10 minutes goes to the same project.
    let f = filed(
        seen.call(
            &p,
            Call::Chat {
                ground_rules: None,
                msg_id: "c3".into(),
                text: "and the readme".into(),
            },
        )
        .unwrap(),
    );
    assert!(
        matches!(&f, Filed::SameTask { project: x, .. } | Filed::NewTask { project: x, .. } if *x == project),
        "the follow-up went elsewhere: {f:?}"
    );

    // A status question is answered from facts.
    let f = filed(
        seen.call(
            &p,
            Call::Chat {
                ground_rules: None,
                msg_id: "c4".into(),
                text: "What's the status?".into(),
            },
        )
        .unwrap(),
    );
    let Filed::Status { reply } = f else {
        panic!("not a status reply: {f:?}")
    };
    assert!(
        reply[0].ends_with("need you.")
            || reply[0] == "Nothing needs you."
            || reply[0] == "1 thing needs you.",
        "{reply:?}"
    );

    // After a status question about all projects there is no "same
    // project": an idea naming none is just a chat, kept in the chats
    // folder (the Inbox is no longer fed from the chat).
    let f = filed(
        seen.call(
            &p,
            Call::Chat {
                ground_rules: None,
                msg_id: "c5".into(),
                text: "Idea: something about speed".into(),
            },
        )
        .unwrap(),
    );
    let chats = mimir_core::paths::chats_dir().display().to_string();
    assert!(
        matches!(&f, Filed::NewTask { project: x, .. } if *x == chats),
        "not a chat: {f:?}"
    );
    assert_eq!(
        seen.call(&p, Call::Inbox).unwrap().as_array().map(Vec::len),
        Some(0)
    );
    let _ = recipe;
}

/// P1's owner-way test: an older phone app still chats with a PC on the new
/// build, and an everyday question adds nothing to General: it is a chat in
/// the chats folder (moved to the chat store later), never a guess.
#[test]
fn p2_an_everyday_chat_goes_to_the_chats_folder_not_random() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.play("run-2.jsonl", "live.jsonl");
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    let chats = mimir_core::paths::chats_dir().display().to_string();
    for (i, text) in ["give me a random number", "Fix the bug"]
        .iter()
        .enumerate()
    {
        let f = filed(
            seen.call(
                &p,
                Call::Chat {
                    ground_rules: None,
                    msg_id: format!("u{i}"),
                    text: text.to_string(),
                },
            )
            .unwrap(),
        );
        // The second goes on in the first's chat.
        let (Filed::NewTask { project, .. } | Filed::SameTask { project, .. }) = f else {
            panic!("{text:?} wasn't a chat: {f:?}")
        };
        assert_eq!(project, chats, "{text:?}");
    }
    assert!(
        !home.root.join("Projects/General").exists(),
        "General was made for a chat"
    );
    assert!(
        st.tasks.iter().all(|v| v.task.placed.is_none()),
        "a chat was marked as placed"
    );
    assert_eq!(
        seen.call(&p, Call::Inbox).unwrap().as_array().map(Vec::len),
        Some(0)
    );
}

#[test]
fn p2_move_restarts_the_task_in_the_new_project_with_the_brief() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "allow.jsonl");
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    let project = home.project.display().to_string();
    let recipe = other_project(&home);
    let f = filed(
        seen.call(
            &p,
            Call::Chat {
                ground_rules: None,
                msg_id: "m1".into(),
                text: "Station: Run this Bash command: touch a.txt".into(),
            },
        )
        .unwrap(),
    );
    let Filed::NewTask { task, .. } = f else {
        panic!("{f:?}")
    };
    let moved = seen
        .call(
            &p,
            Call::MoveTask {
                task: task.clone(),
                project: recipe.clone(),
            },
        )
        .expect("Move was refused");
    let moved = moved.as_str().unwrap().to_string();
    assert!(
        service_until(&mut st, |s| {
            let old = s.tasks.iter().find(|v| v.task.id == task);
            let new = s.tasks.iter().find(|v| v.task.id == moved);
            old.is_some_and(|v| {
            v.task.entries.iter().any(|e| {
                matches!(e, mimir_core::task::Entry::Note(n) if n.starts_with("Moved to Recipe-Box"))
            })
        }) && new.is_some_and(|v| v.task.project == recipe && v.task.mode == Mode::Ask)
        }),
        "the task wasn't moved"
    );
    let first = st
        .tasks
        .iter()
        .find(|v| v.task.id == moved)
        .and_then(|v| {
            v.task.entries.iter().find_map(|e| match e {
                mimir_core::task::Entry::You(t) => Some(t.clone()),
                _ => None,
            })
        })
        .unwrap_or_default();
    assert!(
        first.starts_with("Run this Bash command: touch a.txt")
            && first.contains("first started in project"),
        "the moved task's message lacks the handover: {first}"
    );
    let _ = project;
}

// ---- Phase 6: status from facts ----

/// A run log line as tools/core_checks.py writes it.
fn run_line(time: &str, commit: &str, results: serde_json::Value) -> String {
    serde_json::json!({
        "time": time, "kind": "window", "commit": commit, "dirty": false,
        "claude_code": "2.1.327", "results": results, "missing_features": [],
        "program_sha256": null, "anyway": null, "note": null, "refers_to": null,
    })
    .to_string()
}

#[test]
fn p5_a_check_that_starts_failing_makes_a_serious_item() {
    let Some(home) = home() else { return };
    set_env("MIMIR_CHECKS_EVERY_MS", "200");
    std::fs::create_dir_all(home.project.join("checks")).unwrap();
    std::fs::write(
        home.project.join("checks/core-features.md"),
        "# Features\n\n## CF-3\n\nAllow and Don't allow reach Claude.\n\nApproved: 2026-11-18\n",
    )
    .unwrap();
    let log = home.root.join(".local/share/keep-it-working/project.jsonl");
    std::fs::create_dir_all(log.parent().unwrap()).unwrap();
    std::fs::write(
        &log,
        run_line(
            "2026-11-18T10:00:00Z",
            "aaaa111",
            serde_json::json!({"cf3_allow_reaches_claude": "passed"}),
        ) + "\n",
    )
    .unwrap();
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    // The page says it's passing.
    let page: StatusPage = serde_json::from_value(seen.call(&p, Call::Status).unwrap()).unwrap();
    let f = page
        .projects
        .iter()
        .find(|f| f.name == "project")
        .expect("no project on the page");
    assert_eq!(f.line.split(" · ").next(), Some("All 1 passing"));
    std::thread::sleep(Duration::from_millis(600));
    let mut w = std::fs::OpenOptions::new().append(true).open(&log).unwrap();
    writeln!(
        w,
        "{}",
        run_line(
            "2026-11-18T11:00:00Z",
            "bbbb222",
            serde_json::json!({"cf3_allow_reaches_claude": "failed"})
        )
    )
    .unwrap();
    assert!(
        seen.until(&p, |s| s.item(NeedsKind::CheckFailing).is_some()),
        "no needs-you item for the check that started failing"
    );
    let item = seen.item(NeedsKind::CheckFailing).unwrap();
    assert!(item.serious);
    assert!(
        item.text.contains("Allow and Don't allow reach Claude"),
        "{}",
        item.text
    );
    // The page says so too.
    let page: StatusPage = serde_json::from_value(seen.call(&p, Call::Status).unwrap()).unwrap();
    let f = page.projects.iter().find(|f| f.name == "project").unwrap();
    assert!(
        f.line
            .starts_with("1 of 1 failing: allow and Don't allow reach Claude"),
        "{}",
        f.line
    );
    assert_eq!(
        page.projects[0].name, "project",
        "a failing project comes first"
    );
    // "Ask Claude to fix it" starts a task asking first.
    seen.call(
        &p,
        Call::AnswerItem {
            ask_id: item.ask_id,
            answer: ItemAnswer::AskClaudeToFix,
        },
    )
    .unwrap();
    assert!(
        service_until(&mut st, |s| {
            s.tasks.iter().any(|v| v.task.mode == Mode::Ask
        && v.task.entries.iter().any(|e| matches!(e,
            mimir_core::task::Entry::You(t) if t.contains("started failing at bbbb222") && t.ends_with("find out why and fix it."))))
        }),
        "no fix task started"
    );
}

/// Move to another project from the phone: only to a project on the PC's
/// list, and the task asks first there, whatever mode it had.
#[test]
fn p2_a_phone_moves_a_task_only_to_a_listed_project_and_it_asks_first_there() {
    let Some(home) = home() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let project = home.project.display().to_string();
    let recipe = other_project(&home);
    let task = st
        .call_wait(Call::NewTask {
            files: vec![],
            project: project.clone(),
            text: "Tidy up notes.txt.".into(),
            pictures: vec![],
            mode: Mode::NoAsk,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: None,
            ground_rules: None,
        })
        .unwrap()
        .as_str()
        .unwrap()
        .to_string();
    assert!(service_until(&mut st, |s| s.tasks.iter().any(|v| v
        .task
        .id
        == task
        && !v.busy())));
    let sid = "5f1c2e7a-6b3d-4c8e-9a10-11ee0c0f1a2b";
    let dir = home
        .root
        .join(".claude/projects")
        .join(folder_safe(&project));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join(format!("{sid}.jsonl")), "{}\n").unwrap();
    let (p, mut seen, _) = paired(&mut st, None);
    let elsewhere = home.root.join("Not-listed");
    std::fs::create_dir_all(&elsewhere).unwrap();
    let refused = seen.call(
        &p,
        Call::MoveTo {
            task: task.clone(),
            project: elsewhere.display().to_string(),
        },
    );
    assert!(
        refused.is_err(),
        "a phone moved a task to an unlisted folder"
    );
    seen.call(
        &p,
        Call::MoveTo {
            task: task.clone(),
            project: recipe.clone(),
        },
    )
    .expect("the move to a listed project was refused");
    assert!(
        service_until(&mut st, |s| s.tasks.iter().any(|v| v.task.id == task
            && v.task.project == recipe
            && v.task.mode == Mode::Ask)),
        "the moved task doesn't ask first"
    );
}

/// A phone starts a task only in a project on the PC's list, as with
/// MoveTo; an unlisted folder is refused and nothing starts there.
#[test]
fn n3_a_phone_starts_a_task_only_in_a_listed_project() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    let new = |project: String| Call::NewTask {
        files: vec![],
        project,
        text: "Tidy up notes.txt.".into(),
        pictures: vec![],
        mode: Mode::Ask,
        model: None,
        effort: None,
        separate_copy: false,
        msg_id: None,
        ground_rules: None,
    };
    let elsewhere = home.root.join("Not-listed");
    std::fs::create_dir_all(&elsewhere).unwrap();
    let refused = seen.call(&p, new(elsewhere.display().to_string()));
    assert!(
        refused.is_err(),
        "a phone started a task in an unlisted folder"
    );
    seen.call(&p, new(home.project.display().to_string()))
        .expect("a task in a listed project was refused");
    assert!(
        !st.tasks
            .iter()
            .any(|v| v.task.project == elsewhere.display().to_string()),
        "a task started in the unlisted folder"
    );
}

/// YOLO from the phone: refused until the PC's Settings allow it, then the
/// task never asks; the window may always.
#[test]
fn yolo_from_a_phone_only_when_the_pc_allows_it() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let task = st
        .call_wait(Call::NewTask {
            files: vec![],
            project: home.project.display().to_string(),
            text: "Tidy up notes.txt.".into(),
            pictures: vec![],
            mode: Mode::Ask,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: None,
            ground_rules: None,
        })
        .unwrap()
        .as_str()
        .unwrap()
        .to_string();
    let (p, mut seen, _) = paired(&mut st, None);
    let yolo = || Call::SetMode {
        task: task.clone(),
        mode: Mode::NoAsk,
    };
    let refused = seen.call(&p, yolo());
    assert!(
        refused
            .as_ref()
            .is_err_and(|e| e.contains("Allow YOLO from my phone")),
        "a phone turned on YOLO with the PC's switch off: {refused:?}"
    );
    assert!(
        st.tasks
            .iter()
            .any(|v| v.task.id == task && v.task.mode == Mode::Ask)
    );
    st.call_wait(Call::SetSettings {
        settings: serde_json::json!({"yolo_from_phone": true}),
    })
    .unwrap();
    seen.call(&p, yolo())
        .expect("YOLO was refused with the PC's switch on");
    assert!(
        service_until(&mut st, |s| s
            .tasks
            .iter()
            .any(|v| v.task.id == task && v.task.mode == Mode::NoAsk)),
        "the task isn't in YOLO"
    );
}

#[test]
fn p4_another_sessions_permission_request_is_answered_on_the_phone() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    let project = home.project.display().to_string();
    let outside = |s: &Seen| {
        s.needs
            .iter()
            .find(|i| i.kind == NeedsKind::Permission && i.task.is_none())
            .cloned()
    };

    // Allow on the phone: the hook prints Claude Code's allow.
    let hook = approve_hook(&project, false);
    assert!(
        seen.until(&p, |s| outside(s).is_some()),
        "the request never reached the phone"
    );
    let item = outside(&seen).unwrap();
    assert_eq!(item.project, project);
    assert!(item.serious);
    assert!(item.text.contains("hello.txt"), "{}", item.text);
    assert!(item.title.contains("5e55104"), "{}", item.title);
    assert_eq!(
        item.choices,
        vec!["Allow", "Don't allow", "Always allow", "Answer on the PC"]
    );
    seen.call(
        &p,
        Call::AnswerItem {
            ask_id: item.ask_id,
            answer: ItemAnswer::Option(0),
        },
    )
    .expect("the answer was refused");
    let out: serde_json::Value = serde_json::from_str(&hook_printed(hook)).unwrap();
    assert_eq!(out["hookSpecificOutput"]["decision"]["behavior"], "allow");
    assert!(seen.until(&p, |s| outside(s).is_none()));

    // Don't allow. Always allow (a lasting rule) is refused from the phone
    // first, and the request still waits.
    let hook = approve_hook(&project, false);
    assert!(seen.until(&p, |s| outside(s).is_some()));
    let id = outside(&seen).unwrap().ask_id;
    for always in [ItemAnswer::Option(2), ItemAnswer::AlwaysProject] {
        let r = seen.call(
            &p,
            Call::AnswerItem {
                ask_id: id.clone(),
                answer: always,
            },
        );
        assert!(r.is_err(), "the phone gave Always allow");
    }
    assert!(outside(&seen).is_some_and(|i| i.ask_id == id));
    seen.call(
        &p,
        Call::AnswerItem {
            ask_id: id,
            answer: ItemAnswer::Option(1),
        },
    )
    .unwrap();
    let out: serde_json::Value = serde_json::from_str(&hook_printed(hook)).unwrap();
    assert_eq!(out["hookSpecificOutput"]["decision"]["behavior"], "deny");

    // Answer on the PC: the hook prints nothing, so Claude Code asks as usual.
    let hook = approve_hook(&project, false);
    assert!(seen.until(&p, |s| outside(s).is_some()));
    let id = outside(&seen).unwrap().ask_id;
    seen.call(
        &p,
        Call::AnswerItem {
            ask_id: id,
            answer: ItemAnswer::Option(3),
        },
    )
    .unwrap();
    assert_eq!(hook_printed(hook), "");

    // A hook that stops waiting (the session ended) takes its request away.
    let mut hook = approve_hook(&project, false);
    assert!(seen.until(&p, |s| outside(s).is_some()));
    hook.kill().unwrap();
    let _ = hook.wait();
    assert!(
        seen.until(&p, |s| outside(s).is_none()),
        "a gone hook's request stayed"
    );

    // A session Mímir runs asks its own way: nothing reaches the phone.
    let hook = approve_hook(&project, true);
    assert_eq!(hook_printed(hook), "");
    assert!(outside(&seen).is_none());
}

/// The phone's project list has no stand-ins or doubles, and a chat naming
/// a git project beside the listed ones that isn't listed yet goes there.
#[test]
fn p2_one_clean_project_list_and_an_unlisted_git_project_gets_its_chat() {
    let Some(home) = TestHome::new() else { return };
    let maker = home.root.join("Projects/Map-Maker");
    std::fs::create_dir_all(maker.join(".git")).unwrap();
    std::fs::write(maker.join("README.md"), "# Map Maker\n").unwrap();
    // The listed "Recipes" is an empty stand-in for the real one beside the
    // projects.
    let real = home.root.join("Projects/Recipes");
    std::fs::create_dir_all(real.join(".git")).unwrap();
    std::fs::write(real.join("README.md"), "# Recipes\n").unwrap();
    let stand_in = home.root.join("Documents/recipes");
    std::fs::create_dir_all(&stand_in).unwrap();
    std::fs::write(stand_in.join(".directory"), "").unwrap();
    // Most listed projects are in ~/Projects.
    let notes = home.root.join("Projects/Notes");
    std::fs::create_dir_all(&notes).unwrap();
    std::fs::write(notes.join("todo.txt"), "").unwrap();
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project, notes, stand_in],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
    }));
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let (p, mut seen, _) = paired(&mut st, None);
    let list = seen.call(&p, Call::Projects).unwrap();
    let paths: Vec<&str> = list["projects"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|p| p["path"].as_str())
        .collect();
    let names: Vec<&str> = list["projects"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|p| p["name"].as_str())
        .collect();
    assert!(
        paths.contains(&maker.display().to_string().as_str())
            && paths.contains(&real.display().to_string().as_str())
            && !paths.contains(&stand_in.display().to_string().as_str()),
        "{list}"
    );
    assert_eq!(
        names
            .iter()
            .filter(|n| n.eq_ignore_ascii_case("recipes"))
            .count(),
        1,
        "{list}"
    );
    let f = filed(
        seen.call(
            &p,
            Call::Chat {
                ground_rules: None,
                msg_id: "hf1".into(),
                // (The test home's own project is called "project".)
                text: "In Map-Maker, what does this app do?".into(),
            },
        )
        .unwrap(),
    );
    let Filed::NewTask { project, .. } = f else {
        panic!("not a new task: {f:?}")
    };
    assert_eq!(project, maker.display().to_string());
}
