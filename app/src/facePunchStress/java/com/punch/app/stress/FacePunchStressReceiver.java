package com.punch.app.stress;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.punch.app.PunchApplication;
import com.punch.app.face.FaceManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FacePunchStressReceiver extends BroadcastReceiver {
    public static final String ACTION_RUN = "com.punch.app.stress.RUN";
    public static final String ACTION_STATUS = "com.punch.app.stress.STATUS";
    public static final String ACTION_CLEANUP = "com.punch.app.stress.CLEANUP";
    public static final String MODE_FACE = "Face";
    public static final String MODE_PUNCH = "Punch";
    public static final String MODE_ALL = "All";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static volatile String state = "idle";
    private static volatile String error = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_STATUS.equals(action)) {
            setResultCode(Activity.RESULT_OK);
            setResultData(statusData(context));
            return;
        }
        if (ACTION_CLEANUP.equals(action)) {
            PunchStressEngine.cleanup(context.getApplicationContext());
            setResultCode(Activity.RESULT_OK);
            setResultData("cleaned=true");
            return;
        }
        if (!ACTION_RUN.equals(action)) return;
        if (busy) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("busy=true");
            return;
        }
        final Context app = context.getApplicationContext();
        final String mode = intent.getStringExtra("mode") == null ? MODE_ALL : intent.getStringExtra("mode");
        final int count = Math.max(1, intent.getIntExtra("count", 100));
        busy = true;
        state = "running";
        error = "";
        EXECUTOR.execute(() -> {
            try {
                File dir = resultDir(app);
                if (MODE_FACE.equalsIgnoreCase(mode) || MODE_ALL.equalsIgnoreCase(mode)) {
                    StressResult face = FaceStressEngine.run(app, count);
                    write(new File(dir, "face-result.json"), face.toJson().toString());
                    if (!face.error.isEmpty()) throw new IllegalStateException(face.error);
                }
                if (MODE_PUNCH.equalsIgnoreCase(mode) || MODE_ALL.equalsIgnoreCase(mode)) {
                    StressResult punch = PunchStressEngine.run(app, count);
                    write(new File(dir, "punch-result.json"), punch.toJson().toString());
                    if (!punch.error.isEmpty()) throw new IllegalStateException(punch.error);
                }
                state = "completed";
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                state = "failed";
            } finally {
                busy = false;
                try { writeStatus(app); } catch (Throwable ignored) {}
            }
        });
        setResultCode(Activity.RESULT_OK);
        setResultData("accepted=true;mode=" + mode + ";count=" + count);
    }

    private static String statusData(Context context) {
        PunchApplication app = PunchApplication.get();
        boolean punchDataPreparing = app != null && app.isPunchDataPreparing();
        boolean punchDataReady = app != null && app.isPunchRecognitionReady();
        return "busy=" + busy + ";state=" + state + ";error=" + error
                + ";face_initialized=" + FaceManager.get().isInitialized()
                + ";loaded_face_count=" + FaceManager.get().getLoadedFaceCount()
                + ";punch_data_preparing=" + punchDataPreparing
                + ";punch_data_ready=" + punchDataReady;
    }

    private static File resultDir(Context context) {
        File dir = new File(context.getFilesDir(), "face_punch_stress");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void writeStatus(Context context) throws Exception {
        JSONObject o = new JSONObject();
        o.put("busy", busy);
        o.put("state", state);
        o.put("error", error);
        o.put("face_initialized", FaceManager.get().isInitialized());
        o.put("loaded_face_count", FaceManager.get().getLoadedFaceCount());
        PunchApplication app = PunchApplication.get();
        o.put("punch_data_preparing", app != null && app.isPunchDataPreparing());
        o.put("punch_data_ready", app != null && app.isPunchRecognitionReady());
        write(new File(resultDir(context), "status.json"), o.toString());
    }

    private static void write(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file, false);
        try { out.write(text.getBytes(StandardCharsets.UTF_8)); out.flush(); }
        finally { out.close(); }
    }
}
