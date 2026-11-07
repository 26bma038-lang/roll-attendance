// sources.json: the Classroom courses Roll copies from, and who sees each one.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, SyncError } from './auth.js';

export function loadSources(file = join(ROOT, 'sources.json')) {
  return validateSources(JSON.parse(readFileSync(file, 'utf8')));
}

export function validateSources(sources) {
  if (!Array.isArray(sources)) throw new SyncError('sources.json must be a list.');
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
    if (!Array.isArray(s.branches) || !s.branches.length || !s.branches.every((b) => /^[A-Z]{2}$/.test(b))) {
      throw new SyncError(`sources.json: "${s.id}" needs "branches", e.g. ["EC", "MA"].`);
    }
  }
  return sources;
}
