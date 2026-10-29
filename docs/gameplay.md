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

### Landscape, with the lanes leaning: the convention

Phones play in **landscape** by default, with the lanes **leaning** back in perspective; portrait is a setting, with
flat lanes by default. This follows the convention of Malody's key mode on phones, which the owner chose. Ensomi takes
the convention, not Malody's art, names or skins. What public sources show (old Malody to 4.3.7, and Malody V):

| Malody's key mode on phones | Source |
| --- | --- |
| Landscape by default; portrait play added later, as an option, in both generations ("Support portrait play mode (Optional)", 1.14.1; "Enabled portrait mode for 4K playing", Malody V 6.6.12, 2025) | [release log](https://m.mugzone.net/wiki/127); [Malody V on the App Store](https://apps.apple.com/us/app/malody-v/id1566873256) |
| The lean is a true perspective tilt of the lane plane: a trapezoid with straight edges, narrow at the top, the four lanes equal at each height, notes shrinking with distance in width and in thickness | official store screenshots ([Malody V](https://apps.apple.com/us/app/malody-v/id1566873256), [Malody](https://apps.apple.com/us/app/malody/id989630809)) |
| At the top edge of the screen the lanes are about 0.29 (Malody V) and 0.21 (old Malody) of their width at the line; there is no horizon: notes enter at the top edge | the same screenshots, measured |
| A switch ("3D column") and an angle in degrees: up to 50° in old Malody, 55° in Malody V's key mode; players mostly set 40° | release log 1.13.5 and 1.17.1; player posts on TapTap and guides (Chinese) |
| The judgement line about 80 to 85 % down the screen; the field centred and, at the line, about 65 % (Malody V) to 79 % (old Malody) of the screen's width, with a lane-width setting | screenshots, measured; skin documentation |
| Touch zones are the skin's: old Malody's default skin puts four pads under the line inside the leaning lanes, with a "full width virtual keyboard" option | release log 1.10.1 and 3.3.6; screenshot |

Not confirmed from public sources: Malody's default angle and whether the tilt is on after a fresh install (the
official screenshots show it on); Malody V's default touch zones; whether its tilt applies in portrait (one portrait
player describes his set-up as "no depth"); what its "3D motion" and "3D thickness" compensation settings do exactly.
Chinese video and forum sites mostly refused automated reading, so videos were checked only through store
screenshots and thumbnails.

### Geometry

From `PlayfieldGeometry`, in dp; the numbers in brackets are for the 1080 × 2424 px emulator (923 × 411 dp in
landscape) with a 24 dp gesture area.

| Part | Landscape (the default) | Portrait |
| --- | --- | --- |
| Lanes at the line | 64 % of the width, at most 640 dp (591 dp, 148 dp per lane) | The width less 8 dp margins, at most 440 dp (395 dp, 99 dp per lane) |
| HUD | In the top corners beside the lanes' far end: title and chart on the left, accuracy and Pause on the right | A 64 dp row at the top, below any display cutout |
| Far end of the lanes | 6 dp under the top edge | Under the HUD row |
| Judgement line | 3 dp coral, at the top of the key tray (79 % down the screen) | The same (88 % down) |
| Key tray | 64 dp, one 44 dp cap per lane | 88 dp, one 68 dp cap per lane |
| Under the tray | The system's mandatory gesture inset, at least 10 dp | The same, at least 16 dp |
| Lanes' height on the screen | 317 dp | About 750 dp |
| Lean by default | 40° | Flat |

The lanes alternate `#1D1627` and `#191220` between two 2 dp `#2A2036` side edges, which show the lean's shape
against the ground, and their far end fades into the ground over 8 % of their height on the screen, so notes come out of the distance instead of appearing at an edge. A 3 dp song progress bar runs along the far
end.

### The lean

**The model.** The lanes are a flat strip tilted back by the lean angle θ about the judgement line, seen from an eye
level with the strip's far end and 0.36 of the lanes' screen height in front of the screen. The far end stays at the top
whatever the angle, and is `k = 1 / (1 + tan θ / 0.36)` of the line's width: 0.67 at 10°, 0.50 at 20°, 0.38 at 30°,
**0.30 at 40°** (the default, close to Malody V's look), 0.20 at 55° (the most). 0° is flat, so the lean needs no
separate switch. The eye distance was chosen so that 40° looks like the convention; it is one constant.

**Lane space.** Everything on the lanes (lanes, lights, notes, hit feedback) is drawn as on the flat strip, in lane
space: x as on the screen at the line, y from the far end to the line over a length `H = h / k`, where h is the lanes'
height on the screen. One perspective `android.graphics.Matrix`, which `PlayfieldGeometry` computes and the renderer
applies with `Canvas.concat`, tilts it onto the screen. With L the line's y, c the field's centre x and a = (1 − k) / k:

```text
| 1   −c·a/H        c·a·L/H  |
| 0   1 − a·L/H     a·L²/H   |
| 0   −a/H          1 + a·L/H |
```

Lane space and the screen agree exactly along the line, so the line and everything at it is drawn at its design size;
with no lean the matrix is the identity and the drawing is the flat field.

**A note's place and size.** The runtime's `noteGeometry` places a note along H linearly in its time, as for flat
lanes. On the screen, a note a fraction d of the way from the line to the far end is drawn at `s = k / (k + (1 − k)·d)`
of its size at the line, `h·d / (k + (1 − k)·d)` above it. So it appears at the far end at the same moment at any lean,
crosses the line at its time, and moves slowly far away and fast near the line: at the line 1/k times as fast as flat
lanes of the same height (3.3 times at 40°), at the far end k times. Perspective narrows a note by s but flattens it by
s², which would thin far notes to slivers; taps and hold ends are therefore stretched in lane space so that they keep
their shape and only shrink (Malody V offers a "thickness compensation" setting for the same problem). Hold bodies
follow the lane, since their length is time.

**What does not lean.** Drawn on the screen as it is: the ground, the key tray and its caps, the judgement line, the
progress bar, and the judgement word and combo, which stay upright and sharp at 42 % of the lanes' height on the screen
(a fifth smaller in landscape, where the field is short). They are drawn after the lanes and before the notes, so a
note passing through them in the middle lanes stays whole; the words are large and change slowly, so a note hides
little of them. Moving them off the lanes was not an option in landscape, where the lanes take the middle of the
screen and the HUD its top corners, and making them translucent would still tint the notes they cover.
The line, the tray and the touch zones are the same at every lean.

**Notes** follow the Swift play screen. Taps are rose `#EAA0B7`, 20 dp tall at the line, inset 5 dp from the lane edges,
7 dp corners, no glow. Holds have lavender `#B5B3EF` heads and tails and a purple `#9562D7` body at 72 % of the note
width, 60 % opaque, 85 % while held; a held head stays on the line with a soft halo. A note the runtime resolves as hit
disappears; one judged a miss while still on screen is greyed (`#8F7DAB` at half opacity). A note centred on the line
is drawn whole, then slips under the top edge of its key cap. Nothing on the lanes is clipped: a clip under a
perspective matrix is no longer a rectangle, so notes are cut to the lanes by hand.

**Hit feedback** is drawn only from the judgement events the runtime reports (`GameplayFeedback.judgementEventBatches`),
aged from the chart time each batch was recorded at: a hit lights the line in its lane in the tier's colour and a flare
rises for 200 ms; a miss tints 96 dp of the lane above the line and marks the line for 360 ms. Lane and cap lights come
from `laneBrightness` at the frame's presentation time; a lane's light rises 45 % of the lanes' screen height at any
lean. The word's opacity, scale and drift come from `judgementPresentation`; a Good shows an arrow for early (up,
lavender) or late (down, rose); the combo shows from 2; "Ready" holds the place until the first judgement.

**Touch zones** are the lanes at the line, extended up and down the whole screen as columns; a touch left or right of
the field belongs to the nearest lane, so in landscape the outer lanes reach the screen's edges. They do not follow the
lean: changing it never moves a lane under the thumbs, and a thumb that lands a little high is still in its lane
(Malody's default pads under the line drew a player's complaint that they sit too low). The caps mark where the thumbs
rest.

**Scroll speed** keeps the Swift app's scale: a note takes `11485 / speed` ms to travel the lanes, whatever the screen,
the orientation and the lean; the default 25 is 459 ms. So the reading time is the same everywhere; what changes is the
speed on the screen near the line: about 1.6 dp per ms in portrait, 0.7 in landscape without lean, 2.3 in landscape at
40°. Malody V lets players reshape this with a "3D motion compensation" setting; Ensomi keeps plain perspective and
offers no such setting in this round.

**Scroll direction** is down only: notes fall towards the thumbs, and the hands at the bottom never cover incoming
notes. An upscroll would put the line at the top of the screen, out of reach, with the hands covering the notes.

**Previewing the lean.** Settings shows the game's own renderer playing the sample chart under the orientation and
lean sliders, on a surface buffer the size of the phone's screen in that orientation, which the system scales into the
list (`SurfaceHolder.setFixedSize`; the
[hardware scaler](https://android-developers.googleblog.com/2013/09/using-hardware-scaler-for-performance.html)). The
preview takes the new lean and scroll speed on the next frame while a slider moves.

## The clock: song time from the audio being heard

Every time on the play path is on one clock, `CLOCK_MONOTONIC`: `System.nanoTime()`, `MotionEvent` event times,
`AudioTimestamp.nanoTime` with `TIMEBASE_MONOTONIC`, and Choreographer's frame times. No conversion between clocks is
needed (stack report §2.4).

**Output.** The song is decoded once with `MediaExtractor` and `MediaCodec` (stack report §2.6) to float PCM at the
file's own rate. A writer thread feeds an `AudioTrack` (`ENCODING_PCM_FLOAT`, `MODE_STREAM`), first with the lead-in
as silence (two seconds, so the first note never comes before the hands are on the glass), then the song. Music volume
is the track's gain (`AudioTrack.setVolume`), which does not change frame positions.

**Song time.** Charts are made and played on the song's time: the clock of ensomi-model's release engine, which puts
every song on osu!'s (BASS's), the one R2 was fitted to, as in the Swift app. BASS trims an MP3's encoder delay and the
529-frame Layer III decoder delay only when a LAME extension gives a non-zero encoder delay, and trims nothing
otherwise. The app decodes MP3 and AAC without the platform's gapless trimming (it takes the extractor's encoder delay
and padding out of the format) and with Android's own decoders rather than a vendor's, so the start of the PCM is the
same on every phone: `c2.android.mp3.decoder` trims its 529 frames, and the extractor skips a Xing, Info or VBRI frame.
`Mp3Lead` then finds the release engine's header class and puts the PCM on the song's time: 529 frames of silence first
for a file without a header, with a Xing or Info header but no LAME extension, or with a LAME delay of 0; the LAME
encoder delay taken off when it is not 0; a VBRI header left as decoded; the priming of an iTunes gapless comment taken
off, as AVFoundation does. AAC's priming (the container's encoder delay) is taken off too, as AVFoundation does; other
formats are as their decoders give them. The end of a file is left as decoded.

**Reading it.** `AudioTrack.getTimestamp` gives a frame position and "the estimated time when that frame was presented
or is committed to be presented" ([AudioTrack](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp))).
The clock keeps the newest valid pair and carries it to any asked time at rate 1:

```text
songMs(t) = (framePosition − leadInFrames) / sampleRate × 1000 + (t − nanoTime) / 1e6
```

- Only the low 32 bits of the position are valid; the clock extends them across wraps (same source).
- It polls every 10 ms while the output warms up and for a second after each start, resume or route change, then once
  a second; the reference says short-term differences are not meaningful and to build the fine mapping in the app.
  Media3 does the same (10 ms, then 10 s; stack report §2.4). How often to poll once stable is a phone measurement.
- Before the first valid timestamp after a start or a resume, the clock holds its time: a pair counts only if it was
  presented after the start or resume and its frame is past the one the output stopped at. It never runs backwards:
  if a new pair would move song time back, the clock keeps to the old line up to the latest time it was asked for
  (frames ask for their presentation time, a little ahead) and holds there until the new line catches up, taking no
  other pair meanwhile; a pair ahead is taken at once. The runtime's session never moves back either.
- The timestamp is a presented frame, so the output latency the device reports is already out of song time. What it
  does not report stays in the audio offset. There is no runtime API for true end-to-end audio latency, and the CDD
  bounds the reported value only loosely (stack report §2.4), so the offset stays necessary.
- `AudioTrack.getUnderrunCount()` is logged per game; the writer keeps at least 200 ms queued (the track's buffer is
  400 ms or the platform's minimum, whichever is larger, filled before `play()`). After the song it writes silence until
  the game closes, so the clock keeps reading real timestamps through the last notes and the results.
- The game's audio offset is the one of the device the track is routed to (`AudioTrack.getRoutedDevice`), taken when
  the clock first runs, in the lead-in; from then on that device is the output Settings and Results show.

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
jumps backwards. The HUD in Compose observes only accuracy and the end of the game, which change when a judgement
does, not every frame. The judgement word and combo are drawn by the render thread, on the same timeline as the notes.

## Input

**Lanes.** A pointer's lane is where it went down: `floor((x − lineLeft) / laneWidth)` with the lanes as they are at the
line, clamped to 0..3, at any height and any lean. It keeps that lane until it lifts; sliding into the next lane does
nothing, like a key. A lane is pressed while any pointer holds it, so the press goes to the runtime with the first
finger and the release with the last. Chords are separate pointers and separate presses, each with its own time; in
landscape the two thumbs usually take two lanes each, and nothing limits the pointers to two.

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
  same page allows games "during gameplay". In landscape the outer lanes reach the screen's edges, so this matters
  more there. The system caps how much of each edge an app may exclude (`View.setSystemGestureExclusionRects`, 200 dp
  per edge), so only the lowest 200 dp of each outer lane's column is asked for, while the game runs (not while
  paused); whether the cap leaves enough on a landscape phone is a check for the phone.

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
  name (speaker, wired, a given pair of Bluetooth headphones), as `bluetooth-a2dp/WH-1000XM4`. A device without one
  uses the default. Outside a game (Settings, calibration, results) the output is the media route Android picks by
  default among the connected devices: Bluetooth or a hearing aid, then wired or USB, then HDMI, then the speaker.
- **Visual offset** belongs to the display and is one value.
- **No separate input offset.** The time from a finger landing to its event timestamp shifts every tap the same way an
  unreported output latency does; judgement cannot tell them apart, so both live in the audio offset. Because the
  phone is fixed and the output varies, keeping the offset per output is enough. The visual offset is calibrated
  without taps, so touch latency cannot leak into it.
- **The lean moves no offset.** It changes where a note is drawn, not when: a note crosses the line at its time at any
  lean and in either orientation, so neither offset depends on them. Near the line a leaning note moves faster, so a
  given visual error shows as a larger gap; the picture step therefore draws with the player's own lean, so the visual
  offset is set on the field that is played.
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
  objects, lane states) at 0.05 ms through its bindings. Through UniFFI's Kotlin bindings every call goes through JNA,
  which marshals structures by reflection, and a frame makes about ten calls plus one `noteGeometry` per visible note
  (up to about 70 in a dense frame). On the emulator with the host's GPU the runtime calls took 1.0 to 1.5 ms per
  frame on average (a debug build; longest frames 3 to 18 ms), which says little about a phone but is too large to
  assume away. It is the first thing the phone measures, in a release build. If it stays above about 1 ms, the fix is one
  runtime call per frame that returns the visible notes with their positions, lane brightness, the recent batches,
  the presentation and the score: a runtime change, listed in plan.md and not made. The prototype already asks only
  what changed (the score after events, the tier words once).
- The renderer allocates nothing per frame; the bindings return new lists each call (`visibleObjects`, batches), so
  garbage collection pauses are watched in the traces.
- Drawing is a hardware canvas (HWUI's GPU renderer) of about 150 rounded rectangles, gradients and two text runs, with
  additive blending for lights. With a lean, the lanes and notes are drawn through one perspective matrix
  (`Canvas.concat`): the GPU projects them, and the CPU does no more work than for flat lanes. Hardware canvases have
  supported perspective transforms for a long time (Android's
  [hardware acceleration](https://developer.android.com/develop/ui/views/graphics/hardware-accel) page lists even
  `clipRect()` under rotation or perspective as supported from API 18); what a phone must show is their cost, since
  rounded shapes under perspective can take a slower path in the GPU renderer than flat ones. If they do, the first
  fallback keeps the canvas: project each note's corners on the CPU and draw all notes of a colour as quads in one
  `Canvas.drawVertices` call (hardware-accelerated from API 29, same page). GLES with instanced quads replaces only
  the draw step if the canvas still misses frames at 120 Hz; the thread, pacing, clock and frame order stay.
- Measured with per-frame logs on the render thread and Perfetto's FrameTimeline, which records expected and actual
  presentation and jank per layer ([FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)). The
  prototype's render thread already logs, every two seconds under the tag `EnsomiPlayfield`, its frame rate, the
  vsyncs it missed, and its average and longest time per frame in the runtime and in drawing.

## What the emulator can and cannot show

The arm64 AVD `ensomi-36` runs the app natively on the Mac, at 1080 × 2424 (923 × 411 dp in landscape). Headless with
its default renderer it draws with SwiftShader on the CPU, at 5 to 14 frames per second; started with `-gpu host` it
draws on the Mac's GPU, and the flat portrait playfield then held 59.5 to 60 frames per second, with 0.8 to 2.9 ms
of drawing and 1.0 to 1.5 ms of runtime calls per frame on average. The captures use the host GPU. Its recordings show
the look and the motion of the playfield, not a phone's frame cost. The lean cost nothing it could measure: with the
Mac busy, 20 seconds of each, one after the other, gave the same median rate (32 to 35 frames per second) and
drawing time (14 to 16 ms) for flat landscape lanes, 40°, 55° and flat portrait lanes. The slowness was the Mac's:
the runtime calls, which draw nothing, took about three times as long as on a quiet Mac too.

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
| Frame pacing | Perfetto FrameTimeline over a 60 s autoplay of the densest sample, at 120 Hz, at no lean, 40° and 55° | Under 0.5 % of frames late; render-thread work p99 under 3 ms at every lean |
| The lean in hand | Players who know 4K on phones play the samples in landscape at 0°, 40° and 55°, and in portrait | 40° reads at speed; far notes are big enough; thumbs land in their lanes; the HUD never covers a lane |
| Refresh rate | `Display.getRefreshRate()` during play after `setFrameRate` | 120 Hz on the Xiaomi unless battery saver is on |
| Multi-touch | Four-finger chords and holds with real hands, presses and releases counted | Every press has its release; no lane stays lit |
| Haptics | 240 fps video with Vibrate on lane touch | Its delay known, to say whether to offer it |
| Heat | 10 minutes of play: thermal status and frame pacing | No drop in pacing |
