package com.termux.app.privateadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;

/** Existing exported Activity only opens confirmation; an outside Intent cannot silently enable. */
public final class AutostartEntry {
    public static final String ENABLE = "com.termux.action.PRIVATE_ADB_AUTOSTART_ENABLE";
    private AutostartEntry() {}
    public static void onActivityStart(Activity activity) {
        if (PrivateAdbService.unlocked(activity) && NativeWindow.owned(activity))
            NativeWindow.cleanupIfExpired(activity, PrivateAdbService.bootId(), android.os.SystemClock.elapsedRealtime());
        Intent i = activity.getIntent();
        if (i != null && ENABLE.equals(i.getAction())) {
            i.setAction(null); // Consume this dedicated action, never interpret extras as commands.
            if (AutostartConfig.load(activity) == null) {
                new AlertDialog.Builder(activity).setMessage("Private recovery config is missing, disabled or invalid.")
                    .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            new AlertDialog.Builder(activity).setTitle("Enable private ADB recovery?")
                .setMessage("If the private daemon heartbeat stops, temporarily enable system Wireless debugging for at most 45 seconds, then close the owned window. Requires existing local pairing. Force-stop cannot guarantee cleanup. Stop in the notification disables recovery.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Enable", (dialog, which) -> {
                    if (activity.getSharedPreferences(PrivateAdbService.PREFS, 0).edit()
                        .putBoolean("userStopped", false).putBoolean("safetyPaused", false).commit())
                        PrivateAdbService.startIfEnabled(activity);
                }).show();
        } else PrivateAdbService.startIfEnabled(activity);
    }
}
