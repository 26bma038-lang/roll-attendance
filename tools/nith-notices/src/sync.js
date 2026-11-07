// `npm run sync`: reads the NITH notice pages and writes collegeFeed/nith when something changed.
// `npm run dry-run`: prints what it found, writes nothing.
//
//   --dry-run          print, don't write
//   --json             with --dry-run: also print the feed as JSON on stdout
//   --fixtures <dir>   read <dir>/<page>.html instead of the website (tests, emulator checks)
//
// A page that fails (network, HTTP error, no notice table) keeps its old notices, and the run
// exits with an error so GitHub Actions emails the owner.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseArgs } from 'node:util';
import { CATEGORY_NAMES, loadConfig } from './config.js';
import { buildFeed, feedHash, pageKey } from './feed.js';
import { fetchPages } from './fetch.js';

const log = (line = '') => console.error(line);

/** A fetch() stand-in that serves saved pages, <dir>/academic-notices.html and so on. */
export function fixtureFetch(dir) {
  return async (url) => {
    try {
      const html = readFileSync(join(dir, `${pageKey(url)}.html`), 'utf8');
      return { ok: true, status: 200, text: async () => html };
    } catch {
      return { ok: false, status: 404, text: async () => '' };
    }
  };
}

function printSummary(results, feed) {
  for (const result of results) {
    log(result.ok ? `${result.page.url}: ${result.rows.length} rows` : `${result.page.url}: FAILED (${result.error})`);
  }
  const counts = CATEGORY_NAMES.map((c) => `${c} ${feed.items.filter((i) => i.category === c).length}`).join(', ');
  log(`\nFeed: ${feed.items.length} notices (${counts})`);
  for (const item of feed.items.slice(0, 15)) {
    log(`  ${item.sortDate}  ${item.category.padEnd(8)}  ${item.title.slice(0, 90)}`);
  }
  if (feed.items.length > 15) log(`  … ${feed.items.length - 15} more`);
  for (const warning of feed.warnings) log(`warning: ${warning}`);
}

async function main() {
  const { values } = parseArgs({
    options: {
      'dry-run': { type: 'boolean', default: false },
      json: { type: 'boolean', default: false },
      fixtures: { type: 'string' },
    },
  });
  const config = loadConfig();
  const now = new Date();

  let firestore = null;
  if (!values['dry-run']) {
    const { openFirestore, readFeed, writeFeed } = await import('./firestore.js'); // only the real sync needs firebase-admin
    firestore = { ...openFirestore(), readFeed, writeFeed };
    log(`Writing to Firestore project ${firestore.projectId}${process.env.FIRESTORE_EMULATOR_HOST ? ' (emulator)' : ''}.`);
  }

  const results = await fetchPages(config, values.fixtures ? fixtureFetch(values.fixtures) : fetch);
  const previous = firestore ? await firestore.readFeed(firestore.db) : null;
  const feed = buildFeed(results, previous, now, config);
  printSummary(results, feed);
  if (values['dry-run'] && values.json) process.stdout.write(JSON.stringify(feed.items, null, 2) + '\n');

  if (firestore) {
    const hash = feedHash(feed);
    if (previous?.contentHash === hash) {
      log('\nunchanged: collegeFeed/nith, nothing written');
    } else {
      await firestore.writeFeed(firestore.db, feed, hash);
      log(`\nwritten: collegeFeed/nith, ${feed.items.length} notices`);
    }
  }

  if (feed.errors.length) {
    throw new Error(`${feed.errors.length} page(s) failed; their old notices were kept:\n  ${feed.errors.join('\n  ')}`);
  }
}

main().catch((error) => {
  const message = error?.message ?? String(error);
  if (process.env.GITHUB_ACTIONS) console.error(`::error::${message.split('\n')[0]}`);
  console.error(`\n${message}`);
  process.exitCode = 1; // not process.exit(): let open handles close first
});
