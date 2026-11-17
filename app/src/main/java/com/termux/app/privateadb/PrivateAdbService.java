package com.termux.app.privateadb;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.nsd.*;
import android.os.*;
import android.provider.Settings;
import com.termux.R;
import org.json.JSONObject;
import java.lang.Process;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated from TermuxService and terminal sessions. One worker owns each bounded recovery. */
public final class PrivateAdbService extends Service {
    public static final String STOP = "com.termux.action.PRIVATE_ADB_AUTOSTART_STOP";
    static final String PREFS = "private_adb_autostart";
    private static final String CHANNEL = "private_adb_autostart";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopping;
    private volatile Process process;
    private volatile Discovery discovery;
    private long nextAttempt;
    private int failures;
    private boolean scheduled;

    static boolean unlocked(Context c) {
        return Build.VERSION.SDK_INT < 24 || ((UserManager)c.getSystemService(USER_SERVICE)).isUserUnlocked();
    }
    static boolean enabled(Context c) {
        return unlocked(c) && AutostartConfig.load(c) != null &&
            !c.getSharedPreferences(PREFS, 0).getBoolean("userStopped", false);
    }
    static void startIfEnabled(Context c) {
        if (!enabled(c)) return;
        Intent i = new Intent(c, PrivateAdbService.class);
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); }
        catch (RuntimeException ignored) { phase(c, "start_blocked"); }
    }
    static void phase(Context c, String value) {
        c.getSharedPreferences(PREFS, 0).edit().putString("phase", value)
            .putLong("phaseElapsedMs", SystemClock.elapsedRealtime()).apply();
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL,
            "Private ADB recovery", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 8104,
            new Intent(this, PrivateAdbService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        startForeground(8103, builder.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Private ADB recovery")
            .setContentText("Monitoring local heartbeat; Stop disables automatic recovery")
            .setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, 0).edit().putBoolean("userStopped", true).commit();
            stopping = true;
            abort();
            worker.execute(() -> { NativeWindow.close(this); phase(this, "user_stopped"); stopSelf(); });
            return START_NOT_STICKY;
        }
        if (!scheduled) {
            scheduled = true;
            worker.scheduleWithFixedDelay(this::tickSafely, 0, 15, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }
    private void tickSafely() {
        try { tick(); } catch (Exception e) { phase(this, "monitor_error"); }
    }
    private void tick() throws Exception {
        // Sticky reconstruction closes a previous owned window before doing anything else.
        if (NativeWindow.owned(this) && !NativeWindow.close(this)) { phase(this, "cleanup_pending"); return; }
        if (stopping || !enabled(this)) { phase(this, "disabled"); stopSelf(); return; }
        if (getSharedPreferences(PREFS, 0).getBoolean("safetyPaused", false)) { phase(this, "safety_paused"); return; }
        AutostartConfig config = AutostartConfig.load(this);
        String boot = bootId();
        if (!AutostartPolicy.validBoot(boot)) { phase(this, "boot_unavailable"); return; }
        long now = SystemClock.elapsedRealtime();
        android.content.SharedPreferences state = getSharedPreferences(PREFS, 0);
        if (boot.equals(state.getString("retryBoot", ""))) {
            nextAttempt = Math.max(nextAttempt, state.getLong("nextAttemptElapsedMs", 0));
            failures = Math.max(failures, state.getInt("failures", 0));
        }
        boolean healthy = false;
        try {
            JSONObject h = new JSONObject(AutostartConfig.read(new File(AutostartConfig.directory(this), "health.json")));
            healthy = AutostartPolicy.healthy(h.getInt("version"), h.getString("boot_id"), boot,
                h.getLong("uptime_ms"), SystemClock.elapsedRealtime(), h.getLong("watch_pid"), h.getLong("child_pid"), h.getString("state"));
        } catch (Exception ignored) {}
        if (healthy) {
            phase(this, "healthy"); failures = 0;
            state.edit().putInt("failures", 0).apply(); return;
        }
        if (now < nextAttempt) return;
        if (!wifi()) { phase(this, "waiting_wifi"); return; }
        failures = Math.min(4, failures + 1);
        nextAttempt = now + AutostartPolicy.WINDOW_MS + AutostartPolicy.retryDelay(failures);
        // Persist before the window so repeated sticky deaths cannot erase the backoff.
        if (!state.edit().putString("retryBoot", boot).putInt("failures", failures)
            .putLong("nextAttemptElapsedMs", nextAttempt).commit()) { phase(this, "state_write_failed"); return; }
        try { recover(config, boot); }
        finally {
            nextAttempt = SystemClock.elapsedRealtime() + AutostartPolicy.retryDelay(failures);
            state.edit().putLong("nextAttemptElapsedMs", nextAttempt).commit();
        }
    }
    private boolean wifi() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }
    static String bootId() {
        try { return AutostartConfig.read(new File("/proc/sys/kernel/random/boot_id")).trim(); }
        catch (Exception ignored) { return ""; }
    }
    private void recover(AutostartConfig config, String boot) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + AutostartPolicy.WINDOW_MS;
        Discovery d = new Discovery(config.expectedSerial, deadline);
        discovery = d;
        PowerManager.WakeLock lock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Termux:PrivateAdbWindow");
        lock.acquire(50000);
        try {
            if (stopping || !NativeWindow.open(this, boot, deadline)) { phase(this, "native_busy_or_denied"); return; }
            phase(this, "discovering");
            main.post(d::start);
            int attempts = 0;
            getSharedPreferences(PREFS, 0).edit().putInt("lastCandidatePort", 0).putInt("attempts", 0).apply();
            long settled = Math.min(deadline, SystemClock.elapsedRealtime() + NsdRecoveryPolicy.SETTLE_MS);
            while (!stopping && enabled(this) && !d.closed.get() && SystemClock.elapsedRealtime() < deadline) {
                if (SystemClock.elapsedRealtime() < settled) { Thread.sleep(100); continue; }
                Integer port = d.candidates.next(SystemClock.elapsedRealtime());
                if (port == null) { Thread.sleep(100); continue; }
                getSharedPreferences(PREFS, 0).edit().putInt("lastCandidatePort", port).putInt("attempts", ++attempts).apply();
                phase(this, "bootstrap");
                ProcessBuilder pb = new ProcessBuilder(AutostartConfig.MANAGER, "start", Integer.toString(port));
                Map<String,String> env = pb.environment();
                env.clear();
                env.put("HOME", "/data/data/com.termux/files/home");
                env.put("PREFIX", "/data/data/com.termux/files/usr");
                env.put("PATH", "/data/data/com.termux/files/usr/bin:/system/bin");
                env.put("TMPDIR", "/data/data/com.termux/files/usr/tmp");
                env.put("TABLET_DAEMON_EXPECTED_SERIAL", config.expectedSerial);
                pb.directory(new File(getFilesDir(), "home"));
                // Never store raw wrapper output (may contain device identifiers or secrets).
                pb.redirectOutput(new File("/dev/null")); pb.redirectError(new File("/dev/null"));
                try {
                    if (stopping || !enabled(this) || d.closed.get() || SystemClock.elapsedRealtime() >= deadline) return;
                    Process p = pb.start(); process = p; p.getOutputStream().close();
                    Integer code = null;
                    while (!stopping && enabled(this) && SystemClock.elapsedRealtime() < deadline) {
                        try { code = p.exitValue(); break; }
                        catch (IllegalThreadStateException running) { Thread.sleep(100); }
                    }
                    if (code == null) { phase(this, "bootstrap_timeout"); return; }
                    if (code == 2) {
                        getSharedPreferences(PREFS, 0).edit().putBoolean("safetyPaused", true).commit();
                        phase(this, "safety_paused");
                    } else phase(this, code == 0 ? "bootstrap_returned" : "candidate_failed");
                    if (d.candidates.finished(code)) return;
                } catch (IOException failed) { phase(this, "candidate_failed"); }
                finally { abortProcess(); }
                // Failed or stale observed ports do not consume the remainder of the window.
            }
            phase(this, "discovery_timeout");
        } finally {
            abortProcess();
            d.close(); discovery = null;
            if (!NativeWindow.close(this)) phase(this, "cleanup_pending");
            if (lock.isHeld()) lock.release();
        }
    }
    private void abortProcess() {
        Process p = process; process = null;
        if (p != null) { p.destroy(); if (Build.VERSION.SDK_INT >= 26) p.destroyForcibly(); }
    }
    private void abort() { abortProcess(); Discovery d = discovery; if (d != null) d.close(); }
    @Override public void onDestroy() {
        stopping = true; abort(); worker.shutdownNow();
        NativeWindow.close(this); super.onDestroy();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        // One explicit, private fallback; no recurring broadcast restart loop.
        if (enabled(this)) {
            PendingIntent pi = PendingIntent.getBroadcast(this, 8105,
                new Intent(this, AutostartReceiver.class).setAction(AutostartReceiver.RECHECK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            ((AlarmManager)getSystemService(ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60000, pi);
        }
        super.onTaskRemoved(rootIntent);
    }
    @Override public IBinder onBind(Intent i) { return null; }

    private final class Discovery implements NsdManager.DiscoveryListener {
        final NsdRecoveryPolicy candidates;
        final NsdRecoveryPolicy.Services<NsdServiceInfo> pending = new NsdRecoveryPolicy.Services<>();
        final NsdManager nsd = (NsdManager)getSystemService(NSD_SERVICE);
        final String serial;
        final AtomicBoolean closed = new AtomicBoolean();
        final ExecutorService checks = Executors.newSingleThreadExecutor();
        final Map<String, LiveInfo> live = new HashMap<>(); // Only touched on main.
        NsdManager.ResolveListener active;
        boolean started;
        final Runnable retry = this::resolveNext;
        Discovery(String serial, long deadline) { this.serial = serial; candidates = new NsdRecoveryPolicy(deadline); }
        boolean matches(NsdServiceInfo info) {
            return info != null && info.getServiceName() != null && info.getServiceType() != null &&
                info.getServiceName().startsWith("adb-" + serial + "-") &&
                info.getServiceType().startsWith("_adb-tls-connect._tcp");
        }
        String key(NsdServiceInfo info) { return info.getServiceName() + "|" + info.getServiceType(); }
        void start() {
            if (closed.get()) return;
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    // Select an existing physical Wi-Fi network, never the VPN/default network.
                    ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
                    Network wifiNetwork = null;
                    for (Network network : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { wifiNetwork = network; break; }
                    }
                    if (wifiNetwork == null) { close(); return; }
                    started = true;
                    nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD,
                        wifiNetwork, command -> main.post(command), this);
                } else {
                    started = true;
                    nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, this);
                }
            } catch (RuntimeException ignored) { close(); }
        }
        void close() {
            if (!closed.compareAndSet(false, true)) return;
            candidates.close(); checks.shutdownNow();
            main.post(() -> {
                main.removeCallbacks(retry); pending.clear();
                if (Build.VERSION.SDK_INT >= 34) {
                    for (LiveInfo callback : new ArrayList<>(live.values())) callback.close();
                    live.clear();
                    if (active != null) try { nsd.stopServiceResolution(active); } catch (RuntimeException ignored) {}
                }
                if (started) { started = false;
                    try { nsd.stopServiceDiscovery(this); } catch (RuntimeException ignored) {} }
            });
        }
        void observe(NsdServiceInfo service) {
            if (closed.get() || !matches(service)) return;
            try { checks.execute(() -> {
                try {
                    List<InetAddress> local = new ArrayList<>();
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    while (interfaces != null && interfaces.hasMoreElements())
                        local.addAll(Collections.list(interfaces.nextElement().getInetAddresses()));
                    if (!closed.get() && AutostartPolicy.localEndpoint(service.getHost(), service.getPort(), local))
                        candidates.offer(service.getPort());
                } catch (Exception ignored) {}
            }); } catch (RejectedExecutionException ignored) {}
        }
        @Override public void onServiceFound(NsdServiceInfo info) {
            main.post(() -> {
                if (closed.get() || !matches(info)) return;
                String key = key(info);
                if (Build.VERSION.SDK_INT >= 34) {
                    if (live.containsKey(key)) return;
                    if (live.size() >= NsdRecoveryPolicy.LIMIT) return;
                    LiveInfo callback = new LiveInfo(info); live.put(key, callback);
                    callback.start();
                } else { pending.put(key, info); resolveNext(); }
            });
        }
        void resolveNext() {
            if (closed.get() || active != null) return;
            main.removeCallbacks(retry);
            NsdServiceInfo info = pending.next();
            if (info == null) return;
            active = new NsdManager.ResolveListener() {
                void done() { active = null; if (!closed.get()) main.postDelayed(retry, 1000); }
                @Override public void onResolveFailed(NsdServiceInfo service, int error) { main.post(this::done); }
                @Override public void onServiceResolved(NsdServiceInfo service) {
                    observe(service); main.post(this::done);
                }
            };
            try { nsd.resolveService(info, active); }
            catch (RuntimeException ignored) { active = null; main.postDelayed(retry, 1000); }
        }
        // Isolated API-34 type: older Androids use the serialized, periodically refreshed fallback.
        @android.annotation.TargetApi(34)
        final class LiveInfo implements NsdManager.ServiceInfoCallback {
            final NsdServiceInfo info;
            boolean registered;
            LiveInfo(NsdServiceInfo info) { this.info = info; }
            void start() {
                try { registered = true; nsd.registerServiceInfoCallback(info, command -> main.post(command), this); }
                catch (RuntimeException ignored) { registered = false; fallback(); }
            }
            void fallback() {
                if (!closed.get()) { pending.put(key(info), info); resolveNext(); }
            }
            void close() {
                if (registered) { registered = false;
                    try { nsd.unregisterServiceInfoCallback(this); } catch (RuntimeException ignored) {} }
            }
            @Override public void onServiceInfoCallbackRegistrationFailed(int error) { registered = false; fallback(); }
            @Override public void onServiceInfoCallbackUnregistered() { registered = false; }
            @Override public void onServiceLost() { /* Keep subscription for a later update in the same window. */ }
            @Override public void onServiceUpdated(NsdServiceInfo service) { observe(service); }
        }
        @Override public void onDiscoveryStarted(String type) {}
        @Override public void onDiscoveryStopped(String type) {}
        @Override public void onStartDiscoveryFailed(String type, int error) { close(); }
        @Override public void onStopDiscoveryFailed(String type, int error) {}
        @Override public void onServiceLost(NsdServiceInfo service) {
            main.post(() -> {
                if (!matches(service)) return;
                pending.remove(key(service));
                if (Build.VERSION.SDK_INT >= 34) {
                    LiveInfo callback = live.remove(key(service)); if (callback != null) callback.close();
                }
            });
        }
    }
}
