package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.File;

/**
 * The launcher's cast player (PROTOCOL.md §6 Cast, kinds video/audio): Media3 ExoPlayer on a SurfaceView, the
 * title and sender in an Omarchy pane, D-pad play/pause (OK) and seek (left/right), Back stops.
 * {@code live:true} (screen mirroring): a ~0.5 s buffer, no seek, no "ended" (the stream just stops).
 * The screen stays on only while playing (window flag, no wakelock).
 */
@OptIn(markerClass = UnstableApi.class)
public final class CastPlayerActivity extends Activity implements CastRouter.Surface {

    private static final long TICK_MS = 10_000, UI_MS = 1_000, OVERLAY_MS = 4_000, PAUSE_SAVER_MS = 8_000;

    private ExoPlayer player;
    private String id, kind, lastState, mediaTitle, mediaUrl;
    private boolean live, replaced, done;
    private FrameLayout root;
    private SurfaceView video;
    private LinearLayout overlay;
    private AsciiSaverView pauseSaver;
    private TextView timeView, stateView;
    private View progress;
    private FrameLayout progressTrack;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) {
                report("playing", true);
                h.postDelayed(this, TICK_MS);
            }
        }
    };
    private final Runnable ui = new Runnable() {
        @Override
        public void run() {
            updateTime();
            if (player != null && overlay.getVisibility() == View.VISIBLE) h.postDelayed(this, UI_MS);
        }
    };
    private final Runnable hideOverlay = () -> {
        if (player != null && player.isPlaying()) overlay.setVisibility(View.GONE);
    };
    private final Runnable showPauseSaver = () -> {
        if (player != null && !player.isPlaying() && pauseSaver != null) {
            double posS = player.getCurrentPosition() / 1000.0;
            double durS = player.getDuration() > 0 ? player.getDuration() / 1000.0 : -1;
            String label = "‖ PAUSED · " + (mediaTitle != null ? mediaTitle : "") + " · " + CastUi.clock(posS)
                    + (durS > 0 ? " / " + CastUi.clock(durS) : "") + " · OK to resume";
            pauseSaver.setStatusOverride(label);
            pauseSaver.setVisibility(View.VISIBLE);
            pauseSaver.start();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme t = Theme.cur();
        id = getIntent().getStringExtra(CastRouter.EXTRA_ID);
        kind = getIntent().getStringExtra(CastRouter.EXTRA_KIND);
        live = getIntent().getBooleanExtra(CastRouter.EXTRA_LIVE, false);
        String title = getIntent().getStringExtra(CastRouter.EXTRA_TITLE);
        mediaTitle = title;
        AmbilightController.restoreFollowVideo(this);
        String from = getIntent().getStringExtra(CastRouter.EXTRA_FROM);
        String mime = getIntent().getStringExtra(CastRouter.EXTRA_MIME);
        if (id == null) {
            finish();
            return;
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000 | (t.bgDarker & 0xFFFFFF));
        video = new SurfaceView(this);
        root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        boolean audio = "audio".equals(kind);
        if (audio) {
            TextView note = CastUi.label(this, "♪", 96, t.accent);
            root.addView(note, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }

        // Title pane: "▶ title" / "from <pc>", then the progress line and time (or "● live").
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackground(CastUi.pane(this));
        int p = CastUi.dp(this, 14);
        overlay.setPadding(p + CastUi.dp(this, 4), p, p + CastUi.dp(this, 4), p);
        TextView name = nameView = CastUi.label(this, title == null || title.isEmpty() ? getString(audio ? R.string.cast_audio : R.string.cast_video) : title, 20, t.fgBright);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        overlay.addView(name);
        if (from != null && !from.isEmpty()) {
            TextView f = CastUi.label(this, getString(R.string.cast_from, from), 13, t.fgDim);
            f.setPadding(0, CastUi.dp(this, 6), 0, 0);
            overlay.addView(f);
        }
        progressTrack = new FrameLayout(this);
        progressTrack.setBackgroundColor(t.selection);
        progress = new View(this);
        progress.setBackgroundColor(t.accent);
        progressTrack.addView(progress, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CastUi.dp(this, 3));
        tp.topMargin = CastUi.dp(this, 12);
        overlay.addView(progressTrack, tp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        stateView = CastUi.label(this, "", 13, t.accent);
        timeView = CastUi.label(this, "", 13, t.fg);
        row.addView(stateView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(timeView);
        row.setPadding(0, CastUi.dp(this, 8), 0, 0);
        overlay.addView(row);
        if (live) progressTrack.setVisibility(View.GONE);
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(CastUi.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        op.setMargins(CastUi.dp(this, 56), 0, 0, CastUi.dp(this, 40));
        root.addView(overlay, op);
        pauseSaver = new AsciiSaverView(this, null);
        pauseSaver.setAlpha(0.92f);
        pauseSaver.setVisibility(View.GONE);
        root.addView(pauseSaver, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (mime != null && (mime.contains("mpegurl") || mime.contains("dash+xml"))) {
            fail(getString(R.string.cast_no_adaptive));
            return;
        }
        Uri uri;
        String sha = getIntent().getStringExtra(CastRouter.EXTRA_FILE);
        String url = getIntent().getStringExtra(CastRouter.EXTRA_URL);
        mediaUrl = url;
        if (sha != null) {
            File f = new File(CastRouter.dir(this), sha);
            if (!f.isFile()) {
                fail("file gone");
                return;
            }
            uri = Uri.fromFile(f);
        } else if (url != null) {
            uri = Uri.parse(url);
        } else {
            finish();
            return;
        }

        DefaultLoadControl load = live
                ? new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 2_000, 250, 500)   // min, max, to start, after a rebuffer
                    .setPrioritizeTimeOverSizeThresholds(true).build()
                : new DefaultLoadControl();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent("OmarchyTV")
                .setConnectTimeoutMs(10_000).setReadTimeoutMs(live ? 5_000 : 15_000).setAllowCrossProtocolRedirects(false);
        // PC one-time URLs are plain http on the LAN; targetSdk 34 forbids cleartext in HttpURLConnection.
        androidx.media3.datasource.DataSource.Factory src = LanHttpDataSource.allowed(uri)
                ? new LanHttpDataSource.Factory(live ? 5_000 : 15_000) : new DefaultDataSource.Factory(this, http);
        player = new ExoPlayer.Builder(this).setLoadControl(load)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(src)).build();
        player.setVideoSurfaceView(video);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_BUFFERING) report("buffering", false);
                else if (s == Player.STATE_ENDED) {
                    report(live ? "stopped" : "ended", false);
                    showOverlay(false);
                    // v2.13 up-next queue: advance to the next item instead of stopping.
                    String[] q = listUrls();
                    if (q != null && "queue".equals(getIntent().getStringExtra(EXTRA_LIST_MODE)) && curIdx() + 1 < q.length) {
                        h.post(() -> tune(curIdx() + 1));
                    }
                } else if (s == Player.STATE_READY && !player.getPlayWhenReady()) report("paused", false);
                updateTime();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (playing) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    h.removeCallbacks(showPauseSaver);
                    if (pauseSaver != null && pauseSaver.getVisibility() == View.VISIBLE) {
                        pauseSaver.stop();
                        pauseSaver.setVisibility(View.GONE);
                    }
                    report("playing", false);
                    h.removeCallbacks(tick);
                    h.postDelayed(tick, TICK_MS);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    h.removeCallbacks(tick);
                    if (player.getPlaybackState() == Player.STATE_READY) {
                        report("paused", false);
                        h.removeCallbacks(showPauseSaver);
                        h.postDelayed(showPauseSaver, PAUSE_SAVER_MS);
                    }
                }
                updateTime();
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                playerError(e);
            }

            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                fit(vs);
            }

            @Override
            public void onCues(androidx.media3.common.text.CueGroup cg) {
                showCues(cg);
            }
        });
        player.setMediaItem(mediaItem(uri, getIntent().getStringExtra(CastRouter.EXTRA_SUB)));
        double pos = getIntent().getDoubleExtra(CastRouter.EXTRA_POS, 0);
        if (!live && pos > 0) player.seekTo((long) (pos * 1000));
        player.setPlayWhenReady(true);
        player.prepare();
        CastRouter.attach(this);
        showOverlay(true);
    }

    private TextView subs;

    /** A MediaItem with an optional sideloaded .srt / .vtt track, selected by default. */
    static MediaItem mediaItem(Uri uri, String sub) {
        if (sub == null || sub.isEmpty()) return MediaItem.fromUri(uri);
        String path = Uri.parse(sub).getPath();
        String p = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        String mime = p.endsWith(".vtt") ? androidx.media3.common.MimeTypes.TEXT_VTT
                : androidx.media3.common.MimeTypes.APPLICATION_SUBRIP;
        MediaItem.SubtitleConfiguration sc = new MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                .setMimeType(mime).setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build();
        return new MediaItem.Builder().setUri(uri).setSubtitleConfigurations(java.util.Collections.singletonList(sc)).build();
    }

    /** Render subtitle cues as plain outlined text at the bottom (we draw on a SurfaceView, no PlayerView). */
    private void showCues(androidx.media3.common.text.CueGroup cg) {
        StringBuilder sb = new StringBuilder();
        for (androidx.media3.common.text.Cue c : cg.cues) {
            if (c.text == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.text);
        }
        if (subs == null) {
            if (sb.length() == 0) return;
            subs = new TextView(this);
            subs.setTextSize(26);
            subs.setTextColor(0xFFFFFFFF);
            subs.setShadowLayer(6, 0, 0, 0xFF000000);
            subs.setGravity(Gravity.CENTER);
            subs.setPadding(48, 8, 48, 8);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = 64;
            ((ViewGroup) root).addView(subs, lp);
        }
        subs.setText(sb);
        subs.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Letterbox the SurfaceView to the video's aspect ratio. */
    private void fit(VideoSize vs) {
        if (vs.width == 0 || vs.height == 0 || root.getWidth() == 0) return;
        float ar = vs.width * vs.pixelWidthHeightRatio / vs.height;
        int w = root.getWidth(), hh = root.getHeight();
        int vw = w, vh = Math.round(w / ar);
        if (vh > hh) {
            vh = hh;
            vw = Math.round(hh * ar);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        lp.width = vw;
        lp.height = vh;
        video.setLayoutParams(lp);
    }

    private void showOverlay(boolean autoHide) {
        overlay.setVisibility(View.VISIBLE);
        h.removeCallbacks(ui);
        h.post(ui);
        h.removeCallbacks(hideOverlay);
        if (autoHide && !"audio".equals(kind)) h.postDelayed(hideOverlay, OVERLAY_MS);
    }

    private void updateTime() {
        if (player == null) return;
        Theme t = Theme.cur();
        boolean playing = player.isPlaying();
        int s = player.getPlaybackState();
        if (live) {
            stateView.setText(playing ? "● live" : s == Player.STATE_BUFFERING ? "… live" : "‖ live");
            stateView.setTextColor(playing ? t.red : t.fgDim);
            timeView.setText("");
            return;
        }
        stateView.setText(s == Player.STATE_BUFFERING ? "…" : s == Player.STATE_ENDED ? "■" : playing ? "▶" : "‖");
        stateView.setTextColor(t.accent);
        double posS = player.getCurrentPosition() / 1000.0, durS = player.getDuration() > 0 ? player.getDuration() / 1000.0 : -1;
        timeView.setText(CastUi.clock(posS) + " / " + CastUi.clock(durS));
        int w = progressTrack.getWidth();
        ViewGroup.LayoutParams lp = progress.getLayoutParams();
        lp.width = durS > 0 ? (int) Math.min(w, w * posS / durS) : 0;
        progress.setLayoutParams(lp);
    }

    /** Sends a state to the PC on change; {@code force} = the 10 s "still playing" tick. */
    private void report(String state, boolean force) {
        if (done || replaced) return;
        if (!force && state.equals(lastState)) return;
        lastState = state;
        double pos = -1, dur = -1;
        if (player != null && !live) {
            pos = player.getCurrentPosition() / 1000.0;
            if (player.getDuration() > 0) dur = player.getDuration() / 1000.0;
        }
        OmarchyLink.castState(id, state, pos, dur, null);
        if ("stopped".equals(state) || "ended".equals(state) && live) done = true;
    }

    /** HTTP status of a source error (InvalidResponseCodeException anywhere in the cause chain), else -1. */
    private static int httpStatus(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c).responseCode;
            }
            if (c.getCause() == c) break;
        }
        return -1;
    }

    /**
     * A YouTube URL that answers 4xx (signed googlevideo stream expired, or a page that is not a stream) goes to the
     * YouTube app / embed when its id is known; every other error is a readable sentence, not "io_bad_http_status".
     */
    private void playerError(PlaybackException e) {
        int status = httpStatus(e);
        String code = e.getErrorCodeName().replace("ERROR_CODE_", "").toLowerCase(java.util.Locale.ROOT);
        String url = mediaUrl;
        if (listIdx >= 0 && listUrls() != null && listIdx < listUrls().length) url = listUrls()[listIdx];
        if (status < 0 && YoutubeLaunch.youtubeOrigin(url)) status = 403;   // e.g. a watch page parsed as media
        if (status >= 0) {
            String act = YoutubeLaunch.onHttpError(url, status);
            if ("app".equals(act)) {
                android.widget.Toast.makeText(this, getString(R.string.cast_yt_expired_app, status), android.widget.Toast.LENGTH_LONG).show();
                if (!done && !replaced) OmarchyLink.castState(id, "error", -1, -1, "youtube_" + status + "_app");
                done = true;
                release();
                YoutubeEmbedActivity.openInYoutubeApp(this, YoutubeLaunch.idOf(url), getIntent().getStringExtra(CastRouter.EXTRA_TITLE));
                finish();
                return;
            }
            fail("expired".equals(act) ? getString(R.string.cast_yt_expired, status) : getString(R.string.cast_http_refused, status));
            return;
        }
        fail(getString(R.string.cast_cant_play, code));
    }

    private void fail(String why) {
        if (!done && !replaced) OmarchyLink.castState(id, "error", -1, -1, why);
        done = true;
        if (stateView != null) {
            stateView.setText("✗ " + why);
            stateView.setTextColor(Theme.cur().red);
            overlay.setVisibility(View.VISIBLE);
        }
        release();
    }

    // ---- v2.13: channel list (IPTV zapping) / up-next queue -------------------------------------
    static final String EXTRA_LIST_URLS = "omarchy.list.urls", EXTRA_LIST_TITLES = "omarchy.list.titles",
            EXTRA_LIST_INDEX = "omarchy.list.index", EXTRA_LIST_MODE = "omarchy.list.mode",
            EXTRA_LIST_SUBS = "omarchy.list.subs";
    private TextView nameView;
    private int listIdx = -1, prevIdx = -1;

    private String[] listUrls() {
        return getIntent().getStringArrayExtra(EXTRA_LIST_URLS);
    }

    private boolean iptvMode() {
        return "iptv".equals(getIntent().getStringExtra(EXTRA_LIST_MODE));
    }

    /** Switches the running player to entry {@code i} of the list (wraps). Returns false without a list. */
    private boolean tune(int i) {
        String[] urls = listUrls();
        String[] titles = getIntent().getStringArrayExtra(EXTRA_LIST_TITLES);
        if (urls == null || urls.length == 0 || player == null) return false;
        if (listIdx < 0) listIdx = getIntent().getIntExtra(EXTRA_LIST_INDEX, 0);
        int n = ((i % urls.length) + urls.length) % urls.length;
        if (n != listIdx) prevIdx = listIdx;
        listIdx = n;
        mediaUrl = urls[n];
        mediaTitle = titles != null && n < titles.length ? titles[n] : urls[n];
        if (nameView != null) nameView.setText((iptvMode() ? "CH " + (n + 1) + " · " : "") + mediaTitle);
        if (iptvMode()) com.omarchytv.home.iptv.IptvStore.tuned(this, mediaUrl);
        String[] subsArr = getIntent().getStringArrayExtra(EXTRA_LIST_SUBS);
        player.setMediaItem(mediaItem(Uri.parse(mediaUrl), subsArr != null && n < subsArr.length ? subsArr[n] : null));
        player.prepare();
        player.play();
        showOverlay(true);
        return true;
    }

    /** v2.13: Cinemarchy ":pause" (main thread). */
    void togglePlayPause() {
        if (player == null) return;
        if (player.getPlayWhenReady()) castControl("pause", 0);
        else castControl("play", 0);
        showOverlay(true);
    }

    private int curIdx() {
        return listIdx >= 0 ? listIdx : getIntent().getIntExtra(EXTRA_LIST_INDEX, 0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        if (listUrls() != null) {
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_CHANNEL_UP || kc == KeyEvent.KEYCODE_PAGE_UP || kc == KeyEvent.KEYCODE_MEDIA_NEXT) {
                return tune(curIdx() + 1) || super.dispatchKeyEvent(e);
            }
            if (kc == KeyEvent.KEYCODE_CHANNEL_DOWN || kc == KeyEvent.KEYCODE_PAGE_DOWN || kc == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                return tune(curIdx() - 1) || super.dispatchKeyEvent(e);
            }
            if (kc == KeyEvent.KEYCODE_LAST_CHANNEL && prevIdx >= 0) {
                return tune(prevIdx);
            }
        }
        if (pauseSaver != null && pauseSaver.getVisibility() == View.VISIBLE) {
            pauseSaver.stop();
            pauseSaver.setVisibility(View.GONE);
            if (player != null && !player.getPlayWhenReady()) {
                player.setPlayWhenReady(true);
                showOverlay(true);
                return true;
            }
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player != null) player.setPlayWhenReady(!player.getPlayWhenReady());
                showOverlay(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                castControl("play", 0);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                castControl("pause", 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-10_000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(30_000);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showOverlay(true);
                return true;
            case KeyEvent.KEYCODE_S:
                if (player != null && player.isPlaying()) player.pause();
                h.removeCallbacks(showPauseSaver);
                showPauseSaver.run();
                return true;
            case KeyEvent.KEYCODE_H:
                if (player != null) {
                    double posS = player.getCurrentPosition() / 1000.0;
                    OmarchyLink.handoffToPc(mediaTitle, mediaUrl, posS);
                    android.widget.Toast.makeText(this, "Handoff to PC mpv @ " + CastUi.clock(posS), android.widget.Toast.LENGTH_SHORT).show();
                    stopByUser();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                stopByUser();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void seekBy(long ms) {
        if (player == null || live) return;
        long d = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + ms);
        if (d > 0) to = Math.min(d, to);
        player.seekTo(to);
        showOverlay(true);
    }

    @Override
    public void onBackPressed() {
        stopByUser();
    }

    private void stopByUser() {
        report("stopped", false);
        release();
        finish();
    }

    // ---- CastRouter.Surface (control from the PC)

    @Override
    public String castId() {
        return id;
    }

    @Override
    public void castControl(String action, double position) {
        if (player == null) {
            if ("stop".equals(action)) stopByUser();
            return;
        }
        switch (action) {
            case "play":
                if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
                player.play();
                break;
            case "pause":
                player.pause();
                break;
            case "seek":
                if (!live) player.seekTo((long) (position * 1000));
                break;
            case "stop":
                done = true;   // the server already reported "stopped"
                release();
                finish();
                return;
            default:
        }
        showOverlay(true);
    }

    @Override
    public void castReplaced() {
        replaced = true;
        release();
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Home, another app or the screen going off: a cast never keeps playing behind the user's back.
        if (!isFinishing()) {
            report("stopped", false);
            release();
            finish();
        }
    }

    private void release() {
        h.removeCallbacksAndMessages(null);
        if (pauseSaver != null) pauseSaver.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (!done && !replaced) report("stopped", false);
        if (live && !replaced) OmarchyLink.screenMirror(false);   // v2.9: Back ends "Schermo PC" on the PC too
        release();
        CastRouter.detach(this);
        super.onDestroy();
    }
}
