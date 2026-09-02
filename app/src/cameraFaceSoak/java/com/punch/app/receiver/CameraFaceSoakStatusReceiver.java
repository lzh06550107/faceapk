package com.punch.app.receiver;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.punch.app.face.FaceManager;

/**
 * Test-only runtime status endpoint for the cameraFaceSoak build.
 * This class is absent from release/debug/smoke variants.
 */
public class CameraFaceSoakStatusReceiver extends BroadcastReceiver {
    public static final String ACTION_STATUS = "com.punch.app.action.CAMERA_FACE_SOAK_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean initialized = FaceManager.get().isInitialized();
        int loadedFaceCount = FaceManager.get().getLoadedFaceCount();
        setResultCode(Activity.RESULT_OK);
        setResultData(
                "face_initialized=" + initialized
                        + ";loaded_face_count=" + loadedFaceCount
        );
    }
}
