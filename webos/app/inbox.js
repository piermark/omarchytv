/*
 * OmarchyTV — v2.21 agents on LG webOS and Samsung Tizen.
 *
 * The PC bridge (omarchy-tv webos-serve) keeps an inbox for this TV: notifications, a page to open full screen and
 * questions to answer with the remote. The app polls GET /v1/inbox every few seconds with the ask code, and:
 *   - shows new notifications in the Omarchy style (mako-like, top right, the theme's accent border);
 *   - opens the page full screen (an iframe of a LAN / tailnet address; BACK closes it);
 *   - shows each question with its answers; OK answers (POST /v1/ask/<id>), BACK leaves it for later.
 * ES5 for Chrome 38 (webOS 3). No state on disk.
 */
(function (root) {
  "use strict";

  var BACK = { 461: 1, 10009: 1, 27: 1, 8: 1 };

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  /** http:// to a literal private or tailnet IPv4 address: the only pages the TV opens. */
  function lanUrl(u) {
    var m = /^http:\/\/(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:\d{1,5})?(\/[^\s]*)?$/.exec(String(u || ""));
    if (!m) return false;
    var a = +m[1], b = +m[2];
    return a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31) || (a === 100 && b >= 64 && b <= 127);
  }

  function create(opts) {
    var doc = opts.doc || root.document, seq = 0, pageSeq = 0, skipped = {}, busy = false, open = null;
    var fetchFn = opts.fetch || (root.fetch ? root.fetch.bind(root) : null);

    function headers(json) {
      var h = { "X-Omarchy-Token": opts.token() || "" };
      if (json) h["Content-Type"] = "text/plain";
      return h;
    }

    // ---- notifications, mako style -----------------------------------------------------------------------
    function stack() {
      var s = doc.getElementById("otv-notes");
      if (!s) {
        s = doc.createElement("div");
        s.id = "otv-notes";
        s.setAttribute("style", "position:fixed;top:3vh;right:2.5vw;z-index:99990;display:flex;flex-direction:column;gap:1vh;width:30vw;pointer-events:none");
        doc.body.appendChild(s);
      }
      return s;
    }

    function note(n) {
      var box = doc.createElement("div"), lvl = n.level === "alert" ? "var(--red,#f7768e)" : n.level === "warn" ? "var(--yellow,#e0af68)" : "var(--accent,#7aa2f7)";
      box.className = "otv-note";
      box.setAttribute("style", "background:var(--bg,#1a1b26);color:var(--fg,#a9b1d6);border:2px solid " + lvl +
        ";padding:1.2vh 1.2vw;font-family:inherit;font-size:2vh;line-height:1.35;box-shadow:0 0 0 1px rgba(0,0,0,.4)");
      box.innerHTML = "<div style='color:" + lvl + "'>● " + esc(n.from) + (n.title ? " · " + esc(n.title) : "") + "</div>" +
        (n.text ? "<div>" + esc(n.text) + "</div>" : "");
      var s = stack();
      s.appendChild(box);
      while (s.children.length > 4) s.removeChild(s.firstChild);
      setTimeout(function () { if (box.parentNode) box.parentNode.removeChild(box); }, n.level === "alert" ? 15000 : 7000);
      return box;
    }

    // ---- full-screen layer: a page or a question ---------------------------------------------------------
    function layer(html) {
      close();
      var l = doc.createElement("div");
      l.id = "otv-agent-layer";
      l.setAttribute("style", "position:fixed;inset:0;left:0;top:0;right:0;bottom:0;z-index:99980;background:var(--bg,#1a1b26);color:var(--fg,#a9b1d6)");
      l.innerHTML = html;
      doc.body.appendChild(l);
      open = { el: l };
      return l;
    }

    function close() {
      if (open && open.el && open.el.parentNode) open.el.parentNode.removeChild(open.el);
      open = null;
    }

    function showPage(p) {
      if (!lanUrl(p.url)) return null;
      var l = layer("<iframe src='" + esc(p.url) + "' style='border:0;width:100%;height:100%;background:#fff'></iframe>");
      open.kind = "page";
      return l;
    }

    function showAsk(a) {
      var btns = "";
      for (var i = 0; i < a.choices.length; i++) {
        btns += "<span class='otv-choice' data-i='" + i + "' style='display:inline-block;margin-right:1.2vw;padding:1vh 1.6vw;border:2px solid var(--muted,#565f89)'>" + esc(a.choices[i]) + "</span>";
      }
      var l = layer("<div style='position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:60vw;border:2px solid var(--accent,#7aa2f7);padding:4vh 3vw;font-size:2.6vh'>" +
        "<div style='color:var(--accent,#7aa2f7)'>● " + esc(a.from) + " " + esc(opts.asks ? opts.asks() : "asks") + "</div>" +
        "<div style='font-size:3.4vh;margin:2vh 0 3vh;color:var(--fgBright,#c0caf5)'>" + esc(a.question) + "</div>" +
        "<div>" + btns + "</div><div style='opacity:.6;margin-top:2.4vh;font-size:1.8vh'>" + esc(opts.hint ? opts.hint() : "OK answers · BACK later") + "</div></div>");
      open.kind = "ask";
      open.ask = a;
      open.sel = 0;
      paint();
      return l;
    }

    function paint() {
      if (!open || open.kind !== "ask") return;
      var cs = open.el.querySelectorAll(".otv-choice");
      for (var i = 0; i < cs.length; i++) cs[i].style.borderColor = i === open.sel ? "var(--accent,#7aa2f7)" : "var(--muted,#565f89)";
    }

    function answer(a, choice) {
      if (!fetchFn) return Promise.resolve(false);
      return fetchFn(opts.base() + "/v1/ask/" + a.id, { method: "POST", headers: headers(true), body: JSON.stringify({ answer: choice }) })
        .then(function (r) { return r.ok; }, function () { return false; });
    }

    /** Keys while a page or a question is on screen. True when the key was used. */
    function key(code) {
      if (!open) return false;
      if (BACK[code]) {
        if (open.kind === "ask") skipped[open.ask.id] = 1;
        close();
        return true;
      }
      if (open.kind === "ask") {
        var n = open.ask.choices.length;
        if (code === 37) { open.sel = (open.sel + n - 1) % n; paint(); }
        else if (code === 39) { open.sel = (open.sel + 1) % n; paint(); }
        else if (code === 13) { var a = open.ask, c = a.choices[open.sel]; skipped[a.id] = 1; close(); answer(a, c); }
        return true;
      }
      return false;   // a page: arrows and OK go to the page
    }

    function apply(box) {
      if (!box) return;
      var notes = box.notes || [];
      for (var i = 0; i < notes.length; i++) if (notes[i].seq > seq) note(notes[i]);
      if (typeof box.seq === "number") seq = box.seq;
      if (open && open.kind === "ask") return;   // one thing at a time
      if (box.page && box.page.seq > pageSeq) {
        pageSeq = box.page.seq;
        showPage(box.page);
        return;
      }
      if (!box.page && open && open.kind === "page" && pageSeq) { close(); return; }   // the PC closed it
      var asks = box.asks || [];
      for (var j = 0; j < asks.length; j++) {
        if (!skipped[asks[j].id] && !(open && open.kind === "page")) { showAsk(asks[j]); return; }
      }
    }

    function tick() {
      if (busy || !fetchFn || !opts.base() || !opts.token()) return Promise.resolve(null);
      busy = true;
      return fetchFn(opts.base() + "/v1/inbox?since=" + seq, { headers: headers(false) })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (box) { busy = false; apply(box); return box; }, function () { busy = false; return null; });
    }

    /** Questions you left with BACK come back when you ask for them (or on the next new question). */
    function later() { skipped = {}; }

    return { tick: tick, key: key, apply: apply, note: note, isOpen: function () { return !!open; }, kind: function () { return open && open.kind; }, later: later, close: close };
  }

  var api = { create: create, lanUrl: lanUrl, esc: esc };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.OmarchyInbox = api;
})(typeof window !== "undefined" ? window : this);
