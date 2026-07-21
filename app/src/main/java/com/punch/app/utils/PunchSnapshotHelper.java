package com.punch.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class PunchSnapshotHelper {
    private static final String TAG = "PunchSnapshotHelper";
    private static final String DIR_NAME = "punch_snapshots";
    private static final int JPEG_QUALITY = 75;
    private static final int MAX_DIMENSION = 960;

    private PunchSnapshotHelper() {
    }

    @Nullable
    public static Snapshot capture(Context context,
                                   String clientRecordId,
                                   byte[] nv21,
                                   int width,
                                   int height,
                                   int angle,
                                   int mirror) {
        return capture(context, clientRecordId, nv21, width, height, angle, mirror, null);
    }

    @Nullable
    public static Snapshot capture(Context context,
                                   String clientRecordId,
                                   byte[] nv21,
                                   int width,
                                   int height,
                                   int angle,
                                   int mirror,
                                   @Nullable RectF normalizedCropRect) {
        if (context == null || clientRecordId == null || clientRecordId.trim().isEmpty()
                || nv21 == null || width <= 0 || height <= 0) {
            return null;
        }

        File outputFile = new File(getSnapshotDirectory(context), clientRecordId + ".jpg");
        Bitmap bitmap = null;
        Bitmap transformed = null;
        Bitmap cropped = null;
        Bitmap scaled = null;
        try {
            YuvImage yuvImage = new YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream jpegStream = new ByteArrayOutputStream();
            if (!yuvImage.compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, jpegStream)) {
                return null;
            }

            byte[] jpegBytes = jpegStream.toByteArray();
            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (bitmap == null) {
                return null;
            }

            transformed = transformBitmap(bitmap, angle, mirror == 1);
            Bitmap outputBitmap = transformed;
            if (normalizedCropRect != null) {
                cropped = cropBitmap(transformed, normalizedCropRect);
                if (cropped == null) {
                    return null;
                }
                outputBitmap = cropped;
            }
            scaled = scaleBitmapIfNeeded(outputBitmap);
            if (scaled == null) {
                return null;
            }

            FileOutputStream outputStream = new FileOutputStream(outputFile);
            try {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)) {
                    return null;
                }
                outputStream.flush();
            } finally {
                outputStream.close();
            }

            return new Snapshot(
                    outputFile.getAbsolutePath(),
                    "image/jpeg",
                    scaled.getWidth(),
                    scaled.getHeight(),
                    outputFile.length(),
                    System.currentTimeMillis() / 1000L
            );
        } catch (IOException e) {
            AppLogger.w(TAG, "capture snapshot failed: " + e.getMessage());
            deleteSnapshot(outputFile.getAbsolutePath());
            return null;
        } finally {
            recycleDistinct(scaled);
            recycleDistinct(cropped, scaled);
            recycleDistinct(transformed, bitmap, cropped, scaled);
            recycleDistinct(bitmap, transformed, cropped, scaled);
        }
    }

    public static void deleteSnapshot(@Nullable String snapshotPath) {
        if (snapshotPath == null || snapshotPath.trim().isEmpty()) {
            return;
        }
        File file = new File(snapshotPath);
        if (file.exists() && !file.delete()) {
            AppLogger.w(TAG, "delete snapshot failed: " + snapshotPath);
        }
    }

    public static void clearSnapshots(Context context) {
        if (context == null) {
            return;
        }
        File dir = getSnapshotDirectory(context);
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && file.isFile() && !file.delete()) {
                AppLogger.w(TAG, "clear snapshot failed: " + file.getAbsolutePath());
            }
        }
    }

    private static File getSnapshotDirectory(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            AppLogger.w(TAG, "create snapshot dir failed: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static Bitmap transformBitmap(Bitmap bitmap, int angle, boolean mirror) {
        Matrix matrix = new Matrix();
        if (angle != 0) {
            matrix.postRotate(angle);
        }
        if (mirror) {
            matrix.postScale(-1f, 1f);
        }
        if (matrix.isIdentity()) {
            return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    @Nullable
    private static Bitmap cropBitmap(Bitmap bitmap, RectF normalizedCropRect) {
        if (bitmap == null || normalizedCropRect == null) {
            return bitmap;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = clamp(Math.round(normalizedCropRect.left * width), 0, width - 1);
        int top = clamp(Math.round(normalizedCropRect.top * height), 0, height - 1);
        int right = clamp(Math.round(normalizedCropRect.right * width), left + 1, width);
        int bottom = clamp(Math.round(normalizedCropRect.bottom * height), top + 1, height);
        if (left <= 0 && top <= 0 && right >= width && bottom >= height) {
            return bitmap;
        }
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
    }

    private static Bitmap scaleBitmapIfNeeded(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int maxDimension = Math.max(width, height);
        if (maxDimension <= MAX_DIMENSION) {
            return bitmap;
        }
        float scale = MAX_DIMENSION / (float) maxDimension;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void recycleDistinct(@Nullable Bitmap target, Bitmap... others) {
        if (target == null || target.isRecycled()) {
            return;
        }
        if (others != null) {
            for (Bitmap other : others) {
                if (target == other) {
                    return;
                }
            }
        }
        target.recycle();
    }

    public static final class Snapshot {
        public final String path;
        public final String mimeType;
        public final int width;
        public final int height;
        public final long sizeBytes;
        public final long capturedAtSeconds;

        public Snapshot(String path,
                        String mimeType,
                        int width,
                        int height,
                        long sizeBytes,
                        long capturedAtSeconds) {
            this.path = path;
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
            this.sizeBytes = sizeBytes;
            this.capturedAtSeconds = capturedAtSeconds;
        }
    }
}
