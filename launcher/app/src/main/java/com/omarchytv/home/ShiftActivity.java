package com.omarchytv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omarchytv.home.link.NightShift;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/**
 * v2.22 the night shift's page: what the agents did at night (✓ / ✗ / ·), the tasks, and the decisions waiting for
 * the owner. Opened by the morning wake-up (ShiftWake), the first Home after it, or Settings. Only here, with the
 * remote, can a queued decision be approved: Deny has the focus, Approve waits 1.5 s (as AgentQuestionActivity).
 */
public class ShiftActivity extends Activity {
    static final String EXTRA_WAKE = "wake";
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private Theme t;
    private String lastJson = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            fill(false);
            h.postDelayed(this, 10_000);
        }
    };

    static Intent intent(Context c, boolean wake) {
        return new Intent(c, ShiftActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_WAKE, wake);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getIntent().getBooleanExtra(EXTRA_WAKE, false)) {   // the morning: light the screen for this page
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getIntent().getBooleanExtra(EXTRA_WAKE, false)) ShiftWake.dueOnHome(this);   // marks it shown: not again on Home
        t = Theme.cur();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bg);
        sv.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        list.setPadding(pad, dp(32), pad, dp(32));
        sv.addView(list);
        setContentView(sv);
        fill(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.postDelayed(refresh, 10_000);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(refresh);
        super.onPause();
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

    private View row(String s, int color, View.OnClickListener ok) {
        TextView v = text(s, 18, color, false);
        v.setPadding(dp(14), dp(8), dp(14), dp(8));
        v.setFocusable(true);
        v.setMaxLines(2);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable off = new GradientDrawable(), on = new GradientDrawable();
        off.setColor(0x00000000);
        on.setColor(t.bgLight);
        on.setStroke(dp(2), t.accent);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, off);
        v.setBackground(sl);
        if (ok != null) v.setOnClickListener(ok);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        v.setLayoutParams(lp);
        return v;
    }

    private void section(String s) {
        TextView v = text(s, 20, t.accent, true);
        v.setPadding(0, dp(22), 0, dp(6));
        list.addView(v);
    }

    private String time(long ms) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ms));
    }

    /** Rebuilds the page when the shift changed (first: always), keeping the focus on the same row when it can. */
    private void fill(boolean first) {
        JSONObject full;
        try {
            full = NightShift.full(LauncherConfig.readJson(this), System.currentTimeMillis());
        } catch (Exception e) {
            return;
        }
        String json = full.toString();
        if (!first && json.equals(lastJson)) return;
        lastJson = json;
        View f = getCurrentFocus();
        Object keep = f != null ? f.getTag() : null;
        list.removeAllViews();
        list.addView(text("🌙 " + getString(R.string.shift_title), 30, t.fgBright, true));
        String state = full.optString("state");
        if ("none".equals(state)) {
            list.addView(text(getString(R.string.shift_none), 18, t.fg, false));
            return;
        }
        JSONObject c = full.optJSONObject("counts");
        View rp = row("▶ " + getString(R.string.live_replay), t.accent, v -> startActivity(LiveActivity.intent(this, true)));   // the VCR
        rp.setTag("replay");
        list.addView(rp);
        String head = ("running".equals(state) ? getString(R.string.shift_running, time(full.optLong("until")))
                : getString(R.string.shift_ended, time(full.has("endedAt") ? full.optLong("endedAt") : full.optLong("until"))))
                + "   ✓ " + c.optInt("ok") + "   ✗ " + c.optInt("fail") + "   · " + c.optInt("info");
        list.addView(text(head, 18, t.fg, false));

        JSONArray q = full.optJSONArray("queue");
        int waiting = c.optInt("waiting");
        section(getString(R.string.shift_waiting, waiting));
        if (waiting == 0) list.addView(text("  —", 18, t.fg, false));
        for (int i = 0; q != null && i < q.length(); i++) {
            JSONObject e = q.optJSONObject(i);
            String st = e.optString("state");
            String mark = "pending".equals(st) ? "◆ " : "approved".equals(st) ? "✓ " : "✗ ";
            View r = row(mark + e.optString("title") + "  · " + e.optString("agent") + (e.has("risk") ? " · " + e.optString("risk") : ""),
                    "pending".equals(st) ? t.fgBright : t.fg, v -> decide(e));
            r.setTag("q:" + e.optString("id"));
            list.addView(r);
        }

        JSONArray tasks = full.optJSONArray("tasks");
        section(getString(R.string.shift_tasks));
        for (int i = 0; tasks != null && i < tasks.length(); i++) {
            list.addView(text("  " + (i + 1) + ". " + tasks.optJSONObject(i).optString("text"), 18, t.fg, false));
        }

        JSONArray log = full.optJSONArray("log");
        section(getString(R.string.shift_diary));
        if (log == null || log.length() == 0) list.addView(text("  —", 18, t.fg, false));
        for (int i = log == null ? -1 : log.length() - 1; i >= 0; i--) {   // newest first
            JSONObject e = log.optJSONObject(i);
            String lv = e.optString("level");
            int color = "ok".equals(lv) ? t.green : "fail".equals(lv) ? t.red : t.fg;
            String mark = "ok".equals(lv) ? "✓ " : "fail".equals(lv) ? "✗ " : "· ";
            View r = row(time(e.optLong("at")) + "  " + mark + e.optString("text") + "  · " + e.optString("agent"), color,
                    e.has("detail") ? v -> detail(e.optString("text"), e.optString("detail")) : null);
            r.setTag("l:" + e.optLong("at") + ":" + i);
            list.addView(r);
        }

        if ("running".equals(state)) {
            View end = row("■ " + getString(R.string.shift_end), t.red, v -> new AlertDialog.Builder(this)
                    .setTitle(R.string.shift_end).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        try {
                            OmarchyLink.shiftEdit(this, cfg -> NightShift.end(cfg, System.currentTimeMillis()), "ended");
                        } catch (Exception ignored) {
                        }
                        fill(true);
                    }).show());
            end.setTag("end");
            list.addView(end);
        }
        View target = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (keep != null && keep.equals(v.getTag())) target = v;
            if (target == null && first && v.isFocusable()) target = v;   // first: the first waiting decision
        }
        if (target != null) target.requestFocus();
    }

    private void detail(String title, String detail) {
        TextView v = text(detail, 15, t.fg, false);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        new AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton(android.R.string.ok, null).show();
    }

    /** A queued decision: its detail, Deny with the focus, Approve only after 1.5 s. */
    private void decide(JSONObject e) {
        if (!"pending".equals(e.optString("state"))) {
            detail(e.optString("title"), e.optString("detail", e.optString("state")));
            return;
        }
        TextView v = text((e.has("risk") ? "⚠ " + e.optString("risk") + "\n\n" : "") + e.optString("detail", ""), 15, t.fg, false);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        String id = e.optString("id");
        AlertDialog d = new AlertDialog.Builder(this).setTitle(e.optString("agent") + ": " + e.optString("title")).setView(sv)
                .setNegativeButton(R.string.shift_deny, (x, w) -> answer(id, false))
                .setPositiveButton(R.string.shift_approve, (x, w) -> answer(id, true)).create();
        d.setOnShowListener(x -> {
            Button no = d.getButton(AlertDialog.BUTTON_NEGATIVE), yes = d.getButton(AlertDialog.BUTTON_POSITIVE);
            yes.setEnabled(false);
            no.requestFocus();
            h.postDelayed(() -> yes.setEnabled(true), AgentQuestionActivity.OK_GUARD_MS);
        });
        d.show();
    }

    private void answer(String id, boolean yes) {
        try {
            OmarchyLink.shiftEdit(this, cfg -> NightShift.answer(cfg, id, yes, System.currentTimeMillis()), yes ? "approved" : "denied");
        } catch (Exception ignored) {
            // already answered from another screen: the page shows it
        }
        fill(true);
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
