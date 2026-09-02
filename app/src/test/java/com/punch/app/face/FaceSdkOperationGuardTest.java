package com.punch.app.face;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FaceSdkOperationGuardTest {

    @Test
    public void sharedGuardIsProcessWide() {
        assertTrue(FaceSdkOperationGuard.shared() == FaceSdkOperationGuard.shared());
    }
    @Test
    public void sdkOperationsRunOneAtATime() throws Exception {
        FaceSdkOperationGuard guard = FaceSdkOperationGuard.shared();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        Future<String> first = executor.submit(() -> guard.call(() -> {
            events.add("first-start");
            firstStarted.countDown();
            await(releaseFirst);
            events.add("first-end");
            return "first";
        }));

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        Future<String> second = executor.submit(() -> guard.call(() -> {
            events.add("second");
            secondFinished.countDown();
            return "second";
        }));

        assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS));

        releaseFirst.countDown();

        assertEquals("first", first.get(2, TimeUnit.SECONDS));
        assertEquals("second", second.get(2, TimeUnit.SECONDS));
        assertEquals("first-start", events.get(0));
        assertEquals("first-end", events.get(1));
        assertEquals("second", events.get(2));
        executor.shutdownNow();
    }

    @Test
    public void sdkOperationGuardReleasesAfterFailure() {
        FaceSdkOperationGuard guard = FaceSdkOperationGuard.shared();

        try {
            guard.call(() -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
        }

        assertEquals("next", guard.call(() -> "next"));
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
