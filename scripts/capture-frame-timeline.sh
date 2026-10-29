#!/usr/bin/env bash
# Records a Perfetto trace of whatever plays on the one booted emulator or device: SurfaceFlinger's FrameTimeline
# (each frame's expected and actual presentation and its jank, per layer), the graphics and view atrace categories,
# and the app's own trace sections (EnsomiFrame, EnsomiRuntime, EnsomiDraw on the render thread). Start the game
# first; the trace covers the next SECONDS.
#
#   scripts/capture-frame-timeline.sh OUT_DIR NAME [SECONDS]
#
# Writes OUT_DIR/NAME.pftrace. With Perfetto's trace_processor on the PATH (trace_processor or trace_processor_shell),
# or the `perfetto` Python package, it also writes OUT_DIR/NAME-frametimeline.txt: the app's frames by jank type and
# their actual durations, from the queries at the end of this script. Open the trace at https://ui.perfetto.dev.
set -euo pipefail

OUT=${1:?usage: scripts/capture-frame-timeline.sh OUT_DIR NAME [SECONDS]}
NAME=${2:?usage: scripts/capture-frame-timeline.sh OUT_DIR NAME [SECONDS]}
SECONDS_TO_TRACE=${3:-15}
PKG=io.ensomi.android
mkdir -p "$OUT"
DEVICE_TRACE=/data/misc/perfetto-traces/ensomi-$NAME.pftrace

CONFIG=$(cat <<EOF
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
buffers { size_kb: 4096 fill_policy: RING_BUFFER }
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 0 } }
data_sources {
  config {
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "input"
      atrace_apps: "$PKG"
    }
  }
}
data_sources { config { name: "linux.process_stats" target_buffer: 1 } }
duration_ms: $((SECONDS_TO_TRACE * 1000))
EOF
)

echo "$CONFIG" | adb shell perfetto --txt -c - -o "$DEVICE_TRACE" >/dev/null
adb pull "$DEVICE_TRACE" "$OUT/$NAME.pftrace" >/dev/null
adb shell rm -f "$DEVICE_TRACE"
echo "$NAME.pftrace"

# The app's frames on its playfield layer (the SurfaceView's), by jank type, and how long they took against the
# frame interval; then the render thread's own sections.
QUERIES=$(cat <<'EOF'
SELECT a.layer_name, a.jank_type, COUNT(*) AS frames, ROUND(AVG(a.dur) / 1e6, 2) AS avg_ms, ROUND(MAX(a.dur) / 1e6, 2) AS max_ms
FROM actual_frame_timeline_slice a JOIN process p USING (upid)
WHERE p.name = 'io.ensomi.android'
GROUP BY a.layer_name, a.jank_type ORDER BY a.layer_name, frames DESC;
SELECT s.name, COUNT(*) AS slices, ROUND(AVG(s.dur) / 1e6, 3) AS avg_ms, ROUND(MAX(s.dur) / 1e6, 3) AS max_ms
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.name = 'EnsomiPlayfield' AND s.name IN ('EnsomiFrame', 'EnsomiRuntime', 'EnsomiDraw')
GROUP BY s.name;
EOF
)

SUMMARY="$OUT/$NAME-frametimeline.txt"
TP=$(command -v trace_processor || command -v trace_processor_shell || true)
if [ -n "$TP" ]; then
  printf '%s\n' "$QUERIES" >"$OUT/.frametimeline.sql"
  "$TP" -q "$OUT/.frametimeline.sql" "$OUT/$NAME.pftrace" >"$SUMMARY" 2>/dev/null || echo "trace_processor failed on $NAME" >"$SUMMARY"
  rm -f "$OUT/.frametimeline.sql"
  echo "$NAME-frametimeline.txt"
elif python3 -c 'import perfetto.trace_processor' 2>/dev/null; then
  QUERIES="$QUERIES" python3 - "$OUT/$NAME.pftrace" >"$SUMMARY" <<'PY'
import os, sys
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1])
for query in [q.strip() for q in os.environ["QUERIES"].split(";") if q.strip()]:
    print(tp.query(query).as_pandas_dataframe().to_string(index=False))
    print()
tp.close()
PY
  echo "$NAME-frametimeline.txt"
else
  echo "No trace_processor: open $NAME.pftrace at https://ui.perfetto.dev and run the queries in this script."
fi
