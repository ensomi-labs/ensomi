---
commit: 8e3de3b
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
| Settings | macOS uses the SwiftUI `Settings` scene, the standard app-menu command and Command-comma; toolbar access uses `SettingsLink`. iOS presents a navigation-based sheet with Done. |
| Preferences | Use grouped forms, system labels, pickers, text fields, and steppers. Keep units and valid ranges clear. Settings share the existing play model. |
| Library | Music folders and scan status are primary. Empty content offers Add Music Folder. Manual resolution and recording controls remain available in Library Tools. Removing a library folder does not delete its audio files. |
| Feedback | Describe failures in text with a symbol. Do not use color alone to distinguish error, selection, readiness, or key-capture state. Treat picker cancellation as cancellation, not failure. |

These choices apply Apple's guidance on [sidebars](https://developer.apple.com/design/human-interface-guidelines/sidebars),
[settings](https://developer.apple.com/design/human-interface-guidelines/settings),
and [accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility).
Prefer system components to imitating their appearance with custom drawing.

## Gameplay and custom presentation

The Play experience and timing calibration retain their dark game presentation,
lane rendering, and timing-specific input. Custom styling is appropriate when it
helps people read the chart or understand game feedback. It must not replace
standard window controls, hide settings, or change scoring and capture behavior.
Library and ordinary settings use system appearance and semantic colors.

Use descriptive action and mode names. Keep backend implementation details in
supporting explanations or tools unless people need them to make a decision.
When a custom interaction is necessary, preserve keyboard focus, accessible
names and values, and an explicit way to cancel or leave it. Record a new lasting
exception here with its user benefit and affected surface.

Generate a Chart places a Sections list below the song's star target. Each section
uses start and end fields in `mm:ss`, a capability-backed kind picker, and either
a strength slider from 0 to 1 or a star target with the song's range. Experimental
kinds are labelled; controls marked not working are hidden. Show validation by
the list before enabling generation. During generation, the Session and Backend
panels show the current phase; failures retain the service's message. Playback
actions stay on one line and move below the statistics at narrow widths.

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
