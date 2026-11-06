# Roll

A class attendance tracker for Android. One CR (class representative) per class posts each day's
classes; students mark themselves present or absent and see how many classes they can miss. It's a
self-tracking tool for students, not an official record. The full brief is in `attendance-app-prompt.md`.

## Download

[**Roll-1.0.0.apk**](download/Roll-1.0.0.apk) (Android 8.0+, 4 MB). Also attached to the
[latest release](../../releases/latest).

This build has no Firebase project connected yet, so it opens on a "Firebase isn't set up" screen.
To get a working app, follow [Firebase setup](#1-firebase-setup-once-about-10-minutes) below and
build your own APK. To install an APK, allow "Install unknown apps" for your browser or file manager
when Android asks.

Kotlin · Jetpack Compose (Material 3, heavily customised) · MVVM + StateFlow · Hilt · Navigation
Compose (type-safe) · Firebase Auth + Cloud Firestore (Spark plan only) · DataStore · WorkManager.

---

## 1. Firebase setup (once, about 10 minutes)

The app builds without Firebase, but shows a "Firebase isn't set up" screen until you do this.

1. **Create a project.** Go to <https://console.firebase.google.com> → **Add project** → name it
   (e.g. `roll-attendance`) → you can turn Google Analytics off → **Create**. Stay on the free
   **Spark** plan; nothing here needs Blaze.
2. **Add the Android app.** On the project overview, click the Android icon:
   - Package name: `app.roll`
   - App nickname: `Roll`
   - Debug signing certificate SHA-1: `94:63:1E:6E:A6:9A:10:47:47:8D:5D:AD:B8:36:8B:37:96:CC:34:D3`
   - Click **Register app**, then **Download google-services.json**.
3. **Put `google-services.json` in the `app/` folder** (next to `app/build.gradle.kts`). It is
   git-ignored.
4. **Add the release SHA-1 too** (so Google sign-in works in the release APK): Project settings
   (gear icon) → **Your apps** → `app.roll` → **Add fingerprint** →
   `5A:B9:6D:DC:7E:1D:BB:CC:BF:DD:F1:22:85:86:C3:13:27:C0:27:1E`.
   If you add fingerprints after step 2, download `google-services.json` again and replace the old one.
5. **Turn on Google sign-in.** Build → **Authentication** → **Get started** → **Sign-in method** →
   **Google** → **Enable** → pick a support email → **Save**.
6. **Create the database.** Build → **Firestore Database** → **Create database** → location
   **asia-south1 (Mumbai)** → start in **production mode** → **Create**.
7. **Deploy the security rules and indexes** (from this repo, needs Node):
   ```powershell
   cd rules-tests
   npm install                 # first time only
   npx firebase login
   npx firebase deploy --only firestore --project YOUR_PROJECT_ID --config ../firebase.json
   ```
   `YOUR_PROJECT_ID` is shown under Project settings → General. This uploads `firestore.rules`
   and `firestore.indexes.json` (the index lets each student's marks sync incrementally; it takes a
   few minutes to build after deploying).
8. **Rebuild** the app (below) and install it.

## 2. Build and install

The project needs JDK 17+ and the Android SDK (platform 37, build-tools 36). On this machine both are
installed per user: JDK at `%LOCALAPPDATA%\Android\jdk\jdk-21.0.13+9`, SDK at `%LOCALAPPDATA%\Android\Sdk`
(Android Studio can use the same SDK folder).

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Android\jdk\jdk-21.0.13+9"
.\gradlew installDebug          # phone connected with USB debugging on
```

| What | Command | Output |
| --- | --- | --- |
| Debug APK | `.\gradlew assembleDebug` | `app\build\outputs\apk\debug\app-debug.apk` |
| Signed release APK | `.\gradlew assembleRelease` | `app\build\outputs\apk\release\app-release.apk` |
| Play Store bundle | `.\gradlew bundleRelease` | `app\build\outputs\bundle\release\app-release.aab` |
| Unit tests (+ screenshots) | `.\gradlew testDebugUnitTest` | `app\build\reports\tests\` |
| Record screenshots | `.\gradlew recordRoborazziDebug` | `app\build\outputs\roborazzi\` |
| Lint | `.\gradlew lintDebug` | `app\build\reports\lint-results-debug.html` |
| Security rules tests | `cd rules-tests; npm test` | starts the Firestore emulator, 50 tests |

## 3. Release build and signing

Release builds are minified with R8 (`app/proguard-rules.pro`) and signed with the key in
`keystore/roll-release.jks`. Its passwords are in `keystore.properties`. **Both files are git-ignored.
Back them up somewhere safe** (a password manager or private drive): without them you can never
publish an update to the same app on the Play Store.

To use your own key instead:

```powershell
keytool -genkeypair -v -keystore keystore\roll-release.jks -storetype PKCS12 -alias roll `
  -keyalg RSA -keysize 4096 -validity 10000
```

then update `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and add the
new SHA-1 (`keytool -list -v -keystore keystore\roll-release.jks -alias roll`) in Firebase as in step 4.

If you publish on Google Play with Play App Signing, also add Play's **app signing key** SHA-1
(Play Console → Setup → App integrity) to Firebase, or Google sign-in will fail for Play installs.

## 4. How it works

**Data** (Firestore, see `firestore.rules` for exactly who can read and write what):

```
users/{uid}                          name, rollNo, email, classId
joinCodes/{code}                     classId           (get only, never listed)
classes/{classId}                    name, semester, crUid, joinCode, requiredPercent, timezone
  members/{uid}                      name, rollNo, joinedAt, joinCode
  subjects/{id}                      name, shortName, code, faculty, colorIndex, archived
  timetable/{id}                     dayOfWeek, startMinutes, endMinutes, subjectId, type, room
  sessions/{id}                      subjectId, date, start/end, type, status, note, room, weight, …
    marks/{uid}                      status, markedAt, uid, classId, sessionId
```

Choices worth knowing:

- **Percentages are computed on the phone** (`domain/attendance/AttendanceCalculator.kt`, fully
  unit-tested). Unmarked past classes count as absent, and the app says so ("includes 2 unmarked").
- **Staying inside the free quota.** A normal Firestore listener re-reads every document on each cold
  start, which for 60 students × a semester of classes would exceed Spark's 50k reads a day. Sessions
  and marks therefore sync incrementally (`data/firebase/DeltaSync.kt`): everything comes from the
  offline cache, and only documents changed since the newest cached one come from the server. For
  that to work, deletions are tombstones: a deleted class gets `status: "deleted"`, and a cleared mark
  gets `status: "cleared"`. The app treats both as absent.
- **Offline first.** Marks are written locally at once and sync when there is signal ("Will sync
  when online"). If the server later rejects one (for example, outside the 2-day edit window), a
  snackbar says so.
- **Edit window.** Students can mark today and the two days before (Asia/Kolkata), enforced in both
  the app and the rules.
- **Deep links.** `roll://join/K7MP2X` opens the join screen with the code filled in. The share
  message includes it.

**Code layout**

```
app/src/main/java/app/roll/
  core/designsystem/   theme tokens (Color, Type, Dimens), components with light/dark previews, icons
  core/common, util/   dispatchers, time source, snackbar messages, formatting, share/copy
  data/                Firebase repositories, mappers, delta sync, app session state, preferences
  domain/              models, attendance maths, timetable posting, join codes (pure Kotlin)
  feature/             auth, onboarding, today, subjects, history, manage, settings
  navigation/          type-safe routes and the main tab scaffold
app/src/debug/         design-system gallery (debug only, used by screenshot tests)
rules-tests/           Firestore rules tests (Node, @firebase/rules-unit-testing, emulator)
```

## Licences

The code is under the [MIT License](LICENSE). IBM Plex Sans and IBM Plex Mono are bundled under the SIL Open Font License (`licenses/IBM-Plex-OFL.txt`).
Icons are Material Symbols (Apache 2.0).
