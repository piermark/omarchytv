package com.omarchytv.home;

import android.app.AppOpsManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import com.omarchytv.home.link.LinkServer;
import com.omarchytv.home.link.LinkStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Omarchy Link, TV side (omarchy-link/docs/PROTOCOL.md): the Android {@link LinkServer.Host} plus the
 * plumbing around it (bind to the LAN address, NsdManager advert, status changes, keys).
 *
 * Lifetime: the server lives with the launcher process, which Android keeps because it is the HOME app.
 * {@link KeepAlive} is a plain started Service (no foreground notification, no wakelock) so the process
 * gets service priority while Android allows it; when Android stops it under background limits the
 * server keeps running in the process. Started from {@link MainActivity#onCreate}.
 */
public final class OmarchyLink implements LinkServer.Host {

    private static final String TAG = "OmarchyLink";
    private static final long PAIRING_WINDOW_MS = 5 * 60_000L;
    private static final long FG_POLL_MS = 10_000L;

    /** UI listeners (main thread): "pairing", "pcs", "answer", "answer_error", "ai_sources_ok", "ai_sources_error". */
    interface Listener {
        void onLinkEvent(String type, JSONObject data);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    /**
     * Everything Link does off the UI thread: one worker, created on demand, gone after 10 s idle. With the
     * PC off nothing is queued here, so the launcher pays nothing (no polling, no retries, no wakelocks).
     */
    private static final java.util.concurrent.ThreadPoolExecutor bg = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 10, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(64), r -> {
        Thread t = new Thread(r, "otv-link-bg");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }, new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    @android.annotation.SuppressLint("StaticFieldLeak")   // holds the application context only
    private static volatile OmarchyLink runtime;

    // Pairing: memory only (§8). Open while the Connect Omarchy Linux page is visible, at most 5 minutes.
    private static volatile String code;
    private static volatile long pairingOpenedAt;
    private static volatile boolean pairingVisible;

    // Status inputs, updated by the activity / dream / receivers.
    private static volatile boolean homeVisible, screensaver;
    private static volatile String lastLaunched;

    private Context app;
    private volatile LinkServer server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdReg;
    private boolean nsdPair;
    private volatile InetAddress bound;
    private String lastStatusKey = "";
    private boolean started;

    // --------------------------------------------------------------- entry points used by the launcher

    /** Called from MainActivity.onCreate (launcher in the foreground, so startService is allowed). */
    static void start(Context c) {
        ensure(c.getApplicationContext());
        try {
            c.startService(new Intent(c, KeepAlive.class));
        } catch (IllegalStateException ignored) {
            // background start refused: the in-process server already runs
        }
    }

    /** Returns at once: the store load, socket bind and mDNS run on the Link worker, never on the UI thread. */
    private static synchronized void ensure(Context app) {
        if (runtime != null) return;
        OmarchyLink r = new OmarchyLink();
        r.app = app;
        runtime = r;
        bg.execute(r::boot);
    }

    /** Booted and enabled (omarchytv.json "link": {"enabled": false} turns the whole service off). */
    private boolean live() {
        return server != null;
    }

    static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** The pairing page opened: fresh code, pair=1 in TXT. */
    static String openPairing() {
        code = newCode();
        pairingOpenedAt = SystemClock.elapsedRealtime();
        pairingVisible = true;
        OmarchyLink r = runtime;
        if (r != null) bg.execute(r::advertise);
        return code;
    }

    static void closePairing() {
        pairingVisible = false;
        OmarchyLink r = runtime;
        if (r != null) bg.execute(r::advertise);
    }

    /** Code on screen (null when the page is closed). */
    static String currentCode() {
        return pairingVisible ? code : null;
    }

    static boolean running() {
        OmarchyLink r = runtime;
        return r != null && r.live() && r.bound != null;
    }

    /** PC ids with an open event socket (the "> ask" and AI-source switches need one). */
    static boolean connected() {
        OmarchyLink r = runtime;
        return r != null && r.live() && r.server.connectedClients() > 0;
    }

    static boolean connected(String pcId) {
        OmarchyLink r = runtime;
        return r != null && r.live() && r.server.connected(pcId);
    }

    static void setHomeVisible(boolean v) {
        homeVisible = v;
        statusChanged();
    }

    static void setScreensaver(boolean v) {
        screensaver = v;
        statusChanged();
    }

    static void launched(String pkg) {
        lastLaunched = pkg;
        statusChanged();
    }

    /** Forget PC on the TV: sends "unpaired", closes its sockets, deletes it. */
    static void forget(String pcId) {
        OmarchyLink r = runtime;
        if (r != null && r.live()) bg.execute(() -> r.server.forget(pcId));
    }

    static List<LinkStore.Pc> pcs() {
        OmarchyLink r = runtime;
        return r == null || !r.live() ? java.util.Collections.<LinkStore.Pc>emptyList() : r.server.store().list();
    }

    /**
     * "> ask", written on the Link worker (never on the UI thread: a socket write there throws
     * NetworkOnMainThreadException). {@code done} runs on the UI thread with null when sent, else
     * "not_connected" / "busy".
     */
    static void ask(JSONObject data, java.util.function.Consumer<String> done) {
        OmarchyLink r = runtime;
        // Instant answer when no PC is connected: the screen says "PC offline" right away.
        if (r == null || !r.live()) {
            done.accept("not_connected");
            return;
        }
        bg.execute(() -> {
            String e = r.server.ask(data);
            main.post(() -> done.accept(e));
        });
    }

    /**
     * AI-pane source switches: the full list of enabled ids, in display order. Written on the Link worker;
     * {@code done} runs on the UI thread with null when sent, else "not_connected" / "busy".
     */
    static void aiSources(JSONObject data, java.util.function.Consumer<String> done) {
        OmarchyLink r = runtime;
        if (r == null || !r.live()) {
            done.accept("not_connected");
            return;
        }
        bg.execute(() -> {
            String e = r.server.aiSources(data);
            main.post(() -> done.accept(e));
        });
    }

    /** Last agent list from the PC (bounded, plain text), or null when nothing came since start / no link. */
    static JSONObject agents() {
        OmarchyLink r = runtime;
        if (r == null || !r.live()) return null;
        try {
            JSONObject a = r.server.agents();
            return a.isNull("at") && a.optJSONArray("agents").length() == 0 ? null : a;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /**
     * agent_ask {id, agentId, text}, written on the Link worker (never on the UI thread). A request the TV
     * refuses itself ("bad_request", "busy", "not_connected") comes back to listeners as a local
     * agent_ask_error with that code, exactly like a PC refusal.
     */
    static void agentAsk(String id, String agentId, String text) {
        OmarchyLink r = runtime;
        if (r == null || !r.live() || r.server.connectedClients() == 0) {
            localAskError(id, agentId, "not_connected");
            return;
        }
        bg.execute(() -> {
            String e = r.server.agentAsk(id, agentId, text);
            if (e != null) localAskError(id, agentId, e);
        });
    }

    /** True when a PC is connected over the sealed link (the Grok Bot bridge needs one). */
    static boolean hasPc() {
        OmarchyLink r = runtime;
        return r != null && r.live() && r.server.connectedClients() > 0;
    }

    /**
     * Experimental Grok Bot companion bridge: grokbot_ask {id, text[, bot]} to the PC's omarchy-link, which
     * forwards it to @tcballard's companion. TV-side refusals come back as a local grokbot_error.
     */
    static void grokbotAsk(String id, String text, String bot) {
        OmarchyLink r = runtime;
        if (!hasPc()) {
            localGrokbotError(id, "not_connected");
            return;
        }
        bg.execute(() -> {
            String e = r.server.grokbotAsk(id, text, bot);
            if (e != null) localGrokbotError(id, e);
        });
    }

    private static void localGrokbotError(String id, String code) {
        try {
            emit("grokbot_error", new JSONObject().put("id", id).put("error", code).put("local", true));
        } catch (org.json.JSONException ignored) {
        }
    }

    private static void localAskError(String id, String agentId, String code) {
        try {
            emit("agent_ask_error", new JSONObject().put("id", id).put("agentId", agentId).put("error", code).put("local", true));
        } catch (org.json.JSONException ignored) {
        }
    }

    /** The agents pane / view became visible: ask connected PCs for fresh activity (nothing when none). */
    static void agentSnapshot() {
        OmarchyLink r = runtime;
        if (r == null || !r.live() || r.server.connectedClients() == 0) return;
        bg.execute(r.server::agentSnapshot);
    }

    static void screenMirror(boolean start) {
        OmarchyLink r = runtime;
        if (r == null || !r.live()) return;
        bg.execute(() -> {
            try {
                r.server.screenMirror(start);
            } catch (Exception ignored) {
            }
        });
    }

    static void shortcut(String command) {
        OmarchyLink r = runtime;
        if (r == null || !r.live()) return;
        bg.execute(() -> {
            try {
                r.server.shortcut(command);
            } catch (Exception ignored) {
            }
        });
    }

    /** Reverse theme sync: tell the paired PC(s) the theme just picked on the TV. Fire and forget. */
    /** v2.14 send the Living screensaver prefs to the connected PCs (on connect and when a setting changes). */
    static void livingPrefs() {
        OmarchyLink r = runtime;
        if (r == null || !r.live() || r.server.connectedClients() == 0) return;
        JSONObject p = Living.prefs(LauncherConfig.get(r.app));
        bg.execute(() -> r.server.livingPrefs(p));
    }

    static void themeSet(String name) {
        OmarchyLink r = runtime;
        if (r == null || !r.live() || name == null || name.isEmpty()) return;
        bg.execute(() -> {
            try {
                r.server.themeSet(name);
            } catch (Exception ignored) {
            }
        });
    }

    static void statusChanged() {
        OmarchyLink r = runtime;
        if (r != null) r.maybeBroadcastStatus();
    }

    // --------------------------------------------------------------- Service shell

    /** Keeps the launcher process at service priority while Android allows it. Holds nothing itself. */
    public static final class KeepAlive extends Service {
        @Override
        public void onCreate() {
            super.onCreate();
            ensure(getApplicationContext());
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            return START_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }

    // --------------------------------------------------------------- runtime

    private void boot() {
        if (started) return;
        started = true;
        if (!LauncherConfig.get(app).linkEnabled) return;   // off: no socket, no mDNS, nothing scheduled
        LinkServer s = new LinkServer(this, new LinkStore(new File(app.getFilesDir(), "link.json")));
        // Foreground-app checks run only while a PC listens; with no PC there is no timer at all.
        s.setOnClientsChanged(() -> {
            main.post(this::schedulePoll);
            livingPrefs();   // a PC (re)connected: tell it what the Living screensaver wants
            emit("clients", new JSONObject());   // AI pane: "pc offline" mark on/off
        });
        server = s;
        nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    bg.execute(OmarchyLink.this::rebind);
                }

                @Override
                public void onLost(Network network) {
                    bg.execute(OmarchyLink.this::rebind);
                }
            };
            // The (callback, Handler) overload is API 26; Fire OS 6 is API 25 (#59, #56). Both callbacks only
            // hand off to bg, so the 1-arg API 24 overload behaves the same there.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) cm.registerDefaultNetworkCallback(cb, main);
            else cm.registerDefaultNetworkCallback(cb);
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_DREAMING_STARTED);
        f.addAction(Intent.ACTION_DREAMING_STOPPED);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_DREAMING_STARTED.equals(a)) screensaver = true;
                else if (Intent.ACTION_DREAMING_STOPPED.equals(a)) screensaver = false;
                else if (Intent.ACTION_SCREEN_ON.equals(a)) powerChanged(true);
                else if (Intent.ACTION_SCREEN_OFF.equals(a)) powerChanged(false);
                maybeBroadcastStatus();
            }
        }, f);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                appsChanged();
            }
        }, pf);
        rebind();
        if (screenOn()) powerChanged(true);   // the TV just booted: wake the PC too
    }

    /**
     * v2.14 "TV and PC power together" (pcPower, default off). On: Wake-on-LAN to the PC's MAC (from "pc_wol") and
     * "tv_power" on (cancels a pending suspend). Off (standby): "tv_power" standby, the PC suspends after its grace
     * time. Sent at once from the network thread: the TV may cut the network a few seconds after standby.
     */
    private void powerChanged(boolean on) {
        LauncherConfig cfg = LauncherConfig.get(app);
        if (!cfg.pcPower) return;
        bg.execute(() -> {
            if (on && !cfg.pcMac.isEmpty()) WakeOnLan.send(cfg.pcMac, cfg.pcBroadcast);
            if (!live() || server.connectedClients() == 0) return;
            try {
                server.broadcast("tv_power", new JSONObject().put("state", on ? "on" : "standby"));
            } catch (Exception ignored) {
            }
        });
    }

    private boolean polling;

    private void schedulePoll() {
        boolean want = live() && server.connectedClients() > 0;
        if (want && !polling) {
            polling = true;
            main.postDelayed(fgPoll, FG_POLL_MS);
        } else if (!want && polling) {
            polling = false;
            main.removeCallbacks(fgPoll);
        }
    }

    /** Hidden apps or the dock changed in the launcher. */
    static void appsChanged() {
        OmarchyLink r = runtime;
        if (r == null || !r.live() || r.server.connectedClients() == 0) return;
        bg.execute(() -> {
            try {
                r.server.broadcast("apps", new JSONObject().put("apps", r.apps()));
            } catch (Exception ignored) {
            }
        });
    }

    /** Foreground app via usage stats changes without any callback: a cheap poll, only while a PC listens. */
    private final Runnable fgPoll = new Runnable() {
        @Override
        public void run() {
            if (!live() || server.connectedClients() == 0) {
                polling = false;
                return;   // last PC left: stop, no timer while idle
            }
            if (screenOn()) maybeBroadcastStatus();
            main.postDelayed(this, FG_POLL_MS);
        }
    };

    private void maybeBroadcastStatus() {
        if (!live() || server.connectedClients() == 0) return;   // PC off: nothing to do, return at once
        bg.execute(() -> {
            JSONObject st = server.status();
            JSONObject fg = st.optJSONObject("foreground");
            String key = (fg != null ? fg.optString("package") : "") + st.optBoolean("homeVisible") + st.optBoolean("screensaver")
                    + st.optBoolean("screenOn") + st.optJSONArray("capabilities");
            synchronized (this) {
                if (key.equals(lastStatusKey)) return;
                lastStatusKey = key;
            }
            server.broadcast("status", st);
        });
    }

    /** Bind to the active network's IPv4 address (§3: not every interface). Link worker thread. */
    private synchronized void rebind() {
        if (!live()) return;
        InetAddress a = lanAddress();
        if (a != null && a.equals(bound)) return;
        unadvertise();
        bound = null;
        if (a == null) {
            server.stopListening();
            return;
        }
        try {
            server.start(a, LinkServer.PORT);
            bound = a;
            advertise();
        } catch (Exception e) {
            Log.w(TAG, "listen " + a + ": " + e);   // no retry loop: the next network change tries again
        }
    }

    private LinkProperties activeLink() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        return n != null ? cm.getLinkProperties(n) : null;
    }

    private InetAddress lanAddress() {
        LinkProperties lp = activeLink();
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) if (la.getAddress() instanceof Inet4Address) return la.getAddress();
        return null;
    }

    /** mDNS _omarchytv._tcp; TXT v, id, name, pair (only). Re-registered when pair changes. */
    private synchronized void advertise() {
        if (!live() || nsd == null || bound == null) return;
        boolean pair = pairingOpen();
        if (nsdReg != null && pair == nsdPair) return;
        unadvertise();
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(tvName());
        info.setServiceType("_omarchytv._tcp");
        info.setPort(LinkServer.PORT);
        info.setAttribute("v", "1");
        info.setAttribute("id", server.store().tvId());
        info.setAttribute("name", tvName());
        info.setAttribute("pair", pair ? "1" : "0");
        nsdPair = pair;
        nsdReg = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo s, int err) { Log.w(TAG, "nsd register " + err); }
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int err) { }
            @Override public void onServiceRegistered(NsdServiceInfo s) { }
            @Override public void onServiceUnregistered(NsdServiceInfo s) { }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdReg);
        } catch (RuntimeException e) {
            nsdReg = null;
        }
        if (pair) main.postDelayed(() -> bg.execute(this::advertise), PAIRING_WINDOW_MS + 1000);   // drop pair=1 when the window ends
    }

    private void unadvertise() {
        if (nsd != null && nsdReg != null) {
            try {
                nsd.unregisterService(nsdReg);
            } catch (RuntimeException ignored) {
            }
        }
        nsdReg = null;
    }

    private static String newCode() {
        return String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
    }

    private static void emit(String type, JSONObject data) {
        main.post(() -> {
            for (Listener l : listeners) l.onLinkEvent(type, data);
        });
    }

    private boolean screenOn() {
        PowerManager pm = app.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    // --------------------------------------------------------------- LinkServer.Host

    @Override
    public String tvName() {
        String n = Settings.Global.getString(app.getContentResolver(), "device_name");
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        n = n.replaceAll("[^\\p{L}\\p{N} ._()-]", "").trim();
        return n.isEmpty() ? "OmarchyTV" : (n.length() > 40 ? n.substring(0, 40) : n);
    }

    @Override
    public boolean pairingOpen() {
        return pairingVisible && code != null && SystemClock.elapsedRealtime() - pairingOpenedAt < PAIRING_WINDOW_MS;
    }

    @Override
    public String pairingCode() {
        return code;
    }

    @Override
    public void newPairingCode() {
        code = newCode();
        emit("pairing", new JSONObject());
    }

    /** §3: only the active network's own subnet (LinkProperties prefix). */
    @Override
    public boolean allowPeer(InetAddress peer) {
        LinkProperties lp = activeLink();
        if (lp == null || peer == null) return false;
        byte[] p = peer.getAddress();
        for (LinkAddress la : lp.getLinkAddresses()) {
            byte[] a = la.getAddress().getAddress();
            if (a.length != p.length) continue;
            int bits = la.getPrefixLength();
            if (bits < 8) continue;   // never "everything"
            boolean same = true;
            for (int i = 0; i < a.length && bits > 0; i++, bits -= 8) {
                int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                if ((a[i] & mask) != (p[i] & mask)) {
                    same = false;
                    break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    private boolean usageAccess() {
        AppOpsManager ops = app.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        @SuppressWarnings("deprecation")
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private String usageForeground() {
        UsageStatsManager usm = (UsageStatsManager) app.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(now - 6 * 3600_000L, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String fg = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) fg = e.getPackageName();
        }
        return fg;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = app.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    public JSONObject status() {
        try {
            JSONArray caps = new JSONArray();
            for (String c : new String[]{"theme", "wallpaper", "widgets", "ai", "apps", "launch", "events", "key:home",
                    "key:play_pause", "key:play", "key:pause", "key:stop", "key:next", "key:previous", "key:rewind",
                    "key:fast_forward", "key:volume_up", "key:volume_down", "key:mute"}) caps.put(c);
            if (OmarchyRemote.enabled()) caps.put("key:back");
            if (!LauncherConfig.get(app).aiProviders.isEmpty()) caps.put("ai-ask");
            JSONObject fg = null;
            String pkg = null, source = "launcher";
            if (homeVisible) pkg = app.getPackageName();
            else if (usageAccess()) {
                pkg = usageForeground();
                source = "usage-stats";
            }
            if (pkg == null && !homeVisible) pkg = lastLaunched;
            if (pkg != null) {
                fg = new JSONObject().put("package", pkg)
                        .put("label", pkg.equals(app.getPackageName()) ? "OmarchyTV" : label(pkg)).put("source", source);
            }
            return new JSONObject().put("tvId", server.store().tvId()).put("name", tvName()).put("protocol", 1)
                    .put("launcherVersion", version())
                    .put("foreground", fg == null ? JSONObject.NULL : fg)
                    .put("homeVisible", homeVisible).put("screensaver", screensaver).put("screenOn", screenOn())
                    .put("time", System.currentTimeMillis() / 1000).put("capabilities", caps);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String version() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    @Override
    public JSONArray apps() {
        JSONArray out = new JSONArray();
        PackageManager pm = app.getPackageManager();
        LauncherConfig cfg = LauncherConfig.get(app);
        Set<String> seen = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo ri : l) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(app.getPackageName()) || !seen.add(pkg)) continue;
                try {
                    ApplicationInfo ai = ri.activityInfo.applicationInfo;
                    out.put(new JSONObject().put("package", pkg).put("label", String.valueOf(ai.loadLabel(pm)))
                            .put("dock", cfg.dock.contains(pkg)).put("hidden", cfg.hidden.contains(pkg)));
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    @Override
    public boolean launch(String pkg) {
        PackageManager pm = app.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        final Intent fi = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        main.post(() -> {
            try {
                app.startActivity(fi);
                launched(pkg);
            } catch (Exception e) {
                Log.w(TAG, "launch " + pkg + ": " + e);
            }
        });
        return true;
    }

    @Override
    public int key(String key) {
        AudioManager am = app.getSystemService(AudioManager.class);
        switch (key) {
            case "home":
                main.post(() -> app.startActivity(new Intent(app, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
                return 200;
            case "volume_up":
            case "volume_down":
            case "mute":
                if (am == null) return 409;
                am.adjustSuggestedStreamVolume("mute".equals(key) ? AudioManager.ADJUST_TOGGLE_MUTE
                                : "volume_up".equals(key) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                return 200;
            case "back":
                return OmarchyRemote.back() ? 200 : 409;
            default:
                int code = mediaKey(key);
                if (code == 0 || am == null) return 409;   // up/down/left/right/select, power...: not in v1
                long t = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
                return 200;
        }
    }

    private static int mediaKey(String k) {
        switch (k) {
            case "play_pause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "play": return KeyEvent.KEYCODE_MEDIA_PLAY;
            case "pause": return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case "stop": return KeyEvent.KEYCODE_MEDIA_STOP;
            case "next": return KeyEvent.KEYCODE_MEDIA_NEXT;
            case "previous": return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case "rewind": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "fast_forward": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            default: return 0;
        }
    }

    @Override
    public void editConfig(LinkServer.ConfigEdit edit) throws Exception {
        LauncherConfig.updateOrThrow(app, edit::edit);
        emit("config", new JSONObject());
    }

    @Override
    public File wallpaperDir() {
        File d = app.getExternalFilesDir(null);
        return new File(d != null ? d : app.getFilesDir(), "link");
    }

    @Override
    public int[] jpegSize(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        return o.outWidth > 0 && o.outHeight > 0 && "image/jpeg".equals(o.outMimeType) ? new int[]{o.outWidth, o.outHeight} : null;
    }

    @Override
    public void onPcsChanged(String pairedName) {
        try {
            JSONArray pcs = server.store().publicPcs();
            LauncherConfig.updateOrThrow(app, j -> {
                JSONObject l = j.optJSONObject("link");
                if (l == null) j.put("link", l = new JSONObject());
                l.put("pcs", pcs);
            });
        } catch (Exception e) {
            Log.w(TAG, "link.pcs: " + e);
        }
        JSONObject d = new JSONObject();
        try {
            if (pairedName != null) d.put("paired", pairedName);
        } catch (Exception ignored) {
        }
        emit("pcs", d);
    }

    // --------------------------------------------------------------- cast (PROTOCOL.md §6 Cast)

    @Override
    public java.util.List<String> castKinds() {
        return CastRouter.kinds(app);
    }

    @Override
    public File castDir() {
        return CastRouter.dir(app);
    }

    @Override
    public String openCast(com.omarchytv.home.link.Cast c) {
        return CastRouter.open(app, c);
    }

    @Override
    public String castControl(String id, String action, double position) {
        return CastRouter.control(app, id, action, position);
    }

    /** Player / viewer state for the PC (any thread). Socket I/O goes to the Link worker. No PC: returns at once. */
    /** Sends a TV -> PC mpv handoff event with current URL and playback position in seconds. */
    static void handoffToPc(String title, String url, double position) {
        OmarchyLink r = runtime;
        if (r == null || !r.live()) return;
        bg.execute(() -> {
            try {
                r.server.cinemarchyHandoffToPc(new JSONObject()
                        .put("title", title != null ? title : "")
                        .put("url", url != null ? url : "")
                        .put("position", position));
            } catch (Exception ignored) {
            }
        });
    }

    /** v2.13: asks paired PCs to pause/resume their mpv. Returns false when no PC is connected. */
    static boolean mpvControl(String action) {
        OmarchyLink r = runtime;
        if (r == null || !r.live()) return false;
        bg.execute(() -> {
            try {
                r.server.cinemarchyMpv(action);
            } catch (Exception ignored) {
            }
        });
        return true;
    }

    static void castState(String id, String state, double position, double duration, String error) {
        OmarchyLink r = runtime;
        if (r == null || !r.live()) return;
        bg.execute(() -> r.server.castState(id, state, position, duration, error));
    }

    /** Home resumed: 24 h cleanup of cast uploads, on the Link worker, only when the cache exists. */
    static void sweepCasts(Context c) {
        OmarchyLink r = runtime;
        if (r == null || !CastRouter.dir(r.app).isDirectory()) return;
        bg.execute(() -> CastRouter.sweep(r.app, r.live() ? r.server.currentCastFile() : null));
    }

    @Override
    public void onEvent(String pcId, String type, JSONObject data) {
        if ("living".equals(type)) {   // v2.14: kept for the screensaver page, not an event for the UI
            Living.set(data);
            return;
        }
        if ("pc_wol".equals(type)) {   // remember the PC's MAC for Wake-on-LAN; rewrite only when it changed
            LauncherConfig cur = LauncherConfig.get(app);
            String mac = data.optString("mac", ""), bc = data.optString("broadcast", "");
            if (!mac.equals(cur.pcMac) || !bc.equals(cur.pcBroadcast)) {
                LauncherConfig.update(app, j -> {
                    JSONObject link = j.optJSONObject("link");
                    if (link == null) j.put("link", link = new JSONObject());
                    link.put("pcWol", data);
                });
            }
            return;
        }
        emit(type, data);
    }

    @Override
    public void log(String message) {
        Log.i(TAG, message);
    }
}
