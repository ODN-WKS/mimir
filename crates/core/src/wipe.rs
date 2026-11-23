//! One wiper for every file Mímir passes on: a PDF, a Word, Excel, PowerPoint
//! or LibreOffice document, a recording, a picture or a zip goes on without
//! its author, company, dates, editing history, location or comments.
//! Pictures sent on their own are cleaned by `pictures.rs`; this module also
//! cleans the pictures inside documents, without redrawing them.
//!
//! What a file says (its words, pages, cells, slides, sound) is never changed.
//! A file that can't be cleaned is refused; a kind Mímir doesn't know is
//! passed as it was, and says so.

use regex::{Captures, Regex};
use std::collections::{BTreeSet, HashMap};
use std::io::{Read, Write};
use std::sync::{LazyLock, Mutex};

/// Said when a known kind of file can't be cleaned.
pub const FAILED: &str = "This file's hidden details couldn't be removed, so it wasn't sent.";
/// Said when a file that still has hidden details would go to an online model.
pub const NOT_SENT_ONLINE: &str =
    "This file still has hidden details, so it wasn't sent to the online model.";
/// Said under a file whose kind Mímir can't clean yet.
pub const UNKNOWN_KIND: &str = "Mímir doesn't know how to clean this kind of file yet";

const ENTRY_CAP: usize = 64 * 1024 * 1024;
const TOTAL_CAP: usize = 256 * 1024 * 1024;
/// A zip inside a zip gets a smaller budget, so nesting can't multiply it.
const NESTED_CAP: usize = 64 * 1024 * 1024;
/// Said under a file that was signed.
const SIGNATURE: &str = "Its signature no longer matches after cleaning.";

/// A cleaned file.
#[derive(Debug, Clone)]
pub struct Wiped {
    pub bytes: Vec<u8>,
    /// What was taken out, in plain words (empty: nothing hidden was found).
    pub removed: Vec<String>,
    /// Set when the file could not be cleaned and goes on as it was: the line
    /// to show under it, saying why.
    pub kept_as_it_was: Option<String>,
    /// The file carried a signature, which no longer matches once cleaned.
    pub signed: bool,
}

impl Wiped {
    /// The one short line shown under the file.
    pub fn note(&self) -> String {
        match &self.kept_as_it_was {
            Some(line) => line.clone(),
            None if self.removed.is_empty() => "No hidden details found.".into(),
            None if self.signed => format!("Hidden details removed. {SIGNATURE}"),
            None => "Hidden details removed.".into(),
        }
    }
}

fn done(bytes: Vec<u8>, original: &[u8], what: &str) -> Wiped {
    let removed = if bytes != original {
        vec![what.to_string()]
    } else {
        vec![]
    };
    Wiped {
        bytes,
        removed,
        kept_as_it_was: None,
        signed: false,
    }
}

fn kept(bytes: &[u8], why: &str) -> Wiped {
    Wiped {
        bytes: bytes.to_vec(),
        removed: vec![],
        kept_as_it_was: Some(format!("Kept as it was: {why}.")),
        signed: false,
    }
}

const TEXT_KINDS: &[&str] = &[
    "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini", "log",
    "rs", "py", "js", "ts", "java", "kt", "c", "h", "cpp", "hpp", "go", "rb", "sh", "css", "sql",
    "tex", "html", "htm", "rst", "cfg", "conf", "xml", "xhtml", "opf", "ncx",
];

/// Heif and AVIF pictures: their `meta` box is the picture, not a tag.
const FTYP_PICTURES: &[&[u8]] = &[
    b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis", b"mif1", b"msf1", b"avif", b"avis",
];

fn looks_text(b: &[u8]) -> bool {
    b.starts_with(&[0xFF, 0xFE])
        || b.starts_with(&[0xFE, 0xFF])
        || !b[..b.len().min(8192)].contains(&0)
}

enum ZipKind {
    Office,
    Open,
    Plain,
}

/// What a zip is, from what is inside it (not from its name).
fn zip_kind(b: &[u8], depth: u32) -> Option<ZipKind> {
    let members = zip_read_at(b, depth).ok()?;
    if members.first().is_some_and(|m| {
        m.name == "mimetype" && m.data.starts_with(b"application/vnd.oasis.opendocument")
    }) {
        Some(ZipKind::Open)
    } else if members.iter().any(|m| m.name == "[Content_Types].xml") {
        Some(ZipKind::Office)
    } else {
        Some(ZipKind::Plain)
    }
}

fn is_signed(b: &[u8]) -> bool {
    if b.starts_with(b"%PDF") {
        return b.windows(10).any(|w| w == b"/ByteRange");
    }
    zip_read_at(b, 0).is_ok_and(|ms| {
        ms.iter().any(|m| {
            m.name.starts_with("_xmlsignatures/")
                || m.name == "META-INF/documentsignatures.xml"
                || m.name == "META-INF/macrosignatures.xml"
        })
    })
}

/// Cleans one file. Err (in plain words) when a kind Mímir knows can't be
/// cleaned: the file must not be passed on then.
pub fn wipe(name: &str, bytes: &[u8]) -> Result<Wiped, String> {
    wipe_deep(name, bytes, 0)
}

fn wipe_deep(name: &str, b: &[u8], depth: u32) -> Result<Wiped, String> {
    let mut w = wipe_kind(name, b, depth)?;
    if w.kept_as_it_was.is_none() && w.bytes != b && is_signed(b) {
        w.signed = true;
    }
    Ok(w)
}

fn wipe_kind(name: &str, b: &[u8], depth: u32) -> Result<Wiped, String> {
    let ext = name
        .rsplit_once('.')
        .map(|(_, e)| e.to_ascii_lowercase())
        .unwrap_or_default();
    let ext = ext.as_str();
    let fail = |_| FAILED.to_string();
    // What a file is comes from what is inside it; the name only helps
    // where the inside has no clear mark.
    if b.starts_with(b"%PDF") {
        return pdf(b)
            .map(|o| done(o, b, "Author, dates and program details"))
            .map_err(fail);
    }
    if is_jpeg(b) || b.starts_with(b"\x89PNG\r\n\x1a\n") || is_webp(b) {
        let o = media(b).ok().flatten().ok_or(FAILED)?;
        return Ok(done(o, b, "Location, camera and comment details"));
    }
    let zip_like = b.starts_with(b"PK\x03\x04") || b.starts_with(b"PK\x05\x06");
    let office = matches!(
        ext,
        "docx" | "xlsx" | "pptx" | "docm" | "xlsm" | "pptm" | "dotx" | "xltx" | "potx"
    );
    let open = matches!(ext, "odt" | "ods" | "odp" | "ott" | "ots" | "otp");
    let plain = matches!(ext, "zip" | "epub");
    if zip_like || office || open || plain {
        let kind = if zip_like {
            zip_kind(b, depth).unwrap_or(ZipKind::Plain)
        } else if office {
            ZipKind::Office
        } else if open {
            ZipKind::Open
        } else {
            ZipKind::Plain
        };
        return match kind {
            ZipKind::Office => {
                let o = office_zip(b, depth).map_err(fail)?;
                Ok(done(
                    o,
                    b,
                    "Author, company, dates, comments and editing history",
                ))
            }
            ZipKind::Open => {
                let o = open_doc_zip(b, depth).map_err(fail)?;
                Ok(done(o, b, "Author, dates, comments and editing history"))
            }
            ZipKind::Plain => {
                let (o, left) = plain_zip(b, depth).map_err(fail)?;
                let mut w = done(
                    o,
                    b,
                    "Hidden details of the files inside, dates and comment",
                );
                if left {
                    w.kept_as_it_was =
                        Some("Kept as it was: some files inside can't be cleaned yet.".to_string());
                }
                Ok(w)
            }
        };
    }
    if b.starts_with(b"fLaC") || (ext == "flac" && b.starts_with(b"ID3")) {
        let o = flac(b).map_err(fail)?;
        return Ok(done(o, b, "Tags"));
    }
    if b.len() >= 12 && &b[..4] == b"RIFF" && &b[8..12] == b"WAVE" {
        let o = wav(b).map_err(fail)?;
        return Ok(done(o, b, "Tags"));
    }
    let ftyp = b.len() > 12 && &b[4..8] == b"ftyp";
    if ftyp && FTYP_PICTURES.contains(&&b[8..12]) {
        return Ok(kept(b, "this kind of picture can't be cleaned yet"));
    }
    if matches!(ext, "m4a" | "m4b" | "mp4" | "m4v" | "mov") || ftyp {
        let o = mp4(b).map_err(fail)?;
        return Ok(done(o, b, "Tags, location and dates"));
    }
    if ext == "mp3" || b.starts_with(b"ID3") {
        return Ok(done(mp3(b), b, "Tags"));
    }
    if matches!(ext, "ogg" | "oga" | "opus") || b.starts_with(b"OggS") {
        return Ok(kept(
            b,
            "its tags can't be taken out of this kind of recording yet",
        ));
    }
    if b.starts_with(&[0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1]) {
        return Ok(kept(
            b,
            "old Word, Excel and PowerPoint files can't be cleaned yet",
        ));
    }
    if TEXT_KINDS.contains(&ext) && looks_text(b) {
        return Ok(Wiped {
            bytes: b.to_vec(),
            removed: vec![],
            kept_as_it_was: None,
            signed: false,
        });
    }
    Ok(kept(b, UNKNOWN_KIND))
}

// ------------------------------------------------------------------ PDF

/// Annotations made by a person (they carry a writer's name in /T).
const MARKUP: &[&[u8]] = &[
    b"Text",
    b"FreeText",
    b"Line",
    b"Square",
    b"Circle",
    b"Polygon",
    b"PolyLine",
    b"Highlight",
    b"Underline",
    b"Squiggly",
    b"StrikeOut",
    b"Stamp",
    b"Caret",
    b"Ink",
    b"Popup",
    b"FileAttachment",
    b"Sound",
    b"Redact",
];

/// How deep arrays and dictionaries may nest before the file is refused.
const PDF_NESTING: usize = 64;

/// Walks the file's own syntax (skipping strings, comments and stream
/// bodies) and says whether arrays or dictionaries nest too deeply.
fn pdf_too_nested(b: &[u8]) -> bool {
    let (mut i, mut depth) = (0usize, 0usize);
    while i < b.len() {
        match b[i] {
            b'[' => {
                depth += 1;
                i += 1;
            }
            b']' => {
                depth = depth.saturating_sub(1);
                i += 1;
            }
            b'<' if b.get(i + 1) == Some(&b'<') => {
                depth += 1;
                i += 2;
            }
            b'>' if b.get(i + 1) == Some(&b'>') => {
                depth = depth.saturating_sub(1);
                i += 2;
            }
            b'<' => {
                while i < b.len() && b[i] != b'>' {
                    i += 1;
                }
            }
            b'(' => {
                let mut open = 0usize;
                while i < b.len() {
                    match b[i] {
                        b'\\' => i += 1,
                        b'(' => open += 1,
                        b')' => {
                            open -= 1;
                            if open == 0 {
                                break;
                            }
                        }
                        _ => {}
                    }
                    i += 1;
                }
                i += 1;
            }
            b'%' => {
                while i < b.len() && b[i] != b'\n' && b[i] != b'\r' {
                    i += 1;
                }
            }
            b's' if b[i..].starts_with(b"stream") && depth == 0 => {
                // The body is data, not syntax: jump to its end.
                i += 6;
                match b[i..].windows(9).position(|w| w == b"endstream") {
                    Some(n) => i += n + 9,
                    None => return false,
                }
            }
            _ => i += 1,
        }
        if depth > PDF_NESTING {
            return true;
        }
    }
    false
}

fn pdf(bytes: &[u8]) -> Result<Vec<u8>, ()> {
    use lopdf::{Dictionary, Object};
    if pdf_too_nested(bytes) {
        return Err(());
    }
    let mut doc = lopdf::Document::load_mem(bytes).map_err(|_| ())?;
    if doc.is_encrypted() {
        return Err(());
    }
    // The Info dictionary, wherever it is, and the identifiers.
    let mut gone: Vec<lopdf::ObjectId> = vec![];
    if let Ok(Object::Reference(id)) = doc.trailer.get(b"Info") {
        gone.push(*id);
    }
    doc.trailer.remove(b"Info");
    doc.trailer.remove(b"ID");
    // Hidden data on any object: XMP, private data, and the author and
    // dates of notes; embedded files' dates.
    let mut params: Vec<lopdf::ObjectId> = vec![];
    for (id, obj) in doc.objects.iter_mut() {
        let dict: &mut Dictionary = match obj {
            Object::Dictionary(d) => d,
            Object::Stream(s) => &mut s.dict,
            _ => continue,
        };
        let is = |d: &Dictionary, k: &[u8], v: &[u8]| {
            d.get(k).ok().and_then(|o| o.as_name().ok()) == Some(v)
        };
        if is(dict, b"Type", b"Metadata") {
            gone.push(*id);
        }
        dict.remove(b"Metadata");
        dict.remove(b"PieceInfo");
        // A note's writer and dates. A form field's /T is its name, and stays.
        if let Some(kind) = dict
            .get(b"Subtype")
            .ok()
            .and_then(|o| o.as_name().ok())
            .map(|n| n.to_vec())
            && dict.has(b"Rect")
        {
            if MARKUP.contains(&kind.as_slice()) {
                dict.remove(b"T");
            }
            dict.remove(b"M");
            dict.remove(b"CreationDate");
        }
        if is(dict, b"Type", b"EmbeddedFile") {
            match dict.get_mut(b"Params") {
                Ok(Object::Dictionary(p)) => {
                    p.remove(b"CreationDate");
                    p.remove(b"ModDate");
                }
                Ok(Object::Reference(r)) => params.push(*r),
                _ => {}
            }
        }
    }
    for id in params {
        if let Some(Object::Dictionary(p)) = doc.objects.get_mut(&id) {
            p.remove(b"CreationDate");
            p.remove(b"ModDate");
        }
    }
    for id in gone {
        doc.objects.remove(&id);
    }
    // Older versions left behind by later saves (an old Info, old XMP).
    doc.prune_objects();
    let mut out = Vec::new();
    doc.save_to(&mut out).map_err(|_| ())?;
    // Proof it still opens, and nothing of the old file is left in it.
    lopdf::Document::load_mem(&out).map_err(|_| ())?;
    Ok(out)
}

// ------------------------------------------------------------------ zip

struct Member {
    name: String,
    data: Vec<u8>,
    /// Kept stored (not packed), as in the original.
    stored: bool,
    /// How the entry was written in the original: its name's own bytes, its
    /// permissions and so on, kept as they were.
    raw: Option<Raw>,
}

#[derive(Clone)]
struct Raw {
    name: Vec<u8>,
    utf8: bool,
    made_by: u16,
    internal: u16,
    external: u32,
}

fn le16(b: &[u8], i: usize) -> Option<usize> {
    Some(u16::from_le_bytes(b.get(i..i + 2)?.try_into().ok()?) as usize)
}
fn le32(b: &[u8], i: usize) -> Option<usize> {
    Some(u32::from_le_bytes(b.get(i..i + 4)?.try_into().ok()?) as usize)
}

#[cfg(test)]
fn zip_read(b: &[u8]) -> Result<Vec<Member>, ()> {
    zip_read_at(b, 0)
}

/// Reads a zip; one inside another gets a smaller size budget.
fn zip_read_at(b: &[u8], depth: u32) -> Result<Vec<Member>, ()> {
    let cap = if depth == 0 { TOTAL_CAP } else { NESTED_CAP };
    let from = b.len().saturating_sub(22 + 65_535);
    let eocd = (from..=b.len().checked_sub(22).ok_or(())?)
        .rev()
        .find(|i| b[*i..].starts_with(b"PK\x05\x06"))
        .ok_or(())?;
    let count = le16(b, eocd + 10).ok_or(())?;
    let mut at = le32(b, eocd + 16).ok_or(())?;
    let (mut out, mut total) = (vec![], 0usize);
    for _ in 0..count {
        if b.get(at..at + 4).ok_or(())? != b"PK\x01\x02" {
            return Err(());
        }
        let flags = le16(b, at + 8).ok_or(())?;
        let method = le16(b, at + 10).ok_or(())?;
        let (comp, size) = (le32(b, at + 20).ok_or(())?, le32(b, at + 24).ok_or(())?);
        let (nl, xl, cl) = (
            le16(b, at + 28).ok_or(())?,
            le16(b, at + 30).ok_or(())?,
            le16(b, at + 32).ok_or(())?,
        );
        let local = le32(b, at + 42).ok_or(())?;
        let raw_name = b.get(at + 46..at + 46 + nl).ok_or(())?.to_vec();
        let name = String::from_utf8_lossy(&raw_name).into_owned();
        let meta = Raw {
            name: raw_name,
            utf8: flags & 0x0800 != 0,
            made_by: le16(b, at + 4).ok_or(())? as u16,
            internal: le16(b, at + 36).ok_or(())? as u16,
            external: le32(b, at + 38).ok_or(())? as u32,
        };
        at += 46 + nl + xl + cl;
        // Locked, or too big for this reader: not cleaned, not passed on.
        if flags & 1 == 1 || comp == 0xFFFF_FFFF || size == 0xFFFF_FFFF || size > ENTRY_CAP {
            return Err(());
        }
        total += size;
        if total > cap {
            return Err(());
        }
        let h = b.get(local..local + 30).ok_or(())?;
        if &h[..4] != b"PK\x03\x04" {
            return Err(());
        }
        let start = local + 30 + le16(b, local + 26).ok_or(())? + le16(b, local + 28).ok_or(())?;
        let raw = b.get(start..start + comp).ok_or(())?;
        let data = match method {
            0 => raw.to_vec(),
            8 => {
                let mut v = Vec::new();
                flate2::read::DeflateDecoder::new(raw)
                    .take(ENTRY_CAP as u64 + 1)
                    .read_to_end(&mut v)
                    .map_err(|_| ())?;
                v
            }
            _ => return Err(()),
        };
        if data.len() != size {
            return Err(());
        }
        out.push(Member {
            name,
            data,
            stored: method == 0,
            raw: Some(meta),
        });
    }
    Ok(out)
}

/// A zip with the same members in the same order, every date fixed at
/// 1980-01-01, no comments, no extra fields.
fn zip_write(members: &[Member]) -> Result<Vec<u8>, ()> {
    let mut out: Vec<u8> = Vec::new();
    let mut central: Vec<u8> = Vec::new();
    for m in members {
        let mut crc = flate2::Crc::new();
        crc.update(&m.data);
        let crc = crc.sum();
        let (method, body) = if m.stored || m.data.is_empty() {
            (0u16, m.data.clone())
        } else {
            let mut e =
                flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::default());
            e.write_all(&m.data).map_err(|_| ())?;
            (8u16, e.finish().map_err(|_| ())?)
        };
        let name_bytes: &[u8] = match &m.raw {
            Some(r) => &r.name,
            None => m.name.as_bytes(),
        };
        let flags: u16 = match &m.raw {
            Some(r) if r.utf8 => 0x0800,
            Some(_) => 0,
            None if m.name.is_ascii() => 0,
            None => 0x0800,
        };
        let (made_by, internal, external) = match &m.raw {
            Some(r) => (r.made_by, r.internal, r.external),
            None => (20, 0, 0),
        };
        let off = u32::try_from(out.len()).map_err(|_| ())?;
        let (comp, size) = (
            u32::try_from(body.len()).map_err(|_| ())?,
            u32::try_from(m.data.len()).map_err(|_| ())?,
        );
        let nl = u16::try_from(name_bytes.len()).map_err(|_| ())?;
        let common = |v: &mut Vec<u8>| {
            v.extend(flags.to_le_bytes());
            v.extend(method.to_le_bytes());
            v.extend(0u16.to_le_bytes()); // time
            v.extend(0x0021u16.to_le_bytes()); // 1980-01-01
            v.extend(crc.to_le_bytes());
            v.extend(comp.to_le_bytes());
            v.extend(size.to_le_bytes());
            v.extend(nl.to_le_bytes());
            v.extend(0u16.to_le_bytes()); // extra
        };
        out.extend(b"PK\x03\x04");
        out.extend(20u16.to_le_bytes());
        common(&mut out);
        out.extend(name_bytes);
        out.extend(&body);
        central.extend(b"PK\x01\x02");
        central.extend(made_by.to_le_bytes());
        central.extend(20u16.to_le_bytes());
        common(&mut central);
        central.extend(0u16.to_le_bytes()); // comment
        central.extend(0u16.to_le_bytes()); // disk
        central.extend(internal.to_le_bytes());
        central.extend(external.to_le_bytes());
        central.extend(off.to_le_bytes());
        central.extend(name_bytes);
    }
    let n = u16::try_from(members.len()).map_err(|_| ())?;
    let cdir = u32::try_from(out.len()).map_err(|_| ())?;
    let csize = u32::try_from(central.len()).map_err(|_| ())?;
    out.extend(central);
    out.extend(b"PK\x05\x06");
    out.extend([0u8; 4]);
    out.extend(n.to_le_bytes());
    out.extend(n.to_le_bytes());
    out.extend(csize.to_le_bytes());
    out.extend(cdir.to_le_bytes());
    out.extend(0u16.to_le_bytes());
    // It must read back.
    zip_read_at(&out, 0)?;
    Ok(out)
}

/// The cleaned zip, and whether some file inside could not be cleaned.
fn plain_zip(b: &[u8], depth: u32) -> Result<(Vec<u8>, bool), ()> {
    if depth >= 3 {
        return Err(());
    }
    let mut members = zip_read_at(b, depth)?;
    let mut left = false;
    for m in members.iter_mut() {
        if m.name.ends_with('/') || m.data.is_empty() {
            continue;
        }
        let w = wipe_deep(&m.name, &m.data, depth + 1).map_err(|_| ())?;
        left |= w.kept_as_it_was.is_some();
        m.data = w.bytes;
    }
    Ok((zip_write(&members)?, left))
}

// ------------------------------------------------------------ XML helpers

/// A pattern, compiled the first time it is used and kept.
fn rx(pattern: &'static str) -> Regex {
    static CACHE: LazyLock<Mutex<HashMap<&'static str, Regex>>> =
        LazyLock::new(|| Mutex::new(HashMap::new()));
    let mut c = CACHE.lock().unwrap_or_else(|e| e.into_inner());
    c.entry(pattern)
        .or_insert_with(|| Regex::new(pattern).expect("fixed pattern"))
        .clone()
}

fn sub(s: &str, re: &'static str, with: &str) -> String {
    rx(re).replace_all(s, with).into_owned()
}

#[derive(Clone, Copy)]
enum Enc {
    Utf8,
    Utf16Le,
    Utf16Be,
}

/// An XML part's text and how it was written (UTF-8, or UTF-16 with a mark).
fn decode_xml(b: &[u8]) -> Option<(String, Enc)> {
    let (rest, enc) = match b {
        [0xFF, 0xFE, rest @ ..] => (rest, Enc::Utf16Le),
        [0xFE, 0xFF, rest @ ..] => (rest, Enc::Utf16Be),
        _ => return Some((std::str::from_utf8(b).ok()?.to_string(), Enc::Utf8)),
    };
    if rest.len() % 2 != 0 {
        return None;
    }
    let units = rest.chunks(2).map(|c| match enc {
        Enc::Utf16Be => u16::from_be_bytes([c[0], c[1]]),
        _ => u16::from_le_bytes([c[0], c[1]]),
    });
    let s: String = char::decode_utf16(units).collect::<Result<_, _>>().ok()?;
    Some((s, enc))
}

fn encode_xml(s: &str, enc: Enc) -> Vec<u8> {
    match enc {
        Enc::Utf8 => s.as_bytes().to_vec(),
        Enc::Utf16Le => [0xFF, 0xFE]
            .into_iter()
            .chain(s.encode_utf16().flat_map(|u| u.to_le_bytes()))
            .collect(),
        Enc::Utf16Be => [0xFE, 0xFF]
            .into_iter()
            .chain(s.encode_utf16().flat_map(|u| u.to_be_bytes()))
            .collect(),
    }
}

/// An XML part that reads back as XML.
fn xml_ok(b: &[u8]) -> bool {
    decode_xml(b).is_some_and(|(s, _)| roxmltree::Document::parse(&s).is_ok())
}

fn with_text(m: &mut Member, f: impl Fn(&str) -> String) {
    if let Some((s, enc)) = decode_xml(&m.data) {
        m.data = encode_xml(&f(&s), enc);
    }
}

/// Where a relationship's target is, from the folder of the part that owns it.
fn resolve(rels_name: &str, target: &str) -> String {
    if let Some(t) = target.strip_prefix('/') {
        return t.to_string();
    }
    let owner_dir = rels_name
        .rsplit_once("_rels/")
        .map(|(d, _)| d)
        .unwrap_or_default();
    let mut parts: Vec<&str> = owner_dir.split('/').filter(|p| !p.is_empty()).collect();
    for p in target.split('/') {
        match p {
            ".." => {
                parts.pop();
            }
            "." | "" => {}
            p => parts.push(p),
        }
    }
    parts.join("/")
}

/// Takes dropped parts out of the relationship lists and the type list.
fn forget_parts(members: &mut [Member], dropped: &BTreeSet<String>) {
    let rel = rx(r"<Relationship\b[^>]*/>");
    let target = rx(r#"Target="([^"]*)""#);
    let over = rx(r"<Override\b[^>]*/>");
    let part = rx(r#"PartName="/([^"]*)""#);
    let entry = rx(r"<manifest:file-entry\b[^>]*/>");
    let path = rx(r#"manifest:full-path="([^"]*)""#);
    for m in members.iter_mut() {
        let name = m.name.clone();
        if name.ends_with(".rels") {
            with_text(m, |s| {
                rel.replace_all(s, |c: &Captures| {
                    let hit = target
                        .captures(&c[0])
                        .is_some_and(|t| dropped.contains(&resolve(&name, &t[1])));
                    if hit { String::new() } else { c[0].to_string() }
                })
                .into_owned()
            });
        } else if name == "[Content_Types].xml" {
            with_text(m, |s| {
                over.replace_all(s, |c: &Captures| {
                    let hit = part
                        .captures(&c[0])
                        .is_some_and(|t| dropped.contains(&t[1]));
                    if hit { String::new() } else { c[0].to_string() }
                })
                .into_owned()
            });
        } else if name == "META-INF/manifest.xml" {
            with_text(m, |s| {
                entry
                    .replace_all(s, |c: &Captures| {
                        let hit = path.captures(&c[0]).is_some_and(|t| {
                            dropped.contains(t[1].trim_end_matches('/'))
                                || dropped
                                    .iter()
                                    .any(|d| d.ends_with('/') && t[1].starts_with(d.as_str()))
                        });
                        if hit { String::new() } else { c[0].to_string() }
                    })
                    .into_owned()
            });
        }
    }
}

// ---------------------------------------------------- Word, Excel, PowerPoint

const EMPTY_CORE: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"/>"#;

fn office_zip(b: &[u8], depth: u32) -> Result<Vec<u8>, ()> {
    if depth >= 3 {
        return Err(());
    }
    let mut members = zip_read_at(b, depth)?;
    // The core properties part, wherever the root relationships put it.
    let core_paths: BTreeSet<String> = members
        .iter()
        .find(|m| m.name == "_rels/.rels")
        .and_then(|m| decode_xml(&m.data))
        .map(|(s, _)| {
            rx(r"<Relationship\b[^>]*/>")
                .find_iter(&s)
                .filter(|r| rx(r#"Type="[^"]*core-properties""#).is_match(r.as_str()))
                .filter_map(|r| {
                    rx(r#"Target="([^"]*)""#)
                        .captures(r.as_str())
                        .map(|t| resolve("_rels/.rels", &t[1]))
                })
                .collect()
        })
        .unwrap_or_default();
    let mut dropped = BTreeSet::new();
    for m in &members {
        let n = m.name.as_str();
        if n == "docProps/custom.xml"
            || n.starts_with("docProps/thumbnail")
            || n == "word/people.xml"
        {
            dropped.insert(m.name.clone());
        }
    }
    members.retain(|m| !dropped.contains(&m.name));
    forget_parts(&mut members, &dropped);
    for m in members.iter_mut() {
        let n = m.name.clone();
        match n.as_str() {
            "docProps/core.xml" => m.data = EMPTY_CORE.as_bytes().to_vec(),
            _ if core_paths.contains(&n) || n.ends_with(".psmdcp") => {
                m.data = EMPTY_CORE.as_bytes().to_vec()
            }
            "docProps/app.xml" => with_text(m, |s| {
                sub(
                    s,
                    r"<(?:Company|Manager|Template|TotalTime|HyperlinkBase|Application|AppVersion)(?:\s[^>]*)?(?:/>|>[^<]*</(?:Company|Manager|Template|TotalTime|HyperlinkBase|Application|AppVersion)>)",
                    "",
                )
            }),
            _ => {}
        }
        if !n.ends_with(".xml") {
            if in_media(&n)
                && let Some(clean) = media(&m.data)?
            {
                m.data = clean;
            }
            continue;
        }
        if n.starts_with("word/") {
            with_text(m, |s| {
                let s = sub(s, r#"\sw:author="[^"]*""#, r#" w:author="""#);
                let s = sub(&s, r#"\sw:(initials|date)="[^"]*""#, "");
                // Editing session numbers and the exact (UTC) times.
                let s = sub(&s, r"(?s)<(?:\w+:)?rsids\s*>.*?</(?:\w+:)?rsids>", "");
                let s = sub(&s, r"<(?:\w+:)?rsids\s*/>", "");
                let s = sub(&s, r#"\s\w+:rsid\w*="[^"]*""#, "");
                sub(&s, r#"\s\w+:dateUtc="[^"]*""#, "")
            });
        } else if n.starts_with("xl/comments") {
            with_text(m, |s| sub(s, r"<((?:\w+:)?author)>[^<]*</", "<$1></"));
        } else if n.starts_with("xl/persons/") || n.starts_with("xl/threadedComments/") {
            with_text(m, |s| {
                let s = sub(s, r#"(displayName|userId|providerId)="[^"]*""#, r#"$1="""#);
                sub(&s, r#"\bdT="[^"]*""#, r#"dT="1980-01-01T00:00:00""#)
            });
        } else if n == "ppt/commentAuthors.xml" || n == "ppt/authors.xml" {
            with_text(m, |s| {
                sub(
                    s,
                    r#"\s(name|initials|userId|providerId)="[^"]*""#,
                    r#" $1="""#,
                )
            });
        } else if n.starts_with("ppt/comments/") {
            with_text(m, |s| {
                let s = sub(s, r#"\bdt="[^"]*""#, r#"dt="1980-01-01T00:00:00""#);
                sub(
                    &s,
                    r#"\bcreated="[^"]*""#,
                    r#"created="1980-01-01T00:00:00""#,
                )
            });
        } else if n.starts_with("xl/revisions/") {
            with_text(m, |s| {
                let s = sub(s, r#"\buserName="[^"]*""#, r#"userName="""#);
                let s = sub(
                    &s,
                    r#"\bdateTime="[^"]*""#,
                    r#"dateTime="1980-01-01T00:00:00Z""#,
                );
                if n.ends_with("/userNames.xml") {
                    sub(&s, r#"\bname="[^"]*""#, r#"name="""#)
                } else {
                    s
                }
            });
        }
    }
    Ok(zip_write(&members)?).and_then(|z| {
        let again = zip_read_at(&z, 0)?;
        again.iter().try_for_each(|m| {
            if m.name.ends_with(".xml") || m.name.ends_with(".rels") {
                xml_ok(&m.data).then_some(()).ok_or(())
            } else {
                Ok(())
            }
        })?;
        Ok(z)
    })
}

fn in_media(name: &str) -> bool {
    name.contains("/media/") || name.starts_with("Pictures/") || name.contains("/Pictures/")
}

// ------------------------------------------------------------ LibreOffice

const EMPTY_META: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" office:version="1.2"><office:meta/></office:document-meta>"#;

fn open_doc_zip(b: &[u8], depth: u32) -> Result<Vec<u8>, ()> {
    if depth >= 3 {
        return Err(());
    }
    let mut members = zip_read_at(b, depth)?;
    if !members.iter().any(|m| m.name == "content.xml") {
        return Err(());
    }
    let dropped: BTreeSet<String> = members
        .iter()
        .filter(|m| m.name.starts_with("Thumbnails/") && !m.name.ends_with('/'))
        .map(|m| m.name.clone())
        .chain(["Thumbnails/".to_string()])
        .collect();
    members.retain(|m| !dropped.contains(&m.name));
    forget_parts(&mut members, &dropped);
    for m in members.iter_mut() {
        let n = m.name.clone();
        let embedded = n.starts_with("Object ");
        if n == "meta.xml" || (embedded && n.ends_with("/meta.xml")) {
            m.data = EMPTY_META.as_bytes().to_vec();
        } else if n == "settings.xml" || (embedded && n.ends_with("/settings.xml")) {
            // The printer the document was last set up for.
            with_text(m, |s| {
                sub(
                    s,
                    r#"(<config:config-item\b[^>]*config:name="(?:PrinterName|PrinterSetup)"[^>]*>)[^<]*(</config:config-item>)"#,
                    "$1$2",
                )
            });
        } else if n == "content.xml"
            || n == "styles.xml"
            || (embedded && (n.ends_with("/content.xml") || n.ends_with("/styles.xml")))
        {
            // Who wrote a note or made a tracked change, and when.
            with_text(m, |s| {
                let s = sub(
                    s,
                    r"<dc:creator>[^<]*</dc:creator>",
                    "<dc:creator></dc:creator>",
                );
                let s = sub(
                    &s,
                    r"<dc:date>[^<]*</dc:date>",
                    "<dc:date>1980-01-01T00:00:00</dc:date>",
                );
                let s = sub(
                    &s,
                    r"<meta:creator-initials>[^<]*</meta:creator-initials>",
                    "<meta:creator-initials></meta:creator-initials>",
                );
                sub(
                    &s,
                    r"<meta:date-string>[^<]*</meta:date-string>",
                    "<meta:date-string></meta:date-string>",
                )
            });
        } else if in_media(&n)
            && let Some(clean) = media(&m.data)?
        {
            m.data = clean;
        }
    }
    let z = zip_write(&members)?;
    for m in zip_read_at(&z, 0)? {
        if m.name.ends_with(".xml") && !xml_ok(&m.data) {
            return Err(());
        }
    }
    Ok(z)
}

// --------------------------------------------------------------- pictures

fn is_jpeg(b: &[u8]) -> bool {
    b.starts_with(&[0xFF, 0xD8, 0xFF])
}
fn is_webp(b: &[u8]) -> bool {
    b.len() >= 12 && &b[..4] == b"RIFF" && &b[8..12] == b"WEBP"
}

/// A JPEG, PNG or WebP without its hidden parts (location, camera, comments,
/// XMP, time stamps), the pixels untouched. None when it isn't one of these,
/// or can't be read.
pub fn strip_image(b: &[u8]) -> Option<Vec<u8>> {
    if is_jpeg(b) {
        strip_jpeg(b)
    } else if b.starts_with(b"\x89PNG\r\n\x1a\n") {
        strip_png(b)
    } else if is_webp(b) {
        strip_webp(b)
    } else {
        None
    }
}

/// A picture inside a document, or sent as a file, cleaned: untouched pixels
/// when it can be, redrawn (turned the way it was meant to be seen) when its
/// camera turn would be lost. Ok(None): not a picture kind, left as it is.
fn media(b: &[u8]) -> Result<Option<Vec<u8>>, ()> {
    if !(is_jpeg(b) || b.starts_with(b"\x89PNG\r\n\x1a\n") || is_webp(b)) {
        return Ok(None);
    }
    if let Some(o) = strip_image(b) {
        return Ok(Some(o));
    }
    use base64::Engine as _;
    let p = crate::pictures::clean(b).ok_or(())?;
    base64::engine::general_purpose::STANDARD
        .decode(p.base64.as_bytes())
        .map(Some)
        .map_err(|_| ())
}

/// The turn a photo's EXIF asks for (1 when none).
fn exif_turn(app1: &[u8]) -> u16 {
    match app1.strip_prefix(b"Exif\0\0") {
        Some(t) => tiff_turn(t),
        None => 1,
    }
}

/// The same, from the bare TIFF data (as a WebP keeps it).
fn tiff_turn(t: &[u8]) -> u16 {
    if t.len() <= 8 {
        return 1;
    }
    let big = &t[..2] == b"MM";
    let u16_at = |i: usize| -> Option<u16> {
        let v: [u8; 2] = t.get(i..i + 2)?.try_into().ok()?;
        Some(if big {
            u16::from_be_bytes(v)
        } else {
            u16::from_le_bytes(v)
        })
    };
    let u32_at = |i: usize| -> Option<usize> {
        let v: [u8; 4] = t.get(i..i + 4)?.try_into().ok()?;
        Some(if big {
            u32::from_be_bytes(v)
        } else {
            u32::from_le_bytes(v)
        } as usize)
    };
    let find = || -> Option<u16> {
        let ifd = u32_at(4)?;
        for k in 0..u16_at(ifd)? as usize {
            let e = ifd + 2 + k * 12;
            if u16_at(e)? == 0x0112 {
                return u16_at(e + 8);
            }
        }
        None
    };
    find().unwrap_or(1)
}

fn strip_jpeg(b: &[u8]) -> Option<Vec<u8>> {
    let mut out = vec![0xFF, 0xD8];
    let mut i = 2;
    loop {
        if *b.get(i)? != 0xFF {
            return None;
        }
        let m = *b.get(i + 1)?;
        if m == 0xFF {
            i += 1;
            continue;
        }
        if m == 0xD9 {
            out.extend([0xFF, 0xD9]);
            return Some(out);
        }
        let len = u16::from_be_bytes([*b.get(i + 2)?, *b.get(i + 3)?]) as usize;
        let seg = b.get(i..i + 2 + len)?;
        if m == 0xDA {
            // The picture itself runs to the end marker; whatever is added
            // after it (a second picture, private data) is dropped.
            out.extend(seg);
            let mut j = i + 2 + len;
            while j + 1 < b.len() {
                if b[j] == 0xFF && b[j + 1] == 0xD9 {
                    out.extend(&b[i + 2 + len..j]);
                    out.extend([0xFF, 0xD9]);
                    return Some(out);
                }
                j += 1;
            }
            return None;
        }
        let payload = &seg[4..];
        let hidden = match m {
            0xE1 => {
                // A photo that is meant to be shown turned must be redrawn, not just stripped.
                if exif_turn(payload) != 1 {
                    return None;
                }
                true
            }
            0xFE | 0xED => true,
            0xE3..=0xE9 | 0xEB..=0xEC | 0xEF => true,
            _ => false,
        };
        if !hidden {
            out.extend(seg);
        }
        i += 2 + len;
    }
}

fn strip_png(b: &[u8]) -> Option<Vec<u8>> {
    const KEEP: &[&[u8; 4]] = &[
        b"IHDR", b"PLTE", b"IDAT", b"IEND", b"tRNS", b"gAMA", b"cHRM", b"sRGB", b"iCCP", b"sBIT",
        b"bKGD", b"pHYs", b"acTL", b"fcTL", b"fdAT",
    ];
    let mut out = b[..8].to_vec();
    let mut i = 8;
    while i + 12 <= b.len() {
        let len = u32::from_be_bytes(b[i..i + 4].try_into().ok()?) as usize;
        let end = i.checked_add(12)?.checked_add(len)?;
        let chunk = b.get(i..end)?;
        let ty: &[u8; 4] = chunk[4..8].try_into().ok()?;
        if KEEP.contains(&ty) {
            out.extend(chunk);
        }
        i = end;
        if ty == b"IEND" {
            return Some(out);
        }
    }
    None
}

fn strip_webp(b: &[u8]) -> Option<Vec<u8>> {
    let mut body = b"WEBP".to_vec();
    let mut i = 12;
    while i + 8 <= b.len() {
        let ty = &b[i..i + 4];
        let len = u32::from_le_bytes(b[i + 4..i + 8].try_into().ok()?) as usize;
        let end = i.checked_add(8)?.checked_add(len)?;
        let padded = end + (len & 1);
        let chunk = b.get(i..end)?;
        if ty == b"EXIF" {
            // A picture meant to be shown turned must be redrawn, not just stripped.
            let exif = &chunk[8..];
            if tiff_turn(exif.strip_prefix(b"Exif\0\0").unwrap_or(exif)) != 1 {
                return None;
            }
        } else if ty == b"XMP " {
        } else if ty == b"VP8X" && chunk.len() >= 9 {
            let mut c = chunk.to_vec();
            c[8] &= !0x0C;
            body.extend(c);
            if len & 1 == 1 {
                body.push(0);
            }
        } else {
            body.extend(chunk);
            if len & 1 == 1 {
                body.push(0);
            }
        }
        i = padded;
    }
    let mut out = b"RIFF".to_vec();
    out.extend(u32::try_from(body.len()).ok()?.to_le_bytes());
    out.extend(body);
    Some(out)
}

// ------------------------------------------------------------------ sound

/// An ID3v2 tag at the start, and an ID3v1 or APE tag at the end.
fn untag_ends(b: &[u8]) -> &[u8] {
    let mut b = b;
    if b.starts_with(b"ID3") && b.len() >= 10 {
        let size = b[6..10]
            .iter()
            .fold(0usize, |a, x| (a << 7) | (*x as usize & 0x7F));
        let footer = if b[5] & 0x10 != 0 { 10 } else { 0 };
        let total = 10 + size + footer;
        if total <= b.len() {
            b = &b[total..];
        }
    }
    if b.len() >= 128 && &b[b.len() - 128..b.len() - 125] == b"TAG" {
        b = &b[..b.len() - 128];
    }
    if b.len() >= 32 && &b[b.len() - 32..b.len() - 24] == b"APETAGEX" {
        let f = &b[b.len() - 32..];
        let size = u32::from_le_bytes([f[12], f[13], f[14], f[15]]) as usize;
        let flags = u32::from_le_bytes([f[20], f[21], f[22], f[23]]);
        let head = if flags & 0x8000_0000 != 0 { 32 } else { 0 };
        if size + head <= b.len() {
            b = &b[..b.len() - size - head];
        }
    }
    b
}

fn mp3(b: &[u8]) -> Vec<u8> {
    untag_ends(b).to_vec()
}

fn flac(b: &[u8]) -> Result<Vec<u8>, ()> {
    let mut b = b;
    if b.starts_with(b"ID3") {
        let tail = untag_ends(b);
        b = tail;
    }
    if !b.starts_with(b"fLaC") {
        return Err(());
    }
    let mut blocks: Vec<&[u8]> = vec![];
    let mut i = 4;
    loop {
        let h = *b.get(i).ok_or(())?;
        let n = b.get(i + 1..i + 4).ok_or(())?;
        let len = u32::from_be_bytes([0, n[0], n[1], n[2]]) as usize;
        let block = b.get(i..i + 4 + len).ok_or(())?;
        // Stream info and the seek table only; comments, pictures, cue sheets, notes go.
        if matches!(h & 0x7F, 0 | 3) {
            blocks.push(block);
        }
        i += 4 + len;
        if h & 0x80 != 0 {
            break;
        }
    }
    if blocks.first().map(|x| x[0] & 0x7F) != Some(0) {
        return Err(());
    }
    let mut out = b"fLaC".to_vec();
    let n = blocks.len();
    for (k, bl) in blocks.iter().enumerate() {
        let mut head = bl[0] & 0x7F;
        if k + 1 == n {
            head |= 0x80;
        }
        out.push(head);
        out.extend(&bl[1..]);
    }
    out.extend(&b[i..]);
    Ok(out)
}

fn wav(b: &[u8]) -> Result<Vec<u8>, ()> {
    let mut body = b"WAVE".to_vec();
    let mut i = 12;
    while i + 8 <= b.len() {
        let ty = &b[i..i + 4];
        let len = u32::from_le_bytes(b[i + 4..i + 8].try_into().map_err(|_| ())?) as usize;
        // A recording still being written has no length yet: it runs to the end.
        let open = len == 0xFFFF_FFFF;
        let end = if open {
            b.len()
        } else {
            i.checked_add(8)
                .and_then(|x| x.checked_add(len))
                .ok_or(())?
        };
        let chunk = b.get(i..end).ok_or(())?;
        let odd = !open && len & 1 == 1;
        if [&b"fmt "[..], b"data", b"fact", b"cue ", b"smpl", b"inst"].contains(&ty) {
            body.extend(chunk);
            if odd {
                body.push(0);
            }
        }
        i = end + usize::from(odd);
    }
    if !body.windows(4).any(|w| w == b"fmt ") {
        return Err(());
    }
    let mut out = b"RIFF".to_vec();
    out.extend(u32::try_from(body.len()).map_err(|_| ())?.to_le_bytes());
    out.extend(body);
    Ok(out)
}

/// MP4 and M4A: the tag, location and note boxes are blanked in place (the
/// box turns into empty "free" space of the same size, so nothing moves),
/// and the creation and change times are set to zero.
fn mp4(b: &[u8]) -> Result<Vec<u8>, ()> {
    let mut out = b.to_vec();
    blank_boxes(&mut out, 0, b.len(), 0)?;
    Ok(out)
}

fn blank_boxes(b: &mut [u8], start: usize, end: usize, depth: u32) -> Result<(), ()> {
    if depth > 6 {
        return Err(());
    }
    let mut i = start;
    while i + 8 <= end {
        let size32 = u32::from_be_bytes(b[i..i + 4].try_into().map_err(|_| ())?) as usize;
        let ty: [u8; 4] = b[i + 4..i + 8].try_into().map_err(|_| ())?;
        let (hdr, size) = match size32 {
            0 => (8, end - i),
            1 => {
                let big =
                    u64::from_be_bytes(b.get(i + 8..i + 16).ok_or(())?.try_into().map_err(|_| ())?);
                (16, usize::try_from(big).map_err(|_| ())?)
            }
            n => (8, n),
        };
        if size < hdr || i.checked_add(size).is_none_or(|e| e > end) {
            return Err(());
        }
        let stop = i.checked_add(size).ok_or(())?;
        match &ty {
            b"udta" | b"meta" | b"uuid" => {
                for x in &mut b[i + hdr..stop] {
                    *x = 0;
                }
                b[i + 4..i + 8].copy_from_slice(b"free");
            }
            b"moov" | b"trak" | b"mdia" => blank_boxes(b, i + hdr, stop, depth + 1)?,
            b"mvhd" | b"tkhd" | b"mdhd" => {
                let v = *b.get(i + hdr).ok_or(())?;
                let n = if v == 1 { 16 } else { 8 };
                for x in b.get_mut(i + hdr + 4..i + hdr + 4 + n).ok_or(())? {
                    *x = 0;
                }
            }
            _ => {}
        }
        i = stop;
    }
    Ok(())
}

// ------------------------------------------------------- the callers' side

/// One file sent in a chat message, cleaned (bytes and base64), with the line
/// to show under it; or why it was refused.
pub fn wipe_sent(f: &crate::proto::SentFile) -> Result<(crate::proto::SentFile, String), String> {
    wipe_sent_for(f, false)
}

/// The same; with `online`, a file that couldn't be cleaned (kept as it was)
/// is refused with `NOT_SENT_ONLINE` instead of being passed on.
pub fn wipe_sent_for(
    f: &crate::proto::SentFile,
    online: bool,
) -> Result<(crate::proto::SentFile, String), String> {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(f.base64.as_bytes())
        .map_err(|_| "A file didn't arrive whole. Send it again.".to_string())?;
    let w = wipe(&f.name, &bytes)?;
    if online && w.kept_as_it_was.is_some() {
        return Err(NOT_SENT_ONLINE.to_string());
    }
    let mut out = f.clone();
    out.base64 = crate::events::base64(&w.bytes);
    Ok((out, w.note()))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A real PDF carrying an author, a company, dates, a hidden XMP
    /// packet and a note with its writer's name.
    pub(crate) fn sample_pdf() -> Vec<u8> {
        use lopdf::content::{Content, Operation};
        use lopdf::{Document, Object, Stream, dictionary};
        let mut doc = Document::with_version("1.5");
        let pages = doc.new_object_id();
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Courier"
        });
        let res = doc.add_object(dictionary! { "Font" => dictionary! { "F1" => font } });
        let content = Content {
            operations: vec![
                Operation::new("BT", vec![]),
                Operation::new("Tf", vec!["F1".into(), 24.into()]),
                Operation::new("Td", vec![50.into(), 600.into()]),
                Operation::new("Tj", vec![Object::string_literal("Rent is due monthly")]),
                Operation::new("ET", vec![]),
            ],
        };
        let content = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
        let note = doc.add_object(dictionary! {
            "Type" => "Annot", "Subtype" => "Text",
            "Rect" => vec![0.into(), 0.into(), 10.into(), 10.into()],
            "T" => Object::string_literal("Annotator Anna"),
            "M" => Object::string_literal("D:20200101000000"),
            "Contents" => Object::string_literal("Check this"),
        });
        let page = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages, "Contents" => content,
            "Annots" => vec![note.into()],
        });
        doc.objects.insert(
            pages,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![page.into()], "Count" => 1,
                "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
                "Resources" => res,
            }),
        );
        let xmp = doc.add_object(Stream::new(
            dictionary! { "Type" => "Metadata", "Subtype" => "XML" },
            b"<x:xmpmeta><dc:creator>Alice Example</dc:creator></x:xmpmeta>".to_vec(),
        ));
        let root = doc.add_object(dictionary! {
            "Type" => "Catalog", "Pages" => pages, "Metadata" => xmp,
        });
        let info = doc.add_object(dictionary! {
            "Author" => Object::string_literal("Alice Example"),
            "Creator" => Object::string_literal("WordPro 9"),
            "Producer" => Object::string_literal("Acme PDF Maker"),
            "Company" => Object::string_literal("Acme Ltd"),
            "CreationDate" => Object::string_literal("D:20190101120000"),
        });
        doc.trailer.set("Root", root);
        doc.trailer.set("Info", info);
        let mut out = Vec::new();
        doc.save_to(&mut out).unwrap();
        out
    }

    fn mem(name: &str, data: &[u8]) -> Member {
        Member {
            name: name.into(),
            data: data.to_vec(),
            stored: false,
            raw: None,
        }
    }

    fn zip_of(parts: &[(&str, &[u8])]) -> Vec<u8> {
        let m: Vec<Member> = parts
            .iter()
            .map(|(n, d)| Member {
                stored: *n == "mimetype",
                ..mem(n, d)
            })
            .collect();
        zip_write(&m).unwrap()
    }

    fn unzip(b: &[u8]) -> Vec<(String, Vec<u8>)> {
        zip_read(b)
            .unwrap()
            .into_iter()
            .map(|m| (m.name, m.data))
            .collect()
    }

    fn all_bytes(b: &[u8]) -> String {
        let mut s = String::from_utf8_lossy(b).into_owned();
        for (n, d) in unzip(b) {
            s += &n;
            s += &String::from_utf8_lossy(&d);
        }
        s
    }

    fn jpeg_with_gps() -> Vec<u8> {
        let mut raw = Vec::new();
        image::RgbImage::from_pixel(8, 8, image::Rgb([200, 30, 30]))
            .write_to(
                &mut std::io::Cursor::new(&mut raw),
                image::ImageFormat::Jpeg,
            )
            .unwrap();
        let mut exif =
            b"Exif\0\0II*\0\x08\0\0\0\x01\0\x12\x01\x03\0\x01\0\0\0\x01\0\0\0\0\0".to_vec();
        exif.extend(b"GPS-SECRET-51.5N");
        let mut out = raw[..2].to_vec();
        out.extend([0xFF, 0xE1]);
        out.extend((exif.len() as u16 + 2).to_be_bytes());
        out.extend(exif);
        out.extend([0xFF, 0xFE, 0, 9]);
        out.extend(b"comment");
        out.extend(&raw[2..]);
        out
    }

    fn pixels(jpeg: &[u8]) -> Vec<u8> {
        image::load_from_memory(jpeg).unwrap().to_rgb8().into_raw()
    }

    fn text_of(xml: &[u8]) -> String {
        let s = String::from_utf8_lossy(xml).into_owned();
        let d = roxmltree::Document::parse(&s).unwrap();
        d.descendants()
            .filter(|n| n.is_text())
            .map(|n| n.text().unwrap())
            .collect()
    }

    fn docx() -> Vec<u8> {
        zip_of(&[
            (
                "[Content_Types].xml",
                br#"<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="x"/><Override PartName="/docProps/custom.xml" ContentType="y"/><Override PartName="/word/people.xml" ContentType="z"/></Types>"#,
            ),
            (
                "_rels/.rels",
                br#"<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="a" Target="word/document.xml"/><Relationship Id="r2" Type="b" Target="docProps/custom.xml"/><Relationship Id="r3" Type="c" Target="docProps/thumbnail.jpeg"/></Relationships>"#,
            ),
            (
                "docProps/core.xml",
                br#"<?xml version="1.0"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"><dc:creator>Alice Example</dc:creator><cp:lastModifiedBy>Bob Editor</cp:lastModifiedBy><dcterms:created>2019-01-01T00:00:00Z</dcterms:created></cp:coreProperties>"#,
            ),
            (
                "docProps/app.xml",
                br#"<?xml version="1.0"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Company>Acme Ltd</Company><Pages>1</Pages><TotalTime>99</TotalTime></Properties>"#,
            ),
            ("docProps/custom.xml", b"<Properties>secret-custom</Properties>"),
            ("docProps/thumbnail.jpeg", b"thumb-bytes"),
            ("word/people.xml", br#"<w15:people xmlns:w15="x"><w15:person w15:author="Carol Reviewer"/></w15:people>"#),
            (
                "word/document.xml",
                br#"<?xml version="1.0"?><w:document xmlns:w="w"><w:body><w:p><w:r><w:t>Hello world</w:t></w:r><w:ins w:id="1" w:author="Dave Tracker" w:date="2020-02-02T00:00:00Z"><w:r><w:t> added</w:t></w:r></w:ins></w:p></w:body></w:document>"#,
            ),
            (
                "word/comments.xml",
                br#"<?xml version="1.0"?><w:comments xmlns:w="w"><w:comment w:id="0" w:author="Erin Commenter" w:date="2021-03-03T00:00:00Z" w:initials="EC"><w:p><w:r><w:t>Nice</w:t></w:r></w:p></w:comment></w:comments>"#,
            ),
        ])
    }

    #[test]
    fn pdf_loses_author_company_dates_xmp_and_note_author() {
        let src = sample_pdf();
        let before = lopdf::Document::load_mem(&src)
            .unwrap()
            .extract_text(&[1])
            .unwrap();
        for hidden in [
            "Alice Example",
            "Acme Ltd",
            "WordPro",
            "Annotator Anna",
            "20190101",
        ] {
            assert!(String::from_utf8_lossy(&src).contains(hidden), "{hidden}");
        }
        let w = wipe("Lease.pdf", &src).unwrap();
        let out = String::from_utf8_lossy(&w.bytes).into_owned();
        for hidden in [
            "Alice Example",
            "Acme Ltd",
            "WordPro",
            "Acme PDF",
            "Annotator Anna",
            "20190101",
            "xmpmeta",
            "D:2020",
        ] {
            assert!(!out.contains(hidden), "{hidden} is still in the PDF");
        }
        let doc = lopdf::Document::load_mem(&w.bytes).unwrap();
        assert_eq!(doc.extract_text(&[1]).unwrap(), before);
        assert!(out.contains("Check this"), "the note's own words stay");
        assert_eq!(w.note(), "Hidden details removed.");
    }

    #[test]
    fn docx_loses_author_company_dates_comments_and_history() {
        let w = wipe("Plan.docx", &docx()).unwrap();
        let all = all_bytes(&w.bytes);
        for hidden in [
            "Alice Example",
            "Bob Editor",
            "Acme Ltd",
            "2019-01-01",
            "secret-custom",
            "thumb-bytes",
            "Carol Reviewer",
            "Dave Tracker",
            "2020-02-02",
            "Erin Commenter",
            "2021-03-03",
            "EC\"",
            "TotalTime",
            "thumbnail",
            "custom.xml",
            "people.xml",
        ] {
            assert!(!all.contains(hidden), "{hidden} is still in the document");
        }
        let names: Vec<String> = unzip(&w.bytes).into_iter().map(|x| x.0).collect();
        assert_eq!(
            names,
            [
                "[Content_Types].xml",
                "_rels/.rels",
                "docProps/core.xml",
                "docProps/app.xml",
                "word/document.xml",
                "word/comments.xml"
            ]
        );
        let m = unzip(&w.bytes);
        let get = |n: &str| m.iter().find(|x| x.0 == n).unwrap().1.clone();
        assert_eq!(text_of(&get("word/document.xml")), "Hello world added");
        assert_eq!(text_of(&get("word/comments.xml")), "Nice");
        assert!(String::from_utf8_lossy(&get("docProps/app.xml")).contains("<Pages>1</Pages>"));
        for (n, d) in &m {
            if n.ends_with(".xml") || n.ends_with(".rels") {
                roxmltree::Document::parse(std::str::from_utf8(d).unwrap()).unwrap();
            }
        }
    }

    #[test]
    fn xlsx_and_pptx_comment_authors_and_people_go() {
        let sheet = zip_of(&[
            ("[Content_Types].xml", br#"<Types xmlns="t"/>"#),
            ("xl/workbook.xml", br#"<workbook><sheets><sheet name="Budget" sheetId="1"/></sheets></workbook>"#),
            ("xl/comments1.xml", b"<comments><authors><author>Frank Finance</author></authors><commentList><comment ref=\"A1\" authorId=\"0\"><text><t>Check</t></text></comment></commentList></comments>"),
            ("xl/persons/person.xml", br#"<personList><person displayName="Grace Person" id="{1}" userId="grace@example.org" providerId="AD"/></personList>"#),
            ("docProps/app.xml", b"<Properties><Manager>Heidi Boss</Manager></Properties>"),
        ]);
        let w = wipe("Budget.xlsx", &sheet).unwrap();
        let all = all_bytes(&w.bytes);
        for hidden in [
            "Frank Finance",
            "Grace Person",
            "grace@example.org",
            "Heidi Boss",
        ] {
            assert!(!all.contains(hidden), "{hidden}");
        }
        assert!(all.contains("Budget") && all.contains("Check"));
        let deck = zip_of(&[
            ("[Content_Types].xml", br#"<Types xmlns="t"/>"#),
            ("ppt/commentAuthors.xml", br#"<p:cmAuthorLst xmlns:p="p"><p:cmAuthor id="0" name="Ivan Presenter" initials="IP" lastIdx="1" clrIdx="0"/></p:cmAuthorLst>"#),
            ("ppt/comments/comment1.xml", br#"<p:cmLst xmlns:p="p"><p:cm authorId="0" dt="2022-04-04T10:00:00.000"><p:text>Slide note</p:text></p:cm></p:cmLst>"#),
            ("ppt/slides/slide1.xml", br#"<p:sld xmlns:p="p" xmlns:a="a"><a:t name="Title">Quarterly review</a:t></p:sld>"#),
        ]);
        let w = wipe("Deck.pptx", &deck).unwrap();
        let all = all_bytes(&w.bytes);
        for hidden in ["Ivan Presenter", "IP\"", "2022-04-04"] {
            assert!(!all.contains(hidden), "{hidden}");
        }
        assert!(all.contains("Quarterly review") && all.contains("Slide note"));
    }

    #[test]
    fn pictures_inside_a_document_lose_their_location_keep_their_pixels() {
        let jpg = jpeg_with_gps();
        let z = zip_of(&[
            ("[Content_Types].xml", br#"<Types xmlns="t"/>"#),
            ("word/media/image1.jpeg", &jpg),
        ]);
        let w = wipe("Pics.docx", &z).unwrap();
        let out = unzip(&w.bytes).remove(1).1;
        assert!(!String::from_utf8_lossy(&out).contains("GPS-SECRET"));
        assert!(!String::from_utf8_lossy(&out).contains("comment"));
        assert_eq!(pixels(&out), pixels(&jpg));
        // A PNG's text chunks go too.
        let mut png = Vec::new();
        image::RgbImage::from_pixel(4, 4, image::Rgb([1, 2, 3]))
            .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
            .unwrap();
        let mut with_text = png[..8].to_vec();
        let ihdr_end = 8 + 25;
        with_text.extend(&png[8..ihdr_end]);
        with_text.extend([0, 0, 0, 16]);
        with_text.extend(b"tEXtAuthor\0Jane Doe!");
        with_text.extend([0, 0, 0, 0]);
        with_text.extend(&png[ihdr_end..]);
        let w = wipe("a.png", &with_text).unwrap();
        assert!(!String::from_utf8_lossy(&w.bytes).contains("Jane Doe"));
        assert_eq!(
            image::load_from_memory(&w.bytes)
                .unwrap()
                .to_rgb8()
                .into_raw(),
            image::load_from_memory(&png).unwrap().to_rgb8().into_raw()
        );
    }

    #[test]
    fn odt_loses_author_dates_thumbnail_and_change_authors() {
        let odt = zip_of(&[
            ("mimetype", b"application/vnd.oasis.opendocument.text"),
            ("meta.xml", br#"<?xml version="1.0"?><office:document-meta xmlns:office="o" xmlns:meta="m" xmlns:dc="d"><office:meta><meta:initial-creator>Judy Author</meta:initial-creator><dc:creator>Kim Editor</dc:creator><meta:creation-date>2018-05-05T00:00:00</meta:creation-date><meta:generator>LibreOffice/7</meta:generator><meta:user-defined meta:name="Owner">Leo</meta:user-defined></office:meta></office:document-meta>"#),
            ("Thumbnails/thumbnail.png", b"thumb-png-bytes"),
            ("META-INF/manifest.xml", br#"<manifest:manifest xmlns:manifest="m"><manifest:file-entry manifest:full-path="/" manifest:media-type="x"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/><manifest:file-entry manifest:full-path="Thumbnails/thumbnail.png" manifest:media-type="image/png"/></manifest:manifest>"#),
            ("content.xml", br#"<?xml version="1.0"?><office:document-content xmlns:office="o" xmlns:text="t" xmlns:dc="d"><office:body><text:p>Garden notes</text:p><office:annotation><dc:creator>Mia Reviewer</dc:creator><dc:date>2020-06-06T00:00:00</dc:date><text:p>Water more</text:p></office:annotation></office:body></office:document-content>"#),
        ]);
        let w = wipe("Notes.odt", &odt).unwrap();
        let all = all_bytes(&w.bytes);
        for hidden in [
            "Judy Author",
            "Kim Editor",
            "2018-05-05",
            "LibreOffice/7",
            "Leo",
            "thumb-png",
            "Thumbnails",
            "Mia Reviewer",
            "2020-06-06",
        ] {
            assert!(!all.contains(hidden), "{hidden}");
        }
        let m = unzip(&w.bytes);
        assert_eq!(m[0].0, "mimetype");
        assert_eq!(m[0].1, b"application/vnd.oasis.opendocument.text");
        assert!(zip_read(&w.bytes).unwrap()[0].stored);
        let content = &m.iter().find(|x| x.0 == "content.xml").unwrap().1;
        assert!(
            text_of(content).contains("Garden notes") && text_of(content).contains("Water more")
        );
        assert!(all.contains("content.xml"));
    }

    fn id3_mp3() -> (Vec<u8>, Vec<u8>) {
        let frames: Vec<u8> = (0..300u32).map(|i| (i % 251) as u8).collect();
        let body = b"TPE1\0\0\0\x0f\0\0\0Artist Name!!!";
        let mut b = b"ID3\x03\0\0".to_vec();
        let n = body.len();
        b.extend([0, 0, (n >> 7) as u8, (n & 0x7F) as u8]);
        b.extend(body);
        b.extend(&frames);
        let mut v1 = [0u8; 128];
        v1[..3].copy_from_slice(b"TAG");
        v1[3..13].copy_from_slice(b"Old Title\0");
        b.extend(v1);
        (b, frames)
    }

    #[test]
    fn mp3_loses_its_tags_keeps_its_sound() {
        let (src, frames) = id3_mp3();
        let w = wipe("song.mp3", &src).unwrap();
        assert_eq!(w.bytes, frames);
        assert!(!String::from_utf8_lossy(&w.bytes).contains("Artist"));
    }

    #[test]
    fn flac_and_wav_lose_comments_keep_sound() {
        let audio = b"\xFF\xF8audio-frames".to_vec();
        let mut f = b"fLaC".to_vec();
        f.extend([0, 0, 0, 34]);
        f.extend([7u8; 34]);
        let vc = b"\0\0\0\0\x0c\0\0\0ARTIST=Zoe!!";
        f.push(4);
        f.extend([0, 0, vc.len() as u8]);
        f.extend(vc);
        f.extend([0x86, 0, 0, 3, 1, 2, 3]);
        f.extend(&audio);
        let w = wipe("a.flac", &f).unwrap();
        assert!(!String::from_utf8_lossy(&w.bytes).contains("Zoe"));
        assert_eq!(w.bytes[4], 0x80, "stream info is now the last block");
        assert!(w.bytes.ends_with(&audio));
        assert_eq!(w.bytes.len(), 4 + 4 + 34 + audio.len());

        let mut body = b"WAVE".to_vec();
        for (id, data) in [
            (&b"fmt "[..], &[1u8; 16][..]),
            (b"LIST", b"INFOIART\x04\0\0\0Zoe!"),
            (b"data", b"PCMDATA!"),
        ] {
            body.extend(id);
            body.extend((data.len() as u32).to_le_bytes());
            body.extend(data);
        }
        let mut wav = b"RIFF".to_vec();
        wav.extend((body.len() as u32).to_le_bytes());
        wav.extend(body);
        let w = wipe("a.wav", &wav).unwrap();
        assert!(!String::from_utf8_lossy(&w.bytes).contains("Zoe"));
        assert!(w.bytes.ends_with(b"PCMDATA!") && w.bytes.windows(4).any(|x| x == b"fmt "));
        assert_eq!(
            u32::from_le_bytes(w.bytes[4..8].try_into().unwrap()) as usize,
            w.bytes.len() - 8
        );
    }

    fn bx(ty: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        let mut v = ((payload.len() + 8) as u32).to_be_bytes().to_vec();
        v.extend(ty);
        v.extend(payload);
        v
    }

    #[test]
    fn m4a_loses_title_artist_location_and_times() {
        let mut mvhd = vec![0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8];
        mvhd.extend([9u8; 88]);
        let tags = bx(
            b"meta",
            &bx(b"ilst", b"\xA9nam Secret Title \xA9xyz +51.5-0.1"),
        );
        let moov = bx(b"moov", &[bx(b"mvhd", &mvhd), bx(b"udta", &tags)].concat());
        let mut f = bx(b"ftyp", b"M4A \0\0\0\0M4A ");
        f.extend(moov);
        f.extend(bx(b"mdat", b"AUDIO-DATA"));
        let w = wipe("a.m4a", &f).unwrap();
        assert_eq!(w.bytes.len(), f.len(), "nothing moves");
        let s = String::from_utf8_lossy(&w.bytes).into_owned();
        assert!(!s.contains("Secret Title") && !s.contains("+51.5") && s.contains("AUDIO-DATA"));
        assert!(s.contains("free"));
        let at = w.bytes.windows(4).position(|x| x == b"mvhd").unwrap() + 8;
        assert_eq!(&w.bytes[at..at + 8], &[0u8; 8]);
        let mut again = w.bytes.clone();
        let n = again.len();
        blank_boxes(&mut again, 0, n, 0).unwrap();
    }

    #[test]
    fn zip_loses_its_comment_and_dates_and_wipes_what_is_inside() {
        let inner = docx();
        let mut z = zip_of(&[
            ("notes.txt", b"plain words"),
            ("Plan.docx", &inner),
            ("other.bin", b"\x01\x02"),
        ]);
        // A comment at the end, and a date in the first entry.
        let n = z.len();
        z[n - 2..].copy_from_slice(&13u16.to_le_bytes());
        z.extend(b"secret author");
        z[10..14].copy_from_slice(&[0x12, 0x34, 0x56, 0x78]);
        let w = wipe("all.zip", &z).unwrap();
        assert!(!String::from_utf8_lossy(&w.bytes).contains("secret author"));
        assert_eq!(&w.bytes[10..14], &[0, 0, 0x21, 0]);
        let m = unzip(&w.bytes);
        assert_eq!(
            m.iter().map(|x| x.0.as_str()).collect::<Vec<_>>(),
            ["notes.txt", "Plan.docx", "other.bin"]
        );
        assert_eq!(m[0].1, b"plain words");
        assert_eq!(m[2].1, b"\x01\x02");
        let inner_all = all_bytes(&m[1].1);
        assert!(!inner_all.contains("Alice Example") && !inner_all.contains("Dave Tracker"));
    }

    #[test]
    fn a_file_that_cant_be_cleaned_is_refused_not_passed_on() {
        for (name, bytes) in [
            ("a.pdf", b"%PDF-1.4 broken".to_vec()),
            ("a.docx", b"PK\x03\x04 not a zip really".to_vec()),
            ("a.zip", b"nothing".to_vec()),
            ("a.jpg", b"\xFF\xD8\xFF\xE1 cut".to_vec()),
            ("a.flac", b"fLaC\x00\x00".to_vec()),
        ] {
            assert_eq!(wipe(name, &bytes).unwrap_err(), FAILED, "{name}");
        }
        // A locked zip can't be looked into, so it is not sent.
        let mut z = zip_of(&[("a.txt", b"x")]);
        let at = z.windows(4).position(|w| w == b"PK\x01\x02").unwrap();
        z[at + 8] |= 1;
        assert!(wipe("a.zip", &z).is_err());
    }

    #[test]
    fn text_passes_unknown_is_kept_and_says_so() {
        let w = wipe("notes.txt", b"just words").unwrap();
        assert_eq!(w.bytes, b"just words");
        assert_eq!(w.kept_as_it_was, None);
        assert_eq!(w.note(), "No hidden details found.");
        let w = wipe("scan.heic", b"abc").unwrap();
        assert_eq!(w.bytes, b"abc");
        assert!(
            w.note()
                .starts_with("Kept as it was: Mímir doesn't know how to clean")
        );
        let w = wipe("a.ogg", b"OggS....").unwrap();
        assert!(w.kept_as_it_was.is_some());
    }

    #[test]
    fn chat_files_are_cleaned_before_they_are_read() {
        use base64::Engine as _;
        let b64 = base64::engine::general_purpose::STANDARD;
        let mk = |name: &str, b: &[u8]| mimir_proto::SentFile {
            name: name.into(),
            media_type: String::new(),
            base64: b64.encode(b),
        };
        let files = vec![
            mk("Lease.pdf", &sample_pdf()),
            mk("Bad.pdf", b"%PDF-broken"),
        ];
        let mut notes = vec![];
        let (sent, lines) = crate::service::wiped_chat_files(&files, &mut notes);
        assert_eq!(sent.len(), 1);
        assert_eq!(lines, ["Hidden details removed."]);
        let bytes = b64.decode(&sent[0].base64).unwrap();
        assert!(!String::from_utf8_lossy(&bytes).contains("Alice Example"));
        assert_eq!(notes.len(), 1);
        assert_eq!(
            (notes[0].name.as_str(), notes[0].note.as_str()),
            ("Bad.pdf", FAILED)
        );
    }

    // ----------------------------------------------------- M1b: review fixes

    fn form_pdf() -> Vec<u8> {
        use lopdf::{Document, Object, dictionary};
        let mut doc = Document::with_version("1.5");
        let pages = doc.new_object_id();
        let widget = doc.add_object(dictionary! {
            "Type" => "Annot", "Subtype" => "Widget", "FT" => "Tx",
            "T" => Object::string_literal("full_name"),
            "V" => Object::string_literal("Jo"),
            "Rect" => vec![0.into(), 0.into(), 10.into(), 10.into()],
        });
        let note = doc.add_object(dictionary! {
            "Type" => "Annot", "Subtype" => "Text",
            "T" => Object::string_literal("Annotator Anna"),
            "Contents" => Object::string_literal("Check this"),
            "Rect" => vec![0.into(), 0.into(), 10.into(), 10.into()],
        });
        let page = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages,
            "Annots" => vec![widget.into(), note.into()],
        });
        doc.objects.insert(
            pages,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![page.into()], "Count" => 1,
                "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            }),
        );
        let root = doc.add_object(dictionary! {
            "Type" => "Catalog", "Pages" => pages,
            "AcroForm" => dictionary! { "Fields" => vec![widget.into()] },
        });
        doc.trailer.set("Root", root);
        let mut out = Vec::new();
        doc.save_to(&mut out).unwrap();
        out
    }

    #[test]
    fn m1b_1_a_fillable_form_keeps_its_field_names() {
        let w = wipe("Form.pdf", &form_pdf()).unwrap();
        let out = String::from_utf8_lossy(&w.bytes).into_owned();
        assert!(out.contains("full_name"), "the field name is gone");
        assert!(!out.contains("Annotator Anna"), "a note's writer stays");
        let doc = lopdf::Document::load_mem(&w.bytes).unwrap();
        let named = doc
            .objects
            .values()
            .filter_map(|o| o.as_dict().ok())
            .any(|d| d.get(b"T").is_ok() && d.get(b"FT").is_ok());
        assert!(named);
    }

    #[test]
    fn m1b_2_an_old_info_object_left_by_a_later_save_is_gone() {
        use lopdf::{Object, dictionary};
        let mut doc = lopdf::Document::load_mem(&sample_pdf()).unwrap();
        let newer = doc.add_object(dictionary! {
            "Author" => Object::string_literal("Second Author"),
        });
        doc.trailer.set("Info", newer);
        let mut src = Vec::new();
        doc.save_to(&mut src).unwrap();
        let s = String::from_utf8_lossy(&src).into_owned();
        assert!(s.contains("Alice Example") && s.contains("Second Author"));
        let w = wipe("Lease.pdf", &src).unwrap();
        let out = String::from_utf8_lossy(&w.bytes).into_owned();
        for hidden in ["Alice Example", "Second Author", "Acme Ltd"] {
            assert!(!out.contains(hidden), "{hidden} is still in the PDF");
        }
    }

    #[test]
    fn m1b_3_a_box_with_a_huge_size_is_refused_not_looped() {
        let mut f = bx(b"ftyp", b"M4A \0\0\0\0M4A ");
        f.extend(1u32.to_be_bytes());
        f.extend(b"free");
        f.extend((u64::MAX - 5).to_be_bytes());
        f.extend([0u8; 16]);
        assert_eq!(wipe("a.m4a", &f).unwrap_err(), FAILED);
        let mut again = f.clone();
        let n = again.len();
        assert!(blank_boxes(&mut again, 0, n, 0).is_err());
    }

    /// A zip written by hand (stored entries): (raw name, flags, made by,
    /// external attributes, data).
    fn raw_zip(entries: &[(&[u8], u16, u16, u32, &[u8])]) -> Vec<u8> {
        let (mut out, mut central) = (Vec::new(), Vec::new());
        for (name, flags, by, ext, data) in entries {
            let mut crc = flate2::Crc::new();
            crc.update(data);
            let off = out.len() as u32;
            let tail = |v: &mut Vec<u8>| {
                v.extend(flags.to_le_bytes());
                v.extend(0u16.to_le_bytes());
                v.extend(0x1234u16.to_le_bytes());
                v.extend(0x5678u16.to_le_bytes());
                v.extend(crc.sum().to_le_bytes());
                v.extend((data.len() as u32).to_le_bytes());
                v.extend((data.len() as u32).to_le_bytes());
                v.extend((name.len() as u16).to_le_bytes());
                v.extend(0u16.to_le_bytes());
            };
            out.extend(b"PK\x03\x04");
            out.extend(20u16.to_le_bytes());
            tail(&mut out);
            out.extend(*name);
            out.extend(*data);
            central.extend(b"PK\x01\x02");
            central.extend(by.to_le_bytes());
            central.extend(20u16.to_le_bytes());
            tail(&mut central);
            central.extend([0u8; 2 + 2 + 2]);
            central.extend(ext.to_le_bytes());
            central.extend(off.to_le_bytes());
            central.extend(*name);
        }
        let n = entries.len() as u16;
        let (cdir, csize) = (out.len() as u32, central.len() as u32);
        out.extend(central);
        out.extend(b"PK\x05\x06\0\0\0\0");
        out.extend(n.to_le_bytes());
        out.extend(n.to_le_bytes());
        out.extend(csize.to_le_bytes());
        out.extend(cdir.to_le_bytes());
        out.extend(0u16.to_le_bytes());
        out
    }

    /// (raw name, flags, made by, external attributes) read straight from the
    /// central directory.
    fn central(b: &[u8]) -> Vec<(Vec<u8>, u16, u16, u32)> {
        let eocd = b.windows(4).rposition(|w| w == b"PK\x05\x06").unwrap();
        let n = u16::from_le_bytes([b[eocd + 10], b[eocd + 11]]);
        let mut at = u32::from_le_bytes(b[eocd + 16..eocd + 20].try_into().unwrap()) as usize;
        let mut out = vec![];
        for _ in 0..n {
            let u16_at = |i: usize| u16::from_le_bytes([b[i], b[i + 1]]);
            let (nl, xl, cl) = (
                u16_at(at + 28) as usize,
                u16_at(at + 30) as usize,
                u16_at(at + 32) as usize,
            );
            out.push((
                b[at + 46..at + 46 + nl].to_vec(),
                u16_at(at + 8),
                u16_at(at + 4),
                u32::from_le_bytes(b[at + 38..at + 42].try_into().unwrap()),
            ));
            at += 46 + nl + xl + cl;
        }
        out
    }

    #[test]
    fn m1b_4_zip_names_flags_and_permissions_survive_byte_for_byte() {
        let cp437 = b"caf\x82.txt".to_vec();
        let src = raw_zip(&[
            (&cp437, 0, 0x031E, 0o100644 << 16, b"coffee"),
            (b"run.sh", 0, 0x031E, 0o100755 << 16, b"#!/bin/sh\n"),
            (
                "na\u{ef}ve.txt".as_bytes(),
                0x0800,
                0x031E,
                0o100600 << 16,
                b"x",
            ),
        ]);
        let w = wipe("files.zip", &src).unwrap();
        assert_eq!(central(&w.bytes), central(&src));
        let m = unzip(&w.bytes);
        assert_eq!(m[0].1, b"coffee");
        assert_eq!(m[1].1, b"#!/bin/sh\n");
        assert!(
            w.bytes.windows(cp437.len()).any(|x| x == cp437),
            "the name's own bytes are kept"
        );
    }

    fn office_with(extra: &[(&str, &[u8])]) -> Vec<u8> {
        let mut parts: Vec<(&str, &[u8])> = vec![(
            "[Content_Types].xml",
            br#"<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>"#,
        )];
        parts.extend(extra.iter().cloned());
        zip_of(&parts)
    }

    #[test]
    fn m1b_5a_core_properties_found_through_the_root_relationships() {
        let z = office_with(&[
            (
                "_rels/.rels",
                br#"<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="package/services/metadata/core-properties/abc123.psmdcp"/></Relationships>"#,
            ),
            (
                "package/services/metadata/core-properties/abc123.psmdcp",
                br#"<?xml version="1.0"?><coreProperties xmlns="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:creator>Psm Author</dc:creator><lastModifiedBy>Psm Editor</lastModifiedBy></coreProperties>"#,
            ),
            ("word/document.xml", br#"<w:document xmlns:w="w"><w:t>Hi</w:t></w:document>"#),
        ]);
        let w = wipe("a.docx", &z).unwrap();
        let all = all_bytes(&w.bytes);
        assert!(!all.contains("Psm Author") && !all.contains("Psm Editor"));
        assert!(all.contains("Hi"));
    }

    #[test]
    fn m1b_5b_word_editing_session_ids_and_utc_dates_go() {
        let z = office_with(&[
            (
                "word/document.xml",
                br#"<w:document xmlns:w="w" xmlns:w16du="d"><w:body><w:p w:rsidR="00A1B2C3" w:rsidRDefault="00D4E5F6"><w:ins w:id="1" w:author="Zed" w16du:dateUtc="2020-02-02T10:00:00Z"><w:r w:rsidRPr="0099AA11"><w:t>Hello</w:t></w:r></w:ins></w:p></w:body></w:document>"#,
            ),
            (
                "word/settings.xml",
                br#"<w:settings xmlns:w="w"><w:rsids><w:rsidRoot w:val="00BEEF01"/><w:rsid w:val="00CAFE02"/></w:rsids><w:zoom w:percent="100"/></w:settings>"#,
            ),
        ]);
        let w = wipe("a.docx", &z).unwrap();
        let all = all_bytes(&w.bytes);
        for hidden in [
            "00A1B2C3",
            "00D4E5F6",
            "0099AA11",
            "00BEEF01",
            "00CAFE02",
            "dateUtc",
            "2020-02-02",
            "rsids",
        ] {
            assert!(!all.contains(hidden), "{hidden}");
        }
        assert!(all.contains("Hello") && all.contains("zoom"));
    }

    #[test]
    fn m1b_5c_excel_revision_logs_lose_names_and_times() {
        let z = office_with(&[
            ("xl/workbook.xml", br#"<workbook><sheets><sheet name="Budget" sheetId="1"/></sheets></workbook>"#),
            (
                "xl/revisions/revisionHeaders.xml",
                br#"<headers xmlns="x"><header guid="{1}" dateTime="2021-05-05T09:30:00.000Z" userName="Rita Revisor" minRId="1"/></headers>"#,
            ),
            (
                "xl/revisions/userNames.xml",
                br#"<users xmlns="x"><userInfo guid="{1}" name="Rita Revisor" userName="Rita Revisor" dateTime="2021-05-05T09:30:00Z"/></users>"#,
            ),
        ]);
        let w = wipe("a.xlsx", &z).unwrap();
        let all = all_bytes(&w.bytes);
        for hidden in ["Rita Revisor", "2021-05-05"] {
            assert!(!all.contains(hidden), "{hidden}");
        }
        assert!(all.contains("Budget"));
    }

    #[test]
    fn m1b_5d_powerpoint_modern_comments_lose_authors_and_times() {
        let z = office_with(&[
            (
                "ppt/authors.xml",
                br#"<p188:authorLst xmlns:p188="a"><p188:author id="{9}" name="Sam Slide" initials="SS" userId="S::sam@example.org::123" providerId="AD"/></p188:authorLst>"#,
            ),
            (
                "ppt/comments/modernComment_100_1.xml",
                br#"<p188:cmLst xmlns:p188="a"><p188:cm id="{5}" authorId="{9}" created="2023-07-07T08:00:00.000"><p188:txBody><a:p xmlns:a="b"><a:r><a:t>Move this</a:t></a:r></a:p></p188:txBody></p188:cm></p188:cmLst>"#,
            ),
        ]);
        let w = wipe("a.pptx", &z).unwrap();
        let all = all_bytes(&w.bytes);
        for hidden in [
            "Sam Slide",
            "sam@example.org",
            "S::",
            "2023-07-07",
            "providerId=\"AD\"",
        ] {
            assert!(!all.contains(hidden), "{hidden}");
        }
        assert!(all.contains("Move this"));
    }

    fn webp_turned() -> Vec<u8> {
        let img = image::RgbImage::from_fn(4, 2, |x, _| image::Rgb([x as u8 * 60, 9, 9]));
        let mut raw = Vec::new();
        image::codecs::webp::WebPEncoder::new_lossless(&mut raw)
            .encode(img.as_raw(), 4, 2, image::ExtendedColorType::Rgb8)
            .unwrap();
        // RIFF header (12 bytes), then the picture chunk(s).
        let picture = &raw[12..];
        let mut vp8x = vec![0x08, 0, 0, 0, 3, 0, 0, 1, 0, 0];
        let mut body = b"WEBP".to_vec();
        body.extend(b"VP8X");
        body.extend(10u32.to_le_bytes());
        body.append(&mut vp8x);
        body.extend(picture);
        let exif = b"II*\0\x08\0\0\0\x01\0\x12\x01\x03\0\x01\0\0\0\x06\0\0\0\0\0\0\0";
        body.extend(b"EXIF");
        body.extend((exif.len() as u32).to_le_bytes());
        body.extend(exif);
        let mut out = b"RIFF".to_vec();
        out.extend((body.len() as u32).to_le_bytes());
        out.extend(body);
        out
    }

    #[test]
    fn m1b_8a_a_webp_made_to_be_shown_turned_is_redrawn_turned() {
        let src = webp_turned();
        let w = wipe("shot.webp", &src).unwrap();
        assert!(!w.bytes.windows(4).any(|x| x == b"EXIF"));
        let img = image::load_from_memory(&w.bytes).unwrap();
        assert_eq!(
            (img.width(), img.height()),
            (2, 4),
            "turned the way it is meant to be seen"
        );
    }

    #[test]
    fn m1b_8b_a_streamed_wav_with_an_open_length_is_cleaned() {
        let mut body = b"WAVE".to_vec();
        body.extend(b"fmt ");
        body.extend(16u32.to_le_bytes());
        body.extend([1u8; 16]);
        body.extend(b"LIST");
        body.extend(8u32.to_le_bytes());
        body.extend(b"INFOZoe!");
        body.extend(b"data");
        body.extend(0xFFFF_FFFFu32.to_le_bytes());
        body.extend(b"PCMDATA!");
        let mut wav = b"RIFF".to_vec();
        wav.extend(0xFFFF_FFFFu32.to_le_bytes());
        wav.extend(body);
        let w = wipe("live.wav", &wav).unwrap();
        assert!(!String::from_utf8_lossy(&w.bytes).contains("Zoe"));
        assert!(w.bytes.ends_with(b"PCMDATA!"));
    }

    #[test]
    fn m1b_8c_odf_printer_names_and_embedded_object_details_go() {
        let odt = zip_of(&[
            ("mimetype", b"application/vnd.oasis.opendocument.text"),
            ("meta.xml", br#"<office:document-meta xmlns:office="o"><office:meta/></office:document-meta>"#),
            ("content.xml", br#"<office:document-content xmlns:office="o" xmlns:text="t"><office:body><text:p>Hello</text:p></office:body></office:document-content>"#),
            (
                "settings.xml",
                br#"<office:document-settings xmlns:office="o" xmlns:config="c"><office:settings><config:config-item-set config:name="s"><config:config-item config:name="PrinterName" config:type="string">HP-Hallway-Secret</config:config-item><config:config-item config:name="PrinterSetup" config:type="base64Binary">SFAtSGFsbHdheQ==</config:config-item><config:config-item config:name="ZoomValue" config:type="int">100</config:config-item></config:config-item-set></office:settings></office:document-settings>"#,
            ),
            (
                "Object 1/meta.xml",
                br#"<office:document-meta xmlns:office="o" xmlns:dc="d" xmlns:meta="m"><office:meta><dc:creator>Embedded Author</dc:creator><meta:initial-creator>Embedded Init</meta:initial-creator></office:meta></office:document-meta>"#,
            ),
        ]);
        let w = wipe("a.odt", &odt).unwrap();
        let all = all_bytes(&w.bytes);
        for hidden in [
            "HP-Hallway-Secret",
            "SFAtSGFsbHdheQ",
            "Embedded Author",
            "Embedded Init",
        ] {
            assert!(!all.contains(hidden), "{hidden}");
        }
        assert!(all.contains("ZoomValue") && all.contains("Hello"));
    }

    #[test]
    fn m1b_8d_a_zip_inside_a_zip_gets_a_smaller_size_budget() {
        let big = vec![0u8; 30 * 1024 * 1024];
        let inner = zip_of(&[("a.bin", &big), ("b.bin", &big), ("c.bin", &big)]);
        let outer = zip_of(&[("inner.zip", &inner)]);
        drop(big);
        assert_eq!(wipe("outer.zip", &outer).unwrap_err(), FAILED);
    }

    #[test]
    fn m1b_8e_utf16_xml_parts_are_cleaned_not_refused() {
        let xml = r#"<?xml version="1.0" encoding="UTF-16"?><w:comments xmlns:w="w"><w:comment w:id="0" w:author="Utf Sixteen" w:date="2021-03-03T00:00:00Z"><w:p><w:r><w:t>Nice</w:t></w:r></w:p></w:comment></w:comments>"#;
        let mut u16le = vec![0xFF, 0xFE];
        for c in xml.encode_utf16() {
            u16le.extend(c.to_le_bytes());
        }
        let z = office_with(&[("word/comments.xml", &u16le)]);
        let w = wipe("a.docx", &z).unwrap();
        let m = unzip(&w.bytes);
        let part = &m.iter().find(|x| x.0 == "word/comments.xml").unwrap().1;
        assert_eq!(&part[..2], &[0xFF, 0xFE], "still UTF-16");
        let text: String = char::decode_utf16(
            part[2..]
                .chunks(2)
                .map(|c| u16::from_le_bytes([c[0], c[1]])),
        )
        .map(|c| c.unwrap())
        .collect();
        assert!(!text.contains("Utf Sixteen") && !text.contains("2021-03-03"));
        assert!(text.contains("Nice"));
    }

    #[test]
    fn m1b_8f_a_pdf_nested_without_end_is_refused() {
        let mut b = b"%PDF-1.4\n1 0 obj\n".to_vec();
        b.extend(vec![b'['; 20_000]);
        b.extend(vec![b']'; 20_000]);
        b.extend(b"\nendobj\ntrailer\n<</Root 1 0 R>>\n%%EOF\n");
        assert_eq!(wipe("deep.pdf", &b).unwrap_err(), FAILED);
    }

    #[test]
    fn m1b_8g_a_signed_file_says_its_signature_no_longer_matches() {
        use lopdf::{Document, Object, dictionary};
        let mut doc = Document::load_mem(&sample_pdf()).unwrap();
        doc.add_object(dictionary! {
            "Type" => "Sig", "Filter" => "Adobe.PPKLite",
            "ByteRange" => vec![0.into(), 1.into(), 2.into(), 3.into()],
            "Contents" => Object::string_literal("sig"),
        });
        let mut b = Vec::new();
        doc.save_to(&mut b).unwrap();
        let w = wipe("signed.pdf", &b).unwrap();
        assert!(
            w.note()
                .contains("Its signature no longer matches after cleaning.")
        );
        let mut plain = docx();
        let mut m = zip_read(&plain).unwrap();
        m.push(mem("_xmlsignatures/sig1.xml", b"<Signature/>"));
        plain = zip_write(&m).unwrap();
        let w = wipe("signed.docx", &plain).unwrap();
        assert!(
            w.note()
                .contains("Its signature no longer matches after cleaning.")
        );
        let w = wipe("plain.docx", &docx()).unwrap();
        assert!(!w.note().contains("signature"));
    }

    #[test]
    fn m1b_6a_a_file_is_known_by_what_is_inside_not_by_its_name() {
        // A Word file with the wrong ending is still cleaned.
        for name in ["notes.txt", "photo.dat", "noext"] {
            let w = wipe(name, &docx()).unwrap();
            assert!(
                !all_bytes(&w.bytes).contains("Alice Example"),
                "{name} kept its author"
            );
            assert_eq!(w.kept_as_it_was, None, "{name}");
        }
        // A phone picture (HEIC) is kept whole, and says so, not blanked as a video.
        let heic = [
            bx(b"ftyp", b"heic\0\0\0\0mif1heic"),
            bx(b"meta", b"PICTURE-DATA"),
        ]
        .concat();
        let w = wipe("img.mp4", &heic).unwrap();
        assert_eq!(w.bytes, heic);
        assert!(w.kept_as_it_was.is_some());
        // Old Word, Excel and PowerPoint files, and text that is really something else.
        let mut ole = vec![0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];
        ole.extend(b"Author: Old Writer");
        assert!(wipe("old.txt", &ole).unwrap().kept_as_it_was.is_some());
        assert!(
            wipe("a.txt", b"hi\0\0there")
                .unwrap()
                .kept_as_it_was
                .is_some()
        );
        assert_eq!(wipe("a.txt", b"hi there").unwrap().kept_as_it_was, None);
        // A zip with something inside it that can't be cleaned says so too.
        let z = zip_of(&[("note.txt", b"fine"), ("old.doc", &ole)]);
        assert!(wipe("pack.zip", &z).unwrap().kept_as_it_was.is_some());
    }

    #[test]
    fn m1b_6b_a_file_still_hiding_details_is_not_sent_to_an_online_model() {
        use base64::Engine as _;
        let b64 = base64::engine::general_purpose::STANDARD;
        let mk = |name: &str, b: &[u8]| mimir_proto::SentFile {
            name: name.into(),
            media_type: String::new(),
            base64: b64.encode(b),
        };
        let files = vec![mk("Lease.pdf", &sample_pdf()), mk("scan.heic", b"abc")];
        // The PC's own model may read both.
        let mut notes = vec![];
        let (sent, _) = crate::service::wiped_chat_files_for(&files, &mut notes, false);
        assert_eq!((sent.len(), notes.len()), (2, 0));
        // An online model gets only the cleaned one, and the person is told.
        let mut notes = vec![];
        let (sent, lines) = crate::service::wiped_chat_files_for(&files, &mut notes, true);
        assert_eq!(sent.len(), 1);
        assert_eq!(lines, ["Hidden details removed."]);
        assert_eq!(notes.len(), 1);
        assert_eq!(
            (notes[0].name.as_str(), notes[0].note.as_str()),
            (
                "scan.heic",
                "This file still has hidden details, so it wasn't sent to the online model."
            )
        );
    }
}
