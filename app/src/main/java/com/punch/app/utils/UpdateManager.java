package com.punch.app.utils;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;

import com.punch.app.network.InteractionLogger;
import com.punch.app.receiver.UpdateInstallStateReceiver;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public final class UpdateManager {
    public static final String ACTION_PACKAGE_INSTALL_RESULT =
            "com.punch.app.action.PACKAGE_INSTALL_RESULT";
    private static final String EXTRA_INSTALL_RESULT =
            "android.intent.extra.INSTALL_RESULT";
    private static final int REQ_INSTALL_RESULT = 1001;
    private static final int BUFFER_SIZE = 64 * 1024;

    private UpdateManager() {
    }

    public static StartResult startInstall(
            Activity activity,
            Uri apkUri,
            String apkPath,
            String fallbackTargetVersion,
            ActivityResultLauncher<Intent> systemInstallerLauncher
    ) {
        if (activity == null) {
            return StartResult.fail("Install context unavailable");
        }
        if (apkUri == null) {
            return StartResult.fail("Update APK uri is empty");
        }

        ApkInstallValidator.Result validation =
                ApkInstallValidator.validateUpdateApk(activity, apkPath);
        if (!validation.success) { // TODO
            markFailed(validation.message, Integer.MIN_VALUE);
            logFailure("Update APK validation failed",
                    validation.message + "\napkPath=" + safe(apkPath));
            return StartResult.fail(validation.message);
        }

        String targetVersion = !TextUtils.isEmpty(validation.versionName)
                ? validation.versionName
                : safe(fallbackTargetVersion);
        SessionManager.get().markUpdateInstallStarted(
                apkPath,
                targetVersion,
                validation.versionCode
        );
        log("Update APK validation passed",
                "package=" + validation.packageName
                        + "\nversionName=" + targetVersion
                        + "\nversionCode=" + validation.versionCode
                        + "\nsize=" + validation.fileSize
                        + "\napkPath=" + safe(apkPath));

        if (KioskManager.isDeviceOwner(activity)) {
            if (SessionManager.get().isKioskEnabled()) {
                KioskManager.ensureOwnerKioskPolicies(activity);
            }
            return installWithPackageInstaller(activity, apkPath, validation);
        }
        return launchSystemInstaller(activity, apkUri, systemInstallerLauncher);
    }

    public static void handleSystemInstallerResult(
            Context context,
            int resultCode,
            @Nullable Intent data
    ) {
        String detail = buildInstallerResultDetail(resultCode, data);
        if (isInstalledVersionAtTarget(context)) {
            String version = resolveInstalledVersionName(context);
            SessionManager.get().markUpdateInstallResult(
                    Constants.UPDATE_INSTALL_STATUS_SUCCESS,
                    "Updated to " + safe(version),
                    resultCode
            );
            log("System installer reported success", detail + "\nversion=" + safe(version));
            return;
        }

        int installResult = extractInstallResult(data, resultCode);
        String message = mapInstallResult(installResult);
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_FAILED,
                message,
                installResult
        );
        logFailure("System installer reported failure", detail + "\nmessage=" + message);
    }

    public static void handlePackageInstallerResult(Context context, Intent intent) {
        int status = intent == null
                ? PackageInstaller.STATUS_FAILURE
                : intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String statusMessage = intent == null
                ? ""
                : intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        int sessionId = intent == null
                ? -1
                : intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);

        if (status == PackageInstaller.STATUS_SUCCESS || isInstalledVersionAtTarget(context)) {
            String version = resolveInstalledVersionName(context);
            SessionManager.get().markUpdateInstallResult(
                    Constants.UPDATE_INSTALL_STATUS_SUCCESS,
                    "Updated to " + safe(version),
                    status
            );
            log("Device Owner install succeeded",
                    "sessionId=" + sessionId + "\nversion=" + safe(version));
            UpdateInstallStateReceiver.scheduleUpdatedAppLaunch(context, version);
            return;
        }

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            SessionManager.get().markUpdateInstallResult(
                    Constants.UPDATE_INSTALL_STATUS_FAILED,
                    "Installer requires user action",
                    status
            );
            logFailure("Device Owner install requires user action",
                    "sessionId=" + sessionId + "\nmessage=" + safe(statusMessage));
            return;
        }

        String message = "Install failed: " + safe(statusMessage);
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_FAILED,
                message,
                status
        );
        logFailure("Device Owner install failed",
                "sessionId=" + sessionId
                        + "\nstatus=" + status
                        + "\nmessage=" + safe(statusMessage));
    }

    public static void reconcileInstallState(Context context) {
        if (!SessionManager.get().isUpdateInstallPending()) {
            return;
        }
        if (!isInstalledVersionAtTarget(context)) {
            return;
        }
        String version = resolveInstalledVersionName(context);
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_SUCCESS,
                "Updated to " + safe(version),
                0
        );
        log("Install state reconciled by version check",
                "version=" + safe(version)
                        + "\ntargetVersionCode="
                        + SessionManager.get().getUpdateInstallTargetVersionCode());
    }

    public static boolean isInstalledVersionAtTarget(Context context) {
        long targetCode = SessionManager.get().getUpdateInstallTargetVersionCode();
        if (targetCode > 0L) {
            return ApkInstallValidator.getInstalledVersionCode(context) >= targetCode;
        }
        String targetVersion = SessionManager.get().getUpdateInstallTargetVersion();
        return compareVersions(resolveInstalledVersionName(context), targetVersion) >= 0;
    }

    private static StartResult installWithPackageInstaller(
            Context context,
            String apkPath,
            ApkInstallValidator.Result validation
    ) {
        PackageInstaller.Session session = null;
        int sessionId = -1;
        try {
            File apkFile = new File(apkPath);
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (FileInputStream input = new FileInputStream(apkFile);
                 OutputStream output = session.openWrite("base.apk", 0, apkFile.length())) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = input.read(buffer)) != -1) {
                    output.write(buffer, 0, len);
                }
                session.fsync(output);
            }

            Intent callbackIntent = new Intent(context, UpdateInstallStateReceiver.class);
            callbackIntent.setAction(ACTION_PACKAGE_INSTALL_RESULT);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQ_INSTALL_RESULT,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | mutableFlag()
            );
            UpdateInstallStateReceiver.scheduleUpdatedAppLaunch(context, validation.versionName);
            session.commit(pendingIntent.getIntentSender());
            log("Device Owner install session committed",
                    "sessionId=" + sessionId
                            + "\nversionName=" + safe(validation.versionName)
                            + "\nversionCode=" + validation.versionCode);
            return StartResult.ok("Background install submitted", true);
        } catch (Exception e) {
            if (sessionId >= 0) {
                try {
                    context.getPackageManager().getPackageInstaller().abandonSession(sessionId);
                } catch (Exception ignored) {
                }
            }
            String message = "Install submit failed: " + e.getClass().getSimpleName()
                    + ": " + safe(e.getMessage());
            markFailed(message, Integer.MIN_VALUE);
            logFailure("Device Owner install submit failed", message);
            return StartResult.fail(message);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private static StartResult launchSystemInstaller(
            Context context,
            Uri apkUri,
            ActivityResultLauncher<Intent> launcher
    ) {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(apkUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            log("System installer launched", "uri=" + apkUri);
            launcher.launch(intent);
            return StartResult.ok("Installer launched", false);
        } catch (Exception e) {
            String message = "Unable to launch installer: " + safe(e.getMessage());
            markFailed(message, Integer.MIN_VALUE);
            logFailure("System installer launch failed", message);
            return StartResult.fail(message);
        }
    }

    private static int mutableFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return PendingIntent.FLAG_MUTABLE;
        }
        return 0;
    }

    private static void markFailed(String message, int resultCode) {
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_FAILED,
                message,
                resultCode
        );
    }

    private static int extractInstallResult(@Nullable Intent data, int fallback) {
        if (data == null || data.getExtras() == null) {
            return fallback;
        }
        return data.getExtras().getInt(EXTRA_INSTALL_RESULT, fallback);
    }

    private static String mapInstallResult(int result) {
        switch (result) {
            case -1:
                return "Install failed: already exists";
            case -2:
                return "Install failed: invalid APK";
            case -3:
                return "Install failed: invalid URI";
            case -4:
                return "Install failed: insufficient storage";
            case -5:
                return "Install failed: duplicate package";
            case -7:
                return "Install failed: update signature is incompatible";
            case -25:
                return "Install failed: version downgrade";
            default:
                return "Install cancelled or failed: result=" + result;
        }
    }

    private static String buildInstallerResultDetail(int resultCode, @Nullable Intent data) {
        StringBuilder builder = new StringBuilder()
                .append("resultCode=").append(resultCode);
        if (data != null && data.getExtras() != null && !data.getExtras().isEmpty()) {
            builder.append("\nextras=").append(data.getExtras());
        }
        return builder.toString();
    }

    private static String resolveInstalledVersionName(Context context) {
        if (context == null) {
            return "";
        }
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left);
        String[] rightParts = normalizeVersion(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftValue = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static String[] normalizeVersion(String version) {
        if (version == null || version.trim().isEmpty()) {
            return new String[0];
        }
        return version.trim().split("\\.");
    }

    private static int parseVersionPart(String part) {
        if (part == null || part.isEmpty()) {
            return 0;
        }
        String digits = part.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void log(String title, String detail) {
        InteractionLogger.logBusiness(InteractionLogger.GROUP_UPDATE, title, detail);
    }

    private static void logFailure(String title, String detail) {
        InteractionLogger.logBusinessFailure(InteractionLogger.GROUP_UPDATE, title, detail);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class StartResult {
        public final boolean success;
        public final String message;
        public final boolean deviceOwnerInstall;

        private StartResult(boolean success, String message, boolean deviceOwnerInstall) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.deviceOwnerInstall = deviceOwnerInstall;
        }

        public static StartResult ok(String message, boolean deviceOwnerInstall) {
            return new StartResult(true, message, deviceOwnerInstall);
        }

        public static StartResult fail(String message) {
            return new StartResult(false, message, false);
        }
    }
}
