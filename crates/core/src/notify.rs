//! Desktop notifications.

/// A GVariant text literal for `gdbus call`.
fn gvariant_text(s: &str) -> String {
    format!("'{}'", s.replace('\\', "\\\\").replace('\'', "\\'"))
}

/// A desktop notification (KDE shows these in the corner).
pub fn notify(title: &str, body: &str) {
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
