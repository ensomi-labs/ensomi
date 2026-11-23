import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  EnsomiError,
  ScriptedMockProvider,
  SessionController,
  TRACE_SCHEMA_VERSION,
  applyEvent,
  createCheckpoint,
  createInitialState,
  foldTrace,
  parseTraceNdjson,
  projectRedLines,
  rankInboxSpans,
  serializeState,
  validatePatchOffer,
  verifyCheckpoint,
} from "../index.js";

const rootDir = dirname(dirname(fileURLToPath(import.meta.url)));

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

const FORBIDDEN_USER_FACING_PATTERNS = [
  /\bmock\b/i,
  /\bWIP\b/,
  /\bexperimental\b/i,
  /reserved for N1/i,
  /\bsk:[0-9a-f]/i,
  /\bsha256:/i,
  /\bbatch_[A-Za-z0-9_]+/,
  /\boffer_[A-Za-z0-9_]+/,
  /\bseg_[A-Za-z0-9_]+/,
  /\bbeat_[A-Za-z0-9_]+/,
  /\bsnd_[A-Za-z0-9_]+/,
  /\bunbeat_[A-Za-z0-9_]+/,
];

function assertCleanHumanCopy(text, label) {
  assert.equal(typeof text, "string", `${label} must be a string`);
  for (const pat of FORBIDDEN_USER_FACING_PATTERNS) {
    assert.equal(
      pat.test(text),
      false,
      `Forbidden internal/dev token matched ${pat} in ${label}: "${text}"`,
    );
  }
}

describe("Astra xhigh regressions and human-safe copy audit", () => {
  it("reopening session with fresh provider preserves accepted beats, never collides IDs, and rejects duplicate offer IDs", async () => {
    const provider1 = new ScriptedMockProvider();
    const ctrl1 = new SessionController({ provider: provider1 });
    await ctrl1.bindAudio({ songId: "reopen_song", durationSec: 12 });
    ctrl1.loadDraft({ beats: [], sounds: [] });

    const r1 = await ctrl1.recompute({ scope: { t0: 0, t1: 2 }, reason: "local_rewrite" });
    assert.equal(r1.status, "applied");
    assert.equal(ctrl1.acceptOffer(r1.offers[0].id).ok, true);

    const savedTrace = parseTraceNdjson(ctrl1.exportNdjson());
    const provider2 = new ScriptedMockProvider();
    const ctrl2 = new SessionController({ provider: provider2, initialEvents: savedTrace });

    const r2 = await ctrl2.recompute({ scope: { t0: 4, t1: 6 }, reason: "local_rewrite" });
    assert.equal(r2.status, "applied");
    assert.notEqual(r2.offers[0].id, r1.offers[0].id);
    assert.equal(ctrl2.acceptOffer(r2.offers[0].id).ok, true);

    const beatTimes = Array.from(ctrl2.getState().beats.values())
      .map((b) => b.timeSec)
      .sort((a, b) => a - b);
    assert.deepEqual(beatTimes, [0.25, 0.75, 4.25, 4.75]);

    assert.throws(
      () =>
        applyEvent(
          ctrl2.getState(),
          ev(
            ctrl2.getState().lastSeq + 1,
            "offer_batch",
            { batchId: "dup_b", offers: [{ ...r1.offers[0], status: "valid" }] },
            { kind: "model" },
          ),
        ),
      (err) => err instanceof EnsomiError && err.code === "DUPLICATE_OFFER_ID",
    );
  });

  it("ABA undo-then-re-edit at the same integer revision cannot allow an obsolete offer to accept", async () => {
    const provider = new ScriptedMockProvider({
      script: [
        async ({ state, request }) => ({
          batchId: "b_aba",
          offers: [
            {
              id: "off_aba",
              batchId: "b_aba",
              providerId: "ensomi.scripted",
              providerKind: "mock",
              generation: request.generation,
              basisSeq: request.basisSeq,
              expectedRevisions: { b: state.entityRevisions.get("b") },
              writeScopes: [{ segmentId: "seg_0", t0: 0, t1: 2 }],
              scopeKind: "local",
              title: "Refine beat timing",
              consequenceSummary: "Nudges 1 beat (up to 50 ms)",
              advisoryNotes: [],
              ops: { upsertBeats: [{ id: "b", timeSec: 0.25, source: "offer" }] },
              consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0, maxShiftMs: 50, segmentChanges: 0 },
              conflicts: [],
              semanticKey: "sk:aba",
              status: "valid",
            },
          ],
        }),
      ],
    });

    const ctrl = new SessionController({ provider });
    await ctrl.bindAudio({ songId: "song_aba", durationSec: 4 });
    ctrl.loadDraft({ beats: [{ id: "b", timeSec: 0.1, source: "draft", revision: 1 }] });

    const move1 = ctrl.applyEdit({ op: "move_beat", id: "b", timeSec: 0.2 });
    assert.equal(ctrl.getState().beats.get("b")?.revision, 2);

    const rec = await ctrl.recompute({ scope: { t0: 0, t1: 2 }, reason: "local_rewrite" });
    const offerId = rec.offers[0].id;

    // Retract move1 (revision drops back to 1), then move b to 1.5 (revision becomes 2 again!)
    ctrl.retract(move1.event.id, "undo move1");
    ctrl.applyEdit({ op: "move_beat", id: "b", timeSec: 1.5 });
    assert.equal(ctrl.getState().beats.get("b")?.revision, 2);

    assert.equal(ctrl.getState().offers.get(offerId)?.status, "stale");
    const acc = ctrl.acceptOffer(offerId);
    assert.equal(acc.ok, false);
    assert.ok(acc.conflicts.some((c) => c.kind === "version_mismatch"));
    assert.equal(ctrl.getState().beats.get("b")?.timeSec, 1.5);
  });

  it("isolates caller-owned inputs, returned getState/getTrace copies, and rejects tampered checkpoints", () => {
    const provider = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider });
    ctrl.loadDraft({ durationSec: 10, beats: [], sounds: [] });

    const op = { op: "upsert_beat", beat: { id: "b1", timeSec: 1.0, source: "user" } };
    ctrl.applyEdit(op);
    op.beat.timeSec = 9.0;

    const stateCopy = ctrl.getState();
    stateCopy.beats.get("b1").timeSec = 77;
    stateCopy.segments[0].kept = true;

    const traceCopy = ctrl.getTrace();
    traceCopy[1].payload.beat.timeSec = 88;

    assert.equal(ctrl.getState().beats.get("b1")?.timeSec, 1.0);
    assert.equal(ctrl.getState().segments[0].kept, false);
    assert.equal(foldTrace(ctrl.getTrace()).beats.get("b1")?.timeSec, 1.0);

    const cp = createCheckpoint(ctrl.getState());
    assert.equal(verifyCheckpoint(cp, ctrl.getTrace()).valid, true);

    const tamperedSnap = structuredClone(cp);
    tamperedSnap.snapshot.ready = true;
    assert.equal(verifyCheckpoint(tamperedSnap, ctrl.getTrace()).valid, false);

    const tamperedVer = { ...structuredClone(cp), schemaVersion: "9.9" };
    assert.equal(verifyCheckpoint(tamperedVer, ctrl.getTrace()).valid, false);
  });

  it("lazily prepares restored sessions on first provider call and discards late prepare from superseded songId", async () => {
    let prepareCalls = 0;
    let resolveSlowSongA;
    const customProvider = {
      manifest: () => new ScriptedMockProvider().manifest(),
      prepare: async (audio) => {
        prepareCalls += 1;
        if (audio.songId === "slow_A") {
          await new Promise((r) => {
            resolveSlowSongA = r;
          });
        }
        return { songId: audio.songId, durationSec: audio.durationSec, prepared: true };
      },
      analyze: async (evidence) => ({
        beats: [{ id: "b0", timeSec: evidence.songId === "fast_B" ? 2.0 : 1.0, source: "draft" }],
      }),
      suggestSnaps: async () => [],
      recompute: async () => ({ offers: [] }),
    };

    // Part 1: Restored from initialEvents
    const restoredCtrl = new SessionController({
      provider: customProvider,
      initialEvents: [
        ev(0, "audio_bound", { songId: "restored_1", durationSec: 10 }, { kind: "host" }),
      ],
    });
    const resRestored = await restoredCtrl.analyze();
    assert.equal(resRestored.status, "applied");
    assert.equal(prepareCalls, 1);

    // Part 2: Song A slow prepare superseded by Song B fast prepare
    const raceCtrl = new SessionController({
      provider: customProvider,
      triggerPolicy: { song_open: { mode: "manual" } },
    });
    const pA = raceCtrl.bindAudio({ songId: "slow_A", durationSec: 10 });
    const pB = raceCtrl.bindAudio({ songId: "fast_B", durationSec: 10 });
    const evB = await pB;
    assert.equal(evB.songId, "fast_B");

    resolveSlowSongA();
    await assert.rejects(pA, (err) => err instanceof EnsomiError && err.code === "SUPERSEDED");

    await raceCtrl.analyze();
    assert.equal(raceCtrl.getState().beats.get("b0")?.timeSec, 2.0);
  });

  it("audits src/*.js and runtime user-facing strings for zero internal IDs, batchIds, hashes, or dev/mock leaks", async () => {
    // 1. Source scan for forbidden developer placeholders
    const srcFiles = readdirSync(join(rootDir, "src")).filter((f) => f.endsWith(".js"));
    for (const file of srcFiles) {
      const content = readFileSync(join(rootDir, "src", file), "utf8");
      assert.equal(/reserved for N1/i.test(content), false, `Found "reserved for N1" in src/${file}`);
      assert.equal(/\bWIP\b/.test(content), false, `Found "WIP" in src/${file}`);
      assert.equal(/\bexperimental\b/i.test(content), false, `Found "experimental" in src/${file}`);
    }

    // 2. Runtime user-facing copy scan across all scenarios, conflicts, advisories, views, and snaps
    const provider = new ScriptedMockProvider();
    const ctrl = new SessionController({
      provider,
      triggerPolicy: { micro_assist: { debounceMs: 0 } },
    });
    await ctrl.openSong({ songId: "audit_track", durationSec: 32 });

    ctrl.applyEdit([
      { op: "upsert_beat", beat: { id: "beat_user_1", timeSec: 2.25, source: "user" } },
      { op: "apply_unbeat_cue", id: "unbeat_user_1", timeSec: 2.75 },
      { op: "set_range_keep", t0: 24, t1: 28, kept: true },
    ]);

    const scenarios = [
      "local_rewrite",
      "triplet_vs_reset",
      "forward",
      "cross_region",
      "protected_keep_conflict",
    ];
    for (const reason of scenarios) {
      const out = await ctrl.recompute({ scope: { t0: 2, t1: 4 }, reason });
      for (const offer of out.offers) {
        assertCleanHumanCopy(offer.title, `offer.title (${reason})`);
        assertCleanHumanCopy(offer.consequenceSummary, `offer.consequenceSummary (${reason})`);
        for (const note of offer.advisoryNotes) {
          assertCleanHumanCopy(note, `offer.advisoryNotes (${reason})`);
        }
        for (const conflict of offer.conflicts) {
          assertCleanHumanCopy(conflict.message, `conflict.message (${reason})`);
        }
        const check = validatePatchOffer(ctrl.getState(), offer);
        for (const note of check.advisoryNotes) {
          assertCleanHumanCopy(note, `validated advisory (${reason})`);
        }
        for (const conflict of check.conflicts) {
          assertCleanHumanCopy(conflict.message, `validated conflict (${reason})`);
        }
      }
    }

    // Check sibling supersession conflict message
    const sib = await ctrl.recompute({ scope: { t0: 6, t1: 8 }, reason: "triplet_vs_reset" });
    ctrl.acceptOffer(sib.offers[0].id);
    const supOffer = ctrl.getState().offers.get(sib.offers[1].id);
    for (const conflict of supOffer?.conflicts ?? []) {
      assertCleanHumanCopy(conflict.message, "superseded sibling conflict.message");
    }

    // Check snaps, inbox reasons, and red-line losses
    const snaps = await ctrl.suggestSnaps({ timeSec: 4.0, windowSec: 0.1 });
    for (const cand of snaps.candidates) {
      assertCleanHumanCopy(cand.label, "SnapCandidate.label");
    }

    const inbox = rankInboxSpans(ctrl.getState());
    for (const sp of inbox) {
      if (sp.reason) assertCleanHumanCopy(sp.reason, "Inbox Span.reason");
    }

    const proj = projectRedLines(ctrl.getState().beats, ctrl.getState().segments);
    for (const loss of proj.losses) {
      assertCleanHumanCopy(loss, "RedLineProjection.losses");
    }
  });
});
