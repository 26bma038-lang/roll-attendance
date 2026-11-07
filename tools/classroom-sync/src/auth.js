// Google OAuth for an installed (desktop) app, with no dependencies: the browser login with a
// loopback redirect and PKCE, and refresh-token → access-token exchange.
//
// Secrets live in .secrets/ (git-ignored) or, on GitHub Actions, in environment variables:
//   GOOGLE_OAUTH_CLIENT   contents of the downloaded OAuth client JSON
//   GOOGLE_REFRESH_TOKEN  the refresh token `npm run login` saved

import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
export const SECRETS_DIR = join(ROOT, '.secrets');
export const CLIENT_FILE = join(SECRETS_DIR, 'oauth-client.json');
export const TOKEN_FILE = join(SECRETS_DIR, 'token.json');

export const SCOPES = [
  'https://www.googleapis.com/auth/classroom.courses.readonly',
  'https://www.googleapis.com/auth/classroom.topics.readonly',
  'https://www.googleapis.com/auth/classroom.courseworkmaterials.readonly',
];

const AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';

/** A failure with a message written for the person running the script. */
export class SyncError extends Error {}

const BLOCKED_MESSAGE = `NITH's Google Workspace is blocking this app from reading Classroom (admin_policy_enforced).

That is a college admin setting, not a bug in the script. Options:
  - Ask the NITH Workspace admin to allow (trust) your OAuth client ID for Classroom.
  - Sign in with a personal Gmail account that has joined the same class, if the class allows it.`;

/** The OAuth client: { client_id, client_secret }. */
export function loadClient() {
  let text = process.env.GOOGLE_OAUTH_CLIENT;
  if (!text) {
    let file = CLIENT_FILE;
    if (!existsSync(file) && existsSync(SECRETS_DIR)) {
      // Accept the file under the name Google gives it: client_secret_<id>.json
      const downloaded = readdirSync(SECRETS_DIR).find((f) => /^client_secret.*\.json$/.test(f));
      if (downloaded) file = join(SECRETS_DIR, downloaded);
    }
    if (!existsSync(file)) {
      throw new SyncError(
        `No OAuth client found. Download the "Desktop app" client JSON from Google Cloud and save it as\n  ${CLIENT_FILE}\nSee README.md, step 3.`,
      );
    }
    text = readFileSync(file, 'utf8');
  }
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new SyncError('The OAuth client JSON is not valid JSON.');
  }
  const client = json.installed ?? json.web ?? json;
  if (!client.client_id || !client.client_secret) {
    throw new SyncError('The OAuth client JSON has no client_id/client_secret. Use the "Desktop app" type.');
  }
  if (json.web) {
    throw new SyncError('This is a "Web application" OAuth client. Create a "Desktop app" client instead (README step 3).');
  }
  return { client_id: client.client_id, client_secret: client.client_secret };
}

export function loadRefreshToken() {
  if (process.env.GOOGLE_REFRESH_TOKEN) return process.env.GOOGLE_REFRESH_TOKEN.trim();
  if (!existsSync(TOKEN_FILE)) {
    throw new SyncError('Not signed in yet. Run `npm run login` first.');
  }
  const token = JSON.parse(readFileSync(TOKEN_FILE, 'utf8'));
  if (!token.refresh_token) throw new SyncError('The saved token has no refresh token. Run `npm run login` again.');
  return token.refresh_token;
}

export function saveToken(token) {
  mkdirSync(SECRETS_DIR, { recursive: true });
  writeFileSync(TOKEN_FILE, JSON.stringify(token, null, 2) + '\n', { mode: 0o600 });
}

/**
 * Turns a Google OAuth error code into a SyncError with advice. Exported for tests.
 */
export function oauthError(code, description = '') {
  const text = `${code} ${description}`;
  if (/admin_policy_enforced|org_internal|access_blocked/i.test(text)) return new SyncError(BLOCKED_MESSAGE);
  if (code === 'invalid_grant') {
    return new SyncError(
      `Google rejected the refresh token (invalid_grant${description ? `: ${description}` : ''}).
It was revoked, the password changed, or the OAuth consent screen is still in "Testing" mode
(tokens expire after 7 days there). Publish the app (README step 2) and run \`npm run login\` again.`,
    );
  }
  if (code === 'access_denied') return new SyncError('Sign-in was cancelled or the permissions were not granted.');
  if (code === 'invalid_client' || code === 'unauthorized_client') {
    return new SyncError(`Google does not accept this OAuth client (${code}). Download the Desktop app client JSON again.`);
  }
  return new SyncError(`Google sign-in failed: ${code}${description ? ` (${description})` : ''}`);
}

async function postToken(params) {
  const response = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.error) throw oauthError(body.error ?? `HTTP ${response.status}`, body.error_description);
  return body;
}

/** Refresh token → short-lived access token. */
export async function accessToken() {
  const client = loadClient();
  const body = await postToken({
    client_id: client.client_id,
    client_secret: client.client_secret,
    refresh_token: loadRefreshToken(),
    grant_type: 'refresh_token',
  });
  return body.access_token;
}

/** PKCE pair for the browser login. */
export function pkce() {
  const verifier = randomBytes(32).toString('base64url');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  return { verifier, challenge };
}

export function authUrl({ clientId, redirectUri, state, challenge }) {
  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: SCOPES.join(' '),
    access_type: 'offline',
    prompt: 'consent', // always return a refresh token, even on a second login
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });
  return `${AUTH_URL}?${params}`;
}

export async function exchangeCode({ client, code, redirectUri, verifier }) {
  return postToken({
    client_id: client.client_id,
    client_secret: client.client_secret,
    code,
    code_verifier: verifier,
    redirect_uri: redirectUri,
    grant_type: 'authorization_code',
  });
}
