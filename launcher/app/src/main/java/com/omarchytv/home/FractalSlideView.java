package com.omarchytv.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The Home still-fractal slideshow (the same 12 JPEGs as {@link FractalSlides}), for Radiomarchy
 * demo mode when live GL is off or has been killed. Crossfade and a very gentle Ken Burns pan.
 * Decodes off the UI thread; RGB_565 and inSampleSize on low-RAM TVs.
 */
final class FractalSlideView extends FrameLayout {
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private final ImageView slotA, slotB;
    private boolean frontIsA = true;
    private int index;
    private boolean running;
    private boolean decodePending;
    private ObjectAnimator ken;
    private Bitmap heldA, heldB;

    FractalSlideView(Context c) {
        super(c);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        slotA = makeSlot(c);
        slotB = makeSlot(c);
        addView(slotA, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(slotB, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        slotB.setAlpha(0f);
    }

    private static ImageView makeSlot(Context c) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setFocusable(false);
        v.setClickable(false);
        v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setLayerType(LAYER_TYPE_HARDWARE, null);
        return v;
    }

    void start() {
        if (running) return;
        running = true;
        if (heldA == null && !decodePending) loadFirst();
        else {
            startKen(front());
            UI.removeCallbacks(tick);
            UI.postDelayed(tick, FractalSlides.INTERVAL_MS);
        }
    }

    void stop() {
        running = false;
        UI.removeCallbacks(tick);
        stopKen();
    }

    void drop() {
        stop();
        recycle(heldA);
        recycle(heldB);
        heldA = heldB = null;
        slotA.setImageBitmap(null);
        slotB.setImageBitmap(null);
    }

    private final Runnable tick = this::showNext;

    private ImageView front() {
        return frontIsA ? slotA : slotB;
    }

    private ImageView back() {
        return frontIsA ? slotB : slotA;
    }

    private void loadFirst() {
        decodePending = true;
        final Context app = getContext().getApplicationContext();
        com.omarchytv.home.lights.Lights.run(() -> {
            final Bitmap bm = FractalSlides.decode(app, 0);
            UI.post(() -> {
                decodePending = false;
                if (bm == null) return;
                recycle(heldA);
                heldA = bm;
                index = 0;
                slotA.setImageBitmap(bm);
                slotA.setAlpha(1f);
                slotB.setAlpha(0f);
                frontIsA = true;
                if (running) {
                    startKen(slotA);
                    UI.removeCallbacks(tick);
                    UI.postDelayed(tick, FractalSlides.INTERVAL_MS);
                }
            });
        });
    }

    private void showNext() {
        if (!running) return;
        if (decodePending) {
            UI.postDelayed(tick, FractalSlides.INTERVAL_MS);
            return;
        }
        decodePending = true;
        final int next = (index + 1) % FractalSlides.count();
        final Context app = getContext().getApplicationContext();
        com.omarchytv.home.lights.Lights.run(() -> {
            final Bitmap bm = FractalSlides.decode(app, next);
            UI.post(() -> {
                decodePending = false;
                if (!running) {
                    recycle(bm);
                    return;
                }
                if (bm == null) {
                    UI.postDelayed(tick, FractalSlides.INTERVAL_MS);
                    return;
                }
                ImageView incoming = back();
                ImageView outgoing = front();
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
                UI.removeCallbacks(tick);
                UI.postDelayed(tick, FractalSlides.INTERVAL_MS);
            });
        });
    }

    private void startKen(ImageView v) {
        stopKen();
        if (v == null) return;
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

    private void stopKen() {
        if (ken != null) {
            ken.cancel();
            ken = null;
        }
    }

    private void stopKen(ImageView v) {
        if (ken != null && ken.getTarget() == v) stopKen();
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }
}
