// Pure parsing: notice page HTML → rows, dates from URLs, the academic filter. No I/O here.

import { parse } from 'node-html-parser';

/** A page that loaded but doesn't look like a notice page any more. */
export class PageError extends Error {}

const collapse = (text) => String(text ?? '').replace(/\s+/g, ' ').trim();

/**
 * The notice table: one with a "Sl. No." / "Particulars" / "Description" header, or failing that
 * the site's notice table classes (the exam page's table has an empty header).
 */
function findTable(root) {
  const tables = root.querySelectorAll('table');
  const byHeader = tables.find((t) => {
    const head = collapse(t.querySelector('thead')?.text ?? '').toLowerCase();
    return head.includes('sl. no') && (head.includes('particulars') || head.includes('description'));
  });
  if (byHeader) return byHeader;
  return tables.find((t) => /\b(mytabnum|stafftable)\b/.test(t.getAttribute('class') ?? '')) ?? null;
}

/** "29-10-2026" → "2026-10-29". */
function pageDate(text) {
  const m = /^(\d{2})-(\d{2})-(\d{4})$/.exec(collapse(text));
  if (!m) return null;
  const [, dd, mm, yyyy] = m;
  const date = `${yyyy}-${mm}-${dd}`;
  return Number.isNaN(Date.parse(date)) ? null : date;
}

/**
 * Rows of the notice table: `{ title, url, postedOn? }`, in page order (the site lists newest
 * first). Throws [PageError] when there is no notice table at all. An empty table is not an
 * error: the exam page is sometimes empty.
 */
export function parsePage(html, pageUrl) {
  const root = parse(html);
  const table = findTable(root);
  if (!table) throw new PageError(`No notice table on ${pageUrl}. The page layout may have changed.`);
  const rows = [];
  for (const tr of table.querySelectorAll('tr')) {
    if (tr.closest('thead')) continue;
    const link = tr.querySelectorAll('a').find((a) => {
      const href = (a.getAttribute('href') ?? '').trim();
      return href && !href.startsWith('#') && !/^(javascript|mailto|tel):/i.test(href);
    });
    if (!link) continue;
    let url;
    try {
      url = new URL(link.getAttribute('href').trim(), pageUrl).href;
    } catch {
      continue;
    }
    const title = collapse(link.text);
    if (!title) continue;
    const postedOn = tr.querySelectorAll('td').map((td) => pageDate(td.text)).find(Boolean) ?? null;
    rows.push(postedOn ? { title, url, postedOn } : { title, url });
  }
  return rows;
}

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** `/uploads/files/oct26/x.pdf` → "2026-10"; also sept26, june2026, aug-25. Else null. */
export function monthFromUrl(url) {
  const m = /\/uploads\/files\/([a-z]+)[-_]?(\d{2}|\d{4})\//i.exec(String(url ?? ''));
  if (!m) return null;
  const month = MONTHS.indexOf(m[1].slice(0, 3).toLowerCase());
  if (month < 0) return null;
  const year = m[2].length === 2 ? 2000 + Number(m[2]) : Number(m[2]);
  return `${year}-${String(month + 1).padStart(2, '0')}`;
}

/**
 * Older uploads (`/uploads/topics/…17244223408638.pdf`) end in an upload timestamp whose first
 * ten digits are Unix seconds. → "2024-08-23", or null if there is none or it's implausible.
 */
export function dateFromUploadStamp(url, now = new Date()) {
  const name = String(url ?? '').split(/[?#]/)[0].split('/').pop() ?? '';
  const m = /(\d{13,14})\.[a-z0-9]+$/i.exec(name);
  if (!m) return null;
  const ms = Number(m[1].slice(0, 10)) * 1000;
  if (ms < Date.UTC(2010, 0, 1) || ms > now.getTime() + 86_400_000) return null;
  return new Date(ms).toISOString().slice(0, 10);
}

/** The best date for a row: the page's own date, the upload stamp, the month folder. */
export function bestDate(row, now = new Date()) {
  return row.postedOn ?? dateFromUploadStamp(row.url, now) ?? monthFromUrl(row.url);
}

/** "2026-10-29" or "2026-10" → "October 2026". */
export function monthLabel(date) {
  const m = /^(\d{4})-(\d{2})/.exec(String(date ?? ''));
  return m ? `${MONTH_NAMES[Number(m[2]) - 1]} ${m[1]}` : null;
}

/** Whole-word, any case, plural allowed: "fee" matches "Fees" but not "Feedback". */
export function matchesAny(title, keywords) {
  const text = String(title ?? '').toLowerCase();
  return keywords.some((keyword) => {
    const escaped = keyword.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/ /g, '\\s*');
    return new RegExp(`(?<![a-z])${escaped}(e?s)?(?![a-z])`).test(text);
  });
}
