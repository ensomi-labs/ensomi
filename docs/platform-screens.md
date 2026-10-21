---
commit: 3000fc33f58e067d52ccc1d7b55cc26a13350ad8
---

# Windows and Android screens: proposed specification

This document describes the screens the Windows and Android clients must present. It is a
proposal: the Apple apps are the source for every behavior, control and message below, and this
document restates them so that a client can be built and reviewed without reading SwiftUI. The
layering that puts these screens on top of the shared core is in
[platform clients](platform-clients.md); the conventions they follow are in
[design guidelines](design-guidelines.md), which remains the authority on look and on Apple's
guidance.

Where a number appears, it is the Apple client's value in points. Use the same number of dp in
Compose: the two units are the closest correspondence, and the Apple sizes were chosen by eye.

## Screens and their source

| Screen | Apple source | Windows | Android |
| --- | --- | --- | --- |
| S1 Workbench | `EnsomiWorkbenchView` | sidebar + detail | bottom navigation |
| S2 Library | `LocalLibraryDashboardView` | page | page |
| S3 Play setup | `Mania4KSetupView` in `Mania4KPlayExperienceView` | page | page |
| S4 Play scene | `Mania4KPlaySceneView`, `Mania4KPlayHUDBar` | page and window | full screen |
| S5 Settings | `EnsomiSettingsView` | own window | sheet |
| S6 Offset calibration | `Mania4KOffsetCalibrationView` | sheet over Settings | sheet over Settings |
| S7 Chart Inspector | `Mania4KChartInspectorView` | own window | — |
| S8 Listen & Play | `LiveGenerationDemoView` | own window | — |
| S9 Recognition Flow | `LiveRecognitionSyncWindow` | own window, Debug builds | — |

Android has no S7, S8 or S9: the iOS client has none either, and it is Android's reference.

## S1 Workbench

**Windows.** A permanent sidebar (160–190 dp) lists Library and Play; the detail area shows the
selected page. The window opens at 1100×760 and does not go below 640×480. The toolbar holds Live
Demo, Settings and, in Debug builds, Recognition Flow. A Debug menu carries the same Recognition
Flow command with its shortcut.

**Android.** A bottom navigation bar holds Library and Play; a Settings action in the top bar
opens S5 as a sheet. Navigation titles are "Library" and "Play".

**States.** No selection falls back to Library. Both destinations stay reachable when their
content is empty.

**Copy.** "Library", "Play", "Settings", "Live Demo", "Recognition Flow", "Ensomi".

## S2 Library

One scrolling page, grouped form.

| Group | Content |
| --- | --- |
| Music Folders | One row per folder: path, truncated in the middle, and a remove action. Removing a folder keeps its audio files on disk. Empty state: "Add Your Music", "Choose a folder of audio files to use for recognition and play.", with the primary action "Add Music Folder…". The add action stays visible below the rows. |
| Library Error | The error text with a warning symbol, selectable. Present only on failure. |
| Scan Status | Discovered, Indexed, Failed, Missing counts; Last scan, "Never" before the first; "Rescan Library" and "Refresh Status", both disabled with no folders. |
| Library Tools | A disclosure holding manual resolution (title, artists, album, ISRC, duration; "Resolve Result" with candidates). The fixture recorder is Apple-only and is not ported. |

**Platform.** Windows uses a folder picker and a plain path. Android uses the system folder picker
and keeps a persistable grant per folder; a revoked grant is reported in Library Error and the
folder stays listed until removed.

**Acceptance.** Adding a folder starts indexing without another action; the counts update after a
rescan or a refresh; removing a folder leaves the files on disk.

## S3 Play setup

Two panels side by side at 800 dp and wider — input on the left, at most 460 dp, session on the
right — and stacked below that width. The header is compact below 600 dp.

**Header.** "Ensomi", "Choose how you want to play", and a badge reading "4K" with the session
status word. The badge's outline takes the ready colour when the setup can generate.

**Input panel.**

1. "Game Mode". The catalogue holds one mode, "Generate a Chart" / "Create a chart from a local
   song"; the picker stays because the catalogue can grow. Choosing one replaces the buttons with a
   summary, and a Clear action returns to the catalogue.
2. "Where to generate": a segmented control over the engines, "On this device" and "Chart server".
3. The model controls, when the device engine is chosen and no model is installed: download with
   progress and cancel, "Choose Folder…", "Use Downloaded Model", "Remove Download".
4. "Audio": the chosen file name and a "Choose" action; a refused file reports its reason.
5. "Song length: m:ss" once the file reads.
6. "osu!mania star difficulty", stepped by 0.1 inside the model's range, which comes from the
   engine's capabilities; unavailable until it answers.
7. "Seed", blank meaning a new chart each time, with "Blank makes a new chart each time. The same
   song, stars, sections and seed make the same chart again."
8. "Sections": per section, start and end in `mm:ss`, a kind picker, then either a strength slider
   from 0 to 1 or a section star target. Sections last 8–32 seconds, stay inside the song and do
   not overlap; a violation is shown before Generate is enabled. "Add Section".
9. The engine's five phases while generating, in order: "Loading audio", "Finding the beat",
   "Placing notes", "Arranging lanes", "Finishing", each shown as done, in progress or waiting.
   "Connecting to the chart server" precedes the first phase while a client connects.
10. The result: "Chart ready", "N notes: X taps, Y long notes", the seed with "Use Seed", and the
    server's archive path when the server wrote one.
11. Actions: "Generate" (primary, disabled until the setup is valid) or "Stop" while generating;
    after a result, "Play" (primary), "Save .osu…", "Save .osz…", "Generate Again".

**Session panel.** The status badge (WAITING, READY, GENERATING, ERROR), the current phase, six
stat tiles — Song, Stars, Seed, Sections, Notes, Model — the engine name with its status sentence,
and the lane preview, at least 360 dp tall. Tiles wrap to one column when the panel is narrow.

**Copy for stopped and failed.** "Stopped. No chart was made." and "The chart could not be made."
above the engine's own message.

**Acceptance.** Generate is unreachable while the audio, the star target, a section or the seed is
invalid; the same song, stars, sections and seed produce the same chart; a finished chart saves as
`.osu` and `.osz` with the same bytes as the Apple client.

## S4 Play scene

The stage is dark, from the play palette, and fills the page.

- **HUD bar.** Title and subtitle, the accuracy figure with the label "Accuracy", pause and quit.
  Below 800 dp the title takes its own line and the controls share the next one.
- **Playfield.** At most 440 dp wide, centred, 16 dp above the bottom: four lanes, a key tray, lane
  press lights and the hit feedback. Key labels sit in the tray; on Android the tray carries the
  touch targets instead of key names.
- **Overlays.** "Loading" with the chart source and Cancel; "Paused", "Resume when you're ready.",
  Resume and Quit; "Play stopped" with the failure and Back to Setup; "Results" with accuracy, max
  combo and misses, and Back to Setup. A paused stage dims behind its overlay.

**Android.** Play is landscape by preference, keeps the screen on, pauses on a back gesture, and
claims audio focus. Four fingers on four lanes replace the keyboard.

**Acceptance.** Judgement happens at the audio time of the input, not at the frame time; a paused
session neither judges nor advances; the results screen reports the core's score.

## S5 Settings

Grouped form, one window on Windows (560×680, not below 480×560) opened from the toolbar and the
standard shortcut, and a sheet with Done on Android. Sections, in order:

| Section | Controls |
| --- | --- |
| Gameplay | Scroll speed 1–40 in 0.1 steps, where a note takes 11485 ms divided by the speed to reach the line; judge difficulty A–E. |
| Sound and timing | Music volume as a fraction of the song's own level; audio offset for the current output device, named in the control; visual offset, which moves display only; "Calibrate Timing…". |
| Chart model | The model controls of S3, the licence the package gives with its notice sheets, and the build's commit on Windows as on macOS. |
| Keyboard | Four lane keys, default D F J K, captured one at a time with Escape cancelling, and Restore Defaults. Windows only. |
| Developer | "Open Chart Inspector…". Windows only, and the last section, as on macOS. |

Sound and timing is disabled while a game is in progress, with a footer saying it can be changed
when the game ends. Android omits Keyboard and Developer; its play input is touch.

## S6 Offset calibration

A modal sheet over S5, dark, not dismissible by accident. It names the device whose offset it
edits, plays a click track against a note stage, and shows the current hit error as it goes.

- The pending audio and visual offsets step by key or button, with the current value beside them.
- A suggestion is the negative mean of the successful hit errors; "Use Suggestion" applies it.
- Presets: the active preset, a name field, Add, and Delete for a saved preset.
- Apply stores the result on the current output device and returns to S5; Cancel discards.

**Acceptance.** The applied offset is the one the next run of play uses, it is stored per output
device, and it survives a restart.

## S7 Chart Inspector (Windows)

A window, opened from Settings → Developer. Header: title, artist, difficulty, note count, a
difficulty picker when the archive holds several readable charts, and "Open…", which takes one
`.osz` or one `.osu` with its audio. Empty state explains what to open; a refused chart is
described in text with a symbol and cannot be chosen.

Transport, in order: previous note, step back, play or pause as the primary action, step forward,
next note; the position and length as `m:ss.mmm`; a rate menu from 0.5× to 1.5×; a scroll-speed
step that changes the same setting play uses. A line names the audio offset with its device, the
visual offset and the volume, all owned by S5. Dragging the scrubber pauses and releasing resumes.
Notes pass the line and slip under the key caps because nothing is judged.

## S8 Listen & Play (Windows)

A window from the Live Demo toolbar action. It recognizes the music nearby, resolves it to a local
recording, generates that recording's chart on the device while sync locks, and plays from the
heard position at final lock.

Content: the listening source (Microphone, System audio), "Retry / Start" and "Stop", the resolved
recording with "Use this recording", the star and seed used for this chart ("This chart: seed N",
"Use Seed"), the recognition and lock status, and the play stage once it starts. Without a model
the window offers its download or a folder and never starts capture; with credentials missing it
says so and does not capture.

## S9 Recognition Flow (Windows, Debug builds)

A window with the capture configuration, the recognition result ("No ACRCloud match yet." before
one arrives), the ambient-sync snapshot ("Ambient sync has not emitted a snapshot yet."), and
"Start Flow", "Start Ambient Sync" and "Stop".

## Session state machine

```
setup → loading → playing ⇄ paused
                    ↓
                 finished, failed → setup
```

`finished` and `failed` end a run; both return to setup without keeping the session. Setup keeps
the chosen mode, the generated chart and its seed, as the Apple client does, so a new run does not
repeat the generation.

## Input

| | Windows | Android |
| --- | --- | --- |
| Lanes | Four keys, default D F J K, rebindable in S5 | Four touch targets in the tray |
| Timestamp | The window message time of the key event | The pointer event's timestamp |
| Capture | Keyboard focus on the play surface; Escape ends a binding capture | Multi-touch, one pointer per lane |

A press reaches the session with the timestamp at which the OS observed it, converted through the
audio clock's host-time mapping. The toolkit's own event time is not used.

## Open decisions

1. Interface language. The Apple clients are English-only; a Chinese-first Windows or Android
   release needs its own copy pass, not a translation of Apple's.
2. Whether Android's Settings sheet keeps the Developer section once the Inspector is ported, or
   never gains it.
3. Whether Windows keeps the separate play window, or plays only in the workbench page.

## Acceptance for the set

- Every screen above is reachable, and the surfaces Android omits are absent rather than disabled.
- The same song, stars, sections and seed make the same chart on every client.
- A `.osz` saved by any client opens in the Chart Inspector and in osu!.
- A stopped or failed generation never leaves a partial chart that can be played.
- Settings changed during a run take effect at the next run, never mid-game.
