import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { loadConfig } from '../src/config.js';
import { buildFeed, categoryOf, feedHash, noticeId, pageKey } from '../src/feed.js';
import { fetchPages } from '../src/fetch.js';
import { parsePage } from '../src/parse.js';

const config = loadConfig();
const [academicPage, examPage, studentPage] = config.pages;
const now = new Date('2026-11-07T06:00:00Z');
const later = new Date('2026-11-08T06:00:00Z');

const fixture = (name) => readFileSync(new URL(`./fixtures/${name}.html`, import.meta.url), 'utf8');
const realResults = () => config.pages.map((page) => ({ page, ok: true, rows: parsePage(fixture(pageKey(page.url)), page.url) }));

const row = (title, file, extra = {}) => ({ title, url: `https://nith.ac.in/uploads/files/${file}`, ...extra });
const ok = (page, rows) => ({ page, ok: true, rows });

// ---------- the real pages ----------

test('real pages: 150 newest, no duplicate URLs, every category present, newest first', () => {
  const feed = buildFeed(realResults(), null, now, config);
  assert.equal(feed.items.length, 150);
  assert.equal(new Set(feed.items.map((i) => i.url)).size, 150);
  assert.deepEqual([...new Set(feed.items.map((i) => i.category))].sort(), ['Academic', 'Exams', 'Students']);
  const dates = feed.items.map((i) => i.sortDate);
  assert.deepEqual(dates, [...dates].sort().reverse());
  assert.equal(feed.items[0].title.slice(0, 31), 'Suspension of Classes on 30/10/');
  assert.deepEqual(feed.errors, []);
  // Old undated notices don't jump to the top on the first sync.
  assert.ok(feed.items.every((i) => i.sortDate <= '2026-10-29'));
});

test('real pages: the student page only keeps academic notices', () => {
  const feed = buildFeed(realResults(), null, now, config);
  const students = feed.items.filter((i) => i.page === 'student-related-notices');
  assert.ok(students.length > 0);
  assert.ok(!students.some((i) => /Swachh Bharat|SHIELD/.test(i.title)));
});

// ---------- dedupe, ids, categories ----------

test('the same URL twice is one notice; its id is the hash of the URL', () => {
  const feed = buildFeed([
    ok(academicPage, [row('Mid-sem date sheet', 'sept26/ds.pdf'), row('Mid-sem date sheet (again)', 'sept26/ds.pdf')]),
    ok(examPage, []),
    ok(studentPage, []),
  ], null, now, config);
  assert.equal(feed.items.length, 1);
  assert.equal(feed.items[0].id, noticeId('https://nith.ac.in/uploads/files/sept26/ds.pdf'));
  assert.equal(feed.items[0].title, 'Mid-sem date sheet');
});

test('date sheets are Exams wherever they appear; the exam page wins duplicates', () => {
  assert.equal(categoryOf({ title: 'UG End Semester Theory Examination Date Sheet' }, academicPage, config), 'Exams');
  assert.equal(categoryOf({ title: 'Academic Calendar 2026-27' }, academicPage, config), 'Academic');
  assert.equal(categoryOf({ title: 'Anything' }, examPage, config), 'Exams');
  const feed = buildFeed([
    ok(academicPage, []),
    ok(examPage, [row('Schedule', 'nov26/s.pdf')]),
    ok(studentPage, [row('Schedule of semester classes', 'nov26/s.pdf')]),
  ], null, now, config);
  assert.equal(feed.items[0].category, 'Exams');
});

// ---------- dates ----------

test('month label from the folder; dated rows sort by date', () => {
  const feed = buildFeed([
    ok(academicPage, [row('Calendar', 'aug26/cal.pdf'), row('Older', 'jan26/old.pdf')]),
    ok(examPage, []),
    ok(studentPage, [row('Suspension of classes', 'oct26/s.pdf', { postedOn: '2026-10-29' })]),
  ], null, now, config);
  assert.deepEqual(feed.items.map((i) => [i.title, i.sortDate, i.monthLabel]), [
    ['Suspension of classes', '2026-10-29', 'October 2026'],
    ['Calendar', '2026-08-01', 'August 2026'],
    ['Older', '2026-01-01', 'January 2026'],
  ]);
});

test('first sync: undated rows borrow the date below them; later, a new undated notice is dated today', () => {
  const rows = [row('Undated top', 'x/a.pdf'), row('Dated', 'sept26/b.pdf'), row('Undated bottom', 'y/c.pdf')];
  const first = buildFeed([ok(academicPage, rows), ok(examPage, []), ok(studentPage, [])], null, now, config);
  const byTitle = Object.fromEntries(first.items.map((i) => [i.title, i]));
  assert.equal(byTitle['Undated top'].sortDate, '2026-09-01');
  assert.equal(byTitle['Undated bottom'].sortDate, '2026-09-01');
  assert.equal(byTitle['Undated top'].monthLabel, undefined);
  assert.equal(byTitle['Undated top'].firstSeenAt, now.toISOString());

  const previous = { ...first, contentHash: feedHash(first) };
  const second = buildFeed([ok(academicPage, [row('Brand new, undated', 'z/new.pdf'), ...rows]), ok(examPage, []), ok(studentPage, [])], previous, later, config);
  assert.equal(second.items[0].title, 'Brand new, undated');
  assert.equal(second.items[0].sortDate, '2026-11-08');
  assert.equal(second.items[0].firstSeenAt, later.toISOString());
  // Known notices keep their first-seen time and date.
  const top = second.items.find((i) => i.title === 'Undated top');
  assert.equal(top.firstSeenAt, now.toISOString());
  assert.equal(top.sortDate, '2026-09-01');
});

test('a notice that fell out of the newest 150 keeps its date when it comes back', () => {
  const small = { ...config, keep: 1 };
  const rows = [row('Dated', 'sept26/b.pdf'), row('Undated', 'x/old.pdf')];
  const first = buildFeed([ok(academicPage, rows), ok(examPage, []), ok(studentPage, [])], null, now, small);
  assert.equal(first.items.length, 1);
  assert.equal(first.seen[noticeId('https://nith.ac.in/uploads/files/x/old.pdf')], '2026-09-01');
  const second = buildFeed([ok(academicPage, rows), ok(examPage, []), ok(studentPage, [])], first, later, { ...config, keep: 5 });
  assert.equal(second.items.find((i) => i.title === 'Undated').sortDate, '2026-09-01');
});

test('unchanged pages give the same hash, so nothing is written', () => {
  const first = buildFeed(realResults(), null, now, config);
  const second = buildFeed(realResults(), first, later, config);
  assert.equal(feedHash(second), feedHash(first));
});

// ---------- a page breaks ----------

test('page broke → nothing deleted: its old notices stay, the error is reported', () => {
  const first = buildFeed(realResults(), null, now, config);
  const oldAcademic = first.items.filter((i) => i.page === 'academic-notices');
  assert.ok(oldAcademic.length > 0);
  const results = realResults();
  results[0] = { page: academicPage, ok: false, error: 'HTTP 503' };
  const second = buildFeed(results, first, later, config);
  // (A notice also listed on the student page may now carry that page's date; it is still there.)
  for (const item of oldAcademic) assert.ok(second.items.some((i) => i.id === item.id), item.title);
  assert.deepEqual(second.errors, ['https://nith.ac.in/academic-notices: HTTP 503']);
  assert.equal(second.items.length, 150);
});

test('a page that had notices and now shows none keeps them, with a warning', () => {
  const first = buildFeed(realResults(), null, now, config);
  const results = realResults();
  results[2] = ok(studentPage, []);
  const second = buildFeed(results, first, later, config);
  assert.equal(second.items.filter((i) => i.page === 'student-related-notices').length, first.items.filter((i) => i.page === 'student-related-notices').length);
  assert.equal(second.warnings.length, 1);
  assert.deepEqual(second.errors, []);
});

test('fetchPages: HTTP errors, timeouts and missing tables become failed pages, never throws', async () => {
  const responses = {
    'https://nith.ac.in/academic-notices': async () => ({ ok: false, status: 500, text: async () => '' }),
    'https://nith.ac.in/examination-schedules': async () => { const e = new Error('timed out'); e.name = 'TimeoutError'; throw e; },
    'https://nith.ac.in/student-related-notices': async () => ({ ok: true, status: 200, text: async () => '<p>maintenance</p>' }),
  };
  const seenHeaders = [];
  const results = await fetchPages(config, (url, init) => { seenHeaders.push(init.headers['User-Agent']); return responses[url](); });
  assert.deepEqual(results.map((r) => r.ok), [false, false, false]);
  assert.equal(results[0].error, 'HTTP 500');
  assert.equal(results[1].error, 'no answer within 20 s');
  assert.match(results[2].error, /No notice table/);
  assert.ok(seenHeaders.every((ua) => ua.includes('RollApp/2.2')));
});
