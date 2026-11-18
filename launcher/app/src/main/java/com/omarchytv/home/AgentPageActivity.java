package com.omarchytv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * v2.21 agent pages: a dashboard or an agent's page from the LAN or the tailnet, full screen (POST /v1/page,
 * MCP tv_show_page). Only http:// to a literal LAN / tailnet address (LanHttpDataSource.lanHost), and it stays
 * on that host: a link elsewhere is not followed. No file access, no JavaScript bridge. BACK closes it.
 */
public class AgentPageActivity extends Activity {
    static final String EXTRA_URL = "url";
    static volatile AgentPageActivity open;

    private WebView web;
    private String host;

    /** http://, a literal LAN or tailnet address (no DNS), at most 2000 characters. */
    static boolean allowed(String url) {
        if (url == null || url.length() > 2000) return false;
        Uri u = Uri.parse(url);
        return "http".equals(u.getScheme()) && LanHttpDataSource.lanHost(u.getHost());
    }

    static Intent intent(Context c, String url) {
        return new Intent(c, AgentPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_URL, url);
    }

    static boolean closeOpen() {
        AgentPageActivity a = open;
        if (a == null) return false;
        a.runOnUiThread(a::finish);
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        open = this;
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Theme.cur().bg);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                return host == null || !host.equals(u.getHost()) || !"http".equals(u.getScheme());   // stay on the page's box
            }

            @android.annotation.TargetApi(26)
            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                // the page's renderer died (out of memory on a small TV): drop it and go back, never crash the launcher
                if (web == v) {
                    ((android.view.ViewGroup) v.getParent()).removeView(v);
                    v.destroy();
                    web = null;
                }
                finish();
                return true;
            }
        });
        setContentView(web);
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        load(i);
    }

    private void load(Intent i) {
        String url = i.getStringExtra(EXTRA_URL);
        if (!allowed(url)) {
            finish();
            return;
        }
        host = Uri.parse(url).getHost();
        web.loadUrl(url);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (open == this) open = null;
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
