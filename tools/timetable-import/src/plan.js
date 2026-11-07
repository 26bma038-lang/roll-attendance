// Decides what `npm run apply` writes for one class. Pure; firestore.js reads and writes.
//
// Same shapes as the app's Manage screens (and firestore.rules):
//   classes/{classId}/subjects/{id}  { name, shortName, code, faculty, colorIndex, archived }
//   classes/{classId}/timetable/{id} { dayOfWeek, startMinutes, endMinutes, subjectId, type, room }

import { createHash } from 'node:crypto';

const PALETTE_SIZE = 8;
const codeKey = (code) => String(code ?? '').toUpperCase().replace(/[\s-]/g, '');

/** Imported slots get fixed ids, so applying the same draft twice changes nothing. */
export function slotId(slot) {
  const key = `${slot.day}|${slot.start}|${slot.end}|${slot.subject}|${slot.group ?? ''}`;
  return `imp-${createHash('sha1').update(key).digest('hex').slice(0, 16)}`;
}

export const subjectIdFor = (code) => `imp-${code.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;

const sameSlot = (a, b) =>
  a.dayOfWeek === b.dayOfWeek && a.startMinutes === b.startMinutes && a.endMinutes === b.endMinutes &&
  a.subjectId === b.subjectId && a.type === b.type && a.room === b.room;

/**
 * @param cls       one class from validateDraft(...).classes
 * @param existing  `{ subjects: [{ id, ...data }], slots: [{ id, ...data }] }` now in Firestore
 * @param replace   also delete slots that aren't in the draft (including ones a CR added)
 * @returns `{ subjects: { create, reuse }, slots: { create, update, unchanged, alreadyThere, remove, keep } }`
 */
export function planClass(cls, existing, { replace = false } = {}) {
  // Subjects: reuse a CR's subject with the same code (never rename or recolour it).
  const byCode = new Map(existing.subjects.filter((s) => s.code).map((s) => [codeKey(s.code), s]));
  const subjectIds = new Map();
  const create = [];
  const reuse = [];
  let colour = existing.subjects.length;
  for (const subject of cls.subjects) {
    const found = byCode.get(codeKey(subject.code));
    if (found) {
      subjectIds.set(subject.code, found.id);
      reuse.push({ id: found.id, code: subject.code, name: found.name });
    } else {
      const id = subjectIdFor(subject.code);
      subjectIds.set(subject.code, id);
      create.push({
        id,
        data: { name: subject.name, shortName: subject.code, code: subject.code, faculty: '', colorIndex: colour++ % PALETTE_SIZE, archived: false },
      });
    }
  }

  // Slots.
  const existingById = new Map(existing.slots.map((s) => [s.id, s]));
  const wanted = cls.slots.map((slot) => ({
    id: slotId(slot),
    data: {
      dayOfWeek: slot.day,
      startMinutes: slot.start,
      endMinutes: slot.end,
      subjectId: subjectIds.get(slot.subject),
      type: slot.type,
      room: [slot.room, slot.group].filter(Boolean).join(' · ').slice(0, 40),
    },
  }));
  const wantedIds = new Set(wanted.map((w) => w.id));
  const slots = { create: [], update: [], unchanged: [], alreadyThere: [], remove: [], keep: [] };
  for (const w of wanted) {
    const current = existingById.get(w.id);
    if (current) {
      (sameSlot(current, w.data) ? slots.unchanged : slots.update).push(w);
      continue;
    }
    // A CR already entered this exact class by hand: don't add a twin.
    const twin = existing.slots.find((s) => !wantedIds.has(s.id) && sameSlot(s, w.data));
    if (twin && !replace) slots.alreadyThere.push({ ...w, twinId: twin.id });
    else slots.create.push(w);
  }
  for (const s of existing.slots) {
    if (wantedIds.has(s.id)) continue;
    if (replace) slots.remove.push(s);
    else if (!slots.alreadyThere.some((w) => w.twinId === s.id)) slots.keep.push(s);
  }
  return { subjects: { create, reuse }, slots };
}

/** Writes this plan would make: 0 means nothing to do. */
export const writeCount = (plan) =>
  plan.subjects.create.length + plan.slots.create.length + plan.slots.update.length + plan.slots.remove.length;
