//! Release signing, run only on the owner's PC. Never shipped.
//!
//!   mimir-release keygen              make the signing key (once)
//!   mimir-release public              print the public key (hex)
//!   mimir-release sign <file> <ver>   write <file>.sig
//!   mimir-release verify <file> <ver> check <file>.sig with the app's key
//!
//! The private key lives in ~/.config/mimir-release/release.key,
//! outside everything Mímir installs or uninstalls.

use ed25519_dalek::SigningKey;
use mimir_core::update;
use std::io::Read;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::process::exit;

const APP_PUBLIC: &str = include_str!("../../ui/src/release-key.pub");

fn key_path() -> PathBuf {
    // tools/release.sh points this at a short-lived copy of the key.
    if let Some(p) = std::env::var_os("MIMIR_RELEASE_KEY") {
        return PathBuf::from(p);
    }
    PathBuf::from(std::env::var_os("HOME").unwrap_or_default())
        .join(".config/mimir-release/release.key")
}

fn load() -> SigningKey {
    let p = key_path();
    let meta = std::fs::metadata(&p).unwrap_or_else(|_| {
        eprintln!(
            "No signing key at {}. Run: mimir-release keygen",
            p.display()
        );
        exit(1)
    });
    if meta.permissions().mode() & 0o077 != 0 {
        eprintln!("{} must be readable only by you (chmod 600).", p.display());
        exit(1);
    }
    let bytes: [u8; 32] = std::fs::read(&p)
        .ok()
        .and_then(|b| b.try_into().ok())
        .unwrap_or_else(|| {
            eprintln!("The signing key file is damaged.");
            exit(1)
        });
    SigningKey::from_bytes(&bytes)
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args
        .iter()
        .map(String::as_str)
        .collect::<Vec<_>>()
        .as_slice()
    {
        ["keygen"] => {
            let p = key_path();
            if p.exists() {
                eprintln!("A key already exists at {}. Not replacing it.", p.display());
                exit(1);
            }
            let mut seed = [0u8; 32];
            std::fs::File::open("/dev/urandom")
                .and_then(|mut f| f.read_exact(&mut seed))
                .expect("random bytes");
            let dir = p.parent().unwrap();
            std::fs::create_dir_all(dir).unwrap();
            std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700)).unwrap();
            std::fs::write(&p, seed).unwrap();
            std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o600)).unwrap();
            println!(
                "{}",
                update::to_hex(&SigningKey::from_bytes(&seed).verifying_key().to_bytes())
            );
        }
        ["public"] => println!("{}", update::to_hex(&load().verifying_key().to_bytes())),
        ["sign", file, version] => {
            if update::parse_version(version).is_none() {
                eprintln!("Versions are two-part, like 0.1.");
                exit(1);
            }
            let key = load();
            if update::to_hex(&key.verifying_key().to_bytes()) != APP_PUBLIC.trim() {
                eprintln!("This key does not match the public key built into the app.");
                exit(1);
            }
            let data = std::fs::read(file).expect("read file");
            std::fs::write(format!("{file}.sig"), update::sign(&key, version, &data)).unwrap();
            println!("signed {file} as {version}");
        }
        ["verify", file, version] => {
            let public = update::public_from_hex(APP_PUBLIC).expect("app key");
            let data = std::fs::read(file).expect("read file");
            let sig = std::fs::read(format!("{file}.sig")).expect("read signature");
            match update::verify(&public, version, &data, &sig) {
                Ok(()) => println!("good signature for {version}"),
                Err(e) => {
                    eprintln!("{e}");
                    exit(1)
                }
            }
        }
        _ => {
            eprintln!(
                "usage: mimir-release keygen | public | sign <file> <version> | verify <file> <version>"
            );
            exit(2);
        }
    }
}
