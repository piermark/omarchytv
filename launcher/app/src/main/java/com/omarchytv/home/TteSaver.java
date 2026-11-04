package com.omarchytv.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * v2.10 screensaver = omarchy-screensaver: `ttfx --random-effect` looped over screensaver.txt, black, centred.
 * Three levels, never black: (1) the official tte.js package (assets/saver/tte-js, ES modules, 37 effects) served
 * from https://appassets.androidplatform.net by a tiny shouldInterceptRequest handler (ES modules are blocked on
 * file://); (2) the bundled single-file build (assets/saver/tte-bundle.js); (3) the native {@link AsciiSaverView}
 * when the page reports no engine, has not drawn within 3 s, or the TV has no usable WebView. If the renderer process
 * dies (onRenderProcessGone) the screensaver closes and the Home screen comes back, through {@link #onDead}.
 * Console messages and JS errors go to logcat with tag "OmarchyTV".
 */
final class TteSaver {

    static final String TAG = "OmarchyTV";
    private static final String HOST = "appassets.androidplatform.net";
    private static final long DRAW_TIMEOUT_MS = 10000;   // v2.10: the native logo shows meanwhile, so never black

    private final Context ctx;
    private final FrameLayout root;
    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView web;
    private AsciiSaverView fallback;
    /** What to do when the WebView renderer dies: the owner closes the screensaver and returns to Home. */
    Runnable onDead;
    private volatile boolean drawn, stopped;
    /** Test hook (screensaver window intent extras, not exported): one fixed effect, or "bundle"/"native" level. */
    String forceEffect = "", forceMode = "";
    long drawTimeoutMs = DRAW_TIMEOUT_MS;

    TteSaver(Context c) {
        ctx = c;
        root = new FrameLayout(c);
        root.setBackgroundColor(Color.BLACK);
        try {
            web = makeWeb(c);
            root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable e) {   // no WebView provider / disabled WebView package
            Log.w(TAG, "salvaschermo: WebView non disponibile (" + e + "), modalita nativo");
            web = null;
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView makeWeb(Context c) {
        WebView w = new WebView(c);
        w.setBackgroundColor(Color.BLACK);
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        w.setFocusable(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        final String text = text(c);
        LauncherConfig cfg = LauncherConfig.get(c);
        final String color = String.format(Locale.ROOT, "#%06x", cfg.accent & 0xFFFFFF);
        final String exclude = exclude(c);
        final String speed = cfg.screensaverSpeed;
        w.addJavascriptInterface(new Object() {
            @JavascriptInterface public String text() { return text; }
            @JavascriptInterface public String color() { return color; }
            @JavascriptInterface public String exclude() { return exclude; }
            @JavascriptInterface public String speed() { return speed; }
            @JavascriptInterface public double widthFraction() { return 0.65; }
            /** The page saw n lit pixels in the effect canvas: check the screen shows them too. */
            @JavascriptInterface public void canvasLit(int n) {
                if (n > 0) h.post(() -> checkVisible(0));
            }
            @JavascriptInterface public String forceEffect() { return forceEffect; }
            @JavascriptInterface public String forceMode() { return forceMode; }
            @JavascriptInterface public void log(String m) { Log.i(TAG, "salvaschermo: " + m); }
            /** v2.14 Living screensaver: weather + the PC's feed, as JSON (Living.forSaver). */
            @JavascriptInterface public String living() { return Living.forSaver(c.getApplicationContext()); }
            @JavascriptInterface public void drawn(String mode, int count, String effect) {
                drawn = true;
                h.post(() -> revealWeb());
                Log.i(TAG, "salvaschermo: modalita " + mode + " (" + count + " effetti), effetto " + effect
                        + ", WebView " + (web != null ? web.getWidth() + "x" + web.getHeight() : "?") + " px");
            }
            @JavascriptInterface public void fail(String why) {
                h.post(() -> toNative("pagina: " + why));
            }
        }, "OmarchySaver");
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                String line = "console " + m.messageLevel() + ": " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber();
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, line);
                else Log.i(TAG, line);
                return true;
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return serve(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return true;   // the saver never navigates
            }

            @android.annotation.TargetApi(26)
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail d) {
                // v2.14: the renderer died (old TVs, 1-2 GB of RAM). The screensaver runs inside the dream/preview
                // process: unhandled, it would take that down. Same fallback as a page that draws nothing: native.
                rendererGone(view);
                return true;
            }
        });
        return w;
    }

    /** Minimal WebViewAssetLoader: https://appassets.androidplatform.net/assets/... -> APK assets; nothing else loads. */
    private WebResourceResponse serve(Uri u) {
        String path = u.getPath();
        if (!HOST.equals(u.getHost()) || path == null || !path.startsWith("/assets/") || path.contains("..")) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
        }
        String rel = path.substring("/assets/".length());
        try {
            InputStream in = ctx.getAssets().open(rel);
            String mime = rel.endsWith(".js") ? "text/javascript" : rel.endsWith(".html") ? "text/html"
                    : rel.endsWith(".ttf") ? "font/ttf" : rel.endsWith(".json") ? "application/json" : "text/plain";
            WebResourceResponse r = new WebResourceResponse(mime, "utf-8", in);
            java.util.Map<String, String> hd = new java.util.HashMap<>();
            hd.put("Access-Control-Allow-Origin", "https://" + HOST);
            r.setResponseHeaders(hd);
            return r;
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
        }
    }

    static String text(Context c) {
        LauncherConfig cfg = LauncherConfig.get(c);
        String t = cfg.screensaverFromPc ? cfg.screensaverText : "";   // #84: an art picked on the TV wins
        if (t != null && !t.trim().isEmpty()) return t;
        if ("cinemarchy".equals(cfg.screensaverArt)) return Art.load(c, "cinemarchy");
        if ("omarchy".equals(cfg.screensaverArt)) return Art.load(c, "omarchy");
        if ("original".equals(cfg.screensaverArt)) return Art.load(c, "original");
        try (InputStream in = c.getAssets().open("saver/screensaver-tv.txt")) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "OMARCHY TV";
        }
    }

    /** omarchytv.json "screensaver": {"exclude": ["fireworks", ...]}: effects left out of the rotation (heavy at 4K). */
    private static String exclude(Context c) {
        org.json.JSONObject ss = LauncherConfig.readJson(c).optJSONObject("screensaver");
        org.json.JSONArray a = ss != null ? ss.optJSONArray("exclude") : null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) b.append(i > 0 ? "," : "").append(a.optString(i));
        return b.toString();
    }

    /** Window to sample (the preview activity or the dream), set by the owner. */
    android.view.Window window;
    private int blackChecks;

    /**
     * v2.10 "never black": the page can report drawn while nothing reaches the screen (GPU composition of the
     * WebView canvas failing; seen on the emulator). Sample the window with PixelCopy at low resolution; if it is
     * black, retry once with a software layer, then fall back to the native logo.
     */
    private void checkVisible(int attempt) {
        if (stopped || fallback != null || web == null || window == null || android.os.Build.VERSION.SDK_INT < 26) return;
        final android.graphics.Bitmap b;
        try {
            b = android.graphics.Bitmap.createBitmap(480, 270, android.graphics.Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return;
        }
        try {
            android.view.PixelCopy.request(window, b, res -> {
                int lit = 0;
                if (res == android.view.PixelCopy.SUCCESS) {
                    int[] px = new int[b.getWidth() * b.getHeight()];
                    b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
                    for (int p : px) if (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF) > 24) lit++;
                }
                b.recycle();
                Log.i(TAG, "salvaschermo: controllo visibile " + attempt + ": " + lit + " pixel accesi (copia " + res + ")");
                if (res != android.view.PixelCopy.SUCCESS || lit > 0) return;
                if (attempt == 0) {   // black: switch to a software layer at once (cheap to try)
                    Log.w(TAG, "salvaschermo: schermo nero con WebView accelerata, provo layer software");
                    softwareLayer();
                    h.postDelayed(() -> checkVisible(3), 1500);
                } else if (attempt >= 3 && attempt < 5) {
                    h.postDelayed(() -> checkVisible(attempt + 1), 800);
                } else {
                    toNative("schermo nero dopo il disegno");
                }
            }, h);
        } catch (Throwable t) {
            Log.w(TAG, "salvaschermo: PixelCopy non disponibile: " + t);
        }
    }

    /** Test hook: draw the WebView in a software layer (checks GPU composition problems). */
    void softwareLayer() {
        if (web != null) web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    View view() {
        return root;
    }

    void start() {
        stopped = false;
        logEnv();
        if (web == null) {
            toNative("nessuna WebView");
            return;
        }
        drawn = false;
        LauncherConfig cfg = LauncherConfig.get(ctx);
        if (!cfg.living && ("native".equals(forceMode) || AsciiSaverView.isAquarium(cfg))) {   // v2.14 Live needs the page
            toNative("aquarium/nativo");
            return;
        }
        // v2.10 "never black": the native logo animates at once; the WebView renders invisibly on top and is revealed
        // only when tte.js reports its first frame (cold WebView start can take seconds on a TV).
        if (placeholder == null) {
            placeholder = newNative();
            root.addView(placeholder, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            placeholder.start();
        }
        web.setAlpha(0f);
        web.loadUrl("https://" + HOST + "/assets/saver/index.html");
        h.postDelayed(() -> {
            if (!drawn) toNative("nessun disegno entro " + drawTimeoutMs + " ms");
        }, drawTimeoutMs);
    }

    private void logEnv() {
        try {
            PackageInfo p = android.os.Build.VERSION.SDK_INT >= 26 ? WebView.getCurrentWebViewPackage() : null;
            Log.i(TAG, "salvaschermo: WebView " + (p != null ? p.packageName + " " + p.versionName : "?")
                    + ", tte.js ufficiale " + assetsSize("saver/tte-js/src") + " byte, tte-bundle.js " + assetsSize("saver/tte-bundle.js") + " byte");
        } catch (Throwable ignored) {
        }
    }

    private long assetsSize(String path) {
        try {
            String[] kids = ctx.getAssets().list(path);
            if (kids != null && kids.length > 0) {
                long n = 0;
                for (String k : kids) n += assetsSize(path + "/" + k);
                return n;
            }
            try (InputStream in = ctx.getAssets().open(path)) {
                long n = 0;
                byte[] b = new byte[8192];
                for (int r; (r = in.read(b)) > 0; ) n += r;
                return n;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private AsciiSaverView placeholder;

    private AsciiSaverView newNative() {
        AsciiSaverView v = new AsciiSaverView(ctx, null);
        v.setShowClock(LauncherConfig.get(ctx).screensaverClock);   // #83: the Screensaver > Clock row does something
        v.setWidthFraction(0.65f);
        return v;
    }

    private void revealWeb() {
        if (stopped || web == null || fallback != null) return;
        web.setAlpha(1f);
        if (placeholder != null) {
            placeholder.stop();
            root.removeView(placeholder);
            placeholder = null;
        }
    }

    /**
     * The WebView renderer crashed or was killed for memory (low-RAM TVs). On the UI thread, as every call here:
     * drop the dead WebView, stop the clocks, and run {@link #onDead} so the owner closes the screensaver and the Home
     * screen comes back. Where nobody can close it (a Dream the system runs) the native view takes over instead.
     */
    private void rendererGone(WebView w) {
        if (w != null && w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        if (w != null && w == web) web = null;
        try {
            if (w != null) w.destroy();
        } catch (Throwable ignored) {
        }
        if (onDead != null && !stopped) {   // v2.14: close this screensaver, the Home screen is right there
            Log.w(TAG, "salvaschermo: renderer WebView morto, chiudo e torno alla Home");
            stopped = true;
            h.removeCallbacksAndMessages(null);
            if (fallback != null) fallback.stop();
            if (placeholder != null) {
                placeholder.stop();
                placeholder = null;
            }
            onDead.run();
            return;
        }
        toNative("renderer WebView morto");   // nobody to close it (a dream the system owns): keep the native one
    }

    private void toNative(String why) {
        if (stopped || fallback != null) return;
        Log.w(TAG, "salvaschermo: modalita nativo (" + why + ")");
        if (web != null) {
            root.removeView(web);
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        if (placeholder != null) {   // already animating underneath: keep it
            fallback = placeholder;
            placeholder = null;
            return;
        }
        fallback = newNative();
        root.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fallback.start();
    }

    boolean toggleSound() {
        if (fallback != null && fallback.isAquariumMode()) {
            return fallback.toggleSound();
        }
        if (placeholder != null && placeholder.isAquariumMode()) {
            return placeholder.toggleSound();
        }
        return false;
    }

    boolean isAquariumActive() {
        return (fallback != null && fallback.isAquariumMode())
                || (placeholder != null && placeholder.isAquariumMode());
    }

    void stop() {
        stopped = true;
        h.removeCallbacksAndMessages(null);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        if (fallback != null) fallback.stop();
        if (placeholder != null) placeholder.stop();
    }
}
