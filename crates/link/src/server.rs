//! The PC side of the link: pairing and paired phones' sessions.

use crate::*;
use iroh::endpoint::Connection;
use iroh::{Endpoint, SecretKey};
use mimir_proto::{CallMsg, EventMsg};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

pub struct ServerConfig {
    /// `~/.config/mimir/link`: `key` and `phones.json`.
    pub dir: PathBuf,
    /// Use n0's relays (and address lookup). Off: direct connections only.
    pub relays: bool,
    pub pc_name: String,
}

/// What the link tells the service.
pub enum LinkEvent {
    /// A phone gave the right code: show its name and words, and ask.
    PairPending {
        pending: u64,
        phone_name: String,
        words: Vec<String>,
    },
    /// The pairing ended (paired, refused after "No", or no answer).
    PairEnded {
        pending: u64,
        paired: bool,
    },
    /// A paired phone opened a session: send its events to `events`.
    Connected {
        conn: u64,
        phone_key: String,
        phone_name: String,
        /// Whose phone it is, from what the PC kept when it was paired
        /// (never from anything the phone says).
        person: String,
        events: Sender<EventMsg>,
    },
    Call {
        conn: u64,
        msg: CallMsg,
    },
    /// A person has no phone left (their last phone paired again as
    /// someone else's): their chats and settings must go.
    PersonGone {
        person: String,
    },
    Disconnected {
        conn: u64,
    },
}

/// A paired phone, as `phones.json` keeps it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Phone {
    /// The key iroh authenticated for the pairing connection.
    pub key: String,
    pub name: String,
    pub paired: u64,
    #[serde(default)]
    pub last_seen: u64,
    /// Whose phone it is: [`OWNER`] (the PC's owner, also for every phone
    /// paired before people were told apart) or another person's id.
    #[serde(default = "owner_id")]
    pub person: String,
}

/// The PC's owner as a person.
pub const OWNER: &str = "owner";

fn owner_id() -> String {
    OWNER.to_string()
}

/// A person other than the owner, as `people.json` keeps them.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Person {
    /// Letters and digits only (it is also a folder name on the PC).
    pub id: String,
    /// A first name.
    pub name: String,
    /// May use the owner's online plan (Claude) for their chats. Off
    /// until the owner turns it on.
    #[serde(default)]
    pub online: bool,
}

/// The owner's answer to "Do the words match?" and "Whose phone is this?".
#[derive(Debug, Clone, PartialEq)]
pub struct Verdict {
    pub yes: bool,
    /// `Some(WHOSE_MINE)`: the owner's own phone. `Some(first name)`:
    /// someone else's. None or blank: not said, so refused.
    pub person: Option<String>,
}

/// What a phone hears when no code is waiting (used, expired or replaced:
/// the PC keeps nothing of an old code to tell which).
const NO_CODE: &str =
    "That code no longer works: it was used, expired or replaced. Make a new one on the PC.";

struct Offer {
    secret: [u8; 32],
    expires: Instant,
    tries: u32,
}

struct Inner {
    dir: PathBuf,
    pc_name: Mutex<String>,
    pc_key: [u8; 32],
    offer: Mutex<Option<Offer>>,
    pending: Mutex<HashMap<u64, tokio::sync::oneshot::Sender<Verdict>>>,
    phones: Mutex<Vec<Phone>>,
    people: Mutex<Vec<Person>>,
    conns: Mutex<HashMap<String, Vec<(u64, Connection)>>>,
    next: AtomicU64,
    events: Mutex<Sender<LinkEvent>>,
}

pub struct Server {
    inner: Arc<Inner>,
    endpoint: Endpoint,
    rt: Option<tokio::runtime::Runtime>,
}

impl Server {
    /// Opens the link (its own runtime, 2 threads) and starts accepting.
    pub fn start(cfg: ServerConfig, events: Sender<LinkEvent>) -> std::io::Result<Server> {
        std::fs::create_dir_all(&cfg.dir)?;
        private(&cfg.dir, 0o700);
        let key = load_or_make_key(&cfg.dir.join("key"))?;
        let phones = load_phones(&cfg.dir.join("phones.json"));
        let people = load_people(&cfg.dir.join("people.json"));
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("mimir-link")
            .enable_all()
            .build()?;
        let builder = if cfg.relays {
            Endpoint::builder(iroh::endpoint::presets::N0)
        } else {
            Endpoint::builder(iroh::endpoint::presets::N0DisableRelay)
        };
        let endpoint = rt
            .block_on(
                builder
                    .secret_key(key.clone())
                    .alpns(vec![ALPN_PAIR.to_vec(), ALPN_PHONE.to_vec()])
                    .bind(),
            )
            .map_err(|e| std::io::Error::other(format!("the phone link can't open: {e}")))?;
        let inner = Arc::new(Inner {
            dir: cfg.dir,
            pc_name: Mutex::new(cfg.pc_name),
            pc_key: *key.public().as_bytes(),
            offer: Mutex::new(None),
            pending: Mutex::new(HashMap::new()),
            phones: Mutex::new(phones),
            people: Mutex::new(people),
            conns: Mutex::new(HashMap::new()),
            next: AtomicU64::new(1),
            events: Mutex::new(events),
        });
        let ep = endpoint.clone();
        let inn = inner.clone();
        rt.spawn(async move {
            while let Some(incoming) = ep.accept().await {
                let inn = inn.clone();
                tokio::spawn(async move {
                    let Ok(conn) = incoming.await else { return };
                    let alpn = conn.alpn().to_vec();
                    if alpn == ALPN_PAIR {
                        pair(inn, conn).await;
                    } else if alpn == ALPN_PHONE {
                        session(inn, conn).await;
                    } else {
                        conn.close(1u32.into(), b"unknown");
                    }
                });
            }
        });
        Ok(Server {
            inner,
            endpoint,
            rt: Some(rt),
        })
    }

    /// The PC's link id (its public key).
    pub fn id(&self) -> String {
        self.endpoint.id().to_string()
    }

    /// Waits until the PC can be found through the relays (its address is
    /// published). False after `timeout`.
    pub fn wait_online(&self, timeout: std::time::Duration) -> bool {
        let ep = self.endpoint.clone();
        match &self.rt {
            Some(rt) => {
                rt.block_on(async move { tokio::time::timeout(timeout, ep.online()).await.is_ok() })
            }
            None => false,
        }
    }

    /// The local sockets it listens on (for checks that connect directly).
    pub fn bound(&self) -> Vec<SocketAddr> {
        self.endpoint.bound_sockets()
    }

    pub fn set_pc_name(&self, name: &str) {
        *self.inner.pc_name.lock().unwrap() = name.to_string();
    }

    /// A new pairing code (the old one stops working). It lives only in
    /// memory, for 10 minutes or one use.
    pub fn pair_code(&self) -> String {
        let mut secret = [0u8; 32];
        rand::fill(&mut secret);
        let life = wait_from_env("MIMIR_PAIR_LIFE_MS", CODE_LIFE);
        // A new code replaces the old one, which stops working.
        *self.inner.offer.lock().unwrap() = Some(Offer {
            secret,
            expires: Instant::now() + life,
            tries: 0,
        });
        Code {
            pc_id: self.id(),
            secret,
            pc_name: self.inner.pc_name.lock().unwrap().clone(),
        }
        .text()
    }

    /// The owner's answer to "Do the words match?".
    pub fn confirm(&self, pending: u64, yes: bool) -> bool {
        let person = Some(mimir_proto::WHOSE_MINE.to_string());
        self.confirm_as(pending, Verdict { yes, person })
    }

    /// The owner's answer with "Whose phone is this?".
    pub fn confirm_as(&self, pending: u64, verdict: Verdict) -> bool {
        match self.inner.pending.lock().unwrap().remove(&pending) {
            Some(tx) => tx.send(verdict).is_ok(),
            None => false,
        }
    }

    pub fn phones(&self) -> Vec<Phone> {
        self.inner.phones.lock().unwrap().clone()
    }

    /// The people other than the owner.
    pub fn people(&self) -> Vec<Person> {
        self.inner.people.lock().unwrap().clone()
    }

    /// Whose a paired phone is (None: not paired).
    pub fn person_of(&self, key: &str) -> Option<String> {
        self.inner.person_of(key)
    }

    /// Whether `person` may use the owner's online plan. The owner always.
    pub fn may_go_online(&self, person: &str) -> bool {
        person == OWNER
            || self
                .inner
                .people
                .lock()
                .unwrap()
                .iter()
                .any(|p| p.id == person && p.online)
    }

    /// Turns the online plan on or off for a person (not the owner).
    pub fn set_online(&self, person: &str, on: bool) -> bool {
        let found = {
            let mut p = self.inner.people.lock().unwrap();
            match p.iter_mut().find(|x| x.id == person) {
                Some(x) => {
                    x.online = on;
                    true
                }
                None => false,
            }
        };
        if found {
            self.inner.save_people();
        }
        found
    }

    /// Gives a person (not the owner) another first name.
    pub fn rename_person(&self, person: &str, name: &str) -> bool {
        let name = clean_name(name);
        if name.is_empty() {
            return false;
        }
        let found = {
            let mut p = self.inner.people.lock().unwrap();
            match p.iter_mut().find(|x| x.id == person) {
                Some(x) => {
                    x.name = name;
                    true
                }
                None => false,
            }
        };
        if found {
            self.inner.save_people();
        }
        found
    }

    /// Unpairs a phone and closes its open connections at once.
    pub fn remove_phone(&self, key: &str) -> bool {
        self.unpair(key).is_some()
    }

    /// [`Server::remove_phone`], and with it a person
    /// left with no phone is gone too: their id is returned, so their
    /// chats can go with them.
    pub fn unpair(&self, key: &str) -> Option<Option<String>> {
        let (found, person) = {
            let mut p = self.inner.phones.lock().unwrap();
            let person = p.iter().find(|x| x.key == key).map(|x| x.person.clone());
            let before = p.len();
            p.retain(|x| x.key != key);
            (before != p.len(), person)
        };
        if found {
            self.inner.save_phones();
        }
        if let Some(list) = self.inner.conns.lock().unwrap().remove(key) {
            for (_, c) in list {
                c.close(2u32.into(), b"not paired");
            }
        }
        if !found {
            return None;
        }
        let person = person.filter(|p| p != OWNER);
        let gone = person.filter(|id| {
            !self
                .inner
                .phones
                .lock()
                .unwrap()
                .iter()
                .any(|x| &x.person == id)
        });
        if let Some(id) = &gone {
            self.inner.people.lock().unwrap().retain(|p| &p.id != id);
            self.inner.save_people();
        }
        Some(gone)
    }

    /// Removes a person (not the owner) with every phone of theirs.
    pub fn remove_person(&self, person: &str) -> bool {
        if person == OWNER {
            return false;
        }
        let keys: Vec<String> = self
            .inner
            .phones
            .lock()
            .unwrap()
            .iter()
            .filter(|p| p.person == person)
            .map(|p| p.key.clone())
            .collect();
        for k in &keys {
            self.unpair(k);
        }
        let known = {
            let mut p = self.inner.people.lock().unwrap();
            let before = p.len();
            p.retain(|x| x.id != person);
            before != p.len()
        };
        if known {
            self.inner.save_people();
        }
        known || !keys.is_empty()
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        if let Some(rt) = self.rt.take() {
            let ep = self.endpoint.clone();
            rt.block_on(async move {
                let _ = tokio::time::timeout(std::time::Duration::from_secs(2), ep.close()).await;
            });
            rt.shutdown_background();
        }
    }
}

impl Inner {
    fn send(&self, e: LinkEvent) {
        let _ = self.events.lock().unwrap().send(e);
    }

    /// Checks a phone's pairing proof against the code in memory.
    fn check(&self, phone_key: &[u8], hmac: &str) -> Result<[u8; 32], String> {
        let mut o = self.offer.lock().unwrap();
        let Some(offer) = o.as_mut() else {
            return Err(NO_CODE.into());
        };
        if Instant::now() > offer.expires {
            *o = None;
            return Err("The code expired. Make a new one on the PC.".into());
        }
        if pair_hmac_ok(&offer.secret, phone_key, hmac) {
            let secret = offer.secret;
            *o = None;
            return Ok(secret);
        }
        offer.tries += 1;
        if offer.tries >= CODE_TRIES {
            *o = None;
            return Err(
                "Too many wrong tries: the code was cancelled. Make a new one on the PC.".into(),
            );
        }
        Err("That code doesn't match the PC's. Scan it again.".into())
    }

    fn is_paired(&self, key: &str) -> Option<(String, String)> {
        self.phones
            .lock()
            .unwrap()
            .iter()
            .find(|p| p.key == key)
            .map(|p| (p.name.clone(), p.person.clone()))
    }

    fn person_of(&self, key: &str) -> Option<String> {
        self.is_paired(key).map(|(_, person)| person)
    }

    /// The person with this first name (not the owner), made when new.
    fn person_named(&self, name: &str) -> String {
        let mut people = self.people.lock().unwrap();
        if let Some(p) = people
            .iter()
            .find(|p| p.name.to_lowercase() == name.to_lowercase())
        {
            return p.id.clone();
        }
        let mut raw = [0u8; 6];
        rand::fill(&mut raw);
        let id: String = raw.iter().map(|b| format!("{b:02x}")).collect();
        people.push(Person {
            id: id.clone(),
            name: name.to_string(),
            online: false,
        });
        id
    }

    fn save_people(&self) {
        let list = self.people.lock().unwrap().clone();
        self.save_json("people.json", &list);
    }

    fn save_json<T: Serialize>(&self, name: &str, value: &T) {
        let path = self.dir.join(name);
        let tmp = self.dir.join(format!("{name}.tmp"));
        if let Ok(text) = serde_json::to_vec_pretty(value)
            && std::fs::write(&tmp, text).is_ok()
        {
            private(&tmp, 0o600);
            let _ = std::fs::rename(&tmp, &path);
        }
    }

    fn save_phones(&self) {
        let list = self.phones.lock().unwrap().clone();
        self.save_json("phones.json", &list);
    }
}

/// A first name as kept: trimmed, one line, not long.
fn clean_name(name: &str) -> String {
    name.chars()
        .filter(|c| !c.is_control() || c.is_whitespace())
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(40)
        .collect()
}

async fn pair(inner: Arc<Inner>, conn: Connection) {
    let phone = conn.remote_id();
    let Ok((mut send, mut recv)) = conn.accept_bi().await else {
        return;
    };
    let req = match tokio::time::timeout(
        std::time::Duration::from_secs(20),
        read_json::<_, PairRequest>(&mut recv),
    )
    .await
    {
        Ok(Ok(Some(r))) => r,
        _ => return,
    };
    let name: String = req.name.trim().chars().take(60).collect();
    let name = if name.is_empty() {
        "A phone".to_string()
    } else {
        name
    };
    let secret = match inner.check(phone.as_bytes(), &req.hmac) {
        Ok(secret) => secret,
        Err(why) => {
            let _ = write_json(&mut send, &PairReply::Refused { why }).await;
            finish(&conn, send).await;
            return;
        }
    };
    let words = four_words(&inner.pc_key, phone.as_bytes(), &secret);
    if write_json(
        &mut send,
        &PairReply::Words {
            words: words.clone(),
        },
    )
    .await
    .is_err()
    {
        return;
    }
    let id = inner.next.fetch_add(1, Ordering::Relaxed);
    let (tx, rx) = tokio::sync::oneshot::channel();
    inner.pending.lock().unwrap().insert(id, tx);
    inner.send(LinkEvent::PairPending {
        pending: id,
        phone_name: name.clone(),
        words,
    });
    let wait = wait_from_env("MIMIR_PAIR_CONFIRM_MS", CONFIRM_WAIT);
    // The phone giving up (closing) also ends the wait.
    let answer = tokio::select! {
        a = tokio::time::timeout(wait, rx) => match a {
            Ok(Ok(v)) => Some(v),
            _ => None,
        },
        _ = conn.closed() => None,
    };
    inner.pending.lock().unwrap().remove(&id);
    let reply = match answer {
        Some(Verdict { yes: true, person }) => {
            let key = phone.to_string();
            // Whose phone it is was decided at the PC, by its owner.
            // Said out loud: only an explicit "mine" is the owner's.
            let person = match person {
                Some(m) if m == mimir_proto::WHOSE_MINE => Some(OWNER.to_string()),
                Some(n) if !clean_name(&n).is_empty() => Some(inner.person_named(&clean_name(&n))),
                _ => None,
            };
            let Some(person) = person else {
                let _ = write_json(
                    &mut send,
                    &PairReply::Refused {
                        why: "The PC didn't hear whose phone this is. Make a new code on the PC."
                            .into(),
                    },
                )
                .await;
                inner.send(LinkEvent::PairEnded {
                    pending: id,
                    paired: false,
                });
                finish(&conn, send).await;
                return;
            };
            let old;
            {
                let mut p = inner.phones.lock().unwrap();
                old = p.iter().find(|x| x.key == key).map(|x| x.person.clone());
                p.retain(|x| x.key != key);
                p.push(Phone {
                    key: key.clone(),
                    name,
                    paired: now(),
                    last_seen: now(),
                    person: person.clone(),
                });
            }
            inner.save_phones();
            inner.save_people();
            // The phone's old person with no phone left is gone: nothing of
            // them may be inherited by whoever is named the same later.
            if let Some(old) = old.filter(|o| *o != person && o != OWNER)
                && !inner.phones.lock().unwrap().iter().any(|x| x.person == old)
            {
                inner.people.lock().unwrap().retain(|p| p.id != old);
                inner.save_people();
                inner.send(LinkEvent::PersonGone { person: old });
            }
            // A pairing again starts afresh: a connection of the old
            // pairing (maybe another person's) is closed.
            if let Some(list) = inner.conns.lock().unwrap().remove(&key) {
                for (_, c) in list {
                    c.close(2u32.into(), b"paired again");
                }
            }
            PairReply::Paired {
                pc_name: inner.pc_name.lock().unwrap().clone(),
            }
        }
        Some(Verdict { yes: false, .. }) => PairReply::Refused {
            why: "The PC said the words don't match. Make a new code on the PC.".into(),
        },
        None => PairReply::Refused {
            why: "Nobody confirmed on the PC in time. Make a new code on the PC.".into(),
        },
    };
    let paired = matches!(reply, PairReply::Paired { .. });
    let _ = write_json(&mut send, &reply).await;
    inner.send(LinkEvent::PairEnded {
        pending: id,
        paired,
    });
    finish(&conn, send).await;
}

/// Finishes the stream and waits briefly for the phone to read it.
async fn finish(conn: &Connection, mut send: iroh::endpoint::SendStream) {
    let _ = send.finish();
    let _ = tokio::time::timeout(std::time::Duration::from_secs(5), conn.closed()).await;
}

async fn session(inner: Arc<Inner>, conn: Connection) {
    let key = conn.remote_id().to_string();
    let Some((name, person)) = inner.is_paired(&key) else {
        conn.close(2u32.into(), b"not paired");
        return;
    };
    let id = inner.next.fetch_add(1, Ordering::Relaxed);
    inner
        .conns
        .lock()
        .unwrap()
        .entry(key.clone())
        .or_default()
        .push((id, conn.clone()));
    // Paired again or removed while this was starting: look again, now
    // that the connection can be found and closed by whoever changes it.
    if inner.is_paired(&key).map(|(_, p)| p) != Some(person.clone()) {
        if let Some(list) = inner.conns.lock().unwrap().get_mut(&key) {
            list.retain(|(c, _)| *c != id);
        }
        conn.close(2u32.into(), b"not paired");
        return;
    }
    {
        let mut p = inner.phones.lock().unwrap();
        if let Some(x) = p.iter_mut().find(|x| x.key == key) {
            x.last_seen = now();
        }
    }
    inner.save_phones();
    if let Ok((mut send, mut recv)) = conn.accept_bi().await
        && let Ok(Ok(Some(Greeting { link: 1 }))) = tokio::time::timeout(
            std::time::Duration::from_secs(20),
            read_json::<_, Greeting>(&mut recv),
        )
        .await
    {
        let (etx, erx) = std::sync::mpsc::channel::<EventMsg>();
        let (atx, mut arx) = tokio::sync::mpsc::unbounded_channel::<EventMsg>();
        std::thread::spawn(move || {
            for e in erx {
                if atx.send(e).is_err() {
                    break;
                }
            }
        });
        inner.send(LinkEvent::Connected {
            conn: id,
            phone_key: key.clone(),
            phone_name: name,
            person,
            events: etx,
        });
        let writer = tokio::spawn(async move {
            while let Some(e) = arx.recv().await {
                if write_json(&mut send, &e).await.is_err() {
                    break;
                }
            }
        });
        loop {
            tokio::select! {
                m = read_json::<_, CallMsg>(&mut recv) => match m {
                    Ok(Some(msg)) => inner.send(LinkEvent::Call { conn: id, msg }),
                    _ => break,
                },
                _ = conn.closed() => break,
            }
        }
        writer.abort();
        inner.send(LinkEvent::Disconnected { conn: id });
    }
    if let Some(list) = inner.conns.lock().unwrap().get_mut(&key) {
        list.retain(|(c, _)| *c != id);
    }
    // A phone removed meanwhile is told so.
    if inner.is_paired(&key).is_none() {
        conn.close(2u32.into(), b"not paired");
    }
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn private(path: &std::path::Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode));
}

fn load_or_make_key(path: &std::path::Path) -> std::io::Result<SecretKey> {
    if let Ok(b) = std::fs::read(path)
        && let Ok(b) = <[u8; 32]>::try_from(b.as_slice())
    {
        return Ok(SecretKey::from_bytes(&b));
    }
    let key = SecretKey::generate();
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, key.to_bytes())?;
    private(&tmp, 0o600);
    std::fs::rename(&tmp, path)?;
    Ok(key)
}

fn load_people(path: &std::path::Path) -> Vec<Person> {
    std::fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn load_phones(path: &std::path::Path) -> Vec<Phone> {
    std::fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}
