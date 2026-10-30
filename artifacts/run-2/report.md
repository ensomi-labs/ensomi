# Redmi K Pad phone checks — run 2, USB continuation

Run date: 2026-10-30. Branch: `codex/kpad-run-2`. This report replaces the wireless-discovery
report committed in `d4fecf3`; the owner subsequently authorized USB. The runner is the repository's
`scripts/phone-checks.sh` from `a472c8a`; the supplied kit was built at `a7fd06a`. No rebuild occurred.

The detached runner collected device facts, attempted the debug-app install three times, and exited
with status **1** before `Chart model` or any check. Each install returned
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. There was no rerun.

All timestamps below are the Mac's JST (UTC+09:00); the tablet's CST is one hour earlier. Prompt
sample timestamps mark the start of the window/power queries. No tablet logcat times were used.
All relative artifact paths are relative to the worktree.

1. **Transport and device.** USB. Only one device row was listed, so the runner received no `-s`,
   as requested in the continuation brief. The sampler and preservation pull used `-s <serial>`.
   No mDNS or connect command ran during this continuation. The device was ready immediately;
   the three-minute wait was unnecessary. Start and end rows were identical, with identifiers
   redacted:

   ```text
   Start — 23:20:40 JST:
   List of devices attached
   <serial>       device usb:<serial> product:turner model:25079RPDCC device:turner transport_id:50

   End — 23:21:26 JST:
   List of devices attached
   <serial>       device usb:<serial> product:turner model:25079RPDCC device:turner transport_id:50
   ```

   USB was still attached at the end; `transport_id` was **50 → 50**. Saved rows:
   `artifacts/run-2/usb-devices-start.txt` and `artifacts/run-2/usb-devices-end.txt`.
   Device facts recorded `ro.soc.model=MT8799`, `ro.board.platform=mt6991`, Android 16 / SDK 36,
   HyperOS `OS3.0.310.0.WAOCNXM`, `page_size=4096`, physical size `1880x3008`, density `450`.

2. **Install prompts.** All three attempts were for `app-debug.apk`. The test APK and both
   installs in the frames group were never reached. Each attempt took focus as
   `com.miui.securitycenter/com.miui.permcenter.install.AdbInstallActivity`; every sample of
   that focus had `mWakefulness=Awake`.

   | Attempt | Install announced | First focus sample | Last focus sample | First subsequent sample without dialog | First focus → subsequent absence | Wakefulness |
   | --- | --- | --- | --- | --- | --- | --- |
   | 1 | 23:20:43.895 | 23:20:46.027 | 23:20:57.085 | 23:20:58.095 | 12.068 s | Awake |
   | 2 | 23:20:57.732 | 23:21:00.109 | 23:21:10.181 | 23:21:11.186 | 11.077 s | Awake |
   | 3 | 23:21:11.545 | 23:21:14.192 | 23:21:24.260 | 23:21:25.269 | 11.077 s | Awake |

   These are sampled intervals, not exact focus lifetimes. The preceding absent samples were
   23:20:45.019, 23:20:59.101 and 23:21:13.188. The first-to-last observed focus spans were
   respectively 11.058, 10.072 and 10.068 seconds; the preceding-absence-to-following-absence
   spans were 13.076, 12.085 and 12.081 seconds. Query timing and approximately one-second
   sampling limit the duration precision.

   | Attempt | PNG | Capture completed | Visual inspection | adb outcome |
   | --- | --- | --- | --- | --- |
   | 1 | `artifacts/run-2/prompt-1.png` | 23:20:46.560 | Visible Ensomi USB installation dialog; not black | `Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]` |
   | 2 | `artifacts/run-2/prompt-2.png` | 23:21:00.582 | Visible Ensomi USB installation dialog; not black | `Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]` |
   | 3 | `artifacts/run-2/prompt-3.png` | 23:21:14.678 | Visible Ensomi USB installation dialog; not black | `Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]` |

   Each capture exited 0. All three PNGs have SHA-256
   `caa147678b0679456692112654e122a244c5b610e23cd5a8080accdfd1b979b3`.
   They show the title `USB安装提示`, app name `Ensomi`, left button `继续安装` (continue installation),
   and right button `拒绝（10）` (reject, with countdown). They establish a rendered dialog in the
   captures; the power samples report Awake. No observation of the owner's taps was recorded.
   The adb rejection text does not itself establish whether a tap occurred.

   The console announced retry 2 at 23:20:57.732 and retry 3 at 23:21:11.545, then reported the
   stop at 23:21:25.435. No fourth attempt occurred. The sampler's final sample at 23:21:26.274
   showed `NotificationShade` and `mWakefulness=Asleep`, after the last install failure during
   script exit handling. The sampler stopped at 23:21:26.465 and is no longer running.
   Raw evidence: `artifacts/run-2/prompt-samples.txt`, `console.txt`, `progress.txt`, and the
   run directory's `script.log`.

3. **Model.** `Chart model` was never reached. No `model-install.txt` was produced; no model
   path or installation duration was recorded. There was no Hugging Face download or files fallback.

4. **Groups.** Every group was skipped before execution due to the failed prerequisite install.
   There are no instrumentation assertions or group-specific failure texts. The common blocking
   failure was `Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]` on all three
   debug-app install attempts.

   | Group | Status |
   | --- | --- |
   | runtime | Skipped — app install failed |
   | model | Skipped — app install failed |
   | decode | Skipped — app install failed |
   | generation | Skipped — app install failed |
   | determinism | Skipped — app install failed |
   | audio (speaker label) | Skipped — app install failed |
   | frames (debug and checks builds) | Skipped — app install failed |
   | lifecycle | Skipped — app install failed |

5. **Verdict files and final output.** No `not-ok.jsonl` or `results.jsonl` was produced, so there
   are no lines to reproduce and no results-count summary. The script's final console line was:

   ```text
   Stopped: the phone refused the install three times: its prompt went unanswered, or 'Install via USB' is off. On HyperOS that toggle is in Settings > Additional settings > Developer options (it asks for a Xiaomi account; a phone may also need a SIM). Next time, watch for the prompt and tap Install.
   ```

6. **Generation.** No song or `lowMemory` setting ran. No per-stage times, peak resident memory,
   note counts, stars, seeds or chart SHA-256 values exist for this run. Equality with the emulator's
   `mosaic.mp3` hash `e79435e98a105b209e0f3ea970dbe4d9c9614493a5dfd0e6be5dce3ade571cc1`
   cannot be assessed; this run produced neither settings nor charts to compare.

7. **Audio.** Timestamp jitter, drift, steps back of each kind, underruns and actual output were
   not recorded. `speaker` was the runner's requested label only; the audio check never started.

8. **Frames.** Neither build ran any scenario. Frame rate, actual display refresh rate, late-frame
   share, work per frame p50/p99 and `meetsTarget` are unavailable. There are no measured scenario
   rows to tabulate, and no instrumentation or target pass is claimed.

9. **Lifecycle.** Back, Home, Recents, notification shade and screen-off triggers were not run.
   Each trigger's `wasPlaying`, pause cause and `panelShown` are unavailable.

10. **Connection and preservation.** The script reported no connection gap. USB was present at
    both endpoints with unchanged `transport_id`; no intervening reconnect is established by
    these observations. Every `adb install` returned a failure rather than hanging, and the
    script advanced through its three retries. `am instrument` was never invoked. The
    wait-through-gap behavior and the frames group's install-failure handling remain untested
    on real adb.

    Because the script ended before `Collecting`, the detached supervisor immediately attempted
    the required pull at 23:21:26.465, before the final device listing:

    ```sh
    adb -s <serial> pull /sdcard/Android/media/io.ensomi.android/phone-checks artifacts/run-2/partial-results-1
    ```

    It exited 1 at 23:21:26.486, with this exact output:

    ```text
    adb: error: failed to stat remote object '/sdcard/Android/media/io.ensomi.android/phone-checks': No such file or directory
    ```

    No partial results were recovered. The pull log is `artifacts/run-2/partial-pull-1.txt`.
    No rerun, uninstall or manual recovery was attempted.

11. **Archive and duration.** No archive was created because the script stopped before collection;
    there is no archive absolute path or SHA-256. The local run directory is
    `artifacts/run-2/phone-checks-25079RPDCC-20261030-232041/`.
    The detached invocation started at 23:20:40.982. The stop message was observed at
    23:21:25.435; script exit was detected and preservation began at 23:21:26.465. Exit handling
    and the required pull finished at 23:21:26.486. Total observed time including exit handling
    and preservation was **45.504 seconds**; the script itself ended after approximately
    **45.483 seconds**. These values come from `run-start.json`, `run-end.json` and `progress.txt`.

12. **Deviations and runbook observations.** USB followed the owner's revised brief. The script
    was launched detached by a Python supervisor (new process session and ignored SIGHUP), with
    output in `artifacts/run-2/console.txt`; its repository file was unchanged. The sampler
    selected the two requested dumpsys fields with Python filtering instead of shell `grep`,
    at approximately one-second intervals. It started before the script and stopped on script
    exit; no checks ran while it was active. These are implementation differences in the
    requested monitoring, not changes to device actions. The supervisor and monitoring files
    remain uncommitted under `artifacts/run-2/`.

    The runbook's three-attempt install behavior occurred on real adb. Its approximate
    twelve-second unanswered-prompt observation is accompanied here by focus samples and
    visible PNGs. The reason for the owner-side failure to approve is not established.
    The runbook's normal model/check/collection flow was not reached. The explicit brief's
    pull-and-stop rule was followed rather than the runbook's suggestion to rerun after a stop.
    There were no additional deviations from the revised brief.

    No app source, repository script or documentation was changed, and no rebuild occurred.
    No device input or setting change was made outside the script's own actions. Only this
    report is committed, in a new commit after `d4fecf3`. PNGs, logs, device facts and other
    artifacts remain local; identifiers, account names and network identifiers are omitted
    from the committed report.
