from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read_text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8-sig")


class TestSuiteOrchestratorContractTest(unittest.TestCase):
    REQUIRED_FILES = [
        "scripts/run-test-suite.ps1",
        "scripts/lib/TestOrchestrator.ps1",
        "scripts/lib/TestResult.ps1",
        "scripts/lib/ReleaseGate.ps1",
        "TEST-ORCHESTRATOR-V3.0.md",
    ]

    def test_required_orchestrator_files_exist(self) -> None:
        for relative in self.REQUIRED_FILES:
            self.assertTrue((ROOT / relative).is_file(), relative)

    def test_entrypoint_exposes_profiles_resume_and_experimental_switch(self) -> None:
        text = read_text("scripts/run-test-suite.ps1")
        self.assertRegex(text, r"ValidateSet\(['\"]Smoke['\"],['\"]Regression['\"],['\"]Release['\"]\)")
        for token in ["$Profile", "$Serial", "$Resume", "$IncludeExperimental"]:
            self.assertIn(token, text)

    def test_orchestrator_reuses_existing_harnesses_instead_of_reimplementing_them(self) -> None:
        text = read_text("scripts/lib/TestOrchestrator.ps1")
        for script in [
            "run-ui-smoke.ps1",
            "run-performance-baseline.ps1",
            "run-camera-face-soak.ps1",
            "run-face-punch-stress.ps1",
            "run-network-fault-sync.ps1",
            "run-db-stress.ps1",
            "run-process-recovery.ps1",
        ]:
            self.assertIn(script, text)


    def test_ui_smoke_gate_surfaces_child_failure_reason_and_avoids_duplicate_restore(self) -> None:
        orchestrator = read_text("scripts/lib/TestOrchestrator.ps1")
        self.assertIn("function Get-UiSmokeFailureReason", orchestrator)
        self.assertIn("ui-smoke-summary.txt", orchestrator)
        self.assertIn("failure_reason=", orchestrator)
        self.assertIn("production_restore_succeeded=true", orchestrator)

    def test_db_stress_gate_surfaces_child_restore_failure_reason(self) -> None:
        orchestrator = read_text("scripts/lib/TestOrchestrator.ps1")
        self.assertIn("function Get-DbStressFailureReason", orchestrator)
        self.assertIn("restore_failure=", orchestrator)
        self.assertIn("Get-DbStressFailureReason -StageDirectory $stageDirectory", orchestrator)

    def test_process_recovery_gate_surfaces_child_scenario_failure_reason(self) -> None:
        orchestrator = read_text("scripts/lib/TestOrchestrator.ps1")
        self.assertIn("function Get-ProcessRecoveryFailureReason", orchestrator)
        self.assertIn("transaction.txt", orchestrator)
        self.assertIn("scenario_failures=", orchestrator)
        self.assertIn("restore_failure=", orchestrator)
        self.assertIn("Get-ProcessRecoveryFailureReason -StageDirectory $stageDirectory", orchestrator)
        self.assertIn('process recovery failed exit=${exitCode}: $processFailure', orchestrator)

    def test_release_profile_contains_required_release_gates(self) -> None:
        text = read_text("scripts/lib/TestOrchestrator.ps1")
        for stage in [
            "EnvironmentPreflight",
            "SourceIsolationAudit",
            "UnitTests",
            "AssembleDebug",
            "UiSmoke",
            "PerformanceBaseline",
            "CameraFaceSoak",
            "FacePunchStress",
            "NetworkFault",
            "DbStress1000",
            "DbStress5000",
            "ProcessRecovery",
            "AssembleRelease",
            "ApkIsolationAudit",
        ]:
            self.assertIn(stage, text)

    def test_v273_is_experimental_and_not_in_default_release_stage_list(self) -> None:
        text = read_text("scripts/lib/TestOrchestrator.ps1")
        self.assertIn("CameraFaceRecoveryExperimental", text)
        self.assertIn("IncludeExperimental", text)
        release_match = re.search(
            r"(?s)function Get-TestSuiteStages.*?\$releaseStages\s*=\s*@\((.*?)\)\s*\n.*?if\s*\(\$IncludeExperimental\)",
            text,
        )
        self.assertIsNotNone(release_match)
        self.assertNotIn("CameraFaceRecoveryExperimental", release_match.group(1))

    def test_entrypoint_resolves_single_device_automatically(self) -> None:
        text = read_text("scripts/run-test-suite.ps1")
        self.assertIn("Resolve-AndroidSerial", text)
        self.assertRegex(text, r"Resolve-AndroidSerial\s+-RequestedSerial\s+\$resolvedSerial")

    def test_results_are_persisted_for_resume_and_human_readable_summary(self) -> None:
        entry = read_text("scripts/run-test-suite.ps1")
        result_lib = read_text("scripts/lib/TestResult.ps1")
        for token in ["state.json", "summary.json", "summary.md"]:
            self.assertIn(token, entry + result_lib)
        for token in ["PASS", "FAIL", "BLOCKED", "SKIPPED"]:
            self.assertIn(token, result_lib)
        self.assertIn("Resume", entry)
        self.assertIn("Import-TestSuiteState", entry + result_lib)

    def test_failure_path_runs_restore_sweep_in_finally(self) -> None:
        entry = read_text("scripts/run-test-suite.ps1")
        self.assertRegex(entry, r"(?s)try\s*\{.*?Invoke-TestSuite.*?\}\s*finally\s*\{.*?Invoke-TestSuiteRestoreSweep")
        orchestrator = read_text("scripts/lib/TestOrchestrator.ps1")
        self.assertIn("restore-production-from-report.ps1", orchestrator)
        self.assertIn("production-backup", orchestrator)

    def test_release_gate_audits_source_defaults_and_apk_forbidden_tokens(self) -> None:
        text = read_text("scripts/lib/ReleaseGate.ps1")
        for token in [
            "camera_face_soak_auto_enable",
            "face_punch_stress_bypass_prechecks",
            "CameraFaceRecoveryTest",
            "ProcessRecoveryTest",
            "DbStressTest",
            "NetworkFaultTest",
            "FacePunchStress",
            "RECOVERY_FACE_V273",
            "STRESS_FACE_V23",
            "PunchPersistence.persist",
            "ACTION_PUNCH_PUSH",
        ]:
            self.assertIn(token, text)
        self.assertIn("classes", text)
        self.assertIn("ZipArchive", text)

    def test_orchestrator_avoids_ps51_new_object_generic_list_array_subexpression_bug(self) -> None:
        text = read_text("scripts/lib/TestOrchestrator.ps1")
        self.assertNotIn("New-Object System.Collections.Generic.List[object]", text)
        self.assertNotIn("-Results @($results)", text)
        self.assertNotIn("return @($results)", text)
        self.assertIn("$results.ToArray()", text)

    def test_orchestrator_avoids_unbraced_variable_before_colon_in_double_quoted_strings(self) -> None:
        text = read_text("scripts/lib/TestOrchestrator.ps1")
        unsafe = re.findall(r'"[^"\r\n]*\$[A-Za-z_][A-Za-z0-9_]*:\s', text)
        self.assertEqual([], unsafe, f"PowerShell 5.1 parser-unsafe interpolation(s): {unsafe}")

    def test_powershell_files_are_utf8_bom_crlf_for_windows_ps51(self) -> None:
        for relative in self.REQUIRED_FILES[:-1]:
            raw = (ROOT / relative).read_bytes()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), relative)
            body = raw[3:]
            self.assertIn(b"\r\n", body, relative)
            self.assertNotIn(b"\n", body.replace(b"\r\n", b""), relative)


if __name__ == "__main__":
    unittest.main()
