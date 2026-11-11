#!/usr/bin/env python3
"""Validation experiment: prove the validator accepts the real report, rejects a
report with a required section removed, and that the real report was never
touched.

Every step shells out to validate_report.py and records the **actual** exit code.
The original report.json is hashed before and after and is never written to; the
tampered copy is a temporary file (a copy is kept in evidence/ for auditability).

Exit codes:
    0 = experiment behaved as expected AND report.json's recorded steps match
    1 = unexpected behaviour or a mismatch with report.json
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, List

HERE = Path(__file__).resolve().parent
REPORT = HERE / "report.json"
VALIDATOR = HERE / "validate_report.py"
EVIDENCE = HERE / "evidence"
EXPECTED_CODES = [0, 1, 0, 0]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def run_validator(target: Path) -> Dict[str, Any]:
    command = [sys.executable, str(VALIDATOR), str(target), "--check-evidence"]
    completed = subprocess.run(command, capture_output=True, text=True, cwd=HERE)
    display = "report.json" if target == REPORT else f"<tmp>/{target.name}"
    return {
        # temp paths change per run, so they are shown as <tmp>/ to keep the
        # transcript reproducible; the temp directory is deleted afterwards.
        "command": f"python3 validate_report.py {display} --check-evidence",
        "exit_code": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    }


def first_result_line(stdout: str) -> str:
    for line in stdout.splitlines():
        if line.startswith("RESULT"):
            return line.strip()
    return stdout.strip().splitlines()[-1] if stdout.strip() else "(no output)"


def main() -> int:
    EVIDENCE.mkdir(exist_ok=True)
    if not REPORT.exists():
        print(f"ERROR: {REPORT} not found")
        return 1

    print("VALIDATION EXPERIMENT")
    print("=" * 78)
    print(f"report    : {REPORT}")
    print(f"validator : {VALIDATOR}")

    steps: List[Dict[str, Any]] = []
    hash_before = sha256(REPORT)
    print(f"\noriginal sha256 (before): {hash_before}")

    # ---- Step 1: valid report ------------------------------------------
    print("\n[1] validate the completed report")
    first = run_validator(REPORT)
    print(f"    {first['command']}")
    print(f"    exit={first['exit_code']} | {first_result_line(first['stdout'])}")
    steps.append({
        "step": 1,
        "action": "Validate the completed report",
        "command": first["command"],
        "exit_code": first["exit_code"],
        "observed": first_result_line(first["stdout"]),
    })

    # ---- Step 2: tampered copy -----------------------------------------
    print("\n[2] copy the report, delete the required 'research' section, validate")
    temp_dir = Path(tempfile.mkdtemp(prefix="fac100_validation_"))
    tampered_path = temp_dir / "report_missing_research.json"
    try:
        data = json.loads(REPORT.read_text(encoding="utf-8"))
        removed_keys = []
        for key in ("research",):
            if key in data:
                del data[key]
                removed_keys.append(key)
        tampered_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        print(f"    removed top-level keys: {removed_keys}")
        print(f"    tampered copy: <tmp>/{tampered_path.name} (deleted after the run)")

        second = run_validator(tampered_path)
        errors = [ln.strip() for ln in second["stdout"].splitlines() if ln.strip().startswith("- ")]
        detected_research = any("research" in err for err in errors)
        print(f"    {second['command']}")
        print(f"    exit={second['exit_code']} | {first_result_line(second['stdout'])}")
        print(f"    detects missing section: {detected_research}")
        for err in errors[:6]:
            print(f"      {err}")
        steps.append({
            "step": 2,
            "action": "Delete the required 'research' section from a temporary copy and validate it",
            "command": second["command"],
            "exit_code": second["exit_code"],
            "observed": f"{first_result_line(second['stdout'])}; "
                        f"mentions 'research': {detected_research}; "
                        f"{len(errors)} error line(s)",
        })

        # keep a copy of the tampered report as audit evidence
        shutil.copyfile(tampered_path, EVIDENCE / "report_tampered_missing_research.json")
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)

    # ---- Step 3: original untouched ------------------------------------
    print("\n[3] confirm the original report was never modified")
    hash_after = sha256(REPORT)
    unchanged = hash_after == hash_before
    print(f"    original sha256 (after) : {hash_after}")
    print(f"    unchanged               : {unchanged}")
    steps.append({
        "step": 3,
        "action": "Hash the original report before and after the experiment",
        "command": "sha256sum report.json (before vs after)",
        "exit_code": 0 if unchanged else 1,
        "observed": f"before={hash_before} after={hash_after} unchanged={unchanged}",
    })

    # ---- Step 4: valid again -------------------------------------------
    print("\n[4] re-validate the original report")
    fourth = run_validator(REPORT)
    print(f"    {fourth['command']}")
    print(f"    exit={fourth['exit_code']} | {first_result_line(fourth['stdout'])}")
    steps.append({
        "step": 4,
        "action": "Re-validate the original report",
        "command": fourth["command"],
        "exit_code": fourth["exit_code"],
        "observed": first_result_line(fourth["stdout"]),
    })

    codes = [s["exit_code"] for s in steps]
    experiment_passed = (
        codes[0] == 0
        and codes[1] == 1
        and unchanged
        and detected_research
        and codes[3] == 0
        and codes == EXPECTED_CODES
    )

    # ---- consistency with what report.json claims ----------------------
    print("\n[5] cross-check against the steps recorded inside report.json")
    recorded = []
    try:
        recorded = json.loads(REPORT.read_text(encoding="utf-8"))["agentic_execution"]["validation_experiment"]["steps"]
        recorded_codes = [s["exit_code"] for s in recorded]
    except (KeyError, json.JSONDecodeError, TypeError):
        recorded_codes = []
    consistent = recorded_codes == codes
    print(f"    recorded exit codes: {recorded_codes}")
    print(f"    observed exit codes: {codes}")
    print(f"    consistent          : {consistent}")

    payload = {
        "experiment": "validation_experiment",
        "report": str(REPORT.name),
        "validator": str(VALIDATOR.name),
        "original_sha256_before": hash_before,
        "original_sha256_after": hash_after,
        "original_report_unchanged": unchanged,
        "removed_required_section": "research",
        "tampered_copy_kepts_as_evidence": "evidence/report_tampered_missing_research.json",
        "steps": steps,
        "expected_exit_codes": EXPECTED_CODES,
        "observed_exit_codes": codes,
        "missing_section_detected": detected_research,
        "passed": experiment_passed,
        "consistent_with_report_json": consistent,
        "generated_utc": __import__("time").strftime("%Y-%m-%dT%H:%M:%SZ", __import__("time").gmtime()),
    }
    (EVIDENCE / "validation_experiment.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )

    print("\n" + "=" * 78)
    print(f"original_report_unchanged = {unchanged}")
    print(f"missing_section_detected  = {detected_research}")
    print(f"observed exit codes       = {codes} (expected {EXPECTED_CODES})")
    print(f"consistent with report    = {consistent}")
    print(f"EXPERIMENT: {'PASS' if experiment_passed and consistent else 'FAIL'}")
    print(f"evidence: {EVIDENCE / 'validation_experiment.json'}")
    return 0 if (experiment_passed and consistent) else 1


if __name__ == "__main__":
    sys.exit(main())
