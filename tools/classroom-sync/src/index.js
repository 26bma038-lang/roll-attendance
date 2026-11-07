// Turns raw Classroom API responses for one course into Roll's material index. Pure: the same
// input always gives the same output, in the same order, so re-syncs only change what changed.

import { createHash } from 'node:crypto';
import {
  SECTION_ORDER,
  displayTitle,
  fixTypos,
  guessMimeType,
  mapTopic,
  parseAcademicYear,
  parseExam,
  parseSubject,
} from './parse.js';

/** Stable entry id: courseId + Classroom item id + Drive file id (or link URL). */
export function entryId(courseId, itemId, fileKey) {
  return createHash('sha1').update(`${courseId}|${itemId}|${fileKey}`).digest('hex').slice(0, 20);
}

/** One Classroom attachment → `{ key, title, url, driveFileId?, mimeType? }`, or null. */
function attachment(material) {
  if (material.driveFile) {
    const file = material.driveFile.driveFile ?? {};
    if (!file.id) return null;
    return {
      key: file.id,
      fileName: file.title ?? '',
      url: file.alternateLink ?? `https://drive.google.com/file/d/${file.id}/view`,
      driveFileId: file.id,
      mimeType: guessMimeType(file.title),
    };
  }
  if (material.link?.url) {
    return { key: material.link.url, fileName: material.link.title ?? '', url: material.link.url };
  }
  if (material.youtubeVideo?.alternateLink) {
    const video = material.youtubeVideo;
    return { key: video.alternateLink, fileName: video.title ?? '', url: video.alternateLink };
  }
  if (material.form?.formUrl) {
    return { key: material.form.formUrl, fileName: material.form.title ?? '', url: material.form.formUrl };
  }
  return null;
}

/**
 * @param source  one entry of sources.json
 * @param raw     `{ topics, materials }` as returned by the Classroom API
 * @returns `{ items, warnings }`
 */
export function buildIndex(source, raw) {
  const warnings = [];
  const topicNames = new Map((raw.topics ?? []).map((t) => [t.topicId, t.name]));
  const unknownTopics = new Set();

  const posts = raw.materials ?? [];

  const items = [];
  for (const post of posts) {
    const topicName = post.topicId ? topicNames.get(post.topicId) : undefined;
    const topic = mapTopic(topicName);
    if (!topic.known && !unknownTopics.has(topic.sectionLabel)) {
      unknownTopics.add(topic.sectionLabel);
      warnings.push(topicName === undefined
        ? 'Some posts have no topic: their files go to section "other".'
        : `Unknown topic "${topic.sectionLabel}": its files go to section "other".`);
    }

    const itemTitle = fixTypos(post.title).trim();
    const subject = topic.subjectName
      ? { subjectCode: null, subjectName: topic.subjectName }
      : parseSubject(itemTitle);
    const isPaper = topic.section === 'pyq' || /paper/i.test(itemTitle);

    const seen = new Set();
    for (const material of post.materials ?? []) {
      const file = attachment(material);
      if (!file) {
        warnings.push(`"${itemTitle}": skipped an attachment Roll can't link to (${Object.keys(material).join(', ')}).`);
        continue;
      }
      if (seen.has(file.key)) continue;
      seen.add(file.key);

      const title = displayTitle(file.fileName) || itemTitle || 'Untitled';
      items.push(stripNulls({
        id: entryId(source.courseId, post.id, file.key),
        section: topic.section,
        sectionLabel: topic.sectionLabel,
        subjectCode: subject.subjectCode,
        subjectName: subject.subjectName,
        itemTitle,
        title,
        exam: isPaper ? parseExam(file.fileName) : null,
        academicYear: parseAcademicYear(file.fileName),
        url: file.url,
        driveFileId: file.driveFileId ?? null,
        mimeType: file.mimeType ?? null,
        updatedAt: post.updateTime ?? post.creationTime ?? null,
      }));
    }
    if ((post.materials ?? []).length === 0) {
      warnings.push(`"${itemTitle}" (${topic.sectionLabel}) has no attachments.`);
    }
  }

  fillSubjectNames(items);
  items.sort(compareItems);
  return { items, warnings };
}

/** "CS-102" alone gets the name another item gave the same code, e.g. "CS-102(Programming)". */
function fillSubjectNames(items) {
  const names = new Map();
  for (const item of items) {
    if (item.subjectCode && item.subjectName !== item.subjectCode && !names.has(item.subjectCode)) {
      names.set(item.subjectCode, item.subjectName);
    }
  }
  for (const item of items) {
    if (item.subjectCode && item.subjectName === item.subjectCode && names.has(item.subjectCode)) {
      item.subjectName = names.get(item.subjectCode);
    }
  }
}

const sectionRank = (s) => {
  const i = SECTION_ORDER.indexOf(s);
  return i < 0 ? SECTION_ORDER.length : i;
};
const examRank = (e) => (e === 'END' ? 0 : e === 'MID' ? 1 : 2);

/** Section order, then subject, then newest academic year, End before Mid, then title. */
export function compareItems(a, b) {
  return (
    sectionRank(a.section) - sectionRank(b.section) ||
    (a.sectionLabel ?? '').localeCompare(b.sectionLabel ?? '') ||
    (a.subjectCode ?? '￿').localeCompare(b.subjectCode ?? '￿') ||
    (a.subjectName ?? '').localeCompare(b.subjectName ?? '') ||
    (b.academicYear ?? '').localeCompare(a.academicYear ?? '') ||
    examRank(a.exam) - examRank(b.exam) ||
    a.title.localeCompare(b.title, 'en', { numeric: true }) ||
    a.id.localeCompare(b.id)
  );
}

function stripNulls(object) {
  return Object.fromEntries(Object.entries(object).filter(([, v]) => v !== null && v !== undefined));
}

/** Per-section, per-subject counts for the dry-run summary. */
export function summarize(items) {
  const sections = new Map();
  for (const item of items) {
    const key = item.section === 'other' ? `other: ${item.sectionLabel}` : item.section;
    if (!sections.has(key)) sections.set(key, { label: item.sectionLabel, count: 0, subjects: new Map() });
    const section = sections.get(key);
    section.count += 1;
    const subject = item.subjectCode && item.subjectName !== item.subjectCode
      ? `${item.subjectCode}  ${item.subjectName}`
      : item.subjectCode ?? item.subjectName;
    section.subjects.set(subject, (section.subjects.get(subject) ?? 0) + 1);
  }
  return sections;
}
