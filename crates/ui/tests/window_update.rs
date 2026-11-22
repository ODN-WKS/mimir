//! Window checks for updates (CF-11): a signed newer version opens in the
//! software centre, and homes written by older versions still open.

mod common;
use common::*;
use ed25519_dalek::SigningKey;
use mimir_core::update;
use station_ui::labels;
use std::path::{Path, PathBuf};

/// A made-up release the checks sign with a throwaway key.
const NEW_VERSION: &str = "0.16";
const NEW_PROGRAM: &[u8] = b"#!/bin/sh\necho 'Mimir 0.16 (made up by the update check)'\n";

#[test]
fn cf11_update_opens_in_the_software_centre() {
    let Some(home) = TestHome::new() else { return };
    // The release folder Mímir reads instead of GitHub, with a
    // file signed by a key only this check knows.
    let key = SigningKey::from_bytes(&[42u8; 32]);
    let name = update::asset_name(NEW_VERSION);
    let releases = home.root.join("updates");
    std::fs::write(releases.join(&name), NEW_PROGRAM).unwrap();
    std::fs::write(
        releases.join(format!("{name}.sig")),
        update::sign(&key, NEW_VERSION, NEW_PROGRAM),
    )
    .unwrap();
    let opened = home.root.join("opened.txt");
    // SAFETY: as in common::set; nothing else runs yet.
    unsafe {
        std::env::set_var(
            "MIMIR_TEST_KEY",
            update::to_hex(&key.verifying_key().to_bytes()),
        );
        std::env::set_var("MIMIR_TEST_OPEN", &opened);
    };
    let mut h = home.open();
    let offer = labels::update_to(NEW_VERSION);
    assert!(
        wait_for(&mut h, |h| has_button(h, &offer)),
        "the window didn't offer {NEW_VERSION}\n{}",
        screen_text(&h)
    );
    click(&mut h, &offer);
    // The checked file opens in the software centre; the program itself is
    // never replaced.
    let done = wait_for(&mut h, |h| shows(h, &labels::update_opened(NEW_VERSION)));
    let said = std::fs::read_to_string(&opened).unwrap_or_default();
    assert!(
        done && said.trim_end().ends_with(&name) && !home.root.join(".local/bin/mimir").exists(),
        "the update didn't open in the software centre\n{}",
        screen_text(&h)
    );
    assert_eq!(
        std::fs::read(said.trim_end()).unwrap_or_default(),
        NEW_PROGRAM
    );
    close(h);
}

/// A fake `flatpak` in a folder
/// at the front of PATH. Its source is Mímir's own; `update` brings the new
/// version only once the file `ready` exists.
fn fake_flatpak(home: &TestHome) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    let bin = home.root.join("fake-bin");
    std::fs::create_dir_all(&bin).unwrap();
    let state = home.root.join("fake-flatpak");
    std::fs::create_dir_all(&state).unwrap();
    std::fs::write(state.join("commit"), "old\n").unwrap();
    let flatpak = format!(
        "#!/bin/sh\n\
         S='{}'\n\
         case \"$1 $3\" in\n\
         \"info --show-origin\") echo mimir ;;\n\
         \"info --show-commit\") cat \"$S/commit\" ;;\n\
         \"remotes --show-disabled\") printf 'mimir\\thttps://example.invalid/repo\\t\\n' ;;\n\
         \"update -y\") echo '40%'; if [ -e \"$S/ready\" ]; then echo new > \"$S/commit\"; fi; echo '100%' ;;\n\
         esac\n",
        state.display()
    );
    std::fs::write(bin.join("flatpak"), flatpak).unwrap();
    std::fs::set_permissions(bin.join("flatpak"), std::fs::Permissions::from_mode(0o755)).unwrap();
    let path = std::env::var("PATH").unwrap_or_default();
    set_env("PATH", format!("{}:{path}", bin.display()));
    set_env("MIMIR_ASK_FLATPAK", "1");
    state
}

#[test]
fn flatpak_update_says_why_it_failed_then_restarts_after_trying_again() {
    let Some(home) = TestHome::new() else { return };
    let name = update::asset_name(NEW_VERSION);
    std::fs::write(home.root.join("updates").join(&name), NEW_PROGRAM).unwrap();
    let state = fake_flatpak(&home);
    let mut h = home.open();
    click(&mut h, "Settings");
    click(&mut h, "About");
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::UPDATE_NOW)),
        "Settings didn't offer Update now\n{}",
        screen_text(&h)
    );
    // The source hasn't got the new version yet: the real reason shows.
    click(&mut h, labels::UPDATE_NOW);
    let why = "The new version isn't there yet. Try again a little later.";
    assert!(
        wait_for(&mut h, |h| shows(h, why)
            && has_button(h, labels::TRY_AGAIN)),
        "the failed update gave no reason\n{}",
        screen_text(&h)
    );
    assert!(!shows(&h, "Couldn't check just now."));
    // Once it is there, Try again runs it again and asks for a restart.
    std::fs::write(state.join("ready"), "").unwrap();
    click(&mut h, labels::TRY_AGAIN);
    assert!(
        wait_for(&mut h, |h| has_button(h, labels::RESTART)),
        "the update didn't ask for a restart\n{}",
        screen_text(&h)
    );
    close(h);
}

/// The titles and home projects of the tasks saved in a home.
fn saved_tasks(home: &TestHome) -> Vec<(String, String)> {
    let dir = home.root.join(".local/share/mimir/tasks");
    let mut v = vec![];
    for e in std::fs::read_dir(dir).unwrap().flatten() {
        let t: serde_json::Value =
            serde_json::from_slice(&std::fs::read(e.path()).unwrap()).unwrap();
        let project = t["project"].as_str().unwrap_or_default().to_string();
        v.push((t["title"].as_str().unwrap_or_default().to_string(), project));
    }
    v
}

#[test]
fn cf11_older_homes_open() {
    let homes = homes_dir();
    let mut versions: Vec<String> = std::fs::read_dir(&homes)
        .map(|rd| {
            rd.flatten()
                .map(|e| e.file_name().to_string_lossy().to_string())
                .collect()
        })
        .unwrap_or_default();
    versions.sort();
    assert!(
        !versions.is_empty(),
        "no older homes in {}",
        homes.display()
    );
    for v in versions {
        let Some(home) = TestHome::with_home(&v) else {
            return;
        };
        // The program reads the home without a window and counts it.
        let out = std::process::Command::new(station_program())
            .arg("--check-home")
            .output()
            .unwrap();
        let counted = String::from_utf8_lossy(&out.stdout).trim().to_string();
        // The window opens it: every task is listed under its project, and
        // the settings (a project's own name) are in use.
        let mut h = home.open();
        let tasks = saved_tasks(&home);
        let listed = wait_for(&mut h, |h| {
            tasks.iter().all(|(title, project)| {
                has_label(
                    h,
                    &labels::task_row(title, &station_ui::places::project_name(project)),
                )
            })
        });
        let named = shows(&h, "Letters home");
        assert!(
            out.status.success() && listed && named,
            "the {v} home's tasks or settings didn't open\n--check-home: {counted} {}\n{}",
            String::from_utf8_lossy(&out.stderr),
            screen_text(&h)
        );
        close(h);
    }
}

/// Writes a home in today's formats to `checks/homes/<version>/`, with
/// made-up data. Run it by hand when a change touches the task or settings
/// format: `cargo nextest run --run-ignored only make_check_home`.
#[test]
#[ignore = "run by hand when the task or settings format changes"]
fn make_check_home() {
    use mimir_core::task::{Entry, Mode, Status, Task};
    use station_ui::places::{Defaults, Prompt, Settings};
    let version = station_ui::VERSION.trim();
    let out = check_home(version);
    assert!(
        !out.exists(),
        "{} is already there; delete it first to write it again",
        out.display()
    );
    let tmp = std::env::temp_dir().join(format!("make-check-home-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&tmp);
    let root = tmp.join("root");
    let (garden, letters) = (root.join("garden"), root.join("letters"));
    for d in [&garden, &letters] {
        std::fs::create_dir_all(d).unwrap();
    }
    std::fs::write(
        garden.join("beds.txt"),
        "north bed: shade\nsouth bed: full sun\n",
    )
    .unwrap();
    std::fs::write(letters.join("draft.txt"), "Dear neighbours,\n").unwrap();
    let (g, l) = (garden.display().to_string(), letters.display().to_string());
    let mut t1 = Task::new(
        "1794000000-1000-1".into(),
        "Plan the spring beds".into(),
        g.clone(),
        1_794_000_000,
    );
    t1.entries = vec![
        Entry::You("Read beds.txt and suggest what to plant where this spring.".into()),
        Entry::Claude("The south bed gets full sun, so tomatoes and beans go there.".into()),
        Entry::You("Thanks. Keep the herbs near the door.".into()),
        Entry::Claude("Noted: the herbs go nearest the door.".into()),
    ];
    t1.status = Status::Done;
    let mut t2 = Task::new(
        "1794000000-1000-2".into(),
        "Water timer notes".into(),
        g.clone(),
        1_794_000_100,
    );
    t2.entries = vec![
        Entry::You("Write down when the water timer runs.".into()),
        Entry::Note("You stopped this task.".into()),
    ];
    t2.status = Status::Stopped;
    t2.mode = Mode::AcceptEdits;
    let mut t3 = Task::new(
        "1794000000-1000-3".into(),
        "Thank-you letter".into(),
        l.clone(),
        1_794_000_200,
    );
    t3.entries = vec![
        Entry::You("Draft a short thank-you letter to the neighbours.".into()),
        Entry::Claude("Dear neighbours, thank you for the plums.".into()),
    ];
    t3.status = Status::Done;
    let tasks = root.join(".local/share/mimir/tasks");
    std::fs::create_dir_all(&tasks).unwrap();
    for t in [&t1, &t2, &t3] {
        std::fs::write(
            tasks.join(format!("{}.json", t.id)),
            serde_json::to_vec_pretty(t).unwrap(),
        )
        .unwrap();
    }
    let mut s = Settings {
        projects: vec![g.clone(), l.clone()],
        last_project: Some(l.clone()),
        last_task: Some(t2.id.clone()),
        read_speed: Some(1.25),
        zoom: Some(1.1),
        pinned_tasks: vec![t2.id.clone()],
        prompts_seeded: true,
        prompts: vec![Prompt {
            name: "Check spelling".into(),
            text: "Check the spelling in this folder and list what you'd change.".into(),
        }],
        ..Default::default()
    };
    s.project_names.insert(l.clone(), "Letters home".into());
    s.defaults.insert(
        g.clone(),
        Defaults {
            mode: Mode::AcceptEdits,
            model: Some("sonnet".into()),
            effort: Some("high".into()),
        },
    );
    let config = root.join(".config/mimir");
    std::fs::create_dir_all(&config).unwrap();
    std::fs::write(
        config.join("settings.json"),
        serde_json::to_vec_pretty(&s).unwrap(),
    )
    .unwrap();
    std::fs::write(
        root.join("expect.json"),
        serde_json::to_vec_pretty(&serde_json::json!({"projects": 2, "tasks": 3, "messages": 6}))
            .unwrap(),
    )
    .unwrap();
    copy_with_placeholder(&root, &out, &root.display().to_string());
    let _ = std::fs::remove_dir_all(&tmp);
    eprintln!("Wrote {}", out.display());
}

/// Copies a home, putting `@ROOT@` back where its own folder appears.
fn copy_with_placeholder(src: &Path, dst: &Path, root: &str) {
    std::fs::create_dir_all(dst).unwrap();
    let as_name: String = root
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    for e in std::fs::read_dir(src).unwrap().flatten() {
        let name = e.file_name().to_string_lossy().replace(&as_name, "@ROOT@");
        let to: PathBuf = dst.join(name);
        if e.file_type().unwrap().is_dir() {
            copy_with_placeholder(&e.path(), &to, root);
        } else {
            let text = std::fs::read_to_string(e.path()).unwrap();
            std::fs::write(&to, text.replace(root, "@ROOT@")).unwrap();
        }
    }
}
