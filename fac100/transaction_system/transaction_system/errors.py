"""Rejection types and machine-readable rejection codes."""

from __future__ import annotations

from enum import Enum
from typing import Optional


class RejectionCode(str, Enum):
    """Stable codes used in the audit trail and in tests."""

    INVALID_TRANSACTION_TYPE = "INVALID_TRANSACTION_TYPE"
    INVALID_AMOUNT = "INVALID_AMOUNT"
    INVALID_TRANSACTION_ID = "INVALID_TRANSACTION_ID"
    INVALID_ACCOUNT = "INVALID_ACCOUNT"
    DUPLICATE_TRANSACTION_ID = "DUPLICATE_TRANSACTION_ID"
    INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS"
    PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND"
    PAYMENT_NOT_REFUNDABLE = "PAYMENT_NOT_REFUNDABLE"
    REFUND_EXCEEDS_PAYMENT = "REFUND_EXCEEDS_PAYMENT"
    MISSING_PAYMENT_REFERENCE = "MISSING_PAYMENT_REFERENCE"


class TransactionRejected(Exception):
    """Raised when a transaction is refused.

    Guarantee: when this exception is raised, **no** account balance, committed
    ledger entry, or refund tally has been modified.  The attempt is recorded in
    a separate append-only rejection log for auditability only.
    """

    def __init__(self, code: RejectionCode, message: str, txn_id: Optional[str] = None):
        self.code = code
        self.message = message
        self.txn_id = txn_id
        super().__init__(f"{code.value}: {message}")
