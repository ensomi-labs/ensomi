/**
 * Strict RFC 8785 JSON Canonicalization Scheme (JCS), SHA-256 hashing,
 * UTF-16 code-unit comparator, and EnsomiError.
 *
 * Zero dependencies; identical behavior across Node.js 22/24+, browsers, and Rust JCS targets.
 */

export class EnsomiError extends Error {
  /**
   * @param {string} code
   * @param {string} message
   * @param {Record<string, unknown>} [details]
   */
  constructor(code, message, details) {
    super(message);
    this.name = "EnsomiError";
    this.code = code;
    if (details !== undefined) {
      this.details = details;
    }
  }
}

const LONE_SURROGATE_RE =
  /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/;

/**
 * Assert `value` is a non-null, non-array plain object (`Object.prototype` or `null` prototype).
 *
 * @param {unknown} value
 * @param {string} [label="value"]
 * @param {string} [errorCode="INVALID_FIELD"]
 * @returns {Record<string, unknown>}
 */
export function assertPlainObject(value, label = "value", errorCode = "INVALID_FIELD") {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new EnsomiError(errorCode, `${label} must be a plain object`);
  }
  const proto = Object.getPrototypeOf(value);
  if (proto !== Object.prototype && proto !== null) {
    throw new EnsomiError(errorCode, `${label} must be a plain object`);
  }
  return /** @type {Record<string, unknown>} */ (value);
}

/**
 * Check whether `str` contains any lone UTF-16 surrogate.
 *
 * @param {string} str
 * @returns {boolean}
 */
export function hasLoneSurrogate(str) {
  return LONE_SURROGATE_RE.test(str);
}

/**
 * Deterministic UTF-16 code-unit comparator (RFC 8785 §3.2.3).
 * Uses raw 16-bit code-unit ordering rather than locale-dependent string collation.
 *
 * @param {string} a
 * @param {string} b
 * @returns {number}
 */
export function compareCodeUnits(a, b) {
  if (a === b) return 0;
  return a < b ? -1 : 1;
}

/**
 * Escape a verified well-formed UTF-16 string per RFC 8785 §3.2.2.2.
 *
 * @param {string} str
 * @returns {string}
 */
function serializeJcsString(str) {
  if (LONE_SURROGATE_RE.test(str)) {
    throw new EnsomiError(
      "INVALID_CANONICAL_VALUE",
      "RFC 8785 canonical JSON forbids lone UTF-16 surrogates",
    );
  }
  let out = '"';
  for (let i = 0; i < str.length; i += 1) {
    const code = str.charCodeAt(i);
    if (code === 0x22) {
      out += '\\"';
    } else if (code === 0x5c) {
      out += "\\\\";
    } else if (code === 0x08) {
      out += "\\b";
    } else if (code === 0x09) {
      out += "\\t";
    } else if (code === 0x0a) {
      out += "\\n";
    } else if (code === 0x0c) {
      out += "\\f";
    } else if (code === 0x0d) {
      out += "\\r";
    } else if (code < 0x20) {
      out += `\\u${code.toString(16).padStart(4, "0")}`;
    } else {
      out += str[i];
    }
  }
  out += '"';
  return out;
}

/**
 * Strict RFC 8785 (JCS) canonical JSON serializer:
 *  - Object keys sorted by UTF-16 code units (`compareCodeUnits`).
 *  - Numbers formatted via ECMAScript `Number.prototype.toString()` (`-0` -> `"0"`).
 *  - Object properties with value `undefined` are omitted; `undefined` at root or inside
 *    arrays throws `EnsomiError("INVALID_CANONICAL_VALUE")`.
 *  - Throws `EnsomiError("INVALID_CANONICAL_VALUE")` on `NaN`, `±Infinity`, lone UTF-16
 *    surrogates, functions, symbols, bigints, or non-plain objects (`Map`, `Set`, `Date`, etc.).
 *
 * @param {unknown} value
 * @returns {string}
 */
export function canonicalJson(value) {
  if (value === null) {
    return "null";
  }
  const t = typeof value;
  if (t === "boolean") {
    return value ? "true" : "false";
  }
  if (t === "number") {
    if (!Number.isFinite(value)) {
      throw new EnsomiError(
        "INVALID_CANONICAL_VALUE",
        `RFC 8785 canonical JSON forbids non-finite number: ${String(value)}`,
      );
    }
    return value === 0 ? "0" : Number.prototype.toString.call(value);
  }
  if (t === "string") {
    return serializeJcsString(/** @type {string} */ (value));
  }
  if (Array.isArray(value)) {
    const parts = new Array(value.length);
    for (let i = 0; i < value.length; i += 1) {
      const item = value[i];
      if (item === undefined) {
        throw new EnsomiError(
          "INVALID_CANONICAL_VALUE",
          `RFC 8785 canonical JSON forbids undefined inside arrays (index ${i})`,
        );
      }
      parts[i] = canonicalJson(item);
    }
    return `[${parts.join(",")}]`;
  }
  if (t === "object") {
    const proto = Object.getPrototypeOf(value);
    if (proto !== Object.prototype && proto !== null) {
      throw new EnsomiError(
        "INVALID_CANONICAL_VALUE",
        "RFC 8785 canonical JSON requires plain JSON objects",
      );
    }
    const obj = /** @type {Record<string, unknown>} */ (value);
    const keys = Object.keys(obj);
    /** @type {string[]} */
    const definedKeys = [];
    for (let i = 0; i < keys.length; i += 1) {
      const k = keys[i];
      if (obj[k] !== undefined) {
        definedKeys.push(k);
      }
    }
    definedKeys.sort(compareCodeUnits);
    const entries = new Array(definedKeys.length);
    for (let i = 0; i < definedKeys.length; i += 1) {
      const k = definedKeys[i];
      entries[i] = `${serializeJcsString(k)}:${canonicalJson(obj[k])}`;
    }
    return `{${entries.join(",")}}`;
  }
  throw new EnsomiError(
    "INVALID_CANONICAL_VALUE",
    `RFC 8785 canonical JSON forbids value of type "${t}"`,
  );
}

const SHA256_K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

const SHA256_INIT = new Uint32Array([
  0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
  0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
]);

const textEncoder = new TextEncoder();
const SHARED_W = new Uint32Array(64);
const SHARED_BUF = new Uint8Array(1024);
const SHARED_VIEW = new DataView(SHARED_BUF.buffer);
const HEX_LUT = Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, "0"));

function uint32ToHex(v) {
  return (
    HEX_LUT[(v >>> 24) & 0xff] +
    HEX_LUT[(v >>> 16) & 0xff] +
    HEX_LUT[(v >>> 8) & 0xff] +
    HEX_LUT[v & 0xff]
  );
}

/**
 * Pure JavaScript SHA-256 returning lowercase 64-character hex string.
 * Rejects lone UTF-16 surrogates so hashed UTF-8 bytes match strict UTF-8 runtimes (Rust/Python).
 *
 * @param {string} input
 * @returns {string}
 */
export function sha256Hex(input) {
  if (typeof input !== "string") {
    throw new EnsomiError("INVALID_HASH_INPUT", "sha256Hex input must be a string");
  }
  if (LONE_SURROGATE_RE.test(input)) {
    throw new EnsomiError(
      "INVALID_CANONICAL_VALUE",
      "sha256Hex forbids strings with lone UTF-16 surrogates",
    );
  }

  let view;
  let paddedLen;
  if (input.length <= 300) {
    const { written } = textEncoder.encodeInto(input, SHARED_BUF);
    const bitLen = written * 8;
    paddedLen = ((written + 9 + 63) & ~63);
    SHARED_BUF.fill(0, written, paddedLen);
    SHARED_BUF[written] = 0x80;
    SHARED_VIEW.setUint32(paddedLen - 8, 0, false);
    SHARED_VIEW.setUint32(paddedLen - 4, bitLen >>> 0, false);
    view = SHARED_VIEW;
  } else {
    const bytes = textEncoder.encode(input);
    const bitLen = bytes.length * 8;
    paddedLen = Math.ceil((bytes.length + 9) / 64) * 64;
    const padded = new Uint8Array(paddedLen);
    padded.set(bytes);
    padded[bytes.length] = 0x80;
    view = new DataView(padded.buffer);
    const highBits = Math.floor(bitLen / 0x100000000);
    const lowBits = bitLen >>> 0;
    view.setUint32(paddedLen - 8, highBits, false);
    view.setUint32(paddedLen - 4, lowBits, false);
  }

  let h0 = SHA256_INIT[0];
  let h1 = SHA256_INIT[1];
  let h2 = SHA256_INIT[2];
  let h3 = SHA256_INIT[3];
  let h4 = SHA256_INIT[4];
  let h5 = SHA256_INIT[5];
  let h6 = SHA256_INIT[6];
  let h7 = SHA256_INIT[7];

  const w = SHARED_W;

  for (let offset = 0; offset < paddedLen; offset += 64) {
    for (let i = 0; i < 16; i += 1) {
      w[i] = view.getUint32(offset + i * 4, false);
    }
    for (let i = 16; i < 64; i += 1) {
      const s0 =
        ((w[i - 15] >>> 7) | (w[i - 15] << 25)) ^
        ((w[i - 15] >>> 18) | (w[i - 15] << 14)) ^
        (w[i - 15] >>> 3);
      const s1 =
        ((w[i - 2] >>> 17) | (w[i - 2] << 15)) ^
        ((w[i - 2] >>> 19) | (w[i - 2] << 13)) ^
        (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }

    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    let f = h5;
    let g = h6;
    let h = h7;

    for (let i = 0; i < 64; i += 1) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const temp1 = (h + S1 + ch + SHA256_K[i] + w[i]) >>> 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (S0 + maj) >>> 0;

      h = g;
      g = f;
      f = e;
      e = (d + temp1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) >>> 0;
    }

    h0 = (h0 + a) >>> 0;
    h1 = (h1 + b) >>> 0;
    h2 = (h2 + c) >>> 0;
    h3 = (h3 + d) >>> 0;
    h4 = (h4 + e) >>> 0;
    h5 = (h5 + f) >>> 0;
    h6 = (h6 + g) >>> 0;
    h7 = (h7 + h) >>> 0;
  }

  return (
    uint32ToHex(h0) +
    uint32ToHex(h1) +
    uint32ToHex(h2) +
    uint32ToHex(h3) +
    uint32ToHex(h4) +
    uint32ToHex(h5) +
    uint32ToHex(h6) +
    uint32ToHex(h7)
  );
}

/**
 * Convenience helper returning `sha256Hex(canonicalJson(value))`.
 *
 * @param {unknown} value
 * @returns {string}
 */
export function jcsSha256Hex(value) {
  return sha256Hex(canonicalJson(value));
}
