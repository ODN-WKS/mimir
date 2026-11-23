//! "task_reply_options": a paired phone may set a task's model and effort
//! (`SetModel`, `SetEffort`) just before its next message.

use mimir_proto::*;

#[test]
fn a_phone_may_set_a_tasks_model_and_effort() {
    let m = Call::SetModel {
        task: "t".into(),
        model: Some("opus".into()),
    };
    let e = Call::SetEffort {
        task: "t".into(),
        effort: Some("high".into()),
    };
    let clear = Call::SetEffort {
        task: "t".into(),
        effort: None,
    };
    assert!(m.phone_may() && e.phone_may() && clear.phone_may());
    for c in [m, e, clear] {
        let s = serde_json::to_string(&c).unwrap();
        assert_eq!(serde_json::from_str::<Call>(&s).unwrap(), c);
    }
}

#[test]
fn the_feature_is_listed() {
    assert!(FEATURES.contains(&"task_reply_options"));
}
