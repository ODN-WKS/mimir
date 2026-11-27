//! "use opus 5.5 high" typed in a task or chat changes the model and effort
//! (it is never sent to the AI), and new work with no pick runs on the PC's
//! default for new work: Auto · capable at High unless changed.

mod common;
use common::*;
use mimir_core::client::Station;
use mimir_core::proto::{Call, ChatTurn, Filed};
use mimir_core::task::{Entry, Mode, Task};

fn task<'a>(s: &'a Station, id: &str) -> Option<&'a Task> {
    s.tasks.iter().find(|v| v.task.id == id).map(|v| &v.task)
}

fn until(st: &mut Station, what: &str, done: impl FnMut(&Station) -> bool) {
    assert!(service_until(st, done), "timed out: {what}");
}

fn new_task(home: &TestHome, model: Option<&str>, effort: Option<&str>) -> Call {
    Call::NewTask {
        typed: true,
        project: home.project.display().to_string(),
        text: "Tidy the notes".into(),
        pictures: vec![],
        files: vec![],
        mode: Mode::Ask,
        model: model.map(Into::into),
        effort: effort.map(Into::into),
        separate_copy: false,
        msg_id: None,
        ground_rules: None,
    }
}

fn start(st: &mut Station, call: Call) -> String {
    st.call_wait(call).unwrap().as_str().unwrap().to_string()
}

#[test]
fn new_work_with_no_pick_runs_on_the_pcs_default_and_a_pick_wins() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let a = start(&mut st, new_task(&home, None, None));
    let b = start(
        &mut st,
        new_task(&home, Some("claude-haiku-5-5"), Some("low")),
    );
    until(&mut st, "both tasks", |s| {
        [&a, &b].iter().all(|id| task(s, id).is_some())
    });
    let t = &task(&st, &a).unwrap();
    assert_eq!(t.model.as_deref(), Some("auto-capable"));
    assert_eq!(t.effort.as_deref(), Some("high"));
    let t = &task(&st, &b).unwrap();
    assert_eq!(t.model.as_deref(), Some("claude-haiku-5-5"));
    assert_eq!(t.effort.as_deref(), Some("low"));
    // Changed in Settings, from either side.
    st.call_wait(Call::SetEngines {
        chats: None,
        project: None,
        chat_model: None,
        default_model: Some("claude-sonnet-5-5".into()),
        default_effort: Some(String::new()),
    })
    .unwrap();
    let m = st.call_wait(Call::Models).unwrap();
    assert_eq!(m["default_model"], "claude-sonnet-5-5");
    assert_eq!(m["default_effort"], "");
    let c = start(&mut st, new_task(&home, None, None));
    until(&mut st, "the third task", |s| task(s, &c).is_some());
    let t = &task(&st, &c).unwrap();
    assert_eq!(t.model.as_deref(), Some("claude-sonnet-5-5"));
    assert_eq!(t.effort, None);
}

#[test]
fn typed_words_change_the_task_and_are_not_sent_to_the_ai() {
    let Some(home) = TestHome::new() else { return };
    home.play("run-1.jsonl", "live.jsonl");
    let mut st = home.service();
    let id = start(&mut st, new_task(&home, None, None));
    until(&mut st, "the task", |s| task(s, &id).is_some());
    st.call_wait(Call::Send {
        task: id.clone(),
        text: "use opus 5.5 max".into(),
        pictures: vec![],
        files: vec![],
        msg_id: Some("s-1".into()),
        typed: true,
    })
    .unwrap();
    until(&mut st, "the pick shows", |s| {
        task(s, &id).is_some_and(|l| {
            l.effort.as_deref() == Some("max")
                && l.entries.iter().any(
                    |e| matches!(e, Entry::Note(n) if n.starts_with("Now using Opus 5.5 at Max effort")),
                )
        })
    });
    let t = &task(&st, &id).unwrap();
    assert_eq!(t.model.as_deref(), Some("claude-opus-5-5"));
    assert!(
        t.entries
            .iter()
            .any(|e| matches!(e, Entry::You(x) if x == "use opus 5.5 max")),
        "the person's words stay in the conversation"
    );
    // From the general chat while it is in this task.
    let talk = |id_: &str, text: &str, task: Option<String>| Call::Talk {
        chat_id: "c-1".into(),
        turn_id: id_.into(),
        turns: vec![
            serde_json::from_value::<ChatTurn>(serde_json::json!({
                "id": id_, "user": true, "text": text, "at": 1, "version": 1, "seq": 0
            }))
            .unwrap(),
        ],
        lookup: None,
        sources: vec![],
        effort: None,
        ground_rules: None,
        task,
        chat_only: false,
        typed: true,
        again: false,
    };
    let f: Filed = serde_json::from_value(
        st.call_wait(talk("t-1", "use sonnet low", Some(id.clone())))
            .unwrap(),
    )
    .unwrap();
    assert!(matches!(f, Filed::SameTask { .. }), "{f:?}");
    until(&mut st, "the second pick", |s| {
        task(s, &id).is_some_and(|l| l.model.as_deref() == Some("claude-sonnet-5-5"))
    });
    // In a plain chat: one line saying where the picker is.
    let f: Filed = serde_json::from_value(
        st.call_wait(talk("t-2", "use opus 5.5 high", None))
            .unwrap(),
    )
    .unwrap();
    let Filed::Status { reply } = f else {
        panic!("{f:?}")
    };
    assert_eq!(
        reply,
        vec![mimir_core::model_words::CHAT_PICKER.to_string()]
    );
}
