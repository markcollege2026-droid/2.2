"""Concurrency tests.

These are meaningful here because the engine is in-process and the critical
section is a real lock: without it, these tests fail (overdrafts and duplicate
committed ids appear).
"""

from __future__ import annotations

import sys
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, List

from transaction_system import (
    RejectionCode,
    TransactionRejected,
    TransactionSystem,
    build_audit_summary,
)


def run_concurrently(workers: int, fn: Callable[[int], object]) -> List[object]:
    """Start ``workers`` calls as simultaneously as the GIL allows."""
    barrier = threading.Barrier(workers)
    results: List[object] = []
    lock = threading.Lock()

    def wrapped(index: int) -> None:
        barrier.wait(timeout=10)
        try:
            outcome = fn(index)
        except TransactionRejected as exc:
            outcome = exc
        with lock:
            results.append(outcome)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        for future in [pool.submit(wrapped, i) for i in range(workers)]:
            future.result()
    return results


class TestConcurrency(unittest.TestCase):
    CATEGORY = "concurrency"

    def setUp(self) -> None:
        # Force frequent thread switches (default is 5 ms) so that a
        # check-then-act race inside the engine is actually scheduled.  Without
        # this the GIL can hide a missing lock on small workloads.
        self._old_interval = sys.getswitchinterval()
        sys.setswitchinterval(1e-6)
        self.addCleanup(sys.setswitchinterval, self._old_interval)

    def test_concurrent_withdrawals_never_overdraw(self) -> None:
        sys = TransactionSystem(initial_balances={"acct-1": 10_000})
        workers = 40
        results = run_concurrently(
            workers, lambda i: sys.withdraw(f"w-{i}", "acct-1", 1_000)
        )
        successes = [r for r in results if not isinstance(r, TransactionRejected)]
        failures = [r for r in results if isinstance(r, TransactionRejected)]
        self.assertEqual(len(successes), 10)
        self.assertEqual(len(failures), 30)
        self.assertEqual(sys.balance("acct-1"), 0)
        self.assertGreaterEqual(sys.balance("acct-1"), 0)
        self.assertTrue(all(f.code is RejectionCode.INSUFFICIENT_FUNDS for f in failures))
        self.assertTrue(build_audit_summary(sys)["invariants_ok"])

    def test_concurrent_duplicate_ids_commit_once(self) -> None:
        sys = TransactionSystem()
        workers = 32
        results = run_concurrently(
            workers, lambda i: sys.deposit("same-id", "acct-1", 1_000)
        )
        successes = [r for r in results if not isinstance(r, TransactionRejected)]
        failures = [r for r in results if isinstance(r, TransactionRejected)]
        self.assertEqual(len(successes), 1, "exactly one submission may commit")
        self.assertEqual(len(failures), workers - 1)
        self.assertTrue(
            all(f.code is RejectionCode.DUPLICATE_TRANSACTION_ID for f in failures)
        )
        self.assertEqual(sys.balance("acct-1"), 1_000)
        self.assertEqual(len(sys.ledger), 1)

    def test_concurrent_refunds_respect_capture_cap(self) -> None:
        sys = TransactionSystem()
        sys.deposit("pay-1", "acct-1", 5_000)
        workers = 24
        results = run_concurrently(
            workers, lambda i: sys.refund(f"r-{i}", "acct-1", 400, "pay-1")
        )
        successes = [r for r in results if not isinstance(r, TransactionRejected)]
        failures = [r for r in results if isinstance(r, TransactionRejected)]
        self.assertEqual(len(successes), 12)  # floor(5000 / 400)
        self.assertEqual(len(failures), workers - 12)
        self.assertEqual(sys.refunded_for("pay-1"), 4_800)
        self.assertLessEqual(sys.refunded_for("pay-1"), 5_000)
        self.assertEqual(sys.balance("acct-1"), 200)  # 5000 captured - 4800 refunded
        self.assertTrue(
            all(f.code is RejectionCode.REFUND_EXCEEDS_PAYMENT for f in failures)
        )
        self.assertTrue(build_audit_summary(sys)["invariants_ok"])

    def test_mixed_concurrent_workload_keeps_invariants(self) -> None:
        sys = TransactionSystem(initial_balances={"acct-1": 50_000})

        def work(i: int) -> object:
            kind = i % 4
            if kind == 0:
                return sys.deposit(f"mix-{i}", "acct-1", 700)
            if kind == 1:
                return sys.withdraw(f"mix-{i}", "acct-1", 900)
            if kind == 2:
                return sys.refund(f"mix-{i}", "acct-1", 100, f"mix-{i - 2}")
            return sys.withdraw(f"mix-{i}", "acct-1", 20_000)

        run_concurrently(40, work)
        summary = build_audit_summary(sys)
        self.assertTrue(summary["invariants_ok"], summary["invariants"])
        self.assertGreaterEqual(sys.balance("acct-1"), 0)
        ledger = sys.ledger
        self.assertEqual(len({e.txn_id for e in ledger}), len(ledger))


if __name__ == "__main__":
    unittest.main()


class _SlowCommitSystem(TransactionSystem):
    """Widens the validate->commit window so a missing lock is unmistakable.

    With the real lock, threads serialise and the result is still correct (just
    slower).  Without a lock, every thread reads the same pre-mutation balance
    and commits, so overdraw is guaranteed rather than merely likely.
    """

    WINDOW_SECONDS = 0.002

    def _before_commit(self, txn) -> None:  # noqa: ANN001 - test seam
        time.sleep(self.WINDOW_SECONDS)


class TestLockIsLoadBearing(unittest.TestCase):
    CATEGORY = "concurrency"

    def setUp(self) -> None:
        self._old_interval = sys.getswitchinterval()
        sys.setswitchinterval(1e-6)
        self.addCleanup(sys.setswitchinterval, self._old_interval)

    def test_wide_window_withdrawals_cannot_overdraw(self) -> None:
        sys = _SlowCommitSystem(initial_balances={"acct-1": 10_000})
        workers = 20
        results = run_concurrently(
            workers, lambda i: sys.withdraw(f"wide-{i}", "acct-1", 1_000)
        )
        successes = [r for r in results if not isinstance(r, TransactionRejected)]
        failures = [r for r in results if isinstance(r, TransactionRejected)]
        self.assertEqual(len(successes), 10, f"overdraw not prevented: {len(successes)} succeeded")
        self.assertEqual(len(failures), workers - 10)
        self.assertEqual(sys.balance("acct-1"), 0)
        self.assertTrue(build_audit_summary(sys)["invariants_ok"])

    def test_wide_window_duplicate_ids_commit_once(self) -> None:
        sys = _SlowCommitSystem()
        results = run_concurrently(12, lambda i: sys.deposit("wide-dup", "acct-1", 500))
        successes = [r for r in results if not isinstance(r, TransactionRejected)]
        self.assertEqual(len(successes), 1)
        self.assertEqual(sys.balance("acct-1"), 500)
