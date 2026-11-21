//! Labs, the "don't touch" notice: its two events cross the link unchanged,
//! go to phones, and are never replayed to a phone that connects later.

use mimir_proto::*;

#[test]
fn the_notice_events_round_trip_and_reach_phones_only_live() {
    let on = Event::TestNotice {
        name: "Breaker".into(),
        what: "Break test".into(),
        until: "7:15 PM".into(),
    };
    for (event, json) in [
        (
            on,
            r#"{"seq":5,"event":{"TestNotice":{"name":"Breaker","what":"Break test","until":"7:15 PM"}}}"#,
        ),
        (Event::TestNoticeOff, r#"{"seq":5,"event":"TestNoticeOff"}"#),
    ] {
        let msg = EventMsg { seq: 5, event };
        let text = serde_json::to_string(&msg).unwrap();
        assert_eq!(text, json);
        assert_eq!(serde_json::from_str::<EventMsg>(&text).unwrap(), msg);
        assert!(msg.event.for_phone());
        assert!(!msg.event.durable());
    }
}

#[test]
fn a_phone_has_no_call_that_sends_the_notice() {
    // The notice is an event from the PC; nothing in `Call` carries it.
    assert!(
        serde_json::from_str::<Call>(r#"{"TestNotice":{"name":"x","what":"","until":""}}"#)
            .is_err()
    );
    assert!(serde_json::from_str::<Call>(r#""TestNoticeOff""#).is_err());
}
