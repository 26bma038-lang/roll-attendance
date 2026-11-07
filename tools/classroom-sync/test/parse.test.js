import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  SUNIL_SIR_LABEL,
  displayTitle,
  guessMimeType,
  mapTopic,
  parseAcademicYear,
  parseExam,
  parseSubject,
} from '../src/parse.js';

test('topics map to sections, case-insensitively', () => {
  const cases = {
    SYLLABUS: 'syllabus',
    'Academic Calendar': 'calendar',
    NOTES: 'notes',
    BOOKS: 'books',
    'PRACTICAL FILES': 'practicals',
    WORKSHOP: 'workshop',
    'Engineering Drawing': 'drawing',
    ASSIGNMENTS: 'assignments',
    'ASSIGNMENT SOLUTIONS': 'solutions',
    'QUESTION PAPERS': 'pyq',
    'question papers': 'pyq',
    '  Question   Papers ': 'pyq',
  };
  for (const [name, section] of Object.entries(cases)) {
    const topic = mapTopic(name);
    assert.equal(topic.section, section, name);
    assert.equal(topic.known, true, name);
  }
  assert.equal(mapTopic('QUESTION PAPERS').sectionLabel, 'Previous year papers');
});

test("Sunil Sir's notes are notes with a fixed label, curly apostrophe or not", () => {
  for (const name of ["SUNIL SIR'S NOTES", 'SUNIL SIR’S NOTES', "sunil sir's notes"]) {
    assert.deepEqual(mapTopic(name), {
      section: 'notes',
      subjectName: SUNIL_SIR_LABEL,
      sectionLabel: 'Notes',
      known: true,
    });
  }
});

test('unknown and missing topics go to other, keeping the raw name', () => {
  assert.deepEqual(mapTopic('Lab manuals'), { section: 'other', sectionLabel: 'Lab manuals', known: false });
  assert.deepEqual(mapTopic(undefined), { section: 'other', sectionLabel: 'No topic', known: false });
});

test('subject code and name come from the item title', () => {
  assert.deepEqual(parseSubject('MA-111(Eng. Mathematics-1)'), { subjectCode: 'MA-111', subjectName: 'Eng. Mathematics-1' });
  assert.deepEqual(parseSubject('MA 111 (Eng. Mathematics-1)'), { subjectCode: 'MA-111', subjectName: 'Eng. Mathematics-1' });
  assert.deepEqual(parseSubject('MA111(Maths)'), { subjectCode: 'MA-111', subjectName: 'Maths' });
  assert.deepEqual(parseSubject('HSS-101(English (Comm.))'), { subjectCode: 'HSS-101', subjectName: 'English (Comm.)' });
  assert.deepEqual(parseSubject('  EC-101  ( Basic Electronics ) '), { subjectCode: 'EC-101', subjectName: 'Basic Electronics' });
});

test('a bare code is a subject whose name is the code', () => {
  assert.deepEqual(parseSubject('CS-102'), { subjectCode: 'CS-102', subjectName: 'CS-102' });
  assert.deepEqual(parseSubject('PH-102 lab'), { subjectCode: 'PH-102', subjectName: 'PH-102' });
});

test('no code → null code, the title as name', () => {
  assert.deepEqual(parseSubject('Fourier Series'), { subjectCode: null, subjectName: 'Fourier Series' });
  assert.deepEqual(parseSubject('CIVIL'), { subjectCode: null, subjectName: 'CIVIL' });
  assert.deepEqual(parseSubject('ma-111(lower case)'), { subjectCode: null, subjectName: 'ma-111(lower case)' });
  assert.deepEqual(parseSubject('MA-1111(Too many digits)'), { subjectCode: null, subjectName: 'MA-1111(Too many digits)' });
});

test('exam from the file name', () => {
  assert.equal(parseExam('MID SEM 2024-25.pdf'), 'MID');
  assert.equal(parseExam('midsem.pdf'), 'MID');
  assert.equal(parseExam('END SEM 2025-26.pdf'), 'END');
  assert.equal(parseExam('Maths_End-sem.pdf'), 'END');
  assert.equal(parseExam('PAPER 1.pdf'), null);
  assert.equal(parseExam('Academic Calendar.pdf'), null);
  assert.equal(parseExam('Pyramid.pdf'), null);
  assert.equal(parseExam(undefined), null);
});

test('academic year from the file name', () => {
  assert.equal(parseAcademicYear('MID SEM 2024-25.pdf'), '2024-25');
  assert.equal(parseAcademicYear('END SEM 2024-2025.pdf'), '2024-25');
  assert.equal(parseAcademicYear('END SEM 2024_25.pdf'), '2024-25');
  assert.equal(parseAcademicYear('END SEM 2024 – 25.pdf'), '2024-25');
  assert.equal(parseAcademicYear('MID SEM 2023(NEW).pdf'), '2023');
  assert.equal(parseAcademicYear('PAPER 1.pdf'), null);
  assert.equal(parseAcademicYear('Roll 120235.pdf'), null);
});

test('display title drops the extension and stays readable', () => {
  assert.equal(displayTitle('MID SEM 2023(NEW).pdf'), 'MID SEM 2023 (NEW)');
  assert.equal(displayTitle('END SEM 2025-26.PDF'), 'END SEM 2025-26');
  assert.equal(displayTitle('Fourier_Series_notes.pdf'), 'Fourier Series notes');
  assert.equal(displayTitle('  PAPER   1.pdf '), 'PAPER 1');
  assert.equal(displayTitle('Notes (Unit 2).docx'), 'Notes (Unit 2)');
  assert.equal(displayTitle('Version 1.2'), 'Version 1.2');
});

test('mime type is guessed from the extension', () => {
  assert.equal(guessMimeType('a.pdf'), 'application/pdf');
  assert.equal(guessMimeType('a.PDF'), 'application/pdf');
  assert.equal(guessMimeType('Google Doc'), null);
});
