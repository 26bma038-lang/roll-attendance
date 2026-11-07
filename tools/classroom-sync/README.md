# Classroom sync

Copies the list of study material (notes, books, practical files, assignments, solutions, previous
year papers) from a senior's Google Classroom into Roll. The PDFs stay in Google Drive. Roll keeps
only an index: titles, subjects and links.

Node 20 or newer. No dependencies to install for the dry run.

The courses to copy are listed in [`sources.json`](sources.json). To add one (say, second year),
add an entry with its course id, credit line, Classroom link, years and branches. No code changes.

## One-time setup

You do this once, with a laptop and the NITH Google account that has joined the class.

### 1. Create a Google Cloud project and enable the Classroom API

1. Open <https://console.cloud.google.com/> and sign in. Any Google account works for the project
   itself; a personal Gmail is fine.
2. Top bar → project picker → **New project**. Name it `roll-classroom-sync` and create it. Make
   sure it is selected in the top bar afterwards.
3. Open <https://console.cloud.google.com/apis/library/classroom.googleapis.com> and press
   **Enable**.

### 2. OAuth consent screen: External, then publish it

1. Left menu → **APIs & Services** → **OAuth consent screen** (newer consoles call it
   **Google Auth Platform**; press **Get started** if asked).
2. App name `Roll classroom sync`, your email as support and developer contact.
3. Audience / user type: **External**.
4. Save. You don't need to add scopes or a logo here.
5. Go to **Audience** (or back to the consent screen page) and press **Publish app**, then
   **Confirm**. The status must read **In production**.

Stay in production. In "Testing" mode Google expires the sign-in after 7 days and the daily sync
would stop working. The app stays "unverified", which is fine: you are the only person who signs
in to it. Google shows an "unverified app" warning at sign-in; you click past it once.

### 3. Create a Desktop app OAuth client

1. **APIs & Services** → **Credentials** (or **Google Auth Platform** → **Clients**) →
   **Create credentials** → **OAuth client ID**.
2. Application type: **Desktop app**. Name: `classroom-sync`. Create.
3. Press **Download JSON**.
4. Make a folder `.secrets` inside `tools/classroom-sync/` and save the file there as
   `oauth-client.json`. (The name Google gives it, `client_secret_….json`, also works.)

`.secrets/` is in `.gitignore`. Never commit it: the repo is public.

### 4. Sign in once

```
cd tools/classroom-sync
npm run login
```

A browser tab opens. Sign in with the **NITH account that is in the class**. At the "Google hasn't
verified this app" warning choose **Advanced** → **Go to Roll classroom sync (unsafe)**, then
allow the three read-only Classroom permissions (see your classes, see topics, see class
materials). Tick every box. The script saves a refresh token to
`.secrets/token.json`.

If your account is not in the class yet, open the `classroomUrl` from `sources.json` and join it
first.

#### "Access blocked" / admin_policy_enforced

NITH's Google Workspace admin can block outside apps from reading Classroom. If that is switched
on, the browser shows **Access blocked** or **Error 400: admin_policy_enforced**, and the script
says so. It is a college setting, not something the script can work around. Ask the Workspace
admin to trust your OAuth client ID (from step 3), or sign in with another account that is in the
class.

## Dry run

```
npm run dry-run                      # index JSON on stdout, counts on stderr
npm run dry-run -- --out index.json  # index into a file, counts on screen
npm run dry-run -- --source fy-ec-ma # one source only
npm run dry-run -- --save-raw raw.json
```

Nothing is written anywhere except the files you name. The counts list every Classroom topic as a
Roll section, with the subjects inside. Topics Roll doesn't know go to `other` with their own name
and print a warning; no file is ever dropped.

`--save-raw` keeps the Classroom API responses so the parser can be re-run offline with
`--fixture raw.json`. Don't commit that file: it has the course's Drive links.

## How files are sorted

| Classroom topic | Roll section |
|---|---|
| SYLLABUS | `syllabus` |
| Academic Calendar | `calendar` |
| NOTES | `notes` |
| SUNIL SIR'S NOTES | `notes`, under "Sunil Sir's notes" |
| BOOKS | `books` |
| PRACTICAL FILES | `practicals` |
| WORKSHOP | `workshop` |
| Engineering Drawing | `drawing` |
| ASSIGNMENTS | `assignments` |
| ASSIGNMENT SOLUTIONS | `solutions` |
| QUESTION PAPERS | `pyq` (previous year papers) |
| anything else | `other`, with the topic's name |

- **Subject** from the post title: `MA-111(Eng. Mathematics-1)` → `MA-111`, "Eng. Mathematics-1".
  A bare code like `CS-102` keeps the code and borrows the name if another post spells it out.
  No code → no subject code, the post title as the name.
- **Exam** (papers only) from the file name: "mid" → `MID`, "end" → `END`.
- **Academic year** from the file name: `2024-25` or `2024-2025` → `2024-25`, else a lone `2023`.
- **Title**: the file name without `.pdf`, `MID SEM 2023(NEW)` → `MID SEM 2023 (NEW)`.
- Only **materials** posts are read (that is how all the study material is posted). Posts made as
  Classroom assignments are not: NITH accounts can't grant the coursework permission.
- Each Drive file and each link becomes one entry. Entry ids are a hash of course, post and file,
  so they don't change between syncs.

## Tests

```
npm test
```

## Files

| Path | What |
|---|---|
| `sources.json` | courses to copy and who sees them |
| `src/login.js` | `npm run login`: browser sign-in, saves the refresh token |
| `src/sync.js` | `npm run dry-run` |
| `src/classroom.js` | Classroom API calls, paging, error messages |
| `src/index.js` | raw API data → Roll index |
| `src/parse.js` | topic, subject, exam, year and title rules |
| `.secrets/` | OAuth client and token. Git-ignored. |
