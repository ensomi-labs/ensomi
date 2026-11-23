import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  TRACE_SCHEMA_VERSION,
  applyEvent,
  computeGridFit,
  createInitialState,
  generateSyntheticDraft,
  projectRedLines,
  rankTroubleSpans,
} from "../index.js";

describe("Pure derived views: computeGridFit, projectRedLines, rankTroubleSpans", () => {
  it("reclassifies SoundAnchors automatically when a beat moves without mutating sounds", () => {
    const beats = [
      { id: "b0", t: 1.0, source: "model", version: 1 },
      { id: "b1", t: 1.5, source: "model", version: 1 },
      { id: "b2", t: 2.0, source: "model", version: 1 },
    ];
    const sounds = [
      { id: "s_beat", t: 1.005, emphasis: 0.9, source: "model", version: 1 },  // on_grid (5ms from b0)
      { id: "s_half", t: 1.25, emphasis: 0.7, source: "model", version: 1 },   // subdivision 1/2 (0ms)
      { id: "s_trip", t: 1.667, emphasis: 0.8, source: "model", version: 1 },  // subdivision 1/3 (~0.3ms)
      { id: "s_unexp", t: 1.30, emphasis: 0.6, source: "model", version: 1 },  // unexplained in [1.0, 1.5]
    ];

    const fitBefore = computeGridFit(sounds, beats, [], { divisors: [1, 2, 3, 4], tolMs: 15 });
    const byIdBefore = new Map(fitBefore.map((x) => [x.soundId, x]));

    assert.equal(byIdBefore.get("s_beat")?.status, "on_grid");
    assert.equal(byIdBefore.get("s_beat")?.nearestDivisor, 1);

    assert.equal(byIdBefore.get("s_half")?.status, "subdivision");
    assert.equal(byIdBefore.get("s_half")?.nearestDivisor, 2);

    assert.equal(byIdBefore.get("s_trip")?.status, "subdivision");
    assert.equal(byIdBefore.get("s_trip")?.nearestDivisor, 3);

    assert.equal(byIdBefore.get("s_unexp")?.status, "unexplained");
    assert.equal(byIdBefore.get("s_unexp")?.nearestDivisor, null);

    // Move b1 from 1.5 -> 1.30: s_unexp becomes on_grid, and s_half (1.25 in [1.0, 1.30]) becomes unexplained!
    const movedBeats = [
      beats[0],
      { ...beats[1], t: 1.30, version: 2 },
      beats[2],
    ];
    const fitAfter = computeGridFit(sounds, movedBeats, [], { divisors: [1, 2, 3, 4], tolMs: 15 });
    const byIdAfter = new Map(fitAfter.map((x) => [x.soundId, x]));

    assert.equal(byIdAfter.get("s_unexp")?.status, "on_grid");
    assert.equal(byIdAfter.get("s_unexp")?.nearestDivisor, 1);
    assert.equal(byIdAfter.get("s_half")?.status, "unexplained");
    assert.equal(byIdAfter.get("s_half")?.nearestDivisor, null);
  });

  it("does not interpolate subdivisions across a gap_start boundary", () => {
    const beats = [
      { id: "b0", t: 1.0, source: "model", version: 1 },
      { id: "b1", t: 3.0, source: "model", version: 1 },
    ];
    const sounds = [{ id: "s_mid", t: 2.0, emphasis: 0.8, source: "model", version: 1 }];
    const boundaries = [{ id: "g0", t: 1.5, kind: "gap_start", source: "user", version: 1 }];

    const withoutGap = computeGridFit(sounds, beats, []);
    assert.equal(withoutGap[0].status, "subdivision");

    const withGap = computeGridFit(sounds, beats, boundaries);
    assert.equal(withGap[0].status, "unexplained");
  });

  it("collapses jittered steady runs into minimal .osu red lines and explicitly reports barlines_not_inferred", () => {
    // 32 beats at 120 BPM with +/- 4ms deterministic jitter, followed by 32 beats at 150 BPM with +/- 4ms jitter
    const part1 = generateSyntheticDraft({ durationSec: 16.5, bpm: 120, jitterMs: 4, startSec: 0.5, seed: 7 });
    const part2Start = part1.beats[part1.beats.length - 1].t + 0.4;
    const part2 = generateSyntheticDraft({
      durationSec: part2Start + 12.8,
      bpm: 150,
      jitterMs: 4,
      startSec: part2Start,
      seed: 9,
    });

    const combinedBeats = [
      ...part1.beats,
      ...part2.beats.map((b, i) => ({ ...b, id: `p2_beat_${i}` })),
    ];
    const boundaries = part1.boundaries;

    const projection = projectRedLines(combinedBeats, boundaries, { defaultMeter: 4, jitterTolMs: 15 });

    assert.equal(projection.barPolicy, "barlines_not_inferred");
    assert.ok(projection.losses.some((l) => l.includes("barlines_not_inferred")));
    assert.ok(projection.losses.some((l) => l.includes("jitter_collapsed")));

    // Must collapse ~64 jittered beats into 2 clean red lines (not 64 jitter-spammed lines!)
    assert.equal(projection.redLines.length, 2);
    assert.ok(Math.abs(projection.redLines[0].bpm - 120) < 0.5);
    assert.ok(Math.abs(projection.redLines[1].bpm - 150) < 0.5);
    assert.equal(projection.osuTimingPoints.split("\n").length, 2);
  });

  it("ranks trouble spans transparently by low confidence, interval jumps, unexplained sounds, conflicts, and visited status", () => {
    let state = createInitialState();
    state = applyEvent(state, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 0,
      id: "audio_0",
      type: "audio_bound",
      author: { kind: "host" },
      payload: { audioId: "synth_trouble", durationSec: 16, clock: "source-audio-sec" },
    });
    state = applyEvent(state, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 1,
      id: "draft_1",
      type: "draft_loaded",
      author: { kind: "model" },
      payload: {
        beats: [
          // Clean steady span [0, 8)
          { id: "b0", t: 0.5, source: "model", conf: 0.98 },
          { id: "b1", t: 1.0, source: "model", conf: 0.98 },
          { id: "b2", t: 1.5, source: "model", conf: 0.98 },
          { id: "b3", t: 2.0, source: "model", conf: 0.98 },
          { id: "b4", t: 2.5, source: "model", conf: 0.98 },
          { id: "b5", t: 3.0, source: "model", conf: 0.98 },
          { id: "b6", t: 3.5, source: "model", conf: 0.98 },
          { id: "b6b", t: 4.0, source: "model", conf: 0.98 },
          { id: "b6c", t: 4.5, source: "model", conf: 0.98 },
          // Troubled span [8, 12): low confidence + IBI jump
          { id: "b7", t: 8.0, source: "model", conf: 0.3 },
          { id: "b8", t: 8.5, source: "model", conf: 0.35 },
          { id: "b9", t: 9.35, source: "model", conf: 0.25 }, // big IBI jump!
        ],
        boundaries: [
          { id: "g_start", t: 5.0, kind: "gap_start", source: "model" },
          { id: "g_end", t: 7.9, kind: "gap_end", source: "model" },
        ],
        sounds: [
          { id: "s_ok", t: 1.0, emphasis: 0.9, source: "model" },
          { id: "s_bad1", t: 8.81, emphasis: 0.95, source: "model" },
          { id: "s_bad2", t: 10.2, emphasis: 0.9, source: "model" },
        ],
      },
    });
    // Mark [0, 4) as visited
    state = applyEvent(state, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 2,
      id: "listen_2",
      type: "listen_effort",
      author: { kind: "host" },
      payload: { listenSec: 4, span: { t0: 0, t1: 4 } },
    });

    const ranked = rankTroubleSpans(state, { windowSec: 4 });
    assert.ok(ranked.length >= 3);
    // [8, 12) must rank #1!
    assert.deepEqual(ranked[0].span, { t0: 8, t1: 12 });
    assert.ok(ranked[0].breakdown.lowConf > 0);
    assert.ok(ranked[0].breakdown.intervalJumps > 0);
    assert.ok(ranked[0].breakdown.unexplainedSounds > 0);
    assert.equal(ranked[0].visited, false);

    // Visited clean span [0, 4) should rank lowest with score 0
    const clean = ranked.find((r) => r.span.t0 === 0 && r.span.t1 === 4);
    assert.equal(clean?.visited, true);
    assert.equal(clean?.score, 0);
  });
});
