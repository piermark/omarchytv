/* Cinemarchy on LG webOS and Samsung Tizen: the same hub as on Android TV, in Omarchy's TUI style. Three panes
 * (sources · library · details) in the theme's colours, a built-in player (the TV's own HTML5 video: MP4, HLS)
 * with an ASCII on-screen display, resume where you stopped (real positions, saved on this TV), your own M3U
 * playlists with groups and favourites, an Up-next queue, @dhh37's channel, the streaming apps and the TV inputs.
 * Nothing is bundled but four Blender open movies, labelled as demo streams. Only rows in view are drawn, so a
 * playlist with thousands of channels stays fast on old TVs.
 * And three things no other TV hub has: ASCII mode (the film drawn live as theme-coloured ASCII, like a terminal;
 * for streams that allow it), VHS/CRT tape mode (scanlines, a rolling tracking bar, the VCR counter), a surprise
 * channel with a channel-change burst, and an Omarchy-style sleep timer. Legal sources only: your playlists, your
 * PC, YouTube, open movies; no torrent or pirate add-ons. ES5.
 *
 * Host: OmarchyCinemarchy.open(el, api) -> { key(name, event) -> handled, destroy() }. See app.js openCinemarchy. */
(function (root) {
  "use strict";
  var ROWS0 = 12, RESUME = "omarchytv.cine.resume.v1", MAX_RESUME = 200;
  var DEMO = [
    ["Big Buck Bunny", "BigBuckBunny.mp4"], ["Sintel", "Sintel.mp4"],
    ["Tears of Steel", "TearsOfSteel.mp4"], ["Elephants Dream", "ElephantsDream.mp4"]
  ];
  var DEMO_BASE = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/";

  function rep(c, n) { var s = ""; for (var i = 0; i < n; i++) s += c; return s; }
  function bar(p, w) { var f = Math.max(0, Math.min(w, Math.round(p * w))); return rep("█", f) + rep("░", w - f); }
  function fmtTime(s) {
    s = Math.max(0, Math.floor(s || 0));
    var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
    return (h ? h + ":" + (m < 10 ? "0" : "") : "") + m + ":" + (x < 10 ? "0" : "") + x;
  }
  // ASCII mode: light to characters, with the contrast of each frame stretched (the darkest 2 % become spaces, the
  // brightest 2 % "@"), eased over a few frames so it never flickers. A dull or hazy film used to come out as a sheet
  // of ":;," (Sintel's sky); now its figures stand out. Kept a quarter of the plain light, and a flat frame keeps its
  // own level: black stays blank, grey stays grey, white stays white. The Android player does the same (CineFxView.java). st keeps the eased range.
  var RAMP = " .,:;-=+*#%@";
  function asciiArt(rgba, cols, rows, st) {
    var n = cols * rows, L = new Array(n), hist = [], B = 32, i, k;
    for (k = 0; k < B; k++) hist[k] = 0;
    for (i = 0; i < n; i++) { var l = (rgba[i * 4] * 0.3 + rgba[i * 4 + 1] * 0.59 + rgba[i * 4 + 2] * 0.11) / 255; L[i] = l; hist[Math.min(B - 1, Math.floor(l * B))]++; }
    var c = 0, lo = 0, hi = 1, cut = n * 0.02;
    for (k = 0; k < B; k++) { c += hist[k]; if (c > cut) { lo = k / B; break; } }
    c = 0;
    for (k = B - 1; k >= 0; k--) { c += hist[k]; if (c > cut) { hi = (k + 1) / B; break; } }
    if (st) {
      if (st.lo == null) { st.lo = lo; st.hi = hi; } else { st.lo += (lo - st.lo) * 0.3; st.hi += (hi - st.hi) * 0.3; }
      lo = st.lo; hi = st.hi;
    }
    var span = hi - lo, out = "", R = RAMP.length;
    if (span < 0.2) { lo = Math.max(0, Math.min(0.8, (lo + hi) / 2 - 0.1)); span = 0.2; }   // a flat frame: black, grey or white as it is
    for (i = 0; i < n; i++) {
      var v = 0.25 * L[i] + 0.75 * Math.max(0, Math.min(1, (L[i] - lo) / span));
      out += RAMP.charAt(Math.min(R - 1, Math.floor(v * R)));
      if ((i + 1) % cols === 0) out += "\n";
    }
    return out;
  }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function host(u) { var m = /^[a-z]+:\/\/([^\/?#]+)/i.exec(u || ""); return m ? m[1] : ""; }
  function validUrl(u) { return /^https?:\/\/[^\s]+$/i.test(u || ""); }

  /** #EXTM3U playlists: [{ name, url, group, logo }] (attributes tvg-name, group-title, tvg-logo). */
  function parseM3U(text) {
    var out = [], cur = null, lines = String(text || "").split(/\r?\n/);
    for (var i = 0; i < lines.length && out.length < 20000; i++) {
      var l = lines[i].replace(/^\s+|\s+$/g, "");
      if (!l) continue;
      if (l.indexOf("#EXTINF") === 0) {
        var comma = l.indexOf(","), attrs = comma >= 0 ? l.slice(0, comma) : l, name = comma >= 0 ? l.slice(comma + 1) : "";
        var g = /group-title="([^"]*)"/.exec(attrs), lg = /tvg-logo="([^"]*)"/.exec(attrs), tn = /tvg-name="([^"]*)"/.exec(attrs);
        cur = { name: (name || (tn && tn[1]) || "").replace(/^\s+|\s+$/g, "") || "Channel", group: g ? g[1] : "", logo: lg ? lg[1] : "" };
      } else if (l.charAt(0) !== "#") {
        if (validUrl(l) || /^rtmp|^udp|^rtp/i.test(l)) out.push({ name: cur ? cur.name : l, url: l, group: cur ? cur.group : "", logo: cur ? cur.logo : "" });
        cur = null;
      }
    }
    return out;
  }

  /** Resume positions on this TV: { url: { pos, dur, at, title } }, the newest 200. */
  function Resume(store) {
    function all() { try { return JSON.parse(store.getItem(RESUME)) || {}; } catch (e) { return {}; } }
    return {
      get: function (url) { return all()[url] || null; },
      put: function (url, pos, dur, title) {
        if (!url || !(dur > 0)) return;
        var a = all();
        if (pos < 20 || pos > dur * 0.95) delete a[url];   // barely started or finished: start fresh next time
        else a[url] = { pos: Math.floor(pos), dur: Math.floor(dur), at: Date.now(), title: title || "" };
        var keys = Object.keys(a);
        if (keys.length > MAX_RESUME) keys.sort(function (x, y) { return a[x].at - a[y].at; }).slice(0, keys.length - MAX_RESUME).forEach(function (k) { delete a[k]; });
        try { store.setItem(RESUME, JSON.stringify(a)); } catch (e) {}
      },
      progress: function (url) { var r = all()[url]; return r && r.dur ? r.pos / r.dur : 0; }
    };
  }

  function open(el, api) {
    var t = api.tr, th = api.theme, doc = el.ownerDocument || document, store = api.store;
    var resume = Resume(store);
    function cine() { var c = api.cfg.cine || (api.cfg.cine = {}); c.iptv = c.iptv || []; c.queue = c.queue || []; c.fav = c.fav || []; return c; }
    var ROWS = ROWS0;   // set from the real pane height once drawn
    var S = { pcPath: null, pcList: null, src: 0, pane: "src", idx: [], off: [], items: [], iptv: null, iptvLoading: false, iptvError: "", group: "ALL", prompt: null, player: null, toastT: 0 };
    var SOURCES = [
      { id: "dhh", label: "@dhh37", name: t("cine_src_dhh") }, { id: "pc", label: "omarchy-pc://", name: t("cine_src_pc") },
      { id: "stream", label: "stream://", name: t("cine_src_stream") },
      { id: "live", label: "livetv://", name: t("cine_src_live") }, { id: "iptv", label: "iptv://", name: t("cine_src_iptv") },
      { id: "demo", label: "open://", name: t("cine_src_demo") }, { id: "queue", label: "queue://", name: t("cine_src_queue") },
      { id: "tools", label: "tools://", name: t("cine_src_tools") }
    ];

    // ---- DOM ----
    var w = doc.createElement("div"), st = doc.createElement("style");
    w.className = "cine";
    var A = th.accent, F = th.fg, D = th.dim, B = th.bg;
    st.textContent = ".cine{position:absolute;left:0;top:0;right:0;bottom:0;z-index:55;background:" + B + ";color:" + F + ";font-family:CaskaydiaMono,monospace;font-size:1.32vw;line-height:1.35;overflow:hidden}" +
      ".cine .hd{position:absolute;left:2vw;right:2vw;top:2.4vh;height:7vh;white-space:nowrap}.cine .hd svg{display:inline-block!important;vertical-align:middle;color:" + A + ";filter:drop-shadow(0 0 .5vw " + A + "66)}" +
      ".cine .logo{color:" + A + ";font-size:2.6vw;letter-spacing:.35em;font-weight:bold;text-shadow:0 0 1.2vw " + A + "66}" +
      ".cine .ver{color:" + D + ";margin-left:1em;font-size:.8em}.cine .clk{position:absolute;right:0;top:.6vh;color:" + D + "}" +
      ".cine .bx{position:absolute;border:.14vw solid " + D + ";border-radius:.35vw;padding:1.6vh 1vw 1vh}" +
      ".cine .bx.on{border-color:" + A + ";box-shadow:0 0 1.4vw " + A + "33}" +
      ".cine .lg{position:absolute;top:-1.05em;left:.8em;background:" + B + ";padding:0 .4em;color:" + D + "}.cine .bx.on .lg{color:" + A + "}" +
      ".cine .r{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding:.18em .5em;border-radius:.25em;height:1.35em}" +
      ".cine .r .b{color:" + A + "}.cine .r .s{color:" + D + "}.cine .r .p{color:" + th.green + ";float:right}" +
      ".cine .r.sel{background:" + D + "55}.cine .bx.on .r.sel{background:" + A + ";color:" + B + "}.cine .bx.on .r.sel .b,.cine .bx.on .r.sel .s,.cine .bx.on .r.sel .p{color:" + B + "}" +
      ".cine .more{color:" + D + ";text-align:center;height:1.2em}" +
      ".cine .dt h1{font-size:1.5em;margin:0 0 .4em;color:" + th.bright + ";font-weight:bold;white-space:normal}" +
      ".cine .dt .m{color:" + D + ";margin:.2em 0}.cine .dt .bar{color:" + th.green + ";margin:.8em 0}.cine .dt .cli{margin-top:1em;color:" + th.cyan + ";white-space:pre-wrap;font-size:.9em}" +
      ".cine .ft{position:absolute;left:2vw;right:2vw;bottom:2.4vh;color:" + D + ";white-space:nowrap;overflow:hidden}" +
      ".cine .tst{position:absolute;left:50%;bottom:8vh;transform:translateX(-50%);-webkit-transform:translateX(-50%);background:" + A + ";color:" + B + ";padding:.5em 1.2em;border-radius:.3em;display:none}" +
      ".cine .pr{position:absolute;left:20vw;right:20vw;top:35vh;background:" + B + ";border:.16vw solid " + A + ";padding:2.5vh 2vw;display:none}" +
      ".cine .pr input{width:100%;font:inherit;background:" + th.bg + ";color:" + F + ";border:.12vw solid " + D + ";padding:.4em;margin:.8em 0;box-sizing:border-box}" +
      ".cine .pl{position:absolute;left:0;top:0;right:0;bottom:0;background:#000;display:none}.cine .pl video{width:100%;height:100%;background:#000}" +
      ".cine video::cue{font-family:CaskaydiaMono,monospace;color:" + F + ";background:rgba(0,0,0,.72)}" +
      ".cine .osd{position:absolute;left:0;right:0;bottom:0;padding:3vh 3vw 4vh;background:linear-gradient(transparent,rgba(0,0,0,.85));color:" + F + ";-webkit-transition:opacity .3s;transition:opacity .3s}" +
      ".cine .osd .tt{color:" + A + ";font-size:1.4em;margin-bottom:.4em}.cine .osd .tl{color:" + th.green + "}.cine .osd .ky{color:" + D + ";margin-top:.5em}" +
      ".cine .asc{position:absolute;left:0;top:0;right:0;bottom:0;margin:0;display:none;background:#000;color:" + A + ";font-family:CaskaydiaMono,monospace;line-height:1;white-space:pre;text-align:center;text-shadow:0 0 .4em " + A + "88;overflow:hidden}" +
      ".cine .vhs{position:absolute;left:0;top:0;right:0;bottom:0;display:none;pointer-events:none;background:repeating-linear-gradient(to bottom,rgba(0,0,0,0) 0,rgba(0,0,0,0) 2px,rgba(0,0,0,.35) 3px,rgba(0,0,0,0) 4px)}" +
      ".cine .vhs .trk{position:absolute;left:0;right:0;height:6vh;background:linear-gradient(transparent,rgba(255,255,255,.12),rgba(255,255,255,.05),transparent);-webkit-animation:cinetrk 7s linear infinite;animation:cinetrk 7s linear infinite}" +
      "@-webkit-keyframes cinetrk{from{top:-8vh}to{top:105vh}}@keyframes cinetrk{from{top:-8vh}to{top:105vh}}" +
      ".cine .vhs .vcr{position:absolute;left:4vw;top:5vh;color:#fff;font-size:2.4vw;text-shadow:.15vw .15vw 0 #000,0 0 .6vw #fff8;letter-spacing:.1em}" +
      ".cine .vhs .vcr2{position:absolute;right:4vw;bottom:6vh;color:#fff;font-size:2vw;text-shadow:.15vw .15vw 0 #000}" +
      ".cine .zap{position:absolute;left:0;top:0;right:0;bottom:0;margin:0;display:none;background:#000;color:#888;font-family:monospace;font-size:1.6vw;line-height:1;overflow:hidden;white-space:pre}" +
      ".cine .zap b{position:absolute;left:50%;top:45%;transform:translateX(-50%);-webkit-transform:translateX(-50%);background:" + A + ";color:" + B + ";padding:.2em .8em;font-size:2.4vw}";
    w.innerHTML = '<div class="hd"><span class="logo">CINEMARCHY</span><span class="ver"></span><span class="clk"></span></div>' +
      '<div class="bx p0" style="left:2vw;top:12.5vh;width:19vw;bottom:8vh"><span class="lg"></span><div class="rows"></div></div>' +
      '<div class="bx p1" style="left:24vw;top:12.5vh;width:43vw;bottom:8vh"><span class="lg"></span><div class="rows"></div></div>' +
      '<div class="bx p2 dt" style="left:70vw;top:12.5vh;right:2vw;bottom:8vh"><span class="lg"></span><div class="in"></div></div>' +
      '<div class="ft"></div><div class="tst"></div>' +
      '<div class="pr"><div class="pt"></div><input type="url" autocomplete="off"><div class="ph" style="color:' + D + '"></div></div>' +
      '<div class="pl"><video></video><pre class="asc"></pre><div class="vhs"><div class="trk"></div><div class="vcr"></div><div class="vcr2"></div></div><div class="osd"><div class="tt"></div><div class="tl"></div><div class="ky"></div></div></div><pre class="zap"><b></b></pre>';
    w.insertBefore(st, w.firstChild);
    el.appendChild(w);
    function q(sel) { return w.querySelector(sel); }
    q(".ver").textContent = api.version || "";
    if (api.logoHtml) { var lg = q(".logo"); lg.innerHTML = api.logoHtml; lg.style.letterSpacing = "0"; }
    q(".p0 .lg").textContent = t("cine_sources");
    q(".p1 .lg").textContent = t("cine_library");
    q(".p2 .lg").textContent = t("cine_details");
    q(".ft").textContent = t("cine_keys");
    // typing a URL: the letters and digits stay in the field, nothing else in the launcher sees them
    q(".pr input").addEventListener("keydown", function (e) { var c = e.keyCode; if (c !== 13 && c !== 461 && c !== 10009 && c !== 27) e.stopPropagation(); });

    function toast(msg) {
      var e = q(".tst"); e.textContent = msg; e.style.display = "block";
      clearTimeout(S.toastT); S.toastT = setTimeout(function () { e.style.display = "none"; }, 2200);
    }

    // ---- the library of each source ----
    function progressItem(it) {
      if (it.live || !it.url) return it;
      var r = resume.get(it.url);
      if (r) { it.prog = bar(r.pos / r.dur, 6) + " " + Math.round(r.pos * 100 / r.dur) + "%"; it.resumeAt = r.pos; }
      return it;
    }
    function itemsFor(src) {
      var id = SOURCES[src].id, out = [], c = cine();
      if (id === "dhh") {
        var st2 = api.dhh.status();
        out.push({ title: "@dhh37 · " + st2, sub: t("cine_refresh"), badge: "[↻]", kind: "action", act: function () { api.dhh.load(true, refresh); toast(t("cine_loading")); } });
        api.dhh.list().forEach(function (v) {
          out.push({ title: v.title, sub: "@dhh37 · " + (v.published ? new Date(v.published).toLocaleDateString() : "youtube"), badge: api.dhh.isNew(v) ? "[" + t("cine_new") + "]" : "[▶]",
            kind: "youtube", info: "youtube.com/watch?v=" + v.id, act: function () { api.dhh.play(v); } });
        });
      } else if (id === "pc") {
        if (!api.pc || !api.pc.available()) { out.push({ title: t("cine_pc_need"), sub: "", badge: "[PC]", kind: "noop" }); return out; }
        if (S.pcPath === null || S.pcPath === undefined) {
          out.push({ title: "📁 " + t("cine_pc_folders"), sub: "~/Videos · ~/Music · ~/Pictures · ~/Downloads", badge: "[DIR]", kind: "action", act: function () { pcOpen(""); } });
          return out;
        }
        var L = S.pcList;
        out.push({ title: t("cine_up"), sub: "/" + (S.pcPath || ""), badge: "[..]", kind: "action", act: function () { pcUp(); } });
        if (!L) { out.push({ title: t("cine_loading"), sub: "", badge: "[…]", kind: "noop" }); return out; }
        if (L.error) out.push({ title: L.error, sub: "", badge: "[!]", kind: "noop" });
        (L.dirs || []).forEach(function (d) { out.push({ title: d.name + "/", sub: "", badge: "[DIR]", kind: "action", act: function () { pcOpen(d.path); } }); });
        (L.files || []).forEach(function (f) {
          out.push(progressItem({ title: f.name, sub: (f.kind === "image" ? "🖼 " : f.kind === "audio" ? "♪ " : "") + Math.round((f.size || 0) / 1048576) + " MB" + (f.subUrl ? " · CC" : ""),
            badge: f.kind === "video" ? "[▶]" : f.kind === "audio" ? "[♪]" : "[IMG]", kind: "stream", url: api.pc.base() + f.url, subUrl: f.subUrl ? api.pc.base() + f.subUrl : "",
            image: f.kind === "image", info: "omarchy-pc:/" + f.path }));
        });
        if (!(L.dirs || []).length && !(L.files || []).length && !L.error) out.push({ title: t("cine_pc_empty"), sub: "", badge: "[ ]", kind: "noop" });
      } else if (id === "stream") {
        var apps = api.apps();
        apps.forEach(function (a) { out.push({ title: a.name, sub: a.sub || "", badge: "[APP]", kind: "app", info: a.id, act: a.launch }); });
        if (!apps.length) out.push({ title: t("cine_none"), sub: "", badge: "[ ]", kind: "noop" });
      } else if (id === "live") {
        api.inputs().forEach(function (p) { out.push({ title: p.name, sub: p.sub || "", badge: "[TV]", kind: "input", act: p.launch }); });
      } else if (id === "iptv") {
        out.push({ title: t("cine_add_playlist"), sub: t("cine_playlist_hint"), badge: "[+M3U]", kind: "action", act: function () { prompt(t("cine_add_playlist"), "https://…/playlist.m3u8", addPlaylist); } });
        c.iptv.forEach(function (p, i) {
          out.push({ title: "▤ " + p.name, sub: host(p.url) + " · " + t("cine_remove_hint"), badge: "[M3U]", kind: "playlist", url: p.url, playlistIdx: i, act: function () { S.group = "ALL"; loadIptv(true); } });
        });
        if (c.iptv.length) {
          if (!S.iptv && !S.iptvLoading) loadIptv(false);
          var groups = groupsOf(S.iptv || []);
          out.push({ title: t("cine_group").replace("%s", S.group === "FAV" ? "★" : S.group) + (S.iptvLoading ? "  · " + t("cine_loading") : S.iptv ? "  · " + S.iptv.length + " ch" : ""),
            sub: S.iptvError || t("cine_group_hint"), badge: "[GRP]", kind: "action", act: function () { cycleGroup(groups); } });
          var last = c.lastChannel || "";
          (S.iptv || []).forEach(function (ch) {
            var fav = c.fav.indexOf(ch.url) >= 0;
            if (S.group === "FAV" && !fav) return;
            if (S.group !== "ALL" && S.group !== "FAV" && (ch.group || "—") !== S.group) return;
            out.push({ title: ch.name, sub: (fav ? "★ " : "") + (ch.group || t("cine_live")) + (ch.url === last ? " · ↺" : ""), badge: fav ? "[★]" : "[" + t("cine_live") + "]",
              kind: "stream", live: true, url: ch.url, chan: true });
          });
        }
      } else if (id === "demo") {
        DEMO.forEach(function (d) { out.push(progressItem({ title: d[0], sub: t("cine_demo_note"), badge: "[OPEN]", kind: "stream", url: DEMO_BASE + d[1] })); });
      } else if (id === "queue") {
        if (!c.queue.length) out.push({ title: t("cine_queue_empty"), sub: "", badge: "[ ]", kind: "noop" });
        c.queue.forEach(function (it, i) { out.push(progressItem({ title: it.title, sub: host(it.url), badge: "[#" + (i + 1) + "]", kind: "stream", url: it.url, live: !!it.live, queueIdx: i })); });
        if (c.queue.length) out.push({ title: t("cine_queue_clear") + " (" + c.queue.length + ")", sub: "", badge: "[CLR]", kind: "action", act: function () { c.queue = []; api.save(); refresh(); } });
      } else if (id === "tools") {
        out.push({ title: t("cine_embed"), sub: api.embed.get() ? "on" : "off", badge: "[YT]", kind: "action", act: function () { api.embed.set(!api.embed.get()); refresh(); } });
        out.push({ title: "🎲 " + t("cine_roulette"), sub: t("cine_roulette_hint"), badge: "[🎲]", kind: "action", act: roulette });
        out.push({ title: t("cine_saver_test"), sub: "10 s", badge: "[‖]", kind: "action", act: function () { api.saverPreview(); } });
        out.push({ title: t("cine_add_link"), sub: t("cine_playlist_hint"), badge: "[URL]", kind: "action", act: function () { prompt(t("cine_add_link"), "https://…/video.mp4", addLink); } });
      }
      return out;
    }
    function pcOpen(path) {
      S.pcPath = path; S.pcList = null; S.idx[S.src] = 0; S.off[S.src] = 0; refresh();
      api.pc.list(path).then(function (j) { if (S.pcPath === path) { S.pcList = j; refresh(); } },
        function (e) { if (S.pcPath === path) { S.pcList = { error: (e && e.message) || "error", dirs: [], files: [] }; refresh(); } });
    }
    function pcUp() {
      if (!S.pcPath) { S.pcPath = null; S.pcList = null; S.idx[S.src] = 0; refresh(); return; }
      pcOpen(S.pcPath.indexOf("/") >= 0 ? S.pcPath.slice(0, S.pcPath.lastIndexOf("/")) : "");
    }
    function groupsOf(list) { var g = {}, out = []; list.forEach(function (c) { var k = c.group || "—"; if (!g[k]) { g[k] = 1; out.push(k); } }); return out.sort(); }
    function cycleGroup(groups) { var all = ["ALL", "FAV"].concat(groups), i = all.indexOf(S.group); S.group = all[(i + 1) % all.length]; S.idx[S.src] = 0; S.off[S.src] = 0; refresh(); }
    function loadIptv(force) {
      var c = cine();
      if (!c.iptv.length || (S.iptvLoading && !force)) return;
      S.iptvLoading = true; S.iptvError = "";
      var merged = [], left = c.iptv.length;
      c.iptv.forEach(function (p) {
        api.fetchText(p.url).then(function (txt) { merged = merged.concat(parseM3U(txt)); }, function (e) { S.iptvError = host(p.url) + ": " + (e && e.message || "error"); })
          .then(function () { if (--left === 0) { S.iptv = merged; S.iptvLoading = false; if (SOURCES[S.src].id === "iptv") refresh(); } });
      });
      refresh();
    }
    function addPlaylist(u) {
      if (!validUrl(u)) { toast(t("cine_bad_url")); return; }
      cine().iptv.push({ name: host(u) || "Playlist", url: u }); api.save(); S.iptv = null; loadIptv(true);
    }
    function addLink(u) {
      if (!validUrl(u)) { toast(t("cine_bad_url")); return; }
      cine().queue.push({ title: decodeURIComponent((u.split("/").pop() || u).split("?")[0]) || u, url: u }); api.save(); toast(t("cine_queued")); refresh();
    }
    function refresh() {
      S.items = itemsFor(S.src);
      var n = S.items.length, i = Math.min(S.idx[S.src] || 0, Math.max(0, n - 1));
      S.idx[S.src] = i;
      render();
    }

    // ---- drawing: only the rows in view ----
    function rowHtml(it, sel) {
      return '<div class="r' + (sel ? " sel" : "") + '"><span class="b">' + esc(it.badge) + "</span> " + esc(it.title) +
        (it.prog ? '<span class="p">' + esc(it.prog) + "</span>" : "") + (it.sub ? ' <span class="s">· ' + esc(it.sub) + "</span>" : "") + "</div>";
    }
    function render() {
      q(".p0").className = "bx p0" + (S.pane === "src" ? " on" : "");
      q(".p1").className = "bx p1" + (S.pane === "list" ? " on" : "");
      q(".p0 .rows").innerHTML = SOURCES.map(function (s, i) {
        return '<div class="r' + (i === S.src ? " sel" : "") + '"><span class="b">[' + (i + 1) + "]</span> " + esc(s.name) + "</div><div style=\"height:.5em\"></div>";
      }).join("");
      var items = S.items, i = S.idx[S.src] || 0, off = S.off[S.src] || 0;
      if (i < off) off = i; if (i >= off + ROWS) off = i - ROWS + 1;
      S.off[S.src] = off;
      var html = off > 0 ? '<div class="more">▲ ' + off + "</div>" : '<div class="more"></div>';
      for (var k = off; k < Math.min(items.length, off + ROWS); k++) html += rowHtml(items[k], k === i);
      html += items.length > off + ROWS ? '<div class="more">▼ ' + (items.length - off - ROWS) + "</div>" : "";
      q(".p1 .rows").innerHTML = html;
      q(".p1 .lg").textContent = t("cine_library") + " · " + SOURCES[S.src].label + "  " + (items.length ? (i + 1) + "/" + items.length : "");
      var it = items[i];
      var d = "";
      if (it) {
        d += "<h1>" + esc(it.title) + "</h1>";
        d += '<div class="m">' + esc(it.badge) + " · " + esc(SOURCES[S.src].name) + "</div>";
        if (it.sub) d += '<div class="m">' + esc(it.sub) + "</div>";
        if (it.url) d += '<div class="m">' + esc(it.live ? t("cine_live") + " · " : "") + esc(host(it.url)) + "</div>";
        if (it.info) d += '<div class="m">' + esc(it.info) + "</div>";
        var r = it.url && !it.live ? resume.get(it.url) : null;
        if (r) d += '<div class="bar">' + bar(r.pos / r.dur, 16) + " " + fmtTime(r.pos) + " / " + fmtTime(r.dur) + "</div>" + '<div class="m">' + esc(t("cine_resume").replace("%s", fmtTime(r.pos))) + "</div>";
        d += '<div class="cli">' + esc(hintFor(it)) + "</div>";
      }
      q(".p2 .in").innerHTML = d;
      var now = new Date();
      q(".clk").textContent = (now.getHours() < 10 ? "0" : "") + now.getHours() + ":" + (now.getMinutes() < 10 ? "0" : "") + now.getMinutes();
    }
    function hintFor(it) {
      if (it.kind === "stream") return "OK " + t("cine_play") + (it.chan ? "\n" + t("cine_fav_hint") : "") + (it.queueIdx === undefined && !it.chan ? "\n" + t("cine_queue_hint") : it.queueIdx !== undefined ? "\n" + t("cine_remove_hint") : "");
      if (it.kind === "playlist") return "OK " + t("cine_reload") + "\n" + t("cine_remove_hint");
      if (it.kind === "youtube") return "OK " + t("cine_play") + " · YouTube";
      if (it.kind === "app" || it.kind === "input") return "OK " + t("cine_open");
      return "OK";
    }
    (function fitRows() {   // as many rows as the library pane really holds, on any TV
      var box = q(".p1"), probe = doc.createElement("div");
      probe.className = "r"; probe.textContent = "x"; q(".p1 .rows").appendChild(probe);
      var rh = probe.offsetHeight, h = box.clientHeight;
      probe.parentNode.removeChild(probe);
      if (rh > 0 && h > 0) ROWS = Math.max(6, Math.floor(h / rh) - 3);
    })();
    refresh();
    var clockT = setInterval(function () { if (!S.player) render(); }, 30000);

    // ---- the prompt (the TV's own keyboard opens on the field) ----
    function prompt(title, hint, done) {
      var p = q(".pr"), inp = p.querySelector("input");
      p.querySelector(".pt").textContent = title; p.querySelector(".ph").textContent = "OK · " + t("cine_back");
      inp.value = ""; inp.placeholder = hint; p.style.display = "block";
      S.prompt = done;
      setTimeout(function () { try { inp.focus(); } catch (e) {} }, 50);
    }
    function closePrompt(ok) {
      var p = q(".pr"), inp = p.querySelector("input"), done = S.prompt;
      p.style.display = "none"; S.prompt = null; try { inp.blur(); } catch (e) {}
      if (ok && done) done(inp.value.replace(/^\s+|\s+$/g, ""));
    }

    // ---- the player ----
    function play(list, idx) {
      var it = list[idx];
      if (!it || !it.url) return;
      if (it.chan) { cine().lastChannel = it.url; api.save(); }
      var pl = q(".pl"), v = pl.querySelector("video");
      S.player = { list: list, idx: idx, it: it, osdT: 0, saveT: 0, mode: S.lastMode || "normal", sleepAt: S.sleepAt || 0 };
      pl.style.display = "block";
      var img = pl.querySelector("img.ph");
      if (!img) { img = doc.createElement("img"); img.className = "ph"; img.style.cssText = "position:absolute;left:0;top:0;width:100%;height:100%;object-fit:contain;background:#000;display:none"; pl.insertBefore(img, pl.firstChild.nextSibling); }
      while (v.firstChild) v.removeChild(v.firstChild);
      if (it.image) {   // a photo from the PC: shown full screen, ▲ ▼ go through the folder
        v.style.display = "none"; img.style.display = "block"; img.src = it.url;
        api.onPlay(true); osd(); return;
      }
      img.style.display = "none"; img.removeAttribute("src"); v.style.display = "block";
      if (it.subUrl) { var tr = doc.createElement("track"); tr.kind = "subtitles"; tr.src = it.subUrl; tr.default = true; tr.srclang = "xx"; tr.label = "CC"; v.appendChild(tr); v.crossOrigin = "anonymous"; }
      v.src = it.url;
      var r = !it.live ? resume.get(it.url) : null;
      v.onloadedmetadata = function () { if (r && r.pos && v.duration && r.pos < v.duration - 5) { try { v.currentTime = r.pos; } catch (e) {} } };
      v.onerror = function () { osd(t("cine_no_play")); };
      v.onended = function () { save(); next(1, true); };
      try { var pr = v.play(); if (pr && pr.catch) pr.catch(function () {}); } catch (e) {}
      api.onPlay(true);
      setMode(S.player.mode);
      osd();
      S.player.saveT = setInterval(save, 10000);
    }
    function save() {
      var P = S.player; if (!P) return;
      var v = q(".pl video");
      if (!P.it.live && !P.it.image && v.duration && isFinite(v.duration)) resume.put(P.it.url, v.currentTime, v.duration, P.it.title);
    }
    function osd(msg) {
      var P = S.player; if (!P) return;
      var v = q(".pl video"), o = q(".osd");
      q(".osd .tt").textContent = (P.it.live ? "● " + t("cine_live") + "  " : "▶ ") + P.it.title;
      var dur = v.duration && isFinite(v.duration) ? v.duration : 0;
      q(".osd .tl").textContent = msg ? msg : P.it.live ? (P.list.length > 1 ? (P.idx + 1) + "/" + P.list.length : "") :
        (v.paused ? "‖ " : "▶ ") + fmtTime(v.currentTime) + " [" + bar(dur ? v.currentTime / dur : 0, 40) + "] " + fmtTime(dur);
      var extra = (P.mode !== "normal" ? "  ·  " + (P.mode === "vhs" ? "VHS" : "ASCII") : "") + (P.sleepAt ? "  ·  ⏾ " + fmtTime((P.sleepAt - Date.now()) / 1000) : "");
      q(".osd .ky").textContent = t("cine_player_keys") + extra;
      o.style.opacity = "1";
      clearTimeout(P.osdT);
      P.osdT = setTimeout(function () { if (S.player && !msg) o.style.opacity = "0"; }, 4000);
    }
    var osdTick = setInterval(function () { if (S.player && q(".osd").style.opacity !== "0") osd(); }, 1000);
    // ---- VHS tape mode and ASCII mode ----
    var cv = doc.createElement("canvas"), cx = null, ascT = 0, ascSt = {};
    function setMode(m) {
      var P = S.player; if (!P) return;
      P.mode = m; S.lastMode = m;
      q(".vhs").style.display = m === "vhs" ? "block" : "none";
      q(".asc").style.display = m === "ascii" ? "block" : "none";
      clearInterval(ascT);
      if (m === "ascii") { ascSt = {}; ascT = setInterval(asciiFrame, 90); }
    }
    function asciiFrame() {
      var P = S.player, v = q(".pl video"), pre = q(".asc");
      if (!P || P.mode !== "ascii" || !v.videoWidth) return;
      var cols = 120, rows = Math.max(20, Math.round(cols * v.videoHeight / v.videoWidth * 0.5));
      if (cv.width !== cols) { cv.width = cols; cv.height = rows; cx = cv.getContext && cv.getContext("2d"); pre.style.fontSize = Math.min(100 / cols / 0.6, 100 / rows) + "vw"; }
      if (!cx) return;
      var data;
      try { cx.drawImage(v, 0, 0, cols, rows); data = cx.getImageData(0, 0, cols, rows).data; }
      catch (e) { asciiBlocked(); return; }
      pre.textContent = asciiArt(data, cols, rows, ascSt);
    }
    function asciiBlocked() {   // the stream's server forbids reading its pixels: try once with CORS, else stay normal
      var P = S.player, v = q(".pl video");
      if (!P) return;
      if (!P.triedCors) {
        P.triedCors = true;
        var at = v.currentTime, url = P.it.url;
        clearInterval(ascT);
        v.onerror = function () { v.onerror = null; v.removeAttribute("crossorigin"); v.src = url; v.onloadedmetadata = function () { try { v.currentTime = at; } catch (e) {} }; try { v.play(); } catch (e) {} setMode("normal"); toast(t("cine_ascii_blocked")); };
        v.crossOrigin = "anonymous"; v.src = url;
        v.onloadedmetadata = function () { try { v.currentTime = at; } catch (e) {} };
        try { var pr = v.play(); if (pr && pr.catch) pr.catch(function () {}); } catch (e) {}
        ascT = setInterval(asciiFrame, 90);
        return;
      }
      setMode("normal"); toast(t("cine_ascii_blocked"));
    }
    var vcrT = setInterval(function () {
      var P = S.player; if (!P) return;
      if (P.mode === "vhs") {
        var v = q(".pl video");
        q(".vcr").textContent = (v.paused ? "PAUSE ‖" : "PLAY ▶");
        q(".vcr2").textContent = "SP  " + fmtTime(v.currentTime);
      }
      if (P.sleepAt && Date.now() >= P.sleepAt) { S.sleepAt = 0; stop(); toast(t("cine_sleep_done")); }
    }, 500);
    var SLEEP = [0, 15, 30, 60, 90];
    function cycleSleep() {
      var P = S.player, cur = P.sleepAt ? Math.round((P.sleepAt - Date.now()) / 60000) : 0, i = 0;
      for (var k = 0; k < SLEEP.length; k++) if (SLEEP[k] >= cur) { i = k; break; }
      var n = SLEEP[(i + 1) % SLEEP.length];
      P.sleepAt = S.sleepAt = n ? Date.now() + n * 60000 : 0;
      toast(n ? t("cine_sleep").replace("%s", n + " min") : t("cine_sleep_off"));
    }
    // ---- a surprise channel: static, then a random channel from your playlists (or an open movie) ----
    function roulette() {
      var pool = (S.iptv || []).map(function (c) { return { title: c.name, sub: c.group, badge: "[🎲]", kind: "stream", live: true, url: c.url, chan: true }; });
      if (!pool.length) pool = itemsFor(5).filter(function (x) { return x.url; });
      if (!pool.length) return;
      var pick = Math.floor(Math.random() * pool.length), z = q(".zap"), noise = "  ░░▒▓", n = 0;
      z.style.display = "block";
      z.querySelector("b").textContent = "🎲 CH " + (pick + 1 < 10 ? "0" : "") + (pick + 1);
      var zt = setInterval(function () {
        var s2 = ""; for (var r = 0; r < 40; r++) { for (var c2 = 0; c2 < 110; c2++) s2 += noise.charAt(Math.floor(Math.random() * noise.length)); s2 += "\n"; }
        z.firstChild && z.firstChild.nodeType === 3 ? (z.firstChild.nodeValue = s2) : z.insertBefore(doc.createTextNode(s2), z.firstChild);
        if (++n > 6) { clearInterval(zt); z.style.display = "none"; if (S.player) stop(true); play(pool, pick); }
      }, 70);
    }
    function next(d, auto) {
      var P = S.player; if (!P) return;
      for (var j = P.idx + d; j >= 0 && j < P.list.length; j += d) if (P.list[j].url && P.list[j].kind === "stream" && !!P.list[j].image === !!P.it.image) { stop(true); play(P.list, j); return; }
      if (auto) stop();
    }
    function stop(keep) {
      var P = S.player; if (!P) return;
      save(); clearInterval(P.saveT); clearTimeout(P.osdT); clearInterval(ascT);
      q(".asc").style.display = "none"; q(".vhs").style.display = "none";
      var v = q(".pl video"), im = q(".pl img.ph");
      if (im) { im.style.display = "none"; im.removeAttribute("src"); }
      try { v.pause(); v.removeAttribute("src"); v.removeAttribute("crossorigin"); v.load(); } catch (e) {}
      S.player = null;
      if (!keep) { q(".pl").style.display = "none"; api.onPlay(false); refresh(); }
    }
    function playerKey(k) {
      var v = q(".pl video"), P = S.player;
      if (k === "back") { stop(); return; }
      if (k === "ok") { try { if (v.paused) v.play(); else v.pause(); } catch (e) {} }
      else if ((k === "left" || k === "right") && !P.it.live) { try { v.currentTime = Math.max(0, v.currentTime + (k === "left" ? -10 : 10)); } catch (e) {} }
      else if (k === "up") next(-1);
      else if (k === "down") next(1);
      else if (k === "blue") { var m = P.mode === "normal" ? "vhs" : P.mode === "vhs" ? "ascii" : "normal"; setMode(m); toast(m === "normal" ? t("cine_mode_normal") : m === "vhs" ? "VHS · CRT" : "ASCII"); }
      else if (k === "yellow") cycleSleep();
      else if (k === "green" && P.it.chan) { roulette(); return; }
      osd();
    }

    // ---- keys ----
    function act(it) {
      if (!it) return;
      if (it.kind === "stream") { play(S.items, S.items.indexOf(it)); return; }
      if (it.act) it.act();
    }
    function key(k) {
      if (S.prompt) {   // letters go to the field; OK and Back close it
        if (k === "ok") { closePrompt(true); return true; }
        if (k === "back") { closePrompt(false); return true; }
        return false;
      }
      if (S.player) { playerKey(k); return true; }
      var items = S.items, i = S.idx[S.src] || 0, it = items[i], c = cine();
      if (/^[1-8]$/.test(k)) { S.src = +k - 1; S.pane = "list"; refresh(); return true; }
      if (k === "back") {
        if (S.pane === "list" && SOURCES[S.src].id === "pc" && S.pcPath !== null && S.pcPath !== undefined) { pcUp(); return true; }
        if (S.pane === "list") { S.pane = "src"; render(); } else close();
        return true;
      }
      if (S.pane === "src") {
        if (k === "up") { S.src = (S.src + SOURCES.length - 1) % SOURCES.length; refresh(); }
        else if (k === "down") { S.src = (S.src + 1) % SOURCES.length; refresh(); }
        else if (k === "right" || k === "ok") { S.pane = "list"; render(); }
        return true;
      }
      if (k === "up") { S.idx[S.src] = Math.max(0, i - 1); render(); }
      else if (k === "down") { S.idx[S.src] = Math.min(items.length - 1, i + 1); render(); }
      else if (k === "pageup") { S.idx[S.src] = Math.max(0, i - ROWS); render(); }
      else if (k === "pagedown") { S.idx[S.src] = Math.min(items.length - 1, i + ROWS); render(); }
      else if (k === "left") { S.pane = "src"; render(); }
      else if (k === "ok") act(it);
      else if (k === "red" && it) {   // red: add to Up next / remove from it
        if (it.queueIdx !== undefined) { c.queue.splice(it.queueIdx, 1); api.save(); refresh(); }
        else if (it.playlistIdx !== undefined) { c.iptv.splice(it.playlistIdx, 1); api.save(); S.iptv = null; refresh(); }
        else if (it.kind === "stream") { c.queue.push({ title: it.title, url: it.url, live: !!it.live }); if (c.queue.length > 100) c.queue.shift(); api.save(); toast(t("cine_queued")); }
      } else if (k === "green" && it && it.chan) {   // green: favourite channel
        var at = c.fav.indexOf(it.url);
        if (at >= 0) c.fav.splice(at, 1); else c.fav.push(it.url);
        api.save(); toast(t(at >= 0 ? "cine_fav_removed" : "cine_fav_added")); refresh();
      } else if (k === "yellow" && SOURCES[S.src].id === "iptv") cycleGroup(groupsOf(S.iptv || []));
      else if (k === "blue") roulette();
      return true;
    }
    function close() { destroy(); if (api.onExit) api.onExit(); }
    function destroy() {
      stop(true);
      clearInterval(clockT); clearInterval(osdTick); clearInterval(vcrT); clearInterval(ascT); clearTimeout(S.toastT);
      if (w.parentNode) w.parentNode.removeChild(w);
      api.onPlay(false);
    }
    return { key: key, destroy: destroy, refresh: refresh, _state: function () { return S; } };
  }

  /** A key event to a Cinemarchy key name (webOS / Tizen remotes and a keyboard). */
  function keyName(e) {
    var k = e.key, c = e.keyCode;
    if (k === "ArrowUp" || c === 38) return "up";
    if (k === "ArrowDown" || c === 40) return "down";
    if (k === "ArrowLeft" || c === 37) return "left";
    if (k === "ArrowRight" || c === 39) return "right";
    if (k === "Enter" || c === 13) return "ok";
    if (k === "Escape" || k === "GoBack" || c === 27 || c === 461 || c === 10009) return "back";
    if (c === 8 && !(e.target && /INPUT|TEXTAREA/.test(e.target.tagName))) return "back";
    if (c === 403 || k === "q") return "red";
    if (c === 404 || k === "f") return "green";
    if (c === 405 || k === "g") return "yellow";
    if (c === 406 || k === "b") return "blue";
    if (c === 33 || c === 427) return "pageup";
    if (c === 34 || c === 428) return "pagedown";
    if (c >= 49 && c <= 56 && !(e.target && /INPUT/.test(e.target.tagName))) return String(c - 48);
    return "";
  }

  root.OmarchyCinemarchy = { open: open, keyName: keyName, parseM3U: parseM3U, bar: bar, fmtTime: fmtTime, asciiArt: asciiArt, Resume: Resume, DEMO: DEMO };
})(typeof window !== "undefined" ? window : this);
