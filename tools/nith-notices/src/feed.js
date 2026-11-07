// Turns parsed pages + the previous feed into the next feed. Pure: unit-tested without network
// or Firestore.
//
// collegeFeed/nith
//   syncedAt, contentHash,
//   items: [{ id, title, url, category, page, sortDate, monthLabel?, firstSeenAt }]   newest first
//   seen:  { id: sortDate }   every notice on the pages, so dates stay put when one drops out of
//                             the newest 150 and comes back

import { createHash } from 'node:crypto';
import { bestDate, matchesAny, monthLabel } from './parse.js';

export const CATEGORIES = ['Exams', 'Academic', 'Students'];

/** Notice id: hash of the URL. */
export function noticeId(url) {
  return createHash('sha1').update(url).digest('hex').slice(0, 20);
}

/** "https://nith.ac.in/academic-notices" → "academic-notices". */
export function pageKey(url) {
  return new URL(url).pathname.replace(/^\/+|\/+$/g, '') || new URL(url).hostname;
}

/** "2026-10" → "2026-10-01"; days pass through. */
const toDay = (date) => (date && date.length === 7 ? `${date}-01` : date);

/**
 * The category for a row: the exam page is always Exams; date sheets and examination notices on
 * the other pages are Exams too (the exam page is often empty); otherwise the page's category.
 */
export function categoryOf(row, page, config) {
  if (page.category === 'Exams') return 'Exams';
  if (matchesAny(row.title, config.examKeywords ?? [])) return 'Exams';
  return page.category;
}

/**
 * @param results   one per config page, in config order:
 *                  `{ page, ok: true, rows }` or `{ page, ok: false, error }`
 * @param previous  the current document's data, or null on the very first sync
 * @param now       Date of this run
 * @returns `{ items, seen, errors, warnings }`
 */
export function buildFeed(results, previous, now, config) {
  const errors = [];
  const warnings = [];
  const today = now.toISOString().slice(0, 10);
  const firstSync = !previous;
  const oldItems = previous?.items ?? [];
  const oldById = new Map(oldItems.map((item) => [item.id, item]));
  const oldSeen = previous?.seen ?? {};

  const candidates = []; // { item fields…, order }
  const keptPages = new Set();
  let order = 0;

  for (const result of results) {
    const key = pageKey(result.page.url);
    const previousOfPage = oldItems.filter((item) => item.page === key);
    if (!result.ok) {
      errors.push(`${result.page.url}: ${result.error}`);
      keptPages.add(key);
      continue;
    }
    if (result.rows.length === 0 && previousOfPage.length > 0) {
      // A page that had notices and now shows none is more likely broken than emptied.
      warnings.push(`${result.page.url} shows no notices now; keeping the ${previousOfPage.length} from before.`);
      keptPages.add(key);
      continue;
    }

    const rows = result.page.filter
      ? result.rows.filter((row) => matchesAny(row.title, config.academicKeywords))
      : result.rows;

    // On the very first sync, undated rows take the date of the next dated row below them (the
    // site lists newest first), so old undated notices don't all look new today.
    const inherited = new Array(rows.length).fill(null);
    let below = null;
    for (let i = rows.length - 1; i >= 0; i--) {
      const date = bestDate(rows[i], now);
      if (date) below = toDay(date);
      inherited[i] = below;
    }
    // Undated rows at the very bottom (the oldest) take the last date above them instead.
    let above = null;
    for (let i = 0; i < rows.length; i++) {
      if (inherited[i]) above = inherited[i];
      else inherited[i] = above;
    }

    rows.forEach((row, i) => {
      const id = noticeId(row.url);
      const real = toDay(bestDate(row, now));
      candidates.push({
        id,
        title: row.title,
        url: row.url,
        category: categoryOf(row, result.page, config),
        page: key,
        realDate: real,
        fallbackDate: firstSync ? (inherited[i] ?? today) : today,
        order: order++,
      });
    });
  }

  // Pages that failed keep their old items, as they were.
  for (const item of oldItems) {
    if (keptPages.has(item.page)) candidates.push({ ...item, kept: true, order: order++ });
  }

  // One entry per URL. The first occurrence keeps its title; the category is the strongest seen.
  const byId = new Map();
  for (const c of candidates) {
    const existing = byId.get(c.id);
    if (!existing) {
      byId.set(c.id, c);
    } else if (CATEGORIES.indexOf(c.category) < CATEGORIES.indexOf(existing.category)) {
      existing.category = c.category;
    }
  }

  // Remember every date we saw; when a page failed, also everything remembered before, since that
  // page's rows could not be checked this time.
  const seen = keptPages.size ? { ...oldSeen } : {};
  const all = [...byId.values()].map((c) => {
    if (c.kept) {
      seen[c.id] = c.sortDate;
      const { kept, order: _, ...item } = c;
      return { item, order: c.order };
    }
    const old = oldById.get(c.id);
    const sortDate = c.realDate ?? old?.sortDate ?? oldSeen[c.id] ?? c.fallbackDate;
    seen[c.id] = sortDate;
    const item = {
      id: c.id,
      title: c.title,
      url: c.url,
      category: c.category,
      page: c.page,
      sortDate,
      firstSeenAt: old?.firstSeenAt ?? now.toISOString(),
    };
    const label = monthLabel(c.realDate);
    if (label) item.monthLabel = label;
    return { item, order: c.order };
  });

  all.sort((a, b) => b.item.sortDate.localeCompare(a.item.sortDate) || a.order - b.order);
  const items = all.slice(0, config.keep).map((entry) => entry.item);
  return { items, seen, errors, warnings };
}

/** Hash of what students see plus the remembered dates. Same hash → no write. */
export function feedHash(feed) {
  return createHash('sha256').update(JSON.stringify({ items: feed.items, seen: feed.seen })).digest('hex');
}
