import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  applyEvent,
  canonicalJson,
  computeSnapshotDigest,
  createInitialState,
  EnsomiError,
  foldTrace,
  sha256Hex,
  toSnapshot,
  validateOffer,
} from "../index.js";

const rootDir = dirname(dirname(fileURLToPath(import.meta.url)));
const conformanceDir = join(rootDir, "conformance", "v0.1");

function loadJson(fileName) {
  return JSON.parse(readFileSync(join(conformanceDir, fileName), "utf8"));
}

describe("Executable Cross-Language Conformance Suite (conformance/v0.1/*.json)", () => {
  it("verifies RFC 8785 (JCS) canonical JSON & SHA-256 vectors (jcs-vectors.json)", () => {
    const suite = loadJson("jcs-vectors.json");
    assert.equal(suite.conformanceVersion, "0.1.0");
    assert.ok(suite.validVectors.length >= 5);

    for (const vec of suite.validVectors) {
      const actualCanonical = canonicalJson(vec.input);
      assert.equal(actualCanonical, vec.expectedCanonicalJson, vec.id);
      assert.equal(sha256Hex(actualCanonical), vec.expectedSha256Hex, vec.id);
    }

    for (const bad of suite.loneSurrogateHexCases) {
      const codeUnits = bad.utf16CodeUnitsHex.map((h) => parseInt(h, 16));
      const malformedString = String.fromCharCode(...codeUnits);
      assert.throws(
        () => canonicalJson({ s: malformedString }),
        (err) => err instanceof EnsomiError && err.code === bad.expectedErrorCode,
        bad.id,
      );
    }
  });

  it("verifies end-to-end valid traces & exact RFC 8785 Snapshot SHA-256 digests (valid-traces.json)", () => {
    const suite = loadJson("valid-traces.json");
    assert.equal(suite.conformanceVersion, "0.1.0");
    assert.ok(suite.cases.length >= 3);

    for (const tc of suite.cases) {
      // 1. Full foldTrace replay
      const folded = foldTrace(tc.events);
      const snapshot = toSnapshot(folded);
      const digest = computeSnapshotDigest(snapshot);

      assert.equal(digest, tc.expectedSnapshotDigest, `${tc.id}: foldTrace digest`);
      assert.deepEqual(snapshot, tc.expectedSnapshot, `${tc.id}: foldTrace snapshot`);

      // 2. Incremental applyEvent replay must produce the exact same Snapshot & JCS digest!
      let inc = createInitialState();
      for (const ev of tc.events) {
        inc = applyEvent(inc, ev);
      }
      assert.equal(
        computeSnapshotDigest(inc),
        tc.expectedSnapshotDigest,
        `${tc.id}: incremental applyEvent digest`,
      );
    }
  });

  it("verifies event-atomic selective retract & orphanedTransaction cases (selective-retract-orphans.json)", () => {
    const suite = loadJson("selective-retract-orphans.json");
    assert.equal(suite.conformanceVersion, "0.1.0");
    assert.ok(suite.cases.length >= 3);

    for (const tc of suite.cases) {
      const folded = foldTrace(tc.events);
      const snapshot = toSnapshot(folded);
      assert.equal(computeSnapshotDigest(snapshot), tc.expectedSnapshotDigest, tc.id);
      assert.deepEqual(
        snapshot.orphanedTransactions.map((o) => ({
          eventId: o.eventId,
          code: o.code,
          causeCode: o.causeCode,
        })),
        tc.expectedOrphans,
        tc.id,
      );
    }
  });

  it("verifies offer conflicts, challenges, authorizations & retract restoration (offer-conflicts-and-challenges.json)", () => {
    const suite = loadJson("offer-conflicts-and-challenges.json");
    assert.equal(suite.conformanceVersion, "0.1.0");
    assert.ok(suite.validationCases.length >= 5);
    assert.ok(suite.traceCases.length >= 2);

    for (const vc of suite.validationCases) {
      const state = foldTrace(vc.setupEvents);
      const result = validateOffer(state, vc.offer);
      assert.equal(result.status, vc.expectedStatus, vc.id);
      assert.equal(result.valid, vc.expectedValid, vc.id);
      assert.deepEqual(
        result.conflicts.map((c) => c.kind),
        vc.expectedConflictKinds,
        vc.id,
      );
      assert.equal(result.requiredChallenges.length, vc.expectedRequiredChallengeCount, vc.id);
    }

    for (const tc of suite.traceCases) {
      const state = foldTrace(tc.events);
      assert.equal(computeSnapshotDigest(state), tc.expectedSnapshotDigest, tc.id);
    }
  });

  it("verifies live rejected malformed/authority/duplicate/bounds inputs (rejected-inputs.json)", () => {
    const suite = loadJson("rejected-inputs.json");
    assert.equal(suite.conformanceVersion, "0.1.0");
    assert.ok(suite.cases.length >= 6);

    for (const rc of suite.cases) {
      assert.throws(
        () => foldTrace(rc.events),
        (err) => err instanceof EnsomiError && err.code === rc.expectedErrorCode,
        rc.id,
      );
    }
  });
});
