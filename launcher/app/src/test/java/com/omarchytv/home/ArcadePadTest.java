package com.omarchytv.home;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** v2.15 phones as gamepads (ArcadePad): the code, two players at most, only the arcade's keys. */
public class ArcadePadTest {
    private final List<String> keys = new ArrayList<>();
    private final List<List<Integer>> joins = new ArrayList<>();
    private final ArcadePad.Sink sink = new ArcadePad.Sink() {
        @Override
        public void key(String k, int player) {
            synchronized (keys) {
                keys.add(player + ":" + k);
            }
        }

        @Override
        public void players(List<Integer> on) {
            joins.add(on);
        }
    };

    @Test
    public void codeIsEightUnambiguousChars() {
        String c = ArcadePad.randomCode();
        assertTrue(c, c.matches("[a-hj-km-np-z2-9]{8}"));
        assertNotEquals(c, ArcadePad.randomCode());
    }

    @Test
    public void twoPhonesPlayTheThirdWaits_onlyWithTheCode_onlyArcadeKeys() {
        ArcadePad p = new ArcadePad(null, sink, "abcdefgh");
        assertEquals(403, p.answer("/hello?c=wrong&i=a").code);
        assertEquals(403, p.answer("/k?c=&i=a&k=up").code);
        assertEquals(403, p.answer("/p/wrongcode").code);   // a page path without the code: refused like the rest
        assertEquals("{\"p\":1}", p.answer("/hello?c=abcdefgh&i=phoneA").text());
        assertEquals("{\"p\":2}", p.answer("/hello?c=abcdefgh&i=phoneB").text());
        assertEquals("{\"p\":0}", p.answer("/hello?c=abcdefgh&i=phoneC").text());
        assertEquals("{\"p\":1}", p.answer("/hello?c=abcdefgh&i=phoneA").text());   // the same phone keeps its place
        assertEquals(204, p.answer("/k?c=abcdefgh&i=phoneB&k=left").code);
        assertEquals(204, p.answer("/k?c=abcdefgh&i=phoneA&k=ok").code);
        assertEquals(409, p.answer("/k?c=abcdefgh&i=phoneC&k=ok").code);
        assertEquals(400, p.answer("/k?c=abcdefgh&i=phoneA&k=home").code);
        assertEquals(400, p.answer("/k?c=abcdefgh&i=phoneA&k=up');alert(1);('").code);
        assertEquals(java.util.Arrays.asList("2:left", "1:ok"), keys);
        assertEquals(java.util.Arrays.asList(1, 2), p.players());
        assertEquals(java.util.Arrays.asList(1, 2), joins.get(joins.size() - 1));
    }

    @Test
    public void servesOverHttpAndStops() throws Exception {
        ArcadePad p = new ArcadePad(null, sink, "abcdefgh");
        String url = p.start("127.0.0.1");
        assertNotNull(url);
        assertTrue(url, url.matches("http://127\\.0\\.0\\.1:\\d+/p/abcdefgh"));
        String base = url.substring(0, url.indexOf("/p/"));
        assertEquals("{\"p\":1}", get(base + "/hello?c=abcdefgh&i=x"));
        assertEquals(204, code(base + "/k?c=abcdefgh&i=x&k=up"));
        assertEquals(403, code(base + "/k?c=nope&i=x&k=up"));
        synchronized (keys) {
            assertEquals(java.util.Collections.singletonList("1:up"), keys);
        }
        p.stop();
        try {
            code(base + "/hello?c=abcdefgh&i=x");
            fail("still answering after stop");
        } catch (java.io.IOException expected) {
        }
        assertNull(new ArcadePad(null, sink).start(""));
    }

    private static int code(String u) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static String get(String u) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
