/*
 * OmarchyTV webOS: compatibility layer for older LG TVs (webOS 3.x, WebAppManager on Chrome 38).
 * Loaded before data.js / app.js. Every part is feature-detected, so on webOS 4+ it does nothing.
 *
 * Chrome 38 lacks Object.assign, fetch, CSS custom properties, CSS grid, flex `gap`, `inset` and
 * `aspect-ratio`. Without them app.js throws on startup and the UI renders black. This file adds the
 * two JS polyfills and, only when var() is unsupported, rewrites the inline <style> with the theme
 * values resolved (re-rendered whenever app.js sets a --t-* property on a theme change).
 */
(function () {
  "use strict";

  if (!Math.cbrt) Math.cbrt = function (x) { return x < 0 ? -Math.pow(-x, 1 / 3) : Math.pow(x, 1 / 3); };   // Chrome 38 (vendored Grok Bot palette)
  if (!Object.assign) Object.assign = function (target) {
    for (var i = 1; i < arguments.length; i++) {
      var src = arguments[i];
      if (src != null) for (var k in src) if (Object.prototype.hasOwnProperty.call(src, k)) target[k] = src[k];
    }
    return target;
  };

  // Minimal fetch: GET JSON from Open-Meteo and the PC bridge, POST a question to the bridge's Grok Bot.
  if (!window.fetch) window.fetch = function (url, opts) {
    opts = opts || {};
    return new Promise(function (resolve, reject) {
      var x = new XMLHttpRequest();
      x.open(opts.method || "GET", url);
      var h = opts.headers || {};
      for (var k in h) if (Object.prototype.hasOwnProperty.call(h, k)) x.setRequestHeader(k, h[k]);
      x.onload = function () {
        resolve({
          ok: x.status >= 200 && x.status < 300,
          status: x.status,
          text: function () { return Promise.resolve(x.responseText); },
          json: function () { return Promise.resolve(JSON.parse(x.responseText)); }
        });
      };
      x.onerror = x.ontimeout = function () { reject(new TypeError("Network request failed")); };
      x.send(opts.body == null ? null : opts.body);
    });
  };

  if (window.CSS && CSS.supports && CSS.supports("color", "var(--x)")) return;

  var styleEl = document.querySelector("style");
  var src = styleEl.textContent.replace(/\/\*[\s\S]*?\*\//g, "");
  var vars = {};      // :root custom properties, updated by app.js through setProperty()
  var blocks = [];    // plain rules {sel, decls} and raw @-blocks {raw}
  var scopes = [];    // rules that declare their own custom properties (.box, .box.focused)
  var aspects = [];   // {sel, ratio} for the aspect-ratio emulation

  function decls(body) {
    return body.split(";").map(function (d) {
      var i = d.indexOf(":");
      return i < 0 ? null : [d.slice(0, i).trim(), d.slice(i + 1).trim()];
    }).filter(function (d) { return d && d[0]; });
  }

  for (var pos = 0; pos < src.length;) {
    var open = src.indexOf("{", pos);
    if (open < 0) break;
    var sel = src.slice(pos, open).trim();
    if (sel.charAt(0) === "@") {   // @font-face / @keyframes: keep as-is
      var depth = 1, j = open + 1;
      for (; j < src.length && depth; j++) depth += src[j] === "{" ? 1 : src[j] === "}" ? -1 : 0;
      blocks.push({ raw: src.slice(pos, j) });
      pos = j;
      continue;
    }
    var close = src.indexOf("}", open);
    var ds = decls(src.slice(open + 1, close));
    if (sel === ":root") {
      ds.forEach(function (d) { vars[d[0]] = d[1]; });
    } else {
      var own = {}, hasOwn = false;
      ds.forEach(function (d) { if (d[0].indexOf("--") === 0) { own[d[0]] = d[1]; hasOwn = true; } });
      if (hasOwn) scopes.push({ sel: sel, vars: own });
      blocks.push({ sel: sel, decls: ds.filter(function (d) { return d[0].indexOf("--") !== 0; }) });
    }
    pos = close + 1;
  }

  function resolve(v, ctx) {
    for (var n = 0; n < 5 && v.indexOf("var(") >= 0; n++) {
      v = v.replace(/var\((--[\w-]+)\s*(?:,\s*([^)]*))?\)/g, function (_, name, fb) {
        return ctx[name] != null ? ctx[name] : vars[name] != null ? vars[name] : (fb || "");
      });
    }
    return v;
  }

  // One rule with vars resolved; grid -> wrapping flex, gap -> margins, inset -> 4 offsets.
  function ruleCss(sel, ds, ctx) {
    var map = {}, out = [], extra = [], m;
    ds.forEach(function (d) { map[d[0]] = resolve(d[1], ctx); });
    var gap = map.gap;
    var cols = (m = /repeat\((\d+)/.exec(map["grid-template-columns"] || "")) && +m[1];
    ds.forEach(function (d) {
      var p = d[0], v = map[p];
      if (p === "inset") out.push("top:" + v + ";right:" + v + ";bottom:" + v + ";left:" + v);
      else if (p === "gap" || p === "grid-template-columns" || p === "aspect-ratio") return;
      else if (p === "display" && v === "grid") out.push("display:flex;flex-wrap:wrap;align-content:flex-start");
      else out.push(p + ":" + v);
    });
    if (map["aspect-ratio"] && (m = /([\d.]+)\s*\/\s*([\d.]+)/.exec(map["aspect-ratio"]))) {
      aspects.push({ sel: sel, ratio: m[2] / m[1] });
    }
    if (gap) sel.split(",").forEach(function (s) {
      s = s.trim();
      if (cols) {
        // margins only between cells, so the last row adds no extra height (no scrollbar)
        extra.push(s + ">*{width:calc((100% - " + (cols - 1) + " * " + gap + ") / " + cols + ");margin-right:" + gap + "}");
        extra.push(s + ">*:nth-child(" + cols + "n){margin-right:0}");
        extra.push(s + ">*:nth-child(n+" + (cols + 1) + "){margin-top:" + gap + "}");
      } else if (map["flex-direction"] === "column") {
        extra.push(s + ">*+*{margin-top:" + gap + "}");
      } else {
        extra.push(s + ">*+*{margin-left:" + gap + "}");
      }
    });
    return sel + "{" + out.join(";") + "}" + extra.join("");
  }

  // `p` re-scoped under a rule that overrides custom properties (".box-top-line" -> ".box.focused .box-top-line").
  function scoped(s, p) {
    var base = /^[.#][\w-]+/.exec(s.sel)[0];
    return p.indexOf(base) === 0 && !/[\w-]/.test(p.charAt(base.length)) ? s.sel + p.slice(base.length) : s.sel + " " + p;
  }

  function renderSheet() {
    var css = [];
    aspects = [];
    blocks.forEach(function (b) {
      if (b.raw) { css.push(resolve(b.raw, {})); return; }
      var usesScoped = scopes.length && b.decls.some(function (d) { return /var\(--box-/.test(d[1]); });
      css.push(ruleCss(b.sel, b.decls, usesScoped ? scopes[0].vars : {}));
      if (usesScoped) scopes.slice(1).forEach(function (s) {
        var sel = b.sel.split(",").map(function (p) { return scoped(s, p.trim()); }).join(",");
        css.push(ruleCss(sel, b.decls, Object.assign({}, scopes[0].vars, s.vars)));
      });
    });
    styleEl.textContent = css.join("\n");
  }

  // Inline style="" and SVG stroke/fill="var(--x)", including markup app.js builds with innerHTML.
  function fixInline(root) {
    var els = root.querySelectorAll ? root.querySelectorAll("[style*='var(--'],[style*='gap:'],[data-compat-style],[stroke*='var(--'],[fill*='var(--'],[data-compat-stroke],[data-compat-fill]") : [];
    var list = [root].concat([].slice.call(els));
    list.forEach(function (el) {
      if (!el.getAttribute) return;
      var st = el.getAttribute("data-compat-style") || el.getAttribute("style");
      if (st && (st.indexOf("var(--") >= 0 || /(^|;)\s*gap\s*:/.test(st))) {
        el.setAttribute("data-compat-style", st);
        var resolved = resolve(st, {});
        el.setAttribute("style", resolved);
        var g = /(?:^|;)\s*gap\s*:\s*([^;]+)/.exec(resolved);
        if (g) for (var c = el.firstElementChild && el.firstElementChild.nextElementSibling; c; c = c.nextElementSibling) c.style.marginLeft = g[1];
      }
      ["stroke", "fill"].forEach(function (a) {
        var key = "data-compat-" + a, v = el.getAttribute(key) || el.getAttribute(a);
        if (v && v.indexOf("var(--") >= 0) { el.setAttribute(key, v); el.setAttribute(a, resolve(v, {})); }
      });
    });
  }

  // aspect-ratio: size elements right after they are inserted, before the next paint, so rebuilt dock
  // tiles never flash at 0 px height. No polling: DOM mutations and resize only.
  function sizeAspects() {
    aspects.forEach(function (a) {
      var els = document.querySelectorAll(a.sel);
      for (var i = 0; i < els.length; i++) {
        if (els[i].offsetWidth) els[i].style.height = Math.round(els[i].offsetWidth * a.ratio) + "px";
      }
    });
  }

  function renderAll() {
    renderSheet();
    fixInline(document.body);
    sizeAspects();
  }

  new MutationObserver(function (records) {
    records.forEach(function (r) { for (var i = 0; i < r.addedNodes.length; i++) if (r.addedNodes[i].nodeType === 1) fixInline(r.addedNodes[i]); });
    sizeAspects();
  }).observe(document.body, { childList: true, subtree: true });
  window.addEventListener("resize", sizeAspects);
  window.addEventListener("load", sizeAspects);

  // app.js applies themes with documentElement.style.setProperty("--t-*", ...): keep `vars` in sync.
  var pending = false;
  var setProp = CSSStyleDeclaration.prototype.setProperty;
  CSSStyleDeclaration.prototype.setProperty = function (name, value) {
    if (String(name).indexOf("--") !== 0) return setProp.apply(this, arguments);
    vars[name] = String(value);
    if (!pending) {
      pending = true;
      setTimeout(function () { pending = false; renderAll(); }, 0);
    }
  };

  renderAll();
})();
