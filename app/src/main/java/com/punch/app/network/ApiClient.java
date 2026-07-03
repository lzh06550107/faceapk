package com.punch.app.network;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {
    private static final String TAG = "ApiClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    private static OkHttpClient client;
    private static String baseUrlOverride;

    private static OkHttpClient getClient() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    static void setClientForTest(OkHttpClient testClient) {
        client = testClient;
    }

    static void setBaseUrlForTest(String baseUrl) {
        baseUrlOverride = baseUrl;
    }

    static void resetForTest() {
        client = null;
        baseUrlOverride = null;
    }

    private static String baseUrl() {
        return baseUrlOverride != null ? baseUrlOverride : SessionManager.get().getBaseUrl();
    }

    public static ApiResponse get(String path) {
        Request request = buildRequest(path, null);
        return execute(request);
    }

    public static ApiResponse post(String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request request = buildRequest(path, rb);
        return execute(request);
    }

    public static ApiResponse postForm(String path, Map<String, Object> body) {
        FormBody.Builder builder = new FormBody.Builder();
        if (body != null) {
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                Object value = entry.getValue();
                if (entry.getKey() == null || value == null) {
                    continue;
                }
                builder.add(entry.getKey(), String.valueOf(value));
            }
        }
        Request request = buildFormRequest(path, builder.build());
        return execute(request);
    }

    public static ApiResponse post(String baseUrl, String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request request = buildRequest(baseUrl, path, rb);
        return execute(request);
    }

    public static ApiResponse postPublic(String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request request = buildPublicRequest(baseUrl(), path, rb, "POST");
        return execute(request);
    }

    public static ApiResponse postPublic(String baseUrl, String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request request = buildPublicRequest(baseUrl, path, rb, "POST");
        return execute(request);
    }

    public static ApiResponse put(String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request.Builder builder = new Request.Builder()
                .url(baseUrl() + path)
                .put(rb)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId())
                .addHeader("Content-Type", "application/json");
        addAuthorizationHeader(builder);
        return execute(builder.build());
    }

    private static Request buildRequest(String path, RequestBody body) {
        return buildRequest(baseUrl(), path, body);
    }

    private static Request buildRequest(String baseUrl, String path, RequestBody body) {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId())
                .addHeader("Content-Type", "application/json");
        addAuthorizationHeader(builder);
        if (body != null) {
            builder.post(body);
        } else {
            builder.get();
        }
        return builder.build();
    }

    private static Request buildPublicRequest(String baseUrl, String path, RequestBody body, String method) {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId())
                .addHeader("Content-Type", "application/json");
        if ("POST".equals(method)) {
            builder.post(body);
        } else {
            builder.method(method, body);
        }
        return builder.build();
    }

    private static Request buildFormRequest(String path, RequestBody body) {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl() + path)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId())
                .addHeader("Content-Type", "application/x-www-form-urlencoded");
        addAuthorizationHeader(builder);
        return builder.post(body).build();
    }

    private static ApiResponse execute(Request request) {
        try (Response response = getClient().newCall(request).execute()) {
            String bodyStr = response.body() != null ? response.body().string() : "{}";
            JsonObject obj = parseJsonObject(bodyStr);
            if (!response.isSuccessful()) {
                int code = extractCode(obj, response.code());
                String message = extractMessage(obj);
                if (message == null || message.trim().isEmpty()) {
                    message = "HTTP " + response.code();
                }
                return new ApiResponse(false, code, message, null);
            }
            if (obj == null) {
                return new ApiResponse(false, response.code(), "Invalid response", null);
            }
            int code = obj.has("code") ? obj.get("code").getAsInt() : 200;
            String msg = obj.has("msg") ? obj.get("msg").getAsString() : "";
            boolean ok = code == 200 || code == 0;
            return new ApiResponse(ok, code, msg, ok && obj.has("data") ? obj.get("data") : null);
        } catch (IOException e) {
            Log.e(TAG, "Request failed: " + e.getMessage());
            return new ApiResponse(false, -1, e.getMessage(), null);
        }
    }

    public static boolean isNetworkAvailable() {
        try {
            Request req = new Request.Builder()
                    .url(baseUrl() + ApiEndpoints.HEALTH)
                    .get().build();
            Response resp = getClient().newCall(req).execute();
            resp.close();
            return resp.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean downloadToFile(String url, File destination) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId());
        addAuthorizationHeader(builder);
        Request request = builder.build();

        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }

        try (Response response = getClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }

            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(destination, false)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "Download failed: " + e.getMessage());
            return false;
        }
    }

    private static void addAuthorizationHeader(Request.Builder builder) {
        String token = SessionManager.get().getToken();
        if (token != null && !token.trim().isEmpty()) {
            builder.addHeader("Authorization", "Bearer " + token.trim());
        }
    }

    private static JsonObject parseJsonObject(String bodyStr) {
        try {
            return GSON.fromJson(bodyStr, JsonObject.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int extractCode(JsonObject obj, int fallbackCode) {
        if (obj == null || !obj.has("code") || obj.get("code").isJsonNull()) {
            return fallbackCode;
        }
        try {
            return obj.get("code").getAsInt();
        } catch (Exception ignored) {
            return fallbackCode;
        }
    }

    private static String extractMessage(JsonObject obj) {
        if (obj == null || !obj.has("msg") || obj.get("msg").isJsonNull()) {
            return null;
        }
        try {
            return obj.get("msg").getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
