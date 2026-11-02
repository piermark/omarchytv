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
 * removed on pause), 360x240 scaled up, 30 fps. Only assets/omafire/ loads; no network.
 */
final class OmaFireLayer {
    private static final String HOST = "appassets.androidplatform.net";
    private static WebView web;

    private OmaFireLayer() {
    }

    /** "omafire:campfire" / "omafire:embers" -> the mode, else null. */
    static String mode(String wallpaper) {
        if ("omafire:campfire".equals(wallpaper)) return "campfire";
        if ("omafire:embers".equals(wallpaper)) return "embers";
        return null;
    }

    /** From onResume: show it when the wallpaper is an OmaFire one (wall = the still WallpaperView). */
    @SuppressLint("SetJavaScriptEnabled")
    static void onResume(Activity a, View wall) {
        String m = mode(LauncherConfig.get(a).wallpaper);
        if (m == null || wall == null || !(wall.getParent() instanceof ViewGroup)) {
            onPause();
            return;
        }
        if (web != null) return;
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
                            : p.endsWith(".webp") ? "image/webp" : "application/octet-stream";
                    return new WebResourceResponse(mime, "utf-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return true;
            }
        });
        parent.addView(w, parent.indexOfChild(wall) + 1, new ViewGroup.LayoutParams(wall.getLayoutParams()));
        w.loadUrl("https://" + HOST + "/assets/omafire/index.html#" + m);
        web = w;
    }

    /** From onPause / onDestroy: nothing keeps running behind other apps. */
    static void onPause() {
        WebView w = web;
        web = null;
        if (w == null) return;
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        try {
            w.destroy();
        } catch (Throwable ignored) {
        }
    }
}
