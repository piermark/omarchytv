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
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import com.omarchytv.home.link.Tailnet;
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
 * Lifetime: the server lives in the launcher process. Once a PC is paired, {@link KeepAlive} is a foreground
 * service (a quiet low-importance notification, type connectedDevice, no wakelock) started at boot and after an
 * update by {@link HomeCheck}, from {@link MainActivity#onCreate} and on pairing, so the server stays reachable
 * whether or not OmarchyTV is in front, and keeps network access while the TV dozes. START_STICKY brings it
 * back if Android stops it. After sleep the existing SCREEN_ON and network-callback paths rebind the socket.
 * With Link disabled or no PC paired there is no resident service (the in-process server still runs while
 * the launcher is up, as before).
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

    /** Called from MainActivity.onCreate: runs the server in-process; promotes it to the foreground service when a PC is paired. */
    static void start(Context c) {
        ensure(c.getApplicationContext());
        // Never throws: a refused start leaves the in-process server running. Link switched off (or the last PC
        // gone) since the service started: it stops, so the off switch takes effect without a reboot.
        LinkBoot.reconcile(c);
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

    /** v2.20 GrokBot Office: office_hire to the PC (Link worker); {@code done} on the UI thread with null or the error. */
    static void officeHire(JSONObject data, java.util.function.Consumer<String> done) {
        OmarchyLink r = runtime;
        if (r == null || !r.live()) {
            done.accept("not_connected");
            return;
        }
        bg.execute(() -> {
            String e = r.server.officeHire(data);
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
    // ---- v2.21 app API (dashboards, agents): questions, pages, app tokens -------------------------------------

    /** The answer picked on the TV (AgentQuestionActivity). */
    static boolean answerAsk(String id, String choice) {
        OmarchyLink r = runtime;
        return r != null && r.live() && r.server.answerAsk(id, choice);
    }

    /** Home came back: show the oldest question still waiting, if any. */
    static void showPendingAsk(android.app.Activity a) {
        OmarchyLink r = runtime;
        if (r == null || !r.live()) return;
        java.util.List<String[]> p = r.server.pendingAsks();
        if (p.isEmpty()) return;
        String[] q = p.get(0);
        java.util.List<String> choices = new java.util.ArrayList<>(java.util.Arrays.asList(q).subList(4, q.length));
        a.startActivity(AgentQuestionActivity.intent(a, q[0], q[1], q[2], choices, Long.parseLong(q[3])));
    }

    /** v2.21 Settings › System info: the same as /v1/diagnostics, with whole-server numbers when Link runs. */
    static JSONObject systemInfo(Context c) {
        OmarchyLink r = runtime;
        if (r != null && r.live()) {
            JSONObject d = r.diagnostics();
            try {
                d.put("link", new JSONObject().put("pcs", r.server.store().list().size()).put("events", r.server.connectedClients())
                        .put("appTokens", r.server.appTokenCount()).put("pendingQuestions", r.server.pendingAsks().size()));
            } catch (org.json.JSONException ignored) {
            }
            return d;
        }
        try {
            return new JSONObject().put("launcherVersion", c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName)
                    .put("device", new JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
                    .put("network", new JSONObject().put("listening", false));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static int pendingAsks() {
        OmarchyLink r = runtime;
        return r != null && r.live() ? r.server.pendingAsks().size() : 0;
    }

    static int appTokens() {
        OmarchyLink r = runtime;
        return r != null && r.live() ? r.server.appTokenCount() : 0;
    }

    static String[] newAppToken(String name, String scope) {
        OmarchyLink r = runtime;
        return r != null && r.live() ? r.server.newAppToken(name, scope) : null;
    }

    static int revokeAppTokens() {
        OmarchyLink r = runtime;
        return r != null && r.live() ? r.server.revokeAppTokens() : 0;
    }

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
    /** v2.14 Omastorm from the PC: action "start" | "key" (with key) | "stop". */
    /** Cinemarchy: ask the PC for a folder of its media ("" = its shared folders); the answer is a "media_list" event. */
    static void mediaList(String path) {
        mediaSend("media_list", path, -1);
    }

    /** Cinemarchy: play one of the PC's files here (the PC streams it once, to this TV only, like omarchy-tv send). */
    static void mediaPlay(String path, double position) {
        mediaSend("media_play", path, position);
    }

    private static void mediaSend(String type, String path, double position) {
        OmarchyLink r = runtime;
        if (r == null || !r.live() || r.server.connectedClients() == 0) return;
        bg.execute(() -> {
            try {
                JSONObject o = new JSONObject().put("path", path == null ? "" : path);
                if (position > 0) o.put("position", position);
                r.server.broadcast(type, o);
            } catch (Exception ignored) {
            }
        });
    }

    static void omastorm(String action, String key) {
        OmarchyLink r = runtime;
        if (r == null || !r.live() || r.server.connectedClients() == 0) return;
        bg.execute(() -> {
            try {
                JSONObject o = new JSONObject().put("action", action);
                if (key != null) o.put("key", key);
                r.server.broadcast("omastorm", o);
            } catch (Exception ignored) {
            }
        });
    }

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

    /**
     * Foreground service (low-importance notification, connectedDevice) that keeps the launcher process, and so
     * the Link server, alive. Holds nothing itself: no wakelock, no timer.
     */
    public static final class KeepAlive extends Service {
        @Override
        public void onCreate() {
            super.onCreate();
            ensure(getApplicationContext());
        }

        private int lastStartId;

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            lastStartId = startId;
            // Always first, even for a sticky restart with a null intent: a startForegroundService call must be answered.
            if (!LinkBoot.promote(this)) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (!LinkBoot.wanted(getApplicationContext())) {   // Link off, or no PC paired: no resident service
                retire();
                return START_NOT_STICKY;
            }
            LinkBoot.promoted = this;
            return START_STICKY;
        }

        /**
         * Main thread. stopSelf(lastStartId) does nothing when a newer start is already queued; that start's
         * onStartCommand promotes and decides again, so a pending start is never stopped before startForeground.
         */
        void retire() {
            if (LinkBoot.promoted == this) LinkBoot.promoted = null;
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf(lastStartId);
        }

        @Override
        public void onDestroy() {
            if (LinkBoot.promoted == this) LinkBoot.promoted = null;
            super.onDestroy();
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
            // v2.21: with a VPN (Tailscale) up, the default network is the VPN; the Wi-Fi/Ethernet under it and the
            // tailnet coming and going are only seen by a callback on every network, VPNs included.
            try {
                cm.registerNetworkCallback(new NetworkRequest.Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                        new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(Network network) {
                                bg.execute(OmarchyLink.this::rebind);
                            }

                            @Override
                            public void onLost(Network network) {
                                bg.execute(OmarchyLink.this::rebind);
                            }
                        });
            } catch (RuntimeException e) {
                Log.w(TAG, "network callback: " + e);
            }
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

    private volatile String bindKey;

    /**
     * Bind to the LAN's IPv4 address (§3: not every interface). v2.21: with Tailscale allowed and a tailnet address
     * on this TV, listen on all IPv4 interfaces instead; allowPeer still lets in only the LAN subnet and the tailnet.
     * Link worker thread.
     */
    private synchronized void rebind() {
        if (!live()) return;
        Tailnet.allowed = LauncherConfig.get(app).tailscale;
        InetAddress a = lanAddress();
        InetAddress ts = Tailnet.allowed ? tailnetAddress() : null;
        InetAddress listen = ts != null ? anyV4() : a;
        String key = a + "|" + ts;
        if (listen != null && key.equals(bindKey) && bound != null) return;
        unadvertise();
        bound = null;
        bindKey = null;
        if (listen == null) {
            server.stopListening();
            return;
        }
        try {
            if (ts != null) {
                // v2.21 Tailscale on: dual stack (::), so the tailnet's IPv6 (fd7a:115c:a1e0::/48) works too; a TV whose
                // kernel has no IPv6 falls back to IPv4 only.
                try {
                    server.start(InetAddress.getByName("::"), LinkServer.PORT);
                } catch (Exception v6) {
                    Log.i(TAG, "no IPv6 listener (" + v6 + "): IPv4 only");
                    server.start(listen, LinkServer.PORT);
                }
            } else {
                server.start(listen, LinkServer.PORT);
            }
            bound = a != null ? a : ts;
            bindKey = key;
            if (ts != null) Log.i(TAG, "Tailscale on: listening for the LAN " + a + " and the tailnet " + ts.getHostAddress());
            advertise();
        } catch (Exception e) {
            Log.w(TAG, "listen " + listen + ": " + e);   // no retry loop: the next network change tries again
        }
    }

    private static InetAddress anyV4() {
        try {
            return InetAddress.getByAddress(new byte[4]);
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    /**
     * The LAN under any VPN: the active network when it is not a VPN, else a Wi-Fi or Ethernet network with IPv4.
     * Before v2.21 a VPN app (Tailscale) made the TV bind to the VPN address and refuse the PC next to it.
     */
    private LinkProperties lanLink() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        Network active = cm.getActiveNetwork();
        if (active != null && !isVpn(cm, active)) return cm.getLinkProperties(active);
        LinkProperties best = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null || v4(lp) == null) continue;
            if (best == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) best = lp;   // wired first
        }
        return best;
    }

    private static boolean isVpn(ConnectivityManager cm, Network n) {
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private static InetAddress v4(LinkProperties lp) {
        for (LinkAddress la : lp.getLinkAddresses()) if (la.getAddress() instanceof Inet4Address) return la.getAddress();
        return null;
    }

    private InetAddress lanAddress() {
        LinkProperties lp = lanLink();
        return lp == null ? null : v4(lp);
    }

    /** This TV's Tailscale address (100.64.0.0/10 on a VPN network), or null. */
    private InetAddress tailnetAddress() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            if (!isVpn(cm, n)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address && Tailnet.isTailnet(la.getAddress())) return la.getAddress();
            }
        }
        return null;
    }

    /** Settings: the TV's LAN address (under any VPN). */
    static String lanIp() {
        OmarchyLink r = runtime;
        InetAddress a = r != null ? r.lanAddress() : null;
        return a == null ? null : a.getHostAddress();
    }

    /** Settings: the TV's Tailscale address, or null when there is none. */
    static String tailnetIp() {
        OmarchyLink r = runtime;
        InetAddress a = r != null ? r.tailnetAddress() : null;
        return a == null ? null : a.getHostAddress();
    }

    /** Settings toggled Tailscale: bind again (all interfaces or the LAN only). */
    static void tailscaleChanged() {
        OmarchyLink r = runtime;
        if (r != null) bg.execute(r::rebind);
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

    /** §3: only the LAN's own subnet (LinkProperties prefix, under any VPN); v2.21 plus the tailnet when allowed. */
    @Override
    public boolean allowPeer(InetAddress peer) {
        LinkProperties lp = peer == null || Tailnet.allowedPeer(peer) ? null : lanLink();
        List<LinkAddress> las = lp == null ? java.util.Collections.emptyList() : lp.getLinkAddresses();
        byte[][] addrs = new byte[las.size()][];
        int[] bits = new int[las.size()];
        for (int i = 0; i < las.size(); i++) {
            addrs[i] = las.get(i).getAddress().getAddress();
            bits[i] = las.get(i).getPrefixLength();
        }
        return Tailnet.peerAllowed(peer, addrs, bits);
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
        LinkBoot.reconcile(app);   // re-reads link.json on the main thread: concurrent callbacks can't race
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

    /** v2.21 GET /v1/theme: the theme on screen, unless the owner turned sharing off. Server thread. */
    @Override
    public JSONObject sharedTheme() {
        LauncherConfig cfg = LauncherConfig.get(app);
        if (!cfg.shareTheme) return null;
        try {
            ThemeShare.Wallpaper wp = ThemeShare.wallpaper(app);
            return ThemeShare.payload(Theme.cur(), wp, ThemeShare.source(themeCfg()));
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject themeCfg() {
        JSONObject j = LauncherConfig.readJson(app);
        return j != null ? j.optJSONObject("theme") : null;
    }

    @Override
    public com.omarchytv.home.link.LinkServer.Blob sharedWallpaper() {
        if (!LauncherConfig.get(app).shareTheme) return null;
        ThemeShare.Wallpaper wp = ThemeShare.wallpaper(app);
        return wp == null ? null : new com.omarchytv.home.link.LinkServer.Blob(wp.bytes, wp.type, wp.sha256);
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

    /** v2.21 notifications that waited for Home (level info while an app was on screen); at most 10, oldest dropped. */
    private static final java.util.ArrayDeque<String> HELD = new java.util.ArrayDeque<>();

    /** v2.21 POST /v1/notify, MCP tv_notify: only with agent alerts on; "info" waits while an app is on screen. */
    @Override
    public String notify(String from, String title, String text, String level) {
        if (!LauncherConfig.get(app).agentAlert) return null;
        String body = title.isEmpty() ? text : text.isEmpty() ? title : title + " · " + text;
        String msg = app.getString(R.string.agent_note, from, body);
        try {
            emit("agent", new JSONObject().put("name", from).put("text", body));
        } catch (org.json.JSONException ignored) {
        }
        if ("info".equals(level) && !homeVisible && screenOn()) {
            synchronized (HELD) {
                if (HELD.size() >= 10) HELD.removeFirst();
                HELD.addLast(msg);
            }
            return "deferred";
        }
        main.post(() -> android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show());
        if (!"info".equals(level) && LauncherConfig.get(app).notifySound) chime("alert".equals(level));
        return "shown";
    }

    /** v2.21 warn: one short tone; alert: two. On the notification stream, so the TV's own volume rules apply. */
    private void chime(boolean twice) {
        bg.execute(() -> {
            android.media.ToneGenerator tg = null;
            try {
                tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70);
                tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 160);
                if (twice) {
                    Thread.sleep(260);
                    tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 220);
                }
                Thread.sleep(300);
            } catch (Exception ignored) {
                // no tone on this TV: the toast is enough
            } finally {
                if (tg != null) tg.release();
            }
        });
    }

    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady;
    private String ttsPending;

    /** v2.21 notify "speak": read it aloud in the TV's language; the engine starts on first use. */
    @Override
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        String say = text.length() > 300 ? text.substring(0, 300) : text;
        main.post(() -> {
            if (tts == null) {
                ttsPending = say;
                tts = new android.speech.tts.TextToSpeech(app, st -> {
                    ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS;
                    if (ttsReady && ttsPending != null) {
                        tts.speak(ttsPending, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "otv-notify");
                        ttsPending = null;
                    }
                });
            } else if (ttsReady) {
                tts.speak(say, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "otv-notify");
            } else {
                ttsPending = say;
            }
        });
    }

    /** Home is back: the notifications that waited, one toast (the last three lines). */
    static void flushHeldNotes(Context c) {
        StringBuilder b = new StringBuilder();
        int n;
        synchronized (HELD) {
            n = HELD.size();
            int skip = Math.max(0, n - 3);
            for (String m : HELD) if (skip-- <= 0) b.append(b.length() == 0 ? "" : "\n").append(m);
            HELD.clear();
        }
        if (n == 0) return;
        if (n > 3) b.insert(0, "+" + (n - 3) + "\n");
        android.widget.Toast.makeText(c.getApplicationContext(), b.toString(), android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    public JSONObject nowPlaying() {
        String[] np = RadioPlayer.nowPlaying();
        if (np == null) return null;
        try {
            return new JSONObject().put("title", np[0]).put("artist", np[1]).put("source", "radiomarchy");
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** v2.21 ask_user: only with agent alerts on; the dialog opens over the current app when Android allows it. */
    @Override
    public String askUser(String id, String from, String question, java.util.List<String> choices, long deadlineMs) {
        if (!LauncherConfig.get(app).agentAlert) return "ask_off";
        String msg = app.getString(R.string.agent_note, from, question);
        main.post(() -> {
            try {
                app.startActivity(AgentQuestionActivity.intent(app, id, from, question, choices, deadlineMs));
            } catch (RuntimeException e) {
                Log.w(TAG, "ask: " + e);
            }
            android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show();   // seen even if the dialog waits for Home
        });
        log("question from " + from);
        return null;
    }

    @Override
    public String openPage(String url, String from) {
        if (!AgentPageActivity.allowed(url)) return "not_lan_url";
        main.post(() -> {
            try {
                app.startActivity(AgentPageActivity.intent(app, url));
            } catch (RuntimeException e) {
                Log.w(TAG, "page: " + e);
            }
        });
        log("page from " + from + ": " + android.net.Uri.parse(url).getHost());
        return null;
    }

    @Override
    public String openPages(java.util.List<String> urls, String from) {
        java.util.List<String> ok = new java.util.ArrayList<>();
        for (String u : urls) if (AgentPageActivity.allowed(u)) ok.add(u);
        if (ok.isEmpty()) return "not_lan_url";
        main.post(() -> {
            try {
                app.startActivity(AgentPageActivity.intent(app, ok));
            } catch (RuntimeException e) {
                Log.w(TAG, "pages: " + e);
            }
        });
        log("pages from " + from + ": " + ok.size());
        return null;
    }

    @Override
    public boolean closePage() {
        return AgentPageActivity.closeOpen();
    }

    /** v2.21 Radiomarchy for agents; runs on the main thread (the player lives there), waits at most 3 s. */
    @Override
    public JSONObject radio(String action) {
        final JSONObject[] out = {null};
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> {
            try {
                RadioPlayer p = RadioPlayer.get(app);
                switch (action) {
                    case "play": p.playOrStart(); break;
                    case "pause": p.pause(); break;
                    case "next": p.advance(1, true); break;
                    case "previous": p.advance(-1, true); break;
                    case "stop": p.stop(); break;
                    default: break;
                }
                RadioCatalog.Track t = p.current();
                out[0] = new JSONObject().put("playing", p.playing()).put("title", t == null ? JSONObject.NULL : t.title)
                        .put("artist", t == null ? JSONObject.NULL : t.artist).put("sleepMinutes", p.sleepLeft());
            } catch (Exception e) {
                Log.w(TAG, "radio " + action + ": " + e);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log("radio " + action);
        return out[0];
    }

    @Override
    public String openTarget(String target) {
        Class<?> c;
        switch (target == null ? "" : target) {
            case "home": c = MainActivity.class; break;
            case "radiomarchy": c = RadioActivity.class; break;
            case "cinemarchy": c = CinemarchyActivity.class; break;
            case "arcade": c = ArcadeActivity.class; break;
            case "office": c = OfficeActivity.class; break;
            default: return "no_such_target";
        }
        Class<?> fc = c;
        main.post(() -> {
            try {
                app.startActivity(new Intent(app, fc).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (RuntimeException e) {
                Log.w(TAG, "open " + target + ": " + e);
            }
        });
        log("open " + target);
        return null;
    }

    @Override
    public JSONObject config() {
        return LauncherConfig.readJson(app);
    }

    @Override
    public byte[] sdk() {
        try (java.io.InputStream in = app.getAssets().open("sdk/omarchytv.js")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** v2.21 GET /v1/diagnostics, MCP tv_diagnostics: what a bug report needs. No tokens, no keys, no file paths. */
    @Override
    public JSONObject diagnostics() {
        try {
            LauncherConfig cfg = LauncherConfig.get(app);
            android.app.ActivityManager am = app.getSystemService(android.app.ActivityManager.class);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            String gpu = app.getSharedPreferences("omarchytv", Context.MODE_PRIVATE).getString(FractalView.PREF_GPU, "");
            InetAddress ts = tailnetAddress();
            JSONArray logs = new JSONArray();
            synchronized (LOG) {
                for (String l : LOG) logs.put(l);
            }
            return new JSONObject()
                    .put("launcherVersion", version())
                    .put("device", new JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                            .put("ramMb", mi.totalMem / (1024 * 1024)).put("lowRam", am != null && am.isLowRamDevice())
                            .put("gpu", gpu))
                    .put("network", new JSONObject().put("lan", lanIp() == null ? JSONObject.NULL : lanIp())
                            .put("tailnet", ts == null ? JSONObject.NULL : ts.getHostAddress())
                            .put("listening", bound != null))
                    .put("settings", new JSONObject().put("shareTheme", cfg.shareTheme).put("tailscale", cfg.tailscale)
                            .put("agentAlerts", cfg.agentAlert).put("cast", cfg.castEnabled))
                    .put("screen", new JSONObject().put("on", screenOn()).put("home", homeVisible).put("screensaver", screensaver))
                    .put("log", logs);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public void onEvent(String pcId, String type, JSONObject data) {
        if ("living".equals(type)) {   // v2.14: kept for the screensaver page, not an event for the UI
            Living.set(data);
            return;
        }
        if ("pulse".equals(type)) {   // v2.15: the PC's Pulse, for the Pulse screensaver (PulseLink, memory only)
            PulseLink.put(data.toString());
            return;
        }
        if ("agent".equals(type)) {   // v2.15: an AI agent on the PC is waiting for you (opt-in, over any app)
            if (!LauncherConfig.get(app).agentAlert) return;
            String name = data.optString("name", "agent"), what = data.optString("text", "");
            String msg = app.getString(R.string.agent_alert, name.length() > 40 ? name.substring(0, 40) : name, what.length() > 80 ? what.substring(0, 80) : what);
            main.post(() -> android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show());
            emit(type, data);
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

    /** The last 100 Link log lines, in memory only, for /v1/diagnostics. */
    private static final java.util.ArrayDeque<String> LOG = new java.util.ArrayDeque<>();

    @Override
    public void log(String message) {
        Log.i(TAG, message);
        String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + " "
                + (message.length() > 200 ? message.substring(0, 200) : message);
        synchronized (LOG) {
            if (LOG.size() >= 100) LOG.removeFirst();
            LOG.addLast(line);
        }
    }
}
