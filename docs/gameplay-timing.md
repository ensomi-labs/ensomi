---
commit: 20bf67d
---

# Gameplay timing

How the play screen gets from a key press to a judgement and from the audio clock
to a drawn frame, what was measured, and how to measure it again. The contracts
are in [architecture](architecture.md#gameplay-and-scoring). Numbers come from a
Debug build on a MacBook Air M5 (60 Hz built-in display, built-in speakers) with
the output muted. They show the pipeline's own consistency; the delay between a
sound reaching the speaker and the player hearing it, and between a frame
reaching the display and the player seeing it, are left to the audio and visual
offsets.

## Pipeline

- **Input.** A key event's `NSEvent.timestamp` is system uptime, the same clock as
  `ProcessInfo.systemUptime`, `CACurrentMediaTime()` and `AVAudioTime` host times
  (they agree to within 1 ms). The session converts it to chart time through the
  audio clock's `audioTimeMs(atHostTimeMs:)` the moment the key is handled and
  queues the input. The next frame delivers the inputs stamped up to its chart time
  in timestamp order, then advances the session to its chart time less 100 ms
  (`inputDeliveryAllowanceMs`). The gameplay core never moves its clock back, and
  key events stamped before a frame can still wait in the main thread's queue, so a
  frame only declares inputs delivered up to that allowance. A press handled within
  100 ms of happening is judged when it happened; hits show at once, misses up to
  100 ms after they fall due.
- **Audio clock.** Local files play through `AVAudioEngine`. The clock takes the
  player node's sample time at the last render (`lastRenderTime`,
  `playerTime(forNodeTime:)`), carries it to the asked host time at rate 1, and
  subtracts `outputNode.presentationLatency`. Ambient play keeps its host-time
  anchor.
- **Frames.** The `MTKView` that draws the playfield owns a display link
  (`NSView.displayLink(target:selector:)`, `CADisplayLink` on iOS). Each
  refresh it judges at the current chart time, publishes a frame whose render time
  is the chart time at the link's `targetTimestamp`, and draws it. There is no
  other tick. The link stops while the window is minimized, hidden, fully covered or
  on another Space (its occlusion state is not visible), so frames and stream reads
  stop too. The playfield view then pauses the session, `pause()`, and the player
  resumes with the Resume button.
- **Drawing.** `Mania4KPlayfieldLayout` turns the play frame into rounded
  rectangles, `Mania4KMetalRenderer` draws them in one instanced call. The HUD is
  SwiftUI over the Metal view.

## What the audio engine does

These shaped the clock and are easy to get wrong again:

- The player node's timeline follows the device clock only when something at the
  output's sample rate sits between the player and the main mixer. A 44.1 kHz file
  connected straight to the main mixer reports 471 samples per 512-frame cycle, a
  drift of 1.3 ms per second against the host (20 ms after 16 s); with a mixer
  at the output rate in between, the drift is 7 ppm, the same as a 48 kHz file.
- Silencing must not use the player's own volume. At volume 0 the node time drifts
  the same 1.3 ms per second even with the mixer in place. The clock's `volume` sets
  the main mixer's output volume instead. `AVAudioPlayer` at volume 0 drifts the same
  way, so a silent comparison against it plays a file of zeros at full volume.
- The render timestamp's host time leads the moment of the call by 13 to 22 ms,
  depending on where in the 10.7 ms I/O cycle (512 frames at 48 kHz) the call falls.
  On WH-1000XM4 headphones over Bluetooth the first render after a start leads by
  35 to 46 ms, and `presentationLatency` is 203 ms.
- On the built-in speakers `presentationLatency` is 1.25 ms (60 frames, the device
  latency). CoreAudio also reports 556 frames (11.6 ms) of stream latency and a
  48-frame safety offset that `presentationLatency` leaves out; they stay in the
  audio offset.
- A change of output device (headphones connecting or unplugging) stops the engine
  and posts `AVAudioEngineConfigurationChange`. The clock builds a new engine, player
  and mixer for the new device, so the mixer is at the new output rate, re-reads the
  presentation latency, and schedules the source from the position that was being
  heard: the file from that frame (`scheduleSegment`), the click track from its place
  in the lead-in or the interval.
  The audio time of the player's first frame becomes that position, so the audio time
  carries on from it and the gap only delays the song; reads in between hold it. The
  clock reads the position being heard, so after the restart it reads lower by the new
  device's latency (plus the render lead below) until the resumed sound is heard. The
  gameplay core never moves its clock back, so the chart waits for the clock to catch up. A paused clock is
  rebuilt paused at its position.
- `playerTime(forNodeTime:)` is nil while the player is paused, and the player
  timeline continues across pause and resume. The clock holds the paused time and
  waits for the first buffer after `play()` before it extrapolates again.

## Measurements

Key events with known timestamps, handled 0 to 60 ms after they happened, against
the engine clock (21 presses). Error is the judged hit error of a press stamped at
the chart time of its note, judged by the gameplay core; stamping at handling time
makes the error equal to the delay. Without the input allowance a press handled
after a frame had passed its stamp was judged at the frame's time (8, 28 and 58 ms
at the delays below).

| Delay between press and handling | Presses | Mean abs error | Max abs error | Error stamping at handling |
| ---: | ---: | ---: | ---: | ---: |
| 0 ms | 6 | 0.106 ms | 0.138 ms | 0 ms |
| 10 ms | 5 | 0.093 ms | 0.122 ms | 10 ms |
| 30 ms | 5 | 0.106 ms | 0.135 ms | 30 ms |
| 60 ms | 5 | 0.101 ms | 0.130 ms | 60 ms |

A 60 s silent autoplay on the 60 Hz display (3601 frames, 418 notes pressed
through the key path at their stamped times: 418 perfect, 0 miss):

| Quantity | Result |
| --- | --- |
| Display refresh interval (`targetTimestamp` steps) | 16.666 ms at p1, p50 and p99; one interval of 27.7 ms in 3600 |
| Main-thread callback interval | mean 16.670, sd 0.66, p1 16.36, p99 16.98, max 29.2 ms; 6 over 1.5x nominal, none over 2.5x |
| Callback start after the vsync timestamp | p50 0.36 ms, p99 1.0 ms, max 12.9 ms |
| Engine chart-time step minus refresh step | sd 0.010 ms, within -0.018 to 0.019 ms |
| `AVAudioPlayer.currentTime` step minus callback step (the old clock) | sd 0.36 ms, one step 21.7 ms short |

With the gameplay core, a 30 s silent autoplay (1795 frames, 203 notes pressed
through the key path: 203 perfect, 0 miss) gave the same clock behaviour: chart-time
step minus refresh step sd 0.010 ms, within -0.018 to 0.015 ms; refresh interval
16.666 ms at p50 and p99.

The old clock's row was measured with `AVAudioPlayer` at volume 0, which drifts as
described above; at normal volume it is nearly as smooth. What the engine clock gains
is heard time: it reads the song time being heard, with the latency the device
reports removed, where `AVAudioPlayer.currentTime` saw neither the render schedule nor
the output device.

CPU cost per frame (Debug build): laying out a busy frame (130 quads) 0.17 to 0.20 ms
at p50 with the core's note geometry through the Swift bindings (0.035 ms with the
Swift layout before), encoding and committing it 0.011 ms. With the play screen's
current look (`gameplay-look.md`) the same frame plus eight hits at the line is 134
quads; laying it out, including fetching the recent judgement events through the
bindings, took 0.25 and 0.33 ms at p50 in two runs, against 0.21 and 0.38 ms for the
previous layout in two runs on the same machine minutes apart, while another job loaded
it (the session cost below read 0.06 to 0.11 ms in those runs). The GPU draws the frame
at 720 by 1400 pixels in 0.52 ms at p50, against 0.93 ms for the previous look, which
filled every lane twice, stroked it and glowed every note. The core session's `advance`,
`visibleObjects` and `laneStates` on a 4000-object chart cost 0.050 ms at p50 and
0.054 ms at p99 through the Swift bindings (maximum 3.7 ms in 1000 frames); the
core's own frame, timed in Rust (release), is 0.7 us at p50 for 4000 and for 16000
objects, since it only looks at the objects near the current time. The Swift engine
it replaced cost 2.4 to 3.6 ms at p50, walking every object each frame.

## Offsets

The default audio offset was -215 ms, from `3ef07b0` ("better preset", June 2026),
a preset named for Sony WH-1000XM4 headphones on a MacBook Air M5. With
`AVAudioPlayer.currentTime`, which does not see the output device, almost all of it
was Bluetooth latency. The engine clock now subtracts the latency the device
reports, so the default is the residual: -15 ms, tuned by ear on the same
WH-1000XM4 headphones with this clock (the built-in speakers measured about -20 ms:
unreported stream latency and safety offset, about 13 ms, and keyboard latency).
The visual default stays at -15 ms. The play screen's frame is now drawn for the
display time instead of the last tick, so it may need a different value.

What the clock does not see stays in the audio offset: latency a device leaves out
of `presentationLatency`, keyboard and input-handling delay, and the time from
speaker to ear. Applied offsets and presets stored before this clock
(`mania4k.offsetCalibrationState`) are kept as they are. They were tuned against
`AVAudioPlayer.currentTime` and include the device latency the clock now removes,
so they are too negative by about that latency.

The offset calibration screen judges as play does, on the same clock. Its click, the
bundled `calibration_tick.wav`, is laid in a click track that
`AVFoundationMania4KAudioClock` plays through the graph above: silence for 1 s, then the
tick every 500 ms as a looped buffer. Tick k is therefore heard at audio time
`1000 + 500 k` ms, as a sound of the song at that time would be, and the screen's
clock is that audio time, read at each frame. A key is stamped with
`NSEvent.timestamp` and converted through `audioTimeMs(atHostTimeMs:)`. Tick k is a
note at chart time `1000 + 500 k` ms, a key's chart time is its audio time plus the
audio offset, and the hit error is their difference. A key's suggested audio offset is
the rendered offset minus its hit error, which is `1000 + 500 k` minus the key's audio
time: minus the delay between hearing the tick and pressing, whatever offset the screen
started from. The suggestion is the median of the last 16 accepted keys. The device's
reported latency is not counted twice, and calibrating again from a result measures a
hit error near zero. Notes are drawn at the audio time of the display's presentation of the frame plus the
audio offset plus the visual offset, as in play: a display link on a view behind the
screen calls the model each refresh with the link's `targetTimestamp`, and the SwiftUI
update that follows is the frame. A hidden or minimized window stops the link and the
screen pauses its clock until the window is visible again.

## Reproduce

On the Mac that has the display and audio device, from the repository root:

```sh
xcodegen generate
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac -destination 'platform=macOS' test \
  -only-testing:EnsomiCoreTests/GameplayTimingTests
```

That runs the key-timestamp table, the CPU cost measurements and the clock
checks, and plays silently for about 15 s. Lines starting with `TIMING` in the
output are the numbers above. The 60 s autoplay and the hosted play-screen test
need an awake display and open a window, so they run only with an environment
variable; `caffeinate -u -d` wakes and holds the display for the run:

```sh
TEST_RUNNER_ENSOMI_TIMING_RUN=1 caffeinate -u -d -t 150 xcodebuild -project Ensomi.xcodeproj \
  -scheme EnsomiMac -destination 'platform=macOS' test -only-testing:EnsomiCoreTests/GameplayTimingTests
```

`TEST_RUNNER_ENSOMI_TIMING_SECONDS` shortens the autoplay. A change to a public
default such as `Mania4KDefaultPlaySettings` can leave the test target with a stale
inlined value in an incremental build; delete the test target's intermediates
under the derived data path if a default test disagrees with the source.

`-only-testing:EnsomiCoreTests/AVFoundationMania4KAudioClockTests` runs the clock's
own checks, silently: it follows the host clock within 0.5 ms and only forward while
playing, holds while paused, renders the click track's ticks at their audio times,
and resumes from the heard position after a simulated device change. The restart's
checks hold on any output: the first reading after the restart, plus the latency the
clock subtracts, lies from the held position as the first reading after the clock's own
start lay from 0, within 25 ms (`TIMING route change` lines), and the time the device
takes to start is not bounded. Those that play skip when there is no audio output.

## Not covered

- Displays faster than 60 Hz: the code uses the link's own timestamps and does not
  assume 60 Hz, but only a 60 Hz display was measured.
- Bluetooth and other devices that report a latency: the clock uses what the
  device reports.
- A real device change: the tests call the clock's restart directly.
- A route change to a device the engine cannot start on (no output device): the clock
  stays paused at its position and nothing reports it.
- The delay from sound to ear and from frame to eye, which only the offsets can
  absorb.
