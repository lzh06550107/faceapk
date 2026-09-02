package com.punch.app.face;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ParallelDownloadBatchRunnerTest {

    @Test
    public void downloadsOverlapWhileProcessingStaysSerial() {
        ExecutorService downloads = Executors.newFixedThreadPool(4);
        AtomicInteger activeDownloads = new AtomicInteger();
        AtomicInteger maxDownloads = new AtomicInteger();
        AtomicInteger activeProcessors = new AtomicInteger();
        AtomicInteger maxProcessors = new AtomicInteger();
        CountDownLatch allDownloadsStarted = new CountDownLatch(4);
        CountDownLatch releaseDownloads = new CountDownLatch(1);

        try {
            List<String> results = ParallelDownloadBatchRunner.run(
                    Arrays.asList("a", "b", "c", "d"),
                    downloads,
                    item -> {
                        int active = activeDownloads.incrementAndGet();
                        maxDownloads.accumulateAndGet(active, Math::max);
                        allDownloadsStarted.countDown();
                        if (allDownloadsStarted.getCount() == 0) {
                            releaseDownloads.countDown();
                        }
                        await(releaseDownloads);
                        activeDownloads.decrementAndGet();
                        return item + "-downloaded";
                    },
                    (item, downloaded) -> {
                        int active = activeProcessors.incrementAndGet();
                        maxProcessors.accumulateAndGet(active, Math::max);
                        activeProcessors.decrementAndGet();
                        return downloaded + "-processed";
                    },
                    (item, error) -> item + "-failed"
            );

            assertEquals(4, results.size());
            assertTrue(results.contains("a-downloaded-processed"));
            assertTrue(results.contains("b-downloaded-processed"));
            assertTrue(results.contains("c-downloaded-processed"));
            assertTrue(results.contains("d-downloaded-processed"));
            assertTrue(maxDownloads.get() > 1);
            assertEquals(1, maxProcessors.get());
        } finally {
            releaseDownloads.countDown();
            downloads.shutdownNow();
        }
    }


    @Test
    public void defaultDownloadParallelismIsFour() {
        assertEquals(4, ParallelDownloadBatchRunner.DEFAULT_DOWNLOAD_PARALLELISM);
    }

    @Test(timeout = 2000L)
    public void processesCompletedDownloadWithoutWaitingForEarlierSlowItem() {
        ExecutorService downloads = Executors.newFixedThreadPool(2);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastDownloaded = new CountDownLatch(1);
        try {
            List<String> results = ParallelDownloadBatchRunner.run(
                    Arrays.asList("slow", "fast"),
                    downloads,
                    item -> {
                        if ("slow".equals(item)) {
                            await(releaseSlow);
                        } else {
                            fastDownloaded.countDown();
                        }
                        return item + "-downloaded";
                    },
                    (item, downloaded) -> {
                        if ("fast".equals(item)) {
                            releaseSlow.countDown();
                        }
                        return downloaded;
                    },
                    (item, error) -> item + "-failed"
            );

            assertEquals(Arrays.asList(
                    "fast-downloaded",
                    "slow-downloaded"), results);
            assertEquals(0L, fastDownloaded.getCount());
        } finally {
            releaseSlow.countDown();
            downloads.shutdownNow();
        }
    }

    @Test
    public void oneDownloadFailureDoesNotAbortOtherEmployees() {
        ExecutorService downloads = Executors.newFixedThreadPool(3);
        try {
            List<String> results = ParallelDownloadBatchRunner.run(
                    Arrays.asList("a", "bad", "c"),
                    downloads,
                    item -> {
                        if ("bad".equals(item)) {
                            throw new IllegalStateException("download failed");
                        }
                        return item + "-downloaded";
                    },
                    (item, downloaded) -> downloaded + "-processed",
                    (item, error) -> item + ":" + error.getMessage()
            );
            assertEquals(3, results.size());
            assertTrue(results.contains("a-downloaded-processed"));
            assertTrue(results.contains("bad:download failed"));
            assertTrue(results.contains("c-downloaded-processed"));
        } finally {
            downloads.shutdownNow();
        }
    }


    @Test
    public void reportsAggregateDownloadWaitAndProcessorTime() {
        ExecutorService downloads = Executors.newFixedThreadPool(2);
        long[] metrics = {-1L, -1L};
        try {
            List<String> results = ParallelDownloadBatchRunner.run(
                    Arrays.asList("a", "b"),
                    downloads,
                    item -> item + "-downloaded",
                    (item, downloaded) -> {
                        Thread.sleep(5L);
                        return downloaded + "-processed";
                    },
                    (item, error) -> item + "-failed",
                    (downloadWaitMs, processMs) -> {
                        metrics[0] = downloadWaitMs;
                        metrics[1] = processMs;
                    }
            );

            assertEquals(2, results.size());
            assertTrue(metrics[0] >= 0L);
            assertTrue(metrics[1] >= 5L);
        } finally {
            downloads.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
