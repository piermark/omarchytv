package com.omarchytv.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GrokBot Office (v2.20, optional, off by default): the rules behind the office, with no Android in them (JVM-tested
 * by OfficeTest; the same rules as webos/app/office.js). The bots and the weekly usage are only what the PC pushed
 * (omarchy-tv office push: PUT /v1/office, or adb push of omarchytv-office.json) or what a Grok Bot diretto reply
 * carried in its "office" field. Nothing is made up: no bots, an empty office; no usage number, no usage card.
 * 2.20 multi-provider: each bot has a provider tag (grok by default; claude, chatgpt, ...), PC assistants hired for
 * Claude / ChatGPT carry their assistant id, and "providers_usage" is the real spend per provider (Admin APIs on the PC).
 */
public final class Office {
    static final int MAX_BOTS = 24, SHOWN = 16;
    /** 30 frames a second at most. */
    static final long FRAME_MS = 33;
    static final String FILE_NAME = "omarchytv-office.json";
    static final long PENDING_TTL_MS = 24L * 3600 * 1000;
    static final String[] PALETTE = {"#f7768e", "#ff9e64", "#e0af68", "#9ece6a", "#73daca", "#7dcfff", "#7aa2f7", "#bb9af7", "#c0caf5", "#ff79c6", "#50fa7b"};
    private static final Map<String, String> NAMED = new HashMap<>();
    private static final List<String> STATUS = Arrays.asList("working", "idle", "done", "error");

    static {
        String[][] n = {{"red", "#f7768e"}, {"orange", "#ff9e64"}, {"yellow", "#e0af68"}, {"green", "#9ece6a"}, {"teal", "#73daca"}, {"cyan", "#7dcfff"},
                {"blue", "#7aa2f7"}, {"purple", "#bb9af7"}, {"violet", "#bb9af7"}, {"pink", "#ff79c6"}, {"white", "#c0caf5"}, {"lime", "#50fa7b"},
                {"grey", "#a9b1d6"}, {"gray", "#a9b1d6"}};
        for (String[] p : n) NAMED.put(p[0], p[1]);
    }

    private Office() {
    }

    static final class Bot {
        final String id, name, role, color, status, task;
        /** Which AI: grok (default), claude, chatgpt, or another short word. Only a tag; never routes anything by itself. */
        final String provider;
        /** A PC assistant id (omarchy-link) when the bot lives on the PC; "" otherwise. */
        final String assistant;

        Bot(String id, String name, String role, String color, String status, String task) {
            this(id, name, role, color, status, task, "grok", "");
        }

        Bot(String id, String name, String role, String color, String status, String task, String provider, String assistant) {
            this.id = id;
            this.name = name;
            this.role = role;
            this.color = color;
            this.status = status;
            this.task = task;
            this.provider = provider;
            this.assistant = assistant;
        }
    }

    /** One provider's real spend (providers_usage): pct only with a budget. */
    static final class Spend {
        final String provider, unit, label, resets;
        final double spent, budget, pct;

        Spend(String provider, double spent, String unit, String label, double budget, String resets) {
            this.provider = provider;
            this.spent = spent;
            this.unit = unit;
            this.label = label;
            this.budget = budget;
            this.pct = budget > 0 ? Math.max(0, Math.min(100, Math.round(spent / budget * 1000) / 10.0)) : -1;
            this.resets = resets;
        }
    }

    static final class Usage {
        final double pct;
        final String label, resets;

        Usage(double pct, String label, String resets) {
            this.pct = pct;
            this.label = label;
            this.resets = resets;
        }
    }

    static final class Data {
        final String title;
        final List<Bot> bots;
        final Usage usage;
        final List<Spend> spend;

        Data(String title, List<Bot> bots, Usage usage) {
            this(title, bots, usage, new ArrayList<>());
        }

        Data(String title, List<Bot> bots, Usage usage, List<Spend> spend) {
            this.title = title;
            this.bots = bots;
            this.usage = usage;
            this.spend = spend;
        }

        static Data empty() {
            return new Data("", new ArrayList<>(), null);
        }
    }

    /** A hire sent to Grok Bot and not confirmed yet: it walks in and waits with a "pending" tag. */
    static final class Pending {
        final String name, role, provider;
        final long at;
        final int before;

        Pending(String name, String role, long at, int before) {
            this(name, role, at, before, "grok");
        }

        Pending(String name, String role, long at, int before, String provider) {
            this.name = name == null ? "" : name;
            this.role = role == null ? "" : role;
            this.at = at;
            this.before = before;
            this.provider = provider == null || provider.isEmpty() ? "grok" : provider;
        }
    }

    // ---- data ----

    static String clean(Object o, int max) {
        String s = o == null || o == JSONObject.NULL ? "" : String.valueOf(o);
        s = s.replaceAll("[\\u0000-\\u001F\\u007F<>]", "").replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String slug(String s) {
        String v = (s == null ? "" : s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return v.length() > 40 ? v.substring(0, 40) : v;
    }

    /** The same 32-bit hash as office.js (so a name gets the same colour on every TV). */
    static long hash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = h * 31 + s.charAt(i);
        return Math.abs((long) h);
    }

    /** "#rrggbb" or a colour name; anything else gets a palette colour picked from the name (stable). */
    static String colorOf(Object c, String name) {
        if (c instanceof String) {
            String v = ((String) c).trim().toLowerCase(Locale.ROOT);
            if (v.matches("#[0-9a-f]{6}")) return v;
            if (v.matches("#[0-9a-f]{3}")) return "#" + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2) + v.charAt(3) + v.charAt(3);
            if (NAMED.containsKey(v)) return NAMED.get(v);
        }
        return PALETTE[(int) (hash(name == null ? "" : name) % PALETTE.length)];
    }

    static Bot bot(Object o) {
        if (!(o instanceof JSONObject)) return null;
        JSONObject b = (JSONObject) o;
        String name = clean(b.has("name") ? b.opt("name") : b.opt("shortName"), 24);
        if (name.isEmpty()) return null;
        String id = slug(clean(b.opt("id"), 80));
        if (id.isEmpty()) id = slug(name);
        if (id.isEmpty()) id = "bot";
        String st = clean(b.opt("status"), 10).toLowerCase(Locale.ROOT);
        Object col = b.has("color") ? b.opt("color") : b.opt("fill");
        String as = clean(b.opt("assistant"), 64).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        return new Bot(id, name, clean(b.opt("role"), 40), colorOf(col, name), STATUS.contains(st) ? st : "idle", clean(b.opt("task"), 60),
                provider(b.opt("provider")), as.length() > 32 ? as.substring(0, 32) : as);
    }

    /** A short lowercase word; "grok" when missing (the same rule as office.js and the PC plugin). */
    static String provider(Object v) {
        String p = (v == null || v == JSONObject.NULL ? "" : String.valueOf(v)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "");
        if (p.length() > 16) p = p.substring(0, 16);
        return p.isEmpty() ? "grok" : p;
    }

    /** The tag colour: generic, no logos. */
    static String providerColor(String p) {
        switch (p == null ? "" : p) {
            case "grok": return "#7aa2f7";
            case "claude": case "anthropic": return "#ff9e64";
            case "chatgpt": case "openai": return "#9ece6a";
            case "gemini": case "google": return "#bb9af7";
            default: return "#a9b1d6";
        }
    }

    /** A PC assistant's family from its id or announced tag (claude-code, codex, office-x with provider claude, ...). */
    static String family(String tag, String id) {
        String s = ((tag == null ? "" : tag) + " " + (id == null ? "" : id)).toLowerCase(Locale.ROOT);
        if (tag != null && !tag.isEmpty()) return provider(tag);
        if (s.contains("claude") || s.contains("anthropic")) return "claude";
        if (s.contains("codex") || s.contains("openai") || s.contains("gpt")) return "chatgpt";
        if (s.contains("grok") || s.contains("xai")) return "grok";
        return provider(id);
    }

    static Spend spend(Object o) {
        if (!(o instanceof JSONObject)) return null;
        JSONObject u = (JSONObject) o;
        Object v = u.opt("spent");
        if (!(v instanceof Number) || v instanceof Boolean) return null;
        double sp = ((Number) v).doubleValue();
        if (Double.isNaN(sp) || Double.isInfinite(sp) || sp < 0) return null;
        Object b = u.opt("budget");
        double bud = b instanceof Number && !(b instanceof Boolean) && ((Number) b).doubleValue() > 0 ? Math.round(((Number) b).doubleValue() * 100) / 100.0 : 0;
        String unit = clean(u.opt("unit"), 4);
        return new Spend(provider(u.opt("provider")), Math.round(sp * 100) / 100.0, unit.isEmpty() ? "$" : unit, clean(u.opt("label"), 24), bud, clean(u.opt("resets"), 32));
    }

    static List<Spend> spends(JSONArray a) {
        List<Spend> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && out.size() < 6; i++) {
            Spend r = spend(a.opt(i));
            if (r != null) out.add(r);
        }
        return out;
    }

    static Usage usage(Object o) {
        if (!(o instanceof JSONObject)) return null;
        JSONObject u = (JSONObject) o;
        Object v = u.has("weekly_pct") ? u.opt("weekly_pct") : u.has("weeklyPct") ? u.opt("weeklyPct") : u.opt("pct");
        double p;
        if (v instanceof Number) p = ((Number) v).doubleValue();
        else if (v instanceof String && ((String) v).matches("\\s*\\d+(\\.\\d+)?\\s*%?\\s*")) p = Double.parseDouble(((String) v).replace("%", "").trim());
        else return null;
        if (Double.isNaN(p) || p < 0 || p > 100) return null;
        return new Usage(Math.round(p * 10) / 10.0, clean(u.opt("label"), 24), clean(u.has("resets") ? u.opt("resets") : u.opt("resets_at"), 32));
    }

    /** Untrusted JSON -> data; never throws. */
    static Data parse(String json) {
        try {
            return parse(new JSONObject(json));
        } catch (Exception e) {
            return Data.empty();
        }
    }

    static Data parse(JSONObject j) {
        if (j == null) return Data.empty();
        List<Bot> bots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JSONArray a = j.optJSONArray("bots");
        for (int i = 0; a != null && i < a.length() && bots.size() < MAX_BOTS; i++) {
            Bot b = bot(a.opt(i));
            if (b == null || !seen.add(b.id)) continue;
            bots.add(b);
        }
        return new Data(clean(j.opt("title"), 32), bots, usage(j.opt("usage")), spends(j.optJSONArray("providers_usage")));
    }

    /** A reply's "office" payload on top of the data: "bots" replaces the list, "hired" adds one, "usage" updates. */
    static Data applyReply(Data d, JSONObject office) {
        if (d == null) d = Data.empty();
        if (office == null) return d;
        List<Bot> bots = new ArrayList<>(d.bots);
        if (office.optJSONArray("bots") != null) bots = parse(new JSONObjectBuilder().put("bots", office.optJSONArray("bots")).o).bots;
        Bot h = bot(office.opt("hired"));
        if (h != null && bots.size() < MAX_BOTS) {
            boolean has = false;
            for (Bot b : bots) has |= b.id.equals(h.id);
            if (!has) bots.add(h);
        }
        Usage u = usage(office.opt("usage"));
        String title = office.has("title") ? clean(office.opt("title"), 32) : d.title;
        return new Data(title, bots, u != null ? u : d.usage, d.spend);
    }

    static JSONObject toJson(Data d) {
        try {
            JSONArray a = new JSONArray();
            for (Bot b : d.bots) a.put(new JSONObject().put("id", b.id).put("name", b.name).put("role", b.role).put("color", b.color).put("status", b.status).put("task", b.task)
                    .put("provider", b.provider).put("assistant", b.assistant.isEmpty() ? null : b.assistant));
            JSONObject o = new JSONObject().put("bots", a);
            if (!d.title.isEmpty()) o.put("title", d.title);
            if (d.usage != null) o.put("usage", new JSONObject().put("weekly_pct", d.usage.pct).put("label", d.usage.label).put("resets", d.usage.resets));
            if (!d.spend.isEmpty()) {
                JSONArray sp = new JSONArray();
                for (Spend r : d.spend) {
                    JSONObject x = new JSONObject().put("provider", r.provider).put("spent", r.spent).put("unit", r.unit);
                    if (!r.label.isEmpty()) x.put("label", r.label);
                    if (r.budget > 0) x.put("budget", r.budget);
                    if (!r.resets.isEmpty()) x.put("resets", r.resets);
                    sp.put(x);
                }
                o.put("providers_usage", sp);
            }
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** org.json's put throws a checked exception; this keeps one-liners readable. */
    private static final class JSONObjectBuilder {
        final JSONObject o = new JSONObject();

        JSONObjectBuilder put(String k, Object v) {
            try {
                o.put(k, v);
            } catch (Exception ignored) {
            }
            return this;
        }
    }

    // ---- what the user asked ----

    private static final Pattern HIRE = Pattern.compile("\\b(hire|recruit|onboard|assumi|assumere|ingaggia|recluta|einstellen|stell\\w*\\s+ein|embauche|recrute|contrata|contratar)\\b|\\b(add|new|aggiungi|nuovo)\\s+(a\\s+|un\\s+)?bot\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern OPEN = Pattern.compile("\\b(office|ufficio|b(ü|u)ro|bureau|oficina|my bots|i miei bot|team)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NAME = Pattern.compile("(?:called|named|name it|chiamat[oa]|di nome|nome|namens|genannt|nommé|appelé|llamad[oa])\\s+[\"“'«]?([A-Za-zÀ-ÿ0-9_-]{2,20})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LEAD = Pattern.compile("^\\s*(please|per favore|bitte|s'il te plaît|por favor|grokbot|grok bot|ok|hey)[\\s,]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTICLE = Pattern.compile("^[\\s,.:;!-]*(a|an|un|una|uno|ein|eine|einen|une|me)?\\s*(new|nuovo|nuova|neuen|nouveau|nuevo)?\\s*(bot|agent|agente)?\\s*(that|who|to|for|che|per|a|der|die|qui|pour|que|para)?\\s+", Pattern.CASE_INSENSITIVE);

    static final class Asked {
        final String type, name, role;
        /** For a hire: the AI named in the user's words (claude, chatgpt), else grok. */
        final String provider;

        Asked(String type, String name, String role) {
            this(type, name, role, "grok");
        }

        Asked(String type, String name, String role, String provider) {
            this.type = type;
            this.name = name;
            this.role = role;
            this.provider = provider;
        }
    }

    private static final Pattern CLAUDE = Pattern.compile("\\b(claude|anthropic)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GPT = Pattern.compile("\\b(chat\\s?gpt|openai|gpt)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AI_WORDS = Pattern.compile("\\b(on|with|su|con|mit|avec|sur)?\\s*(claude|anthropic|chat\\s?gpt|openai|gpt)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOT_LEAD = Pattern.compile("^[\\s,.:;!-]*(bot|agent|agente)?\\s*(that|who|to|for|che|per|der|die|qui|pour|que|para)?\\s+", Pattern.CASE_INSENSITIVE);

    /** hire (name and role only from the user's words), open, ask or none. */
    static Asked intent(String text) {
        String s = clean(text, 240);
        if (s.isEmpty()) return new Asked("none", "", "");
        if (HIRE.matcher(s).find()) {
            Matcher m = NAME.matcher(s);
            String name = m.find() ? m.group(1) : "";
            String role = HIRE.matcher(NAME.matcher(s).replaceFirst(" ")).replaceFirst(" ");
            role = LEAD.matcher(role).replaceFirst(" ");
            role = ARTICLE.matcher(role).replaceFirst("");
            role = role.replaceAll("[\\s.!?]+$", "");
            String pv = CLAUDE.matcher(s).find() ? "claude" : GPT.matcher(s).find() ? "chatgpt" : "grok";
            if (!"grok".equals(pv)) role = BOT_LEAD.matcher(AI_WORDS.matcher(role).replaceAll(" ")).replaceFirst("").replaceAll("\\s+", " ").trim();
            return new Asked("hire", name, clean(role, 60), pv);
        }
        if (OPEN.matcher(s).find() && s.length() < 60 && !s.matches(".*\\?\\s*$")) return new Asked("open", "", "");
        return new Asked("ask", "", "");
    }

    static JSONArray names(Data d) {
        JSONArray a = new JSONArray();
        if (d != null) for (Bot b : d.bots) a.put(b.name);
        return a;
    }

    static final class Request {
        final String question;
        final JSONObject office;

        Request(String question, JSONObject office) {
            this.question = question;
            this.office = office;
        }
    }

    /** The question and "office" object of a hire (the routine creates the bot and answers with office.hired). */
    static Request hireRequest(String name, String role, String description, Data d) {
        String q = "Hire a new bot for my GrokBot Office" + (name.isEmpty() ? "" : " named " + name) + (role.isEmpty() ? "" : ": " + role)
                + ". Create it, then reply with office.hired = {name, role, color}.";
        return new Request(q, new JSONObjectBuilder().put("action", "hire").put("name", name).put("role", role)
                .put("description", description == null || description.isEmpty() ? role : description).put("bots", names(d)).o);
    }

    static Request askRequest(String q, Data d) {
        return new Request(q, new JSONObjectBuilder().put("action", "ask").put("bots", names(d)).o);
    }

    /** Grok Bot diretto's request body (GrokbotDirect.body) plus the office object. */
    static String requestBody(String gbdBody, JSONObject office) throws Exception {
        return new JSONObject(gbdBody).put("office", office).toString();
    }

    /** The "office" object of the reply with this id in an ntfy poll body, or null. Untrusted. */
    static JSONObject findOffice(String ndjson, String id) {
        if (ndjson == null || id == null) return null;
        for (String line : ndjson.split("\n")) {
            try {
                JSONObject ev = new JSONObject(line.trim());
                if (!"message".equals(ev.optString("event"))) continue;
                JSONObject m = new JSONObject(ev.optString("message", ""));
                if (id.equals(m.optString("id"))) return m.optJSONObject("office");
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** A pending hire is done when the bots hold its name (or, without a name, when there are more bots). */
    static boolean confirmed(Data d, Pending p) {
        if (p == null || d == null) return false;
        if (!p.name.isEmpty()) {
            for (Bot b : d.bots) if (b.name.equalsIgnoreCase(p.name)) return true;
            return false;
        }
        return d.bots.size() > p.before;
    }

    static JSONObject pendingJson(Pending p) {
        return new JSONObjectBuilder().put("name", p.name).put("role", p.role).put("at", p.at).put("before", p.before).o;
    }

    static Pending pending(String json, long now) {
        try {
            JSONObject o = new JSONObject(json);
            long at = o.optLong("at");
            if (now - at > PENDING_TTL_MS || now < at) return null;
            return new Pending(clean(o.opt("name"), 24), clean(o.opt("role"), 60), at, o.optInt("before"));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- the room ----

    static final int ROOM = 10;

    /** Desk spots (room tiles) for n bots: rows of 3 or 4, at most 16, at least 6 so the office never looks bare. */
    static double[][] layout(int n) {
        int k = Math.min(SHOWN, Math.max(6, n)), cols = k <= 6 ? 3 : 4, rows = (k + cols - 1) / cols;
        double gx = cols == 3 ? 2.9 : 2.3, gy = rows <= 2 ? 2.9 : 2.3;
        double x0 = ROOM / 2.0 - (cols - 1) * gx / 2 + 0.2, y0 = ROOM / 2.0 - (rows - 1) * gy / 2 + 0.2;
        double[][] out = new double[k][];
        for (int i = 0; i < k; i++) out[i] = new double[]{x0 + (i % cols) * gx, y0 + (i / cols) * gy};
        return out;
    }

    static boolean due(long last, long now) {
        return now - last >= FRAME_MS - 2;
    }

    // ---- the file the PC pushes (and the replies update) ----

    static Data load(File f) {
        try {
            if (!f.isFile() || f.length() > 256 * 1024) return Data.empty();
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
                return parse(new String(b, 0, n, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return Data.empty();
        }
    }

    static void save(File f, Data d) throws Exception {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no dir");
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(toJson(d).toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename");
    }

    // ---- LinkServer: PUT / GET /v1/office from the paired PC (omarchy-tv office push) ----

    /** Cleans and stores what the PC pushed; returns the stored JSON. The Office (if open) picks the file up. */
    public static JSONObject put(File dir, JSONObject body) throws Exception {
        Data d = parse(body);
        save(new File(dir, FILE_NAME), d);
        return toJson(d);
    }

    public static JSONObject get(File dir) {
        return toJson(load(new File(dir, FILE_NAME)));
    }

    /**
     * Pixel widths for the Office screens, the same policy as webos/app/office.js: a column of half the
     * panel (at least 560dp, at most 90%) so the title stays on one line and the ask field is not clipped
     * on 720p and 1080p TVs (tvdpi 213 and xhdpi 320).
     */
    static final class Chrome {
        static int column(int screenPx, float density) {
            return clamp(Math.max(dp(560, density), Math.round(0.50f * screenPx)), Math.round(0.90f * screenPx));
        }

        static int hint(int screenPx, float density) {
            return column(screenPx, density);
        }

        static int reply(int screenPx, float density) {
            return column(screenPx, density);
        }

        static int hire(int screenPx, float density) {
            int want = Math.max(dp(360, density), Math.round(0.28f * screenPx));
            int cap = Math.min(Math.round(0.48f * screenPx), Math.max(1, screenPx - dp(80, density)));
            return clamp(want, cap);
        }

        static int usage(int screenPx, float density) {
            return clamp(Math.max(dp(240, density), Math.round(0.20f * screenPx)), Math.round(0.32f * screenPx));
        }

        static int keys(int screenPx) {
            return clamp(Math.round(0.46f * screenPx), Math.round(0.60f * screenPx));
        }

        static int header(int screenPx) {
            return Math.max(1, Math.round(0.70f * screenPx));
        }

        /** Ask bar bottom margin, in dp (OfficeActivity.at() takes dp). */
        static final int BAR_BOTTOM_DP = 36;
        /** Reserved ask-bar height (17sp field, padding, stroke), in dp. */
        static final int BAR_HEIGHT_DP = 56;
        static final int GAP_DP = 12;
        static final int USAGE_LEFT_DP = 36;
        /** Reserved usage-card height so the reply can sit above it when they share a column. */
        static final int USAGE_RESERVE_DP = 100;

        /** Bottom margin of the usage card, in px: always just above the ask bar. */
        static int usageBottom(float density) {
            return dp(BAR_BOTTOM_DP, density) + dp(BAR_HEIGHT_DP, density) + dp(GAP_DP, density);
        }

        /**
         * Bottom margin of the reply, in px. Same as the usage card when they sit side by side;
         * stacked above the reserved usage height when the card would collide with the column.
         */
        static int replyBottom(int screenPx, float density) {
            int aboveBar = usageBottom(density);
            return usageCollidesWithColumn(screenPx, density)
                    ? aboveBar + dp(USAGE_RESERVE_DP, density) + dp(GAP_DP, density)
                    : aboveBar;
        }

        /** dp form of {@link #usageBottom(float)}, for docs and density-1 checks. */
        static int usageBottomDp() {
            return BAR_BOTTOM_DP + BAR_HEIGHT_DP + GAP_DP;
        }

        /** True when the usage card's right edge (plus a gap) crosses the centered ask/reply column. */
        static boolean usageCollidesWithColumn(int screenPx, float density) {
            int usageRight = dp(USAGE_LEFT_DP, density) + usage(screenPx, density);
            int colLeft = (screenPx - column(screenPx, density)) / 2;
            return usageRight + dp(GAP_DP, density) > colLeft;
        }

        private static int dp(int v, float density) {
            return Math.round(v * density);
        }

        private static int clamp(int v, int max) {
            return Math.max(1, Math.min(max, v));
        }
    }
}
