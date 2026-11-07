import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { CONFIG_FILE, loadConfig, validateConfig } from '../src/config.js';
import { PageError, bestDate, dateFromUploadStamp, matchesAny, monthFromUrl, monthLabel, parsePage } from '../src/parse.js';

const fixture = (name) => readFileSync(new URL(`./fixtures/${name}.html`, import.meta.url), 'utf8');
const config = loadConfig();

// ---------- the three saved pages (nith.ac.in, 7 Nov 2026) ----------

test('academic notices: every row, absolute URLs, whitespace collapsed', () => {
  const rows = parsePage(fixture('academic-notices'), 'https://nith.ac.in/academic-notices');
  assert.equal(rows.length, 212);
  assert.deepEqual(rows[0], {
    title: 'PG End Semester Theory Examination Date Sheet (November/December 2026)',
    url: 'https://nith.ac.in/uploads/files/oct26/pg-datesheet.pdf',
  });
  assert.ok(rows.every((r) => /^https?:\/\//.test(r.url)));
  assert.ok(rows.every((r) => r.title === r.title.replace(/\s+/g, ' ').trim() && r.title.length > 0));
  // The site lists some rows twice; the parser keeps them, the feed dedupes.
  assert.equal(new Set(rows.map((r) => r.url)).size, 207);
});

test('examination schedules: the table is there but empty today, which is not an error', () => {
  assert.deepEqual(parsePage(fixture('examination-schedules'), 'https://nith.ac.in/examination-schedules'), []);
});

test('student notices: rows carry the page\'s own upload date', () => {
  const rows = parsePage(fixture('student-related-notices'), 'https://nith.ac.in/student-related-notices');
  assert.equal(rows.length, 287);
  assert.equal(rows[0].postedOn, '2026-10-29');
  assert.match(rows[0].title, /^Suspension of Classes on 30\/10\/2026/);
  assert.equal(rows.filter((r) => r.postedOn).length, 286); // one row has no date cell
});

test('a page without a notice table throws PageError', () => {
  assert.throws(() => parsePage('<html><body><p>Under maintenance</p></body></html>', 'https://nith.ac.in/x'), PageError);
});

test('links that go nowhere are skipped; relative links become absolute', () => {
  const html = `<table class="mytabnum"><thead><tr><th>Sl. No.</th><th>Particulars</th></tr></thead><tbody>
    <tr><td></td><td><a href="#">Nothing</a></td></tr>
    <tr><td></td><td><a href="javascript:void(0)">Script</a></td></tr>
    <tr><td></td><td><a href="/uploads/files/nov26/a.pdf">  Notice
       about   fees &amp; dues </a></td></tr>
    <tr><td></td><td>No link at all</td></tr></tbody></table>`;
  assert.deepEqual(parsePage(html, 'https://nith.ac.in/academic-notices'), [
    { title: 'Notice about fees & dues', url: 'https://nith.ac.in/uploads/files/nov26/a.pdf' },
  ]);
});

// ---------- dates ----------

test('month folder in the URL', () => {
  assert.equal(monthFromUrl('https://nith.ac.in/uploads/files/oct26/x.pdf'), '2026-10');
  assert.equal(monthFromUrl('https://nith.ac.in/uploads/files/sept26/x.pdf'), '2026-09');
  assert.equal(monthFromUrl('https://nith.ac.in/uploads/files/june2025/x.pdf'), '2025-06');
  assert.equal(monthFromUrl('https://nith.ac.in/uploads/files/jan-24/x.pdf'), '2024-01');
  assert.equal(monthFromUrl('https://nith.ac.in/uploads/files/misc/x.pdf'), null);
  assert.equal(monthFromUrl('https://nith.ac.in/uploads/files/abc26/x.pdf'), null);
  assert.equal(monthFromUrl('https://exams.nith.ac.in/'), null);
});

test('upload timestamp at the end of older file names', () => {
  const now = new Date('2026-11-07T00:00:00Z');
  assert.equal(dateFromUploadStamp('https://nith.ac.in/uploads/topics/17244223408638.pdf', now), '2024-08-23');
  assert.equal(dateFromUploadStamp('https://nith.ac.in/uploads/topics/academic-calander-even17180752079089.pdf', now), '2024-06-11');
  assert.equal(dateFromUploadStamp('https://nith.ac.in/uploads/topics/notice-12.pdf', now), null);
  assert.equal(dateFromUploadStamp('https://nith.ac.in/uploads/topics/x99999999999999.pdf', now), null); // in the future
});

test('best date: page date, then upload stamp, then month folder', () => {
  const now = new Date('2026-11-07T00:00:00Z');
  assert.equal(bestDate({ url: 'https://nith.ac.in/uploads/files/oct26/a.pdf', postedOn: '2026-10-29' }, now), '2026-10-29');
  assert.equal(bestDate({ url: 'https://nith.ac.in/uploads/topics/a17244223408638.pdf' }, now), '2024-08-23');
  assert.equal(bestDate({ url: 'https://nith.ac.in/uploads/files/oct26/a.pdf' }, now), '2026-10');
  assert.equal(bestDate({ url: 'https://exams.nith.ac.in/' }, now), null);
});

test('month labels', () => {
  assert.equal(monthLabel('2026-10-29'), 'October 2026');
  assert.equal(monthLabel('2026-01'), 'January 2026');
  assert.equal(monthLabel(null), null);
});

// ---------- the academic filter ----------

test('academic keywords: whole words, any case, plurals', () => {
  const keep = (title) => matchesAny(title, config.academicKeywords);
  assert.ok(keep('Suspension of Classes on 30/10/2026'));
  assert.ok(keep('Revised DATE SHEET for re-appear examinations'));
  assert.ok(keep('Datesheet of minor tests'));
  assert.ok(keep('Fee payment schedule'));
  assert.ok(keep('Fees for hostel'));
  assert.ok(keep('Results of the even semester'));
  assert.ok(keep('Revised Time Table for 3rd semester'));
  assert.ok(keep('Holiday on account of Diwali'));
  assert.ok(keep('Backlog registration'));
  assert.ok(!keep('Feedback form for the mess'));
  assert.ok(!keep('Invitation to join the Swachh Bharat Abhiyan Student Committee'));
  assert.ok(!keep('List of Selected Students for SHIELD (Cybersecurity Society)'));
  assert.ok(!keep('Example notice'));
});

test('the filter list is the one in the prompt', () => {
  assert.deepEqual(config.academicKeywords, [
    'exam', 'examination', 'date sheet', 'datesheet', 'result', 'academic', 'calendar', 'semester',
    'registration', 'classes', 'timetable', 'time table', 'scholarship', 'fee', 'holiday', 'suspension',
    'attendance', 're-appear', 'backlog',
  ]);
});

test('config.json is valid; mistakes are caught', () => {
  assert.equal(config.pages.length, 3);
  assert.deepEqual(config.pages.map((p) => [p.category, p.filter]), [['Academic', false], ['Exams', false], ['Students', true]]);
  assert.equal(config.timeoutMs, 20000);
  assert.ok(CONFIG_FILE.endsWith('config.json'));
  assert.throws(() => validateConfig({ ...config, pages: [] }), /pages/);
  assert.throws(() => validateConfig({ ...config, pages: [{ ...config.pages[0], category: 'News' }] }), /category/);
  assert.throws(() => validateConfig({ ...config, academicKeywords: [' exam'] }), /academicKeywords/);
  assert.throws(() => validateConfig({ ...config, userAgent: 'curl/8' }), /RollApp/);
});
