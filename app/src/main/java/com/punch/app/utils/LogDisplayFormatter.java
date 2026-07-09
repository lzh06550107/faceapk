package com.punch.app.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.punch.app.network.InteractionLogEntry;
import com.punch.app.network.InteractionLogger;

public final class LogDisplayFormatter {
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private LogDisplayFormatter() {
    }

    public static String buildDetail(InteractionLogEntry entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "类型", InteractionLogger.CATEGORY_BUSINESS.equals(entry.category) ? "业务事件" : "网络请求");
        appendLine(builder, "分组", InteractionLogger.groupLabel(entry.group));
        if (InteractionLogger.CATEGORY_BUSINESS.equals(entry.category)) {
            appendLine(builder, "状态", entry.success ? "成功" : "失败");
            appendBlock(builder, "详情", safe(entry.detail));
            return builder.toString().trim();
        }
        appendLine(builder, "URL", safe(entry.url));
        appendLine(builder, "Path", safe(entry.path));
        appendLine(builder, "HTTP", entry.httpStatus > 0 ? String.valueOf(entry.httpStatus) : "-");
        appendLine(builder, "业务码", entry.backendCode != 0 ? String.valueOf(entry.backendCode) : "-");
        appendLine(builder, "业务消息", safe(entry.backendMessage));
        appendLine(builder, "耗时", entry.durationMs + " ms");
        appendLine(builder, "错误", safe(entry.errorMessage));
        appendBlock(builder, "请求体", prettyJson(safe(entry.requestBody)));
        appendBlock(builder, "响应体", prettyJson(safe(entry.responseBody)));
        return builder.toString().trim();
    }

    public static String buildExportText(java.util.List<InteractionLogEntry> entries) {
        StringBuilder builder = new StringBuilder();
        if (entries == null) {
            return "";
        }
        for (InteractionLogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            String title = safe(entry.title);
            if (title.isEmpty()) {
                title = (safe(entry.method) + " " + safe(entry.path)).trim();
            }
            builder.append(title).append('\n');
            builder.append(buildDetail(entry)).append("\n\n");
        }
        return builder.toString().trim();
    }

    private static void appendLine(StringBuilder builder, String label, String value) {
        if (value == null || value.trim().isEmpty() || "-".equals(value.trim())) {
            return;
        }
        builder.append(label).append(": ").append(value).append('\n');
    }

    private static void appendBlock(StringBuilder builder, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        builder.append('\n').append(label).append(":\n").append(value).append('\n');
    }

    private static String prettyJson(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            JsonElement element = JsonParser.parseString(trimmed);
            return PRETTY_GSON.toJson(element);
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
