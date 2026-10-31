package com.omarchytv.home;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;

public class GrokbotDirectTest {
    @Test
    public void topicIsOtvPlus32LowercaseAlnum() {
        String t = GrokbotDirect.newTopic(new Random(1));
        assertTrue(t, t.matches("otv-[a-z0-9]{32}"));
        assertTrue(GrokbotDirect.validTopic(t));
        assertFalse(GrokbotDirect.validTopic("otv-ABC"));
        assertNotEquals(t, GrokbotDirect.newTopic());
    }

    @Test
    public void headersMatchGrokbotAsk() {
        Map<String, String> h = GrokbotDirect.headers("crsr_placeholder123");
        assertEquals("Bearer crsr_placeholder123", h.get("Authorization"));
        assertEquals("crsr_placeholder123", h.get("X-Automation-Key"));
        assertEquals("application/json", h.get("Content-Type"));
    }

    @Test
    public void bodyCarriesQuestionTopicIdAndApps() throws Exception {
        JSONObject b = new JSONObject(GrokbotDirect.body("play a video", "it", "otv-x", "id1", Arrays.asList("Netflix", "YouTube"), "ctx"));
        assertEquals("play a video", b.getString("question"));
        assertEquals("it", b.getString("lang"));
        assertEquals("otv-x", b.getString("reply_topic"));
        assertEquals("id1", b.getString("id"));
        assertEquals(2, b.getJSONArray("apps").length());
        assertEquals("ctx", b.getString("context"));
    }

    @Test
    public void keyIsMaskedAndValidated() {
        String m = GrokbotDirect.mask("crsr_abcdefghijklmnop1234");
        assertFalse(m.contains("abcdefgh"));
        assertTrue(m.startsWith("crsr_"));
        assertEquals("not set", GrokbotDirect.mask(""));
        assertFalse(GrokbotDirect.validKey("has space"));
        assertTrue(GrokbotDirect.validUrl("https://example.com/hook/PLACEHOLDER"));
        assertFalse(GrokbotDirect.validUrl("http://example.com/x"));
    }

    @Test
    public void findMatchesOnIdOnly() {
        String nd = "{\"event\":\"open\"}\n"
                + "{\"event\":\"message\",\"message\":\"{\\\"id\\\":\\\"other\\\",\\\"text\\\":\\\"no\\\"}\"}\n"
                + "{\"event\":\"message\",\"message\":\"not json\"}\n"
                + "{\"event\":\"message\",\"message\":\"{\\\"id\\\":\\\"me\\\",\\\"text\\\":\\\"Ciao\\\",\\\"action\\\":{\\\"type\\\":\\\"open_app\\\",\\\"name\\\":\\\"Netflix\\\"}}\"}\n";
        GrokbotDirect.Reply r = GrokbotDirect.find(nd, "me");
        assertNotNull(r);
        assertEquals("Ciao", r.text);
        assertEquals("open_app", r.action.type);
        assertEquals("Netflix", r.action.name);
        assertNull(GrokbotDirect.find(nd, "missing"));
    }

    @Test
    public void playVideoRoutesYoutubeOnlyForYoutubeContent() throws Exception {
        GrokbotDirect.Action yt = GrokbotDirect.action(new JSONObject("{\"type\":\"play_video\",\"url\":\"https://youtu.be/dQw4w9WgXcQ\"}"));
        assertTrue(yt.youtube());
        GrokbotDirect.Action mp4 = GrokbotDirect.action(new JSONObject("{\"type\":\"play_video\",\"url\":\"https://example.com/a.mp4\"}"));
        assertFalse(mp4.youtube());
        assertNull(GrokbotDirect.action(new JSONObject("{\"type\":\"play_video\",\"url\":\"javascript:alert(1)\"}")));
        assertNull(GrokbotDirect.action(new JSONObject("{\"type\":\"rm_rf\"}")));
        assertNull(GrokbotDirect.action(new JSONObject("{\"type\":\"none\"}")));
        assertNull(GrokbotDirect.action("play_video"));
        GrokbotDirect.Action s = GrokbotDirect.action(new JSONObject("{\"type\":\"search_youtube\",\"query\":\"dhh rails\"}"));
        assertEquals("https://www.youtube.com/results?search_query=dhh+rails", GrokbotDirect.youtubeSearchUrl(s.query));
    }

    @Test
    public void pollUrlUsesNtfyJsonPoll() {
        assertEquals("https://ntfy.sh/otv-a/json?poll=1&since=100", GrokbotDirect.pollUrl("otv-a", 100));
        assertEquals(2000, GrokbotDirect.POLL_MS);
        assertEquals(90000, GrokbotDirect.TIMEOUT_MS);
    }
}
