package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Live TV entry points.
 *
 * <p>Amazon Fire OS ships {@code com.amazon.tv.livetv} (the Live TV / DTV guide app) on every
 * Fire TV and Fire Stick. Its real launcher activity is permission-gated
 * ({@code com.amazon.tv.livetv.permission.SHOW_EPG} / {@code TUNE_CHANNEL}), so starting it
 * directly throws a SecurityException. The app exposes unguarded wrapper activities for exactly
 * this purpose:
 *
 * <ul>
 *   <li>{@code .GuideWrapperActivity} -&gt; opens the Live TV channel guide (EPG).</li>
 * </ul>
 *
 * <p>So on Fire OS the TV tile must target the wrapper, not the launcher. On other platforms
 * (Sony, Sharp, Philips, Google TV) the plain launch intent is correct and must be kept.
 */
public class LiveTvTest {

    @Test
    public void fireTvUsesTheGuideWrapperNotTheGatedLauncher() {
        assertEquals(LiveTv.FIRE_GUIDE_WRAPPER, LiveTv.entryActivity(LiveTv.FIRE_LIVE_TV));
    }

    @Test
    public void otherPlatformsKeepTheirPlainLaunchIntent() {
        assertNull(LiveTv.entryActivity("com.sony.dtv.tvx"));
        assertNull(LiveTv.entryActivity("jp.co.sharp.av.android.aquostvapp"));
        assertNull(LiveTv.entryActivity("org.dtvkit.inputsource"));
        assertNull(LiveTv.entryActivity(null));
    }

    @Test
    public void fireLiveTvIsAlwaysACandidate() {
        List<String> c = LiveTv.candidates(Arrays.asList("com.sony.dtv.tvx"));
        assertTrue(c.contains(LiveTv.FIRE_LIVE_TV));
        assertTrue(c.contains("com.sony.dtv.tvx"));
    }

    @Test
    public void candidatesAreDeduplicatedAndBlanksDropped() {
        List<String> c = LiveTv.candidates(Arrays.asList("com.sony.dtv.tvx", "  ", "com.sony.dtv.tvx", LiveTv.FIRE_LIVE_TV));
        assertEquals(1, c.stream().filter(LiveTv.FIRE_LIVE_TV::equals).count());
        assertEquals(1, c.stream().filter("com.sony.dtv.tvx"::equals).count());
        assertTrue(!c.contains(""));
    }

    @Test
    public void aConfiguredFireLiveTvIsNotDuplicated() {
        List<String> c = LiveTv.candidates(Arrays.asList(LiveTv.FIRE_LIVE_TV));
        assertEquals(1, c.stream().filter(LiveTv.FIRE_LIVE_TV::equals).count());
    }
}
