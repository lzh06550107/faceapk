package com.punch.app.fragment;

import java.util.Objects;

final class StatisticsRequestCoordinator {
    enum Reason {
        PANEL_READY,
        PANEL_ENTER,
        FILTER_CHANGED,
        NEXT_PAGE,
        USER_REFRESH
    }

    static final class QueryKey {
        final String date;
        final String lineCode;
        final int clockIndex;
        final Integer clockStatus;
        final int page;
        final int pageSize;

        QueryKey(String date,
                 String lineCode,
                 int clockIndex,
                 Integer clockStatus,
                 int page,
                 int pageSize) {
            this.date = date;
            this.lineCode = lineCode;
            this.clockIndex = clockIndex;
            this.clockStatus = clockStatus;
            this.page = page;
            this.pageSize = pageSize;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof QueryKey)) {
                return false;
            }
            QueryKey key = (QueryKey) other;
            return clockIndex == key.clockIndex
                    && page == key.page
                    && pageSize == key.pageSize
                    && Objects.equals(date, key.date)
                    && Objects.equals(lineCode, key.lineCode)
                    && Objects.equals(clockStatus, key.clockStatus);
        }

        @Override
        public int hashCode() {
            return Objects.hash(date, lineCode, clockIndex, clockStatus, page, pageSize);
        }
    }

    static final class RequestToken {
        final long id;
        final QueryKey key;

        private RequestToken(long id, QueryKey key) {
            this.id = id;
            this.key = key;
        }
    }

    private long nextId;
    private RequestToken activeRequest;

    synchronized RequestToken begin(Reason reason, QueryKey key) {
        if (reason == null || key == null) {
            return null;
        }
        if (activeRequest != null && activeRequest.key.equals(key)) {
            return null;
        }
        activeRequest = new RequestToken(++nextId, key);
        return activeRequest;
    }

    synchronized boolean isCurrent(RequestToken token) {
        return token != null && activeRequest != null && token.id == activeRequest.id;
    }

    synchronized void complete(RequestToken token) {
        if (isCurrent(token)) {
            activeRequest = null;
        }
    }

    synchronized void invalidate() {
        nextId++;
        activeRequest = null;
    }
}
