package com.omarchytv.home.link;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** v2.22 #158 / #159: Home widget pages and the navigator window, without a TV. */
public class HomePagesTest {
    private static JSONObject cfgWith(int... pages) throws Exception {
        JSONArray w = new JSONArray();
        for (int i = 0; i < pages.length; i++) {
            w.put(new JSONObject().put("id", "w" + i).put("type", "stat").put("page", pages[i]).put("x", 0).put("y", 0).put("w", 1).put("h", 1));
        }
        return new JSONObject().put("widgets", w);
    }

    @Test
    public void pagesAreCreatedOnDemandAndStayContiguous() throws Exception {
        JSONObject c = cfgWith(1, 2, 3);
        assertEquals(3, HomePages.total(c));
        assertEquals(4, HomePages.onDemand(c, 40, false, 1));   // 40 when there are 3 → 4
        try {
            HomePages.onDemand(c, 40, true, 1);
            fail();
        } catch (HomeWidgets.Bad e) {
            assertEquals("no_such_page", e.code);
        }
        int casa = HomePages.onDemand(c, "casa", false, 1);   // an unknown id → a new page with that id
        assertEquals(4, casa);
        assertEquals(4, HomePages.resolve(c, "casa"));
        assertEquals("casa", HomePages.name(c, 4));
        assertEquals("Page 2", HomePages.name(c, 2));
    }

    @Test
    public void createAfterRenameReorderDelete() throws Exception {
        JSONObject c = cfgWith(1, 2, 3);
        int at = HomePages.create(c, new JSONObject().put("name", "Server").put("id", "server").put("after", 1), 1);
        assertEquals(2, at);
        assertEquals(4, HomePages.total(c));
        assertEquals(3, c.getJSONArray("widgets").getJSONObject(1).getInt("page"));   // old page 2 moved right
        HomePages.rename(c, "server", "Servers", 1);
        assertEquals("Servers", HomePages.name(c, 2));
        assertEquals(4, HomePages.reorder(c, "server", 4, 1));
        assertEquals(4, HomePages.resolve(c, "server"));
        try {
            HomePages.reorder(c, 1, 3, 1);
            fail();
        } catch (HomeWidgets.Bad e) {
            assertEquals("home_page", e.code);
        }
        // delete page 2 moving its widget to page 1
        int moved = HomePages.delete(c, 2, 1, 1);
        assertEquals(1, moved);
        assertEquals(3, HomePages.total(c));
        for (int i = 0; i < c.getJSONArray("widgets").length(); i++) {
            JSONObject a = c.getJSONArray("widgets").getJSONObject(i), b;
            for (int k = i + 1; k < c.getJSONArray("widgets").length(); k++) {
                b = c.getJSONArray("widgets").getJSONObject(k);
                if (a.getInt("page") == b.getInt("page")) {
                    assertTrue(!HomeWidgets.overlap(a.getInt("x"), a.getInt("y"), a.getInt("w"), a.getInt("h"), b.getInt("x"), b.getInt("y"), b.getInt("w"), b.getInt("h")));
                }
            }
        }
        try {
            HomePages.delete(c, 1, null, 1);
            fail();
        } catch (HomeWidgets.Bad e) {
            assertEquals("home_page", e.code);
        }
    }

    @Test
    public void emptyPagesGoAwayAfterADay() throws Exception {
        JSONObject c = cfgWith(1);
        HomePages.create(c, new JSONObject().put("name", "Later"), 1000);
        HomePages.create(c, new JSONObject().put("name", "Fresh"), 100_000);
        assertEquals(3, HomePages.total(c));
        assertEquals(1, HomePages.cleanup(c, 100_000));   // "Later" was empty for more than a day
        assertEquals(2, HomePages.total(c));
        assertEquals("Fresh", HomePages.name(c, 2));
    }

    @Test
    public void navigatorWindow() {
        assertArrayEquals(new int[]{0, 0, 0, 0}, HomePages.window(1, 0, 5));
        assertArrayEquals(new int[]{1, 1, 0, 0}, HomePages.window(1, 1, 5));
        assertArrayEquals(new int[]{1, 3, 0, 0}, HomePages.window(2, 3, 5));
        assertArrayEquals(new int[]{1, 5, 0, 0}, HomePages.window(5, 5, 5));
        assertArrayEquals(new int[]{1, 5, 0, 1}, HomePages.window(1, 6, 5));
        assertArrayEquals(new int[]{6, 6, 1, 0}, HomePages.window(6, 6, 5));
        assertArrayEquals(new int[]{6, 10, 1, 1}, HomePages.window(8, 42, 5));
        assertArrayEquals(new int[]{41, 42, 1, 0}, HomePages.window(42, 42, 5));
        assertArrayEquals(new int[]{996, 1000, 1, 0}, HomePages.window(1000, 1000, 5));
        assertArrayEquals(new int[]{1, 5, 0, 1}, HomePages.window(-3, 1000, 5));
    }

    @Test
    public void hiddenPagesAreLeftOutOfTheNavigator() throws Exception {
        JSONObject c = cfgWith(1, 2, 3);
        c.put("widgetPages", new JSONArray().put(new JSONObject().put("index", 2).put("hidden", true)));
        assertEquals(java.util.Arrays.asList(1, 3), HomePages.visible(c));
    }
}
