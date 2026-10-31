package com.omarchytv.home.lights;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Lights hub follows the launcher being on screen. Every activity of the main process (Home, Cinemarchy, the
 * player, the YouTube embed, Settings) counts as "inside"; when none is started for {@link LightsLeave#DEBOUNCE_MS}
 * (another app such as Netflix or the YouTube app took over) or the screen goes off, the leave policy runs.
 * Coming back paints the theme again. The Philips Ambilight TV LEDs are not touched here.
 */
public final class LightsPresence implements Application.ActivityLifecycleCallbacks {
    private static LightsPresence installed;

    /** Hook: paint the hub for the activity now on screen (Home: theme; Cinemarchy: its dim level). */
    public interface Painter {
        void paint(Activity a);

        /** False when Ambilight is off: OmarchyTV never painted the hub, so leaving must not switch it off. */
        boolean active();
    }

    private final Context app;
    private final Painter painter;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int started;
    private boolean away;
    private final Runnable leave = this::leaveNow;

    private LightsPresence(Context app, Painter painter) {
        this.app = app;
        this.painter = painter;
    }

    /** Once per process, from Home's onCreate, before its first paint: takes the snapshot if one is needed. */
    public static void install(Activity a, Painter painter) {
        if (installed != null) return;
        installed = new LightsPresence(a.getApplicationContext(), painter);
        a.getApplication().registerActivityLifecycleCallbacks(installed);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        a.getApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) installed.screenOff();
                else installed.screenOn();
            }
        }, f);
        installed.snapshotIfNeeded();
    }

    private void snapshotIfNeeded() {
        if (!painter.active()) return;
        String policy = LightsStore.get(app, "leave");
        if (!LightsLeave.takeSnapshot(policy, !LightsStore.get(app, "snapshot").isEmpty())) return;
        LightsHub hub = Lights.current(app);
        if (hub == null || !hub.configured()) return;
        Lights.run(() -> {   // same single thread as the paint, so it reads the lights before they change
            try {
                String s = hub.snapshot();
                if (s != null) LightsStore.put(app, "snapshot", s);
            } catch (Throwable ignored) {
            }
        });
    }

    private void leaveNow() {
        h.removeCallbacks(leave);
        if (away) return;
        away = true;
        if (!painter.active()) return;
        LightsHub hub = Lights.current(app);
        if (hub == null || !hub.configured()) return;
        String snap = LightsStore.get(app, "snapshot");
        String act = LightsLeave.action(LightsStore.get(app, "leave"), !snap.isEmpty());
        if ("none".equals(act)) return;
        if ("restore".equals(act)) LightsStore.put(app, "snapshot", "");   // the next visit takes a fresh one
        Lights.run(() -> {
            try {
                if ("restore".equals(act)) hub.restore(snap);
                else hub.off();
            } catch (Throwable ignored) {   // hub offline: nothing to undo
            }
        });
    }

    private void come(Activity a) {
        h.removeCallbacks(leave);
        if (!away) return;
        away = false;
        snapshotIfNeeded();
        if (a != null && painter.active()) painter.paint(a);
    }

    private void screenOff() {
        leaveNow();
    }

    private void screenOn() {
        if (started > 0 && current != null) come(current);
    }

    private Activity current;

    @Override
    public void onActivityStarted(Activity a) {
        started = LightsLeave.count(started, true);
        current = a;
        come(a);
    }

    @Override
    public void onActivityStopped(Activity a) {
        started = LightsLeave.count(started, false);
        if (a == current) current = null;
        if (started == 0 && !a.isChangingConfigurations()) {
            h.removeCallbacks(leave);
            h.postDelayed(leave, LightsLeave.DEBOUNCE_MS);
        }
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityResumed(Activity a) {
        current = a;
    }

    @Override
    public void onActivityPaused(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
    }
}
