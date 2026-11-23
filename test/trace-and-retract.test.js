import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  TRACE_SCHEMA_VERSION,
  applyEvent,
  createCheckpoint,
  createInitialState,
  foldTrace,
  getSegmentBoundaryTimes,
  parseTraceNdjson,
  resolveActiveRetractions,
  serializeState,
  serializeTraceNdjson,
  verifyCheckpoint,
} from "../index.js";

function ev(seq, type, payload, author = { kind: "user" }, id = `${type}_${seq}`) {
  return {
    schemaVersion: TRACE_SCHEMA_VERSION,
    seq,
    id,
    type,
    tsMs: 1_700_000_000_000 + seq * 100,
    author,
    payload,
  };
}

function assertPartitionAndSpanInvariants(state) {
  if (state.durationSec === 0) {
    assert.equal(state.segments.length, 0);
    assert.equal(state.spans.length, 0);
    return;
  }
  assert.ok(state.segments.length >= 1, "Expected non-empty segments when durationSec > 0");
  assert.equal(state.segments[0].t0, 0);
  assert.equal(state.segments[state.segments.length - 1].t1, state.durationSec);
  for (let i = 0; i < state.segments.length; i += 1) {
    const seg = state.segments[i];
    assert.ok(seg.t1 > seg.t0, `Segment ${seg.id} must have t1 > t0`);
    assert.ok(state.entityRevisions.has(seg.id), `Missing entityRevisions for segment ${seg.id}`);
    if (i > 0) {
      assert.equal(state.segments[i - 1].t1, seg.t0, "Contiguous segment partition broken");
    }
  }
  const segById = new Map(state.segments.map((s) => [s.id, s]));
  for (const sp of state.spans) {
    const parent = segById.get(sp.segmentId);
    assert.ok(parent, `Span ${sp.id} references missing parent segment ${sp.segmentId}`);
    assert.ok(
      sp.t0 >= parent.t0 - 1e-9 && sp.t1 <= parent.t1 + 1e-9 && sp.t1 > sp.t0,
      `Span ${sp.id} [${sp.t0}, ${sp.t1}) violates Span ⊆ Segment [${parent.t0}, ${parent.t1})`,
    );
    assert.ok(state.entityRevisions.has(sp.id), `Missing entityRevisions for span ${sp.id}`);
    if (sp.kind === "keep") assert.equal(parent.kept, false, "Redundant keep child span on kept segment");
    if (sp.kind === "free") assert.equal(parent.regime !== "free", true, "Redundant free child span on free segment");
  }
}

describe("Three-tier Segment/Range/Span reducer, atomic UnbeatCue, and selective retraction", () => {
  it("enforces partition-safe split, boundary move, and symmetric merge preservation of keep & free", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "audio_bound", { songId: "song_seg", durationSec: 20 }, { kind: "host" }),
    );
    assertPartitionAndSpanInvariants(state);
    assert.equal(state.segments.length, 1);

    // Split [0, 20) at 10 -> seg_0 [0, 10) and seg_1 [10, 20)
    state = applyEvent(
      state,
      ev(1, "edit", { op: "split_segment", segmentId: "seg_0", newSegmentId: "seg_1", timeSec: 10 }),
    );
    assert.deepEqual(getSegmentBoundaryTimes(state.segments), [10]);

    // Mark seg_0 kept: true, and seg_1 regime: "free"
    state = applyEvent(
      state,
      ev(2, "edit", [
        { op: "set_segment_keep", segmentId: "seg_0", kept: true },
        { op: "set_segment_regime", segmentId: "seg_1", regime: "free" },
      ]),
    );
    assert.equal(state.segments[0].kept, true);
    assert.equal(state.segments[1].regime, "free");

    // Merge seg_0 + seg_1:
    // Symmetric preservation MUST yield seg_0 [0, 20) with kept: false, regime: "auto",
    // PLUS child keep Span [0, 10) AND child free Span [10, 20)!
    state = applyEvent(
      state,
      ev(3, "edit", { op: "merge_segments", leftSegmentId: "seg_0", rightSegmentId: "seg_1" }),
    );
    assertPartitionAndSpanInvariants(state);
    assert.equal(state.segments.length, 1);
    assert.equal(state.segments[0].kept, false);
    assert.equal(state.segments[0].regime, "auto");

    const keepSpan = state.spans.find((s) => s.kind === "keep");
    const freeSpan = state.spans.find((s) => s.kind === "free");
    assert.ok(keepSpan);
    assert.equal(keepSpan.t0, 0);
    assert.equal(keepSpan.t1, 10);
    assert.ok(freeSpan);
    assert.equal(freeSpan.t0, 10);
    assert.equal(freeSpan.t1, 20);

    // Split at 5 (inside keepSpan [0, 10)) and move boundary to 15 (crossing freeSpan [10, 20))
    state = applyEvent(
      state,
      ev(4, "edit", { op: "split_segment", segmentId: "seg_0", newSegmentId: "seg_right", timeSec: 5 }),
    );
    assertPartitionAndSpanInvariants(state);
    state = applyEvent(
      state,
      ev(5, "edit", {
        op: "move_segment_boundary",
        leftSegmentId: "seg_0",
        rightSegmentId: "seg_right",
        timeSec: 15,
      }),
    );
    assertPartitionAndSpanInvariants(state);
    assert.deepEqual(
      serializeState(state),
      serializeState(foldTrace(state.events)),
    );
  });

  it("supports set_range_keep, set_range_free, partial unlocking, and promote_range_to_segments", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "audio_bound", { songId: "song_rng", durationSec: 16 }, { kind: "host" }),
    );
    state = applyEvent(
      state,
      ev(1, "edit", { op: "split_segment", segmentId: "seg_0", newSegmentId: "seg_1", timeSec: 8 }),
    );

    // Lock [0, 12) across seg_0 [0, 8) and half of seg_1 [8, 12)
    state = applyEvent(
      state,
      ev(2, "edit", { op: "set_range_keep", t0: 0, t1: 12, kept: true }),
    );
    assertPartitionAndSpanInvariants(state);
    // Whole seg_0 upgraded to kept: true; seg_1 has child keep span [8, 12)
    assert.equal(state.segments[0].kept, true);
    assert.equal(state.segments[1].kept, false);
    assert.equal(state.spans.length, 1);
    assert.deepEqual([state.spans[0].segmentId, state.spans[0].t0, state.spans[0].t1], ["seg_1", 8, 12]);

    // Unlock [2, 6) inside kept seg_0 -> seg_0 downgrades to kept:false with child keep spans [0, 2) and [6, 8)!
    state = applyEvent(
      state,
      ev(3, "edit", { op: "set_range_keep", t0: 2, t1: 6, kept: false }),
    );
    assertPartitionAndSpanInvariants(state);
    assert.equal(state.segments[0].kept, false);
    const seg0KeepSpans = state.spans
      .filter((s) => s.segmentId === "seg_0" && s.kind === "keep")
      .map((s) => [s.t0, s.t1]);
    assert.deepEqual(seg0KeepSpans, [
      [0, 2],
      [6, 8],
    ]);

    // Promote [3, 5) to its own "break" segment
    state = applyEvent(
      state,
      ev(4, "edit", { op: "promote_range_to_segments", t0: 3, t1: 5, regime: "break" }),
    );
    assertPartitionAndSpanInvariants(state);
    const breakSeg = state.segments.find((s) => s.regime === "break");
    assert.ok(breakSeg);
    assert.equal(breakSeg.t0, 3);
    assert.equal(breakSeg.t1, 5);
  });

  it("applies UnbeatCue atomically (standalone or capturing draft beats) and restores everything on a single retract", () => {
    const events = [
      ev(0, "audio_bound", { songId: "song_unbeat", durationSec: 12 }, { kind: "host" }),
      ev(
        1,
        "draft_loaded",
        {
          beats: [
            { id: "b_draft", timeSec: 2.01, source: "draft", revision: 1 },
            { id: "b_user", timeSec: 2.02, source: "user", revision: 1 },
          ],
        },
        { kind: "model" },
      ),
      // 1. Standalone unbeat with no beat nearby
      ev(2, "edit", { op: "apply_unbeat_cue", id: "u_empty", timeSec: 6.5 }),
      // 2. Atomic unbeat with captureRadiusSec: 0.030 (removes draft beat, keeps user beat by default)
      ev(3, "edit", { op: "apply_unbeat_cue", id: "u_cap", timeSec: 2.0, captureRadiusSec: 0.03 }),
    ];

    let state = events.reduce(applyEvent, createInitialState());
    assert.equal(state.unbeats.length, 2);
    assert.equal(state.beats.has("b_draft"), false);
    assert.equal(state.beats.has("b_user"), true);

    // Single retract of event 3 (⌘Z) restores b_draft AND removes u_cap simultaneously!
    const undoCap = ev(4, "retract", { targetEventId: "edit_3", reason: "undo unbeat" });
    state = applyEvent(state, undoCap);
    assert.equal(state.beats.has("b_draft"), true);
    assert.equal(state.beats.get("b_draft")?.timeSec, 2.01);
    assert.equal(state.unbeats.some((u) => u.id === "u_cap"), false);
    assert.equal(state.unbeats.some((u) => u.id === "u_empty"), true);

    assert.deepEqual(
      serializeState(state),
      serializeState(foldTrace([...events, undoCap])),
    );
  });

  it("maintains 100% incremental vs. full re-fold equivalence across randomized segment/span/beat/unbeat/retract traces", () => {
    let seed = 9001;
    const rand = () => {
      seed = (Math.imul(1664525, seed) + 1013904223) >>> 0;
      return seed / 0x100000000;
    };

    for (let trial = 0; trial < 25; trial += 1) {
      /** @type {any[]} */
      const trace = [
        ev(0, "audio_bound", { songId: `fuzz_${trial}`, durationSec: 24 }, { kind: "host" }),
        ev(
          1,
          "draft_loaded",
          {
            beats: [0.5, 1.0, 2.0, 4.0, 8.0, 12.0, 16.0, 20.0].map((timeSec, idx) => ({
              id: `b_${idx}`,
              timeSec,
              source: "draft",
            })),
          },
          { kind: "model" },
        ),
      ];

      let incremental = trace.reduce(applyEvent, createInitialState());

      for (let step = 2; step < 26; step += 1) {
        const roll = rand();
        let nextEvent = null;

        if (roll < 0.16 && incremental.segments.length < 6) {
          const candidates = incremental.segments.filter((s) => s.t1 - s.t0 > 1.0);
          if (candidates.length > 0) {
            const seg = candidates[Math.floor(rand() * candidates.length)];
            const splitT = Number((seg.t0 + 0.3 + rand() * (seg.t1 - seg.t0 - 0.6)).toFixed(3));
            nextEvent = ev(step, "edit", {
              op: "split_segment",
              segmentId: seg.id,
              newSegmentId: `seg_s${step}`,
              timeSec: splitT,
            });
          }
        } else if (roll < 0.3 && incremental.segments.length >= 2) {
          const idx = Math.floor(rand() * (incremental.segments.length - 1));
          const left = incremental.segments[idx];
          const right = incremental.segments[idx + 1];
          if (right.t1 - left.t0 > 1.0) {
            const moveT = Number((left.t0 + 0.25 + rand() * (right.t1 - left.t0 - 0.5)).toFixed(3));
            nextEvent = ev(step, "edit", {
              op: "move_segment_boundary",
              leftSegmentId: left.id,
              rightSegmentId: right.id,
              timeSec: moveT,
            });
          }
        } else if (roll < 0.42 && incremental.segments.length >= 2) {
          const idx = Math.floor(rand() * (incremental.segments.length - 1));
          nextEvent = ev(step, "edit", {
            op: "merge_segments",
            leftSegmentId: incremental.segments[idx].id,
            rightSegmentId: incremental.segments[idx + 1].id,
          });
        } else if (roll < 0.56) {
          const t0 = Number((rand() * 18).toFixed(3));
          const t1 = Number(Math.min(24, t0 + 0.5 + rand() * 5).toFixed(3));
          if (t1 > t0) {
            nextEvent = ev(
              step,
              "edit",
              rand() < 0.5
                ? { op: "set_range_keep", t0, t1, kept: rand() < 0.6 }
                : { op: "set_range_free", t0, t1, free: rand() < 0.6 },
            );
          }
        } else if (roll < 0.72) {
          const timeSec = Number((0.2 + rand() * 23.5).toFixed(3));
          nextEvent = ev(
            step,
            "edit",
            rand() < 0.5
              ? { op: "upsert_beat", beat: { id: `ub_${step}`, timeSec, source: "user" } }
              : {
                  op: "apply_unbeat_cue",
                  id: `uc_${step}`,
                  timeSec,
                  captureRadiusSec: 0.04,
                  removeUserBeats: rand() < 0.3,
                },
          );
        } else if (roll < 0.85 && incremental.segments.length > 0) {
          const seg = incremental.segments[Math.floor(rand() * incremental.segments.length)];
          const regimes = ["auto", "free", "break"];
          nextEvent = ev(
            step,
            "edit",
            rand() < 0.5
              ? { op: "set_segment_keep", segmentId: seg.id, kept: !seg.kept }
              : {
                  op: "set_segment_regime",
                  segmentId: seg.id,
                  regime: regimes[Math.floor(rand() * regimes.length)],
                },
          );
        } else {
          // Retract a random earlier edit or retract (skipping audio_bound/draft_loaded so durationSec stays 24)
          const retractable = trace.filter((e) => e.seq >= 2);
          if (retractable.length > 0) {
            const target = retractable[Math.floor(rand() * retractable.length)];
            // Only retract if replay stays valid (e.g. retracting a split before a merge that depended on it would throw on replay)
            const candidateRetract = ev(step, "retract", { targetEventId: target.id });
            try {
              foldTrace([...trace, candidateRetract]);
              nextEvent = candidateRetract;
            } catch {
              nextEvent = null;
            }
          }
        }

        if (!nextEvent) {
          nextEvent = ev(step, "listen_effort", { listenSec: 1.0, range: { t0: 0, t1: 4 } }, { kind: "host" });
        }

        trace.push(nextEvent);
        incremental = applyEvent(incremental, nextEvent);
        const refolded = foldTrace(trace);

        assertPartitionAndSpanInvariants(incremental);
        assert.deepEqual(
          serializeState(incremental),
          serializeState(refolded),
          `Divergence at trial ${trial}, step ${step} (${nextEvent.type})`,
        );
      }
    }
  });

  it("handles NDJSON round-trip, crash-safe truncated tail recovery, checkpoints, and redo chains", () => {
    const events = [
      ev(0, "audio_bound", { songId: "s_ndjson", durationSec: 10 }, { kind: "host" }),
      ev(1, "edit", { op: "upsert_beat", beat: { id: "b1", timeSec: 1.0, source: "user" } }),
      ev(2, "edit", { op: "move_beat", id: "b1", timeSec: 2.0 }),
      ev(3, "retract", { targetEventId: "edit_1" }),
      ev(4, "retract", { targetEventId: "retract_3" }),
    ];

    const state = foldTrace(events);
    assert.equal(state.beats.get("b1")?.timeSec, 2.0);
    assert.equal(state.beats.get("b1")?.revision, 2);
    const activeRetractions = resolveActiveRetractions(events);
    assert.equal(activeRetractions.has("edit_1"), false);
    assert.equal(activeRetractions.has("retract_3"), true);

    const ndjson = serializeTraceNdjson(events);
    assert.deepEqual(parseTraceNdjson(ndjson), events);

    const truncated = `${ndjson}{"schemaVersion":"${TRACE_SCHEMA_VERSION}","seq":5,"id":"broken`;
    assert.throws(() => parseTraceNdjson(truncated), /Corrupt NDJSON/);
    assert.deepEqual(parseTraceNdjson(truncated, { discardTruncatedTail: true }), events);

    const cp = createCheckpoint(state);
    assert.equal(verifyCheckpoint(cp, events).valid, true);
  });
});
