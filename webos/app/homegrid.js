/*
 * OmarchyTV — v2.22 (#157 / #158 / #159) Home widget pages on LG webOS and Samsung Tizen.
 *
 * The PC bridge (omarchy-tv webos-serve) keeps the widgets and the pages that agents build (home_grid.py, the same
 * API as an Android TV). This app reads them with its ask code (GET /v1/home) and shows them full screen, one page
 * at a time, over the classic Home (which stays "page 0", ⌂):
 *   - CH+ / CH- (and Page Up / Down) go ⌂ → 1 → 2 … → ⌂; BACK returns to the Home;
 *   - the navigator at the bottom: ⌂ ‹ 1 2 3 4 5 › 8 / 42 · name;
 *   - an agent's tv_page_show brings its page up; pages marked "rotate" turn every N seconds (a setting on the TV);
 *   - arrows move between the buttons of the page (scenes, switches, shortcuts, the radio, a page to open), OK runs.
 * Home Assistant widgets talk to Home Assistant from the TV with the URL and token of Ambilight & Lights.
 * Everything is text (textContent), never HTML from the bridge. ES5 for Chrome 38 (webOS 3). No editing on the TV:
 * agents and the PC place the widgets.
 */
(function (root) {
  "use strict";

  var BACK = { 461: 1, 10009: 1, 27: 1, 8: 1 };
  var CH_UP = { 427: 1, 33: 1 }, CH_DOWN = { 428: 1, 34: 1 };
  var COLS = 12, ROWS = 6, WINDOW = 5;
  var SKILL = "https://github.com/piermark/omarchytv/blob/main/skills/README.md";

  /** http:// to a literal private or tailnet IPv4 address. */
  function lanUrl(u) {
    var m = /^http:\/\/(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:\d{1,5})?(\/[^\s]*)?$/.exec(String(u || ""));
    if (!m) return false;
    var a = +m[1], b = +m[2];
    return a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31) || (a === 100 && b >= 64 && b <= 127);
  }

  function webUrl(u) {
    u = String(u || "");
    return (u.indexOf("https://") === 0 && u.length > 8 && !/\s/.test(u)) || lanUrl(u);
  }

  /** The navigator's window of page numbers, as Android's HomePages.window: [first, last, prev, next]. */
  function windowOf(current, total, size) {
    if (total <= 0) return [0, 0, 0, 0];
    var cur = Math.max(1, Math.min(total, current));
    var first = Math.floor((cur - 1) / size) * size + 1, last = Math.min(total, first + size - 1);
    if (total <= size) return [1, total, 0, 0];
    return [first, last, first > 1 ? 1 : 0, last < total ? 1 : 0];
  }

  /** What OK does to a Home Assistant entity: [domain, service], or null when it only shows a state. */
  function haService(entity) {
    var d = String(entity).split(".")[0];
    if (d === "scene" || d === "script") return [d, "turn_on"];
    if (d === "automation") return ["automation", "trigger"];
    if (d === "light" || d === "switch" || d === "fan" || d === "input_boolean") return [d, "toggle"];
    return null;
  }

  /** The next page for CH+ (dir 1) / CH- (dir -1) through [0 (the Home), visible pages…], wrapping. */
  function stepPage(visible, current, dir) {
    var list = [0].concat(visible || []), i = list.indexOf(current);
    if (i < 0) i = 0;
    return list[(i + dir + list.length) % list.length];
  }

  function create(opts) {
    var doc = opts.doc || root.document, fetchFn = opts.fetch || (root.fetch ? root.fetch.bind(root) : null);
    var view = null, page = 0, focus = 0, items = [], layer = null, frame = null, busy = false;
    var seenShow = null, rotateAt = 0, haCache = { at: 0, states: null }, flash = {}, armed = null;

    function t(k, d) { var v = opts.tr && opts.tr(k); return v && v !== k ? v : d; }

    function el(tag, style, text) {
      var e = doc.createElement(tag);
      if (style) e.setAttribute("style", style);
      if (text != null) e.textContent = String(text);
      return e;
    }

    function hasPages() { return !!(view && view.widgets && view.widgets.length); }

    function pageMeta(i) {
      var ps = (view && view.pages) || [];
      for (var k = 0; k < ps.length; k++) if (ps[k].index === i) return ps[k];
      return null;
    }

    function pageName(i) { var m = pageMeta(i); return m ? m.name : "Page " + i; }

    // ---- Home Assistant from the TV -------------------------------------------------------------------------
    function ha() { var h = opts.ha && opts.ha(); return h && h.url && h.token ? h : null; }

    function haFetch(path, method, body) {
      var h = ha();
      if (!h || !fetchFn) return Promise.reject(new Error("ha_not_set"));
      var o = { method: method || "GET", headers: { "Authorization": "Bearer " + h.token, "Content-Type": "application/json" } };
      if (body) o.body = JSON.stringify(body);
      return fetchFn(h.url.replace(/\/+$/, "") + path, o).then(function (r) {
        if (!r.ok) throw new Error("ha_" + r.status);
        return r.json();
      });
    }

    function haStates(force) {
      var now = Date.now();
      if (!force && haCache.states && now - haCache.at < 30000) return Promise.resolve(haCache.states);
      return haFetch("/api/states").then(function (s) {
        haCache = { at: Date.now(), states: s && s.length !== undefined ? s : [] };
        return haCache.states;
      });
    }

    function haRun(entity) {
      var sv = haService(entity);
      if (!sv) return Promise.resolve(false);
      return haFetch("/api/services/" + sv[0] + "/" + sv[1], "POST", { entity_id: entity }).then(function () {
        haCache.at = 0;
        return true;
      });
    }

    // ---- the layer -------------------------------------------------------------------------------------------
    function ensureLayer() {
      if (layer) return layer;
      layer = el("div", "position:fixed;left:0;top:0;right:0;bottom:0;z-index:99960;background:var(--bg,#1a1b26);color:var(--fg,#a9b1d6);font-family:inherit");
      layer.id = "otv-wgrid";
      doc.body.appendChild(layer);
      return layer;
    }

    function close() {
      if (layer && layer.parentNode) layer.parentNode.removeChild(layer);
      layer = null; frame = null; items = []; armed = null;
      if (page !== 0) { page = 0; report(); }
    }

    function report() {
      if (!fetchFn || !opts.base() || !opts.token()) return;
      fetchFn(opts.base() + "/v1/home/shown", { method: "POST", headers: { "X-Omarchy-Token": opts.token(), "Content-Type": "text/plain" },
        body: JSON.stringify({ index: page }) }).then(function () {}, function () {});
    }

    function goTo(p, by) {
      if (!p || !hasPages() || (view.visible || []).indexOf(p) < 0) { close(); return false; }
      var changed = p !== page;
      page = p;
      if (changed) { focus = 0; armed = null; rotateAt = Date.now(); }
      render();
      if (changed) report();
      if (opts.onShow) opts.onShow(p, by);
      return true;
    }

    function add(box, act, id) {
      box.setAttribute("tabindex", "-1");
      items.push({ el: box, act: act, id: id });
    }

    function card(w) {
      var left = (w.x / COLS * 100), top = (w.y / ROWS * 100), wid = (w.w / COLS * 100), hei = (w.h / ROWS * 100);
      var c = el("div", "position:absolute;box-sizing:border-box;left:" + left + "%;top:" + top + "%;width:" + wid + "%;height:" + hei +
        "%;padding:.6vh .5vw");
      var b = el("div", "box-sizing:border-box;width:100%;height:100%;border:2px solid var(--muted,#565f89);padding:1.2vh 1vw;overflow:hidden;" +
        "background:rgba(0,0,0,.18)");
      var head = (w.icon ? w.icon + " " : "") + (w.title || "");
      if (head) b.appendChild(el("div", "color:var(--accent,#7aa2f7);font-size:2vh;margin-bottom:.8vh;white-space:nowrap;overflow:hidden;text-overflow:ellipsis", head));
      c.appendChild(b);
      try { body(w, b); } catch (e) { b.appendChild(el("div", "opacity:.6", "—")); }
      return c;
    }

    function chip(text, act, id) {
      var s = el("span", "display:inline-block;margin:0 .6vw .8vh 0;padding:.8vh 1vw;border:2px solid var(--muted,#565f89);font-size:2.1vh;" +
        "max-width:100%;box-sizing:border-box;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;vertical-align:top", text);
      add(s, act, id);
      return s;
    }

    function body(w, b) {
      var d = w.data || {}, i;
      switch (w.type) {
        case "lines":
          for (i = 0; i < (d.lines || []).length; i++) {
            var ln = d.lines[i], row = el("div", "display:flex;justify-content:space-between;font-size:2vh;line-height:1.5");
            row.appendChild(el("span", "opacity:.75", ln.label));
            row.appendChild(el("span", ln.tone === "up" ? "color:var(--green,#9ece6a)" : ln.tone === "down" ? "color:var(--red,#f7768e)" : "", ln.value));
            b.appendChild(row);
          }
          break;
        case "stat": {   // one row high: value, then label and delta on one line
          var big = el("div", "font-size:" + (w.h > 1 ? "6vh" : "4.2vh") + ";color:var(--fgBright,#c0caf5);line-height:1.1;white-space:nowrap", (d.value || "") + (d.unit ? " " + d.unit : ""));
          b.appendChild(big);
          var sub = el("div", "font-size:1.8vh;white-space:nowrap;overflow:hidden;text-overflow:ellipsis");
          if (d.label) sub.appendChild(el("span", "opacity:.75;margin-right:.6vw", d.label));
          if (d.delta) sub.appendChild(el("span", "color:" + (d.trend === "down" ? "var(--red,#f7768e)" : "var(--green,#9ece6a)"),
            (d.trend === "up" ? "▲ " : d.trend === "down" ? "▼ " : "") + d.delta));
          b.appendChild(sub);
          break;
        }
        case "text":
          b.appendChild(el("div", "white-space:pre-wrap;font-size:2vh;line-height:1.4", d.text || ""));
          break;
        case "image":
          if (webUrl(d.url)) {
            var img = el("img", "width:100%;height:85%;object-fit:" + (d.fit === "contain" ? "contain" : "cover"));
            img.setAttribute("src", d.url);
            img.setAttribute("referrerpolicy", "no-referrer");
            b.appendChild(img);
          }
          break;
        case "chart":
          b.appendChild(chart(d));
          break;
        case "page":
          if (lanUrl(d.url)) b.appendChild(chip("▶ " + t("wg_open", "Open"), function () { openFrame(d.url); }, w.id + ":open"));
          break;
        case "shortcuts":
          for (i = 0; i < (d.items || []).length; i++) (function (s) {
            var a = s.action || {}, label = (s.icon ? s.icon + " " : "") + s.label;
            if (a.type === "view" && /^https:\/\//i.test(a.uri || "")) b.appendChild(chip(label, function () { if (opts.openUrl) opts.openUrl(a.uri); }, w.id + ":" + s.id));
            else b.appendChild(el("span", "display:inline-block;margin:0 .6vw .8vh 0;padding:.8vh 1vw;opacity:.45;font-size:2.1vh", label));   // Android apps / PC commands
          })(d.items[i]);
          break;
        case "homeassistant.scenes":
        case "homeassistant.entities":
          haBody(w, d, b);
          break;
        case "radio": {
          var now = opts.radioNow ? opts.radioNow() : "";
          b.appendChild(chip("♪ " + (now || "Radio"), function () { if (opts.openRadio) opts.openRadio(); }, w.id + ":radio"));
          break;
        }
        case "qr": {
          var url = d.preset === "skill" ? SKILL + (opts.version ? "?v=" + opts.version() : "") : d.url;
          var Q = root.OmarchyQR;
          if (webUrl(url) && Q) {
            var cv = Q.canvas(doc, url, Math.round((root.innerHeight || 1080) * 0.22), "M");
            if (cv) { cv.setAttribute("style", "height:70%;image-rendering:pixelated"); b.appendChild(cv); }
          }
          b.appendChild(el("div", "font-size:1.7vh;opacity:.75", d.preset === "skill" ? "Agent skill" : (d.label || "")));
          break;
        }
      }
    }

    function chart(d) {
      var NS = "http://www.w3.org/2000/svg", s = doc.createElementNS(NS, "svg"), p = d.points || [];
      s.setAttribute("viewBox", "0 0 100 40");
      s.setAttribute("preserveAspectRatio", "none");
      s.setAttribute("style", "width:100%;height:70%");
      var lo = Math.min.apply(null, p), hi = Math.max.apply(null, p), span = hi - lo || 1, i;
      if (d.kind === "bars") {
        var bw = 100 / p.length;
        for (i = 0; i < p.length; i++) {
          var r = doc.createElementNS(NS, "rect"), h = Math.max(1, (p[i] - Math.min(0, lo)) / ((hi - Math.min(0, lo)) || 1) * 38);
          r.setAttribute("x", i * bw + bw * 0.1); r.setAttribute("width", bw * 0.8); r.setAttribute("y", 40 - h); r.setAttribute("height", h);
          r.setAttribute("fill", "var(--accent,#7aa2f7)");
          s.appendChild(r);
        }
      } else {
        var pts = [];
        for (i = 0; i < p.length; i++) pts.push((p.length === 1 ? 50 : i / (p.length - 1) * 100) + "," + (38 - (p[i] - lo) / span * 36));
        var pl = doc.createElementNS(NS, "polyline");
        pl.setAttribute("points", pts.join(" "));
        pl.setAttribute("fill", "none");
        pl.setAttribute("stroke", "var(--accent,#7aa2f7)");
        pl.setAttribute("stroke-width", "1.5");
        pl.setAttribute("vector-effect", "non-scaling-stroke");
        s.appendChild(pl);
      }
      var wrap = el("div", "width:100%;height:100%");
      wrap.appendChild(s);
      if (d.label || p.length) wrap.appendChild(el("div", "font-size:1.8vh;opacity:.75", (d.label ? d.label + " · " : "") + p[p.length - 1] + (d.unit ? " " + d.unit : "")));
      return wrap;
    }

    function haBody(w, d, b) {
      if (!ha()) { b.appendChild(el("div", "opacity:.6;font-size:1.9vh", t("wg_ha", "Set up Home Assistant in Ambilight & Lights"))); return; }
      var scenes = w.type === "homeassistant.scenes", list = d.items === "all" ? null : (d.items || []);
      var holder = el("div");
      b.appendChild(holder);
      function draw(states) {
        var byId = {}, i, its = list;
        for (i = 0; states && i < states.length; i++) byId[states[i].entity_id] = states[i];
        if (!its) {   // "all": every scene, by name
          its = [];
          for (i = 0; states && i < states.length; i++) if (/^scene\./.test(states[i].entity_id)) its.push({ entity: states[i].entity_id });
          its = its.slice(0, 48);
        }
        holder.textContent = "";
        for (i = 0; i < its.length; i++) (function (it) {
          var st = byId[it.entity], name = it.label || (st && st.attributes && st.attributes.friendly_name) || it.entity;
          var key = w.id + ":" + it.entity, mark = flash[key] ? " " + flash[key] : "";
          if (scenes || haService(it.entity)) {
            var shown = (it.icon ? it.icon + " " : "") + name + (!scenes && st ? " · " + st.state : "") + (armed === key ? " · OK?" : "") + mark;
            holder.appendChild(chip(shown, function () {
              if (it.confirm && armed !== key) { armed = key; render(); setTimeout(function () { if (armed === key) { armed = null; render(); } }, 3000); return; }
              armed = null;
              haRun(it.entity).then(function () { flash[key] = "✓"; render(); }, function () { flash[key] = "✗"; render(); });
              setTimeout(function () { delete flash[key]; if (layer) render(); }, 2500);
            }, key));
          } else {
            var row = el("div", "display:flex;justify-content:space-between;font-size:2vh;line-height:1.5");
            row.appendChild(el("span", "opacity:.75", name));
            row.appendChild(el("span", "", st ? st.state + (st.attributes && st.attributes.unit_of_measurement ? " " + st.attributes.unit_of_measurement : "") : "—"));
            holder.appendChild(row);
          }
        })(its[i]);
      }
      draw(haCache.states);
      if (!haCache.states || Date.now() - haCache.at >= 30000) haStates(false).then(function () { if (layer) render(); }, function () {});
    }

    function navigator() {
      var vis = view.visible || [], total = vis.length, pos = vis.indexOf(page) + 1;
      var win = windowOf(pos, total, WINDOW), nav = el("div", "position:absolute;left:2.5vw;right:2.5vw;bottom:2vh;font-size:2.2vh;white-space:nowrap");
      nav.appendChild(el("span", "opacity:.7;margin-right:1.2vw", "⌂"));
      nav.appendChild(el("span", "margin-right:1vw;opacity:" + (win[2] ? ".9" : ".2"), "‹"));
      for (var n = win[0]; n <= win[1]; n++) {
        nav.appendChild(el("span", "margin-right:1vw;padding:0 .4vw;" + (n === pos ? "color:var(--bg,#1a1b26);background:var(--accent,#7aa2f7)" : "opacity:.7"), String(n)));
      }
      nav.appendChild(el("span", "margin-right:1.4vw;opacity:" + (win[3] ? ".9" : ".2"), "›"));
      nav.appendChild(el("span", "opacity:.7", pos + " / " + total + " · " + pageName(page)));
      return nav;
    }

    function render() {
      if (!page || !view) return;
      var l = ensureLayer(), keepId = items[focus] && items[focus].id;
      l.textContent = "";
      items = [];
      frame = null;
      var grid = el("div", "position:absolute;left:2.5vw;right:2.5vw;top:3vh;bottom:8vh");
      var ws = view.widgets || [];
      for (var i = 0; i < ws.length; i++) if (ws[i].page === page) grid.appendChild(card(ws[i]));
      l.appendChild(grid);
      l.appendChild(navigator());
      if (keepId) for (var k = 0; k < items.length; k++) if (items[k].id === keepId) focus = k;
      if (focus >= items.length) focus = 0;
      paint();
    }

    function paint() {
      for (var i = 0; i < items.length; i++) {
        items[i].el.style.borderColor = i === focus ? "var(--accent,#7aa2f7)" : "var(--muted,#565f89)";
        items[i].el.style.color = i === focus ? "var(--fgBright,#c0caf5)" : "";
      }
    }

    function openFrame(url) {
      if (!lanUrl(url) || !layer) return;
      layer.textContent = "";
      items = [];
      frame = el("iframe", "border:0;width:100%;height:100%;background:#fff");
      frame.setAttribute("sandbox", "allow-scripts allow-same-origin allow-forms");
      frame.setAttribute("src", url);
      layer.appendChild(frame);
    }

    /** Arrows: the nearest button in that direction (by the centres on screen). */
    function moveFocus(dx, dy) {
      if (items.length < 2) return;
      var a = items[focus].el.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2, best = -1, bestD = 1e12;
      for (var i = 0; i < items.length; i++) {
        if (i === focus) continue;
        var r = items[i].el.getBoundingClientRect(), x = r.left + r.width / 2 - ax, y = r.top + r.height / 2 - ay;
        if ((dx && x * dx <= 1) || (dy && y * dy <= 1)) continue;
        var d = dx ? Math.abs(x) + 2 * Math.abs(y) : Math.abs(y) + 2 * Math.abs(x);
        if (d < bestD) { bestD = d; best = i; }
      }
      if (best >= 0) { focus = best; paint(); }
    }

    /** True when the key was used. */
    function key(code) {
      if (!layer) {
        if (!hasPages() || (opts.idle && !opts.idle())) return false;
        if (CH_UP[code]) return goTo(stepPage(view.visible, 0, 1), "key") || true;
        if (CH_DOWN[code]) return goTo(stepPage(view.visible, 0, -1), "key") || true;
        return false;
      }
      if (frame) { if (BACK[code]) { render(); return true; } return false; }   // a page: its own keys
      if (BACK[code]) { close(); return true; }
      if (CH_UP[code] || CH_DOWN[code]) { goTo(stepPage(view.visible, page, CH_UP[code] ? 1 : -1), "key"); return true; }
      if (code === 37) moveFocus(-1, 0);
      else if (code === 39) moveFocus(1, 0);
      else if (code === 38) moveFocus(0, -1);
      else if (code === 40) moveFocus(0, 1);
      else if (code === 13) { if (items[focus]) items[focus].act(); }
      else if (code >= 48 && code <= 57) return true;   // not the workspaces under this page
      else return false;
      return true;
    }

    function apply(v) {
      if (!v || !v.widgets) return;
      view = v;
      var show = v.show || {};
      if (seenShow === null) seenShow = show.at || 0;   // a "show" from before this app started is old news
      else if ((show.at || 0) > seenShow) {
        seenShow = show.at;
        if (!opts.idle || opts.idle() || layer) goTo(show.index, "agent");
      }
      if (layer && !frame) {
        if ((v.visible || []).indexOf(page) < 0 || !hasPages()) close();
        else render();
      }
    }

    function rotateTick() {
      var sec = opts.rotateSec ? opts.rotateSec() : 0;
      if (!sec || !hasPages() || frame || Date.now() - rotateAt < sec * 1000) return;
      if (opts.idle && !opts.idle() && !layer) return;
      var turn = [], ps = view.pages || [];
      for (var i = 0; i < ps.length; i++) if (ps[i].rotate && (view.visible || []).indexOf(ps[i].index) >= 0) turn.push(ps[i].index);
      rotateAt = Date.now();
      if (turn.length) goTo(turn[(turn.indexOf(page) + 1) % turn.length], "rotate");
    }

    function tick() {
      rotateTick();
      if (busy || !fetchFn || !opts.base() || !opts.token()) return Promise.resolve(null);
      busy = true;
      return fetchFn(opts.base() + "/v1/home", { headers: { "X-Omarchy-Token": opts.token() } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (v) { busy = false; apply(v); return v; }, function () { busy = false; return null; });
    }

    return {
      tick: tick, key: key, apply: apply, close: close, goTo: goTo,
      isOpen: function () { return !!layer; }, page: function () { return page; }, hasPages: hasPages,
      focused: function () { return items[focus] ? items[focus].id : null; }
    };
  }

  var api = { create: create, windowOf: windowOf, haService: haService, stepPage: stepPage, lanUrl: lanUrl, webUrl: webUrl };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.OmarchyHomeGrid = api;
})(typeof window !== "undefined" ? window : this);
