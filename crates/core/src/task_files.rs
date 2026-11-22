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

/// The folder one task's attachments live in.
pub fn dir_for(data_dir: &Path, task: &str) -> PathBuf {
    let safe: String = task
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect();
    data_dir.join("task-files").join(safe)
}

/// Deletes a task's attachments (nothing sits unused).
pub fn remove(data_dir: &Path, task: &str) {
    let _ = std::fs::remove_dir_all(dir_for(data_dir, task));
}

/// A JPEG without its APP1 (camera details, location) and APP13 segments.
/// Anything that isn't a well-formed JPEG comes back unchanged.
pub fn strip_jpeg(bytes: &[u8]) -> Vec<u8> {
    if bytes.len() < 4 || bytes[0] != 0xFF || bytes[1] != 0xD8 {
        return bytes.to_vec();
    }
    let mut out = vec![0xFF, 0xD8];
    let mut i = 2;
    while i + 4 <= bytes.len() {
        if bytes[i] != 0xFF {
            return bytes.to_vec();
        }
        let marker = bytes[i + 1];
        if marker == 0xFF {
            i += 1;
            continue;
        }
        // Start of scan: the picture data follows to the end; keep it all.
        if marker == 0xDA {
            out.extend_from_slice(&bytes[i..]);
            return out;
        }
        let len = u16::from_be_bytes([bytes[i + 2], bytes[i + 3]]) as usize;
        if len < 2 || i + 2 + len > bytes.len() {
            return bytes.to_vec();
        }
        if marker != 0xE1 && marker != 0xED {
            out.extend_from_slice(&bytes[i..i + 2 + len]);
        }
        i += 2 + len;
    }
    bytes.to_vec()
}

fn safe_name(n: &str) -> String {
    let base = n.rsplit(['/', '\\']).next().unwrap_or("file");
    let s: String = base
        .chars()
        .filter(|c| !c.is_control() && !matches!(c, '"' | ':' | '*' | '?' | '<' | '>' | '|'))
        .take(100)
        .collect();
    let s = s.trim().trim_start_matches('.').to_string();
    if s.is_empty() { "file".into() } else { s }
}

fn ext_of(media_type: &str) -> &'static str {
    match media_type {
        "image/png" => "png",
        "image/jpeg" | "image/jpg" => "jpg",
        "image/gif" => "gif",
        "image/webp" => "webp",
        _ => "bin",
    }
}

/// What was kept: the pictures to give Claude Code (without location) and
/// the saved paths to name in the message.
pub struct Kept {
    pub pictures: Vec<Picture>,
    pub paths: Vec<PathBuf>,
}

/// Refuses too many or too big attachments before anything is started.
pub fn check(pictures: &[Picture], files: &[SentFile]) -> Result<(), String> {
    let size = |b64: &str| b64.len() / 4 * 3;
    if pictures.len() > MAX_PICTURES {
        return Err("Four pictures at most in one message.".into());
    }
    if files.len() > MAX_FILES {
        return Err("Three files at most in one message.".into());
    }
    if pictures.iter().any(|p| size(&p.base64) > MAX_PICTURE_BYTES) {
        return Err("A picture is over 5 MB. Send a smaller one.".into());
    }
    if files.iter().any(|f| size(&f.base64) > MAX_FILE_BYTES) {
        return Err("A file is over 10 MB. Send a smaller part of it.".into());
    }
    Ok(())
}

/// Saves a message's pictures and files for a task. Errors are one plain line.
pub fn save(
    data_dir: &Path,
    task: &str,
    pictures: &[Picture],
    files: &[SentFile],
) -> Result<Kept, String> {
    let mut kept = Kept {
        pictures: vec![],
        paths: vec![],
    };
    if pictures.is_empty() && files.is_empty() {
        return Ok(kept);
    }
    if pictures.len() > MAX_PICTURES {
        return Err("Four pictures at most in one message.".into());
    }
    if files.len() > MAX_FILES {
        return Err("Three files at most in one message.".into());
    }
    let b64 = base64::engine::general_purpose::STANDARD;
    let dir = dir_for(data_dir, task);
    std::fs::create_dir_all(&dir).map_err(|_| "Mímir couldn't keep the files. Try again.")?;
    let stamp = std::fs::read_dir(&dir).map(|d| d.count()).unwrap_or(0);
    let mut n = stamp;
    for p in pictures {
        let bytes = b64
            .decode(p.base64.as_bytes())
            .map_err(|_| "A picture didn't arrive whole. Send it again.".to_string())?;
        if bytes.len() > MAX_PICTURE_BYTES {
            return Err("A picture is over 5 MB. Send a smaller one.".into());
        }
        let bytes = strip_jpeg(&bytes);
        n += 1;
        let path = dir.join(format!("picture-{n}.{}", ext_of(&p.media_type)));
        std::fs::write(&path, &bytes).map_err(|_| "Mímir couldn't keep the files. Try again.")?;
        kept.pictures.push(Picture {
            media_type: p.media_type.clone(),
            base64: b64.encode(&bytes),
        });
        kept.paths.push(path);
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
        n += 1;
        let path = dir.join(format!("{n}-{}", safe_name(&f.name)));
        std::fs::write(&path, &bytes).map_err(|_| "Mímir couldn't keep the files. Try again.")?;
        kept.paths.push(path);
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

    #[test]
    fn pictures_and_files_are_kept_named_and_removed() {
        let d = tmp("keep");
        let b64 = base64::engine::general_purpose::STANDARD;
        let pic = Picture {
            media_type: "image/png".into(),
            base64: b64.encode(b"\x89PNGdata"),
        };
        let file = SentFile {
            name: "../Lease.pdf".into(),
            media_type: "application/pdf".into(),
            base64: b64.encode(b"%PDF-1"),
        };
        let k = save(&d, "t-1", &[pic], &[file]).unwrap();
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
        assert!(!dir_for(&d, "t-1").exists());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn too_many_or_too_big_is_refused_in_plain_words() {
        let d = tmp("limits");
        let b64 = base64::engine::general_purpose::STANDARD;
        let f = |n: usize| SentFile {
            name: "a.txt".into(),
            media_type: String::new(),
            base64: b64.encode(vec![b'a'; n]),
        };
        assert!(save(&d, "t", &[], &[f(1), f(1), f(1), f(1)]).is_err());
        assert!(save(&d, "t", &[], &[f(MAX_FILE_BYTES + 1)]).is_err());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_photos_location_is_removed() {
        // SOI, APP1 "Exif" with a pretend location, APP0, SOS, data, EOI.
        let mut j = vec![0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x0A];
        j.extend_from_slice(b"Exif\0GPS");
        j.extend_from_slice(&[0xFF, 0xE0, 0x00, 0x04, 1, 2]);
        j.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x02, 9, 9, 0xFF, 0xD9]);
        let out = strip_jpeg(&j);
        assert!(!out.windows(4).any(|w| w == b"Exif"));
        assert!(out.windows(2).any(|w| w == [0xFF, 0xE0]));
        assert!(out.ends_with(&[0xFF, 0xD9]));
        // Not a JPEG: unchanged.
        assert_eq!(strip_jpeg(b"hello"), b"hello");
    }
}
