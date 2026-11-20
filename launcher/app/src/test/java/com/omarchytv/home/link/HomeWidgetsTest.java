package com.omarchytv.home.link;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** v2.22 #157: the widget model, validation and placement, without a TV. */
public class HomeWidgetsTest {
    private static JSONObject w(String id, String type) throws Exception {
        return new JSONObject().put("id", id).put("type", type);
    }

    @Test
    public void createsInTheFirstFreeSlotAndNeverOverlaps() throws Exception {
        JSONArray all = new JSONArray();
        JSONObject a = HomeWidgets.create(all, w("nas", "lines").put("w", 6).put("h", 2), "pc", 1);
        assertEquals(0, a.getInt("x"));
        JSONObject b = HomeWidgets.create(all, w("temp", "stat").put("data", new JSONObject().put("value", "21.4").put("unit", "°C")), "pc", 1);
        assertEquals(6, b.getInt("x"));   // right of nas, same row
        assertEquals(0, b.getInt("y"));
        assertEquals(2, b.getInt("w"));   // stat default 2 x 1
        try {
            HomeWidgets.create(all, w("x", "text").put("x", 1).put("y", 1), "pc", 1);
            fail();
        } catch (HomeWidgets.Bad e) {
            assertEquals(409, e.status);
            assertEquals("cell_taken", e.code);
            assertTrue(e.free.length() > 0);
        }
        try {
            HomeWidgets.create(all, w("y", "text").put("x", 11).put("w", 3), "pc", 1);
            fail();
        } catch (HomeWidgets.Bad e) {
            assertEquals("cell_taken", e.code);   // out of the grid
        }
        try {
            HomeWidgets.create(all, w("nas", "text"), "pc", 1);
            fail();
        } catch (HomeWidgets.Bad e) {
            assertEquals("exists", e.code);
        }
    }

    @Test
    public void pushMovesTheOthersAside() throws Exception {
        JSONArray all = new JSONArray();
        HomeWidgets.create(all, w("a", "text").put("x", 0).put("y", 0).put("w", 4).put("h", 2), "pc", 1);
        HomeWidgets.create(all, w("big", "text").put("x", 0).put("y", 0).put("w", 12).put("h", 6).put("page", 2), "pc", 1);
        JSONObject b = HomeWidgets.create(all, w("b", "text").put("x", 2).put("y", 1).put("w", 4).put("h", 2).put("push", true), "pc", 1);
        assertEquals(2, b.getInt("x"));
        JSONObject a = all.getJSONObject(HomeWidgets.indexOf(all, "a"));
        assertFalse(HomeWidgets.overlap(a.getInt("x"), a.getInt("y"), 4, 2, 2, 1, 4, 2));
        assertEquals(1, a.getInt("page"));
        // nothing overlaps anywhere
        for (int i = 0; i < all.length(); i++) for (int k = i + 1; k < all.length(); k++) {
            JSONObject p = all.getJSONObject(i), q = all.getJSONObject(k);
            if (p.getInt("page") != q.getInt("page")) continue;
            assertFalse(HomeWidgets.overlap(p.getInt("x"), p.getInt("y"), p.getInt("w"), p.getInt("h"), q.getInt("x"), q.getInt("y"), q.getInt("w"), q.getInt("h")));
        }
    }

    @Test
    public void pushSpillsToTheNextPageWhenFull() throws Exception {
        JSONArray all = new JSONArray();
        HomeWidgets.create(all, w("full", "text").put("x", 0).put("y", 0).put("w", 12).put("h", 6), "pc", 1);
        HomeWidgets.create(all, w("new", "text").put("x", 0).put("y", 0).put("w", 12).put("h", 6).put("push", true), "pc", 1);
        JSONObject full = all.getJSONObject(HomeWidgets.indexOf(all, "full"));
        assertEquals(2, full.getInt("page"));
    }

    @Test
    public void updateIsContentOnlyAndMoveIsShapeOnly() throws Exception {
        JSONArray all = new JSONArray();
        HomeWidgets.create(all, w("t", "stat").put("data", new JSONObject().put("value", "1")), "pc", 1);
        HomeWidgets.update(all, "t", new JSONObject().put("data", new JSONObject().put("value", "2").put("trend", "up")).put("x", 9), 5);
        JSONObject t = all.getJSONObject(0);
        assertEquals("2", t.getJSONObject("data").getString("value"));
        assertEquals(0, t.getInt("x"));   // update never moves
        HomeWidgets.move(all, "t", new JSONObject().put("x", 10).put("y", 5), 6);
        assertEquals(10, t.getInt("x"));
        assertEquals(5, t.getInt("y"));
        HomeWidgets.move(all, "t", new JSONObject().put("page", 3), 7);
        assertEquals(3, t.getInt("page"));
        assertEquals(0, t.getInt("x"));   // first free slot on the new page
        assertTrue(HomeWidgets.delete(all, "t"));
        assertFalse(HomeWidgets.delete(all, "t"));
    }

    @Test
    public void dataIsCheckedPerTypeAndKeptAsText() throws Exception {
        JSONObject d = HomeWidgets.cleanData("lines", new JSONObject().put("lines", new JSONArray()
                .put(new JSONObject().put("label", "Disk<b>").put("value", "71%").put("tone", "evil"))));
        assertEquals("Disk<b>", d.getJSONArray("lines").getJSONObject(0).getString("label"));   // text, never HTML
        assertFalse(d.getJSONArray("lines").getJSONObject(0).has("tone"));
        assertEquals("a\nb", HomeWidgets.cleanData("text", new JSONObject().put("text", "a\r\nb\u0007")).getString("text"));
        String[][] rejected = {{"image", "{\"url\":\"http://evil.example/x.png\"}"}, {"page", "{\"url\":\"https://example.com\"}"},
                {"chart", "{\"points\":[1,\"x\"]}"}, {"chart", "{\"points\":[]}"}, {"shortcuts", "{\"items\":[{\"id\":\"a\",\"action\":{\"type\":\"rm\"}}]}"},
                {"homeassistant.scenes", "{\"items\":[\"rm -rf\"]}"}, {"qr", "{\"url\":\"javascript:alert(1)\"}"}, {"nope", "{}"}};
        for (String[] r : rejected) {
            try {
                HomeWidgets.cleanData(r[0], new JSONObject(r[1]));
                fail(r[0] + " " + r[1]);
            } catch (HomeWidgets.Bad expected) {
                // ok
            }
        }
        assertEquals("https://example.com/a.png", HomeWidgets.cleanData("image", new JSONObject().put("url", "https://example.com/a.png")).getString("url"));
        assertEquals("http://192.168.1.10:8123/lovelace", HomeWidgets.cleanData("page", new JSONObject().put("url", "http://192.168.1.10:8123/lovelace")).getString("url"));
        assertEquals("all", HomeWidgets.cleanData("homeassistant.scenes", new JSONObject().put("items", "all")).getString("items"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 17_000; i++) big.append('x');
        try {
            HomeWidgets.cleanData("text", new JSONObject().put("text", big.toString()));
            fail();
        } catch (HomeWidgets.Bad e) {
            assertEquals("too_big", e.code);
        }
    }

    @Test
    public void panesBecomeReadOnlyLinesWidgetsInTheFreeCells() throws Exception {
        JSONArray all = new JSONArray();
        HomeWidgets.create(all, w("left", "text").put("x", 0).put("y", 0).put("w", 6).put("h", 6), "pc", 1);
        JSONArray panes = new JSONArray().put(new JSONObject().put("id", "nas").put("title", "NAS").put("lines", new JSONArray()));
        JSONArray pw = HomeWidgets.paneWidgets(all, panes);
        assertEquals(1, pw.length());
        JSONObject p = pw.getJSONObject(0);
        assertEquals("pane:nas", p.getString("id"));
        assertEquals(6, p.getInt("x"));
        assertTrue(p.getBoolean("virtual"));
        assertEquals(0, HomeWidgets.paneWidgets(all, null).length());
    }

    @Test
    public void pagesAreTheOnesInUse() throws Exception {
        assertTrue(HomeWidgets.pages(new JSONArray()).isEmpty());
        JSONArray all = new JSONArray();
        HomeWidgets.create(all, w("a", "stat").put("page", 3), "pc", 1);
        assertEquals(java.util.Arrays.asList(1, 3), HomeWidgets.pages(all));   // page 1 always there
    }

    @Test
    public void listIsInReadingOrder() throws Exception {
        JSONArray all = new JSONArray();
        HomeWidgets.create(all, w("b", "stat").put("x", 5).put("y", 2), "pc", 1);
        HomeWidgets.create(all, w("a", "stat").put("x", 0).put("y", 2), "pc", 1);
        HomeWidgets.create(all, w("c", "stat").put("x", 9).put("y", 0), "pc", 1);
        JSONArray l = HomeWidgets.list(all, 1);
        assertEquals("c", l.getJSONObject(0).getString("id"));
        assertEquals("a", l.getJSONObject(1).getString("id"));
        assertNull(HomeWidgets.firstFree(new JSONArray().put(new JSONObject().put("id", "z").put("page", 1).put("x", 0).put("y", 0).put("w", 12).put("h", 6)), 1, 1, 1, null));
        assertArrayEquals(new int[]{0, 0}, HomeWidgets.firstFree(new JSONArray(), 4, 12, 6, null));
    }
}
