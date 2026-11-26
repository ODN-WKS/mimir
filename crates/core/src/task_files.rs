//! Pictures and files sent with a project task's message ("task_files").
//!
//! They are kept in a folder of their own for that task, under Mímir's data
//! folder (never inside the project's own files), so the task's Claude Code
//! can read them; the message to Claude Code names each one ("Attached: ...").
//! The folder goes when the task goes. Photos lose their location and camera
//! details on the way in.

use mimir_proto::{Picture, SentFile};
use std::path::{Path, PathBuf};

use base64::Engine as _;

pub const MAX_FILES: usize = 3;
pub const MAX_FILE_BYTES: usize = 10 * 1024 * 1024;
pub const MAX_PICTURES: usize = 4;
pub const MAX_PICTURE_BYTES: usize = 5 * 1024 * 1024;

const CANT_CLEAN: &str = "A picture's details (like where it was taken) couldn't be taken off, so it wasn't kept. Send it as a JPEG or PNG.";
const KEEP_FAILED: &str = "Mímir couldn't keep the files. Try again.";

fn safe_id(task: &str) -> String {
    task.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// The folder one task's attachments live in. A Whisper (incognito) task's
/// are kept inside its own sealed folder (in memory, gone with the task or
/// a crash), never in Mímir's data folder.
pub fn dir_for(data_dir: &Path, task: &str, incognito: bool) -> PathBuf {
    if incognito {
        return crate::incognito::dir(task).join("files");
    }
    data_dir.join("task-files").join(safe_id(task))
}

/// Deletes a task's attachments (nothing sits unused).
pub fn remove(data_dir: &Path, task: &str) {
    let _ = std::fs::remove_dir_all(dir_for(data_dir, task, false));
}

/// At start-up: deletes the attachments of every task that isn't kept (a
/// crash or a missed delete leaves nothing behind).
pub fn sweep(data_dir: &Path, keep: &[String]) {
    let keep: std::collections::HashSet<String> = keep.iter().map(|k| safe_id(k)).collect();
    let root = data_dir.join("task-files");
    for e in std::fs::read_dir(&root).into_iter().flatten().flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        if !keep.contains(&name) {
            let p = e.path();
            if p.is_dir() {
                let _ = std::fs::remove_dir_all(p);
            } else {
                let _ = std::fs::remove_file(p);
            }
        }
    }
}

/// Characters that hide or blur a name (Unicode "format" characters such as
/// the right-to-left override, and separators), and the list separator.
fn blurs(c: char) -> bool {
    c.is_control()
        || matches!(
            c,
            '"' | ':'
                | '*'
                | '?'
                | '<'
                | '>'
                | '|'
                | '\u{00AD}'
                | '\u{061C}'
                | '\u{06DD}'
                | '\u{070F}'
                | '\u{180E}'
                | '\u{FEFF}'
                | '\u{2028}'
                | '\u{2029}'
        )
        || ('\u{0600}'..='\u{0605}').contains(&c)
        || ('\u{200B}'..='\u{200F}').contains(&c)
        || ('\u{202A}'..='\u{202E}').contains(&c)
        || ('\u{2060}'..='\u{206F}').contains(&c)
        || ('\u{FFF9}'..='\u{FFFB}').contains(&c)
        || ('\u{E0000}'..='\u{E007F}').contains(&c)
        || (c.is_whitespace() && c != ' ')
}

fn safe_name(n: &str) -> String {
    let base = n.rsplit(['/', '\\']).next().unwrap_or("file");
    let s: String = base
        .chars()
        .filter(|c| !blurs(*c))
        .map(|c| if c == ',' { '_' } else { c })
        .collect();
    let s = s.trim().trim_start_matches('.').to_string();
    if s.is_empty() {
        return "file".into();
    }
    // At most 120 bytes, cut between characters, the ending kept.
    const MAX: usize = 120;
    if s.len() <= MAX {
        return s;
    }
    let ext = match s.rsplit_once('.') {
        Some((_, e)) if e.len() <= 12 && !e.is_empty() => format!(".{e}"),
        _ => String::new(),
    };
    let mut out = String::new();
    for c in s.chars() {
        if out.len() + c.len_utf8() + ext.len() > MAX {
            break;
        }
        out.push(c);
    }
    out + &ext
}

/// A message's pictures and files, checked and cleaned in memory: nothing
/// is on disk yet, so a refusal leaves nothing behind.
#[derive(Default)]
pub struct Prepared {
    /// (file name, bytes) of each thing to keep, pictures first.
    items: Vec<(String, Vec<u8>)>,
    /// The cleaned pictures to give Claude Code.
    pictures: Vec<Picture>,
    /// The line to show under each file: (file name, what was done).
    notes: Vec<(String, String)>,
    /// Files that still have hidden details (a kind Mímir can't clean yet).
    uncleaned: Vec<String>,
}

impl Prepared {
    /// The dim line for each file ("Hidden details removed.").
    pub fn notes(&self) -> &[(String, String)] {
        &self.notes
    }
    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }
    /// Whether some file still has hidden details, so it must not go to an
    /// online model.
    pub fn has_uncleaned(&self) -> bool {
        !self.uncleaned.is_empty()
    }
}

/// Checks counts and sizes, and cleans every picture (also one sent as a
/// file) and every file so no location, camera or author details are kept.
/// Anything that can't be cleaned is refused in one plain line.
pub fn prepare(pictures: &[Picture], files: &[SentFile]) -> Result<Prepared, String> {
    if pictures.len() > MAX_PICTURES {
        return Err("Four pictures at most in one message.".into());
    }
    if files.len() > MAX_FILES {
        return Err("Three files at most in one message.".into());
    }
    let size = |b64: &str| b64.len() / 4 * 3;
    if pictures.iter().any(|p| size(&p.base64) > MAX_PICTURE_BYTES) {
        return Err("A picture is over 5 MB. Send a smaller one.".into());
    }
    if files.iter().any(|f| size(&f.base64) > MAX_FILE_BYTES) {
        return Err("A file is over 10 MB. Send a smaller part of it.".into());
    }
    let b64 = base64::engine::general_purpose::STANDARD;
    let mut out = Prepared::default();
    for (i, p) in pictures.iter().enumerate() {
        let bytes = b64
            .decode(p.base64.as_bytes())
            .map_err(|_| "A picture didn't arrive whole. Send it again.".to_string())?;
        let clean = crate::pictures::clean(&bytes).ok_or(CANT_CLEAN)?;
        let kept = b64
            .decode(clean.base64.as_bytes())
            .map_err(|_| CANT_CLEAN)?;
        out.items.push((format!("picture-{}.jpg", i + 1), kept));
        out.pictures.push(clean);
    }
    for f in files {
        let bytes = b64
            .decode(f.base64.as_bytes())
            .map_err(|_| "A file didn't arrive whole. Send it again.".to_string())?;
        if bytes.len() > MAX_FILE_BYTES {
            return Err("A file is over 10 MB. Send a smaller part of it.".into());
        }
        if bytes.is_empty() {
            return Err("A file is empty. Pick another one.".into());
        }
        let name = safe_name(&f.name);
        if crate::pictures::looks_like_picture(&bytes, &name) {
            let clean = crate::pictures::clean(&bytes).ok_or(CANT_CLEAN)?;
            let kept = b64
                .decode(clean.base64.as_bytes())
                .map_err(|_| CANT_CLEAN)?;
            let stem = name.rsplit_once('.').map_or(name.as_str(), |(s, _)| s);
            out.items.push((format!("{}.jpg", safe_name(stem)), kept));
            out.pictures.push(clean);
        } else {
            // Every other file loses its author, dates and comments first;
            // one that can't be cleaned is not kept.
            let w = crate::wipe::wipe(&name, &bytes)?;
            out.notes.push((name.clone(), w.note()));
            if w.kept_as_it_was.is_some() {
                out.uncleaned.push(name.clone());
            }
            out.items.push((name, w.bytes));
        }
    }
    Ok(out)
}

/// In the name of a kept file that still has hidden details.
const LOCAL_ONLY: &str = "local-only-";

/// Takes away the files of a task that still have hidden details (it moved
/// to an online model). How many went.
pub fn drop_local_only(dir: &Path) -> usize {
    let Ok(rd) = std::fs::read_dir(dir) else {
        return 0;
    };
    rd.flatten()
        .filter(|e| {
            e.file_name()
                .to_string_lossy()
                .contains(&format!("-{LOCAL_ONLY}"))
        })
        .filter(|e| std::fs::remove_file(e.path()).is_ok())
        .count()
}

/// A cleaned copy of one of the person's own files, kept for the task: what
/// an online model reads instead of the original. Returns where it is and the
/// line to show; a file that can't be cleaned is refused in plain words.
pub fn cleaned_copy(dir: &Path, path: &Path) -> Result<(PathBuf, String), String> {
    use std::io::Read as _;
    let name = safe_name(
        &path
            .file_name()
            .map_or_else(|| "file".to_string(), |n| n.to_string_lossy().into_owned()),
    );
    let unread = || "A file couldn't be read. Pick another one.".to_string();
    // Only a real file: never a device, a pipe or a system file whose size
    // isn't its size.
    if !std::fs::metadata(path).is_ok_and(|m| m.is_file()) {
        return Err(unread());
    }
    let mut bytes = Vec::new();
    std::fs::File::open(path)
        .and_then(|f| f.take(MAX_FILE_BYTES as u64 + 1).read_to_end(&mut bytes))
        .map_err(|_| unread())?;
    if bytes.len() > MAX_FILE_BYTES {
        return Err("A file is over 10 MB. Send a smaller part of it.".to_string());
    }
    let w = crate::wipe::wipe(&name, &bytes)?;
    if w.kept_as_it_was.is_some() {
        return Err(crate::wipe::NOT_SENT_ONLINE.into());
    }
    // The same file sent again uses the copy already kept.
    let tail = format!("-{name}");
    if let Ok(rd) = std::fs::read_dir(dir) {
        for e in rd.flatten() {
            let q = e.path();
            if e.file_name().to_string_lossy().ends_with(&tail)
                && e.metadata().is_ok_and(|m| m.len() == w.bytes.len() as u64)
                && std::fs::read(&q).is_ok_and(|b| b == w.bytes)
            {
                return Ok((q, w.note()));
            }
        }
    }
    let one = Prepared {
        items: vec![(name, w.bytes.clone())],
        ..Default::default()
    };
    let mut kept = write(dir, &one)?;
    let at = kept.paths.pop().ok_or(KEEP_FAILED)?;
    Ok((at, w.note()))
}

/// What was kept: the pictures to give Claude Code and the saved paths to
/// name in the message.
pub struct Kept {
    pub pictures: Vec<Picture>,
    pub paths: Vec<PathBuf>,
}

/// Writes what was prepared into `dir` (private to the owner). If any part
/// fails, everything written for it is removed again.
pub fn write(dir: &Path, p: &Prepared) -> Result<Kept, String> {
    use std::io::Write as _;
    use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
    let mut kept = Kept {
        pictures: p.pictures.clone(),
        paths: vec![],
    };
    if p.items.is_empty() {
        return Ok(kept);
    }
    let made = !dir.exists();
    let undo = |paths: &[PathBuf]| {
        for f in paths {
            let _ = std::fs::remove_file(f);
        }
        if made {
            let _ = std::fs::remove_dir(dir);
        }
    };
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(dir)
        .map_err(|_| KEEP_FAILED)?;
    if let Some(root) = dir.parent() {
        let _ = std::fs::set_permissions(root, std::fs::Permissions::from_mode(0o700));
    }
    let first = std::fs::read_dir(dir).map(|d| d.count()).unwrap_or(0);
    for (i, (name, bytes)) in p.items.iter().enumerate() {
        // A file that still has hidden details is marked, so it can be taken
        // away if the task moves to an online model.
        let local = if p.uncleaned.contains(name) {
            LOCAL_ONLY
        } else {
            ""
        };
        let path = dir.join(format!("{}-{local}{name}", first + i + 1));
        let r = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
            .and_then(|mut f| f.write_all(bytes));
        kept.paths.push(path);
        if r.is_err() {
            undo(&kept.paths);
            return Err(KEEP_FAILED.into());
        }
    }
    Ok(kept)
}

/// The message with its attachments named.
pub fn named(text: &str, paths: &[PathBuf]) -> String {
    if paths.is_empty() {
        return text.to_string();
    }
    let list: Vec<String> = paths.iter().map(|p| p.display().to_string()).collect();
    format!("{text}\n\nAttached: {}", list.join(", "))
}

/// Drops the closing "[N picture(s) attached]" line the runner adds after the
/// path line (the file chips already say it).
fn strip_picture_line(text: &str) -> &str {
    if let Some((head, last)) = text.rsplit_once('\n') {
        let l = last.trim();
        if let Some(n) = l.strip_prefix('[').and_then(|r| {
            r.strip_suffix(" pictures attached]")
                .or_else(|| r.strip_suffix(" picture attached]"))
        }) && !n.is_empty()
            && n.bytes().all(|b| b.is_ascii_digit())
        {
            return head;
        }
    }
    text
}

/// The message as the person wrote it, and the names of what they attached:
/// the "Attached: /path/a, /path/b" tail that [`named`] adds for Claude Code
/// is never shown, only each file's own name (without its number and its
/// local-only marker).
pub fn split_attached(text: &str) -> (String, Vec<String>) {
    let full = text;
    let text = strip_picture_line(text);
    let Some(at) = text.rfind("Attached: /") else {
        return (full.to_string(), vec![]);
    };
    if at != 0 && !text[..at].ends_with("\n\n") {
        return (full.to_string(), vec![]);
    }
    let tail = &text[at + "Attached: ".len()..];
    if tail.contains('\n') {
        return (full.to_string(), vec![]);
    }
    let names = tail
        .split(", /")
        .map(|p| {
            let p = p.trim_start_matches('/');
            let file = p.rsplit('/').next().unwrap_or(p);
            let file = match file.split_once('-') {
                Some((n, rest)) if !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit()) => rest,
                _ => file,
            };
            file.strip_prefix(LOCAL_ONLY).unwrap_or(file).to_string()
        })
        .collect();
    (text[..at].trim_end().to_string(), names)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn p09_item1_the_attached_tail_becomes_names() {
        let t = named(
            "Look at these",
            &[
                PathBuf::from("/home/x/.local/share/mimir/task-files/t-1/1-plan.pdf"),
                PathBuf::from("/home/x/.local/share/mimir/task-files/t-1/2-local-only-a, b.png"),
            ],
        );
        let (text, names) = split_attached(&t);
        assert_eq!(text, "Look at these");
        assert_eq!(names, vec!["plan.pdf", "a, b.png"]);
        assert!(!text.contains("/home"));
        // Nothing attached: untouched. Words that merely mention it: untouched.
        assert_eq!(split_attached("Hello"), ("Hello".into(), vec![]));
        let said = "I wrote Attached: /etc/hosts in a line";
        assert_eq!(split_attached(said), (said.into(), vec![]));
        // Only attachments.
        let (text, names) = split_attached("Attached: /a/b/1-x.jpg");
        assert_eq!((text.as_str(), names.len()), ("", 1));
    }

    #[test]
    fn p10_the_picture_count_line_is_dropped_too() {
        let p = "/home/deck/.local/share/mimir/task-files/1795472443-2-2/6-picture-1.jpg";
        let (t, n) = split_attached(&format!("hello\n\nAttached: {p}\n[1 picture attached]"));
        assert_eq!(
            (t.as_str(), n),
            ("hello", vec!["picture-1.jpg".to_string()])
        );
        let three = "Attached: /a/1-picture-1.jpg, /a/2-picture-2.jpg, /a/3-picture-3.jpg";
        let (t, n) = split_attached(&format!("hi\n\n{three}\n[3 pictures attached]"));
        assert_eq!((t.as_str(), n.len()), ("hi", 3));
        let (t, n) = split_attached(
            "hi\n\nAttached: /a/1-plan.pdf, /a/2-picture-1.jpg\n[1 picture attached]",
        );
        assert_eq!(
            (t.as_str(), n),
            (
                "hi",
                vec!["plan.pdf".to_string(), "picture-1.jpg".to_string()]
            )
        );
        // Only pictures, no files: the words stay, the count line goes.
        assert_eq!(
            split_attached("look\n[2 pictures attached]").0,
            "look\n[2 pictures attached]"
        );
    }

    fn tmp(name: &str) -> PathBuf {
        let d =
            std::env::temp_dir().join(format!("mimir-task-files-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn jpeg() -> Vec<u8> {
        let mut out = Vec::new();
        image::RgbImage::from_pixel(4, 4, image::Rgb([9, 9, 9]))
            .write_to(
                &mut std::io::Cursor::new(&mut out),
                image::ImageFormat::Jpeg,
            )
            .unwrap();
        out
    }

    #[test]
    fn pictures_and_files_are_kept_named_and_removed() {
        let d = tmp("keep");
        let b64 = base64::engine::general_purpose::STANDARD;
        let pic = Picture {
            media_type: "image/jpeg".into(),
            base64: b64.encode(jpeg()),
        };
        let file = SentFile {
            name: "../Lease.pdf".into(),
            media_type: "application/pdf".into(),
            base64: b64.encode(crate::wipe::tests::sample_pdf()),
        };
        let p = prepare(&[pic], &[file]).unwrap();
        let k = write(&dir_for(&d, "t-1", false), &p).unwrap();
        assert_eq!(k.pictures.len(), 1);
        assert_eq!(k.paths.len(), 2);
        assert!(
            k.paths
                .iter()
                .all(|p| p.exists() && p.starts_with(d.join("task-files")))
        );
        assert!(k.paths[1].to_string_lossy().ends_with("Lease.pdf"));
        assert!(named("Look", &k.paths).contains("Attached: "));
        remove(&d, "t-1");
        assert!(!dir_for(&d, "t-1", false).exists());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn task_files_are_wiped_and_one_that_cant_be_is_refused() {
        let b64 = base64::engine::general_purpose::STANDARD;
        let f = |name: &str, b: &[u8]| SentFile {
            name: name.into(),
            media_type: String::new(),
            base64: b64.encode(b),
        };
        let p = prepare(&[], &[f("Lease.pdf", &crate::wipe::tests::sample_pdf())]).unwrap();
        assert_eq!(
            p.notes(),
            [(
                "Lease.pdf".to_string(),
                "Hidden details removed.".to_string()
            )]
        );
        assert!(!String::from_utf8_lossy(&p.items[0].1).contains("Alice Example"));
        let e = prepare(&[], &[f("Bad.pdf", b"%PDF-1.4 broken")])
            .err()
            .unwrap();
        assert_eq!(e, crate::wipe::FAILED);
    }

    #[test]
    fn too_many_or_too_big_is_refused_in_plain_words() {
        let b64 = base64::engine::general_purpose::STANDARD;
        let f = |n: usize| SentFile {
            name: "a.txt".into(),
            media_type: String::new(),
            base64: b64.encode(vec![b'a'; n]),
        };
        assert!(prepare(&[], &[f(1), f(1), f(1), f(1)]).is_err());
        assert!(prepare(&[], &[f(MAX_FILE_BYTES + 1)]).is_err());
    }

    #[test]
    fn sweep_keeps_only_stored_tasks() {
        let d = tmp("sweep");
        for t in ["keep-1", "gone-2"] {
            let x = dir_for(&d, t, false);
            std::fs::create_dir_all(&x).unwrap();
            std::fs::write(x.join("a"), b"x").unwrap();
        }
        sweep(&d, &["keep-1".to_string()]);
        assert!(dir_for(&d, "keep-1", false).exists());
        assert!(!dir_for(&d, "gone-2", false).exists());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_whispers_files_go_inside_its_sealed_folder() {
        let d = tmp("whisper");
        let p = dir_for(&d, "w-1", true);
        assert!(p.starts_with(crate::incognito::dir("w-1")));
        assert!(!p.starts_with(&d));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn names_are_cut_by_bytes_and_blur_nothing() {
        let n = safe_name(&format!("{}.txt", "é".repeat(200)));
        assert!(n.len() <= 120 && n.ends_with(".txt"), "{n}");
        let n = safe_name("a\u{202E}txt.exe, b\u{2028}.txt");
        assert!(
            !n.contains('\u{202E}') && !n.contains(',') && !n.contains('\u{2028}'),
            "{n}"
        );
    }

    #[test]
    fn m1b_6c_a_file_that_could_not_be_cleaned_is_marked() {
        let b64 = base64::engine::general_purpose::STANDARD;
        let f = |name: &str, b: &[u8]| SentFile {
            name: name.into(),
            media_type: String::new(),
            base64: b64.encode(b),
        };
        let p = prepare(&[], &[f("Lease.pdf", &crate::wipe::tests::sample_pdf())]).unwrap();
        assert!(!p.has_uncleaned());
        let p = prepare(&[], &[f("data.bin", b"abc")]).unwrap();
        assert!(p.has_uncleaned());
        assert!(p.notes()[0].1.starts_with("Kept as it was:"));
    }

    #[test]
    fn m1b2_3_only_real_files_are_copied_and_the_same_file_uses_one_copy() {
        let d = std::env::temp_dir().join(format!("m1b2-copy-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        assert!(cleaned_copy(&d, Path::new("/dev/zero")).is_err());
        assert!(cleaned_copy(&d, &d).is_err());
        let src = d.join("a.txt");
        std::fs::write(&src, b"words").unwrap();
        let kept = d.join("kept");
        let (one, _) = cleaned_copy(&kept, &src).unwrap();
        let (two, _) = cleaned_copy(&kept, &src).unwrap();
        assert_eq!(one, two);
        assert_eq!(std::fs::read_dir(&kept).unwrap().count(), 1);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn m1b2_9_files_with_hidden_details_are_taken_away_when_a_task_goes_online() {
        let b64 = base64::engine::general_purpose::STANDARD;
        let f = |name: &str, b: &[u8]| SentFile {
            name: name.into(),
            media_type: String::new(),
            base64: b64.encode(b),
        };
        let p = prepare(&[], &[f("data.bin", b"abc"), f("notes.txt", b"words")]).unwrap();
        let d = std::env::temp_dir().join(format!("m1b2-drop-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        write(&d, &p).unwrap();
        assert_eq!(drop_local_only(&d), 1);
        let left: Vec<String> = std::fs::read_dir(&d)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(left.len(), 1);
        assert!(left[0].ends_with("notes.txt"));
        let _ = std::fs::remove_dir_all(&d);
    }
}
