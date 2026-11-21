//! Read aloud's text rules: what is spoken for one of Claude's replies.
//!
//! Ported from the phone app's `ReadAloudText.kt` (the same rules, "Untrusted
//! text"). The reply is cleaned twice: first for any voice
//! ([`plain`]: no control or format characters, no markup, no word longer
//! than [`MAX_WORD`], at most [`MAX_TEXT`] read), then for the speech engine
//! ([`for_engine`]: only Latin letters, digits, spaces and a few marks, so
//! eSpeak's phoneme input and SSML can never be reached).
//!
//! What differs from the chat apps: the input is Markdown. Code blocks are
//! not read (one phrase says there is one), headings get a longer pause,
//! list items and table rows are their own phrases, links read their words.

/// The longest piece spoken at once.
const MAX_PIECE: usize = 400;
/// The most of a reply read (in characters, after Markdown is taken off).
/// No hard cap: long texts are read in full (PCE-15). Non-Latin phrases
/// produce a warning instead of silence (PCE-16).
// PCE-15: no limit on text length (was 4000 chars).
const MAX_TEXT: usize = usize::MAX / 8;
/// The longest "word" read as one: longer runs are cut.
const MAX_WORD: usize = 40;
/// Our own pauses after a phrase, in ms at Cadence 100%.
const PAUSE_COMMA: u32 = 250;
const PAUSE_STOP: u32 = 500;
const PAUSE_LINE: u32 = 400;
const PAUSE_ELLIPSIS: u32 = 500;
/// After a heading (the chat apps' pause between two messages).
const PAUSE_HEADING: u32 = 700;
/// A sentence longer than this many words is split at a natural break.
const LONG_WORDS: usize = 18;
/// What is said for a fenced code block.
const CODE_BLOCK: &str = "There's a code block here.";
/// "..." and "…" while the text is split: a pause, never read.
const ELL: char = '\u{1}';
/// The punctuation the speech engine is given (with letters, digits and spaces).
const ENGINE_PUNCT: &str = ".,!?;:'\"()-/%+";
/// Markup that never reaches a speech engine.
const MARKUP: &str = "<>[]{}\\";
/// Raw Markdown looked at for one block, before cleaning (a guard on time).
const MAX_RAW_BLOCK: usize = MAX_TEXT * 4;

/// One phrase, given to the voice whole, and the pause after it (ms at
/// Cadence 100%; 0 after the last).
#[derive(Debug, Clone, PartialEq)]
pub struct Phrase {
    pub text: String,
    pub pause_ms: u32,
}

/// Claude's reply (Markdown) → the phrases to speak, in order, each `text`
/// already safe for the speech engine (the forEngine whitelist), with the
/// usual pause after it (before any cadence scaling).
pub fn phrases(markdown: &str) -> Vec<Phrase> {
    let mut out: Vec<Phrase> = Vec::new();
    for line in lines(markdown) {
        let start = out.len();
        split_line(&normalize(&line.text), &mut out);
        // Only what the engine may say; a phrase with nothing left gives its
        // pause to the one before.
        let mut kept = start;
        for k in start..out.len() {
            let raw = &out[k].text;
            let pause = out[k].pause_ms;
            // PCE-16: warn when a phrase has letters but none are Latin.
            let has_letter = raw.chars().any(|c| c.is_alphabetic());
            let has_latin = raw.chars().any(is_latin);
            if has_letter && !has_latin {
                eprintln!(
                    "[mimir voice] non-Latin text cannot be spoken: {:?}",
                    &raw[..raw.len().min(40)]
                );
            }
            let text = for_engine(raw);
            if text.chars().any(char::is_alphanumeric) {
                out[kept] = Phrase {
                    text,
                    pause_ms: pause,
                };
                kept += 1;
            } else if kept > 0 {
                out[kept - 1].pause_ms = out[kept - 1].pause_ms.max(pause);
            }
        }
        out.truncate(kept);
        if out.len() > start {
            let min = if line.kind == Kind::Heading {
                PAUSE_HEADING
            } else {
                PAUSE_LINE
            };
            let last = out.len() - 1;
            out[last].pause_ms = out[last].pause_ms.max(min);
        }
    }
    if let Some(last) = out.last_mut() {
        last.pause_ms = 0;
    }
    out
}

/// The same reply as clean plain text for the desktop's fallback voice
/// (spd-say): no Markdown marks, links as their words, no emoji.
pub fn plain(markdown: &str) -> String {
    lines(markdown)
        .into_iter()
        .map(|l| l.text)
        .collect::<Vec<_>>()
        .join("\n")
}

// ---- Markdown into lines of plain text.

#[derive(Debug, Clone, Copy, PartialEq)]
enum Kind {
    Text,
    Heading,
    Code,
}

struct Line {
    kind: Kind,
    text: String,
}

struct Block {
    kind: Kind,
    text: String,
    /// A paragraph (a setext underline makes it a heading), not a list item.
    para: bool,
}

/// The reply's blocks as plain lines, at most [`MAX_TEXT`] in all (cut at a
/// word, nothing said about it).
fn lines(markdown: &str) -> Vec<Line> {
    let mut out = Vec::new();
    let mut left = MAX_TEXT;
    for b in blocks(markdown) {
        let text = match b.kind {
            Kind::Code => CODE_BLOCK.to_string(),
            _ => clean(&inline(&take_chars(&b.text, MAX_RAW_BLOCK))),
        };
        if text.is_empty() {
            continue;
        }
        let n = text.chars().count();
        if n > left {
            if b.kind != Kind::Code {
                let cut = cut_at_word(&text, left);
                if !cut.is_empty() {
                    out.push(Line {
                        kind: b.kind,
                        text: cut,
                    });
                }
            }
            break;
        }
        out.push(Line { kind: b.kind, text });
        // The line break after it counts as one.
        left -= n;
        if left <= 1 {
            break;
        }
        left -= 1;
    }
    out
}

fn take_chars(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

/// [s] cut to at most [max] characters, at the last space when the cut would
/// fall inside a word.
fn cut_at_word(s: &str, max: usize) -> String {
    let c: Vec<char> = s.chars().collect();
    if c.len() <= max {
        return s.to_string();
    }
    let mut end = max;
    if c[max] != ' '
        && let Some(sp) = c[..max].iter().rposition(|&x| x == ' ')
    {
        end = sp;
    }
    c[..end].iter().collect::<String>().trim().to_string()
}

/// The reply's blocks: code blocks, headings, list items, table rows and
/// paragraphs (their lines joined).
fn blocks(markdown: &str) -> Vec<Block> {
    let raw: Vec<&str> = markdown.lines().collect();
    let mut out: Vec<Block> = Vec::new();
    let mut open: Option<Block> = None;
    let mut fence: Option<(char, usize)> = None;
    let mut in_table = false;
    fn flush(open: &mut Option<Block>, out: &mut Vec<Block>) {
        if let Some(b) = open.take() {
            out.push(b);
        }
    }
    for (i, line) in raw.iter().enumerate() {
        let s = unquote(line);
        if let Some((fc, n)) = fence {
            if closes_fence(s, fc, n) {
                fence = None;
            }
            continue;
        }
        if s.is_empty() {
            flush(&mut open, &mut out);
            in_table = false;
            continue;
        }
        if let Some(f) = fence_open(s) {
            flush(&mut open, &mut out);
            out.push(Block {
                kind: Kind::Code,
                text: String::new(),
                para: false,
            });
            fence = Some(f);
            in_table = false;
            continue;
        }
        // Tables: each row's cells, read with commas between.
        if is_delimiter_row(s) {
            flush(&mut open, &mut out);
            in_table = true;
            continue;
        }
        let next_is_delimiter = raw.get(i + 1).is_some_and(|n| is_delimiter_row(unquote(n)));
        if s.contains('|') && (s.starts_with('|') || in_table || next_is_delimiter) {
            flush(&mut open, &mut out);
            out.push(Block {
                kind: Kind::Text,
                text: cells(s),
                para: false,
            });
            in_table = true;
            continue;
        }
        in_table = false;
        if let Some(r) = rule(s) {
            // "===" or "---" under a paragraph makes it a heading; otherwise a
            // line across the page, never read.
            let setext = (r == '=' || r == '-') && !s.contains([' ', '\t']);
            match open.take() {
                Some(mut b) if b.para && setext => {
                    b.kind = Kind::Heading;
                    out.push(b);
                }
                Some(b) => out.push(b),
                None => {}
            }
            continue;
        }
        if let Some(h) = heading(s) {
            flush(&mut open, &mut out);
            out.push(Block {
                kind: Kind::Heading,
                text: h.to_string(),
                para: false,
            });
            continue;
        }
        if let Some(item) = list_item(s) {
            flush(&mut open, &mut out);
            open = Some(Block {
                kind: Kind::Text,
                text: item.to_string(),
                para: false,
            });
            continue;
        }
        // A paragraph's next line (or a list item's) joins it.
        match &mut open {
            Some(b) => {
                // PCE-15: no hard cap on accumulated text.
                b.text.push(' ');
                b.text.push_str(s);
            }
            None => {
                open = Some(Block {
                    kind: Kind::Text,
                    text: s.to_string(),
                    para: true,
                })
            }
        }
    }
    flush(&mut open, &mut out);
    out
}

/// A line without its block quote marks (read like any other) and spaces.
fn unquote(line: &str) -> &str {
    let mut s = line.trim();
    while let Some(r) = s.strip_prefix('>') {
        s = r.trim_start();
    }
    s
}

/// A code fence that opens here: its mark and how many.
fn fence_open(s: &str) -> Option<(char, usize)> {
    let fc = s.chars().next()?;
    if fc != '`' && fc != '~' {
        return None;
    }
    let n = s.chars().take_while(|&c| c == fc).count();
    if n < 3 {
        return None;
    }
    // "```code```" on one line is inline code, not a fence.
    if fc == '`' && s[n..].contains('`') {
        return None;
    }
    Some((fc, n))
}

fn closes_fence(s: &str, fc: char, n: usize) -> bool {
    let run = s.chars().take_while(|&c| c == fc).count();
    run >= n && s[run * fc.len_utf8()..].trim().is_empty()
}

/// "---", "***", "___" (a line across the page) or "===" (under a heading).
fn rule(s: &str) -> Option<char> {
    let mut marks = s.chars().filter(|c| *c != ' ' && *c != '\t');
    let first = marks.next()?;
    if !matches!(first, '-' | '*' | '_' | '=') {
        return None;
    }
    let mut n = 1;
    for c in marks {
        if c != first {
            return None;
        }
        n += 1;
    }
    (n >= 3 || first == '=').then_some(first)
}

/// "## Title ##" → "Title".
fn heading(s: &str) -> Option<&str> {
    let n = s.chars().take_while(|&c| c == '#').count();
    if n == 0 || n > 6 {
        return None;
    }
    let rest = &s[n..];
    if !rest.is_empty() && !rest.starts_with([' ', '\t']) {
        return None;
    }
    let t = rest.trim();
    let closed = t.trim_end_matches('#');
    if closed.is_empty() || closed.ends_with([' ', '\t']) {
        return Some(closed.trim());
    }
    Some(t)
}

/// "- item", "* item", "+ item", "1. item", "2) item" (and a task's box) →
/// "item".
fn list_item(s: &str) -> Option<&str> {
    let rest = if let Some(r) = s.strip_prefix(['-', '*', '+']) {
        r
    } else {
        let d = s.chars().take_while(char::is_ascii_digit).count();
        if d == 0 || d > 9 {
            return None;
        }
        s[d..].strip_prefix(['.', ')'])?
    };
    if !rest.is_empty() && !rest.starts_with([' ', '\t']) {
        return None;
    }
    let mut item = rest.trim();
    for task in ["[ ]", "[x]", "[X]"] {
        if let Some(r) = item.strip_prefix(task)
            && (r.is_empty() || r.starts_with([' ', '\t']))
        {
            item = r.trim_start();
        }
    }
    Some(item)
}

/// "|---|:--:|": under a table's header.
fn is_delimiter_row(s: &str) -> bool {
    s.contains('|')
        && s.contains('-')
        && s.chars().all(|c| matches!(c, '|' | '-' | ':' | ' ' | '\t'))
}

/// "| a | b |" → "a, b" (empty cells skipped).
fn cells(s: &str) -> String {
    let s = s.strip_prefix('|').unwrap_or(s);
    let s = if s.ends_with('|') && !s.ends_with("\\|") {
        &s[..s.len() - 1]
    } else {
        s
    };
    let mut out: Vec<String> = Vec::new();
    let mut cell = String::new();
    let mut escaped = false;
    for c in s.chars() {
        if c == '|' && !escaped {
            out.push(std::mem::take(&mut cell));
        } else {
            cell.push(c);
        }
        escaped = c == '\\' && !escaped;
    }
    out.push(cell);
    out.iter()
        .map(|c| c.trim())
        .filter(|c| !c.is_empty())
        .collect::<Vec<_>>()
        .join(", ")
}

// ---- Inline Markdown: links as their words, code's words kept, marks gone.

/// One block's Markdown, inline: [words](url) and images read their words,
/// `code` its words, <https://...> and bare links "link", HTML tags gone;
/// then the chat apps' rules: * ~ ` | gone, _ a space, @ before a name gone.
fn inline(s: &str) -> String {
    let t = links_to_word(&spans(s, 0));
    let mut marks = String::with_capacity(t.len());
    for c in t.chars() {
        match c {
            '*' | '~' | '`' | '|' => {}
            '_' => marks.push(' '),
            _ => marks.push(c),
        }
    }
    // "@ava" is "ava".
    let c: Vec<char> = marks.chars().collect();
    let mut out = String::with_capacity(marks.len());
    for (i, &ch) in c.iter().enumerate() {
        if ch == '@'
            && (i == 0 || is_ws(c[i - 1]))
            && c.get(i + 1)
                .is_some_and(|n| n.is_alphanumeric() || *n == '_')
        {
            continue;
        }
        out.push(ch);
    }
    out
}

/// Code spans, links, images, autolinks and tags.
fn spans(s: &str, depth: u32) -> String {
    let c: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < c.len() {
        match c[i] {
            '\\' if c.get(i + 1).is_some_and(char::is_ascii_punctuation) => {
                out.push(c[i + 1]);
                i += 2;
            }
            '`' => {
                let n = c[i..].iter().take_while(|&&x| x == '`').count();
                match closing_ticks(&c, i + n, n) {
                    Some(j) => {
                        out.extend(&c[i + n..j]);
                        i = j + n;
                    }
                    None => {
                        out.extend(&c[i..i + n]);
                        i += n;
                    }
                }
            }
            '!' if c.get(i + 1) == Some(&'[') => i += 1,
            '[' if depth < 4 => match link(&c, i) {
                Some((words, end)) => {
                    let w: String = words.iter().collect();
                    if !w.starts_with('^') {
                        out.push_str(&spans(&w, depth + 1));
                    }
                    i = end;
                }
                None => {
                    out.push('[');
                    i += 1;
                }
            },
            '<' => match angle(&c, i) {
                Some((said, end)) => {
                    out.push_str(said);
                    i = end;
                }
                None => {
                    out.push('<');
                    i += 1;
                }
            },
            ch => {
                out.push(ch);
                i += 1;
            }
        }
    }
    out
}

/// Where a code span opened with [n] backticks closes (a run of exactly n).
fn closing_ticks(c: &[char], from: usize, n: usize) -> Option<usize> {
    let mut j = from;
    while j < c.len() {
        if c[j] == '`' {
            let run = c[j..].iter().take_while(|&&x| x == '`').count();
            if run == n {
                return Some(j);
            }
            j += run;
        } else {
            j += 1;
        }
    }
    None
}

/// The bracket that closes the one at [i] (nested ones counted), within [limit].
fn matching(c: &[char], i: usize, open: char, close: char, limit: usize) -> Option<usize> {
    let mut depth = 0;
    let mut j = i;
    while j < c.len() && j - i <= limit {
        match c[j] {
            '\\' => j += 1,
            x if x == open => depth += 1,
            x if x == close => {
                depth -= 1;
                if depth == 0 {
                    return Some(j);
                }
            }
            _ => {}
        }
        j += 1;
    }
    None
}

/// [words](url), [words][ref] and [^note] at [i]: the words and where it ends.
fn link(c: &[char], i: usize) -> Option<(&[char], usize)> {
    let close = matching(c, i, '[', ']', 1000)?;
    let words = &c[i + 1..close];
    match c.get(close + 1) {
        Some('(') => {
            let end = matching(c, close + 1, '(', ')', 2000)?;
            Some((words, end + 1))
        }
        Some('[') => {
            let end = matching(c, close + 1, '[', ']', 200)?;
            Some((words, end + 1))
        }
        _ if words.first() == Some(&'^') => Some((words, close + 1)),
        _ => None,
    }
}

const TAGS: &[&str] = &[
    "a",
    "abbr",
    "b",
    "blockquote",
    "br",
    "code",
    "del",
    "details",
    "div",
    "em",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "hr",
    "i",
    "img",
    "ins",
    "kbd",
    "li",
    "mark",
    "ol",
    "p",
    "pre",
    "s",
    "small",
    "span",
    "strong",
    "sub",
    "summary",
    "sup",
    "table",
    "u",
    "ul",
];

/// <https://...> is "link"; an HTML tag (<br>, </kbd>...) is nothing.
fn angle(c: &[char], i: usize) -> Option<(&'static str, usize)> {
    let mut j = i + 1;
    while j < c.len() && j - i < 500 && c[j] != '>' && c[j] != '<' && c[j] != '\n' {
        j += 1;
    }
    if c.get(j) != Some(&'>') {
        return None;
    }
    let inner: String = c[i + 1..j].iter().collect();
    let low = inner.to_ascii_lowercase();
    if ["http://", "https://", "www.", "mailto:"]
        .iter()
        .any(|p| low.starts_with(p))
    {
        return Some((" link ", j + 1));
    }
    let name: String = low
        .trim_start_matches('/')
        .chars()
        .take_while(char::is_ascii_alphanumeric)
        .collect();
    let after = low.trim_start_matches('/')[name.len()..].chars().next();
    if TAGS.contains(&name.as_str()) && after.is_none_or(|a| a == ' ' || a == '/') {
        return Some((" ", j + 1));
    }
    None
}

/// Bare links (https://..., www....) say "link".
fn links_to_word(s: &str) -> String {
    let c: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < c.len() {
        if ["https://", "http://", "www."]
            .iter()
            .any(|p| lit(&c, i, p, true).is_some())
        {
            out.push_str(" link ");
            while i < c.len() && !c[i].is_whitespace() {
                i += 1;
            }
        } else {
            out.push(c[i]);
            i += 1;
        }
    }
    out
}

// ---- The chat apps' rules (ReadAloudText.kt).

/// Emoji, pictographs, flags, skin tones, joiners, variation selectors,
/// keycaps and tags: never read out.
fn dropped(c: char) -> bool {
    let c = c as u32;
    (0x1F000..=0x1FAFF).contains(&c)
        || (0x2600..=0x27BF).contains(&c)
        || (0x2B00..=0x2BFF).contains(&c)
        || (0xFE00..=0xFE0F).contains(&c)
        || c == 0x200D
        || c == 0x20E3
        || (0xE0000..=0xE007F).contains(&c)
}

/// Control and format characters, private and noncharacters, other spaces
/// and markup: a space.
fn blank(ch: char) -> bool {
    let c = ch as u32;
    ch.is_control()
        || ch.is_whitespace()
        || is_format(c)
        || (0xE000..=0xF8FF).contains(&c)
        || c >= 0xF0000
        || (0xFDD0..=0xFDEF).contains(&c)
        || (c & 0xFFFF) >= 0xFFFE
        || (ch.is_ascii() && MARKUP.contains(ch))
}

/// Unicode's format characters (Cf).
fn is_format(c: u32) -> bool {
    matches!(c,
        0xAD | 0x600..=0x605 | 0x61C | 0x6DD | 0x70F | 0x890..=0x891 | 0x8E2 | 0x180E
        | 0x200B..=0x200F | 0x202A..=0x202E | 0x2060..=0x2064 | 0x2066..=0x206F | 0xFEFF
        | 0xFFF9..=0xFFFB | 0x110BD | 0x110CD | 0x13430..=0x1343F | 0x1BCA0..=0x1BCA3
        | 0x1D173..=0x1D17A | 0xE0001 | 0xE0020..=0xE007F)
}

/// One line read for any voice: no emoji, control characters or markup,
/// words of at most [`MAX_WORD`], spaces tidied.
fn clean(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut run = 0;
    for c in s.chars() {
        if dropped(c) {
            continue;
        }
        if blank(c) {
            out.push(' ');
            run = 0;
            continue;
        }
        // Very long "words" are cut into pieces of MAX_WORD.
        if run == MAX_WORD {
            out.push(' ');
            run = 0;
        }
        out.push(c);
        run += 1;
    }
    one_space(&out)
}

fn one_space(s: &str) -> String {
    s.split(' ')
        .filter(|w| !w.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
}

/// A Latin letter (the scripts' Latin blocks; other letters are not).
fn is_latin(ch: char) -> bool {
    matches!(ch as u32,
        0x41..=0x5A | 0x61..=0x7A | 0xAA | 0xBA | 0xC0..=0xD6 | 0xD8..=0xF6 | 0xF8..=0x2AF
        | 0x1E00..=0x1EFF | 0x2071 | 0x207F | 0x2090..=0x209C | 0x212A..=0x212B | 0x2132
        | 0x214E | 0x2C60..=0x2C7F | 0xA722..=0xA7FF | 0xAB30..=0xAB5A | 0xAB5C..=0xAB64
        | 0xFB00..=0xFB06 | 0xFF21..=0xFF3A | 0xFF41..=0xFF5A)
}

/// An accent written after its letter (combining marks).
fn is_mark(ch: char) -> bool {
    matches!(ch as u32,
        0x300..=0x36F | 0x483..=0x489 | 0x1AB0..=0x1AFF | 0x1DC0..=0x1DFF | 0x20D0..=0x20FF
        | 0xFE20..=0xFE2F)
}

/// What the speech engine (and its eSpeak NG) is given for one piece: Latin
/// letters (at most two accents each), digits 0 to 9, spaces and
/// . , ! ? ; : ' " ( ) - / % + only; anything else is a space. No brackets,
/// angle brackets or backslashes, so eSpeak's phoneme input ([[ ]]) and SSML
/// can't be reached.
fn for_engine(piece: &str) -> String {
    let mut out = String::with_capacity(piece.len());
    let mut after_letter = false;
    let mut accents = 0;
    for c in piece.chars() {
        if is_mark(c) {
            // An accent on a letter: at most two on one letter.
            if after_letter && accents < 2 {
                out.push(c);
                accents += 1;
            }
            continue;
        }
        accents = 0;
        after_letter = false;
        match c {
            _ if c.is_alphabetic() && is_latin(c) => {
                out.push(c);
                after_letter = true;
            }
            '0'..='9' => out.push(c),
            '&' => out.push_str(" and "),
            '\u{2018}' | '\u{2019}' => out.push('\''),
            '\u{201C}' | '\u{201D}' => out.push('"'),
            '\u{2026}' => out.push_str("..."),
            _ if c.is_ascii() && ENGINE_PUNCT.contains(c) => out.push(c),
            _ => out.push(' '),
        }
    }
    one_space(&out)
}

/// Java's \s.
fn is_ws(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\x0B' | '\x0C' | '\r')
}

/// [text] in pieces to speak one at a time: sentences and lines, none longer
/// than [`MAX_PIECE`].
fn pieces(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    for line in text.split('\n') {
        for part in sentence_parts(line) {
            let mut s: Vec<char> = part.trim().chars().collect();
            while s.len() > MAX_PIECE {
                // Cut long runs at the last comma, else the last space, before
                // the limit.
                let comma = (1..MAX_PIECE.min(s.len() - 1))
                    .rev()
                    .find(|&k| s[k] == ',' && s[k + 1] == ' ');
                let cut = match comma {
                    Some(k) => k + 1,
                    None => match s[..MAX_PIECE].iter().rposition(|&x| x == ' ') {
                        Some(k) if k > 0 => k,
                        _ => MAX_PIECE,
                    },
                };
                out.push(s[..cut].iter().collect::<String>().trim().to_string());
                s = s[cut..].iter().collect::<String>().trim().chars().collect();
            }
            if !s.is_empty() {
                out.push(s.into_iter().collect());
            }
        }
    }
    out
}

/// [line] split after . ! ? and … followed by spaces.
fn sentence_parts(line: &str) -> Vec<String> {
    let c: Vec<char> = line.chars().collect();
    let mut out = Vec::new();
    let mut from = 0;
    let mut i = 0;
    while i < c.len() {
        if is_ws(c[i]) && i > 0 && matches!(c[i - 1], '.' | '!' | '?' | '\u{2026}') {
            out.push(c[from..i].iter().collect());
            while i < c.len() && is_ws(c[i]) {
                i += 1;
            }
            from = i;
        } else {
            i += 1;
        }
    }
    out.push(c[from..].iter().collect());
    out
}

/// One (already normalized) line in phrases: at sentence ends, commas, ; and
/// :, "..." and the line's end; long ones split again.
fn split_line(line: &str, out: &mut Vec<Phrase>) {
    let l: Vec<char> = line.trim().chars().collect();
    if l.is_empty() {
        return;
    }
    let start = out.len();
    let mut from = 0;
    let mut i = 0;
    while i < l.len() {
        let c = l[i];
        let mut pause = None;
        let mut end = i;
        if c == ELL {
            pause = Some(PAUSE_ELLIPSIS);
        } else if matches!(c, '.' | '!' | '?' | ',' | ';' | ':') {
            let mut j = i;
            while j + 1 < l.len() && matches!(l[j + 1], '.' | '!' | '?') {
                j += 1;
            }
            while j + 1 < l.len() && matches!(l[j + 1], '"' | '\'' | ')') {
                j += 1;
            }
            if j + 1 >= l.len() || l[j + 1] == ' ' || l[j + 1] == ELL {
                pause = Some(if matches!(c, ',' | ';' | ':') {
                    PAUSE_COMMA
                } else {
                    PAUSE_STOP
                });
                end = j + 1;
                i = j;
            }
        }
        if let Some(p) = pause {
            add(&l[from..end], p, out);
            from = if c == ELL { i + 1 } else { end };
        }
        i += 1;
    }
    if from < l.len() {
        add(&l[from..], PAUSE_LINE, out);
    }
    // The line's end: at least the line break's pause.
    if out.len() > start {
        let last = out.len() - 1;
        out[last].pause_ms = out[last].pause_ms.max(PAUSE_LINE);
    }
}

fn add(raw: &[char], pause: u32, out: &mut Vec<Phrase>) {
    let s: String = raw.iter().collect();
    let s = s.trim();
    // Nothing to say (only marks): its pause goes to the phrase before.
    if !s.chars().any(char::is_alphanumeric) {
        if let Some(last) = out.last_mut() {
            last.pause_ms = last.pause_ms.max(pause);
        }
        return;
    }
    let parts = long_split(s);
    let n = parts.len();
    for (i, p) in parts.into_iter().enumerate() {
        out.push(Phrase {
            text: p,
            pause_ms: if i + 1 == n { pause } else { PAUSE_COMMA },
        });
    }
}

/// A phrase over [`LONG_WORDS`] words cut at ", and", ", but", " because"
/// and ";", then to at most [`MAX_PIECE`].
fn long_split(s: &str) -> Vec<String> {
    let words = s.split(' ').filter(|w| !w.trim().is_empty()).count();
    if words <= LONG_WORDS {
        return pieces(s);
    }
    let c: Vec<char> = s.chars().collect();
    let word_then_ws = |at: usize, w: &str| {
        lit(&c, at, w, true).is_some_and(|e| c.get(e).is_some_and(|&x| is_ws(x)))
    };
    let mut first: Vec<String> = Vec::new();
    let mut from = 0;
    let mut i = 0;
    while i < c.len() {
        if !is_ws(c[i]) {
            i += 1;
            continue;
        }
        let a = i;
        while i < c.len() && is_ws(c[i]) {
            i += 1;
        }
        let before = if a > 0 { c[a - 1] } else { ' ' };
        let at_break = before == ';'
            || (before == ',' && (word_then_ws(i, "and") || word_then_ws(i, "but")))
            || word_then_ws(i, "because");
        if at_break {
            first.push(c[from..a].iter().collect());
            from = i;
        }
    }
    first.push(c[from..].iter().collect());
    first
        .iter()
        .map(|p| p.trim())
        .filter(|p| !p.is_empty())
        .flat_map(pieces)
        .collect()
}

// ---- normalize: plain words for numbers, times, abbreviations, shorthand.

/// Where [pat] matches [c] at [i] (ASCII case ignored when [ci]): its end.
fn lit(c: &[char], i: usize, pat: &str, ci: bool) -> Option<usize> {
    let mut j = i;
    for p in pat.chars() {
        let x = *c.get(j)?;
        let same = if ci {
            x.eq_ignore_ascii_case(&p)
        } else {
            x == p
        };
        if !same {
            return None;
        }
        j += 1;
    }
    Some(j)
}

/// Each place [at] matches (scanning left to right, as a regex replace
/// does) replaced: [at] gives the match's end and what it becomes.
fn replace_with(t: &str, mut at: impl FnMut(&[char], usize) -> Option<(usize, String)>) -> String {
    let c: Vec<char> = t.chars().collect();
    let mut out = String::with_capacity(t.len());
    let mut i = 0;
    while i < c.len() {
        match at(&c, i) {
            Some((end, rep)) if end > i => {
                out.push_str(&rep);
                i = end;
            }
            _ => {
                out.push(c[i]);
                i += 1;
            }
        }
    }
    out
}

/// What follows means the sentence ends here: nothing, or spaces then a
/// capital or a quote.
fn ends_here(rest: &[char]) -> bool {
    let ws = rest.iter().take_while(|&&x| is_ws(x)).count();
    match rest.get(ws) {
        None => true,
        Some(&n) => ws > 0 && (n.is_uppercase() || n == '"' || n == '\''),
    }
}

const ONES: [&str; 20] = [
    "zero",
    "one",
    "two",
    "three",
    "four",
    "five",
    "six",
    "seven",
    "eight",
    "nine",
    "ten",
    "eleven",
    "twelve",
    "thirteen",
    "fourteen",
    "fifteen",
    "sixteen",
    "seventeen",
    "eighteen",
    "nineteen",
];
const TENS: [&str; 10] = [
    "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
];
const SHORT: [(&str, &str); 8] = [
    ("lol", "laughing out loud"),
    ("brb", "be right back"),
    ("idk", "I don't know"),
    ("omw", "on my way"),
    ("tbh", "to be honest"),
    ("imo", "in my opinion"),
    ("ty", "thank you"),
    ("np", "no problem"),
];

/// Small, safe tidying before the voice: curly quotes made straight, "..."
/// and "…" a pause, ".." one full stop (never "dot"), titles and common
/// abbreviations in words, texting shorthand spelled out, times ("3:30" is
/// "three thirty") and numbers in words.
fn normalize(text: &str) -> String {
    let mut t: String = text
        .chars()
        .map(|c| match c {
            '\u{2018}' | '\u{2019}' | '\u{201A}' | '\u{201B}' => '\'',
            '\u{201C}' | '\u{201D}' | '\u{201E}' | '\u{201F}' => '"',
            c => c,
        })
        .collect();
    let ell = format!(" {ELL} ");
    t = replace_with(&t, |c, i| {
        if c[i] == '\u{2026}' {
            return Some((i + 1, ell.clone()));
        }
        let n = c[i..].iter().take_while(|&&x| x == '.').count();
        (n >= 3).then(|| (i + n, ell.clone()))
    });
    t = replace_with(&t, |c, i| lit(c, i, "..", false).map(|e| (e, ".".into())));
    let not_after_letter = |c: &[char], i: usize| i == 0 || !c[i - 1].is_alphabetic();
    // Titles before a name: never the end of a sentence.
    for (w, rep) in [
        ("Dr.", "Doctor"),
        ("Mrs.", "Missus"),
        ("Mr.", "Mister"),
        ("Ms.", "Miz"),
    ] {
        t = replace_with(&t, |c, i| {
            if !not_after_letter(c, i) {
                return None;
            }
            lit(c, i, w, false).map(|e| (e, rep.into()))
        });
    }
    // "St." before a name is Saint.
    t = replace_with(&t, |c, i| {
        if !not_after_letter(c, i) {
            return None;
        }
        let e = lit(c, i, "St.", false)?;
        let ws = c[e..].iter().take_while(|&&x| is_ws(x)).count();
        (ws > 0 && c.get(e + ws).is_some_and(|x| x.is_uppercase())).then(|| (e, "Saint".into()))
    });
    // Others: a full stop stays when a sentence ends with one.
    for (w, rep, ci) in [
        ("St.", "Street", false),
        ("etc.", "et cetera", true),
        ("e.g.", "for example", true),
        ("i.e.", "that is", true),
        ("vs.", "versus", true),
        ("Jr.", "Junior", false),
        ("Sr.", "Senior", false),
    ] {
        t = replace_with(&t, |c, i| {
            if !not_after_letter(c, i) {
                return None;
            }
            let e = lit(c, i, w, ci)?;
            Some((
                e,
                if ends_here(&c[e..]) {
                    format!("{rep}.")
                } else {
                    rep.into()
                },
            ))
        });
    }
    let word_edge = |x: Option<&char>| x.is_none_or(|x| !x.is_alphanumeric() && *x != '\'');
    t = replace_with(&t, |c, i| {
        if !word_edge(if i > 0 { c.get(i - 1) } else { None }) {
            return None;
        }
        SHORT.iter().find_map(|(w, rep)| {
            let e = lit(c, i, w, true)?;
            word_edge(c.get(e)).then(|| (e, (*rep).to_string()))
        })
    });
    t = replace_with(&t, time_at);
    t = replace_with(&t, ordinal_at);
    t = replace_with(&t, money_at);
    t = replace_with(&t, negative_at);
    t = replace_with(&t, number_at);
    one_space(&t)
}

fn digit(c: &[char], k: usize) -> Option<u32> {
    c.get(k).and_then(|x| {
        if x.is_ascii_digit() {
            x.to_digit(10)
        } else {
            None
        }
    })
}

fn digit_run(c: &[char], i: usize) -> usize {
    c[i.min(c.len())..]
        .iter()
        .take_while(|x| x.is_ascii_digit())
        .count()
}

/// "3:30", "10:15am", "7:00 p.m.".
fn time_at(c: &[char], i: usize) -> Option<(usize, String)> {
    if i > 0 && (c[i - 1].is_numeric() || c[i - 1] == ':' || c[i - 1] == '.') {
        return None;
    }
    let mut hours = Vec::new();
    if let (Some(a), Some(b)) = (digit(c, i), digit(c, i + 1))
        && (a <= 1 || (a == 2 && b <= 3))
    {
        hours.push((a * 10 + b, i + 2));
    }
    if let Some(a) = digit(c, i) {
        hours.push((a, i + 1));
    }
    for (h, p) in hours {
        if c.get(p) != Some(&':') {
            continue;
        }
        let (Some(m1), Some(m2)) = (digit(c, p + 1), digit(c, p + 2)) else {
            continue;
        };
        if m1 > 5 {
            continue;
        }
        let q = p + 3;
        if c.get(q).is_some_and(|x| x.is_numeric() || *x == ':') {
            continue;
        }
        let (end, half) = match am_pm(c, q) {
            Some((e, h)) => (e, Some(h)),
            None => (q, None),
        };
        return Some((end, time_words(h, m1 * 10 + m2, half)));
    }
    None
}

/// " pm", "am", " p.m." after a time: where it ends, and 'a' or 'p'.
fn am_pm(c: &[char], q: usize) -> Option<(usize, char)> {
    let mut k = q;
    if c.get(k).is_some_and(|&x| is_ws(x)) {
        k += 1;
    }
    let half = c.get(k)?.to_ascii_lowercase();
    if half != 'a' && half != 'p' {
        return None;
    }
    k += 1;
    if c.get(k) == Some(&'.') {
        k += 1;
    }
    if c.get(k).is_some_and(|&x| is_ws(x)) {
        k += 1;
    }
    if !c.get(k).is_some_and(|x| x.eq_ignore_ascii_case(&'m')) {
        return None;
    }
    k += 1;
    let letter = |k: usize| c.get(k).is_some_and(|x| x.is_alphabetic());
    if c.get(k) == Some(&'.') && !letter(k + 1) {
        Some((k + 1, half))
    } else if !letter(k) {
        Some((k, half))
    } else {
        None
    }
}

/// "21st" → "twenty first".
fn ordinal_at(c: &[char], i: usize) -> Option<(usize, String)> {
    if i > 0 && (c[i - 1].is_alphanumeric() || c[i - 1] == '.' || c[i - 1] == ',') {
        return None;
    }
    let run = digit_run(c, i);
    if run == 0 || run > 9 {
        return None;
    }
    let e = i + run;
    let e2 = ["st", "nd", "rd", "th"]
        .iter()
        .find_map(|s| lit(c, e, s, true))?;
    if c.get(e2).is_some_and(|x| x.is_alphanumeric()) {
        return None;
    }
    let n: u64 = c[i..e].iter().collect::<String>().parse().ok()?;
    Some((e2, ordinal_words(n)))
}

/// Where a number's whole part (digits, maybe "1,234,567") can end, in the
/// order a regex tries them.
fn whole_ends(c: &[char], i: usize) -> Vec<usize> {
    let run = digit_run(c, i);
    if run == 0 {
        return Vec::new();
    }
    let mut ends = Vec::new();
    if run <= 3 {
        let mut e = i + run;
        while c.get(e) == Some(&',') && digit_run(c, e + 1) >= 3 {
            e += 4;
            ends.push(e);
        }
        ends.reverse();
    }
    ends.push(i + run);
    ends
}

fn numeric_at(c: &[char], k: usize) -> bool {
    c.get(k).is_some_and(|x| x.is_numeric())
}

/// "$15" → "fifteen dollars", "£1.50" → "one pound fifty".
fn money_at(c: &[char], i: usize) -> Option<(usize, String)> {
    let sign = c[i];
    if !matches!(sign, '$' | '\u{A3}' | '\u{20AC}') {
        return None;
    }
    for end in whole_ends(c, i + 1) {
        let whole: String = c[i + 1..end].iter().filter(|&&x| x != ',').collect();
        if c.get(end) == Some(&'.') && digit_run(c, end + 1) >= 2 && !numeric_at(c, end + 3) {
            let cents: String = c[end + 1..end + 3].iter().collect();
            return Some((end + 3, money_words(sign, &whole, &cents)));
        }
        if !numeric_at(c, end) {
            return Some((end, money_words(sign, &whole, "")));
        }
    }
    None
}

/// "-5" → "negative five", "-3.5" → "negative three point five" (PCE-31).
fn negative_at(c: &[char], i: usize) -> Option<(usize, String)> {
    if c[i] != '-' {
        return None;
    }
    // The minus must be at the start of a word (not inside one).
    if i > 0 && (c[i - 1].is_alphanumeric() || c[i - 1] == '-') {
        return None;
    }
    // What follows must be digits.
    if digit_run(c, i + 1) == 0 {
        return None;
    }
    let (end, words) = number_at_pos(c, i + 1)?;
    Some((end, format!("negative {words}")))
}

/// "1,250" → "one thousand two hundred fifty", "3.5" → "three point five".
fn number_at(c: &[char], i: usize) -> Option<(usize, String)> {
    if i > 0 && (c[i - 1].is_alphanumeric() || c[i - 1] == '.' || c[i - 1] == ',') {
        return None;
    }
    number_at_pos(c, i)
}

/// The number starting at exactly [i] (no prefix guard).
fn number_at_pos(c: &[char], i: usize) -> Option<(usize, String)> {
    for end in whole_ends(c, i) {
        let whole: String = c[i..end].iter().collect();
        if c.get(end) == Some(&'.') {
            let d = digit_run(c, end + 1);
            if d > 0 && !numeric_at(c, end + 1 + d) {
                let dec: String = c[end + 1..end + 1 + d].iter().collect();
                return Some((end + 1 + d, number_text(&whole, &dec)));
            }
        }
        if !numeric_at(c, end) {
            return Some((end, number_text(&whole, "")));
        }
    }
    None
}

/// [n] in words: 1923 is "one thousand nine hundred twenty three".
fn number_words(n: u64) -> String {
    if n < 20 {
        return ONES[n as usize].to_string();
    }
    if n < 100 {
        let mut s = TENS[(n / 10) as usize].to_string();
        if !n.is_multiple_of(10) {
            s.push(' ');
            s.push_str(ONES[(n % 10) as usize]);
        }
        return s;
    }
    if n < 1000 {
        let mut s = format!("{} hundred", ONES[(n / 100) as usize]);
        if !n.is_multiple_of(100) {
            s.push(' ');
            s.push_str(&number_words(n % 100));
        }
        return s;
    }
    for (size, name) in [
        (1_000_000_000, "billion"),
        (1_000_000, "million"),
        (1000, "thousand"),
    ] {
        if n >= size {
            let mut s = format!("{} {name}", number_words(n / size));
            if !n.is_multiple_of(size) {
                s.push(' ');
                s.push_str(&number_words(n % size));
            }
            return s;
        }
    }
    n.to_string()
}

/// A year said the usual way: 1923 "nineteen twenty three", 1905 "nineteen
/// oh five", 2007 "two thousand seven".
fn year_words(n: u64) -> String {
    if (2000..=2009).contains(&n) || n.is_multiple_of(1000) {
        return number_words(n);
    }
    let (hi, lo) = (n / 100, n % 100);
    let tail = match lo {
        0 => " hundred".to_string(),
        1..=9 => format!(" oh {}", ONES[lo as usize]),
        _ => format!(" {}", number_words(lo)),
    };
    number_words(hi) + &tail
}

/// 1 "first", 22 "twenty second", 100 "one hundredth".
fn ordinal_words(n: u64) -> String {
    let w = number_words(n);
    let (head, last) = match w.rfind(' ') {
        Some(k) => (&w[..=k], &w[k + 1..]),
        None => ("", w.as_str()),
    };
    let ord = match last {
        "one" => "first".to_string(),
        "two" => "second".into(),
        "three" => "third".into(),
        "five" => "fifth".into(),
        "eight" => "eighth".into(),
        "nine" => "ninth".into(),
        "twelve" => "twelfth".into(),
        l if l.ends_with('y') => format!("{}ieth", &l[..l.len() - 1]),
        l => format!("{l}th"),
    };
    format!("{head}{ord}")
}

/// "3:30" "three thirty", "3:05" "three oh five", "3:00" "three o'clock",
/// "3:00 pm" "three pee em", "15:00" "fifteen hundred".
fn time_words(h: u32, m: u32, half: Option<char>) -> String {
    let hour = if half.is_some() && h == 0 {
        "twelve".to_string()
    } else {
        number_words(h.into())
    };
    let mins = match m {
        0 if half.is_some() => String::new(),
        0 if (1..=12).contains(&h) => " o'clock".into(),
        0 => " hundred".into(),
        1..=9 => format!(" oh {}", ONES[m as usize]),
        _ => format!(" {}", number_words(m.into())),
    };
    let tail = match half {
        Some('a') => " ay em",
        Some('p') => " pee em",
        _ => "",
    };
    format!("{hour}{mins}{tail}")
}

/// "$15" is "fifteen dollars", "£1.50" "one pound fifty" (the engine never
/// sees the sign).
fn money_words(sign: char, whole: &str, cents: &str) -> String {
    let Ok(n) = whole.parse::<i64>() else {
        return format!("{sign}{whole}");
    };
    let unit = match sign {
        '$' => "dollar",
        '\u{A3}' => "pound",
        _ => "euro",
    };
    let mut s = format!(
        "{} {unit}{}",
        number_words(n as u64),
        if n == 1 { "" } else { "s" }
    );
    let c: u64 = cents.parse().unwrap_or(0);
    if c > 0 {
        s.push(' ');
        s.push_str(&number_words(c));
    }
    s
}

/// One number as found: digits (commas between thousands) and maybe a
/// decimal part.
fn number_text(whole: &str, decimal: &str) -> String {
    let digits: String = whole.chars().filter(|&c| c != ',').collect();
    let one_by_one = |s: &str| {
        s.chars()
            .map(|d| ONES[d.to_digit(10).unwrap_or(0) as usize])
            .collect::<Vec<_>>()
            .join(" ")
    };
    let head = if (digits.len() > 1 && digits.starts_with('0')) || digits.len() > 12 {
        one_by_one(&digits)
    } else {
        let n: u64 = digits.parse().unwrap_or(0);
        if decimal.is_empty() && whole.len() == 4 && (1100..=2099).contains(&n) {
            year_words(n)
        } else {
            number_words(n)
        }
    };
    if decimal.is_empty() {
        head
    } else {
        format!("{head} point {}", one_by_one(&take_chars(decimal, 6)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn texts(p: &[Phrase]) -> Vec<&str> {
        p.iter().map(|p| p.text.as_str()).collect()
    }

    fn pauses(p: &[Phrase]) -> Vec<u32> {
        p.iter().map(|p| p.pause_ms).collect()
    }

    fn ph(text: &str, pause_ms: u32) -> Phrase {
        Phrase {
            text: text.into(),
            pause_ms,
        }
    }

    #[test]
    fn engine_whitelist() {
        assert_eq!(
            for_engine("[[h@'loU]] <speak>Hi</speak> \u{C5}sa & co \u{2019}s"),
            "h 'loU speak Hi /speak \u{C5}sa and co 's"
        );
        assert_eq!(for_engine("a\\b{c}<d>"), "a b c d");
        assert_eq!(
            for_engine("\u{41F}\u{440}\u{438} \u{65E5}\u{672C} 123"),
            "123"
        );
        assert_eq!(
            for_engine("a\u{30A}\u{301}\u{300}\u{302}b"),
            "a\u{30A}\u{301}b"
        );
        // Through the whole way: no bracket, angle bracket or backslash left.
        let p = phrases("Say [[h@loU]] <b>now</b> \\x and <speak> {it}");
        assert_eq!(texts(&p), vec!["Say h loU now x and speak it"]);
        for p in &p {
            assert!(!p.text.contains(['[', ']', '<', '>', '\\', '{', '}', '@']));
        }
    }

    #[test]
    fn plain_rules() {
        assert_eq!(plain("See https://example.com/a?b=1 now"), "See link now");
        assert_eq!(plain("*Hi* @ava, ~so~ `good` _day_"), "Hi ava, so good day");
        assert_eq!(
            plain("Dinner \u{1F355}\u{1F44D}\u{1F3FD} ready \u{2764}\u{FE0F}"),
            "Dinner ready"
        );
        assert_eq!(
            plain("a\u{7}b<speak>c</speak>\u{202E}d"),
            "a b speak c /speak d"
        );
        assert_eq!(
            plain("**Bold** and __this__ and ~~old~~ and `cargo test`"),
            "Bold and this and old and cargo test"
        );
    }

    #[test]
    fn numbers_times_abbreviations() {
        assert_eq!(normalize("3:30"), "three thirty");
        assert_eq!(normalize("Meet at 3:05"), "Meet at three oh five");
        assert_eq!(normalize("3:00"), "three o'clock");
        assert_eq!(normalize("7:00 pm"), "seven pee em");
        assert_eq!(normalize("10:15am"), "ten fifteen ay em");
        assert_eq!(normalize("15:00"), "fifteen hundred");
        assert_eq!(normalize("I have 23 apples"), "I have twenty three apples");
        assert_eq!(normalize("1,250"), "one thousand two hundred fifty");
        assert_eq!(normalize("3.5"), "three point five");
        assert_eq!(normalize("1923"), "nineteen twenty three");
        assert_eq!(normalize("2007"), "two thousand seven");
        assert_eq!(
            normalize("the 1st and the 22nd"),
            "the first and the twenty second"
        );
        assert_eq!(normalize("007"), "zero zero seven");
        assert_eq!(normalize("It's $15"), "It's fifteen dollars");
        assert_eq!(normalize("\u{A3}1.50"), "one pound fifty");
        assert_eq!(number_words(2_000_005), "two million five");
        assert_eq!(ordinal_words(90), "ninetieth");
        assert_eq!(normalize("lol, brb"), "laughing out loud, be right back");
        assert_eq!(normalize("IDK. omw"), "I don't know. on my way");
        assert_eq!(normalize("lollipop typing"), "lollipop typing");
        assert_eq!(
            normalize("Dr. Lee and Mr. Smith met Mrs. Jones"),
            "Doctor Lee and Mister Smith met Missus Jones"
        );
        assert_eq!(
            normalize("St. Olaf lived on Main St."),
            "Saint Olaf lived on Main Street."
        );
        assert_eq!(
            normalize("Apples, pears etc. Then more"),
            "Apples, pears et cetera. Then more"
        );
        assert_eq!(
            normalize("e.g. this, i.e. that"),
            "for example this, that is that"
        );
        assert_eq!(
            normalize("It\u{2019}s \u{201C}fine\u{201D}"),
            "It's \"fine\""
        );
        assert_eq!(normalize("Okay.. See you"), "Okay. See you");
        assert_eq!(
            texts(&phrases("Dr. Lee is here.")),
            vec!["Doctor Lee is here."]
        );
    }

    #[test]
    fn pauses_follow_the_chat_rules() {
        let p = phrases("Hello there, friend. How are you?\nFine!");
        assert_eq!(
            texts(&p),
            vec!["Hello there,", "friend.", "How are you?", "Fine!"]
        );
        assert_eq!(pauses(&p), vec![250, 500, 500, 0]);
        let p = phrases("Well... maybe\u{2026} yes");
        assert_eq!(texts(&p), vec!["Well", "maybe", "yes"]);
        assert_eq!(pauses(&p), vec![500, 500, 0]);
        assert_eq!(texts(&phrases("Wait! !!")), vec!["Wait!"]);
        assert!(phrases("").is_empty());
        let long = "We walked all the way down to the old river in the morning light because the boats were coming in from the sea that day";
        let p = phrases(long);
        assert_eq!(p.len(), 2);
        assert!(p[1].text.starts_with("because"));
        assert_eq!(p[0].pause_ms, PAUSE_COMMA);
        assert_eq!(phrases("I stayed home because it rained").len(), 1);
    }

    #[test]
    fn code_block_is_one_phrase() {
        let p = phrases("Here it is:\n\n```rust\nfn main() { println!(\"[[x]]\"); }\n```\n\nDone.");
        assert_eq!(
            p,
            vec![
                ph("Here it is:", 400),
                ph("There's a code block here.", 500),
                ph("Done.", 0)
            ]
        );
        // Unclosed: the rest is code.
        assert_eq!(texts(&phrases("~~~\nsecret\nstuff")), vec![CODE_BLOCK]);
        assert_eq!(
            plain("a\n```\nx\n```\nb"),
            "a\nThere's a code block here.\nb"
        );
    }

    #[test]
    fn links_read_their_words() {
        let md = "See [the docs](https://example.com/a_(b)) or https://x.y/z now.";
        assert_eq!(plain(md), "See the docs or link now.");
        assert_eq!(texts(&phrases(md)), vec!["See the docs or link now."]);
        assert_eq!(
            plain("Go to <https://a.b> or ![a cat](cat.png)."),
            "Go to link or a cat."
        );
    }

    #[test]
    fn blocks_are_phrases() {
        let p =
            phrases("Steps:\n- First thing\n- Second thing\n  more of it\n1. Third\n* [x] Done");
        assert_eq!(
            texts(&p),
            vec![
                "Steps:",
                "First thing",
                "Second thing more of it",
                "Third",
                "Done"
            ]
        );
        assert_eq!(pauses(&p), vec![400, 400, 400, 400, 0]);
        let p = phrases("# Title\nBody text\nsame paragraph\n\n---\n\n> Quoted");
        assert_eq!(
            p,
            vec![
                ph("Title", PAUSE_HEADING),
                ph("Body text same paragraph", 400),
                ph("Quoted", 0)
            ]
        );
        let p = phrases("Setext\n===\n\n| Name | Count |\n|------|------:|\n| a | 2 |");
        assert_eq!(texts(&p), vec!["Setext", "Name,", "Count", "a,", "two"]);
        assert_eq!(pauses(&p), vec![PAUSE_HEADING, 250, 400, 250, 0]);
    }

    #[test]
    fn long_words_are_cut() {
        let x = "x".repeat(100);
        assert_eq!(
            plain(&x),
            format!("{} {} {}", "x".repeat(40), "x".repeat(40), "x".repeat(20))
        );
        for p in phrases(&x) {
            assert!(p.text.split(' ').all(|w| w.chars().count() <= MAX_WORD));
        }
    }

    #[test]
    fn long_text_is_not_cut() {
        // No hard length cap: 10,000+ characters reach the engine.
        // Use 2001 repetitions to ensure we cross the 10,000 char mark even after
        // whitespace normalisation (each "word " → "word" = 4 chars; 10,000 × 4/5 = 8,000
        // minimum; but plain() joins with "\n" so we check for "words survived").
        let ten_k = "word ".repeat(2001); // 10,005 chars raw
        let p = plain(&ten_k);
        // After clean(), the result is "word word … word" (4 chars × 2001 + spaces between).
        // 2001 words × 4 + 2000 spaces = 10,004 chars. Confirm more than 9,000 chars remain.
        assert!(p.chars().count() >= 9_000, "text was cut short");
        // Pieces are still at most MAX_PIECE each.
        let run = "ab ".repeat(1000);
        assert!(
            phrases(&run)
                .iter()
                .all(|p| p.text.chars().count() <= MAX_PIECE)
        );
        let comma = format!("{}, {}", "a".repeat(300), "b".repeat(300));
        let lp = pieces(&comma);
        assert_eq!(lp.len(), 2);
        assert_eq!(lp[0].chars().count(), 301);
    }

    /// B6 / PCE-15, PCE-16, PCE-31: no hard length cap; non-Latin logs a
    /// warning (no panic, no hang); negative numbers and 4-digit years read
    /// as words.
    #[test]
    fn speech_text() {
        // No truncation at 10,000 chars.
        let long = format!("{} words.", "word ".repeat(2000));
        let p = phrases(&long);
        let total: usize = p.iter().map(|p| p.text.chars().count()).sum();
        // After whitespace normalisation the total is slightly under 10,000; > 9,900 confirms
        // no hard 4,000-char cap is applied.
        assert!(total >= 9_900, "text was truncated at {total} chars");

        // Negative number.
        assert_eq!(normalize("-5"), "negative five");
        assert_eq!(
            normalize("temperature was -3"),
            "temperature was negative three"
        );

        // 4-digit year (PCE-31).
        assert_eq!(normalize("1990"), "nineteen ninety");
        assert_eq!(normalize("2001"), "two thousand one");

        // Non-Latin: no panic, returns empty phrase (warning goes to stderr).
        let non_latin = "\u{418}\u{43C}\u{44F}"; // Cyrillic "Имя"
        let p = phrases(non_latin);
        // Either empty or only digits/Latin from normalisation.
        assert!(
            p.iter()
                .all(|p| !p.text.chars().any(|c| c as u32 > 0x7F && !is_latin(c)))
        );
    }
}
