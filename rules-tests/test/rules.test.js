// Firestore security rules tests. Run with `npm test` (starts the emulator).
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  collectionGroup,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
} from 'firebase/firestore';

const CLASS = 'class1';
const CODE = 'K7MP2X';
const CR = 'cr';
const STUDENT = 'student';
const OTHER = 'other'; // member of the class
const OUTSIDER = 'outsider'; // signed in, not a member

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

const db = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

function classDoc(overrides = {}) {
  return {
    name: 'CSE DS — C',
    semester: 'Odd 2026-27',
    crUid: CR,
    joinCode: CODE,
    requiredPercent: 75,
    timezone: 'Asia/Kolkata',
    createdAt: serverTimestamp(),
    ...overrides,
  };
}

function sessionDoc(overrides = {}) {
  return {
    subjectId: 'daa',
    date: istDate(0),
    startMinutes: 540,
    endMinutes: 590,
    type: 'lecture',
    status: 'scheduled',
    note: '',
    room: 'LH-204',
    weight: 1,
    createdBy: CR,
    createdByName: 'Sahil',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function markDoc(uid, sessionId, status = 'present', overrides = {}) {
  return {
    status,
    markedAt: serverTimestamp(),
    uid,
    classId: CLASS,
    sessionId,
    ...overrides,
  };
}

/** A class with a CR, two members, one subject, sessions for today / 2 days ago / 3 days ago. */
async function seed() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const f = ctx.firestore();
    const now = new Date();
    await setDoc(doc(f, 'classes', CLASS), { ...classDoc(), createdAt: now });
    await setDoc(doc(f, 'joinCodes', CODE), { classId: CLASS });
    for (const uid of [CR, STUDENT, OTHER]) {
      await setDoc(doc(f, `classes/${CLASS}/members/${uid}`), {
        name: uid, rollNo: `R-${uid}`, joinedAt: now, joinCode: CODE,
      });
    }
    await setDoc(doc(f, `classes/${CLASS}/subjects/daa`), {
      name: 'Design and Analysis of Algorithms', shortName: 'DAA', code: '21CSC204J',
      faculty: 'Dr. Rao', colorIndex: 0, archived: false,
    });
    await setDoc(doc(f, `classes/${CLASS}/timetable/slot1`), {
      dayOfWeek: 4, startMinutes: 540, endMinutes: 590, subjectId: 'daa', type: 'lecture', room: 'LH-204',
    });
    const s = (o) => ({ ...sessionDoc(o), createdAt: now, updatedAt: now });
    await setDoc(doc(f, `classes/${CLASS}/sessions/today`), s());
    await setDoc(doc(f, `classes/${CLASS}/sessions/twoDaysAgo`), s({ date: istDate(-2) }));
    await setDoc(doc(f, `classes/${CLASS}/sessions/threeDaysAgo`), s({ date: istDate(-3) }));
    await setDoc(doc(f, `classes/${CLASS}/sessions/tomorrow`), s({ date: istDate(1) }));
    await setDoc(doc(f, `classes/${CLASS}/sessions/cancelled`), s({ status: 'cancelled', note: 'faculty on leave' }));
    await setDoc(doc(f, `classes/${CLASS}/sessions/today/marks/${OTHER}`), { ...markDoc(OTHER, 'today'), markedAt: now });
    await setDoc(doc(f, `users/${STUDENT}`), { name: 'Student', rollNo: 'R1', email: 's@x', classId: CLASS, createdAt: now });
  });
}

beforeEach(async () => {
  await env.clearFirestore();
  await seed();
});

describe('users', () => {
  test('a user can read and write their own doc', async () => {
    await assertSucceeds(getDoc(doc(db(STUDENT), 'users', STUDENT)));
    await assertSucceeds(setDoc(doc(db(STUDENT), 'users', STUDENT), { name: 'S', rollNo: 'R1', email: '', classId: null, createdAt: serverTimestamp() }));
  });

  test("a user can't read or write someone else's doc", async () => {
    await assertFails(getDoc(doc(db(OTHER), 'users', STUDENT)));
    await assertFails(setDoc(doc(db(OTHER), 'users', STUDENT), { name: 'x' }));
  });

  test('signed-out users get nothing', async () => {
    await assertFails(getDoc(doc(anon(), 'users', STUDENT)));
  });
});

describe('joinCodes', () => {
  test('signed-in users can get a code', async () => {
    await assertSucceeds(getDoc(doc(db(OUTSIDER), 'joinCodes', CODE)));
  });

  test('signed-out users cannot get a code', async () => {
    await assertFails(getDoc(doc(anon(), 'joinCodes', CODE)));
  });

  test('nobody can list codes', async () => {
    await assertFails(getDocs(collection(db(OUTSIDER), 'joinCodes')));
    await assertFails(getDocs(collection(db(CR), 'joinCodes')));
  });

  test('the CR can create and delete a code for their class', async () => {
    await assertSucceeds(setDoc(doc(db(CR), 'joinCodes', 'ABCDEF'), { classId: CLASS }));
    await assertSucceeds(deleteDoc(doc(db(CR), 'joinCodes', CODE)));
  });

  test('a non-CR cannot create or delete a code', async () => {
    await assertFails(setDoc(doc(db(STUDENT), 'joinCodes', 'ABCDEF'), { classId: CLASS }));
    await assertFails(deleteDoc(doc(db(STUDENT), 'joinCodes', CODE)));
  });

  test('an existing code cannot be repointed', async () => {
    await assertFails(setDoc(doc(db(CR), 'joinCodes', CODE), { classId: 'elsewhere' }));
  });

  test('malformed codes are rejected', async () => {
    await assertFails(setDoc(doc(db(CR), 'joinCodes', 'ABC0EF'), { classId: CLASS }));
  });
});

describe('classes', () => {
  test('anyone signed in can create a class as its CR, with code and member doc in one batch', async () => {
    const f = db(OUTSIDER);
    const batch = writeBatch(f);
    batch.set(doc(f, 'classes', 'new'), classDoc({ crUid: OUTSIDER, joinCode: 'NEWCDE' }));
    batch.set(doc(f, 'joinCodes', 'NEWCDE'), { classId: 'new' });
    batch.set(doc(f, 'classes/new/members', OUTSIDER), { name: 'O', rollNo: 'R9', joinedAt: serverTimestamp(), joinCode: 'NEWCDE' });
    await assertSucceeds(batch.commit());
  });

  test('cannot create a class naming someone else as CR', async () => {
    await assertFails(setDoc(doc(db(OUTSIDER), 'classes', 'new'), classDoc({ crUid: STUDENT })));
  });

  test('cannot create a class with a bad required percent', async () => {
    await assertFails(setDoc(doc(db(OUTSIDER), 'classes', 'new'), classDoc({ crUid: OUTSIDER, requiredPercent: 120 })));
  });

  test('signed-out users cannot create a class', async () => {
    await assertFails(setDoc(doc(anon(), 'classes', 'new'), classDoc()));
  });

  test('members and the CR can read the class; outsiders cannot', async () => {
    await assertSucceeds(getDoc(doc(db(STUDENT), 'classes', CLASS)));
    await assertSucceeds(getDoc(doc(db(CR), 'classes', CLASS)));
    await assertFails(getDoc(doc(db(OUTSIDER), 'classes', CLASS)));
  });

  test('only the CR can update the class', async () => {
    await assertSucceeds(updateDoc(doc(db(CR), 'classes', CLASS), { requiredPercent: 80 }));
    await assertFails(updateDoc(doc(db(STUDENT), 'classes', CLASS), { requiredPercent: 50 }));
  });

  test('the CR can transfer the role to a member', async () => {
    await assertSucceeds(updateDoc(doc(db(CR), 'classes', CLASS), { crUid: STUDENT }));
  });

  test('the CR cannot transfer the role to a non-member', async () => {
    await assertFails(updateDoc(doc(db(CR), 'classes', CLASS), { crUid: OUTSIDER }));
  });

  test('a member cannot take the CR role', async () => {
    await assertFails(updateDoc(doc(db(STUDENT), 'classes', CLASS), { crUid: STUDENT }));
  });

  test('nobody can delete a class', async () => {
    await assertFails(deleteDoc(doc(db(CR), 'classes', CLASS)));
  });
});

describe('members', () => {
  const join = (uid, code = CODE, classId = CLASS) =>
    setDoc(doc(db(uid), `classes/${classId}/members/${uid}`), {
      name: 'New', rollNo: 'R7', joinedAt: serverTimestamp(), joinCode: code,
    });

  test('a user can join with a valid code', async () => {
    await assertSucceeds(join(OUTSIDER));
  });

  test('a user cannot join with a wrong or missing code', async () => {
    await assertFails(join(OUTSIDER, 'ZZZZZZ'));
    await assertFails(setDoc(doc(db(OUTSIDER), `classes/${CLASS}/members/${OUTSIDER}`), {
      name: 'New', rollNo: 'R7', joinedAt: serverTimestamp(),
    }));
  });

  test("a code for another class doesn't open this one", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'joinCodes', 'OTHERC'), { classId: 'class2' });
    });
    await assertFails(join(OUTSIDER, 'OTHERC'));
  });

  test('a user cannot create a member doc for someone else', async () => {
    await assertFails(setDoc(doc(db(OUTSIDER), `classes/${CLASS}/members/someoneElse`), {
      name: 'X', rollNo: 'R', joinedAt: serverTimestamp(), joinCode: CODE,
    }));
  });

  test('members read only their own doc; the CR reads all', async () => {
    await assertSucceeds(getDoc(doc(db(STUDENT), `classes/${CLASS}/members/${STUDENT}`)));
    await assertFails(getDoc(doc(db(STUDENT), `classes/${CLASS}/members/${OTHER}`)));
    await assertSucceeds(getDocs(collection(db(CR), `classes/${CLASS}/members`)));
    await assertFails(getDocs(collection(db(STUDENT), `classes/${CLASS}/members`)));
  });

  test('a member can update their own name and roll number only', async () => {
    await assertSucceeds(updateDoc(doc(db(STUDENT), `classes/${CLASS}/members/${STUDENT}`), { rollNo: 'R2' }));
    await assertFails(updateDoc(doc(db(STUDENT), `classes/${CLASS}/members/${STUDENT}`), { joinCode: 'ABCDEF' }));
    await assertFails(updateDoc(doc(db(STUDENT), `classes/${CLASS}/members/${OTHER}`), { rollNo: 'R2' }));
  });

  test('the CR can remove a member', async () => {
    await assertSucceeds(deleteDoc(doc(db(CR), `classes/${CLASS}/members/${STUDENT}`)));
  });

  test('a member cannot remove someone else', async () => {
    await assertFails(deleteDoc(doc(db(STUDENT), `classes/${CLASS}/members/${OTHER}`)));
  });

  test('a member can leave', async () => {
    await assertSucceeds(deleteDoc(doc(db(STUDENT), `classes/${CLASS}/members/${STUDENT}`)));
  });

  test('the CR cannot leave without transferring the role', async () => {
    await assertFails(deleteDoc(doc(db(CR), `classes/${CLASS}/members/${CR}`)));
  });
});

describe('subjects, timetable, sessions', () => {
  const subject = { name: 'Computer Networks', shortName: 'CN', code: '', faculty: '', colorIndex: 2, archived: false };
  const slot = { dayOfWeek: 1, startMinutes: 600, endMinutes: 650, subjectId: 'daa', type: 'lab', room: 'Lab 3' };

  test('members and the CR can read; outsiders cannot', async () => {
    for (const path of ['subjects', 'timetable', 'sessions']) {
      await assertSucceeds(getDocs(collection(db(STUDENT), `classes/${CLASS}/${path}`)));
      await assertSucceeds(getDocs(collection(db(CR), `classes/${CLASS}/${path}`)));
      await assertFails(getDocs(collection(db(OUTSIDER), `classes/${CLASS}/${path}`)));
    }
  });

  test('only the CR writes subjects', async () => {
    await assertSucceeds(setDoc(doc(db(CR), `classes/${CLASS}/subjects/cn`), subject));
    await assertFails(setDoc(doc(db(STUDENT), `classes/${CLASS}/subjects/cn`), subject));
    await assertSucceeds(updateDoc(doc(db(CR), `classes/${CLASS}/subjects/daa`), { archived: true }));
    await assertFails(updateDoc(doc(db(STUDENT), `classes/${CLASS}/subjects/daa`), { archived: true }));
  });

  test('only the CR writes the timetable', async () => {
    await assertSucceeds(setDoc(doc(db(CR), `classes/${CLASS}/timetable/slot2`), slot));
    await assertFails(setDoc(doc(db(STUDENT), `classes/${CLASS}/timetable/slot2`), slot));
    await assertFails(deleteDoc(doc(db(STUDENT), `classes/${CLASS}/timetable/slot1`)));
    await assertSucceeds(deleteDoc(doc(db(CR), `classes/${CLASS}/timetable/slot1`)));
  });

  test('timetable slots must end after they start', async () => {
    await assertFails(setDoc(doc(db(CR), `classes/${CLASS}/timetable/bad`), { ...slot, endMinutes: 500 }));
  });

  test('only the CR posts, edits and cancels sessions', async () => {
    await assertSucceeds(setDoc(doc(db(CR), `classes/${CLASS}/sessions/new`), sessionDoc()));
    await assertFails(setDoc(doc(db(STUDENT), `classes/${CLASS}/sessions/new2`), sessionDoc({ createdBy: STUDENT })));
    await assertSucceeds(updateDoc(doc(db(CR), `classes/${CLASS}/sessions/today`), {
      status: 'cancelled', note: 'faculty on leave', updatedAt: serverTimestamp(),
    }));
    await assertFails(updateDoc(doc(db(STUDENT), `classes/${CLASS}/sessions/today`), {
      status: 'cancelled', updatedAt: serverTimestamp(),
    }));
  });

  test('session writes must stamp updatedAt', async () => {
    await assertFails(updateDoc(doc(db(CR), `classes/${CLASS}/sessions/today`), { note: 'Moved to Lab 3' }));
  });

  test('session weight is bounded', async () => {
    await assertFails(setDoc(doc(db(CR), `classes/${CLASS}/sessions/heavy`), sessionDoc({ weight: 9 })));
  });
});

describe('marks', () => {
  const markRef = (f, sessionId, uid) => doc(f, `classes/${CLASS}/sessions/${sessionId}/marks/${uid}`);
  const mark = (uid, sessionId, status = 'present', asUid = uid) =>
    setDoc(markRef(db(asUid), sessionId, uid), markDoc(uid, sessionId, status));

  test('a member marks their own attendance today', async () => {
    await assertSucceeds(mark(STUDENT, 'today'));
    await assertSucceeds(mark(STUDENT, 'today', 'absent'));
    await assertSucceeds(mark(STUDENT, 'today', 'cleared'));
  });

  test('the CR marks their own attendance too', async () => {
    await assertSucceeds(mark(CR, 'today'));
  });

  test('marks two days back are allowed; three days back are not', async () => {
    await assertSucceeds(mark(STUDENT, 'twoDaysAgo'));
    await assertFails(mark(STUDENT, 'threeDaysAgo'));
  });

  test('future sessions cannot be marked', async () => {
    await assertFails(mark(STUDENT, 'tomorrow'));
  });

  test('cancelled sessions cannot be marked', async () => {
    await assertFails(mark(STUDENT, 'cancelled'));
  });

  test("nobody can mark for someone else, not even the CR", async () => {
    await assertFails(mark(OTHER, 'today', 'absent', STUDENT));
    await assertFails(mark(STUDENT, 'today', 'absent', CR));
  });

  test('non-members cannot mark', async () => {
    await assertFails(mark(OUTSIDER, 'today'));
  });

  test('only present, absent or cleared', async () => {
    await assertFails(mark(STUDENT, 'today', 'late'));
  });

  test('mark fields must match their path', async () => {
    await assertFails(setDoc(markRef(db(STUDENT), 'today', STUDENT), markDoc(STUDENT, 'twoDaysAgo')));
    await assertFails(setDoc(markRef(db(STUDENT), 'today', STUDENT), markDoc(STUDENT, 'today', 'present', { classId: 'class2' })));
  });

  test('the CR reads all marks for a session', async () => {
    await assertSucceeds(getDocs(collection(db(CR), `classes/${CLASS}/sessions/today/marks`)));
  });

  test('students read only their own mark', async () => {
    await assertSucceeds(getDoc(markRef(db(OTHER), 'today', OTHER)));
    await assertFails(getDoc(markRef(db(STUDENT), 'today', OTHER)));
    await assertFails(getDocs(collection(db(STUDENT), `classes/${CLASS}/sessions/today/marks`)));
  });

  test("students can query their own marks across sessions, not anyone else's", async () => {
    await assertSucceeds(getDocs(query(collectionGroup(db(OTHER), 'marks'), where('uid', '==', OTHER))));
    await assertFails(getDocs(query(collectionGroup(db(STUDENT), 'marks'), where('uid', '==', OTHER))));
    await assertFails(getDocs(collectionGroup(db(STUDENT), 'marks')));
  });

  test('only the CR deletes marks (when deleting a session)', async () => {
    await assertFails(deleteDoc(markRef(db(OTHER), 'today', OTHER)));
    await assertSucceeds(deleteDoc(markRef(db(CR), 'today', OTHER)));
  });
});
