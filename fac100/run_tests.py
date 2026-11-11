#!/usr/bin/env python3
"""fac100 test entry point.

Runs the stdlib ``unittest`` suite in-process (no third-party dependencies),
captures per-test outcomes, writes ``test_results.csv`` and evidence files, and
exits non-zero if anything failed.

Usage:
    python3 run_tests.py
    python3 run_tests.py --repeat-concurrency 5
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import platform
import sys
import time
import unittest
from typing import Dict, List

HERE = os.path.dirname(os.path.abspath(__file__))
PACKAGE_DIR = os.path.join(HERE, "transaction_system")
TESTS_DIR = os.path.join(PACKAGE_DIR, "tests")
EVIDENCE_DIR = os.path.join(HERE, "evidence")
CSV_PATH = os.path.join(HERE, "test_results.csv")

MODULE_CATEGORIES = {
    "test_money": "integer_precision",
    "test_transactions": "core_transactions",
    "test_refunds": "refunds",
    "test_atomicity": "atomicity",
    "test_concurrency": "concurrency",
}

MODULE_REQUIREMENTS = {
    "test_money": ["integer-precision", "invalid-inputs"],
    "test_transactions": ["normal-deposits-withdrawals", "duplicate-ids", "invalid-inputs",
                          "insufficient-funds", "history"],
    "test_refunds": ["excessive-refunds", "nonexistent-payment-reference"],
    "test_atomicity": ["rejected-leaves-state-unchanged"],
    "test_concurrency": ["concurrent-processing"],
}


class RecordingResult(unittest.TextTestResult):
    """Collects a row per test for the CSV report."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.rows: List[Dict[str, object]] = []
        self._started: Dict[str, float] = {}

    def startTest(self, test: unittest.TestCase) -> None:  # noqa: N802 (unittest API)
        self._started[test.id()] = time.perf_counter()
        super().startTest(test)

    def _row(self, test: unittest.TestCase, outcome: str, message: str = "") -> None:
        test_id = test.id()
        started = self._started.get(test_id, time.perf_counter())
        module = test.__class__.__module__.split(".")[-1]
        category = getattr(test, "CATEGORY", MODULE_CATEGORIES.get(module, "other"))
        self.rows.append(
            {
                "test_id": test_id,
                "module": module,
                "test_name": getattr(test, "_testMethodName", ""),
                "category": category,
                "outcome": outcome,
                "duration_ms": round((time.perf_counter() - started) * 1000, 3),
                "message": " ".join(str(message).split())[:500],
                "requirement": ";".join(MODULE_REQUIREMENTS.get(module, [])),
            }
        )

    def addSuccess(self, test):  # noqa: N802
        super().addSuccess(test)
        self._row(test, "PASS")

    def addFailure(self, test, err):  # noqa: N802
        super().addFailure(test, err)
        self._row(test, "FAIL", self._exc_info_to_string(err, test))

    def addError(self, test, err):  # noqa: N802
        super().addError(test, err)
        self._row(test, "ERROR", self._exc_info_to_string(err, test))

    def addSkip(self, test, reason):  # noqa: N802
        super().addSkip(test, reason)
        self._row(test, "SKIP", reason)

    def addExpectedFailure(self, test, err):  # noqa: N802
        super().addExpectedFailure(test, err)
        self._row(test, "EXPECTED_FAILURE")

    def addUnexpectedSuccess(self, test):  # noqa: N802
        super().addUnexpectedSuccess(test)
        self._row(test, "UNEXPECTED_SUCCESS")


class Tee(io.TextIOBase):
    """Duplicate stdout into a log file so evidence survives the terminal."""

    def __init__(self, *streams: io.TextIOBase) -> None:
        self.streams = streams

    def write(self, data: str) -> int:  # type: ignore[override]
        for stream in self.streams:
            stream.write(data)
        return len(data)

    def flush(self) -> None:  # type: ignore[override]
        for stream in self.streams:
            stream.flush()


def build_suite(repeat_concurrency: int) -> unittest.TestSuite:
    loader = unittest.TestLoader()
    loader.sortTestMethodsUsing = None  # keep declaration order for readable logs
    suite = loader.discover(start_dir=TESTS_DIR, pattern="test_*.py", top_level_dir=PACKAGE_DIR)
    if repeat_concurrency > 1:
        # Re-import the concurrency module directly and repeat it, so flaky
        # lock behaviour would surface as failures in the CSV.
        from tests import test_concurrency  # type: ignore

        extra = unittest.TestSuite()
        for _ in range(repeat_concurrency - 1):
            extra.addTests(loader.loadTestsFromModule(test_concurrency))
        suite.addTests(extra)
    return suite


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the fac100 transaction-system tests")
    parser.add_argument("--repeat-concurrency", type=int, default=1,
                        help="run the concurrency module N times to probe flakiness")
    parser.add_argument("--quiet", action="store_true", help="suppress verbose test output")
    args = parser.parse_args(argv)

    os.makedirs(EVIDENCE_DIR, exist_ok=True)
    log_path = os.path.join(EVIDENCE_DIR, "unittest_run.txt")
    if PACKAGE_DIR not in sys.path:
        sys.path.insert(0, PACKAGE_DIR)

    with open(log_path, "w", encoding="utf-8") as log_file:
        tee = Tee(sys.stdout, log_file)
        real_stdout = sys.stdout
        sys.stdout = tee  # type: ignore[assignment]
        try:
            print("=" * 78)
            print("fac100 transaction-system test run")
            print(f"python     : {platform.python_version()} ({platform.platform()})")
            print(f"started_utc: {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}")
            print(f"suite root : {TESTS_DIR}")
            print(f"repeat_concurrency: {args.repeat_concurrency}")
            print("=" * 78)
            runner = unittest.TextTestRunner(
                stream=tee,
                verbosity=1 if args.quiet else 2,
                resultclass=RecordingResult,
            )
            suite = build_suite(max(1, args.repeat_concurrency))
            started = time.perf_counter()
            result: RecordingResult = runner.run(suite)  # type: ignore[assignment]
            elapsed = time.perf_counter() - started
        finally:
            sys.stdout = real_stdout

    rows = result.rows
    fieldnames = ["test_id", "module", "test_name", "category", "outcome",
                  "duration_ms", "message", "requirement"]
    with open(CSV_PATH, "w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)

    passed = sum(1 for r in rows if r["outcome"] == "PASS")
    failed = sum(1 for r in rows if r["outcome"] in ("FAIL", "ERROR", "UNEXPECTED_SUCCESS"))
    skipped = sum(1 for r in rows if r["outcome"] == "SKIP")
    by_category: Dict[str, Dict[str, int]] = {}
    for row in rows:
        bucket = by_category.setdefault(str(row["category"]), {"pass": 0, "fail": 0})
        if row["outcome"] == "PASS":
            bucket["pass"] += 1
        elif row["outcome"] in ("FAIL", "ERROR", "UNEXPECTED_SUCCESS"):
            bucket["fail"] += 1

    summary = {
        "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "python_version": platform.python_version(),
        "platform": platform.platform(),
        "repeat_concurrency": args.repeat_concurrency,
        "tests_run": len(rows),
        "passed": passed,
        "failed": failed,
        "skipped": skipped,
        "unittest_testsRun": result.testsRun,
        "unittest_failures": len(result.failures),
        "unittest_errors": len(result.errors),
        "duration_s": round(elapsed, 3),
        "by_category": by_category,
        "exit_code": 0 if (failed == 0 and result.wasSuccessful()) else 1,
        "csv_path": os.path.relpath(CSV_PATH, HERE),
        "log_path": os.path.relpath(log_path, HERE),
        "failed_tests": [r["test_id"] for r in rows
                         if r["outcome"] in ("FAIL", "ERROR", "UNEXPECTED_SUCCESS")],
    }
    with open(os.path.join(EVIDENCE_DIR, "test_summary.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)

    print("=" * 78)
    print(f"tests={len(rows)} passed={passed} failed={failed} skipped={skipped} "
          f"duration={elapsed:.2f}s")
    print(f"csv      : {os.path.relpath(CSV_PATH, HERE)}")
    print(f"log      : {os.path.relpath(log_path, HERE)}")
    print(f"summary  : evidence/test_summary.json")
    print(f"RESULT   : {'PASS' if summary['exit_code'] == 0 else 'FAIL'}")
    return int(summary["exit_code"])


if __name__ == "__main__":
    sys.exit(main())
