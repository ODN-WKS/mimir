//! A file name that looks private never leaves the PC in the live words.

use mimir_proto::{doing_words, sensitive_name};

#[test]
fn private_looking_names_give_only_the_verb() {
    for n in [
        ".env",
        "prod.env",
        ".bashrc",
        "id_ed25519",
        "server.pem",
        "api_key.txt",
        "my-secret.json",
        "token.txt",
        "src/.npmrc",
        "/home/p/.ssh/config",
        "Passwords.csv",
    ] {
        assert!(sensitive_name(n), "{n}");
        assert_eq!(
            doing_words("Read", &format!("Read {n}")),
            "Reading a file",
            "{n}"
        );
        assert_eq!(
            doing_words("Edit", &format!("Changed {n}")),
            "Changing a file"
        );
        assert_eq!(
            doing_words("Write", &format!("Wrote {n}")),
            "Writing a file"
        );
    }
}

#[test]
fn plain_names_still_show() {
    for n in ["main.rs", "notes.md", "Garden planner.txt", "src/lib.rs"] {
        assert!(!sensitive_name(n), "{n}");
    }
    assert_eq!(doing_words("Read", "Read main.rs"), "Reading main.rs");
}
