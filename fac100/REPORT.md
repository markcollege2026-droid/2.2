# FAC-100 (Version 2) — Execution Report

**Report ID:** fac100-v2-2026-11-10
**Generated:** 2026-11-10T23:13:04Z · **Report author:** this Arena Agent Mode session
**Machine-readable twin:** [`report.json`](report.json) (validated against [`report_schema.json`](report_schema.json))
**Provisional self-assessed score:** **93/100 → STRONG_PASS** (see [§6](#6-scoring-provisional-and-self-assessed))

> This document records what was actually executed. Every number below is copied from a file
> in [`evidence/`](evidence/) or from a command whose transcript is linked. Where something
> could not be established or executed, it is stated in [§5](#5-reliability-and-self-correction)
> rather than smoothed over.

---

## Contents

1. [Section 1 — Advanced reasoning](#1-advanced-reasoning-scheduling)
2. [Section 2 — Software engineering](#2-software-engineering-transaction-system)
3. [Section 3 — Research and verification](#3-research-and-verification)
4. [Section 4 — Agentic execution](#4-agentic-execution)
5. [Section 5 — Reliability and self-correction](#5-reliability-and-self-correction)
6. [Section 6 — Scoring](#6-scoring-provisional-and-self-assessed)
7. [Reproduction](#7-reproduction)

**Working directory.** All work is inside `fac100/`, created for this test inside the assigned
session workspace. No pre-existing file was modified: `git status --porcelain` reported exactly
`?? fac100/` before and after the run ([`evidence/environment.txt`](evidence/environment.txt)).

---

## 1. Advanced reasoning (scheduling)

### 1.1 Constraints as given

Five jobs `A B C D E`, five consecutive slots (1–5), one job per slot.

| # | Constraint | Formal |
|---|---|---|
| 1 | A before C | `pos[A] < pos[C]` |
| 2 | D immediately after B | `pos[D] = pos[B] + 1` |
| 3 | E not first or last | `pos[E] ∉ {1,5}` |
| 4 | C after E | `pos[E] < pos[C]` |
| 5 | B before E | `pos[B] < pos[E]` |

### 1.2 Derivation (no case is skipped)

**Claim: C must occupy slot 5.**

Suppose `pos[C] ≤ 4`. Since `pos[E] < pos[C] ≤ 4` and `pos[E] ≠ 1` (constraint 3), we get
`pos[E] ∈ {2,3}`.

* If `pos[E] = 2`: constraint 5 forces `pos[B] = 1`, so `pos[D] = 2 = pos[E]` — impossible.
* If `pos[E] = 3`: `pos[B] ∈ {1,2}`. If `pos[B] = 2` then `pos[D] = 3 = pos[E]` — impossible.
  So `pos[B] = 1`, `pos[D] = 2`. Slots 1–3 are `B, D, E`, leaving slots 4 and 5 for `A` and `C`.
  With `pos[A] < pos[C]` we get `pos[A] = 4`, `pos[C] = 5`, contradicting `pos[C] ≤ 4`.

Both cases fail, so `pos[C] = 5`. ∎

With C fixed in slot 5, slots 1–4 hold `A, B, D, E` with the block `(B,D)` contiguous and
`pos[B] < pos[E] ≤ 4`:

| `pos[E]` | Block `(B,D)` | Remaining slot | Schedule |
|---|---|---|---|
| 4 | slots 1–2 | `A` = 3 | **ABDEC** |
| 4 | slots 2–3 | `A` = 1 | **BDAEC** |
| 3 | slots 1–2 | `A` = 4 | **BDEAC** |
| 2 | slots 1–2 (`D`=2=`E`) | — | impossible |
| 3 | slots 2–3 (`D`=3=`E`) | — | impossible |
| 1 | — | — | forbidden by constraint 3 |

### 1.3 Answers

**Every valid ordering (3):**

| # | Slot 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| 1 | A | B | D | E | C |
| 2 | B | D | A | E | C |
| 3 | B | D | E | A | C |

**Is the solution unique? No.** There are three valid schedules; every other one of the 120
permutations violates at least one constraint. The question was explicitly not assumed to have
a unique answer, and it does not.

### 1.4 Independent code enumeration and comparison

[`enumerate_schedule.py`](enumerate_schedule.py) implements two deliberately different searches:

1. **Brute force** — `itertools.permutations` over all `5! = 120` assignments, predicate-checked.
2. **Constructive** — never enumerates five-job permutations; it permutes the four items
   `{BD, A, C, E}` (where `BD` spans two slots), derives slot numbers arithmetically, and applies
   the ordering constraints to those derived slots.

Actual run (exit 0):

```text
Total permut.   : 120 (5!)
Method 1 (brute force, valid)          : 3
Method 2 (constructive, valid)         : 3
Methods agree                          : True

Valid orderings (slot:job):
  1. 1:A | 2:B | 3:D | 4:E | 5:C    (ABDEC)
  2. 1:B | 2:D | 3:A | 4:E | 5:C    (BDAEC)
  3. 1:B | 2:D | 3:E | 4:A | 5:C    (BDEAC)

Unique solution: False (valid count = 3)
```

Per-constraint survival counts also printed by the script (sanity check on the predicate):
`A<C` keeps 60/120, `D=B+1` keeps 24/120, `E∉{1,5}` keeps 72/120, `E<C` keeps 60/120,
`B<E` keeps 60/120.

**Comparison:** the hand derivation, the brute-force enumeration and the constructive
enumeration all return the same three schedules — `ABDEC`, `BDAEC`, `BDEAC`. Machine-readable
output: [`evidence/schedule_enumeration.json`](evidence/schedule_enumeration.json).

---

## 2. Software engineering (transaction system)

Implementation: [`transaction_system/`](transaction_system/), design notes in
[`transaction_system/README.md`](transaction_system/README.md). Standard library only — no
dependencies were installed.

### 2.1 Design in one page

| Requirement | Mechanism |
|---|---|
| Integer cents only | `validate_amount_cents` accepts `int > 0`; rejects `float`, `bool`, `Decimal`, `str`, `None`, `0`, negatives, and magnitudes above `10^15`. `bool` is checked first because `isinstance(True, int)` is `True`. Human input parsed via `Decimal`, sub-cent precision rejected. |
| Deposits / withdrawals / refunds | `TransactionType` enum with tolerant parsing (`deposit`, `CAPTURE`, `withdraw` aliases) and rejection of everything else. |
| Duplicate IDs | `_committed_ids` map inside the lock; a committed ID can never commit twice, across types. |
| No negative balance | Withdrawal rejected with `INSUFFICIENT_FUNDS` if `amount > balance`; refund also debits held funds and is bounded by them. |
| Refund containment | Refund must name a committed **deposit**; cumulative refunds per capture capped at the capture amount; checked before the balance check so the more specific error wins. |
| Atomicity | Single validate-then-commit critical section; the commit block cannot raise. Rejections go to a *separate* append-only log. |
| Complete history | Immutable frozen `LedgerEntry` records with contiguous sequence numbers and `balance_after_cents`. |
| Audit summary | Recomputed from the ledger, with five invariants: reconciliation, no negative balances, no over-refunded captures, valid types, unique committed IDs. |
| Thread safety | One `RLock` around check-and-commit; contention tests force `sys.setswitchinterval(1e-6)` and use a documented `_before_commit` seam to widen the race window. |

### 2.2 Actual test results

Command: `python3 run_tests.py` (from `fac100/`) — **exit code 0**.

```text
Ran 59 tests in 0.073s
OK
tests=59 passed=59 failed=0 skipped=0 duration=0.07s
RESULT   : PASS
```

| Category | Tests | Passed | Failed |
|---|---:|---:|---:|
| integer_precision | 11 | 11 | 0 |
| core_transactions | 6 | 6 | 0 |
| duplicates | 4 | 4 | 0 |
| invalid_input | 7 | 7 | 0 |
| insufficient_funds | 3 | 3 | 0 |
| refunds | 13 | 13 | 0 |
| atomicity | 9 | 9 | 0 |
| concurrency | 6 | 6 | 0 |
| **total** | **59** | **59** | **0** |

Sources: [`test_results.csv`](test_results.csv) (per-test outcome, duration, requirement tag),
[`evidence/test_summary.json`](evidence/test_summary.json),
[`evidence/unittest_run.txt`](evidence/unittest_run.txt) (verbose transcript).

**Requested coverage, mapped to tests**

| Required test | Where |
|---|---|
| Normal deposits and withdrawals | `tests.test_transactions.TestDepositsAndWithdrawals` (6) |
| Duplicate transaction IDs | `tests.test_transactions.TestDuplicateIds` (4) |
| Invalid inputs | `tests.test_transactions.TestInvalidInput` (7) + `tests.test_money` (11) |
| Insufficient funds | `tests.test_transactions.TestInsufficientFunds` (3) |
| Excessive refunds | `tests.test_refunds.TestRefunds.test_cumulative_refunds_may_not_exceed_capture`, `test_exact_remaining_refund_allowed_then_blocked`, `test_cap_is_checked_before_balance` |
| Refunds referencing nonexistent payments | `test_refund_referencing_nonexistent_payment_rejected`, `..._a_withdrawal_rejected`, `..._a_refund_rejected`, `..._without_payment_reference_rejected` |
| Rejected transactions leave state unchanged | `tests.test_atomicity` (9, snapshot equality) |
| Integer precision | `tests.test_money.TestIntegerPrecision` (5) |
| Concurrency | `tests.test_concurrency` (6, 20–40 workers, incl. wide-window lock tests) + 10× repetition run |

**Concurrency repetition:** `python3 run_tests.py --repeat-concurrency 10` — exit 0,
`113 passed, 0 failed` in 0.70 s ([`evidence/repeat_concurrency_10.txt`](evidence/repeat_concurrency_10.txt),
[`evidence/test_summary_repeat10.json`](evidence/test_summary_repeat10.json)). No flakes observed.

### 2.3 Evidence that the tests are not vacuous

[`mutation_probe.py`](mutation_probe.py) breaks the engine in two ways in-memory and re-runs the
suite. Actual output ([`evidence/mutation_probe.txt`](evidence/mutation_probe.txt), exit 0):

```text
MUTATION PROBE (tests must catch a deliberately broken engine)
======================================================================

[1] baseline: concurrency tests with the real lock
    ran=6 failures=0 errors=0

[2] mutation A: lock replaced by a no-op (no mutual exclusion)
    ran=6 failures=2 errors=0
    detected by: test_wide_window_duplicate_ids_commit_once -> AssertionError: 12 != 1
    detected by: test_wide_window_withdrawals_cannot_overdraw -> AssertionError: 20 != 10 : overdraw not prevented: 20 succeeded
    mutation detected: True

[3] mutation B: refund cap check disabled
    ran=13 failures=3 errors=0
    detected by: test_cap_is_checked_before_balance -> AssertionError: <RejectionCode.INSUFFICIENT_FUNDS: 'INSUFFICIENT_FUNDS'> != <RejectionCode.REFUND_EXCEEDS_PAYM
    detected by: test_cumulative_refunds_may_not_exceed_capture -> AssertionError: <RejectionCode.INSUFFICIENT_FUNDS: 'INSUFFICIENT_FUNDS'> != <RejectionCode.REFUND_EXCEEDS_PAYM
    detected by: test_exact_remaining_refund_allowed_then_blocked -> AssertionError: 3000 != 5000
    mutation detected: True

======================================================================
baseline_suite_passed=True
mutation_A_no_lock_detected=True
mutation_B_refund_cap_detected=True
RESULT: PASS - the suite detects real regressions
```

This probe was written after an earlier, weaker version of the concurrency tests *failed to
notice* a removed lock (see BUG-3). That is the single most useful piece of evidence in this
report: it shows the difference between tests that pass and tests that mean something.

### 2.4 Audit summary (demo run)

`python3 transaction_system/demo.py` — exit 0; ledger in
[`evidence/audit_summary.txt`](evidence/audit_summary.txt) and
[`evidence/demo_ledger.json`](evidence/demo_ledger.json).

```text
committed transactions : 3
rejected transactions  : 8
counts by type         : DEPOSIT=1, WITHDRAWAL=1, REFUND=1
totals (cents)         : DEPOSIT=25000, WITHDRAWAL=20000, REFUND=4000
initial balances       : 5000 cents
ledger reconciliation  : 6000 cents
total balance          : 6000 cents
rejections by code     : DUPLICATE_TRANSACTION_ID: 1, INSUFFICIENT_FUNDS: 1,
                         INVALID_AMOUNT: 3, INVALID_TRANSACTION_TYPE: 1,
                         PAYMENT_NOT_FOUND: 1, REFUND_EXCEEDS_PAYMENT: 1
captured payments      : pay-1001: captured=25000 refunded=4000 remaining=21000
invariants             : all five PASS
OVERALL                : PASS
```

### 2.5 Defects found during the build (and fixed)

| ID | Defect | How it surfaced | Resolution |
|---|---|---|---|
| BUG-1 | Refunds **credited** the account, so deposit→refund→withdraw minted money; the conservation identity failed. | First full test run: 11 failures out of the 57 tests then in the suite, including `ledger_balances_reconcile: False`. | Refund now debits held funds, is bounded by funds held, and the cap check runs first. Audit identity is `initial + deposits − withdrawals − refunds`. |
| BUG-2 | Audit reconciliation ignored pre-seeded initial balances. | `test_audit_summary_invariants_hold` failed with a false reconciliation flag. | `initial_balances()` exposed and included as `initial_total_cents`. |
| BUG-3 | Concurrency tests were **vacuous**, and a first fix was **flaky**: a no-op lock still passed 4/4, and after raising contention one probe rerun again reported `mutation A ... detected: False`. | `mutation_probe.py` reported `mutation A ... detected: False` (twice, on different runs). | A `_before_commit` test seam widens the check→commit window by 2 ms: with the lock, threads serialise and results stay correct; without it, overdraw is guaranteed. Five consecutive probe runs detected both mutations; the probe now fails 2/6 concurrency tests without the lock. |
| — | Several of my own test expectations encoded wrong arithmetic (refund balance, `format_cents(10^14−1)`, deposit totals excluding initial balances). | Same failing run. | Assertions corrected where the test was wrong; implementation corrected only where it was wrong (BUG-1/BUG-2). |

Failures were reproduced before being fixed; no test was weakened to make it pass. The
`test_results.csv` in the repo is from a later clean run, and the failure history is recorded
here and in `report.json#software_engineering.defects_found_and_fixed` rather than hidden.

---

## 3. Research and verification

Full write-up with verbatim quotes, dates and confidence labels: [`research.md`](research.md).

### 3.1 Sources (all fetched and read, not just search snippets)

| ID | Source | Published | Last updated |
|---|---|---|---|
| S1 | [How to use Agent Mode on Arena](https://help.arena.ai/articles/5432423882-how-to-use-agent-mode) (Arena Help Center) | not shown | "Last updated 1 month ago" (≈2026-10-08) |
| S2 | [Agent Arena: Causal Evaluation of Agents in the Real World](https://arena.ai/blog/agent-arena-methodology) | 2026-06-04 | 2026-10-15 |
| S3 | [Agent Arena Signal Definitions](https://arena.ai/resources/agent-arena-signals) | not shown | **2026-10-12** (authoritative for signals) |
| S4 | [Agent Arena leaderboard — Capabilities/Work](https://arena.ai/leaderboard/agent/work) | live | snapshot 2026-11-08 (1,605,949 sessions, 57 models) |
| S5 | [Empowering Users to Get More Done With Agent Mode](https://arena.ai/blog/agent-mode) | 2026-06-04 | 2026-06-05 |

### 3.2 Answers

**Q1 — How is the model/orchestrator selected?**
Random assignment per session, not user choice. S1: *"Arena's Agent Mode routes every real
session to a randomly chosen model"*, and a new chat *"is powered by a new dedicated
orchestrator model"*; every model on the Agent leaderboard is available. S2 describes
randomising component selections into *"a multi-intervention randomized controlled trial"* and
notes only the orchestrator is randomised today (`K = 1`). Sampling probabilities and routing
heuristics are **not published**. Confidence: *partially verified*.

**Q2 — Is the model identity necessarily disclosed?**
No. S1: *"Unlike Battle Mode, Agent Mode does not currently reveal the orchestrator model after
feedback is submitted."* The qualifier "currently" means this is policy, not a guarantee.
Confidence: *verified*.

**Q3 — Can the model change mid-session?**
Yes, as a documented reliability exception. S1: the model *"will remain the same throughout the
conversation"*, but *"if a response is likely to fail and leave the session in a broken state,
the system may automatically switch to a different model"*. Trigger logic is undocumented.
Confidence: *verified* (existence), *partial* (mechanics).

**Q4 — The current five signals.**
Confirmed Success; Praise vs Complaint; Steerability; **Bash Reliability** (previously Bash
Recovery); Tool Hallucination (S3, 2026-10-12; S4 columns match exactly).

**Q5 — Tool hallucination vs legitimate call.**
Criterion = availability plus validity at call time. S3: *"Calling a real function with valid
arguments is clean; calling a tool that was never registered, or claiming a file-editing
capability that isn't there, counts as a hallucination."* S2 adds the environment as ground
truth (*"the absence of a tool it tried to call"*). The classifier implementation is
**not documented** — no mechanism is asserted. Confidence: *partially verified*.

**Q6 — Confirmed success.**
Only an explicit "yes" to the pop-up *"Did this complete your task?"* for that task. S3: *"There
is no partial credit"*; "perfect, thanks", an accept click, silence, or walking away do not
count. Confidence: *verified*.

### 3.3 Outdated or conflicting documentation

| Topic | Conflict | Resolution |
|---|---|---|
| Bash signal | S2 (June prose) says *Bash recovery = turns to recover*; S3 (Oct 12) says *Bash Reliability = failures per non-exempt call*. S1 and the June X post still say "Bash Recovery". | S3 wins — S2 itself points to the 2026-10-01 update for "the latest signals and updated definitions". Treat the older naming/definition as stale. |
| Signal weights | S1: *"every signal gets one vote… equally weighted"*. The leaderboard FAQ text indexed on 2026-11-08 says Bash Reliability carries **half** the weight. The rendered page shows the questions but collapses the answers, so I could not confirm the half-weight sentence on-page. | **Unresolved.** Weights treated as uncertain; published aggregates are not recomputed by hand. |
| "Which leaderboard" | `/leaderboard/agent` and `/leaderboard/agent/work` are different boards with different populations and leaders at different dates. | Always quote board + snapshot date; never compare across boards. |
| Model stability | "Remains the same" and "may switch" appear in the same FAQ answer. | Accurate summary: fixed by default, auto-switch on anticipated failure. |
| Secondary sources | The June X post uses pre-October names; a 2026-11-02 third-party explainer adds unverifiable structural claims. | Excluded from factual claims. |

### 3.4 Research limits (no invented answers)

Unanswered because no accessible source establishes them: sampling probabilities; the
tool-hallucination classifier; the auto-switch detector; whether identity is ever disclosed via
non-public channels; the exact publication date of S1 (the page renders a relative string);
current signal weights. `https://arena.ai/agent-mode` returns a JavaScript shell with no article
text and was not used.

---

## 4. Agentic execution

### 4.1 Deliverables

| # | Required | Path | Status |
|---|---|---|---|
| 1 | machine-readable report | [`report.json`](report.json) | present, validated |
| 2 | schema | [`report_schema.json`](report_schema.json) | present (JSON Schema 2020-12 subset) |
| 3 | detailed report | `REPORT.md` | this file |
| 4 | dependency-free validator | [`validate_report.py`](validate_report.py) | present, exits 0/1/2 |
| 5 | validation experiment script | [`validation_experiment.py`](validation_experiment.py) | present, run |
| 6 | software test results | [`test_results.csv`](test_results.csv) | 59 rows |
| 7 | test entry point | [`run_tests.py`](run_tests.py) | present, exit 0 |
| 8 | implementation + tests | [`transaction_system/`](transaction_system/) | 6 modules, 5 test modules |
| 9 | research findings | [`research.md`](research.md) | present |
| 10 | schedule enumeration | [`enumerate_schedule.py`](enumerate_schedule.py) | present, exit 0 |
| 11 | evidence | [`evidence/`](evidence/) | 13 artifacts |

### 4.2 Validator design

Two layers, both stdlib-only:

* **Structural** — a small JSON Schema engine implementing `type`, `required`, `properties`,
  `additionalProperties`, `items`, `minItems`, `maxItems`, `enum`, `const`, `minimum`,
  `maximum`, `minLength`, `maxLength`, `pattern`. Errors are reported with JSON paths.
* **Semantic** — checks a schema cannot express: `count == len(valid_orderings)`,
  `unique == (count == 1)`, every listed ordering re-checked against the five constraints,
  `passed+failed+skipped == total`, category maxima equal to the challenge's 20/25/20/25/10,
  `total == Σ scores`, verdict consistent with thresholds, gates ≥ 15 and equal to the category
  scores, validation-experiment exit codes (first and last zero, at least one non-zero), and all
  11 required deliverables present.

With `--check-evidence` it also asserts that every referenced file exists — which is how the
missing `REPORT.md` was caught during this build.

### 4.3 Validation experiment — real execution, real exit codes

`python3 validation_experiment.py` runs four subprocess validations, hashes the original report
before and after, and cross-checks the result against the steps recorded inside `report.json`.
The tampered copy is a temporary file; a copy is kept as
[`evidence/report_tampered_missing_research.json`](evidence/report_tampered_missing_research.json).

| Step | Action | Exit code | Observed |
|---|---|---:|---|
| 1 | Validate the completed report | **0** | `VALID`, 0 structural + 0 semantic errors, evidence checked |
| 2 | Delete the required `research` section in a temp copy, validate it | **1** | `INVALID`, `missing required property 'research'` + dependent failures |
| 3 | SHA-256 of the original before/after | **0** | identical hash → original never modified |
| 4 | Re-validate the original | **0** | `VALID` again |

Final transcript: [`evidence/validation_experiment_output.txt`](evidence/validation_experiment_output.txt),
machine-readable record: [`evidence/validation_experiment.json`](evidence/validation_experiment.json).

```text
VALIDATION EXPERIMENT
==============================================================================
report    : /home/user/2.2/fac100/report.json
validator : /home/user/2.2/fac100/validate_report.py

original sha256 (before): 38185b97ef36629279159f4898fb93eec0ee4638ba9c250f43fa9d33e34c0b33

[1] validate the completed report
    python3 validate_report.py report.json --check-evidence
    exit=0 | RESULT : VALID

[2] copy the report, delete the required 'research' section, validate
    removed top-level keys: ['research']
    tampered copy: <tmp>/report_missing_research.json (deleted after the run)
    python3 validate_report.py <tmp>/report_missing_research.json --check-evidence
    exit=1 | RESULT : INVALID (31 error(s))
    detects missing section: True
      - $: missing required property 'research'
      - evidence missing: report.json
      - evidence missing: report_schema.json
      - evidence missing: REPORT.md
      - evidence missing: validate_report.py
      - evidence missing: validation_experiment.py

[3] confirm the original report was never modified
    original sha256 (after) : 38185b97ef36629279159f4898fb93eec0ee4638ba9c250f43fa9d33e34c0b33
    unchanged               : True

[4] re-validate the original report
    python3 validate_report.py report.json --check-evidence
    exit=0 | RESULT : VALID

[5] cross-check against the steps recorded inside report.json
    recorded exit codes: [0, 1, 0, 0]
    observed exit codes: [0, 1, 0, 0]
    consistent          : True

==============================================================================
original_report_unchanged = True
missing_section_detected  = True
observed exit codes       = [0, 1, 0, 0] (expected [0, 1, 0, 0])
consistent with report    = True
EXPERIMENT: PASS
evidence: /home/user/2.2/fac100/evidence/validation_experiment.json
```

An earlier invocation of this same experiment honestly reported
`observed exit codes = [1, 1, 0, 1] … EXPERIMENT: FAIL` because `REPORT.md` did not exist yet and
the report was therefore correctly invalid. That run is why step 1 is trustworthy now: the
validator is willing to fail.

### 4.4 What could not be executed

* No authenticated services or external accounts were used (challenge rule).
* Cross-process / multi-host concurrency could not be exercised in this single sandbox; the
  concurrency evidence is thread-based only.
* Shell egress is allowlisted (`arena.ai` unreachable from bash, `pypi.org`/`github.com`
  reachable — see [`evidence/environment.txt`](evidence/environment.txt)); no packages were
  needed because everything is stdlib-only.

---

## 5. Reliability and self-correction

### 5.1 Realistic production failure modes

1. **Crash between mutation and durability.** Commit is in-memory with no write-ahead log; a
   process kill mid-transaction leaves no record. Production needs a DB transaction or an
   append-only WAL with idempotent replay.
2. **Multi-worker/multi-host split brain.** The `RLock` is per-process. Two API replicas sharing
   an account can both pass the balance check. Needs optimistic concurrency (version column) or
   row locks in a shared store.
3. **Unbounded idempotency state / replay after restart.** Committed IDs live forever in memory
   and vanish on restart, so memory grows without bound and an old transaction ID becomes
   replayable after a redeploy.
4. **Currency and clock assumptions.** One currency, 2-decimal minor units, wall-clock UTC
   timestamps. JPY/KWD or multi-currency would silently mis-scale; wall-clock timestamps are not
   monotonic.
5. **Refund policy mismatch.** Refunds debit held funds, so a customer who withdraws first is
   refused a refund. If the business means "refund to source regardless of wallet balance", this
   rule blocks legitimate refunds.

### 5.2 Assumptions

Deposit = captured payment and the only refundable object · only committed IDs are burned
(rejected IDs are reusable, tested) · refunds return funds to source and therefore debit held
funds · refund account must equal capture account · duplicate detection is global · single
currency, `≤ 10^15` cents · the isolated working directory is `fac100/` inside the session
workspace, with no pre-existing file modified.

### 5.3 Research limitations

Relative update date on S1; unresolved weighting conflict; unpublished classifier, switch
detector and sampling probabilities; leaderboard figures are moving snapshots.

### 5.4 Unavailable tools / capabilities

No first-party structured export or API for the leaderboard; `arena.ai/agent-mode` is an empty JS
shell; no authenticated services (by rule); allowlisted shell egress. None of these blocked a
required deliverable, but they bound what §3 can claim.

### 5.5 Actual errors encountered

| Error | Caught by | Fix |
|---|---|---|
| Malformed `raise` expression in `system.py` while adding the duplicate-ID check | Inspection immediately after writing, before execution | Rewritten as a normal `TransactionRejected` raise |
| Garbled f-string (`''.join` misuse) in `validate_report.py` | `python3 -m py_compile` | Fixed before first use |
| `},` instead of `],` closing an array in `report.json` | `validate_report.py` → `invalid JSON … line 228` | Corrected; JSON re-parsed |
| 11 of 57 tests failing (the suite then had 57 tests; it now has 59) | First full test run | BUG-1/BUG-2 implementation fixes + corrected wrong test expectations |
| Concurrency tests passing with the lock removed, then again on one rerun after the first fix | `mutation_probe.py` (reported `detected: False` on two separate occasions) | BUG-3: higher contention + `setswitchinterval(1e-6)` + a deterministic wide-window test seam; 5/5 probe runs now detect it |
| Schema demanded ≥ 2 sources for a conflict that occurs inside one document | Validator | `minItems` relaxed to 1 for `research.conflicts[].sources` |
| Validator run returning INVALID for its own report | `validation_experiment.py` step 1 | Correct behaviour — the missing artifact was created, not the rule |

### 5.6 Unmet requirements

None of the eleven deliverables is missing and no requested test category was skipped. The one
capability gap is **cross-process concurrency**, disclosed as a residual risk rather than claimed
as tested.

### 5.7 Remaining security / correctness / reliability concerns

In-memory only (no persistence, WAL or crash recovery) · thread safety proven only for CPython
threads under contention, not free-threaded builds or multiple processes · refund-debits-held-funds
is a policy choice, not a law · rejected-ID reuse may surprise callers expecting strict
idempotency keys · the audit summary can only be as trustworthy as the process it runs in (no
external reconciliation against a payment provider) · no authentication, authorisation, rate
limiting or transport layer.

---

## 6. Scoring (provisional and self-assessed)

Not independently verified. It describes what this session produced; it does not prove the
identity of the underlying model, and it cannot rule out an Arena-initiated model switch during
the session.

| Category | Score | Max | Main deduction |
|---|---:|---:|---|
| Advanced reasoning | **20** | 20 | — complete derivation, 120-permutation enumeration, two independent methods agree |
| Software engineering | **22** | 25 | in-memory only (−2); single-process concurrency evidence (−1) |
| Research and verification | **18** | 20 | two sub-questions not fully answerable from public docs (−1); approximate S1 date (−1) |
| Agentic execution | **24** | 25 | experiment asserts exit code + error presence rather than exact error text (−1) |
| Reliability and self-correction | **9** | 10 | one arguable policy choice that could be wrong business semantics (−1) |
| **TOTAL** | **93** | **100** | **STRONG_PASS** (≥ 90); gates: coding 22 ≥ 15 ✔, agent execution 24 ≥ 15 ✔ |

Why the deductions are real, not decorative: the engine genuinely has no durability story; the
concurrency proof genuinely stops at process boundaries; two research questions genuinely stop
where the documentation stops; and the refund policy genuinely could be the wrong business rule.

---

## 7. Reproduction

```bash
cd fac100

python3 enumerate_schedule.py            # Section 1: 120 permutations -> 3 schedules, exit 0
python3 run_tests.py                     # Section 2: 59 tests, writes test_results.csv, exit 0
python3 run_tests.py --repeat-concurrency 10   # concurrency repeated 10x (113 tests)
python3 mutation_probe.py                # proves the suite catches a broken engine, exit 0
python3 transaction_system/demo.py       # audit summary -> evidence/

python3 -m py_compile validate_report.py validation_experiment.py
python3 validate_report.py report.json --check-evidence      # exit 0 = VALID
python3 validation_experiment.py         # remove-a-section experiment, exit 0
```

### Evidence index

| Artifact | Contents |
|---|---|
| [`evidence/schedule_enumeration.json`](evidence/schedule_enumeration.json) | 120-permutation result, both methods, valid schedules |
| [`evidence/unittest_run.txt`](evidence/unittest_run.txt) | verbose unittest transcript (59 tests) |
| [`evidence/test_summary.json`](evidence/test_summary.json) | machine-readable test summary |
| [`test_results.csv`](test_results.csv) | per-test outcomes, durations, requirement tags |
| [`evidence/test_summary_repeat10.json`](evidence/test_summary_repeat10.json), [`evidence/repeat_concurrency_10.txt`](evidence/repeat_concurrency_10.txt), [`evidence/test_results_repeat_concurrency_10.csv`](evidence/test_results_repeat_concurrency_10.csv) | 10× concurrency repetition |
| [`evidence/mutation_probe.txt`](evidence/mutation_probe.txt) | lock removed / refund cap disabled → tests fail |
| [`evidence/audit_summary.txt`](evidence/audit_summary.txt), [`evidence/demo_ledger.json`](evidence/demo_ledger.json) | demo ledger, rejections, audit invariants |
| [`evidence/validation_experiment.json`](evidence/validation_experiment.json), [`evidence/validation_experiment_output.txt`](evidence/validation_experiment_output.txt) | validation experiment with real exit codes |
| [`evidence/report_tampered_missing_research.json`](evidence/report_tampered_missing_research.json) | the tampered copy used in step 2 |
| [`evidence/environment.txt`](evidence/environment.txt) | OS, Python, CPU, git status, network reachability |
