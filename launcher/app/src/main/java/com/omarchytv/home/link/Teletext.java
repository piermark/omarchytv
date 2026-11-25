package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * v2.22 Televideo AI: text pages that agents publish, 40 columns by 22 rows, numbered 101-899 like the teletext of
 * old; page 100 is the index, made by the TV. Text only, with the seven teletext colours. Kept in omarchytv.json
 * ("teletext": {"101": {...}}). Pure model, tested on the JVM.
 */
public final class Teletext {
    public static final int COLS = 40, ROWS = 22, MAX_PAGES = 60, INDEX = 100;
    public static final List<String> COLORS = Arrays.asList("white", "red", "green", "yellow", "blue", "magenta", "cyan");

    private Teletext() {
    }

    /** 101-899 (100 is the TV's index). */
    public static int number(Object v) throws Studio.Bad {
        int n;
        try {
            n = Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            n = -1;
        }
        if (n < 101 || n > 899) throw new Studio.Bad("bad_page", "page: 101 to 899 (100 is the index)", 400);
        return n;
    }

    public static JSONObject publish(JSONObject cfg, int n, JSONObject b, String by, long now) throws Studio.Bad, JSONException {
        String title = LinkServer.printable(b.optString("title", ""), 32);
        if (title.isEmpty()) throw new Studio.Bad("bad_title", "title: 1 to 32 characters", 400);
        JSONArray in = b.optJSONArray("lines"), lines = new JSONArray();
        if (in == null || in.length() == 0) throw new Studio.Bad("bad_lines", "lines: 1 to " + ROWS + " (text, or {text, color})", 400);
        if (in.length() > ROWS) throw new Studio.Bad("too_many", ROWS + " lines", 400);
        for (int i = 0; i < in.length(); i++) {
            Object o = in.opt(i);
            String text = o instanceof JSONObject ? ((JSONObject) o).optString("text", "") : o == null || JSONObject.NULL.equals(o) ? "" : String.valueOf(o);
            String color = o instanceof JSONObject ? ((JSONObject) o).optString("color", "white") : "white";
            if (!COLORS.contains(color)) throw new Studio.Bad("bad_color", "color: one of " + COLORS, 400);
            lines.put(new JSONObject().put("text", LinkServer.printable(text, COLS)).put("color", color));
        }
        JSONObject all = cfg.optJSONObject("teletext");
        if (all == null) cfg.put("teletext", all = new JSONObject());
        String key = String.valueOf(n);
        if (!all.has(key) && all.length() >= MAX_PAGES) throw new Studio.Bad("full", MAX_PAGES + " pages; remove one first", 409);
        JSONObject p = new JSONObject().put("page", n).put("title", title).put("lines", lines).put("by", LinkServer.printable(by, 40)).put("at", now);
        all.put(key, p);
        return p;
    }

    public static void remove(JSONObject cfg, int n) throws Studio.Bad {
        JSONObject all = cfg.optJSONObject("teletext");
        if (all == null || all.remove(String.valueOf(n)) == null) throw new Studio.Bad("no_such_page", String.valueOf(n), 404);
    }

    public static List<Integer> numbers(JSONObject cfg) {
        List<Integer> out = new ArrayList<>();
        JSONObject all = cfg.optJSONObject("teletext");
        for (Iterator<String> it = all == null ? Collections.<String>emptyIterator() : all.keys(); it.hasNext(); ) {
            try {
                out.add(Integer.parseInt(it.next()));
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(out);
        return out;
    }

    /** [{page, title, by, at}], in page order. */
    public static JSONArray list(JSONObject cfg) throws JSONException {
        JSONArray out = new JSONArray();
        JSONObject all = cfg.optJSONObject("teletext");
        for (int n : numbers(cfg)) {
            JSONObject p = all.getJSONObject(String.valueOf(n));
            out.put(new JSONObject().put("page", n).put("title", p.optString("title")).put("by", p.optString("by")).put("at", p.optLong("at")));
        }
        return out;
    }

    /** A page; 100 is the index of every page. */
    public static JSONObject page(JSONObject cfg, int n) throws Studio.Bad, JSONException {
        if (n == INDEX) return index(cfg);
        JSONObject all = cfg.optJSONObject("teletext");
        JSONObject p = all == null ? null : all.optJSONObject(String.valueOf(n));
        if (p == null) throw new Studio.Bad("no_such_page", String.valueOf(n), 404);
        return p;
    }

    static JSONObject index(JSONObject cfg) throws JSONException {
        JSONArray lines = new JSONArray();
        List<Integer> ns = numbers(cfg);
        JSONObject all = cfg.optJSONObject("teletext");
        if (ns.isEmpty()) {
            lines.put(line("No pages yet.", "white")).put(line("", "white"))
                    .put(line("Agents publish them with", "white")).put(line("tv_teletext_publish {page: 101-899}", "cyan"));
        }
        for (int n : ns) {
            if (lines.length() >= ROWS) break;
            String t = all.getJSONObject(String.valueOf(n)).optString("title");
            StringBuilder s = new StringBuilder(t);
            while (s.length() < COLS - 4) s.append('.');
            lines.put(line(s.substring(0, COLS - 4) + n, n / 100 % 2 == 1 ? "yellow" : "cyan"));
        }
        return new JSONObject().put("page", INDEX).put("title", "INDEX").put("lines", lines).put("by", "OmarchyTV").put("at", 0);
    }

    private static JSONObject line(String text, String color) throws JSONException {
        return new JSONObject().put("text", text).put("color", color);
    }

    /** The next (dir 1) or previous (dir -1) page that exists, the index included, wrapping around. */
    public static int step(JSONObject cfg, int from, int dir) {
        List<Integer> ns = new ArrayList<>(numbers(cfg));
        ns.add(0, INDEX);
        int i = ns.indexOf(from);
        if (i < 0) {
            for (i = 0; i < ns.size() && ns.get(i) < from; i++) ;
            if (dir < 0) i--;
            else if (i >= ns.size()) i = 0;
            return ns.get(Math.floorMod(i, ns.size()));
        }
        return ns.get(Math.floorMod(i + dir, ns.size()));
    }
}
