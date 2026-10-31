# Ensomi for Android: the recognition session

A test path, in debug and checks builds only, from music playing in a room to a game joined at the position the music
has reached. Nothing here plays audio: the room's music goes on, and the game follows it. The stages are the ones the
session's screen shows:

1. **Listen.** The session registers its media listening and builds a local audio index before anything else.
2. **Open.** NetEase Cloud Music's song recognition (听歌识曲) is started: Path A with the launcher intent, Path B with
   the deep link `orpheus://identify`, Auto with B and then A if the link does not start. Both are ordinary
   `startActivity` calls.
3. **Result.** The host shows a result.
4. **Download.** The song's download action is used; the host does not download a song twice.
5. **Share.** The song is shared to Ensomi with `ACTION_SEND`. This is also how the person lands back in Ensomi.
6. **Audio.** A complete, decodable file of the song is found, already there or arriving.
7. **Chart.** The whole chart is made from that file, or a stored one is used.
8. **Sync.** The microphone is aligned against the song until a final lock.
9. **Play.** The game joins the music.

Steps 2 to 5 happen in another app, and the session does not depend on how: it goes on from what it observes, a share
arriving, a file appearing and decoding. `HostDriver` is the interface for those steps. The session says which is due,
`HostReports` brings back anything seen, and a step nothing else has done goes to the person, in Chinese and English,
large enough to read at arm's length. `ManualHostDriver`, the only one in this repository, hands every step to the person.

## Where it lives

`app/src/recognition/` is added to the debug and checks builds' source sets: the code, its text, the share target and the
permissions. A release build compiles `app/src/release/`'s empty `RecognitionEntry` instead, so it has none of this, and
its merged manifest is the same as without it. The app's main code knows only that entry, one navigation key and
`AmbientGame`, a game with a clock of its own and no sound. JVM tests are in `app/src/testDebug/`.

| File | Holds |
| --- | --- |
| `Session.kt` | `SessionMachine`: the session as plain state and rules, what happens in and what to do out |
| `Candidates.kt` | The rules for whether a file is the song; matching by tags and names |
| `SharedSong.kt` | Reading a share's text |
| `HostDriver.kt` | The seam, and `ManualHostDriver` |
| `MediaListening.kt` | The three ways of seeing a file arrive |
| `SessionAudio.kt` | Decoding a candidate to the end; the chart on the app's own path |
| `SyncPort.kt` | The port to the runtime's aligner |
| `Capture.kt` | The microphone, and the WAV replay that stands in for it |
| `AmbientPlayClock.kt` | The clock, and the join point |
| `RecognitionController.kt` | Runs a session: feeds the machine, carries out what it asks |
| `RecognitionScreen.kt` | The test screen |
| `SessionTrace.kt`, `SessionStorage.kt` | The trace, the snapshot, the options |

## An event-driven session

`SessionMachine` holds facts, not a position in a sequence. Each event adds what it says, and each fact moves on
whatever it unblocks: a file before the share waits for the song to be known; a share before the file waits for the
file; a share at any time closes the host's stages, as "not observed" if nothing reported them. A failure stops nothing.
It is shown with the stage it happened at and the stage to try again from, and the session goes on listening, so what
arrives late still counts. Any stage that has started can be tried again from the list on the screen, failed or not. A session is over only when its game ends or it is stopped.

A share with no session starts one, without opening the host. A share of a different song starts a new session. The text
may be any of the host's forms: `分享xxx的单曲《xxx》: https://… (来自@网易云音乐)` with a link to `music.163.com/song?id=`,
`music.163.com/#/song?id=`, `music.163.com/song/<id>`, `y.music.163.com/m/song?id=` or `orpheus://song/`, or a public
short link, which is looked up by its plain HTTP redirect with a three-second timeout. That lookup is only a help: the
title and artist in the text match files without it. If the sharing app will not offer Ensomi, Paste a song link does the
same from the clipboard.

## Finding the audio

NetEase keeps downloads in `Download/netease/cloudmusic/Music/`, named like `Artist - Title.mp3`. Nobody could say in
advance which way of seeing a file would work on the test phone, so three run at once and whichever fires first counts:

| Source | Needs | Evidence it gives |
| --- | --- | --- |
| `FileObserver` on the folder | `MANAGE_EXTERNAL_STORAGE`, granted with `adb shell appops`; whether it fires through the FUSE layer is a question for the phone | `CLOSE_WRITE`, `MOVED_TO`; `CREATE` and `MODIFY` only say it is being written |
| MediaStore's content observer | `READ_MEDIA_AUDIO`; it sees files in any folder | A row no longer pending; a row still pending only goes to the trace |
| A poll of both, every five seconds | The same | A size and a time, which prove less |

A candidate is judged in this order, the cheap and certain refusals first:

| Rule | Verdict when it fails |
| --- | --- |
| Not a temporary name (`.tmp`, `.part`, a leading dot and so on) | `TEMPORARY` |
| An audio file the app decodes | `NOT_AUDIO` |
| Its writer finished: `CLOSE_WRITE`, `MOVED_TO`, or a row no longer pending | `UNFINISHED` |
| The session knows its song | `NO_SONG_YET`: the file waits |
| It matches, or is new and could be it | `BEFORE_SESSION` for an older file, `NO_MATCH` for a new one |
| Quiet for a second, or for eight when only the poll has seen it | `NOT_QUIET` |
| A decoder opens it and reads it to the end | `UNDECODABLE` |
| It decodes as long as it says it is | `TRUNCATED`: it is still arriving |
| Its length agrees with the song's within two seconds, where that is known | `DURATION_DIFFERS` |

A file is matched by its tags and by its name read both ways round: title and artist (`TITLE_ARTIST`), title alone
(`TITLE_ONLY`), and the song id through `known-songs.json`, the record of which file each id was found in before, which
is the only way this app ties an id to a file. An older file is accepted only by identity, so a repeat of a song needs no
download. A new file that matches nothing is held, and the screen offers it: Use this file is the person's word, and it
also sets the length rules aside, since a file's declared length can be a guess.

**A `.ncm` is refused.** It is the host's protected format. Such a file is never opened, decoded, converted or read for
audio; only its name, size and time are looked at. A `.ncm` that is the song is a stated refusal in the session ("this
track is protected; download a purchased MP3 instead"), not a route, and an MP3 of the song arriving afterwards goes on.

## From the audio to the game

**Chart.** `RuntimeChartMaker` makes it, kept in the app's `ChartStore`. A chart made from the same bytes is used as it
is, whether by a session or from the Library. On a Redmi K Pad a 3 to 5 minute song takes 60 to 130 s, so the music moves
on meanwhile. That is expected: Sync runs after the chart exists, and the join uses wherever the music has got to.

**Sync.** Android gives the microphone to the app on top, so capture starts only with Ensomi in front, and a person
sent away is asked to come back. `AudioRecord` runs at 48 kHz mono in float, from `UNPROCESSED` where the device has it,
else `VOICE_RECOGNITION`. A block's time comes from `AudioRecord.getTimestamp` on the monotonic clock, not from when
`read()` returned, which is used only while no timestamp is there, and the trace says which. The runtime does the
alignment (`SyncReference`, `SyncSession`), against the mono PCM the chart was made from, so a reference time is a chart
time and the file's MP3 lead needs no adding. Blocks are pushed from the capture thread, as the runtime asks, until the
first final lock, as in the Swift app.

There is no play without a final lock: the runtime's `isFinal` is the only gate. A lock takes at least 3.6 s of audio,
and one real recording took about 26 s, so the limit is generous: after three minutes of listening without one, the
session says so and does not join at a guess, since the song's recording may not be the room's, a live version for
example. A wrong song gave no final lock in 24 of 24 of the runtime's takes. A provisional position counts for nothing,
whatever its confidence; the screen shows the state, the reason no position is held and the agreement as they change.
When capture stops because Ensomi left the front, it starts again with a new `SyncSession` when Ensomi returns, since the
audio does not continue what came before.

**The clock.** `AmbientPlayClock` implements the app's `PlayClock`:

```text
songMs(t) = referenceMs + (t − captureNanos) / 1e6
```

The anchor is the moment the locked audio was captured, not the moment the lock was read or the clock was made. Like the
Swift app's, it is set once and never follows the music again, so the microphone stops at the lock. It stays on
`CLOCK_MONOTONIC`, the play path's one clock (gameplay.md), so a touch's event time needs no conversion; the trace records
`elapsedRealtimeNanos` on every event and both clocks together where they meet. Like the audio clock it never goes back:
a pause holds the furthest time shown.

**The join.** The game joins at the first time at least four seconds, or a scroll time if that is longer, past where the
music is heard, at which no hold is open. Earlier notes are not in the session, and a hold that started before the join
has ended by it. This is the rule of the runtime's scheduler, which the Swift app plays by; a stored chart has no
scheduler, so `JoinPoint` applies it to the chart. The playfield opens at once with the music's position already on it,
and a visible count runs on the clock to the join. Nothing waits for the player. `PlayController` is the same one drop-in
play uses, and the same `AudioTrack` path is untouched: only its clock and its chart differ. A join is always a new
controller, so its first frame asks the session for what is visible at that position.

Pause stops the game, not the music: Resume finds the music where it has got to, and the notes passed meanwhile are
misses. Restart is not offered, since the room's music cannot start again. If the player leaves, the game can join again
from the same lock while the song plays.

**Offsets.** No output's audio offset applies, since there is no output. The screen's ambient offset, default 0, takes
its place: a tester's trim of how the join feels, stored with the session's options.

**Results.** A session's game ends on the session's screen, with accuracy, counts and the mean hit error, which is what
to set the ambient offset by.

## When things go wrong

| Case | What the session does |
| --- | --- |
| NetEase is not installed | Says so; a share still goes on |
| Recognition fails, or a song has no download right | Says so, from a driver's report; by hand, the person sees it in the host |
| No share arrives in 10 minutes, no file in 15 minutes after the share | Says so; what arrives later still counts |
| A `.ncm` | The refusal above |
| The model is not installed | Says so before the host opens; Retry looks again |
| The chart fails | Says the runtime's words; Retry makes it again |
| The app is killed in the background, as HyperOS does | The session is in a snapshot, written on every change of stage. The next start or share goes on: listening again, a new look at the files, the chart or Sync started again. Only a game cannot be picked up: the session says it was lost |
| The app was frozen | Nothing is delivered to a frozen process, so each return to the screen looks at the files again |
| The microphone hears nothing | Asks whether the music is still playing, after 15 s, with the level in the trace |
| Sync never locks | As above; no guess |
| The room's song ends before the join | Says so: no note is left after the join point. Try again from Sync |

A driver has 30 seconds for a step and 90 for the recognition's result; after that the step goes to the person.

## The trace

One JSONL file per session in the app's external files, written and flushed line by line, on the device until someone
pulls it. It is meant to be enough on its own to see what went wrong:

```sh
adb pull /sdcard/Android/data/io.ensomi.android/files/recognition/
```

Every line has `elapsed_ns` (`SystemClock.elapsedRealtimeNanos`), `cid`, `seq` and `event`, then the event's fields.

| Event | Fields |
| --- | --- |
| `session.device` | The device, the host's version, the driver, the capture source, both clocks read together |
| `session.start`, `session.restored`, `session.lost`, `session.end`, `abort` | How it began, why it stopped, at which stage |
| `stage` | A stage's status changing |
| `watch.start`, `watch.folder`, `watch.seen`, `watch.failed` | Which sources are listening, whether all-files access is on, and each source's first sight of each new file, so that all of them answer whichever fired first |
| `index.ready` | Audio files found |
| `host.launch`, `driver.step`, `manual_intervention` | How the host started; what a driver was asked and said; what went to the person, and why |
| `share.received`, `share.link`, `song.identity` | The text, what was read from it, the short link's outcome |
| `audio.candidate`, `audio.gone`, `decode`, `audio.ready` | Each file with its evidence, match and verdict, and what decoding it showed |
| `chart.phase`, `chart.ready`, `chart.failed` | The phases, the notes, whether it was stored, the time |
| `sync.reference`, `capture.start`, `capture.level`, `capture.stamps`, `sync.state`, `sync.truth` | The aligner, the source and format, the room's level each second, how blocks are stamped, each change of state with the reason no position is held, and for a replay the lock's error against the file |
| `game.plan`, `game.join`, `game.end`, `game.quit` | Where the music was, how old the lock was, where the game joined, what it skipped, the score |
| `failure`, `retry`, `foreground` | What stopped, what was tried again, Ensomi coming and going |

Nothing recorded from the microphone leaves the device, or is kept: blocks go to the aligner and only their level to the
trace.

## Running a test

On a debug or checks build, with the chart model installed:

```sh
adb shell appops set io.ensomi.android MANAGE_EXTERNAL_STORAGE allow   # optional: the FileObserver and sight of .ncm
```

Open the music note in the Library's top app bar, choose Path A, B or Auto, and press Start session, which asks for the
microphone and music files. The large panel says what to do by hand. Once the share has brought you back, keep the tablet
on the session's screen with the music playing.

`recognition/options.json` in the same folder as the traces holds the tester's choices. The screen writes it, and a script
can push it instead, for those the screen leaves out:

```json
{"path":"AUTO","stars":3.5,"ambientOffsetMs":0,"capture":"REPLAY","replayStartMs":30000,"autoplay":true}
```

`capture: REPLAY` plays `recognition/replay.wav` (16-bit or float, any rate) into Sync in real time instead of the
microphone, from `replayStartMs` into the file, which is how an emulator runs the whole path through the real aligner.
Since a replay knows where it is, `sync.truth` gives the lock's error. `autoplay` has the autoplay play the game.

```sh
adb shell am start -a android.intent.action.SEND -t text/plain -n io.ensomi.android/io.ensomi.android.recognition.ShareActivity \
  --es android.intent.extra.TEXT "分享周杰伦的单曲《晴天》: https://music.163.com/song?id=186016 (来自@网易云音乐)"
```

## What is not known yet

Nobody has run this on the K Pad with the host: whether Ensomi shows in the host's share sheet, whether `FileObserver`
fires through FUSE there as it does on an AVD, what state the host's rows are in, whether HyperOS keeps the app alive while
the host is in front, how the host names a file while downloading it, and how the microphone hears a room. The trace is
there to answer these: `watch.seen` gives each source's answer apart from which fired first.
