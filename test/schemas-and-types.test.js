import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import * as ensomi from "../index.js";

const rootDir = dirname(dirname(fileURLToPath(import.meta.url)));

describe("JSON schemas, TypeScript declarations, and core contract types", () => {
  it("parses all 4 JSON schema files cleanly and aligns enums with runtime constants", () => {
    const schemaFiles = [
      "schemas/entities.schema.json",
      "schemas/trace-event.schema.json",
      "schemas/patch-offer.schema.json",
      "schemas/provider-manifest.schema.json",
    ];
    /** @type {Record<string, any>} */
    const schemas = {};
    for (const rel of schemaFiles) {
      const raw = readFileSync(join(rootDir, rel), "utf8");
      const parsed = JSON.parse(raw);
      assert.equal(parsed.$schema, "https://json-schema.org/draft/2020-12/schema", rel);
      assert.ok(typeof parsed.$id === "string" && parsed.$id.length > 0, rel);
      schemas[rel] = parsed;
    }

    const entitiesDefs = schemas["schemas/entities.schema.json"].$defs;
    assert.deepEqual(entitiesDefs.EntitySource.enum, [...ensomi.ENTITY_SOURCES]);
    assert.deepEqual(entitiesDefs.SegmentRegime.enum, [...ensomi.SEGMENT_REGIMES]);
    assert.deepEqual(entitiesDefs.SpanKind.enum, [...ensomi.SPAN_KINDS]);
    assert.deepEqual(entitiesDefs.SpanPriority.enum, [...ensomi.SPAN_PRIORITIES]);
    assert.deepEqual(entitiesDefs.SnapRole.enum, [...ensomi.SNAP_ROLES]);
    assert.deepEqual(entitiesDefs.SnapFamily.enum, [...ensomi.SNAP_FAMILIES]);
    assert.ok(!ensomi.SNAP_ROLES.includes("sustain_entry"));

    const offerDefs = schemas["schemas/patch-offer.schema.json"].$defs;
    assert.deepEqual(offerDefs.OfferScopeKind.enum, [...ensomi.OFFER_SCOPE_KINDS]);
    assert.deepEqual(offerDefs.OfferStatus.enum, [...ensomi.OFFER_STATUSES]);
    assert.deepEqual(offerDefs.ConflictKind.enum, [...ensomi.CONFLICT_KINDS]);

    const traceSchema = schemas["schemas/trace-event.schema.json"];
    assert.equal(traceSchema.properties.schemaVersion.const, ensomi.TRACE_SCHEMA_VERSION);
    assert.deepEqual(traceSchema.properties.type.enum, [...ensomi.TRACE_EVENT_TYPES]);
    assert.deepEqual(
      traceSchema.properties.author.properties.kind.enum,
      [...ensomi.AUTHOR_KINDS],
    );
  });

  it("exports every declared runtime symbol in index.d.ts 1-to-1", () => {
    const dts = readFileSync(join(rootDir, "index.d.ts"), "utf8");
    /** @type {string[]} */
    const declaredNames = [];
    const regex = /^export declare (?:const|function|class) ([A-Za-z0-9_]+)/gm;
    let match;
    while ((match = regex.exec(dts)) !== null) {
      declaredNames.push(match[1]);
    }
    assert.ok(declaredNames.length >= 60);

    const runtimeExports = new Set(Object.keys(ensomi));
    for (const name of declaredNames) {
      assert.ok(runtimeExports.has(name), `index.d.ts declares "${name}" missing from index.js`);
    }
    for (const name of runtimeExports) {
      assert.ok(
        declaredNames.includes(name),
        `index.js exports "${name}" missing from index.d.ts`,
      );
    }
  });

  it("exposes hardened trigger policy, snap taxonomy, segment partition healing, and zero boundaries storage", () => {
    const initial = ensomi.createInitialState();
    assert.equal("boundaries" in initial, false);
    assert.equal("statements" in initial, false);
    assert.deepEqual(initial.segments, []);

    const withDur = ensomi.createInitialState({ durationSec: 24 });
    assert.equal(withDur.segments.length, 1);
    assert.equal(withDur.segments[0].t0, 0);
    assert.equal(withDur.segments[0].t1, 24);
    assert.equal(withDur.segments[0].regime, "auto");
    assert.equal(withDur.segments[0].kept, false);

    // Heal gapped/partial provider segments into exact [0, durationSec) partition
    const healed = ensomi.normalizeSegmentPartition(
      [
        { id: "verse", t0: 4, t1: 12, regime: "auto", kept: false },
        { id: "rubato", t0: 14, t1: 18, regime: "free", kept: true },
      ],
      20,
    );
    assert.equal(healed[0].t0, 0);
    assert.equal(healed[healed.length - 1].t1, 20);
    for (let i = 0; i < healed.length - 1; i += 1) {
      assert.equal(healed[i].t1, healed[i + 1].t0);
    }
    assert.deepEqual(ensomi.getSegmentBoundaryTimes(healed), [4, 12, 14, 18]);

    // Decompose transient Range across segments
    const slices = ensomi.decomposeRangeToSpans(healed, { t0: 2, t1: 15 });
    assert.deepEqual(
      slices.map((s) => [s.segmentId, s.t0, s.t1]),
      [
        [healed[0].id, 2, 4],
        ["verse", 4, 12],
        [healed[2].id, 12, 14],
        ["rubato", 14, 15],
      ],
    );

    // Verify DEFAULT_TRIGGER_POLICY & TRIGGER_LAYERS
    assert.deepEqual(ensomi.TRIGGER_LAYERS, ["song_open", "micro_assist", "heavy_recompute"]);
    assert.deepEqual(ensomi.DEFAULT_TRIGGER_POLICY, {
      song_open: { mode: "auto", debounceMs: 0, cancellation: "latest_wins" },
      micro_assist: { mode: "auto", debounceMs: 30, cancellation: "latest_wins" },
      heavy_recompute: { mode: "manual", debounceMs: 0, cancellation: "latest_wins" },
    });

    // Verify SnapCandidate signed deltaMs = (targetSec - querySec) * 1000
    const snap = ensomi.normalizeSnapCandidate(
      {
        eventId: "evt_1",
        targetSec: 10.012,
        deltaMs: 0,
        family: "percussion",
        role: "transient_attack",
        sigmaMs: 3,
        label: "Kick attack",
        salience: 0.95,
      },
      10.02,
    );
    assert.equal(snap.deltaMs, -8);
  });
});
