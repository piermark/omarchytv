package com.omarchytv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Omarchy-style web app (like omarchy-webapp-install): one site, fullscreen, no browser chrome.
 * Runs in its own process (android:process=":web"), so the home screen never carries a WebView and the
 * whole web process is freed when you leave. Cookies persist, so logins survive.
 *
 * Remote: D-pad moves focus between links/fields (WebView's built-in spatial navigation); MENU (or a
 * long-press on OK) opens the web menu: cursor mode, desktop/mobile site, reload, exit. In cursor mode
 * the arrows move a pointer (it accelerates), OK clicks, and the page scrolls at the edges.
 * BACK = page history back, then exit. Text fields open the TV keyboard, whose mic does voice dictation.
 */
public class WebAppActivity extends Activity {

    static final String EXTRA_ID = "id", EXTRA_NAME = "name", EXTRA_URL = "url", EXTRA_UA = "ua";
    /**
     * Below this, warn: modern sites (x.com) need a recent Chromium. Android 8/9 TVs top out at
     * WebView/Chrome 138 (Chrome 139+ needs Android 10), so "update in the Play Store" reaches 138 at most.
     */
    static final int MIN_WEBVIEW_MAJOR = 110;

    private WebView web;
    private View pointer;
    private TextView status;
    private boolean cursorMode;
    private float px, py;
    private int repeat;
    private String id, ua;
    private String mobileUa;

    static void open(Context c, LauncherConfig.WebApp w) {
        AmbilightController.restoreFollowVideo(c);
        Intent i = new Intent(c, WebAppActivity.class)
                .putExtra(EXTRA_ID, w.id).putExtra(EXTRA_NAME, w.name).putExtra(EXTRA_URL, w.url).putExtra(EXTRA_UA, w.ua)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        c.startActivity(i);
    }

    static void openUrl(Context c, String name, String url) {
        open(c, new LauncherConfig.WebApp(LauncherConfig.slug(name), name, url, "", "desktop"));
    }

    private void fallBackToBrowser(String url) {
        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            // No browser on this TV either: the toast is all we can do.
        }
        finish();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        id = in.getStringExtra(EXTRA_ID);
        ua = in.getStringExtra(EXTRA_UA);
        String url = in.getStringExtra(EXTRA_URL);
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            finish();
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.m(0xFF1A1B26));
        if (Build.VERSION.SDK_INT >= 28) OmarchyApp.useOwnWebViewDir(getPackageName());   // no-op when already set
        try {
            web = new WebView(this);
        } catch (Throwable t) {
            // No usable WebView (missing/updating package, data dir locked): open the page in a browser instead.
            fallBackToBrowser(url);
            return;
        }
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pointer = new View(this);
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(Theme.m(0xCC7AA2F7));
        dot.setStroke((int) (2 * d), Theme.m(0xFF16161E));
        pointer.setBackground(dot);
        pointer.setVisibility(View.GONE);
        root.addView(pointer, new FrameLayout.LayoutParams((int) (18 * d), (int) (18 * d)));

        status = new TextView(this);
        status.setTextColor(Theme.m(0xFFC0CAF5));
        status.setTextSize(13);
        status.setBackgroundColor(Theme.m(0xE616161E));
        status.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        status.setTypeface(CastUi.font(this));
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        slp.setMargins(0, (int) (16 * d), (int) (16 * d), 0);
        root.addView(status, slp);
        setContentView(root);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        mobileUa = s.getUserAgentString();
        applyUa();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String scheme = req.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try {   // intent:, market:, mailto: etc. go to the system (other apps), never run here
                    startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
                return true;
            }

            /** http:// only to a literal LAN address (network_security_config.xml), every request, page or not. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!"http".equals(u.getScheme()) || LanHttpDataSource.allowed(u)) return null;
                byte[] body = getString(R.string.web_http_lan_only).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null,
                        new java.io.ByteArrayInputStream(body));
            }

            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                flash("… " + Uri.parse(u).getHost());
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient());   // fullscreen video, alerts
        web.requestFocus();
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(url);
        checkWebViewVersion();
    }

    /** Desktop UA: Chrome on Linux (like Omarchy's Chromium web apps); mobile: the WebView default. */
    private void applyUa() {
        WebSettings s = web.getSettings();
        if ("desktop".equals(ua)) {
            String chrome = "120.0.0.0";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([\\d.]+)").matcher(mobileUa);
            if (m.find()) chrome = m.group(1);
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome + " Safari/537.36");
        } else {
            // v2.10: X (x.com) serves a blank page to WebViews ("; wv" / "Version/4.0" in the UA): present the
            // TV's own Chrome-Android UA without the WebView markers.
            s.setUserAgentString(mobileUa.replace("; wv)", ")").replaceAll("Version/\\d+(\\.\\d+)* ", ""));
        }
    }

    private void checkWebViewVersion() {
        if (Build.VERSION.SDK_INT < 26) return;
        PackageInfo p = WebView.getCurrentWebViewPackage();
        if (p == null || p.versionName == null) return;
        try {
            int major = Integer.parseInt(p.versionName.split("\\.")[0]);
            if (major < MIN_WEBVIEW_MAJOR) {
                Toast.makeText(this, getString(R.string.web_old, p.versionName), Toast.LENGTH_LONG).show();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);

    private void flash(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        status.removeCallbacks(hideStatus);
        status.postDelayed(hideStatus, 1600);
    }

    private void setCursorMode(boolean on) {
        cursorMode = on;
        if (on && px == 0 && py == 0) {
            px = web.getWidth() / 2f;
            py = web.getHeight() / 2f;
        }
        pointer.setVisibility(on ? View.VISIBLE : View.GONE);
        placePointer();
        flash(getString(on ? R.string.web_cursor_on : R.string.web_cursor_off));
    }

    private void placePointer() {
        pointer.setTranslationX(px - pointer.getWidth() / 2f);
        pointer.setTranslationY(py - pointer.getHeight() / 2f);
    }

    private void click() {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px, py, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, px, py, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private AsciiMenu menu;

    private void showMenu() {
        menu = new AsciiMenu(this, 360);
        AsciiMenu.Page p = new AsciiMenu.Page(getIntent().getStringExtra(EXTRA_NAME));
        p.add(getString(R.string.web_cursor), getString(cursorMode ? R.string.on : R.string.off), r -> {
            setCursorMode(!cursorMode);
            return false;
        });
        p.add(getString(R.string.web_site), getString("desktop".equals(ua) ? R.string.web_desktop : R.string.web_mobile), r -> {
            ua = "desktop".equals(ua) ? "mobile" : "desktop";
            applyUa();
            web.reload();
            saveUa();
            return false;
        });
        p.add(getString(R.string.web_reload), null, r -> {
            web.reload();
            return false;
        });
        p.add(getString(R.string.web_exit), null, r -> {
            finish();
            return false;
        });
        status.removeCallbacks(hideStatus);
        status.setText(getIntent().getStringExtra(EXTRA_NAME));
        status.setVisibility(View.VISIBLE);   // the menu hangs below this label, top right
        menu.setOnDismiss(() -> status.setVisibility(View.GONE));
        menu.showBelow(status, p);
    }

    /** Remembers the desktop/mobile choice in omarchytv.json for this web app. */
    private void saveUa() {
        final String chosen = ua;
        LauncherConfig.update(this, j -> {
            org.json.JSONArray a = j.optJSONArray("webapps");
            if (a == null) {
                a = new org.json.JSONArray();
                for (LauncherConfig.WebApp w : LauncherConfig.get(this).webapps) {
                    a.put(new org.json.JSONObject().put("id", w.id).put("name", w.name).put("url", w.url).put("ua", w.ua));
                }
                j.put("webapps", a);
            }
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null && id.equals(LauncherConfig.slug(o.optString("id", o.optString("name"))))) o.put("ua", chosen);
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (web == null) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_MENU) {
            if (!down) showMenu();
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (!down) {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
            return true;
        }
        if (!cursorMode) {
            // Long-press OK also opens the menu (remotes without a MENU key).
            if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && down && e.getRepeatCount() == 12) {
                showMenu();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        float d = getResources().getDisplayMetrics().density;
        if (!down) {
            repeat = 0;
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) click();
            return isPointerKey(k) || super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) return true;
        if (!isPointerKey(k)) return super.dispatchKeyEvent(e);
        repeat++;
        float step = Math.min(48, 8 + repeat * 3) * d;
        float dx = k == KeyEvent.KEYCODE_DPAD_LEFT ? -step : k == KeyEvent.KEYCODE_DPAD_RIGHT ? step : 0;
        float dy = k == KeyEvent.KEYCODE_DPAD_UP ? -step : k == KeyEvent.KEYCODE_DPAD_DOWN ? step : 0;
        float margin = 24 * d;
        px += dx;
        py += dy;
        // At the edges, scroll the page instead of leaving the screen.
        if (py > web.getHeight() - margin) {
            web.scrollBy(0, (int) (py - (web.getHeight() - margin)));
            py = web.getHeight() - margin;
        } else if (py < margin) {
            web.scrollBy(0, (int) (py - margin));
            py = margin;
        }
        if (px > web.getWidth() - margin) {
            web.scrollBy((int) (px - (web.getWidth() - margin)), 0);
            px = web.getWidth() - margin;
        } else if (px < margin) {
            web.scrollBy((int) (px - margin), 0);
            px = margin;
        }
        placePointer();
        return true;
    }

    private static boolean isPointerKey(int k) {
        return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();   // keep logins
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (menu != null && menu.isShowing()) menu.dismiss();
        if (web != null) {
            CookieManager.getInstance().flush();
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
        // Own ":web" process only: free the whole WebView (often 100+ MB) as soon as the web app is closed.
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }
}
