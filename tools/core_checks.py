#!/usr/bin/env python3
"""The run log, results and coverage for tools/core-checks.sh.

The run log (~/.local/share/keep-it-working/mimir.jsonl) holds one
JSON object per line; see docs/designs/control-center/design_draft.md,
"The run log". Only the standard library is used.
"""

import datetime
import json
import os
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOG = pathlib.Path(
    os.environ.get(
        "KEEP_IT_WORKING_LOG",
        pathlib.Path.home() / ".local/share/keep-it-working/mimir.jsonl",
    )
)
FEATURES = ROOT / "checks/core-features.md"
PROOFS = ROOT / "checks/proofs"
TESTS = ROOT / "crates/ui/tests"


def git(*args):
    return subprocess.run(
        ["git", "-C", str(ROOT), *args], capture_output=True, text=True
    ).stdout.strip()


def claude_code_version():
    try:
        out = subprocess.run(
            ["claude", "--version"], capture_output=True, text=True, timeout=30
        ).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"\d+\.\d+\.\d+", out)
    return m.group(0) if m else None


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write(kind, results=None, missing=None, note=None, refers_to=None, anyway=None,
          program_sha256=None):
    line = {
        "time": now(),
        "kind": kind,
        "commit": git("rev-parse", "HEAD"),
        "dirty": bool(git("status", "--porcelain", "--untracked-files=no")),
        "claude_code": claude_code_version(),
        "results": results or {},
        "missing_features": missing or [],
        "program_sha256": program_sha256,
        "anyway": anyway,
        "note": note,
        "refers_to": refers_to,
    }
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("a") as f:
        f.write(json.dumps(line) + "\n")
    return line


def read_log():
    if not LOG.exists():
        return []
    out = []
    for raw in LOG.read_text().splitlines():
        try:
            out.append(json.loads(raw))
        except ValueError:
            continue
    return out


def features():
    """Feature ids in the owner's list, in order: ["CF-1", ..., "S-1"]."""
    return re.findall(r"^## ((?:CF|S)-\d+)", FEATURES.read_text(), re.M)


def check_number(check):
    m = re.match(r"cf(\d+)_", check)
    if m:
        return f"CF-{m.group(1)}"
    # The socket refusals (sec1_ to sec4_) are all S-1.
    return "S-1" if re.match(r"sec\d+_", check) else None


def window_checks():
    """Every core check (cfN_* in crates/ui/tests/window_*.rs, secN_* in
    phone_*.rs) and its file."""
    found = {}
    files = sorted(TESTS.glob("window_*.rs")) + sorted(TESTS.glob("phone_*.rs"))
    for f in files:
        for name in re.findall(r"^fn ((?:cf|sec)\d+_\w+)\(", f.read_text(), re.M):
            found[name] = f.relative_to(ROOT).as_posix()
    return found


def results_from_junit(path):
    """passed / failed / unreliable for each cf check in a nextest JUnit file."""
    results = {}
    tree = ET.parse(path)
    for case in tree.iter("testcase"):
        name = case.get("name", "")
        # Real checks (cfN_real_*) are run and logged by tools/real-check.sh.
        if not re.match(r"(?:cf|sec)\d+_", name) or "_real_" in name:
            continue
        tags = {child.tag for child in case}
        if "failure" in tags or "error" in tags:
            results[name] = "failed"
        elif "flakyFailure" in tags or "flakyError" in tags:
            results[name] = "unreliable"
        elif "skipped" in tags:
            results[name] = "could_not_run"
        else:
            results[name] = "passed"
    return results


def coverage(results):
    """Prints which feature each check covers; returns features with no check."""
    checks = window_checks()
    missing = []
    print("Core features and their window checks:")
    for cf in features():
        mine = [c for c in checks if check_number(c) == cf]
        if not mine:
            missing.append(cf)
            print(f"  {cf}: NO CHECK")
            continue
        words = ", ".join(f"{c} {results.get(c, 'not run')}" for c in mine)
        print(f"  {cf}: {words}")
    return missing


def cmd_window(junit):
    results = results_from_junit(junit)
    missing = coverage(results)
    write("window", results, missing)
    bad = {c: r for c, r in results.items() if r != "passed"}
    for c, r in bad.items():
        print(f"  not passed: {c} ({r})")
    if missing:
        print(f"  features with no check: {', '.join(missing)}")
    return 1 if any(r == "failed" for r in results.values()) else 0


def patch_head(check):
    """The expect, files and breaks lines of a check's proof patch."""
    head = {}
    for line in (PROOFS / f"{check}.patch").read_text().splitlines()[:3]:
        key, _, value = line.partition(": ")
        head[key] = value
    return head


def changed_since(commit, paths):
    if not commit:
        return True
    out = subprocess.run(
        ["git", "-C", str(ROOT), "diff", "--quiet", commit, "--", *paths]
    )
    return out.returncode != 0


def cmd_changed(with_files):
    """The checks to prove again: no proof yet, a failed one, or their test,
    patch (and with --files the source files they name) changed since."""
    checks = window_checks()
    last = {}
    for line in read_log():
        if line.get("kind") != "prove":
            continue
        for c, r in line.get("results", {}).items():
            last[c] = (line.get("commit"), r)
    todo = []
    for c, test in checks.items():
        patch = PROOFS / f"{c}.patch"
        commit, result = last.get(c, (None, None))
        paths = [test, patch.relative_to(ROOT).as_posix()]
        if with_files and patch.exists():
            paths += patch_head(c).get("files", "").split()
        if result != "passed" or changed_since(commit, paths):
            todo.append(c)
    print(" ".join(todo))
    return 0


def cmd_prove_log(outcomes):
    """outcomes: check=passed|failed ..."""
    results = dict(o.split("=", 1) for o in outcomes)
    write("prove", results, note=f"{len(results)} proofs")
    return 1 if any(r != "passed" for r in results.values()) else 0


def cmd_real_log(outcomes):
    """outcomes: check=passed|failed|could_not_run ... (tools/real-check.sh)"""
    results = dict(o.split("=", 1) for o in outcomes)
    failed = [c for c, r in results.items() if r == "failed"]
    skipped = [c for c, r in results.items() if r == "could_not_run"]
    note = f"{len(results)} real checks"
    if skipped:
        note += f"; could not run: {', '.join(skipped)}"
    write("real", results, note=note)
    print(f"Real checks: {len(results) - len(failed) - len(skipped)} passed, "
          f"{len(failed)} failed, {len(skipped)} could not run.")
    return 1 if failed else 0


def cmd_false(why, refers_to):
    lines = [l for l in read_log() if l.get("kind") != "false"]
    if refers_to is None:
        if not lines:
            print("The run log is empty; nothing to mark.")
            return 1
        refers_to = lines[-1]["time"]
    write("false", note=why, refers_to=refers_to)
    print(f"Marked the run at {refers_to} as a false failure: {why}")
    return 0


# --- The release gate (tools/release.sh) -----------------------------------


def last_line(kind, commit):
    found = None
    for line in read_log():
        if line.get("kind") == kind and line.get("commit") == commit and not line.get("dirty"):
            found = line
    return found


def last_proofs():
    last = {}
    for line in read_log():
        if line.get("kind") == "prove":
            last.update(line.get("results", {}))
    return last


def gate_state(commit):
    """Everything --draft needs to decide: (results, not_passed, missing)."""
    checks = window_checks()
    results, not_passed = {}, []
    window = last_line("window", commit)
    if window is None:
        not_passed.append(("window checks", "did not run on this commit"))
    else:
        for c in checks:
            r = window.get("results", {}).get(c, "not run")
            results[c] = r
            if r != "passed":
                not_passed.append((c, r))
    real = last_line("real", commit)
    if real is None:
        not_passed.append(("real checks", "did not run on this commit"))
    else:
        for c, r in real.get("results", {}).items():
            results[c] = r
            if r != "passed":
                not_passed.append((c, r))
    proofs = last_proofs()
    for c in checks:
        r = proofs.get(c, "not proven")
        if r != "passed":
            not_passed.append((f"{c} (proof)", r))
    have = {check_number(c) for c in checks}
    missing = [cf for cf in features() if cf not in have]
    for cf in missing:
        not_passed.append((cf, "has no check"))
    return results, not_passed, missing


def gate_notes(not_passed, anyway):
    out = ["", "## Core checks", ""]
    if not not_passed:
        out.append("Every core feature passed its checks for this release.")
    else:
        out.append("Not passed for this release:")
        out.append("")
        out += [f"- {what}: {why.replace('_', ' ')}" for what, why in not_passed]
    if anyway:
        out += ["", f"Released anyway: {anyway}"]
    return "\n".join(out) + "\n"


def cmd_gate(commit, mode, anyway, notes_file):
    """0: make the draft. 1: stop (block mode, something not passed)."""
    _, not_passed, _ = gate_state(commit)
    with open(notes_file, "a") as f:
        f.write(gate_notes(not_passed, anyway))
    if not not_passed:
        print("Core checks: everything passed.")
        return 0
    print("Core checks not passed:")
    for what, why in not_passed:
        print(f"  {what}: {why}")
    if mode == "block" and not anyway:
        print('Block mode: no draft. To release anyway, the owner says so and Claude runs '
              'tools/release.sh --draft --anyway "<the owner\'s words>".')
        return 1
    if mode == "block":
        print(f"Released anyway: {anyway}")
    else:
        print("Warn mode: the draft is made, and its notes name these.")
    return 0


def cmd_draft_log(commit, sha, anyway):
    results, not_passed, missing = gate_state(commit)
    note = "everything passed" if not not_passed else (
        "not passed: " + ", ".join(w for w, _ in not_passed))
    write("draft", results, missing, note=note, anyway=anyway or None, program_sha256=sha)
    return 0


def cmd_publish_check(sha):
    if any(l.get("kind") == "draft" and l.get("program_sha256") == sha for l in read_log()):
        return 0
    print("Refused: this draft's program wasn't checked (its fingerprint is in no draft "
          "line of the run log).")
    return 1


def cmd_home_copy(src, dst):
    """An older-version home for --check-home: @ROOT@ becomes the new root,
    in file contents and (as Claude Code names folders) in names."""
    root = str(pathlib.Path(dst).resolve())
    safe = "".join(c if c.isascii() and c.isalnum() else "-" for c in root)

    def copy(a, b):
        for e in sorted(pathlib.Path(a).iterdir()):
            to = pathlib.Path(b) / e.name.replace("@ROOT@", safe)
            if e.is_dir():
                to.mkdir(parents=True, exist_ok=True)
                copy(e, to)
            else:
                to.write_text(e.read_text().replace("@ROOT@", root))

    pathlib.Path(root).mkdir(parents=True, exist_ok=True)
    copy(src, root)
    return 0


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    cmd, rest = argv[0], argv[1:]
    if cmd == "window":
        return cmd_window(rest[0])
    if cmd == "changed":
        return cmd_changed("--files" in rest)
    if cmd == "checks":
        for c, test in window_checks().items():
            print(c, test)
        return 0
    if cmd == "expect":
        print(patch_head(rest[0]).get("expect", ""))
        return 0
    if cmd == "prove-log":
        return cmd_prove_log(rest)
    if cmd == "real-log":
        return cmd_real_log(rest)
    if cmd == "gate":
        commit, mode, notes = rest[0], rest[1], rest[2]
        return cmd_gate(commit, mode, " ".join(rest[3:]), notes)
    if cmd == "draft-log":
        return cmd_draft_log(rest[0], rest[1], " ".join(rest[2:]))
    if cmd == "publish-check":
        return cmd_publish_check(rest[0])
    if cmd == "publish-log":
        write("publish", program_sha256=rest[0], note=" ".join(rest[1:]) or None)
        return 0
    if cmd == "home-copy":
        return cmd_home_copy(rest[0], rest[1])
    if cmd == "false":
        refers = None
        if "--run" in rest:
            i = rest.index("--run")
            refers = rest[i + 1]
            rest = rest[:i] + rest[i + 2:]
        return cmd_false(" ".join(rest), refers)
    print(f"unknown command {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
