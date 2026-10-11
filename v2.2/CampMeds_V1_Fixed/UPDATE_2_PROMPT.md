# CampMeds — Incremental Update to Existing Android Application

You are updating the existing CampMeds Android application. The previously requested features have already been incorporated into the current app. Your task is to implement the new changes listed below on top of the current codebase.

Do not rebuild the application from scratch, replace the existing architecture, or reimplement features that already work.

Before modifying anything, inspect the existing project structure and identify the relevant files, database entities, DAOs, repositories, ViewModels, screens, navigation, QR-scanning workflow, medication scheduling logic, NDC lookup implementation, and database migrations.

Determine how the existing implementation works before making changes. Reuse existing functionality wherever possible and make only the modifications necessary for these updates.

---

## 1. Change the home screen to show patients first

Modify the home screen so that it displays the names of patients with medications currently due, rather than displaying each due medication directly.

Requirements:

- When a patient's medication becomes due, display the patient's name on the home screen.
- Do not display the names of individual medications on the home screen.
- When the user taps a patient's name, open or expand a view showing that patient's currently due medications.
- Allow the user to select an individual medication and continue through the existing administration workflow.
- Once a medication has been administered, remove it from the patient's current due-medication list.
- If the patient has other medications that remain due, keep the patient visible on the home screen.
- If none of the patient's medications are currently due, remove that patient from the due-patient list.
- Preserve existing administration records and history.
- Maintain the existing scheduled-time logic: a medication must not appear as due before its scheduled administration time has been reached.
- Do not display patients merely because they have medications scheduled later in the day.

---

## 2. Add an optional QR-scanning toggle for each medication

Add a toggle when registering a new medication and when editing an existing medication.

Use a clear label such as:

> "Require QR scan when selecting this medication."

Requirements:

- When enabled, tapping the medication in the patient's due-medication view must open the existing QR code scanner as part of the administration workflow.
- When disabled, tapping the medication must continue through the existing workflow without requiring a QR scan.
- Save the setting individually for each medication.
- The setting must persist after the application is closed and reopened.
- Apply the setting consistently to newly created and existing medications.
- Do not accidentally require scanning for every medication.
- Preserve the existing medication-identification and scanning safeguards.
- Inspect the current data model and add the smallest necessary database change to persist this setting. Use a proper database migration if required.

---

## 3. Add administrator-configurable named administration times

Add a settings screen or an appropriate section of the existing settings interface where an administrator can create and manage named medication administration times.

Each entry must contain:

- **Name:** A descriptive label, such as Breakfast, Lunch, Dinner, or Bedtime.
- **Time:** The actual scheduled time, selected using an appropriate time picker.

For example:

| Administration name | Scheduled time |
|---|---|
| Breakfast | 8:00 AM |
| Lunch | 12:00 PM |
| Dinner | 6:00 PM |
| Bedtime | 9:00 PM |

### Administrator functionality

Administrators must be able to:

- Add named administration times.
- Edit existing names and times.
- Remove entries when appropriate.
- View the configured administration times.
- Protect existing schedules from accidental damage when an administrator edits or removes a time already assigned to medications.

### Medication registration and editing

Replace the requirement to manually enter an administration time for every medication with a dropdown containing the configured administration-time names.

For example, selecting Breakfast assigns the configured Breakfast time of 8:00 AM to the medication schedule.

Requirements:

- Display the selected administration-time name clearly.
- Use the actual configured time for due-time calculations, not the name alone.
- Preserve support for multiple administration times per medication where the existing application supports multiple daily doses.
- Ensure that scheduled doses appear due only when their configured date and time have been reached.
- Define and implement consistent behavior when an administrator changes a named time. For example, if Breakfast changes from 8:00 AM to 8:30 AM, update applicable active schedules according to a clearly defined rule without corrupting historical administration records.
- Do not silently alter past administration records when a schedule changes.
- Only administrators may manage the shared list of named administration times.
- Authorized providers may select configured times when their existing permissions allow medication creation or editing.
- Store these settings in the existing local database. Do not introduce cloud synchronization or a new backend.
- Use appropriate database migrations to preserve existing data and schedules.

---

## 4. Add group administration for prepackaged medications

The application must support medications that have been prepackaged together and are intended to be administered together.

Each individual medication must have a setting on its registration and editing screens to indicate whether it belongs to a prepackaged medication group.

Use a clear label, such as:

> "Administer together with other selected medications."

Requirements:

- The setting must be configurable separately for each medication.
- Multiple medications belonging to the same patient must be able to be grouped together.
- The application must identify which medications belong to the same group.
- Medications in a group must be distinguishable from medications intended to be administered individually.
- Do not automatically group every medication belonging to a patient.
- Do not group medications belonging to different patients.
- Do not assume that medications scheduled for the same time are necessarily packaged together.
- Preserve individual medication identities, schedules, and administration history.
- Continue to allow individual administration when needed.
- Inspect the existing data model before implementation. If a group identifier or equivalent relationship is required to reliably identify the medications that belong together, implement it using the smallest appropriate change to the existing architecture.

### 4.1. One-action administration for an entire prepackaged group

The user must be able to administer all currently due medications in a prepackaged group with one action.

For example, if three medications have been prepackaged together for the same patient, the user should be able to select the group, confirm the medications included, and press "Given" once to record all three as administered.

Implement the following behavior:

- Show the medications included in the group before the user confirms administration.
- Provide a single "Given" action for the group.
- When confirmed, mark every applicable, currently due medication dose in that group as Given.
- Create or update a separate administration record for each medication dose.
- Record each medication's identity, scheduled dose, administration date and time, and administering user, using the existing data model and history functionality.
- Remove all successfully recorded doses from the patient's current due-medication list.
- Do not require the user to open and mark each medication individually.
- Prevent duplicate administration records if the user accidentally presses the confirmation button more than once.
- Do not mark doses scheduled for a later time as Given merely because another dose in the group was administered.
- Allow an individual medication to be handled separately if it is refused, held, unavailable, or otherwise not administered. Do not incorrectly mark that medication as Given.
- Preserve the ability to administer a single medication individually when appropriate.

**Important:** A single user action must create a separate, accurate administration record for each medication dose that was actually recorded as administered. Do not create one generic group record that obscures which medications were given.

The purpose of this feature is to accommodate prepackaged medication groups, not to automatically administer or mark every medication belonging to the patient as given.

---

## 5. Improve the "Can't Scan" option

Locate the existing "Can't Scan" option on the QR code scanner screen. It is currently positioned at the bottom of the screen.

Modify the existing option as follows:

- Move it to the top of the scanner screen.
- Give it a clearly contrasting background so that it is immediately visible.
- Ensure it is easy to find and press during medication administration.
- Preserve its existing functionality.
- Ensure it remains usable on smaller Android screens.
- Do not obstruct the camera preview or essential scanning controls.
- Reposition and restyle the existing option rather than creating a duplicate.
- Do not silently bypass existing medication-identification safeguards when this option is used. Preserve the current manual identification or exception-handling workflow.

---

## 6. Fix NDC lookup to support all valid NDC formats

The existing NDC lookup works with many number combinations, but some codes may not work. Inspect the current implementation and determine whether valid NDC numbers are being rejected, incorrectly normalized, or searched incorrectly.

The goal is to ensure that all valid supported NDC formats can be entered and processed correctly, rather than supporting only the formats that currently work.

### 6.1. Support recognized NDC formats

Account for:

- The recognized 10-digit NDC segment arrangements.
- The standardized 11-digit billing format.
- Different labeler, product, and package segment lengths.
- Hyphenated NDCs.
- Unhyphenated numeric input.
- Appropriate zero-padding and format conversion.

Do not assume that all NDCs use the same segment lengths.

### 6.2. Normalize NDCs correctly

Inspect how the current implementation validates, normalizes, and submits NDCs to the existing openFDA NDC Directory API.

Requirements:

- Correctly identify the labeler, product, and package segments.
- Add zeros only to the appropriate segment when converting a recognized format.
- Do not blindly prepend a zero to an entire code.
- Do not strip leading zeros in ways that change the intended drug or package.
- Do not treat the 10-digit NDC and its 11-digit billing representation as interchangeable strings without correctly applying the relevant formatting rules.
- Preserve the original entered value when useful for display or troubleshooting.
- If an unhyphenated 10-digit input has multiple plausible segment interpretations, handle the ambiguity explicitly rather than silently selecting an uncertain match.

### 6.3. Verify the existing API integration

Inspect the existing openFDA NDC Directory API integration, including:

- Request construction and query parameters.
- Search fields and expected formatting.
- URL encoding and query escaping.
- Response parsing.
- Handling of multiple matching records.
- Error handling and user-facing messages.
- Network failures and API response failures.

Correct any defects that cause valid NDCs to fail because of incorrect query construction, format assumptions, or response parsing.

Reuse the existing API integration wherever possible. Do not replace the app architecture or introduce an unnecessary service.

### 6.4. Handle lookup failures correctly

Distinguish between:

- Invalid input.
- A code that appears structurally valid but has no matching directory record.
- Ambiguous input that cannot be resolved confidently.
- Network connectivity failures.
- API errors or unexpected responses.

Do not silently select a different medication when the result is uncertain.

A failed lookup must not prevent manual medication entry. The existing workflow must continue to allow a medication to be registered with an empty NDC when manual entry is selected. Do not require a fake or placeholder NDC.

### 6.5. Test NDC handling comprehensively

Add or update automated tests covering:

- Every recognized 10-digit NDC segment arrangement.
- Standardized 11-digit billing codes.
- Hyphenated and unhyphenated input.
- Correct leading-zero handling.
- Correct conversion between supported formats.
- Invalid lengths and invalid characters.
- Nonexistent codes.
- Ambiguous input.
- API errors and network failures.
- Correct identification of the intended medication and package.

Use verified test cases rather than assuming that arbitrary numeric combinations are valid NDCs.

Do not claim that every possible numeric combination is a valid NDC. The requirement is that every valid supported NDC format be handled correctly, and that invalid or ambiguous input be handled safely.

---

## 7. Database integrity and backward compatibility

Before making database changes, inspect the current schema and migration history.

- Preserve existing patients, medications, schedules, and administration records.
- Use proper incremental Room database migrations.
- Do not delete and recreate the database.
- Do not use destructive migrations.
- Preserve existing medication and QR workflows.
- Ensure that existing medication records receive sensible defaults for any newly introduced settings.
- Ensure group administration, QR-scan settings, and named administration times remain correct after the app restarts.
- Do not introduce cloud synchronization or a backend.

---

## 8. Testing and regression prevention

Before considering the update complete, verify that:

- The home screen shows patients rather than individual due medications.
- Tapping a patient displays only that patient's currently due medications.
- Administered medications disappear from the current due list while history remains intact.
- The QR-scanning toggle works independently for each medication and persists across restarts.
- Administrators can create, edit, and remove named administration times.
- Medication forms use the configured administration-time dropdown.
- Changes to named administration times do not corrupt active schedules or historical records.
- Multiple medications can be assigned to a prepackaged group for the same patient.
- A single "Given" action records each applicable dose in the group individually.
- Group administration does not mark later doses, held doses, refused doses, or unadministered medications as Given.
- Duplicate taps do not create duplicate administration records.
- Individual medication administration remains available.
- The "Can't Scan" option is visible at the top of the scanner screen and works as before.
- All recognized valid NDC formats are handled correctly.
- NDC lookup resolves to the correct medication and package.
- Invalid, ambiguous, nonexistent, and unavailable NDC lookups are handled safely.
- Manual entry remains available without an NDC.
- Existing functionality not specifically changed continues to work.
- Database migrations preserve existing data.
- The application compiles successfully.
- The debug APK assembles successfully.

Do not report a test as passing unless it was actually run and passed. Clearly identify any remaining failures or unverified behavior.

---

## 9. Required output — return only changed files

Return only the files that were actually modified or newly created for this update. Do not return the entire application or a complete project archive.

Requirements:

- Preserve the original filenames and relative directory paths for modified files.
- New files must use appropriate names and locations within the existing project structure, module, and package layout.
- Do not rename, move, or reorganize files that this update does not require changing.
- Return every file you touch, even for a small edit, but do not return files you did not change.
- Do not include generated build output, the Gradle wrapper, IDE files, or database schema JSON unless a migration changed the schema — in that case include only the new or changed schema JSON.
- Do not return a zip, archive, or whole-project copy; provide the changed files with their full contents and correct paths.
- For each returned file, state in one line what changed and why.
- Keep the diff minimal and scoped to this update. Do not reformat, "clean up", or refactor code the update does not require.
- Everything not covered by the changes above must keep working exactly as it does today.
- Do not report any requirement as complete unless it was actually implemented, and do not report a test as passing unless it was actually run and passed.

=== END OF PROMPT ===

<!-- Everything below this line is orientation notes for the person applying the update, NOT part of the prompt. Delete it before pasting into Claude if you prefer. -->

---

## Appendix — current codebase orientation (verified by inspection, not a requirement)

Quick notes from reading the current code, so the update does not have to rediscover them. Re-verify in the code before relying on any of it.

- **Database:** Room version is currently `2` (`data/AppDatabase.kt`), with `MIGRATION_1_2` in `data/Migrations.kt`; the committed baseline schema is `app/schemas/com.campmeds.app.data.AppDatabase/1.json` (Room generates `2.json` on build). Any new column/table means version `3` + a new `MIGRATION_2_3` registered alongside the existing migration — keep `fallbackToDestructiveMigration` removed.
- **Settings:** no settings screen exists yet. Navigation is hand-rolled in `ui/navigation/NavGraph.kt`; roles live in `data/entity/Enums.kt` (`PROVIDER`, `LEVEL1_OVERRIDE`, `ADMIN`) with `auth/Session.kt` helpers — that is where the admin-only gating for named administration times belongs.
- **Home screen:** `ui/duetoday/TodaysDueScreen.kt` with due-list/grouping logic in `ui/duetoday/DueDoseItem.kt` (`buildCurrentDueList`, `groupByPatient`). "Due" is never stored — it is computed from the clock, `scheduleTimes` and `DoseLog`s, so restarts/midnight already behave.
- **Medication model:** `data/entity/Medication.kt` (`scheduleTimes: List<LocalTime>`, `isException`, `isArchived`). `scheduleTimes` is a free-form list of times today, so named administration times need a new table plus a link on the medication (e.g. name stored with time resolved at read). The QR-required toggle and the prepackaged-group identifier are new fields on this entity (group scoped to patient).
- **Scanner:** `ui/duetoday/ScannerView.kt` is the camera preview; "Can't scan — verify manually" is currently a plain `TextButton` inside `TodaysDueScreen.kt` (the `DoseFlowMode.CONFIRM_SCAN` branch, rendered below the preview) — move/restyle that control in place, do not add a second one.
- **NDC:** `domain/NdcNormalizer.kt` (format candidates, padding rules, ambiguity list), `network/NdcApiClient.kt` (openFDA `drug/ndc.json`; queries `product_ndc` and `packaging.package_ndc`, 404 = no match, multi-product match = error), `network/NdcLookupResult.kt`. Existing unit tests: `app/src/test/java/com/campmeds/app/domain/NdcNormalizerTest.kt` and `app/src/test/java/com/campmeds/app/network/NdcApiClientTest.kt`.
- **Build:** Kotlin + Gradle KTS, `minSdk 26`, `compileSdk 34`, Room 2.6.1 + SQLCipher 4.5.4, CameraX 1.3.4 + ML Kit barcode 17.3.0, Apache POI 5.2.5. CI at `.github/workflows/blank.yml` runs `./gradlew assembleDebug` (JDK 17, Gradle 8.7).
