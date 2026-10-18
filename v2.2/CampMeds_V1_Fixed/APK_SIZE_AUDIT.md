# APK size audit

Measured on 2026-10-18 from the debug universal APK produced by GitHub Actions run
`39130577185`. The app source and dependency set match main commit
`fc7c004c1327aae0c76a74bc03b3c7320d4cc5a3`; the audit-only CI changes do not enter the APK.

## Current total

- **106,770,248 bytes**
- **106.77 MB** (decimal, matching the reported “107 MB”)
- **101.82 MiB** (binary)
- **162.03 MiB** of uncompressed ZIP-entry payload
- 2,883 packaged files

This is a universal debug APK. It contains native code for four ABIs, and it is not minified. The
release build is also configured with `isMinifyEnabled = false`, so changing from debug to the current
release configuration will not address the main causes.

## Packaged-size categories

These figures use compressed bytes actually present in the APK.

| Category | APK size | APK share | Uncompressed payload |
|---|---:|---:|---:|
| Native libraries | 71.32 MiB | 70.0% | 71.32 MiB |
| DEX bytecode | 25.84 MiB | 25.4% | 84.02 MiB |
| Bundled ML models/assets | 2.06 MiB | 2.0% | 2.26 MiB |
| Other packaged files | 0.90 MiB | 0.9% | 3.26 MiB |
| Android resources | 0.90 MiB | 0.9% | 1.00 MiB |
| All remaining assets/metadata | 0.08 MiB | 0.1% | 0.17 MiB |
| ZIP headers, alignment, and signing overhead | 0.71 MiB | 0.7% | — |

The app's images and ordinary Android resources are not the problem. Native SDKs and dependency
bytecode account for about 95% of the APK.

## Native-library cost centers

| Cost center | Universal-APK size | Share of APK | Assessment |
|---|---:|---:|---|
| ML Kit text/OCR pipeline | 39.13 MiB | 38.4% | Large, but intentional if OCR must work offline |
| ML Kit barcode (`barhopper`) | 19.29 MiB | 18.9% | Avoidable for QR-only scanning |
| SQLCipher | 12.78 MiB | 12.6% | Intentional security cost |
| ML Kit vision support | 0.13 MiB | 0.1% | Negligible |

The same native libraries are repeated for every supported ABI:

| ABI | Native bytes in universal APK |
|---|---:|
| `x86_64` | 20.61 MiB |
| `x86` | 20.24 MiB |
| `arm64-v8a` | 18.75 MiB |
| `armeabi-v7a` | 11.72 MiB |

A physical ARM64 phone uses only the 18.75 MiB ARM64 set. The other **52.57 MiB** is packaging
waste for that device.

The four largest individual files are the OCR pipeline repeated by ABI: 11.09 MiB (`x86_64`),
11.03 MiB (`x86`), 10.55 MiB (`arm64-v8a`), and 6.47 MiB (`armeabi-v7a`).

## DEX/dependency bloat

The APK has 19 DEX files totaling 25.84 MiB compressed. `apkanalyzer` attributes 42,863,132 bytes
of inclusive package code. Inclusive parent and child rows overlap, so the rows below should not be
summed.

| Package/cost center | Analyzer byte metric | Share of analyzer total | Why it is notable |
|---|---:|---:|---|
| `androidx.compose.material.icons` | 13.54 MiB | 33.1% | All extended icon styles are packaged; the app uses only 13 filled icons |
| POI/OOXML stack (`org` plus `com.microsoft.schemas`) | about 13.22 MiB | 32.3% | Includes spreadsheet, WordprocessingML, DrawingML, VML, XMLBeans, Commons, and POI code |
| `androidx.compose` (including icons) | 18.39 MiB | 44.9% | Mostly explained by extended icons |
| `com.google` | 3.51 MiB | 8.6% | ML Kit, Play Services support, and Tink |
| CampMeds app code (`com.campmeds.app`) | 0.66 MiB | 1.6% | The app's own code is small |

Particularly clear dependency waste:

1. **`material-icons-extended`** packages five styles and thousands of icons although only 13 filled
   icons are referenced. `Logout` and `Upload` are the two stated reasons for selecting the extended
   package.
2. **Apache POI OOXML** is used for basic XLSX import/export, but its generated schema code also
   contains large WordprocessingML, DrawingML, and VML namespaces that this app does not need.
3. The explicit `poi` declaration alongside `poi-ooxml` is redundant but does **not** itself duplicate
   classes in the APK; Gradle deduplicates that module.
4. The barcode reader uses ML Kit only to return a raw QR value, while ZXing core is already packaged
   for QR generation. A ZXing QR decoder could preserve offline scanning without `barhopper`.

## Estimated outcomes

These are APK/download estimates, not Android's post-install “app storage” number.

| Change set | Estimated result | Estimated saving |
|---|---:|---:|
| Current universal build | **101.82 MiB / 106.77 MB** | — |
| ABI split/App Bundle, ARM64 device, no other change | **about 49.25 MiB** | 52.57 MiB |
| Sideload APK containing both ARM ABIs, no x86 | **about 60.97 MiB** | 40.85 MiB |
| Universal APK with tested R8/resource shrinking | **about 82–90 MiB** | about 12–20 MiB |
| ARM64 split plus tested R8/resource shrinking | **about 31–38 MiB** | about 64–71 MiB |
| ARM64 split, shrinking, local/core icons, lightweight XLSX, and ZXing QR decoding | **about 23–30 MiB** | about 72–79 MiB |

The most realistic target **without removing offline OCR or database encryption is 25–35 MiB per
modern ARM64 device**. The lower end requires replacing the extended-icons, POI/OOXML, and ML Kit
barcode costs rather than relying on R8 alone.

## Recommended order

1. **Ship an Android App Bundle or ABI-split APKs.** This is the largest and lowest-risk win. If direct
   sideloading is required, publish an ARM64 APK and optionally a separate 32-bit ARM APK rather than
   one four-ABI universal APK.
2. **Enable R8 and resource shrinking for release.** Test XLSX import/export, Room, OCR, barcode/QR,
   and SQLCipher against the minified release because POI/XMLBeans and SDK reflection can require
   keep rules.
3. **Remove `material-icons-extended`.** Keep core icons and provide local vector paths/resources for
   the small number that core does not include.
4. **Replace the full POI/OOXML path.** The app performs constrained spreadsheet operations that can
   be implemented with a small XLSX ZIP/XML reader/writer. This removes large unrelated Office
   schemas.
5. **Use ZXing for QR decoding** if the scanners only need QR/raw values. Keep ML Kit barcode only if
   broad one-dimensional and two-dimensional barcode support or its detection quality is required.
6. **Keep offline OCR and SQLCipher unless product requirements change.** They are large, but they
   are feature/security costs rather than accidental bloat. Switching to Play-Services-delivered ML
   models would reduce the initial package further but would weaken the current offline guarantee.

## Ongoing measurement

`ci/analyze-apk-size.py` now categorizes APK ZIP entries, native libraries, ABIs, largest files, and DEX
packages. The Android workflow writes `apk-size-report.md`, adds retrievable check annotations, and
includes the report with the APK artifact. A `size_audit` workflow-dispatch input permits a build-only
size audit without running the test suites.
