package com.punch.app.utils;

import android.util.Log;


public class AppLogger {
    private static final boolean DEBUG = true;

    public static void d(String tag, String msg) {
        if (!DEBUG) {
            return;
        }
        try {
            Log.d(tag, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void i(String tag, String msg) {
        try {
            Log.i(tag, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void w(String tag, String msg) {
        try {
            Log.w(tag, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String tag, String msg) {
        try {
            Log.e(tag, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String tag, String msg, Throwable t) {
        try {
            Log.e(tag, msg, t);
        } catch (Throwable ignored) {
        }
    }
}
