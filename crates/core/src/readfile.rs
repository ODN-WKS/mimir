//! Reads the files a phone sends with a chat message ("chat_files"): PDFs,
//! Word / LibreOffice / Excel / PowerPoint documents, books (epub), zip
//! archives, plain text and code, and recordings. What comes out is plain
//! words for the PC's model, and one plain line saying how much was read
//! ("Read all of it", "Read pages 1 to 40 of 120"): nothing is ever cut
//! without saying so. Pure Rust, no program is started for documents.
//!
//! A file's bytes are never kept: only the words read from it (with the
//! chat, per person, see `mimir_store::talk`).

use base64::Engine as _;
use mimir_proto::{FileNote, Picture, SentFile};
use std::io::Read as _;

/// The most one file may be (as sent), and how many go with a message.
pub const MAX_FILE_BYTES: usize = 10 * 1024 * 1024;
pub const MAX_FILES: usize = 3;
/// What the PC's own free model is given from all files of one message: its
/// 16k-token memory (about 40,000 letters) less the chat and the answer.
pub const FREE_CHARS: usize = 30_000;
/// What Claude is given (its memory is far bigger).
pub const PAID_CHARS: usize = 120_000;
/// A scan: this many pages are looked at as pictures.
pub const SCAN_PAGES: usize = 3;
/// Most text kept of one file while reading it (a guard, not what is used).
const READ_CAP: usize = 3_000_000;
/// Most a zip member may unpack to, and all of them together.
const MEMBER_CAP: usize = 24 * 1024 * 1024;
const UNPACK_CAP: usize = 48 * 1024 * 1024;

pub const NOT_READABLE: &str =
    "Couldn't read the text in this file (it may be a scan). Try a version with the text in it.";
const UNKNOWN_KIND: &str =
    "Couldn't read this kind of file. Save it as a PDF or a text file and send that.";

/// What was found in one file, in order, before it is fitted to the model.
#[derive(Debug, Clone, Default)]
pub struct Extracted {
    pub name: String,
    /// Pieces in order: PDF pages, sheets, slides, a zip's list then its
    /// text files; or one piece for everything else.
    pub pieces: Vec<String>,
    /// What a piece is called ("page", "sheet", "slide", "file"); "" when
    /// the file is one block.
    pub unit: &'static str,
    /// Pictures of scanned pages (or the picture itself) for a model that sees.
    pub pictures: Vec<Picture>,
    /// Said under the file when it was looked at as pictures.
    pub looked_at: Option<String>,
    /// Why nothing could be read, in plain words.
    pub problem: Option<String>,
}

/// One file as the model gets it, and the line the phone shows.
#[derive(Debug, Clone, PartialEq)]
pub struct Fitted {
    pub name: String,
    pub text: String,
    pub note: String,
}

impl Fitted {
    pub fn file_note(&self) -> FileNote {
        FileNote {
            name: self.name.clone(),
            note: self.note.clone(),
        }
    }
    /// The words that go in the message to the model: a line saying what
    /// the file is and how much was read, then what was read.
    pub fn for_model(&self) -> String {
        if self.text.trim().is_empty() {
            return format!("File \"{}\" was attached. {}", self.name, self.note);
        }
        format!(
            "File \"{}\" ({}):\n{}",
            self.name,
            self.note.trim_end_matches('.').to_lowercase_first(),
            self.text
        )
    }
}

trait LowerFirst {
    fn to_lowercase_first(&self) -> String;
}
impl LowerFirst for str {
    fn to_lowercase_first(&self) -> String {
        let mut c = self.chars();
        match c.next() {
            Some(f) => f.to_lowercase().chain(c).collect(),
            None => String::new(),
        }
    }
}

/// Reads one sent file (bytes as sent). `can_see`: the answering model can
/// look at pictures (so a scan may be read through its pages' pictures).
pub fn extract(f: &SentFile, can_see: bool) -> Extracted {
    let name = clean_name(&f.name);
    let mut out = Extracted {
        name: name.clone(),
        ..Extracted::default()
    };
    let bytes = match base64::engine::general_purpose::STANDARD.decode(f.base64.as_bytes()) {
        Ok(b) if b.len() <= MAX_FILE_BYTES => b,
        Ok(_) => {
            out.problem = Some(
                "This file is over 10 MB, so it wasn't read. Send a smaller part of it.".into(),
            );
            return out;
        }
        Err(_) => {
            out.problem = Some("This file didn't arrive whole. Send it again.".into());
            return out;
        }
    };
    if bytes.is_empty() {
        out.problem = Some("This file is empty. Pick another one.".into());
        return out;
    }
    let ext = name
        .rsplit_once('.')
        .map(|(_, e)| e.to_lowercase())
        .unwrap_or_default();
    let kind = f.media_type.to_lowercase();
    if bytes.starts_with(b"%PDF") || ext == "pdf" || kind == "application/pdf" {
        pdf(&bytes, can_see, &mut out);
    } else if bytes.starts_with(b"PK\x03\x04") || bytes.starts_with(b"PK\x05\x06") {
        match Zip::open(&bytes) {
            Some(z) => archive(&z, &ext, &mut out),
            None => out.problem = Some(UNKNOWN_KIND.into()),
        }
    } else if bytes.starts_with(b"\xFF\xD8\xFF") || bytes.starts_with(b"\x89PNG") {
        if can_see && bytes.len() <= 6 * 1024 * 1024 {
            let media = if bytes[0] == 0xFF {
                "image/jpeg"
            } else {
                "image/png"
            };
            out.pictures.push(Picture {
                media_type: media.into(),
                base64: base64::engine::general_purpose::STANDARD.encode(&bytes),
            });
            out.looked_at = Some("Looked at it as a picture.".into());
        } else {
            out.problem = Some("Pictures go through Photo or video in the + menu.".into());
        }
    } else if is_sound(&bytes, &ext, &kind) {
        sound(&bytes, &ext, &mut out);
    } else if matches!(
        ext.as_str(),
        "doc" | "xls" | "ppt" | "rtf" | "pages" | "numbers" | "key"
    ) || bytes.starts_with(b"\xD0\xCF\x11\xE0")
    {
        out.problem = Some(
            "Couldn't read this kind of file. Save it as a PDF, .docx or text file and send that."
                .into(),
        );
    } else {
        match plain_text(&bytes) {
            Some(t) if !t.trim().is_empty() => out.pieces.push(t),
            Some(_) => out.problem = Some("This file has no words in it.".into()),
            None => out.problem = Some(UNKNOWN_KIND.into()),
        }
    }
    out
}

/// Fits what was read from the files of one message into `budget` letters:
/// each gets an equal share, and a short file hands what it doesn't use to
/// the others. Every file gets its line saying how much was read.
pub fn fit(items: &[Extracted], budget: usize) -> Vec<Fitted> {
    let sizes: Vec<usize> = items
        .iter()
        .map(|e| e.pieces.iter().map(|p| p.chars().count()).sum())
        .collect();
    let mut share = vec![0usize; items.len()];
    let mut left = budget;
    let mut open: Vec<usize> = (0..items.len()).filter(|i| sizes[*i] > 0).collect();
    // Water-filling: files smaller than an equal share keep all they need.
    while !open.is_empty() {
        let each = left / open.len();
        let small: Vec<usize> = open.iter().copied().filter(|i| sizes[*i] <= each).collect();
        if small.is_empty() {
            for i in &open {
                share[*i] = each;
            }
            break;
        }
        for i in &small {
            share[*i] = sizes[*i];
            left -= sizes[*i];
        }
        open.retain(|i| !small.contains(i));
    }
    items
        .iter()
        .enumerate()
        .map(|(i, e)| fit_one(e, share[i]))
        .collect()
}

fn fit_one(e: &Extracted, share: usize) -> Fitted {
    let name = e.name.clone();
    if e.pieces.iter().all(|p| p.trim().is_empty()) {
        let note = match (&e.looked_at, &e.problem) {
            (Some(l), _) => l.clone(),
            (None, Some(p)) => p.clone(),
            (None, None) => NOT_READABLE.to_string(),
        };
        return Fitted {
            name,
            text: String::new(),
            note,
        };
    }
    let total = e.pieces.len();
    let mut text = String::new();
    let mut used = 0usize;
    let mut taken = 0usize;
    let mut cut_inside = false;
    for p in &e.pieces {
        let n = p.chars().count();
        // A piece joins as a whole; the first piece may be cut to fit.
        if used + n <= share {
            push_piece(&mut text, p);
            used += n;
            taken += 1;
        } else {
            if taken == 0 && share > 200 {
                push_piece(&mut text, &p.chars().take(share).collect::<String>());
                taken = 1;
                cut_inside = true;
            }
            break;
        }
    }
    let all = taken == total && !cut_inside;
    let note = if all {
        match &e.looked_at {
            Some(l) => format!("Read all of it. {l}"),
            None => "Read all of it".into(),
        }
    } else {
        match e.unit {
            "" => "Read the first part of it".into(),
            "file" => format!(
                "Read the list and {} of {} files",
                taken.saturating_sub(1),
                total.saturating_sub(1)
            ),
            u if taken <= 1 => format!(
                "Read {u} 1{} of {total}",
                if cut_inside { " (the first part)" } else { "" }
            ),
            u => format!("Read {u}s 1 to {taken} of {total}"),
        }
    };
    let note = match (all, &e.looked_at) {
        (false, Some(l)) => format!("{note}. {l}"),
        _ => note,
    };
    Fitted { name, text, note }
}

fn push_piece(text: &mut String, p: &str) {
    if !text.is_empty() {
        text.push_str("\n\n");
    }
    text.push_str(p.trim());
}

/// A name safe to show and to put in a message: no folders, no controls.
fn clean_name(n: &str) -> String {
    let base = n.rsplit(['/', '\\']).next().unwrap_or("file");
    let s: String = base
        .chars()
        .filter(|c| !c.is_control() && *c != '"')
        .take(120)
        .collect();
    if s.trim().is_empty() {
        "file".into()
    } else {
        s
    }
}

// ---------------------------------------------------------------- text

/// Plain words from text, code, csv, markdown, html, json; None for
/// anything that isn't words (binary).
pub fn plain_text(bytes: &[u8]) -> Option<String> {
    let bytes = &bytes[..bytes.len().min(READ_CAP * 2)];
    let s = if bytes.starts_with(b"\xFF\xFE") || bytes.starts_with(b"\xFE\xFF") {
        let le = bytes[0] == 0xFF;
        let units: Vec<u16> = bytes[2..]
            .as_chunks::<2>()
            .0
            .iter()
            .map(|c| {
                if le {
                    u16::from_le_bytes(*c)
                } else {
                    u16::from_be_bytes(*c)
                }
            })
            .collect();
        String::from_utf16_lossy(&units)
    } else {
        let b = bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes);
        match std::str::from_utf8(b) {
            Ok(s) => s.to_string(),
            Err(e) if e.error_len().is_none() && e.valid_up_to() + 4 > b.len() => {
                String::from_utf8_lossy(&b[..e.valid_up_to()]).into_owned()
            }
            Err(_) => {
                // An older single-byte text file: fine if it has no NULs
                // and hardly any control characters.
                let odd = b
                    .iter()
                    .filter(|c| **c < 9 || (**c > 13 && **c < 32))
                    .count();
                if b.contains(&0) || odd * 50 > b.len() {
                    return None;
                }
                b.iter().map(|c| *c as char).collect()
            }
        }
    };
    if s.contains('\u{0}') {
        return None;
    }
    Some(tidy(&s.replace("\r\n", "\n").replace('\r', "\n")))
}

/// Trailing spaces and runs of blank lines out; capped.
fn tidy(s: &str) -> String {
    let mut out = String::new();
    let mut blank = 0;
    for line in s.lines() {
        let l = line.trim_end();
        if l.is_empty() {
            blank += 1;
            if blank > 1 {
                continue;
            }
        } else {
            blank = 0;
        }
        out.push_str(l);
        out.push('\n');
        if out.len() > READ_CAP {
            break;
        }
    }
    out.trim().to_string()
}

fn strip_tags(html: &str) -> String {
    let mut out = String::with_capacity(html.len() / 2);
    let mut chars = html.chars().peekable();
    let (mut tag, mut skip) = (String::new(), false);
    while let Some(c) = chars.next() {
        if c == '<' {
            tag.clear();
            for t in chars.by_ref() {
                if t == '>' {
                    break;
                }
                tag.push(t);
            }
            let t = tag.trim_start_matches('/').to_lowercase();
            let t = t
                .split(|c: char| c.is_whitespace() || c == '/')
                .next()
                .unwrap_or("");
            match t {
                "script" | "style" => skip = !tag.starts_with('/'),
                "p" | "div" | "br" | "li" | "tr" | "h1" | "h2" | "h3" | "h4" | "h5" | "h6"
                    if !out.ends_with('\n') =>
                {
                    out.push('\n')
                }
                _ => {}
            }
        } else if !skip {
            out.push(c);
        }
    }
    let out = out
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&");
    tidy(&out)
}

// ----------------------------------------------------------------- pdf

fn pdf(bytes: &[u8], can_see: bool, out: &mut Extracted) {
    out.unit = "page";
    let doc = match lopdf::Document::load_mem(bytes) {
        Ok(d) => d,
        Err(_) => {
            out.problem =
                Some("Couldn't open this PDF. Try saving it again, or send another copy.".into());
            return;
        }
    };
    if doc.is_encrypted() {
        out.problem = Some(
            "This PDF is locked with a password, so it wasn't read. Send an unlocked copy.".into(),
        );
        return;
    }
    let pages = doc.get_pages();
    let numbers: Vec<u32> = pages.keys().copied().collect();
    let mut any = false;
    for n in &numbers {
        // More than any model can be given: the rest is counted, not read.
        if out.pieces.iter().map(|p| p.len()).sum::<usize>() > 400_000 {
            out.pieces.push("(not read)".into());
            continue;
        }
        let text = doc
            .extract_text(&[*n])
            .map(|t| tidy(&t.replace('\u{0}', "")))
            .unwrap_or_default();
        any |= !text.trim().is_empty();
        out.pieces.push(text);
    }
    if numbers.is_empty() {
        out.problem =
            Some("Couldn't open this PDF. Try saving it again, or send another copy.".into());
        return;
    }
    if any {
        // A page with no words in the middle says so; empty pages at the
        // end stay empty (nothing was left out there).
        let last = out
            .pieces
            .iter()
            .rposition(|p| !p.trim().is_empty())
            .unwrap_or(0);
        for p in out.pieces.iter_mut().take(last) {
            if p.trim().is_empty() {
                *p = "(no words on this page)".into();
            }
        }
        return;
    }
    out.pieces.clear();
    // A scan: pages are pictures. A model that sees can look at the first few.
    if !can_see {
        out.problem = Some(NOT_READABLE.into());
        return;
    }
    for (_, id) in pages.iter().take(SCAN_PAGES) {
        let Ok(images) = doc.get_page_images(*id) else {
            continue;
        };
        // The biggest picture on the page is the scan; only plain JPEG ones
        // can be handed on as they are.
        let best = images
            .iter()
            .filter(|i| {
                i.filters
                    .as_ref()
                    .is_some_and(|f| f.len() == 1 && f[0] == "DCTDecode")
            })
            .max_by_key(|i| i.content.len());
        if let Some(i) = best
            && i.content.len() <= 6 * 1024 * 1024
            && i.content.starts_with(b"\xFF\xD8")
        {
            out.pictures.push(Picture {
                media_type: "image/jpeg".into(),
                base64: base64::engine::general_purpose::STANDARD.encode(i.content),
            });
        }
    }
    if out.pictures.is_empty() {
        out.problem = Some(NOT_READABLE.into());
    } else {
        out.looked_at = Some(format!(
            "It looks like a scan, so the first {} of {} pages were looked at as pictures",
            out.pictures.len(),
            numbers.len()
        ));
    }
}

// ------------------------------------------------------------------ zip

struct Entry {
    name: String,
    method: usize,
    comp: usize,
    size: usize,
    at: usize,
}

/// A zip file's members, read in place (no zip64, no passwords).
pub struct Zip<'a> {
    bytes: &'a [u8],
    entries: Vec<Entry>,
}

impl<'a> Zip<'a> {
    fn open(bytes: &'a [u8]) -> Option<Zip<'a>> {
        let u16_at = |i: usize| -> Option<usize> {
            Some(u16::from_le_bytes(bytes.get(i..i + 2)?.try_into().ok()?) as usize)
        };
        let u32_at = |i: usize| -> Option<usize> {
            Some(u32::from_le_bytes(bytes.get(i..i + 4)?.try_into().ok()?) as usize)
        };
        let from = bytes.len().saturating_sub(22 + 65_535);
        let eocd = (from..=bytes.len().checked_sub(22)?)
            .rev()
            .find(|i| bytes[*i..].starts_with(b"PK\x05\x06"))?;
        let count = u16_at(eocd + 10)?;
        let mut at = u32_at(eocd + 16)?;
        let mut entries = vec![];
        for _ in 0..count.min(20_000) {
            if bytes.get(at..at + 4)? != b"PK\x01\x02" {
                break;
            }
            let (method, comp, size) = (u16_at(at + 10)?, u32_at(at + 20)?, u32_at(at + 24)?);
            let (nl, xl, cl) = (u16_at(at + 28)?, u16_at(at + 30)?, u16_at(at + 32)?);
            let local = u32_at(at + 42)?;
            let name = String::from_utf8_lossy(bytes.get(at + 46..at + 46 + nl)?).into_owned();
            entries.push(Entry {
                name,
                method,
                comp,
                size,
                at: local,
            });
            at += 46 + nl + xl + cl;
        }
        Some(Zip { bytes, entries })
    }

    fn names(&self) -> impl Iterator<Item = &str> {
        self.entries.iter().map(|e| e.name.as_str())
    }

    fn has(&self, name: &str) -> bool {
        self.names().any(|n| n == name)
    }

    /// One member, unpacked (at most `cap` bytes; None if it is bigger).
    fn read(&self, name: &str, cap: usize) -> Option<Vec<u8>> {
        let e = self.entries.iter().find(|e| e.name == name)?;
        if e.size > cap || e.name.ends_with('/') {
            return None;
        }
        let h = self.bytes.get(e.at..e.at + 30)?;
        if &h[..4] != b"PK\x03\x04" {
            return None;
        }
        let nl = u16::from_le_bytes([h[26], h[27]]) as usize;
        let xl = u16::from_le_bytes([h[28], h[29]]) as usize;
        let start = e.at + 30 + nl + xl;
        let raw = self.bytes.get(start..start + e.comp)?;
        match e.method {
            0 => Some(raw.to_vec()),
            8 => {
                let mut v = Vec::new();
                flate2::read::DeflateDecoder::new(raw)
                    .take(cap as u64 + 1)
                    .read_to_end(&mut v)
                    .ok()?;
                (v.len() <= cap).then_some(v)
            }
            _ => None,
        }
    }

    fn text(&self, name: &str) -> Option<String> {
        String::from_utf8(self.read(name, MEMBER_CAP)?).ok()
    }
}

fn archive(z: &Zip, ext: &str, out: &mut Extracted) {
    let mimetype = z
        .read("mimetype", 200)
        .map(|m| String::from_utf8_lossy(&m).into_owned())
        .unwrap_or_default();
    if z.has("word/document.xml") {
        docx(z, out)
    } else if z.has("xl/workbook.xml") {
        xlsx(z, out)
    } else if z.has("ppt/presentation.xml") {
        pptx(z, out)
    } else if mimetype.starts_with("application/vnd.oasis.opendocument") && z.has("content.xml") {
        opendoc(z, &mimetype, out)
    } else if mimetype.starts_with("application/epub")
        || z.has("META-INF/container.xml") && ext == "epub"
    {
        epub(z, out)
    } else {
        listing(z, out)
    }
    if out.pieces.iter().all(|p| p.trim().is_empty())
        && out.problem.is_none()
        && out.looked_at.is_none()
    {
        out.problem = Some(NOT_READABLE.into());
    }
}

/// Any other zip: the list of what is inside, then the text files.
fn listing(z: &Zip, out: &mut Extracted) {
    out.unit = "file";
    let files: Vec<&Entry> = z
        .entries
        .iter()
        .filter(|e| !e.name.ends_with('/'))
        .collect();
    let mut list = format!("This zip holds {} files:\n", files.len());
    for e in files.iter().take(300) {
        list.push_str(&format!("{} ({} bytes)\n", e.name, e.size));
    }
    if files.len() > 300 {
        list.push_str(&format!("…and {} more\n", files.len() - 300));
    }
    out.pieces.push(list);
    let mut unpacked = 0usize;
    for e in files.iter().take(200) {
        let low = e.name.to_lowercase();
        if low.starts_with("__macosx/")
            || e.name
                .rsplit('/')
                .next()
                .is_some_and(|b| b.starts_with('.'))
        {
            continue;
        }
        if unpacked > UNPACK_CAP {
            break;
        }
        let Some(b) = z.read(&e.name, 4 * 1024 * 1024) else {
            continue;
        };
        unpacked += b.len();
        let t = if low.ends_with(".html") || low.ends_with(".htm") || low.ends_with(".xhtml") {
            plain_text(&b).map(|t| strip_tags(&t))
        } else if low.ends_with(".docx")
            || low.ends_with(".xlsx")
            || low.ends_with(".pdf")
            || low.ends_with(".zip")
        {
            None
        } else {
            plain_text(&b)
        };
        if let Some(t) = t.filter(|t| !t.trim().is_empty()) {
            out.pieces.push(format!("== {} ==\n{}", e.name, t));
        }
    }
}

// --------------------------------------------------------- office xml

fn local<'a>(n: &roxmltree::Node<'a, '_>) -> &'a str {
    n.tag_name().name()
}

fn xml(s: &str) -> Option<roxmltree::Document<'_>> {
    roxmltree::Document::parse_with_options(
        s,
        roxmltree::ParsingOptions {
            allow_dtd: false,
            nodes_limit: 4_000_000,
            ..roxmltree::ParsingOptions::default()
        },
    )
    .ok()
}

/// A paragraph's words from Word (w:t, tabs, breaks), PowerPoint (a:t) or OpenDocument.
fn paragraph_text(p: roxmltree::Node) -> String {
    let mut s = String::new();
    for n in p.descendants() {
        if n.is_text() {
            if let Some(par) = n.parent()
                && matches!(local(&par), "t" | "p" | "h" | "span" | "a" | "text-box")
            {
                s.push_str(n.text().unwrap_or(""));
            }
        } else if n.is_element() {
            match local(&n) {
                "tab" => s.push('\t'),
                "br" | "line-break" | "cr" => s.push('\n'),
                "s" => s.push(' '),
                _ => {}
            }
        }
    }
    s
}

fn docx(z: &Zip, out: &mut Extracted) {
    let Some(src) = z.text("word/document.xml") else {
        return;
    };
    let Some(doc) = xml(&src) else {
        return;
    };
    let mut text = String::new();
    for p in doc
        .descendants()
        .filter(|n| n.is_element() && local(n) == "p")
    {
        let t = paragraph_text(p);
        text.push_str(t.trim_end());
        text.push('\n');
    }
    out.pieces.push(tidy(&text));
}

fn pptx(z: &Zip, out: &mut Extracted) {
    out.unit = "slide";
    let mut slides: Vec<(u32, String)> = z
        .names()
        .filter_map(|n| {
            let num = n.strip_prefix("ppt/slides/slide")?.strip_suffix(".xml")?;
            Some((num.parse().ok()?, n.to_string()))
        })
        .collect();
    slides.sort();
    for (_, name) in slides {
        let mut text = String::new();
        if let Some(src) = z.text(&name)
            && let Some(doc) = xml(&src)
        {
            for p in doc
                .descendants()
                .filter(|n| n.is_element() && local(n) == "p")
            {
                let mut line = String::new();
                for t in p
                    .descendants()
                    .filter(|n| n.is_element() && local(n) == "t")
                {
                    line.push_str(t.text().unwrap_or(""));
                }
                if !line.trim().is_empty() {
                    text.push_str(line.trim_end());
                    text.push('\n');
                }
            }
        }
        out.pieces.push(text.trim().to_string());
    }
}

fn xlsx(z: &Zip, out: &mut Extracted) {
    out.unit = "sheet";
    let strings: Vec<String> = z
        .text("xl/sharedStrings.xml")
        .and_then(|s| {
            let doc = xml(&s)?;
            Some(
                doc.descendants()
                    .filter(|n| n.is_element() && local(n) == "si")
                    .map(|si| {
                        si.descendants()
                            .filter(|n| n.is_element() && local(n) == "t")
                            .map(|t| t.text().unwrap_or(""))
                            .collect::<String>()
                    })
                    .collect(),
            )
        })
        .unwrap_or_default();
    let names: Vec<String> = z
        .text("xl/workbook.xml")
        .and_then(|s| {
            let doc = xml(&s)?;
            Some(
                doc.descendants()
                    .filter(|n| n.is_element() && local(n) == "sheet")
                    .map(|n| n.attribute("name").unwrap_or("").to_string())
                    .collect(),
            )
        })
        .unwrap_or_default();
    let mut sheets: Vec<(u32, String)> = z
        .names()
        .filter_map(|n| {
            let num = n
                .strip_prefix("xl/worksheets/sheet")?
                .strip_suffix(".xml")?;
            Some((num.parse().ok()?, n.to_string()))
        })
        .collect();
    sheets.sort();
    for (i, (_, name)) in sheets.iter().enumerate() {
        let mut text = format!(
            "Sheet: {}\n",
            names.get(i).map(String::as_str).unwrap_or("?")
        );
        if let Some(src) = z.text(name)
            && let Some(doc) = xml(&src)
        {
            for row in doc
                .descendants()
                .filter(|n| n.is_element() && local(n) == "row")
            {
                let mut cells: Vec<String> = vec![];
                for c in row.children().filter(|n| n.is_element() && local(n) == "c") {
                    let v = c
                        .children()
                        .find(|n| n.is_element() && local(n) == "v")
                        .and_then(|n| n.text())
                        .unwrap_or("");
                    let cell = match c.attribute("t") {
                        Some("s") => v
                            .parse::<usize>()
                            .ok()
                            .and_then(|i| strings.get(i).cloned())
                            .unwrap_or_default(),
                        Some("inlineStr") => c
                            .descendants()
                            .filter(|n| n.is_element() && local(n) == "t")
                            .map(|t| t.text().unwrap_or(""))
                            .collect(),
                        _ => v.to_string(),
                    };
                    // Keep the column place: "C4" is the third column.
                    let col = c
                        .attribute("r")
                        .map(|r| {
                            r.chars()
                                .take_while(|c| c.is_ascii_alphabetic())
                                .collect::<String>()
                        })
                        .map(|l| {
                            l.bytes().fold(0usize, |a, b| {
                                a * 26 + (b.to_ascii_uppercase() - b'A' + 1) as usize
                            })
                        })
                        .unwrap_or(cells.len() + 1);
                    while cells.len() + 1 < col.min(200) {
                        cells.push(String::new());
                    }
                    cells.push(cell);
                }
                if cells.iter().any(|c| !c.is_empty()) {
                    text.push_str(cells.join("\t").trim_end());
                    text.push('\n');
                }
            }
        }
        out.pieces.push(text.trim().to_string());
    }
}

fn opendoc(z: &Zip, mimetype: &str, out: &mut Extracted) {
    let Some(src) = z.text("content.xml") else {
        return;
    };
    let Some(doc) = xml(&src) else {
        return;
    };
    if mimetype.ends_with("spreadsheet") {
        out.unit = "sheet";
        for t in doc
            .descendants()
            .filter(|n| n.is_element() && local(n) == "table")
        {
            let mut text = format!(
                "Sheet: {}\n",
                t.attributes()
                    .find(|a| a.name() == "name")
                    .map(|a| a.value())
                    .unwrap_or("?")
            );
            for row in t
                .descendants()
                .filter(|n| n.is_element() && local(n) == "table-row")
            {
                let mut cells = vec![];
                for c in row
                    .children()
                    .filter(|n| n.is_element() && local(n) == "table-cell")
                {
                    let s: String = c
                        .children()
                        .filter(|n| n.is_element() && local(n) == "p")
                        .map(paragraph_text)
                        .collect::<Vec<_>>()
                        .join(" ");
                    let repeat = c
                        .attributes()
                        .find(|a| a.name() == "number-columns-repeated")
                        .and_then(|a| a.value().parse::<usize>().ok())
                        .unwrap_or(1)
                        .min(if s.is_empty() { 1 } else { 20 });
                    for _ in 0..repeat {
                        cells.push(s.clone());
                    }
                }
                if cells.iter().any(|c| !c.is_empty()) {
                    text.push_str(cells.join("\t").trim_end());
                    text.push('\n');
                }
            }
            out.pieces.push(text.trim().to_string());
        }
    } else if mimetype.ends_with("presentation") {
        out.unit = "slide";
        for page in doc
            .descendants()
            .filter(|n| n.is_element() && local(n) == "page")
        {
            let text: Vec<String> = page
                .descendants()
                .filter(|n| n.is_element() && matches!(local(n), "p" | "h"))
                .map(paragraph_text)
                .filter(|t| !t.trim().is_empty())
                .collect();
            out.pieces.push(text.join("\n"));
        }
    } else {
        let mut text = String::new();
        for p in doc
            .descendants()
            .filter(|n| n.is_element() && matches!(local(n), "p" | "h"))
        {
            // A paragraph inside another (a table cell's) is read where it is.
            text.push_str(paragraph_text(p).trim_end());
            text.push('\n');
        }
        out.pieces.push(tidy(&text));
    }
}

fn epub(z: &Zip, out: &mut Extracted) {
    out.unit = "";
    let container = z.text("META-INF/container.xml").unwrap_or_default();
    let opf_path = xml(&container)
        .and_then(|d| {
            d.descendants()
                .find(|n| n.is_element() && local(n) == "rootfile")
                .and_then(|n| n.attribute("full-path").map(str::to_string))
        })
        .unwrap_or_default();
    let base = opf_path
        .rsplit_once('/')
        .map(|(b, _)| format!("{b}/"))
        .unwrap_or_default();
    let mut order: Vec<String> = vec![];
    if let Some(src) = z.text(&opf_path)
        && let Some(doc) = xml(&src)
    {
        let items: Vec<(String, String)> = doc
            .descendants()
            .filter(|n| n.is_element() && local(n) == "item")
            .filter_map(|n| {
                Some((
                    n.attribute("id")?.to_string(),
                    n.attribute("href")?.to_string(),
                ))
            })
            .collect();
        for r in doc
            .descendants()
            .filter(|n| n.is_element() && local(n) == "itemref")
        {
            if let Some(id) = r.attribute("idref")
                && let Some((_, href)) = items.iter().find(|(i, _)| i == id)
            {
                order.push(format!("{base}{href}"));
            }
        }
    }
    if order.is_empty() {
        order = z
            .names()
            .filter(|n| n.ends_with(".xhtml") || n.ends_with(".html") || n.ends_with(".htm"))
            .map(str::to_string)
            .collect();
    }
    let mut text = String::new();
    for n in order.iter().take(500) {
        if let Some(src) = z.text(n) {
            let t = strip_tags(&src);
            if !t.is_empty() {
                text.push_str(&t);
                text.push_str("\n\n");
            }
        }
        if text.len() > READ_CAP {
            break;
        }
    }
    out.pieces.push(tidy(&text));
}

// --------------------------------------------------------------- sound

fn is_sound(bytes: &[u8], ext: &str, kind: &str) -> bool {
    kind.starts_with("audio/")
        || matches!(
            ext,
            "wav" | "mp3" | "m4a" | "ogg" | "oga" | "opus" | "flac" | "aac" | "amr" | "wma" | "3gp"
        )
        || (bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WAVE"))
        || bytes.starts_with(b"OggS")
        || bytes.starts_with(b"ID3")
        || bytes.starts_with(b"fLaC")
}

/// A recording into words with the PC's own listening (the same one that
/// types what is said); other sound formats are changed to a plain WAV first.
fn sound(bytes: &[u8], ext: &str, out: &mut Extracted) {
    let root = crate::audio::voice_root();
    if !crate::listen::ready(&root) {
        out.problem = Some(
            "Your PC can't turn recordings into words yet. Turn on talking in Settings on your PC."
                .into(),
        );
        return;
    }
    let wav: Vec<u8> = if bytes.starts_with(b"RIFF") && crate::listen::samples(bytes).is_ok() {
        bytes.to_vec()
    } else {
        match to_wav(bytes, ext) {
            Some(w) => w,
            None => {
                out.problem = Some(
                    "Couldn't turn this recording into words here. Send it as a WAV file instead."
                        .into(),
                );
                return;
            }
        }
    };
    let heard = match crate::listen::Heard::keep(&crate::listen::heard_dir(), &wav) {
        Ok(h) => h,
        Err(_) => {
            out.problem = Some(
                "Couldn't listen to this recording just now. Send it again in a moment.".into(),
            );
            return;
        }
    };
    match crate::listen::transcribe(&root, heard.path()) {
        Ok(t) if !t.trim().is_empty() => out.pieces.push(t),
        Ok(_) => out.problem = Some("Nothing was heard in this recording. Try another one.".into()),
        Err(_) => {
            out.problem = Some(
                "Couldn't listen to this recording just now. Send it again in a moment.".into(),
            )
        }
    }
}

/// 16 kHz mono 16-bit WAV through ffmpeg, if this PC has it.
fn to_wav(bytes: &[u8], ext: &str) -> Option<Vec<u8>> {
    use std::io::Write as _;
    use std::process::{Command, Stdio};
    let _ = ext;
    let mut child = Command::new("ffmpeg")
        .args([
            "-v", "quiet", "-i", "pipe:0", "-ac", "1", "-ar", "16000", "-f", "wav", "pipe:1",
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;
    let mut stdin = child.stdin.take()?;
    let data = bytes.to_vec();
    let w = std::thread::spawn(move || {
        let _ = stdin.write_all(&data);
    });
    let mut v = vec![];
    child
        .stdout
        .take()?
        .take(200 * 1024 * 1024)
        .read_to_end(&mut v)
        .ok()?;
    let _ = w.join();
    child
        .wait()
        .ok()?
        .success()
        .then_some(v)
        .filter(|v| v.len() > 44)
}

#[cfg(test)]
mod tests;
