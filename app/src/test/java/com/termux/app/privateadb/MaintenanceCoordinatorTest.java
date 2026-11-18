package com.termux.app.privateadb;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class MaintenanceCoordinatorTest {
    Context c;
    final String token = MaintenanceLeaseTest.HASH;
    String payload;
    @Before public void setup() throws Exception {
        c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("private_adb_install_maintenance", 0).edit().clear().commit();
        c.getSharedPreferences(PrivateAdbService.PREFS, 0).edit().clear().commit();
        NativeWindow.prefs(c).edit().clear().commit();
        Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 0);
        java.io.File directory = AutostartConfig.directory(c); directory.mkdirs();
        new java.io.File(directory, "start.pending").delete();
        MaintenanceCoordinator.forgetAcknowledgement();
        // Robolectric's API-28 Os.lstat is not a real Windows filesystem syscall.
        // Only this syscall seam uses Java NIO; store, Settings and drain execute production code.
        MaintenanceCoordinator.pendingProbe = context -> {
            try {
                java.nio.file.Files.readAttributes(new java.io.File(AutostartConfig.directory(context),
                    "start.pending").toPath(), java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
                return true;
            } catch (java.nio.file.NoSuchFileException absent) { return false; }
            catch (java.io.IOException unknown) { return true; }
        };
        MaintenanceCoordinator.clock = new MaintenanceCoordinator.Clock() {
            public String boot() { return MaintenanceLeaseTest.BOOT; }
            public long now() { return SystemClock.elapsedRealtime(); }
            public long installed(Context context) {
                try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode; }
                catch (Exception e) { return -1; }
            }
        };
        payload = new JSONObject().put("version", 1).put("token", token)
            .put("boot", MaintenanceLeaseTest.BOOT).put("deadlineElapsedMs", SystemClock.elapsedRealtime()+300000)
            .put("profileSha256", token).put("releaseSha256", token)
            .put("baselineVersion", 100).put("targetVersion", 101).toString();
        android.content.pm.PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        info.versionCode = 100;
        org.robolectric.Shadows.shadowOf(c.getPackageManager()).installPackage(info);
    }
    String command(String verb) throws Exception {
        return MaintenanceCoordinator.call(c, verb, "request".equals(verb) ? payload :
            new JSONObject().put("version", 1).put("token", token).toString());
    }
    @After public void resetSeams() {
        MaintenanceCoordinator.clock = MaintenanceCoordinator.SYSTEM;
        MaintenanceCoordinator.pendingProbe = MaintenanceCoordinator.SYSTEM_PENDING;
        MaintenanceCoordinator.forgetAcknowledgement();
        new java.io.File(AutostartConfig.directory(c),"autostart.json").delete();
        new java.io.File(AutostartConfig.directory(c),"health.json").delete();
    }
    @Test public void guardianTickAcknowledgesBeforeHealthAndPreservesFailureBudget() throws Exception {
        command("request");
        c.getSharedPreferences(PrivateAdbService.PREFS,0).edit().putInt("failures",3).putString("phase","healthy").commit();
        PrivateAdbService service = Robolectric.buildService(PrivateAdbService.class).get();
        try {
            java.lang.reflect.Method tick = PrivateAdbService.class.getDeclaredMethod("tick");
            tick.setAccessible(true); tick.invoke(service);
            assertEquals("active", new JSONObject(command("status")).getString("phase"));
            assertEquals(3,c.getSharedPreferences(PrivateAdbService.PREFS,0).getInt("failures",-1));
            assertEquals("healthy",c.getSharedPreferences(PrivateAdbService.PREFS,0).getString("phase",""));
        } finally { service.onDestroy(); }
    }
    @Test public void expiredOrDestroyedWorkerCannotPublishActiveAcknowledgement() throws Exception {
        command("request");
        java.util.concurrent.atomic.AtomicBoolean live = new java.util.concurrent.atomic.AtomicBoolean(true);
        assertEquals("active",MaintenanceCoordinator.workerCheckpoint(c,true,live::get));
        live.set(false);
        assertEquals("pending",new JSONObject(command("status")).getString("phase"));
        assertEquals("draining",MaintenanceCoordinator.workerCheckpoint(c,true,live::get));
    }
    @Test public void appDispatchIntentBlocksAckEvenBeforeWrapperCreatesPending() throws Exception {
        command("request");
        c.getSharedPreferences(MaintenanceCoordinator.PREFS,0).edit().putBoolean("guardianDispatchUnconfirmed",true).commit();
        assertEquals("blocked_unknown_dispatch",MaintenanceCoordinator.workerCheckpoint(c,true,() -> true));
        assertEquals("blocked_unknown_dispatch",new JSONObject(command("status")).getString("phase"));
    }
    @Test public void samePayloadIsIdempotentButCannotChangeDeadlineOrStealLease() throws Exception {
        assertEquals("pending",new JSONObject(command("request")).getString("phase"));
        assertEquals("pending",new JSONObject(command("request")).getString("phase"));
        String original = payload;
        payload = new JSONObject(payload).put("deadlineElapsedMs",new JSONObject(payload).getLong("deadlineElapsedMs")+1).toString();
        assertEquals("payload_mismatch",new JSONObject(command("request")).getString("phase"));
        payload = new JSONObject(original).put("token",new String(new char[64]).replace('\0','b')).toString();
        assertEquals("busy",new JSONObject(command("request")).getString("phase"));
        payload = original;
        assertEquals("active",MaintenanceCoordinator.workerCheckpoint(c,true,() -> true));
    }
    @Test public void targetPackageReplacementKeepsBindingButThirdVersionRevokes() throws Exception {
        command("request");
        android.content.pm.PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(),0);
        info.versionCode=101; org.robolectric.Shadows.shadowOf(c.getPackageManager()).installPackage(info);
        MaintenanceCoordinator.forgetAcknowledgement();
        assertEquals("pending",new JSONObject(command("status")).getString("phase"));
        assertEquals("active",MaintenanceCoordinator.workerCheckpoint(c,true,() -> true));
        info.versionCode=102; org.robolectric.Shadows.shadowOf(c.getPackageManager()).installPackage(info);
        assertEquals("version_mismatch",new JSONObject(command("status")).getString("phase"));
        assertFalse(MaintenanceCoordinator.suppresses(c));
    }
    @Test public void unknownWrapperPendingIsNeverDeletedOrAcknowledged() throws Exception {
        command("request");
        java.io.File pending = new java.io.File(AutostartConfig.directory(c),"start.pending");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(pending)) { out.write(85); }
        assertEquals("blocked_unknown_dispatch",MaintenanceCoordinator.workerCheckpoint(c,true,() -> true));
        assertEquals(1,pending.length());
        assertEquals("blocked_unknown_dispatch",new JSONObject(command("status")).getString("phase"));
        assertTrue(pending.isFile());
    }
    @Test public void lateStopDuringSlowPendingCheckRevokesBeforeAckAndPreservesBudget() throws Exception {
        command("request");
        c.getSharedPreferences(PrivateAdbService.PREFS,0).edit().putInt("failures",3).commit();
        MaintenanceCoordinator.pendingProbe = context -> {
            context.getSharedPreferences(PrivateAdbService.PREFS,0).edit().putBoolean("userStopped",true).commit();
            return false;
        };
        assertEquals("revoked",MaintenanceCoordinator.workerCheckpoint(c,true,() -> true));
        command("release");
        assertTrue(c.getSharedPreferences(PrivateAdbService.PREFS,0).getBoolean("userStopped",false));
        assertEquals(3,c.getSharedPreferences(PrivateAdbService.PREFS,0).getInt("failures",-1));
    }
    @Test public void expiryClosesOwnedWindowAndDoesNotRenewOnSameTokenRetry() throws Exception {
        command("request");
        assertTrue(NativeWindow.open(c,MaintenanceLeaseTest.BOOT,SystemClock.elapsedRealtime()+45000));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300001));
        assertEquals("expired",MaintenanceCoordinator.workerCheckpoint(c,true,() -> true));
        assertFalse(NativeWindow.owned(c));
        assertFalse(MaintenanceCoordinator.suppresses(c));
        assertEquals("expired",new JSONObject(command("request")).getString("phase"));
        payload = new JSONObject(payload).put("deadlineElapsedMs",SystemClock.elapsedRealtime()+10000).toString();
        assertEquals("payload_mismatch",new JSONObject(command("request")).getString("phase"));
    }
    @Test public void bootChangeExpiresAndReleasedTokensCannotReplayAfterAnotherTransaction() throws Exception {
        command("request"); command("release");
        String original=payload;
        payload = new JSONObject(payload).put("token",new String(new char[64]).replace('\0','b')).toString();
        assertEquals("pending",new JSONObject(command("request")).getString("phase"));
        MaintenanceCoordinator.Clock prior=MaintenanceCoordinator.clock;
        MaintenanceCoordinator.clock = new MaintenanceCoordinator.Clock() {
            public String boot() { return "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"; }
            public long now() { return prior.now(); }
            public long installed(Context context) { return prior.installed(context); }
        };
        assertFalse(MaintenanceCoordinator.suppresses(c));
        MaintenanceCoordinator.clock=prior;
        payload=original;
        assertEquals("token_reused",new JSONObject(command("request")).getString("phase"));
    }
    @Test public void concurrentTokensOnlyOneCanReserve() throws Exception {
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        String second=new JSONObject(payload).put("token",new String(new char[64]).replace('\0','b')).toString();
        try {
            java.util.concurrent.Future<String> a=pool.submit(() -> MaintenanceCoordinator.call(c,"request",payload));
            java.util.concurrent.Future<String> b=pool.submit(() -> MaintenanceCoordinator.call(c,"request",second));
            java.util.List<String> phases=java.util.Arrays.asList(new JSONObject(a.get(5,java.util.concurrent.TimeUnit.SECONDS)).getString("phase"),new JSONObject(b.get(5,java.util.concurrent.TimeUnit.SECONDS)).getString("phase"));
            assertEquals(1,java.util.Collections.frequency(phases,"pending"));
            assertEquals(1,java.util.Collections.frequency(phases,"busy"));
        } finally { pool.shutdownNow(); pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS); }
    }
    @Test public void realSerialWorkerCannotAckUntilEarlierRecoveryTaskReturns() throws Exception {
        command("request");
        PrivateAdbService service=Robolectric.buildService(PrivateAdbService.class).get();
        java.lang.reflect.Field f=PrivateAdbService.class.getDeclaredField("worker");f.setAccessible(true);
        java.util.concurrent.ScheduledExecutorService worker=(java.util.concurrent.ScheduledExecutorService)f.get(service);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),finish=new java.util.concurrent.CountDownLatch(1);
        try {
            assertTrue(NativeWindow.open(c,MaintenanceLeaseTest.BOOT,SystemClock.elapsedRealtime()+45000));
            worker.execute(() -> { entered.countDown(); try { finish.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            service.onStartCommand(null,0,1);
            assertNotEquals("active",new JSONObject(command("status")).getString("phase"));
            assertTrue(NativeWindow.owned(c));
            finish.countDown(); worker.submit(() -> {}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("active",new JSONObject(command("status")).getString("phase"));
            assertFalse(NativeWindow.owned(c));
        } finally { finish.countDown(); service.onDestroy(); }
    }
    @Test public void requestDuringActualDiscoveryDrainsClosesThenWorkerAcknowledges() throws Exception {
        JSONObject config=new JSONObject().put("version",1).put("enabled",true).put("expectedSerial","fictional-serial")
            .put("managerPath",AutostartConfig.MANAGER).put("heartbeatPath",AutostartConfig.HEARTBEAT);
        try (java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(AutostartConfig.directory(c),"autostart.json"))) {
            out.write(config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        PrivateAdbService service=Robolectric.buildService(PrivateAdbService.class).get();
        java.lang.reflect.Field field=PrivateAdbService.class.getDeclaredField("worker");field.setAccessible(true);
        java.util.concurrent.ScheduledExecutorService worker=(java.util.concurrent.ScheduledExecutorService)field.get(service);
        try {
            java.lang.reflect.Method recover=PrivateAdbService.class.getDeclaredMethod("recover",AutostartConfig.class,String.class);recover.setAccessible(true);
            java.util.concurrent.Future<?> future=worker.submit(() -> {
                try { recover.invoke(service,AutostartConfig.load(c),MaintenanceLeaseTest.BOOT); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            long limit=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!"discovering".equals(c.getSharedPreferences(PrivateAdbService.PREFS,0).getString("phase","")) && System.nanoTime()<limit && !future.isDone()) Thread.sleep(10);
            assertTrue(NativeWindow.owned(c));
            assertNotEquals("active",new JSONObject(command("request")).getString("phase"));
            service.onStartCommand(null,0,1);
            future.get(5,java.util.concurrent.TimeUnit.SECONDS);
            worker.submit(() -> {}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("active",new JSONObject(command("status")).getString("phase"));
            assertFalse(NativeWindow.owned(c));
            assertEquals(0,c.getSharedPreferences(PrivateAdbService.PREFS,0).getInt("attempts",-1));
        } finally { service.onDestroy(); }
    }
    @Test public void actualFrameRoutesSameUidToCoordinatorStore() throws Exception {
        java.io.ByteArrayOutputStream request=new java.io.ByteArrayOutputStream();
        MaintenanceWire.writeRequest(request,"request",payload);
        MaintenanceTransportTest.Connection connection=new MaintenanceTransportTest.Connection(request.toByteArray());
        connection.uid=android.os.Process.myUid();
        MaintenanceTransport.serve(connection,android.os.Process.myUid(),(verb,json) -> MaintenanceCoordinator.call(c,verb,json));
        assertEquals("pending",new JSONObject(MaintenanceWire.readResponse(new java.io.ByteArrayInputStream(connection.output.toByteArray()))).getString("phase"));
        assertEquals(android.os.Process.myUid(),c.getSharedPreferences(MaintenanceCoordinator.PREFS,0).getInt("ownerUid",-1));
        assertEquals("active",MaintenanceCoordinator.workerCheckpoint(c,true,() -> true));
    }
    @Test public void stopDoesNotWaitForSlowCoordinatorLockBeforeCancellingRecovery() throws Exception {
        PrivateAdbService service=Robolectric.buildService(PrivateAdbService.class).get();
        java.util.concurrent.CountDownLatch held=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService threads=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            threads.submit(() -> {synchronized(MaintenanceCoordinator.class){held.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
            assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<Integer> stopped=threads.submit(() -> service.onStartCommand(new android.content.Intent().setAction(PrivateAdbService.STOP),0,1));
            assertEquals(android.app.Service.START_NOT_STICKY,(int)stopped.get(2,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(c.getSharedPreferences(PrivateAdbService.PREFS,0).getBoolean("userStopped",false));
        } finally {release.countDown();threads.shutdownNow();threads.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);service.onDestroy();}
    }
    @Test public void durablePendingRequiresSerialDrainThenCloseBeforeAck() throws Exception {
        assertEquals("pending", new JSONObject(command("request")).getString("phase"));
        assertTrue(NativeWindow.open(c, MaintenanceLeaseTest.BOOT, SystemClock.elapsedRealtime()+45000));
        assertEquals("draining", MaintenanceCoordinator.workerCheckpoint(c, false, () -> true));
        assertTrue(NativeWindow.owned(c));
        assertEquals("active", MaintenanceCoordinator.workerCheckpoint(c, true, () -> true));
        assertFalse(NativeWindow.owned(c));
        assertEquals(0, Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", -1));
        assertEquals("active", new JSONObject(command("status")).getString("phase"));
        MaintenanceCoordinator.forgetAcknowledgement();
        assertEquals("pending", new JSONObject(command("status")).getString("phase"));
        assertEquals("active", MaintenanceCoordinator.workerCheckpoint(c, true, () -> true));
        assertEquals("released", new JSONObject(command("release")).getString("phase"));
        assertEquals("released", new JSONObject(command("request")).getString("phase"));
    }
}
