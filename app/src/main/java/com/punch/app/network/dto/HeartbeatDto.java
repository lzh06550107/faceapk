package com.punch.app.network.dto;

import java.util.ArrayList;
import java.util.List;

public final class HeartbeatDto {
    private HeartbeatDto() {
    }

    public static final class HeartbeatEventData {
        public String cursor = "";
        public String eventType = "";
        public String scopeType = "";
        public String scopeValue = "";
    }

    public static final class HeartbeatData {
        public long serverTime;
        public boolean hasChanges;
        public final List<HeartbeatEventData> events = new ArrayList<>();
    }
}
