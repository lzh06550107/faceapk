package com.punch.app.camerafacerecovery;

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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exported only in cameraFaceRecoveryTest; adb drives V2.7.3 through this receiver. */
public class CameraFaceRecoveryTestReceiver extends BroadcastReceiver {
    public static final String ACTION_STATUS = "com.punch.app.camerafacerecovery.STATUS";
    public static final String ACTION_PREPARE = "com.punch.app.camerafacerecovery.PREPARE";
    public static final String ACTION_RECOGNIZE = "com.punch.app.camerafacerecovery.RECOGNIZE";
    public static final String ACTION_KILL = "com.punch.app.camerafacerecovery.KILL";
    public static final String ACTION_CLEANUP = "com.punch.app.camerafacerecovery.CLEANUP";
    public static final String ACTION_TERMINATE = "com.punch.app.camerafacerecovery.TERMINATE";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static volatile String state = "idle";
    private static volatile String error = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Context app = context.getApplicationContext();
        int cycle = Math.max(1, intent.getIntExtra("cycle", 1));

        if (ACTION_STATUS.equals(action)) {
            setResultCode(Activity.RESULT_OK);
            setResultData(statusData(app));
            return;
        }
        if (ACTION_CLEANUP.equals(action)) {
            CameraFaceRecoveryEngine.cleanup(app);
            state = "cleaned";
            error = "";
            setResultCode(Activity.RESULT_OK);
            setResultData("cleaned=true");
            return;
        }
        if (ACTION_TERMINATE.equals(action)) {
            state = "terminating";
            error = "";
            try { CameraFaceRecoveryEngine.cleanup(app); } catch (Throwable ignored) { }
            try { append(killEvents(app), killEvent(cycle, "terminate").toString() + "\n"); } catch (Throwable ignored) { }
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=terminate");
            scheduleKill(250L);
            return;
        }
        if (ACTION_KILL.equals(action)) {
            state = "kill_scheduled";
            error = "";
            try {
                JSONObject event = killEvent(cycle, "camera_face_active");
                write(new File(resultDir(app), cycleName(cycle, "kill.json")), event.toString());
                append(killEvents(app), event.toString() + "\n");
            } catch (Throwable ignored) { }
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=kill;cycle=" + cycle);
            scheduleKill(250L);
            return;
        }
        if (busy) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("busy=true;state=" + state);
            return;
        }
        if (ACTION_PREPARE.equals(action)) {
            busy = true;
            state = "preparing";
            error = "";
            EXECUTOR.execute(() -> {
                try {
                    JSONObject result = CameraFaceRecoveryEngine.prepare(app);
                    write(new File(resultDir(app), "prepare-result.json"), result.toString());
                    JSONObject health = result.optJSONObject("health");
                    JSONObject recognition = result.optJSONObject("recognition");
                    if (health != null) write(new File(resultDir(app), "pre-kill-health.json"), health.toString());
                    if (recognition != null) write(new File(resultDir(app), "pre-kill-recognition.json"), recognition.toString());
                    if (!result.optBoolean("success", false)) throw new IllegalStateException("prepare_gate_failed");
                    state = "prepared";
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                    state = "failed";
                } finally {
                    busy = false;
                }
            });
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=prepare");
            return;
        }
        if (ACTION_RECOGNIZE.equals(action)) {
            busy = true;
            state = "recognizing";
            error = "";
            EXECUTOR.execute(() -> {
                try {
                    JSONObject result = CameraFaceRecoveryEngine.recognize(app);
                    write(new File(resultDir(app), cycleName(cycle, "recognition.json")), result.toString());
                    if (!result.optBoolean("success", false)) throw new IllegalStateException("recognition_gate_failed");
                    state = "recognition_ready";
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                    state = "failed";
                } finally {
                    busy = false;
                }
            });
            setResultCode(Activity.RESULT_OK);
            setResultData("accepted=true;phase=recognize;cycle=" + cycle);
        }
    }

    private static String statusData(Context context) {
        JSONObject health = CameraFaceRecoveryEngine.health(context);
        return "busy=" + busy
                + ";state=" + state
                + ";error=" + sanitize(error)
                + ";database_active=" + health.optBoolean("database_active", false)
                + ";token_valid=" + health.optBoolean("token_valid", false)
                + ";device_registered=" + health.optBoolean("device_registered", false)
                + ";face_initialized=" + health.optBoolean("face_initialized", false)
                + ";loaded_face_count=" + health.optInt("loaded_face_count", 0)
                + ";punch_ready=" + health.optBoolean("punch_ready", false)
                + ";punch_preparing=" + health.optBoolean("punch_preparing", false)
                + ";fixture_employee=" + health.optBoolean("fixture_employee", false)
                + ";fixture_image=" + health.optBoolean("fixture_image", false);
    }

    private static void scheduleKill(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> Process.killProcess(Process.myPid()), delayMs);
    }

    private static JSONObject killEvent(int cycle, String phase) throws Exception {
        JSONObject result = new JSONObject();
        result.put("version", "V2.7.3");
        result.put("cycle", cycle);
        result.put("phase", phase);
        result.put("pid", Process.myPid());
        result.put("timestamp_ms", System.currentTimeMillis());
        return result;
    }

    private static String cycleName(int cycle, String suffix) {
        return String.format(Locale.US, "cycle-%02d-%s", cycle, suffix);
    }

    private static File resultDir(Context context) {
        File dir = new File(context.getFilesDir(), "camera-face-recovery-v273");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File killEvents(Context context) {
        return new File(resultDir(context), "kill-events.jsonl");
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
}
