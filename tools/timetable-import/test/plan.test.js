import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { validateDraft } from '../src/draft.js';
import { planClass, slotId, subjectIdFor, writeCount } from '../src/plan.js';

const [ma] = validateDraft(JSON.parse(readFileSync(new URL('./fixtures/sample-draft.json', import.meta.url), 'utf8'))).classes;
const empty = { subjects: [], slots: [] };

test('an empty class gets every subject and slot, in the app\'s shapes', () => {
  const plan = planClass(ma, empty);
  assert.equal(plan.subjects.create.length, 7);
  assert.deepEqual(plan.subjects.create[0], {
    id: 'imp-ma-111',
    data: { name: 'Engineering Mathematics-1', shortName: 'MA-111', code: 'MA-111', faculty: '', colorIndex: 0, archived: false },
  });
  assert.equal(plan.slots.create.length, ma.slots.length);
  const lab = plan.slots.create.find((s) => s.data.room.startsWith('Physics Lab 1 · G1'));
  assert.deepEqual(lab.data, { dayOfWeek: 1, startMinutes: 840, endMinutes: 990, subjectId: 'imp-ph-102', type: 'lab', room: 'Physics Lab 1 · G1' });
  assert.equal(writeCount(plan), 7 + ma.slots.length);
});

test('applying the same draft twice writes nothing the second time', () => {
  const first = planClass(ma, empty);
  const existing = {
    subjects: first.subjects.create.map((s) => ({ id: s.id, ...s.data })),
    slots: first.slots.create.map((s) => ({ id: s.id, ...s.data })),
  };
  const again = planClass(ma, existing);
  assert.equal(writeCount(again), 0);
  assert.equal(again.slots.unchanged.length, ma.slots.length);
  assert.equal(again.subjects.reuse.length, 7);
});

test('a CR\'s own subject with the same code is reused, never renamed or recoloured', () => {
  const crMaths = { id: 'abc', name: 'Maths 1 (Prof. Sharma)', shortName: 'M1', code: 'MA 111', faculty: 'Prof. Sharma', colorIndex: 5, archived: false };
  const plan = planClass(ma, { subjects: [crMaths], slots: [] });
  assert.deepEqual(plan.subjects.reuse, [{ id: 'abc', code: 'MA-111', name: 'Maths 1 (Prof. Sharma)' }]);
  assert.ok(!plan.subjects.create.some((s) => s.data.code === 'MA-111'));
  assert.ok(plan.slots.create.filter((s) => s.data.subjectId === 'abc').length > 0);
  // New subjects take the next colours after the class's own.
  assert.equal(plan.subjects.create[0].data.colorIndex, 1);
});

test('without --replace nothing is deleted, and a slot the CR already entered is not doubled', () => {
  const crSlot = { id: 'cr-1', dayOfWeek: 1, startMinutes: 540, endMinutes: 590, subjectId: 'imp-ma-111', type: 'lecture', room: 'LH-101' };
  const extra = { id: 'cr-2', dayOfWeek: 6, startMinutes: 600, endMinutes: 650, subjectId: 'x', type: 'lecture', room: 'Seminar hall' };
  const plan = planClass(ma, { subjects: [], slots: [crSlot, extra] });
  assert.deepEqual(plan.slots.remove, []);
  assert.equal(plan.slots.alreadyThere.length, 1);
  assert.equal(plan.slots.alreadyThere[0].twinId, 'cr-1');
  assert.deepEqual(plan.slots.keep.map((s) => s.id), ['cr-2']);
  assert.equal(plan.slots.create.length, ma.slots.length - 1);
});

test('--replace deletes every slot that is not in the draft, including the CR\'s', () => {
  const extra = { id: 'cr-2', dayOfWeek: 6, startMinutes: 600, endMinutes: 650, subjectId: 'x', type: 'lecture', room: '' };
  const plan = planClass(ma, { subjects: [], slots: [extra] }, { replace: true });
  assert.deepEqual(plan.slots.remove.map((s) => s.id), ['cr-2']);
  assert.equal(plan.slots.create.length, ma.slots.length);
});

test('a changed room updates the same imported slot instead of adding another', () => {
  const first = planClass(ma, empty);
  const moved = first.slots.create.map((s, i) => ({ id: s.id, ...s.data, room: i === 0 ? 'LH-999' : s.data.room }));
  const plan = planClass(ma, { subjects: first.subjects.create.map((s) => ({ id: s.id, ...s.data })), slots: moved });
  assert.equal(plan.slots.update.length, 1);
  assert.equal(plan.slots.create.length, 0);
});

test('ids are stable and readable', () => {
  assert.equal(subjectIdFor('MA-111'), 'imp-ma-111');
  assert.equal(slotId(ma.slots[0]), slotId({ ...ma.slots[0] }));
  assert.notEqual(slotId(ma.slots[0]), slotId(ma.slots[1]));
  assert.match(slotId(ma.slots[0]), /^imp-[0-9a-f]{16}$/);
});
