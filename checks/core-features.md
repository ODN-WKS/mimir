# Mímir's core features

The features that must keep working in every release. The owner chose them on
2026-11-18 (the numbers keep those of the list of 13 they were chosen from); the
wording is the owner's to change. Each feature's window checks are the tests
named `cfN_*` in `crates/ui/tests/window_*.rs`, and each check has a proof
patch in `checks/proofs/` that breaks the feature and must make the check fail.
`tools/core-checks.sh` runs them; see
`docs/designs/control-center/design_draft.md`.

## CF-1

In a project, typing a request starts Claude, and its reply appears in the
window as it is written.

Approved: 2026-11-18

## CF-2

A message sent while Claude is working gets answered, and a task reopened after
closing Mímir continues the same conversation.

Approved: 2026-11-18

## CF-3

Allow lets the step run, Don't allow stops it and tells Claude, and Always
allow stops asking for that kind of step in that project.

Approved: 2026-11-18

## CF-4

When Claude asks a question, it shows in the orange question card, and the
owner's answer reaches Claude.

Approved: 2026-11-18

## CF-11

Update now installs the newest version and Mímir restarts on it, with
tasks and settings kept.

Approved: 2026-11-18

## CF-13

Tasks are listed under their projects, and sessions started in a terminal or on
the phone are listed and can be opened and continued.

Approved: 2026-11-18

## S-1

A Claude session can't approve its own requests: only Mímir's window (and, later, a paired phone) can answer a permission request, and a task's request is always its own.

Approved: 2026-11-18 (part of the phone app design, approved ahead of time)
