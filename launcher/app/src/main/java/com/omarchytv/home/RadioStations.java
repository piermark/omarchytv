package com.omarchytv.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * v2.22 (#162) the Radio plugin's own stations: omarchytv.json radio.stations [{id, name, url, logo?, fav?}], at most
 * {@link #MAX}. Omarchy Radio (the songs of radio.omarchy.org) is always the first, built in. A station URL is
 * https:// to any host, or http:// to a private LAN address, or any http:// once the owner ticks radio.allowHttp.
 * Playlists (.pls, .m3u) are resolved to their first stream; HLS (.m3u8) is played as it is. Pure, unit-tested.
 */
final class RadioStations {
    static final int MAX = 200;
    static final String OMARCHY = "omarchy";
    static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");

    static final class Station {
        final String id, name, url, logo;
        final boolean fav;

        Station(String id, String name, String url, String logo, boolean fav) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.logo = logo;
            this.fav = fav;
        }

        boolean builtIn() {
            return OMARCHY.equals(id);
        }
    }

    private RadioStations() {
    }

    /** The URL rule: https anywhere; http only on the LAN, unless the owner allowed http streams. */
    static boolean urlAllowed(String url, boolean allowHttp) {
        if (url == null || url.length() > 512 || url.indexOf(' ') >= 0) return false;
        if (url.startsWith("https://")) return url.length() > 9;
        if (!url.startsWith("http://")) return false;
        if (allowHttp) return url.length() > 8;
        try {
            return LanHttpDataSource.lanHost(new java.net.URL(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** The stations: Omarchy Radio first, then the valid ones of the config (favourites first, in order). */
    static List<Station> list(JSONObject radio) {
        List<Station> out = new ArrayList<>(), favs = new ArrayList<>(), rest = new ArrayList<>();
        out.add(new Station(OMARCHY, "Omarchy Radio", "https://radio.omarchy.org", "", true));
        JSONArray a = radio != null ? radio.optJSONArray("stations") : null;
        boolean allowHttp = radio != null && radio.optBoolean("allowHttp");
        java.util.Set<String> ids = new java.util.HashSet<>();
        ids.add(OMARCHY);
        for (int i = 0; a != null && i < a.length() && favs.size() + rest.size() < MAX; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id"), name = o.optString("name").trim(), url = o.optString("url").trim();
            if (!ID.matcher(id).matches() || name.isEmpty() || !urlAllowed(url, allowHttp) || !ids.add(id)) continue;
            String logo = o.optString("logo", "");
            Station s = new Station(id, name.length() > 40 ? name.substring(0, 40) : name, url, urlAllowed(logo, false) ? logo : "", o.optBoolean("fav"));
            (s.fav ? favs : rest).add(s);
        }
        out.addAll(favs);
        out.addAll(rest);
        return out;
    }

    /** A station by id, or by name (case-insensitive), or null. */
    static Station find(List<Station> all, String ref) {
        if (ref == null) return null;
        for (Station s : all) if (s.id.equals(ref)) return s;
        for (Station s : all) if (s.name.equalsIgnoreCase(ref.trim())) return s;
        return null;
    }

    /** An id for a new station from its name ("Radio Paradise" → radio-paradise), unique among {@code taken}. */
    static String idFor(String name, java.util.Set<String> taken) {
        String base = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "station";
        if (base.length() > 32) base = base.substring(0, 32);
        String id = base;
        for (int n = 2; taken.contains(id) || OMARCHY.equals(id); n++) id = base + "-" + n;
        return id;
    }

    /** True for a playlist to resolve first (.pls, .m3u, but not the HLS .m3u8). */
    static boolean isPlaylist(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT).replaceAll("[?#].*$", "");
        return u.endsWith(".pls") || u.endsWith(".m3u");
    }

    /** The first http(s) stream in a .pls ("File1=…") or .m3u (one URL per line) playlist, or null. */
    static String firstStream(String playlist) {
        if (playlist == null) return null;
        for (String raw : playlist.split("\\r?\\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("[")) continue;
            int eq = l.indexOf('=');
            if (l.toLowerCase(java.util.Locale.ROOT).startsWith("file") && eq > 0) l = l.substring(eq + 1).trim();
            if (l.startsWith("http://") || l.startsWith("https://")) return l;
        }
        return null;
    }
}
