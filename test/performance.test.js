import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { performance } from "node:perf_hooks";

import {
  applyEvent,
  computeGridFit,
  createInitialState,
  foldTrace,
  projectTempoMap,
  rankInboxItems,
  SessionController,
  TRACE_SCHEMA_VERSION,
} from "../index.js";
import { generateSyntheticDraft, ScriptedMockProvider } from "../testing.js";

describe("Performance gate (10-minute synthetic song: 1,500 beats + 3,200 spanned sounds)", () => {
  it("executes single edits in < 1.5 ms and folds 6,000 events in < 25 ms", async () => {
    const durationUs = 600_000_000; // 10 minutes
    const draft = generateSyntheticDraft({
      durationUs,
      bpm: 150, // 1,500 beats (> 1,200+)
      jitterUs: 2_000,
      soundCount: 3200, // 3,200 spanned sounds (> 3,000+)
      segmentCount: 8,
      startUs: 200_000,
      seed: 20261125,
    });

    assert.ok(draft.beats.length >= 1490, `Expected >= 1200 beats, got ${draft.beats.length}`);
    assert.equal(draft.sounds.length, 3200);
    assert.equal(draft.segments.length, 8);

    // 1. Single edit benchmark on raw `applyEvent`
    let state = createInitialState();
    state = applyEvent(state, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 0,
      id: "audio_0",
      type: "audio_bound",
      author: { kind: "host" },
      payload: { audioId: "perf_10min", durationUs, sampleRateHz: 48_000 },
    });
    state = applyEvent(state, {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq: 1,
      id: "draft_1",
      type: "draft_loaded",
      author: { kind: "model" },
      payload: draft,
    });

    // Warm JIT
    for (let i = 0; i < 15; i += 1) {
      const id = `beat_${i * 10}`;
      const curr = state.beats.get(id);
      state = applyEvent(state, {
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 2 + i,
        id: `warm_${i}`,
        type: "edit",
        author: { kind: "user" },
        payload: { op: "move_beat", id, atUs: curr.atUs + 1_500 },
      });
    }

    const editIterations = 100;
    const t0Reducer = performance.now();
    for (let i = 0; i < editIterations; i += 1) {
      const idx = (i * 23) % draft.beats.length;
      const id = `beat_${idx}`;
      const curr = state.beats.get(id);
      state = applyEvent(state, {
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 20 + i,
        id: `ed_${i}`,
        type: "edit",
        author: { kind: "user" },
        payload: { op: "move_beat", id, atUs: curr.atUs + 1_000 },
      });
    }
    const avgReducerEditMs = (performance.now() - t0Reducer) / editIterations;
    assert.ok(
      avgReducerEditMs < 1.5,
      `Expected applyEvent < 1.5 ms on 1,500 beats + 3,200 sounds, got ${avgReducerEditMs.toFixed(4)} ms`,
    );

    // 2. Single edit benchmark on `SessionController.applyEdit`
    const ctrl = new SessionController({ provider: new ScriptedMockProvider() });
    await ctrl.bindAudio({ audioId: "perf_ctrl_10min", durationUs });
    ctrl.loadDraft(draft);
    for (let i = 0; i < 10; i += 1) {
      ctrl.applyEdit({
        op: "move_beat",
        id: `beat_${i * 7}`,
        atUs: draft.beats[i * 7].atUs + 2_000,
      });
    }

    const t0Ctrl = performance.now();
    for (let i = 0; i < editIterations; i += 1) {
      const idx = (i * 29) % draft.beats.length;
      const id = `beat_${idx}`;
      const curr = ctrl.getState().beats.get(id);
      ctrl.applyEdit({ op: "move_beat", id, atUs: curr.atUs + 1_000 });
    }
    const avgCtrlEditMs = (performance.now() - t0Ctrl) / editIterations;
    assert.ok(
      avgCtrlEditMs < 1.5,
      `Expected SessionController.applyEdit < 1.5 ms, got ${avgCtrlEditMs.toFixed(4)} ms`,
    );

    // 3. 6,000-event `foldTrace` benchmark (< 25 ms!)
    /** @type {import("../index.d.ts").TraceEvent[]} */
    const events = [
      {
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 0,
        id: "audio_0",
        type: "audio_bound",
        author: { kind: "host" },
        payload: { audioId: "perf_fold_6000", durationUs },
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
    for (let i = 0; i < 5998; i += 1) {
      const idx = (i * 17) % beatCount;
      const baseAtUs = draft.beats[idx].atUs;
      const offsetUs = ((i % 9) - 4) * 1_000 + 500;
      events.push({
        schemaVersion: TRACE_SCHEMA_VERSION,
        seq: 2 + i,
        id: `edit_${i}`,
        type: "edit",
        author: { kind: "user" },
        payload: {
          op: "move_beat",
          id: `beat_${idx}`,
          atUs: Math.max(0, Math.min(durationUs - 1, baseAtUs + offsetUs)),
        },
      });
    }
    assert.equal(events.length, 6000);

    // Warm V8 JIT once, then measure fresh unfrozen 6,000-event foldTrace
    const warmCopy = structuredClone(events);
    const warmed = foldTrace(warmCopy);
    assert.equal(warmed.lastSeq, 5999);

    const freshEvents = structuredClone(events);
    const t0Fold = performance.now();
    const folded = foldTrace(freshEvents);
    const foldMs = performance.now() - t0Fold;

    assert.equal(folded.lastSeq, 5999);
    assert.equal(folded.beats.size, beatCount);
    assert.equal(folded.sounds.size, 3200);
    assert.ok(
      foldMs < 25.0,
      `Expected 6,000-event foldTrace < 25 ms, got ${foldMs.toFixed(2)} ms`,
    );
    console.log(
      `[perf] 10-min song (1,500 beats, 3,200 sounds): applyEvent=${avgReducerEditMs.toFixed(3)} ms, SessionController.applyEdit=${avgCtrlEditMs.toFixed(3)} ms, 6,000-event foldTrace=${foldMs.toFixed(2)} ms`,
    );

    // Smoke-verify derived views on 1,500 beats + 3,200 sounds
    const gf = computeGridFit(folded);
    assert.equal(gf.length, 3200);
    const tm = projectTempoMap(folded);
    assert.equal(tm.points.length, 8);
    const inbox = rankInboxItems(folded);
    assert.ok(inbox.length > 0);
  });
});
