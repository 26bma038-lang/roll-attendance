# Roll

The attendance app for **NIT Hamirpur** students. Sign in with your `@nith.ac.in` Google account and
Roll puts you in your batch and branch's class automatically. CRs, appointed by the admin, post each
day's classes, assignments and notices. Students mark themselves present or absent and see how many
classes they can still miss. It's a self-tracking tool for students, not an official record.

The original brief is `attendance-app-prompt.md`; the v2 NITH upgrade brief is `docs/nith-upgrade-prompt.md`.

## Download

[**Roll-1.0.0.apk**](download/Roll-1.0.0.apk) is the v1 build (no Firebase connected). For v2, build
your own APK after the Firebase setup below. To install an APK, allow "Install unknown apps" for your
browser or file manager when Android asks.

Kotlin · Jetpack Compose (Material 3, heavily customised) · MVVM + StateFlow · Hilt · Navigation
Compose (type-safe) · Firebase Auth + Cloud Firestore (Spark plan only) · DataStore · WorkManager · Glance.

---

## 1. Roles

| Who | How they get it | What they can do |
| --- | --- | --- |
| **Student** | Any `<YY>b<branch><NNN>@nith.ac.in` account of a supported branch | Mark own attendance (today and 2 days back), tick deadlines done, read their class's classes, assignments and notices, department info |
| **CR** (up to 3 per class) | Appointed by the admin | Everything a student can, plus: subjects, timetable, post / edit / cancel classes, assignments, class notices, class settings (name, semester, required %) |
| **Admin** | Email listed in `NithConfig.adminEmails` and `isAdmin()` in `firestore.rules` | Appoint and remove CRs for every class, edit department info, post college notices, post assignments and notices in any class |

Sign-in rules: other domains are signed straight back out ("Use your @nith.ac.in college account."),
and college accounts outside the supported pattern or branches see "Roll isn't available for your
branch yet." Roll number, batch, branch and class come from the email and can never be edited. Every
rule checks `email_verified`.

## 2. Supported branches, and how to add one

| Code | Branch |
| --- | --- |
| MA | Mathematics and Computing |
| CS | Computer Science and Engineering |
| CH | Chemical Engineering |
| CE | Civil Engineering |
| ME | Mechanical Engineering |
| EC | Electronics and Communication Engineering |

To add a branch (say Electrical, code `EE`):

1. Add `"EE" to "Electrical Engineering"` to `branches` in
   `app/src/main/java/app/roll/domain/college/NithConfig.kt`.
2. Add `'ee'` to `supportedBranch()` in `firestore.rules`.
3. Run the rules tests, deploy the rules (section 4), and release a new APK.

To add an admin, add the email to `adminEmails` in `NithConfig.kt` **and** to `isAdmin()` in
`firestore.rules`, then deploy.

Class ids are `nith-<YY>-<branch>` (e.g. `nith-26-ma`). Sections can later be appended
(`nith-26-ma-a`) without changing existing ids.

## 3. Data model

See `firestore.rules` for exactly who can read and write what.

```
users/{uid}                    name, rollNo, email, batch, branch, classId, createdAt   (own only; admin reads)
  assignmentStatus/{aid}       done, doneAt                                             (private to the student)
departments/{code}             code, name, about, hod, office, email, phone, links[], updatedAt   (admin writes)
notices/{nid}                  college notice: text, pinned, createdAt, expiresAt, createdByName, status, updatedAt
classes/{nith-26-ma}           name, semester, batch, branch, crUids[], crNames[], requiredPercent,
                               timezone, memberCount, createdAt
  members/{uid}                name, rollNo, joinedAt          (read by CRs and the admin only)
  subjects/{id}                name, shortName, code, faculty, colorIndex, archived
  timetable/{id}               dayOfWeek, startMinutes, endMinutes, subjectId, type, room
  sessions/{id}                subjectId, date, start/end, type, status, note, room, weight, createdBy, …, updatedAt
    marks/{uid}                status, markedAt, uid, classId, sessionId
  assignments/{aid}            title, subjectId, type, description, link, dueAt, createdBy, createdByName,
                               createdAt, updatedAt, status
  notices/{nid}                class notice, same shape as college notices
```

- **Joining.** The first student of a batch + branch creates its class (no CRs, 75%, Asia/Kolkata,
  semester from the batch and today: July–December odd, January–June even). Later students add their
  member doc and bump `memberCount` in the same batch; the rules check both together.
- **Privacy.** Students never see the member list (only the count and CR names on the class doc),
  other students' marks, or anyone's done ticks. No class averages are shown anywhere.
- **Percentages are computed on the phone** (`domain/attendance/AttendanceCalculator.kt`). Unmarked
  past classes count as absent, and the app says so ("includes 2 unmarked").

### Staying inside Spark's 50k reads a day

A plain listener re-reads every matching document on each cold start. That's fine for small
collections and too expensive for growing ones, so:

| Collection | How it syncs | Why |
| --- | --- | --- |
| `sessions`, own `marks`, `assignments` | **Delta sync** (`data/firebase/DeltaSync.kt`): everything from the offline cache, then only documents whose `updatedAt` / `markedAt` is newer than the newest cached one. Deletes are tombstones (`deleted` / `cleared`). | Hundreds of docs per class per semester |
| class and college `notices` | Plain listener on `status == "active"`; deleted ones drop out of the query | A handful at a time |
| `assignmentStatus`, `subjects`, `timetable`, `departments`, class doc | Plain listener | Tens of small docs |
| `members` | Plain listener, CRs and the admin only | One screen, few users |

### On-device only (no server)

- **Deadline reminders** 24 h and 2 h before each deadline that isn't done (WorkManager jobs,
  re-planned whenever assignments or done ticks change; switch in Settings).
- **Mark from a notification**: when each class ends, "DS · 10:00–10:50 — mark attendance" with
  Present and Absent buttons. Planned from the day's posted classes, or the timetable when nothing is
  posted, in the app and by a 6:00 daily job. Inexact timing; no exact-alarm permission.
- **Daily reminder** at a chosen time if today has unmarked classes.
- **Home-screen widget** (Glance): today's classes with P / A, and the next deadline.

## 4. Firebase setup and rules deploy

The project already points at the `roll-attendance` Firebase project (`app/google-services.json`,
git-ignored). For a fresh project: create it on the Spark plan, add an Android app `app.roll` with the
debug SHA-1 `94:63:1E:6E:A6:9A:10:47:47:8D:5D:AD:B8:36:8B:37:96:CC:34:D3` and release SHA-1
`5A:B9:6D:DC:7E:1D:BB:CC:BF:DD:F1:22:85:86:C3:13:27:C0:27:1E`, enable **Google** sign-in under
Authentication, create Firestore (asia-south1, production mode), and download `google-services.json`
into `app/`.

**Deploy rules and indexes** after any change to `firestore.rules` or `firestore.indexes.json`:

```powershell
cd rules-tests
npm install                     # first time only
npx firebase login              # first time only
npx firebase deploy --only firestore --project roll-attendance --config ../firebase.json
```

## 5. Build, test, release

Needs JDK 17+ (system Java 1.8 fails) and the Android SDK (platform 37, build-tools 36):

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Android\jdk\jdk-21.0.13+9"
.\gradlew installDebug          # phone connected with USB debugging on
```

| What | Command | Output |
| --- | --- | --- |
| Debug APK | `.\gradlew assembleDebug` | `app\build\outputs\apk\debug\app-debug.apk` |
| Signed release APK | `.\gradlew assembleRelease` | `app\build\outputs\apk\release\app-release.apk` |
| Unit tests (+ screenshots) | `.\gradlew testDebugUnitTest` | `app\build\reports\tests\` |
| Record screenshots | `.\gradlew recordRoborazziDebug` | `app\build\outputs\roborazzi\` |
| Lint | `.\gradlew lintDebug` | `app\build\reports\lint-results-debug.html` |
| Security rules tests | `cd rules-tests; npm test` | starts the Firestore emulator |

Release builds are minified with R8 (`app/proguard-rules.pro`) and signed with
`keystore/roll-release.jks` (passwords in `keystore.properties`). Both are git-ignored: **back them
up**, or you can never publish an update to the same app. `app/src/main/res/raw/keep.xml` stops the
resource shrinker from removing `default_web_client_id`, which Google sign-in looks up by name.

## 6. Code layout

```
app/src/main/java/app/roll/
  core/designsystem/   theme tokens, components with light/dark previews, icons
  core/common, util/   dispatchers, time source, snackbar messages, formatting, links
  data/                Firebase repositories, mappers, delta sync, app session, preferences
  domain/              pure Kotlin: NithConfig, attendance maths, deadlines, leave planner,
                       class-end planning, timetable posting
  feature/             auth, onboarding, today, deadlines, subjects (incl. leave planner),
                       history, department, notices, manage, admin, settings, classend, widget
  navigation/          type-safe routes and the main tab scaffold
app/src/debug/         design-system gallery (debug only, used by screenshot tests)
rules-tests/           Firestore rules tests (Node, @firebase/rules-unit-testing, emulator)
```

## Licences

The code is under the [MIT License](LICENSE). IBM Plex Sans and IBM Plex Mono are bundled under the
SIL Open Font License (`licenses/IBM-Plex-OFL.txt`). Icons are Material Symbols (Apache 2.0).
