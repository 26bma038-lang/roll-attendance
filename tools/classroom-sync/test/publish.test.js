import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { buildIndex } from '../src/index.js';
import { contentHash, estimateBytes, planWrite, splitItems } from '../src/publish.js';
import { hasPlaceholder } from '../src/sources.js';

const source = JSON.parse(readFileSync(new URL('../sources.json', import.meta.url), 'utf8'))[0];
const raw = JSON.parse(readFileSync(new URL('./fixtures/sample-course.json', import.meta.url), 'utf8'))[source.id];
const { items } = buildIndex(source, raw);

test('first sync writes one document with every item and the source fields', () => {
  const plan = planWrite(source, items, null);
  assert.equal(plan.changed, true);
  assert.deepEqual(plan.parts, []);
  assert.deepEqual(plan.deleteParts, []);
  assert.deepEqual(plan.doc, {
    title: source.title,
    credit: source.credit,
    classroomUrl: source.classroomUrl,
    years: source.years,
    branches: source.branches,
    contentHash: contentHash(source, items),
    itemCount: items.length,
    partCount: 0,
    items,
  });
});

test('an unchanged index writes nothing; --force writes anyway', () => {
  const existing = planWrite(source, items, null).doc;
  assert.equal(planWrite(source, items, existing).changed, false);
  assert.equal(planWrite(source, items, existing, { force: true }).changed, true);
});

test('a removed file or a new credit line counts as a change', () => {
  const existing = planWrite(source, items, null).doc;
  const fewer = planWrite(source, items.slice(1), existing);
  assert.equal(fewer.changed, true);
  assert.equal(fewer.doc.items.length, items.length - 1);
  assert.equal(planWrite({ ...source, credit: 'Material shared by A. Senior' }, items, existing).changed, true);
});

test('a big index is split into parts under the limit, in order', () => {
  const limit = 2_000;
  const plan = planWrite(source, items, null, { limit });
  assert.ok(plan.parts.length > 1);
  assert.equal(plan.doc.partCount, plan.parts.length);
  assert.deepEqual(plan.doc.items, []);
  assert.equal(plan.doc.itemCount, items.length);
  assert.deepEqual(plan.parts.flat(), items);
  for (const part of plan.parts) assert.ok(estimateBytes(part) <= limit);
});

test('parts no longer needed are deleted', () => {
  const big = planWrite(source, items, null, { limit: 2_000 }).doc;
  const shrunk = planWrite(source, items.slice(0, 3), big, { limit: 2_000 });
  assert.equal(shrunk.doc.partCount, 0);
  assert.deepEqual(shrunk.deleteParts, Array.from({ length: big.partCount }, (_, i) => i));

  const fewerParts = planWrite(source, items.slice(0, 8), big, { limit: 2_000 });
  assert.ok(fewerParts.doc.partCount < big.partCount);
  assert.deepEqual(fewerParts.deleteParts[0], fewerParts.doc.partCount);
  assert.equal(fewerParts.deleteParts.at(-1), big.partCount - 1);
});

test('a single item bigger than the limit still gets its own part', () => {
  const huge = [{ id: 'x', title: 'y'.repeat(500) }, { id: 'z', title: 'small' }];
  assert.deepEqual(splitItems(huge, 100).map((p) => p.length), [1, 1]);
});

test('574 files of realistic size fit in one document', () => {
  const many = Array.from({ length: 574 }, (_, i) => ({ ...items[0], id: String(i).padStart(20, '0') }));
  assert.ok(estimateBytes(many) < 800_000);
  assert.equal(planWrite(source, many, null).doc.partCount, 0);
});

test('credit placeholders are caught', () => {
  assert.equal(hasPlaceholder('Material shared by <SENIOR NAME>'), true);
  assert.equal(hasPlaceholder('Material shared by ___'), true);
  assert.equal(hasPlaceholder('Material shared by A. Senior'), false);
});
