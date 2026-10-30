**Redmi K Pad — final phone-check run**

Branch: `codex/kpad-final`; script revision: `47135ca`. Device: `<serial>`; model: `25079RPDCC`; HyperOS: `OS3.0.310.0.WAOCNXM`.

Start: `2026-10-31T02:21:15.662464+09:00`. Finish: `2026-10-31T02:54:00.298959+09:00`. Script exit: `0`; approver exit: `0`.

One detached invocation; `PATH` prepended with `/Users/l/Library/Android/sdk/platform-tools`:

```sh
sh scripts/phone-checks.sh -n -g frames,lifecycle -l final -t 900 \
  -k /Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/ensomi-phone-kit \
  -o /Users/l/ensomi/ensomi--kpad-final/artifacts/final \
  > artifacts/final/console.txt 2>&1
```

**1. Whole console output**

Device serials are replaced with `<serial>` throughout this report.

```text
Results go to /Users/l/ensomi/ensomi--kpad-final/artifacts/final/phone-checks-25079RPDCC-20261031-022115

== Device facts
ro.product.model=25079RPDCC
ro.product.device=turner
ro.soc.model=MT8799
ro.build.fingerprint=Redmi/turner/turner:16/BP2A.250605.031.A3/OS3.0.310.0.WAOCNXM:user/release-keys
ro.build.version.release=16
ro.mi.os.version.incremental=OS3.0.310.0.WAOCNXM
ro.vendor.build.fingerprint=Redmi/turner/turner:15/AP3A.240905.015.A2/OS3.0.310.0.WAOCNXM:user/release-keys

== Checks
  frames-debug
    timed out after 900 s: stopped, going on. Run its group again with -g, or longer with -t (logcat-frames-debug.txt)
Installing the checks build (not debuggable) for frame pacing. Look at the phone now: HyperOS and some other systems ask you to allow each install.
  installed
  frames-checks-build
    timed out after 900 s: stopped, going on. Run its group again with -g, or longer with -t (logcat-frames-checks-build.txt)
Installing the debug build again. Look at the phone now: HyperOS and some other systems ask you to allow each install.
  installed
  (the lifecycle check presses Home, Recents and the power key by itself: leave the phone alone)
  lifecycle
    did not pass: see instrument-lifecycle.txt

== Collecting
6 results, 6 not ok; did not pass: lifecycle; timed out: frames-debug frames-checks-build

Done. Send back this file: /Users/l/ensomi/ensomi--kpad-final/artifacts/final/phone-checks-25079RPDCC-20261031-022115.tar.gz
```

**2. Launch app-op and activity denials**

`MIUIOP(10021)` reported as not reading `allow`: no; 0 console warnings. Successful readbacks are silent in the script.

| Logcat file | `Permission Denied Activity` occurrences |
| --- | --- |
| `logcat-frames-checks-build.txt` | 1 |
| `logcat-frames-debug.txt` | 1 |
| `logcat-lifecycle.txt` | 6 |

```text
logcat-frames-checks-build.txt:1095:10-31 01:36:30.643  1729  5356 D ActivityStarterImpl: MIUILOG- Permission Denied Activity KeyguardLocked: Intent { flg=0x10008000 xflg=0x4 cmp=io.ensomi.android/.MainActivity (has extras) } pkg : io.ensomi.android uid : 10283
logcat-frames-debug.txt:1389:10-31 01:21:19.811  1729  2596 D ActivityStarterImpl: MIUILOG- Permission Denied Activity KeyguardLocked: Intent { flg=0x10008000 xflg=0x4 cmp=io.ensomi.android/.MainActivity (has extras) } pkg : io.ensomi.android uid : 10283
logcat-lifecycle.txt:1220:10-31 01:51:42.434  1729  3082 D ActivityStarterImpl: MIUILOG- Permission Denied Activity KeyguardLocked: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10008000 xflg=0x4 pkg=io.ensomi.android cmp=io.ensomi.android/.MainActivity (has extras) } pkg : io.ensomi.android uid : 10283
logcat-lifecycle.txt:4958:10-31 01:52:01.857  1729  3083 D ActivityStarterImpl: MIUILOG- Permission Denied Activity KeyguardLocked: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 xflg=0x4 pkg=io.ensomi.android cmp=io.ensomi.android/.MainActivity } pkg : io.ensomi.android uid : 10283
logcat-lifecycle.txt:6666:10-31 01:52:26.109  1729  2005 D ActivityStarterImpl: MIUILOG- Permission Denied Activity KeyguardLocked: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 xflg=0x4 pkg=io.ensomi.android cmp=io.ensomi.android/.MainActivity } pkg : io.ensomi.android uid : 10283
logcat-lifecycle.txt:12715:10-31 01:52:49.313  1729  2830 D ActivityStarterImpl: MIUILOG- Permission Denied Activity KeyguardLocked: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 xflg=0x4 pkg=io.ensomi.android cmp=io.ensomi.android/.MainActivity } pkg : io.ensomi.android uid : 10283
logcat-lifecycle.txt:19550:10-31 01:53:12.654  1729  2004 D ActivityStarterImpl: MIUILOG- Permission Denied Activity KeyguardLocked: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 xflg=0x4 pkg=io.ensomi.android cmp=io.ensomi.android/.MainActivity } pkg : io.ensomi.android uid : 10283
logcat-lifecycle.txt:29043:10-31 01:53:36.693  1729  2908 D ActivityStarterImpl: MIUILOG- Permission Denied Activity KeyguardLocked: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 xflg=0x4 pkg=io.ensomi.android cmp=io.ensomi.android/.MainActivity } pkg : io.ensomi.android uid : 10283
```

**3. Frames**

Values are the fields reported in `results/results.jsonl`; `latePct` is a percentage and work is in milliseconds. Thermal cells retain every reported thermal field.

**Debug build**

No frame-pacing result rows were collected for this build. `—` means not reported, not zero.

| Scenario | Frames | fps | Sampled refresh Hz | Late % | Work p50 ms | Work p99 ms | `meetsTarget` | Thermal before | Thermal after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Not reported | — | — | — | — | — | — | — | — | — |

**Checks build**

No frame-pacing result rows were collected for this build. `—` means not reported, not zero.

| Scenario | Frames | fps | Sampled refresh Hz | Late % | Work p50 ms | Work p99 ms | `meetsTarget` | Thermal before | Thermal after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Not reported | — | — | — | — | — | — | — | — | — |

**4. Lifecycle**

| Trigger | `wasPlaying` | Causes | `panelShown` | `ok` |
| --- | --- | --- | --- | --- |
| `back` | true | `[]` | false | false |
| `home` | true | `[]` | false | false |
| `recents` | true | `[]` | false | false |
| `notification-shade` | true | `[]` | false | false |
| `screen-off` | true | `[]` | false | false |

Lifecycle aggregate, verbatim:

```json
{"check":"lifecycle","label":"final","debuggable":true,"triggers":5,"failed":["back","home","recents","notification-shade","screen-off"],"ok":false}
```

**5. Installs and approver**

Approver copied unchanged from `/Users/l/ensomi/ensomi--kpad-frames/artifacts/frames/approver.py`. Its adb calls require an active `Installing ...` console line. Taps require a fresh focus check for `com.miui.permcenter.install.AdbInstallActivity`; allowed coordinates are landscape `1265 1196` or portrait `701 1760`, with one exact-text `继续安装` uiautomator fallback. No refusal button or checkbox tap is implemented.

| Install | Dialog | Tap | Console outcome |
| --- | --- | --- | --- |
| the checks build (not debuggable) for frame pacing | Not observed in focus polls | No | installed |
| the debug build again | Not observed in focus polls | No | installed |

Approver events, verbatim:

```jsonl
{"time": "2026-10-31T02:21:15.681+09:00", "event": "approver_started"}
{"time": "2026-10-31T02:36:28.422+09:00", "event": "install_window_opened", "attempt_line": 14, "install": "Installing the checks build (not debuggable) for frame pacing. Look at the phone now: HyperOS and some other systems ask you to allow each install."}
{"time": "2026-10-31T02:36:28.517+09:00", "event": "focus", "attempt_line": 14, "focus": "  mCurrentFocus=Window{4e40cfc u0 NotificationShade}"}
{"time": "2026-10-31T02:36:29.572+09:00", "event": "focus", "attempt_line": 14, "focus": "  mCurrentFocus=Window{4e40cfc u0 NotificationShade}"}
{"time": "2026-10-31T02:36:30.582+09:00", "event": "install_window_closed", "attempt_line": 14, "console_tail": ["  frames-debug", "    timed out after 900 s: stopped, going on. Run its group again with -g, or longer with -t (logcat-frames-debug.txt)", "Installing the checks build (not debuggable) for frame pacing. Look at the phone now: HyperOS and some other systems ask you to allow each install.", "  installed", "  frames-checks-build"]}
{"time": "2026-10-31T02:51:40.267+09:00", "event": "install_window_opened", "attempt_line": 18, "install": "Installing the debug build again. Look at the phone now: HyperOS and some other systems ask you to allow each install."}
{"time": "2026-10-31T02:51:40.344+09:00", "event": "focus", "attempt_line": 18, "focus": "  mCurrentFocus=Window{4e40cfc u0 NotificationShade}"}
{"time": "2026-10-31T02:51:41.420+09:00", "event": "focus", "attempt_line": 18, "focus": "  mCurrentFocus=Window{4e40cfc u0 NotificationShade}"}
{"time": "2026-10-31T02:51:42.429+09:00", "event": "install_window_closed", "attempt_line": 18, "console_tail": ["    timed out after 900 s: stopped, going on. Run its group again with -g, or longer with -t (logcat-frames-checks-build.txt)", "Installing the debug build again. Look at the phone now: HyperOS and some other systems ask you to allow each install.", "  installed", "  (the lifecycle check presses Home, Recents and the power key by itself: leave the phone alone)", "  lifecycle"]}
{"time": "2026-10-31T02:54:00.293+09:00", "event": "approver_stopped"}
```

Script log (includes adb install and pull output), verbatim:

```text
02:21:15 kit=/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/ensomi-phone-kit groups=frames,lifecycle label=final install=no limit=900 app=/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/ensomi-phone-kit/app-debug.apk test=/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/ensomi-phone-kit/app-debug-androidTest.apk checks=/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/ensomi-phone-kit/app-checks.apk
02:36:27     timed out after 900 s: stopped, going on. Run its group again with -g, or longer with -t (logcat-frames-debug.txt)
Performing Streamed Install
Success
02:51:39     timed out after 900 s: stopped, going on. Run its group again with -g, or longer with -t (logcat-frames-checks-build.txt)
Performing Streamed Install
Success
/sdcard/Android/media/io.ensomi.android/phone-checks/: 1 file pulled, 0 skipped. 0.1 MB/s (942 bytes in 0.007s)
02:53:58 6 results, 6 not ok; did not pass: lifecycle; timed out: frames-debug frames-checks-build
```

**6. Not-ok lines, summary and archive**

`not-ok.jsonl`: 6 lines.

```jsonl
{"check":"lifecycle-pause","label":"final","debuggable":true,"trigger":"back","wasPlaying":true,"causes":[],"panelShown":false,"seconds":22.2,"ok":false}
{"check":"lifecycle-pause","label":"final","debuggable":true,"trigger":"home","wasPlaying":true,"causes":[],"panelShown":false,"seconds":24.2,"ok":false}
{"check":"lifecycle-pause","label":"final","debuggable":true,"trigger":"recents","wasPlaying":true,"causes":[],"panelShown":false,"seconds":23.2,"ok":false}
{"check":"lifecycle-pause","label":"final","debuggable":true,"trigger":"notification-shade","wasPlaying":true,"causes":[],"panelShown":false,"seconds":23.3,"ok":false}
{"check":"lifecycle-pause","label":"final","debuggable":true,"trigger":"screen-off","wasPlaying":true,"causes":[],"panelShown":false,"seconds":24.0,"ok":false}
{"check":"lifecycle","label":"final","debuggable":true,"triggers":5,"failed":["back","home","recents","notification-shade","screen-off"],"ok":false}
```

Final summary line, verbatim:

```text
6 results, 6 not ok; did not pass: lifecycle; timed out: frames-debug frames-checks-build
```

Archive absolute path: `/Users/l/ensomi/ensomi--kpad-final/artifacts/final/phone-checks-25079RPDCC-20261031-022115.tar.gz`.

**7. Installed build; deviations and observations**

Installed at the end: **debug build**, from the successful replacement with the kit's `app-debug.apk`. The lifecycle rows report `debuggable:true`. Read-only `adb shell dumpsys package io.ensomi.android` after completion returned:

```text
versionCode=1 minSdk=33 targetSdk=36
versionName=0.1.0
lastUpdateTime=2026-10-31 01:51:41
pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
```

`enable_three_gesture`: script's startup `device.txt` reports `0`; a read-only query after completion reports `0`.

Observed differences, deviations and other run facts:

- The runbook asks for the device to be unlocked. The collected logcats contain `Permission Denied Activity KeyguardLocked`: 1 occurrence in `logcat-frames-debug.txt`, 1 in `logcat-frames-checks-build.txt`, and 6 in `logcat-lifecycle.txt`. Each corresponding app-launch `START` line reports `result code=102`. The exact denial lines are in item 2. No manual unlock was attempted.
- Both frame checks reported `timed out after 900 s`. Their instrumentation files end at `INSTRUMENTATION_STATUS_CODE: 1`, with no completion code. There are no `frame-pacing` JSON rows and no frame CSV files in the collected results or archive. No frames, fps, refresh samples, late shares, work percentiles, target flags or per-scenario thermal values were returned.
- Lifecycle returned five trigger rows, each with `wasPlaying:true`, `causes:[]`, `panelShown:false`, `ok:false`; its aggregate lists all five triggers in `failed`. Its instrumentation reports `Time: 135.535`, `Tests run: 1,  Failures: 1`, and `INSTRUMENTATION_CODE: -1`. The script printed `did not pass` and proceeded to collection.
- The script and approver each exited `0`; the script's result summary still contains the lifecycle failure and both frame timeouts.
- The runbook's frame-group table describes 0°, 40° and 55° in landscape and portrait. `FramePacingCheck.kt` defines four default scenarios: `pacing-flat` (landscape 0°), `pacing` (landscape 40°), `pacing-lean-max` (landscape 55°), and `pacing-portrait` (portrait 0°). This invocation returned no scenario rows.
- With `-n -g frames,lifecycle`, the script skipped its initial debug and test APK installs, model stage and test-data stage. It still performed the two replacement installs. Both installs reported `Performing Streamed Install` then `Success` on the first attempt. The approver's focus polls showed `NotificationShade` during both install windows. No install dialog was observed, no tap was made, and no uiautomator fallback ran.
- The install helper invokes `KEYCODE_WAKEUP` before each replacement. With `-n`, there is no initial install helper call before `frames-debug`. The script does not dismiss the keyguard before either frame check. The lifecycle check's existing screen-off trigger invokes `wm dismiss-keyguard`; the collected log still reports `KeyguardLocked` for the subsequent launch.
- The script's successful `MIUIOP(10021)` readbacks are silent; no not-allow warning was printed at any of the three checks. The script invokes the grant before each check. No separate app-op or permission changes were made.
- The results pull succeeded on its first attempt: `1 file pulled, 0 skipped`, `942 bytes`. The retry path was not taken. The archive contains only `results/results.jsonl` under `results/`.
- The script invokes `svc power stayon usb` at entry, `logcat -G 16M`, clears logcat before each check, removes the prior on-device check results at suite start, force-stops the app after each timeout and during collection, and invokes `svc power stayon false` on exit. These are actions in the requested script; there were no independent device-setting changes or taps.
- Mac-side script/approver times and device logcat times are retained as recorded: for example, the checks install window opened at `2026-10-31T02:36:28.422+09:00`, and its app-launch denial is logged at `10-31 01:36:30.643`.
- The repository's `artifacts` symlink to `/Users/l/ensomi/ensomi/artifacts` was replaced by a local directory; only the link was removed. The approver was copied unchanged. The kit and app/script/runbook sources were not changed. The run was detached, with no extra watchdog, no rerun, no uninstall, no rebuild and no other groups.

Outstanding result fields: all frame measurements are absent; all five lifecycle trigger rows and the lifecycle aggregate report `ok:false`. No further device run was performed.
