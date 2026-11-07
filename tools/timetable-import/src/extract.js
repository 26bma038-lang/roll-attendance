// `npm run extract -- path/to/timetable.pdf`
//
// Sends the PDF to Gemini (free tier), writes timetable-draft.json, and prints a table per class
// to check against the PDF. Nothing is written to Firestore.
//
//   --out <file>        where to write the draft (default timetable-draft.json)
//   --model <name>      Gemini model (default gemini-flash-latest, or GEMINI_MODEL)
//   --response <file>   use a saved generateContent response instead of calling Gemini (tests)

import { readFileSync, writeFileSync } from 'node:fs';
import { basename } from 'node:path';
import { parseArgs } from 'node:util';
import { validateDraft } from './draft.js';
import { formatClass } from './format.js';
import { DEFAULT_MODEL, GeminiError, extractDraft, loadKey, parseResponse } from './gemini.js';

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      out: { type: 'string', default: 'timetable-draft.json' },
      model: { type: 'string', default: process.env.GEMINI_MODEL || DEFAULT_MODEL },
      response: { type: 'string' },
    },
  });
  const pdf = positionals[0];
  if (!pdf && !values.response) throw new GeminiError('Usage: npm run extract -- path/to/timetable.pdf');

  let draft;
  if (values.response) {
    draft = parseResponse(JSON.parse(readFileSync(values.response, 'utf8')));
  } else {
    const bytes = readFileSync(pdf);
    console.error(`Reading ${basename(pdf)} with ${values.model} … (up to a few minutes)`);
    draft = await extractDraft(bytes, { key: loadKey(), model: values.model });
  }
  draft = { source: pdf ? basename(pdf) : values.response, ...draft };
  writeFileSync(values.out, JSON.stringify(draft, null, 2) + '\n');

  const { errors, warnings, classes } = validateDraft(draft);
  for (const cls of classes) console.log(`\n${formatClass(cls)}`);
  for (const w of warnings) console.log(`warning: ${w}`);
  for (const e of errors) console.log(`error: ${e}`);
  console.log(`\nDraft written to ${values.out}. Check it against the PDF and fix anything wrong by hand.`);
  if (errors.length) {
    console.log(`Fix the ${errors.length} error(s) above before applying.`);
    process.exitCode = 1;
  } else {
    console.log(`Then: npm run apply -- ${values.out}`);
  }
}

main().catch((error) => {
  console.error(`\n${error instanceof GeminiError ? error.message : error.stack ?? error}`);
  process.exitCode = 1;
});
