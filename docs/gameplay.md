# Ensomi for Android: the gameplay experience

What "good" means for playing a chart on an Android phone, how the app gets there with Android's APIs, and how it is
checked. The screens around the game are in [design.md](design.md). The Swift app's `docs/gameplay-timing.md` and
`docs/gameplay-look.md` on `dev/swift` are the reference: the timing contract and the look carry over; the mechanisms
are Android's. The playfield in the prototype is the start of this design: its render thread, frame order, runtime
calls and touch handling are the real ones, driven by a fake clock and an autoplay until the audio clock exists.

## What good means

1. **Notes and music agree.** A note crosses the judgement line at the moment its sound is heard, every frame, for the
   whole song, on speaker, wired and Bluetooth output, once the offsets are set.
2. **A tap counts when the finger landed.** The judged error is the touch's own time minus the note's time, the same
   whether the app handled the touch at once or 50 ms later, and the same at any frame rate.
3. **Motion is smooth.** Every display refresh at 60, 90 or 120 Hz shows a new frame drawn for the moment it appears;
   dense passages drop no frames.
4. **The field answers the finger.** A lane lights as soon as the touch is handled, and nothing stays pressed: chords
   of up to four fingers and holds start and end where the fingers do.
5. **It reads at speed.** Notes are plain and distinct by kind, hits and misses are shown where the eyes are, in
   words as well as colour, and the player's hands do not cover what is coming.
6. **Interruptions are safe.** A call, the notification shade, unplugged headphones or leaving the app pause the game;
   resuming gives the hands time to return.

Each has a check below; those that need a phone are marked.

## The playfield on a phone

The geometry, in dp, from `PlayfieldGeometry`:

| Part | Size and place |
| --- | --- |
| Lanes | Four, touching, alternating `#1D1627` and `#191220`; the screen's width less 8 dp margins, at most 440 dp (about 99 dp per lane on a 411 dp wide phone) |
| HUD row | 64 dp at the top, below any display cutout: title and chart on the left, accuracy and Pause on the right, no panel |
| Song progress | A 3 dp coral bar along the top edge of the lanes |
| Judgement line | 3 dp coral, at the top of the key tray |
| Key tray | 88 dp, rounded at the bottom, with one 68 dp key cap per lane; its bottom sits above the system's mandatory gesture inset (at least 16 dp from the edge) |
| Fall distance | From the top of the lanes to the line: about 750 dp on a 923 dp tall phone |

**Notes** follow the Swift play screen. Taps are rose `#EAA0B7`, 20 dp tall, inset 5 dp from the lane edges, 7 dp
corners, no glow. Holds have lavender `#B5B3EF` heads and tails and a purple `#9562D7` body at 72 % of the note width,
60 % opaque, 85 % while held; a held head stays on the line with a soft halo. A note the runtime resolves as hit
disappears; one judged a miss while still on screen is greyed (`#8F7DAB` at half opacity). A note centred on the line
is drawn whole, then slips under the top edge of its key cap. Positions come from the runtime's `noteGeometry`.

**Hit feedback** is drawn only from the judgement events the runtime reports (`GameplayFeedback.judgementEventBatches`),
aged from the chart time each batch was recorded at: a hit lights the line in its lane in the tier's colour and a flare
rises for 200 ms; a miss tints 96 dp of the lane above the line and marks the line for 360 ms. Lane and cap lights come
from `laneBrightness` at the frame's presentation time. The judgement word and the combo sit at 42 % of the fall
distance; the word's opacity, scale and drift come from `judgementPresentation`; a Good shows an arrow for early (up,
lavender) or late (down, rose); the combo shows from 2; "Ready" holds the place until the first judgement.

**Touch zones** are the whole lane columns, from the top of the screen to the bottom, so a finger never needs to aim
at the small cap; a touch left or right of the field belongs to the nearest lane. The caps mark where the thumbs rest.

**Scroll speed** keeps the Swift app's scale: a note takes `11485 / speed` ms to fall from the top of the lanes to the
line, whatever the screen's height; the default 25 is 459 ms. On a phone the distance is similar to the Swift play
window's, so a speed means about the same physical speed too (about 1.6 dp per ms at 25).

**Scroll direction** is down only: notes fall towards the thumbs, and the hands at the bottom never cover incoming
notes. An upscroll would put the line at the top of the screen, out of reach, with the hands covering the notes.

## The clock: song time from the audio being heard

Every time on the play path is on one clock, `CLOCK_MONOTONIC`: `System.nanoTime()`, `MotionEvent` event times,
`AudioTimestamp.nanoTime` with `TIMEBASE_MONOTONIC`, and Choreographer's frame times. No conversion between clocks is
needed (stack report §2.4).

**Output.** The song is decoded once with `MediaExtractor` and `MediaCodec` (stack report §2.6) to float PCM at the
file's own rate. A writer thread feeds an `AudioTrack` (`ENCODING_PCM_FLOAT`, `MODE_STREAM`), first with the lead-in
as silence (two seconds, so the first note never comes before the hands are on the glass), then the song. Music volume
is the track's gain (`AudioTrack.setVolume`), which does not change frame positions.

**Reading it.** `AudioTrack.getTimestamp` gives a frame position and "the estimated time when that frame was presented
or is committed to be presented" ([AudioTrack](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp))).
The clock keeps the newest valid pair and carries it to any asked time at rate 1:

```text
songMs(t) = (framePosition − leadInFrames) / sampleRate × 1000 + (t − nanoTime) / 1e6
```

- Only the low 32 bits of the position are valid; the clock extends them across wraps (same source).
- It polls every 10 ms while the output warms up and after each start, resume or route change, then less often; the
  reference says short-term differences are not meaningful and to build the fine mapping in the app. Media3 does the
  same (10 ms, then 10 s; stack report §2.4). How often to poll once stable is a phone measurement.
- Before the first valid timestamp after a start or a resume, the clock holds its time. It never runs backwards: if a
  new pair would move song time back, it holds until the new pair catches up. The runtime's session never moves back
  either.
- The timestamp is a presented frame, so the output latency the device reports is already out of song time. What it
  does not report stays in the audio offset. There is no runtime API for true end-to-end audio latency, and the CDD
  bounds the reported value only loosely (stack report §2.4), so the offset stays necessary.
- `AudioTrack.getUnderrunCount()` is logged per game; the writer keeps at least 200 ms queued.

**Pause and resume.** Pause stops the track and the clock holds the song time being heard. Resume, after the count-in,
plays the track and the clock holds until the first timestamp after it, as the Swift clock waits for the first buffer
after `play()`.

**The picture follows the audio.** Each frame is drawn for the time it will be on screen, not when its code runs: the
render time is the song time at the frame's expected presentation time, plus the audio and visual offsets (next
section).

## The frame

The playfield is a `SurfaceView` with its own render thread, its own `Looper` and its own `Choreographer`, and the
8 MiB stack the runtime's worker uses, since the runtime is called from it. `SurfaceView` is the recommended surface
for this on API 24 and later (stack report §2.9). Each vsync, `Choreographer.postVsyncCallback` (API 33) hands the
thread a `FrameData`, whose preferred `FrameTimeline` gives the expected presentation time
([Choreographer](https://developer.android.com/reference/android/view/Choreographer#postVsyncCallback(android.view.Choreographer.VsyncCallback))).
Then, in this order, as in the Swift app:

1. Take the touches queued since the last frame, convert each event time to chart time through the clock, and deliver
   those stamped at or before the frame's gameplay time to the session in time order.
2. `advance` the session to the gameplay time less the 100 ms input allowance.
3. Read what to draw at the render time: `visibleObjects` from 700 ms before it to the scroll time plus 250 ms after,
   lane brightness, the recent judgement batches, the judgement presentation, the score.
4. Draw on `lockHardwareCanvas()` and post.

```text
gameplayMs = songMs(now) + audioOffset
renderMs   = songMs(expectedPresentation) + audioOffset + visualOffset
```

A frame that misses its deadline is not redrawn late; the next vsync draws for its own time, so motion never stalls or
jumps backwards. The HUD row in Compose observes only accuracy and the end of the game, which change when a judgement
does, not every frame. The judgement word and combo are drawn by the render thread, on the same timeline as the notes.

## Input

**Lanes.** A pointer's lane is where it went down: `floor((x − fieldLeft) / laneWidth)`, clamped to 0..3. It keeps that
lane until it lifts; sliding into the next lane does nothing, like a key. A lane is pressed while any pointer holds it,
so the press goes to the runtime with the first finger and the release with the last. Chords are separate pointers and
separate presses, each with its own time.

**Times.** Each press or release carries the event's own time: `MotionEvent.getEventTimeNanos()` from API 34,
`getEventTime()` in milliseconds on API 33 (the test phones run Android 16), not the time the app handled it. Android
batches only `ACTION_MOVE`, not downs and ups, so the app does not ask for unbuffered dispatch (stack report §2.9,
AOSP's input consumers). Touches arrive on the main thread and go to the render thread through a lock-free queue;
the render thread delivers them at the next frame.

**The allowance.** The session never moves its clock back, and a touch stamped before a frame may still be waiting on
a busy main thread. So a frame declares inputs delivered only up to its time less 100 ms: a touch handled within
100 ms of happening is judged exactly when it happened. Hits show at once; misses show up to 100 ms after they fall due.
A touch later than that is judged at the session's clock and counted in the runtime's `lateInputs`, which the app
logs.

**Edge cases.**

- `ACTION_CANCEL` (the system took the gesture) releases every pressed lane at the cancel's time.
- A pointer that lifts with `FLAG_CANCELED` (API 33; the system decided it was not deliberate, a palm for example)
  still releases its lane, the only way to keep press and release paired.
- Pausing releases every lane in the tracker; inputs queued before the pause are dropped, as in the Swift app.
- **System gestures.** Back is an inward swipe from the left or right edge, home a swipe from the bottom
  ([gesture navigation](https://developer.android.com/develop/ui/views/touch-and-input/gestures/gesturenav)). Taps are
  not affected; a finger that slides at an edge could be. Play is immersive, which makes a bottom swipe first show the
  bars; the key tray sits above the mandatory gesture inset, which apps cannot opt out of; and during play the app
  excludes the lower part of the outer lanes from the back gesture with `setSystemGestureExclusionRects`, which the
  same page allows games "during gameplay".

## Judgements through the runtime

The runtime does every rule; the app moves inputs and time and draws what it is told.

- `GameplaySession(chart, Ruleset(judge))` judges: `input(InputEvent(lane, phase, chartMs))`, `advance(watermark)`,
  `visibleObjects`, `score`, `isResolved`. Note lock, early presses, hold rules, tail windows, accuracy and the offset
  suggestion are its own (the Swift architecture doc's judge table holds for both).
- `GameplayFeedback` eases lane lights and chooses and animates the judgement word; the app records each batch of
  events at the chart time of the call that produced it, as the Swift app does.
- The app maps tiers to the words it shows with the runtime's `tierJudgement`, and for results counts Perfect
  (`bigP`), Good (`p1` to `g`) and Miss (`m`), as the runtime's README defines them.
- A game ends when every object is decided and the song has played to its end, then Results show the runtime's score.
- Every runtime call of a game happens on the render thread; none on the main thread.

## Offsets and calibration

The session applies offsets once, as in the Swift app: the audio offset moves judging and the drawn notes together;
the visual offset moves only the drawn notes; scroll speed changes only how far ahead notes are drawn.

- **Audio offset** belongs to the output: the `AudioTrack`'s routed `AudioDeviceInfo`, keyed by its type and product
  name (speaker, wired, a given pair of Bluetooth headphones). A device without one uses the default.
- **Visual offset** belongs to the display and is one value.
- **No separate input offset.** The time from a finger landing to its event timestamp shifts every tap the same way an
  unreported output latency does; judgement cannot tell them apart, so both live in the audio offset. Because the
  phone is fixed and the output varies, keeping the offset per output is enough. The visual offset is calibrated
  without taps, so touch latency cannot leak into it.
- **Defaults are 0 ms** for both until the phones are measured; the Swift app's −15 ms was tuned on a Mac with a
  keyboard. The phone measurements may give per-route defaults.

**Calibration, sound step.** The same `AudioTrack` path plays a click track: one second of silence, then a click every
500 ms, generated in code (a short tone burst). Click k is heard at song time `1000 + 500 k` ms on the play clock. The
pad is a `View` that stamps taps with `getEventTimeNanos`, as the playfield does. A tap's error is its chart time minus
the nearest click's time; the suggested audio offset is the current one minus the median error of the last 16 taps.
Calibrating again from a result gives errors near zero. The screen names the output it calibrates.

**Calibration, picture step.** The clicks go on and the play renderer draws a note at each click's time on the same
clock, with the visual offset from the slider, live. The player moves it until the notes cross the line on the click.
No taps.

**After a game**, Results offer the runtime's `suggestedAudioOffsetMs` (minus the mean error of hit taps and hold
heads) for the output used, when it is at least 3 ms.

## Frame pacing at 60, 90 and 120 Hz

| Refresh | Frame interval | Render thread work, target p99 |
| --- | --- | --- |
| 60 Hz | 16.7 ms | 3 ms |
| 90 Hz | 11.1 ms | 3 ms |
| 120 Hz | 8.3 ms | 3 ms |

- The play screen asks for the display's fastest mode with `Surface.setFrameRate`
  ([frame rate](https://developer.android.com/media/optimize/performance/frame-rate)), compatibility default, only if
  the switch is seamless. The system may refuse (battery saver; HyperOS may cap apps); positions are computed from
  time, so the game is right at any rate it gets.
- The render thread's work is the four steps above. The Swift app measured the runtime's frame (advance, visible
  objects, lane states) at 0.05 ms through its bindings; through JNA each call costs more, and `noteGeometry` is one
  call per visible note (about 70 in a dense frame). That cost is the first thing the phone measures. If it is too
  high, the fix is one runtime call that returns every visible note's geometry: a runtime change, listed in plan.md and
  not made.
- The renderer allocates nothing per frame; the bindings return new lists each call (`visibleObjects`, batches), so
  garbage collection pauses are watched in the traces.
- Drawing is a hardware canvas (HWUI's GPU renderer) of about 150 rounded rectangles, gradients and two text runs, with
  additive blending for lights. GLES with instanced quads replaces only the draw step if the canvas misses frames at
  120 Hz; the thread, pacing, clock and frame order stay.
- Measured with per-frame logs on the render thread and Perfetto's FrameTimeline, which records expected and actual
  presentation and jank per layer ([FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)). The
  prototype's render thread already logs, every two seconds under the tag `EnsomiPlayfield`, its frame rate, the
  vsyncs it missed, and its average and longest time per frame in the runtime and in drawing.

## What the emulator can and cannot show

The arm64 AVD `ensomi-36` runs the app natively on the Mac, at 1080 × 2424.

| It shows | It cannot show |
| --- | --- |
| Layout, insets, every screen and state, light and dark, font scale, landscape | 90 or 120 Hz: it has one 60 Hz vsync |
| The playfield's look and motion (screen recordings), the frame order, the runtime judging the autoplay's inputs | Real audio timing: host audio, no meaningful `AudioTrack` timestamps or latency |
| Single touches judged, from `adb shell input tap` | Touch latency, real multi-touch from a hand, palm rejection |
| Pause on lifecycle, focus and Back; the count-in | Haptic delay; HyperOS refresh caps, power and thermal behaviour |
| That runtime calls stay off the main thread (logs) | Frame cost on a phone CPU and GPU |

## Measurements a phone must make

On the Snapdragon 8 Gen 3 Xiaomi (HyperOS, 120 Hz) and the Dimensity 9400+, on speaker, wired and Bluetooth output
where it applies:

| What | How | Good when |
| --- | --- | --- |
| Clock smoothness | Log every timestamp pair over a 3-minute song; fit a line | Residual sd under 0.5 ms; no backward step; no stall after resume or a route change |
| Picture against audio | Chart-time step between frames minus the presentation-time step | sd under 0.5 ms (the Swift app measured 0.010 ms) |
| Unreported output latency | The calibration's sound step, three runs per output; for speaker and wired also an external recording of a click against the logged clock | Runs within 5 ms of each other; gives each output's default offset |
| Touch stamp | Event time against handling time for every touch of a game; the runtime's `lateInputs` | `lateInputs` 0 in normal play; handling delay p99 well under 100 ms |
| Touch to light | 240 fps video of a finger tapping a lane | The light within two refreshes of the finger landing |
| Frame pacing | Perfetto FrameTimeline over a 60 s autoplay of the densest sample, at 120 Hz | Under 0.5 % of frames late; render-thread work p99 under 3 ms |
| Refresh rate | `Display.getRefreshRate()` during play after `setFrameRate` | 120 Hz on the Xiaomi unless battery saver is on |
| Multi-touch | Four-finger chords and holds with real hands, presses and releases counted | Every press has its release; no lane stays lit |
| Haptics | 240 fps video with Vibrate on lane touch | Its delay known, to say whether to offer it |
| Heat | 10 minutes of play: thermal status and frame pacing | No drop in pacing |
