package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v2.14 Liftoff on the big screen: a PC build (`omarchy-link liftoff -- make`) or an update (the TV's own, or
 * LG/Samsung via the PC) as an Omarchy-style rocket countdown over Home. T-minus from the last run, stage and
 * progress; LIFTOFF (the rocket climbs away) when it worked; a red blinking ABORT with the last lines and an alarm
 * when it failed. Any key hides it (no key reaches Home while it is on screen); a new state shows it again.
 * Words and numbers: {@link LiftoffScript}.
 */
final class LiftoffView extends View {
    private static LiftoffView showing;
    private static String dismissedId = "", alarmedId = "";

    private JSONObject state = new JSONObject();
    private long receivedAt, endedAt;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final int bg, fg, fgDim, accent, green, red, yellow;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            String st = state.optString("state");
            long since = SystemClock.uptimeMillis() - endedAt;
            if ("ok".equals(st) && endedAt > 0 && since > 5000) {
                hide();
                return;
            }
            if ("failed".equals(st) && endedAt > 0 && since > 120_000) {
                hide();
                return;
            }
            h.postDelayed(this, 80);
        }
    };

    private LiftoffView(Context c) {
        super(c);
        Theme t = Theme.cur();
        bg = t.bgDarker;
        fg = t.fg;
        fgDim = t.fgDim;
        accent = t.accent;
        green = t.green;
        red = t.red;
        yellow = t.yellow;
        Typeface tf = CastUi.font(c);
        paint.setTypeface(tf);
        bold.setTypeface(Typeface.create(tf, Typeface.BOLD));
        setFocusable(false);
    }

    // ---- MainActivity hooks ----

    /**
     * A new state ("liftoff" from the PC, or the launcher's own update). resumed=false: Home is not on screen, so only
     * a failure is worth a toast over the app being watched.
     */
    static void onState(Activity a, FrameLayout root, JSONObject s, boolean resumed) {
        String st = s.optString("state");
        if (!"running".equals(st) && !"ok".equals(st) && !"failed".equals(st)) return;
        String id = s.optString("id");
        if ("failed".equals(st) && !id.equals(alarmedId)) {
            alarmedId = id;
            alarm();
            if (!resumed) {
                Toast.makeText(a, a.getString(R.string.liftoff_failed_toast, s.optString("name")), Toast.LENGTH_LONG).show();
            }
        }
        if (!resumed || root == null) return;
        boolean news = !"running".equals(st);   // the end always shows, even after the countdown was hidden
        if (id.equals(dismissedId) && !news) return;
        if (news && (id + st).equals(endShown)) return;   // the same ending again (e.g. after a reconnect)
        if (showing == null) {
            if ("ok".equals(st) && !id.equals(seenId)) return;   // nothing was on screen for it: no surprise rocket
            showing = new LiftoffView(a);
            root.addView(showing, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            showing.h.post(showing.tick);
        }
        seenId = id;
        if (news) endShown = id + st;
        showing.set(s);
    }

    private static String seenId = "", endShown = "";

    /** The top of dispatchKeyEvent: any key hides it; true = consumed. */
    static boolean consumeKey(KeyEvent e) {
        LiftoffView v = showing;
        if (v == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            dismissedId = v.state.optString("id");
            v.hide();
        }
        return true;
    }

    static void onDestroy() {
        if (showing != null) showing.hide();
    }

    private void set(JSONObject s) {
        String before = state.optString("state");
        state = s;
        receivedAt = SystemClock.uptimeMillis();
        if (!"running".equals(s.optString("state")) && (endedAt == 0 || "running".equals(before))) endedAt = receivedAt;
        if ("running".equals(s.optString("state"))) endedAt = 0;
        invalidate();
    }

    private void hide() {
        h.removeCallbacks(tick);
        if (getParent() instanceof FrameLayout) ((FrameLayout) getParent()).removeView(this);
        if (showing == this) showing = null;
    }

    private static void alarm() {
        new Thread(() -> {
            ToneGenerator g = null;
            try {
                g = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
                for (int i = 0; i < 3; i++) {
                    g.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 350);
                    Thread.sleep(500);
                }
            } catch (Exception ignored) {
            } finally {
                if (g != null) g.release();
            }
        }, "liftoff-alarm").start();
    }

    /** The update's stages in the TV's language; a build's own stage text stays as the PC sent it. */
    private String stageLabel(String s) {
        switch (s) {
            case "download": return getContext().getString(R.string.liftoff_stage_download);
            case "verify": return getContext().getString(R.string.liftoff_stage_verify);
            case "sign": return getContext().getString(R.string.liftoff_stage_sign);
            case "install": return getContext().getString(R.string.liftoff_stage_install);
            case "launch": return getContext().getString(R.string.liftoff_stage_launch);
            default: return s;
        }
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), hh = getHeight();
        long now = SystemClock.uptimeMillis();
        long since = (now - receivedAt) / 1000;
        String st = state.optString("state");
        boolean failed = "failed".equals(st), ok = "ok".equals(st);
        boolean blink = (now / 400) % 2 == 0;

        fill.setColor(0xE0000000 | (bg & 0xFFFFFF));
        c.drawRect(0, 0, w, hh, fill);
        if (failed && blink) {   // red alarm wash
            fill.setColor(0x33000000 | (red & 0xFFFFFF));
            c.drawRect(0, 0, w, hh, fill);
        }

        float bw = w * 0.74f, bh = hh * 0.7f, bx = (w - bw) / 2, by = (hh - bh) / 2;
        int frame = failed ? red : ok ? green : accent;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2, hh / 360f));
        paint.setColor(failed && !blink ? fgDim : frame);
        c.drawRect(new RectF(bx, by, bx + bw, by + bh), paint);
        paint.setStyle(Paint.Style.FILL);

        float small = hh / 34f;
        String kind = getContext().getString("update".equals(state.optString("kind")) ? R.string.liftoff_update : R.string.liftoff_build);
        String title = " " + getContext().getString(R.string.liftoff_title) + " · " + kind + " · " + state.optString("name") + " ";
        paint.setTextSize(small);
        float tw = paint.measureText(title);
        fill.setColor(0xFF000000 | (bg & 0xFFFFFF));
        c.drawRect(bx + small, by - small * 0.7f, bx + small + tw, by + small * 0.4f, fill);
        paint.setColor(frame);
        c.drawText(title, bx + small, by + small * 0.3f, paint);

        // Rocket on the left; at liftoff it climbs out of the box.
        float rs = hh / 26f;
        bold.setTextSize(rs);
        float rx = bx + bw * 0.08f, ry = by + bh * 0.22f;
        if (ok && endedAt > 0) {
            float t = (now - endedAt) / 1000f;
            ry -= t * t * hh * 0.18f;
        }
        bold.setColor(failed ? red : fg);
        for (int i = 0; i < LiftoffScript.ROCKET.length; i++) c.drawText(LiftoffScript.ROCKET[i], rx, ry + i * rs * 1.15f, bold);
        String[] flame = LiftoffScript.FLAME[ok ? 1 : 0];
        if (!failed) {
            bold.setColor(ok ? yellow : accent);
            float fy = ry + LiftoffScript.ROCKET.length * rs * 1.15f;
            c.drawText(flame[(int) ((now / 120) % 2)], rx, fy, bold);
            if (ok) c.drawText(flame[(int) ((now / 120 + 1) % 2)], rx, fy + rs * 1.15f, bold);
        }

        // Countdown, stage and bar on the right.
        float cx = bx + bw * 0.38f;
        String clock = ok ? getContext().getString(R.string.liftoff_ok)
                : failed ? getContext().getString(R.string.liftoff_abort) : LiftoffScript.clock(state, since);
        bold.setTextSize(hh / 9f);
        boolean dim = LiftoffScript.finalCount(state, since) && !blink;
        bold.setColor(failed ? red : ok ? green : dim ? fgDim : accent);
        c.drawText(clock, cx, by + bh * 0.36f, bold);

        paint.setTextSize(small * 1.1f);
        paint.setColor(fg);
        String stage = stageLabel(state.optString("stage"));
        if (failed && state.has("code")) {
            stage = (stage.isEmpty() ? "" : stage + " · ") + getContext().getString(R.string.liftoff_exit, String.valueOf(state.optInt("code")));
        }
        if (!stage.isEmpty()) c.drawText(stage, cx, by + bh * 0.47f, paint);
        String bar = LiftoffScript.bar(LiftoffScript.progress(state, since), 24);
        if (!bar.isEmpty() && !failed) {
            paint.setColor(ok ? green : accent);
            c.drawText(bar, cx, by + bh * 0.57f, paint);
        }
        if (!failed && !ok) {
            paint.setColor(fgDim);
            paint.setTextSize(small * 0.9f);
            c.drawText("T+" + LiftoffScript.mmss(LiftoffScript.elapsed(state, since)), cx, by + bh * 0.65f, paint);
        }

        // Failure: the last lines of output.
        JSONArray tail = state.optJSONArray("tail");
        if (failed && tail != null) {
            paint.setTextSize(small * 0.85f);
            paint.setColor(fg);
            float ty = by + bh * 0.66f;
            for (int i = 0; i < tail.length(); i++) {
                String line = tail.optString(i);
                float max = bx + bw - small - (bx + bw * 0.08f);
                while (line.length() > 4 && paint.measureText(line) > max) line = line.substring(0, line.length() - 2);
                c.drawText(line, bx + bw * 0.08f, ty + i * small * 1.05f, paint);
            }
        }

        paint.setTextSize(small * 0.8f);
        paint.setColor(fgDim);
        String hint = getContext().getString(R.string.liftoff_hint);
        c.drawText(hint, bx + bw - paint.measureText(hint) - small, by + bh - small * 0.6f, paint);
    }
}
