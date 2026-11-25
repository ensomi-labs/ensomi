import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import * as ensomi from "../index.js";
import * as ensomiTesting from "../testing.js";

const rootDir = dirname(dirname(fileURLToPath(import.meta.url)));

function extractDtsRuntimeSymbols(dtsText) {
  /** @type {string[]} */
  const names = [];
  const declRe = /^export declare (?:const|function|class) ([A-Za-z0-9_]+)/gm;
  let m;
  while ((m = declRe.exec(dtsText)) !== null) {
    names.push(m[1]);
  }
  return names;
}

describe("Package surface, TypeScript 1-to-1 parity, JSON Schemas, Timebase & RFC 8785 JCS", () => {
  it("keeps index.js free of mock bloat and maintains 1-to-1 symbol parity with index.d.ts and testing.d.ts", () => {
    assert.equal("ScriptedMockProvider" in ensomi, false);
    assert.equal("generateSyntheticDraft" in ensomi, false);
    assert.equal(typeof ensomiTesting.ScriptedMockProvider, "function");
    assert.equal(typeof ensomiTesting.generateSyntheticDraft, "function");

    const mainDts = readFileSync(join(rootDir, "index.d.ts"), "utf8");
    const mainDeclared = extractDtsRuntimeSymbols(mainDts);
    const mainRuntime = Object.keys(ensomi);

    for (const sym of mainDeclared) {
      assert.ok(
        Object.prototype.hasOwnProperty.call(ensomi, sym),
        `index.d.ts declares "${sym}" missing from index.js`,
      );
    }
    for (const sym of mainRuntime) {
      assert.ok(
        mainDeclared.includes(sym),
        `index.js exports "${sym}" missing from index.d.ts`,
      );
    }
    assert.equal(mainDeclared.length, mainRuntime.length);

    const testingDts = readFileSync(join(rootDir, "testing.d.ts"), "utf8");
    const testingDeclared = extractDtsRuntimeSymbols(testingDts);
    const testingRuntime = Object.keys(ensomiTesting);

    assert.deepEqual(
      testingDeclared.slice().sort(),
      testingRuntime.slice().sort(),
    );
  });

  it("validates all JSON Schema Draft 2020-12 files and enum parity with runtime constants", () => {
    const schemaFiles = [
      "schemas/entities.schema.json",
      "schemas/trace-event.schema.json",
      "schemas/offer.schema.json",
      "schemas/provider-manifest.schema.json",
    ];
    /** @type {Record<string, any>} */
    const schemas = {};
    for (const rel of schemaFiles) {
      const parsed = JSON.parse(readFileSync(join(rootDir, rel), "utf8"));
      assert.equal(parsed.$schema, "https://json-schema.org/draft/2020-12/schema", rel);
      assert.ok(typeof parsed.$id === "string" && parsed.$id.length > 0, rel);
      schemas[rel] = parsed;
    }

    const eDefs = schemas["schemas/entities.schema.json"].$defs;
    assert.deepEqual(eDefs.Origin.enum, [...ensomi.ORIGINS]);
    assert.deepEqual(eDefs.VerdictKind.enum, [...ensomi.VERDICT_KINDS]);
    assert.deepEqual(eDefs.VerdictVia.enum, [...ensomi.VERDICT_VIAS]);
    assert.deepEqual(eDefs.EntityKind.enum, [...ensomi.ENTITY_KINDS]);
    assert.deepEqual(eDefs.VerdictTargetKind.enum, [...ensomi.VERDICT_TARGET_KINDS]);
    assert.deepEqual(eDefs.SegmentRegime.enum, [...ensomi.SEGMENT_REGIMES]);
    assert.deepEqual(eDefs.SegmentKinematics.enum, [...ensomi.SEGMENT_KINEMATICS]);
    assert.deepEqual(eDefs.SpanKind.enum, [...ensomi.SPAN_KINDS]);
    assert.deepEqual(eDefs.InboxPriority.enum, [...ensomi.INBOX_PRIORITIES]);
    assert.deepEqual(eDefs.SnapRole.enum, [...ensomi.SNAP_ROLES]);
    assert.deepEqual(eDefs.SoundRegister.enum, [...ensomi.SOUND_REGISTERS]);
    assert.deepEqual(eDefs.SoundEndKind.enum, [...ensomi.SOUND_END_KINDS]);
    assert.deepEqual(eDefs.SoundBlendStatus.enum, [...ensomi.SOUND_BLEND_STATUSES]);
    assert.deepEqual(eDefs.SoundMatchMode.enum, [...ensomi.SOUND_MATCH_MODES]);
    assert.deepEqual(eDefs.PulseLayer.enum, [...ensomi.PULSE_LAYERS]);

    const tDefs = schemas["schemas/trace-event.schema.json"].$defs;
    assert.equal(tDefs.TraceEvent.properties.schemaVersion.const, "0.3");
    assert.equal(ensomi.TRACE_SCHEMA_VERSION, "0.3");
    assert.deepEqual(tDefs.TraceEventType.enum, [...ensomi.TRACE_EVENT_TYPES]);
    assert.deepEqual(tDefs.AuthorKind.enum, [...ensomi.AUTHOR_KINDS]);
    assert.deepEqual(tDefs.TransactionOpKind.enum, [...ensomi.TRANSACTION_OP_KINDS]);

    const oDefs = schemas["schemas/offer.schema.json"].$defs;
    assert.deepEqual(oDefs.OfferKind.enum, [...ensomi.OFFER_KINDS]);
    assert.deepEqual(oDefs.OfferStatus.enum, [...ensomi.OFFER_STATUSES]);
    assert.deepEqual(oDefs.ConflictKind.enum, [...ensomi.CONFLICT_KINDS]);
    assert.deepEqual(oDefs.ChallengeDisposition.enum, [...ensomi.CHALLENGE_DISPOSITIONS]);
    assert.deepEqual(oDefs.OfferOpKind.enum, [...ensomi.OFFER_OP_KINDS]);
  });

  it("enforces strict RFC 8785 (JCS) canonicalization and rejects invalid canonical values", () => {
    assert.equal(ensomi.canonicalJson(-0), "0");
    assert.equal(ensomi.canonicalJson({ b: -0, a: 1, omitted: undefined }), '{"a":1,"b":0}');

    // RFC 8785 §3.2.3 critical test: UTF-16 code-unit order sorts U+10000 (\uD800\uDC00)
    // BEFORE U+E000 (\uE000) because 0xD800 < 0xE000!
    const supp = "\uD800\uDC00";
    const bmpHigh = "\uE000";
    const obj = { [bmpHigh]: "bmp", [supp]: "supplementary", "\r": "cr" };
    assert.equal(
      ensomi.canonicalJson(obj),
      `{"\\r":"cr","${supp}":"supplementary","${bmpHigh}":"bmp"}`,
    );

    assert.equal(
      ensomi.canonicalJson('\x00\x08\x09\x0a\x0c\x0d\x1f"\\'),
      '"\\u0000\\b\\t\\n\\f\\r\\u001f\\"\\\\"',
    );

    assert.equal(
      ensomi.sha256Hex("abc"),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );

    for (const bad of [
      Number.NaN,
      Number.POSITIVE_INFINITY,
      Number.NEGATIVE_INFINITY,
      undefined,
      [1, undefined, 3],
      "\uD800",
      "abc\uDC00def",
      { ["bad\uD800key"]: 1 },
      new Map(),
      new Set(),
      new Date(),
    ]) {
      assert.throws(
        () => ensomi.canonicalJson(bad),
        (err) => err instanceof ensomi.EnsomiError && err.code === "INVALID_CANONICAL_VALUE",
      );
    }
  });

  it("provides exact single-rounding microsecond <-> sample & second boundary conversions", () => {
    assert.equal(ensomi.secToUs(1.25), 1_250_000);
    assert.equal(ensomi.secToUs(-0.015), -15_000);
    assert.equal(ensomi.secToUs(-0), 0);
    assert.equal(ensomi.usToSec(1_500_000), 1.5);
    assert.equal(ensomi.msToUs(12.5), 12_500);
    assert.equal(ensomi.usToMs(12_500), 12.5);

    assert.equal(ensomi.sampleToUs(28_800_000, 48_000), 600_000_000);
    assert.equal(ensomi.usToNearestSample(600_000_000, 48_000), 28_800_000);

    for (const n of [0, 1, 2, 441, 44_100, 26_460_000]) {
      const us = ensomi.sampleToUs(n, 44_100);
      assert.equal(ensomi.usToNearestSample(us, 44_100), n);
    }
  });

  it("resolves package.json exports subpaths (ensomi, ensomi/testing, ensomi/package.json, ensomi/schemas/*)", async () => {
    const pkgMain = await import("ensomi");
    const pkgTesting = await import("ensomi/testing");
    const pkgJson = await import("ensomi/package.json", { with: { type: "json" } });
    const entitiesSchema = await import("ensomi/schemas/entities.schema.json", {
      with: { type: "json" },
    });

    assert.equal(pkgMain.TRACE_SCHEMA_VERSION, "0.3");
    assert.equal(typeof pkgTesting.ScriptedMockProvider, "function");
    assert.equal(pkgJson.default.version, "0.1.0");
    assert.equal(entitiesSchema.default.$schema, "https://json-schema.org/draft/2020-12/schema");
  });
});
