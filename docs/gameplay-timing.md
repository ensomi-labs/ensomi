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
  in timestamp order, then advances the engine, so a press is judged when it
  happened and not when the main thread got to it.
- **Audio clock.** Local files play through `AVAudioEngine`. The clock takes the
  player node's sample time at the last render (`lastRenderTime`,
  `playerTime(forNodeTime:)`), carries it to the asked host time at rate 1, and
  subtracts `outputNode.presentationLatency`. Ambient play keeps its host-time
  anchor.
- **Frames.** The `MTKView` that draws the playfield owns a display link
  (`NSView.displayLink(target:selector:)`, `CADisplayLink` on iOS). Each
  refresh it judges at the current chart time, publishes a frame whose render time
  is the chart time at the link's `targetTimestamp`, and draws it. There is no
  other tick.
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
  the main mixer's output volume instead.
- The render timestamp's host time leads the moment of the call by 13 to 22 ms (one
  I/O buffer, 512 frames at 48 kHz, plus the device's safety offset).
- On the built-in speakers `presentationLatency` is 1.25 ms (60 frames, the device
  latency). CoreAudio also reports 556 frames (11.6 ms) of stream latency and a
  48-frame safety offset that `presentationLatency` leaves out; they stay in the
  audio offset.
- `playerTime(forNodeTime:)` is nil while the player is paused, and the player
  timeline continues across pause and resume. The clock holds the paused time and
  waits for the first buffer after `play()` before it extrapolates again.

## Measurements

Key events with known timestamps, handled 0 to 60 ms after they happened, against
the engine clock (21 presses). Error is the judged hit error of a press stamped at
the chart time of its note; stamping at handling time, as before, makes the error
equal to the delay.

| Delay between press and handling | Presses | Mean abs error | Max abs error | Error stamping at handling |
| ---: | ---: | ---: | ---: | ---: |
| 0 ms | 6 | 0.110 ms | 0.154 ms | 0 ms |
| 10 ms | 5 | 0.097 ms | 0.128 ms | 10 ms |
| 30 ms | 5 | 0.110 ms | 0.142 ms | 30 ms |
| 60 ms | 5 | 0.107 ms | 0.146 ms | 60 ms |

A 60 s silent autoplay on the 60 Hz display (3601 frames, 418 notes pressed
through the key path at their stamped times: 418 perfect, 0 miss):

| Quantity | Result |
| --- | --- |
| Display refresh interval (`targetTimestamp` steps) | 16.666 ms at p1, p50 and p99; one interval of 27.7 ms in 3600 |
| Main-thread callback interval | mean 16.670, sd 0.66, p1 16.36, p99 16.98, max 29.2 ms; 6 over 1.5x nominal, none over 2.5x |
| Callback start after the vsync timestamp | p50 0.36 ms, p99 1.0 ms, max 12.9 ms |
| Engine chart-time step minus refresh step | sd 0.010 ms, within -0.018 to 0.019 ms |
| `AVAudioPlayer.currentTime` step minus callback step (the old clock) | sd 0.36 ms, one stall of 21.7 ms |

CPU cost per frame (Debug build): laying out a busy frame (130 quads) 0.035 ms at
p50, encoding and committing it 0.011 ms. The engine's `advance` plus `snapshot`
on a 4000-object chart cost 2.4 to 3.6 ms at p50 (both walk every object each
frame; an optimized build is far cheaper), with a rare outlier of several tens of
milliseconds that is a single dropped frame.

## Offsets

The default audio offset was -215 ms, from `3ef07b0` ("better preset", June 2026),
a preset named for Sony WH-1000XM4 headphones on a MacBook Air M5. With
`AVAudioPlayer.currentTime`, which does not see the output device, almost all of it
was Bluetooth latency. The engine clock now subtracts the latency the device
reports, so the default is the residual: -20 ms, which is what the built-in
speakers needed (unreported stream latency and safety offset, about 13 ms, and
keyboard latency). The visual default stays at -15 ms. The play screen's frame is
now drawn for the display time instead of the last tick, so it may need a
different value.

The offset calibration screen still measures from the command to play a tick, with
its own `AVAudioPlayer`, so its result includes the output latency the clock now
removes. On the built-in speakers the difference is about 1 ms; with a device that
reports a large latency, such as Bluetooth headphones, subtract that device's
`presentationLatency` or adjust the offset during play.

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

## Not covered

- Displays faster than 60 Hz: the code uses the link's own timestamps and does not
  assume 60 Hz, but only a 60 Hz display was measured.
- Bluetooth and other devices that report a latency: the clock uses what the
  device reports.
- An audio route change during play (for example unplugging headphones) stops the
  engine; the session does not restart it.
- The delay from sound to ear and from frame to eye, which only the offsets can
  absorb.
