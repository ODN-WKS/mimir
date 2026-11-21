//! The phone side of the link, with blocking calls for the phone core
//! (and for the checks' fake phone on the PC).

use crate::*;
use iroh::endpoint::{Connection, RecvStream, SendStream};
use iroh::{Endpoint, EndpointAddr, EndpointId, SecretKey};
use mimir_proto::{Call, CallMsg, EventMsg};
use std::collections::VecDeque;
use std::net::SocketAddr;
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

/// A new phone key (kept by the phone, in its Keystore-protected store).
pub fn generate_key() -> [u8; 32] {
    SecretKey::generate().to_bytes()
}

struct Pairing {
    conn: Connection,
    #[allow(dead_code)]
    send: SendStream,
    recv: RecvStream,
}

struct Session {
    conn: Connection,
    calls: tokio::sync::mpsc::UnboundedSender<CallMsg>,
    closed: Arc<AtomicBool>,
}

type Queue = Arc<(Mutex<VecDeque<EventMsg>>, Condvar)>;

pub struct Client {
    rt: tokio::runtime::Runtime,
    endpoint: Endpoint,
    hints: Mutex<Vec<SocketAddr>>,
    pairing: Mutex<Option<Pairing>>,
    session: Mutex<Option<Session>>,
    events: Queue,
    next_call: AtomicU64,
    /// Why the last session ended ("not paired" after removal).
    ended: Arc<Mutex<Option<String>>>,
    /// Woken when the phone's network changes (see [`Client::network_changed`]).
    net: Arc<tokio::sync::Notify>,
}

impl Client {
    /// `relays`: use n0's relays and address lookup (off for checks that
    /// connect over loopback).
    pub fn new(secret: [u8; 32], relays: bool) -> Result<Client, String> {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|e| e.to_string())?;
        let builder = if relays {
            Endpoint::builder(iroh::endpoint::presets::N0)
        } else {
            Endpoint::builder(iroh::endpoint::presets::N0DisableRelay)
        };
        // A dropped network is noticed within about 12 s instead of 30 s, so the phone shows the
        // PC as away (and starts reconnecting) soon after the link goes quiet. Keep-alives every
        // 5 s keep a healthy link from timing out.
        let transport = iroh::endpoint::QuicTransportConfig::builder()
            .keep_alive_interval(std::time::Duration::from_secs(4))
            .max_idle_timeout(std::time::Duration::from_secs(12).try_into().ok())
            .build();
        let endpoint = rt
            .block_on(
                builder
                    .secret_key(SecretKey::from_bytes(&secret))
                    .transport_config(transport)
                    .bind(),
            )
            .map_err(|e| format!("The phone's network side can't open: {e}"))?;
        Ok(Client {
            rt,
            endpoint,
            hints: Mutex::new(vec![]),
            pairing: Mutex::new(None),
            session: Mutex::new(None),
            events: Arc::new((Mutex::new(VecDeque::new()), Condvar::new())),
            next_call: AtomicU64::new(1),
            ended: Arc::new(Mutex::new(None)),
            net: Arc::new(tokio::sync::Notify::new()),
        })
    }

    /// This phone's link id (its public key).
    pub fn id(&self) -> String {
        self.endpoint.id().to_string()
    }

    /// Tells the client where the PC listens, instead of looking it up
    /// (checks over loopback, and the same home network).
    pub fn add_hint(&self, addr: SocketAddr) {
        self.hints.lock().unwrap().push(addr);
    }

    fn addr(&self, pc_id: &str) -> Result<EndpointAddr, String> {
        let id = EndpointId::from_str(pc_id).map_err(|_| "The PC's id isn't valid.".to_string())?;
        let mut a = EndpointAddr::new(id);
        for h in self.hints.lock().unwrap().iter() {
            a = a.with_ip_addr(*h);
        }
        Ok(a)
    }

    /// First half of pairing: sends the proof from the code; returns the
    /// four words to compare with the PC's screen.
    pub fn pair_start(&self, code: &str, phone_name: &str) -> Result<Vec<String>, String> {
        let code = Code::parse(code)?;
        let addr = self.addr(&code.pc_id)?;
        let hmac = pair_hmac(&code.secret, self.endpoint.id().as_bytes());
        let ep = self.endpoint.clone();
        let name = phone_name.to_string();
        let net = self.net.clone();
        let (pairing, reply) = self.rt.block_on(async move {
            let conn = reach(&ep, addr, ALPN_PAIR, &net, PAIR_BUDGET).await?;
            let (mut send, mut recv) = conn.open_bi().await.map_err(|e| e.to_string())?;
            write_json(&mut send, &PairRequest { name, hmac })
                .await
                .map_err(|e| e.to_string())?;
            let reply = tokio::time::timeout(
                Duration::from_secs(30),
                read_json::<_, PairReply>(&mut recv),
            )
            .await
            .map_err(|_| "The PC didn't answer.".to_string())?
            .map_err(|e| e.to_string())?
            .ok_or_else(|| "The PC closed the pairing.".to_string())?;
            Ok::<_, String>((Pairing { conn, send, recv }, reply))
        })?;
        match reply {
            PairReply::Words { words } => {
                *self.pairing.lock().unwrap() = Some(pairing);
                Ok(words)
            }
            PairReply::Refused { why } => {
                pairing.conn.close(0u32.into(), b"done");
                Err(why)
            }
            PairReply::Paired { .. } => Err("The PC answered out of order.".into()),
        }
    }

    /// Second half: waits for the owner to confirm on the PC. `Ok` with
    /// the PC's name once paired.
    pub fn pair_finish(&self, timeout: Duration) -> Result<String, String> {
        let Some(mut p) = self.pairing.lock().unwrap().take() else {
            return Err("No pairing is in progress.".into());
        };
        let r = self.rt.block_on(async {
            tokio::time::timeout(timeout, read_json::<_, PairReply>(&mut p.recv)).await
        });
        p.conn.close(0u32.into(), b"done");
        match r {
            Ok(Ok(Some(PairReply::Paired { pc_name }))) => Ok(pc_name),
            Ok(Ok(Some(PairReply::Refused { why }))) => Err(why),
            Ok(Ok(_)) | Ok(Err(_)) => Err("The PC closed the pairing.".into()),
            Err(_) => Err("Nobody confirmed on the PC in time.".into()),
        }
    }

    /// Opens the session with a paired PC. Events then queue up for
    /// [`Client::next_event`].
    pub fn connect(&self, pc_id: &str) -> Result<(), String> {
        self.disconnect();
        let addr = self.addr(pc_id)?;
        let ep = self.endpoint.clone();
        let events = self.events.clone();
        let ended = self.ended.clone();
        *ended.lock().unwrap() = None;
        let closed = Arc::new(AtomicBool::new(false));
        let cl = closed.clone();
        let net = self.net.clone();
        let (conn, calls) = self.rt.block_on(async move {
            let conn = reach(&ep, addr, ALPN_PHONE, &net, CONNECT_BUDGET).await?;
            // A stream is only seen by the PC once something is written. Both
            // steps have a time limit: a connection that came up on a path
            // that just went away must not hold the phone in "connecting".
            let (mut send, mut recv) = tokio::time::timeout(OPEN_TIMEOUT, async {
                let (mut send, recv) = conn.open_bi().await.map_err(|e| e.to_string())?;
                write_json(&mut send, &Greeting { link: 1 })
                    .await
                    .map_err(|e| e.to_string())?;
                Ok::<_, String>((send, recv))
            })
            .await
            .map_err(|_| "The PC didn't answer in time.".to_string())??;
            let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<CallMsg>();
            tokio::spawn(async move {
                while let Some(c) = rx.recv().await {
                    if write_json(&mut send, &c).await.is_err() {
                        break;
                    }
                }
            });
            let c2 = conn.clone();
            tokio::spawn(async move {
                while let Ok(Some(e)) = read_json::<_, EventMsg>(&mut recv).await {
                    let (q, cv) = &*events;
                    q.lock().unwrap().push_back(e);
                    cv.notify_all();
                }
                let why = match c2.close_reason() {
                    Some(iroh::endpoint::ConnectionError::ApplicationClosed(c)) => {
                        String::from_utf8_lossy(&c.reason).into_owned()
                    }
                    Some(e) => e.to_string(),
                    None => "closed".into(),
                };
                *ended.lock().unwrap() = Some(why);
                cl.store(true, Ordering::SeqCst);
                let (_, cv) = &*events;
                cv.notify_all();
            });
            Ok::<_, String>((conn, tx))
        })?;
        *self.session.lock().unwrap() = Some(Session {
            conn,
            calls,
            closed,
        });
        Ok(())
    }

    /// Sends a call; its number (the `Reply` carries it).
    pub fn call(&self, call: Call) -> Result<u64, String> {
        let s = self.session.lock().unwrap();
        let Some(s) = s.as_ref() else {
            return Err("Not connected to the PC.".into());
        };
        if s.closed.load(Ordering::SeqCst) {
            return Err("Not connected to the PC.".into());
        }
        let call_id = self.next_call.fetch_add(1, Ordering::Relaxed);
        s.calls
            .send(CallMsg { call_id, call })
            .map_err(|_| "Not connected to the PC.".to_string())?;
        Ok(call_id)
    }

    /// The next event, waiting up to `timeout`.
    pub fn next_event(&self, timeout: Duration) -> Option<EventMsg> {
        let (q, cv) = &*self.events;
        let mut g = q.lock().unwrap();
        if g.is_empty() {
            let closed = self
                .session
                .lock()
                .unwrap()
                .as_ref()
                .is_none_or(|s| s.closed.load(Ordering::SeqCst));
            if closed {
                return None;
            }
            g = cv.wait_timeout(g, timeout).unwrap().0;
        }
        g.pop_front()
    }

    /// The session is open.
    pub fn connected(&self) -> bool {
        self.session
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|s| !s.closed.load(Ordering::SeqCst))
    }

    /// Why the last session ended ("not paired" once the PC removed this
    /// phone).
    pub fn ended_because(&self) -> Option<String> {
        self.ended.lock().unwrap().clone()
    }

    /// Tells the link the phone's network may have changed (Wi-Fi to mobile
    /// data, back from airplane mode, the app back in front after Android
    /// cut its network). iroh can't see this on Android by itself, and
    /// without it the link keeps using paths that no longer work.
    pub fn network_changed(&self) {
        let ep = self.endpoint.clone();
        let net = self.net.clone();
        self.rt.spawn(async move {
            ep.network_change().await;
            // A connect under way starts again on the new network.
            net.notify_waiters();
        });
    }

    pub fn disconnect(&self) {
        if let Some(s) = self.session.lock().unwrap().take() {
            s.conn.close(0u32.into(), b"bye");
        }
    }
}

/// The longest one connect to a paired PC may take, all tries together
/// (network changes included): the phone's link loop tries again at once
/// after a network change, so a stuck connect must end soon. The phone sat
/// on "connecting to the PC" for 233 s on mobile data when every network
/// change gave the connect a fresh 30 s.
pub const CONNECT_BUDGET: Duration = Duration::from_secs(10);
/// Pairing waits longer: the PC may have only just shown its code.
pub const PAIR_BUDGET: Duration = Duration::from_secs(30);
/// The longest one try may take, so a stuck one doesn't hold up the next.
pub const CONNECT_TRY: Duration = Duration::from_secs(5);
/// Opening the session's stream once connected.
pub const OPEN_TIMEOUT: Duration = Duration::from_secs(4);

/// Connects, trying again until `budget` runs out: a PC that just came
/// online may not be findable through the relays for a few seconds. A
/// network change (`net`) drops the try under way and starts a fresh one at
/// once (the old one was going out on a path that's gone), but never moves
/// the end of the budget.
async fn reach(
    ep: &Endpoint,
    addr: EndpointAddr,
    alpn: &[u8],
    net: &tokio::sync::Notify,
    budget: Duration,
) -> Result<Connection, String> {
    let until = tokio::time::Instant::now() + budget;
    let mut last: Option<String> = None;
    loop {
        let left = until.saturating_duration_since(tokio::time::Instant::now());
        if left.is_zero() {
            return Err(match last {
                Some(e) => format!("The PC can't be reached: {e}"),
                None => "The PC didn't answer. Is it on and online?".into(),
            });
        }
        let attempt = tokio::time::timeout(left.min(CONNECT_TRY), ep.connect(addr.clone(), alpn));
        tokio::select! {
            r = attempt => match r {
                Ok(Ok(c)) => return Ok(c),
                Err(_) => continue,
                Ok(Err(e)) => {
                    last = Some(e.to_string());
                    let pause = Duration::from_secs(1)
                        .min(until.saturating_duration_since(tokio::time::Instant::now()));
                    tokio::select! {
                        _ = tokio::time::sleep(pause) => {}
                        _ = net.notified() => {}
                    }
                }
            },
            _ = net.notified() => {}
        }
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        self.disconnect();
        let ep = self.endpoint.clone();
        self.rt.block_on(async move {
            let _ = tokio::time::timeout(Duration::from_secs(2), ep.close()).await;
        });
    }
}
