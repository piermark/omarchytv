/*
 * omarchytv.js — the OmarchyTV SDK for dashboards and agent pages (v2.21, MIT).
 *
 *   <script src="http://<tv>:47800/v1/sdk/omarchytv.js"></script>
 *   <script>
 *     var tv = OmarchyTV.connect({ token: "otva_…" });   // the TV is where this script came from
 *     tv.followTheme();                                     // CSS variables --otv-background, --otv-accent, …
 *     tv.on("state", function (s) { console.log(s.screen, s.app); });
 *     tv.ask("Deploy to production?", ["Yes", "Not now"]).then(function (a) { … });
 *     OmarchyTV.remote({ red: function () { refresh(); } });   // arrows move the focus, OK clicks
 *   </script>
 *
 * The theme needs no token. state / on() need a "read" token, notify() and ask() a "notify" token
 * (omarchy-link token add NAME --scope read|notify|control on the paired PC). ES5 syntax, no dependencies; it needs
 * fetch and Promise (any Android TV WebView, Chrome 42+). docs/INTEGRATIONS.md.
 */
(function (root) {
  "use strict";

  function scriptOrigin() {
    try {
      var s = root.document && (root.document.currentScript || (function () {
        var all = root.document.getElementsByTagName("script");
        for (var i = all.length - 1; i >= 0; i--) if (/\/v1\/sdk\/omarchytv\.js(\?|$)/.test(all[i].src)) return all[i];
        return null;
      })());
      var m = s && /^(https?:\/\/[^\/]+)\/v1\/sdk\//.exec(s.src);
      return m ? m[1] : "";
    } catch (e) {
      return "";
    }
  }

  var ORIGIN = scriptOrigin();

  function cssName(key) {
    return "--otv-" + String(key).replace(/_/g, "-").replace(/[^a-z0-9-]/gi, "");
  }

  /** CSS variables for a theme payload (schema omarchytv-theme/1). Pure: used by followTheme() and the tests. */
  function themeVars(t) {
    var out = {};
    if (!t) return out;
    var colors = t.colors || {};
    for (var k in colors) if (Object.prototype.hasOwnProperty.call(colors, k)) out[cssName(k)] = colors[k];
    out["--otv-background"] = t.background;
    out["--otv-foreground"] = t.foreground;
    out["--otv-accent"] = t.accent;
    out["--otv-accent2"] = t.accent2;
    out["--otv-mode"] = t.mode;
    return out;
  }

  function Client(opts) {
    opts = opts || {};
    this.base = String(opts.tv || ORIGIN || "").replace(/\/+$/, "");
    if (this.base && !/^https?:\/\//.test(this.base)) this.base = "http://" + this.base;
    if (this.base && !/:\d+$/.test(this.base)) this.base += ":47800";
    this.token = opts.token || "";
    this._fetch = opts.fetch || (root.fetch ? root.fetch.bind(root) : null);
    this._ES = opts.EventSource || root.EventSource || null;
    this._wait = opts.setTimeout || root.setTimeout;
    this._listeners = {};
    this._es = null;
    this._etag = "";
    this._theme = null;
  }

  Client.prototype._req = function (method, path, body, auth) {
    var self = this;
    if (!self._fetch) return Promise.reject(new Error("this browser cannot make web requests"));
    var h = {};
    if (auth) {
      if (!self.token) return Promise.reject(new Error("an app token is needed (omarchy-link token add)"));
      h.Authorization = "Bearer " + self.token;
    }
    if (body !== undefined) h["Content-Type"] = "application/json";
    return self._fetch(self.base + path, { method: method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) {
          if (!r.ok) {
            var e = new Error(j.message || j.error || ("HTTP " + r.status));
            e.status = r.status;
            e.code = j.error || "";
            throw e;
          }
          return j;
        });
      });
  };

  /** The TV's theme (no token). Resolves to the cached one when the TV says 304. */
  Client.prototype.theme = function () {
    var self = this;
    if (!self._fetch) return Promise.reject(new Error("this browser cannot make web requests"));
    var h = self._etag ? { "If-None-Match": self._etag } : {};
    return self._fetch(self.base + "/v1/theme", { headers: h }).then(function (r) {
      if (r.status === 304 && self._theme) return self._theme;
      if (!r.ok) throw new Error("theme: HTTP " + r.status);
      self._etag = (r.headers && r.headers.get && r.headers.get("ETag")) || "";
      return r.json().then(function (t) { self._theme = t; return t; });
    });
  };

  /** The wallpaper's URL, or "" for a procedural one. */
  Client.prototype.wallpaperUrl = function (t) {
    t = t || this._theme;
    return t && t.wallpaper ? this.base + t.wallpaper.url + "?v=" + String(t.wallpaper.sha256 || "").slice(0, 12) : "";
  };

  /** Apply the theme as CSS variables on an element (default <html>); returns the variables. */
  Client.prototype.applyTheme = function (t, el) {
    var vars = themeVars(t);
    el = el || (root.document && root.document.documentElement);
    if (el && el.style && el.style.setProperty) for (var k in vars) if (vars[k]) el.style.setProperty(k, vars[k]);
    if (el && el.style && el.style.setProperty) el.style.setProperty("--otv-wallpaper", this.wallpaperUrl(t) ? "url(\"" + this.wallpaperUrl(t) + "\")" : "none");
    return vars;
  };

  /** Keep the page in the TV's colours: now, then on every change (the stream with a token, else every 15 s). */
  Client.prototype.followTheme = function (el, cb) {
    var self = this;
    function apply(t) { self.applyTheme(t, el); if (cb) cb(t); }
    self.theme().then(apply, function () { /* the TV is away: try again below */ });
    if (self.token && self._ES) {
      self.on("theme", function (t) { self._theme = t; apply(t); });
    } else {
      var tick = function () { self.theme().then(apply, function () {}); self._timer = self._wait(tick, 15000); };
      self._timer = self._wait(tick, 15000);
    }
    return self;
  };

  /** What is on screen: {screen, app, theme, nowPlaying, time}. Needs a "read" token. */
  Client.prototype.state = function () {
    return this._req("GET", "/v1/state", undefined, true);
  };

  /** Events: "state", "theme", "notify", "ask_answer". One stream for every listener; it reconnects by itself. */
  Client.prototype.on = function (type, cb) {
    var self = this;
    (self._listeners[type] = self._listeners[type] || []).push(cb);
    if (!self._es && self._ES && self.token) {
      self._es = new self._ES(self.base + "/v1/stream?token=" + encodeURIComponent(self.token));
      ["state", "theme", "notify", "ask_answer"].forEach(function (name) {
        self._es.addEventListener(name, function (ev) {
          var data;
          try { data = JSON.parse(ev.data); } catch (e) { return; }
          var ls = self._listeners[name] || [];
          for (var i = 0; i < ls.length; i++) try { ls[i](data); } catch (e) { /* a listener's own error */ }
        });
      });
    }
    return self;
  };

  /** One line on the TV over whatever is playing. Needs a "notify" token and agent alerts on the TV. */
  Client.prototype.notify = function (text) {
    return this._req("POST", "/v1/notify", { text: String(text) }, true);
  };

  /**
   * Ask the person at the TV; resolves to the picked answer, or null when the question expired.
   * choices: up to 4 short strings (default Yes / No). opts.timeout: seconds on screen (10-300, default 120).
   */
  Client.prototype.ask = function (question, choices, opts) {
    var self = this;
    opts = opts || {};
    return self._req("POST", "/v1/ask", { question: String(question), choices: choices || [], timeout: opts.timeout || 120 }, true)
      .then(function (a) {
        function poll() {
          return self._req("GET", "/v1/ask/" + a.id + "?wait=25", undefined, true).then(function (s) {
            if (s.state === "answered") return s.answer;
            if (s.state === "expired") return null;
            return poll();
          });
        }
        return poll();
      });
  };

  /** Stop the stream and the theme timer. */
  Client.prototype.close = function () {
    if (this._es) { this._es.close(); this._es = null; }
    if (this._timer && root.clearTimeout) root.clearTimeout(this._timer);
    this._listeners = {};
  };

  /** Set the badge (a count on the page's Home tile; 0 hides it). Needs a "notify" token. */
  Client.prototype.badge = function (pageId, n) {
    return this._req("POST", "/v1/pages/" + encodeURIComponent(pageId) + "/badge", { badge: n | 0 }, true);
  };

  // ---- the remote: keys and focus ---------------------------------------------------------------------------

  var KEYS = {
    37: "left", 38: "up", 39: "right", 40: "down", 13: "ok", 23: "ok",
    403: "red", 404: "green", 405: "yellow", 406: "blue",   // webOS / Tizen / HbbTV colour keys
    415: "play", 19: "pause", 179: "play_pause", 10252: "play_pause", 417: "next", 412: "previous"
  };
  var KEY_NAMES = {
    ArrowLeft: "left", ArrowUp: "up", ArrowRight: "right", ArrowDown: "down", Enter: "ok",
    ColorF0Red: "red", ColorF1Green: "green", ColorF2Yellow: "yellow", ColorF3Blue: "blue",
    MediaPlayPause: "play_pause", MediaPlay: "play", MediaPause: "pause", MediaTrackNext: "next", MediaTrackPrevious: "previous"
  };

  /** The remote key of a keyboard event: "up", "ok", "red", "play_pause" …, or "". */
  function keyOf(ev) {
    if (!ev) return "";
    return KEY_NAMES[ev.key] || KEYS[ev.keyCode] || "";
  }

  /**
   * The next element from `from` in direction `dir` among `items` (each with a rect {left, top, width, height}):
   * the nearest one whose centre lies that way, with a penalty for being off-axis. Pure, for tests.
   */
  function nearest(from, dir, items, rectOf) {
    var a = rectOf(from), ax = a.left + a.width / 2, ay = a.top + a.height / 2, best = null, bestD = Infinity;
    for (var i = 0; i < items.length; i++) {
      var el = items[i];
      if (el === from) continue;
      var b = rectOf(el), bx = b.left + b.width / 2, by = b.top + b.height / 2, dx = bx - ax, dy = by - ay, main, off;
      if (dir === "left") { if (dx >= -1) continue; main = -dx; off = Math.abs(dy); }
      else if (dir === "right") { if (dx <= 1) continue; main = dx; off = Math.abs(dy); }
      else if (dir === "up") { if (dy >= -1) continue; main = -dy; off = Math.abs(dx); }
      else { if (dy <= 1) continue; main = dy; off = Math.abs(dx); }
      var d = main + off * 2;
      if (d < bestD) { bestD = d; best = el; }
    }
    return best;
  }

  /**
   * Remote control for the page: arrows move the focus between `selector` elements (default: links, buttons and
   * [data-otv-focus]), OK clicks, and `handlers` get every key by name (return false to stop the default).
   * The focused element gets the class "otv-focus" and an outline in the theme's accent.
   */
  function remote(handlers, selector, doc) {
    doc = doc || root.document;
    handlers = handlers || {};
    selector = selector || "a[href], button, [data-otv-focus], input, select, textarea";
    if (!doc) return null;
    if (!doc.getElementById("otv-focus-style") && doc.createElement) {
      var st = doc.createElement("style");
      st.id = "otv-focus-style";
      st.textContent = ".otv-focus{outline:3px solid var(--otv-accent,#7aa2f7)!important;outline-offset:3px}";
      (doc.head || doc.documentElement).appendChild(st);
    }
    var cur = null;
    function items() { return Array.prototype.slice.call(doc.querySelectorAll(selector)); }
    function rect(el) { return el.getBoundingClientRect(); }
    function focus(el) {
      if (!el) return;
      if (cur && cur.classList) cur.classList.remove("otv-focus");
      cur = el;
      if (el.classList) el.classList.add("otv-focus");
      if (el.focus) try { el.focus(); } catch (e) { /* not focusable */ }
      if (el.scrollIntoView) try { el.scrollIntoView({ block: "nearest", inline: "nearest" }); } catch (e) { el.scrollIntoView(false); }
    }
    function onKey(ev) {
      var k = keyOf(ev);
      if (!k) return;
      if (handlers[k] && handlers[k](ev, cur) === false) { ev.preventDefault(); return; }
      if (handlers.any && handlers.any(k, ev, cur) === false) { ev.preventDefault(); return; }
      var list = items();
      if (!list.length) return;
      if (!cur || list.indexOf(cur) < 0) { focus(list[0]); ev.preventDefault(); return; }
      if (k === "ok") { if (cur.click) cur.click(); ev.preventDefault(); return; }
      if (k === "up" || k === "down" || k === "left" || k === "right") {
        var n = nearest(cur, k, list, rect);
        if (n) { focus(n); ev.preventDefault(); }
      }
    }
    doc.addEventListener("keydown", onKey, true);
    var first = items()[0];
    if (first) focus(first);
    return { focus: focus, current: function () { return cur; }, stop: function () { doc.removeEventListener("keydown", onKey, true); } };
  }

  var OmarchyTV = {
    version: "2.21.0",
    connect: function (opts) { return new Client(opts); },
    themeVars: themeVars,
    remote: remote,
    keyOf: keyOf,
    nearest: nearest,
    Client: Client
  };

  if (typeof module !== "undefined" && module.exports) module.exports = OmarchyTV;
  else root.OmarchyTV = OmarchyTV;
})(typeof window !== "undefined" ? window : this);
