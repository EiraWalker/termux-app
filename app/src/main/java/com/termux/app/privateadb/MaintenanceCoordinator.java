package com.termux.app.privateadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** One app-process store. Binder may reserve pending; only guardian's worker may acknowledge. */
final class MaintenanceCoordinator {
    static final String PREFS = "private_adb_install_maintenance";
    interface Clock { String boot(); long now(); long installed(Context context); }
    static final Clock SYSTEM = new Clock() {
        public String boot() { return PrivateAdbService.bootId(); }
        public long now() { return SystemClock.elapsedRealtime(); }
        public long installed(Context c) {
            try {
                android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
                return android.os.Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
            } catch (Exception e) { return -1; }
        }
    };
    static Clock clock = SYSTEM; // Package-private controllable host-test clock, not an IPC option.
    private static String acknowledgedToken;
    private static java.util.function.BooleanSupplier acknowledgedWorker = () -> false;
    private MaintenanceCoordinator() {}
    static synchronized void forgetAcknowledgement() { acknowledgedToken = null; acknowledgedWorker = () -> false; }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }
    private static MaintenanceLease load(Context c) {
        SharedPreferences p = prefs(c);
        String raw = p.getString("record", null);
        if (raw == null) return null;
        MaintenanceLease.Request r = MaintenanceProtocol.parse("request", raw).request;
        String phase = p.getString("phase", "");
        if (!("pending".equals(phase) || "active".equals(phase) || "released".equals(phase) ||
              "expired".equals(phase) || "revoked".equals(phase) || "version_mismatch".equals(phase)))
            throw new IllegalStateException("invalid maintenance store");
        return new MaintenanceLease(r, p.getInt("ownerUid", -1), phase);
    }
    private static boolean flag(Context c, String name) {
        return c.getSharedPreferences(PrivateAdbService.PREFS, 0).getBoolean(name, false);
    }
    private static String validate(Context c, MaintenanceLease l) {
        String prior = l.phase;
        long installed = clock.installed(c); // May call Binder; all mutable gates sampled afterwards.
        String boot = clock.boot();
        String phase = l.validate(Process.myUid(), boot, clock.now(), installed,
            flag(c, "userStopped"), flag(c, "safetyPaused"));
        if (!prior.equals(l.phase) && !prefs(c).edit().putString("phase", l.phase).commit())
            throw new IllegalStateException("maintenance write failed");
        return phase;
    }
    interface PendingProbe { boolean unknown(Context c); }
    static final PendingProbe SYSTEM_PENDING = c -> {
        try { Os.lstat(new File(AutostartConfig.directory(c), "start.pending").getPath()); return true; }
        catch (ErrnoException e) { return e.errno != OsConstants.ENOENT; }
        catch (RuntimeException e) { return true; }
    };
    static PendingProbe pendingProbe = SYSTEM_PENDING;
    static synchronized boolean reserveGuardianDispatch(Context c, String boot) {
        if (suppresses(c) || unknownDispatch(c) || flag(c,"userStopped") || flag(c,"safetyPaused")) return false;
        return prefs(c).edit().putBoolean("guardianDispatchUnconfirmed",true).putString("guardianDispatchBoot",boot).commit();
    }
    static synchronized void confirmGuardianDispatch(Context c) {
        if (!prefs(c).edit().putBoolean("guardianDispatchUnconfirmed",false).commit())
            throw new IllegalStateException("dispatch receipt failed");
    }
    static boolean unknownDispatch(Context c) {
        boolean unknown = prefs(c).getBoolean("guardianDispatchUnconfirmed",false);
        if (unknown) {
            String prior = prefs(c).getString("guardianDispatchBoot","");
            String boot = clock.boot();
            if (AutostartPolicy.validBoot(prior) && AutostartPolicy.validBoot(boot) && !prior.equals(boot)) {
                if (!prefs(c).edit().putBoolean("guardianDispatchUnconfirmed",false).commit()) return true;
                unknown = false;
            }
        }
        return unknown || pendingProbe.unknown(c);
    }
    static synchronized boolean suppresses(Context c) {
        try {
            MaintenanceLease l = load(c);
            return l != null && ("not_owner".equals(validate(c, l)) || l.live());
        } catch (RuntimeException e) { return true; }
    }
    static String call(Context c, String verb, String payload) {
        MaintenanceProtocol.Parsed parsed = MaintenanceProtocol.parse(verb, payload);
        String result;
        synchronized (MaintenanceCoordinator.class) { result = operate(c, verb, payload, parsed); }
        // Read-only status never restarts a service. A non-ready request/release may wake the
        // existing FGS path; no potentially slow startup is performed after returning an active snapshot.
        if ("release".equals(verb) || ("request".equals(verb) && !result.contains("\"phase\":\"active\"")))
            PrivateAdbService.startIfEnabled(c);
        return result;
    }
    private static String operate(Context c, String verb, String payload, MaintenanceProtocol.Parsed parsed) {
        try {
            MaintenanceLease l = load(c);
            if (l != null) validate(c, l);
            if ("request".equals(verb)) {
                MaintenanceLease.Request r = parsed.request;
                if (l != null && l.request.token.equals(r.token)) {
                    if (!l.request.same(r)) return response("payload_mismatch", null);
                } else {
                    if (l != null && l.live()) return response("busy", null);
                    SharedPreferences p = prefs(c);
                    String boot = clock.boot();
                    Set<String> used = new HashSet<>(p.getStringSet("usedTokens", new HashSet<>()));
                    if (!boot.equals(p.getString("usedBoot", ""))) used.clear();
                    if (used.contains(r.token)) return response("token_reused", null);
                    if (used.size() >= 256) return response("capacity", null);
                    long installed = clock.installed(c);
                    long now = clock.now();
                    if (flag(c, "userStopped") || flag(c, "safetyPaused")) return response("revoked", null);
                    if (!AutostartPolicy.validBoot(boot) || !boot.equals(r.boot) || r.deadline <= now ||
                        r.deadline - now > MaintenanceLease.MAX_MS) return response("expired_or_invalid_deadline", null);
                    if (r.baseline != installed) return response("version_mismatch", null);
                    used.add(r.token);
                    if (!p.edit().putString("record", payload).putString("phase", "pending")
                        .putInt("ownerUid", Process.myUid()).putString("usedBoot", boot)
                        .putStringSet("usedTokens", used).commit()) return response("store_error", null);
                    acknowledgedToken = null;
                    l = new MaintenanceLease(r, Process.myUid(), "pending");
                }
            } else {
                if (l == null || !l.request.token.equals(parsed.token)) return response("not_owner", null);
                if ("release".equals(verb)) {
                    String phase = l.release(Process.myUid(), parsed.token);
                    if (!prefs(c).edit().putString("phase", l.phase).commit()) return response("store_error", null);
                    acknowledgedToken = null;
                    return response(phase, l);
                }
            }
            // A persisted active bit is not a fresh ack after APK/process replacement.
            boolean pending = unknownDispatch(c);
            boolean owned = NativeWindow.owned(c);
            String phase = validate(c, l); // Recheck Stop/boot/deadline after all slow checks.
            if (l.live()) {
                if (pending || owned) {
                    acknowledgedToken = null;
                    phase = pending ? "blocked_unknown_dispatch" : "cleanup_pending";
                } else if (!l.request.token.equals(acknowledgedToken) || !acknowledgedWorker.getAsBoolean()) phase = "pending";
            }
            return response(phase, l);
        } catch (RuntimeException e) { return response("store_error", null); }
    }
    /** Called only after recovery has returned on the single guardian worker. */
    static synchronized String workerCheckpoint(Context c, boolean drained, java.util.function.BooleanSupplier liveWorker) {
        try {
            MaintenanceLease l = load(c);
            if (!drained || !liveWorker.getAsBoolean()) return "draining";
            if (!NativeWindow.close(c) || NativeWindow.owned(c)) return "cleanup_pending";
            if (l == null) return "none";
            boolean pending = unknownDispatch(c);
            String phase = validate(c, l);
            if (!l.live()) { acknowledgedToken = null; return phase; }
            if (pending) { acknowledgedToken = null; return "blocked_unknown_dispatch"; }
            phase = l.ack(Process.myUid(), clock.boot(), clock.now(), clock.installed(c),
                flag(c, "userStopped"), flag(c, "safetyPaused"), true, NativeWindow.owned(c));
            // Installed-version lookup above may be slow: validate samples time and flags once more.
            validate(c, l);
            if (!liveWorker.getAsBoolean()) { acknowledgedToken = null; return "draining"; }
            if (!prefs(c).edit().putString("phase", l.phase).commit()) return "store_error";
            acknowledgedToken = "active".equals(l.phase) ? l.request.token : null;
            acknowledgedWorker = liveWorker;
            return l.phase;
        } catch (RuntimeException e) { acknowledgedToken = null; return "store_error"; }
    }
    private static String response(String phase, MaintenanceLease l) {
        try {
            JSONObject j = new JSONObject().put("version", 1).put("phase", phase);
            if (l != null) {
                MaintenanceLease.Request r = l.request;
                j.put("token", r.token).put("boot", r.boot).put("deadlineElapsedMs", r.deadline)
                    .put("profileSha256", r.profile).put("releaseSha256", r.release)
                    .put("baselineVersion", r.baseline).put("targetVersion", r.target).put("ownerUid", l.owner);
            }
            return j.toString();
        } catch (Exception e) { return "{\"version\":1,\"phase\":\"store_error\"}"; }
    }
}
