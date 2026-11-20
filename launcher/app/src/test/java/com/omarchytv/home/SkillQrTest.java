package com.omarchytv.home;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** v2.22 #161: the skill QR carries the public page and, for "this TV", only an address in the fragment. */
public class SkillQrTest {
    @Test
    public void publicUrlIsPinnedToTheVersion() {
        assertEquals(SkillQr.BASE + "?v=2.22.0", SkillQr.url("2.22.0"));
        assertEquals(SkillQr.BASE + "?v=2.22.0-devx", SkillQr.url("2.22.0-dev <x>"));
        assertEquals(SkillQr.BASE, SkillQr.url(""));
    }

    @Test
    public void thisTvAddsOnlyTheAddress() {
        String u = SkillQr.url("2.22.0");
        assertEquals(u + "#tv=192.168.1.40:47800", SkillQr.thisTv(u, "192.168.1.40", 47800));
        assertEquals(u, SkillQr.thisTv(u, null, 47800));
        assertEquals(u, SkillQr.thisTv(u, "evil.example", 47800));
        assertEquals(u, SkillQr.thisTv(u, "10.0.0.2", 0));
    }
}
