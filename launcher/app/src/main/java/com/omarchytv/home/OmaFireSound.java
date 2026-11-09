package com.omarchytv.home;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.util.HashMap;
import java.util.Map;

/**
 * v2.14.1 OmaFire sound on Android TV, played natively (#125). The animated wallpaper is a WebView that never gets the
 * remote's keys, so a WebAudio context that a TV starts "suspended" could never be woken: the fire was silent (Google
 * TV Streamer 4K). The page (omafire-audio.js) still decides everything as in Wes Grimes' FireAudio: the three loops
 * follow the fire's heat, a log or a stoke, the night around the lake. This only plays it, with SoundPool, from the
 * same WAV files in assets/omafire. Volumes glide (the page's 0.6 s easing, the 0.3 s fade), so it sounds the same.
 * Exposed to the page as window.OmaFireNative; only the local omafire page is ever loaded in that WebView.
 */
final class OmaFireSound {
    private static final String TAG = "OmarchyTV";
    private static final String[] FILES = {"fire-embers", "fire-crackle", "fire-roar", "fire-log", "fire-stoke", "cricket", "peep", "croak"};
    private static final String[] LOOPS = {"fire-embers", "fire-crackle", "fire-roar"};
    private static final long STEP_MS = 40;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<String, Integer> streams = new HashMap<>();
    private final Map<String, float[]> vol = new HashMap<>();   // name -> {now, target, per-step change}
    private final float[] master = {0f, 0f, 0f};
    private SoundPool pool;
    private int loaded;
    private boolean on, released, ticking;

    OmaFireSound(Context c) {
        pool = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build();
        pool.setOnLoadCompleteListener((p, id, status) -> {
            if (status == 0) loaded++;
            if (loaded == FILES.length && on) startLoops();
        });
        for (String f : FILES) {
            try (AssetFileDescriptor fd = c.getAssets().openFd("omafire/" + f + ".wav")) {
                ids.put(f, pool.load(fd, 1));
            } catch (Exception e) {   // a missing or compressed file: that sound stays silent, the rest plays
                Log.w(TAG, "omafire: suono " + f + " non caricato: " + e);
            }
        }
        for (String l : LOOPS) vol.put(l, new float[]{0f, 0f, 0f});
    }

    private boolean ready() {
        return !released && loaded > 0 && loaded >= ids.size();
    }

    private void startLoops() {
        for (String l : LOOPS) {
            Integer id = ids.get(l);
            if (id == null || streams.containsKey(l)) continue;
            int s = pool.play(id, 0f, 0f, 1, -1, 1f);   // loop forever, silent until the page sets its level
            if (s != 0) streams.put(l, s);
        }
        tick();
    }

    private void stopLoops() {
        for (Integer s : streams.values()) pool.stop(s);
        streams.clear();
    }

    /** Glide every level towards its target, a step every 40 ms, like WebAudio's linear ramps. */
    private void tick() {
        if (ticking || released) return;
        ticking = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean moving = glide(master);
                for (Map.Entry<String, float[]> e : vol.entrySet()) {
                    moving |= glide(e.getValue());
                    Integer s = streams.get(e.getKey());
                    float v = e.getValue()[0] * master[0];
                    if (s != null) pool.setVolume(s, v, v);
                }
                if (!on && master[0] <= 0f && master[1] <= 0f) stopLoops();
                if (moving && !released) ui.postDelayed(this, STEP_MS);
                else ticking = false;
            }
        }, STEP_MS);
    }

    private static boolean glide(float[] v) {
        if (v[0] == v[1]) return false;
        float d = v[1] - v[0];
        v[0] = Math.abs(d) <= Math.abs(v[2]) || v[2] == 0f ? v[1] : v[0] + v[2];
        return true;
    }

    private static void aim(float[] v, float target, float secs) {
        v[1] = Math.max(0f, Math.min(1f, target));
        float steps = Math.max(1f, secs * 1000f / STEP_MS);
        v[2] = (v[1] - v[0]) / steps;
    }

    // ---- what the page calls (from the WebView's thread: everything goes to the UI thread) ----

    /** Sound on (fade in over secs) or off (fade out, then the loops stop). */
    @JavascriptInterface
    public void setOn(boolean v, float secs) {
        ui.post(() -> {
            if (released) return;
            on = v;
            if (on && ready()) startLoops();
            aim(master, on ? 1f : 0f, secs);
            tick();
        });
    }

    /** A loop's level (0..1), reached over secs. */
    @JavascriptInterface
    public void loop(String name, float level, float secs) {
        ui.post(() -> {
            float[] v = vol.get(name);
            if (v == null || released) return;
            aim(v, level, secs);
            tick();
        });
    }

    /** A one-shot (a log, a stoke, a cricket...) at this volume. */
    @JavascriptInterface
    public void shot(String name, float level) {
        ui.post(() -> {
            Integer id = ids.get(name);
            if (id == null || released || !on || !ready()) return;
            float v = Math.max(0f, Math.min(1f, level)) * master[0];
            if (v > 0f) pool.play(id, v, v, 0, 0, 1f);
        });
    }

    /** From OmaFireLayer when the layer goes away: nothing keeps playing behind other apps. */
    void release() {
        released = true;
        ui.removeCallbacksAndMessages(null);
        try {
            stopLoops();
            pool.release();
        } catch (Throwable ignored) {
        }
        pool = null;
    }
}
