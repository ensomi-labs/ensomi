/**
 * Constraint IR compiler.
 * Compiles active semantic Statements into the 4-form Constraint IR:
 *   - observe : acoustic evidence cues (sound_at)
 *   - clamp   : tempo/beat regime or anchor targets (steady, free, beat_at, beat_run)
 *   - forbid  : prohibited beat locations or pulse-free regions (forbid_beat, no_pulse)
 *   - freeze  : immutable spans or entity sets protected from offer mutation (keep)
 */

import { EnsomiError } from "./types.js";

export const DEFAULT_POINT_TOL_SEC = 0.02;

/**
 * Compile active Statements into the deterministic 4-form Constraint IR.
 * Inactive statements (`active === false`) are omitted.
 *
 * @param {Iterable<import("../index.d.ts").Statement> | Map<string, import("../index.d.ts").Statement> | Record<string, import("../index.d.ts").Statement>} statements
 * @returns {import("../index.d.ts").Constraint[]}
 */
export function compileConstraints(statements) {
  const list = toStatementArray(statements);
  const active = list
    .filter((s) => s && s.active !== false)
    .sort((a, b) => {
      if (a.createdAtSeq !== b.createdAtSeq) return a.createdAtSeq - b.createdAtSeq;
      return a.id.localeCompare(b.id);
    });

  /** @type {import("../index.d.ts").Constraint[]} */
  const compiled = [];

  for (const st of active) {
    const value = st.value ? structuredClone(st.value) : {};
    switch (st.kind) {
      case "sound_at":
        compiled.push({
          id: `c:${st.id}`,
          statementId: st.id,
          statementKind: st.kind,
          form: "observe",
          target: "sound",
          ...(st.t !== undefined ? { t: st.t } : {}),
          ...(st.span !== undefined ? { span: { ...st.span } } : {}),
          params: {
            tolSec: typeof value.tolSec === "number" ? value.tolSec : DEFAULT_POINT_TOL_SEC,
            ...value,
          },
        });
        break;

      case "steady":
        compiled.push({
          id: `c:${st.id}`,
          statementId: st.id,
          statementKind: st.kind,
          form: "clamp",
          target: "tempo_regime",
          ...(st.span !== undefined ? { span: { ...st.span } } : {}),
          ...(st.t !== undefined ? { t: st.t } : {}),
          params: {
            regime: "steady",
            ...value,
          },
        });
        break;

      case "free":
        compiled.push({
          id: `c:${st.id}`,
          statementId: st.id,
          statementKind: st.kind,
          form: "clamp",
          target: "tempo_regime",
          ...(st.span !== undefined ? { span: { ...st.span } } : {}),
          ...(st.t !== undefined ? { t: st.t } : {}),
          params: {
            regime: "free",
            ...value,
          },
        });
        break;

      case "beat_at":
        compiled.push({
          id: `c:${st.id}`,
          statementId: st.id,
          statementKind: st.kind,
          form: "clamp",
          target: "beat",
          ...(st.t !== undefined ? { t: st.t } : {}),
          ...(st.span !== undefined ? { span: { ...st.span } } : {}),
          params: {
            tolSec: typeof value.tolSec === "number" ? value.tolSec : DEFAULT_POINT_TOL_SEC,
            ...value,
          },
        });
        break;

      case "beat_run":
        compiled.push({
          id: `c:${st.id}`,
          statementId: st.id,
          statementKind: st.kind,
          form: "clamp",
          target: "beat_run",
          ...(st.span !== undefined ? { span: { ...st.span } } : {}),
          ...(st.t !== undefined ? { t: st.t } : {}),
          params: { ...value },
        });
        break;

      case "forbid_beat":
        compiled.push({
          id: `c:${st.id}`,
          statementId: st.id,
          statementKind: st.kind,
          form: "forbid",
          target: "beat",
          ...(st.t !== undefined ? { t: st.t } : {}),
          ...(st.span !== undefined ? { span: { ...st.span } } : {}),
          params: {
            tolSec: typeof value.tolSec === "number" ? value.tolSec : DEFAULT_POINT_TOL_SEC,
            ...value,
          },
        });
        break;

      case "no_pulse":
        compiled.push({
          id: `c:${st.id}`,
          statementId: st.id,
          statementKind: st.kind,
          form: "forbid",
          target: "pulse",
          ...(st.span !== undefined ? { span: { ...st.span } } : {}),
          ...(st.t !== undefined ? { t: st.t } : {}),
          params: {
            reason: "no_pulse",
            ...value,
          },
        });
        break;

      case "keep":
        compiled.push({
          id: `c:${st.id}`,
          statementId: st.id,
          statementKind: st.kind,
          form: "freeze",
          target: "span",
          ...(st.span !== undefined ? { span: { ...st.span } } : {}),
          ...(st.t !== undefined ? { t: st.t } : {}),
          params: {
            scope: typeof value.scope === "string" ? value.scope : "beats_and_boundaries",
            ...value,
          },
        });
        break;

      default:
        throw new EnsomiError("UNKNOWN_STATEMENT_KIND", `Unknown statement kind: ${st.kind}`);
    }
  }

  return compiled;
}

/**
 * Normalize an iterable, Map, or plain Record of statements to an array.
 * @param {any} input
 * @returns {any[]}
 */
function toStatementArray(input) {
  if (!input) return [];
  if (input instanceof Map) {
    return Array.from(input.values());
  }
  if (Array.isArray(input)) {
    return input;
  }
  if (typeof input[Symbol.iterator] === "function") {
    return Array.from(input);
  }
  if (typeof input === "object") {
    return Object.values(input);
  }
  return [];
}
