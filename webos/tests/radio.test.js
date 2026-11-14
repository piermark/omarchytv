// node --test webos/tests/radio.test.js — Radiomarchy on LG/Samsung (webos/app/radio.js): the same rules as Android.
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const R = (() => { const ctx = {}; require("vm").runInNewContext(fs.readFileSync(path.join(__dirname, "../app/radio.js"), "utf8"), Object.assign(ctx, { window: ctx })); return ctx.OmarchyRadio; })();
const FIX = path.join(__dirname, "../../launcher/app/src/test/resources/radio/");

test("the real playlist: every song, its maker, its https address and page; explicit shown unless hidden", () => {
  const s = R.parseSongs(fs.readFileSync(FIX + "playlist.json", "utf8"));
  assert.strictEqual(s.length, 33);
  assert.strictEqual(s[0].title, "Omarchy Oligarchy (Synthwave Mix)");
  assert.strictEqual(s[0].artist, "YZL81");
  assert.strictEqual(s[0].url, "https://radio.omarchy.org/tracks/yzl81-omarchy-oligarchy-synthwave-mix.mp3");
  assert.strictEqual(s[0].lyrics, "https://radio.omarchy.org/tracks/lyrics/yzl81-omarchy-oligarchy-synthwave-mix.lrc");
  assert.strictEqual(s[0].page, "https://radio.omarchy.org/playlist/omarchy-oligarchy-synthwave-mix");
  assert.strictEqual(s.filter((t) => t.explicit).length, 1);
  assert.strictEqual(R.visible(s, true).length, 33);
  assert.strictEqual(R.visible(s, false).length, 32);
});

test("unsafe entries are skipped; clashing titles get the maker, as on the site", () => {
  const s = R.parseSongs(JSON.stringify({ tracks: [
    { title: "A b", artist: "X", file: "x a.mp3", lyrics: false }, { title: "Plain", artist: "Z", url: "http://insecure/z.mp3" },
    { title: "Up", artist: "Z", file: "../etc/passwd" }, { artist: "none", file: "n.mp3" }, { title: "A b", artist: "Q", file: "q.mp3", explicit: true }] }));
  assert.strictEqual(s.length, 2);
  assert.strictEqual(s[0].url, "https://radio.omarchy.org/tracks/x%20a.mp3");
  assert.strictEqual(s[0].lyrics, "");
  assert.strictEqual(s[1].page, "https://radio.omarchy.org/playlist/a-b-q");
  assert.strictEqual(R.parseSongs("nope").length, 0);
  assert.strictEqual(R.slug("Omarchée C'est La Vie"), "omarchee-cest-la-vie");
  assert.strictEqual(R.slug("It’s Fucking Fork O'Clock"), "its-fucking-fork-oclock");
});

test("Omarchy Stories: episodes with length, makers and text without HTML", () => {
  const e = R.parseFeed(fs.readFileSync(FIX + "feed.rss", "utf8"));
  assert.strictEqual(e.length, 2);
  assert.ok(e[0].title.startsWith("009 Joel Gaff"));
  assert.strictEqual(e[0].artist, "Omarchy Stories · Basti");
  assert.strictEqual(e[0].dur, 47 * 60 + 47);
  assert.ok(e[0].url.startsWith("https://api.riverside.com/"));
  assert.ok(!/<p>/.test(e[0].about) && e[0].about.length > 0);
  const odd = R.parseFeed('<rss><channel><itunes:image href="https://i/s.png"/><item><title><![CDATA[Tom &amp; Jerry &#8212; live]]></title><enclosure url="https://h/a.mp3?x=1&amp;y=2"/><itunes:duration>3725</itunes:duration></item><item><title>http</title><enclosure url="http://h/b.mp3"/></item></channel></rss>');
  assert.strictEqual(odd.length, 1);
  assert.strictEqual(odd[0].title, "Tom & Jerry — live");
  assert.strictEqual(odd[0].url, "https://h/a.mp3?x=1&y=2");
  assert.strictEqual(odd[0].dur, 3725);
  assert.strictEqual(odd[0].image, "https://i/s.png");
});

test("lyric sheets, timed and plain", () => {
  const t = R.parseLyrics("[ar:x]\n[00:12.50]first\n[00:05.00][01:00.00]chorus\n[00:20]third\n");
  assert.strictEqual(JSON.stringify(t.map((l) => l.ms)), "[5000,12500,20000,60000]");
  assert.strictEqual(R.lineAt(t, 1000), -1);
  assert.strictEqual(R.lineAt(t, 19999), 1);
  const p = R.parseLyrics("\nI was stuck\r\n\nWe can fix everything\n\n");
  assert.strictEqual(p.length, 3);
  assert.strictEqual(R.lineAt(p, 5000), -1);
});

test("shuffle keeps the chosen song first and every song once", () => {
  for (let n = 1; n < 40; n += 7) {
    const o = R.shuffled(n, n - 1);
    assert.strictEqual(o[0], n - 1);
    assert.strictEqual(JSON.stringify(o.slice().sort((a, b) => a - b)), JSON.stringify(Array.from({ length: n }, (_, i) => i)));
  }
});

test("bands: a kick in the bass is a beat, hiss is not", () => {
  const b = R.Bands(), out = {}, n = 1024, sr = 44100, hz = sr / 2 / n;
  const frame = (kick) => { const f = new Uint8Array(n); for (let i = 0; i < n; i++) { const fq = i * hz; f[i] = kick && fq < 120 ? 250 : fq > 3000 && fq < 3100 ? 90 : 10; } return f; };
  let now = 0;
  for (let i = 0; i < 200; i++) { now += 16; b(frame(i % 30 < 3), sr, now, 0.016, out); }
  assert.ok(out.beats >= 5 && out.beats <= 8, "beats " + out.beats);
  const h = R.Bands(), o2 = {};
  now = 0;
  for (let i = 0; i < 200; i++) { now += 16; h(frame(false), sr, now, 0.016, o2); }
  assert.strictEqual(o2.beats, 0);
});

test("every language Radiomarchy speaks has every string", () => {
  const keys = Object.keys(R.T.en);
  for (const l of Object.keys(R.T)) for (const k of keys) assert.ok(R.T[l][k], l + " " + k);
});
