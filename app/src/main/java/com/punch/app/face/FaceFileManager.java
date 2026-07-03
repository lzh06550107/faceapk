package com.punch.app.face;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;


public class FaceFileManager {
    private static final String TAG = "FaceFileManager";
    private static final String FACES_DIR = "faces";

    
    public static File ensureFacesDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), FACES_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    
    public static String getFaceImagePath(Context ctx, String empId) {
        File dir = ensureFacesDir(ctx);
        return new File(dir, empId + ".jpg").getAbsolutePath();
    }

    
    public static void deleteFaceImage(Context ctx, String empId) {
        File file = new File(getFaceImagePath(ctx, empId));
        if (file.exists()) {
            file.delete();
        }
    }

    
    public static void clearAllFaceImages(Context ctx) {
        File dir = ensureFacesDir(ctx);
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile()) {
                file.delete();
            }
        }
    }

    
    public static String downloadAndVerify(Context ctx, String empId,
                                           String imageUrl, String expectedSha256) {
        File dir = ensureFacesDir(ctx);
        File dest = new File(dir, empId + ".jpg");

        if (dest.exists() && expectedSha256 != null) {
            String existing = sha256(dest);
            if (expectedSha256.equalsIgnoreCase(existing)) {
                return dest.getAbsolutePath();
            }
        }

        try {
            download(imageUrl, dest);
        } catch (IOException e) {
            Log.e(TAG, "Download failed: " + imageUrl, e);
            return null;
        }

        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String actual = sha256(dest);
            if (!expectedSha256.equalsIgnoreCase(actual)) {
                Log.w(TAG, "SHA256 mismatch for " + empId);
                dest.delete();
                return null;
            }
        }
        return dest.getAbsolutePath();
    }

    
    private static void download(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.connect();
        if (conn.getResponseCode() != 200) {
            throw new IOException("HTTP " + conn.getResponseCode());
        }
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }

    
    private static String sha256(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}