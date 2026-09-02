package com.punch.app.face;

import com.punch.app.model.Employee;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class FaceLibraryRuntimeState {
    private boolean ready;
    private String scope = "";
    private String employeeFingerprint = "";

    synchronized void markReady(String scope, String employeeFingerprint) {
        this.scope = safe(scope);
        this.employeeFingerprint = safe(employeeFingerprint);
        ready = true;
    }

    synchronized void invalidate() {
        ready = false;
        scope = "";
        employeeFingerprint = "";
    }

    synchronized boolean canReuse(String scope, String employeeFingerprint) {
        return ready
                && this.scope.equals(safe(scope))
                && this.employeeFingerprint.equals(safe(employeeFingerprint));
    }

    static String fingerprint(List<Employee> employees) {
        List<Employee> sorted = new ArrayList<>();
        if (employees != null) {
            for (Employee employee : employees) {
                if (employee != null) {
                    sorted.add(employee);
                }
            }
        }
        sorted.sort(Comparator.comparing(employee -> safe(employee.id)));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Employee employee : sorted) {
                update(digest, employee.id);
                update(digest, employee.faceImageUrl);
                update(digest, employee.faceImageSha256);
                update(digest, String.valueOf(employee.faceVersion));
                update(digest, employee.faceStatus);
                update(digest, employee.localFaceId);
                update(digest, String.valueOf(employee.faceRegistered));
                update(digest, String.valueOf(employee.isDeleted));
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = safe(value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
