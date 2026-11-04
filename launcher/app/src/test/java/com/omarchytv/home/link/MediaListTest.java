package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Cinemarchy's PC folder listings from omarchy-link: only relative paths, known kinds, bounded. */
public class MediaListTest {
    @Test
    public void keepsRelativeMediaOnly() throws Exception {
        JSONObject in = new JSONObject()
                .put("path", "Video").put("parent", "")
                .put("dirs", new JSONArray()
                        .put(new JSONObject().put("name", "Serie").put("path", "Video/Serie"))
                        .put(new JSONObject().put("name", "up").put("path", "Video/../etc"))
                        .put(new JSONObject().put("name", "abs").put("path", "/etc")))
                .put("files", new JSONArray()
                        .put(new JSONObject().put("name", "film.mkv").put("path", "Video/film.mkv").put("kind", "video").put("size", 1000))
                        .put(new JSONObject().put("name", "x.sh").put("path", "Video/x.sh").put("kind", "script"))
                        .put(new JSONObject().put("name", "neg").put("path", "Video/a.mp3").put("kind", "audio").put("size", -5)));
        JSONObject out = LinkServer.mediaList(in);
        assertEquals("Video", out.getString("path"));
        assertEquals(1, out.getJSONArray("dirs").length());
        assertEquals("Video/Serie", out.getJSONArray("dirs").getJSONObject(0).getString("path"));
        JSONArray files = out.getJSONArray("files");
        assertEquals(2, files.length());
        assertEquals("film.mkv", files.getJSONObject(0).getString("name"));
        assertEquals(0, files.getJSONObject(1).getLong("size"));
    }

    @Test
    public void safeRelRefusesEscapes() {
        assertEquals("", LinkServer.safeRel("../x"));
        assertEquals("", LinkServer.safeRel("/abs"));
        assertEquals("", LinkServer.safeRel("a\\b"));
        assertEquals("", LinkServer.safeRel("a/./b"));
        assertEquals("Video/a b.mkv", LinkServer.safeRel("Video/a b.mkv"));
        assertFalse(LinkServer.safeRel("Video/\u0000x").contains("\u0000"));
    }
}
