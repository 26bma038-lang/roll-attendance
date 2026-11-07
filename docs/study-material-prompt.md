# Roll v2.1 — Study material from the seniors' Google Classroom

You are working on **Roll**, the NITH attendance app in this repo (Kotlin, Jetpack Compose, Hilt,
Firebase Auth + Firestore on the free Spark plan). Read these first:

- `attendance-app-prompt.md` §6 — the design rules. They apply to every new screen (warm paper
  background, deep green / brick tokens, IBM Plex Sans + Mono, sentence case, no emojis, no
  cards-in-cards, no exclamation marks, one accent per screen).
- `docs/nith-upgrade-prompt.md` — how v2 works (college email → batch/branch/class, admin, CRs).
- `README.md`, `firestore.rules`, `rules-tests/`.

Do the phases in order. Stop after each phase, run the tests, and show the result before moving on.

---

## Goal

Add a **Study** tab with notes, books, practical files, assignments, assignment solutions and
previous year papers, copied automatically from a senior's Google Classroom. The PDFs stay in
Google Drive. Roll stores only an index (titles, subjects, links), so storage costs nothing.

Permission from the senior has been given. The app must credit them.

---

## Fixed facts

**Source course:** "First year (EC, ECD, PH & MNC)"
- Classroom URL: `https://classroom.google.com/w/NjI2OTQwMTM5NDAy/t/all`
- Course id: `626940139402` (the URL segment is this id in base64)
- Credit line: `Material shared by <SENIOR NAME>` — ask the user for the exact name and wording.
- Applies to: **first-year** students (semesters 1–2) of branches **EC** and **MA** (MNC = Mathematics
  and Computing = Roll's `MA`). ECD and PH are not in `NithConfig.branches` yet; ignore them.

Keep sources in a config file (`tools/classroom-sync/sources.json`), so more courses (2nd year,
other branch groups) can be added later without code changes:

```json
[{ "id": "fy-ec-ma", "courseId": "626940139402", "title": "First year",
   "credit": "Material shared by <SENIOR NAME>",
   "classroomUrl": "https://classroom.google.com/w/NjI2OTQwMTM5NDAy/t/all",
   "years": [1], "branches": ["EC", "MA"] }]
```

**How the course is organised** (checked on 2026-11-07). Topics are material types. Each item
inside a topic is one subject, and Drive PDFs are attached to it:

| Classroom topic | Roll section | Notes |
|---|---|---|
| SYLLABUS | `syllabus` | course structure |
| Academic Calendar | `calendar` | |
| NOTES | `notes` | one item per subject, e.g. "MA-111(Eng. Mathematics-1)" |
| SUNIL SIR'S NOTES | `notes` | maths by chapter ("Fourier Series", "Matrix" …), no subject code. Group them under the label "Sunil Sir's notes" with `subjectCode = null` |
| BOOKS | `books` | |
| PRACTICAL FILES | `practicals` | lab codes CS-102, CY-102, EC-102, EE-102, HS-102, PH-102 |
| WORKSHOP | `workshop` | items CIVIL / ELECTRICAL / MECHANICAL / Assignment / Assignment Solutions |
| Engineering Drawing | `drawing` | Assignment / Assignment Solutions / Question Papers |
| ASSIGNMENTS | `assignments` | |
| ASSIGNMENT SOLUTIONS | `solutions` | |
| QUESTION PAPERS | `pyq` | e.g. MA-111 has PAPER 1.pdf … "MID SEM 2024-25.pdf", "END SEM 2025-26.pdf" |

Map topics by case-insensitive name. An unknown topic → section `other` with its raw name kept, and
the sync logs a warning; never drop files.

**Parsing (unit-test every rule):**
- Subject from the item title: `^([A-Z]{2,3})\s*-?\s*(\d{3})\s*\((.+)\)` → code `MA-111`, name
  `Eng. Mathematics-1`. No match → `subjectCode = null`, name = item title.
- Exam from the file name: `mid` → `MID`, `end` → `END`, otherwise `null`.
- Academic year: `20YY-YY` → `"2024-25"`, else a lone `20YY` → `"2023"`, else `null`.
- Display title: the file name without `.pdf`, cleaned up (`MID SEM 2023(NEW)` stays readable).

---

## Phase 1 — Sync script, dry run only

`tools/classroom-sync/` (Node 20, plain JS or TS, its own `package.json`, not part of Gradle).

- Use the Google Classroom API with **the user's own Google account** (OAuth installed-app flow,
  refresh token saved locally in a git-ignored file):
  scopes `classroom.courses.readonly`, `classroom.topics.readonly`,
  `classroom.courseworkmaterials.readonly`, `classroom.coursework.students.readonly`.
- Read `courses.topics.list`, `courses.courseWorkMaterials.list` and `courses.courseWork.list`
  (some files may be posted as coursework). Page through everything.
- For each attached `driveFile` produce one entry; also keep `link` attachments.
- `--dry-run` prints the resulting index as JSON and a per-section count. No Firestore yet.

Write `tools/classroom-sync/README.md` with the one-time Google Cloud setup, step by step for a
first-year student:
1. Create a Google Cloud project, enable the Classroom API.
2. OAuth consent screen: **External**, then **Publish app** ("In production", unverified).
   Stay in production: in "Testing" mode refresh tokens expire after 7 days and the daily sync
   would break. Unverified is fine because only the user signs in to it.
3. Create a "Desktop app" OAuth client, download the JSON.
4. Run `npm run login` once and sign in with the NITH account that is in the class.

If NITH's Workspace blocks third-party access to Classroom, the login will fail with an
"admin_policy_enforced" / "access blocked" error. Detect it and print a clear message.

**Stop here** and show the user the dry-run output so they can check the sorting.

---

## Phase 2 — Write to Firestore + rules

**Data model — one document per source** (keeps reads tiny on the Spark plan: one read per student,
then cached). Firestore's 1 MB doc limit fits roughly 3,000 entries; if a source gets near 800 KB,
split it into `materialSources/{id}/parts/{n}`.

```
materialSources/{sourceId}
  title, credit, classroomUrl, years: [1], branches: ["EC","MA"], syncedAt
  items: [ { id, section, sectionLabel, subjectCode?, subjectName, title,
             exam?, academicYear?, url, driveFileId?, mimeType?, updatedAt } ]
```

- `id` = stable hash of courseId + item id + driveFileId, so re-syncs don't reshuffle anything.
- The script writes with the **Firebase Admin SDK** (service-account key, git-ignored, later a GitHub
  secret). It only writes when the index changed, to save writes.
- Files removed from Classroom disappear from the index on the next sync.

**Rules:** `materialSources` — `allow read: if validUser();` and **no client writes** (the Admin SDK
bypasses rules). Add rules tests: a student can read, a student and the admin cannot write, a
signed-out user cannot read.

The user deploys the rules themselves:
`firebase deploy --only firestore:rules --project roll-attendance`, run from the repo root.

---

## Phase 3 — Study tab in the app

- New bottom-nav destination **Study**, between the existing tabs in whatever order fits §6.
- `data/materials/MaterialRepository` observes the sources that apply to the signed-in student:
  their branch is in `branches`, and their current year (from batch + today, using the existing
  semester logic in `NithConfig`) is in `years`. Use the Firestore cache so it works offline after
  the first load.
- **Study home:** a search field, then a list of subjects (code in IBM Plex Mono + name), with
  "Sunil Sir's notes", Syllabus, Academic calendar, Workshop and Engineering drawing as their own
  rows. The credit line sits at the top, quiet, with an "Open Classroom" text button
  (ACTION_VIEW on `classroomUrl`). A student who isn't in the class yet can join from there.
- **Subject screen:** sections in this order — Previous year papers, Notes, Books, Assignments,
  Solutions, Practical files. Show only sections that have items. Papers are sorted newest academic
  year first, then End before Mid, then untagged; show "End sem · 2024-25" style labels.
- Tapping an item opens `url` with ACTION_VIEW (the Drive app if installed, otherwise the browser).
- Search matches subject code, subject name and file title.
- **Empty and error states, written in the §6 voice:** no source for this student's year/branch
  ("Study material for your year isn't here yet."), offline with no cache, and loading.
- ViewModel tests with a fake repository: filtering by branch/year, section order, paper sorting,
  search.

---

## Phase 4 — Daily sync on GitHub Actions

`.github/workflows/classroom-sync.yml`: runs daily at 06:00 IST (`cron: "30 0 * * *"`) and on
manual dispatch. Secrets: `GOOGLE_OAUTH_CLIENT`, `GOOGLE_REFRESH_TOKEN`, `FIREBASE_SERVICE_ACCOUNT`.
The job fails loudly if the refresh token stops working, so the user gets GitHub's failure email.
Document how to add the secrets in the script README.

The repo is public. Never commit any of these files: OAuth client JSON, token file, service-account
key. Add them to `.gitignore` before Phase 1 creates them.

---

## Done when

- The dry run lists every topic in the course with sensible sections and subjects.
- A first-year EC or MA student sees the Study tab filled; another year/branch sees the empty state.
- Tapping a paper opens it in Drive with the student's NITH account.
- All unit tests and rules tests pass; version bumped to 2.1.0.
