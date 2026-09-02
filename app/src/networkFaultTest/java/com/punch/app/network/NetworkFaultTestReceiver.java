package com.punch.app.network;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exported only in networkFaultTest; adb uses it to drive deterministic V2.5 scenarios. */
public class NetworkFaultTestReceiver extends BroadcastReceiver {
    public static final String ACTION_RUN = "com.punch.app.networkfault.RUN";
    public static final String ACTION_STATUS = "com.punch.app.networkfault.STATUS";
    public static final String ACTION_CLEANUP = "com.punch.app.networkfault.CLEANUP";
    public static final String ACTION_PREPARE_OFFLINE = "com.punch.app.networkfault.PREPARE_OFFLINE";
    public static final String ACTION_OFFLINE_SYNC = "com.punch.app.networkfault.OFFLINE_SYNC";
    public static final String ACTION_RECOVER_OFFLINE = "com.punch.app.networkfault.RECOVER_OFFLINE";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static volatile String state = "idle";
    private static volatile String scenario = "";
    private static volatile String error = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Context app = context.getApplicationContext();

        if (ACTION_STATUS.equals(action)) {
            setResultCode(Activity.RESULT_OK);
            setResultData(statusData(app));
            return;
        }
        if (ACTION_CLEANUP.equals(action)) {
            NetworkFaultTestEngine.cleanup(app);
            state = "cleaned";
            error = "";
            setResultCode(Activity.RESULT_OK);
            setResultData("cleaned=true");
            return;
        }
        if (busy) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("busy=true;state=" + state);
            return;
        }

        if (ACTION_RUN.equals(action)) {
            String requested = intent.getStringExtra("scenario");
            scenario = requested == null || requested.trim().isEmpty() ? "AllSafe" : requested.trim();
            startAsync(app, "running", () -> NetworkFaultTestEngine.run(app, scenario), "completed");
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;scenario=" + scenario);
            return;
        }
        if (ACTION_PREPARE_OFFLINE.equals(action)) {
            scenario = "DeviceOfflineRecovery";
            startAsync(app, "preparing", () -> NetworkFaultTestEngine.prepareDeviceOffline(app), "prepared");
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=prepare_offline");
            return;
        }
        if (ACTION_OFFLINE_SYNC.equals(action)) {
            scenario = "DeviceOfflineRecovery";
            startAsync(app, "offline_syncing", () -> NetworkFaultTestEngine.executeDeviceOfflineFailure(app), "offline_retained");
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=offline_sync");
            return;
        }
        if (ACTION_RECOVER_OFFLINE.equals(action)) {
            scenario = "DeviceOfflineRecovery";
            startAsync(app, "recovering", () -> NetworkFaultTestEngine.recoverDeviceOffline(app), "completed");
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=recover_offline");
        }
    }

    private static void startAsync(Context context,
                                   String startedState,
                                   Work work,
                                   String successState) {
        busy = true;
        state = startedState;
        error = "";
        EXECUTOR.execute(() -> {
            try {
                JSONObject result = work.run();
                write(new File(resultDir(context), "result.json"), result.toString());
                write(new File(resultDir(context), "requests.jsonl"), NetworkFaultTestEngine.requestJsonLines());
                if (!result.optBoolean("success", false)) {
                    throw new IllegalStateException("scenario_gate_failed");
                }
                state = successState;
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                state = "failed";
            } finally {
                busy = false;
                try { writeStatus(context); } catch (Throwable ignored) { }
            }
        });
    }

    private static String statusData(Context context) {
        JSONObject engine = NetworkFaultTestEngine.status(context);
        return "busy=" + busy
                + ";state=" + state
                + ";scenario=" + scenario
                + ";error=" + sanitize(error)
                + ";database_prepared=" + engine.optBoolean("database_prepared", false)
                + ";token_valid=" + engine.optBoolean("token_valid", false)
                + ";device_registered=" + engine.optBoolean("device_registered", false)
                + ";base_url=" + sanitize(engine.optString("base_url", ""));
    }

    private static void writeStatus(Context context) throws Exception {
        JSONObject result = NetworkFaultTestEngine.status(context);
        result.put("busy", busy);
        result.put("state", state);
        result.put("scenario", scenario);
        result.put("error", error);
        write(new File(resultDir(context), "status.json"), result.toString());
    }

    private static File resultDir(Context context) {
        File dir = new File(context.getFilesDir(), "network-fault-v25");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void write(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file, false);
        try {
            out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace(';', '_').replace('\n', ' ').replace('\r', ' ');
    }

    private interface Work { JSONObject run() throws Exception; }
}
