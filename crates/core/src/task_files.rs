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
}

impl Prepared {
    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }
}

/// Checks counts and sizes, and cleans every picture (also one sent as a
/// file) so no location or camera details are kept. A picture that can't be
/// cleaned is refused in one plain line.
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
            out.items.push((name, bytes));
        }
    }
    Ok(out)
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
        let path = dir.join(format!("{}-{name}", first + i + 1));
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

#[cfg(test)]
mod tests {
    use super::*;

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
            base64: b64.encode(b"%PDF-1"),
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
}
