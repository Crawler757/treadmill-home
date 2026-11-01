package com.treadmill.home;

import android.app.admin.DeviceAdminReceiver;

/**
 * Lets our app be the console's device owner (set once over ADB with
 * `dpm set-device-owner com.treadmill.home/.AdminReceiver`). See DeviceSetup for what it does
 * with that. The app is testOnly, so `dpm remove-active-admin` can undo it.
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
