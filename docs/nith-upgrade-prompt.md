# Roll v2 — NITH college accounts, admin, departments, assignments

You are working on **Roll**, an Android class-attendance app in this repo (Kotlin, Jetpack Compose,
Hilt, Firebase Auth + Firestore on the free Spark plan). Read these first, they are the source of
truth for style and architecture:

- `attendance-app-prompt.md` — the original brief. **Its design section (§6) still applies to every
  new screen**: warm paper background, deep green / brick tokens, IBM Plex Sans + Mono, tabular
  figures, sentence case, no gradients, no emojis, no cards-in-cards, no progress rings, no
  exclamation marks, one accent per screen.
- `README.md` — how the data model, delta sync, offline marking and tests work today.
- `firestore.rules` + `rules-tests/` — the current security rules and their tests.

The app is already built and working (all 8 original phases). Google sign-in works. Nobody is using
it for real yet, so **you may change the data model freely; no migration of old data is needed.**

---

## Goal

Make Roll the attendance app for **NIT Hamirpur** students:

1. Sign-in only with a college Google account (`@nith.ac.in`). The email decides the student's
   batch, branch and roll number, and they land in their branch's class automatically — no join codes.
2. One **admin** account appoints CRs. Only CRs post classes, assignments and notices.
3. Each branch gets a **department dashboard**.
4. An **assignments and deadlines** section that only CRs can post to.
5. Extra features that make it the easiest attendance app for NITH students (listed in Phase 6).

Nothing in this list is optional. Do every phase.

---

## Fixed facts (put these in ONE config file, `domain/college/NithConfig.kt`, mirrored in the rules)

**College domain:** `nith.ac.in`

**Email format:** `<YY><program><branch><NNN>@nith.ac.in`, case-insensitive. Example
`26bma038@nith.ac.in` → batch **2026**, program **B** (Bachelor's / B.Tech), branch **MA**,
roll number **26BMA038** (display in uppercase, IBM Plex Mono).

Regex (lowercase the email first):
`^([0-9]{2})b(ma|cs|ch|ce|me|ec)([0-9]{3})@nith\.ac\.in$`

**Supported branches for now (Bachelor's only):**

| Code | Branch name |
|---|---|
| MA | Mathematics and Computing |
| CS | Computer Science and Engineering |
| CH | Chemical Engineering |
| CE | Civil Engineering |
| ME | Mechanical Engineering |
| EC | Electronics and Communication Engineering |

Not supported yet (show "Roll isn't available for your branch yet"): every other NITH branch
(Electrical, Materials Science, Engineering Physics, M.Tech, etc.).

Keep names in this one table so they can be corrected or extended in one place. Adding a branch
later must only mean adding a row here and the matching code in `firestore.rules`.

**Admin account:** `26bma038@nith.ac.in` (also a normal MA student). Hard-code the admin email list
in both `NithConfig.kt` and `firestore.rules`. Check `request.auth.token.email_verified == true`
everywhere an email is trusted.

**Sections:** ignore for now. One class per batch + branch. Design the class id so a section can be
added later without breaking anything.

**Class id:** `nith-<YY>-<branch>` lowercase, e.g. `nith-26-ma`.

---

## Constraints (do not break)

- **Spark plan only.** No Cloud Functions, no Cloud Storage (it now needs Blaze), no paid APIs, no
  server-sent push (FCM needs a server). Everything must work with Firestore + on-device work.
- Keep the 50k reads/day budget in mind: new collections (assignments, notices) must use the same
  delta-sync + tombstone approach as sessions/marks (`data/firebase/DeltaSync.kt`), or be small enough
  that it doesn't matter — explain which in the README.
- Offline-first stays: writes are local first, errors surface in a snackbar.
- **Do not touch `app/google-services.json`, `keystore/`, `keystore.properties` or
  `app/src/main/res/raw/keep.xml`.**
- **Do not deploy Firestore rules or indexes.** I deploy them myself. At the end, tell me the exact
  command (`cd rules-tests; npx firebase deploy --only firestore --project roll-attendance --config ../firebase.json`).
- Build needs JDK 21: in PowerShell `$env:JAVA_HOME = "$env:LOCALAPPDATA\Android\jdk\jdk-21.0.13+9"`
  before any `.\gradlew` command. System Java is 1.8 and will fail.
- Use the plugins/skills available in this session:
  - **context7** for current docs of every library API you touch (Credential Manager / googleid,
    Firebase Auth, Firestore, Glance, WorkManager, AlarmManager, Navigation Compose). Don't guess APIs.
  - **frontend-design** and/or **impeccable** skill for a design review of every new screen.
  - **Roborazzi** screenshot tests (`.\gradlew recordRoborazziDebug`) for every new screen in light
    and dark; look at the images and fix anything that breaks the design rules before moving on.
- Commit after each phase with a clear message (git repo exists). Don't push.

---

## Phase 1 — College sign-in and automatic class

1. **Sign-in screen:** "Continue with your NITH account". Ask Credential Manager to show only
   `nith.ac.in` accounts if the googleid library supports a hosted-domain filter (check context7);
   otherwise filter after sign-in.
2. After sign-in, parse the email with `NithConfig`:
   - **Not `@nith.ac.in`:** sign out immediately, show "Use your @nith.ac.in college account." with a
     button to try again.
   - **`@nith.ac.in` but not a supported format/branch** (other branches, M.Tech, faculty, etc.):
     sign out, show "Roll isn't available for your branch yet." plus the email they used.
   - **Valid:** continue.
3. **Profile setup** becomes one confirmation screen: name (prefilled from Google, editable), roll
   number shown read-only (`26BMA038`), branch and batch shown read-only ("Mathematics and Computing ·
   2026 batch"). One button: "Continue".
4. **Joining is automatic:** the app creates/joins `classes/nith-<YY>-<branch>`. If the class doc
   doesn't exist yet, the first student creates it with no CRs (`crUids: []`) and default settings
   (name e.g. "MA 2026", required 75%, timezone Asia/Kolkata, semester computed from batch + today:
   Jul–Dec = odd semester, Jan–Jun = even; CR can edit).
5. **Remove join codes completely**: the join/create screen, join-code UI, `joinCodes` collection,
   share-code flow, `roll://join` deep link, regenerate code, and the matching rules and tests.
   The "create a class" flow is gone too — classes come from emails.
6. A class with no CR yet shows a calm empty state on Today: "No CR yet for MA 2026. Classes appear
   here once the admin appoints one."

**Rules for Phase 1** (and tests in `rules-tests/`):
- Helper `collegeEmail()`, `parsedBatch()`, `parsedBranch()`, `myClassId()` derived from
  `request.auth.token.email` (lowercased with `.lower()`), requiring `email_verified`.
- A user can only create/read their own `users/{uid}`, and its `rollNo`, `batch`, `branch`,
  `classId` must match what the email says (no editing these later).
- A user can create `classes/{classId}` only if `classId == myClassId()` and `crUids == []`.
- A user can only create their own `members/{uid}` in `myClassId()`.
- Non-`nith.ac.in` or unsupported emails can read/write nothing.

## Phase 2 — Admin and CRs

1. **Data model:** `classes/{id}.crUid` → `crUids` (list, max 3). Update every place that used
   `crUid`, including `isCR()` in rules. Transfer-CR flow is removed; CRs are only changed by admin.
2. **Admin panel** (visible only to admin emails; entry in Settings and a small top-bar icon):
   - **Classes:** list of every class (`nith-26-ma`, …) with member count and current CRs.
   - **Appoint CR:** open a class → member list sorted by roll number → "Make CR" / "Remove CR",
     with a confirm dialog naming the person and class. Also a search box by roll number.
   - **Departments:** edit department info (Phase 3).
   - **College notice:** post a notice shown to every student (Phase 5).
3. **Rules:** only admin can change `crUids`; admin can read all classes, members and users (to
   appoint CRs); a CR can edit their class's settings (name, semester, required %) but not `crUids`.
   Students can't read the member list (only counts and CR names, which live on the class doc —
   store `crNames` alongside `crUids`).
4. CRs keep everything the current Manage tab does (timetable, subjects, post today's classes,
   add/cancel/delete a class, session detail with Present / Absent / Not marked). **Students can never
   create or edit classes, subjects, timetable, assignments or notices** — enforce in rules, hide in UI.

## Phase 3 — Department dashboard

A **Department** tab for every student, showing their own branch:

- Header: branch name, "2026 batch", semester, class CR names.
- **This week's timetable** (Mon–Sat from the class timetable), today highlighted.
- **Subjects** with faculty names (from the class subjects the CR entered).
- **Department info** from `departments/{branchCode}` (admin-editable): about, HOD, department office
  location, office email/phone, and a list of useful links (title + URL, opened in the browser).
- **Class stats** (no personal data): number of students, classes held this semester, the class's
  average attendance is NOT shown (privacy) — only the student's own numbers anywhere.

**Do not invent facts.** Seed `departments/*` with only the code and branch name. Every other field
shows "Not added yet" until the admin fills it in. No made-up HOD names, phone numbers or links.

Rules: `departments/*` readable by any valid NITH user, writable by admin only.

## Phase 4 — Assignments and deadlines

1. **Data:** `classes/{id}/assignments/{aid}`: `title`, `subjectId`, `type`
   (assignment · lab report · quiz · mid-sem · end-sem · presentation · other), `description`
   (optional, plain text), `link` (optional URL, e.g. Google Classroom/Drive — no file uploads),
   `dueAt` (timestamp, IST), `createdBy`, `createdByName`, `createdAt`, `updatedAt`,
   `status` (`active` | `deleted` tombstone, for delta sync).
2. **Only CRs** create/edit/delete (rules + UI). Admin can too.
3. **Personal done state:** `users/{uid}/assignmentStatus/{aid}` `{ done, doneAt }`, private to that
   user. Students tick "Done"; it never shows to anyone else.
4. **Deadlines tab:**
   - Sections: Overdue (not done, past due) · Due today · This week · Later · Done.
   - Each row: subject colour bar, title, type, due date/time in tabular figures, relative time
     ("in 2 days", "3 h left", "overdue by 1 day" in the warning colour), done checkbox.
   - Filter by subject. Tap → detail with description and link.
   - CR sees "+ Add" (bottom sheet: subject, type, title, due date + time pickers, description, link)
     and edit/delete on the detail screen.
5. **Today screen** gets a slim "Due soon" strip (next 48 h, not done) above today's classes.
6. **Local reminders** (no server): when assignments sync, schedule notifications 24 h and 2 h before
   each not-done deadline; cancel when marked done/deleted/edited. Respect a Settings toggle.

## Phase 5 — Notices

- **Class notices** (CR posts): `classes/{id}/notices/{nid}`: `text` (max 500 chars), `pinned`,
  `createdAt`, `expiresAt` (optional), `createdByName`, `status` tombstone.
- **College notices** (admin posts): top-level `notices/{nid}`, same shape, for all students.
- Pinned/unexpired notices show at the top of Today as a quiet bordered block (not a card with
  shadow). A full list lives in the Department tab.

## Phase 6 — Features that make it the best app for NITH students

1. **Mark from the notification.** At each class's end time, a local notification
   "DS · 10:00–10:50 — mark attendance" with **Present** and **Absent** action buttons that write the
   mark directly (offline-safe, through the existing mark repository). Schedule from the day's
   posted sessions; fall back to the timetable if not posted. No exact-alarm permission — inexact is
   fine. Ask for `POST_NOTIFICATIONS` (Android 13+) at a sensible moment, with a short explanation.
2. **Home-screen widget** (Jetpack Glance): today's classes with P/A tap targets, and the next
   deadline. Match the app's tokens in light and dark.
3. **Leave planner:** in Subjects, "Plan leave" → pick dates on a calendar → shows, per subject, the
   classes you'd miss (predicted from the timetable) and the projected percentage, warning colour if
   below the requirement. Pure Kotlin in `domain/`, unit-tested.
4. **Bottom navigation:** Today · Deadlines · Subjects · Department, plus Manage for CRs. History
   moves into Subjects (calendar icon in the top bar) — keep the existing History screen.
5. Keep everything that already exists (Subjects stats, "can miss N more", day edits within the
   2-day window, theme, daily reminder, sign out, delete account, leave class → now only "sign out",
   since the class is fixed by email).

## Phase 7 — Finish

1. Update `firestore.rules` and `firestore.indexes.json`; extend `rules-tests/` to cover: wrong
   domain, unverified email, unsupported branch, joining another branch's class, editing own
   roll/branch, student posting a class/assignment/notice, CR changing `crUids`, admin appointing a
   CR, reading another student's assignment status. Run `cd rules-tests; npm test` — all must pass.
2. Unit tests: email parser (valid, uppercase, other domain, unsupported branch, bad formats), class
   id, semester calculation, deadline grouping, reminder scheduling, leave planner.
3. Roborazzi screenshots of every new/changed screen in light and dark; review them with the design
   skill and fix issues.
4. `.\gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` — all green.
5. Confirm the release APK still contains `default_web_client_id`:
   `& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt2.exe" dump resources app\build\outputs\apk\release\app-release.apk | Select-String default_web_client_id`
6. Bump version to **2.0.0** (versionCode 2).
7. Update `README.md`: new data model, roles, admin, how to add a branch, rules deploy command.
8. **Report to me**, in plain language:
   - what was built, phase by phase;
   - anything you couldn't do and why;
   - the rules deploy command for me to run;
   - the release APK path;
   - a short test checklist for my phone (admin account `26bma038@nith.ac.in` + a second NITH account).
