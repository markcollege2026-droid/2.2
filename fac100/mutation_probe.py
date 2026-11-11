#!/usr/bin/env python3
"""Mutation probe: prove the concurrency tests are not vacuous.

This deliberately breaks the engine in a temporary way (a no-op lock, i.e. no
mutual exclusion) and re-runs the concurrency test module.  A test suite that
still passes with the lock removed would be worthless, so this script asserts
that failures DO occur, then exits 0 only if the mutation was detected.

Nothing on disk is modified: the lock is replaced in memory for this process.
Also probes a second mutation: refund cap disabled (should be caught by the
refund tests).

Usage: python3 mutation_probe.py
"""

from __future__ import annotations

import contextlib
import io
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PACKAGE_DIR = os.path.join(HERE, "transaction_system")
TESTS_DIR = os.path.join(PACKAGE_DIR, "tests")
sys.path.insert(0, PACKAGE_DIR)

from tests import test_concurrency, test_refunds  # noqa: E402
from transaction_system import TransactionSystem  # noqa: E402


class _NullLock:
    """A lock that locks nothing - the classic missing-critical-section bug."""

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False

    def acquire(self, *args, **kwargs):
        return True

    def release(self):
        return None


@contextlib.contextmanager
def no_lock():
    original = TransactionSystem.__init__

    def patched_init(self, *args, **kwargs):
        original(self, *args, **kwargs)
        self._lock = _NullLock()

    TransactionSystem.__init__ = patched_init
    try:
        yield
    finally:
        TransactionSystem.__init__ = original


def run_module(module) -> unittest.result.TestResult:
    loader = unittest.TestLoader()
    suite = loader.loadTestsFromModule(module)
    stream = io.StringIO()
    result = unittest.TextTestRunner(stream=stream, verbosity=0).run(suite)
    return result


def main() -> int:
    print("MUTATION PROBE (tests must catch a deliberately broken engine)")
    print("=" * 70)

    print("\n[1] baseline: concurrency tests with the real lock")
    baseline = run_module(test_concurrency)
    print(f"    ran={baseline.testsRun} failures={len(baseline.failures)} "
          f"errors={len(baseline.errors)}")
    baseline_ok = baseline.wasSuccessful()

    print("\n[2] mutation A: lock replaced by a no-op (no mutual exclusion)")
    with no_lock():
        mutated = run_module(test_concurrency)
    detected = not mutated.wasSuccessful()
    print(f"    ran={mutated.testsRun} failures={len(mutated.failures)} "
          f"errors={len(mutated.errors)}")
    for test, tb in list(mutated.failures) + list(mutated.errors):
        last = [ln for ln in tb.strip().splitlines() if ln.strip()][-1]
        print(f"    detected by: {test.id().split('.')[-1]} -> {last[:110]}")
    print(f"    mutation detected: {detected}")

    print("\n[3] mutation B: refund cap check disabled")
    original_refunded = TransactionSystem._process_locked

    def uncapped(self, txn):
        # Simulates "forgot to account for earlier refunds": the cumulative
        # tally is reset before every refund, so only the single-refund bound
        # remains.  Everything else is untouched.
        for pid in list(self._refunded_by_payment):
            self._refunded_by_payment[pid] = 0
        return original_refunded(self, txn)

    TransactionSystem._process_locked = uncapped
    try:
        refund_mutated = run_module(test_refunds)
    finally:
        TransactionSystem._process_locked = original_refunded
    refund_detected = not refund_mutated.wasSuccessful()
    print(f"    ran={refund_mutated.testsRun} failures={len(refund_mutated.failures)} "
          f"errors={len(refund_mutated.errors)}")
    for test, tb in list(refund_mutated.failures) + list(refund_mutated.errors):
        last = [ln for ln in tb.strip().splitlines() if ln.strip()][-1]
        print(f"    detected by: {test.id().split('.')[-1]} -> {last[:110]}")
    print(f"    mutation detected: {refund_detected}")

    print("\n" + "=" * 70)
    print(f"baseline_suite_passed={baseline_ok}")
    print(f"mutation_A_no_lock_detected={detected}")
    print(f"mutation_B_refund_cap_detected={refund_detected}")
    ok = baseline_ok and detected and refund_detected
    print(f"RESULT: {'PASS - the suite detects real regressions' if ok else 'INCONCLUSIVE'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
