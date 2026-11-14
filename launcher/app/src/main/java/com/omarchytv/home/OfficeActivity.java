package com.omarchytv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GrokBot Office (v2.20, optional, off by default: Settings › Grok Bot e AI). A start screen with "Ask GrokBot" and
 * the mic, then the office (OfficeView) with the bots the PC pushed, the weekly usage card when there are numbers,
 * "Anything else?" and the Hire card. Questions and hires go to the user's own Grok Bot routine through Grok Bot
 * diretto (the same webhook, key, topic and reply server as GrokbotDirectActivity), with an "office" object added.
 * 2.20 multi-provider: the chip next to the input picks who answers: Grok Bot (the webhook) or an AI the paired PC
 * announced (omarchy-link's assistant over the sealed link: the "ask" event; keys stay on the PC). Hire for Claude /
 * ChatGPT is an "office_hire" event: the PC checks the key with the provider and saves the bot, and only its yes
 * brings the bot in. The usage card also lists each provider's real spend (providers_usage).
 */
public class OfficeActivity extends Activity implements OmarchyLink.Listener {
    static final String PREFS = "office";
    private static final int REQ_MIC = 46, REQ_VOICE = 47;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Theme t;
    private Typeface mono;
    private OfficeView office;
    private FrameLayout start;
    private TextView header, usagePct, usageLabel, hint, reply, status, hireName, hireRole;
    private View usageCard, usageFill, hireCard, hireBtn;
    private LinearLayout usageBar;
    private EditText startInput, officeInput;
    private Office.Data data = Office.Data.empty();
    private Office.Pending pending;
    private String hireDraftName = "", hireDraftRole = "", hireDraftText = "";
    private volatile String pendingId;
    private long fileStamp = -1;
    private boolean resumed;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private String queuedSpeech;
    private android.speech.SpeechRecognizer recognizer;
    private boolean listening;
    private static final String[] HIRE_PROVIDERS = {"grok", "claude", "chatgpt"};
    private TextView provStart, provOffice, hireProv;
    private LinearLayout spendList;
    private String hireDraftProvider = "grok";
    /** The PC answer or hire this screen waits for (link events carry the id). */
    private String pcId, pcKind;
    private final StringBuilder pcText = new StringBuilder();
    private final Runnable pcTimeout = () -> {
        if (pcId == null) return;
        boolean hiring = "hire".equals(pcKind);
        pcId = null;
        if (hiring) dropPcPending();
        say(getString(R.string.ask_not_connected));
    };

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    /** On/off; also shows or hides the "GrokBot Office" banner in the TV's own app row. */
    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
        try {
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, "com.omarchytv.home.OfficeLauncher"),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
        }
    }

    /** Where the PC's push lands (LinkServer PUT /v1/office) and the replies update: next to omarchytv.json. */
    static File file(Context c) {
        File d = c.getExternalFilesDir(null);
        return new File(d != null ? d : c.getFilesDir(), Office.FILE_NAME);
    }

    static void open(Context c) {
        Intent i = new Intent(c, OfficeActivity.class);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (!enabled(this)) {   // the banner may still be in a launcher's cache: say where to turn it on
            android.widget.Toast.makeText(this, getString(R.string.office_setting) + ": " + getString(R.string.off), android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        t = Theme.cur();
        try {
            mono = CastUi.font(this);
        } catch (Exception e) {
            mono = Typeface.MONOSPACE;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(OfficeView.shade(t.bg, -0.6f));
        office = new OfficeView(this, t, mono);
        office.setLabels(getString(R.string.office_pending), getString(R.string.office_more));
        root.addView(office, new FrameLayout.LayoutParams(-1, -1));

        header = text("", 22, t.fgBright, true);
        root.addView(header, at(-2, -2, Gravity.TOP | Gravity.START, 36, 24, 0, 0));

        LinearLayout uc = new LinearLayout(this);
        uc.setOrientation(LinearLayout.VERTICAL);
        uc.setPadding(dp(14), dp(10), dp(14), dp(10));
        uc.setBackground(box(Theme.withAlpha(t.bg, 0.9f), Theme.withAlpha(t.accent, 0.55f), 10, 2));
        usagePct = text("", 32, t.fgBright, true);
        uc.addView(usagePct);
        usageBar = new LinearLayout(this);
        usageBar.setBackground(box(Theme.withAlpha(t.fg, 0.2f), 0, 6, 0));
        usageFill = new View(this);
        usageFill.setBackground(box(t.accent, 0, 6, 0));
        usageBar.addView(usageFill, new LinearLayout.LayoutParams(0, dp(8)));
        uc.addView(usageBar, new LinearLayout.LayoutParams(dp(240), dp(8)));
        usageLabel = text("", 13, t.fg, false);
        usageLabel.setPadding(0, dp(6), 0, 0);
        uc.addView(usageLabel);
        spendList = new LinearLayout(this);
        spendList.setOrientation(LinearLayout.VERTICAL);
        uc.addView(spendList);
        usageCard = uc;
        root.addView(uc, at(-2, -2, Gravity.BOTTOM | Gravity.START, 36, 0, 0, 40));

        hint = text(getString(R.string.office_empty), 16, t.fgBright, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(20), dp(16), dp(20), dp(16));
        hint.setBackground(box(Theme.withAlpha(t.bg, 0.93f), t.accent, 10, 2));
        root.addView(hint, at(dp(620), -2, Gravity.CENTER, 0, 0, 0, 0));

        reply = text("", 15, t.fgBright, false);
        reply.setPadding(dp(16), dp(10), dp(16), dp(10));
        reply.setMaxLines(4);
        reply.setBackground(box(Theme.withAlpha(t.bg, 0.93f), Theme.withAlpha(t.accent, 0.5f), 10, 2));
        reply.setVisibility(View.GONE);
        root.addView(reply, at(dp(640), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 104));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        officeInput = input(getString(R.string.office_anything));
        bar.addView(officeInput, new LinearLayout.LayoutParams(dp(470), -2));
        provOffice = button("");
        provOffice.setOnClickListener(v -> nextProvider());
        bar.addView(provOffice);
        TextView mic = button("🎤 " + getString(R.string.office_mic));
        mic.setOnClickListener(v -> voice());
        bar.addView(mic);
        root.addView(bar, at(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 36));

        TextView keys = text(getString(R.string.office_keys), 11, Theme.withAlpha(t.fg, 0.6f), false);
        root.addView(keys, at(-2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, 36, 12));

        LinearLayout hc = new LinearLayout(this);
        hc.setOrientation(LinearLayout.VERTICAL);
        hc.setPadding(dp(20), dp(18), dp(20), dp(18));
        hc.setBackground(box(Theme.withAlpha(t.bg, 0.96f), t.accent, 14, 2));
        hc.addView(text(getString(R.string.office_hire_title), 20, t.fgBright, true));
        hc.addView(label(getString(R.string.office_name)));
        hireName = text("", 17, t.fgBright, false);
        hc.addView(hireName);
        hc.addView(label(getString(R.string.office_role)));
        hireRole = text("", 17, t.fgBright, false);
        hc.addView(hireRole);
        hc.addView(label(getString(R.string.office_ai)));
        LinearLayout prow = new LinearLayout(this);
        prow.setPadding(0, dp(4), 0, 0);
        hireProv = button("");
        ((LinearLayout.LayoutParams) hireProv.getLayoutParams()).leftMargin = 0;
        hireProv.setOnClickListener(v -> {
            int i = java.util.Arrays.asList(HIRE_PROVIDERS).indexOf(hireDraftProvider);
            hireDraftProvider = HIRE_PROVIDERS[(i + 1) % HIRE_PROVIDERS.length];
            showHireProvider();
        });
        prow.addView(hireProv);
        hc.addView(prow);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(16), 0, 0);
        TextView cancel = button(getString(R.string.office_cancel));
        cancel.setOnClickListener(v -> closeHire());
        row.addView(cancel);
        TextView hire = button(getString(R.string.office_hire));
        hire.setOnClickListener(v -> hireNow());
        row.addView(hire);
        hireBtn = hire;
        hc.addView(row, new LinearLayout.LayoutParams(-1, -2));
        hc.setVisibility(View.GONE);
        hireCard = hc;
        root.addView(hc, at(dp(400), -2, Gravity.END | Gravity.TOP, 0, 150, 60, 0));

        // the start screen: the mark, "GrokBot Office", Ask GrokBot, the mic
        start = new FrameLayout(this);
        start.setBackgroundColor(OfficeView.shade(t.bg, -0.7f));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        View markView = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                OfficeView.mark(c, getWidth() / 2f, getHeight() / 2f, getWidth() * 0.5f, t.accent, t.fgBright);
            }
        };
        col.addView(markView, new LinearLayout.LayoutParams(dp(200), dp(200)));
        TextView wm = text("GrokBot Office", 34, t.fgBright, true);
        android.text.SpannableString sp = new android.text.SpannableString("GrokBot Office");
        sp.setSpan(new android.text.style.ForegroundColorSpan(t.accent), 8, 14, 0);
        wm.setText(sp);
        wm.setPadding(0, dp(8), 0, dp(28));
        col.addView(wm);
        LinearLayout srow = new LinearLayout(this);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        startInput = input(getString(R.string.office_ask) + "…");
        srow.addView(startInput, new LinearLayout.LayoutParams(dp(460), -2));
        provStart = button("");
        provStart.setOnClickListener(v -> nextProvider());
        srow.addView(provStart);
        TextView smic = button("🎤");
        smic.setOnClickListener(v -> voice());
        srow.addView(smic);
        TextView go = button(getString(R.string.office_enter) + " ›");
        go.setOnClickListener(v -> enter(true));
        srow.addView(go);
        col.addView(srow);
        status = text("", 16, t.cyan, false);
        status.setPadding(0, dp(20), 0, 0);
        col.addView(status);
        start.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(start, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        SharedPreferences pp = prefs(this);
        pending = Office.pending(pp.getString("pending", ""), System.currentTimeMillis());
        reload(true);
        showProvider();
        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS && tts != null) {
                tts.setLanguage(Locale.forLanguageTag(MainActivity.speechLanguage(this)));
                ttsReady = true;
                if (queuedSpeech != null) speak(queuedSpeech);
                queuedSpeech = null;
            }
        });
        startInput.requestFocus();
    }

    // ---- data: the pushed file, checked every few seconds while the office is on screen ----

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            reload(false);
            if (resumed) ui.postDelayed(this, 3000);
        }
    };

    private void reload(boolean force) {
        File f = file(this);
        long st = f.isFile() ? f.lastModified() ^ f.length() : 0;
        if (!force && st == fileStamp) return;
        fileStamp = st;
        data = Office.load(f);
        refresh();
    }

    private void setData(Office.Data d) {
        data = d;
        try {
            Office.save(file(this), d);
            File f = file(this);
            fileStamp = f.lastModified() ^ f.length();
        } catch (Exception ignored) {
        }
        refresh();
    }

    private void refresh() {
        if (pending != null && Office.confirmed(data, pending)) {
            String nm = pending.name;
            pending = null;
            prefs(this).edit().remove("pending").apply();
            Office.Bot nb = null;
            for (Office.Bot b : data.bots) if (!nm.isEmpty() && b.name.equalsIgnoreCase(nm)) nb = b;
            if (nb == null && !data.bots.isEmpty()) nb = data.bots.get(data.bots.size() - 1);
            if (nb != null) say(getString(R.string.office_welcome, nb.name));
        }
        office.setData(data, pending);
        int n = data.bots.size();
        android.text.SpannableStringBuilder h = new android.text.SpannableStringBuilder("GrokBot ");
        int s0 = h.length();
        h.append("Office");
        h.setSpan(new android.text.style.ForegroundColorSpan(t.accent), s0, h.length(), 0);
        int s1 = h.length();
        h.append("  · ").append(n == 1 ? getString(R.string.office_one_bot) : getString(R.string.office_bots, n));
        h.setSpan(new android.text.style.ForegroundColorSpan(t.fg), s1, h.length(), 0);
        header.setText(h);
        usageCard.setVisibility(data.usage != null || !data.spend.isEmpty() ? View.VISIBLE : View.GONE);   // no numbers, no card
        usagePct.setVisibility(data.usage != null ? View.VISIBLE : View.GONE);
        usageBar.setVisibility(data.usage != null ? View.VISIBLE : View.GONE);
        usageLabel.setVisibility(data.usage != null ? View.VISIBLE : View.GONE);
        spendList.removeAllViews();
        for (Office.Spend r : data.spend) {   // real spend per provider (the PC's Admin API keys); a bar only with a budget
            int pc = android.graphics.Color.parseColor(Office.providerColor(r.provider));
            android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder(r.provider);
            line.setSpan(new android.text.style.ForegroundColorSpan(pc), 0, line.length(), 0);
            line.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, line.length(), 0);
            line.append(" ").append(r.unit).append(String.format(Locale.ROOT, "%.2f", r.spent));
            if (r.budget > 0) line.append(" / ").append(r.unit).append(String.format(Locale.ROOT, "%.0f", r.budget));
            int m0 = line.length();
            line.append(" ").append(getString(R.string.office_month));
            line.setSpan(new android.text.style.ForegroundColorSpan(t.fg), m0, line.length(), 0);
            TextView tv = text("", 13, t.fgBright, false);
            tv.setText(line);
            tv.setPadding(0, dp(8), 0, 0);
            spendList.addView(tv);
            if (r.pct >= 0) {
                LinearLayout bb = new LinearLayout(this);
                bb.setBackground(box(Theme.withAlpha(t.fg, 0.2f), 0, 4, 0));
                View fill = new View(this);
                fill.setBackground(box(pc, 0, 4, 0));
                bb.addView(fill, new LinearLayout.LayoutParams(Math.round(dp(240) * (float) r.pct / 100f), dp(5)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(5));
                lp.topMargin = dp(3);
                spendList.addView(bb, lp);
            }
        }
        if (data.usage != null) {
            double p = data.usage.pct;
            usagePct.setText((p == Math.rint(p) ? String.valueOf((long) p) : String.valueOf(p)) + "%");
            usageFill.setLayoutParams(new LinearLayout.LayoutParams(Math.round(dp(240) * (float) Math.min(100, p) / 100f), dp(8)));
            usageLabel.setText((data.usage.label.isEmpty() ? getString(R.string.office_usage) : data.usage.label)
                    + (data.usage.resets.isEmpty() ? "" : " · " + getString(R.string.office_resets, data.usage.resets)));
        }
        hint.setVisibility(start.getVisibility() != View.VISIBLE && n == 0 && pending == null && hireCard.getVisibility() != View.VISIBLE
                && reply.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        OmarchyLink.addListener(this);
    }

    @Override
    protected void onStop() {
        OmarchyLink.removeListener(this);
        super.onStop();
    }

    // ---- who answers: Grok Bot (the webhook) or an AI on the PC ----

    /** {id, name, family}: Grok Bot first, then what the paired PC announced. Nothing announced, nothing offered. */
    private List<String[]> providers() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"grok", "Grok Bot", "grok"});
        if (!OmarchyLink.hasPc()) return out;
        for (LauncherConfig.Provider p : LauncherConfig.get(this).aiProviders) {
            if ("grok-bot".equals(p.id) || out.size() > 16) continue;
            out.add(new String[]{"pc:" + p.id, p.name, Office.family(p.family, p.id)});
        }
        return out;
    }

    private String[] provider() {
        List<String[]> l = providers();
        String want = prefs(this).getString("provider", "grok");
        for (String[] p : l) if (p[0].equals(want)) return p;
        return l.get(0);
    }

    private void nextProvider() {
        List<String[]> l = providers();
        String cur = provider()[0];
        int i = 0;
        while (i < l.size() && !l.get(i)[0].equals(cur)) i++;
        prefs(this).edit().putString("provider", l.get((i + 1) % l.size())[0]).apply();
        showProvider();
    }

    private CharSequence chip(String family, String name) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder("◆ ");
        b.setSpan(new android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor(Office.providerColor(family))), 0, 1, 0);
        b.append(name);
        return b;
    }

    private void showProvider() {
        String[] p = provider();
        provStart.setText(chip(p[2], p[1]));
        provOffice.setText(chip(p[2], p[1]));
        startInput.setHint("grok".equals(p[0]) ? getString(R.string.office_ask) + "…" : getString(R.string.office_ask).replace("GrokBot", p[1]) + "…");
    }

    private void showHireProvider() {
        String n = "claude".equals(hireDraftProvider) ? "Claude" : "chatgpt".equals(hireDraftProvider) ? "ChatGPT" : "Grok Bot";
        hireProv.setText(chip(hireDraftProvider, n + " ▸"));
    }

    @Override
    public void onLinkEvent(String type, JSONObject d) {
        if (pcId == null || !pcId.equals(d.optString("id"))) return;
        if ("answer".equals(type) && "ask".equals(pcKind)) {
            pcText.append(d.optString("text", ""));
            if (!d.optBoolean("done")) return;
            String text = d.optString("final", pcText.toString());
            pcId = null;
            ui.removeCallbacks(pcTimeout);
            officeInput.setHint(R.string.office_anything);
            say(text.isEmpty() ? pcText.toString() : text);
        } else if ("answer_error".equals(type) && "ask".equals(pcKind)) {
            pcId = null;
            ui.removeCallbacks(pcTimeout);
            officeInput.setHint(R.string.office_anything);
            String m = d.optString("message", "");
            say(m.isEmpty() ? d.optString("error", "error") : m);
        } else if ("office_hire_result".equals(type) && "hire".equals(pcKind)) {
            pcId = null;
            ui.removeCallbacks(pcTimeout);
            JSONObject bot = d.optJSONObject("bot");
            if (!d.optBoolean("ok") || bot == null) {   // no yes, no bot
                dropPcPending();
                String m = d.optString("message", "");
                say(m.isEmpty() ? d.optString("error", "error") : m);
                return;
            }
            String name = Office.clean(bot.optString("name"), 24);
            pending = null;
            setData(Office.applyReply(data, new JSONObjectWrap().put("hired", bot).o));
            say(getString(R.string.office_hired_pc, name));
        }
    }

    private static final class JSONObjectWrap {
        final JSONObject o = new JSONObject();

        JSONObjectWrap put(String k, Object v) {
            try {
                o.put(k, v);
            } catch (Exception ignored) {
            }
            return this;
        }
    }

    private void dropPcPending() {
        pending = null;
        refresh();
    }

    /** The question goes to an AI on the PC (the "ask" event over the sealed link); the reply is shown and spoken. */
    private void askPc(String providerId, String q) {
        if (pcId != null || pendingId != null) return;
        if (!OmarchyLink.hasPc()) {
            say(getString(R.string.office_pc_off));
            return;
        }
        String id = Long.toHexString(new SecureRandom().nextLong());
        JSONObject d = new JSONObjectWrap().put("id", id).put("text", q).put("lang", MainActivity.speechLanguage(this)).put("provider", providerId).o;
        pcId = id;
        pcKind = "ask";
        pcText.setLength(0);
        say(getString(R.string.office_thinking));
        officeInput.setHint(R.string.office_thinking);
        ui.postDelayed(pcTimeout, 90_000);
        OmarchyLink.ask(d, err -> {
            if (err == null || !id.equals(pcId)) return;
            pcId = null;
            ui.removeCallbacks(pcTimeout);
            officeInput.setHint(R.string.office_anything);
            say(getString("busy".equals(err) ? R.string.ask_busy : R.string.office_pc_off));
        });
    }

    /** Claude / ChatGPT: the PC checks the key and saves the bot; it walks in while we wait, and stays only on a yes. */
    private void hirePc() {
        if (pcId != null || pendingId != null) return;
        if (!OmarchyLink.hasPc()) {
            say(getString(R.string.office_pc_off));
            return;
        }
        String id = Long.toHexString(new SecureRandom().nextLong());
        JSONObject d = new JSONObjectWrap().put("id", id).put("name", hireDraftName).put("provider", hireDraftProvider)
                .put("role", hireDraftRole).put("description", hireDraftText).o;
        pcId = id;
        pcKind = "hire";
        pending = new Office.Pending(hireDraftName, hireDraftRole, System.currentTimeMillis(), data.bots.size(), hireDraftProvider);
        say(getString(R.string.office_hiring, hireDraftName.isEmpty() ? getString(R.string.office_someone) : hireDraftName));
        ui.postDelayed(pcTimeout, 60_000);
        OmarchyLink.officeHire(d, err -> {
            if (err == null || !id.equals(pcId)) return;
            pcId = null;
            ui.removeCallbacks(pcTimeout);
            dropPcPending();
            say(getString("busy".equals(err) ? R.string.ask_busy : R.string.office_pc_off));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.post(watch);
        if (office != null) office.setRunning(start.getVisibility() != View.VISIBLE);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(watch);
        if (office != null) office.setRunning(false);   // no frames, no CPU while hidden
        stopListening();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        pendingId = null;
        pcId = null;
        ui.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    // ---- flow ----

    private void enter(boolean spoken) {
        if (spoken) speak(getString(R.string.office_opening));
        start.setVisibility(View.GONE);
        office.setRunning(resumed);
        officeInput.requestFocus();
        refresh();
    }

    private EditText input() {
        return start.getVisibility() == View.VISIBLE ? startInput : officeInput;
    }

    private void submit(String raw) {
        String q = Office.clean(raw, 240);
        if (q.isEmpty()) return;
        EditText in = input();
        in.setText("");
        hideKeyboard(in);
        Office.Asked it = Office.intent(q);
        switch (it.type) {
            case "hire":
                if (start.getVisibility() == View.VISIBLE) enter(true);
                hireDraftName = it.name;
                hireDraftRole = it.role;
                hireDraftText = q;
                String pp = provider()[2];   // "hire a claude bot…" names it; else the AI picked in the chip
                hireDraftProvider = !"grok".equals(it.provider) ? it.provider : java.util.Arrays.asList(HIRE_PROVIDERS).contains(pp) ? pp : "grok";
                showHireProvider();
                hireName.setText(it.name.isEmpty() ? "(" + getString(R.string.office_picked) + ")" : it.name);
                hireRole.setText(it.role.isEmpty() ? "—" : it.role);
                reply.setVisibility(View.GONE);
                hireCard.setVisibility(View.VISIBLE);
                hireBtn.requestFocus();
                refresh();
                return;
            case "open":
                reply.setVisibility(View.GONE);
                enter(true);
                return;
            default:
                if (start.getVisibility() == View.VISIBLE) enter(false);
                String[] pv = provider();
                if (!"grok".equals(pv[0])) askPc(pv[0].substring(3), q);
                else askBot(Office.askRequest(q, data), false, null);
        }
    }

    private void closeHire() {
        hireCard.setVisibility(View.GONE);
        officeInput.requestFocus();
        refresh();
    }

    private void hireNow() {
        hireCard.setVisibility(View.GONE);
        if (!"grok".equals(hireDraftProvider)) {
            officeInput.requestFocus();
            hirePc();
            return;
        }
        pending = new Office.Pending(hireDraftName, hireDraftRole, System.currentTimeMillis(), data.bots.size());
        prefs(this).edit().putString("pending", Office.pendingJson(pending).toString()).apply();
        officeInput.requestFocus();
        say(getString(R.string.office_sent, hireDraftName.isEmpty() ? getString(R.string.office_someone) : hireDraftName));
        refresh();
        askBot(Office.hireRequest(hireDraftName, hireDraftRole, hireDraftText, data), true, ok -> {
            if (!ok && pending != null && !Office.confirmed(data, pending)) {   // not sent: no ghost at the door
                pending = null;
                prefs(this).edit().remove("pending").apply();
                refresh();
            }
        });
    }

    private void say(String text) {
        reply.setText(text);
        reply.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (start.getVisibility() == View.VISIBLE) status.setText(text);
        if (!text.isEmpty()) speak(text);
        refresh();
    }

    private void speak(String text) {
        if (!GrokbotDirectActivity.speak(this)) return;   // the same switch as Grok Bot diretto: Leggi risposte ad alta voce
        if (!ttsReady || tts == null) {
            queuedSpeech = text;
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "office");
    }

    interface Done {
        void done(boolean ok);
    }

    /** One request to the user's Grok Bot routine (Grok Bot diretto), with the office object; the reply may update the office. */
    private void askBot(Office.Request req, boolean keepReply, Done then) {
        if (pendingId != null) return;
        SharedPreferences p = GrokbotDirectActivity.prefs(this);
        final String url = p.getString("url", ""), key = p.getString("key", "");
        if (!GrokbotDirectActivity.enabled(this)) {
            say(getString(R.string.office_not_set));
            if (then != null) then.done(false);
            return;
        }
        final String id = GrokbotDirect.newId(new SecureRandom());
        final String topic = GrokbotDirectActivity.topic(this);
        final String lang = MainActivity.speechLanguage(this).substring(0, 2);
        final List<String> apps = appNames();
        final String server = GrokbotDirect.server(p.getString("server", ""));
        final String token = p.getString("ntfy_token", "");
        pendingId = id;
        if (!keepReply) say(getString(R.string.office_thinking));
        officeInput.setHint(R.string.office_thinking);
        new Thread(() -> {
            String err = null;
            GrokbotDirect.Reply r = null;
            JSONObject off = null;
            try {
                long since = System.currentTimeMillis() / 1000 - 5;
                post(url, GrokbotDirect.headers(key), Office.requestBody(GrokbotDirect.body(req.question, lang, topic, id, apps, "OmarchyTV on Android TV · GrokBot Office", server), req.office));
                long end = System.currentTimeMillis() + GrokbotDirect.TIMEOUT_MS;
                while (r == null && id.equals(pendingId) && System.currentTimeMillis() < end) {
                    Thread.sleep(GrokbotDirect.POLL_MS);
                    try {
                        String nd = get(GrokbotDirect.pollUrl(server, topic, since), token);
                        r = GrokbotDirect.find(nd, id);
                        if (r != null) off = Office.findOffice(nd, id);
                    } catch (Exception ignored) {   // one failed poll: keep trying until the deadline
                    }
                }
                if (r == null && id.equals(pendingId)) err = "No answer from Grok Bot within 90 s. Check the routine and its ntfy step (see docs/GROKBOT-DIRECT.md).";
            } catch (HttpCode e) {
                err = e.code == 401 || e.code == 403 ? "The webhook refused the key (HTTP " + e.code + "): check it in Settings." : "The webhook answered HTTP " + e.code + ".";
            } catch (Exception e) {
                err = "Could not reach the webhook: " + e.getClass().getSimpleName() + ".";   // never the key or URL
            }
            final GrokbotDirect.Reply fr = r;
            final String fe = err;
            final JSONObject fo = off;
            ui.post(() -> {
                if (!id.equals(pendingId)) return;
                pendingId = null;
                if (isFinishing() || isDestroyed()) return;
                officeInput.setHint(R.string.office_anything);
                if (fo != null) setData(Office.applyReply(data, fo));
                if (fr == null) say(fe == null ? "" : fe);
                else say(fr.text);
                if (then != null) then.done(fr != null);
            });
        }, "office-ask").start();
    }

    // ---- keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        EditText in = start == null ? null : input();
        if (in != null && !in.hasFocus() && hireCard.getVisibility() != View.VISIBLE && e.getAction() == KeyEvent.ACTION_DOWN && e.isPrintingKey() && e.getUnicodeChar() != 0) {
            in.requestFocus();   // typing starts the question
            in.append(String.valueOf((char) e.getUnicodeChar()));
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && (e.getKeyCode() == KeyEvent.KEYCODE_SEARCH || e.getKeyCode() == KeyEvent.KEYCODE_VOICE_ASSIST)) {
            voice();   // the remote's mic key
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (hireCard != null && hireCard.getVisibility() == View.VISIBLE) {
            closeHire();
            return;
        }
        if (reply != null && reply.getVisibility() == View.VISIBLE && pendingId == null && pcId == null) {
            reply.setVisibility(View.GONE);
            refresh();
            return;
        }
        super.onBackPressed();
    }

    // ---- voice: the in-app SpeechRecognizer (as GrokbotDirectActivity), the system recognizer as fallback ----

    private void voice() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceIntent();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        listen();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) listen();
        else voiceIntent();
    }

    private void showListening(String extra) {
        String s = "🎤 " + getString(R.string.office_listening) + (extra.isEmpty() ? "" : " " + extra);
        status.setText(s);
        officeInput.setHint(s);
    }

    private void listen() {
        stopListening();
        try {
            recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
        } catch (Exception e) {
            voiceIntent();
            return;
        }
        listening = true;
        if (tts != null) tts.stop();
        showListening("");
        recognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int type, Bundle p) { }

            @Override public void onPartialResults(Bundle r) {
                String s = first(r);
                if (!s.isEmpty()) showListening(s);
            }

            @Override public void onResults(Bundle r) {
                String s = first(r);
                stopListening();
                if (!s.isEmpty()) submit(s);
                else say(getString(R.string.office_heard));
            }

            @Override public void onError(int err) {
                stopListening();
                say(err == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "The microphone permission is off for OmarchyTV." : getString(R.string.office_heard));
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, MainActivity.speechLanguage(this))
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            stopListening();
            voiceIntent();
        }
    }

    private static String first(Bundle r) {
        if (r == null) return "";
        List<String> l = r.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        return l != null && !l.isEmpty() && l.get(0) != null ? l.get(0).trim() : "";
    }

    private void stopListening() {
        if (listening) {
            listening = false;
            if (status != null) status.setText("");
            if (officeInput != null) officeInput.setHint(R.string.office_anything);
        }
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    private void voiceIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, MainActivity.speechLanguage(this))
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.office_ask));
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            say(getString(R.string.office_no_mic));
            input().requestFocus();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (req != REQ_VOICE || res != RESULT_OK || d == null) return;
        List<String> r = d.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        String q = r != null && !r.isEmpty() ? r.get(0) : d.getStringExtra("query");
        if (q != null && !q.trim().isEmpty()) submit(q);
    }

    // ---- HTTP (no logging: the key and the URL never reach logcat) ----

    static final class HttpCode extends Exception {
        final int code;

        HttpCode(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static void post(String url, Map<String, String> headers, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            c.setRequestProperty("User-Agent", "OmarchyTV-office");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpCode(code);
        } finally {
            c.disconnect();
        }
    }

    private static String get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (GrokbotDirect.validToken(token)) c.setRequestProperty("Authorization", "Bearer " + token);
            c.setConnectTimeout(8_000);
            c.setReadTimeout(10_000);
            if (c.getResponseCode() != 200) return "";
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private List<String> appNames() {
        PackageManager pm = getPackageManager();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> r = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            if (r == null) continue;
            for (ResolveInfo ri : r) {
                if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
                if (seen.add(ri.activityInfo.packageName)) out.add(String.valueOf(ri.loadLabel(pm)));
            }
        }
        return out;
    }

    // ---- views ----

    private void hideKeyboard(View v) {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private EditText input(String hintText) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hintText);
        e.setTextColor(t.fgBright);
        e.setHintTextColor(Theme.withAlpha(t.fg, 0.7f));
        e.setTypeface(mono);
        e.setTextSize(17);
        e.setPadding(dp(18), dp(10), dp(18), dp(10));
        e.setImeOptions(EditorInfo.IME_ACTION_SEND);
        style(e, false);
        e.setOnFocusChangeListener((v, f) -> style(v, f));
        e.setOnEditorActionListener((v, id, ev) -> {
            boolean enterKey = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_DONE || enterKey) {
                submit(e.getText().toString());
                return true;
            }
            return false;
        });
        return e;
    }

    private TextView button(String s) {
        TextView v = text(s, 16, t.fgBright, true);
        v.setFocusable(true);
        v.setClickable(true);
        v.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(10);
        v.setLayoutParams(lp);
        style(v, false);
        v.setOnFocusChangeListener((x, f) -> style(x, f));
        return v;
    }

    private void style(View v, boolean f) {
        v.setBackground(box(f && !(v instanceof EditText) ? t.accent : Theme.withAlpha(t.bg, 0.94f), f ? t.accent : Theme.withAlpha(t.fg, 0.35f), 40, f ? 3 : 2));
        if (v instanceof TextView && !(v instanceof EditText)) ((TextView) v).setTextColor(f ? t.bg : t.fgBright);
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView label(String s) {
        TextView v = text(s, 13, t.fg, false);
        v.setPadding(0, dp(10), 0, 0);
        return v;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(mono, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private FrameLayout.LayoutParams at(int w, int h, int gravity, int l, int tp, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(dp(l), dp(tp), dp(r), dp(b));
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
