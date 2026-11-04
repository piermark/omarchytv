// node --test webos/tests/qr.test.js — OmarchyTV QR codes (record sharing, boot, Live) on qrcode-generator (MIT).
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const APP = path.join(__dirname, "../app");
const ASSETS = path.join(__dirname, "../../launcher/app/src/main/assets");
function load() {
  const c = {};
  c.window = c;
  vm.createContext(c);
  ["vendor/qrcode/qrcode.js", "qr.js", "games/i18n.js"].forEach((f) => vm.runInContext(fs.readFileSync(path.join(APP, f), "utf8"), c, { filename: f }));
  return c;
}

test("the vendored qrcode.js is unchanged and Android has the very same copies", () => {
  const v = fs.readFileSync(path.join(APP, "vendor/qrcode/qrcode.js"), "utf8");
  assert.ok(v.indexOf("Copyright (c) 2009 Kazuhiko Arase") > 0 && v.indexOf("Licensed under the MIT license") > 0);
  assert.ok(fs.existsSync(path.join(APP, "vendor/qrcode/LICENSE")));
  assert.ok(fs.existsSync(path.join(ASSETS, "licenses/qrcode-generator-MIT.txt")));
  ["saver", "games"].forEach((d) => {
    assert.strictEqual(fs.readFileSync(path.join(ASSETS, d, "qrcode.js"), "utf8"), v, d + "/qrcode.js");
    assert.strictEqual(fs.readFileSync(path.join(ASSETS, d, "qr.js"), "utf8"), fs.readFileSync(path.join(APP, "qr.js"), "utf8"), d + "/qr.js");
    const html = fs.readFileSync(path.join(ASSETS, d, "index.html"), "utf8");
    assert.ok(html.indexOf('src="qrcode.js"') > 0 && html.indexOf('src="qr.js"') > html.indexOf('src="qrcode.js"'), d);
  });
  const web = fs.readFileSync(path.join(APP, "index.html"), "utf8");
  assert.ok(web.indexOf('src="vendor/qrcode/qrcode.js"') > 0 && web.indexOf('src="qr.js"') > 0);
});

test("Android's boot QR rows are the project page's code", () => {
  const c = load(), java = fs.readFileSync(path.join(__dirname, "../../launcher/app/src/main/java/com/omarchytv/home/BootQr.java"), "utf8");
  const rows = (java.match(/"([01]+)"/g) || []).map((s) => s.slice(1, -1));
  assert.deepStrictEqual(rows, Array.from(c.OmarchyQR.rows(c.OmarchyQR.PROJECT_URL, "M")));
  assert.ok(java.indexOf('URL = "' + c.OmarchyQR.PROJECT_URL + '"') > 0);
  assert.strictEqual(rows.length, 29);
});

test("the matrix has finder patterns in three corners", () => {
  const c = load(), m = c.OmarchyQR.matrix("https://example.com", "M");
  [[0, 0], [0, m.n - 7], [m.n - 7, 0]].forEach(([r, k]) => {
    for (let i = 0; i < 7; i++) { assert.ok(m.dark(r, k + i)); assert.ok(m.dark(r + 6, k + i)); assert.ok(m.dark(r + i, k)); }
    assert.ok(m.dark(r + 3, k + 3));
  });
});

test("every language's share post fits a code a phone reads across the room", () => {
  const c = load(), I = c.OmarchyArcadeI18n;
  Object.keys(I).forEach((l) => {
    ["V-HOLD", "RICE WARS", "FORK BOMB"].forEach((game) => {
      const text = I[l].share_post.replace("{score}", "999999").replace("{game}", game);
      assert.ok(text.indexOf("#OmarchyTV") >= 0, l);
      const url = c.OmarchyQR.shareUrl(text);
      assert.ok(url.indexOf("https://x.com/intent/post?text=") === 0);
      assert.ok(url.indexOf("url=https%3A%2F%2Fgithub.com%2Fpiermark%2Fomarchytv") > 0);
      const m = c.OmarchyQR.matrix(url, "L");
      assert.ok(m && m.n <= 73, l + " " + game + " " + (m && m.n));
    });
  });
});

test("the QR is drawn in the given colours, quiet zone included", () => {
  const c = load(), ops = [];
  const g = { fillRect: (x, y, w, h) => ops.push([g.fillStyle, x, y, w, h]), fillStyle: "" };
  const doc = { createElement: () => ({ style: {}, getContext: () => g }) };
  const cv = c.OmarchyQR.canvas(doc, "https://github.com/piermark/omarchytv", 140, "M", { light: "#c0caf5", dark: "#1a1b26" });
  assert.ok(cv && cv.width === cv.height && cv.width <= 140);
  assert.deepStrictEqual(ops[0], ["#c0caf5", 0, 0, cv.width, cv.width]);
  assert.ok(ops.slice(1).every((o) => o[0] === "#1a1b26" && o[1] >= 3 * o[3] && o[2] >= 3 * o[3]));
});

test("settings: Live by default, boot only when asked, never on the OmaFire wallpaper", () => {
  const app = fs.readFileSync(path.join(APP, "app.js"), "utf8");
  assert.ok(/shareQr: "live"/.test(app));
  assert.ok(app.indexOf('qr: cfg.shareQr === "boot"') > 0);
  assert.ok(app.indexOf('qr: cfg.shareQr !== "off"') > 0);
  ["omafire-wall.js", "omafire-audio.js"].forEach((f) => assert.ok(fs.readFileSync(path.join(APP, "vendor/omafire", f), "utf8").indexOf("OmarchyQR") < 0));
  const cfg = fs.readFileSync(path.join(__dirname, "../../launcher/app/src/main/java/com/omarchytv/home/LauncherConfig.java"), "utf8");
  assert.ok(cfg.indexOf('optString("shareQr", "live")') > 0);
});
