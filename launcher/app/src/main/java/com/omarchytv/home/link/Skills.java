package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * v2.22 skills from the community, installed on the TV. A skill is data, never code: a skill.json with an id, a name,
 * an author, a version, the permissions it asks for, the widgets it puts on its own page (the existing widget types,
 * checked by HomeWidgets) and the instructions it gives an agent. The catalogue ships inside the app
 * (assets/skills/, reviewed by pull request); installing asks the owner on the TV, with the permissions spelled out;
 * removing takes its widgets and its page away. Installed skills live in the "skills" object of omarchytv.json.
 */
public final class Skills {
    public static final int MAX_WIDGETS = 16, MAX_INSTALLED = 32;
    public static final List<String> PERMISSIONS = Arrays.asList("widgets", "notify", "ha.read", "ha.control", "shift", "live");
    static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{1,39}");

    private Skills() {
    }

    /** The catalogue entry, cleaned: or HomeWidgets.Bad with what is wrong (a skill PR must pass this). */
    public static JSONObject check(JSONObject in) throws HomeWidgets.Bad, JSONException {
        String id = in.optString("id", "");
        if (!ID.matcher(id).matches()) throw new HomeWidgets.Bad("bad_skill", "id: a-z, 0-9, dashes, 2 to 40");
        String name = LinkServer.printable(in.optString("name", ""), 40), author = LinkServer.printable(in.optString("author", ""), 40);
        String version = in.optString("version", "");
        if (name.isEmpty() || author.isEmpty() || !version.matches("\\d{1,3}\\.\\d{1,3}(\\.\\d{1,3})?")) {
            throw new HomeWidgets.Bad("bad_skill", "name, author and version (1.0) are required");
        }
        JSONArray perms = in.optJSONArray("permissions"), clean = new JSONArray();
        for (int i = 0; perms != null && i < perms.length(); i++) {
            String p = perms.optString(i);
            if (!PERMISSIONS.contains(p)) throw new HomeWidgets.Bad("bad_skill", "permission " + p + ": one of " + PERMISSIONS);
            clean.put(p);
        }
        JSONArray ws = in.optJSONArray("widgets"), cw = new JSONArray();
        if (ws != null && ws.length() > MAX_WIDGETS) throw new HomeWidgets.Bad("bad_skill", MAX_WIDGETS + " widgets at most");
        boolean hasWidgets = false;
        for (int i = 0; perms != null && i < perms.length(); i++) if ("widgets".equals(perms.optString(i))) hasWidgets = true;
        if (ws != null && ws.length() > 0 && !hasWidgets) throw new HomeWidgets.Bad("bad_skill", "widgets need the \"widgets\" permission");
        for (int i = 0; ws != null && i < ws.length(); i++) {
            JSONObject w = ws.optJSONObject(i);
            if (w == null) throw new HomeWidgets.Bad("bad_skill", "widgets: objects");
            String t = w.optString("type");
            if (t.startsWith("homeassistant.") && !contains(clean, "ha.read")) throw new HomeWidgets.Bad("bad_skill", t + " needs ha.read");
            JSONArray probe = new JSONArray();   // the same checks as tv_widget_create
            JSONObject x = new JSONObject(w.toString());
            x.put("id", "probe");
            x.remove("page");
            HomeWidgets.create(probe, x, "skill", 0);
            cw.put(w);
        }
        return new JSONObject().put("id", id).put("name", name).put("author", author).put("version", version)
                .put("description", LinkServer.printable(in.optString("description", ""), 200))
                .put("license", LinkServer.printable(in.optString("license", ""), 20))
                .put("permissions", clean).put("widgets", cw)
                .put("page", LinkServer.printable(in.optString("page", name), 32))
                .put("agent", NightShift.multiline(in.optString("agent", ""), 2000, 40));
    }

    static boolean contains(JSONArray a, String s) {
        for (int i = 0; a != null && i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    public static JSONObject installed(JSONObject cfg) throws JSONException {
        JSONObject s = cfg.optJSONObject("skills");
        if (s == null) cfg.put("skills", s = new JSONObject());
        return s;
    }

    /** Puts the skill's widgets on a page of its own (created on demand), and remembers what it put there. */
    public static JSONObject install(JSONObject cfg, JSONObject skill, long now) throws HomeWidgets.Bad, JSONException {
        JSONObject sk = check(skill);
        String id = sk.getString("id");
        JSONObject inst = installed(cfg);
        if (inst.has(id)) remove(cfg, id);   // an update: the old widgets go first
        if (inst.length() >= MAX_INSTALLED) throw new HomeWidgets.Bad(409, "too_many", MAX_INSTALLED + " skills", null);
        JSONArray all = cfg.optJSONArray("widgets");
        if (all == null) cfg.put("widgets", all = new JSONArray());
        JSONArray ids = new JSONArray(), ws = sk.getJSONArray("widgets");
        int page = 0;
        if (ws.length() > 0) {
            page = HomePages.onDemand(cfg, "skill-" + id, false, now);
            HomePages.rename(cfg, page, sk.getString("page"), now);
        }
        for (int i = 0; i < ws.length(); i++) {
            JSONObject w = new JSONObject(ws.getJSONObject(i).toString());
            String wid = ("s." + id + "." + w.optString("id", String.valueOf(i))).toLowerCase(java.util.Locale.ROOT);
            if (wid.length() > 48) wid = wid.substring(0, 48);
            w.put("id", wid).put("page", page);
            HomeWidgets.create(all, w, "skill:" + id, now);
            ids.put(wid);
        }
        JSONObject rec = new JSONObject().put("version", sk.getString("version")).put("name", sk.getString("name"))
                .put("author", sk.getString("author")).put("permissions", sk.getJSONArray("permissions"))
                .put("widgets", ids).put("page", page).put("agent", sk.getString("agent")).put("at", now);
        inst.put(id, rec);
        return rec;
    }

    public static JSONObject remove(JSONObject cfg, String id) throws HomeWidgets.Bad, JSONException {
        JSONObject inst = installed(cfg), rec = inst.optJSONObject(id);
        if (rec == null) throw new HomeWidgets.Bad(404, "no_such_skill", id, null);
        JSONArray all = cfg.optJSONArray("widgets"), ids = rec.optJSONArray("widgets");
        for (int i = all == null ? -1 : all.length() - 1; i >= 0; i--) {
            if (contains(ids, all.getJSONObject(i).optString("id"))) all.remove(i);
        }
        int page = rec.optInt("page");
        if (page > 1 && page <= HomePages.total(cfg) && HomePages.count(cfg, page) == 0) HomePages.delete(cfg, page, null, 0);
        inst.remove(id);
        return rec;
    }
}
