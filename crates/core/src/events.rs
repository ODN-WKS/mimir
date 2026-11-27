//! Reads the stream-json lines that `claude -p --output-format stream-json`
//! prints. Unknown or malformed lines never fail: they become
//! [`Event::Unknown`] so a newer Claude Code cannot break the window.

use serde_json::Value;

#[derive(Debug, Clone, PartialEq)]
pub struct Init {
    pub session_id: String,
    pub model: String,
    pub version: String,
    pub cwd: String,
    pub capabilities: Vec<String>,
    pub tools: Vec<String>,
    /// Slash commands and skills available in this session.
    pub slash_commands: Vec<String>,
    /// Connected MCP servers: (name, status).
    pub mcp_servers: Vec<(String, String)>,
    pub permission_mode: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Finished {
    pub session_id: String,
    pub subtype: String,
    pub is_error: bool,
    pub text: Option<String>,
    pub duration_ms: u64,
    pub cost_usd: f64,
    /// For example "completed" or "aborted_tools" (stopped by the owner).
    pub terminal_reason: String,
    /// The model's context window in tokens, when reported.
    pub context_window: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    Init(Init),
    /// Text Claude wrote to the owner.
    Text(String),
    /// The model Claude Code says wrote the message whose events follow
    /// (e.g. "claude-opus-5-5").
    Model(String),
    /// Claude Code took in a message from its input and echoed it back
    /// (`--replay-user-messages`): its text.
    Echo(String),
    /// A piece of text while Claude is still writing it
    /// (`--include-partial-messages`).
    TextDelta(String),
    /// Tokens of context in use after Claude's latest message.
    ContextUsed(u64),
    /// Claude Code summarised the conversation (`/compact` or automatically).
    Compacted {
        before: u64,
        after: u64,
    },
    ToolUse {
        id: String,
        name: String,
        input: Value,
    },
    ToolResult {
        id: String,
        is_error: bool,
        /// Short text form of the result.
        text: String,
        /// The structured `tool_use_result` when Claude Code sends one.
        detail: Value,
    },
    /// Weekly (seven day) use, 0.0 to 1.0, and when it resets (unix seconds).
    /// The 5-hour window, when Claude Code reports one.
    FiveHourUse {
        used: f64,
        resets_at: i64,
    },
    WeeklyUse {
        used: f64,
        resets_at: i64,
    },
    Denied {
        tool_use_id: String,
        tool_name: String,
    },
    Finished(Finished),
    /// Claude Code started work that goes on after its turn (a background
    /// command or helper), by its own id; it stays up until that ends.
    BackgroundStarted(String),
    /// That background work ended (Claude Code is told, and may answer on).
    BackgroundEnded(String),
    /// Anthropic refused the key Claude Code sent (Claude Code tries again
    /// for minutes before it gives up, so this comes long before the end).
    KeyRefused,
    /// Anything we do not use. Kept so it can be logged.
    Unknown(String),
}

fn s(v: &Value, key: &str) -> String {
    v.get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

fn strings(v: &Value, key: &str) -> Vec<String> {
    v.get(key)
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .filter_map(|x| x.as_str().map(str::to_string))
                .collect()
        })
        .unwrap_or_default()
}

/// The text inside a tool_result `content`, which is either a string or a
/// list of blocks.
fn content_text(c: &Value) -> String {
    match c {
        Value::String(t) => t.clone(),
        Value::Array(items) => items
            .iter()
            .filter_map(|i| i.get("text").and_then(Value::as_str))
            .collect::<Vec<_>>()
            .join("\n"),
        _ => String::new(),
    }
}

/// Turns one output line into zero or more events.
pub fn parse_line(line: &str) -> Vec<Event> {
    let line = line.trim();
    if line.is_empty() {
        return vec![];
    }
    let Ok(v) = serde_json::from_str::<Value>(line) else {
        return vec![Event::Unknown(line.to_string())];
    };
    let unknown = || vec![Event::Unknown(line.to_string())];
    // Messages from helpers Claude starts carry a parent id; the window
    // shows the main conversation only.
    let from_helper = v.get("parent_tool_use_id").is_some_and(|p| !p.is_null());
    match v.get("type").and_then(Value::as_str).unwrap_or_default() {
        "system" => match v.get("subtype").and_then(Value::as_str) {
            Some("init") => vec![Event::Init(Init {
                session_id: s(&v, "session_id"),
                model: s(&v, "model"),
                version: s(&v, "claude_code_version"),
                cwd: s(&v, "cwd"),
                capabilities: strings(&v, "capabilities"),
                tools: strings(&v, "tools"),
                slash_commands: strings(&v, "slash_commands"),
                mcp_servers: v
                    .get("mcp_servers")
                    .and_then(Value::as_array)
                    .map(|a| a.iter().map(|m| (s(m, "name"), s(m, "status"))).collect())
                    .unwrap_or_default(),
                permission_mode: s(&v, "permissionMode"),
            })],
            Some("compact_boundary") => {
                let n = |k: &str| {
                    v.pointer(&format!("/compact_metadata/{k}"))
                        .and_then(Value::as_u64)
                        .unwrap_or(0)
                };
                vec![Event::Compacted {
                    before: n("pre_tokens"),
                    after: n("post_tokens"),
                }]
            }
            Some("task_started") => vec![Event::BackgroundStarted(s(&v, "task_id"))],
            Some("task_notification") => vec![Event::BackgroundEnded(s(&v, "task_id"))],
            Some("permission_denied") => vec![Event::Denied {
                tool_use_id: s(&v, "tool_use_id"),
                tool_name: s(&v, "tool_name"),
            }],
            // As Claude Code 2.1.332 reports a refused key (recorded on
            // this PC): `error_status` 401, `error` "authentication_failed".
            Some("api_retry") if v.get("error_status").and_then(Value::as_u64) == Some(401) => {
                vec![Event::KeyRefused]
            }
            _ => unknown(),
        },
        "assistant" if !from_helper => {
            let blocks = v
                .pointer("/message/content")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            let mut out = vec![];
            // "<synthetic>": Claude Code's own words (an error), no model's.
            match v.pointer("/message/model").and_then(Value::as_str) {
                Some(m) if !m.is_empty() && !m.starts_with('<') => {
                    out.push(Event::Model(m.to_string()))
                }
                _ => {}
            }
            if let Some(u) = v.pointer("/message/usage") {
                let n = |k: &str| u.get(k).and_then(Value::as_u64).unwrap_or(0);
                let used = n("input_tokens")
                    + n("cache_creation_input_tokens")
                    + n("cache_read_input_tokens");
                if used > 0 {
                    out.push(Event::ContextUsed(used));
                }
            }
            for b in blocks {
                match b.get("type").and_then(Value::as_str) {
                    Some("text") => {
                        let t = s(&b, "text");
                        if !t.trim().is_empty() {
                            out.push(Event::Text(t));
                        }
                    }
                    Some("tool_use") => out.push(Event::ToolUse {
                        id: s(&b, "id"),
                        name: s(&b, "name"),
                        input: b.get("input").cloned().unwrap_or(Value::Null),
                    }),
                    _ => {}
                }
            }
            out
        }
        // A message from the input, echoed as Claude Code takes it in.
        "user"
            if !from_helper
                && v.get("isReplay").and_then(Value::as_bool) == Some(true)
                && v.get("isSynthetic").and_then(Value::as_bool) != Some(true) =>
        {
            vec![Event::Echo(content_text(
                v.pointer("/message/content").unwrap_or(&Value::Null),
            ))]
        }
        "user" if !from_helper => {
            let detail = v.get("tool_use_result").cloned().unwrap_or(Value::Null);
            let blocks = v
                .pointer("/message/content")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            blocks
                .iter()
                .filter(|b| b.get("type").and_then(Value::as_str) == Some("tool_result"))
                .map(|b| Event::ToolResult {
                    id: s(b, "tool_use_id"),
                    is_error: b.get("is_error").and_then(Value::as_bool).unwrap_or(false),
                    text: content_text(b.get("content").unwrap_or(&Value::Null)),
                    detail: detail.clone(),
                })
                .collect()
        }
        "assistant" | "user" => vec![],
        "stream_event" if !from_helper => {
            match v.pointer("/event/delta/type").and_then(Value::as_str) {
                Some("text_delta") => {
                    let t = v
                        .pointer("/event/delta/text")
                        .and_then(Value::as_str)
                        .unwrap_or_default();
                    vec![Event::TextDelta(t.to_string())]
                }
                _ => vec![],
            }
        }
        "stream_event" => vec![],
        "rate_limit_event" => {
            let window = |name: &str| {
                let w = v.pointer(&format!("/rate_limit_info/unifiedWindows/{name}"))?;
                let used = w.get("utilization").and_then(Value::as_f64)?;
                let resets = w.get("resetsAt").and_then(Value::as_i64).unwrap_or(0);
                Some((used, resets))
            };
            let mut out = vec![];
            if let Some((used, resets_at)) = window("seven_day") {
                out.push(Event::WeeklyUse { used, resets_at });
            }
            if let Some((used, resets_at)) = window("five_hour") {
                out.push(Event::FiveHourUse { used, resets_at });
            }
            if out.is_empty() { unknown() } else { out }
        }
        "result" => vec![Event::Finished(Finished {
            session_id: s(&v, "session_id"),
            subtype: s(&v, "subtype"),
            is_error: v.get("is_error").and_then(Value::as_bool).unwrap_or(false),
            text: v.get("result").and_then(Value::as_str).map(str::to_string),
            duration_ms: v.get("duration_ms").and_then(Value::as_u64).unwrap_or(0),
            cost_usd: v
                .get("total_cost_usd")
                .and_then(Value::as_f64)
                .unwrap_or(0.0),
            terminal_reason: s(&v, "terminal_reason"),
            context_window: v
                .get("modelUsage")
                .and_then(Value::as_object)
                .map(|m| {
                    m.values()
                        .filter_map(|x| x.get("contextWindow").and_then(Value::as_u64))
                        .max()
                        .unwrap_or(0)
                })
                .unwrap_or(0),
        })],
        _ => unknown(),
    }
}

/// The JSON line that sends a message to Claude Code's stdin. Not marked as typed:
/// see [`user_line`].
pub fn user_message(text: &str) -> String {
    user_line(text, &[], false)
}

pub use mimir_proto::Picture;

/// Reads a picture file; None for other kinds of file.
pub fn picture_from_file(path: &std::path::Path) -> Option<Picture> {
    let ext = path.extension()?.to_str()?.to_ascii_lowercase();
    if !matches!(ext.as_str(), "png" | "jpg" | "jpeg" | "gif" | "webp") {
        return None;
    }
    let bytes = std::fs::read(path).ok()?;
    // Claude accepts pictures up to about 5 MB.
    if bytes.len() > 5 * 1024 * 1024 {
        return None;
    }
    // Details taken out without touching the pixels (a PNG stays a PNG, an
    // animation stays one); a photo meant to be shown turned is redrawn. One
    // that can't be cleaned (a GIF) is not passed on as a picture: its path
    // goes through the file wiper instead.
    if let Some(clean) = crate::wipe::strip_image(&bytes) {
        let media_type = if bytes.starts_with(b"\x89PNG") {
            "image/png"
        } else if bytes.starts_with(&[0xFF, 0xD8]) {
            "image/jpeg"
        } else {
            "image/webp"
        };
        return Some(Picture {
            media_type: media_type.to_string(),
            base64: base64(&clean),
        });
    }
    crate::pictures::clean(&bytes)
}

/// Standard base64 with padding.
pub fn base64(bytes: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for c in bytes.chunks(3) {
        let n = (c[0] as u32) << 16
            | (*c.get(1).unwrap_or(&0) as u32) << 8
            | *c.get(2).unwrap_or(&0) as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if c.len() > 1 {
            T[(n >> 6) as usize & 63] as char
        } else {
            '='
        });
        out.push(if c.len() > 2 {
            T[n as usize & 63] as char
        } else {
            '='
        });
    }
    out
}

/// [`user_line`] for words nobody typed.
pub fn user_message_with(text: &str, pictures: &[Picture]) -> String {
    user_line(text, pictures, false)
}

/// The input line for a message. With pictures: content blocks, pictures first
/// (checked against Claude Code 2.1.325). `typed`: a person typed these words
/// into a message box, and Claude Code is told (`origin` human, as its SDK asks
/// of apps that forward typed words). Everything else carries no `origin`.
pub fn user_line(text: &str, pictures: &[Picture], typed: bool) -> String {
    let content: Value = if pictures.is_empty() {
        text.into()
    } else {
        let mut blocks: Vec<Value> = pictures
            .iter()
            .map(|p| {
                serde_json::json!({
                    "type": "image",
                    "source": { "type": "base64", "media_type": p.media_type, "data": p.base64 }
                })
            })
            .collect();
        blocks.push(serde_json::json!({ "type": "text", "text": text }));
        blocks.into()
    };
    let mut line = serde_json::json!({
        "type": "user",
        "message": { "role": "user", "content": content }
    });
    if typed {
        line["origin"] = serde_json::json!({ "kind": "human" });
    }
    line.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn junk_and_new_types_are_ignored() {
        assert!(parse_line("").is_empty());
        assert!(matches!(parse_line("not json")[0], Event::Unknown(_)));
        // P26: a refused key is told apart from other retries.
        assert_eq!(
            parse_line(
                r#"{"type":"system","subtype":"api_retry","error":"authentication_failed","attempt":3,"max_retries":10,"error_status":401}"#
            ),
            vec![Event::KeyRefused]
        );
        assert!(matches!(
            parse_line(
                r#"{"type":"system","subtype":"api_retry","error":"overloaded","error_status":529}"#
            )[0],
            Event::Unknown(_)
        ));
        assert!(matches!(
            parse_line(r#"{"type":"brand_new_thing","x":1}"#)[0],
            Event::Unknown(_)
        ));
        assert!(matches!(
            parse_line(r#"{"type":"system","subtype":"init"}"#)[0],
            Event::Init(_)
        ));
    }

    #[test]
    fn helper_messages_are_skipped() {
        let l = r#"{"type":"assistant","parent_tool_use_id":"t1","message":{"content":[{"type":"text","text":"hi"}]}}"#;
        assert!(parse_line(l).is_empty());
    }

    #[test]
    fn base64_matches_the_standard() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
        let v: Value = serde_json::from_str(&user_message_with(
            "what is it",
            &[Picture {
                media_type: "image/png".into(),
                base64: "AAAA".into(),
            }],
        ))
        .unwrap();
        assert_eq!(v["message"]["content"][0]["source"]["data"], "AAAA");
        assert_eq!(v["message"]["content"][1]["text"], "what is it");
    }

    #[test]
    fn user_message_shape() {
        let v: Value = serde_json::from_str(&user_message("hi")).unwrap();
        assert_eq!(v["type"], "user");
        assert_eq!(v["message"]["content"], "hi");
    }

    /// The exact input lines: only typed words carry `origin`.
    #[test]
    fn only_a_typed_line_carries_origin() {
        assert_eq!(
            user_line("hi", &[], true),
            r#"{"message":{"content":"hi","role":"user"},"origin":{"kind":"human"},"type":"user"}"#
        );
        assert_eq!(
            user_line("hi", &[], false),
            r#"{"message":{"content":"hi","role":"user"},"type":"user"}"#
        );
        assert_eq!(user_message("hi"), user_line("hi", &[], false));
        let p = [Picture {
            media_type: "image/png".into(),
            base64: "AAAA".into(),
        }];
        let blocks = r#"[{"source":{"data":"AAAA","media_type":"image/png","type":"base64"},"type":"image"},{"text":"hi","type":"text"}]"#;
        assert_eq!(
            user_line("hi", &p, true),
            format!(
                r#"{{"message":{{"content":{blocks},"role":"user"}},"origin":{{"kind":"human"}},"type":"user"}}"#
            )
        );
        assert_eq!(
            user_message_with("hi", &p),
            format!(r#"{{"message":{{"content":{blocks},"role":"user"}},"type":"user"}}"#)
        );
    }

    #[test]
    fn five_hour_and_weekly_limits() {
        let line = r#"{"type":"rate_limit_event","rate_limit_info":{"unifiedWindows":{"seven_day":{"utilization":0.06,"resetsAt":100},"five_hour":{"utilization":0.4,"resetsAt":50}}}}"#;
        let evs = parse_line(line);
        assert!(evs.contains(&Event::WeeklyUse {
            used: 0.06,
            resets_at: 100
        }));
        assert!(evs.contains(&Event::FiveHourUse {
            used: 0.4,
            resets_at: 50
        }));
    }
}
