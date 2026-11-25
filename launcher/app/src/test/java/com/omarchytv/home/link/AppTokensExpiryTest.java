package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

/** v2.22: an app token nobody used for its expiry (90 days by default) stops working and goes away by itself. */
public class AppTokensExpiryTest {
    private static final long DAY = 86_400L;

    @Test
    public void unusedTokensExpireAndUseKeepsThemAlive() throws Exception {
        File f = new File(Files.createTempDirectory("tok").toFile(), "apps.json");
        AppTokens t = new AppTokens(f);
        long now = System.currentTimeMillis() / 1000;
        String[] old = t.add("old dashboard", "read", "alert", now - 100 * DAY);           // default 90 days, never used
        String[] used = t.add("busy agent", "notify", "warn", now - 200 * DAY);
        t.byId(used[0]).lastUsed = now - 10 * DAY;                                         // used ten days ago
        String[] forever = t.add("wall clock", "read", "alert", 0, now - 900 * DAY);      // 0 = never expires
        String[] shortLived = t.add("demo", "read", "alert", 7, now - 8 * DAY);
        assertNull(t.find(old[1]));
        assertNotNull(t.find(used[1]));
        assertNotNull(t.find(forever[1]));
        assertNull(t.find(shortLived[1]));
        assertEquals(2, t.size());
        assertEquals(2, new AppTokens(f).size());   // gone from the file too
        assertEquals(0, new AppTokens(f).list().getJSONObject(1).getInt("expireDays"));
        assertNull(t.add("bad", "read", "alert", -1, now));
        assertNull(t.add("bad", "read", "alert", AppTokens.MAX_EXPIRE_DAYS + 1, now));
    }
}
