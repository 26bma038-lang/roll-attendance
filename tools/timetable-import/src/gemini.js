// Sends the timetable PDF to the Gemini API (free tier, Google AI Studio key) and gets the draft
// back as strict JSON. No SDK: one HTTPS request.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ROLL_BRANCHES } from './draft.js';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
export const KEY_FILE = join(ROOT, '.secrets', 'gemini-key');
/** The newest Flash model on the free tier. Override with GEMINI_MODEL or --model. */
export const DEFAULT_MODEL = 'gemini-flash-latest';
/** Inline PDFs must stay under 20 MB per request. */
export const MAX_PDF_BYTES = 19 * 1024 * 1024;

export class GeminiError extends Error {}

export function loadKey() {
  const fromEnv = process.env.GEMINI_API_KEY?.trim();
  if (fromEnv) return fromEnv;
  if (existsSync(KEY_FILE)) {
    const key = readFileSync(KEY_FILE, 'utf8').trim();
    if (key) return key;
  }
  throw new GeminiError(
    `No Gemini API key. Get a free one at https://aistudio.google.com/apikey and save it as\n  ${KEY_FILE}\n(or set GEMINI_API_KEY). See README.md.`,
  );
}

/** What the model must return; Gemini enforces it with responseSchema. */
export const RESPONSE_SCHEMA = {
  type: 'OBJECT',
  properties: {
    classes: {
      type: 'ARRAY',
      items: {
        type: 'OBJECT',
        properties: {
          batch: { type: 'INTEGER', description: 'Year of admission, e.g. 2026 for first-years in 2026-27.' },
          branch: { type: 'STRING', description: 'Roll branch code.' },
          section: { type: 'STRING', nullable: true },
          label: { type: 'STRING', description: 'The class as the PDF names it.' },
          subjects: {
            type: 'ARRAY',
            items: {
              type: 'OBJECT',
              properties: {
                code: { type: 'STRING' },
                name: { type: 'STRING' },
                type: { type: 'STRING', enum: ['lecture', 'lab', 'tutorial'] },
              },
              required: ['code', 'name', 'type'],
            },
          },
          slots: {
            type: 'ARRAY',
            items: {
              type: 'OBJECT',
              properties: {
                day: { type: 'STRING', enum: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] },
                start: { type: 'STRING', description: '24-hour HH:MM' },
                end: { type: 'STRING', description: '24-hour HH:MM' },
                subject: { type: 'STRING', description: 'A code from this class\'s subjects.' },
                type: { type: 'STRING', enum: ['lecture', 'lab', 'tutorial'] },
                room: { type: 'STRING' },
                group: { type: 'STRING', nullable: true, description: 'Lab group such as G1, when the class splits.' },
              },
              required: ['day', 'start', 'end', 'subject', 'type', 'room'],
            },
          },
        },
        required: ['batch', 'branch', 'subjects', 'slots'],
      },
    },
    notes: { type: 'ARRAY', items: { type: 'STRING' } },
  },
  required: ['classes'],
};

export function buildPrompt(today = new Date()) {
  return `You are reading a weekly class timetable PDF from NIT Hamirpur (NITH), India.
Extract EVERY class (batch + branch, or section) the PDF covers into the JSON schema. Rules:

- batch: the year of admission. Today is ${today.toISOString().slice(0, 10)}; the academic year runs
  July-June. A 1st-year class in 2026-27 has batch 2026, 2nd-year 2025, and so on.
- branch: use these codes when the PDF means them: MA = Mathematics and Computing (also written
  MNC or MnC), CS = Computer Science and Engineering (CSE), CH = Chemical Engineering,
  CE = Civil Engineering, ME = Mechanical Engineering, EC = Electronics and Communication
  Engineering (ECE). Any other branch: its usual short code (EE, PH, ECD, AR, MS …).
  Supported by the app today: ${ROLL_BRANCHES.join(', ')}.
- section: only if the PDF splits one batch+branch into sections; otherwise null.
- subjects: every course of that class: code as written (e.g. MA-111, CS-101), full name, and type
  (lecture, lab, or tutorial). A lab with its own code (CS-102) is its own subject.
- slots: one per period in the weekly grid. 24-hour times ("14:00", never "2:00" for 2 pm).
  A lab spanning several periods is ONE slot from its start to its end. subject must be one of
  the class's subject codes. room as printed ("" if none). If the class splits into lab groups
  at the same time, give each its own slot with group "G1", "G2" ….
- Lunch, breaks, library hours and blank periods are not slots.
- Never invent anything. If something is unreadable or doesn't fit, leave it out and say so in notes.`;
}

export function buildRequest(pdfBytes, today = new Date()) {
  return {
    contents: [
      {
        role: 'user',
        parts: [
          { inlineData: { mimeType: 'application/pdf', data: Buffer.from(pdfBytes).toString('base64') } },
          { text: buildPrompt(today) },
        ],
      },
    ],
    generationConfig: {
      temperature: 0,
      responseMimeType: 'application/json',
      responseSchema: RESPONSE_SCHEMA,
    },
  };
}

/** The draft JSON inside a generateContent response; throws GeminiError with advice otherwise. */
export function parseResponse(body) {
  if (body?.promptFeedback?.blockReason) {
    throw new GeminiError(`Gemini refused the PDF (${body.promptFeedback.blockReason}).`);
  }
  const candidate = body?.candidates?.[0];
  const text = (candidate?.content?.parts ?? []).map((p) => p.text ?? '').join('').trim();
  if (!text) throw new GeminiError(`Gemini returned no text (finish reason: ${candidate?.finishReason ?? 'unknown'}).`);
  const json = text.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
  try {
    return JSON.parse(json);
  } catch {
    const hint = candidate?.finishReason === 'MAX_TOKENS' ? ' The answer was cut off; try a PDF with fewer pages.' : '';
    throw new GeminiError(`Gemini's answer isn't valid JSON.${hint}`);
  }
}

/** HTTP error → advice, for the free tier's usual problems. */
export function httpError(status, body) {
  const message = body?.error?.message ?? `HTTP ${status}`;
  if (status === 400 && /api key/i.test(message)) return new GeminiError('Google rejected the Gemini API key. Create a new one at https://aistudio.google.com/apikey.');
  if (status === 403) return new GeminiError(`Gemini says no access (${message}). Check the key belongs to a project with the Gemini API enabled.`);
  if (status === 404) return new GeminiError(`Model not found (${message}). Try --model gemini-flash-latest.`);
  if (status === 429) return new GeminiError('Gemini free-tier limit reached. Wait a minute (or until tomorrow for the daily limit) and run it again.');
  if (status >= 500) return new GeminiError(`Gemini had a problem on its side (${status}). Try again in a few minutes.`);
  return new GeminiError(`Gemini error ${status}: ${message}`);
}

export async function extractDraft(pdfBytes, { key, model = DEFAULT_MODEL, fetchImpl = fetch, today = new Date() } = {}) {
  if (pdfBytes.length > MAX_PDF_BYTES) throw new GeminiError('The PDF is over 19 MB. Split it or compress it first.');
  if (Buffer.from(pdfBytes).subarray(0, 5).toString('latin1') !== '%PDF-') throw new GeminiError("That file isn't a PDF.");
  const response = await fetchImpl(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify(buildRequest(pdfBytes, today)),
    signal: AbortSignal.timeout(180_000),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw httpError(response.status, body);
  return parseResponse(body);
}
