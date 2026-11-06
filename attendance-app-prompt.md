# Build: "Roll" — class attendance tracker (Android only)

You are building a production-quality native Android app from scratch. Read this whole document before writing any code. Work in the phases listed at the end, and stop after each phase so I can run the app on my phone and confirm before you continue.

## 1. What the app does

A college class (e.g. "B.Tech CSE DS — Section C") uses this app to track attendance.

- **One CR (class representative) per class.** Only the CR can create the class, add subjects, set the weekly timetable, and post/cancel/edit classes (sessions).
- **Students** join the class with a 6-character join code, then mark themselves **Present** or **Absent** for each class the CR has posted.
- Every student sees their own attendance percentage per subject, and how many classes they can miss, or must attend, to stay at or above the required percentage (default 75%).
- The CR can see, for each session, who marked present, who marked absent, and who hasn't marked.

This is a self-tracking tool for students, not an official record. Keep that tone in the copy.

## 2. Tech stack (do not deviate without asking)

- **Language:** Kotlin
- **UI:** Jetpack Compose with Material 3, heavily customized (see Design section)
- **Architecture:** MVVM + unidirectional data flow. `ViewModel` exposes `StateFlow<UiState>`; screens are stateless composables that take state + event lambdas.
- **DI:** Hilt
- **Navigation:** Navigation Compose with type-safe routes (Kotlin serialization)
- **Async:** Coroutines + Flow
- **Backend:** Firebase on the free Spark plan only. Do not use Cloud Functions or anything that requires the Blaze plan.
  - Firebase Authentication with Google Sign-In through Android Credential Manager
  - Cloud Firestore with offline persistence enabled (students often have bad network in classrooms)
- **Local:** DataStore (preferences), WorkManager (local reminder notifications)
- **Build:** Gradle Kotlin DSL with a version catalog (`libs.versions.toml`). Use the current stable versions of all libraries. minSdk 26, target/compile the latest stable SDK.
- **Testing:** JUnit + Turbine for ViewModels, the Firebase Emulator Suite for security rules tests.

## 3. Data model (Firestore)

```
users/{uid}
  name: string
  rollNo: string
  email: string
  classId: string | null
  createdAt: timestamp

classes/{classId}
  name: string                // "CSE DS — C"
  semester: string            // "Odd 2026-27"
  crUid: string
  joinCode: string            // 6 chars, uppercase, no 0/O/1/I
  requiredPercent: number     // default 75
  createdAt: timestamp

classes/{classId}/members/{uid}
  name: string
  rollNo: string
  joinedAt: timestamp

classes/{classId}/subjects/{subjectId}
  name: string                // "Design and Analysis of Algorithms"
  shortName: string           // "DAA"
  code: string                // "21CSC204J"
  faculty: string
  colorIndex: number          // index into a fixed 8-colour palette
  archived: boolean

classes/{classId}/timetable/{slotId}
  dayOfWeek: number           // 1 = Monday
  startMinutes: number        // minutes from midnight
  endMinutes: number
  subjectId: string
  type: "lecture" | "lab" | "tutorial"
  room: string

classes/{classId}/sessions/{sessionId}
  subjectId: string
  date: string                // "2026-11-05", class-local date
  startMinutes: number
  endMinutes: number
  type: "lecture" | "lab" | "tutorial"
  status: "scheduled" | "cancelled"
  note: string                // optional, e.g. "Moved to Lab 3"
  createdBy: string
  createdAt: timestamp

classes/{classId}/sessions/{sessionId}/marks/{uid}
  status: "present" | "absent"
  markedAt: timestamp
```

Notes:
- A lab spanning two periods counts as **one** session unless the CR sets its weight; add `weight: number` (default 1) to sessions so a 2-hour lab can count as 2.
- Cancelled sessions never count toward percentages.
- Percentages are computed on device from the student's own marks + the class's scheduled sessions. Do not store denormalized percentages.
- Join codes: look up via a `joinCodes/{code}` document `{ classId }` so students can join without being able to list all classes.

## 4. Security rules (critical — write these early and test them)

Write `firestore.rules` enforcing:
- A user can read/write only their own `users/{uid}` doc.
- Only signed-in users can read `joinCodes/{code}`; only the class's CR can create or delete it.
- Anyone can create a class (they become its CR: `crUid == request.auth.uid`).
- Class doc, subjects, timetable, and sessions: **readable by members and the CR; writable only by the CR.**
- `members/{uid}`: a user can create only their own member doc, and only if they supply a valid join code path (pass the code as a field and verify it with `get()` on `joinCodes`). The CR can delete members.
- `marks/{uid}`: a student can create/update **only their own** mark, only if they are a member, only if the parent session's `status == "scheduled"`, and only if the session date is today or within the last 2 days. The CR can read all marks. Students can read only their own mark.
- CR transfer: the CR can update `crUid` to another member's uid; nobody else can change it.

Write emulator-based tests in a `rules-tests/` folder (Node + `@firebase/rules-unit-testing`) covering every rule above, including the denials.

## 5. Screens

### Shared
1. **Sign in** — app name, one line of description, "Continue with Google". Nothing else.
2. **Profile setup** — name (prefilled from Google), roll number. One screen, one button.
3. **Join or create** — two plain options: "Join with a code" (code field, 6 boxes) or "I'm the CR — create a class" (class name, semester, required %). After creating, show the join code large and offer a share intent ("Join CSE DS — C on Roll: K7MP2X").

### Student (bottom nav: Today · Subjects · History)
4. **Today** — the main screen. Date as a header ("Thursday, 5 Nov"). A list of today's sessions in time order. Each row: time on the left in tabular figures, subject short name + full name, room, and on the right a two-segment **P / A** toggle. Marked state is shown by the filled segment. Cancelled sessions are shown struck through, greyed, with the CR's note. Empty state: "No classes posted yet for today." Pull to refresh. If yesterday has unmarked sessions, show a slim row at the top: "2 classes from yesterday aren't marked" → opens them.
5. **Subjects** — overall percentage at the top as a big number. Then a list: subject name, `attended / total`, percentage, and a status line in plain words: "Can miss 3 more" or "Attend the next 4 to get back to 75%". Percentage turns the warning colour below the requirement. Tap a subject → subject detail with a month-by-month list of every session and its mark, plus a simple "if I miss the next N classes" stepper that updates the projected percentage live.
6. **History** — a month calendar. Each day shows small dots (present = ink colour, absent = warning colour, unmarked = outline). Tap a day to see and edit that day's marks if still within the edit window.

### CR (same as student, plus a "Manage" tab)
The CR is also a student and marks their own attendance. Add a fourth tab:
7. **Manage** — sections:
   - **Today's classes**: list with each session's counts ("38 present · 4 absent · 12 not marked"). Big button "Post today from timetable" which creates today's sessions from the timetable in one tap (skip slots already posted). "+ Add class" opens a bottom sheet: subject picker, time, type, room, weight.
   - Tap a session → **Session detail**: three tabs (Present / Absent / Not marked) listing names + roll numbers, sorted by roll number. Actions: edit, cancel with a note, delete.
   - **Timetable**: weekday tabs (Mon–Sat), list of slots, add/edit/delete.
   - **Subjects**: list, add/edit/archive.
   - **Class settings**: name, semester, required %, join code (copy/share/regenerate), members list (remove), "Transfer CR role" (pick a member, confirm by typing the class name).

### Settings (from a top-bar icon)
Theme (system / light / dark), daily reminder time (local notification via WorkManager: "Mark today's attendance" — only fires if there are unmarked sessions today), leave class, sign out, delete account.

## 6. Design — the most important section

The app must look like it was designed by a careful human designer, not generated. It should feel like a well-made utility: calm, dense, fast, and specific. Think of the restraint of apps like a good bank's transaction list or a train timetable, not a startup landing page.

### Hard bans (never do these)
- No gradients anywhere. No glassmorphism, blur, neon, or glow.
- No purple/indigo "AI" accent colours.
- No emojis in UI text. No sparkle icons. No illustrations of people. No mascots.
- No "Welcome back! 👋", "Let's get started!", "Oops!", or exclamation marks in copy.
- No cards inside cards. Don't wrap every list row in an elevated card with a shadow.
- No circular progress rings for percentages. No confetti.
- No placeholder lorem ipsum or fake stats.
- No more than one accent colour per screen.

### Colour
Define all colours as tokens in one `Color.kt` / theme file; never hardcode in composables.

Light:
- Background `#F6F5F1` (warm paper, not pure white)
- Surface `#FFFFFF`
- Ink (primary text) `#1A1A18`
- Ink secondary `#6B6A65`
- Hairline divider `#E3E1DA`
- Accent / present `#1E5E45` (deep green)
- Warning / absent / below-requirement `#A8402B` (brick)
- Cancelled `#9C9A93`

Dark:
- Background `#121211`
- Surface `#1B1B19`
- Ink `#ECEAE4`
- Ink secondary `#9A988F`
- Hairline `#2C2B28`
- Accent `#5FB38C`
- Warning `#E07A62`

Subject colours: a fixed palette of 8 muted, desaturated tones, used only as a 3dp vertical bar on the left of a row, never as row backgrounds.

Disable Material dynamic colour.

### Typography
- Font: **IBM Plex Sans** (bundle it via Google Fonts downloadable fonts or as resources). Use **IBM Plex Mono** only for the join code and roll numbers.
- Use tabular (monospaced) figures for all times, counts and percentages so columns line up.
- Scale: Display 40/44 (big percentage only), Title 20/26 medium, Body 16/22, Label 13/18 medium, Caption 12/16.
- Sentence case everywhere. No ALL-CAPS buttons.

### Layout and components
- 4dp base grid, 16dp screen gutters.
- Lists separated by hairline dividers, not cards. Rows are 64–72dp tall.
- Corner radius: 10dp for sheets and buttons, 6dp for the P/A toggle, nothing rounder. No pill-shaped everything.
- Elevation: flat. Use the surface vs background colour difference instead of shadows. Only the bottom sheet and dialogs get elevation.
- The **P / A toggle** is the signature control: two adjacent 44×36dp segments with a 1dp outline. Unselected: outline only, secondary ink letter. Selected Present: filled accent, white "P". Selected Absent: filled warning, white "A". A short haptic tick on change. Tapping the selected segment again clears the mark (with a 3-second "Mark cleared · Undo" snackbar).
- Bottom navigation: plain icons + labels, Material Symbols "Outlined", weight 400. The selected item is indicated by ink colour and a small 2dp line above, not a big tinted pill.
- Top app bar: left-aligned title, no centre-aligned titles, no colourful bars.
- Empty states: one line of secondary text, optionally one text button. No illustration.
- Loading: skeleton rows in the hairline colour, never a full-screen spinner. Since Firestore has offline cache, show cached data instantly.
- Motion: 150–200ms, standard easing. No bouncy springs, no staggered list entrance animations.

### Copy voice
Short, plain, specific, like a timetable. Examples:
- "Can miss 2 more"
- "Attend the next 5 to reach 75%"
- "Posted by Sahil · 9:12 am"
- "Cancelled — faculty on leave"
- "You're at 71.4% in DAA"

### Accessibility
- Every touch target at least 44dp.
- Colour is never the only signal: P/A letters are always visible; below-requirement subjects also get a text status line.
- Support font scaling up to 200% without clipping; test with the largest system font.
- Content descriptions on all icon buttons. TalkBack order should read time → subject → status.

## 7. Behaviour details

- Percentage = attended weight / (attended weight + absent weight + unmarked-past weight) × 100. Treat **unmarked past sessions as absent** in the calculation, and show an "includes N unmarked" note so students are nudged to mark them.
- "Can miss N": largest N such that attended / (total + N) ≥ required.
- "Must attend N": smallest N such that (attended + N) / (total + N) ≥ required.
- Show percentages to one decimal place.
- All dates in Asia/Kolkata for this version; store the class timezone on the class doc so it can change later.
- Everything must work offline: marking while offline queues the write and shows a small "Will sync when online" line under the header.
- Handle the CR being removed or leaving: block leaving until they transfer the role.
- Deep link: `roll://join/{code}` opens the join flow with the code filled in.

## 8. Project structure

```
app/src/main/java/.../
  core/        (theme, design system components, utils, dispatchers)
  data/        (Firestore repositories, DTOs, mappers, auth)
  domain/      (models, attendance calculation — pure Kotlin, fully unit-tested)
  feature/
    auth/  onboarding/  today/  subjects/  history/  manage/  settings/
  navigation/
```

Put every reusable UI piece (PAToggle, SessionRow, SubjectRow, StatLine, HairlineDivider, EmptyState, SkeletonRow) in `core/designsystem` with `@Preview`s in both light and dark.

## 9. Build phases (stop after each one)

1. **Setup:** create the project, version catalog, Hilt, theme tokens, fonts, design system components with previews. No Firebase yet. Show me a preview gallery screen.
2. **Domain:** attendance calculation logic + exhaustive unit tests (edge cases: zero sessions, all cancelled, weights, exact 75%).
3. **Firebase + auth:** connect Firebase (tell me exactly which steps I must do in the Firebase console and where to put `google-services.json`), Google sign-in, profile setup, create/join class.
4. **Security rules + emulator tests.**
5. **Student screens:** Today, Subjects, Subject detail, History.
6. **CR screens:** Manage, timetable, subjects, session detail, class settings, CR transfer.
7. **Polish:** offline indicators, reminders, deep links, dark mode check, large-font check, TalkBack pass, empty/error states everywhere.
8. **Release:** app icon (simple, flat, two colours), signed release build instructions, R8/ProGuard rules for Firebase.

At each phase: run a build, fix all warnings you introduced, and give me a short list of what to test on my phone.
