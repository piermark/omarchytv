package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** v2.22 #162: the Radio plugin's stations, URL rules and playlists. */
public class RadioStationsTest {
    @Test
    public void urlRules() {
        assertTrue(RadioStations.urlAllowed("https://stream.radioparadise.com/aac-320", false));
        assertTrue(RadioStations.urlAllowed("http://192.168.1.20:8000/live.mp3", false));
        assertFalse(RadioStations.urlAllowed("http://ice1.somafm.com/groovesalad-128-mp3", false));
        assertTrue(RadioStations.urlAllowed("http://ice1.somafm.com/groovesalad-128-mp3", true));
        assertFalse(RadioStations.urlAllowed("file:///sdcard/x.mp3", true));
        assertFalse(RadioStations.urlAllowed("https://x y", true));
    }

    @Test
    public void listPutsOmarchyFirstThenFavourites() throws Exception {
        JSONObject radio = new JSONObject().put("stations", new JSONArray()
                .put(new JSONObject().put("id", "soma").put("name", "SomaFM").put("url", "https://ice1.somafm.com/groovesalad-256-mp3"))
                .put(new JSONObject().put("id", "rp").put("name", "Radio Paradise").put("url", "https://stream.radioparadise.com/aac-320").put("fav", true))
                .put(new JSONObject().put("id", "bad").put("name", "Bad").put("url", "http://example.com/x"))
                .put(new JSONObject().put("id", "omarchy").put("name", "Fake").put("url", "https://x.example")));
        List<RadioStations.Station> l = RadioStations.list(radio);
        assertEquals(3, l.size());
        assertEquals("omarchy", l.get(0).id);
        assertEquals("rp", l.get(1).id);
        assertEquals("rp", RadioStations.find(l, "radio paradise").id);
        assertNull(RadioStations.find(l, "nope"));
        assertEquals("radio-paradise-2", RadioStations.idFor("Radio Paradise!", new java.util.HashSet<>(java.util.Arrays.asList("radio-paradise"))));
    }

    @Test
    public void playlistsResolveToTheirFirstStream() {
        assertTrue(RadioStations.isPlaylist("https://somafm.com/groovesalad.pls"));
        assertTrue(RadioStations.isPlaylist("https://x.example/a.m3u?x=1"));
        assertFalse(RadioStations.isPlaylist("https://x.example/live.m3u8"));
        assertEquals("https://ice2.somafm.com/groovesalad-128-mp3",
                RadioStations.firstStream("[playlist]\nnumberofentries=2\nFile1=https://ice2.somafm.com/groovesalad-128-mp3\nTitle1=Groove Salad\n"));
        assertEquals("http://10.0.0.5:8000/x", RadioStations.firstStream("#EXTM3U\n#EXTINF:-1,Local\nhttp://10.0.0.5:8000/x\n"));
        assertNull(RadioStations.firstStream("nothing here"));
    }
}
