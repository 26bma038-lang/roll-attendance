// Firestore access for `npm run apply` (Firebase Admin SDK; bypasses security rules).
//
// Credentials, in order:
//   FIREBASE_SERVICE_ACCOUNT  contents of the service-account key JSON
//   .secrets/service-account.json, or the Classroom sync's copy in ../classroom-sync/.secrets/
// With FIRESTORE_EMULATOR_HOST set, no key is needed (FIREBASE_PROJECT, default demo-roll).

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { GoogleAuth, JWT } from 'google-auth-library';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const KEY_FILES = [
  join(ROOT, '.secrets', 'service-account.json'),
  join(ROOT, '..', 'classroom-sync', '.secrets', 'service-account.json'),
];
const SCOPES = ['https://www.googleapis.com/auth/datastore', 'https://www.googleapis.com/auth/cloud-platform'];

function loadServiceAccount() {
  let text = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!text) {
    const file = KEY_FILES.find((f) => existsSync(f));
    if (!file) throw new Error(`No Firebase service-account key. Save it as\n  ${KEY_FILES[0]}\n(the same key as tools/classroom-sync).`);
    text = readFileSync(file, 'utf8');
  }
  const key = JSON.parse(text);
  if (key.type !== 'service_account' || !key.project_id || !key.private_key) throw new Error('The Firebase key is not a service-account key.');
  return key;
}

export function openFirestore() {
  if (process.env.FIRESTORE_EMULATOR_HOST) {
    process.env.METADATA_SERVER_DETECTION ??= 'none';
    const projectId = process.env.FIREBASE_PROJECT ?? 'demo-roll';
    return { db: getFirestore(initializeApp({ projectId })), projectId };
  }
  const key = loadServiceAccount();
  const projectId = key.project_id;
  const db = getFirestore(initializeApp({ projectId }));
  db.settings({ auth: new GoogleAuth({ authClient: new JWT({ email: key.client_email, key: key.private_key, scopes: SCOPES }), projectId }) });
  return { db, projectId };
}

/** `null` when the class doesn't exist (no student of it has signed in yet). */
export async function readClass(db, classId) {
  const ref = db.collection('classes').doc(classId);
  const snapshot = await ref.get();
  if (!snapshot.exists) return null;
  const [subjects, slots] = await Promise.all([ref.collection('subjects').get(), ref.collection('timetable').get()]);
  return {
    subjects: subjects.docs.map((d) => ({ id: d.id, ...d.data() })),
    slots: slots.docs.map((d) => ({ id: d.id, ...d.data() })),
  };
}

export async function writePlan(db, classId, plan) {
  const ref = db.collection('classes').doc(classId);
  const batch = db.batch();
  for (const s of plan.subjects.create) batch.set(ref.collection('subjects').doc(s.id), s.data);
  for (const s of [...plan.slots.create, ...plan.slots.update]) batch.set(ref.collection('timetable').doc(s.id), s.data);
  for (const s of plan.slots.remove) batch.delete(ref.collection('timetable').doc(s.id));
  await batch.commit();
}
