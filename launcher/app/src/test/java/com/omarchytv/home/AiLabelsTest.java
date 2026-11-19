package com.omarchytv.home;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class AiLabelsTest {
    @Test
    public void sharedProviderWordMovesOut() {
        java.util.List<String> names = Arrays.asList("Claude session", "Claude week", "Claude week (Fable)");
        String p = AiLabels.commonPrefix(names);
        assertEquals("Claude", p);
        assertEquals("session", AiLabels.strip("Claude session", p));
        assertEquals("week (Fable)", AiLabels.strip("Claude week (Fable)", p));
    }

    @Test
    public void noPrefixWhenProvidersDifferOrOneEntry() {
        assertEquals("", AiLabels.commonPrefix(Arrays.asList("Grok API", "Claude week")));
        assertEquals("", AiLabels.commonPrefix(Collections.singletonList("Claude week")));
        assertEquals("", AiLabels.commonPrefix(null));
        // a partial word is not a prefix
        assertEquals("", AiLabels.commonPrefix(Arrays.asList("Codex", "Code week")));
        assertEquals("Grok API", AiLabels.strip("Grok API", ""));
    }

    @Test
    public void neverEmptiesARow() {
        // "Claude" alone is the provider's own row: keep it, take nothing
        assertEquals("", AiLabels.commonPrefix(Arrays.asList("Claude", "Claude week")));
        // two shared words, a third left on each row
        assertEquals("Claude Max", AiLabels.commonPrefix(Arrays.asList("Claude Max session", "Claude Max week")));
        // identical names: keep all but the last word
        assertEquals("Claude", AiLabels.commonPrefix(Arrays.asList("Claude week", "Claude week")));
    }

    @Test
    public void labelColumnFollowsTheRoom() {
        java.util.List<String> labels = Arrays.asList("session", "week", "week (Fable)");
        assertEquals(12, AiLabels.labelCols(labels, 30));           // fits
        assertEquals(30 - 4 - 5 - 2, AiLabels.labelCols(Arrays.asList("a very long label for a credit"), 30));
        assertEquals(8, AiLabels.labelCols(labels, 0));             // width unknown: as before
        assertEquals(4, AiLabels.labelCols(Arrays.asList("cpu"), 30));
        assertEquals(4, AiLabels.labelCols(labels, 6));             // never below 4
    }
}
