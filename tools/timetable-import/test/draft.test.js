import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { classIdOf, normalizeCode, parseDay, parseTime, validateDraft } from '../src/draft.js';
import { formatClass } from '../src/format.js';

const sample = () => JSON.parse(readFileSync(new URL('./fixtures/sample-draft.json', import.meta.url), 'utf8'));

/** One small valid class to break in each test. */
const draftWith = (slots, subjects = [{ code: 'MA-111', name: 'Engineering Mathematics-1', type: 'lecture' }]) => ({
  classes: [{ batch: 2026, branch: 'MA', subjects, slots }],
});
const slot = (extra = {}) => ({ day: 'Mon', start: '09:00', end: '09:50', subject: 'MA-111', type: 'lecture', room: 'LH-101', ...extra });

test('the made-up sample draft is valid', () => {
  const { errors, warnings, classes } = validateDraft(sample());
  assert.deepEqual(errors, []);
  assert.equal(classes.length, 3);
  assert.deepEqual(classes.map(classIdOf), ['nith-26-ma', 'nith-26-ec', 'nith-26-ee']);
  assert.ok(warnings.some((w) => /branch EE/.test(w)));
  assert.ok(warnings.some((w) => /Note from the extraction/.test(w)));
});

test('days: names, abbreviations, numbers; unknown days are errors', () => {
  assert.equal(parseDay('Mon'), 1);
  assert.equal(parseDay('saturday'), 6);
  assert.equal(parseDay('THU'), 4);
  assert.equal(parseDay(7), 7);
  assert.equal(parseDay('Funday'), null);
  assert.equal(parseDay(8), null);
  const { errors } = validateDraft(draftWith([slot({ day: 'Funday' })]));
  assert.match(errors[0], /unknown day "Funday"/);
});

test('times: 24-hour and am/pm; nonsense is an error', () => {
  assert.equal(parseTime('09:00'), 540);
  assert.equal(parseTime('9.50'), 590);
  assert.equal(parseTime('2:30 PM'), 870);
  assert.equal(parseTime('12:15 am'), 15);
  assert.equal(parseTime('25:00'), null);
  assert.equal(parseTime('9:75'), null);
  assert.equal(parseTime('nine'), null);
  assert.match(validateDraft(draftWith([slot({ start: 'nine' })])).errors[0], /start "nine"/);
});

test('end before start is an error', () => {
  const { errors } = validateDraft(draftWith([slot({ start: '10:40', end: '09:50' })]));
  assert.equal(errors.length, 1);
  assert.match(errors[0], /ends \(09:50\) before it starts \(10:40\)/);
  assert.match(validateDraft(draftWith([slot({ start: '09:00', end: '09:00' })])).errors[0], /before it starts/);
});

test('overlapping slots on the same day are an error', () => {
  const { errors } = validateDraft(draftWith([slot(), slot({ start: '09:30', end: '10:20' })]));
  assert.equal(errors.length, 1);
  assert.match(errors[0], /overlap on Mon \(09:00–09:50 and 09:30–10:20\)/);
  // Back to back is fine; another day is fine.
  assert.deepEqual(validateDraft(draftWith([slot(), slot({ start: '09:50', end: '10:40' }), slot({ day: 'Tue' })])).errors, []);
});

test('lab groups may overlap; the same group, or a group with the whole class, may not', () => {
  const lab = (group) => slot({ start: '14:00', end: '16:30', type: 'lab', group });
  assert.deepEqual(validateDraft(draftWith([lab('G1'), lab('G2')])).errors, []);
  assert.equal(validateDraft(draftWith([lab('G1'), lab('G1')])).errors.length, 1);
  assert.equal(validateDraft(draftWith([lab('G1'), lab(null)])).errors.length, 1);
});

test('a slot whose subject code is missing from the subject list is an error', () => {
  const { errors } = validateDraft(draftWith([slot({ subject: 'PH-101' })]));
  assert.match(errors[0], /subject "PH-101" isn't in this class's subject list/);
});

test('codes are normalised before matching: "MA 111" in a slot matches MA-111', () => {
  const { errors, classes } = validateDraft(draftWith([slot({ subject: 'ma 111' })]));
  assert.deepEqual(errors, []);
  assert.equal(classes[0].slots[0].subject, 'MA-111');
  assert.equal(normalizeCode('CS102'), 'CS-102');
  assert.equal(normalizeCode('Library'), 'Library');
});

test('class-level problems: batch, branch, duplicates, sections, empty lists', () => {
  assert.match(validateDraft({ classes: [{ batch: 26, branch: 'MA', subjects: [], slots: [] }] }).errors.join('\n'), /year of admission/);
  assert.match(validateDraft({}).errors[0], /"classes" list/);
  const twice = draftWith([slot()]);
  twice.classes.push(structuredClone(twice.classes[0]));
  assert.match(validateDraft(twice).errors.join('\n'), /appears twice/);
  const section = draftWith([slot()]);
  section.classes[0].section = 'A';
  assert.match(validateDraft(section).warnings.join('\n'), /no sections; apply skips section A/);
  assert.match(validateDraft(draftWith([])).errors.join('\n'), /no slots/);
});

test('subject problems: type, duplicates, long codes; unused subjects warn', () => {
  const bad = validateDraft(draftWith([slot()], [
    { code: 'MA-111', name: 'Maths', type: 'seminar' },
    { code: 'MA-111', name: 'Maths again', type: 'lecture' },
    { code: 'VERYLONGCODE-1', name: 'X', type: 'lecture' },
  ]));
  assert.ok(bad.errors.some((e) => /lecture, lab or tutorial, not "seminar"/.test(e)));
  assert.ok(bad.errors.some((e) => /MA-111 is listed twice/.test(e)));
  assert.ok(bad.errors.some((e) => /longer than 10/.test(e)));
  const unused = validateDraft(draftWith([slot()], [
    { code: 'MA-111', name: 'Maths', type: 'lecture' },
    { code: 'PH-101', name: 'Physics', type: 'lecture' },
  ]));
  assert.deepEqual(unused.errors, []);
  assert.match(unused.warnings.join('\n'), /PH-101 has no slots/);
});

test('the printed table lists subjects and the week', () => {
  const [ma] = validateDraft(sample()).classes;
  const table = formatClass(ma);
  assert.match(table, /^MA 2026  \(nith-26-ma\)/);
  assert.match(table, /MA-111 {4}lecture {3}Engineering Mathematics-1/);
  assert.match(table, /14:00–16:30 {2}PH-102 {4}lab · Physics Lab 1 · G1/);
});
