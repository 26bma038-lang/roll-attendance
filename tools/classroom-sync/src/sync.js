// `npm run dry-run`: reads every course in sources.json and prints the material index.
//
//   --dry-run          required for now (Firestore writes arrive in Phase 2)
//   --source <id>      only this source
//   --out <file>       write the index JSON to a file instead of stdout
//   --save-raw <file>  also save the raw Classroom API responses (for debugging the parser)
//   --fixture <file>   read raw responses from a file saved with --save-raw instead of the API

import { readFileSync, writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { SyncError, accessToken } from './auth.js';
import { fetchCourse } from './classroom.js';
import { buildIndex, summarize } from './index.js';
import { loadSources } from './sources.js';

function printSummary(source, course, items, warnings) {
  const log = (line = '') => console.error(line);
  log();
  log(`${source.id}: ${course?.name ?? source.title}  (${items.length} files)`);
  log(`  credit: ${source.credit}`);
  if (/<[^>]+>/.test(source.credit)) log('  warning: the credit line still has a placeholder; fill in the senior\'s name in sources.json');
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
      source: { type: 'string' },
      out: { type: 'string' },
      'save-raw': { type: 'string' },
      fixture: { type: 'string' },
    },
  });
  if (!values['dry-run']) {
    throw new SyncError('Only --dry-run is available so far: writing to Firestore comes in Phase 2.');
  }

  let sources = loadSources();
  if (values.source) {
    sources = sources.filter((s) => s.id === values.source);
    if (!sources.length) throw new SyncError(`No source "${values.source}" in sources.json.`);
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
  }

  if (values['save-raw']) writeFileSync(values['save-raw'], JSON.stringify(raws, null, 2) + '\n');
  const json = JSON.stringify(output, null, 2) + '\n';
  if (values.out) {
    writeFileSync(values.out, json);
    console.error(`\nIndex written to ${values.out}`);
  } else {
    process.stdout.write(json);
  }
}

main().catch((error) => {
  console.error(`\n${error instanceof SyncError ? error.message : error.stack ?? error}`);
  process.exitCode = 1; // not process.exit(): let open handles close first (libuv asserts on Windows otherwise)
});
