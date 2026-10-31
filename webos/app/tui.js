// "Grok Bot · Omarchy TUI" for LG webOS / Samsung Tizen: a port of the Android AskActivity commands.
// Pure parsing only (no DOM, no network) so node can test it: webos/tests/tui.test.js.
// parse(text, themes) -> { kind, ... }:
//   clear | help | themes | next-theme | theme {name} | theme-unknown {query} | wallpaper | saver {style?}
//   | speed {speed} | agents | agent {query} | status | apps | web {target} | open {target} | ask {text} | empty
(function (root) {
  "use strict";
  var SAVER_STYLES = { aquarium: "aquarium", acquario: "aquarium", classic: "classic", classico: "classic",
    logo: "logo", omarchy: "logo", cinemarchy: "cinemarchy" };
  var WEB = { "web": "grok", "grok.com": "grok", "grok web": "grok", "webapp": "grok", "grok": "grok",
    "chatgpt": "chatgpt", "open chatgpt": "chatgpt", "apri chatgpt": "chatgpt",
    "claude": "claude", "open claude": "claude", "apri claude": "claude", "claude.ai": "claude",
    "hermes": "hermes", "open hermes": "hermes", "apri hermes": "hermes",
    "x": "xgrok", "x grok": "xgrok", "grok on x": "xgrok" };
  var WEB_URLS = { grok: "https://grok.com/", xgrok: "https://x.com/i/grok", chatgpt: "https://chatgpt.com/",
    claude: "https://claude.ai/", hermes: "https://hermes.nousresearch.com" };
  var SPEEDS = { slow: "slow", lento: "slow", normal: "normal", normale: "normal", fast: "fast", veloce: "fast" };
  function one(low, list) { return list.indexOf(low) >= 0; }
  function norm(s) { return String(s || "").toLowerCase().replace(/[^a-z0-9]/g, ""); }

  // Theme by name: exact, then normalised ("tokyo roadster" = "tokyo-roadster"), then a unique prefix/substring.
  function matchTheme(q, themes) {
    var n = norm(q), i, hits = [];
    if (!n) return null;
    for (i = 0; i < themes.length; i++) if (themes[i] === q || norm(themes[i]) === n) return themes[i];
    for (i = 0; i < themes.length; i++) if (norm(themes[i]).indexOf(n) === 0) hits.push(themes[i]);
    if (hits.length === 1) return hits[0];
    if (!hits.length) for (i = 0; i < themes.length; i++) if (norm(themes[i]).indexOf(n) >= 0) hits.push(themes[i]);
    return hits.length === 1 ? hits[0] : null;
  }

  function parse(text, themes) {
    themes = themes || [];
    var raw = String(text || "").replace(/\s+/g, " ").trim();
    var low = raw.toLowerCase().replace(/^[>$]\s*/, "");
    if (!low) return { kind: "empty" };
    if (one(low, ["clear", "cls", "pulisci"])) return { kind: "clear" };
    if (one(low, ["help", "aiuto", "?", "comandi", "commands"])) return { kind: "help" };
    if (one(low, ["themes", "temi", "list themes", "elenco temi"])) return { kind: "themes" };
    if (one(low, ["next theme", "tema successivo", "cambia tema", "switch theme"])) return { kind: "next-theme" };
    if (one(low, ["wallpaper", "next wallpaper", "sfondo", "cambia sfondo", "sfondo successivo"])) return { kind: "wallpaper" };
    if (one(low, ["screensaver", "salvaschermo", "saver"])) return { kind: "saver" };
    var m = /^(speed|velocita|velocità) (.+)$/.exec(low);
    if (m) return { kind: "speed", speed: /slow|lent/.test(m[2]) ? "slow" : (/fast|veloc/.test(m[2]) ? "fast" : "normal") };
    m = /^(screensaver|salvaschermo|saver) (.+)$/.exec(low);
    if (m && SPEEDS[m[2]]) return { kind: "saver", speed: SPEEDS[m[2]] };
    if (m) return SAVER_STYLES[m[2]] ? { kind: "saver", style: SAVER_STYLES[m[2]] } : { kind: "ask", text: raw };
    if (one(low, ["agents", "agenti", "agent", "agente", "list agents"])) return { kind: "agents" };
    m = /^(agent|agente|use|usa) (?!tema |theme )(.+)$/.exec(low);
    if (m) return { kind: "agent", query: m[2].trim() };
    if (one(low, ["status", "stato", "info", "mcp", "fastfetch", "neofetch", "ip"])) return { kind: "status" };
    if (one(low, ["apps", "elenco app", "list apps", "app"])) return { kind: "apps" };
    if (WEB[low]) return { kind: "web", target: WEB[low], url: WEB_URLS[WEB[low]] };
    m = /^(open|launch|apri|avvia) (.+)$/.exec(low);
    if (m) {
      var t = m[2].trim();
      if (WEB[t]) return { kind: "web", target: WEB[t], url: WEB_URLS[WEB[t]] };
      return { kind: "open", target: raw.slice(raw.length - t.length).trim() };
    }
    m = /^(set theme|theme|imposta tema|usa tema|tema) (.+)$/.exec(low);
    if (m) {
      var th = matchTheme(m[2], themes);
      return th ? { kind: "theme", name: th } : { kind: "theme-unknown", query: m[2] };
    }
    var direct = matchTheme(low, themes);   // a bare theme name switches to it, like on Android
    if (direct && norm(low).length >= 4 && norm(direct) === norm(low)) return { kind: "theme", name: direct };
    return { kind: "ask", text: raw };
  }

  var HELP = [
    "themes · theme <name> · next theme",
    "wallpaper · screensaver [aquarium|classic|logo|cinemarchy|slow|normal|fast] · speed slow|normal|fast",
    "agents · agent <name>  (Grok Bot, Claude, Codex, Grok, Hermes, Gemini, ollama… from your PC)",
    "apps · open <app> · status · clear",
    "web · chatgpt · claude · hermes  (open in the TV browser)",
    "anything else goes to the agent (PC bridge or Grok Bot diretto)"
  ];

  // Typewriter: how many characters to show after `ms` (about 60 chars/s, whole text after at most 4 s).
  function typed(text, ms) {
    var n = String(text || "").length;
    return Math.min(n, Math.max(Math.floor(ms * 0.06), Math.ceil(n * ms / 4000)));
  }

  var api = { parse: parse, matchTheme: matchTheme, typed: typed, HELP: HELP, WEB_URLS: WEB_URLS };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.OmarchyTui = api;
})(typeof window !== "undefined" ? window : this);
