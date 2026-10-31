package com.omarchytv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * "Grok Bot · Omarchy TUI": native Omarchy-styled terminal assistant & TV control center.
 *
 * Styled like Omarchy v2.9/v2.12 Hyprland windows (BoxView with 2 px border, btop legend cut into the top border,
 * translucent theme surface over the live 4K WallpaperView, CaskaydiaCove Nerd Font, and theme-mapped ANSI colors).
 *
 * Works in three complementary zero-API-cost modes:
 * 1. Immediate Local TV & MCP Control (works offline / standalone on the TV with zero setup):
 *    switch any of the 32 MIT themes, cycle 4K wallpapers, trigger or configure the 37 TTE ASCII screensavers
 *    and speed (slow/normal/fast), launch installed TV apps or Live TV by name, or inspect TV/network/MCP status.
 * 2. Paired PC / Mac CLI Bridge (Omarchy Link PROTOCOL.md §6 "Assistant"):
 *    forwards open-ended questions over the encrypted LAN link to the user's authenticated CLI on their PC/Mac
 *    (grok-bot, claude, codex, gemini, hermes, ollama) and streams the reply typewriter-style.
 * 3. Optional Full WebApp Launcher:
 *    one-click menu or command to open grok.com, x.com/i/grok, chatgpt.com, claude.ai, or Hermes in WebAppActivity (:web).
 */
public class AskActivity extends Activity implements OmarchyLink.Listener {

    private static final long ANSWER_TIMEOUT_MS = 100_000L;
    private static final int REQ_SPEECH = 21;

    private float s;
    private Typeface tf, tfBold;
    private WallpaperView wallpaperView;
    private BoxView win;
    private View topDivider, bottomDivider;
    private TextView headerPrompt, statusBadge, log, bottomPrompt, hint;
    private EditText input;
    private ScrollView scroll;
    private final List<TextView> bracketButtons = new ArrayList<>();
    private AsciiMenu popupMenu;

    private String pendingId, session;
    private final StringBuilder queue = new StringBuilder();
    private int replyStart = -1;
    private String finalText;
    private boolean typing;
    private final SpannableStringBuilder transcript = new SpannableStringBuilder();
    private TextToSpeech tts;
    private boolean ttsReady;

    private final Runnable timeout = () -> {
        if (pendingId != null) {
            pendingId = null;
            line("sys", "timeout waiting for PC response", Theme.cur().red);
        }
    };

    static void open(Context c) {
        Intent i = new Intent(c, AskActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        c.startActivity(i);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        s = Math.min(dm.heightPixels / 1080f, dm.widthPixels / 1920f);
        tf = CastUi.font(this);
        tfBold = Typeface.create(tf, Typeface.BOLD);

        LauncherConfig cfg = LauncherConfig.get(this);
        session = "tv-" + System.currentTimeMillis() / 1000;

        FrameLayout root = new FrameLayout(this);
        wallpaperView = new WallpaperView(this, null);
        root.addView(wallpaperView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        win = new BoxView(this, s, tf);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        wl.leftMargin = wl.rightMargin = px(48);
        wl.topMargin = wl.bottomMargin = px(36);
        root.addView(win, wl);

        // ---- Row 1: Top Terminal Header Bar ----
        LinearLayout topBar = hbox();
        LinearLayout leftStatus = hbox();
        headerPrompt = text("", 21, true);
        statusBadge = text("", 17, false);
        statusBadge.setSingleLine(true);
        statusBadge.setEllipsize(TextUtils.TruncateAt.END);
        leftStatus.addView(headerPrompt);
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(0, -2, 1f);
        sbl.leftMargin = px(12);
        leftStatus.addView(statusBadge, sbl);
        topBar.addView(leftStatus, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView btnVoice = bracketBtn(isIt() ? "🎤 voce" : "🎤 voice", this::listen);
        TextView btnThemes = bracketBtn(isIt() ? "🎨 temi ▾" : "🎨 themes ▾", () -> showThemesPopup(win));
        TextView btnWeb = bracketBtn("🌐 web ▾", () -> showWebPopup(win));
        TextView btnClose = bracketBtn("✕", this::finish);

        addBtn(topBar, btnVoice, 10);
        addBtn(topBar, btnThemes, 10);
        addBtn(topBar, btnWeb, 10);
        addBtn(topBar, btnClose, 10);
        win.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        // ---- Row 2: Quick TUI Command Bar (1-click D-pad pills) ----
        LinearLayout quickBar = hbox();
        LinearLayout.LayoutParams qbl = new LinearLayout.LayoutParams(-1, -2);
        qbl.topMargin = px(10);
        TextView qNextTheme = bracketBtn(isIt() ? "⏭ tema succ." : "⏭ next theme", () -> runCommand("next theme"));
        TextView qWall = bracketBtn(isIt() ? "🖼 sfondo" : "🖼 wallpaper", () -> runCommand("next wallpaper"));
        TextView qSaver = bracketBtn("✦ screensaver", () -> runCommand("screensaver"));
        TextView qStatus = bracketBtn(isIt() ? "📊 stato" : "📊 status", () -> runCommand("status"));
        TextView qApps = bracketBtn("📦 apps", () -> runCommand("apps"));
        TextView qHelp = bracketBtn(isIt() ? "❓ aiuto" : "❓ help", () -> runCommand("help"));
        TextView qGrokWeb = bracketBtn("🌐 grok.com", () -> openWebApp("grok-bot"));

        quickBar.addView(qNextTheme);
        addBtn(quickBar, qWall, 10);
        addBtn(quickBar, qSaver, 10);
        addBtn(quickBar, qStatus, 10);
        addBtn(quickBar, qApps, 10);
        addBtn(quickBar, qHelp, 10);
        addBtn(quickBar, qGrokWeb, 10);
        win.addView(quickBar, qbl);

        topDivider = new View(this);
        LinearLayout.LayoutParams tdl = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
        tdl.topMargin = px(10);
        win.addView(topDivider, tdl);

        // ---- Row 3: Scrollable Terminal Transcript ----
        scroll = new ScrollView(this);
        scroll.setFocusable(true);
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-1, 0, 1f);
        scl.topMargin = px(10);
        scl.bottomMargin = px(10);
        log = text("", 18, false);
        log.setLineSpacing(px(4), 1.18f);
        scroll.addView(log, new FrameLayout.LayoutParams(-1, -2));
        win.addView(scroll, scl);

        bottomDivider = new View(this);
        LinearLayout.LayoutParams bdl = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
        bdl.bottomMargin = px(10);
        win.addView(bottomDivider, bdl);

        // ---- Row 4: Interactive Terminal Prompt Input Row ----
        LinearLayout inputRow = hbox();
        bottomPrompt = text("", 19, true);
        inputRow.addView(bottomPrompt);

        input = new EditText(this);
        input.setTypeface(tf);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19 * s);
        input.setSingleLine(true);
        input.setShowSoftInputOnFocus(false);
        input.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        input.setBackground(null);
        input.setPadding(px(8), px(6), px(8), px(6));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setHint(isIt()
                ? "Chiedi a Grok Bot o esegui un comando TV (es. tema jade-horse, sfondo, apri YouTube)…"
                : "Ask Grok Bot or run a TV command (e.g. theme jade-horse, wallpaper, open YouTube)…");
        input.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submitInput();
                return true;
            }
            return false;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView btnSend = bracketBtn(isIt() ? "↵ invia" : "↵ run", this::submitInput);
        addBtn(inputRow, btnSend, 10);
        win.addView(inputRow, new LinearLayout.LayoutParams(-1, -2));

        // ---- Row 5: Footer Key Hints ----
        hint = text("", 14, false);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = px(8);
        win.addView(hint, hl);

        setContentView(root);
        applyThemeStyle();
        printWelcomeBanner();

        if (cfg.assistantTts) {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) tts.setLanguage(Locale.forLanguageTag(MainActivity.speechLanguage(this)));
            });
        }

        String initCmd = getIntent().getStringExtra("cmd");
        if (initCmd != null && !initCmd.trim().isEmpty()) {
            runCommand(initCmd.trim());
        } else {
            btnVoice.requestFocus();
        }
    }

    private void submitInput() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) {
            listen();
            return;
        }
        input.setText("");
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
        runCommand(q);
    }

    private boolean isIt() {
        String lang = MainActivity.speechLanguage(this);
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("it");
    }

    private int px(float v) {
        return Math.round(v * s);
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String v, float sizePx, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(v);
        tv.setTypeface(bold ? tfBold : tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * s);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private void addBtn(LinearLayout row, TextView btn, int leftMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(leftMarginPx);
        row.addView(btn, lp);
    }

    private TextView bracketBtn(String label, Runnable action) {
        TextView b = text("[ " + label + " ]", 17, true);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(px(10), px(6), px(10), px(6));
        b.setBackground(new BracketBg());
        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, has) -> {
            Theme t = Theme.cur();
            b.setTextColor(has ? t.accent : t.fgBright);
            v.invalidate();
        });
        bracketButtons.add(b);
        return b;
    }

    private final class BracketBg extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint();

        @Override
        public void draw(@NonNull Canvas c) {
            int[] st = getState();
            boolean focused = false;
            for (int x : st) if (x == android.R.attr.state_focused) focused = true;
            Theme t = Theme.cur();
            Rect b = getBounds();
            p.setColor(focused ? Theme.withAlpha(t.selection, 0.90f) : Theme.withAlpha(t.bgLight, 0.55f));
            c.drawRect(b, p);
            if (focused) {
                p.setColor(t.accent);
                c.drawRect(b.left + px(2), b.bottom - px(3), b.right - px(2), b.bottom, p);
            }
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private void applyThemeStyle() {
        LauncherConfig cfg = LauncherConfig.get(this);
        Theme t = Theme.cur();
        float op = HomeV29.opacity(cfg), nb = HomeV29.noBlur();
        int winBg = Theme.withAlpha(t.bg, Math.min(0.98f, op + nb + 0.06f));
        win.setColors(winBg, t.muted, t.accent, t.cyan);
        win.setLegend("grok bot · omarchy tui  [" + t.name + "]", px(18), px(24));

        SpannableStringBuilder p1 = buildPromptSpan(t);
        headerPrompt.setText(p1, TextView.BufferType.SPANNABLE);
        bottomPrompt.setText(buildPromptSpan(t), TextView.BufferType.SPANNABLE);

        String pcStat = OmarchyLink.connected()
                ? ("pc: " + providerName(cfg))
                : (isIt() ? "tv locale + mcp :47800" : "local tv + mcp :47800");
        statusBadge.setText("● " + pcStat + "  ·  theme: " + t.name);
        statusBadge.setTextColor(t.cyan);

        topDivider.setBackgroundColor(t.muted);
        bottomDivider.setBackgroundColor(t.muted);
        log.setTextColor(t.fg);
        input.setTextColor(t.fgBright);
        input.setHintTextColor(t.fgDim);

        String ip = localIp();
        hint.setTextColor(t.fgDim);
        hint.setText((isIt()
                ? "OK: Voce/Esegui  ·  ▲▼◀▶: Azioni rapide e terminale  ·  MENU: Web e AI  ·  MCP: http://"
                : "OK: Voice/Run  ·  ▲▼◀▶: Quick Actions & Prompt  ·  MENU: Web & AI  ·  MCP: http://")
                + ip + ":47800/mcp");

        for (TextView b : bracketButtons) {
            b.setTextColor(b.isFocused() ? t.accent : t.fgBright);
            b.invalidate();
        }
        if (wallpaperView != null) {
            wallpaperView.refreshIfChanged();
            wallpaperView.invalidate();
        }
    }

    private SpannableStringBuilder buildPromptSpan(Theme t) {
        SpannableStringBuilder p = new SpannableStringBuilder();
        span(p, "grokbot@omarchytv", t.green);
        span(p, ":", t.fg);
        span(p, "~", t.accent);
        span(p, "$ ", t.fg);
        return p;
    }

    private static void span(SpannableStringBuilder b, String s, int color) {
        int start = b.length();
        b.append(s);
        b.setSpan(new ForegroundColorSpan(color), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void printWelcomeBanner() {
        Theme t = Theme.cur();
        LauncherConfig cfg = LauncherConfig.get(this);
        String[] walls = WallpaperView.backgrounds(this, t.name);
        String ip = localIp();
        transcript.clear();

        String b1 = "╭─ GROK BOT · OMARCHY TV v2.12.0-beta ─────────────────────────────────────────╮\n";
        String b2 = String.format(Locale.ROOT,
                "│ ● Theme : %-18s  ● Wallpapers : %-2d (4K)  ● Saver : %-12s │\n",
                t.name, walls.length, "37 TTE (" + cfg.screensaverSpeed + ")");
        String b3 = String.format(Locale.ROOT,
                "│ ● TV MCP: http://%-15s:47800/mcp       ● PC Link: %-15s │\n",
                ip, OmarchyLink.connected() ? "connected" : "standalone");
        String b4 = "╰──────────────────────────────────────────────────────────────────────────────╯";

        span(transcript, b1, t.accent);
        span(transcript, b2, t.cyan);
        span(transcript, b3, t.green);
        span(transcript, b4, t.accent);
        log.setText(transcript);

        if (isIt()) {
            line("grok-bot",
                    "Pronto in modalità Omarchy TUI nativa.\n"
                    + "  • Comandi TV immediati (anche senza PC): \"tema jade-horse\", \"tema hermarchy\", \"sfondo\", \"screensaver\", \"apri YouTube\", \"stato\", \"temi\"\n"
                    + "  • Domande AI via PC (Omarchy Link): chiedi qualsiasi cosa a voce o da tastiera\n"
                    + "  • WebApp completa: premi [ 🌐 grok.com ] o [ 🌐 web ▾ ] per aprire Grok Web, ChatGPT, Claude o Hermes.",
                    t.cyan);
        } else {
            line("grok-bot",
                    "Ready in native Omarchy TUI mode.\n"
                    + "  • Instant TV commands (works standalone): \"theme jade-horse\", \"theme hermarchy\", \"wallpaper\", \"screensaver\", \"open YouTube\", \"status\", \"themes\"\n"
                    + "  • PC AI Bridge (Omarchy Link): ask any question by voice or keyboard\n"
                    + "  • Full WebApp mode: select [ 🌐 grok.com ] or [ 🌐 web ▾ ] to open Grok Web, ChatGPT, Claude, or Hermes.",
                    t.cyan);
        }
    }

    // ---- Local TV & Natural Language Command Engine + PC OmarchyLink Fallback ----

    private void runCommand(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;
        Theme t = Theme.cur();
        line(isIt() ? "tu" : "you", text, t.green);

        String low = text.toLowerCase(Locale.ROOT).trim();

        // 1. Clear / Reset
        if (low.equals("clear") || low.equals("cls") || low.equals("pulisci")) {
            printWelcomeBanner();
            return;
        }

        // 2. Help / Commands
        if (low.equals("help") || low.equals("aiuto") || low.equals("?") || low.equals("comandi") || low.equals("commands")) {
            String msg = isIt()
                    ? "Comandi Omarchy TUI disponibili:\n"
                      + "  • tema <nome> / next theme   → cambia uno dei 32 temi MIT (es. tema jade-horse, tema year-of-the-horse, tema hermarchy)\n"
                      + "  • temi / themes              → elenca tutti i 26 temi integrati\n"
                      + "  • sfondo / next wallpaper    → passa allo sfondo 4K successivo del tema attivo\n"
                      + "  • screensaver [effetto]      → avvia lo screensaver ASCII (es. screensaver matrix, speed fast)\n"
                      + "  • apri <app> / open <app>    → avvia un'app TV installata o la Live TV (es. apri YouTube, apri TV)\n"
                      + "  • apps                       → mostra le app TV installate\n"
                      + "  • stato / status / mcp       → mostra stato TV, rete, tema e server MCP (:47800/mcp)\n"
                      + "  • web [grok|chatgpt|claude]  → apre l'interfaccia Web completa nel processo :web"
                    : "Available Omarchy TUI commands:\n"
                      + "  • theme <name> / next theme  → switch any of the 32 MIT themes (e.g. theme jade-horse, theme year-of-the-horse, theme hermarchy)\n"
                      + "  • themes                     → list all 26 bundled MIT themes\n"
                      + "  • wallpaper / next wallpaper → cycle 4K wallpapers for the active theme\n"
                      + "  • screensaver [effect]       → start TTE ASCII screensaver (e.g. screensaver matrix, speed fast)\n"
                      + "  • open <app>                 → launch an installed TV app or Live TV (e.g. open YouTube, open TV)\n"
                      + "  • apps                       → list installed TV apps\n"
                      + "  • status / mcp               → show TV hardware, network, theme, and MCP server (:47800/mcp)\n"
                      + "  • web [grok|chatgpt|claude]  → open the full browser WebApp in :web";
            line("grok-bot", msg, t.cyan);
            speakIfEnabled(msg);
            return;
        }

        // 3. List themes
        if (low.equals("themes") || low.equals("temi") || low.equals("list themes") || low.equals("elenco temi")) {
            String[] names = Theme.names(this);
            StringBuilder sb = new StringBuilder();
            sb.append(isIt() ? "26 temi MIT integrati (attivo: " : "26 bundled MIT themes (active: ").append(t.name).append("):\n  ");
            for (int i = 0; i < names.length; i++) {
                if (i > 0) sb.append(i % 5 == 0 ? "\n  " : "  ·  ");
                sb.append(names[i].equals(t.name) ? "★ " + names[i] : names[i]);
            }
            line("grok-bot", sb.toString(), t.cyan);
            return;
        }

        // 4. Next theme
        if (low.equals("next theme") || low.equals("tema successivo") || low.equals("cambia tema") || low.equals("switch theme")) {
            String next = nextThemeName(this);
            if (next != null) applyLiveTheme(next);
            return;
        }

        // 5. Switch to a named theme (e.g. "theme jade-horse", "set theme nord", "tema jade horse", or direct theme name)
        String matchedTheme = matchThemeCommand(low);
        if (matchedTheme != null) {
            applyLiveTheme(matchedTheme);
            return;
        }

        // 6. Wallpaper cycling
        if (low.equals("wallpaper") || low.equals("next wallpaper") || low.equals("sfondo")
                || low.equals("cambia sfondo") || low.equals("sfondo successivo")) {
            String[] walls = WallpaperView.backgrounds(this, t.name);
            int count = Math.max(1, walls.length);
            LauncherConfig cfg = LauncherConfig.get(this);
            final int nextIdx = (cfg.wallpaperBg + 1) % count;
            LauncherConfig.update(this, j -> {
                j.put("wallpaper", "omarchy");
                j.put("wallpaperIndex", nextIdx);
            });
            applyThemeStyle();
            String wallName = walls.length > 0 ? walls[nextIdx % walls.length] : "omarchy";
            String msg = (isIt() ? "✓ Sfondo 4K impostato su #" : "✓ 4K Wallpaper switched to #")
                    + (nextIdx + 1) + "/" + count + " (" + wallName + ")";
            line("grok-bot", msg, Theme.cur().cyan);
            return;
        }

        // 7. Screensaver speed
        if (low.startsWith("speed ") || low.startsWith("velocita ") || low.startsWith("velocità ")) {
            String sp = low.contains("slow") || low.contains("lent") ? "slow"
                    : (low.contains("fast") || low.contains("veloc") ? "fast" : "normal");
            LauncherConfig.update(this, j -> {
                JSONObject ss = j.optJSONObject("screensaver");
                if (ss == null) j.put("screensaver", ss = new JSONObject());
                ss.put("speed", sp);
            });
            applyThemeStyle();
            line("grok-bot", (isIt() ? "✓ Velocità screensaver impostata su: " : "✓ Screensaver speed set to: ") + sp, Theme.cur().cyan);
            return;
        }

        // 8. Screensaver trigger / effect change
        if (low.equals("screensaver") || low.equals("salvaschermo") || low.equals("saver")
                || low.startsWith("screensaver ") || low.startsWith("salvaschermo ")) {
            String arg = low.replaceFirst("^(screensaver|salvaschermo|saver)\\s*", "").trim();
            if (arg.equals("slow") || arg.equals("normal") || arg.equals("fast")) {
                final String chosenSpd = arg;
                LauncherConfig.update(this, j -> {
                    JSONObject ss = j.optJSONObject("screensaver");
                    if (ss == null) j.put("screensaver", ss = new JSONObject());
                    ss.put("speed", chosenSpd);
                });
            }
            applyThemeStyle();
            LauncherConfig cfg = LauncherConfig.get(this);
            line("grok-bot", (isIt() ? "✓ Avvio screensaver ASCII (37 effetti TTE, velocità " : "✓ Starting ASCII screensaver (37 TTE effects, speed ")
                    + cfg.screensaverSpeed + ")…", Theme.cur().cyan);
            startActivity(new Intent(this, ScreensaverPreviewActivity.class));
            return;
        }

        // 9. Status / MCP / Fastfetch
        if (low.equals("status") || low.equals("stato") || low.equals("info") || low.equals("mcp")
                || low.equals("fastfetch") || low.equals("neofetch") || low.equals("ip")) {
            LauncherConfig cfg = LauncherConfig.get(this);
            String[] walls = WallpaperView.backgrounds(this, t.name);
            String ip = localIp();
            String msg = String.format(Locale.ROOT,
                    "OmarchyTV v2.12.0-beta  ·  %s %s (Android %s, SDK %d)\n"
                    + "  • Theme       : %s (%d bundled 4K wallpapers, index #%d)\n"
                    + "  • Screensaver : %s · speed=%s · idle=%ds\n"
                    + "  • TV MCP HTTP : http://%s:47800/mcp (JSON-RPC 2.0, 9 tools)\n"
                    + "  • PC Link     : %s (provider: %s)",
                    Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                    t.name, walls.length, cfg.wallpaperBg + 1,
                    "37 TTE effects", cfg.screensaverSpeed, cfg.screensaverIdleSec,
                    ip, OmarchyLink.connected() ? "connected" : (linked() ? "paired (offline)" : "standalone TV mode"),
                    providerName(cfg));
            line("grok-bot", msg, t.cyan);
            return;
        }

        // 10. List installed TV apps
        if (low.equals("apps") || low.equals("elenco app") || low.equals("list apps")) {
            List<ResolveInfo> apps = queryTvApps();
            PackageManager pm = getPackageManager();
            StringBuilder sb = new StringBuilder();
            sb.append(isIt() ? "App TV installate (" : "Installed TV apps (").append(apps.size()).append("):\n  ");
            int shown = 0;
            for (ResolveInfo ri : apps) {
                if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
                if (shown > 0) sb.append(shown % 4 == 0 ? "\n  " : "  ·  ");
                sb.append(ri.loadLabel(pm));
                shown++;
            }
            line("grok-bot", sb.toString(), t.cyan);
            return;
        }

        // 11. Open WebApp explicitly ("web", "grok.com", "grok web", "chatgpt", "claude", "hermes")
        if (low.equals("web") || low.equals("grok.com") || low.equals("grok web") || low.equals("webapp")) {
            openWebApp("grok-bot");
            return;
        }
        if (low.equals("chatgpt") || low.equals("open chatgpt") || low.equals("apri chatgpt")) {
            openWebApp("chatgpt");
            return;
        }
        if (low.equals("claude") || low.equals("open claude") || low.equals("apri claude")) {
            openWebApp("claude");
            return;
        }
        if (low.equals("hermes") || low.equals("open hermes") || low.equals("apri hermes")) {
            openWebApp("hermes");
            return;
        }

        // 12. Open / Launch an installed TV app ("open youtube", "apri netflix", "open tv", "apri tv")
        if (low.startsWith("open ") || low.startsWith("launch ") || low.startsWith("apri ") || low.startsWith("avvia ")) {
            String target = low.replaceFirst("^(open|launch|apri|avvia)\\s+", "").trim();
            if (target.equals("tv") || target.equals("live tv") || target.equals("canali")) {
                String tvPkg = MainActivity.liveTvPackage(this);
                if (!tvPkg.isEmpty()) {
                    Intent li = getPackageManager().getLeanbackLaunchIntentForPackage(tvPkg);
                    if (li == null) li = getPackageManager().getLaunchIntentForPackage(tvPkg);
                    if (li != null) {
                        line("grok-bot", (isIt() ? "✓ Avvio Live TV (" : "✓ Launching Live TV (") + tvPkg + ")…", t.cyan);
                        startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    }
                }
            }
            if (tryLaunchAppByName(target)) return;
        }

        // 13. Forward general AI prompt to paired PC via OmarchyLink (or explain standalone mode)
        askPcBridge(text);
    }

    private String matchThemeCommand(String low) {
        String candidate = low;
        if (low.startsWith("set theme ")) candidate = low.substring("set theme ".length()).trim();
        else if (low.startsWith("theme ")) candidate = low.substring("theme ".length()).trim();
        else if (low.startsWith("imposta tema ")) candidate = low.substring("imposta tema ".length()).trim();
        else if (low.startsWith("tema ")) candidate = low.substring("tema ".length()).trim();
        else if (low.startsWith("usa tema ")) candidate = low.substring("usa tema ".length()).trim();

        String norm = candidate.replace(' ', '-').replace('_', '-');
        for (String name : Theme.names(this)) {
            if (name.equalsIgnoreCase(norm) || name.equalsIgnoreCase(candidate)) {
                return name;
            }
        }
        // Also support partial match when user explicitly prefixed with "theme " or "tema "
        if (!candidate.equals(low) && candidate.length() >= 3) {
            for (String name : Theme.names(this)) {
                if (name.contains(norm) || name.replace("-", " ").contains(candidate)) {
                    return name;
                }
            }
        }
        return null;
    }

    /** The bundled theme after the current one (wrapping), or null if there are none. */
    static String nextThemeName(Context c) {
        String[] names = Theme.names(c);
        if (names.length == 0) return null;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(Theme.cur().name)) return names[(i + 1) % names.length];
        }
        return names[0];
    }

    /** Config edit that switches to a bundled theme (shared with the "next-theme" key action). */
    static LauncherConfig.Edit themeEdit(String themeName) {
        return j -> {
            JSONObject th = j.optJSONObject("theme");
            if (th == null) j.put("theme", th = new JSONObject());
            th.put("name", themeName);
            th.remove("colors");
            th.remove("accent");
            th.remove("accent2");
            th.remove("mode");
            j.put("wallpaper", "omarchy");
            j.put("wallpaperIndex", 0);
        };
    }

    private void applyLiveTheme(String themeName) {
        LauncherConfig.update(this, themeEdit(themeName));
        LauncherConfig.get(this);
        applyThemeStyle();
        String[] walls = WallpaperView.backgrounds(this, themeName);
        String msg = isIt()
                ? "✓ Tema impostato su " + themeName + " (" + walls.length + " sfondi 4K MIT integrati)."
                : "✓ Theme switched to " + themeName + " (" + walls.length + " bundled 4K MIT wallpapers).";
        line("grok-bot", msg, Theme.cur().cyan);
        speakIfEnabled(msg);
    }

    private List<ResolveInfo> queryTvApps() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        return found != null ? found : Collections.emptyList();
    }

    private boolean tryLaunchAppByName(String query) {
        PackageManager pm = getPackageManager();
        for (ResolveInfo ri : queryTvApps()) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            String label = String.valueOf(ri.loadLabel(pm));
            if (label.toLowerCase(Locale.ROOT).contains(query) || pkg.toLowerCase(Locale.ROOT).contains(query)) {
                Intent i = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    line("grok-bot", (isIt() ? "✓ Avvio " : "✓ Launching ") + label + " (" + pkg + ")…", Theme.cur().cyan);
                    startActivity(i);
                    return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    private void openWebApp(String id) {
        LauncherConfig.WebApp target = LauncherConfig.BUILTIN_AI_WEBAPPS[0];
        for (LauncherConfig.WebApp w : LauncherConfig.BUILTIN_AI_WEBAPPS) {
            if (w.id.equals(id)) {
                target = w;
                break;
            }
        }
        line("grok-bot", (isIt() ? "✓ Apertura WebApp " : "✓ Opening WebApp ") + target.name + " (" + target.url + ")…", Theme.cur().cyan);
        WebAppActivity.open(this, target);
    }

    private void showThemesPopup(View anchor) {
        if (popupMenu != null && popupMenu.isShowing()) popupMenu.dismiss();
        popupMenu = new AsciiMenu(this, 420);
        AsciiMenu.Page p = new AsciiMenu.Page(isIt() ? "temi omarchy (26)" : "omarchy themes (26)");
        String cur = Theme.cur().name;
        for (String name : Theme.names(this)) {
            final String tn = name;
            p.add(tn, tn.equals(cur) ? "●" : "", r -> {
                applyLiveTheme(tn);
                return false;
            });
        }
        popupMenu.showBelow(anchor, p);
    }

    private void showWebPopup(View anchor) {
        if (popupMenu != null && popupMenu.isShowing()) popupMenu.dismiss();
        popupMenu = new AsciiMenu(this, 440);
        AsciiMenu.Page p = new AsciiMenu.Page("grok bot · web & ai");
        for (LauncherConfig.WebApp w : LauncherConfig.BUILTIN_AI_WEBAPPS) {
            final LauncherConfig.WebApp fw = w;
            String host = android.net.Uri.parse(w.url).getHost();
            p.add(w.name + " (Web)", (host != null ? host : w.url) + " ›", r -> {
                WebAppActivity.open(this, fw);
                return false;
            });
        }
        p.add("> agents (PC)", OmarchyLink.connected() ? "connected ›" : "›", r -> {
            startActivity(new Intent(this, AgentActivity.class));
            return false;
        });
        popupMenu.showBelow(anchor, p);
    }

    private void askPcBridge(String text) {
        Theme t = Theme.cur();
        if (!linked() || !OmarchyLink.connected()) {
            String msg = isIt()
                    ? "Modalità TV standalone attiva (nessun PC Omarchy collegato in questo momento).\n"
                      + "  • Puoi controllare subito la TV con comandi come: \"tema jade-horse\", \"sfondo\", \"screensaver\", \"apri YouTube\", \"stato\", \"temi\".\n"
                      + "  • Per chat AI libera sul TV: premi [ 🌐 grok.com ] in alto per aprire Grok Web, oppure collega il PC con `omarchy-tv pair`."
                    : "Standalone TV mode active (no paired Omarchy PC currently connected).\n"
                      + "  • Control the TV right now with commands like: \"theme jade-horse\", \"wallpaper\", \"screensaver\", \"open YouTube\", \"status\", \"themes\".\n"
                      + "  • For open-ended AI chat on TV: select [ 🌐 grok.com ] above to open Grok Web, or pair your PC with `omarchy-tv pair`.";
            line("grok-bot", msg, t.yellow);
            return;
        }
        if (pendingId != null) {
            line("sys", getString(R.string.ask_busy), t.yellow);
            return;
        }
        LauncherConfig cfg = LauncherConfig.get(this);
        String id = Long.toHexString(new java.security.SecureRandom().nextLong());
        JSONObject d;
        try {
            d = new JSONObject()
                    .put("id", id)
                    .put("text", text.length() > 2000 ? text.substring(0, 2000) : text)
                    .put("lang", MainActivity.speechLanguage(this));
            String prov = cfg.assistantProvider;
            if (!prov.isEmpty()) {
                boolean known = false;
                for (LauncherConfig.Provider p : cfg.aiProviders) known |= p.id.equals(prov);
                if (known) d.put("provider", prov);
            }
            if (session != null) d.put("session", session);
        } catch (org.json.JSONException e) {
            line("sys", getString(R.string.ask_not_connected), t.yellow);
            return;
        }
        // Pending before the send, so a second question can't slip in while the worker writes this one.
        pendingId = id;
        replyStart = -1;
        log.postDelayed(timeout, ANSWER_TIMEOUT_MS);
        OmarchyLink.ask(d, err -> {
            if (err == null || isDestroyed() || !id.equals(pendingId)) return;
            pendingId = null;
            log.removeCallbacks(timeout);
            line("sys", getString("busy".equals(err) ? R.string.ask_busy : R.string.ask_not_connected), t.yellow);
        });
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

    @Override
    public void onLinkEvent(String type, JSONObject d) {
        if ("clients".equals(type)) {
            applyThemeStyle();
            return;
        }
        if (pendingId == null || !pendingId.equals(d.optString("id"))) return;
        if ("answer_error".equals(type)) {
            pendingId = null;
            log.removeCallbacks(timeout);
            String msg = d.optString("message", "");
            line("sys", msg.isEmpty() ? d.optString("error", "error") : msg, Theme.cur().red);
            return;
        }
        if (!"answer".equals(type)) return;
        if (replyStart < 0) startReply();
        enqueue(d.optString("text", ""));
        if (d.optBoolean("done")) {
            pendingId = null;
            log.removeCallbacks(timeout);
            if (d.has("session") && !d.isNull("session")) session = d.optString("session");
            finalText = d.isNull("final") ? null : d.optString("final", null);
            if (!typing) endReply();
        } else {
            log.removeCallbacks(timeout);
            log.postDelayed(timeout, ANSWER_TIMEOUT_MS);
        }
    }

    private void startReply() {
        LauncherConfig cfg = LauncherConfig.get(this);
        if (transcript.length() > 0) transcript.append("\n\n");
        int s0 = transcript.length();
        transcript.append(providerName(cfg).toLowerCase(Locale.ROOT)).append("> ");
        transcript.setSpan(new ForegroundColorSpan(Theme.cur().cyan), s0, transcript.length(), 0);
        replyStart = transcript.length();
        log.setText(transcript);
    }

    private void enqueue(String text) {
        if (text.isEmpty()) return;
        queue.append(text);
        if (!typing) {
            typing = true;
            log.post(typer);
        }
    }

    private final Runnable typer = new Runnable() {
        @Override
        public void run() {
            int n = Math.min(queue.length(), 4);
            transcript.append(queue, 0, n);
            queue.delete(0, n);
            log.setText(transcript);
            scroll.fullScroll(View.FOCUS_DOWN);
            if (queue.length() > 0) log.postDelayed(this, 16);
            else {
                typing = false;
                if (pendingId == null && replyStart >= 0) endReply();
            }
        }
    };

    private void endReply() {
        String shown = transcript.subSequence(replyStart, transcript.length()).toString();
        String text = finalText != null && !finalText.isEmpty() ? finalText : shown;
        if (!text.equals(shown)) {
            transcript.replace(replyStart, transcript.length(), text);
            log.setText(transcript);
        }
        replyStart = -1;
        finalText = null;
        speakIfEnabled(text);
    }

    private void speakIfEnabled(String text) {
        if (ttsReady && text != null && !text.isEmpty()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "otv-ask");
        }
    }

    static String providerName(LauncherConfig cfg) {
        for (LauncherConfig.Provider p : cfg.aiProviders) if (p.id.equals(cfg.assistantProvider)) return p.name;
        return cfg.aiProviders.isEmpty() ? "grok-bot" : cfg.aiProviders.get(0).name;
    }

    private boolean linked() {
        JSONObject link = LauncherConfig.readJson(this).optJSONObject("link");
        JSONArray pcs = link != null ? link.optJSONArray("pcs") : null;
        return pcs != null && pcs.length() > 0;
    }

    private void line(String who, String text, int color) {
        if (transcript.length() > 0) transcript.append("\n\n");
        int s0 = transcript.length();
        transcript.append(who).append("> ");
        transcript.setSpan(new ForegroundColorSpan(color), s0, transcript.length(), 0);
        int s1 = transcript.length();
        transcript.append(text);
        transcript.setSpan(new ForegroundColorSpan(Theme.cur().fgBright), s1, transcript.length(), 0);
        log.setText(transcript);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, MainActivity.speechLanguage(this))
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ask_prompt));
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK || data == null) return;
        List<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r != null && !r.isEmpty()) runCommand(r.get(0));
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showWebPopup(win);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            listen();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private static String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
