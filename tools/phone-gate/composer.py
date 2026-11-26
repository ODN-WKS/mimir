#!/usr/bin/env python3
"""The message box's layout, from a uiautomator dump (phone gate, R1).

    composer.py <dump.xml> <screen width px>

Pass when the private note above it is short (at most 18 % of the screen) and the field is on top and, under it, the + (Attach), the mic (a Whisper's box only; P12), every chip and Send share
one row: their vertical centres within 6 px of each other, nothing wrapped onto a second row,
Send and the chips fully on the screen. Prints one line, "ok ..." or "BAD ...", and exits 0 or 1.
"""
import re
import sys
import xml.etree.ElementTree as ET


def box(b):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b or "")
    return tuple(int(x) for x in m.groups()) if m else None


def main():
    path, width = sys.argv[1], int(sys.argv[2])
    try:
        root = ET.parse(path).getroot()
    except Exception as e:  # an empty or half-written dump
        print(f"BAD no screen dump to read ({e})")
        return 1
    nodes = []
    for n in root.iter("node"):
        b = box(n.get("bounds"))
        if b and b[2] > b[0] and b[3] > b[1]:
            nodes.append((n.get("content-desc") or "", n.get("text") or "", n.get("class") or "", b))
    field = next((b for d, _, c, b in nodes if c.endswith("EditText")), None)
    if field is None:
        print("BAD the message field isn't on the screen")
        return 1
    send = next((b for d, _, _, b in nodes if d in ("Send", "Stop the reply")), None)
    attach = next((b for d, _, _, b in nodes if d == "Attach"), None)
    mic = next((b for d, _, _, b in nodes if d == "Talk instead of typing"), None)
    # The mic is in the chat's three dots (P12); only a Whisper's box keeps it.
    if send is None or attach is None:
        missing = [n for n, v in (("Send", send), ("+", attach)) if v is None]
        print("BAD not on the screen: " + ", ".join(missing))
        return 1
    # The chips: described buttons that start below the field's top edge and aren't the three above.
    chips = [b for d, _, _, b in nodes if (d.startswith("Answers come from") or d.startswith("Effort:") or d.startswith("Model") or d.startswith("Answers by")) and b[1] >= field[3] - 4]
    row = [attach, send] + ([mic] if mic else []) + chips
    centres = [(b[1] + b[3]) // 2 for b in row]
    spread = max(centres) - min(centres)
    problems = []
    # The private note above the box must stay short: never a tall column at a large text size.
    screen_h = max(b[3] for _, _, _, b in nodes)
    note = next((b for _, t, _, b in nodes if t.startswith("Private:")), None)
    if note is None:
        problems.append("the private note isn't on the screen")
    elif note[3] - note[1] > 0.18 * screen_h:
        problems.append(f"the private note is a tall column ({note[3] - note[1]} px of a {screen_h} px screen; at most {int(0.18 * screen_h)})")
    if spread > 6:
        problems.append(f"the + / mic / chips / Send are on different rows (centres {min(centres)}..{max(centres)} px apart {spread})")
    if min(b[1] for b in row) < field[3] - 4:
        problems.append("the buttons are beside the field, not under it")
    if send[2] > width or send[0] < 0 or send[2] <= send[0]:
        problems.append(f"Send isn't fully on the screen (right edge {send[2]} of {width})")
    for b in chips:
        if b[2] > width or b[0] < 0:
            problems.append("a chip runs off the screen")
    # Anything described that sits below the row would be a wrapped second row.
    low = [d for d, _, _, b in nodes if d and b[1] > max(b[3] for b in row) - 4 and not d.startswith("Message") and b[1] > field[3]]
    if low:
        problems.append("a second row under the first: " + ", ".join(low[:3]))
    if problems:
        print("BAD " + "; ".join(problems))
        return 1
    print(f"ok note {note[3]-note[1]} px tall, field {field[3]-field[1]} px tall, then one row of {len(row)} ({len(chips)} chips), centres within {spread} px, Send {send[0]}..{send[2]} of {width}")
    return 0


sys.exit(main())
