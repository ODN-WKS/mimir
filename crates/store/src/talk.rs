//! Where general chats live on the PC (Stage 1 design, 2.4 and 2.5): one
//! file a chat in `talk/`, 0600, written whole and atomically by the
//! store's writer thread, encrypted with a key kept in the desktop's
//! keyring. Every change the PC accepts gets the next number of one
//! PC-wide counter (`seq`), so a phone asks for "everything after n" and
//! device clocks never matter. Turns are added, never edited, except that
//! an answer with a higher version replaces the one before; a deleted chat
//! leaves a tombstone forever.

use crate::keyring::{Keyring, MAGIC, open_sealed, seal};
use crate::writer::Writer;
use mimir_proto::{ChatPutDone, ChatRecord, ChatTurn, ChatsPage, Picture};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// A page of `ChatsSince` stays under about this many bytes (the link
/// carries at most 4 MB a message).
const PAGE_BYTES: usize = 1_500_000;

/// Pictures kept for one chat stay under this many bytes; the oldest go
/// first, so a chat never fills the disk.
const PICTURES_CAP: u64 = if cfg!(test) {
    1024 * 1024
} else {
    40 * 1024 * 1024
};
/// At most this many pictures a message, and this big each (as sent).
pub const PICTURES_PER_TURN: usize = 4;
const PICTURE_BYTES: usize = 3 * 1024 * 1024;

/// The one line Settings shows when chats are kept without encryption
/// (PCE-07).
pub const UNENCRYPTED: &str = "Chats kept unencrypted";

/// The person who owns the PC. Chats kept before people were told apart
/// belong to them (they have no `person` in their file).
pub const OWNER: &str = "owner";

fn owner() -> String {
    OWNER.to_string()
}

/// A chat as kept: its record, whose it is, and the change number of its
/// name and deletion.
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
struct Kept {
    chat: ChatRecord,
    #[serde(default)]
    meta_seq: u64,
    /// Whose chat it is. A chat id means a chat only for its own person:
    /// the same id under another person is another chat.
    #[serde(default = "owner")]
    person: String,
}

/// A chat's place in the store: (person, chat id).
type Key = (String, String);

fn chat_key(person: &str, id: &str) -> Key {
    (person.to_string(), id.to_string())
}

/// The chats on this PC.
pub struct TalkStore {
    dir: PathBuf,
    key: Option<[u8; 32]>,
    /// No keyring answered while some files are encrypted: nothing can be
    /// read or written until it does.
    locked: bool,
    /// Each person's own change counter: nobody can see another's.
    seqs: HashMap<String, u64>,
    chats: HashMap<Key, Kept>,
    ring: Keyring,
    /// Writes go here (batched, retried until they work).
    writer: Writer,
}

/// Asks the keyring again for a locked store, on a thread of its own and
/// at most once in a while, so a locked wallet never holds the caller.
pub struct Unlock {
    pending: Option<std::sync::mpsc::Receiver<TalkStore>>,
    last: Option<std::time::Instant>,
    gap: std::time::Duration,
}

impl Unlock {
    pub fn new(gap: std::time::Duration) -> Unlock {
        Unlock {
            pending: None,
            last: None,
            gap,
        }
    }

    /// Takes in a finished try, or starts one when due. Never waits.
    pub fn step(&mut self, store: &mut TalkStore) {
        if !store.locked {
            self.pending = None;
            return;
        }
        if let Some(rx) = &self.pending {
            match rx.try_recv() {
                Ok(fresh) => {
                    self.pending = None;
                    if !fresh.locked {
                        crate::log::note("store", "unlock", vec![]);
                        *store = fresh;
                    }
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => return,
                Err(_) => self.pending = None,
            }
        }
        if self.pending.is_none() && self.last.is_none_or(|t| t.elapsed() >= self.gap) {
            self.last = Some(std::time::Instant::now());
            let (tx, rx) = std::sync::mpsc::channel();
            let (dir, ring, w) = (store.dir.clone(), store.ring.clone(), store.writer.clone());
            if std::thread::Builder::new()
                .name("chat-keyring".into())
                .spawn(move || {
                    let _ = tx.send(TalkStore::open_with(&dir, &ring, w));
                })
                .is_ok()
            {
                self.pending = Some(rx);
            }
        }
    }
}

impl TalkStore {
    /// A store not opened yet: locked and empty, to be opened by [`Unlock`]
    /// on its thread (so the keyring is never asked on the caller's).
    pub fn pending(dir: &Path, ring: &Keyring) -> TalkStore {
        TalkStore::pending_with(dir, ring, Writer::global().clone())
    }

    /// [`TalkStore::pending`] with its own writer.
    pub fn pending_with(dir: &Path, ring: &Keyring, writer: Writer) -> TalkStore {
        TalkStore {
            dir: dir.to_path_buf(),
            key: None,
            locked: true,
            seqs: HashMap::new(),
            chats: HashMap::new(),
            ring: ring.clone(),
            writer,
        }
    }

    /// Opens the chats in `dir`, with the key from `ring`.
    pub fn open(dir: &Path, ring: &Keyring) -> TalkStore {
        TalkStore::open_with(dir, ring, Writer::global().clone())
    }

    /// [`TalkStore::open`] with its own writer.
    pub fn open_with(dir: &Path, ring: &Keyring, writer: Writer) -> TalkStore {
        let opened = std::time::Instant::now();
        let encrypted_files = std::fs::read_dir(dir)
            .into_iter()
            .flatten()
            .flatten()
            .any(|e| {
                use std::io::Read;
                let mut head = [0u8; 6];
                e.path().extension().is_some_and(|x| x == "json")
                    && std::fs::File::open(e.path())
                        .and_then(|mut f| f.read_exact(&mut head))
                        .is_ok()
                    && head == *MAGIC
            });
        crate::atomic::sweep(dir);
        let found = ring.key(!encrypted_files);
        // The desktop's keyring failing is never "plain files": the chats
        // are locked until it answers. Only a PC with no keyring program
        // at all, and no encrypted chats, keeps them plain (and Settings
        // says so, PCE-07).
        let no_program = matches!(&found, Err(e) if e == crate::keyring::NO_PROGRAM);
        let ring_failed = matches!(ring, Keyring::SecretService)
            && found.is_err()
            && !(no_program && !encrypted_files);
        let key = found.ok();
        let mut s = TalkStore {
            dir: dir.to_path_buf(),
            key,
            locked: ring_failed,
            seqs: HashMap::from([(
                OWNER.to_string(),
                std::fs::read_to_string(dir.join("seq"))
                    .ok()
                    .and_then(|t| t.trim().parse().ok())
                    .unwrap_or(0),
            )]),
            chats: HashMap::new(),
            ring: ring.clone(),
            writer,
        };
        // The owner's chats are the files in the folder itself; every other
        // person's are in `people/<person>/`.
        let mut files: Vec<(String, PathBuf)> = vec![];
        for e in std::fs::read_dir(dir).into_iter().flatten().flatten() {
            files.push((OWNER.to_string(), e.path()));
        }
        for who in std::fs::read_dir(people_dir(dir))
            .into_iter()
            .flatten()
            .flatten()
        {
            let person = who.file_name().to_string_lossy().into_owned();
            crate::atomic::sweep(&who.path());
            for e in std::fs::read_dir(who.path())
                .into_iter()
                .flatten()
                .flatten()
            {
                files.push((person.clone(), e.path()));
            }
        }
        for (person, p) in files {
            if p.extension().is_none_or(|x| x != "json") {
                continue;
            }
            let Ok(bytes) = std::fs::read(&p) else {
                continue;
            };
            let plain = if bytes.starts_with(MAGIC) {
                match s.key.as_ref().and_then(|k| open_sealed(k, &bytes)) {
                    Some(p) => p,
                    None => {
                        s.locked = true;
                        crate::log::error(
                            "talk",
                            "open",
                            vec![("file", p.display().to_string().into())],
                            "a chat file can't be opened",
                        );
                        continue;
                    }
                }
            } else {
                bytes
            };
            if let Ok(mut k) = serde_json::from_slice::<Kept>(&plain) {
                // The folder a chat is in says whose it is, whatever the
                // file says.
                k.person = person.clone();
                let top = s.seqs.entry(person.clone()).or_insert(0);
                *top = (*top).max(k.chat.seq);
                s.chats.insert(chat_key(&person, &k.chat.id), k);
            }
        }
        let mut f: Vec<(&'static str, serde_json::Value)> = vec![
            ("chats", s.chats.len().into()),
            ("locked", s.locked.into()),
            ("encrypted", s.key.is_some().into()),
            ("ms", (opened.elapsed().as_millis() as u64).into()),
        ];
        if s.locked {
            f.push((
                "err",
                "The chat history is locked (the desktop's wallet).".into(),
            ));
            f.push(("kind", "keyring".into()));
        }
        crate::log::note("store", "open", f);
        s
    }

    /// Whether the chats are locked (no keyring key for encrypted files).
    pub fn locked(&self) -> bool {
        self.locked
    }

    /// Whether the chat files are encrypted (false: plain, 0600).
    pub fn encrypted(&self) -> bool {
        self.key.is_some()
    }

    /// The newest change number of the owner's chats.
    pub fn seq(&self) -> u64 {
        self.seq_of(OWNER)
    }

    /// The newest change number of one person's chats (their own count).
    pub fn seq_of(&self, person: &str) -> u64 {
        self.seqs.get(person).copied().unwrap_or(0)
    }

    fn next(&mut self, person: &str) -> u64 {
        let n = self.seqs.entry(person.to_string()).or_insert(0);
        *n += 1;
        *n
    }

    /// Hands a chat (and the counter) to the writer: written within about
    /// a second, and again until it works (PCE-08, PCE-14, PCE-32).
    fn save(&self, person: &str, id: &str) -> Result<(), String> {
        let Some(k) = self.chats.get(&chat_key(person, id)) else {
            return Ok(());
        };
        let plain = serde_json::to_vec(k).map_err(|e| e.to_string())?;
        let bytes = match &self.key {
            Some(key) => seal(key, &plain)?,
            None => plain,
        };
        self.writer.put(&chat_path(&self.dir, person, id), bytes);
        if person == OWNER {
            self.writer
                .put(&self.dir.join("seq"), self.seq().to_string().into_bytes());
        }
        Ok(())
    }

    /// Writes everything waiting now (at shutdown, and in checks).
    pub fn flush(&self) -> Result<(), String> {
        self.writer.flush(std::time::Duration::from_secs(10))
    }

    /// The one line Settings shows about how chats are kept, if any:
    /// "Chats kept unencrypted" without a keyring (PCE-07).
    pub fn notice(&self) -> Option<&'static str> {
        (!self.locked && self.key.is_none()).then_some(UNENCRYPTED)
    }

    /// Whether saves are failing (a full disk): they're retried.
    pub fn save_failing(&self) -> Option<String> {
        self.writer.last_error()
    }

    /// Takes a chat's new or changed turns, name or deletion. The reply,
    /// and what changed (for the other devices), if anything did.
    pub fn put(
        &mut self,
        chat_id: &str,
        name: Option<String>,
        turns: Vec<ChatTurn>,
        deleted: bool,
        now_ms: i64,
    ) -> Result<(ChatPutDone, Option<ChatRecord>), String> {
        self.put_for(OWNER, chat_id, name, turns, deleted, now_ms)
    }

    /// [`TalkStore::put`] in `person`'s chats: another person's chat with
    /// the same id is another chat, never touched.
    pub fn put_for(
        &mut self,
        person: &str,
        chat_id: &str,
        name: Option<String>,
        turns: Vec<ChatTurn>,
        deleted: bool,
        now_ms: i64,
    ) -> Result<(ChatPutDone, Option<ChatRecord>), String> {
        if self.locked {
            return Err("Your PC's chat history is locked: unlock the desktop's wallet.".into());
        }
        if chat_id.is_empty() || chat_id.len() > 200 {
            return Err("That isn't a chat.".into());
        }
        if self
            .chats
            .get(&chat_key(person, chat_id))
            .is_some_and(|k| k.chat.deleted.is_some())
        {
            return Ok((
                ChatPutDone {
                    seq: self.seq_of(person),
                    deleted: true,
                },
                None,
            ));
        }
        if deleted {
            let seq = self.next(person);
            let k = self.chats.entry(chat_key(person, chat_id)).or_default();
            k.person = person.to_string();
            k.chat = ChatRecord {
                id: chat_id.to_string(),
                name: None,
                at: now_ms,
                seq,
                turns: vec![],
                deleted: Some(now_ms),
            };
            k.meta_seq = seq;
            let changed = k.chat.clone();
            let _ = std::fs::remove_dir_all(pictures_dir(&self.dir, person, chat_id));
            self.save(person, chat_id)?;
            return Ok((ChatPutDone { seq, deleted: true }, Some(changed)));
        }
        let mut changed_turns = vec![];
        let mut meta = false;
        let mut k = self
            .chats
            .remove(&chat_key(person, chat_id))
            .unwrap_or_else(|| Kept {
                chat: ChatRecord {
                    id: chat_id.to_string(),
                    ..Default::default()
                },
                meta_seq: 0,
                person: person.to_string(),
            });
        for mut t in turns {
            // Pictures are kept apart (`put_pictures`), never in the turn
            // a phone is sent.
            t.pictures.clear();
            match k.chat.turns.iter_mut().find(|x| x.id == t.id) {
                Some(old) if t.version > old.version => {
                    t.seq = self.next(person);
                    *old = t.clone();
                    changed_turns.push(t);
                }
                Some(_) => {}
                None => {
                    t.seq = self.next(person);
                    k.chat.at = k.chat.at.max(t.at);
                    k.chat.turns.push(t.clone());
                    changed_turns.push(t);
                }
            }
        }
        if let Some(n) = name.filter(|n| Some(n) != k.chat.name.as_ref()) {
            k.chat.name = Some(n).filter(|n| !n.trim().is_empty());
            k.meta_seq = self.next(person);
            meta = true;
        }
        order(&mut k.chat.turns);
        let changed = !changed_turns.is_empty() || meta;
        if changed {
            k.chat.seq = self.seq_of(person);
        }
        let out = changed.then(|| ChatRecord {
            turns: changed_turns,
            ..k.chat.clone()
        });
        self.chats.insert(chat_key(person, chat_id), k);
        if changed {
            self.save(person, chat_id)?;
        }
        Ok((
            ChatPutDone {
                seq: self.seq_of(person),
                deleted: false,
            },
            out,
        ))
    }

    /// Every chat of the owner changed after `since`: only the turns changed
    /// after it, tombstones included, in pages under about 1.5 MB (`more`
    /// says another page follows).
    pub fn since(&self, since: u64) -> ChatsPage {
        self.since_for(OWNER, since)
    }

    /// [`TalkStore::since`] for one person: only their own chats are ever
    /// looked at.
    pub fn since_for(&self, person: &str, since: u64) -> ChatsPage {
        // Every change after `since`, oldest first: (seq, chat, turn index
        // or None for its name and deletion).
        let mut changes: Vec<(u64, &str, Option<usize>)> = vec![];
        for ((who, id), k) in &self.chats {
            if who != person {
                continue;
            }
            if k.meta_seq > since {
                changes.push((k.meta_seq, id, None));
            }
            for (i, t) in k.chat.turns.iter().enumerate() {
                if t.seq > since {
                    changes.push((t.seq, id, Some(i)));
                }
            }
        }
        changes.sort();
        let mut out: Vec<ChatRecord> = vec![];
        let mut bytes = 0;
        let mut last = since;
        let mut more = false;
        for (seq, id, turn) in changes {
            let kept = &self.chats[&chat_key(person, id)];
            let size = turn.map_or(200, |i| {
                let t = &kept.chat.turns[i];
                t.text.len() + t.sources.iter().map(|s| s.words.len() + 200).sum::<usize>() + 200
            });
            if bytes > 0 && bytes + size > PAGE_BYTES {
                more = true;
                break;
            }
            bytes += size;
            last = seq;
            let k = kept;
            let rec = match out.iter_mut().find(|r| r.id == id) {
                Some(r) => r,
                None => {
                    out.push(ChatRecord {
                        turns: vec![],
                        ..k.chat.clone()
                    });
                    out.last_mut().expect("just pushed")
                }
            };
            if let Some(i) = turn {
                rec.turns.push(k.chat.turns[i].clone());
            }
        }
        for r in &mut out {
            order(&mut r.turns);
        }
        ChatsPage {
            seq: if more {
                last
            } else {
                self.seq_of(person).max(since)
            },
            chats: out,
            more,
        }
    }

    /// One of the owner's chats, whole.
    pub fn get(&self, id: &str) -> Option<&ChatRecord> {
        self.get_for(OWNER, id)
    }

    /// One chat of `person`, whole. Another person's chat with this id is
    /// not here: it is "not found", the same as a chat that was never made.
    pub fn get_for(&self, person: &str, id: &str) -> Option<&ChatRecord> {
        self.chats.get(&chat_key(person, id)).map(|k| &k.chat)
    }

    /// The owner's chats that aren't deleted, newest first.
    pub fn list(&self) -> Vec<&ChatRecord> {
        self.list_for(OWNER)
    }

    /// `person`'s chats that aren't deleted, newest first.
    pub fn list_for(&self, person: &str) -> Vec<&ChatRecord> {
        let mut l: Vec<&ChatRecord> = self
            .chats
            .iter()
            .filter(|((who, _), _)| who == person)
            .map(|(_, k)| &k.chat)
            .filter(|c| c.deleted.is_none())
            .collect();
        l.sort_by_key(|c| std::cmp::Reverse(c.at));
        l
    }

    /// Keeps the pictures sent with a message, in the chat's own place
    /// (sealed like the chat), up to [`PICTURES_PER_TURN`] and a size cap
    /// a chat; the oldest go first.
    pub fn put_pictures(
        &mut self,
        person: &str,
        chat_id: &str,
        turn_id: &str,
        pictures: &[Picture],
    ) -> Result<(), String> {
        if self.locked {
            return Err("Your PC's chat history is locked: unlock the desktop's wallet.".into());
        }
        let pictures: Vec<&Picture> = pictures
            .iter()
            .filter(|p| p.base64.len() <= PICTURE_BYTES)
            .take(PICTURES_PER_TURN)
            .collect();
        if pictures.is_empty() || chat_id.is_empty() || chat_id.len() > 200 {
            return Ok(());
        }
        let plain = serde_json::to_vec(&pictures).map_err(|e| e.to_string())?;
        let bytes = match &self.key {
            Some(key) => seal(key, &plain)?,
            None => plain,
        };
        let dir = pictures_dir(&self.dir, person, chat_id);
        crate::atomic::write(&dir.join(format!("{}.pics", file_name(turn_id))), &bytes)
            .map_err(|e| e.to_string())?;
        // Over the cap: the oldest pictures of this chat go first.
        let mut files: Vec<(std::time::SystemTime, u64, PathBuf)> = std::fs::read_dir(&dir)
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|e| {
                let m = e.metadata().ok()?;
                Some((m.modified().ok()?, m.len(), e.path()))
            })
            .collect();
        files.sort();
        let mut total: u64 = files.iter().map(|f| f.1).sum();
        for (_, len, path) in files {
            if total <= PICTURES_CAP {
                break;
            }
            if path.file_stem().is_some_and(|n| *n == *file_name(turn_id)) {
                continue;
            }
            let _ = std::fs::remove_file(path);
            total -= len;
        }
        Ok(())
    }

    /// The pictures kept for one message of `person`'s chat (none when
    /// there are none, or the message is another person's).
    pub fn pictures_for(&self, person: &str, chat_id: &str, turn_id: &str) -> Vec<Picture> {
        if self.locked || chat_id.is_empty() || chat_id.len() > 200 {
            return vec![];
        }
        let path =
            pictures_dir(&self.dir, person, chat_id).join(format!("{}.pics", file_name(turn_id)));
        let Ok(bytes) = std::fs::read(path) else {
            return vec![];
        };
        let plain = if bytes.starts_with(MAGIC) {
            match self.key.as_ref().and_then(|k| open_sealed(k, &bytes)) {
                Some(p) => p,
                None => return vec![],
            }
        } else {
            bytes
        };
        serde_json::from_slice(&plain).unwrap_or_default()
    }

    /// Deletes everything of a person (not the owner): their chats and
    /// their files. Nothing of it is kept, not even a tombstone.
    pub fn forget_person(&mut self, person: &str) {
        if person == OWNER || person.is_empty() {
            return;
        }
        self.chats.retain(|(who, _), _| who != person);
        // Whatever the writer still holds for them would bring them back.
        let _ = self.writer.flush(std::time::Duration::from_secs(10));
        let _ = std::fs::remove_dir_all(people_dir(&self.dir).join(file_name(person)));
    }

    /// The people who have chats here, the owner not counted.
    pub fn people(&self) -> Vec<String> {
        let mut v: Vec<String> = self
            .chats
            .keys()
            .map(|(who, _)| who.clone())
            .filter(|w| w != OWNER)
            .collect();
        v.sort();
        v.dedup();
        v
    }
}

/// Where other people's chats are kept.
fn people_dir(dir: &Path) -> PathBuf {
    dir.join("people")
}

/// Where a chat's pictures are kept: a folder a chat, one file a message.
fn pictures_dir(dir: &Path, person: &str, chat_id: &str) -> PathBuf {
    let base = if person == OWNER {
        dir.join("pictures")
    } else {
        people_dir(dir).join(file_name(person)).join("pictures")
    };
    base.join(file_name(chat_id))
}

/// A chat's file: the owner's in the folder itself, another person's in
/// their own folder under `people/`.
fn chat_path(dir: &Path, person: &str, id: &str) -> PathBuf {
    let name = format!("{}.json", file_name(id));
    if person == OWNER {
        dir.join(name)
    } else {
        people_dir(dir).join(file_name(person)).join(name)
    }
}

/// A chat id as a file name (ids are UUIDs; anything else is made safe).
fn file_name(id: &str) -> String {
    if id
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        id.to_string()
    } else {
        {
            use sha2::Digest;
            let h = sha2::Sha256::digest(id.as_bytes());
            let hex: String = h.iter().take(6).map(|b| format!("{b:02x}")).collect();
            format!("h{hex}")
        }
    }
}

/// The same order on every device: each message by its own time, its
/// answers right after it.
fn order(turns: &mut [ChatTurn]) {
    let at: HashMap<String, i64> = turns
        .iter()
        .filter(|t| t.user)
        .map(|t| (t.id.clone(), t.at))
        .collect();
    turns.sort_by(|a, b| {
        let key = |t: &ChatTurn| {
            let base = t.id.strip_suffix(".a").unwrap_or(&t.id);
            (
                at.get(base).copied().unwrap_or(t.at),
                base.to_string(),
                !t.user,
                t.id.clone(),
            )
        };
        key(a).cmp(&key(b))
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use mimir_proto::Ran;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("talk-store-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    fn t(id: &str, user: bool, text: &str, at: i64, version: u32) -> ChatTurn {
        ChatTurn {
            id: id.into(),
            user,
            text: text.into(),
            sources: vec![],
            ran: (!user).then_some(Ran::Pc),
            model_words: None,
            at,
            version,
            seq: 0,
            gone: false,
            pictures: vec![],
        }
    }

    #[test]
    fn turns_merge_by_id_and_the_higher_version_wins() {
        let d = tmp("merge");
        let key = d.with_extension("key");
        let mut s = TalkStore::open(&d, &Keyring::File(key.clone()));
        assert!(s.encrypted());
        let (done, ch) = s
            .put(
                "c1",
                None,
                vec![t("a", true, "Capital of Norway?", 10, 1)],
                false,
                0,
            )
            .unwrap();
        assert_eq!(done.seq, 1);
        assert_eq!(ch.unwrap().turns.len(), 1);
        // The PC's answer (version 1), then the phone's (version 2).
        s.put("c1", None, vec![t("a.a", false, "Oslo.", 12, 1)], false, 0)
            .unwrap();
        let (_, ch) = s
            .put(
                "c1",
                None,
                vec![t("a.a", false, "Oslo, on the fjord.", 11, 2)],
                false,
                0,
            )
            .unwrap();
        assert_eq!(ch.unwrap().turns[0].text, "Oslo, on the fjord.");
        // A late PC answer (version 1) is dropped; the same turn twice is no change.
        let (_, ch) = s
            .put(
                "c1",
                None,
                vec![
                    t("a.a", false, "Oslo.", 12, 1),
                    t("a", true, "Capital of Norway?", 10, 1),
                ],
                false,
                0,
            )
            .unwrap();
        assert!(ch.is_none());
        assert_eq!(s.get("c1").unwrap().turns.len(), 2);
        assert_eq!(s.get("c1").unwrap().turns[1].text, "Oslo, on the fjord.");
        // Kept across a restart, encrypted, 0600.
        s.flush().unwrap();
        let s2 = TalkStore::open(&d, &Keyring::File(key.clone()));
        assert_eq!(s2.get("c1").unwrap().turns[1].version, 2);
        assert_eq!(s2.seq(), s.seq());
        let raw = std::fs::read(d.join("c1.json")).unwrap();
        assert!(raw.starts_with(MAGIC));
        assert!(!String::from_utf8_lossy(&raw).contains("Norway"));
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(d.join("c1.json"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        // Without the key nothing is read, and nothing is written over it.
        let mut locked = TalkStore::open(&d, &Keyring::Off);
        assert!(locked.get("c1").is_none());
        assert!(locked.put("c1", None, vec![], false, 0).is_err());
        let _ = std::fs::remove_dir_all(&d);
        let _ = std::fs::remove_file(&key);
    }

    #[test]
    fn a_tombstone_cuts_a_turn_and_a_higher_version_brings_it_back() {
        let d = tmp("cut");
        let mut s = TalkStore::open(&d, &Keyring::Off);
        s.put(
            "c1",
            None,
            vec![t("m1", true, "One", 1, 1), t("m2", true, "Two", 2, 1)],
            false,
            10,
        )
        .unwrap();
        let before = s.seq();
        let mut gone = t("m2", true, "", 2, 2);
        gone.gone = true;
        s.put("c1", None, vec![gone], false, 11).unwrap();
        // It travels to the other devices as a change.
        let page = s.since(before);
        assert!(
            page.chats[0]
                .turns
                .iter()
                .any(|x| x.id == "m2" && x.gone && x.text.is_empty())
        );
        // An older copy cannot undo the cut.
        s.put("c1", None, vec![t("m2", true, "Two", 2, 1)], false, 12)
            .unwrap();
        assert!(
            s.get("c1")
                .unwrap()
                .turns
                .iter()
                .any(|x| x.id == "m2" && x.gone)
        );
        // A higher version (Undo) does.
        s.put("c1", None, vec![t("m2", true, "Two", 2, 5)], false, 13)
            .unwrap();
        assert!(
            s.get("c1")
                .unwrap()
                .turns
                .iter()
                .any(|x| x.id == "m2" && !x.gone && x.text == "Two")
        );
    }

    #[test]
    fn turns_added_apart_come_out_in_the_same_order_everywhere() {
        let d = tmp("order");
        let mut s = TalkStore::open(&d, &Keyring::Off);
        assert!(!s.encrypted());
        // The phone (clock ahead) and the PC each added a message and answer.
        s.put(
            "c",
            None,
            vec![
                t("p1", true, "phone q", 2000, 1),
                t("p1.a", false, "phone a", 1990, 1),
            ],
            false,
            0,
        )
        .unwrap();
        s.put(
            "c",
            None,
            vec![
                t("w1", true, "window q", 1000, 1),
                t("w1.a", false, "window a", 1500, 1),
            ],
            false,
            0,
        )
        .unwrap();
        let ids: Vec<&str> = s
            .get("c")
            .unwrap()
            .turns
            .iter()
            .map(|t| t.id.as_str())
            .collect();
        assert_eq!(ids, vec!["w1", "w1.a", "p1", "p1.a"]);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn since_returns_only_changes_after_n_and_a_tombstone_always_wins() {
        let d = tmp("since");
        let mut s = TalkStore::open(&d, &Keyring::Off);
        s.put("c1", None, vec![t("a", true, "one", 1, 1)], false, 0)
            .unwrap();
        s.put("c2", None, vec![t("b", true, "two", 2, 1)], false, 0)
            .unwrap();
        let mark = s.seq();
        s.put(
            "c1",
            Some("Norway".into()),
            vec![t("a.a", false, "Oslo", 3, 1)],
            false,
            0,
        )
        .unwrap();
        let p = s.since(mark);
        assert_eq!(p.chats.len(), 1);
        assert_eq!(p.chats[0].name.as_deref(), Some("Norway"));
        assert_eq!(p.chats[0].turns.len(), 1);
        assert_eq!(p.seq, s.seq());
        assert!(!p.more);
        // Deleted on one phone: gone, and a late put can't bring it back.
        let (done, ch) = s.put("c2", None, vec![], true, 99).unwrap();
        assert!(done.deleted && ch.unwrap().deleted == Some(99));
        let (done, ch) = s
            .put("c2", None, vec![t("b2", true, "late", 5, 1)], false, 0)
            .unwrap();
        assert!(done.deleted && ch.is_none());
        assert!(s.get("c2").unwrap().turns.is_empty());
        let p = s.since(mark);
        assert!(p.chats.iter().any(|c| c.id == "c2" && c.deleted.is_some()));
        assert_eq!(s.list().len(), 1);
        // Everything from the start.
        let all = s.since(0);
        assert_eq!(all.chats.iter().map(|c| c.turns.len()).sum::<usize>(), 2);
        let _ = std::fs::remove_dir_all(&d);
    }

    fn pic(n: usize) -> Picture {
        Picture {
            media_type: "image/jpeg".into(),
            base64: "A".repeat(n),
        }
    }

    #[test]
    fn pictures_are_kept_per_person_and_chat_and_go_with_them() {
        let d = tmp("pics");
        let mut s = TalkStore::open(&d, &Keyring::Off);
        s.put("c1", None, vec![t("a", true, "see", 1, 1)], false, 5)
            .unwrap();
        s.put_pictures(OWNER, "c1", "a", &[pic(10), pic(20)])
            .unwrap();
        assert_eq!(s.pictures_for(OWNER, "c1", "a").len(), 2);
        // Ana, with the same chat and message ids, sees none of it.
        assert!(s.pictures_for("ana", "c1", "a").is_empty());
        // At most four a message.
        s.put_pictures(OWNER, "c1", "b", &vec![pic(5); 6]).unwrap();
        assert_eq!(s.pictures_for(OWNER, "c1", "b").len(), 4);
        // A turn put with pictures keeps none in the chat's own file.
        let mut with = t("c", true, "x", 2, 1);
        with.pictures = vec![pic(5)];
        s.put("c1", None, vec![with], false, 6).unwrap();
        assert!(
            s.get("c1")
                .unwrap()
                .turns
                .iter()
                .all(|t| t.pictures.is_empty())
        );
        // Deleting the chat deletes its pictures.
        s.put("c1", None, vec![], true, 7).unwrap();
        assert!(s.pictures_for(OWNER, "c1", "a").is_empty());
        // A person who leaves takes theirs.
        s.put_for("bo", "c9", None, vec![t("z", true, "bo", 3, 1)], false, 8)
            .unwrap();
        s.put_pictures("bo", "c9", "z", &[pic(7)]).unwrap();
        assert_eq!(s.pictures_for("bo", "c9", "z").len(), 1);
        s.flush().unwrap();
        s.forget_person("bo");
        assert!(s.pictures_for("bo", "c9", "z").is_empty());
        assert!(!d.join("people/bo").exists());
    }

    #[test]
    fn a_chats_pictures_stay_under_the_cap_oldest_first() {
        let d = tmp("picscap");
        let mut s = TalkStore::open(&d, &Keyring::Off);
        let big = 300_000;
        for i in 0..20 {
            s.put_pictures(OWNER, "c1", &format!("t{i}"), &[pic(big)])
                .unwrap();
            std::thread::sleep(std::time::Duration::from_millis(3));
        }
        let kept = (0..20)
            .filter(|i| !s.pictures_for(OWNER, "c1", &format!("t{i}")).is_empty())
            .count();
        assert!((2..20).contains(&kept), "kept {kept}");
        assert!(!s.pictures_for(OWNER, "c1", "t19").is_empty());
        assert!(s.pictures_for(OWNER, "c1", "t0").is_empty());
    }

    #[test]
    fn a_chat_id_means_a_chat_only_for_its_own_person() {
        let d = tmp("people");
        let mut s = TalkStore::open(&d, &Keyring::Off);
        s.put(
            "c1",
            Some("Garden".into()),
            vec![t("a", true, "owner's", 1, 1)],
            false,
            5,
        )
        .unwrap();
        // Another person: not listed, not read, not found by the id, and the same
        // id is a chat of their own.
        assert!(s.get_for("ana", "c1").is_none());
        assert!(s.list_for("ana").is_empty());
        assert!(s.since_for("ana", 0).chats.is_empty());
        s.put_for(
            "ana",
            "c1",
            None,
            vec![t("b", true, "ana's", 2, 1)],
            false,
            6,
        )
        .unwrap();
        assert_eq!(s.get_for("ana", "c1").unwrap().turns[0].text, "ana's");
        assert_eq!(s.get("c1").unwrap().turns.len(), 1);
        assert_eq!(s.get("c1").unwrap().turns[0].text, "owner's");
        // She deletes hers: the owner's stays.
        s.put_for("ana", "c1", None, vec![], true, 7).unwrap();
        assert!(s.list_for("ana").is_empty());
        assert_eq!(s.list().len(), 1);
        // Kept apart on disk, and across a restart.
        s.put_for("bo", "c9", None, vec![t("z", true, "bo's", 3, 1)], false, 8)
            .unwrap();
        s.flush().unwrap();
        assert!(d.join("people/bo/c9.json").is_file());
        let s2 = TalkStore::open(&d, &Keyring::Off);
        assert_eq!(s2.get_for("bo", "c9").unwrap().turns[0].text, "bo's");
        assert!(s2.get("c9").is_none() && s2.get_for("ana", "c9").is_none());
        assert_eq!(s2.get("c1").unwrap().name.as_deref(), Some("Garden"));
        // A person who leaves takes everything with them, files too.
        let mut s2 = s2;
        s2.forget_person("bo");
        assert!(s2.get_for("bo", "c9").is_none());
        assert_eq!(s2.people(), ["ana"]);
        assert!(!d.join("people/bo").exists());
        // The owner can't be forgotten.
        s2.forget_person(OWNER);
        assert!(s2.get("c1").is_some());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn chats_kept_before_people_belong_to_the_owner() {
        let d = tmp("migrate");
        std::fs::create_dir_all(&d).unwrap();
        // A chat file as an earlier version wrote it: no person in it.
        std::fs::write(
            d.join("old.json"),
            serde_json::json!({
                "chat": {"id": "old", "name": null, "at": 1, "seq": 1,
                         "turns": [t("a", true, "from before", 1, 1)]},
                "meta_seq": 0
            })
            .to_string(),
        )
        .unwrap();
        let s = TalkStore::open(&d, &Keyring::Off);
        assert_eq!(s.get("old").unwrap().turns[0].text, "from before");
        assert!(s.get_for("ana", "old").is_none());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_big_history_comes_in_pages() {
        let d = tmp("pages");
        let mut s = TalkStore::open(&d, &Keyring::Off);
        let long = "x".repeat(100_000);
        for i in 0..40 {
            s.put(
                &format!("c{i}"),
                None,
                vec![t(&format!("t{i}"), true, &long, i, 1)],
                false,
                0,
            )
            .unwrap();
        }
        let mut since = 0;
        let mut got = 0;
        loop {
            let p = s.since(since);
            got += p.chats.iter().map(|c| c.turns.len()).sum::<usize>();
            assert!(serde_json::to_vec(&p).unwrap().len() < 2_000_000);
            since = p.seq;
            if !p.more {
                break;
            }
        }
        assert_eq!(got, 40);
        let _ = std::fs::remove_dir_all(&d);
    }

    use crate::TEST_TOOL as TOOL;
    use crate::keyring::encode;

    /// A stand-in `secret-tool`: `lookup` answers with `lookup_script`,
    /// `store` appends what it was given to `<dir>/stored`.
    fn fake_tool(d: &Path, lookup_script: &str) -> PathBuf {
        use std::os::unix::fs::PermissionsExt;
        std::fs::create_dir_all(d).unwrap();
        let f = d.join("secret-tool");
        std::fs::write(
            &f,
            format!(
                "#!/bin/sh\ncase \"$1\" in\nlookup) {lookup_script};;\nstore) cat >> \"{}/stored\"; exit 0;;\nesac\n",
                d.display()
            ),
        )
        .unwrap();
        std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o755)).unwrap();
        f
    }

    #[test]
    fn a_failed_keyring_lookup_never_makes_or_stores_a_key() {
        let _g = TOOL.lock().unwrap_or_else(|e| e.into_inner());
        let d = tmp("ringfail");
        // The real tool also exits 1 on errors, with the reason on stderr:
        // those, a locked wallet (exit 2) and a garbled answer are all
        // "locked", never "not found".
        for script in [
            "exit 2",
            "echo oops; exit 1",
            "echo 'Cannot get secret of a locked object' >&2; exit 1",
            "echo garbled; exit 0",
        ] {
            let f = fake_tool(&d, script);
            unsafe { std::env::set_var("MIMIR_SECRET_TOOL", &f) };
            assert!(Keyring::SecretService.key(true).is_err(), "{script}");
            assert!(!d.join("stored").exists(), "{script} stored a key");
        }
        // Encrypted chats on disk: even a plain "not found" makes no key.
        let f = fake_tool(&d, "exit 1");
        unsafe { std::env::set_var("MIMIR_SECRET_TOOL", &f) };
        assert!(Keyring::SecretService.key(false).is_err());
        assert!(!d.join("stored").exists());
        // Only a plain "not found" with no chats makes and stores one.
        let _ = Keyring::SecretService.key(true);
        assert!(d.join("stored").exists());
        unsafe { std::env::remove_var("MIMIR_SECRET_TOOL") };
    }

    #[test]
    fn a_keyring_that_fails_at_first_open_locks_the_chats_never_plain_files() {
        let _g = TOOL.lock().unwrap_or_else(|e| e.into_inner());
        let d = tmp("ringlocked");
        let f = fake_tool(&d, "echo 'no bus' >&2; exit 1");
        unsafe { std::env::set_var("MIMIR_SECRET_TOOL", &f) };
        let mut s = TalkStore::open(&d.join("talk"), &Keyring::SecretService);
        assert!(s.locked());
        assert!(
            s.put("c1", None, vec![t("u1", true, "hi", 1, 1)], false, 1)
                .is_err()
        );
        assert!(
            !d.join("talk/c1.json").exists(),
            "a chat was saved in plain"
        );
        unsafe { std::env::remove_var("MIMIR_SECRET_TOOL") };
    }

    #[test]
    fn a_pending_store_is_opened_on_the_unlock_thread() {
        let d = tmp("pending");
        let key = d.with_extension("key");
        let mut s = TalkStore::open(&d, &Keyring::File(key.clone()));
        s.put("c1", None, vec![t("u1", true, "hi", 1, 1)], false, 5)
            .unwrap();
        s.flush().unwrap();
        let mut p = TalkStore::pending(&d, &Keyring::File(key));
        assert!(
            p.locked() && p.list().is_empty(),
            "nothing opened on the caller's thread"
        );
        let mut un = Unlock::new(std::time::Duration::from_secs(60));
        un.step(&mut p);
        let t0 = std::time::Instant::now();
        while p.locked() && t0.elapsed() < std::time::Duration::from_secs(5) {
            std::thread::sleep(std::time::Duration::from_millis(10));
            un.step(&mut p);
        }
        assert_eq!(p.get("c1").unwrap().turns.len(), 1);
    }

    #[test]
    fn a_locked_store_opens_on_a_later_request_once_the_key_is_there() {
        let d = tmp("relock");
        let key = d.with_extension("key");
        let mut s = TalkStore::open(&d, &Keyring::File(key.clone()));
        s.put("c1", None, vec![t("u1", true, "hi", 1, 1)], false, 5)
            .unwrap();
        s.flush().unwrap();
        let saved = std::fs::read(&key).unwrap();
        std::fs::write(&key, encode(&[7u8; 32])).unwrap();
        let mut locked = TalkStore::open(&d, &Keyring::File(key.clone()));
        assert!(locked.locked());
        assert!(locked.put("c1", None, vec![], false, 6).is_err());
        std::fs::write(&key, saved).unwrap();
        // Asked again on a thread of its own: the caller never waits, and
        // a second ask inside the gap starts nothing.
        let mut un = Unlock::new(std::time::Duration::from_secs(60));
        un.step(&mut locked);
        assert!(un.pending.is_some());
        let t0 = std::time::Instant::now();
        while locked.locked() && t0.elapsed() < std::time::Duration::from_secs(5) {
            std::thread::sleep(std::time::Duration::from_millis(10));
            un.step(&mut locked);
        }
        assert!(!locked.locked());
        assert!(
            locked
                .put("c1", None, vec![t("u2", true, "more", 2, 1)], false, 7)
                .is_ok()
        );
        assert_eq!(locked.get("c1").unwrap().turns.len(), 2);
    }

    #[test]
    fn writes_are_batched_not_one_sync_per_change() {
        let d = tmp("batched");
        let mut s = TalkStore::open_with(
            &d,
            &Keyring::Off,
            Writer::spawn(std::time::Duration::from_millis(300)),
        );
        for i in 0..20 {
            s.put(
                "c",
                None,
                vec![t(&format!("u{i}"), true, "hi", i, 1)],
                false,
                i,
            )
            .unwrap();
        }
        // Nothing written yet: twenty changes, one write a moment later.
        assert!(!d.join("c.json").exists());
        let t0 = std::time::Instant::now();
        while !d.join("c.json").exists() && t0.elapsed() < std::time::Duration::from_secs(5) {
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
        let s2 = TalkStore::open(&d, &Keyring::Off);
        assert_eq!(s2.get("c").unwrap().turns.len(), 20);
        assert_eq!(s2.seq(), 20);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn without_a_keyring_program_settings_says_chats_are_unencrypted() {
        let _g = TOOL.lock().unwrap_or_else(|e| e.into_inner());
        let d = tmp("noprogram");
        unsafe { std::env::set_var("MIMIR_SECRET_TOOL", d.join("no-such-tool")) };
        let s = TalkStore::open(&d, &Keyring::SecretService);
        assert!(!s.locked());
        assert_eq!(s.notice(), Some(UNENCRYPTED));
        unsafe { std::env::remove_var("MIMIR_SECRET_TOOL") };
        // With a key the notice is gone.
        let key = d.with_extension("key");
        let s = TalkStore::open(&d.join("k"), &Keyring::File(key));
        assert_eq!(s.notice(), None);
    }
}
