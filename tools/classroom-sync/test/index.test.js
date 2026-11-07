import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { apiError } from '../src/classroom.js';
import { SCOPES, SyncError, oauthError } from '../src/auth.js';
import { buildIndex, entryId, summarize } from '../src/index.js';
import { validateSources } from '../src/sources.js';

const sources = JSON.parse(readFileSync(new URL('../sources.json', import.meta.url), 'utf8'));
const source = sources[0];
const raw = JSON.parse(readFileSync(new URL('./fixtures/sample-course.json', import.meta.url), 'utf8'))[source.id];

test('every attachment becomes one entry; duplicates within an item collapse', () => {
  const { items } = buildIndex(source, raw);
  // 1 syllabus + 2 MA-111 notes (file + link) + 1 Sunil + 1 CS-102 + 1 CS-101 + 5 papers
  // + 1 workshop + 1 unknown-topic + 2 assignments
  assert.equal(items.length, 15);
  assert.equal(new Set(items.map((i) => i.id)).size, items.length);
});

test('entries carry section, subject, exam and year', () => {
  const { items } = buildIndex(source, raw);
  const paper = items.find((i) => i.driveFileId === 'p2');
  assert.deepEqual(paper, {
    id: entryId(source.courseId, 'm6', 'p2'),
    section: 'pyq',
    sectionLabel: 'Previous year papers',
    subjectCode: 'MA-111',
    subjectName: 'Eng. Mathematics-1',
    itemTitle: 'MA-111(Eng. Mathematics-1)',
    title: 'MID SEM 2024-25',
    exam: 'MID',
    academicYear: '2024-25',
    url: 'https://drive.google.com/file/d/p2/view',
    driveFileId: 'p2',
    mimeType: 'application/pdf',
    updatedAt: '2025-12-20T10:00:00Z',
  });
});

test('links are kept, without a Drive id', () => {
  const { items } = buildIndex(source, raw);
  const link = items.find((i) => i.url.startsWith('https://www.youtube.com/'));
  assert.equal(link.section, 'notes');
  assert.equal(link.title, 'Lecture playlist');
  assert.equal(link.driveFileId, undefined);
});

test("Sunil Sir's notes have no subject code and the fixed label", () => {
  const { items } = buildIndex(source, raw);
  const sunil = items.find((i) => i.driveFileId === 'd3');
  assert.equal(sunil.subjectCode, undefined);
  assert.equal(sunil.subjectName, "Sunil Sir's notes");
  assert.equal(sunil.itemTitle, 'Fourier Series');
  assert.equal(sunil.title, 'Fourier Series notes');
});

test('exam is only read for papers', () => {
  const { items } = buildIndex(source, raw);
  assert.ok(items.filter((i) => i.exam).every((i) => i.section === 'pyq'));
});

test('unknown topic → other with the raw name and one warning; never dropped', () => {
  const { items, warnings } = buildIndex(source, raw);
  const manual = items.find((i) => i.driveFileId === 'x1');
  assert.equal(manual.section, 'other');
  assert.equal(manual.sectionLabel, 'Lab manuals');
  assert.equal(warnings.filter((w) => w.includes('Lab manuals')).length, 1);
  assert.ok(warnings.some((w) => w.includes('no topic')));
  assert.ok(warnings.some((w) => w.includes('"Welcome"') && w.includes('no attachments')));
});

test('items are ordered: section, subject, newest year, End before Mid, natural title order', () => {
  const { items } = buildIndex(source, raw);
  assert.deepEqual([...new Set(items.map((i) => i.section))], ['pyq', 'notes', 'assignments', 'practicals', 'workshop', 'syllabus', 'other']);
  const papers = items.filter((i) => i.section === 'pyq').map((i) => i.title);
  assert.deepEqual(papers, ['END SEM 2025-26', 'END SEM 2024-25', 'MID SEM 2024-25', 'MID SEM 2023 (NEW)', 'PAPER 1']);
  const assignments = items.filter((i) => i.section === 'assignments').map((i) => i.title);
  assert.deepEqual(assignments, ['Assignment 2', 'Assignment 10']);
});

test("a bare code borrows the subject name another item gave it", () => {
  const withName = structuredClone(raw);
  withName.materials.push({
    id: 'm99', topicId: 't3', title: 'CS-102(Programming Lab)',
    materials: [{ driveFile: { driveFile: { id: 'z1', title: 'z.pdf' } } }],
  });
  const { items } = buildIndex(source, withName);
  assert.equal(items.find((i) => i.driveFileId === 'd4').subjectName, 'Programming Lab');
  // Without that item it stays the code.
  assert.equal(buildIndex(source, raw).items.find((i) => i.driveFileId === 'd4').subjectName, 'CS-102');
});

test('ids are stable across runs and input order', () => {
  const shuffled = structuredClone(raw);
  shuffled.materials.reverse();
  assert.deepEqual(buildIndex(source, shuffled).items, buildIndex(source, raw).items);
});

test('a Drive file without alternateLink gets a Drive URL', () => {
  const { items } = buildIndex(source, {
    topics: [{ topicId: 't', name: 'BOOKS' }],
    materials: [{ id: 'm', topicId: 't', title: 'PH-101(Physics)', materials: [{ driveFile: { driveFile: { id: 'abc', title: 'HC Verma.pdf' } } }] }],
  });
  assert.equal(items[0].url, 'https://drive.google.com/file/d/abc/view');
  assert.equal(items[0].section, 'books');
});

test('summary counts per section and subject', () => {
  const summary = summarize(buildIndex(source, raw).items);
  assert.equal(summary.get('pyq').count, 5);
  assert.equal(summary.get('pyq').subjects.get('MA-111  Eng. Mathematics-1'), 5);
  assert.equal(summary.get('other: Lab manuals').count, 1);
});

test('sources.json is valid; bad entries are rejected', () => {
  assert.equal(validateSources(sources).length, 1);
  assert.throws(() => validateSources([{ ...source, branches: ['ec'] }]), SyncError);
  assert.throws(() => validateSources([{ ...source, years: [] }]), SyncError);
  assert.throws(() => validateSources([source, source]), /twice/);
  assert.throws(() => validateSources([{ ...source, courseId: undefined }]), /courseId/);
});

test('a Workspace block is reported in plain words', () => {
  assert.match(oauthError('admin_policy_enforced').message, /NITH's Google Workspace is blocking/);
  assert.match(oauthError('access_denied', 'admin_policy_enforced').message, /blocking/);
  assert.match(oauthError('invalid_grant', 'Token has been expired or revoked.').message, /Testing/);
  assert.match(apiError(403, { error: { message: '@ClassroomApiDisabled The user is not permitted to access the Classroom API.' } }, '1').message, /turned off Classroom API/);
  assert.match(apiError(403, { error: { message: 'Classroom API has not been used in project 123 before or it is disabled.' } }, '1').message, /not enabled/);
  assert.match(apiError(404, { error: { message: 'Requested entity was not found.' } }, '1').message, /member of the class/);
});

test('login asks only for the three read-only scopes a student account can grant', () => {
  assert.deepEqual(SCOPES.map((s) => s.replace('https://www.googleapis.com/auth/', '')), [
    'classroom.courses.readonly',
    'classroom.topics.readonly',
    'classroom.courseworkmaterials.readonly',
  ]);
});
