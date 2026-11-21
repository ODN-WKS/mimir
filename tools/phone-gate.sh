#!/usr/bin/env bash
# The phone gate: before a phone update reaches the owner, this checks the bugs the owner already
# found (docs/designs/qa/regressions.md) on a real phone and says PASS, FAIL or SKIP (why) for each.
#
#   tools/phone-gate.sh --serial <adb serial> [--model-phone] [--only R2,R6] [--dry-run]
#                       [--apk <file>] [--no-build] [--keep-app] [--no-offline] [--owner-ok]
#
#   --model-phone  the phone can run a model: also runs R5 and R7 (they need one)
#   --only         run just these: R1 R2 R3 R5 R6 R7 R9 FT K1 (FT = frame timing, K1 = kill the app mid-save)
#   --dry-run      print what would be done to the phone, change nothing on it
#   --apk          check this build (a dev build: io.github.odnwks.mimir.dev)
#   --no-build     don't build; use the build already made in this checkout
#   --keep-app     leave the check build installed afterwards
#   --no-offline   skip the part of R3 that switches the phone's internet off for a minute
#   --owner-ok     the owner has said this is his own phone (the S25) may be used
#
# What it does to the phone (all put back afterwards, also on Ctrl-C): media volume to 0, the text
# size, the standby bucket and Doze, Wi-Fi and mobile data (R3's offline part), staying awake while
# plugged in, the screen. The check build is installed fresh and taken off again.
# It exits 1 if any scenario FAILs. A pass mark is never loosened to get a pass: a failing scenario
# is reported, with its numbers.
#
# Results: ~/.cache/mimir-checks/phone-gate/<serial>-<time>.txt (screenshots beside it,
# in a folder of the same name), and the phone's developer log pulled with tools/phone-log.sh.
set -uo pipefail
cd "$(dirname "$0")/.."
repo="$PWD"
here="$repo/tools/phone-gate"
PKG=io.github.odnwks.mimir.dev
NS=io.github.odnwks.mimir
RECV="$PKG/$NS.standalone.CheckAsk"
ASK="$NS.STANDALONE_ASK"
cache="${STATION_RELEASE_CACHE:-$HOME/.cache/mimir-checks}"
# Set these for your own devices: the serial of the test phone, the start of the serial of a
# personal phone to be extra careful with, and the address of a TV that must never be used.
S8_SERIAL="${MIMIR_TEST_PHONE:-none-set}"
PERSONAL_PHONE="${MIMIR_PERSONAL_PHONE:-none-set}"
TV="${MIMIR_TV_ADDRESS:-none-set}"

serial=""; model_phone=0; only=""; DRY=0; apk=""; build=1; keep=0; offline=1; owner_ok=0
while [ $# -gt 0 ]; do
    case "$1" in
        --serial) serial="${2:-}"; shift 2 ;;
        --model-phone) model_phone=1; shift ;;
        --only) only="${2:-}"; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        --apk) apk="${2:-}"; shift 2 ;;
        --no-build) build=0; shift ;;
        --keep-app) keep=1; shift ;;
        --no-offline) offline=0; shift ;;
        --owner-ok) owner_ok=1; shift ;;
        -h|--help) sed -n 2,26p "$0"; exit 0 ;;
        *) echo "Unknown option $1 (see --help)"; exit 2 ;;
    esac
done
[ -n "$serial" ] || { echo "Give the phone's adb serial: --serial <serial> (adb devices)"; exit 2; }
case "$serial" in *"$TV"*) echo "That is the TV, not a test device."; exit 2 ;; esac

stamp="$(date +%Y%m%d-%H%M%S)"
out="$cache/phone-gate"
mkdir -p "$out"
summary="$out/$serial-$stamp.txt"
shots="$out/$serial-$stamp"
work="$(mktemp -d "$cache/phone-gate/work.XXXXXX")"
mkdir -p "$shots"
: > "$summary"

say() { printf '%s\n' "$*" | tee -a "$summary"; }
note() { printf '%s\n' "$*" >&2; }

# --- talking to the phone ---------------------------------------------------------------------
adb_() { if ((DRY)); then note "  [dry-run] adb $*"; return 0; fi; adb -s "$serial" "$@"; }
# The owner's phone (the S25) comes first: if another app is in front than the check build (or the home screen, or the
# lock screen), every step that would touch the screen waits. The check build is never brought back, and nothing is
# relaunched on a focus change. It carries on once the screen has been off or locked for 2 minutes. (owner, 2026-11-20)
OWNER_PHONE=0; OPENED=0
case "$serial" in "$PERSONAL_PHONE"*|adb-"$PERSONAL_PHONE"*) OWNER_PHONE=1 ;; esac
owner_guard() {
    ((OWNER_PHONE && !DRY)) || return 0
    # The owner swiped away from the test: everything was handed back by the helper; stop at once.
    if [ "$(s25-hands-off released 2>/dev/null)" = yes ]; then say "STOPPED the owner took the phone back; nothing more is done on it"; rc=5; exit 5; fi
    ((OPENED)) || return 0
    local f; f="$(adb -s "$serial" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)"
    case "$f" in *"$PKG"*|*launcher*|*NotificationShade*|*StatusBar*|*Keyguard*|*"Window{"*" u0 }"*) return 0 ;; esac
    mimir activity list >/dev/null 2>&1
    s25-hands-off note "Paused: your phone is yours. Tests carry on when you're done." >/dev/null 2>&1
    note "Paused: another app is in front ($f). Waiting until the screen has been off for 2 minutes."
    local off=0 waited=0
    while [ $off -lt 120 ] && [ $waited -lt 1800 ]; do
        sleep 10; waited=$((waited + 10))
        if adb -s "$serial" shell dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'; then off=0; else off=$((off + 10)); fi
    done
    if [ $off -lt 120 ]; then say "PAUSED the checks stayed paused for the owner; stopping without touching the phone"; rc=4; exit 4; fi
    adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; adb -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1
    s25-hands-off note "Continuing the tests" >/dev/null 2>&1
}
sh_() {
    if ((DRY)); then note "  [dry-run] adb shell $*"; return 0; fi
    case "$1" in input|am) case "$*" in "am force-stop"*|"am broadcast"*|"am set-standby"*|"am get-standby"*) ;; *) owner_guard ;; esac ;; esac
    adb -s "$serial" shell "$@" 2>&1 | tr -d '\r'
}
now_s() { date +%s; }
setting() { sh_ settings get "$1" "$2"; }

LOGPID=""
logstart() { # <name>: follow the app's log lines into $work/<name>.log
    LOG="$work/$1.log"; : > "$LOG"
    ((DRY)) && return 0
    # From this moment on (the phone's own clock): clearing the buffer isn't enough, old lines came back and an
    # earlier series' "done" line was read as this one's.
    local from; from="$(adb -s "$serial" shell "date '+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r')"
    adb -s "$serial" logcat -v time -T "$from" NaturalReader:I MimirStandalone:I '*:S' > "$LOG" 2>/dev/null &
    LOGPID=$!
}
logstop() { [ -n "$LOGPID" ] && { kill -9 "$LOGPID" 2>/dev/null; wait "$LOGPID" 2>/dev/null; LOGPID=""; }; return 0; }
waitlog() { # <regex> <seconds>: wait for a line in $LOG
    ((DRY)) && return 0
    local end=$(( $(now_s) + $2 ))
    while [ "$(now_s)" -lt "$end" ]; do grep -Eq "$1" "$LOG" 2>/dev/null && return 0; sleep 1; done
    return 1
}
# The media volume: `media volume` where the phone has it, else `cmd media_session volume` (Android 17 has no `media`).
vol_get() { local o; o="$(sh_ media volume --stream 3 --get)"; case "$o" in *"not found"*|*inaccessible*) o="$(sh_ cmd media_session volume --stream 3 --get)" ;; esac; echo "$o" | grep -o 'volume is [0-9]*' | grep -o '[0-9]*'; }
vol_set() { ((OWNER_PHONE)) && return 0; local o; o="$(sh_ media volume --stream 3 --set "$1")"; case "$o" in *"not found"*|*inaccessible*) sh_ cmd media_session volume --stream 3 --set "$1" >/dev/null ;; esac; }
q() { local s=$1 sq="'"; printf "'%s'" "${s//$sq/$sq\\$sq$sq}"; }
hook() { local a=""; for x in "$@"; do a+=" $(q "$x")"; done; sh_ "am broadcast -a $ASK -n $RECV$a" >/dev/null; }

# --- what is put back -------------------------------------------------------------------------
orig_font=""; orig_vol=""; orig_stay=""; orig_wifi=""; orig_data=""; orig_bucket=""; orig_awake=""
CH_FONT=0; CH_VOL=0; CH_STAY=0; CH_NET=0; CH_BUCKET=0; CH_DOZE=0; INSTALLED=0; CLAIMED=0; ACT=""
rc=0; fails=0; ran=0

restore() {
    logstop
    ((DRY)) && return 0
    ((CH_DOZE)) && { sh_ dumpsys deviceidle unforce >/dev/null; sh_ dumpsys battery reset >/dev/null; CH_DOZE=0; }
    ((CH_BUCKET)) && { sh_ am set-standby-bucket "$PKG" "${orig_bucket:-active}" >/dev/null; CH_BUCKET=0; }
    if ((CH_NET)); then
        [ "$orig_wifi" = 1 ] && sh_ svc wifi enable >/dev/null
        [ "$orig_data" = 1 ] && sh_ svc data enable >/dev/null
        CH_NET=0
    fi
    if ((CH_FONT)); then
        if [ "$orig_font" = null ] || [ -z "$orig_font" ]; then sh_ settings delete system font_scale >/dev/null
        else sh_ settings put system font_scale "$orig_font" >/dev/null; fi
        CH_FONT=0
    fi
    ((CH_VOL)) && { vol_set "$orig_vol"; CH_VOL=0; }
    ((CH_STAY)) && { sh_ settings put global stay_on_while_plugged_in "$orig_stay" >/dev/null; CH_STAY=0; }
    sh_ input keyevent KEYCODE_HOME >/dev/null
    ((INSTALLED)) && ((!keep)) && { adb -s "$serial" uninstall "$PKG" >/dev/null 2>&1; INSTALLED=0; }
    [ "$orig_awake" = Asleep ] || [ "$orig_awake" = Dozing ] && sh_ input keyevent 223 >/dev/null
    return 0
}
finish() {
    local code=$?
    trap - EXIT INT TERM
    restore
    [ -n "$ACT" ] && mimir activity stop "$ACT" >/dev/null 2>&1
    ((CLAIMED)) && [ "$serial" = "$S8_SERIAL" ] && s8 release >/dev/null 2>&1
    find "$work" -mindepth 1 -delete 2>/dev/null; rmdir "$work" 2>/dev/null
    [ "$rc" != 0 ] && code=$rc
    ((code == 0 && fails > 0)) && code=1
    if ((!DRY)); then say ""; say "Summary kept in $summary"; fi
    exit "$code"
}
trap 'rc=130; exit 130' INT TERM
trap finish EXIT

# --- reporting --------------------------------------------------------------------------------
wanted() { [ -z "$only" ] || [[ ",$only," == *",$1,"* ]]; }
report() { # <PASS|FAIL|SKIP> <id> <name> <detail>
    local verdict="$1"
    [ "$verdict" = SKIP ] && say "SKIP $2 $3 ($4)" || say "$verdict $2 $3: $4"
    [ "$verdict" = FAIL ] && fails=$((fails + 1))
    ran=$((ran + 1))
}

# --- the phone --------------------------------------------------------------------------------
model="$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
if ((!DRY)); then
    [ -n "$model" ] || { echo "No phone answers on $serial (adb devices)."; exit 2; }
    case "$model" in SM-S9*) ((owner_ok)) || { echo "$model looks like the owner's own phone; it may be used only with his OK (--owner-ok)."; exit 2; } ;; esac
else
    model="${model:-(dry run)}"
fi
sdk="$(adb -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
fps="$(adb -s "$serial" shell dumpsys display 2>/dev/null | grep -o 'fps=[0-9.]*' | head -1 | cut -d= -f2)"
fps="${fps:-60}"
budget="$(python3 -I -c "print(round(1000/float('$fps'),2))")"
git_dirty=""; [ -n "$(git status --porcelain -- android tools/phone-gate tools/phone-gate.sh)" ] && git_dirty=" (with changes not committed)"
commit="$(git rev-parse --short HEAD)$git_dirty"

say "Phone gate $(date '+%Y-%m-%d %H:%M')  phone $model ($serial), Android API ${sdk:-?}, ${fps} Hz (frame budget ${budget} ms)"
say "Commit $commit  build: $PKG (the check build: release code, with the check hooks)  model phone: $( ((model_phone)) && echo yes || echo no )"
((DRY)) && say "DRY RUN: nothing below is done to the phone; each step is shown as '[dry-run] adb ...' on stderr."

# Claim and announce (the test-devices rules).
if ((!DRY)); then
    if [ "$serial" = "$S8_SERIAL" ] && command -v s8 >/dev/null; then
        s8 claim "phone-gate: regression checks" >/dev/null || { echo "The S8 is in use by another session; not touching it."; rc=3; exit 3; }
        CLAIMED=1
    fi
    needs=(); [ "$serial" = "$S8_SERIAL" ] && needs=(--needs device:S8)
    ACT="$(mimir activity start --name "phone-gate" --what "Regression checks on the $model" "${needs[@]}" --minutes 40 2>/dev/null | tail -1 | awk '{print $NF}')"
    case "$ACT" in *[!A-Za-z0-9_-]*|"") ACT="" ;; esac
fi

# --- build and install ------------------------------------------------------------------------
build_apk() {
    if [ -n "$apk" ]; then return 0; fi
    local g="$cache/gate"
    mkdir -p "$g/build" "$g/app-build"
    [ -e android/build ] || ln -s "$g/build" android/build
    [ -e android/app/build ] || ln -s "$g/app-build" android/app/build
    if ((build)); then
        note "Building the check build (about a minute)..."
        unset LISTEN_FDS LISTEN_PID LISTEN_FDNAMES LISTEN_PIDFDID
        distrobox enter dev -- bash -lc "cd '$repo/android' && export ANDROID_HOME=\$HOME/Android/Sdk && ./gradlew -q --no-daemon --max-workers=6 assembleDevBenchmark" >"$work/build.log" 2>&1 \
            || { tail -20 "$work/build.log"; say "FAIL the check build didn't build (see above)"; rc=1; exit 1; }
    fi
    apk="$repo/android/app/build/outputs/apk/dev/benchmark/app-dev-benchmark.apk"
}
if ((!DRY)); then
    build_apk
    [ -f "$apk" ] || { say "No check build at $apk"; rc=1; exit 1; }
else
    note "  [dry-run] would build the check build (assembleDevBenchmark) unless --apk or --no-build"
    apk="${apk:-(the check build)}"
fi

# --- set up what a quiet, repeatable run needs -------------------------------------------------
if ((!DRY)); then
    orig_font="$(setting system font_scale)"
    orig_vol="$(vol_get)"
    orig_stay="$(setting global stay_on_while_plugged_in)"
    orig_wifi="$(setting global wifi_on)"
    orig_data="$(setting global mobile_data)"
    orig_bucket="$(sh_ am get-standby-bucket "$PKG" | tr -d ' ')"
    orig_awake="$(sh_ dumpsys power | grep -o 'mWakefulness=[A-Za-z]*' | head -1 | cut -d= -f2)"
    case "$orig_bucket" in 5|exempted) orig_bucket=exempted ;; 10|active) orig_bucket=active ;; 20|working_set) orig_bucket=working_set ;; 30|frequent) orig_bucket=frequent ;; 40|rare) orig_bucket=rare ;; 45|restricted) orig_bucket=restricted ;; *) orig_bucket=active ;; esac
else
    orig_font=1.0; orig_vol=0; orig_stay=0; orig_wifi=1; orig_data=0; orig_bucket=active; orig_awake=Awake
fi
vol_set 0; CH_VOL=1
((OWNER_PHONE)) || { sh_ settings put global stay_on_while_plugged_in 3 >/dev/null; CH_STAY=1; }
vol_now="$(vol_get)"
if ((!DRY)) && [ "$vol_now" != 0 ]; then say "FAIL setup: the media volume wouldn't go to 0 (it is $vol_now); stopping so nothing is played out loud"; rc=1; exit 1; fi
sh_ input keyevent KEYCODE_WAKEUP >/dev/null
sh_ wm dismiss-keyguard >/dev/null

# GATE_KEEP_DATA=1: install over the check build already there, keeping its data (a model downloaded into it).
((DRY)) || [ "${GATE_KEEP_DATA:-0}" = 1 ] || { adb -s "$serial" uninstall "$PKG" >/dev/null 2>&1; }
note "Installing the check build..."
if ((!DRY)); then adb -s "$serial" install -r -g "$apk" >/dev/null || { say "FAIL the check build wouldn't install"; rc=1; exit 1; }; INSTALLED=1
else note "  [dry-run] adb install -r -g <apk>"; fi

open_app() { sh_ am force-stop "$PKG" >/dev/null; sh_ am start -n "$PKG/$NS.MainActivity" >/dev/null; wait_focus && OPENED=1; sleep 2; }
focus_ok() { ((DRY)) && return 0; local f; f="$(sh_ dumpsys window | grep mCurrentFocus)"; [[ "$f" == *"$PKG"* ]]; }
wait_focus() { ((DRY)) && return 0; local n=0; while ! focus_ok && [ $n -lt 15 ]; do sleep 1; n=$((n + 1)); done; focus_ok; }
dump_ui() { # <file>
    ((DRY)) && return 0
    sh_ uiautomator dump /sdcard/gate-ui.xml >/dev/null
    adb -s "$serial" pull /sdcard/gate-ui.xml "$1" >/dev/null 2>&1
}
locate() { # <dump.xml> <text-or-desc>: print the centre "x y" of the first element with exactly this text or description
    python3 -I - "$1" "$2" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[2]
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    if want in ((n.get("text") or ""), (n.get("content-desc") or "")):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m:
            a, b, c, d = map(int, m.groups())
            print((a + c) // 2, (b + d) // 2)
            break
PY
}
screen_size() { sh_ wm size | grep -o '[0-9]*x[0-9]*' | tail -1; }

# --- R9: nothing plays and the volume stays down ---------------------------------------------
r9() {
    local name="no sound on install and open"
    open_app
    sleep 6
    if ((DRY)); then report SKIP R9 "$name" "dry run: would open the app, wait 6 s, then check the volume is still 0 and no player of the app is started"; return; fi
    local uid vol players
    uid="$(sh_ dumpsys package "$PKG" | grep -m1 -o 'userId=[0-9]*' | cut -d= -f2)"
    vol="$(vol_get)"
    players="$(sh_ dumpsys audio | grep -E "AudioPlaybackConfiguration" | grep -E "u/pid:$uid/" | grep -c "state:started")"
    if ! focus_ok; then report FAIL R9 "$name" "the check build was not in front after opening it"; return; fi
    if [ "$vol" != 0 ]; then report FAIL R9 "$name" "the volume went from 0 to $vol after opening"
    elif [ "${players:-0}" != 0 ]; then report FAIL R9 "$name" "$players player(s) of the app were playing after opening"
    else report PASS R9 "$name" "volume still 0, 0 players started, 6 s after opening"; fi
}

# --- R1: the message box --------------------------------------------------------------------
r1() {
    local name="message box layout"
    local bad=0 detail="" shot_w
    if ((DRY)); then
        sh_ am start -n "$PKG/$NS.ui.adaptive.CheckComposerActivity" --es mode unpaired >/dev/null
        report SKIP R1 "$name" "dry run: would open the real message box (unpaired and paired) at text size 1.0 and 1.5, screenshot each and check + / mic / chips / Send are one row under the field"
        return
    fi
    shot_w="$(screen_size | cut -dx -f1)"
    for scale in 1.0 1.5; do
        sh_ settings put system font_scale "$scale" >/dev/null; CH_FONT=1
        for mode in unpaired paired; do
            local try
            for try in 1 2 3; do
                sh_ am force-stop "$PKG" >/dev/null
                sh_ am start -n "$PKG/$NS.ui.adaptive.CheckComposerActivity" --es mode "$mode" >/dev/null
                wait_focus && break
            done
            sleep 1.5
            if ! focus_ok; then bad=1; detail+="[$mode, size $scale: the message box screen didn't open] "; continue; fi
            adb -s "$serial" exec-out screencap -p > "$shots/R1-$mode-$scale.png" 2>/dev/null
            dump_ui "$work/composer.xml"
            local v; v="$(python3 -I "$here/composer.py" "$work/composer.xml" "$shot_w")"
            case "$v" in ok*) detail+="[$mode, size $scale: ${v#ok }] " ;; *) bad=1; detail+="[$mode, size $scale: ${v#BAD }] " ;; esac
        done
    done
    # Back to the text size the phone had.
    if [ "$orig_font" = null ] || [ -z "$orig_font" ]; then sh_ settings delete system font_scale >/dev/null; else sh_ settings put system font_scale "$orig_font" >/dev/null; fi
    CH_FONT=0
    if ((bad)); then report FAIL R1 "$name" "$detail(screenshots in $shots)"; else report PASS R1 "$name" "$detail(screenshots in $shots; the real message box on a stand-in screen)"; fi
}

# --- Frame timing: the app's screens and a list, scripted -----------------------------------
ft() {
    local name="frame timing"
    local k=0 files=()
    if ((DRY)); then
        report SKIP FT "$name" "dry run: would reset gfxinfo, tap through the tabs and scroll Settings, read framestats after each step, and pass when the 95th percentile frame time is within ${budget} ms"
        return
    fi
    open_app
    sh_ dumpsys gfxinfo "$PKG" reset >/dev/null
    grab() { k=$((k + 1)); sh_ dumpsys gfxinfo "$PKG" framestats > "$work/fs-$k-$1.txt"; files+=("$work/fs-$k-$1.txt"); }
    local wh w h
    wh="$(screen_size)"; w="${wh%x*}"; h="${wh#*x}"
    local missing=""
    # Where things are, read once: no screen dump while frames are being measured.
    dump_ui "$work/main.xml"
    declare -A at
    for label in Status Updates Chat Settings; do at[$label]="$(locate "$work/main.xml" "$label")"; done
    sh_ dumpsys gfxinfo "$PKG" reset >/dev/null
    for round in 1 2 3; do
        for tab in Status Updates Chat; do
            if [ -n "${at[$tab]}" ]; then sh_ input tap ${at[$tab]} >/dev/null; sleep 0.4; grab "tab-$tab"; sleep 0.6; grab "tab-$tab"; else missing="$tab"; fi
        done
        # Settings opens as a screen change, then a list is scrolled both ways.
        if [ -n "${at[Settings]}" ]; then sh_ input tap ${at[Settings]} >/dev/null
            sleep 0.4; grab settings-open; sleep 0.5; grab settings-open
            for i in 1 2 3; do
                sh_ input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 300 >/dev/null; grab scroll-up
                sh_ input swipe $((w / 2)) $((h / 4)) $((w / 2)) $((h * 3 / 4)) 300 >/dev/null; grab scroll-down
            done
            sh_ input keyevent KEYCODE_BACK >/dev/null; sleep 0.4; grab settings-back
        else missing="Settings"; fi
    done
    focus_ok || { report FAIL FT "$name" "the check build left the front while the screens were driven"; return; }
    local r
    r="$(python3 -I "$here/frames.py" "$budget" "${files[@]}" 2>"$work/frames.err")"; local st=$?
    [ -s "$work/frames.err" ] && say "  frame timing: $(cat "$work/frames.err")"
    if [ $st = 2 ]; then report FAIL FT "$name" "measured only: $r (the scripted steps found little to draw${missing:+; could not tap: $missing})"; return; fi
    set -- $r
    local detail="$1 frames, median $2 ms, 90th $3 ms, 95th $4 ms, 99th $5 ms, worst $6 ms, $7 over ${budget} ms (pass: 95th ≤ ${budget} ms at ${fps} Hz)"
    [ -n "$missing" ] && detail+="; could not tap: $missing"
    if [ $st = 0 ] && [ -z "$missing" ]; then report PASS FT "$name" "$detail"; else report FAIL FT "$name" "$detail"; fi
}

# --- R6 and R3: lookups through the check build's hook ---------------------------------------
series() { # <kind> <rounds> <max seconds> [label]: runs the hook, leaves the lines in $LOG (a file of its own per label)
    logstart "series-${4:-$1}"
    hook --es series "$1" --ei rounds "$2"
    waitlog "check series $1 done" "$3"
}
r6() {
    local name="10 searches in a row"
    if ((DRY)); then series search 10 150; report SKIP R6 "$name" "dry run: would run 10 searches through the hook; pass when median ≤ 2 s and worst ≤ 6 s with none failing"; return; fi
    open_app
    if ! series search 10 200; then logstop; report FAIL R6 "$name" "the 10 searches did not finish within 200 s ($(grep -c 'check series search [0-9]' "$LOG") done)"; return; fi
    logstop
    local line med worst failed
    line="$(grep 'check series search done' "$LOG" | tail -1)"
    med="$(echo "$line" | sed -n 's/.*median \([0-9]*\).*/\1/p')"; worst="$(echo "$line" | sed -n 's/.*worst \([0-9]*\).*/\1/p')"
    failed="$(grep -c 'check series search [0-9]*: .* FAILED' "$LOG")"
    local detail="median $med ms, worst $worst ms, $failed failed (pass: median ≤ 2000, worst ≤ 6000, none failed)"
    if [ -n "$med" ] && [ "$med" -le 2000 ] && [ "$worst" -le 6000 ] && [ "$failed" = 0 ]; then report PASS R6 "$name" "$detail"; else report FAIL R6 "$name" "$detail"; fi
}
net_state() { sh_ dumpsys connectivity | grep -c 'NetworkAgentInfo.*state: CONNECTED'; }
r3() {
    local name="weather lookup"
    if ((DRY)); then series weather 6 90; report SKIP R3 "$name" "dry run: would ask 6 weather questions live (each answered within 6 s), then with the internet off 2 more (each ending in an honest failure within 7.5 s)"; return; fi
    open_app
    if ! series weather 6 120; then logstop; report FAIL R3 "$name" "the 6 weather questions did not finish within 120 s"; return; fi
    logstop
    local times worst failed
    times="$(grep -o 'check series weather [0-9]*: [0-9]* ms' "$LOG" | grep -o ': [0-9]* ms' | grep -o '[0-9]*')"
    worst="$(echo "$times" | sort -n | tail -1)"
    failed="$(grep -c 'check series weather [0-9]*: .* FAILED' "$LOG")"
    local live="live: worst $worst ms, $failed of 6 failed (pass: each answer within 6000 ms)"
    local live_ok=0; [ -n "$worst" ] && [ "$worst" -le 6000 ] && [ "$failed" = 0 ] && live_ok=1
    # With every source down: an honest failure, not a hang.
    local off_detail="offline part skipped (--no-offline)" off_ok=1
    if ((offline)); then
        orig_wifi="$(setting global wifi_on)"; orig_data="$(setting global mobile_data)"
        CH_NET=1
        sh_ svc wifi disable >/dev/null; sh_ svc data disable >/dev/null
        local end=$(( $(now_s) + 20 ))
        while [ "$(net_state)" != 0 ] && [ "$(now_s)" -lt "$end" ]; do sleep 1; done
        if [ "$(net_state)" != 0 ]; then
            off_detail="offline part not run: the phone stayed connected"
        else
            if series weather 2 40 weather-offline; then
                logstop
                local otimes oworst ofail
                otimes="$(grep -o 'check series weather [0-9]*: [0-9]* ms' "$LOG" | grep -o ': [0-9]* ms' | grep -o '[0-9]*')"
                oworst="$(echo "$otimes" | sort -n | tail -1)"
                ofail="$(grep -c 'check series weather [0-9]*: .* FAILED' "$LOG")"
                off_detail="offline: both ended in $oworst ms at most, $ofail of 2 reported failure (pass: failure within 7500 ms)"
                [ -n "$oworst" ] && [ "$oworst" -le 7500 ] && [ "$ofail" = 2 ] || off_ok=0
            else
                logstop; off_detail="offline: no end within 40 s"; off_ok=0
            fi
        fi
        [ "$orig_wifi" = 1 ] && sh_ svc wifi enable >/dev/null
        [ "$orig_data" = 1 ] && sh_ svc data enable >/dev/null
        CH_NET=0
        local end2=$(( $(now_s) + 30 ))
        while [ "$(net_state)" = 0 ] && [ "$(now_s)" -lt "$end2" ]; do sleep 1; done
    fi
    if ((live_ok && off_ok)); then report PASS R3 "$name" "$live; $off_detail"; else report FAIL R3 "$name" "$live; $off_detail"; fi
}

# --- R2: reading aloud with the screen off ---------------------------------------------------
# The notice is a record that BELONGS to the check build ("(0x...: pkg=<package> ..." at its top). The last record
# of the dump is followed by the rest of the dump, which names every package, so the top of each record is what counts.
notice_up() { sh_ dumpsys notification --noredact | awk -v p="pkg=$PKG " 'BEGIN{RS="NotificationRecord"} index(substr($0, 1, 120), p) && /Reading aloud/ {f=1} END{exit !f}'; }
r2() {
    local name="read aloud, screen off"
    local words="${GATE_READ_WORDS:-700}"
    if ((DRY)); then
        logstart r2; hook --es read long --ei words "$words"
        report SKIP R2 "$name" "dry run: would read a $words-word message with volume 0, switch the screen off after 5 s, and pass when the log says \"read in\", the \"Reading aloud\" notice showed while reading and was gone after, and the reading took 3 minutes or more"
        return
    fi
    open_app
    logstart r2
    hook --es read long --ei words "$words"
    if ! waitlog "check read: started" 20; then logstop; report FAIL R2 "$name" "the read never started"; return; fi
    sleep 5
    sh_ input keyevent 223 >/dev/null
    sleep 2
    local awake; awake="$(sh_ dumpsys power | grep -o 'mWakefulness=[A-Za-z]*' | head -1 | cut -d= -f2)"
    local up=0
    for i in 1 2 3; do notice_up && { up=1; break; }; sleep 2; done
    local start; start="$(now_s)"
    waitlog "check read: ended" 900 ; local ended=$?
    local secs=$(( $(now_s) - start ))
    logstop
    sh_ input keyevent KEYCODE_WAKEUP >/dev/null; sh_ wm dismiss-keyguard >/dev/null
    local gone=0
    for i in 1 2 3 4 5; do notice_up || { gone=1; break; }; sleep 1; done
    local readin; readin="$(grep -o 'read in [0-9]* ms' "$LOG" | grep -o '[0-9]*' | tail -1)"
    local detail="screen was $awake at 7 s; notice while reading: $( ((up)) && echo shown || echo MISSING ); notice after: $( ((gone)) && echo gone || echo STILL THERE ); read in ${readin:-none} ms (pass: reaches the end, 180000 ms or more of reading, notice shown then gone)"
    local ok=1
    [ "$ended" = 0 ] && [ -n "$readin" ] && [ "$readin" -ge 180000 ] && ((up)) && ((gone)) && [ "$awake" != Awake ] || ok=0
    if ((ok)); then report PASS R2 "$name" "$detail"; else report FAIL R2 "$name" "$detail"; fi
}

# --- K1: kill the app in the middle of saving, 20 times (PHE-01, PHD-01) ----------------------
# The real chat store and the real pairing store (the phone's Keystore), saving a longer list as fast as they can;
# the app is force-stopped after a random time, then the store is read back. Nothing acknowledged may be lost,
# the file must never be unreadable, and the pairing identity must never change.
k1_count() { # <what>: prints "count unreadable bad key temps" from the check build's own log line
    logstart k1c
    hook --es kill_test "$1" --es phase count
    waitlog "kill_test $1 count=" 20 || { logstop; echo "none"; return; }
    logstop
    grep -o "kill_test $1 count=.*" "$LOG" | tail -1 | sed -E 's/.*count=([0-9]+) unreadable=([a-z]+) bad=([a-z]+)( key=(-?[0-9]+))? temps_before=([0-9]+).*/\1 \2 \3 \5 \6/'
}
k1() {
    local name="kill the app mid-save, 20 times"
    local rounds="${GATE_KILL_ROUNDS:-20}"
    if ((DRY)); then
        report SKIP K1 "$name" "dry run: would save chats and waiting messages as fast as possible $rounds times, force-stop the app after 0.3 to 1.4 s each time, and pass when nothing that was saved is missing, the file is never unreadable and the pairing identity never changes"
        return
    fi
    open_app
    local what bad_total=0 detail=""
    for what in store vault; do
        hook --es kill_test "$what" --es phase reset; sleep 1
        local prev=0 key="" i lost=0 unread=0 keychg=0 temps=0 final=0
        for i in $(seq 1 "$rounds"); do
            LOG="$work/k1.log"
            adb -s "$serial" logcat -c >/dev/null 2>&1
            hook --es kill_test "$what"
            sleep "$(python3 -I -c "import random; print(round(random.uniform(0.3, 1.4), 2))")"
            sh_ am force-stop "$PKG" >/dev/null
            # Read what the app logged before it died (not a stream the kill could cut short).
            adb -s "$serial" logcat -d -v time MimirStandalone:I '*:S' > "$LOG" 2>/dev/null
            local acked
            acked="$(grep -o "kill_test $what saved [0-9]*" "$LOG" | tail -1 | grep -o '[0-9]*$')"
            acked="${acked:-$prev}"
            local got cnt un bd kk tmp
            got="$(k1_count "$what")"
            read -r cnt un bd kk tmp <<<"$got"
            if [ "$got" = none ] || [ -z "$cnt" ]; then lost=$((lost + 1)); detail+="[$what round $i: no answer from the check build] "; continue; fi
            [ "$un" = true ] && { unread=$((unread + 1)); detail+="[$what round $i: the file could not be read after the kill] "; }
            [ "$cnt" -lt "$acked" ] && { lost=$((lost + acked - cnt)); detail+="[$what round $i: $acked saved, $cnt found] "; }
            [ "$cnt" -gt $((acked + 1)) ] && { lost=$((lost + 1)); detail+="[$what round $i: $acked saved, $cnt found (more than one in flight)] "; }
            if [ "$what" = vault ]; then
                if [ -z "$key" ]; then key="$kk"; elif [ "$key" != "$kk" ]; then keychg=$((keychg + 1)); fi
            fi
            temps=$((temps + ${tmp:-0}))
            prev="$cnt"; final="$cnt"
            sh_ am force-stop "$PKG" >/dev/null
        done
        hook --es kill_test "$what" --es phase reset
        detail+="$what: $rounds kills, $final kept at the end, $lost lost, $unread unreadable, ${keychg} identity changes, $temps half-written temp files met (and swept). "
        bad_total=$((bad_total + lost + unread + keychg))
    done
    if ((bad_total == 0)); then report PASS K1 "$name" "$detail"; else report FAIL K1 "$name" "$detail"; fi
}

# --- R4: Thorough effort on a plain lookup question doesn't think for minutes (model phone) -----
r4() {
    local name="no long thinking on a lookup"
    if ((DRY)); then report SKIP R4 "$name" "dry run, model phone only: would ask a weather question at effort Thorough, and pass when the first word comes within 5 s once warm and no thinking ran past 45 s"; return; fi
    open_app
    logstart r4
    hook --es text "What is the weather in Portland today?" --es effort thorough --ez new true
    waitlog "reply timing" 240 || { logstop; report FAIL R4 "$name" "no answer within 240 s (is a model installed?)"; return; }
    sleep 1
    # Asked again once warm: the first answer pays for loading the model.
    hook --es text "And what is the weather in Seattle?" --es effort thorough
    local n=0; while [ "$(grep -c 'reply timing' "$LOG")" -lt 2 ] && [ $n -lt 240 ]; do sleep 1; n=$((n + 1)); done
    logstop
    local line first thought think
    line="$(grep 'reply timing' "$LOG" | tail -1)"
    first="$(echo "$line" | sed -n 's/.*first-word=\([0-9]*\)ms.*/\1/p')"; thought="$(echo "$line" | sed -n 's/.*thought-end=\([0-9]*\)ms.*/\1/p')"
    think="$(echo "$line" | sed -n 's/.*think=\([a-z]*\).*/\1/p')"
    local detail="warm answer: first word $first ms, thinking ended at ${thought:-?} ms, think=$think (pass: first word ≤ 5000 ms, thinking ≤ 45000 ms)"
    if [ -n "$first" ] && [ "$first" -le 5000 ] && { [ -z "$thought" ] || [ "$thought" -le 45000 ]; }; then report PASS R4 "$name" "$detail"; else report FAIL R4 "$name" "$detail"; fi
}

# --- R7: a follow-up reads only what is new (model phone) ------------------------------------
r7() {
    local name="follow-up re-read"
    if ((DRY)); then
        report SKIP R7 "$name" "dry run, model phone only: would ask a question, then a follow-up in the same chat, and pass when the follow-up read fewer tokens than the whole chat and its first word came within 1.5 s"
        return
    fi
    open_app
    logstart r7
    hook --es text "What is the capital of France?" --ez new true
    waitlog "reply timing" 240 || { logstop; report FAIL R7 "$name" "no first answer within 240 s (is a model installed?)"; return; }
    sleep 3
    hook --es text "And roughly how many people live there?"
    local n=0; while [ "$(grep -c 'reply timing' "$LOG")" -lt 2 ] && [ $n -lt 240 ]; do sleep 1; n=$((n + 1)); done
    logstop
    local line new total first
    line="$(grep 'reply timing' "$LOG" | tail -1)"
    new="$(echo "$line" | sed -n 's/.*read=\([0-9]*\)\/\([0-9]*\)tok.*/\1/p')"; total="$(echo "$line" | sed -n 's/.*read=\([0-9]*\)\/\([0-9]*\)tok.*/\2/p')"
    first="$(echo "$line" | sed -n 's/.*first-word=\([0-9]*\)ms.*/\1/p')"
    local detail="follow-up read $new of $total tokens, first word $first ms (pass: new < whole chat, first word ≤ 1500 ms)"
    if [ -n "$new" ] && [ -n "$first" ] && [ "$new" -lt "$total" ] && [ "$first" -le 1500 ]; then report PASS R7 "$name" "$detail"; else report FAIL R7 "$name" "${detail:-no timing line in the log}"; fi
}

# --- R5: nothing runs on in the background (model phone) -------------------------------------
busy_threads() { # app threads using over 15 % of a core
    local pid; pid="$(sh_ pidof "$PKG")"
    [ -n "$pid" ] || { echo 0; return; }
    sh_ top -H -b -n 1 -p "$pid" | awk 'NR>7 && $9+0 > 15 {c++} END{print c+0}'
}
r5() {
    local name="background stop"
    if ((DRY)); then
        sh_ am set-standby-bucket "$PKG" restricted >/dev/null; sh_ dumpsys deviceidle force-idle >/dev/null
        report SKIP R5 "$name" "dry run, model phone only: would start a long answer, leave the app, put the app in the restricted bucket and force Doze, and pass when the answer stops or finishes within 150 s with no busy threads left; then bucket and Doze are reset"
        return
    fi
    open_app
    logstart r5
    hook --es text "Write a long, detailed story, at least 1500 words, about a lighthouse keeper." --ez new true
    sleep 8
    # Home is pressed only while the check build is in front (never on another app).
    focus_ok || { logstop; report FAIL R5 "$name" "the check build was not in front, so Home was not pressed"; return; }
    # On the owner's S25 the hands-off guard is told this Home is the test's own (nothing more is done on screen).
    ((OWNER_PHONE)) && touch "$HOME/.cache/s25-expect-away"
    sh_ input keyevent KEYCODE_HOME >/dev/null
    sh_ am set-standby-bucket "$PKG" restricted >/dev/null; CH_BUCKET=1
    local idle; idle="$(sh_ dumpsys deviceidle force-idle)"; CH_DOZE=1
    local t0; t0="$(now_s)" stopped=0
    while [ $(( $(now_s) - t0 )) -lt 150 ]; do
        if grep -Eq "check: [0-9]+ ms|reply timing" "$LOG"; then stopped=1; break; fi
        [ "$(busy_threads)" = 0 ] && [ $(( $(now_s) - t0 )) -gt 20 ] && { stopped=1; break; }
        sleep 3
    done
    local took=$(( $(now_s) - t0 ))
    sleep 5
    local busy; busy="$(busy_threads)"
    logstop
    sh_ am set-standby-bucket "$PKG" "$orig_bucket" >/dev/null; CH_BUCKET=0
    sh_ dumpsys deviceidle unforce >/dev/null; sh_ dumpsys battery reset >/dev/null; CH_DOZE=0
    rm -f "$HOME/.cache/s25-expect-away"
    local detail="Doze: $(echo "$idle" | head -1); the answer stopped or finished after $took s; $busy busy thread(s) left (pass: within 150 s, 0 busy threads); bucket and Doze reset"
    if ((stopped)) && [ "$busy" = 0 ]; then report PASS R5 "$name" "$detail"; else report FAIL R5 "$name" "$detail"; fi
}

# --- run them ---------------------------------------------------------------------------------
sequence=(R9 R1 FT R6 R3 R2 K1 R4 R7 R5)
for id in "${sequence[@]}"; do
    wanted "$id" || continue
    case "$id" in
        R4|R5|R7)
            if ((!model_phone)); then report SKIP "$id" "$( [ $id = R5 ] && echo 'background stop' || echo 'follow-up re-read' )" "model phone only; pass --model-phone on a phone with a model"; continue; fi ;;
    esac
    case "$id" in
        R9) r9 ;; R1) r1 ;; FT) ft ;; R6) r6 ;; R3) r3 ;; R2) r2 ;; K1) k1 ;; R4) r4 ;; R7) r7 ;; R5) r5 ;;
    esac
done

# --- the phone's own developer log ------------------------------------------------------------
if ((!DRY)); then
    say ""
    say "Developer log (tools/phone-log.sh), the app's own lines (the whole log is in ~/.cache/mimir-checks/phone-logs/$serial):"
    "$repo/tools/phone-log.sh" "$serial" -p "$PKG" -g "NaturalReader\|MimirStandalone" -n 40 2>&1 | tail -30 | tee -a "$summary"
    say ""
    say "Result: $((ran - fails)) of $ran not failing, $fails FAIL"
fi
((fails > 0)) && exit 1
exit 0
