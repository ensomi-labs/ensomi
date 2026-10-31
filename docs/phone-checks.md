# Phone checks: the runbook

The plan's phone checks ([plan.md](plan.md), the "Phone check" column; [gameplay.md](gameplay.md), "Measurements a
phone must make") need real phones: the emulator has one 60 Hz display, host audio and no touch screen. This runbook is
for a teammate with an Android phone and a computer. One script installs the app, makes sure the chart model is there,
runs the automated checks and packs their results into one file to send back; a checklist covers what needs a person.

The checks work on any arm64 phone or tablet with Android 13 or later; "phone" below covers a tablet too. The first two
devices are the Snapdragon 8 Gen 3 HyperOS phone, a Redmi K80 (`24117RK2CC`, `zorn`, HyperOS `OS3.0.307.0.WOKCNXM`,
120 Hz, held by Swd146296), and a Redmi K Pad tablet (`25079RPDCC`, `turner`, HyperOS `OS3.0.310.0.WAOCNXM`,
Dimensity 9400+). Where the plan or gameplay.md say "the Xiaomi" or "the HyperOS phone", they mean the Redmi K80.

## What you get

A kit (a zip; `scripts/make-phone-kit.sh` makes it): `phone-checks.sh`, this runbook, three APKs
(`app-debug.apk`, its checks `app-debug-androidTest.apk`, and `app-checks.apk`, the same app built not debuggable for
the frame pacing pass), `clicks/` (click tracks in every format), `songs/` (full-length songs, if the team shares any)
and `model/ensomi-r2/` (the model's 30 files and its manifest, for phones that cannot reach Hugging Face).

## Before you start

On the computer:

- **adb**, from Android's [platform-tools](https://developer.android.com/tools/releases/platform-tools), on `PATH`
  (`adb version` answers). Nothing else: no Gradle, no Android SDK, no Android Studio.
- **A POSIX shell**: Terminal on macOS or Linux. On Windows use **Git Bash** (from Git for Windows); `cmd` and
  PowerShell cannot run the script. In Git Bash, `adb.exe` must be on `PATH`.
- About 2 GB free where you run it.

On the phone:

1. **Developer options**: Settings › About phone, tap the OS version (on HyperOS "OS version") seven times.
2. In Developer options (on HyperOS: Settings › Additional settings › Developer options) turn on:
   - **USB debugging**;
   - **Install via USB** (HyperOS and MIUI only: it asks for a Xiaomi account, and a phone may need a SIM card; the
     Redmi K Pad, with no SIM slot, took both this toggle and the next. Without it every install fails with
     `INSTALL_FAILED_USER_RESTRICTED`);
   - **USB debugging (Security settings)** (HyperOS and MIUI only: lets adb tap and press keys, which the lifecycle
     check does).
3. Connect the USB cable, choose "File transfer" if asked, and allow the computer's USB debugging key ("Always allow").
   `adb devices` must list the phone as `device`, not `unauthorized`.
4. For the run: charge above 50 %, turn off battery saver, set the screen lock to None or Swipe (the lifecycle check
   turns the screen off and on), media volume at about a third, Do not disturb on (calls and notifications pause the
   game). Leave HyperOS's refresh rate setting as it is: the script records it.
5. For hand play on HyperOS (the checklist), turn off the three-finger gesture with
   `adb shell settings put system enable_three_gesture 0`, and restore it afterwards with `1`. Left on, it makes the
   game drop every finger on a chord: three fingers landing within 280 ms count as the gesture, and the app cannot
   block it. While it is off, three-finger screenshots and split-screen swipes do not work.

**The install prompt.** HyperOS (and some other systems) show their own dialog for an `adb install`. On the K Pad it is
titled `USB安装提示`. The grey `继续安装` button on the left approves. The blue `拒绝 (10)` button on the right refuses, and
the countdown is on that one: doing nothing refuses at zero. Leave the checkbox `拒绝后下次安装不再提示` unticked. Tap
`继续安装` each time. The English wording is not known. Installing the checks build over the debug build, the same key,
gave no prompt in the one case seen, so one may not come.

The script installs up to four times (the app, its checks, the checks build, the app again; `-n` skips only the first
two); it wakes the screen and tells you to look at the phone before each try. Unanswered, adb reports
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. The script asks again, up to three times for each install.
If the app or its checks still do not install, the run stops. A prompt missed in the `frames` group skips that pass and
does not stop the run: `lifecycle` and the archive still follow, and the last lines say what was skipped. If the debug
build could not go back on after the checks build, the checks build stays installed; put the debug build back
(`adb install -r -t app-debug.apk`) before the checklist.

This is also why Android Studio's or Gradle's `connectedDebugAndroidTest` does not work on these phones: the
dialog goes unanswered, the install fails with `INSTALL_FAILED_USER_RESTRICTED`, and Gradle still prints
`BUILD SUCCESSFUL`. The script installs with `adb install` and runs the checks with `adb shell am instrument`.

## The chart model

The checks that make charts need the Ensomi R2 model (`sed-i/ensomi-r2` at `r2-1.0.1`, 120 MB). The script takes care
of it, in this order:

1. If the pinned model is installed already, it is used.
2. **Otherwise the app downloads it from Hugging Face**, as the Download button does. This is the normal way.
3. **If the download fails** (huggingface.co is blocked in mainland China), the script copies the kit's
   `model/ensomi-r2` folder to the phone's `Download/ensomi-r2` and asks you to install it in the app: tap
   **Install from files**, open **Download**, open **ensomi-r2**, tap **Use this folder**, then **Allow**. The app copies
   and checks every file (a minute or two) and the script goes on by itself. (Android does not let an app pick the
   Download folder itself, only a folder inside it.)

The app accepts only the exact pinned package: the manifest's SHA-256 is pinned in the app, and every file is checked
against the manifest. So files from anywhere are fine (the kit, a mirror of the same tag, a colleague's copy); if they
are not exactly `r2-1.0.1`, the app says so and installs nothing. The results record which way the model came
(`model-install.txt`: `present`, `huggingface` or `files`).

## Running the checks

Unzip the kit, open a terminal in its folder, and run:

```sh
sh phone-checks.sh
```

It takes about 30 to 45 minutes. Leave the phone on the desk, screen up, unlocked, connected. The script keeps the
screen on while it runs. It prints each step; when it needs you (an install prompt, Install from files) it says so.
At the end it prints the file to send back, `phone-checks-<model>-<date>.tar.gz`. Each check has a time limit of 30
minutes (`-t SECONDS` changes it): at the limit the script stops the check and goes on to the next.

What it runs, each group in its own process (`-g` picks groups, comma-separated):

| Group | Check (`app/src/androidTest/.../checks/`) | Plan step | What it measures |
| --- | --- | --- | --- |
| runtime | `RuntimeCheck` | 4, 9 | The runtime's native library loads; calls without a model answer; UniFFI's API checksums read back (raw and low 16 bits, see below); the cost of each runtime call through JNA, and of a frame's worth |
| model | `ModelCheck` | 4 | The installed model is the pinned package (every file's SHA-256 again), how it came, how long the runtime takes to open it; Install from files on the phone with the runtime's own checks: a whole copy, and the refusals (another manifest, a copy cut short, a changed file) leaving nothing behind |
| decode | `ClickTrackDecodeCheck` | 3 | Each click track decoded by the app on the phone's decoders: the first click at 1000.08 ms (1025.14 ms for the MP3 without a Xing header), stereo equal to mono |
| generation | `GenerationCheck` | 5 (T10) | A whole song through the app's own path (decode, `prepare`, the chart), with `lowMemory` off and then on, each in its own process: the time of each stage, peak resident memory per stage, the least memory the system had left |
| determinism | `DeterminismCheck` | 5 | The same song, stars, seed and `lowMemory` twice: the same `.osu` (its SHA-256 compares phones too) |
| audio | `AudioClockCheck` | 6 | Three minutes of quiet clicks on the game's `AudioTrack` and clock, with a pause: timestamp jitter (line fit), drift, steps back, the song time between frames against the presentation time, the time to move again after the resume, underruns, the output used |
| frames | `FramePacingCheck` | 9 | 60 s of the sample game at 0°, 40° and 55° in landscape and in portrait: frame rate, the refresh rate the display ran at, missed vsyncs, frames still working at the next vsync, frames past their deadline, work per frame (p50, p99), in the debug build and again in the checks build (not debuggable, as a release); and what a long frame costs a `Choreographer` loop, by when it asks for the next vsync |
| lifecycle | `LifecycleCheck` | 7 | The game pauses on Back, Home, Recents, the notification shade and the screen turning off, and shows the pause panel when it comes back |

Each check logs one JSON object per result under the logcat tag `EnsomiPhoneCheck` and appends it to
`results/results.jsonl`; frame and timestamp series go to CSV files beside it. `"ok": false` marks a result that broke
a rule (song time going back, a click in the wrong place, two charts differing) or missed its target (frames late).
A check without what it needs (no songs, no click tracks) reports `"skipped"` and passes.

**Each output.** The audio check measures the output the phone plays on. After the main run, plug in wired headphones
(or a USB-C adapter) and run `sh phone-checks.sh -n -g audio -l wired`; then connect Bluetooth headphones and run
`sh phone-checks.sh -n -g audio -l bt`. `-n` skips the two installs at the start; the `frames` group still installs
the checks build and then the debug build, and their prompts still need an answer. Each run makes its own archive.

**Without songs** the generation checks make a synthetic four-minute song, so there is always a number; real songs
from the kit's `songs/` give the numbers that count.

## HyperOS refuses the checks' launch

On the K Pad, HyperOS would not let `FramePacingCheck` open the app. Its logcat shows
`ActivityStarterImpl: MIUILOG- Permission Denied Activity ... io.ensomi.android/.MainActivity`, then
`START ... result code=102`, and the check then hangs. `LifecycleCheck` launches the app in a similar way.

`adb shell appops set io.ensomi.android 10021 allow` fixed it, in a control differing only by that grant: `appops get`
showed `MIUIOP(10021): ignore` before and `allow` after, the launch gave `result code=0`, and frames were recorded.
Replacing the APK loses the grant, so on HyperOS and MIUI the script grants it again before every check, reads it back
and says so if it does not read `allow`. When exactly a replacement resets it was not measured.

The Settings toggle for the same thing is believed to be 后台弹出界面 in the app's permissions; that is unconfirmed.

## The checklist

What the script cannot do. Use the app normally (it is the debug build the script installed): add a song from the
kit (copy `songs/` to the phone first), make a chart, play. Before you start, run `adb logcat -G 16M` so the phone keeps
enough log; afterwards run `sh phone-checks.sh -n -g manual` to collect the app's logs and its last game's clock and
frame files, and send that archive too. Write the answers in a text file named `checklist.txt`, one line per item.

| # | Check | What to do | What to record |
| --- | --- | --- | --- |
| 1 | Touch to light (plan 7) | Film a finger tapping one lane in a game with the phone's slow-motion camera (240 fps or more) | The video |
| 2 | A full game by hand (plan 7, 9) | Play one whole song with your fingers, then run the `manual` collection before playing again | The results screen (screenshot); anything that felt late, wrong or uneven (the `manual` archive holds the game's late inputs, its clock log, its frame log and every touch's handling delay) |
| 3 | Multi-touch (plan 7) | In a game, play chords with four fingers and hold two lanes while tapping the others; then the same with both thumbs in landscape | Whether any lane stayed lit after your fingers left; whether any press was missed; a screen recording if something went wrong |
| 4 | Calibration by ear (plan 8) | Settings › Calibrate, three times on the speaker, three on wired headphones, three on Bluetooth | The suggestion of each run, per output (for example `speaker: +12, +10, +13`) |
| 5 | Change of output (plan 7) | During a game: plug in wired headphones; resume; unplug them; then connect and disconnect Bluetooth headphones | Whether the game paused each time, what the pause panel said, and whether the sound went on on the new output after Resume |
| 6 | Interruptions (plan 7) | During a game, have someone call you; set an alarm a minute ahead | Whether the game paused and came back paused |
| 7 | Song files (plan 2) | Add a song from Downloads and one from a cloud app (Xiaomi Cloud, Google Drive, Baidu Netdisk, whatever you use) with Add songs; make a chart of each and play it | Which provider; whether each added, made a chart and played; any message |
| 8 | Mobile data (plan 4) | Where Hugging Face is reachable: Settings › Chart model › Remove, then Download on mobile data, then again on Wi-Fi | The time each took, and whether it resumed after a dropped connection; skip where Hugging Face is blocked |
| 9 | The lean in hand (plan 9a) | Settings › Playfield: play the preview and a real game at 0°, 40° and 55° in landscape, and in portrait | Whether 40° reads at speed; whether the far notes are big enough; whether your thumbs land in their lanes; whether the top corners ever cover a lane |
| 10 | Edge gestures (gameplay.md, Input) | In landscape, play the outer lanes hard, sliding a little towards the edges | Whether Back or Home ever fired during play |
| 11 | Heat (gameplay.md) | Play for 10 minutes (the playfield preview loops), then at once run `sh phone-checks.sh -n -g frames -l hot` (it still installs two builds: answer the prompts) | Nothing more: the frames results hold the thermal state before and after |
| 12 | Refresh rate (gameplay.md) | Note the phone's display refresh setting (HyperOS: Settings › Display › Refresh rate) and whether battery saver was on | The setting's name and value |

## What to send back

- Every `phone-checks-*.tar.gz` the script made (the main run, each output, `manual`, `hot`).
- `checklist.txt` and the videos and screenshots it names.

The archive holds the script's own log (`script.log`: its settings, adb's install, push and pull output, and the
warnings and the summary, not all the console showed), the device's facts (`device.txt`: model, SoC, build, HyperOS
version; `getprop.txt`; `display.txt` with the display modes; `surfaceflinger.txt`; `refresh-settings.txt`; memory,
battery, thermal, power and audio dumps), how the model came (`model-install.txt`), each group's instrumentation output
and logcat, and `results/` (the JSON lines and CSV files). Nothing in it is personal: no songs, no accounts.

## Reading the results

- `results/results.jsonl`: one JSON object per line with `check` (`runtime-load`, `uniffi-checksums`,
  `runtime-call-cost`, `model-integrity`, `model-install-from-files`, `decode-click`, `decode-clicks`, `generation`,
  `determinism`, `audio-clock`, `frame-pacing`, `vsync-after-long-frame`, `lifecycle-pause`, `lifecycle`), `ok`, `debuggable` (false for the
  checks build), the `label` given and the check's own fields. `not-ok.jsonl` lists the results with `"ok": false`.
- Targets, from gameplay.md: clock residual sd under 0.5 ms and no step back (`fitResidualSdMs`, `songTimeStepsBack`);
  the picture step sd under 0.5 ms (`pictureStepSdMs`); under 0.5 % of vsyncs missed, under 0.5 % of frames late and
  work p99 under 3 ms at every lean (`missedPct`, `latePct`, `workMsP99`, `meetsTarget`); a frame's runtime calls
  under about 1 ms (`runtime-call-cost`'s
  `frameMsP99`). The frames numbers of the checks build are the ones that decide whether GLES is needed; the debug
  build's are slower by ART's debuggable mode.
- `uniffi-checksums`: the runtime's AAR is built with UniFFI's checksum comparison left out, because UniFFI 0.31 gets
  it wrong on AArch64 for values of 0x8000 and above. The check reads every checksum function as the bindings declare
  it. `low16Sha256` must be the same on every device and the emulator for the same AAR; `rawWithHighBits` counts
  values that came back with garbage above 16 bits, the AArch64 behaviour itself.
- Emulator runs show the checks work; their timing is the emulator's and says nothing about a phone.

## When something goes wrong

- `INSTALL_FAILED_USER_RESTRICTED`: Install via USB is off, or the install prompt was refused or timed out. Turn it on,
  run again, tap `继续安装`.
- `Install canceled by user`, after that code: the install prompt's countdown ran out, perhaps unseen. Watch the screen
  when the script says to look at the phone, and tap `继续安装`. On the K Pad with Install via USB on,
  `adb shell getprop persist.security.adbinstall` reads `1`.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: an Ensomi signed with another key is installed. `adb uninstall
  io.ensomi.android` (this removes its model too) and run again.
- `unauthorized` in `adb devices`: unplug, replug, accept the prompt on the phone.
- The phone drops off USB (`no devices/emulators found`, or the script says it is waiting for the phone): use another
  data cable, or a port with no hub. `transport_id` rising in `adb devices -l` means it keeps reconnecting. The script
  waits for the phone before each check and before collecting, then goes on. A check cut off by a drop is reported as
  `connection lost`, not as failed: run its group again with `-n -g`.
- A check says `timed out`: it hung, or needed more than its limit. The script stopped it and the app and went on; the
  last line lists it under `timed out`, apart from `did not pass` and `connection lost`. Read its `logcat-<name>.txt`
  and run its group again with `-n -g`. If it was only slow, add `-t`: `determinism` makes its chart twice, so a slow
  phone may need a longer limit there.
- `Permission Denied Activity` in a check's logcat: HyperOS refused the launch (see
  [above](#hyperos-refuses-the-checks-launch)), and `FramePacingCheck` then hangs until its limit. If the script also
  said `MIUIOP(10021) does not read allow`, its grant did not hold: try the Settings toggle.
- `The pull failed twice`: the results are still on the phone, and running the script again deletes them. Get them
  first with the `adb pull` it prints.
- Chords drop in a hand-played game on HyperOS, with `MiuiMultiFingerGestureManager: Pilfer pointers because gesture
  detected` in logcat and `ACTION_CANCEL` in the app: the three-finger gesture took the fingers, and the app cannot
  block it. Turn it off with `adb shell settings put system enable_three_gesture 0` and restore it afterwards with `1`
  (step 5 of "On the phone"). The script says so when it reads `1`, and `device.txt` records the value.
- The lifecycle check fails with no pause panel after `screen-off`: the phone has a screen lock; set it to None for
  the run.
- `Install from files` says the folder has no `ensomi-package.json`: pick the `ensomi-r2` folder itself, not Download.
- The script stops halfway: run it again; the model stays installed, and `-g` runs only the groups still missing.
