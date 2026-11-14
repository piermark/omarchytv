package com.omarchytv.home;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FractalViewQualityTest {

    @Test
    public void bufferNeverExceeds1080pAndMediumIs720p() {
        // 1080p UI: high fills the view, medium/low use the 720p floor
        assertEquals(1080, FractalView.bufferHeight(1, 1080, 1080));
        assertEquals(720, FractalView.bufferHeight(2, 1080, 1080));
        assertEquals(720, FractalView.bufferHeight(3, 1080, 1080));
        assertEquals(1f, FractalView.scaleFor(1, 1080), 1e-6f);
        assertEquals(720f / 1080f, FractalView.scaleFor(2, 1080), 1e-6f);
        assertEquals(720f / 1080f, FractalView.scaleFor(3, 1080), 1e-6f);
        assertEquals(1f, FractalView.scaleFor(3, 720), 1e-6f);

        // 4K view (Sony panel, 1080p UI sometimes reports the native size): cap at 1080p, never 2160
        assertEquals(1080, FractalView.bufferHeight(0, 2160, 1080));
        assertEquals(1080, FractalView.bufferHeight(1, 2160, 1080));
        assertEquals(720, FractalView.bufferHeight(2, 2160, 1080));
        assertEquals(1080f / 2160f, FractalView.scaleFor(1, 2160), 1e-6f);
        int[] high4k = FractalView.bufferSize(1, 3840, 2160, 1080);
        assertArrayEquals(new int[] { 1920, 1080 }, high4k);
        int[] med4k = FractalView.bufferSize(2, 3840, 2160, 1080);
        assertArrayEquals(new int[] { 1280, 720 }, med4k);
        assertTrue(FractalView.LEVELS[0].scale >= 0.99f);
        assertTrue(FractalView.LEVELS[1].scale >= 0.99f);
    }

    @Test
    public void firstLevelStartsMaliT8xxAndLowRamAtLight() {
        assertEquals(FractalView.LIGHT_LEVEL, FractalView.firstLevel("Mali-450 MP", false, 0));
        assertEquals(FractalView.LIGHT_LEVEL, FractalView.firstLevel("anything", true, 8L * 1024 * 1024 * 1024));
        assertEquals(FractalView.LIGHT_LEVEL, FractalView.firstLevel("Mali-T860", false, 0));
        assertEquals(FractalView.LIGHT_LEVEL, FractalView.firstLevel("Mali-T760", false, 0));
        assertEquals(FractalView.LIGHT_LEVEL, FractalView.firstLevel("Mali-G52", false, 2L * 1024 * 1024 * 1024));
        assertEquals(1, FractalView.firstLevel("Mali-G52", false, 0));
        assertEquals(1, FractalView.firstLevel("Mali-G52", false, 4L * 1024 * 1024 * 1024));
        assertEquals(FractalView.LIGHT_LEVEL, FractalView.firstLevel("Adreno (TM) 630", false, 1900L * 1024 * 1024));
        assertEquals(FractalView.LIGHT_LEVEL, FractalView.firstLevel("Mali-G52", false, 4L * 1024 * 1024 * 1024, true));
        assertEquals("light", FractalView.LEVELS[FractalView.LIGHT_LEVEL].name);
        assertTrue(FractalView.isLight(4));
        assertFalse(FractalView.isLight(2));
        assertEquals(1, FractalView.cheapScaleFor(FractalView.LIGHT_LEVEL, 1));
        assertEquals(160, FractalView.BAKE_H);
        assertEquals(20, FractalView.BAKE_ITERS);
    }

    @Test
    public void presentCostDetectsSwapBackpressureNotTheFpsCap() {
        long cap24 = 1_000_000_000L / 24;           // ~41.7 ms
        assertFalse(FractalView.isBackpressured(cap24, 24));
        assertFalse(FractalView.isBackpressured(cap24 + 5_000_000L, 24));
        assertTrue(FractalView.isBackpressured(cap24 + 20_000_000L, 24));
        assertTrue(FractalView.isBackpressured(80_000_000L, 24));
        assertFalse(FractalView.shouldDisableAtFloor(2, 5));
        assertFalse(FractalView.shouldDisableAtFloor(3, 1));
        assertTrue(FractalView.shouldDisableAtFloor(3, 2));
        assertEquals(24, FractalView.HOME_FPS);
        assertEquals(30, FractalView.SAVER_FPS);
        assertEquals(24, FractalView.sceneFps("Mali-T860", 0));
        assertEquals(24, FractalView.sceneFps("Mali-G52", 2L * 1024 * 1024 * 1024));
        assertEquals(30, FractalView.sceneFps("Mali-G52", 4L * 1024 * 1024 * 1024));
        assertEquals(30, FractalView.sceneFps("Mali-G52", 0));
    }

    @Test
    public void startupStallIsIgnoredDuringGrace() {
        // no layout yet
        assertTrue(FractalView.inGracePeriod(false, 0, 0));
        assertTrue(FractalView.inGracePeriod(false, 100, 4000));
        // laid out but no frames presented, still inside the 5 s window
        assertTrue(FractalView.inGracePeriod(true, 0, 1_000));
        // enough frames but still inside the 5 s resume/attach window
        assertTrue(FractalView.inGracePeriod(true, 100, FractalView.GRACE_MS - 1));
        // the Sony cold-start 600 ms stall: first layout, no frames, a few hundred ms in
        assertTrue(FractalView.inGracePeriod(true, 0, 600));
        // grace over once laid out, N frames presented, and 5 s elapsed
        assertFalse(FractalView.inGracePeriod(true, FractalView.GRACE_FRAMES, FractalView.GRACE_MS));
        assertFalse(FractalView.inGracePeriod(true, FractalView.GRACE_FRAMES, FractalView.GRACE_MS + 1));
        // time alone ends grace, even with almost no frames (and even if not laid out)
        assertFalse(FractalView.inGracePeriod(true, FractalView.GRACE_FRAMES - 1, FractalView.GRACE_MAX_MS));
        assertFalse(FractalView.inGracePeriod(false, 100, FractalView.GRACE_MAX_MS));
        assertFalse(FractalView.inGracePeriod(true, 0, FractalView.GRACE_MAX_MS));
        assertEquals(8000, FractalView.GRACE_MAX_MS);
        assertTrue(FractalView.isUiStall(600_000_000L));
        assertFalse(FractalView.isUiStall(499_000_000L));
    }

    @Test
    public void singleStallDegradesNotKills() {
        assertEquals(FractalView.STALL_DEGRADE, FractalView.stallAction(1, false, 1));
        assertEquals(FractalView.STALL_DEGRADE, FractalView.stallAction(2, false, 1));
        assertEquals(FractalView.STALL_DEGRADE, FractalView.stallAction(3, false, 1));
        assertEquals(FractalView.STALL_DEGRADE, FractalView.stallAction(3, true, 1));
        assertEquals(FractalView.STALL_DEGRADE, FractalView.stallAction(3, true, 2));
        assertEquals(2, FractalView.countStallsInWindow(new long[] { 1000 }, 1, 5000, FractalView.STALL_WINDOW_MS));
        assertEquals(1, FractalView.countStallsInWindow(new long[] { 1000 }, 1, 30_000, FractalView.STALL_WINDOW_MS));
    }

    @Test
    public void repeatedStallsAtFloorKill() {
        assertEquals(FractalView.STALL_KILL, FractalView.stallAction(3, true, 3));
        assertEquals(FractalView.STALL_KILL, FractalView.stallAction(3, true, 4));
        assertEquals(FractalView.STALL_DEGRADE, FractalView.stallAction(2, true, 5));
        assertEquals(FractalView.STALL_DEGRADE, FractalView.stallAction(1, true, 5));
        // at the floor, even if iterations were reset (the old reducedIters guard)
        assertEquals(FractalView.STALL_KILL, FractalView.stallAction(3, false, 5));
        assertEquals(3, FractalView.STALLS_TO_KILL);
    }

    @Test
    public void sonyPersistentFloorStallsWithResetItersKill() {
        // Exact Sony KD-55XE9005 pattern (fix5 log): "UI stall 6xx-7xxms: floor, reduce iters/fps cap=24"
        // every ~750 ms, with iterMul/fpsCap reset to the defaults after each degrade.
        long t = 0;
        long[] times = new long[16];
        int n = 0;
        int last = FractalView.STALL_DEGRADE;
        for (int i = 0; i < 8; i++) {
            t += 750;
            int count = FractalView.countStallsInWindow(times, n, t, FractalView.STALL_WINDOW_MS);
            if (n < times.length) times[n++] = t;
            last = FractalView.stallAction(3, false, count);   // reducedIters always false: the reset bug
            if (i < 2) assertEquals("stall " + (i + 1) + " must only degrade", FractalView.STALL_DEGRADE, last);
            else {
                assertEquals("stall " + (i + 1) + " at floor must kill", FractalView.STALL_KILL, last);
                break;
            }
        }
        assertEquals(FractalView.STALL_KILL, last);
        assertEquals(360, FractalView.internalHeight(720, 2));
        assertEquals(240, FractalView.internalHeight(720, 3));
        assertEquals(720, FractalView.internalHeight(720, 1));
        assertEquals(2, FractalView.cheapScaleFor(3, 1));
        assertEquals(1, FractalView.cheapScaleFor(2, 1));
        assertEquals(12, FractalView.CHEAP_FPS);
    }

    @Test
    public void sonySparsePresentsEndGraceAndLongStallsKill() {
        // Exact Sony KD-55XE9005 fix6 log with wallpaper radiomarchy:fractals (17:30 Europe/Rome):
        //   17:30:45 grace: ignore UI stall 6499ms presented=3 elapsed=35139ms
        //   17:30:49 grace: ignore UI stall 3533ms presented=4 elapsed=38718ms
        //   17:30:53 grace: ignore UI stall 3805ms presented=5 elapsed=42533ms
        //   17:30:59 grace: ignore UI stall 6016ms presented=6 elapsed=48570ms
        //   17:31:01 grace: ignore UI stall 2349ms presented=7 elapsed=50935ms
        // Wallpaper was presenting ~0.15 fps; grace required 8 frames so it never ended.
        int[] presented = { 3, 4, 5, 6, 7 };
        long[] elapsed = { 35139, 38718, 42533, 48570, 50935 };
        long[] gap = { 6499, 3533, 3805, 6016, 2349 };
        for (int i = 0; i < presented.length; i++) {
            assertFalse("elapsed " + elapsed[i] + "ms presented=" + presented[i] + " must be past grace",
                    FractalView.inGracePeriod(true, presented[i], elapsed[i]));
            assertEquals("stall " + gap[i] + "ms at light must kill",
                    FractalView.STALL_KILL,
                    FractalView.afterGraceAction(FractalView.LIGHT_LEVEL, gap[i], 0, 1));
        }
        // still in grace early on, even with no frames
        assertTrue(FractalView.inGracePeriod(true, 3, 4000));
        assertTrue(FractalView.inGracePeriod(true, 0, 600));
        // a short stall at light degrades (does not kill) until 3 in the window or a 2 s gap
        assertEquals(FractalView.STALL_DEGRADE, FractalView.afterGraceAction(FractalView.LIGHT_LEVEL, 600, 0, 1));
        assertEquals(FractalView.STALL_DEGRADE, FractalView.afterGraceAction(FractalView.LIGHT_LEVEL, 600, 2, 1));
        assertEquals(FractalView.STALL_KILL, FractalView.afterGraceAction(FractalView.LIGHT_LEVEL, 600, 3, 1));
        assertEquals(FractalView.STALL_KILL, FractalView.afterGraceAction(FractalView.LIGHT_LEVEL, 2000, 0, 1));
        // not cheapest: a long stall does not by itself turn the picture off
        assertEquals(FractalView.STALL_DEGRADE, FractalView.afterGraceAction(1, 6499, 0, 1));
        assertTrue(FractalView.isSlowPresent(1_000_000_000L));
        assertFalse(FractalView.isSlowPresent(999_000_000L));
        assertTrue(FractalView.isLongStall(2000));
        assertFalse(FractalView.isLongStall(1999));
    }

    @Test
    public void preferPlainUsesWeakGpuAndTightRam() {
        long two = 2L * 1024 * 1024 * 1024;
        long over = two + 200L * 1024 * 1024;   // a 2 GB Sony may report a little over 2 GiB
        assertTrue(FractalView.tightRam(two));
        assertTrue(FractalView.tightRam(over));
        assertFalse(FractalView.tightRam(0));
        assertFalse(FractalView.tightRam(4L * 1024 * 1024 * 1024));
        assertTrue(FractalView.weakGpu("Mali-T860"));
        assertTrue(FractalView.weakGpu("Mali-450 MP"));
        assertFalse(FractalView.weakGpu("Mali-G52"));
        assertTrue(FractalView.preferPlain("Mali-T860", 0, false, false));
        assertTrue(FractalView.preferPlain("", two, false, false));
        assertTrue(FractalView.preferPlain("Mali-G52", over, false, false));
        assertTrue(FractalView.preferPlain("Mali-G52", 0, true, false));
        assertTrue(FractalView.preferPlain("Mali-G52", 0, false, true));
        assertFalse(FractalView.preferPlain("Mali-G52", 4L * 1024 * 1024 * 1024, false, false));
        assertFalse(FractalView.preferPlain("", 0, false, false));
    }

    @Test
    public void sonyRadiomarchyOpenFloorStallsKillEvenIfPinned() {
        // Exact Sony KD-55XE9005 log when opening Radiomarchy (17:57 Europe/Rome), integ+fix7:
        //   17:57:47 Choreographer Skipped 64 frames
        //   17:58:11 UI stall 1416ms: floor, reduce iters/fps cap=24 cheap=1
        //   17:58:12 716ms, 13 650ms, 15 633ms, 16 649ms, 18 600ms
        // Light was not selected (cheap=1 live floor path). Kill never fired because the
        // floor branch is taken when quality is pinned (canDrop=false) at a mid level.
        long[] gaps = { 1416, 716, 650, 633, 649, 600 };
        long t = 0;
        long[] times = new long[16];
        int n = 0;
        int last = FractalView.STALL_DEGRADE;
        for (int i = 0; i < gaps.length; i++) {
            t += (i == 0 ? 0 : (gaps[i] < 700 ? 1500 : 2000));
            t += 1;
            int count = FractalView.countStallsInWindow(times, n, t, FractalView.STALL_WINDOW_MS);
            if (n < times.length) times[n++] = t;
            last = FractalView.afterGraceAction(1, gaps[i], 0, count, false);
            if (i < 2) assertEquals("stall " + (i + 1) + " (" + gaps[i] + "ms) must only degrade",
                    FractalView.STALL_DEGRADE, last);
            else {
                assertEquals("stall " + (i + 1) + " (" + gaps[i] + "ms) at a floor that cannot drop must kill",
                        FractalView.STALL_KILL, last);
                break;
            }
        }
        assertEquals(FractalView.STALL_KILL, last);
        // a single 1416 ms stall is under 2 s, so it still only degrades
        assertEquals(FractalView.STALL_DEGRADE, FractalView.afterGraceAction(1, 1416, 0, 1, false));
        assertEquals(FractalView.STALL_KILL, FractalView.afterGraceAction(1, 2000, 0, 1, false));
        // when we can still drop, a mid-level stall does not kill on count alone
        assertEquals(FractalView.STALL_DEGRADE, FractalView.afterGraceAction(1, 716, 0, 5, true));
    }

    @Test
    public void contrastPushesADarkAccentOffADarkBackground() {
        int bg = 0xFF111111;
        int dark = 0xFF1A1A1A;
        assertTrue(FractalView.contrastRatio(dark, bg) < 4.5f);
        int fixed = FractalView.ensureContrast(dark, bg);
        assertTrue(FractalView.contrastRatio(fixed, bg) >= 4.5f);
        int already = 0xFF7AA2F7;
        assertEquals(already, FractalView.ensureContrast(already, bg));
    }
}
