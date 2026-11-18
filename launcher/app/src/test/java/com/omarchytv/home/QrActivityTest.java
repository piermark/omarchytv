package com.omarchytv.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QrActivityTest {
    @Test
    public void textIsEscapedInTheScriptAndTheMarkup() {
        String h = QrActivity.html("<b>T</b>", "otva_x\"</script><img src=x>", "hint", true, 0x1a1b26, 0xffffff, 0x7aa2f7);
        assertFalse(h.contains("</script><img"));
        assertFalse(h.contains("<b>T</b>"));
        assertTrue(h.contains("&lt;b&gt;T&lt;/b&gt;"));
        assertTrue(h.contains("qrcode(0,'M')"));
        assertTrue(h.contains("#1a1b26"));
    }
}
