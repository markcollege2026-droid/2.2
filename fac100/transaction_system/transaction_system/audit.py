"""Audit reporting: a self-checking summary of the committed ledger.

The summary is intentionally *recomputed from the ledger*, not from counters
maintained incrementally, so that any drift between balances and history shows
up as a failed invariant instead of being hidden.
"""

from __future__ import annotations

from typing import Dict, List

from .models import TransactionType
from .system import TransactionSystem

_CODES = {t.value for t in TransactionType}


def build_audit_summary(system: TransactionSystem) -> Dict[str, object]:
    """Return a JSON-serialisable summary plus invariant checks."""
    ledger = system.ledger
    rejections = system.rejections
    balances = system.balances()
    initial = system.initial_balances()

    totals = {t.value: 0 for t in TransactionType}
    counts = {t.value: 0 for t in TransactionType}
    payments: Dict[str, Dict[str, object]] = {}

    for entry in ledger:
        totals[entry.type.value] += entry.amount_cents
        counts[entry.type.value] += 1
        if entry.type is TransactionType.DEPOSIT:
            payments[entry.txn_id] = {
                "account_id": entry.account_id,
                "captured_cents": entry.amount_cents,
                "refunded_cents": 0,
                "remaining_cents": entry.amount_cents,
            }

    # Attribute refunds to their captures by replaying the ledger in order.
    for entry in ledger:
        if entry.type is TransactionType.REFUND and entry.payment_id in payments:
            bucket = payments[entry.payment_id]
            bucket["refunded_cents"] += entry.amount_cents  # type: ignore[operator]
            bucket["remaining_cents"] -= entry.amount_cents  # type: ignore[operator]

    rejections_by_code: Dict[str, int] = {}
    for record in rejections:
        rejections_by_code[record.code] = rejections_by_code.get(record.code, 0) + 1

    # Conservation identity: a refund returns held funds to the payer, so it is a
    # debit like a withdrawal.  Initial (pre-seeded) balances are part of the sum.
    initial_total = sum(initial.values())
    accounted = initial_total + totals["DEPOSIT"] - totals["WITHDRAWAL"] - totals["REFUND"]
    total_balance = sum(balances.values())

    over_refunded: List[str] = [
        pid for pid, b in payments.items() if b["refunded_cents"] > b["captured_cents"]
    ]
    negative_accounts = [acct for acct, bal in balances.items() if bal < 0]
    unknown_types = [e.txn_id for e in ledger if e.type.value not in _CODES]

    invariants = {
        "ledger_balances_reconcile": accounted == total_balance,
        "no_negative_balances": not negative_accounts,
        "no_over_refunded_payments": not over_refunded,
        "all_committed_types_valid": not unknown_types,
        "committed_ids_unique": len({e.txn_id for e in ledger}) == len(ledger),
    }

    return {
        "committed_transactions": len(ledger),
        "rejected_transactions": len(rejections),
        "totals_cents": totals,
        "counts": counts,
        "initial_balances_cents": initial,
        "initial_total_cents": initial_total,
        "balances_cents": balances,
        "total_balance_cents": total_balance,
        "refund_reconciliation_cents": accounted,
        "payments": payments,
        "rejections_by_code": rejections_by_code,
        "invariants": invariants,
        "invariants_ok": all(invariants.values()),
    }


def format_audit_summary(summary: Dict[str, object]) -> str:
    """Human-readable rendering used by the demo CLI and the evidence logs."""
    lines = [
        "AUDIT SUMMARY",
        "=" * 60,
        f"committed transactions : {summary['committed_transactions']}",
        f"rejected transactions  : {summary['rejected_transactions']}",
        "counts by type         : "
        + ", ".join(f"{k}={v}" for k, v in summary["counts"].items()),
        "totals (cents)         : "
        + ", ".join(f"{k}={v}" for k, v in summary["totals_cents"].items()),
        f"initial balances       : {summary['initial_total_cents']} cents",
        f"ledger reconciliation  : {summary['refund_reconciliation_cents']} cents",
        f"total balance          : {summary['total_balance_cents']} cents",
        "balances by account    : "
        + ", ".join(f"{k}={v}" for k, v in sorted(summary["balances_cents"].items())),
    ]
    if summary["rejections_by_code"]:
        lines.append("rejections by code     :")
        for code, n in sorted(summary["rejections_by_code"].items()):
            lines.append(f"  - {code}: {n}")
    if summary["payments"]:
        lines.append("captured payments      :")
        for pid, bucket in sorted(summary["payments"].items()):
            lines.append(
                f"  - {pid}: captured={bucket['captured_cents']} "
                f"refunded={bucket['refunded_cents']} "
                f"remaining={bucket['remaining_cents']}"
            )
    lines.append("invariants             :")
    for name, ok in summary["invariants"].items():
        lines.append(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    lines.append(f"OVERALL                : {'PASS' if summary['invariants_ok'] else 'FAIL'}")
    return "\n".join(lines)
