// Readable tables of a checked draft, one per class, for the admin to compare with the PDF.

import { DAY_NAMES, classIdOf, classLabel, formatTime } from './draft.js';

export function formatClass(cls) {
  const lines = [];
  lines.push(`${classLabel(cls)}  (${classIdOf(cls)})${cls.label ? `  "${cls.label}"` : ''}`);
  lines.push('  Subjects');
  for (const s of cls.subjects) lines.push(`    ${s.code.padEnd(9)} ${s.type.padEnd(9)} ${s.name}`);
  lines.push('  Week');
  for (let day = 1; day <= 7; day++) {
    const slots = cls.slots.filter((s) => s.day === day).sort((a, b) => a.start - b.start);
    if (slots.length === 0) continue;
    lines.push(`    ${DAY_NAMES[day - 1]}`);
    for (const s of slots) {
      const extra = [s.type !== 'lecture' ? s.type : null, s.room || null, s.group].filter(Boolean).join(' · ');
      lines.push(`      ${formatTime(s.start)}–${formatTime(s.end)}  ${s.subject.padEnd(9)} ${extra}`);
    }
  }
  return lines.join('\n');
}

export function formatPlan(classId, plan) {
  const s = plan.slots;
  return [
    `${classId}`,
    `  subjects: ${plan.subjects.create.length} new, ${plan.subjects.reuse.length} already there`,
    `  slots:    ${s.create.length} new, ${s.update.length} changed, ${s.unchanged.length} unchanged` +
      (s.alreadyThere.length ? `, ${s.alreadyThere.length} already entered by the CR` : '') +
      (s.remove.length ? `, ${s.remove.length} DELETED (--replace)` : '') +
      (s.keep.length ? `, ${s.keep.length} other slots kept` : ''),
  ].join('\n');
}
