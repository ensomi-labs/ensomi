// The timing points of an osu! beatmap file (`.osu`): reading and writing the lines of its
// `[TimingPoints]` section.

/**
 * One line of the `[TimingPoints]` section of an osu! beatmap file.
 *
 * @typedef {object} TimingPoint
 * @property {number} time Milliseconds from the start of the audio. May be negative or fractional.
 * @property {number} beatLength On an uninherited point, the length of one beat in milliseconds (positive).
 *   On an inherited point, a negative slider-velocity multiplier that carries no tempo.
 * @property {number} meter Beats per bar.
 * @property {number} sampleSet
 * @property {number} sampleIndex
 * @property {number} volume
 * @property {boolean} uninherited `true` for a point that sets the tempo (a "red line"), `false` for an
 *   inherited point (a "green line").
 * @property {number} effects Bit field: bit 0 is kiai, bit 3 omits the first bar line.
 */

const SECTION_HEADER = "[TimingPoints]";
const LINE_BREAK = /\r\n|\n|\r/;
const DECIMAL = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

/**
 * Reads the timing points of an osu! beatmap file.
 *
 * - If the text has a `[TimingPoints]` header line, the lines after the first such header are read, up to the
 *   next line that starts with `[`. If the text has no section header at all, every line is read as a timing
 *   point. If it has other section headers but no `[TimingPoints]`, the result is empty.
 * - Lines end with LF or CRLF (or a lone CR). Blank lines and lines that start with `//` are skipped.
 * - A line is `time,beatLength,meter,sampleSet,sampleIndex,volume,uninherited,effects`. It needs at least
 *   `time,beatLength`; missing trailing fields take the defaults `meter=4`, `sampleSet=0`, `sampleIndex=0`,
 *   `volume=100`, `uninherited=true`, `effects=0`. Fields after the eighth are ignored.
 *
 * @param {string} text A whole `.osu` file, or only the lines of its `[TimingPoints]` section.
 * @returns {TimingPoint[]} The points in file order.
 * @throws {SyntaxError} If a line has fewer than two fields, a field that is present is not a finite decimal
 *   number, or `uninherited` is neither `1` nor `0`. The message names the 1-based line number within `text`.
 */
export function parseTimingPoints(text) {
  if (typeof text !== "string") {
    throw new TypeError("parseTimingPoints: text must be a string");
  }
  // trim() also removes a byte order mark and the CR of a CRLF line end.
  const lines = text.split(LINE_BREAK).map((line) => line.trim());

  let start = 0;
  let end = lines.length;
  const header = lines.indexOf(SECTION_HEADER);
  if (header !== -1) {
    start = header + 1;
    const next = lines.findIndex((line, index) => index >= start && line.startsWith("["));
    if (next !== -1) end = next;
  } else if (lines.some((line) => line.startsWith("["))) {
    return [];
  }

  const points = [];
  for (let index = start; index < end; index += 1) {
    const line = lines[index];
    if (line === "" || line.startsWith("//")) continue;
    points.push(parseLine(line, index + 1));
  }
  return points;
}

/**
 * @param {string} line
 * @param {number} lineNumber
 * @returns {TimingPoint}
 */
function parseLine(line, lineNumber) {
  const fields = line.split(",").map((field) => field.trim());
  if (fields.length < 2) {
    throw new SyntaxError(`Line ${lineNumber}: a timing point needs at least time,beatLength`);
  }
  const number = (index, name, fallback) => {
    if (index >= fields.length) return fallback;
    const value = DECIMAL.test(fields[index]) ? Number(fields[index]) : NaN;
    if (!Number.isFinite(value)) {
      throw new SyntaxError(`Line ${lineNumber}: ${name} is not a finite number: ${JSON.stringify(fields[index])}`);
    }
    return value + 0; // "-0" reads as 0, so that every parsed value survives formatTimingPoints
  };
  const flag = (index, name, fallback) => {
    if (index >= fields.length) return fallback;
    if (fields[index] !== "1" && fields[index] !== "0") {
      throw new SyntaxError(`Line ${lineNumber}: ${name} is neither 1 nor 0: ${JSON.stringify(fields[index])}`);
    }
    return fields[index] === "1";
  };
  // Property order is the field order of the file, and so is the order in which fields are checked.
  return {
    time: number(0, "time"),
    beatLength: number(1, "beatLength"),
    meter: number(2, "meter", 4),
    sampleSet: number(3, "sampleSet", 0),
    sampleIndex: number(4, "sampleIndex", 0),
    volume: number(5, "volume", 100),
    uninherited: flag(6, "uninherited", true),
    effects: number(7, "effects", 0),
  };
}

/**
 * Writes timing points as the lines of a `[TimingPoints]` section: one eight-field line per point, joined with
 * `\n`, with no trailing newline and no section header.
 *
 * Numbers are written with `String(number)`, the shortest text that reads back as the same number, and
 * `uninherited` as `1` or `0`. So `parseTimingPoints(formatTimingPoints(points))` deep-equals `points` (a
 * negative zero is written as `0`). The text of a file is reproduced byte for byte only when its numbers were
 * already in that form: `500.0` comes back as `500`, and a short old-format line comes back with all eight
 * fields.
 *
 * @param {readonly TimingPoint[]} points
 * @returns {string}
 * @throws {TypeError} If a point's numeric field is not a finite number or its `uninherited` is not a boolean.
 */
export function formatTimingPoints(points) {
  if (!Array.isArray(points)) {
    throw new TypeError("formatTimingPoints: points must be an array");
  }
  return points
    .map((point, index) => {
      if (point === null || typeof point !== "object") {
        throw new TypeError(`points[${index}] must be a timing point`);
      }
      const number = (name) => {
        const value = point[name];
        if (typeof value !== "number" || !Number.isFinite(value)) {
          throw new TypeError(`points[${index}].${name} must be a finite number`);
        }
        return String(value);
      };
      if (typeof point.uninherited !== "boolean") {
        throw new TypeError(`points[${index}].uninherited must be a boolean`);
      }
      return [
        number("time"),
        number("beatLength"),
        number("meter"),
        number("sampleSet"),
        number("sampleIndex"),
        number("volume"),
        point.uninherited ? "1" : "0",
        number("effects"),
      ].join(",");
    })
    .join("\n");
}
