package com.omarchytv.home.iptv;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class IptvTest {

    @Test
    public void m3u_parsesAttributesGroupsAndQuotes() {
        String m3u = "\uFEFF#EXTM3U url-tvg=\"https://example.com/epg.xml.gz\"\n"
                + "#EXTINF:-1 tvg-id=\"rai1.it\" tvg-name=\"Rai 1\" tvg-logo=\"https://img/1.png\" group-title=\"Generalisti\",Rai 1 HD\n"
                + "https://stream.example.com/rai1/master.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"news,1\" group-title=\"News\",Channel, With Comma\n"
                + "#EXTVLCOPT:http-user-agent=Custom\n"
                + "http://192.168.1.50:9981/stream/channel/2\n"
                + "#EXTINF:-1,Fallback Name\n"
                + "#EXTGRP:Sports\n"
                + "rtsp://192.168.1.50:554/live\n";
        List<M3uParser.Channel> ch = M3uParser.parse(m3u);
        assertEquals(3, ch.size());
        assertEquals("Rai 1 HD", ch.get(0).name);
        assertEquals("rai1.it", ch.get(0).tvgId);
        assertEquals("Generalisti", ch.get(0).group);
        assertEquals("https://img/1.png", ch.get(0).logo);

        assertEquals("Channel, With Comma", ch.get(1).name);
        assertEquals("news,1", ch.get(1).tvgId);
        assertEquals("Sports", ch.get(2).group);
        assertEquals(List.of("Generalisti", "News", "Sports"), M3uParser.groups(ch));
    }

    @Test
    public void xmltv_parsesNowNextAndGzip() throws Exception {
        String xml = "<tv>"
                + "<programme start=\"20261028200000 +0100\" stop=\"20261028210000 +0100\" channel=\"rai1.it\">"
                + "<title lang=\"it\">Telegiornale &amp; Meteo</title></programme>"
                + "<programme start=\"20261028210000 +0100\" stop=\"20261028223000 +0100\" channel=\"rai1.it\">"
                + "<title>Cinema Serale</title></programme>"
                + "</tv>";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
            gz.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        String decoded = Xmltv.decode(bo.toByteArray());
        Map<String, List<Xmltv.Programme>> map = Xmltv.parse(decoded);
        long mid = Xmltv.time("20261028203000 +0100");
        Xmltv.Programme[] nn = Xmltv.nowNext(map.get("rai1.it"), mid);
        assertNotNull(nn[0]);
        assertNotNull(nn[1]);
        assertEquals("Telegiornale & Meteo", nn[0].title);
        assertEquals("Cinema Serale", nn[1].title);
    }

    @Test
    public void store_addsAndReadsPlaylists() throws Exception {
        JSONObject root = new JSONObject();
        IptvStore.addPlaylist(root, "Home", "https://example.com/a.m3u8");
        IptvStore.addPlaylist(root, "Home Renamed", "https://example.com/a.m3u8");
        List<IptvStore.Playlist> pls = IptvStore.playlists(root);
        assertEquals(1, pls.size());
        assertEquals("Home Renamed", pls.get(0).name);
    }

    @Test
    public void downloadsCarryAPlayerUserAgentAndAcceptAnOverride() throws Exception {
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", IptvStore.userAgent(new JSONObject()));
        JSONObject iptv = new JSONObject().put("playlists", new org.json.JSONArray());
        JSONObject custom = new JSONObject().put("cinemarchy", new JSONObject().put("iptv", iptv.put("userAgent", "  okhttp/4.9.0  ")));
        assertEquals("okhttp/4.9.0", IptvStore.userAgent(custom));
        // A value with a line break would make HttpURLConnection throw: it is flattened instead.
        JSONObject broken = new JSONObject().put("cinemarchy", new JSONObject().put("iptv", new JSONObject().put("userAgent", "a\r\nb")));
        assertEquals("a  b", IptvStore.userAgent(broken));
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", IptvStore.userAgent(new JSONObject().put("cinemarchy", new JSONObject().put("iptv", new JSONObject().put("userAgent", "   ")))));
    }
}
