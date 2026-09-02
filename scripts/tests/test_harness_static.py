from __future__ import annotations

import re
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def read_text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class ProductionEndpointContractTest(unittest.TestCase):
    def test_default_business_base_url_uses_current_lan_server(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/utils/Constants.java")
        self.assertIn(
            'public static final String DEFAULT_BASE_URL = "http://192.168.111.240";',
            text,
        )
        self.assertNotIn("http://hzmq1.hainasmart.com.cn", text)

    def test_device_activation_server_remains_unchanged(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/network/ApiEndpoints.java")
        self.assertIn(
            'public static final String DEVICE_ACTIVATE_BASE_URL = "http://park.hainasmart.com.cn:8898";',
            text,
        )


class SmokeVariantContractTest(unittest.TestCase):
    def test_gradle_targets_smoke_for_instrumentation(self) -> None:
        gradle = read_text("app/build.gradle")
        self.assertRegex(gradle, r'(?m)^\s*testBuildType\s+[\'\"]smoke[\'\"]\s*$')

    def test_smoke_has_kiosk_home_test_host(self) -> None:
        path = ROOT / "app/src/smoke/java/com/punch/app/activity/UiTestKioskHomeHostActivity.java"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        self.assertIn("extends KioskHomeActivity", text)
        self.assertIn("void allowFinish()", text)

    def test_debug_has_setup_wizard_test_host(self) -> None:
        path = ROOT / "app/src/debug/java/com/punch/app/activity/UiTestSetupWizardHostActivity.java"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        self.assertIn("extends SetupWizardActivity", text)
        self.assertIn("KioskManager.setUiTestBypassForTest(true)", text)

    def test_smoke_manifest_registers_all_test_hosts(self) -> None:
        manifest = ROOT / "app/src/smoke/AndroidManifest.xml"
        root = ET.parse(manifest).getroot()
        application = root.find("application")
        self.assertIsNotNone(application)
        names = {
            node.attrib.get(ANDROID_NS + "name")
            for node in application.findall("activity")
        }
        expected = {
            ".activity.UiTestLoginHostActivity",
            ".activity.UiTestAdvancedConfigHostActivity",
            ".activity.UiTestSetupWizardHostActivity",
            ".activity.UiTestKioskHomeHostActivity",
        }
        self.assertTrue(expected.issubset(names), f"missing={expected - names}")


class ScriptContractTest(unittest.TestCase):
    REQUIRED_SCRIPTS = [
        "scripts/lib/DeviceTestCommon.ps1",
        "scripts/run-ui-smoke.ps1",
        "scripts/run-device-tests.ps1",
        "scripts/run-regression.ps1",
        "scripts/collect-device-metrics.ps1",
        "scripts/restore-production-from-report.ps1",
    ]

    def test_required_scripts_exist(self) -> None:
        missing = [path for path in self.REQUIRED_SCRIPTS if not (ROOT / path).is_file()]
        self.assertEqual([], missing)

    def test_v1_scripts_do_not_contain_destructive_device_operations(self) -> None:
        forbidden = [
            r"dpm\s+set-device-owner",
            r"dpm\s+remove-active-admin",
            r"\bpm\s+clear\b",
            r"\badb(?:\.exe)?\b[^\r\n]*\breboot\b",
            r"\badb(?:\.exe)?\b[^\r\n]*\buninstall\b",
            r"recovery\s+--wipe_data",
        ]
        for relative in self.REQUIRED_SCRIPTS:
            text = read_text(relative)
            for pattern in forbidden:
                self.assertIsNone(
                    re.search(pattern, text, re.IGNORECASE),
                    f"{relative} contains forbidden operation matching {pattern}",
                )

    def test_common_adb_helper_always_targets_serial(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("function Invoke-Adb", text)
        self.assertRegex(text, r'(?s)function\s+Invoke-Adb.*?-s.*?\$Serial')

    def test_native_command_helpers_neutralize_stderr_erroractionpreference(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("function Invoke-NativeCapture", text)
        self.assertIn("function Invoke-LoggedCommand", text)
        self.assertRegex(text, r'(?s)function\s+Invoke-NativeCapture.*?ErrorActionPreference\s*=\s*[\'"]Continue[\'"]')
        self.assertRegex(text, r'(?s)function\s+Invoke-LoggedCommand.*?ErrorActionPreference\s*=\s*[\'"]Continue[\'"]')
        self.assertIn('Invoke-NativeCapture -FilePath "java"', text)
        self.assertIn("Invoke-NativeCapture -FilePath $gradlew", text)

    def test_logged_command_stringifies_native_stderr_before_host_rendering(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        body = re.search(r'(?s)function\s+Invoke-LoggedCommand\s*\{(.*?)\n\}', text)
        self.assertIsNotNone(body)
        body_text = body.group(1)
        self.assertNotIn('2>&1 | Tee-Object', body_text)
        self.assertRegex(body_text, r'2>&1\s*\|\s*ForEach-Object\s*\{[^}]*\$_\.ToString\(\)')
        self.assertIn('Tee-Object -FilePath $LogPath', body_text)
        self.assertIn('Write-Host', body_text)


    def test_smoke_runner_builds_and_runs_expected_instrumentation(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn(":app:assembleSmoke", text)
        self.assertIn(":app:assembleSmokeAndroidTest", text)
        self.assertIn("com.punch.app.smoke.test/com.punch.app.test.UiSmokeTestRunner", text)
        self.assertIn("install", text)
        self.assertIn("am", text)
        self.assertIn("instrument", text)
        self.assertIn("finally", text)
        self.assertIn("Find-AppFatalEvents", text)


    def test_smoke_runner_has_device_owner_preflight_and_timeout(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn("InstrumentationTimeoutSeconds", text)
        self.assertIn("Get-SmokeDeviceConflict", text)
        self.assertIn("device-policy.txt", text)
        self.assertIn("activity-state.txt", text)
        self.assertIn("Invoke-AdbWithTimeout", text)

    def test_common_library_detects_device_owner_kiosk_conflicts(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("function Get-SmokeDeviceConflict", text)
        self.assertIn("function Invoke-AdbWithTimeout", text)
        self.assertIn("dpm", text)
        self.assertIn("list-owners", text)
        self.assertIn("dumpsys", text)
        self.assertIn("device_policy", text)
        self.assertIn("mResumedActivity", text)
        self.assertIn("mLockTaskModeState", text)

    def test_smoke_runner_can_opt_in_to_temporarily_stop_production_kiosk(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn("StopProductionAppForSmoke", text)
        self.assertIn("ProductionPackageName", text)
        self.assertIn("force-stop", text)
        self.assertIn("KioskHomeActivity", text)

    def test_smoke_timeout_defaults_to_bounded_value(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        match = re.search(r"\[int\]\$InstrumentationTimeoutSeconds\s*=\s*(\d+)", text)
        self.assertIsNotNone(match)
        seconds = int(match.group(1))
        self.assertGreaterEqual(seconds, 60)
        self.assertLessEqual(seconds, 600)

    def test_daily_runner_includes_unit_and_smoke_gates(self) -> None:
        text = read_text("scripts/run-device-tests.ps1")
        self.assertIn(":app:testDebugUnitTest", text)
        self.assertIn("run-ui-smoke.ps1", text)
        self.assertIn("StopProductionAppForSmoke", text)
        self.assertIn("InstrumentationTimeoutSeconds", text)

    def test_regression_runner_contains_all_local_quality_gates(self) -> None:
        text = read_text("scripts/run-regression.ps1")
        for task in [
            ":app:testDebugUnitTest",
            ":app:assembleDebug",
            ":app:assembleRelease",
            ":app:lintDebug",
        ]:
            self.assertIn(task, text)
        self.assertIn("run-ui-smoke.ps1", text)
        self.assertIn("SkipDevice", text)
        self.assertIn("StopProductionAppForSmoke", text)
        self.assertIn("InstrumentationTimeoutSeconds", text)

    def test_metrics_collector_captures_core_signals(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        for token in [
            "dumpsys meminfo",
            "dumpsys battery",
            "dumpsys thermalservice",
            "dumpsys cpuinfo",
            "df /data",
        ]:
            self.assertIn(token, text)

    def test_primary_scripts_use_timestamped_test_results(self) -> None:
        common = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("test-results", common)
        self.assertIn("yyyyMMdd-HHmmss", common)


    def test_db_stress_restore_retries_fresh_target_when_locktask_is_not_active(self) -> None:
        text = read_text("scripts/run-db-stress.ps1")
        self.assertIn("force_fresh_target", text)
        self.assertIn("restore-kiosk-retry.log", text)
        self.assertIn("restore-kiosk-state-retry.txt", text)
        self.assertIn("IsProductionForeground", text)
        self.assertIn("IsExpectedRoute", text)
        self.assertIn("IsDeviceOwner", text)
        self.assertIn("-not $kioskRestoreState.IsLockTaskActive", text)
        self.assertIn("Wait-DbStressProductionKioskReady", text)

    def test_manual_restore_script_uses_backup_and_restores_kiosk(self) -> None:
        text = read_text("scripts/restore-production-from-report.ps1")
        self.assertIn("production-backup", text)
        self.assertIn("Restore-InstalledPackageApks", text)
        self.assertIn("KioskHomeActivity", text)
        self.assertIn("debug.punch.device_test_maintenance", text)


class DeviceOwnerMaintenanceBridgeContractTest(unittest.TestCase):
    def test_gradle_defines_device_owner_test_build_without_suffix(self) -> None:
        gradle = read_text("app/build.gradle")
        self.assertRegex(gradle, r"(?s)deviceOwnerTest\s*\{.*?initWith\s+debug.*?signingConfig\s+signingConfigs\.debug")
        block = re.search(r"(?s)deviceOwnerTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertNotIn("applicationIdSuffix", block.group(1))

    def test_device_owner_test_manifest_exposes_only_maintenance_activity(self) -> None:
        manifest = ROOT / "app/src/deviceOwnerTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        root = ET.parse(manifest).getroot()
        app = root.find("application")
        self.assertIsNotNone(app)
        self.assertEqual(".DeviceOwnerTestApplication", app.attrib.get(ANDROID_NS + "name"))
        activities = {n.attrib.get(ANDROID_NS + "name"): n for n in app.findall("activity")}
        node = activities.get(".activity.DeviceOwnerTestControlActivity")
        self.assertIsNotNone(node)
        self.assertEqual("true", node.attrib.get(ANDROID_NS + "exported"))

    def test_device_owner_test_sources_exist(self) -> None:
        expected = [
            "app/src/deviceOwnerTest/java/com/punch/app/DeviceOwnerTestApplication.java",
            "app/src/deviceOwnerTest/java/com/punch/app/DeviceOwnerTestRuntimeFlags.java",
            "app/src/deviceOwnerTest/java/com/punch/app/activity/DeviceOwnerTestControlActivity.java",
            "app/src/deviceOwnerTest/java/com/punch/app/receiver/DeviceOwnerTestControlReceiver.java",
        ]
        for relative in expected:
            self.assertTrue((ROOT / relative).is_file(), relative)

    def test_kiosk_manager_has_maintenance_mode_policy_controls(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/utils/KioskManager.java")
        for token in [
            "setDeviceTestMaintenanceModeForTest",
            "isDeviceTestMaintenanceModeForTest",
            "enterDeviceTestMaintenanceMode",
            "restoreDeviceOwnerKioskAfterTest",
            "setPackagesSuspended",
            "clearPackagePersistentPreferredActivities",
        ]:
            self.assertIn(token, text)

    def test_kiosk_enter_uses_activity_as_policy_context(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/utils/KioskManager.java")
        match = re.search(
            r"(?s)private static void enterIfPossible\(Activity activity, int attempt\) \{(.*?)\n    \}",
            text,
        )
        self.assertIsNotNone(match)
        body = match.group(1)
        self.assertIn("ensureOwnerKioskPolicies(activity);", body)
        self.assertNotIn("ensureOwnerKioskPolicies(context);", body)

    def test_application_suppresses_watchdog_during_maintenance(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/PunchApplication.java")
        self.assertIn("isDeviceTestMaintenanceModeForTest", text)
        self.assertRegex(text, r"(?s)kioskForegroundWatchdogRunnable.*?isDeviceTestMaintenanceModeForTest")

    def test_smoke_runner_supports_transactional_device_owner_bridge(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        for token in [
            "UseDeviceOwnerMaintenanceBridge",
            ":app:assembleDeviceOwnerTest",
            "Backup-InstalledPackageApks",
            "Restore-InstalledPackageApks",
            "debug.punch.device_test_maintenance",
            "DeviceOwnerTestControlReceiver",
            "maintenance-enter",
            "production-backup",
        ]:
            self.assertIn(token, text)


    def test_manual_restore_requires_real_locktask_and_retries_fresh_target(self) -> None:
        text = read_text("scripts/restore-production-from-report.ps1")
        self.assertIn("force_fresh_target", text)
        self.assertIn("IsDeviceOwner", text)
        self.assertIn("IsProductionForeground", text)
        self.assertIn("IsLockTaskActive", text)
        self.assertIn("manual-restore-kiosk-retry.log", text)
        self.assertRegex(text, r"(?s)if \(-not \$finalKioskState\.IsLockTaskActive\).*?throw")

    def test_restore_production_script_does_not_shadow_readonly_pid_automatic_variable(self) -> None:
        text = read_text("scripts/restore-production-from-report.ps1")
        self.assertIsNone(
            re.search(r"(?im)^\s*\$pid\s*=", text),
            "restore-production-from-report.ps1 must not assign to PowerShell's readonly $PID automatic variable",
        )
        self.assertIn("$productionPidText", text)

    def test_ui_smoke_failure_reports_instrumentation_diagnostics(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn("function Get-InstrumentationFailureDetail", text)
        self.assertIn("instrumentation_tail=", text)
        self.assertIn("failure_marker=", text)
        self.assertIn("production_restore_succeeded=", text)
        self.assertLess(text.index("failure_marker="), text.index("missing_success_marker="))

    def test_common_library_can_backup_and_restore_installed_apks(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("function Backup-InstalledPackageApks", text)
        self.assertIn("function Restore-InstalledPackageApks", text)
        self.assertIn("pm", text)
        self.assertIn("path", text)
        self.assertIn("install-multiple", text)

    def test_runner_never_force_stops_active_production_device_owner(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        self.assertNotIn('@("shell", "am", "force-stop", $ProductionPackageName)', text)

    def test_maintenance_control_is_not_declared_in_release_or_smoke_manifests(self) -> None:
        for relative in ["app/src/main/AndroidManifest.xml", "app/src/smoke/AndroidManifest.xml"]:
            text = read_text(relative)
            self.assertNotIn("DeviceOwnerTestControlReceiver", text)

    def test_v14_maintenance_bridge_uses_broadcast_receiver(self) -> None:
        manifest = read_text("app/src/deviceOwnerTest/AndroidManifest.xml")
        self.assertIn(".receiver.DeviceOwnerTestControlReceiver", manifest)
        runner = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn('"broadcast"', runner)
        self.assertIn("DeviceOwnerTestControlReceiver", runner)

    def test_v14_maintenance_revokes_all_lock_task_authorization(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/utils/KioskManager.java")
        match = re.search(r"public static boolean enterDeviceTestMaintenanceMode\(Context context, String\[\] testPackages\)(.*?)(?=\n    public static boolean restoreDeviceOwnerKioskAfterTest)", text, re.S)
        self.assertIsNotNone(match, "Context-based maintenance method must exist")
        body = match.group(1)
        self.assertIn("dpm.setLockTaskPackages(admin, new String[]{})", body)
        self.assertNotRegex(body, r"(?<![A-Za-z0-9_.])stopLockTask\s*\(")
        self.assertNotIn("mergePackages", body)

    def test_v14_harness_polls_for_maintenance_readiness(self) -> None:
        common = read_text("scripts/lib/DeviceTestCommon.ps1")
        runner = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn("function Wait-DeviceOwnerMaintenanceReady", common)
        self.assertIn("Wait-DeviceOwnerMaintenanceReady", runner)
        self.assertIn("MaintenanceReadyTimeoutSeconds", runner)


class PerformanceHarnessV21ContractTest(unittest.TestCase):
    REQUIRED_V2_SCRIPTS = [
        "scripts/lib/PerformanceMetrics.ps1",
        "scripts/run-performance-baseline.ps1",
        "scripts/run-memory-soak.ps1",
    ]

    def test_v21_performance_scripts_exist(self) -> None:
        missing = [path for path in self.REQUIRED_V2_SCRIPTS if not (ROOT / path).is_file()]
        self.assertEqual([], missing)

    def test_performance_library_collects_required_runtime_signals(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        for token in [
            "function Get-AppPerformanceSample",
            "dumpsys\", \"meminfo",
            "dumpsys\", \"cpuinfo",
            "/proc/$appPid/task",
            "/proc/$appPid/fd",
            "dumpsys\", \"battery",
            "df\", \"/data",
            "TotalPssMb",
            "JavaHeapMb",
            "NativeHeapMb",
            "RssMb",
            "CpuPercent",
            "ThreadCount",
            "FdCount",
            "BatteryTemperatureC",
        ]:
            self.assertIn(token, text)

    def test_performance_analysis_uses_warmup_and_stable_window_medians(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        for token in [
            "function Get-MemorySoakAnalysis",
            "WarmupMinutes",
            "Get-Median",
            "stableSamples",
            "firstWindow",
            "lastWindow",
            "PssGrowthPercent",
            "NativeHeapGrowthPercent",
            "JavaHeapGrowthPercent",
            "PssSlopeMbPerHour",
            "CpuP95Percent",
        ]:
            self.assertIn(token, text)

    def test_memory_soak_default_is_30_minutes_at_60_seconds(self) -> None:
        text = read_text("scripts/run-memory-soak.ps1")
        self.assertRegex(text, r"\[int\]\$Minutes\s*=\s*30")
        self.assertRegex(text, r"\[int\]\$IntervalSeconds\s*=\s*60")
        self.assertRegex(text, r"\[int\]\$WarmupMinutes\s*=\s*5")

    def test_memory_soak_generates_csv_text_html_and_raw_evidence(self) -> None:
        text = read_text("scripts/run-memory-soak.ps1")
        for token in [
            "metrics.csv",
            "summary.txt",
            "report.html",
            "raw-samples",
            "logcat.txt",
            "fatal-events.txt",
            "Export-Csv",
            "Write-PerformanceHtmlReport",
            "Find-AppFatalEvents",
        ]:
            self.assertIn(token, text)

    def test_memory_soak_has_hard_gates_for_restart_fatal_and_resource_growth(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        for token in [
            "ProcessRestartCount",
            "FatalEventCount",
            "MaxPssGrowthPercent",
            "MaxNativeHeapGrowthPercent",
            "MaxJavaHeapGrowthPercent",
            "MaxThreadGrowth",
            "MaxFdGrowth",
            "Status",
        ]:
            self.assertIn(token, text)

    def test_v21_scripts_are_non_destructive(self) -> None:
        forbidden = [
            r"dpm\s+set-device-owner",
            r"dpm\s+remove-active-admin",
            r"\bpm\s+clear\b",
            r"\bforce-stop\b",
            r"\breboot\b",
            r"\buninstall\b",
            r"setLockTaskPackages",
            r"setPackagesSuspended",
        ]
        for relative in self.REQUIRED_V2_SCRIPTS:
            if not (ROOT / relative).exists():
                continue
            text = read_text(relative)
            for pattern in forbidden:
                self.assertIsNone(re.search(pattern, text, re.IGNORECASE), f"{relative}: {pattern}")

    def test_performance_library_does_not_shadow_powershell_pid_automatic_variable(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        self.assertIsNone(re.search(r"(?i)\$pid\b", text), "PowerShell $PID is a read-only automatic variable")
        self.assertIn("$appPid", text)

    def test_memory_soak_avoids_windows_powershell_generic_list_array_subexpression_bug(self) -> None:
        text = read_text("scripts/run-memory-soak.ps1")
        self.assertNotIn("@($samples)", text)
        self.assertIn("$samples.ToArray()", text)

    def test_analysis_avoids_windows_powershell_generic_list_array_subexpression_bug(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        self.assertNotIn("@($failureReasons)", text)
        self.assertIn("$failureReasons.ToArray()", text)

    def test_v21_documentation_exists(self) -> None:
        self.assertTrue((ROOT / "PERFORMANCE-HARNESS-V2.1.md").is_file())



class CameraFaceHarnessV22ContractTest(unittest.TestCase):
    def test_v22_camera_face_soak_contract(self) -> None:
        runner = ROOT / "scripts" / "run-camera-face-soak.ps1"
        lib = ROOT / "scripts" / "lib" / "CameraFaceMetrics.ps1"
        doc = ROOT / "CAMERA-FACE-SOAK-V2.2.md"
        self.assertTrue(runner.exists(), "missing V2.2 camera/face soak runner")
        self.assertTrue(lib.exists(), "missing V2.2 camera/face metrics library")
        self.assertTrue(doc.exists(), "missing V2.2 camera/face soak documentation")

        runner_text = runner.read_text(encoding="utf-8-sig")
        core = ROOT / "scripts" / "run-camera-face-soak-core.ps1"
        if core.exists():
            runner_text += "\n" + core.read_text(encoding="utf-8-sig")
        lib_text = lib.read_text(encoding="utf-8-sig")
        for token in [
            "Minutes = 480",
            "WarmupMinutes = 15",
            "AutoOpenPunchScreen",
            "Get-CameraFaceHealthSample",
            "Get-CameraFaceSoakAnalysis",
            "camera-face-soak",
        ]:
            self.assertIn(token, runner_text)
        for token in [
            "uiautomator",
            "nav_punch",
            "btn_punch_toggle",
            "打卡：关",
            "Enable-PunchIfNeeded",
            'dumpsys\", \"media.camera',
            "libbdface_sdk",
            "libonnxruntime",
            "CameraActive",
            "FaceSdkLoaded",
            "MainActivityForeground",
            "Write-CameraFaceSummary",
            "Write-CameraFaceHtmlReport",
        ]:
            self.assertIn(token, lib_text)

    def test_v223_activity_parser_supports_mtk_resumedactivity_format(self) -> None:
        lib_text = read_text("scripts/lib/CameraFaceMetrics.ps1")
        match = re.search(r"(?s)function Get-ResumedActivityInfo \{(.*?)(?=\nfunction |\Z)", lib_text)
        self.assertIsNotNone(match, "Get-ResumedActivityInfo must exist")
        body = match.group(1)
        self.assertIn("mResumedActivity", body)
        self.assertIn("ResumedActivity", body)
        self.assertIn("topResumedActivity", body)
        self.assertRegex(body, r"mResumedActivity\|ResumedActivity\|topResumedActivity")

    def test_v222_preflight_retries_punch_enable_until_camera_becomes_active(self) -> None:
        lib_text = read_text("scripts/lib/CameraFaceMetrics.ps1")
        match = re.search(r"(?s)function Wait-CameraFaceReady \{(.*?)(?=\nfunction |\Z)", lib_text)
        self.assertIsNotNone(match, "Wait-CameraFaceReady must exist")
        body = match.group(1)
        self.assertIn("Enable-PunchIfNeeded", body)
        self.assertIn("toggle-attempt-", body)
        self.assertRegex(body, r"(?s)CameraActive.*?Enable-PunchIfNeeded.*?Get-CameraFaceHealthSample")

    def test_v224_camera_face_soak_build_is_transactional_and_auto_enables_punch(self) -> None:
        gradle = read_text("app/build.gradle")
        punch = read_text("app/src/main/java/com/punch/app/fragment/PunchFragment.java")
        policy = read_text("app/src/main/java/com/punch/app/fragment/PunchCameraPolicy.java")
        runner = read_text("scripts/run-camera-face-soak.ps1")

        self.assertIn("cameraFaceSoak", gradle)
        self.assertIn('versionNameSuffix "-camera-face-soak"', gradle)
        self.assertNotIn('applicationIdSuffix ".cameraFaceSoak"', gradle)
        self.assertTrue((ROOT / "app/src/cameraFaceSoak/res/values/test_flags.xml").exists())
        self.assertIn("camera_face_soak_auto_enable", punch)
        self.assertIn("shouldEnablePunchOnVisibleEntry", policy)
        self.assertIn("assembleCameraFaceSoak", runner)
        self.assertIn("Backup-InstalledPackageApks", runner)
        self.assertIn("Restore-InstalledPackageApks", runner)
        self.assertIn("production-backup", runner)
        self.assertIn("app-cameraFaceSoak.apk", runner)


    def test_v226_core_process_streams_output_and_returns_only_exit_code(self) -> None:
        runner = read_text("scripts/run-camera-face-soak.ps1")
        body = runner.split('function Invoke-CameraFaceCoreProcess', 1)[1].split('try {', 1)[0]
        self.assertIn('camera-face-soak-core.log', body)
        self.assertIn('Invoke-LoggedCommand', body)
        self.assertIn('-FilePath $powershellExe', body)
        self.assertIn('-Arguments $args', body)
        self.assertNotIn('& $powershellExe @args', body)

    def test_v225_camera_face_launches_through_exported_launcher_not_internal_main(self) -> None:
        common = read_text("scripts/lib/DeviceTestCommon.ps1")
        runner = read_text("scripts/run-camera-face-soak.ps1")
        core = read_text("scripts/run-camera-face-soak-core.ps1")
        camera_lib = read_text("scripts/lib/CameraFaceMetrics.ps1")

        self.assertIn("function Start-AndroidLauncherPackage", common)
        self.assertIn("android.intent.action.MAIN", common)
        self.assertIn("android.intent.category.LAUNCHER", common)
        self.assertRegex(common, r'(?s)Start-AndroidLauncherPackage.*?"-p".*?\$PackageName')
        self.assertNotIn('$PackageName/.activity.MainActivity', runner)
        self.assertNotIn('$PackageName/.activity.MainActivity', core)
        self.assertNotIn('$PackageName/.activity.MainActivity', camera_lib)
        self.assertIn("Start-AndroidLauncherPackage", runner)
        self.assertIn("Start-AndroidLauncherPackage", core)
        self.assertIn("Start-AndroidLauncherPackage", camera_lib)

    def test_v224_release_default_keeps_auto_enable_disabled(self) -> None:
        main_flag = read_text("app/src/main/res/values/test_flags.xml")
        soak_flag = read_text("app/src/cameraFaceSoak/res/values/test_flags.xml") if (ROOT / "app/src/cameraFaceSoak/res/values/test_flags.xml").exists() else ""
        self.assertRegex(main_flag, r'<bool name="camera_face_soak_auto_enable">false</bool>')
        self.assertRegex(soak_flag, r'<bool name="camera_face_soak_auto_enable">true</bool>')


    def test_v22_does_not_modify_android_app_sources(self) -> None:
        forbidden = [
            ROOT / "app" / "src" / "performanceTest",
            ROOT / "app" / "src" / "cameraFaceTest",
        ]
        for path in forbidden:
            self.assertFalse(path.exists(), f"V2.2 unexpectedly introduced app test source set: {path}")

    def test_v228_face_readiness_uses_test_only_runtime_receiver_not_proc_maps(self) -> None:
        camera_lib = read_text("scripts/lib/CameraFaceMetrics.ps1")
        manifest_path = ROOT / "app/src/cameraFaceSoak/AndroidManifest.xml"
        receiver_path = ROOT / "app/src/cameraFaceSoak/java/com/punch/app/receiver/CameraFaceSoakStatusReceiver.java"

        self.assertTrue(manifest_path.exists(), "cameraFaceSoak status receiver manifest overlay is missing")
        self.assertTrue(receiver_path.exists(), "cameraFaceSoak status receiver class is missing")

        manifest = manifest_path.read_text(encoding="utf-8-sig")
        receiver = receiver_path.read_text(encoding="utf-8-sig")

        self.assertIn("CameraFaceSoakStatusReceiver", manifest)
        self.assertIn('android:exported="true"', manifest)
        self.assertNotIn("CameraFaceSoakStatusReceiver", read_text("app/src/main/AndroidManifest.xml"))
        self.assertNotIn("CameraFaceSoakStatusReceiver", read_text("app/src/smoke/AndroidManifest.xml"))
        self.assertIn("FaceManager.get().isInitialized()", receiver)
        self.assertIn("getLoadedFaceCount()", receiver)
        self.assertIn("setResultData", receiver)

        self.assertIn("function Get-CameraFaceRuntimeStatus", camera_lib)
        self.assertIn("CAMERA_FACE_SOAK_STATUS", camera_lib)
        self.assertIn("face_initialized", camera_lib)
        self.assertIn("FaceRuntimeInitialized", camera_lib)

        wait_body = camera_lib.split("function Wait-CameraFaceReady", 1)[1].split("function Find-CameraFaceHealthEvents", 1)[0]
        self.assertIn("FaceRuntimeInitialized", wait_body)
        self.assertNotRegex(wait_body, r"faceReady\s*=\s*\(-not \$last\.FaceSdkProbeSupported\)")


class FacePunchStressV23ContractTest(unittest.TestCase):
    def test_v23_variant_is_same_package_and_isolated(self) -> None:
        gradle = read_text("app/build.gradle")
        block = re.search(r"(?s)facePunchStress\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))
        manifest = ROOT / "app/src/facePunchStress/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        text = manifest.read_text(encoding="utf-8")
        self.assertIn("FacePunchStressReceiver", text)
        for relative in [
            "app/src/main/AndroidManifest.xml",
            "app/src/debug/AndroidManifest.xml",
            "app/src/smoke/AndroidManifest.xml",
            "app/src/deviceOwnerTest/AndroidManifest.xml",
            "app/src/cameraFaceSoak/AndroidManifest.xml",
        ]:
            self.assertNotIn("FacePunchStressReceiver", read_text(relative))

    def test_v23_reuses_production_persistence_with_non_upload_stress_action(self) -> None:
        persistence = read_text("app/src/main/java/com/punch/app/service/PunchPersistence.java")
        fragment = read_text("app/src/main/java/com/punch/app/fragment/PunchFragment.java")
        constants = read_text("app/src/main/java/com/punch/app/utils/Constants.java")
        self.assertIn("boolean persist", persistence)
        self.assertIn("enqueueSyncItem", persistence)
        self.assertIn("PunchPersistence", fragment)
        self.assertIn("ACTION_PUNCH_STRESS_NO_UPLOAD", constants)
        self.assertIn('"stress_punch_no_upload"', constants)

    def test_v23_database_has_prefix_count_and_cleanup_helpers(self) -> None:
        db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        for token in [
            "countPunchRecordsByClientPrefix",
            "countSyncQueueByAction",
            "deletePunchRecordsByClientPrefix",
            "deleteSyncQueueByAction",
        ]:
            self.assertIn(token, db)

    def test_v23_face_engine_uses_local_or_prepared_fixture_and_real_face_manager(self) -> None:
        engine = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressEngine.java")
        fixture = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressFixture.java")
        combined = engine + fixture
        for token in [
            "getAllActiveEmployees",
            "faceRegistered",
            "FaceFileManager.getFaceImagePath",
            "BitmapFactory.decodeFile",
            "recognizeFromBitmap",
            "bitmap.recycle",
        ]:
            self.assertIn(token, combined)

    def test_v233_face_fixture_auto_prepares_without_marking_employee_registered(self) -> None:
        fixture_path = ROOT / "app/src/facePunchStress/java/com/punch/app/stress/FaceStressFixture.java"
        self.assertTrue(fixture_path.is_file(), fixture_path)
        fixture = fixture_path.read_text(encoding="utf-8")
        engine = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressEngine.java")
        for token in [
            "getAllActiveEmployees",
            "FaceManager.get().registerFace",
            "FaceManager.get().removeFace",
            "face_sdk_ids",
            "STRESS_FACE_V23",
            "bundled_test_face",
        ]:
            self.assertIn(token, fixture)
        self.assertNotIn("updateFaceRegistration", fixture)
        self.assertIn("FaceStressFixture.prepare", engine)
        self.assertIn("fixture.close()", engine)

    def test_v234_face_fixture_uses_bundled_public_domain_face_without_business_data_dependency(self) -> None:
        fixture = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressFixture.java")
        engine = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressEngine.java")
        result = read_text("app/src/facePunchStress/java/com/punch/app/stress/StressResult.java")
        asset = ROOT / "app/src/facePunchStress/res/raw/stress_face_fixture.jpg"
        self.assertTrue(asset.is_file(), asset)
        self.assertGreater(asset.stat().st_size, 20_000)
        for token in [
            "STRESS_FACE_V23",
            "R.raw.stress_face_fixture",
            "fixture_source",
            "bundled_test_face",
            "FaceManager.get().registerFace",
            "FaceManager.get().removeFace",
            "deleteFaceSdkMapping",
        ]:
            self.assertIn(token, fixture + engine + result)
        self.assertNotIn("FaceFileManager.downloadAndVerify", fixture)
        self.assertNotIn("faceImageUrl", fixture)
        self.assertNotIn("updateFaceRegistration", fixture)

    def test_v23_punch_engine_uses_stress_prefix_snapshot_and_non_upload_action(self) -> None:
        text = read_text("app/src/facePunchStress/java/com/punch/app/stress/PunchStressEngine.java")
        for token in [
            "STRESS_V23_",
            "PunchSnapshotHelper.capture",
            "ACTION_PUNCH_STRESS_NO_UPLOAD",
            "PunchPersistence.persist",
        ]:
            self.assertIn(token, text)
        self.assertNotIn("ACTION_PUNCH_PUSH", text)
        self.assertIn("record.isSynced = 1", text)

    def test_v236_punch_engine_uses_synthetic_employee_without_business_employee_dependency(self) -> None:
        text = read_text("app/src/facePunchStress/java/com/punch/app/stress/PunchStressEngine.java")
        self.assertIn('STRESS_EMP_V23', text)
        self.assertIn('V2.3 Stress Employee', text)
        self.assertIn('Stress', text)
        self.assertNotIn('getAllActiveEmployees', text)
        self.assertNotIn('List<Employee>', text)
        self.assertNotIn('import com.punch.app.model.Employee;', text)
        self.assertIn('record.empId = STRESS_EMP_ID;', text)
        self.assertIn('record.empName = STRESS_EMP_NAME;', text)
        self.assertIn('record.dept = STRESS_DEPT;', text)

    def test_v23_receiver_exposes_run_status_cleanup_only_in_test_variant(self) -> None:
        text = read_text("app/src/facePunchStress/java/com/punch/app/stress/FacePunchStressReceiver.java")
        for token in [
            "ACTION_RUN",
            "ACTION_STATUS",
            "ACTION_CLEANUP",
            "MODE_FACE",
            "MODE_PUNCH",
            "MODE_ALL",
        ]:
            self.assertIn(token, text)
        self.assertIn("Executors.newSingleThreadExecutor", text)

    def test_v23_runner_is_transactional_and_reports_face_punch_metrics(self) -> None:
        runner = read_text("scripts/run-face-punch-stress.ps1")
        lib = read_text("scripts/lib/FacePunchStress.ps1")
        for token in [
            "assembleFacePunchStress",
            "Backup-InstalledPackageApks",
            "Restore-InstalledPackageApks",
            "production-backup",
            "FacePunchStressReceiver",
            "Mode",
            "Count",
            "metrics.csv",
            "summary.txt",
            "fatal-events.txt",
        ]:
            self.assertTrue(token in runner or token in lib, token)
        self.assertNotRegex(runner + lib, r"\$PID\b")

    def test_v232_face_preflight_waits_for_punch_data_preparation(self) -> None:
        receiver = read_text("app/src/facePunchStress/java/com/punch/app/stress/FacePunchStressReceiver.java")
        lib = read_text("scripts/lib/FacePunchStress.ps1")
        self.assertIn("PunchApplication.get()", receiver)
        self.assertIn("isPunchDataPreparing()", receiver)
        self.assertIn("isPunchRecognitionReady()", receiver)
        self.assertIn("punch_data_preparing", receiver)
        self.assertIn("punch_data_ready", receiver)
        self.assertIn("PunchDataPreparing", lib)
        runner = read_text("scripts/run-face-punch-stress.ps1")
        self.assertIn("PreflightTimeoutSeconds", runner)
        self.assertIn("Face data preparation did not finish", runner)
        wait_body = lib.split("function Wait-FacePunchStressReady", 1)[1].split("function Convert-StressJsonToCsv", 1)[0]
        self.assertRegex(wait_body, r"FaceInitialized.*-and.*-not \$s\.PunchDataPreparing")

    def test_v235_face_native_growth_uses_hot_baseline_after_warmup(self) -> None:
        runner = read_text("scripts/run-face-punch-stress.ps1")
        self.assertIn("WarmupFaceCount", runner)
        self.assertIn("[INFO] Face native warm-up", runner)
        self.assertIn("native_heap_cold_to_warm_percent", runner)
        warmup_pos = runner.index("[INFO] Face native warm-up")
        baseline_pos = runner.index("$initial=Get-AppPerformanceSample")
        run_pos = runner.index("$runOutput=Invoke-FacePunchStressBroadcast")
        self.assertLess(warmup_pos, baseline_pos)
        self.assertLess(baseline_pos, run_pos)
        self.assertRegex(runner, r"nativeGrowth=100\.0\*\(\$final\.NativeHeapMb-\$initial\.NativeHeapMb\)/\$initial\.NativeHeapMb")

    def test_v23_documentation_exists(self) -> None:
        path = ROOT / "FACE-PUNCH-STRESS-V2.3.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in ["-Count 10", "-Count 100", "-Count 500", "-Count 1000", "Mode Face", "Mode Punch"]:
            self.assertIn(token, text)


class FacePunchStressSafetyContractTest(unittest.TestCase):
    def test_v23_precheck_bypass_is_resource_gated_and_false_in_release(self) -> None:
        main_flags = read_text("app/src/main/res/values/test_flags.xml")
        stress_flags = read_text("app/src/facePunchStress/res/values/test_flags.xml")
        face = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")
        self.assertIn('<bool name="face_punch_stress_bypass_prechecks">false</bool>', main_flags)
        self.assertIn('<bool name="face_punch_stress_bypass_prechecks">true</bool>', stress_flags)
        self.assertIn("R.bool.face_punch_stress_bypass_prechecks", face)

    def test_v23_sync_coordinator_never_uploads_stress_action(self) -> None:
        sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        self.assertIn("Constants.ACTION_PUNCH_PUSH", sync)
        self.assertNotIn("ACTION_PUNCH_STRESS_NO_UPLOAD", sync)


class NetworkFaultSyncV25ContractTest(unittest.TestCase):
    def test_v25_variant_is_same_package_and_receiver_is_test_only(self) -> None:
        gradle = read_text("app/build.gradle")
        block = re.search(r"(?s)networkFaultTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))
        manifest = ROOT / "app/src/networkFaultTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        text = manifest.read_text(encoding="utf-8")
        self.assertIn("NetworkFaultTestReceiver", text)
        self.assertIn('android:exported="true"', text)
        self.assertIn("UpdateInstallStateReceiver", text)
        self.assertIn('tools:node="remove"', text)
        app = read_text("app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultTestApplication.java")
        self.assertIn("setUiTestModeForTest(true)", app)
        for relative in [
            "app/src/main/AndroidManifest.xml",
            "app/src/debug/AndroidManifest.xml",
            "app/src/smoke/AndroidManifest.xml",
            "app/src/deviceOwnerTest/AndroidManifest.xml",
            "app/src/cameraFaceSoak/AndroidManifest.xml",
            "app/src/facePunchStress/AndroidManifest.xml",
        ]:
            self.assertNotIn("NetworkFaultTestReceiver", read_text(relative))

    def test_v25_fault_controller_uses_existing_api_client_test_hooks_and_loopback(self) -> None:
        controller = read_text("app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultController.java")
        server = read_text("app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultServer.java")
        self.assertIn("ApiClient.setBaseUrlForTest", controller)
        self.assertIn("ApiClient.setClientForTest", controller)
        self.assertIn("ApiClient.resetForTest", controller)
        self.assertIn("127.0.0.1", controller + server)
        for token in ["SUCCESS", "HTTP_401", "HTTP_500", "TIMEOUT", "DROP"]:
            self.assertIn(token, server)
        self.assertIn("new ServerSocket", server)

    def test_v25_uses_isolated_sqlite_database_before_production_sync(self) -> None:
        db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        bridge = read_text("app/src/networkFaultTest/java/com/punch/app/db/NetworkFaultDatabaseController.java")
        self.assertIn("databaseNameOverrideForTest", db)
        self.assertIn("setDatabaseNameForTest", db)
        self.assertIn("resetForTest", db)
        self.assertIn("punch_network_fault_v25.db", bridge)
        self.assertIn("DatabaseHelper.setDatabaseNameForTest", bridge)
        self.assertIn("deleteDatabase", bridge)
        self.assertNotIn("Constants.DB_NAME", bridge)

    def test_v25_engine_drives_production_sync_with_real_unsynced_punch_queue(self) -> None:
        engine = read_text("app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultTestEngine.java")
        for token in [
            "NET_V25_",
            "record.isSynced = 0",
            "Constants.ACTION_PUNCH_PUSH",
            "SyncService.triggerSync",
            "SyncTrigger.AFTER_PUNCH",
            "SyncTrigger.MANUAL",
            "SYNC_MAX_RETRY",
        ]:
            self.assertIn(token, engine)
        self.assertNotIn("ACTION_PUNCH_STRESS_NO_UPLOAD", engine)
        self.assertIn("client_record_id LIKE ?", engine)

    def test_v251_runner_foregrounds_test_host_before_sync_broadcasts(self) -> None:
        manifest = read_text("app/src/networkFaultTest/AndroidManifest.xml")
        runner = read_text("scripts/run-network-fault-sync.ps1")
        host = ROOT / "app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultTestHostActivity.java"
        self.assertTrue(host.is_file(), host)
        self.assertIn("NetworkFaultTestHostActivity", manifest)
        self.assertIn('android:exported="true"', manifest)
        text = host.read_text(encoding="utf-8")
        self.assertIn("FLAG_KEEP_SCREEN_ON", text)
        self.assertIn("FLAG_TURN_SCREEN_ON", text)
        self.assertIn("NetworkFaultTestHostActivity", runner)
        self.assertLess(runner.index("NetworkFaultTestHostActivity"), runner.index("Invoke-NetworkFaultBroadcast"))

    def test_v25_runner_is_transactional_and_restores_network_state(self) -> None:
        runner = read_text("scripts/run-network-fault-sync.ps1")
        lib = read_text("scripts/lib/NetworkFaultSync.ps1")
        combined = runner + lib
        for token in [
            "assembleNetworkFaultTest",
            "Backup-InstalledPackageApks",
            "Restore-InstalledPackageApks",
            "NetworkFaultTestReceiver",
            "DeviceOfflineRecovery",
            "Capture-DeviceNetworkState",
            "Restore-DeviceNetworkState",
            "summary.txt",
            "fatal-events.txt",
        ]:
            self.assertIn(token, combined)
        self.assertIn("finally", runner)
        self.assertNotIn("Constants.DEFAULT_BASE_URL", combined)

    def test_v25_powershell_files_are_windows_ps51_safe(self) -> None:
        for relative in ["scripts/lib/NetworkFaultSync.ps1", "scripts/run-network-fault-sync.ps1"]:
            raw = (ROOT / relative).read_bytes()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), relative)
            body = raw[3:]
            self.assertIn(b"\r\n", body, relative)
            self.assertNotIn(b"\n", body.replace(b"\r\n", b""), relative)

    def test_v25_documentation_exists_and_lists_required_scenarios(self) -> None:
        path = ROOT / "NETWORK-FAULT-SYNC-V2.5.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in [
            "DisconnectRecovery", "TimeoutRecovery", "Http500Retry",
            "Http401Recovery", "RetryLimitManualRecovery", "DeviceOfflineRecovery",
        ]:
            self.assertIn(token, text)


class DbStressV26ContractTest(unittest.TestCase):
    def test_v26_variant_is_same_package_and_uses_isolated_database(self) -> None:
        gradle = read_text("app/build.gradle")
        block = re.search(r"(?s)dbStressTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))
        manifest = ROOT / "app/src/dbStressTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        text = manifest.read_text(encoding="utf-8")
        self.assertIn("DbStressTestHostActivity", text)
        self.assertIn("DbStressTestReceiver", text)
        self.assertIn("UpdateInstallStateReceiver", text)
        self.assertIn('tools:node="remove"', text)
        controller = read_text("app/src/dbStressTest/java/com/punch/app/db/DbStressDatabaseController.java")
        app = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestApplication.java")
        self.assertIn('punch_db_stress_v26.db', controller)
        self.assertIn('setDatabaseNameForTest', controller)
        self.assertIn('deleteDatabase', controller)
        self.assertIn('setUiTestModeForTest(true)', app)
        for relative in [
            "app/src/main/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml",
            "app/src/smoke/AndroidManifest.xml", "app/src/deviceOwnerTest/AndroidManifest.xml",
            "app/src/cameraFaceSoak/AndroidManifest.xml", "app/src/facePunchStress/AndroidManifest.xml",
            "app/src/networkFaultTest/AndroidManifest.xml",
        ]:
            self.assertNotIn("DbStressTestReceiver", read_text(relative))

    def test_v26_application_installs_safe_loopback_before_super_startup(self) -> None:
        app = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestApplication.java")
        self.assertIn("DbStressNetworkController.reset()", app)
        self.assertLess(app.index("DbStressNetworkController.reset()"), app.index("super.onCreate()"))

    def test_v26_loopback_server_is_production_sync_compatible(self) -> None:
        controller = read_text("app/src/dbStressTest/java/com/punch/app/network/DbStressNetworkController.java")
        server = read_text("app/src/dbStressTest/java/com/punch/app/network/DbStressServer.java")
        self.assertIn("ApiClient.setBaseUrlForTest", controller)
        self.assertIn("ApiClient.setClientForTest", controller)
        self.assertIn("SAFE_IDLE_BASE_URL", controller)
        self.assertIn("127.0.0.1", controller + server)
        self.assertIn("new ServerSocket", server)
        self.assertIn("/clock/upload", server)
        self.assertIn("v26_heartbeat_suppressed", server)
        self.assertIn("getPunchRequestCount", server)
        self.assertNotIn("DEFAULT_BASE_URL", controller + server)

    def test_v26_network_controller_never_restores_production_base_url_in_test_process(self) -> None:
        controller = read_text("app/src/dbStressTest/java/com/punch/app/network/DbStressNetworkController.java")
        self.assertIn('SAFE_IDLE_BASE_URL = "http://127.0.0.1:1"', controller)
        self.assertIn("ApiClient.setBaseUrlForTest(SAFE_IDLE_BASE_URL)", controller)
        self.assertNotIn("ApiClient.resetForTest()", controller)

    def test_v26_engine_uses_real_queue_restart_integrity_and_vacuum(self) -> None:
        engine = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestEngine.java")
        receiver = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestReceiver.java")
        combined = engine + receiver
        for token in [
            "DB_V26_", "record.isSynced = 0", "Constants.ACTION_PUNCH_PUSH",
            "SyncService.triggerSync", "SyncTrigger.MANUAL", "PUNCH_BATCH_SIZE",
            "PRAGMA integrity_check", "VACUUM", "Process.killProcess",
            "1000", "5000", "10000", "duplicate_client_ids",
            "unexpected_retry", "getPunchRequestCount",
        ]:
            self.assertIn(token, combined)
        for action in ["PREPARE", "KILL", "VERIFY_RESTART", "DRAIN", "STATUS", "CLEANUP"]:
            self.assertIn(action, receiver)
        self.assertNotIn("ACTION_PUNCH_STRESS_NO_UPLOAD", engine)
        self.assertNotIn("PunchSnapshotHelper.capture", engine)

    def test_v26_runner_is_transactional_restart_aware_and_ps51_safe(self) -> None:
        runner = read_text("scripts/run-db-stress.ps1")
        lib = read_text("scripts/lib/DbStress.ps1")
        combined = runner + lib
        for token in [
            "assembleDbStressTest", "Backup-InstalledPackageApks", "Restore-InstalledPackageApks",
            "DbStressTestHostActivity", "PREPARE", "KILL", "VERIFY_RESTART", "DRAIN",
            "1000", "5000", "10000", "metrics.csv", "summary.txt", "fatal-events.txt",
            "Get-AppPerformanceSample", "finally",
        ]:
            self.assertIn(token, combined)
        self.assertLess(runner.index("DbStressTestHostActivity"), runner.index("Invoke-DbStressBroadcast"))
        self.assertNotIn("DEFAULT_BASE_URL", combined)
        for relative in ["scripts/lib/DbStress.ps1", "scripts/run-db-stress.ps1"]:
            raw = (ROOT / relative).read_bytes()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), relative)
            body = raw[3:]
            self.assertIn(b"\r\n", body, relative)
            self.assertNotIn(b"\n", body.replace(b"\r\n", b""), relative)

    def test_v261_runner_requires_actual_kiosk_restore_gate(self) -> None:
        runner = read_text("scripts/run-db-stress.ps1")
        lib = read_text("scripts/lib/DbStress.ps1")
        self.assertIn("Wait-DbStressProductionKioskReady", runner)
        self.assertIn("Get-DbStressProductionKioskState", lib)
        self.assertIn("dumpsys", lib)
        self.assertIn("activity", lib)
        self.assertIn("dpm", lib)
        self.assertIn("list-owners", lib)
        self.assertRegex(lib, r"mResumedActivity|topResumedActivity|ResumedActivity")
        self.assertRegex(lib, r"mLockTaskModeState|lockTaskModeState")
        self.assertIn("MainActivity", lib)
        self.assertIn("kiosk_restore_ready", runner)
        self.assertIn("restore-kiosk-state.txt", runner)
        self.assertIn("core_status=", runner)
        self.assertIn("overall_status=", runner)
        self.assertGreater(runner.rindex("RESULT:"), runner.index("finally"))

    def test_v262_runner_terminates_test_process_before_restoring_production_apk(self) -> None:
        runner = read_text("scripts/run-db-stress.ps1")
        receiver = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestReceiver.java")
        self.assertIn("ACTION_TERMINATE", receiver)
        self.assertIn("Process.killProcess(Process.myPid())", receiver)
        self.assertIn("TERMINATE", runner)
        self.assertLess(runner.index("TERMINATE"), runner.index("Restore-InstalledPackageApks"))

    def test_v26_powershell_does_not_shadow_readonly_pid_automatic_variable(self) -> None:
        for relative in ["scripts/lib/DbStress.ps1", "scripts/run-db-stress.ps1"]:
            text = read_text(relative)
            self.assertIsNone(
                re.search(r"(?im)^\s*\$pid\s*=", text),
                f"{relative} must not assign to PowerShell's readonly $PID automatic variable",
            )

    def test_v26_documentation_exists_with_three_capacity_commands(self) -> None:
        path = ROOT / "DB-STRESS-V2.6.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in [
            "-Count 1000", "-Count 5000", "-Count 10000",
            "punch_db_stress_v26.db", "PRAGMA integrity_check", "VACUUM",
            "PUNCH_BATCH_SIZE", "production database", "no snapshots",
        ]:
            self.assertIn(token, text)


class StressResultAndroidCompileRegressionTest(unittest.TestCase):
    def test_stress_result_compiles_against_android_json_overloads(self) -> None:
        import subprocess
        import tempfile
        import textwrap
        import shutil

        javac = shutil.which("javac")
        self.assertIsNotNone(javac, "javac is required for this regression test")
        source = ROOT / "app/src/facePunchStress/java/com/punch/app/stress/StressResult.java"
        self.assertTrue(source.is_file(), source)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            json_dir = root / "org/json"
            json_dir.mkdir(parents=True)
            (json_dir / "JSONException.java").write_text(
                "package org.json; public class JSONException extends Exception {}",
                encoding="utf-8",
            )
            (json_dir / "JSONArray.java").write_text(textwrap.dedent("""
                package org.json;
                public class JSONArray {
                    public JSONArray put(Object value) { return this; }
                }
            """), encoding="utf-8")
            (json_dir / "JSONObject.java").write_text(textwrap.dedent("""
                package org.json;
                public class JSONObject {
                    public JSONObject put(String key, int value) throws JSONException { return this; }
                    public JSONObject put(String key, long value) throws JSONException { return this; }
                    public JSONObject put(String key, Object value) throws JSONException { return this; }
                }
            """), encoding="utf-8")
            result = subprocess.run(
                [javac, "-source", "8", "-target", "8", "-d", str(root / "out"),
                 str(json_dir / "JSONException.java"), str(json_dir / "JSONArray.java"),
                 str(json_dir / "JSONObject.java"), str(source)],
                text=True, capture_output=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

class ProcessRecoveryV271ContractTest(unittest.TestCase):
    def test_v271_variant_is_same_package_and_runs_production_lifecycle(self) -> None:
        gradle = read_text("app/build.gradle")
        self.assertIn("processRecoveryTest", gradle)
        block = re.search(r"(?s)processRecoveryTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))

        manifest = ROOT / "app/src/processRecoveryTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        root = ET.parse(manifest).getroot()
        app = root.find("application")
        self.assertIsNotNone(app)
        self.assertEqual(
            ".processrecovery.ProcessRecoveryTestApplication",
            app.attrib.get(ANDROID_NS + "name"),
        )

        application = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/processrecovery/ProcessRecoveryTestApplication.java"
        )
        self.assertNotIn("setUiTestModeForTest(true)", application)
        self.assertIn("setUiTestModeForTest(false)", application)
        self.assertIn("ProcessRecoveryDatabaseController.activateExisting(this)", application)
        self.assertIn("ProcessRecoveryNetworkController.reset()", application)
        self.assertLess(
            application.index("ProcessRecoveryDatabaseController.activateExisting(this)"),
            application.index("super.onCreate()"),
        )
        self.assertLess(
            application.index("ProcessRecoveryNetworkController.reset()"),
            application.index("super.onCreate()"),
        )

    def test_v271_runner_cold_starts_test_variant_before_preflight(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        install_pos = runner.index("Installing temporary processRecoveryTest build")
        preflight_start = runner.index("Starting V2.7.1 Kiosk entry for preflight")
        transition = runner[install_pos:preflight_start]
        self.assertIn("-Action TERMINATE", transition)
        self.assertIn("Wait-ProcessRecoveryOldPidExit", transition)
        self.assertIn("cold", transition.lower())
        self.assertIn("$preflight.DatabaseActive", runner)

    def test_v271_uses_isolated_database_and_loopback_only_network(self) -> None:
        db = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/db/ProcessRecoveryDatabaseController.java"
        )
        network = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/network/ProcessRecoveryNetworkController.java"
        )
        self.assertIn('TEST_DB_NAME = "punch_process_recovery_v271.db"', db)
        self.assertIn("DatabaseHelper.setDatabaseNameForTest(TEST_DB_NAME)", db)
        self.assertIn('SAFE_IDLE_BASE_URL = "http://127.0.0.1:1"', network)
        self.assertIn("ApiClient.setBaseUrlForTest", network)
        self.assertNotRegex(network, r"https?://(?!127\.0\.0\.1)")

    def test_v271_manifest_removes_update_install_receiver(self) -> None:
        text = read_text("app/src/processRecoveryTest/AndroidManifest.xml")
        self.assertIn("UpdateInstallStateReceiver", text)
        self.assertIn('tools:node="remove"', text)

    def test_v271_engine_seeds_real_punch_queue_and_uses_real_sync_service(self) -> None:
        engine = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/processrecovery/ProcessRecoveryTestEngine.java"
        )
        self.assertIn('PREFIX = "PROC_V271_"', engine)
        self.assertIn("SYNC_RECORD_COUNT = 100", engine)
        self.assertIn("PunchRecord record = new PunchRecord()", engine)
        self.assertIn("db.insertPunchRecord(record)", engine)
        self.assertIn("db.enqueueSyncItem(record.clientRecordId, Constants.ACTION_PUNCH_PUSH)", engine)
        self.assertIn("SyncService.triggerSync(context, SyncTrigger.MANUAL)", engine)
        self.assertIn('rawQuery("PRAGMA integrity_check"', engine)
        self.assertIn("duplicateClientIds", engine)
        self.assertIn("queueCount == state.unsynced", engine)
        self.assertIn("synced > 0 && synced < SYNC_RECORD_COUNT", engine)

    def test_v271_slow_loopback_server_creates_partial_sync_window(self) -> None:
        server = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/network/ProcessRecoveryServer.java"
        )
        controller = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/network/ProcessRecoveryNetworkController.java"
        )
        self.assertIn("punchDelayMs", server)
        self.assertIn("Thread.sleep(punchDelayMs)", server)
        self.assertIn('/clock/upload', server)
        self.assertIn('/device/heartbeat', server)
        self.assertIn("startLoopback(long punchDelayMs)", controller)
        self.assertIn("127.0.0.1", controller)

    def test_v271_receiver_is_test_only_and_exposes_required_kill_recovery_actions(self) -> None:
        manifest = read_text("app/src/processRecoveryTest/AndroidManifest.xml")
        receiver = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/processrecovery/ProcessRecoveryTestReceiver.java"
        )
        self.assertIn("ProcessRecoveryTestReceiver", manifest)
        self.assertIn('android:exported="true"', manifest)
        for action in [
            "STATUS", "PREPARE_SYNC_KILL", "KILL_FOREGROUND",
            "VERIFY_SYNC_RESTART", "RESUME_SYNC", "CLEANUP", "TERMINATE",
        ]:
            self.assertIn(action, receiver)
        self.assertIn("Process.killProcess(Process.myPid())", receiver)
        self.assertIn("sync-kill-checkpoint.json", receiver)
        self.assertIn("sync-after-restart.json", receiver)
        self.assertIn("sync-result.json", receiver)
        self.assertIn("foreground-kill.json", receiver)
        self.assertIn("kill-events.jsonl", receiver)

        for relative in [
            "app/src/main/AndroidManifest.xml",
            "app/src/debug/AndroidManifest.xml",
            "app/src/release/AndroidManifest.xml" if (ROOT / "app/src/release/AndroidManifest.xml").exists() else "app/src/main/AndroidManifest.xml",
        ]:
            self.assertNotIn("ProcessRecoveryTestReceiver", read_text(relative))

    def test_v271_runner_observes_autonomous_recovery_without_starting_activity_after_kill(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        lib = read_text("scripts/lib/ProcessRecovery.ps1")
        combined = runner + lib
        for token in [
            "assembleProcessRecoveryTest", "Backup-InstalledPackageApks", "Restore-InstalledPackageApks",
            "ForegroundKill", "SyncKillRecovery", "KILL_FOREGROUND", "PREPARE_SYNC_KILL",
            "VERIFY_SYNC_RESTART", "RESUME_SYNC", "TERMINATE", "Wait-ProcessRecoveryAutomaticKioskReady",
            "old_pid", "new_pid", "restore-kiosk-state.txt", "fatal-events.txt", "finally",
        ]:
            self.assertIn(token, combined)
        wait_body = lib.split("function Wait-ProcessRecoveryAutomaticKioskReady", 1)[1]
        if "function " in wait_body:
            wait_body = wait_body.split("function ", 1)[0]
        self.assertNotIn("am','start", wait_body)
        self.assertNotIn('am","start', wait_body)
        self.assertNotIn("force-stop", combined)
        self.assertNotRegex(combined, r"(?im)^\s*\$pid\s*=")
        self.assertIn("dpm", lib)
        self.assertIn("list-owners", lib)
        self.assertRegex(lib, r"mResumedActivity|topResumedActivity|ResumedActivity")
        self.assertRegex(lib, r"mLockTaskModeState|lockTaskModeState")

    def test_v271_runner_requires_new_pid_and_autonomous_kiosk_before_sync_restart_verification(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        self.assertIn("OldPid", runner)
        self.assertIn("NewPid", runner)
        self.assertRegex(runner, r"(?s)KILL_FOREGROUND.*?Wait-ProcessRecoveryAutomaticKioskReady")
        self.assertRegex(runner, r"(?s)PREPARE_SYNC_KILL.*?Wait-ProcessRecoveryAutomaticKioskReady.*?VERIFY_SYNC_RESTART")
        self.assertLess(runner.index("Wait-ProcessRecoveryAutomaticKioskReady"), runner.index("VERIFY_SYNC_RESTART"))
        self.assertIn("autonomous_recovery", runner)

    def test_v271_runner_preserves_device_kill_evidence_and_delays_final_result_until_restore(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        self.assertIn("kill-events.jsonl", runner)
        self.assertIn("foreground-kill.json", runner)
        self.assertIn("foreground-autonomous-recovery.txt", runner)
        self.assertIn("Get-ProcessRecoveryTestFile", runner)
        self.assertGreater(runner.rindex("RESULT:"), runner.index("finally"))

    def test_v271_powershell_is_ps51_compatible_and_loopback_only(self) -> None:
        for relative in ["scripts/lib/ProcessRecovery.ps1", "scripts/run-process-recovery.ps1"]:
            raw = (ROOT / relative).read_bytes()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), relative)
            body = raw[3:]
            self.assertIn(b"\r\n", body, relative)
            self.assertNotIn(b"\n", body.replace(b"\r\n", b""), relative)
            text = raw.decode("utf-8-sig")
            self.assertNotRegex(text, r"https?://(?!127\.0\.0\.1)")
            self.assertIsNone(re.search(r"(?im)^\s*\$pid\s*=", text))

    def test_v271_documentation_exists_with_commands_and_autonomous_recovery_definition(self) -> None:
        path = ROOT / "PROCESS-RECOVERY-V2.7.1.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in [
            "-Scenario ForegroundKill", "-Scenario SyncKillRecovery", "-Scenario All",
            "punch_process_recovery_v271.db", "PROC_V271_", "127.0.0.1",
            "autonomous", "LockTask", "Device Owner", "production backend",
        ]:
            self.assertIn(token, text)

    def test_v271_runner_observes_autonomous_recovery_and_restores_production_transactionally(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        lib = read_text("scripts/lib/ProcessRecovery.ps1")
        for token in [
            "assembleProcessRecoveryTest", "Backup-InstalledPackageApks", "Restore-InstalledPackageApks",
            "ForegroundKill", "SyncKillRecovery", "KILL_FOREGROUND", "PREPARE_SYNC_KILL",
            "VERIFY_SYNC_RESTART", "RESUME_SYNC", "Wait-ProcessRecoveryAutomaticKioskReady",
            "Find-AppFatalEvents", "TERMINATE", "RESULT: PASS",
        ]:
            self.assertIn(token, runner + "\n" + lib)
        self.assertIn("old_pid", runner)
        self.assertIn("new_pid", runner)
        self.assertIn("Wait-ProcessRecoveryOldPidExit", runner)
        self.assertIn("Wait-ProcessRecoveryProductionKioskReady", runner)

        kill_pos = runner.index("-Action KILL_FOREGROUND")
        auto_pos = runner.index("Wait-ProcessRecoveryAutomaticKioskReady", kill_pos)
        self.assertNotIn("'am','start'", runner[kill_pos:auto_pos])
        self.assertNotIn('"am","start"', runner[kill_pos:auto_pos])

    def test_v271_powershell_avoids_pid_shadowing_and_non_loopback_targets(self) -> None:
        for relative in ["scripts/lib/ProcessRecovery.ps1", "scripts/run-process-recovery.ps1"]:
            text = read_text(relative)
            self.assertIsNone(re.search(r"(?i)\$pid\b", text), f"{relative} shadows PowerShell $PID")
            urls = re.findall(r"https?://[^'\"\s]+", text)
            self.assertTrue(all("127.0.0.1" in url for url in urls), (relative, urls))

if __name__ == "__main__":
    unittest.main(verbosity=2)


class CameraFaceRecoveryV273ContractTest(unittest.TestCase):
    def test_v273_variant_is_same_package_and_keeps_production_lifecycle(self) -> None:
        gradle = read_text("app/build.gradle")
        self.assertIn("cameraFaceRecoveryTest", gradle)
        block = re.search(r"(?s)cameraFaceRecoveryTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))

        manifest = ROOT / "app/src/cameraFaceRecoveryTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        app = ET.parse(manifest).getroot().find("application")
        self.assertIsNotNone(app)
        self.assertEqual(
            ".camerafacerecovery.CameraFaceRecoveryTestApplication",
            app.attrib.get(ANDROID_NS + "name"),
        )

        application = read_text(
            "app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryTestApplication.java"
        )
        self.assertIn("CameraFaceRecoveryDatabaseController.activateExisting(this)", application)
        self.assertIn("CameraFaceRecoveryNetworkController.reset()", application)
        self.assertIn("setUiTestModeForTest(false)", application)
        self.assertLess(application.index("CameraFaceRecoveryDatabaseController.activateExisting(this)"), application.index("super.onCreate()"))
        self.assertLess(application.index("CameraFaceRecoveryNetworkController.reset()"), application.index("super.onCreate()"))

    def test_v273_blocks_background_update_before_production_application_start(self) -> None:
        application = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryTestApplication.java")
        guard = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryUpdateGuard.java")
        self.assertIn("CameraFaceRecoveryUpdateGuard.block()", application)
        self.assertLess(application.index("CameraFaceRecoveryUpdateGuard.block()"), application.index("super.onCreate()"))
        self.assertIn('getDeclaredField("AUTO_UPDATE_RUNNING")', guard)
        self.assertIn("AtomicBoolean", guard)
        self.assertIn("set(true)", guard)

    def test_v273_isolated_database_loopback_and_test_flags(self) -> None:
        db = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/db/CameraFaceRecoveryDatabaseController.java")
        net = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/network/CameraFaceRecoveryNetworkController.java")
        flags = read_text("app/src/cameraFaceRecoveryTest/res/values/test_flags.xml")
        self.assertIn('TEST_DB_NAME = "punch_camera_face_recovery_v273.db"', db)
        self.assertIn("DatabaseHelper.setDatabaseNameForTest(TEST_DB_NAME)", db)
        self.assertIn('SAFE_IDLE_BASE_URL = "http://127.0.0.1:1"', net)
        self.assertIn("ApiClient.setBaseUrlForTest", net)
        self.assertNotRegex(net, r"https?://(?!127\.0\.0\.1)")
        self.assertIn('<bool name="camera_face_soak_auto_enable">true</bool>', flags)
        self.assertIn('<bool name="face_punch_stress_bypass_prechecks">true</bool>', flags)

    def test_v273_fixture_uses_normal_face_file_and_production_rebuild_path(self) -> None:
        fixture = ROOT / "app/src/cameraFaceRecoveryTest/res/raw/recovery_face_fixture.jpg"
        self.assertTrue(fixture.is_file(), fixture)
        self.assertGreater(fixture.stat().st_size, 1000)
        engine = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryEngine.java")
        for token in [
            'EMP_ID = "RECOVERY_FACE_V273"',
            "FaceFileManager.getFaceImagePath",
            "db.upsertEmployee(employee)",
            "app.resetPunchRecognitionState()",
            "app.preparePunchRecognitionData()",
            "FaceManager.get().getLoadedFaceCount()",
            "FaceManager.get().recognizeFromBitmap(bitmap)",
        ]:
            self.assertIn(token, engine)
        self.assertNotIn("pushPersonById", engine)
        self.assertNotIn("registerFace(", engine)

    def test_v273_prepare_refuses_to_race_existing_punch_preparation(self) -> None:
        engine = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryEngine.java")
        self.assertIn('throw new IllegalStateException("previous_preparation_still_running")', engine)
        self.assertRegex(engine, r"(?s)waitForPreviousPreparation.*?if \(!app\.isPunchDataPreparing\(\)\) return;.*?previous_preparation_still_running")

    def test_v273_receiver_exposes_prepare_health_recognize_kill_cleanup_terminate(self) -> None:
        manifest = read_text("app/src/cameraFaceRecoveryTest/AndroidManifest.xml")
        receiver = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryTestReceiver.java")
        self.assertIn("CameraFaceRecoveryTestReceiver", manifest)
        self.assertIn('android:exported="true"', manifest)
        for token in ["STATUS", "PREPARE", "RECOGNIZE", "KILL", "CLEANUP", "TERMINATE"]:
            self.assertIn(token, receiver)
        self.assertIn("Process.killProcess(Process.myPid())", receiver)
        self.assertIn("kill-events.jsonl", receiver)
        self.assertIn("cycle-", receiver)
        for relative in ["app/src/main/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml"]:
            self.assertNotIn("CameraFaceRecoveryTestReceiver", read_text(relative))

    def test_v273_runner_observes_autonomous_recovery_and_gates_camera_face(self) -> None:
        runner = read_text("scripts/run-camera-face-recovery.ps1")
        lib = read_text("scripts/lib/CameraFaceRecovery.ps1")
        combined = runner + "\n" + lib
        for token in [
            "assembleCameraFaceRecoveryTest", "Backup-InstalledPackageApks", "Restore-InstalledPackageApks",
            "-Cycles", "PREPARE", "RECOGNIZE", "KILL", "TERMINATE",
            "Wait-CameraFaceRecoveryAutomaticReady", "dumpsys", "media.camera",
            "FaceInitialized", "LoadedFaceCount", "PunchReady", "old_pid", "new_pid",
            "restore-kiosk-state.txt", "fatal-events.txt", "finally", "RESULT: PASS",
        ]:
            self.assertIn(token, combined)
        wait_body = lib.split("function Wait-CameraFaceRecoveryAutomaticReady", 1)[1]
        if "function " in wait_body:
            wait_body = wait_body.split("function ", 1)[0]
        for forbidden in ["am','start", 'am","start', "monkey", "force-stop"]:
            self.assertNotIn(forbidden, wait_body)
        self.assertGreater(runner.rindex("RESULT:"), runner.index("finally"))
        self.assertNotRegex(combined, r"(?im)^\s*\$pid\s*=")
        self.assertNotRegex(combined, r"https?://(?!127\.0\.0\.1)")

    def test_v273_runner_has_cold_test_transition_and_final_cold_production_restore(self) -> None:
        runner = read_text("scripts/run-camera-face-recovery.ps1")
        install_pos = runner.index("Installing temporary cameraFaceRecoveryTest build")
        preflight_pos = runner.index("Starting V2.7.3 Kiosk entry for preflight")
        transition = runner[install_pos:preflight_pos]
        self.assertIn("-Action TERMINATE", transition)
        self.assertIn("Wait-CameraFaceRecoveryOldPidExit", transition)
        self.assertIn("DatabaseActive", runner)
        self.assertRegex(runner, r"(?s)finally.*?CLEANUP.*?TERMINATE.*?Restore-InstalledPackageApks")

    def test_v273_powershell_is_ps51_compatible_utf8_bom_crlf(self) -> None:
        for relative in ["scripts/lib/CameraFaceRecovery.ps1", "scripts/run-camera-face-recovery.ps1"]:
            raw = (ROOT / relative).read_bytes()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), relative)
            body = raw[3:]
            self.assertIn(b"\r\n", body, relative)
            self.assertNotIn(b"\n", body.replace(b"\r\n", b""), relative)
            self.assertIsNone(re.search(r"(?im)^\s*\$pid\s*=", raw.decode("utf-8-sig")))


    def test_v2731_runner_avoids_ps51_colon_interpolation_and_balances_terminate_call(self) -> None:
        runner = read_text("scripts/run-camera-face-recovery.ps1")
        self.assertNotRegex(runner, r'\$[A-Za-z_][A-Za-z0-9_]*:')
        self.assertIn('-Cycle ([Math]::Max(1,$completedCycles)))', runner)

    def test_v2732_preflight_preserves_startup_failure_evidence(self) -> None:
        runner = read_text("scripts/run-camera-face-recovery.ps1")
        lib = read_text("scripts/lib/CameraFaceRecovery.ps1")
        clear_pos = runner.index("logcat','-c")
        start_pos = runner.index("Starting V2.7.3 Kiosk entry for preflight")
        self.assertLess(clear_pos, start_pos)
        preflight_gate = runner.index("V2.7.3 preflight kiosk gate failed")
        self.assertIn("Save-CameraFaceRecoveryPreflightDiagnostics", runner[:preflight_gate + 500])
        self.assertIn("preflight-startup-logcat.txt", runner)
        self.assertIn("preflight-fatal-events.txt", runner)
        self.assertIn("process exited during preflight", runner)
        for token in [
            "dumpsys','activity','top",
            "dumpsys','window','windows",
            "dumpsys','power",
            "preflight-activity-top.txt",
            "preflight-window.txt",
            "preflight-power.txt",
        ]:
            self.assertIn(token, lib)
        self.assertIn("ProcessId", lib)
        self.assertIn("IsProcessRunning", lib)

    def test_v2731_runner_parses_with_powershell_when_available(self) -> None:
        import shutil
        import subprocess
        gate = ROOT / "scripts/tests/test-powershell-parse.ps1"
        self.assertTrue(gate.is_file(), gate)
        gate_text = gate.read_text(encoding="utf-8-sig")
        self.assertIn("System.Management.Automation.Language.Parser]::ParseFile", gate_text)
        self.assertIn("PowerShell parser errors=0", gate_text)
        exe = shutil.which("pwsh") or shutil.which("powershell")
        if not exe:
            self.skipTest("PowerShell parser executable is not available in this environment")
        script = ROOT / "scripts/run-camera-face-recovery.ps1"
        command = [exe, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(gate), "-Paths", str(script)]
        completed = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_v273_docs_define_three_cycles_and_real_post_restart_recognition(self) -> None:
        path = ROOT / "CAMERA-FACE-RECOVERY-V2.7.3.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in [
            "-Cycles 1", "-Cycles 3", "RECOVERY_FACE_V273",
            "punch_camera_face_recovery_v273.db", "127.0.0.1",
            "autonomous", "Camera", "Face", "recognizeFromBitmap", "LockTask", "Device Owner",
        ]:
            self.assertIn(token, text)
