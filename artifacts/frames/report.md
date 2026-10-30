Ensomi Redmi K Pad launch control and clean rerun — 2026-10-31 JST

Worktree `/Users/l/ensomi/ensomi--kpad-frames`; branch `codex/kpad-frames`; starting commit `a472c8a52f39db2af989ac119ab4d959b4374ad3`. Ran the existing `scripts/phone-checks.sh` with the unchanged kit `/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/ensomi-phone-kit`. No rebuild or changes to source, repository scripts, or existing documentation. Mac timestamps below use JST (UTC+09:00); device logcat uses CST (UTC+08:00), with its dates retained.

Observed sequence: the launcher control was refused with `10021=ignore`; the same short control recorded frames after `10021=allow`. Audio and debug pacing completed. After the checks-build install, launch was refused again; the watchdog stopped the run and the final app-op read was `ignore`. Only partial results are available.

**0. Initial state**

Saved at 2026-10-31T00:22:17.709618+09:00–2026-10-31T00:22:18.178450+09:00. `adb devices -l`:

```text
List of devices attached
<serial>       device usb:17825792X product:turner model:25079RPDCC device:turner transport_id:50
```

Focus:

```text
mCurrentFocus=Window{bf936c u0 com.netease.cloudmusic/com.netease.cloudmusic.music.biz.rn.activity.BgMainProcessRNActivity}
```

Model: `25079RPDCC`; Android `16`; HyperOS `OS3.0.310.0.WAOCNXM`. NetEase `com.netease.cloudmusic` was running (PID `30342`) and in the foreground. Its two registered AudioTrack players (`piid:13967`, `piid:13983`, UID/PID `10213/30342`) were both `state:paused`. The current playback-player list contained no `state:started` player. No change was made to NetEase. Initial app-ops output:

```text
Uid mode: COARSE_LOCATION: ignore
FINE_LOCATION: ignore
READ_CONTACTS: ignore
WRITE_CONTACTS: ignore
READ_CALL_LOG: ignore
WRITE_CALL_LOG: ignore
READ_CALENDAR: ignore
WRITE_CALENDAR: ignore
POST_NOTIFICATION: ignore
CALL_PHONE: ignore
READ_SMS: ignore
RECEIVE_SMS: ignore
RECEIVE_MMS: ignore
RECEIVE_WAP_PUSH: ignore
SEND_SMS: ignore
CAMERA: ignore
RECORD_AUDIO: ignore
READ_CLIPBOARD: foreground
WRITE_CLIPBOARD: foreground
AUDIO_MEDIA_VOLUME: foreground
READ_PHONE_STATE: ignore
ADD_VOICEMAIL: ignore
USE_SIP: ignore
PROCESS_OUTGOING_CALLS: ignore
BODY_SENSORS: ignore
READ_CELL_BROADCASTS: ignore
READ_EXTERNAL_STORAGE: ignore
WRITE_EXTERNAL_STORAGE: ignore
GET_ACCOUNTS: ignore
READ_PHONE_NUMBERS: ignore
ANSWER_PHONE_CALLS: ignore
ACCEPT_HANDOVER: ignore
BLUETOOTH_SCAN: ignore
ACTIVITY_RECOGNITION: ignore
READ_MEDIA_AUDIO: ignore
READ_MEDIA_VIDEO: ignore
READ_MEDIA_IMAGES: ignore
ACCESS_MEDIA_LOCATION: ignore
BLUETOOTH_CONNECT: ignore
UWB_RANGING: ignore
BLUETOOTH_ADVERTISE: ignore
NEARBY_WIFI_DEVICES: ignore
READ_MEDIA_VISUAL_USER_SELECTED: ignore
READ_HEART_RATE: ignore
READ_SKIN_TEMPERATURE: ignore
RANGING: ignore
READ_OXYGEN_SATURATION: ignore
SYSTEM_ALERT_WINDOW: default; rejectTime=+48m40s762ms ago
WRITE_MEDIA_AUDIO: deny; rejectTime=+21m1s360ms ago
WRITE_MEDIA_VIDEO: deny; rejectTime=+21m0s371ms ago
WRITE_MEDIA_IMAGES: deny; rejectTime=+21m0s370ms ago
MANAGE_EXTERNAL_STORAGE: default; rejectTime=+21m1s372ms ago
NO_ISOLATED_STORAGE: deny
ACCESS_RESTRICTED_SETTINGS: default; rejectTime=+56m56s212ms ago
USE_FULL_SCREEN_INTENT: default
CONTROL_AUDIO_PARTIAL: foreground; time=+51m10s992ms ago; duration=+2m25s585ms
MIUIOP(10004): ignore
MIUIOP(10008): ignore
MIUIOP(10017): ignore
MIUIOP(10020): ignore
MIUIOP(10021): ignore; rejectTime=+48m40s761ms ago
MIUIOP(10022): ignore
MIUIOP(10033): allow
MIUIOP(10045): ignore
MIUIOP(10053): ignore
```

**1. Control without a grant**

Pressed Home once, waited three seconds, and confirmed:

```text
mCurrentFocus=Window{f0eab24 u0 com.miui.home/com.miui.home.launcher.Launcher}
```

After `adb logcat -c`, ran:

```text
adb shell am instrument -w -r -e class io.ensomi.android.checks.FramePacingCheck -e pacing pacing-flat -e paceSeconds 5 io.ensomi.android.test/androidx.test.runner.AndroidJUnitRunner
```

Refused with the launcher in front at launch; no other app had focus then. NetEase was running in step 0 and was not force-stopped or otherwise altered. Started 2026-10-31T00:22:31.374853+09:00; finished 2026-10-31T00:24:34.821293+09:00. At the 120-second Mac limit, killed the adb client and ran `adb shell am force-stop io.ensomi.android` (exit 0). Control wrapper instrument-to-capture elapsed time: 120.159 s. No frame result was emitted. Relevant logcat lines (device time):

```text
10-30 23:22:36.548 24250 24282 I TestRunner: started: framesAtEachLean(io.ensomi.android.checks.FramePacingCheck)
10-30 23:22:36.573  1729  2831 D ActivityStarterImpl: MIUILOG- Permission Denied Activity : Intent { flg=0x10008000 xflg=0x4 cmp=io.ensomi.android/.MainActivity (has extras) } pkg : io.ensomi.android uid : 10283 tuid : 10132
10-30 23:22:36.575  1729  2831 I ActivityTaskManager: START u0 {flg=0x10008000 xflg=0x4 cmp=io.ensomi.android/.MainActivity (has extras)} with LAUNCH_MULTIPLE from uid 10283 from pid 24250 callingPackage io.ensomi.android (BAL_ALLOW_PERMISSION) result code=102
```

**2. App-op change and identical short control**

Before `adb shell appops set io.ensomi.android 10021 allow`:

```text
Uid mode: COARSE_LOCATION: ignore
FINE_LOCATION: ignore
READ_CONTACTS: ignore
WRITE_CONTACTS: ignore
READ_CALL_LOG: ignore
WRITE_CALL_LOG: ignore
READ_CALENDAR: ignore
WRITE_CALENDAR: ignore
POST_NOTIFICATION: ignore
CALL_PHONE: ignore
READ_SMS: ignore
RECEIVE_SMS: ignore
RECEIVE_MMS: ignore
RECEIVE_WAP_PUSH: ignore
SEND_SMS: ignore
CAMERA: ignore
RECORD_AUDIO: ignore
READ_CLIPBOARD: foreground
WRITE_CLIPBOARD: foreground
AUDIO_MEDIA_VOLUME: foreground
READ_PHONE_STATE: ignore
ADD_VOICEMAIL: ignore
USE_SIP: ignore
PROCESS_OUTGOING_CALLS: ignore
BODY_SENSORS: ignore
READ_CELL_BROADCASTS: ignore
READ_EXTERNAL_STORAGE: ignore
WRITE_EXTERNAL_STORAGE: ignore
GET_ACCOUNTS: ignore
READ_PHONE_NUMBERS: ignore
ANSWER_PHONE_CALLS: ignore
ACCEPT_HANDOVER: ignore
BLUETOOTH_SCAN: ignore
ACTIVITY_RECOGNITION: ignore
READ_MEDIA_AUDIO: ignore
READ_MEDIA_VIDEO: ignore
READ_MEDIA_IMAGES: ignore
ACCESS_MEDIA_LOCATION: ignore
BLUETOOTH_CONNECT: ignore
UWB_RANGING: ignore
BLUETOOTH_ADVERTISE: ignore
NEARBY_WIFI_DEVICES: ignore
READ_MEDIA_VISUAL_USER_SELECTED: ignore
READ_HEART_RATE: ignore
READ_SKIN_TEMPERATURE: ignore
RANGING: ignore
READ_OXYGEN_SATURATION: ignore
SYSTEM_ALERT_WINDOW: default; rejectTime=+2m10s142ms ago
WRITE_MEDIA_AUDIO: deny; rejectTime=+23m28s411ms ago
WRITE_MEDIA_VIDEO: deny; rejectTime=+23m27s422ms ago
WRITE_MEDIA_IMAGES: deny; rejectTime=+23m27s421ms ago
MANAGE_EXTERNAL_STORAGE: default; rejectTime=+23m28s423ms ago
NO_ISOLATED_STORAGE: deny
ACCESS_RESTRICTED_SETTINGS: default; rejectTime=+59m23s263ms ago
USE_FULL_SCREEN_INTENT: default
CONTROL_AUDIO_PARTIAL: foreground; time=+53m38s43ms ago; duration=+2m25s585ms
MIUIOP(10004): ignore
MIUIOP(10008): ignore
MIUIOP(10017): ignore
MIUIOP(10020): ignore
MIUIOP(10021): ignore; rejectTime=+2m10s142ms ago
MIUIOP(10022): ignore
MIUIOP(10033): allow
MIUIOP(10045): ignore
MIUIOP(10053): ignore
```

The set command exited 0 and printed nothing. Read-back after:

```text
Uid mode: COARSE_LOCATION: ignore
FINE_LOCATION: ignore
READ_CONTACTS: ignore
WRITE_CONTACTS: ignore
READ_CALL_LOG: ignore
WRITE_CALL_LOG: ignore
READ_CALENDAR: ignore
WRITE_CALENDAR: ignore
POST_NOTIFICATION: ignore
CALL_PHONE: ignore
READ_SMS: ignore
RECEIVE_SMS: ignore
RECEIVE_MMS: ignore
RECEIVE_WAP_PUSH: ignore
SEND_SMS: ignore
CAMERA: ignore
RECORD_AUDIO: ignore
READ_CLIPBOARD: foreground
WRITE_CLIPBOARD: foreground
AUDIO_MEDIA_VOLUME: foreground
READ_PHONE_STATE: ignore
ADD_VOICEMAIL: ignore
USE_SIP: ignore
PROCESS_OUTGOING_CALLS: ignore
BODY_SENSORS: ignore
READ_CELL_BROADCASTS: ignore
READ_EXTERNAL_STORAGE: ignore
WRITE_EXTERNAL_STORAGE: ignore
GET_ACCOUNTS: ignore
READ_PHONE_NUMBERS: ignore
ANSWER_PHONE_CALLS: ignore
ACCEPT_HANDOVER: ignore
BLUETOOTH_SCAN: ignore
ACTIVITY_RECOGNITION: ignore
READ_MEDIA_AUDIO: ignore
READ_MEDIA_VIDEO: ignore
READ_MEDIA_IMAGES: ignore
ACCESS_MEDIA_LOCATION: ignore
BLUETOOTH_CONNECT: ignore
UWB_RANGING: ignore
BLUETOOTH_ADVERTISE: ignore
NEARBY_WIFI_DEVICES: ignore
READ_MEDIA_VISUAL_USER_SELECTED: ignore
READ_HEART_RATE: ignore
READ_SKIN_TEMPERATURE: ignore
RANGING: ignore
READ_OXYGEN_SATURATION: ignore
SYSTEM_ALERT_WINDOW: default; rejectTime=+2m10s245ms ago
WRITE_MEDIA_AUDIO: deny; rejectTime=+23m28s514ms ago
WRITE_MEDIA_VIDEO: deny; rejectTime=+23m27s525ms ago
WRITE_MEDIA_IMAGES: deny; rejectTime=+23m27s524ms ago
MANAGE_EXTERNAL_STORAGE: default; rejectTime=+23m28s526ms ago
NO_ISOLATED_STORAGE: deny
ACCESS_RESTRICTED_SETTINGS: default; rejectTime=+59m23s366ms ago
USE_FULL_SCREEN_INTENT: default
CONTROL_AUDIO_PARTIAL: foreground; time=+53m38s146ms ago; duration=+2m25s585ms
MIUIOP(10004): ignore
MIUIOP(10008): ignore
MIUIOP(10017): ignore
MIUIOP(10020): ignore
MIUIOP(10021): allow; rejectTime=+2m10s245ms ago
MIUIOP(10022): ignore
MIUIOP(10033): allow
MIUIOP(10045): ignore
MIUIOP(10053): ignore
```

Repeated the same Home press, three-second wait, launcher confirmation, logcat clear, scenario and five-second request, with the same 120-second hard limit. Focus:

```text
mCurrentFocus=Window{f0eab24 u0 com.miui.home/com.miui.home.launcher.Launcher}
```

Started 2026-10-31T00:24:45.311583+09:00; finished 2026-10-31T00:25:04.354012+09:00; wrapper instrument-to-capture time 15.75 s; adb exit 0; no timeout. Instrumentation reported `OK (1 test)` and `Time: 15.233`. MainActivity launch log:

```text
10-30 23:24:50.494  1729  1754 I ActivityTaskManager: START u0 {flg=0x10008000 xflg=0x4 cmp=io.ensomi.android/.MainActivity (has extras)} with LAUNCH_MULTIPLE from uid 10283 from pid 24486 callingPackage io.ensomi.android (BAL_ALLOW_PERMISSION) result code=0
```

The short pass recorded frames. These are its reported values, after the check’s warm-up exclusion:

| Build / scenario | Frames | fps | displayRefreshHz | latePct (%) | workMsP50 | workMsP99 | meetsTarget | ok |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| debug / pacing-flat | 1448 | 115.6 | [120.0] | 0.55 | 4.266 | 8.775 | false | true |

Short-pass thermal before: `{"thermalStatus":0,"thermalHeadroom10s":0.05000000074505806,"powerSave":false}`; after: `{"thermalStatus":0,"thermalHeadroom10s":0.05000000074505806,"powerSave":false}`. The UI label “后台弹出界面” and the background-start explanation remain hypotheses; this run directly observed the numeric app-op change and the launch/frame results.

**3. Full rerun and audio**

Ran detached with a separate approver and a watchdog:

```text
sh scripts/phone-checks.sh -n -g audio,frames,lifecycle -l clean \
  -k /Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/ensomi-phone-kit \
  -o /Users/l/ensomi/ensomi--kpad-frames/artifacts/frames \
  > artifacts/frames/console.txt 2>&1
```

| Audio field | Reported value |
| --- | --- |
| seconds | 180 |
| routes | ["speaker/25079RPDCC","speaker/25079RPDCC"] |
| sampleRate | 44100 |
| outputSampleRate | "48000" |
| outputFramesPerBuffer | "256" |
| queuedMs | 400 |
| clockReads | 289 |
| frames | 9000 |
| frameRateHz | 50.0 |
| fitResidualSdMs | 0.288 |
| fitMaxResidualMs | 0.985 |
| driftPpm | [-0.3,-1.6] |
| pictureStepSdMs | 0.04 |
| pictureStepWorstMs | 1.184 |
| songTimeStepsBack | 0 |
| songTimeWorstBackMs | 0.0 |
| clockStepsBackHeld | 103 |
| timestampPositionsBack | 0 |
| presentationStepsBack | 0 |
| presentationWorstBackMs | 0.0 |
| presentationRepeats | 0 |
| underruns | 0 |
| timestamps | 4262 |
| timestampGapMsP50 | 42.66 |
| timestampGapMsMax | 295.31 |
| timestampAheadOfReadMsP50 | -10.67 |
| firstNewSongTimeAfterResumeMs | 479.4 |
| smoothnessGood | true |
| pictureStepGood | true |
| ok | true |

`fitResidualSdMs` is raw timestamp fit jitter; `driftPpm` contains the before/after-pause fit values. `timestampGapMsMax` is computed over all distinct timestamp pairs, without excluding the pause interval. `ok` is the check’s reported field.

**4. Frames**

Each scenario requests 60 seconds. `displayRefreshHz` lists the sampled refresh rates during play; work values are milliseconds. Thermal columns preserve the raw objects. `meetsTarget` is the pacing verdict: reported late share <0.5% and work p99 <3 ms. Instrumentation success is separate.

Build: **debug (`debuggable=true`)**

| Scenario | Lean | Frames | fps | displayRefreshHz | latePct (%) | workMsP50 | workMsP99 | meetsTarget | Thermal before | Thermal after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pacing-flat | landscape 0° | 7252 | 117.5 | [120.0] | 0.04 | 4.449 | 7.789 | false | `{"thermalStatus":0,"thermalHeadroom10s":0.04666665568947792,"powerSave":false}` | `{"thermalStatus":0,"thermalHeadroom10s":0.05000000074505806,"powerSave":false}` |
| pacing | landscape 40° | 7329 | 118.4 | [120.0] | 0.03 | 4.405 | 7.477 | false | `{"thermalStatus":0,"thermalHeadroom10s":null,"powerSave":false}` | `{"thermalStatus":0,"thermalHeadroom10s":0.0533333458006382,"powerSave":false}` |
| pacing-lean-max | landscape 55° | 7379 | 119.2 | [120.0] | 0.04 | 4.2 | 7.056 | false | `{"thermalStatus":0,"thermalHeadroom10s":null,"powerSave":false}` | `{"thermalStatus":0,"thermalHeadroom10s":0.05666669085621834,"powerSave":false}` |
| pacing-portrait | portrait 0° | 7365 | 119.0 | [120.0] | 0.04 | 4.265 | 7.299 | false | `{"thermalStatus":0,"thermalHeadroom10s":null,"powerSave":false}` | `{"thermalStatus":0,"thermalHeadroom10s":0.06666667014360428,"powerSave":false}` |

Build: **checks (`debuggable=false`)**

No metrics were recorded for this build: launch was refused at the start of the test, and the watchdog stopped it. Unavailable values are not zeros or verdicts.

| Scenario | Lean | Frames | fps | displayRefreshHz | latePct (%) | workMsP50 | workMsP99 | meetsTarget | Thermal before | Thermal after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pacing-flat | landscape 0° | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |
| pacing | landscape 40° | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |
| pacing-lean-max | landscape 55° | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |
| pacing-portrait | portrait 0° | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |

**5. Lifecycle**

| Trigger | wasPlaying | causes | panelShown | ok |
| --- | --- | --- | --- | --- |
| back | not measured | not measured | not measured | not measured |
| home | not measured | not measured | not measured | not measured |
| recents | not measured | not measured | not measured | not measured |
| notification-shade | not measured | not measured | not measured | not measured |
| screen-off | not measured | not measured | not measured | not measured |

Lifecycle was not reached; no trigger results were recorded.

**6. Mid-run installs**

The approver was derived from `/Users/l/ensomi/ensomi--kpad-run-2/artifacts/run-2/approver.py`. It retained the console/install gate on every adb call; its focus guard checked the full `com.miui.permcenter.install.AdbInstallActivity` class. Its only possible taps remained the supplied landscape `(1265,1196)` or portrait `(701,1760)` coordinates, followed if needed by one dump and the exact `继续安装` text bounds. No tap or dump occurred in this run.

| Intended install | Observed interval (JST) | Rotation | Coordinates tapped | First tap worked | Outcome |
| --- | --- | --- | --- | --- | --- |
| Checks build | 00:32:43.587–00:32:45.721 | Not measured; no install dialog observed | None | N/A; no tap | `Success`; console `installed` |
| Debug build again | Not reached | N/A | None | N/A | Not attempted before watchdog stop |

During the first install, the approver's two focus reads (00:32:43.641 and 00:32:44.716) both showed `com.miui.home/com.miui.home.launcher.Launcher`. An approval dialog was not observed. The install log was:

```text
Performing Streamed Install
Success
```

The checks-build install completed without approver input. The debug build was not restored; the checks build remains installed.

**7. Not-ok records and script summary**

Every `not-ok.jsonl` line, verbatim:

`not-ok.jsonl` was not created because the script did not reach Collecting. No verbatim lines are available.

Script summary:

None: the script stopped before producing its summary.

Final console line:

```text
Terminated: 15
```

**8. Files, time and remaining grant**

Results: `/Users/l/ensomi/ensomi--kpad-frames/artifacts/frames/partial-results/phone-checks/`

Archive: none created; the run stopped before collection/packing.

Console: `/Users/l/ensomi/ensomi--kpad-frames/artifacts/frames/console.txt`

State, both controls, app-op reads, approver log and watchdog records: `/Users/l/ensomi/ensomi--kpad-frames/artifacts/frames/`. Raw artifacts are local and uncommitted.

Full supervised rerun: 2026-10-31T00:25:20.009+09:00–2026-10-31T00:43:23.548+09:00; 1083.536 s; script exit 130; reached Collecting `false`; watchdog hang `true`. State capture through full-rerun completion: 1265.838 s. Watchdog checked the newest `instrument-*.txt` every minute and captured/stopped/pulled at its first poll after ten minutes without change.

Hang capture: `/Users/l/ensomi/ensomi--kpad-frames/artifacts/frames/hang/`; partial pull: `/Users/l/ensomi/ensomi--kpad-frames/artifacts/frames/partial-results/`; pull log: `/Users/l/ensomi/ensomi--kpad-frames/artifacts/frames/partial-pull.txt`. The checks build remains installed because the script stopped before the debug reinstall. No further checks or installs were run.

Final `appops get` read-back at 2026-10-31T00:43:37.404523+09:00 for the changed operation:

```text
MIUIOP(10021): ignore; rejectTime=+10m51s334ms ago
```

The allow grant is no longer set: the final mode is `ignore`. No additional app-op command was run after the initial grant.

**9. Deviations and unexpected observations**

The full requested sequence is incomplete because the prescribed watchdog stopped it. No extra permission, input, reinstall, wired/Bluetooth audio run, manual group, rebuild, or source change was attempted. The pre-existing `artifacts` symlink alone was replaced with a local directory; the linked checkout's artifacts were left in place. Device serials are replaced with `<serial>` in this report, and no song titles, account names or network identifiers are included.

Unexpected observations:

- The checks-build install reported success without an observed permission dialog; the approver made no taps. Thus rotation and first-tap success could not be measured. The second install was never reached.
- The checks-build test started at device time 23:32:47.581 and the next MainActivity launch was refused 34–36 ms later:

```text
10-30 23:32:47.581 25989 26143 I TestRunner: started: framesAtEachLean(io.ensomi.android.checks.FramePacingCheck)
10-30 23:32:47.615  1729  2769 D ActivityStarterImpl: MIUILOG- Permission Denied Activity : Intent { flg=0x10008000 xflg=0x4 cmp=io.ensomi.android/.MainActivity (has extras) } pkg : io.ensomi.android uid : 10283 tuid : 10132
10-30 23:32:47.617  1729  2769 I ActivityTaskManager: START u0 {flg=0x10008000 xflg=0x4 cmp=io.ensomi.android/.MainActivity (has extras)} with LAUNCH_MULTIPLE from uid 10283 from pid 25989 callingPackage io.ensomi.android (BAL_ALLOW_PERMISSION) result code=102
```

- At the 00:43:20.020 JST watchdog poll, `instrument-frames-checks-build.txt` was 339 bytes and unchanged for 633.977 s (the first one-minute poll after the ten-minute threshold). The captured focus was the launcher. The supervisor captured `hang/window.txt`, `hang/focus.txt`, `hang/screen.png` and `hang/logcat.txt`, stopped the script (exit 130), force-stopped Ensomi, and pulled seven result files successfully (3,283,813 bytes). No checks-build frame result, lifecycle result, `not-ok.jsonl`, final result-summary line or archive was produced. Five partial result lines exist: one audio and four debug frames, each with `ok:true`; all four full-pass frame results have `meetsTarget:false`.
- The final read-back showed `MIUIOP(10021): ignore`. Comparing operation modes before the full run and at the end, `10021` was the only observed mode difference (`allow` to `ignore`). **Hypothesis:** the APK replacement reset the operation. The operation was not read immediately at installation, so the exact reset time and mechanism were not measured. No command restored it to `default` or changed it again; the allow grant is no longer set.
- The successful short control logged a separate MIUI ConfirmStartActivity launch after MainActivity. No extra interaction was made with it:

```text
10-30 23:24:59.366  1729  3060 I ActivityTaskManager: START u0 {act=android.app.action.CHECK_ALLOW_START_ACTIVITY flg=0x800000 pkg=com.miui.securitycenter cmp=com.miui.securitycenter/com.miui.wakepath.ui.ConfirmStartActivity (has extras)} with LAUNCH_MULTIPLE from uid 10283 from pid 24486 callingPackage io.ensomi.android (BAL_ALLOW_VISIBLE_WINDOW) result code=0
```

- The requested short sample was five seconds; instrumentation reported 15.233 s and the reported post-warm-up frame count was 1,448. Its initial `asked` log reported 50.0 Hz, while its sampled `displayRefreshHz` was `[120.0]`. The full debug flat pass likewise initially logged 50.0 Hz and sampled `[120.0]`; the other full debug scenarios initially logged 120.0 Hz and sampled `[120.0]`. Every `asked` log requested 165.0 Hz only if seamless. No refresh setting was changed outside the existing script/checks.
- Three full debug scenarios reported `thermalHeadroom10s:null` before measurement; those values are preserved in the table. Audio reported 103 `clockStepsBackHeld` with zero reported song-time, timestamp-position and presentation steps back. No `blockUntilOffloadDrain` line was found in the saved clean-audio log.

Remaining work: checks-build pacing and all lifecycle triggers have no measurements from this rerun; the debug reinstall was not reached. Stopped under the brief's watchdog rule.
