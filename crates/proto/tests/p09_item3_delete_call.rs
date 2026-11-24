//! 0.09 item 3 (P09A): a paired phone may delete a task.

use mimir_proto::*;

#[test]
fn p09_item3_a_phone_may_delete_a_task() {
    let c = Call::DeleteTask { task: "t-1".into() };
    assert!(c.phone_may());
    let s = serde_json::to_string(&c).unwrap();
    assert_eq!(s, r#"{"DeleteTask":{"task":"t-1"}}"#);
    assert_eq!(serde_json::from_str::<Call>(&s).unwrap(), c);
}
