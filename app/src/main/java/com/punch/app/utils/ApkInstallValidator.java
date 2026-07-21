package com.punch.app.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public final class ApkInstallValidator {
    private ApkInstallValidator() {
    }

    public static Result validateUpdateApk(Context context, String apkPath) {
        if (context == null) {
            return Result.fail("Context unavailable");
        }
        if (isBlank(apkPath)) {
            return Result.fail("Update APK path is empty");
        }

        File apkFile = new File(apkPath);
        if (!apkFile.exists() || !apkFile.isFile() || apkFile.length() <= 0L) {
            return Result.fail("Update APK missing or empty: " + apkPath);
        }

        PackageManager pm = context.getPackageManager();
        PackageInfo archiveInfo = getArchivePackageInfo(pm, apkPath);
        if (archiveInfo == null) {
            return Result.fail("Update file is not a valid APK");
        }

        String currentPackageName = context.getPackageName();
        if (!currentPackageName.equals(archiveInfo.packageName)) {
            return Result.fail("Package name mismatch: " + safe(archiveInfo.packageName));
        }

        PackageInfo currentInfo;
        try {
            currentInfo = getInstalledPackageInfo(pm, currentPackageName);
        } catch (Exception e) {
            return Result.fail("Failed to read installed package info: " + safe(e.getMessage()));
        }

        long currentVersionCode = getLongVersionCode(currentInfo);
        long archiveVersionCode = getLongVersionCode(archiveInfo);
        if (archiveVersionCode <= currentVersionCode) {
            return Result.fail("Version code is not newer: current="
                    + currentVersionCode + ", target=" + archiveVersionCode);
        }

        Set<String> currentSignatures = signatureDigests(currentInfo);
        Set<String> archiveSignatures = signatureDigests(archiveInfo);
        if (currentSignatures.isEmpty() || archiveSignatures.isEmpty()) {
            return Result.fail("Unable to read APK signature");
        }
        if (!currentSignatures.equals(archiveSignatures)) {
            return Result.fail("APK signature mismatch");
        }

        return Result.ok(
                archiveInfo.packageName,
                safe(archiveInfo.versionName),
                archiveVersionCode,
                apkFile.length()
        );
    }

    private static PackageInfo getArchivePackageInfo(PackageManager pm, String apkPath) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        return pm.getPackageArchiveInfo(apkPath, flags);
    }

    private static PackageInfo getInstalledPackageInfo(PackageManager pm, String packageName)
            throws PackageManager.NameNotFoundException {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        return pm.getPackageInfo(packageName, flags);
    }

    public static long getInstalledVersionCode(Context context) {
        if (context == null) {
            return 0L;
        }
        try {
            return getLongVersionCode(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long getLongVersionCode(PackageInfo info) {
        if (info == null) {
            return 0L;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private static Set<String> signatureDigests(PackageInfo info) {
        Signature[] signatures = extractSignatures(info);
        Set<String> digests = new HashSet<>();
        if (signatures == null) {
            return digests;
        }
        for (Signature signature : signatures) {
            String digest = sha256(signature.toByteArray());
            if (!isBlank(digest)) {
                digests.add(digest);
            }
        }
        return digests;
    }

    private static Signature[] extractSignatures(PackageInfo info) {
        if (info == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            if (info.signingInfo.hasMultipleSigners()) {
                return info.signingInfo.getApkContentsSigners();
            }
            return info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures;
    }

    private static String sha256(byte[] data) {
        if (data == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final long fileSize;

        private Result(boolean success, String message, String packageName,
                       String versionName, long versionCode, long fileSize) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.packageName = packageName == null ? "" : packageName;
            this.versionName = versionName == null ? "" : versionName;
            this.versionCode = versionCode;
            this.fileSize = fileSize;
        }

        public static Result ok(String packageName, String versionName,
                                long versionCode, long fileSize) {
            return new Result(true, "", packageName, versionName, versionCode, fileSize);
        }

        public static Result fail(String message) {
            return new Result(false, message, "", "", 0L, 0L);
        }
    }
}
