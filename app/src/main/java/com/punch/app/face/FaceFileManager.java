package com.punch.app.face;

import android.content.Context;
import android.util.Log;

import com.punch.app.network.ApiService;
import com.punch.app.utils.SessionManager;

import java.io.File;
import java.io.FileInputStream;
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

    
    public static DownloadResult downloadAndVerify(Context ctx, String empId,
                                                   String imageUrl, String expectedSha256) {
        File dir = ensureFacesDir(ctx);
        File dest = new File(dir, empId + ".jpg");
        String safeUrl = imageUrl == null ? "" : imageUrl.trim();
        if (safeUrl.isEmpty()) {
            Log.w(TAG, "Face image url is empty for " + empId);
            return DownloadResult.fail("人脸图片URL为空");
        }

        if (dest.exists() && expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
            String existing = sha256(dest);
            if (expectedSha256.trim().equalsIgnoreCase(existing)) {
                return DownloadResult.ok(dest.getAbsolutePath());
            }
        }

        String resolvedUrl = resolveImageUrl(safeUrl);
        boolean downloaded;
        try {
            downloaded = ApiService.downloadToFile(resolvedUrl, dest);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid face image url: empId=" + empId + " url=" + resolvedUrl, e);
            return DownloadResult.fail("人脸图片URL无效");
        } catch (RuntimeException e) {
            Log.e(TAG, "Face image download error: empId=" + empId + " url=" + resolvedUrl, e);
            return DownloadResult.fail("人脸图片下载异常");
        }
        if (!downloaded) {
            Log.w(TAG, "Face image download failed: empId=" + empId + " url=" + resolvedUrl);
            return DownloadResult.fail("人脸图片下载失败");
        }

        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String actual = sha256(dest);
            if (!expectedSha256.trim().equalsIgnoreCase(actual)) {
                Log.w(TAG, "Face image SHA256 mismatch: empId=" + empId
                        + " expected=" + expectedSha256.trim()
                        + " actual=" + actual);
                dest.delete();
                return DownloadResult.fail("人脸图片校验失败");
            }
        }
        return DownloadResult.ok(dest.getAbsolutePath());
    }

    private static String resolveImageUrl(String source) {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return source;
        }
        String baseUrl = SessionManager.get().getBaseUrl();
        if (source.startsWith("/")) {
            return baseUrl + source;
        }
        return baseUrl + "/" + source;
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

    public static final class DownloadResult {
        public final boolean success;
        public final String path;
        public final String failMsg;

        private DownloadResult(boolean success, String path, String failMsg) {
            this.success = success;
            this.path = path;
            this.failMsg = failMsg == null ? "" : failMsg;
        }

        static DownloadResult ok(String path) {
            return new DownloadResult(true, path, "");
        }

        static DownloadResult fail(String failMsg) {
            return new DownloadResult(false, null, failMsg);
        }
    }
}
