# Face Download Completion-Order Optimization Plan

Goal: eliminate head-of-line blocking in the V2 four-way face-image download pipeline without increasing Baidu Face SDK concurrency.

Implementation:
- keep the fixed four-thread download pool;
- replace input-order `Future.get()` consumption with `ExecutorCompletionService`;
- process whichever employee download completes first;
- keep `FaceRegistrationManager` batch processing and Face SDK work on the existing single-thread executor;
- preserve per-employee success/failure correlation by `empId`;
- expose aggregate `download_wait_ms` and `face_process_ms` in the batch completion log.

Verification:
- prove the old behavior blocks with a latch-based regression test;
- verify completion-order processing unlocks a slow earlier item;
- verify download overlap remains >1 while processor concurrency remains exactly 1;
- verify a single download failure does not abort the remaining employees;
- verify existing Feature cache / SDK guard / employee delta / SDK-ID / sync trigger / punch sync policy tests remain green.
