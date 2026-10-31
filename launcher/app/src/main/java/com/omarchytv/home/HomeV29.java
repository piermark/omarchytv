package com.omarchytv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v2.9 home (spec "Omarchy TV", §3): Waybar top bar, fastfetch mini terminal, btop-style widgets, empty centre
 * for the wallpaper, bottom app window with 16:9 banner tiles. All sizes are the spec's px at 1080p scaled by
 * screen height / 1080 (same proportions at 4K). Every colour comes from the colors.toml keys in {@link Theme}.
 */
final class HomeV29 extends FrameLayout {

    interface Host {
        void openSettings(View anchor);
        void showWorkspace(int ws);
        void showInputs(View anchor);
        void screenPc(View anchor);
        void openWalker();
        void weatherMenu(View anchor);
        void aiMenu(View anchor);
        void launchApp(int position);
        String wifiName();
        // v2.10
        void wifiClick();
        void openBluetooth();
        void openTv();
        void tileMenu(int appPos, View anchor);
        void moveApp(int from, int to);
        void finishMove(boolean save);
        List<Source> sources();
        boolean pcPaired();
        void pairPc();
        void sys(String id);
        void savePanelPos(String id, int cx, int cy);
        /** ▶ past the last top-bar icon: select (arm) the floating companion; false when it is not shown. */
        boolean selectCompanion();
    }

    /** v2.10 workspace 2 tile: TV or an HDMI input (CEC name when known). */
    static final class Source {
        final String label, sub;
        final int icon;
        final Runnable open;

        Source(String label, String sub, int icon, Runnable open) {
            this.label = label;
            this.sub = sub;
            this.icon = icon;
            this.open = open;
        }
    }

    private final Activity act;
    private final Host host;
    final float s;
    private final Typeface tf, tfBold;
    private Theme t;
    private LauncherConfig cfg;

    // bar
    private final FrameLayout bar;
    private final TextView clock;
    private final TextView[] ws = new TextView[5];
    private final View menuBtn;
    private final List<View> barIcons = new ArrayList<>();
    private final TextView tip;
    private int activeWs = 1;
    // terminal
    private final BoxView term;
    private final TextView termInfo, termLogo, termPrompt;
    // widgets
    private final LinearLayout widgets;
    private final BoxView wxBox, pcBox, depBox;
    private final LinearLayout pcBody, aiBody, depBody;
    /** Up to 3 panes the TV fetches itself (RemotePanes), in their own row under the widgets. */
    private final LinearLayout remoteRow;
    private final BoxView[] remoteBox = new BoxView[3];
    private final LinearLayout[] remoteBody = new LinearLayout[3];
    /** "barWeather": icon + temperature in the bar, left of the status icons. */
    private final TextView barSpacex;
    private final BoxView grokbotBox;
    private final TextView grokbotEyes, grokbotText;
    private final GrokbotMark grokbotMark;
    private final LinearLayout barWx;
    private final ImageView barWxIcon;
    private final TextView barWxTemp;
    private final TextView wxTemp, wxLine1, wxLine2;
    // apps
    private final BoxView appWin;
    private final TextView appName, appHints;
    final RecyclerView row;
    private final TileAdapter rowAdapter;
    private AppAdapter apps;
    /** Adapter position in the home row (0 = TV tile). */
    int lastApp;
    // v2.10 workspaces 2-5
    private final BoxView tvWin, appsWin, pcWin, sysWin;
    private final LinearLayout tvBody, pcPaneBody, sysBody;
    final RecyclerView grid;
    private final TileAdapter gridAdapter;
    private final TextView gridName, gridHints;
    private final android.util.SparseArray<View> wsFocus = new android.util.SparseArray<>();
    /** Move mode (OK lungo → Sposta): app index being moved, or -1; which list (row / grid). */
    int movingApp = -1;
    private boolean movingInGrid;
    private SpannableStringBuilder promptText;
    private ForegroundColorSpan cursorSpan;
    private String logoFinal = "";
    private final android.os.Handler anim = new android.os.Handler(android.os.Looper.getMainLooper());

    HomeV29(Activity a, Host host) {
        super(a);
        this.act = a;
        this.host = host;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        s = Math.min(dm.heightPixels / 1080f, dm.widthPixels / 1920f);
        tf = CastUi.font(a);
        tfBold = Typeface.create(tf, Typeface.BOLD);
        setClipChildren(false);

        // ---- 3.1 bar: 52 px, padding 32, three equal columns ----
        bar = new FrameLayout(a);
        addView(bar, new LayoutParams(-1, px(52)));
        LinearLayout left = hbox();
        left.setPadding(px(32), 0, 0, 0);
        bar.addView(left, new LayoutParams(-2, -1, Gravity.START));
        menuBtn = iconButton(R.drawable.ln_menu, 44, 24);
        left.addView(menuBtn);
        menuBtn.setOnClickListener(v -> host.openSettings(v));
        for (int i = 0; i < 5; i++) {
            final int n = i + 1;
            TextView w = text(String.valueOf(n), 20, false);
            w.setGravity(Gravity.CENTER);
            w.setFocusable(true);
            w.setPadding(px(10), 0, px(10), 0);
            w.setOnClickListener(v -> host.showWorkspace(cfg != null ? cfg.workspaceAt(n) : n));   // n = bar position
            w.setBackground(new Underline());
            ws[i] = w;
            left.addView(w, new LinearLayout.LayoutParams(-2, -1));
        }
        clock = text("", 22, false);
        bar.addView(clock, new LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout right = hbox();
        right.setPadding(0, 0, px(32) - px(13), 0);
        bar.addView(right, new LayoutParams(-2, -1, Gravity.END));
        barSpacex = text("🚀 SpaceX TV", 18, true);
        barSpacex.setFocusable(true);
        barSpacex.setClickable(true);
        barSpacex.setGravity(Gravity.CENTER);   // same row as the icons: centred in a 44 px box like iconButton
        barSpacex.setSingleLine(true);
        barSpacex.setPadding(px(12), 0, px(12), 0);
        barSpacex.setBackground(new Underline());
        barSpacex.setOnClickListener(v -> host.sys("spacex-tv"));
        LinearLayout.LayoutParams sxl = new LinearLayout.LayoutParams(-2, px(44));
        sxl.gravity = Gravity.CENTER_VERTICAL;
        sxl.rightMargin = px(16);
        right.addView(barSpacex, sxl);
        barWx = hbox();
        barWxIcon = new ImageView(a);
        barWx.addView(barWxIcon, new LinearLayout.LayoutParams(px(24), px(24)));
        barWxTemp = text("", 22, false);
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(-2, -2);
        btl.leftMargin = px(8);
        barWx.addView(barWxTemp, btl);
        LinearLayout.LayoutParams bwl = new LinearLayout.LayoutParams(-2, -1);
        bwl.rightMargin = px(20);
        right.addView(barWx, bwl);
        barWx.setVisibility(GONE);
        // v2.10: no gear (the menu is the Omarchy logo on the left).
        int[] icons = {R.drawable.ln_cinemarchy, R.drawable.ln_wifi, R.drawable.ln_bt, R.drawable.ln_vol, R.drawable.ln_input, R.drawable.ln_monitor};
        for (int ic : icons) {
            View b = iconButton(ic, 24 + 26, 24);
            right.addView(b);
            barIcons.add(b);
        }
        barIcons.get(0).setOnClickListener(v -> host.sys("cinemarchy"));
        barIcons.get(1).setOnClickListener(v -> host.wifiClick());
        barIcons.get(2).setOnClickListener(v -> host.openBluetooth());
        barIcons.get(3).setOnClickListener(v -> {
            android.media.AudioManager am = act.getSystemService(android.media.AudioManager.class);
            if (am != null) am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI);
        });
        barIcons.get(4).setOnClickListener(host::showInputs);
        barIcons.get(5).setOnClickListener(host::screenPc);
        tip = text("", 19, false);
        tip.setVisibility(GONE);
        tip.setPadding(px(14), px(8), px(14), px(8));
        tip.setElevation(20f);
        addView(tip, new LayoutParams(-2, -2));
        for (int i = 0; i < barIcons.size(); i++) {
            final int k = i;
            barIcons.get(i).setOnFocusChangeListener((v, f) -> showTip(f ? v : null, k));
        }

        // ---- 3.2 fastfetch: x 32, y 76, w 640, padding 18×22 ----
        term = new BoxView(a, s, tf);
        LayoutParams tl = new LayoutParams(px(640), -2);
        tl.leftMargin = px(32);
        tl.topMargin = px(76);
        addView(term, tl);
        termPrompt = text("", 20, false);
        termLogo = text(logoText(), 8, false);
        termLogo.setLineSpacing(0, 1.15f);
        termLogo.setHorizontallyScrolling(true);
        termInfo = text("", 20, false);
        termInfo.setMaxLines(3);   // v2.10: one item per line (the single line was cut to "up" on the TV)
        termInfo.setLineSpacing(px(4), 1f);
        term.addView(termPrompt);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = px(12);
        ll.bottomMargin = px(12);
        term.addView(termLogo, ll);
        term.addView(termInfo);

        // ---- 3.3 widgets: right 32, y 76, w 1120, 3 equal columns, gap 16 ----
        widgets = new LinearLayout(a);
        widgets.setOrientation(LinearLayout.VERTICAL);
        LayoutParams wl = new LayoutParams(px(1120), -2, Gravity.END | Gravity.TOP);
        wl.rightMargin = px(32);
        wl.topMargin = px(76);
        addView(widgets, wl);
        LinearLayout r1 = hbox(), r2 = hbox();
        r1.setGravity(Gravity.TOP);
        // v2.10 "Disponi riquadri": panels are offset with translations, so their rows must not clip them.
        widgets.setClipChildren(false);
        r1.setClipChildren(false);
        r2.setClipChildren(false);
        setClipChildren(false);
        r1.setBaselineAligned(false);
        widgets.addView(r1, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams r2l = new LinearLayout.LayoutParams(-1, -2);
        r2l.topMargin = px(16);
        widgets.addView(r2, r2l);
        wxBox = widget(r1, act.getString(R.string.v29_weather), 0);
        pcBox = widget(r1, "omarchy · pc", 16);
        BoxView ai = widget(r1, "ai credits · pc", 16);
        r2.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 2f));
        View gap = new View(a);
        r2.addView(gap, new LinearLayout.LayoutParams(px(32), 1));
        depBox = widget(r2, "deploys · pc", 0);
        ((LinearLayout.LayoutParams) depBox.getLayoutParams()).weight = 1f;
        // 2 columns + 1 gap left of the third column: weights 2 : 1 with a 32 px spacer ≈ 3 equal columns, 16 gaps
        this.aiBoxRef = ai;
        wxTemp = text("", 50, false);
        wxLine1 = line("");
        wxLine2 = line("");
        wxBox.addView(wxTemp);
        wxBox.addView(wxLine1);
        wxBox.addView(wxLine2);
        pcBody = vbox();
        pcBox.addView(pcBody, new LinearLayout.LayoutParams(-1, -2));
        aiBody = vbox();
        ai.addView(aiBody, new LinearLayout.LayoutParams(-1, -2));
        depBody = vbox();
        depBox.addView(depBody, new LinearLayout.LayoutParams(-1, -2));
        remoteRow = hbox();
        remoteRow.setGravity(Gravity.TOP);
        remoteRow.setBaselineAligned(false);
        remoteRow.setClipChildren(false);
        LinearLayout.LayoutParams r3l = new LinearLayout.LayoutParams(-1, -2);
        r3l.topMargin = px(16);
        widgets.addView(remoteRow, r3l);
        for (int i = 0; i < remoteBox.length; i++) {
            remoteBox[i] = widget(remoteRow, "", i == 0 ? 0 : 16);
            remoteBox[i].setFocusable(false);   // read-only data: keep the D-pad path as it was
            remoteBody[i] = vbox();
            remoteBox[i].addView(remoteBody[i], new LinearLayout.LayoutParams(-1, -2));
        }
        remoteRow.setVisibility(GONE);
        wxBox.setOnClickListener(host::weatherMenu);
        ai.setOnClickListener(host::aiMenu);

        // ---- 3.4 app window: margins 32, padding 20×28 ----
        appWin = new BoxView(a, s, tf);
        LayoutParams al = new LayoutParams(-1, -2, Gravity.BOTTOM);
        al.leftMargin = al.rightMargin = al.bottomMargin = px(32);
        addView(appWin, al);
        appWin.setClipChildren(false);
        appWin.setClipToPadding(false);
        FrameLayout head = new FrameLayout(a);
        appName = text("", 30, true);
        appName.setSingleLine(true);
        appName.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(appName, new LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        appHints = text("", 18, false);
        head.addView(appHints, new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        appWin.addView(head, new LinearLayout.LayoutParams(-1, -2));
        row = new RecyclerView(a);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setOverScrollMode(OVER_SCROLL_NEVER);
        row.setItemAnimator(null);
        row.setLayoutManager(new LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false));
        rowAdapter = new TileAdapter(true, rowCols(0), row);   // bind() applies Layout → App size
        row.setAdapter(rowAdapter);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = px(16);
        appWin.addView(row, rl);

        // ---- v2.13 Grok Bot Desktop Companion (io.github.tcballard.grokbot-desktop, MIT) ----
        grokbotBox = new BoxView(a, s, tf);
        grokbotBox.setLegend("grokbot", px(10), px(14));
        grokbotBox.setFocusable(true);
        grokbotBox.setClickable(true);
        grokbotBox.focusBorder = true;
        LinearLayout gbRow = hbox();
        gbRow.setGravity(Gravity.CENTER_VERTICAL);
        grokbotMark = new GrokbotMark(a);
        grokbotMark.setVisibility(GONE);
        grokbotEyes = text("[ ◉ ◉ ]", 20, true);
        grokbotText = text(" companion · ask", 18, false);
        grokbotText.setSingleLine(true);
        grokbotText.setEllipsize(TextUtils.TruncateAt.END);
        grokbotText.setMaxWidth(px(620));
        gbRow.addView(grokbotMark, new LinearLayout.LayoutParams(px(34), px(34)));
        gbRow.addView(grokbotEyes);
        gbRow.addView(grokbotText);
        grokbotBox.addView(gbRow);
        grokbotBox.setOnClickListener(v -> host.sys("grokbot-companion"));
        LayoutParams gbl = new LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        gbl.rightMargin = px(36);
        gbl.bottomMargin = px(296);
        addView(grokbotBox, gbl);

        // ---- v2.10 workspaces 2-5: same v2.9 windows (square boxes, titles in the border) ----
        tvWin = window("tv");
        tvBody = vbox();
        tvWin.addView(tvBody, new LinearLayout.LayoutParams(-1, -2));
        appsWin = window("app");
        FrameLayout gh = new FrameLayout(a);
        gridName = text("", 30, true);
        gridName.setSingleLine(true);
        gh.addView(gridName, new LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        gridHints = text("", 18, false);
        gh.addView(gridHints, new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        appsWin.addView(gh, new LinearLayout.LayoutParams(-1, -2));
        grid = new RecyclerView(a);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(px(8), px(12), px(8), px(12));
        grid.setOverScrollMode(OVER_SCROLL_NEVER);
        grid.setItemAnimator(null);
        grid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(a, 7));
        gridAdapter = new TileAdapter(false, 7, grid);
        grid.setAdapter(gridAdapter);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, 0, 1f);
        gl.topMargin = px(8);
        appsWin.addView(grid, gl);
        ((LayoutParams) appsWin.getLayoutParams()).height = -1;
        pcWin = window("pc");
        pcPaneBody = vbox();
        pcWin.addView(pcPaneBody, new LinearLayout.LayoutParams(-1, -2));
        sysWin = window(act.getString(R.string.v29_system));
        sysBody = vbox();
        sysWin.addView(sysBody, new LinearLayout.LayoutParams(-1, -2));
    }

    /** A full-width v2.9 window under the bar for workspaces 2-5 (hidden until its workspace is shown). */
    private BoxView window(String title) {
        BoxView b = new BoxView(act, s, tf);
        b.setLegend(title, px(20), px(28));
        b.setClipChildren(false);
        b.setClipToPadding(false);
        LayoutParams l = new LayoutParams(-1, -2);
        l.leftMargin = l.rightMargin = l.bottomMargin = px(32);
        l.topMargin = px(76);
        b.setVisibility(GONE);
        addView(b, l);
        return b;
    }

    private BoxView aiBoxRef;

    // ---------------------------------------------------------------------------------------------------------

    int px(float v) {
        return Math.round(v * s);
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** Text scale for the large workspace-4 widgets (1 elsewhere). */
    private float big = 1f;

    private TextView text(String v, float sizePx, boolean bold) {
        TextView tv = new TextView(act);
        tv.setText(v);
        tv.setTypeface(bold ? tfBold : tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * s * big);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private TextView line(String v) {
        TextView tv = text(v, 19, false);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = px(6);
        tv.setLayoutParams(l);
        return tv;
    }

    private BoxView widget(LinearLayout parent, String title, int gapLeft) {
        BoxView b = new BoxView(act, s, tf);
        b.setLegend(title, px(14), px(18));
        b.setFocusable(true);
        b.focusBorder = true;
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
        l.leftMargin = px(gapLeft);
        parent.addView(b, l);
        return b;
    }

    private View iconButton(int res, int boxPx, int iconPx) {
        FrameLayout f = new FrameLayout(act);
        f.setFocusable(true);
        f.setClickable(true);
        ImageView iv = new ImageView(act);
        iv.setImageResource(res);
        f.addView(iv, new LayoutParams(px(iconPx), px(iconPx), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(px(boxPx), px(44)));
        f.setBackground(new Underline());
        return f;
    }

    /** Waybar focus/active marker: 3 px accent underline (no rounding). */
    private final class Underline extends Drawable {
        private final Paint p = new Paint();

        @Override
        public void draw(@NonNull android.graphics.Canvas c) {
            int[] st = getState();
            boolean on = false;
            for (int x : st) if (x == android.R.attr.state_focused) on = true;
            if (!on) return;
            p.setColor(t != null ? t.accent : Color.WHITE);
            android.graphics.Rect b = getBounds();
            c.drawRect(b.left + px(4), b.bottom - px(3), b.right - px(4), b.bottom, p);
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
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private void open(android.content.Intent i) {
        try {
            act.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    private String logoText() {
        try (InputStream in = act.getAssets().open("saver/screensaver-tv.txt")) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            String txt = new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8);
            return txt.replaceAll("\\s+$", "");
        } catch (Exception e) {
            return "OMARCHY TV";
        }
    }

    // ---- alpha rules (spec §2): windows = opacity, bar = +0.10 (max 0.95), tiles = −0.15 (min 0.30); no blur
    // below Android 12 → +0.10 everywhere. ----

    static float opacity(LauncherConfig c) {
        return 1f - Math.max(5, Math.min(60, c.transparency)) / 100f;
    }

    static float noBlur() {
        return Build.VERSION.SDK_INT < 31 ? 0.10f : 0f;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Re-reads config and theme and redraws everything (resume, theme push, settings change). */
    void bind(LauncherConfig c, AppAdapter apps) {
        cfg = c;
        t = Theme.cur();
        this.apps = apps;
        float op = opacity(c), nb = noBlur();
        int winBg = Theme.withAlpha(t.bg, Math.min(0.98f, op + nb));
        bar.setBackgroundColor(Theme.withAlpha(t.bg, Math.min(0.95f, op + 0.10f) + nb > 0.98f ? 0.98f : Math.min(0.95f, op + 0.10f) + nb));
        clock.setTextColor(t.fgBright);
        for (int i = 0; i < 5; i++) {
            boolean on = c.workspaceAt(i + 1) == activeWs;
            ws[i].setTextColor(on ? t.accent : t.fg);
            ws[i].setTypeface(on ? tfBold : tf);
            ws[i].setPaintFlags(on ? ws[i].getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG : ws[i].getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
            ws[i].setVisibility(c.workspaceAt(i + 1) > 0 ? VISIBLE : GONE);
            ws[i].setText(c.workspaceNamesInBar && c.workspaceAt(i + 1) > 0 ? c.workspaceName(c.workspaceAt(i + 1)) : String.valueOf(i + 1));
        }
        tint(menuBtn, t.fgBright);
        for (View b : barIcons) tint(b, t.fgBright);
        tip.setBackgroundColor(Theme.withAlpha(t.bg, 0.98f));
        tip.setTextColor(t.fgBright);
        for (BoxView b : new BoxView[]{term, wxBox, pcBox, aiBoxRef, depBox, appWin, tvWin, appsWin, pcWin, sysWin, grokbotBox}) b.setColors(winBg, t.muted, t.accent, t.cyan);
        barSpacex.setTextColor(t.accent2);
        barSpacex.setVisibility(c.pluginSpacexTv ? VISIBLE : GONE);
        grokbotEyes.setTextColor(t.accent);
        grokbotText.setTextColor(t.fgBright);
        boolean mirrored = c.grokbotBotName != null && !c.grokbotBotName.isEmpty();
        grokbotMark.setVisibility(VISIBLE);   // v2.13: Grok Bot's ball when nothing is mirrored
        grokbotEyes.setVisibility(GONE);
        grokbotMark.set(mirrored ? c.grokbotBotShape : "orb", mirrored && c.grokbotBotFill != 0 ? c.grokbotBotFill : GrokbotMark.GROKBOT_ORANGE);
        String gbMsg = c.grokbotMessage != null && !c.grokbotMessage.isEmpty() ? c.grokbotMessage : null;
        grokbotText.setText(mirrored ? "  " + c.grokbotBotName + (gbMsg != null ? ": " + gbMsg : " · ask")
                : gbMsg != null ? " " + gbMsg : " companion · ask");
        // The floating face is the one companion: this "companion · ask" tile only stands in when it is off.
        grokbotBox.setVisibility(activeWs == 1 && CompanionPlacement.homeTile(c.pluginGrokbot, c.companionOverlay) ? VISIBLE : GONE);
        for (BoxView b : remoteBox) b.setColors(winBg, t.muted, t.accent, t.cyan);
        // v2.10: every home widget can be switched off (Settings → Widgets); the clock lives only in the bar.
        wxBox.setVisibility(c.wWeather ? VISIBLE : GONE);
        pcBox.setVisibility(c.wPc ? VISIBLE : GONE);
        aiBoxRef.setVisibility(c.wAi ? VISIBLE : GONE);
        term.setVisibility(activeWs == 1 && c.wTerm ? VISIBLE : GONE);
        if (editPanel == null) post(() -> applyPanelPos(c));
        gridName.setTextColor(t.accent);
        gridHints.setTextColor(t.fg);
        gridHints.setText(gridHintsText());
        term.setLegend(null, px(18), px(22));
        appWin.setLegend(null, px(20), px(28));
        // fastfetch
        SpannableStringBuilder p = new SpannableStringBuilder();
        span(p, c.promptUser + "@" + c.promptHost, t.green);
        span(p, ":", t.fg);
        span(p, "~", t.accent);
        span(p, "$ ", t.fg);
        int cs = p.length();
        p.append("█");
        cursorSpan = new ForegroundColorSpan(t.fg);
        p.setSpan(cursorSpan, cs, p.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        promptText = p;
        termPrompt.setText(p, TextView.BufferType.SPANNABLE);
        termLogo.setTextColor(t.accent);
        appName.setTextColor(t.accent);
        appHints.setTextColor(t.fg);
        updateRowHeader();
        rowAdapter.cols = rowCols(c.columns);
        // grokbot sits just above the app window: follow its height (296 px fits the 6-per-row tiles)
        LayoutParams gbl = (LayoutParams) grokbotBox.getLayoutParams();
        gbl.bottomMargin = px(296) + rowAdapter.tileH(rowAdapter.cols) - rowAdapter.tileH(6);
        grokbotBox.setLayoutParams(gbl);
        rowAdapter.notifyDataSetChanged();
        gridAdapter.notifyDataSetChanged();
        refresh();
        buildWorkspace();
    }

    private static void tint(View b, int color) {
        View iv = ((ViewGroup) b).getChildAt(0);
        if (iv instanceof ImageView) ((ImageView) iv).setColorFilter(color);
    }

    private static void span(SpannableStringBuilder b, String s, int color) {
        int st = b.length();
        b.append(s);
        b.setSpan(new ForegroundColorSpan(color), st, b.length(), 0);
    }

    /** Clock, fastfetch line, widgets; cheap (in-memory reads + small cached files). */
    void refresh() {
        if (cfg == null) return;
        Locale loc = !"auto".equals(cfg.language) && !cfg.language.isEmpty() ? new Locale(cfg.language) : Locale.getDefault();
        String d = new SimpleDateFormat("EEE d MMM", loc).format(new Date()).replace(".", "").toLowerCase(loc);
        String h = new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(act) ? "HH:mm" : "h:mm a", loc).format(new Date());
        clock.setText(d + "  " + h);
        long up = SystemClock.elapsedRealtime() / 60_000L;
        SpannableStringBuilder info = new SpannableStringBuilder();
        span(info, act.getString(R.string.v29_theme) + " ", t.accent);
        span(info, t.name + "\n", t.fg);
        span(info, act.getString(R.string.v29_net) + " ", t.accent);
        span(info, host.wifiName() + "\n", t.fg);
        span(info, act.getString(R.string.v29_up) + " ", t.accent);
        span(info, (up / 60 > 0 ? (up / 60) + " h " : "") + (up % 60) + " min", t.fg);
        termInfo.setText(info);
        updateWeather();
        updatePc(pcBody, depBody, depBox, cfg.wDeploys);
        updateAi(aiBody);
        updateRemote();
        if (activeWs == 4) buildWorkspace();
    }

    /** Fetched panes (RemotePanes): "label … value" rows, value green/red by tone; dim after ttl, gone after 2 x ttl. */
    private void updateRemote() {
        JSONObject cache = RemotePanes.cached(act, cfg.remotePanesUrl);
        JSONArray panes = cache != null ? cache.optJSONArray("panes") : null;
        long now = System.currentTimeMillis() / 1000L;
        int shown = 0;
        for (int i = 0; i < remoteBox.length; i++) {
            JSONObject p = panes != null ? panes.optJSONObject(i) : null;
            long age = p != null ? now - RemotePanes.paneAt(cache, p) : 0;
            boolean show = p != null && activeWs == 1 && age <= 2L * p.optInt("ttl", 180);
            remoteBox[i].setVisibility(show ? VISIBLE : GONE);
            remoteBody[i].removeAllViews();
            if (!show) continue;
            shown++;
            remoteBox[i].setLegend(p.optString("title", p.optString("id")).toLowerCase(Locale.getDefault()), px(14), px(18));
            remoteBody[i].setAlpha(age > p.optInt("ttl", 180) ? 0.55f : 1f);
            JSONArray lines = p.optJSONArray("lines");
            for (int k = 0; lines != null && k < lines.length(); k++) {
                JSONObject l = lines.optJSONObject(k);
                if (l == null) continue;
                LinearLayout r = hbox();
                TextView n = text(l.optString("label"), REMOTE_TEXT, false);
                n.setTextColor(t.fg);
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.END);
                r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
                String tone = l.optString("tone");
                TextView v = text(l.optString("value"), REMOTE_TEXT, false);
                v.setTextColor("up".equals(tone) ? t.green : "down".equals(tone) ? t.red : t.fgBright);
                v.setSingleLine(true);
                LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
                vl.leftMargin = px(12);
                r.addView(v, vl);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
                ll.topMargin = px(k == 0 ? 0 : 8);
                remoteBody[i].addView(r, ll);
            }
        }
        remoteRow.setVisibility(shown > 0 ? VISIBLE : GONE);
        // With the widgets above switched off, the row moves up to the top (aligned with the fastfetch box).
        View row2 = (View) depBox.getParent();
        row2.setVisibility(depBox.getVisibility());
        boolean above = wxBox.getVisibility() == VISIBLE || pcBox.getVisibility() == VISIBLE
                || aiBoxRef.getVisibility() == VISIBLE || depBox.getVisibility() == VISIBLE;
        LinearLayout.LayoutParams rl = (LinearLayout.LayoutParams) remoteRow.getLayoutParams();
        int top = above ? px(16) : 0;
        if (rl.topMargin != top) {
            rl.topMargin = top;
            remoteRow.setLayoutParams(rl);
        }
    }

    /** Fetched panes are read from across the room: larger than the 19 px widget text. */
    private static final float REMOTE_TEXT = 24;

    private void updateWeather() {
        wxTemp.setTextColor(t.accent);
        wxLine1.setTextColor(t.fg);
        wxLine2.setTextColor(t.fg);
        JSONObject w = Weather.cached(act);
        JSONObject cur = w != null ? w.optJSONObject("current") : null;
        barWx.setVisibility(cfg.barWeather && cur != null ? VISIBLE : GONE);
        if (cfg.barWeather && cur != null) {
            barWxIcon.setImageResource(Weather.icon(cur.optInt("weather_code")));
            barWxIcon.setColorFilter(t.fgBright);
            barWxTemp.setTextColor(t.fgBright);
            barWxTemp.setText(Weather.temp(cur.optDouble("temperature_2m"), cfg.tempF) + (cfg.tempF ? "°F" : "°"));
            barWx.setContentDescription(act.getString(Weather.describe(cur.optInt("weather_code"))));
        }
        if (cur == null) {
            wxTemp.setText("--°");
            wxLine1.setText(Weather.where(act, cfg) == null ? act.getString(R.string.v29_wx_locating) : act.getString(R.string.weather_loading));
            wxLine2.setText("");
            return;
        }
        wxTemp.setText(Weather.temp(cur.optDouble("temperature_2m"), cfg.tempF) + (cfg.tempF ? "°F" : "°"));
        String place = Weather.place(act, cfg);
        String cond = act.getString(Weather.describe(cur.optInt("weather_code"))).toLowerCase(Locale.getDefault());
        wxLine1.setText(place.isEmpty() ? cond : place + " · " + cond);
        JSONObject daily = w.optJSONObject("daily");
        JSONArray mn = daily != null ? daily.optJSONArray("temperature_2m_min") : null, mx = daily != null ? daily.optJSONArray("temperature_2m_max") : null;
        wxLine2.setText(mn != null && mx != null ? "min " + Weather.temp(mn.optDouble(0), cfg.tempF) + "° · max " + Weather.temp(mx.optDouble(0), cfg.tempF) + "°" : "");
    }

    private TextView offline() {
        TextView v = line(act.getString(R.string.v29_pc_off));
        v.setTextColor(t.fg);
        return v;
    }

    /** "label  [bar]  NN%": bar 12 px (CPU/RAM) or 10 px (AI), fill colour given, track = selection. */
    private View barRow(String label, int labelCols, double frac, int fill, int barH, String right) {
        LinearLayout r = hbox();
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = px(6);
        r.setLayoutParams(l);
        TextView a = text(label, 19, false);
        a.setTextColor(t.fg);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        float cw = a.getPaint().measureText("M");
        r.addView(a, new LinearLayout.LayoutParams(Math.round(cw * labelCols), -2));
        BarView b = new BarView(act, t.selection, fill, frac);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, px(barH), 1f);
        bl.leftMargin = px(8);
        bl.rightMargin = px(10);
        r.addView(b, bl);
        TextView pct = text(right, 19, false);
        pct.setTextColor(t.fg);
        pct.setGravity(Gravity.END);
        r.addView(pct, new LinearLayout.LayoutParams(Math.round(cw * 4), -2));
        return r;
    }

    private void updatePc(LinearLayout pcBody, LinearLayout depBody, View depBox, boolean depOn) {
        pcBody.removeAllViews();
        depBody.removeAllViews();
        JSONObject st = OmarchyLink.connected() ? com.omarchytv.home.link.LinkServer.stats() : null;
        if (!OmarchyLink.connected()) {
            pcBody.addView(offline());
        } else if (st == null) {
            pcBody.addView(line("…"));
        } else {
            if (st.has("cpu")) pcBody.addView(barRow("cpu", 4, st.optInt("cpu") / 100.0, t.green, 12, st.optInt("cpu") + "%"));
            if (st.has("ram")) pcBody.addView(barRow("ram", 4, st.optInt("ram") / 100.0, t.yellow, 12, st.optInt("ram") + "%"));
            JSONObject np = st.optJSONObject("nowPlaying");
            if (np != null) {
                LinearLayout r = hbox();
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
                l.topMargin = px(8);
                ImageView ic = new ImageView(act);
                ic.setImageResource(R.drawable.ln_music);
                ic.setColorFilter(t.magenta);
                r.addView(ic, new LinearLayout.LayoutParams(px(19), px(19)));
                String a = np.optString("artist", "");
                TextView tx = text((a.isEmpty() ? "" : a + " — ") + np.optString("title"), 19, false);
                tx.setTextColor(t.fg);
                tx.setSingleLine(true);
                tx.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams txl = new LinearLayout.LayoutParams(0, -2, 1f);
                txl.leftMargin = px(8);
                r.addView(tx, txl);
                pcBody.addView(r, l);
            }
        }
        JSONArray d = st != null ? st.optJSONArray("deploys") : null;
        depBox.setVisibility(depOn && d != null && d.length() > 0 ? VISIBLE : GONE);
        for (int i = 0; d != null && i < d.length(); i++) {
            JSONObject x = d.optJSONObject(i);
            String stt = x.optString("status");
            int col = "green".equals(stt) || "ok".equals(stt) ? t.green : "yellow".equals(stt) || "building".equals(stt) ? t.yellow : t.red;
            LinearLayout r = hbox();
            TextView n = text(x.optString("name"), 19, false);
            n.setTextColor(t.fg);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView v = text(stt, 19, false);
            v.setTextColor(col);
            v.setSingleLine(true);
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
            vl.leftMargin = px(12);
            r.addView(v, vl);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
            l.topMargin = px(i == 0 ? 0 : 6);
            depBody.addView(r, l);
        }
    }

    private void updateAi(LinearLayout aiBody) {
        aiBody.removeAllViews();
        if (!OmarchyLink.connected() && cfg.aiAt > 0 || cfg.ai.isEmpty() && !OmarchyLink.connected()) {
            aiBody.addView(offline());
            return;
        }
        if (cfg.ai.isEmpty()) {
            aiBody.addView(line(act.getString(R.string.ai_empty)));
            return;
        }
        int nameW = 4;
        for (LauncherConfig.AiEntry a : cfg.ai) nameW = Math.max(nameW, Math.min(8, a.name.length()));
        int shown = 0;
        for (LauncherConfig.AiEntry a : cfg.ai) {
            if (shown++ >= 5) break;
            double f = a.fraction();
            if (f >= 0) {
                int col = f > 0.9 ? t.red : f >= 0.7 ? t.yellow : t.green;
                aiBody.addView(barRow(a.name, nameW, f, col, 10, Math.round(f * 100) + "%"));
            } else {
                TextView v = line(a.name + "  " + (a.usedUnknown() ? "?" : MainActivity.amountText(a.used, a.unit)));
                v.setTextColor(t.fg);
                aiBody.addView(v);
            }
        }
    }

    // ---- bar tooltip (network name only in the menu shown while the icon is focused) ----

    private void showTip(View anchor, int k) {
        if (anchor == null) {
            tip.setVisibility(GONE);
            return;
        }
        String[] labels = {"cinemarchy", host.wifiName(), "bluetooth", act.getString(R.string.v29_volume), act.getString(R.string.inputs),
                act.getString(R.string.v29_screen_pc)};
        if (k == 3) {
            android.media.AudioManager am = act.getSystemService(android.media.AudioManager.class);
            if (am != null) labels[3] += " " + Math.round(100f * am.getStreamVolume(3) / Math.max(1, am.getStreamMaxVolume(3))) + "%";
        }
        tip.setText(labels[k]);
        tip.setVisibility(VISIBLE);
        tip.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2], me = new int[2];
        anchor.getLocationInWindow(loc);
        getLocationInWindow(me);
        LayoutParams l = (LayoutParams) tip.getLayoutParams();
        l.topMargin = px(56);
        l.leftMargin = Math.max(px(32), Math.min(getWidth() - px(32) - tip.getMeasuredWidth(), loc[0] - me[0] + anchor.getWidth() / 2 - tip.getMeasuredWidth() / 2));
        tip.setLayoutParams(l);
    }

    // ---- workspaces (v2.10: 1 home · 2 tv · 3 app · 4 pc · 5 sistema, all inside this view) ----

    int workspace() {
        return activeWs;
    }

    /** The bar number of the active workspace ("workspaces": {"order"} decides its position). */
    private TextView activeWsButton() {
        int pos = cfg != null ? cfg.positionOf(activeWs) : activeWs;
        return ws[Math.max(1, pos) - 1];
    }

    void setWorkspace(int n) {
        if (n < 1 || n > 5) return;
        int prev = activeWs;
        View f = act.getCurrentFocus();
        if (f != null && zone(f) >= 1) wsFocus.put(prev, f);
        if (movingApp >= 0) host.finishMove(false);
        activeWs = n;
        boolean home = n == 1;
        term.setVisibility(home && (cfg == null || cfg.wTerm) ? VISIBLE : GONE);
        widgets.setVisibility(home ? VISIBLE : GONE);
        appWin.setVisibility(home ? VISIBLE : GONE);
        grokbotBox.setVisibility(home && (cfg == null ? false : CompanionPlacement.homeTile(cfg.pluginGrokbot, cfg.companionOverlay)) ? VISIBLE : GONE);
        tvWin.setVisibility(n == 2 ? VISIBLE : GONE);
        appsWin.setVisibility(n == 3 ? VISIBLE : GONE);
        pcWin.setVisibility(n == 4 ? VISIBLE : GONE);
        sysWin.setVisibility(n == 5 ? VISIBLE : GONE);
        if (cfg != null) bind(cfg, apps);
        // Horizontal slide, 120 ms (spec: ≤150 ms); no animation on the first show.
        if (prev != n) {
            boolean right = cfg != null ? cfg.positionOf(n) > cfg.positionOf(prev) : n > prev;
            float dx = (right ? 1 : -1) * px(80);
            for (View v : new View[]{term, widgets, appWin, tvWin, appsWin, pcWin, sysWin}) {
                if (v.getVisibility() != VISIBLE) continue;
                v.setTranslationX(dx);
                v.setAlpha(0.4f);
                v.animate().translationX(0).alpha(1f).setDuration(120).start();
            }
        }
        if (home) stopLogo();
        post(this::restoreFocus);
    }

    /** Focus the view this workspace had, else its default (TV tile on home, first tile / row elsewhere). */
    void restoreFocus() {
        View v = wsFocus.get(activeWs);
        if (v != null && v.isAttachedToWindow() && v.isShown() && v.requestFocus()) return;
        focusDefault();
    }

    void focusDefault() {
        switch (activeWs) {
            case 1:
                focusApp(0);
                break;
            case 3:
                focusGrid(0);
                break;
            default: {
                ViewGroup g = activeWs == 2 ? tvBody : activeWs == 4 ? pcPaneBody : sysBody;
                if (!focusFirst(g)) activeWsButton().requestFocus();
            }
        }
    }

    private static boolean focusFirst(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != VISIBLE) continue;
            if (v.isFocusable() && v.requestFocus()) return true;
            if (v instanceof ViewGroup && focusFirst((ViewGroup) v)) return true;
        }
        return false;
    }

    /** Called by MainActivity on resume: home always comes back on the TV tile (spec: default focus). */
    void onHomeShown() {
        wsFocus.remove(1);
        lastApp = 0;
        if (activeWs == 1) {
            focusApp(0);
            startLogo();
        } else restoreFocus();
    }

    /** Blinking prompt cursor on the home (driven by MainActivity's blink tick while resumed). */
    void blink(boolean on) {
        if (cursorSpan == null || promptText == null || activeWs != 1 || t == null) return;
        int st = promptText.getSpanStart(cursorSpan), en = promptText.getSpanEnd(cursorSpan);
        if (st < 0) return;
        promptText.removeSpan(cursorSpan);
        cursorSpan = new ForegroundColorSpan(on ? t.fg : Color.TRANSPARENT);
        promptText.setSpan(cursorSpan, st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        termPrompt.setText(promptText, TextView.BufferType.SPANNABLE);
        if (grokbotBox.getVisibility() == VISIBLE) {
            grokbotEyes.setText(on ? "[ ◉ ◉ ]" : "[ ▬ ▬ ]");
            grokbotEyes.setTextColor(on ? t.accent : t.accent2);
            grokbotMark.blink(on || SystemClock.uptimeMillis() % 4000 > 600);
        }
    }

    private void buildWorkspace() {
        if (t == null) return;
        if (activeWs == 2) buildTv();
        else if (activeWs == 4) buildPc();
        else if (activeWs == 5) buildSys();
    }

    // ---- 2 · tv: TV + connected HDMI inputs as large 16:9 tiles (no channels, no guide) ----

    private void buildTv() {
        View had = act.getCurrentFocus();
        int hadIdx = -1;
        tvBody.removeAllViews();
        List<Source> src = host.sources();
        int cols = 4, gap = px(24);
        int inner = Math.round(1920 * s) - px(64) - px(56);
        int w = (inner - gap * (cols - 1)) / cols;
        LinearLayout r = null;
        for (int i = 0; i < src.size(); i++) {
            if (i % cols == 0) {
                r = hbox();
                r.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = i == 0 ? px(12) : gap;
                tvBody.addView(r, rl);
            }
            Source x = src.get(i);
            FrameLayout f = bigTile(x.icon, x.label, x.sub);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, w * 9 / 16);
            l.rightMargin = (i % cols) == cols - 1 ? 0 : gap;
            r.addView(f, l);
            f.setOnClickListener(v -> x.open.run());
        }
        if (src.isEmpty()) {
            TextView v = line(act.getString(R.string.no_inputs));
            v.setTextColor(t.fg);
            tvBody.addView(v);
        }
    }

    private FrameLayout bigTile(int icon, String label, String sub) {
        FrameLayout f = new FrameLayout(act);
        f.setFocusable(true);
        f.setClickable(true);
        LinearLayout col = vbox();
        col.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(act);
        iv.setImageResource(icon);
        iv.setColorFilter(t.accent);
        col.addView(iv, new LinearLayout.LayoutParams(px(72), px(72)));
        TextView tv = text(label, 30, true);
        tv.setTextColor(t.fgBright);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = px(12);
        col.addView(tv, tl);
        if (sub != null && !sub.isEmpty()) {
            TextView sv = text(sub, 20, false);
            sv.setTextColor(t.fg);
            sv.setSingleLine(true);
            sv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(sv, new LinearLayout.LayoutParams(-2, -2));
        }
        f.addView(col, new LayoutParams(-1, -1));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.bgLight);
        f.setBackground(bg);
        f.setForeground(new FocusRing());
        styleTile(f, false, false);
        f.setOnFocusChangeListener((v, has) -> styleTile((FrameLayout) v, has, true));
        return f;
    }

    // ---- 4 · pc: pairing prompt, or the omarchy · pc / ai credits / deploys widgets in large ----

    private void buildPc() {
        boolean keep = pcWin.hasFocus();
        pcPaneBody.removeAllViews();
        if (!host.pcPaired()) {
            TextView m = text(act.getString(R.string.v210_pc_link), 34, true);
            m.setTextColor(t.fgBright);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
            ml.topMargin = px(24);
            pcPaneBody.addView(m, ml);
            TextView h = line(act.getString(R.string.v210_pc_link_hint));
            h.setTextColor(t.fg);
            h.setSingleLine(false);
            pcPaneBody.addView(h);
            LinearLayout actions = hbox();
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
            al.topMargin = px(28);
            al.bottomMargin = px(16);
            TextView b = button(act.getString(R.string.v210_pc_pair), () -> host.pairPc());
            TextView bGrok = button(act.getString(R.string.grok_bot_hub), () -> host.sys("grok-bot"));
            TextView bCine = button("Cinemarchy", () -> host.sys("cinemarchy"));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
            gl.leftMargin = px(18);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.leftMargin = px(18);
            actions.addView(b);
            actions.addView(bGrok, gl);
            actions.addView(bCine, cl);
            pcPaneBody.addView(actions, al);
            if (keep) b.requestFocus();
            return;
        }
        big = 1.35f;
        LinearLayout r = hbox();
        r.setGravity(Gravity.TOP);
        r.setBaselineAligned(false);
        pcPaneBody.addView(r, new LinearLayout.LayoutParams(-1, -2));
        BoxView pc = widget(r, "omarchy · pc", 0), ai = widget(r, "ai credits · pc", 24), dep = widget(r, "deploys · pc", 24);
        int winBg = Theme.withAlpha(t.bg, Math.min(0.98f, opacity(cfg) + noBlur()));
        for (BoxView b : new BoxView[]{pc, ai, dep}) b.setColors(winBg, t.muted, t.accent, t.cyan);
        LinearLayout pb = vbox(), ab = vbox(), db = vbox();
        pc.addView(pb, new LinearLayout.LayoutParams(-1, -2));
        ai.addView(ab, new LinearLayout.LayoutParams(-1, -2));
        dep.addView(db, new LinearLayout.LayoutParams(-1, -2));
        updatePc(pb, db, dep, true);
        if (dep.getVisibility() != VISIBLE) {
            dep.setVisibility(VISIBLE);
            TextView v = line("—");
            v.setTextColor(t.fg);
            db.addView(v);
        }
        updateAi(ab);
        ai.setOnClickListener(host::aiMenu);
        big = 1f;
        LinearLayout actions = hbox();
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.topMargin = px(20);
        TextView bGrok = button(act.getString(R.string.grok_bot_hub), () -> host.sys("grok-bot"));
        TextView bCine = button("Cinemarchy", () -> host.sys("cinemarchy"));
        TextView bAsk = button(act.getString(R.string.ask_entry), () -> host.sys("ask"));
        TextView bAgents = button("> agents", () -> host.sys("agents"));
        LinearLayout.LayoutParams sl0 = new LinearLayout.LayoutParams(-2, -2);
        sl0.leftMargin = px(16);
        LinearLayout.LayoutParams sl1 = new LinearLayout.LayoutParams(-2, -2);
        sl1.leftMargin = px(16);
        LinearLayout.LayoutParams sl2 = new LinearLayout.LayoutParams(-2, -2);
        sl2.leftMargin = px(16);
        actions.addView(bGrok);
        actions.addView(bCine, sl0);
        actions.addView(bAsk, sl1);
        actions.addView(bAgents, sl2);
        pcPaneBody.addView(actions, al);
        if (keep) pc.requestFocus();
    }

    private TextView button(String label, Runnable r) {
        TextView b = text("[ " + label + " ]", 26, true);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(px(18), px(10), px(18), px(10));
        b.setTextColor(t.fgBright);
        b.setBackground(new Underline());
        b.setOnClickListener(v -> r.run());
        return b;
    }

    // ---- 5 · sistema: Android settings, themes, transparency, screensaver, info and the v2.10 options ----

    private void buildSys() {
        int had = -1;
        View f = act.getCurrentFocus();
        if (f != null && f.getParent() == sysBody) had = sysBody.indexOfChild(f);
        sysBody.removeAllViews();
        String idle = cfg.screensaverIdleSec <= 0 ? act.getString(R.string.ss_never)
                : cfg.screensaverIdleSec % 60 == 0 ? (cfg.screensaverIdleSec / 60) + " min"
                : String.format(Locale.getDefault(), "%.1f min", cfg.screensaverIdleSec / 60f);
        String on = act.getString(R.string.on), off = act.getString(R.string.off);
        sysRow(R.string.v210_sys_android, null, "android");
        sysRow(R.string.v210_sys_themes, t.name, "themes");
        sysRow(R.string.v210_sys_transparency, cfg.transparency + "%", "layout");
        sysRow(R.string.v210_sys_widgets, null, "widgets");
        sysRow(R.string.v210_arrange, null, "arrange");
        sysRow(R.string.menu_screensaver, null, "screensaver");
        sysRow(R.string.v210_ss_after, idle, "idle");
        sysRow(R.string.v210_logo_anim, cfg.logoAnim ? on : off, "logoAnim");
        sysRow(R.string.v210_text_tiles, cfg.textTiles ? on : off, "textTiles");
        sysRow(R.string.cinemarchy_hub, null, "cinemarchy");
        sysRow(R.string.grok_bot_hub, null, "grok-bot");
        sysRow(R.string.set_hidden, String.valueOf(cfg.hidden.size()), "hidden");
        sysRow(R.string.v210_sys_all, null, "menu");
        sysRow(R.string.menu_about, null, "about");
        if (had >= 0 && had < sysBody.getChildCount()) sysBody.getChildAt(had).requestFocus();
    }

    private void sysRow(int label, String value, String id) {
        FrameLayout r = new FrameLayout(act);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(px(16), px(10), px(16), px(10));
        TextView a = text(act.getString(label), 24, false);
        a.setTextColor(t.fgBright);
        r.addView(a, new LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        TextView b = text(value != null ? value : "›", 24, false);
        b.setTextColor(t.accent);
        r.addView(b, new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        r.setBackground(new Underline());
        r.setOnClickListener(v -> host.sys(id));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(900), -2);
        l.topMargin = px(4);
        sysBody.addView(r, l);
    }

    // ---- v2.10 "Disponi riquadri": move home panels on a 12x8 grid (fractions of the screen: same at 1080p and 4K) ----
    static final int GRID_X = 12, GRID_Y = 8;
    static final String[] PANEL_IDS = {"term", "weather", "pc", "ai", "deploys"};
    private View editPanel;
    private String editId;
    private int editCx, editCy, editOldCx, editOldCy;

    View panelView(String id) {
        switch (id) {
            case "term": return term;
            case "weather": return wxBox;
            case "pc": return pcBox;
            case "ai": return aiBoxRef;
            case "deploys": return depBox;
            default: return null;
        }
    }

    private float cellW() { return getWidth() / (float) GRID_X; }
    private float cellH() { return getHeight() / (float) GRID_Y; }

    void applyPanelPos(LauncherConfig c) {
        for (String id : PANEL_IDS) {
            View v = panelView(id);
            int[] p = c.panelPos.get(id);
            v.setTranslationX(p != null ? p[0] * cellW() : 0);
            v.setTranslationY(p != null ? p[1] * cellH() : 0);
        }
    }

    /** View rect in this layout's coordinates, with an offset of (cx, cy) cells instead of its translation. */
    private android.graphics.RectF rectAt(View v, float tx, float ty) {
        float x = v.getLeft() + tx, y = v.getTop() + ty;
        for (android.view.ViewParent p = v.getParent(); p instanceof View && p != this; p = p.getParent()) {
            View pv = (View) p;
            x += pv.getLeft() + pv.getTranslationX();
            y += pv.getTop() + pv.getTranslationY();
        }
        return new android.graphics.RectF(x, y, x + v.getWidth(), y + v.getHeight());
    }

    /** True if the panel at (cx, cy) stays on screen, below the bar, above the app row and off every other panel. */
    private boolean fits(View v, int cx, int cy) {
        android.graphics.RectF r = rectAt(v, cx * cellW(), cy * cellH());
        if (r.left < 0 || r.right > getWidth() || r.top < barHeight() || r.bottom > getHeight()) return false;
        if (appWin.getVisibility() == VISIBLE && android.graphics.RectF.intersects(r, rectAt(appWin, appWin.getTranslationX(), appWin.getTranslationY()))) return false;
        for (String id : PANEL_IDS) {
            View o = panelView(id);
            if (o == v || o.getVisibility() != VISIBLE) continue;
            if (android.graphics.RectF.intersects(r, rectAt(o, o.getTranslationX(), o.getTranslationY()))) return false;
        }
        return true;
    }

    boolean editPanel(String id) {
        View v = panelView(id);
        if (v == null || v.getVisibility() != VISIBLE || activeWs != 1) return false;
        editPanel = v;
        editId = id;
        editCx = editOldCx = Math.round(v.getTranslationX() / cellW());
        editCy = editOldCy = Math.round(v.getTranslationY() / cellH());
        v.setAlpha(0.8f);
        v.setFocusable(true);
        v.requestFocus();
        return true;
    }

    private boolean panelKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int dx = 0, dy = 0;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                endPanelEdit(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                endPanelEdit(false);
                return true;
            default:
                return true;
        }
        // Collision prevention: skip over cells that would overlap, stop at the edge.
        for (int step = 1; step <= Math.max(GRID_X, GRID_Y); step++) {
            int nx = editCx + dx * step, ny = editCy + dy * step;
            android.graphics.RectF r = rectAt(editPanel, nx * cellW(), ny * cellH());
            if (r.left < 0 || r.right > getWidth() || r.top < barHeight() || r.bottom > getHeight()) break;
            if (fits(editPanel, nx, ny)) {
                editCx = nx;
                editCy = ny;
                editPanel.animate().translationX(nx * cellW()).translationY(ny * cellH()).setDuration(80).start();
                break;
            }
        }
        android.util.Log.i("OmarchyTV", "panel " + editId + " -> " + editCx + "," + editCy);
        return true;
    }

    private void endPanelEdit(boolean save) {
        View v = editPanel;
        editPanel = null;
        v.setAlpha(1f);
        if (save) {
            host.savePanelPos(editId, editCx, editCy);
        } else {
            v.setTranslationX(editOldCx * cellW());
            v.setTranslationY(editOldCy * cellH());
        }
        focusApp(lastApp);
    }

    int barHeight() {
        return px(52);
    }

    // ---- animated OMARCHY TV logo (v2.10): 1-2 s "decrypt" or "print" when the home opens, then still ----

    private static final String GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%&*<>[]{}/\\|=+";
    private long logoStart;
    private boolean logoPrint;
    private final java.util.Random rnd = new java.util.Random();
    private float[] logoAt;

    private final Runnable logoTick = new Runnable() {
        @Override
        public void run() {
            float p = Math.min(1f, (SystemClock.uptimeMillis() - logoStart) / 1500f);
            if (p >= 1f || !isShown() || activeWs != 1) {
                termLogo.setText(logoFinal);
                return;
            }
            termLogo.setText(logoPrint ? printFrame(p) : decryptFrame(p));
            anim.postDelayed(this, 40);
        }
    };

    void startLogo() {
        anim.removeCallbacks(logoTick);
        logoFinal = logoText();
        if (cfg == null || !cfg.logoAnim || activeWs != 1) {
            termLogo.setText(logoFinal);
            return;
        }
        logoPrint = rnd.nextBoolean();
        logoAt = new float[logoFinal.length()];
        for (int i = 0; i < logoAt.length; i++) logoAt[i] = 0.15f + rnd.nextFloat() * 0.8f;
        logoStart = SystemClock.uptimeMillis();
        android.util.Log.i("OmarchyTV", "logo: effetto " + (logoPrint ? "print" : "decrypt"));
        anim.post(logoTick);
    }

    /** No timers while the home is not visible. */
    void stopLogo() {
        anim.removeCallbacks(logoTick);
        if (!logoFinal.isEmpty()) termLogo.setText(logoFinal);
    }

    private String decryptFrame(float p) {
        char[] out = logoFinal.toCharArray();
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            if (c == '\n' || c == ' ') continue;
            if (p < logoAt[i]) out[i] = GLYPHS.charAt(rnd.nextInt(GLYPHS.length()));
        }
        return new String(out);
    }

    private String printFrame(float p) {
        String[] lines = logoFinal.split("\n", -1);
        float pos = p * lines.length;
        int full = (int) pos;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) b.append('\n');
            if (i < full) b.append(lines[i]);
            else if (i == full) {
                int n = Math.round((pos - full) * lines[i].length());
                b.append(lines[i], 0, Math.min(n, lines[i].length()));
                if (n < lines[i].length()) b.append('█');
            }
        }
        return b.toString();
    }

    // ---- D-pad (spec §3.5) ----

    private int zone(View f) {
        for (View v = f; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == bar) return 0;
            if (v == widgets) return 1;
            if (v == row) return 2;
            if (v == grid) return 3;
            if (v == tvWin || v == pcWin || v == sysWin) return 4;
            if (v == this) break;
        }
        return -1;
    }

    /** Called before the default dispatch while the home is shown. True = consumed. */
    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (movingApp >= 0) return moveKey(e);
        if (editPanel != null) return panelKey(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (k == KeyEvent.KEYCODE_C) {
            host.sys("cinemarchy");
            return true;
        }
        View f = act.getCurrentFocus();
        int z = zone(f);
        boolean arrow = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (z == -1) {
            if (arrow) {
                focusDefault();
                return true;
            }
            return false;
        }
        if (z == 0) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (activeWs == 1) {
                    if (!focusFirstVisible(widgets)) focusApp(lastApp);
                } else restoreFocus();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                boolean r = k == KeyEvent.KEYCODE_DPAD_RIGHT;
                List<View> order = new ArrayList<>();
                order.add(menuBtn);
                for (TextView w : ws) order.add(w);
                order.add(barSpacex);
                order.addAll(barIcons);
                // hidden workspaces (and SpaceX TV when off) are GONE: skip them, or focus gets stuck
                int to = BarFocus.next(order, f, r, v -> v.getVisibility() == VISIBLE && v.isFocusable());
                if (to >= 0) order.get(to).requestFocus();
                else if (BarFocus.pastEnd(order, f, r, v -> v.getVisibility() == VISIBLE && v.isFocusable())) host.selectCompanion();
                return true;
            }
            return false;
        }
        if (z == 3) {   // 3 · app grid: up from the first row goes to the bar
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                int pos = grid.getChildAdapterPosition(grid.findContainingItemView(f));
                if (pos >= 0 && pos < 7) {
                    wsFocus.put(3, f);
                    activeWsButton().requestFocus();
                    return true;
                }
                if (pos >= 7) {
                    focusGrid(pos - 7);
                    return true;
                }
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                int pos = grid.getChildAdapterPosition(grid.findContainingItemView(f));
                if (pos >= 0) {
                    int to = Math.min(gridAdapter.getItemCount() - 1, pos + 7);
                    if (to / 7 > pos / 7) focusGrid(to);
                    return true;
                }
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int pos = grid.getChildAdapterPosition(grid.findContainingItemView(f));
                int to = pos + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (pos >= 0 && to >= 0 && to < gridAdapter.getItemCount()) focusGrid(to);
                return true;
            }
            return false;
        }
        if (z == 4) {   // 2 / 4 / 5: default focus search inside the window; nothing above = the bar
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                View nx = f.focusSearch(FOCUS_UP);
                if (nx == null || zone(nx) != 4) {
                    wsFocus.put(activeWs, f);
                    activeWsButton().requestFocus();
                    return true;
                }
            }
            if (arrow) {
                int dir = k == KeyEvent.KEYCODE_DPAD_DOWN ? FOCUS_DOWN : k == KeyEvent.KEYCODE_DPAD_LEFT ? FOCUS_LEFT : k == KeyEvent.KEYCODE_DPAD_RIGHT ? FOCUS_RIGHT : FOCUS_UP;
                View nx = f.focusSearch(dir);
                if (nx != null && zone(nx) == 4) nx.requestFocus();
                return true;
            }
            return false;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (z == 2) {
                    if (!focusFirstVisible(widgets)) ws[0].requestFocus();
                } else if (z == 1) activeWsButton().requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (z == 1) {
                    if (f.getParent() == ((ViewGroup) widgets.getChildAt(0)) && depBox.getVisibility() == VISIBLE && f == aiBoxRef) depBox.requestFocus();
                    else focusApp(lastApp);
                    return true;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                boolean r = k == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (z == 2) {
                    int pos = row.getChildAdapterPosition(row.findContainingItemView(f));
                    int to = pos + (r ? 1 : -1);
                    if (to >= 0 && to < rowAdapter.getItemCount()) focusApp(to);
                    return true;
                }
                if (f == depBox) return true;
                ViewGroup g = (ViewGroup) f.getParent();
                int i = g.indexOfChild(f), to = i + (r ? 1 : -1);
                while (to >= 0 && to < g.getChildCount() && (g.getChildAt(to).getVisibility() != VISIBLE || !g.getChildAt(to).isFocusable())) to += r ? 1 : -1;
                if (to >= 0 && to < g.getChildCount()) g.getChildAt(to).requestFocus();
                return true;
            }
            default:
                return false;
        }
    }

    private boolean focusFirstVisible(ViewGroup g) {
        ViewGroup r1 = (ViewGroup) g.getChildAt(0);
        for (int i = 0; i < r1.getChildCount(); i++) {
            View v = r1.getChildAt(i);
            if (v.getVisibility() == VISIBLE && v.isFocusable()) return v.requestFocus();
        }
        if (depBox.getVisibility() == VISIBLE) return depBox.requestFocus();
        return false;
    }

    /** The TV tile is the first item of the home row unless "tv.enabled" is false. */
    boolean tvShown() {
        return cfg == null || cfg.tvEnabled;
    }

    int tvOffset() {
        return tvShown() ? 1 : 0;
    }

    void focusApp(int pos) {
        focusIn(row, rowAdapter, pos, true);
    }

    void focusGrid(int pos) {
        focusIn(grid, gridAdapter, pos, false);
    }

    private void focusIn(RecyclerView rv, TileAdapter ad, int pos, boolean horizontal) {
        int n = ad.getItemCount();
        if (n == 0) {
            activeWsButton().requestFocus();
            return;
        }
        final int p = Math.max(0, Math.min(n - 1, pos));
        RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(p);
        if (h != null && fullyVisible(rv, h.itemView)) {
            h.itemView.requestFocus();
            return;
        }
        rv.scrollToPosition(p);
        rv.post(() -> {
            RecyclerView.ViewHolder h2 = rv.findViewHolderForAdapterPosition(p);
            if (h2 != null) {
                h2.itemView.requestFocus();
                if (horizontal) {
                    int l = h2.itemView.getLeft(), r = h2.itemView.getRight(), w = rv.getWidth();
                    if (r > w) rv.scrollBy(r - w + px(6), 0);
                    else if (l < 0) rv.scrollBy(l - px(6), 0);
                } else {
                    int tp = h2.itemView.getTop(), b = h2.itemView.getBottom(), hh = rv.getHeight();
                    if (b > hh) rv.scrollBy(0, b - hh + px(12));
                    else if (tp < 0) rv.scrollBy(0, tp - px(12));
                }
            }
        });
    }

    private static boolean fullyVisible(RecyclerView rv, View v) {
        return v.getLeft() >= 0 && v.getRight() <= rv.getWidth() && v.getTop() >= 0 && v.getBottom() <= rv.getHeight();
    }

    View focusedTile() {
        View f = act.getCurrentFocus();
        int z = zone(f);
        return z == 2 || z == 3 ? f : null;
    }

    // ---- move mode (OK lungo → Sposta): ◀▶ (▲▼ in the grid) move the app, OK saves, Back cancels ----

    void startMove(int appPos) {
        movingApp = appPos;
        movingInGrid = activeWs == 3;
        updateRowHeader();
        if (movingInGrid) {
            gridName.setText(act.getString(R.string.move_hint));
            focusGrid(appPos);
        } else focusApp(appPos + tvOffset());
    }

    /** The app grid's key hints; the workspace keys run 1..n, n = the workspaces in the bar. */
    private String gridHintsText() {
        return getContext().getString(R.string.v210_grid_hints, cfg != null ? cfg.workspaceOrder.size() : 5);
    }

    void endMove() {
        movingApp = -1;
        updateRowHeader();
        gridHints.setText(gridHintsText());
    }

    private boolean moveKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int n = apps != null ? apps.getItemCount() : 0;
        int to = -1;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && movingApp > 0) to = movingApp - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && movingApp < n - 1) to = movingApp + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && movingInGrid && movingApp - 7 >= 0) to = movingApp - 7;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && movingInGrid && movingApp + 7 < n) to = movingApp + 7;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) host.finishMove(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) host.finishMove(false);
                return true;
            default:
                return true;
        }
        if (to >= 0) {
            host.moveApp(movingApp, to);
            movingApp = to;
            rowAdapter.notifyDataSetChanged();
            gridAdapter.notifyDataSetChanged();
            if (movingInGrid) focusGrid(to);
            else focusApp(to + tvOffset());
        }
        return true;
    }

    private void updateRowHeader() {
        if (t == null) return;
        if (movingApp >= 0 && !movingInGrid) {
            appHints.setText(R.string.move_hint);
            return;
        }
        boolean tvFocused = lastApp == 0 && tvShown();
        appHints.setText(tvFocused ? R.string.v210_tv_hints : R.string.v210_app_hints);
        if (tvFocused) appName.setText("TV");
    }

    /** Home row tiles visible at once: Layout → App size (4..7); 0 (no config yet) gives the v2.9 six. */
    static int rowCols(int columns) {
        return columns <= 0 ? 6 : Math.max(4, Math.min(7, columns));
    }

    /** 16:9 tile width that fits cols tiles and cols - 1 gaps in inner px. */
    static int tileW(int inner, int gap, int cols) {
        return (inner - gap * (cols - 1)) / cols;
    }

    // ---- tiles: home row (TV + apps, App size per row, 16:9) and the 7-per-row grid of workspace 3 ----
    // Focus (spec): 4 px accent border + 2 px dark outer outline + scale 1.05; unfocused: 2 px muted border.

    private final class TileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final boolean tvFirst;
        int cols;
        private final RecyclerView rv;

        TileAdapter(boolean tvFirst, int cols, RecyclerView rv) {
            this.tvFirst = tvFirst;
            this.cols = cols;
            this.rv = rv;
        }

        /** Home row with the TV tile shown ("tv.enabled": false hides it). */
        boolean tvOn() {
            return tvFirst && tvShown();
        }

        int appIndex(int pos) {
            return tvOn() ? pos - 1 : pos;
        }

        @Override
        public int getItemCount() {
            return (apps != null ? apps.getItemCount() : 0) + (tvOn() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            return tvOn() && position == 0 ? 1 : 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout f = new FrameLayout(act);
            f.setFocusable(true);
            f.setClickable(true);
            if (viewType == 1) {   // TV tile: line TV icon (accent, ~56 px) over "TV" mono bold 28 px
                LinearLayout col = vbox();
                col.setGravity(Gravity.CENTER);
                ImageView iv = new ImageView(act);
                iv.setImageResource(R.drawable.ln_monitor);
                col.addView(iv, new LinearLayout.LayoutParams(px(56), px(56)));
                TextView tv = text("TV", 28, true);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                tl.topMargin = px(8);
                col.addView(tv, tl);
                f.addView(col, new LayoutParams(-1, -1));
            } else {
                ImageView iv = new ImageView(act);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                f.addView(iv, new LayoutParams(-1, -1));
                TextView tv = text("", 24, false);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(px(10), 0, px(10), 0);
                tv.setMaxLines(2);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                f.addView(tv, new LayoutParams(-1, -1));
            }
            f.setForeground(new FocusRing());
            return new RecyclerView.ViewHolder(f) {
            };
        }

        private int inner() {
            return rv.getWidth() > 0 ? rv.getWidth() - rv.getPaddingLeft() - rv.getPaddingRight()
                    : Math.round(1920 * s) - px(64) - px(56) - px(4) - (tvFirst ? 0 : px(16));
        }

        /** Tile height with n per row (the row's height follows it). */
        int tileH(int n) {
            return tileW(inner(), px(20), n) * 9 / 16;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            FrameLayout f = (FrameLayout) h.itemView;
            int inner = inner();
            int gap = px(20);
            int w = tileW(inner, gap, cols);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(tvFirst ? w : (inner / cols), w * 9 / 16);
            if (tvFirst) lp.rightMargin = pos == getItemCount() - 1 ? 0 : gap;
            else {
                lp.width = w;
                lp.leftMargin = gap / 2;
                lp.rightMargin = gap / 2;
                lp.bottomMargin = gap;
            }
            f.setLayoutParams(lp);
            boolean tvTile = tvOn() && pos == 0;
            final int app = appIndex(pos);
            if (tvTile) {
                LinearLayout col = (LinearLayout) f.getChildAt(0);
                ((ImageView) col.getChildAt(0)).setColorFilter(t.accent);
                ((TextView) col.getChildAt(1)).setTextColor(t.fgBright);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(t.bgLight);
                f.setBackground(bg);
            } else {
                ImageView iv = (ImageView) f.getChildAt(0);
                TextView tv = (TextView) f.getChildAt(1);
                Drawable banner = cfg.textTiles ? null : apps.bannerAt(app);
                iv.setImageDrawable(banner);
                iv.setVisibility(banner != null ? VISIBLE : GONE);
                iv.setPadding(px(2), px(2), px(2), px(2));
                tv.setText(apps.labelAt(app));
                tv.setVisibility(banner != null ? GONE : VISIBLE);
                tv.setTextColor(cfg.textTiles ? t.fg : t.fgBright);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(cfg.textTiles ? t.bgLight : Theme.withAlpha(t.bgLight, Math.max(0.30f, opacity(cfg) + noBlur() - 0.15f)));
                f.setBackground(bg);
            }
            f.setAlpha(movingApp >= 0 && app == movingApp && (movingInGrid != tvFirst) ? 0.85f : 1f);
            styleTile(f, f.isFocused(), false);
            f.setOnFocusChangeListener((v, has) -> {
                styleTile((FrameLayout) v, has, true);
                if (has) {
                    int p = rv.getChildAdapterPosition(v);
                    if (p < 0) return;
                    if (tvFirst) {
                        lastApp = p;
                        if (movingApp < 0) {
                            appName.setText(tvOn() && p == 0 ? "TV" : apps.labelAt(appIndex(p)));
                            updateRowHeader();
                        }
                    } else if (movingApp < 0) gridName.setText(apps.labelAt(p));
                }
            });
            f.setOnClickListener(v -> {
                int p = rv.getChildAdapterPosition(v);
                if (p < 0) return;
                if (tvOn() && p == 0) host.openTv();
                else host.launchApp(appIndex(p));
            });
            f.setOnLongClickListener(v -> {
                int p = rv.getChildAdapterPosition(v);
                if (p < 0) return true;
                if (tvOn() && p == 0) host.openWalker();   // the TV tile cannot be moved or hidden
                else host.tileMenu(appIndex(p), v);
                return true;
            });
            if (tvFirst && pos == lastApp && appName.getText().length() == 0) appName.setText(tvTile ? "TV" : apps.labelAt(app));
        }
    }

    private void styleTile(FrameLayout f, boolean focused, boolean animate) {
        Drawable d = f.getForeground();
        if (d instanceof FocusRing) ((FocusRing) d).set(focused);
        View label = f.getChildAt(f.getChildCount() - 1);
        if (label instanceof TextView && cfg != null && cfg.textTiles) {
            ((TextView) label).setTypeface(focused ? tfBold : tf);
            ((TextView) label).setTextColor(focused ? t.fgBright : t.fg);
        }
        float sc = focused ? 1.05f : 1f;
        f.setZ(focused ? 2f : 0f);
        if (animate) f.animate().scaleX(sc).scaleY(sc).setDuration(110).start();
        else {
            f.setScaleX(sc);
            f.setScaleY(sc);
        }
    }

    /** Tile border: unfocused 2 px muted; focused 2 px dark outer outline + 4 px accent inside it. */
    private final class FocusRing extends Drawable {
        private final Paint p = new Paint();
        private boolean on;

        FocusRing() {
            p.setStyle(Paint.Style.STROKE);
        }

        void set(boolean focused) {
            if (on != focused) {
                on = focused;
                invalidateSelf();
            }
        }

        @Override
        public void draw(@NonNull android.graphics.Canvas c) {
            if (t == null) return;
            android.graphics.Rect b = getBounds();
            if (!on) {
                float w = px(2);
                p.setStrokeWidth(w);
                p.setColor(t.muted);
                c.drawRect(b.left + w / 2, b.top + w / 2, b.right - w / 2, b.bottom - w / 2, p);
                return;
            }
            float o = px(2), a = px(4);
            p.setStrokeWidth(o);
            p.setColor(0xE6000000);
            c.drawRect(b.left + o / 2, b.top + o / 2, b.right - o / 2, b.bottom - o / 2, p);
            p.setStrokeWidth(a);
            p.setColor(t.accent);
            c.drawRect(b.left + o + a / 2, b.top + o + a / 2, b.right - o - a / 2, b.bottom - o - a / 2, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Progress bar: track = selection, fill = given colour, square ends. */
    static final class BarView extends View {
        private final Paint tr = new Paint(), fl = new Paint();
        private final double f;

        BarView(Context c, int track, int fill, double f) {
            super(c);
            tr.setColor(track);
            fl.setColor(fill);
            this.f = Math.max(0, Math.min(1, f));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), tr);
            c.drawRect(0, 0, (float) (getWidth() * f), getHeight(), fl);
        }
    }
}
