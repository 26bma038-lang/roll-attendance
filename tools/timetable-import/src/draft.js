// The timetable draft: what Gemini extracts from the PDF and the admin checks by hand before
// `npm run apply`. Pure: parsing and validation only, all unit-tested.
//
// {
//   "source": "timetable.pdf",
//   "classes": [{
//     "batch": 2026, "branch": "MA", "section": null, "label": "B.Tech. 1st year, MNC",
//     "subjects": [{ "code": "MA-111", "name": "Engineering Mathematics-1", "type": "lecture" }],
//     "slots": [{ "day": "Mon", "start": "09:00", "end": "09:50", "subject": "MA-111",
//                 "type": "lecture", "room": "LH-201", "group": null }]
//   }],
//   "notes": ["anything the model could not place"]
// }

/** Branch codes Roll supports (NithConfig.branches in the app). Others are skipped by apply. */
export const ROLL_BRANCHES = ['MA', 'CS', 'CH', 'CE', 'ME', 'EC'];
export const TYPES = ['lecture', 'lab', 'tutorial'];
const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
export const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/** "Mon", "monday", "MON", 1 → 1 (Monday) … 7 (Sunday); else null. */
export function parseDay(value) {
  if (Number.isInteger(value)) return value >= 1 && value <= 7 ? value : null;
  const key = String(value ?? '').trim().toLowerCase().slice(0, 3);
  const index = DAYS.indexOf(key);
  return index < 0 ? null : index + 1;
}

/**
 * "09:00", "9:00", "14:30", "2:30 PM" → minutes after midnight; else null. Times without am/pm
 * are 24-hour; college timetables that write "2:00" for 2 pm are fixed by the prompt, and by the
 * admin when checking the draft.
 */
export function parseTime(value) {
  const m = /^\s*(\d{1,2})[:.](\d{2})\s*(am|pm)?\s*$/i.exec(String(value ?? ''));
  if (!m) return null;
  let hours = Number(m[1]);
  const minutes = Number(m[2]);
  const meridiem = m[3]?.toLowerCase();
  if (minutes > 59) return null;
  if (meridiem) {
    if (hours < 1 || hours > 12) return null;
    if (meridiem === 'pm' && hours !== 12) hours += 12;
    if (meridiem === 'am' && hours === 12) hours = 0;
  } else if (hours > 23) {
    return null;
  }
  return hours * 60 + minutes;
}

export const formatTime = (minutes) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;

/** "MA 111", "ma-111", "MA111" → "MA-111" (Roll's spelling); anything else trimmed as is. */
export function normalizeCode(code) {
  const text = String(code ?? '').trim();
  const m = /^([A-Za-z]{2,4})\s*-?\s*(\d{3}[A-Za-z]?)$/.exec(text);
  return m ? `${m[1].toUpperCase()}-${m[2].toUpperCase()}` : text;
}

/** nith-26-ma, as NithConfig.classId in the app. */
export function classIdOf(cls) {
  return `nith-${String(cls.batch % 100).padStart(2, '0')}-${String(cls.branch).toLowerCase()}`;
}

export const classLabel = (cls) => `${cls.branch} ${cls.batch}${cls.section ? ` section ${cls.section}` : ''}`;

/**
 * Checks a draft. Errors must be fixed before applying; warnings are things apply will skip or
 * that look odd. Returns `{ errors, warnings, classes }` where `classes` is the normalised draft
 * (codes as MA-111, days 1–7, times in minutes) when there are no errors.
 */
export function validateDraft(draft) {
  const errors = [];
  const warnings = [];
  if (!draft || !Array.isArray(draft.classes)) {
    return { errors: ['The draft needs a "classes" list.'], warnings, classes: [] };
  }
  if (draft.classes.length === 0) errors.push('The draft has no classes.');

  const seenClasses = new Set();
  const classes = draft.classes.map((cls, ci) => {
    const where = `Class ${ci + 1}${cls?.branch ? ` (${cls.branch} ${cls.batch ?? '?'})` : ''}`;
    const batch = Number(cls?.batch);
    const branch = String(cls?.branch ?? '').trim().toUpperCase();
    const section = cls?.section ? String(cls.section).trim() : null;
    if (!Number.isInteger(batch) || batch < 2000 || batch > 2100) errors.push(`${where}: "batch" must be the year of admission, e.g. 2026.`);
    if (!/^[A-Z]{2,4}$/.test(branch)) errors.push(`${where}: "branch" must be a branch code such as MA or EC.`);
    else if (!ROLL_BRANCHES.includes(branch)) warnings.push(`${where}: Roll doesn't have branch ${branch} yet; apply skips it.`);
    if (section) warnings.push(`${where}: Roll has one class per batch and branch, no sections; apply skips section ${section}. Merge it into one class or drop it.`);
    const key = `${batch}|${branch}|${section ?? ''}`;
    if (seenClasses.has(key)) errors.push(`${where}: this batch, branch and section appears twice.`);
    seenClasses.add(key);

    // Subjects.
    const subjects = [];
    const codes = new Set();
    for (const [si, subject] of (cls?.subjects ?? []).entries()) {
      const at = `${where}, subject ${si + 1}`;
      const code = normalizeCode(subject?.code);
      const name = String(subject?.name ?? '').trim().replace(/\s+/g, ' ');
      const type = String(subject?.type ?? 'lecture').toLowerCase();
      if (!code) errors.push(`${at}: missing "code".`);
      else if (code.length > 10) errors.push(`${at}: code "${code}" is longer than 10 characters.`);
      if (!name) errors.push(`${at}: missing "name".`);
      else if (name.length > 80) errors.push(`${at}: name is longer than 80 characters.`);
      if (!TYPES.includes(type)) errors.push(`${at}: "type" must be lecture, lab or tutorial, not "${subject?.type}".`);
      if (code && codes.has(code)) errors.push(`${at}: code ${code} is listed twice.`);
      codes.add(code);
      subjects.push({ code, name, type });
    }
    if (subjects.length === 0) errors.push(`${where}: no subjects.`);

    // Slots.
    const slots = [];
    for (const [si, slot] of (cls?.slots ?? []).entries()) {
      const at = `${where}, slot ${si + 1}`;
      const day = parseDay(slot?.day);
      const start = parseTime(slot?.start);
      const end = parseTime(slot?.end);
      const subject = normalizeCode(slot?.subject);
      const type = String(slot?.type ?? subjects.find((s) => s.code === subject)?.type ?? 'lecture').toLowerCase();
      const group = slot?.group ? String(slot.group).trim() : null;
      if (day === null) errors.push(`${at}: unknown day "${slot?.day}". Use Mon … Sat.`);
      if (start === null) errors.push(`${at}: start "${slot?.start}" isn't a time like 09:00.`);
      if (end === null) errors.push(`${at}: end "${slot?.end}" isn't a time like 09:50.`);
      if (start !== null && end !== null && end <= start) errors.push(`${at}: ends (${slot.end}) before it starts (${slot.start}).`);
      if (!codes.has(subject)) errors.push(`${at}: subject "${slot?.subject}" isn't in this class's subject list.`);
      if (!TYPES.includes(type)) errors.push(`${at}: "type" must be lecture, lab or tutorial, not "${slot?.type}".`);
      const room = String(slot?.room ?? '').trim();
      slots.push({ day, start, end, subject, type, room, group, at });
    }
    if (slots.length === 0) errors.push(`${where}: no slots.`);

    // Overlaps on the same day. Lab groups (G1 in the lab while G2 has a tutorial) may overlap
    // when both slots name a different group.
    const valid = slots.filter((s) => s.day !== null && s.start !== null && s.end !== null && s.end > s.start);
    for (let i = 0; i < valid.length; i++) {
      for (let j = i + 1; j < valid.length; j++) {
        const a = valid[i];
        const b = valid[j];
        if (a.day !== b.day || a.start >= b.end || b.start >= a.end) continue;
        if (a.group && b.group && a.group !== b.group) continue;
        errors.push(`${a.at} and ${b.at.replace(`${where}, `, '')} overlap on ${DAY_NAMES[a.day - 1]} (${formatTime(a.start)}–${formatTime(a.end)} and ${formatTime(b.start)}–${formatTime(b.end)}).`);
      }
    }

    const used = new Set(slots.map((s) => s.subject));
    for (const subject of subjects) {
      if (subject.code && !used.has(subject.code)) warnings.push(`${where}: subject ${subject.code} has no slots.`);
    }
    return { batch, branch, section, label: cls?.label ?? null, subjects, slots: slots.map(({ at, ...rest }) => rest) };
  });

  for (const note of draft.notes ?? []) warnings.push(`Note from the extraction: ${note}`);
  return { errors, warnings, classes: errors.length ? [] : classes };
}
