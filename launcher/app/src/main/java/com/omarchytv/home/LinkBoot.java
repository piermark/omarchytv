package com.omarchytv.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Starting {@link OmarchyLink.KeepAlive} as a foreground service, at boot, after an update and from the launcher.
 * It runs only while Link is enabled AND at least one PC is paired: pairing is the user's opt-in to a TV that
 * stays reachable, so TVs that never pair keep no resident service (1 GB TVs). Every call here is wrapped so a
 * refused start can never throw out of the boot receiver or the activity (a crash at boot would count towards
 * {@link Watchdog}). No wakelock, no timer, no network.
 */
final class LinkBoot {
    private static final String TAG = "OmarchyLink";
    static final String CHANNEL = "omarchy_link";
    static final int NOTIFICATION_ID = 47800;

    private LinkBoot() {
    }

    /** BOOT_COMPLETED / MY_PACKAGE_REPLACED: start Link when it is wanted, whatever the default home is. */
    static void startAtBoot(Context c) {
        if (wanted(c)) startService(c);
    }

    /** Link enabled (omarchytv.json, default on) and at least one PC paired. False if either is unreadable. */
    static boolean wanted(Context c) {
        try {
            return LauncherConfig.get(c).linkEnabled && hasPairedPc(c);
        } catch (RuntimeException e) {
            Log.w(TAG, "Link state unreadable, keep-alive not started", e);
            return false;
        }
    }

    /** Reads link.json read-only (never creates it, unlike LinkStore): any entry under "pcs". */
    static boolean hasPairedPc(Context c) {
        File f = new File(c.getFilesDir(), "link.json");
        if (!f.isFile() || f.length() > (1 << 20)) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return pairedIn(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** link.json text has at least one entry under "pcs"; false for anything unreadable. */
    static boolean pairedIn(String linkJson) {
        try {
            JSONObject pcs = new JSONObject(linkJson).optJSONObject("pcs");
            return pcs != null && pcs.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** The keep-alive instance once it is in the foreground; main thread only. */
    static OmarchyLink.KeepAlive promoted;
    private static Handler main;

    /**
     * Pairings or the Link switch changed: start or stop the keep-alive to match the current state. Runs on the main
     * thread, the same thread as the service callbacks, so concurrent pairing callbacks are applied in order, and it
     * re-reads link.json and the config there instead of trusting a value computed earlier. It never calls
     * stopService: a service started with startForegroundService and stopped before its startForeground crashes
     * the app on Android 9+. A promoted instance stops itself; a start still pending stops itself in
     * onStartCommand, which promotes first and then re-checks {@link #wanted}. Never throws.
     */
    static void reconcile(Context c) {
        Context app = c.getApplicationContext();
        synchronized (LinkBoot.class) {
            if (main == null) main = new Handler(Looper.getMainLooper());
        }
        main.post(() -> {
            if (wanted(app)) {
                startService(app);
                return;
            }
            OmarchyLink.KeepAlive s = promoted;
            if (s != null) s.retire();
        });
    }

    /** startForegroundService on API 26+, startService below. Never throws. */
    static void startService(Context c) {
        try {
            Intent i = new Intent(c, OmarchyLink.KeepAlive.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {   // start refused (background limits, SecurityException): the in-process server still runs
            Log.w(TAG, "foreground start refused", e);
        }
    }

    /** Calls startForeground with the quiet notification. Returns false when the system refused. */
    static boolean promote(Service s) {
        try {
            Notification n = notification(s);
            if (Build.VERSION.SDK_INT >= 29) {
                s.startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                s.startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground refused", e);
            return false;
        }
    }

    private static Notification notification(Context c) {
        Notification.Builder b;
        // IMPORTANCE_LOW, not MIN: Android replaces a MIN foreground-service notification with a louder system one.
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                        c.getString(R.string.link_notification_channel), NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_LOW);
        }
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(c.getString(R.string.link_notification))
                .setOngoing(true)
                .build();
    }
}
