package com.omarchytv.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omarchytv.home.link.LiveFeed;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v2.22 AI LIVE in the corner while you watch: a small card over the film (another app, or the cast player) with the
 * agent at work, red when one waits for your yes. The film keeps playing and keeps the remote; only when a question
 * arrives the card takes the remote for 15 s: OK opens the question, BACK leaves it for when you are back on the Home,
 * any other key gives the remote back to the film at once (that key press is not passed on).
 *
 * Needs "Display over other apps" (SYSTEM_ALERT_WINDOW) and Android 8+. "liveCorner": "off" (default) | "questions" |
 * "live" (also the agent at work). Off on the Home and on OmarchyTV's own screens (there the question opens as usual),
 * with the screen off, on the screensaver and during a night shift.
 */
final class LiveCorner {
    private static final String TAG = "LiveCorner";
    private static final long ASK_FOCUS_MS = 15_000, ACTIVE_MS = 600_000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static View card;
    private static TextView top, line;
    private static WindowManager.LayoutParams lp;
    private static Intent pendingAsk;
    private static long pendingDeadline;
    private static final Runnable unfocus = LiveCorner::unfocus;
    private static int resumed;   // OmarchyTV's own screens on top (main process)

    private LiveCorner() {
    }

    static void init(Context c) {
        app = c.getApplicationContext();
        LiveFeed.get().addWatcher(() -> main.post(LiveCorner::refresh));
        if (app instanceof android.app.Application) {
            ((android.app.Application) app).registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(android.app.Activity a) { if (!(a instanceof CastPlayerActivity)) resumed++; refresh(); }   // the film
                @Override public void onActivityPaused(android.app.Activity a) { if (!(a instanceof CastPlayerActivity)) resumed = Math.max(0, resumed - 1); main.postDelayed(LiveCorner::refresh, 400); }
                @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { }
                @Override public void onActivityStarted(android.app.Activity a) { }
                @Override public void onActivityStopped(android.app.Activity a) { }
                @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
                @Override public void onActivityDestroyed(android.app.Activity a) { }
            });
        }
    }

    static boolean overlayAllowed(Context c) {
        return Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(c);
    }

    private static boolean on() {
        if (app == null || !overlayAllowed(app) || "off".equals(LauncherConfig.get(app).liveCorner)) return false;
        PowerManager pm = app.getSystemService(PowerManager.class);
        return resumed == 0 && !OmarchyLink.homeShown() && !OmarchyLink.screensaverShown() && (pm == null || pm.isInteractive()) && !ShiftWake.quiet(app);
    }

    /** A question while a film is on: the corner asks instead of covering the film. False: show it the usual way. */
    static boolean ask(Intent question, long deadlineMs) {
        if (!on()) return false;
        pendingAsk = question;
        pendingDeadline = deadlineMs;
        refresh();
        if (card == null) return false;
        focus(true);
        main.removeCallbacks(unfocus);
        main.postDelayed(unfocus, ASK_FOCUS_MS);
        return true;
    }

    /** The Home is back: a question left with BACK opens now, if it has not expired. */
    static void homeBack() {
        main.post(() -> {
            hide();
            Intent q = pendingAsk;
            pendingAsk = null;
            if (q != null && (pendingDeadline <= 0 || System.currentTimeMillis() < pendingDeadline)) openQuestion(q);
        });
    }

    private static void openQuestion(Intent q) {
        try {
            app.startActivity(q.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "question: " + e);
        }
    }

    static void refresh() {
        if (!on()) {
            hide();
            return;
        }
        JSONObject a = null;
        boolean waits = false;
        try {
            JSONArray ag = LiveFeed.get().snapshot(System.currentTimeMillis(), 0).optJSONArray("agents");
            long now = System.currentTimeMillis();
            for (int i = 0; ag != null && i < ag.length(); i++) {
                JSONObject x = ag.getJSONObject(i);
                if (x.optJSONObject("waiting") != null) { a = x; waits = true; break; }
                if ("live".equals(LauncherConfig.get(app).liveCorner) && now - x.optLong("at") < ACTIVE_MS && (a == null || x.optLong("at") > a.optLong("at"))) a = x;
            }
        } catch (Exception ignored) {
        }
        if (a == null && pendingAsk == null) {
            hide();
            return;
        }
        show();
        Theme t = Theme.cur();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(waits || pendingAsk != null ? 0xEED7263D : 0xE6000000 | (t.bgDarker & 0xFFFFFF));
        card.setBackground(g);
        String who = a != null ? a.optString("agent") : pendingAsk.getStringExtra("from");
        top.setText((waits || pendingAsk != null ? "◆ " : "● ") + "AI LIVE · " + who);
        if (waits || pendingAsk != null) {
            String q = a != null && a.optJSONObject("waiting") != null ? a.optJSONObject("waiting").optString("question") : "";
            line.setText(app.getString(R.string.corner_waits, q) + (lp != null && (lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0 ? "   OK ›" : ""));
        } else {
            int p = a.optInt("progress", -1);
            line.setText(a.optString("text") + (p >= 0 ? "  · " + p + "%" : ""));
        }
    }

    private static int dp(int v) {
        return Math.round(v * app.getResources().getDisplayMetrics().density);
    }

    private static void show() {
        if (card != null || Build.VERSION.SDK_INT < 26) return;
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(10));
        top = new TextView(app);
        top.setTextColor(0xFFFFFFFF);
        top.setTextSize(15);
        top.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.setSingleLine(true);
        line = new TextView(app);
        line.setTextColor(0xFFFFFFFF);
        line.setTextSize(13);
        line.setMaxLines(2);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(top);
        box.addView(line);
        box.setFocusable(true);
        box.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_UP) return true;
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                Intent q = pendingAsk;
                pendingAsk = null;
                unfocus();
                if (q != null) openQuestion(q);
                refresh();
            } else {
                unfocus();   // BACK keeps the question for the Home; any other key goes back to the film
            }
            return true;
        });
        card = box;
        lp = new WindowManager.LayoutParams(dp(360), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(28);
        lp.y = dp(24);
        try {
            app.getSystemService(WindowManager.class).addView(card, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "overlay: " + e);
            card = null;
        }
    }

    private static void focus(boolean yes) {
        if (card == null || lp == null) return;
        if (yes) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try {
            app.getSystemService(WindowManager.class).updateViewLayout(card, lp);
            if (yes) card.requestFocus();
        } catch (RuntimeException ignored) {
        }
    }

    private static void unfocus() {
        main.removeCallbacks(unfocus);
        focus(false);
        if (card != null) refresh();
    }

    private static void hide() {
        main.removeCallbacks(unfocus);
        if (card == null) return;
        try {
            app.getSystemService(WindowManager.class).removeViewImmediate(card);
        } catch (RuntimeException ignored) {
        }
        card = null;
        lp = null;
    }
}
