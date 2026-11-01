# Ensomi for Android: Listen & Play

Listen & Play ships in the public APK. It follows music playing in the room; Ensomi plays no audio of its own on this
route. The guide is available beside Add songs once the chart model is ready, with an empty library or with songs.

Ensomi never operates another app. It uses no Accessibility service, reads no other app's screen, and makes no taps for the person. The person recognises the song, downloads it and shares it to Ensomi themselves; Ensomi explains each step, then waits for the share.

## The guide and the share

The guide reads the current model, microphone and music access state whenever it resumes. It explains that the
microphone lines up with the music on this device and is neither saved nor uploaded, and that music access finds the
downloaded MP3. Each missing permission has an Allow button using Android’s permission dialog; app settings are
available if permission was blocked. A share with a missing model points back to the Library. After fixing a missing
prerequisite, the person shares the song again, so the new session gets a fresh audio wait once indexing is ready.

There is one launch path and four steps, in English and Simplified Chinese:

1. **Open NetEase Cloud Music.** The person taps the guide’s button. It calls `getLaunchIntentForPackage` for
   `com.netease.cloudmusic`, with `FLAG_ACTIVITY_NEW_TASK`, preserving the page already open. Nothing else launches it.
2. **Recognise the song.** The person uses NetEase’s 听歌识曲 while the music keeps playing. If NetEase shows several results, pick the one you have already downloaded.
3. **Finish downloading the MP3.** The person downloads the recognised song as MP3 and waits for it to finish. If NetEase says it is already downloaded, go back and Share.
4. **Share to Ensomi.** The person taps Share and chooses Ensomi. The guide says that a share shows what to do next;
   keep the music playing and stay in Ensomi while it lines up.

NetEase’s recognition results and download rights belong to its own UI. Ensomi waits for the person’s share. If NetEase
is absent or cannot open, the guide explains that plainly. It does not attempt another launch path.

`ShareActivity` is exported and accepts `ACTION_SEND`, `text/plain`. Its empty task affinity keeps this temporary
entry out of the main task. It starts MainActivity with the launcher’s MAIN/LAUNCHER intent; MainActivity uses singleTask so a launcher tap after a cold share cannot add a second instance. Navigation requests are collected only while the activity is started.
It treats every share as untrusted. Empty shares are ignored. Text with no song, a missing model, or either permission
missing leads to a plain guide message without starting file work or capture. A non-song share during a live session or game only shows a brief message and preserves all work. The share body takes precedence over the subject so a duplicated subject cannot garble the song name. Parsing is bounded to 8,192 characters. A ready song share starts the session; a different song
replaces it. Listen & Play returns to a live session. Show the guide is available only after it ends; Back asks whether to stop an active session. After process death, a snapshot stays dormant until the person opens Listen & Play, and expired waits and interrupted games are discarded with the guide’s restore notice, rather than a session failure. A new share always starts a fresh wait after process death. A restore notice never opens a screen by itself and is kept when the guide opens; successfully resuming a saved session consumes it. Opening the guide clears old setup and non-song notices, retaining only a restore notice. The guide renders immediately while library work stops; the session displays that wait without asking someone already in Ensomi to return. A Stop confirmation belongs to its session and disappears when a new share replaces it. A repeat share of the same song normally retains accepted audio, chart and sync, and does not restart the wait. After failed alignment, it retries Audio and can find another take.

Text can name a song in `分享Artist的单曲《Title》` or supply a public `music.163.com` / `y.music.163.com` song URL.
Public `163cn.tv` and `163.tn` short links are resolved with at most three HTTP redirects, each with three-second
connect/read timeouts, over HTTPS and only among those allowed hosts. No cookies or account data are supplied.
The lookup is optional when the text names the song. Clipboard paste on the session screen follows the same checks.

## Code and builds

`app/src/recognition/` supplies Kotlin, resources and the manifest to debug, checks and release. Release keep rules in
`app/src/release/keepRules` retain JNA; the runtime AAR supplies UniFFI keep rules including the sync bindings.
The required AAR SHA-256 is `16c647da00a050f3cd13a33b7a1124f467c152819b36139a6186cd195f220880`.
JVM tests in `app/src/testDebug/` cover prerequisites, share handoff and parsing, matching, waits, growth, refusal,
chart/sync ordering, restoration and joining. Device tests in `app/src/androidTest/` exercise the real controller’s share routing, blank SEND guard, one-use guide baseline, same-file retry and dormant restoration; an opt-in decoder check uses generated headerless MP3 fixtures.

| File | Responsibility |
| --- | --- |
| `RecognitionGuide.kt`, `GuideState.kt` | Live prerequisites and the four steps; share admission |
| `ShareActivity.kt`, `SharedSong.kt` | Exported share entry and bounded text parsing |
| `Session.kt` | Plain state and rules, driven by share, file, chart, capture and game observations |
| `MediaListening.kt`, `Candidates.kt` | File discovery and matching |
| `SessionAudio.kt` | Complete decode, hashing and the app’s chart path |
| `Capture.kt`, `SyncPort.kt` | Microphone blocks into the local runtime aligner |
| `AmbientPlayClock.kt` | The captured anchor, game clock and safe join point |
| `RecognitionController.kt`, `RecognitionScreen.kt` | Session work, progress and next steps |
| `SessionStorage.kt`, `SessionTrace.kt` | Local snapshots, bounded history and traces |

Release needs no launch extras, shell commands or options file. Defaults are microphone capture, autoplay off,
3.5 stars, ambient offset 0, audio explanation at 10 seconds, idle stop at 15 seconds and sync limit three minutes. Debug/checks builds alone expose
replay, diagnostic details and options, including raw stage details, song IDs and “Stopped at”. The index row says “Check music files on this device”; Done means indexing finished. Audio ready means a usable file was found. All builds use the same limited storage permissions.

## Finding the MP3

Three sources run concurrently. MediaStore with `READ_MEDIA_AUDIO` is the supported route. `FileObserver` on
`Download/netease/cloudmusic/Music/` and folder listing are attempted and may see less without all-files access.
A five-second poll checks both the folder and MediaStore. No build requests all-files access. Refreshing a candidate
checks MediaStore first, preserving its content URI even when direct filesystem access is unavailable. A newly published
URI retries an unreadable folder candidate even without size growth; a row added during the session is considered even
when the file preserves an older modification time. Every index row is compared before the first probe; eligible files are ranked by match strength, then newest modification time. A remembered path is used only for an unnamed ID share, so it cannot override newer named evidence. Retry from Audio (also a same-song re-share after failed alignment) forgets that ID mapping and ranks the rejected file after other usable candidates, allowing another take while retaining the only file as a fallback. This applies to failed alignment and reference building alike. It also invalidates outstanding alignment work. A raw shell push may need an explicit media scan before it is readable.

| Evidence | Meaning |
| --- | --- |
| `CLOSE_WRITE` or `MOVED_TO` | The folder observer saw a completed write or move |
| Non-pending MediaStore row | MediaStore reports the audio as ready |
| Pending row, create or modify | Possible growth, never permission to decode an unfinished file |
| Folder poll only | Requires eight seconds quiet; stronger evidence requires one second |

The candidate must be MP3, complete and quiet, match the song, and decode to its declared length (within the existing
length tolerance). Temporary suffixes cannot be decoded. Tags and filenames match title/artist; a previously known
song ID may identify the same file. Older files with title and artist (or a remembered ID) can match automatically. An older title-only match is offered under “Other MP3 files found”; Use this file is required. Only a title-only offer awaiting a choice suspends the idle timeout, for at most three minutes from the first offer; then the ordinary no-audio message stops the session. Unrelated and truncated held files renew nothing and suspend nothing. The offer deadline survives restoration. New uncertain files may also be listed. A title-only offer has a plain prompt at the top of the screen to choose Use this file. Choosing removes it from the offers; a decode failure explains that the selected file could not be read, and the ordinary idle stop can run again. Resuming refreshes held paths missing from the index and removes deleted files. The session’s file timeline starts when the guide was opened within the preceding 30 minutes, otherwise at the share. The first session that takes that baseline consumes it, including its stored copy; ending any session clears it. A later bare link cannot reuse the previous download window. Stage durations, capture limits and restoration age still use the live share clock, so time spent following the guide cannot consume them. The unused song-duration field and unreachable duration-mismatch verdict are removed; decoding still compares against file metadata. A headerless partial MP3 whose metadata describes only the surviving frames cannot reliably be distinguished from a short complete recording. Matching folds width, case, punctuation and spacing, and removes only complete, recognised distribution
credits, including a trailing `(原曲《…》)` (also full-width brackets). Nested 《》 remain intact in the share parser; Live, Remix, Instrumental, TV Size, Long Ver. and unknown parenthesised text stay distinct. For an untagged file (including MediaStore’s `<unknown>` artist), a matching title and all shared artists appearing in its filename’s artist part also match automatically, in any order. One-character normalised artists require a whole token; longer names may be joined by spaces.

**The wait starts once the first index is ready and a share names the song.** At ten idle seconds, the session shows the no-audio explanation below and keeps waiting. At fifteen idle seconds, it stops with the same explanation. A first nonempty matching MP3 candidate and each observed
size increase renew both marks, including a visible pending MediaStore row or a recognisable temporary MP3
name. Repeated sightings, timestamp-only changes, unrelated files and protected-file growth cannot extend it. A probe
already decoding can finish. The one-second tick shows the message and stops the session at or after their respective idle marks. A usable MP3 arriving before the stop proceeds normally and clears the message. A frozen process
checks when Android runs it again; an explicitly resumed snapshot retains the same clock but cannot time out until its fresh index finishes. Re-sharing after process death starts a fresh session and re-arms its wait after indexing.

A person who shares while the download grows gets this allowance where a source can see that growth. When no source
can see it, the explanation appears at ten seconds and remains after the stop at fifteen seconds, in both languages:

> No usable audio arrived. Only MP3 works. Let the download finish before sharing. A song NetEase saved in its protected format cannot be used.
>
> 没有等到可用的音频。仅支持 MP3。请等下载完成后再分享。网易云音乐保存为受保护格式的歌曲无法使用。

`.ncm` files are never opened, decoded, converted or read. A matching filename, if visible, records the protected
format and gives a more specific refusal at timeout; a later MP3 can still proceed before that deadline. Such files
will usually be invisible with the public permissions, so the ordinary message covers the protected-format case too.
A bare link with no known file or new audio instead asks for a NetEase song share containing its name and artist.

Timeout records `failure`, `abort` and `session.end` with `audio_wait_timeout`, cancels listeners, file and chart work,
and releases capture and alignment. The explanation remains on screen with Back and a route to the guide; the screen is no longer kept awake.

## Chart, capture and joining

An out-of-memory failure during decoding (including the chart maker’s own decode after restoration) or reference building stops the session with a plain message and clears its snapshot. Trace creation, writes and close tolerate disk errors. Candidate refresh queries only the requested MediaStore path. A delayed game plan is published only if its session and retry generation still match.

A complete MP3 is decoded once to mono PCM and hashed. The chart and alignment reference reuse that PCM when possible.
Chart generation and reference building proceed independently; an existing chart with the same hash may be reused.
Capture starts only while the session is in front. `AudioRecord` supplies 48 kHz mono blocks from `UNPROCESSED` where
available, otherwise `VOICE_RECOGNITION`. Timestamps use the monotonic audio timestamp, with read-time fallback.
The buffers are passed directly to the local runtime aligner; they are not written to disk or sent over the network.
Only level and timing summaries enter the trace. Backgrounding stops capture; returning starts a new sync run.

Only the runtime’s final lock can start play, after the chart is also ready. Provisional positions never start a game.
Silence prompts the person after 15 seconds; three minutes without a final lock gives a plain failure. The clock is:

```text
songMs(t) = referenceMs + (t − captureNanos) / 1e6
```

The microphone stops at final lock. `JOIN_LEAD_MS` is **2,000 ms**, or the scroll time if longer. The join moves to a
point with no open hold; earlier notes are skipped. Pause holds the game while room music continues; Resume catches
up with that music. There is no Restart on this route. A room song that has ended cannot be joined.

## Local storage and network

Release writes the current session snapshot and a bounded song-ID-to-file map (256 entries) in private
`files/recognition/`; a snapshot holds at most 64 uncertain file choices. It retains at most ten traces of at most 1 MiB each there. Traces contain shared text, song/file
identity, timing, stages and outcomes, but no microphone recording. Ended snapshots are removed. These files are local,
and backup/device transfer is disabled. Debug/checks traces and options instead use the app’s external files directory.
Release never reads that external options file and never writes options or replay audio.

Recognition charts use the app’s chart store, with at most twenty recognition-created charts retained; Library charts
remain controlled by Library removal. The shared prepared-audio cache already has a 512 MiB limit. The pinned model is
stored locally. No downloaded source song is copied by recognition.

Only the optional public short-link lookup sends a shared URL to its public host, and model download requests fetch
the pinned package. Microphone samples, local songs, charts, session snapshots and traces are not uploaded.

## Verification and remaining device questions

The release must be tested with R8 enabled on arm64. Check home with and without songs, the guide and permission dialogs,
a share before setup, an invalid share, the ten-second no-file message and fifteen-second stop, an existing matching MP3 and an MP3 arriving after
the share. Trace `watch.seen` identifies each source; a shell push is not evidence of NetEase writing a file.

A muted emulator can establish layout, permission flow, file discovery, chart generation and microphone capture startup.
It cannot establish real room alignment, latency, the NetEase share sheet or file visibility on a physical phone. Those
remain device checks. No game should join silence just to make an emulator check pass.
