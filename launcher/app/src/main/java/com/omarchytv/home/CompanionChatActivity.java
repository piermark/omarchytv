package com.omarchytv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v2.13 Grok Bot Companion (TV) session: a chat panel opened from the floating companion (OK on it,
 * long-press Menu, or the home tile). Type with the on-screen or a Bluetooth/USB keyboard, or speak through
 * the system recognizer (the remote's mic on Android TV). With @tcballard's Grok Bot Desktop Companion on
 * a linked PC (omarchy-link) the question goes to it; otherwise to OmarchyTV's own Ask (the AI provider
 * chosen there: Grok Bot, Claude Code, Codex, Hermes…). "open Netflix" / "apri YouTube" opens an installed app
 * first (CompanionCommands). Links in a reply become buttons: YouTube ones play in the YouTube app, others open
 * in the WebApp, only after OK on the button. Each bot keeps its last 30 lines. Built against @tcballard's
 * Grok Bot Desktop Companion v0.1.0 (MIT, commit a2efb00); the code here is mine.
 */
public class CompanionChatActivity extends Activity implements OmarchyLink.Listener {
    private static final int REQ_VOICE = 41;
    private LinearLayout log;
    private ScrollView scroll;
    private EditText input;
    private TextView mic;
    private GrokbotMark mark;
    private String pendingId;
    private String botName = "Grok Bot";
    private static final int HISTORY = 30;
    private Theme t;
    private Typeface mono;
    /** True while the chat is on screen: replies then land here, not in the unread count. */
    static volatile boolean visible;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = Theme.cur();
        try {
            mono = CastUi.font(this);
        } catch (Exception e) {
            mono = Typeface.MONOSPACE;
        }
        LauncherConfig cfg = LauncherConfig.get(this);
        if (cfg.grokbotBotName != null && !cfg.grokbotBotName.isEmpty()) botName = cfg.grokbotBotName;

        FrameLayout shade = new FrameLayout(this);
        shade.setBackgroundColor(0xB0000000);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.withAlpha(t.bg, 0.97f));
        bg.setStroke(dp(2), t.activeBorder);
        bg.setCornerRadius(dp(6));
        panel.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        mark = new GrokbotMark(this);
        CompanionOverlay.paint(mark, cfg);
        head.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(botName + " · Grok Bot Companion (TV)", 16, t.fgBright, true);
        title.setPadding(dp(12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(head);
        TextView via = text(routeNote(), 11, t.fgDim, false);
        via.setPadding(0, dp(6), 0, dp(10));
        panel.addView(via);

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(log);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ask " + botName + "…  (OK / Enter to send)");
        input.setTextColor(t.fgBright);
        input.setHintTextColor(t.fgDim);
        input.setTypeface(mono);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_DONE || enter) {
                send(input.getText().toString());
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mic = button("🎤 Speak");
        mic.setOnClickListener(v -> voice());
        row.addView(mic);
        TextView sendBtn = button("Send ›");
        sendBtn.setOnClickListener(v -> send(input.getText().toString()));
        row.addView(sendBtn);
        panel.addView(row);
        TextView clear = button("Clear");
        clear.setOnClickListener(v -> {
            prefs().edit().remove(historyKey()).apply();
            log.removeAllViews();
        });
        row.addView(clear);
        TextView foot = text("Built for @tcballard's Grok Bot Desktop Companion v0.1.0 (MIT) · try \"open YouTube\"", 10, t.fgDim, false);
        foot.setPadding(0, dp(8), 0, 0);
        panel.addView(foot);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(720), dp(460), Gravity.CENTER);
        shade.addView(panel, lp);
        setContentView(shade);

        restoreHistory();
        if (cfg.grokbotMessage != null && !cfg.grokbotMessage.isEmpty()) line(botName, cfg.grokbotMessage, false, false);
        String q = getIntent().getStringExtra("q");
        if (q != null && !q.trim().isEmpty()) send(q);
        if (getIntent().getBooleanExtra("voice", false)) voice();
        else mic.requestFocus(); // soft keyboard opens only when the user selects the text field
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        // Physical/Bluetooth keyboard: typing while focus is elsewhere goes straight into the field.
        if (input != null && !input.hasFocus() && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.isPrintingKey() && e.getUnicodeChar() != 0) {
            input.requestFocus();
            input.append(String.valueOf((char) e.getUnicodeChar()));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private String route() {
        LauncherConfig cfg = LauncherConfig.get(this);
        if ("ask".equals(cfg.companionAgent)) return "ask";   // Settings → Agent: OmarchyTV's Ask (Claude, Codex, Hermes…)
        return CompanionPlacement.route(cfg.grokbotBotName, OmarchyLink.hasPc(), true);
    }

    private String routeNote() {
        return "companion".equals(route())
                ? "Answers come from @tcballard's Grok Bot Desktop Companion on your PC (omarchy-link)."
                : "No PC companion linked: questions go to OmarchyTV's Ask.";
    }

    private void send(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        if (q.length() > 240) q = q.substring(0, 240);
        input.setText("");
        line("You", q, true);
        if (launchByName(CompanionCommands.launchQuery(q))) return;
        if ("companion".equals(route())) {
            pendingId = UUID.randomUUID().toString();
            line(botName, "…", false, false);
            mark.pose("think");
            OmarchyLink.grokbotAsk(pendingId, q, null);
        } else {
            line("OmarchyTV", "Opening Ask with your question…", false);
            startActivity(new Intent(this, AskActivity.class).putExtra("cmd", q));
        }
    }

    private void voice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, MainActivity.speechLanguage(this))
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask " + botName);
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            line("OmarchyTV", "No speech recognizer on this TV: type instead (the keyboard's own mic may work).", false);
            input.requestFocus();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE || res != RESULT_OK || data == null) return;
        List<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r != null && !r.isEmpty()) send(r.get(0));
    }

    @Override
    public void onLinkEvent(String type, JSONObject d) {
        if (!"grokbot_answer".equals(type) && !"grokbot_error".equals(type)) return;
        runOnUiThread(() -> {
            if (pendingId == null || !pendingId.equals(d.optString("id"))) return;
            pendingId = null;
            if (log.getChildCount() > 0) log.removeViewAt(log.getChildCount() - 1);   // the "…" line
            mark.pose("grokbot_answer".equals(type) ? "happy" : "alert");
            mark.postDelayed(() -> mark.pose("idle"), 2500);
            if ("grokbot_answer".equals(type)) {
                String text = d.optString("text", "");
                String mode = d.optString("mode");
                line(d.optString("bot", botName), "sent".equals(mode)
                        ? "Sent ✓ The answer appears on the PC companion (its webhook replies there)."
                        : "ipc".equals(mode) || text.isEmpty()
                        ? "Sent to the companion on the PC; the reply is in its desktop bubble (IPC-only mode)." : text, false);
                linkButtons(d.optJSONArray("links"), text);
                mark.blink(false);
                mark.postDelayed(() -> mark.blink(true), 160);
            } else {
                String code = d.optString("error", "failed");
                line("OmarchyTV", "not_connected".equals(code) ? "No PC connected over omarchy-link."
                        : "busy".equals(code) ? "One question every 3 seconds." : d.optString("message", code), false);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        markRead(this, LauncherConfig.get(this));
        OmarchyLink.addListener(this);
    }

    @Override
    protected void onPause() {
        visible = false;
        OmarchyLink.removeListener(this);
        super.onPause();
    }

    private void line(String who, String msg, boolean me) {
        line(who, msg, me, true);
    }

    private void line(String who, String msg, boolean me, boolean keep) {
        TextView v = text(who + ": " + msg, 13, me ? t.cyan : t.fgBright, false);
        v.setPadding(0, dp(3), 0, dp(3));
        log.addView(v);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        if (keep) remember(who, msg, me);
    }

    // ---- local app launching ("open Netflix", "apri YouTube") ----

    /** Opens the installed app that best matches the spoken name; false when none does (the agent answers). */
    private boolean launchByName(String query) {
        if (query == null || query.isEmpty()) return false;
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> r = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            if (r != null) found.addAll(r);
        }
        List<String[]> names = new ArrayList<>();
        List<ResolveInfo> infos = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            names.add(new String[]{String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName});
            infos.add(ri);
        }
        int k = CompanionCommands.matchApp(query, names);
        if (k < 0) return false;
        ResolveInfo ri = infos.get(k);
        Intent i = pm.getLeanbackLaunchIntentForPackage(ri.activityInfo.packageName);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            line(botName, "Opening " + names.get(k)[0] + "…", false);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ---- links in a reply: a button each, opened only with OK on it ----

    private void linkButtons(JSONArray fromPc, String text) {
        List<String> urls = new ArrayList<>();
        if (fromPc != null) {
            for (int i = 0; i < fromPc.length() && urls.size() < 6; i++) {
                JSONObject l = fromPc.optJSONObject(i);
                String u = l != null ? l.optString("url", "") : "";
                if (u.startsWith("https://") || u.startsWith("http://")) urls.add(u);
            }
        }
        if (urls.isEmpty()) urls = CompanionCommands.links(text);
        for (String u : urls) {
            final String url = u;
            final String id = CompanionCommands.youtubeId(url);
            String shown = url.replaceFirst("^https?://", "");
            if (shown.length() > 48) shown = shown.substring(0, 48) + "…";
            TextView b = button((DhhFeed.validId(id) ? "▶ Play on YouTube · " : "🔗 Open · ") + shown);
            b.setOnClickListener(v -> {
                if (DhhFeed.validId(id)) YoutubeEmbedActivity.openInYoutubeApp(this, id, null);
                else WebAppActivity.openUrl(this, "Link", url);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            log.addView(b, lp);
            if (urls.indexOf(u) == 0) b.requestFocus();
        }
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ---- per-bot history (the last 30 lines) ----

    private SharedPreferences prefs() {
        return prefs(this);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("grokbot_companion", MODE_PRIVATE);
    }

    private String historyKey() {
        return "chat:" + botName;
    }

    /** The bot name the chat files history and unread under. */
    static String botName(LauncherConfig cfg) {
        return cfg.grokbotBotName != null && !cfg.grokbotBotName.isEmpty() ? cfg.grokbotBotName : "Grok Bot";
    }

    private void remember(String who, String msg, boolean me) {
        remember(prefs(), historyKey(), who, msg, me);
    }

    private static void remember(SharedPreferences p, String key, String who, String msg, boolean me) {
        try {
            JSONArray a = new JSONArray(p.getString(key, "[]"));
            a.put(new JSONObject().put("who", who).put("msg", msg.length() > 4000 ? msg.substring(0, 4000) : msg).put("me", me));
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - HISTORY); i < a.length(); i++) keep.put(a.get(i));
            p.edit().putString(key, keep.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---- unread (the dot on the floating companion) ----

    /** A reply that arrived while the chat was closed: kept in the bot's history and counted as unread. */
    static void deliver(Context c, LauncherConfig cfg, String who, String msg) {
        if (msg == null || msg.isEmpty()) return;
        String bot = botName(cfg);
        SharedPreferences p = prefs(c);
        remember(p, "chat:" + bot, who == null || who.isEmpty() ? bot : who, msg, false);
        p.edit().putInt("unread:" + bot, Math.min(99, p.getInt("unread:" + bot, 0) + 1)).apply();
    }

    /** A new desktop bubble line from the PC companion counts once as unread. */
    static void noteBubble(Context c, LauncherConfig cfg) {
        String m = cfg.grokbotMessage;
        if (visible || m == null || m.isEmpty()) return;
        String bot = botName(cfg);
        SharedPreferences p = prefs(c);
        if (m.equals(p.getString("seen:" + bot, ""))) return;
        p.edit().putString("seen:" + bot, m).putInt("unread:" + bot, Math.min(99, p.getInt("unread:" + bot, 0) + 1)).apply();
    }

    static int unread(Context c, LauncherConfig cfg) {
        return prefs(c).getInt("unread:" + botName(cfg), 0);
    }

    static void markRead(Context c, LauncherConfig cfg) {
        String bot = botName(cfg);
        String m = cfg.grokbotMessage == null ? "" : cfg.grokbotMessage;
        prefs(c).edit().putInt("unread:" + bot, 0).putString("seen:" + bot, m).apply();
    }

    private void restoreHistory() {
        try {
            JSONArray a = new JSONArray(prefs().getString(historyKey(), "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                line(o.optString("who"), o.optString("msg"), o.optBoolean("me"), false);
            }
        } catch (Exception ignored) {
        }
    }

    private TextView button(String s) {
        TextView v = text(s, 13, t.fg, true);
        v.setFocusable(true);
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        v.setLayoutParams(lp);
        style(v, false);
        v.setOnFocusChangeListener((x, f) -> style((TextView) x, f));
        return v;
    }

    private void style(TextView v, boolean f) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(f ? t.selection : Theme.withAlpha(t.bgDarker, 0.8f));
        g.setStroke(dp(f ? 2 : 1), f ? t.activeBorder : t.inactiveBorder);
        v.setBackground(g);
        v.setTextColor(f ? t.fgBright : t.fg);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(mono, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
