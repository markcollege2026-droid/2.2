"""Value objects: transaction types, the transaction record, ledger entries.

These objects are deliberately *inert* carriers.  All business validation lives
in :mod:`transaction_system.system` so that a rejected transaction can never
raise halfway through mutating state.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Optional


class TransactionType(str, Enum):
    DEPOSIT = "DEPOSIT"
    WITHDRAWAL = "WITHDRAWAL"
    REFUND = "REFUND"

    @classmethod
    def parse(cls, raw: object) -> "TransactionType":
        """Normalise a type given as enum or string (case/space tolerant).

        Raises :class:`ValueError` for anything that is not one of the three
        supported types; the engine converts that into an audit-visible
        ``INVALID_TRANSACTION_TYPE`` rejection.
        """
        if isinstance(raw, TransactionType):
            return raw
        if isinstance(raw, str):
            key = raw.strip().upper().replace("-", "_")
            aliases = {"WITHDRAW": cls.WITHDRAWAL, "WITHDRAWEL": cls.WITHDRAWAL,
                       "PAYMENT": cls.DEPOSIT, "CAPTURE": cls.DEPOSIT,
                       "DEPOSIT_CAPTURE": cls.DEPOSIT}
            if key in cls.__members__:
                return cls[key]
            if key in aliases:
                return aliases[key]
        raise ValueError(f"unsupported transaction type: {raw!r}")


@dataclass(frozen=True)
class Transaction:
    """A request submitted to the engine.  Untrusted: fields are validated later."""

    txn_id: str
    type: object  # TransactionType or raw string; normalised by the engine
    amount_cents: object  # must resolve to a positive int of cents
    account_id: str
    payment_id: Optional[str] = None  # required for REFUND, forbidden otherwise

    def __post_init__(self) -> None:
        # Only type-level sanity here; no money is moved, so failing early is safe.
        if not isinstance(self.txn_id, str) or not self.txn_id.strip():
            raise ValueError("txn_id must be a non-empty string")
        if not isinstance(self.account_id, str) or not self.account_id.strip():
            raise ValueError("account_id must be a non-empty string")


@dataclass(frozen=True)
class LedgerEntry:
    """An immutable record of one *committed* transaction."""

    sequence: int
    txn_id: str
    type: TransactionType
    amount_cents: int
    account_id: str
    payment_id: Optional[str]
    balance_after_cents: int
    recorded_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def as_dict(self) -> dict:
        return {
            "sequence": self.sequence,
            "txn_id": self.txn_id,
            "type": self.type.value,
            "amount_cents": self.amount_cents,
            "amount_display": f"{self.amount_cents / 100:.2f}",
            "account_id": self.account_id,
            "payment_id": self.payment_id,
            "balance_after_cents": self.balance_after_cents,
            "recorded_at": self.recorded_at.isoformat(),
        }


@dataclass(frozen=True)
class RejectionRecord:
    """Append-only audit record of a refused attempt (never touches balances)."""

    sequence: int
    txn_id: Optional[str]
    type: object
    amount_cents: object
    account_id: Optional[str]
    code: str
    message: str
    recorded_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def as_dict(self) -> dict:
        return {
            "sequence": self.sequence,
            "txn_id": self.txn_id,
            "type": str(getattr(self.type, "value", self.type)),
            "amount_cents": self.amount_cents,
            "account_id": self.account_id,
            "code": self.code,
            "message": self.message,
            "recorded_at": self.recorded_at.isoformat(),
        }
