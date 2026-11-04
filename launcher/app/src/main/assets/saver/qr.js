/* OmarchyTV QR codes (v2.14), on top of Kazuhiko Arase's qrcode-generator (vendor/qrcode/qrcode.js, MIT, unchanged):
 * a record to share on X (the phone opens X with the post already written; the player sends it, nothing is posted
 * by the TV) and the project's page in the boot screen and the Live screensaver. Dark modules on a white quiet zone,
 * drawn once into a small canvas: nothing redraws per frame. Around the launcher it uses the theme's own colours
 * (its light foreground and its dark background), so it looks like part of the screen and still scans. ES5. */
(function (root) {
  "use strict";
  var PROJECT_URL = "https://github.com/piermark/omarchytv";

  /** The QR matrix of text: { n: modules per side, dark(r, c) }. ecc "L" (most data) .. "H". */
  function matrix(text, ecc) {
    var Q = root.qrcode;
    if (!Q) return null;
    var prev = Q.stringToBytes;
    if (Q.stringToBytesFuncs && Q.stringToBytesFuncs["UTF-8"]) Q.stringToBytes = Q.stringToBytesFuncs["UTF-8"];
    try {
      var q = Q(0, ecc || "M");   // type 0: the smallest version that fits
      q.addData(text, "Byte");
      q.make();
      var n = q.getModuleCount();
      return { n: n, dark: function (r, c) { return q.isDark(r, c); } };
    } catch (e) {
      return null;
    } finally {
      Q.stringToBytes = prev;
    }
  }

  /** Rows of "1"/"0", for a native copy (Android's boot screen) and tests. */
  function rows(text, ecc) {
    var m = matrix(text, ecc), out = [];
    if (!m) return out;
    for (var r = 0; r < m.n; r++) { var s = ""; for (var c = 0; c < m.n; c++) s += m.dark(r, c) ? "1" : "0"; out.push(s); }
    return out;
  }

  /** A canvas with the code (quiet zone of 3 modules), sized px wide and high. colors: { dark, light } (default
   *  black on white); keep the light one light and the dark one dark, or phones can't read it. */
  function canvas(doc, text, px, ecc, colors) {
    colors = colors || {};
    var m = matrix(text, ecc);
    if (!m || !doc.createElement) return null;
    var cv = doc.createElement("canvas"), quiet = 3, side = m.n + quiet * 2;
    var cell = Math.max(2, Math.floor(px / side)), size = cell * side;
    cv.width = size; cv.height = size;
    var g = cv.getContext && cv.getContext("2d");
    if (!g) return null;
    g.fillStyle = colors.light || "#ffffff"; g.fillRect(0, 0, size, size);
    g.fillStyle = colors.dark || "#000000";
    for (var r = 0; r < m.n; r++) for (var c = 0; c < m.n; c++) if (m.dark(r, c)) g.fillRect((c + quiet) * cell, (r + quiet) * cell, cell, cell);
    cv.style.imageRendering = "pixelated";
    return cv;
  }

  /** X's "write a post" link with the text (and the project's link) filled in. */
  function shareUrl(text) {
    return "https://x.com/intent/post?text=" + encodeURIComponent(text) + "&url=" + encodeURIComponent(PROJECT_URL);
  }

  root.OmarchyQR = { PROJECT_URL: PROJECT_URL, matrix: matrix, rows: rows, canvas: canvas, shareUrl: shareUrl };
})(typeof window !== "undefined" ? window : this);
