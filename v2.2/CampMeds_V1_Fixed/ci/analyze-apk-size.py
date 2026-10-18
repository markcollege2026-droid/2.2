#!/usr/bin/env python3
"""Produce a dependency-oriented size report for an Android APK.

Only Python's standard library is used so this can run on a developer machine or
on GitHub Actions immediately after assembleDebug/assembleRelease.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import zipfile
from collections import defaultdict
from pathlib import Path
from typing import Iterable


MIB = 1024 * 1024


def mib(size: int) -> str:
    return f"{size / MIB:.2f} MiB"


def pct(part: int, total: int) -> str:
    return f"{(100 * part / total) if total else 0:.1f}%"


def native_family(filename: str) -> str:
    value = filename.lower()
    if "sqlcipher" in value:
        return "SQLCipher"
    if "barhopper" in value or "barcode" in value:
        return "ML Kit barcode"
    if any(token in value for token in ("text_recogn", "ocr", "tflite", "tensorflow")):
        return "ML Kit text/OCR"
    if "image_processing" in value or "vision" in value:
        return "ML Kit vision support"
    if "c++_shared" in value:
        return "C++ shared runtime"
    return "Other native libraries"


def is_ml_asset(name: str) -> bool:
    value = name.lower()
    return any(
        token in value
        for token in (
            "mlkit",
            "barcode",
            "text_recogn",
            "tflite",
            "tensorflow",
            "model",
            "lstm",
            "latin",
            "ocr",
            "com.google.android.odml",
        )
    )


def classify(name: str) -> tuple[str, str]:
    clean = name.lstrip("/")
    lower = clean.lower()
    parts = clean.split("/")

    if lower.startswith("lib/") and len(parts) >= 3:
        return "Native libraries", native_family(parts[-1])
    if re.fullmatch(r"classes(?:\d+)?\.dex", clean):
        return "DEX bytecode", "App and JVM dependency bytecode"
    if lower.startswith("assets/"):
        if is_ml_asset(clean):
            return "Bundled ML models/assets", "ML Kit models and metadata"
        return "Other assets", "Other assets"
    if lower.startswith("res/"):
        return "Android resources", "Compiled res/ files"
    if clean == "resources.arsc":
        return "Android resources", "Resource table"
    if clean == "AndroidManifest.xml":
        return "Android resources", "Binary manifest"
    if lower.startswith("meta-inf/"):
        return "Metadata/signing", "META-INF"
    if lower.startswith(("kotlin/", "okhttp3/")):
        return "JVM resources", "Library resources"
    return "Other packaged files", "Other"


def zip_rows(apk: Path):
    with zipfile.ZipFile(apk) as archive:
        return [entry for entry in archive.infolist() if not entry.is_dir()]


def category_totals(rows: Iterable[zipfile.ZipInfo], classifier):
    result: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])
    for row in rows:
        key = classifier(row)
        values = result[key]
        values[0] += row.compress_size
        values[1] += row.file_size
        values[2] += 1
    return result


def find_apkanalyzer(explicit: str | None) -> str | None:
    if explicit:
        return explicit
    from_path = shutil_which("apkanalyzer")
    if from_path:
        return from_path
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        return None
    candidates = sorted(Path(sdk).glob("cmdline-tools/*/bin/apkanalyzer"), reverse=True)
    return str(candidates[0]) if candidates else None


def shutil_which(name: str) -> str | None:
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        candidate = Path(directory) / name
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def dex_package_excerpt(tool: str | None, apk: Path) -> list[str]:
    if not tool:
        return []
    try:
        completed = subprocess.run(
            [tool, "dex", "packages", "--defined-only", str(apk)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=180,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        return [f"apkanalyzer could not read DEX packages: {error}"]

    # The package tree can contain thousands of class/member rows. Package rows
    # carry an inclusive byte-size column, so sorting them surfaces heavyweight
    # dependency namespaces regardless of where they occur in the tree output.
    package_rows = [line.rstrip() for line in completed.stdout.splitlines() if line.startswith("P ")]

    def byte_size(line: str) -> int:
        fields = line.split()
        try:
            return int(fields[-2])
        except (IndexError, ValueError):
            return 0

    return sorted(package_rows, key=byte_size, reverse=True)[:80]


def build_report(apk: Path, top_count: int, apkanalyzer: str | None) -> str:
    rows = zip_rows(apk)
    physical = apk.stat().st_size
    compressed_payload = sum(row.compress_size for row in rows)
    unpacked_payload = sum(row.file_size for row in rows)
    zip_overhead = max(0, physical - compressed_payload)

    categories = category_totals(rows, lambda row: classify(row.filename)[0])
    details = category_totals(rows, lambda row: classify(row.filename)[1])
    abi_totals = category_totals(
        (row for row in rows if row.filename.startswith("lib/") and len(row.filename.split("/")) >= 3),
        lambda row: row.filename.split("/")[1],
    )

    lines = [
        "# APK size report",
        "",
        f"APK: `{apk}`",
        "",
        "## Totals",
        "",
        f"- APK on disk/download: **{mib(physical)}** ({physical:,} bytes; {physical / 1_000_000:.2f} MB decimal)",
        f"- ZIP entry payload: **{mib(compressed_payload)}**",
        f"- Uncompressed ZIP payload: **{mib(unpacked_payload)}**",
        f"- ZIP headers/alignment/signing overhead: **{mib(zip_overhead)}**",
        f"- Packaged files: **{len(rows):,}**",
        "",
        "The percentages below use actual compressed bytes in the APK, which is the closest measure of download/APK cost.",
        "",
        "## Categories",
        "",
        "| Category | APK bytes | Share | Uncompressed | Files |",
        "|---|---:|---:|---:|---:|",
    ]
    for name, values in sorted(categories.items(), key=lambda item: item[1][0], reverse=True):
        lines.append(
            f"| {name} | {mib(values[0])} | {pct(values[0], physical)} | {mib(values[1])} | {values[2]:,} |"
        )

    lines.extend(
        [
            "",
            "## Cost centers",
            "",
            "| Cost center | APK bytes | Share | Uncompressed | Files |",
            "|---|---:|---:|---:|---:|",
        ]
    )
    for name, values in sorted(details.items(), key=lambda item: item[1][0], reverse=True):
        lines.append(
            f"| {name} | {mib(values[0])} | {pct(values[0], physical)} | {mib(values[1])} | {values[2]:,} |"
        )

    if abi_totals:
        lines.extend(
            [
                "",
                "## Native code by ABI",
                "",
                "A universal APK pays for every ABI below even though one device uses only one of them.",
                "",
                "| ABI | APK bytes | Share | Uncompressed | Files |",
                "|---|---:|---:|---:|---:|",
            ]
        )
        for name, values in sorted(abi_totals.items(), key=lambda item: item[1][0], reverse=True):
            lines.append(
                f"| `{name}` | {mib(values[0])} | {pct(values[0], physical)} | {mib(values[1])} | {values[2]:,} |"
            )

    lines.extend(
        [
            "",
            f"## Largest {top_count} packaged files",
            "",
            "| File | APK bytes | Uncompressed | Compression |",
            "|---|---:|---:|---:|",
        ]
    )
    for row in sorted(rows, key=lambda item: item.compress_size, reverse=True)[:top_count]:
        saved = 1 - (row.compress_size / row.file_size) if row.file_size else 0
        lines.append(
            f"| `{row.filename}` | {mib(row.compress_size)} | {mib(row.file_size)} | {saved:.0%} saved |"
        )

    dex_excerpt = dex_package_excerpt(apkanalyzer, apk)
    if dex_excerpt:
        lines.extend(
            [
                "",
                "## DEX package tree excerpt",
                "",
                "`apkanalyzer dex packages --defined-only` output (shallow package rows):",
                "",
                "```text",
                *dex_excerpt,
                "```",
            ]
        )

    return "\n".join(lines) + "\n"


def github_escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--top", type=int, default=30)
    parser.add_argument("--apkanalyzer")
    parser.add_argument("--github-annotation", action="store_true")
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error(f"APK does not exist: {args.apk}")

    tool = find_apkanalyzer(args.apkanalyzer)
    report = build_report(args.apk, max(1, args.top), tool)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
    print(report, end="")

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(report)
    if args.github_annotation:
        # GitHub truncates annotation messages in its API. Keep the DEX package
        # tree in a separate annotation so it remains retrievable even when the
        # main report contains a long largest-files table.
        compact = report[:50_000]
        print(f"::notice title=APK size report::{github_escape(compact)}")
        dex_excerpt = dex_package_excerpt(tool, args.apk)
        if dex_excerpt:
            dex_report = "apkanalyzer dex packages --defined-only\n\n" + "\n".join(dex_excerpt)
            print(f"::notice title=DEX package size report::{github_escape(dex_report)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
