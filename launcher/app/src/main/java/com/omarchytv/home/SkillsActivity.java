package com.omarchytv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omarchytv.home.link.Skills;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * v2.22 skills from the community: the catalogue shipped in the app (✓ installed), and the install question with the
 * permissions in plain words. Cancel has the focus and Install waits 1.5 s, as for an agent's question. An agent can
 * ask for one (tv_skill_install): this screen opens on it, and only the owner's OK installs it.
 */
public class SkillsActivity extends Activity {
    static final String EXTRA_ID = "id";
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private Theme t;

    static Intent intent(Context c, String id) {
        return new Intent(c, SkillsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ID, id == null ? "" : id);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = Theme.cur();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bg);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(48), dp(32), dp(48), dp(32));
        sv.addView(list);
        setContentView(sv);
        fill();
        String id = getIntent().getStringExtra(EXTRA_ID);
        JSONObject sk = id == null || id.isEmpty() ? null : OmarchyLink.catalog(this).get(id);
        if (sk != null) h.post(() -> ask(sk));
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

    private void fill() {
        list.removeAllViews();
        list.addView(text("🧩 " + getString(R.string.skills_title), 30, t.fgBright, true));
        list.addView(text(getString(R.string.skills_hint), 16, t.fg, false));
        JSONObject inst = LauncherConfig.readJson(this).optJSONObject("skills");
        boolean first = true;
        for (Map.Entry<String, JSONObject> e : OmarchyLink.catalog(this).entrySet()) {
            JSONObject sk = e.getValue();
            boolean on = inst != null && inst.has(e.getKey());
            TextView r = text((on ? "✓ " : "＋ ") + sk.optString("name") + "  · " + sk.optString("author") + " · v" + sk.optString("version")
                    + "\n    " + sk.optString("description"), 18, on ? t.fgBright : t.fg, false);
            r.setPadding(dp(14), dp(10), dp(14), dp(10));
            r.setFocusable(true);
            GradientDrawable off = new GradientDrawable(), focus = new GradientDrawable();
            off.setColor(0);
            focus.setColor(t.bgLight);
            focus.setStroke(dp(2), t.accent);
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_focused}, focus);
            sl.addState(new int[]{}, off);
            r.setBackground(sl);
            r.setOnClickListener(v -> { if (on) removeAsk(sk); else ask(sk); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            list.addView(r, lp);
            if (first) { r.requestFocus(); first = false; }
        }
    }

    /** The permissions in plain words. */
    private String perms(JSONArray p) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; p != null && i < p.length(); i++) {
            int res;
            switch (p.optString(i)) {
                case "widgets": res = R.string.skill_perm_widgets; break;
                case "notify": res = R.string.skill_perm_notify; break;
                case "ha.read": res = R.string.skill_perm_ha_read; break;
                case "ha.control": res = R.string.skill_perm_ha_control; break;
                case "shift": res = R.string.skill_perm_shift; break;
                default: res = R.string.skill_perm_live; break;
            }
            b.append("• ").append(getString(res)).append('\n');
        }
        return b.toString();
    }

    private void ask(JSONObject sk) {
        String msg = sk.optString("description") + "\n\n" + getString(R.string.skill_asks) + "\n" + perms(sk.optJSONArray("permissions"))
                + "\n" + getString(R.string.skill_data_only);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("🧩 " + sk.optString("name") + " · " + sk.optString("author"))
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.skill_install, (x, w) -> {
                    try {
                        OmarchyLink.skillEdit(this, cfg -> Skills.install(cfg, sk, System.currentTimeMillis()));
                        android.widget.Toast.makeText(this, "✓ " + sk.optString("name"), android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, "✗ " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                    }
                    fill();
                }).create();
        d.setOnShowListener(x -> {
            Button no = d.getButton(AlertDialog.BUTTON_NEGATIVE), yes = d.getButton(AlertDialog.BUTTON_POSITIVE);
            yes.setEnabled(false);
            no.requestFocus();
            h.postDelayed(() -> yes.setEnabled(true), AgentQuestionActivity.OK_GUARD_MS);
        });
        d.show();
    }

    private void removeAsk(JSONObject sk) {
        new AlertDialog.Builder(this).setTitle("🧩 " + sk.optString("name")).setMessage(R.string.skill_remove_q)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.skill_remove, (x, w) -> {
                    try {
                        OmarchyLink.skillEdit(this, cfg -> Skills.remove(cfg, sk.optString("id")));
                    } catch (Exception ignored) {
                    }
                    fill();
                }).show();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
