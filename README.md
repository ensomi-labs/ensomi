# ensomi

The developer kit for Ensomi models.

**Status:** early. Versions are `0.0.x`, and the API may change in any release.

This release reads and writes the timing points of an osu! beatmap file (`.osu`) and writes them out as a
Standard MIDI File tempo map. It is plain ECMAScript modules with type declarations and no dependencies, and
declares Node.js 20 or later. Its tests use hand-written input and read the MIDI bytes back; the files have
not been checked against particular DAWs.

## Install

```sh
npm install ensomi
```

## Use

```js
import { readFile, writeFile } from "node:fs/promises";
import { formatTimingPoints, parseTimingPoints, timingPointsToMidi } from "ensomi";

const points = parseTimingPoints(await readFile("chart.osu", "utf8"));
await writeFile("chart.mid", timingPointsToMidi(points));
```

### `parseTimingPoints(text)`

Takes the text of a `.osu` file, or only its `[TimingPoints]` lines, and returns one object per line:

```js
const points = parseTimingPoints("[TimingPoints]\n1200,461.538461538462,4,2,1,60,1,0\n8584.6,-100,4,2,1,60,0,1");
points[1];
// { time: 8584.6, beatLength: -100, meter: 4, sampleSet: 2, sampleIndex: 1, volume: 60,
//   uninherited: false, effects: 1 }
```

`time` is in milliseconds. On an uninherited point (a red line) `beatLength` is the length of one beat in
milliseconds; on an inherited point (a green line) it is a negative slider-velocity multiplier. Blank lines and
`//` comments are skipped, short lines from old files take the format's defaults for the missing fields, and a
line that cannot be read throws a `SyntaxError` that names its line number.

### `formatTimingPoints(points)`

Returns the lines of the section, joined with `\n`, without the `[TimingPoints]` header:

```js
formatTimingPoints(points); // "1200,461.538461538462,4,2,1,60,1,0\n8584.6,-100,4,2,1,60,0,1"
```

Parsing the result gives back the same points. Numbers are written as `String(number)` writes them, so the
text of a file is reproduced byte for byte only when its numbers already had that form (`500.0` becomes `500`).

### `timingPointsToMidi(points, options?)`

Returns the bytes (a `Uint8Array`) of a Standard MIDI File, format 1 with one track, that holds a Set Tempo for
each red line and a Time Signature (`meter`/4) for the first red line and wherever the meter changes. Tick 0 is
time 0 of the audio, and one osu! beat is one quarter note. `timingPointsToMidi(points, { ppq: 480 })` sets the
ticks per quarter note (1 to 32767, default 960). Input that cannot be written throws a `RangeError`, such as
no red line, a beat length that does not round to a MIDI tempo (1 to 16777215 microseconds) or a meter over 255.

What the conversion loses:

- The offset becomes a lead-in: when the first red line is after time 0, the file starts with a bar (past 255
  beats, bars of the first meter) that lasts as long as the offset, at a tempo as close to the first section's
  as a whole number of beats allows, and the first red line starts the bar after it.
- An offset shorter than a beat gives a lead-in faster than the song, more than twice as fast when it is under
  half a beat (less than 60 ms means more than 1000 BPM), and a host that limits tempo will then misplace the
  map.
- Of the red lines at or before time 0 only the last is used; one before time 0 is moved forward by whole bars
  to time 0 or to at least half a beat after it, or dropped if the next red line comes first.
- MIDI tempo has a resolution of one microsecond per beat, so a section can drift by up to half of that per beat.
- A red line is placed on the nearest tick, up to half a tick from its exact place (a tick is about 0.52 ms at
  the default 960 ticks per quarter note and 120 BPM), so the length of a section can be off by up to one tick,
  and these errors can add up over many red lines.
- Red lines that land on the same tick become one, the last of them.
- A red line that does not fall on a bar line of the section before it restarts the beat and the bar in osu!;
  the file changes tempo there, but a DAW may keep counting beats and bars from the section before.
- The "omit first bar line" flag, inherited points, and everything else except tempo and meter (sample set,
  sample index, volume, kiai) are not written.

## Ensomi

Ensomi's models and its other repositories are at <https://github.com/ensomi-labs>.

## Licence

[MIT](LICENSE)
