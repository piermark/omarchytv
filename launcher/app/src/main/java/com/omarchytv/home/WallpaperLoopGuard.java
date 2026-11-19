package com.omarchytv.home;

import android.content.Context;

/**
 * v2.21 the looping Home wallpaper (Eyes Wide) on a TV that cannot keep up: after a 3 s warm-up, a 10 s window that
 * shows fewer than half the clip's frames ends the loop (the clip finishes, the still stays) and is remembered, so
 * this TV keeps the still from then on. "Always-animated wallpaper" toggled in Settings clears it.
 */
final class WallpaperLoopGuard {
    static final String PREF = "wallpaper.loopStutter";
    static final long WARMUP_MS = 3000, WINDOW_MS = 10_000;
    private final int fps;
    private long start = -1, windowStart = -1;
    private int frames;
    private boolean tripped;

    WallpaperLoopGuard(int fps) {
        this.fps = Math.max(1, fps);
    }

    /** One frame shown at {@code nowMs}; true once, when the loop should stop. */
    boolean frame(long nowMs) {
        if (tripped) return false;
        if (start < 0) start = nowMs;
        if (nowMs - start < WARMUP_MS) return false;
        if (windowStart < 0) {
            windowStart = nowMs;
            frames = 0;
        }
        frames++;
        long span = nowMs - windowStart;
        if (span < WINDOW_MS) return false;
        boolean slow = frames < fps * (span / 1000.0) * 0.5;
        windowStart = nowMs;
        frames = 0;
        if (slow) tripped = true;
        return slow;
    }

    static boolean stuttered(Context c) {
        try {
            return c.getSharedPreferences("omarchytv", Context.MODE_PRIVATE).getBoolean(PREF, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void remember(Context c) {
        try {
            c.getSharedPreferences("omarchytv", Context.MODE_PRIVATE).edit().putBoolean(PREF, true).apply();
        } catch (RuntimeException ignored) {
        }
    }

    static void forget(Context c) {
        try {
            c.getSharedPreferences("omarchytv", Context.MODE_PRIVATE).edit().remove(PREF).apply();
        } catch (RuntimeException ignored) {
        }
    }
}
