# transaction_system

A small, dependency-free Python transaction processor for deposits, withdrawals and
refunds. Money is **integer cents only**. Rejections are total: they never leave partial
state behind.

```text
transaction_system/
├── transaction_system/
│   ├── __init__.py      public API
│   ├── money.py         integer-cent validation, formatting, parsing
│   ├── errors.py        RejectionCode enum + TransactionRejected
│   ├── models.py        Transaction, LedgerEntry, RejectionRecord (inert carriers)
│   ├── system.py        the engine: validate-then-commit under a lock
│   └── audit.py         audit summary + self-checking invariants
├── tests/               stdlib unittest suite (5 modules, 59 tests)
├── demo.py              end-to-end scenario, writes evidence/
└── README.md            this file
```

## Quick start

```bash
cd transaction_system
python3 -m unittest discover -s tests -t . -v    # 59 tests
python3 demo.py                                   # scenario + audit summary
# or, from fac100/
python3 run_tests.py                              # CSV + evidence
```

```python
from transaction_system import TransactionSystem, TransactionRejected, build_audit_summary

sys_ = TransactionSystem(initial_balances={"cust-1": 5_000})   # cents
sys_.deposit("pay-1", "cust-1", 25_000)                        # captured payment
sys_.withdraw("wd-1", "cust-1", 20_000)
sys_.refund("ref-1", "cust-1", 4_000, payment_id="pay-1")      # must name the capture

try:
    sys_.deposit("pay-1", "cust-1", 25_000)                    # duplicate id
except TransactionRejected as exc:
    print(exc.code)                                            # DUPLICATE_TRANSACTION_ID

print(build_audit_summary(sys_)["invariants_ok"])               # True
```

## Design decisions

### 1. Integer cents, and floats are refused rather than rounded
Amounts must be `int` cents and `> 0`. `float`, `Decimal`, `str` and `bool` are rejected at
the boundary (`validate_amount_cents`). `bool` is checked *first* because `isinstance(True, int)`
is `True` in Python and would otherwise be accepted as `1`. Human input is converted with
`Decimal`, never `float`, and sub-cent precision (e.g. `"12.345"`) is rejected instead of
silently truncated. Tests assert that `0.10 + 0.20` style binaries never enter the ledger.

### 2. Validate-then-commit atomicity
`_process_locked` runs all checks first, builds the immutable `LedgerEntry`, and only then
mutates `_balances`, `_ledger`, `_committed_ids` and the refund tally in a straight-line block
that cannot raise. Any failure raises `TransactionRejected` from the validation phase, so no
account state has been touched. `tests/test_atomicity.py` snapshots balances, ledger and refund
tallies and asserts bit-for-bit equality after every kind of rejection.

Rejections are recorded in a **separate** append-only rejection log. That log is audit
metadata, not account state: it never affects balances, the ledger, or sequence numbers.

### 3. Idempotency by transaction ID
`txn_id` is global across types. A committed ID can never commit again
(`DUPLICATE_TRANSACTION_ID`), including a race of many concurrent threads with the same ID.
Rejected attempts do **not** burn their ID, so a caller can fix the amount and retry with the
same ID — tested explicitly (`test_rejected_id_is_not_consumed_and_can_be_retried`).
*Trade-off:* if you want strict "at-most-once attempt" semantics instead, record rejected IDs
in `_committed_ids` too; it is a one-line change, and the current choice is documented here so
nobody has to guess.

### 4. Refunds are bounded twice, and they debit held funds
* A refund must name a **committed deposit** (`payment_id`). Referencing a missing ID,
  a withdrawal, or another refund is rejected (`PAYMENT_NOT_FOUND` /
  `MISSING_PAYMENT_REFERENCE`).
* Cumulative refunds per capture may never exceed the captured amount
  (`REFUND_EXCEEDS_PAYMENT`), including exact-fit totals.
* A refund sends held funds back to the original payment method, so it **debits** the account
  and cannot exceed the funds actually held (`INSUFFICIENT_FUNDS`), nor target a different
  account than the capture (`PAYMENT_NOT_REFUNDABLE`).

The last rule is the arguable one. The alternative (refund credits the wallet) makes a
deposit→refund→withdraw cycle mint money — the first full test run failed exactly there, with
an audit identity that did not reconcile. Debiting keeps conservation:
`initial + deposits - withdrawals - refunds == sum(balances)`, which the audit summary
recomputes from the ledger on every call. If the business wants refund-to-source regardless of
wallet balance, relax the held-funds check and document the resulting exposure.

### 5. Concurrency: one lock around check-and-commit
A `threading.RLock` covers the whole validation + commit critical section, so two threads can
never both pass the same balance check. 20–40 worker tests use a `threading.Barrier` to start
together and set `sys.setswitchinterval(1e-6)` to force context switches inside the critical
section.

Contention alone was not enough: CPython's GIL hid a deliberately removed lock on some runs
(see BUG-3 in `../report.json`). The engine therefore exposes a one-line test seam,
`_before_commit(txn)`, called while the lock is held. `TestLockIsLoadBearing` overrides it to
sleep 2 ms, widening the check→commit window: with the lock, threads serialise and the results
stay correct; without it, every thread commits and the overdraw is guaranteed rather than
probabilistic. `../mutation_probe.py` verifies both directions.

### 6. Audit summary that can fail
`build_audit_summary` recomputes totals from the ledger instead of trusting incremental
counters, and asserts five invariants: balances reconcile, no negative balances, no
over-refunded captures, all committed types valid, committed IDs unique. Any drift makes
`invariants_ok` false rather than being silently reported as healthy.

## Assumptions

1. One currency, minor unit = cent (2 decimals); the upper bound is `10^15` cents.
2. A deposit **is** the captured payment; it is the only refundable object.
3. Refunds go back to the same account that captured the payment.
4. Balance may be zero but never negative; withdrawals and refunds are validated against it.
5. Duplicate detection is global, not per account.
6. Single process, in memory. Persistence, crash recovery and cross-process locking are
   explicitly out of scope (see residual risks in `../REPORT.md`).

## What is deliberately not implemented

Multi-currency, partial captures, chargebacks/disputes, holds/reservations, fee accounting,
interest, persistence/WAL, authentication, rate limiting, and HTTP transport. Each would
change the invariants above and belongs in a different layer.

## Test suite

| Module | Focus | Tests |
|---|---|---|
| `test_money.py` | integer-cent validation, float/bool rejection, exact sums | 11 |
| `test_transactions.py` | deposits, withdrawals, duplicates, invalid input, insufficient funds, history | 20 |
| `test_refunds.py` | capture references, cumulative caps, misuse, audit reconciliation | 13 |
| `test_atomicity.py` | rejected transactions leave state unchanged, immutability | 9 |
| `test_concurrency.py` | overdraw, duplicate-ID race, refund-cap race, mixed workload, wide-window lock proof | 6 |
| **total** | | **59** |

All 59 pass (`../test_results.csv`, `../evidence/unittest_run.txt`), plus a 10× concurrency
repetition (113 tests, all passing) and a mutation probe that verifies the suite fails when the
lock is removed or the refund cap is disabled — reproduced on five consecutive runs.
