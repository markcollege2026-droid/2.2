"""fac100 transaction processing system.

Public surface::

    from transaction_system import TransactionSystem, Transaction, TransactionType
    from transaction_system import TransactionRejected, RejectionCode
    from transaction_system import build_audit_summary, format_audit_summary

Money is always integer cents.
"""

from .audit import build_audit_summary, format_audit_summary
from .errors import RejectionCode, TransactionRejected
from .models import LedgerEntry, RejectionRecord, Transaction, TransactionType
from .money import AmountError, format_cents, parse_amount_to_cents, validate_amount_cents
from .system import TransactionSystem

__all__ = [
    "AmountError",
    "LedgerEntry",
    "RejectionCode",
    "RejectionRecord",
    "Transaction",
    "TransactionRejected",
    "TransactionSystem",
    "TransactionType",
    "build_audit_summary",
    "format_audit_summary",
    "format_cents",
    "parse_amount_to_cents",
    "validate_amount_cents",
]

__version__ = "1.0.0"
