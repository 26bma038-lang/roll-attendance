// Read-only Classroom API calls for one course, paging through everything.

import { SyncError } from './auth.js';

const API = 'https://classroom.googleapis.com/v1';

/** Turns a Classroom API error into advice. Exported for tests. */
export function apiError(status, body, courseId) {
  const error = body?.error ?? {};
  const message = error.message ?? `HTTP ${status}`;
  const details = JSON.stringify(error.details ?? []);
  if (/ClassroomApiDisabled/i.test(message)) {
    return new SyncError(
      `NITH's Google Workspace has turned off Classroom API access for this account (@ClassroomApiDisabled).
That is a college admin setting. Ask the NITH Workspace admin to allow the Classroom API, or sign in
with another account that is in the class.`,
    );
  }
  if (/SERVICE_DISABLED|has not been used in project|is disabled/i.test(message + details)) {
    return new SyncError(`The Classroom API is not enabled in your Google Cloud project. Enable it (README step 1) and try again.\n${message}`);
  }
  if (status === 401) return new SyncError(`Google rejected the access token: ${message}. Run \`npm run login\` again.`);
  if (status === 403 && /insufficient.*scope/i.test(message + details)) {
    return new SyncError('The saved sign-in is missing a permission. Run `npm run login` again and tick every box.');
  }
  if (status === 403 || status === 404) {
    return new SyncError(
      `Can't open course ${courseId} (${status}: ${message}).
The signed-in account must be a member of the class. Open the classroomUrl from sources.json in the
browser with the same account and join the class first.`,
    );
  }
  return new SyncError(`Classroom API error ${status}: ${message}`);
}

async function getAll(token, courseId, path, listKey) {
  const all = [];
  let pageToken;
  do {
    const url = new URL(`${API}/courses/${courseId}/${path}`);
    url.searchParams.set('pageSize', '100');
    if (pageToken) url.searchParams.set('pageToken', pageToken);
    const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw apiError(response.status, body, courseId);
    all.push(...(body[listKey] ?? []));
    pageToken = body.nextPageToken;
  } while (pageToken);
  return all;
}

/** Everything buildIndex needs: `{ course, topics, materials }`. All study material is posted as
 * course-work materials; course work (assignments with submissions) isn't read, because
 * NITH accounts can't grant a coursework scope. */
export async function fetchCourse(token, courseId) {
  const response = await fetch(`${API}/courses/${courseId}`, { headers: { Authorization: `Bearer ${token}` } });
  const course = await response.json().catch(() => ({}));
  if (!response.ok) throw apiError(response.status, course, courseId);

  const [topics, materials] = await Promise.all([
    getAll(token, courseId, 'topics', 'topic'),
    getAll(token, courseId, 'courseWorkMaterials', 'courseWorkMaterial'),
  ]);
  return { course: { id: course.id, name: course.name, section: course.section }, topics, materials };
}
