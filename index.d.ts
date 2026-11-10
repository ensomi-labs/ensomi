/**
 * One line of the `[TimingPoints]` section of an osu! beatmap file (`.osu`).
 */
export interface TimingPoint {
  /** Milliseconds from the start of the audio. May be negative or fractional. */
  time: number;
  /**
   * On an uninherited point, the length of one beat in milliseconds (positive). On an inherited point, a
   * negative slider-velocity multiplier that carries no tempo.
   */
  beatLength: number;
  /** Beats per bar. */
  meter: number;
  sampleSet: number;
  sampleIndex: number;
  volume: number;
  /** `true` for a point that sets the tempo (a "red line"), `false` for an inherited point (a "green line"). */
  uninherited: boolean;
  /** Bit field: bit 0 is kiai, bit 3 omits the first bar line. */
  effects: number;
}

/** Options of {@link timingPointsToMidi}. */
export interface TimingPointsToMidiOptions {
  /** Ticks per quarter note: an integer from 1 to 32767. Default 960. */
  ppq?: number;
}

/**
 * Reads the timing points of an osu! beatmap file.
 *
 * If `text` has a `[TimingPoints]` header line, the lines after it are read, up to the next line that starts
 * with `[`. If `text` has no section header at all, every line is read as a timing point. If it has other
 * section headers but no `[TimingPoints]`, the result is empty. Blank lines and lines that start with `//` are
 * skipped. Missing trailing fields take the defaults `meter=4`, `sampleSet=0`, `sampleIndex=0`, `volume=100`,
 * `uninherited=true`, `effects=0`.
 *
 * @throws {SyntaxError} If a line has fewer than two fields, a field that is present is not a finite decimal
 *   number, or `uninherited` is neither `1` nor `0`. The message names the 1-based line number within `text`.
 */
export function parseTimingPoints(text: string): TimingPoint[];

/**
 * Writes timing points as the lines of a `[TimingPoints]` section: one eight-field line per point, joined with
 * `\n`, with no trailing newline and no section header.
 *
 * `parseTimingPoints(formatTimingPoints(points))` deep-equals `points` (a negative zero is written as `0`).
 * The text of a file is reproduced byte for byte only when its numbers were already written the way
 * `String(number)` writes them.
 *
 * @throws {TypeError} If a point's numeric field is not a finite number or its `uninherited` is not a boolean.
 */
export function formatTimingPoints(points: readonly TimingPoint[]): string;

/**
 * Writes the tempo and meter of the uninherited timing points as a Standard MIDI File (format 1, one track)
 * that holds only a track name, Set Tempo, Time Signature and End of Track. Tick 0 of the file is time 0 of
 * the audio, and one osu! beat is one MIDI quarter note. The README lists what the conversion loses.
 *
 * @returns The bytes of the `.mid` file.
 * @throws {RangeError} If `ppq` is out of range, there is no uninherited point, or an uninherited point cannot
 *   be written: a `time` that is not finite, a `beatLength` that is not finite and positive or gives a tempo
 *   outside 1..16777215 microseconds per quarter note, a `meter` that is not an integer from 1 to 255, or two
 *   events more than 268435455 ticks apart.
 */
export function timingPointsToMidi(points: readonly TimingPoint[], options?: TimingPointsToMidiOptions): Uint8Array;
