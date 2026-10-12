---
commit: 20bf67d
---

# Gameplay audio timing

How the play screen's audio clock follows what the listener hears, what the audio
engine does that shaped it, and what the audio offset is left to correct. The
contracts are in [architecture](architecture.md#gameplay-and-scoring). Numbers
come from a MacBook Air M5 on the built-in speakers with the output muted.

## The clock

Local files play through `AVAudioEngine` (`AVFoundationMania4KAudioClock`). The
clock takes the player node's sample time at its last render (`lastRenderTime`,
`playerTime(forNodeTime:)`), carries it to the asked host time at rate 1, and
subtracts `outputNode.presentationLatency`, read when playback starts. The result
is the song time being heard at that host time, not how far the decoder has got.
`AVAudioPlayer.currentTime`, the clock it replaces, saw neither the render
schedule nor the output device. Ambient play keeps its host-time anchor.

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
- On the built-in speakers `presentationLatency` is 1.25 ms (60 frames, the device
  latency). CoreAudio also reports 556 frames (11.6 ms) of stream latency and a
  48-frame safety offset that `presentationLatency` leaves out; they stay in the
  audio offset.
- `playerTime(forNodeTime:)` is nil while the player is paused, and the player
  timeline continues across pause and resume. The clock holds the paused time and
  waits for the first buffer after `play()` before it extrapolates again.
- A change of output device (headphones connecting or unplugging) stops the engine
  and posts `AVAudioEngineConfigurationChange`. The clock builds a new engine, player
  and mixer for the new device, so the mixer is at the new output rate, re-reads the
  presentation latency, and schedules the source from the position that was being
  heard: the file from that frame (`scheduleSegment`), the click track from its
  place in the lead-in or the interval. Reads in between hold that position, and the
  gap only delays the song. Once the new engine renders, the clock reads lower by
  the new device's latency plus the render lead until the resumed sound is heard;
  the play session follows it back, so the playfield steps back once by that amount
  and notes already judged stay judged. A paused clock is rebuilt paused at its
  position.

## Offsets

The default audio offset was -215 ms, a preset named for Sony WH-1000XM4
headphones on a MacBook Air M5. With `AVAudioPlayer.currentTime`, which does not
see the output device, almost all of it was Bluetooth latency. The engine clock
subtracts the latency the device reports, so the default is the residual: -15 ms,
tuned by ear on the same WH-1000XM4 headphones with this clock (the built-in
speakers measured about -20 ms: unreported stream latency and safety offset, about
13 ms, and keyboard latency). The visual default stays at -15 ms.

What the clock does not see stays in the audio offset: latency a device leaves out
of `presentationLatency`, keyboard and input-handling delay, and the time from
speaker to ear. Applied offsets and presets stored before this clock
(`mania4k.offsetCalibrationState`) are kept as they are. They were tuned against
`AVAudioPlayer.currentTime` and include the device latency the clock now removes,
so they are too negative by about that latency.

## Offset calibration

The calibration screen plays its ticks as a click track (1 s of silence, then
`calibration_tick.wav` every 500 ms) through the same clock, so tick k sounds at
audio time `1000 + 500 k` with the device's reported latency removed as in play.
A key is read on that clock when it is handled, as play reads keys. A tick's chart
time is its audio time, and a key's hit error is its audio time plus the rendered
audio offset minus the tick's time, as in play. A key `d` ms after a tick
therefore suggests an offset of `-d` whatever offset the screen started from, and
calibrating again from the result measures a hit error near zero. The suggestion
is the median of the last 16 accepted keys.

## Reproduce

On the Mac with the audio device, from the repository root:

```sh
xcodegen generate
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac -destination 'platform=macOS' test \
  -only-testing:EnsomiCoreTests/AVFoundationMania4KAudioClockTests
```

The tests play silently. They check that the clock follows the host clock within
0.5 ms and only forward while playing, holds while paused, renders the click track's
ticks at their audio times, and resumes from the heard position after a simulated
device change. Those that play skip when there is no audio output.

## Not covered

- Bluetooth and other devices that report a large latency: the clock uses what the
  device reports, which was not checked against a measurement of the sound.
- A real device change: the tests call the clock's restart directly.
- A device change to no output device: the clock stays paused at its position and
  nothing reports it.
- The delay from sound to ear, which only the audio offset can absorb.
