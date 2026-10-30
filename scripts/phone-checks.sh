#!/bin/sh
# Runs Ensomi's phone checks on the one connected Android phone and packs everything they found into one archive to
# send back. Needs only adb (Android platform-tools) and a POSIX shell: macOS, Linux, or Git Bash on Windows.
# The runbook is docs/phone-checks.md.
#
#   sh phone-checks.sh [options]
#
#   -k DIR     the kit: the APKs, clicks/, songs/ and model/ensomi-r2/ (default: this script's directory)
#   -o DIR     where the results go (default: the current directory)
#   -s SERIAL  the phone, when more than one is connected (adb devices)
#   -g GROUPS  only these groups, comma-separated (default: all, in this order):
#              runtime,model,decode,generation,determinism,audio,frames,lifecycle
#              and, on its own after the checklist's manual checks, manual (the app's logs and its last game's files)
#   -l LABEL   a name for this run's output, such as speaker, wired or bt (the audio group); recorded in the results
#   -n         do not install the APKs at the start (they are installed already); the frames group still installs the
#              checks build and then the debug build again
#   -t SECONDS the time limit of each check (default: 1800)
#
# Steps: install the debug app and its checks with adb install (HyperOS asks on the phone each time); make sure the
# chart model is installed, by the app's own download from Hugging Face first and, if that fails, by "Install from
# files" with the kit's model/ensomi-r2 copied to the phone's Download folder; push the click tracks and songs;
# run each group of checks in its own process with am instrument, up to the time limit; for the frames group also with
# the checks build, which is not debuggable; pull the results, logcat and the device's facts; pack them into
# OUT/<name>.tar.gz.
set -u

# Git Bash would turn /data/... and /sdcard/... arguments into Windows paths.
MSYS_NO_PATHCONV=1
MSYS2_ARG_CONV_EXCL='*'
export MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL

PKG=io.ensomi.android
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
CHECKS=io.ensomi.android.checks
PUSHED=/data/local/tmp/ensomi-checks
RESULTS=/sdcard/Android/media/$PKG/phone-checks
# The pinned package's manifest SHA-256 (ModelSource.PINNED_MANIFEST_SHA256).
PINNED_MANIFEST=6e05317427cc24f3d46c0e8dcdd7a44078954437129c60f8797acb80bea6a1fb
ALL_GROUPS=runtime,model,decode,generation,determinism,audio,frames,lifecycle
# One check's time limit in seconds (-t): a hang costs this much, and the run still reaches "Collecting".
CHECK_LIMIT=1800

HERE=$(cd "$(dirname "$0")" && pwd)
KIT=$HERE
OUT_PARENT=$(pwd)
WANT=$ALL_GROUPS
LABEL=
INSTALL=yes
while getopts k:o:s:g:l:t:nh opt; do
  case $opt in
    k) KIT=$OPTARG ;;
    o) OUT_PARENT=$OPTARG ;;
    s) ANDROID_SERIAL=$OPTARG; export ANDROID_SERIAL ;;
    g) WANT=$OPTARG ;;
    l) LABEL=$OPTARG ;;
    t) CHECK_LIMIT=$OPTARG ;;
    n) INSTALL=no ;;
    *) sed -n '2,24p' "$0"; exit 2 ;;
  esac
done

say() { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
die() { printf 'Stopped: %s\n' "$*" >&2; exit 1; }
wanted() { case ",$WANT," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }
# adb shell output without the carriage returns old adb versions add.
ash() { adb shell "$@" 2>&1 | tr -d '\r'; }
case $CHECK_LIMIT in '' | 0* | *[!0-9]*) die "-t needs a whole number of seconds above zero." ;; esac

command -v adb >/dev/null 2>&1 || die "adb is not on PATH. Install Android platform-tools and try again."
count=$(adb devices | tr -d '\r' | awk 'NR > 1 && $2 == "device"' | wc -l | tr -d ' ')
if [ -z "${ANDROID_SERIAL:-}" ] && [ "$count" != 1 ]; then
  adb devices
  die "connect exactly one phone with USB debugging allowed (found $count), or name one with -s SERIAL."
fi
adb get-state >/dev/null 2>&1 || die "the phone is not ready: allow USB debugging on it (adb devices shows 'unauthorized')."

find_apk() { # name, then places to look
  name=$1; shift
  for dir in "$@"; do
    [ -f "$dir/$name" ] && { printf '%s\n' "$dir/$name"; return 0; }
  done
  return 1
}
REPO=$(cd "$HERE/.." && pwd)
APP=$(find_apk app-debug.apk "$KIT" "$KIT/apk" "$REPO/app/build/outputs/apk/debug") || APP=
TEST=$(find_apk app-debug-androidTest.apk "$KIT" "$KIT/apk" "$REPO/app/build/outputs/apk/androidTest/debug") || TEST=
CHECKS_APK=$(find_apk app-checks.apk "$KIT" "$KIT/apk" "$REPO/app/build/outputs/apk/checks") || CHECKS_APK=

MODEL_NAME=$(ash getprop ro.product.model | tr -c 'A-Za-z0-9._\n-' '_')
NAME=phone-checks-${MODEL_NAME:-phone}-$(date +%Y%m%d-%H%M%S)
OUT=$OUT_PARENT/$NAME
mkdir -p "$OUT" || die "cannot write to $OUT_PARENT"
LOG=$OUT/script.log
exec 3>&1
log() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" >>"$LOG"; }
# say, and also into the archive's log: whoever reads the archive never sees the console.
note() { say "$*"; log "$*"; }
say "Results go to $OUT"
log "kit=$KIT groups=$WANT label=$LABEL install=$INSTALL limit=$CHECK_LIMIT app=$APP test=$TEST checks=$CHECKS_APK"

# The running am instrument client: in the background it ignores Ctrl-C, so stopping the script has to stop it too.
CLIENT=
# Keep the screen on while plugged in, so a long check is never paused by the screen going off; put back at the end.
restore() {
  [ -z "$CLIENT" ] || kill "$CLIENT" 2>/dev/null
  adb shell svc power stayon false >/dev/null 2>&1
}
trap restore EXIT
trap 'restore; exit 130' INT TERM

step "Device facts"
{
  for prop in ro.product.manufacturer ro.product.brand ro.product.model ro.product.device ro.product.name \
    ro.soc.manufacturer ro.soc.model ro.board.platform ro.hardware ro.build.fingerprint ro.build.display.id \
    ro.build.version.release ro.build.version.sdk ro.build.version.incremental ro.build.version.security_patch \
    ro.mi.os.version.name ro.mi.os.version.code ro.mi.os.version.incremental ro.miui.ui.version.name \
    ro.product.cpu.abilist ro.config.low_ram ro.vendor.build.fingerprint; do
    printf '%s=%s\n' "$prop" "$(ash getprop "$prop")"
  done
  printf 'page_size=%s\n' "$(ash getconf PAGE_SIZE)"
  printf 'wm_size=%s\n' "$(ash wm size | tr '\n' ' ')"
  printf 'wm_density=%s\n' "$(ash wm density | tr '\n' ' ')"
} >"$OUT/device.txt"
ash getprop >"$OUT/getprop.txt"
ash dumpsys display >"$OUT/display.txt"
ash dumpsys SurfaceFlinger >"$OUT/surfaceflinger.txt"
ash settings list system | grep -i -E 'refresh|fps|frame' >"$OUT/refresh-settings.txt"
ash settings list secure | grep -i -E 'refresh|fps|frame' >>"$OUT/refresh-settings.txt"
ash cat /proc/meminfo >"$OUT/meminfo.txt"
ash dumpsys battery >"$OUT/battery.txt"
ash dumpsys thermalservice >"$OUT/thermal.txt"
ash dumpsys power | grep -i -E 'battery ?saver|low ?power|mSettingBatterySaver' >"$OUT/power.txt"
ash dumpsys audio >"$OUT/audio.txt"
grep -E 'model=|device=|soc.model|fingerprint=|version.release|mi.os.version.incremental' "$OUT/device.txt"
# Non-empty on HyperOS or MIUI. A USB gap just now would count too, which costs only a needless grant.
XIAOMI_OS=$(grep -E '^ro\.(mi\.os\.version\.name|miui\.ui\.version\.name)=.' "$OUT/device.txt")
# A chord lands three fingers, and HyperOS's three-finger gesture then takes them from the app. Only read here: the
# automated checks do not play by hand, and the setting is the tester's.
if [ -n "$XIAOMI_OS" ]; then
  three_gesture=$(ash settings get system enable_three_gesture)
  printf 'enable_three_gesture=%s\n' "$three_gesture" >>"$OUT/device.txt"
  [ "$three_gesture" != 1 ] || note "HyperOS's three-finger gesture is on (enable_three_gesture=1): hand play will lose chords. See the runbook."
fi

# The USB link can drop for a few seconds, and adb then fails every command: wait for the phone rather than fail too.
wait_device() {
  [ "$(adb get-state 2>/dev/null | tr -d '\r')" = device ] && return 0
  note "  the phone is not connected: waiting for it to come back"
  adb wait-for-device
  note "  the phone is back"
}

# On failure leaves the reason in INSTALL_ERROR and returns non-zero: the caller decides whether that stops the run.
install_apk() { # file, what
  for try in 1 2 3; do
    wait_device
    # HyperOS's prompt closes itself unanswered, so it has to come up on a lit screen.
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    say "Installing $2. Look at the phone now: HyperOS and some other systems ask you to allow each install."
    result=$(adb install -r -t "$1" 2>&1 | tr -d '\r')
    printf '%s\n' "$result" >>"$LOG"
    case $result in
      *Success*) say "  installed"; return 0 ;;
      *INSTALL_FAILED_USER_RESTRICTED*)
        INSTALL_ERROR="the phone refused the install three times: its prompt went unanswered, or 'Install via USB' is off. On HyperOS that toggle is in Settings > Additional settings > Developer options (it asks for a Xiaomi account; a phone may also need a SIM). Next time, watch for the prompt and tap Install."
        [ "$try" = 3 ] || note "  the install prompt went unanswered; it closes itself after a few seconds. Asking again ($((try + 1)) of 3)." ;;
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
        INSTALL_ERROR="another build of Ensomi with a different signature is installed. Uninstall it (adb uninstall $PKG; this removes its model) and run this again."
        return 1 ;;
      *) INSTALL_ERROR="adb install $1 failed: $result"; return 1 ;;
    esac
  done
  return 1
}

# Ahead of the installs, so the screen stays on for their prompts.
adb shell svc power stayon usb >/dev/null 2>&1
if [ "$INSTALL" = yes ]; then
  step "Installing"
  [ -n "$APP" ] || die "app-debug.apk is not in $KIT"
  [ -n "$TEST" ] || die "app-debug-androidTest.apk is not in $KIT"
  install_apk "$APP" "the app (debug build)" || die "$INSTALL_ERROR"
  install_apk "$TEST" "its checks" || die "$INSTALL_ERROR"
fi
# Room for a whole check's log (and a manual game's) in logcat's buffer.
adb logcat -G 16M >/dev/null 2>&1
adb shell pm list instrumentation | tr -d '\r' | grep -q "$PKG.test" || die "the checks are not installed (pm list instrumentation)"

# The installed model's record, or nothing.
model_record() { ash run-as $PKG cat no_backup/models/ensomi-r2/ensomi-install.json | grep manifest_sha256; }

# Waits for the app's install log (EnsomiModelInstall) to report SOURCE's outcome; prints installed or stopped.
wait_install() { # source, seconds
  waited=0
  while [ "$waited" -lt "$2" ]; do
    lines=$(adb logcat -d -v raw -s EnsomiModelInstall:I 2>/dev/null | tr -d '\r' | grep "\"source\":\"$1\"")
    case $lines in
      *'"result":"installed"'*) printf '%s\n' "$lines" >>"$OUT/model-install.txt"; echo installed; return 0 ;;
      *'"result":"stopped"'* | *'"result":"cancelled"'*)
        printf '%s\n' "$lines" >>"$OUT/model-install.txt"
        # The player may try again from the app: only a fresh outcome ends the wait for files.
        if [ "$1" = huggingface ]; then echo stopped; return 0; fi
        printf '%s\n' "$lines" | grep -E '"result":"(stopped|cancelled)"' | tail -1 | sed 's/^/  the install stopped: /' >&3
        adb logcat -c ;;
    esac
    sleep 5
    waited=$((waited + 5))
    [ $((waited % 60)) -eq 0 ] && say "  still waiting ($((waited / 60)) min)" >&3
  done
  echo timeout
}

if wanted model || wanted generation || wanted determinism; then
  step "Chart model"
  record=$(model_record)
  case $record in
    *"$PINNED_MANIFEST"*)
      say "The pinned model is installed already."
      printf 'path=present\n%s\n' "$record" >>"$OUT/model-install.txt" ;;
    *)
      say "Downloading the model from Hugging Face in the app (120 MB). Keep the phone unlocked."
      adb logcat -c
      adb shell am start -n $PKG/.MainActivity --ez download-model true >/dev/null 2>&1
      outcome=$(wait_install huggingface 1800)
      if [ "$outcome" = installed ]; then
        say "Downloaded and installed from Hugging Face."
        printf 'path=huggingface\n' >>"$OUT/model-install.txt"
      else
        say "The download from Hugging Face did not finish ($outcome)."
        tail -1 "$OUT/model-install.txt" 2>/dev/null | sed 's/^/  /'
        files=$KIT/model/ensomi-r2
        [ -f "$files/ensomi-package.json" ] || die "the kit has no model/ensomi-r2/ensomi-package.json to install from files. Ask for the model files (docs/phone-checks.md) and run this again."
        say "Copying the kit's model files to the phone's Download/ensomi-r2 folder."
        adb shell rm -rf /sdcard/Download/ensomi-r2 >/dev/null 2>&1
        adb push "$files" /sdcard/Download/ >>"$LOG" 2>&1 || die "could not copy the model files to the phone"
        adb logcat -c
        adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1
        say ""
        say "Now on the phone, in Ensomi: tap 'Install from files', open the Download folder, open 'ensomi-r2',"
        say "tap 'Use this folder' and then 'Allow'. Ensomi copies and checks the files; this waits until it is done."
        outcome=$(wait_install files 1800)
        [ "$outcome" = installed ] || die "the model was not installed from files ($outcome). See $OUT/model-install.txt."
        say "Installed from files."
        printf 'path=files\n' >>"$OUT/model-install.txt"
        adb shell rm -rf /sdcard/Download/ensomi-r2 >/dev/null 2>&1
      fi
      model_record >>"$OUT/model-install.txt" ;;
  esac
  adb shell am force-stop $PKG
fi

if wanted decode || wanted generation || wanted determinism; then
step "Test data"
ash rm -rf $PUSHED >/dev/null
ash mkdir -p $PUSHED/clicks $PUSHED/songs >/dev/null
SONGS=
n=0
for kind in clicks songs; do
  [ -d "$KIT/$kind" ] || continue
  for file in "$KIT/$kind"/*; do
    [ -f "$file" ] || continue
    # Names without spaces, quotes or other scripts' letters: the checks read them through a shell without quoting.
    safe=$(basename "$file" | tr -c 'A-Za-z0-9._\n-' '_')
    if [ "$kind" = songs ]; then
      n=$((n + 1))
      safe="song$n-$safe"
      SONGS="$SONGS $safe"
      printf '%s = %s\n' "$safe" "$(basename "$file")" >>"$OUT/songs.txt"
    fi
    adb push "$file" "$PUSHED/$kind/$safe" >>"$LOG" 2>&1 || die "could not push $file"
  done
done
say "click tracks: $(ash ls $PUSHED/clicks | wc -l | tr -d ' '), songs:${SONGS:- none (the generation checks make a synthetic song)}"
fi

# Any group of checks: the results of an earlier run on the phone go first (they were pulled then).
SUITE=no
for group in runtime model decode generation determinism audio frames lifecycle; do wanted $group && SUITE=yes; done
[ "$SUITE" = yes ] && ash rm -rf "$RESULTS" >/dev/null
FAILED=
LOST=
TIMED_OUT=
# am instrument ends with INSTRUMENTATION_CODE or says why it could not run. Output with neither stopped at the
# connection: adb's own error, or nothing more at all.
connection_lost() { ! grep -q -E 'INSTRUMENTATION_(CODE:|FAILED|ABORTED)|Exception' "$1"; }
# HyperOS refuses a check's launch of the app, which then hangs, without this app-op; replacing the APK loses it. So
# every check grants it, whichever way the APKs got there. appops get lists every op: only 10021's line counts.
grant_launch() {
  [ -n "$XIAOMI_OS" ] || return 0
  adb shell appops set $PKG 10021 allow >/dev/null 2>&1
  mode=$(ash appops get $PKG | grep 10021)
  case $mode in
    *': allow'*) ;;
    *) note "    MIUIOP(10021) does not read allow (${mode:-nothing}): HyperOS may refuse this check's launch of the app" ;;
  esac
}
# In the background against the clock: macOS has no timeout command. Non-zero at the limit. TERM, then KILL: a client
# that stayed would hold up the wait, and the limit with it. The braces keep the shell's "Terminated" off the console.
instrument() { # output file, then am instrument's arguments
  file=$1; shift
  adb shell am instrument -w -r "$@" $RUNNER >"$file" 2>&1 &
  CLIENT=$!
  waited=0
  while kill -0 "$CLIENT" 2>/dev/null; do
    if [ "$waited" -ge "$CHECK_LIMIT" ]; then
      { kill "$CLIENT"; sleep 1; kill -9 "$CLIENT"; wait "$CLIENT"; } 2>/dev/null
      CLIENT=
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  wait "$CLIENT"
  CLIENT=
}
run_check() { # name, class, then am instrument -e pairs
  name=$1; class=$2; shift 2
  wait_device
  say "  $name"
  adb logcat -c
  grant_launch
  limit_reached=no
  if ! instrument "$OUT/instrument-$name.txt" -e class "$CHECKS.$class" "$@"; then
    limit_reached=yes
    adb shell am force-stop $PKG >/dev/null 2>&1
  fi
  adb logcat -d -v threadtime >"$OUT/logcat-$name.txt" 2>&1
  if [ "$limit_reached" = yes ]; then
    note "    timed out after $CHECK_LIMIT s: stopped, going on. Run its group again with -g, or longer with -t (logcat-$name.txt)"
    TIMED_OUT="$TIMED_OUT $name"
  elif grep -q 'OK (' "$OUT/instrument-$name.txt"; then
    if grep -q -E 'INSTRUMENTATION_STATUS_CODE: -(3|4)' "$OUT/instrument-$name.txt"; then say "    skipped (see the results)"; else say "    passed"; fi
  elif connection_lost "$OUT/instrument-$name.txt"; then
    note "    connection lost during this check: not a failed check. Run its group again with -g (instrument-$name.txt)"
    LOST="$LOST $name"
  else
    say "    did not pass: see instrument-$name.txt"
    FAILED="$FAILED $name"
  fi
}
label_args() { [ -n "$LABEL" ] && printf '%s %s %s' -e label "$LABEL"; }

step "Checks"
wanted runtime && run_check runtime RuntimeCheck $(label_args)
wanted model && run_check model ModelCheck $(label_args)
wanted decode && run_check decode ClickTrackDecodeCheck $(label_args)
if wanted generation; then
  for song in ${SONGS:-synthetic}; do
    song_arg=
    [ "$song" = synthetic ] || song_arg="-e song $song"
    # One process per run, so the peak memory of one does not count in the next.
    for low in off on; do
      run_check "generation-$song-lowmemory-$low" GenerationCheck -e lowMemory $low $song_arg $(label_args)
    done
  done
fi
if wanted determinism; then
  first=$(printf '%s\n' $SONGS | head -1)
  if [ -n "$first" ]; then run_check determinism DeterminismCheck -e song "$first" $(label_args)
  else run_check determinism DeterminismCheck $(label_args); fi
fi
if wanted audio; then
  say "  (the audio check plays quiet clicks for three minutes: leave the phone alone; its output is recorded)"
  run_check "audio${LABEL:+-$LABEL}" AudioClockCheck $(label_args)
fi
if wanted frames; then
  run_check frames-debug FramePacingCheck $(label_args)
  if [ -n "$CHECKS_APK" ]; then
    # The checks build is the same app, not debuggable: installed over the debug build it keeps the model. No install
    # here stops the run: what the groups found so far is only collected at the end.
    if install_apk "$CHECKS_APK" "the checks build (not debuggable) for frame pacing"; then
      run_check frames-checks-build FramePacingCheck $(label_args)
      if [ -n "$APP" ] && ! install_apk "$APP" "the debug build again"; then
        note "  The checks build stays installed. Leave this run going; before the checklist put the debug build back: adb install -r -t $APP"
        note "  Why: $INSTALL_ERROR"
        FAILED="$FAILED debug-reinstall (the checks build stays installed)"
      fi
    else
      note "  Skipping frame pacing in the checks build; the debug build is still installed. Leave this run going; for that pass run this again with -n -g frames after it."
      note "  Why: $INSTALL_ERROR"
      FAILED="$FAILED frames-checks-build (not installed, skipped)"
    fi
  else
    say "  app-checks.apk is not in the kit: frame pacing ran in the debug build only"
  fi
fi
if wanted lifecycle; then
  say "  (the lifecycle check presses Home, Recents and the power key by itself: leave the phone alone)"
  run_check lifecycle LifecycleCheck $(label_args)
fi

if wanted manual; then
  step "The app's logs and its last game"
  mkdir -p "$OUT/manual"
  for file in clock-log.csv frame-log.csv touch-log.csv settings.json library.json; do
    ash run-as $PKG cat files/$file >"$OUT/manual/$file"
    # A file the app has not written (no game yet) is left out rather than kept as an error message.
    if grep -q -E 'No such file|run-as:' "$OUT/manual/$file"; then rm -f "$OUT/manual/$file"; fi
  done
  adb logcat -d -v threadtime -s Ensomi:I EnsomiPlay:I EnsomiAudio:I EnsomiPlayfield:I EnsomiCalibration:I \
    EnsomiCharts:I EnsomiModel:I EnsomiModelInstall:I EnsomiDiagnostics:I EnsomiDecodeCheck:I >"$OUT/manual/logcat.txt" 2>&1
  say "  collected the logs, the last game's clock, frame and touch logs, settings.json and library.json"
fi

wait_device
step "Collecting"
PULL_FAILED=
if [ "$SUITE" = yes ]; then
  if ! adb pull "$RESULTS" "$OUT/results" >>"$LOG" 2>&1; then
    wait_device
    # Into the directory a broken pull left, adb would put the results one level down.
    rm -rf "$OUT/results"
    if ! adb pull "$RESULTS" "$OUT/results" >>"$LOG" 2>&1; then
      # Only the phone's own word means there is nothing: the next run deletes whatever is left there.
      case $(ash ls -d "$RESULTS") in
        *'No such file'*) say "  no result files on the phone" ;;
        *)
          note "  The pull failed twice, so the results are still on the phone at $RESULTS (a results folder here holds only part of them). Running this script again deletes them: first get them with adb pull $RESULTS"
          PULL_FAILED="; the pull failed: the results are still on the phone" ;;
      esac
    fi
  fi
fi
adb shell am force-stop $PKG
cp "$0" "$OUT/" 2>/dev/null
OUTCOME=${FAILED:+; did not pass:$FAILED}${LOST:+; connection lost during:$LOST}${TIMED_OUT:+; timed out:$TIMED_OUT}$PULL_FAILED
if [ -f "$OUT/results/results.jsonl" ]; then
  grep '"ok":false' "$OUT/results/results.jsonl" >"$OUT/not-ok.jsonl"
  bad=$(wc -l <"$OUT/not-ok.jsonl" | tr -d ' ')
  total=$(wc -l <"$OUT/results/results.jsonl" | tr -d ' ')
  note "$total results, $bad not ok$OUTCOME"
elif [ "$SUITE" = yes ]; then
  note "no results.jsonl was pulled$OUTCOME"
fi
# COPYFILE_DISABLE keeps macOS tar from adding ._ metadata files.
(cd "$OUT_PARENT" && COPYFILE_DISABLE=1 tar -czf "$NAME.tar.gz" "$NAME") || die "could not pack $OUT"
say ""
say "Done. Send back this file: $OUT_PARENT/$NAME.tar.gz"
