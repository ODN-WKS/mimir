//! The optional `typed` field: an older phone or PC keeps working both ways.

use mimir_proto::{Call, QueuedMessage};
use serde_json::{Value, json};

/// Every call that carries words, as an older app writes it.
fn old() -> Vec<Value> {
    vec![
        json!({"NewTask": {"project": "/p", "text": "hi", "mode": "Ask", "model": null,
            "effort": null, "separate_copy": false}}),
        json!({"Send": {"task": "t", "text": "hi"}}),
        json!({"Chat": {"msg_id": "m", "text": "hi"}}),
        json!({"Talk": {"chat_id": "c", "turn_id": "m", "turns": []}}),
        json!({"Queue": {"task": "t", "text": "hi", "files": []}}),
        json!({"NewIncognito": {"project": "/p", "text": "hi", "model": "ollama:x", "mode": "Ask"}}),
    ]
}

fn typed(c: &Call) -> bool {
    match c {
        Call::NewTask { typed, .. }
        | Call::Send { typed, .. }
        | Call::Chat { typed, .. }
        | Call::Talk { typed, .. }
        | Call::Queue { typed, .. }
        | Call::NewIncognito { typed, .. } => *typed,
        other => panic!("{other:?}"),
    }
}

#[test]
fn an_older_apps_words_are_not_typed_and_unmarked_calls_look_as_before() {
    for v in old() {
        let c: Call = serde_json::from_value(v.clone()).unwrap();
        assert!(!typed(&c), "{v}");
        let back = serde_json::to_value(&c).unwrap();
        let body = back.as_object().unwrap().values().next().unwrap();
        assert!(body.get("typed").is_none(), "{back}");
    }
}

#[test]
fn typed_is_carried_when_set() {
    for mut v in old() {
        let name = v.as_object().unwrap().keys().next().unwrap().clone();
        v[&name]["typed"] = json!(true);
        let c: Call = serde_json::from_value(v).unwrap();
        assert!(typed(&c));
        assert_eq!(serde_json::to_value(&c).unwrap()[&name]["typed"], true);
    }
}

/// An older PC reads a newer app's call: a field it doesn't know is skipped
/// (shown with a made-up one, since these types read every call the same way).
#[test]
fn a_field_this_pc_does_not_know_is_skipped() {
    for mut v in old() {
        let name = v.as_object().unwrap().keys().next().unwrap().clone();
        v[&name]["from_a_newer_app"] = json!(true);
        serde_json::from_value::<Call>(v).unwrap();
    }
}

#[test]
fn a_waiting_message_never_sends_the_services_own_note() {
    let q = QueuedMessage {
        text: "hi".into(),
        files: vec![],
        typed: true,
    };
    assert_eq!(
        serde_json::to_value(q).unwrap(),
        json!({"text": "hi", "files": []})
    );
}
