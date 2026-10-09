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
