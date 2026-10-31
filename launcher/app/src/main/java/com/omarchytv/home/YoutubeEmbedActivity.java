package com.omarchytv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v2.13 "Play inside Cinemarchy": the official YouTube IFrame Player API in a WebView, with a chapter rail
 * that only calls the player's own seekTo/playVideo/pauseVideo. No stream extraction, no download. If the
 * video can't be embedded (player errors 101/150/153) or the page fails, the YouTube app opens instead.
 * Off by default (Cinemarchy ":embed" or Settings › Cinemarchy turns it on).
 */
public class YoutubeEmbedActivity extends Activity {
    static final String EXTRA_ID = "yt_id";
    static final String EXTRA_TITLE = "yt_title";
    /** Opened because no YouTube app took the video: its own fallback goes to the WebApp. */
    static final String EXTRA_NO_APP = "yt_no_app";
    /** YouTube identifies embedders by HTTP Referer; for an app WebView that is its package as an https origin. */
    static final String ORIGIN = "https://com.omarchytv.home";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private String videoId, title;
    private boolean fellBack;
    private boolean noApp;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    /** True for the player errors that mean "not embeddable here": hand off to the YouTube app. */
    static boolean fallbackError(int code) {
        return code == 101 || code == 150 || code == 153 || code == 152 || code == 5 || code == 2 || code == 100;
    }

    static String html(String id) {
        // id is validated ([A-Za-z0-9_-]{11}) before it gets here.
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style>"
                + "</head><body><div id=p></div><script>"
                + "var player,ready=false;"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:'" + id + "',"
                + "playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,origin:'" + ORIGIN + "'},"
                + "events:{onReady:function(e){ready=true;e.target.playVideo();OTV.ready(Math.round(e.target.getDuration()||0));},"
                + "onError:function(e){OTV.error(e.data|0);}}});}"
                + "function seekPct(p){if(!ready)return;var d=player.getDuration()||0;player.seekTo(Math.floor(d*p/100),true);player.playVideo();}"
                + "function seekBy(s){if(!ready)return;player.seekTo(Math.max(0,(player.getCurrentTime()||0)+s),true);}"
                + "function toggle(){if(!ready)return;player.getPlayerState()==1?player.pauseVideo():player.playVideo();}"
                + "var t=document.createElement('script');t.src='https://www.youtube.com/iframe_api';"
                + "t.onerror=function(){OTV.error(-1);};document.head.appendChild(t);"
                + "</script></body></html>";
    }

    /** The YouTube app on this exact video, falling back to the in-app embed player, then the WebApp. */
    static void openInYoutubeApp(Context c, String id, String title) {
        openInYoutubeApp(c, id, title, true);
    }

    /**
     * YoutubeLaunch.appSteps in order (TV app watch URL, TV app vnd.youtube:, phone app, any handler). The TV app is
     * declared in the manifest's queries, so it is resolved first and a step whose app is missing is skipped
     * instead of ending the chain. allowEmbed is false when the embed player itself is falling back (no loop).
     */
    static void openInYoutubeApp(Context c, String id, String title, boolean allowEmbed) {
        if (!DhhFeed.validId(id)) return;
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (YoutubeLaunch.Step s : YoutubeLaunch.appSteps(id, 0)) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(s.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (s.pkg != null) i.setPackage(s.pkg);
            if (s.uri.startsWith("vnd.youtube:")) i.putExtra("force_fullscreen", true);
            // Only the TV app is visible to resolveActivity on Android 11+; the others are just tried.
            if (YoutubeLaunch.TV.equals(s.pkg) && i.resolveActivity(pm) == null) continue;
            try {
                c.startActivity(i);
                return;
            } catch (RuntimeException ignored) {   // ActivityNotFoundException, SecurityException
            }
        }
        if (allowEmbed) {
            try {
                c.startActivity(new Intent(c, YoutubeEmbedActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_NO_APP, true));
                return;
            } catch (RuntimeException ignored) {
            }
        }
        WebAppActivity.openUrl(c, title == null ? "YouTube" : title, DhhFeed.watchUrl(id));
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        videoId = getIntent().getStringExtra(EXTRA_ID);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        noApp = getIntent().getBooleanExtra(EXTRA_NO_APP, false);
        if (!DhhFeed.validId(videoId)) {
            finish();
            return;
        }
        Theme t = Theme.cur();
        Typeface mono;
        try {
            mono = CastUi.font(this);
        } catch (Exception e) {
            mono = Typeface.MONOSPACE;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF000000);

        // Android 9 TVs throw here while the system WebView updates or is disabled (MissingWebViewPackageException,
        // AndroidRuntimeException): no player page then, the YouTube app instead of a crash.
        try {
            web = new WebView(this);
        } catch (RuntimeException e) {
            web = null;
            openInYoutubeApp(this, videoId, title, false);
            finish();
            return;
        }
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.setBackgroundColor(0xFF000000);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Taps on the YouTube logo / title open the app instead of navigating away inside the player.
                if (r.isForMainFrame()) {
                    fallback(0);
                    return true;
                }
                return false;
            }

            @android.annotation.TargetApi(26)
            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                // The WebView renderer crashed or was killed for memory: without this the whole app dies.
                if (web == v) {
                    ((ViewGroup) v.getParent()).removeView(v);
                    v.destroy();
                    web = null;
                }
                fallback(-4);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "OTV");
        root.addView(web, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(14), dp(16), dp(14), dp(16));
        rail.setBackgroundColor(0xFF000000 | (t.bgDarker & 0xFFFFFF));
        TextView head = text(title == null ? "@dhh37" : title, 14, t.fgBright, mono, true);
        head.setMaxLines(3);
        rail.addView(head);
        status = text("Loading the official YouTube player…", 11, t.fgDim, mono, false);
        status.setPadding(0, dp(6), 0, dp(12));
        rail.addView(status);
        rail.addView(text("─ chapters (seekTo) ─", 11, t.accent, mono, true));
        addAction(rail, t, mono, "1  Start", "seekPct(0)");
        addAction(rail, t, mono, "2  25 %", "seekPct(25)");
        addAction(rail, t, mono, "3  Half way", "seekPct(50)");
        addAction(rail, t, mono, "4  75 %", "seekPct(75)");
        addAction(rail, t, mono, "◀  −30 s", "seekBy(-30)");
        addAction(rail, t, mono, "▶  +30 s", "seekBy(30)");
        addAction(rail, t, mono, "⏯  Play / pause", "toggle()");
        TextView app = addAction(rail, t, mono, "↗  Open in the YouTube app", null);
        app.setOnClickListener(v -> fallback(0));
        TextView note = text("Official embed · YouTube Terms apply", 10, t.fgDim, mono, false);
        note.setPadding(0, dp(12), 0, 0);
        rail.addView(note);
        root.addView(rail, new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        try {
            web.loadDataWithBaseURL(ORIGIN + "/", html(videoId), "text/html", "utf-8", null);
        } catch (RuntimeException e) {   // a WebView that dies while loading (renderer gone): hand off, no crash
            fallback(-3);
            return;
        }
        if (rail.getChildCount() > 3) rail.getChildAt(3).requestFocus();
        // No onReady in 20 s (blocked network, no WebView codecs): hand off rather than show a black box.
        handler.postDelayed(() -> {
            if (!readySeen) fallback(-2);
        }, 20_000);
    }

    private volatile boolean readySeen;

    private final class Bridge {
        @JavascriptInterface
        public void ready(int durationSec) {
            handler.post(() -> {
                readySeen = true;
                if (status != null) status.setText(durationSec > 0
                        ? "Playing · " + (durationSec / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", durationSec % 60)
                        : "Playing");
            });
        }

        @JavascriptInterface
        public void error(int code) {
            handler.post(() -> {
                if (code < 0 || fallbackError(code)) fallback(code);
                else if (status != null) status.setText("Player error " + code);
            });
        }
    }

    private void fallback(int code) {
        if (fellBack || isFinishing()) return;
        fellBack = true;
        if (code != 0) Toast.makeText(this, "Can't play here (" + code + "): opening the YouTube app", Toast.LENGTH_SHORT).show();
        if (noApp) WebAppActivity.openUrl(this, title == null ? "YouTube" : title, DhhFeed.watchUrl(videoId));
        else openInYoutubeApp(this, videoId, title, false);
        finish();
    }

    private void js(String call) {
        if (web != null && call != null) web.evaluateJavascript(call, null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_1: js("seekPct(0)"); return true;
            case KeyEvent.KEYCODE_2: js("seekPct(25)"); return true;
            case KeyEvent.KEYCODE_3: js("seekPct(50)"); return true;
            case KeyEvent.KEYCODE_4: js("seekPct(75)"); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: js("seekBy(-30)"); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: js("seekBy(30)"); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                js("toggle()");
                return true;
            default:
                return super.onKeyDown(keyCode, e);
        }
    }

    @Override
    protected void onPause() {
        js("if(ready)player.pauseVideo()");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private TextView addAction(LinearLayout rail, Theme t, Typeface mono, String label, String call) {
        TextView v = text(label, 13, t.fg, mono, false);
        v.setFocusable(true);
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        style(v, t, false);
        v.setOnFocusChangeListener((x, f) -> style((TextView) x, t, f));
        if (call != null) v.setOnClickListener(x -> js(call));
        rail.addView(v, lp);
        return v;
    }

    private void style(TextView v, Theme t, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? t.selection : Theme.withAlpha(t.bg, 0.6f));
        g.setStroke(dp(focused ? 2 : 1), focused ? t.activeBorder : t.inactiveBorder);
        v.setBackground(g);
        v.setTextColor(focused ? t.fgBright : t.fg);
    }

    private TextView text(String s, int sp, int color, Typeface tf, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(tf, bold ? Typeface.BOLD : Typeface.NORMAL);
        v.setGravity(Gravity.START);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
