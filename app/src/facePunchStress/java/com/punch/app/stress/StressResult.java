package com.punch.app.stress;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StressResult {
    public final String mode;
    public final int requested;
    public int completed;
    public int success;
    public int failed;
    public String employeeId = "";
    public String fixtureSource = "";
    public boolean fixtureRegistered;
    public String error = "";
    public int punchRecordCount;
    public int queueCount;
    public int snapshotCount;
    public long snapshotBytes;
    private final List<Long> latenciesMs = new ArrayList<>();
    private final List<String> outcomes = new ArrayList<>();

    public StressResult(String mode, int requested) {
        this.mode = mode;
        this.requested = requested;
    }

    public void add(long latencyMs, boolean ok, String outcome) {
        completed++;
        if (ok) success++; else failed++;
        latenciesMs.add(Math.max(0L, latencyMs));
        outcomes.add(outcome == null ? "" : outcome);
    }

    private long percentile(double p) {
        if (latenciesMs.isEmpty()) return 0L;
        List<Long> copy = new ArrayList<>(latenciesMs);
        Collections.sort(copy);
        int index = (int)Math.ceil(p * copy.size()) - 1;
        index = Math.max(0, Math.min(index, copy.size() - 1));
        return copy.get(index);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("mode", mode);
        o.put("requested", requested);
        o.put("completed", completed);
        o.put("success", success);
        o.put("failed", failed);
        o.put("employee_id", employeeId);
        o.put("fixture_source", fixtureSource);
        o.put("fixture_registered", fixtureRegistered);
        o.put("error", error);
        o.put("punch_record_count", punchRecordCount);
        o.put("queue_count", queueCount);
        o.put("snapshot_count", snapshotCount);
        o.put("snapshot_bytes", snapshotBytes);
        long sum = 0L;
        for (Long value : latenciesMs) sum += value;
        long minLatencyMs = latenciesMs.isEmpty() ? 0L : Collections.min(latenciesMs).longValue();
        o.put("latency_min_ms", minLatencyMs);
        o.put("latency_mean_ms", latenciesMs.isEmpty() ? 0 : (sum / latenciesMs.size()));
        o.put("latency_p50_ms", percentile(0.50));
        o.put("latency_p95_ms", percentile(0.95));
        o.put("latency_p99_ms", percentile(0.99));
        long maxLatencyMs = latenciesMs.isEmpty() ? 0L : Collections.max(latenciesMs).longValue();
        o.put("latency_max_ms", maxLatencyMs);
        JSONArray a = new JSONArray();
        for (int i = 0; i < latenciesMs.size(); i++) {
            JSONObject row = new JSONObject();
            row.put("index", i + 1);
            row.put("latency_ms", latenciesMs.get(i));
            row.put("outcome", outcomes.get(i));
            a.put(row);
        }
        o.put("iterations", a);
        return o;
    }
}
