// Builds test/fixtures/sample-timetable.pdf: a made-up NITH-style timetable (one page per class)
// drawn from test/fixtures/sample-draft.json, so `npm run extract` can be tried end to end before
// a real timetable PDF exists. Plain PDF 1.4, Helvetica, no dependencies.
//
//   npm run sample-pdf

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DAY_NAMES, formatTime, validateDraft } from '../src/draft.js';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const draft = JSON.parse(readFileSync(join(ROOT, 'test', 'fixtures', 'sample-draft.json'), 'utf8'));
const { classes, errors } = validateDraft(draft);
if (errors.length) throw new Error(errors.join('\n'));

const PERIODS = [[540, 590], [590, 640], [660, 710], [710, 760], [840, 990]]; // 9:00 … 16:30, lunch 12:40-14:00
const W = 842; // A4 landscape
const H = 595;

const esc = (text) => String(text).replace(/[\\()]/g, (c) => `\\${c}`).replace(/[^\x20-\x7e]/g, '-');

function page(cls) {
  const ops = [];
  const text = (x, y, size, s, bold = false) => ops.push(`BT /${bold ? 'F2' : 'F1'} ${size} Tf ${x} ${y} Td (${esc(s)}) Tj ET`);
  const line = (x1, y1, x2, y2) => ops.push(`${x1} ${y1} m ${x2} ${y2} l S`);
  text(40, H - 40, 13, 'NATIONAL INSTITUTE OF TECHNOLOGY HAMIRPUR (SAMPLE, NOT A REAL TIMETABLE)', true);
  text(40, H - 60, 11, `Time Table: ${cls.label}`, true);
  const left = 40;
  const top = H - 90;
  const dayW = 60;
  const colW = (W - 80 - dayW) / PERIODS.length;
  const rowH = 62;
  ops.push('0.6 w');
  // Header row.
  text(left + 6, top - 16, 9, 'Day / Time', true);
  PERIODS.forEach(([s, e], i) => text(left + dayW + i * colW + 6, top - 16, 9, `${formatTime(s)}-${formatTime(e)}`, true));
  const days = [1, 2, 3, 4, 5];
  const bottom = top - 24 - days.length * rowH;
  for (let r = 0; r <= days.length; r++) line(left, top - 24 - r * rowH, W - 40, top - 24 - r * rowH);
  line(left, top, W - 40, top);
  line(left, top, left, bottom);
  line(left + dayW, top, left + dayW, bottom);
  for (let i = 1; i <= PERIODS.length; i++) line(left + dayW + i * colW, top, left + dayW + i * colW, bottom);
  days.forEach((day, r) => {
    const y = top - 24 - r * rowH - 16;
    text(left + 6, y, 10, DAY_NAMES[day - 1], true);
    PERIODS.forEach(([s, e], i) => {
      const here = cls.slots.filter((slot) => slot.day === day && slot.start < e && slot.end > s);
      here.forEach((slot, k) => {
        const name = cls.subjects.find((sub) => sub.code === slot.subject)?.name ?? '';
        const x = left + dayW + i * colW + 5;
        const label = `${slot.subject}${slot.type === 'lab' ? ' (P)' : slot.type === 'tutorial' ? ' (T)' : ''}${slot.group ? ` ${slot.group}` : ''}`;
        text(x, y - k * 26, 8, label, true);
        text(x, y - k * 26 - 10, 7, `${name.slice(0, 26)} ${slot.room}`);
      });
    });
  });
  text(40, bottom - 20, 8, 'Lunch 12:40-14:00. (L) lecture, (T) tutorial, (P) practical. G1/G2: lab groups.');
  return ops.join('\n');
}

// Assemble the PDF.
const objects = [];
const add = (body) => objects.push(body) && objects.length;
const catalog = add('');
const pages = add('');
const f1 = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
const f2 = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>');
const kids = classes.map((cls) => {
  const content = page(cls);
  const stream = add(`<< /Length ${Buffer.byteLength(content)} >>\nstream\n${content}\nendstream`);
  return add(`<< /Type /Page /Parent ${pages} 0 R /MediaBox [0 0 ${W} ${H}] /Contents ${stream} 0 R /Resources << /Font << /F1 ${f1} 0 R /F2 ${f2} 0 R >> >> >>`);
});
objects[catalog - 1] = `<< /Type /Catalog /Pages ${pages} 0 R >>`;
objects[pages - 1] = `<< /Type /Pages /Kids [${kids.map((k) => `${k} 0 R`).join(' ')}] /Count ${kids.length} >>`;

let pdf = '%PDF-1.4\n';
const offsets = [];
objects.forEach((body, i) => {
  offsets.push(Buffer.byteLength(pdf));
  pdf += `${i + 1} 0 obj\n${body}\nendobj\n`;
});
const xref = Buffer.byteLength(pdf);
pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
pdf += `trailer\n<< /Size ${objects.length + 1} /Root ${catalog} 0 R >>\nstartxref\n${xref}\n%%EOF\n`;

const out = join(ROOT, 'test', 'fixtures', 'sample-timetable.pdf');
writeFileSync(out, pdf, 'latin1');
console.log(`Wrote ${out} (${classes.length} pages)`);
