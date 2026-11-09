package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class RacingTest {

    private static final TimeZone BKK = Racing.zone("Asia/Bangkok");

    private static JSONObject asset() throws Exception {
        File f = new File("src/main/assets/racing_" + Racing.YEAR + ".json");
        return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void parsesEveryOffsetForm() {
        long t = Racing.utc("2026-11-22T04:00:00Z");
        assertEquals(t, Racing.utc("2026-11-21T20:00:00-08:00"));
        assertEquals(t, Racing.utc("2026-11-21T20:00:00-0800"));
        assertEquals(t, Racing.utc("2026-11-22T05:00:00+01:00"));
        assertEquals(-1, Racing.utc("tomorrow"));
        assertEquals("2026-11-22T04:00:00Z", Racing.iso(t));
    }

    @Test
    public void thaiTimeIs24HourAndCrossesMidnight() {
        // Las Vegas: Saturday 20:00 local is Sunday 11:00 in Thailand
        assertEquals("sun 22 nov 11:00", Racing.when(Racing.utc("2026-11-21T20:00:00-08:00"), BKK, Locale.ENGLISH));
        // São Paulo: Sunday 14:00 local is Monday 00:00 in Thailand
        assertEquals("mon 9 nov 00:00", Racing.when(Racing.utc("2026-11-08T17:00:00Z"), BKK, Locale.ENGLISH));
        assertEquals(TimeZone.getDefault().getID(), Racing.zone("Not/AZone").getID());   // 2.15: the TV's own zone
        assertEquals(TimeZone.getDefault().getID(), Racing.zone("").getID());
    }

    @Test
    public void countdownClock() {
        assertEquals("00:00:00", Racing.clock(-5));
        assertEquals("00:01:05", Racing.clock(65_000));
        assertEquals("23:59:59", Racing.clock(86_399_000));
        assertEquals("1d 00:00:00", Racing.clock(86_400_000));
        assertEquals("12d 04:12:09", Racing.clock(((12 * 24 + 4) * 3600 + 12 * 60 + 9) * 1000L));
    }

    @Test
    public void bundledCalendarOnlyShowsWhatIsLeft() throws Exception {
        JSONObject a = asset();
        assertEquals(Racing.YEAR, a.getInt("year"));
        List<Racing.Meeting> f1 = Racing.parse(a.getJSONArray("f1")), mgp = Racing.parse(a.getJSONArray("motogp"));
        assertTrue(f1.size() > 20);
        assertTrue(mgp.size() > 20);
        // Saturday 7 Nov 2026, 19:25 in Thailand: São Paulo and Qatar weekends are running
        long now = Racing.utc("2026-11-07T19:25:00+07:00");
        List<Racing.Meeting> f1Left = Racing.upcoming(f1, now), mgpLeft = Racing.upcoming(mgp, now);
        assertEquals("São Paulo GP", f1Left.get(0).name);
        assertEquals(4, f1Left.size());
        assertEquals("Abu Dhabi GP", f1Left.get(3).name);
        assertEquals("Qatar GP", mgpLeft.get(0).name);
        assertEquals(3, mgpLeft.size());
        assertEquals("Valencia GP", mgpLeft.get(2).name);
        for (Racing.Meeting m : f1Left) assertEquals("RACE", Racing.race(m).code);
        // the bar counts down to the soonest session: MotoGP Qatar FP2 is earlier than F1 FP3 in Brazil
        Racing.Next n = Racing.soonest(f1, mgp, now);
        assertNotNull(n);
        assertTrue(n.session.start >= now || n.session.live(now));
        // after Abu Dhabi and Valencia, 2026 is over
        long after = Racing.utc("2026-12-31T00:00:00Z");
        assertTrue(Racing.upcoming(f1, after).isEmpty());
        assertNull(Racing.soonest(f1, mgp, after));
    }

    @Test
    public void runningSessionWinsTheBar() throws Exception {
        List<Racing.Meeting> f1 = Racing.parse(asset().getJSONArray("f1"));
        Racing.Meeting vegas = null;
        for (Racing.Meeting m : f1) if (m.name.startsWith("Las Vegas")) vegas = m;
        assertNotNull(vegas);
        Racing.Session race = Racing.race(vegas);
        long during = race.start + 10 * 60_000L;
        assertTrue(race.live(during));
        Racing.Next n = Racing.soonest(f1, Racing.parse(new JSONArray()), during);
        assertEquals(race.start, n.session.start);
    }

    @Test
    public void motoGpFeedKeepsMotoGpClassAndDropsCancelled() throws Exception {
        JSONArray events = new JSONArray("[{\"kind\":\"GP\",\"id\":\"x\",\"name\":\"QATAR AIRWAYS GRAND PRIX OF QATAR \",\"broadcasts\":["
                + "{\"type\":\"SESSION\",\"shortname\":\"FP1\",\"name\":\"Free Practice Nr. 1\",\"status\":\"FINISHED\",\"date_start\":\"2026-11-06T14:45:00+0300\",\"date_end\":\"2026-11-06T15:30:00+0300\",\"category\":{\"acronym\":\"MGP\"}},"
                + "{\"type\":\"SESSION\",\"shortname\":\"FP1\",\"name\":\"Free Practice Nr. 1\",\"status\":\"FINISHED\",\"date_start\":\"2026-11-06T13:00:00+0300\",\"date_end\":\"2026-11-06T13:35:00+0300\",\"category\":{\"acronym\":\"MT3\"}},"
                + "{\"type\":\"SESSION\",\"shortname\":\"WUP\",\"name\":\"Warm Up\",\"status\":\"NOT-STARTED\",\"date_start\":\"2026-11-08T12:40:00+0300\",\"date_end\":\"2026-11-08T12:50:00+0300\",\"category\":{\"acronym\":\"MGP\"}},"
                + "{\"type\":\"SESSION\",\"shortname\":\"RAC\",\"name\":\"Grand Prix\",\"status\":\"NOT-STARTED\",\"date_start\":\"2026-11-08T19:00:00+0300\",\"date_end\":\"2026-11-08T19:00:00+0300\",\"category\":{\"acronym\":\"MGP\"}}]},"
                + "{\"kind\":\"GP\",\"id\":\"y\",\"name\":\"RED BULL GRAND PRIX OF AUSTRALIA\",\"broadcasts\":["
                + "{\"type\":\"SESSION\",\"shortname\":\"RAC\",\"name\":\"Grand Prix - Cancelled\",\"status\":\"CANCELLED\",\"date_start\":\"2026-10-25T16:30:00+1100\",\"category\":{\"acronym\":\"MGP\"}}]},"
                + "{\"kind\":\"TEST\",\"id\":\"z\",\"name\":\"VALENCIA TEST\",\"broadcasts\":[]}]");
        List<Racing.Meeting> m = Racing.parse(Racing.parseMotoGp(events));
        assertEquals(1, m.size());
        assertEquals("Qatar GP", m.get(0).name);
        assertEquals(2, m.get(0).sessions.size());
        assertEquals("FP1", m.get(0).sessions.get(0).code);
        assertEquals("RACE", m.get(0).sessions.get(1).code);
        assertEquals(Racing.utc("2026-11-08T16:00:00Z"), m.get(0).sessions.get(1).start);
        assertEquals(0, m.get(0).sessions.get(1).end);   // no length given: counts as 2 h
    }

    @Test
    public void f1PageSessionsAreReadAndDeduplicated() throws Exception {
        String s = "{\\\"session\\\":\\\"r\\\",\\\"shortName\\\":\\\"R\\\",\\\"description\\\":\\\"Race\\\",\\\"startTime\\\":\\\"2026-11-21T20:00:00\\\","
                + "\\\"endTime\\\":\\\"2026-11-21T22:00:00\\\",\\\"gmtOffset\\\":\\\"-08:00\\\",\\\"state\\\":\\\"upcoming\\\",\\\"sessionType\\\":\\\"Race\\\"}";
        String q = "{\\\"session\\\":\\\"q\\\",\\\"shortName\\\":\\\"Q\\\",\\\"description\\\":\\\"Qualifying\\\",\\\"startTime\\\":\\\"2026-11-20T20:00:00\\\","
                + "\\\"endTime\\\":\\\"2026-11-20T21:00:00\\\",\\\"gmtOffset\\\":\\\"-08:00\\\",\\\"state\\\":\\\"upcoming\\\",\\\"sessionType\\\":\\\"Qualifying\\\"}";
        JSONArray a = Racing.parseF1Page("<script>self.__next_f.push([1,\"" + s + "," + q + "," + s + "\"])</script>");
        assertEquals(2, a.length());
        assertEquals("QUALI", a.getJSONObject(0).getString("code"));
        assertEquals("RACE", a.getJSONObject(1).getString("code"));
        assertEquals("2026-11-22T04:00:00Z", a.getJSONObject(1).getString("start"));
    }
}
