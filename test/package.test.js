// The package as a consumer sees it: its name resolves through the `exports` map, and package.json names
// files that exist.

import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = new URL("../", import.meta.url);
const manifest = JSON.parse(readFileSync(new URL("package.json", root), "utf8"));

test("the package name resolves to the three functions", async () => {
  const kit = await import("ensomi"); // a package can import itself by name through its own exports map
  assert.deepStrictEqual(Object.keys(kit).sort(), ["formatTimingPoints", "parseTimingPoints", "timingPointsToMidi"]);
  for (const name of Object.keys(kit)) {
    assert.equal(typeof kit[name], "function");
  }
  assert.equal(manifest.name, "ensomi");
});

test("package.json names files that exist", () => {
  const entry = manifest.exports["."];
  for (const path of [entry.types, entry.default, manifest.main, manifest.types, ...manifest.files]) {
    assert.ok(existsSync(fileURLToPath(new URL(path, root))), `${path} exists`);
  }
  for (const path of ["README.md", "LICENSE"]) {
    assert.ok(existsSync(fileURLToPath(new URL(path, root))), `${path} exists`);
  }
});

test("the package has no dependencies and no install scripts", () => {
  for (const field of ["dependencies", "devDependencies", "peerDependencies", "optionalDependencies"]) {
    assert.equal(manifest[field], undefined, `no ${field}`);
  }
  assert.deepStrictEqual(Object.keys(manifest.scripts), ["test"]);
});
