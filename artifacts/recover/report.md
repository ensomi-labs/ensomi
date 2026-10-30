Redmi K Pad recovery and NetEase generation observations — 2026-10-30/31

Device model `25079RPDCC`, HyperOS `OS3.0.310.0.WAOCNXM`, Android 16. USB serial: `<serial>`. Mac times are JST (UTC+09); tablet log and file times are CST (UTC+08). JST is one hour ahead. The prior 105-second fixture is called “reference song”; downloaded files are called song 1–song 5. Names, titles, artists, and account/network identifiers are omitted.

Hang evidence was captured on 2026-10-30 at 23:57:54.882–23:57:58 JST, before stopping anything. `hang.png` shows a NetEase Cloud Music playlist and its player controls; the tablet clock reads 22:57. Window focus and focused app both name `com.netease.cloudmusic/com.netease.cloudmusic.music.biz.rn.activity.BgMainProcessRNActivity`; `mWakefulness=Awake`. Ensomi PID 15480 was alive; no separate uiautomator process was listed. The AndroidJUnitRunner instrumentation was active with one process and `mFinished=false`. The frame instrumentation file contained 339 bytes: the start status for `FramePacingCheck.framesAtEachLean`, ending in `INSTRUMENTATION_STATUS_CODE: 1`, with no completion status.

| Mac process | PID | Started JST, Oct 30 | Elapsed at capture |
| --- | --- | --- | --- |
| Prior run supervisor | 26259 | 23:25:11 | 32:43 |
| Prior install approver | 26261 | 23:25:11 | 32:43 |
| Prior phone-checks shell | 26262 | 23:25:11 | 32:43 |
| adb am instrument, FramePacingCheck | 27272 | 23:33:37 | 24:17 |

USB: no drop after 23:33 JST was observed in the available evidence. The saved start snapshot at 23:25, the snapshot at 23:46:21.917, and recovery at 23:57:54.916 all show one USB device in state `device`, `transport_id:50`. The same adbd PID 29586 handled frame instrumentation at 22:33:38.567 CST and recovery commands at 22:57 CST. No USB disconnect/reconnect entry was found after 22:33 CST. These are snapshots and retained logs, not a continuous USB trace.

The retained log records the following sequence (CST, October 30):

| Time CST | Observed log entry |
| --- | --- |
| 22:33:02–22:33:23 | NetEase activities resumed repeatedly |
| 22:33:31.618 | Launcher resumed |
| 22:33:34.259 | Ensomi MainActivity resumed |
| 22:33:34.512 | Audio result emitted |
| 22:33:35.640 | Ensomi MainActivity resumed again during audio-check cleanup |
| 22:33:36.564 | Launcher resumed |
| 22:33:38.880 | TestRunner started framesAtEachLean |
| 22:33:38.883 | UiDevice and UiAutomation initialized |
| 22:33:38.903 | ActivityStarterImpl: MIUILOG- Permission Denied Activity for io.ensomi.android/.MainActivity |
| 22:33:38.904 | ActivityTaskManager: START MainActivity from PID 15480; result code=102 |
| 22:34:56 onward | Settings activities resumed |
| 22:35:57.996 onward | NetEase activities resumed again |

No `FATAL EXCEPTION`, `Fatal signal`, `am_crash`, `am_anr`, or `ANR in` marker was found in the saved all-buffer log from 22:33 CST onward. Strings naming a crash-reporting library and a dumper waiting for a signal were present, but no crash/ANR event was recorded. No EnsomiPhoneCheck line remained in the hang snapshot: the script clears logcat before each check. The last two completed-check lines were recovered from the prior run’s saved per-check logs; they are reproduced below with the reference filename replaced.

```text
10-30 22:29:35.767 12528 12560 I EnsomiPhoneCheck: {"check":"determinism","label":"speaker","debuggable":true,"song":"reference song","synthetic":false,"songSha256":"e8400c1ec54dc31d39e41d7d9a4b7d608809287f89e8ac2cf77284b1e95f81d4","modelRevision":"r2-1.0.1","manifestSha256":"6e05317427cc24f3d46c0e8dcdd7a44078954437129c60f8797acb80bea6a1fb","lowMemory":false,"stars":4.0,"seed":"20261029","firstOsuSha256":"e79435e98a105b209e0f3ea970dbe4d9c9614493a5dfd0e6be5dce3ade571cc1","secondOsuSha256":"e79435e98a105b209e0f3ea970dbe4d9c9614493a5dfd0e6be5dce3ade571cc1","notes":1093,"firstSeconds":20.97,"secondSeconds":21.73,"same":true,"ok":true}
10-30 22:33:34.512 12659 12702 I EnsomiPhoneCheck: {"check":"audio-clock","label":"speaker","debuggable":true,"seconds":180,"routes":["speaker/25079RPDCC"],"sampleRate":44100,"outputSampleRate":"48000","outputFramesPerBuffer":"256","queuedMs":400,"underruns":1,"clockReads":154,"clockStepsBackHeld":47,"frames":7252,"frameRateHz":78.9,"songTimeStepsBack":0,"songTimeWorstBackMs":0.0,"presentationStepsBack":1,"presentationWorstBackMs":0.0,"presentationRepeats":0,"pictureStepSdMs":0.057,"pictureStepWorstMs":1.145,"pictureStepGood":true,"firstNewSongTimeAfterResumeMs":null,"timestamps":2252,"timestampPositionsBack":0,"timestampGapMsP50":42.65,"timestampGapMsMax":145832.3,"timestampAheadOfReadMsP50":-6.71,"fitResidualSdMs":0.35,"fitMaxResidualMs":1.398,"driftPpm":[8.7],"smoothnessGood":true,"files":["audio-clock-speaker-frames.csv","audio-clock-speaker-timestamps.csv"],"ok":true}
```

Guess, not established cause: the denied MainActivity launch may have left the frame check waiting for its activity. NetEase foreground activity and download writes overlap the run, but they do not establish why the launch was denied or the exact blocked call. No thread dump was taken.

Stopping and recovery: at 23:58:16.421–23:58:16.427 JST, the four prior-run Mac processes were first frozen with SIGSTOP and then killed with SIGKILL. This also prevented the old shell’s exit trap from changing the device’s stay-awake setting. `adb shell am force-stop io.ensomi.android` completed successfully at 23:58:16.508. Follow-up process and instrumentation queries returned no Ensomi/uiautomator process or active instrumentation. Mac matches remaining were the current recovery job’s command text, not the old run. The tablet results were then pulled successfully before any new script invocation: 4 files, 703,549 bytes, including 20 lines in `results.jsonl`, both audio CSVs, and the checksum text file.

Recovered results: 19 records have `ok:true`; one informational `model-installed` record has no `ok` field. There are zero `ok:false` records. The prior instrumentation files report passes for runtime, model, decode, generation, determinism, and audio.

| Group | JSON lines | Recorded result |
| --- | --- | --- |
| Runtime | 3 | 3 ok:true; instrumentation 3 tests passed |
| Model | 3 | 2 ok:true; installed:true informational record; instrumentation 2 tests passed |
| Decode | 10 | 9 format cases and aggregate ok:true; instrumentation 1 test passed |
| Generation | 2 | Both lowMemory settings ok:true |
| Determinism | 1 | same:true, ok:true |
| Audio | 1 | ok:true |
| Frames | 0 | Started, no result recorded |

Runtime details: `initMs=30.9`, 27,200 measured frames; frame mean 1.417 ms, p50 1.347 ms, p99 2.54 ms, maximum 145.95 ms. `underOneMsPerFrameAtP99=false` while `ok=true`. UniFFI contract 30, 52 checksums, `stableAcrossCalls=true`, checksum SHA-256 `3c85aeaba1cb97e5a66ffee0829e18b9071a1a8309a5fe1fddf64e0c19d90729`. Model revision `r2-1.0.1`, pinned manifest `6e05317427cc24f3d46c0e8dcdd7a44078954437129c60f8797acb80bea6a1fb`; 30 files, 120,377,244 bytes, no hash mismatches. File-install check 0.95 s / 126.9 MB/s; integrity hashing 0.09 s / 1,343.0 MB/s; model open 813.9 ms.

| Decode case | Decode ms | Click error ms | ok |
| --- | --- | --- | --- |
| WAV 48 kHz | 509.8 | 0.0 | true |
| MP3 tagged delay | 752.0 | 0.007 | true |
| MP3 without Xing | 714.6 | 0.007 | true |
| MP3 VBR | 666.4 | 0.007 | true |
| FLAC | 262.4 | 0.007 | true |
| M4A/AAC | 954.1 | 0.007 | true |
| Ogg/Vorbis | 756.4 | 0.007 | true |
| Opus | 888.7 | 0.021 | true |
| WAV 44.1 kHz | 224.2 | 0.007 | true |

All nine decode records have `stereoEqualsMono=true`; aggregate tolerance is 0.5 ms and `wrong=[]`. No runtime, model, or decode `ok:false` line exists to reproduce.

Reference-song generation used 2,518,746 input bytes, input SHA-256 `e8400c1ec54dc31d39e41d7d9a4b7d608809287f89e8ac2cf77284b1e95f81d4`, `stars=4.0`, `seed="20261029"`. Times and memory below are the fields as reported. MB means decimal megabytes. Model opening is measured separately before the generation total; decoding includes input hashing. Peak RSS is sampled at 50 ms intervals.

| lowMemory | Model open ms | Decode s | Prepare s | Chart s | Total s | Peak RSS MB | Notes | Holds | ok |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| false | 823.7 | 13.41 | 7.19 | 1.4 | 22.01 | 1825.6 | 1093 | 144 | true |
| true | 844.3 | 7.07 | 10.72 | 1.46 | 19.26 | 1039.8 | 1093 | 144 | true |

| lowMemory | Peak RSS by phase MB | Chart SHA-256 | Equals both emulators |
| --- | --- | --- | --- |
| false | `{"loading":287.3,"timing":1825.6,"rows":473.7}` | `e79435e98a105b209e0f3ea970dbe4d9c9614493a5dfd0e6be5dce3ade571cc1` | true |
| true | `{"loading":294.1,"timing":1039.8,"rows":472.4}` | `e79435e98a105b209e0f3ea970dbe4d9c9614493a5dfd0e6be5dce3ade571cc1` | true |

Both reference charts equal the supplied emulator SHA-256 at the same stars and seed, also matching the saved comparison for `ensomi-36` and `ensomi-36-16k`. Recovered determinism: `lowMemory=false`, `same=true`, `ok=true`, 1,093 notes; first 20.97 s and second 21.73 s. Both chart hashes are `e79435e98a105b209e0f3ea970dbe4d9c9614493a5dfd0e6be5dce3ade571cc1`.

Recovered audio output: `routes=["speaker/25079RPDCC"]`, label `speaker`; source sample rate 44,100 Hz, output sample rate `"48000"`, output frames per buffer `"256"`, queued audio 400 ms. The record’s requested duration is 180 s; TestRunner started at 22:29:36.342 CST, emitted the result at 22:33:34.512 and finished the test at 22:33:34.515 (238.173 s wall time).

| Audio field | Raw value |
| --- | --- |
| clockReads | 154 |
| frames | 7252 |
| frameRateHz | 78.9 |
| timestamps | 2252 |
| fitResidualSdMs | 0.35 |
| fitMaxResidualMs | 1.398 |
| driftPpm | `[8.7]` |
| timestampGapMsP50 | 42.65 |
| timestampGapMsMax | 145832.3 |
| timestampAheadOfReadMsP50 | -6.71 |
| timestampPositionsBack | 0 |
| clockStepsBackHeld | 47 |
| songTimeStepsBack | 0 |
| songTimeWorstBackMs | 0.0 |
| presentationStepsBack | 1 |
| presentationWorstBackMs | 0.0 |
| presentationRepeats | 0 |
| underruns | 1 |
| pictureStepSdMs | 0.057 |
| pictureStepWorstMs | 1.145 |
| firstNewSongTimeAfterResumeMs | null |
| pictureStepGood | true |
| smoothnessGood | true |
| ok | true |

The retained event log also has a 22:33:34.249 CST `dvm_lock_sample` for EnsomiAudioWriter: `145812` ms at `AudioTrack.blockUntilOffloadDrain`, with `AudioTrack.startImpl` in the record. This is recorded alongside the large timestamp gap and wall time; no causal conclusion is drawn.

No debug frame scenario produced a JSON result or a frame CSV before the stop:

| Scenario | Frame rate | Refresh rate | Late-frame share | Work p50 | Work p99 | meetsTarget |
| --- | --- | --- | --- | --- | --- | --- |
| Landscape 0° | not recorded | not recorded | not recorded | not recorded | not recorded | not recorded |
| Landscape 40° | not recorded | not recorded | not recorded | not recorded | not recorded | not recorded |
| Landscape 55° | not recorded | not recorded | not recorded | not recorded | not recorded | not recorded |
| Portrait 0° | not recorded | not recorded | not recorded | not recorded | not recorded | not recorded |

Download inventory: the audio files were in `/sdcard/Download/netease/cloudmusic/Music/`. The four requested roots were inspected. Only metadata was collected for encrypted/cache files; none was copied, decrypted, or converted. Per-file directories, extensions, sizes and modification times are saved locally in `music-inventory.json`. The extension counts are recursive within each root:

| Directory searched | File count | Counts by extension / observation |
| --- | --- | --- |
| /sdcard/Download/netease/cloudmusic/ | 191 | .jpg: 3, .ncm: 164, .mp3: 24 |
| /sdcard/netease/cloudmusic/ | 0 | Directory absent |
| /sdcard/Music/ | 2 | (none): 2 |
| /sdcard/Android/data/com.netease.cloudmusic/ | 1628 | (none): 575, .cnt: 667, .zip: 1, .webp: 4, .json: 28, .png: 284, .glsl: 4, .ttf: 1, .xcrash: 3, .config: 1, .nmsf: 1, .nmsfi: 1, .text: 54, .lock: 2, .log: 1, .jpg: 1 |

The download root contains 188 music files: 24 `.mp3`, 164 `.ncm`; its other 3 files are JPEGs. `/sdcard/Music/` contains two extensionless thumbnail metadata files, no plain audio. The app-data tree contains no file with one of the six allowed plain-audio extensions. Counts of `.uc` and `.uc!` are both zero in these roots. The `.nmsf`/`.nmsfi` pair and other app/cache files were left untouched. A broader `/sdcard` search was unnecessary because the download directory held plain audio.

| Extension | Count | Earliest mtime CST, Oct 30 | Latest mtime CST, Oct 30 | Equivalent JST interval |
| --- | --- | --- | --- | --- |
| .mp3 | 24 | 22:31:38.039246133 | 22:41:13.643246167 | 23:31:38.039–23:41:13.643 |
| .ncm | 164 | 22:31:33.255246132 | 22:41:17.995246167 | 23:31:33.255–23:41:17.995 |

These modification times overlap audio (22:29:36.342–22:33:34.515 CST) and the pending frames-debug check (started 22:33:38.880 CST). Seven MP3 and 53 NCM modification timestamps precede the frame start; the remaining 17 MP3 and 111 NCM timestamps fall after it. The file timestamps therefore precede the owner’s approximate 23:33 JST download start by about 1 minute 27 seconds. Modification times identify file writes, not complete network-transfer intervals. Runtime, model, decode, generation and determinism finished before the earliest of these music timestamps; determinism finished at 22:29:35.774 CST. All downloaded music timestamps precede this recovery’s new generation run.

Five plain MP3s were selected across available sizes; ffprobe confirmed all exceed three minutes. No other allowed audio format was found in the searched roots, so a spread of formats was unavailable. Each is stereo MP3 at 320,000 bit/s; songs 1–4 are 44,100 Hz and song 5 is 48,000 Hz. Exact bytes, ffprobe duration, 16-hex SHA-256 prefix, and source modification time follow. Full input hashes for these five files and source-name mappings remain only in local artifacts.

| Song | Format | Duration s | Bytes | SHA-256 prefix | Source mtime CST, Oct 30 |
| --- | --- | --- | --- | --- | --- |
| song 1 | mp3 | 189.486100 | 7682112 | `a677044471e141b9` | 22:32:58.015246137 |
| song 2 | mp3 | 224.240000 | 9096214 | `6b92203d9075232c` | 22:33:58.943246141 |
| song 3 | mp3 | 256.509388 | 10293769 | `4169004f48a102da` | 22:35:42.075246147 |
| song 4 | mp3 | 290.333333 | 11805081 | `f712865a4428fcf0` | 22:39:50.015246162 |
| song 5 | mp3 | 314.602667 | 12605917 | `49049c657475d978` | 22:40:54.447246166 |

New checks used the existing installed debug app, runner, and pinned model. A local kit holds only the five chosen files in `songs/`, with symlinks to the original kit’s three APKs and `clicks/`. The original kit was not modified. The existing script ran detached with `-n -g generation,determinism -l netease`; one USB device was listed, so no `-s` was added. The script itself is unchanged. The watchdog polls `instrument-*.txt` every 60 seconds and captures evidence, stops, and pulls results if the newest output has been unchanged for at least 600 seconds.

New result file: 11 lines. Generation parameters: `stars=4.0`, `seed="20261029"`, `debuggable=true`, one process per song and lowMemory setting. Stage-time and RSS definitions are the same as above. Instrumentation files reporting `OK`: 11/11.

| Song | lowMemory | Model open ms | Decode s | Prepare s | Chart s | Total s | Peak RSS MB | Notes | Holds | ok |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| song 1 | false | 2292.1 | 24.96 | 28.35 | 6.03 | 59.34 | 1846.2 | 1576 | 313 | true |
| song 1 | true | 2243.3 | 22.96 | 47.92 | 6.15 | 77.04 | 1041.7 | 1576 | 313 | true |
| song 2 | false | 2276.6 | 26.2 | 31.5 | 7.44 | 65.13 | 1869.5 | 2436 | 562 | true |
| song 2 | true | 2208.9 | 29.26 | 52.62 | 7.57 | 89.46 | 1071.5 | 2436 | 562 | true |
| song 3 | false | 2197.5 | 34.48 | 36.98 | 9.07 | 80.54 | 1864.6 | 3249 | 126 | true |
| song 3 | true | 2202.9 | 26.64 | 61.16 | 9.22 | 97.02 | 1073.5 | 3249 | 126 | true |
| song 4 | false | 2243.6 | 38.52 | 39.94 | 7.68 | 86.15 | 1904.1 | 2225 | 310 | true |
| song 4 | true | 2214.3 | 38.35 | 66.16 | 7.92 | 112.42 | 1096.4 | 2225 | 310 | true |
| song 5 | false | 2231.7 | 33.5 | 43.77 | 8.83 | 86.1 | 1927.8 | 2902 | 761 | true |
| song 5 | true | 2262.7 | 45.32 | 73.8 | 9.01 | 128.13 | 1117.5 | 2902 | 761 | true |

| Song | lowMemory | Peak RSS by phase MB | Chart SHA-256 |
| --- | --- | --- | --- |
| song 1 | false | `{"loading":277.8,"timing":1846.2,"rows":464.4,"done":227.8}` | `287367699284293824d2868f24e5674b1ae4b535b98e77c64546f5276381b2b0` |
| song 1 | true | `{"loading":270.9,"timing":1041.7,"rows":475.3}` | `287367699284293824d2868f24e5674b1ae4b535b98e77c64546f5276381b2b0` |
| song 2 | false | `{"loading":293.6,"timing":1869.5,"rows":487.9}` | `0763c7bfde2216455705637f33a325efc06d5a3e852979d6d153a0442c87a726` |
| song 2 | true | `{"loading":256.3,"timing":1071.5,"rows":491.8}` | `0763c7bfde2216455705637f33a325efc06d5a3e852979d6d153a0442c87a726` |
| song 3 | false | `{"loading":278.5,"timing":1864.6,"rows":525.4,"export":334.0}` | `0d4109379ec389c3d70b0a8cd64405b74b2c754c7ea95b9122a07dc8577465dc` |
| song 3 | true | `{"start":193.9,"loading":261.2,"timing":1073.5,"rows":512.9,"export":329.1}` | `0d4109379ec389c3d70b0a8cd64405b74b2c754c7ea95b9122a07dc8577465dc` |
| song 4 | false | `{"loading":316.3,"timing":1904.1,"rows":526.3}` | `484e86326bcb1ff573561089e326e8ab62a70a82087e8b94c170e7be9c51bb1f` |
| song 4 | true | `{"loading":321.5,"timing":1096.4,"rows":536.1}` | `484e86326bcb1ff573561089e326e8ab62a70a82087e8b94c170e7be9c51bb1f` |
| song 5 | false | `{"loading":294.1,"timing":1927.8,"rows":548.4}` | `23d9f192b31278be8d7c32730074555879fa5fd58aa6adc775abfbb6b8a2ca2b` |
| song 5 | true | `{"loading":321.8,"timing":1117.5,"rows":555.7,"done":236.6}` | `23d9f192b31278be8d7c32730074555879fa5fd58aa6adc775abfbb6b8a2ca2b` |

For all five songs, the off/on chart SHA-256 values are equal: `true`.

Determinism ran on song 1 only, as the script selects the first kit song: `lowMemory=false`, `stars=4.0`, `seed="20261029"`, `same=true`, `ok=true`; 1576 notes, first 59.02 s, second 50.54 s. First chart SHA-256 `287367699284293824d2868f24e5674b1ae4b535b98e77c64546f5276381b2b0`; second `287367699284293824d2868f24e5674b1ae4b535b98e77c64546f5276381b2b0`.

New `not-ok.jsonl`: 0 lines. It is empty; no lines to reproduce.

The stopped prior run never reached collection and created no `not-ok.jsonl`; scanning the recovered 20 lines finds zero `"ok":false` lines. It emitted no final summary line.

New script’s final result-summary line (verbatim):

```text
11 results, 0 not ok
```

Final console line (verbatim):

```text
Done. Send back this file: /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover/phone-checks-25079RPDCC-20261031-000009.tar.gz
```

Absolute artifact paths on this Mac:

| Artifact | Absolute path |
| --- | --- |
| Hang evidence and complete all-buffer log | /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover |
| Recovered prior results | /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover/results |
| Recovered prior JSON | /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover/results/results.jsonl |
| Chosen songs | /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover/songs |
| Isolated kit | /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover/kit |
| New results | /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover/phone-checks-25079RPDCC-20261031-000009/results |
| New archive | /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover/phone-checks-25079RPDCC-20261031-000009.tar.gz |
| Console | /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover/console.txt |
| Watchdog polls | /Users/l/ensomi/ensomi--kpad-recover/artifacts/recover/watchdog-polls.jsonl |

New script start: `2026-10-31T00:00:08.884534+09:00`; end: `2026-10-31T00:17:24.949598+09:00`; elapsed `1036.065` seconds; exit code `0`. Watchdog polls: 18; greatest observed unchanged age 110.062 seconds; triggered: false.

Recovery evidence start: `2026-10-30T23:57:54.881703+09:00`; report assembled: `2026-10-31T00:18:28.164643+09:00`; total recovery through report assembly: 1233.283 seconds. The prior script had run from October 30 23:25:11 JST to the force-stop at 23:58:16.508 JST (33 minutes 5.508 seconds).

Archive validation: `1305060` bytes, SHA-256 `c7aa6fd6a156eabf0ad7795605bbe28de790c694140e88a315b527f0bf3a43bb`. The tar/gzip listing completed successfully; the archived results JSON is byte-for-byte equal to the collected results JSON.

Deviations and unexpected observations: the existing script unconditionally calls `svc power stayon usb`, `svc power stayon false` and `logcat -G 16M`, including with `-n`. To satisfy the explicit prohibition on device-setting changes, a local adb guard skipped these three calls and logged them, while forwarding the check commands to the supplied adb. The old script was killed without executing its restore trap for the same reason. The guard and watchdog are uncommitted helpers under this artifact directory; no repository source, existing scripts, or docs were edited. This differs from running the supplied command with an unguarded adb on PATH.

Stay-awake global setting before and after the new run: `2` / `2`. Post-run Ensomi/uiautomator process and active-instrumentation queries were empty. The script SHA-256 matches its pre-run value. Only the requested generation and determinism classes were launched; no install, uninstall, input injection, or setting write was executed.

Other observations and limits: no frame scenario completed; all plain music found in the searched roots was MP3; determinism covers only song 1. The prior audio record contains one underrun, 47 held backward clock steps, one backward presentation step, and a 145,832.3 ms maximum timestamp gap despite `ok=true`. Runtime’s p99-under-1-ms flag is false despite `ok=true`. The prior supervisor and install approver survived the stopped owner job until this recovery killed them. The artifact symlink was replaced with a worktree-local directory; its original target was left untouched. Original tablet songs and the source kit were left in place. No build or additional check group was run. The hang’s exact blocked call and frame measurements remain unestablished; no further run is included in this task.
