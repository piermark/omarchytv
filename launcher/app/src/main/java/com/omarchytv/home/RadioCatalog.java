package com.omarchytv.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.20 Radiomarchy: the two lists of Omarchy Radio (radio.omarchy.org), read the way its own deck reads them.
 * <ul>
 * <li>Songs: {@code /tracks/playlist.json} (title, artist, file, and optionally album, url, explicit, lyrics). Every
 *     song was sent in by its maker as a pull request; the player shows who made it and streams the MP3 from
 *     radio.omarchy.org, never from a copy in the app.</li>
 * <li>Omarchy Stories, the community's podcast: the site's own mirror of the show's feed ({@code /stories/feed.rss},
 *     falling back to the host's feed); the episode audio comes from the show's host, so its figures still count.</li>
 * <li>Lyrics: {@code /tracks/lyrics/<song>.lrc} when there is one, timed ([mm:ss.xx]) or plain.</li>
 * </ul>
 * Songs labelled explicit by their makers are in the list with an E badge, as on the site; a setting hides them.
 * The last good copy of each list is kept on the TV, so the app opens straight away and works when the site is down.
 * Parsing is pure Java (org.json and regular expressions), tested in RadioCatalogTest.
 */
final class RadioCatalog {
    static final String SITE = "https://radio.omarchy.org";
    static final String PLAYLIST = SITE + "/tracks/playlist.json";
    static final String FEED = SITE + "/stories/feed.rss";
    static final String FEED_HOST = "https://api.riverside.com/hosting/1i59HjrN.rss";
    static final String SHOW = "Omarchy Stories";

    static final class Track {
        final String kind;       // "song" or "episode"
        final String title, artist, url, album, lyricsUrl, date, image, about, page;
        final boolean explicit;
        final long durationMs;   // 0 = not known yet

        Track(String kind, String title, String artist, String url, String album, boolean explicit, String lyricsUrl,
              long durationMs, String date, String image, String about, String page) {
            this.kind = kind;
            this.title = title;
            this.artist = artist;
            this.url = url;
            this.album = album;
            this.explicit = explicit;
            this.lyricsUrl = lyricsUrl;
            this.durationMs = durationMs;
            this.date = date;
            this.image = image;
            this.about = about;
            this.page = page;
        }

        boolean episode() {
            return "episode".equals(kind);
        }

        /** "Artist — Title" for a song, "Omarchy Stories — Title" for an episode. */
        String line() {
            return (artist.isEmpty() ? "" : artist + " — ") + title;
        }
    }

    /** One line of a lyric sheet: its time (ms, -1 for an untimed sheet) and words. */
    static final class Line {
        final long ms;
        final String text;

        Line(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    private RadioCatalog() {
    }

    // ---- songs ----------------------------------------------------------------------------------------

    static List<Track> parseSongs(String json) {
        List<Track> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            JSONObject o = new JSONObject(json);
            JSONArray a = o.optJSONArray("tracks");
            for (int i = 0; a != null && i < a.length() && out.size() < 5000; i++) {
                JSONObject t = a.optJSONObject(i);
                if (t == null) continue;
                String title = t.optString("title", "").trim(), file = t.optString("file", "").trim(), url = t.optString("url", "").trim();
                if (title.isEmpty()) continue;
                String page = SITE + "/playlist/" + uniqueSlug(title, t.optString("artist", ""), seen);
                if (url.isEmpty()) {
                    if (file.isEmpty() || file.contains("/") || file.contains("..")) continue;
                    url = SITE + "/tracks/" + enc(file);
                }
                if (!url.startsWith("https://")) continue;   // the deck plays https only, and so do we
                String lyrics;
                Object ly = t.opt("lyrics");
                if (Boolean.FALSE.equals(ly)) lyrics = "";
                else if (ly instanceof String && !((String) ly).isEmpty() && !((String) ly).contains("/")) lyrics = SITE + "/tracks/lyrics/" + enc((String) ly);
                else if (!file.isEmpty()) lyrics = SITE + "/tracks/lyrics/" + enc(file.replaceAll("\\.[^.]+$", "") + ".lrc");
                else lyrics = "";
                out.add(new Track("song", title, t.optString("artist", "").trim(), url, t.optString("album", "").trim(),
                        t.optBoolean("explicit", false), lyrics, 0, "", "", "", page));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** radio.omarchy.org's slugify (src/lib/slug.ts): accents stripped, lower case, a-z 0-9 joined by hyphens. */
    static String slug(String s) {
        String n = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD).replaceAll("[\u0300-\u036f]", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("['\u2019]", "").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** The site's assignSlugs: the title, then title-artist for a clash, then title-2, -3... */
    static String uniqueSlug(String title, String artist, java.util.Set<String> seen) {
        String base = slug(title);
        if (base.isEmpty()) base = "track";
        String s = base;
        if (seen.contains(s)) s = base + "-" + slug(artist);
        int n = 2;
        while (seen.contains(s)) s = base + "-" + n++;
        seen.add(s);
        return s;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    // ---- the podcast ----------------------------------------------------------------------------------

    private static final Pattern ITEM = Pattern.compile("<item\\b[^>]*>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern ENCL = Pattern.compile("<enclosure\\b([^>]*)/?>", Pattern.DOTALL);

    static List<Track> parseFeed(String xml) {
        List<Track> out = new ArrayList<>();
        if (xml == null) return out;
        String showImage = attr(firstTag(channelHead(xml), "itunes:image", true), "href");
        Matcher m = ITEM.matcher(xml);
        while (m.find() && out.size() < 500) {
            String it = m.group(1);
            Matcher e = ENCL.matcher(it);
            if (!e.find()) continue;
            String url = unescape(attr(e.group(1), "url"));
            if (!url.startsWith("https://")) continue;
            String title = clean(text(it, "title"));
            if (title.isEmpty()) title = clean(text(it, "itunes:title"));
            if (title.isEmpty()) continue;
            String who = clean(text(it, "dc:creator"));
            if (who.isEmpty()) who = clean(text(it, "itunes:author"));
            String img = attr(firstTag(it, "itunes:image", true), "href");
            String about = clean(text(it, "itunes:summary"));
            if (about.isEmpty()) about = clean(text(it, "description"));
            String link = clean(text(it, "link"));
            boolean explicit = "yes".equalsIgnoreCase(clean(text(it, "itunes:explicit"))) || "true".equalsIgnoreCase(clean(text(it, "itunes:explicit")));
            out.add(new Track("episode", title, who.isEmpty() ? SHOW : SHOW + " · " + who, url, "", explicit, "",
                    duration(clean(text(it, "itunes:duration"))), clean(text(it, "pubDate")),
                    img.isEmpty() ? showImage : img, about.length() > 1200 ? about.substring(0, 1200) + "…" : about, link));
        }
        return out;
    }

    private static String channelHead(String xml) {
        int i = xml.indexOf("<item");
        return i > 0 ? xml.substring(0, i) : xml;
    }

    /** "00:47:47", "47:47" or seconds, to ms. */
    static long duration(String d) {
        if (d == null || d.isEmpty()) return 0;
        try {
            String[] p = d.trim().split(":");
            long s = 0;
            for (String x : p) s = s * 60 + Long.parseLong(x.trim().replaceAll("\\..*", ""));
            return s * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String text(String in, String tag) {
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "\\b[^>]*>(.*?)</" + Pattern.quote(tag) + ">", Pattern.DOTALL).matcher(in);
        return m.find() ? m.group(1) : "";
    }

    private static String firstTag(String in, String tag, boolean attrsOnly) {
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "\\b([^>]*)>", Pattern.DOTALL).matcher(in);
        return m.find() ? m.group(1) : "";
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").matcher(attrs == null ? "" : attrs);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3)) : "";
    }

    /** CDATA unwrapped, tags dropped, entities decoded, spaces folded. */
    static String clean(String s) {
        if (s == null) return "";
        boolean cdata = s.contains("<![CDATA[");
        s = s.replaceAll("(?s)<!\\[CDATA\\[(.*?)]]>", "$1");
        if (!cdata) s = unescape(s);   // HTML escaped into the feed (&lt;p&gt;): decoded first, then its tags go
        s = s.replaceAll("(?i)<br\\s*/?>|</p>", "\n").replaceAll("(?s)<[^>]+>", "");
        s = unescape(s);
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\\n[ \\n]*", "\n").trim();
    }

    static String unescape(String s) {
        if (s == null) return "";
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (Exception e) {
                cp = '?';
            }
            m.appendReplacement(b, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(b);
        return b.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
    }

    // ---- lyrics ---------------------------------------------------------------------------------------

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    /** An .lrc sheet: timed lines in time order, or every line with ms = -1 for a plain sheet. */
    static List<Line> parseLyrics(String text) {
        List<Line> timed = new ArrayList<>(), plain = new ArrayList<>();
        if (text == null) return plain;
        for (String raw : text.replace("\r", "").split("\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> at = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    ms += Long.parseLong(f) * (f.length() == 1 ? 100 : f.length() == 2 ? 10 : 1);
                }
                at.add(ms);
                end = m.end();
            }
            String words = raw.substring(end).trim();
            if (!at.isEmpty()) {
                for (long ms : at) timed.add(new Line(ms, words));
            } else if (!raw.matches("^\\s*\\[[a-zA-Z]+:.*]\\s*$")) {   // [ar:...] [ti:...] tags are not lyrics
                plain.add(new Line(-1, raw.trim()));
            }
        }
        if (!timed.isEmpty()) {
            timed.sort((a, b) -> Long.compare(a.ms, b.ms));
            return timed;
        }
        while (!plain.isEmpty() && plain.get(0).text.isEmpty()) plain.remove(0);
        while (!plain.isEmpty() && plain.get(plain.size() - 1).text.isEmpty()) plain.remove(plain.size() - 1);
        return plain;
    }

    /** The line being sung at {@code ms} (timed sheets), or -1. */
    static int lineAt(List<Line> sheet, long ms) {
        int at = -1;
        for (int i = 0; i < sheet.size(); i++) {
            if (sheet.get(i).ms < 0) return -1;
            if (sheet.get(i).ms <= ms) at = i;
            else break;
        }
        return at;
    }

    /** Songs only, minus the explicit ones when they are hidden. */
    static List<Track> visible(List<Track> all, boolean showExplicit) {
        if (showExplicit) return all;
        List<Track> out = new ArrayList<>();
        for (Track t : all) if (!t.explicit) out.add(t);
        return out;
    }

    // ---- fetching and the copy kept on the TV -------------------------------------------------------

    /** GET over https (radio.omarchy.org and the show's host), at most 4 MB; null on any failure. */
    static String fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "OmarchyTV-Radiomarchy");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 4 << 20) return null;
                    b.write(buf, 0, n);
                }
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String readCache(File f) {
        if (!f.isFile() || f.length() > 4 << 20) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void writeCache(File f, String s) {
        if (s == null) return;
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }
}
