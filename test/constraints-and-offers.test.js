import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  TRACE_SCHEMA_VERSION,
  applyEvent,
  compileConstraints,
  computeSemanticKey,
  createInitialState,
  getRangeEntityIds,
  isOfferSuppressed,
  validatePatchOffer,
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

describe("Constraint IR compiler and conflict-first PatchOffer semantics", () => {
  it("compiles all 8 active StatementKinds to the 4-form Constraint IR and omits inactive statements", () => {
    const statements = [
      { id: "s1", kind: "sound_at", t: 1.2, value: { emphasis: 0.9 }, active: true, version: 1, createdAtSeq: 1 },
      { id: "s2", kind: "steady", span: { t0: 0, t1: 8 }, value: { bpm: 120 }, active: true, version: 1, createdAtSeq: 2 },
      { id: "s3", kind: "free", span: { t0: 8, t1: 12 }, active: true, version: 1, createdAtSeq: 3 },
      { id: "s4", kind: "beat_at", t: 4.0, active: true, version: 1, createdAtSeq: 4 },
      { id: "s5", kind: "beat_run", span: { t0: 12, t1: 16 }, value: { bpm: 140 }, active: true, version: 1, createdAtSeq: 5 },
      { id: "s6", kind: "forbid_beat", t: 6.25, active: true, version: 1, createdAtSeq: 6 },
      { id: "s7", kind: "no_pulse", span: { t0: 16, t1: 20 }, active: true, version: 1, createdAtSeq: 7 },
      { id: "s8", kind: "keep", span: { t0: 0, t1: 4 }, active: true, version: 1, createdAtSeq: 8 },
      { id: "s9", kind: "keep", span: { t0: 20, t1: 24 }, active: false, version: 2, createdAtSeq: 9 },
    ];

    const compiled = compileConstraints(statements);
    assert.equal(compiled.length, 8);
    assert.deepEqual(
      compiled.map((c) => [c.statementKind, c.form]),
      [
        ["sound_at", "observe"],
        ["steady", "clamp"],
        ["free", "clamp"],
        ["beat_at", "clamp"],
        ["beat_run", "clamp"],
        ["forbid_beat", "forbid"],
        ["no_pulse", "forbid"],
        ["keep", "freeze"],
      ],
    );
  });

  it("computes deterministic semanticKey independent of ephemeral entity IDs", () => {
    const scope = { t0: 2.0, t1: 4.0 };
    const key1 = computeSemanticKey(scope, {
      upsertBeats: [
        { id: "ephemeral_a_1", t: 2.5, source: "model", version: 1 },
        { id: "ephemeral_a_2", t: 3.0, source: "model", version: 1 },
      ],
      removeBeatIds: ["orig_2", "orig_1"],
    });
    const key2 = computeSemanticKey(scope, {
      upsertBeats: [
        { id: "fresh_uuid_99", t: 3.0, source: "model", version: 5 },
        { id: "fresh_uuid_42", t: 2.5, source: "model", version: 2 },
      ],
      removeBeatIds: ["orig_1", "orig_2"],
    });
    assert.equal(key1, key2);

    const keyDifferent = computeSemanticKey(scope, {
      upsertBeats: [
        { id: "ephemeral_a_1", t: 2.6, source: "model" },
        { id: "ephemeral_a_2", t: 3.0, source: "model" },
      ],
      removeBeatIds: ["orig_1", "orig_2"],
    });
    assert.notEqual(key1, keyDifferent);
  });

  it("detects version_mismatch, range_changed, keep/forbid constraint_violation, and sibling_conflict", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "draft_loaded", {
        beats: [
          { id: "b1", t: 1.0, source: "model", version: 1 },
          { id: "b2", t: 1.5, source: "model", version: 1 },
          { id: "b3", t: 5.0, source: "model", version: 1 },
        ],
      }, { kind: "model" }),
    );

    const scope = { t0: 0.5, t1: 2.5 };
    const initialRangeIds = getRangeEntityIds(state, scope);

    // 1. Version mismatch after user edits b1
    const offerVersioned = {
      id: "off_ver",
      batchId: "b_ver",
      providerId: "mock",
      providerKind: "mock",
      generation: 1,
      basisSeq: 0,
      role: "rewrite",
      scopeKind: "local",
      readScope: scope,
      writeScope: scope,
      ops: {
        upsertBeats: [{ id: "b1", t: 1.05, source: "model" }],
        expectedVersions: { b1: 1 },
        expectedRangeEntityIds: initialRangeIds,
      },
      summary: "Adjust b1",
      consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0 },
      conflicts: [],
      semanticKey: "sk:ver",
      status: "pending",
    };

    assert.equal(validatePatchOffer(state, offerVersioned).length, 0);

    const stateMoved = applyEvent(state, ev(1, "edit", { op: "move_beat", id: "b1", t: 1.1 }));
    const verConflicts = validatePatchOffer(stateMoved, offerVersioned);
    assert.ok(verConflicts.some((c) => c.kind === "version_mismatch" && c.entityIds?.includes("b1")));

    // 2. Range membership change when user inserts a new beat inside writeScope
    const stateInserted = applyEvent(
      state,
      ev(1, "edit", { op: "upsert_beat", beat: { id: "b_new", t: 2.0, source: "user" } }),
    );
    const rangeConflicts = validatePatchOffer(stateInserted, offerVersioned);
    assert.ok(rangeConflicts.some((c) => c.kind === "range_changed" && c.entityIds?.includes("b_new")));

    // 3. Constraint violation: active keep (freeze) and forbid_beat
    let stateConstrained = applyEvent(
      state,
      ev(1, "statement", {
        action: "upsert",
        statement: { id: "st_keep", kind: "keep", span: { t0: 0.8, t1: 1.6 } },
      }),
    );
    stateConstrained = applyEvent(
      stateConstrained,
      ev(2, "statement", {
        action: "upsert",
        statement: { id: "st_forbid", kind: "forbid_beat", t: 5.25 },
      }),
    );

    const freezeConflicts = validatePatchOffer(stateConstrained, offerVersioned);
    assert.ok(
      freezeConflicts.some(
        (c) => c.kind === "constraint_violation" && c.statementIds?.includes("st_keep"),
      ),
    );

    const forbidOffer = {
      ...offerVersioned,
      id: "off_forbid",
      writeScope: { t0: 5.0, t1: 6.0 },
      ops: { upsertBeats: [{ id: "b_bad", t: 5.255, source: "model" }] },
    };
    const forbidConflicts = validatePatchOffer(stateConstrained, forbidOffer);
    assert.ok(
      forbidConflicts.some(
        (c) => c.kind === "constraint_violation" && c.statementIds?.includes("st_forbid"),
      ),
    );

    // 4. Sibling conflict: mutually conflicting options in one batchId
    const sibA = {
      ...offerVersioned,
      id: "sib_a",
      batchId: "batch_sib",
      optionIndex: 0,
      ops: { upsertBeats: [{ id: "b_opt_a", t: 2.1, source: "model" }] },
      semanticKey: computeSemanticKey(scope, { upsertBeats: [{ id: "b_opt_a", t: 2.1, source: "model" }] }),
    };
    const sibB = {
      ...offerVersioned,
      id: "sib_b",
      batchId: "batch_sib",
      optionIndex: 1,
      ops: { upsertBeats: [{ id: "b_opt_b", t: 2.2, source: "model" }] },
      semanticKey: computeSemanticKey(scope, { upsertBeats: [{ id: "b_opt_b", t: 2.2, source: "model" }] }),
    };

    let sibState = applyEvent(state, ev(1, "offer_batch", { batchId: "batch_sib", offers: [sibA, sibB] }, { kind: "model" }));
    sibState = applyEvent(sibState, ev(2, "offer_accept", { offerId: "sib_a" }));

    assert.equal(sibState.offers.get("sib_a")?.status, "accepted");
    assert.equal(sibState.offers.get("sib_b")?.status, "superseded");
    const sibConflicts = validatePatchOffer(sibState, sibState.offers.get("sib_b"));
    assert.ok(sibConflicts.some((c) => c.kind === "sibling_conflict"));
  });

  it("suppresses rejected semanticKey and clears suppression on overlapping edit or statement", () => {
    const scope = { t0: 4.0, t1: 6.0 };
    const ops1 = { upsertBeats: [{ id: "temp_1", t: 4.5, source: "model" }] };
    const ops2 = { upsertBeats: [{ id: "temp_999", t: 4.5, source: "model" }] }; // different ephemeral ID, same semantics
    const offer1 = {
      id: "off_1",
      batchId: "b1",
      providerId: "mock",
      providerKind: "mock",
      generation: 1,
      basisSeq: 0,
      role: "rewrite",
      scopeKind: "local",
      readScope: scope,
      writeScope: scope,
      ops: ops1,
      summary: "Proposal",
      consequences: { beatsAdded: 1, beatsMoved: 0, beatsRemoved: 0 },
      conflicts: [],
      semanticKey: computeSemanticKey(scope, ops1),
      status: "pending",
    };

    let state = createInitialState();
    state = applyEvent(state, ev(0, "offer_batch", { batchId: "b1", offers: [offer1] }, { kind: "model" }));
    state = applyEvent(state, ev(1, "offer_reject", { offerId: "off_1", reason: "wrong phase" }));

    const offer2 = { ...offer1, id: "off_2", batchId: "b2", generation: 2, ops: ops2, semanticKey: computeSemanticKey(scope, ops2) };
    assert.equal(offer1.semanticKey, offer2.semanticKey);
    assert.equal(isOfferSuppressed(state, offer2), true);

    // Non-overlapping edit does NOT clear suppression
    state = applyEvent(state, ev(2, "edit", { op: "upsert_beat", beat: { id: "far_b", t: 1.0, source: "user" } }));
    assert.equal(isOfferSuppressed(state, offer2), true);

    // Overlapping edit DOES clear suppression
    state = applyEvent(state, ev(3, "edit", { op: "upsert_beat", beat: { id: "near_b", t: 5.0, source: "user" } }));
    assert.equal(isOfferSuppressed(state, offer2), false);

    // Re-reject and verify overlapping statement also clears suppression
    state = applyEvent(state, ev(4, "offer_batch", { batchId: "b2", offers: [offer2] }, { kind: "model" }));
    state = applyEvent(state, ev(5, "offer_reject", { offerId: "off_2" }));
    assert.equal(isOfferSuppressed(state, offer2), true);

    state = applyEvent(
      state,
      ev(6, "statement", {
        action: "upsert",
        statement: { id: "st_steady", kind: "steady", span: { t0: 5.5, t1: 8.0 } },
      }),
    );
    assert.equal(isOfferSuppressed(state, offer2), false);
  });
});
