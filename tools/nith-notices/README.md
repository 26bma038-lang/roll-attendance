# NITH notices

Copies **academic** notices from nith.ac.in into Roll, so they show up in the app's College
notices screen (Department → College notices) within about 3 hours of being posted. Only titles
and links are stored; the PDFs stay on the college website.

Node 22 or newer.

## Which pages

[`config.json`](config.json):

| Page | Category in Roll |
|---|---|
| <https://nith.ac.in/academic-notices> | Academic |
| <https://nith.ac.in/examination-schedules> | Exams |
| <https://nith.ac.in/student-related-notices> | Students, only rows matching `academicKeywords` |

- `academicKeywords` filters the student page: whole words, any case, plurals allowed ("fee"
  matches "Fees" but not "Feedback"). Recruitment, tenders, clubs and alumni notices stay out.
- `examKeywords`: a date sheet or examination notice on any page is filed under **Exams**. The
  exam page itself is often empty; the date sheets are posted under academic notices.
- `keep`: how many notices Roll keeps, newest first (150).

## Dates

The site shows no dates on two of the pages, so each notice gets the best date there is:

1. the student page's own "Uploaded Date" column;
2. the upload timestamp at the end of older file names (`…/uploads/topics/name17244223408638.pdf`
   → 23 Aug 2024);
3. the month folder (`/uploads/files/oct26/` → October 2026);
4. otherwise the day the sync first saw it. On the very first sync, undated notices take the date of
   the next dated row below them instead, so old notices don't all look new.

The month label shown in the app ("October 2026") only comes from 1–3, never from a guess.

## Run it

```
npm install
npm run dry-run          # fetch the 3 pages, print what would be stored, write nothing
npm run sync             # also write collegeFeed/nith (only when something changed)
npm test
```

`npm run sync` needs the Firebase service-account key: the one in
`../classroom-sync/.secrets/service-account.json` is used automatically, or put a copy in
`.secrets/service-account.json` here (git-ignored). On GitHub Actions it comes from the
`FIREBASE_SERVICE_ACCOUNT` secret.

`--fixtures test/fixtures` reads the saved pages instead of the website (tests, emulator checks).

## When a page breaks

If a page doesn't load, times out (20 s), or has no notice table any more, its notices from the
last good run are kept, nothing is deleted, and the run fails with a clear message, so GitHub
emails you. A page that had notices and suddenly shows none is treated the same way, with a
warning instead of a failure.

## What Roll stores

```
collegeFeed/nith
  syncedAt, contentHash
  items: [ { id, title, url, category, page, sortDate, monthLabel?, firstSeenAt } ]   newest first, 150
  seen:  { id: sortDate }    every notice on the pages, so dates stay put
```

`id` is a hash of the URL; the same URL listed twice (the site does that) is one notice. One
document: a student's phone reads it once per change and keeps it offline.

## GitHub Actions

[`.github/workflows/nith-notices.yml`](../../.github/workflows/nith-notices.yml) runs every 3 hours
from 08:00 to 20:00 IST, and on **Run workflow**. It needs only the `FIREBASE_SERVICE_ACCOUNT`
secret, the same one the Classroom sync uses (see `tools/classroom-sync/README.md`).

## Notifications

Roll has no push notifications (no Firebase Cloud Messaging), so new notices are not pushed. They
show in the app: a "New" mark on each, "2 new from nith.ac.in" on Department, and a dot on the
Department tab, until the student opens College notices.
