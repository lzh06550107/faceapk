package com.punch.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SessionManagerTest {
    @Before
    public void setUp() throws Exception {
        setSessionPreferences(new MemorySharedPreferences(), new MemorySharedPreferences());
        setAppContext(null);
        SessionManager.setResolvedDeviceIdForTest(null);
        SessionManager.get().clearAll();
    }

    @After
    public void tearDown() throws Exception {
        SessionManager.setResolvedDeviceIdForTest(null);
        setAppContext(null);
        SessionManager.get().clearAll();
    }

    @Test
    public void saveTeamBindingName_shouldRoundTrip() {
        SessionManager.get().saveTeamBindingId(2);
        SessionManager.get().saveTeamBindingName("Team B");

        assertEquals(2, SessionManager.get().getTeamBindingId());
        assertEquals("Team B", SessionManager.get().getTeamBindingName());
    }

    @Test
    public void companyId_shouldDefaultAndRoundTrip() {
        assertEquals(Constants.DEFAULT_COMPANY_ID, SessionManager.get().getCompanyId());

        SessionManager.get().saveCompanyId(18);
        assertEquals(18, SessionManager.get().getCompanyId());

        SessionManager.get().saveCompanyId(0);
        assertEquals(Constants.DEFAULT_COMPANY_ID, SessionManager.get().getCompanyId());
    }

    @Test
    public void baseUrl_shouldDefaultAndRoundTrip() {
        assertEquals(Constants.DEFAULT_BASE_URL, SessionManager.get().getBaseUrl());

        SessionManager.get().saveBaseUrl("https://example.com/");

        assertEquals("https://example.com", SessionManager.get().getBaseUrl());
    }

    @Test
    public void baseUrl_shouldFallbackWhenInvalid() {
        SessionManager.get().saveBaseUrl("not-a-url");

        assertEquals(Constants.DEFAULT_BASE_URL, SessionManager.get().getBaseUrl());
    }

    @Test
    public void kioskEnabled_shouldDefaultToTrueAndRoundTrip() {
        assertTrue(SessionManager.get().isKioskEnabled());

        SessionManager.get().saveKioskEnabled(false);
        assertFalse(SessionManager.get().isKioskEnabled());

        SessionManager.get().saveKioskEnabled(true);
        assertTrue(SessionManager.get().isKioskEnabled());
    }

    @Test
    public void advancedSettingsPassword_shouldDefaultAndRoundTrip() {
        assertEquals(Constants.ADVANCED_SETTINGS_PASSWORD, SessionManager.get().getAdvancedSettingsPassword());

        SessionManager.get().saveAdvancedSettingsPassword("5566");
        assertEquals("5566", SessionManager.get().getAdvancedSettingsPassword());

        SessionManager.get().saveAdvancedSettingsPassword("");
        assertEquals(Constants.ADVANCED_SETTINGS_PASSWORD, SessionManager.get().getAdvancedSettingsPassword());
    }

    @Test
    public void checkCount_shouldDefaultToZeroAndRoundTrip() {
        assertEquals(0, SessionManager.get().getCheckCount());

        SessionManager.get().saveCheckCount(12);
        assertEquals(12, SessionManager.get().getCheckCount());

        SessionManager.get().saveCheckCount(-1);
        assertEquals(0, SessionManager.get().getCheckCount());
    }

    @Test
    public void saveUpdateInfo_shouldRoundTrip() {
        SessionManager.get().saveUpdateInfo(true, "https://example.com/app.apk", "1.0.0", "2.0.0", "release");

        assertTrue(SessionManager.get().isUpdateNeeded());
        assertEquals("https://example.com/app.apk", SessionManager.get().getUpdateApkUrl());
        assertEquals("1.0.0", SessionManager.get().getUpdateCurrentVersion());
        assertEquals("2.0.0", SessionManager.get().getUpdateTargetVersion());
        assertEquals("release", SessionManager.get().getUpdateVersionName());
    }

    @Test
    public void saveUpdateInfo_shouldAllowClearing() {
        SessionManager.get().saveUpdateInfo(true, "https://example.com/app.apk", "1.0.0", "2.0.0", "release");
        SessionManager.get().saveUpdateInfo(false, "", "", "", "");

        assertFalse(SessionManager.get().isUpdateNeeded());
        assertEquals("", SessionManager.get().getUpdateApkUrl());
        assertEquals("", SessionManager.get().getUpdateCurrentVersion());
        assertEquals("", SessionManager.get().getUpdateTargetVersion());
        assertEquals("", SessionManager.get().getUpdateVersionName());
    }

    @Test
    public void clearServerBoundState_shouldResetServerDerivedState() throws Exception {
        SessionManager.get().saveToken("token", 1893456000L);
        SessionManager.get().saveDeviceRegistered(true);
        SessionManager.get().saveDeviceConfigInitialized(true);
        SessionManager.get().saveLineBinding("L1", "Line 1");
        SessionManager.get().saveTeamBindingId(7);
        SessionManager.get().saveTeamBindingName("Team 7");
        putRawString(Constants.KEY_TEAM_TIME_RANGES, "[\"08:00-17:00\"]");
        SessionManager.get().saveCheckCount(3);
        SessionManager.get().saveUpdateInfo(true, "https://example.com/app.apk", "1.0.0", "2.0.0", "release");

        SessionManager.get().clearServerBoundState();

        assertEquals(null, SessionManager.get().getToken());
        assertFalse(SessionManager.get().isDeviceRegistered());
        assertFalse(SessionManager.get().isDeviceConfigInitialized());
        assertEquals("", SessionManager.get().getLineCode());
        assertEquals("", SessionManager.get().getLineName());
        assertEquals(0, SessionManager.get().getTeamBindingId());
        assertEquals("", SessionManager.get().getTeamBindingName());
        assertTrue(SessionManager.get().getCurrentTeamTimeRanges().isEmpty());
        assertEquals(0, SessionManager.get().getCheckCount());
        assertFalse(SessionManager.get().isUpdateNeeded());
    }

    @Test
    public void buildStableDeviceId_shouldReturnStableUppercase16Chars() {
        String first = SessionManager.buildStableDeviceId("device-fingerprint-seed");
        String second = SessionManager.buildStableDeviceId("device-fingerprint-seed");

        assertEquals(first, second);
        assertEquals(16, first.length());
        assertTrue(first.matches("[0-9A-F]{16}"));
    }

    @Test
    public void isModernDeviceId_shouldValidateSerialFriendlyId() {
        assertTrue(SessionManager.isModernDeviceId("A1B2C3D4E5F60789"));
        assertTrue(SessionManager.isModernDeviceId("PDA-2026-0018"));
        assertTrue(SessionManager.isModernDeviceId("device_0018"));
        assertTrue(SessionManager.isModernDeviceId("a1b2c3d4e5f60789"));
        assertTrue(SessionManager.isModernDeviceId("12345678"));
        assertFalse(SessionManager.isModernDeviceId("bad id"));
    }

    @Test
    public void getOrCreateDeviceId_shouldCreateAndPersist16CharId() {
        SessionManager.setResolvedDeviceIdForTest(SessionManager.buildStableDeviceId("device-fingerprint-seed"));
        String first = SessionManager.get().getOrCreateDeviceId();
        String second = SessionManager.get().getOrCreateDeviceId();

        assertEquals(first, second);
        assertEquals(16, first.length());
        assertTrue(first.matches("[0-9A-F]{16}"));
    }

    @Test
    public void rebuildDeviceId_shouldOverwriteStoredValueAndResetRegisteredFlag() throws Exception {
        SessionManager.setResolvedDeviceIdForTest(SessionManager.buildStableDeviceId("device-fingerprint-seed"));
        putRawDeviceId("PDA-OLD-ID");
        SessionManager.get().saveDeviceRegistered(true);

        String rebuilt = SessionManager.get().rebuildDeviceId();

        assertEquals(rebuilt, SessionManager.get().getDeviceId());
        assertEquals(16, rebuilt.length());
        assertTrue(rebuilt.matches("[0-9A-F]{16}"));
        assertFalse(SessionManager.get().isDeviceRegistered());
    }

    @Test
    public void getOrCreateDeviceId_shouldMigrateInvalidLegacyDeviceId() throws Exception {
        SessionManager.setResolvedDeviceIdForTest(SessionManager.buildStableDeviceId("device-fingerprint-seed"));
        putRawDeviceId("a b");
        SessionManager.get().saveDeviceRegistered(true);
        SessionManager.get().saveToken("token-legacy", 1893456000L);

        String migrated = SessionManager.get().getOrCreateDeviceId();

        assertTrue(SessionManager.isModernDeviceId(migrated));
        assertEquals(16, migrated.length());
        assertFalse(SessionManager.get().isDeviceRegistered());
        assertEquals(null, SessionManager.get().getToken());
    }

    @Test
    public void saveLastWifiConfig_shouldRoundTrip() {
        SessionManager.get().saveLastWifiConfig("Office-WiFi", "secret123");

        assertEquals("Office-WiFi", SessionManager.get().getLastWifiSsid());
        assertEquals("secret123", SessionManager.get().getLastWifiPassword());
    }

    private void setSessionPreferences(SharedPreferences prefs, SharedPreferences securePrefs) throws Exception {
        Field prefsField = SessionManager.class.getDeclaredField("prefs");
        prefsField.setAccessible(true);
        prefsField.set(SessionManager.get(), prefs);

        Field securePrefsField = SessionManager.class.getDeclaredField("securePrefs");
        securePrefsField.setAccessible(true);
        securePrefsField.set(SessionManager.get(), securePrefs);
    }

    private void setAppContext(Object context) throws Exception {
        Field appContextField = SessionManager.class.getDeclaredField("appContext");
        appContextField.setAccessible(true);
        appContextField.set(SessionManager.get(), context);
    }

    private void putRawDeviceId(String value) throws Exception {
        putRawString(Constants.KEY_DEVICE_ID, value);
    }

    private void putRawString(String key, String value) throws Exception {
        Field prefsField = SessionManager.class.getDeclaredField("prefs");
        prefsField.setAccessible(true);
        SharedPreferences prefs = (SharedPreferences) prefsField.get(SessionManager.get());
        prefs.edit().putString(key, value).apply();
    }

    private static final class MemorySharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                pending.put(key, new HashSet<>(values));
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                pending.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                pending.clear();
                removals.clear();
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (clear) {
                    values.clear();
                }
                for (String key : removals) {
                    values.remove(key);
                }
                values.putAll(pending);
            }
        }
    }
}
