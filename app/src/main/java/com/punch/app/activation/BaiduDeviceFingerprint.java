package com.punch.app.activation;

import android.content.Context;
import android.text.TextUtils;
import android.util.Pair;

import com.baidu.liantian.ac.LH;


public final class BaiduDeviceFingerprint {

    private static final String ID_FLAG = "1";
    private static volatile String cachedFingerprint = "";

    private BaiduDeviceFingerprint() {
    }

    public static String get(Context context) {
        if (!TextUtils.isEmpty(cachedFingerprint)) {
            return cachedFingerprint;
        }
        if (context == null) {
            return "";
        }
        try {
            Context appContext = context.getApplicationContext();
            LH.init(appContext, false);
            Pair<String, String> deviceId = LH.getId(appContext, ID_FLAG);
            if (deviceId != null && !TextUtils.isEmpty(deviceId.second)) {
                cachedFingerprint = deviceId.second.toUpperCase();
            }
        } catch (Throwable ignored) {
            cachedFingerprint = "";
        }
        return cachedFingerprint;
    }
}
