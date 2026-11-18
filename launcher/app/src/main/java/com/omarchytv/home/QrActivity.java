package com.omarchytv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * v2.21 a QR code full screen, for the phone: the Tailscale app to install, an app token made on the TV, the page of
 * the song playing. Drawn by the vendored qrcode-generator (assets/games/qrcode.js, MIT) in a WebView, in the theme's
 * colours with a light quiet zone so it scans. Text only: nothing is fetched. BACK or OK closes it.
 */
public class QrActivity extends Activity {
    static final String EXTRA_TEXT = "text", EXTRA_TITLE = "title", EXTRA_HINT = "hint", EXTRA_SHOW = "show";

    private WebView web;

    /** {@code show}: also print the text under the code (a token to type, a short URL). */
    static Intent intent(Context c, String title, String text, String hint, boolean show) {
        return new Intent(c, QrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_HINT, hint).putExtra(EXTRA_SHOW, show);
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(ch);
            }
        }
        return b.toString();
    }

    static String hex(int c) {
        return String.format(java.util.Locale.ROOT, "#%06x", c & 0xFFFFFF);
    }

    /** The page: JSON-escaped text into the script, HTML-escaped into the markup. */
    static String html(String title, String text, String hint, boolean show, int bg, int fg, int accent) {
        String js = org.json.JSONObject.quote(text == null ? "" : text);
        return "<!doctype html><html><head><meta charset='utf-8'><script src='qrcode.js'></script><style>"
                + "html,body{margin:0;height:100%;background:" + hex(bg) + ";color:" + hex(fg) + ";font-family:monospace}"
                + "body{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2.2vh}"
                + "h1{font-size:4vh;margin:0;color:" + hex(accent) + "}#q{background:#fff;padding:2.4vh;border-radius:1vh}"
                + "#q svg{width:46vh;height:46vh;display:block}p{font-size:2.4vh;margin:0;max-width:80vw;text-align:center;word-break:break-all}"
                + ".d{opacity:.7}</style></head><body><h1>" + esc(title) + "</h1><div id='q'></div>"
                + (show ? "<p>" + esc(text) + "</p>" : "") + "<p class='d'>" + esc(hint) + "</p>"
                + "<script>var t=" + js + ";try{qrcode.stringToBytes=qrcode.stringToBytesFuncs['UTF-8'];"
                + "var q=qrcode(0,'M');q.addData(t,'Byte');q.make();document.getElementById('q').innerHTML=q.createSvgTag(4,0);}"
                + "catch(e){document.getElementById('q').textContent='';}</script></body></html>";
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        String text = i.getStringExtra(EXTRA_TEXT);
        if (text == null || text.isEmpty() || text.length() > 1200) {
            finish();
            return;
        }
        Theme t = Theme.cur();
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.setBackgroundColor(t.bg);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                return true;   // nothing to navigate to
            }

            @android.annotation.TargetApi(26)
            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
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
        web.loadDataWithBaseURL("file:///android_asset/games/",
                html(i.getStringExtra(EXTRA_TITLE), text, i.getStringExtra(EXTRA_HINT), i.getBooleanExtra(EXTRA_SHOW, false),
                        t.bg, t.fgBright, t.accent), "text/html", "utf-8", null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
