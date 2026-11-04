package com.omarchytv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;

/**
 * v2.14 OmarchyTV Arcade on Android TV: the same games as LG/Samsung (assets/games, identical copies of
 * webos/app/games) in a full-screen WebView, inside the Retro CRT set when that plugin is on. Only assets/games/,
 * the themes and the font load; no network. The remote's D-pad, OK and Back go straight to the game as key names
 * (a Bluetooth gamepad's D-pad and A button too), so nothing scrolls the page and nothing leaves the launcher.
 * Records stay in the WebView's local storage. RICE WARS may re-theme the Omarchy PC ("arcadePcTheme", off by
 * default); the PC gets the launcher's theme back when the arcade closes. If the WebView renderer dies the game is
 * reloaded once on a fresh WebView; a second death closes the arcade with a toast, it never takes the launcher down.
 */
public final class ArcadeActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private FrameLayout root;
    private String url;
    /** Renderer deaths so far: one silent reload, the second one leaves the arcade. */
    private int rendererCrashes;
    private boolean pcTouched;

    static void open(Context c) {
        c.startActivity(new Intent(c, ArcadeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        LauncherConfig cfg = LauncherConfig.get(this);
        url = "https://" + HOST + "/assets/games/index.html#" + Uri.encode(params(cfg).toString());
        CrtOverlay.attach(this, root, cfg.pluginRetroCrt);   // the set around the screen, as before
        if (!attachWeb()) finish();   // no WebView on this TV
    }

    /**
     * A new WebView on the arcade page: once from onCreate, and once more after the renderer dies. false when this TV
     * has no usable WebView.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private boolean attachWeb() {
        WebView w;
        try {
            w = new WebView(this);
        } catch (Throwable e) {   // no WebView on this TV
            return false;
        }
        w.setBackgroundColor(Color.BLACK);
        w.setFocusable(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // the high scores
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        final android.content.res.AssetManager assets = getAssets();
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String p = u.getPath();
                boolean ok = HOST.equals(u.getHost()) && p != null && !p.contains("..")
                        && (p.startsWith("/assets/games/") || p.equals("/assets/omarchy_themes.json") || p.startsWith("/assets/fonts/"));
                if (!ok) return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                try {
                    InputStream in = assets.open(p.substring("/assets/".length()));
                    String mime = p.endsWith(".js") ? "text/javascript" : p.endsWith(".html") ? "text/html"
                            : p.endsWith(".json") ? "application/json" : p.endsWith(".ttf") ? "font/ttf" : "application/octet-stream";
                    return new WebResourceResponse(mime, "utf-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return true;   // never navigate anywhere
            }

            @android.annotation.TargetApi(26)
            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                rendererGone(v);
                return true;
            }
        });
        w.addJavascriptInterface(new Bridge(), "OmarchyAndroid");
        web = w;
        root.addView(w, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        w.loadUrl(url);
        return true;
    }

    /**
     * The WebView renderer crashed or the TV killed it for memory (1-2 GB sets): without onRenderProcessGone the whole
     * launcher dies with it. Take the dead WebView off the screen and free it, reload once on a brand new one (outside
     * the callback), and on a second death leave the arcade with a short toast instead of a crash.
     */
    private void rendererGone(WebView v) {
        if (web == v) web = null;
        if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
        try {
            v.removeJavascriptInterface("OmarchyAndroid");
            v.destroy();
        } catch (Throwable ignored) {
        }
        if (rendererCrashes++ > 0) {
            Toast.makeText(this, R.string.arcade_crashed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        root.post(() -> {
            if (isFinishing() || isDestroyed()) return;   // left the arcade meanwhile
            if (!attachWeb()) {   // no WebView left to try: say so and go back to the launcher
                Toast.makeText(ArcadeActivity.this, R.string.arcade_crashed, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    /** Language, sound, the PC option and the launcher's theme colours (custom themes from the PC included). */
    private JSONObject params(LauncherConfig cfg) {
        JSONObject j = new JSONObject();
        try {
            Theme t = Theme.cur();
            j.put("lang", java.util.Locale.getDefault().toLanguageTag());
            j.put("sound", cfg.arcadeSound);
            j.put("pc", cfg.arcadePcTheme && OmarchyLink.hasPc());
            JSONObject th = new JSONObject();
            th.put("name", t.name);
            th.put("background", hex(t.bg));
            th.put("foreground", hex(t.fg));
            th.put("accent", hex(t.accent));
            th.put("magenta", hex(t.accent2));
            th.put("red", hex(t.red));
            th.put("green", hex(t.green));
            th.put("yellow", hex(t.yellow));
            th.put("cyan", hex(t.cyan));
            th.put("blue", hex(t.blue));
            th.put("bright_foreground", hex(t.fgBright));
            j.put("theme", th);
        } catch (Exception ignored) {
        }
        return j;
    }

    private static String hex(int c) {
        return String.format(java.util.Locale.ROOT, "#%06x", c & 0xffffff);
    }

    private String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_SPACE: return "ok";
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B: return "back";
            default: return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String k = keyName(e.getKeyCode());
        if (k == null || web == null) return super.dispatchKeyEvent(e);   // Home, volume, power: as always
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            web.evaluateJavascript("window.OmarchyArcadeHost&&OmarchyArcadeHost.key('" + k + "')", v -> {
                if ("back".equals(k) && !"true".equals(v)) finish();   // the page never loaded: Back still leaves
            });
        }
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.evaluateJavascript("window.OmarchyArcadeHost&&OmarchyArcadeHost.pause()", null);
            web.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (pcTouched) themeBack();
        if (web != null) {
            web.removeJavascriptInterface("OmarchyAndroid");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void themeBack() {
        pcTouched = false;
        if (OmarchyLink.hasPc()) OmarchyLink.themeSet(Theme.cur().name);
    }

    /** Called from assets/games/index.html. */
    private final class Bridge {
        @JavascriptInterface
        public void exit() {
            runOnUiThread(ArcadeActivity.this::finish);
        }

        @JavascriptInterface
        public void pcTheme(String name) {
            if (!LauncherConfig.get(ArcadeActivity.this).arcadePcTheme || !OmarchyLink.hasPc()) return;
            if (name == null || name.isEmpty()) {
                themeBack();
                return;
            }
            pcTouched = true;
            OmarchyLink.themeSet(name);
        }
    }
}
