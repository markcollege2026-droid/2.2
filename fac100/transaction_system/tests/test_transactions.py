"""Tests for deposits, withdrawals, duplicates, invalid input and history."""

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


class TestDepositsAndWithdrawals(unittest.TestCase):
    CATEGORY = "core_transactions"

    def setUp(self) -> None:
        self.sys = TransactionSystem(initial_balances={"acct-1": 10_000})

    def test_deposit_credits_integer_cents(self) -> None:
        entry = self.sys.deposit("t-1", "acct-1", 2_500)
        self.assertEqual(entry.balance_after_cents, 12_500)
        self.assertEqual(self.sys.balance("acct-1"), 12_500)

    def test_withdrawal_debits(self) -> None:
        entry = self.sys.withdraw("t-2", "acct-1", 3_000)
        self.assertEqual(entry.balance_after_cents, 7_000)

    def test_withdrawal_of_entire_balance_allowed(self) -> None:
        self.sys.withdraw("t-3", "acct-1", 10_000)
        self.assertEqual(self.sys.balance("acct-1"), 0)

    def test_deposit_creates_new_account(self) -> None:
        self.sys.deposit("t-4", "acct-new", 500)
        self.assertEqual(self.sys.balance("acct-new"), 500)

    def test_history_preserves_order_and_balances(self) -> None:
        self.sys.deposit("t-5", "acct-1", 1_000)
        self.sys.withdraw("t-6", "acct-1", 4_000)
        self.sys.deposit("t-7", "acct-1", 250)
        ledger = self.sys.ledger
        self.assertEqual([e.txn_id for e in ledger], ["t-5", "t-6", "t-7"])
        self.assertEqual([e.sequence for e in ledger], [1, 2, 3])
        self.assertEqual([e.balance_after_cents for e in ledger], [11_000, 7_000, 7_250])
        self.assertEqual([e.type for e in ledger],
                         [TransactionType.DEPOSIT, TransactionType.WITHDRAWAL,
                          TransactionType.DEPOSIT])

    def test_audit_summary_invariants_hold(self) -> None:
        self.sys.deposit("t-8", "acct-1", 1_000)
        self.sys.withdraw("t-9", "acct-1", 500)
        summary = build_audit_summary(self.sys)
        self.assertTrue(summary["invariants_ok"], summary["invariants"])
        self.assertEqual(summary["totals_cents"]["DEPOSIT"], 1_000)  # ledger only
        self.assertEqual(summary["initial_total_cents"], 10_000)
        self.assertEqual(summary["totals_cents"]["WITHDRAWAL"], 500)
        self.assertEqual(summary["total_balance_cents"], 10_500)


class TestDuplicateIds(unittest.TestCase):
    CATEGORY = "duplicates"

    def setUp(self) -> None:
        self.sys = TransactionSystem(initial_balances={"acct-1": 10_000})

    def test_duplicate_deposit_rejected(self) -> None:
        self.sys.deposit("dup-1", "acct-1", 1_000)
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.deposit("dup-1", "acct-1", 1_000)
        self.assertEqual(ctx.exception.code, RejectionCode.DUPLICATE_TRANSACTION_ID)
        self.assertEqual(self.sys.balance("acct-1"), 11_000)
        self.assertEqual(len(self.sys.ledger), 1)

    def test_duplicate_id_across_different_types_rejected(self) -> None:
        self.sys.deposit("dup-2", "acct-1", 1_000)
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.withdraw("dup-2", "acct-1", 1_000)
        self.assertEqual(ctx.exception.code, RejectionCode.DUPLICATE_TRANSACTION_ID)
        self.assertEqual(self.sys.balance("acct-1"), 11_000)

    def test_duplicate_refund_rejected(self) -> None:
        self.sys.deposit("pay-1", "acct-1", 5_000)
        self.sys.refund("ref-1", "acct-1", 1_000, "pay-1")
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.refund("ref-1", "acct-1", 1_000, "pay-1")
        self.assertEqual(ctx.exception.code, RejectionCode.DUPLICATE_TRANSACTION_ID)
        self.assertEqual(self.sys.refunded_for("pay-1"), 1_000)

    def test_rejected_id_is_not_consumed_and_can_be_retried(self) -> None:
        # Documented assumption: only *committed* ids are burned.
        with self.assertRaises(TransactionRejected):
            self.sys.withdraw("retry-1", "acct-1", 99_999)
        entry = self.sys.withdraw("retry-1", "acct-1", 1_000)
        self.assertEqual(entry.balance_after_cents, 9_000)
        self.assertTrue(self.sys.is_committed("retry-1"))


class TestInvalidInput(unittest.TestCase):
    CATEGORY = "invalid_input"

    def setUp(self) -> None:
        self.sys = TransactionSystem(initial_balances={"acct-1": 10_000})

    def test_invalid_transaction_types_rejected(self) -> None:
        for bad in ("TRANSFER", "PAYOUT", "", None, 42, 3.5, ["DEPOSIT"]):
            with self.subTest(bad=bad):
                txn = Transaction("bad-type", bad, 100, "acct-1")
                with self.assertRaises(TransactionRejected) as ctx:
                    self.sys.process(txn)
                self.assertEqual(ctx.exception.code, RejectionCode.INVALID_TRANSACTION_TYPE)
        self.assertEqual(len(self.sys.ledger), 0)
        self.assertEqual(self.sys.balance("acct-1"), 10_000)

    def test_lowercase_type_is_normalised(self) -> None:
        txn = Transaction("ok-type", "deposit", 100, "acct-1")
        self.assertEqual(self.sys.process(txn).type, TransactionType.DEPOSIT)

    def test_zero_and_negative_amounts_rejected(self) -> None:
        for bad in (0, -1, -1_000):
            with self.subTest(bad=bad):
                with self.assertRaises(TransactionRejected) as ctx:
                    self.sys.deposit(f"zero-{bad}", "acct-1", bad)
                self.assertEqual(ctx.exception.code, RejectionCode.INVALID_AMOUNT)
        self.assertEqual(len(self.sys.ledger), 0)

    def test_float_amount_rejected_not_rounded(self) -> None:
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.deposit("float-1", "acct-1", 10.5)
        self.assertEqual(ctx.exception.code, RejectionCode.INVALID_AMOUNT)
        self.assertEqual(self.sys.balance("acct-1"), 10_000)

    def test_bool_amount_rejected(self) -> None:
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.deposit("bool-1", "acct-1", True)
        self.assertEqual(ctx.exception.code, RejectionCode.INVALID_AMOUNT)

    def test_blank_ids_rejected_at_construction(self) -> None:
        with self.assertRaises(ValueError):
            Transaction("", TransactionType.DEPOSIT, 100, "acct-1")
        with self.assertRaises(ValueError):
            Transaction("t", TransactionType.DEPOSIT, 100, "  ")


class TestInsufficientFunds(unittest.TestCase):
    CATEGORY = "insufficient_funds"

    def setUp(self) -> None:
        self.sys = TransactionSystem(initial_balances={"acct-1": 1_000})

    def test_overdraft_rejected(self) -> None:
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.withdraw("w-1", "acct-1", 1_001)
        self.assertEqual(ctx.exception.code, RejectionCode.INSUFFICIENT_FUNDS)
        self.assertEqual(self.sys.balance("acct-1"), 1_000)

    def test_unknown_account_withdrawal_rejected(self) -> None:
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.withdraw("w-2", "ghost", 1)
        self.assertEqual(ctx.exception.code, RejectionCode.INVALID_ACCOUNT)

    def test_repeated_withdrawals_cannot_go_negative(self) -> None:
        self.sys.withdraw("w-0", "acct-1", 400)
        self.sys.withdraw("w-1", "acct-1", 400)
        self.assertEqual(self.sys.balance("acct-1"), 200)
        with self.assertRaises(TransactionRejected) as ctx:
            self.sys.withdraw("w-2", "acct-1", 400)
        self.assertEqual(ctx.exception.code, RejectionCode.INSUFFICIENT_FUNDS)
        self.assertEqual(self.sys.balance("acct-1"), 200)


class TestDepositWithPaymentRef(unittest.TestCase):
    CATEGORY = "invalid_input"

    def test_deposit_with_payment_reference_rejected(self) -> None:
        sys = TransactionSystem()
        with self.assertRaises(TransactionRejected) as ctx:
            sys.process(Transaction("d-1", TransactionType.DEPOSIT, 100, "acct-1", "pay-x"))
        self.assertEqual(ctx.exception.code, RejectionCode.MISSING_PAYMENT_REFERENCE)


if __name__ == "__main__":
    unittest.main()
