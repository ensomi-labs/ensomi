**Redmi K Pad phone checks — run 1 retry, 2026-10-30**

The tablet was listed as `device` on the first connection check. The detached script ran for 15.221604824066162 seconds and stopped during its first app install, before any check group or the `Collecting` step. Script exit code: `1`.

The raw install failure was:

```text
Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```

Before further work, the supervisor attempted the required pull from `/sdcard/Android/media/io.ensomi.android/phone-checks` to `artifacts/run-1/partial-results-1`. It exited `1` with:

```text
adb: error: failed to stat remote object '/sdcard/Android/media/io.ensomi.android/phone-checks': No such file or directory
```

1. Device facts. The single connection check returned one physical device and no emulator. No connection retry or `-s` was needed. Its device row, with the serial replaced, was:

```text
<serial>       device usb:18874368X product:turner model:25079RPDCC device:turner transport_id:44
```

The requested property and shell output was printed together before the run, saved to `facts.txt`, and is reproduced here unchanged:

```text
getprop ro.product.model
25079RPDCC
getprop ro.product.device
turner
getprop ro.product.marketname
REDMI K Pad
getprop ro.soc.manufacturer
Mediatek
getprop ro.soc.model
MT8799
getprop ro.build.version.release
16
getprop ro.build.version.sdk
36
getprop ro.build.version.incremental
OS3.0.310.0.WAOCNXM
getprop ro.mi.os.version.name
OS3.0
getprop ro.mi.os.version.incremental
OS3.0.310.0.WAOCNXM
getprop ro.product.cpu.abi
arm64-v8a
getprop persist.security.adbinstall
1
getprop persist.security.adbinput
1
getconf PAGE_SIZE
4096
wm size
Physical size: 1880x3008
wm density
Physical density: 450
FACTS-DONE
```

2. Model. The script did not reach `Chart model`. No model installation was attempted, and `model-install.txt`, its `path` and installation duration are unavailable.

3. Groups. The install prerequisite failed; all eight groups were skipped. No `run_check` name was invoked, so no instrumentation failure text exists.

| Group | Status | Reason |
| --- | --- | --- |
| runtime | skipped | First app install failed before checks |
| model | skipped | First app install failed before checks |
| decode | skipped | First app install failed before checks |
| generation | skipped | First app install failed before checks |
| determinism | skipped | First app install failed before checks |
| audio | skipped | First app install failed before checks |
| frames | skipped | First app install failed before checks |
| lifecycle | skipped | First app install failed before checks |

4. `not-ok.jsonl`. Not produced; there are no lines to reproduce. `results.jsonl` was also not produced or retrieved. These missing files are not passing verdicts.

5. Generation. No song or `lowMemory` setting ran. No stage time, peak resident memory, note count or chart SHA-256 was recorded. There is no device chart to compare with the emulator's `mosaic.mp3` SHA-256 `e79435e98a105b209e0f3ea970dbe4d9c9614493a5dfd0e6be5dce3ade571cc1`; no device stars or seed result exists.

6. Audio. Timestamp jitter, drift, steps back of each kind, underruns and the output used by the audio check were not recorded. The command's label was `speaker`; the audio check never ran.

7. Frames. Neither `frames-debug` nor `frames-checks-build` ran. No scenario has a frame rate, actual display refresh rate, late-frame share, work per frame p50/p99 or `meetsTarget` result. There are no frame measurement rows to tabulate.

8. Lifecycle. No trigger ran. No `wasPlaying`, pause cause or `panelShown` values were recorded.

9. Archive and time. The script did not reach collection or archive creation. No archive path or archive SHA-256 exists. The detached supervisor recorded:

| Field | Raw value |
| --- | --- |
| `started_at` | `2026-10-30T13:29:20.881411+00:00` |
| `ended_at` | `2026-10-30T13:29:36.103015+00:00` |
| `elapsed_seconds` | `15.221604824066162` |
| `exit_code` | `1` |
| `collecting_seen` | `false` |

The elapsed time covers the script invocation through its exit, including its exit trap; it excludes the subsequent partial-results pull and report preparation. Local evidence remains under `artifacts/run-1/`: `console.txt`, `run-status.json`, `partial-pull-1.txt`, and `phone-checks-25079RPDCC-20261030-222920/` (device dumps and `script.log`). These files are not committed.

10. Deviations and observed runbook differences:

- The run stopped at the first of the expected four installs. `persist.security.adbinstall` and `persist.security.adbinput` both returned `1`, and the app install still returned the failure quoted above. No claim is made about why the install was canceled.
- The required partial-results pull was attempted immediately after the script exited; the remote results directory did not exist. No rerun occurred and no tablet results were deleted by a rerun.
- The optional install-retry exception was not used. The first app install failed before any check group existed to resume, and `-n` would skip the installation that had just failed. The remaining work is to resolve the install prompt/refusal with the owner and run the supplied kit again.
- The worktree's `artifacts` was already a local directory; no symlink was replaced.
- A detached supervisor supplied the Android platform-tools directory on `PATH`, ignored hangup, logged the unmodified script to `console.txt`, recorded elapsed time, and performed the required emergency pull. The script was invoked with the requested `-k`, `-o` and `-l speaker`, without `-s`, `-n` or `-g`.
- The script's `-h` printed its usage and exited `2` before the actual run; the actual run's exit code and time are reported separately above.
- There was no device input outside the script, no manual group, wired or Bluetooth run, uninstall, rebuild, source/script/docs change, adb server restart or external device-setting change. The script's own commands and cleanup ran as written. No GitHub authentication work was performed on this retry; the ens job handles the push on exit.

This replaces the earlier connection-blocked report. Only `artifacts/run-1/report.md` and `artifacts/run-1/facts.txt` are included in the new commit on top of `1c625e1`.
