package com.treadmill.home;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.util.Log;

/**
 * Undoes what iFit's core app (eru) resets on every restart. Only works once our app is the
 * device owner; until then it does nothing.
 *
 * - Hides iFit's heavy background apps that the treadmill doesn't need (they used ~1 core and
 *   ~500 MB between them). Never touches glassos_service (motor), eru, rivendell (needed by
 *   glassos) or mithlond (clears the idle lockout).
 * - Turns ADB back on, which eru switches off at boot.
 * - Keeps our app as the home screen.
 */
final class DeviceSetup {

    private static final String TAG = "TreadmillSetup";
    // Empty on purpose: hiding gandalf + arda (Oct 27, 2026) made eru fall back at the next boot,
    // disabling glassos_service (the motor service) and rivendell. Hidden apps look uninstalled
    // to eru. Don't hide iFit apps again.
    static final String[] HIDE = {};
    // Recovery: un-hide what the hiding experiment hid. Only a device owner can (shell lacks MANAGE_USERS).
    static final String[] UNHIDE = {"com.ifit.gandalf", "com.ifit.arda"};
    // Note: apply() and report() above use HIDE/UNHIDE; the eru experiment uses restoreEruIfMotorDown.

    private DeviceSetup() {
    }

    static boolean isOwner(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    static ComponentName admin(Context context) {
        return new ComponentName(context, AdminReceiver.class);
    }

    /** Safe to call repeatedly; each step is independent so one failure doesn't stop the rest. */
    static void apply(Context context) {
        if (!isOwner(context)) return;
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = admin(context);

        for (String pkg : UNHIDE) {
            try {
                if (dpm.isApplicationHidden(admin, pkg)) {
                    Log.i(TAG, "unhide " + pkg + ": " + dpm.setApplicationHidden(admin, pkg, false));
                }
            } catch (Exception e) {
                Log.w(TAG, "could not unhide " + pkg + ": " + e);
            }
        }

        for (String pkg : HIDE) {
            try {
                if (!dpm.isApplicationHidden(admin, pkg)) {
                    boolean ok = dpm.setApplicationHidden(admin, pkg, true);
                    Log.i(TAG, "hide " + pkg + ": " + ok);
                }
            } catch (Exception e) {
                Log.w(TAG, "could not hide " + pkg + ": " + e);
            }
        }

        try {
            if (Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
                dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1");
                Log.i(TAG, "adb re-enabled");
            }
        } catch (Exception e) {
            Log.w(TAG, "could not re-enable adb: " + e);
        }

        try {
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, home, new ComponentName(context, HomeActivity.class));
        } catch (Exception e) {
            Log.w(TAG, "could not pin home screen: " + e);
        }
    }

    /**
     * Safety net for the eru-removal experiment: if the motor service hasn't answered for a while
     * after boot, put iFit's core app back. enableSystemApp re-installs a system app that was
     * uninstalled for this user; it does nothing if eru is still installed.
     */
    static void restoreEruIfMotorDown(Context context, long uptimeMs, long sinceGlassosAnsweredMs) {
        if (!isOwner(context) || uptimeMs < RESTORE_AFTER_BOOT_MS || sinceGlassosAnsweredMs < RESTORE_AFTER_BOOT_MS) {
            return;
        }
        try {
            context.getPackageManager().getPackageInfo(ERU, 0);
            return; // still installed; nothing to restore
        } catch (Exception notInstalled) {
            // fall through
        }
        try {
            DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
            dpm.enableSystemApp(admin(context), ERU);
            Log.w(TAG, "motor service down " + sinceGlassosAnsweredMs / 1000 + " s after boot: restored " + ERU);
        } catch (Exception e) {
            Log.w(TAG, "could not restore " + ERU + ": " + e);
        }
    }

    static final String ERU = "com.ifit.eru";
    private static final long RESTORE_AFTER_BOOT_MS = 4 * 60_000;

    /** One line per hidden app, for the status page. */
    static String report(Context context) {
        if (!isOwner(context)) return "owner      no (setup not applied)\n";
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        StringBuilder r = new StringBuilder("owner      yes\n");
        for (String pkg : UNHIDE) {
            boolean hidden;
            try {
                hidden = dpm.isApplicationHidden(admin(context), pkg);
            } catch (Exception e) {
                hidden = false;
            }
            r.append("hidden     ").append(pkg).append(hidden ? " yes" : " NO").append('\n');
        }
        return r.toString();
    }
}
