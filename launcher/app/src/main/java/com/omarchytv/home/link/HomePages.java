package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * v2.22 (#158 / #159) Home widget pages: as many as needed (up to {@link HomeWidgets#MAX_PAGE}), with optional
 * names, kept in omarchytv.json "widgetPages" [{index, id, name, rotate?, hidden?, touched}]. The widgets carry their
 * page index; a page with widgets but no entry is simply "Page N". Pure functions, unit-tested without a TV.
 */
public final class HomePages {
    static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
    /** An empty page other than page 1 goes away by itself after a day. */
    static final long EMPTY_TTL_S = 24 * 3600;

    private HomePages() {
    }

    /** v2.22 #158: published LAN pages (tv_publish_page), no longer stopped at 16. */
    public static final int MAX_AGENT_PAGES = 200;

    /** The widget page on the Home now, set by the Home (0 = the classic Home panels). */
    public static volatile int shown;

    /** The number of pages: the highest page in use by a widget or named, at least 1. */
    public static int total(JSONObject cfg) {
        int n = 1;
        JSONArray w = cfg.optJSONArray("widgets"), p = cfg.optJSONArray("widgetPages");
        for (int i = 0; w != null && i < w.length(); i++) {
            JSONObject o = w.optJSONObject(i);
            if (o != null) n = Math.max(n, o.optInt("page", 1));
        }
        for (int i = 0; p != null && i < p.length(); i++) {
            JSONObject o = p.optJSONObject(i);
            if (o != null) n = Math.max(n, o.optInt("index", 1));
        }
        return Math.min(n, HomeWidgets.MAX_PAGE);
    }

    static JSONObject meta(JSONObject cfg, int index) {
        JSONArray p = cfg.optJSONArray("widgetPages");
        for (int i = 0; p != null && i < p.length(); i++) {
            JSONObject o = p.optJSONObject(i);
            if (o != null && o.optInt("index") == index) return o;
        }
        return null;
    }

    private static JSONObject metaOrNew(JSONObject cfg, int index, long now) throws JSONException {
        JSONObject m = meta(cfg, index);
        if (m != null) return m;
        JSONArray p = cfg.optJSONArray("widgetPages");
        if (p == null) cfg.put("widgetPages", p = new JSONArray());
        m = new JSONObject().put("index", index).put("touched", now);
        p.put(m);
        return m;
    }

    public static String name(JSONObject cfg, int index) {
        JSONObject m = meta(cfg, index);
        String n = m != null ? m.optString("name", "") : "";
        return n.isEmpty() ? "Page " + index : n;
    }

    /** A page reference (index, or id / name as a string) → its index, or -1. */
    public static int resolve(JSONObject cfg, Object ref) {
        if (ref instanceof Number) return ((Number) ref).intValue();
        String s = ref == null ? "" : String.valueOf(ref).trim();
        if (s.matches("\\d{1,4}")) return Integer.parseInt(s);
        JSONArray p = cfg.optJSONArray("widgetPages");
        for (int i = 0; p != null && i < p.length(); i++) {
            JSONObject o = p.optJSONObject(i);
            if (o != null && (s.equals(o.optString("id")) || s.equalsIgnoreCase(o.optString("name")))) return o.optInt("index");
        }
        return -1;
    }

    /**
     * #158 "pages are created on demand": the index a widget may use for {@code ref}. An unknown id creates a page
     * with that id at the end; a number past the end becomes the next page (contiguous), unless strict.
     */
    public static int onDemand(JSONObject cfg, Object ref, boolean strict, long now) throws HomeWidgets.Bad, JSONException {
        int total = total(cfg);
        int idx = resolve(cfg, ref);
        if (idx < 0) {
            String id = String.valueOf(ref).trim();
            if (!ID.matcher(id).matches()) throw new HomeWidgets.Bad("bad_page", "a page number or id (a-z, 0-9, dashes)");
            if (strict) throw new HomeWidgets.Bad(404, "no_such_page", id, null);
            if (total >= HomeWidgets.MAX_PAGE) throw new HomeWidgets.Bad(409, "too_many_pages", HomeWidgets.MAX_PAGE + " pages", null);
            idx = total + 1;
            metaOrNew(cfg, idx, now).put("id", id).put("name", id);
            return idx;
        }
        if (idx < 1) throw new HomeWidgets.Bad("bad_page", "1 to " + HomeWidgets.MAX_PAGE);
        if (idx > total) {
            if (strict) throw new HomeWidgets.Bad(404, "no_such_page", String.valueOf(idx), null);
            idx = Math.min(total + 1, HomeWidgets.MAX_PAGE);
        }
        return idx;
    }

    /** tv_page_list: index, id, name, widgets, hidden, rotate for every page (empty ones older than a day dropped). */
    public static JSONArray list(JSONObject cfg, long now) throws JSONException {
        cleanup(cfg, now);
        JSONArray out = new JSONArray();
        int total = total(cfg);
        for (int i = 1; i <= total; i++) {
            JSONObject m = meta(cfg, i);
            out.put(new JSONObject().put("index", i).put("id", m != null ? m.optString("id", "") : (i == 1 ? "home" : ""))
                    .put("name", name(cfg, i)).put("widgets", count(cfg, i))
                    .put("hidden", m != null && m.optBoolean("hidden")).put("rotate", m != null && m.optBoolean("rotate")));
        }
        return out;
    }

    static int count(JSONObject cfg, int index) {
        JSONArray w = cfg.optJSONArray("widgets");
        return w == null ? 0 : HomeWidgets.countOn(w, index);
    }

    /** tv_page_create {name?, id?, after?}: a new empty page, after page {@code after} (default: at the end). */
    public static int create(JSONObject cfg, JSONObject in, long now) throws HomeWidgets.Bad, JSONException {
        int total = total(cfg);
        if (total >= HomeWidgets.MAX_PAGE) throw new HomeWidgets.Bad(409, "too_many_pages", HomeWidgets.MAX_PAGE + " pages", null);
        String id = in.optString("id", "").trim(), name = LinkServer.printable(in.optString("name", ""), 32);
        if (!id.isEmpty() && !ID.matcher(id).matches()) throw new HomeWidgets.Bad("bad_id", "a-z, 0-9, dashes, up to 40");
        if (!id.isEmpty() && resolve(cfg, id) > 0) throw new HomeWidgets.Bad(409, "exists", "a page with this id exists", null);
        int at = total + 1;
        if (in.has("after")) {
            int after = resolve(cfg, in.opt("after"));
            if (after < 1 || after > total) throw new HomeWidgets.Bad(404, "no_such_page", String.valueOf(in.opt("after")), null);
            at = after + 1;
            shift(cfg, at, total, 1);   // pages at..total move one to the right
        }
        JSONObject m = metaOrNew(cfg, at, now);
        if (!id.isEmpty()) m.put("id", id);
        if (!name.isEmpty()) m.put("name", name);
        m.put("touched", now);
        return at;
    }

    /** Pages from..to (and their widgets) move by d. */
    private static void shift(JSONObject cfg, int from, int to, int d) throws JSONException {
        JSONArray w = cfg.optJSONArray("widgets"), p = cfg.optJSONArray("widgetPages");
        for (int i = 0; w != null && i < w.length(); i++) {
            JSONObject o = w.optJSONObject(i);
            if (o != null && o.optInt("page", 1) >= from && o.optInt("page", 1) <= to) o.put("page", o.optInt("page", 1) + d);
        }
        for (int i = 0; p != null && i < p.length(); i++) {
            JSONObject o = p.optJSONObject(i);
            if (o != null && o.optInt("index") >= from && o.optInt("index") <= to) o.put("index", o.optInt("index") + d);
        }
    }

    /** Renumbers page a to b (and b to a), widgets included. */
    private static void swap(JSONObject cfg, int a, int b) throws JSONException {
        JSONArray w = cfg.optJSONArray("widgets"), p = cfg.optJSONArray("widgetPages");
        for (int i = 0; w != null && i < w.length(); i++) {
            JSONObject o = w.optJSONObject(i);
            if (o == null) continue;
            int pg = o.optInt("page", 1);
            if (pg == a) o.put("page", b);
            else if (pg == b) o.put("page", a);
        }
        for (int i = 0; p != null && i < p.length(); i++) {
            JSONObject o = p.optJSONObject(i);
            if (o == null) continue;
            int pg = o.optInt("index");
            if (pg == a) o.put("index", b);
            else if (pg == b) o.put("index", a);
        }
    }

    public static void rename(JSONObject cfg, Object ref, String name, long now) throws HomeWidgets.Bad, JSONException {
        int idx = existing(cfg, ref);
        String n = LinkServer.printable(name == null ? "" : name, 32);
        if (n.isEmpty()) throw new HomeWidgets.Bad("bad_name", "1 to 32 characters");
        metaOrNew(cfg, idx, now).put("name", n).put("touched", now);
    }

    /** Moves page {@code ref} to position {@code to} (1..total); page 1 stays the Home and cannot move. */
    public static int reorder(JSONObject cfg, Object ref, int to, long now) throws HomeWidgets.Bad, JSONException {
        int idx = existing(cfg, ref), total = total(cfg);
        if (idx == 1 || to == 1) throw new HomeWidgets.Bad("home_page", "page 1 is the Home and stays first");
        if (to < 2 || to > total) throw new HomeWidgets.Bad("bad_page", "to: 2 to " + total);
        while (idx < to) {
            swap(cfg, idx, idx + 1);
            idx++;
        }
        while (idx > to) {
            swap(cfg, idx, idx - 1);
            idx--;
        }
        return to;
    }

    /** Removes a page (never page 1); its widgets go to {@code moveTo} (first free slots) or are removed with it. */
    public static int delete(JSONObject cfg, Object ref, Object moveTo, long now) throws HomeWidgets.Bad, JSONException {
        int idx = existing(cfg, ref), total = total(cfg);
        if (idx == 1) throw new HomeWidgets.Bad("home_page", "page 1 is the Home and cannot be removed");
        JSONArray w = cfg.optJSONArray("widgets");
        int dest = moveTo == null || JSONObject.NULL.equals(moveTo) ? -1 : existing(cfg, moveTo);
        if (dest == idx) throw new HomeWidgets.Bad("bad_page", "moveTo is the page being removed");
        int moved = 0;
        for (int i = w == null ? -1 : w.length() - 1; i >= 0; i--) {
            JSONObject o = w.optJSONObject(i);
            if (o == null || o.optInt("page", 1) != idx) continue;
            if (dest > 0) {
                o.put("page", -1);
                int[] slot = HomeWidgets.firstFree(w, dest, o.optInt("w", 1), o.optInt("h", 1), o.optString("id"));
                if (slot == null || HomeWidgets.countOn(w, dest) >= HomeWidgets.MAX_PER_PAGE) {
                    o.put("page", idx);
                    throw new HomeWidgets.Bad(409, "cell_taken", "no room on page " + dest + " for " + o.optString("id"), null);
                }
                o.put("page", dest).put("x", slot[0]).put("y", slot[1]);
                moved++;
            } else {
                w.remove(i);
            }
        }
        JSONArray p = cfg.optJSONArray("widgetPages");
        for (int i = p == null ? -1 : p.length() - 1; i >= 0; i--) {
            JSONObject o = p.optJSONObject(i);
            if (o != null && o.optInt("index") == idx) p.remove(i);
        }
        shift(cfg, idx + 1, total, -1);
        return moved;
    }

    private static int existing(JSONObject cfg, Object ref) throws HomeWidgets.Bad {
        int idx = resolve(cfg, ref);
        if (idx < 1 || idx > total(cfg)) throw new HomeWidgets.Bad(404, "no_such_page", String.valueOf(ref), null);
        return idx;
    }

    /** Empty pages other than page 1, untouched for a day, go away (the later ones move down). */
    public static int cleanup(JSONObject cfg, long now) throws JSONException {
        int removed = 0;
        for (int i = total(cfg); i >= 2; i--) {
            if (count(cfg, i) > 0) continue;
            JSONObject m = meta(cfg, i);
            long touched = m != null ? m.optLong("touched", 0) : 0;
            if (now - touched < EMPTY_TTL_S) continue;
            try {
                delete(cfg, i, null, now);
                removed++;
            } catch (HomeWidgets.Bad ignored) {
                // cannot happen for an empty page
            }
        }
        return removed;
    }

    /** Marks a page as used now (a widget landed on it), so it is not cleaned up as empty. */
    static void touch(JSONObject cfg, int index, long now) throws JSONException {
        JSONObject m = meta(cfg, index);
        if (m != null) m.put("touched", now);
    }

    // ---------------------------------------------------------------- #159 the navigator window

    /**
     * The navigator's window of page numbers: {first, last, prevEnabled ? 1 : 0, nextEnabled ? 1 : 0}. Windows are
     * 1–5, 6–10, …; the current page is always inside. With total ≤ size there are no arrows (both 0).
     */
    public static int[] window(int current, int total, int size) {
        if (total <= 0) return new int[]{0, 0, 0, 0};
        int cur = Math.max(1, Math.min(total, current));
        int first = ((cur - 1) / size) * size + 1, last = Math.min(total, first + size - 1);
        if (total <= size) return new int[]{1, total, 0, 0};
        return new int[]{first, last, first > 1 ? 1 : 0, last < total ? 1 : 0};
    }

    /** The visible pages (not hidden), ascending: what the navigator and CH+/CH- go through. */
    public static List<Integer> visible(JSONObject cfg) {
        List<Integer> out = new ArrayList<>();
        int total = total(cfg);
        for (int i = 1; i <= total; i++) {
            JSONObject m = meta(cfg, i);
            if (i == 1 || m == null || !m.optBoolean("hidden")) out.add(i);
        }
        return out;
    }
}
