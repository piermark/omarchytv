package com.omarchytv.home;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * v2.14 "TV and PC power together": wakes the paired Omarchy PC with a Wake-on-LAN magic packet when the TV turns on.
 * The PC tells the TV its MAC and broadcast address over Omarchy Link ("pc_wol"); the PC's network card must have
 * Wake-on-LAN enabled (BIOS + `ethtool -s <dev> wol g`), and most PCs only wake over Ethernet.
 */
final class WakeOnLan {
    private WakeOnLan() {
    }

    /** "aa:bb:cc:dd:ee:ff" (or with '-') → 6 bytes, else null. */
    static byte[] mac(String s) {
        if (s == null) return null;
        String[] p = s.trim().toLowerCase(Locale.ROOT).split("[:-]");
        if (p.length != 6) return null;
        byte[] out = new byte[6];
        int sum = 0;
        for (int i = 0; i < 6; i++) {
            if (!p[i].matches("[0-9a-f]{2}")) return null;
            out[i] = (byte) Integer.parseInt(p[i], 16);
            sum |= out[i] & 0xff;
        }
        return sum == 0 ? null : out;
    }

    /** The magic packet: 6 x 0xFF, then the MAC 16 times (102 bytes); null for a bad MAC. */
    static byte[] packet(String macText) {
        byte[] m = mac(macText);
        if (m == null) return null;
        byte[] b = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) b[i] = (byte) 0xff;
        for (int i = 0; i < 16; i++) System.arraycopy(m, 0, b, 6 + i * 6, 6);
        return b;
    }

    /** Only an IPv4 broadcast/unicast literal (never a host name: no DNS from here). */
    static boolean ipv4(String s) {
        return s != null && s.matches("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    }

    /** Sends the packet 3 times to 255.255.255.255 and the PC's subnet broadcast, ports 9 and 7. Network thread. */
    static boolean send(String macText, String broadcast) {
        byte[] b = packet(macText);
        if (b == null) return false;
        Set<String> to = new LinkedHashSet<>();
        to.add("255.255.255.255");
        if (ipv4(broadcast)) to.add(broadcast);
        boolean any = false;
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            for (int round = 0; round < 3; round++) {
                for (String host : to) {
                    for (int port : new int[]{9, 7}) {
                        try {
                            s.send(new DatagramPacket(b, b.length, InetAddress.getByName(host), port));
                            any = true;
                        } catch (Exception ignored) {
                        }
                    }
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return any;
    }
}
