// node --test webos/tests/tui.test.js — Grok Bot · Omarchy TUI command parsing (app/tui.js).
const test = require("node:test");
const assert = require("assert");
const T = require("../app/tui.js");
const THEMES = ["tokyo-night", "tokyo-roadster", "catppuccin", "catppuccin-latte", "last-horizon", "cumulus"];

test("local commands, English and Italian", () => {
  assert.strictEqual(T.parse("help").kind, "help");
  assert.strictEqual(T.parse("Aiuto").kind, "help");
  assert.strictEqual(T.parse("temi").kind, "themes");
  assert.strictEqual(T.parse("cambia tema").kind, "next-theme");
  assert.strictEqual(T.parse("sfondo").kind, "wallpaper");
  assert.strictEqual(T.parse("> status").kind, "status");
  assert.strictEqual(T.parse("apps").kind, "apps");
  assert.strictEqual(T.parse("pulisci").kind, "clear");
  assert.strictEqual(T.parse("   ").kind, "empty");
});

test("screensaver with styles", () => {
  assert.deepStrictEqual(T.parse("screensaver"), { kind: "saver" });
  assert.deepStrictEqual(T.parse("salvaschermo acquario"), { kind: "saver", style: "aquarium" });
  assert.deepStrictEqual(T.parse("saver cinemarchy"), { kind: "saver", style: "cinemarchy" });
  assert.strictEqual(T.parse("screensaver please explain").kind, "ask");
});

test("themes by name", () => {
  assert.deepStrictEqual(T.parse("theme tokyo roadster", THEMES), { kind: "theme", name: "tokyo-roadster" });
  assert.deepStrictEqual(T.parse("tema cumulus", THEMES), { kind: "theme", name: "cumulus" });
  assert.deepStrictEqual(T.parse("theme catppuccin", THEMES), { kind: "theme", name: "catppuccin" });
  assert.strictEqual(T.parse("theme tokyo", THEMES).kind, "theme-unknown", "ambiguous");
  assert.deepStrictEqual(T.parse("Last Horizon", THEMES), { kind: "theme", name: "last-horizon" });
});

test("open apps and web apps", () => {
  assert.deepStrictEqual(T.parse("open MUBI"), { kind: "open", target: "MUBI" });
  assert.deepStrictEqual(T.parse("apri Prime Video"), { kind: "open", target: "Prime Video" });
  assert.strictEqual(T.parse("grok.com").url, "https://grok.com/");
  assert.strictEqual(T.parse("apri claude").target, "claude");
  assert.strictEqual(T.parse("open chatgpt").url, "https://chatgpt.com/");
});

test("everything else is a question for the agent", () => {
  assert.deepStrictEqual(T.parse("What should I watch tonight?", THEMES), { kind: "ask", text: "What should I watch tonight?" });
});

test("typewriter progress", () => {
  assert.strictEqual(T.typed("hello", 0), 0);
  assert.strictEqual(T.typed("hello", 10000), 5);
  assert.ok(T.typed("x".repeat(1000), 2000) >= 500);
});
