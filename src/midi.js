// Timing points as a Standard MIDI File tempo map.

/** @typedef {import("./timing-points.js").TimingPoint} TimingPoint */

const DEFAULT_PPQ = 960;
const MAX_PPQ = 0x7fff;
const MAX_TEMPO = 0xffffff; // microseconds per quarter note: Set Tempo holds three bytes
const MAX_METER = 0xff; // quarter notes in a bar: the numerator of Time Signature is one byte
const MAX_DELTA = 0x0fffffff; // the largest variable-length quantity
// Milliseconds. The file cannot say anything shorter than a microsecond, so a time less than half a microsecond
// from 0 counts as time 0. Without this, a `time` written with 15 digits that is a whole number of bars before
// 0 could be moved one bar too far by floating-point noise.
const HALF_MICROSECOND = 0.0005;
const TRACK_NAME = "Tempo map";

/**
 * Writes the tempo and meter of timing points as a Standard MIDI File: format 1, one track, `ppq` ticks per
 * quarter note, holding only a track name, Set Tempo, Time Signature and End of Track. Tick 0 of the file is
 * time 0 of the audio, and one osu! beat is one MIDI quarter note.
 *
 * 1. Only uninherited points are used, sorted by `time` (points with equal times keep their order).
 * 2. Each tempo is `Math.round(beatLength * 1000)` microseconds per quarter note.
 * 3. Of the points at or before time 0 only the last is kept. If its time is negative it is moved forward by
 *    the fewest whole bars (`meter` beats each) that bring it either to time 0 (a bar line less than half a
 *    microsecond from time 0 counts as at time 0) or to at least half a beat after time 0. If the next point
 *    comes at or before that moved time, the moved point is dropped and the next point is the first.
 * 4. If the first point is then at a time `t0` of half a microsecond or more, the file starts with a lead-in
 *    of `k` quarter notes at `Math.round(t0 * 1000 / k)` microseconds each, and the first point sits at tick
 *    `k * ppq`. `k` is `t0` in beats of the first point, rounded to a whole number and at least 1, so the
 *    lead-in's tempo is as close to the first section's as a whole number of beats allows. `k` is raised to
 *    `Math.ceil(t0 * 1000 / 16777215)` if the tempo would be more than a Set Tempo holds, and lowered if the
 *    tempo would round to 0. Up to `k = 255` the lead-in is one bar, with the time signature `k/4`. Beyond
 *    that it is whole bars of the first point's meter: `k` is `t0` in those bars, rounded and at least one
 *    bar, with the same two limits applied in whole bars, and the time signature is `meter/4`.
 * 5. Each later point's position in beats is the previous point's position plus the time between them divided
 *    by the previous point's `beatLength`; its tick is that position times `ppq`, rounded. If two points land
 *    on the same tick, the later one replaces the earlier one.
 * 6. Every kept point gets a Set Tempo. The first point, and every point whose `meter` differs from the kept
 *    point before it, gets a Time Signature `meter/4`.
 *
 * How far the time of a point in the file can be from its `time`: half a microsecond for each quarter note of
 * the lead-in and for each beat since the first point (tempo rounding), half a tick at the tempo before the
 * point (tick rounding), and for each earlier point half a tick times the change of beat length there.
 *
 * @param {readonly TimingPoint[]} points
 * @param {{ ppq?: number }} [options] `ppq`: ticks per quarter note, an integer from 1 to 32767. Default 960.
 * @returns {Uint8Array} The bytes of the `.mid` file.
 * @throws {RangeError} If `ppq` is out of range; if there is no uninherited point; if an uninherited point has
 *   a `time` that is not finite, a `beatLength` that is not finite and positive or gives a tempo outside
 *   1..16777215 microseconds, or a `meter` that is not an integer from 1 to 255 (points that rule 3 drops are
 *   checked too); or if two events, or the lead-in's two ends, are more than 268435455 ticks apart.
 */
export function timingPointsToMidi(points, options) {
  if (!Array.isArray(points)) {
    throw new TypeError("timingPointsToMidi: points must be an array");
  }
  const ppq = options?.ppq ?? DEFAULT_PPQ;
  if (!Number.isInteger(ppq) || ppq < 1 || ppq > MAX_PPQ) {
    throw new RangeError(`timingPointsToMidi: ppq must be an integer from 1 to ${MAX_PPQ}, got ${String(ppq)}`);
  }

  const lines = startAtOrAfterZero(redLines(points));

  /** @type {Array<{ tick: number, bytes: number[] }>} */
  const events = [{ tick: 0, bytes: trackName(TRACK_NAME) }];

  const lead = leadIn(lines[0]);
  if (lead !== null) {
    events.push({ tick: 0, bytes: setTempo(lead.tempo) }, { tick: 0, bytes: timeSignature(lead.meter) });
  }

  /** @type {Array<{ tick: number, tempo: number, meter: number }>} */
  const kept = [];
  let position = lead === null ? 0 : lead.beats; // in beats, from tick 0
  lines.forEach((line, index) => {
    if (index > 0) {
      const previous = lines[index - 1];
      position += (line.time - previous.time) / previous.beatLength;
    }
    const entry = { tick: Math.round(position * ppq), tempo: line.tempo, meter: line.meter };
    if (kept.length > 0 && kept[kept.length - 1].tick === entry.tick) {
      kept[kept.length - 1] = entry;
    } else {
      kept.push(entry);
    }
  });

  kept.forEach((entry, index) => {
    events.push({ tick: entry.tick, bytes: setTempo(entry.tempo) });
    if (index === 0 || entry.meter !== kept[index - 1].meter) {
      events.push({ tick: entry.tick, bytes: timeSignature(entry.meter) });
    }
  });

  const track = [];
  let tick = 0;
  for (const event of events) {
    const delta = event.tick - tick;
    if (delta > MAX_DELTA) {
      throw new RangeError(
        `timingPointsToMidi: two events are ${delta} ticks apart; a MIDI file allows at most ${MAX_DELTA}`,
      );
    }
    track.push(...variableLength(delta), ...event.bytes);
    tick = event.tick;
  }
  track.push(0x00, 0xff, 0x2f, 0x00); // End of Track

  return Uint8Array.from([
    ...ascii("MThd"),
    ...uint(6, 4),
    ...uint(1, 2), // format 1
    ...uint(1, 2), // one track
    ...uint(ppq, 2),
    ...ascii("MTrk"),
    ...uint(track.length, 4),
    ...track,
  ]);
}

/**
 * Rules 1 and 2: the uninherited points, checked, with their MIDI tempo, sorted by time.
 *
 * @param {readonly TimingPoint[]} points
 */
function redLines(points) {
  const lines = [];
  points.forEach((point, index) => {
    if (point === null || typeof point !== "object") {
      throw new TypeError(`timingPointsToMidi: points[${index}] must be a timing point`);
    }
    if (!point.uninherited) return;
    const { time, beatLength, meter } = point;
    const at = `timingPointsToMidi: points[${index}]`;
    if (!Number.isFinite(time)) {
      throw new RangeError(`${at}.time must be a finite number, got ${String(time)}`);
    }
    if (!Number.isFinite(beatLength) || beatLength <= 0) {
      throw new RangeError(
        `${at}.beatLength must be a positive finite number on an uninherited point, got ${String(beatLength)}`,
      );
    }
    if (!Number.isInteger(meter) || meter < 1 || meter > MAX_METER) {
      throw new RangeError(`${at}.meter must be an integer from 1 to ${MAX_METER}, got ${String(meter)}`);
    }
    const tempo = Math.round(beatLength * 1000);
    if (tempo < 1 || tempo > MAX_TEMPO) {
      throw new RangeError(
        `${at}.beatLength ${beatLength} ms is ${tempo} microseconds per quarter note; ` +
          `a MIDI tempo is from 1 to ${MAX_TEMPO}`,
      );
    }
    lines.push({ time, beatLength, meter, tempo });
  });
  if (lines.length === 0) {
    throw new RangeError("timingPointsToMidi: there is no uninherited timing point, so no tempo to write");
  }
  return lines.sort((a, b) => a.time - b.time); // Array.prototype.sort is stable
}

/**
 * Rule 3: drops all but the last point at or before time 0, and moves that point, if its time is negative,
 * forward by whole bars to time 0 or to at least half a beat after it.
 *
 * @template {{ time: number, beatLength: number, meter: number }} Line
 * @param {Line[]} lines Sorted by time, not empty.
 * @returns {Line[]} Not empty; the first time is 0 or later.
 */
function startAtOrAfterZero(lines) {
  let last = 0;
  lines.forEach((line, index) => {
    if (line.time <= 0) last = index;
  });
  const rest = lines.slice(last);
  const first = rest[0];
  if (first.time >= 0) return rest;

  const barLength = first.meter * first.beatLength;
  // The first bar line that is not more than half a microsecond before time 0.
  const bars = Math.ceil((-first.time - HALF_MICROSECOND) / barLength);
  let moved = first.time + bars * barLength;
  if (moved < HALF_MICROSECOND) {
    moved = 0;
  } else if (moved < first.beatLength / 2) {
    // Too soon after time 0 for a lead-in of one beat near this tempo: the next bar line.
    moved = first.time + (bars + 1) * barLength;
  }
  if (rest.length > 1 && rest[1].time <= moved) {
    return rest.slice(1); // the next point is after time 0, so it needs no move
  }
  rest[0] = { ...first, time: moved };
  return rest;
}

/**
 * Rule 4: the lead-in that fills the time before the first point.
 *
 * @param {{ time: number, beatLength: number, meter: number }} first The first point; its time is 0 or later.
 * @returns {{ beats: number, tempo: number, meter: number } | null} The length in quarter notes, the tempo in
 *   microseconds per quarter note and the numerator of the time signature; `null` when the first point is less
 *   than half a microsecond after time 0.
 */
function leadIn(first) {
  if (first.time < HALF_MICROSECOND) return null;
  const micros = first.time * 1000;

  // The whole number of units of `step` quarter notes nearest to the lead-in's length in the first point's
  // beats, moved as far as it takes for the tempo to be one a Set Tempo holds (1..16777215).
  const fit = (step) => {
    let beats = step * Math.max(1, Math.round(first.time / (first.beatLength * step)));
    if (Math.round(micros / beats) > MAX_TEMPO) beats = step * Math.ceil(micros / MAX_TEMPO / step);
    while (beats > step && Math.round(micros / beats) < 1) beats -= step;
    return beats;
  };

  let beats = fit(1);
  let meter = beats; // one bar of `beats` quarter notes
  if (beats > MAX_METER) {
    beats = fit(first.meter); // whole bars of the first point's meter
    meter = first.meter;
  }
  return { beats, tempo: Math.round(micros / beats), meter };
}

/** @param {string} text ASCII only. */
function ascii(text) {
  return Array.from(text, (character) => character.charCodeAt(0));
}

/**
 * @param {number} value
 * @param {number} bytes
 * @returns {number[]} `value` as a big-endian unsigned integer of `bytes` bytes.
 */
function uint(value, bytes) {
  const out = [];
  for (let shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
    out.push((value >>> shift) & 0xff);
  }
  return out;
}

/**
 * @param {number} value An integer from 0 to 0x0fffffff.
 * @returns {number[]} `value` as a MIDI variable-length quantity.
 */
function variableLength(value) {
  const out = [value & 0x7f];
  for (let rest = value >>> 7; rest > 0; rest >>>= 7) {
    out.unshift(0x80 | (rest & 0x7f));
  }
  return out;
}

/** @param {string} name ASCII, shorter than 128 characters. */
function trackName(name) {
  return [0xff, 0x03, name.length, ...ascii(name)];
}

/** @param {number} tempo Microseconds per quarter note, 1..16777215. */
function setTempo(tempo) {
  return [0xff, 0x51, 0x03, ...uint(tempo, 3)];
}

/** @param {number} numerator Quarter notes per bar, 1..255. */
function timeSignature(numerator) {
  // dd = 2: the denominator is a quarter note; cc = 24 MIDI clocks per metronome click; bb = 8
  // thirty-second notes per 24 MIDI clocks.
  return [0xff, 0x58, 0x04, numerator, 2, 24, 8];
}
