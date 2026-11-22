//! Mímir's window as a library: the program (`main.rs`) opens it,
//! and the window checks (`tests/window_*.rs`) drive it.

mod app;
mod app_view;
pub mod chat_pics;
pub mod cli;
mod extras;
mod fonts;
pub mod labels;
mod menus;
mod models;
mod organize;
mod pcstats;
pub mod places;
mod setup_view;
mod shot;
mod theme;
mod view_chat;
mod view_labs;
mod view_left;
mod view_models;
mod view_new;
mod view_phone;
mod view_right;
mod view_settings;
mod view_task;
mod view_top;
mod view_windows;

pub use app::App;

use eframe::egui;

pub const VERSION: &str = include_str!("../../../VERSION");
const PUBLIC_KEY: &str = include_str!("release-key.pub");

/// The key updates are checked with (see `app_view::key_in_use`).
pub fn public_key() -> String {
    app_view::key_in_use(
        PUBLIC_KEY,
        std::env::var("MIMIR_TEST_KEY").ok(),
        cfg!(debug_assertions),
    )
}

/// The program: a command (`hook`, `--version`, ...) or the window.
pub fn run() -> eframe::Result {
    // In a Flatpak the data and settings stay in their usual folders.
    mimir_core::flatpak::use_usual_folders();
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("hook") => std::process::exit(mimir_core::approvals::run_hook(&args[1..])),
        // Mímir's work, as its own process (systemd starts it).
        Some("service") => std::process::exit(mimir_core::service::run_main(VERSION.trim())),
        Some("ask") => std::process::exit(mimir_core::needs::ask_command(&args[1..])),
        // The owner's own Claude sessions' permission hook (see `approve`).
        Some("approve") => std::process::exit(mimir_core::approve::command(&args[1..])),
        Some("incognito-launch") => {
            std::process::exit(mimir_core::incognito::launch_main(&args[1..]))
        }
        Some("weekly") => std::process::exit(mimir_core::keep_current::weekly_main()),
        Some("host") => std::process::exit(mimir_core::host::main(&args[1..])),
        Some("devlog") => std::process::exit(mimir_core::devlog::command(&args[1..])),
        Some("chats") => std::process::exit(mimir_core::talk_store::command(&args[1..])),
        Some("engine") => std::process::exit(mimir_core::engine::command(&args[1..])),
        Some("serve") | Some("status") | Some("pair") | Some("chat") | Some("say")
        | Some("import-old") => {
            std::process::exit(cli::command(&args, VERSION.trim()).unwrap_or(2))
        }
        Some("--help") | Some("-h") => {
            println!(
                "Mímir {}\n\nWith no command it opens the window. Commands:\n  \
                 --version, --public-key, --check-home\n  \
                 ask …        ask the owner a question (from a session)\n  \
                 approve      Claude Code's permission hook: asks on the phone and in the window\n  \
                 weekly       tidy unused models and leftovers, look for better free models\n  \
                 engine [probe]   where chats run; probe measures the PC's models now\n  \
                 chats [--full|--json]   the chats kept on this PC\n  \
                 serve        the service, in this terminal\n  \
                 status [--json]   the PC's status (from the running service)\n  \
                 pair         pair a phone from the terminal\n  \
                 chat \"…\"     send one chat message, print the answer\n  \
                 say \"…\"      speak text (not wired yet)\n  \
                 service      the background part (the window starts it)",
                VERSION.trim()
            );
            return Ok(());
        }
        Some("--version") => {
            println!("Mímir {}", VERSION.trim());
            return Ok(());
        }
        Some("--public-key") => {
            println!("{}", public_key());
            return Ok(());
        }
        Some("--check-home") => std::process::exit(app_view::check_home()),
        _ => {}
    }
    // Only a bare `mimir` opens the window. Anything else typed in a
    // terminal is a command, and a command never needs a display (it may run
    // from a Claude session or the service, with no screen at all).
    if let Some(word) = args.first() {
        eprintln!(
            "Mímir has no command \"{word}\". Run mimir --help to see the commands, \
             or run mimir on its own to open the window."
        );
        std::process::exit(2);
    }
    // In a Flatpak only a window the service started is let in (it holds the
    // capability the control socket asks for). A bare `mimir` is the
    // launcher: it has the service open the window, or becomes the service.
    if mimir_core::sockets::cap_required() && !mimir_core::sockets::take_held() {
        std::process::exit(mimir_core::client::open_window_through_service(
            VERSION.trim(),
        ));
    }
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("Mímir")
            .with_app_id(if mimir_core::flatpak::active() {
                mimir_core::flatpak::APP_ID
            } else {
                "mimir"
            })
            .with_icon(window_icon())
            .with_inner_size([1280.0, 800.0])
            .with_min_inner_size([900.0, 560.0]),
        ..Default::default()
    };
    // The window's own developer log (slow frames), beside the service's.
    let s = mimir_core::settings::Settings::read().unwrap_or_default();
    mimir_core::devlog::init(
        &format!("window-{}", std::process::id()),
        mimir_core::devlog::Config::window(mimir_core::devlog::dir()),
        s.devlog_on(),
        s.devlog_chats(),
    );
    mimir_core::devlog::install_panic_hook();
    // Ask the desktop to start Mímir's background part with the session.
    std::thread::spawn(mimir_core::flatpak::ask_to_run_in_background);
    let out = eframe::run_native(
        "Mímir",
        options,
        Box::new(|cc| Ok(Box::new(App::new(cc, VERSION.trim(), public_key())))),
    );
    mimir_core::devlog::shutdown();
    out
}

/// The window's icon: Mímir's round stone well (the menu entry's 256 px one).
pub fn window_icon() -> std::sync::Arc<egui::IconData> {
    let png = mimir_core::install::ICONS
        .iter()
        .find(|(n, _)| *n == 256)
        .map(|(_, png)| *png)
        .unwrap_or_default();
    let icon = image::load_from_memory_with_format(png, image::ImageFormat::Png)
        .map(|i| {
            let i = i.into_rgba8();
            let (width, height) = i.dimensions();
            egui::IconData {
                rgba: i.into_raw(),
                width,
                height,
            }
        })
        .unwrap_or_default();
    std::sync::Arc::new(icon)
}

#[cfg(test)]
mod tests {
    /// Scan all user-visible string literals in `crates/ui/src/` for banned
    /// terms. The test fails if any banned word appears outside a comment or
    /// an `//!` doc-comment, in a plain string literal, unless it is inside
    /// a fold or section clearly marked "Advanced".
    #[test]
    fn banned_words() {
        const BANNED: &[&str] = &[
            concat!("Claude", " Station"),
            "Ollama",
            "abliterated",
            "sherpa-onnx",
            "Ground rules",
            "worktree",
            "YOLO",
            "Phone too",
            "relays",
            "end to end",
            "Effort: default",
            "Delegate=",
            "[Request interrupted",
            concat!("steam", "deck"),
        ];
        let src_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
        let mut failures: Vec<String> = vec![];
        for entry in std::fs::read_dir(&src_dir).expect("read src dir") {
            let entry = entry.expect("dir entry");
            if entry.path().extension().and_then(|e| e.to_str()) != Some("rs") {
                continue;
            }
            let path = entry.path();
            // Skip this file: it contains the BANNED list itself.
            if path.file_name().and_then(|n| n.to_str()) == Some("lib.rs") {
                continue;
            }
            let source = std::fs::read_to_string(&path).expect("read source file");
            let mut in_test_block: i32 = 0;
            let mut test_attr = false;
            for line in source.lines() {
                let trimmed = line.trim();
                // Track `#[cfg(test)]` blocks.
                if trimmed == "#[cfg(test)]" {
                    test_attr = true;
                    continue;
                }
                if test_attr {
                    test_attr = false;
                    if trimmed.starts_with("mod ") {
                        in_test_block = 1;
                        continue;
                    }
                }
                if in_test_block > 0 {
                    for c in trimmed.chars() {
                        if c == '{' {
                            in_test_block += 1;
                        }
                        if c == '}' {
                            in_test_block -= 1;
                        }
                    }
                    continue; // skip all lines inside test blocks
                }
                // Skip full-line comments and doc-comments.
                if trimmed.starts_with("//") {
                    continue;
                }
                // Skip concat! usage (machine-output patterns, not UI labels).
                if trimmed.contains("concat!(") {
                    continue;
                }
                // Extract only string literals from the line to avoid
                // catching identifiers (e.g. `phone_relays`).
                let mut string_parts = String::new();
                let mut in_str = false;
                let mut prev = '\0';
                for c in line.chars() {
                    if c == '"' && prev != '\\' {
                        in_str = !in_str;
                    } else if in_str {
                        string_parts.push(c);
                    }
                    prev = c;
                }
                for &word in BANNED {
                    if string_parts.contains(word) {
                        failures.push(format!(
                            "{}:{}: found banned word {:?}",
                            path.file_name().unwrap().to_string_lossy(),
                            trimmed,
                            word,
                        ));
                    }
                }
            }
        }
        assert!(
            failures.is_empty(),
            "banned words found in crates/ui/src/:\n{}",
            failures.join("\n")
        );
    }
}
