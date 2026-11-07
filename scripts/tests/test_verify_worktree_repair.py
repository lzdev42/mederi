"""Evidence-gate checks only; these do not simulate product regression success."""
import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
loader = importlib.util.spec_from_file_location("worktree_repair_gate", ROOT / "scripts/verify-worktree-repair.py")
gate = importlib.util.module_from_spec(loader)
loader.loader.exec_module(gate)


class WorktreeRepairGateTest(unittest.TestCase):
    def setUp(self):
        self.manifest = gate.base.read_json(gate.SPEC / "repair-acceptance.json")

    def cases(self):
        return {suite["className"] + "." + name: "PASS"
                for suite in self.manifest["suites"] for name in suite["tests"]}

    def test_requires_all_nineteen_independent_cases(self):
        cases = self.cases()
        self.assertEqual(19, len(cases))
        self.assertEqual([], gate.repair_errors(self.manifest, cases))
        cases.pop(next(iter(cases)))
        self.assertIn("MISSING", gate.repair_errors(self.manifest, cases)[0])

    def test_failed_or_skipped_repair_case_is_not_accepted(self):
        for state in ("FAIL", "SKIP"):
            cases = self.cases()
            cases[next(iter(cases))] = state
            self.assertIn(state, gate.repair_errors(self.manifest, cases)[0])

    def test_original_passes_do_not_substitute_for_repair_tests(self):
        self.assertEqual(19, len(gate.repair_errors(self.manifest, {"old.Suite.existing": "PASS"})))

    def test_real_documents_and_assets_are_complete(self):
        with contextlib.redirect_stdout(io.StringIO()):
            gate.docs_check(self.manifest, gate.base.read_json(gate.base.MANIFEST))

    def test_duplicate_manifest_cases_are_rejected(self):
        self.manifest["suites"][0]["tests"][1] = self.manifest["suites"][0]["tests"][0]
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ValueError):
            gate.docs_check(self.manifest, gate.base.read_json(gate.base.MANIFEST))


if __name__ == "__main__":
    unittest.main()
