package com.punch.app.activation;

import android.content.Context;
import android.os.Build;

import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public final class DeviceInfoProvider {
    private DeviceInfoProvider() {
    }

    public static Map<String, Object> buildRegisterPayload(Context context) {
        Map<String, Object> body = new HashMap<>();
        body.put("company_id", SessionManager.get().getCompanyId());
        body.put("device_name", buildDeviceName());
        body.put("device_id", SessionManager.get().getDeviceId());
        body.put("ip", getLocalIpAddress());
        body.put("software_version", getSoftwareVersion(context));
        return body;
    }

    public static Map<String, Object> buildHeartbeatPayload(Context context) {
        Map<String, Object> body = new HashMap<>();
        body.put("device_id", SessionManager.get().getDeviceId());
        body.put("software_version", getSoftwareVersion(context));
        body.put("ip", getLocalIpAddress());
        return body;
    }

    private static String buildDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String name = (manufacturer + " " + model).trim();
        return name.isEmpty() ? "PDA" : name;
    }

    private static String getSoftwareVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    private static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
