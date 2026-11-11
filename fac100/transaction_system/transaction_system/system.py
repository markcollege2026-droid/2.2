"""The transactional engine.

Correctness properties enforced here
------------------------------------
1.  **Integer cents only** - amounts are validated by
    :func:`transaction_system.money.validate_amount_cents`; floats/bools/zero
    and negatives are rejected.
2.  **Validate-then-commit** - every check runs before anything is mutated, and
    the mutation phase is a short, contiguous block that cannot raise, under a lock.  A
    rejection therefore cannot leave partial state behind.
3.  **Idempotency** - a ``txn_id`` that has already *committed* can never be
    committed again (``DUPLICATE_TRANSACTION_ID``).
4.  **No negative balances** - withdrawals/refunds validate the resulting
    balance before committing.
5.  **Refund containment** - cumulative refunds against a captured payment may
    never exceed the captured amount; each refund must name a committed
    capture (deposit) transaction.
6.  **Thread safety** - a single re-entrant lock serialises the
    check-and-commit critical section, so concurrent submissions cannot
    interleave into an overdraft or a double-spend.
"""

from __future__ import annotations

import threading
from typing import Dict, List, Optional

from .errors import RejectionCode, TransactionRejected
from .models import LedgerEntry, RejectionRecord, Transaction, TransactionType
from .money import AmountError, validate_amount_cents


class TransactionSystem:
    """In-memory, thread-safe ledger of deposits, withdrawals and refunds."""

    def __init__(self, initial_balances: Optional[Dict[str, int]] = None) -> None:
        self._lock = threading.RLock()
        self._balances: Dict[str, int] = {}
        self._ledger: List[LedgerEntry] = []
        self._rejections: List[RejectionRecord] = []
        self._committed_ids: Dict[str, LedgerEntry] = {}
        self._payments: Dict[str, LedgerEntry] = {}
        self._refunded_by_payment: Dict[str, int] = {}
        self._initial_balances: Dict[str, int] = {}
        for account_id, cents in (initial_balances or {}).items():
            if not isinstance(cents, int) or isinstance(cents, bool) or cents < 0:
                raise AmountError(
                    f"initial balance for {account_id!r} must be a non-negative int"
                )
            self._balances[account_id] = cents
            self._initial_balances[account_id] = cents

    # ------------------------------------------------------------------ API
    def process(self, txn: Transaction) -> LedgerEntry:
        """Submit ``txn``; return the committed :class:`LedgerEntry`.

        Raises :class:`TransactionRejected` if the transaction is refused, in
        which case account state is untouched.
        """
        with self._lock:
            try:
                return self._process_locked(txn)
            except TransactionRejected as exc:
                self._record_rejection(txn, exc)
                raise

    def deposit(self, txn_id: str, account_id: str, amount_cents: int) -> LedgerEntry:
        return self.process(Transaction(txn_id, TransactionType.DEPOSIT, amount_cents, account_id))

    def withdraw(self, txn_id: str, account_id: str, amount_cents: int) -> LedgerEntry:
        return self.process(
            Transaction(txn_id, TransactionType.WITHDRAWAL, amount_cents, account_id)
        )

    def refund(self, txn_id: str, account_id: str, amount_cents: int,
               payment_id: str) -> LedgerEntry:
        return self.process(
            Transaction(txn_id, TransactionType.REFUND, amount_cents, account_id, payment_id)
        )

    # ------------------------------------------------------------- queries
    @property
    def ledger(self) -> List[LedgerEntry]:
        with self._lock:
            return list(self._ledger)

    @property
    def rejections(self) -> List[RejectionRecord]:
        with self._lock:
            return list(self._rejections)

    def balance(self, account_id: str) -> int:
        with self._lock:
            return self._balances.get(account_id, 0)

    def balances(self) -> Dict[str, int]:
        with self._lock:
            return dict(self._balances)

    def initial_balances(self) -> Dict[str, int]:
        with self._lock:
            return dict(self._initial_balances)

    def refunded_for(self, payment_id: str) -> int:
        with self._lock:
            return self._refunded_by_payment.get(payment_id, 0)

    def is_committed(self, txn_id: str) -> bool:
        with self._lock:
            return txn_id in self._committed_ids

    # ------------------------------------------------------------ internals
    def _process_locked(self, txn: Transaction) -> LedgerEntry:
        # ---- phase 1: validate everything (no mutation) ------------------
        if txn.txn_id in self._committed_ids:
            raise TransactionRejected(
                RejectionCode.DUPLICATE_TRANSACTION_ID,
                f"transaction id {txn.txn_id!r} was already committed",
                txn.txn_id,
            )

        try:
            txn_type = TransactionType.parse(txn.type)
        except ValueError as exc:
            raise TransactionRejected(
                RejectionCode.INVALID_TRANSACTION_TYPE, str(exc), txn.txn_id
            ) from exc

        try:
            amount = validate_amount_cents(txn.amount_cents)
        except AmountError as exc:
            raise TransactionRejected(
                RejectionCode.INVALID_AMOUNT, str(exc), txn.txn_id
            ) from exc

        current = self._balances.get(txn.account_id, 0)

        if txn_type is TransactionType.DEPOSIT:
            if txn.payment_id is not None:
                raise TransactionRejected(
                    RejectionCode.MISSING_PAYMENT_REFERENCE,
                    "a deposit is itself the capture and must not reference another payment",
                    txn.txn_id,
                )
            new_balance = current + amount

        elif txn_type is TransactionType.WITHDRAWAL:
            if txn.account_id not in self._balances:
                raise TransactionRejected(
                    RejectionCode.INVALID_ACCOUNT,
                    f"unknown account {txn.account_id!r}",
                    txn.txn_id,
                )
            if amount > current:
                raise TransactionRejected(
                    RejectionCode.INSUFFICIENT_FUNDS,
                    f"withdrawal of {amount} exceeds balance {current}",
                    txn.txn_id,
                )
            new_balance = current - amount

        else:  # REFUND
            if not txn.payment_id:
                raise TransactionRejected(
                    RejectionCode.MISSING_PAYMENT_REFERENCE,
                    "a refund must reference its original capture transaction id",
                    txn.txn_id,
                )
            payment = self._payments.get(txn.payment_id)
            if payment is None:
                raise TransactionRejected(
                    RejectionCode.PAYMENT_NOT_FOUND,
                    f"no committed capture with id {txn.payment_id!r}",
                    txn.txn_id,
                )
            if payment.account_id != txn.account_id:
                raise TransactionRejected(
                    RejectionCode.PAYMENT_NOT_REFUNDABLE,
                    "refund account does not match the capturing account",
                    txn.txn_id,
                )
            already = self._refunded_by_payment.get(txn.payment_id, 0)
            if amount > payment.amount_cents - already:
                raise TransactionRejected(
                    RejectionCode.REFUND_EXCEEDS_PAYMENT,
                    f"refund of {amount} would bring total refunds to {already + amount} "
                    f"against captured {payment.amount_cents}",
                    txn.txn_id,
                )
            # A refund sends held funds back to the original payment method, so it
            # debits the account.  Funds already withdrawn cannot be refunded twice.
            if amount > current:
                raise TransactionRejected(
                    RejectionCode.INSUFFICIENT_FUNDS,
                    f"refund of {amount} exceeds funds held in account ({current})",
                    txn.txn_id,
                )
            new_balance = current - amount

        # ---- phase 2: commit (cannot fail) -------------------------------
        # Test seam: subclasses may widen the check→commit window to prove the
        # lock is doing its job.  It is a no-op in production and is called
        # while the lock is held, so real serialisation is unaffected.
        self._before_commit(txn)

        entry = LedgerEntry(
            sequence=len(self._ledger) + 1,
            txn_id=txn.txn_id,
            type=txn_type,
            amount_cents=amount,
            account_id=txn.account_id,
            payment_id=txn.payment_id if txn_type is TransactionType.REFUND else None,
            balance_after_cents=new_balance,
        )
        self._balances[txn.account_id] = new_balance
        self._ledger.append(entry)
        self._committed_ids[txn.txn_id] = entry
        if txn_type is TransactionType.DEPOSIT:
            self._payments[txn.txn_id] = entry
            self._refunded_by_payment.setdefault(txn.txn_id, 0)
        elif txn_type is TransactionType.REFUND and txn.payment_id is not None:
            self._refunded_by_payment[txn.payment_id] = (
                self._refunded_by_payment.get(txn.payment_id, 0) + amount
            )
        return entry

    def _before_commit(self, txn: Transaction) -> None:
        """Hook called between validation and commit. No-op by default."""
        return None

    def _record_rejection(self, txn: Transaction, exc: TransactionRejected) -> None:
        """Record the refusal for audit only - balances and ledger are untouched."""
        self._rejections.append(
            RejectionRecord(
                sequence=len(self._rejections) + 1,
                txn_id=getattr(txn, "txn_id", None),
                type=getattr(txn, "type", None),
                amount_cents=getattr(txn, "amount_cents", None),
                account_id=getattr(txn, "account_id", None),
                code=exc.code.value,
                message=exc.message,
            )
        )
