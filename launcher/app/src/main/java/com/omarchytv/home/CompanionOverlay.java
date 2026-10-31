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
 * blinking now and then and bobbing a few dp. It never takes D-pad focus on its own: the Blue key opens the chat;
 * ▶ past the last top-bar icon (or long-press Menu) selects it (a ring and "OK: chat" show), OK opens the chat,
 * any other key puts focus back on the bar. Touch / mouse can click it.
 * v2.14: Tom Ballard's own avatar (his 14 shapes, palette, eyes, poses through {@link GrokbotAvatar}), with the
 * webOS/Tizen behaviour: blinks (sometimes twice), glances around, falls asleep after two idle minutes (any key
 * wakes it), a slow burn-in drift, an unread dot, and Settings for shape, colour, size, badge and animation.
 * Built for @tcballard's Grok Bot Desktop Companion v0.1.0 (MIT); my own code.
 */
final class CompanionOverlay extends LinearLayout {
    private final GrokbotMark mark;
    private final TextView name;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float density;
    private boolean armed, running, anim = true, badge = true;
    private String label = "Grok Bot";
    private final long t0 = System.currentTimeMillis();
    private long lastAct = System.currentTimeMillis();

    private final Runnable bobber = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long t = System.currentTimeMillis() - t0;
            boolean asleep = "sleep".equals(mark.pose());
            mark.setTranslationY(asleep ? 0f : CompanionPlacement.bob(t) * density);
            // Slow burn-in drift: a few dp on a 10 minute loop (same curve as webos/app/companion.js drift()).
            double d = t / 600000.0 * Math.PI * 2;
            setTranslationX((float) (Math.sin(d) * 14 * density / 2));
            setTranslationY((float) (Math.sin(d * 2 + 1) * 8 * density / 2));
            h.postDelayed(this, 50);
        }
    };
    private final Runnable blinker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (CompanionPlacement.asleep(System.currentTimeMillis() - lastAct, anim)) {
                mark.pose("sleep");
                mark.gaze(0f, 0f);
            } else if ("idle".equals(mark.pose())) {
                double r = Math.random();
                if (mark.isAvatar() && r < 0.35) {   // a glance, then back to you
                    mark.gaze((float) (Math.round((Math.random() * 2 - 1) * 10) / 10.0),
                            (float) (Math.round((Math.random() * 1.4 - 0.8) * 10) / 10.0));
                    h.postDelayed(() -> mark.gaze(0f, 0f), 1600);
                } else if (r < 0.55) {   // idle squint instead of a blink
                    mark.narrow(0.5f);
                    h.postDelayed(() -> mark.narrow(1f), 1400);
                } else {
                    blinkOnce(0);
                    if (Math.random() < 0.25) blinkOnce(320);   // sometimes twice
                }
            }
            h.postDelayed(this, CompanionPlacement.blinkDelay(Math.random()));
        }
    };

    private void blinkOnce(long after) {
        h.postDelayed(() -> mark.blink(false), after);
        h.postDelayed(() -> mark.blink(true), after + 150);
    }

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
        setContentDescription("Grok Bot companion: Blue key, or right from the top bar then OK");
        setArmed(false);
    }

    /**
     * Shape and colour for a mark, as on webOS/Tizen: the Settings choice, else the PC bot, else Tom's blob in
     * his orange. Shared with the chat header.
     */
    static void paint(GrokbotMark m, LauncherConfig cfg) {
        boolean mirrored = cfg.grokbotBotName != null && !cfg.grokbotBotName.isEmpty();
        String shape = !cfg.companionShape.isEmpty() ? cfg.companionShape
                : mirrored && cfg.grokbotBotShape != null && !cfg.grokbotBotShape.isEmpty() ? cfg.grokbotBotShape : "blob";
        if (!cfg.companionColor.isEmpty()) {
            m.set(shape, GrokbotAvatar.fill(cfg.companionColor), GrokbotAvatar.eye(cfg.companionColor));
        } else if (mirrored && cfg.grokbotBotFill != 0) {
            m.set(shape, cfg.grokbotBotFill, GrokbotAvatar.eyeForFill(cfg.grokbotBotFill));
        } else {
            m.set(shape, GrokbotAvatar.fill("orange"), GrokbotAvatar.eye("orange"));
        }
    }

    void bind(LauncherConfig cfg) {
        Theme t = Theme.cur();
        boolean mirrored = cfg.grokbotBotName != null && !cfg.grokbotBotName.isEmpty();
        paint(mark, cfg);
        anim = cfg.companionAnim;
        badge = cfg.companionBadge;
        int px = Math.round(56 * CompanionPlacement.sizeScale(cfg.companionSize));
        mark.setLayoutParams(new LayoutParams(dp(px), dp(px)));
        mark.unread(CompanionChatActivity.unread(getContext(), cfg) > 0);
        label = mirrored ? cfg.grokbotBotName : "Grok Bot";
        name.setText(armed ? "OK: chat" : label);
        name.setVisibility(badge || armed ? VISIBLE : GONE);
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
        if (cfg.companionOverlay && anim) {
            start();
        } else {
            stop();
            mark.pose("idle");
            mark.gaze(0f, 0f);
        }
    }

    /** A key was pressed on the home screen: wake up if asleep. */
    void poke() {
        lastAct = System.currentTimeMillis();
        if ("sleep".equals(mark.pose())) mark.pose("idle");
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
        name.setVisibility(badge || on ? VISIBLE : GONE);
    }

    void start() {
        if (running || getVisibility() != VISIBLE) return;
        running = true;
        lastAct = System.currentTimeMillis();
        h.post(bobber);
        h.postDelayed(blinker, CompanionPlacement.blinkDelay(Math.random()));
    }

    void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        mark.setTranslationY(0);
        mark.blink(true);
        mark.narrow(1f);
        setTranslationX(0);
        setTranslationY(0);
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
