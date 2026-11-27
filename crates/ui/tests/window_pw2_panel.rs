//! PW2: the right panel is open by default and stays open (a person can
//! still hide it, and that choice is kept); "This PC" is always the first
//! card with the three meters; a task started from the phone gets the same
//! panel as one started on the PC.

mod common;
use common::*;
use mimir_core::proto::{Call, Event};
use mimir_core::store::Store;
use mimir_core::task::{Entry, Status, Task};
use mimir_link::client::{Client, generate_key};
use station_ui::labels;
use std::time::{Duration, Instant};

fn plain_home() -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.display().to_string();
    // No "details" choice: the panel is at its default.
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
    }));
    Some(home)
}

fn this_pc_words(h: &egui_kittest::Harness<'static, station_ui::App>) -> bool {
    ["THIS PC", "Processor", "PC memory (RAM)"]
        .iter()
        .all(|w| shows(h, w))
}

/// Where a piece of text sits from the top of the window.
fn top_of(h: &egui_kittest::Harness<'static, station_ui::App>, text: &str) -> f32 {
    use egui_kittest::kittest::Queryable;
    h.query_all_by(|n| {
        n.label().is_some_and(|l| l.contains(text)) || n.value().is_some_and(|v| v.contains(text))
    })
    .map(|n| n.rect().min.y)
    .reduce(f32::min)
    .unwrap_or_else(|| panic!("{text:?} is not on screen\n{}", screen_text(h)))
}

#[test]
fn the_panel_is_open_on_a_fresh_start_with_this_pc_at_the_top() {
    let Some(home) = plain_home() else { return };
    let mut h = home.open();
    for _ in 0..5 {
        h.step();
    }
    assert!(this_pc_words(&h), "{}", screen_text(&h));
    assert!(has_button(&h, labels::HIDE_PANEL), "{}", screen_text(&h));
    close(h);
}

#[test]
fn hiding_the_panel_is_kept_and_the_panel_comes_back_when_asked() {
    let Some(home) = plain_home() else { return };
    let mut h = home.open();
    assert!(wait_for(&mut h, |h| has_button(h, labels::HIDE_PANEL)));
    click(&mut h, labels::HIDE_PANEL);
    assert!(wait_for(&mut h, |h| !shows(h, "THIS PC")));
    close(h);
    // A new window on the same home: still hidden (the choice is kept).
    let mut h = home.open();
    for _ in 0..5 {
        h.step();
    }
    assert!(!shows(&h, "THIS PC"), "{}", screen_text(&h));
    click(&mut h, labels::SHOW_PANEL);
    assert!(wait_for(&mut h, this_pc_words));
    close(h);
}

#[test]
fn an_open_task_has_this_pc_first_then_its_own_cards() {
    let Some(home) = plain_home() else { return };
    let project = home.project.clone();
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(
        "t1".into(),
        "Sort the receipts".into(),
        project.display().to_string(),
        0,
    );
    t.entries = vec![
        Entry::You("Sort the receipts".into()),
        Entry::Claude("Done.".into()),
    ];
    t.status = Status::Done;
    store.save(&t).unwrap();
    home.tripwire();
    let mut h = home.open();
    let name = project.file_name().unwrap().to_string_lossy().to_string();
    let row = labels::task_row("Sort the receipts", &name);
    wait_for(&mut h, |h| shows(h, &row));
    click(&mut h, &row);
    assert!(wait_for(&mut h, |h| shows(h, "THIS TASK")));
    assert!(this_pc_words(&h), "{}", screen_text(&h));
    let (pc, task) = (top_of(&h, "THIS PC"), top_of(&h, "THIS TASK"));
    assert!(pc < task, "This PC ({pc}) is not above This task ({task})");
    // The three meters are the first thing in the panel.
    assert!(top_of(&h, "Processor") < task);
    assert!(top_of(&h, "PC memory (RAM)") < task);
    close(h);
}

/// A fake phone, paired and past its snapshot.
fn paired(st: &mut mimir_core::client::Station) -> Client {
    let info = st.call_wait(Call::LinkInfo).expect("no link");
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
    let code = st.call_wait(Call::PairCode).unwrap();
    let code = code.as_str().unwrap().to_string();
    p.pair_start(&code, "Fake phone").expect("code refused");
    assert!(service_until(st, |s| s.pairing.is_some()));
    let pending = st.pairing.as_ref().unwrap().0;
    st.call_wait(Call::ConfirmPair {
        pending,
        yes: true,
        person: Some(mimir_core::proto::WHOSE_MINE.into()),
    })
    .unwrap();
    p.pair_finish(Duration::from_secs(10)).expect("not paired");
    p.connect(&pc).expect("couldn't connect");
    p.call(Call::Hello {
        proto: mimir_core::proto::PROTO,
        app_version: "test".into(),
        client_name: "Fake phone".into(),
        env: vec![],
        features: ["talk", "chat_sync", "truth"]
            .iter()
            .map(|f| f.to_string())
            .collect(),
    })
    .unwrap();
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        if let Some(m) = p.next_event(Duration::from_millis(50))
            && matches!(m.event, Event::Ready)
        {
            break;
        }
        assert!(Instant::now() < until, "no snapshot after Hello");
    }
    p
}

/// The side panel set to Automatic (it opens while work runs, closes when
/// calm) shows for a task the phone started, as for one started here.
#[test]
fn the_automatic_panel_opens_for_a_task_started_from_the_phone() {
    let Some(home) = plain_home() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    home.hold_replies();
    let mut h = home.open();
    let mut st = home.service();
    let phone = paired(&mut st);
    // Hide it, then ask for Automatic: calm, so it stays closed.
    click(&mut h, labels::HIDE_PANEL);
    assert!(wait_for(&mut h, |h| has_button(h, "Automatic")));
    click(&mut h, "Automatic");
    for _ in 0..10 {
        h.step();
    }
    assert!(!shows(&h, "THIS PC"), "{}", screen_text(&h));
    phone
        .call(Call::NewTask {
            typed: false,
            files: vec![],
            ground_rules: None,
            project: home.project.display().to_string(),
            text: "Tidy up.".into(),
            pictures: vec![],
            mode: mimir_core::proto::Mode::Ask,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: None,
        })
        .unwrap();
    assert!(
        wait_for(&mut h, this_pc_words),
        "the panel did not open for a phone-started task\n{}",
        screen_text(&h)
    );
    home.let_through(1000);
    close(h);
}
