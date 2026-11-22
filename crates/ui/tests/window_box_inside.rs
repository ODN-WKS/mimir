//! R18: the message box and the hint under it never leave the window, at any
//! size, however long the conversation is. Only the messages scroll.

mod common;
use common::*;
use eframe::egui::accesskit::Role;
use egui_kittest::kittest::Queryable;
use mimir_core::proto::{Call, ChatTurn, Entry, Event, Ran};
use mimir_link::client::{Client, generate_key};
use station_ui::labels;
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

fn long_text(n: usize) -> String {
    (0..n)
        .map(|i| {
            format!(
                "Paragraph {i}. Lettuce, spinach and kale do well with only a few hours of \
                 sun, and they like water in the morning so the leaves dry before night."
            )
        })
        .collect::<Vec<_>>()
        .join("\n\n")
}

/// The send button and the hint are fully inside a window of this size, and
/// so is the message box.
fn box_is_inside(
    h: &egui_kittest::Harness<'static, station_ui::App>,
    size: (f32, f32),
    hint: &str,
    what: &str,
) {
    let inside = |r: eframe::egui::Rect, name: &str| {
        assert!(
            r.min.x >= 0.0 && r.min.y >= 0.0 && r.max.x <= size.0 + 0.5 && r.max.y <= size.1 + 0.5,
            "{what} at {size:?}: {name} is at {r:?}, outside the window\n{}",
            screen_text(h)
        );
    };
    let send = h
        .query_all_by_role_and_label(Role::Button, labels::SEND)
        .next()
        .unwrap_or_else(|| panic!("{what}: no Send button\n{}", screen_text(h)));
    inside(send.rect(), "the Send button");
    let edit = h
        .query_all_by_role(Role::MultilineTextInput)
        .next()
        .unwrap_or_else(|| panic!("{what}: no message box"));
    // A long draft scrolls inside the box: its top shows, the rest scrolls.
    let top = edit.rect();
    inside(
        eframe::egui::Rect::from_min_size(top.min, eframe::egui::vec2(1.0, 1.0)),
        "the top of the message box",
    );
    let hint_node = h
        .query_all_by(|n| {
            n.label().is_some_and(|l| l.contains(hint))
                || n.value().is_some_and(|v| v.contains(hint))
        })
        .next()
        .unwrap_or_else(|| panic!("{what}: no hint line\n{}", screen_text(h)));
    inside(hint_node.rect(), "the hint line");
}

const SIZES: [(f32, f32); 2] = [(1280.0, 720.0), (900.0, 560.0)];

#[test]
fn a_long_chat_keeps_its_box_in_the_window() {
    let Some(home) = home() else { return };
    let mut st = home.service();
    let phone = paired(&mut st);
    let mut turns = vec![];
    for i in 0..4 {
        turns.push(turn(
            &format!("q{i}"),
            true,
            &format!("Question {i}?"),
            None,
            None,
        ));
        turns.push(turn(
            &format!("q{i}.a"),
            false,
            &long_text(8),
            Some(Ran::Pc),
            Some("Qwen 3.6 coding"),
        ));
    }
    phone
        .call(Call::ChatPut {
            chat_id: "long".into(),
            name: Some("Long chat".into()),
            turns,
            deleted: false,
        })
        .unwrap();
    for size in SIZES {
        let mut h = home.open_at(size.0, size.1);
        assert!(
            wait_for(&mut h, |h| has_button(h, "Chat: Long chat")),
            "{}",
            screen_text(&h)
        );
        click(&mut h, "Chat: Long chat");
        assert!(wait_for(&mut h, |h| shows(h, "Question 3?")
            || shows(h, "Paragraph 7")));
        for _ in 0..30 {
            h.step();
        }
        box_is_inside(&h, size, labels::HINT_CHAT, "a long chat");
        close(h);
    }
}

#[test]
fn an_empty_new_chat_keeps_its_box_in_the_window() {
    let Some(home) = home() else { return };
    for size in SIZES {
        let mut h = home.open_at(size.0, size.1);
        click(&mut h, labels::NEW_CHAT);
        for _ in 0..30 {
            h.step();
        }
        box_is_inside(&h, size, labels::HINT_CHAT, "a new chat");
        close(h);
    }
}

#[test]
fn a_long_task_keeps_its_box_in_the_window() {
    let Some(home) = home() else { return };
    let project = home.project.display().to_string();
    let store = mimir_core::store::Store::new(mimir_core::store::Store::default_dir()).unwrap();
    let mut t = mimir_core::task::Task::new(
        "1700000000-1-1".into(),
        "Plan the garden".into(),
        project,
        1_700_000_000,
    );
    t.status = mimir_core::task::Status::Done;
    for i in 0..4 {
        t.entries.push(Entry::You(format!("Question {i}?")));
        t.entries.push(Entry::Claude(long_text(8)));
    }
    store.save(&t).unwrap();
    for size in SIZES {
        let mut h = home.open_at(size.0, size.1);
        assert!(
            wait_for(&mut h, |h| has_button(h, "Plan the garden")
                || shows(h, "Plan the garden")),
            "{}",
            screen_text(&h)
        );
        let b = h
            .query_all_by(|n| {
                n.role() == Role::Button && n.label().is_some_and(|l| l.contains("Plan the garden"))
            })
            .next()
            .expect("no task row");
        b.click();
        for _ in 0..40 {
            h.step();
        }
        box_is_inside(&h, size, labels::HINT_TASK, "a long task");
        close(h);
    }
}

/// A long message typed or pasted into the box makes the box taller, never
/// pushes its Send button or hint out of the window.
#[test]
fn a_long_draft_keeps_the_send_button_in_the_window() {
    let Some(home) = home() else { return };
    let long = (0..60)
        .map(|i| format!("line {i} of a long pasted message"))
        .collect::<Vec<_>>()
        .join("\n");
    for size in SIZES {
        let mut h = home.open_at(size.0, size.1);
        click(&mut h, labels::NEW_CHAT);
        type_message(&mut h, &long);
        for _ in 0..10 {
            h.step();
        }
        box_is_inside(&h, size, labels::HINT_CHAT, "a long draft");
        close(h);
    }
}

/// The window as the owner opens it: straight into the newest task, on a
/// sharp screen (1.5 and 2 pixels to the point), with no click or key
/// afterwards. The whole box and the hint are in the window, and an old
/// stored note reads in today's plain words.
#[test]
fn a_task_opened_at_start_shows_its_whole_box_on_sharp_screens() {
    let Some(home) = home() else { return };
    let project = home.project.display().to_string();
    let store = mimir_core::store::Store::new(mimir_core::store::Store::default_dir()).unwrap();
    let mut t =
        mimir_core::task::Task::new("1700000000-1-1".into(), "hi".into(), project, 1_700_000_000);
    t.status = mimir_core::task::Status::Done;
    t.entries.push(Entry::You("hi".into()));
    t.entries.push(Entry::Note(
        "On qwen3.6:35b-a3b-coding, a free model on this PC (Settings \u{203a} Models \u{203a} What powers your tasks)."
            .into(),
    ));
    for i in 0..3 {
        t.entries.push(Entry::You(format!("Question {i}?")));
        t.entries.push(Entry::Claude(long_text(8)));
    }
    store.save(&t).unwrap();
    for ppp in [1.0, 1.5, 2.0] {
        let size = (1280.0, 830.0);
        let mut h = egui_kittest::Harness::builder()
            .with_size(eframe::egui::Vec2::new(size.0, size.1))
            .with_pixels_per_point(ppp)
            .with_max_steps(400)
            .allow_missing_glyphs()
            .with_accessibility_check(false)
            .build_eframe(|cc| station_ui::App::new(cc, "0.15", station_ui::public_key()));
        settle(&mut h);
        box_is_inside(
            &h,
            size,
            labels::HINT_TASK,
            &format!("a task at start, ppp {ppp}"),
        );
        assert!(
            !shows(&h, "Settings \u{203a} Models") && !shows(&h, "qwen3.6:35b"),
            "the old note shows raw\n{}",
            screen_text(&h)
        );
        close(h);
    }
}
