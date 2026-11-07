import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
loader = importlib.util.spec_from_file_location("round4_gate", ROOT / "scripts/verify-worktree-round4.py")
gate = importlib.util.module_from_spec(loader)
loader.loader.exec_module(gate)


class RoundFourGateTest(unittest.TestCase):
    def setUp(self):
        self.manifest = gate.base.read_json(gate.SPEC / "round4-acceptance.json")
        self.cases = {suite["className"] + "." + name: "PASS"
                      for suite in self.manifest["suites"] for name in suite["tests"]}

    def test_all_32_cases_are_required(self):
        self.assertEqual(32, len(self.cases))
        self.assertEqual([], gate.repair.repair_errors(self.manifest, self.cases))

    def test_missing_failed_and_skipped_are_rejected(self):
        key = next(iter(self.cases))
        for state in (None, "FAIL", "SKIP"):
            with self.subTest(state=state):
                cases = dict(self.cases)
                if state is None:
                    del cases[key]
                else:
                    cases[key] = state
                self.assertTrue(gate.repair.repair_errors(self.manifest, cases))

    def test_old_19_cases_cannot_replace_new_requirements(self):
        old = gate.base.read_json(gate.SPEC / "repair-acceptance.json")
        old_cases = {suite["className"] + "." + name: "PASS"
                     for suite in old["suites"] for name in suite["tests"]}
        self.assertEqual(32, len(gate.repair.repair_errors(self.manifest, old_cases)))

    def test_docs_and_durable_assets_are_valid(self):
        gate.docs_check(self.manifest)

    def test_duplicate_case_is_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["suites"][0]["tests"][1] = manifest["suites"][0]["tests"][0]
        with self.assertRaises(ValueError):
            gate.docs_check(manifest)

    def test_prior_gate_failure_is_not_waived(self):
        with patch.object(gate.repair, "check_regular_repair_evidence", side_effect=ValueError("old gate rejected")):
            with self.assertRaisesRegex(ValueError, "old gate rejected"):
                gate.check_round4_reports(ROOT / "temp/worktree-evidence/delivery", self.manifest, "fixture")


if __name__ == "__main__":
    unittest.main()
