package com.punch.app.fragment;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StatisticsRequestCoordinatorTest {
    @Test
    public void sameActiveQueryFromMultipleAllowedTriggersStartsOnlyOnce() {
        StatisticsRequestCoordinator coordinator = new StatisticsRequestCoordinator();
        StatisticsRequestCoordinator.QueryKey key = queryKey("2026-08-20", 1, 0, 1);

        StatisticsRequestCoordinator.RequestToken first = coordinator.begin(
                StatisticsRequestCoordinator.Reason.PANEL_READY, key);
        StatisticsRequestCoordinator.RequestToken duplicate = coordinator.begin(
                StatisticsRequestCoordinator.Reason.PANEL_ENTER, key);

        assertNotNull(first);
        assertNull(duplicate);
        coordinator.complete(first);
        assertNotNull(coordinator.begin(StatisticsRequestCoordinator.Reason.PANEL_ENTER, key));
    }

    @Test
    public void changedQueryInvalidatesOlderResponse() {
        StatisticsRequestCoordinator coordinator = new StatisticsRequestCoordinator();
        StatisticsRequestCoordinator.RequestToken oldRequest = coordinator.begin(
                StatisticsRequestCoordinator.Reason.PANEL_ENTER,
                queryKey("2026-08-20", 1, 0, 1));
        StatisticsRequestCoordinator.RequestToken newRequest = coordinator.begin(
                StatisticsRequestCoordinator.Reason.FILTER_CHANGED,
                queryKey("2026-08-20", 1, 1, 1));

        assertFalse(coordinator.isCurrent(oldRequest));
        assertTrue(coordinator.isCurrent(newRequest));
        coordinator.complete(oldRequest);
        assertTrue(coordinator.isCurrent(newRequest));
    }

    @Test
    public void invalidationRejectsResponseFromDestroyedView() {
        StatisticsRequestCoordinator coordinator = new StatisticsRequestCoordinator();
        StatisticsRequestCoordinator.RequestToken request = coordinator.begin(
                StatisticsRequestCoordinator.Reason.PANEL_READY,
                queryKey("2026-08-20", 1, 0, 1));

        coordinator.invalidate();

        assertFalse(coordinator.isCurrent(request));
    }

    private static StatisticsRequestCoordinator.QueryKey queryKey(String date,
                                                                    int clockIndex,
                                                                    Integer status,
                                                                    int page) {
        return new StatisticsRequestCoordinator.QueryKey(
                date, "LINE-1", clockIndex, status, page, 20);
    }
}
