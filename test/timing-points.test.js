// Every input in this file is written by hand for the test. Nothing here comes from a real beatmap.

import assert from "node:assert/strict";
import test from "node:test";

import { formatTimingPoints, parseTimingPoints } from "../src/index.js";

const KEYS = ["time", "beatLength", "meter", "sampleSet", "sampleIndex", "volume", "uninherited", "effects"];

/** A timing point from its eight values in file order. */
function point(time, beatLength, meter, sampleSet, sampleIndex, volume, uninherited, effects) {
  return { time, beatLength, meter, sampleSet, sampleIndex, volume, uninherited, effects };
}

const SECTION_LINES = [
  "1200,500,4,2,1,60,1,0",
  "1200,-100,4,2,1,60,0,0",
  "17200,-83.3333333333333,4,2,1,70,0,1",
  "33200.5,461.538461538462,3,1,0,80,1,8",
];

const SECTION_POINTS = [
  point(1200, 500, 4, 2, 1, 60, true, 0),
  point(1200, -100, 4, 2, 1, 60, false, 0),
  point(17200, -83.3333333333333, 4, 2, 1, 70, false, 1),
  point(33200.5, 461.538461538462, 3, 1, 0, 80, true, 8),
];

const WHOLE_FILE = [
  "osu file format v14", // line 1
  "",
  "[General]",
  "AudioFilename: audio.ogg",
  "Mode: 3",
  "",
  "[Metadata]",
  "Title:Synthetic fixture",
  "Creator:nobody",
  "",
  "[Events]",
  "//Background and Video events",
  "0,0,\"background.png\",0,0",
  "",
  "[TimingPoints]", // line 15
  ...SECTION_LINES, // lines 16 to 19
  "",
  "",
  "[HitObjects]",
  "64,192,1200,1,0,0:0:0:0:",
  "192,192,1700,128,0,2200:0:0:0:0:",
  "",
].join("\n");

/** Asserts that parsing `text` throws a SyntaxError that names `lineNumber`. */
function assertSyntaxErrorAtLine(text, lineNumber) {
  assert.throws(
    () => parseTimingPoints(text),
    (error) => {
      assert.ok(error instanceof SyntaxError, `expected a SyntaxError, got ${error}`);
      assert.match(error.message, new RegExp(`^Line ${lineNumber}:`));
      return true;
    },
  );
}

test("parses the [TimingPoints] section of a whole .osu text and nothing around it", () => {
  assert.deepStrictEqual(parseTimingPoints(WHOLE_FILE), SECTION_POINTS);
});

test("returns objects with exactly the eight keys, in file order", () => {
  for (const parsed of parseTimingPoints(WHOLE_FILE)) {
    assert.deepStrictEqual(Object.keys(parsed), KEYS);
    assert.equal(typeof parsed.uninherited, "boolean");
  }
});

test("reads a bare section, with no header, as timing points", () => {
  assert.deepStrictEqual(parseTimingPoints(SECTION_LINES.join("\n")), SECTION_POINTS);
  assert.deepStrictEqual(parseTimingPoints(SECTION_LINES.join("\n") + "\n"), SECTION_POINTS);
});

test("an empty text and a section with no lines have no points", () => {
  assert.deepStrictEqual(parseTimingPoints(""), []);
  assert.deepStrictEqual(parseTimingPoints("[TimingPoints]"), []);
  assert.deepStrictEqual(parseTimingPoints("[TimingPoints]\n\n[HitObjects]\n64,192,1200,1,0,0:0:0:0:"), []);
});

test("a text with other sections but no [TimingPoints] has no points", () => {
  assert.deepStrictEqual(parseTimingPoints("[General]\nMode: 3\n\n[HitObjects]\n64,192,1200,1,0,0:0:0:0:\n"), []);
});

test("the section runs to the end of the text when no other section follows", () => {
  assert.deepStrictEqual(parseTimingPoints("[General]\nMode: 3\n[TimingPoints]\n0,500\n2000,-50"), [
    point(0, 500, 4, 0, 0, 100, true, 0),
    point(2000, -50, 4, 0, 0, 100, true, 0),
  ]);
});

test("only the first [TimingPoints] section is read", () => {
  const text = "[TimingPoints]\n0,500,4,1,0,100,1,0\n[Colours]\n[TimingPoints]\n900,300,4,1,0,100,1,0\n";
  assert.deepStrictEqual(parseTimingPoints(text), [point(0, 500, 4, 1, 0, 100, true, 0)]);
});

test("accepts CRLF line ends, and a lone CR", () => {
  assert.deepStrictEqual(parseTimingPoints(WHOLE_FILE.replace(/\n/g, "\r\n")), SECTION_POINTS);
  assert.deepStrictEqual(parseTimingPoints(SECTION_LINES.join("\r\n") + "\r\n"), SECTION_POINTS);
  assert.deepStrictEqual(parseTimingPoints(WHOLE_FILE.replace(/\n/g, "\r")), SECTION_POINTS);
  assertSyntaxErrorAtLine("0,500\r1000,500\rbad", 3);
});

test("accepts a byte order mark and spaces around a line", () => {
  assert.deepStrictEqual(parseTimingPoints("﻿[TimingPoints]\n  250 , 400 , 3,0,0,50,1,1  \n"), [
    point(250, 400, 3, 0, 0, 50, true, 1),
  ]);
});

test("skips blank lines and // comments inside the section", () => {
  const text = [
    "[TimingPoints]",
    "// first tempo",
    "",
    "0,600,4,1,0,100,1,0",
    "   ",
    "//2400,-200,4,1,0,100,0,0",
    "4800,-50,4,1,0,100,0,0",
    "",
  ].join("\n");
  assert.deepStrictEqual(parseTimingPoints(text), [
    point(0, 600, 4, 1, 0, 100, true, 0),
    point(4800, -50, 4, 1, 0, 100, false, 0),
  ]);
});

test("reads negative and fractional times and exponent notation", () => {
  assert.deepStrictEqual(parseTimingPoints("-150.25,375,4,1,0,100,1,0\n1e3,2.5e2,4,1,0,100,1,0\n-0,.5"), [
    point(-150.25, 375, 4, 1, 0, 100, true, 0),
    point(1000, 250, 4, 1, 0, 100, true, 0),
    point(0, 0.5, 4, 0, 0, 100, true, 0),
  ]);
  assert.ok(Object.is(parseTimingPoints("-0,500")[0].time, 0), "-0 reads as 0");
});

test("short old-format lines take the defaults for the missing fields", () => {
  assert.deepStrictEqual(
    parseTimingPoints(
      [
        "500,300", // 2 fields
        "600,310,3", // 3
        "700,320,5,2", // 4
        "800,330,6,2,7", // 5
        "900,340,7,1,3,45", // 6
        "1000,-75,4,1,3,45,0", // 7
      ].join("\n"),
    ),
    [
      point(500, 300, 4, 0, 0, 100, true, 0),
      point(600, 310, 3, 0, 0, 100, true, 0),
      point(700, 320, 5, 2, 0, 100, true, 0),
      point(800, 330, 6, 2, 7, 100, true, 0),
      point(900, 340, 7, 1, 3, 45, true, 0),
      point(1000, -75, 4, 1, 3, 45, false, 0),
    ],
  );
});

test("ignores fields after the eighth", () => {
  assert.deepStrictEqual(parseTimingPoints("40,250,4,1,0,100,1,0,99,extra"), [point(40, 250, 4, 1, 0, 100, true, 0)]);
});

test("a time or beatLength that is not a finite number is a SyntaxError naming the line", () => {
  assertSyntaxErrorAtLine("0,500\nabc,500\n", 2);
  assertSyntaxErrorAtLine("0,500\n\n// note\n1000,fast,4,1,0,100,1,0", 4);
  assertSyntaxErrorAtLine("NaN,500", 1);
  assertSyntaxErrorAtLine("0,Infinity", 1);
  assertSyntaxErrorAtLine("0,1e999", 1);
  assertSyntaxErrorAtLine("0,0x1F4", 1);
  assertSyntaxErrorAtLine("0,", 1);
  assertSyntaxErrorAtLine(",500", 1);
  assertSyntaxErrorAtLine("12 34,500", 1);
});

test("a line with fewer than two fields is a SyntaxError naming the line", () => {
  assertSyntaxErrorAtLine("1200", 1);
  assertSyntaxErrorAtLine("0,500\n1200\n", 2);
  assertSyntaxErrorAtLine("just some words", 1);
});

test("the line number of an error counts from the start of the given text", () => {
  const lines = WHOLE_FILE.split("\n");
  lines[17] = "17200;-83.3333333333333"; // line 18: one field
  assertSyntaxErrorAtLine(lines.join("\n"), 18);
  lines[17] = "17200,minus,4,2,1,70,0,1";
  assertSyntaxErrorAtLine(lines.join("\n"), 18);
  assertSyntaxErrorAtLine(lines.join("\r\n"), 18);
});

test("a later field that is present but not a number, or an uninherited flag other than 1 or 0, is a SyntaxError", () => {
  assertSyntaxErrorAtLine("0,500\n0,500\n10,500,four", 3);
  assertSyntaxErrorAtLine("10,500,4,1,0,loud,1,0", 1);
  assertSyntaxErrorAtLine("10,500,4,1,0,100,1,kiai", 1);
  assertSyntaxErrorAtLine("10,500,4,", 1);
  assertSyntaxErrorAtLine("10,500,4,1,0,100,yes,0", 1);
  assertSyntaxErrorAtLine("10,500,4,1,0,100,2,0", 1);
  assertSyntaxErrorAtLine("10,500,4,1,0,100,,0", 1);
});

test("an error in another section is not reported", () => {
  assert.deepStrictEqual(parseTimingPoints("[Events]\nnot,a,timing,point\n[TimingPoints]\n0,500\n[HitObjects]\nx"), [
    point(0, 500, 4, 0, 0, 100, true, 0),
  ]);
});

test("text that is not a string is a TypeError", () => {
  assert.throws(() => parseTimingPoints(undefined), TypeError);
  assert.throws(() => parseTimingPoints(new Uint8Array(4)), TypeError);
});

test("formats one eight-field line per point, joined with \\n, with no header and no trailing newline", () => {
  assert.equal(formatTimingPoints(SECTION_POINTS), SECTION_LINES.join("\n"));
  assert.equal(formatTimingPoints([point(-150.25, 375, 4, 1, 0, 100, true, 9)]), "-150.25,375,4,1,0,100,1,9");
  assert.equal(formatTimingPoints([]), "");
});

test("round trip: parsing the formatted text gives back the same points", () => {
  const points = [
    point(-2769.23076923077, 461.538461538462, 3, 1, 0, 100, true, 0),
    point(-10, -100, 4, 1, 0, 100, false, 0),
    point(0, -83.3333333333333, 4, 2, 5, 35, false, 1),
    point(1200.5, 333.333333333333, 7, 3, 12, 0, true, 8),
    point(98765.4321, 0.1 + 0.2, 4, 0, 0, 100, true, 9),
    point(1e21, 1e-7, 255, 0, 0, 100, true, 0),
    point(123456789.125, -1.5e-9, 1, 0, 0, 100, false, 0),
    point(Number.MAX_SAFE_INTEGER, Number.MIN_VALUE, 4, 0, 0, 100, true, 0),
  ];
  assert.deepStrictEqual(parseTimingPoints(formatTimingPoints(points)), points);
  assert.deepStrictEqual(parseTimingPoints(`[TimingPoints]\n${formatTimingPoints(points)}\n`), points);
});

test("round trip: the points of a parsed text survive formatting and parsing again", () => {
  for (const text of [WHOLE_FILE, "500,300\n600,-50,3\n-0,461.538461538462,4,1,0,100,1,0"]) {
    const points = parseTimingPoints(text);
    assert.deepStrictEqual(parseTimingPoints(formatTimingPoints(points)), points);
  }
});

test("text is reproduced byte for byte only when its numbers were already in canonical form", () => {
  const canonical = "0,461.538461538462,4,1,0,100,1,0\n5538.5,-100,4,1,0,100,0,0\n9230.75,-83.3333333333333,3,2,1,55,0,1";
  assert.equal(formatTimingPoints(parseTimingPoints(canonical)), canonical);

  // The same values written another way come back in canonical form, and short lines come back whole.
  assert.equal(formatTimingPoints(parseTimingPoints("0.0,500.00,+4,01,0,100,1,0")), "0,500,4,1,0,100,1,0");
  assert.equal(formatTimingPoints(parseTimingPoints("1e3,2.5e2")), "1000,250,4,0,0,100,1,0");
  assert.equal(formatTimingPoints(parseTimingPoints("7,333.33333333333331483")), "7,333.3333333333333,4,0,0,100,1,0");
});

test("formatting rejects a value that could not be read back", () => {
  const good = point(0, 500, 4, 1, 0, 100, true, 0);
  assert.throws(() => formatTimingPoints([good, { ...good, time: NaN }]), {
    name: "TypeError",
    message: /points\[1\]\.time/,
  });
  assert.throws(() => formatTimingPoints([{ ...good, beatLength: Infinity }]), TypeError);
  assert.throws(() => formatTimingPoints([{ ...good, volume: "100" }]), TypeError);
  assert.throws(() => formatTimingPoints([{ ...good, uninherited: 1 }]), TypeError);
  assert.throws(() => formatTimingPoints([{ time: 0, beatLength: 500 }]), TypeError);
  assert.throws(() => formatTimingPoints([null]), TypeError);
  assert.throws(() => formatTimingPoints("0,500"), TypeError);
});
