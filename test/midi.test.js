// Every input in this file is written by hand for the test. Nothing here comes from a real beatmap.

import assert from "node:assert/strict";
import test from "node:test";

import { timingPointsToMidi } from "../src/index.js";

/** An uninherited timing point (a red line). */
function red(time, beatLength, meter = 4) {
  return { time, beatLength, meter, sampleSet: 1, sampleIndex: 0, volume: 100, uninherited: true, effects: 0 };
}

/** An inherited timing point (a green line). */
function green(time, beatLength = -100) {
  return { time, beatLength, meter: 4, sampleSet: 1, sampleIndex: 0, volume: 100, uninherited: false, effects: 0 };
}

// --- A small Standard MIDI File reader, enough for a one-track file of meta events. ---

/** Reads the header and the events of the track, with absolute ticks. */
function readSmf(bytes) {
  assert.ok(bytes instanceof Uint8Array);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const ascii = (offset, length) => String.fromCharCode(...bytes.subarray(offset, offset + length));

  assert.equal(ascii(0, 4), "MThd");
  assert.equal(view.getUint32(4), 6, "the header chunk holds 6 bytes");
  const format = view.getUint16(8);
  const trackCount = view.getUint16(10);
  const division = view.getUint16(12);
  assert.equal(ascii(14, 4), "MTrk");
  assert.equal(22 + view.getUint32(18), bytes.length, "the track chunk runs to the end of the file");

  const events = [];
  let offset = 22;
  let tick = 0;
  while (offset < bytes.length) {
    let delta = 0;
    let byte;
    do {
      byte = bytes[offset];
      offset += 1;
      delta = delta * 128 + (byte & 0x7f);
    } while (byte & 0x80);
    tick += delta;

    assert.equal(bytes[offset], 0xff, "the track holds only meta events");
    const type = bytes[offset + 1];
    const length = bytes[offset + 2];
    assert.ok(length < 0x80, "every meta event here is shorter than 128 bytes");
    const data = bytes.subarray(offset + 3, offset + 3 + length);
    offset += 3 + length;
    assert.ok(offset <= bytes.length, "an event does not run past the end of the file");

    if (type === 0x03) {
      events.push({ tick, type: "name", text: String.fromCharCode(...data) });
    } else if (type === 0x51) {
      assert.equal(length, 3);
      events.push({ tick, type: "tempo", microseconds: data[0] * 65536 + data[1] * 256 + data[2] });
    } else if (type === 0x58) {
      assert.equal(length, 4);
      assert.deepStrictEqual([...data.subarray(1)], [2, 24, 8], "dd=2 (a quarter note), cc=24, bb=8");
      events.push({ tick, type: "meter", numerator: data[0] });
    } else if (type === 0x2f) {
      assert.equal(length, 0);
      assert.equal(offset, bytes.length, "End of Track is the last event");
      events.push({ tick, type: "end" });
    } else {
      assert.fail(`unexpected meta event 0x${type.toString(16)}`);
    }
  }
  assert.equal(events[events.length - 1].type, "end", "the track ends with End of Track");
  return { format, trackCount, division, events };
}

/** The events of a file as lines of text: `tick kind value`. */
function listing(bytes) {
  return readSmf(bytes).events.map((event) => {
    if (event.type === "name") return `${event.tick} name ${event.text}`;
    if (event.type === "tempo") return `${event.tick} tempo ${event.microseconds}`;
    if (event.type === "meter") return `${event.tick} meter ${event.numerator}/4`;
    return `${event.tick} end`;
  });
}

/** Each Set Tempo of a file with the time in milliseconds at which a player reaches it. */
function tempoEvents(bytes) {
  const smf = readSmf(bytes);
  const found = [];
  let microseconds = 0;
  let tick = 0;
  let tempo = 500000; // the MIDI default until the first Set Tempo
  for (const event of smf.events) {
    microseconds += ((event.tick - tick) * tempo) / smf.division;
    tick = event.tick;
    if (event.type === "tempo") {
      found.push({ tick, tempo: event.microseconds, ms: microseconds / 1000 });
      tempo = event.microseconds;
    }
  }
  return found;
}

/**
 * The documented bound, in milliseconds, on the distance between the time of red line `index` in the file and
 * its original time. `lines` are red lines sorted by time with the first at time 0 or later.
 *
 * - tempo rounding: half a microsecond for each quarter note of the lead-in and each beat since the first line;
 * - tick rounding: half a tick at the tempo before the line, and for each earlier line half a tick times the
 *   change of beat length there.
 */
function documentedBound(lines, index, ppq, leadInBeats) {
  const halfTick = 0.5 / ppq; // in beats
  const written = (line) => Math.round(line.beatLength * 1000) / 1000; // the beat length the file holds, in ms
  let bound = leadInBeats * 0.0005;
  for (let i = 0; i < index; i += 1) {
    bound += ((lines[i + 1].time - lines[i].time) / lines[i].beatLength) * 0.0005;
    if (i > 0) bound += halfTick * Math.abs(written(lines[i - 1]) - written(lines[i]));
  }
  if (index > 0) bound += halfTick * written(lines[index - 1]);
  return bound + 1e-9; // room for the floating-point arithmetic of this reader
}

/**
 * Asserts that every red line is in the file within the documented bound of its original time, and that every
 * section between two red lines has its original length to within one tick plus half a microsecond per beat.
 * Returns the largest distance and the largest bound, in milliseconds.
 */
function assertTimesWithinDocumentedBound(lines, ppq, leadInBeats) {
  const tempos = tempoEvents(timingPointsToMidi(lines, { ppq })).slice(leadInBeats > 0 ? 1 : 0);
  assert.equal(tempos.length, lines.length, "one Set Tempo for each red line");
  assert.equal(tempos[0].tick, leadInBeats * ppq, "the first red line follows a lead-in of that many quarter notes");
  let largestError = 0;
  let largestBound = 0;
  lines.forEach((line, index) => {
    const error = Math.abs(tempos[index].ms - line.time);
    const bound = documentedBound(lines, index, ppq, leadInBeats);
    assert.ok(error <= bound, `red line ${index} at ${line.time} ms is ${error} ms off; the bound is ${bound} ms`);
    largestError = Math.max(largestError, error);
    largestBound = Math.max(largestBound, bound);

    if (index > 0) {
      const previous = lines[index - 1];
      const length = line.time - previous.time;
      const lengthInFile = tempos[index].ms - tempos[index - 1].ms;
      const tick = Math.round(previous.beatLength * 1000) / 1000 / ppq; // in ms
      const allowed = tick + (length / previous.beatLength) * 0.0005 + 1e-9;
      assert.ok(
        Math.abs(lengthInFile - length) <= allowed,
        `the section before red line ${index} is ${lengthInFile} ms long in the file, originally ${length} ms`,
      );
    }
  });
  return { largestError, largestBound };
}

// --- Fixtures ---

// 120 BPM from 1000 ms (two beats after time 0), 130 BPM from 9000 ms (16 beats later), 180 BPM in 3/4 from
// 12692 ms (just short of 8 beats later: 3692 / 461.538461538462 = 7.99933 beats).
const THREE_SECTIONS = [red(1000, 500, 4), red(9000, 461.538461538462, 4), red(12692, 333.333333333333, 3)];

// Twelve sections with times that fall between ticks.
const TWELVE_SECTIONS = [
  red(731.4, 512.82, 4),
  red(9412.9, 487.3, 4),
  red(15633.05, 361.44578313253, 3),
  red(27000, 600, 4),
  red(31234.567, 285.714285714286, 4),
  red(40000.25, 428.571428571429, 5),
  red(52999.9, 333.333333333333, 4),
  red(60123.4, 750.1, 7),
  red(75555.5, 252.63157894737, 4),
  red(81000.001, 500, 4),
  red(99000.75, 312.5, 6),
  red(120000.3, 451.127819548872, 4),
];

// --- Header and whole files ---

test("writes the header of a format 1 file with one track", () => {
  const bytes = timingPointsToMidi([red(0, 500)]);
  assert.ok(bytes instanceof Uint8Array);
  assert.deepStrictEqual(
    [...bytes.subarray(0, 14)],
    [0x4d, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x01, 0x00, 0x01, 0x03, 0xc0],
    "MThd, length 6, format 1, one track, 960 ticks per quarter note",
  );
  const smf = readSmf(bytes);
  assert.deepStrictEqual([smf.format, smf.trackCount, smf.division], [1, 1, 960]);
});

test("a single point at time 0 gives exactly these bytes", () => {
  // prettier-ignore
  const expected = [
    0x4d, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x01, 0x00, 0x01, 0x03, 0xc0, // MThd
    0x4d, 0x54, 0x72, 0x6b, 0x00, 0x00, 0x00, 0x20, // MTrk, 32 bytes
    0x00, 0xff, 0x03, 0x09, 0x54, 0x65, 0x6d, 0x70, 0x6f, 0x20, 0x6d, 0x61, 0x70, // track name "Tempo map"
    0x00, 0xff, 0x51, 0x03, 0x07, 0xa1, 0x20, // Set Tempo 500000
    0x00, 0xff, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08, // Time Signature 4/4
    0x00, 0xff, 0x2f, 0x00, // End of Track
  ];
  assert.deepStrictEqual([...timingPointsToMidi([red(0, 500, 4)])], expected);
});

test("a point at a positive time gives exactly these bytes, with a lead-in", () => {
  // prettier-ignore
  const expected = [
    0x4d, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x01, 0x00, 0x01, 0x03, 0xc0, // MThd
    0x4d, 0x54, 0x72, 0x6b, 0x00, 0x00, 0x00, 0x30, // MTrk, 48 bytes
    0x00, 0xff, 0x03, 0x09, 0x54, 0x65, 0x6d, 0x70, 0x6f, 0x20, 0x6d, 0x61, 0x70, // track name "Tempo map"
    0x00, 0xff, 0x51, 0x03, 0x09, 0x28, 0xba, // Set Tempo 600250: two quarter notes that last 1200.5 ms
    0x00, 0xff, 0x58, 0x04, 0x02, 0x02, 0x18, 0x08, // Time Signature 2/4
    0x8f, 0x00, 0xff, 0x51, 0x03, 0x07, 0xa1, 0x20, // 1920 ticks later: Set Tempo 500000
    0x00, 0xff, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08, // Time Signature 4/4
    0x00, 0xff, 0x2f, 0x00, // End of Track
  ];
  assert.deepStrictEqual([...timingPointsToMidi([red(1200.5, 500, 4)])], expected);
});

// --- Events, ticks and tempo values ---

test("writes the events of three sections in order, at the expected ticks", () => {
  assert.deepStrictEqual(listing(timingPointsToMidi(THREE_SECTIONS)), [
    "0 name Tempo map",
    "0 tempo 500000", // lead-in: two quarter notes of 500 ms
    "0 meter 2/4",
    "1920 tempo 500000",
    "1920 meter 4/4",
    "17280 tempo 461538", // 16 beats later; 461538.461538462 rounds to 461538
    "24959 tempo 333333", // 7.99933 beats later: 17280 + 7679.36 rounds to 24959
    "24959 meter 3/4",
    "24959 end",
  ]);
});

test("each red line of three sections is in the file within 0.28 ms of its original time", () => {
  const tempos = tempoEvents(timingPointsToMidi(THREE_SECTIONS)).slice(1); // without the lead-in
  assert.deepStrictEqual(
    tempos.map((tempo) => tempo.tick),
    [1920, 17280, 24959],
  );
  // The first two fall exactly on ticks and their tempos are whole microseconds.
  assert.ok(Math.abs(tempos[0].ms - 1000) < 1e-9);
  assert.ok(Math.abs(tempos[1].ms - 9000) < 1e-9);
  // The third is 0.36 ticks early (0.173 ms at 461.538 ms per beat), and 8 beats at a tempo rounded down by
  // 0.46 microseconds add 0.004 ms: 12691.823 ms in the file.
  assert.ok(Math.abs(tempos[2].ms - 12691.8232) < 0.0001, `got ${tempos[2].ms}`);

  const { largestError, largestBound } = assertTimesWithinDocumentedBound(THREE_SECTIONS, 960, 2);
  assert.ok(largestBound < 0.28, `the documented bound for this fixture is ${largestBound} ms`);
  assert.ok(largestError < 0.28);
});

test("each red line of twelve sections is in the file within the documented bound, at several resolutions", () => {
  // Tick rounding carries over where the tempo changes, so the bound grows along the file: at 960 ticks it is
  // 0.28 ms for the second red line and 1.51 ms for the twelfth. The largest distance found is 0.66 ms (at the
  // ninth red line), more than half a tick of any section here (at most 0.39 ms).
  const at960 = assertTimesWithinDocumentedBound(TWELVE_SECTIONS, 960, 1);
  assert.ok(at960.largestBound < 1.51, `the documented bound at 960 is ${at960.largestBound} ms`);
  assert.ok(at960.largestError < 0.66, `the largest distance at 960 is ${at960.largestError} ms`);

  // Both shrink as ppq grows, down to the tempo rounding (0.15 ms over the 296 beats of this fixture).
  const at96 = assertTimesWithinDocumentedBound(TWELVE_SECTIONS, 96, 1);
  assert.ok(at96.largestBound < 13.8, `the documented bound at 96 is ${at96.largestBound} ms`);
  assert.ok(at96.largestError < 2.3, `the largest distance at 96 is ${at96.largestError} ms`);

  const at15360 = assertTimesWithinDocumentedBound(TWELVE_SECTIONS, 15360, 1);
  assert.ok(at15360.largestBound < 0.24, `the documented bound at 15360 is ${at15360.largestBound} ms`);
  assert.ok(at15360.largestError < 0.03, `the largest distance at 15360 is ${at15360.largestError} ms`);
});

test("emits a Set Tempo at every point and a Time Signature only where the meter changes", () => {
  const points = [red(0, 500, 4), red(8000, 500, 3), red(14000, 500, 3), red(20000, 250, 5)];
  assert.deepStrictEqual(listing(timingPointsToMidi(points)), [
    "0 name Tempo map",
    "0 tempo 500000",
    "0 meter 4/4",
    "15360 tempo 500000", // 16 beats
    "15360 meter 3/4",
    "26880 tempo 500000", // 12 more beats; same meter, so no Time Signature
    "38400 tempo 250000", // 12 more beats
    "38400 meter 5/4",
    "38400 end",
  ]);
});

test("ignores inherited points", () => {
  const mixed = [
    green(-500, -50),
    THREE_SECTIONS[0],
    green(1000, -100),
    green(5000, -83.3333333333333),
    THREE_SECTIONS[1],
    green(9000, -200),
    { ...green(10000), beatLength: NaN, meter: 0 }, // not checked: it is inherited
    THREE_SECTIONS[2],
    green(20000, -100),
  ];
  assert.deepStrictEqual(timingPointsToMidi(mixed), timingPointsToMidi(THREE_SECTIONS));
});

test("sorts the points by time, and does not change the given array", () => {
  const shuffled = [THREE_SECTIONS[2], THREE_SECTIONS[0], THREE_SECTIONS[1]];
  const copy = shuffled.map((point) => ({ ...point }));
  assert.deepStrictEqual(timingPointsToMidi(shuffled), timingPointsToMidi(THREE_SECTIONS));
  assert.deepStrictEqual(shuffled, copy);
});

// --- The lead-in ---

/** The events of the lead-in and of the first point: the Set Tempo and Time Signature of each. */
function start(points, options) {
  return listing(timingPointsToMidi(points, options)).slice(1, 5);
}

test("an offset of a whole number of beats becomes a lead-in at the first section's own tempo", () => {
  assert.deepStrictEqual(listing(timingPointsToMidi([red(2000, 500, 3)])), [
    "0 name Tempo map",
    "0 tempo 500000", // four quarter notes of 500 ms, in one bar
    "0 meter 4/4",
    "3840 tempo 500000",
    "3840 meter 3/4",
    "3840 end",
  ]);
  // 3000 ms at 500 ms per beat: six beats.
  assert.deepStrictEqual(start([red(3000, 500)]), [
    "0 tempo 500000",
    "0 meter 6/4",
    "5760 tempo 500000",
    "5760 meter 4/4",
  ]);
  // Three beats of 461.538461538462 ms, written with 15 digits.
  assert.deepStrictEqual(start([red(1384.61538461539, 461.538461538462, 3)]), [
    "0 tempo 461538",
    "0 meter 3/4",
    "2880 tempo 461538",
    "2880 meter 3/4", // the first point's own meter is written even when it equals the lead-in's
  ]);
  // 20 s at 500 ms per beat: 40 beats, still one bar.
  assert.deepStrictEqual(start([red(20000, 500)]), [
    "0 tempo 500000",
    "0 meter 40/4",
    "38400 tempo 500000",
    "38400 meter 4/4",
  ]);
});

test("any other offset becomes the nearest whole number of beats, at the tempo that makes them last as long", () => {
  // 1250 ms is 2.5 beats of 500 ms: three quarter notes of 416.667 ms.
  assert.deepStrictEqual(start([red(1250, 500)]), [
    "0 tempo 416667",
    "0 meter 3/4",
    "2880 tempo 500000",
    "2880 meter 4/4",
  ]);
  // 700 ms is 1.4 beats: one quarter note of 700 ms.
  assert.deepStrictEqual(start([red(700, 500)]), [
    "0 tempo 700000",
    "0 meter 1/4",
    "960 tempo 500000",
    "960 meter 4/4",
  ]);

  // 1000.001 ms is 3.33 beats of 300 ms: three quarter notes of 333333.67 microseconds, rounded to 333334.
  const points = [red(1000.001, 300), red(2200.001, 500)];
  assert.deepStrictEqual(listing(timingPointsToMidi(points, { ppq: 480 })).slice(1, 6), [
    "0 tempo 333334",
    "0 meter 3/4",
    "1440 tempo 300000",
    "1440 meter 4/4",
    "3360 tempo 500000", // 4 beats later
  ]);
  const tempos = tempoEvents(timingPointsToMidi(points, { ppq: 480 }));
  assert.ok(Math.abs(tempos[1].ms - 1000.002) < 1e-9, "the lead-in is 3 * 333334 microseconds long");
  assertTimesWithinDocumentedBound(points, 480, 3); // within 3 * 0.0005 ms for the first point
});

test("an offset shorter than a beat becomes one quarter note that is faster than the song", () => {
  // 100 ms before a section of 500 ms per beat (120 BPM): one quarter note of 100 ms (600 BPM).
  assert.deepStrictEqual(start([red(100, 500)]), [
    "0 tempo 100000",
    "0 meter 1/4",
    "960 tempo 500000",
    "960 meter 4/4",
  ]);
  // 60 ms: 1000 BPM.
  assert.deepStrictEqual(start([red(60, 500)]).slice(0, 2), ["0 tempo 60000", "0 meter 1/4"]);
  assert.deepStrictEqual(start([red(345.678, 400, 3)]), [
    "0 tempo 345678",
    "0 meter 1/4",
    "960 tempo 400000",
    "960 meter 3/4",
  ]);
});

test("an offset of more than 255 beats becomes whole bars of the first meter", () => {
  // 255 beats still fit one bar.
  assert.deepStrictEqual(start([red(127500, 500, 4)]), [
    "0 tempo 500000",
    "0 meter 255/4",
    "244800 tempo 500000",
    "244800 meter 4/4",
  ]);
  // 300 beats: 75 bars of 4/4.
  assert.deepStrictEqual(start([red(150000, 500, 4)]), [
    "0 tempo 500000",
    "0 meter 4/4",
    "288000 tempo 500000",
    "288000 meter 4/4",
  ]);
  // 300 beats in 7/4 are 42.86 bars: 43 bars, 301 quarter notes of 498338.87 microseconds.
  const inSeven = [red(150000, 500, 7), red(153500, 250, 7)];
  assert.deepStrictEqual(listing(timingPointsToMidi(inSeven)), [
    "0 name Tempo map",
    "0 tempo 498339",
    "0 meter 7/4",
    "288960 tempo 500000",
    "288960 meter 7/4",
    "295680 tempo 250000", // 7 beats later
    "295680 end",
  ]);
  assertTimesWithinDocumentedBound(inSeven, 960, 301); // within 301 * 0.0005 ms for the first point
  // 255.6 beats round to 256, more than one bar holds; in 5/4 they are 51.12 bars: 51 bars, 255 quarter notes.
  assert.deepStrictEqual(start([red(127800, 500, 5)]), [
    "0 tempo 501176",
    "0 meter 5/4",
    "244800 tempo 500000",
    "244800 meter 5/4",
  ]);

  // No offset is too long for a lead-in: 4290 s are 2145 bars.
  assert.deepStrictEqual(start([red(4290000, 500)]), [
    "0 tempo 500000",
    "0 meter 4/4",
    "8236800 tempo 500000",
    "8236800 meter 4/4",
  ]);
  // Only the distance between two events limits it: 300000 beats at 960 ticks are 288000000 ticks.
  assert.throws(() => timingPointsToMidi([red(150000000, 500)]), RangeError);
  assert.equal(start([red(150000000, 500)], { ppq: 480 })[2], "144000000 tempo 500000");
});

test("the lead-in's tempo stays within what a Set Tempo holds", () => {
  // 23 s before a section of 16 s per beat is 1.44 beats, but one quarter note of 23 s is more than 16777215
  // microseconds: two quarter notes of 11.5 s.
  assert.deepStrictEqual(start([red(23000, 16000)]), [
    "0 tempo 11500000",
    "0 meter 2/4",
    "1920 tempo 16000000",
    "1920 meter 4/4",
  ]);
  // The longest quarter note itself fits.
  assert.deepStrictEqual(start([red(16777.215, 16777.215)]), [
    "0 tempo 16777215",
    "0 meter 1/4",
    "960 tempo 16777215",
    "960 meter 4/4",
  ]);
  // In whole bars. 4500 s before a section of 15 s per beat in 255/4 are 300 beats, 1.18 bars; one bar would be
  // 255 quarter notes of 17.6 s: two bars.
  assert.deepStrictEqual(start([red(4500000, 15000, 255)]), [
    "0 tempo 8823529",
    "0 meter 255/4",
    "489600 tempo 15000000",
    "489600 meter 255/4",
  ]);
  // 4400 s before a section of 16777.215 ms per beat: 262 quarter notes would be too slow and 263 are more than
  // one bar holds: 66 bars of 4/4.
  assert.deepStrictEqual(start([red(4400000, 16777.215, 4)]), [
    "0 tempo 16666667",
    "0 meter 4/4",
    "253440 tempo 16777215",
    "253440 meter 4/4",
  ]);

  // The fastest tempo is 1 microsecond. 0.0009 ms is 1.8 beats of 0.0005 ms, but two quarter notes of 0.45
  // microseconds would round to 0: one quarter note.
  assert.deepStrictEqual(start([red(0.0009, 0.0005)]), ["0 tempo 1", "0 meter 1/4", "960 tempo 1", "960 meter 4/4"]);
  // In whole bars: 500000 bars of 4/4 at 0.4999999 microseconds would round to 0: one bar fewer.
  assert.deepStrictEqual(start([red(999.9998, 0.0005, 4)], { ppq: 96 }), [
    "0 tempo 1",
    "0 meter 4/4",
    "191999616 tempo 1", // 1999996 quarter notes
    "191999616 meter 4/4",
  ]);
});

test("an offset of less than half a microsecond gets no lead-in", () => {
  assert.deepStrictEqual(listing(timingPointsToMidi([red(0.0004, 500)])), [
    "0 name Tempo map",
    "0 tempo 500000",
    "0 meter 4/4",
    "0 end",
  ]);
  assert.deepStrictEqual(start([red(0.002, 500)]), ["0 tempo 2", "0 meter 1/4", "960 tempo 500000", "960 meter 4/4"]);
});

// --- Points at or before time 0 ---

test("a point before time 0 moves forward by whole bars", () => {
  // Bars of 4 * 500 = 2000 ms: -1000 ms moves one bar, to 1000 ms. The lead-in is two beats, and the next
  // point is 8 beats after the moved one.
  const bytes = timingPointsToMidi([red(-1000, 500, 4), red(5000, 400, 4)]);
  assert.deepStrictEqual(listing(bytes), [
    "0 name Tempo map",
    "0 tempo 500000",
    "0 meter 2/4",
    "1920 tempo 500000",
    "1920 meter 4/4",
    "9600 tempo 400000",
    "9600 end",
  ]);
  const tempos = tempoEvents(bytes);
  assert.ok(Math.abs(tempos[1].ms - 1000) < 1e-9, "a bar line of the first section is at 1000 ms");
  assert.ok(Math.abs(tempos[2].ms - 5000) < 1e-9, "the second point keeps its time");

  // -700 ms moves to 1300 ms, 2.6 beats: three quarter notes of 433333.33 microseconds.
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-700, 500, 4), red(5300, 400, 4)])).slice(1, 6), [
    "0 tempo 433333",
    "0 meter 3/4",
    "2880 tempo 500000",
    "2880 meter 4/4",
    "10560 tempo 400000", // 8 beats later
  ]);
  // Bars of 3 * 400 = 1200 ms: -4000 ms moves four bars, to 800 ms.
  assert.deepStrictEqual(start([red(-4000, 400, 3)]), [
    "0 tempo 400000",
    "0 meter 2/4",
    "1920 tempo 400000",
    "1920 meter 3/4",
  ]);
});

test("a point a whole number of bars before time 0 moves to time 0 and gets no lead-in", () => {
  const atZero = ["0 name Tempo map", "0 tempo 500000", "0 meter 4/4", "0 end"];
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-4000, 500, 4)])), atZero);
  assert.deepStrictEqual(listing(timingPointsToMidi([red(0, 500, 4)])), atZero);
});

test("a bar line within half a microsecond of time 0 counts as at time 0", () => {
  const atZero = (meter) => ["0 name Tempo map", "0 tempo 461538", `0 meter ${meter}/4`, "0 end"];
  // One bar of 4 * 461.538461538462 ms, written with 15 digits: floating-point division gives 1.000000000000001
  // bars. Two bars of 3 * 461.538461538462 ms: 1.9999999999999984 bars.
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-1846.15384615385, 461.538461538462, 4)])), atZero(4));
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-2769.23076923077, 461.538461538462, 3)])), atZero(3));

  // A bar line 0.3 microseconds after, and 0.3 microseconds before, time 0.
  const plain = ["0 name Tempo map", "0 tempo 500000", "0 meter 4/4", "0 end"];
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-1999.9997, 500, 4)])), plain);
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-2000.0003, 500, 4)])), plain);
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-0.0003, 500, 4)])), plain);

  // A bar line one microsecond before time 0 is before it: the point moves one more bar, to 1999.999 ms.
  assert.deepStrictEqual(start([red(-2000.001, 500, 4)]), [
    "0 tempo 500000", // four quarter notes of 499999.75 microseconds
    "0 meter 4/4",
    "3840 tempo 500000",
    "3840 meter 4/4",
  ]);
});

test("a bar line less than half a beat after time 0 is passed over for the next one", () => {
  // Bars of 4 * 461.538461538462 = 1846.154 ms. From -1846 ms the first bar line is 0.154 ms after time 0; the
  // next is at 1846.308 ms, four beats: four quarter notes of 461576.92 microseconds.
  const bytes = timingPointsToMidi([red(-1846, 461.538461538462, 4)]);
  assert.deepStrictEqual(listing(bytes), [
    "0 name Tempo map",
    "0 tempo 461577",
    "0 meter 4/4",
    "3840 tempo 461538",
    "3840 meter 4/4",
    "3840 end",
  ]);
  assert.ok(Math.abs(tempoEvents(bytes)[1].ms - 1846.3077) < 0.002, "within 4 * 0.0005 ms of the bar line");
  // From -1847 ms the first bar line is 0.846 ms before time 0, and the next at 1845.308 ms: nearly the same.
  assert.deepStrictEqual(start([red(-1847, 461.538461538462, 4)]), [
    "0 tempo 461327",
    "0 meter 4/4",
    "3840 tempo 461538",
    "3840 meter 4/4",
  ]);

  // Bars of 2000 ms, beats of 500 ms. A bar line 200 ms after time 0 is passed over: 2200 ms, 4.4 beats.
  assert.deepStrictEqual(start([red(-1800, 500, 4)]), [
    "0 tempo 550000",
    "0 meter 4/4",
    "3840 tempo 500000",
    "3840 meter 4/4",
  ]);
  // A bar line exactly half a beat after time 0 is used: one quarter note of 250 ms.
  assert.deepStrictEqual(start([red(-1750, 500, 4)]), [
    "0 tempo 250000",
    "0 meter 1/4",
    "960 tempo 500000",
    "960 meter 4/4",
  ]);
  // One 249 ms after time 0 is not: 2249 ms, 4.498 beats.
  assert.deepStrictEqual(start([red(-1751, 500, 4)]), [
    "0 tempo 562250",
    "0 meter 4/4",
    "3840 tempo 500000",
    "3840 meter 4/4",
  ]);
  // In 1/4 the next bar line is one beat later: from 100 ms to 600 ms.
  assert.deepStrictEqual(start([red(-400, 500, 1)]), [
    "0 tempo 600000",
    "0 meter 1/4",
    "960 tempo 500000",
    "960 meter 1/4",
  ]);

  // This is for points before time 0 only: a point 200 ms after time 0 stays there.
  assert.deepStrictEqual(start([red(200, 500, 4)]), [
    "0 tempo 200000",
    "0 meter 1/4",
    "960 tempo 500000",
    "960 meter 4/4",
  ]);
});

test("of the points at or before time 0, only the last is used", () => {
  // -100 ms in bars of 3 * 500 = 1500 ms moves to 1400 ms, 2.8 beats; the point at 4400 ms is 6 beats after that.
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-5000, 600, 4), red(-100, 500, 3), red(4400, 250, 3)])), [
    "0 name Tempo map",
    "0 tempo 466667",
    "0 meter 3/4",
    "2880 tempo 500000",
    "2880 meter 3/4",
    "8640 tempo 250000",
    "8640 end",
  ]);
  const atZero = ["0 name Tempo map", "0 tempo 500000", "0 meter 4/4", "0 end"];
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-900, 600, 5), red(0, 500, 4)])), atZero);
  // Two points at time 0: the later one in the given order is the last.
  assert.deepStrictEqual(listing(timingPointsToMidi([red(0, 600, 5), red(0, 500, 4)])), atZero);
});

test("a moved point is dropped when the next point is not after its moved time", () => {
  // -50 ms in bars of 2000 ms would move to 1950 ms, past the point at 800 ms: the first section's tempo is not
  // written, and the lead-in to 800 ms is two beats of the second point.
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-50, 500, 4), red(800, 400, 3), red(2400, 300, 3)])), [
    "0 name Tempo map",
    "0 tempo 400000",
    "0 meter 2/4",
    "1920 tempo 400000",
    "1920 meter 3/4",
    "5760 tempo 300000", // 4 beats later
    "5760 end",
  ]);

  // -1000 ms moves to 1000 ms. A point at exactly 1000 ms replaces it.
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-1000, 500, 4), red(1000, 250, 4)])), [
    "0 name Tempo map",
    "0 tempo 250000",
    "0 meter 4/4",
    "3840 tempo 250000",
    "3840 meter 4/4",
    "3840 end",
  ]);

  // A point at 1000.5 ms is after it: the moved point stays, and the next one is 0.001 beats (0.96 ticks) later.
  assert.deepStrictEqual(listing(timingPointsToMidi([red(-1000, 500, 4), red(1000.5, 250, 4)])), [
    "0 name Tempo map",
    "0 tempo 500000",
    "0 meter 2/4",
    "1920 tempo 500000",
    "1920 meter 4/4",
    "1921 tempo 250000",
    "1921 end",
  ]);

  // Passing over a bar line can take the moved time past the next point. From -1846 ms the bar line at 0.154 ms
  // is passed over, and the one at 1846.308 ms is after the point at 1000 ms (2.5 beats of 400 ms).
  assert.deepStrictEqual(start([red(-1846, 461.538461538462, 4), red(1000, 400, 4)]), [
    "0 tempo 333333",
    "0 meter 3/4",
    "2880 tempo 400000",
    "2880 meter 4/4",
  ]);
});

// --- Ticks ---

test("the ppq option sets the division and scales the ticks", () => {
  const at480 = timingPointsToMidi(THREE_SECTIONS, { ppq: 480 });
  assert.deepStrictEqual([...at480.subarray(8, 14)], [0x00, 0x01, 0x00, 0x01, 0x01, 0xe0]);
  assert.deepStrictEqual(listing(at480), [
    "0 name Tempo map",
    "0 tempo 500000",
    "0 meter 2/4",
    "960 tempo 500000",
    "960 meter 4/4",
    "8640 tempo 461538",
    "12480 tempo 333333", // 25.99933 beats * 480 = 12479.68
    "12480 meter 3/4",
    "12480 end",
  ]);

  assert.equal(readSmf(timingPointsToMidi(THREE_SECTIONS, { ppq: 1 })).division, 1);
  assert.deepStrictEqual(
    tempoEvents(timingPointsToMidi(THREE_SECTIONS, { ppq: 1 })).map((tempo) => tempo.tick),
    [0, 2, 18, 26],
  );
  assert.deepStrictEqual([...timingPointsToMidi(THREE_SECTIONS, { ppq: 32767 }).subarray(12, 14)], [0x7f, 0xff]);

  const byDefault = timingPointsToMidi(THREE_SECTIONS);
  assert.deepStrictEqual(timingPointsToMidi(THREE_SECTIONS, {}), byDefault);
  assert.deepStrictEqual(timingPointsToMidi(THREE_SECTIONS, { ppq: undefined }), byDefault);
  assert.deepStrictEqual(timingPointsToMidi(THREE_SECTIONS, { ppq: 960 }), byDefault);
});

test("a ppq that is not an integer from 1 to 32767 is a RangeError", () => {
  for (const ppq of [0, -960, 32768, 65536, 1.5, NaN, Infinity, "960"]) {
    assert.throws(() => timingPointsToMidi(THREE_SECTIONS, { ppq }), RangeError, `ppq ${ppq}`);
  }
});

test("ticks are rounded from the exact position, so rounding does not add up", () => {
  // Each section is 1.3 beats (1.3 * 96 = 124.8 ticks). Rounding each step would give 125 ticks every time.
  const points = [red(0, 500), red(650, 500), red(1300, 500), red(1950, 500), red(2600, 500), red(3250, 500)];
  assert.deepStrictEqual(
    tempoEvents(timingPointsToMidi(points, { ppq: 96 })).map((tempo) => tempo.tick),
    [0, 125, 250, 374, 499, 624],
  );
});

test("when two points land on the same tick, the later one replaces the earlier one", () => {
  // 5000.1 ms is 0.0002 beats (0.19 ticks) after 5000 ms. The 3/4 of the replaced point goes with it, and the
  // point that replaces it has the meter of the point before, so no Time Signature is written there.
  const points = [red(0, 500, 4), red(5000, 500, 3), red(5000.1, 300, 4), red(8000.1, 600, 4)];
  assert.deepStrictEqual(listing(timingPointsToMidi(points)), [
    "0 name Tempo map",
    "0 tempo 500000",
    "0 meter 4/4",
    "9600 tempo 300000",
    "19200 tempo 600000", // 10 beats of 300 ms after 5000.1 ms
    "19200 end",
  ]);

  // The first point can be replaced too; the point that replaces it is then the first.
  assert.deepStrictEqual(listing(timingPointsToMidi([red(0, 500, 4), red(0.1, 300, 3)])), [
    "0 name Tempo map",
    "0 tempo 300000",
    "0 meter 3/4",
    "0 end",
  ]);

  // Two points at the same time: the later one in the given order wins.
  assert.deepStrictEqual(listing(timingPointsToMidi([red(0, 500, 4), red(6000, 400, 4), red(6000, 750, 5)])), [
    "0 name Tempo map",
    "0 tempo 500000",
    "0 meter 4/4",
    "11520 tempo 750000",
    "11520 meter 5/4",
    "11520 end",
  ]);
});

test("delta times are variable-length quantities", () => {
  // 1000 beats at 32767 ticks: 32767000 = 0x1f3fc18, in groups of seven bits 0x0f 0x4f 0x78 0x18.
  const bytes = timingPointsToMidi([red(0, 500), red(500000, 250)], { ppq: 32767 });
  const second = 22 + 13 + 7 + 8; // after the chunk headers, the track name, the first tempo and the first meter
  assert.deepStrictEqual(
    [...bytes.subarray(second, second + 10)],
    [0x8f, 0xcf, 0xf8, 0x18, 0xff, 0x51, 0x03, 0x03, 0xd0, 0x90],
  );
  assert.deepStrictEqual(listing(bytes).slice(3), ["32767000 tempo 250000", "32767000 end"]);

  // Two events can be at most 268435455 ticks apart: 8193 beats at 32767 ticks are 268460031.
  assert.throws(() => timingPointsToMidi([red(0, 500), red(4096500, 250)], { ppq: 32767 }), RangeError);
  assert.deepStrictEqual(
    listing(timingPointsToMidi([red(0, 500), red(4096000, 250)], { ppq: 32767 })).slice(3),
    ["268427264 tempo 250000", "268427264 end"],
  );
});

// --- Errors ---

test("no uninherited point is a RangeError", () => {
  assert.throws(() => timingPointsToMidi([]), RangeError);
  assert.throws(() => timingPointsToMidi([green(0), green(1000, -50)]), RangeError);
});

test("an uninherited point with a beatLength that is not finite and positive is a RangeError", () => {
  for (const beatLength of [0, -100, -0.5, NaN, Infinity, -Infinity, "500"]) {
    assert.throws(
      () => timingPointsToMidi([red(0, 500), red(1000, beatLength)]),
      { name: "RangeError", message: /points\[1\]\.beatLength/ },
      `beatLength ${beatLength}`,
    );
  }
});

test("an uninherited point with a meter that is not an integer from 1 to 255 is a RangeError", () => {
  for (const meter of [0, -4, 256, 4.5, NaN, Infinity, "4"]) {
    assert.throws(
      () => timingPointsToMidi([red(0, 500, meter)]),
      { name: "RangeError", message: /points\[0\]\.meter/ },
      `meter ${meter}`,
    );
  }
  assert.deepStrictEqual(listing(timingPointsToMidi([red(0, 500, 1), red(1000, 500, 255)])).slice(2, 5), [
    "0 meter 1/4",
    "1920 tempo 500000",
    "1920 meter 255/4",
  ]);
});

test("a tempo outside 1..16777215 microseconds per quarter note is a RangeError", () => {
  assert.throws(() => timingPointsToMidi([red(0, 0.0004)]), RangeError); // rounds to 0
  assert.throws(() => timingPointsToMidi([red(0, 16777.216)]), RangeError); // 16777216
  assert.throws(() => timingPointsToMidi([red(0, 500), red(1000, 60000)]), RangeError);
  assert.deepStrictEqual(listing(timingPointsToMidi([red(0, 0.0005)]))[1], "0 tempo 1");
  assert.deepStrictEqual(listing(timingPointsToMidi([red(0, 16777.215)]))[1], "0 tempo 16777215");
});

test("points that are dropped are checked too", () => {
  assert.throws(() => timingPointsToMidi([red(-3000, 60000), red(0, 500)]), RangeError);
  assert.throws(() => timingPointsToMidi([red(-3000, 500, 0), red(0, 500)]), RangeError);
});

test("an uninherited point with a time that is not finite is a RangeError", () => {
  for (const time of [NaN, Infinity, -Infinity, "0"]) {
    assert.throws(() => timingPointsToMidi([red(time, 500)]), RangeError, `time ${time}`);
  }
});

test("points that are not an array of timing points are a TypeError", () => {
  assert.throws(() => timingPointsToMidi("0,500"), TypeError);
  assert.throws(() => timingPointsToMidi(undefined), TypeError);
  assert.throws(() => timingPointsToMidi([null]), TypeError);
});
