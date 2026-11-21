package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omarchytv.home.link.Teletext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v2.22 Televideo AI: the agents' text pages as the teletext of old. Black, 40 columns, the seven colours, the header
 * with the page number and the clock. Type three digits to go to a page; ▲ ▼ (or CH+ / CH-) the next and previous;
 * the four keys are fastext: red the previous page, green the next, yellow the index (100), blue the agents' Guide.
 */
public class TeletextActivity extends Activity {
    static final String EXTRA_PAGE = "page";
    private static final int[] COLOR = {0xFFFFFFFF, 0xFFFF3B3B, 0xFF3BFF3B, 0xFFFFFF3B, 0xFF4F6BFF, 0xFFFF3BFF, 0xFF3BFFFF};
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView header, title;
    private final TextView[] rows = new TextView[Teletext.ROWS];
    private int page = Teletext.INDEX;
    private String typed = "", shownKey = "";
    private long missingUntil;
    private float px;

    static Intent intent(Context c, int page) {
        return new Intent(c, TeletextActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_PAGE, page);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render(false);
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        page = getIntent().getIntExtra(EXTRA_PAGE, Teletext.INDEX);
        int w = getResources().getDisplayMetrics().widthPixels, hh = getResources().getDisplayMetrics().heightPixels;
        px = Math.min(w * 0.86f / (Teletext.COLS * 0.6f), hh * 0.92f / ((Teletext.ROWS + 4) * 1.18f));   // 40 × 26 cells fit the screen
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF000000);
        box.setGravity(Gravity.CENTER);
        header = cell(0xFFFFFFFF);
        title = cell(0xFFFFFF3B);
        title.setBackgroundColor(0xFF1530A8);
        box.addView(header);
        box.addView(title);
        for (int i = 0; i < rows.length; i++) box.addView(rows[i] = cell(0xFFFFFFFF));
        LinearLayout fast = new LinearLayout(this);
        String[] labels = {"◀", "▶", "100", getString(R.string.guide_title)};
        int[] colors = {0xFFFF3B3B, 0xFF3BFF3B, 0xFFFFFF3B, 0xFF3BFFFF};
        for (int i = 0; i < 4; i++) {
            TextView f = cell(colors[i]);
            f.setText(labels[i]);
            f.setGravity(Gravity.CENTER);
            fast.addView(f, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(fast, new LinearLayout.LayoutParams((int) (px * 0.6f * Teletext.COLS), ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(box);
    }

    private TextView cell(int color) {
        TextView v = new TextView(this);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        v.setTextColor(color);
        v.setSingleLine(true);
        v.setIncludeFontPadding(false);
        v.setLineSpacing(0, 1.0f);
        v.setLayoutParams(new LinearLayout.LayoutParams((int) (px * 0.6f * Teletext.COLS), ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        page = i.getIntExtra(EXTRA_PAGE, page);
        render(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(true);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() > n ? s.substring(0, n) : s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private void render(boolean force) {
        String clock = new SimpleDateFormat("EEE dd MMM  HH:mm:ss", Locale.getDefault()).format(new Date());
        String num = typed.isEmpty() ? "P" + page : "P" + pad(typed, 3).replace(' ', '-');
        header.setText(pad(num + "  OmarchyTV", Teletext.COLS - clock.length()) + clock);
        JSONObject cfg = LauncherConfig.readJson(this), p;
        try {
            p = Teletext.page(cfg, page);
        } catch (Exception e) {
            p = null;
        }
        String key = p == null ? "none" : page + ":" + p.optLong("at") + ":" + p.optString("title") + ":" + (page == Teletext.INDEX ? Teletext.numbers(cfg).toString() : "");
        if (!force && key.equals(shownKey) && System.currentTimeMillis() > missingUntil) return;
        shownKey = key;
        if (p == null) {   // as the old teletext: the page is not there, back to the index
            title.setText(pad(" P" + page + "  " + getString(R.string.teletext_missing), Teletext.COLS));
            for (TextView r : rows) r.setText("");
            if (missingUntil == 0) missingUntil = System.currentTimeMillis() + 2500;
            else if (System.currentTimeMillis() > missingUntil) { missingUntil = 0; page = Teletext.INDEX; render(true); }
            return;
        }
        missingUntil = 0;
        String by = page == Teletext.INDEX ? getString(R.string.teletext_title) : p.optString("by");
        title.setText(pad(" " + p.optString("title").toUpperCase(Locale.getDefault()), Teletext.COLS - by.length() - 1) + by + " ");
        JSONArray ls = p.optJSONArray("lines");
        for (int i = 0; i < rows.length; i++) {
            JSONObject l = ls != null && i < ls.length() ? ls.optJSONObject(i) : null;
            rows[i].setText(l == null ? "" : l.optString("text"));
            int c = l == null ? 0 : Teletext.COLORS.indexOf(l.optString("color"));
            rows[i].setTextColor(COLOR[Math.max(0, c)]);
        }
    }

    private void go(int n) {
        page = n;
        typed = "";
        missingUntil = 0;
        render(true);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            int d = code - KeyEvent.KEYCODE_0;
            if (typed.isEmpty() && (d == 0 || d == 9)) return true;   // pages 100-899
            typed += d;
            if (typed.length() == 3) go(Integer.parseInt(typed));
            else render(true);
            return true;
        }
        JSONObject cfg = LauncherConfig.readJson(this);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                go(Teletext.step(cfg, page, 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                go(Teletext.step(cfg, page, -1));
                return true;
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                go(Teletext.INDEX);
                return true;
            case KeyEvent.KEYCODE_PROG_BLUE:
                startActivity(GuideActivity.intent(this));
                return true;
            case KeyEvent.KEYCODE_TV_TELETEXT:
                finish();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }
}
