#!/usr/bin/env bash
# Captures the prototype's screens on the one booted emulator or device, with the debug APK installed:
# every scenario of app/src/main/kotlin/io/ensomi/android/prototype/Prototype.kt in light and dark mode,
# the game at each lean and in both orientations, a few screens at 200 % font size and with the phone in landscape,
# UI hierarchy dumps, the render thread's frame log and a Perfetto FrameTimeline trace at each lean, a recording of the
# playfield preview (landscape, the default lean), and a real game on the app's own stores.
#
#   scripts/capture-prototype.sh OUT_DIR [GROUP...]
#   REAL_SONG="Song title" scripts/capture-prototype.sh OUT_DIR real
#
# GROUP is any of screens, game, lean, font, landscape, dumps, pacing, recording, real; all of them when none is given.
# The real group plays REAL_SONG, a song in the library with a chart, by the autoplay (a debug launch extra), with the
# settings the app has; it is skipped when REAL_SONG is not set.
set -euo pipefail

OUT=${1:?usage: scripts/capture-prototype.sh OUT_DIR [GROUP...]}
shift
GROUPS_WANTED=${*:-screens game lean font landscape dumps pacing recording real}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
PKG=io.ensomi.android
ACTIVITY=$PKG/.MainActivity

launch() { # scenario [extra am args...]
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$ACTIVITY" --es prototype "$@" >/dev/null
}
shot() { adb exec-out screencap -p >"$OUT/$1.png"; echo "$1.png"; }
wanted() { case " $GROUPS_WANTED " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
swipe_up() { adb shell input swipe 540 1900 540 700 500; sleep 1.5; }
# The results keep the game's landscape: scroll their left column.
swipe_up_landscape() { adb shell input swipe 700 700 700 250 500; sleep 1.5; }

# scenario:seconds to wait before the screenshot
SCENARIOS=(
  first-run:3 downloading:3 download-stopped:3 no-space:3 library:3 library-missing:3
  make-chart:3 make-chart-sections:3 make-chart-section-problem:3 make-chart-no-model:3
  make-chart-making:3 make-chart-made:3 make-chart-stopped:3 make-chart-failed:3
  results:3 settings:3 settings-playfield:4 settings-playfield-portrait:4 settings-downloading:3
  settings-update-pending:3 notices:3 notice-text:4
  calibration-sound:3 calibration-picture:5 diagnostics:6
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

if wanted screens; then
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
      make-chart-sections|make-chart-made|calibration-sound)
        swipe_up; shot "$mode-$name-scrolled" ;;
      results)
        swipe_up_landscape; shot "$mode-$name-scrolled" ;;
      settings)
        swipe_up; shot "$mode-$name-2"; swipe_up; shot "$mode-$name-3"; swipe_up; shot "$mode-$name-4" ;;
      settings-downloading|settings-update-pending)
        swipe_up; swipe_up; shot "$mode-$name-model" ;;
    esac
  done
  # The wallpaper's colours instead of the brand's, for comparison.
  launch library --ez dynamicColor true; sleep 3; shot "$mode-library-dynamic"
  launch make-chart-made --ez dynamicColor true; sleep 3; shot "$mode-make-chart-made-dynamic"
done
adb shell cmd uimode night no
sleep 2
fi

if wanted game; then
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
# Results in both modes, so the game screens' system bars can be checked.
for mode in light dark; do
  if [ "$mode" = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  sleep 2
  launch results; sleep 3; shot "$mode-results"; swipe_up_landscape; shot "$mode-results-scrolled"
done
adb shell cmd uimode night no
fi

if wanted lean; then
# The lanes at no lean, the default and the most, in landscape (the phones' default) and portrait; then the settings'
# live preview at no lean and the most, in both modes. Each game is shot once notes are falling.
for name in play-flat play play-lean-max play-portrait play-portrait-lean; do
  launch "$name"; sleep 8; shot "lean-$name"
done
for mode in light dark; do
  if [ "$mode" = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  sleep 2
  for name in settings-playfield-flat settings-playfield settings-playfield-max; do
    launch "$name"; sleep 4; shot "$mode-$name"
  done
done
adb shell cmd uimode night no
sleep 2
fi

if wanted font; then
# Larger text: Android's font size at 200 %.
adb shell settings put system font_scale 2.0
sleep 2
for name in library make-chart make-chart-made settings results; do
  launch "$name"; sleep 3; shot "font200-$name"
done
adb shell settings put system font_scale 1.0
sleep 2
fi

if wanted landscape; then
# The phone turned to landscape: the chrome follows it. (Play turns it to landscape by itself.)
adb shell settings put system user_rotation 1
sleep 2
for name in library make-chart-made settings settings-playfield calibration-sound calibration-picture; do
  launch "$name"; sleep 3; shot "landscape-$name"
done
adb shell settings put system user_rotation 0
sleep 2
fi

if wanted dumps; then
# What TalkBack reads: the UI hierarchy of every kind of screen.
for name in first-run library make-chart make-chart-made make-chart-failed play-paused results settings notices \
  notice-text calibration-sound calibration-picture diagnostics; do
  launch "$name"
  if [ "$name" = play-paused ]; then sleep 15; else sleep 3; fi
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 && adb pull /sdcard/window.xml "$OUT/window-$name.xml" >/dev/null || echo "no dump for $name"
done
fi

if wanted pacing; then
# The render thread's own frame log and a 15-second Perfetto FrameTimeline trace of the game at each lean, landscape
# and portrait, one after the other under the same conditions: pacing-NAME.txt, pacing-NAME.pftrace (and its summary).
for name in play-flat play play-lean-max play-portrait; do
  adb logcat -c
  launch "$name"; sleep 4
  "$HERE/capture-frame-timeline.sh" "$OUT" "pacing-$name" 15
  sleep 3
  adb logcat -d -s EnsomiPlayfield:I >"$OUT/pacing-$name.txt" || true
  echo "pacing-$name.txt"
done
adb shell dumpsys SurfaceFlinger >"$OUT/surfaceflinger.txt" || true
fi

if wanted recording; then
# The playfield preview in motion, 15 seconds, in landscape at the default lean, at a size the emulator's encoder
# keeps up with; then the render thread's own frame log.
adb logcat -c
launch play-preview
sleep 2
adb shell screenrecord --time-limit 15 --size 1616x720 --bit-rate 8000000 /sdcard/playfield-preview.mp4
adb pull /sdcard/playfield-preview.mp4 "$OUT/playfield-preview.mp4" >/dev/null
echo playfield-preview.mp4
adb logcat -d -s EnsomiPlayfield:I >"$OUT/frame-stats.txt" || true
adb shell dumpsys SurfaceFlinger --list >"$OUT/surfaceflinger-layers.txt" || true
fi

if wanted real && [ -n "${REAL_SONG:-}" ]; then
# A real game: the app's own stores, the song's stored chart on its own sound, played by the autoplay, to the results.
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -W -n "$ACTIVITY" --ez autoplay true >/dev/null
sleep 3; shot real-library
tap_text "$REAL_SONG"
sleep 6; shot real-song
tap_text "Play"
sleep 10; shot real-play-1
sleep 15; shot real-play-2
# The results come when the song ends: look for Play again every 10 seconds, for up to 10 minutes.
for _ in $(seq 60); do
  sleep 10
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || continue
  if adb shell cat /sdcard/ui.xml | grep -q 'text="Play again"'; then break; fi
done
shot real-results
adb logcat -d -s EnsomiPlay:I EnsomiAudio:I EnsomiPlayfield:I >"$OUT/real-game.txt" || true
echo real-game.txt
fi

adb shell am force-stop "$PKG"
