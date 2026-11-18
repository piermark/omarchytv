package com.omarchytv.home.link;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import org.junit.After;
import org.junit.Test;

public class TailnetTest {
    @After
    public void off() {
        Tailnet.allowed = false;
    }

    static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    public void onlyTailscaleRangesCount() throws Exception {
        assertTrue(Tailnet.isTailnet(ip("100.64.0.1")));
        assertTrue(Tailnet.isTailnet(ip("100.101.102.103")));
        assertTrue(Tailnet.isTailnet(ip("100.127.255.254")));
        assertFalse(Tailnet.isTailnet(ip("100.63.255.255")));
        assertFalse(Tailnet.isTailnet(ip("100.128.0.1")));
        assertFalse(Tailnet.isTailnet(ip("8.8.8.8")));
        assertFalse(Tailnet.isTailnet(ip("192.168.1.10")));
        assertTrue(Tailnet.isTailnet(ip("fd7a:115c:a1e0::1")));
        assertFalse(Tailnet.isTailnet(ip("fd7a:115c:a1e1::1")));
        assertFalse(Tailnet.isTailnet(null));
    }

    @Test
    public void tailnetPeersOnlyWhenTheOwnerAllowedIt() throws Exception {
        assertFalse(Tailnet.allowedPeer(ip("100.100.1.2")));
        Tailnet.allowed = true;
        assertTrue(Tailnet.allowedPeer(ip("100.100.1.2")));
        assertFalse(Tailnet.allowedPeer(ip("1.1.1.1")));
    }

    @Test
    public void samePrefixNeverMeansEverything() throws Exception {
        byte[] tv = ip("192.168.1.20").getAddress();
        assertTrue(Tailnet.samePrefix(tv, 24, ip("192.168.1.99").getAddress()));
        assertFalse(Tailnet.samePrefix(tv, 24, ip("192.168.2.99").getAddress()));
        assertTrue(Tailnet.samePrefix(tv, 22, ip("192.168.2.99").getAddress()));
        assertFalse(Tailnet.samePrefix(tv, 0, ip("8.8.8.8").getAddress()));
        assertFalse(Tailnet.samePrefix(tv, 24, ip("fd00::1").getAddress()));
    }

    @Test
    public void linkPeerRuleLanSubnetsAndTailnetOnlyWhenAllowed() throws Exception {
        byte[][] lan = {ip("192.168.1.20").getAddress(), ip("fe80::1").getAddress()};
        int[] bits = {24, 64};
        assertTrue(Tailnet.peerAllowed(ip("192.168.1.77"), lan, bits));
        assertTrue(Tailnet.peerAllowed(ip("fe80::99"), lan, bits));
        assertFalse(Tailnet.peerAllowed(ip("192.168.2.77"), lan, bits));
        assertFalse(Tailnet.peerAllowed(ip("8.8.8.8"), lan, bits));
        assertFalse(Tailnet.peerAllowed(ip("100.101.2.3"), lan, bits));   // switch off
        assertFalse(Tailnet.peerAllowed(null, lan, bits));
        Tailnet.allowed = true;
        assertTrue(Tailnet.peerAllowed(ip("100.101.2.3"), lan, bits));
        assertTrue(Tailnet.peerAllowed(ip("100.101.2.3"), new byte[0][], new int[0]));   // no LAN, tailnet still in
        assertFalse(Tailnet.peerAllowed(ip("100.200.2.3"), lan, bits));
        assertFalse(Tailnet.peerAllowed(ip("1.1.1.1"), lan, bits));
        assertTrue(Tailnet.peerAllowed(ip("fd7a:115c:a1e0::42"), lan, bits));   // the tailnet over IPv6
    }

    @Test
    public void ipv4PeersOnADualStackSocketKeepTheirLanSubnet() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = (byte) 192;
        mapped[13] = (byte) 168;
        mapped[14] = 1;
        mapped[15] = 77;
        InetAddress peer = java.net.Inet6Address.getByAddress(null, mapped, -1);
        byte[][] lan = {ip("192.168.1.20").getAddress()};
        assertTrue(Tailnet.peerAllowed(peer, lan, new int[]{24}));
        assertFalse(Tailnet.peerAllowed(peer, new byte[][]{ip("10.0.0.2").getAddress()}, new int[]{24}));
    }
}
