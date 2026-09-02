package com.punch.app.network;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Test-only loopback-only ApiClient selector for V2.7.1. */
public final class ProcessRecoveryNetworkController {
    private static final String SAFE_IDLE_BASE_URL = "http://127.0.0.1:1";
    private static ProcessRecoveryServer server;
    private static String baseUrl = SAFE_IDLE_BASE_URL;

    private ProcessRecoveryNetworkController() { }

    public static synchronized ProcessRecoveryServer startLoopback(long punchDelayMs) throws IOException {
        reset();
        server = new ProcessRecoveryServer(punchDelayMs);
        installShortTimeoutClient();
        baseUrl = "http://127.0.0.1:" + server.getPort();
        ApiClient.setBaseUrlForTest(baseUrl);
        return server;
    }

    public static synchronized String getBaseUrl() { return baseUrl; }

    public static synchronized void reset() {
        if (server != null) {
            server.close();
            server = null;
        }
        installShortTimeoutClient();
        baseUrl = SAFE_IDLE_BASE_URL;
        ApiClient.setBaseUrlForTest(SAFE_IDLE_BASE_URL);
    }

    private static void installShortTimeoutClient() {
        ApiClient.setClientForTest(new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build());
    }
}
