package com.punch.app.network;

public final class InteractionLogEntry {
    public String id = "";
    public String category = "";
    public String group = "";
    public String title = "";
    public String detail = "";
    public long timeMillis;
    public String method = "";
    public String path = "";
    public String url = "";
    public String requestBody = "";
    public String responseBody = "";
    public int httpStatus;
    public int backendCode;
    public boolean success;
    public long durationMs;
    public String backendMessage = "";
    public String errorMessage = "";
}
