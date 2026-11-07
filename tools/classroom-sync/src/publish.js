// Decides what to write to Firestore for one source. Pure, so it is unit-tested; firestore.js
// does the reads and writes.
//
// materialSources/{sourceId}
//   title, credit, classroomUrl, years, branches, syncedAt, contentHash, itemCount, partCount,
//   items: [...]                       when the index fits in one document (partCount 0)
// materialSources/{sourceId}/parts/{n} { n, items: [...] }   n = 0..partCount-1, when it doesn't

import { createHash } from 'node:crypto';

/** Firestore allows 1 MiB per document; split well before that. */
export const PART_LIMIT_BYTES = 800_000;

/** Fields copied from sources.json onto the document. */
export function sourceMeta(source) {
  const { title, credit, classroomUrl, years, branches } = source;
  return { title, credit, classroomUrl, years, branches };
}

/** Hash of everything students see. Same index → same hash → no write. */
export function contentHash(source, items) {
  return createHash('sha256').update(JSON.stringify({ meta: sourceMeta(source), items })).digest('hex');
}

/**
 * Rough Firestore size of a value: string bytes + 1, 8 per number/bool, map keys counted like
 * strings, +32 per document. Close enough to keep parts well under the limit.
 */
export function estimateBytes(value) {
  if (value === null || value === undefined) return 1;
  if (typeof value === 'string') return Buffer.byteLength(value) + 1;
  if (typeof value === 'number' || typeof value === 'boolean') return 8;
  if (Array.isArray(value)) return value.reduce((sum, v) => sum + estimateBytes(v), 0);
  return Object.entries(value).reduce((sum, [k, v]) => sum + Buffer.byteLength(k) + 1 + estimateBytes(v), 0);
}

/** Splits items into consecutive chunks of at most `limit` estimated bytes each. */
export function splitItems(items, limit = PART_LIMIT_BYTES) {
  const parts = [];
  let current = [];
  let size = 0;
  for (const item of items) {
    const bytes = estimateBytes(item);
    if (current.length && size + bytes > limit) {
      parts.push(current);
      current = [];
      size = 0;
    }
    current.push(item);
    size += bytes;
  }
  if (current.length) parts.push(current);
  return parts;
}

/**
 * @param existing  the current document's data, or null if there is none
 * @returns `{ changed: false }` or
 *          `{ changed: true, doc, parts: [[...items]], deleteParts: [n...] }`
 *          (`doc` without syncedAt; firestore.js adds the server time)
 */
export function planWrite(source, items, existing, { limit = PART_LIMIT_BYTES, force = false } = {}) {
  const hash = contentHash(source, items);
  if (!force && existing?.contentHash === hash) return { changed: false, hash };

  const meta = sourceMeta(source);
  const base = { ...meta, contentHash: hash, itemCount: items.length };
  const headerBytes = estimateBytes(base) + 200;
  const oldParts = existing?.partCount ?? 0;

  if (estimateBytes(items) + headerBytes <= limit) {
    return {
      changed: true,
      hash,
      doc: { ...base, partCount: 0, items },
      parts: [],
      deleteParts: range(0, oldParts),
    };
  }

  const parts = splitItems(items, limit);
  return {
    changed: true,
    hash,
    doc: { ...base, partCount: parts.length, items: [] },
    parts,
    deleteParts: range(parts.length, oldParts),
  };
}

function range(from, to) {
  return Array.from({ length: Math.max(0, to - from) }, (_, i) => from + i);
}
