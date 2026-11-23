//! Labs on the PC: Run checks, and the plain-text problem report with the
//! link that opens GitHub's form. Mímir never sends a report itself: the
//! owner copies it, or opens the form and sends it there.
//!
//! The phone has the same shapes in Kotlin (`labs/`). The checks are asked
//! of a [`Probes`] so they are tested without a PC to ask.

use crate::scrub;
use serde_json::Value;
use std::path::Path;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    Good,
    Problem,
    Skipped,
}

/// One line of "Run checks".
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CheckLine {
    pub name: String,
    pub result: Outcome,
    pub line: String,
    /// For a problem: what to do next.
    pub next: Option<String>,
}

impl CheckLine {
    fn new(name: &str, result: Outcome, line: impl Into<String>, next: Option<&str>) -> CheckLine {
        CheckLine {
            name: name.to_string(),
            result,
            line: line.into(),
            next: next.map(str::to_string),
        }
    }

    pub fn mark(&self) -> &'static str {
        match self.result {
            Outcome::Good => "✓",
            Outcome::Problem => "✗",
            Outcome::Skipped => "–",
        }
    }

    pub fn as_text(&self) -> String {
        let next = self
            .next
            .as_ref()
            .map(|n| format!(" ({n})"))
            .unwrap_or_default();
        format!("{} {}: {}{next}", self.mark(), self.name, self.line)
    }
}

/// What "Run checks" asks of the PC. The window gives the real answers.
pub trait Probes {
    fn version(&self) -> String;
    /// Claude Code's version, or None when it isn't found.
    fn claude_version(&self) -> Option<String>;
    fn claude_signed_in(&self) -> bool;
    /// The local model runner: None when it can't be reached, else (models installed, models loaded).
    fn runner(&self) -> Option<(usize, usize)>;
    /// (phones paired, phones online now)
    fn phones(&self) -> (usize, usize);
    /// Whether the read-aloud voice is ready.
    fn voice_ready(&self) -> bool;
    fn free_bytes(&self) -> u64;
}

pub const LOW_BYTES: u64 = 2_000_000_000;

fn plural(n: usize, one: &str, many: &str) -> String {
    if n == 1 {
        format!("1 {one}")
    } else {
        format!("{n} {many}")
    }
}

pub fn run_checks(p: &dyn Probes) -> Vec<CheckLine> {
    let mut out = vec![];
    match p.claude_version() {
        None => out.push(CheckLine::new(
            "Claude Code",
            Outcome::Problem,
            "Not found",
            Some("Open Settings › General and install it"),
        )),
        Some(v) if !p.claude_signed_in() => out.push(CheckLine::new(
            "Claude Code",
            Outcome::Problem,
            format!("Found ({v}), but not signed in"),
            Some("Open Settings › General and sign in"),
        )),
        Some(v) => out.push(CheckLine::new(
            "Claude Code",
            Outcome::Good,
            format!("Found ({v}) and signed in"),
            None,
        )),
    }
    match p.runner() {
        None => out.push(CheckLine::new(
            "Models on this PC",
            Outcome::Problem,
            "Not answering",
            Some("Start Mímir's model helper, then run the checks again"),
        )),
        Some((0, _)) => out.push(CheckLine::new(
            "Models on this PC",
            Outcome::Problem,
            "Reachable, but no model is installed",
            Some("Get one in Settings › Models"),
        )),
        Some((installed, loaded)) => out.push(CheckLine::new(
            "Models on this PC",
            Outcome::Good,
            format!(
                "Reachable · {} · {} ready now",
                plural(installed, "model", "models"),
                loaded
            ),
            None,
        )),
    }
    let (paired, online) = p.phones();
    out.push(if paired == 0 {
        CheckLine::new("Phones", Outcome::Skipped, "None paired", None)
    } else if online == 0 {
        CheckLine::new(
            "Phones",
            Outcome::Problem,
            format!("{} paired, none connected now", paired),
            Some("Open Mímir on the phone"),
        )
    } else {
        CheckLine::new(
            "Phones",
            Outcome::Good,
            format!("{paired} paired · {online} connected now"),
            None,
        )
    });
    out.push(if p.voice_ready() {
        CheckLine::new("Voice", Outcome::Good, "Ready", None)
    } else {
        CheckLine::new(
            "Voice",
            Outcome::Problem,
            "Not ready",
            Some("Open Settings › Voice to get it"),
        )
    });
    let free = p.free_bytes();
    out.push(if free >= LOW_BYTES {
        CheckLine::new(
            "Space on this PC",
            Outcome::Good,
            format!("{} free", gb(free)),
            None,
        )
    } else {
        CheckLine::new(
            "Space on this PC",
            Outcome::Problem,
            format!("Only {} free", gb(free)),
            Some("Free some space in Settings › Storage"),
        )
    });
    out.push(CheckLine::new(
        "App version",
        Outcome::Good,
        p.version(),
        None,
    ));
    out
}

/// Room left on the disk holding `dir`.
pub fn free_bytes(dir: &Path) -> Option<u64> {
    use std::os::unix::ffi::OsStrExt;
    let c = std::ffi::CString::new(dir.as_os_str().as_bytes()).ok()?;
    // SAFETY: statvfs fills the struct we own from a valid C string.
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(c.as_ptr(), &mut st) } != 0 {
        return None;
    }
    Some(st.f_bavail as u64 * st.f_frsize as u64)
}

fn gb(b: u64) -> String {
    format!("{:.1} GB", b as f64 / 1_000_000_000.0)
}

/// What the report says about this PC's Mímir: nothing here names the owner or the PC.
#[derive(Clone, Debug, Default)]
pub struct Facts {
    pub version: String,
    /// "Flatpak" or "Installed program" and the like.
    pub how_installed: String,
    pub system: String,
    pub phones_paired: usize,
    /// "Claude", "Free (on this PC)" and so on.
    pub who_answers: String,
}

/// The report, in the order people read it. `errors` is already scrubbed; None: the notes are off.
pub fn build_report(
    f: &Facts,
    checks: Option<&[CheckLine]>,
    errors: Option<&[String]>,
    what: &str,
) -> String {
    let mut s = String::from("Mímir problem report\n");
    if !what.trim().is_empty() {
        s.push_str(&format!("What happened: {}\n\n", what.trim()));
    }
    s.push_str(&format!(
        "App: Mímir {} ({})\nSystem: {}\nPhones paired: {}\nWho answers: {}\n",
        f.version, f.how_installed, f.system, f.phones_paired, f.who_answers
    ));
    if let Some(c) = checks {
        s.push_str("\nChecks:\n");
        if c.is_empty() {
            s.push_str("  (not run yet)\n");
        }
        for l in c {
            s.push_str(&format!("  {}\n", l.as_text()));
        }
    }
    if let Some(e) = errors {
        s.push_str("\nRecent errors (names, messages and addresses taken out):\n");
        if e.is_empty() {
            s.push_str("  (none)\n");
        }
        for l in e {
            s.push_str(&format!("  {l}\n"));
        }
    }
    s.trim_end().to_string()
}

/// One line for the form's report box when the whole report is on the clipboard.
pub fn summary(f: &Facts) -> String {
    format!(
        "Mímir {} ({}), {}, phones paired {}, answers: {}",
        f.version, f.how_installed, f.system, f.phones_paired, f.who_answers
    )
}

pub const FORM: &str = "https://github.com/ODN-WKS/mimir/issues/new?template=report-a-problem.yml";
/// GitHub refuses very long addresses (it names no number), so the link stays under this.
pub const MAX_URL: usize = 2000;
pub const PASTE_NOTE: &str = "Paste the copied report below this line.";
pub const COPIED: &str = "Copied";
pub const COPIED_PASTE: &str = "The report is copied: paste it into the form.";

fn enc(s: &str) -> String {
    let mut o = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            o.push(b as char);
        } else {
            o.push_str(&format!("%{b:02X}"));
        }
    }
    o
}

fn one_line(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// The form's address with the title, the person's words (cut until it fits) and a one-line summary.
pub fn github_link(title: &str, what: &str, summary: &str) -> String {
    let report = format!(
        "{}\n\n{PASTE_NOTE}",
        one_line(summary).chars().take(300).collect::<String>()
    );
    let t: String = one_line(title).chars().take(80).collect();
    let mut w: Vec<char> = what.trim().chars().collect();
    let url = |w: &[char]| {
        format!(
            "{FORM}&title={}&what={}&report={}",
            enc(&t),
            enc(&w.iter().collect::<String>()),
            enc(&report)
        )
    };
    while url(&w).len() > MAX_URL && !w.is_empty() {
        let keep = (w.len() * 4 / 5).min(w.len() - 1);
        w.truncate(keep);
        while w.last().is_some_and(|c| c.is_whitespace()) {
            w.pop();
        }
    }
    url(&w)
}

/// A short title from what the person wrote: its first line, or a plain default.
pub fn title_from(what: &str) -> String {
    what.lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .map(|l| l.chars().take(70).collect())
        .unwrap_or_else(|| "A problem in Mímir".to_string())
}

/// Whether a notes entry is worth showing in a report: an error, a crash, a slow stretch or a dropped link.
pub fn is_important(v: &Value) -> bool {
    v.get("err").is_some()
        || matches!(v["cat"].as_str(), Some("slow" | "crash" | "error"))
        || matches!(v["ev"].as_str(), Some("drop" | "disconnect"))
}

/// The last 50 important lines of the notes in `dir`, scrubbed. Chat text is never in them.
pub fn recent_errors(dir: &Path, names: &[String]) -> Vec<String> {
    let raw: Vec<String> = crate::devlog::read_all(dir)
        .iter()
        .filter(|v| is_important(v))
        .map(crate::devlog::line_text)
        .collect();
    scrub::lines(&raw, names, 50)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fake {
        claude: Option<String>,
        signed: bool,
        runner: Option<(usize, usize)>,
        phones: (usize, usize),
        voice: bool,
        free: u64,
    }

    impl Default for Fake {
        fn default() -> Fake {
            Fake {
                claude: Some("2.1.0".into()),
                signed: true,
                runner: Some((2, 1)),
                phones: (1, 1),
                voice: true,
                free: 50_000_000_000,
            }
        }
    }

    impl Probes for Fake {
        fn version(&self) -> String {
            "0.01".into()
        }
        fn claude_version(&self) -> Option<String> {
            self.claude.clone()
        }
        fn claude_signed_in(&self) -> bool {
            self.signed
        }
        fn runner(&self) -> Option<(usize, usize)> {
            self.runner
        }
        fn phones(&self) -> (usize, usize) {
            self.phones
        }
        fn voice_ready(&self) -> bool {
            self.voice
        }
        fn free_bytes(&self) -> u64 {
            self.free
        }
    }

    fn by<'a>(l: &'a [CheckLine], name: &str) -> &'a CheckLine {
        l.iter().find(|c| c.name == name).unwrap()
    }

    #[test]
    fn everything_well() {
        let l = run_checks(&Fake::default());
        assert_eq!(l.len(), 6);
        assert!(l.iter().all(|c| c.result == Outcome::Good), "{l:?}");
    }

    #[test]
    fn each_problem_says_what_to_do() {
        let l = run_checks(&Fake {
            claude: None,
            runner: None,
            phones: (2, 0),
            voice: false,
            free: 100_000_000,
            ..Fake::default()
        });
        for n in [
            "Claude Code",
            "Models on this PC",
            "Phones",
            "Voice",
            "Space on this PC",
        ] {
            let c = by(&l, n);
            assert_eq!(c.result, Outcome::Problem, "{n}");
            assert!(c.next.as_ref().is_some_and(|x| !x.is_empty()), "{n}");
        }
    }

    #[test]
    fn signed_out_and_no_phones_and_no_model() {
        let l = run_checks(&Fake {
            signed: false,
            runner: Some((0, 0)),
            phones: (0, 0),
            ..Fake::default()
        });
        assert_eq!(by(&l, "Claude Code").result, Outcome::Problem);
        assert_eq!(by(&l, "Models on this PC").result, Outcome::Problem);
        assert_eq!(by(&l, "Phones").result, Outcome::Skipped);
    }

    fn facts() -> Facts {
        Facts {
            version: "0.01".into(),
            how_installed: "Flatpak".into(),
            system: "Linux, 1440x900".into(),
            phones_paired: 1,
            who_answers: "Online".into(),
        }
    }

    #[test]
    fn report_has_the_facts_in_order() {
        let checks = [CheckLine::new(
            "Voice",
            Outcome::Problem,
            "Not ready",
            Some("Get it"),
        )];
        let r = build_report(
            &facts(),
            Some(&checks),
            Some(&["E net: timed out".to_string()]),
            "It stops.\nHelp",
        );
        let at: Vec<Option<usize>> = [
            "problem report",
            "What happened: It stops.",
            "App: Mímir 0.01 (Flatpak)",
            "System: Linux",
            "Phones paired: 1",
            "Who answers: Online",
            "✗ Voice: Not ready (Get it)",
            "Recent errors",
            "E net: timed out",
        ]
        .iter()
        .map(|w| r.find(w))
        .collect();
        assert!(at.iter().all(Option::is_some), "{at:?}\n{r}");
        let n: Vec<usize> = at.into_iter().flatten().collect();
        assert!(n.windows(2).all(|w| w[0] < w[1]), "{r}");
    }

    #[test]
    fn report_leaves_out_what_is_off() {
        let r = build_report(&facts(), None, None, "");
        assert!(!r.contains("Checks:") && !r.contains("Recent errors"));
        assert!(build_report(&facts(), None, Some(&[]), "").contains("(none)"));
    }

    fn param(url: &str, key: &str) -> String {
        let q = url.split_once('?').unwrap().1;
        let v = q
            .split('&')
            .find_map(|p| p.strip_prefix(&format!("{key}=")))
            .unwrap();
        let mut bytes = vec![];
        let b = v.as_bytes();
        let mut i = 0;
        while i < b.len() {
            if b[i] == b'%' {
                bytes.push(u8::from_str_radix(&v[i + 1..i + 3], 16).unwrap());
                i += 3;
            } else {
                bytes.push(b[i]);
                i += 1;
            }
        }
        String::from_utf8(bytes).unwrap()
    }

    #[test]
    fn link_has_the_form_and_the_boxes() {
        let u = github_link(
            "It stops",
            "It stops when I open a chat & close it",
            "Mímir 0.01",
        );
        assert!(u.starts_with(
            "https://github.com/ODN-WKS/mimir/issues/new?template=report-a-problem.yml&title="
        ));
        assert_eq!(param(&u, "title"), "It stops");
        assert_eq!(param(&u, "what"), "It stops when I open a chat & close it");
        assert!(param(&u, "report").starts_with("Mímir 0.01"));
        assert!(param(&u, "report").contains(PASTE_NOTE));
        assert!(!u.contains(' ') && !u.contains("labels="));
    }

    #[test]
    fn long_text_is_cut_to_keep_the_link_short() {
        let u = github_link("t", &"word ".repeat(2000), "summary");
        assert!(u.len() <= MAX_URL, "{}", u.len());
        assert!(param(&u, "what").starts_with("word word"));
        let u = github_link(&"t".repeat(500), &"é".repeat(5000), &"s ".repeat(5000));
        assert!(u.len() <= MAX_URL, "{}", u.len());
    }

    #[test]
    fn title_comes_from_the_first_line() {
        assert_eq!(title_from("\n It stops \nmore"), "It stops");
        assert_eq!(title_from("   "), "A problem in Mímir");
        assert_eq!(title_from(&"x".repeat(300)).chars().count(), 70);
    }

    #[test]
    fn important_notes_are_picked() {
        let e: Value = serde_json::json!({"cat":"call","ev":"done","err":"x"});
        let ok: Value = serde_json::json!({"cat":"call","ev":"done"});
        let s: Value = serde_json::json!({"cat":"slow","ev":"frame"});
        assert!(is_important(&e) && is_important(&s) && !is_important(&ok));
    }
}
