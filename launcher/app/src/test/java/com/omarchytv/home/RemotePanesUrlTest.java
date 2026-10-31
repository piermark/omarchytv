package com.omarchytv.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemotePanesUrlTest {
    @Test
    public void httpsAsBefore() {
        assertTrue(RemotePanes.urlAllowed("https://example.com"));
        assertTrue(RemotePanes.urlAllowed("https://example.com/tv/panes.json"));
    }

    @Test
    public void httpToALiteralPrivateOrLinkLocalAddress() {
        for (String host : new String[]{"10.5.10.56", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.1.1"}) {
            assertTrue(host, RemotePanes.urlAllowed("http://" + host));
            assertTrue(host, RemotePanes.urlAllowed("http://" + host + "/"));
            assertTrue(host, RemotePanes.urlAllowed("http://" + host + ":1880"));
            assertTrue(host, RemotePanes.urlAllowed("http://" + host + ":1880/omarchytv/panes.json"));
            assertTrue(host, RemotePanes.urlAllowed("http://" + host + "/omarchytv/panes.json"));
        }
    }

    @Test
    public void httpToPublicLoopbackCgnatOrHostNamesIsRefused() {
        for (String host : new String[]{"8.8.8.8", "172.32.0.1", "11.0.0.1", "100.64.0.1", "127.0.0.1", "example.com", "nodered.local"}) {
            assertFalse(host, RemotePanes.urlAllowed("http://" + host));
            assertFalse(host, RemotePanes.urlAllowed("http://" + host + ":1880/omarchytv/panes.json"));
        }
        assertFalse(RemotePanes.urlAllowed("http://10.0.0.1@example.com/panes.json"));
    }

    @Test
    public void otherSchemesEmptyNullAndTooLongAreRefused() {
        assertFalse(RemotePanes.urlAllowed("ftp://10.5.10.56/panes.json"));
        assertFalse(RemotePanes.urlAllowed(""));
        assertFalse(RemotePanes.urlAllowed(null));
        StringBuilder b = new StringBuilder("http://10.5.10.56/");
        while (b.length() <= 512) b.append('a');
        assertFalse(RemotePanes.urlAllowed(b.toString()));
        b.replace(0, "http://10.5.10.56/".length(), "https://example.com/");
        assertFalse(RemotePanes.urlAllowed(b.toString()));
    }

    @Test
    public void lanHostMatchesTheWebTileRule() {
        assertTrue(LanHttpDataSource.lanHost("192.168.1.10"));
        assertTrue(LanHttpDataSource.lanHost("[fd00::1]"));
        assertTrue(LanHttpDataSource.lanHost("[fe80::1]"));
        assertFalse(LanHttpDataSource.lanHost("[2001:4860:4860::8888]"));
        assertFalse(LanHttpDataSource.lanHost("nodered.local"));
        assertFalse(LanHttpDataSource.lanHost(""));
        assertFalse(LanHttpDataSource.lanHost(null));
    }
}
