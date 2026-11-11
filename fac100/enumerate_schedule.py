#!/usr/bin/env python3
"""FAC-100 Section 1: exhaustive scheduling enumeration + independent cross-check.

Problem
-------
Five jobs A, B, C, D, E occupy five consecutive slots (1..5), one job per slot.

Constraints
-----------
1. A before C            (pos[A] < pos[C])
2. D immediately after B  (pos[D] == pos[B] + 1)
3. E not first or last   (pos[E] not in {1, 5})
4. C after E             (pos[E] < pos[C])
5. B before E            (pos[B] < pos[E])

Method 1: brute force over all 120 permutations (itertools.permutations).
Method 2: independent constructive enumeration that never enumerates a full
          permutation of the 5 jobs: it places the fused block (B,D) plus the
          three remaining items (A, C, E) into slot patterns and derives slot
          numbers arithmetically.  Two different code paths -> cross-check.

Exit code 0 only if both methods agree.
"""

from __future__ import annotations

import itertools
import json
import sys
from typing import Dict, Iterable, Iterator, List, Tuple

JOBS: Tuple[str, ...] = ("A", "B", "C", "D", "E")
SLOTS: Tuple[int, ...] = (1, 2, 3, 4, 5)


# --------------------------------------------------------------------------
# Shared constraint predicate (used by method 1 only; method 2 is structural)
# --------------------------------------------------------------------------
def satisfies(ordering: Iterable[str]) -> bool:
    """Check all five constraints for a candidate 1-indexed ordering."""
    ordering = tuple(ordering)
    if sorted(ordering) != list(JOBS):  # exactly the five jobs, once each
        return False
    pos: Dict[str, int] = {job: i + 1 for i, job in enumerate(ordering)}
    return (
        pos["A"] < pos["C"]                       # 1. A before C
        and pos["D"] == pos["B"] + 1              # 2. D immediately after B
        and pos["E"] not in (min(SLOTS), max(SLOTS))  # 3. E neither first nor last
        and pos["E"] < pos["C"]                   # 4. C after E
        and pos["B"] < pos["E"]                   # 5. B before E
    )


# --------------------------------------------------------------------------
# Method 1: brute force
# --------------------------------------------------------------------------
def brute_force() -> List[Tuple[str, ...]]:
    """Enumerate all 5! = 120 permutations and keep the valid ones."""
    total = 0
    valid: List[Tuple[str, ...]] = []
    for perm in itertools.permutations(JOBS):
        total += 1
        if satisfies(perm):
            valid.append(perm)
    assert total == 120, f"expected 120 permutations, enumerated {total}"
    return valid


# --------------------------------------------------------------------------
# Method 2: independent constructive derivation (no full 5-job permutations)
# --------------------------------------------------------------------------
def constructive() -> List[Tuple[str, ...]]:
    """Build valid orderings item-by-item using only the constraint structure.

    Reasoning encoded structurally:
      * (B, D) is one fused 2-slot item, so the five slots hold four items.
      * E must sit in slot 2, 3 or 4.
      * B < E forces the block to start no later than E - 1.
      * A and C fill the two leftover slots, with A before C.
    We enumerate *slot assignments* and derive each arrangement directly,
    which is a different search space from method 1 (4! item orders + slot math).
    """
    results: List[Tuple[str, ...]] = []
    items = ("BD", "A", "C", "E")
    for item_order in itertools.permutations(items):
        # Walk the slot cursor: "BD" consumes two slots, everything else one.
        layout: List[Tuple[str, int, Tuple[int, ...]]] = []
        cursor = 1
        for item in item_order:
            width = 2 if item == "BD" else 1
            occupied = tuple(range(cursor, cursor + width))
            layout.append((item, width, occupied))
            cursor += width
        if cursor - 1 != len(SLOTS):  # must fill exactly 5 slots
            continue
        slot_of = {item: occ for item, _w, occ in layout}
        e_slot = slot_of["E"][0]
        b_slot = slot_of["BD"][0]
        c_slot = slot_of["C"][0]
        a_slot = slot_of["A"][0]
        if e_slot in (1, 5):          # constraint 3
            continue
        if not b_slot < e_slot:       # constraints 2 + 5 (B<E)
            continue
        if not e_slot < c_slot:       # constraint 4
            continue
        if not a_slot < c_slot:       # constraint 1
            continue
        arrangement = [None] * len(SLOTS)
        arrangement[a_slot - 1] = "A"
        arrangement[b_slot - 1] = "B"      # D immediately after B, by construction
        arrangement[b_slot] = "D"
        arrangement[c_slot - 1] = "C"
        arrangement[e_slot - 1] = "E"
        results.append(tuple(arrangement))
    return results


def describe(ordering: Tuple[str, ...]) -> str:
    return " ".join(f"{slot}:{job}" for slot, job in zip(SLOTS, ordering))


def main() -> int:
    brute = sorted(brute_force())
    built = sorted(constructive())

    print("FAC-100 Section 1 - exhaustive schedule enumeration")
    print("=" * 62)
    print(f"Jobs            : {', '.join(JOBS)}")
    print("Constraints     : A<C ; D=B+1 ; E not in {1,5} ; E<C ; B<E")
    print(f"Total permut.   : 120 (5!)")
    print(f"Method 1 (brute force, valid)          : {len(brute)}")
    print(f"Method 2 (constructive, valid)         : {len(built)}")
    agree = brute == built
    print(f"Methods agree                          : {agree}")
    print()
    print("Valid orderings (slot:job):")
    for i, ordering in enumerate(brute, 1):
        print(f"  {i}. {' | '.join(f'{s}:{j}' for s, j in zip(SLOTS, ordering))}"
              f"    ({''.join(ordering)})")
    print()
    unique = len(brute) == 1
    print(f"Unique solution: {unique} (valid count = {len(brute)})")

    # Rejected-permutation diagnostics: show why near misses fail.
    print()
    print("Sanity checks:")
    print(f"  every valid ordering starts with B or A : "
          f"{all(o[0] in ('B', 'A') for o in brute)}")
    for constraint, fn in (
        ("A<C", lambda p: p.index("A") < p.index("C")),
        ("D=B+1", lambda p: p.index("D") == p.index("B") + 1),
        ("E not 1/5", lambda p: p.index("E") not in (0, 4)),
        ("E<C", lambda p: p.index("E") < p.index("C")),
        ("B<E", lambda p: p.index("B") < p.index("E")),
    ):
        kept = [p for p in itertools.permutations(JOBS) if fn(p)]
        print(f"  alone, '{constraint}' keeps {len(kept):3d}/120 permutations")

    payload = {
        "jobs": list(JOBS),
        "constraints": ["A<C", "D=B+1", "E not in {1,5}", "E<C", "B<E"],
        "permutations_enumerated": 120,
        "brute_force_count": len(brute),
        "constructive_count": len(built),
        "methods_agree": agree,
        "unique_solution": unique,
        "valid_orderings": [
            {"slots": list(zip(SLOTS, o)), "sequence": "".join(o)} for o in brute
        ],
    }
    with open("evidence/schedule_enumeration.json", "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2)
    print("\nWrote evidence/schedule_enumeration.json")

    ok = agree and len(brute) > 0
    print(f"RESULT: {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
