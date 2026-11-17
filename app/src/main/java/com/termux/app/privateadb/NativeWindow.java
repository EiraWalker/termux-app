package com.termux.app.privateadb;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;

/** Process-shared ownership record, durable before opening TLS. Never adopts a user's window. */
final class NativeWindow {
    private static final String PREFS = "private_adb_native_lease";
    private NativeWindow() {}
    static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }
    static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 8103, new Intent(c, AutostartReceiver.class)
            .setAction(AutostartReceiver.CLEANUP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static synchronized boolean owned(Context c) { return prefs(c).getBoolean("ownedFlag", false); }
    static synchronized boolean close(Context c) {
        if (!owned(c)) return true;
        try {
            Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 0);
            if (Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", -1) != 0) return false;
            if (!prefs(c).edit().putBoolean("ownedFlag", false).commit()) return false;
            ((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(alarm(c));
            return true;
        } catch (RuntimeException ignored) { return false; }
    }
    static synchronized boolean open(Context c, String boot, long deadline) {
        if (owned(c) || !AutostartPolicy.validBoot(boot) ||
            Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", -1) != 0) return false;
        if (!prefs(c).edit().putBoolean("ownedFlag", true).putString("bootId", boot)
            .putLong("deadlineElapsedMs", deadline).commit()) return false;
        try {
            AlarmManager am = (AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarm(c));
            else am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarm(c));
            if (!Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1) ||
                Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", -1) != 1) {
                close(c); return false;
            }
            return true;
        } catch (RuntimeException e) { close(c); return false; }
    }
    static synchronized void cleanupIfExpired(Context c, String boot, long now) {
        if (owned(c) && AutostartPolicy.leaseExpired(prefs(c).getString("bootId", ""), boot,
            prefs(c).getLong("deadlineElapsedMs", 0), now)) close(c);
    }
}
