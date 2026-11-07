"""Tests for the evidence gate, using independent fixture reports, not product evidence."""
import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
MODULE = importlib.util.spec_from_file_location("worktree_gate", ROOT / "scripts/verify-worktree-delivery.py")
gate = importlib.util.module_from_spec(MODULE)
MODULE.loader.exec_module(gate)


class WorktreeEvidenceGateTest(unittest.TestCase):
    def setUp(self):
        (ROOT / "temp").mkdir(exist_ok=True)
        self.scratch = tempfile.TemporaryDirectory(prefix="worktree-gate-test-", dir=ROOT / "temp")
        self.directory = Path(self.scratch.name)

    def tearDown(self):
        self.scratch.cleanup()

    def report(self, name="fixture.Suite", method="behavior", state="PASS", path=None):
        path = path or self.directory / "report.xml"
        body = {"PASS": "", "FAIL": '<failure message="fixture failure"/>', "SKIP": "<skipped/>"}[state]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'<testsuite><testcase classname="{name}" name="{method}[jvm]">{body}</testcase></testsuite>')
        return path

    def baseline(self):
        directory = self.directory / "baseline"
        directory.mkdir()
        report = self.report(path=directory / "baseline.xml")
        gate.write_json(directory / "receipt.json", gate.make_receipt(directory, [report], "baseline-source", failedCases=[]))

    @staticmethod
    def manifest():
        return dict(suites=[dict(className="fixture.Suite", platform="any", tests=["behavior"])],
                    requiredNativePlatforms=["macos", "linux"], requiredCompilations=[], manualScenarios=[])

    def test_missing_failed_and_skipped_tests_cannot_pass(self):
        for state in ("FAIL", "SKIP"):
            cases = gate.cases_in([self.report(state=state)])
            self.assertTrue(gate.suite_errors(self.manifest(), cases))
        self.assertTrue(gate.suite_errors(self.manifest(), {}))
        self.assertEqual([], gate.suite_errors(self.manifest(), gate.cases_in([self.report()])))

    def test_duplicate_report_cases_are_rejected(self):
        first = self.report(path=self.directory / "first.xml")
        second = self.report(path=self.directory / "second.xml")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            gate.cases_in([first, second])

    def test_report_checksum_changes_are_rejected(self):
        report = self.report()
        receipt = gate.make_receipt(self.directory, [report], "source")
        report.write_text(report.read_text().replace("behavior", "different"))
        with self.assertRaisesRegex(ValueError, "checksum"):
            gate.checked_reports(self.directory, receipt, "source")

    def test_receipt_from_another_source_is_rejected(self):
        receipt = gate.make_receipt(self.directory, [self.report()], "old-source")
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            gate.checked_reports(self.directory, receipt, "new-source")

    def test_paths_cannot_escape_evidence_directory(self):
        with self.assertRaisesRegex(ValueError, "escapes"):
            gate.inside_evidence(self.directory, "../outside.xml")

    def test_native_host_mismatch_never_starts_gradle(self):
        with patch.object(gate, "host_platform", return_value="macos"), patch.object(gate, "run_gradle") as run:
            with self.assertRaisesRegex(ValueError, "cannot run"):
                gate.native_run(self.directory, self.manifest(), "source", "linux")
            run.assert_not_called()

    def test_final_gate_does_not_accept_regular_tests_without_native_evidence(self):
        self.baseline()
        regular = self.directory / "regular"
        regular.mkdir()
        report = self.report(path=regular / "report.xml")
        gate.write_json(regular / "receipt.json", gate.make_receipt(regular, [report], "source", errors=[], compilations=[]))
        gate.write_json(self.directory / "manual.json", dict(specVersion=gate.VERSION, sourceFingerprint="source",
                        unresolvedDifferences=[], desktopAppVersion="fixture", codexAppVersion="fixture",
                        mobileClient="fixture", verifiedAt="fixture", scenarios=[]))
        spec = self.directory / "spec"
        spec.mkdir()
        (spec / "delivery.md").write_text("fixture")
        with patch.object(gate, "SPEC", spec):
            with self.assertRaisesRegex(ValueError, "Missing native"):
                gate.final_check(self.directory, self.manifest(), "source")

    def test_final_gate_requires_concrete_manual_artifacts(self):
        self.baseline()
        regular = self.directory / "regular"
        regular.mkdir()
        report = self.report(path=regular / "report.xml")
        gate.write_json(regular / "receipt.json", gate.make_receipt(regular, [report], "source", errors=[], compilations=[]))
        manifest = self.manifest()
        manifest["requiredNativePlatforms"] = []
        manifest["manualScenarios"] = [dict(id="desktop-create")]
        gate.write_json(self.directory / "manual.json", dict(specVersion=gate.VERSION, sourceFingerprint="source",
                        unresolvedDifferences=[], desktopAppVersion="fixture", codexAppVersion="fixture",
                        mobileClient="fixture", verifiedAt="fixture",
                        scenarios=[dict(id="desktop-create", status="PASS", observed="a sufficiently concrete fixture observation", artifacts=[])]))
        with self.assertRaisesRegex(ValueError, "Missing artifacts"):
            gate.final_check(self.directory, manifest, "source")

    def test_final_gate_requires_preimplementation_baseline(self):
        regular = self.directory / "regular"
        regular.mkdir()
        report = self.report(path=regular / "report.xml")
        gate.write_json(regular / "receipt.json", gate.make_receipt(regular, [report], "source", errors=[], compilations=[]))
        with self.assertRaisesRegex(ValueError, "baseline"):
            gate.final_check(self.directory, self.manifest(), "source")

    def test_real_spec_manifest_is_consistent(self):
        with contextlib.redirect_stdout(io.StringIO()):
            gate.docs_check(gate.read_json(gate.MANIFEST))


if __name__ == "__main__":
    unittest.main()
