// `npm run apply -- timetable-draft.json`
//
// Writes the checked draft's subjects and weekly slots into each class that exists in Roll,
// in the same shape the app's Manage screens write. Shows a summary first and asks y/N.
//
//   --replace   also delete timetable slots that aren't in the draft, including ones a CR added
//               by hand (without it, nothing is ever deleted)
//   --yes       don't ask (scripts, emulator checks)
//   --dry-run   show the summary, write nothing

import { readFileSync } from 'node:fs';
import { createInterface } from 'node:readline/promises';
import { parseArgs } from 'node:util';
import { ROLL_BRANCHES, classIdOf, classLabel, validateDraft } from './draft.js';
import { formatPlan } from './format.js';
import { planClass, writeCount } from './plan.js';

async function confirm(question) {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    return /^y(es)?$/i.test((await rl.question(question)).trim());
  } finally {
    rl.close();
  }
}

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      replace: { type: 'boolean', default: false },
      yes: { type: 'boolean', default: false },
      'dry-run': { type: 'boolean', default: false },
    },
  });
  const file = positionals[0];
  if (!file) throw new Error('Usage: npm run apply -- timetable-draft.json [--replace]');

  const { errors, warnings, classes } = validateDraft(JSON.parse(readFileSync(file, 'utf8')));
  for (const w of warnings) console.log(`warning: ${w}`);
  if (errors.length) {
    for (const e of errors) console.log(`error: ${e}`);
    throw new Error(`The draft has ${errors.length} error(s). Fix them in ${file} first.`);
  }

  const { openFirestore, readClass, writePlan } = await import('./firestore.js');
  const { db, projectId } = openFirestore();
  console.log(`Firestore project ${projectId}${process.env.FIRESTORE_EMULATOR_HOST ? ' (emulator)' : ''}\n`);

  const plans = [];
  for (const cls of classes) {
    const classId = classIdOf(cls);
    if (!ROLL_BRANCHES.includes(cls.branch) || cls.section) {
      console.log(`${classLabel(cls)}: skipped (see the warning above)`);
      continue;
    }
    const existing = await readClass(db, classId);
    if (!existing) {
      console.log(`${classId}: skipped, the class doesn't exist yet. It's created when its first student signs in; apply again after that.`);
      continue;
    }
    const plan = planClass(cls, existing, { replace: values.replace });
    plans.push({ classId, plan });
    console.log(formatPlan(classId, plan));
  }

  const total = plans.reduce((sum, p) => sum + writeCount(p.plan), 0);
  if (total === 0) {
    console.log('\nNothing to write.');
    return;
  }
  const deleting = plans.reduce((sum, p) => sum + p.plan.slots.remove.length, 0);
  console.log(`\n${total} write(s)${deleting ? `, including ${deleting} deletion(s)` : ''}.`);
  if (values['dry-run']) return;
  if (!values.yes && !(await confirm('Write these to Firestore? [y/N] '))) {
    console.log('Nothing written.');
    return;
  }
  for (const { classId, plan } of plans) {
    if (writeCount(plan) > 0) await writePlan(db, classId, plan);
  }
  console.log('Done. CRs see the subjects in Manage → Subjects and the slots in Manage → Timetable.');
}

main().catch((error) => {
  console.error(`\n${error.message ?? error}`);
  process.exitCode = 1;
});
