package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Omarchy Link v1 server, the TV column of omarchy-link/docs/PROTOCOL.md: HTTP/1.1 + RFC 6455 WebSocket on
 * TCP 47800, SPAKE2 pairing, signed requests/responses, sealed events. Plain Java on java.net sockets (no
 * android.*): the Android side is a {@link Host}; the JVM tests and harness use a fake one.
 *
 * Light on purpose: one accept thread, a small bounded pool (idle threads exit after 30 s), one timer
 * thread for WebSocket pings, no wakelocks. Everything stops with the launcher process.
 */
public final class LinkServer {

    public static final int PORT = 47800;
    static final int JSON_MAX = 64 * 1024, JPEG_MAX = 8 * 1024 * 1024, WS_MAX = 1024 * 1024, HEADER_MAX = 16 * 1024;
    static final long SKEW_S = 120, NONCE_TTL_MS = 240_000L, PAIR_TTL_MS = 60_000L;
    static final int MAX_FAILURES = 5, MAX_WS = 4, MAX_CONN = 12;

    static final Set<String> KEYS = new HashSet<>(Arrays.asList("home", "back", "up", "down", "left", "right", "select",
            "play_pause", "play", "pause", "stop", "next", "previous", "rewind", "fast_forward",
            "volume_up", "volume_down", "mute", "power", "sleep", "wakeup"));
    static final Pattern WIDGET_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,47}$");
    static final Pattern PROVIDER_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,31}$");
    static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    static final Pattern PC_ID = Pattern.compile("^[A-Za-z0-9]{8,64}$");

    /** What the server needs from the launcher. Called on server threads; implementations must be thread-safe. */
    public interface Host {
        String tvName();

        /** The Connect Omarchy Linux page is visible (and opened at most 5 minutes ago). */
        boolean pairingOpen();

        /** The 6-digit code on screen (memory only). */
        String pairingCode();

        /** Show a fresh code (after a success, or 5 failures). */
        void newPairingCode();

        /** Peer is on the TV's own subnet. */
        boolean allowPeer(InetAddress peer);

        JSONObject status();

        JSONArray apps();

        boolean launch(String pkg);

        /** 200 done, 409 capability missing right now. Only called with a key from {@link #KEYS}. */
        int key(String key);

        /** One read-modify-write of omarchytv.json (LauncherConfig.update). */
        void editConfig(ConfigEdit edit) throws Exception;

        /** Where pushed wallpapers live (getExternalFilesDir("link")); the path goes into omarchytv.json. */
        File wallpaperDir();

        /** {width, height} of a JPEG, or null when it does not decode. */
        int[] jpegSize(byte[] jpeg);

        /** link.pcs / pairing state changed: refresh omarchytv.json link.pcs and the pairing page. */
        void onPcsChanged(String pairedName);

        /** answer / answer_error / ai_sources_* / agent_* from a PC (agent data already bounded, plain text). */
        void onEvent(String pcId, String type, JSONObject data);

        void log(String message);

        /** Cast kinds the launcher can open right now (PROTOCOL.md, Cast). Empty = no "cast" capability. */
        List<String> castKinds();

        /** getCacheDir()/cast: uploads only, never shared storage. null = no "cast-upload". */
        File castDir();

        /**
         * Opens a validated cast (the activity start is posted to the main thread). Returns null when it opens,
         * or an error code ("no_app"). Called on a server thread; only fixed intents are built from {@code c}.
         */
        String openCast(Cast c);

        /** play / pause / stop / seek on the open cast. Returns null when done, or an error code ("nothing_playing"). */
        String castControl(String id, String action, double position);
    }

    public interface ConfigEdit {
        void edit(JSONObject json) throws Exception;
    }

    private final Host host;
    private final LinkStore store;
    private volatile ServerSocket server;
    private volatile boolean running;
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService timer;
    private final List<WsClient> clients = new CopyOnWriteArrayList<>();
    private final Map<String, Long> nonces = new HashMap<>();
    private final Map<String, long[]> rates = new HashMap<>();
    private final Object pairLock = new Object();
    private Pending pending;
    private int failures;
    private volatile String providersFrom, aiSourcesFrom;
    private long lastSourcesMs;
    private long lastAskMs, lastAgentAskMs;
    /** Last agent list (§6 Agents), already bounded; and the PC that sends it (agent_ask goes there). */
    private volatile JSONObject agentsNow;
    private volatile JSONObject cinemarchyNow;
    private volatile JSONObject snippetsNow;
    private volatile String agentsFrom;
    private volatile String grokbotFrom;   // PC whose omarchy-link last pushed the Grok Bot companion state
    private long lastGrokbotAskMs;
    /** Seconds; tests shift it. */
    volatile long clockOffsetS;
    /** Rate limits (§3). Only the JVM harness that runs the PC test-suite turns them off, like the PC's mock TV. */
    volatile boolean limits = true;
    /** PUT /v1/cast/file limit (Status castUploadMax). The cache keeps at most CAST_FILES files / CAST_BYTES, 24 h each. */
    volatile long castUploadMax = 64L << 20;
    static final int CAST_FILES = 8;
    static final long CAST_BYTES = 512L << 20, CAST_FREE_MIN = 64L << 20;
    private final Object castLock = new Object();
    private String castId, castFileSha;
    private boolean castActive;
    /** Test hook: called with "METHOD path bodyLength" after a request authenticated. */
    volatile java.util.function.Consumer<String> requestLog;

    private static final class Pending {
        String id, pcId, pcName;
        LinkCrypto.TvPairing b;
        long expires;
    }

    public LinkServer(Host host, LinkStore store) {
        this.host = host;
        this.store = store;
        pool = new ThreadPoolExecutor(0, MAX_CONN, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "otv-link");
            t.setDaemon(true);
            return t;
        });
        // Idle = passive: the ping thread exists only while a PC has an event socket open (see linkUp/linkDown).
        java.util.concurrent.ScheduledThreadPoolExecutor st = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "otv-link-ping");
            t.setDaemon(true);
            return t;
        });
        st.setKeepAliveTime(5, TimeUnit.SECONDS);
        st.allowCoreThreadTimeOut(true);
        st.setRemoveOnCancelPolicy(true);
        timer = st;
    }

    private java.util.concurrent.ScheduledFuture<?> pinger;
    /** Test hook / Host: the number of PC event sockets changed. */
    volatile Runnable onClientsChanged;

    private synchronized void linkUp(WsClient c) {
        clients.add(c);
        if (pinger == null) pinger = timer.scheduleWithFixedDelay(this::pingAll, 25, 25, TimeUnit.SECONDS);
        Runnable r = onClientsChanged;
        if (r != null) r.run();
    }

    private synchronized void linkDown(WsClient c) {
        clients.remove(c);
        if (clients.isEmpty() && pinger != null) {
            pinger.cancel(false);
            pinger = null;
        }
        Runnable r = onClientsChanged;
        if (r != null) r.run();
    }

    /** True when nothing is scheduled: no PC connected means no timers at all (PC off = zero cost). */
    public synchronized boolean idle() {
        return pinger == null && clients.isEmpty();
    }

    public void setOnClientsChanged(Runnable r) {
        onClientsChanged = r;
    }

    public LinkStore store() {
        return store;
    }

    long now() {
        return System.currentTimeMillis() / 1000 + clockOffsetS;
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    /** (Re)binds to {@code address} (the LAN address; null = all interfaces) on {@code port}. */
    public synchronized int start(InetAddress address, int port) throws IOException {
        stopListening();
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(address, port), 16);
        server = s;
        running = true;
        Thread t = new Thread(() -> acceptLoop(s), "otv-link-accept");
        t.setDaemon(true);
        t.start();
        host.log("listening on " + s.getLocalSocketAddress());
        return s.getLocalPort();
    }

    public synchronized void stopListening() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void shutdown() {
        running = false;
        stopListening();
        for (WsClient c : clients) c.close();
        timer.shutdownNow();
        pool.shutdownNow();
    }

    public boolean listening() {
        ServerSocket s = server;
        return s != null && !s.isClosed();
    }

    private void acceptLoop(ServerSocket s) {
        while (running && !s.isClosed()) {
            Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                break;
            }
            try {
                pool.execute(() -> serve(c));
            } catch (RejectedExecutionException e) {
                closeQuietly(c);   // over MAX_CONN: drop
            }
        }
    }

    // ---- HTTP --------------------------------------------------------------------------------------

    static final class Request {
        String method, target, version;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        /** Streamed upload: the body went to {@link #upload}; this is hex(SHA-256) of it. */
        String bodySha;
        File upload;
        long bodyLen;
        String peer;

        String header(String name) {
            String v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null ? "" : v;
        }
    }

    static final class Response {
        int status;
        byte[] body;
        final Map<String, String> headers = new java.util.LinkedHashMap<>();
        boolean close;

        Response(int status, JSONObject obj) {
            this.status = status;
            this.body = obj == null ? new byte[0] : LinkCrypto.utf8(obj.toString());
        }
    }

    private void serve(Socket sock) {
        try {
            sock.setSoTimeout(15_000);
            sock.setTcpNoDelay(true);
            InetAddress peer = sock.getInetAddress();
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            if (!host.allowPeer(peer)) {
                Response r = new Response(403, err("not_lan", "LAN clients only"));
                r.close = true;
                write(out, r);
                return;
            }
            while (running) {
                Request req = readHead(in);
                if (req == null) return;
                req.peer = peer.getHostAddress();
                Response r = handle(req, in, out, sock);
                if (r == null) return;   // upgraded to WebSocket (and finished), or already answered
                if ("close".equalsIgnoreCase(req.header("Connection")) || "HTTP/1.0".equals(req.version)) r.close = true;
                write(out, r);
                if (r.close) return;
            }
        } catch (IOException | RuntimeException e) {
            // peer went away, timeout, or malformed input
        } finally {
            lingerClose(sock);
        }
    }

    /**
     * Close after an answer without losing it. A refused request (403 not_lan, 413 too_large, ...) is answered
     * before its body is read; closing a socket with unread input makes the kernel send RST instead of FIN, and the
     * RST can destroy the answer before the client reads it ("Connection reset" instead of the 403/413). So: FIN
     * first, then read and drop what the client still sends (at most 256 KiB, at most 1 s), then close.
     */
    static void lingerClose(Socket sock) {
        try {
            if (!sock.isClosed() && sock.isConnected() && !sock.isOutputShutdown()) {
                sock.shutdownOutput();
                sock.setSoTimeout(250);
                InputStream in = sock.getInputStream();
                byte[] buf = new byte[8192];
                long until = System.currentTimeMillis() + 1000;
                int total = 0, n;
                while (total < 256 * 1024 && System.currentTimeMillis() < until && (n = in.read(buf)) >= 0) total += n;
            }
        } catch (IOException | RuntimeException ignored) {
            // timeout or the peer already closed: nothing left to save
        } finally {
            closeQuietly(sock);
        }
    }

    /** Request line + headers; null on EOF. Throws on oversize or garbage. */
    static Request readHead(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        List<String> lines = new ArrayList<>();
        int total = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (lines.isEmpty() && line.size() == 0) return null;
                throw new IOException("eof in headers");
            }
            if (++total > HEADER_MAX) throw new IOException("headers too large");
            if (b == '\n') {
                String l = line.toString("ISO-8859-1");
                if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
                line.reset();
                if (l.isEmpty()) {
                    if (lines.isEmpty()) continue;   // tolerate stray CRLF between requests
                    break;
                }
                lines.add(l);
            } else {
                line.write(b);
            }
        }
        String[] rl = lines.get(0).split(" ");
        if (rl.length != 3 || !rl[2].startsWith("HTTP/1.")) throw new IOException("bad request line");
        Request r = new Request();
        r.method = rl[0];
        r.target = rl[1];
        r.version = rl[2];
        for (int i = 1; i < lines.size(); i++) {
            String h = lines.get(i);
            int c = h.indexOf(':');
            if (c <= 0) throw new IOException("bad header");
            r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
        }
        return r;
    }

    static void write(OutputStream out, Response r) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        if (r.status != 101) {
            h.append("Content-Type: application/json\r\n");
            h.append("Content-Length: ").append(r.body.length).append("\r\n");
        }
        h.append("Cache-Control: no-store\r\n");
        h.append("X-OTV-Time: ").append(System.currentTimeMillis() / 1000).append("\r\n");
        for (Map.Entry<String, String> e : r.headers.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        if (r.close) h.append("Connection: close\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (r.status != 101) out.write(r.body);
        out.flush();
    }

    static String reason(int s) {
        switch (s) {
            case 101: return "Switching Protocols";
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    static JSONObject err(String code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", code);
            if (message != null) o.put("message", message);
        } catch (JSONException ignored) {
        }
        return o;
    }

    static JSONObject ok() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static byte[] readBody(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("eof in body");
            off += r;
        }
        return b;
    }

    private Response tooLarge() {
        Response r = new Response(413, err("too_large", "request too large"));
        r.close = true;
        return r;
    }

    private Response handle(Request req, InputStream in, OutputStream out, Socket sock) throws IOException {
        String path = req.target;
        boolean wallpaperPut = "PUT".equals(req.method) && "/v1/wallpaper".equals(path);
        if (!req.header("Transfer-Encoding").isEmpty()) {
            Response r = new Response(411, err("length_required", "send Content-Length"));
            r.close = true;
            return r;
        }
        long len;
        try {
            len = req.header("Content-Length").isEmpty() ? 0 : Long.parseLong(req.header("Content-Length"));
        } catch (NumberFormatException e) {
            Response r = new Response(400, err("bad_request", "Content-Length"));
            r.close = true;
            return r;
        }
        if (themeUpload(req)) {
            if (len < 0 || len > CustomThemes.CAP) return tooLarge();
            Response pre = preAuthenticate(req);
            if (pre != null) {
                pre.close = true;
                return pre;
            }
            File tdir = CustomThemes.root(host.wallpaperDir());
            Response no = receiveUpload(req, in, len, tdir, false);
            if (no != null) return no;
            try {
                return handleBody(req, in, out, sock, false);
            } finally {
                if (req.upload != null) req.upload.delete();
            }
        }
        File castDir = castUpload(req) ? host.castDir() : null;
        if (castDir != null) {
            if (len < 0 || len > castUploadMax) return tooLarge();
            Response pre = preAuthenticate(req);
            if (pre != null) {
                pre.close = true;   // body not read: drop the connection
                return pre;
            }
            Response no = receiveUpload(req, in, len, castDir, true);
            if (no != null) return no;
        } else {
            if (len < 0 || len > (wallpaperPut ? JPEG_MAX : bannerPut(req) ? BANNER_MAX : JSON_MAX)) return tooLarge();
            req.body = readBody(in, (int) len);
        }
        try {
            return handleBody(req, in, out, sock, wallpaperPut);
        } finally {
            if (req.upload != null) req.upload.delete();   // kept files were renamed away
        }
    }

    static final java.util.regex.Pattern THEME_PATH = java.util.regex.Pattern.compile("^/v1/themes/([a-z0-9][a-z0-9-]{0,39})$");
    static final java.util.regex.Pattern THEME_SELECT = java.util.regex.Pattern.compile("^/v1/themes/([a-z0-9][a-z0-9-]{0,39})/select$");

    /** v-next: custom app banners, PUT/DELETE /v1/banners/<package> (PNG), GET /v1/banners. */
    static final java.util.regex.Pattern BANNER_PATH = java.util.regex.Pattern.compile("^/v1/banners/([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)$");
    static final int BANNER_MAX = 1024 * 1024, BANNER_FILES = 200;

    private static boolean bannerPut(Request req) {
        return "PUT".equals(req.method) && req.target != null && BANNER_PATH.matcher(req.target).matches();
    }

    /** banners/ next to the link/ wallpaper dir: the app's external files dir, read by AppAdapter. */
    private File bannerDir() {
        File w = host.wallpaperDir();
        return new File(w.getParentFile() != null ? w.getParentFile() : w, "banners");
    }

    private Response banners(String m, String pkg, Request req) throws Exception {
        File dir = bannerDir();
        if (pkg == null) {
            JSONArray a = new JSONArray();
            String[] names = dir.list();
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names) if (n.endsWith(".png") && !n.startsWith(".")) a.put(n.substring(0, n.length() - 4));
            }
            return new Response(200, new JSONObject().put("banners", a));
        }
        File f = new File(dir, pkg + ".png");
        if ("DELETE".equals(m)) {
            if (!f.isFile()) return new Response(404, err("not_found", "no such banner"));
            f.delete();
            host.onEvent(null, "banners", new JSONObject().put("package", pkg));
            return new Response(200, ok());
        }
        byte[] b = req.body;
        if (b == null || b.length < 8 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') {
            return new Response(415, err("not_png", "Content-Type image/png, PNG data"));
        }
        String[] have = dir.list();
        if (!f.isFile() && have != null && have.length >= BANNER_FILES) return new Response(413, err("too_many", BANNER_FILES + " banners"));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no banner dir");
        File tmp = new File(dir, ".banner.tmp");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
            o.write(b);
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("rename");
        }
        host.onEvent(null, "banners", new JSONObject().put("package", pkg));
        return new Response(200, ok().put("package", pkg).put("bytes", b.length));
    }

    private static boolean themeUpload(Request req) {
        return "PUT".equals(req.method) && THEME_PATH.matcher(req.target).matches();
    }

    private static boolean castUpload(Request req) {
        return "PUT".equals(req.method) && "/v1/cast/file".equals(req.target);
    }

    /**
     * Before a large upload is read: the Authorization header must name a paired PC with a fresh timestamp,
     * so a stranger on the LAN cannot make the TV write anything. The signature (over the body hash) is
     * checked once the body has been streamed to the private cache.
     */
    private Response preAuthenticate(Request req) {
        Map<String, String> f = parseAuthorization(req.header("Authorization"));
        if (f == null || store.get(f.get("pc")) == null) return new Response(401, err("unauthorized", "missing or bad Authorization"));
        String ts = f.get("ts");
        if (!ts.matches("-?[0-9]{1,15}") || Math.abs(Long.parseLong(ts) - now()) > SKEW_S) {
            return new Response(401, err("clock_skew", "timestamp outside ±120 s"));
        }
        return null;
    }

    /** Streams {@code len} bytes to castDir/.up-*.tmp while hashing; enforces the free-space floor first. */
    private Response receiveUpload(Request req, InputStream in, long len, File dir, boolean cast) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no upload dir");
        if (cast) Cast.sweep(dir, System.currentTimeMillis(), castKeep(), CAST_FILES - 1, Math.max(0, CAST_BYTES - len));
        if (dir.getUsableSpace() < len + CAST_FREE_MIN) {
            Response r = new Response(507, err("no_space", "not enough free space on the TV"));
            r.close = true;
            return r;
        }
        File tmp = new File(dir, ".up-" + LinkCrypto.hex(LinkCrypto.randomBytes(8)) + ".tmp");
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        req.upload = tmp;
        byte[] buf = new byte[64 * 1024];
        long left = len;
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) throw new IOException("eof in body");
                md.update(buf, 0, r);
                fo.write(buf, 0, r);
                left -= r;
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        req.bodySha = LinkCrypto.hex(md.digest());
        req.bodyLen = len;
        return null;
    }

    private String castKeep() {
        synchronized (castLock) {
            return castActive ? castFileSha : null;
        }
    }

    private Response handleBody(Request req, InputStream in, OutputStream out, Socket sock, boolean wallpaperPut) throws IOException {
        String path = req.target;

        if ("GET".equals(req.method) && "/v1/info".equals(path)) {
            JSONObject o = new JSONObject();
            try {
                o.put("service", "omarchytv-link").put("protocol", 1).put("tvId", store.tvId())
                        .put("name", host.tvName()).put("pairingOpen", host.pairingOpen());
            } catch (JSONException ignored) {
            }
            return new Response(200, o);
        }
        if ("POST".equals(req.method) && ("/v1/pair/start".equals(path) || "/v1/pair/confirm".equals(path))) {
            long retry = limited(req.peer + "|" + path, 1, 2000);
            if (retry > 0) return retryAfter(new Response(429, err("rate_limited", "1 pairing request per 2 s")), retry);
            return pair(path, req);
        }

        // ---- authenticated from here
        Auth a = authenticate(req);
        if (a.error != null) return a.error;
        // Optional AES-256-GCM payloads. The HMAC signature covers the ciphertext, so auth is unchanged;
        // clients that don't send the header keep the 2.12 plaintext behaviour.
        final boolean enc = LinkCrypto.ENC_ALG.equals(req.header(LinkCrypto.ENC_HEADER)) && req.upload == null;
        if (enc && req.body.length > 0) {
            try {
                req.body = LinkCrypto.open(a.token, "OTV1-E\n" + a.nonce, req.body);
            } catch (java.security.GeneralSecurityException e) {
                return sign(a, new Response(400, err("bad_enc", "payload decryption failed")));
            }
        }
        java.util.function.Consumer<String> rl = requestLog;
        if (rl != null) rl.accept(req.method + " " + path + " " + req.body.length);
        long retry = limited(req.peer + "|auth", 20, 1000);
        if (retry == 0) {
            String bucket = wallpaperPut ? "wallpaper" : "PUT".equals(req.method) && ("/v1/widgets".equals(path)
                    || "/v1/ai".equals(path) || "/v1/ai/providers".equals(path)) ? path : null;
            if (bucket != null) retry = limited(req.peer + "|" + bucket, 1, wallpaperPut ? 10_000 : 5_000);
            else if ("POST".equals(req.method) && "/v1/cast".equals(path)) retry = limited(req.peer + "|cast", 5, 5_000);
            else if (themeUpload(req)) retry = limited(req.peer + "|themes", 1, 5_000);
            else if (castUpload(req)) retry = limited(req.peer + "|castfile", 2, 5_000);
            else if ("PUT".equals(req.method) && "/v1/agents".equals(path)) retry = limited(req.peer + "|agents", 2, 5_000);
            else if ("PUT".equals(req.method) && "/v1/stats".equals(path)) retry = limited(req.peer + "|stats", 1, 1_500);
            else if ("PUT".equals(req.method) && "/v1/screensaver".equals(path)) retry = limited(req.peer + "|saver", 1, 5_000);
        }
        if (retry > 0) return sign(a, retryAfter(new Response(429, err("rate_limited", "slow down")), retry));

        if ("GET".equals(req.method) && "/v1/events".equals(path)) {
            if (!"websocket".equalsIgnoreCase(req.header("Upgrade")) || req.header("Sec-WebSocket-Key").isEmpty()) {
                return sign(a, new Response(400, err("bad_request", "WebSocket upgrade expected")));
            }
            if (clients.size() >= MAX_WS) return sign(a, new Response(503, err("busy", "too many event sockets")));
            websocket(req, a, sock, in, out);
            return null;
        }

        JSONObject body;
        if (wallpaperPut || bannerPut(req) || req.upload != null || req.body.length == 0) {
            body = new JSONObject();
        } else {
            try {
                body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                return sign(a, new Response(400, err("bad_json", "body is not a JSON object")));
            }
        }
        Response r;
        try {
            r = route(req, a, body);
        } catch (Exception e) {
            host.log("handler " + path + ": " + e);
            r = new Response(400, err("bad_request", String.valueOf(e.getMessage())));
        }
        if (enc && r.body != null && r.body.length > 0) {
            try {
                r.body = LinkCrypto.seal(a.token, "OTV1-E-R\n" + a.nonce, r.body);
                r.headers.put(LinkCrypto.ENC_HEADER, LinkCrypto.ENC_ALG);
            } catch (java.security.GeneralSecurityException e) {
                host.log("encrypt response: " + e);
            }
        }
        return sign(a, r);
    }

    private static Response retryAfter(Response r, long seconds) {
        r.headers.put("Retry-After", String.valueOf(Math.max(1, seconds)));
        return r;
    }

    /** Fixed-window limiter: at most {@code max} per {@code windowMs}; returns 0 or the seconds to wait. */
    private long limited(String key, int max, long windowMs) {
        if (!limits) return 0;
        long t = System.currentTimeMillis();
        synchronized (rates) {
            if (rates.size() > 2048) rates.entrySet().removeIf(e -> t - e.getValue()[0] > 60_000);
            long[] w = rates.get(key);
            if (w == null || t - w[0] >= windowMs) {
                rates.put(key, new long[]{t, 1});
                return 0;
            }
            if (w[1] < max) {
                w[1]++;
                return 0;
            }
            return (windowMs - (t - w[0]) + 999) / 1000;
        }
    }

    // ---- pairing (§4) ------------------------------------------------------------------------------

    private Response pair(String path, Request req) {
        if (!host.pairingOpen()) return new Response(403, err("pairing_closed", "open “Connect Omarchy Linux” on the TV"));
        JSONObject j;
        try {
            j = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return new Response(400, err("bad_json", null));
        }
        synchronized (pairLock) {
            long t = System.currentTimeMillis();
            if ("/v1/pair/start".equals(path)) {
                if (j.has("protocol") && j.optInt("protocol", -1) != 1) return new Response(400, err("bad_request", "protocol 1 only"));
                String pcId = j.optString("pcId", "");
                if (!PC_ID.matcher(pcId).matches()) return new Response(400, err("bad_request", "pcId"));
                if (store.full(pcId)) return new Response(409, err("too_many_pcs", "8 PCs are paired; forget one on the TV"));
                Pending p = new Pending();
                try {
                    p.b = new LinkCrypto.TvPairing(host.pairingCode(), store.tvId(), pcId, LinkCrypto.unb64u(j.optString("t", "")), null);
                } catch (IllegalArgumentException e) {
                    return new Response(400, err("bad_request", e.getMessage()));
                }
                p.id = LinkCrypto.hex(LinkCrypto.randomBytes(8));
                p.pcId = pcId;
                p.pcName = printable(j.optString("pcName", "omarchy"), 40);
                if (p.pcName.isEmpty()) p.pcName = "omarchy";
                p.expires = t + PAIR_TTL_MS;
                pending = p;   // one at a time; a new start replaces it
                JSONObject o = new JSONObject();
                try {
                    o.put("pairingId", p.id).put("tvId", store.tvId()).put("tvName", host.tvName())
                            .put("s", LinkCrypto.b64u(LinkCrypto.elementBytes(p.b.s))).put("expiresIn", 60);
                } catch (JSONException ignored) {
                }
                return new Response(200, o);
            }
            Pending p = pending;
            if (p == null || !p.id.equals(j.optString("pairingId", "")) || p.expires < t) {
                return new Response(400, err("no_pairing", "start again"));
            }
            pending = null;
            boolean good;
            try {
                good = LinkCrypto.equal(LinkCrypto.unb64u(j.optString("confirm", "")), p.b.keys.confirmPc);
            } catch (IllegalArgumentException e) {
                good = false;
            }
            if (!good) {
                failures++;
                int left = Math.max(0, MAX_FAILURES - failures);
                if (left == 0) {
                    failures = 0;
                    host.newPairingCode();
                }
                JSONObject o = err("bad_code", "wrong code");
                try {
                    o.put("attemptsLeft", left);
                } catch (JSONException ignored) {
                }
                return new Response(401, o);
            }
            failures = 0;
            store.put(p.pcId, p.pcName, p.b.keys.token, req.peer);
            host.newPairingCode();   // a code pairs one PC
            host.onPcsChanged(p.pcName);
            JSONObject o = new JSONObject();
            try {
                o.put("confirm", LinkCrypto.b64u(p.b.keys.confirmTv)).put("tvId", store.tvId());
            } catch (JSONException ignored) {
            }
            return new Response(200, o);
        }
    }

    /** The code on screen changed (page reopened): forget the attempt count and any pending start. */
    public void resetPairing() {
        synchronized (pairLock) {
            failures = 0;
            pending = null;
        }
    }

    // ---- authentication (§5) -----------------------------------------------------------------------

    static final class Auth {
        String pcId, nonce;
        byte[] token;
        Response error;
    }

    static Map<String, String> parseAuthorization(String value) {
        String v = value == null ? "" : value.trim();
        if (!v.startsWith("OTV1 ")) return null;
        Map<String, String> f = new HashMap<>();
        for (String part : v.substring(5).split(",")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) return null;
            f.put(p.substring(0, eq), p.substring(eq + 1));
        }
        for (String k : new String[]{"pc", "ts", "nonce", "sig"}) if (f.get(k) == null || f.get(k).isEmpty()) return null;
        return f;
    }

    private Auth authenticate(Request req) {
        Auth a = new Auth();
        String rawAuth = req.header("Authorization").trim();
        if (("/mcp".equals(req.target) || "/v1/mcp".equals(req.target)) && rawAuth.startsWith("Bearer ")) {
            String tokStr = rawAuth.substring(7).trim();
            byte[] cand = decodeBearerToken(tokStr);
            if (cand != null) {
                for (LinkStore.Pc pc : store.list()) {
                    if (LinkCrypto.equal(cand, pc.token)) {
                        a.pcId = pc.id;
                        a.token = pc.token;
                        a.nonce = "mcp";
                        if (store.seen(pc.id, req.peer)) host.onPcsChanged(null);
                        return a;
                    }
                }
            }
            a.error = new Response(401, err("unauthorized", "invalid MCP Bearer token"));
            return a;
        }
        Map<String, String> f = parseAuthorization(rawAuth);
        if (f == null) {
            a.error = new Response(401, err("unauthorized", "missing or bad Authorization"));
            return a;
        }
        LinkStore.Pc pc = store.get(f.get("pc"));
        if (pc == null) {
            a.error = new Response(401, err("unauthorized", "unknown PC"));
            return a;
        }
        String ts = f.get("ts");
        if (!ts.matches("-?[0-9]{1,15}") || Math.abs(Long.parseLong(ts) - now()) > SKEW_S) {
            a.error = new Response(401, err("clock_skew", "timestamp outside ±120 s"));
            return a;
        }
        byte[] expected = LinkCrypto.hmac(pc.token, req.bodySha != null
                ? LinkCrypto.requestStringHex(req.method, req.target, ts, f.get("nonce"), req.bodySha)
                : LinkCrypto.requestString(req.method, req.target, ts, f.get("nonce"), req.body));
        boolean good;
        try {
            good = LinkCrypto.equal(LinkCrypto.unb64u(f.get("sig")), expected);
        } catch (IllegalArgumentException e) {
            good = false;
        }
        if (!good) {
            a.error = new Response(401, err("unauthorized", "bad signature"));
            return a;
        }
        String key = pc.id + " " + f.get("nonce");
        long t = System.currentTimeMillis();
        synchronized (nonces) {
            for (Iterator<Map.Entry<String, Long>> it = nonces.entrySet().iterator(); it.hasNext(); ) {
                if (t - it.next().getValue() > NONCE_TTL_MS) it.remove();
            }
            if (nonces.containsKey(key)) {
                a.error = new Response(401, err("replay", "nonce already used"));
                return a;
            }
            if (nonces.size() < 20_000) nonces.put(key, t);
        }
        a.pcId = pc.id;
        a.token = pc.token;
        a.nonce = f.get("nonce");
        if (store.seen(pc.id, req.peer)) host.onPcsChanged(null);
        return a;
    }

    private static Response sign(Auth a, Response r) {
        r.headers.put("X-OTV-Sig", LinkCrypto.signResponse(a.token, a.nonce, r.status, r.body));
        return r;
    }

    // ---- endpoints (§6) ----------------------------------------------------------------------------

    private Response route(Request req, Auth a, JSONObject body) throws Exception {
        String m = req.method, p = req.target;
        if ("GET".equals(m) && "/v1/status".equals(p)) return new Response(200, status());
        if ("POST".equals(m) && "/v1/cast".equals(p)) return cast(body);
        if ("PUT".equals(m) && "/v1/cast/file".equals(p)) return castFile(req);
        if ("POST".equals(m) && "/v1/cast/control".equals(p)) return castControl(body);
        if ("GET".equals(m) && "/v1/apps".equals(p)) return new Response(200, new JSONObject().put("apps", host.apps()));
        if ("POST".equals(m) && "/v1/apps/launch".equals(p)) {
            String pkg = body.optString("package", "");
            return pkg.matches("[A-Za-z0-9_.]{1,200}") && host.launch(pkg) ? new Response(200, ok())
                    : new Response(404, err("no_such_app", "not installed or not a TV app"));
        }
        if ("POST".equals(m) && "/v1/remote/key".equals(p)) {
            String key = body.optString("key", "");
            if (!KEYS.contains(key)) return new Response(400, err("bad_key", "unknown key"));
            int s = host.key(key);
            return s == 200 ? new Response(200, ok()) : new Response(409, err("capability_missing", "the TV cannot send " + key + " right now"));
        }
        if ("PUT".equals(m) && "/v1/theme".equals(p)) return theme(body);
        if ("GET".equals(m) && "/v1/banners".equals(p)) return banners(m, null, req);
        java.util.regex.Matcher bm = BANNER_PATH.matcher(p);
        if (bm.matches() && ("PUT".equals(m) || "DELETE".equals(m))) return banners(m, bm.group(1), req);
        if ("GET".equals(m) && "/v1/themes".equals(p)) return new Response(200, CustomThemes.list(host.wallpaperDir()));
        java.util.regex.Matcher tm = THEME_PATH.matcher(p);
        if (tm.matches() && "PUT".equals(m)) {
            if (req.upload == null) return new Response(400, err("bad_request", "ZIP body expected"));
            try {
                JSONObject r = CustomThemes.install(host.wallpaperDir(), tm.group(1), req.upload);
                host.onEvent(a.pcId, "themes", r);
                return new Response(200, r);
            } catch (CustomThemes.Bad b) {
                return new Response(b.status, err(b.code, b.getMessage()));
            }
        }
        if (tm.matches() && "DELETE".equals(m)) {
            return CustomThemes.delete(host.wallpaperDir(), tm.group(1)) ? new Response(200, ok()) : new Response(404, err("not_found", "no such theme"));
        }
        java.util.regex.Matcher sm = THEME_SELECT.matcher(p);
        if (sm.matches() && "POST".equals(m)) {
            String n = sm.group(1);
            if (java.util.Arrays.asList(CustomThemes.names(host.wallpaperDir())).indexOf(n) < 0) return new Response(404, err("not_found", "no such theme"));
            int bg = body.optInt("bg", 0);
            host.editConfig(j -> CustomThemes.select(host.wallpaperDir(), n, bg, j));
            return new Response(200, ok());
        }
        if ("GET".equals(m) && "/v1/wallpaper".equals(p)) {
            String sha = currentWallpaper();
            return new Response(200, new JSONObject().put("sha256", sha == null ? JSONObject.NULL : sha));
        }
        if ("PUT".equals(m) && "/v1/wallpaper".equals(p)) return wallpaper(req);
        if ("PUT".equals(m) && "/v1/widgets".equals(p)) return widgets(body);
        if ("PUT".equals(m) && "/v1/stats".equals(p)) return stats(body, a.pcId);
        if ("PUT".equals(m) && "/v1/screensaver".equals(p)) return screensaver(body);
        if ("PUT".equals(m) && "/v1/ai".equals(p)) return ai(body, a.pcId);
        if ("PUT".equals(m) && "/v1/ai/providers".equals(p)) return providers(body, a.pcId);
        if ("PUT".equals(m) && "/v1/agents".equals(p)) return agentsPut(body, a.pcId);
        if ("GET".equals(m) && "/v1/agents".equals(p)) return new Response(200, agents());
        if ("GET".equals(m) && "/v1/cinemarchy/library".equals(p)) return new Response(200, cinemarchyLibrary());
        if ("PUT".equals(m) && "/v1/cinemarchy/library".equals(p)) return cinemarchyLibraryPut(body, a.pcId);
        if ("GET".equals(m) && "/v1/cinemarchy/snippets".equals(p)) return new Response(200, cinemarchySnippets());
        if ("PUT".equals(m) && "/v1/cinemarchy/snippets".equals(p)) return cinemarchySnippetsPut(body, a.pcId);
        if ("POST".equals(m) && "/v1/cinemarchy/handoff".equals(p)) return cinemarchyHandoff(body, a.pcId);
        if ("POST".equals(m) && "/v1/cinemarchy/handoff-to-pc".equals(p)) return cinemarchyHandoffToPc(body);
        if ("GET".equals(m) && "/v1/cinemarchy/iptv".equals(p)) return new Response(200, cinemarchyIptv());
        if ("PUT".equals(m) && "/v1/cinemarchy/iptv".equals(p)) return cinemarchyIptvPut(body, a.pcId);
        if ("POST".equals(m) && "/v1/cinemarchy/queue".equals(p)) return cinemarchyQueue(body, a.pcId);
        if ("GET".equals(m) && "/v1/plugins".equals(p)) return new Response(200, pluginsGet());
        if ("PUT".equals(m) && "/v1/plugins".equals(p)) return pluginsPut(body, a.pcId);
        if ("GET".equals(m) && "/v1/plugins/grokbot".equals(p)) return new Response(200, pluginsGrokbotGet());
        if (("POST".equals(m) || "PUT".equals(m)) && "/v1/plugins/grokbot".equals(p)) return pluginsGrokbotPost(body, a.pcId);
        if ("POST".equals(m) && ("/mcp".equals(p) || "/v1/mcp".equals(p))) {
            return mcp(body);
        }
        if ("POST".equals(m) && "/v1/unpair".equals(p)) {
            closeClients(a.pcId, false);
            store.remove(a.pcId);
            host.onPcsChanged(null);
            return new Response(200, ok());
        }
        return new Response(404, err("not_found", null));
    }

    private static byte[] decodeBearerToken(String s) {
        if (s == null || s.isEmpty()) return null;
        if (s.matches("^[0-9a-fA-F]{64}$")) {
            byte[] out = new byte[32];
            for (int i = 0; i < 32; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            return out;
        }
        try {
            return LinkCrypto.unb64u(s);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            return com.omarchytv.home.ApiCompat.unb64(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** The launcher's own versionName, as reported by the Host status; "0" only if the host has none. */
    private String launcherVersion() {
        try {
            String v = host.status().optString("launcherVersion", "");
            return v.matches("\\d{1,3}(\\.\\d{1,3}){1,2}(-[A-Za-z0-9.]+)?") ? v : "0";
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * Native Model Context Protocol (MCP) JSON-RPC 2.0 endpoint (/mcp and /v1/mcp) for Grok Bot, Claude,
     * Hermes, and desktop MCP clients. Authenticated via OTV1 HMAC or Authorization: Bearer <paired-token>.
     */
    private Response mcp(JSONObject req) throws Exception {
        Object id = req.has("id") ? req.get("id") : JSONObject.NULL;
        String method = req.optString("method", "");
        JSONObject params = req.optJSONObject("params");
        if (params == null) params = new JSONObject();
        JSONObject res = new JSONObject().put("jsonrpc", "2.0").put("id", id);
        switch (method) {
            case "initialize": {
                JSONObject r = new JSONObject()
                        .put("protocolVersion", "2024-11-05")
                        .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                        .put("serverInfo", new JSONObject().put("name", "omarchytv-mcp").put("version", launcherVersion()));
                return new Response(200, res.put("result", r));
            }
            case "notifications/initialized":
            case "ping":
                return new Response(200, res.put("result", new JSONObject()));
            case "tools/list": {
                JSONArray tools = new JSONArray()
                        .put(mcpTool("tv_status", "Read OmarchyTV status, active theme, capabilities, and paired PCs.", new JSONObject()))
                        .put(mcpTool("tv_list_themes", "List bundled MIT Omarchy themes and custom themes installed on the TV.", new JSONObject()))
                        .put(mcpTool("tv_set_theme", "Switch the active Omarchy theme on the TV.",
                                new JSONObject().put("name", new JSONObject().put("type", "string").put("description", "Theme slug (e.g. tokyo-night, jade-horse, hermarchy)"))))
                        .put(mcpTool("tv_send_key", "Send a remote key to the TV (home, back, up, down, left, right, select, play_pause, volume_up, volume_down, mute).",
                                new JSONObject().put("key", new JSONObject().put("type", "string").put("description", "Key name"))))
                        .put(mcpTool("tv_list_apps", "List installed Android TV apps and WebApps.", new JSONObject()))
                        .put(mcpTool("tv_launch_app", "Launch an Android TV app by package name.",
                                new JSONObject().put("package", new JSONObject().put("type", "string").put("description", "Android package name"))))
                        .put(mcpTool("tv_screensaver", "Set or reset custom ASCII screensaver text on the TV.",
                                new JSONObject().put("text", new JSONObject().put("type", "string").put("description", "ASCII text (empty string resets to default)"))))
                        .put(mcpTool("tv_ai_status", "Read live AI agents and PC stats displayed on OmarchyTV.", new JSONObject()))
                        .put(mcpTool("tv_cinemarchy", "Read Cinemarchy media library and video snippets on OmarchyTV.", new JSONObject()));
                return new Response(200, res.put("result", new JSONObject().put("tools", tools)));
            }
            case "tools/call": {
                String name = params.optString("name", "");
                final JSONObject args = params.optJSONObject("arguments") != null ? params.optJSONObject("arguments") : new JSONObject();
                JSONObject out;
                boolean isError = false;
                switch (name) {
                    case "tv_status":
                        out = status();
                        break;
                    case "tv_list_themes":
                        out = CustomThemes.list(host.wallpaperDir());
                        break;
                    case "tv_set_theme": {
                        String tName = printable(args.optString("name", ""), 40);
                        if (tName.isEmpty()) {
                            out = err("bad_theme", "name is required");
                            isError = true;
                        } else {
                            host.editConfig(j -> {
                                JSONObject t = new JSONObject().put("name", tName).put("source", "tv");
                                if (HEX_COLOR.matcher(args.optString("accent", "")).matches()) t.put("accent", args.optString("accent").toLowerCase(Locale.ROOT));
                                if (HEX_COLOR.matcher(args.optString("accent2", "")).matches()) t.put("accent2", args.optString("accent2").toLowerCase(Locale.ROOT));
                                j.put("theme", t);
                                j.put("wallpaper_bg", 0);
                            });
                            out = ok().put("theme", tName);
                        }
                        break;
                    }
                    case "tv_send_key": {
                        String key = args.optString("key", "");
                        if (!KEYS.contains(key)) {
                            out = err("bad_key", "unknown key: " + key);
                            isError = true;
                        } else {
                            int code = host.key(key);
                            out = code == 200 ? ok().put("key", key) : err("capability_missing", "cannot send " + key);
                            isError = code != 200;
                        }
                        break;
                    }
                    case "tv_list_apps":
                        out = new JSONObject().put("apps", host.apps());
                        break;
                    case "tv_launch_app": {
                        String pkg = args.optString("package", "");
                        boolean launched = pkg.matches("[A-Za-z0-9_.]{1,200}") && host.launch(pkg);
                        out = launched ? ok().put("package", pkg) : err("no_such_app", "not installed");
                        isError = !launched;
                        break;
                    }
                    case "tv_screensaver": {
                        Response sr = screensaver(new JSONObject().put("text", args.optString("text", "")));
                        out = new JSONObject(new String(sr.body, StandardCharsets.UTF_8));
                        isError = sr.status != 200;
                        break;
                    }
                    case "tv_ai_status":
                        out = new JSONObject().put("agents", agents()).put("stats", stats() != null ? stats() : JSONObject.NULL);
                        break;
                    case "tv_cinemarchy":
                        out = new JSONObject().put("library", cinemarchyLibrary()).put("snippets", cinemarchySnippets());
                        break;
                    default:
                        return new Response(200, res.put("error", new JSONObject().put("code", -32601).put("message", "Unknown tool: " + name)));
                }
                JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", out.toString()));
                return new Response(200, res.put("result", new JSONObject().put("content", content).put("isError", isError)));
            }
            default:
                return new Response(200, res.put("error", new JSONObject().put("code", -32601).put("message", "Method not found: " + method)));
        }
    }

    private static JSONObject mcpTool(String name, String description, JSONObject props) throws JSONException {
        return new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", props));
    }

    // ---- cast (§6 Cast) ------------------------------------------------------------------------------

    /** Host status plus the cast capabilities, castKinds and castUploadMax. Use this, not host.status(). */
    public JSONObject status() {
        JSONObject st = host.status();
        try {
            JSONArray caps0 = st.optJSONArray("capabilities");
            if (caps0 == null) st.put("capabilities", caps0 = new JSONArray());
            caps0.put("agents");
            caps0.put("themes");
            caps0.put("banners");
            caps0.put("cinemarchy");
            caps0.put("plugins");
            caps0.put("enc-a256gcm");
            caps0.put("mcp");   // v-next: PUT/DELETE /v1/banners/<package> (PNG), GET /v1/banners   // v2.10.1: PUT/DELETE /v1/themes/<name>, GET /v1/themes, POST /v1/themes/<name>/select   // §6 Agents: PUT/GET /v1/agents, agent_activity, agent_ask
            List<String> kinds = host.castKinds();
            if (kinds != null && !kinds.isEmpty()) {
                JSONArray caps = st.optJSONArray("capabilities");
                if (caps == null) st.put("capabilities", caps = new JSONArray());
                caps.put("cast");
                boolean up = host.castDir() != null;
                if (up) caps.put("cast-upload");
                st.put("castKinds", new JSONArray(kinds));
                if (up) st.put("castUploadMax", castUploadMax);
            }
        } catch (JSONException ignored) {
        }
        return st;
    }

    private Response cast(JSONObject body) throws Exception {
        List<String> kinds = host.castKinds();
        if (kinds == null || kinds.isEmpty()) return new Response(409, err("capability_missing", "cast is off"));
        Cast c;
        try {
            c = Cast.parse(body, kinds, host.castDir());
        } catch (Cast.Bad b) {
            return new Response(b.status, err(b.code, b.getMessage()));
        }
        String old;
        synchronized (castLock) {
            old = castActive && !c.id.equals(castId) ? castId : null;
            castId = c.id;
            castFileSha = c.file != null ? c.file.getName() : null;
            castActive = true;
        }
        if (old != null) broadcast("cast_state", new JSONObject().put("id", old).put("state", "stopped"));
        if (c.file != null) c.file.setLastModified(System.currentTimeMillis());
        String e = host.openCast(c);
        if (e != null) {
            synchronized (castLock) {
                if (c.id.equals(castId)) castActive = false;
            }
            return new Response(409, err(e, "nothing on the TV can open this"));
        }
        host.log(c.toString());
        rememberFromPc(c);
        broadcast("cast_state", new JSONObject().put("id", c.id).put("state", "opening"));
        return new Response(200, new JSONObject().put("id", c.id).put("state", "opening").put("app", c.app));
    }

    private Response castFile(Request req) throws Exception {
        File dir = host.castDir();
        if (dir == null || req.upload == null) return new Response(409, err("capability_missing", "no uploads"));
        if (req.bodyLen == 0) return new Response(400, err("bad_cast", "empty file"));
        // v2.10: allowlist by content (first bytes), never by name or Content-Type.
        if (Cast.sniff(req.upload) == null) return new Response(415, err("unsupported_type", "allowed: jpeg png gif webp mp4 mkv webm ts mp3 ogg flac wav text"));
        File target = new File(dir, req.bodySha);
        if (target.isFile() && target.length() == req.bodyLen) {
            target.setLastModified(System.currentTimeMillis());
        } else if (!req.upload.renameTo(target)) {
            throw new IOException("rename");
        }
        Cast.sweep(dir, System.currentTimeMillis(), req.bodySha, CAST_FILES, CAST_BYTES);
        return new Response(200, new JSONObject().put("file", req.bodySha).put("size", req.bodyLen));
    }

    private Response castControl(JSONObject body) throws Exception {
        if (host.castKinds() == null || host.castKinds().isEmpty()) return new Response(409, err("capability_missing", "cast is off"));
        String[] a;
        try {
            a = Cast.control(body);
        } catch (Cast.Bad b) {
            return new Response(b.status, err(b.code, b.getMessage()));
        }
        String id;
        synchronized (castLock) {
            id = castId;
            if (id == null || !castActive || (!a[1].isEmpty() && !a[1].equals(id))) {
                return new Response(409, err("nothing_playing", null));
            }
        }
        String e = host.castControl(id, a[0], a[2].isEmpty() ? 0 : Double.parseDouble(a[2]));
        if (e != null) return new Response(409, err(e, null));
        if ("stop".equals(a[0])) castState(id, "stopped", -1, -1, null);
        return new Response(200, ok());
    }

    /**
     * The player / viewer reports (any thread; it does socket I/O, so not the UI thread). Only the current
     * cast is reported: a replaced one already got "stopped". stopped/error end it and drop its upload.
     */
    public void castState(String id, String state, double position, double duration, String error) {
        File drop = null;
        synchronized (castLock) {
            if (id == null || !id.equals(castId) || !castActive) return;
            if ("stopped".equals(state) || "error".equals(state)) {
                castActive = false;
                File dir = host.castDir();
                if (castFileSha != null && dir != null) drop = new File(dir, castFileSha);
                castFileSha = null;
            }
        }
        if (drop != null) drop.delete();
        try {
            JSONObject d = new JSONObject().put("id", id).put("state", state);
            if (position >= 0) d.put("position", Math.round(position * 10) / 10.0);
            if (duration > 0) d.put("duration", Math.round(duration * 10) / 10.0);
            if (error != null) d.put("error", printable(error, 120));
            broadcast("cast_state", d);
        } catch (JSONException ignored) {
        }
    }

    /** sha256 of the upload the open cast shows (never swept), or null. */
    public String currentCastFile() {
        return castKeep();
    }

    /** The cast that is open now (null when none). */
    public String currentCast() {
        synchronized (castLock) {
            return castActive ? castId : null;
        }
    }

    private File wallpaperFile(String sha) {
        return new File(host.wallpaperDir(), "wallpaper-" + sha.substring(0, 12) + ".jpg");
    }

    private String currentWallpaper() {
        String sha = store.wallpaperSha();
        return sha != null && wallpaperFile(sha).isFile() ? sha : null;
    }

    private Response theme(JSONObject t) throws Exception {
        String accent = t.optString("accent", ""), accent2 = t.optString("accent2", "");
        if (!HEX_COLOR.matcher(accent).matches() || !HEX_COLOR.matcher(accent2).matches()) {
            return new Response(400, err("bad_theme", "accent/accent2 must be #rrggbb"));
        }
        JSONObject colors = new JSONObject();
        JSONObject in = t.optJSONObject("colors");
        if (in != null) {
            for (Iterator<String> it = in.keys(); it.hasNext() && colors.length() < 64; ) {
                String k = it.next();
                String v = in.optString(k, "");
                if (k.matches("[a-z0-9_]{1,32}") && HEX_COLOR.matcher(v).matches()) colors.put(k, v.toLowerCase(Locale.ROOT));
            }
        }
        JSONObject wpo = t.optJSONObject("wallpaper");
        String wp = wpo != null && !wpo.isNull("sha256") ? wpo.optString("sha256", "") : "";
        if (!wp.isEmpty() && !wp.equals(currentWallpaper())) {
            return new Response(409, err("unknown_wallpaper", "PUT /v1/wallpaper first"));
        }
        String mode = "light".equals(t.optString("mode")) ? "light" : "dark";
        String name = printable(t.optString("name", ""), 40);
        final String wpPath = wp.isEmpty() ? null : wallpaperFile(wp).getAbsolutePath();
        // Optional home prompt "<user>@<host>:~$" from the PC ($USER / hostname); only [a-z0-9._-], at most 16.
        JSONObject pin = t.optJSONObject("prompt");
        final String pUser = pin != null ? promptPart(pin.optString("user", "")) : "", pHost = pin != null ? promptPart(pin.optString("host", "")) : "";
        host.editConfig(j -> {
            j.put("theme", new JSONObject().put("name", name).put("mode", mode).put("accent", accent.toLowerCase(Locale.ROOT))
                    .put("accent2", accent2.toLowerCase(Locale.ROOT)).put("colors", colors).put("source", "omarchy-link"));
            if (wpPath != null) j.put("wallpaper", wpPath);
            if (!pUser.isEmpty() || !pHost.isEmpty()) {
                JSONObject pr = j.optJSONObject("prompt");
                if (pr == null) j.put("prompt", pr = new JSONObject());
                if (!pUser.isEmpty()) pr.put("user", pUser);
                if (!pHost.isEmpty()) pr.put("host", pHost);
            }
        });
        return new Response(200, ok());
    }

    private Response wallpaper(Request req) throws Exception {
        byte[] b = req.body;
        String ct = req.header("Content-Type").toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) ct = ct.substring(0, semi).trim();
        if (!"image/jpeg".equals(ct) || b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8 || (b[2] & 0xFF) != 0xFF) {
            return new Response(415, err("not_jpeg", "Content-Type image/jpeg, JPEG data"));
        }
        int[] size = host.jpegSize(b);
        if (size == null) return new Response(415, err("not_jpeg", "JPEG does not decode"));
        if (size[0] > 3840 || size[1] > 2160) return new Response(413, err("too_large", "at most 3840x2160"));
        String sha = LinkCrypto.hex(LinkCrypto.sha256(b));
        File dir = host.wallpaperDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no wallpaper dir");
        File target = wallpaperFile(sha);
        File tmp = new File(dir, ".wallpaper.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(b);
        }
        if (!tmp.renameTo(target)) throw new IOException("rename");
        File[] old = dir.listFiles((d, n) -> n.startsWith("wallpaper-") && n.endsWith(".jpg"));
        if (old != null) for (File f : old) if (!f.equals(target)) f.delete();   // keep only the newest
        store.setWallpaperSha(sha);
        return new Response(200, new JSONObject().put("sha256", sha));
    }

    /** Prompt user / host: lower case [a-z0-9._-], at most 16 characters (LauncherConfig.promptPart without the default). */
    static String promptPart(String in) {
        StringBuilder b = new StringBuilder();
        String l = in == null ? "" : in.toLowerCase(Locale.ROOT);
        for (int i = 0; i < l.length() && b.length() < 16; i++) {
            char ch = l.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-') b.append(ch);
        }
        return b.toString();
    }

    /** Printable text only (no control characters), trimmed, at most {@code max} code points. */
    /** A PC media folder listing, bounded: relative paths (never absolute, never ".."), names, kinds, sizes. */
    static JSONObject mediaList(JSONObject d) throws JSONException {
        JSONObject out = new JSONObject().put("path", safeRel(d.optString("path", "")));
        if (d.has("parent") && !d.isNull("parent")) out.put("parent", safeRel(d.optString("parent", "")));
        if (d.has("error")) out.put("error", printable(d.optString("error", ""), 24));
        JSONArray dirs = new JSONArray(), files = new JSONArray();
        JSONArray in = d.optJSONArray("dirs");
        for (int i = 0; in != null && i < in.length() && i < 2000; i++) {
            JSONObject o = in.optJSONObject(i);
            if (o == null) continue;
            String p = safeRel(o.optString("path", ""));
            if (!p.isEmpty()) dirs.put(new JSONObject().put("name", printable(o.optString("name", ""), 200)).put("path", p));
        }
        in = d.optJSONArray("files");
        for (int i = 0; in != null && i < in.length() && i < 2000; i++) {
            JSONObject o = in.optJSONObject(i);
            if (o == null) continue;
            String p = safeRel(o.optString("path", "")), k = o.optString("kind", "");
            if (p.isEmpty() || !(k.equals("video") || k.equals("audio") || k.equals("image"))) continue;
            files.put(new JSONObject().put("name", printable(o.optString("name", ""), 200)).put("path", p).put("kind", k)
                    .put("size", Math.max(0, o.optLong("size", 0))));
        }
        return out.put("dirs", dirs).put("files", files);
    }

    static String safeRel(String p) {
        if (p == null || p.length() > 1024 || p.startsWith("/") || p.contains("\\")) return "";
        for (String part : p.split("/")) if (part.equals("..") || part.equals(".")) return "";
        return printable(p, 1024);
    }

    static String printable(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (int i = 0; i < s.length() && n < max; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT && cp != 0x200D) continue;
            b.appendCodePoint(cp);
            n++;
        }
        return b.toString().trim();
    }

    private Response widgets(JSONObject w) throws Exception {
        JSONArray panesIn = w.optJSONArray("panes"), scIn = w.optJSONArray("shortcuts");
        if (panesIn == null) panesIn = new JSONArray();
        if (scIn == null) scIn = new JSONArray();
        if (panesIn.length() > 8 || scIn.length() > 16) return new Response(400, err("too_many", "8 panes, 16 shortcuts"));
        String[] why = new String[2];
        JSONArray panes = cleanPanes(panesIn, now(), why);
        if (panes == null) return new Response(400, err(why[0], why[1]));
        JSONArray shortcuts = new JSONArray();
        for (int i = 0; i < scIn.length(); i++) {
            JSONObject s = scIn.optJSONObject(i);
            if (s == null || !WIDGET_ID.matcher(s.optString("id", "")).matches()) return new Response(400, err("bad_widgets", "shortcut id"));
            JSONObject act = s.optJSONObject("action");
            JSONObject clean = cleanAction(act);
            if (clean == null) return new Response(400, err("bad_widgets", "shortcut " + s.optString("id") + ": action"));
            shortcuts.put(new JSONObject().put("id", s.getString("id")).put("label", printable(s.optString("label", s.getString("id")), 16))
                    .put("icon", printable(s.optString("icon", ""), 16)).put("action", clean));
        }
        long at = now();
        host.editConfig(j -> {
            JSONObject link = j.optJSONObject("link");
            if (link == null) j.put("link", link = new JSONObject());
            link.put("panes", panes).put("shortcuts", shortcuts).put("widgetsAt", at);
        });
        return new Response(200, ok());
    }

    static final Set<String> LINE_TONES = new HashSet<>(Arrays.asList("up", "down"));

    /**
     * The panes of PUT /v1/widgets, checked and cleaned (text only, PROTOCOL.md limits). Also used for the panes
     * the TV fetches itself (omarchytv.json "remotePanes"). Returns null with {code, message} in {@code why}.
     */
    public static JSONArray cleanPanes(JSONArray panesIn, long nowSec, String[] why) throws JSONException {
        if (panesIn.length() > 8) {
            why[0] = "too_many";
            why[1] = "8 panes";
            return null;
        }
        JSONArray panes = new JSONArray();
        for (int i = 0; i < panesIn.length(); i++) {
            JSONObject p = panesIn.optJSONObject(i);
            if (p == null || !WIDGET_ID.matcher(p.optString("id", "")).matches()) {
                why[0] = "bad_widgets";
                why[1] = "pane id";
                return null;
            }
            JSONArray lines = p.optJSONArray("lines");
            if (lines != null && lines.length() > 8) {
                why[0] = "too_many";
                why[1] = "8 lines per pane";
                return null;
            }
            JSONArray outLines = new JSONArray();
            for (int k = 0; lines != null && k < lines.length(); k++) {
                JSONObject l = lines.optJSONObject(k);
                if (l == null) continue;
                JSONObject line = new JSONObject().put("label", printable(l.optString("label", ""), 16))
                        .put("value", printable(l.optString("value", ""), 64));
                // Optional "tone": "up" | "down" colours the value (e.g. a price change); anything else is dropped.
                String tone = l.optString("tone", "");
                if (LINE_TONES.contains(tone)) line.put("tone", tone);
                outLines.put(line);
            }
            int ttl = p.optInt("ttl", 180);
            JSONObject pane = new JSONObject().put("id", p.getString("id")).put("title", printable(p.optString("title", p.getString("id")), 48))
                    .put("icon", printable(p.optString("icon", ""), 16)).put("ttl", Math.max(10, Math.min(86_400, ttl))).put("lines", outLines);
            // Optional "at" (unix seconds): when these live stats were taken; absent = the push time (widgetsAt).
            long paneAt = p.optLong("at", 0L);
            if (paneAt > 0 && paneAt <= nowSec + 300) pane.put("at", paneAt);
            panes.put(pane);
        }
        return panes;
    }

    /** Last PUT /v1/stats (home "omarchy · pc" / "deploys · pc"), in memory only: gone with the process. */
    private static volatile JSONObject lastStats;
    private static volatile long lastStatsAt;

    /** Latest PC stats (cpu, ram, nowPlaying, deploys) or null; the caller also checks a PC is connected. */
    public static JSONObject stats() {
        return System.currentTimeMillis() - lastStatsAt < 30_000 ? lastStats : null;
    }

    static final Set<String> DEPLOY_STATUS = new HashSet<>(Arrays.asList("green", "yellow", "red", "ok", "building", "failed"));

    /** v2.9: {cpu 0..100, ram 0..100, nowPlaying {artist, title}?, deploys [{name, status}]?}. Never written to disk. */
    private Response stats(JSONObject b, String pcId) throws Exception {
        JSONObject o = new JSONObject();
        if (b.has("cpu")) o.put("cpu", Math.max(0, Math.min(100, b.optInt("cpu"))));
        if (b.has("ram")) o.put("ram", Math.max(0, Math.min(100, b.optInt("ram"))));
        JSONObject np = b.optJSONObject("nowPlaying");
        if (np != null && !np.optString("title", "").isEmpty()) {
            o.put("nowPlaying", new JSONObject().put("artist", printable(np.optString("artist", ""), 64)).put("title", printable(np.optString("title", ""), 96)));
        }
        JSONArray d = b.optJSONArray("deploys");
        if (d != null) {
            if (d.length() > 8) return new Response(400, err("too_many", "8 deploys"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < d.length(); i++) {
                JSONObject x = d.optJSONObject(i);
                if (x == null) continue;
                String st = x.optString("status", "").toLowerCase(Locale.ROOT);
                if (!DEPLOY_STATUS.contains(st)) return new Response(400, err("bad_stats", "deploy status"));
                out.put(new JSONObject().put("name", printable(x.optString("name", ""), 24)).put("status", st));
            }
            o.put("deploys", out);
        }
        lastStats = o;
        lastStatsAt = System.currentTimeMillis();
        host.onEvent(pcId, "stats", o);
        return new Response(200, ok());
    }

    /** v2.9: {text: screensaver.txt} (at most 16 KiB, 60 lines x 240 cols); "" restores the OMARCHY TV logo. */
    private Response screensaver(JSONObject b) throws Exception {
        String t = b.optString("text", "");
        String[] lines = t.split("\n", -1);
        if (t.length() > 16_384 || lines.length > 60) return new Response(413, err("too_large", "16 KiB, 60 lines"));
        for (String l : lines) if (l.length() > 240) return new Response(400, err("bad_screensaver", "line too long"));
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < ' ' && c != '\n') return new Response(400, err("bad_screensaver", "control character"));
        }
        host.editConfig(j -> {
            JSONObject link = j.optJSONObject("link");
            if (link == null) j.put("link", link = new JSONObject());
            if (t.trim().isEmpty()) link.remove("screensaver");
            else link.put("screensaver", t);
        });
        return new Response(200, ok());
    }

    static final Pattern PKG = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

    /** app / view (https:, vnd.youtube:, nflx: only) / pc (command id only); null when not allowed. */
    static JSONObject cleanAction(JSONObject a) throws JSONException {
        if (a == null) return null;
        String type = a.optString("type", "");
        String pkg = a.optString("package", "");
        if (!pkg.isEmpty() && !PKG.matcher(pkg).matches()) return null;
        switch (type) {
            case "app":
                return pkg.isEmpty() ? null : new JSONObject().put("type", "app").put("package", pkg);
            case "view": {
                String uri = a.optString("uri", "");
                String low = uri.toLowerCase(Locale.ROOT);
                if (uri.length() > 2048 || !(low.startsWith("https://") || low.startsWith("vnd.youtube:") || low.startsWith("nflx://"))) return null;
                for (int i = 0; i < uri.length(); i++) if (uri.charAt(i) <= ' ') return null;
                JSONObject o = new JSONObject().put("type", "view").put("uri", uri);
                if (!pkg.isEmpty()) o.put("package", pkg);
                return o;
            }
            case "pc": {
                String cmd = a.optString("command", "");
                return WIDGET_ID.matcher(cmd).matches() ? new JSONObject().put("type", "pc").put("command", cmd) : null;
            }
            default:
                return null;
        }
    }

    private Response ai(JSONObject a, String pcId) throws Exception {
        JSONArray items = a.optJSONArray("ai");
        if (items == null || items.length() > 16) return new Response(400, err("bad_ai", "\"ai\": at most 16 entries"));
        for (int i = 0; i < items.length(); i++) {
            JSONObject e = items.optJSONObject(i);
            if (e == null || e.optString("name", "").trim().isEmpty()) return new Response(400, err("bad_ai", "entry " + i));
        }
        JSONArray sources = null;
        if (a.has("aiSources")) {
            JSONArray in = a.optJSONArray("aiSources");
            if (in == null || in.length() > 16) return new Response(400, err("bad_ai", "\"aiSources\": at most 16"));
            sources = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < in.length(); i++) {
                JSONObject e = in.optJSONObject(i);
                String id = e == null ? "" : e.optString("id", "");
                if (!PROVIDER_ID.matcher(id).matches() || !seen.add(id)) return new Response(400, err("bad_ai", "aiSources " + i));
                sources.put(new JSONObject().put("id", id).put("name", printable(e.optString("name", id), 40))
                        .put("enabled", e.optBoolean("enabled", false)).put("available", e.optBoolean("available", true))
                        .put("inBar", e.optBoolean("inBar", false)));
            }
            aiSourcesFrom = pcId;
        }
        final JSONArray src = sources;
        long at = now();
        host.editConfig(j -> {
            j.put("ai", items);   // verbatim, canonical top-level "ai" (+ "aiAt")
            j.put("aiAt", at);
            // aiSources present = this PC accepts the "ai_sources" event; absent = no switches on the TV.
            if (src != null) j.put("aiSources", src);
            else j.remove("aiSources");
            JSONObject link = j.optJSONObject("link");
            if (link != null) link.remove("ai");
        });
        return new Response(200, ok());
    }

    private Response providers(JSONObject a, String pcId) throws Exception {
        JSONArray in = a.optJSONArray("providers");
        if (in == null || in.length() > 16) return new Response(400, err("bad_providers", "at most 16"));
        JSONArray clean = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < in.length(); i++) {
            JSONObject p = in.optJSONObject(i);
            String id = p == null ? "" : p.optString("id", "");
            if (!PROVIDER_ID.matcher(id).matches() || !ids.add(id)) return new Response(400, err("bad_providers", "bad id"));
            JSONObject o = new JSONObject().put("id", id).put("name", printable(p.optString("name", id), 32));
            if (p.has("kind")) o.put("kind", printable(p.optString("kind"), 16));
            if (p.has("model")) o.put("model", p.isNull("model") ? JSONObject.NULL : printable(p.optString("model"), 64));
            if (p.has("streaming")) o.put("streaming", p.optBoolean("streaming"));
            clean.put(o);
        }
        String def = a.isNull("default") ? "" : a.optString("default", "");
        Object dflt = ids.contains(def) ? def : JSONObject.NULL;
        providersFrom = pcId;
        host.editConfig(j -> {
            JSONObject link = j.optJSONObject("link");
            if (link == null) j.put("link", link = new JSONObject());
            link.put("aiProviders", clean).put("aiDefault", dflt);
        });
        return new Response(200, ok());
    }

    // ---- agents (§6 Agents) -------------------------------------------------------------------------

    /** The last list received since the launcher started, or {"agents":[],"at":null}. */
    public JSONObject agents() throws JSONException {
        JSONObject a = agentsNow;
        return a != null ? a : new JSONObject().put("agents", new JSONArray()).put("at", JSONObject.NULL);
    }

    private JSONObject setAgents(Agents a) {
        JSONObject j = a.toJson();
        try {
            j.put("rx", System.currentTimeMillis());   // TV clock when it arrived: timers count from here (no PC clock skew)
        } catch (JSONException ignored) {
        }
        agentsNow = j;
        return j;
    }

    private Response agentsPut(JSONObject body, String pcId) throws Exception {
        JSONArray in = body.optJSONArray("agents");
        if (in == null || in.length() > Agents.MAX_AGENTS) return new Response(400, err("bad_agents", "agents: a list of at most 8"));
        Agents a = Agents.parse(body);
        JSONObject clean = setAgents(a);
        if (clean.toString().length() > 64 * 1024) return new Response(413, err("too_large", "at most 64 KiB"));
        agentsFrom = pcId;
        long at = now();
        host.editConfig(j -> {
            JSONObject link = j.optJSONObject("link");
            if (link == null) j.put("link", link = new JSONObject());
            link.put("agents", clean).put("agentsAt", at);
        });
        host.onEvent(pcId, "agent_activity", clean);
        return new Response(200, ok());
    }

    public JSONObject cinemarchyLibrary() throws JSONException {
        JSONObject c = cinemarchyNow;
        return c != null ? c : new JSONObject().put("items", new JSONArray()).put("continueWatching", new JSONArray());
    }

    private Response cinemarchyLibraryPut(JSONObject body, String pcId) throws Exception {
        final JSONArray items = body.optJSONArray("items") != null ? body.optJSONArray("items") : new JSONArray();
        if (items.length() > 500) return new Response(400, err("too_many", "at most 500 library items"));
        final JSONArray cont = body.optJSONArray("continueWatching") != null ? body.optJSONArray("continueWatching") : new JSONArray();
        JSONObject clean = new JSONObject().put("items", items).put("continueWatching", cont).put("at", now());
        cinemarchyNow = clean;
        host.editConfig(j -> {
            JSONObject cm = j.optJSONObject("cinemarchy");
            if (cm == null) j.put("cinemarchy", cm = new JSONObject());
            cm.put("library", items).put("continueWatching", cont).put("at", now());
        });
        host.onEvent(pcId, "cinemarchy", clean);
        return new Response(200, ok());
    }

    public JSONObject cinemarchySnippets() throws JSONException {
        JSONObject s = snippetsNow;
        return s != null ? s : new JSONObject().put("snippets", new JSONArray());
    }

    private Response cinemarchySnippetsPut(JSONObject body, String pcId) throws Exception {
        final JSONArray sn = body.optJSONArray("snippets") != null ? body.optJSONArray("snippets") : new JSONArray();
        if (sn.length() > 100) return new Response(400, err("too_many", "at most 100 snippets"));
        JSONObject clean = new JSONObject().put("snippets", sn).put("at", now());
        snippetsNow = clean;
        host.editConfig(j -> {
            JSONObject cm = j.optJSONObject("cinemarchy");
            if (cm == null) j.put("cinemarchy", cm = new JSONObject());
            cm.put("snippets", sn);
        });
        host.onEvent(pcId, "cinemarchy_snippets", clean);
        return new Response(200, ok());
    }

    private Response cinemarchyHandoff(JSONObject body, String pcId) throws Exception {
        String url = body.optString("url", "").trim();
        String title = printable(body.optString("title", "PC mpv Handoff"), 120);
        double pos = Math.max(0, body.optDouble("position", 0));
        if (url.isEmpty() && !body.has("file")) {
            return new Response(400, err("bad_request", "url or file required for handoff"));
        }
        JSONObject castBody = new JSONObject()
                .put("id", "handoff-" + System.currentTimeMillis())
                .put("kind", body.optString("kind", "video"))
                .put("title", title)
                .put("from", body.optString("from", "mpv · Omarchy PC"))
                .put("position", pos);
        if (!url.isEmpty()) castBody.put("url", url);
        if (body.has("file")) castBody.put("file", body.optString("file"));
        host.editConfig(j -> {
            JSONObject cm = j.optJSONObject("cinemarchy");
            if (cm == null) j.put("cinemarchy", cm = new JSONObject());
            cm.put("lastHandoff", new JSONObject()
                    .put("direction", "pc_to_tv")
                    .put("title", title)
                    .put("url", url)
                    .put("position", pos)
                    .put("at", now()));
        });
        return cast(castBody);
    }

    /** v2.13 IPTV: user-supplied playlists + optional XMLTV URL (cinemarchy.iptv in omarchytv.json). */
    private JSONObject cinemarchyIptv() throws Exception {
        JSONObject[] out = {null};
        host.editConfig(j -> {
            JSONObject cm = j.optJSONObject("cinemarchy");
            JSONObject iptv = cm == null ? null : cm.optJSONObject("iptv");
            out[0] = iptv != null ? new JSONObject(iptv.toString()) : new JSONObject();
        });
        JSONObject r = out[0];
        if (!r.has("playlists")) r.put("playlists", new JSONArray());
        if (!r.has("epg")) r.put("epg", "");
        return r;
    }

    private static boolean httpUrl(String u) {
        return u != null && u.length() <= 2000 && u.matches("(?i)https?://[^\\s]{3,}");
    }

    private Response cinemarchyIptvPut(JSONObject body, String pcId) throws Exception {
        JSONArray in = body.optJSONArray("playlists") != null ? body.optJSONArray("playlists") : new JSONArray();
        if (in.length() > 50) return new Response(400, err("too_many", "at most 50 playlists"));
        JSONArray pl = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.optJSONObject(i);
            if (o == null || !httpUrl(o.optString("url", "").trim())) return new Response(400, err("bad_url", "playlist url must be http(s)"));
            pl.put(new JSONObject().put("name", printable(o.optString("name", "Playlist " + (i + 1)), 80))
                    .put("url", o.optString("url").trim()));
        }
        String epg = body.optString("epg", "").trim();
        if (!epg.isEmpty() && !httpUrl(epg)) return new Response(400, err("bad_url", "epg url must be http(s)"));
        JSONObject clean = new JSONObject().put("playlists", pl).put("epg", epg);
        host.editConfig(j -> {
            JSONObject cm = j.optJSONObject("cinemarchy");
            if (cm == null) j.put("cinemarchy", cm = new JSONObject());
            cm.put("iptv", clean);
        });
        host.onEvent(pcId, "cinemarchy_iptv", clean);
        return new Response(200, ok().put("playlists", pl.length()));
    }

    /** v2.13 "omarchy-tv send --queue": append a link to Cinemarchy's Up-Next queue (queue://up-next). */

    /** v2.13 Declarative Plugin Engine (sandboxed JSON state, zero LocalAdb / shell access). */
    private JSONObject pluginsGet() throws Exception {
        final JSONObject[] out = {null};
        host.editConfig(j -> {
            JSONObject pl = j.optJSONObject("plugins");
            out[0] = pl != null ? new JSONObject(pl.toString()) : new JSONObject();
        });
        JSONObject r = out[0];
        if (!r.has("spacexTv")) r.put("spacexTv", true);
        if (!r.has("grokbot")) r.put("grokbot", true);
        if (!r.has("plugins")) {
            r.put("plugins", new JSONArray()
                    .put(new JSONObject().put("id", "com.sighmon.spacex-tv").put("name", "SpaceX TV")
                            .put("author", "sighmon").put("license", "MIT").put("enabled", r.optBoolean("spacexTv", true)))
                    .put(new JSONObject().put("id", "io.github.tcballard.grokbot-desktop").put("name", "Agent Desktop")
                            .put("author", "tcballard").put("license", "MIT").put("enabled", r.optBoolean("grokbot", true))));
        }
        return r;
    }

    private Response pluginsPut(JSONObject body, String pcId) throws Exception {
        final boolean sx = body.optBoolean("spacexTv", true);
        final boolean gb = body.optBoolean("grokbot", true);
        JSONArray in = body.optJSONArray("plugins");
        final JSONArray cleanArr = new JSONArray();
        if (in != null) {
            if (in.length() > 32) return new Response(400, err("too_many", "at most 32 plugins"));
            for (int i = 0; i < in.length(); i++) {
                JSONObject o = in.optJSONObject(i);
                if (o == null) continue;
                String id = printable(o.optString("id", ""), 64);
                if (id.isEmpty()) continue;
                cleanArr.put(new JSONObject()
                        .put("id", id)
                        .put("name", printable(o.optString("name", id), 80))
                        .put("author", printable(o.optString("author", ""), 60))
                        .put("license", printable(o.optString("license", "MIT"), 32))
                        .put("enabled", o.optBoolean("enabled", true)));
            }
        }
        final JSONObject clean = new JSONObject()
                .put("spacexTv", sx)
                .put("grokbot", gb)
                .put("plugins", cleanArr)
                .put("at", now());
        host.editConfig(j -> {
            JSONObject old = j.optJSONObject("plugins");
            if (old != null && old.has("grokbotMessage")) clean.put("grokbotMessage", old.optString("grokbotMessage", ""));
            if (old != null && old.has("grokbotBot")) clean.put("grokbotBot", old.opt("grokbotBot"));
            j.put("plugins", clean);
        });
        host.onEvent(pcId, "plugins", clean);
        return new Response(200, ok().put("spacexTv", sx).put("grokbot", gb));
    }

    private JSONObject pluginsGrokbotGet() throws Exception {
        final String[] msg = {""};
        final Object[] bot = {JSONObject.NULL};
        host.editConfig(j -> {
            JSONObject pl = j.optJSONObject("plugins");
            if (pl != null) {
                msg[0] = pl.optString("grokbotMessage", "");
                JSONObject b = pl.optJSONObject("grokbotBot");
                if (b != null) bot[0] = b;
            }
        });
        return new JSONObject().put("ok", true).put("message", msg[0]).put("bot", bot[0]);
    }

    /** Shapes/colours @tcballard's Agent Desktop uses (names only; the TV draws its own primitives). */
    static final java.util.Set<String> GROKBOT_SHAPES = new java.util.HashSet<>(java.util.Arrays.asList(
            "circle", "square", "triangle", "diamond", "pentagon", "hexagon", "octagon", "heptagon", "pill", "cloud",
            "heart", "crescent", "star", "spark", "burst", "flower", "clover", "sprout", "drop", "bean", "peanut",
            "pebble", "shield", "ghost", "bolt"));
    static final java.util.Set<String> GROKBOT_COLORS = new java.util.HashSet<>(java.util.Arrays.asList(
            "black", "brown", "red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta", "gray", "omarchy"));

    /** The companion's current bot as pushed by omarchy-link, cleaned for the tile; null when unusable. */
    public static JSONObject sanitizeGrokbotBot(JSONObject b) throws Exception {
        if (b == null) return null;
        String name = printable(b.optString("name", ""), 40);
        if (name.isEmpty()) return null;
        String shape = b.optString("shape", "bean").toLowerCase(java.util.Locale.ROOT);
        String color = b.optString("color", "orange").toLowerCase(java.util.Locale.ROOT);
        String fill = b.optString("fill", "");
        String mode = b.optString("mode", "offline");
        String version = b.optString("version", "");
        String compat = b.optString("compat", "branch");
        return new JSONObject()
                .put("name", name)
                .put("shortName", printable(b.optString("shortName", name), 24))
                .put("shape", GROKBOT_SHAPES.contains(shape) ? shape : "bean")
                .put("color", GROKBOT_COLORS.contains(color) ? color : "orange")
                .put("fill", fill.matches("#[0-9A-Fa-f]{6}") ? fill.toUpperCase(java.util.Locale.ROOT) : JSONObject.NULL)
                .put("mode", "relay".equals(mode) || "ipc".equals(mode) ? mode : "offline")
                // v0.2.0 = the companion release the bridge targets; the branch may report none.
                .put("version", version.matches("v?\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") ? version.replaceFirst("^v", "") : JSONObject.NULL)
                .put("compat", GROKBOT_COMPAT.contains(compat) ? compat : "branch");
    }

    private static final java.util.Set<String> GROKBOT_COMPAT = new java.util.HashSet<>(
            java.util.Arrays.asList("target", "branch", "newer", "unsupported"));

    private Response pluginsGrokbotPost(JSONObject body, String pcId) throws Exception {
        final String msg = printable(body.optString("message", body.optString("text", "")), 240);
        final boolean hasBot = body.has("bot");
        final JSONObject bot = hasBot ? sanitizeGrokbotBot(body.optJSONObject("bot")) : null;
        if (hasBot && bot == null && !body.isNull("bot")) return new Response(400, err("bad_bot", "bot needs a name"));
        if (hasBot && bot != null) grokbotFrom = pcId;
        if (msg.isEmpty() && !hasBot) return new Response(400, err("bad_request", "message or bot required"));
        host.editConfig(j -> {
            JSONObject pl = j.optJSONObject("plugins");
            if (pl == null) j.put("plugins", pl = new JSONObject().put("spacexTv", true).put("grokbot", true));
            if (!msg.isEmpty()) pl.put("grokbotMessage", msg).put("grokbotAt", now());
            if (hasBot) {
                if (bot == null) pl.remove("grokbotBot");
                else pl.put("grokbotBot", bot);
            }
        });
        JSONObject ev = new JSONObject().put("from", printable(body.optString("from", "pc"), 40));
        if (!msg.isEmpty()) ev.put("message", msg);
        if (hasBot) ev.put("bot", bot != null ? bot : JSONObject.NULL);
        host.onEvent(pcId, "grokbot_companion", ev);
        JSONObject r = ok();
        if (!msg.isEmpty()) r.put("message", msg);
        if (hasBot) r.put("bot", bot != null ? bot : JSONObject.NULL);
        return new Response(200, r);
    }

    private Response cinemarchyQueue(JSONObject body, String pcId) throws Exception {
        String url = body.optString("url", "").trim();
        if (!httpUrl(url) || url.length() > 4096) return new Response(400, err("bad_url", "url must be http(s)"));
        String sub = body.optString("subtitle", "").trim();
        if (!sub.isEmpty() && !httpUrl(sub)) return new Response(400, err("bad_url", "subtitle must be http(s)"));
        // A local .srt/.vtt from the PC arrives inline (the PC is not serving it later, when the item plays).
        String subData = body.optString("subtitleData", "");
        if (sub.isEmpty() && !subData.isEmpty()) {
            String fmt = body.optString("subtitleFormat", "srt");
            if (!"srt".equals(fmt) && !"vtt".equals(fmt)) return new Response(400, err("bad_subtitle", "srt or vtt"));
            byte[] raw = subData.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (raw.length > 1024 * 1024) return new Response(400, err("bad_subtitle", "subtitle larger than 1 MiB"));
            File base = host.castDir() != null ? host.castDir() : host.wallpaperDir().getParentFile();
            File dir = new File(base, "queue-subs");
            if (!dir.isDirectory() && !dir.mkdirs()) return new Response(500, err("io", "cannot store subtitle"));
            String name = LinkCrypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)).substring(0, 24) + "." + fmt;
            File f = new File(dir, name);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(raw); }
            sub = "file://" + f.getAbsolutePath();
        }
        String title = printable(body.optString("title", url), 200);
        JSONObject e = new JSONObject().put("title", title).put("subtitle", "from PC").put("url", url)
                .put("live", false).put("pos", 0);
        if (!sub.isEmpty()) e.put("subs", sub);
        final int[] n = {0};
        host.editConfig(j -> {
            JSONObject cm = j.optJSONObject("cinemarchy");
            if (cm == null) j.put("cinemarchy", cm = new JSONObject());
            JSONArray q = cm.optJSONArray("queue");
            if (q == null) cm.put("queue", q = new JSONArray());
            if (q.length() >= 100) q.remove(0);
            q.put(e);
            n[0] = q.length();
        });
        host.onEvent(pcId, "cinemarchy_queue", new JSONObject().put("title", title).put("length", n[0]));
        return new Response(200, ok().put("length", n[0]));
    }

    /** v2.13 Cinemarchy "From PC" row: the last 30 video/audio casts (title, url-or-file, from, time). */
    private void rememberFromPc(Cast c) {
        if (!"video".equals(c.kind) && !"audio".equals(c.kind)) return;
        try {
            JSONObject e = new JSONObject().put("title", c.title == null || c.title.isEmpty() ? "Untitled" : c.title)
                    .put("from", c.from == null ? "" : c.from).put("at", now()).put("kind", c.kind);
            if (c.url != null) e.put("url", c.url);
            if (c.file != null) e.put("file", c.file.getName());
            if (c.subtitle != null) e.put("subs", c.subtitle);
            host.editConfig(j -> {
                JSONObject cm = j.optJSONObject("cinemarchy");
                if (cm == null) j.put("cinemarchy", cm = new JSONObject());
                JSONArray old = cm.optJSONArray("fromPc");
                JSONArray nu = new JSONArray().put(e);
                for (int i = 0; old != null && i < old.length() && nu.length() < 30; i++) nu.put(old.get(i));
                cm.put("fromPc", nu);
            });
        } catch (Exception ignored) {
            // history is best-effort; never fail a cast over it
        }
    }

    /** v2.13 ":pause" with nothing playing on the TV: tells PCs to toggle mpv (event only). */
    public void cinemarchyMpv(String action) throws Exception {
        broadcast("cinemarchy_mpv", new JSONObject().put("action", action).put("at", now()));
    }

    public Response cinemarchyHandoffToPc(JSONObject body) throws Exception {
        String title = printable(body.optString("title", ""), 120);
        String url = body.optString("url", "").trim();
        double pos = Math.max(0, body.optDouble("position", 0));
        JSONObject payload = new JSONObject()
                .put("direction", "tv_to_pc")
                .put("title", title)
                .put("url", url)
                .put("position", Math.round(pos * 10) / 10.0)
                .put("at", now());
        broadcast("cinemarchy_handoff", payload);
        host.editConfig(j -> {
            JSONObject cm = j.optJSONObject("cinemarchy");
            if (cm == null) j.put("cinemarchy", cm = new JSONObject());
            cm.put("lastHandoff", payload);
        });
        return new Response(200, ok().put("handoff", payload));
    }

    /** agent_ask_state / agent_answer / agent_ask_error reduced to their plain-text fields. */
    static JSONObject agentReply(String type, JSONObject d) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", printable(d.optString("id", ""), 64)).put("agentId", printable(d.optString("agentId", ""), 128));
            if ("agent_answer".equals(type)) o.put("text", printable(d.optString("text", ""), 1200));
            else if ("agent_ask_state".equals(type)) {
                String st = d.optString("state", "");
                o.put("state", "awaiting_approval".equals(st) || "running".equals(st) ? st : "running");
                if (d.has("message")) o.put("message", printable(d.optString("message", ""), 200));
            } else {
                o.put("error", printable(d.optString("error", "failed"), 32)).put("message", printable(d.optString("message", ""), 200));
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** grokbot_answer / grokbot_error from omarchy-link (experimental Grok Bot companion bridge), plain text only. */
    static JSONObject grokbotReply(String type, JSONObject d) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", printable(d.optString("id", ""), 64)).put("bot", printable(d.optString("bot", ""), 40));
            if ("grokbot_answer".equals(type)) {
                String mode = d.optString("mode", "relay");
                o.put("text", printable(d.optString("text", ""), 1200)).put("mode", "ipc".equals(mode) ? "ipc" : "relay");
            } else {
                o.put("error", printable(d.optString("error", "failed"), 32)).put("message", printable(d.optString("message", ""), 200));
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    /**
     * TV -> PC "grokbot_ask" {"id","text"[,"bot"]} to the PC that pushed the companion state (else the first one).
     * Returns null when sent, else "bad_request" (empty or over 240 chars), "busy" (1 per 3 s) or "not_connected".
     */
    public synchronized String grokbotAsk(String id, String text, String bot) {
        text = text == null ? "" : text.trim();
        if (id == null || id.isEmpty() || text.isEmpty() || text.codePointCount(0, text.length()) > 240) return "bad_request";
        long t = System.currentTimeMillis();
        if (limits && t - lastGrokbotAskMs < 3000) return "busy";
        WsClient target = null;
        for (WsClient c : clients) {
            if (c.closed) continue;
            if (target == null || c.pcId.equals(grokbotFrom)) target = c;
        }
        if (target == null) return "not_connected";
        try {
            JSONObject d = new JSONObject().put("id", id).put("text", text);
            if (bot != null && !bot.trim().isEmpty()) d.put("bot", printable(bot, 80));
            target.send("grokbot_ask", d);
            lastGrokbotAskMs = t;
            return null;
        } catch (JSONException e) {
            return "bad_request";
        } catch (IOException e) {
            target.close();
            return "not_connected";
        }
    }

    /**
     * Sends {"id","agentId","text"} as agent_ask to the PC that sends agent activity (else the first one).
     * Returns null when sent, else "bad_request" (empty or over 2000 chars), "busy" (1 per 3 s) or "not_connected".
     */
    public synchronized String agentAsk(String id, String agentId, String text) {
        text = text == null ? "" : text.trim();
        if (id == null || id.isEmpty() || agentId == null || agentId.isEmpty() || text.isEmpty()
                || text.codePointCount(0, text.length()) > Agents.ASK_MAX) return "bad_request";
        long t = System.currentTimeMillis();
        if (limits && t - lastAgentAskMs < 3000) return "busy";
        WsClient target = null;
        for (WsClient c : clients) {
            if (c.closed) continue;
            if (target == null || c.pcId.equals(agentsFrom)) target = c;
        }
        if (target == null) return "not_connected";
        try {
            target.send("agent_ask", new JSONObject().put("id", id).put("agentId", agentId).put("text", text));
            lastAgentAskMs = t;
            return null;
        } catch (JSONException e) {
            return "bad_request";
        } catch (IOException e) {
            target.close();
            return "not_connected";
        }
    }

    /** The agents widget became visible: ask every PC for a fresh agent_activity. */
    public void agentSnapshot() {
        broadcast("agent_snapshot", new JSONObject());
    }

    // ---- WebSocket events (§7) ---------------------------------------------------------------------

    final class WsClient {
        final String pcId;
        final byte[] ks;
        final Socket sock;
        final OutputStream out;
        long sendSeq, recvSeq;
        volatile boolean closed;

        WsClient(String pcId, byte[] ks, Socket sock, OutputStream out) {
            this.pcId = pcId;
            this.ks = ks;
            this.sock = sock;
            this.out = out;
        }

        synchronized void send(String type, JSONObject data) throws IOException {
            if (closed) throw new IOException("closed");
            sendSeq++;
            String payload = "{\"seq\":" + sendSeq + ",\"type\":" + JSONObject.quote(type) + ",\"data\":"
                    + (data == null ? "{}" : data.toString()) + "}";
            frame(0x1, LinkCrypto.utf8(LinkCrypto.seal(ks, payload)));
        }

        synchronized void frame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream h = new ByteArrayOutputStream(10 + payload.length);
            h.write(0x80 | opcode);
            int n = payload.length;
            if (n < 126) {
                h.write(n);
            } else if (n < 65536) {
                h.write(126);
                h.write(n >>> 8);
                h.write(n & 0xFF);
            } else {
                h.write(127);
                for (int i = 7; i >= 0; i--) h.write((int) (((long) n >>> (8 * i)) & 0xFF));
            }
            h.write(payload, 0, n);
            out.write(h.toByteArray());
            out.flush();
        }

        void close() {
            closed = true;
            closeQuietly(sock);
        }
    }

    static String acceptKey(String key) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1));
            return com.omarchytv.home.ApiCompat.b64(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void websocket(Request req, Auth a, Socket sock, InputStream in, OutputStream out) throws IOException {
        String serverNonce = LinkCrypto.b64u(LinkCrypto.randomBytes(16));
        Response r = new Response(101, null);
        r.headers.put("Upgrade", "websocket");
        r.headers.put("Connection", "Upgrade");
        r.headers.put("Sec-WebSocket-Accept", acceptKey(req.header("Sec-WebSocket-Key")));
        r.headers.put("Sec-WebSocket-Protocol", "omarchytv-link.v1");
        r.headers.put("X-OTV-Nonce", serverNonce);
        r.headers.put("X-OTV-Sig", LinkCrypto.signResponse(a.token, a.nonce, 101, LinkCrypto.utf8(serverNonce)));
        write(out, r);
        WsClient c = new WsClient(a.pcId, LinkCrypto.wsSessionKey(a.token, a.nonce, serverNonce), sock, out);
        sock.setSoTimeout(70_000);   // the PC pings every 25 s; we ping too
        linkUp(c);
        host.log("events: " + a.pcId.substring(0, Math.min(6, a.pcId.length())) + "… connected");
        try {
            c.send("hello", status());
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (!c.closed) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                long n = b1 & 0x7F;
                if (n == 126) n = (in.read() << 8) | in.read();
                else if (n == 127) {
                    n = 0;
                    for (int i = 0; i < 8; i++) n = (n << 8) | (in.read() & 0xFF);
                }
                if (n < 0 || n > WS_MAX || msg.size() + n > WS_MAX) break;
                byte[] mask = (b1 & 0x80) != 0 ? readBody(in, 4) : null;
                byte[] data = readBody(in, (int) n);
                if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (op == 0x8) {
                    try {
                        c.frame(0x8, new byte[0]);
                    } catch (IOException ignored) {
                    }
                    break;
                }
                if (op == 0x9) {
                    c.frame(0xA, data);
                    continue;
                }
                if (op == 0xA) continue;
                if (op != 0x1 && op != 0x0) break;   // binary frames are not part of v1
                msg.write(data, 0, data.length);
                if (!fin) continue;
                String text = new String(msg.toByteArray(), StandardCharsets.UTF_8);
                msg.reset();
                try {
                    if (!incoming(c, text)) break;
                } catch (JSONException e) {
                    continue;   // a frame we cannot rebuild is dropped, the link stays up
                }
            }
        } catch (SocketException e) {
            // closed
        } finally {
            linkDown(c);
            c.close();
        }
    }

    /** One sealed frame from the PC; false = protocol violation (bad MAC or seq), close. */
    private boolean incoming(WsClient c, String text) throws IOException, JSONException {
        String payload = LinkCrypto.open(c.ks, text);
        if (payload == null) return false;
        JSONObject m;
        try {
            m = new JSONObject(payload);
        } catch (JSONException e) {
            return false;
        }
        if (m.optLong("seq", -1) != c.recvSeq + 1) return false;
        c.recvSeq++;
        String type = m.optString("type", "");
        JSONObject data = m.optJSONObject("data");
        if (data == null) data = new JSONObject();
        switch (type) {
            case "answer":
            case "answer_error":
            case "ai_sources_ok":
            case "ai_sources_error":
                host.onEvent(c.pcId, type, data);
                break;
            case "agent_activity":
                agentsFrom = c.pcId;
                host.onEvent(c.pcId, type, setAgents(Agents.parse(data)));
                break;
            case "agent_ask_state":
            case "agent_answer":
            case "agent_ask_error":
                host.onEvent(c.pcId, type, agentReply(type, data));
                break;
            case "grokbot_answer":
            case "grokbot_error":
                host.onEvent(c.pcId, type, grokbotReply(type, data));
                break;
            case "pc_wol": {   // v2.14 the PC's MAC for Wake-on-LAN ("TV and PC power together")
                String mac = data.optString("mac", "").toLowerCase(Locale.ROOT);
                String bc = data.optString("broadcast", "");
                if (!mac.matches("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) break;
                JSONObject wol = new JSONObject().put("mac", mac);
                if (bc.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) wol.put("broadcast", bc);
                host.onEvent(c.pcId, "pc_wol", wol);
                break;
            }
            case "omastorm_state":   // v2.14 the PC could not open Omastorm (not_installed, disabled, ...)
                host.onEvent(c.pcId, type, new JSONObject().put("state", printable(data.optString("state", ""), 24)));
                break;
            case "media_list":   // Cinemarchy: a folder of the PC's media (names and relative paths only)
                host.onEvent(c.pcId, type, mediaList(data));
                break;
            case "living":   // v2.14 Living screensaver feed (GitHub, PC notifications, X), memory only
                host.onEvent(c.pcId, type, living(data));
                break;
            case "liftoff":   // v2.14 a build/update on the PC: rocket countdown on Home (LiftoffView)
                host.onEvent(c.pcId, type, liftoff(data));
                break;
            default:
                break;   // ping/pong (keep-alive only; the TV uses WebSocket ping frames) and unknown types
        }
        return true;
    }

    private void pingAll() {
        for (WsClient c : clients) {
            try {
                c.frame(0x9, new byte[0]);
            } catch (IOException e) {
                c.close();
            }
        }
    }

    /** v2.14 "living" from the PC, re-built with known fields only, every string clipped and printable. */
    static JSONObject living(JSONObject d) throws JSONException {
        JSONObject o = new JSONObject();
        String[][] spec = {
                {"github", "6", "repo:60", "what:24", "who:39", "text:90", "at:24"},
                {"notes", "12", "app:32", "title:80", "body:200", "at:24"},
                {"x", "8", "kind:10", "user:80", "text:240", "at:24"},
        };
        for (String[] s : spec) {
            JSONArray in = d.optJSONArray(s[0]), out = new JSONArray();
            int max = Integer.parseInt(s[1]);
            for (int i = 0; in != null && i < in.length() && out.length() < max; i++) {
                JSONObject x = in.optJSONObject(i);
                if (x == null) continue;
                JSONObject y = new JSONObject();
                for (int k = 2; k < s.length; k++) {
                    String[] f = s[k].split(":");
                    y.put(f[0], printable(x.optString(f[0], ""), Integer.parseInt(f[1])));
                }
                out.put(y);
            }
            o.put(s[0], out);
        }
        String xm = d.optString("xMode", "off");
        o.put("xMode", "notify".equals(xm) || "api".equals(xm) ? xm : "off");
        if (d.has("xError")) o.put("xError", printable(d.optString("xError", ""), 100));
        // v2.14 Omastorm radar snapshot: a JPEG data URL only, at most ~300 KB of image.
        JSONObject rad = d.optJSONObject("radar");
        String img = rad != null ? rad.optString("img", "") : "";
        if (img.startsWith("data:image/jpeg;base64,") && img.length() <= 420_000
                && img.substring(23).matches("[A-Za-z0-9+/=]+")) {
            o.put("radar", new JSONObject().put("img", img).put("at", printable(rad.optString("at", ""), 24)));
        }
        return o;
    }

    static final Set<String> LIFTOFF_KINDS = new HashSet<>(Arrays.asList("build", "update"));
    static final Set<String> LIFTOFF_STATES = new HashSet<>(Arrays.asList("running", "ok", "failed"));

    /** v2.14 "liftoff" from the PC, re-built with only known fields, clipped; state "invalid" if unusable. */
    static JSONObject liftoff(JSONObject d) throws JSONException {
        String kind = d.optString("kind", ""), st = d.optString("state", "");
        if (!LIFTOFF_KINDS.contains(kind) || !LIFTOFF_STATES.contains(st)) return new JSONObject().put("state", "invalid");
        JSONObject o = new JSONObject().put("id", printable(d.optString("id", ""), 32)).put("kind", kind).put("state", st)
                .put("name", printable(d.optString("name", ""), 40)).put("stage", printable(d.optString("stage", ""), 40))
                .put("elapsedS", Math.max(0, Math.min(864_000, d.optInt("elapsedS", 0))));
        int exp = d.optInt("expectedS", 0);
        if (exp > 0 && exp < 86_400) o.put("expectedS", exp);
        if (d.has("pct") && !d.isNull("pct")) o.put("pct", Math.max(0, Math.min(100, d.optInt("pct"))));
        if (d.has("code") && !d.isNull("code")) o.put("code", d.optInt("code"));
        JSONArray tail = d.optJSONArray("tail"), t = new JSONArray();
        if (tail != null) for (int i = Math.max(0, tail.length() - 6); i < tail.length(); i++) t.put(printable(tail.optString(i, ""), 120));
        return o.put("tail", t);
    }

    /** Sends an event to every connected PC. */
    public void broadcast(String type, JSONObject data) {
        for (WsClient c : clients) {
            try {
                c.send(type, data);
            } catch (IOException e) {
                c.close();
            }
        }
    }

    /** Test hook: an unsealed/forged text frame to every client (the PC must reject it). */
    void debugRawFrame(String text) {
        for (WsClient c : clients) {
            try {
                c.frame(0x1, LinkCrypto.utf8(text));
            } catch (IOException e) {
                c.close();
            }
        }
    }

    public int connectedClients() {
        return clients.size();
    }

    public boolean connected(String pcId) {
        for (WsClient c : clients) if (c.pcId.equals(pcId) && !c.closed) return true;
        return false;
    }

    /**
     * Sends an "ask" event (§6 Assistant) to the PC that announced the providers (else the first connected
     * one). Returns null when sent, else "not_connected" or "busy" (1 per 3 s, TV side).
     */
    public synchronized String ask(JSONObject data) {
        long t = System.currentTimeMillis();
        if (limits && t - lastAskMs < 3000) return "busy";
        WsClient target = null;
        for (WsClient c : clients) {
            if (c.closed) continue;
            if (target == null || c.pcId.equals(providersFrom)) target = c;
        }
        if (target == null) return "not_connected";
        try {
            target.send("ask", data);
            lastAskMs = t;
            return null;
        } catch (IOException e) {
            target.close();
            return "not_connected";
        }
    }

    /**
     * The user flipped an AI-pane source switch: send {"id","enabled":[ids]} as "ai_sources" to the PC that
     * pushed the aiSources list (else any connected one). Returns null, "busy" or "not_connected".
     * The PC answers ai_sources_ok / ai_sources_error (delivered to Host.onEvent), then re-pushes PUT /v1/ai.
     */
    public synchronized String aiSources(JSONObject data) {
        long t = System.currentTimeMillis();
        if (limits && t - lastSourcesMs < 1000) return "busy";
        WsClient target = null;
        for (WsClient c : clients) {
            if (c.closed) continue;
            if (target == null || c.pcId.equals(aiSourcesFrom)) target = c;
        }
        if (target == null) return "not_connected";
        try {
            target.send("ai_sources", data);
            lastSourcesMs = t;
            return null;
        } catch (IOException e) {
            target.close();
            return "not_connected";
        }
    }

    /** A "pc" shortcut was pressed: tell the PCs (they run only commands from their own config). */
    /** v2.9 top bar "Schermo PC": ask the PC to start/stop its experimental screen cast (PC confirms). */
    public void screenMirror(boolean start) throws JSONException {
        broadcast("screen_mirror", new JSONObject().put("action", start ? "start" : "stop"));
    }

    public void shortcut(String command) throws JSONException {
        broadcast("shortcut", new JSONObject().put("command", command));
    }

    /** Reverse theme sync: the TV picked a theme; the PC switches to it if installed there (omarchy-link theme_set). */
    /** v2.14 what the Living screensaver wants from the PC ({notes, github, x}). */
    public void livingPrefs(JSONObject prefs) {
        broadcast("living_prefs", prefs);
    }

    public void themeSet(String name) throws JSONException {
        broadcast("theme_set", new JSONObject().put("name", name));
    }

    /** Forget PC on the TV: send "unpaired", close its sockets, delete it. */
    public void forget(String pcId) {
        closeClients(pcId, true);
        store.remove(pcId);
        host.onPcsChanged(null);
    }

    private void closeClients(String pcId, boolean tell) {
        for (WsClient c : clients) {
            if (!c.pcId.equals(pcId)) continue;
            if (tell) {
                try {
                    c.send("unpaired", new JSONObject());
                } catch (IOException ignored) {
                }
            }
            c.close();
        }
    }

    static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
