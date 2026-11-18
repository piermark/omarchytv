// v2.21 omarchytv.js, the SDK the TV serves at /v1/sdk/omarchytv.js
const test = require("node:test");
const assert = require("assert");
const path = require("path");
const SDK = require(path.join(__dirname, "../../launcher/app/src/main/assets/sdk/omarchytv.js"));

function resp(status, body, headers) {
  return { ok: status >= 200 && status < 300, status, headers: { get: (k) => (headers || {})[k] }, json: () => Promise.resolve(body) };
}

test("themeVars maps the theme to CSS variables", () => {
  const v = SDK.themeVars({ background: "#1a1b26", foreground: "#a9b1d6", accent: "#7aa2f7", accent2: "#bb9af7", mode: "dark",
    colors: { color4: "#7aa2f7", hyprland_active_border: "rgba(33ccffee)", "bad key!": "#000000" } });
  assert.strictEqual(v["--otv-background"], "#1a1b26");
  assert.strictEqual(v["--otv-accent"], "#7aa2f7");
  assert.strictEqual(v["--otv-color4"], "#7aa2f7");
  assert.strictEqual(v["--otv-hyprland-active-border"], "rgba(33ccffee)");
  assert.ok(!Object.keys(v).some((k) => /[^a-z0-9-]/.test(k)));
});

test("connect fills the port, theme() uses the ETag and keeps the theme on 304", async () => {
  const calls = [];
  const t = { name: "tokyo-night", background: "#1a1b26", wallpaper: { url: "/v1/theme/wallpaper", sha256: "abcdef0123456789" } };
  const fetch = (u, o) => {
    calls.push([u, o]);
    return Promise.resolve(calls.length === 1 ? resp(200, t, { ETag: '"e1"' }) : resp(304, null));
  };
  const tv = SDK.connect({ tv: "192.168.1.50", fetch });
  assert.strictEqual(tv.base, "http://192.168.1.50:47800");
  assert.strictEqual((await tv.theme()).name, "tokyo-night");
  assert.strictEqual((await tv.theme()).name, "tokyo-night");
  assert.strictEqual(calls[1][1].headers["If-None-Match"], '"e1"');
  assert.ok(!calls[0][1].headers.Authorization, "the theme needs no token");
  assert.strictEqual(tv.wallpaperUrl(), "http://192.168.1.50:47800/v1/theme/wallpaper?v=abcdef012345");
});

test("state and notify send the app token; errors carry the code", async () => {
  const seen = [];
  const fetch = (u, o) => {
    seen.push([u, o]);
    if (u.endsWith("/v1/notify")) return Promise.resolve(resp(403, { error: "notify_off", message: "turn on agent alerts" }));
    return Promise.resolve(resp(200, { screen: "home" }));
  };
  const tv = SDK.connect({ tv: "http://10.0.0.2:47800", token: "otva_x", fetch });
  assert.strictEqual((await tv.state()).screen, "home");
  assert.strictEqual(seen[0][1].headers.Authorization, "Bearer otva_x");
  await assert.rejects(tv.notify("hi"), (e) => e.code === "notify_off" && e.status === 403);
  await assert.rejects(SDK.connect({ tv: "10.0.0.2", fetch }).state(), /app token/);
});

test("ask() posts the question and long-polls until the answer", async () => {
  let polls = 0;
  const fetch = (u, o) => {
    if (o.method === "POST") {
      assert.deepStrictEqual(JSON.parse(o.body), { question: "Deploy?", choices: ["Yes", "Not now"], timeout: 60 });
      return Promise.resolve(resp(200, { id: "0123456789abcdef", state: "pending" }));
    }
    assert.ok(u.endsWith("/v1/ask/0123456789abcdef?wait=25"));
    polls++;
    return Promise.resolve(resp(200, polls < 3 ? { state: "pending" } : { state: "answered", answer: "Not now" }));
  };
  const tv = SDK.connect({ tv: "10.0.0.2", token: "otva_x", fetch });
  assert.strictEqual(await tv.ask("Deploy?", ["Yes", "Not now"], { timeout: 60 }), "Not now");
  assert.strictEqual(polls, 3);
  const expired = SDK.connect({ tv: "10.0.0.2", token: "otva_x", fetch: (u, o) => Promise.resolve(o.method === "POST" ? resp(200, { id: "a" }) : resp(200, { state: "expired" })) });
  assert.strictEqual(await expired.ask("?"), null);
});

test("on() opens one stream with the token and dispatches events", () => {
  const made = [];
  function ES(url) { this.url = url; this.l = {}; made.push(this); }
  ES.prototype.addEventListener = function (n, f) { this.l[n] = f; };
  ES.prototype.close = function () { this.closed = true; };
  const tv = SDK.connect({ tv: "10.0.0.2", token: "otva_y", EventSource: ES, fetch: () => Promise.resolve(resp(200, {})) });
  const got = [];
  tv.on("state", (s) => got.push(s.screen)).on("notify", (n) => got.push(n.text));
  assert.strictEqual(made.length, 1);
  assert.strictEqual(made[0].url, "http://10.0.0.2:47800/v1/stream?token=otva_y");
  made[0].l.state({ data: '{"screen":"app"}' });
  made[0].l.notify({ data: '{"text":"done"}' });
  made[0].l.state({ data: "not json" });
  assert.deepStrictEqual(got, ["app", "done"]);
  tv.close();
  assert.ok(made[0].closed);
});

test("remote keys by code and by name", () => {
  assert.strictEqual(SDK.keyOf({ keyCode: 38 }), "up");
  assert.strictEqual(SDK.keyOf({ keyCode: 403 }), "red");
  assert.strictEqual(SDK.keyOf({ key: "ColorF3Blue", keyCode: 0 }), "blue");
  assert.strictEqual(SDK.keyOf({ key: "MediaPlayPause" }), "play_pause");
  assert.strictEqual(SDK.keyOf({ keyCode: 65 }), "");
});

test("spatial focus picks the nearest element that way", () => {
  // a 2x2 grid plus one far right on the top row
  const R = { a: [0, 0], b: [200, 0], c: [0, 100], d: [200, 100], e: [800, 0] };
  const rect = (el) => ({ left: R[el][0], top: R[el][1], width: 100, height: 50 });
  const all = Object.keys(R);
  assert.strictEqual(SDK.nearest("a", "right", all, rect), "b");
  assert.strictEqual(SDK.nearest("a", "down", all, rect), "c");
  assert.strictEqual(SDK.nearest("d", "up", all, rect), "b");
  assert.strictEqual(SDK.nearest("b", "right", all, rect), "e");
  assert.strictEqual(SDK.nearest("a", "left", all, rect), null);
});

test("badge() posts the count with the token", async () => {
  let seen;
  const tv = SDK.connect({ tv: "10.0.0.2", token: "otva_z", fetch: (u, o) => { seen = [u, o]; return Promise.resolve(resp(200, { ok: true })); } });
  await tv.badge("dubai-flats", 4);
  assert.strictEqual(seen[0], "http://10.0.0.2:47800/v1/pages/dubai-flats/badge");
  assert.deepStrictEqual(JSON.parse(seen[1].body), { badge: 4 });
});
