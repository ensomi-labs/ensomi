import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { performance } from "node:perf_hooks";

import {
  ScriptedMockProvider,
  SessionController,
  TRACE_SCHEMA_VERSION,
  applyEvent,
  computeGridFit,
  createInitialState,
  foldTrace,
  generateSyntheticDraft,
  projectRedLines,
  rankInboxSpans,
} from "../index.js";

describe("Performance benchmark (10-minute synthetic song)", () => {
  it("keeps incremental reducer edits AND SessionController edits well under 1 ms per edit on ~1,500 beats and ~1,000 sounds", async () => {
    const draft = generateSyntheticDraft({
      durationSec: 600,
      bpm: 150,
      jitterMs: 3,
      soundCount: 1000,
      segmentCount: 8,
      startSec: 0.2,
      seed: 2026,
    });

    assert.ok(draft.beats.length >= 1490 && draft.beats.length <= 1510, `got ${draft.beats.length} beats`);
    assert.equal(draft.sounds.length, 1000);
    assert.equal(draft.segments.length, 8);

    // 1. Raw reducer incremental applyEvent benchmark
    let state = createInitialState();
    state = applyEvent(state, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 0,
      id: "audio_0",
      type: "audio_bound",
      author: { kind: "host" },
      payload: { songId: "synth_10min", durationSec: 600 },
    });
    state = applyEvent(state, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 1,
      id: "draft_1",
      type: "draft_loaded",
      author: { kind: "model" },
      payload: draft,
    });

    // JIT warm-up
    for (let i = 0; i < 10; i += 1) {
      const id = `beat_${i * 10}`;
      const curr = state.beats.get(id);
      state = applyEvent(state, {
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 2 + i,
        id: `warm_${i}`,
        type: "edit",
        author: { kind: "user" },
        payload: { op: "move_beat", id, timeSec: curr.timeSec + 0.002 },
      });
    }

    const editCount = 100;
    const t0Reducer = performance.now();
    for (let i = 0; i < editCount; i += 1) {
      const targetIdx = (i * 23) % draft.beats.length;
      const beatId = `beat_${targetIdx}`;
      const curr = state.beats.get(beatId);
      state = applyEvent(state, {
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 20 + i,
        id: `edit_${i}`,
        type: "edit",
        author: { kind: "user" },
        payload: { op: "move_beat", id: beatId, timeSec: curr.timeSec + 0.001 },
      });
    }
    const avgReducerMs = (performance.now() - t0Reducer) / editCount;
    assert.ok(
      avgReducerMs < 1.0,
      `Expected average applyEvent < 1.0 ms on 10-min song, got ${avgReducerMs.toFixed(4)} ms`,
    );

    // 2. SessionController.applyEdit benchmark
    const ctrl = new SessionController({ provider: new ScriptedMockProvider() });
    await ctrl.bindAudio({ songId: "synth_ctrl_10min", durationSec: 600 });
    ctrl.loadDraft(draft);
    for (let i = 0; i < 10; i += 1) {
      ctrl.applyEdit({ op: "move_beat", id: `beat_${i * 7}`, timeSec: draft.beats[i * 7].timeSec + 0.002 });
    }

    const t0Ctrl = performance.now();
    for (let i = 0; i < editCount; i += 1) {
      const targetIdx = (i * 29) % draft.beats.length;
      const beatId = `beat_${targetIdx}`;
      ctrl.applyEdit({ op: "move_beat", id: beatId, timeSec: draft.beats[targetIdx].timeSec + 0.003 });
    }
    const avgCtrlMs = (performance.now() - t0Ctrl) / editCount;
    assert.ok(
      avgCtrlMs < 1.0,
      `Expected average SessionController.applyEdit < 1.0 ms on 10-min song, got ${avgCtrlMs.toFixed(4)} ms`,
    );

    // 3. Smoke-test derived views on the 10-minute song
    const fit = computeGridFit(state.sounds, state.beats, state.segments);
    assert.equal(fit.length, 1000);

    const redLines = projectRedLines(state.beats, state.segments);
    assert.equal(redLines.barPolicy, "barlines_not_inferred");
    assert.equal(redLines.redLines.length, 8); // 1 steady run per segment across 8 segments

    const inbox = rankInboxSpans(state, { windowSec: 4 });
    assert.ok(inbox.length > 0);
  });

  it("folds 6,000 distinct beat edits on a 10-minute song in < 80 ms with O(1) zero-container-copy replay", () => {
    const draft = generateSyntheticDraft({
      durationSec: 600,
      bpm: 150,
      jitterMs: 2,
      soundCount: 1000,
      segmentCount: 8,
      startSec: 0.2,
      seed: 4242,
    });

    /** @type {import("../index.d.ts").TraceEvent[]} */
    const events = [
      {
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 0,
        id: "audio_0",
        type: "audio_bound",
        author: { kind: "host" },
        payload: { songId: "perf_6000", durationSec: 600 },
      },
      {
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 1,
        id: "draft_1",
        type: "draft_loaded",
        author: { kind: "model" },
        payload: draft,
      },
    ];

    const beatCount = draft.beats.length;
    for (let i = 0; i < 6000; i += 1) {
      const idx = (i * 17) % beatCount;
      const baseTime = draft.beats[idx].timeSec;
      events.push({
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 2 + i,
        id: `edit_${i}`,
        type: "edit",
        author: { kind: "user" },
        payload: {
          op: "move_beat",
          id: `beat_${idx}`,
          timeSec: Number((baseTime + ((i % 9) - 4) * 0.001).toFixed(6)),
        },
      });
    }

    // Warm V8 JIT once, then measure fresh foldTrace over all 6,002 events (unfrozen plain objects!)
    const warmEvents = structuredClone(events);
    const warmed = foldTrace(warmEvents);
    assert.equal(warmed.lastSeq, 6001);

    const freshEvents = structuredClone(events);
    const t0 = performance.now();
    const folded = foldTrace(freshEvents);
    const foldMs = performance.now() - t0;

    assert.equal(folded.lastSeq, 6001);
    assert.equal(folded.beats.size, beatCount);
    assert.equal(folded.effort.actionCount, 6000);
    assert.ok(
      foldMs < 80,
      `Expected 6,000-edit foldTrace < 80 ms, got ${foldMs.toFixed(2)} ms`,
    );

    // Also measure retract re-fold on pre-frozen events (SessionController.undo() path)
    const t0Retract = performance.now();
    const afterRetract = applyEvent(folded, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 6002,
      id: "retract_6002",
      type: "retract",
      author: { kind: "user" },
      payload: { targetEventId: "edit_5999" },
    });
    const retractMs = performance.now() - t0Retract;
    assert.equal(afterRetract.retractedEventIds.has("edit_5999"), true);
    assert.ok(
      retractMs < 80,
      `Expected retract() over 6,000-edit trace < 80 ms, got ${retractMs.toFixed(2)} ms`,
    );
  });
});
