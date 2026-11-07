import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { validateDraft } from '../src/draft.js';
import { DEFAULT_MODEL, GeminiError, buildPrompt, buildRequest, extractDraft, httpError, parseResponse } from '../src/gemini.js';

const fixture = (name) => readFileSync(new URL(`./fixtures/${name}`, import.meta.url));
const pdf = fixture('sample-timetable.pdf');
const savedResponse = JSON.parse(fixture('sample-gemini-response.json').toString('utf8'));

test('the request sends the PDF inline and asks for strict JSON', () => {
  const body = buildRequest(pdf, new Date('2026-11-07T00:00:00Z'));
  const [file, prompt] = body.contents[0].parts;
  assert.equal(file.inlineData.mimeType, 'application/pdf');
  assert.equal(Buffer.from(file.inlineData.data, 'base64').subarray(0, 5).toString(), '%PDF-');
  assert.match(prompt.text, /Today is 2026-11-07/);
  assert.match(prompt.text, /MA = Mathematics and Computing \(also written\s+MNC/);
  assert.equal(body.generationConfig.responseMimeType, 'application/json');
  assert.equal(body.generationConfig.temperature, 0);
  assert.deepEqual(body.generationConfig.responseSchema.required, ['classes']);
});

test('the prompt asks for every class, 24-hour times, and no inventions', () => {
  const prompt = buildPrompt();
  assert.match(prompt, /EVERY class/);
  assert.match(prompt, /24-hour times/);
  assert.match(prompt, /Never invent anything/);
});

test('a saved response becomes a valid draft', () => {
  const draft = parseResponse(savedResponse);
  assert.equal(draft.classes.length, 3);
  assert.deepEqual(validateDraft(draft).errors, []);
});

test('code fences around the JSON are tolerated', () => {
  const fenced = { candidates: [{ content: { parts: [{ text: '```json\n{"classes": []}\n```' }] } }] };
  assert.deepEqual(parseResponse(fenced), { classes: [] });
});

test('blocked, empty and cut-off answers explain themselves', () => {
  assert.throws(() => parseResponse({ promptFeedback: { blockReason: 'SAFETY' } }), /refused the PDF \(SAFETY\)/);
  assert.throws(() => parseResponse({ candidates: [{ finishReason: 'RECITATION', content: { parts: [] } }] }), /no text \(finish reason: RECITATION\)/);
  assert.throws(() => parseResponse({ candidates: [{ finishReason: 'MAX_TOKENS', content: { parts: [{ text: '{"classes": [' }] } }] }), /cut off/);
});

test('free-tier HTTP errors become advice', () => {
  assert.match(httpError(429, {}).message, /free-tier limit/);
  assert.match(httpError(400, { error: { message: 'API key not valid. Please pass a valid API key.' } }).message, /rejected the Gemini API key/);
  assert.match(httpError(404, { error: { message: 'models/x is not found' } }).message, /--model gemini-flash-latest/);
  assert.match(httpError(503, {}).message, /on its side/);
});

test('extractDraft: one POST to the model with the key in a header, not the URL', async () => {
  const calls = [];
  const fakeFetch = async (url, init) => {
    calls.push({ url, init });
    return { ok: true, status: 200, json: async () => savedResponse };
  };
  const draft = await extractDraft(pdf, { key: 'test-key', fetchImpl: fakeFetch });
  assert.equal(draft.classes.length, 3);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, `https://generativelanguage.googleapis.com/v1beta/models/${DEFAULT_MODEL}:generateContent`);
  assert.ok(!calls[0].url.includes('test-key'));
  assert.equal(calls[0].init.headers['x-goog-api-key'], 'test-key');
});

test('extractDraft refuses files that are not PDFs before calling anything', async () => {
  await assert.rejects(extractDraft(Buffer.from('hello'), { key: 'k', fetchImpl: () => assert.fail('no call') }), GeminiError);
});
