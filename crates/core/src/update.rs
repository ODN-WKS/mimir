//! Signed updates from the public repository's releases.
//!
//! Each release carries `Mimir-<version>.flatpak` and a `.sig`
//! file: an ed25519 signature, made with a key kept only on the owner's PC,
//! over "mimir <version>\n" followed by the file's SHA-256. The
//! version is part of what is signed, so an old release cannot be passed
//! off as a new one.

use crate::install::{Layout, write_atomic};
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::Command;

pub const REPO: &str = "ODN-WKS/mimir";

/// Default manifest URL: the newest release's `manifest.json`, with its
/// `manifest.json.sig` next to it. Override with `MIMIR_UPDATE_URL`.
pub const DEFAULT_UPDATE_URL: &str =
    "https://github.com/ODN-WKS/mimir/releases/latest/download/manifest.json";

/// "0.12" or "0.18.1" → (0, 12, 0) or (0, 18, 1).  Accepts two- or
/// three-part semver; the third part defaults to 0.
pub fn parse_version(v: &str) -> Option<(u32, u32, u32)> {
    let v = v.trim().trim_start_matches('v');
    let parts: Vec<&str> = v.splitn(3, '.').collect();
    match parts.as_slice() {
        [a, b] => Some((a.parse().ok()?, b.parse().ok()?, 0)),
        [a, b, c] => Some((a.parse().ok()?, b.parse().ok()?, c.parse().ok()?)),
        _ => None,
    }
}

pub fn is_newer(candidate: &str, current: &str) -> bool {
    match (parse_version(candidate), parse_version(current)) {
        (Some(c), Some(n)) => c > n,
        _ => false,
    }
}

/// Fetches a URL with curl, timing out after `timeout_secs`.
/// Returns the response body or an error message.
pub fn fetch_url(url: &str, timeout_secs: u64) -> Result<Vec<u8>, String> {
    let out = Command::new("curl")
        .args([
            "--silent",
            "--fail",
            "--location",
            "--max-time",
            &timeout_secs.to_string(),
            url,
        ])
        .output()
        .map_err(|_| "curl is not installed".to_string())?;
    if !out.status.success() {
        return Err(format!(
            "Update check failed (curl exited {}). Check failed — try again.",
            out.status.code().unwrap_or(-1)
        ));
    }
    Ok(out.stdout)
}

/// Fetches the signed update manifest from the public URL and returns its
/// raw bytes, or a clear error. Uses a 30 s timeout by default; pass a
/// different `timeout_secs` for tests.
pub fn fetch_manifest(url: &str, timeout_secs: u64) -> Result<Vec<u8>, String> {
    fetch_url(url, timeout_secs)
}

pub fn asset_name(version: &str) -> String {
    format!("Mimir-{version}.flatpak")
}

fn signed_message(version: &str, file: &[u8]) -> Vec<u8> {
    let mut m = format!("mimir {version}\n").into_bytes();
    m.extend_from_slice(&Sha256::digest(file));
    m
}

pub fn sign(key: &SigningKey, version: &str, file: &[u8]) -> [u8; 64] {
    key.sign(&signed_message(version, file)).to_bytes()
}

/// Checks a downloaded program against the public key built into the app.
pub fn verify(public: &[u8; 32], version: &str, file: &[u8], sig: &[u8]) -> Result<(), String> {
    let key = VerifyingKey::from_bytes(public).map_err(|_| "The built-in key is damaged.")?;
    let sig: [u8; 64] = sig
        .try_into()
        .map_err(|_| "The signature file is damaged.".to_string())?;
    key.verify(&signed_message(version, file), &Signature::from_bytes(&sig))
        .map_err(|_| "The download's signature does not match. It was not installed.".to_string())
}

pub fn public_from_hex(hex: &str) -> Option<[u8; 32]> {
    let hex = hex.trim();
    if hex.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, b) in out.iter_mut().enumerate() {
        *b = u8::from_str_radix(hex.get(i * 2..i * 2 + 2)?, 16).ok()?;
    }
    Some(out)
}

pub fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Where releases come from: a public manifest URL (default), or a
/// local folder (used by the tests).
#[derive(Debug, Clone)]
pub enum Source {
    /// Fetch the signed manifest from a public URL; no GitHub sign-in needed.
    Manifest(String),
    Folder(PathBuf),
}

impl Source {
    /// `MIMIR_UPDATES` names a folder; `MIMIR_UPDATE_URL` overrides the
    /// manifest URL; otherwise the built-in default is used.
    pub fn from_env() -> Self {
        if let Some(p) = std::env::var_os("MIMIR_UPDATES") {
            return Source::Folder(PathBuf::from(p));
        }
        let url =
            std::env::var("MIMIR_UPDATE_URL").unwrap_or_else(|_| DEFAULT_UPDATE_URL.to_string());
        Source::Manifest(url)
    }

    /// The newest published release's version. A manifest from the web is
    /// trusted only when its `.sig` file matches the built-in key; an
    /// unsigned or wrongly signed one is refused.
    pub fn latest(&self, public: &[u8; 32]) -> Result<Option<String>, String> {
        match self {
            Source::Folder(dir) => {
                let rd = std::fs::read_dir(dir).map_err(|e| e.to_string())?;
                Ok(rd
                    .flatten()
                    .filter_map(|e| {
                        let n = e.file_name().to_string_lossy().to_string();
                        let v = n.strip_prefix("Mimir-")?.strip_suffix(".flatpak")?;
                        parse_version(v).map(|p| (p, v.to_string()))
                    })
                    .max()
                    .map(|(_, v)| v))
            }
            Source::Manifest(url) => {
                let body = fetch_manifest(url, 30)?;
                let json = String::from_utf8_lossy(&body);
                let Some(version) = newest_tag(&json) else {
                    return Ok(None);
                };
                let sig = fetch_url(&format!("{url}.sig"), 30).map_err(|_| {
                    "The update list is not signed. It was not trusted.".to_string()
                })?;
                verify(public, &version, &body, &sig).map_err(|_| {
                    "The update list's signature does not match. It was not trusted.".to_string()
                })?;
                Ok(Some(version))
            }
        }
    }

    /// Downloads the program and its signature into `dir`.
    pub fn download(&self, version: &str, dir: &Path) -> Result<(PathBuf, PathBuf), String> {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        let name = asset_name(version);
        let (bin, sig) = (dir.join(&name), dir.join(format!("{name}.sig")));
        match self {
            Source::Folder(src) => {
                std::fs::copy(src.join(&name), &bin).map_err(|e| e.to_string())?;
                std::fs::copy(src.join(format!("{name}.sig")), &sig).map_err(|e| e.to_string())?;
            }
            Source::Manifest(manifest_url) => {
                let (bin_url, sig_url) = manifest_urls(manifest_url, version)?;
                let bin_data = fetch_url(&bin_url, 120)?;
                let sig_data = fetch_url(&sig_url, 30)?;
                std::fs::write(&bin, &bin_data).map_err(|e| e.to_string())?;
                std::fs::write(&sig, &sig_data).map_err(|e| e.to_string())?;
            }
        }
        Ok((bin, sig))
    }
}

/// The program's and its signature's addresses for `version`, from the
/// manifest entry (`url`, `sigUrl`); without them, next to the manifest.
/// The program is checked against the built-in key afterwards either way.
fn manifest_urls(manifest_url: &str, version: &str) -> Result<(String, String), String> {
    let body = fetch_manifest(manifest_url, 30)?;
    let v: Value =
        serde_json::from_slice(&body).map_err(|_| "The update list is damaged.".to_string())?;
    let tag = format!("v{version}");
    let entry = v
        .as_array()
        .and_then(|a| {
            a.iter()
                .find(|r| r.get("tagName").and_then(Value::as_str) == Some(&tag))
        })
        .ok_or_else(|| format!("The update list has no version {version}."))?;
    let field = |k: &str| entry.get(k).and_then(Value::as_str).map(str::to_string);
    if let Some(url) = field("url") {
        let sig = field("sigUrl").unwrap_or_else(|| format!("{url}.sig"));
        return Ok((url, sig));
    }
    let file = field("file").ok_or_else(|| "The update list has no file name.".to_string())?;
    let base = manifest_url
        .rfind('/')
        .map(|i| &manifest_url[..i + 1])
        .unwrap_or(manifest_url);
    Ok((format!("{base}{file}"), format!("{base}{file}.sig")))
}

/// `gh release list --json tagName` or manifest JSON → newest version.
pub fn newest_tag(json: &str) -> Option<String> {
    let v: Value = serde_json::from_str(json).ok()?;
    v.as_array()?
        .iter()
        .filter_map(|r| r.get("tagName")?.as_str())
        .filter_map(|t| {
            let v = t.trim_start_matches('v');
            parse_version(v).map(|p| (p, v.to_string()))
        })
        .max()
        .map(|(_, v)| v)
}

/// Downloads a newer version and checks its signature. The checked
/// file is kept in the data folder; the window opens it with the system's
/// software centre, which does the installing (Mímir never replaces itself).
pub fn download(
    source: &Source,
    layout: &Layout,
    public: &[u8; 32],
    current: &str,
    version: &str,
) -> Result<std::path::PathBuf, String> {
    if !is_newer(version, current) {
        return Err(format!("{version} is not newer than {current}."));
    }
    let dir = layout.data_dir().join("download");
    let _ = std::fs::remove_dir_all(&dir);
    let result = (|| {
        let (bin, sig) = source.download(version, &dir)?;
        let file = std::fs::read(&bin).map_err(|e| e.to_string())?;
        let sig = std::fs::read(&sig).map_err(|e| e.to_string())?;
        verify(public, version, &file, &sig)?;
        let keep = layout.data_dir().join("new-version");
        std::fs::create_dir_all(&keep).map_err(|e| e.to_string())?;
        let kept = keep.join(asset_name(version));
        write_atomic(&kept, &file, 0o644).map_err(|e| e.to_string())?;
        Ok(kept)
    })();
    let _ = std::fs::remove_dir_all(&dir);
    result
}

/// The name Mímir's Flatpak source gets on a PC.
pub const FLATPAK_REMOTE: &str = "mimir";

/// The file that adds Mímir's source (address and signing key).
pub const DEFAULT_REPO_FILE_URL: &str =
    "https://github.com/ODN-WKS/mimir/releases/latest/download/mimir.flatpakrepo";

/// Where this copy of Mímir was installed from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Origin {
    /// From a Flatpak source on the web (or a folder): the software centre and
    /// `flatpak update` keep it current.
    Source(String),
    /// From a single downloaded file: nothing updates it by itself.
    File,
    /// Not a Flatpak (a test run or a build in a terminal).
    NotFlatpak,
}

/// Works out the origin from `flatpak info --show-origin` (the name) and
/// `flatpak remotes --show-disabled --columns=name,url,options` (tab separated).
/// A file install leaves a disabled remote with no address behind.
pub fn classify_origin(origin: &str, remotes: &str) -> Origin {
    let origin = origin.trim();
    if origin.is_empty() || origin == "-" {
        return Origin::File;
    }
    for line in remotes.lines() {
        let mut cols = line.split('\t').map(str::trim);
        if cols.next() != Some(origin) {
            continue;
        }
        let url = cols.next().unwrap_or("");
        let options = cols.next().unwrap_or("");
        let off = options.split(',').any(|o| o.trim() == "disabled");
        return if url.is_empty() || off {
            Origin::File
        } else {
            Origin::Source(origin.to_string())
        };
    }
    Origin::File
}

fn flatpak_out(args: &[&str]) -> Result<String, String> {
    let out = crate::flatpak::host_cmd("flatpak")
        .args(args)
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .output()
        .map_err(|_| "Flatpak is not available.".to_string())?;
    if !out.status.success() {
        return Err("Flatpak did not answer.".into());
    }
    Ok(String::from_utf8_lossy(&out.stdout).to_string())
}

/// Asks Flatpak (on the PC, through the host helper) where this app came
/// from. Always asks; see [`origin`] for the version the window uses.
pub fn query_origin() -> Origin {
    let id = crate::flatpak::app_id();
    let Ok(name) = flatpak_out(&["info", "--user", "--show-origin", &id]) else {
        return Origin::File;
    };
    let remotes = flatpak_out(&[
        "remotes",
        "--user",
        "--show-disabled",
        "--columns=name,url,options",
    ])
    .unwrap_or_default();
    classify_origin(&name, &remotes)
}

/// [`query_origin`] inside a Flatpak; outside one, [`Origin::NotFlatpak`].
pub fn origin() -> Origin {
    if crate::flatpak::active() {
        query_origin()
    } else {
        Origin::NotFlatpak
    }
}

/// The first "NN%" in a line of Flatpak's progress output.
pub fn percent_in(line: &str) -> Option<u8> {
    let i = line.find('%')?;
    let digits: String = line[..i]
        .chars()
        .rev()
        .take_while(char::is_ascii_digit)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    digits.parse::<u8>().ok().filter(|p| *p <= 100)
}

/// Runs a Flatpak command on the PC as this person (never root), passing
/// progress percentages to `progress`.
fn flatpak_run(args: &[&str], progress: &mut dyn FnMut(u8)) -> Result<(), String> {
    use std::io::Read;
    let mut child = crate::flatpak::host_cmd("flatpak")
        .args(args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(if std::env::var_os("MIMIR_FLATPAK_VERBOSE").is_some() {
            std::process::Stdio::inherit()
        } else {
            std::process::Stdio::null()
        })
        .spawn()
        .map_err(|_| "Flatpak is not available.".to_string())?;
    if let Some(mut out) = child.stdout.take() {
        let (mut buf, mut line) = ([0u8; 512], String::new());
        while let Ok(n) = out.read(&mut buf) {
            if n == 0 {
                break;
            }
            for c in String::from_utf8_lossy(&buf[..n]).chars() {
                if c == '\r' || c == '\n' {
                    if let Some(p) = percent_in(&line) {
                        progress(p);
                    }
                    line.clear();
                } else {
                    line.push(c);
                }
            }
        }
    }
    let status = child
        .wait()
        .map_err(|_| "Flatpak did not finish.".to_string())?;
    status
        .success()
        .then_some(())
        .ok_or_else(|| "The update did not work. Check your internet and try again.".to_string())
}

/// "Update now": `flatpak update` for Mímir, as this person. Fails with a
/// plain line when nothing new came (the source may not have it yet).
pub fn flatpak_update(progress: &mut dyn FnMut(u8)) -> Result<(), String> {
    let id = crate::flatpak::app_id();
    let commit = || flatpak_out(&["info", "--user", "--show-commit", &id]).unwrap_or_default();
    let before = commit();
    flatpak_run(
        &["update", "--user", "-y", "--noninteractive", &id],
        progress,
    )?;
    if !before.is_empty() && commit() == before {
        return Err("The new version isn't there yet. Try again a little later.".into());
    }
    Ok(())
}

/// "Switch to automatic updates": adds Mímir's source and installs from it
/// again. Chats and settings live in the user's own folders and stay.
pub fn switch_to_source(repo_file_url: &str, progress: &mut dyn FnMut(u8)) -> Result<(), String> {
    let id = crate::flatpak::app_id();
    // The source's file carries its address and signing key.
    flatpak_run(
        &[
            "remote-add",
            "--user",
            "--if-not-exists",
            "--from",
            FLATPAK_REMOTE,
            repo_file_url,
        ],
        &mut |_| {},
    )?;
    let r#ref = format!("{id}//stable");
    flatpak_run(
        &[
            "install",
            "--user",
            "-y",
            "--noninteractive",
            "--reinstall",
            FLATPAK_REMOTE,
            &r#ref,
        ],
        progress,
    )?;
    // The leftover from the file install is no longer used (best effort).
    let _ = flatpak_run(
        &[
            "remote-delete",
            "--user",
            &format!("{FLATPAK_REMOTE}-origin"),
        ],
        &mut |_| {},
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> SigningKey {
        SigningKey::from_bytes(&[7u8; 32])
    }

    #[test]
    fn versions() {
        assert_eq!(parse_version("0.1"), Some((0, 1, 0)));
        assert_eq!(parse_version("v0.12"), Some((0, 12, 0)));
        // Three-part semver is now accepted (B9: patch versions).
        assert_eq!(parse_version("1.0.0"), Some((1, 0, 0)));
        assert_eq!(parse_version("0.18.1"), Some((0, 18, 1)));
        assert!(is_newer("0.10", "0.9"));
        assert!(!is_newer("0.1", "0.1"));
        assert!(is_newer("0.18.1", "0.18.0"), "patch newer");
        assert!(!is_newer("0.18.0", "0.18.1"), "older patch");
        assert_eq!(
            newest_tag(r#"[{"tagName":"v0.2"},{"tagName":"v0.10"},{"tagName":"junk"}]"#).as_deref(),
            Some("0.10")
        );
        assert_eq!(newest_tag("[]"), None);
    }

    #[test]
    fn patch_version_signable() {
        // B9 PCR-02: "0.18.1" must be accepted by the sign/verify logic.
        let k = SigningKey::from_bytes(&[42u8; 32]);
        let public = k.verifying_key().to_bytes();
        let sig = sign(&k, "0.18.1", b"patch binary");
        assert!(
            verify(&public, "0.18.1", b"patch binary", &sig).is_ok(),
            "patch version signable"
        );
        assert!(
            verify(&public, "0.18.0", b"patch binary", &sig).is_err(),
            "different patch rejected"
        );
        assert!(
            parse_version("0.18.1").is_some(),
            "parse_version accepts patch"
        );
        assert!(is_newer("0.18.1", "0.18.0"), "0.18.1 is newer than 0.18.0");
    }

    #[test]
    fn update_manifest_timeout() {
        // B9 PCW-16: hanging fetch returns error (here with a 2 s timeout for speed).
        use std::io::Read;
        use std::net::TcpListener;
        // Bind to a random port that accepts connections but never sends data.
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let _t = std::thread::spawn(move || {
            // Accept and hold open (never send anything).
            if let Ok((mut s, _)) = listener.accept() {
                let mut buf = [0u8; 1];
                let _ = s.read(&mut buf);
            }
        });
        let url = format!("http://{addr}/manifest.json");
        let start = std::time::Instant::now();
        let result = fetch_manifest(&url, 2);
        let elapsed = start.elapsed();
        assert!(result.is_err(), "hanging server must return an error");
        assert!(
            elapsed.as_secs() < 10,
            "must return within 10 s (got {elapsed:?})"
        );
        let msg = result.unwrap_err();
        assert!(
            msg.contains("failed") || msg.contains("Failed") || msg.contains("curl"),
            "clear error message: {msg}"
        );
    }

    #[test]
    fn signatures_bind_file_and_version() {
        let k = key();
        let public = k.verifying_key().to_bytes();
        let sig = sign(&k, "0.2", b"program");
        assert!(verify(&public, "0.2", b"program", &sig).is_ok());
        assert!(
            verify(&public, "0.2", b"programX", &sig).is_err(),
            "changed file"
        );
        assert!(
            verify(&public, "0.3", b"program", &sig).is_err(),
            "relabelled version"
        );
        let other = SigningKey::from_bytes(&[9u8; 32]);
        let forged = sign(&other, "0.2", b"program");
        assert!(
            verify(&public, "0.2", b"program", &forged).is_err(),
            "someone else's key"
        );
        assert!(verify(&public, "0.2", b"program", &sig[..10]).is_err());
        assert_eq!(public_from_hex(&to_hex(&public)), Some(public));
    }

    #[test]
    fn update_from_a_folder() {
        let home = std::env::temp_dir().join(format!("station-upd-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        let layout = Layout { home: home.clone() };
        let releases = home.join("releases");
        std::fs::create_dir_all(&releases).unwrap();
        let k = key();
        let public = k.verifying_key().to_bytes();

        // A good 0.2 and a tampered 0.3.
        std::fs::write(releases.join(asset_name("0.2")), "new program").unwrap();
        std::fs::write(
            releases.join(format!("{}.sig", asset_name("0.2"))),
            sign(&k, "0.2", b"new program"),
        )
        .unwrap();
        let src = Source::Folder(releases.clone());
        assert_eq!(src.latest(&public).unwrap().as_deref(), Some("0.2"),);
        let got = download(&src, &layout, &public, "0.1", "0.2").unwrap();
        assert_eq!(std::fs::read_to_string(&got).unwrap(), "new program");
        assert!(got.ends_with("Mimir-0.2.flatpak"));
        assert!(!layout.data_dir().join("download").exists());

        std::fs::write(releases.join(asset_name("0.3")), "evil").unwrap();
        std::fs::write(
            releases.join(format!("{}.sig", asset_name("0.3"))),
            sign(&k, "0.3", b"good"),
        )
        .unwrap();
        assert!(download(&src, &layout, &public, "0.2", "0.3").is_err());
        assert!(
            download(&src, &layout, &public, "0.2", "0.2").is_err(),
            "not newer"
        );
        std::fs::remove_dir_all(&home).unwrap();
    }

    /// A web-style release folder read through `file://`: manifest, its
    /// signature, the program and its signature.
    fn web_release(dir: &Path, k: &SigningKey, sign_manifest_as: &str) -> String {
        std::fs::create_dir_all(dir).unwrap();
        let bin = b"web program";
        let name = "Mimir-0.5.flatpak";
        std::fs::write(dir.join(name), bin).unwrap();
        std::fs::write(dir.join(format!("{name}.sig")), sign(k, "0.5", bin)).unwrap();
        let manifest = format!(
            r#"[{{"tagName":"v0.5","file":"{name}","url":"file://{d}/{name}","sigUrl":"file://{d}/{name}.sig"}}]"#,
            d = dir.display()
        );
        std::fs::write(dir.join("manifest.json"), &manifest).unwrap();
        std::fs::write(
            dir.join("manifest.json.sig"),
            sign(k, sign_manifest_as, manifest.as_bytes()),
        )
        .unwrap();
        format!("file://{}/manifest.json", dir.display())
    }

    #[test]
    fn default_address_is_the_public_latest_release() {
        assert_eq!(
            DEFAULT_UPDATE_URL,
            "https://github.com/ODN-WKS/mimir/releases/latest/download/manifest.json"
        );
        assert_eq!(REPO, "ODN-WKS/mimir");
    }

    #[test]
    fn signed_manifest_is_trusted_and_downloads() {
        let home = std::env::temp_dir().join(format!("station-web-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        let k = key();
        let public = k.verifying_key().to_bytes();
        let url = web_release(&home.join("web"), &k, "0.5");
        let src = Source::Manifest(url);
        assert_eq!(src.latest(&public).unwrap().as_deref(), Some("0.5"));
        let layout = Layout {
            home: home.join("h"),
        };
        let got = download(&src, &layout, &public, "0.1", "0.5").unwrap();
        assert_eq!(std::fs::read_to_string(got).unwrap(), "web program");
        std::fs::remove_dir_all(&home).unwrap();
    }

    #[test]
    fn unsigned_or_wrongly_signed_manifest_is_refused() {
        let home = std::env::temp_dir().join(format!("station-web2-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        let k = key();
        let public = k.verifying_key().to_bytes();
        // Signed for another version (relabelled).
        let url = web_release(&home.join("a"), &k, "0.9");
        assert!(Source::Manifest(url).latest(&public).is_err(), "relabelled");
        // Signed by someone else.
        let other = SigningKey::from_bytes(&[9u8; 32]);
        let url = web_release(&home.join("b"), &other, "0.5");
        assert!(Source::Manifest(url).latest(&public).is_err(), "wrong key");
        // No signature file at all.
        let url = web_release(&home.join("c"), &k, "0.5");
        std::fs::remove_file(home.join("c/manifest.json.sig")).unwrap();
        let e = Source::Manifest(url).latest(&public).unwrap_err();
        assert!(e.contains("not signed"), "{e}");
        // Edited after signing.
        let url = web_release(&home.join("d"), &k, "0.5");
        let m = std::fs::read_to_string(home.join("d/manifest.json")).unwrap();
        std::fs::write(home.join("d/manifest.json"), m.replace("v0.5", "v0.6")).unwrap();
        assert!(Source::Manifest(url).latest(&public).is_err(), "edited");
        std::fs::remove_dir_all(&home).unwrap();
    }

    #[test]
    fn where_the_app_came_from() {
        let remotes = "flathub\thttps://dl.flathub.org/repo/\t\nmimir-origin\t\tdisabled,no-enumerate,no-gpg-verify\nmimir\thttps://odn-wks.github.io/mimir/flatpak/\t\n";
        assert_eq!(classify_origin("mimir-origin\n", remotes), Origin::File);
        assert_eq!(
            classify_origin("mimir", remotes),
            Origin::Source("mimir".into())
        );
        assert_eq!(classify_origin("-", remotes), Origin::File);
        assert_eq!(classify_origin("", remotes), Origin::File);
        assert_eq!(classify_origin("gone", remotes), Origin::File);
        // A source that is switched off does not update anything.
        assert_eq!(
            classify_origin("x", "x\thttps://a.invalid/\tdisabled\n"),
            Origin::File
        );
    }

    #[test]
    fn progress_is_read_from_flatpaks_lines() {
        assert_eq!(percent_in("Installing… ████ 42%  3.1 MB/s"), Some(42));
        assert_eq!(percent_in("Updating 7% x"), Some(7));
        assert_eq!(percent_in("100%"), Some(100));
        assert_eq!(percent_in("no number % here"), None);
        assert_eq!(percent_in("nothing"), None);
        assert_eq!(percent_in("500%"), None);
    }

    /// These tests change Flatpak apps: refuse to run anywhere but in the
    /// throwaway installation tools/release-update-test.sh sets up.
    fn throwaway_only() {
        let dir = std::env::var("FLATPAK_USER_DIR").unwrap_or_default();
        assert!(
            dir.contains("/update-test."),
            "not a throwaway installation"
        );
        // Flatpak itself must be using that folder (a wrapper may drop the variable).
        let id = crate::flatpak::app_id();
        let at = flatpak_out(&["info", "--user", "--show-location", &id]).unwrap_or_default();
        assert!(at.trim().starts_with(&dir), "this is the real installation");
    }

    // Real Flatpak runs for tools/release-test.sh's throwaway installation
    // (FLATPAK_USER_DIR points at it); skipped in a normal test run.
    #[test]
    #[ignore]
    fn real_switch_from_a_file_to_a_source() {
        throwaway_only();
        let url = std::env::var("MIMIR_REPO_FILE_URL").expect("MIMIR_REPO_FILE_URL");
        assert_eq!(query_origin(), Origin::File);
        let mut seen = vec![];
        switch_to_source(&url, &mut |p| seen.push(p)).unwrap();
        assert_eq!(query_origin(), Origin::Source(FLATPAK_REMOTE.into()));
    }

    #[test]
    #[ignore]
    fn real_update_from_a_source() {
        throwaway_only();
        assert_eq!(query_origin(), Origin::Source(FLATPAK_REMOTE.into()));
        let mut seen = vec![];
        flatpak_update(&mut |p| seen.push(p)).unwrap();
        eprintln!("progress seen: {seen:?}");
    }
}
