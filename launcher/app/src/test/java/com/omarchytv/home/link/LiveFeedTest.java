package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** v2.22 AI LIVE: the channel's memory of what each agent does. */
public class LiveFeedTest {
    @Test
    public void agentsStatusPreviewAndQuestions() throws Exception {
        LiveFeed f = LiveFeed.forTest();
        f.now("claude", new JSONObject().put("text", "running tests\nnow").put("progress", 140)
                .put("preview", new JSONObject().put("kind", "diff").put("text", "+ ok\n- bad")), 1000);
        f.waiting("claude", "a1", "push tv-2.22?", 2000);
        JSONObject s = f.snapshot(3000, 60);
        JSONObject a = s.getJSONArray("agents").getJSONObject(0);
        assertEquals("running tests now", a.getString("text"));
        assertEquals(100, a.getInt("progress"));
        assertEquals("a1", a.getJSONObject("waiting").getString("id"));
        assertEquals("diff", f.previewOf("claude", 3000).getString("kind"));
        assertNull(f.previewOf("claude", 2000 + 600_001));
        f.answered("a1", "Deny", 4000);
        JSONObject b = f.snapshot(5000, 60).getJSONArray("agents").getJSONObject(0);
        assertFalse(b.has("waiting"));
        JSONArray ev = f.snapshot(5000, 60).getJSONArray("events");
        assertEquals("answer", ev.getJSONObject(ev.length() - 1).getString("kind"));
    }

    @Test
    public void thePcsAgentMonitorGoesOnAirByItself() throws Exception {
        LiveFeed f = LiveFeed.forTest();
        f.fromMonitor(new JSONObject().put("agents", new JSONArray()
                .put(new JSONObject().put("agentName", "Claude").put("project", "omarchytv").put("status", "running").put("step", "Bash · npm test"))
                .put(new JSONObject().put("agentName", "Codex").put("status", "idle").put("step", "x"))), 1000);
        JSONArray a = f.snapshot(1000, 10).getJSONArray("agents");
        assertEquals(1, a.length());
        assertEquals("Claude · omarchytv", a.getJSONObject(0).getString("agent"));
        assertEquals("Bash · npm test", a.getJSONObject(0).getString("text"));
    }

    @Test
    public void previewsAreChecked() throws Exception {
        assertNull(LiveFeed.preview(new JSONObject().put("kind", "image").put("url", "http://8.8.8.8/x.png")));
        assertNull(LiveFeed.preview(new JSONObject().put("kind", "image").put("url", "javascript:alert(1)")));
        assertTrue(LiveFeed.preview(new JSONObject().put("kind", "image").put("url", "https://a.example/s.png")).has("url"));
        assertTrue(LiveFeed.preview(new JSONObject().put("kind", "image").put("url", "http://192.168.1.4:8080/s.png")).has("url"));
        assertNull(LiveFeed.preview(new JSONObject().put("kind", "chart").put("points", new JSONArray().put("x"))));
        assertNull(LiveFeed.preview(new JSONObject().put("kind", "html").put("text", "<b>")));
        assertNull(LiveFeed.preview(new JSONObject().put("kind", "text").put("text", "")));
    }

    @Test
    public void theChannelIsBounded() throws Exception {
        LiveFeed f = LiveFeed.forTest();
        for (int i = 0; i < LiveFeed.MAX_EVENTS + 50; i++) f.event("a" + (i % 40), "note", "n" + i, i);
        JSONObject s = f.snapshot(LiveFeed.MAX_EVENTS + 50, 1000);
        assertEquals(LiveFeed.MAX_EVENTS, s.getJSONArray("events").length());
        assertTrue(s.getJSONArray("agents").length() <= LiveFeed.MAX_AGENTS);
        assertEquals(0, f.snapshot(LiveFeed.AGENT_TTL_MS * 2, 10).getJSONArray("agents").length());   // idle agents leave
    }
}
