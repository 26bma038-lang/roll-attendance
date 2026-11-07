# Timetable import

Turns the college's timetable PDF into Roll subjects and weekly timetable slots, without typing
them in. Two commands, with a check by hand in between:

1. `npm run extract -- timetable.pdf` reads the PDF with **Gemini** (Google's free tier) and writes
   `timetable-draft.json`, plus a readable table per class. Nothing goes to Roll yet.
2. You compare the tables with the PDF and fix `timetable-draft.json` by hand where needed.
3. `npm run apply -- timetable-draft.json` shows what it will write per class, asks `y/N`, and
   writes the subjects and slots in the same shape the app's Manage screens use.

This is a tool for the admin's laptop, not part of the app: everything stays on the free Spark
plan and the free Gemini tier. No billing account or card is needed anywhere.

Node 22 or newer.

## One-time setup

```
cd tools/timetable-import
npm install
```

**Gemini API key (free)**

1. Open <https://aistudio.google.com/apikey> and sign in with any Google account.
2. Press **Create API key** (let it create a project if it asks). Don't add billing.
3. Make a folder `.secrets` here and save the key, alone on one line, as `.secrets/gemini-key`.
   (Or set `GEMINI_API_KEY` in the terminal instead.)

`.secrets/`, `timetable-draft*.json` and PDFs in this folder are git-ignored. Never commit them.

On the free tier Google may use what you send to improve its products. A class timetable is
public information; don't send PDFs with students' personal details.

**Firebase key** (for `apply` only): the same service-account key as the Classroom sync. If
`../classroom-sync/.secrets/service-account.json` exists it is used; otherwise put a copy in
`.secrets/service-account.json`.

## Try it with the sample

`test/fixtures/sample-timetable.pdf` is a made-up NITH-style timetable (MA, EC and EE first years,
odd semester 2026–27). It is not real; it's there to try the tool end to end:

```
npm run extract -- test/fixtures/sample-timetable.pdf --out timetable-draft.json
```

## With the real timetable

```
npm run extract -- "C:\path\to\timetable.pdf"
```

That's the only step that changes. Then check the tables it prints against the PDF, fix
`timetable-draft.json` if needed, and run `npm run apply -- timetable-draft.json`.

Options for `extract`: `--out <file>` (default `timetable-draft.json`), `--model <name>` (default
`gemini-flash-latest`, or set `GEMINI_MODEL`).

## The draft

```json
{
  "classes": [{
    "batch": 2026, "branch": "MA", "section": null, "label": "B.Tech. 1st Year (MNC)",
    "subjects": [{ "code": "MA-111", "name": "Engineering Mathematics-1", "type": "lecture" }],
    "slots": [{ "day": "Mon", "start": "09:00", "end": "09:50", "subject": "MA-111",
                "type": "lecture", "room": "LH-101", "group": null }]
  }],
  "notes": ["things the model couldn't place"]
}
```

- `batch` is the year of admission (first-years of 2026–27 → 2026); `branch` is Roll's code (MA,
  CS, CH, CE, ME, EC). One PDF may hold many classes; all of them are extracted.
- Times are 24-hour. A lab over several periods is one slot.
- `group` (G1, G2 …) is for a class that splits into lab groups at the same time. Grouped slots may
  overlap; the group is added to the room ("CC Lab 2 · G1").

`extract` and `apply` both check the draft and refuse to apply while there are errors:

- unknown day, a time that isn't a time, a slot that ends before it starts;
- two slots of one class overlapping on the same day (unless they're different lab groups);
- a slot whose subject code isn't in that class's subject list;
- a subject listed twice, a code over 10 characters, a type other than lecture / lab / tutorial.

Warnings, which `apply` skips over: branches Roll doesn't have yet (EE, PH …), sections (Roll has
one class per batch and branch), subjects with no slots.

## What `apply` writes

For each class in the draft whose Roll class exists (`nith-26-ma`; a class is created when its
first student signs in):

- **Subjects**: a subject the CR already has with the same code is reused as it is (name, faculty
  and colour untouched). New ones get the code as their short name.
- **Slots**: written with fixed ids, so applying the same draft twice writes nothing the second
  time, and a changed room updates the slot instead of adding another. A slot the CR already
  entered by hand at the same time for the same subject isn't doubled.
- **Nothing is deleted** unless you pass `--replace`, which deletes every timetable slot of that
  class that isn't in the draft, including ones a CR added. The summary says how many first.

`--dry-run` shows the summary and writes nothing; `--yes` skips the question (scripts).

To try `apply` without touching the real database, run it on the emulator from `rules-tests/`:
`npx firebase emulators:exec --only firestore --project demo-roll --config ../firebase.json "node ../tools/timetable-import/src/apply.js ../tools/timetable-import/timetable-draft.json"`.

## Tests

```
npm test
```

They cover the draft checks (overlaps, unknown days, end before start, missing subject codes …),
the Gemini request and answer handling (with a saved answer, no network), and what `apply` plans
to write. `npm run sample-pdf` rebuilds the sample PDF from `test/fixtures/sample-draft.json`.
