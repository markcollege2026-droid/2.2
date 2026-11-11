#!/usr/bin/env python3
"""Dependency-free validator for fac100 ``report.json``.

Two layers
----------
1. **Structural**: a small JSON-Schema (draft-2020-12 subset) engine covering
   the keywords used by ``report_schema.json``: ``type``, ``required``,
   ``properties``, ``additionalProperties``, ``items``, ``minItems``,
   ``maxItems``, ``enum``, ``const``, ``minimum``, ``maximum``, ``minLength``,
   ``maxLength``, ``pattern``.
2. **Semantic**: report-specific cross-checks that JSON Schema cannot express
   (score arithmetic, verdict/threshold agreement, gate compliance, schedule
   count consistency, validation-experiment exit codes) and, with
   ``--check-evidence``, existence of every referenced file.

Stdlib only (json, re, argparse, pathlib). Exit codes:
    0 = report valid, 1 = report invalid, 2 = usage / unreadable input.

Usage:
    python3 validate_report.py                     # validates ./report.json
    python3 validate_report.py path/to/report.json --check-evidence
    python3 validate_report.py --json              # machine-readable output
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

DEFAULT_REPORT = Path(__file__).resolve().parent / "report.json"
DEFAULT_SCHEMA = Path(__file__).resolve().parent / "report_schema.json"
SUPPORTED_SCHEMA_MAJOR = 1

CATEGORY_MAXIMA = {
    "reasoning": 20,
    "coding": 25,
    "research": 20,
    "agent_execution": 25,
    "reliability": 10,
}

REQUIRED_DELIVERABLES = [
    "report.json",
    "report_schema.json",
    "REPORT.md",
    "validate_report.py",
    "validation_experiment.py",
    "test_results.csv",
    "run_tests.py",
    "transaction_system",
    "research.md",
    "enumerate_schedule.py",
    "evidence",
]

_TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "boolean": bool,
}


# --------------------------------------------------------------------------
# Layer 1: JSON Schema subset
# --------------------------------------------------------------------------
def _type_ok(value: Any, expected: str) -> bool:
    if expected == "null":
        return value is None
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    py = _TYPES.get(expected)
    return isinstance(value, py) if py else True


def _fmt_path(path: str) -> str:
    return path or "$"


def validate_node(value: Any, schema: Dict[str, Any], path: str,
                  errors: List[str]) -> None:
    """Recursively validate ``value`` against ``schema``, appending errors."""
    if not isinstance(schema, dict):
        return

    expected = schema.get("type")
    if expected is not None:
        options = expected if isinstance(expected, list) else [expected]
        if not any(_type_ok(value, opt) for opt in options):
            errors.append(
                f"{_fmt_path(path)}: expected type {expected}, got {type(value).__name__}"
            )
            return  # further checks would be noise

    if "const" in schema and value != schema["const"]:
        errors.append(f"{_fmt_path(path)}: expected const {schema['const']!r}, got {value!r}")

    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{_fmt_path(path)}: value {value!r} not in enum {schema['enum']}")

    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{_fmt_path(path)}: {value} < minimum {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{_fmt_path(path)}: {value} > maximum {schema['maximum']}")

    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(
                f"{_fmt_path(path)}: string shorter than minLength {schema['minLength']}"
            )
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errors.append(
                f"{_fmt_path(path)}: string longer than maxLength {schema['maxLength']}"
            )
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errors.append(
                f"{_fmt_path(path)}: {value!r} does not match pattern {schema['pattern']}"
            )

    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                errors.append(f"{_fmt_path(path)}: missing required property '{key}'")
        props = schema.get("properties", {})
        extra = schema.get("additionalProperties", True)
        for key, sub_value in value.items():
            child = f"{path}.{key}" if path else key
            if key in props:
                validate_node(sub_value, props[key], child, errors)
            elif extra is False:
                errors.append(f"{_fmt_path(path)}: unexpected property '{key}'")
            elif isinstance(extra, dict):
                validate_node(sub_value, extra, child, errors)

    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            errors.append(
                f"{_fmt_path(path)}: array has {len(value)} items, minItems {schema['minItems']}"
            )
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            errors.append(
                f"{_fmt_path(path)}: array has {len(value)} items, maxItems {schema['maxItems']}"
            )
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, item in enumerate(value):
                validate_node(item, item_schema, f"{path}[{index}]", errors)


# --------------------------------------------------------------------------
# Layer 2: report-specific semantic checks
# --------------------------------------------------------------------------
def cross_checks(report: Dict[str, Any], check_evidence: bool,
                 base_dir: Path) -> List[str]:
    problems: List[str] = []

    meta = report.get("meta", {})
    version = str(meta.get("schema_version", ""))
    try:
        major = int(version.split(".")[0])
    except (ValueError, IndexError):
        major = -1
    if major != SUPPORTED_SCHEMA_MAJOR:
        problems.append(
            f"meta.schema_version: unsupported major version {version!r} "
            f"(validator supports {SUPPORTED_SCHEMA_MAJOR}.x)"
        )

    # ---- reasoning -------------------------------------------------------
    reasoning = report.get("reasoning", {})
    orderings = reasoning.get("valid_orderings", [])
    if reasoning.get("count") != len(orderings):
        problems.append(
            f"reasoning.count ({reasoning.get('count')}) != len(valid_orderings) ({len(orderings)})"
        )
    if reasoning.get("unique") is not (len(orderings) == 1):
        problems.append(
            f"reasoning.unique ({reasoning.get('unique')}) inconsistent with "
            f"{len(orderings)} valid ordering(s)"
        )
    seen_sequences = set()
    for index, ordering in enumerate(orderings):
        slots = ordering.get("slots", [])
        sequence = ordering.get("sequence", "")
        if sorted(slots) != ["A", "B", "C", "D", "E"]:
            problems.append(f"reasoning.valid_orderings[{index}]: slots are not a permutation of A-E")
        if "".join(slots) != sequence:
            problems.append(
                f"reasoning.valid_orderings[{index}]: sequence {sequence!r} "
                f"!= slots {''.join(slots)!r}"
            )
        if sequence in seen_sequences:
            problems.append(f"reasoning.valid_orderings[{index}]: duplicate sequence {sequence!r}")
        seen_sequences.add(sequence)
        # re-derive the constraints independently
        pos = {job: i + 1 for i, job in enumerate(slots)} if sorted(slots) == sorted("ABCDE") else {}
        if pos:
            ok = (
                pos["A"] < pos["C"]
                and pos["D"] == pos["B"] + 1
                and pos["E"] not in (1, 5)
                and pos["E"] < pos["C"]
                and pos["B"] < pos["E"]
            )
            if not ok:
                problems.append(
                    f"reasoning.valid_orderings[{index}] ({sequence}) violates a stated constraint"
                )
    enum = reasoning.get("enumeration", {})
    if enum.get("permutations_enumerated") != 120:
        problems.append("reasoning.enumeration.permutations_enumerated must be 120 (5!)")
    if enum.get("brute_force_count") != len(orderings):
        problems.append("reasoning.enumeration.brute_force_count != len(valid_orderings)")
    if enum.get("constructive_count") != len(orderings):
        problems.append("reasoning.enumeration.constructive_count != len(valid_orderings)")
    if enum.get("methods_agree") is not True:
        problems.append("reasoning.enumeration.methods_agree must be true")

    # ---- software engineering -------------------------------------------
    se = report.get("software_engineering", {})
    tests = se.get("tests", {})
    total = tests.get("total", 0)
    passed = tests.get("passed", 0)
    failed = tests.get("failed", 0)
    skipped = tests.get("skipped", 0)
    if passed + failed + skipped != total:
        problems.append(
            f"software_engineering.tests: passed+failed+skipped ({passed + failed + skipped}) != total ({total})"
        )
    if total < 1:
        problems.append("software_engineering.tests.total must be >= 1")
    coverage = se.get("requirements_coverage", [])
    statuses = {c.get("status") for c in coverage}
    if "not_covered" in statuses:
        problems.append(
            "software_engineering.requirements_coverage contains 'not_covered' entries; "
            "declare them under reliability.unmet_requirements and mark partially_covered instead"
        )
    probe = se.get("mutation_probe", {})
    if not (probe.get("lock_removed_detected") and probe.get("refund_cap_disabled_detected")):
        problems.append("software_engineering.mutation_probe: mutations were not detected by the suite")

    # ---- research --------------------------------------------------------
    research = report.get("research", {})
    ids = [q.get("id") for q in research.get("questions", [])]
    if len(ids) != len(set(ids)):
        problems.append("research.questions: duplicate question ids")
    for question in research.get("questions", []):
        if question.get("confidence") == "not_documented" and question.get("sources"):
            pass  # allowed: sources explain where the documentation stops
        for source in question.get("sources", []):
            if not str(source.get("url", "")).startswith("https://"):
                problems.append(
                    f"research.questions[{question.get('id')}]: source url is not https"
                )

    # ---- agentic execution ----------------------------------------------
    ae = report.get("agentic_execution", {})
    delivered = {d.get("path") for d in ae.get("deliverables", [])}
    missing = [p for p in REQUIRED_DELIVERABLES if p not in delivered]
    if missing:
        problems.append(f"agentic_execution.deliverables: missing required entries {missing}")
    if any(d.get("exists") is not True for d in ae.get("deliverables", [])):
        problems.append("agentic_execution.deliverables: entry claims exists != true")
    experiment = ae.get("validation_experiment", {})
    steps = experiment.get("steps", []) or []
    codes = [s.get("exit_code") for s in steps]
    if steps:
        if codes[0] != 0:
            problems.append("validation_experiment: first validation must exit 0")
        if codes[-1] != 0:
            problems.append("validation_experiment: final validation must exit 0")
        if not any(isinstance(c, int) and c != 0 for c in codes):
            problems.append("validation_experiment: no step shows a non-zero exit code")
        if experiment.get("passed") is not True:
            problems.append("validation_experiment.passed must be true")
        if experiment.get("original_report_unchanged") is not True:
            problems.append("validation_experiment.original_report_unchanged must be true")

    # ---- scoring ---------------------------------------------------------
    scoring = report.get("scoring", {})
    categories = scoring.get("categories", [])
    names = [c.get("name") for c in categories]
    if sorted(names) != sorted(CATEGORY_MAXIMA):
        problems.append(f"scoring.categories: expected {sorted(CATEGORY_MAXIMA)}, got {sorted(names)}")
    total_score = 0.0
    by_name: Dict[str, float] = {}
    for category in categories:
        name = category.get("name")
        score = category.get("score", 0)
        maximum = category.get("max", 0)
        if CATEGORY_MAXIMA.get(name) != maximum:
            problems.append(
                f"scoring.categories[{name}]: max {maximum} != challenge maximum {CATEGORY_MAXIMA.get(name)}"
            )
        if score > maximum:
            problems.append(f"scoring.categories[{name}]: score {score} exceeds max {maximum}")
        if score < 0:
            problems.append(f"scoring.categories[{name}]: negative score")
        total_score += score
        by_name[name] = score
    if abs(total_score - scoring.get("total", -1)) > 1e-9:
        problems.append(
            f"scoring.total ({scoring.get('total')}) != sum of category scores ({total_score})"
        )
    if scoring.get("max_total") != CATEGORY_MAXIMA and scoring.get("max_total") != 100:
        problems.append("scoring.max_total must be 100")

    thresholds = scoring.get("thresholds", {})
    strong = thresholds.get("strong_pass")
    passing = thresholds.get("pass")
    borderline = thresholds.get("borderline")
    if None not in (strong, passing, borderline) and not (strong > passing > borderline):
        problems.append("scoring.thresholds must satisfy strong_pass > pass > borderline")
    total_value = scoring.get("total", 0)
    expected_verdict = (
        "STRONG_PASS" if strong is not None and total_value >= strong
        else "PASS" if passing is not None and total_value >= passing
        else "BORDERLINE" if borderline is not None and total_value >= borderline
        else "FAIL"
    )
    if scoring.get("verdict") != expected_verdict:
        problems.append(
            f"scoring.verdict ({scoring.get('verdict')}) inconsistent with total "
            f"{total_value} and thresholds {thresholds} (expected {expected_verdict})"
        )

    gates = scoring.get("minimum_gates", {})
    for gate, actual_key, category in (
        ("coding_min", "coding_actual", "coding"),
        ("agent_execution_min", "agent_execution_actual", "agent_execution"),
    ):
        minimum = gates.get(gate)
        actual = gates.get(actual_key)
        if minimum is not None and minimum < 15:
            problems.append(f"scoring.minimum_gates.{gate} must be 15 per the challenge rules")
        if actual is not None and by_name.get(category) is not None and actual != by_name[category]:
            problems.append(
                f"scoring.minimum_gates.{actual_key} ({actual}) != scoring.categories[{category}].score"
            )
        if minimum is not None and actual is not None and actual < minimum:
            problems.append(f"scoring gate failed: {actual_key} ({actual}) < {gate} ({minimum})")

    # ---- evidence existence ---------------------------------------------
    if check_evidence:
        candidates: List[str] = []
        candidates += [d.get("path", "") for d in ae.get("deliverables", [])]
        candidates += [e.get("path", "") for e in report.get("evidence_index", [])]
        candidates.append(tests.get("csv", ""))
        candidates.append(tests.get("summary", ""))
        candidates.append(probe.get("evidence", ""))
        candidates.append(experiment.get("evidence", ""))
        candidates.append(enum.get("evidence", ""))
        candidates.append(enum.get("script", ""))
        for relative in candidates:
            if not relative:
                continue
            if not (base_dir / relative).exists():
                problems.append(f"evidence missing: {relative}")

    return problems


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def load_json(path: Path) -> Tuple[Optional[Any], Optional[str]]:
    try:
        return json.loads(path.read_text(encoding="utf-8")), None
    except FileNotFoundError:
        return None, f"file not found: {path}"
    except json.JSONDecodeError as exc:
        return None, f"invalid JSON in {path}: {exc}"


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Validate a fac100 report.json")
    parser.add_argument("report", nargs="?", default=str(DEFAULT_REPORT))
    parser.add_argument("--schema", default=str(DEFAULT_SCHEMA))
    parser.add_argument("--check-evidence", action="store_true",
                        help="also verify that every referenced file exists")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    args = parser.parse_args(argv)

    report_path = Path(args.report)
    schema_path = Path(args.schema)
    report, report_error = load_json(report_path)
    schema, schema_error = load_json(schema_path)
    if report_error or schema_error:
        message = report_error or schema_error
        if args.json:
            print(json.dumps({"valid": False, "errors": [message]}, indent=2))
        else:
            print(f"ERROR: {message}")
        return 2

    errors: List[str] = []
    validate_node(report, schema, "", errors)
    structural = list(errors)
    semantic = cross_checks(report, args.check_evidence, report_path.parent)
    errors.extend(semantic)

    valid = not errors
    if args.json:
        print(json.dumps(
            {
                "valid": valid,
                "report": str(report_path),
                "schema": str(schema_path),
                "structural_errors": structural,
                "semantic_errors": semantic,
                "error_count": len(errors),
                "evidence_checked": bool(args.check_evidence),
            },
            indent=2,
        ))
    else:
        print(f"report : {report_path}")
        print(f"schema : {schema_path}")
        print(f"checks : structural={len(structural)} semantic={len(semantic)}"
              f" evidence={'on' if args.check_evidence else 'off'}")
        if valid:
            print("RESULT : VALID")
        else:
            print(f"RESULT : INVALID ({len(errors)} error(s))")
            for error in errors:
                print(f"  - {error}")
    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main())
