# Ensomi for Android: Listen & Play

Listen & Play ships in the public APK. It follows music playing in the room; Ensomi plays no audio of its own on this
route. The guide is available beside Add songs once the chart model is ready, with an empty library or with songs.

Ensomi never operates another app. It uses no Accessibility service, reads no other app's screen, and makes no taps for the person. The person recognises the song, downloads it and shares it to Ensomi themselves; Ensomi explains each step, then waits for the share.

## The guide and the share

The guide reads the current model, microphone and music access state whenever it resumes. It explains that the
microphone lines up with the music on this device and is neither saved nor uploaded, and that music access finds the
downloaded MP3. Each missing permission has an Allow button using Android’s permission dialog; app settings are
available if permission was blocked. A share with a missing model points back to the Library. After fixing a missing
prerequisite, the person shares the song again, so the audio wait begins with that new share.

There is one launch path and four steps, in English and Simplified Chinese:

1. **Open NetEase Cloud Music.** The person taps the guide’s button. It calls `getLaunchIntentForPackage` for
   `com.netease.cloudmusic`, with `FLAG_ACTIVITY_NEW_TASK`, preserving the page already open. Nothing else launches it.
2. **Recognise the song.** The person uses NetEase’s 听歌识曲 while the music keeps playing.
3. **Finish downloading the MP3.** The person downloads the recognised song as MP3 and waits for it to finish.
4. **Share to Ensomi.** The person taps Share and chooses Ensomi. The guide says that a share shows what to do next;
   keep the music playing and stay in Ensomi while it lines up.

NetEase’s recognition results and download rights belong to its own UI. Ensomi waits for the person’s share. If NetEase
is absent or cannot open, the guide explains that plainly. It does not attempt another launch path.

`ShareActivity` is exported and accepts `ACTION_SEND`, `text/plain`. It treats every share as untrusted: empty text,
text with no song, a missing model, or either permission missing leads to a plain guide message, without starting
file work or capture. Parsing is bounded to 8,192 characters. A ready song share starts the session; a different song
replaces it. A repeat share of the same song retains accepted audio, chart and sync, and does not restart the wait.

Text can name a song in `分享Artist的单曲《Title》` or supply a public `music.163.com` / `y.music.163.com` song URL.
Public `163cn.tv` and `163.tn` short links are resolved with at most three HTTP redirects, each with three-second
connect/read timeouts, over HTTPS and only among those allowed hosts. No cookies or account data are supplied.
The lookup is optional when the text names the song. Clipboard paste on the session screen follows the same checks.

## Code and builds

`app/src/recognition/` supplies Kotlin, resources and the manifest to debug, checks and release. Release keep rules in
`app/src/release/keepRules` retain JNA; the runtime AAR supplies UniFFI keep rules including the sync bindings.
The required AAR SHA-256 is `16c647da00a050f3cd13a33b7a1124f467c152819b36139a6186cd195f220880`.
JVM tests in `app/src/testDebug/` cover prerequisites, share handoff and parsing, matching, waits, growth, refusal,
chart/sync ordering, restoration and joining.

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
3.5 stars, ambient offset 0, audio idle wait 10 seconds and sync limit three minutes. Debug/checks builds alone expose
replay, diagnostic details and options. All builds use the same limited storage permissions.

## Finding the MP3

Three sources run concurrently. MediaStore with `READ_MEDIA_AUDIO` is the supported route. `FileObserver` on
`Download/netease/cloudmusic/Music/` and folder listing are attempted and may see less without all-files access.
A five-second poll checks both the folder and MediaStore. No build requests all-files access. Refreshing a candidate
checks MediaStore first, preserving its content URI even when direct filesystem access is unavailable. A newly published
URI retries an unreadable folder candidate even without size growth; a row added during the session is considered even
when the file preserves an older modification time. A raw shell push may need an explicit media scan before it is readable.

| Evidence | Meaning |
| --- | --- |
| `CLOSE_WRITE` or `MOVED_TO` | The folder observer saw a completed write or move |
| Non-pending MediaStore row | MediaStore reports the audio as ready |
| Pending row, create or modify | Possible growth, never permission to decode an unfinished file |
| Folder poll only | Requires eight seconds quiet; stronger evidence requires one second |

The candidate must be MP3, complete and quiet, match the song, and decode to its declared length (within the existing
length tolerance). Temporary suffixes cannot be decoded. Tags and filenames match title/artist; a previously known
song ID may identify the same file. Older files require identity. New uncertain files may be offered for the person
to choose. Matching folds width, case, punctuation and spacing, and removes only complete, recognised distribution
credits; Live, Remix, Instrumental, TV Size and unknown parenthesised text stay distinct.

**The 10-second wait starts at the share.** It is an idle limit. A first nonempty matching MP3 candidate and each observed
size increase grant a fresh ten seconds, including a visible pending MediaStore row or a recognisable temporary MP3
name. Repeated sightings, timestamp-only changes, unrelated files and protected-file growth cannot extend it. A probe
already decoding can finish. The one-second tick stops the session at or after the idle deadline. A frozen process
checks when Android runs it again; restoration retains the same clock.

A person who shares while the download grows gets this allowance where a source can see that growth. When no source
can see it, the wait ends with the ordinary message, in both languages:

> No usable audio arrived, so the session stopped. Only MP3 works. Let the download finish before sharing. A song NetEase saved in its protected format cannot be used.
>
> 没有等到可用的音频，会话已停止。仅支持 MP3。请等下载完成后再分享。网易云音乐保存为受保护格式的歌曲无法使用。

`.ncm` files are never opened, decoded, converted or read. A matching filename, if visible, records the protected
format and gives a more specific refusal at timeout; a later MP3 can still proceed before that deadline. Such files
will usually be invisible with the public permissions, so the ordinary message covers the protected-format case too.
Timeout records `failure`, `abort` and `session.end` with `audio_wait_timeout`, cancels listeners, file and chart work,
and releases capture and alignment. The explanation remains on screen with Back and a route to the guide.

## Chart, capture and joining

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
a share before setup, an invalid share, the ten-second no-file stop, an existing matching MP3 and an MP3 arriving after
the share. Trace `watch.seen` identifies each source; a shell push is not evidence of NetEase writing a file.

A muted emulator can establish layout, permission flow, file discovery, chart generation and microphone capture startup.
It cannot establish real room alignment, latency, the NetEase share sheet or file visibility on a physical phone. Those
remain device checks. No game should join silence just to make an emulator check pass.
