package com.omarchytv.home;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

/** GrokBot Office: the same rules as webos/tests/office.test.js. */
public class OfficeTest {
    @Test
    public void botsAndUsageComeOnlyFromThePushedJson() {
        Office.Data d = Office.parse("{\"title\":\"My office\",\"bots\":[{\"name\":\"Trimmer\",\"role\":\"Cuts token use\",\"color\":\"#FF9E64\",\"status\":\"working\"},"
                + "{\"name\":\"  Scout  \",\"color\":\"cyan\"},{\"name\":\"\"},null,{\"role\":\"no name\"},{\"name\":\"Trimmer\",\"color\":\"red\"},"
                + "{\"name\":\"<b>X</b>\",\"color\":\"javascript:1\",\"status\":\"hacking\"}],\"usage\":{\"weekly_pct\":46.25,\"label\":\"Weekly usage\",\"resets\":\"Mon 09:00\"}}");
        assertEquals("My office", d.title);
        assertEquals(3, d.bots.size());
        assertEquals("Trimmer", d.bots.get(0).name);
        assertEquals("#ff9e64", d.bots.get(0).color);
        assertEquals("working", d.bots.get(0).status);
        assertEquals("Scout", d.bots.get(1).name);
        assertEquals("#7dcfff", d.bots.get(1).color);
        assertEquals("bX/b", d.bots.get(2).name);
        assertTrue(d.bots.get(2).color.matches("#[0-9a-f]{6}"));
        assertEquals("idle", d.bots.get(2).status);
        assertEquals(46.3, d.usage.pct, 1e-9);
        assertEquals("Mon 09:00", d.usage.resets);
        assertEquals(Office.colorOf("", "Pixel"), Office.colorOf(null, "Pixel"));
    }

    @Test
    public void sameColourAsTheWebAppForTheSameName() {
        // office.js: hash("Pixel") % 11 with the same 32-bit arithmetic
        int h = 0;
        for (char c : "Pixel".toCharArray()) h = h * 31 + c;
        assertEquals(Office.PALETTE[(int) (Math.abs((long) h) % 11)], Office.colorOf(null, "Pixel"));
    }

    @Test
    public void noUsageNumbersNoUsageCard() {
        assertNull(Office.parse("{\"bots\":[]}").usage);
        assertNull(Office.parse("{\"usage\":{\"label\":\"Weekly usage\"}}").usage);
        assertNull(Office.parse("{\"usage\":{\"weekly_pct\":\"lots\"}}").usage);
        assertNull(Office.parse("{\"usage\":{\"weekly_pct\":140}}").usage);
        assertEquals(12, Office.parse("{\"usage\":{\"pct\":\"12%\"}}").usage.pct, 1e-9);
        assertTrue(Office.parse("not json").bots.isEmpty());
        StringBuilder b = new StringBuilder("{\"bots\":[");
        for (int i = 0; i < 40; i++) b.append(i == 0 ? "" : ",").append("{\"name\":\"b").append(i).append("\"}");
        assertEquals(Office.MAX_BOTS, Office.parse(b.append("]}").toString()).bots.size());
    }

    @Test
    public void replyPayloadReplacesAddsOnceAndUpdatesUsage() throws Exception {
        Office.Data d = Office.parse("{\"bots\":[{\"name\":\"Scout\"}]}");
        d = Office.applyReply(d, new JSONObject("{\"hired\":{\"name\":\"Trimmer\",\"color\":\"orange\"}}"));
        assertEquals(2, d.bots.size());
        assertEquals("#ff9e64", d.bots.get(1).color);
        d = Office.applyReply(d, new JSONObject("{\"hired\":{\"name\":\"Trimmer\"}}"));
        assertEquals(2, d.bots.size());
        d = Office.applyReply(d, new JSONObject("{\"usage\":{\"weekly_pct\":50}}"));
        assertEquals(50, d.usage.pct, 1e-9);
        d = Office.applyReply(d, new JSONObject("{\"bots\":[{\"name\":\"Solo\"}]}"));
        assertEquals(1, d.bots.size());
        assertSame(d, Office.applyReply(d, null));
        Office.Data back = Office.parse(Office.toJson(d));
        assertEquals("Solo", back.bots.get(0).name);
        assertEquals(50, back.usage.pct, 1e-9);
    }

    @Test
    public void whatTheUserAsked() {
        assertEquals("open", Office.intent("open my office").type);
        assertEquals("open", Office.intent("apri l'ufficio").type);
        Office.Asked h = Office.intent("Hire a bot called Trimmer to trim my tokens");
        assertEquals("hire", h.type);
        assertEquals("Trimmer", h.name);
        assertEquals("trim my tokens", h.role);
        Office.Asked it = Office.intent("assumi un bot chiamato Ragioniere per controllare le spese");
        assertEquals("Ragioniere", it.name);
        assertEquals("controllare le spese", it.role);
        Office.Asked noName = Office.intent("hire someone who watches CI");
        assertEquals("", noName.name);
        assertEquals("someone who watches CI", noName.role);
        assertEquals("ask", Office.intent("what's the weather tomorrow?").type);
        assertEquals("ask", Office.intent("how is my office doing?").type);
        assertEquals("none", Office.intent("   ").type);
    }

    @Test
    public void requestsRideOnGrokBotDirettosOwnBody() throws Exception {
        Office.Data d = Office.parse("{\"bots\":[{\"name\":\"Scout\"}]}");
        Office.Request h = Office.hireRequest("Trimmer", "trim my tokens", "", d);
        assertTrue(h.question.startsWith("Hire a new bot for my GrokBot Office named Trimmer: trim my tokens."));
        assertEquals("hire", h.office.getString("action"));
        assertEquals("trim my tokens", h.office.getString("description"));
        assertEquals("Scout", h.office.getJSONArray("bots").getString(0));
        JSONObject b = new JSONObject(Office.requestBody(GrokbotDirect.body(h.question, "en", "otv-x", "id1", Collections.emptyList(), "ctx"), h.office));
        assertEquals("otv-x", b.getString("reply_topic"));
        assertEquals("Trimmer", b.getJSONObject("office").getString("name"));
        assertEquals("ask", Office.askRequest("hi", d).office.getString("action"));
    }

    @Test
    public void officePayloadFromOurReplyOnlyAndPendingConfirms() throws Exception {
        String nd = msg("other", "{\"hired\":{\"name\":\"X\"}}") + "\ngarbage\n" + msg("me", "{\"hired\":{\"name\":\"Trimmer\"}}");
        assertEquals("Trimmer", Office.findOffice(nd, "me").getJSONObject("hired").getString("name"));
        assertNull(Office.findOffice(nd, "nobody"));
        Office.Data d = Office.parse("{\"bots\":[{\"name\":\"Scout\"},{\"name\":\"trimmer\"}]}");
        assertTrue(Office.confirmed(d, new Office.Pending("Trimmer", "", 0, 2)));
        assertFalse(Office.confirmed(d, new Office.Pending("Pixel", "", 0, 0)));
        assertTrue(Office.confirmed(d, new Office.Pending("", "", 0, 1)));
        assertFalse(Office.confirmed(d, new Office.Pending("", "", 0, 2)));
        assertFalse(Office.confirmed(d, null));
        long now = 10_000_000_000L;
        Office.Pending p = Office.pending(Office.pendingJson(new Office.Pending("Trimmer", "trim", now - 1000, 1)).toString(), now);
        assertEquals("Trimmer", p.name);
        assertNull(Office.pending(Office.pendingJson(new Office.Pending("Old", "", now - Office.PENDING_TTL_MS - 1, 1)).toString(), now));
    }

    private static String msg(String id, String office) throws Exception {
        return new JSONObject().put("event", "message").put("message", new JSONObject().put("id", id).put("text", "ok").put("office", new JSONObject(office)).toString()).toString();
    }

    @Test
    public void desksAndFrameCap() {
        assertEquals(6, Office.layout(0).length);
        assertEquals(7, Office.layout(7).length);
        assertEquals(16, Office.layout(40).length);
        for (double[] p : Office.layout(16)) assertTrue(p[0] > 0.5 && p[0] < 9.5 && p[1] > 0.5 && p[1] < 9.5);
        assertFalse(Office.due(1000, 1010));
        assertTrue(Office.due(1000, 1034));
        assertTrue(Office.FRAME_MS >= 33);
    }

    @Test
    public void fileRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("office").toFile();
        File f = new File(dir, Office.FILE_NAME);
        assertTrue(Office.load(f).bots.isEmpty());
        Office.save(f, Office.parse("{\"bots\":[{\"name\":\"Herald\",\"status\":\"working\"}],\"usage\":{\"weekly_pct\":46.3}}"));
        Office.Data d = Office.load(f);
        assertEquals("Herald", d.bots.get(0).name);
        assertEquals(46.3, d.usage.pct, 1e-9);
    }

    @Test
    public void providerIsGrokByDefaultAndAShortWord() throws Exception {
        Office.Data d = Office.parse("{\"bots\":[{\"name\":\"A\"},{\"name\":\"B\",\"provider\":\"Claude\"},{\"name\":\"C\",\"provider\":\"ChatGPT\"},"
                + "{\"name\":\"D\",\"provider\":\"<b>mistral large 2 with many words</b>\"},{\"name\":\"E\",\"provider\":\"  \"},{\"name\":\"F\",\"provider\":42},"
                + "{\"name\":\"G\",\"assistant\":\"Office-G!\"}]}");
        assertEquals("grok", d.bots.get(0).provider);
        assertEquals("claude", d.bots.get(1).provider);
        assertEquals("chatgpt", d.bots.get(2).provider);
        assertTrue(d.bots.get(3).provider.matches("[a-z0-9._-]{1,16}"));
        assertEquals("grok", d.bots.get(4).provider);
        assertEquals("42", d.bots.get(5).provider);
        assertEquals("office-g", d.bots.get(6).assistant);
        assertEquals("", d.bots.get(0).assistant);
        assertEquals("claude", Office.toJson(d).getJSONArray("bots").getJSONObject(1).getString("provider"));
        assertFalse(Office.toJson(d).getJSONArray("bots").getJSONObject(0).has("assistant"));
        assertEquals("chatgpt", Office.applyReply(d, new JSONObject("{\"hired\":{\"name\":\"H\",\"provider\":\"chatgpt\"}}")).bots.get(7).provider);
        assertEquals("#ff9e64", Office.providerColor("claude"));
        assertEquals("#a9b1d6", Office.providerColor("unknown-ai"));
        assertEquals("claude", Office.family("", "claude-code"));
        assertEquals("chatgpt", Office.family("", "codex"));
        assertEquals("claude", Office.family("claude", "office-trimmer"));
        assertEquals("ollama", Office.family("", "ollama"));
    }

    @Test
    public void spendPerProviderOnlyWithRealNumbers() throws Exception {
        Office.Data d = Office.parse("{\"bots\":[],\"providers_usage\":[{\"provider\":\"claude\",\"spent\":12.345,\"unit\":\"$\",\"budget\":50,\"label\":\"Claude API\"},"
                + "{\"provider\":\"chatgpt\",\"spent\":3.5},{\"provider\":\"x\",\"spent\":\"lots\"},{\"provider\":\"y\",\"spent\":-1},{\"provider\":\"z\",\"spent\":true},null]}");
        assertEquals(2, d.spend.size());
        assertEquals(12.35, d.spend.get(0).spent, 1e-9);
        assertEquals(24.7, d.spend.get(0).pct, 1e-9);
        assertEquals(-1, d.spend.get(1).pct, 1e-9);   // no budget, no percentage
        assertTrue(Office.parse("{\"bots\":[]}").spend.isEmpty());
        assertEquals(12.35, Office.toJson(d).getJSONArray("providers_usage").getJSONObject(0).getDouble("spent"), 1e-9);
        assertEquals(2, Office.parse(Office.toJson(d)).spend.size());   // round trip through the file
    }

    @Test
    public void hireNamesTheProviderFromTheUsersWords() {
        Office.Asked a = Office.intent("hire a claude bot called Trimmer to trim my tokens");
        assertEquals("claude", a.provider);
        assertEquals("Trimmer", a.name);
        assertEquals("trim my tokens", a.role);
        assertEquals("chatgpt", Office.intent("assumi un bot chiamato Scout su ChatGPT per cercare offerte").provider);
        assertEquals("grok", Office.intent("hire a bot called Pixel to draw").provider);
    }
}
