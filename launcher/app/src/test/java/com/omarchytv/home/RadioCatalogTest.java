package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/** Radiomarchy reads Omarchy Radio's playlist, the Omarchy Stories feed and lyric sheets the way the site does. */
public class RadioCatalogTest {

    private static String res(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/test/resources/radio/" + name).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theRealPlaylistGivesEverySongWithItsMakerAndAnHttpsAddress() throws Exception {
        List<RadioCatalog.Track> s = RadioCatalog.parseSongs(res("playlist.json"));
        assertEquals(33, s.size());
        RadioCatalog.Track first = s.get(0);
        assertEquals("Omarchy Oligarchy (Synthwave Mix)", first.title);
        assertEquals("YZL81", first.artist);
        assertEquals("https://radio.omarchy.org/tracks/yzl81-omarchy-oligarchy-synthwave-mix.mp3", first.url);
        assertEquals("https://radio.omarchy.org/tracks/lyrics/yzl81-omarchy-oligarchy-synthwave-mix.lrc", first.lyricsUrl);
        assertEquals("https://radio.omarchy.org/playlist/omarchy-oligarchy-synthwave-mix", first.page);
        int explicit = 0;
        for (RadioCatalog.Track t : s) {
            assertTrue(t.url.startsWith("https://radio.omarchy.org/tracks/"));
            assertFalse(t.artist.isEmpty());
            assertEquals("song", t.kind);
            if (t.explicit) explicit++;
        }
        assertEquals("the site labels one song explicit", 1, explicit);
        // shown by default (with an E); the setting leaves them out
        assertEquals(33, RadioCatalog.visible(s, true).size());
        assertEquals(32, RadioCatalog.visible(s, false).size());
    }

    @Test
    public void fieldsAndUnsafeEntries() {
        String j = "{\"tracks\":[{\"title\":\"A b\",\"artist\":\"X\",\"file\":\"x a.mp3\",\"album\":\"LP\",\"lyrics\":false},"
                + "{\"title\":\"Elsewhere\",\"artist\":\"Y\",\"url\":\"https://cdn.example/y.mp3\",\"lyrics\":\"y.lrc\"},"
                + "{\"title\":\"Plain\",\"artist\":\"Z\",\"url\":\"http://insecure/z.mp3\"},"
                + "{\"title\":\"Up\",\"artist\":\"Z\",\"file\":\"../etc/passwd\"},"
                + "{\"artist\":\"no title\",\"file\":\"n.mp3\"},"
                + "{\"title\":\"A b\",\"artist\":\"Q\",\"file\":\"q.mp3\",\"explicit\":true}]}";
        List<RadioCatalog.Track> s = RadioCatalog.parseSongs(j);
        assertEquals(3, s.size());
        assertEquals("https://radio.omarchy.org/tracks/x%20a.mp3", s.get(0).url);
        assertEquals("LP", s.get(0).album);
        assertEquals("", s.get(0).lyricsUrl);
        assertEquals("https://cdn.example/y.mp3", s.get(1).url);
        assertEquals("https://radio.omarchy.org/tracks/lyrics/y.lrc", s.get(1).lyricsUrl);
        assertTrue(s.get(2).explicit);
        // two songs with one title: the second is told apart by its maker, as on the site
        assertEquals("https://radio.omarchy.org/playlist/a-b", s.get(0).page);
        assertEquals("https://radio.omarchy.org/playlist/a-b-q", s.get(2).page);
        assertEquals(0, RadioCatalog.parseSongs("not json").size());
        assertEquals(0, RadioCatalog.parseSongs("{}").size());
    }

    @Test
    public void slugsMatchTheSite() {
        assertEquals("omarchee-cest-la-vie", RadioCatalog.slug("Omarchée C'est La Vie"));
        assertEquals("its-fucking-fork-oclock", RadioCatalog.slug("It’s Fucking Fork O'Clock"));
        assertEquals("quattro-lets-go-nerds", RadioCatalog.slug("  Quattro! Let's Go, Nerds  "));
        Set<String> seen = new HashSet<>();
        assertEquals("x", RadioCatalog.uniqueSlug("X", "a", seen));
        assertEquals("x-a", RadioCatalog.uniqueSlug("X", "a", seen));
        assertEquals("x-2", RadioCatalog.uniqueSlug("X", "a", seen));
        assertEquals("track", RadioCatalog.uniqueSlug("!!!", "", seen));
    }

    @Test
    public void theStoriesFeedGivesEpisodesWithLengthDateAndArt() throws Exception {
        List<RadioCatalog.Track> e = RadioCatalog.parseFeed(res("feed.rss"));
        assertEquals(2, e.size());
        RadioCatalog.Track a = e.get(0);
        assertEquals("episode", a.kind);
        assertTrue(a.title, a.title.startsWith("009 Joel Gaff"));
        assertEquals("Omarchy Stories · Basti", a.artist);
        assertTrue(a.url.startsWith("https://api.riverside.com/"));
        assertTrue(a.url.endsWith(".mp3"));
        assertEquals((47 * 60 + 47) * 1000L, a.durationMs);
        assertTrue(a.date.startsWith("Wed, 28 Oct 2026"));
        assertTrue(a.image.startsWith("https://"));
        assertFalse(a.about.contains("<p>"));
        assertFalse(a.about.isEmpty());
        assertFalse(a.explicit);
    }

    @Test
    public void feedOddities() {
        String x = "<rss><channel><title>S</title><itunes:image href=\"https://i/show.png\"/>"
                + "<item><title><![CDATA[Tom &amp; Jerry &#8212; live]]></title><enclosure url=\"https://h/a.mp3?x=1&amp;y=2\" type=\"audio/mpeg\"/>"
                + "<itunes:duration>3725</itunes:duration><itunes:explicit>yes</itunes:explicit></item>"
                + "<item><title>No audio</title></item>"
                + "<item><title>Plain http</title><enclosure url=\"http://h/b.mp3\"/></item>"
                + "</channel></rss>";
        List<RadioCatalog.Track> e = RadioCatalog.parseFeed(x);
        assertEquals(1, e.size());
        assertEquals("Tom & Jerry — live", e.get(0).title);
        assertEquals("https://h/a.mp3?x=1&y=2", e.get(0).url);
        assertEquals(3725_000L, e.get(0).durationMs);
        assertEquals("https://i/show.png", e.get(0).image);
        assertTrue(e.get(0).explicit);
        assertEquals(0, RadioCatalog.parseFeed("").size());
        assertEquals(0, RadioCatalog.parseFeed(null).size());
        assertEquals(47 * 60_000L + 47_000, RadioCatalog.duration("47:47"));
        assertEquals(0, RadioCatalog.duration("soon"));
    }

    @Test
    public void lyricSheetsTimedAndPlain() {
        List<RadioCatalog.Line> t = RadioCatalog.parseLyrics("[ar:Someone]\n[ti:Song]\n[00:12.50]first line\n[00:05.00][01:00.00]chorus\n[00:20]third\n");
        assertEquals(4, t.size());
        assertEquals(5000, t.get(0).ms);
        assertEquals("chorus", t.get(0).text);
        assertEquals(12500, t.get(1).ms);
        assertEquals(20000, t.get(2).ms);
        assertEquals(60000, t.get(3).ms);
        assertEquals(-1, RadioCatalog.lineAt(t, 1000));
        assertEquals(0, RadioCatalog.lineAt(t, 5000));
        assertEquals(1, RadioCatalog.lineAt(t, 19999));
        assertEquals(3, RadioCatalog.lineAt(t, 999999));
        List<RadioCatalog.Line> p = RadioCatalog.parseLyrics("\nI was stuck\r\n\nWe can fix everything\n\n");
        assertEquals(3, p.size());
        assertEquals(-1, p.get(0).ms);
        assertEquals("I was stuck", p.get(0).text);
        assertEquals(-1, RadioCatalog.lineAt(p, 5000));
    }
}
