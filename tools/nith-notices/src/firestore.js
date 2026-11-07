// Reads and writes collegeFeed/nith with the Firebase Admin SDK (bypasses security rules).
//
// Credentials, in order:
//   FIREBASE_SERVICE_ACCOUNT  contents of the service-account key JSON (GitHub Actions)
//   .secrets/service-account.json, or the Classroom sync's copy in ../classroom-sync/.secrets/
// With FIRESTORE_EMULATOR_HOST set, no key is needed and FIREBASE_PROJECT (default demo-roll)
// picks the emulator project.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';
import { GoogleAuth, JWT } from 'google-auth-library';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
export const SERVICE_ACCOUNT_FILES = [
  join(ROOT, '.secrets', 'service-account.json'),
  join(ROOT, '..', 'classroom-sync', '.secrets', 'service-account.json'),
];
export const FEED_DOC = 'collegeFeed/nith';
const SCOPES = ['https://www.googleapis.com/auth/datastore', 'https://www.googleapis.com/auth/cloud-platform'];

function loadServiceAccount() {
  let text = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!text) {
    const file = SERVICE_ACCOUNT_FILES.find((f) => existsSync(f));
    if (!file) {
      throw new Error(`No Firebase service-account key. Save it as\n  ${SERVICE_ACCOUNT_FILES[0]}\n(the same key as tools/classroom-sync).`);
    }
    text = readFileSync(file, 'utf8');
  }
  let key;
  try {
    key = JSON.parse(text);
  } catch {
    throw new Error('The Firebase service-account key is not valid JSON.');
  }
  if (key.type !== 'service_account' || !key.project_id || !key.private_key) {
    throw new Error('The Firebase key is not a service-account key.');
  }
  return key;
}

/** Explicit auth client: firebase-admin's cert() makes Firestore print a deprecation warning. */
export function openFirestore() {
  if (process.env.FIRESTORE_EMULATOR_HOST) {
    process.env.METADATA_SERVER_DETECTION ??= 'none';
    const projectId = process.env.FIREBASE_PROJECT ?? 'demo-roll';
    return { db: getFirestore(initializeApp({ projectId })), projectId };
  }
  const key = loadServiceAccount();
  const projectId = key.project_id;
  const authClient = new JWT({ email: key.client_email, key: key.private_key, scopes: SCOPES });
  const db = getFirestore(initializeApp({ projectId }));
  db.settings({ auth: new GoogleAuth({ authClient, projectId }) });
  return { db, projectId };
}

export async function readFeed(db) {
  const snapshot = await db.doc(FEED_DOC).get();
  return snapshot.exists ? snapshot.data() : null;
}

export async function writeFeed(db, feed, hash) {
  await db.doc(FEED_DOC).set({
    items: feed.items,
    seen: feed.seen,
    contentHash: hash,
    syncedAt: FieldValue.serverTimestamp(),
  });
}
