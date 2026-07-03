package com.punch.app.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.punch.app.utils.SessionManager;

public class SyncService extends Service {
    public static final String ACTION_SYNC_NOW = "com.punch.app.SYNC_NOW";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SYNC_NOW.equals(intent.getAction())) {
            HeartbeatManager.get(getApplicationContext()).triggerNow();
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void triggerSync(Context context) {
        if (!SessionManager.get().isTokenValid()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        HeartbeatManager.get(appContext).start();
        Intent intent = new Intent(appContext, SyncService.class);
        intent.setAction(ACTION_SYNC_NOW);
        appContext.startService(intent);
    }
}
