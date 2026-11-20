package com.omarchytv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omarchytv.home.link.LiveFeed;
import com.omarchytv.home.link.NightShift;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * v2.22 AI LIVE: the agents' work as a TV channel. "● LIVE", one agent on camera (what it does, progress, the preview
 * of its work), the studios on the right, the news ticker at the bottom. The director cuts every 12 s to the next
 * agent that is working, and at once to one that waits for the owner's yes (OK opens its question). LEFT / RIGHT
 * change camera by hand.
 *
 * REPLAY (the VCR): the night shift's diary played back like a recording: OK / PLAY pauses, FF doubles the speed,
 * LEFT / RIGHT jump to the previous / next highlight (a failure, a step left for the morning), the timeline at the
 * bottom; at the end the decisions that wait open on the shift page.
 *
 * Plain views and one bitmap at a time: light on 2 GB TVs; nothing runs while the screen is not shown.
 */
public class LiveActivity extends Activity {
    static final String EXTRA_REPLAY = "replay";
    static final long CUT_MS = 12_000, HAND_MS = 30_000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Theme t;
    private boolean replay, paused;
    private int speed = 1, cursor;
    private TextView badge, clock, name, line, since, question, previewText, studios, ticker, hint, timelineLabel;
    private View progress, progressTrack, timeline;
    private ImageView previewImage;
    private FrameLayout tickerBox;
    private ValueAnimator tickerAnim;
    private String onCamera = "", lastTicker = "", imageUrl = "";
    private long cutAt, handUntil;
    private JSONObject snap;
    private List<JSONObject> tape = new ArrayList<>();

    static Intent intent(Context c, boolean replay) {
        return new Intent(c, LiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_REPLAY, replay);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (replay) step();
            else refresh();
            h.postDelayed(this, replay ? Math.max(400, 2500 / speed) : 1000);
        }
    };

    private final Runnable blink = new Runnable() {
        @Override public void run() {
            badge.setAlpha(badge.getAlpha() > 0.6f ? 0.35f : 1f);
            h.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        replay = getIntent().getBooleanExtra(EXTRA_REPLAY, false);
        t = Theme.cur();
        build();
        if (replay) loadTape();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(frame);
        h.post(blink);
        LiveFeed.get().onChange(() -> h.post(() -> { if (!replay) refresh(); }));
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(frame);
        h.removeCallbacks(blink);
        LiveFeed.get().onChange(null);
        if (tickerAnim != null) tickerAnim.cancel();
        super.onPause();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView tv(float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(CastUi.font(this), bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return v;
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);
        root.setPadding(dp(36), dp(22), dp(36), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        badge = tv(16, 0xFFFFFFFF, true);
        badge.setText(replay ? "▶ REPLAY" : "● LIVE");
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(replay ? t.accent : t.red);
        bb.setCornerRadius(dp(4));
        badge.setBackground(bb);
        badge.setPadding(dp(10), dp(3), dp(10), dp(3));
        top.addView(badge);
        TextView chan = tv(18, t.fgBright, true);
        chan.setText(replay ? "  AI LIVE · " + getString(R.string.live_replay) : "  AI LIVE · OmarchyTV");
        top.addView(chan, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clock = tv(18, t.fg, false);
        top.addView(clock);
        root.addView(top);

        LinearLayout middle = new LinearLayout(this);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        ml.topMargin = dp(18);
        root.addView(middle, ml);

        LinearLayout cam = new LinearLayout(this);   // the camera
        cam.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(t.bgDark);
        cb.setStroke(dp(2), t.muted);
        cam.setBackground(cb);
        cam.setPadding(dp(28), dp(22), dp(28), dp(22));
        middle.addView(cam, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.2f));
        name = tv(34, t.fgBright, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        cam.addView(name);
        line = tv(22, t.fg, false);
        line.setMaxLines(2);
        line.setEllipsize(TextUtils.TruncateAt.END);
        cam.addView(line);
        progressTrack = new View(this);
        progressTrack.setBackgroundColor(t.bgLight);
        FrameLayout pf = new FrameLayout(this);
        pf.addView(progressTrack, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        progress = new View(this);
        progress.setBackgroundColor(t.accent);
        pf.addView(progress, new FrameLayout.LayoutParams(0, dp(6)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pl.topMargin = dp(10);
        cam.addView(pf, pl);
        since = tv(15, t.muted, false);
        since.setPadding(0, dp(6), 0, 0);
        cam.addView(since);
        question = tv(20, t.fgBright, true);
        question.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable qb = new GradientDrawable();
        qb.setColor(t.red & 0x55FFFFFF);
        qb.setStroke(dp(2), t.red);
        question.setBackground(qb);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ql.topMargin = dp(14);
        cam.addView(question, ql);
        FrameLayout pv = new FrameLayout(this);
        LinearLayout.LayoutParams pvl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        pvl.topMargin = dp(14);
        cam.addView(pv, pvl);
        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.FIT_START);
        pv.addView(previewImage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewText = tv(15, t.fg, false);
        previewText.setTypeface(android.graphics.Typeface.MONOSPACE);
        pv.addView(previewText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        studios = tv(17, t.fg, false);   // the studios
        studios.setPadding(dp(22), dp(6), 0, 0);
        studios.setLineSpacing(dp(6), 1f);
        middle.addView(studios, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        timeline = new View(this) {   // REPLAY: the tape with its highlights
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), hh = getHeight(), n = tape.size();
                p.setColor(t.bgLight);
                c.drawRect(0, hh / 2f - dp(2), w, hh / 2f + dp(2), p);
                for (int i = 0; i < n; i++) {
                    String k = tape.get(i).optString("kind");
                    if (!"fail".equals(k) && !"queued".equals(k)) continue;
                    p.setColor("fail".equals(k) ? t.red : t.yellow);
                    float x = n <= 1 ? 0 : i * (w - 1f) / (n - 1);
                    c.drawRect(x - dp(2), 0, x + dp(2), hh, p);
                }
                p.setColor(t.accent);
                float x = n <= 1 ? 0 : cursor * (w - 1f) / (n - 1);
                c.drawCircle(x, hh / 2f, dp(7), p);
            }
        };
        timeline.setVisibility(replay ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18));
        tl.topMargin = dp(12);
        root.addView(timeline, tl);
        timelineLabel = tv(14, t.muted, false);
        timelineLabel.setVisibility(replay ? View.VISIBLE : View.GONE);
        root.addView(timelineLabel);

        tickerBox = new FrameLayout(this);   // the news ticker
        tickerBox.setBackgroundColor(t.bgDark);
        tickerBox.setClipChildren(true);
        ticker = tv(18, t.fgBright, false);
        ticker.setSingleLine(true);
        ticker.setPadding(0, dp(6), 0, dp(6));
        tickerBox.addView(ticker, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.topMargin = dp(12);
        root.addView(tickerBox, kl);
        hint = tv(14, t.muted, false);
        hint.setPadding(0, dp(6), 0, 0);
        hint.setText(replay ? "OK ⏯   ⏩ ×2   ◀ ▶ highlights   BACK" : "◀ ▶ camera   OK " + getString(R.string.live_answer) + "   BACK");
        root.addView(hint);
        setContentView(root);
    }

    // ---- LIVE --------------------------------------------------------------------------------------------------

    private void refresh() {
        long now = System.currentTimeMillis();
        clock.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(now)));
        try {
            snap = LiveFeed.get().snapshot(now, 40);
        } catch (Exception e) {
            return;
        }
        JSONArray a = snap.optJSONArray("agents");
        if (a == null || a.length() == 0) {
            onCamera = "";
            name.setText("AI LIVE");
            line.setText(getString(R.string.live_empty));
            question.setVisibility(View.GONE);
            since.setText("");
            showProgress(-1);
            showPreview(null);
            studios.setText("");
            setTicker("");
            return;
        }
        JSONObject cur = null, waiting = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x.optString("agent").equals(onCamera)) cur = x;
            if (waiting == null && x.has("waiting")) waiting = x;
        }
        if (waiting != null && now >= handUntil) cur = waiting;   // breaking: whoever waits for a yes
        else if (cur == null || (now - cutAt > CUT_MS && now >= handUntil)) cur = next(a, cur, 1, now);
        if (cur == null) cur = a.optJSONObject(0);
        if (!cur.optString("agent").equals(onCamera)) cutAt = now;
        onCamera = cur.optString("agent");
        showAgent(cur, now);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            boolean on = x.optString("agent").equals(onCamera);
            String dot = x.has("waiting") ? "◆" : now - x.optLong("at") < 600_000 ? "●" : "○";
            s.append(on ? "▶ " : "   ").append(dot).append(' ').append(x.optString("agent")).append('\n');
        }
        studios.setText(s.toString().trim());
        JSONArray ev = snap.optJSONArray("events");
        StringBuilder k = new StringBuilder();
        for (int i = ev == null ? -1 : ev.length() - 1; i >= 0 && k.length() < 600; i--) {
            JSONObject e = ev.optJSONObject(i);
            k.append(time(e.optLong("at"))).append(' ').append(mark(e.optString("kind"))).append(' ')
                    .append(e.optString("agent")).append(" · ").append(e.optString("text")).append("     ◆     ");
        }
        setTicker(k.toString());
    }

    private JSONObject next(JSONArray a, JSONObject cur, int dir, long now) {
        int start = 0;
        for (int i = 0; cur != null && i < a.length(); i++) if (a.optJSONObject(i).optString("agent").equals(cur.optString("agent"))) start = i;
        for (int k = 1; k <= a.length(); k++) {
            JSONObject x = a.optJSONObject(((start + dir * k) % a.length() + a.length()) % a.length());
            if (now - x.optLong("at") < 600_000 || x.has("waiting")) return x;   // only who is working
        }
        return cur;
    }

    private void showAgent(JSONObject x, long now) {
        name.setText(x.optString("agent"));
        line.setText(x.optString("text").isEmpty() ? "…" : x.optString("text"));
        long on = Math.max(0, now - x.optLong("since", now)) / 60_000;
        since.setText(getString(R.string.live_on_air, on));
        showProgress(x.optInt("progress", -1));
        JSONObject w = x.optJSONObject("waiting");
        question.setVisibility(w == null ? View.GONE : View.VISIBLE);
        if (w != null) question.setText("◆ " + getString(R.string.live_waits) + ": " + w.optString("question") + "   [OK]");
        showPreview(x.optJSONObject("preview"));
    }

    private void showProgress(int pct) {
        boolean on = pct >= 0;
        progressTrack.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        progress.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        if (!on) return;
        progressTrack.post(() -> {
            ViewGroup.LayoutParams lp = progress.getLayoutParams();
            lp.width = progressTrack.getWidth() * Math.max(0, Math.min(100, pct)) / 100;
            progress.setLayoutParams(lp);
        });
    }

    private void showPreview(JSONObject p) {
        String kind = p == null ? "" : p.optString("kind");
        if ("image".equals(kind)) {
            previewText.setText("");
            String url = p.optString("url");
            if (!url.equals(imageUrl)) {
                imageUrl = url;
                previewImage.setImageDrawable(null);
                int w = Math.max(320, previewImage.getWidth()), hh = Math.max(180, previewImage.getHeight());
                new Thread(() -> {
                    Bitmap bm = WidgetGrid.fetch(url, w, hh);
                    h.post(() -> { if (url.equals(imageUrl) && bm != null) previewImage.setImageBitmap(bm); });
                }, "live-preview").start();
            }
            return;
        }
        imageUrl = "";
        previewImage.setImageDrawable(null);
        if ("diff".equals(kind) || "text".equals(kind)) {
            previewText.setText("diff".equals(kind) ? AgentQuestionActivity.coloured(p.optString("text"), t.green, t.red) : p.optString("text"));
        } else if ("chart".equals(kind)) {
            JSONArray pts = p.optJSONArray("points");
            StringBuilder s = new StringBuilder(p.optString("label")).append("\n");
            String bars = "▁▂▃▄▅▆▇█";
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < pts.length(); i++) { lo = Math.min(lo, pts.optDouble(i)); hi = Math.max(hi, pts.optDouble(i)); }
            for (int i = 0; i < pts.length(); i++) {
                int k = hi > lo ? (int) Math.round((pts.optDouble(i) - lo) / (hi - lo) * 7) : 3;
                s.append(bars.charAt(k));
            }
            previewText.setText(s.toString());
        } else {
            previewText.setText("");
        }
    }

    private void setTicker(String text) {
        if (text.equals(lastTicker)) return;
        lastTicker = text;
        ticker.setText(text);
        if (tickerAnim != null) tickerAnim.cancel();
        if (text.isEmpty()) return;
        ticker.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = ticker.getMeasuredWidth(), box = Math.max(1, tickerBox.getWidth() > 0 ? tickerBox.getWidth() : getResources().getDisplayMetrics().widthPixels);
        tickerAnim = ValueAnimator.ofFloat(box, -w);
        tickerAnim.setDuration(Math.max(8000, (long) (w + box) * 9));
        tickerAnim.setInterpolator(new LinearInterpolator());
        tickerAnim.setRepeatCount(ValueAnimator.INFINITE);
        tickerAnim.addUpdateListener(an -> ticker.setTranslationX((float) an.getAnimatedValue()));
        tickerAnim.start();
    }

    // ---- REPLAY ------------------------------------------------------------------------------------------------

    private void loadTape() {
        JSONObject full;
        try {
            full = NightShift.full(LauncherConfig.readJson(this), System.currentTimeMillis());
        } catch (Exception e) {
            return;
        }
        List<JSONObject> out = new ArrayList<>();
        JSONArray log = full.optJSONArray("log"), q = full.optJSONArray("queue");
        for (int i = 0; log != null && i < log.length(); i++) {
            JSONObject e = log.optJSONObject(i);
            try {
                out.add(new JSONObject().put("at", e.optLong("at")).put("agent", e.optString("agent")).put("kind", e.optString("level"))
                        .put("text", e.optString("text")).put("detail", e.optString("detail", "")));
            } catch (Exception ignored) {
            }
        }
        for (int i = 0; q != null && i < q.length(); i++) {
            JSONObject e = q.optJSONObject(i);
            try {
                out.add(new JSONObject().put("at", e.optLong("at")).put("agent", e.optString("agent")).put("kind", "queued")
                        .put("text", e.optString("title")).put("detail", e.optString("detail", "")));
            } catch (Exception ignored) {
            }
        }
        out.sort((x, y) -> Long.compare(x.optLong("at"), y.optLong("at")));
        tape = out;
        cursor = 0;
        if (tape.isEmpty()) {
            name.setText(getString(R.string.live_replay));
            line.setText(getString(R.string.shift_none));
        }
    }

    private void step() {
        if (tape.isEmpty()) return;
        showTape();
        if (paused) return;
        if (cursor < tape.size() - 1) cursor++;
        else {
            paused = true;
            hint.setText(getString(R.string.live_end) + "   OK → " + getString(R.string.shift_title));
        }
    }

    private void showTape() {
        JSONObject e = tape.get(Math.max(0, Math.min(cursor, tape.size() - 1)));
        clock.setText(time(e.optLong("at")) + (paused ? "  ‖" : speed > 1 ? "  ⏩×" + speed : "  ▶"));
        name.setText(e.optString("agent"));
        line.setText(mark(e.optString("kind")) + " " + e.optString("text"));
        since.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(e.optLong("at"))));
        showProgress((cursor + 1) * 100 / tape.size());
        question.setVisibility("queued".equals(e.optString("kind")) ? View.VISIBLE : View.GONE);
        if ("queued".equals(e.optString("kind"))) question.setText("◆ " + getString(R.string.live_waits) + ": " + e.optString("text"));
        String d = e.optString("detail");
        try {
            showPreview(d.isEmpty() ? null : new JSONObject().put("kind", d.startsWith("diff") || d.contains("\n+") ? "diff" : "text").put("text", d));
        } catch (Exception ignored) {
        }
        timelineLabel.setText((cursor + 1) + " / " + tape.size());
        timeline.invalidate();
        studios.setText("");
        setTicker("");
    }

    private void jump(int dir) {
        for (int i = cursor + dir; i >= 0 && i < tape.size(); i += dir) {
            String k = tape.get(i).optString("kind");
            if ("fail".equals(k) || "queued".equals(k)) {
                cursor = i;
                showTape();
                return;
            }
        }
        cursor = dir < 0 ? 0 : Math.max(0, tape.size() - 1);
        showTape();
    }

    // ---- keys --------------------------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (replay) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (paused && cursor >= tape.size() - 1 && !tape.isEmpty()) {   // the end: the decisions
                        startActivity(ShiftActivity.intent(this, false));
                        finish();
                        return true;
                    }
                    paused = !paused;
                    showTapeSafe();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_DPAD_UP:
                    speed = speed >= 4 ? 1 : speed * 2;
                    showTapeSafe();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    jump(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    jump(1);
                    return true;
                default:
                    break;
            }
            return super.onKeyDown(code, e);
        }
        long now = System.currentTimeMillis();
        JSONArray a = snap == null ? null : snap.optJSONArray("agents");
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && a != null && a.length() > 0) {
            JSONObject cur = null;
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("agent").equals(onCamera)) cur = a.optJSONObject(i);
            int start = 0;
            for (int i = 0; cur != null && i < a.length(); i++) if (a.optJSONObject(i) == cur) start = i;
            JSONObject nx = a.optJSONObject(((start + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)) % a.length() + a.length()) % a.length());
            onCamera = nx.optString("agent");
            handUntil = now + HAND_MS;
            cutAt = now;
            showAgent(nx, now);
            refresh();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x.optString("agent").equals(onCamera) && x.has("waiting")) {
                    OmarchyLink.showAsk(this, x.optJSONObject("waiting").optString("id"));
                    return true;
                }
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void showTapeSafe() {
        if (!tape.isEmpty()) showTape();
    }

    private static String time(long ms) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ms));
    }

    private static String mark(String kind) {
        switch (kind) {
            case "ok": return "✓";
            case "fail": case "alert": return "✗";
            case "ask": case "queued": return "◆";
            case "answer": return "↩";
            case "status": return "▶";
            default: return "·";
        }
    }
}
