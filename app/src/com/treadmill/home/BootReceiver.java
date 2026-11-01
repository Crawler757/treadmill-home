package com.treadmill.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the speed/incline bar after the console boots, and has it bring our home screen back. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startForegroundService(new Intent(context, OverlayService.class)
                    .putExtra(OverlayService.EXTRA_FROM_BOOT, true));
        }
    }
}
