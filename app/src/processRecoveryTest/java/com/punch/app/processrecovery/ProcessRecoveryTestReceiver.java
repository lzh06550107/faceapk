package com.punch.app.processrecovery;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exported only in processRecoveryTest; adb drives V2.7.1 recovery phases through this receiver. */
public class ProcessRecoveryTestReceiver extends BroadcastReceiver {
    public static final String ACTION_STATUS = "com.punch.app.processrecovery.STATUS";
    public static final String ACTION_PREPARE_SYNC_KILL = "com.punch.app.processrecovery.PREPARE_SYNC_KILL";
    public static final String ACTION_KILL_FOREGROUND = "com.punch.app.processrecovery.KILL_FOREGROUND";
    public static final String ACTION_VERIFY_SYNC_RESTART = "com.punch.app.processrecovery.VERIFY_SYNC_RESTART";
    public static final String ACTION_RESUME_SYNC = "com.punch.app.processrecovery.RESUME_SYNC";
    public static final String ACTION_CLEANUP = "com.punch.app.processrecovery.CLEANUP";
    public static final String ACTION_TERMINATE = "com.punch.app.processrecovery.TERMINATE";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static volatile String state = "idle";
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
            ProcessRecoveryTestEngine.cleanup(app);
            state = "cleaned";
            error = "";
            setResultCode(Activity.RESULT_OK);
            setResultData("cleaned=true");
            return;
        }
        if (ACTION_KILL_FOREGROUND.equals(action)) {
            state = "foreground_kill_scheduled";
            error = "";
            try {
                JSONObject event = killEvent("foreground", "scheduled");
                write(new File(resultDir(app), "foreground-kill.json"), event.toString());
                append(new File(resultDir(app), "kill-events.jsonl"), event.toString() + "\n");
            } catch (Throwable ignored) { }
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=foreground_kill");
            scheduleKill(250L);
            return;
        }
        if (ACTION_TERMINATE.equals(action)) {
            state = "terminating";
            error = "";
            try { ProcessRecoveryTestEngine.cleanup(app); } catch (Throwable ignored) { }
            try {
                append(new File(resultDir(app), "kill-events.jsonl"),
                        killEvent("terminate", "scheduled").toString() + "\n");
            } catch (Throwable ignored) { }
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=terminate");
            scheduleKill(250L);
            return;
        }
        if (busy) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("busy=true;state=" + state);
            return;
        }

        if (ACTION_PREPARE_SYNC_KILL.equals(action)) {
            startAsync(app, "preparing_sync_kill", "sync-kill-checkpoint.json",
                    () -> ProcessRecoveryTestEngine.prepareSyncKill(app),
                    "sync_kill_ready", true);
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=prepare_sync_kill");
            return;
        }
        if (ACTION_VERIFY_SYNC_RESTART.equals(action)) {
            startAsync(app, "verifying_sync_restart", "sync-after-restart.json",
                    () -> ProcessRecoveryTestEngine.verifySyncRestart(app),
                    "sync_restart_verified", false);
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=verify_sync_restart");
            return;
        }
        if (ACTION_RESUME_SYNC.equals(action)) {
            startAsync(app, "resuming_sync", "sync-result.json",
                    () -> ProcessRecoveryTestEngine.resumeSyncAndCleanup(app),
                    "sync_recovery_completed", false);
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=resume_sync");
        }
    }

    private static void startAsync(Context context,
                                   String startedState,
                                   String outputName,
                                   Work work,
                                   String successState,
                                   boolean killAfterSuccess) {
        busy = true;
        state = startedState;
        error = "";
        EXECUTOR.execute(() -> {
            try {
                JSONObject result = work.run();
                write(new File(resultDir(context), outputName), result.toString());
                if (!result.optBoolean("success", false)) {
                    throw new IllegalStateException("scenario_gate_failed");
                }
                state = successState;
                if (killAfterSuccess) {
                    JSONObject event = killEvent("sync", "partial_sync_ready");
                    append(new File(resultDir(context), "kill-events.jsonl"), event.toString() + "\n");
                    busy = false;
                    scheduleKill(100L);
                    return;
                }
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                state = "failed";
            } finally {
                if (!killAfterSuccess || !"sync_kill_ready".equals(state)) {
                    busy = false;
                    try { writeStatus(context); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private static void scheduleKill(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> Process.killProcess(Process.myPid()), delayMs);
    }

    private static JSONObject killEvent(String scenario, String phase) throws Exception {
        JSONObject result = new JSONObject();
        result.put("version", "V2.7.1");
        result.put("scenario", scenario);
        result.put("phase", phase);
        result.put("pid", Process.myPid());
        result.put("timestamp_ms", System.currentTimeMillis());
        return result;
    }

    private static String statusData(Context context) {
        JSONObject engine = ProcessRecoveryTestEngine.status(context);
        return "busy=" + busy
                + ";state=" + state
                + ";error=" + sanitize(error)
                + ";database_active=" + engine.optBoolean("database_active", false)
                + ";token_valid=" + engine.optBoolean("token_valid", false)
                + ";device_registered=" + engine.optBoolean("device_registered", false);
    }

    private static void writeStatus(Context context) throws Exception {
        JSONObject result = ProcessRecoveryTestEngine.status(context);
        result.put("busy", busy);
        result.put("state", state);
        result.put("error", error);
        write(new File(resultDir(context), "status.json"), result.toString());
    }

    private static File resultDir(Context context) {
        File dir = new File(context.getFilesDir(), "process-recovery-v271");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void write(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file, false);
        try {
            out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally { out.close(); }
    }

    private static void append(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally { out.close(); }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace(';', '_').replace('\n', ' ').replace('\r', ' ');
    }

    private interface Work { JSONObject run() throws Exception; }
}
