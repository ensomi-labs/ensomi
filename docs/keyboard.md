# Ensomi for Android: hardware keyboard play (accepted)

This is the design for issue [#29](https://github.com/ensomi-labs/ensomi/issues/29), Bluetooth keyboard input. It was
written against `dev/android` at `8a09ddf`, the shipped `android-v0.1.3`, revised after the owner's decisions of
2026-11-07 ([Decided](#decided-owner-2026-11-07)) and accepted by the owner the same day, every open question decided as
recommended, with two amendments ([Open questions, decided](#open-questions-decided-owner-2026-11-07)). It is built step
by step ([Build plan](#build-plan)). Once it is built, its screens fold into [design.md](design.md), its input path into
[gameplay.md](gameplay.md), its steps into [plan.md](plan.md) and its checklist rows into
[phone-checks.md](phone-checks.md). Until then those documents describe the app as it is. Timing for keys, and for touch
beside them, is a design of its own, [latency.md](latency.md), accepted the same day: input latency slots per input
method, audio latency slots chosen by hand and one calibration screen.

**In scope:** play with a hardware keyboard (in practice a Bluetooth keyboard on a phone or tablet), the key map and its
test, keyboards connecting and disconnecting, the keys around the game, diagnostics and checks. **Not in scope**, by
the owner's decision: gamepads, MIDI and other controllers. The input path leaves room for them ([Lanes from several
sources](#lanes-from-several-sources)) and plans none.

The Swift app on `dev/swift` (at `623feaa`) plays from the keyboard and is the reference for behaviour and the timing
contract (`Mania4KKeyBindingSet` and `Mania4KKeyboardInputRouter` in `Mania4KDomain.swift`, `docs/gameplay-timing.md`).
This design uses Android's APIs and ports no Swift code. API levels and behaviours were checked against
developer.android.com, AOSP source at `android-16.0.0_r1` unless another revision is named, the Compose 1.12.1 sources
that the pinned BOM 2026.09.00 resolves to, ensomi-runtime at `d090374` (the commit the 0.1.3 build links) and
osu!lazer at `ppy/osu` `77df204`. [Sources](#sources) lists them; [Not verified](#not-verified) lists what is still
open.

## What good means

1. **A key counts when it went down.** The judged time is the key event's own timestamp, through the same clock, queue
   and 100 ms allowance as a touch ([gameplay.md, Input](gameplay.md#input)), with the keyboard's input latency
   ([latency.md](latency.md#where-the-input-latency-is-applied)).
2. **Every press has its release.** One press and one release per lane reach the runtime, whether a lane is held by a
   finger, a key, or both, and nothing stays pressed after a pause, a cancel or a keyboard's removal.
3. **A keyboard coming or going never restarts a game,** and changes nothing else in it. A keyboard lost mid-song is
   the player's to deal with (D4).
4. **The keys are the player's.** One map of five slots, Lane 1 to 4 and Pause, by their place on the keyboard, Z X .
   / and Escape by default, changed in one place, with a test that shows what the app sees held and says plainly when
   a keyboard reports fewer keys than are pressed.
5. **Lane keys play under any input method,** and never start word composition (D6).
6. **The app works from the keyboard.** Pause, resume, results and every screen around the game move with Tab, the arrow
   keys, Enter, Space and Escape, with focus always visible.
7. **Touch is unchanged by keys.** A player without a keyboard sees and gets what touch play gives; latency.md's
   changes apply to touch as well.

## What the player sees

### Play with a keyboard

Nothing changes on the play screen until a keyboard is connected. While one is (any non-virtual keyboard with letter
keys), each key cap in the tray carries the label of its lane's key, as the Swift play screen does over its caps. The
caps stay where they are and touch stays live, so a player can switch between fingers and keys at any time, or use both
(D2). A keyboard connecting or disconnecting during a game shows or hides the labels and nothing else: no notice, no
pause (D4).

The label is the character the key produces in the layout Android uses for that keyboard ([Key identity and the
map](#key-identity-and-the-map)): for the Z position, "Z" on a US layout, "Y" on German QWERTZ, "W" on French AZERTY.
Letters, digits and punctuation show their character; Space shows "Space", Escape "Esc" and the arrow keys show
arrows. A lane with no key (O10) has a cap without a label. The labels are drawn by the render thread in screen space,
like the tray itself, so they never lean ([gameplay.md, The lean](gameplay.md#the-lean)).

Listen & Play's games, the playfield preview opened from Settings › About and Play again all take keys as they take
touches. The playfield preview inside the Settings list takes neither; the calibration screen takes both
([Calibration with keys](#calibration-with-keys)).

### Keys around the game

"The game" is the play screen: playing, the count-in and the pause panel. "The Pause key" is whatever key the map gives
Pause, Escape by default.

| Where | Key | What it does |
| --- | --- | --- |
| Playing | The four lane keys | Press and release their lanes |
| | The Pause key | Pauses, as the Pause button does |
| | Back (a keyboard's own Back key, or Meta + Escape, which Android turns into Back) | Pauses, as Back does now |
| | Escape, Space, Tab, arrows and Enter (numeric Enter and the D-pad centre with it, which Compose's clickable takes as Enter) when the map does not hold them | Nothing: taken by the game, so focus never moves onto the HUD under the player (O2, O3) |
| | Volume, media keys and anything the system handles | Passed on unchanged |
| Count-in | Lane keys | Nothing, as touches during the count-in are dropped now |
| | The Pause key | Back to the pause panel |
| | Escape, Space, Tab, arrows and Enter when the map does not hold them | Nothing, as while playing |
| Paused | The Pause key | Resumes with the count-in (O1) |
| | Enter, or Space when the map does not hold it | Presses the focused button; Resume has focus when the panel opens after a key |
| | Lane keys | Nothing (O4) |
| | Escape when it is not the Pause key | Nothing (O2) |
| | Tab and arrows | Move between Resume, Restart and Quit |
| | Back | Leaves the game, as now |
| Results | Enter, or Space when the map does not hold it | Play again, which has focus first when the game ended after a key |
| | Lane keys | Nothing (O4) |
| | Escape, unless it plays a lane | Back to song (O2) |
| | The Pause key, when it is not Escape | Nothing: there is nothing to pause |
| Other screens | Tab, arrows, Enter, Space | Compose's focus navigation and activation |
| | Escape | Back, on every screen but the Library (O2) |
| TalkBack notice before play | Escape | Back, as the dialog handles it itself |

**One press, one action.** The Pause key acts only on a fresh down, a repeat count of 0. That down pauses, resumes or
returns to the panel, by the state the game is in when it comes, and the key is then spent until its up: its repeats
and its up are taken and do nothing, whatever the screen shows by then. A held Escape therefore never pauses and
resumes, and its up never reaches the activity as Back on the panel that its own down opened.

**Lane keys and the game's buttons.** A lane key pressed just after the pause panel or Results appear would otherwise
reach the focused Resume or Play again. Compose's clickable presses on a down of Enter, numeric Enter, the D-pad centre
or Space and clicks on the matching up, and only when it saw that down itself (`Clickable.kt`, Compose Foundation
1.12.1), so a key already held when the panel appeared does nothing, while a fresh Space would resume or start again.
By O4, the play screen and Results take every lane key's down, repeats and up while the panel, the count-in or Results
show; Enter and the Pause key act there, lane keys do not.

**Space** is a key like any other in the map (O3): it may play a lane or pause, and when the map does not hold it, it
does nothing in the game. A pause on a mis-pressed key loses the passage, so no key pauses unless the map says so.

Focus follows Android's guidance for Compose
([focus](https://developer.android.com/develop/ui/compose/touch-input/focus)): a logical first focus, Tab and arrow-key
order, focus restored after a panel closes, and a visible focus state. Stock Material 3 buttons take focus only when the
system is not in touch mode, so asking for focus on Resume or Play again when the panel or the results open draws a
focus ring after a key and nothing after a touch (Compose's `Focusability.SystemDefined`). Android's adaptive app
quality guidelines ask for this at their tier 2: Tab and arrow navigation through the main task flows, Escape closing
modals and leaving full screen, and a visible focused state on custom interactive drawables ([tier
2](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2)).

### Connecting and disconnecting

A keyboard connecting or disconnecting changes the labels and nothing else (D4). The song never restarts for it
([Keyboards coming and going](#keyboards-coming-and-going)). Keys held as a keyboard goes are released by the canceled
ups Android sends for them, through the normal press and release path, and the game goes on, by touch or with another
keyboard. There is no pause, no line on the pause panel and no pause cause for it.

### Settings › Keyboard

Settings gains a **Keyboard** section after Playfield with one row, "Keyboard", whose supporting text reads "Lanes: Z X
. / · Pause: Esc · Connected: K380" or "… · No keyboard connected". With a lane empty it reads "Lane 3 has no key" with
a warning symbol, in the error colour. It opens its own screen, the one place the map is set (D5, O5), reached from
Settings, Back to Settings, a row in [design.md's navigation table](design.md#information-architecture-and-navigation).
The screen is chrome: Material 3, a top app bar with Up, one column centred at 640 dp at most.

1. **Status.** The connected keyboards by name, or "No keyboard connected" with "Pair a Bluetooth keyboard in Android's
   settings, then come back." and a **Bluetooth settings** button (`Settings.ACTION_BLUETOOTH_SETTINGS`). With a
   keyboard connected, a line says "Labels follow the layout chosen for this keyboard in Android's settings." with a
   **Keyboard layout** link (`Settings.ACTION_HARD_KEYBOARD_SETTINGS`). Both intents may have no activity on a given
   phone, which their reference pages warn of; the button is then left out.
2. **The key map.** Five buttons: Lane 1 to 4 in a row from left to right, each with the lane's number and its key's
   label in large tabular type, and Pause beside them, or under them on a narrow window. **Use default keys** (Z X . /
   and Escape) sits under them. Pressing a button (by touch, Enter or Space) waits for a key: the button reads "Press a
   key", a line under the row says "Press the key for lane 2.", and two text buttons appear, **Clear** and **Cancel**.
   - The next key from the table binds and the wait ends. Back, Cancel or touching anything else ends the wait with
     nothing changed. Escape binds like any other key, so it does not cancel (O9).
   - **Clear** empties the slot.
   - A key already in another slot swaps the two (D3), and the line says so: "X now plays lane 1, and Z lane 2.";
     "Escape now plays lane 4, and / pauses."
   - A bind that swapped nothing moves the wait to the next lane, lane 1 to 4, as osu!lazer's ruleset key rows move on
     after a bind; four presses set four lanes. The wait never moves on to Pause: it ends after lane 4, and after a bind
     of Pause. A swap ends the wait, so the player sees what moved.
   - A key that cannot be bound leaves the wait open and says why, with a warning symbol: "Enter, Tab, Backspace and
     keys such as Shift, volume or Home can't be used here."
   - Without a keyboard the buttons show the map with US QWERTY labels and cannot wait for a key.
3. **A lane without a key.** The map is complete when all four lanes have a key; Pause may be empty (D5). An empty slot
   reads "None". An empty lane is marked: its button takes Material's error colour, outline and label, and a notice
   under the row, with a warning symbol, says which: "Lane 3 has no key. Press Lane 3, then the key you want.", or
   "Lanes 2 and 3 have no key." An empty Pause is not marked: the Pause button and Back still pause. No slot is ever
   labelled required or optional; the mark shows only while a lane is in fact empty. osu!lazer has no such mark
   ([Sources](#sources)); it is ours, in the spirit of osu!lazer's conflict preview, which shows in red the action a
   choice would leave without a key. A lane empties only by Clear, or by a swap with an empty slot: with Pause empty,
   giving Pause lane 4's key leaves lane 4 with nothing ("/ now pauses, and lane 4 has no key."). Leaving the screen is
   never blocked; the map is kept as it is, and a game plays an empty lane by touch only (O10).
4. **Key test.** A panel of four lane tiles in the game's palette, a **Test keys** button and, while testing, "Hold the
   four lane keys together. Each lane lights while its key is held. Back stops." Each tile lights while its key is held,
   from any keyboard; Escape also stops the test unless it plays a lane. Under the tiles: "Also held: Shift, A" for
   other keys, and "Most lane keys held together: 3 of 4" once the player has held more than one. When that number is
   under four after the player has pressed all four keys at some point, it is said in words with a warning symbol: "This
   keyboard reported at most 3 lane keys at once. Chords need all four: try other keys, or another keyboard." A tile
   that lights without its key held means the keyboard reported a key that was not pressed; the help line says so. A
   last line covers fast repeats: "If quick presses of one key go missing, check Android's physical keyboard
   accessibility settings."

While a key is awaited or the test runs, every key goes to that mode, so Space or Enter cannot press a button under it.
Otherwise the screen takes keys as any screen does.

| State | What it shows | Actions |
| --- | --- | --- |
| No keyboard | Status as above; the map with QWERTY labels; test off | Bluetooth settings |
| Connected | The keyboards by name; the map with their labels | Change a slot's key, Use default keys, Test keys, Keyboard layout |
| Waiting for a key | That slot's button reads "Press a key"; the prompt line, a polite live region | Any table key, Clear, Cancel, Back |
| Key refused | Warning symbol and the reason; still waiting | Clear, Cancel, Back |
| A lane empty | The lane's button in the error colour, reading "None"; the notice with a warning symbol | That lane's button, Use default keys |
| Testing | Tiles lit by held keys, the other held keys, the most lane keys held together | Stop test, or Back |
| A limit found | Warning symbol and the sentence above | Test keys again |

### Calibration with keys

The calibration is one screen ([latency.md, Timing calibration](latency.md#timing-calibration-one-screen)). It takes a
lane key down as a press, timed from the key event as a touch is from its own, and judged with the keyboard's input
latency slot; key repeats are ignored, and a release is a release, as in play. Every press counts for the note's lane,
so any of the four lane keys does, and the player presses with the finger they play with. The Swift app calibrates with
one key (J). The keyboard's slot is edited there with Keyboard chosen, or in Settings › Sound and timing.

### Visual language and motion

The labels belong to the game presentation ([design.md, Visual language](design.md#visual-language)): muted text
`#C7BCD6` on the cap's `#2A2036` (8.5:1), bold, 20 dp in landscape's 44 dp caps and 24 dp in portrait's 68 dp caps, a
fixed size like the judgement word. A held lane lights its cap additively in lavender, which makes the cap nearly white
at full light. The label brightens with the light, from `#C7BCD6` towards light text `#F6F1FB`, as the Swift caps do,
and switches to the ground's ink `#130E1A` once the cap's light passes 45 %. Bold at 20 dp or more, the labels are WCAG
large text (14 pt bold), for which 3:1 is the minimum; with that switch every step of the light keeps 4.0:1 or more
(computed from the palette, light added to the cap as the renderer does). The label has no motion of its own: it
follows `laneBrightness`, so Remove animations changes nothing for it. There are no new haptics: keys have their own
feel, and play has none ([design.md, Haptics](design.md#haptics)).

The Keyboard screen is stock Material 3 chrome in the brand scheme, apart from the test's tiles, which use the lane and
light colours so the test looks like the tray it predicts. The empty lane's mark uses the scheme's error role and is
never colour alone: the button reads "None" and the notice has words and a warning symbol ([design.md, Visual
language](design.md#visual-language), Failures).

### Accessibility

- **Labels.** Each slot button is "Lane 2 key, X" or "Pause key, Escape", with the state "Waiting for a key" while it
  waits; an empty lane is "Lane 3 key, none" with the state "Lane 3 has no key". The notice is a polite live region
  when it appears. Each test tile is one element, "Lane 2, held" or "Lane 2, not held"; the most-held line is a polite
  live region that speaks only when its number changes.
- **Font size.** At large font sizes the five buttons wrap, four lanes in two rows of two and Pause under them; nothing
  is truncated.
- **TalkBack in play.** With touch exploration on, a tap explores instead of touching ([design.md,
  Accessibility](design.md#accessibility)). Keys are not touches, so a keyboard could let a TalkBack user play, but
  TalkBack is an accessibility service that may filter key events before the app sees them
  (`AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS`), and whether it passes plain letter keys through is not
  verified. Until a phone check shows it does, the TalkBack notice before play stays as it is; if it does, its text
  gains "A connected keyboard still plays the lanes." and the playfield's description names the keys ("Playfield: four
  lanes, played with Z, X, period and slash, or by touch.").
- **Keyboard navigation** is itself an accessibility feature, for players who cannot use the glass, which is why every
  screen gets the K4 pass, not only the play screen.

### Phone sizes and orientation

Keyboards matter most on a tablet flat on a table and on a phone in landscape on a stand; nothing here depends on
either. Play keeps its orientation rules ([design.md, Phone sizes and
orientation](design.md#phone-sizes-and-orientation)): on windows at least 600 dp wide Android 16 ignores the orientation
request and the playfield takes the window's shape, labels included. On a wide window the Keyboard screen puts the test
beside the map, as Settings puts the preview beside its sliders.

### What comes from the Swift app

| Taken from the Swift app | Changed for Android, and why |
| --- | --- |
| Four distinct keys, D F J K by default (also osu!lazer's 4K default), rebinding by pressing the key, Restore Defaults | One map of five slots with Pause; Z X . / and Escape by default (the owner's choice); bound by position on the keyboard through `InputDevice.getKeyCodeForKeyLocation`; kept with the other settings in DataStore; a key in use swaps; Clear; an empty lane marked in red with a notice; a screen of its own with a key test |
| Key labels over the caps, brightening while pressed | Drawn by the render thread; shown only while a keyboard is connected; ink switches on a lit cap |
| Repeats ignored, the key's own timestamp, a press ignored while its lane is held, the 100 ms allowance | Millisecond stamps (Android has no public nanosecond key time); keys and touches share one table of holders |
| Keyboard latency left in the audio offset; calibration by key | An input latency slot shared by every keyboard, apart from touch's ([latency.md](latency.md)); the one calibration screen takes lane keys |
| Pause from the button only; Escape cancels key capture | The Pause key pauses and resumes; Back keeps Android's meaning; Escape binds like any key, and Back cancels a capture |

## How keys reach the game

### Where key events arrive

Keys are taken in **`MainActivity.dispatchKeyEvent`**, which Android calls "to intercept all key events before they are
dispatched to the window". It hands each event to a small activity-scoped router, `HardwareKeys`, provided to Compose
through a `CompositionLocal`. Screens register a handler while they need keys (the play screen, Results, the Keyboard
screen while waiting or testing, the calibration screen); the newest handler sees the event first and returns whether
it took it. An event no handler takes goes to `super.dispatchKeyEvent`, so Compose and the system see it as today.

The two other places were weighed and not chosen:

- **The playfield view's `onKeyDown` and `onKeyUp`** would need the `SurfaceView` inside an `AndroidView` to hold focus
  for the whole game. It takes no focus today, Compose's focus interop would decide when it has it, and Tab or an arrow
  would move focus to the HUD's Pause button and away from the lanes.
- **Compose's `onPreviewKeyEvent` on the play screen** delivers only along the path to the focused node. In Compose
  1.12.1 `FocusOwnerImpl.dispatchKeyEvent` also drops a key event, unhandled, while focus is being invalidated, which
  can happen while focusable panels appear and disappear.

The activity sees keys after any accessibility service, and after the input method when the window has one, and before
the views handle them; only a view's pre-IME hook comes earlier ([What stands before the
app](#what-stands-before-the-app)). No path changes timing, since every event carries its own time; the choice is about
not losing events and not depending on focus.

Escape outside the game goes the other way: an Escape that no view handled becomes Back through the
`OnBackPressedDispatcher`, unless the Library is on top (O2). In the game, an Escape the map does not hold is taken and
does nothing. `Activity.onKeyDown` is called for a key "not handled by any of the views inside of the activity"; it
starts tracking the Escape, and `onKeyUp` acts on a tracked, not canceled one, as Android tracks Back. A text field that
uses Escape keeps it, and so does Compose, which on an Escape down moves focus out of the focus group that holds it
(`FocusDirection.Exit` in Compose 1.12.1) when it can: the first Escape leaves the group, a later one goes Back. Android
itself does not turn a plain Escape into Back in a full-screen activity: `Activity.onKeyDown` finishes the activity on
Escape only when it would close on a touch outside, dialogs close on Escape since Android 9, an unhandled Escape closes
system dialogs, and Meta + Escape is the system's Back gesture (AOSP `Activity.java`, `Dialog.java`,
`PhoneWindowManager.java`). The key character map has no fallback from Escape to Back (`Generic.kcm`, Android 11 to 16).

### Which events play a lane

The play screen's handler passes key events to the playfield, `PlayfieldView.keyInput(event)`, on the main thread, where
touches already arrive. The playfield turns them into a plain value (action, key code, repeat count, flags, device id,
event time in milliseconds) for two pieces of pure Kotlin that the JVM tests drive without Android classes: `KeyLanes`,
which turns lane keys into presses and releases, and `GameKeys`, which decides what every other key does in each state
of the game (playing, count-in, paused, Results), as the table above sets out.

| Event | What happens |
| --- | --- |
| `ACTION_DOWN`, `getRepeatCount()` 0, a lane key, from a keyboard | A press for that lane, unless the lane is already held |
| `ACTION_DOWN`, repeat count 0, the Pause key, from a keyboard | Pause, resume or back to the panel, by the state of the game; the key is spent until its up |
| `ACTION_DOWN` with a repeat count above 0 | Taken and ignored. Android synthesises repeats in its dispatcher (`ViewConfiguration.getKeyRepeatTimeout` and `getKeyRepeatDelay`) and marks a second down of the same key from the same device as a repeat; `InputReader` drops the kernel's own repeats |
| `ACTION_UP` of a held lane key, with or without `FLAG_CANCELED` | A release. A canceled up still releases, the only way to keep press and release paired, as for touch |
| `ACTION_UP` of the spent Pause key | Taken; nothing happens |
| `ACTION_UP` of a key that holds nothing | Taken if it is in the map, otherwise passed on |
| `FLAG_FALLBACK`, `FLAG_SOFT_KEYBOARD`, `FLAG_VIRTUAL_HARD_KEY` | Never a lane or a pause: a synthesised default action, an input method's key, or a capacitive button |
| `ACTION_MULTIPLE` | Never a lane; deprecated in API 29, "no longer used by the input system" |
| A key the map does not hold | Passed on, except Escape, Space, Tab, arrows and Enter in the game (the table above) |

"From a keyboard" means a device whose `InputDevice.getKeyboardType()` is `KEYBOARD_TYPE_ALPHABETIC`, or the virtual
keyboard (`KeyCharacterMap.VIRTUAL_KEYBOARD`, id −1) that `adb shell input` injects from, so the emulator can drive it.
Gamepads that also report a keyboard source are left out by that rule and by the key table, which holds no gamepad
buttons. Modifier keys held at the same time do not matter: Z with Shift held is still Z. A game reads its map when it
starts; Settings cannot be opened during one ([design.md, Information
architecture](design.md#information-architecture-and-navigation)).

### Key identity and the map

**By position** (D1). A slot names a place on the keyboard, written as the key code a US QWERTY keyboard produces there
(Z is `KEYCODE_Z`, the slash position `KEYCODE_SLASH`). `InputDevice.getKeyCodeForKeyLocation(location)` (API 33, the
app's minimum) returns the key code the key at that place produces in the layout Android uses for that keyboard: Q
gives A on French AZERTY, Y gives Z on German QWERTZ, B gives B on US QWERTY (its reference page's examples). By the
same swaps the Z position gives W on AZERTY and Y on QWERTZ. The default lanes, Z X . /, are the bottom row's two ends,
one pair for each hand; on Dvorak the same places are labelled ; Q V Z, and the hands stay where they were. The call is
a binder call into the system (`InputManagerGlobal`), so each keyboard's five produced key codes are resolved on the
main thread when it connects, when `onInputDeviceChanged` reports it changed, and on every `ON_START`, never per event.
It returns `KEYCODE_UNKNOWN` when the mapping cannot be determined; that keyboard then uses the location's own key code.
Labels come from `KeyCharacterMap.getDisplayLabel(keyCode)` of that keyboard's map, or from the table below when it has
none.

Binding by layout key code (what Android delivers, and close to the Swift app's characters) or by scan code
(`KeyEvent.getScanCode()`, whose reference says the values "are not reliable and vary from device to device", and which
`adb shell input` sends as 0) were the alternatives the owner did not choose.

**The key table** is fixed, in pure Kotlin, and the same for all five slots (O9): the letters, the digits of the main
block, the main block's punctuation (`` ` - = [ ] \ ; ' , . / ``), Space, the four arrows and Escape. Each entry has a
stable name (the Android symbolic name without `KEYCODE_`, such as `Z`, `PERIOD`, `DPAD_LEFT`, `ESCAPE`), its key code
and a label for when no keyboard is there. Everything else is refused: Back, Enter, Tab and Backspace (navigation),
modifiers (Shift, Ctrl, Alt, Meta, Caps Lock, Fn, Num Lock), volume and media keys (the system's), function keys (often
media keys behind Fn on tablet keyboards), the numeric keypad (whose keys become other keys through the key character
map when Num Lock is off) and the editing block (Home, End, Page Up and Down, Insert, Delete). The table is also the
list the capture searches: to bind a pressed key, it asks the device which table entry produces that key code.

**Storage.** `StoredSettings` gains `keys`, a name or null for each slot:
`{"lanes": ["Z", "X", "PERIOD", "SLASH"], "pause": "ESCAPE"}` by default, in the same `settings.json` through the same
DataStore store in `AppStores`. An older file has no `keys` and reads the default map, as for every field added since
step 1. A map with a name not in the table, one name in two slots, or not four lanes reads the default map; an empty
slot stays empty (O10). `PlaySettings` carries the validated map to the screens and the game.

### Lanes from several sources

`LaneTouchTracker` becomes `LaneHolds`: which holders hold which lane. A holder is a pointer (its id) or a key (its
device and key code); the type leaves room for another kind of input, and none is planned. A lane is held while any
holder holds it (D2); the press goes to the runtime with the first holder and the release with the last, as the tracker
does for fingers now. It keeps the tracker's rules: a holder belongs to the lane it pressed until it lets go, and a
second press of a held lane sends nothing. It adds releasing by kind of holder: all pointers on `ACTION_CANCEL` (keys
stay held), all keys of one device when it goes. At a pause it keeps its holders (O7): a lane whose holders all let go
during the pause is released at the resume, at the pause's song time. It stays on the main thread, where both touches
and keys arrive.

`LaneInput` gains a `source` (touch, keyboard, the autoplay's script). It chooses the input latency the controller
applies ([latency.md](latency.md#where-the-input-latency-is-applied)) and goes into the logs. Each source has its own
small class that turns its events into `press(holder, lane, eventNanos)` and `release(holder, eventNanos)` on one object
the playfield owns, which keeps `LaneHolds` and the sequence counter and enqueues for the render thread. Touch keeps its
lane geometry ([gameplay.md, Input](gameplay.md#input)); keys bring `KeyLanes`. Another kind of input would add its own
class and change neither of these.

**The cost of "any holder".** A contact left on the glass in a lane swallows that lane's key presses, and a key held in
a lane swallows taps there, because a second press of a held lane sends nothing. The issue asks for this rule, osu!mania
does the same (its `Unique` binding mode: only the first binding of an action presses it, the last released releases
it), and the runtime cannot tell sources apart anyway: its `InputEvent`, as the app builds it, is a lane, a phase and a
chart time. The owner kept the rule (D2); the touch log shows whether stray contacts during keyboard games make it worth
revisiting.

**To the render thread.** A key's time is `KeyEvent.getEventTime()`, milliseconds in the `SystemClock.uptimeMillis()`
base, which is the base of `System.nanoTime()`; multiplied by 1 000 000 it joins the queue next to touches, and the
frame, the allowance and the late-input count apply as latency.md sets them out ([gameplay.md, The
frame](gameplay.md#the-frame)). The nanosecond accessor exists in AOSP but is `@hide` on `KeyEvent`, while
`MotionEvent.getEventTimeNanos()` is public from API 34; key times are therefore truncated to the millisecond, at most
1 ms early.

### Keyboards coming and going

**No restart.** `MainActivity` declares `orientation|screenSize|screenLayout|smallestScreenSize` and nothing for
keyboards. Android sets `Configuration.keyboard` to `KEYBOARD_QWERTY` when any non-virtual alphabetic keyboard is
present and `Configuration.navigation` to `NAVIGATION_DPAD` when a device reports a D-pad source (AOSP
`DisplayContent.computeScreenConfiguration`); a keyboard counts as a D-pad only if it has Up, Down, Left, Right and
Center (inputflinger's `DPAD_REQUIRED_KEYCODES`), which depends on the keyboard. An undeclared change restarts the
activity. Read from the code, not run: `PlayfieldView.onDetachedFromWindow` closes the game's controller and sound, and
the recreated play screen builds a new game, so a keyboard connecting to a phone without one restarts the song from its
lead-in. The manifest therefore adds `keyboard|keyboardHidden|navigation` (D4 keeps it), with no screen tied to the
change. The app has no resources qualified by keyboard or navigation, Compose recomposes what reads
`LocalConfiguration` (only `DarkSystemBars`, which reapplies the bar colours), and the playfield reads nothing a
keyboard changes. `DiagnosticsActivity` keeps the default: it collects again if recreated. The configuration-changes
guide says Android 17 no longer restarts activities by default for keyboard, keyboard-hidden, navigation and touchscreen
changes; it does not say whether that depends on the target SDK, the behaviour-change pages do not list it, and the app
targets 36 with testers on Android 16.

**Who is connected.** `ConnectedKeyboards`, one per process in `AppStores` beside the output route and reached through
an `AppGraph` interface (so the screenshot scenarios can fake a keyboard), registers an
`InputManager.InputDeviceListener` on the main looper and keeps a `StateFlow` of keyboards: id, name, vendor and product
id, whether external (`isExternal()`, API 29, true for USB and Bluetooth devices unless their configuration says
otherwise), and the resolved positions. It needs no permission; pairing is Android's. Timing does not read it: an input
method counts as live in a game only once it has pressed there, whatever is connected
([latency.md](latency.md#where-the-input-latency-is-applied)).

**A disconnect** is the player's to deal with (D4): the game goes on. Android releases the device's held keys itself:
removing a device resets its keyboard mapper, which sends an `ACTION_UP` with `FLAG_CANCELED` for every key still down
(AOSP `InputReader::removeDeviceLocked`, `KeyboardInputMapper::cancelAllDownKeys`), and those ups release the lanes
through the normal path. `onInputDeviceRemoved` also drops whatever holders that device still has, normally none, since
the order of the two is not guaranteed. Focus leaving the window also brings canceled ups for held keys ("focus left
window" in the dispatcher), and the game pauses on focus loss already.

**Several keyboards** play together, each through its own resolved positions, since layouts are chosen per keyboard. The
same key held on two keyboards is two holders and one press. Labels follow the first keyboard connected. All of them
share the keyboard's input latency slot (D7).

**A keyboard that sleeps** and reconnects on the next key appears to Android as a removal and a new device: the labels
go and come back, and nothing else happens. Whether the waking key is lost depends on the keyboard.

### What stands before the app

- **Input method** (D6, a requirement). Lane keys must play under any input method without starting word composition.
  `ViewRootImpl` hands a key to the input method before the views (pre-IME, IME, post-IME, view) whenever the window has
  IME focus, whatever has focus inside it. So the game's screens never let their window be an input method target: the
  play screen, Results and the calibration screen, and the Keyboard screen while it waits for a key or tests, add
  `WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM` to the activity's window when they open and clear it when they
  close. On a focusable window the flag "prevents this window from becoming the target of the input method" (its javadoc
  at `android-16.0.0_r1`): `LayoutParams.mayUseInputMethod` is then false, `ImeFocusController` keeps no IME focus for
  the window, and its IME stage returns "not handled" without calling `InputMethodManager`, so the key goes on to the
  views and the input method never sees it. No `InputConnection` is made either: the window is no target, and none of
  these screens has a text field. Compose's root view reports a text editor and makes an `InputConnection` only while a
  text field's input session is active (`AndroidComposeView.onCheckIsTextEditor` and `onCreateInputConnection`, Compose
  UI 1.12.1). Dialogs over these screens, such as latency.md's slot names, are windows of their own and keep their input
  method. Were an input method to see keys anyway, their event times would not change; only the handling delay would
  grow. Whether HyperOS's usual input methods, Chinese ones with physical keyboard support among them, leave keys alone
  is a device check (checklist 20). If one still takes them, the fallback is a pre-IME hook on a focused view during
  play (`View.onKeyPreIme`), with the focus cost described above.
- **Accessibility services** with `FLAG_REQUEST_FILTER_KEY_EVENTS` see keys before the app (API 18). TalkBack is one.
- **System shortcuts.** Android takes some combinations before the app, among them Meta + Escape as Back. Meta and the
  other modifiers are not bindable, so a lane key on its own is never a system shortcut. HyperOS's own shortcuts and
  Game Turbo are not verified (checklist 20).
- **Keyboard accessibility settings.** Android 16's source has bounce keys, which ignore "rapid, repeated keypresses of
  the same key", and slow keys, which accept a press only after it has been held (AOSP `InputSettings`). Bounce keys
  would swallow fast repeated notes in one lane. Their state is a hidden API, so the app cannot read it; the Keyboard
  screen's last line points at the setting.

## Timing

**What the stamp holds.** A key's event time is the kernel's input event timestamp, taken on `CLOCK_MONOTONIC` when the
event reaches the kernel's input layer (AOSP `EventHub::processEventTimestamp`). A Bluetooth keyboard's reports reach
the kernel from Android's Bluetooth stack through `/dev/uhid` (AOSP `bta_hh_co.cc`). Everything before that write is not
in the stamp: the keyboard's own scanning, the radio link and the Bluetooth stack. What comes after it (dispatch, the
main thread, the queue) delays the handling, not the stamp. That second part is measured per key by the key log's
handling delay and judged away by the 100 ms allowance; the first part cannot be measured from inside the phone. No
figure for it is given here: none was measured for these keyboards, and it varies with the keyboard and the link.

**Where that delay goes** (D7). Into the keyboard's input latency slot, one for every keyboard and apart from touch's,
with the player's own habit ([latency.md](latency.md)). The controller subtracts it from key chart times; the audio
latency slot, chosen for the output, lines the picture up with the sound for keys and touch alike. A player who
changes between touch and keys on one output therefore needs no new calibration, and a keyboard's delay never moves
touch. The slot is set on the calibration screen with lane keys, or in Settings › Sound and timing.

**How to measure it.** Checklist 18: on one output and one audio slot, the calibration screen three times with touch
presses for touch's slot, then three times with lane keys for the keyboard's. The difference of the two values is the
keyboard's delay against touch, its Bluetooth link included; latency.md shows both values also hold the display's
delay, which the difference takes out. Checklist 19 films a key press and a finger tap at 240 fps or more with the lane
light in frame; the frames from contact to light compare the whole paths, display included.

## Edge cases

- **A key held as the game starts** (pressed before the playfield existed): its down was never seen, so its up releases
  nothing and is ignored.
- **Keys held at a pause** (O7, decided (c); built in the pause step, [Build plan](#build-plan)). Holders survive the
  pause, for touch and keys alike: the app tells the session nothing until the resume, when play goes on after the
  count-in, and then releases the lanes whose holders all let go meanwhile, at the pause's song time, since the clock
  was frozen; a finger or key still held at the resume holds its lane, and its hold, through the count-in and on, and
  nothing new is sent for it. Presses during the pause and the count-in are dropped. Ups during the pause, canceled or
  not (focus leaving the window, the app leaving, a keyboard going), are kept for the resume. Keys make a held lane at
  a pause common, since a player often holds a lane key while reaching for the Pause key; keys go through the same
  `LaneSender` as touch, so they do whatever touch does. `LaneHolds.pause` keeps the holders and notes each lane let
  go with the source of its last holder; `LaneSender.resume` sends those releases marked `LaneInput.atPause`, queued
  before the clock runs again; `InputQueue` stamps them with the chart time its paused frames held at. Before the
  step, pausing forgot the holders without telling the session, inputs were dropped while the clock was paused, and a
  paused frame cleared the queue: a release queued but not yet delivered left its lane pressed in the session for good,
  and a dropped press left the opposite. Now a paused frame delivers what was queued before the pause, at the pause's
  time at the latest, so at every resume the session holds the app's lanes ([gameplay.md, Input](gameplay.md#input)).
  Read from the runtime at `d090374` (`crates/gameplay/src/session.rs`, `feedback.rs`): `input` counts an input late
  only when it is stamped strictly before the session's clock, which `advance` and each input move forward and never
  back, and handles it at that clock; frames advance only to their time less 100 ms, and paused frames not at all, so a
  release at the pause's chart time is never late. A release for a lane that holds no hold changes nothing but the
  session's pressed flag: no event, and no count unless it is late. A second press of a held lane is an ordinary press,
  which tries the lane's next waiting note and leaves a held hold held; `LaneHolds` sends none. With the behaviour
  before the step, the session kept a forgotten lane pressed and a hold in it held, the feedback kept the lane lit at
  its hold brightness, a later press and release of the lane ended the hold by that release, and with no release the
  hold missed when its tail window closed, 1.5 × 200 ms after its end, or at the next note of the lane if that came
  first.
- **The Pause key held down**: its repeats and its up are taken, so it pauses once and never resumes by itself.
- **A lane key pressed as the pause panel or Results appear**: taken, so it presses nothing (O4).
- **Keys pressed during the count-in** are dropped, as touches are, and such a key held on plays only when pressed
  again; a key held since before the pause keeps its lane (above).
- **Rollover and ghosting.** A keyboard may not report every key held at once, or may report one that is not held. When
  a USB or Bluetooth keyboard reports a rollover error, Linux ignores that whole report, so the keys already held stay
  held and the new one is not seen (`hid-core.c`, "Ignore report if ErrorRollOver"). The app cannot tell a key not
  pressed from a key not reported; the key test shows what arrives, and the most lane keys held together in each game is
  logged.
- **Bounce keys** drop fast repeats of one key; the app cannot detect it (What stands before the app).
- **The layout changes** while the app is in the background: positions are resolved again on `ON_START`; the game was
  paused on leaving, so no held key changes meaning mid-press.
- **A keyboard removed mid-song**: Android's canceled ups release its keys and the game goes on (D4).
- **A lane with no key** (O10): that lane plays by touch only, and its cap has no label.
- **Split screen or another window** taking focus: keys go to the focused window, held keys get canceled ups, and the
  game pauses as now.
- **A gamepad or remote** whose keys reach the activity: not an alphabetic keyboard, so it plays nothing and its keys
  pass on.
- **The window too small to play in:** the game is paused, so lane keys do nothing.

## Diagnostics

Device names are free text, and diagnostics.md admits only technical facts into its records, so names never go into
logs, the journal or the export ([diagnostics.md, Traces, privacy and
limits](diagnostics.md#traces-privacy-and-limits)). Keyboards are identified by vendor and product id in hexadecimal,
and within one game by an index (0, 1, …; −1 for injected keys). That keeps the phone checks' archives free of personal
data, as [phone-checks.md](phone-checks.md#what-to-send-back) promises. Key names in the map (`Z`, `ESCAPE`) are fixed
technical names, not free text.

- **Key log (debug and checks builds).** `files/key-log.csv`, beside `touch-log.csv`, written when the playfield closes:
  `action,eventNanos,handledNanos,keyCode,scanCode,repeat,flags,keyboard,lane,sent` for every key event the playfield
  saw, with `lane` −1 for keys outside the lanes (−2 for the Pause key) and `sent` 1 when a press or release went to the
  queue. Its summary under `EnsomiPlayfield` gives the handling delay at the median, the 99th percentile and the most,
  as the touch log does. K6 adds it to what `scripts/phone-checks.sh -g manual` collects.
- **The game's log** (`EnsomiPlay`, at game over): presses by source, late inputs by source (an input is late when its
  chart time falls before the session's last advance; the runtime's own `lateInputs` stays the total), the most lane
  keys held together, the keyboards seen by vendor and product, and the map by key names. The latency lines are
  latency.md's.
- **Journal.** `input_device` with phases `added` and `removed`, not durable, context `kind=keyboard`, `vendor`,
  `product`, `external`, `in_game`. One record per connection, never per key ([diagnostics.md, Journal API and
  durability](diagnostics.md#journal-api-and-durability)).
- **Summary JSON.** An `input.keyboards` list (vendor id, product id, external, keyboard type) read from `InputManager`
  in the Diagnostics activity without app stores, an additive field as schema 2's own additions were ([diagnostics.md,
  Summary JSON](diagnostics.md#summary-json)). No names and no input device descriptors, which are stable identifiers.
  O8.

## Checks

### JVM tests

All of it is pure Kotlin on plain values, so no test builds an Android `KeyEvent`:

- `LaneHoldsTest` (from `PlayfieldGeometryTest`'s tracker cases): a finger and a key on one lane give one press and one
  release; two keyboards on one key; a second press of a held lane sends nothing; `ACTION_CANCEL` releases pointers and
  leaves keys; a device's release leaves the others; an unknown holder's release is ignored; holders at a pause as O7
  (c) has it, with the pause step: kept at the pause; let go during it and released at the resume, marked for the
  pause's time, from the source of the last holder; still held at the resume; pressed during the pause and dropped; a
  cancel or a keyboard going during the pause; cleared for Restart, Quit and leaving; and for any sequence of these, a
  session fed what is sent holds the app's lanes, and through a pause those held as it began.
- `InputQueueTest` (the pause step): a paused frame hands on what was queued before the pause; a release at the resume
  takes the chart time the paused frames held at, also when a paused frame takes it or none ran; nothing taken after a
  pause is stamped before it; such a release comes before a press at the same time; and holders, the queue and a model
  of the runtime's session together hold the same lanes after every frame, with no late input.
- `KeyLanesTest`: repeats ignored; a canceled up releases; fallback, soft-keyboard and virtual-hard-key events never
  play; non-alphabetic devices pass through; a fake AZERTY layout maps the Z position to `KEYCODE_W`; the virtual
  keyboard maps every position to itself; keys held at a pause keep their lanes, and those let go, a canceled up
  included, are released at the resume.
- `GameKeysTest`: every row of the keys table in each state; the Pause key acts on a fresh down only, its repeats and up
  taken, so a held key never toggles twice; lane keys taken on the panel, in the count-in and on Results; Escape and
  Space as O2 and O3 decide, in and out of the map; navigation keys taken in the game.
- `KeyMapTest` (in place of the earlier `LaneKeysTest`): Z X . / and Escape by default; a bind to a key in another
  slot swaps, with Pause too; a swap with an empty slot empties the other; Clear; the next lane waits after a bind that
  swapped nothing, lane 1 to 4, and the wait ends after lane 4 and after Pause, never moving on to Pause; a swap ends
  the wait; Escape binds to any slot; Enter, Tab, Backspace, modifiers, volume, function, keypad and editing keys
  are refused; the completeness rule (four lanes) and the empty lanes it names; `settings.json` without `keys`, with an
  unknown name, a name in two slots or three lanes reads the default; an empty slot reads back empty; a round trip keeps
  the order.
- `KeyTestTest`: the most lane keys held together, the other keys held, and the limit sentence only after all four keys
  have been pressed.
- latency.md's `CalibrationReadingsTest` gains key presses, with repeats ignored.

### On the emulator

[gameplay.md](gameplay.md#what-the-emulator-can-and-cannot-show) says what the AVD shows in general. For keys:

| Tool | What it exercises | What it cannot |
| --- | --- | --- |
| `adb shell input keyevent KEYCODE_Z` | A down followed at once by an up, from the virtual keyboard (id −1, scan code 0): taps, the map, the Pause key, Tab, Enter | Holds, chords, a real device |
| `input keyevent --duration 800 KEYCODE_Z` | A held key: the down, one repeat with `FLAG_LONG_PRESS` at the long-press timeout, the up at 800 ms: repeat filtering, hold notes, a held Pause key that pauses once | Chords |
| `input keycombination -t 300 KEYCODE_Z KEYCODE_X …` | Several downs at once: chords of presses | Holds: in AOSP its ups carry the downs' event time, so each release is stamped at its press |
| `adb emu event send EV_KEY:KEY_Z:1`, later `EV_KEY:KEY_Z:0` | The AVD's own keyboard, with Linux key codes: holds and chords at any timing; several events per command | Connecting and disconnecting; timing set by the host (the stamps are the guest's) |
| `adb shell hid -` (AOSP's uhid tool, in its base system image; `adb shell` runs with the `uhid` group for it) | A HID keyboard registered with `"bus": "bluetooth"`: registering connects it, reports press keys (Z X . / are HID usages 0x1D, 0x1B, 0x37, 0x38), a report holding the rollover error (0x01) is ignored by the kernel, end of input disconnects it. The listener, labels, canceled ups on removal with no pause, the rollover sentence | The radio, a real keyboard's matrix, sleep and reconnect, HyperOS |
| `adb shell dumpsys window windows` during play, the calibration and the Keyboard screen's capture | `ALT_FOCUSABLE_IM` in the app window's flags then, and not on other screens | Whether a real input method composes (checklist 20) |
| The emulator window with "Enable keyboard input" (`hw.keyboard`) | The reviewer plays with the Mac's keyboard | Astra's headless runs |

**The restart check needs an AVD that reports no keyboard of its own.** `adb shell am get-config` prints the
configuration's qualifiers, `nokeys` or `qwerty` among them. An AVD with `hw.keyboard` on has a keyboard of its own, and
then registering another changes nothing and proves nothing. So step K2's emulator run, on `ensomi-36-16k`, the only AVD,
first records whether `ensomi-36-16k` reports `nokeys` (`adb shell am get-config`); if it reports `qwerty`, the check
cannot prove the restart is gone, and the owner decides whether its `hw.keyboard` may change. The run also records
whether `hid` runs from `adb shell` on that image.

### On a phone or tablet

New rows for [the runbook's checklist](phone-checks.md#the-checklist), on the Redmi K Pad with the owner's Bluetooth
keyboard first, then the Redmi K80. HyperOS's three-finger gesture does not matter here unless touch is also used.

| # | Check | What to do | What to record |
| --- | --- | --- | --- |
| 14 | Keyboard and labels | Pair the keyboard; open Settings › Keyboard; look at a game's tray | The name shown; whether the labels match the keycaps (Z X . / by default); the layout Android chose |
| 15 | Rollover | Settings › Keyboard › Test keys: hold Z X . / together; then pairs and threes; then your own keys | "Most lane keys held together"; any lane that stayed dark, or lit without its key |
| 16 | Connect in a game | Keyboard off; start a game; turn it on mid-song | Whether the song went on without restarting, and whether the labels appeared |
| 17 | A full game with keys | Play one whole song with the keyboard, then run the `manual` collection | The results screenshot; anything late or missed (the archive holds the key log and late inputs by source) |
| 18 | Keys against touch | On one output and audio slot: Settings › Calibrate three times by touch for touch's slot, then three times with lane keys for the keyboard's | Each touch value and each keyboard value |
| 19 | Key to light | Film a lane key press with the lane light in frame at 240 fps or more; then a finger tap on the glass the same way | Both videos |
| 20 | What stands before the app | Play with the phone's usual input method, and with a Chinese input method that supports physical keyboards (the phone's own, Sogou or Baidu) where one is installed; try Meta shortcuts and Game Turbo if present; with TalkBack on, press lane keys in the preview | Each input method's name; any lost key, composing text or candidate bar; any key the system took; whether lane keys played with TalkBack on |
| 21 | Keys around the game | Without touching: Library → song → Play → Escape → Escape → finish → Play again → Escape back to the song; Tab through Settings and the Keyboard screen; then give Pause another key and press Escape in a game and on its pause panel | Anything unreachable, or focused without a visible focus state; what Escape did with Pause elsewhere |

A second keyboard, where one is at hand, goes in the notes of 17.

## Build plan

Steps in the manner of [plan.md](plan.md); a step is done when `assembleDebug`, `testDebugUnitTest` and `lint` pass, its
emulator check passes and the screenshots of the screens it touches were looked at. K3 to K6 can go in any order after
K2. No step adds a library, a pin or a permission. latency.md's steps L1 to L5 run beside these; L1 shares K1's
`LaneInput.source`. O7 (c) changes pausing, for touch play too, so it is a small step of its own between K1 and K2, the
pause step (done): `LaneHolds` keeps its holders at a pause and notes the lanes let go during it, `LaneSender` releases
those at the resume, marked for the pause's time, and `InputQueue` stamps them with the chart time the paused frames
held at; a paused frame hands on what was queued before the pause instead of dropping it, so that the session and the
app agree on the lanes held. It is checked by `LaneHoldsTest`'s pause cases, `InputQueueTest` and `KeyLanesTest`, and
on `ensomi-36-16k` by touches held across a pause.

| Step | Builds | Emulator check | Phone check | Needs |
| --- | --- | --- | --- | --- |
| K1 (done) | `LaneHolds` in place of `LaneTouchTracker`, touch unchanged; `LaneInput.source`; the key map (table, defaults, swap, Clear, completeness, storage) in `StoredSettings`; `KeyLanes` and `GameKeys` as pure logic | The JVM tests above; a game from `adb shell input tap` and the capture script as before; `settings.json` keeps every field and gains `keys` | — | D1 to D3, D5; O9, O10 |
| K2 | Keyboard play: the manifest's `keyboard\|keyboardHidden\|navigation`; `HardwareKeys` in `MainActivity`; `ConnectedKeyboards` in `AppStores` with a fake for the scenarios; lane keys, the Pause key with its fresh-down rule and the taken keys in play; `FLAG_ALT_FOCUSABLE_IM` on the game's screens; labels on the caps; the key log and the game log's additions | `input keyevent` taps judged; `--duration` gives one press and one release, and a held Pause key pauses once; `emu event send` chords and holds judged; a `hid` keyboard registered mid-game: `am get-config` before and after, `MainActivity` created once (a debug log line), the song goes on; `hid` closed: canceled ups in the key log, no lane left lit, no pause; the window flag in `dumpsys window`; captures with labels in both orientations | 14, 16, 17, 20 | K1; latency.md L1; O6, O7 |
| K3 | Settings › Keyboard: status, links, the five slots, capture with Clear, Cancel and moving on, swap, refusal, the empty lane's mark and notice, defaults, the key test and its limit sentence; the Settings row; scenarios with a fake keyboard | Capture by `input keyevent`, Escape included; kept after a force-stop; Clear on a lane shows the mark and the notice, and the Settings row says so; the test lit by `emu event send` holds (4 of 4) and by a `hid` keyboard that never reports a fourth key (the sentence); UI Automator dumps; TalkBack walk; 200 % font; landscape | 14, 15 | K2; O5, O9, O10 |
| K4 | Keys around the game: the Pause key in the count-in and on the pause panel, lane keys taken on the panel and Results, Escape in and out of the game, first focus on Resume and Play again; a focus pass over every screen; optionally the game's keys in Android's keyboard shortcuts list (`Activity.onProvideKeyboardShortcuts`, API 24) | `input keyevent` sequences of Tab, arrows, Enter, Space and Escape from the Library to a game, its pause, its results and back, without a tap; a lane key (Space bound to a lane) sent as the panel and Results open presses nothing; screenshots of every focus state; UI Automator's `focused` | 21 | K2; O1 to O4 |
| K5 | Calibration with keys: lane keys as presses on the calibration screen, judged with the keyboard's slot; the method in the calibration log | Key presses from `input keyevent` at logged event times give the runtime's errors when recomputed with the keyboard's slot, as latency.md's check does for taps | 18, 19 | K2; latency.md L3 |
| K6 | Diagnostics and runbook: the journal's `input_device`, the summary's `input.keyboards`, the `manual` collection of `key-log.csv`, checklist rows 14 to 21 in phone-checks.md | Redaction tests; the Diagnostics JSON lists a `hid` keyboard by vendor and product; no device name anywhere in the export or the archive | The checklist run | K2; O8 |

What K1 showed (builds b1 and b2 of `claude/android-keyboard`, `991ac24` and `691b628`, on an `ensomi-36` made afresh,
since the old one was gone): the full build passed with 431 JVM tests, 51 of them in `LaneHoldsTest`, `KeyLanesTest`,
`KeyMapTest` and `GameKeysTest`, and lint found nothing the commit before K1 did not. A `settings.json` in 0.1.3's shape
read unchanged after the update, and the first change of a setting wrote every field back with `keys` added. The sample
game played by `adb shell input tap` reached Results with its taps judged, as on the build before K1, and its log said
`presses touch 113, keyboard 0, autoplay 168`, as many touch presses as the touch log's downs. The capture script's
screens looked as before. The AVD had no songs or charts, so no real song was played by taps.

What the pause step showed (Astra job `pause-b1` of `claude/android-keyboard` at `b5a2269`, and its follow-up, on
`ensomi-36-16k` with 16 KB pages): the full build passed with 448 JVM tests, and lint found the same 6 warnings as before
the step. The AVD's Library was empty, so the sample game was played (`--es prototype play`), whose autoplay plays as
well. Touches were `adb shell input motionevent DOWN` and `UP`; Back paused; Resume was tapped, or, with a finger still
down, reached by Tab and Enter. `adb emu event send` delivered no touch on that AVD: its console refuses
`EV_SYN:SYN_REPORT:0`, and with a numeric code it answers OK and nothing arrives. A finger on lane 4 lifted during the
pause: the session held the lane at the pause; at the resume the playfield released it at the pause's time, the
session held nothing and the lane was dark. A finger held through the resume: the session held lane 4 at the pause and
at the resume, and the lane stayed lit until the finger lifted. On the sample chart's hold in lane 1, taken by the
finger before the autoplay could: held through the resume, the session held the hold on both sides of the pause and it
went on to its tail; let go during the pause, it was judged a miss as play went on, greyed, and the lane was dark. None
of these added a late input, and no touch was cancelled. A game without a pause reached Results as before, with one
late input: a tap's up handled 22 ms after it happened, after an autoplay press stamped 18 ms later had reached the
session (the autoplay delivers as soon as its time comes and does not wait out the allowance). No real song's game, on
the audio clock, was paused: the AVD had none; `InputQueueTest` covers the audio clock's hold at the resume, in two cases
added after the emulator run (job `pause-b2` at `d73cc1e`: 450 JVM tests passed, lint unchanged).

### K2 as built

Built as the K2 row says, from [How keys reach the game](#how-keys-reach-the-game) and the keys table. What it adds,
and where it goes further than the row or differs:

- **The router.** `keyboard/HardwareKeys`, made with `MainActivity` and provided as `LocalHardwareKeys`, holds a
  `KeyRouter` of handlers, the newest first; `HardwareKeyHandler` registers one while a screen is composed. The play
  screen's handler runs until its game is over, then Results' takes over. The TalkBack notice before play is a dialog,
  a window of its own, so its keys never reach the activity, as the table has it.
- **Escape as Back** (O2) is built now, since Results' row needs it: `MainActivity.onKeyDown` tracks an Escape no view
  handled and `onKeyUp` goes Back on its up, on every screen but the Library (`HardwareKeys.escapeGoesBack`, set from
  the back stack). An up whose down went elsewhere, such as a Pause key held as the game ended, does nothing. K4's focus
  pass and first focus on Resume and Play again are not built.
- **Who is connected.** `keyboard/ConnectedKeyboards` keeps a `KeyboardRoster`, pure Kotlin: each non-virtual
  alphabetic device with its `ResolvedKeys` and its cap labels, asked of the system when it connects, when it changes,
  at every `ON_START` (`EnsomiApp`'s `LifecycleStartEffect`) and when Settings' map changes, never per key. Two
  additions: a key from a device the listener has not yet reported resolves that device once, since the key can come
  first, and a device that is no keyboard is remembered as such until it changes. The scenarios on fakes have
  `FakeKeyboards`: none, or a sample keyboard for the label scenarios, so they take injected keys only.
- **Keys in play.** The handler hands each event to `PlayfieldView.keyInput`, which makes a `KeyInput` and asks
  `GameKeys` in the play screen's phase; lane keys go through `KeyLanes` to the playfield's one `LaneSender` as
  `LaneHolder.Key`, source `KEYBOARD`, so they take the keyboard's input latency slot through L1's formulas and the input
  clock. Until the playfield's game exists a lane key's down presses nothing. Two additions to `KeyLanes`: a lane key
  whose down it saw belongs to that lane until its up, even when its keyboard no longer resolves by then (the roster
  can drop a keyboard before Android's canceled ups for it arrive); and a keyboard leaving the roster lets go of its
  keys at once through `LaneSender.releaseKeys`, so whichever comes first releases. The Pause key's `RESUME` does nothing
  behind the window-too-small notice, whose panel cannot be seen.
- **The window and the input method.** `KeepInputMethodAway` on the play screen, Results and the calibration screen
  sets `FLAG_ALT_FOCUSABLE_IM`, counted by `InputMethodGuard`, so a screen replacing another keeps the flag on. The
  calibration takes no keys yet (K5).
- **Labels.** `PlayfieldRenderer` draws the first keyboard's lane labels on the caps, in screen space, bold at 20 dp in
  landscape and 24 dp in portrait, narrowed only if a word is wider than its cap. `CapLabel.color` reads "brightens from
  `#C7BCD6` towards `#F6F1FB`" as reaching light text when the light reaches 45 %, then the ink: computed as the renderer
  adds the light, its worst step is 4.06:1, at 44.8 %. Brightening with the light itself, reaching only 45 % of the way
  at the switch, would fall to 3.1:1 there. Words: `key_space` and `key_escape`.
- **Logs.** `key-log.csv` in debug and checks builds, every event the playfield was handed, devices numbered in the
  order the game saw them (−1 for injected keys); its summary under `EnsomiPlayfield` adds the most lane keys held
  together. At game over `EnsomiPlay` gives, after L1's "Game over" line with presses and late inputs by source, a
  "Keys:" line: the most lane keys held together (lanes with a key down, so the same lane on two keyboards is one), the
  keyboards seen by index, vendor and product id, whether injected keys came, and the map by key names. `EnsomiKeys`
  (debug and checks builds) logs each keyboard connected or removed by vendor and product id, each `MainActivity`
  created and each configuration change taken in place, for the restart check. No log carries a device name.
- **Scenarios** `play-keyboard`, `play-portrait-keyboard` and `play-paused-keyboard`, and the capture script's
  `keyboard` group.

When the steps are done, design.md takes the screens and the keys table, gameplay.md the input path and edge cases,
plan.md the steps and what they showed, phone-checks.md the rows, and design.md's "Not carried over in this round"
drops key bindings.

## Sources

Android reference (developer.android.com): [`KeyEvent`](https://developer.android.com/reference/android/view/KeyEvent)
(`getEventTime`, `getRepeatCount`, `getScanCode`, `getDeviceId`: API 1; `FLAG_CANCELED`, `FLAG_LONG_PRESS`,
`FLAG_VIRTUAL_HARD_KEY`, `isCanceled`: 5; `FLAG_SOFT_KEYBOARD`: 3; `FLAG_FALLBACK`: 11; `keyCodeToString`,
`keyCodeFromString`: 12; `ACTION_MULTIPLE` deprecated in 29),
[`MotionEvent.getEventTimeNanos`](https://developer.android.com/reference/android/view/MotionEvent#getEventTimeNanos())
(34), [`InputDevice`](https://developer.android.com/reference/android/view/InputDevice) (`getKeyCodeForKeyLocation`: 33;
`getKeyboardType`, `KEYBOARD_TYPE_ALPHABETIC`, `getName`, `getKeyCharacterMap`, `getDevice`, `SOURCE_KEYBOARD`: 9;
`isVirtual`, `getDescriptor`: 16; `getVendorId`, `getProductId`: 19; `isExternal`: 29),
[`KeyCharacterMap`](https://developer.android.com/reference/android/view/KeyCharacterMap) (`getDisplayLabel`, `load`: 1;
`VIRTUAL_KEYBOARD` = −1: 11),
[`InputManager`](https://developer.android.com/reference/android/hardware/input/InputManager) and
[`InputDeviceListener`](https://developer.android.com/reference/android/hardware/input/InputManager.InputDeviceListener)
(16), [`Activity`](https://developer.android.com/reference/android/app/Activity) (`dispatchKeyEvent`, `onKeyDown`: 1;
`dispatchGenericMotionEvent`: 12; `onProvideKeyboardShortcuts`: 24),
[`WindowManager.LayoutParams`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_ALT_FOCUSABLE_IM)
(`FLAG_ALT_FOCUSABLE_IM`),
[`ViewConfiguration`](https://developer.android.com/reference/android/view/ViewConfiguration) (key repeat timeout and
delay: 12), [`SystemClock`](https://developer.android.com/reference/android/os/SystemClock),
[`Settings`](https://developer.android.com/reference/android/provider/Settings) (`ACTION_BLUETOOTH_SETTINGS`: 1;
`ACTION_HARD_KEYBOARD_SETTINGS`: 24),
[`AccessibilityServiceInfo`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_REQUEST_FILTER_KEY_EVENTS)
(18).

Android guides: [`<activity>` `configChanges`](https://developer.android.com/guide/topics/manifest/activity-element),
[configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes) (Android 17's default),
[Compose focus](https://developer.android.com/develop/ui/compose/touch-input/focus), [Compose keyboard
actions](https://developer.android.com/develop/ui/compose/touch-input/keyboard-input/commands), [adaptive app quality,
tier 2](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2), [emulator
console](https://developer.android.com/studio/run/emulator-console) (`event send`),
[AVDs](https://developer.android.com/studio/run/managing-avds) (`hw.keyboard`).

AOSP at `android-16.0.0_r1` (android.googlesource.com): frameworks/base `KeyEvent.java` (on `main`: the `@hide`
nanosecond time), `InputDevice.java`, `InputManagerGlobal.java`, `Activity.java`, `Dialog.java`, `ViewRootImpl.java`
(`ImeInputStage`), `ImeFocusController.java` (`onProcessImeInputStage`, IME focus from `mayUseInputMethod`),
`WindowManager.java` (`FLAG_ALT_FOCUSABLE_IM`'s javadoc, `LayoutParams.mayUseInputMethod`), `InputMethodManager.java`,
`Configuration.java`, `InputSettings.java`, `services/.../wm/DisplayContent.java`,
`services/.../policy/PhoneWindowManager.java`, `services/.../input/InputShellCommand.java`,
`services/.../am/ActivityManagerShellCommand.java`, `data/keyboards/Generic.kcm` (also at the Android 11, 13, 14 and 15
tags), `cmds/hid/README.md` (`main`); frameworks/native inputflinger `EventHub.cpp`, `KeyCodeClassifications.h`,
`InputReader.cpp`, `mapper/KeyboardInputMapper.cpp`, `dispatcher/InputDispatcher.cpp`, `dispatcher/InputState.cpp`;
build `target/product/base_system.mk`; system/core `rootdir/ueventd.rc`; packages/modules/adb `daemon/main.cpp`;
packages/modules/Bluetooth `system/btif/co/bta_hh_co.cc` (`main`).

Compose 1.12.1 sources from Google's Maven: UI's `FocusOwnerImpl.kt`, `AndroidComposeView.android.kt`
(`onCheckIsTextEditor`, `onCreateInputConnection`); Foundation's `Focusability.kt` and `Clickable.kt` (`isEnter`: the
D-pad centre, Enter, numeric Enter and Space; a click only on an up after the clickable's own down).

ensomi-runtime at `d090374` (`codex/android-no-kleidiai`, workspace version 0.3.2, `v0.3.2-19-gd090374`; its
`crates/gameplay` is the same at `main`, `edf4066`): `crates/gameplay/src/session.rs` (`Session::input`, `settle`,
`press`, `release`, `expiry`, `holding`), `feedback.rs` (`record_input`, `brightness`), `rules.rs` (`g` = 200 ms,
`TAIL_LENIENCE` = 1.5); `crates/ffi/src/lib.rs` (`GameplaySession` passes inputs through).

osu!lazer, `ppy/osu` at `77df204` (2026-11-06), MIT, read for behaviour only and no code copied:
`osu.Game/Overlays/Settings/Sections/Input/KeyBindingRow.cs` (a focused row waits for the next key, ignores repeats,
waits for the rest of a combination after a modifier; "Press a key combination to set a new binding...", with Cancel and
Clear binding, the latter a `DangerousRoundedButton`, while waiting; losing focus ends the wait; a key already used in
the section opens the conflict popover instead of binding), `KeyBindingRow.KeyButton.cs` (an action without a key reads
"(none)" at 40 % opacity; gameplay keys may not be the mouse wheel), `KeyBindingRow.ConflictResolution.cs` and
`KeyBindingConflictPopover.cs` ("The binding you've selected conflicts with another existing binding.", Keep existing or
Apply new; Apply new gives the new action the key and leaves the other with none; hovering a choice previews it, the
binding that gets or keeps its key in green and the one left without it, or with its old key, in red; Select, which is
Enter, numeric Enter or Space, applies new), `KeyBindingsSubsection.cs` and `VariantBindingsSubsection.cs` (Reset all
bindings in section; ruleset rows move to the next row after a bind without a conflict),
`osu.Game/Input/RealmKeyBindingStore.cs` and `osu.Game/Rulesets/UI/RulesetInputManager.cs` (at gameplay a key bound to
two actions of a variant is cleared from both), `osu.Game/Localisation/InputSettingsStrings.cs`,
`osu.Game.Rulesets.Mania/SingleStageVariantGenerator.cs` (4K from D F J K). A search of those directories,
`osu.Game/Screens/Play` and `osu.Game/Rulesets/UI` found no mark or warning for an action left without a key. Also
osu!lazer's `ManiaInputManager.cs` (`Unique` binding mode) and osu-framework's `KeyBindingContainer.cs`
(`ppy/osu-framework`), as in the first version.

Others: Linux `drivers/hid/hid-core.c` and `hid-input.c` (`torvalds/linux` master); the HID usage tables for the
keyboard page's usages.

## Not verified

- The delay from key contact to event time on any Bluetooth keyboard, and how it compares with touch (checklists 18,
  19).
- Whether `FLAG_ALT_FOCUSABLE_IM` keeps HyperOS's input methods, Chinese ones with physical keyboard support among them,
  away from lane keys, and whether its shortcuts or Game Turbo take lane keys or the Pause key (checklist 20).
- Whether TalkBack passes plain letter keys to the app (checklist 20).
- Whether choosing another keyboard layout in Android's settings raises `onInputDeviceChanged`; the design resolves
  positions again on `ON_START` regardless.
- The labels Android's own AZERTY and Dvorak layouts give the period and slash positions; the examples above follow the
  layouts' usual arrangement, not Android's layout files.
- Whether a sleeping keyboard loses the key that wakes it, which depends on the keyboard.
- Whether `hid` runs from `adb shell` on `ensomi-36-16k` (AOSP grants the group; SELinux on the image is untested),
  and whether that AVD reports a keyboard of its own.
- Whether Android's dispatcher adds its own repeats to keys injected by `adb shell input`.
- With a release for a lane it does not hold, the runtime's behaviour is read from its source at `d090374` (Edge
  cases), not run. Lanes and a hold held across a pause were run on the emulator, in the sample game only
  ([Build plan](#build-plan)).
- A real song paused on the audio clock with a lane held: the pause step's AVD had no song, so only `InputQueueTest`
  shows the release at the resume and the presses after it on `AudioPlayClock`.
- Whether `adb emu event send` can touch the screen on `ensomi-36-16k`, which multi-touch on the emulator needs; in the
  pause step it delivered nothing.
- Whether Material 3's focus state is clearly visible on the brand's plum in the game presentation, and whether its
  sliders step with the arrow keys (K4's captures).

## Decisions

### Decided (owner, 2026-11-07)

- **Scope.** Keyboards only. Gamepads and MIDI are out; the input path may leave room for them.
- **D1. Key identity.** By position on the keyboard, through `InputDevice.getKeyCodeForKeyLocation`: the same fingers on
  every layout, labels following the layout.
- **D2. Touch beside keys.** Touch stays live while keys play, and a lane is held while any holder, finger or key,
  holds it.
- **D3. A key already in the map.** Binding it to another slot swaps the two slots' keys, and the screen says so.
- **D4. Disconnects.** A keyboard lost mid-song is the player's to deal with: no pause, no pause panel line and no pause
  cause for it, and no disconnect check in the runbook. The manifest still declares
  `keyboard|keyboardHidden|navigation`, with nothing tied to it, so a keyboard coming or going never recreates the
  activity and restarts the song. Keys held as a keyboard goes still get Android's canceled ups, which the normal press
  and release path handles.
- **D5. One key map, set in one place.** Five slots, Lane 1 to 4 and Pause, bound by position, by default Z, X, period
  and slash (`KEYCODE_Z`, `KEYCODE_X`, `KEYCODE_PERIOD`, `KEYCODE_SLASH`) for the lanes and Escape for Pause. This
  replaces the Swift app's D F J K and the fixed Escape pause of the first version. A map is complete with all four
  lanes bound; Pause may be empty. No slot is labelled required or optional; a lane that is missing shows a red mark
  with a notice, with osu!lazer as the reference ([Settings › Keyboard](#settings--keyboard), item 3).
- **D6. Input methods.** Lane keys play under any input method without starting word composition. This is a
  requirement: the game's screens keep their window from being an input method target
  ([What stands before the app](#what-stands-before-the-app)), checklist 20 checks it, and the pre-IME hook stays the
  fallback.
- **D7. Timing.** [latency.md](latency.md)'s model, in place of the first version's decision 6: every keyboard shares
  one input latency slot, apart from touch's, and the audio latency slot is chosen by hand.

### Open questions, decided (owner, 2026-11-07)

The owner took every recommendation below, with two amendments. **Moving on after a bind** stops at the lanes: a bind
that swapped nothing moves the wait to the next lane, lane 1 to 4, never on to Pause, and the wait ends after lane 4
([Settings › Keyboard](#settings--keyboard), item 2). **Live methods** for the input clock: an input method counts as
live only once it has pressed in the game, touch included ([latency.md, Live
methods](latency.md#where-the-input-latency-is-applied)). Each item keeps its options for the record.

1. **O1. The Pause key on the pause panel and in the count-in.** (a) On the panel it resumes with the count-in; in the
   count-in it returns to the panel; each on a fresh down only, as the keys table requires anyway. (b) It does nothing
   there; Resume is by Enter or touch. **Decided: (a).** One key toggles pause, as in most PC rhythm games, and with
   Escape as the Pause key tier 2's "Esc closes modals" reads the same way. Back keeps leaving.
2. **O2. Escape when the map does not hold it.** (a) Outside the game, Escape is Back on every screen but the Library,
   Results included; in the game (playing, count-in, paused) it is taken and does nothing, so only the map's keys act
   there. (b) Escape is Back everywhere, the game included: it pauses in play, as Back does, and leaves the game from
   the panel. (c) Escape is never Back, Android's default for a full-screen activity; Meta + Escape remains the system's
   Back. **Decided: (a).** With the default map nothing changes, since Escape is the Pause key. With Pause on another
   key, an Escape pressed from habit cannot leave a game from its panel. Back itself, from the keyboard's Back key,
   Meta + Escape or the gesture, keeps its meaning everywhere.
3. **O3. Space.** (a) An ordinary key for any slot, a lane or Pause; when the map does not hold it, it does nothing in
   the game, and outside the game it activates the focused control as Compose does. (b) Space pauses when the map does
   not hold it, as tier 2 suggests for media playback. **Decided: (a).** The map decides what keys do in the game, and
   a pause on a mis-press costs the passage.
4. **O4. Lane keys and the buttons on the pause panel and Results.** (a) Lane keys never act there: the play screen and
   Results take every lane key's down, repeats and up while the panel, the count-in or Results show; Enter, Space when
   the map does not hold it, and the Pause key act. (b) A short guard: for 500 ms after the panel or Results appear,
   every key but Back is taken. (c) No first focus after a key: Resume and Play again take focus only after Tab or an
   arrow. **Decided: (a).** It closes the gap for every lane key at any moment rather than within a guessed 500 ms,
   keeps Enter's first focus, and costs nothing, since lane keys mean nothing else there.
5. **O5. Where the map lives** (the first version's decision 7). (a) The Keyboard screen of its own from one Settings
   row. (b) The five slots inline in Settings, with the test in a dialog. **Decided: (a),** as D5 already implied: one
   place for five slots, the capture, the empty lane's notice and the test.
6. **O6. Labels on the caps.** (a) While a keyboard is connected. (b) Always. (c) A setting. **Decided: (a).**
7. **O7. Lanes held at a pause, for touch and keys alike.** What the runtime does with today's behaviour is in
   [Edge cases](#edge-cases), read from its source at `d090374`. (a) As now: the app forgets its holders and the session
   is not told, so the lane stays lit through the pause and after it, the session keeps a held hold, a press and release
   of that lane after the resume ends the hold by that release, and without one the hold misses when its tail window
   closes. (b) At the pause the session gets a release for every held lane, at the pause's time, before the clock stops:
   nothing stays lit, and a hold held then misses at once unless the pause falls within 300 ms of its end, where it is
   judged at the pause. (c) Holders survive the pause: the app keeps them and tells the session nothing until the
   resume, when it releases the lanes whose holders let go meanwhile, at the pause's song time, since the clock was
   frozen; a key still held at the resume holds its lane, and its hold, through the count-in and on. Presses during the
   pause are dropped as now. **Decided: (c).** (b) was the simpler fallback. Both end the lit lane of (a) and its
   accidental rescue of a hold; (c) also keeps a hold for a player who pauses with the other hand and keeps holding,
   which keys make common, at the cost of a list of released lanes in `LaneHolds`. It changes touch play too, and is a
   small step of its own between K1 and K2, the pause step ([Build plan](#build-plan)).
8. **O8. Keyboards in Diagnostics.** (a) Vendor and product ids, whether external, and keyboard type in the journal and
   the summary, never names. (b) Nothing outside the debug logs. **Decided: (a)**: it tells a bad keyboard from a bad
   build without personal data.
9. **O9. Which keys a slot may take.** (a) One table for all five slots: the letters, the main block's digits and
   punctuation, Space, the four arrows and Escape. Escape binds like any key while a slot waits, so Back, Cancel or a
   touch elsewhere cancels the wait, not Escape as the Swift guidelines have it; a lane may take Escape, and Pause then
   takes that lane's key by the swap. (b) Escape for Pause only: a lane refuses Escape with the reason, and Escape
   cancels a lane's wait. **Decided: (a).** One map, one rule; and after the player moves Pause elsewhere, Escape can
   be bound back to it without Use default keys.
10. **O10. Empty lanes and leaving with one.** A lane empties by Clear (osu!lazer's "Clear binding") or by a swap with
    an empty slot. (a) As osu!lazer: leaving is never blocked and the map is kept as edited; a game plays an empty lane
    by touch only; the red mark and the notice stay on the Keyboard screen and the Settings row until the lane has a
    key. (b) The last complete map plays until the edited one is complete again; the screen keeps the incomplete one.
    (c) Leaving the screen is blocked until every lane has a key. **Decided: (a).** osu!lazer stores what the player
    set, blocks nothing and plays an action without a key as unpressable; with touch live, an empty lane still plays.
    (b) would make the screen and the game disagree, and (c) would hold the player on a screen that Back should leave.
