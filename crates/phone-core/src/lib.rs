//! The phone app's Rust side: the link to the PC, over JNI (Kotlin class
//! `io.github.odnwks.mimir.link.Native`).
//!
//! A handle is a raw pointer to a boxed [`Client`], passed to Kotlin as a
//! `long`. Calls and events cross as JSON, in `mimir-proto`'s own serde
//! form, so the two sides read the same shapes (a Kotlin test decodes the
//! examples `crates/proto` writes).

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jstring};
use mimir_link::client::Client;
use mimir_proto::Call;
use std::time::Duration;

fn string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

fn jstr(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// `h` must be 0 or a live handle from `start`.
unsafe fn client<'a>(h: jlong) -> Option<&'a Client> {
    unsafe { (h as *const Client).as_ref() }
}

fn err(e: impl std::fmt::Display) -> String {
    serde_json::json!({ "error": e.to_string() }).to_string()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_generateKey<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
) -> jbyteArray {
    env.byte_array_from_slice(&mimir_link::client::generate_key())
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// A client for this phone's key; 0 if the network side can't open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_start<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    key: JByteArray<'l>,
    relays: jboolean,
) -> jlong {
    let Ok(bytes) = env.convert_byte_array(&key) else {
        return 0;
    };
    let Ok(key) = <[u8; 32]>::try_from(bytes.as_slice()) else {
        return 0;
    };
    match Client::new(key, relays != 0) {
        Ok(c) => Box::into_raw(Box::new(c)) as jlong,
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_stop<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) {
    if h != 0 {
        // SAFETY: `h` came from `start` and Kotlin drops it once.
        drop(unsafe { Box::from_raw(h as *mut Client) });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_id<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) -> jstring {
    let id = unsafe { client(h) }.map(|c| c.id()).unwrap_or_default();
    jstr(&mut env, &id)
}

/// Where the PC listens on the home network ("10.0.0.5:12345"), for a
/// direct connection without the relays (checks).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_addHint<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    addr: JString<'l>,
) {
    let a = string(&mut env, &addr);
    if let (Some(c), Ok(a)) = (unsafe { client(h) }, a.parse()) {
        c.add_hint(a);
    }
}

/// `{"words": [..4]}` or `{"error": "plain words"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_pairStart<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    code: JString<'l>,
    name: JString<'l>,
) -> jstring {
    let code = string(&mut env, &code);
    let name = string(&mut env, &name);
    let out = match unsafe { client(h) } {
        None => err("not started"),
        Some(c) => match c.pair_start(&code, &name) {
            Ok(words) => serde_json::json!({ "words": words }).to_string(),
            Err(e) => err(e),
        },
    };
    jstr(&mut env, &out)
}

/// `{"pc_id": .., "pc_name": ..}` once confirmed on the PC, or
/// `{"error": ..}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_pairFinish<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    code: JString<'l>,
    timeout_ms: jlong,
) -> jstring {
    let code = string(&mut env, &code);
    let out = match unsafe { client(h) } {
        None => err("not started"),
        Some(c) => match c.pair_finish(Duration::from_millis(timeout_ms.max(0) as u64)) {
            Ok(pc_name) => {
                let pc_id = mimir_link::Code::parse(&code)
                    .map(|c| c.pc_id)
                    .unwrap_or_default();
                serde_json::json!({ "pc_id": pc_id, "pc_name": pc_name }).to_string()
            }
            Err(e) => err(e),
        },
    };
    jstr(&mut env, &out)
}

/// "" once connected, or why not.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_connect<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    pc_id: JString<'l>,
) -> jstring {
    let id = string(&mut env, &pc_id);
    let out = match unsafe { client(h) } {
        None => "not started".to_string(),
        Some(c) => c.connect(&id).err().unwrap_or_default(),
    };
    jstr(&mut env, &out)
}

/// Sends one call (`mimir-proto`'s `Call` as JSON); its number, or -1.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_call<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    json: JString<'l>,
) -> jlong {
    let json = string(&mut env, &json);
    let (Some(c), Ok(call)) = (unsafe { client(h) }, serde_json::from_str::<Call>(&json)) else {
        return -1;
    };
    c.call(call).map(|id| id as jlong).unwrap_or(-1)
}

/// The next event as JSON (`EventMsg`), waiting up to `timeout_ms`; null
/// if none came.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_nextEvent<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    timeout_ms: jlong,
) -> jstring {
    let Some(c) = (unsafe { client(h) }) else {
        return std::ptr::null_mut();
    };
    match c.next_event(Duration::from_millis(timeout_ms.max(0) as u64)) {
        Some(e) => match serde_json::to_string(&e) {
            Ok(s) => jstr(&mut env, &s),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_connected<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) -> jboolean {
    unsafe { client(h) }.is_some_and(|c| c.connected()) as jboolean
}

/// The phone's network changed, or the app came back to the front.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_networkChanged<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) {
    if let Some(c) = unsafe { client(h) } {
        c.network_changed();
    }
}

/// Why the last session ended ("not paired" once the PC removed this
/// phone), or "".
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_endedBecause<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) -> jstring {
    let why = unsafe { client(h) }
        .and_then(|c| c.ended_because())
        .unwrap_or_default();
    jstr(&mut env, &why)
}

// ---- Tor, for a Whisper with internet (see `tor.rs`).

pub mod tor;

/// # Safety
/// `h` must be 0 or a live handle from `torNew`.
unsafe fn tor_ref<'a>(h: jlong) -> Option<&'a std::sync::Arc<tor::Tor>> {
    unsafe { (h as *const std::sync::Arc<tor::Tor>).as_ref() }
}

/// A Tor client (not connected yet), or 0 with nothing started.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_torNew<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    state: JString<'l>,
    cache: JString<'l>,
) -> jlong {
    let (state, cache) = (string(&mut env, &state), string(&mut env, &cache));
    match tor::Tor::new(&state, &cache) {
        Ok(t) => Box::into_raw(Box::new(std::sync::Arc::new(t))) as jlong,
        Err(_) => 0,
    }
}

/// Connects to Tor; "" when it's ready, else why not. Waits (never on the main thread).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_torConnect<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    timeout_ms: jlong,
) -> jstring {
    // SAFETY: `h` is 0 or a live handle from `torNew`.
    let Some(t) = (unsafe { tor_ref(h) }).cloned() else {
        return jstr(&mut env, "Tor isn't running");
    };
    let r = t.bootstrap(Duration::from_millis(timeout_ms.max(0) as u64));
    jstr(&mut env, &r.err().unwrap_or_default())
}

/// How far connecting to Tor has got, in percent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_torProgress<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) -> jlong {
    // SAFETY: `h` is 0 or a live handle from `torNew`.
    unsafe { tor_ref(h) }.map_or(0, |t| (t.progress() * 100.0) as jlong)
}

/// Reads an HTTPS address through Tor: `{"status","location","type","body"}` or `{"error"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_torGet<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    url: JString<'l>,
    headers_json: JString<'l>,
    timeout_ms: jlong,
    max_bytes: jlong,
) -> jstring {
    let url = string(&mut env, &url);
    let headers: Vec<(String, String)> = serde_json::from_str::<
        serde_json::Map<String, serde_json::Value>,
    >(&string(&mut env, &headers_json))
    .unwrap_or_default()
    .into_iter()
    .filter_map(|(k, v)| v.as_str().map(|v| (k, v.to_string())))
    .collect();
    // SAFETY: `h` is 0 or a live handle from `torNew`.
    let Some(t) = (unsafe { tor_ref(h) }).cloned() else {
        return jstr(&mut env, &err("Tor isn't running"));
    };
    let v = t.get(
        &url,
        &headers,
        Duration::from_millis(timeout_ms.max(0) as u64),
        max_bytes.max(0) as usize,
    );
    jstr(&mut env, &v.to_string())
}

/// Stops whatever is waiting on Tor (Cancel).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_torCancel<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) {
    // SAFETY: `h` is 0 or a live handle from `torNew`.
    if let Some(t) = unsafe { tor_ref(h) } {
        t.cancel();
    }
}

/// Stops Tor (the Whisper ended).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_odnwks_mimir_link_Native_torStop<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) {
    if h != 0 {
        // SAFETY: `h` came from `torNew` and Kotlin drops it once.
        let t = unsafe { Box::from_raw(h as *mut std::sync::Arc<tor::Tor>) };
        t.cancel();
        // The runtime shuts down on another thread: a JNI call never waits for Tor to wind down.
        std::thread::spawn(move || drop(t));
    }
}
