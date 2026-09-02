package com.punch.app.face;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ParallelDownloadBatchRunner {
    static final int DEFAULT_DOWNLOAD_PARALLELISM = 4;

    private ParallelDownloadBatchRunner() {
    }

    interface Downloader<I, D> {
        D download(I item) throws Exception;
    }

    interface Processor<I, D, R> {
        R process(I item, D downloaded) throws Exception;
    }

    interface FailureFactory<I, R> {
        R create(I item, Throwable error);
    }

    interface MetricsSink {
        void onBatchComplete(long downloadWaitMs, long processMs);
    }

    static <I, D, R> List<R> run(List<I> items,
                                  ExecutorService downloadExecutor,
                                  Downloader<I, D> downloader,
                                  Processor<I, D, R> processor,
                                  FailureFactory<I, R> failureFactory) {
        return run(items, downloadExecutor, downloader, processor, failureFactory, null);
    }

    static <I, D, R> List<R> run(List<I> items,
                                  ExecutorService downloadExecutor,
                                  Downloader<I, D> downloader,
                                  Processor<I, D, R> processor,
                                  FailureFactory<I, R> failureFactory,
                                  MetricsSink metricsSink) {
        List<R> results = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            notifyMetrics(metricsSink, 0L, 0L);
            return results;
        }

        CompletionService<D> completionService = new ExecutorCompletionService<>(downloadExecutor);
        Map<Future<D>, I> pending = new LinkedHashMap<>();
        for (I item : items) {
            Future<D> future = completionService.submit(() -> downloader.download(item));
            pending.put(future, item);
        }

        long downloadWaitNanos = 0L;
        long processNanos = 0L;
        try {
            while (!pending.isEmpty()) {
                long waitStartedAt = System.nanoTime();
                Future<D> completed;
                try {
                    completed = completionService.take();
                } catch (InterruptedException e) {
                    downloadWaitNanos += System.nanoTime() - waitStartedAt;
                    Thread.currentThread().interrupt();
                    cancelRemaining(pending);
                    addFailures(results, pending.values(), failureFactory, e);
                    pending.clear();
                    break;
                }
                downloadWaitNanos += System.nanoTime() - waitStartedAt;

                I item = pending.remove(completed);
                if (item == null) {
                    continue;
                }

                try {
                    D downloaded = completed.get();
                    long processStartedAt = System.nanoTime();
                    try {
                        results.add(processor.process(item, downloaded));
                    } finally {
                        processNanos += System.nanoTime() - processStartedAt;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(failureFactory.create(item, e));
                    cancelRemaining(pending);
                    addFailures(results, pending.values(), failureFactory, e);
                    pending.clear();
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    results.add(failureFactory.create(item, cause));
                } catch (Exception e) {
                    results.add(failureFactory.create(item, e));
                }
            }
        } finally {
            notifyMetrics(
                    metricsSink,
                    TimeUnit.NANOSECONDS.toMillis(downloadWaitNanos),
                    TimeUnit.NANOSECONDS.toMillis(processNanos)
            );
        }
        return results;
    }

    private static void cancelRemaining(Map<? extends Future<?>, ?> pending) {
        for (Future<?> future : pending.keySet()) {
            future.cancel(true);
        }
    }

    private static <I, R> void addFailures(List<R> results,
                                            Iterable<I> items,
                                            FailureFactory<I, R> failureFactory,
                                            Throwable error) {
        for (I item : items) {
            results.add(failureFactory.create(item, error));
        }
    }

    private static void notifyMetrics(MetricsSink metricsSink,
                                      long downloadWaitMs,
                                      long processMs) {
        if (metricsSink != null) {
            metricsSink.onBatchComplete(downloadWaitMs, processMs);
        }
    }
}
