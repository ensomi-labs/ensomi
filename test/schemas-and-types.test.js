import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import * as ensomi from "../index.js";

const rootDir = dirname(dirname(fileURLToPath(import.meta.url)));

describe("JSON schemas and public API surface", () => {
  it("parses all JSON schema files cleanly and declares Draft 2020-12", () => {
    const schemaFiles = [
      "schemas/entities.schema.json",
      "schemas/trace-event.schema.json",
      "schemas/patch-offer.schema.json",
      "schemas/provider-manifest.schema.json",
    ];
    for (const rel of schemaFiles) {
      const raw = readFileSync(join(rootDir, rel), "utf8");
      const parsed = JSON.parse(raw);
      assert.equal(parsed.$schema, "https://json-schema.org/draft/2020-12/schema", rel);
      assert.ok(typeof parsed.$id === "string" && parsed.$id.length > 0, rel);
    }
  });

  it("exports every declared runtime symbol in index.d.ts", () => {
    const dts = readFileSync(join(rootDir, "index.d.ts"), "utf8");
    const declaredNames = [];
    const regex = /^export declare (?:const|function|class) ([A-Za-z0-9_]+)/gm;
    let match;
    while ((match = regex.exec(dts)) !== null) {
      declaredNames.push(match[1]);
    }
    assert.ok(declaredNames.length >= 25);

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
});
