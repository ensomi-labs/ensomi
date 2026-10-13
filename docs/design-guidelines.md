---
commit: 353ad2d
---

# Ensomi design guidelines

Use Apple's [Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines)
when creating or changing user-facing screens, navigation, settings, controls,
feedback, and accessibility. These are Ensomi's UI/UX conventions for contributors
and agents, not a claim that every existing screen has passed an accessibility
audit. Service, timing, and scoring contracts belong in [architecture](architecture.md).

## Platform conventions

| Area | Ensomi convention |
| --- | --- |
| Navigation | The macOS workbench uses a sidebar for Library and Play. iOS uses tabs with navigation titles. Preserve destination identity and access when content is empty. |
| Settings | macOS uses the SwiftUI `Settings` scene, the standard app-menu command and Command-comma; toolbar access uses `SettingsLink`. iOS presents a navigation-based sheet with Done. On macOS the last section, Developer, opens the Chart Inspector in a window of its own. |
| Preferences | Use grouped forms, system labels, pickers, text fields, and steppers. Keep units and valid ranges clear. Settings share the existing play model. Sound and timing settings (music volume, audio and visual offset, calibration) are disabled while a game is in progress, with a footer saying they can change when it ends. The audio offset and the calibration screen name the output device the offset is saved for. |
| Library | Music folders and scan status are primary. Empty content offers Add Music Folder. Manual resolution and recording controls remain available in Library Tools. Removing a library folder does not delete its audio files. |
| Feedback | Describe failures in text with a symbol. Do not use color alone to distinguish error, selection, readiness, or key-capture state. Treat picker cancellation as cancellation, not failure. |

These choices apply Apple's guidance on [sidebars](https://developer.apple.com/design/human-interface-guidelines/sidebars),
[settings](https://developer.apple.com/design/human-interface-guidelines/settings),
and [accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility).
Prefer system components to imitating their appearance with custom drawing.

## Gameplay and custom presentation

The Play experience, the Chart Inspector and timing calibration retain their dark game
presentation, lane rendering, and timing-specific input. Custom styling is appropriate when it
helps people read the chart or understand game feedback. It must not replace
standard window controls, hide settings, or change scoring and capture behavior.
Library and ordinary settings use system appearance and semantic colors.

The play screen takes its look from the brand's dark plum theme, with one palette
(`Mania4KPlayPalette`) for the Metal playfield and the SwiftUI over it, and SF Pro
Rounded for its text. Notes are coloured by kind, not by lane: rose taps, lavender
hold heads and tails, purple hold bodies. The coral action colour marks only the
judgement line, song progress and the primary action. Hit feedback follows the
judgement events the core reports, and each tier is named in words as well as
coloured. With Reduce Motion, feedback fades without growing, pulsing or drifting.
The choices and their reasons are in [gameplay look](gameplay-look.md).

The Play setup offers Generate a Chart; on macOS, Listen & Play has its own window, opened
from the Live Demo toolbar button. Ensomi is not made for playing existing charts, so no
mode loads a song with a chart to play it.

The Chart Inspector (macOS, Settings → Developer) draws an existing chart against its
song for checking it. It uses the play screen's stage, playfield and palette, without
the HUD, key labels, judgement text or pause dimming, because nothing is judged: notes
pass the judgement line and slip under the key caps. The header shows the title, artist,
difficulty and note count, a difficulty picker when the `.osz` has several readable
ones, and Open… (Command-O), which takes one `.osz` or an `.osu` together with its audio
file. With nothing open, a panel says what to open. An `.osz` with several difficulties
lists them; those the gameplay core refuses show its reason and cannot be chosen. A
refused chart or an unreadable file is described in text with a symbol. The transport
bar holds, in order, previous note (Option-Left Arrow), step back (Left Arrow), play and
pause as the primary action (Space), step forward (Right Arrow) and next note
(Option-Right Arrow); the position and the length in `m:ss.mmm` around a scrubber; a
playback-rate menu from 0.5× to 1.5×, whose pitch follows the rate; and a scroll-speed
stepper that changes the same setting play uses. A note step puts that note's start on
the judgement line. A line names the audio offset with its output device, the visual
offset and the volume taken from Settings; they change only there. Dragging the
scrubber pauses playback and resumes it on release. Hiding or closing the window
pauses.

Use descriptive action and mode names. Keep backend implementation details in
supporting explanations or tools unless people need them to make a decision.
When a custom interaction is necessary, preserve keyboard focus, accessible
names and values, and an explicit way to cancel or leave it. Record a new lasting
exception here with its user benefit and affected surface.

Generate a Chart opens with a segmented choice between generating on this device and
at the chart server, and while this device has no model it shows the model controls
(download with progress and cancel, resume after a stopped download, or choose a folder)
before anything else; the same controls sit in a Chart model section of Settings, followed
there by the licence the model's package gives, with a link to the model card, and buttons that
open the notice texts of the model (BeatThis's licence) and of ONNX Runtime in a sheet. Below the
song's star target come an optional Seed field (blank is a new chart each time) and a
Sections list. Each section uses start and end fields in `mm:ss`, a capability-backed kind
picker, and either a strength slider from 0 to 1 or a star target with the song's range.
Experimental kinds are labelled; controls marked not working are hidden. Show validation by
the field or list before enabling generation. Generate is the primary action. During
generation the panel lists the five phases (done, under way, to come) with Stop, and the
Session panel shows the current phase under the engine's name. A stop says no chart was
made; a failure says the chart could not be made above the service's or core's message.
A finished chart shows its notes and the seed that makes it again (Use Seed puts it in the
field), then Play as the primary action and Save .osu…, Save .osz… and Generate Again; the
Session panel shows song, stars, seed, sections, notes and model. The chart and the chosen
mode stay when play ends. Playback actions stay on one line and move below the statistics
at narrow widths.

The setup screens use the brand's plum theme as the web app names it: `--bg` and `--bg-2`
divided on the diagonal, `--surface` panels, `--surface-2` controls, `--line` borders,
lavender for icons and secondary actions, `--ok`, `--warn` and `--no` for status, and the
coral action gradient only for the primary button. There is no grid, and the lane preview
colours notes by kind as the play screen does.

## Layout and accessibility

Follow Apple's [layout guidance](https://developer.apple.com/design/human-interface-guidelines/layout).
Adapt to available width, not only device type. Setup panels stack in narrow
windows and at accessibility text sizes. Use semantic text styles and allow
labels to wrap. Do not shrink essential text to make a fixed layout fit.

Provide descriptive accessibility labels for icon-only actions and values for
custom controls. Keep standard controls keyboard reachable; key-binding capture
must end on assignment or Escape. Use native controls' platform sizing and leave
adequate separation between actions. Check contrast and focus visibility in
both light and dark appearances where those appearances are supported.

## Workflow for interface changes

1. Read this document and the current Apple HIG sections relevant to the affected
   interaction. Separate Apple's guidance from Ensomi-specific design choices.
2. Check `project.yml` deployment targets before adopting an API. The baseline is
   macOS 14 and iOS 17; newer SDK availability alone does not justify raising it.
3. Preserve model ownership, persisted settings, file access, capture lifecycle,
   and game timing while changing presentation.
4. Build affected platforms and run focused tests for behavior changes. Inspect
   the changed screens at narrow and normal widths, enlarged text on iOS, and
   supported appearances. Check keyboard navigation, dismissal, accessible
   labels, and affected empty/error states. Report checks that were unavailable.
5. Update this document when a durable UI/UX convention changes. Keep local
   screenshots, run logs, and temporary verification artifacts out of committed
   documentation.
