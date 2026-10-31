package com.omarchytv.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v2.13 floating Grok Bot companion on the launcher: the mirrored bot (or Grok Bot's orange ball) in a corner,
 * blinking now and then and bobbing a few dp. It never takes D-pad focus on its own: long-press Menu arms it
 * (a ring shows), OK opens the chat, any other key disarms it. Touch / mouse can click it directly.
 * Built for @tcballard's Grok Bot Desktop Companion v0.1.0 (MIT); my own code.
 */
final class CompanionOverlay extends LinearLayout {
    private final GrokbotMark mark;
    private final TextView name;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float density;
    private boolean armed, running;
    private String label = "Grok Bot";
    private final long t0 = System.currentTimeMillis();

    private final Runnable bobber = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            mark.setTranslationY(CompanionPlacement.bob(System.currentTimeMillis() - t0) * density);
            h.postDelayed(this, 50);
        }
    };
    private final Runnable blinker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (Math.random() < 0.3) {   // idle squint instead of a blink
                mark.narrow(0.5f);
                h.postDelayed(() -> mark.narrow(1f), 1400);
            } else {
                mark.blink(false);
                h.postDelayed(() -> mark.blink(true), 150);
            }
            h.postDelayed(this, CompanionPlacement.blinkDelay(Math.random()));
        }
    };

    CompanionOverlay(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setFocusable(false);
        setClickable(true);
        int p = dp(6);
        setPadding(p, p, p, p);
        mark = new GrokbotMark(c);
        addView(mark, new LayoutParams(dp(56), dp(56)));
        name = new TextView(c);
        name.setTextSize(10);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        addView(name, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        setContentDescription("Grok Bot companion: long-press Menu, then OK");
        setArmed(false);
    }

    void bind(LauncherConfig cfg) {
        Theme t = Theme.cur();
        boolean mirrored = cfg.grokbotBotName != null && !cfg.grokbotBotName.isEmpty();
        mark.set(mirrored ? cfg.grokbotBotShape : "orb", mirrored && cfg.grokbotBotFill != 0 ? cfg.grokbotBotFill : GrokbotMark.GROKBOT_ORANGE);
        label = mirrored ? cfg.grokbotBotName : "Grok Bot";
        name.setText(armed ? "OK: chat" : label);
        name.setTextColor(t.fgBright);
        name.setShadowLayer(4f, 0f, 1f, 0xCC000000);   // readable on bright wallpapers
        setVisibility(cfg.companionOverlay ? VISIBLE : GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, CompanionPlacement.gravity(cfg.companionCorner));
        int side = dp(24 + cfg.companionOffsetX);
        // Bottom corners float above the dock row and the companion home tile; top corners below the status bar.
        int vert = dp(CompanionPlacement.safeVertical(cfg.companionCorner, cfg.companionOffsetY, 196, 70));   // clears the dock and the Grok Bot home tile
        lp.setMargins(side, vert, side, vert);
        setLayoutParams(lp);
        if (cfg.companionOverlay) start();
        else stop();
    }

    boolean isArmed() {
        return armed;
    }

    void setArmed(boolean on) {
        armed = on;
        Theme t = Theme.cur();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(on ? Theme.withAlpha(t.bg, 0.85f) : 0x00000000);
        g.setStroke(on ? dp(2) : 0, t.activeBorder);
        setBackground(g);
        name.setText(on ? "OK: chat" : label);
    }

    void start() {
        if (running || getVisibility() != VISIBLE) return;
        running = true;
        h.post(bobber);
        h.postDelayed(blinker, CompanionPlacement.blinkDelay(Math.random()));
    }

    void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        mark.setTranslationY(0);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    @SuppressWarnings("unused")
    private static boolean isVisible(View v) {
        return v.getVisibility() == VISIBLE;
    }
}
