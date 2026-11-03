# Ensomi for Android: hardware keyboard play (proposed)

This is a proposed design for issue [#29](https://github.com/ensomi-labs/ensomi/issues/29), Bluetooth keyboard input. It
is not built. It was written against `dev/android` at `8a09ddf`. Once it is accepted and built, its screens fold into
[design.md](design.md), its input path and timing into [gameplay.md](gameplay.md), its steps into [plan.md](plan.md) and
its checklist rows into [phone-checks.md](phone-checks.md). Until then those documents describe the app as it is.

**In scope:** play with a hardware keyboard (in practice a Bluetooth keyboard on a phone or tablet), bindings and their
test, keyboards connecting and disconnecting, the keys around the game, timing, diagnostics and checks. **Not in
scope:** gamepads, MIDI and other controllers. The input path keeps them possible (Lanes from several sources).

The Swift app on `dev/swift` (at `623feaa`) plays from the keyboard and is the reference for behaviour and the timing
contract (`Mania4KKeyBindingSet` and `Mania4KKeyboardInputRouter` in `Mania4KDomain.swift`, `docs/gameplay-timing.md`).
This design uses Android's APIs and ports no Swift code. API levels and behaviours were checked against
developer.android.com, AOSP source at `android-16.0.0_r1` unless another revision is named, and the Compose 1.12.1
sources that the pinned BOM 2026.09.00 resolves to. [Sources](#sources) lists them; [Not verified](#not-verified) lists
what is still open.

## What good means

1. **A key counts when it went down.** The judged time is the key event's own timestamp, through the same clock, queue
   and 100 ms allowance as a touch ([gameplay.md, Input](gameplay.md#input)).
2. **Every press has its release.** One press and one release per lane reach the runtime, whether a lane is held by a
   finger, a key, or both, and nothing stays pressed after a pause, a cancel or a disconnect.
3. **A keyboard coming or going never restarts a game.** A disconnect during play pauses it and says why.
4. **The keys are the player's.** Four keys by their place on the keyboard, D F J K by default, changed in Settings,
   with a test that shows what the app sees held and says plainly when a keyboard reports fewer keys than are pressed.
5. **The app works from the keyboard.** Pause, resume, results and every screen around the game move with Tab, the arrow
   keys, Enter, Space and Escape, with focus always visible.
6. **Touch is unchanged.** A player without a keyboard sees and gets exactly what the app does today.

## What the player sees

### Play with a keyboard

Nothing changes on the play screen until a keyboard is connected. While one is (any non-virtual keyboard with letter
keys), each key cap in the tray carries the label of its lane's key, as the Swift play screen does over its caps. The
caps stay where they are and touch stays live, so a player can switch between fingers and keys at any time, or use both
(decision 2). Connecting a keyboard during a game shows the labels and nothing else: no notice, no pause.

The label is the character the key produces in the layout Android uses for that keyboard (Key identity and bindings):
"D" on a US or German layout, "E" for the same position on Dvorak. Letters, digits and punctuation show their character;
Space shows "Space" and the arrow keys show arrows. The labels are drawn by the render thread in screen space, like the
tray itself, so they never lean ([gameplay.md, The lean](gameplay.md#the-lean)).

Listen & Play's games, the playfield preview opened from Settings › About and Play again all take keys as they take
touches. The playfield preview inside the Settings list and the calibration's picture step take neither.

### Keys around the game

| Where | Key | What it does |
| --- | --- | --- |
| Playing | The four lane keys | Press and release their lanes |
| | Escape | Pauses, as the Pause button does |
| | Back (a keyboard's own Back key, or Meta + Escape, which Android turns into Back) | Pauses, as Back does now |
| | Tab, arrows, Enter, Space when not lane keys | Nothing: taken by the game so focus never moves onto the HUD under the player |
| | Volume, media keys and anything the system handles | Passed on unchanged |
| Count-in | Lane keys | Nothing, as touches during the count-in are dropped now |
| | Escape | Back to the pause panel |
| Paused | Escape | Resumes with the count-in (decision 3) |
| | Enter or Space | Presses the focused button; Resume has focus when the panel opens after a key |
| | Tab and arrows | Move between Resume, Restart and Quit |
| | Back | Leaves the game, as now |
| Results | Enter or Space | Play again, which has focus first when the game ended after a key |
| | Escape | Back to song (decision 5) |
| Other screens | Tab, arrows, Enter, Space | Compose's focus navigation and activation |
| | Escape | Back, on every screen but the Library (decision 5) |
| TalkBack notice before play | Escape | Back, as the dialog handles it itself |

Space never pauses (decision 4): it is a common key to bind, and a pause on a mis-pressed key loses the passage.

Focus follows Android's guidance for Compose
([focus](https://developer.android.com/develop/ui/compose/touch-input/focus)): a logical first focus, Tab and arrow-key
order, focus restored after a panel closes, and a visible focus state. Stock Material 3 buttons take focus only when the
system is not in touch mode, so asking for focus on Resume or Play again when the panel or the results open draws a
focus ring after a key and nothing after a touch (Compose's `Focusability.SystemDefined`). Android's adaptive app
quality guidelines ask for this at their tier 2: Tab and arrow navigation through the main task flows, Escape closing
modals and leaving full screen, and a visible focused state on custom interactive drawables ([tier
2](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2)).

### Pause and disconnects

A keyboard disconnecting during play pauses the game, and the pause panel says so under "Resume when you're ready", as
it does for a change of output: **"Keyboard disconnected: K380. Reconnect it, or play by touch."** The name is the one
Android reports for the device; it is shown on screen only and never logged (Diagnostics). The game pauses even when
another keyboard is still connected, since the player has lost keys. A disconnect while paused, in the count-in or
outside play changes nothing but the labels.

### Settings › Keyboard

Settings gains a **Keyboard** section after Playfield with one row, "Keyboard", whose supporting text reads "Lanes: D F
J K · Connected: K380" or "Lanes: D F J K · No keyboard connected". It opens its own screen (decision 7), reached from
Settings, Back to Settings, a row in [design.md's navigation table](design.md#information-architecture-and-navigation).
The screen is chrome: Material 3, a top app bar with Up, one column centred at 640 dp at most.

1. **Status.** The connected keyboards by name, or "No keyboard connected" with "Pair a Bluetooth keyboard in Android's
   settings, then come back." and a **Bluetooth settings** button (`Settings.ACTION_BLUETOOTH_SETTINGS`). With a
   keyboard connected, a line says "Labels follow the layout chosen for this keyboard in Android's settings." with a
   **Keyboard layout** link (`Settings.ACTION_HARD_KEYBOARD_SETTINGS`). Both intents may have no activity on a given
   phone, which their reference pages warn of; the button is then left out.
2. **Lane keys.** Four buttons in a row, lane 1 to 4 from left to right, each with the lane's number and its key's label
   in large tabular type, and **Use default keys** (D F J K) under them. Pressing a button (by touch, Enter or Space)
   waits for a key: the button reads "Press a key" and a line under the row says "Press the key for lane 2. Escape
   cancels." The next allowed key binds and the wait ends; Escape, Back, or touching anything else cancels it, as the
   Swift design guidelines require of key capture. A key already bound to another lane swaps the two (decision 11): "J
   now plays lane 2, and F lane 3." A key that cannot play a lane leaves the wait open and says why, with a warning
   symbol: "Escape, Enter, Tab and keys such as Shift, volume or Home can't play a lane." Without a keyboard the buttons
   show the bindings with their US QWERTY labels and cannot wait for a key.
3. **Key test.** A panel of four lane tiles in the game's palette, a **Test keys** button and, while testing, "Hold the
   four lane keys together. Each lane lights while its key is held. Escape stops." Each tile lights while its key is
   held, from any keyboard. Under the tiles: "Also held: Shift, A" for other keys, and "Most lane keys held together: 3
   of 4" once the player has held more than one. When that number is under four after the player has pressed all four
   keys at some point, it is said in words with a warning symbol: "This keyboard reported at most 3 lane keys at once.
   Chords need all four: try other keys, or another keyboard." A tile that lights without its key held means the
   keyboard reported a key that was not pressed; the help line says so. A last line covers fast repeats: "If quick
   presses of one key go missing, check Android's physical keyboard accessibility settings."

While a key is awaited or the test runs, every key goes to that mode, so Space or Enter cannot press a button under it.
Otherwise the screen takes keys as any screen does.

| State | What it shows | Actions |
| --- | --- | --- |
| No keyboard | Status as above; bindings with QWERTY labels; test off | Bluetooth settings |
| Connected | The keyboards by name; bindings with their labels | Change a lane's key, Use default keys, Test keys, Keyboard layout |
| Waiting for a key | That lane's button reads "Press a key"; the prompt line, a polite live region | Escape or Cancel |
| Key refused | Warning symbol and the reason; still waiting | Escape or Cancel |
| Testing | Tiles lit by held keys, the other held keys, the most lane keys held together | Stop test, or Escape |
| A limit found | Warning symbol and the sentence above | Test keys again |

### Calibration with keys

The sound step of [Timing calibration](design.md#timing-calibration) takes a lane key as a tap, timed from the key event
as the pad's taps are from theirs; key repeats and key releases are ignored. The pad's text becomes "Tap on the click,
or press a lane key". Nothing else on the screen changes: the strip, the median of 16 and Use work as now. The Swift app
calibrates with one key (J); here any of the four lane keys counts, so the player taps with the finger they play with.
What the suggestion means for keyboard play is decision 6 ([Timing](#timing)).

### Visual language and motion

The labels belong to the game presentation ([design.md, Visual language](design.md#visual-language)): muted text
`#C7BCD6` on the cap's `#2A2036` (8.5:1), bold, 20 dp in landscape's 44 dp caps and 24 dp in portrait's 68 dp caps, a
fixed size like the judgement word. A held lane lights its cap additively in lavender, which makes the cap nearly white
at full light. The label brightens with the light, from `#C7BCD6` towards light text `#F6F1FB`, as the Swift caps do,
and switches to the ground's ink `#130E1A` once the cap's light passes 45 %. Bold at 20 dp or more, the labels are WCAG large text (14 pt bold), for which 3:1 is the minimum; with that switch
every step of the light keeps 4.0:1 or more (computed from the palette, light added to the cap as the renderer does).
The label has no motion of its own: it follows `laneBrightness`, so Remove animations changes nothing for it. There are
no new haptics: keys have their own feel, and play has none ([design.md, Haptics](design.md#haptics)).

The Keyboard screen is stock Material 3 chrome in the brand scheme, apart from the test's tiles, which use the lane and
light colours so the test looks like the tray it predicts.

### Accessibility

- **Labels.** Each lane key button is "Lane 2 key, F", with the state "Waiting for a key" while it waits. Each test tile
  is one element, "Lane 2, held" or "Lane 2, not held"; the most-held line is a polite live region that speaks only when
  its number changes.
- **Font size.** At large font sizes the four lane key buttons wrap to two rows of two; nothing is truncated.
- **TalkBack in play.** With touch exploration on, a tap explores instead of touching ([design.md,
  Accessibility](design.md#accessibility)). Keys are not touches, so a keyboard could let a TalkBack user play, but
  TalkBack is an accessibility service that may filter key events before the app sees them
  (`AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS`), and whether it passes plain letter keys through is not
  verified. Until a phone check shows it does, the TalkBack notice before play stays as it is; if it does, its text
  gains "A connected keyboard still plays the lanes." and the playfield's description names the keys ("Playfield: four
  lanes, played with D, F, J and K, or by touch.").
- **Keyboard navigation** is itself an accessibility feature, for players who cannot use the glass, which is why every
  screen gets the K4 pass, not only the play screen.

### Phone sizes and orientation

Keyboards matter most on a tablet flat on a table and on a phone in landscape on a stand; nothing here depends on
either. Play keeps its orientation rules ([design.md, Phone sizes and
orientation](design.md#phone-sizes-and-orientation)): on windows at least 600 dp wide Android 16 ignores the orientation
request and the playfield takes the window's shape, labels included. On a wide window the Keyboard screen puts the test
beside the lane keys, as Settings puts the preview beside its sliders.

### What comes from the Swift app

| Taken from the Swift app | Changed for Android, and why |
| --- | --- |
| Four distinct keys, D F J K by default (also osu!lazer's 4K default), rebinding by pressing the key, Escape cancelling, Restore Defaults | Bound by position on the keyboard, not by character, through `InputDevice.getKeyCodeForKeyLocation`; kept with the other settings in DataStore; a key in use swaps lanes instead of being refused; a screen of its own with a key test |
| Key labels over the caps, brightening while pressed | Drawn by the render thread; shown only while a keyboard is connected; ink switches on a lit cap |
| Repeats ignored, the key's own timestamp, a press ignored while its lane is held, the 100 ms allowance | Millisecond stamps (Android has no public nanosecond key time); keys and touches share one table of holders |
| Keyboard latency left in the audio offset; calibration by key | The sound step takes any lane key or the pad; a phone measurement decides whether keys need a delay of their own |
| Pause from the button only | Escape pauses and resumes; Back keeps Android's meaning |

## How keys reach the game

### Where key events arrive

Keys are taken in **`MainActivity.dispatchKeyEvent`**, which Android calls "to intercept all key events before they are
dispatched to the window". It hands each event to a small activity-scoped router, `HardwareKeys`, provided to Compose
through a `CompositionLocal`. Screens register a handler while they need keys (the play screen, the Keyboard screen
while waiting or testing, the calibration's sound step); the newest handler sees the event first and returns whether it
took it. An event no handler takes goes to `super.dispatchKeyEvent`, so Compose and the system see it as today.

The two other places were weighed and not chosen:

- **The playfield view's `onKeyDown` and `onKeyUp`** would need the `SurfaceView` inside an `AndroidView` to hold focus
  for the whole game. It takes no focus today, Compose's focus interop would decide when it has it, and Tab or an arrow
  would move focus to the HUD's Pause button and away from the lanes.
- **Compose's `onPreviewKeyEvent` on the play screen** delivers only along the path to the focused node. In Compose
  1.12.1 `FocusOwnerImpl.dispatchKeyEvent` also drops a key event, unhandled, while focus is being invalidated, which
  can happen while focusable panels appear and disappear.

The activity sees keys after any accessibility service and input method and before the views handle them (only a view's
pre-IME hook comes earlier) ([What stands before the app](#what-stands-before-the-app)). No path changes timing, since
every event carries its own time; the choice is about not losing events and not depending on focus.

Escape outside play goes the other way: an Escape that no view handled becomes Back through the
`OnBackPressedDispatcher`, unless the Library is on top (decision 5). `Activity.onKeyDown` is called for a key "not
handled by any of the views inside of the activity"; it starts tracking the Escape, and `onKeyUp` acts on a tracked, not
canceled one, as Android tracks Back. A text field that uses Escape keeps it, and so does Compose, which on an Escape
down moves focus out of the focus group that holds it (`FocusDirection.Exit` in Compose 1.12.1) when it can: the first
Escape leaves the group, a later one goes Back. Android itself does not turn a plain Escape into Back in a full-screen
activity: `Activity.onKeyDown` finishes the activity on Escape only when it would close on a touch outside, dialogs
close on Escape since Android 9, an unhandled Escape closes system dialogs, and Meta + Escape is the system's Back
gesture (AOSP `Activity.java`, `Dialog.java`, `PhoneWindowManager.java`). The key character map has no fallback from
Escape to Back (`Generic.kcm`, Android 11 to 16).

### Which events play a lane

The play screen's handler passes key events to the playfield, `PlayfieldView.keyInput(event)`, on the main thread, where
touches already arrive. The playfield turns them into a plain value (action, key code, repeat count, flags, device id,
event time in milliseconds) for `KeyLanes`, pure Kotlin that the JVM tests drive without Android classes:

| Event | What happens |
| --- | --- |
| `ACTION_DOWN`, `getRepeatCount()` 0, a bound key, from a keyboard | A press for that lane, unless the lane is already held |
| `ACTION_DOWN` with a repeat count above 0 | Taken and ignored. Android synthesises repeats in its dispatcher (`ViewConfiguration.getKeyRepeatTimeout` and `getKeyRepeatDelay`) and marks a second down of the same key from the same device as a repeat; `InputReader` drops the kernel's own repeats |
| `ACTION_UP` of a held key, with or without `FLAG_CANCELED` | A release. A canceled up still releases, the only way to keep press and release paired, as for touch |
| `ACTION_UP` of a key that holds nothing | Taken if bound, otherwise passed on |
| `FLAG_FALLBACK`, `FLAG_SOFT_KEYBOARD`, `FLAG_VIRTUAL_HARD_KEY` | Never a lane: a synthesised default action, an input method's key, or a capacitive button |
| `ACTION_MULTIPLE` | Never a lane; deprecated in API 29, "no longer used by the input system" |
| A key that is not bound | Passed on, except Tab, arrows, Enter and Space during play (the table above) |

"From a keyboard" means a device whose `InputDevice.getKeyboardType()` is `KEYBOARD_TYPE_ALPHABETIC`, or the virtual
keyboard (`KeyCharacterMap.VIRTUAL_KEYBOARD`, id −1) that `adb shell input` injects from, so the emulator can drive it.
Gamepads that also report a keyboard source are left out by that rule and by the bindable keys, which hold no gamepad
buttons. Modifier keys held at the same time do not matter: D with Shift held is still D. A game reads its bindings when
it starts; Settings cannot be opened during one ([design.md, Information
architecture](design.md#information-architecture-and-navigation)).

### Key identity and bindings

**By position.** A binding names a place on the keyboard, written as the key code a US QWERTY keyboard produces there (D
is `KEYCODE_D`). `InputDevice.getKeyCodeForKeyLocation(location)` (API 33, the app's minimum) returns the key code the
key at that place produces in the layout Android uses for that keyboard: Q gives A on French AZERTY, Y gives Z on German
QWERTZ, B gives B on US QWERTY (its reference page's examples). D F J K are the home row's index and middle fingers, so
a Dvorak player gets the same hand position, labelled E U H T. The call is a binder call into the system
(`InputManagerGlobal`), so each keyboard's four produced key codes are resolved on the main thread when it connects,
when `onInputDeviceChanged` reports it changed, and on every `ON_START`, never per event. It returns `KEYCODE_UNKNOWN`
when the mapping cannot be determined; that keyboard then uses the location's own key code. Labels come from
`KeyCharacterMap.getDisplayLabel(keyCode)` of that keyboard's map, or from the table below when it has none.

Binding by layout key code (what Android delivers, and close to the Swift app's characters) or by scan code
(`KeyEvent.getScanCode()`, whose reference says the values "are not reliable and vary from device to device", and which
`adb shell input` sends as 0) are the alternatives in decision 1.

**Bindable keys** are a fixed table in pure Kotlin: the letters, the digits of the main block, the main block's
punctuation (`` ` - = [ ] \ ; ' , . / ``), Space and the four arrows. Each entry has a stable name (the Android symbolic
name without `KEYCODE_`, such as `D`, `SEMICOLON`, `DPAD_LEFT`), its key code and a label for when no keyboard is there.
Everything else is refused: Escape (pause), Back, Enter, Tab and Backspace (navigation), modifiers (Shift, Ctrl, Alt,
Meta, Caps Lock, Fn, Num Lock), volume and media keys (the system's), function keys (often media keys behind Fn on
tablet keyboards), and the numeric keypad (whose keys become other keys through the key character map when Num Lock is
off). The table is also the list the capture searches: to bind a pressed key, it asks the device which table entry
produces that key code.

**Storage.** `StoredSettings` gains `laneKeys`, a list of four names, `["D","F","J","K"]` by default, in the same
`settings.json` through the same DataStore store in `AppStores`. An older file has no such key and reads the default, as
for every field added since step 1. A list that is not four distinct names from the table also reads the default.
`PlaySettings` carries the validated value to the screens and the game.

### Lanes from several sources

`LaneTouchTracker` becomes `LaneHolds`: which holders hold which lane. A holder is a pointer (its id), a key (its device
and key code), and later a gamepad button (its device and button). A lane is held while any holder holds it; the press
goes to the runtime with the first holder and the release with the last, as the tracker does for fingers now. It keeps
the tracker's rules: a holder belongs to the lane it pressed until it lets go, and a second press of a held lane sends
nothing. It adds releasing by kind of holder: all pointers on `ACTION_CANCEL` (keys stay held), all keys of one device
when it goes, everything on pause. It stays on the main thread, where both touches and keys arrive.

`LaneInput` gains a `source` (touch, keyboard, the autoplay's script), for diagnostics only. Each source has its own
small class that turns its events into `press(holder, lane, eventNanos)` and `release(holder, eventNanos)` on one object
the playfield owns, which keeps `LaneHolds` and the sequence counter and enqueues for the render thread. Touch keeps its
lane geometry ([gameplay.md, Input](gameplay.md#input)); keys bring `KeyLanes`. A gamepad later adds a third class fed
from the same `dispatchKeyEvent` for its buttons and from `Activity.dispatchGenericMotionEvent` (API 12) for its axes;
MIDI could add a fourth, since `MidiReceiver.onSend` stamps messages on `System.nanoTime()`. Neither changes the touch
or keyboard classes.

**The cost of "any holder".** A contact left on the glass in a lane swallows that lane's key presses, and a key held in
a lane swallows taps there, because a second press of a held lane sends nothing. The issue asks for this rule, osu!mania
does the same (its `Unique` binding mode: only the first binding of an action presses it, the last released releases
it), and the runtime cannot tell sources apart anyway: its `InputEvent`, as the app builds it, is a lane, a phase and a
chart time. Decision 2 keeps the rule; the touch log shows whether stray contacts during keyboard games make it worth
revisiting.

**To the render thread.** A key's time is `KeyEvent.getEventTime()`, milliseconds in the `SystemClock.uptimeMillis()`
base, which is the base of `System.nanoTime()`; multiplied by 1 000 000 it joins the queue next to touches, and the
frame, the allowance and the late-input count apply unchanged ([gameplay.md, The frame](gameplay.md#the-frame)). The
nanosecond accessor exists in AOSP but is `@hide` on `KeyEvent`, while `MotionEvent.getEventTimeNanos()` is public from
API 34; key times are therefore truncated to the millisecond, at most 1 ms early.

### Keyboards coming and going

**No restart.** `MainActivity` declares `orientation|screenSize|screenLayout|smallestScreenSize` and nothing for
keyboards. Android sets `Configuration.keyboard` to `KEYBOARD_QWERTY` when any non-virtual alphabetic keyboard is
present and `Configuration.navigation` to `NAVIGATION_DPAD` when a device reports a D-pad source (AOSP
`DisplayContent.computeScreenConfiguration`); a keyboard counts as a D-pad only if it has Up, Down, Left, Right and
Center (inputflinger's `DPAD_REQUIRED_KEYCODES`), which depends on the keyboard. An undeclared change restarts the
activity. Read from the code, not run: `PlayfieldView.onDetachedFromWindow` closes the game's controller and sound, and
the recreated play screen builds a new game, so a keyboard connecting to a phone without one restarts the song from its
lead-in. The manifest therefore adds `keyboard|keyboardHidden|navigation`. The app has no resources qualified by
keyboard or navigation, Compose recomposes what reads `LocalConfiguration` (only `DarkSystemBars`, which reapplies the
bar colours), and the playfield reads nothing a keyboard changes. `DiagnosticsActivity` keeps the default: it collects
again if recreated. The configuration-changes guide says Android 17 no longer restarts activities by default for
keyboard, keyboard-hidden, navigation and touchscreen changes; it does not say whether that depends on the target SDK,
the behaviour-change pages do not list it, and the app targets 36 with testers on Android 16.

**Who is connected.** `ConnectedKeyboards`, one per process in `AppStores` beside the output route and reached through
an `AppGraph` interface (so the screenshot scenarios can fake a keyboard), registers an
`InputManager.InputDeviceListener` on the main looper and keeps a `StateFlow` of keyboards: id, name, vendor and product
id, whether external (`isExternal()`, API 29, true for USB and Bluetooth devices unless their configuration says
otherwise), and the resolved positions. It needs no permission; pairing is Android's.

**A disconnect.** Android releases the device's held keys itself: removing a device resets its keyboard mapper, which
sends an `ACTION_UP` with `FLAG_CANCELED` for every key still down (AOSP `InputReader::removeDeviceLocked`,
`KeyboardInputMapper::cancelAllDownKeys`). Those ups release the lanes through the normal path. `onInputDeviceRemoved`
then releases whatever that device still holds (nothing, normally; the order of the two is not guaranteed) and pauses
the game with the device's name for the panel. Focus leaving the window also brings canceled ups for held keys ("focus
left window" in the dispatcher), and the game pauses on focus loss already.

**Several keyboards** play together, each through its own resolved positions, since layouts are chosen per keyboard. The
same key held on two keyboards is two holders and one press. Labels follow the first keyboard connected.

**Keyboards that sleep.** A Bluetooth keyboard that drops its connection when idle and reconnects on the next key
appears to Android as a removal and a new device. Whether that first key is lost depends on the keyboard and is not
verified. During a long pause it changes only the labels; the next lane key after the reconnect plays normally.

### What stands before the app

- **Input method.** `ViewRootImpl` hands a key to the input method before the views (pre-IME, IME, post-IME, view), and
  `InputMethodManager.dispatchInputEvent` forwards it whenever an input method session is available, a condition on the
  binding rather than on a focused text field; whether a session stays bound to the play window was not traced. During
  play no text field has focus, so an input method has no reason to take letters, but whether HyperOS's usual input
  methods pass them untouched is not verified (checklist 20). The event time is unaffected either way; only the handling
  delay grows. If one does take them, the fallback is a pre-IME hook on a focused view during play (`View.onKeyPreIme`),
  with the focus cost described above.
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
in the stamp: the keyboard's own scanning, the radio link and the Bluetooth stack. What comes after it (dispatch, an
input method, the main thread, the queue) delays the handling, not the stamp. That second part is measured per key by
the key log's handling delay and judged away by the 100 ms allowance; the first part cannot be measured from inside the
phone. No figure for it is given here: none was measured for these keyboards, and it varies with the keyboard and the
link.

**Where that delay goes.** gameplay.md keeps no input offset: the time from a finger landing to its timestamp shifts
every tap like an unreported output latency, and lives in the audio offset kept per output ([gameplay.md, Offsets and
calibration](gameplay.md#offsets-and-calibration)). The Swift app does the same with keyboard latency. A keyboard is a
second input device, so a player who changes between touch and keys on the same output may need two values. Decision 6
weighs three ways:

- **A. No separate value (recommended until measured).** The audio offset absorbs keyboard delay as it absorbs touch
  delay. A keyboard player calibrates with keys (the sound step takes them) and Results' suggestion keeps working, since
  the runtime's suggestion comes from the hits whatever made them. The cost: someone who alternates touch and keys on
  one output carries the difference until the next calibration or suggestion.
- **B. A delay per keyboard,** keyed by vendor and product id, 0 by default, subtracted from that keyboard's event times
  before judging. A tap's chart time is its song time plus the audio offset A, so key taps land on the clicks when the
  delay is `d = median key error + A`, with A the offset set by touch on that output. The calibration would show it on
  key taps, and the Keyboard screen would show its value. The cost: a second number, valid only once A was set by touch.
- **C. An audio offset per output and per kind of input.** The cost: twice the offsets, and calibration and Results must
  know which input played.

**How to measure before choosing.** Checklist 18: three calibrations with the pad and three with lane keys, on the same
output, one after the other. The difference of their medians is the keyboard's delay against touch, including its
Bluetooth link. gameplay.md already asks three runs per output to agree within 5 ms; if keys and pad differ by more than
that, B is worth its cost. Checklist 19 films a key press and a finger tap at 240 fps or more with the lane light in
frame; the frames from contact to light compare the whole paths, display included.

**Calibration with keys** under A is the sound step as it is, with key downs as taps: the event time converted through
the click track's clock, the error against the nearest click, the median of the last 16 ([gameplay.md, Calibration,
sound step](gameplay.md#offsets-and-calibration)). The picture step has no taps and no keys, so neither delay can leak
into the visual offset.

## Edge cases

- **A key held as the game starts** (pressed before the playfield existed): its down was never seen, so its up releases
  nothing and is ignored.
- **Keys held at a pause.** Pausing clears the app's holders now; for touch, gameplay.md says "Pausing releases every
  lane in the tracker; inputs queued before the pause are dropped". Read from the code, those releases never reach the
  session or the feedback: `PlayfieldView.pause()` clears the tracker without enqueueing them, and a paused frame clears
  the queue. What the runtime then does with lanes it still holds after the resume is not confirmed (the runtime is not
  in this checkout). Keys make this common, since a player often holds a lane key while reaching for Escape. Decision 9;
  the keyboard path follows whatever touch does.
- **Keys during the count-in** are dropped, as touches are, and a key held through it plays only when pressed again.
- **Rollover and ghosting.** A keyboard may not report every key held at once, or may report one that is not held. When
  a USB or Bluetooth keyboard reports a rollover error, Linux ignores that whole report, so the keys already held stay
  held and the new one is not seen (`hid-core.c`, "Ignore report if ErrorRollOver"). The app cannot tell a key not
  pressed from a key not reported; the key test shows what arrives, and the most lane keys held together in each game is
  logged.
- **Bounce keys** drop fast repeats of one key; the app cannot detect it (What stands before the app).
- **The layout changes** while the app is in the background: positions are resolved again on `ON_START`; the game was
  paused on leaving, so no held key changes meaning mid-press.
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
data, as [phone-checks.md](phone-checks.md#what-to-send-back) promises.

- **Key log (debug and checks builds).** `files/key-log.csv`, beside `touch-log.csv`, written when the playfield closes:
  `action,eventNanos,handledNanos,keyCode,scanCode,repeat,flags,keyboard,lane,sent` for every key event the playfield
  saw, with `lane` −1 for unbound keys and `sent` 1 when a press or release went to the queue. Its summary under
  `EnsomiPlayfield` gives the handling delay at the median, the 99th percentile and the most, as the touch log does. K6
  adds it to what `scripts/phone-checks.sh -g manual` collects.
- **The game's log** (`EnsomiPlay`, at game over): presses by source, late inputs by source (an input is late when its
  chart time falls before the session's last advance; the runtime's own `lateInputs` stays the total), the most lane
  keys held together, the keyboards seen by vendor and product, and whether one disconnected. Pause causes gain
  "keyboard disconnected".
- **Journal.** `input_device` with phases `added` and `removed`, not durable, context `kind=keyboard`, `vendor`,
  `product`, `external`, `in_game`. One record per connection, never per key ([diagnostics.md, Journal API and
  durability](diagnostics.md#journal-api-and-durability)).
- **Summary JSON.** An `input.keyboards` list (vendor id, product id, external, keyboard type) read from `InputManager`
  in the Diagnostics activity without app stores, an additive field as schema 2's own additions were ([diagnostics.md,
  Summary JSON](diagnostics.md#summary-json)). No names and no input device descriptors, which are stable identifiers.
  Decision 10.

## Checks

### JVM tests

All of it is pure Kotlin on plain values, so no test builds an Android `KeyEvent`:

- `LaneHoldsTest` (from `PlayfieldGeometryTest`'s tracker cases): a finger and a key on one lane give one press and one
  release; two keyboards on one key; a second press of a held lane sends nothing; `ACTION_CANCEL` releases pointers and
  leaves keys; a device's release leaves the others; an unknown holder's release is ignored.
- `KeyLanesTest`: repeats ignored; a canceled up releases; fallback, soft-keyboard and virtual-hard-key events never
  play; non-alphabetic devices pass through; a fake AZERTY layout maps the Q position to `KEYCODE_A`; the virtual
  keyboard maps every position to itself; unbound keys pass through and navigation keys are taken during play.
- `LaneKeysTest`: D F J K by default; four distinct keys; a rebind to a used key swaps; Escape, Enter, Tab, modifiers,
  volume, function and keypad keys are refused; `settings.json` without `laneKeys`, with three names, duplicates or an
  unknown name reads the default; a round trip keeps the order.
- `KeyTestTest`: the most lane keys held together, the other keys held, and the limit sentence only after all four keys
  have been pressed.
- `CalibrationTapsTest` gains key taps, with repeats and releases ignored.

### On the emulator

[gameplay.md](gameplay.md#what-the-emulator-can-and-cannot-show) says what the AVD shows in general. For keys:

| Tool | What it exercises | What it cannot |
| --- | --- | --- |
| `adb shell input keyevent KEYCODE_D` | A down followed at once by an up, from the virtual keyboard (id −1, scan code 0): taps, bindings, Escape, Tab, Enter | Holds, chords, a real device |
| `input keyevent --duration 800 KEYCODE_D` | A held key: the down, one repeat with `FLAG_LONG_PRESS` at the long-press timeout, the up at 800 ms: repeat filtering and hold notes | Chords |
| `input keycombination -t 300 KEYCODE_D KEYCODE_F …` | Several downs at once: chords of presses | Holds: in AOSP its ups carry the downs' event time, so each release is stamped at its press |
| `adb emu event send EV_KEY:KEY_D:1`, later `EV_KEY:KEY_D:0` | The AVD's own keyboard, with Linux key codes: holds and chords at any timing; several events per command | Connecting and disconnecting; timing set by the host (the stamps are the guest's) |
| `adb shell hid -` (AOSP's uhid tool, in its base system image; `adb shell` runs with the `uhid` group for it) | A HID keyboard registered with `"bus": "bluetooth"`: registering connects it, reports press keys (D F J K are HID usages 0x07, 0x09, 0x0D, 0x0E), a report holding the rollover error (0x01) is ignored by the kernel, end of input disconnects it. The listener, labels, canceled ups, the disconnect pause, the rollover sentence | The radio, a real keyboard's matrix, sleep and reconnect, HyperOS |
| The emulator window with "Enable keyboard input" (`hw.keyboard`) | The reviewer plays with the Mac's keyboard | Astra's headless runs |

**The restart check needs a keyboard-less AVD.** `adb shell am get-config` prints the configuration's qualifiers,
`nokeys` or `qwerty` among them. If `ensomi-36` reports `qwerty` before any HID keyboard registers (an AVD with
`hw.keyboard` on has a keyboard of its own), registering another changes nothing and proves nothing; the check then runs
on an AVD with `hw.keyboard = no`, which must first show `nokeys`. Whether `hid` runs from `adb shell` on these images,
and which keyboard the AVD reports, is the first thing step K2's emulator run records.

### On a phone or tablet

New rows for [the runbook's checklist](phone-checks.md#the-checklist), on the Redmi K Pad with the owner's Bluetooth
keyboard first, then the Redmi K80. HyperOS's three-finger gesture does not matter here unless touch is also used.

| # | Check | What to do | What to record |
| --- | --- | --- | --- |
| 14 | Keyboard and labels | Pair the keyboard; open Settings › Keyboard; look at a game's tray | The name shown; whether the labels match the keycaps; the layout Android chose |
| 15 | Rollover | Settings › Keyboard › Test keys: hold D F J K together; then pairs and threes; then your own keys | "Most lane keys held together"; any lane that stayed dark, or lit without its key |
| 16 | Connect and disconnect in a game | Keyboard off; start a game; turn it on mid-song; later, hold a lane key and turn it off | Whether the song went on without restarting when it connected; whether the game paused, what the panel said, and whether any lane stayed lit after Resume |
| 17 | A full game with keys | Play one whole song with the keyboard, then run the `manual` collection | The results screenshot; anything late or missed (the archive holds the key log and late inputs by source) |
| 18 | Calibration, keys against touch | On one output: Settings › Calibrate three times with the pad, then three times with lane keys | Each suggestion, pad and keys apart |
| 19 | Key to light | Film a lane key press with the lane light in frame at 240 fps or more; then a finger tap on the glass the same way | Both videos |
| 20 | What stands before the app | Play with the phone's usual input method; try Meta shortcuts and Game Turbo if present; with TalkBack on, press lane keys in the preview | The input method's name; any lost key or popup; any key the system took; whether lane keys played with TalkBack on |
| 21 | Keys around the game | Without touching: Library → song → Play → Escape → Escape → finish → Play again → Escape back to the song; Tab through Settings and the Keyboard screen | Anything unreachable, or focused without a visible focus state |

A keyboard left idle (a long pause, then a lane key) and a second keyboard, where one is at hand, go in the notes of 16.

## Build plan

Steps in the manner of [plan.md](plan.md); a step is done when `assembleDebug`, `testDebugUnitTest` and `lint` pass, its
emulator check passes and the screenshots of the screens it touches were looked at. K3 to K6 can go in any order after
K2. No step adds a library, a pin or a permission.

| Step | Builds | Emulator check | Phone check | Needs |
| --- | --- | --- | --- | --- |
| K1 | `LaneHolds` in place of `LaneTouchTracker`, touch unchanged; `LaneInput.source`; `LaneKeys` (table, defaults, swap, storage) in `StoredSettings`; `KeyLanes` as pure logic | The JVM tests above; a game from `adb shell input tap` and the capture script as before; `settings.json` keeps every field and gains `laneKeys` | — | Decisions 1, 2, 11 |
| K2 | Keyboard play: the manifest's `keyboard\|keyboardHidden\|navigation`; `HardwareKeys` in `MainActivity`; `ConnectedKeyboards` in `AppStores` with a fake for the scenarios; lane keys, Escape and the taken navigation keys in play; the disconnect pause and its panel line; labels on the caps; the key log and the game log's additions | `input keyevent` taps judged; `--duration` gives one press and one release; `emu event send` chords and holds judged; a `hid` keyboard registered mid-game: `am get-config` before and after, `MainActivity` created once (a debug log line), the song goes on; `hid` closed: canceled ups in the key log, the panel's line; captures with labels in both orientations | 14, 16, 17, 20 | K1; decisions 8, 9 |
| K3 | Settings › Keyboard: status, links, lane keys, capture, swap, refusal, defaults, the key test and its limit sentence; the Settings row; scenarios with a fake keyboard | Capture by `input keyevent`; kept after a force-stop; the test lit by `emu event send` holds (4 of 4) and by a `hid` keyboard that never reports a fourth key (the sentence); UI Automator dumps; TalkBack walk; 200 % font; landscape | 14, 15 | K2; decision 7 |
| K4 | Keys around the game: Escape in the count-in and the pause panel, first focus on Resume and Play again, Escape as Back; a focus pass over every screen; optionally the game's keys in Android's keyboard shortcuts list (`Activity.onProvideKeyboardShortcuts`, API 24) | `input keyevent` sequences of Tab, arrows, Enter and Escape from the Library to a game, its pause, its results and back, without a tap; screenshots of every focus state; UI Automator's `focused` | 21 | K2; decisions 3, 4, 5 |
| K5 | Calibration with keys: lane keys as taps in the sound step, the pad's text, the input in the calibration log; option B's delay if decision 6 chooses it | Key taps from `input keyevent` at logged event times give the app's suggestion when recomputed, as step 8 did with `input tap` | 18, 19 | K2; decision 6 |
| K6 | Diagnostics and runbook: the journal's `input_device`, the summary's `input.keyboards`, the `manual` collection of `key-log.csv`, checklist rows 14 to 21 in phone-checks.md | Redaction tests; the Diagnostics JSON lists a `hid` keyboard by vendor and product; no device name anywhere in the export or the archive | The checklist run | K2; decision 10 |

When the steps are done, design.md takes the screens and the keys table, gameplay.md the input path, timing and edge
cases, plan.md the steps and what they showed, phone-checks.md the rows, and design.md's "Not carried over in this
round" drops key bindings.

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
[`ViewConfiguration`](https://developer.android.com/reference/android/view/ViewConfiguration) (key repeat timeout and
delay: 12), [`SystemClock`](https://developer.android.com/reference/android/os/SystemClock),
[`Settings`](https://developer.android.com/reference/android/provider/Settings) (`ACTION_BLUETOOTH_SETTINGS`: 1;
`ACTION_HARD_KEYBOARD_SETTINGS`: 24),
[`AccessibilityServiceInfo`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_REQUEST_FILTER_KEY_EVENTS)
(18), [`MidiReceiver.onSend`](https://developer.android.com/reference/android/media/midi/MidiReceiver) (23).

Android guides: [`<activity>` `configChanges`](https://developer.android.com/guide/topics/manifest/activity-element),
[configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes) (Android 17's default),
[Compose focus](https://developer.android.com/develop/ui/compose/touch-input/focus), [Compose keyboard
actions](https://developer.android.com/develop/ui/compose/touch-input/keyboard-input/commands), [adaptive app quality,
tier 2](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2), [emulator
console](https://developer.android.com/studio/run/emulator-console) (`event send`),
[AVDs](https://developer.android.com/studio/run/managing-avds) (`hw.keyboard`).

AOSP at `android-16.0.0_r1` (android.googlesource.com): frameworks/base `KeyEvent.java` (on `main`: the `@hide`
nanosecond time), `InputDevice.java`, `InputManagerGlobal.java`, `Activity.java`, `Dialog.java`, `ViewRootImpl.java`,
`InputMethodManager.java`, `Configuration.java`, `InputSettings.java`, `services/.../wm/DisplayContent.java`,
`services/.../policy/PhoneWindowManager.java`, `services/.../input/InputShellCommand.java`,
`services/.../am/ActivityManagerShellCommand.java`, `data/keyboards/Generic.kcm` (also at the Android 11, 13, 14 and 15
tags), `cmds/hid/README.md` (`main`); frameworks/native inputflinger `EventHub.cpp`, `KeyCodeClassifications.h`,
`InputReader.cpp`, `mapper/KeyboardInputMapper.cpp`, `dispatcher/InputDispatcher.cpp`, `dispatcher/InputState.cpp`;
build `target/product/base_system.mk`; system/core `rootdir/ueventd.rc`; packages/modules/adb `daemon/main.cpp`;
packages/modules/Bluetooth `system/btif/co/bta_hh_co.cc` (`main`).

Others: Compose UI and Foundation 1.12.1 sources from Google's Maven (`FocusOwnerImpl.kt`, `Focusability.kt`,
`Clickable.kt`); Linux `drivers/hid/hid-core.c` and `hid-input.c` (`torvalds/linux` master); osu!lazer's
`SingleStageVariantGenerator.cs`, `VariantMappingGenerator.cs` and `ManiaInputManager.cs` (`ppy/osu` at `49ebb6e`) and
osu-framework's `KeyBindingContainer.cs` (`ppy/osu-framework`), MIT, read for behaviour only.

## Not verified

- The delay from key contact to event time on any Bluetooth keyboard, and how it compares with touch (checklists 18,
  19).
- Whether HyperOS's input methods, its shortcuts or Game Turbo take lane keys or Escape (checklist 20).
- Whether TalkBack passes plain letter keys to the app (checklist 20).
- Whether choosing another keyboard layout in Android's settings raises `onInputDeviceChanged`; the design resolves
  positions again on `ON_START` regardless.
- Whether a sleeping keyboard loses the key that wakes it, which depends on the keyboard.
- Whether `hid` runs from `adb shell` on `ensomi-36` (AOSP grants the group; SELinux on the image is untested), and
  whether that AVD reports a keyboard of its own.
- Whether Android's dispatcher adds its own repeats to keys injected by `adb shell input`.
- The runtime's handling of a release for a lane it does not hold and of lanes left held across a pause. The runtime is
  a local AAR outside this checkout; from the app's calls, `GameplaySession.input` takes `InputEvent(lane, phase,
  chartMs)` and `GameplayFeedback.recordInput` takes the lane, phase, a sequence number and the input's time in
  milliseconds, with no source, so keys need no runtime change.
- Whether Material 3's focus state is clearly visible on the brand's plum in the game presentation, and whether its
  sliders step with the arrow keys (K4's captures).

## Decisions for the owner

1. **Key identity.** (a) By position, through `getKeyCodeForKeyLocation`: the same fingers on every layout, labels
   follow the layout. (b) By the key code the layout produces, closest to the Swift app's characters: simpler, but on
   Dvorak D F J K are scattered. (c) By scan code: physical, but its reference calls the values unreliable across
   devices, and `adb` sends none. **Recommendation: (a).** It costs one binder call per lane per keyboard at connect and
   is available at the app's minimum API.
2. **Touch while keys play.** (a) Touch stays live and any holder holds a lane, as the issue asks. (b) Touch is ignored
   for the rest of a game once a lane key is pressed, which removes stray contacts and the silenced-lane case. (c) A
   setting. **Recommendation: (a)**, revisited if key logs show stray touches silencing lanes.
3. **Escape in the pause panel.** (a) Resumes with the count-in: Escape toggles pause, as in most PC rhythm games and as
   tier 2's "Esc closes modals" reads. (b) Acts as Back and leaves the game, as Back does there now: two Escapes in a
   row then lose the game. **Recommendation: (a).** Back keeps leaving.
4. **Space.** (a) A possible lane key that never pauses. (b) Pauses when it is not bound, as tier 2 suggests for media
   playback. **Recommendation: (a)**: a pause on a mis-press costs the passage.
5. **Escape on other screens.** (a) Back on every screen but the Library, where Back would leave the app. (b) Nothing,
   Android's default for a full-screen activity; Meta + Escape remains the system's Back. **Recommendation: (a).**
6. **Keyboard timing.** (a) No separate value; calibrate with keys; measure with checklist 18 first. (b) A delay per
   keyboard. (c) An audio offset per output and kind of input. **Recommendation: (a) now, then (b) if keys and pad
   differ by more than 5 ms on the same output.**
7. **Where bindings live.** (a) A Keyboard screen of its own from one Settings row. (b) The four keys inline in
   Settings, with the test in a dialog. **Recommendation: (a)**: capture and test take every key, which is clearer on a
   screen of their own, and the Settings list stays short.
8. **Labels on the caps.** (a) While a keyboard is connected. (b) Always. (c) A setting. **Recommendation: (a).**
9. **Lanes held at a pause, for touch and keys alike.** (a) As now: the app forgets its holders and the session is not
   told. (b) The session gets a release for every held lane at the pause's time before the clock stops, so nothing stays
   pressed across a pause. (c) Holders survive the pause and press again at the resume. **Recommendation: (b), once the
   runtime's behaviour under (a) is confirmed;** it would be its own small step before K2 and changes touch play too.
10. **Keyboards in Diagnostics.** (a) Vendor and product ids, whether external, and keyboard type in the journal and the
    summary, never names. (b) Nothing outside the debug logs. **Recommendation: (a)**: it tells a bad keyboard from a
    bad build without personal data.
11. **Rebinding to a key already used.** (a) The two lanes swap keys and the screen says so. (b) Refused, as in the
    Swift app ("Choose four unique non-empty keys."). **Recommendation: (a).**
