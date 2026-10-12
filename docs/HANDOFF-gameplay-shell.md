---
commit: 20bf67d
---

# Handoff: gameplay shell

State of branch `gameplay/shell` (from `dev/swift` `20bf67d`, not pushed): steps 1
to 6 of the plan are committed; the install (step 7) was skipped because the app
was running. Timing details and numbers are in [gameplay-timing](gameplay-timing.md).

## What passes

- `xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac -destination 'platform=macOS' test`:
  265 tests, 4 skipped (the 60 s autoplay and the hosted play screen need
  `TEST_RUNNER_ENSOMI_TIMING_RUN=1` and an awake display; both passed when run).
  Baseline before the work: 245 tests, 2 skipped.
- `EnsomiIOS` (iOS Simulator) and `EnsomiACRCloudDebugCLI` build.
- The Debug app builds at the README install path under
  `.build/EnsomiMacInstallDerivedData`; nothing was copied to `/Applications`.

## Next steps

Branch `gameplay/core` has since done 3 (calibration on the engine clock and event
timestamps), 4 (the Rust gameplay core, whose frame cost does not grow with the
chart) and the route-change and hidden-window parts of 5; 1 and 2 remain.

1. Install with the commands in `AGENTS.md` once `EnsomiMac` is not running; copy the
   current `/Applications/EnsomiMac.app` aside first.
2. Recalibrate the audio and visual offsets by ear. Stored offsets from the old
   clock are still applied; the built-in speakers need about the same audio value,
   a device that reports latency (Bluetooth) needs a much smaller magnitude.
3. The offset calibration screen (`Mania4KOffsetCalibrationView`) still stamps hits
   at handling time and measures from its own `AVAudioPlayer` tick; give it the
   event timestamp and the output latency.
4. `Mania4KJudgementEngine.advance` and `snapshot` walk every object each frame
   (2 to 4 ms in Debug on 4000 objects); index by time if long charts feel heavy.
5. Not handled: audio route changes during play, a window hidden or minimized
   mid-song (the display link stops, so the stream is not read ahead), displays
   above 60 Hz were not measured.
