"""Atomicity: a rejected transaction must leave state bit-for-bit unchanged."""

from __future__ import annotations

import unittest

from transaction_system import (
    RejectionCode,
    Transaction,
    TransactionRejected,
    TransactionSystem,
    build_audit_summary,
)


def snapshot(system: TransactionSystem) -> dict:
    """Full observable state, excluding the (deliberately growing) rejection log."""
    return {
        "balances": system.balances(),
        "ledger": [e.as_dict() for e in system.ledger],
        "refunded": {e.txn_id: system.refunded_for(e.txn_id) for e in system.ledger},
    }


class TestAtomicity(unittest.TestCase):
    CATEGORY = "atomicity"

    def setUp(self) -> None:
        self.sys = TransactionSystem(initial_balances={"acct-1": 10_000})
        self.sys.deposit("pay-1", "acct-1", 5_000)
        self.sys.refund("ref-1", "acct-1", 1_000, "pay-1")
        self.before = snapshot(self.sys)

    def _assert_unchanged(self) -> None:
        self.assertEqual(snapshot(self.sys), self.before)

    def test_rejected_duplicate_leaves_state_unchanged(self) -> None:
        with self.assertRaises(TransactionRejected):
            self.sys.deposit("pay-1", "acct-1", 5_000)
        self._assert_unchanged()

    def test_rejected_overdraft_leaves_state_unchanged(self) -> None:
        with self.assertRaises(TransactionRejected):
            self.sys.withdraw("w-bad", "acct-1", 10**9)
        self._assert_unchanged()

    def test_rejected_over_refund_leaves_state_and_tally_unchanged(self) -> None:
        with self.assertRaises(TransactionRejected):
            self.sys.refund("ref-bad", "acct-1", 4_001, "pay-1")
        self._assert_unchanged()
        self.assertEqual(self.sys.refunded_for("pay-1"), 1_000)

    def test_rejected_invalid_amount_leaves_state_unchanged(self) -> None:
        with self.assertRaises(TransactionRejected):
            self.sys.deposit("bad-amount", "acct-1", -500)
        self._assert_unchanged()

    def test_rejected_invalid_type_leaves_state_unchanged(self) -> None:
        with self.assertRaises(TransactionRejected):
            self.sys.process(Transaction("bad-type", "CHARGEBACK", 100, "acct-1"))
        self._assert_unchanged()

    def test_rejection_is_recorded_but_not_in_ledger(self) -> None:
        ledger_len = len(self.sys.ledger)
        with self.assertRaises(TransactionRejected):
            self.sys.withdraw("w-bad-2", "acct-1", 99_999)
        self.assertEqual(len(self.sys.ledger), ledger_len)
        self.assertEqual(len(self.sys.rejections), 1)
        self.assertEqual(self.sys.rejections[0].code,
                         RejectionCode.INSUFFICIENT_FUNDS.value)
        self.assertEqual(self.sys.rejections[0].txn_id, "w-bad-2")

    def test_sequence_numbers_are_contiguous_after_rejections(self) -> None:
        for i in range(5):
            with self.assertRaises(TransactionRejected):
                self.sys.withdraw(f"w-x-{i}", "acct-1", 10**9)
        self.sys.deposit("pay-2", "acct-1", 100)
        sequences = [e.sequence for e in self.sys.ledger]
        self.assertEqual(sequences, list(range(1, len(sequences) + 1)))

    def test_failed_processing_does_not_corrupt_audit_invariants(self) -> None:
        for attempt in (
            Transaction("x-1", "BOGUS", 100, "acct-1"),
            Transaction("x-2", "WITHDRAWAL", 10**9, "acct-1"),
            Transaction("x-3", "REFUND", 10**9, "acct-1", "pay-1"),
            Transaction("x-4", "DEPOSIT", 0, "acct-1"),
        ):
            with self.assertRaises(TransactionRejected):
                self.sys.process(attempt)
        summary = build_audit_summary(self.sys)
        self.assertTrue(summary["invariants_ok"], summary["invariants"])
        self.assertEqual(summary["rejected_transactions"], 4)

    def test_ledger_entries_are_immutable(self) -> None:
        entry = self.sys.ledger[0]
        with self.assertRaises(Exception):
            entry.amount_cents = 1  # frozen dataclass


if __name__ == "__main__":
    unittest.main()
