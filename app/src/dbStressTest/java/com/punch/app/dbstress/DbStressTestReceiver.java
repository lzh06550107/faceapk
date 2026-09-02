package com.punch.app.dbstress;

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

/** Exported only in dbStressTest; adb drives V2.6 phases through this receiver. */
public class DbStressTestReceiver extends BroadcastReceiver {
    public static final String ACTION_PREPARE = "com.punch.app.dbstress.PREPARE";
    public static final String ACTION_KILL = "com.punch.app.dbstress.KILL";
    public static final String ACTION_VERIFY_RESTART = "com.punch.app.dbstress.VERIFY_RESTART";
    public static final String ACTION_DRAIN = "com.punch.app.dbstress.DRAIN";
    public static final String ACTION_STATUS = "com.punch.app.dbstress.STATUS";
    public static final String ACTION_CLEANUP = "com.punch.app.dbstress.CLEANUP";
    public static final String ACTION_TERMINATE = "com.punch.app.dbstress.TERMINATE";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static volatile String state = "idle";
    private static volatile String error = "";
    private static volatile int count;

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
            DbStressTestEngine.cleanup(app);
            state = "cleaned";
            error = "";
            setResultCode(Activity.RESULT_OK);
            setResultData("cleaned=true");
            return;
        }
        if (ACTION_KILL.equals(action)) {
            state = "killing";
            error = "";
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=kill");
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> Process.killProcess(Process.myPid()), 500L);
            return;
        }
        if (ACTION_TERMINATE.equals(action)) {
            state = "terminating";
            error = "";
            try { DbStressTestEngine.cleanup(app); } catch (Throwable ignored) { }
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=terminate");
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> Process.killProcess(Process.myPid()), 250L);
            return;
        }
        if (busy) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("busy=true;state=" + state);
            return;
        }

        int requestedCount = intent.getIntExtra("count", count > 0 ? count : 1000);
        if (ACTION_PREPARE.equals(action)) {
            count = requestedCount;
            startAsync(app, "preparing", "prepare.json",
                    () -> DbStressTestEngine.prepare(app, requestedCount), "prepared");
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=prepare;count=" + requestedCount);
            return;
        }
        if (ACTION_VERIFY_RESTART.equals(action)) {
            count = requestedCount;
            startAsync(app, "verifying_restart", "restart.json",
                    () -> DbStressTestEngine.verifyRestart(app, requestedCount), "restart_verified");
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=verify_restart;count=" + requestedCount);
            return;
        }
        if (ACTION_DRAIN.equals(action)) {
            count = requestedCount;
            startAsync(app, "draining", "result.json",
                    () -> DbStressTestEngine.drainAndCleanup(app, requestedCount), "completed");
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=drain;count=" + requestedCount);
        }
    }

    private static void startAsync(Context context,
                                   String startedState,
                                   String outputName,
                                   Work work,
                                   String successState) {
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
        JSONObject engine = DbStressTestEngine.status(context);
        return "busy=" + busy
                + ";state=" + state
                + ";error=" + sanitize(error)
                + ";count=" + count
                + ";database_active=" + engine.optBoolean("database_active", false)
                + ";token_valid=" + engine.optBoolean("token_valid", false)
                + ";device_registered=" + engine.optBoolean("device_registered", false);
    }

    private static void writeStatus(Context context) throws Exception {
        JSONObject result = DbStressTestEngine.status(context);
        result.put("busy", busy);
        result.put("state", state);
        result.put("error", error);
        result.put("count", count);
        write(new File(resultDir(context), "status.json"), result.toString());
    }

    private static File resultDir(Context context) {
        File dir = new File(context.getFilesDir(), "db-stress-v26");
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

    private static String sanitize(String value) {
        return value == null ? "" : value.replace(';', '_').replace('\n', ' ').replace('\r', ' ');
    }

    private interface Work { JSONObject run() throws Exception; }
}
