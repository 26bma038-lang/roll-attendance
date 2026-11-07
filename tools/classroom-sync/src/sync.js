// `npm run sync`: reads every course in sources.json and writes its material index to Firestore,
// then deletes source documents sources.json no longer lists.
// `npm run dry-run`: the same, but prints the index instead of writing it.
//
//   --dry-run          print the index, write nothing
//   --force            write even if nothing changed, or if Classroom returned no files
//   --source <id>      only this source
//   --out <file>       also write the index JSON to a file (a dry run prints it to stdout otherwise)
//   --save-raw <file>  also save the raw Classroom API responses (for debugging the parser)
//   --fixture <file>   read raw responses from a file saved with --save-raw instead of the API

import { readFileSync, writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { SyncError, accessToken } from './auth.js';
import { fetchCourse } from './classroom.js';
import { buildIndex, summarize } from './index.js';
import { hasPlaceholder, loadSources } from './sources.js';

function printSummary(source, course, items, warnings) {
  const log = (line = '') => console.error(line);
  log();
  log(`${source.id}: ${course?.name ?? source.title}  (${items.length} files)`);
  log(`  credit: ${source.credit}`);
  if (hasPlaceholder(source.credit)) log('  warning: the credit line still has a placeholder; fill in the senior\'s name in sources.json');
  for (const [key, section] of summarize(items)) {
    log();
    const heading = key.startsWith('other: ') ? `other "${section.label}"` : `${key} "${section.label}"`;
    log(`  ${heading}: ${section.count}`);
    for (const [subject, count] of section.subjects) log(`      ${String(count).padStart(4)}  ${subject}`);
  }
  if (warnings.length) {
    log();
    for (const w of warnings) log(`  warning: ${w}`);
  }
}

async function main() {
  const { values } = parseArgs({
    options: {
      'dry-run': { type: 'boolean', default: false },
      force: { type: 'boolean', default: false },
      source: { type: 'string' },
      out: { type: 'string' },
      'save-raw': { type: 'string' },
      fixture: { type: 'string' },
    },
  });
  const dryRun = values['dry-run'];

  let sources = loadSources();
  if (values.source) {
    sources = sources.filter((s) => s.id === values.source);
    if (!sources.length) throw new SyncError(`No source "${values.source}" in sources.json.`);
  }

  // Check everything needed for writing before spending API calls.
  let firestore = null;
  if (!dryRun) {
    const unnamed = sources.filter((s) => hasPlaceholder(s.credit)).map((s) => s.id);
    if (unnamed.length) {
      throw new SyncError(`Fill in the credit line in sources.json before syncing (${unnamed.join(', ')}): Roll must credit the senior.`);
    }
    const { openFirestore, publishSource, removeStaleSources } = await import('./firestore.js'); // only the real sync needs firebase-admin
    firestore = { ...openFirestore(), publishSource, removeStaleSources };
    console.error(`Writing to Firestore project ${firestore.projectId}${process.env.FIRESTORE_EMULATOR_HOST ? ' (emulator)' : ''}.`);
  }

  const fixture = values.fixture ? JSON.parse(readFileSync(values.fixture, 'utf8')) : null;
  const token = fixture ? null : await accessToken();

  const raws = {};
  const output = [];
  for (const source of sources) {
    const raw = fixture ? fixture[source.id] : await fetchCourse(token, source.courseId);
    if (!raw) throw new SyncError(`The fixture has no data for "${source.id}".`);
    raws[source.id] = raw;
    const { items, warnings } = buildIndex(source, raw);
    printSummary(source, raw.course, items, warnings);
    const { id, title, credit, classroomUrl, years, branches } = source;
    output.push({ id, title, credit, classroomUrl, years, branches, courseName: raw.course?.name, items });

    if (firestore) {
      const result = await firestore.publishSource(firestore.db, source, items, { force: values.force });
      const parts = result.partCount ? ` in ${result.partCount} parts` : '';
      console.error(result.changed
        ? `\n  written: materialSources/${source.id}, ${result.itemCount} files${parts}`
        : `\n  unchanged: materialSources/${source.id}, nothing written`);
    }
  }

  // Every source synced: remove documents sources.json no longer lists (renamed or dropped
  // sources). Skipped with --source, which only saw part of the list.
  if (firestore && !values.source) {
    const removed = await firestore.removeStaleSources(firestore.db, sources);
    for (const id of removed) console.error(`\n  deleted: materialSources/${id} (no longer in sources.json)`);
  }

  if (values['save-raw']) writeFileSync(values['save-raw'], JSON.stringify(raws, null, 2) + '\n');
  const json = JSON.stringify(output, null, 2) + '\n';
  if (values.out) {
    writeFileSync(values.out, json);
    console.error(`\nIndex written to ${values.out}`);
  } else if (dryRun) {
    process.stdout.write(json);
  }
}

main().catch((error) => {
  const message = error instanceof SyncError ? error.message : error.stack ?? String(error);
  // On GitHub Actions the first line also becomes an error annotation on the run page.
  if (process.env.GITHUB_ACTIONS) console.error(`::error::${message.split('\n')[0]}`);
  console.error(`\n${message}`);
  process.exitCode = 1; // not process.exit(): let open handles close first (libuv asserts on Windows otherwise)
});
