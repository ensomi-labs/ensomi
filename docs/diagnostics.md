# Crash reports (schema 2)

Diagnostics is a separate activity, available through the launcher icon's **Diagnostics** shortcut
and Settings → About → Diagnostics. It works without `MainActivity`, `AppStores`, an installed model,
or a native runtime load. The model recovery card opens the same activity through
`DiagnosticsActivity.intent(context)`. The screen explains the bundle before the person presses **Share diagnostics**.
The Android chooser receives a ZIP through a narrowly scoped FileProvider, with ClipData and a
read-only URI grant. Ensomi uploads nothing. **Copy JSON** still copies the summary.

`EnsomiApplication` starts recovery before an activity. It writes the run header synchronously,
chains Android's uncaught exception handler, then collects Android exit history on a background
thread. Collection completion (or an explicit failure) precedes the report and export. The optional
**Run runtime self-test** button is the only diagnostics action that loads native code. That test
never opens a model and cannot establish that ONNX session creation works.

## Journal API and durability

```kotlin
Journal.start(context: Context)
Journal.record(
    event: String,
    phase: String,
    detail: Map<String, String> = emptyMap(),
    durable: Boolean = true,
)
```

Both entry points are process-wide, thread-safe and never throw. Startup calls `start` once;
subsequent calls are harmless. Before startup or if storage fails, recording is best effort. A
durable call appends the pending batch and record with one write before returning. Process death preserves
those bytes in the kernel page cache. Only `model_open/begin` fsyncs the journal, on the runtime worker.
Non-durable events flush at the next durable event, activity `onStop`, snapshot or 8 KiB threshold.
Do not journal per-frame work or frequent download progress.

The two segments `journal-0.jsonl` (older) and `journal-1.jsonl` (newer) live in
`noBackupFilesDir/diagnostics/`, each at most 65,536 bytes. Startup only appends its header. Background
exit recovery rewrites segments only when pruning is needed, retaining the last eight runs plus runs
referenced by exits, within the byte budget. A busy run can evict old headers. A newline separates a torn
tail before the next append; parsing drops incomplete/invalid rows. Export includes the last three
retained runs and every retained run named by `exits[].run`.

Each JSON line has `v:1`, `t` (wall milliseconds), `up` (elapsed realtime milliseconds), `run`
(random 64-bit ID, sixteen hex digits), `seq` (increasing within the run), `ev`, `ph`, and `ctx`.
`run/start` has PID, process start elapsed realtime, inferred process start wall time, current
wall/elapsed times, version name/code, SDK integer and full SDK integer (API 36+), incremental
build, security patch, fingerprint and primary ABI. `uncaught_exception/crash` has exception class,
a suppressed message, up to five causes (class and suppressed message), and bounded class/method/line
frames without source filenames. Dotted class names are preserved.

Event/phase names must be fixed technical literals. Context must contain technical stage facts,
operation IDs, counts, outcomes or error classes; **never supply a song title, document name or
user input**, including in an event name. Reserved sensitive keys (title, song, filename, document,
message) suppress their values completely. Every `InstallEvents.record(event, phase, detail)` is forwarded to `Journal.record` with `durable = true`.
This includes download, manifest, verification, commit and guarded `model_open` boundaries.

## Summary JSON

`schema` is now `2`. Every schema-1 key remains:

- `collectedAt`; `app.applicationId/versionName/versionCode`;
- `device.manufacturer/model/socManufacturer/socModel/androidRelease/sdkInt/vendorOs/vendorOsProperties`;
- `cpu.cores[].index/maxFreqKHz`;
- `memory.pageSizeBytes/totalRamBytes/advertisedRamBytes`;
- `display.currentMode/supportedModes`, each with `id/width/height/refreshRateHz`;
- `audio.lowLatency/pro/outputSampleRateHz/outputFramesPerBuffer/unprocessedCapture`;
- `thermal.status/headroom10s`;
- `runtime.loaded/initMillis/noticeFiles/checkPackage/error`.

Additions:

- `cpu.features`: the first Features line's value from `/proc/cpuinfo`, or null. `hwcap` and
  `hwcap2`: unsigned hexadecimal values of arm64 auxiliary vector keys 16 and 26 from
  `/proc/self/auxv`, or null. `sme` and `sme2` test whole tokens in Features; false when absent.
- `runtime.identity`: the built AAR's SHA-256, computed by Gradle and passed from
  `BuildConfig.RUNTIME_AAR_SHA256` to `DiagnosticsCollector(context, runtimeIdentity)`. Reading identity must
  not load the runtime. Hex identities and tombstone build IDs are structured identifiers, exempt
  from free-text token suppression.
- `runtime.selfTest`: `call: "checkPackage(\"{}\")"`, `expected: "refused"`, `observed` and `pass`.
  Before pressing the button: `observed: "not_run"`, `pass: null`, `loaded: false` (not attempted,
  not a load failure). A `GeneratorException.Model` refusal produces `refused/true`. Unexpected
  acceptance gives `accepted/false`; an unexpected failure gives `error/false`. Unexpected failures remain in `runtime.error`. The expected
  refusal is labelled explicitly and is never displayed as an installed-model error.
- `exits`: up to 32 persisted previous-process records. `journal`: last three retained runs plus retained runs referenced by exits.
- `exitCollection`: `complete`, or `unavailable: <exception class>`. An empty list with a collection
  failure does not claim that no crashes occurred. `collectionErrors` maps failed device-fact sections
  to exception classes; those sections use unknown/zero defaults so a vendor API failure cannot
  prevent export of previous exits.

## Exit records and correlation

`exits.jsonl` uses `v:1`. Android's
[`getHistoricalProcessExitReasons`](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,int,int))
is called with the package name, PID 0 and count 0. Records are deduplicated by PID plus timestamp
and retained across subsequent starts, keeping the newest 32. Fields are:

- `pid`, `timestamp` (wall milliseconds), `reason` (REASON_* name), `reasonCode`, `status`, `signal`;
- `description`, `importance`, `pss` and `rss` (KiB; **0 means not sampled**);
- `processStateSummary` (decoded UTF-8 hint), `match`, `run`, `trace`.

Reasons 17 and 18 are named `REASON_MEMORY_LIMITER` and `REASON_ANOMALY` using numeric cases so
this remains source-compatible with SDKs without those constants. `status` is a signal only for
signaled/native-crash exits; other statuses are not mislabelled as signals.

A matching PID and summary run ID identify the run even after a long event-free period. Without a
summary ID, the exit must fall between process start and the last event plus ten minutes inclusive.
Exactly one candidate must remain. Ambiguous or absent headers stay unmatched. Persisted rows without
numeric timestamps are dropped. The ASCII `e1|<run8>|<stage>` process summary is at most 25 bytes:
`start`, `model_open`, `model_prepare`, `model_chart`, or `idle`. Repeated states are skipped and Android
throttling failures are caught. Android can omit this hint. An exit from 0.1.2 stays unmatched because
that build had no journal. Matched IDs are persisted; retained referenced journal runs are included in exports.

## Traces, privacy and limits

For native crashes and ANRs, read at most 8 MiB from
[`getTraceInputStream`](https://developer.android.com/reference/android/app/ApplicationExitInfo#getTraceInputStream()).
Null or unreadable streams produce `trace: "unavailable"`. Only the last three traces are kept;
older references become `expired`. Other reasons use `not_requested`.

Raw native tombstones contain memory, logs and private filenames. They are **never written to
disk or exported**. The trace file is a redacted text projection of the capped stream, using
[AOSP's tombstone schema](https://android.googlesource.com/platform/system/core/+/refs/heads/main/debuggerd/proto/tombstone.proto):
each trace starts with `projection=2`, then PID/TID, numeric signal/code/fault address, suppressed abort
message and thread name. The crashing thread comes first with all its frames, followed by other threads
with at most 16 frames each, within a 512 KiB UTF-8 output cap. Frames retain relative PCs, module basenames
(including both `base.apk!libX.so` and `base.apk!lib/arm64-v8a/libX.so`), build IDs and symbols. Symbols are exempt from token redaction and capped
at 512 characters. Memory, registers, logs, paths, arguments and unknown fields are omitted. ANRs retain
thread headers, stack lines and native module basenames/build IDs. Truncation or unsupported protobuf is
labelled. The raw input is only held in memory; offsets and build IDs support symbolization with the matching binary.

`Redact` replaces app/external paths with `<files>`, `<nobackup>`, `<cache>`, `<ext>` (other absolute
paths become `<path>`), content URIs with only their authority, and unapproved URLs with `<url>`.
Hugging Face/hf.co and their subdomains/CDNs retain scheme and host only. URL paths, queries,
credentials and fragments do not survive. Quoted text, e-mail addresses, long hex/base64 tokens and
recognisable filenames are suppressed. Every free-text field is bounded (default 1,024 characters;
journal event/phase 80, context keys 64, up to 32 values of 512; CPU Features 4,096; symbols 512).
Arbitrary exception prose is completely suppressed because a title need not have a filename suffix.

The ZIP contains `summary.json`, `journal.jsonl`, `exits.jsonl` and available redacted trace files.
It is created atomically at `cacheDir/diagnostics-export/ensomi-diagnostics.zip`; the next export
replaces it. Backup is disabled, and durable records are under no-backup storage.

## Debug fault checks

Only `src/debug` implements the triggers; release and checks use an empty hook. Both extras are
required on `DiagnosticsActivity`: `--ez diagnostics-faults true --es diagnostics-fault java`
throws on a coroutine, or `--es diagnostics-fault native` sends SIGABRT. These do not run on a
normal launch and cannot be activated in a release APK. The checking procedure and concrete
results for this change are under `artifacts/android-crash-reports/` on the build Mac.
