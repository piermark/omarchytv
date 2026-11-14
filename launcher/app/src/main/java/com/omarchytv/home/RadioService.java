package com.omarchytv.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.view.KeyEvent;

/**
 * v2.20 Radiomarchy: keeps the music playing while Radiomarchy is not on screen (Home, another app, the
 * screensaver). A foreground "media playback" service with one notification (title, artist, previous / play-pause
 * / next); it holds nothing itself: {@link RadioPlayer} does the playing. Stops itself when the music stops.
 */
public class RadioService extends Service {
    private static final String CH = "radiomarchy";
    private static final int ID = 0x0FA2;
    static final String ACT_TOGGLE = "com.omarchytv.home.radio.TOGGLE", ACT_NEXT = "com.omarchytv.home.radio.NEXT",
            ACT_PREV = "com.omarchytv.home.radio.PREV", ACT_STOP = "com.omarchytv.home.radio.STOP";
    private static boolean running;

    static void start(Context c) {
        if (running) {
            refresh(c);
            return;
        }
        try {
            Intent i = new Intent(c, RadioService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            running = true;
        } catch (Exception e) {
            // not allowed from the background on this Android: the music still plays while the launcher lives
        }
    }

    static void stop(Context c) {
        if (!running) return;
        running = false;
        try {
            c.stopService(new Intent(c, RadioService.class));
        } catch (Exception ignored) {
        }
    }

    static void refresh(Context c) {
        if (!running) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(ID, build(c));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        Notification n = build(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
        } catch (Exception e) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        String a = intent != null ? intent.getAction() : null;
        RadioPlayer p = RadioPlayer.peek();
        if (a != null && p != null) {
            switch (a) {
                case ACT_TOGGLE: p.toggle(); break;
                case ACT_NEXT: p.advance(1, true); break;
                case ACT_PREV: p.advance(-1, true); break;
                case ACT_STOP: p.stop(); break;
                default: break;
            }
        }
        if (p == null || p.current() == null) {
            running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Notification build(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                NotificationChannel ch = new NotificationChannel(CH, "Radiomarchy", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(c, CH);
        } else {
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_LOW);
        }
        RadioPlayer p = RadioPlayer.peek();
        RadioCatalog.Track t = p != null ? p.current() : null;
        boolean playing = p != null && p.playing();
        Intent open = new Intent(c, RadioActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        b.setSmallIcon(R.drawable.ln_music)
                .setContentTitle(t != null ? t.title : "Radiomarchy")
                .setContentText(t != null ? t.artist : "Omarchy Radio")
                .setSubText("Radiomarchy")
                .setOngoing(playing)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null, "⏮", pi(c, ACT_PREV, 2)).build())
                .addAction(new Notification.Action.Builder(null, playing ? "⏸" : "▶", pi(c, ACT_TOGGLE, 3)).build())
                .addAction(new Notification.Action.Builder(null, "⏭", pi(c, ACT_NEXT, 4)).build());
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (p != null && p.token() != null) style.setMediaSession(p.token());
        b.setStyle(style);
        return b.build();
    }

    private static PendingIntent pi(Context c, String action, int code) {
        Intent i = new Intent(c, RadioService.class).setAction(action);
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                : PendingIntent.getService(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Receives MEDIA_BUTTON when the MediaSession is not in the foreground (after YouTube took audio focus). */
    public static final class Buttons extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null || !Intent.ACTION_MEDIA_BUTTON.equals(i.getAction())) return;
            KeyEvent e = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (e == null || e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return;
            RadioPlayer p = RadioPlayer.peek();
            if (p != null) p.mediaKey(e.getKeyCode());
        }
    }
}
