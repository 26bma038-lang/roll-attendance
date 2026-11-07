// sources.json: the Classroom courses Roll copies from, and who sees each one.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, SyncError } from './auth.js';

export function loadSources(file = join(ROOT, 'sources.json')) {
  return validateSources(JSON.parse(readFileSync(file, 'utf8')));
}

/** `"branches": "*"` means every branch Roll supports (NithConfig.branches in the app). */
export const ALL_BRANCHES = '*';

/**
 * Checks sources.json and normalises it: `"branches": "*"` becomes `["*"]`, so Firestore always
 * holds a list and the app can match it with one array-contains-any query.
 */
export function validateSources(sources) {
  if (!Array.isArray(sources) || sources.length === 0) throw new SyncError('sources.json must be a non-empty list.');
  const ids = new Set();
  for (const s of sources) {
    for (const key of ['id', 'courseId', 'title', 'credit', 'classroomUrl']) {
      if (typeof s[key] !== 'string' || !s[key]) throw new SyncError(`sources.json: "${s.id ?? '?'}" needs a "${key}".`);
    }
    if (!/^[a-z0-9-]+$/.test(s.id)) throw new SyncError(`sources.json: id "${s.id}" must be lower-case letters, digits and dashes.`);
    if (ids.has(s.id)) throw new SyncError(`sources.json: id "${s.id}" appears twice.`);
    ids.add(s.id);
    if (!Array.isArray(s.years) || !s.years.length || !s.years.every((y) => Number.isInteger(y) && y >= 1 && y <= 5)) {
      throw new SyncError(`sources.json: "${s.id}" needs "years", e.g. [1].`);
    }
    // "*", or ["*"] when validating an already-normalised list.
    if (s.branches === ALL_BRANCHES || (Array.isArray(s.branches) && s.branches.length === 1 && s.branches[0] === ALL_BRANCHES)) continue;
    if (!Array.isArray(s.branches) || !s.branches.length || !s.branches.every((b) => /^[A-Z]{2}$/.test(b))) {
      throw new SyncError(`sources.json: "${s.id}" needs "branches": "*" (every branch) or a list, e.g. ["EC", "MA"].`);
    }
  }
  return sources.map((s) => (s.branches === ALL_BRANCHES ? { ...s, branches: [ALL_BRANCHES] } : s));
}

/** True while a credit line still holds a placeholder such as "<SENIOR NAME>" or "___". */
export function hasPlaceholder(credit) {
  return /<[^>]*>|_{3,}/.test(String(credit ?? ''));
}
