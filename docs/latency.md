# Ensomi for Android: latency slots and one calibration screen (accepted)

This design is not built. It was written against `dev/android` at `8a09ddf`, the shipped `android-v0.1.3`, from the
owner's decisions of 2026-11-07, and accepted by the owner the same day, every open question decided as recommended,
with one amendment to which methods count as live ([Open questions, decided](#open-questions-decided-owner-2026-11-07)).
It replaces decision 6 of the keyboard proposal ([keyboard.md](keyboard.md#timing)) and changes timing for touch play
as well as for keys. Once it is built, its screens fold into [design.md](design.md), its timing into
[gameplay.md](gameplay.md), its steps into [plan.md](plan.md) and its checks into [phone-checks.md](phone-checks.md);
[What changes once built](#what-changes-once-built) lists the passages. Until then those documents describe the app as
it is.

Claims about today's app are read from its code at `8a09ddf`; claims about the runtime from ensomi-runtime at
`d090374`, the commit the 0.1.3 build links (README, plan.md "0.1.3 integration"). Nothing here was run on a device.

## Decided (owner, 2026-11-07)

In our words:

- **Audio latency slots.** Three, numbered 0 to 2, each 0 ms by default, each with a name the player may give it and is
  never asked for. Slot 0 is active by default, and the player chooses the active slot by hand. The output device
  chooses nothing: the audio offset kept per output goes.
- **Input latency slots.** Three, numbered 0 to 2, 0 ms by default, names optional. Each input method, touch and
  keyboard, uses one slot; both methods play in the same game, and every keyboard shares the keyboard's slot. By
  default touch uses slot 0 and the keyboard slot 1 ([O1](#open-questions-decided-owner-2026-11-07)).
- **What an input latency holds.** The device's delay and the player's own habit, such as always pressing early. It
  applies to the event times of its method's presses and releases.
- **No visual offset.** The audio slot lines the picture up with the sound; the input slot lines the hits up.
- **One calibration screen,** with no steps and no order. The game's renderer plays the calibration chart with audible
  clicks. The player moves one audio value and one input value as they like, judging by sight and sound alone or by
  pressing too, by touch or with keys; presses are judged and the judgements shown. Every change is kept at once in the
  slot being edited, with no Use button. The player chooses which audio slot and which input slot are edited and may
  name them. Nothing suggested is ever applied.
- **Results** keep their judgements and timing: the timing chart and the average error ("Your hits were 6 ms early
  on average."). Use suggested offset goes.
- **The pause panel's** "Sound moved to … Its audio offset applies from the next game." no longer fits; this proposal
  replaces it ([Pause after a change of output](#pause-after-a-change-of-output)).

## What good means

1. **The picture meets the sound with one value per output.** A note crosses the line as its sound is heard once the
   slot the player keeps for that output is set. No second value is needed for the picture.
2. **Each way of pressing is judged on time.** Touch and keys each have their own input latency and both play in one
   game, with no input judged late because the other method's latency differs, save the first presses of a method that
   joins a game with a larger latency ([Live methods](#where-the-input-latency-is-applied)).
3. **The player is in charge.** Nothing chooses a slot or applies a value. Calibration asks for no order, shows what
   each change does as it is made and keeps every change.
4. **A game keeps its latencies from start to end,** as it keeps its offsets now: the session never moves back in time.
5. **Nothing is lost on the update.** Touch play on each output a tester has set up is as it was (O5).

## How offsets reach the game today

Read from the code at `8a09ddf`:

- **Storage.** `StoredSettings` keeps `audioOffsets`, a map from `OutputRoutes.key` (the device type and product name,
  such as `bluetooth-a2dp/WH-1000XM4`) to milliseconds, and one `visualOffsetMs`. `forOutput(key)` builds the
  `PlaySettings` of one output, and `DataStorePlaySettingsStore` builds them again whenever the route changes; a
  changed audio offset is written for the current route.
- **A game.** `PlayScreen.controller` builds `PlayTiming(audioOffsetMs, visualOffsetMs, scrollTimeMs)` from the
  settings and hands the controller `resolveAudioOffset`, which `PlayController.frame` calls once, when the clock first
  runs: the offset of the output `AudioTrack.getRoutedDevice` reports ("Audio offset for this game" in the log). The
  game keeps it to the end. A change of output pauses the game, and the panel says "Sound moved to … Its audio offset
  applies from the next game." (`play_output_moved`).
- **A frame.** `gameplayMs = songNow + audioOffset`; `renderMs = presentSong + audioOffset + visualOffset`; each
  input's chart time is `songTimeMs(eventNanos) + audioOffset`; inputs are delivered as soon as their chart time is at
  or before `gameplayMs`, and the session advances to `gameplayMs − 100`.
- **Listen & Play.** An ambient game takes the recognition session's trim in place of the audio offset
  (`timing.copy(audioOffsetMs = ambient.offsetMs)`); the visual offset still applies.
- **Settings' preview.** `PlayTiming(0.0, visualOffsetMs, …)`: the visual offset and no audio offset.
- **Calibration, sound step.** The pad's taps give errors to the nearest click (`CalibrationTaps.errorMs`); the
  suggestion is minus the median of the last 16 song-time errors, and Use writes it as the route's audio offset, with
  a `CONFIRM` tick.
- **Calibration, picture step.** A `PlayController` on the clicks' clock (`ownsClock = false`) with judging off and
  `PlayTiming(0.0, visualOffsetMs, …)`; the slider moves the visual offset live.
- **Results.** The runtime's `suggestedAudioOffsetMs` (minus the mean error of hit taps and hold heads) is offered for
  the output used when it is 3 ms or more (`MIN_SUGGESTION_MS`), and Use writes it.
- **The runtime** knows no offset. `InputEvent.time_ms` is "chart time (audio time plus the audio offset)"
  (`crates/gameplay/src/session.rs`), and `GameplayFeedback.record_input` takes UI time for the lane lights, which no
  offset touches.

**What this means for the picture step**, by the formulas below and not measured: the picture step lines notes up with
no audio offset, while a game draws at the audio offset plus the visual offset. The visual offset it sets is right in
play only on an output whose audio offset is 0; on any other, the notes in play are off from the sound by that
output's audio offset. The model below has no such step.

## The model

### Two differences decide play

What the player sees and gets depends on two differences from the song time the clock reports:

- **where the picture is:** `renderMs − songMs(expectedPresentation)`, which is audio offset plus visual offset today;
- **where a press is judged:** `inputMs − songMs(eventTime)`, which is the audio offset today, the same for every
  input.

The session's own time, `gameplayMs`, changes no judgement. The runtime resolves every miss "at its own chart time",
so "the events and the final score are therefore a function of the inputs alone" (the `Session` documentation); the
session's time decides only when a miss is declared, within the allowance, and how the judgement word ages.

So today two values set two differences, and a keyboard brings a third: where a key press is judged. One audio offset
cannot hold touch's delay and a keyboard's at once, which is why keys forced a choice (keyboard.md's former decision
6). With an input latency per method, the picture's difference is −A and each method's is −A − I[m]: three values for
three differences. A visual offset would be a fourth value for the same three, so it goes, and nothing is lost. Any
setting of today maps onto the new values with the same game for touch:

```text
A         = −(audioOffset + visualOffset)
I[touch]  = visualOffset
```

Check: the picture is at −A = audioOffset + visualOffset, and touch is judged at −A − I[touch] = audioOffset, as today.

### What the values hold

Let u be the output's latency that its timestamps do not report (the sound is heard u later than the frame position
says), v the display's delay past a frame's expected presentation, and d a method's delay from contact to event
timestamp. Then:

- **The picture meets the sound when A = u − v.** The frame drawn for presentation P appears at P + v, when the song
  time heard is `songMs(P) + v − u`, and it shows the note of chart time `songMs(P) − A` at the line.
- **A press on the heard sound is on time when I[m] = d + v.** A note of chart time n is heard at `songMs = n + u`; a
  press then is stamped d later, so `inputMs = n + u + d − A − I[m]`, which is n when `I[m] = u + d − A = d + v`.

A depends on the output and not on the input; I[m] depends on the input method and the display, which is fixed on a
phone, and not on the output. That is why the audio slot goes with the output, chosen by hand, and the input slot
with the method. An input latency also takes the player's habit: someone who presses 8 ms early sets 8 ms less. It
takes v as well, the same for every method on one phone and small while frames show at their expected presentation
time ([gameplay.md, The frame](gameplay.md#the-frame)). A player who judges by the picture alone gets the same I[m]:
a note crosses the line visibly at `songMs = n + A + v`, and a press then gives `inputMs = n + v + d − I[m]`.

### Formulas

With A the game's audio latency and I[m] the input latency of method m:

```text
gameplayMs = songMs(now) − A
renderMs   = songMs(expectedPresentation) − A
inputMs    = songMs(eventTime) − A − I[method]
inputClock = gameplayMs − max(I[m] over the live methods, 0 before any), never moving back
each frame: deliver the queued inputs with inputMs ≤ inputClock, in inputMs order (then sequence),
            then advance(inputClock − 100)
```

With one method at I = 0, these are today's formulas with `audioOffset = −A` and no visual offset
([gameplay.md, The frame](gameplay.md#the-frame), [Offsets and calibration](gameplay.md#offsets-and-calibration)).

### Sign

A positive value means later (O2). **Audio latency +120 ms:** the sound comes 120 ms after the picture would show its
note, so the game holds the picture and the judging back by 120 ms. **Input latency +30 ms:** presses reach their
timestamps 30 ms late, or the player presses late, so they are judged 30 ms earlier. Hits that are late call for more
input latency; notes that cross the line before their sound call for more audio latency. Today's audio offset has the
other sign: a Bluetooth pair that needed −120 ms of offset needs +120 ms of audio latency. Values show as "+120 ms",
"0 ms" and "−8 ms" (`PlaySettings.formatOffset`).

### Where the input latency is applied

**In the app, not the runtime.** `PlayController` subtracts I[m] in song time, after the clock has converted the event
time, so a latency never moves an input across a pause or the clock's hold. `LaneInput` gains its `source`, which
the keyboard proposal adds for its own reasons ([keyboard.md, Lanes from several
sources](keyboard.md#lanes-from-several-sources)); `PlayTiming` carries A and I for each source. The touch, key,
clock and frame logs keep the raw times. The runtime takes chart times and needs no change. The autoplay's script has
no input latency.

**Why the input clock.** The session's clock is "the largest watermark or input time seen", and an input stamped
before it "was delivered late and is handled at the clock instead", counted in `late_inputs` (`Session::input`).
Today an input is delivered as soon as its chart time is due. With two methods at different latencies, a key press
moved 30 ms earlier would often arrive after a touch made just before it, find the session's clock already past it and
be judged late. Holding every input until the gameplay time less the largest live latency has passed its chart time
delivers them in chart-time order, as long as each is handled within the allowance; advancing to the input clock less
100 ms keeps the allowance as it is.

**The cost.** Only a game in which both methods have pressed pays it. While their latencies differ, the hits of the
method with the smaller latency are decided, and their flares and words shown, later by the difference. Lane lights do
not wait: they follow `recordInput` on UI time. Misses show up to 100 ms plus the largest live latency after they fall
due, instead of 100 ms. A player using only keys, or only touch, never waits, and with both methods on one slot nothing
waits.

**Live methods** (the owner's amendment, 2026-11-07). A method counts as live only once it has pressed in this game,
touch included; a keyboard being connected does not make it live ([keyboard.md, Who is
connected](keyboard.md#keyboards-coming-and-going)). Before the first press of a game the input clock is the gameplay
time. A player using only keys, or only touch, has one live method, so each input is delivered as soon as its own
latency allows and nothing waits for the other method. When a second method first presses with a larger latency, the
input clock holds for the difference, since it never moves back; that press, and any of that method made within the
difference of it, can be late, and is counted. A second method with a smaller latency changes nothing.

**Ranges.** Both kinds go from −300 to +300 ms in 1 ms steps, today's offset range.

### Listen & Play and the previews

- **Listen & Play** keeps its own trim in place of the audio slot, with today's sign: no output plays, and the room's
  sound has its own delay. The input slots apply, as in any game, since they belong to the method. Today an ambient
  game also draws with the visual offset; after the migration (O5) a tester whose visual offset was not 0 sees an
  ambient game's picture and judging move together by it, and may trim the join again.
- **The playfield preview in Settings** has no sound and is pressed by the autoplay: no slot applies. Today it takes
  the visual offset.
- **About › Playfield preview** plays the sample on a clock without sound: no audio slot; touches and keys on it are
  judged with their input slots.

## What the player sees

### Settings › Sound and timing

The section keeps Music volume and Calibrate timing. In place of "Audio offset for Phone speaker" and "Visual offset":

- **Audio latency.** A segmented button row of the three slots, "0", "1" and "2", each segment showing its name under
  the number when it has one, the active slot selected. Under it, the active slot's value with −/+ steppers in 1 ms
  steps and **Name**, an icon button ("Name audio slot 1"). Supporting text: "Moves the notes and the judging together.
  Keep a slot for each speaker or pair of headphones you play on, and choose it here before you play." A line names
  the output Android would play on now ("Sound now: WH-1000XM4"), as a hint; it chooses nothing.
- **Input latency.** One row per method, Touch and Keyboard: "Touch uses slot 0" with a menu of the three slots, the
  slot's value with −/+ steppers, and Name. Supporting text: "Moves when your presses count. Raise it if your hits are
  late, lower it if they are early. Every keyboard uses the Keyboard row's slot." When both methods use one slot, the
  two rows show the same value and change it together.

A slot shows as its number, with its name after it when it has one: "1 · WH-1000XM4". No screen asks for a name or
marks a slot without one. Values are kept as they change, as every setting is now. At large font sizes the
segments keep their numbers and drop names they cannot fit, and the steppers sit under their labels
([design.md, Accessibility](design.md#accessibility)).

### Naming a slot

Name opens a dialog, "Name audio slot 1" (or "Name input slot 0"), with a one-line text field of at most 24 characters
and the placeholder "No name", and Clear name, Cancel and Save. Saving it empty leaves the slot without a name. The
dialog is a window of its own, so it keeps the input method even over the calibration screen, which keeps the input
method away from its own window ([keyboard.md, What stands before the app](keyboard.md#what-stands-before-the-app)).
Names are free text: on screen only, never in a log, the journal or an export ([diagnostics.md, Traces, privacy and
limits](diagnostics.md#traces-privacy-and-limits)).

### Timing calibration: one screen

In the game's dark presentation, from Settings › Calibrate timing. The top bar holds Back and "Timing calibration".
In portrait the field is at the top and the controls under it; wider than tall, the field is on the left and the
controls on the right, as the picture step is laid out now.

- **The field.** The play renderer, with the player's lean and lane width, draws a note onto the line on every click
  of the click track (`SampleCharts.CALIBRATION`), and judges. The clicks play from the moment the screen opens until it
  closes, through the output play uses. Notes, flares, the judgement word and the lane lights are the game's own. Every
  press counts for the note's lane: a finger anywhere on the field, or any lane key
  ([keyboard.md, Calibration with keys](keyboard.md#calibration-with-keys)), so the player presses with the finger
  they play with.
- **Audio latency.** The three slots and the active one's value, as in Settings, with a slider from −150 to +150 ms
  beside the steppers, which reach ±300. Choosing a slot here chooses the active slot, the same choice as in Settings.
  The field takes a new value on its next frame, as the picture step takes the visual offset now.
- **Input latency.** Touch or Keyboard, a segmented choice of which method's slot is edited (O3), Touch first, or
  Keyboard when a keyboard is connected as the screen opens; "uses slot 0" with the slot menu; the value with steppers
  and a slider. The field judges every press with its own method's slot, as play does.
- **Readings,** for the method chosen: the strip of the last 16 hits from early to late, and "Last 16 hits: 6 ms early
  on average." Each error is the runtime's (`JudgementEvent.offsetMs`), shown as it would be judged with the values now:
  a hit made before a change of A or I moves by that change (its error less the rise in A + I), as the sound step's
  strip shows taps at the offset now. **Clear hits** empties the readings and changes no value.
- **Help:** "A note falls onto the line with each click. Move Audio latency until they meet. Press with the clicks to
  see where your presses land, and move Input latency until they gather at the middle. Do either, both, in any order.
  Changes are kept as you make them."
- **Nothing to confirm.** No Use, no Done, no suggestion. Back leaves; every change was written when it was made.

**No order, and what each value moves.** The audio value moves the picture and the judging together, so it moves the
readings too; the input value moves only the presses. Lining up the picture first and the presses second moves each
value once; the other way round, the presses move again when the picture is lined up. The screen shows this as it
happens and asks for no order. A player who never presses sets the audio value by sight and sound alone; one who
plays with the sound off can set the input value by pressing on the notes.

**Pauses and outputs.** The clicks pause when the app leaves the foreground or another app takes audio focus, as now;
the field then says "Paused. Tap the field or press a key to go on.", and that press goes on. When the sound moves to
another output, the readings start over, as the taps do now; the slots stay as chosen, and the hint names the new
output.

**Reused from today's calibration:** `ClickTrack` and `AudioClickTracks` (the click track through `GameAudio` and
`AudioPlayClock`, the route callback and its log line); the picture step's `PlayController` on the clicks' clock with
`ownsClock = false`, now judging; `SampleCharts.CALIBRATION`; the picture step's live value on `PlayfieldView`; the
sound step's `TapStrip` and its rebased errors; the pause and resume on lifecycle and audio focus
(`rememberAudioFocus`); `CalibrationTaps.median` for the strip's middle. **Retired:** `CalibrationPadView` (the field
takes the presses), `CalibrationTaps.suggestionMs` and the median of 16 as a suggestion, the Use button and its tick,
the two-step segmented control, the visual offset slider, and the screen's 10 ms clock polling (the field's render
thread reads the clock every frame).

**Accessibility.** The field is one element ("Calibration field: a note falls onto the line with each click."); a
TalkBack double tap is not timed, as on the pad now. The values are sliders and steppers that announce their values;
the readings line is a polite live region that speaks when its text changes. With Remove animations on, the field
follows the playfield's rules ([design.md, Motion](design.md#motion)). No haptics while pressing, as now.

### Results

Results keep the score, the tiers, the longest combo, the timing chart and the average ("Your hits were 6 ms late on
average."). The suggestion line and Use suggested offset go. Two lines are added (O6):

- the slots the game used: "Audio latency 1 · WH-1000XM4. Touch on input 0.", with "Keyboard on input 1" when keys
  played;
- when both methods hit notes, the average per method: "Touch: 4 ms late on average. Keys: 9 ms late on average."

A hit's method is the source of the input whose session call returned it: a tap is decided by its press, a hold by its
release ([gameplay.md, Judgements through the runtime](gameplay.md#judgements-through-the-runtime)).

### Pause after a change of output

The game still pauses when its sound moves. The panel says: **"Sound moved to Pixel Buds. This game keeps audio
latency 0 · Speaker."**, or "This game keeps audio latency 0." for a slot without a name (O4). The game cannot take
another slot: Settings is not reachable during a game, and a new A would move the session's time back, which it never
does, or forward past notes. The next game, or Restart, uses the active slot then.

### Haptics

The `CONFIRM` tick on calibration's and Results' Use goes with the buttons. Calibration stays without haptics while
pressing ([design.md, Haptics](design.md#haptics)).

## Storage and migration

`StoredSettings` gains two objects, in the same `settings.json` through the same DataStore store:

```json
"audioLatency": {"active": 0, "slots": [{"name": "", "ms": 0}, {"name": "", "ms": 0}, {"name": "", "ms": 0}]},
"inputLatency": {"slots": [{"name": "", "ms": 0}, {"name": "", "ms": 0}, {"name": "", "ms": 0}],
                 "touch": 0, "keyboard": 1}
```

Read as the file reads now: a missing object takes its defaults, values are clamped to −300..300, a name is trimmed
and cut to 24 characters, a list that is not three slots is filled or cut to three, and an index outside 0..2 reads its
default. `PlaySettings` carries the active audio slot's value and each method's input value to the screens; a game
copies them into its `PlayTiming` when it starts.

**Migration** (O5, option a), once, when a file has no `audioLatency`:

- **Touch's slot 0** takes the old visual offset V; slots 1 and 2 stay at 0.
- **The audio slots** take the outputs that have a stored offset, in the order the file lists them (the order their
  offsets were first set), up to three: slot k gets `−(A_o + V)` for its output o and is named as the screens name that
  output now ("Phone speaker", "WH-1000XM4"). With no stored output and V not 0, slot 0 gets −V, the latency of an
  output without an offset. Outputs past the third are dropped; the release notes say so.
- **The active slot** is the slot of the output Android would play on at that first start, or 0.
- `audioOffsets` and `visualOffsetMs` are read for this only and left out of the next write.

By the identity above, touch play on each carried output, with its slot chosen, has the same picture and the same
judging as before. A 0.1.x build that reads a new file ignores the new objects and plays at 0 ms.

## Diagnostics

- **`EnsomiPlay`** at the start of a game: "Latency for this game: audio slot 1 +142 ms; touch slot 0 +8 ms; keyboard
  slot 1 +30 ms", in place of "Audio offset for this game"; each method's first press, when it becomes live, with the
  input clock's hold if it made one. At game over: the methods that pressed, and hits timed per method, with their
  averages. Slots are numbers; names are never logged.
- **`EnsomiCalibration`**: each press (its event time, method, slot and the runtime's error), each change of a value
  (kind, slot, old and new), and the time of click 0, as now, so a check can recompute the errors.
- The touch, key, clock and frame logs keep raw times and need nothing new. Latency adds no journal event and no
  summary field: it says nothing about a crash.

## What changes once built

**design.md**

- *Pause*: the output line becomes the one above.
- *Results*: the suggestion and Use go; the slots line and the per-method averages come.
- *Settings*: Sound and timing lists music volume, audio latency, input latency and Calibrate timing; "the audio
  offset is kept per output device" becomes the slots and the methods' slots.
- *Timing calibration*: the two steps become the one screen above.
- *Haptics*: the `CONFIRM` on applying a suggested offset goes.
- *Accessibility*: TalkBack in calibration describes the field, not the pad.
- *What comes from the Swift app*: "Audio offset per output device, one visual offset" and "Two steps; the visual step
  has no taps" change to the slots and the one screen.
- *The prototype in the app*: the stores write slots, not per-output offsets; calibration is the one screen.

**gameplay.md**

- *What good means* 1: "once the offsets are set" becomes "once the audio latency slot kept for that output is set".
- *The clock*: the game's audio offset taken from the routed device when the clock first runs becomes the active audio
  slot, taken when the game starts. "The picture follows the audio" loses the visual offset.
- *The frame*: the formulas above, with the input clock.
- *Input*: times carry their method's input latency; the allowance runs from the input clock.
- *Offsets and calibration*: rewritten from this document's model, values, sign, migration and calibration; the old
  "No separate input offset" reasoning goes, since keys give a second method.
- *Measurements a phone must make*: "Unreported output latency" becomes the audio value per output by sight and sound
  (it holds u − v), with the external recording kept for u alone; "per-route defaults" goes, since the player chooses
  slots.

**plan.md** gains the steps below; **phone-checks.md** rewrites rows 4 and 5 and takes keyboard.md's row 18 in its
new form.

## Checks

### JVM tests

- `LatencySettingsTest`, in place of `OutputSettingsTest`: defaults (three slots of each kind at 0 ms, audio slot 0
  active, touch on 0, keyboard on 1); names optional, trimmed and capped; values clamped; a file without the objects
  reads the defaults; the migration of a 0.1.3 file with offsets for the speaker, wired headphones, a Bluetooth pair and
  a fourth output and a visual offset gives slots whose picture and touch judging equal the old ones on each carried
  output, the fourth dropped, the old fields not written again; a round trip keeps everything.
- `PlayTimingTest`, on the controller's arithmetic as plain functions: today's formulas at I = 0 and A = −audioOffset;
  two methods 30 ms apart with inputs interleaved and handled within the allowance give no late input and chart-time
  order; a method is live only from its first press, touch included, so a game by one method alone never waits for
  the other's latency; the input clock holds, and never goes back, when the keyboard first presses mid-game with a
  larger latency, and that press is counted late when an earlier touch passed it; the watermark is the input clock less
  100 ms; an input across a pause keeps its song time.
- `CalibrationReadingsTest`: the last 16 hits per method; rebasing after a change of A or of I; the average.
- `PlayResultTest`: averages per method; no suggestion.
- `CalibrationTapsTest` keeps `errorMs` and `median` and drops `suggestionMs`.

### On the emulator

- **Migration.** Push a 0.1.3 `settings.json` with offsets for two outputs and a visual offset, launch, and read the
  file back after a setting changes: the slots, names, touch's slot and the active slot as the rule says; Settings shows
  them.
- **Calibration.** Presses from `adb shell input tap` on the field, and later from `input keyevent` once keys play
  (keyboard.md, K5): the runtime's errors equal those recomputed from the logged event times, click 0's time and the
  values, as step 8 recomputed its taps; +10 ms of input latency moves the readings 10 ms earlier, and +10 ms of audio
  latency moves the drawn notes (a screen recording) and the readings; every change kept after a force-stop; no Use or
  Done anywhere (UI Automator dump).
- **Play.** A game from `adb shell input tap` with all slots at 0 is judged as before; with touch's slot at +30 ms its
  hits move 30 ms earlier; Results show the slots line and no suggestion.
- **Screens.** Settings and the calibration captured light and dark (chrome), at 200 % font and in landscape, with a
  named and an unnamed slot; the name dialog; a TalkBack walk.

The emulator has one output, so the pause panel's line after a change of output is a phone check.

### On a phone

Rows for [the runbook's checklist](phone-checks.md#the-checklist), in place of today's rows 4 and 5:

| # | Check | What to do | What to record |
| --- | --- | --- | --- |
| 4 | Calibration per output | Settings › Calibrate on the speaker, on wired headphones and on Bluetooth, each on its own audio slot: line the notes up with the clicks by sight and sound, three times, writing down the value; then, on the speaker, press with the clicks by touch three times and write down the touch value that centres the presses | Each audio value per output (for example `speaker: −4, −2, −5`) and each touch value |
| 5 | Change of output | During a game: plug in wired headphones; resume; unplug them; then connect and disconnect Bluetooth headphones | Whether the game paused each time, what the pause panel said, and whether the sound went on on the new output after Resume |

Good when the three audio values of one output are within 5 ms of each other, as gameplay.md asks of the calibration
now. Keys against touch is keyboard.md's row 18.

## Build plan

Steps in the manner of [plan.md](plan.md); a step is done when `assembleDebug`, `testDebugUnitTest` and `lint` pass,
its emulator check passes and the screenshots of the screens it touches were looked at. No step adds a library, a pin
or a permission.

| Step | Builds | Emulator check | Phone check | Needs |
| --- | --- | --- | --- | --- |
| L1 | The model: the slots and the methods' slots in `StoredSettings`, the migration, `PlaySettings` and `PlayTiming` carrying A and I per source; `PlayController`'s formulas, input clock and watermark; `resolveAudioOffset`, the per-output offset and the visual offset removed; the previews without offsets; the game log | The JVM tests; the migration check; a game by `input tap` at 0 and at +30 ms | — | `LaneInput.source` (keyboard.md K1, or added here first); O1, O2, O5 |
| L2 | Settings › Sound and timing: the audio slots, the input rows, the name dialog | Captures; UI Automator dumps; values kept after a force-stop | — | L1 |
| L3 | The calibration screen: the field judging on the click track, both values, the slot and method choices, the readings, nothing to confirm; the pad, the suggestion and the steps removed; the calibration log | The calibration check; TalkBack walk; landscape | 4 | L1, L2; O3 |
| L4 | Results and pause: the suggestion removed, the slots line, the per-method averages; the pause panel's line | Captures; a game by `input tap` to its results | 5 | L1; O4, O6 |
| L5 | design.md, gameplay.md, plan.md and phone-checks.md updated as [What changes once built](#what-changes-once-built) lists | — | — | L1–L4 |

## Open questions, decided (owner, 2026-11-07)

The owner took every recommendation below, and amended which methods count as live for the input clock ([Live
methods](#where-the-input-latency-is-applied)). Each item keeps its options for the record.

1. **O1. The default methods' slots.** (a) Touch on input slot 0, the keyboard on input slot 1. (b) Both on slot 0
   until the player separates them. **Decided: (a).** Keys and touch differ by their own delay, and separate slots keep
   a keyboard's value from moving touch.
2. **O2. The sign.** (a) Latency: positive is later, for the sound and for presses. (b) Today's audio offset sign, and
   an input offset added to chart time. **Decided: (a).** The owner calls them latency slots, the common cases
   (Bluetooth sound, any input) come out positive, and "late hits, raise it" reads plainly. Testers' values change sign
   on the update, which the migration does for them.
3. **O3. Which input slot the calibration edits.** (a) An explicit choice, Touch or Keyboard, with that method's slot;
   the readings count that method's presses, and every press is judged with its own method's slot. (b) The method that
   pressed last. **Decided: (a).** With every change kept at once, a stray tap while setting the keyboard's
   value must not switch the slot under the slider.
4. **O4. The pause panel after a change of output.** (a) A line naming the slot the game keeps. (b) The line and a slot
   choice on the panel, used from Restart and the next game. **Decided: (a).** Settings stays out of a game, and
   the panel stays small; the hint in Settings and the calibration names the output when the player chooses.
5. **O5. Testers' offsets.** (a) Carried over as [Storage and migration](#storage-and-migration) says, so touch play on
   each carried output is as it was. (b) Every slot starts at 0, and testers calibrate again. **Decided: (a).**
   It costs one pure function and its test, and it keeps a tester's game the same across the update.
6. **O6. What Results add.** (a) The slots line, and the averages per method when both methods hit notes. (b) Only
   today's overall average. **Decided: (a).** The input slots are per method, so the per-method average is the
   number a player would move; the slots line says which slot that is.

Settled here rather than asked, each for the reason given above: the input clock (which methods count as live is the
owner's amendment, [Live methods](#where-the-input-latency-is-applied)); the ranges; the calibration chart with a note on each click and every press counting for its lane; the readings as the
average of the last 16; the audio slot chosen in calibration being the active slot; Listen & Play keeping its trim.

## Sources

- The app at `8a09ddf`: `settings/StoredSettings.kt`, `PlaySettings.kt`, `DataStorePlaySettingsStore.kt`,
  `OutputRoutes.kt`, `Calibration.kt`, `CalibrationPad.kt`, `CalibrationScreen.kt`, `SettingsScreen.kt`,
  `PlayfieldPreview.kt`; `play/PlayController.kt`, `PlayClock.kt`, `PlayScreen.kt`, `PlayfieldView.kt`,
  `ResultsScreen.kt`, `PlayResult.kt`, `SampleCharts.kt`; `audio/ClickTrack.kt`;
  `recognition/…/RecognitionController.kt` (the ambient trim); `res/values/strings.xml`.
- ensomi-runtime at `d090374` (`codex/android-no-kleidiai`, workspace version 0.3.2; its `crates/gameplay` is the same
  at `main`, `edf4066`): `crates/gameplay/src/session.rs` (`InputEvent`, `Session`'s documentation, `input`, `advance`,
  `late_inputs`), `feedback.rs` (`record_input`, lane brightness on UI time), `rules.rs`; `crates/ffi/src/lib.rs` (the
  bindings pass inputs through unchanged).
- [gameplay.md](gameplay.md): The clock, The frame, Input, Offsets and calibration, Measurements a phone must make;
  [design.md](design.md): Pause, Results, Settings, Timing calibration, Haptics, Accessibility;
  [diagnostics.md](diagnostics.md): privacy of free text.
- Android: [`AudioTrack.getTimestamp`](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp))
  (presented frames; what the timestamp does not report stays in the latency);
  Material 3 [segmented buttons](https://m3.material.io/components/segmented-buttons/guidelines) and
  [dialogs](https://m3.material.io/components/dialogs/guidelines).

## Not verified

- The picture step's error in play on outputs with a non-zero audio offset is derived from the code and the formulas,
  not measured.
- u per output, v per phone and d for touch and for each keyboard: phone measurements (rows 4, keyboard.md 18 and 19).
- Whether players line the picture up with the sound to within 5 ms by sight and sound on one screen, which today's
  sound step did by ear alone (row 4).
- Whether a hit decided later by the input clock, while the methods' latencies differ, can be seen or felt.
- Whether three slots of each kind are enough; the owner set three.
- The names the screens give outputs at migration on HyperOS (the product names the phones report).
- A TalkBack walk of the new screen.
