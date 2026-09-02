package com.punch.app.network;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class NetworkFaultServer implements AutoCloseable {
    enum Mode { SUCCESS, HTTP_401, HTTP_500, TIMEOUT, DROP }

    static final class RequestRecord {
        final String method;
        final String path;
        final Map<String, String> headers;
        final String body;

        RequestRecord(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("method", method);
                o.put("path", path);
                o.put("body", body);
                o.put("authorization_present", headers.containsKey("authorization"));
            } catch (Exception ignored) { }
            return o;
        }
    }

    private final ServerSocket serverSocket;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger punchRequestCount = new AtomicInteger();
    private final AtomicInteger refreshRequestCount = new AtomicInteger();
    private final List<RequestRecord> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Mode mode = Mode.SUCCESS;

    NetworkFaultServer() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        acceptExecutor.execute(this::acceptLoop);
    }

    int getPort() { return serverSocket.getLocalPort(); }
    void setMode(Mode mode) { this.mode = mode == null ? Mode.SUCCESS : mode; }
    int getPunchRequestCount() { return punchRequestCount.get(); }
    int getRefreshRequestCount() { return refreshRequestCount.get(); }

    void resetHistory() {
        punchRequestCount.set(0);
        refreshRequestCount.set(0);
        synchronized (requests) { requests.clear(); }
    }

    List<RequestRecord> getRequestsSnapshot() {
        synchronized (requests) { return new ArrayList<>(requests); }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                connectionExecutor.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running.get()) { /* test harness records request-state failures elsewhere */ }
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket; BufferedInputStream input = new BufferedInputStream(s.getInputStream())) {
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.trim().isEmpty()) return;
            String[] parts = requestLine.split(" ", 3);
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "";
            Map<String, String> headers = new LinkedHashMap<>();
            int contentLength = 0;
            while (true) {
                String line = readLine(input);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
                if ("content-length".equals(key)) {
                    try { contentLength = Integer.parseInt(value); } catch (NumberFormatException ignored) { }
                }
            }
            byte[] bodyBytes = readExactly(input, Math.max(0, contentLength));
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            requests.add(new RequestRecord(method, path, headers, body));

            boolean punch = path.contains("/clock/upload");
            if (path.contains("/auth/refresh")) refreshRequestCount.incrementAndGet();
            if (!punch) {
                writeSuccessForNonPunch(s.getOutputStream(), path);
                return;
            }
            punchRequestCount.incrementAndGet();
            Mode active = mode;
            if (active == Mode.DROP) return;
            if (active == Mode.TIMEOUT) {
                try { Thread.sleep(3500L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                writePunchSuccess(s.getOutputStream());
                return;
            }
            if (active == Mode.HTTP_401) {
                writeJson(s.getOutputStream(), 401, "Unauthorized", "{\"code\":401,\"msg\":\"unauthorized\",\"data\":null}");
                return;
            }
            if (active == Mode.HTTP_500) {
                writeJson(s.getOutputStream(), 500, "Internal Server Error", "{\"code\":500,\"msg\":\"server_error\",\"data\":null}");
                return;
            }
            writePunchSuccess(s.getOutputStream());
        } catch (Exception ignored) { }
    }

    private void writeSuccessForNonPunch(OutputStream output, String path) throws IOException {
        long now = System.currentTimeMillis() / 1000L;
        if (path.contains("/auth/refresh")) {
            writeJson(output, 200, "OK", "{\"code\":200,\"msg\":\"ok\",\"data\":{\"token\":\"V25_TEST_TOKEN\",\"token_expire_at\":" + (now + 3600) + "}}");
        } else if (path.contains("/device/heartbeat")) {
            // Punch sync is enqueued before heartbeat. Returning a controlled failure prevents
            // the production SessionManager from persisting test heartbeat timestamps.
            writeJson(output, 503, "Service Unavailable", "{\"code\":503,\"msg\":\"v25_heartbeat_suppressed\",\"data\":null}");
        } else {
            writeJson(output, 200, "OK", "{\"code\":200,\"msg\":\"ok\",\"data\":{}}");
        }
    }

    private void writePunchSuccess(OutputStream output) throws IOException {
        long now = System.currentTimeMillis() / 1000L;
        writeJson(output, 200, "OK", "{\"code\":200,\"msg\":\"ok\",\"data\":{\"record_id\":\"NET_V25_SERVER\",\"snap_time\":" + now + ",\"snap_time_str\":\"\",\"dates\":\"\",\"attend_report_id\":\"\",\"attend_report_table\":\"\"}}");
    }

    private void writeJson(OutputStream output, int code, String reason, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) return out.size() == 0 ? null : out.toString("UTF-8");
            if (previous == '\r' && current == '\n') {
                byte[] raw = out.toByteArray();
                int length = raw.length > 0 && raw[raw.length - 1] == '\r' ? raw.length - 1 : raw.length;
                return new String(raw, 0, length, StandardCharsets.UTF_8);
            }
            out.write(current);
            previous = current;
        }
    }

    private static byte[] readExactly(BufferedInputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = input.read(data, offset, length - offset);
            if (n < 0) break;
            offset += n;
        }
        if (offset == length) return data;
        byte[] shortData = new byte[offset];
        System.arraycopy(data, 0, shortData, 0, offset);
        return shortData;
    }

    @Override public void close() {
        running.set(false);
        try { serverSocket.close(); } catch (IOException ignored) { }
        acceptExecutor.shutdownNow();
        connectionExecutor.shutdownNow();
    }
}
