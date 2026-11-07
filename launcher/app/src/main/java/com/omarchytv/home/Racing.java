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
    static final long MAX_AGE_MS = 6 * 3600_000L, WEEKEND_AGE_MS = 3600_000L;
    /** A session with no end time (MotoGP races, sprints) counts as running for this long after its start. */
    static final long DEFAULT_LENGTH_MS = 2 * 3600_000L;
    private static final String MGP_API = "https://api.motogp.pulselive.com/motogp/v1/events?seasonYear=" + YEAR;
    private static final String F1_PAGE = "https://www.formula1.com/en/racing/" + YEAR + "/";
    private static final String UA = "Mozilla/5.0 (Linux; Android TV) OmarchyTV";
    private static volatile boolean fetching;
    private static JSONObject asset;

    private Racing() {
    }

    static final class Session {
        final String code, name;
        final long start, end;

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
        if (live != null && live.optInt("year") == YEAR) {
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
                sessions.add(new Session(s.optString("code"), s.optString("name"), st, Math.max(en, 0)));
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
        Next best = null;
        for (int k = 0; k < 2; k++) {
            List<Meeting> list = k == 0 ? f1 : motogp;
            Meeting m = next(list, now);
            Session s = m != null ? current(m, now) : null;
            if (s == null) continue;
            Next n = new Next(k == 0 ? F1 : MOTOGP, m, s);
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
        TimeZone tz = TimeZone.getTimeZone(id == null || id.isEmpty() ? DEFAULT_ZONE : id);
        // getTimeZone answers GMT for an unknown id: fall back to Bangkok rather than silently showing UTC.
        return "GMT".equals(tz.getID()) && !"GMT".equalsIgnoreCase(id) ? TimeZone.getTimeZone(DEFAULT_ZONE) : tz;
    }

    /** "sun 22 nov 11:00" in the given zone (24 h, the way race schedules are printed). */
    static String when(long t, TimeZone tz, Locale loc) {
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", loc);
        f.setTimeZone(tz);
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
        if (fetching) return;
        final Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        boolean due = false;
        for (String series : new String[]{F1, MOTOGP}) {
            File f = cacheFile(c, series);
            long soon = nextStart(meetings(c, series), now);
            if (soon == Long.MAX_VALUE) continue;   // 2026 is over for this series: nothing left to refresh
            long maxAge = soon - now < 48 * 3600_000L ? WEEKEND_AGE_MS : MAX_AGE_MS;
            if (!f.isFile() || now - f.lastModified() > maxAge) due = true;
        }
        if (!due) return;
        fetching = true;
        new Thread(() -> {
            try {
                refreshMotoGp(c);
            } catch (Exception ignored) {
            }
            try {
                refreshF1(c);
            } catch (Exception ignored) {
            } finally {
                fetching = false;
                new Handler(Looper.getMainLooper()).post(onDone);
            }
        }, "omarchytv-racing").start();
    }

    private static void refreshMotoGp(Context c) throws Exception {
        String body = get(MGP_API);
        if (body == null) return;
        JSONArray meetings = parseMotoGp(new JSONArray(body));
        if (meetings.length() > 0) write(cacheFile(c, MOTOGP), meetings);
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
                if (cat == null || !"MGP".equals(cat.optString("acronym"))) continue;
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
