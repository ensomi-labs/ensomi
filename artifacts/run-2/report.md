# Redmi K Pad phone checks — run 2

2026-10-30. Branch `codex/kpad-run-2`; starting commit
`a472c8a52f39db2af989ac119ab4d959b4374ad3`. The supplied kit was built at `a7fd06a`;
no build was performed. Discovery stopped after the brief's two-minute limit because no wireless
transport or connection address appeared. The phone-check script was not started.

1. **Transport.** No transport was selected or used for checks. The initial `adb devices -l`
   observation was at approximately 23:16:11 JST (22:16:11 CST). The final observation was at
   23:18:12 JST (22:18:12 CST). Every observation listed only the following USB row; USB was
   still attached at the end. The device serial and USB identifier are redacted below.

   ```text
   List of devices attached
   <serial>       device usb:<serial> product:turner model:25079RPDCC device:turner transport_id:50
   ```

   `adb mdns services` returned this header and no service rows initially and at every subsequent
   observation, including the final one:

   ```text
   List of discovered mdns services
   ```

   After the initial observation, discovery was recorded at 23:16:31, 23:16:46, 23:17:01,
   23:17:16, 23:17:23, 23:17:38, 23:17:53, 23:18:08 and 23:18:12 JST. No `adb connect`
   was attempted because no tablet address was shown. There was no USB fallback. Local evidence:
   `artifacts/run-2/devices-initial.txt`, `devices-final.txt`, `mdns-initial.txt`,
   `mdns-final.txt`, `discovery.txt` and `discovery-ended.txt` (all in the same directory).

2. **Install prompts.** Zero attempts. The sampler was not started because wireless discovery
   did not succeed. No `AdbInstallActivity` focus, focus duration, `mWakefulness`, screenshot or
   install outcome was measured. No PNGs were produced.

3. **Model.** No model operation ran. There is no `model-install.txt`; its `path` and duration
   are unavailable.

4. **Groups.** All groups were skipped before execution because no wireless transport was
   available. There is no instrumentation failure text.

   | Group | Status |
   | --- | --- |
   | runtime | Skipped — wireless transport unavailable |
   | model | Skipped — wireless transport unavailable |
   | decode | Skipped — wireless transport unavailable |
   | generation | Skipped — wireless transport unavailable |
   | determinism | Skipped — wireless transport unavailable |
   | audio (speaker) | Skipped — wireless transport unavailable |
   | frames (debug and checks builds) | Skipped — wireless transport unavailable |
   | lifecycle | Skipped — wireless transport unavailable |

5. **Verdict files and summary.** Neither `not-ok.jsonl` nor `results.jsonl` was generated.
   There are no lines to reproduce and no script final summary. No check passed or failed.

6. **Generation.** No song or `lowMemory` setting was run. Stage times, peak resident memory,
   note counts, chart SHA-256, stars and seed were not measured. Equality with the emulator's
   `mosaic.mp3` chart SHA-256
   `e79435e98a105b209e0f3ea970dbe4d9c9614493a5dfd0e6be5dce3ade571cc1`
   cannot be assessed; neither a hash nor run settings were produced.

7. **Audio.** Timestamp jitter, drift, steps back of either kind, underruns and actual output
   were not recorded. `speaker` was the requested label only.

8. **Frames.** Neither build ran any scenario. Frame rate, display refresh rate, late-frame
   share, work per frame p50/p99 and `meetsTarget` are all unavailable; there are no measured
   scenario rows to tabulate.

9. **Lifecycle.** No trigger ran. `wasPlaying`, pause cause and `panelShown` are unavailable
   for Back, Home, Recents, notification shade and screen-off.

10. **Connection behavior.** No script-reported connection gap exists because the script never
    started. Discovery commands completed and the USB row remained present with `transport_id:50`.
    No wireless row was observed. `am instrument` was never invoked, so its behavior during a
    connection gap, and the script's install retry/wait behavior on real adb, remain untested.

11. **Archive and duration.** No archive was created; no archive absolute path or SHA-256 exists.
    The checks did not start. Transport discovery lasted approximately 2 minutes 1 second
    (23:16:11–23:18:12 JST).

12. **Brief and runbook differences.** The brief's explicit stop condition was followed. The
    sampler, detached checks, collection and partial-results pull were not reached. No run was
    restarted and no tablet results were deleted. Wireless discovery was used as directed by
    the brief, whereas `docs/phone-checks.md` describes connecting by USB; no behavior of its
    checks was observed on this tablet in this run. No other deviation was made. The
    `artifacts` symlink was replaced with a local directory; its target was left untouched.
    Source, scripts and docs were unchanged. No device setting was changed, no input was
    injected and no application was installed or uninstalled.

Only this report is committed. The discovery evidence remains local under `artifacts/run-2/`.
A wireless transport must become available before the owner starts another run.
