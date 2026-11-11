#!/usr/bin/env python3
"""Extend the original worktree evidence gate with the GLM repair regressions.

--run collects regular regression/compilation evidence, not final native/manual
acceptance. No flag bypasses the original final evidence requirements.
"""
from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
VERSION = "WT-REPAIR-2026-11-02.1"

loader = importlib.util.spec_from_file_location("worktree_original_gate", ROOT / "scripts/verify-worktree-delivery.py")
base = importlib.util.module_from_spec(loader)
loader.loader.exec_module(base)


def repair_errors(manifest, cases):
    errors = []
    for suite in manifest["suites"]:
        for name in suite["tests"]:
            key = suite["className"] + "." + name
            # Gradle XML may include "()" in testcase names for some test frameworks;
            # suite_errors in the base script uses the same fallback pattern.
            state = cases.get(key) if key in cases else cases.get(key + "()")
            if state != "PASS":
                errors.append(f"{key}: {state or 'MISSING'}")
    return errors


def manifest_check(manifest, original):
    base.manifest_check(original)
    base.require(manifest["specVersion"] == VERSION, "Repair manifest version mismatch")
    base.require(manifest["baseSpecVersion"] == original["specVersion"], "Original spec version mismatch")
    for relative in ["scripts/worktree-review/init.gradle", "scripts/worktree-review/README.md",
                     "scripts/worktree-review/src/xyz/mederi/workspace/WorkspaceReviewRegressionTest.kt"]:
        base.require((ROOT / relative).is_file(), f"Missing durable regression asset: {relative}")
    keys = [suite["className"] + "." + name for suite in manifest["suites"] for name in suite["tests"]]
    base.require(len(keys) == 19 and len(set(keys)) == 19, "Repair requires 19 distinct cases")
    for suite in manifest["suites"]:
        base.require(suite["module"] in ("core", "shared"), "Unknown repair suite module")
    print("Repair manifest valid: 6 reproduced defects + 13 production-chain cases")


def check_regular_repair_evidence(directory, manifest, source):
    promoted = ROOT / "core/src/jvmTest/kotlin/xyz/mederi/workspace/WorkspaceReviewRegressionTest.kt"
    base.require(promoted.is_file(), "Promote the six review regressions into core's regular source set")
    receipt_path = directory / "regular/receipt.json"
    base.require(receipt_path.is_file(), "Missing regular evidence: run --run after implementing fixes")
    receipt = base.read_json(receipt_path)
    cases = base.checked_reports(receipt_path.parent, receipt, source)
    base.require(not receipt.get("errors", []), "Original regular gate has errors")
    errors = repair_errors(manifest, cases)
    base.require(not errors, "\n".join(errors))
    print("PASS: all 19 repair regressions are present, non-skipped and successful.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--manifest-only", "--docs-only", action="store_true")
    modes.add_argument("--run", action="store_true")
    parser.add_argument("--evidence-dir", default="temp/worktree-evidence/delivery")
    args = parser.parse_args()
    manifest = base.read_json(base.ASSETS / "repair-acceptance.json")
    original = base.read_json(base.MANIFEST)
    manifest_check(manifest, original)
    if args.manifest_only:
        return
    directory = (ROOT / args.evidence_dir).resolve()
    allowed = (ROOT / "temp/worktree-evidence").resolve()
    base.require(directory.is_relative_to(allowed), "Evidence must remain under repository temp/worktree-evidence")
    directory.mkdir(parents=True, exist_ok=True)
    source = base.fingerprint()
    print("Current source fingerprint:", source)
    if args.run:
        base.regular_run(directory, original, source)
        check_regular_repair_evidence(directory, manifest, source)
        print("Regular repair evidence passed. Final native/manual acceptance is still a separate gate.")
    else:
        # Check both without offering a mode to waive original native/manual proof.
        check_regular_repair_evidence(directory, manifest, source)
        base.final_check(directory, original, source)
        print("PASS: original worktree acceptance and GLM repair acceptance are complete.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError, base.json.JSONDecodeError) as failure:
        print("NOT ACCEPTED:", failure, file=sys.stderr)
        sys.exit(1)
