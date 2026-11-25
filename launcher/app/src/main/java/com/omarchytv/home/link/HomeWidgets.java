package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * v2.22 (#157) positioned, typed Home widgets: the model, its validation and the placement rules, as pure functions on
 * the omarchytv.json "widgets" array so they can be unit-tested without a TV.
 *
 * A widget: {id, type, page, x, y, w, h, title, icon, ttl?, data, updatedAt, owner}. Each page is a grid of
 * {@link #COLS} × {@link #ROWS} cells on the 1920×1080 stage; widgets never overlap and never leave the grid.
 * Everything is text: every string goes through {@link LinkServer#printable}, nothing is ever rendered as HTML.
 */
public final class HomeWidgets {
    public static final int COLS = 12, ROWS = 6;
    public static final int MAX_PER_PAGE = 64, MAX_TOTAL = 2000, MAX_DATA = 16 * 1024, MAX_PAGE = 1000;
    static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,47}$");
    public static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "lines", "stat", "text", "image", "chart", "page", "shortcuts",
            "homeassistant.scenes", "homeassistant.entities", "radio", "qr"));
    /** Default size per type when the caller gives none. */
    static int[] defaultSize(String type) {
        switch (type) {
            case "stat": case "radio": return new int[]{2, 1};
            case "text": case "lines": case "shortcuts": case "qr": return new int[]{3, 2};
            case "chart": case "homeassistant.entities": return new int[]{4, 2};
            case "image": case "page": case "homeassistant.scenes": return new int[]{4, 3};
            default: return new int[]{3, 2};
        }
    }

    private HomeWidgets() {
    }

    /** Thrown with an API error code and a one-line message (400 unless {@link #status} says otherwise). */
    public static final class Bad extends Exception {
        public final String code;
        public final int status;
        public final JSONArray free;

        Bad(String code, String message) {
            this(400, code, message, null);
        }

        Bad(int status, String code, String message, JSONArray free) {
            super(message);
            this.status = status;
            this.code = code;
            this.free = free;
        }
    }

    // ---------------------------------------------------------------- data, per type

    /** The widget's data, checked and cleaned for its type. */
    static JSONObject cleanData(String type, JSONObject in) throws Bad, JSONException {
        if (in == null) in = new JSONObject();
        if (in.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DATA) throw new Bad("too_big", "data is over 16 KB");
        JSONObject out = new JSONObject();
        switch (type) {
            case "lines": {
                JSONArray ls = in.optJSONArray("lines"), o = new JSONArray();
                if (ls != null && ls.length() > 8) throw new Bad("too_many", "8 lines");
                for (int i = 0; ls != null && i < ls.length(); i++) {
                    JSONObject l = ls.optJSONObject(i);
                    if (l == null) continue;
                    JSONObject line = new JSONObject().put("label", LinkServer.printable(l.optString("label", ""), 16))
                            .put("value", LinkServer.printable(l.optString("value", ""), 64));
                    String tone = l.optString("tone", "");
                    if (LinkServer.LINE_TONES.contains(tone)) line.put("tone", tone);
                    o.put(line);
                }
                return out.put("lines", o);
            }
            case "stat": {
                out.put("value", LinkServer.printable(in.optString("value", ""), 16))
                        .put("unit", LinkServer.printable(in.optString("unit", ""), 8))
                        .put("label", LinkServer.printable(in.optString("label", ""), 32));
                String delta = LinkServer.printable(in.optString("delta", ""), 16);
                if (!delta.isEmpty()) out.put("delta", delta);
                String trend = in.optString("trend", "");
                if (trend.equals("up") || trend.equals("down") || trend.equals("flat")) out.put("trend", trend);
                return out;
            }
            case "text": {
                String t = multiline(in.optString("text", ""), 1024, 30);
                return out.put("text", t);
            }
            case "image": {
                String url = in.optString("url", "").trim();
                if (!webUrl(url)) throw new Bad("bad_url", "https://, or http:// to a LAN or tailnet IP address");
                String fit = "contain".equals(in.optString("fit")) ? "contain" : "cover";
                return out.put("url", url).put("fit", fit);
            }
            case "chart": {
                JSONArray ps = in.optJSONArray("points"), o = new JSONArray();
                if (ps == null || ps.length() == 0) throw new Bad("bad_data", "points: 1 to 120 numbers");
                if (ps.length() > 120) throw new Bad("too_many", "120 points");
                for (int i = 0; i < ps.length(); i++) {
                    double v = ps.optDouble(i, Double.NaN);
                    if (Double.isNaN(v) || Double.isInfinite(v)) throw new Bad("bad_data", "points must be numbers");
                    o.put(v);
                }
                out.put("points", o).put("kind", "bars".equals(in.optString("kind")) ? "bars" : "spark")
                        .put("label", LinkServer.printable(in.optString("label", ""), 32))
                        .put("unit", LinkServer.printable(in.optString("unit", ""), 8));
                return out;
            }
            case "page": {
                String url = in.optString("url", "").trim();
                if (!AppApi.lanUrl(url)) throw new Bad("not_lan_url", "http:// to a LAN or tailnet IP address");
                return out.put("url", url);
            }
            case "shortcuts": {
                JSONArray items = in.optJSONArray("items"), o = new JSONArray();
                if (items == null || items.length() == 0) throw new Bad("bad_data", "items: 1 to 16 shortcuts");
                if (items.length() > 16) throw new Bad("too_many", "16 shortcuts");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject s = items.optJSONObject(i);
                    if (s == null || !LinkServer.WIDGET_ID.matcher(s.optString("id", "")).matches()) throw new Bad("bad_data", "shortcut id");
                    JSONObject act = LinkServer.cleanAction(s.optJSONObject("action"));
                    if (act == null) throw new Bad("bad_data", "shortcut " + s.optString("id") + ": action");
                    o.put(new JSONObject().put("id", s.getString("id")).put("label", LinkServer.printable(s.optString("label", s.getString("id")), 16))
                            .put("icon", LinkServer.printable(s.optString("icon", ""), 16)).put("action", act));
                }
                return out.put("items", o);
            }
            case "homeassistant.scenes":
            case "homeassistant.entities": {
                Object raw = in.opt("items");
                if ("all".equals(raw) && type.equals("homeassistant.scenes")) out.put("items", "all");
                else {
                    JSONArray items = in.optJSONArray("items"), o = new JSONArray();
                    if (items == null || items.length() == 0) throw new Bad("bad_data", "items: entity ids");
                    if (items.length() > 48) throw new Bad("too_many", "48 items");
                    for (int i = 0; i < items.length(); i++) {
                        Object it = items.opt(i);
                        JSONObject e = it instanceof JSONObject ? (JSONObject) it : new JSONObject().put("entity", String.valueOf(it));
                        String ent = e.optString("entity", "");
                        if (!ENTITY.matcher(ent).matches()) throw new Bad("bad_data", "entity id like scene.movie");
                        JSONObject c = new JSONObject().put("entity", ent);
                        String label = LinkServer.printable(e.optString("label", ""), 24);
                        if (!label.isEmpty()) c.put("label", label);
                        String icon = LinkServer.printable(e.optString("icon", ""), 4);
                        if (!icon.isEmpty()) c.put("icon", icon);
                        if (e.optBoolean("confirm")) c.put("confirm", true);
                        o.put(c);
                    }
                    out.put("items", o);
                }
                out.put("columns", Math.max(1, Math.min(6, in.optInt("columns", 3))));
                return out;
            }
            case "radio":
                return out;
            case "qr": {
                String preset = in.optString("preset", "");
                if ("skill".equals(preset)) return out.put("preset", "skill");
                String url = in.optString("url", "").trim();
                if (!webUrl(url)) throw new Bad("bad_url", "preset \"skill\", or an https:// / LAN http:// url");
                return out.put("url", url).put("label", LinkServer.printable(in.optString("label", ""), 32));
            }
            default:
                throw new Bad("bad_type", "one of " + TYPES);
        }
    }

    static final Pattern ENTITY = Pattern.compile("^[a-z_]{1,32}\\.[a-z0-9_]{1,64}$");

    static boolean webUrl(String url) {
        if (url == null || url.length() > 512) return false;
        if (url.startsWith("https://")) return url.length() > 8 && url.indexOf(' ') < 0;
        return AppApi.lanUrl(url);
    }

    /** Plain text kept on several lines: at most maxLines lines, maxChars in all, each line printable. */
    static String multiline(String s, int maxChars, int maxLines) {
        String[] ls = s.replace("\r\n", "\n").split("\n", -1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ls.length && i < maxLines && b.length() < maxChars; i++) {
            if (i > 0) b.append('\n');
            b.append(LinkServer.printable(ls[i], maxChars - b.length()));
        }
        return b.toString().trim();
    }

    // ---------------------------------------------------------------- placement

    /** True if a w×h box at (x, y) on this page stays in the grid and overlaps no widget other than {@code except}. */
    public static boolean fits(JSONArray all, int page, int x, int y, int w, int h, String except) {
        if (x < 0 || y < 0 || w < 1 || h < 1 || x + w > COLS || y + h > ROWS) return false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null || o.optInt("page", 1) != page || o.optString("id").equals(except)) continue;
            if (overlap(x, y, w, h, o.optInt("x"), o.optInt("y"), o.optInt("w", 1), o.optInt("h", 1))) return false;
        }
        return true;
    }

    public static boolean overlap(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
    }

    /** The first slot (row-major) where a w×h box fits on this page, or null. */
    static int[] firstFree(JSONArray all, int page, int w, int h, String except) {
        for (int y = 0; y + h <= ROWS; y++) {
            for (int x = 0; x + w <= COLS; x++) if (fits(all, page, x, y, w, h, except)) return new int[]{x, y};
        }
        return null;
    }

    /** Up to 8 free slots for a w×h box on this page, as [{x, y}], for the 409 answer. */
    static JSONArray freeSlots(JSONArray all, int page, int w, int h, String except) throws JSONException {
        JSONArray out = new JSONArray();
        for (int y = 0; y + h <= ROWS && out.length() < 8; y++) {
            for (int x = 0; x + w <= COLS && out.length() < 8; x++) {
                if (fits(all, page, x, y, w, h, except)) out.put(new JSONObject().put("x", x).put("y", y));
            }
        }
        return out;
    }

    static int countOn(JSONArray all, int page) {
        int n = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optInt("page", 1) == page) n++;
        }
        return n;
    }

    public static int indexOf(JSONArray all, String id) {
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return i;
        }
        return -1;
    }

    /**
     * "push": every widget the box at (x, y, w, h) would cover moves to the first free slot on the same page, then to
     * the next pages. Returns the ids that moved. The box itself must already be in the grid.
     */
    static List<String> pushAside(JSONArray all, String movingId, int page, int x, int y, int w, int h) throws JSONException {
        List<String> moved = new ArrayList<>();
        List<JSONObject> hit = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null || o.optInt("page", 1) != page || o.optString("id").equals(movingId)) continue;
            if (overlap(x, y, w, h, o.optInt("x"), o.optInt("y"), o.optInt("w", 1), o.optInt("h", 1))) hit.add(o);
        }
        // reserve the box with a placeholder so the pushed widgets do not land in it
        JSONObject hold = new JSONObject().put("id", "\u0000hold").put("page", page).put("x", x).put("y", y).put("w", w).put("h", h);
        all.put(hold);
        try {
            for (JSONObject o : hit) {
                int ow = o.optInt("w", 1), oh = o.optInt("h", 1);
                o.put("page", -1);   // out of the way while searching
                int p = page;
                int[] slot = null;
                while (slot == null && p <= MAX_PAGE) {
                    if (countOn(all, p) < MAX_PER_PAGE) slot = firstFree(all, p, ow, oh, o.optString("id"));
                    if (slot == null) p++;
                }
                if (slot == null) throw new JSONException("no room");
                o.put("page", p).put("x", slot[0]).put("y", slot[1]);
                moved.add(o.optString("id"));
            }
        } finally {
            all.remove(all.length() - 1);
        }
        return moved;
    }

    // ---------------------------------------------------------------- operations on the array

    /** Creates a widget in {@code all}; returns it. {@code owner}: "pc" or "app:<token name>". */
    static JSONObject create(JSONArray all, JSONObject in, String owner, long now) throws Bad, JSONException {
        String id = in.optString("id", "");
        if (!ID.matcher(id).matches()) throw new Bad("bad_id", "a-z, 0-9, . _ -, up to 48");
        if (indexOf(all, id) >= 0) throw new Bad(409, "exists", "a widget with this id exists; use tv_widget_update or tv_widget_move", null);
        if (all.length() >= MAX_TOTAL) throw new Bad(409, "too_many", MAX_TOTAL + " widgets", null);
        String type = in.optString("type", "");
        if (!TYPES.contains(type)) throw new Bad("bad_type", "one of " + TYPES);
        int page = in.has("page") ? in.optInt("page", 0) : 1;
        if (page < 1 || page > MAX_PAGE) throw new Bad("bad_page", "1 to " + MAX_PAGE);
        if (countOn(all, page) >= MAX_PER_PAGE) throw new Bad(409, "page_full", MAX_PER_PAGE + " widgets per page", null);
        int[] d = defaultSize(type);
        int w = in.has("w") ? in.optInt("w", 0) : d[0], h = in.has("h") ? in.optInt("h", 0) : d[1];
        if (w < 1 || w > COLS || h < 1 || h > ROWS) throw new Bad("bad_size", "w 1-" + COLS + ", h 1-" + ROWS);
        JSONObject data = cleanData(type, in.optJSONObject("data"));
        int x, y;
        if (in.has("x") || in.has("y")) {
            x = in.optInt("x", 0);
            y = in.optInt("y", 0);
            place(all, id, page, x, y, w, h, in.optBoolean("push"));
        } else {
            int[] slot = firstFree(all, page, w, h, null);
            if (slot == null) throw new Bad(409, "cell_taken", "no free " + w + "×" + h + " slot on page " + page, freeSlots(all, page, 1, 1, null));
            x = slot[0];
            y = slot[1];
        }
        JSONObject wd = new JSONObject().put("id", id).put("type", type).put("page", page).put("x", x).put("y", y).put("w", w).put("h", h)
                .put("title", LinkServer.printable(in.optString("title", ""), 48)).put("icon", LinkServer.printable(in.optString("icon", ""), 16))
                .put("data", data).put("updatedAt", now).put("owner", owner);
        if (in.has("ttl")) wd.put("ttl", Math.max(10, Math.min(86_400, in.optInt("ttl", 180))));
        all.put(wd);
        return wd;
    }

    /** Checks that (x, y, w, h) is free on the page, or pushes the others aside when {@code push}. */
    private static void place(JSONArray all, String id, int page, int x, int y, int w, int h, boolean push) throws Bad, JSONException {
        if (x < 0 || y < 0 || x + w > COLS || y + h > ROWS) {
            throw new Bad(409, "cell_taken", "outside the " + COLS + "×" + ROWS + " grid", freeSlots(all, page, w, h, id));
        }
        if (fits(all, page, x, y, w, h, id)) return;
        if (!push) throw new Bad(409, "cell_taken", "another widget is there; pass push:true or use a free slot", freeSlots(all, page, w, h, id));
        try {
            pushAside(all, id, page, x, y, w, h);
        } catch (JSONException e) {
            throw new Bad(409, "cell_taken", "no room to push the other widgets", freeSlots(all, page, w, h, id));
        }
    }

    /** Content only (title, icon, ttl, data): what a notify token may change. */
    public static JSONObject update(JSONArray all, String id, JSONObject in, long now) throws Bad, JSONException {
        int i = indexOf(all, id);
        if (i < 0) throw new Bad(404, "no_such_widget", id, null);
        JSONObject wd = all.getJSONObject(i);
        if (in.has("title")) wd.put("title", LinkServer.printable(in.optString("title", ""), 48));
        if (in.has("icon")) wd.put("icon", LinkServer.printable(in.optString("icon", ""), 16));
        if (in.has("ttl")) wd.put("ttl", Math.max(10, Math.min(86_400, in.optInt("ttl", 180))));
        if (in.has("data")) wd.put("data", cleanData(wd.optString("type"), in.optJSONObject("data")));
        return wd.put("updatedAt", now);
    }

    /** Position and shape (page, x, y, w, h). */
    public static JSONObject move(JSONArray all, String id, JSONObject in, long now) throws Bad, JSONException {
        int i = indexOf(all, id);
        if (i < 0) throw new Bad(404, "no_such_widget", id, null);
        JSONObject wd = all.getJSONObject(i);
        int page = in.has("page") ? in.optInt("page", 0) : wd.optInt("page", 1);
        int w = in.has("w") ? in.optInt("w", 0) : wd.optInt("w", 1), h = in.has("h") ? in.optInt("h", 0) : wd.optInt("h", 1);
        if (page < 1 || page > MAX_PAGE) throw new Bad("bad_page", "1 to " + MAX_PAGE);
        if (w < 1 || w > COLS || h < 1 || h > ROWS) throw new Bad("bad_size", "w 1-" + COLS + ", h 1-" + ROWS);
        if (page != wd.optInt("page", 1) && countOn(all, page) >= MAX_PER_PAGE) throw new Bad(409, "page_full", MAX_PER_PAGE + " widgets per page", null);
        int x, y;
        if (in.has("x") || in.has("y")) {
            x = in.has("x") ? in.optInt("x", 0) : wd.optInt("x");
            y = in.has("y") ? in.optInt("y", 0) : wd.optInt("y");
        } else if (page != wd.optInt("page", 1)) {
            int[] slot = firstFree(all, page, w, h, id);
            if (slot == null) throw new Bad(409, "cell_taken", "no free " + w + "×" + h + " slot on page " + page, freeSlots(all, page, 1, 1, id));
            x = slot[0];
            y = slot[1];
        } else {
            x = wd.optInt("x");
            y = wd.optInt("y");
        }
        place(all, id, page, x, y, w, h, in.optBoolean("push"));
        return wd.put("page", page).put("x", x).put("y", y).put("w", w).put("h", h).put("updatedAt", now);
    }

    public static boolean delete(JSONArray all, String id) {
        int i = indexOf(all, id);
        if (i < 0) return false;
        all.remove(i);
        return true;
    }

    /** The widgets on one page (all pages when page is 0), in reading order (y, then x). */
    public static JSONArray list(JSONArray all, int page) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && (page == 0 || o.optInt("page", 1) == page)) out.add(o);
        }
        out.sort((a, b) -> a.optInt("page", 1) != b.optInt("page", 1) ? a.optInt("page", 1) - b.optInt("page", 1)
                : a.optInt("y") != b.optInt("y") ? a.optInt("y") - b.optInt("y") : a.optInt("x") - b.optInt("x"));
        return new JSONArray(out);
    }

    /**
     * #157: the PC's pushed panes (PUT /v1/widgets, link.panes) as read-only "lines" widgets on page 1, placed in the
     * free cells after the real widgets. Their ids are "pane:<pane id>"; they are not stored in "widgets".
     */
    static JSONArray paneWidgets(JSONArray all, JSONArray panes) throws JSONException {
        JSONArray out = new JSONArray();
        if (panes == null) return out;
        JSONArray occupied = new JSONArray();
        for (int i = 0; i < all.length(); i++) occupied.put(all.get(i));
        for (int i = 0; i < panes.length(); i++) {
            JSONObject p = panes.optJSONObject(i);
            if (p == null) continue;
            int[] slot = firstFree(occupied, 1, 3, 2, null);
            if (slot == null) slot = firstFree(occupied, 1, 2, 1, null);
            if (slot == null) break;
            int w = fits(occupied, 1, slot[0], slot[1], 3, 2, null) ? 3 : 2, h = w == 3 ? 2 : 1;
            JSONObject wd = new JSONObject().put("id", "pane:" + p.optString("id")).put("type", "lines").put("page", 1)
                    .put("x", slot[0]).put("y", slot[1]).put("w", w).put("h", h).put("title", p.optString("title"))
                    .put("icon", p.optString("icon", "")).put("ttl", p.optInt("ttl", 180))
                    .put("data", new JSONObject().put("lines", p.optJSONArray("lines") != null ? p.optJSONArray("lines") : new JSONArray()))
                    .put("owner", "pc").put("virtual", true);
            if (p.has("at")) wd.put("updatedAt", p.optLong("at"));
            occupied.put(wd);
            out.put(wd);
        }
        return out;
    }

    /** The pages that hold widgets, ascending; page 1 is always there once any widget exists. */
    public static java.util.List<Integer> pages(JSONArray all) {
        java.util.TreeSet<Integer> p = new java.util.TreeSet<>();
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null) p.add(Math.max(1, o.optInt("page", 1)));
        }
        if (!p.isEmpty()) p.add(1);
        return new ArrayList<>(p);
    }

    /** GET /v1/widgets/types: the types with the shape of their data (a short JSON schema each). */
    static JSONObject types() throws JSONException {
        JSONObject t = new JSONObject();
        t.put("lines", schema("{lines: [{label ≤16, value ≤64, tone?: up|down}] ≤8}"));
        t.put("stat", schema("{value ≤16, unit? ≤8, label? ≤32, delta? ≤16, trend?: up|down|flat}"));
        t.put("text", schema("{text: plain text ≤1024 chars, ≤30 lines}"));
        t.put("image", schema("{url: https:// or LAN http://, PNG/JPEG ≤512 KB, fit?: cover|contain}"));
        t.put("chart", schema("{points: [numbers] 1-120, kind?: spark|bars, label?, unit?}"));
        t.put("page", schema("{url: http:// to a LAN or tailnet IP address}"));
        t.put("shortcuts", schema("{items: [{id, label, icon?, action: {type: app|view|pc, …}}] ≤16}"));
        t.put("homeassistant.scenes", schema("{items: \"all\" | [scene.x | script.x | {entity, label?, icon?, confirm?}] ≤48, columns? 1-6}"));
        t.put("homeassistant.entities", schema("{items: [entity ids or {entity, label?}] ≤48, columns? 1-6}"));
        t.put("radio", schema("{} (the radio mini player)"));
        t.put("qr", schema("{preset: \"skill\"} or {url: https:// / LAN http://, label?}"));
        return new JSONObject().put("grid", new JSONObject().put("cols", COLS).put("rows", ROWS)).put("types", t)
                .put("limits", new JSONObject().put("perPage", MAX_PER_PAGE).put("total", MAX_TOTAL).put("dataBytes", MAX_DATA).put("pages", MAX_PAGE));
    }

    private static JSONObject schema(String data) throws JSONException {
        return new JSONObject().put("data", data);
    }
}
