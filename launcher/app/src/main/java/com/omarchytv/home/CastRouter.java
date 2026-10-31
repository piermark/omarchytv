package com.omarchytv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.omarchytv.home.link.Cast;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens a validated cast (PROTOCOL.md §6 Cast) with fixed intents only, and routes control to whatever shows
 * it. Nothing here runs unless a paired PC sends a cast: no receivers, no timers, no background work.
 *
 * youtube → the YouTube TV app (https://www.youtube.com/watch?v=ID, then vnd.youtube:ID in it, then a browser);
 * video/audio → {@link CastPlayerActivity} (Media3 ExoPlayer); image/text → {@link CastViewActivity};
 * url → the TV's browser (ACTION_VIEW + CATEGORY_BROWSABLE), else the launcher's own web view.
 */
final class CastRouter {
    static final String YOUTUBE_TV = "com.google.android.youtube.tv";
    static final String EXTRA_ID = "cast.id", EXTRA_KIND = "cast.kind", EXTRA_URL = "cast.url", EXTRA_FILE = "cast.file",
            EXTRA_MIME = "cast.mime", EXTRA_TITLE = "cast.title", EXTRA_FROM = "cast.from", EXTRA_POS = "cast.position",
            EXTRA_LIVE = "cast.live", EXTRA_TEXT = "cast.text", EXTRA_SUB = "cast.subtitle";

    /** What shows a cast inside the launcher process (player, viewer, text card). Main thread only. */
    interface Surface {
        String castId();

        /** play / pause / seek / stop from the PC. */
        void castControl(String action, double position);

        /** A newer cast replaces this one: close without reporting (the server already sent "stopped"). */
        void castReplaced();
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WeakReference<Surface> surface = new WeakReference<>(null);
    /** Cast handed to another app (youtube / browser): controlled with media keys. */
    private static volatile String externalId, externalApp;
    private static volatile List<String> kinds;
    private static volatile long kindsAt;

    private CastRouter() {
    }

    static File dir(Context app) {
        return new File(app.getCacheDir(), "cast");
    }

    /** Kinds that open right now; cached 30 s (a PackageManager query, only when a PC asks for status). */
    /** Forget the cached kinds (the cast switch changed). */
    static void reset() {
        kinds = null;
    }

    static List<String> kinds(Context app) {
        // v2.10: cast off by default; no kinds = no "cast" capability and every cast request is refused.
        if (!LauncherConfig.get(app).castEnabled) return new ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        List<String> k = kinds;
        if (k != null && now - kindsAt < 30_000) return k;
        k = new ArrayList<>();
        k.add("url");   // the launcher's own web view is the fallback browser
        if (installed(app, YOUTUBE_TV) || browser(app, Uri.parse("https://www.youtube.com/")) != null) k.add("youtube");
        k.add("video");
        k.add("audio");
        k.add("image");
        k.add("text");
        kinds = k;
        kindsAt = now;
        return k;
    }

    private static boolean installed(Context app, String pkg) {
        try {
            app.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** An external browser activity for {@code u}, or null. Never the launcher itself. */
    private static Intent browser(Context app, Uri u) {
        Intent i = new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE);
        List<ResolveInfo> r = app.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo ri : r) {
            if (ri.activityInfo == null || app.getPackageName().equals(ri.activityInfo.packageName)) continue;
            return i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        }
        return null;
    }

    private static boolean resolves(Context app, Intent i) {
        return i.resolveActivity(app.getPackageManager()) != null;
    }

    /** Server thread. Returns null when the cast opens, or "no_app". */
    static String open(Context app, Cast c) {
        Intent i;
        boolean external = false;
        switch (c.kind) {
            case "youtube": {
                // Only the validated 11-character videoId goes into these fixed URIs.
                // The watch URL first, as YoutubeEmbedActivity does: YouTube TV 5.30 resolves vnd.youtube: but then
                // stays on its home screen (or profile picker) instead of playing.
                long t = Math.round(c.position);
                Uri web = Uri.parse("https://www.youtube.com/watch?v=" + c.videoId + (t > 0 ? "&t=" + t + "s" : ""));
                i = new Intent(Intent.ACTION_VIEW, web).setPackage(YOUTUBE_TV);
                if (!resolves(app, i)) i = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + c.videoId
                        + (t > 0 ? "?t=" + t : ""))).setPackage(YOUTUBE_TV).putExtra("force_fullscreen", true);
                if (!resolves(app, i)) i = browser(app, web);
                if (i == null) return "no_app";
                external = true;
                break;
            }
            case "url": {
                Uri u = Uri.parse(c.url);
                String s = u.getScheme();
                if (s == null || !(s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"))) return "no_app";
                i = browser(app, u);
                if (i != null) {
                    external = true;
                } else {
                    i = new Intent(app, WebAppActivity.class).putExtra(WebAppActivity.EXTRA_ID, "cast")
                            .putExtra(WebAppActivity.EXTRA_NAME, c.title.isEmpty() ? u.getHost() : c.title)
                            .putExtra(WebAppActivity.EXTRA_URL, c.url).putExtra(WebAppActivity.EXTRA_UA, "mobile");
                    external = true;   // own :web process: reported as handed over, closed by going home
                }
                break;
            }
            case "video":
            case "audio":
                // A YouTube page sent as "video": ExoPlayer can't play it (and signed streams 403): the YouTube app.
                if (c.url != null && DhhFeed.validId(YoutubeLaunch.idOf(c.url))) {
                    final String yt = YoutubeLaunch.idOf(c.url);
                    main.post(() -> {
                        YoutubeEmbedActivity.openInYoutubeApp(app, yt, c.title);
                        OmarchyLink.castState(c.id, "playing", -1, -1, null);
                    });
                    return null;
                }
                i = surfaceIntent(app, CastPlayerActivity.class, c);
                break;
            default:   // image, text
                i = surfaceIntent(app, CastViewActivity.class, c);
        }
        final Intent start = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final boolean ext = external;
        main.post(() -> {
            Surface s = surface.get();
            surface = new WeakReference<>(null);
            if (s != null) s.castReplaced();
            externalId = ext ? c.id : null;
            externalApp = ext ? c.app : null;
            try {
                app.startActivity(start);
                if (ext) OmarchyLink.castState(c.id, "playing", -1, -1, null);
            } catch (RuntimeException e) {
                OmarchyLink.castState(c.id, "error", -1, -1, "cannot open " + c.app);
            }
        });
        return null;
    }

    private static Intent surfaceIntent(Context app, Class<?> cls, Cast c) {
        Intent i = new Intent(app, cls).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_ID, c.id).putExtra(EXTRA_KIND, c.kind).putExtra(EXTRA_TITLE, c.title)
                .putExtra(EXTRA_FROM, c.from).putExtra(EXTRA_POS, c.position).putExtra(EXTRA_LIVE, c.live);
        if (c.file != null) i.putExtra(EXTRA_FILE, c.file.getName());   // a sha; resolved in our private cache
        else if (c.url != null) i.putExtra(EXTRA_URL, c.url);
        if (c.mime != null) i.putExtra(EXTRA_MIME, c.mime);
        if (c.text != null) i.putExtra(EXTRA_TEXT, c.text);
        if (c.subtitle != null) i.putExtra(EXTRA_SUB, c.subtitle);
        return i;
    }

    /** Main thread: the activity showing {@code s} is up. */
    static void attach(Surface s) {
        surface = new WeakReference<>(s);
        externalId = null;
    }

    static void detach(Surface s) {
        if (surface.get() == s) surface = new WeakReference<>(null);
    }

    /**
     * v2.13 Cinemarchy ":pause": toggles the in-launcher player if one is attached, else the external app
     * via a media key. Main thread. Returns false when nothing on the TV could be toggled.
     */
    static boolean togglePause(Context app) {
        Surface s = surface.get();
        if (s instanceof CastPlayerActivity) {
            ((CastPlayerActivity) s).togglePlayPause();
            return true;
        }
        if (externalId != null) {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            return true;
        }
        return false;
    }

    /** Server thread. null when done, or an error code. */
    static String control(Context app, String id, String action, double position) {
        if (id.equals(externalId)) {
            if ("seek".equals(action)) return "unsupported";
            if ("stop".equals(action) && "browser".equals(externalApp)) {
                main.post(() -> app.startActivity(new Intent(app, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
            } else {
                int code = "play".equals(action) ? KeyEvent.KEYCODE_MEDIA_PLAY : "pause".equals(action)
                        ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_STOP;
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return "unsupported";
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
            if ("stop".equals(action)) externalId = null;
            return null;
        }
        Surface s = surface.get();
        if (s == null || !id.equals(s.castId())) return "nothing_playing";
        main.post(() -> {
            Surface now = surface.get();
            if (now != null && id.equals(now.castId())) now.castControl(action, position);
        });
        return null;
    }

    /** Home resumed: drop uploads older than 24 h (only touches the disk when the cast cache exists). */
    static void sweep(Context app, String keep) {
        File d = dir(app);
        if (d.isDirectory()) Cast.sweep(d, System.currentTimeMillis(), keep, 8, 512L << 20);
    }
}
