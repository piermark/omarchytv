package com.omarchytv.home.link;

import java.net.InetAddress;

/**
 * v2.21 Tailscale, opt-in ("link": {"tailscale": true}). Plain Java so the rules are unit-tested.
 *
 * <p>Only Tailscale's own ranges count: 100.64.0.0/10 (the CGNAT block Tailscale gives every device) and
 * fd7a:115c:a1e0::/48 (its IPv6 ULA). Nothing else on a VPN, and never the internet. Funnel and Serve are not
 * used: a tailnet peer reaches the TV directly, and only when the owner turned this on.
 */
public final class Tailnet {
    /** Set from omarchytv.json when the config loads; read by the Link peer check, casts and Remote panes. */
    public static volatile boolean allowed;

    private Tailnet() {
    }

    /** 100.64.0.0/10 or fd7a:115c:a1e0::/48. */
    public static boolean isTailnet(InetAddress a) {
        if (a == null) return false;
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
        if (b.length == 16) {
            if ((b[0] & 0xFF) == 0xFD && (b[1] & 0xFF) == 0x7A && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0x5C
                    && (b[4] & 0xFF) == 0xA1 && (b[5] & 0xFF) == 0xE0) return true;
            // ::ffff:100.64.x.x (an IPv4 peer on a dual-stack socket)
            for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
            return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF && (b[12] & 0xFF) == 100 && (b[13] & 0xC0) == 64;
        }
        return false;
    }

    /** A tailnet peer, and only when the owner allowed Tailscale. */
    public static boolean allowedPeer(InetAddress a) {
        return allowed && isTailnet(a);
    }

    /**
     * The Link peer rule (OmarchyLink.allowPeer): a tailnet peer when Tailscale is allowed, else a peer on one of the
     * LAN's own subnets (address + prefix length of each LinkAddress under any VPN). Plain Java, for tests.
     */
    public static boolean peerAllowed(InetAddress peer, byte[][] lanAddrs, int[] prefixes) {
        if (peer == null) return false;
        if (allowedPeer(peer)) return true;
        byte[] p = unmap(peer.getAddress());
        for (int i = 0; lanAddrs != null && i < lanAddrs.length; i++) {
            if (samePrefix(lanAddrs[i], prefixes[i], p)) return true;
        }
        return false;
    }

    /** ::ffff:a.b.c.d (an IPv4 peer on a dual-stack socket) → the 4 IPv4 bytes; anything else unchanged. */
    static byte[] unmap(byte[] b) {
        if (b == null || b.length != 16) return b;
        for (int i = 0; i < 10; i++) if (b[i] != 0) return b;
        if ((b[10] & 0xFF) != 0xFF || (b[11] & 0xFF) != 0xFF) return b;
        return new byte[]{b[12], b[13], b[14], b[15]};
    }

    /** Same prefix of {@code bits} bits (bits &lt; 8 is never a match: never "everything"). */
    public static boolean samePrefix(byte[] a, int bits, byte[] p) {
        if (a == null || p == null || a.length != p.length || bits < 8) return false;
        for (int i = 0; i < a.length && bits > 0; i++, bits -= 8) {
            int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
            if ((a[i] & mask) != (p[i] & mask)) return false;
        }
        return true;
    }
}
