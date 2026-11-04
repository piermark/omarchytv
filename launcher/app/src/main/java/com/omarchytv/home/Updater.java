package com.omarchytv.home;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.14 "Update OmarchyTV" (Launcher settings): the newest omarchytv-release-&lt;version&gt;.apk from the official
 * GitHub releases, only when newer than this build, downloaded, checked (release checksum, package name, same
 * signing key as the installed launcher, higher versionCode) and handed to Android's installer, which asks the user
 * to confirm. Android also refuses an APK signed with another key, so nothing else can replace the launcher.
 */
final class Updater {
    static final String RELEASES = "https://api.github.com/repos/piermark/omarchytv/releases?per_page=30";
    static final String DOWNLOAD_PREFIX = "https://github.com/piermark/omarchytv/releases/download/";
    static final Pattern ASSET = Pattern.compile("^omarchytv-release-(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.]+)?)\\.apk$");
    static final long MAX_BYTES = 128L * 1024 * 1024;
    static final String ACTION_DONE = "com.omarchytv.home.UPDATE_DONE";

    private Updater() {
    }

    /** Newest release asset: version, name, url, size, sha256 (or ""). */
    static final class Asset {
        final String version, name, url, sha256;
        final long size;

        Asset(String version, String name, String url, long size, String sha256) {
            this.version = version;
            this.name = name;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** Compares 2.13.8 &lt; 2.13.10 &lt; 2.14.0-beta &lt; 2.14.0 (a pre-release sorts before its release). */
    static int compare(String a, String b) {
        long[] x = key(a), y = key(b);
        for (int i = 0; i < 4; i++) if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
        String pa = pre(a), pb = pre(b);
        if (pa.isEmpty() || pb.isEmpty()) return 0;
        return Integer.signum(pa.compareTo(pb));
    }

    private static final Pattern VER = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(.+))?$");

    private static long[] key(String v) {
        Matcher m = VER.matcher(v == null ? "" : v.trim());
        if (!m.matches()) return new long[]{-1, 0, 0, 0};
        return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)),
                m.group(4) == null ? 1 : 0};
    }

    private static String pre(String v) {
        Matcher m = VER.matcher(v == null ? "" : v.trim());
        return m.matches() && m.group(4) != null ? m.group(4) : "";
    }

    /** The newest launcher APK strictly newer than current, from the GitHub releases JSON; null if none. */
    static Asset pick(JSONArray releases, String current) {
        Asset best = null;
        for (int i = 0; releases != null && i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optBoolean("draft")) continue;
            JSONArray as = r.optJSONArray("assets");
            for (int k = 0; as != null && k < as.length(); k++) {
                JSONObject a = as.optJSONObject(k);
                if (a == null) continue;
                Matcher m = ASSET.matcher(a.optString("name", ""));
                String url = a.optString("browser_download_url", "");
                if (!m.matches() || !url.startsWith(DOWNLOAD_PREFIX)) continue;
                String v = m.group(1);
                if (best != null && compare(v, best.version) <= 0) continue;
                String dg = a.optString("digest", "");
                best = new Asset(v, a.optString("name"), url, a.optLong("size", 0),
                        dg.startsWith("sha256:") ? dg.substring(7).toLowerCase(Locale.ROOT) : "");
            }
        }
        return best != null && compare(best.version, current) > 0 ? best : null;
    }

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /** Network thread. Throws with a short reason on failure. */
    static Asset check(Context c) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(RELEASES).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(20_000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "OmarchyTV");
        try (InputStream in = h.getInputStream()) {
            byte[] b = readAll(in, 4L * 1024 * 1024);
            return pick(new JSONArray(new String(b, "UTF-8")), currentVersion(c));
        } finally {
            h.disconnect();
        }
    }

    interface Progress {
        void on(int pct);
    }

    /** Download + checks. Network thread. Returns the verified APK file. */
    static File download(Context c, Asset a, Progress p) throws Exception {
        if (a.size > MAX_BYTES) throw new Exception("too large");
        File out = new File(c.getCacheDir(), "update.apk");
        URL url = new URL(a.url);
        HttpURLConnection h = null;
        for (int hop = 0; hop < 5; hop++) {   // GitHub redirects to its download host (https only)
            h = (HttpURLConnection) url.openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(15_000);
            h.setReadTimeout(30_000);
            h.setRequestProperty("User-Agent", "OmarchyTV");
            int code = h.getResponseCode();
            if (code / 100 != 3) break;
            String loc = h.getHeaderField("Location");
            h.disconnect();
            if (loc == null || !loc.startsWith("https://")) throw new Exception("bad redirect");
            url = new URL(loc);
        }
        if (h == null || h.getResponseCode() != 200) throw new Exception("HTTP " + (h != null ? h.getResponseCode() : 0));
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : a.size, got = 0;
        try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                got += n;
                if (got > MAX_BYTES) throw new Exception("too large");
                o.write(buf, 0, n);
                sha.update(buf, 0, n);
                int pct = total > 0 ? (int) (got * 100 / total) : -1;
                if (pct != last) p.on(last = pct);
            }
        } finally {
            h.disconnect();
        }
        if (!a.sha256.isEmpty() && !a.sha256.equals(hex(sha.digest()))) throw new Exception(c.getString(R.string.update_bad_apk));
        verify(c, out);
        return out;
    }

    /** Same package, higher versionCode, same signing certificate as the running launcher. */
    @SuppressWarnings("deprecation")
    static void verify(Context c, File apk) throws Exception {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo mine = pm.getPackageInfo(c.getPackageName(), flags);
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), flags);
        String bad = c.getString(R.string.update_bad_apk);
        if (theirs == null || !c.getPackageName().equals(theirs.packageName)) throw new Exception(bad);
        if (theirs.versionCode <= mine.versionCode) throw new Exception(bad);
        Signature[] a = signers(mine), b = signers(theirs);
        if (a == null || b == null || b.length == 0 || !sameSigners(a, b)) throw new Exception(bad);
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo p) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return p.signingInfo != null ? p.signingInfo.getApkContentsSigners() : null;
        }
        return p.signatures;
    }

    static boolean sameSigners(Signature[] a, Signature[] b) {
        if (a.length != b.length) return false;
        String[] x = new String[a.length], y = new String[b.length];
        for (int i = 0; i < a.length; i++) {
            x[i] = a[i].toCharsString();
            y[i] = b[i].toCharsString();
        }
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    /** Android 8+: the user must allow OmarchyTV to install apps once. False = settings opened instead. */
    static boolean mayInstall(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        if (c.getPackageManager().canRequestPackageInstalls()) return true;
        try {
            c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
        Toast.makeText(c, R.string.update_allow_unknown, Toast.LENGTH_LONG).show();
        return false;
    }

    /** Hands the APK to PackageInstaller; Android shows its own confirm. Network/worker thread. */
    static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        sp.setSize(apk.length());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("omarchytv.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                s.fsync(o);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;   // the installer adds extras
            Intent done = new Intent(c, Done.class).setAction(ACTION_DONE);
            s.commit(PendingIntent.getBroadcast(c, 0, done, flags).getIntentSender());
        }
    }

    /** Installer callbacks: show Android's confirm, report a failure. Success restarts the launcher by itself. */
    public static final class Done extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                    } catch (Exception ignored) {
                    }
                }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, c.getString(R.string.update_failed, msg != null ? msg : String.valueOf(st)), Toast.LENGTH_LONG).show();
                MainActivity.updateFinished(false, msg != null ? msg : String.valueOf(st));
            }
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    private static byte[] readAll(InputStream in, long limit) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            o.write(buf, 0, n);
            if (o.size() > limit) throw new Exception("too large");
        }
        return o.toByteArray();
    }
}
