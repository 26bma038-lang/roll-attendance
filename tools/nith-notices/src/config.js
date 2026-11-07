// config.json: which pages to read, the academic filter, how many notices to keep.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const CONFIG_FILE = join(dirname(fileURLToPath(import.meta.url)), '..', 'config.json');
export const CATEGORY_NAMES = ['Academic', 'Exams', 'Students'];

export function validateConfig(config) {
  const problems = [];
  if (!Array.isArray(config.pages) || config.pages.length === 0) problems.push('"pages" must be a non-empty list');
  for (const page of config.pages ?? []) {
    if (!/^https:\/\//.test(page.url ?? '')) problems.push(`page url must start with https:// (${page.url})`);
    if (!CATEGORY_NAMES.includes(page.category)) problems.push(`unknown category "${page.category}"`);
    if (typeof page.filter !== 'boolean') problems.push(`"filter" must be true or false (${page.url})`);
  }
  for (const key of ['academicKeywords', 'examKeywords']) {
    if (!Array.isArray(config[key]) || !config[key].every((k) => typeof k === 'string' && k.trim() === k && k.length > 1)) {
      problems.push(`"${key}" must be a list of trimmed words`);
    }
  }
  if (!Number.isInteger(config.keep) || config.keep < 1 || config.keep > 1000) problems.push('"keep" must be 1–1000');
  if (!Number.isInteger(config.timeoutMs) || config.timeoutMs < 1000) problems.push('"timeoutMs" must be at least 1000');
  if (!/RollApp\//.test(config.userAgent ?? '')) problems.push('"userAgent" must name RollApp');
  if (problems.length) throw new Error(`config.json: ${problems.join('; ')}`);
  return config;
}

export function loadConfig(file = CONFIG_FILE) {
  return validateConfig(JSON.parse(readFileSync(file, 'utf8')));
}
