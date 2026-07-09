package com.punch.app.network;

public final class InteractionLogger {
    public static final String CATEGORY_NETWORK = "network";
    public static final String CATEGORY_BUSINESS = "business";

    public static final String GROUP_HEARTBEAT = "heartbeat";
    public static final String GROUP_EMPLOYEE_SYNC = "employee_sync";
    public static final String GROUP_EVENT_RESULT = "event_result";
    public static final String GROUP_DEVICE_CONFIG = "device_config";
    public static final String GROUP_AUTH = "auth";
    public static final String GROUP_PUNCH = "punch";
    public static final String GROUP_UPDATE = "update";
    public static final String GROUP_GENERAL = "general";

    private InteractionLogger() {
    }

    public static void logBusiness(String group, String title, String detail) {
        appendBusiness(group, title, detail, true);
    }

    public static void logBusinessFailure(String group, String title, String detail) {
        appendBusiness(group, title, detail, false);
    }

    public static String resolveNetworkGroup(String path) {
        String safePath = path == null ? "" : path;
        if (safePath.contains("/device/heartbeat")) {
            return GROUP_HEARTBEAT;
        }
        if (safePath.contains("/employee/sync")) {
            return GROUP_EMPLOYEE_SYNC;
        }
        if (safePath.contains("/event/result")) {
            return GROUP_EVENT_RESULT;
        }
        if (safePath.contains("/device/get-config")) {
            return GROUP_DEVICE_CONFIG;
        }
        if (safePath.contains("/auth/login") || safePath.contains("/auth/refresh")) {
            return GROUP_AUTH;
        }
        if (safePath.contains("/clock/upload") || safePath.contains("/clock/statistics")) {
            return GROUP_PUNCH;
        }
        return GROUP_GENERAL;
    }

    public static String resolveNetworkTitle(String method, String path) {
        String safeMethod = method == null ? "" : method;
        String safePath = path == null ? "" : path;
        if (safePath.contains("/device/heartbeat")) {
            return "心跳上报";
        }
        if (safePath.contains("/employee/sync")) {
            return "同步员工列表";
        }
        if (safePath.contains("/event/result")) {
            return "事件结果回传";
        }
        if (safePath.contains("/device/get-config")) {
            return "获取设备配置";
        }
        if (safePath.contains("/auth/login")) {
            return "设备登录";
        }
        if (safePath.contains("/auth/refresh")) {
            return "刷新令牌";
        }
        if (safePath.contains("/clock/upload")) {
            return "打卡记录上传";
        }
        if (safePath.contains("/clock/statistics")) {
            return "打卡统计查询";
        }
        return (safeMethod + " " + safePath).trim();
    }

    public static String groupLabel(String group) {
        if (GROUP_HEARTBEAT.equals(group)) {
            return "心跳";
        }
        if (GROUP_EMPLOYEE_SYNC.equals(group)) {
            return "员工同步";
        }
        if (GROUP_EVENT_RESULT.equals(group)) {
            return "结果回传";
        }
        if (GROUP_DEVICE_CONFIG.equals(group)) {
            return "设备配置";
        }
        if (GROUP_AUTH.equals(group)) {
            return "鉴权";
        }
        if (GROUP_PUNCH.equals(group)) {
            return "打卡";
        }
        if (GROUP_UPDATE.equals(group)) {
            return "软件更新";
        }
        return "通用";
    }

    private static void appendBusiness(String group, String title, String detail, boolean success) {
        InteractionLogStore store = InteractionLogStore.get();
        if (store == null) {
            return;
        }
        InteractionLogEntry entry = new InteractionLogEntry();
        entry.category = CATEGORY_BUSINESS;
        entry.group = normalizeGroup(group);
        entry.title = title == null ? "" : title.trim();
        entry.detail = detail == null ? "" : detail.trim();
        entry.method = "业务";
        entry.path = entry.group;
        entry.success = success;
        entry.timeMillis = System.currentTimeMillis();
        store.append(entry);
    }

    private static String normalizeGroup(String group) {
        return group == null || group.trim().isEmpty() ? GROUP_GENERAL : group.trim();
    }
}
