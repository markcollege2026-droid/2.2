# CampMeds 1.3 — incremental camp operations update

This extends the existing Compose UI, repositories, Room database, SQLCipher encryption and local PIN
permissions. No cloud service or new runtime dependency was added. This note supersedes the 1.2 note
that PRN was classification-only.

## Changes

- **PRN:** administration times are optional in the medication form and spreadsheet imports. Non-PRN
  medication setup still requires a time. PRN medications are not automatically due, group-administered,
  or bulk-held, even if they retain legacy schedule values. Request a dose from the camper's medication
  details or patient dosing screen. The same QR/manual identification, expired-bottle/override checks,
  physical confirmation and outcome workflow applies. Recent outcomes are displayed. Each request keeps
  a stable timestamp while open; repeat submissions cannot overwrite it. PRN outcomes are marked in
  history and exports. This does not infer a clinical interval or maximum dose: staff must follow the prescription.
- **Multiple fields and associations:** campers have independent lists of bunks and counselors. Settings
  configures field labels, field visibility, counselor choices, bunk options and bunk-to-counselor
  associations. Selecting bunks fills the union of their counselors without duplicates; configured manual
  adjustments are supported. Disabled fields retain stored values. Editing an association never silently
  rewrites existing campers. Option names are stable keys, not automatic renames of camper data.
- **Divisions:** configure a #RRGGBB color per division. Patient and dosing screens show a color indicator
  together with the division name, never color alone. Patient and home/dosing lists filter by one division
  or all; the archive is filterable too. Settings filters division colors and bunk associations; global sessions
  and named administration times remain global.
- **Actions:** removed the home/dosing Add Medication control, its picker and its navigation callback.
  Creation/editing remain on the patient-details workflow with the existing admin permissions. Removed
  the Settings Add time floating button; one inline Create named administration time action opens the
  original time editor.
- **Bulk hold:** select patients on the home screen, review the explicit dose list and count, optionally enter
  a shared reason, and confirm. The repository transaction rechecks every previewed dose. Unselected
  patients, already-recorded doses, PRN, future doses, dates that rolled over, changed prescriptions and
  inactive campers/medications are excluded. Newly due doses after the preview are not added. Each held
  dose gets its own record, user and timestamp; a repeated/concurrent submission cannot overwrite history.
- **Named sessions:** Settings maintains session names and start/end dates. Camper and medication forms
  offer a session dropdown that copies both dates, while preserving independent date presets and manual
  entry. Editing/removing a session affects only the reusable option, never existing records or historical dates.
- **Scanner layout:** bounded, clipped camera area uses CameraX COMPATIBLE/TextureView mode rather than
  an overlaying SurfaceView. Verify Manually is pinned above scrollable dose-sheet content, including long
  instructions. Permission denial, return from Android Settings, initialization errors and scan errors retain
  manual access. Scanning/manual entry alone never records a dose or bypasses physical confirmation.

## Data migration

Room **6 → 7** is additive; all prior migrations remain registered. Legacy `bunk`/`counselor` strings and
all medication and administration records remain. Each non-null legacy field is copied into a singleton
JSON list, without splitting names containing commas. New settings default to enabled fields and permitted
manual counselor adjustments. New tables contain session, division and bunk definitions. Old dose rows
receive `isPrn = false` (their original workflow); only newly requested PRN administrations use the new flag.
The existing schema-6 JSON is retained, and schema 7 is exported by Room. No destructive fallback is used.

## Verification scope

Tests cover PRN without times/import validation, PRN exclusion from automatic due/group/bulk flows,
JSON name round-trips, counselor unions and permitted overrides, frozen session date copies, division
colors/filtering, additive 6→7 migration, selected-only bulk holds, concurrent/double submits, changed
prescriptions, day rollover, provider/admin permissions, both removed actions, and small-screen Compose
scanner bounds/manual access with denied/error/live-preview states and long instructions.

Compose layout tests use a bounded simulated preview, including a 320×360 dp container and enlarged font.
They do not prove physical-camera decoding or behavior on every phone model. Physical QR scanning,
real-device permission dialogs and installation over a camp's signed production APK still require acceptance
testing. To preserve installed data, use the same signing certificate; do not uninstall to resolve a debug-key
signature mismatch. CI-only test dependencies add no cloud functionality.

Final build/test results will be recorded after the GitHub Actions run completes.
