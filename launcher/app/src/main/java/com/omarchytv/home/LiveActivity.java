package com.omarchytv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v2.22 AI LIVE: the agents' work as a television broadcast.
 *
 * The look of a news channel: the pulsing ● LIVE bug and the channel logo top left, the clock and the network
 * watermark top right, the studios strip, one agent "on camera" (its avatar, what it does, the progress, the preview
 * of its work on the studio monitor), a news lower third that slides in at every cut, the red BREAKING banner when an
 * agent waits for the owner's yes (OK opens its question), and the NEWS ticker at the bottom. The director cuts every
 * 12 s to the next agent that is working; LEFT / RIGHT change camera by hand.
 *
 * REPLAY, the VCR: the night shift's diary played back like a tape, with the VCR's on-screen display (PLAY ▶,
 * ▶▶ ×2, ‖ PAUSE and the recording's date and time), the timeline with its highlights (failures in red, steps left
 * for the morning in yellow); OK pauses, FF / UP doubles the speed, LEFT / RIGHT jump between highlights, and at the
 * end OK opens the decisions that wait.
 *
 * Plain views, one bitmap at a time, two small animations: light on 2 GB TVs; nothing runs while it is not shown.
 */
public class LiveActivity extends Activity {
    static final String EXTRA_REPLAY = "replay", EXTRA_MODE = "mode", EXTRA_ARG = "arg";
    /** v2.22 the studio's shows: the morning briefing and the agents' debate. */
    static final String BRIEFING = "briefing", DEBATE = "debate";
    static final long CUT_MS = 12_000, HAND_MS = 30_000;
    static final int RED = 0xFFD7263D;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Theme t;
    private boolean replay, paused, stopped;
    private int speed = 1, cursor;
    private TextView bug, clock, studios, name, line, since, monitorLabel, previewText, ltName, ltText, breaking, ticker, hint, osd, osdDate;
    private View progress, progressTrack, lowerThird, timeline, stage;
    private AvatarView avatar;
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

    static Intent intent(Context c, String show) {
        return new Intent(c, LiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_MODE, show);
    }

    private String show = "";   // BRIEFING, DEBATE or "" (LIVE / REPLAY)
    private int section, pick;
    private long pickAt, sectionAt;
    private String debateId = "";
    private LinearLayout panels;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (BRIEFING.equals(show)) briefingFrame();
            else if (DEBATE.equals(show)) debateFrame();
            else if (replay) step();
            else refresh();
            h.postDelayed(this, replay ? Math.max(400, 2500 / speed) : 1000);
        }
    };

    private final Runnable blink = new Runnable() {
        boolean on;

        @Override public void run() {
            on = !on;
            bug.setAlpha(on ? 1f : 0.55f);
            if (breaking.getVisibility() == View.VISIBLE) breaking.setAlpha(on ? 1f : 0.82f);
            osd.setAlpha(replay && paused && !stopped && !on ? 0.3f : 1f);
            h.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        replay = getIntent().getBooleanExtra(EXTRA_REPLAY, false);
        show = getIntent().getStringExtra(EXTRA_MODE) == null ? "" : getIntent().getStringExtra(EXTRA_MODE);
        debateId = getIntent().getStringExtra(EXTRA_ARG) == null ? "" : getIntent().getStringExtra(EXTRA_ARG);
        t = Theme.cur();
        build();
        if (replay) loadTape();
        if (BRIEFING.equals(show)) startBriefing();
        if (DEBATE.equals(show)) startDebate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(frame);
        h.post(blink);
        avatar.setRunning(true);
        LiveFeed.get().onChange(() -> h.post(() -> { if (!replay && show.isEmpty()) refresh(); }));
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(frame);
        h.removeCallbacks(blink);
        LiveFeed.get().onChange(null);
        if (tickerAnim != null) tickerAnim.cancel();
        lastTicker = "";
        avatar.setRunning(false);
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

    private static GradientDrawable box(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static FrameLayout.LayoutParams at(int w, int hh, int gravity, int l, int tp, int r, int bt) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hh, gravity);
        lp.setMargins(l, tp, r, bt);
        return lp;
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{t.bgDarker, t.bg, t.bgDark}));
        int W = ViewGroup.LayoutParams.WRAP_CONTENT, M = ViewGroup.LayoutParams.MATCH_PARENT;

        // top left: the ● LIVE bug and the channel logo
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        bug = tv(17, 0xFFFFFFFF, true);
        bug.setText(replay ? "▶ REPLAY" : "● LIVE");
        bug.setBackground(box(replay ? t.blue : RED, dp(3)));
        bug.setPadding(dp(12), dp(4), dp(12), dp(4));
        brand.addView(bug);
        TextView logo = tv(19, t.bg, true);
        logo.setText("AI LIVE");
        logo.setBackground(box(t.accent, dp(3)));
        logo.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(W, W);
        ll.leftMargin = dp(6);
        brand.addView(logo, ll);
        root.addView(brand, at(W, W, Gravity.TOP | Gravity.START, dp(36), dp(24), 0, 0));

        // top right: the clock and the network watermark
        LinearLayout net = new LinearLayout(this);
        net.setOrientation(LinearLayout.VERTICAL);
        net.setGravity(Gravity.END);
        clock = tv(22, t.fgBright, true);
        clock.setBackground(box(0x66000000, dp(3)));
        clock.setPadding(dp(10), dp(2), dp(10), dp(2));
        net.addView(clock);
        TextView wm = tv(14, t.fgBright, true);
        wm.setText("OmarchyTV");
        wm.setAlpha(0.45f);
        net.addView(wm);
        root.addView(net, at(W, W, Gravity.TOP | Gravity.END, 0, dp(22), dp(36), 0));

        // the studios strip
        studios = tv(15, t.fg, false);
        studios.setSingleLine(true);
        studios.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(studios, at(M, W, Gravity.TOP, dp(36), dp(70), dp(160), 0));

        // the stage: the agent on camera, and the studio monitor with the preview of its work
        LinearLayout st = new LinearLayout(this);
        stage = st;
        st.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(st, at(M, M, Gravity.TOP, dp(36), dp(104), dp(36), dp(176)));
        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.VERTICAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        st.addView(who, new LinearLayout.LayoutParams(0, M, 1.15f));
        avatar = new AvatarView(this);
        who.addView(avatar, new LinearLayout.LayoutParams(dp(128), dp(128)));
        name = tv(38, t.fgBright, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(10), dp(16), 0);
        who.addView(name);
        line = tv(21, t.fg, false);
        line.setMaxLines(3);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setPadding(0, 0, dp(16), 0);
        who.addView(line);
        FrameLayout pf = new FrameLayout(this);
        progressTrack = new View(this);
        progressTrack.setBackground(box(t.bgLight, dp(3)));
        pf.addView(progressTrack, new FrameLayout.LayoutParams(M, dp(6)));
        progress = new View(this);
        progress.setBackground(box(t.accent, dp(3)));
        pf.addView(progress, new FrameLayout.LayoutParams(0, dp(6)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(M, dp(6));
        pl.topMargin = dp(12);
        pl.rightMargin = dp(24);
        who.addView(pf, pl);
        since = tv(14, t.muted, false);
        since.setPadding(0, dp(6), 0, 0);
        who.addView(since);

        FrameLayout monitor = new FrameLayout(this);   // a monitor in the studio, with its frame and label
        GradientDrawable mf = box(t.bgDarker, dp(6));
        mf.setStroke(dp(3), t.muted);
        monitor.setBackground(mf);
        monitor.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, M, 1.85f);
        mlp.topMargin = dp(18);
        st.addView(monitor, mlp);
        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        monitor.addView(previewImage, at(M, M, Gravity.TOP, 0, dp(18), 0, 0));
        previewText = tv(14, t.fg, false);
        previewText.setTypeface(android.graphics.Typeface.MONOSPACE);
        monitor.addView(previewText, at(M, M, Gravity.TOP, 0, dp(18), 0, 0));
        monitorLabel = tv(12, t.bg, true);
        monitorLabel.setBackground(box(t.muted, dp(2)));
        monitorLabel.setPadding(dp(8), dp(1), dp(8), dp(1));
        monitor.addView(monitorLabel, at(W, W, Gravity.TOP | Gravity.START, 0, 0, 0, 0));

        // the BREAKING banner
        breaking = tv(20, 0xFFFFFFFF, true);
        breaking.setBackground(box(RED, 0));
        breaking.setPadding(dp(18), dp(8), dp(18), dp(8));
        breaking.setSingleLine(true);
        breaking.setEllipsize(TextUtils.TruncateAt.END);
        breaking.setVisibility(View.GONE);
        root.addView(breaking, at(M, W, Gravity.BOTTOM, dp(36), 0, dp(36), dp(130)));

        // the news lower third: a coloured block with the name, a light bar with what it does
        LinearLayout lt = new LinearLayout(this);
        lowerThird = lt;
        lt.setGravity(Gravity.CENTER_VERTICAL);
        ltName = tv(20, t.bg, true);
        ltName.setBackground(box(t.accent, 0));
        ltName.setPadding(dp(16), dp(8), dp(16), dp(8));
        ltName.setSingleLine(true);
        lt.addView(ltName);
        ltText = tv(19, t.bg, false);
        ltText.setBackground(box(t.fgBright, 0));
        ltText.setPadding(dp(16), dp(8), dp(16), dp(8));
        ltText.setSingleLine(true);
        ltText.setEllipsize(TextUtils.TruncateAt.END);
        lt.addView(ltText, new LinearLayout.LayoutParams(0, W, 1));
        root.addView(lt, at(M, W, Gravity.BOTTOM, dp(36), 0, dp(120), dp(80)));

        // REPLAY: the tape's timeline and the VCR's on-screen display
        timeline = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), hh = getHeight(), n = tape.size();
                p.setColor(0x88000000);
                c.drawRect(0, hh / 2f - dp(3), w, hh / 2f + dp(3), p);
                for (int i = 0; i < n; i++) {
                    String k = tape.get(i).optString("kind");
                    if (!"fail".equals(k) && !"queued".equals(k)) continue;
                    p.setColor("fail".equals(k) ? t.red : t.yellow);
                    float x = n <= 1 ? 0 : i * (w - 1f) / (n - 1);
                    c.drawRect(x - dp(2), 0, x + dp(2), hh, p);
                }
                p.setColor(t.accent);
                float x = n <= 1 ? 0 : cursor * (w - 1f) / (n - 1);
                c.drawRect(0, hh / 2f - dp(3), x, hh / 2f + dp(3), p);
                c.drawCircle(x, hh / 2f, dp(8), p);
            }
        };
        timeline.setVisibility(replay ? View.VISIBLE : View.GONE);
        root.addView(timeline, at(M, dp(20), Gravity.BOTTOM, dp(36), 0, dp(36), dp(140)));
        osd = tv(30, 0xFFFFFFFF, true);   // the VCR's own letters: big, white, monospaced, with a shadow
        osd.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        osd.setShadowLayer(dp(2), dp(2), dp(2), 0xCC000000);
        osd.setVisibility(replay ? View.VISIBLE : View.GONE);
        root.addView(osd, at(W, W, Gravity.TOP | Gravity.END, 0, dp(92), dp(40), 0));
        osdDate = tv(20, 0xFFFFFFFF, true);
        osdDate.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        osdDate.setShadowLayer(dp(2), dp(2), dp(2), 0xCC000000);
        osdDate.setVisibility(replay ? View.VISIBLE : View.GONE);
        root.addView(osdDate, at(W, W, Gravity.BOTTOM | Gravity.END, 0, 0, dp(40), dp(166)));

        // the NEWS ticker
        LinearLayout tk = new LinearLayout(this);
        tk.setBackground(box(0xEE000000, 0));
        tk.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = tv(17, 0xFFFFFFFF, true);
        label.setText(replay ? "TAPE" : "NEWS");
        label.setBackground(box(replay ? t.blue : RED, 0));
        label.setPadding(dp(14), dp(8), dp(14), dp(8));
        tk.addView(label);
        tickerBox = new FrameLayout(this);
        tickerBox.setClipChildren(true);
        ticker = tv(18, 0xFFFFFFFF, false);
        ticker.setSingleLine(true);
        tickerBox.addView(ticker, new FrameLayout.LayoutParams(W, W, Gravity.CENTER_VERTICAL));
        tk.addView(tickerBox, new LinearLayout.LayoutParams(0, M, 1));
        root.addView(tk, at(M, W, Gravity.BOTTOM, 0, 0, 0, dp(28)));
        hint = tv(13, t.muted, false);
        hint.setText(replay ? "OK ⏯   ▶▶ ×2   ◀ ▶ highlights   BACK" : "◀ ▶ camera   OK " + getString(R.string.live_answer) + "   BACK");
        root.addView(hint, at(W, W, Gravity.BOTTOM | Gravity.START, dp(36), 0, 0, dp(6)));
        setContentView(root);
    }

    /** The lower third slides in from the left at every cut, like a news graphic; the stage fades up. */
    private void cutTo(String who) {
        float off = -(lowerThird.getWidth() > 0 ? lowerThird.getWidth() : getResources().getDisplayMetrics().widthPixels) - dp(40);
        lowerThird.setTranslationX(off);
        lowerThird.animate().translationX(0).setDuration(450).setInterpolator(new DecelerateInterpolator()).start();
        stage.setAlpha(0.2f);
        stage.animate().alpha(1f).setDuration(350).start();
        avatar.setName(who);
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
            if (!"·".equals(onCamera)) cutTo("AI");
            onCamera = "·";
            name.setText("AI LIVE");
            line.setText(getString(R.string.live_empty));
            ltName.setText("AI LIVE");
            ltText.setText(getString(R.string.live_empty));
            breaking.setVisibility(View.GONE);
            since.setText("");
            showProgress(-1);
            showPreview(null);
            studios.setText("");
            setTicker("");
            avatar.setState(false, false);
            return;
        }
        JSONObject cur = null, waiting = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x.optString("agent").equals(onCamera)) cur = x;
            if (waiting == null && x.has("waiting")) waiting = x;
        }
        if (waiting != null && now >= handUntil) cur = waiting;   // breaking: whoever waits for a yes
        else if (cur == null || (now - cutAt > CUT_MS && now >= handUntil)) cur = next(a, cur, now);
        if (cur == null) cur = a.optJSONObject(0);
        if (!cur.optString("agent").equals(onCamera)) {
            cutAt = now;
            onCamera = cur.optString("agent");
            cutTo(onCamera);
        }
        showAgent(cur, now);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            boolean on = x.optString("agent").equals(onCamera);
            String dot = x.has("waiting") ? "◆" : now - x.optLong("at") < 600_000 ? "●" : "○";
            s.append(on ? "▶ " : "").append(dot).append(' ').append(x.optString("agent")).append("      ");
        }
        studios.setText(s.toString().trim());
        JSONArray ev = snap.optJSONArray("events");
        StringBuilder k = new StringBuilder();
        for (int i = ev == null ? -1 : ev.length() - 1; i >= 0 && k.length() < 700; i--) {
            JSONObject e = ev.optJSONObject(i);
            k.append(time(e.optLong("at"))).append("  ").append(mark(e.optString("kind"))).append(' ')
                    .append(e.optString("agent").toUpperCase(Locale.ROOT)).append(" · ").append(e.optString("text")).append("        ▪        ");
        }
        setTicker(k.toString());
    }

    /** The next agent that is working (or waits), after the one on camera. */
    private JSONObject next(JSONArray a, JSONObject cur, long now) {
        int start = 0;
        for (int i = 0; cur != null && i < a.length(); i++) if (a.optJSONObject(i).optString("agent").equals(cur.optString("agent"))) start = i;
        for (int k = 1; k <= a.length(); k++) {
            JSONObject x = a.optJSONObject((start + k) % a.length());
            if (now - x.optLong("at") < 600_000 || x.has("waiting")) return x;
        }
        return cur;
    }

    private void showAgent(JSONObject x, long now) {
        String what = x.optString("text").isEmpty() ? "…" : x.optString("text");
        name.setText(x.optString("agent"));
        line.setText(what);
        ltName.setText(x.optString("agent").toUpperCase(Locale.ROOT));
        ltText.setText(what);
        long on = Math.max(0, now - x.optLong("since", now)) / 60_000;
        since.setText(getString(R.string.live_on_air, on));
        showProgress(x.optInt("progress", -1));
        JSONObject w = x.optJSONObject("waiting");
        boolean wasOn = breaking.getVisibility() == View.VISIBLE;
        breaking.setVisibility(w == null ? View.GONE : View.VISIBLE);
        if (w != null) {
            breaking.setText("BREAKING  ◆  " + x.optString("agent") + " " + getString(R.string.live_waits) + ": " + w.optString("question") + "   [ OK ]");
            if (!wasOn) {
                breaking.setTranslationX(getResources().getDisplayMetrics().widthPixels);
                breaking.animate().translationX(0).setDuration(400).setInterpolator(new DecelerateInterpolator()).start();
            }
        }
        avatar.setState(now - x.optLong("at") < 600_000, w != null);
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
        String title = p == null ? "" : p.optString("title", "");
        monitorLabel.setText(kind.isEmpty() ? "STUDIO" : "ON SCREEN" + (title.isEmpty() ? "" : " · " + title));
        monitorLabel.setBackground(box(kind.isEmpty() ? t.muted : t.accent, dp(2)));
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
            StringBuilder s = new StringBuilder(p.optString("label")).append("\n\n");
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

    // ---- v2.22 MORNING BRIEFING: the agent's news, section after section, read aloud -----------------------------

    private void startBriefing() {
        bug.setText("◆ MORNING");
        bug.setBackground(box(t.yellow, dp(3)));
        hint.setText("◀ ▶ news   OK →   BACK");
        section = 0;
        sectionAt = System.currentTimeMillis();
        JSONObject b = LauncherConfig.readJson(this).optJSONObject("briefing");
        if (b != null && !b.optString("say").isEmpty()) {
            OmarchyLink.speakNow(b.optString("say"));
        }
        cutTo(b == null ? "AI" : b.optString("by", "AI"));
        briefingFrame();
    }

    private void briefingFrame() {
        long now = System.currentTimeMillis();
        clock.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(now)));
        JSONObject b = LauncherConfig.readJson(this).optJSONObject("briefing");
        JSONArray secs = b == null ? null : b.optJSONArray("sections");
        if (secs == null || secs.length() == 0) {
            name.setText("AI LIVE");
            line.setText(getString(R.string.shift_none));
            return;
        }
        if (now - sectionAt > 9000) { section = (section + 1) % secs.length(); sectionAt = now; lowerThird.setTranslationX(-dp(900)); lowerThird.animate().translationX(0).setDuration(450).start(); }
        section = Math.max(0, Math.min(section, secs.length() - 1));
        JSONObject s = secs.optJSONObject(section);
        JSONArray ls = s.optJSONArray("lines");
        name.setText(b.optString("title"));
        line.setText(b.optString("by"));
        ltName.setText(s.optString("title").toUpperCase(Locale.ROOT));
        ltText.setText(ls != null && ls.length() > 0 ? ls.optString(0) : "");
        StringBuilder m = new StringBuilder();
        for (int i = 0; ls != null && i < ls.length(); i++) m.append("▪ ").append(ls.optString(i)).append("\n\n");
        try {
            showPreview(new JSONObject().put("kind", "text").put("title", s.optString("title")).put("text", m.toString().trim()));
        } catch (Exception ignored) {
        }
        StringBuilder st = new StringBuilder();
        for (int i = 0; i < secs.length(); i++) st.append(i == section ? "▶ " : "").append(secs.optJSONObject(i).optString("title")).append("      ");
        studios.setText(st.toString().trim());
        showProgress((section + 1) * 100 / secs.length());
        since.setText((section + 1) + " / " + secs.length());
        breaking.setVisibility(View.GONE);
        StringBuilder k = new StringBuilder();
        for (int i = 0; i < secs.length(); i++) {
            JSONArray l2 = secs.optJSONObject(i).optJSONArray("lines");
            for (int j = 0; l2 != null && j < l2.length(); j++) k.append(secs.optJSONObject(i).optString("title").toUpperCase(Locale.ROOT)).append(" · ").append(l2.optString(j)).append("        ▪        ");
        }
        setTicker(k.toString());
        avatar.setState(true, false);
    }

    // ---- v2.22 DEBATE: the agents' answers side by side, the owner picks the winner -------------------------------

    private void startDebate() {
        bug.setText("◆ DEBATE");
        bug.setBackground(box(t.accent2, dp(3)));
        hint.setText("◀ ▶ answer   OK " + getString(R.string.debate_pick) + "   BACK");
        stage.setVisibility(View.GONE);
        panels = new LinearLayout(this);
        ((FrameLayout) stage.getParent()).addView(panels, at(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP, dp(36), dp(110), dp(36), dp(176)));
        debateFrame();
    }

    private JSONObject currentDebate() {
        try {
            if (!debateId.isEmpty()) return com.omarchytv.home.link.Studio.debate(debateId);
        } catch (Exception ignored) {
        }
        JSONObject d = com.omarchytv.home.link.Studio.debateOnAir();
        if (d != null) debateId = d.optString("id");
        return d;
    }

    private void debateFrame() {
        clock.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
        JSONObject d = currentDebate();
        if (d == null) {
            ltName.setText("DEBATE");
            ltText.setText("—");
            return;
        }
        JSONArray en = d.optJSONArray("entries");
        int n = en == null ? 0 : en.length();
        pick = Math.max(0, Math.min(pick, Math.max(0, n - 1)));
        ltName.setText("THE QUESTION");
        ltText.setText(d.optString("topic"));
        String winner = d.optString("winner", "");
        breaking.setVisibility(View.VISIBLE);
        breaking.setBackground(box(winner.isEmpty() ? RED : t.green, 0));
        breaking.setText(winner.isEmpty() ? (n == 0 ? "◆ " + getString(R.string.debate_waiting) : "◆ " + getString(R.string.debate_you_decide) + " · OK → " + en.optJSONObject(pick).optString("agent"))
                : "🏆 " + winner);
        panels.removeAllViews();
        for (int i = 0; i < n; i++) {
            JSONObject e = en.optJSONObject(i);
            boolean on = i == pick, won = winner.equals(e.optString("agent"));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable g = box(t.bgDarker, dp(6));
            g.setStroke(dp(on || won ? 4 : 2), won ? t.green : on ? t.accent : t.muted);
            col.setBackground(g);
            col.setPadding(dp(16), dp(12), dp(16), dp(12));
            TextView who = tv(22, t.fgBright, true);
            who.setText((won ? "🏆 " : "") + e.optString("agent"));
            col.addView(who);
            TextView tx = tv(16, t.fg, false);
            tx.setText(e.optString("text"));
            tx.setPadding(0, dp(8), 0, 0);
            col.addView(tx);
            JSONObject pv = e.optJSONObject("preview");
            if (pv != null && ("text".equals(pv.optString("kind")) || "diff".equals(pv.optString("kind")))) {
                TextView pt = tv(13, t.fg, false);
                pt.setTypeface(android.graphics.Typeface.MONOSPACE);
                pt.setText("diff".equals(pv.optString("kind")) ? AgentQuestionActivity.coloured(pv.optString("text"), t.green, t.red) : pv.optString("text"));
                pt.setPadding(0, dp(10), 0, 0);
                col.addView(pt);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, i == n - 1 ? 0 : dp(8), 0);
            panels.addView(col, lp);
        }
        StringBuilder st = new StringBuilder();
        for (int i = 0; i < n; i++) st.append(i == pick ? "▶ " : "").append(en.optJSONObject(i).optString("agent")).append("      ");
        studios.setText(st.toString().trim());
        setTicker("");
    }

    private void debateKey(int code) {
        JSONObject d = currentDebate();
        JSONArray en = d == null ? null : d.optJSONArray("entries");
        int n = en == null ? 0 : en.length();
        if (n == 0) return;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            pick = (pick + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) + n) % n;
            pickAt = System.currentTimeMillis();
            debateFrame();
        } else if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && d.optString("winner", "").isEmpty()
                && System.currentTimeMillis() - pickAt > AgentQuestionActivity.OK_GUARD_MS) {   // a reflex OK never picks
            try {
                OmarchyLink.debateChosen(debateId, pick);
            } catch (Exception ignored) {
            }
            debateFrame();
        }
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
            ltName.setText("REPLAY");
            ltText.setText(getString(R.string.shift_none));
            osd.setText("STOP ■");
            stopped = true;
        }
    }

    private void step() {
        if (tape.isEmpty()) return;
        showTape();
        if (paused) return;
        if (cursor < tape.size() - 1) cursor++;
        else {
            paused = true;
            stopped = true;
            osd.setText("STOP ■");
            hint.setText(getString(R.string.live_end) + "   OK → " + getString(R.string.shift_title));
        }
    }

    private void showTape() {
        JSONObject e = tape.get(Math.max(0, Math.min(cursor, tape.size() - 1)));
        String who = e.optString("agent");
        if (!who.equals(onCamera)) {
            onCamera = who;
            cutTo(who);
        }
        clock.setText(time(e.optLong("at")));
        if (!stopped) osd.setText(paused ? "PAUSE ‖" : speed > 1 ? "PLAY ▶▶ ×" + speed : "PLAY ▶");
        osdDate.setText(new SimpleDateFormat("MMM dd yyyy  HH:mm", Locale.getDefault()).format(new Date(e.optLong("at"))).toUpperCase(Locale.getDefault()));
        String text = mark(e.optString("kind")) + " " + e.optString("text");
        name.setText(who);
        line.setText(text);
        ltName.setText(who.toUpperCase(Locale.ROOT));
        ltText.setText(text);
        since.setText((cursor + 1) + " / " + tape.size());
        showProgress((cursor + 1) * 100 / tape.size());
        boolean q = "queued".equals(e.optString("kind"));
        breaking.setVisibility(q ? View.VISIBLE : View.GONE);
        if (q) breaking.setText("◆  " + who + " " + getString(R.string.live_waits) + ": " + e.optString("text"));
        avatar.setState(true, q || "fail".equals(e.optString("kind")));
        String d = e.optString("detail");
        try {
            showPreview(d.isEmpty() ? null : new JSONObject().put("kind", d.startsWith("diff") || d.contains("\n+") ? "diff" : "text").put("text", d));
        } catch (Exception ignored) {
        }
        timeline.invalidate();
        StringBuilder k = new StringBuilder();
        for (int i = cursor + 1; i < tape.size() && k.length() < 500; i++) {
            JSONObject x = tape.get(i);
            k.append(time(x.optLong("at"))).append("  ").append(mark(x.optString("kind"))).append(' ')
                    .append(x.optString("agent").toUpperCase(Locale.ROOT)).append(" · ").append(x.optString("text")).append("        ▪        ");
        }
        setTicker(k.toString());
    }

    private void jump(int dir) {
        stopped = false;
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
        if (DEBATE.equals(show) && code != KeyEvent.KEYCODE_BACK) {
            debateKey(code);
            return true;
        }
        if (BRIEFING.equals(show) && code != KeyEvent.KEYCODE_BACK) {
            JSONObject b = LauncherConfig.readJson(this).optJSONObject("briefing");
            int n = b == null || b.optJSONArray("sections") == null ? 1 : Math.max(1, b.optJSONArray("sections").length());
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                section = (section + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) + n) % n;
                sectionAt = System.currentTimeMillis();
                briefingFrame();
            } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                finish();   // the night shift's page, if the morning opened it, is underneath
            }
            return true;
        }
        if (replay) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (stopped && cursor >= tape.size() - 1 && !tape.isEmpty()) {   // the end of the tape: the decisions
                        startActivity(ShiftActivity.intent(this, false));
                        finish();
                        return true;
                    }
                    paused = !paused;
                    if (!tape.isEmpty()) showTape();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_DPAD_UP:
                    speed = speed >= 4 ? 1 : speed * 2;
                    if (!tape.isEmpty()) showTape();
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
            int start = 0;
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("agent").equals(onCamera)) start = i;
            JSONObject nx = a.optJSONObject(((start + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)) % a.length() + a.length()) % a.length());
            onCamera = nx.optString("agent");
            handUntil = now + HAND_MS;
            cutAt = now;
            cutTo(onCamera);
            showAgent(nx, now);
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

    private static String time(long ms) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ms));
    }

    private static String mark(String kind) {
        switch (kind) {
            case "ok": return "✓";
            case "fail": case "alert": return "✗";
            case "ask": case "queued": return "◆";
            case "answer": return "↩";
            case "talk": return "🎙";
            case "status": return "▶";
            default: return "·";
        }
    }

    /**
     * The agent's face on camera: a disc in a colour of its own (from its name), its initials, and a ring that
     * breathes while it works and turns red, faster, when it waits for the owner. About 15 frames a second, and only
     * while it breathes and the screen is shown.
     */
    static final class AvatarView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Theme th = Theme.cur();
        private final long t0 = SystemClock.uptimeMillis();
        private String initials = "AI";
        private int color;
        private boolean working, waiting, running;

        AvatarView(Context c) {
            super(c);
            color = th.accent;
        }

        void setName(String name) {
            String n = name == null ? "" : name.trim();
            StringBuilder b = new StringBuilder();
            for (String s : n.split("[\\s·_-]+")) if (!s.isEmpty() && b.length() < 2) b.appendCodePoint(s.codePointAt(0));
            initials = b.length() == 0 ? "AI" : b.toString().toUpperCase(Locale.ROOT);
            int[] pal = {th.accent, th.accent2, th.blue, th.cyan, th.green, th.orange, th.yellow};
            color = pal[(n.hashCode() & 0x7FFFFFFF) % pal.length];
            invalidate();
        }

        void setState(boolean working, boolean waiting) {
            if (this.working == working && this.waiting == waiting) return;
            this.working = working;
            this.waiting = waiting;
            invalidate();
        }

        void setRunning(boolean on) {
            running = on;
            if (on) invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight(), cx = w / 2f, cy = hh / 2f, rad = Math.min(w, hh) / 2f;
            double ph = (SystemClock.uptimeMillis() - t0) / (waiting ? 260.0 : 700.0);
            float breath = working || waiting ? (float) (0.5 + 0.5 * Math.sin(ph)) : 0f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(rad * 0.08f);
            p.setColor(waiting ? RED : color);
            p.setAlpha((int) (90 + 165 * breath));
            float ring = rad * (0.88f + 0.08f * breath);
            r.set(cx - ring, cy - ring, cx + ring, cy + ring);
            c.drawOval(r, p);
            p.setStyle(Paint.Style.FILL);
            p.setAlpha(255);
            p.setColor(color);
            c.drawCircle(cx, cy, rad * 0.72f, p);
            p.setColor(th.bg);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(rad * 0.6f);
            p.setFakeBoldText(true);
            c.drawText(initials, cx, cy - (p.descent() + p.ascent()) / 2f, p);
            if (running && (working || waiting)) postInvalidateDelayed(66);
        }

        @Override
        protected void onDetachedFromWindow() {
            running = false;
            super.onDetachedFromWindow();
        }
    }
}
