package com.punch.app.utils;

import android.util.Log;


public class AppLogger {
    private static final boolean DEBUG = true;

    
    public static void d(String tag, String msg) {
        if (DEBUG) {
            Log.d(tag, msg);
        }
    }

    
    public static void i(String tag, String msg) {
        Log.i(tag, msg);
    }

    
    public static void w(String tag, String msg) {
        Log.w(tag, msg);
    }

    
    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    
    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
    }
}
