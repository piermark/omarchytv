package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v2.21 Hyprland-style tiling of agent pages. */
public class AgentPageTileTest {
    static boolean overlap(int[] a, int[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    @Test
    public void windowsFillTheScreenWithGapsAndNeverOverlap() {
        for (int n = 1; n <= 4; n++) {
            int[][] r = AgentPageActivity.tile(n, 1920, 1080, 10);
            assertEquals(n, r.length);
            for (int i = 0; i < n; i++) {
                assertTrue(r[i][0] >= 0 && r[i][1] >= 0 && r[i][2] <= 1920 && r[i][3] <= 1080);
                assertTrue(r[i][2] - r[i][0] > 400 && r[i][3] - r[i][1] > 200);
                for (int j = i + 1; j < n; j++) assertTrue("overlap " + n, !overlap(r[i], r[j]));
            }
        }
        int[][] three = AgentPageActivity.tile(3, 1920, 1080, 10);
        assertEquals(1060, three[0][3] - three[0][1]);   // dwindle: a tall master on the left
        assertEquals(three[1][2] - three[1][0], three[2][2] - three[2][0]);
    }
}
