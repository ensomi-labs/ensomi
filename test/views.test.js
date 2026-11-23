import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  TRACE_SCHEMA_VERSION,
  applyEvent,
  computeGridFit,
  createInitialState,
  generateSyntheticDraft,
  projectRedLines,
  rankInboxSpans,
} from "../index.js";

function ev(seq, type, payload, author = { kind: "user" }) {
  return {
    schemaVersion: TRACE_SCHEMA_VERSION,
    seq,
    id: `${type}_${seq}`,
    type,
    author,
    payload,
  };
}

function reconstructGridFromRedLines(redLines, maxSec) {
  const epsMs = 1e-6;
  const maxMs = maxSec * 1000;
  /** @type {number[]} */
  const beatsSec = [];
  for (let i = 0; i < redLines.length; i += 1) {
    const rl = redLines[i];
    const stopMs = i + 1 < redLines.length ? redLines[i + 1].offsetMs - epsMs : maxMs + epsMs;
    for (let k = 0; ; k += 1) {
      const tMs = rl.offsetMs + k * rl.beatLengthMs;
      if (tMs > stopMs) break;
      beatsSec.push(Math.round(tMs) / 1000);
    }
  }
  return beatsSec;
}

describe("Break-aware derived views: computeGridFit, projectRedLines, rankInboxSpans", () => {
  it("computes signed residualMs with [1,2,3,4,6] / 12ms defaults and never interpolates across Segment boundaries, Break segments, or IBIs > 2.5s", () => {
    const segments = [
      { id: "seg_a", t0: 0, t1: 2.0, regime: "auto", kept: false, source: "draft", revision: 1 },
      { id: "seg_brk", t0: 2.0, t1: 5.0, regime: "break", kept: false, source: "draft", revision: 1 },
      { id: "seg_b", t0: 5.0, t1: 12.0, regime: "auto", kept: false, source: "draft", revision: 1 },
    ];
    const beats = [
      { id: "b0", timeSec: 0.5, source: "draft", revision: 1 },
      { id: "b1", timeSec: 1.0, source: "draft", revision: 1 },
      { id: "b2", timeSec: 1.5, source: "draft", revision: 1 },
      // Across break -> starts at 5.0, then a 3.0s gap (> 2.5s maxInterpolatedIbiSec) to 8.0
      { id: "b3", timeSec: 5.0, source: "draft", revision: 1 },
      { id: "b4", timeSec: 8.0, source: "draft", revision: 1 },
      { id: "b5", timeSec: 8.5, source: "draft", revision: 1 },
    ];
    const sounds = [
      { id: "s_early", timeSec: 0.992, strength: 0.8, source: "draft", revision: 1 }, // -8ms on_grid
      { id: "s_late_13ms", timeSec: 1.013, strength: 0.8, source: "draft", revision: 1 }, // +13ms > default 12ms tolMs!
      { id: "s_eighth", timeSec: 0.5625, strength: 0.8, source: "draft", revision: 1 }, // 1/8th at 62.5ms -> unexplained since 8 excluded!
      { id: "s_sixth", timeSec: 0.5833, strength: 0.8, source: "draft", revision: 1 }, // 1/6th -> subdivision (divisor 6)
      { id: "s_cross_seg", timeSec: 1.75, strength: 0.9, source: "draft", revision: 1 }, // between 1.5 (seg_a) and 5.0 (seg_b) -> unexplained
      { id: "s_in_break", timeSec: 3.25, strength: 0.9, source: "draft", revision: 1 }, // inside break -> unexplained
      { id: "s_long_ibi", timeSec: 6.5, strength: 0.9, source: "draft", revision: 1 }, // midpoint of 3.0s IBI [5.0, 8.0] (> 2.5s) -> unexplained!
      { id: "s_valid_half", timeSec: 8.254, strength: 0.9, source: "draft", revision: 1 }, // +4ms from 8.25 -> subdivision (divisor 2)
    ];

    const fit = computeGridFit(sounds, beats, segments);
    const byId = new Map(fit.map((x) => [x.soundId, x]));

    assert.equal(byId.get("s_early")?.status, "on_grid");
    assert.equal(byId.get("s_early")?.residualMs, -8);

    assert.equal(byId.get("s_late_13ms")?.status, "unexplained");
    assert.equal(byId.get("s_eighth")?.status, "unexplained");

    assert.equal(byId.get("s_sixth")?.status, "subdivision");
    assert.equal(byId.get("s_sixth")?.nearestDivisor, 6);

    assert.equal(byId.get("s_cross_seg")?.status, "unexplained");
    assert.equal(byId.get("s_in_break")?.status, "unexplained");
    assert.equal(byId.get("s_long_ibi")?.status, "unexplained");

    assert.equal(byId.get("s_valid_half")?.status, "subdivision");
    assert.equal(byId.get("s_valid_half")?.nearestDivisor, 2);
    assert.equal(byId.get("s_valid_half")?.residualMs, 4);
  });

  it("projects exact transition-beat red lines, preserves free rubato, and never emits slow red lines across Break or >2.5s gaps", () => {
    const segments = [
      { id: "seg_intro", t0: 0, t1: 3.0, regime: "auto", kept: false, source: "draft", revision: 1 },
      { id: "seg_break", t0: 3.0, t1: 7.0, regime: "break", kept: false, source: "draft", revision: 1 },
      { id: "seg_free", t0: 7.0, t1: 9.0, regime: "free", kept: false, source: "draft", revision: 1 },
      { id: "seg_drop", t0: 9.0, t1: 12.0, regime: "auto", kept: false, source: "draft", revision: 1 },
    ];
    const beats = [
      // Intro at 120 BPM (500ms): 0.5, 1.0, 1.5, 2.0
      { id: "i0", timeSec: 0.5, source: "draft", revision: 1 },
      { id: "i1", timeSec: 1.0, source: "draft", revision: 1 },
      { id: "i2", timeSec: 1.5, source: "draft", revision: 1 },
      { id: "i3", timeSec: 2.0, source: "draft", revision: 1 },
      // Break [3.0, 7.0): 5.0s silence between 2.0 and 7.0!
      // Free accelerando [7.0, 9.0): 7.0 -> 7.60 (600ms) -> 8.10 (500ms) -> 8.55 (450ms) -> seamlessly meets 9.00 (450ms)
      { id: "f0", timeSec: 7.0, source: "draft", revision: 1 },
      { id: "f1", timeSec: 7.6, source: "draft", revision: 1 },
      { id: "f2", timeSec: 8.1, source: "draft", revision: 1 },
      { id: "f3", timeSec: 8.55, source: "draft", revision: 1 },
      // Steady drop at 150 BPM (400ms): 9.0, 9.4, 9.8, 10.2
      { id: "d0", timeSec: 9.0, source: "draft", revision: 1 },
      { id: "d1", timeSec: 9.4, source: "draft", revision: 1 },
      { id: "d2", timeSec: 9.8, source: "draft", revision: 1 },
      { id: "d3", timeSec: 10.2, source: "draft", revision: 1 },
    ];

    const proj = projectRedLines(beats, segments);
    assert.equal(proj.barPolicy, "barlines_not_inferred");

    // ZERO slow red lines (every beatLengthMs must be <= 600ms, never 5000ms across the break!)
    for (const rl of proj.redLines) {
      assert.ok(rl.beatLengthMs <= 600, `Unexpected slow red line: ${JSON.stringify(rl)}`);
      assert.ok(rl.bpm >= 100);
    }

    // Fresh red line starts right at 7000ms after the break, and drop starts right at 9000ms (never one beat late!)
    assert.ok(proj.redLines.some((rl) => rl.offsetMs === 7000 && rl.kind === "free_pulse"));
    assert.ok(proj.redLines.some((rl) => rl.offsetMs === 9000 && Math.abs(rl.bpm - 150) < 0.01));

    // Also verify exact transition-beat grid reconstruction inside a single auto segment
    const shiftBeats = [0, 0.5, 1.0, 1.4, 1.8, 2.2].map((timeSec, idx) => ({
      id: `sb_${idx}`,
      timeSec,
      source: "draft",
      revision: 1,
    }));
    const shiftProj = projectRedLines(shiftBeats, []);
    assert.deepEqual(reconstructGridFromRedLines(shiftProj.redLines, 2.2), [
      0, 0.5, 1.0, 1.4, 1.8, 2.2,
    ]);

    // Collapse jittered steady run into a single red line
    const jittered = generateSyntheticDraft({ durationSec: 16, bpm: 128, jitterMs: 4, seed: 11 });
    const jitProj = projectRedLines(jittered.beats, jittered.segments);
    assert.equal(jitProj.redLines.length, 1);
  });

  it("ranks Inbox spans strictly inside single segments and excludes all keep Segments and keep Spans", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "audio_bound", { songId: "song_inbox", durationSec: 16 }, { kind: "host" }),
    );
    state = applyEvent(
      state,
      ev(
        1,
        "draft_loaded",
        {
          segments: [
            { id: "seg_kept", t0: 0, t1: 4, regime: "auto", kept: true, source: "user" },
            { id: "seg_work", t0: 4, t1: 12, regime: "auto", kept: false, source: "draft" },
            { id: "seg_break", t0: 12, t1: 16, regime: "break", kept: false, source: "draft" },
          ],
          beats: [
            // Even if seg_kept has low-conf beats, it MUST be excluded!
            { id: "k0", timeSec: 1.0, confidence: 0.2, source: "draft" },
            // Clean sub-range [4, 8) that we'll lock via a child keep Span [4, 8)
            { id: "w0", timeSec: 4.5, confidence: 0.3, source: "draft" },
            // Troubled unlocked sub-range [8, 12): low confidence + sudden tempo jump + off-grid onset
            { id: "t0", timeSec: 8.5, confidence: 0.35, source: "draft" },
            { id: "t1", timeSec: 9.0, confidence: 0.3, source: "draft" },
            { id: "t2", timeSec: 9.85, confidence: 0.25, source: "draft" },
          ],
          sounds: [
            { id: "s_off1", timeSec: 8.71, strength: 0.95, source: "draft" },
            { id: "s_off2", timeSec: 10.33, strength: 0.9, source: "draft" },
          ],
        },
        { kind: "model" },
      ),
    );
    // Lock child keep Span [4, 8) inside seg_work
    state = applyEvent(
      state,
      ev(2, "edit", { op: "set_range_keep", t0: 4, t1: 8, kept: true }),
    );

    const inbox = rankInboxSpans(state, { windowSec: 4 });
    assert.ok(inbox.length >= 1);
    assert.equal(inbox[0].segmentId, "seg_work");
    assert.equal(inbox[0].t0, 8);
    assert.equal(inbox[0].t1, 12);
    assert.equal(inbox[0].priority, "high");
    assert.ok(typeof inbox[0].reason === "string" && inbox[0].reason.length > 0);

    // Every returned span is strictly inside a single segment and disjoint from [0, 8) and [12, 16)
    const segById = new Map(state.segments.map((s) => [s.id, s]));
    for (const item of inbox) {
      const parent = segById.get(item.segmentId);
      assert.ok(parent && !parent.kept);
      assert.ok(item.t0 >= parent.t0 && item.t1 <= parent.t1);
      assert.ok(item.t0 >= 8 && item.t1 <= 12);
    }
  });
});
