package com.punch.app.face;

public final class FaceFeatureCachePolicy {
    public static final int FEATURE_LENGTH = 512;

    private FaceFeatureCachePolicy() {
    }

    public static boolean isReusable(int cachedFaceVersion,
                                     String cachedSha256,
                                     int currentFaceVersion,
                                     String currentSha256,
                                     int cachedSchemaVersion,
                                     int currentSchemaVersion,
                                     byte[] feature) {
        if (feature == null || feature.length != FEATURE_LENGTH) {
            return false;
        }
        if (cachedFaceVersion != currentFaceVersion) {
            return false;
        }
        if (cachedSchemaVersion != currentSchemaVersion) {
            return false;
        }
        return normalize(cachedSha256).equals(normalize(currentSha256));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
