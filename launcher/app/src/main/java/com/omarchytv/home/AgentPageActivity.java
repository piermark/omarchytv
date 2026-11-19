package com.omarchytv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.21 agent pages: dashboards and agents' pages from the LAN or the tailnet, full screen (POST /v1/page, MCP
 * tv_show_page, Home tiles). Up to four at once, tiled the Hyprland way: gaps, a border in the theme's accent on the
 * active window and a muted one on the others; 1-4 or the colour keys pick a window, the arrows and OK go to it.
 * Only http:// to a literal LAN / tailnet address (LanHttpDataSource.lanHost); each page stays on its host. No file
 * access, no JavaScript bridge. BACK closes everything. A TV with little RAM gets two windows at most.
 */
public class AgentPageActivity extends Activity {
    static final String EXTRA_URL = "url", EXTRA_URLS = "urls";
    static volatile AgentPageActivity open;

    private final List<WebView> webs = new ArrayList<>();
    private final List<FrameLayout> frames = new ArrayList<>();
    private FrameLayout root;
    private int active;

    /** http://, a literal LAN or tailnet address (no DNS), at most 2000 characters. */
    static boolean allowed(String url) {
        if (url == null || url.length() > 2000) return false;
        Uri u = Uri.parse(url);
        return "http".equals(u.getScheme()) && LanHttpDataSource.lanHost(u.getHost());
    }

    static Intent intent(Context c, String url) {
        return intent(c, java.util.Collections.singletonList(url));
    }

    static Intent intent(Context c, List<String> urls) {
        return new Intent(c, AgentPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_URLS, urls.toArray(new String[0]));
    }

    static boolean closeOpen() {
        AgentPageActivity a = open;
        if (a == null) return false;
        a.runOnUiThread(a::finish);
        return true;
    }

    /**
     * Window rectangles {left, top, right, bottom} for n windows in a w×h screen with gap g, Hyprland's dwindle for 3: one master on the left,
     * two stacked on the right. Pure, for tests.
     */
    static int[][] tile(int n, int w, int h, int g) {
        int cw = (w - 3 * g) / 2, ch = (h - 3 * g) / 2;
        switch (Math.max(1, Math.min(4, n))) {
            case 1:
                return new int[][]{{0, 0, w, h}};
            case 2:
                return new int[][]{{g, g, g + cw, h - g}, {2 * g + cw, g, w - g, h - g}};
            case 3:
                return new int[][]{{g, g, g + cw, h - g}, {2 * g + cw, g, w - g, g + ch}, {2 * g + cw, 2 * g + ch, w - g, h - g}};
            default:
                return new int[][]{{g, g, g + cw, g + ch}, {2 * g + cw, g, w - g, g + ch},
                        {g, 2 * g + ch, g + cw, h - g}, {2 * g + cw, 2 * g + ch, w - g, h - g}};
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        open = this;
        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.cur().bg);
        setContentView(root);
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        load(i);
    }

    private List<String> urls(Intent i) {
        List<String> out = new ArrayList<>();
        String[] many = i.getStringArrayExtra(EXTRA_URLS);
        if (many != null) for (String u : many) if (allowed(u) && !out.contains(u)) out.add(u);
        String one = i.getStringExtra(EXTRA_URL);
        if (one != null && allowed(one) && !out.contains(one)) out.add(one);
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        int max = am != null && am.isLowRamDevice() ? 2 : 4;
        return out.size() > max ? out.subList(0, max) : out;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView web(String url) {
        WebView v = new WebView(this);
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        v.setBackgroundColor(Theme.cur().bg);
        String host = Uri.parse(url).getHost();
        v.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                Uri u = r.getUrl();
                return host == null || !host.equals(u.getHost()) || !"http".equals(u.getScheme());   // stay on the page's box
            }

            @android.annotation.TargetApi(26)
            @Override
            public boolean onRenderProcessGone(WebView w, android.webkit.RenderProcessGoneDetail d) {
                // a page's renderer died (out of memory on a small TV): drop that window, never crash the launcher
                int i = webs.indexOf(w);
                if (i >= 0) {
                    ((ViewGroup) w.getParent()).removeView(w);
                    w.destroy();
                    webs.set(i, null);
                }
                boolean any = false;
                for (WebView x : webs) any |= x != null;
                if (!any) finish();
                return true;
            }
        });
        v.loadUrl(url);
        return v;
    }

    private void load(Intent i) {
        List<String> list = urls(i);
        if (list.isEmpty()) {
            finish();
            return;
        }
        for (WebView w : webs) if (w != null) w.destroy();
        webs.clear();
        frames.clear();
        root.removeAllViews();
        int n = list.size();
        root.post(() -> layout(list, n));
    }

    private void layout(List<String> list, int n) {
        int w = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int h = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int[][] r = tile(n, w, h, n == 1 ? 0 : dp(10));   // {left, top, right, bottom}
        for (int k = 0; k < n; k++) {
            FrameLayout f = new FrameLayout(this);
            int bw = n == 1 ? 0 : dp(2);
            f.setPadding(bw, bw, bw, bw);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(r[k][2] - r[k][0], r[k][3] - r[k][1]);
            lp.leftMargin = r[k][0];
            lp.topMargin = r[k][1];
            WebView v = web(list.get(k));
            final int idx = k;
            v.setOnFocusChangeListener((view, has) -> {
                if (has) select(idx, false);
            });
            f.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(f, lp);
            frames.add(f);
            webs.add(v);
        }
        select(0, true);
    }

    /** The active window: accent border (Hyprland's col.active_border), the others muted. */
    private void select(int k, boolean focus) {
        if (k < 0 || k >= webs.size() || webs.get(k) == null) return;
        active = k;
        Theme t = Theme.cur();
        for (int i = 0; i < frames.size(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(t.bg);
            if (frames.size() > 1) g.setStroke(dp(2), i == active ? t.accent : t.muted);
            frames.get(i).setBackground(g);
        }
        if (focus) webs.get(k).requestFocus(View.FOCUS_DOWN);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        int pick = -1;
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_4) pick = keyCode - KeyEvent.KEYCODE_1;
        else if (keyCode == KeyEvent.KEYCODE_PROG_RED) pick = 0;
        else if (keyCode == KeyEvent.KEYCODE_PROG_GREEN) pick = 1;
        else if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW) pick = 2;
        else if (keyCode == KeyEvent.KEYCODE_PROG_BLUE) pick = 3;
        else if (keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) pick = (active + 1) % Math.max(1, webs.size());
        else if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) pick = (active + webs.size() - 1) % Math.max(1, webs.size());
        if (pick >= 0 && webs.size() > 1) {
            select(pick, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (open == this) open = null;
        for (WebView w : webs) {
            if (w != null) {
                w.stopLoading();
                w.destroy();
            }
        }
        webs.clear();
        super.onDestroy();
    }
}
