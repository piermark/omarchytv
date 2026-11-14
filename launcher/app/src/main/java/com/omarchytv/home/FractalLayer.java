package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * v2.20 optional Home wallpaper: the same music-reactive Radiomarchy fractals as the screensaver, drawn with
 * the native {@link FractalView} (OpenGL ES, never a WebView — a WebView renderer crash took the launcher down
 * on a Sony Android 9 Mali-T860). Off by default; chosen in Settings → Wallpaper next to OmaFire. Picture
 * quality follows Radiomarchy's setting (auto by default). Added only while Home is on screen (paused on
 * {@link #onPause}); if the GPU cannot create a surface the still wallpaper underneath stays.
 */
final class FractalLayer {
    static final String ID = "radiomarchy:fractals";
    /** Failures in a row: after this many the layer stays off until the launcher restarts, never a crash loop. */
    private static final int MAX_FAILS = 2;
    private static FractalView view;
    private static int fails;

    private FractalLayer() {
    }

    static boolean is(String wallpaper) {
        return ID.equals(wallpaper);
    }

    /** From onResume: show it when the wallpaper is the Radiomarchy fractals (wall = the still WallpaperView). */
    static void onResume(Activity a, View wall) {
        if (!is(LauncherConfig.get(a).wallpaper) || wall == null || !(wall.getParent() instanceof ViewGroup)) {
            onPause();
            drop();
            return;
        }
        if (fails >= MAX_FAILS) {
            drop();
            return;
        }
        if (view != null) {
            try {
                view.onResume();
            } catch (Throwable e) {
                fail(view);
            }
            return;
        }
        FractalView v;
        try {
            v = new FractalView(a);
            v.setFocusable(false);
            v.setClickable(false);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int q = a.getSharedPreferences("radiomarchy", Context.MODE_PRIVATE).getInt("quality", 0);
            v.setQuality(q);
            v.setOnFail(() -> fail(v));
        } catch (Throwable e) {
            fails++;
            android.util.Log.w("OmarchyTV", "fractals wallpaper: no GL (" + fails + "/" + MAX_FAILS + "), still wallpaper stays");
            return;
        }
        ViewGroup parent = (ViewGroup) wall.getParent();
        parent.addView(v, parent.indexOfChild(wall) + 1, new ViewGroup.LayoutParams(wall.getLayoutParams()));
        view = v;
    }

    /** From onPause: stop drawing; the view stays so a return to Home is cheap. From onDestroy: {@link #drop}. */
    static void onPause() {
        FractalView v = view;
        if (v == null) return;
        try {
            v.onPause();
        } catch (Throwable ignored) {
        }
    }

    static void drop() {
        FractalView v = view;
        view = null;
        if (v == null) return;
        try {
            v.onPause();
        } catch (Throwable ignored) {
        }
        try {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private static void fail(FractalView v) {
        if (v == null) return;
        fails++;
        android.util.Log.w("OmarchyTV", "fractals wallpaper: GL failed (" + fails + "/" + MAX_FAILS
                + "), still wallpaper stays" + (fails >= MAX_FAILS ? ", animation off until restart" : ""));
        if (view == v) view = null;
        try {
            v.onPause();
        } catch (Throwable ignored) {
        }
        try {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        } catch (Throwable ignored) {
        }
    }
}
