//! The window's Chats (P10b): the same general chats the phone has. A chat
//! a phone saves shows in the side list within 2 s; a message sent from the
//! window is answered by the PC's model, streamed into the window, and the
//! phone sees it, marked "Your PC". Stop ends an answer.

mod common;
use common::*;
use egui_kittest::kittest::Queryable;
use mimir_core::proto::{Call, ChatRecord, ChatTurn, ChatsPage, Event, Ran};
use mimir_link::client::{Client, generate_key};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::time::{Duration, Instant};

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

/// A stand-in Ollama: `/api/chat` streams `words`, one piece each, `pause`
/// apart.
fn fake_ollama(words: &'static [&'static str], pause: Duration) {
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
                let (lines, slow): (Vec<String>, bool) = match path.as_str() {
                    "/api/version" => (vec![r#"{"version":"0.40.16"}"#.into()], false),
                    "/api/ps" | "/api/tags" => (vec![r#"{"models":[]}"#.into()], false),
                    _ => {
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
                        (out, true)
                    }
                };
                let mut w = c;
                let _ = w.write_all(
                    b"HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nTransfer-Encoding: chunked\r\n\r\n",
                );
                for line in lines {
                    let data = format!("{line}\n");
                    if w.write_all(format!("{:x}\r\n{data}\r\n", data.len()).as_bytes())
                        .is_err()
                        || w.flush().is_err()
                    {
                        return;
                    }
                    std::thread::sleep(if slow {
                        pause
                    } else {
                        Duration::from_millis(5)
                    });
                }
                let _ = w.write_all(b"0\r\n\r\n");
            });
        }
    });
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

/// A fake phone, paired and past its snapshot, that knows chats.
fn paired(st: &mut mimir_core::client::Station) -> Client {
    let info = st
        .call_wait(Call::LinkInfo)
        .expect("the link isn't running");
    let pc = info["id"].as_str().unwrap().to_string();
    let p = Client::new(generate_key(), false).unwrap();
    for a in info["bound"].as_array().unwrap() {
        let a: std::net::SocketAddr = a.as_str().unwrap().parse().unwrap();
        let ip: std::net::IpAddr = if a.is_ipv4() {
            [127, 0, 0, 1].into()
        } else {
            std::net::Ipv6Addr::LOCALHOST.into()
        };
        p.add_hint(std::net::SocketAddr::new(ip, a.port()));
    }
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
    p.call(Call::Hello {
        proto: mimir_core::proto::PROTO,
        app_version: "test".into(),
        client_name: "Fake phone".into(),
        env: vec![],
        features: ["search", "lookup", "talk", "chat_sync", "truth"]
            .iter()
            .map(|f| f.to_string())
            .collect(),
    })
    .unwrap();
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        match p.next_event(Duration::from_millis(50)) {
            Some(m) if matches!(m.event, Event::Ready) => break,
            _ => {}
        }
        assert!(Instant::now() < until, "no snapshot after Hello");
    }
    p
}

fn turn(id: &str, user: bool, text: &str, ran: Option<Ran>, words: Option<&str>) -> ChatTurn {
    ChatTurn {
        id: id.into(),
        user,
        text: text.into(),
        sources: vec![],
        ran,
        model_words: words.map(str::to_string),
        at: 1_794_000_000_000 + if user { 0 } else { 1000 },
        version: 1,
        seq: 0,
        gone: false,
        pictures: vec![],
        files: vec![],
    }
}

/// Steps the window until `done`, up to `secs` of real time.
fn wait_secs(
    h: &mut egui_kittest::Harness<'static, station_ui::App>,
    secs: f32,
    done: impl Fn(&egui_kittest::Harness<'static, station_ui::App>) -> bool,
) -> Option<Duration> {
    let start = Instant::now();
    let until = start + Duration::from_secs_f32(secs);
    loop {
        h.step();
        if done(h) {
            return Some(start.elapsed());
        }
        if Instant::now() > until {
            return None;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// A chat a phone saved shows in the window's side list within 2 s, opens,
/// and says where its answer ran.
#[test]
fn a_chat_the_phone_saved_shows_in_the_window_within_2_seconds() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    let mut st = home.service();
    let phone = paired(&mut st);
    assert!(
        !shows(&h, "Capital of Norway"),
        "the chat was there before it was sent"
    );
    let put = |turns: Vec<ChatTurn>| {
        phone
            .call(Call::ChatPut {
                chat_id: "phone-chat".into(),
                name: None,
                turns,
                deleted: false,
            })
            .unwrap();
    };
    let sent = Instant::now();
    put(vec![
        turn("p1", true, "Capital of Norway?", None, None),
        turn("p1.a", false, "Oslo.", Some(Ran::Phone), None),
    ]);
    let took = wait_secs(&mut h, 2.0, |h| shows(h, "Capital of Norway"));
    assert!(
        took.is_some(),
        "the chat didn't show within 2 s ({:?} after it was sent)\n{}",
        sent.elapsed(),
        screen_text(&h)
    );
    assert!(
        has_button(&h, "Chats") || shows(&h, "Chats"),
        "no Chats group"
    );
    // Open it: the owner's message, the answer, and where it ran.
    click(&mut h, "Chat: Capital of Norway?");
    assert!(
        wait_for(&mut h, |h| shows(h, "Oslo.")),
        "the answer isn't shown\n{}",
        screen_text(&h)
    );
    assert!(
        shows(&h, station_ui::labels::THIS_PHONE),
        "no mark for where it ran\n{}",
        screen_text(&h)
    );
    // A second phone message arrives while it is open.
    put(vec![
        turn("p2", true, "And Sweden?", None, None),
        turn("p2.a", false, "Stockholm.", Some(Ran::Phone), None),
    ]);
    assert!(
        wait_secs(&mut h, 2.0, |h| shows(h, "Stockholm.")).is_some(),
        "the new turn didn't show within 2 s\n{}",
        screen_text(&h)
    );
    close(h);
}

/// A message sent from the window reaches the PC's model; its answer streams
/// into the window, is marked "Your PC", and a phone gets it too.
#[test]
fn a_message_sent_from_the_window_is_answered_and_the_phone_sees_it() {
    let Some(home) = home() else { return };
    fake_ollama(
        &["Oslo is ", "the capital ", "of Norway."],
        Duration::from_millis(30),
    );
    picked(&home);
    let mut h = home.open();
    let mut st = home.service();
    let phone = paired(&mut st);
    // A new chat, a question, Send.
    click(&mut h, station_ui::labels::NEW_CHAT);
    type_message(&mut h, "What is the capital of Norway?");
    click(&mut h, station_ui::labels::SEND);
    assert!(
        wait_for(&mut h, |h| shows(h, "Oslo is the capital of Norway.")),
        "the answer never showed in the window\n{}",
        screen_text(&h)
    );
    // The mark under it: where it ran.
    assert!(
        wait_for(&mut h, |h| shows(h, "Qwen 3.6 · Your PC")),
        "no \"Your PC\" mark under the answer\n{}",
        screen_text(&h)
    );
    // No Claude Code was started for it.
    assert!(
        home.conversations().is_empty(),
        "{:?}",
        home.conversations()
    );
    // The phone got the message and the answer, marked as the PC's.
    let mut changed: Vec<ChatRecord> = vec![];
    let until = Instant::now() + Duration::from_secs(10);
    while Instant::now() < until
        && !changed
            .iter()
            .flat_map(|c| &c.turns)
            .any(|t| !t.user && t.text.contains("capital of Norway"))
    {
        if let Some(m) = phone.next_event(Duration::from_millis(50))
            && let Event::ChatChanged { chat } = m.event
        {
            changed.push(chat);
        }
    }
    let turns: Vec<&ChatTurn> = changed.iter().flat_map(|c| &c.turns).collect();
    assert!(
        turns
            .iter()
            .any(|t| t.user && t.text == "What is the capital of Norway?"),
        "the phone didn't get the message: {turns:?}"
    );
    let answer = turns
        .iter()
        .find(|t| !t.user && t.text.contains("capital of Norway"))
        .unwrap_or_else(|| panic!("the phone didn't get the answer: {turns:?}"));
    assert_eq!(answer.ran, Some(Ran::Pc));
    // The side list has the chat, named by its first message.
    assert!(shows(&h, "Chat: What is the capital of Norway?"));
    close(h);
}

/// Stop ends an answer being written: no more words come, and the box is
/// ready for the next message.
#[test]
fn stop_ends_an_answer_in_the_window() {
    let Some(home) = home() else { return };
    // A long answer, slowly.
    fake_ollama(
        &[
            "one ",
            "two ",
            "three ",
            "four ",
            "five ",
            "six ",
            "seven ",
            "eight ",
            "nine ",
            "ten ",
            "eleven ",
            "twelve ",
            "thirteen ",
            "fourteen ",
            "fifteen ",
            "sixteen ",
            "seventeen ",
            "eighteen ",
            "nineteen ",
            "twenty ",
            "END.",
        ],
        Duration::from_millis(250),
    );
    picked(&home);
    let mut h = home.open();
    click(&mut h, station_ui::labels::NEW_CHAT);
    type_message(&mut h, "Count.");
    click(&mut h, station_ui::labels::SEND);
    assert!(
        wait_for(&mut h, |h| shows(h, "three")
            && has_button(h, station_ui::labels::STOP)),
        "no streaming answer with a Stop button\n{}",
        screen_text(&h)
    );
    click(&mut h, station_ui::labels::STOP);
    assert!(
        wait_for(&mut h, |h| has_button(h, station_ui::labels::SEND)),
        "Send didn't come back after Stop\n{}",
        screen_text(&h)
    );
    // Time for the rest to have come, had it not been stopped.
    let _ = wait_secs(&mut h, 2.5, |_| false);
    assert!(
        !shows(&h, "END."),
        "the answer went on after Stop\n{}",
        screen_text(&h)
    );
    close(h);
}

/// Deleting a chat in the window asks first, then removes it here and on
/// the PC (a deleted chat never comes back to a phone).
#[test]
fn a_chat_deleted_in_the_window_is_gone_for_good() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    let mut st = home.service();
    st.call_wait(Call::ChatPut {
        chat_id: "gone".into(),
        name: None,
        turns: vec![
            turn("g1", true, "Tell me a secret", None, None),
            turn("g1.a", false, "No.", Some(Ran::Pc), None),
        ],
        deleted: false,
    })
    .unwrap();
    assert!(
        wait_secs(&mut h, 5.0, |h| has_button(h, "Chat: Tell me a secret")).is_some(),
        "the chat didn't show\n{}",
        screen_text(&h)
    );
    click(&mut h, "Chat: Tell me a secret");
    assert!(wait_for(&mut h, |h| shows(h, "No.")));
    click(&mut h, station_ui::labels::PILL_MENU);
    click(&mut h, "Delete this chat…");
    // It asks first: the chat is still there.
    assert!(
        wait_for(&mut h, |h| has_button(h, "Delete the chat")),
        "no question before deleting\n{}",
        screen_text(&h)
    );
    assert!(has_button(&h, "Chat: Tell me a secret"));
    click(&mut h, "Delete the chat");
    assert!(
        wait_for(&mut h, |h| !has_button(h, "Chat: Tell me a secret")),
        "the chat stayed in the list\n{}",
        screen_text(&h)
    );
    // The PC has it as deleted, so no phone brings it back.
    let page: mimir_core::proto::ChatsPage =
        serde_json::from_value(st.call_wait(Call::ChatsSince { seq: 0 }).unwrap()).unwrap();
    assert!(
        page.chats
            .iter()
            .any(|c| c.id == "gone" && c.deleted.is_some()),
        "the PC still has the chat: {page:?}"
    );
    close(h);
}

/// Editing an earlier message in the window: the words change, everything
/// after it is cut away here and on the PC (tombstones, for the phones), the
/// PC answers again from there, and Undo puts the old turns back.
#[test]
fn an_edited_message_cuts_the_rest_and_undo_brings_it_back() {
    let Some(home) = home() else { return };
    fake_ollama(&["Fine."], Duration::from_millis(20));
    picked(&home);
    let mut h = home.open();
    let mut st = home.service();
    click(&mut h, station_ui::labels::NEW_CHAT);
    type_message(&mut h, "First question");
    click(&mut h, station_ui::labels::SEND);
    assert!(
        wait_for(&mut h, |h| shows(h, "Fine.")),
        "{}",
        screen_text(&h)
    );
    assert!(wait_for(&mut h, |h| has_button(
        h,
        station_ui::labels::SEND
    )));
    type_message(&mut h, "Second question");
    click(&mut h, station_ui::labels::SEND);
    assert!(wait_for(&mut h, |h| screen_text(h)
        .matches("Label: Fine.")
        .count()
        == 2));
    assert!(wait_for(&mut h, |h| has_button(
        h,
        station_ui::labels::SEND
    )));
    // The pencil on the first message edits it in place.
    let page = |st: &mut mimir_core::client::Station| -> ChatsPage {
        serde_json::from_value(st.call_wait(Call::ChatsSince { seq: 0 }).unwrap()).unwrap()
    };
    let before = page(&mut st);
    assert_eq!(before.chats[0].turns.len(), 4);
    let first = before.chats[0].turns[0].clone();
    click(&mut h, "✎ Edit");
    assert!(
        wait_for(&mut h, |h| has_button(h, "Cancel")),
        "{}",
        screen_text(&h)
    );
    h.event(eframe::egui::Event::Text(" really".into()));
    h.step();
    click(&mut h, "Send the edit");
    let until = Instant::now() + Duration::from_secs(15);
    loop {
        h.step();
        let p = page(&mut st);
        let turns = &p.chats[0].turns;
        let live: Vec<&ChatTurn> = turns.iter().filter(|t| !t.gone).collect();
        if live.len() == 2
            && live[0].version == 2
            && live[1].version > 2
            && turns.iter().any(|t| t.gone)
        {
            assert!(live[0].text.contains("really"), "{live:?}");
            assert!(turns.iter().filter(|t| t.gone).all(|t| t.text.is_empty()));
            break;
        }
        assert!(Instant::now() < until, "the cut didn't reach the PC: {p:?}");
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(
        wait_for(&mut h, |h| shows(h, "Later messages removed")),
        "{}",
        screen_text(&h)
    );
    assert!(!shows(&h, "Second question"), "{}", screen_text(&h));
    // Undo: the old turns are back, with higher versions than the cut.
    click(&mut h, "Undo");
    assert!(
        wait_for(&mut h, |h| shows(h, "Second question")),
        "{}",
        screen_text(&h)
    );
    let until = Instant::now() + Duration::from_secs(15);
    loop {
        h.step();
        let p = page(&mut st);
        let turns = &p.chats[0].turns;
        if turns.len() == 4 && turns.iter().all(|t| !t.gone) && turns[0].text == first.text {
            break;
        }
        assert!(
            Instant::now() < until,
            "the undo didn't reach the PC: {p:?}"
        );
        std::thread::sleep(Duration::from_millis(50));
    }
    close(h);
}

/// Rename in the window sticks on the PC (so a phone gets the name), and
/// Try again replaces the answer instead of adding a second one.
#[test]
fn rename_and_try_again_in_the_window() {
    let Some(home) = home() else { return };
    fake_ollama(&["Oslo is ", "the capital."], Duration::from_millis(20));
    picked(&home);
    let mut h = home.open();
    let mut st = home.service();
    click(&mut h, station_ui::labels::NEW_CHAT);
    type_message(&mut h, "Capital of Norway?");
    click(&mut h, station_ui::labels::SEND);
    assert!(
        wait_for(&mut h, |h| shows(h, "Oslo is the capital.")),
        "no answer\n{}",
        screen_text(&h)
    );
    // Try again: one question, one answer, the answer saved as version 2.
    assert!(wait_for(&mut h, |h| has_button(h, "Try again")
        || shows(h, "Try again")));
    click(&mut h, "Try again");
    let page = |st: &mut mimir_core::client::Station| -> ChatsPage {
        serde_json::from_value(st.call_wait(Call::ChatsSince { seq: 0 }).unwrap()).unwrap()
    };
    let until = Instant::now() + Duration::from_secs(15);
    loop {
        h.step();
        let p = page(&mut st);
        let answers: Vec<&ChatTurn> = p
            .chats
            .iter()
            .flat_map(|c| &c.turns)
            .filter(|t| !t.user)
            .collect();
        if answers.len() == 1 && answers[0].version == 2 {
            break;
        }
        assert!(Instant::now() < until, "answer not replaced: {p:?}");
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(wait_for(&mut h, |h| shows(h, "Oslo is the capital.")));
    assert_eq!(
        screen_text(&h)
            .matches("Label: Oslo is the capital.")
            .count(),
        1,
        "the answer is there twice\n{}",
        screen_text(&h)
    );
    // Rename.
    click(&mut h, station_ui::labels::PILL_MENU);
    click(&mut h, "Rename this chat…");
    let boxes: Vec<_> = h
        .query_all_by_role(eframe::egui::accesskit::Role::TextInput)
        .collect();
    boxes.last().expect("no name box").click();
    h.step();
    h.query_all_by_role(eframe::egui::accesskit::Role::TextInput)
        .last()
        .unwrap()
        .type_text(" nordic");
    h.step();
    click(&mut h, "Rename");
    assert!(
        wait_for(&mut h, |h| has_button(h, "Chat: Capital of Norway? nordic")),
        "the new name isn't in the list\n{}",
        screen_text(&h)
    );
    let p = page(&mut st);
    assert!(
        p.chats
            .iter()
            .any(|c| c.name.as_deref() == Some("Capital of Norway? nordic")),
        "the PC doesn't have the name: {p:?}"
    );
    close(h);
}

/// With no model picked, the PC can't answer a chat: the window says so and
/// Send comes back (the chat is not left "answering" with a Stop button).
#[test]
fn a_chat_the_pc_cannot_answer_gets_send_back() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    click(&mut h, station_ui::labels::NEW_CHAT);
    type_message(&mut h, "What is the capital of Norway?");
    click(&mut h, station_ui::labels::SEND);
    assert!(
        wait_for(&mut h, |h| shows(h, "can't answer this one")),
        "no word that the PC can't answer\n{}",
        screen_text(&h)
    );
    assert!(
        wait_for(&mut h, |h| has_button(h, station_ui::labels::SEND)
            && !has_button(h, station_ui::labels::STOP)),
        "the chat stayed answering\n{}",
        screen_text(&h)
    );
    close(h);
}

/// Not a check: makes the kept home `shotpc` with a few chats in it, for the
/// pictures of the window (run it alone with `--run-ignored only`, with
/// CHECK_HOME_NAME=shotpc). The window is then started on that home.
#[test]
#[ignore]
fn make_the_picture_home() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let mut put = |chat: &str, turns: Vec<ChatTurn>| {
        st.call_wait(Call::ChatPut {
            chat_id: chat.into(),
            name: None,
            turns,
            deleted: false,
        })
        .unwrap();
    };
    let at = |t: i64, mut x: ChatTurn| {
        x.at = t;
        x
    };
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    put(
        "c-norway",
        vec![
            at(
                now - 600_000,
                turn(
                    "n1",
                    true,
                    "What is the capital of Norway, and how many people live there?",
                    None,
                    None,
                ),
            ),
            at(
                now - 590_000,
                turn(
                    "n1.a",
                    false,
                    "The capital is **Oslo**. About 710,000 people live in the city itself, and around 1.6 million in the wider urban area.\n\nA few more facts:\n\n- It sits at the head of the Oslofjord.\n- It has been the capital since 1814.",
                    Some(Ran::Pc),
                    Some("Qwen 3.6 · Your PC"),
                ),
            ),
            at(now - 300_000, turn("n2", true, "And Sweden?", None, None)),
            at(
                now - 290_000,
                turn(
                    "n2.a",
                    false,
                    "Stockholm. About 980,000 people live in the city.",
                    Some(Ran::Phone),
                    Some("This phone"),
                ),
            ),
        ],
    );
    put(
        "c-bread",
        vec![
            at(
                now - 7_200_000,
                turn(
                    "b1",
                    true,
                    "A simple recipe for sourdough bread",
                    None,
                    None,
                ),
            ),
            at(
                now - 7_190_000,
                turn(
                    "b1.a",
                    false,
                    "Mix 500 g flour, 350 g water, 100 g starter and 10 g salt. Fold every 30 minutes for two hours, shape, and bake in a hot pot.",
                    Some(Ran::Pc),
                    Some("Qwen 3.6 · Your PC"),
                ),
            ),
        ],
    );
    put(
        "c-rust",
        vec![
            at(
                now - 90_000_000,
                turn(
                    "r1",
                    true,
                    "Explain lifetimes in Rust in two sentences",
                    None,
                    None,
                ),
            ),
            at(
                now - 89_990_000,
                turn(
                    "r1.a",
                    false,
                    "A lifetime says how long a reference may be used. The compiler checks that no reference outlives what it points to.",
                    Some(Ran::Pc),
                    Some("Qwen 3.6 · Your PC"),
                ),
            ),
        ],
    );
    // Let the PC take them in before the service is stopped.
    std::thread::sleep(Duration::from_secs(1));
    home.stop_service();
}
