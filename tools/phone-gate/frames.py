#!/usr/bin/env python3
"""Frame times from `dumpsys gfxinfo <pkg> framestats` dumps (phone gate).

    frames.py <budget ms> <dump file>...

Each frame is FrameCompleted minus IntendedVsync, over frames with Flags 0 (the ones Android says
to count), each frame once however many dumps hold it (a dump keeps only the last 120). Prints
"<count> <p50> <p90> <p95> <p99> <worst> <late count>" in ms and exits 0 when the 95th percentile
is within the budget, 1 when not, 2 when there were too few frames to say. A dump file named
`fs-<n>-<step>.txt` tells which step its new frames came from; a second line says which steps the
late frames were in ("late by step: scroll 12, open 4"), so a failure points at a screen.
"""
import os
import sys

MIN_FRAMES = 60


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]


def main():
    budget = float(sys.argv[1])
    seen = {}
    step_of = {}
    for path in sys.argv[2:]:
        base = os.path.basename(path)[:-4] if path.endswith(".txt") else os.path.basename(path)
        step = base.split("-", 2)[2] if base.count("-") >= 2 else base
        cols = None
        inside = False
        for line in open(path, errors="replace"):
            line = line.strip()
            if line == "---PROFILEDATA---":
                inside = not inside
                cols = None
                continue
            if not inside:
                continue
            f = line.split(",")
            if cols is None:
                cols = {n: i for i, n in enumerate(f) if n}
                continue
            try:
                flags = int(f[cols["Flags"]])
                start = int(f[cols["IntendedVsync"]])
                done = int(f[cols["FrameCompleted"]])
            except (KeyError, ValueError, IndexError):
                continue
            if flags != 0 or done >= 2**62 or done < start:
                continue
            if start not in seen:
                step_of[start] = step
            seen[start] = (done - start) / 1e6
    ms = list(seen.values())
    if len(ms) < MIN_FRAMES:
        print(f"{len(ms)} frames, too few to say")
        return 2
    late = sum(1 for m in ms if m > budget)
    p = [pct(ms, q) for q in (50, 90, 95, 99)]
    print(f"{len(ms)} " + " ".join(f"{v:.1f}" for v in p) + f" {max(ms):.1f} {late}")
    by = {}
    for start, m in seen.items():
        if m > budget:
            by[step_of[start]] = by.get(step_of[start], 0) + 1
    if by:
        print("late by step: " + ", ".join(f"{k} {v}" for k, v in sorted(by.items(), key=lambda kv: -kv[1])), file=sys.stderr)
    return 0 if p[2] <= budget else 1


sys.exit(main())
