#!/usr/bin/env python3
"""Add round-four behavior reports to the existing worktree evidence gates.

This validates report evidence, not the semantic quality of the implementation.
--run is regular automation only; final mode retains native/manual requirements.
"""
from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "docs/design/worktree-spec"
VERSION = "WT-REPAIR-2026-11-03.2"
loader = importlib.util.spec_from_file_location("worktree_repair_gate", ROOT / "scripts/verify-worktree-repair.py")
repair = importlib.util.module_from_spec(loader)
loader.loader.exec_module(repair)
base = repair.base


def docs_check(manifest):
    original = base.read_json(base.MANIFEST)
    previous = base.read_json(SPEC / "repair-acceptance.json")
    repair.docs_check(previous, original)
    base.require(manifest["specVersion"] == VERSION, "Round-four version mismatch")
    base.require(manifest["baseSpecVersion"] == original["specVersion"], "Original version mismatch")
    base.require(manifest["previousRepairVersion"] == previous["specVersion"], "Previous repair version mismatch")
    base.require((SPEC / "ROUND4-REPAIR.md").is_file(), "Missing round-four instructions")
    base.require((SPEC / "round4-spec.md").is_file(), "Missing round-four implementation spec")
    keys = [suite["className"] + "." + name for suite in manifest["suites"] for name in suite["tests"]]
    base.require(len(keys) == 32 and len(set(keys)) == 32, "Round four requires 32 distinct cases")
    for suite in manifest["suites"]:
        base.require(suite["module"] in ("core", "shared"), "Unknown module")
    for name in ["WorkspaceProductionThirdReviewTest", "WorkspaceLifecycleThirdReviewTest",
                 "WorkspaceJournalThirdReviewTest", "WorkspaceRollbackThirdReviewTest"]:
        asset = ROOT / f"scripts/worktree-review-round4/src/xyz/mederi/workspace/{name}.kt"
        base.require(asset.is_file(), f"Missing independent regression asset: {name}")
    print("Round-four package valid: 7 tasks, 7 new reproductions + 5 previous cases + 20 production invariants")
    return original, previous


def check_round4_reports(directory, manifest, source):
    # This also validates checksums, the final source fingerprint, and all 19 previous cases.
    previous = base.read_json(SPEC / "repair-acceptance.json")
    repair.check_regular_repair_evidence(directory, previous, source)
    receipt_path = directory / "regular/receipt.json"
    receipt = base.read_json(receipt_path)
    cases = base.checked_reports(receipt_path.parent, receipt, source)
    errors = repair.repair_errors(manifest, cases)
    base.require(not errors, "\n".join(errors))
    for suite in manifest["suites"]:
        name = suite["className"].split(".")[-1]
        path = ROOT / ("core/src/jvmTest/kotlin/xyz/mederi/workspace" if suite["module"] == "core"
                       else "app/shared/src/jvmTest/kotlin/xyz/mederi/ui/workspace") / f"{name}.kt"
        base.require(path.is_file(), f"Required class is not in the regular test source set: {path}")
    print("PASS: all 32 round-four cases are present, non-skipped and successful.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--docs-only", action="store_true")
    modes.add_argument("--run", action="store_true")
    parser.add_argument("--evidence-dir", default="temp/worktree-evidence/delivery")
    args = parser.parse_args()
    manifest = base.read_json(SPEC / "round4-acceptance.json")
    original, _ = docs_check(manifest)
    if args.docs_only:
        return
    directory = (ROOT / args.evidence_dir).resolve()
    base.require(directory.is_relative_to((ROOT / "temp/worktree-evidence").resolve()), "Evidence must remain under temp/worktree-evidence")
    directory.mkdir(parents=True, exist_ok=True)
    source = base.fingerprint()
    print("Current source fingerprint:", source)
    if args.run:
        base.regular_run(directory, original, source)
        check_round4_reports(directory, manifest, source)
        print("Regular automation passed. Native/manual acceptance and semantic review remain separate.")
    else:
        check_round4_reports(directory, manifest, source)
        base.final_check(directory, original, source)
        print("PASS: original and round-four evidence gates passed. Implementation still requires review.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError, base.json.JSONDecodeError) as failure:
        print("NOT ACCEPTED:", failure, file=sys.stderr)
        sys.exit(1)
