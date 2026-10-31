package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** v2.14 Sony BRAVIA crash: the :web process needs its own WebView data directory; streams skip the WebView. */
public class WebProcessTest {
    private static final String PKG = "com.omarchytv.home";

    @Test
    public void everyExtraProcessGetsItsOwnWebViewDir() {
        assertEquals("web", OmarchyApp.suffixFor(PKG, PKG + ":web"));
        assertEquals("saver", OmarchyApp.suffixFor(PKG, PKG + ":saver"));
        assertNull(OmarchyApp.suffixFor(PKG, PKG));            // main process keeps app_webview/
        assertNull(OmarchyApp.suffixFor(PKG, null));
        assertNull(OmarchyApp.suffixFor(PKG, "other.app:web"));
        assertNull(OmarchyApp.suffixFor(PKG, PKG + ":"));
    }

    @Test
    public void manifestRegistersTheApplication() throws Exception {
        String m = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        assertTrue(m.contains("android:name=\".OmarchyApp\""));
    }

    @Test
    public void directStreamsPlayNativelyWebPagesStayWebApps() {
        assertTrue(StreamUrls.isDirectStream("https://example.com/live/euronews/playlist.m3u8?token=1"));
        assertTrue(StreamUrls.isDirectStream("HTTP://lan.box/movie.MP4"));
        assertFalse(StreamUrls.isDirectStream("https://www.euronews.com/live"));
        assertFalse(StreamUrls.isDirectStream("https://plus.nasa.gov"));
        assertFalse(StreamUrls.isDirectStream("https://example.com/page?file=a.m3u8"));
        assertFalse(StreamUrls.isDirectStream("file:///sdcard/a.m3u8"));
        assertFalse(StreamUrls.isDirectStream(null));
    }
}
