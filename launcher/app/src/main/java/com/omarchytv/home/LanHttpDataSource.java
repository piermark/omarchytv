package com.omarchytv.home;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * v2.10.1: plain-HTTP reader for the paired PC's one-time cast URLs (live "Schermo PC" MPEG-TS and --serve files).
 * A network-security exception cannot name IP ranges, so this opens a socket itself, and only to a literal
 * private / link-local address (never a host name, never the internet). Cleartext is now permitted app-wide for LAN
 * web apps (network_security_config.xml); allowed() is the same gate WebAppActivity uses. GET with Range, 200/206, Content-Length or read-to-close; no redirects, no chunked bodies.
 */
@OptIn(markerClass = UnstableApi.class)
final class LanHttpDataSource extends BaseDataSource {
    private final int readTimeoutMs;
    private Socket socket;
    private InputStream in;
    private Uri uri;
    private long left;   // C.LENGTH_UNSET = until close

    LanHttpDataSource(int readTimeoutMs) {
        super(true);
        this.readTimeoutMs = readTimeoutMs;
    }

    /** http:// to a literal 10/8, 172.16/12, 192.168/16, fc00::/7 or link-local address. */
    static boolean allowed(Uri u) {
        return u != null && "http".equals(u.getScheme()) && lanHost(u.getHost());
    }

    /** A literal 10/8, 172.16/12, 192.168/16, fc00::/7 or link-local address (plain Java, also used by RemotePanes). */
    static boolean lanHost(String h) {
        if (h == null) return false;
        if (!h.matches("[0-9.]+") && !h.contains(":")) return false;   // literal addresses only, no DNS
        try {
            InetAddress a = InetAddress.getByName(h);
            byte[] b = a.getAddress();
            if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
            return b.length == 16 && (b[0] & 0xFE) == 0xFC;
        } catch (IOException e) {
            return false;
        }
    }

    static final class Factory implements DataSource.Factory {
        private final int readTimeoutMs;

        Factory(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        @Override
        public DataSource createDataSource() {
            return new LanHttpDataSource(readTimeoutMs);
        }
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        if (!allowed(uri)) throw new IOException("not a LAN address");
        transferInitializing(spec);
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(InetAddress.getByName(uri.getHost()), port), 10_000);
        socket.setSoTimeout(readTimeoutMs);
        String path = uri.getEncodedPath() == null || uri.getEncodedPath().isEmpty() ? "/" : uri.getEncodedPath();
        if (uri.getEncodedQuery() != null) path += "?" + uri.getEncodedQuery();
        StringBuilder req = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: ")
                .append(uri.getEncodedAuthority()).append("\r\nUser-Agent: OmarchyTV\r\nConnection: close\r\n");
        if (spec.position > 0 || spec.length != C.LENGTH_UNSET) {
            req.append("Range: bytes=").append(spec.position).append('-');
            if (spec.length != C.LENGTH_UNSET) req.append(spec.position + spec.length - 1);
            req.append("\r\n");
        }
        req.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
        String status = line();
        String[] sp = status.split(" ");
        int code = sp.length > 1 && sp[1].matches("\\d{3}") ? Integer.parseInt(sp[1]) : 0;
        long len = C.LENGTH_UNSET;
        boolean chunked = false;
        for (String h; !(h = line()).isEmpty(); ) {
            int c = h.indexOf(':');
            if (c <= 0) continue;
            String k = h.substring(0, c).trim().toLowerCase(Locale.ROOT), v = h.substring(c + 1).trim();
            if (k.equals("content-length") && v.matches("\\d{1,18}")) len = Long.parseLong(v);
            if (k.equals("transfer-encoding") && !v.equalsIgnoreCase("identity")) chunked = true;
        }
        if (code != 200 && code != 206) throw new IOException("HTTP " + code);
        if (chunked) throw new IOException("chunked body");
        if (code == 200 && spec.position > 0) {   // server ignored Range: skip ahead
            long skip = spec.position;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) throw new IOException("short body");
                skip -= s;
            }
            if (len != C.LENGTH_UNSET) len -= spec.position;
        }
        left = spec.length != C.LENGTH_UNSET ? spec.length : len;
        transferStarted(spec);
        return left;
    }

    private String line() throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') break;
            if (c != '\r') b.append((char) c);
            if (b.length() > 8192) throw new IOException("header too long");
        }
        return b.toString();
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (left == 0) return C.RESULT_END_OF_INPUT;
        int want = left == C.LENGTH_UNSET ? len : (int) Math.min(len, left);
        int r = in.read(buf, off, want);
        if (r < 0) return C.RESULT_END_OF_INPUT;
        if (left != C.LENGTH_UNSET) left -= r;
        bytesTransferred(r);
        return r;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        boolean was = socket != null;
        try {
            if (socket != null) socket.close();
        } finally {
            socket = null;
            in = null;
            if (was) transferEnded();
        }
    }
}
