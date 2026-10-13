# CampMeds — V1 Fixed

A single-device Android MAR (medication administration record) app for a summer camp, built
exactly to the attached spec: Kotlin, min SDK 26, Room + SQLCipher (encrypted at rest), CameraX +
ML Kit for QR/barcode scanning, one-time openFDA NDC lookups cached locally, and XLSX export via
Apache POI. No backend, no sync — everything lives on the device.

## Update 1.1 — patients, medications, QR labels, exports, due times

Built on top of V1 Fixed (same architecture, folders and Gradle setup). **Not compiled and not run — see "Verification status".**

| # | Change | Where |
|---|---|---|
| 1 | Dashboard is grouped by patient; only doses whose scheduled time has been reached and that have no recorded outcome are shown; recorded doses leave the list immediately (the `DoseLog` stays). "Due" is never stored: it is computed from the clock + schedule + logs, so restarts and midnight are handled. Tap a patient (or scan their QR) to see only that patient. | `ui/duetoday/DueDoseItem.kt` (`buildCurrentDueList`, `groupByPatient`), `TodaysDueScreen.kt` |
| 2 | **+ Add Medication** is on the dashboard (Admin) and the patient screen. Permission is read reactively, and the nav graph now returns to Login when there is no session (a restored back stack without a session is the likely cause of the vanishing button). | `TodaysDueScreen.kt`, `PatientDetailScreen.kt`, `ui/navigation/NavGraph.kt`, `auth/Session.kt` |
| 3 | NDC may be empty for Manual Entry (button always available, no placeholder NDC stored). NDC lookup accepts product, package, 11-digit and undashed numbers, refuses ambiguous matches, and no longer crashes on bad responses. | `AddEditMedicationScreen.kt`, `domain/NdcNormalizer.kt`, `network/NdcApiClient.kt` |
| 4 | Patient first name, last name, notes, conditions (create + edit). Patient screen shows name, notes, conditions, medications and per-medication details. None of it appears on dashboard/dosing. | `ui/common/PatientFormDialog.kt`, `PatientListScreen.kt`, `PatientDetailScreen.kt` |
| 5 | "What is this medication for?" per medication; shown only on the patient screen. | `AddEditMedicationScreen.kt`, `PatientDetailScreen.kt` |
| 6 | Export = one sheet per medication named `FirstName LastName MedicationName` (cut to 31 chars; duplicates get ` (2)`), with a `Patient Name` column on every row; includes retired medications and deleted patients. | `export/XlsxExporter.kt`, `export/SheetNames.kt`, `CampMedsRepository.loadExportData` |
| 7 | Medication and patient QR labels (QR + names underneath) can be printed, saved as PNG, or shared. Patient QR payload = `campmeds:patient:<patientId>`; medication QR payload is unchanged (existing printed labels keep working). | `scanner/QrCodeGenerator.kt` (`QrLabel`), `scanner/PatientQr.kt`, `ui/common/QrLabelPanel.kt`, `AndroidManifest.xml` (FileProvider) |
| 8 | History integrity: "delete" = archive (`isArchived`) for patients and medications. Both cascading foreign keys that could erase history were removed/replaced (see migration). Renaming a medication (name, NDC, strength or form) archives the old record and creates a new one with a new QR code; other edits update in place. | `data/entity/*`, `data/dao/*`, `domain/MedicationIdentity.kt`, `CampMedsRepository.saveMedicationEdit` |

### Database migration 1 -> 2 (`data/Migrations.kt`)
`fallbackToDestructiveMigration()` is **removed**. `MIGRATION_1_2` adds `firstName/lastName` (backfilled from the old `name`: last word = last name), `conditions`, `purpose`, `isArchived`, and rebuilds `dose_logs` (drops the cascade to medications) and then `medications` (cascade to patients -> RESTRICT), copying every row. `dose_logs` is rebuilt first on purpose: dropping a parent table with cascading keys enabled would otherwise delete the history. `app/schemas/.../1.json` is a **hand-authored** v1 baseline (derived from the v1 entities) for `MigrationTest`; Room will generate `2.json` on the first build.

**Before installing over a device that holds real data, test the migration on a copy / spare device first.**

### Decisions to confirm
* A dose that was due on a previous day and never recorded does not carry over to today (same as V1). Say so if missed doses should stay on the dashboard until handled.
* "Rename" = change of name, NDC, strength or form (`MedicationIdentity.changed`). Schedule, instructions, dates, expiration, pill count and purpose edit in place.
* First name is required, last name optional. Duplicate sheet names get ` (2)`, ` (3)` because Excel forbids duplicates.
* Deleted patients are not browsable in-app (their history is in the export); a patient-QR scan works from the dashboard's in-app scanner only (no phone-camera deep link).

## Update 2 — patients first, per-medication settings, named administration times, prepackaged groups, scanner + NDC

Built on top of Update 1.1 (same architecture, folders, Gradle setup, one local encrypted database, no
backend). **Every part of it is built and executed by CI** — unit tests, the instrumented suite on an Android
emulator, and the debug APK (see "Verification status" below).

| # | Change | Where |
|---|---|---|
| 1 | The home screen lists **patients** only (name + "N due"), never medication names. Tapping a patient opens that patient's currently due medications, which is where a dose is selected and administered. A patient stays listed while anything is still due, disappears when nothing is, and a patient with medications due only later today never appears. | `ui/duetoday/TodaysDueScreen.kt`, `ui/duetoday/PatientDueScreen.kt`, `ui/duetoday/DueDoseItem.kt` (`dueEntriesForPatient`) |
| 2 | Per-medication toggle **"Require QR scan when selecting this medication."** On = the existing scan-first workflow; off = tapping the medication goes straight to the verification/outcome step. Saved per medication, survives restarts; existing records default to on (nothing changes for them). | `data/entity/Medication.kt` (`requireQrScan`), `ui/addmedication/AddEditMedicationScreen.kt`, `ui/duetoday/PatientDueScreen.kt` (`DoseActionSheet`) |
| 3 | Administrator-managed **named administration times** (name + real time, Material 3 time picker) and a medication form that picks from them instead of typing times. Custom times remain possible, so existing medications and camps with no configured names keep working. | `data/entity/AdministrationTime.kt`, `data/dao/AdministrationTimeDao.kt`, `domain/AdministrationTimeRules.kt`, `ui/settings/SettingsScreen.kt`, `ui/addmedication/AddEditMedicationScreen.kt` |
| 4 | **Prepackaged medication groups** (`isGroupAdministered` + `prepackagedGroupId`, per medication, same patient only) and **one-action group administration**: the sheet lists every member, states what will happen to each, and a single "Given" records one administration record per due medication dose. Undue, already-recorded and unticked medications are never touched, and a second tap cannot duplicate or overwrite. | `domain/PrepackagedGroups.kt`, `repository/CampMedsRepository.kt` (`recordGroupAdministration`), `ui/duetoday/PatientDueScreen.kt` (`GroupAdministrationSheet`, `planGroupMembers`) |
| 5 | **"Can't scan — verify manually"** moved to the top of the scanner screen with a contrasting amber background, above the camera preview (not over it), inside a scrollable sheet so it stays usable on small screens. Same behaviour as before: skip the scan, verify by eye, safeguards intact. | `ui/duetoday/PatientDueScreen.kt` (`CanNotScanButton`) |
| 6 | **NDC handling for every valid format**: 10-digit products (4-4, 5-3), 10-digit packages (4-4-2, 5-3-2, 5-4-1), the 11-digit billing format (5-4-2) hyphenated or not, spaces instead of dashes, and 9-digit billing product codes. The padding zero is removed from (or added to) exactly the segment that carries it — never from the whole number — and unhyphenated input is searched in every legal reading so it can never silently resolve to the wrong split. | `domain/NdcNormalizer.kt`, `network/NdcApiClient.kt`, `network/NdcLookupResult.kt` |
| 7 | Lookup outcomes are now distinct: record found (with the directory NDC and the 11-digit billing form to check against the label), no record, **ambiguous** (more than one product matched — never guessed), invalid input, network failure, API failure. Manual entry (empty NDC, no placeholder) stays available in every case. | `ui/addmedication/AddEditMedicationScreen.kt`, `network/NdcLookupResult.kt` |
| 8 | Tests: NDC format coverage (arrangements, billing conversion, ambiguity, invalid input), API client behaviour (query construction, parsing, failures), named-time rules (build/retime/detach/validate), prepackaged rules, and group administration. | `app/src/test/java/com/campmeds/app/...` |

### Database migration 2 -> 3 (`data/Migrations.kt`)

`MIGRATION_2_3` re-creates `medications` with the four new columns and creates `administration_times`
(+ unique name index). No destructive fallback is used and no row is dropped. Defaults for existing data:
`administrationTimeIds = ''` (every existing time is a custom time), `requireQrScan = 1` (scanning still
required), `isGroupAdministered = 0`, `prepackagedGroupId = NULL` (nothing grouped). `MigrationTest` now
migrates 1 → 2 → 3, validates the schema and asserts that the existing rows, the RESTRICT foreign key and the
dose history all survive.

### Rules worth knowing

* **Changing a named time** (e.g. Breakfast 08:00 → 08:30) asks for confirmation, then moves exactly the
  scheduled times that reference that entry on current medications. DoseLogs are never rewritten — history
  keeps the time each dose was scheduled for.
* **Removing a named time** asks for confirmation, keeps every scheduled time and turns it into a custom
  time. No dose can be lost or silently moved to another entry.
* **Renaming** an entry only relabels the medications that use it; no schedule changes.
* **A group action** covers only doses that are due right now; a later dose, a held/refused one or an unticked
  medication is listed for transparency but recorded separately (or not at all).
* **A group is never created automatically** and never spans patients (enforced in the form, in
  `PrepackagedGroups.validate` and again in `CampMedsRepository.saveMedicationEdit`).
* **A lookup that is spread over several requests cannot be sunk by one of them.** A number typed without
  dashes is searched in each legal reading (a 10-digit package code can be read 4-4-2, 5-3-2 or 5-4-1), so
  one NDC entry can mean two or three requests. A momentary `5xx`/`429` from openFDA is retried once; if a
  reading still cannot be checked, the record another reading found is shown **and** the readings that could
  not be checked are named, so the label can be re-checked — a failed request never turns a good lookup into
  "lookup failed", and it is never reported as "no such record" either. (This is what made 5-3-2 codes such
  as `68180-613-07` come back as a lookup failure when typed without dashes.)

### Codes that come back with no record — checked against the live directory

The NDC Directory lists currently-listed products, in the 10-digit form, with each segment at its assigned
width. Three codes reported from a camp list were looked up by hand; none of them has a record, and the
reasons differ:

| Code as typed | Shape | What the directory says | Why |
|---|---|---|---|
| `68180-252-01` | 5-3-2 (legal) | no package `68180-252-01`, no product `68180-252`, and nothing under `68180-25*` at all; the other readings of those digits (`6818-0252-01`, `68180-2520-1`) are absent too, as is the billing sibling `68180-0252-1` | the digits do not exist for that labeler (Lupin's products run `68180-1xx` upwards) — a wrong digit, or a withdrawn product |
| `0093-025-01` | 4-3-2 — **9 digits** | never searched: 9 digits cannot be an NDC (10, or 11 in the billing format) | a digit is missing or extra; no completion matches a current record either — `0093-0025-01`, `00930-025-01` and `0093-0250-01` are all absent |
| `00904-617-10` | 5-3-2 (legal) | no labeler `00904` exists at all; labeler `0904` (Major Pharmaceuticals) does, and its only `0904-61*` product is `0904-6172` (carbamazepine 200 mg, packages `-50` and `-61`) | the labeler carries the **padding zero of the 11-digit billing format** (`00904-6172-50` is the billing form of `0904-6172-50`); the digits as typed match no record |

Two directory facts behind those results, both verified by hand against the API:

* 11-digit billing values are **not** stored: `packaging.package_ndc:"00069-2587-68"` finds nothing while
  `0069-2587-68` (Zyrtec) is there — a billing code is only ever found through the conversion the app does.
* Labelers are stored at their assigned width: `product_ndc:0093-*` (Teva) returns 385 products, while
  `product_ndc:00093-*` (the same labeler, zero-padded) returns none.

So "no record" is usually a formatting or transcription problem, not a missing drug. A lookup that finds
nothing now says which value was searched, which other readings of the same digits exist (when the dashes as
typed kept them from being searched), and whether the code looks like it carries billing padding. It still
searches only what was typed, and never substitutes another code silently.

### Verification status

GitHub Actions (`.github/workflows/blank.yml`) runs on every push to `main`/`master` and on pull requests, and
on the update branch it ran:

| Step | Result |
|---|---|
| `testDebugUnitTest` | **108 unit tests passed, 0 failed** — NDC formats (5-3-2 and pasted values included), lookup outcomes, retry behaviour and the notes a no-record lookup carries, named-time rules, prepackaged rules, due list, group administration |
| `assembleDebugAndroidTest` | instrumented suite compiles |
| `connectedDebugAndroidTest` (API 30 x86_64 emulator) | **14 instrumented tests passed, 0 failed** — `MigrationTest` (1 → 2 → 3), `GroupAndScheduleIntegrityTest`, `HistoryIntegrityTest`, `DatabasePersistenceTest`. The step waits for the emulator to finish booting and retries once after restarting adb if the runner cannot reach it; the retry is reported as a warning |
| `assembleDebug` | debug APK built (uploaded as the `campmeds-debug-apk` artifact) |

The CI job also prints the per-suite pass/fail counts and the Gradle failure text as annotations, so a red
build names the failing test without opening the log.

The requests the client builds were also checked against the live openFDA NDC Directory by hand: the 5-3-2
package codes `68180-613-07` (Quetiapine, Lupin) and `10695-114-18` (Vancomycin HCL, Willow Birch) both come
back with their record, their 4-4-2 and 5-4-1 readings do not, and the directory answers `5xx` often enough
that one retry is worth having.

## Update 2.2 — admin spreadsheet import (.xlsx)

Settings → **Import spreadsheet (.xlsx)** (Administrator only). Everything is parsed on the device with the existing Apache POI dependency; nothing is uploaded and there is no network call.

Two separate workflows:

- **Import new records** — for a spreadsheet without CampMeds IDs. Adds patients and medications only. Rows with an ID are rejected. A patient name that matches an existing active patient needs an explicit decision (new person, or add to the existing record). Medications that match an existing one (name, strength, form) need an ADD or SKIP decision. Nothing is overwritten.
- **Update existing records** — requires a CampMeds template (Settings → Import spreadsheet → *Download CampMeds template*). Rows are matched by **Patient ID** and **Medication ID** only; row position and names never identify a record. Unknown, archived, malformed or wrongly typed IDs block that row. Blank cells change nothing; `[CLEAR]` is the only way to empty an optional field (Date of Birth, Last Name, Notes, Conditions, End Date, Bottle Expiration, Purpose). A medication with administration history cannot be moved to another patient by import.

Safety rules:

- A preview is shown before any write: new patients, new medications, patient and medication changes, possible duplicates, invalid rows, conflicts, and records absent from the sheet (information only; nothing is ever deleted or archived because a row is missing). Cancel writes nothing.
- Significant changes (name, NDC, strength, form, dose instructions, schedule, dates, patient association) need an explicit confirmation tick.
- Changing a medication's identity archives the old record (history kept) and creates a replacement with new IDs, the same rule as the medication form.
- Imported medications have no dose records. Administration history is never read for writing, changed or deleted.
- The reviewed preview is compared with a fresh plan inside the database transaction; if the database changed in between, the import is rejected.
- The whole import is one Room transaction. Any failure rolls it back.
- A verified, SQLCipher-encrypted recovery copy is taken in `filesDir/import_backups` before each write (last 5 kept).
- Each attempt is logged to `import_history` (ID, time, file name, outcome, counts, no patient values). Patient detail changes are logged by field name only.
- Limits: 10 MB file, 5,000 rows, 40 columns, first worksheet only, `.xlsx` only.

Exports: the existing XLSX export layouts are unchanged except that the patient and medication sheets now have **Patient ID** and **Medication ID** columns appended at the end. Reporting exports stay usable.

### Database migration 4 -> 5 (`data/Migrations.kt`)

Schema v4 is the prepackaged group name migration (`MIGRATION_3_4`, from the other update). This step is v4 -> v5.

Purely additive: creates `import_history` and `change_history` (with indexes on `change_history.recordId` and `importId`). No existing table, row or key is changed. There is no destructive fallback.

### Verification status

Not yet compiled or run in this environment (no JDK / Android SDK; Maven, Google and Gradle hosts unreachable here). The JVM tests are in `app/src/test/.../spreadsheet/SpreadsheetPlannerTest.kt`. The device tests are in `app/src/androidTest/.../SpreadsheetImportIntegrationTest.kt`. Run `./gradlew testDebugUnitTest connectedDebugAndroidTest` to verify.

### Limitations

- Medications have no route or frequency fields in this app; the supported fields are used instead.
- Restoring a recovery copy is a manual, administrator-level step (there is no in-app restore).
- The first run after updating runs the 4 -> 5 migration; Room schema JSON for version 5 is generated at build time.

## What "V1 Fixed" is

The original V1 prototype plus **only** the concrete bug fixes below. No V2 functionality is included
(no sync, packages, controlled meds, inventory counts, parent contacts, paper entries, correction history,
or dashboard). DoseLog is still overwrite-based, exactly as V1 allows. V2 is built *on top of this tree*.

| # | Fix | Where |
|---|---|---|
| 1 | SQLCipher key was different on first vs. later launches; now the identical stored value is returned every time (and persisted synchronously) | `data/DatabaseKeyProvider.kt` |
| 2 | Added `material-icons-extended` (Logout/Upload icons are not in core/material3) | `app/build.gradle.kts` |
| 3 | Invalid schedule times are rejected with a message and the typed text is kept (was silently dropped) | `domain/InputValidation.kt`, `AddEditMedicationScreen.kt` |
| 4 | At least one valid schedule time required (duplicates are merged, since duplicate times crashed the due list) | same |
| 5 | Invalid start/end/expiration dates show an error instead of silently keeping the old value | same |
| 6 | End date may not precede start date | same |
| 7 | Today's Due refreshes its date on resume, on system date/time/zone change, and at local midnight | `ui/duetoday/TodaysDueScreen.kt` |
| 8 | Changing the NDC discards the old lookup/manual details; a new lookup or explicit manual exception is required to save | `AddEditMedicationScreen.kt` |
| 9 | Manually entered (exception) medications are no longer used as the NDC lookup cache | `data/dao/MedicationDao.kt` |
| 10 | "select manually" replaced by an honest **"Can't scan — verify manually"** step that shows the due medication | `TodaysDueScreen.kt` |
| 11 | Explicit checkbox "I verified the medication name, strength, and form against the physical medication" before Given/Held/Refused | same |
| 12 | Expired bottle: prominent warning; blocked unless Level 1 Override/Admin, recorded as an override | same |

Also fixed while reviewing (found by reading the code, not on the bug list): `Modifier.clickable` misuse and a leaked
`MainScope` in the patient list, `Flow.first()` called as a top-level function in Login/Export, and
`autoSizeColumn()` (needs `java.awt`, crashes on Android) in the XLSX export.

## Verification status — please read

This was produced in an environment with **no Kotlin compiler, Gradle, Android SDK or network**. Therefore:

* **Compiled: NO.** The code has been reviewed by eye and brace-balanced, but never built. Expect to fix a
  few compile errors on the first Android Studio sync.
* **Tests written but NOT run:** `app/src/test/.../InputValidationTest.kt` (JVM) and
  `app/src/androidTest/.../DatabasePersistenceTest.kt` (device/emulator; covers bug 1).
* Manual test to do first: install → create patient + medication → force-stop → reopen → data still there.

Known issues / risks (not fixed, out of scope for a bug-fix pass):
* Apache POI on Android can need extra StAX/XMLBeans setup; if export fails at runtime, that is the first suspect.
* (Fixed in 1.1) NDC lookup now handles package/11-digit/undashed numbers. It could not be tested against the live openFDA API from the build environment.
* No in-app user-management screen (not in V1 spec); additional staff accounts need `CampMedsRepository.createUser(...)`.
* All times are device-local (`LocalDate/LocalDateTime.now()`); the device timezone must be the camp's timezone.

## Opening the project

1. Install **Android Studio** (Koala/2024.1 or newer recommended).
2. `File → Open`, point it at this folder (the one containing `settings.gradle.kts`).
3. Let Gradle sync — it will download the Gradle wrapper jar itself the first time you sync
   (this project ships the wrapper *config* but not the binary jar, since it was built without
   network access; Android Studio regenerates it automatically on first open). If you'd rather do
   it from the command line first, run `gradle wrapper` once inside this folder with a local
   Gradle 8.7+ install, which will create `gradlew`, `gradlew.bat`, and
   `gradle/wrapper/gradle-wrapper.jar`.
4. Run on a device or emulator with a working camera (the emulator's virtual camera works fine
   for testing the QR scan flow — point it at a QR code image).

## First run

There's no seed data. The Login screen detects that the `users` table is empty and walks you
through creating the first **Admin** account (name + PIN). From there, log in as that Admin to
add patients and medications. Day-to-day dosing can be done by Provider or Level 1 Override
accounts, but there's no "manage users" screen in v1 (it wasn't in the spec's screen list, so it
was left out rather than inferred) — for this prototype, create additional staff accounts by
calling `CampMedsRepository.createUser(name, role, pin)` directly (e.g. temporarily from a debug
button, or via `adb shell` + a small test hook) until a user-management screen is added.

## Spec → code map

| Spec section | Where it lives |
|---|---|
| §2 Platform & stack | `app/build.gradle.kts` (Room, SQLCipher, CameraX, ML Kit, POI) |
| §3 Data model | `data/entity/*.kt`, `data/converter/Converters.kt` |
| §4.1 Login | `ui/login/LoginScreen.kt` |
| §4.2 Patient list | `ui/patientlist/PatientListScreen.kt` |
| §4.3 Patient detail | `ui/patientdetail/PatientDetailScreen.kt` |
| §4.4 Add/Edit medication | `ui/addmedication/AddEditMedicationScreen.kt`, `scanner/QrCodeGenerator.kt` |
| §4.5 Today's due | `ui/duetoday/TodaysDueScreen.kt`, `ui/duetoday/ScannerView.kt`, `ui/duetoday/DueDoseItem.kt` |
| §4.6 Dose history | `ui/dosehistory/DoseHistoryScreen.kt` |
| §4.7 Export | `ui/export/ExportScreen.kt`, `export/XlsxExporter.kt`, `export/SheetNames.kt` |
| §5 Core workflow | `TodaysDueScreen.kt` → `DoseActionSheet` (scan → confirm → mark) |
| §6 Roles | `data/entity/Enums.kt` (`Role`), `auth/Session.kt` (`hasAtLeast`), gated throughout the UI |
| §7 Encryption at rest | `data/DatabaseKeyProvider.kt` (Keystore-wrapped passphrase) + `data/AppDatabase.kt` (SQLCipher `SupportFactory`) |
| §7 Offline-first | `repository/CampMedsRepository.kt.lookupNdc()` — checks the local cache before ever calling `NdcApiClient` |
| §9 Acceptance criteria | See "What to test" below |

## What to test against the acceptance criteria

1. **Add a patient + medication with a successful NDC lookup.** Try a real NDC, e.g.
   `0069-2587` (Pfizer) or any 10/11-digit NDC you have on hand — the Add Medication screen calls
   openFDA and shows the matched name/strength/form for confirmation. An unmatched NDC falls back
   to manual entry and flags the medication `isException = true`.
2. **Scan a generated QR code back to its medication.** Every medication gets a random QR code
   (`Medication.qrCode`) rendered on the Add/Edit screen (`QrCodeGenerator`). Scanning it from
   Today's Due resolves back to that exact medication via `MedicationDao.getByQrCode`.
3. **Log a full day and export.** Mark a few doses Given/Held/Refused from Today's Due, then use
   the Export screen (Admin only) — it writes one `.xlsx` workbook with one sheet per medication via
   Android's `CreateDocument` picker, so you choose the save location (Downloads, Drive, etc.).
4. **Offline behavior.** Turn off networking after medications are already added — the app keeps
   working end to end (scanning, dosing, history, export) because every read after the initial
   lookup comes from the encrypted local Room database.

## Known v1 limitations (intentionally, per spec §8)

- No multi-device sync, no backend.
- No pharmacy-prepackaged bag workflow — every bottle/bag is scanned individually.
- No controlled-substance-specific rules.
- DoseLog edits overwrite in place — no correction/edit history.
- No inventory forecasting/run-out alerts, no push notifications, no multi-camp support.
- No in-app "manage users" screen (not in the spec's screen list — see "First run" above).

## A note on the XLSX dependency

The spec allows POI *or* a lighter XLSX writer. This build uses `org.apache.poi:poi-ooxml`
because it's the most battle-tested option and the export path isn't performance-critical for a
single camp's daily dose log. If APK size ever becomes a concern, swapping in a lighter writer
(e.g. hand-rolling the OOXML zip, or a library like `fastexcel`) would only require changes to
`export/XlsxExporter.kt` — nothing else in the app depends on POI directly.
