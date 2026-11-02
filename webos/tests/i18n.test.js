// node --test webos/tests/i18n.test.js — translations for LG webOS / Samsung Tizen (i18n.js, lang.js).
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const I18N = require("../app/i18n.js");
const L = require("../app/lang.js");

const APP = fs.readFileSync(__dirname + "/../app/app.js", "utf8");
const en = (() => {
  const a = APP.indexOf("    en: {"), b = APP.indexOf("\n    it: {", a);
  const block = APP.slice(a, b);
  const keys = [...block.matchAll(/(?:^|[,{])\s*([a-z_][a-z_0-9]*): "/gm)].map((m) => m[1]);
  const vals = Object.fromEntries([...block.matchAll(/(?:^|[,{])\s*([a-z_][a-z_0-9]*): "((?:[^"\\]|\\.)*)"/gm)].map((m) => [m[1], m[2]]));
  const wmo = [...block.match(/wmo: \{([^}]*)\}/)[1].matchAll(/(\d+):/g)].map((m) => m[1]);
  return { keys, vals, wmo };
})();

test("every language beyond en/it/de has a translation", () => {
  for (const l of L.LANGS) if (["en", "it", "de"].indexOf(l) < 0) assert.ok(I18N[l], "missing " + l);
  assert.strictEqual(Object.keys(I18N).length, L.LANGS.length - 3);
});
test("same keys as English, nothing empty, trailing spaces kept", () => {
  for (const [l, d] of Object.entries(I18N)) {
    for (const k of en.keys) {
      assert.ok(typeof d.strings[k] === "string" && d.strings[k].trim(), l + ": missing " + k);
      assert.strictEqual(d.strings[k].endsWith(" "), en.vals[k].endsWith(" "), l + ": trailing space " + k);
    }
    for (const k of Object.keys(d.strings)) assert.ok(k === "wmo" || en.keys.indexOf(k) >= 0, l + ": unknown " + k);
    assert.deepStrictEqual(Object.keys(d.strings.wmo).sort(), en.wmo.slice().sort(), l + ": wmo");
    assert.strictEqual(d.days.length, 7, l + ": days");
    assert.strictEqual(d.months.length, 12, l + ": months");
    assert.ok(d.name, l + ": name");
  }
});
test("same languages as the Android launcher", () => {
  const src = fs.readFileSync(__dirname + "/../../launcher/app/src/main/java/com/omarchytv/home/LauncherConfig.java", "utf8");
  const arr = src.match(/LANGUAGES = \{([^}]*)\}/)[1].match(/"([^"]+)"/g).map((q) => q.slice(1, -1));
  assert.deepStrictEqual(arr, L.LANGS);
});
test("language shown: saved, else the TV's, Brazil vs Portugal, else English", () => {
  const has = (l) => l === "en" || l === "it" || l === "de" || !!I18N[l];
  assert.strictEqual(L.resolve("fr", "it-IT", has), "fr");
  assert.strictEqual(L.resolve("", "es-ES", has), "es");
  assert.strictEqual(L.resolve("", "pt-BR", has), "pt-BR");
  assert.strictEqual(L.resolve("", "pt_BR", has), "pt-BR");
  assert.strictEqual(L.resolve("", "pt-PT", has), "pt-PT");
  assert.strictEqual(L.resolve("", "pt", has), "pt-PT");
  assert.strictEqual(L.resolve("", "ja-JP", has), "ja");
  assert.strictEqual(L.resolve("", "zh-TW", has), "zh-CN");
  assert.strictEqual(L.resolve("", "no-NO", has), "nb");
  assert.strictEqual(L.resolve("", "ko-KR", has), "en");
  assert.strictEqual(L.resolve("xx", "", has), "en");
});
test("menu cycle, speech and Grok Bot tags", () => {
  let cur = "", n = 0;
  do { cur = L.next(cur); n++; } while (cur && n < 50);
  assert.strictEqual(n, L.LANGS.length + 1);
  assert.strictEqual(L.speechTag("pt-BR"), "pt-BR");
  assert.strictEqual(L.speechTag("el"), "el-GR");
  assert.strictEqual(L.speechTag("xx"), "en-US");
  assert.strictEqual(L.askTag("pt-PT"), "pt");
  assert.strictEqual(L.askTag("ru"), "ru");
  assert.strictEqual(L.name("de", I18N), "Deutsch");
  assert.strictEqual(L.name("pt-BR", I18N), "Português (Brasil)");
});
test("index.html loads lang.js and i18n.js before app.js; app.js uses them", () => {
  const html = fs.readFileSync(__dirname + "/../app/index.html", "utf8");
  const app = html.indexOf('src="app.js"');
  assert.ok(html.indexOf('src="lang.js"') > 0 && html.indexOf('src="lang.js"') < app);
  assert.ok(html.indexOf('src="i18n.js"') > 0 && html.indexOf('src="i18n.js"') < app);
  assert.ok(APP.indexOf("window.OMARCHY_I18N") > 0 && APP.indexOf('cfg.lang === "it" ?') < 0);
});
