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

    @Test
    public void chromeFits720And1080AtTvDensities() {
        // tvdpi 213 and xhdpi 320, the densities Android TV actually uses
        float tvdpi = 213f / 160f, xhdpi = 2f;
        int[] screens = {1280, 1920};
        float[] densities = {tvdpi, xhdpi, 1f};
        for (int screen : screens) {
            for (float d : densities) {
                int col = Office.Chrome.column(screen, d);
                int cap = Math.round(0.90f * screen);
                assertTrue("column fits " + screen + "@" + d, col <= cap && col <= screen);
                assertTrue("column at least 50% or 560dp, capped", col >= Math.min(cap, Math.max(Math.round(560 * d), Math.round(0.50f * screen))) || col == cap);
                // the start title stays on one line at 34sp in a monospace-ish 0.62em advance
                assertTrue("GrokBot Office is one line in the column " + screen + "@" + d,
                        textPx("GrokBot Office", 34, d) <= col);
                // input row: longest Enter button + mic + a provider chip, leftover is the pill
                int enter = textPx("Entrer au bureau ›", 16, d) + dp(32, d);   // padding
                int mic = textPx("🎤", 16, d) + dp(32, d) + dp(10, d);
                int prov = textPx("Grok Bot", 16, d) + dp(32, d) + dp(10, d);
                int leftover = col - enter - mic - prov;
                assertTrue("ask pill still usable after buttons " + screen + "@" + d + " leftover=" + leftover, leftover >= dp(200, d) || leftover >= screen / 6);
                assertEquals(col, Office.Chrome.hint(screen, d));
                assertEquals(col, Office.Chrome.reply(screen, d));
                int hire = Office.Chrome.hire(screen, d);
                assertTrue("hire card on screen", hire <= screen - dp(80, d));
                assertTrue("hire title fits or wraps in 2 lines",
                        textPx("Neuen Bot einstellen", 20, d) <= hire * 2);
                assertTrue("hire buttons Abbrechen + Einstellen fit one row",
                        textPx("Abbrechen", 16, d) + textPx("Einstellen", 16, d) + dp(80, d) <= hire);
                int usage = Office.Chrome.usage(screen, d);
                assertTrue("Wöchentliche Nutzung fits the usage card",
                        textPx("Wöchentliche Nutzung", 13, d) <= usage || usage >= dp(200, d));
                assertTrue("keys have a cap inside the panel", Office.Chrome.keys(screen) < screen);
                assertTrue("header cap leaves the usage side free", Office.Chrome.header(screen) < screen);
            }
        }
        // the Sony 1080p case that clipped when the column was wrap_content (~400px)
        int sony = Office.Chrome.column(1920, 1f);
        assertEquals(960, sony);
        assertTrue("title is one line on a 1080p mdpi Sony", textPx("GrokBot Office", 34, 1f) <= sony);
    }

    @Test
    public void usageCardSitsAboveTheAskBarAtEveryTvDensity() {
        float tvdpi = 213f / 160f, xhdpi = 2f;
        for (int screen : new int[] {1280, 1920}) {
            int screenH = Math.round(screen * 9f / 16f);
            for (float d : new float[] {tvdpi, xhdpi, 1f}) {
                int barBottom = dp(Office.Chrome.BAR_BOTTOM_DP, d);
                int barTop = barBottom + dp(Office.Chrome.BAR_HEIGHT_DP, d);
                int gap = dp(Office.Chrome.GAP_DP, d);
                int usageBottom = Office.Chrome.usageBottom(d);
                int usageTop = usageBottom + dp(Office.Chrome.USAGE_RESERVE_DP, d);
                int usageLeft = dp(Office.Chrome.USAGE_LEFT_DP, d);
                int usageRight = usageLeft + Office.Chrome.usage(screen, d);
                int col = Office.Chrome.column(screen, d);
                int colLeft = (screen - col) / 2;
                int colRight = colLeft + col;
                int replyBottom = Office.Chrome.replyBottom(screen, d);
                int replyTop = replyBottom + dp(100, d);   // 4 lines of 15sp + padding
                String tag = screen + "x" + screenH + "@" + d;
                assertTrue("usage is above the ask bar " + tag, usageBottom >= barTop + gap);
                assertTrue("usage still on the 720p/1080p panel " + tag, usageTop < screenH);
                assertTrue("reply is above the ask bar " + tag, replyBottom >= barTop + gap);
                boolean hit = usageRight + gap > colLeft && usageLeft < colRight;
                if (hit) {
                    assertTrue("usage and the ask/reply column stack " + tag, replyBottom >= usageTop + gap);
                } else {
                    assertEquals("side by side, reply sits just above the bar " + tag,
                            Office.Chrome.usageBottom(d), Office.Chrome.replyBottom(screen, d));
                }
                assertTrue("reply stays on the panel " + tag, replyTop <= screenH);
                // long de/fr usage copy still fits the card width (wrapping is allowed)
                assertTrue("Wöchentliche Nutzung · zurück am Mon 09:00 can wrap in the card " + tag,
                        textPx("Wöchentliche Nutzung", 13, d) <= Office.Chrome.usage(screen, d)
                                || Office.Chrome.usage(screen, d) >= dp(200, d));
            }
        }
        // 1080p mdpi Sony: the card sits beside the 50% column, just above the bar
        assertFalse(Office.Chrome.usageCollidesWithColumn(1920, 1f));
        assertEquals(104, Office.Chrome.usageBottomDp());
        assertEquals(104, Office.Chrome.usageBottom(1f));
        assertEquals(104, Office.Chrome.replyBottom(1920, 1f));
        // 720p xhdpi: the 560dp column fills the panel, so the card stacks above the bar
        assertTrue(Office.Chrome.usageCollidesWithColumn(1280, 2f));
        assertEquals(432, Office.Chrome.replyBottom(1280, 2f));   // 216dp at xhdpi
    }

    private static int dp(int v, float density) {
        return Math.round(v * density);
    }

    private static int textPx(String s, int sp, float density) {
        return Math.round(s.length() * sp * density * 0.62f);
    }
}
