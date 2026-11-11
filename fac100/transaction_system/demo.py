#!/usr/bin/env python3
"""End-to-end demo: a realistic sequence with rejections, then the audit summary.

Run from the fac100 directory:  python3 transaction_system/demo.py
Writes evidence/audit_summary.txt and evidence/demo_ledger.json.
"""

from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from transaction_system import (  # noqa: E402
    RejectionCode,
    Transaction,
    TransactionRejected,
    TransactionSystem,
    build_audit_summary,
    format_audit_summary,
)

EVIDENCE = os.path.join(ROOT, "evidence")


def main() -> int:
    sys_ = TransactionSystem(initial_balances={"cust-1": 5_000})
    print("initial balance cust-1:", sys_.balance("cust-1"), "cents")

    ok = [
        ("pay-1001", "DEPOSIT", 25_000, "cust-1", None),
        ("wd-1002", "WITHDRAWAL", 20_000, "cust-1", None),
        ("ref-1003", "REFUND", 4_000, "cust-1", "pay-1001"),
    ]
    bad = [
        ("pay-1001", "DEPOSIT", 25_000, "cust-1", None),        # duplicate id
        ("wd-1004", "WITHDRAWAL", 10**9, "cust-1", None),        # insufficient funds
        ("ref-1005", "REFUND", 30_000, "cust-1", "pay-1001"),    # exceeds capture
        ("ref-1006", "REFUND", 100, "cust-1", "ghost-pay"),       # unknown capture
        ("tr-1007", "TRANSFER", 100, "cust-1", None),             # invalid type
        ("dp-1008", "DEPOSIT", 0, "cust-1", None),                # zero amount
        ("dp-1009", "DEPOSIT", 12.5, "cust-1", None),             # float amount
        ("wd-1010", "WITHDRAWAL", -500, "cust-1", None),          # negative amount
    ]

    print("\n-- committed --")
    for txn_id, kind, amount, acct, pay in ok:
        entry = sys_.process(Transaction(txn_id, kind, amount, acct, pay))
        print(f"  {entry.sequence}. {txn_id:9s} {kind:10s} {amount:>7} -> balance "
              f"{entry.balance_after_cents:>7} cents")

    print("\n-- rejected (state must not move) --")
    for txn_id, kind, amount, acct, pay in bad:
        try:
            sys_.process(Transaction(txn_id, kind, amount, acct, pay))
        except TransactionRejected as exc:
            print(f"  {txn_id:9s} {kind:10s} {str(amount):>7} -> {exc.code.value}")

    summary = build_audit_summary(sys_)
    print()
    print(format_audit_summary(summary))

    os.makedirs(EVIDENCE, exist_ok=True)
    with open(os.path.join(EVIDENCE, "audit_summary.txt"), "w", encoding="utf-8") as fh:
        fh.write(format_audit_summary(summary) + "\n")
        fh.write("\ncommitted ledger:\n")
        for entry in sys_.ledger:
            fh.write(json.dumps(entry.as_dict()) + "\n")
        fh.write("\nrejections:\n")
        for record in sys_.rejections:
            fh.write(json.dumps(record.as_dict()) + "\n")
    with open(os.path.join(EVIDENCE, "demo_ledger.json"), "w", encoding="utf-8") as fh:
        json.dump(
            {
                "summary": summary,
                "ledger": [e.as_dict() for e in sys_.ledger],
                "rejections": [r.as_dict() for r in sys_.rejections],
            },
            fh,
            indent=2,
        )
    print("\nWrote evidence/audit_summary.txt and evidence/demo_ledger.json")
    return 0 if summary["invariants_ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
