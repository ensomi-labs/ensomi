#!/usr/bin/env bash
# Captures the prototype's screens on the one booted emulator or device, with the debug APK installed:
# every scenario of app/src/main/kotlin/io/ensomi/android/prototype/Prototype.kt in light and dark mode,
# a few at 200 % font size and in landscape, UI hierarchy dumps, and a recording of the playfield preview.
#
#   scripts/capture-prototype.sh OUT_DIR
set -euo pipefail

OUT=${1:?usage: scripts/capture-prototype.sh OUT_DIR}
mkdir -p "$OUT"
PKG=io.ensomi.android
ACTIVITY=$PKG/.MainActivity

launch() { # scenario [extra am args...]
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$ACTIVITY" --es prototype "$@" >/dev/null
}
shot() { adb exec-out screencap -p >"$OUT/$1.png"; echo "$1.png"; }
swipe_up() { adb shell input swipe 540 1900 540 700 500; sleep 1.5; }

# scenario:seconds to wait before the screenshot
SCENARIOS=(
  first-run:3 downloading:3 download-stopped:3 no-space:3 library:3
  make-chart:3 make-chart-sections:3 make-chart-section-problem:3 make-chart-no-model:3
  make-chart-making:3 make-chart-made:3 make-chart-stopped:3 make-chart-failed:3
  results:3 settings:3 settings-downloading:3 calibration-sound:3 calibration-picture:5 diagnostics:6
)

# A freshly booted emulator under load can show "System UI isn't responding" over every screen. Keep error
# dialogs off for the capture (crashes still reach the crash log) and tap Wait on one already showing.
dismiss_anr_dialog() {
  local dump bounds
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
  dump=$(adb shell cat /sdcard/ui.xml)
  case "$dump" in *"t responding"*) ;; *) return 0 ;; esac
  bounds=$(printf '%s' "$dump" | tr '>' '\n' | grep 'text="Wait"' | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | head -1)
  [ -n "$bounds" ] || return 0
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 2
}

tap_text() { # text on screen to tap
  local bounds
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
  bounds=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1\"" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | head -1)
  [ -n "$bounds" ] || { echo "no \"$1\" on screen"; return 0; }
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

restore() {
  adb shell settings delete secure immersive_mode_confirmations || true
  adb shell settings put global hide_error_dialogs 0 || true
  adb shell settings put system font_scale 1.0 || true
  adb shell settings put system user_rotation 0 || true
  adb shell cmd uimode night no || true
}
trap restore EXIT

adb shell settings put global hide_error_dialogs 1
dismiss_anr_dialog
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell settings put system font_scale 1.0

for mode in light dark; do
  if [ "$mode" = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  sleep 2
  dismiss_anr_dialog
  for entry in "${SCENARIOS[@]}"; do
    name=${entry%%:*}
    launch "$name"
    sleep "${entry##*:}"
    shot "$mode-$name"
    case "$name" in
      make-chart-sections|make-chart-made|results|calibration-sound)
        swipe_up; shot "$mode-$name-scrolled" ;;
      settings)
        swipe_up; shot "$mode-$name-2"; swipe_up; shot "$mode-$name-3"; swipe_up; shot "$mode-$name-4" ;;
    esac
  done
  # The wallpaper's colours instead of the brand's, for comparison.
  launch library --ez dynamicColor true; sleep 3; shot "$mode-library-dynamic"
  launch make-chart-made --ez dynamicColor true; sleep 3; shot "$mode-make-chart-made-dynamic"
done
adb shell cmd uimode night no
sleep 2

# The first game after install: Android's "Viewing full screen" prompt holds the game at the start of its lead-in.
adb shell settings delete secure immersive_mode_confirmations
launch play
sleep 4; shot play-first-prompt
tap_text "Got it"
sleep 4; shot play-after-prompt
# The prompt is shown once; mark it seen so it covers no other capture.
adb shell settings put secure immersive_mode_confirmations confirmed

# A game: the play screen at three moments, paused, counting in, and the results the runtime's score gives at the end.
launch play
sleep 6; shot play-1
sleep 5; shot play-2
sleep 8; shot play-3
sleep 16; shot play-end-results
launch play-paused; sleep 15; shot play-paused
launch play-countdown; sleep 15; shot play-countdown

# Larger text: Android's font size at 200 %.
adb shell settings put system font_scale 2.0
sleep 2
for name in library make-chart make-chart-made settings results; do
  launch "$name"; sleep 3; shot "font200-$name"
done
adb shell settings put system font_scale 1.0
sleep 2

# Landscape: the chrome follows the phone; play asks for portrait.
adb shell settings put system user_rotation 1
sleep 2
for name in library make-chart-made settings; do
  launch "$name"; sleep 3; shot "landscape-$name"
done
adb shell settings put system user_rotation 0
sleep 2

# What TalkBack reads: the UI hierarchy of a few screens.
for name in library make-chart-made play-paused results; do
  launch "$name"
  if [ "$name" = play-paused ]; then sleep 15; else sleep 3; fi
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 && adb pull /sdcard/window.xml "$OUT/window-$name.xml" >/dev/null || echo "no dump for $name"
done

# The playfield preview in motion, 15 seconds.
launch play-preview
sleep 1
adb shell screenrecord --time-limit 15 /sdcard/playfield-preview.mp4
adb pull /sdcard/playfield-preview.mp4 "$OUT/playfield-preview.mp4" >/dev/null
echo playfield-preview.mp4
adb shell dumpsys SurfaceFlinger --list >"$OUT/surfaceflinger-layers.txt" || true

adb shell am force-stop "$PKG"
