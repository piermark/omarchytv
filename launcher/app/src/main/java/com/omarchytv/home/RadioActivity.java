package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Radiomarchy (v2.20): Omarchy Radio on the TV, an app of its own like Cinemarchy (it has its own icon in the
 * TV's app list too). The community's songs from radio.omarchy.org and Omarchy Stories, the community podcast,
 * in Omarchy's TUI style over fractals that dance to the music.
 * <ul>
 * <li>[1] sources: songs · Omarchy Stories · up next · demo mode · settings.</li>
 * <li>[2] the list: every song with who made it, an E badge on the explicit ones (shown by default, as on the
 *     site; Settings hides them), the episodes with their length, date and where you stopped.</li>
 * <li>[3] now playing: title, the maker's credit, a live 16-band spectrum, the progress bar, the controls, the
 *     lyrics (timed lines light up as they are sung) and the song's own address on radio.omarchy.org.</li>
 * <li>Demo mode (the RED key, or a minute without touching the remote while music plays): the panels go and the
 *     fractals take the whole screen, with a 90s sine scroller of what is playing. ◀ ▶ change the fractal,
 *     ▲ ▼ the colours. The launcher's "Radiomarchy fractals" screensaver opens this mode too.</li>
 * </ul>
 * The music keeps playing when you leave ({@link RadioPlayer}, {@link RadioService}); the Home screen's top bar
 * shows what is playing and opens this app again. Media keys work everywhere.
 */
public final class RadioActivity extends Activity implements RadioPlayer.Listener {
    public static final String RADIOMARCHY_VERSION = "0.1.0";
    static final String EXTRA_SAVER = "omarchytv.saver", EXTRA_DEMO = "omarchytv.demo";

    private static final int SRC_SONGS = 0, SRC_STORIES = 1, SRC_QUEUE = 2, SRC_DEMO = 3, SRC_SETTINGS = 4;

    private final Handler h = new Handler(Looper.getMainLooper());
    private RadioPlayer player;
    private Typeface mono, monoBold;
    private FrameLayout root;
    private FractalView fractal;
    private FractalSlideView slides;
    private View dimmer;
    private boolean plainBackdrop;
    private boolean liveForDemoOnly;
    private long openedAt;
    private LinearLayout main, listBox;
    private ScrollView listScroll;
    private TextView status, npBadge, npTitle, npBy, npAlbum, npTime, npBar, lyricsView, npLink, hint, listHeader;
    private SpectrumView spectrum;
    private ScrollerView scroller;
    private TextView demoCorner;
    private final List<TextView> sourceButtons = new ArrayList<>();
    private final List<TextView> controls = new ArrayList<>();
    private int source = SRC_SONGS;
    private List<RadioCatalog.Track> songs = new ArrayList<>(), episodes = new ArrayList<>();
    private boolean loading = true, offline;
    private boolean demo, saver;
    private long lastKey = SystemClock.elapsedRealtime();
    private String lyricsFor = "";
    private List<RadioCatalog.Line> sheet = new ArrayList<>();
    private final Map<String, List<RadioCatalog.Line>> sheets = new HashMap<>();
    private int sheetLine = -2;
    private int palette, scenePin = -1, quality;
    private boolean showExplicit = true, autoDemo = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTime();
            if (!demo && !saver && autoDemo && player.playing() && SystemClock.elapsedRealtime() - lastKey > 60_000) setDemo(true);
            h.postDelayed(this, 250);
        }
    };

    static void open(Context c, boolean demo, boolean asSaver) {
        Intent i = new Intent(c, RadioActivity.class);
        if (demo) i.putExtra(EXTRA_DEMO, true);
        if (asSaver) i.putExtra(EXTRA_SAVER, true);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        openedAt = SystemClock.elapsedRealtime();
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        player = RadioPlayer.get(this);
        SharedPreferences p = player.prefs();
        showExplicit = p.getBoolean("explicit", true);
        autoDemo = p.getBoolean("autoDemo", true);
        palette = p.getInt("palette", 0);
        scenePin = p.getInt("scene", -1);
        quality = p.getInt("quality", 0);
        saver = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SAVER, false);
        Theme t = Theme.cur();
        try {
            mono = CastUi.font(this);
            monoBold = Typeface.create(mono, Typeface.BOLD);
        } catch (Exception e) {
            mono = Typeface.MONOSPACE;
            monoBold = Typeface.DEFAULT_BOLD;
        }

        root = new FrameLayout(this);
        // Instant open: a plain static theme background, like Cinemarchy. No GL, no stills to decode.
        root.setBackgroundColor(0xFF000000 | (t.bgDarker & 0xFFFFFF));
        plainBackdrop = FractalView.preferPlain(this);
        dimmer = new View(this);
        if (!plainBackdrop) dimmer.setBackgroundColor(Theme.withAlpha(t.bgDarker, 0.42f));
        root.addView(dimmer, new FrameLayout.LayoutParams(-1, -1));

        main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(28), dp(16), dp(28), dp(16));

        // header: the RADIOMARCHY wordmark, the prompt line, the station status
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout logoBox = new LinearLayout(this);
        logoBox.setOrientation(LinearLayout.VERTICAL);
        WordmarkView wm = new WordmarkView(this, "radiomarchy", 330f);
        wm.setContinuousShimmer(true);
        logoBox.addView(wm, new LinearLayout.LayoutParams(-2, -2));
        TextView sub = label("radiomarchy@omarchytv:~$ ./radiomarchy --station omarchy --fractals " + FractalView.SCENES[0] + " --theme " + t.name, 11, t.fgDim, false);
        sub.setPadding(0, dp(4), 0, 0);
        logoBox.addView(sub);
        header.addView(logoBox, new LinearLayout.LayoutParams(0, -2, 1f));
        status = label("", 11, t.cyan, true);
        status.setGravity(Gravity.END);
        header.addView(status);
        main.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1f);
        bl.topMargin = dp(10);

        // [1] sources
        LinearLayout src = pane(t, "─ [1] radio ─");
        String[] names = {"♫ " + getString(R.string.radio_songs), "🎙 Omarchy Stories", "≡ " + getString(R.string.radio_queue),
                "✦ " + getString(R.string.radio_demo), "⚙ " + getString(R.string.radio_settings)};
        sourceButtons.clear();
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView b = label(names[i], 13, t.fg, true);
            b.setSingleLine(true);
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setFocusable(true);
            b.setPadding(dp(12), dp(9), dp(12), dp(9));
            b.setOnClickListener(v -> {
                if (k == SRC_DEMO) setDemo(true);
                else selectSource(k, true);
            });
            b.setOnFocusChangeListener((v, f) -> {
                styleSource();
                if (f && k != SRC_DEMO && k != source) selectSource(k, false);
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
            l.topMargin = dp(6);
            src.addView(b, l);
            sourceButtons.add(b);
        }
        TextView keys = label("\n─ keys ─\n[OK] play\n[⏯] pause · resume\n[⏮ ⏭] previous · next\n[⏪ ⏩] −15 s · +15 s\n[RED] demo mode\n[GREEN] shuffle\n[YELLOW] repeat\n[BLUE] explicit on/off\n[1-5] the panes", 11, t.fgDim, false);
        src.addView(keys);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -1, 0.7f);
        p1.rightMargin = dp(12);
        body.addView(src, p1);

        // [2] the list
        LinearLayout center = pane(t, "");
        listHeader = (TextView) center.getChildAt(0);
        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setSmoothScrollingEnabled(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(6), 0, dp(6));
        listScroll.addView(listBox, new FrameLayout.LayoutParams(-1, -2));
        center.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -1, 1.4f);
        p2.rightMargin = dp(12);
        body.addView(center, p2);

        // [3] now playing
        LinearLayout np = pane(t, "─ [3] now playing ─");
        npBadge = label("", 11, t.green, true);
        npBadge.setPadding(0, dp(6), 0, 0);
        np.addView(npBadge);
        npTitle = label("", 20, t.fgBright, true);
        npTitle.setMaxLines(2);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        npTitle.setPadding(0, dp(4), 0, 0);
        np.addView(npTitle);
        npBy = label("", 13, t.accent, true);
        npBy.setSingleLine(true);
        npBy.setEllipsize(TextUtils.TruncateAt.END);
        np.addView(npBy);
        npAlbum = label("", 11, t.fgDim, false);
        npAlbum.setSingleLine(true);
        npAlbum.setEllipsize(TextUtils.TruncateAt.END);
        np.addView(npAlbum);
        spectrum = new SpectrumView(this);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(46));
        sl.topMargin = dp(8);
        np.addView(spectrum, sl);
        npBar = label("", 12, t.green, false);
        npBar.setSingleLine(true);
        npBar.setPadding(0, dp(6), 0, 0);
        np.addView(npBar);
        npTime = label("", 11, t.fgDim, false);
        np.addView(npTime);
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        controls.clear();
        String[] ci = {"⏮", "⏯", "⏭", "🔀", "🔁"};
        for (int i = 0; i < ci.length; i++) {
            final int k = i;
            TextView b = label(ci[i], 16, t.fgBright, true);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            b.setPadding(dp(10), dp(4), dp(10), dp(4));
            b.setOnClickListener(v -> control(k));
            b.setOnFocusChangeListener((v, f) -> styleControls());
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.rightMargin = dp(8);
            ctl.addView(b, l);
            controls.add(b);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(8);
        np.addView(ctl, cl);
        TextView lh = label("─ " + getString(R.string.radio_lyrics) + " ─", 11, t.accent, true);
        lh.setPadding(0, dp(10), 0, 0);
        np.addView(lh);
        lyricsView = label("", 12, t.fg, false);
        lyricsView.setPadding(0, dp(4), 0, 0);
        np.addView(lyricsView, new LinearLayout.LayoutParams(-1, 0, 1f));
        npLink = label("", 10, t.fgDim, false);
        npLink.setSingleLine(true);
        npLink.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        np.addView(npLink);
        body.addView(np, new LinearLayout.LayoutParams(0, -1, 1.1f));
        main.addView(body, bl);

        // the bottom bar, like Cinemarchy's
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(Theme.withAlpha(t.bg, 0.88f));
        bb.setStroke(dp(1), t.inactiveBorder);
        bottom.setBackground(bb);
        TextView mode = label("RADIO", 11, t.bgDarker, true);
        mode.setBackgroundColor(t.accent);
        mode.setPadding(dp(8), dp(2), dp(8), dp(2));
        bottom.addView(mode);
        hint = label("", 11, t.fgDim, false);
        hint.setSingleLine(true);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        hint.setPadding(dp(10), 0, 0, 0);
        bottom.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView credit = label(getString(R.string.radio_send), 11, t.fgDim, false);
        bottom.addView(credit);
        LinearLayout.LayoutParams bol = new LinearLayout.LayoutParams(-1, -2);
        bol.topMargin = dp(8);
        main.addView(bottom, bol);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // demo mode: a 90s sine scroller and a small corner tag over the full-screen fractal
        scroller = new ScrollerView(this);
        scroller.setVisibility(View.GONE);
        FrameLayout.LayoutParams scl = new FrameLayout.LayoutParams(-1, dp(120), Gravity.BOTTOM);
        scl.bottomMargin = dp(36);
        root.addView(scroller, scl);
        demoCorner = label("", 11, t.fgBright, true);
        demoCorner.setVisibility(View.GONE);
        demoCorner.setShadowLayer(6, 0, 0, 0xFF000000);
        FrameLayout.LayoutParams dcl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dcl.topMargin = dp(20);
        dcl.rightMargin = dp(28);
        root.addView(demoCorner, dcl);

        setContentView(root);
        wm.reveal();
        loading = true;
        selectSource(player.current() != null && player.current().episode() ? SRC_STORIES : SRC_SONGS, false);
        refresh(true);
        root.post(this::onFirstUiFrame);
        loadCachedAsync();
        boolean demoNow = saver || (getIntent() != null && getIntent().getBooleanExtra(EXTRA_DEMO, false));
        if (demoNow) setDemo(true);
        if (saver) startSaverMusic();
        fetch();
    }

    /** UI is on screen: log time-to-first-frame, then start live GL only if the normal view wants it. Demo starts after that. */
    private void onFirstUiFrame() {
        if (isFinishing()) return;
        String gpu = FractalView.cachedGpu(this);
        if (RadioPlayer.wantLiveNormal(this)) {
            plainBackdrop = false;
            logStart("live", gpu);
            startLiveGl(false);
        } else {
            plainBackdrop = true;
            logStart("plain", gpu);
        }
        if (demo) startDemoVisuals();
    }

    /**
     * @param forDemo if true on a weak TV, start the 720p full-shader beauty path (not the light bake).
     */
    private void startLiveGl(boolean forDemo) {
        if (isFinishing() || fractal != null) return;
        boolean weak = FractalView.weakHardware(this);
        FractalView v = new FractalView(this);
        v.setPalette(palette);
        v.setQuality(quality);
        if (scenePin >= 0) v.setScene(scenePin);
        v.setOnSafety(why -> dropToSlideshow());
        v.setOnFirstPresent(() -> {
            if (isFinishing() || fractal == null) return;
            android.util.Log.i("Radiomarchy", "start renderer=live/" + fractal.quality()
                    + (weak && (forDemo || RadioPlayer.wantLiveNormal(this)) ? "/beauty720" : "")
                    + " gpu=" + gpuName() + " ram=" + (FractalView.totalRam(this) / (1024 * 1024)) + "MB"
                    + " ttf=" + (SystemClock.elapsedRealtime() - openedAt) + "ms");
        });
        if (weak && (forDemo || RadioPlayer.wantLiveNormal(this))) v.prepareDemo(true);
        else v.prepareScene();
        root.addView(v, 0, new FrameLayout.LayoutParams(-1, -1));
        fractal = v;
        plainBackdrop = false;
        liveForDemoOnly = forDemo && !RadioPlayer.wantLiveNormal(this);
        v.onResume();
    }

    private void dropToSlideshow() {
        FractalView v = fractal;
        fractal = null;
        liveForDemoOnly = false;
        if (v != null) {
            try { v.onPause(); } catch (Throwable ignored) {}
            try { root.removeView(v); } catch (Throwable ignored) {}
        }
        if (demo || RadioPlayer.wantLiveNormal(this)) showSlides();
        else hideSlides();
        if (dimmer != null && !demo) dimmer.setBackgroundColor(0);
        Theme t = Theme.cur();
        root.setBackgroundColor(0xFF000000 | (t.bgDarker & 0xFFFFFF));
        plainBackdrop = true;
        if (demo) updateDemo();
        android.util.Log.i("Radiomarchy", "start renderer=slides gpu=" + gpuName()
                + " ram=" + (FractalView.totalRam(this) / (1024 * 1024)) + "MB (fallback after kill)");
    }

    private void showSlides() {
        if (isFinishing()) return;
        if (slides == null) {
            slides = new FractalSlideView(this);
            root.addView(slides, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        slides.setVisibility(View.VISIBLE);
        slides.start();
    }

    private void hideSlides() {
        if (slides == null) return;
        slides.stop();
        slides.setVisibility(View.GONE);
    }

    private void pauseLiveForPlainUi() {
        if (fractal == null) return;
        try { fractal.onPause(); } catch (Throwable ignored) {}
        fractal.setVisibility(View.GONE);
    }

    private void startDemoVisuals() {
        if (!demo || isFinishing()) return;
        if (RadioPlayer.wantLiveDemo(this)) {
            hideSlides();
            if (fractal == null) startLiveGl(true);
            else {
                fractal.setVisibility(View.VISIBLE);
                try { fractal.onResume(); } catch (Throwable ignored) {}
            }
        } else {
            pauseLiveForPlainUi();
            showSlides();
        }
        updateDemo();
    }

    private String gpuName() {
        String g = FractalView.cachedGpu(this);
        return g == null || g.isEmpty() ? "-" : g;
    }

    private void logStart(String renderer, String gpu) {
        long ramMb = FractalView.totalRam(this) / (1024 * 1024);
        long ttf = SystemClock.elapsedRealtime() - openedAt;
        if (gpu == null || gpu.isEmpty()) gpu = "-";
        android.util.Log.i("Radiomarchy", "start renderer=" + renderer + " gpu=" + gpu
                + " ram=" + ramMb + "MB ttf=" + ttf + "ms");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (fractal != null && fractal.getVisibility() == View.VISIBLE) fractal.onResume();
        if (slides != null && slides.getVisibility() == View.VISIBLE) slides.start();
        player.addListener(this);
        h.removeCallbacks(tick);
        h.post(tick);
        lastKey = SystemClock.elapsedRealtime();
        onRadio();
        if (saver) {
            startSaverMusic();
            h.removeCallbacks(startSaverMusicAgain);
            h.postDelayed(startSaverMusicAgain, 400);   // after a task-switch focus loss on some TVs
        }
    }

    @Override
    protected void onPause() {
        player.removeListener(this);
        h.removeCallbacks(tick);
        h.removeCallbacks(startSaverMusicAgain);
        if (fractal != null) fractal.onPause();
        if (slides != null) slides.stop();
        super.onPause();
        if (saver && !isFinishing()) finish();   // the screensaver never stays behind
    }

    @Override
    public void onRadio() {
        if (isFinishing()) return;
        refresh(false);
    }

    // ---- data -----------------------------------------------------------------------------------------

    private File cache(String name) {
        return new File(getFilesDir(), name);
    }

    private void loadCachedAsync() {
        final File fa = cache("radio-playlist.json"), fb = cache("radio-stories.rss");
        com.omarchytv.home.lights.Lights.run(() -> {
            String a = RadioCatalog.readCache(fa), b = RadioCatalog.readCache(fb);
            final List<RadioCatalog.Track> s = a != null ? RadioCatalog.parseSongs(a) : new ArrayList<RadioCatalog.Track>();
            final List<RadioCatalog.Track> e = b != null ? RadioCatalog.parseFeed(b) : new ArrayList<RadioCatalog.Track>();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                songs = s;
                episodes = e;
                loading = songs.isEmpty();
                selectSource(player.current() != null && player.current().episode() ? SRC_STORIES : SRC_SONGS, false);
                refresh(true);
            });
        });
    }

    private void fetch() {
        final File fa = cache("radio-playlist.json"), fb = cache("radio-stories.rss");
        com.omarchytv.home.lights.Lights.run(() -> {
            String a = RadioCatalog.fetch(RadioCatalog.PLAYLIST);
            List<RadioCatalog.Track> s = a != null ? RadioCatalog.parseSongs(a) : null;
            if (s != null && !s.isEmpty()) RadioCatalog.writeCache(fa, a);
            String b = RadioCatalog.fetch(RadioCatalog.FEED);
            List<RadioCatalog.Track> e = b != null ? RadioCatalog.parseFeed(b) : null;
            if (e == null || e.isEmpty()) {
                b = RadioCatalog.fetch(RadioCatalog.FEED_HOST);
                e = b != null ? RadioCatalog.parseFeed(b) : null;
            }
            if (e != null && !e.isEmpty()) RadioCatalog.writeCache(fb, b);
            final List<RadioCatalog.Track> ss = s, ee = e;
            h.post(() -> {
                if (isFinishing()) return;
                loading = false;
                offline = ss == null || ss.isEmpty();
                if (ss != null && !ss.isEmpty()) songs = ss;
                if (ee != null && !ee.isEmpty()) episodes = ee;
                View f = getCurrentFocus();
                boolean inList = f != null && isInside(f, listBox);
                fillList(inList || f == null);
                refresh(true);
                if (saver) startSaverMusic();
            });
        });
    }

    private void loadLyrics(RadioCatalog.Track t) {
        if (t == null || t.lyricsUrl.isEmpty()) {
            sheet = new ArrayList<>();
            lyricsFor = t != null ? t.url : "";
            return;
        }
        if (t.url.equals(lyricsFor)) return;
        lyricsFor = t.url;
        sheetLine = -2;
        List<RadioCatalog.Line> known = sheets.get(t.lyricsUrl);
        if (known != null) {
            sheet = known;
            return;
        }
        sheet = new ArrayList<>();
        final String url = t.lyricsUrl, forUrl = t.url;
        com.omarchytv.home.lights.Lights.run(() -> {
            String txt = RadioCatalog.fetch(url);
            List<RadioCatalog.Line> got = txt != null ? RadioCatalog.parseLyrics(txt) : new ArrayList<>();
            h.post(() -> {
                sheets.put(url, got);
                if (forUrl.equals(lyricsFor)) {
                    sheet = got;
                    sheetLine = -2;
                    updateLyrics(true);
                }
            });
        });
    }

    // ---- the panes ------------------------------------------------------------------------------------

    private void selectSource(int s, boolean focusList) {
        source = s;
        styleSource();
        fillList(focusList);
    }

    private void styleSource() {
        Theme t = Theme.cur();
        for (int i = 0; i < sourceButtons.size(); i++) {
            TextView b = sourceButtons.get(i);
            boolean sel = i == source, f = b.hasFocus();
            GradientDrawable g = new GradientDrawable();
            g.setColor(f ? t.selection : sel ? Theme.withAlpha(t.bgLight, 0.92f) : Theme.withAlpha(t.bgDarker, 0.65f));
            g.setStroke(dp(f ? 2 : 1), f ? t.activeBorder : sel ? t.accent : t.inactiveBorder);
            b.setBackground(g);
            b.setTextColor(f || sel ? t.fgBright : t.fgDim);
        }
    }

    private List<RadioCatalog.Track> shownSongs() {
        return RadioCatalog.visible(songs, showExplicit);
    }

    private void fillList(boolean focusFirst) {
        Theme t = Theme.cur();
        listBox.removeAllViews();
        RadioCatalog.Track cur = player.current();
        switch (source) {
            case SRC_STORIES: {
                listHeader.setText("─ [2] Omarchy Stories · " + episodes.size() + " ─");
                if (episodes.isEmpty()) {
                    row(null, "…", loading ? getString(R.string.radio_loading) : getString(R.string.radio_empty), "", null);
                    break;
                }
                for (int i = 0; i < episodes.size(); i++) {
                    final int k = i;
                    RadioCatalog.Track e = episodes.get(i);
                    long saved = player.savedPosition(e);
                    String meta = (e.durationMs > 0 ? CastUi.clock(e.durationMs / 1000.0) : "")
                            + (e.date.length() >= 16 ? "  ·  " + e.date.substring(5, 16) : "")
                            + (saved > 0 ? "  ·  " + getString(R.string.radio_resume, CastUi.clock(saved / 1000.0)) : "");
                    row(e, e.title.matches("^\\d{2,}.*") ? "🎙" : String.format(Locale.ROOT, "%02d", episodes.size() - i), e.title, meta,
                            () -> player.play(episodes, k));   // titles that carry their own number ("009 ...") get the mic
                }
                break;
            }
            case SRC_QUEUE: {
                List<RadioCatalog.Track> q = player.upNext(60);
                listHeader.setText("─ [2] " + getString(R.string.radio_queue) + " · " + q.size() + " ─");
                if (cur != null) row(cur, "▶", cur.title, cur.artist, () -> player.toggle());
                if (q.isEmpty() && cur == null) row(null, "…", getString(R.string.radio_nothing), "", null);
                for (int i = 0; i < q.size(); i++) {
                    final int k = i + 1;
                    RadioCatalog.Track e = q.get(i);
                    row(e, String.format(Locale.ROOT, "%2d", k), e.title, e.artist, () -> {
                        for (int j = 0; j < k; j++) player.advance(1, true);
                    });
                }
                break;
            }
            case SRC_SETTINGS:
                listHeader.setText("─ [2] " + getString(R.string.radio_settings) + " ─");
                settings();
                break;
            default: {
                List<RadioCatalog.Track> list = shownSongs();
                listHeader.setText("─ [2] " + getString(R.string.radio_songs) + " · " + list.size() + (offline ? " · offline" : "") + " ─");
                if (list.isEmpty()) {
                    row(null, "…", loading ? getString(R.string.radio_loading) : getString(R.string.radio_empty), offline ? getString(R.string.radio_offline) : "", null);
                    break;
                }
                row(null, "▶", getString(R.string.radio_play_all), list.size() + " · radio.omarchy.org" + (player.shuffle() ? " · 🔀" : ""),
                        () -> player.play(list, player.shuffle() ? (int) (Math.random() * list.size()) : 0));
                row(null, "🔀", getString(R.string.radio_shuffle_all), getString(R.string.radio_shuffle), () -> {
                    if (!player.shuffle()) player.setShuffle(true);
                    player.play(list, (int) (Math.random() * list.size()));
                });
                for (int i = 0; i < list.size(); i++) {
                    final int k = i;
                    RadioCatalog.Track s = list.get(i);
                    row(s, String.format(Locale.ROOT, "%02d", i + 1), s.title, s.artist + (s.album.isEmpty() ? "" : "  ·  " + s.album), () -> player.play(list, k));
                }
            }
        }
        if (focusFirst && listBox.getChildCount() > 0) {
            View target = listBox.getChildAt(0);
            for (int i = 0; i < listBox.getChildCount(); i++) {
                Object tag = listBox.getChildAt(i).getTag();
                if (cur != null && tag instanceof RadioCatalog.Track && ((RadioCatalog.Track) tag).url.equals(cur.url)) {
                    target = listBox.getChildAt(i);
                    break;
                }
            }
            target.requestFocus();
        }
    }

    private void row(RadioCatalog.Track tr, String num, String title, String meta, Runnable act) {
        Theme t = Theme.cur();
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setTag(tr);
        r.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView n = label(num, 12, t.accent, true);
        n.setMinWidth(dp(30));
        r.addView(n);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tl = new LinearLayout(this);
        tl.setGravity(Gravity.CENTER_VERTICAL);
        TextView ti = label(title, 14, t.fgBright, true);
        ti.setSingleLine(true);
        ti.setEllipsize(TextUtils.TruncateAt.END);
        tl.addView(ti, new LinearLayout.LayoutParams(0, -2, 1f));
        if (tr != null && tr.explicit) tl.addView(explicitBadge());
        col.addView(tl, new LinearLayout.LayoutParams(-1, -2));
        TextView m = label(meta, 11, t.fgDim, false);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(m);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView play = label("", 13, t.green, true);
        play.setPadding(dp(8), 0, 0, 0);
        r.addView(play);
        styleRow(r, false);
        r.setOnFocusChangeListener((v, f) -> {
            styleRow((LinearLayout) v, f);
            if (f) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                listScroll.smoothScrollTo(0, Math.max(0, v.getTop() - listScroll.getHeight() / 3));
            }
        });
        if (act != null) r.setOnClickListener(v -> {
            act.run();
            lastKey = SystemClock.elapsedRealtime();
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.bottomMargin = dp(5);
        listBox.addView(r, l);
    }

    private TextView explicitBadge() {
        Theme t = Theme.cur();
        TextView e = label("E", 10, t.bgDarker, true);
        e.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.fgDim);
        g.setCornerRadius(dp(3));
        e.setBackground(g);
        e.setPadding(dp(5), 0, dp(5), 0);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.leftMargin = dp(8);
        e.setLayoutParams(l);
        return e;
    }

    private void styleRow(LinearLayout r, boolean focused) {
        Theme t = Theme.cur();
        RadioCatalog.Track tr = (RadioCatalog.Track) r.getTag(), cur = player.current();
        boolean now = tr != null && cur != null && tr.url.equals(cur.url);
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? t.selection : now ? Theme.withAlpha(t.bgLight, 0.9f) : Theme.withAlpha(t.bgDarker, 0.72f));
        g.setStroke(dp(focused ? 2 : 1), focused ? t.activeBorder : now ? t.accent : t.inactiveBorder);
        r.setBackground(g);
        View last = r.getChildAt(r.getChildCount() - 1);
        if (last instanceof TextView) ((TextView) last).setText(now ? (player.playing() ? eq() : "‖") : focused ? "▶" : "");
    }

    /** A tiny live equaliser for the row that is playing. */
    private String eq() {
        float[] b = spectrum != null ? spectrum.bars : null;
        if (b == null) return "▶";
        String ramp = "▁▂▃▄▅▆▇█";
        StringBuilder s = new StringBuilder();
        for (int i = 1; i < 14; i += 4) s.append(ramp.charAt(Math.max(0, Math.min(7, Math.round(b[i] * 7)))));
        return s.toString();
    }

    private void settings() {
        Theme t = Theme.cur();
        setting(getString(R.string.radio_explicit), getString(showExplicit ? R.string.radio_shown : R.string.radio_hidden), () -> {
            showExplicit = !showExplicit;
            player.prefs().edit().putBoolean("explicit", showExplicit).apply();
        });
        setting(getString(R.string.radio_colours), palette == 1 ? "Omarchy · " + t.name : palette == 0 ? getString(R.string.radio_random) : FractalView.PALETTES[palette], () -> {
            palette = (palette + 1) % FractalView.PALETTES.length;
            player.prefs().edit().putInt("palette", palette).apply();
            if (fractal != null) fractal.setPalette(palette);
        });
        setting(getString(R.string.radio_quality), quality == 0 ? getString(R.string.radio_quality_auto, fractal != null ? fractal.quality() : "plain") : FractalView.QUALITIES[quality], () -> {
            quality = (quality + 1) % FractalView.QUALITIES.length;
            player.prefs().edit().putInt("quality", quality).apply();
            if (fractal != null) fractal.setQuality(quality);
        });
        String anim = RadioPlayer.animatedMode(this);
        int animLab = "on".equals(anim) ? R.string.radio_animated_on : "off".equals(anim) ? R.string.radio_animated_off : R.string.radio_animated_auto;
        setting(getString(R.string.radio_animated), getString(animLab), () -> {
            RadioPlayer.setAnimatedMode(this, RadioPlayer.nextAnimatedMode(RadioPlayer.animatedMode(this)));
        });
        setting(getString(R.string.radio_fractal), scenePin < 0 ? getString(R.string.radio_auto) : FractalView.SCENES[scenePin], () -> {
            scenePin = scenePin + 1 >= FractalView.SCENES.length ? -1 : scenePin + 1;
            player.prefs().edit().putInt("scene", scenePin).apply();
            if (fractal != null) fractal.setScene(scenePin);
        });
        setting(getString(R.string.radio_auto_demo), getString(autoDemo ? R.string.on : R.string.off), () -> {
            autoDemo = !autoDemo;
            player.prefs().edit().putBoolean("autoDemo", autoDemo).apply();
        });
        setting(getString(R.string.radio_shuffle), getString(player.shuffle() ? R.string.on : R.string.off), () -> player.setShuffle(!player.shuffle()));
        setting(getString(R.string.radio_repeat), repeatLabel(), player::cycleRepeat);
        setting(getString(R.string.radio_stop), "■", () -> player.stop());
        TextView about = label("\nRadiomarchy " + RADIOMARCHY_VERSION + " · OmarchyTV\n\n" + getString(R.string.radio_about), 11, t.fgDim, false);
        about.setPadding(dp(12), 0, dp(12), 0);
        listBox.addView(about);
    }

    private String repeatLabel() {
        int r = player.repeat();
        return getString(r == RadioPlayer.REPEAT_ONE ? R.string.radio_repeat_one : r == RadioPlayer.REPEAT_OFF ? R.string.off : R.string.radio_repeat_all);
    }

    private void setting(String name, String value, Runnable change) {
        row(null, "›", name, value, () -> {
            View f = getCurrentFocus();
            int at = f != null ? listBox.indexOfChild(f) : 0;
            change.run();
            fillList(false);
            View v = listBox.getChildAt(Math.max(0, at));
            if (v != null) v.requestFocus();
        });
    }

    private void control(int k) {
        lastKey = SystemClock.elapsedRealtime();
        switch (k) {
            case 0: player.advance(-1, true); break;
            case 1:
                if (player.current() == null) {
                    List<RadioCatalog.Track> l = shownSongs();
                    if (!l.isEmpty()) player.play(l, player.shuffle() ? (int) (Math.random() * l.size()) : 0);
                } else player.toggle();
                break;
            case 2: player.advance(1, true); break;
            case 3: player.setShuffle(!player.shuffle()); break;
            default: player.cycleRepeat(); break;
        }
    }

    private void styleControls() {
        Theme t = Theme.cur();
        for (int i = 0; i < controls.size(); i++) {
            TextView b = controls.get(i);
            boolean on = (i == 3 && player.shuffle()) || (i == 4 && player.repeat() != RadioPlayer.REPEAT_OFF);
            GradientDrawable g = new GradientDrawable();
            g.setColor(b.hasFocus() ? t.selection : Theme.withAlpha(t.bgDarker, 0.7f));
            g.setStroke(dp(b.hasFocus() ? 2 : 1), b.hasFocus() ? t.activeBorder : on ? t.accent : t.inactiveBorder);
            g.setCornerRadius(dp(4));
            b.setBackground(g);
            b.setTextColor(on || b.hasFocus() ? t.fgBright : t.fgDim);
            if (i == 1) b.setText(player.playing() ? "⏸" : "▶");
            if (i == 4) b.setText(player.repeat() == RadioPlayer.REPEAT_ONE ? "🔂" : "🔁");
        }
    }

    // ---- now playing ----------------------------------------------------------------------------------

    private void refresh(boolean full) {
        Theme t = Theme.cur();
        RadioCatalog.Track cur = player.current();
        status.setText((player.playing() ? "● on air" : cur != null ? "‖ paused" : "○ ready") + "  ·  " + songs.size() + " songs  ·  "
                + episodes.size() + " episodes\nradio.omarchy.org  ·  " + (offline ? "offline copy" : "every song a pull request"));
        if (cur == null) {
            npBadge.setText("[ READY ]");
            npTitle.setText(getString(R.string.radio_nothing));
            npBy.setText("");
            npAlbum.setText("");
            npLink.setText("");
            lyricsView.setText("");
        } else {
            String err = player.error();
            npBadge.setText(!err.isEmpty() ? "[ ! " + err + " ]" : player.buffering() ? "[ … BUFFERING ]" : player.playing() ? "[ ● ON AIR ]" : "[ ‖ PAUSED ]");
            npBadge.setTextColor(!err.isEmpty() ? t.red : player.playing() ? t.green : t.yellow);
            SpannableStringBuilder b = new SpannableStringBuilder(cur.title);
            if (cur.explicit) {
                int s = b.length();
                b.append("  E");
                b.setSpan(new ForegroundColorSpan(t.fgDim), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            npTitle.setText(b);
            npBy.setText(cur.episode() ? cur.artist : getString(R.string.radio_by, cur.artist.isEmpty() ? "?" : cur.artist));
            npAlbum.setText(!cur.album.isEmpty() ? cur.album : cur.episode() ? cur.date : "Omarchy Radio · community playlist");
            npLink.setText(cur.page.replaceFirst("^https://", ""));
            loadLyrics(cur);
        }
        if (full) updateLyrics(true);
        for (int i = 0; i < listBox.getChildCount(); i++) {
            View v = listBox.getChildAt(i);
            if (v instanceof LinearLayout) styleRow((LinearLayout) v, v.hasFocus());
        }
        styleControls();
        hint.setText(cur == null ? getString(R.string.radio_keys) : (player.playing() ? "♫ " : "‖ ") + cur.line() + "   ·   " + getString(R.string.radio_keys));
        if (demo) updateDemo();
        updateTime();
    }

    private void updateTime() {
        RadioCatalog.Track cur = player.current();
        long pos = player.position(), dur = player.duration();
        int w = 26;
        double f = dur > 0 ? Math.min(1, pos / (double) dur) : 0;
        int fill = (int) Math.round(f * w);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < w; i++) s.append(i < fill ? '█' : '░');
        npBar.setText(cur == null ? "" : s.toString());
        npTime.setText(cur == null ? "" : CastUi.clock(pos / 1000.0) + " / " + (dur > 0 ? CastUi.clock(dur / 1000.0) : "--:--")
                + (player.shuffle() ? "  ·  shuffle" : "") + (player.repeat() == RadioPlayer.REPEAT_ONE ? "  ·  repeat one" : ""));
        updateLyrics(false);
        if (listBox != null && player.playing()) {
            for (int i = 0; i < listBox.getChildCount(); i++) {
                View v = listBox.getChildAt(i);
                if (v instanceof LinearLayout && v.getTag() instanceof RadioCatalog.Track && cur != null && ((RadioCatalog.Track) v.getTag()).url.equals(cur.url)) {
                    styleRow((LinearLayout) v, v.hasFocus());
                }
            }
        }
    }

    private void updateLyrics(boolean force) {
        RadioCatalog.Track cur = player.current();
        Theme t = Theme.cur();
        if (cur == null || cur.episode()) {
            if (force || sheetLine != -3) lyricsView.setText(cur != null && !cur.about.isEmpty() ? cur.about : "");
            sheetLine = -3;
            return;
        }
        if (sheet.isEmpty()) {
            if (force || sheetLine != -4) lyricsView.setText(getString(R.string.radio_no_lyrics));
            lyricsView.setTextColor(t.fgDim);
            sheetLine = -4;
            return;
        }
        int at = RadioCatalog.lineAt(sheet, player.position());
        if (!force && at == sheetLine) return;
        sheetLine = at;
        lyricsView.setTextColor(t.fg);
        SpannableStringBuilder b = new SpannableStringBuilder();
        int from = at < 0 ? 0 : Math.max(0, at - 2), to = Math.min(sheet.size(), from + (at < 0 ? 40 : 9));
        for (int i = from; i < to; i++) {
            int s = b.length();
            b.append(sheet.get(i).text.isEmpty() ? " " : sheet.get(i).text).append('\n');
            b.setSpan(new ForegroundColorSpan(i == at ? t.fgBright : at < 0 ? t.fg : t.fgDim), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        lyricsView.setText(b);
    }

    // ---- screensaver music ----------------------------------------------------------------------------

    private final Runnable startSaverMusicAgain = this::startSaverMusic;

    /**
     * The fractals screensaver must be heard, not only shown: resume a current track (volume 1, audio focus
     * held again) or start one from the catalogue if nothing is on. Pulse stays the default screensaver.
     */
    private void startSaverMusic() {
        if (!saver || isFinishing()) return;
        player.ensureAudible();
        if (player.current() != null) return;
        java.util.List<RadioCatalog.Track> list = shownSongs();
        if (list.isEmpty()) return;
        player.play(list, player.shuffle() ? (int) (Math.random() * list.size()) : 0);
    }

    // ---- demo mode ------------------------------------------------------------------------------------

    private void setDemo(boolean on) {
        demo = on;
        main.animate().alpha(on ? 0f : 1f).setDuration(600).withEndAction(() -> main.setVisibility(demo ? View.INVISIBLE : View.VISIBLE)).start();
        if (!on) main.setVisibility(View.VISIBLE);
        if (dimmer != null) dimmer.animate().alpha(on ? 0f : 1f).setDuration(600).start();
        if (fractal != null) fractal.setDim(1f);
        scroller.setVisibility(on ? View.VISIBLE : View.GONE);
        demoCorner.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) startDemoVisuals();
        else {
            if (!RadioPlayer.wantLiveNormal(this)) {
                pauseLiveForPlainUi();
                hideSlides();
                Theme th = Theme.cur();
                root.setBackgroundColor(0xFF000000 | (th.bgDarker & 0xFFFFFF));
                plainBackdrop = true;
            }
            View f = getCurrentFocus();
            if (f == null || !f.isShown()) {
                if (listBox.getChildCount() > 0) listBox.getChildAt(0).requestFocus();
            }
        }
    }

    private void updateDemo() {
        RadioCatalog.Track cur = player.current();
        String what = cur != null ? (cur.episode() ? "OMARCHY STORIES  ·  " + cur.title : cur.title.toUpperCase(Locale.ROOT) + "  BY  " + cur.artist.toUpperCase(Locale.ROOT))
                : "RADIOMARCHY  ·  OMARCHY RADIO ON YOUR TV";
        scroller.setText("★  NOW PLAYING  ·  " + what + "  ·  RADIO.OMARCHY.ORG  ·  EVERY SONG A PULL REQUEST FROM THE OMARCHY COMMUNITY  ·  "
                + "FRACTALS: MANDELBROT  JULIA  PLASMA  TUNNEL  KALISET  ·  GREETZ TO EVERY DOTFILE TWEAKER OUT THERE  ★          ");
        String fx = fractal != null ? (FractalView.SCENES[fractal.scene()] + " · " + fractal.quality())
                : (slides != null && slides.getVisibility() == View.VISIBLE ? "slides" : "plain");
        demoCorner.setText("RADIOMARCHY  ·  " + fx + (saver ? "" : "\n◀ ▶ fractal  ·  ▲ ▼ colours  ·  BACK"));
    }

    // ---- keys -----------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return media(kc) || super.dispatchKeyEvent(e);
        lastKey = SystemClock.elapsedRealtime();
        if (mediaDown(kc)) return true;
        if (saver) {   // the screensaver: any other key closes it, and is swallowed
            finish();
            overridePendingTransition(0, 0);
            return true;
        }
        if (demo) {
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (fractal != null) fractal.nextScene(-1);
                    h.postDelayed(this::updateDemo, 100);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (fractal != null) fractal.nextScene(1);
                    h.postDelayed(this::updateDemo, 100);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    palette = (palette + (kc == KeyEvent.KEYCODE_DPAD_UP ? 1 : FractalView.PALETTES.length - 1)) % FractalView.PALETTES.length;
                    player.prefs().edit().putInt("palette", palette).apply();
                    if (fractal != null) fractal.setPalette(palette);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    control(1);
                    return true;
                default:
                    setDemo(false);
                    return true;
            }
        }
        switch (kc) {
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_D:
                setDemo(true);
                return true;
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_S:
                player.setShuffle(!player.shuffle());
                return true;
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_R:
                player.cycleRepeat();
                return true;
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_E:
                showExplicit = !showExplicit;
                player.prefs().edit().putBoolean("explicit", showExplicit).apply();
                fillList(false);
                return true;
            default:
                break;
        }
        if (kc >= KeyEvent.KEYCODE_1 && kc <= KeyEvent.KEYCODE_5) {
            int s = kc - KeyEvent.KEYCODE_1;
            if (s == SRC_DEMO) setDemo(true);
            else {
                selectSource(s, true);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean media(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return true;
            default:
                return false;
        }
    }

    private boolean mediaDown(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: control(1); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: if (!player.playing()) control(1); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: player.pause(); return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP: player.advance(1, true); return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN: player.advance(-1, true); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: player.seekBy(15000); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: player.seekBy(-15000); return true;
            default: return false;
        }
    }

    @Override
    public void onBackPressed() {
        if (demo && !saver) {
            setDemo(false);
            return;
        }
        super.onBackPressed();
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private LinearLayout pane(Theme t, String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.withAlpha(t.bg, 0.80f));
        bg.setStroke(dp(2), t.activeBorder);
        box.setBackground(bg);
        box.addView(label(title, 12, t.accent, true));
        return box;
    }

    private static boolean isInside(View v, ViewGroup g) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == g) return true;
        return false;
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

    // ---- the live spectrum ----------------------------------------------------------------------------

    /** 16 bars, the theme's accent to cyan, read from the music each frame (60 fps only while it is on screen). */
    private final class SpectrumView extends View {
        final float[] bars = new float[AudioBands.BARS];
        private final float[] peak = new float[AudioBands.BARS];
        private final AudioBands.Frame f = new AudioBands.Frame();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SpectrumView(Context c) {
            super(c);
        }

        private long lastDraw;

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.elapsedRealtime();
            long period = 1000L / Math.max(1, RadioPlayer.uiFps(getContext()));
            if (lastDraw != 0 && now - lastDraw < period) {
                if (isShown()) postInvalidateDelayed(period - (now - lastDraw));
                return;
            }
            lastDraw = now;
            Theme t = Theme.cur();
            boolean live = player.frame(f) && player.playing();
            int n = AudioBands.BARS;
            float w = getWidth(), hh = getHeight(), gap = dp(3), bw = (w - gap * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                float v = live ? f.bars[i] : bars[i] * 0.9f;
                bars[i] = v;
                peak[i] = Math.max(peak[i] - 0.012f, v);
                float x = i * (bw + gap), top = hh * (1 - Math.max(0.03f, v));
                p.setColor(blend(t.accent, t.cyan, i / (float) (n - 1)));
                c.drawRect(x, top, x + bw, hh, p);
                p.setColor(t.fgBright);
                float py = hh * (1 - peak[i]);
                c.drawRect(x, py - dp(2), x + bw, py, p);
            }
            if (isShown()) postInvalidateDelayed(1000L / Math.max(1, RadioPlayer.uiFps(getContext())));
        }
    }

    private static int blend(int a, int b, float k) {
        int r = (int) (((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k), g = (int) (((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k),
                bl = (int) ((a & 255) * (1 - k) + (b & 255) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ---- the 90s sine scroller --------------------------------------------------------------------------

    /** Big letters riding a sine wave right to left, bouncing on the bass, each one in the palette's colours. */
    private final class ScrollerView extends View {
        private String text = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final AudioBands.Frame f = new AudioBands.Frame();
        private float x;
        private long last;

        ScrollerView(Context c) {
            super(c);
            p.setTypeface(monoBold);
            p.setShadowLayer(dp(4), 0, dp(2), 0xC0000000);
        }

        void setText(String s) {
            if (!s.equals(text)) text = s;
        }

        @Override
        protected void onDraw(Canvas c) {
            Theme t = Theme.cur();
            long now = SystemClock.elapsedRealtime();
            long period = 1000L / Math.max(1, RadioPlayer.uiFps(getContext()));
            if (last != 0 && now - last < period) {
                if (isShown()) postInvalidateDelayed(period - (now - last));
                return;
            }
            float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1000f);
            last = now;
            player.frame(f);
            float size = getHeight() * 0.42f;
            p.setTextSize(size);
            float cw = p.measureText("M"), total = cw * text.length();
            x -= dt * cw * 6.5f;
            if (x < -total) x += total;
            float base = getHeight() * 0.62f, amp = getHeight() * (0.13f + 0.12f * f.bass), sec = now / 1000f;
            int first = (int) Math.max(0, Math.floor(-x / cw)) - 1;
            for (int k = first; k < first + getWidth() / cw + 3; k++) {
                int i = ((k % text.length()) + text.length()) % text.length();
                float lx = x + k * cw;
                if (lx < -cw || lx > getWidth()) continue;
                float y = base + (float) Math.sin(lx / getWidth() * 6.3f + sec * 3.1f) * amp;
                p.setColor(blend(t.accent, t.cyan, 0.5f + 0.5f * (float) Math.sin(lx / getWidth() * 3.1f + sec)));
                c.drawText(String.valueOf(text.charAt(i)), lx, y, p);
            }
            if (isShown()) postInvalidateDelayed(1000L / Math.max(1, RadioPlayer.uiFps(getContext())));
        }
    }
}
