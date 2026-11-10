package com.omarchytv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "racing" home widget data: the next F1 and MotoGP sessions of the 2026 season, shown in one time zone
 * (Asia/Bangkok unless omarchytv.json racing.timezone says otherwise).
 *
 * Sources, both official and keyless:
 *  - MotoGP: https://api.motogp.pulselive.com/motogp/v1/events?seasonYear=2026 (the API behind motogp.com),
 *    MotoGP class only, warm-up left out.
 *  - F1: the formula1.com page of the next meeting (https://www.formula1.com/en/racing/2026/&lt;slug&gt;), which embeds
 *    its session times; formula1.com has no public season feed, so only the next meeting is re-read live.
 * assets/racing_2026.json (tools/racing_calendar.py) is the bundled snapshot of both calendars: the widget works
 * offline and before the first fetch. Live answers go to the files dir and win over the snapshot.
 * Fetched on a background thread: every 6 h, every hour when a session starts within 48 h.
 */
final class Racing {

    static final int YEAR = 2026;
    static final String DEFAULT_ZONE = "Asia/Bangkok";
    static final String F1 = "f1", MOTOGP = "motogp";
    /**
     * 2.15 every series the widget can follow, with the label it shows: the MotoGP classes from the MotoGP API, the
     * three NASCAR national series from NASCAR's own feed, Formula E and the WEC from their official sites. Which
     * ones show is omarchytv.json racing.series (default f1 and motogp).
     */
    static final String[][] SERIES = {{F1, "F1"}, {MOTOGP, "MotoGP"}, {"moto2", "Moto2"}, {"moto3", "Moto3"},
            {"nascar", "NASCAR Cup"}, {"nascar2", "NASCAR O'Reilly"}, {"truck", "NASCAR Trucks"}, {"fe", "Formula E"}, {"wec", "WEC"}};

    static String label(String id) {
        for (String[] x : SERIES) if (x[0].equals(id)) return x[1];
        return id;
    }

    static boolean known(String id) {
        for (String[] x : SERIES) if (x[0].equals(id)) return true;
        return false;
    }
    static final long MAX_AGE_MS = 6 * 3600_000L, WEEKEND_AGE_MS = 3600_000L;
    /** A session with no end time (MotoGP races, sprints) counts as running for this long after its start. */
    static final long DEFAULT_LENGTH_MS = 2 * 3600_000L;
    private static final String MGP_API = "https://api.motogp.pulselive.com/motogp/v1/events?seasonYear=";
    private static final String NASCAR_API = "https://cf.nascar.com/cacher/";   // + year + "/race_list_basic.json"
    private static final String FE_PAGE = "https://www.fiaformulae.com/en/calendar";
    private static final String WEC_PAGE = "https://www.fiawec.com/en/";
    private static final String F1_PAGE = "https://www.formula1.com/en/racing/" + YEAR + "/";
    private static final String UA = "Mozilla/5.0 (Linux; Android TV) OmarchyTV";
    private static volatile boolean fetching;
    private static JSONObject asset;

    private Racing() {
    }

    static final class Session {
        final String code, name;
        final long start, end;
        /** 2.15: only the day is known (Formula E's calendar): shown without a time, "running" all that day (UTC). */
        boolean dateOnly;

        Session(String code, String name, long start, long end) {
            this.code = code;
            this.name = name;
            this.start = start;
            this.end = end;
        }

        long endOrDefault() {
            return end > start ? end : start + DEFAULT_LENGTH_MS;
        }

        boolean live(long now) {
            return now >= start && now < endOrDefault();
        }
    }

    static final class Meeting {
        final String slug, name;
        final List<Session> sessions;

        Meeting(String slug, String name, List<Session> sessions) {
            this.slug = slug;
            this.name = name;
            this.sessions = sessions;
        }

        long lastEnd() {
            return sessions.isEmpty() ? 0 : sessions.get(sessions.size() - 1).endOrDefault();
        }
    }

    // ---- reading ----------------------------------------------------------------------------------------------

    static File cacheFile(Context c, String series) {
        return new File(c.getFilesDir(), "racing_" + series + ".json");
    }

    /** Meetings of one series: the live cache when there is one, else the bundled snapshot. */
    static List<Meeting> meetings(Context c, String series) {
        JSONObject live = readJson(cacheFile(c, series));
        if (live != null) {   // 2.15: any season the live sources answered (they move on to the next year by themselves)
            List<Meeting> l = parse(live.optJSONArray("meetings"));
            if (!l.isEmpty()) return l;
        }
        JSONObject a = asset(c);
        return parse(a != null ? a.optJSONArray(series) : null);
    }

    private static synchronized JSONObject asset(Context c) {
        if (asset == null) {
            try (InputStream in = c.getAssets().open("racing_" + YEAR + ".json")) {
                asset = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return null;
            }
        }
        return asset;
    }

    /** [{slug, name, sessions:[{code, name, start:"...Z", end}]}] → meetings in start order (bad rows skipped). */
    static List<Meeting> parse(JSONArray a) {
        List<Meeting> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            JSONArray ss = m != null ? m.optJSONArray("sessions") : null;
            if (ss == null) continue;
            List<Session> sessions = new ArrayList<>();
            for (int k = 0; k < ss.length(); k++) {
                JSONObject s = ss.optJSONObject(k);
                long st = s != null ? utc(s.optString("start")) : -1;
                if (st < 0) continue;
                long en = s.isNull("end") ? -1 : utc(s.optString("end"));
                Session x = new Session(s.optString("code"), s.optString("name"), st, Math.max(en, 0));
                x.dateOnly = s.optBoolean("dateOnly", false);
                sessions.add(x);
            }
            if (sessions.isEmpty()) continue;
            Collections.sort(sessions, (x, y) -> Long.compare(x.start, y.start));
            out.add(new Meeting(m.optString("slug"), m.optString("name"), sessions));
        }
        Collections.sort(out, (x, y) -> Long.compare(x.sessions.get(0).start, y.sessions.get(0).start));
        return out;
    }

    /** The meeting still running or next to come at {@code now}, or null when the season is over. */
    static Meeting next(List<Meeting> list, long now) {
        for (Meeting m : list) if (m.lastEnd() > now) return m;
        return null;
    }

    /** The meetings not over at {@code now}: this weekend's (still running) and every one after it. */
    static List<Meeting> upcoming(List<Meeting> list, long now) {
        List<Meeting> out = new ArrayList<>();
        for (Meeting m : list) if (m.lastEnd() > now) out.add(m);
        return out;
    }

    /** The meeting's main race (the last "RACE"), else its last session. */
    static Session race(Meeting m) {
        for (int i = m.sessions.size() - 1; i >= 0; i--) if ("RACE".equals(m.sessions.get(i).code)) return m.sessions.get(i);
        return m.sessions.get(m.sessions.size() - 1);
    }

    /** The meeting's session running at {@code now}, else the next to start; null when all are over. */
    static Session current(Meeting m, long now) {
        for (Session s : m.sessions) if (s.endOrDefault() > now) return s;
        return null;
    }

    /**
     * 2.15.1 Pulse's racing panel: for each followed series, the next session of its next weekend and that weekend's
     * race, as [{series, code, name, start, end, day}] (seconds). The page sorts them and counts down.
     */
    static JSONArray pulseRows(Context c, List<String> series, long now) {
        JSONArray out = new JSONArray();
        try {
            for (String id : series) {
                List<Meeting> left = upcoming(meetings(c, id), now);
                if (left.isEmpty()) continue;
                Meeting m = left.get(0);
                Session s = current(m, now), r = race(m);
                String name = m.name.endsWith(" GP") ? m.name.substring(0, m.name.length() - 3) : m.name;
                for (Session x : s == null || s == r ? new Session[]{r} : new Session[]{s, r}) {
                    if (x == null || x.endOrDefault() <= now || out.length() >= 10) continue;
                    out.put(new JSONObject().put("series", label(id)).put("code", x.code).put("name", name)
                            .put("start", x.start / 1000).put("end", x.endOrDefault() / 1000).put("day", x.dateOnly));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** What the bar counts down to: the running or next session across both series. */
    static final class Next {
        final String series;
        final Meeting meeting;
        final Session session;

        Next(String series, Meeting meeting, Session session) {
            this.series = series;
            this.meeting = meeting;
            this.session = session;
        }
    }

    /** Running sessions win; otherwise the one starting soonest. Null when 2026 is over for both. */
    static Next soonest(List<Meeting> f1, List<Meeting> motogp, long now) {
        java.util.LinkedHashMap<String, List<Meeting>> m = new java.util.LinkedHashMap<>();
        m.put(F1, f1);
        m.put(MOTOGP, motogp);
        return soonest(m, now);
    }

    /** 2.15: across every followed series. Day-only sessions don't drive the bar's countdown (no time to count to). */
    static Next soonest(java.util.Map<String, List<Meeting>> all, long now) {
        Next best = null;
        for (java.util.Map.Entry<String, List<Meeting>> e : all.entrySet()) {
            List<Meeting> list = e.getValue();
            Meeting m = next(list, now);
            Session s = m != null ? current(m, now) : null;
            if (s == null || s.dateOnly) continue;
            Next n = new Next(e.getKey(), m, s);
            if (best == null) best = n;
            else if (s.live(now) != best.session.live(now)) { if (s.live(now)) best = n; }
            else if (s.start < best.session.start) best = n;
        }
        return best;
    }

    /** Countdown clock: "04:12:09", or "3d 04:12:09" when a day or more away. */
    static String clock(long ms) {
        long sec = Math.max(0, ms / 1000), d = sec / 86400, h = sec / 3600 % 24, m = sec / 60 % 60, s = sec % 60;
        String hms = String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
        return d > 0 ? d + "d " + hms : hms;
    }

    /** Sessions starting soonest (min 0 = all) across meetings: the next meeting first, for the refresh timer. */
    static long nextStart(List<Meeting> list, long now) {
        for (Meeting m : list) for (Session s : m.sessions) if (s.endOrDefault() > now) return s.start;
        return Long.MAX_VALUE;
    }

    // ---- formatting -------------------------------------------------------------------------------------------

    static TimeZone zone(String id) {
        // 2.15: no racing.timezone means the TV's own time zone; an unknown id too (getTimeZone answers GMT for
        // those, and silently showing UTC would be wrong).
        if (id == null || id.isEmpty()) return TimeZone.getDefault();
        TimeZone tz = TimeZone.getTimeZone(id);
        return "GMT".equals(tz.getID()) && !"GMT".equalsIgnoreCase(id) ? TimeZone.getDefault() : tz;
    }

    /** "sun 22 nov 11:00" in the given zone (24 h, the way race schedules are printed). */
    static String when(long t, TimeZone tz, Locale loc) {
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", loc);
        f.setTimeZone(tz);
        return f.format(new Date(t)).replace(".", "").toLowerCase(loc);
    }

    /** 2.15 "sat 18 dec": a day with no time (Formula E), in UTC so the date never shifts. */
    static String day(long t, Locale loc) {
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", loc);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t)).replace(".", "").toLowerCase(loc);
    }

    /** Short zone label for the legend: "bangkok" for Asia/Bangkok. */
    static String zoneLabel(TimeZone tz) {
        String id = tz.getID();
        int s = id.lastIndexOf('/');
        return (s >= 0 ? id.substring(s + 1) : id).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    // ---- fetching ---------------------------------------------------------------------------------------------

    static void refreshIfStale(Context ctx, Runnable onDone) {
        refreshIfStale(ctx, java.util.Arrays.asList(F1, MOTOGP), onDone);
    }

    /** 2.15: the followed series whose cache is old (6 h, 1 h in a race weekend) are re-read, one source each. */
    static void refreshIfStale(Context ctx, List<String> series, Runnable onDone) {
        if (fetching) return;
        final Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        final Set<String> due = new HashSet<>();
        for (String id : series) {
            File f = cacheFile(c, id);
            long soon = nextStart(meetings(c, id), now);
            long maxAge = soon != Long.MAX_VALUE && soon - now < 48 * 3600_000L ? WEEKEND_AGE_MS : MAX_AGE_MS;
            if (!f.isFile() || now - f.lastModified() > maxAge) due.add(id);
        }
        if (due.isEmpty()) return;
        fetching = true;
        new Thread(() -> {
            try {
                if (due.contains(MOTOGP) || due.contains("moto2") || due.contains("moto3")) quietly(() -> refreshMotoGp(c));
                if (due.contains("nascar") || due.contains("nascar2") || due.contains("truck")) quietly(() -> refreshNascar(c));
                if (due.contains("fe")) quietly(() -> refreshFe(c));
                if (due.contains("wec")) quietly(() -> refreshWec(c));
                if (due.contains(F1)) quietly(() -> refreshF1(c));
            } finally {
                fetching = false;
                new Handler(Looper.getMainLooper()).post(onDone);
            }
        }, "omarchytv-racing").start();
    }

    private interface Job {
        void run() throws Exception;
    }

    private static void quietly(Job j) {
        try {
            j.run();
        } catch (Exception ignored) {   // one source down never stops the others
        }
    }

    /** This year's season, or next year's once this one has nothing left (the API serves both). */
    private static JSONArray latest(String base, String tail, java.util.function.Function<String, JSONArray> parse) throws Exception {
        int y = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(java.util.Calendar.YEAR);
        JSONArray best = null;
        for (int k = 0; k < 2; k++) {
            String body = get(base + (y + k) + tail);
            JSONArray a = body != null ? parse.apply(body) : null;
            if (a == null) continue;
            if (best == null) best = a;
            if (!upcoming(parse(a), System.currentTimeMillis()).isEmpty()) return a;
        }
        return best;
    }

    private static void refreshMotoGp(Context c) throws Exception {
        String[][] cls = {{MOTOGP, "MGP"}, {"moto2", "MT2"}, {"moto3", "MT3"}};
        for (String[] k : cls) {
            JSONArray meetings = latest(MGP_API, "", body -> {
                try {
                    return parseMotoGp(new JSONArray(body), k[1]);
                } catch (Exception e) {
                    return null;
                }
            });
            if (meetings != null && meetings.length() > 0) write(cacheFile(c, k[0]), meetings);
        }
    }

    private static void refreshNascar(Context c) throws Exception {
        String[][] ser = {{"nascar", "series_1"}, {"nascar2", "series_2"}, {"truck", "series_3"}};
        for (String[] k : ser) {
            JSONArray meetings = latest(NASCAR_API, "/race_list_basic.json", body -> {
                try {
                    return parseNascar(new JSONObject(body).optJSONArray(k[1]));
                } catch (Exception e) {
                    return null;
                }
            });
            if (meetings != null && meetings.length() > 0) write(cacheFile(c, k[0]), meetings);
        }
    }

    private static void refreshFe(Context c) throws Exception {
        String page = get(FE_PAGE);
        JSONArray m = page != null ? parseFePage(page) : null;
        if (m != null && m.length() > 0) write(cacheFile(c, "fe"), m);
    }

    private static void refreshWec(Context c) throws Exception {
        String page = get(WEC_PAGE);
        JSONArray m = page != null ? parseWecPage(page) : null;
        if (m != null && m.length() > 0) write(cacheFile(c, "wec"), m);
    }

    /** NASCAR's race list (one series) → meetings: practice, qualifying and the race from each weekend's schedule. */
    static JSONArray parseNascar(JSONArray races) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; races != null && i < races.length(); i++) {
            JSONObject r = races.optJSONObject(i);
            JSONArray sch = r != null ? r.optJSONArray("schedule") : null;
            JSONArray sessions = new JSONArray();
            int practice = 0;
            for (int k = 0; sch != null && k < sch.length(); k++) {
                JSONObject e = sch.optJSONObject(k);
                int type = e != null ? e.optInt("run_type") : 0;
                if (type < 1 || type > 3) continue;   // 0: garage hours, meetings, autographs...
                long st = utc(e.optString("start_time_utc") + "Z");
                if (st < 0) continue;
                String code = type == 3 ? "RACE" : type == 2 ? "QUALI" : "P" + (++practice);
                sessions.put(new JSONObject().put("code", code).put("name", e.optString("event_name").trim()).put("start", iso(st)).put("end", JSONObject.NULL));
            }
            if (sessions.length() == 0) continue;
            out.put(new JSONObject().put("slug", String.valueOf(r.optInt("race_id"))).put("name", r.optString("track_name", r.optString("race_name")).trim())
                    .put("sessions", sessions));
        }
        return out;
    }

    private static final Pattern LD_JSON = Pattern.compile("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", Pattern.DOTALL);

    /** fiaformulae.com/en/calendar: its schema.org list of SportsEvents (the day of each E-Prix, no times). */
    static JSONArray parseFePage(String html) throws Exception {
        Matcher m = LD_JSON.matcher(html);
        JSONArray out = new JSONArray();
        while (m.find()) {
            JSONObject o;
            try {
                o = new JSONObject(m.group(1).trim());
            } catch (Exception e) {
                continue;
            }
            JSONArray items = o.optJSONArray("itemListElement");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                JSONObject ev = it != null ? it.optJSONObject("item") : null;
                if (ev == null || !"SportsEvent".equals(ev.optString("@type"))) continue;
                if (ev.optString("eventStatus").contains("Cancelled")) continue;
                long day = utc(ev.optString("startDate") + "T00:00:00Z");
                if (day < 0) continue;
                String name = ev.optString("name").replaceFirst("^R\\d+\\s+", "").trim();
                out.put(new JSONObject().put("slug", ev.optString("name")).put("name", name).put("sessions", new JSONArray()
                        .put(new JSONObject().put("code", "RACE").put("name", "E-Prix").put("start", iso(day)).put("end", iso(day + 86_400_000L)).put("dateOnly", true))));
            }
        }
        return out;
    }

    private static final Pattern WEC_EVENT = Pattern.compile("href=\"/en/race/([a-z0-9-]+)\"(?:(?!href=\"/en/race/).){0,3000}?data-date=\"(\\d{9,11})\"", Pattern.DOTALL);

    /** fiawec.com: each round of the season with its first practice ("countdown to Free Practice 1"). */
    static JSONArray parseWecPage(String html) throws Exception {
        Matcher m = WEC_EVENT.matcher(html);
        Set<String> seen = new HashSet<>();
        List<JSONObject> list = new ArrayList<>();
        while (m.find()) {
            String slug = m.group(1);
            if (!seen.add(slug)) continue;
            long st = Long.parseLong(m.group(2)) * 1000L;
            list.add(new JSONObject().put("slug", slug).put("name", wecName(slug)).put("sessions", new JSONArray()
                    .put(new JSONObject().put("code", "FP1").put("name", "Free Practice 1").put("start", iso(st)).put("end", JSONObject.NULL))));
        }
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        return out;
    }

    /** "totalenergies-6-hours-of-spa-francorchamps-2027" → "6 Hours of Spa Francorchamps". */
    static String wecName(String slug) {
        String s = slug.replaceAll("-\\d{4}$", "").replaceFirst("^(totalenergies|rolex|bapco-energies|qatar-airways)-", "");
        StringBuilder b = new StringBuilder();
        for (String w : s.split("-")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w.equals("of") ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    /** F1: re-read the page of the meeting that is next (or running) and put its sessions over the known list. */
    private static void refreshF1(Context c) throws Exception {
        long now = System.currentTimeMillis();
        List<Meeting> list = meetings(c, F1);
        Meeting m = next(list, now);
        if (m == null || m.slug.isEmpty() || !m.slug.matches("[a-z0-9-]+")) return;
        String page = get(F1_PAGE + m.slug);
        if (page == null) return;
        JSONArray sessions = parseF1Page(page);
        if (sessions.length() == 0) return;
        JSONArray out = new JSONArray();
        for (Meeting x : list) {
            JSONObject o = new JSONObject().put("slug", x.slug).put("name", x.name);
            o.put("sessions", x == m ? sessions : toJson(x.sessions));
            out.put(o);
        }
        write(cacheFile(c, F1), out);
    }

    private static void write(File f, JSONArray meetings) throws Exception {
        JSONObject o = new JSONObject().put("year", YEAR).put("meetings", meetings);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static JSONArray toJson(List<Session> sessions) throws Exception {
        JSONArray a = new JSONArray();
        for (Session s : sessions) {
            JSONObject o = new JSONObject().put("code", s.code).put("name", s.name).put("start", iso(s.start));
            o.put("end", s.end > 0 ? iso(s.end) : JSONObject.NULL);
            a.put(o);
        }
        return a;
    }

    /** MotoGP events → meetings JSON (GP weekends, MotoGP class, no warm-up, cancelled sessions dropped). */
    static JSONArray parseMotoGp(JSONArray events) throws Exception {
        return parseMotoGp(events, "MGP");
    }

    /** 2.15: one class of the MotoGP API: "MGP", "MT2" (Moto2) or "MT3" (Moto3). */
    static JSONArray parseMotoGp(JSONArray events, String acronym) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null || !"GP".equals(e.optString("kind")) || e.optBoolean("test")) continue;
            JSONArray b = e.optJSONArray("broadcasts");
            JSONArray sessions = new JSONArray();
            for (int k = 0; b != null && k < b.length(); k++) {
                JSONObject s = b.optJSONObject(k);
                if (s == null || !"SESSION".equals(s.optString("type"))) continue;
                JSONObject cat = s.optJSONObject("category");
                if (cat == null || !acronym.equals(cat.optString("acronym"))) continue;
                String code = mgpCode(s.optString("shortname"));
                String name = s.optString("name").trim();
                if (code == null || s.optString("status").toUpperCase(Locale.ROOT).contains("CANCEL")
                        || name.toLowerCase(Locale.ROOT).contains("cancel")) continue;
                long st = utc(s.optString("date_start")), en = utc(s.optString("date_end"));
                if (st < 0) continue;
                sessions.put(new JSONObject().put("code", code).put("name", name).put("start", iso(st))
                        .put("end", en > st ? iso(en) : JSONObject.NULL));
            }
            if (sessions.length() == 0) continue;
            out.put(new JSONObject().put("slug", e.optString("id")).put("name", mgpName(e.optString("name")))
                    .put("sessions", sessions));
        }
        return out;
    }

    static String mgpCode(String shortname) {
        switch (shortname == null ? "" : shortname.trim().toUpperCase(Locale.ROOT)) {
            case "FP1": return "FP1";
            case "PR": return "PR";
            case "FP2": return "FP2";
            case "Q1": return "Q1";
            case "Q2": return "Q2";
            case "SPR": return "SPR";
            case "RAC": return "RACE";
            default: return null;
        }
    }

    /** "QATAR AIRWAYS GRAND PRIX OF QATAR " → "Qatar GP". */
    static String mgpName(String official) {
        String n = official == null ? "" : official.trim();
        Matcher m = Pattern.compile("GRAND PRIX OF (THE )?(.+)$", Pattern.CASE_INSENSITIVE).matcher(n);
        String place = m.find() ? m.group(2).trim() : n;
        StringBuilder b = new StringBuilder();
        for (String w : place.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b + " GP";
    }

    private static final Pattern F1_SESSION = Pattern.compile("\\{[^{}]*\"startTime\":\"[^\"]+\"[^{}]*\\}");

    /** Session objects embedded in a formula1.com race page → sessions JSON in time order (duplicates dropped). */
    static JSONArray parseF1Page(String html) throws Exception {
        String t = html.replace("\\\"", "\"");
        Matcher m = F1_SESSION.matcher(t);
        Set<String> seen = new HashSet<>();
        List<JSONObject> list = new ArrayList<>();
        while (m.find()) {
            JSONObject s;
            try {
                s = new JSONObject(m.group());
            } catch (Exception e) {
                continue;
            }
            if (!s.has("sessionType") || !s.has("gmtOffset")) continue;
            if (s.optString("state").toLowerCase(Locale.ROOT).contains("cancel")) continue;
            String key = s.optString("startTime") + "|" + s.optString("description");
            if (!seen.add(key)) continue;
            String off = s.optString("gmtOffset");
            long st = utc(s.optString("startTime") + off), en = s.has("endTime") ? utc(s.optString("endTime") + off) : -1;
            if (st < 0) continue;
            list.add(new JSONObject().put("code", f1Code(s)).put("name", s.optString("description", s.optString("sessionType")))
                    .put("start", iso(st)).put("end", en > st ? iso(en) : JSONObject.NULL));
        }
        Collections.sort(list, (x, y) -> x.optString("start").compareTo(y.optString("start")));
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        return a;
    }

    static String f1Code(JSONObject s) {
        String type = s.optString("sessionType").toLowerCase(Locale.ROOT);
        String shortName = s.optString("shortName").toUpperCase(Locale.ROOT);
        String desc = s.optString("description").toLowerCase(Locale.ROOT);
        if (desc.contains("sprint") && desc.contains("qual") || shortName.equals("SQ") || shortName.equals("SS")) return "SQ";
        if (desc.contains("sprint") || shortName.equals("S")) return "SPR";
        if (type.equals("practice") || shortName.startsWith("FP")) return shortName.isEmpty() ? "FP" + s.optInt("sessionNumber") : shortName;
        if (type.equals("qualifying")) return "QUALI";
        if (type.equals("race")) return "RACE";
        return shortName.isEmpty() ? desc.toUpperCase(Locale.ROOT) : shortName;
    }

    // ---- time -------------------------------------------------------------------------------------------------

    /**
     * Epoch ms of "2026-11-20T00:30:00Z", "2026-11-27T09:00:00+0100", "2026-11-27T09:00:00+01:00" or
     * "2026-11-19T16:30:00-08:00"; -1 when it is none of those.
     */
    static long utc(String s) {
        if (s == null || s.length() < 19) return -1;
        String z = s.endsWith("Z") ? s.substring(0, s.length() - 1) + "+0000" : s.replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT);
        f.setLenient(false);
        try {
            return f.parse(z).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    static String iso(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    // ---- io ---------------------------------------------------------------------------------------------------

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(10_000);
            con.setReadTimeout(20_000);
            con.setRequestProperty("User-Agent", UA);
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            con.disconnect();
        }
    }

    private static JSONObject readJson(File f) {
        if (!f.isFile() || f.length() > 2 * 1024 * 1024) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (b.size() > 4 * 1024 * 1024) throw new java.io.IOException("too large");
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }
}
