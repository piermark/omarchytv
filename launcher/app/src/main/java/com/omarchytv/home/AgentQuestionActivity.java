package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v2.21 ask_user: a question from an agent (POST /v1/ask, MCP tv_ask_user), answered on the remote. One question at
 * a time, in the theme's colours; BACK leaves it pending (Home shows it again), it closes itself when it expires.
 */
public class AgentQuestionActivity extends Activity {
    static final String EXTRA_ID = "id", EXTRA_FROM = "from", EXTRA_Q = "q", EXTRA_DEADLINE = "deadline", EXTRA_CHOICES = "choices";
    /** v2.22: the full text under the question (the whole command), in a box you scroll with the remote. */
    static final String EXTRA_DETAIL = "detail";
    /** v2.22: why the request is risky: a red frame and a red "⚠ risky" line; the remote never offers a yes. */
    static final String EXTRA_RISK = "risk";

    private final Handler h = new Handler(Looper.getMainLooper());
    private String id;
    /** v2.22: OK is ignored this long after a question appears, so a press meant for the film answers nothing. */
    static final long OK_GUARD_MS = 1500;
    private long shownAt;

    static Intent intent(Context c, String id, String from, String question, List<String> choices, long deadline) {
        return new Intent(c, AgentQuestionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_FROM, from).putExtra(EXTRA_Q, question)
                .putExtra(EXTRA_DEADLINE, deadline).putExtra(EXTRA_CHOICES, choices.toArray(new String[0]));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        show(i);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void show(Intent i) {
        id = i.getStringExtra(EXTRA_ID);
        String from = i.getStringExtra(EXTRA_FROM), q = i.getStringExtra(EXTRA_Q);
        String[] choices = i.getStringArrayExtra(EXTRA_CHOICES);
        long deadline = i.getLongExtra(EXTRA_DEADLINE, 0);
        if (id == null || q == null || choices == null || choices.length == 0 || System.currentTimeMillis() >= deadline) {
            finish();
            return;
        }
        Theme t = Theme.cur();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor((t.bg & 0x00FFFFFF) | 0xE6000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(28);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable box = new GradientDrawable();
        box.setColor(t.bgDark);
        String risk = i.getStringExtra(EXTRA_RISK);
        boolean risky = risk != null && !risk.isEmpty();
        box.setStroke((int) dp(risky ? 4 : 2), risky ? t.red : t.accent);
        card.setBackground(box);
        if (risky) {
            TextView warn = new TextView(this);
            warn.setText("⚠ " + risk);
            warn.setTextColor(t.red);
            warn.setTypeface(CastUi.font(this), android.graphics.Typeface.BOLD);
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            warn.setPadding(0, 0, 0, (int) dp(8));
            card.addView(warn);
        }
        TextView who = new TextView(this);
        who.setText("● " + (from == null || from.isEmpty() ? "PC" : from) + " " + getString(R.string.agentq_asks));
        who.setTextColor(t.accent);
        who.setTypeface(CastUi.font(this));
        who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        card.addView(who);
        TextView text = new TextView(this);
        text.setText(q);
        text.setTextColor(t.fgBright);
        text.setTypeface(CastUi.font(this));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        text.setPadding(0, (int) dp(12), 0, (int) dp(20));
        card.addView(text);
        String detail = i.getStringExtra(EXTRA_DETAIL);
        if (detail != null && !detail.isEmpty()) {   // v2.22: ↑ from the buttons to focus it, ↑ / ↓ to scroll
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.setFocusable(true);
            GradientDrawable sb = new GradientDrawable();
            sb.setColor(t.bg);
            sb.setStroke((int) dp(1), t.muted);
            sv.setBackground(sb);
            sv.setOnFocusChangeListener((v, f) -> sb.setStroke((int) dp(f ? 2 : 1), f ? t.accent : t.muted));
            TextView dt = new TextView(this);
            dt.setText(coloured(detail, t.green, t.red));   // a diff: "+ " lines green, "- " lines red
            dt.setTextColor(t.fg);
            dt.setTypeface(android.graphics.Typeface.MONOSPACE);
            dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            int dp12 = (int) dp(12);
            dt.setPadding(dp12, dp12, dp12, dp12);
            sv.addView(dt);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            dl.bottomMargin = (int) dp(20);
            card.addView(sv, dl);
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
            sv.post(() -> {
                if (sv.getHeight() > maxH) {
                    sv.getLayoutParams().height = maxH;
                    sv.requestLayout();
                }
            });
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<Button> buttons = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        for (String c : choices) if (!(risky && com.omarchytv.home.link.AppTokens.sayYes(c))) shown.add(c);   // no yes if risky
        if (shown.isEmpty()) {
            finish();
            return;
        }
        for (String c : shown) {
            Button bt = new Button(this);
            bt.setText(c);
            bt.setAllCaps(false);
            bt.setTypeface(CastUi.font(this));
            bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            bt.setTextColor(t.fg);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(t.bgLight);
            bg.setStroke((int) dp(2), t.muted);
            bt.setBackground(bg);
            bt.setOnFocusChangeListener((v, f) -> {
                bg.setStroke((int) dp(2), f ? t.accent : t.muted);
                bt.setTextColor(f ? t.fgBright : t.fg);
            });
            bt.setOnClickListener(v -> {
                if (android.os.SystemClock.uptimeMillis() - shownAt >= OK_GUARD_MS) answer(c);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) dp(52));
            lp.setMarginEnd((int) dp(14));
            bt.setPadding((int) dp(22), 0, (int) dp(22), 0);
            row.addView(bt, lp);
            buttons.add(bt);
        }
        card.addView(row);
        TextView hint = new TextView(this);
        hint.setText(R.string.agentq_hint);
        hint.setTextColor(t.fgDim);
        hint.setTypeface(CastUi.font(this));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, (int) dp(16), 0, 0);
        card.addView(hint);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams((int) Math.min(dp(900), getResources().getDisplayMetrics().widthPixels * 0.8f),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(card, cp);
        setContentView(root);
        buttons.get(startFocus(shown)).requestFocus();
        shownAt = android.os.SystemClock.uptimeMillis();
        h.removeCallbacksAndMessages(null);
        h.postDelayed(this::finish, Math.max(0, deadline - System.currentTimeMillis()));
    }

    private void answer(String choice) {
        OmarchyLink.answerAsk(id, choice);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {   // later: the question stays pending, Home shows it again
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /**
     * v2.22: where the focus starts. Never on an answer that says yes: "On the PC" when there is one (Claude Code's
     * approvals), else the first answer that is not Allow / Yes / OK, else the first one.
     */
    static int startFocus(List<String> choices) {
        int pc = choices.indexOf("On the PC");
        if (pc >= 0) return pc;
        for (int i = 0; i < choices.size(); i++) {
            String c = choices.get(i).trim().toLowerCase(java.util.Locale.ROOT);
            if (!c.equals("allow") && !c.equals("yes") && !c.equals("ok") && !c.startsWith("approve")) return i;
        }
        return 0;
    }

    /** v2.22: the detail with "+ " lines in the theme's green and "- " lines in its red (diffs); other text as is. */
    static CharSequence coloured(String detail, int plus, int minus) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(detail);
        int start = 0;
        for (String line : detail.split("\n", -1)) {
            int end = start + line.length();
            if (line.startsWith("+ ") || line.startsWith("- ")) {
                sb.setSpan(new android.text.style.ForegroundColorSpan(line.charAt(0) == '+' ? plus : minus), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end + 1;
        }
        return sb;
    }

    /** For tests: the choices as given. */
    static List<String> choices(Intent i) {
        String[] c = i.getStringArrayExtra(EXTRA_CHOICES);
        return c == null ? new ArrayList<>() : Arrays.asList(c);
    }
}
