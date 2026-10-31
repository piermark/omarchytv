/* OmarchyTV (webOS / Tizen) · "Grok Bot diretto (senza PC)", v2.13.8. With no PC companion paired, the TV asks the
 * user's own Grok Bot webhook routine and reads the reply from https://ntfy.sh/<reply_topic> (a random topic made
 * once on this TV). Same rules as the Android launcher's GrokbotDirect; pure helpers tested by
 * webos/tests/grokdirect.test.js, plus ask() that does the network calls. URL and key live in their own
 * localStorage entry, never in the synced config, and are never logged. */
(function (root) {
  "use strict";
  var KEY = "omarchy_grokdirect", NTFY = "https://ntfy.sh/", POLL_MS = 2000, TIMEOUT_MS = 90000;
  var ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";

  function rand(n, rnd) {
    var s = "";
    for (var i = 0; i < n; i++) s += ALNUM.charAt(Math.floor((rnd ? rnd() : secure()) * ALNUM.length));
    return s;
  }
  function secure() {
    var c = root.crypto || (typeof globalThis !== "undefined" && globalThis.crypto);
    if (c && c.getRandomValues) { var a = new Uint32Array(1); c.getRandomValues(a); return a[0] / 4294967296; }
    return Math.random();
  }
  function newTopic(rnd) { return "otv-" + rand(32, rnd); }
  function validTopic(t) { return /^otv-[a-z0-9]{32}$/.test(t || ""); }
  function validUrl(u) { return typeof u === "string" && u.length <= 512 && /^https:\/\/[^\s\/?#]+(\/\S*)?$/.test(u); }
  function validKey(k) { return typeof k === "string" && /^[A-Za-z0-9_\-.]{8,256}$/.test(k); }
  function mask(k) {
    if (!k) return "not set";
    return (k.indexOf("crsr_") === 0 ? "crsr_" : "") + "••••••••" + (k.length > 12 ? k.slice(-4) : "");
  }
  function headers(key) {
    return { "Content-Type": "application/json", "Accept": "application/json, text/plain", "Authorization": "Bearer " + key, "X-Automation-Key": key };
  }
  function body(q, lang, topic, id, apps, context) {
    return JSON.stringify({ question: String(q || ""), lang: lang || "en", reply_topic: topic, id: id,
      apps: (apps || []).filter(Boolean).slice(0, 80).map(function (s) { return String(s).slice(0, 40); }), context: context || "" });
  }
  function pollUrl(topic, since) { return NTFY + topic + "/json?poll=1&since=" + since; }
  function clean(s, max) { return String(s == null ? "" : s).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "").trim().slice(0, max); }
  function isYoutube(url) {
    var m = /^(?:https?:\/\/)?([^\/?#:]+)/i.exec(String(url || "").trim());
    if (!m) return false;
    var h = m[1].toLowerCase();
    return ["googlevideo.com", "youtube.com", "youtu.be", "youtube-nocookie.com"].some(function (d) { return h === d || h.slice(-d.length - 1) === "." + d; });
  }
  function youtubeId(url) {
    var m = /^https?:\/\/(?:www\.|m\.|music\.)?(?:youtube\.com\/(?:watch\?(?:[^#]*&)?v=|shorts\/|live\/|embed\/)|youtu\.be\/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])/.exec(url || "");
    return m ? m[1] : "";
  }
  /** The validated action, or null (none / unknown / unsafe). The payload is untrusted. */
  function action(a) {
    if (!a || typeof a !== "object") return null;
    if (a.type === "play_video") {
      var u = String(a.url || "").trim();
      if (!/^https?:\/\/\S+$/.test(u) || u.length > 2000) return null;
      return { type: "play_video", url: u, title: clean(a.title, 120), youtube: isYoutube(u), videoId: youtubeId(u) };
    }
    if (a.type === "open_app") { var n = clean(a.name, 40); return n ? { type: "open_app", name: n } : null; }
    if (a.type === "search_youtube") { var q = clean(a.query, 120); return q ? { type: "search_youtube", query: q } : null; }
    return null;
  }
  function parse(message, id) {
    try {
      var m = JSON.parse(message);
      if (!m || m.id !== id) return null;
      return { text: clean(m.text, 2000), action: action(m.action) };
    } catch (e) { return null; }
  }
  /** The reply for id in an ntfy poll body (newline-delimited JSON), or null. */
  function find(ndjson, id) {
    var lines = String(ndjson || "").split("\n");
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i].trim();
      if (!l) continue;
      try {
        var ev = JSON.parse(l);
        if (ev.event !== "message") continue;
        var r = parse(ev.message || "", id);
        if (r) return r;
      } catch (e) {}
    }
    return null;
  }
  function youtubeSearchUrl(q) { return "https://www.youtube.com/results?search_query=" + encodeURIComponent(q).replace(/%20/g, "+"); }

  function load(store) {
    var s = {};
    try { s = JSON.parse(store.getItem(KEY) || "{}") || {}; } catch (e) { s = {}; }
    if (!validTopic(s.topic)) { s.topic = newTopic(); save(store, s); }
    if (s.speak === undefined) s.speak = true;
    return s;
  }
  function save(store, s) { try { store.setItem(KEY, JSON.stringify(s)); } catch (e) {} }
  function enabled(s) { return !!(s && s.enabled && validUrl(s.url) && validKey(s.key)); }

  /** Ask once; cb(err, reply). err is a user-facing message (never the key or the URL). */
  function ask(s, q, lang, apps, context, cb, deps) {
    deps = deps || {};
    var f = deps.fetch || root.fetch, now = deps.now || Date.now, wait = deps.setTimeout || setTimeout;
    if (!enabled(s)) { cb("Set the webhook URL and key in Settings › Grok Bot e AI › Grok Bot diretto."); return; }
    if (typeof f !== "function") { cb("This TV browser cannot make web requests."); return; }
    var id = rand(16), since = Math.floor(now() / 1000) - 5, end = now() + TIMEOUT_MS;
    f(s.url, { method: "POST", headers: headers(s.key), body: body(q, lang, s.topic, id, apps, context) }).then(function (r) {
      if (!r.ok) { cb(r.status === 401 || r.status === 403 ? "The webhook refused the key (HTTP " + r.status + "): check it in Settings." : "The webhook answered HTTP " + r.status + "."); return; }
      (function poll() {
        if (now() >= end) { cb("No answer from Grok Bot within 90 s. Check the routine and its ntfy.sh step."); return; }
        wait(function () {
          f(pollUrl(s.topic, since)).then(function (p) { return p.ok ? p.text() : ""; }).then(function (t) {
            var rep = find(t, id);
            if (rep) cb(null, rep); else poll();
          }).catch(poll);
        }, POLL_MS);
      })();
    }).catch(function () { cb("Could not reach the webhook."); });
  }

  var api = { KEY: KEY, POLL_MS: POLL_MS, TIMEOUT_MS: TIMEOUT_MS, newTopic: newTopic, validTopic: validTopic, validUrl: validUrl,
    validKey: validKey, mask: mask, headers: headers, body: body, pollUrl: pollUrl, isYoutube: isYoutube, action: action,
    parse: parse, find: find, youtubeSearchUrl: youtubeSearchUrl, load: load, save: save, enabled: enabled, ask: ask };
  root.OmarchyGrokDirect = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : this);
