package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class BootScriptTest {

    /** Answers at once: network up, PC connected. */
    private static final BootScript.Live READY = (key, timedOut) -> "net".equals(key)
            ? new BootScript.Status(true, "Connected to Wi-Fi \"Casa\", 192.168.1.50.")
            : new BootScript.Status(true, "omarchy-link: connected to omarchy-pc.");

    /** Never knows: every live line waits until it times out. */
    private static final BootScript.Live NEVER = (key, timedOut) -> timedOut
            ? new BootScript.Status(false, key + " not reachable.") : null;

    private static BootScript script(long ram, BootScript.Live live) {
        return BootScript.build("2.14.0", "gruvbox", "Sony BRAVIA 4K GB", ram, "3840x2160 @ 60Hz", 34,
                "user", "omarchytv", live);
    }

    private static boolean has(BootScript s, String text) {
        for (BootScript.Line l : s.lines) if (l.text.contains(text)) return true;
        return false;
    }

    private static BootScript.Line first(BootScript s, BootScript.Kind k) {
        for (BootScript.Line l : s.lines) if (l.kind == k) return l;
        return null;
    }

    private static BootScript.Line keyed(BootScript s, String key) {
        for (BootScript.Line l : s.lines) if (key.equals(l.key)) return l;
        return null;
    }

    /** Plays the script in 33 ms frames up to realMs, as the views do. */
    private static int play(BootScript s, int realMs) {
        int ms = 0;
        for (int r = 0; r <= realMs; r += 33) ms = s.clock(r);
        return ms;
    }

    @Test
    public void shortEnoughToNotAnnoy() {
        BootScript s = script(2048, READY);
        assertTrue("ends in under 8 s, was " + s.endAt, s.endAt < 8000);
        assertEquals(s.fadeAt + BootScript.FADE_MS, s.endAt);
    }

    @Test
    public void crtOpensBeforeTheFirstLine() {
        BootScript s = script(2048, READY);
        assertEquals(BootScript.CRT_MS, s.lines.get(0).at);
        assertTrue(s.visibleAt(BootScript.CRT_MS - 1).isEmpty());
        assertEquals(1f, BootScript.crt(BootScript.CRT_MS), 0f);
    }

    @Test
    public void linesAppearInOrderAndFit() {
        int last = -1;
        for (BootScript.Line l : script(2048, READY).lines) {
            assertTrue(l.at >= last);
            last = l.at;
            assertTrue("too wide: " + l.text, l.text.length() <= BootScript.COLUMNS);
        }
    }

    @Test
    public void biosShowsTheRealTv() {
        BootScript s = script(2048, READY);
        assertTrue(has(s, "OmarchyBIOS v2.14.0"));
        assertTrue(has(s, "System Model   : Sony BRAVIA 4K GB"));
        assertTrue(has(s, "Display Mode   : 3840x2160 @ 60Hz"));
        assertTrue(has(s, "Loaded theme gruvbox."));
        assertTrue(has(s, "Indexed 34 apps."));
        assertFalse(has(s, "HDMI-CEC"));
    }

    @Test
    public void unknownValuesLeaveTheirLinesOut() {
        BootScript s = BootScript.build("2.14.0", "gruvbox", "TV", 0, "", -1, "u", "h", READY);
        assertFalse(has(s, "Memory Testing"));
        assertFalse(has(s, "Display Mode"));
        assertFalse(has(s, "Indexed"));
    }

    @Test
    public void memoryCountsUpThenSettles() {
        BootScript.Line mem = first(script(2048, READY), BootScript.Kind.MEMTEST);
        assertEquals("Memory Testing : 0 MB", BootScript.memText(mem, mem.at));
        assertEquals("Memory Testing : 1024 MB", BootScript.memText(mem, mem.at + mem.anim / 2));
        assertEquals("Memory Testing : 2048 MB OK", BootScript.memText(mem, mem.at + mem.anim));
    }

    @Test
    public void readyAnswersShowTheRealNetworkAndPcWithoutDelay() {
        BootScript s = script(2048, READY);
        play(s, s.fadeAt);
        assertEquals(s.fadeAt, s.clock(s.fadeAt));   // no hold: the clock runs with real time
        assertEquals("Connected to Wi-Fi \"Casa\", 192.168.1.50.", keyed(s, "net").doneText());
        assertEquals("omarchy-link: connected to omarchy-pc.", keyed(s, "pc").doneText());
        assertTrue(keyed(s, "pc").doneOk());
    }

    @Test
    public void spinnerReallyWaitsThenWarnsOnTimeout() {
        BootScript s = script(2048, NEVER);
        BootScript.Line net = keyed(s, "net");
        int end = net.at + net.anim;
        assertEquals(end, play(s, end + 1000));            // held at the end of the spinner
        assertTrue(BootScript.spinning(net, end));
        assertNull(net.result);
        int after = play(s, end + BootScript.MAX_WAIT_MS + 200);
        assertFalse(net.doneOk());                         // gave up: drawn [ WARN ]
        assertEquals("net not reachable.", net.doneText());
        assertTrue("script goes on after the wait", after > end);
        assertTrue(after < end + 400);
    }

    @Test
    public void answerArrivingLateContinuesFromThere() {
        final int[] now = {0};
        BootScript.Live late = (key, timedOut) -> now[0] >= 4000 || timedOut
                ? new BootScript.Status(true, key + " ok") : null;
        BootScript s = script(2048, late);
        BootScript.Line net = keyed(s, "net");
        int ms = 0;
        for (int r = 0; r <= 4500; r += 33) {
            now[0] = r;
            ms = s.clock(r);
        }
        assertTrue(net.doneOk());
        assertEquals("net ok", net.doneText());
        assertTrue(ms > net.at + net.anim);
    }

    @Test
    public void failingProbeNeverStopsTheBootScreen() {
        BootScript s = script(2048, (key, timedOut) -> {
            throw new IllegalStateException("boom");
        });
        int ms = play(s, 15000);
        assertFalse(keyed(s, "net").doneOk());
        assertFalse(keyed(s, "pc").doneOk());
        assertTrue("reaches the fade, was " + ms, ms >= s.fadeAt);
    }

    @Test
    public void liveTextIsCleanedAndFits() {
        BootScript s = script(2048, (key, timedOut) -> new BootScript.Status(true, "Wi-Fi \u001b[2J" + "x".repeat(100)));
        play(s, 8000);
        String t = keyed(s, "net").doneText();
        assertFalse(t.contains("\u001b"));
        assertTrue(t.length() <= 64);
    }

    @Test
    public void badgeOnlyDuringTheBiosThenAFlicker() {
        BootScript s = script(2048, READY);
        assertEquals(5, s.badge.size());
        BootScript.Line clear = first(s, BootScript.Kind.CLEAR);
        assertEquals(s.biosEnd, clear.at);
        assertTrue(s.flickering(clear.at));
        assertFalse(s.flickering(clear.at + BootScript.FLICKER_MS));
    }

    @Test
    public void endsWithTheTypedCommandAndTheWordmark() {
        BootScript s = script(2048, READY);
        BootScript.Line p = first(s, BootScript.Kind.PROMPT);
        assertEquals("omarchy-tv", p.detail);
        assertEquals(p.detail.length(), BootScript.typedChars(p, p.at + p.anim));
        List<BootScript.Line> end = s.visibleAt(s.fadeAt);
        assertEquals("Welcome to Omarchy TV!", end.get(0).text);
        int logo = 0;
        for (BootScript.Line l : end) if (l.kind == BootScript.Kind.LOGO) logo++;
        assertEquals(BlockFont.ROWS, logo);
        assertEquals(BootScript.Kind.INFO, end.get(end.size() - 1).kind);
    }

    @Test
    public void wordmarkRowsAreTheBlockFont() {
        List<String> rows = BootScript.blockRows("OMARCHY TV");
        char[][] ref = BlockFont.render("OMARCHY TV");
        for (int r = 0; r < BlockFont.ROWS; r++) assertEquals(new String(ref[r]).replace('#', '█'), rows.get(r));
    }

    @Test
    public void controlCharactersAndEmptyValuesAreCleaned() {
        BootScript s = BootScript.build(null, "", "Evil\u001b[2JTV", 0, null, -1, " ", null, null);
        assertTrue(has(s, "OmarchyBIOS vdev"));
        assertTrue(has(s, "Loaded theme tokyo-night."));
        assertTrue(has(s, "System Model   : Evil[2JTV"));
        assertTrue(has(s, "omarchytv login: user"));
    }

    @Test
    public void bootScreenModeDefaultsToBoot() {
        assertEquals("boot", LauncherConfig.bootScreenMode(null));
        assertEquals("boot", LauncherConfig.bootScreenMode("nonsense"));
        assertEquals("wake", LauncherConfig.bootScreenMode(" Wake "));
        assertEquals("off", LauncherConfig.bootScreenMode("off"));
    }
}
