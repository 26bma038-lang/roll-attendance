// Pure parsing rules: Classroom topic → Roll section, item title → subject, file name → exam,
// academic year and display title. No I/O here, so every rule is unit-tested.

/** Roll sections in display order, with the label the app shows. */
export const SECTIONS = {
  pyq: 'Previous year papers',
  notes: 'Notes',
  books: 'Books',
  assignments: 'Assignments',
  solutions: 'Solutions',
  practicals: 'Practical files',
  workshop: 'Workshop',
  drawing: 'Engineering drawing',
  syllabus: 'Syllabus',
  calendar: 'Academic calendar',
  other: 'Other',
};

export const SECTION_ORDER = Object.keys(SECTIONS);

/** Label for chapter-wise maths notes that carry no subject code. */
export const SUNIL_SIR_LABEL = "Sunil Sir's notes";

/** Normalised Classroom topic name → Roll section (and, for one topic, a fixed subject label). */
const TOPICS = new Map([
  ['syllabus', { section: 'syllabus' }],
  ['academic calendar', { section: 'calendar' }],
  ['notes', { section: 'notes' }],
  ["sunil sir's notes", { section: 'notes', subjectName: SUNIL_SIR_LABEL }],
  ['books', { section: 'books' }],
  ['practical files', { section: 'practicals' }],
  ['workshop', { section: 'workshop' }],
  ['engineering drawing', { section: 'drawing' }],
  ['assignments', { section: 'assignments' }],
  ['assignment solutions', { section: 'solutions' }],
  ['question papers', { section: 'pyq' }],
]);

function normalizeTopic(name) {
  return String(name ?? '')
    .toLowerCase()
    .replace(/[‘’`]/g, "'")
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Classroom topic name → `{ section, sectionLabel, known, subjectName? }`. Case-insensitive.
 * An unknown or missing topic goes to `other`, keeping the raw name as its label.
 */
export function mapTopic(topicName) {
  const hit = TOPICS.get(normalizeTopic(topicName));
  if (hit) return { ...hit, sectionLabel: SECTIONS[hit.section], known: true };
  const raw = String(topicName ?? '').trim();
  return { section: 'other', sectionLabel: raw || 'No topic', known: false };
}

const SUBJECT = /^([A-Z]{2,3})\s*-?\s*(\d{3})\s*\((.+)\)/;
// Same code without a name in brackets, e.g. a practical-file item titled just "CS-102".
const BARE_CODE = /^([A-Z]{2,3})\s*-?\s*(\d{3})(?!\d)/;

/**
 * Item title → `{ subjectCode, subjectName }`.
 * "MA-111(Eng. Mathematics-1)" → MA-111 / "Eng. Mathematics-1".
 * "CS-102" → CS-102 / "CS-102" (the name can be filled in from another item, see buildIndex).
 * Anything else → null / the title.
 */
export function parseSubject(itemTitle) {
  const title = String(itemTitle ?? '').trim().replace(/\s+/g, ' ');
  const full = SUBJECT.exec(title);
  if (full) return { subjectCode: `${full[1]}-${full[2]}`, subjectName: full[3].trim() };
  const bare = BARE_CODE.exec(title);
  if (bare) {
    const code = `${bare[1]}-${bare[2]}`;
    return { subjectCode: code, subjectName: code };
  }
  return { subjectCode: null, subjectName: title };
}

/**
 * File name → "MID", "END" or null. "mid"/"end" must start a word, so "MIDSEM" and "End-sem"
 * count but "Calendar" and "Pyramid" don't. The first one in the name wins.
 */
export function parseExam(fileName) {
  const match = /(?:^|[^a-z])(mid|end)/i.exec(String(fileName ?? ''));
  return match ? match[1].toUpperCase() : null;
}

/**
 * File name → "2024-25" (from "2024-25", "2024-2025", "2024_25"), else a lone year → "2023",
 * else null.
 */
export function parseAcademicYear(fileName) {
  const name = String(fileName ?? '');
  const range = /(?<!\d)20(\d{2})\s*[-–_/]\s*(?:20)?(\d{2})(?!\d)/.exec(name);
  if (range) return `20${range[1]}-${range[2]}`;
  const single = /(?<!\d)(20\d{2})(?!\d)/.exec(name);
  return single ? single[1] : null;
}

const EXTENSION = /\.(pdf|docx?|pptx?|xlsx?|jpe?g|png|txt|zip)$/i;

/**
 * Spelling slips in the course, fixed for display only (the Classroom stays as it is).
 * Whole words, any case, plural too; the fix keeps the original's case.
 */
export const DISPLAY_FIXES = [
  ['assignement', 'assignment'],
];

function matchCase(original, replacement) {
  if (original === original.toUpperCase()) return replacement.toUpperCase();
  if (original[0] === original[0].toUpperCase()) return replacement[0].toUpperCase() + replacement.slice(1);
  return replacement;
}

/** Applies [DISPLAY_FIXES] to a post or file title: "ASSIGNEMENT SOLUTIONS" → "ASSIGNMENT SOLUTIONS". */
export function fixTypos(text) {
  let out = String(text ?? '');
  for (const [wrong, right] of DISPLAY_FIXES) {
    // Not inside a longer word: "Reassignement" is left alone, "Assignements" is fixed.
    const word = new RegExp(`(?<![a-z])${wrong}(?=s?(?![a-z]))`, 'gi');
    out = out.replace(word, (match) => matchCase(match, right));
  }
  return out;
}

/**
 * File name → title shown in the app: no extension, underscores as spaces, a space before
 * "(" so "MID SEM 2023(NEW)" reads "MID SEM 2023 (NEW)", single spaces, typos fixed.
 */
export function displayTitle(fileName) {
  return fixTypos(fileName)
    .trim()
    .replace(EXTENSION, '')
    .replace(/_/g, ' ')
    .replace(/([^\s(])\(/g, '$1 (')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Best guess at the MIME type from the file name; Classroom doesn't report it. */
export function guessMimeType(fileName) {
  const ext = /\.([a-z0-9]+)$/i.exec(String(fileName ?? ''))?.[1]?.toLowerCase();
  return {
    pdf: 'application/pdf',
    doc: 'application/msword',
    docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    ppt: 'application/vnd.ms-powerpoint',
    pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    xls: 'application/vnd.ms-excel',
    xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    png: 'image/png',
    txt: 'text/plain',
    zip: 'application/zip',
  }[ext] ?? null;
}
