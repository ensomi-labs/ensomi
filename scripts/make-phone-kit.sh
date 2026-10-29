#!/bin/sh
# Assembles the kit a tester gets for the phone checks (docs/phone-checks.md), on a machine that can reach
# huggingface.co, with curl, ffmpeg (libmp3lame) and shasum or sha256sum:
#
#   scripts/make-phone-kit.sh KIT_DIR [SONG...]
#
# KIT_DIR/ then holds phone-checks.sh and the runbook; the APKs from app/build/outputs (build them first with
# ./gradlew assembleDebug assembleDebugAndroidTest assembleChecks); clicks/ from make-click-tracks.sh; songs/ with the
# SONG files given (full-length songs the team may share); and model/ensomi-r2/, the R2 package at the pinned tag,
# every file checked against the manifest and the manifest against the app's pin. Zip KIT_DIR and hand it over.
set -eu
KIT=${1:?usage: scripts/make-phone-kit.sh KIT_DIR [SONG...]}
shift
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
SOURCE=$REPO/app/src/main/kotlin/io/ensomi/android/model/ModelSource.kt
REVISION=$(sed -n 's/.*PINNED_REVISION = "\(.*\)".*/\1/p' "$SOURCE")
PIN=$(sed -n 's/.*PINNED_MANIFEST_SHA256 = "\(.*\)".*/\1/p' "$SOURCE")
BASE=https://huggingface.co/sed-i/ensomi-r2/resolve/$REVISION/onnx
sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

mkdir -p "$KIT/model/ensomi-r2" "$KIT/clicks" "$KIT/songs"
cp "$HERE/phone-checks.sh" "$REPO/docs/phone-checks.md" "$KIT/"
for apk in debug/app-debug.apk androidTest/debug/app-debug-androidTest.apk checks/app-checks.apk; do
  if [ -f "$REPO/app/build/outputs/apk/$apk" ]; then cp "$REPO/app/build/outputs/apk/$apk" "$KIT/"; else echo "missing app/build/outputs/apk/$apk"; fi
done

MODEL=$KIT/model/ensomi-r2
curl -fsSL -o "$MODEL/ensomi-package.json" "$BASE/ensomi-package.json"
[ "$(sha "$MODEL/ensomi-package.json")" = "$PIN" ] || { echo "The manifest at $REVISION is not the one the app pins"; exit 1; }
# Each file's path, size and SHA-256 from the manifest, one per line.
tr -d '\n ' <"$MODEL/ensomi-package.json" | tr '}' '\n' | sed -n 's/.*"path":"\([^"]*\)","size":\([0-9]*\),"sha256":"\([0-9a-f]*\)".*/\1 \2 \3/p' |
  while read -r path size hash; do
    mkdir -p "$MODEL/$(dirname "$path")"
    if [ ! -f "$MODEL/$path" ] || [ "$(sha "$MODEL/$path")" != "$hash" ]; then
      echo "fetching $path ($size bytes)"
      curl -fsSL -o "$MODEL/$path" "$BASE/$path"
    fi
    [ "$(sha "$MODEL/$path")" = "$hash" ] || { echo "$path does not match the manifest"; exit 1; }
  done
echo "model: $(ls "$MODEL" | wc -l | tr -d ' ') files, manifest $PIN"

"$HERE/make-click-tracks.sh" "$KIT/clicks" >/dev/null
for song in "$@"; do cp "$song" "$KIT/songs/"; done
echo "kit ready in $KIT: zip it and hand it over"
