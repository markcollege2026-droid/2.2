"""Tests for refunds: referencing captures, cumulative caps, misuse."""

from __future__ import annotations

import unittest

from transaction_system import (
    RejectionCode,
    Transaction,
    TransactionRejected,
    TransactionSystem,
    TransactionType,
    build_audit_summary,
)


class TestRefunds(unittest.TestCase):
    CATEGORY = "refunds"

    def setUp(self) -> None:
        self.sys = TransactionSystem(initial_balances={"acct-1": 0})
        self.sys.deposit("pay-100", "acct-1", 5_000)  # captured payment, $50.00

    def test_refund_debits_held_funds(self) -> None:
        # The capture credited the account (5000); a refund sends those held
        # funds back to the payer, so the held balance drops by the refund.
        entry = self.sys.refund("ref-1", "acct-1", 1_500, "pay-100")
        self.assertEqual(entry.type, TransactionType.REFUND)
        self.assertEqual(entry.payment_id, "pay-100")
        self.assertEqual(self.sys.balance("acct-1"), 3_500)
        self.assertEqual(entry.balance_after_cents, 3_500)
        self.assertEqual(self.sys.refunded_for("pay-100"), 1_500)

    def test_full_refund_allowed(self) -> None:
        self.sys.refund("ref-2", "acct-1", 5_000, "pay-100")
        self.assertEqual(self.sys.balance("acct-1"), 0)

    def test_cumulative_refunds_may_not_exceed_capture(self) -> None:
        self.sys.refund("ref-3", "acct-1", 3_000, "pay-100")
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.refund("ref-4", "acct-1", 2_001, "pay-100")
        self.assertEqual(ctx.exception.code, RejectionCode.REFUND_EXCEEDS_PAYMENT)
        self.assertEqual(self.sys.refunded_for("pay-100"), 3_000)
        self.assertEqual(self.sys.balance("acct-1"), 2_000)

    def test_exact_remaining_refund_allowed_then_blocked(self) -> None:
        self.sys.refund("ref-5", "acct-1", 2_000, "pay-100")
        self.sys.refund("ref-6", "acct-1", 3_000, "pay-100")
        self.assertEqual(self.sys.refunded_for("pay-100"), 5_000)
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.refund("ref-7", "acct-1", 1, "pay-100")
        self.assertEqual(ctx.exception.code, RejectionCode.REFUND_EXCEEDS_PAYMENT)

    def test_refund_referencing_nonexistent_payment_rejected(self) -> None:
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.refund("ref-8", "acct-1", 100, "does-not-exist")
        self.assertEqual(ctx.exception.code, RejectionCode.PAYMENT_NOT_FOUND)
        self.assertEqual(self.sys.balance("acct-1"), 5_000)

    def test_refund_referencing_a_withdrawal_rejected(self) -> None:
        self.sys.withdraw("wd-1", "acct-1", 1_000)
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.refund("ref-9", "acct-1", 100, "wd-1")
        self.assertEqual(ctx.exception.code, RejectionCode.PAYMENT_NOT_FOUND)

    def test_refund_referencing_a_refund_rejected(self) -> None:
        self.sys.refund("ref-10", "acct-1", 1_000, "pay-100")
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.refund("ref-11", "acct-1", 100, "ref-10")
        self.assertEqual(ctx.exception.code, RejectionCode.PAYMENT_NOT_FOUND)

    def test_refund_without_payment_reference_rejected(self) -> None:
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.process(Transaction("ref-12", TransactionType.REFUND, 100, "acct-1"))
        self.assertEqual(ctx.exception.code, RejectionCode.MISSING_PAYMENT_REFERENCE)

    def test_refund_to_wrong_account_rejected(self) -> None:
        self.sys.deposit("pay-200", "acct-2", 1_000)
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.refund("ref-13", "acct-1", 100, "pay-200")
        self.assertEqual(ctx.exception.code, RejectionCode.PAYMENT_NOT_REFUNDABLE)

    def test_refund_of_zero_or_negative_rejected(self) -> None:
        for bad in (0, -100):
            with self.subTest(bad=bad):
                with self.assertRaises(TransactionRejected) as ctx:
                    self.sys.refund(f"ref-bad-{bad}", "acct-1", bad, "pay-100")
                self.assertEqual(ctx.exception.code, RejectionCode.INVALID_AMOUNT)

    def test_refund_cannot_exceed_funds_held_after_withdrawal(self) -> None:
        # Business rule: only funds still held can be sent back to the payer, so
        # withdrawing first consumes refundable capacity on the balance side
        # (the per-capture cap is unchanged).
        self.sys.withdraw("wd-2", "acct-1", 5_000)
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.refund("ref-15", "acct-1", 1_000, "pay-100")
        self.assertEqual(ctx.exception.code, RejectionCode.INSUFFICIENT_FUNDS)
        self.assertEqual(self.sys.refunded_for("pay-100"), 0)

    def test_cap_is_checked_before_balance(self) -> None:
        # After refunding 4500 the remaining refundable amount is 500 and the
        # held balance is also 500.  A 600 refund breaks both rules; the capture
        # cap is reported because it is the more specific diagnosis.
        self.sys.refund("ref-16a", "acct-1", 4_500, "pay-100")
        self.assertEqual(self.sys.balance("acct-1"), 500)
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.refund("ref-16", "acct-1", 600, "pay-100")
        self.assertEqual(ctx.exception.code, RejectionCode.REFUND_EXCEEDS_PAYMENT)

    def test_audit_reconciles_refunds(self) -> None:
        self.sys.refund("ref-14", "acct-1", 1_234, "pay-100")
        summary = build_audit_summary(self.sys)
        self.assertTrue(summary["invariants_ok"], summary["invariants"])
        bucket = summary["payments"]["pay-100"]
        self.assertEqual(bucket["captured_cents"], 5_000)
        self.assertEqual(bucket["refunded_cents"], 1_234)
        self.assertEqual(bucket["remaining_cents"], 3_766)


if __name__ == "__main__":
    unittest.main()
