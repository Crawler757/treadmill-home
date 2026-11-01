package com.treadmill.home;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Read-only status page on the home network (http://<treadmill>:8765/), so the PC can see how
 * the console is doing after a restart even though iFit turns ADB off at boot. Reports only;
 * it accepts no commands.
 */
final class StatusServer {

    static final int PORT = 8765;
    private static final String TAG = "TreadmillStatus";
    private static final String[] IFIT_APPS = {
            "com.ifit.glassos_service", "com.ifit.eru", "com.ifit.rivendell", "com.ifit.mithlond",
            "com.ifit.arda", "com.ifit.gandalf", "com.ifit.launcher", "com.ifit.standalone"};

    private final Context context;
    private final Supplier<String> overlayState;
    private volatile ServerSocket server;

    StatusServer(Context context, Supplier<String> overlayState) {
        this.context = context.getApplicationContext();
        this.overlayState = overlayState;
    }

    void start() {
        Thread t = new Thread(() -> {
            try (ServerSocket s = new ServerSocket(PORT)) {
                server = s;
                while (!s.isClosed()) {
                    try (Socket client = s.accept()) {
                        client.setSoTimeout(3000);
                        // Read and ignore the request line and headers.
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip */ }
                        byte[] body = report().getBytes(StandardCharsets.UTF_8);
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.write(body);
                        out.flush();
                    } catch (Exception e) {
                        if (!s.isClosed()) Log.w(TAG, "request failed: " + e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "status page stopped: " + e);
            }
        }, "status-page");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
    }

    private String report() {
        StringBuilder r = new StringBuilder();
        r.append("time       ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        long up = SystemClock.elapsedRealtime() / 1000;
        r.append("uptime     ").append(up / 3600).append("h ").append(up / 60 % 60).append("m\n");

        ActivityManager am = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        r.append("memory     ").append(mem.availMem / (1024 * 1024)).append(" MB free of ")
                .append(mem.totalMem / (1024 * 1024)).append(" MB").append(mem.lowMemory ? " (LOW)" : "")
                .append('\n');

        r.append("dns        ").append(Settings.Global.getString(context.getContentResolver(), "private_dns_mode"))
                .append(' ').append(Settings.Global.getString(context.getContentResolver(), "private_dns_specifier"))
                .append('\n');
        r.append("adb        ").append(Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1
                ? "on" : "off").append('\n');
        r.append(overlayState.get());

        r.append("\niFit apps:\n");
        PackageManager pm = context.getPackageManager();
        for (String pkg : IFIT_APPS) {
            String state;
            try {
                int s = pm.getApplicationEnabledSetting(pkg);
                state = s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ? "OFF (disabled by us)"
                        : s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ? "OFF"
                        : s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED ? "OFF (until used)"
                        : "on";
                // getApplicationEnabledSetting can say DEFAULT for an app that is installed but
                // disabled in its manifest/state; ApplicationInfo.enabled is the final answer.
                if (state.equals("on") && !pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled) {
                    state = "OFF";
                }
            } catch (IllegalArgumentException | PackageManager.NameNotFoundException e) {
                // Hidden apps (DeviceSetup) look uninstalled to everyone else.
                state = "hidden or not installed";
            }
            r.append("  ").append(String.format(Locale.US, "%-26s", pkg)).append(state).append('\n');
        }
        return r.toString();
    }
}
