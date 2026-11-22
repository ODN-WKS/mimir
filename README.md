# Mímir

A private assistant for your PC and your phone. Ask anything from either one;
chats stay with you. Pair the phone with the PC, choose who answers (your own
PC for free, or an online model), and keep both in step.

- Private by design: no account, no tracking, no telemetry.
- The PC app and service are written in Rust (`crates/`), the phone app in
  Kotlin (`android/`).
- Updates are checked before they are installed.

**Found a problem?** [Report it here](https://github.com/ODN-WKS/mimir/issues/new?template=report-a-problem.yml) — or in the app: Labs › Report a problem. A security problem? See [SECURITY.md](SECURITY.md).

## Install

Mímir comes in two files, nothing else: a Flatpak for the PC and an APK for the phone.

- PC: `flatpak install --user https://github.com/ODN-WKS/mimir/releases/latest/download/io.github.odnwks.Mimir.flatpakref`
  (or open that file: your software centre installs Mímir and adds its source). From then on
  Mímir updates like any other app, in your software centre or with `flatpak update`. The
  runtime it needs is fetched automatically. `Mimir-<version>.flatpak` is an offline copy for
  a PC without internet; it does not update by itself.
- Phone: open `mimir-<version>.apk` on the phone. To check it's the real app, its signing
  certificate's SHA-256 is
  `3ade6f99a3477767d640f1c9895e17ecd32d870cd2c398373630e90f369e18ad`.

Updates: Mímir tells you when a new version is out (in Settings, and the sidebar). With the
source added, "Update now" gets it and "Restart Mímir to finish" opens the new copy. If you
installed from the offline file, Settings offers "Switch to automatic updates" (your chats and
settings stay). Mímir's source is signed with its own key; `flatpak` refuses anything else.

## Permissions (the PC Flatpak)

One line each, and why (the manifest in `packaging/flatpak/` says the same):

- Wayland, with X11 as the fallback, and graphics (`dri`): the window.
- Network: the online company you choose, the update check, and the link to your phones.
- Sound (PulseAudio): reading aloud and talking instead of typing.
- Home folder: your projects and their session files live there, at real paths the
  PC's own tools must also see (the file-chooser portal hands out paths they
  can't use), and Mímir keeps its chats and settings in `~/.local/share/mimir` and
  `~/.config/mimir`, so the data is the same before and after the switch.
- Talk to the Flatpak service (`org.freedesktop.Flatpak`): runs git, the terminal and the
  optional project-work tool (Claude Code) on the PC with `flatpak-spawn --host`. This is effectively a way out of the
  sandbox, and the one big permission: without it Mímir cannot start project work. Only that
  one helper (`crates/core/src/flatpak.rs`) uses it.
- Notifications: "Done" and "needs you" notices.
- The background portal (asks you, and you can switch it off in System Settings): keeps
  Mímir in the background and starts it with your session.

## Build and check

```
tools/check-all.sh          # every check: format, lint, tests, privacy scan
tools/cargo.sh build --release
tools/release.sh --dry-run  # builds the Flatpak and the APK into ~/.cache/mimir/release
tools/release-update-test.sh  # real check of the Flatpak source and "Update now" (stand-in app)
```

`tools/release.sh --publish` also puts the signed Flatpak source on the `gh-pages` branch
(`/flatpak/`). The source's signing key lives in `~/.config/mimir-release/gpg` and must be
backed up; without it nobody can receive further updates.

The phone app builds with Gradle from `android/`
(`./gradlew testDevDebugUnitTest` runs its tests).

Release notes are in `docs/release-notes/`. The release signing key's public
half is `release-key.pub`.

## Licence

Mímir is free software under the GNU General Public License, version 3 only (`GPL-3.0-only`); see `LICENSE`. Copyright ODN-WKS. Third-party parts keep their own licences and are listed in `THIRD-PARTY-NOTICES.md`.
