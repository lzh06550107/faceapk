package com.punch.app.network;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Test-only loopback-only ApiClient selector for V2.7.3. */
public final class CameraFaceRecoveryNetworkController {
    private static final String SAFE_IDLE_BASE_URL = "http://127.0.0.1:1";
    private static String baseUrl = SAFE_IDLE_BASE_URL;

    private CameraFaceRecoveryNetworkController() { }

    public static synchronized String getBaseUrl() { return baseUrl; }

    public static synchronized void reset() {
        ApiClient.setClientForTest(new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build());
        baseUrl = SAFE_IDLE_BASE_URL;
        ApiClient.setBaseUrlForTest(SAFE_IDLE_BASE_URL);
    }
}
