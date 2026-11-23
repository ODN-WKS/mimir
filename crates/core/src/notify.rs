//! Desktop notifications.

/// A GVariant text literal for `gdbus call`.
fn gvariant_text(s: &str) -> String {
    format!("'{}'", s.replace('\\', "\\\\").replace('\'', "\\'"))
}

/// Whether pop-ups are switched off for this process: a test PC
/// (`tools/test-pc.sh` sets `MIMIR_NO_POPUPS=1`) must never post on the
/// real desktop.
pub fn silenced() -> bool {
    silenced_by(std::env::var("MIMIR_NO_POPUPS").ok().as_deref())
}

fn silenced_by(v: Option<&str>) -> bool {
    v.is_some_and(|v| !v.is_empty() && v != "0")
}

/// A desktop notification (KDE shows these in the corner).
pub fn notify(title: &str, body: &str) {
    if silenced() {
        return;
    }
    // A Flatpak talks to the notification service directly; its icon is
    // the app's own.
    let mut cmd = if crate::flatpak::active() {
        let mut c = std::process::Command::new("gdbus");
        c.args([
            "call",
            "--session",
            "--dest",
            "org.freedesktop.Notifications",
            "--object-path",
            "/org/freedesktop/Notifications",
            "--method",
            "org.freedesktop.Notifications.Notify",
            "Mímir",
            "0",
            crate::flatpak::APP_ID,
            &gvariant_text(title),
            &gvariant_text(body),
            "[]",
            "{}",
            "-1",
        ]);
        c
    } else {
        let mut c = std::process::Command::new("notify-send");
        c.args(["-a", "Mímir", "-i", "mimir", title, body]);
        c
    };
    let _ = cmd
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_test_pc_is_silenced_by_its_variable() {
        assert!(silenced_by(Some("1")));
        assert!(silenced_by(Some("yes")));
        assert!(!silenced_by(None));
        assert!(!silenced_by(Some("")));
        assert!(!silenced_by(Some("0")));
    }
}
