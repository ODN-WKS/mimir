#!/usr/bin/env python3
"""Message lint: every sentence the owner can see, on the PC and the phone,
checked for the things the owner keeps finding.

  python3 -I tools/message_lint.py            report: every finding, by rule
  python3 -I tools/message_lint.py --check    fails only on findings that are
                                              not in the baseline (new strings)
  python3 -I tools/message_lint.py --update   rewrites the baseline from today

Rules:
  raw-error     raw error text: "timed out", "Exception", "refused:", HTTP
                codes, or an error value pasted into the sentence
  tech-word     a technical word the owner shouldn't have to know
  claude-name   "Claude" where Mimir should be (real exceptions are in
                tools/message_lint_allow.txt)
  default-hostname  a default machine name ("steam" + "deck"), which SteamOS gives every machine
  no-next-step  says something went wrong but not what to do about it

Where it looks:
  PC     crates/ui/src (everything on screen), and in crates/core/src and
         crates/phone-core/src only text that reaches a screen: notes in a
         conversation, problems, and errors handed back to the window or phone
  phone  android/app/src/main/java (Kotlin), except model prompts and the
         stand-in station

The baseline (tools/message_lint_baseline.txt) lists today's known findings
as rule, file and text (no line numbers, so moving code doesn't matter). A
finding fixed since is reported as gone; --update drops it.
"""

import collections
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE = os.path.join(ROOT, "tools", "message_lint_baseline.txt")
ALLOW = os.path.join(ROOT, "tools", "message_lint_allow.txt")
# Never baselined: a message that says something went wrong must say what to
# do (no-next-step), and none may show a raw error (raw-error). Fix it, or mark
# a line that only reaches the developer log with "lint:log-only".
STRICT = {"no-next-step", "raw-error"}

KOTLIN_DIR = "android/app/src/main/java"
# Files with no owner-facing text: prompts for the models, the protocol,
# the native calls and the stand-in station used by screenshots.
KOTLIN_SKIP = {
    "GroundRules.kt",
    "ModelTest.kt",
    "FakeStation.kt",
    "Native.kt",
    "Wire.kt",
    "LinkLog.kt",
    # Search keywords for the settings search, not sentences.
    "SettingsSearch.kt",
}
RUST_UI = ["crates/ui/src"]
RUST_CORE = ["crates/core/src", "crates/phone-core/src"]
RUST_SKIP = {"main.rs", "bin"}

# ---------------------------------------------------------------- reading


class Lit:
    __slots__ = ("file", "line", "text", "context")

    def __init__(self, file, line, text, context):
        self.file, self.line, self.text, self.context = file, line, text, context


def rust_literals(src):
    """(line, text) for each string literal, comments skipped. Stops at the
    first `#[cfg(test)]`: the tests sit at the end of a file."""
    out = []
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
        elif src.startswith("#[cfg(test)]", i):
            break
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            line += src.count("\n", i, j)
            i = j
        elif c == "r" and re.match(r'r#*"', src[i:i + 10]) and not (
            i > 0 and (src[i - 1].isalnum() or src[i - 1] == "_")
        ):
            m = re.match(r'r(#*)"', src[i:])
            hashes = m.group(1)
            end = src.find('"' + hashes, i + len(m.group(0)))
            end = n if end < 0 else end
            text = src[i + len(m.group(0)):end]
            out.append((line, text))
            line += text.count("\n")
            i = end + 1 + len(hashes)
        elif c == '"':
            j, buf, start = i + 1, [], line
            while j < n and src[j] != '"':
                if src[j] == "\\" and j + 1 < n:
                    nxt = src[j + 1]
                    if nxt == "\n":
                        # A continued line: the next line's indent is dropped.
                        line += 1
                        j += 2
                        while j < n and src[j] in " \t":
                            j += 1
                        continue
                    buf.append({"n": "\n", "t": "\t"}.get(nxt, nxt))
                    j += 2
                    continue
                if src[j] == "\n":
                    line += 1
                buf.append(src[j])
                j += 1
            out.append((start, "".join(buf)))
            i = j + 1
        elif c == "'":
            # A char literal ('a', '\n', '"') or a lifetime ('a): skip either.
            m = re.match(r"'(\\.|\\u\{[0-9a-fA-F]+\}|[^\\'])'", src[i:i + 12])
            i += len(m.group(0)) if m else 1
        else:
            i += 1
    return out


def kotlin_literals(src):
    """(line, text) for each string literal; templates kept as written."""
    out = []
    i, n, line = 0, len(src), 1

    def read_string(i, line, raw):
        # Returns (end index after the closing quote, line, text).
        buf = []
        while i < n:
            if raw and src.startswith('"""', i):
                return i + 3, line, "".join(buf)
            if not raw and src[i] == '"':
                return i + 1, line, "".join(buf)
            if not raw and src[i] == "\\" and i + 1 < n:
                buf.append({"n": "\n", "t": "\t"}.get(src[i + 1], src[i + 1]))
                i += 2
                continue
            if src.startswith("${", i):
                # A template: copy it whole, strings inside it included.
                depth, j = 0, i + 1
                while j < n:
                    if src[j] == "{":
                        depth += 1
                    elif src[j] == "}":
                        depth -= 1
                        if depth == 0:
                            break
                    elif src[j] == '"':
                        j2, line, _ = read_string(j + 1, line, False)
                        j = j2 - 1
                    elif src[j] == "\n":
                        line += 1
                    j += 1
                buf.append(src[i:j + 1])
                i = j + 1
                continue
            if src[i] == "\n":
                line += 1
            buf.append(src[i])
            i += 1
        return n, line, "".join(buf)

    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            line += src.count("\n", i, j)
            i = j
        elif src.startswith('"""', i):
            start = line
            i, line, text = read_string(i + 3, line, True)
            out.append((start, text))
        elif c == '"':
            start = line
            i, line, text = read_string(i + 1, line, False)
            out.append((start, text))
        elif c == "'":
            m = re.match(r"'(\\.|\\u[0-9a-fA-F]{4}|[^\\'])'", src[i:i + 10])
            i += len(m.group(0)) if m else 1
        else:
            i += 1
    return out


# Lines whose text never reaches a screen.
NOT_SHOWN = re.compile(
    r"eprintln!|println!|\blog::|\btracing::|panic!|unreachable!|todo!|\.expect\(|"
    r"assert|debug_assert|\bLog\.[dviwe]\(|\brequire\(|\bcheck\(|Regex\(|toRegex|"
    r"Uri\.parse|id_salt|Id::new|env::var|Command::new|\.arg\(|\.args\(|getenv|"
    r"\bsay_log|\blog\(|\blogLine|\bwriteLog|LinkLog|\.d\(TAG"
)
# In the core, only text in these places reaches a screen.
CORE_SHOWN = re.compile(
    r"Entry::Note|Event::Problem|Problem\s*\{|\bErr\(|bail!|anyhow!|\bnote\(|"
    r"\bfail\(|\bproblem\(|Note\(|\bsay\(|reply:|line:|text:|title:"
)


# A line (or the one above it) carrying this marker never reaches a person:
# it is for the developer log only. Write it as a comment: // lint:log-only
LOG_ONLY = "lint:log-only"


def log_only(lines, ln):
    here = lines[ln - 1] if 0 < ln <= len(lines) else ""
    above = lines[ln - 2] if 1 < ln <= len(lines) + 1 else ""
    return LOG_ONLY in here or LOG_ONLY in above


def files(top, ext):
    base = os.path.join(ROOT, top)
    for d, subdirs, names in os.walk(base):
        subdirs[:] = sorted(s for s in subdirs if s not in RUST_SKIP)
        for name in sorted(names):
            if name.endswith(ext):
                yield os.path.join(d, name)


def collect():
    lits = []
    for top in RUST_UI + RUST_CORE:
        core = top in RUST_CORE
        for path in files(top, ".rs"):
            if os.path.basename(path) in RUST_SKIP:
                continue
            src = open(path, encoding="utf-8").read()
            lines = src.split("\n")
            for ln, text in rust_literals(src):
                here = lines[ln - 1] if ln - 1 < len(lines) else ""
                before = "\n".join(lines[max(0, ln - 4):ln])
                if NOT_SHOWN.search(here) or log_only(lines, ln):
                    continue
                if core and not CORE_SHOWN.search(before):
                    continue
                lits.append(Lit(os.path.relpath(path, ROOT), ln, text, before))
    for path in files(KOTLIN_DIR, ".kt"):
        if os.path.basename(path) in KOTLIN_SKIP:
            continue
        src = open(path, encoding="utf-8").read()
        lines = src.split("\n")
        for ln, text in kotlin_literals(src):
            here = lines[ln - 1] if ln - 1 < len(lines) else ""
            if NOT_SHOWN.search(here) or log_only(lines, ln):
                continue
            lits.append(Lit(os.path.relpath(path, ROOT), ln, text, here))
    return lits


# ---------------------------------------------------------------- deciding

PLACEHOLDER = re.compile(r"\{[^{}]*\}|\$\{[^}]*\}|\$[a-zA-Z_][a-zA-Z0-9_.]*|%[sdf]")


def is_prose(text):
    """Words for a person, not a path, key, pattern or prompt."""
    t = PLACEHOLDER.sub(" ", text).strip()
    if len(t) < 4 or len(text) > 600:
        return False
    if re.match(r"^(https?:|/|\.|[a-z_]+[:=.]|<|\{|\[|#|--?[a-z])", t):
        return False
    words = re.findall(r"[A-Za-zÀ-ÿ']{2,}", t)
    if len(words) < 2 or " " not in t:
        return False
    # Code-like: snake_case or camelCase identifiers only.
    if all(re.fullmatch(r"[a-z]+(_[a-z0-9]+)+|[a-z]+[A-Z]\w*", w) for w in t.split()):
        return False
    return True


RAW_ERROR = re.compile(
    r"(?i)\btimed out\b|exception|\brefused:|\bHTTP\s?[1-5]\d\d\b|"
    r"\b(status|code|error)\s?[45]\d\d\b|\bos error\b|\berrno\b|\bpanicked\b|"
    r"\bstack ?trace\b|"
    # an error value pasted into the sentence
    r"\{(e|err|error|why)(:[#?]+)?\}|\$\{?(e|err|t|ex)\b(\.message|\.localizedMessage)?\}?"
    r"|\.localizedMessage|\$\{e\.message\}|\.message\}"
)
TECH_WORDS = [
    "json", "socket", "stdin", "stdout", "stderr", "pid", "uuid", "daemon",
    "systemd", "rpc", "payload", "endpoint", "localhost", "127.0.0.1",
    "websocket", "jni", "null", "nan", "gguf", "onnx", "quantized",
    "quantization", "ollama", "llama.cpp", "env var", "environment variable",
    "exit code", "exit status", "sqlite", "mutex", "thread", "serde", "utf-8",
    "base64", "ipc", "protocol", "proto", "stack", "kernel", "distrobox",
    "nextest", "cargo", "gradle", "adb", "fcm", "relay", "searx",
    "searxng", "hook", "hooks", "session id", "session_id", "api", "http",
    "tcp", "udp", "port", "ip address", "hostname", "sha", "hash", "regex",
    "jsonl", "tokens", "token", "context window", "cli", "sdk",
]
TECH = re.compile(
    r"(?i)(?<![\w.])(" + "|".join(re.escape(w) for w in TECH_WORDS) + r")(?![\w])"
)
HOSTNAME_DEFAULT = re.compile(r"(?i)" + "steam" + " ?" + "deck")
CLAUDE = re.compile(r"Claude")
PROBLEM = re.compile(
    r"(?i)\b(couldn't|could not|can't|cannot|failed|failure|error|didn't work|"
    r"not found|wasn't|unable|went wrong|lost|is down|isn't (reachable|running|"
    r"answering|signed in|set up|available|connected))\b"
)
NEXT_STEP = re.compile(
    r"(?i)\b(try|tap|click|open|check|press|turn|restart|sign in|choose|pick|"
    r"wait|retry|again|settings|connect|make sure|use|set up|install|reload|"
    r"go to|plug|start|update|ask|allow|download|free up|move|keep|send|"
    r"reconnect|unlock|pair|scan|switch|will|automatically|instead|later|"
    r"when|once|until|run|fix|add|remove|delete|copy|close|reopen|charge|"
    r"type|say|speak|choose|carry on|carries on|skip)\b"
)


def allowed_claude():
    phrases = []
    if os.path.exists(ALLOW):
        for raw in open(ALLOW, encoding="utf-8"):
            raw = raw.split("#", 1)[0].strip()
            if raw:
                # "path|phrase" allows the phrase in that one file only.
                where, _, phrase = raw.rpartition("|")
                phrases.append((where, phrase))
    # Longest first, so "Claude Code's screen" goes before "Claude Code".
    return sorted(phrases, key=lambda w: len(w[1]), reverse=True)


def judge(lit, allow):
    text = lit.text
    if not is_prose(text):
        return []
    found = []
    plain = PLACEHOLDER.sub(" ", text)
    if RAW_ERROR.search(text):
        found.append("raw-error")
    if TECH.search(plain):
        found.append("tech-word")
    rest = text
    for where, p in allow:
        if where and not lit.file.endswith(where):
            continue
        rest = re.sub(re.escape(p), " ", rest, flags=re.I)
    if CLAUDE.search(rest):
        found.append("claude-name")
    if HOSTNAME_DEFAULT.search(text):
        found.append("default-hostname")
    # A question ("Lost a phone?") is a label or a choice, not a failure.
    if PROBLEM.search(plain) and not NEXT_STEP.search(plain) and not plain.rstrip().endswith("?"):
        found.append("no-next-step")
    return found


def norm(text):
    return re.sub(r"\s+", " ", text).strip()


def key(rule, lit):
    return f"{rule}\t{lit.file}\t{norm(lit.text)}"


def findings():
    allow = allowed_claude()
    out = []
    for lit in collect():
        for rule in judge(lit, allow):
            out.append((rule, lit))
    return out


def read_baseline():
    c = collections.Counter()
    if os.path.exists(BASELINE):
        for raw in open(BASELINE, encoding="utf-8"):
            raw = raw.rstrip("\n")
            if raw and not raw.startswith("#"):
                c[raw] += 1
    return c


def main(argv):
    mode = argv[1] if len(argv) > 1 else "--report"
    found = findings()
    keys = collections.Counter(key(r, l) for r, l in found)
    if mode == "--update":
        with open(BASELINE, "w", encoding="utf-8") as f:
            f.write(
                "# Message lint: known findings (rule, file, text). New ones fail\n"
                "# tools/check-all.sh. Fix one, then: python3 -I tools/message_lint.py --update\n"
            )
            for k in sorted(keys.elements()):
                if k.split("\t", 1)[0] not in STRICT:
                    f.write(k + "\n")
        print(f"baseline written: {sum(keys.values())} known findings")
        return 0
    base = read_baseline()
    # The strict kinds ignore the baseline: any of them is new.
    base = collections.Counter({k: v for k, v in base.items() if k.split("\t", 1)[0] not in STRICT})
    new = keys - base
    gone = base - keys
    by_rule = collections.Counter(r for r, _ in found)
    side = collections.Counter(
        ("phone" if l.file.startswith("android") else "PC", r) for r, l in found
    )
    if mode == "--report":
        for rule in ["raw-error", "tech-word", "claude-name", "default-hostname", "no-next-step"]:
            items = [l for r, l in found if r == rule]
            print(f"\n== {rule}: {len(items)}")
            for l in items:
                print(f"  {l.file}:{l.line}  {norm(l.text)[:140]!r}")
    print(
        "\nmessage lint: "
        + ", ".join(f"{r} {by_rule[r]}" for r in sorted(by_rule))
        + f" (PC {sum(v for (s, _), v in side.items() if s == 'PC')},"
        + f" phone {sum(v for (s, _), v in side.items() if s == 'phone')});"
        + f" {sum(new.values())} new, {sum(gone.values())} fixed since the baseline"
    )
    if gone:
        print("Fixed since the baseline (run --update to drop them):")
        for k in sorted(gone):
            print("  " + k.replace("\t", "  "))
    if mode == "--check" and new:
        print("\nNew owner-facing text breaks a message rule:")
        index = {key(r, l): (r, l) for r, l in found}
        for k in sorted(new):
            r, l = index[k]
            print(f"  [{r}] {l.file}:{l.line}  {norm(l.text)!r}")
        print(
            "Say what happened and what the owner can do, in plain words "
            "(see docs/designs/qa/quality_plan.md, A3). If the text is right as "
            "it is, add it with: python3 -I tools/message_lint.py --update (never for no-next-step "
            "or raw-error: fix those, or mark developer-log-only text with lint:log-only)"
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
