# Ensomi for Android: UI and UX design, drop-in play

This is the design of the app's first real round, built as it stands here. The gameplay side (playfield, clock, input,
judgement, offsets, frame pacing) is in [gameplay.md](gameplay.md); the order of work is in [plan.md](plan.md). Every
screen below runs on the app's own stores; the last section says how the screenshot scenarios still open each state on
fakes.

The Swift app on `dev/swift` (at `623feaa`) is the reference for what to build: its features, flows, look and timing
care. This document designs how, with Android's own conventions and APIs; it ports no Swift code.

## Who it is for and the core loop

Ensomi is for someone who plays 4-key rhythm games (osu!mania 4K and the like) and wants to play their own music. They
have songs on their phone and no charts for them. Ensomi makes the chart on the phone, from the song, with the R2
model, and they play it with their fingers on the glass.

The loop: **pick a song → make a chart → play → see the result → play again or make another.** Everything else
(installing the model, adding songs, settings, calibration) serves that loop and stays out of its way.

This round is drop-in play only: install the model, add songs, make a chart with the settings the Swift app offers,
play it with the runtime's judgements, pause, results, offsets and calibration. Not in it: Listen & Play (microphone
sync), recognition, the Chart Inspector, any network service, and saving `.osu`/`.osz` files. A test path through
recognition and sync exists in debug and checks builds only, on one plain screen of its own
([recognition.md](recognition.md)); it is no part of this design.

## Information architecture and navigation

One activity, one back stack of Navigation 3 keys (the shell's pattern). Library is home; everything else is pushed
on top of it and Back pops.

| Screen | Reached from | Back goes to |
| --- | --- | --- |
| Library (home) | launch | leaves the app |
| Make chart (one song) | a song in Library | Library |
| Play | Play on Make chart | pauses first; from the pause panel, Make chart |
| Results | the end of a game (replaces Play) | Make chart |
| Settings | the gear in Library's top app bar | Library |
| Timing calibration | Settings | Settings |
| Playfield preview | Settings › About | Settings |
| Diagnostics | Settings › About | Settings |

- **No navigation bar.** Material's navigation bar is for three to five top-level destinations
  ([M3 navigation bar](https://m3.material.io/components/navigation-bar/guidelines)); this app has one place to be,
  the library, and a settings screen. The Swift iOS app's Library and Play tabs collapse into one flow on Android,
  because play always starts from a song.
- **Settings is a screen, not a sheet**, as Android apps do it, and it is not reachable during a game. So nothing
  needs locking while a game is in progress (the Swift app disables sound and timing settings during play because its
  Settings window can open at any time).
- **Results replace the game** on the back stack, so Back from results goes to the song, never into a finished game.
- **Predictive back.** Apps targeting API 36 get the system's predictive back animations by default
  ([Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-16)). The play
  screen intercepts Back to pause instead; a second Back, from the pause panel, leaves.

## Screens and states

### Library

A top app bar ("Ensomi", the Settings gear), the songs as a Material list (title; artist and length; how many charts
are kept for it), and an extended floating action button, **Add songs**. Tapping a song opens Make chart.

- **First run.** Until the model is installed, a card at the top of the list carries it (below). Under it, an empty
  state card says how songs get in and has its own Add songs button. The first screen therefore reads as two steps,
  get the model and add songs, and both can happen at once.
- **A song whose file has gone** (moved, deleted, its permission revoked) stays in the list with "File not found"
  and its file name, and offers Find again (pick it anew; the song and its charts stay) and Remove. Removing a song
  forgets it and gives its permission back; the file stays where it is. The library looks again at each start and
  each return to the app, by asking the provider for the document's name (a cloud file is not fetched), and a song
  whose file fails to open is marked the same way.
- The songs are listed by title as the phone's language sorts it; a song without a title tag shows its file name.

### Getting the chart model

The model is the published Ensomi R2 package: the `onnx/` directory of `sed-i/ensomi-r2` on Hugging Face at the pinned
tag `r2-1.0.1`, 30 files and about 120 MB by its manifest. The app installs it the way the Swift app's solo-device
mode does: fetch `ensomi-package.json`, check it with the runtime's `checkPackage`, fetch exactly the files it lists,
check each one's size and SHA-256, and only then move the directory into place with an `ensomi-install.json` record of
the tag. It never reads `main`.

**Two ways in, one package** (the owner's decisions of 2026-10-29). The download from Hugging Face is the first,
recommended way, on every screen that offers the model. The second, **Install from files**, is for phones that cannot
reach Hugging Face (huggingface.co is blocked in mainland China): the player picks the folder that holds the package's
files, and the app copies them into its own storage (`noBackupFilesDir`), where the runtime gets the file path it
needs. Both accept only the exact pinned package: the SHA-256 of `ensomi-package.json` for `r2-1.0.1` is pinned in the
app (`ModelSource.PINNED_MANIFEST_SHA256`, beside the tag, the one place both are named), so a manifest with any other
hash is refused before a file is fetched or copied, and every file it lists is then checked by its own size and
SHA-256. The record says which way the files came (`"source": "huggingface"` or `"files"`).

- **A folder, not files.** The picker is `ACTION_OPEN_DOCUMENT_TREE`: one choice for the package's 30 files, where
  `OPEN_MULTIPLE_DOCUMENTS` would ask the player to select all 30 and could not tell a missing file from one left
  unselected. The folder is the one that holds `ensomi-package.json`, or the folder above it when the files are in its
  `onnx` folder, as on Hugging Face. Android 11 and later do not let the root of Download be picked, so the files go
  in a folder inside it (`Download/ensomi-r2`). The app reads only the files the manifest lists, once, and keeps no
  permission to the folder.
- **The same checks as the download**: the manifest's pinned SHA-256 and `checkPackage`; every listed file present, at
  its listed size, before anything is copied; the space reserved with `allocateBytes`; each file's size and SHA-256 as
  it is copied into `.files-ensomi-r2` beside the install; the runtime opening the copy; the record; one rename into
  place. Whatever stops it (a refusal, a file that does not match, a provider that goes away, Cancel) deletes the
  copy, so the installed model is the old one or the whole new one, never half of either.
- **What the player sees**: under Download, a line ("Can't reach Hugging Face? If you have the package's files, pick
  the folder that holds them") and an outlined **Install from files** button; while it copies, the download's
  progress bar under "Installing the Ensomi R2 model from files"; if it stops, the reason in words with a warning
  symbol: not the pinned package, no `ensomi-package.json`, a file missing (named), a file that does not match (named),
  the files unreadable, not enough space. A copy started during a download stops the download first.

| State | What the card says | Actions |
| --- | --- | --- |
| Not installed | What the model is, that it is a one-time download of 120 MB, that songs never leave the phone; "Needs 120 MB. 18.4 GB free."; after a download that stopped before keeping anything, why ("The download from Hugging Face failed. The connection was lost.") | **Download model** (the screen's primary action); Install from files |
| Not enough space | "Not enough space: the model needs 120 MB, and 80 MB is free." with a warning symbol | Try again; Free up space (the system's storage screen) |
| Downloading | Progress bar, percent, megabytes of total; "You can add songs while it downloads." | Cancel |
| Installing from files | The same progress, under "Installing the Ensomi R2 model from files" | Cancel |
| Stopped | "The download stopped at 50.6 of 120.4 MB." and the reason; "Resume continues from the files already here." | Resume download; Install from files |
| Installed (Settings only) | "Ensomi R2 r2-1.0", package tag and size | Download again; Remove |
| Updating (Settings only) | The installed model, and progress to the pinned tag, which it stays in use until | Cancel |
| Update pending (Settings only) | The installed tag, the pinned one, and that the installed model stays in use until the update arrives; why the last try stopped | Download again, or Resume download when files are kept; Remove; Install from files |

- Free space comes from `StorageManager.getAllocatableBytes`, which counts what the system can free from caches, and
  the download reserves what it still has to fetch with `allocateBytes`, which frees the system's caches if it must
  ([StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager)).
- Files live in app-specific storage under `noBackupFilesDir`, as the runtime's own resampler tables do, so they are
  never backed up (the app's data extraction rules already exclude everything). A stopped download keeps its files
  and resumes with HTTP Range requests; there is no automatic retry. While the app looks at what is installed, at
  each start, the library shows no model card.
- In this round the download runs in the app's process while the app is open. Whether it should go on in the
  background, under a notification, is an open question for the owner.
- Settings also shows the model's licence (from its manifest), a link to the model card, and the notice texts of
  BeatThis and ONNX Runtime (the AAR's `onnxRuntimeLicenseAssets`).

### Adding songs

**Add songs** opens the system document picker (`ACTION_OPEN_DOCUMENT` for `audio/*`, several at once). The app takes
a persistable read permission on each pick
([Access documents](https://developer.android.com/training/data-storage/shared/documents-files)), so it needs no
storage or media permission and sees only what the player chose, from local storage, Downloads or a cloud provider. The
photo picker has no audio mode (stack report §2.6). The library keeps each song's URI, file name, length and, when the
file has them, its title and artist tags, in `library.json` in the app's files; it copies nothing. Cancelling the
picker is not an error and says nothing; a file that cannot be read as audio is named in a snackbar, and a song
already in the library is not added twice.

Browsing the whole music collection instead (MediaStore with `READ_MEDIA_AUDIO`,
[Android 13 granular media permissions](https://developer.android.com/about/versions/13/behavior-changes-13#granular-media-permissions))
is an open question for the owner: it would show every song at once, at the price of a permission prompt.

### Making a chart

One screen per song. The top app bar has Back and the song's title; under it, the artist, length and file name.

- **Stars**: a slider in 0.1 steps over the model's range `[min_star, max_star_exclusive)` (2.0 to 5.9 for R2), the
  value beside it, and a line naming the model and its range.
- **Seed**: a number field with the placeholder "Random" and the Swift app's explanation ("Blank makes a new chart
  each time. The same song, stars, sections and seed make the same chart again."). Anything but a 64-bit unsigned
  number shows its error under the field.
- **Sections**, collapsed by default ("Optional. Without any, the whole song follows the stars."). Expanded, each
  section is a card: start and end in `mm:ss`, a kind menu from the model's capabilities (experimental kinds labelled,
  kinds marked not working left out, the capability's note under the menu), then a strength slider from 0 to 1 or, for
  a star target, a star slider. Problems show under the section in words with a symbol, with the runtime's own rules:
  8 to 32 seconds, inside the song, no overlap (sections may touch).
- **A status card at the top and the actions at the bottom.** The bar at the bottom holds only the screen's actions,
  so the primary action is always in the same place and the bar stays small at large text sizes and in landscape;
  what happened shows in a card above the stars:

| State | Status card | Bar |
| --- | --- | --- |
| Ready | none | **Make chart**, with the reason beside it when it is disabled ("Install the chart model first.", "A section needs a change before the chart can be made.") |
| Making | The five phases (Loading audio, Finding the beat, Placing notes, Arranging lanes, Finishing), each done, under way or to come, in words and a symbol | An indeterminate progress bar; **Stop** |
| Stopped | "Stopped. No chart was made." | Make chart |
| Failed | "The chart could not be made." with a warning symbol, the runtime's message under it | Try again |
| Ready to play | "Chart ready", "1,229 notes: 999 taps, 230 long notes", the seed with **Use seed** | **Play** (primary) and Make another, side by side, stacked at large text sizes |

- The runtime reports phases, not fractions, so the bar is indeterminate rather than a guessed percentage. The app
  shows Loading audio while it decodes the song, Finding the beat while the runtime's `prepare` runs, Placing notes
  while the whole-chart scheduler works and Finishing while the chart is saved. The scheduler reports no phases of its
  own, so Arranging lanes is never shown under way (as in the Swift app; plan.md, Runtime changes).
- Choosing a song starts the runtime's `prepare` (the audio stages, most of the work) in the background, as the Swift
  app does, so Make chart often has only the chart stages left. The four latest are kept in memory by the SHA-256 of
  the song's file and the model's revision. A game starting stops a preparation nobody waits for, so the game has the
  processor; opening the song again starts it again.
- The fields are disabled while a chart is being made. Stop signals the runtime's `Cancellation` (during the audio
  stages) or the scheduler's `stop()` (during the chart stages) from another thread; the runtime stops at its next
  graph run.
- The last chart made for a song is kept in app storage (its `.osu` and receipt), so a game can be played again after
  the app was closed. The Swift app keeps it in memory only; on Android the process can be killed in the background at
  any time.

### Play

Full screen: the system bars hidden with `WindowInsetsController` and `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`
([Immersive mode](https://developer.android.com/develop/ui/views/layout/immersive)), the screen kept on, and the phone
turned to the play orientation: **landscape** by default, with the lanes leaning back; portrait is a setting (see
Phone sizes and orientation). Before the game, the song's last chart is read and the song decoded for play, with
"Loading the song…" in the game's dark presentation (instant for Play again, which keeps the sound); a song whose file
has gone, or with no chart, says so there with Back. Without a panel of its own, the HUD: the song's title and its
stars and judge on the left, accuracy on the right, then a Pause button; in landscape these sit in the top corners beside the lanes' far
end, in portrait in a row above the lanes. The playfield itself, its look and its timing are in
[gameplay.md](gameplay.md). A game starts with two seconds of lead-in before the song's first frame, so the hands can
reach the lanes. The lead-in runs only while the play window has focus: the first time an app hides the system bars,
Android shows its "Viewing full screen" prompt, which takes focus a moment later, so losing focus before the song has
started holds the lead-in, without the pause panel, until the prompt is dismissed.

The game pauses by itself whenever the player cannot be playing:

| Event | API |
| --- | --- |
| Pause button, or Back | `OnBackPressedDispatcher` through Compose's `BackHandler` |
| The app leaves the foreground | lifecycle `ON_PAUSE` |
| Another window takes focus (notification shade, a dialog, split screen) | window focus |
| Another app takes audio focus (a call, a voice assistant) | `AudioManager` focus loss ([audio focus](https://developer.android.com/media/optimize/audio-focus)) |
| Headphones unplugged or disconnected | `ACTION_AUDIO_BECOMING_NOISY` ([output devices](https://developer.android.com/media/platform/output)) |
| The output device changes | `AudioTrack.addOnRoutingChangedListener` |

A short sound that only ducks the game (a notification) does not pause it: Android lowers the game's volume for it by
itself. Audio focus is asked for again on each resume; if it is refused (a call goes on), the game stays paused. When
the app returns from the background, the game is where it was, paused: it outlives the playfield's surface.

### Pause

The playfield stays visible, dimmed and frozen, without the judgement word and combo. A panel in the brand's card
shape says "Paused" and "Resume when you're ready", with **Resume** (primary), Restart and Quit. When the output
changed, the panel also says so: "Sound moved to Pixel Buds. Its audio offset applies from the next game." (the game
keeps its offsets from start to end, as in the Swift app).

**Resume counts in**: three beats over 1.5 seconds ("3, 2, 1" with "Get ready"), then the music goes on from where it
stopped. The Swift app resumes at once, which suits a keyboard; on a phone the hands left the glass. The runtime's
session never moves back in time, so the game cannot rewind instead.

### Results

In the game's dark presentation, replacing the game, and still in the play orientation, so the end of a song does not
turn the screen under the player's hands: the song with its stars and seed; accuracy, large; Perfect, Good
and Miss counts, each named and coloured (three tiles, or three rows at large text sizes); the longest combo; a timing
chart of how early or late the hits were, with the mean in words ("Your hits were 6 ms late on average."). When the
runtime's score suggests an audio offset change of 3 ms or more, a line offers it for the output that was used:
"Suggested audio offset for Phone speaker: −6 ms (now 0 ms)." with **Use suggested offset**. Then **Play again**
(primary) and Back to song. In landscape the screen has two columns, the score on the left and the timing, the
suggestion and the actions on the right, so Play again is on the first screen. Content scrolls behind an opaque band
under the status bar.

### Settings

A Material list in sections:

- **Gameplay**: scroll speed (a slider from 1.0× to 40.0× in 0.5 steps, with the fall time in seconds: "Notes take
  0.46 s to fall to the line."), judge difficulty (segmented A to E, C by default).
- **Playfield**: orientation (segmented Landscape and Portrait, Landscape by default); **Lane lean** for the
  orientation chosen (a slider from Flat to 55° in whole degrees; 40° in landscape and Flat in portrait by default:
  each orientation keeps its own); and a **live preview**: the sample chart playing itself on the game's own renderer,
  at the phone's full-screen proportions in the chosen orientation, scaled into the list, under the sliders on a phone
  held upright and beside them in landscape, so it stays in sight while a slider moves. The preview follows
  the lean and the scroll speed while their sliders are dragged, so the player sees what a change does before playing.
  For the full-size look, About › Playfield preview opens the same sample as a game.
- **Sound and timing**: music volume (a fraction of the song's own level), audio offset for the current output
  ("Audio offset for Phone speaker"), visual offset, both with −/+ steppers in 1 ms steps; Calibrate timing.
- **Chart model**: the model panel above, its licence, the model card, the notices.
- **About**: the playfield preview (a sample chart playing itself), Diagnostics, the version.

Settings are stored with Jetpack DataStore; the audio offset is kept per output device (gameplay.md, Offsets).

### Timing calibration

In the game's dark presentation, two steps on a segmented control (in landscape it sits in the top bar beside the
title, so each step keeps the height it needs). The clicks play from the moment the screen opens until it closes,
through the output play uses.

1. **Sound.** A click every half second. The player taps a large pad on each click, eyes closed if they like. A strip
   shows the last 16 taps from early to late, as the game would judge them with the offset in use now, so they gather
   at the middle once it is right. After 16 taps the screen suggests the audio offset for the output the clicks play on
   (named on the screen) from the median of the last 16, and keeps it current with each tap; Use applies it (a
   `CONFIRM` tick), Start over clears the taps. Before 16 taps the suggestion reads "—" and Use is off. If the sound
   moves to another output, the taps start over and the new output is named.
2. **Picture.** The clicks go on and a note falls onto the line with each one, drawn by the play renderer with the
   player's lane lean, on the clicks' own clock. The player moves the visual offset slider until the notes cross the
   line on the click. In landscape the notes sit beside the slider, so the field keeps its height. There is no tapping
   in this step, so touch latency cannot leak into the visual offset.

The clicks pause when the app leaves the foreground or another app takes audio focus, and go on when it comes back;
while they are paused the pad says "Paused. Tap to go on.", and a tap does so. How the click is timed and why this
separates the offsets is in gameplay.md.

### Diagnostics

The shell's Diagnostics screen, unchanged, now reached from Settings › About with an Up button.

## Visual language

Application chrome and the game presentation are kept apart, as the Swift app's design guidelines ask.

**Chrome** (Library, Make chart, Settings) is Material 3 with stock components: top app bars, lists, cards, sliders,
segmented buttons, text fields, the extended FAB, snackbars. It follows the system's light or dark theme. Its colour
scheme is built from the brand: lavender `#B5B3EF` as primary on dark, a deeper `#5D58A8` on light, rose as tertiary,
plum neutrals (`#130E1A` ground and `#2A2036` containers on dark), assigned to Material's colour roles
([M3 colour roles](https://m3.material.io/styles/color/roles)).

- **Brand colour, not dynamic colour, by default.** Android 12+ can theme apps from the wallpaper
  ([Dynamic colour](https://developer.android.com/develop/ui/views/theming/dynamic-colors)), and the shell did. The
  design uses the brand scheme instead because the play screen is always the brand's plum and coral, and a chrome in
  the wallpaper's greens or blues makes every move into play a jump between two apps. Dynamic colour remains one
  switch in the theme; which to use is a question for the owner.
- **The coral action** (`#F39AAF → #FF708F`, dark ink `#2B0F1E`) marks one thing per screen: the primary action
  (Download model, Make chart, Play, Resume, Play again). Everything else is a Material button. In play, coral is the
  judgement line and song progress only, as in the Swift app.
- **Type** is the system's sans-serif with Material's type scale, and tabular figures wherever numbers change. The
  Swift play screen uses SF Pro Rounded; Android has no system rounded face, and bundling one is not worth it for this
  round.
- **Icons** are Material icons, kept as vector drawables in the app (Material 3 no longer pulls in an icon library).
- **Failures** are always words with a warning symbol, never colour alone; cancelling a picker is not a failure.

**Game presentation** (Play, Pause, Results, Calibration) is always dark plum, whatever the system theme, with light
system bar icons. Its palette is the Swift play screen's, which comes from the brand: ground `#130E1A` with the
diagonal division `#1A1322`, alternating lanes `#1D1627` and `#191220`, rose taps `#EAA0B7`, lavender hold heads and
tails `#B5B3EF`, purple hold bodies `#9562D7`, coral line and progress `#FF879F`, tiers Perfect `#F6F1FB`, Good
`#F3C47E`, Miss `#FF9AA5`. Notes are coloured by kind, not by lane. Panels use the brand's card shape: round corners
with a tighter one at the bottom left.

Contrast of the main pairs (WCAG ratio): text on plum 17.1, muted text on plum 10.5, muted text on a panel 8.5, Good on
plum 11.8, Miss on plum 9.4, dark ink on coral 6.7 to 8.4; in light chrome, text 16.4, secondary text 8.8, primary
5.8, outline 4.2. All text pairs are above 4.5:1
([Android accessibility: colour contrast](https://developer.android.com/guide/topics/ui/accessibility/apps#color-contrast)).

## Motion

- **Chrome** uses Material's motion: Navigation 3's default transitions between screens, the predictive back
  animation, component state changes. Nothing else animates.
- **Game**: motion marks what acted, as on the Swift play screen. A hit lights the line in its lane and a flare rises
  for 200 ms; a miss tints the lane above the line for 360 ms; the judgement word pulses and drifts as the runtime's
  `judgementPresentation` says; lane lights ease as `laneBrightness` says. The count-in after a pause steps once per
  beat.
- **Reduced motion.** With "Remove animations" on, `ValueAnimator.areAnimatorsEnabled()` is false
  ([ValueAnimator](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled())).
  The playfield then draws no flare, the line light only fades, the judgement word takes the runtime's opacity without
  its scale or drift, and the count-in digits only change. Notes still scroll: that is the game, not decoration.

## Haptics

Haptics go through `View.performHapticFeedback`, which honours the player's touch-feedback setting and needs no
`VIBRATE` permission ([Haptic feedback](https://developer.android.com/develop/ui/views/haptics/haptic-feedback)).

- **Chrome**: Material components' own feedback; `CONFIRM` when a chart being made is ready (not when a stored one is
  opened) and when a calibration's or the results' suggested offset is applied, `REJECT` when a chart fails.
- **Play**: none (the owner's decision of 2026-10-31): the actuator answers late, and a late tick reads as a late hit.
- **Calibration**: never while tapping. A tick would be a second cue competing with the click; the pad also turns off
  its click sound.

## Accessibility

- **TalkBack on chrome**: every control has a label (icon buttons included); section titles are headings; sliders
  announce their value ("3.5", "25.0×"); the download progress, the generation phases and the chart result are polite
  live regions; problems are text, not colour.
- **TalkBack in play**: with touch exploration on, a single tap explores instead of touching, so the lanes cannot be
  played. The play screen is one labelled element ("Playfield: four lanes. Touch a lane when its note reaches the
  line."), the HUD values and the Pause button stay reachable. When touch exploration is on, before the game starts
  a dialog says "TalkBack is on: … the lanes need direct touch. Turn TalkBack off to play, or start anyway." with Start
  anyway and Back; the lead-in waits behind it. A dialog is its own window, so TalkBack moves into it at once instead
  of starting on the playfield. Playing with TalkBack on is not a goal of this round.
- **TalkBack in calibration**: the pad is one labelled element ("Tap pad. Tap on the click"); a TalkBack double tap
  is not timed, so it counts as no tap. The strip says where the middle of the taps is ("… 22 ms late").
- **Font size**: chrome follows the system font size up to Android 14's 200 %
  ([non-linear font scaling](https://developer.android.com/about/versions/14/features#non-linear-font-scaling)):
  text wraps, steppers sit under their labels, nothing is truncated to fit. The HUD and the panels scale too; text
  drawn inside the playfield (judgement word, combo) keeps a fixed size, like the Swift playfield, so it never covers
  the lanes, and is drawn under the notes, so it never hides one.
- **Touch targets** are Material's 48 dp minimum
  ([large controls](https://developer.android.com/guide/topics/ui/accessibility/apps#large-controls)); lanes are about
  148 dp wide at the line on a phone in landscape and 99 dp in portrait, and a lane's touch zone is its whole column.
- **Contrast and colour**: above; every tier is named in words as well as coloured.
- **Reduced motion**: above.

## Phone sizes and orientation

- **Compact width first** (360 to 430 dp, phones in portrait). Content is one column; on wider windows it is centred at
  640 dp at most rather than stretched.
- **Play is landscape by default**, the owner's choice, following the convention of Malody's key mode on phones:
  landscape play with the lanes leaning back in perspective, portrait an option (the evidence and the geometry are in
  gameplay.md, The playfield on a phone). Portrait stays a setting because the convention keeps it (Malody added
  portrait play as an option in both its generations), because it gives the longest lanes, and because some players
  hold the phone in one hand or are used to portrait 4K games.
- **The game holds its orientation** from the count-in through pause and the results, with
  `SCREEN_ORIENTATION_USER_LANDSCAPE` or `SCREEN_ORIENTATION_USER_PORTRAIT`, which let the phone take either side of
  that orientation as its sensor and the player's rotation setting allow
  ([`ActivityInfo`](https://developer.android.com/reference/android/content/pm/ActivityInfo#SCREEN_ORIENTATION_USER_LANDSCAPE)).
  Leaving the results (Back to song) releases it and the phone returns to its own orientation. Holding through the
  results means the end of a song, and Play again, never turn the screen under the player's hands.
- **The chrome follows the phone** and reflows in landscape: lists stay one column centred at 640 dp at most and pad for
  a side navigation bar or cutout; Results take two columns. Rotation does not recreate the activity
  (`android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`): Compose recomposes with the new
  size, and the playfield's `SurfaceView` resizes in place instead of being rebuilt as the game turns to landscape.
  Android's guidance recommends handling the change directly in Compose-only apps, and warns that it is not a
  substitute for saving state, which the app still does
  ([configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes)).
- **Large screens**: for apps targeting API 36, Android 16 ignores orientation requests on displays at least 600 dp
  wide ([Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-16)). There the
  playfield takes whatever shape the window has: the landscape layout and lean when it is wider than tall, the
  portrait ones otherwise. (Declaring the app a game with `android:appCategory` would exempt it; that is not needed
  for phones and is not proposed.)
- **Edge to edge**: required for API 36 (same source). Chrome pads for the system bars; the playfield keeps its HUD
  below the display cutout and its key tray above the system's mandatory gesture area (gameplay.md).
- **Split screen and short windows**: losing focus pauses; a window less than 280 dp tall or wide pauses the game and
  says "This window is too small to play in" instead of drawing a squashed playfield; when it grows again, the pause
  panel is there.

## What comes from the Swift app, and what changes

| Taken from the Swift app | Changed for Android, and why |
| --- | --- |
| The loop: model, song, generate with stars, seed and sections, play, results | One back stack from a song to its game; no tabs (one top-level place); Settings as a screen, not reachable during a game |
| Model install from Hugging Face at the pinned tag, `checkPackage`, per-file SHA-256, Range resume, install record, update on start; Choose Folder | Free space checked and reserved with `StorageManager`; files under `noBackupFilesDir`; Choose Folder became Install from files (the owner's decision of 2026-10-29): the picked folder's files are copied into app storage, since the runtime needs a file path and a tree URI is not one; both ways accept only the pinned manifest's SHA-256 |
| Music folders as the library | Songs picked one by one with the system picker: no permission, nothing scanned |
| Generation states, phase names, Stop, the core's message on failure, Use Seed | A state bar at the bottom of a phone screen instead of a two-panel setup; no Session tiles or lane preview; no chart-server engine (out of scope); the last chart kept on disk |
| The play look: brand palette, notes by kind, coral line, hit flash and miss tint, "Ready", combo from 2, Reduce Motion rules | Drawn on a `SurfaceView` canvas instead of Metal; touch lanes instead of key caps with letters; immersive full screen in landscape with leaning lanes (new: the owner's choice, after Malody), portrait as a setting; a lead-in and a count-in after pause; Restart in the pause panel |
| The timing contract: event timestamps, a 100 ms input allowance, frames drawn for their presentation time, offsets applied once | Android's clocks (`AudioTrack` timestamps, `getEventTimeNanos`, `FrameTimeline`), all on `CLOCK_MONOTONIC` (gameplay.md) |
| Audio offset per output device, one visual offset, the scroll-speed scale, judge C, music volume 0.8 | Offsets default to 0 until the phones are measured (the Swift −15 ms was tuned on a Mac); the output keyed by `AudioDeviceInfo` type and name |
| Calibration: clicks every 500 ms after 1 s, median of 16 taps | Two steps; the visual step has no taps |
| Results: accuracy, max combo, misses | Its own screen with all three tiers, a timing chart and the runtime's offset suggestion |
| Settings sections: gameplay, sound and timing, chart model | Playfield section (orientation, lane lean, live preview); About with the playfield preview and Diagnostics |

Not carried over in this round: Listen & Play, recognition, the Chart Inspector, the chart server, key bindings, saving
`.osu` and `.osz`.

## The prototype in the app

The app opens on the Library and runs every screen above on its own stores (`AppStores`): the settings, kept with
DataStore, the audio offset per output, written by Settings, calibration's Use and Results' Use; the song library from
the document picker, with File not found, Find again and Remove; decoding to PCM; the model download and the install from files, their states, the
licence, the model card and the notices; making charts with the runtime (prepare on opening a song, the whole-chart
scheduler, Stop, the model's capabilities, the last chart per song kept); play: the player's chart on the song's sound,
the `AudioTrack` clock, the pauses, the results from the runtime's score, and the playfield: the `SurfaceView` render
thread paced by `postVsyncCallback`, the runtime's `GameplaySession` judging, its `GameplayFeedback` easing lights and
the judgement word, `noteGeometry` placing notes, touches mapped to lanes with their event times; and the calibration:
the click track through the play output, the pad's taps on its clock, the picture step drawn on the same clock.

Two parts play a fixed chart by design: the playfield preview in Settings, a 27-second chart on a clock without sound,
pressed by an autoplay with fixed early and late offsets so every judgement shows ("Sample chart · no sound"), whose
results are the runtime's real score of that game; and the calibration's picture step, a note on every click.

**For review**, a debuggable build opens in any state of any screen from a launch extra
(`adb shell am start -n io.ensomi.android/.MainActivity --es prototype make-chart-made`), on the fakes in
`prototype/Fakes.kt` (sample songs, a fixed model and chart state, settings in memory, a click track without sound and
16 sample taps); `scripts/capture-prototype.sh` drives them all and plays one real game. Nothing in the screens says
"not built yet" any more.
