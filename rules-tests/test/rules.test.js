// Firestore security rules tests for Roll v2. Run with `npm test` (starts the emulator).
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  arrayUnion,
  collection,
  collectionGroup,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  increment,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
  writeBatch,
} from 'firebase/firestore';

const CLASS = 'nith-26-ma';
const OTHER_CLASS = 'nith-26-cs';

// uid → email. The admin is also an MA 2026 student.
const USERS = {
  admin: '26bma038@nith.ac.in',
  cr: '26bma010@nith.ac.in',
  student: '26bma020@nith.ac.in',
  newbie: '26bma099@nith.ac.in', // not joined yet
  cs: '26bcs005@nith.ac.in', // another branch
  ee: '26bee001@nith.ac.in', // unsupported branch
  mtech: '26mma001@nith.ac.in', // unsupported programme
  gmail: 'someone@gmail.com',
};

/** "YYYY-MM-DD" in Asia/Kolkata, offset by `days`. Matches istToday() in the rules. */
function istDate(days = 0) {
  const ms = Date.now() + 330 * 60_000 + days * 86_400_000;
  return new Date(ms).toISOString().slice(0, 10);
}

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-roll',
    firestore: {
      rules: readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => {
  await env?.cleanup();
});

/** Firestore as a signed-in user with a verified (or not) email. */
const db = (uid, { verified = true, email = USERS[uid] } = {}) =>
  env.authenticatedContext(uid, { email, email_verified: verified }).firestore();
const anon = () => env.unauthenticatedContext().firestore();

function userDoc(uid, overrides = {}) {
  const local = USERS[uid].split('@')[0];
  return {
    name: uid,
    rollNo: local.toUpperCase(),
    email: USERS[uid],
    batch: 2000 + Number(local.slice(0, 2)),
    branch: local.slice(3, 5).toUpperCase(),
    classId: `nith-${local.slice(0, 2)}-${local.slice(3, 5)}`,
    createdAt: serverTimestamp(),
    ...overrides,
  };
}

function memberDoc(uid, overrides = {}) {
  return { name: uid, rollNo: USERS[uid].split('@')[0].toUpperCase(), joinedAt: serverTimestamp(), ...overrides };
}

function classDoc(overrides = {}) {
  return {
    name: 'MA 2026',
    semester: 'Semester 1 · Odd 2026-27',
    batch: 2026,
    branch: 'MA',
    crUids: [],
    crNames: [],
    requiredPercent: 75,
    timezone: 'Asia/Kolkata',
    memberCount: 1,
    createdAt: serverTimestamp(),
    ...overrides,
  };
}

function sessionDoc(overrides = {}) {
  return {
    subjectId: 'daa', date: istDate(0), startMinutes: 540, endMinutes: 590, type: 'lecture',
    status: 'scheduled', note: '', room: 'LH-204', weight: 1, createdBy: 'cr', createdByName: 'CR',
    createdAt: serverTimestamp(), updatedAt: serverTimestamp(), ...overrides,
  };
}

function markDoc(uid, sessionId, status = 'present', overrides = {}) {
  return { status, markedAt: serverTimestamp(), uid, classId: CLASS, sessionId, ...overrides };
}

function assignmentDoc(createdBy, overrides = {}) {
  return {
    title: 'DAA assignment 3', subjectId: 'daa', type: 'assignment', description: '', link: '',
    dueAt: Timestamp.fromMillis(Date.now() + 86_400_000), createdBy, createdByName: createdBy,
    createdAt: serverTimestamp(), updatedAt: serverTimestamp(), status: 'active', ...overrides,
  };
}

function noticeDoc(overrides = {}) {
  return {
    text: 'Lab moved to Lab 3 this week', pinned: false, createdAt: serverTimestamp(), expiresAt: null,
    createdByName: 'CR', status: 'active', updatedAt: serverTimestamp(), ...overrides,
  };
}

function departmentDoc(code, overrides = {}) {
  return {
    code, name: 'Mathematics and Computing', about: '', hod: '', office: '', email: '', phone: '',
    links: [], updatedAt: serverTimestamp(), ...overrides,
  };
}

function materialSourceDoc(overrides = {}) {
  return {
    title: 'First year', credit: 'Material shared by a senior',
    classroomUrl: 'https://classroom.google.com/w/NjI2OTQwMTM5NDAy/t/all',
    years: [1], branches: ['EC', 'MA'], contentHash: 'h', itemCount: 1, partCount: 0,
    items: [{ id: 'i1', section: 'pyq', sectionLabel: 'Previous year papers', subjectCode: 'MA-111',
      subjectName: 'Eng. Mathematics-1', title: 'END SEM 2024-25', url: 'https://drive.google.com/file/d/p/view' }],
    ...overrides,
  };
}

/** MA 2026 with one CR and two students; CS 2026 with one student. */
async function seed() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const f = ctx.firestore();
    const now = new Date();
    await setDoc(doc(f, 'classes', CLASS), { ...classDoc(), crUids: ['cr'], crNames: ['CR'], memberCount: 3, createdAt: now });
    await setDoc(doc(f, 'classes', OTHER_CLASS), { ...classDoc({ name: 'CS 2026', branch: 'CS' }), memberCount: 1, createdAt: now });
    for (const uid of ['admin', 'cr', 'student']) {
      await setDoc(doc(f, `classes/${CLASS}/members/${uid}`), { ...memberDoc(uid), joinedAt: now });
      await setDoc(doc(f, `users/${uid}`), { ...userDoc(uid), createdAt: now });
    }
    await setDoc(doc(f, `classes/${OTHER_CLASS}/members/cs`), { ...memberDoc('cs'), joinedAt: now });
    await setDoc(doc(f, `classes/${CLASS}/subjects/daa`), {
      name: 'Design and Analysis of Algorithms', shortName: 'DAA', code: '', faculty: '', colorIndex: 0, archived: false,
    });
    const s = (o) => ({ ...sessionDoc(o), createdAt: now, updatedAt: now });
    await setDoc(doc(f, `classes/${CLASS}/sessions/today`), s());
    await setDoc(doc(f, `classes/${CLASS}/sessions/twoDaysAgo`), s({ date: istDate(-2) }));
    await setDoc(doc(f, `classes/${CLASS}/sessions/threeDaysAgo`), s({ date: istDate(-3) }));
    await setDoc(doc(f, `classes/${CLASS}/sessions/cancelled`), s({ status: 'cancelled' }));
    await setDoc(doc(f, `classes/${CLASS}/sessions/today/marks/student`), { ...markDoc('student', 'today'), markedAt: now });
    await setDoc(doc(f, `classes/${CLASS}/assignments/a1`), { ...assignmentDoc('cr'), createdAt: now, updatedAt: now });
    await setDoc(doc(f, `classes/${CLASS}/notices/n1`), { ...noticeDoc(), createdAt: now, updatedAt: now });
    await setDoc(doc(f, 'notices/c1'), { ...noticeDoc({ createdByName: 'Admin' }), createdAt: now, updatedAt: now });
    await setDoc(doc(f, 'departments/MA'), { ...departmentDoc('MA'), updatedAt: now });
    await setDoc(doc(f, 'materialSources/fy-ec-ma'), materialSourceDoc({ syncedAt: now }));
    await setDoc(doc(f, 'materialSources/fy-ec-ma/parts/0'), { n: 0, items: [] });
    await setDoc(doc(f, 'users/student/assignmentStatus/a1'), { done: true, doneAt: now });
  });
}

beforeEach(async () => {
  await env.clearFirestore();
  await seed();
});

// ---------------------------------------------------------------------------------------------

describe('who counts as a student', () => {
  test('wrong domain can read or write nothing', async () => {
    await assertFails(getDoc(doc(db('gmail'), 'classes', CLASS)));
    await assertFails(setDoc(doc(db('gmail'), 'users/gmail'), userDoc('newbie')));
    await assertFails(getDoc(doc(db('gmail'), 'departments/MA')));
    await assertFails(getDoc(doc(db('gmail'), 'notices/c1')));
  });

  test('an unverified email gets nothing, even with the right domain', async () => {
    const f = db('student', { verified: false });
    await assertFails(getDoc(doc(f, 'classes', CLASS)));
    await assertFails(getDoc(doc(f, 'users/student')));
    await assertFails(getDoc(doc(f, 'departments/MA')));
  });

  test('unsupported branches and programmes get nothing', async () => {
    for (const uid of ['ee', 'mtech']) {
      await assertFails(getDoc(doc(db(uid), 'departments/MA')));
      await assertFails(getDoc(doc(db(uid), 'notices/c1')));
      await assertFails(setDoc(doc(db(uid), `users/${uid}`), userDoc(uid)));
    }
  });

  test('upper-case emails are treated the same', async () => {
    const f = db('student', { email: '26BMA020@NITH.AC.IN' });
    await assertSucceeds(getDoc(doc(f, 'classes', CLASS)));
  });

  test('signed-out users get nothing', async () => {
    await assertFails(getDoc(doc(anon(), 'classes', CLASS)));
    await assertFails(getDoc(doc(anon(), 'departments/MA')));
  });
});

describe('users', () => {
  test('a student creates their own profile matching their email', async () => {
    await assertSucceeds(setDoc(doc(db('newbie'), 'users/newbie'), userDoc('newbie')));
  });

  test('roll number, batch, branch and class must match the email', async () => {
    const f = db('newbie');
    await assertFails(setDoc(doc(f, 'users/newbie'), userDoc('newbie', { rollNo: '26BMA001' })));
    await assertFails(setDoc(doc(f, 'users/newbie'), userDoc('newbie', { batch: 2025 })));
    await assertFails(setDoc(doc(f, 'users/newbie'), userDoc('newbie', { branch: 'CS' })));
    await assertFails(setDoc(doc(f, 'users/newbie'), userDoc('newbie', { classId: OTHER_CLASS })));
  });

  test('roll number and branch can never be edited later; the name can', async () => {
    const f = db('student');
    await assertFails(updateDoc(doc(f, 'users/student'), { rollNo: '26BMA001' }));
    await assertFails(updateDoc(doc(f, 'users/student'), { branch: 'CS' }));
    await assertFails(updateDoc(doc(f, 'users/student'), { classId: OTHER_CLASS }));
    await assertSucceeds(updateDoc(doc(f, 'users/student'), { name: 'New name' }));
  });

  test("students can't read other profiles; the admin can", async () => {
    await assertFails(getDoc(doc(db('cr'), 'users/student')));
    await assertSucceeds(getDoc(doc(db('admin'), 'users/student')));
  });

  test('a profile for someone else is refused', async () => {
    await assertFails(setDoc(doc(db('newbie'), 'users/student'), userDoc('newbie')));
  });
});

describe('joining the class from the email', () => {
  test('the first student creates the class with no CRs, with their member doc', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => deleteDoc(doc(ctx.firestore(), 'classes', CLASS)));
    const f = db('newbie');
    const batch = writeBatch(f);
    batch.set(doc(f, 'classes', CLASS), classDoc());
    batch.set(doc(f, `classes/${CLASS}/members/newbie`), memberDoc('newbie'));
    batch.set(doc(f, 'users/newbie'), userDoc('newbie'));
    await assertSucceeds(batch.commit());
  });

  test('a new class must start with no CRs', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => deleteDoc(doc(ctx.firestore(), 'classes', CLASS)));
    const f = db('newbie');
    const batch = writeBatch(f);
    batch.set(doc(f, 'classes', CLASS), classDoc({ crUids: ['newbie'], crNames: ['newbie'] }));
    batch.set(doc(f, `classes/${CLASS}/members/newbie`), memberDoc('newbie'));
    await assertFails(batch.commit());
  });

  test('later students join and bump the member count by one', async () => {
    const f = db('newbie');
    const batch = writeBatch(f);
    batch.set(doc(f, `classes/${CLASS}/members/newbie`), memberDoc('newbie'));
    batch.update(doc(f, 'classes', CLASS), { memberCount: increment(1) });
    await assertSucceeds(batch.commit());
  });

  test("the member count can't be bumped without joining", async () => {
    await assertFails(updateDoc(doc(db('student'), 'classes', CLASS), { memberCount: increment(1) }));
  });

  test("joining another branch's class is refused", async () => {
    const f = db('newbie');
    await assertFails(setDoc(doc(f, `classes/${OTHER_CLASS}/members/newbie`), memberDoc('newbie')));
    await assertFails(setDoc(doc(f, 'classes', 'nith-26-ce'), classDoc({ branch: 'CE' })));
  });

  test("a member doc's roll number must match the email", async () => {
    await assertFails(setDoc(doc(db('newbie'), `classes/${CLASS}/members/newbie`), memberDoc('newbie', { rollNo: '26BMA001' })));
  });

  test("students can read their own class only", async () => {
    await assertSucceeds(getDoc(doc(db('student'), 'classes', CLASS)));
    await assertFails(getDoc(doc(db('student'), 'classes', OTHER_CLASS)));
    await assertFails(getDocs(collection(db('student'), 'classes')));
  });
});

describe('admin and CRs', () => {
  test('the admin lists every class and reads members and users', async () => {
    const f = db('admin');
    await assertSucceeds(getDocs(collection(f, 'classes')));
    await assertSucceeds(getDocs(collection(f, `classes/${OTHER_CLASS}/members`)));
    await assertSucceeds(getDoc(doc(f, 'users/student')));
  });

  test('the admin appoints a CR', async () => {
    await assertSucceeds(updateDoc(doc(db('admin'), 'classes', CLASS), {
      crUids: arrayUnion('student'), crNames: ['CR', 'student'],
    }));
    await assertSucceeds(updateDoc(doc(db('admin'), 'classes', OTHER_CLASS), { crUids: ['cs'], crNames: ['cs'] }));
  });

  test('at most three CRs, and names must match', async () => {
    const f = db('admin');
    await assertFails(updateDoc(doc(f, 'classes', CLASS), { crUids: ['a', 'b', 'c', 'd'], crNames: ['a', 'b', 'c', 'd'] }));
    await assertFails(updateDoc(doc(f, 'classes', CLASS), { crUids: ['a', 'b'], crNames: ['a'] }));
  });

  test("a CR can't change the CR list", async () => {
    await assertFails(updateDoc(doc(db('cr'), 'classes', CLASS), { crUids: ['cr', 'student'], crNames: ['CR', 'S'] }));
    await assertFails(updateDoc(doc(db('cr'), 'classes', CLASS), { crUids: [], crNames: [] }));
  });

  test("a student can't make themselves CR", async () => {
    await assertFails(updateDoc(doc(db('student'), 'classes', CLASS), { crUids: ['cr', 'student'], crNames: ['CR', 'S'] }));
  });

  test('a CR edits class settings; a student cannot', async () => {
    await assertSucceeds(updateDoc(doc(db('cr'), 'classes', CLASS), { name: 'MA 2026 — B.Tech', requiredPercent: 80 }));
    await assertFails(updateDoc(doc(db('student'), 'classes', CLASS), { requiredPercent: 50 }));
  });

  test('only CRs and the admin see the member list', async () => {
    await assertSucceeds(getDocs(collection(db('cr'), `classes/${CLASS}/members`)));
    await assertFails(getDocs(collection(db('student'), `classes/${CLASS}/members`)));
    await assertSucceeds(getDoc(doc(db('student'), `classes/${CLASS}/members/student`)));
  });
});

describe('class content: subjects, timetable, sessions', () => {
  const subject = { name: 'Computer Networks', shortName: 'CN', code: '', faculty: '', colorIndex: 2, archived: false };
  const slot = { dayOfWeek: 1, startMinutes: 600, endMinutes: 650, subjectId: 'daa', type: 'lab', room: 'Lab 3' };

  test('everyone in the class reads; other branches cannot', async () => {
    for (const path of ['subjects', 'timetable', 'sessions']) {
      await assertSucceeds(getDocs(collection(db('student'), `classes/${CLASS}/${path}`)));
      await assertFails(getDocs(collection(db('cs'), `classes/${CLASS}/${path}`)));
    }
  });

  test('a student cannot post a class, subject or timetable slot', async () => {
    const f = db('student');
    await assertFails(setDoc(doc(f, `classes/${CLASS}/sessions/new`), sessionDoc({ createdBy: 'student' })));
    await assertFails(setDoc(doc(f, `classes/${CLASS}/subjects/cn`), subject));
    await assertFails(setDoc(doc(f, `classes/${CLASS}/timetable/s1`), slot));
  });

  test('a CR posts, edits and cancels', async () => {
    const f = db('cr');
    await assertSucceeds(setDoc(doc(f, `classes/${CLASS}/subjects/cn`), subject));
    await assertSucceeds(setDoc(doc(f, `classes/${CLASS}/timetable/s1`), slot));
    await assertSucceeds(setDoc(doc(f, `classes/${CLASS}/sessions/new`), sessionDoc()));
    await assertSucceeds(updateDoc(doc(f, `classes/${CLASS}/sessions/today`), {
      status: 'cancelled', note: 'faculty on leave', updatedAt: serverTimestamp(),
    }));
  });

  test("a CR of one class can't post in another", async () => {
    await assertFails(setDoc(doc(db('cr'), `classes/${OTHER_CLASS}/sessions/new`), sessionDoc()));
  });
});

describe('marks', () => {
  const markRef = (f, sessionId, uid) => doc(f, `classes/${CLASS}/sessions/${sessionId}/marks/${uid}`);
  const mark = (uid, sessionId, status = 'present', asUid = uid) =>
    setDoc(markRef(db(asUid), sessionId, uid), markDoc(uid, sessionId, status));

  test('students mark their own attendance within the window', async () => {
    await assertSucceeds(mark('student', 'today'));
    await assertSucceeds(mark('student', 'today', 'cleared'));
    await assertSucceeds(mark('student', 'twoDaysAgo', 'absent'));
    await assertFails(mark('student', 'threeDaysAgo'));
    await assertFails(mark('student', 'cancelled'));
  });

  test('nobody marks for someone else; other branches cannot mark', async () => {
    await assertFails(mark('student', 'today', 'absent', 'cr'));
    await assertFails(setDoc(markRef(db('cs'), 'today', 'cs'), markDoc('cs', 'today')));
  });

  test('CRs read all marks; students only their own', async () => {
    await assertSucceeds(getDocs(collection(db('cr'), `classes/${CLASS}/sessions/today/marks`)));
    await assertFails(getDocs(collection(db('student'), `classes/${CLASS}/sessions/today/marks`)));
    await assertSucceeds(getDocs(query(collectionGroup(db('student'), 'marks'), where('uid', '==', 'student'))));
    await assertFails(getDocs(query(collectionGroup(db('cr'), 'marks'), where('uid', '==', 'student'))));
  });
});

describe('assignments', () => {
  test('a student cannot post or edit an assignment', async () => {
    await assertFails(setDoc(doc(db('student'), `classes/${CLASS}/assignments/a2`), assignmentDoc('student')));
    await assertFails(updateDoc(doc(db('student'), `classes/${CLASS}/assignments/a1`), {
      status: 'deleted', updatedAt: serverTimestamp(),
    }));
  });

  test('a CR posts, edits and deletes (tombstone)', async () => {
    const f = db('cr');
    await assertSucceeds(setDoc(doc(f, `classes/${CLASS}/assignments/a2`), assignmentDoc('cr', { type: 'lab_report' })));
    await assertSucceeds(updateDoc(doc(f, `classes/${CLASS}/assignments/a1`), { title: 'Renamed', updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(f, `classes/${CLASS}/assignments/a1`), { status: 'deleted', updatedAt: serverTimestamp() }));
  });

  test('the admin can post too', async () => {
    await assertSucceeds(setDoc(doc(db('admin'), `classes/${OTHER_CLASS}/assignments/x`), assignmentDoc('admin')));
  });

  test('bad types and hard deletes are refused', async () => {
    await assertFails(setDoc(doc(db('cr'), `classes/${CLASS}/assignments/a3`), assignmentDoc('cr', { type: 'homework' })));
    await assertFails(deleteDoc(doc(db('cr'), `classes/${CLASS}/assignments/a1`)));
  });

  test('students read assignments of their own class only', async () => {
    await assertSucceeds(getDocs(collection(db('student'), `classes/${CLASS}/assignments`)));
    await assertFails(getDocs(collection(db('cs'), `classes/${CLASS}/assignments`)));
  });

  test("done ticks are private: nobody reads another student's", async () => {
    await assertSucceeds(setDoc(doc(db('student'), 'users/student/assignmentStatus/a2'), { done: true, doneAt: serverTimestamp() }));
    await assertSucceeds(getDoc(doc(db('student'), 'users/student/assignmentStatus/a1')));
    await assertFails(getDoc(doc(db('cr'), 'users/student/assignmentStatus/a1')));
    await assertFails(getDoc(doc(db('admin'), 'users/student/assignmentStatus/a1')));
    await assertFails(setDoc(doc(db('cr'), 'users/student/assignmentStatus/a1'), { done: false, doneAt: serverTimestamp() }));
  });
});

describe('notices', () => {
  test('a student cannot post a class or college notice', async () => {
    await assertFails(setDoc(doc(db('student'), `classes/${CLASS}/notices/n2`), noticeDoc()));
    await assertFails(setDoc(doc(db('student'), 'notices/c2'), noticeDoc()));
  });

  test('a CR posts class notices but not college ones', async () => {
    await assertSucceeds(setDoc(doc(db('cr'), `classes/${CLASS}/notices/n2`), noticeDoc({ pinned: true })));
    await assertFails(setDoc(doc(db('cr'), 'notices/c2'), noticeDoc()));
  });

  test('the admin posts college notices', async () => {
    await assertSucceeds(setDoc(doc(db('admin'), 'notices/c2'), noticeDoc({ expiresAt: Timestamp.fromMillis(Date.now() + 1e9) })));
    await assertSucceeds(updateDoc(doc(db('admin'), 'notices/c1'), { status: 'deleted', updatedAt: serverTimestamp() }));
  });

  test('notices are capped at 500 characters', async () => {
    await assertFails(setDoc(doc(db('cr'), `classes/${CLASS}/notices/n3`), noticeDoc({ text: 'x'.repeat(501) })));
  });

  test('every valid student reads college notices; class notices stay in the class', async () => {
    await assertSucceeds(getDoc(doc(db('cs'), 'notices/c1')));
    await assertFails(getDoc(doc(db('cs'), `classes/${CLASS}/notices/n1`)));
  });
});

describe('departments', () => {
  test('any valid student reads; only the admin writes', async () => {
    await assertSucceeds(getDoc(doc(db('cs'), 'departments/MA')));
    await assertFails(setDoc(doc(db('cr'), 'departments/MA'), departmentDoc('MA', { hod: 'x' })));
    await assertSucceeds(setDoc(doc(db('admin'), 'departments/MA'), departmentDoc('MA', {
      links: [{ title: 'Department page', url: 'https://nith.ac.in' }],
    })));
  });

  test('only supported branches have department docs', async () => {
    await assertFails(setDoc(doc(db('admin'), 'departments/EE'), departmentDoc('EE', { name: 'Electrical' })));
  });
});

describe('study material', () => {
  test('any valid student reads the index and its parts', async () => {
    await assertSucceeds(getDoc(doc(db('student'), 'materialSources/fy-ec-ma')));
    await assertSucceeds(getDoc(doc(db('cs'), 'materialSources/fy-ec-ma')));
    await assertSucceeds(getDocs(collection(db('student'), 'materialSources')));
    await assertSucceeds(getDoc(doc(db('student'), 'materialSources/fy-ec-ma/parts/0')));
  });

  test('signed-out and non-college users cannot read', async () => {
    await assertFails(getDoc(doc(anon(), 'materialSources/fy-ec-ma')));
    await assertFails(getDoc(doc(anon(), 'materialSources/fy-ec-ma/parts/0')));
    await assertFails(getDoc(doc(db('gmail'), 'materialSources/fy-ec-ma')));
  });

  test('no client writes, not even the admin; only the sync script (Admin SDK) writes', async () => {
    for (const uid of ['student', 'cr', 'admin']) {
      const f = db(uid);
      await assertFails(setDoc(doc(f, 'materialSources/fy-ec-ma'), materialSourceDoc({ credit: 'x' })));
      await assertFails(updateDoc(doc(f, 'materialSources/fy-ec-ma'), { items: [] }));
      await assertFails(deleteDoc(doc(f, 'materialSources/fy-ec-ma')));
      await assertFails(setDoc(doc(f, 'materialSources/new'), materialSourceDoc()));
      await assertFails(setDoc(doc(f, 'materialSources/fy-ec-ma/parts/1'), { n: 1, items: [] }));
    }
  });
});
