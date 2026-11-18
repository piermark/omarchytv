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
}
