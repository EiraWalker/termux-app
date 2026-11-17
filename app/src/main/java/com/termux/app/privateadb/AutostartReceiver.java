package com.termux.app.privateadb;

import android.content.*;
import android.os.SystemClock;

/** All broadcasts are private or system protected; never accepts arbitrary commands. */
public final class AutostartReceiver extends BroadcastReceiver {
    static final String CLEANUP = "com.termux.action.PRIVATE_ADB_WINDOW_CLEANUP";
    static final String RECHECK = "com.termux.action.PRIVATE_ADB_RECHECK";
    @Override public void onReceive(Context context, Intent intent) {
        if (!PrivateAdbService.unlocked(context)) return;
        String a = intent.getAction();
        if (CLEANUP.equals(a)) {
            NativeWindow.cleanupIfExpired(context, PrivateAdbService.bootId(), SystemClock.elapsedRealtime());
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_USER_UNLOCKED.equals(a) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) || RECHECK.equals(a)) {
            if (NativeWindow.owned(context) && !NativeWindow.close(context)) {
                PrivateAdbService.phase(context, "cleanup_pending"); return;
            }
            PrivateAdbService.startIfEnabled(context);
        }
    }
}
