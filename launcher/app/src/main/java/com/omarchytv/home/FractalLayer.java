package com.omarchytv.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * v2.20 default Home wallpaper: a slideshow of still Radiomarchy fractals (bundled JPEGs), shown in a
 * hardware-accelerated {@link ImageView} with a slow crossfade and a very gentle Ken Burns pan. No
 * {@link android.opengl.GLSurfaceView} on Home — live GL fractals stay on the Radiomarchy full-screen
 * view and the screensaver. Music keeps playing underneath. Theme accents follow the current still.
 * OmaFire and a wallpaper the owner already picked stay selectable.
 */
final class FractalLayer {
    static final String ID = "radiomarchy:fractals";

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static FrameLayout box;
    private static ImageView slotA, slotB;
    private static boolean frontIsA = true;
    private static int index;
    private static Activity host;
    private static ObjectAnimator ken;
    private static boolean running;
    private static Bitmap heldA, heldB;

    private FractalLayer() {
    }

    static boolean is(String wallpaper) {
        return ID.equals(wallpaper);
    }

    /** From onResume: show the slideshow when the wallpaper is the Radiomarchy fractals. */
    static void onResume(Activity a, View wall) {
        if (!is(LauncherConfig.get(a).wallpaper) || wall == null || !(wall.getParent() instanceof ViewGroup)) {
            onPause();
            drop();
            if (a instanceof MainActivity) ((MainActivity) a).clearFractalAccent();
            return;
        }
        host = a;
        RadioPlayer.get(a).ensureWallpaperMusic();
        if (box != null) {
            applyDim();
            applyAccent(index);
            start();
            return;
        }
        ViewGroup parent = (ViewGroup) wall.getParent();
        FrameLayout layer = new FrameLayout(a);
        layer.setFocusable(false);
        layer.setClickable(false);
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ImageView ia = makeSlot(a);
        ImageView ib = makeSlot(a);
        layer.addView(ia, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer.addView(ib, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(layer, parent.indexOfChild(wall) + 1, new ViewGroup.LayoutParams(wall.getLayoutParams()));
        box = layer;
        slotA = ia;
        slotB = ib;
        frontIsA = true;
        index = 0;
        Bitmap first = FractalSlides.decode(a, 0);
        if (first == null) {
            drop();
            return;
        }
        heldA = first;
        slotA.setImageBitmap(first);
        slotA.setAlpha(1f);
        slotB.setAlpha(0f);
        applyDim();
        applyAccent(0);
        start();
    }

    private static ImageView makeSlot(Activity a) {
        ImageView v = new ImageView(a);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setFocusable(false);
        v.setClickable(false);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        return v;
    }

    /** From onPause: stop the timer and the pan; the views stay so a return to Home is cheap. */
    static void onPause() {
        running = false;
        H.removeCallbacks(tick);
        stopKen();
    }

    static void drop() {
        onPause();
        FrameLayout layer = box;
        box = null;
        slotA = slotB = null;
        host = null;
        recycle(heldA);
        recycle(heldB);
        heldA = heldB = null;
        if (layer == null) return;
        try {
            if (layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
        } catch (Throwable ignored) {
        }
    }

    /** Home tiles no longer fight a GL thread; kept so callers need not change. */
    static void yieldForUi() {
    }

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || box == null || host == null) return;
            showNext();
        }
    };

    private static void start() {
        if (running) return;
        running = true;
        startKen(front());
        H.removeCallbacks(tick);
        H.postDelayed(tick, FractalSlides.INTERVAL_MS);
    }

    private static ImageView front() {
        return frontIsA ? slotA : slotB;
    }

    private static ImageView back() {
        return frontIsA ? slotB : slotA;
    }

    private static void showNext() {
        Activity a = host;
        ImageView incoming = back();
        ImageView outgoing = front();
        if (a == null || incoming == null || outgoing == null) return;
        int next = (index + 1) % FractalSlides.count();
        Bitmap bm = FractalSlides.decode(a, next);
        if (bm == null) {
            H.postDelayed(tick, FractalSlides.INTERVAL_MS);
            return;
        }
        if (frontIsA) {
            recycle(heldB);
            heldB = bm;
        } else {
            recycle(heldA);
            heldA = bm;
        }
        incoming.setImageBitmap(bm);
        incoming.setAlpha(0f);
        incoming.animate().cancel();
        outgoing.animate().cancel();
        startKen(incoming);
        incoming.animate().alpha(1f).setDuration(FractalSlides.FADE_MS).setListener(null).start();
        outgoing.animate().alpha(0f).setDuration(FractalSlides.FADE_MS).setListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        stopKen(outgoing);
                        outgoing.setScaleX(1f);
                        outgoing.setScaleY(1f);
                        outgoing.setTranslationX(0f);
                        outgoing.setTranslationY(0f);
                    }
                }).start();
        frontIsA = !frontIsA;
        index = next;
        applyAccent(index);
        H.removeCallbacks(tick);
        H.postDelayed(tick, FractalSlides.INTERVAL_MS);
    }

    private static void startKen(ImageView v) {
        stopKen();
        if (v == null) return;
        // Alternate a very small pan so it never looks like a video, and stays cheap on the compositor.
        float dir = (index % 2 == 0) ? 1f : -1f;
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        ken = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dir * 18f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -dir * 10f));
        ken.setDuration(FractalSlides.INTERVAL_MS + FractalSlides.FADE_MS);
        ken.setInterpolator(new LinearInterpolator());
        ken.start();
    }

    private static void stopKen() {
        if (ken != null) {
            ken.cancel();
            ken = null;
        }
    }

    private static void stopKen(ImageView v) {
        if (ken != null && ken.getTarget() == v) stopKen();
    }

    private static void applyAccent(int i) {
        Activity a = host;
        if (!(a instanceof MainActivity)) return;
        int bg = Theme.cur() != null ? Theme.cur().bg : 0xFF111111;
        int[] acc = FractalSlides.accents(i, bg);
        ((MainActivity) a).applyFractalAccent(acc[0], acc[1]);
    }

    private static void applyDim() {
        Activity a = host;
        if (a == null) return;
        float dim = Surfaces.globalDim(Surfaces.dimLevel(LauncherConfig.get(a).wallpaperDim));
        PorterDuffColorFilter f = dim > 0
                ? new PorterDuffColorFilter(Color.argb(Math.round(dim * 255f), 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
                : null;
        if (slotA != null) slotA.setColorFilter(f);
        if (slotB != null) slotB.setColorFilter(f);
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }
}
