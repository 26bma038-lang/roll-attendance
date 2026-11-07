// Writes the material index to Firestore with the Firebase Admin SDK (bypasses security rules).
//
// Credentials, in order:
//   FIREBASE_SERVICE_ACCOUNT  contents of the service-account key JSON (GitHub Actions)
//   .secrets/service-account.json
// With FIRESTORE_EMULATOR_HOST set, no key is needed and FIREBASE_PROJECT (default demo-roll)
// picks the emulator project.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';
import { GoogleAuth, JWT } from 'google-auth-library';
import { SECRETS_DIR, SyncError } from './auth.js';
import { planWrite, staleSourceIds } from './publish.js';

export const SERVICE_ACCOUNT_FILE = join(SECRETS_DIR, 'service-account.json');
const COLLECTION = 'materialSources';
const SCOPES = ['https://www.googleapis.com/auth/datastore', 'https://www.googleapis.com/auth/cloud-platform'];

function loadServiceAccount() {
  let text = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!text) {
    if (!existsSync(SERVICE_ACCOUNT_FILE)) {
      throw new SyncError(
        `No Firebase service-account key found. Download one (README, "Firebase key") and save it as\n  ${SERVICE_ACCOUNT_FILE}`,
      );
    }
    text = readFileSync(SERVICE_ACCOUNT_FILE, 'utf8');
  }
  let key;
  try {
    key = JSON.parse(text);
  } catch {
    throw new SyncError('The Firebase service-account key is not valid JSON.');
  }
  if (key.type !== 'service_account' || !key.project_id || !key.private_key) {
    throw new SyncError('The Firebase key is not a service-account key. Generate one under Project settings → Service accounts.');
  }
  return key;
}

/**
 * The service account signs in through an explicit google-auth-library client. Passing the key
 * to firebase-admin with `cert()` makes it hand Firestore the deprecated `credentials` option,
 * which prints a warning on every run.
 */
export function openFirestore() {
  if (process.env.FIRESTORE_EMULATOR_HOST) {
    const projectId = process.env.FIREBASE_PROJECT ?? 'demo-roll';
    // No real credential on the emulator: stop google-auth-library probing for a Google Cloud
    // metadata server, which only times out and prints a MetadataLookupWarning.
    process.env.METADATA_SERVER_DETECTION ??= 'none';
    return { db: getFirestore(initializeApp({ projectId })), projectId };
  }
  const key = loadServiceAccount();
  const projectId = key.project_id;
  const authClient = new JWT({ email: key.client_email, key: key.private_key, scopes: SCOPES });
  const db = getFirestore(initializeApp({ projectId }));
  db.settings({ auth: new GoogleAuth({ authClient, projectId }) });
  return { db, projectId };
}

/**
 * Writes one source if its index changed. One read when nothing changed; otherwise one atomic
 * batch: the parts (if any), the source document, and deletes for parts no longer needed.
 * @returns `{ changed, itemCount, partCount }`
 */
export async function publishSource(db, source, items, { force = false } = {}) {
  const ref = db.collection(COLLECTION).doc(source.id);
  const snapshot = await ref.get();
  const existing = snapshot.exists ? snapshot.data() : null;

  if (!force && items.length === 0 && (existing?.itemCount ?? 0) > 0) {
    throw new SyncError(
      `"${source.id}": Classroom returned no files, but Roll has ${existing.itemCount}. Not wiping the index.\n` +
      'If the course really is empty now, run again with --force.',
    );
  }

  const plan = planWrite(source, items, existing, { force });
  if (!plan.changed) return { changed: false, itemCount: items.length, partCount: existing.partCount ?? 0 };

  const batch = db.batch();
  plan.parts.forEach((partItems, n) => batch.set(ref.collection('parts').doc(String(n)), { n, items: partItems }));
  batch.set(ref, { ...plan.doc, syncedAt: FieldValue.serverTimestamp() });
  for (const n of plan.deleteParts) batch.delete(ref.collection('parts').doc(String(n)));
  await batch.commit();
  return { changed: true, itemCount: items.length, partCount: plan.parts.length };
}

/**
 * Deletes source documents (and their parts) that sources.json no longer lists, e.g. the old
 * `fy-ec-ma` after the rename to `first-year`. Run only after a full sync of every source.
 * @returns the deleted ids
 */
export async function removeStaleSources(db, sources) {
  const existing = await db.collection(COLLECTION).listDocuments();
  const stale = staleSourceIds(existing.map((ref) => ref.id), sources);
  for (const id of stale) {
    const ref = db.collection(COLLECTION).doc(id);
    const parts = await ref.collection('parts').listDocuments();
    const batch = db.batch();
    for (const part of parts) batch.delete(part);
    batch.delete(ref);
    await batch.commit();
  }
  return stale;
}
