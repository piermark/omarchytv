package com.omarchytv.home;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * v2.14 QR code of OmarchyTV on the Android boot screen (setting "boot + Live", off by default): the project page,
 * precomputed with qrcode-generator (Kazuhiko Arase, MIT) through webos/app/qr.js; webos/tests/qr.test.js checks
 * these rows still match. Drawn in the theme's own colours (light foreground, dark background), so it scans.
 */
final class BootQr {
    static final String URL = "https://github.com/piermark/omarchytv";
    static final String[] ROWS = {
            "11111110101011000100001111111",
            "10000010101010000111001000001",
            "10111010100011010011101011101",
            "10111010000010100100101011101",
            "10111010101101101011001011101",
            "10000010000001100100101000001",
            "11111110101010101010101111111",
            "00000000010100100001100000000",
            "10011111110111100100010010111",
            "00101100100000110011010110110",
            "10110111100100110110100110100",
            "01001101010110101100011101001",
            "10011011001111101001001100001",
            "00111100110000011000001111111",
            "10110010111100110111111010101",
            "11000101111111111011000110101",
            "10111011110000010001110001000",
            "11001101111110010011000010110",
            "11011010000111111011000011001",
            "11001000101011010001001001100",
            "11011111101001010110111111110",
            "00000000111011010110100011000",
            "11111110110001011001101011000",
            "10000010100101001101100010011",
            "10111010111001011001111111001",
            "10111010111000111110010000001",
            "10111010011110010100110110111",
            "10000010011010000000001101101",
            "11111110110110100001101010000"
    };

    private BootQr() {
    }

    /** Draws the code with a quiet zone of 3 modules into the square at x, y of side size. */
    static void draw(Canvas c, float x, float y, float size, int light, int dark, int alpha, Paint p) {
        int n = ROWS.length, side = n + 6;
        float cell = size / side;
        p.setStyle(Paint.Style.FILL);
        p.setColor((light & 0xFFFFFF) | (alpha << 24));
        c.drawRect(x, y, x + size, y + size, p);
        p.setColor((dark & 0xFFFFFF) | (alpha << 24));
        for (int r = 0; r < n; r++) {
            String row = ROWS[r];
            for (int k = 0; k < n; k++) {
                if (row.charAt(k) == '1') c.drawRect(x + (k + 3) * cell, y + (r + 3) * cell, x + (k + 4) * cell + 0.5f, y + (r + 4) * cell + 0.5f, p);
            }
        }
    }
}
