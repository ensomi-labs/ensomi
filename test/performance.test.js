import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { performance } from "node:perf_hooks";

import {
  TRACE_SCHEMA_VERSION,
  applyEvent,
  computeGridFit,
  createInitialState,
  generateSyntheticDraft,
  projectRedLines,
  rankTroubleSpans,
} from "../index.js";

describe("Performance smoke test (10-minute synthetic song)", () => {
  it("keeps incremental edits under 10 ms per edit on ~1,500 beats and ~1,000 sounds", () => {
    // 10-minute (600s) song at 150 BPM -> 1,500 beats + 1,000 sound anchors
    const draft = generateSyntheticDraft({
      durationSec: 600,
      bpm: 150,
      jitterMs: 3,
      soundCount: 1000,
      startSec: 0.2,
      seed: 2026,
    });

    assert.ok(draft.beats.length >= 1490 && draft.beats.length <= 1510, `got ${draft.beats.length} beats`);
    assert.equal(draft.sounds.length, 1000);

    let state = createInitialState();
    state = applyEvent(state, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 0,
      id: "audio_0",
      type: "audio_bound",
      author: { kind: "host" },
      payload: { audioId: "synth_10min", durationSec: 600, clock: "source-audio-sec" },
    });
    state = applyEvent(state, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 1,
      id: "draft_1",
      type: "draft_loaded",
      author: { kind: "model" },
      payload: draft,
    });

    // Warm up JIT with 5 edits
    for (let i = 0; i < 5; i += 1) {
      state = applyEvent(state, {
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 2 + i,
        id: `warm_${i}`,
        type: "edit",
        author: { kind: "user" },
        payload: { op: "move_beat", id: `beat_${i * 10}`, t: draft.beats[i * 10].t + 0.005 },
      });
    }

    // Measure 50 incremental edits
    const editCount = 50;
    const t0 = performance.now();
    for (let i = 0; i < editCount; i += 1) {
      const targetIdx = (i * 23) % draft.beats.length;
      const beatId = `beat_${targetIdx}`;
      const curr = state.beats.get(beatId);
      state = applyEvent(state, {
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 10 + i,
        id: `edit_${i}`,
        type: "edit",
        author: { kind: "user" },
        payload: { op: "move_beat", id: beatId, t: curr.t + 0.002 },
      });
    }
    const elapsedMs = performance.now() - t0;
    const avgMs = elapsedMs / editCount;

    assert.ok(
      avgMs < 10,
      `Expected average incremental edit < 10ms on 1,500 beats + 1,000 sounds, got ${avgMs.toFixed(3)}ms`,
    );

    // Also smoke-test derived views on the full 10-minute song
    const fit = computeGridFit(state.sounds, state.beats, state.boundaries);
    assert.equal(fit.length, 1000);

    const redLines = projectRedLines(state.beats, state.boundaries);
    assert.equal(redLines.barPolicy, "barlines_not_inferred");
    assert.equal(redLines.redLines.length, 1); // 1,500 jittered 150 BPM beats collapse to 1 red line!

    const ranked = rankTroubleSpans(state, { windowSec: 4 });
    assert.equal(ranked.length, 150);
  });
});
