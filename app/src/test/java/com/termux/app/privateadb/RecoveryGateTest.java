package com.termux.app.privateadb;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class RecoveryGateTest {
    Context c;
    PrivateAdbService service;
    @Before public void setup() throws Exception {
        c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences(PrivateAdbService.PREFS, 0).edit().clear().commit();
        c.getSharedPreferences(MaintenanceCoordinator.PREFS, 0).edit().clear().commit();
        service = Robolectric.buildService(PrivateAdbService.class).get();
        java.io.File directory = AutostartConfig.directory(c); directory.mkdirs();
        write("autostart.json", new JSONObject().put("version",1).put("enabled",true)
            .put("expectedSerial","fictional-serial").put("managerPath",AutostartConfig.MANAGER)
            .put("heartbeatPath",AutostartConfig.HEARTBEAT));
        new java.io.File(directory,"health.json").delete();
    }
    void write(String name, JSONObject json) throws Exception {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(AutostartConfig.directory(c),name))) {
            out.write(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @After public void cleanup() {
        for (String file : new String[]{"health.json","autostart.json"})
            new java.io.File(AutostartConfig.directory(c),file).delete();
        service.onDestroy();
    }
    @Test public void healthRecoveryInsideActualDiscoveryClosesWindowWithoutDispatch() throws Exception {
        NativeWindow.prefs(c).edit().clear().commit();
        android.provider.Settings.Global.putInt(c.getContentResolver(),"adb_wifi_enabled",0);
        java.util.concurrent.ExecutorService runner=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.lang.reflect.Method recover=PrivateAdbService.class.getDeclaredMethod("recover",AutostartConfig.class,String.class);
            recover.setAccessible(true);
            java.util.concurrent.Future<?> future=runner.submit(() -> {
                try { recover.invoke(service,AutostartConfig.load(c),MaintenanceLeaseTest.BOOT); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            long limit=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!"discovering".equals(c.getSharedPreferences(PrivateAdbService.PREFS,0).getString("phase","")) && System.nanoTime()<limit && !future.isDone()) Thread.sleep(10);
            assertTrue(NativeWindow.owned(c));
            write("health.json",new JSONObject().put("version",1).put("boot_id",MaintenanceLeaseTest.BOOT)
                .put("uptime_ms",SystemClock.elapsedRealtime()).put("watch_pid",71).put("child_pid",72).put("state","healthy"));
            future.get(3,java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(NativeWindow.owned(c));
            assertEquals(0,c.getSharedPreferences(PrivateAdbService.PREFS,0).getInt("attempts",-1));
            assertFalse(c.getSharedPreferences(MaintenanceCoordinator.PREFS,0).getBoolean("guardianDispatchUnconfirmed",false));
            assertEquals("health_recovered",c.getSharedPreferences(PrivateAdbService.PREFS,0).getString("phase",""));
        } finally { service.onDestroy();runner.shutdownNow();runner.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS); }
    }
    @Test public void actualRecoveryReturnsBeforeOpeningWindowWhenHealthRecovered() throws Exception {
        write("health.json", new JSONObject().put("version",1).put("boot_id",MaintenanceLeaseTest.BOOT)
            .put("uptime_ms",SystemClock.elapsedRealtime()).put("watch_pid",71).put("child_pid",72).put("state","healthy"));
        java.util.concurrent.ExecutorService runner = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.lang.reflect.Method recover = PrivateAdbService.class.getDeclaredMethod("recover",AutostartConfig.class,String.class);
            recover.setAccessible(true);
            java.util.concurrent.Future<?> f = runner.submit(() -> {
                try { recover.invoke(service,AutostartConfig.load(c),MaintenanceLeaseTest.BOOT); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            f.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(NativeWindow.owned(c));
            assertEquals("health_recovered",c.getSharedPreferences(PrivateAdbService.PREFS,0).getString("phase",""));
        } finally { service.onDestroy(); runner.shutdownNow(); runner.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS); }
    }
    @Test public void freshHealthDuringDiscoveryCancelsBeforeWindowAndBeforeDispatch() throws Exception {
        long deadline = SystemClock.elapsedRealtime()+45000;
        assertTrue(service.recoveryAllowed(MaintenanceLeaseTest.BOOT,deadline));
        write("health.json", new JSONObject().put("version",1).put("boot_id",MaintenanceLeaseTest.BOOT)
            .put("uptime_ms",SystemClock.elapsedRealtime()).put("watch_pid",71).put("child_pid",72).put("state","healthy"));
        assertFalse(service.recoveryAllowed(MaintenanceLeaseTest.BOOT,deadline));
        assertEquals("health_recovered",c.getSharedPreferences(PrivateAdbService.PREFS,0).getString("phase",""));
        write("health.json",new JSONObject().put("state","failure"));
        assertTrue(service.recoveryAllowed(MaintenanceLeaseTest.BOOT,deadline));
        c.getSharedPreferences(PrivateAdbService.PREFS,0).edit().putBoolean("safetyPaused",true).commit();
        assertFalse(service.recoveryAllowed(MaintenanceLeaseTest.BOOT,deadline));
    }
}
