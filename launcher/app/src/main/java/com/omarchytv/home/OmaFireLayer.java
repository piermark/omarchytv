package com.omarchytv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;

/**
 * v2.14 OmaFire animated wallpaper (Wes Grimes' campsite from the OmaFire Omarchy plugin, MIT, assets/omafire):
 * fire.js unchanged in a small WebView right above the still wallpaper, only while Home is on screen (added on resume,
 * removed on pause), 360x240 scaled up, 30 fps. Only assets/omafire/ loads; no network. If the WebView renderer
 * dies the layer is dropped and the still wallpaper underneath stays: the animation is a decoration, never a crash.
 */
final class OmaFireLayer {
    private static final String HOST = "appassets.androidplatform.net";
    /** Renderer deaths in a row: after this many the layer stays off until the launcher restarts, never a loop. */
    private static final int MAX_DEATHS = 2;
    private static WebView web;
    /** v2.14.1: the fire's sound, played natively (#125); only while the sound setting is on. */
    private static OmaFireSound sound;
    private static int deaths;

    private OmaFireLayer() {
    }

    /** "omafire:campfire" / "omafire:embers" -> the mode, else null. */
    static String mode(String wallpaper) {
        if ("omafire:campfire".equals(wallpaper)) return "campfire";
        if ("omafire:embers".equals(wallpaper)) return "embers";
        return null;
    }

    /** The mode for this config: an OmaFire wallpaper, or the campfire theme with its own wallpaper ("omarchy"). */
    static String mode(LauncherConfig cfg) {
        String m = mode(cfg.wallpaper);
        if (m != null) return m;
        return "omarchy".equals(cfg.wallpaper) && cfg.theme != null && LauncherConfig.CAMPFIRE_THEME.equals(cfg.theme.name) ? "campfire" : null;
    }

    /** From onResume: show it when the wallpaper is an OmaFire one (wall = the still WallpaperView). */
    @SuppressLint("SetJavaScriptEnabled")
    static void onResume(Activity a, View wall) {
        String m = mode(LauncherConfig.get(a));
        if (m == null || wall == null || !(wall.getParent() instanceof ViewGroup)) {
            onPause();
            return;
        }
        if (web != null) return;
        if (deaths >= MAX_DEATHS) return;   // this TV cannot hold the animated one: the still wallpaper stays
        ViewGroup parent = (ViewGroup) wall.getParent();
        WebView w;
        try {
            w = new WebView(a);
        } catch (Throwable e) {   // no WebView on this TV: the still wallpaper stays
            return;
        }
        w.setBackgroundColor(Color.BLACK);
        w.setFocusable(false);
        w.setClickable(false);
        w.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);   // v2.14 OmaFire sound (only when omafireSound is on)
        boolean withSound = LauncherConfig.get(a).omafireSound;
        if (withSound) {   // v2.14.1 (#125): the page drives it, Android plays it; no WebAudio to wake up
            try {
                sound = new OmaFireSound(a.getApplicationContext());
                w.addJavascriptInterface(sound, "OmaFireNative");
            } catch (Throwable e) {
                sound = null;
                android.util.Log.w("OmarchyTV", "omafire: suono nativo non disponibile, resta WebAudio: " + e);
            }
        }
        final android.content.res.AssetManager assets = a.getAssets();
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String p = u.getPath();
                if (!HOST.equals(u.getHost()) || p == null || !p.startsWith("/assets/omafire/") || p.contains("..")) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                }
                try {
                    InputStream in = assets.open(p.substring("/assets/".length()));
                    String mime = p.endsWith(".js") ? "text/javascript" : p.endsWith(".html") ? "text/html"
                            : p.endsWith(".webp") ? "image/webp" : p.endsWith(".wav") ? "audio/wav" : "application/octet-stream";
                    return new WebResourceResponse(mime, "utf-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return true;
            }

            @android.annotation.TargetApi(26)
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail d) {
                // The still wallpaper underneath is the fallback. Not rebuilt here: one retry at the next onResume
                // at most, and after MAX_DEATHS the layer stays off until the launcher restarts (no crash loop).
                drop(view);
                return true;
            }
        });
        parent.addView(w, parent.indexOfChild(wall) + 1, new ViewGroup.LayoutParams(wall.getLayoutParams()));
        w.loadUrl("https://" + HOST + "/assets/omafire/index.html#" + m + (withSound ? "&sound" : ""));
        web = w;
    }

    /** From onPause / onDestroy: nothing keeps running behind other apps. */
    static void onPause() {
        releaseSound();
        WebView w = web;
        web = null;
        if (w == null) return;
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        try {
            w.destroy();
        } catch (Throwable ignored) {
        }
    }

    /**
     * v2.14: the renderer of the animated wallpaper died (or the TV killed it for memory). The layer lives inside
     * MainActivity, so an unhandled death would take the launcher down from the Home screen: take this WebView off
     * the parent, free it, and leave the still wallpaper that is already underneath. On the UI thread, as the
     * callback is.
     */
    private static void releaseSound() {
        OmaFireSound s = sound;
        sound = null;
        if (s != null) s.release();
    }

    private static void drop(WebView w) {
        if (w == null) return;
        deaths++;
        releaseSound();
        android.util.Log.w("OmarchyTV", "omafire: renderer WebView morto (" + deaths + "/" + MAX_DEATHS
                + "), resta lo sfondo fisso" + (deaths >= MAX_DEATHS ? ", animazione spenta fino al riavvio" : ""));
        if (web == w) web = null;
        try {
            if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
            w.destroy();
        } catch (Throwable ignored) {
        }
    }
}
