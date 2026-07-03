package com.punch.app.network.dto;

import java.util.ArrayList;
import java.util.List;

public final class DeviceDto {
    private DeviceDto() {
    }

    public static final class DeviceRegisterData {
        public String deviceId = "";
    }

    public static final class DeviceActivateData {
        public String deviceId = "";
        public String activationCode = "";
    }

    public static final class LineOptionData {
        public String code = "";
        public String name = "";
    }

    public static final class TeamOptionData {
        public int id;
        public String name = "";
        public final List<String> timeRanges = new ArrayList<>();
    }

    public static final class UpdateInfoData {
        public boolean needUpdate;
        public String apkUrl = "";
        public String currentVersion = "";
        public String targetVersion = "";
        public String versionName = "";
    }

    public static final class DeviceConfigData {
        public String deviceId = "";
        public String account = "";
        public String password = "";
        public String lineCode = "";
        public String lineName = "";
        public int teamBindingId;
        public String teamBindingName = "";
        public boolean needUpdate;
        public final List<LineOptionData> lines = new ArrayList<>();
        public final List<TeamOptionData> teams = new ArrayList<>();
        public final UpdateInfoData updateInfo = new UpdateInfoData();
        public Float matchThreshold;
        public Float faceThreshold;
        public Boolean livenessCheck;
        public Boolean maskDetect;
        public Integer timeoutSeconds;
        public String recognitionDistanceMode;
    }
}
