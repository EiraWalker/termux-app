package com.termux.app.privateadb;

import android.content.Context;
import android.provider.Settings;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AutostartConfigLeaseTest {
    Context c;
    private static final String BOOT = "01234567-89ab-cdef-0123-456789abcdef";
    @Before public void setup() {
        c = RuntimeEnvironment.getApplication();
        NativeWindow.prefs(c).edit().clear().commit();
        Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 0);
    }
    JSONObject valid() throws Exception {
        return new JSONObject().put("version", 1).put("enabled", true).put("expectedSerial", "test-serial")
            .put("managerPath", AutostartConfig.MANAGER).put("heartbeatPath", AutostartConfig.HEARTBEAT);
    }
    @Test public void configAcceptsOnlyEnabledFixedPathAndSerialContract() throws Exception {
        assertEquals("test-serial", AutostartConfig.parse(valid().toString()).expectedSerial);
        for (String bad : new String[]{"{}", "not json", valid().put("enabled", false).toString(),
            valid().put("enabled", "true").toString(), valid().put("managerPath", "/system/bin/sh").toString(),
            valid().put("expectedSerial", "serial;sh").toString(), valid().put("heartbeatPath", "/sdcard/health.json").toString()}) {
            try { AutostartConfig.parse(bad); fail("must reject invalid config"); } catch (Exception expected) {}
        }
        assertNull(AutostartConfig.load(c));
    }
    @Test public void neverAdoptsExistingUserNativeWindow() {
        Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
        assertFalse(NativeWindow.open(c, BOOT, 45000));
        assertFalse(NativeWindow.owned(c));
        assertTrue(NativeWindow.close(c));
        assertEquals(1, Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", -1));
    }
    @Test public void ownedLeaseIsDurableAndCleanupReadsBackOff() {
        assertTrue(NativeWindow.open(c, BOOT, 45000));
        assertTrue(NativeWindow.owned(c));
        assertEquals(BOOT, NativeWindow.prefs(c).getString("bootId", ""));
        assertEquals(45000, NativeWindow.prefs(c).getLong("deadlineElapsedMs", -1));
        NativeWindow.cleanupIfExpired(c, BOOT, 44999);
        assertTrue(NativeWindow.owned(c));
        NativeWindow.cleanupIfExpired(c, BOOT, 45000);
        assertFalse(NativeWindow.owned(c));
        assertEquals(0, Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", -1));
    }
    @Test public void previousBootLeaseClosesWithoutConfig() {
        assertTrue(NativeWindow.open(c, BOOT, 45000));
        NativeWindow.cleanupIfExpired(c, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 0);
        assertFalse(NativeWindow.owned(c));
        assertEquals(0, Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", -1));
    }
}
