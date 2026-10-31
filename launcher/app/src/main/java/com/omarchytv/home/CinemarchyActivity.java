package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cinemarchy — the Omarchy Cinema Hub (v2.13).
 *
 * <ul>
 *   <li>Renders the clean 10-row {@code CINEMARCHY} half-block wordmark ({@code res/raw/cinemarchy_logo.txt})
 *       with {@code CINE} in the active theme's accent gradient and {@code MARCHY} verbatim from {@code omarchy_logo.txt},
 *       animated with both the signature Omarchy decrypt reveal and a continuous cinema-projector shimmer wave.</li>
 *   <li>3-Pane TUI / Waybar Tiling Layout:
 *     <ol>
 *       <li><b>Pane [1] sources</b>: {@code @dhh37 · Omarchy Official}, {@code omarchy-link://pc-media},
 *           {@code mpv://handoff}, {@code stream://services}, {@code livetv://tuner-iptv}, {@code snippets://4k-loops}.</li>
 *       <li><b>Pane [2] library</b>: Keyboard/D-pad driven list with progress bars ({@code ███░░ 42%}) and badges.</li>
 *       <li><b>Pane [3] inspector · nfo · cli</b>: Shows item metadata, <b>Tutorial Mode</b> chapter-synced CLI commands
 *           (for {@code @dhh37} Omarchy videos — linked to official YouTube, zero bundled video files), and
 *           {@code omarchy-tv} / {@code mpv} handoff controls.</li>
 *     </ol>
 *   </li>
 *   <li>Full Vim navigation ({@code h j k l g G p s H}) and inline {@code :} / {@code /} Command Palette
 *       ({@code :play}, {@code :handoff}, {@code :pause}, {@code :queue}, {@code :theme <name>},
 *       {@code :ambilight cinema}, {@code :saver}).</li>
 * </ul>
 */
public final class CinemarchyActivity extends Activity {
    /** Cinemarchy has its own version, independent of the OmarchyTV launcher version. */
    public static final String CINEMARCHY_VERSION = "0.1.0-alpha";


    private static final int SRC_DHH = 0;
    private static final int SRC_PC_MEDIA = 1;
    private static final int SRC_MPV_HANDOFF = 2;
    private static final int SRC_STREAMING = 3;
    private static final int SRC_LIVETV = 4;
    private static final int SRC_SNIPPETS = 5;
    private static final int SRC_IPTV = 6;
    private static final int SRC_QUEUE = 7;

    private static final String[] RAW_NAMES = {
            "@dhh37 · Omarchy",
            "omarchy-link://pc",
            "mpv://handoff",
            "stream://services",
            "livetv://tuner-iptv",
            "snippets://4k-loops",
            "iptv://playlists",
            "queue://up-next"
    };

    private static final String[] REEL_FRAMES = {"◐", "◓", "◑", "◒"};

    private int activeSource = SRC_DHH;
    private final List<TextView> sourceButtons = new ArrayList<>();
    private final List<MediaEntry> currentItems = new ArrayList<>();
    private MediaEntry selectedEntry;
    private LinearLayout listContainer;
    private TextView statusTopRight, detailBadge, detailTitle, detailMeta, detailDesc, tutorialPane, cliNote;
    private EditText cmdInput;
    private WordmarkView wordmark;
    private Typeface mono, monoBold;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int reelIdx = 0;

    private final Runnable reelTicker = new Runnable() {
        @Override
        public void run() {
            reelIdx = (reelIdx + 1) % REEL_FRAMES.length;
            updateTopStatus();
            handler.postDelayed(this, 260);
        }
    };

    static final class MediaEntry {
        final String title, subtitle, category, badge, url, pkg, kind, progressBar, tutorialCli;
        final boolean live;
        final double resumeSec;
        String subs = "";   // v2.13: optional .srt/.vtt for queued items

        MediaEntry(String title, String subtitle, String category, String badge, String url, String pkg,
                   String kind, boolean live, String progressBar, String tutorialCli, double resumeSec) {
            this.title = title;
            this.subtitle = subtitle;
            this.category = category;
            this.badge = badge;
            this.url = url;
            this.pkg = pkg;
            this.kind = kind;
            this.live = live;
            this.progressBar = progressBar != null ? progressBar : "";
            this.tutorialCli = tutorialCli != null ? tutorialCli : "";
            this.resumeSec = resumeSec;
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme t = Theme.cur();
        try {
            mono = CastUi.font(this);
            monoBold = Typeface.create(mono, Typeface.BOLD);
        } catch (Exception e) {
            mono = Typeface.MONOSPACE;
            monoBold = Typeface.DEFAULT_BOLD;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000 | (t.bgDarker & 0xFFFFFF));

        WallpaperView wp = new WallpaperView(this, null);
        wp.setAlpha(0.24f);
        root.addView(wp, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(28), padV = dp(18);
        main.setPadding(padH, padV, padH, padV);

        // 1. Top Header: Clean Half-Block CINEMARCHY Wordmark (with continuous projector shimmer) + 35mm Reel Status Bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout logoBox = new LinearLayout(this);
        logoBox.setOrientation(LinearLayout.VERTICAL);
        wordmark = new WordmarkView(this, "cinemarchy", 320f);
        wordmark.setContinuousShimmer(true);
        logoBox.addView(wordmark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView alpha = label("[ALPHA] Cinemarchy " + CINEMARCHY_VERSION + " (preview)", 11, t.accent, true);
        logoBox.addView(alpha);
        TextView subPrompt = label("cinemarchy@omarchytv:~$ ./cinemarchy --tui --handoff mpv --theme " + t.name, 11, t.fgDim, false);
        subPrompt.setPadding(0, dp(3), 0, 0);
        logoBox.addView(subPrompt);
        header.addView(logoBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusTopRight = label("", 11, t.cyan, true);
        updateTopStatus();
        header.addView(statusTopRight);
        main.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. 3-Pane Tiling Body: [1] sources (left) | [2] library (center) | [3] inspector · nfo · cli (right)
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(10);

        // Pane [1]: Sources Sidebar
        LinearLayout sourcesPane = makePane(t, "─ [1] sources ─");
        sourceButtons.clear();
        String[] rawNames = RAW_NAMES;
        for (int i = 0; i < rawNames.length; i++) {
            final int srcIdx = i;
            TextView sb = label(rawNames[i], 13, t.fg, true);
            sb.setFocusable(true);
            sb.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(6);
            sb.setOnClickListener(v -> selectSource(srcIdx, true));
            sb.setOnFocusChangeListener((v, hasFocus) -> {
                styleSourceButton((TextView) v, srcIdx == activeSource, hasFocus, rawNames[srcIdx]);
                if (hasFocus && srcIdx != activeSource) selectSource(srcIdx, false);
            });
            sourceButtons.add(sb);
            sourcesPane.addView(sb, slp);
        }
        TextView keybindHint = label(
                "\n─ keybindings ─\n"
                        + "[1-8] switch source\n"
                        + "[j/k] navigate list\n"
                        + "[g/G] first / last\n"
                        + "[p/OK] play / open\n"
                        + "[H] handoff -> PC mpv\n"
                        + "[Q] queue · [F] ★ fav\n"
                        + "[s] aquarium saver\n"
                        + "[: or /] cmd palette",
                11, t.fgDim, false);
        sourcesPane.addView(keybindHint);

        LinearLayout.LayoutParams pane1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.72f);
        pane1Lp.rightMargin = dp(12);
        body.addView(sourcesPane, pane1Lp);

        // Pane [2]: Center Media Library / Stream List
        LinearLayout centerPane = makePane(t, "─ [2] library · streams · progress ─");
        ScrollView listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(6), 0, dp(6));
        listScroll.addView(listContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        centerPane.addView(listScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout.LayoutParams pane2Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.38f);
        pane2Lp.rightMargin = dp(12);
        body.addView(centerPane, pane2Lp);

        // Pane [3]: Right Inspector · NFO · Tutorial Mode CLI
        LinearLayout inspector = makePane(t, "─ [3] inspector · nfo · tutorial cli ─");

        detailBadge = label("[ READY ]", 12, t.green, true);
        detailBadge.setPadding(0, dp(6), 0, 0);
        inspector.addView(detailBadge);

        detailTitle = label("Select an item", 17, t.fgBright, true);
        detailTitle.setPadding(0, dp(6), 0, 0);
        inspector.addView(detailTitle);

        detailMeta = label("", 12, t.cyan, false);
        detailMeta.setPadding(0, dp(4), 0, 0);
        inspector.addView(detailMeta);

        detailDesc = label("", 12, t.fg, false);
        detailDesc.setPadding(0, dp(8), 0, 0);
        inspector.addView(detailDesc);

        tutorialPane = label("", 11, t.yellow, false);
        tutorialPane.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable tutBg = new GradientDrawable();
        tutBg.setColor(Theme.withAlpha(t.bgDarker, 0.92f));
        tutBg.setStroke(dp(1), t.inactiveBorder);
        tutorialPane.setBackground(tutBg);
        LinearLayout.LayoutParams tutLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tutLp.topMargin = dp(10);
        inspector.addView(tutorialPane, tutLp);

        View spacer = new View(this);
        inspector.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cliNote = label(
                "─ omarchy-tv cli & mpv handoff ─\n"
                        + "$ omarchy-tv handoff              # PC mpv -> TV\n"
                        + "$ omarchy-tv handoff --back       # TV -> PC mpv\n"
                        + "$ omarchy-tv play <file|url>",
                11, t.fgDim, false);
        inspector.addView(cliNote);

        LinearLayout.LayoutParams pane3Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f);
        body.addView(inspector, pane3Lp);
        main.addView(body, bodyLp);

        // 3. Bottom Vim Keys & Command Palette Bar (:play, :handoff, :theme, :saver)
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable botBg = new GradientDrawable();
        botBg.setColor(Theme.withAlpha(t.bg, 0.90f));
        botBg.setStroke(dp(1), t.inactiveBorder);
        bottomBar.setBackground(botBg);
        LinearLayout.LayoutParams botLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        botLp.topMargin = dp(8);

        TextView vimTag = label("NORMAL", 11, t.bgDarker, true);
        vimTag.setBackgroundColor(t.accent);
        vimTag.setPadding(dp(8), dp(2), dp(8), dp(2));
        bottomBar.addView(vimTag);

        TextView vimHints = label("  hjkl/arrows move · p play · H handoff->PC · s saver · : command  ", 11, t.fgDim, false);
        bottomBar.addView(vimHints);

        cmdInput = new EditText(this);
        cmdInput.setSingleLine(true);
        cmdInput.setHint(":play | :pause | :queue | :handoff | :iptv <url> | :tutorial | :saver | :theme <name>");
        cmdInput.setHintTextColor(t.fgDim);
        cmdInput.setTextColor(t.fgBright);
        cmdInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cmdInput.setTypeface(mono);
        cmdInput.setBackground(null);
        cmdInput.setPadding(dp(8), dp(2), dp(8), dp(2));
        cmdInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        cmdInput.setOnEditorActionListener((v, actionId, event) -> {
            String cmd = cmdInput.getText().toString().trim();
            if (!cmd.isEmpty()) {
                executePaletteCommand(cmd);
                cmdInput.setText("");
            }
            return true;
        });
        bottomBar.addView(cmdInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        main.addView(bottomBar, botLp);
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        AmbilightController.cinemaDim(this);
        selectSource(SRC_DHH, false);
        wordmark.reveal();
        if (listContainer.getChildCount() > 0) {
            listContainer.getChildAt(0).requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(reelTicker);
        handler.post(reelTicker);
        loadDhh(false);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(reelTicker);
        handler.removeCallbacks(dhhTicker);
        markDhhSeen();
        super.onPause();
    }

    private void updateTopStatus() {
        if (statusTopRight == null) return;
        String reel = REEL_FRAMES[reelIdx % REEL_FRAMES.length];
        statusTopRight.setText(reel + " 35mm  [ mpv handoff: Super+Shift+Alt+T · ambilight: cinema · 8s saver ]");
    }

    private LinearLayout makePane(Theme t, String headerTitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.withAlpha(t.bg, 0.86f));
        bg.setStroke(dp(2), t.activeBorder);
        box.setBackground(bg);
        TextView hdr = label(headerTitle, 12, t.accent, true);
        box.addView(hdr);
        return box;
    }

    private void selectSource(int src, boolean focusFirstRow) {
        activeSource = src;
        String[] rawNames = RAW_NAMES;
        for (int i = 0; i < sourceButtons.size(); i++) {
            TextView b = sourceButtons.get(i);
            styleSourceButton(b, i == activeSource, b.hasFocus(), rawNames[i]);
        }
        List<MediaEntry> items = entriesForSource(src);
        currentItems.clear();
        currentItems.addAll(items);
        populateList(items);
        if (focusFirstRow && listContainer.getChildCount() > 0) {
            listContainer.getChildAt(0).requestFocus();
        }
    }

    private void styleSourceButton(TextView b, boolean selected, boolean focused, String rawName) {
        Theme t = Theme.cur();
        b.setText((selected ? "▸ " : "  ") + rawName);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(focused ? t.selection : (selected ? Theme.withAlpha(t.bgLight, 0.92f) : Theme.withAlpha(t.bgDarker, 0.65f)));
        gd.setStroke(dp(focused ? 2 : 1), focused ? t.activeBorder : (selected ? t.accent : t.inactiveBorder));
        b.setBackground(gd);
        b.setTextColor(focused || selected ? t.fgBright : t.fgDim);
    }

    private void populateList(List<MediaEntry> items) {
        listContainer.removeAllViews();
        Theme t = Theme.cur();
        for (int i = 0; i < items.size(); i++) {
            final MediaEntry item = items.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            int pH = dp(12), pV = dp(9);
            row.setPadding(pH, pV, pH, pV);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(6);

            TextView badge = label(item.badge, 11, t.accent, true);
            badge.setPadding(0, 0, dp(10), 0);
            row.addView(badge);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            TextView title = label(item.title, 14, t.fgBright, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = label(item.subtitle, 11, t.fgDim, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(title);
            textCol.addView(sub);
            row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (!item.progressBar.isEmpty()) {
                TextView prog = label(item.progressBar + "  ", 11, t.green, false);
                row.addView(prog);
            }

            TextView actionLbl = label("▶", 13, t.cyan, true);
            row.addView(actionLbl);

            styleRow(row, false);
            row.setOnFocusChangeListener((v, hasFocus) -> {
                styleRow((LinearLayout) v, hasFocus);
                if (hasFocus) updateInspector(item);
            });
            row.setOnClickListener(v -> activateEntry(item));
            listContainer.addView(row, rlp);
            if (i == 0) updateInspector(item);
        }
    }

    private void styleRow(LinearLayout row, boolean focused) {
        Theme t = Theme.cur();
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(focused ? t.selection : Theme.withAlpha(t.bgDarker, 0.75f));
        gd.setStroke(dp(focused ? 2 : 1), focused ? t.activeBorder : t.inactiveBorder);
        row.setBackground(gd);
    }

    private void updateInspector(MediaEntry e) {
        selectedEntry = e;
        detailBadge.setText(e.badge + "  ·  " + e.category + (e.progressBar.isEmpty() ? "" : "  ·  " + e.progressBar));
        detailTitle.setText(e.title);
        detailMeta.setText(e.subtitle);
        String target = e.pkg != null && !e.pkg.isEmpty()
                ? ("Native TV pkg: " + e.pkg + " (falls back to Omarchy WebApp)")
                : (e.url != null && !e.url.isEmpty() ? ("Stream / URL: " + e.url) : "System TV Input Framework");
        detailDesc.setText(target + "\n[OK/p] Play  ·  [H] Handoff to PC mpv  ·  [s] Aquarium Saver");
        tutorialPane.setVisibility(showTutorial ? View.VISIBLE : View.GONE);
        if (!e.tutorialCli.isEmpty()) {
            tutorialPane.setText(e.tutorialCli);
        } else {
            tutorialPane.setText("─ nfo / playback telemetry ─\n"
                    + "• codec: H.264 / HEVC / HLS hardware decode (Media3 ExoPlayer)\n"
                    + "• ambilight: Follow-Video + Hue / Home Assistant / WLED sync\n"
                    + "• burn-in guard: 8s pause -> Omarchy TUI / ST3 Aquarium");
        }
    }

    // ---- v2.13: @dhh37 auto-updating channel (YouTube feed, links/metadata only) ------------------
    private volatile List<DhhFeed.Video> dhhFeed = new ArrayList<>();
    private volatile boolean dhhLoading;
    private volatile String dhhError = "";
    private volatile long dhhFetchedAt;
    private long dhhLastSeen = -1;
    private boolean dhhShown;
    private final Runnable dhhTicker = () -> loadDhh(false);

    private java.io.File dhhCache() {
        return new java.io.File(getCacheDir(), "dhh37-feed.xml");
    }

    private void loadDhh(boolean force) {
        if (dhhLastSeen < 0) {
            JSONObject c = LauncherConfig.readJson(this).optJSONObject("cinemarchy");
            dhhLastSeen = c != null ? c.optLong("dhhLastSeen", 0) : 0;
        }
        handler.removeCallbacks(dhhTicker);
        handler.postDelayed(dhhTicker, DhhFeed.TTL_MS);   // every 6 h while Cinemarchy stays open
        if (dhhLoading) return;
        dhhLoading = true;
        final java.io.File cache = dhhCache();
        com.omarchytv.home.lights.Lights.run(() -> {
            List<DhhFeed.Video> got = new ArrayList<>();
            String err = "";
            long at = cache.isFile() ? cache.lastModified() : 0;
            boolean fresh = at > 0 && System.currentTimeMillis() - at < DhhFeed.TTL_MS;
            if (force || !fresh) {
                try {
                    byte[] raw = httpGet(DhhFeed.FEED_URL, 2 * 1024 * 1024);
                    got = DhhFeed.parse(raw);
                    if (got.isEmpty()) {
                        err = "feed unreadable";
                    } else {
                        java.io.File tmp = new java.io.File(cache.getPath() + ".tmp");
                        ApiCompat.write(tmp, raw);
                        if (!tmp.renameTo(cache)) tmp.delete();
                        at = System.currentTimeMillis();
                    }
                } catch (Throwable t) {
                    err = t instanceof java.io.FileNotFoundException ? "feed not found" : "no network";
                }
            }
            if (got.isEmpty() && cache.isFile()) {
                try {
                    got = DhhFeed.parse(ApiCompat.readAllBytes(cache));
                } catch (Throwable ignored) {
                }
            }
            final List<DhhFeed.Video> result = got;
            final String error = got.isEmpty() ? err : "";
            final long fetchedAt = at;
            handler.post(() -> {
                dhhFeed = result;
                dhhError = error;
                dhhFetchedAt = fetchedAt;
                dhhLoading = false;
                if (!isFinishing() && activeSource == SRC_DHH) selectSource(SRC_DHH, false);
            });
        });
    }

    private static byte[] httpGet(String url, int cap) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", "OmarchyTV (Android TV launcher)");
        try (java.io.InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > cap) throw new java.io.IOException("too large");
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /** Once the user has looked at the @dhh37 list, its newest upload stops being NEW next time. */
    private void markDhhSeen() {
        if (!dhhShown) return;
        long newest = DhhFeed.newest(dhhFeed);
        if (newest <= dhhLastSeen) return;
        LauncherConfig.update(this, j -> {
            JSONObject c = j.optJSONObject("cinemarchy");
            if (c == null) j.put("cinemarchy", c = new JSONObject());
            c.put("dhhLastSeen", newest);
        });
    }

    private static String ago(long at, long now) {
        if (at <= 0) return "just now";
        long m = Math.max(0, (now - at) / 60_000L);
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : (m / 60) + " h ago";
    }

    private boolean embedPlayerOn() {
        JSONObject c = LauncherConfig.readJson(this).optJSONObject("cinemarchy");
        return c == null || c.optBoolean("embedPlayer", true);   // on by default (works on Sony); an explicit false stays off
    }

    static void setEmbedPlayer(Context ctx, boolean on) {
        LauncherConfig.update(ctx, j -> {
            JSONObject c = j.optJSONObject("cinemarchy");
            if (c == null) j.put("cinemarchy", c = new JSONObject());
            c.put("embedPlayer", on);
        });
    }

    /** A @dhh37 pick: the official embed when turned on, else the YouTube app on that exact video. */
    private void openYoutube(String id, String title) {
        if (!DhhFeed.validId(id)) return;
        if (embedPlayerOn()) {
            startActivity(new Intent(this, YoutubeEmbedActivity.class)
                    .putExtra(YoutubeEmbedActivity.EXTRA_ID, id)
                    .putExtra(YoutubeEmbedActivity.EXTRA_TITLE, title));
            return;
        }
        YoutubeEmbedActivity.openInYoutubeApp(this, id, title);
    }

    // ---- v2.13: Tutorial toggle, Up-Next queue, real :pause, and user IPTV hub -------------------
    private boolean showTutorial = true;
    private String iptvGroup = "ALL";
    private volatile List<com.omarchytv.home.iptv.M3uParser.Channel> iptvChannels = new ArrayList<>();
    private volatile boolean iptvLoading;

    private void addToQueue(MediaEntry e) {
        if (e == null || e.url == null || e.url.isEmpty() || e.kind.startsWith("iptv_") || e.kind.startsWith("queue_")) {
            Toast.makeText(this, "Select a playable stream first", Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherConfig.update(this, j -> {
            JSONObject c = j.optJSONObject("cinemarchy");
            if (c == null) j.put("cinemarchy", c = new JSONObject());
            JSONArray q = c.optJSONArray("queue");
            if (q == null) c.put("queue", q = new JSONArray());
            if (q.length() >= 100) q.remove(0);
            q.put(new JSONObject().put("title", e.title).put("subtitle", e.subtitle)
                    .put("url", e.url).put("live", e.live).put("pos", e.resumeSec));
        });
        Toast.makeText(this, "Queued: " + e.title + "  ([8] queue://up-next)", Toast.LENGTH_SHORT).show();
    }

    private List<MediaEntry> queueEntries(JSONObject cine) {
        List<MediaEntry> out = new ArrayList<>();
        JSONArray q = cine == null ? null : cine.optJSONArray("queue");
        if (q == null || q.length() == 0) {
            out.add(new MediaEntry("Queue is empty — press Q or :queue on any stream",
                    "Items queued from any source play back-to-back in CastPlayer",
                    "Up-Next Queue", "[EMPTY]", "", null, "noop", false, "", "", 0));
            return out;
        }
        for (int i = 0; i < q.length(); i++) {
            JSONObject o = q.optJSONObject(i);
            if (o == null) continue;
            MediaEntry qe = new MediaEntry(o.optString("title", "Stream"),
                    o.optString("subtitle", o.optString("url")) + (o.optString("subs", "").isEmpty() ? "" : " · CC"),
                    "Up-Next #" + (i + 1), "[#" + (i + 1) + "]", o.optString("url", ""), null, "queue_item",
                    o.optBoolean("live"), "", "# [OK] play queue from here · [CH+/CH-] skip", o.optDouble("pos", 0));
            qe.subs = o.optString("subs", "");
            out.add(qe);
        }
        out.add(new MediaEntry("Clear queue (" + q.length() + " items)", "Remove all queued streams",
                "Up-Next Queue", "[CLEAR]", "", null, "queue_clear", false, "", "", 0));
        return out;
    }

    private List<MediaEntry> iptvEntries(JSONObject root) {
        List<MediaEntry> out = new ArrayList<>();
        List<com.omarchytv.home.iptv.IptvStore.Playlist> pls = com.omarchytv.home.iptv.IptvStore.playlists(root);
        String epgUrl = com.omarchytv.home.iptv.IptvStore.epgUrl(root);
        out.add(new MediaEntry("+ Add M3U / M3U8 playlist URL…",
                pls.isEmpty() ? "User-supplied playlists only · or push via: omarchy-tv iptv add <url>" : pls.size() + " playlist(s) configured",
                "IPTV Setup", "[+M3U]", "", null, "iptv_add", false, "",
                "$ omarchy-tv iptv add https://example.com/channels.m3u8 --name \"Home\"\n$ omarchy-tv iptv list", 0));
        out.add(new MediaEntry("XMLTV EPG URL: " + (epgUrl.isEmpty() ? "(optional, not set)" : epgUrl),
                "Shows Now / Next programme in the right pane (.xml or .xml.gz)",
                "IPTV EPG", "[EPG]", epgUrl, null, "iptv_epg", false, "",
                "$ omarchy-tv iptv add <m3u-url> --epg https://example.com/guide.xml.gz", 0));
        if (pls.isEmpty()) return out;
        if (iptvChannels.isEmpty() && !iptvLoading) reloadIptv(root);
        List<com.omarchytv.home.iptv.M3uParser.Channel> all = iptvChannels;
        java.util.Set<String> favs = com.omarchytv.home.iptv.IptvStore.favorites(this);
        List<String> groups = com.omarchytv.home.iptv.M3uParser.groups(all);
        out.add(new MediaEntry("Group filter: " + iptvGroup + (iptvLoading ? " (loading…)" : " (" + all.size() + " ch)"),
                "Press OK to cycle: ALL -> ★ FAV -> " + TextUtils.join(" -> ", groups),
                "IPTV Groups", "[GRP]", "", null, "iptv_group", false, "", "", 0));
        String last = com.omarchytv.home.iptv.IptvStore.lastChannel(this);
        if (!last.isEmpty()) {
            for (com.omarchytv.home.iptv.M3uParser.Channel c : all) {
                if (last.equals(c.url)) {
                    out.add(channelEntry(c, favs.contains(c.url), "↺ LAST"));
                    break;
                }
            }
        }
        for (com.omarchytv.home.iptv.M3uParser.Channel c : all) {
            boolean fav = favs.contains(c.url);
            String g = c.group.isEmpty() ? "Other" : c.group;
            if ("★ FAV".equals(iptvGroup) && !fav) continue;
            if (!"ALL".equals(iptvGroup) && !"★ FAV".equals(iptvGroup) && !iptvGroup.equals(g)) continue;
            out.add(channelEntry(c, fav, "● LIVE"));
        }
        return out;
    }

    private MediaEntry channelEntry(com.omarchytv.home.iptv.M3uParser.Channel c, boolean fav, String tag) {
        String g = c.group.isEmpty() ? "Live" : c.group;
        String epgLine = epgSummary(c);
        String sub = (fav ? "★ " : "") + g + (epgLine.isEmpty() ? "" : " · " + epgLine);
        String cli = (epgLine.isEmpty() ? "" : "─ EPG now / next ─\n" + epgLine.replace(" · Next: ", "\nNext: ") + "\n\n")
                + "# [OK] Watch · [F] ★ Favorite · [CH+/CH- / PgUp/PgDn] Zap · [LAST] Recall";
        return new MediaEntry(c.name, sub, g, fav ? "[★]" : "[TV]", c.url, null, "iptv_channel", true, tag, cli, 0);
    }

    private String epgSummary(com.omarchytv.home.iptv.M3uParser.Channel c) {
        java.util.Map<String, List<com.omarchytv.home.iptv.Xmltv.Programme>> map = com.omarchytv.home.iptv.IptvStore.epg();
        if (map == null) return "";
        List<com.omarchytv.home.iptv.Xmltv.Programme> l = map.get(c.epgKey());
        if (l == null && !c.name.equals(c.epgKey())) l = map.get(c.name);
        com.omarchytv.home.iptv.Xmltv.Programme[] nn = com.omarchytv.home.iptv.Xmltv.nowNext(l, System.currentTimeMillis());
        StringBuilder sb = new StringBuilder();
        if (nn[0] != null) sb.append("Now: ").append(nn[0].title);
        if (nn[1] != null) sb.append(sb.length() > 0 ? " · Next: " : "Next: ").append(nn[1].title);
        return sb.toString();
    }

    private void reloadIptv(JSONObject root) {
        List<com.omarchytv.home.iptv.IptvStore.Playlist> pls = com.omarchytv.home.iptv.IptvStore.playlists(root);
        String epgUrl = com.omarchytv.home.iptv.IptvStore.epgUrl(root);
        iptvLoading = true;
        com.omarchytv.home.lights.Lights.run(() -> {
            List<com.omarchytv.home.iptv.M3uParser.Channel> merged = new ArrayList<>();
            for (com.omarchytv.home.iptv.IptvStore.Playlist p : pls) {
                try {
                    byte[] raw = com.omarchytv.home.iptv.IptvStore.fetch(this, p.url,
                            "m3u-" + com.omarchytv.home.iptv.IptvStore.key(p.url), 15 * 60_000L);
                    merged.addAll(com.omarchytv.home.iptv.M3uParser.parse(com.omarchytv.home.iptv.Xmltv.decode(raw)));
                } catch (Throwable ignored) {
                }
            }
            com.omarchytv.home.iptv.IptvStore.loadEpg(this, epgUrl);
            iptvChannels = merged;
            iptvLoading = false;
            handler.post(() -> {
                if (activeSource == SRC_IPTV) selectSource(SRC_IPTV, false);
            });
        });
    }

    private void promptIptvUrl(boolean epg) {
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint(epg ? "https://example.com/epg.xml.gz" : "https://example.com/playlist.m3u8");
        new android.app.AlertDialog.Builder(this)
                .setTitle(epg ? "XMLTV EPG URL" : "Add M3U / M3U8 playlist URL")
                .setMessage(epg ? "Optional XMLTV (.xml or .xml.gz) for Now/Next." : "Only streams you have the legal right to watch.")
                .setView(in)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String u = in.getText().toString().trim();
                    if (!u.isEmpty() && !com.omarchytv.home.iptv.IptvStore.validUrl(u)) {
                        Toast.makeText(this, R.string.amb_bad_ip, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    LauncherConfig.update(this, j -> {
                        if (epg) {
                            JSONObject c = j.optJSONObject("cinemarchy");
                            if (c == null) j.put("cinemarchy", c = new JSONObject());
                            JSONObject ip = c.optJSONObject("iptv");
                            if (ip == null) c.put("iptv", ip = new JSONObject());
                            ip.put("epg", u);
                        } else if (!u.isEmpty()) {
                            String host = android.net.Uri.parse(u).getHost();
                            com.omarchytv.home.iptv.IptvStore.addPlaylist(j, host != null ? host : "Playlist", u);
                        }
                    });
                    iptvChannels = new ArrayList<>();
                    reloadIptv(LauncherConfig.readJson(this));
                    selectSource(SRC_IPTV, false);
                }).show();
    }

    private void startListPlayback(MediaEntry e, String mode) {
        List<String> urls = new ArrayList<>(), titles = new ArrayList<>(), subsList = new ArrayList<>();
        int idx = 0;
        for (MediaEntry m : currentItems) {
            if (e.kind.equals(m.kind) && m.url != null && !m.url.isEmpty()) {
                if (m == e || e.url.equals(m.url)) idx = urls.size();
                urls.add(m.url);
                titles.add(m.title);
                subsList.add(m.subs == null ? "" : m.subs);
            }
        }
        if (urls.isEmpty()) return;
        if ("iptv".equals(mode)) com.omarchytv.home.iptv.IptvStore.tuned(this, e.url);
        Intent i = new Intent(this, CastPlayerActivity.class)
                .putExtra(CastRouter.EXTRA_ID, "cinemarchy-" + System.currentTimeMillis())
                .putExtra(CastRouter.EXTRA_KIND, "video")
                .putExtra(CastRouter.EXTRA_URL, e.url)
                .putExtra(CastRouter.EXTRA_TITLE, e.title)
                .putExtra(CastRouter.EXTRA_FROM, "Cinemarchy")
                .putExtra(CastRouter.EXTRA_POS, e.resumeSec)
                .putExtra(CastRouter.EXTRA_LIVE, e.live)
                .putExtra(CastPlayerActivity.EXTRA_LIST_URLS, urls.toArray(new String[0]))
                .putExtra(CastPlayerActivity.EXTRA_LIST_TITLES, titles.toArray(new String[0]))
                .putExtra(CastPlayerActivity.EXTRA_LIST_INDEX, idx)
                .putExtra(CastPlayerActivity.EXTRA_LIST_SUBS, subsList.toArray(new String[0]))
                .putExtra(CastPlayerActivity.EXTRA_LIST_MODE, mode);
        if (e.subs != null && !e.subs.isEmpty()) i.putExtra(CastRouter.EXTRA_SUB, e.subs);
        startActivity(i);
    }

    private void activateEntry(MediaEntry e) {
        if (e == null || "noop".equals(e.kind)) return;
        if ("iptv_add".equals(e.kind)) { promptIptvUrl(false); return; }
        if ("iptv_epg".equals(e.kind)) { promptIptvUrl(true); return; }
        if ("iptv_group".equals(e.kind)) {
            List<String> gs = new ArrayList<>();
            gs.add("ALL");
            gs.add("★ FAV");
            gs.addAll(com.omarchytv.home.iptv.M3uParser.groups(iptvChannels));
            int i = gs.indexOf(iptvGroup);
            iptvGroup = gs.get((i + 1) % gs.size());
            selectSource(SRC_IPTV, true);
            return;
        }
        if ("queue_clear".equals(e.kind)) {
            LauncherConfig.update(this, j -> {
                JSONObject c = j.optJSONObject("cinemarchy");
                if (c != null) c.put("queue", new JSONArray());
            });
            selectSource(SRC_QUEUE, false);
            return;
        }
        // A YouTube page URL (queued @dhh37 pick, PC library, From PC) is not a stream: the video player can't play it.
        String ytId = "youtube".equals(e.kind) ? e.pkg : YoutubeLaunch.idOf(e.url);
        if (!e.kind.startsWith("iptv_") && DhhFeed.validId(ytId)) {
            AmbilightController.restoreFollowVideo(this);
            openYoutube(ytId, e.title);
            return;
        }
        if ("iptv_channel".equals(e.kind)) {
            AmbilightController.restoreFollowVideo(this);
            startListPlayback(e, "iptv");
            return;
        }
        if ("queue_item".equals(e.kind)) {
            AmbilightController.restoreFollowVideo(this);
            startListPlayback(e, "queue");
            return;
        }
        AmbilightController.restoreFollowVideo(this);
        if ("handoff_to_pc".equals(e.kind)) {
            OmarchyLink.handoffToPc(e.title, e.url, e.resumeSec);
            Toast.makeText(this, "Sent mpv handoff to paired Omarchy PC", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("livetv_sys".equals(e.kind)) {
            Intent tvIntent = new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.Channels.CONTENT_URI);
            tvIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(tvIntent);
                return;
            } catch (Exception ignored) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if ("dhh_refresh".equals(e.kind)) { loadDhh(true); return; }
        if ("youtube".equals(e.kind)) { openYoutube(e.pkg, e.title); return; }
        if ("service".equals(e.kind)) {
            if (e.pkg != null && !e.pkg.isEmpty()) {
                PackageManager pm = getPackageManager();
                Intent launch = pm.getLeanbackLaunchIntentForPackage(e.pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(e.pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(launch);
                        return;
                    } catch (Exception ignored) {
                    }
                }
            }
            // A direct HLS/DASH/media URL plays natively; the WebApp is only for web pages.
            if (e.url != null && !e.url.isEmpty() && !StreamUrls.isDirectStream(e.url)) {
                WebAppActivity.openUrl(this, e.title, e.url);
                return;
            }
            if (e.url == null || e.url.isEmpty()) return;
        }
        if (e.url != null && !e.url.isEmpty()) {
            Intent i = new Intent(this, CastPlayerActivity.class)
                    .putExtra(CastRouter.EXTRA_ID, "cinemarchy-" + System.currentTimeMillis())
                    .putExtra(CastRouter.EXTRA_KIND, "audio".equals(e.kind) ? "audio" : "video")
                    .putExtra(CastRouter.EXTRA_URL, e.url)
                    .putExtra(CastRouter.EXTRA_TITLE, e.title)
                    .putExtra(CastRouter.EXTRA_FROM, "Cinemarchy")
                    .putExtra(CastRouter.EXTRA_POS, e.resumeSec)
                    .putExtra(CastRouter.EXTRA_LIVE, e.live);
            startActivity(i);
        }
    }

    private void executePaletteCommand(String raw) {
        String cmd = raw.startsWith(":") || raw.startsWith("/") ? raw.substring(1).trim() : raw.trim();
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (lower.equals("play") || lower.equals("p")) {
            activateEntry(selectedEntry);
        } else if (lower.startsWith("handoff")) {
            if (selectedEntry != null) {
                OmarchyLink.handoffToPc(selectedEntry.title, selectedEntry.url, selectedEntry.resumeSec);
                Toast.makeText(this, "Handoff to PC mpv: " + selectedEntry.title, Toast.LENGTH_SHORT).show();
            }
        } else if (lower.equals("pause") || lower.equals("resume") || lower.equals("toggle")) {
            if (CastRouter.togglePause(this)) {
                Toast.makeText(this, "Toggled TV playback", Toast.LENGTH_SHORT).show();
            } else if (OmarchyLink.mpvControl("pause")) {
                Toast.makeText(this, "Sent mpv pause/resume to paired PC", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No active TV stream or paired PC", Toast.LENGTH_SHORT).show();
            }
        } else if (lower.equals("queue") || lower.startsWith("queue ")) {
            if (lower.equals("queue clear")) {
                LauncherConfig.update(this, j -> {
                    JSONObject c = j.optJSONObject("cinemarchy");
                    if (c != null) c.put("queue", new JSONArray());
                });
                selectSource(SRC_QUEUE, true);
            } else if (lower.equals("queue list") || lower.equals("queue show")) {
                selectSource(SRC_QUEUE, true);
            } else {
                addToQueue(selectedEntry);
            }
        } else if (lower.equals("embed") || lower.equals("embed on") || lower.equals("embed off")) {
            boolean on = lower.equals("embed") ? !embedPlayerOn() : lower.endsWith("on");
            setEmbedPlayer(this, on);
            Toast.makeText(this, on ? "@dhh37: play inside Cinemarchy (official YouTube embed)"
                    : "@dhh37: open in the YouTube app", Toast.LENGTH_SHORT).show();
            if (activeSource == SRC_DHH) selectSource(SRC_DHH, false);
        } else if (lower.equals("tutorial")) {
            showTutorial = !showTutorial;
            if (selectedEntry != null) updateInspector(selectedEntry);
        } else if (lower.startsWith("iptv")) {
            String arg = cmd.length() > 4 ? cmd.substring(4).trim() : "";
            if (com.omarchytv.home.iptv.IptvStore.validUrl(arg)) {
                LauncherConfig.update(this, j -> com.omarchytv.home.iptv.IptvStore.addPlaylist(j, "Playlist", arg));
                iptvChannels = new ArrayList<>();
                reloadIptv(LauncherConfig.readJson(this));
            }
            selectSource(SRC_IPTV, true);
        } else if (lower.equals("saver") || lower.equals("aquarium")) {
            startActivity(new Intent(this, ScreensaverPreviewActivity.class));
        } else if (lower.startsWith("theme ")) {
            String thName = cmd.substring(6).trim();
            if (!thName.isEmpty()) {
                LauncherConfig.update(this, j -> j.put("theme", new JSONObject().put("name", thName).put("source", "tv")));
                recreate();
            }
        } else if (lower.equals("q") || lower.equals("quit")) {
            finish();
        } else {
            // Filter/search current list by query
            for (int i = 0; i < currentItems.size(); i++) {
                if (currentItems.get(i).title.toLowerCase(Locale.ROOT).contains(lower)) {
                    if (i < listContainer.getChildCount()) listContainer.getChildAt(i).requestFocus();
                    break;
                }
            }
        }
    }

    private List<MediaEntry> entriesForSource(int src) {
        List<MediaEntry> out = new ArrayList<>();
        JSONObject root = LauncherConfig.readJson(this);
        JSONObject cine = root.optJSONObject("cinemarchy");

        if (src == SRC_DHH) {
            // v2.13: DHH's real uploads (YouTube feed, cached 6 h) + a curated list of real videos as fallback.
            dhhShown = true;
            long now = System.currentTimeMillis();
            List<DhhFeed.Video> feed = dhhFeed;
            boolean embed = embedPlayerOn();
            String how = embed ? "Plays inside Cinemarchy (official YouTube embed); falls back to the YouTube app"
                    : "Opens in the YouTube app";
            String status = dhhLoading ? "checking the feed…"
                    : (feed.isEmpty() ? (dhhError.isEmpty() ? "curated list" : "offline: curated list (" + dhhError + ")")
                    : "feed updated " + ago(dhhFetchedAt, now));
            out.add(new MediaEntry("@dhh37 · " + (feed.isEmpty() ? "curated" : feed.size() + " from the feed"),
                    status + " · OK refresh · :embed " + (embed ? "off" : "on"), "Channel", "[@dhh37]",
                    DhhFeed.CHANNEL_URL, null, "dhh_refresh", false, "",
                    "─ @dhh37 channel ─\n"
                            + "Feed: youtube.com/feeds/videos.xml?channel_id=" + DhhFeed.CHANNEL_ID + "\n"
                            + "Checked on open and every 6 h, cached; NEW = newer than the last upload you saw.\n"
                            + "Links and metadata only: nothing is downloaded.", 0));
            for (DhhFeed.Video v : DhhFeed.merge(feed, DhhFeed.curated())) {
                boolean fresh = DhhFeed.isNew(v, dhhLastSeen, now);
                String when = v.published > 0 ? new java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                        .format(new java.util.Date(v.published)) : "curated";
                out.add(new MediaEntry(v.title, "@dhh37 · " + when + " · " + how,
                        "Omarchy Official", fresh ? "[NEW]" : "[@dhh37]", v.link, v.id,
                        "youtube", false, "",
                        "─ tutorial mode · chapters ─\n"
                                + (embed ? "Inside Cinemarchy: 1-4 jump to 0/25/50/75 %, ←/→ ±30 s (seekTo).\n"
                                : "Turn on :embed to play here with chapter jumps (seekTo).\n")
                                + "Thumbnail: " + v.thumb + "\n"
                                + "Link: " + v.link, 0));
            }
            out.add(new MediaEntry(
                    "Omarchy Manual (omarchy.org)",
                    "Official Omarchy documentation in the WebApp",
                    "Documentation",
                    "[DOCS]",
                    "https://omarchy.org",
                    null,
                    "service", false, "",
                    "─ tutorial mode · quick reference ─\n"
                            + "$ curl -fsSL https://omarchy.org/install | bash\n"
                            + "$ omarchy-tv pair             # Pair PC with OmarchyTV",
                    0));
        } else if (src == SRC_PC_MEDIA) {
            JSONArray fromPc = cine != null ? cine.optJSONArray("fromPc") : null;
            for (int i = 0; fromPc != null && i < fromPc.length() && i < 10; i++) {
                JSONObject o = fromPc.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                // One-time LAN URLs (omarchy-link FileServer "/cast/<token>/") and uploads die with the send.
                boolean replay = !url.isEmpty() && !url.contains("/cast/");
                String who = o.optString("from", "PC");
                out.add(new MediaEntry(o.optString("title", "Untitled"),
                        replay ? "Sent from " + who + (o.has("subs") ? " · subtitles" : "")
                               : "Sent from " + who + " · streamed live, re-send with: omarchy-tv send",
                        "From PC", "[FROM PC]", replay ? url : "", null, replay ? "video" : "noop", false,
                        "", "$ omarchy-tv send <file|url> [--sub file.srt] [--queue]", 0));
            }
            JSONArray lib = cine != null ? cine.optJSONArray("library") : null;
            if (lib != null) {
                for (int i = 0; i < lib.length(); i++) {
                    JSONObject o = lib.optJSONObject(i);
                    if (o == null) continue;
                    String title = o.optString("title", "").trim();
                    String url = o.optString("url", "").trim();
                    if (title.isEmpty() || url.isEmpty()) continue;
                    out.add(new MediaEntry(
                            title,
                            o.optString("subtitle", "Synced from Omarchy Linux PC"),
                            o.optString("category", "PC Library"),
                            o.optString("badge", "[PC]"),
                            url, null, o.optString("kind", "video"), o.optBoolean("live", false),
                            o.optString("progress", "██░░░ 40%"), "", o.optDouble("position", 0)));
                }
            }
            out.add(new MediaEntry(
                    "Tears of Steel (Blender Sci-Fi Open Movie · 1080p)",
                    "Cyberpunk live-action + VFX open film · Direct ExoPlayer",
                    "Sci-Fi Open Cinema", "[SCI-FI]",
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                    null, "video", false, "███░░ 58%", "", 0));
            out.add(new MediaEntry(
                    "Sintel (Blender Fantasy Open Movie · 1080p)",
                    "4K-mastered open animated film · Direct ExoPlayer",
                    "Open Animation", "[ANIM]",
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
                    null, "video", false, "██░░░ 35%", "", 0));
            out.add(new MediaEntry(
                    "Big Buck Bunny (Blender Open Movie · 1080p)",
                    "Reference open cinema stream · Direct ExoPlayer",
                    "Open Cinema", "[FILM]",
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    null, "video", false, "█████ 100%", "", 0));
            out.add(new MediaEntry(
                    "Elephants Dream (Blender Open Movie)",
                    "Surrealist synth-machine open film · Direct ExoPlayer",
                    "Experimental", "[OPEN]",
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                    null, "video", false, "█░░░░ 20%", "", 0));
        } else if (src == SRC_MPV_HANDOFF) {
            JSONObject ho = cine != null ? cine.optJSONObject("lastHandoff") : null;
            if (ho != null && !ho.optString("url").isEmpty()) {
                double pos = ho.optDouble("position", 0);
                out.add(new MediaEntry(
                        "Resume: " + ho.optString("title", "PC mpv Stream"),
                        "Handed off @ " + CastUi.clock(pos) + " (" + ho.optString("direction", "pc_to_tv") + ")",
                        "mpv Handoff", "[RESUME]",
                        ho.optString("url"), null, "video", false, "███░░ " + CastUi.clock(pos),
                        "─ active mpv handoff state ─\n"
                                + "Position: " + CastUi.clock(pos) + " (" + Math.round(pos) + "s)\n"
                                + "Press [OK] to resume on TV or [H] to send back to PC mpv.",
                        pos));
            }
            out.add(new MediaEntry(
                    "Handoff Tears of Steel @ 02:15 -> TV ExoPlayer",
                    "Simulates `omarchy-tv handoff --at 02:15` from PC mpv",
                    "PC -> TV Handoff", "[PC→TV]",
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                    null, "video", false, "██░░░ 02:15",
                    "─ pc -> tv mpv handoff ─\n"
                            + "Keybind: Super + Shift + Alt + T\n"
                            + "CLI:     $ omarchy-tv handoff --at 02:15\n"
                            + "Socket:  /tmp/mpv-socket (auto-paused on PC)",
                    135));
            out.add(new MediaEntry(
                    "Send Active Stream Back to PC mpv (--start=+pos)",
                    "Broadcasts `cinemarchy_handoff` over Omarchy Link WebSocket",
                    "TV -> PC Handoff", "[TV→PC]",
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                    null, "handoff_to_pc", false, "──▶ mpv",
                    "─ tv -> pc mpv handoff ─\n"
                            + "Press [H] during playback in CastPlayerActivity\n"
                            + "or run `$ omarchy-tv handoff --back` on your PC.",
                    135));
        } else if (src == SRC_STREAMING) {
            out.add(new MediaEntry("Jellyfin Media Server", "Self-hosted cinema & TV server (LAN / WebApp)", "Self-Hosted", "[JELLYFIN]", "https://jellyfin.org", "org.jellyfin.androidtv", "service", false, "", "", 0));
            out.add(new MediaEntry("Plex", "Personal media server & live channels", "Media Server", "[PLEX]", "https://app.plex.tv", "com.plexapp.android", "service", false, "", "", 0));
            out.add(new MediaEntry("Stremio", "Unified streaming hub & add-ons", "Media Center", "[STREMIO]", "https://web.stremio.com", "com.stremio.one", "service", false, "", "", 0));
            out.add(new MediaEntry("YouTube", "Native Android TV app or WebApp", "Streaming", "[YT]", "https://www.youtube.com/tv", "com.google.android.youtube.tv", "service", false, "", "", 0));
            out.add(new MediaEntry("Netflix", "Cinema & series streaming", "Streaming", "[NETFLIX]", "https://www.netflix.com", "com.netflix.ninja", "service", false, "", "", 0));
            out.add(new MediaEntry("Prime Video", "Amazon Prime cinema & live sports", "Streaming", "[PRIME]", "https://www.primevideo.com", "com.amazon.amazonvideo.livingroom", "service", false, "", "", 0));
            out.add(new MediaEntry("Disney+", "Disney, Pixar, Marvel, Star Wars", "Streaming", "[DISNEY+]", "https://www.disneyplus.com", "com.disney.disneyplus", "service", false, "", "", 0));
            out.add(new MediaEntry("Apple TV+", "Apple Original films & series", "Streaming", "[APPLETV]", "https://tv.apple.com", "com.apple.atve.androidtv.appletv", "service", false, "", "", 0));
            out.add(new MediaEntry("Twitch", "Live coding, gaming & creator streams", "Live Stream", "[TWITCH]", "https://www.twitch.tv", "tv.twitch.android.app", "service", false, "", "", 0));
            out.add(new MediaEntry("Crunchyroll", "Anime simulcasts & cinema", "Anime", "[ANIME]", "https://www.crunchyroll.com", "com.crunchyroll.crunchyroid", "service", false, "", "", 0));
            out.add(new MediaEntry("RaiPlay", "Italian live TV & cinema on demand", "Broadcaster", "[RAI]", "https://www.raiplay.it", "it.rainet.androidtv", "service", false, "", "", 0));
        } else if (src == SRC_LIVETV) {
            out.add(new MediaEntry("System Live TV (DVB-T2 / S2 / HDMI Tuner)", "Opens native Android TV hardware tuner", "Hardware Tuner", "[TUNER]", "", null, "livetv_sys", true, "● LIVE", "", 0));
            out.add(new MediaEntry("NASA TV Public (Live ISS & Mission Control)", "24/7 Orbital feed via Omarchy WebApp", "Space Live", "[LIVE]", "https://plus.nasa.gov", null, "service", true, "● LIVE", "", 0));
            out.add(new MediaEntry("Euronews Live Hub", "European live news stream", "News Live", "[NEWS]", "https://www.euronews.com/live", null, "service", true, "● LIVE", "", 0));
            out.add(new MediaEntry("Sky TG24 Live", "24-hour Italian & international news stream", "News Live", "[SKYTG24]", "https://tg24.sky.it/diretta-tv", null, "service", true, "● LIVE", "", 0));
        } else if (src == SRC_SNIPPETS) {
            JSONArray snips = cine != null ? cine.optJSONArray("snippets") : null;
            if (snips != null) {
                for (int i = 0; i < snips.length(); i++) {
                    JSONObject o = snips.optJSONObject(i);
                    if (o == null) continue;
                    String title = o.optString("title", "").trim();
                    String url = o.optString("url", "").trim();
                    if (title.isEmpty() || url.isEmpty()) continue;
                    out.add(new MediaEntry(
                            title, o.optString("subtitle", "Custom Video Snippet"),
                            o.optString("category", "Snippet"), o.optString("badge", "[SNIP]"),
                            url, null, "video", false, "LOOP", "", 0));
                }
            }
            out.add(new MediaEntry("For Bigger Blazes (15s HDR/OLED Showcase)", "High-contrast neon & fire test snippet", "OLED Snippet", "[15s]", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4", null, "video", false, "4K HDR", "", 0));
            out.add(new MediaEntry("For Bigger Escapes (15s Nature & Motion)", "Panning mountain & sky motion test snippet", "Motion Snippet", "[15s]", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4", null, "video", false, "60fps", "", 0));
            out.add(new MediaEntry("For Bigger Joyrides (15s Night Roadster)", "Night city lights & reflections test snippet", "Night Snippet", "[15s]", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4", null, "video", false, "OLED", "", 0));
            out.add(new MediaEntry("For Bigger Fun (60s Vibrant Color Loop)", "Fast-cut color saturation test snippet", "Color Snippet", "[60s]", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4", null, "video", false, "RGB", "", 0));
        } else if (src == SRC_IPTV) {
            return iptvEntries(root);
        } else if (src == SRC_QUEUE) {
            return queueEntries(cine);
        }
        return out;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && (cmdInput == null || !cmdInput.hasFocus())) {
            int kc = e.getKeyCode();
            if (kc >= KeyEvent.KEYCODE_1 && kc <= KeyEvent.KEYCODE_8) {
                selectSource(kc - KeyEvent.KEYCODE_1, true);
                return true;
            }
            switch (kc) {
                case KeyEvent.KEYCODE_J:
                    moveListFocus(1);
                    return true;
                case KeyEvent.KEYCODE_K:
                    moveListFocus(-1);
                    return true;
                case KeyEvent.KEYCODE_G:
                    if (e.isShiftPressed()) {
                        if (listContainer.getChildCount() > 0) {
                            listContainer.getChildAt(listContainer.getChildCount() - 1).requestFocus();
                        }
                    } else {
                        if (listContainer.getChildCount() > 0) {
                            listContainer.getChildAt(0).requestFocus();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_P:
                    activateEntry(selectedEntry);
                    return true;
                case KeyEvent.KEYCODE_Q:
                    addToQueue(selectedEntry);
                    return true;
                case KeyEvent.KEYCODE_F:
                    if (selectedEntry != null && "iptv_channel".equals(selectedEntry.kind)) {
                        boolean on = com.omarchytv.home.iptv.IptvStore.toggleFavorite(this, selectedEntry.url);
                        Toast.makeText(this, (on ? "★ Added: " : "Removed: ") + selectedEntry.title, Toast.LENGTH_SHORT).show();
                        selectSource(SRC_IPTV, false);
                    }
                    return true;
                case KeyEvent.KEYCODE_H:
                    if (selectedEntry != null) {
                        OmarchyLink.handoffToPc(selectedEntry.title, selectedEntry.url, selectedEntry.resumeSec);
                        Toast.makeText(this, "Handoff to PC mpv: " + selectedEntry.title, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case KeyEvent.KEYCODE_S:
                    startActivity(new Intent(this, ScreensaverPreviewActivity.class));
                    return true;
                case KeyEvent.KEYCODE_SEMICOLON:
                case KeyEvent.KEYCODE_SLASH:
                    if (cmdInput != null) {
                        cmdInput.requestFocus();
                        cmdInput.setText(":");
                        cmdInput.setSelection(cmdInput.getText().length());
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_R:
                case KeyEvent.KEYCODE_MENU:
                    if (wordmark != null) wordmark.reveal();
                    return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void moveListFocus(int delta) {
        int count = listContainer.getChildCount();
        if (count == 0) return;
        int cur = 0;
        for (int i = 0; i < count; i++) {
            if (listContainer.getChildAt(i).hasFocus()) {
                cur = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(count - 1, cur + delta));
        listContainer.getChildAt(next).requestFocus();
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(bold ? monoBold : mono);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
