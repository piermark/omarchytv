package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omarchytv.home.link.Guide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/**
 * v2.22 the agents' Guide: their work as a TV schedule (the programmes they put on it, the night shift, the morning
 * briefing), each with its time, its agent as the channel, and ● ON AIR or NEXT. OK on a programme on air opens AI
 * LIVE, on the night shift its page or its replay, on the briefing the briefing. Red: AI LIVE; blue: Televideo.
 */
public class GuideActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView detail, clock;
    private Theme t;
    private String shown = "";
    private int focusIndex = -1;

    static Intent intent(Context c) {
        return new Intent(c, GuideActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            fill(false);
            h.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = Theme.cur();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);
        root.setPadding(dp(48), dp(28), dp(48), dp(20));
        LinearLayout head = new LinearLayout(this);
        TextView ti = text("📺 " + getString(R.string.guide_title), 30, t.fgBright, true);
        head.addView(ti, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        clock = text("", 26, t.fgBright, true);
        head.addView(clock);
        root.addView(head);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(12), 0, dp(12));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        detail = text("", 16, t.fg, false);
        detail.setMaxLines(3);
        root.addView(detail);
        root.addView(text("OK ›   🔴 AI LIVE   🔵 " + getString(R.string.teletext_title) + "   BACK", 14, t.muted, false));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill(true);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(CastUi.font(this), bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return v;
    }

    private void fill(boolean force) {
        long now = System.currentTimeMillis();
        DateFormat tf = DateFormat.getTimeInstance(DateFormat.SHORT);
        clock.setText(tf.format(new Date(now)));
        JSONArray ps;
        try {
            ps = Guide.view(LauncherConfig.readJson(this), now);
        } catch (Exception e) {
            ps = new JSONArray();
        }
        String key = ps.toString();
        if (!force && key.equals(shown)) return;
        shown = key;
        View f = list.findFocus();
        if (f != null) focusIndex = list.indexOfChild(f);
        list.removeAllViews();
        if (ps.length() == 0) {
            list.addView(text(getString(R.string.guide_empty), 18, t.fg, false));
            detail.setText("");
            return;
        }
        int firstLive = -1;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            String when = p.optString("now"), st = p.optString("state");
            boolean live = "on_air".equals(when), past = "past".equals(when);
            if (live && firstLive < 0) firstLive = i;
            String mark = live ? "● " + getString(R.string.guide_on_air) : "next".equals(when) ? getString(R.string.guide_next)
                    : "failed".equals(st) ? "✗" : "cancelled".equals(st) ? "–" : "✓";
            String icon = "shift".equals(p.optString("kind")) ? "🌙 " : "briefing".equals(p.optString("kind")) ? "◆ " : "";
            TextView r = text(tf.format(new Date(p.optLong("start"))) + "–" + tf.format(new Date(p.optLong("end"))) + "   "
                    + p.optString("agent") + "   " + icon + p.optString("title") + "      " + mark, 20,
                    live ? t.fgBright : past ? t.muted : t.fg, live);
            r.setSingleLine(true);
            r.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.setPadding(dp(16), dp(10), dp(16), dp(10));
            r.setFocusable(true);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(6));
            g.setColor(live ? (0x33000000 | (t.red & 0xFFFFFF)) : 0);
            r.setBackground(g);
            r.setOnFocusChangeListener((v, has) -> {
                ((GradientDrawable) v.getBackground()).setStroke(dp(has ? 3 : 0), t.accent);
                if (has) detail.setText(p.optString("detail"));
            });
            r.setOnClickListener(v -> open(p));
            list.addView(r);
        }
        int want = focusIndex >= 0 && focusIndex < list.getChildCount() ? focusIndex : Math.max(0, firstLive);
        list.getChildAt(Math.min(want, list.getChildCount() - 1)).requestFocus();
    }

    private void open(JSONObject p) {
        String kind = p.optString("kind"), when = p.optString("now");
        if ("shift".equals(kind)) startActivity("on_air".equals(when) ? LiveActivity.intent(this, false) : ShiftActivity.intent(this, false));
        else if ("briefing".equals(kind)) startActivity(LiveActivity.intent(this, LiveActivity.BRIEFING));
        else if ("past".equals(when)) detail.setText(p.optString("detail").isEmpty() ? p.optString("title") : p.optString("detail"));
        else startActivity(LiveActivity.intent(this, false));
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_PROG_RED) {
            startActivity(LiveActivity.intent(this, false));
            return true;
        }
        if (code == KeyEvent.KEYCODE_PROG_BLUE || code == KeyEvent.KEYCODE_TV_TELETEXT) {
            startActivity(TeletextActivity.intent(this, 100));
            return true;
        }
        if (code == KeyEvent.KEYCODE_GUIDE) {
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
