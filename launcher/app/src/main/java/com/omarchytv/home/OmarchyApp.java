package com.omarchytv.home;

import android.annotation.TargetApi;
import android.app.Application;
import android.os.Build;
import android.webkit.WebView;

/**
 * v2.14: each extra process (:web, :saver, ...) gets its own WebView data directory. On Android 9+ a WebView
 * data directory can be held by only one process, so the :web web apps crashed in onCreate (crbug 558377)
 * once the main process had used a WebView (YouTube embed, companion). Their cookies and logins now live in
 * app_webview_web/ and persist across launches.
 */
public class OmarchyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 28) useOwnWebViewDir(getPackageName());
    }

    @TargetApi(28)
    static void useOwnWebViewDir(String pkg) {
        String suffix = suffixFor(pkg, Application.getProcessName());
        if (suffix == null) return;
        try {
            WebView.setDataDirectorySuffix(suffix);
        } catch (IllegalStateException | IllegalArgumentException alreadySetOrUsed) {
            // A WebView already ran in this process, or the suffix was set: nothing more to do.
        }
    }

    /** "web" for "com.omarchytv.home:web"; null for the main process or anything unexpected. */
    static String suffixFor(String pkg, String process) {
        if (pkg == null || process == null || process.equals(pkg) || !process.startsWith(pkg + ":")) return null;
        String s = process.substring(pkg.length() + 1).replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() ? null : s;
    }
}
