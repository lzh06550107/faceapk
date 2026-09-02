# Face Download Parallelism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Speed up first-time and incremental employee face preparation by downloading up to four face images concurrently while keeping all Baidu Face SDK work strictly serial.

**Architecture:** Keep `FaceRegistrationManager`'s existing single batch executor as the only caller of Face SDK registration/validation. Add a pure-Java batch runner that submits image download tasks to a fixed four-thread pool, then consumes the corresponding futures in input order on the single batch thread. This overlaps HTTP/I/O with serial Detect/Feature/FaceSearch work without increasing native Face SDK concurrency.

**Tech Stack:** Java 8, Android, JUnit 4, existing OkHttp-based `ApiService`, Baidu offline Face SDK.

**Spec:** Approved in-chat continuation of the employee-sync incremental optimization: `4` concurrent image downloads; Face SDK remains one-at-a-time through `FaceSdkOperationGuard`.

## Global Constraints

- Do not parallelize `FaceDetect`, `FaceFeature`, `FaceSearch.pushPersonById`, `FaceSearch.delPersonById`, or recognition.
- Preserve input/result order for employee sync reporting.
- One failed download must fail only that employee, not abort the whole batch.
- Reuse the existing SHA256 local-image cache logic in `FaceFileManager.downloadAndVerify()`.
- Do not change the `/v3/handheld/employee/sync` protocol.
- Do not change database schema in this phase.

---

### Task 1: Pure Java parallel-download/serial-process runner

**Files:**
- Create: `app/src/main/java/com/punch/app/face/ParallelDownloadBatchRunner.java`
- Create: `app/src/test/java/com/punch/app/face/ParallelDownloadBatchRunnerTest.java`

**Interfaces:**
- Produces: `ParallelDownloadBatchRunner.run(List<I>, ExecutorService, Downloader<I,D>, Processor<I,D,R>, FailureFactory<I,R>) -> List<R>`.

- [ ] Write a failing test proving multiple downloads overlap while processors never overlap.
- [ ] Run the new test and confirm RED because the runner does not exist.
- [ ] Implement the minimal runner: submit all download callables, await futures in input order, process on caller thread, isolate per-item failures.
- [ ] Run the new test and confirm GREEN.
- [ ] Add tests for input-order preservation and per-item exception isolation; verify GREEN.

### Task 2: Integrate four-way download prefetch into FaceRegistrationManager

**Files:**
- Modify: `app/src/main/java/com/punch/app/face/FaceRegistrationManager.java`
- Modify/Test: `app/src/test/java/com/punch/app/face/ParallelDownloadBatchRunnerTest.java`

**Interfaces:**
- Consumes: `ParallelDownloadBatchRunner.run(...)` from Task 1.
- Produces: existing `registerEmployees(...)`, `validateEmployeesForRebuild(...)`, and callback contracts unchanged.

- [ ] Add a failing static/behavioral test that requires download parallelism constant `4` and serial processing semantics.
- [ ] Run and verify RED.
- [ ] Add a fixed four-thread `downloadExecutor` while retaining the existing one-thread batch executor.
- [ ] Split `registerSingle` into download preparation and serial Face SDK processing, and route batch registration through the runner.
- [ ] Run focused tests and verify GREEN.

### Task 3: Regression and packaging

**Files:**
- Create: `README_PATCH.md` in patch output only.

**Interfaces:** None.

- [ ] Run all pure-Java focused tests for the new runner plus the existing Face Feature/Delta/SDK guard tests.
- [ ] Run Java syntax parsing/static checks on modified files.
- [ ] Confirm the production code contains a four-thread download pool and still has one-thread registration executor plus `FaceSdkOperationGuard`.
- [ ] Package only changed/new files over the prior optimization baseline into a new ZIP and generate SHA256.
