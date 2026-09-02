# Performance Harness V2.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a one-command, non-invasive production-device performance baseline and memory soak harness for `com.punch.app`.

**Architecture:** Keep DeviceTestCommon focused on ADB/device control and add `PerformanceMetrics.ps1` for parsing, sampling, trend analysis, and report generation. `run-performance-baseline.ps1` captures a single evidence snapshot; `run-memory-soak.ps1` schedules repeated samples, captures logcat, analyzes stable-window growth, and emits CSV/TXT/HTML reports.

**Tech Stack:** Windows PowerShell 5.1, ADB, existing Gradle/Android project, Python unittest for static contract tests.

**Spec:** Conversation-approved V2.1 scope: baseline + 30-minute memory soak with PSS/Java/Native/RSS/CPU/threads/FD/battery/thermal/disk/crash signals.

## Global Constraints

- Must run on Windows PowerShell 5.1.
- Must require an explicit/uniquely resolved ADB serial and route ADB calls through `-s <serial>`.
- Must not change Device Owner, LockTask, package data, installed APK, network state, or reboot the device.
- Default monitored package is `com.punch.app`.
- Default soak is 30 minutes at 60-second cadence; short validation runs must be supported.
- Warm-up samples are excluded from leak trend gates.
- Crash/ANR/OOM/SIGSEGV and process restart are hard failures.

---

### Task 1: V2.1 contract tests

**Files:**
- Modify: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Consumes: project tree.
- Produces: failing tests for V2 scripts, metrics library, outputs, and safety constraints.

- [ ] Add tests requiring `PerformanceMetrics.ps1`, baseline runner, soak runner, CSV/HTML/TXT output markers, stable-window analysis, PID/thread/FD collection, and non-destructive behavior.
- [ ] Run `python scripts/tests/test_harness_static.py` and verify V2.1 tests fail because files/functions do not exist.

### Task 2: Performance sampling library

**Files:**
- Create: `scripts/lib/PerformanceMetrics.ps1`

**Interfaces:**
- Consumes: `Get-AdbOutput` from `DeviceTestCommon.ps1`.
- Produces: `Get-AppPerformanceSample`, `Get-MemorySoakAnalysis`, `Write-PerformanceHtmlReport`, CSV-compatible sample objects.

- [ ] Parse `dumpsys meminfo` App Summary for TOTAL PSS, TOTAL RSS, Java Heap, Native Heap with `/proc/<pid>/status` fallback for RSS.
- [ ] Collect CPU from `dumpsys cpuinfo`, thread and FD counts from `/proc`, battery level/temperature, `/data` utilization, and process PID.
- [ ] Implement median first/last stable windows, percent growth, slope per hour, p95 CPU, restart detection, and configurable gates.
- [ ] Generate human-readable TXT and standalone HTML summary.

### Task 3: Baseline runner

**Files:**
- Create: `scripts/run-performance-baseline.ps1`

**Interfaces:**
- Consumes: common + performance libraries.
- Produces: timestamped baseline report with one row CSV and raw evidence.

- [ ] Resolve device, verify package process exists, capture device/app info and a performance sample.
- [ ] Write `metrics.csv`, `summary.txt`, and raw evidence directory.

### Task 4: Memory soak runner

**Files:**
- Create: `scripts/run-memory-soak.ps1`

**Interfaces:**
- Consumes: common + performance libraries.
- Produces: `metrics.csv`, `summary.txt`, `report.html`, raw samples, logcat, fatal scan.

- [ ] Schedule samples against wall-clock targets rather than sleeping after each sample.
- [ ] Clear logcat at start, collect final logcat, detect app fatal events, and count best-effort GC lines for the initial PID.
- [ ] Analyze only samples at/after warm-up, fail on process restart/missing process/fatal events, and apply memory/thread/FD gates.
- [ ] Exit 0 on PASS and 1 on FAIL.

### Task 5: Documentation and package verification

**Files:**
- Modify: `README.md`
- Create: `PERFORMANCE-HARNESS-V2.1.md`

**Interfaces:**
- Produces: copy/paste commands for 2-minute validation and 30-minute production soak.

- [ ] Document metrics, thresholds, report layout, and interpretation.
- [ ] Run full static contract suite and XML checks.
- [ ] Build an overlay ZIP containing V1.4.1 plus V2.1 and verify SHA-256.
