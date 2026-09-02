package com.punch.app.network;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

final class NetworkFaultController {
    private static final String UNREACHABLE_TEST_URL = "http://192.0.2.1:9";
    private static NetworkFaultServer server;
    private static String activeBaseUrl = "";

    private NetworkFaultController() { }

    static synchronized NetworkFaultServer useLocal(NetworkFaultServer.Mode mode) throws IOException {
        if (server == null) server = new NetworkFaultServer();
        server.setMode(mode);
        installShortTimeoutClient();
        activeBaseUrl = "http://127.0.0.1:" + server.getPort();
        ApiClient.setBaseUrlForTest(activeBaseUrl);
        return server;
    }

    static synchronized void useUnreachable() {
        installShortTimeoutClient();
        activeBaseUrl = UNREACHABLE_TEST_URL;
        ApiClient.setBaseUrlForTest(activeBaseUrl);
    }

    static synchronized String getActiveBaseUrl() { return activeBaseUrl; }
    static synchronized NetworkFaultServer getServer() { return server; }

    private static void installShortTimeoutClient() {
        ApiClient.setClientForTest(new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build());
    }

    static synchronized void reset() {
        if (server != null) {
            server.close();
            server = null;
        }
        activeBaseUrl = "";
        ApiClient.resetForTest();
    }
}
