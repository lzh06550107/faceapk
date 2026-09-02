package com.punch.app.network;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only success server for V2.6 production punch sync. */
public final class DbStressServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger punchRequestCount = new AtomicInteger();

    public DbStressServer() throws IOException {
        serverSocket = new ServerSocket(0, 100, InetAddress.getByName("127.0.0.1"));
        acceptExecutor.execute(this::acceptLoop);
    }

    public int getPort() { return serverSocket.getLocalPort(); }
    public int getPunchRequestCount() { return punchRequestCount.get(); }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                connectionExecutor.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!running.get()) return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket; BufferedInputStream input = new BufferedInputStream(s.getInputStream())) {
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.trim().isEmpty()) return;
            String[] parts = requestLine.split(" ", 3);
            String path = parts.length > 1 ? parts[1] : "";
            int contentLength = 0;
            while (true) {
                String line = readLine(input);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                if ("content-length".equals(key)) {
                    try { contentLength = Integer.parseInt(value); } catch (NumberFormatException ignored) { }
                }
            }
            readExactly(input, Math.max(0, contentLength));
            if (path.contains("/clock/upload")) {
                punchRequestCount.incrementAndGet();
                writePunchSuccess(s.getOutputStream());
            } else if (path.contains("/device/heartbeat")) {
                writeJson(s.getOutputStream(), 503, "Service Unavailable",
                        "{\"code\":503,\"msg\":\"v26_heartbeat_suppressed\",\"data\":null}");
            } else {
                writeJson(s.getOutputStream(), 200, "OK", "{\"code\":200,\"msg\":\"ok\",\"data\":{}}");
            }
        } catch (Exception ignored) { }
    }

    private void writePunchSuccess(OutputStream output) throws IOException {
        long now = System.currentTimeMillis() / 1000L;
        writeJson(output, 200, "OK",
                "{\"code\":200,\"msg\":\"ok\",\"data\":{\"record_id\":\"DB_V26_SERVER\",\"snap_time\":"
                        + now + ",\"snap_time_str\":\"\",\"dates\":\"\",\"attend_report_id\":\"\",\"attend_report_table\":\"\"}}");
    }

    private void writeJson(OutputStream output, int code, String reason, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + reason
                + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n";
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
        return data;
    }

    @Override
    public void close() {
        running.set(false);
        try { serverSocket.close(); } catch (IOException ignored) { }
        acceptExecutor.shutdownNow();
        connectionExecutor.shutdownNow();
    }
}
