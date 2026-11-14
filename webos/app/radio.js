/* Radiomarchy on LG webOS and Samsung Tizen (v2.20): Omarchy Radio on the TV, like the Android app.
 * The community's songs from radio.omarchy.org (every one made by the person named on it and sent in as a pull
 * request; streamed from the site, nothing copied into the app) and Omarchy Stories, the community podcast, in
 * Omarchy's TUI style over fractals (fractals.js) that dance to the music. Explicit songs are in the list with an
 * E, as their makers labelled them; a setting hides them. Shuffle is on by default.
 * The player (engine) lives as long as the app: the music goes on when Radiomarchy is closed, and the Home bar
 * can show what is playing. Songs go through a WebAudio analyser (radio.omarchy.org allows it: CORS *); episodes
 * play through a plain audio element (their host may not), and the fractals then breathe on their own.
 * ES5. Host:
 *   var eng = OmarchyRadio.engine({ store, fetchText(url) -> Promise<text> });
 *   var ui = OmarchyRadio.open(el, { engine, tr, lang, theme, logoHtml, version, demo, saver, onExit })
 *     -> { key(name) -> handled, destroy() }   (key names as OmarchyCinemarchy.keyName). */
(function (root) {
  "use strict";
  var SITE = "https://radio.omarchy.org", PLAYLIST = SITE + "/tracks/playlist.json", FEED = SITE + "/stories/feed.rss";
  var FEED_HOST = "https://api.riverside.com/hosting/1i59HjrN.rss", SHOW = "Omarchy Stories", PREFS = "omarchytv.radio.v1";

  // ---- the lists (the same rules as RadioCatalog.java) ----------------------------------------------

  function slug(s) {
    var n = String(s == null ? "" : s);
    if (n.normalize) n = n.normalize("NFD").replace(/[\u0300-\u036f]/g, "");
    return n.toLowerCase().replace(/['\u2019]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  }
  function uniqueSlug(title, artist, seen) {
    var base = slug(title) || "track", s = base, n = 2;
    if (seen[s]) s = base + "-" + slug(artist);
    while (seen[s]) s = base + "-" + n++;
    seen[s] = true;
    return s;
  }
  function enc(s) { return encodeURIComponent(s); }
  function parseSongs(text) {
    var out = [], seen = Object.create ? Object.create(null) : {}, j;
    try { j = JSON.parse(text); } catch (e) { return out; }
    var a = j && j.tracks;
    for (var i = 0; a && i < a.length && out.length < 5000; i++) {
      var t = a[i];
      if (!t || !t.title || typeof t.title !== "string") continue;
      var title = t.title.replace(/^\s+|\s+$/g, ""), file = String(t.file || "").replace(/^\s+|\s+$/g, ""), url = String(t.url || "").replace(/^\s+|\s+$/g, "");
      if (!title) continue;
      var artist = String(t.artist || "").replace(/^\s+|\s+$/g, "");
      var page = SITE + "/playlist/" + uniqueSlug(title, artist, seen);
      if (!url) {
        if (!file || file.indexOf("/") >= 0 || file.indexOf("..") >= 0) continue;
        url = SITE + "/tracks/" + enc(file);
      }
      if (url.indexOf("https://") !== 0) continue;
      var lyrics = t.lyrics === false ? "" : typeof t.lyrics === "string" && t.lyrics && t.lyrics.indexOf("/") < 0 ? SITE + "/tracks/lyrics/" + enc(t.lyrics)
        : file ? SITE + "/tracks/lyrics/" + enc(file.replace(/\.[^.]+$/, "") + ".lrc") : "";
      out.push({ kind: "song", title: title, artist: artist, url: url, album: String(t.album || ""), explicit: t.explicit === true, lyrics: lyrics, page: page, dur: 0 });
    }
    return out;
  }
  function unescapeXml(s) {
    return String(s || "").replace(/&#(x?)([0-9a-f]+);/gi, function (m, x, n) {
      var cp = parseInt(n, x ? 16 : 10);
      try { return String.fromCodePoint ? String.fromCodePoint(cp) : String.fromCharCode(cp); } catch (e) { return "?"; }
    }).replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&");
  }
  function clean(s) {
    s = String(s || "");
    var cdata = s.indexOf("<![CDATA[") >= 0;
    s = s.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1");
    if (!cdata) s = unescapeXml(s);
    s = s.replace(/<br\s*\/?>|<\/p>/gi, "\n").replace(/<[^>]+>/g, "");
    return unescapeXml(s).replace(/[ \t\r\f\v]+/g, " ").replace(/ *\n[ \n]*/g, "\n").replace(/^\s+|\s+$/g, "");
  }
  function tag(xml, name) {
    var m = new RegExp("<" + name.replace(":", "\\:") + "\\b[^>]*>([\\s\\S]*?)</" + name.replace(":", "\\:") + ">").exec(xml);
    return m ? m[1] : "";
  }
  function attr(xml, name, a) {
    var m = new RegExp("<" + name.replace(":", "\\:") + "\\b([^>]*)>").exec(xml);
    if (!m) return "";
    var v = new RegExp("\\b" + a + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").exec(m[1]);
    return v ? (v[2] != null ? v[2] : v[3]) : "";
  }
  function duration(d) {
    if (!d) return 0;
    var p = String(d).replace(/^\s+|\s+$/g, "").split(":"), s = 0;
    for (var i = 0; i < p.length; i++) { var n = parseInt(p[i], 10); if (isNaN(n)) return 0; s = s * 60 + n; }
    return s;
  }
  function parseFeed(xml) {
    var out = [];
    if (!xml) return out;
    var head = xml.split("<item")[0], showImg = attr(head, "itunes:image", "href"), re = /<item\b[^>]*>([\s\S]*?)<\/item>/g, m;
    while ((m = re.exec(xml)) && out.length < 500) {
      var it = m[1], url = unescapeXml(attr(it, "enclosure", "url"));
      if (url.indexOf("https://") !== 0) continue;
      var title = clean(tag(it, "title")) || clean(tag(it, "itunes:title"));
      if (!title) continue;
      var who = clean(tag(it, "dc:creator")) || clean(tag(it, "itunes:author")), about = clean(tag(it, "itunes:summary")) || clean(tag(it, "description"));
      var ex = clean(tag(it, "itunes:explicit")).toLowerCase();
      out.push({ kind: "episode", title: title, artist: who ? SHOW + " · " + who : SHOW, url: url, album: "", explicit: ex === "yes" || ex === "true", lyrics: "",
        page: clean(tag(it, "link")), dur: duration(clean(tag(it, "itunes:duration"))), date: clean(tag(it, "pubDate")),
        image: attr(it, "itunes:image", "href") || showImg, about: about.length > 1200 ? about.slice(0, 1200) + "…" : about });
    }
    return out;
  }
  function parseLyrics(text) {
    var timed = [], plain = [], lines = String(text || "").replace(/\r/g, "").split("\n"), stamp = /^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/;
    for (var i = 0; i < lines.length; i++) {
      var raw = lines[i], at = [], m;
      while ((m = stamp.exec(raw))) {
        var ms = (+m[1]) * 60000 + (+m[2]) * 1000;
        if (m[3]) ms += (+m[3]) * (m[3].length === 1 ? 100 : m[3].length === 2 ? 10 : 1);
        at.push(ms);
        raw = raw.slice(m[0].length);
      }
      var words = raw.replace(/^\s+|\s+$/g, "");
      if (at.length) for (var k = 0; k < at.length; k++) timed.push({ ms: at[k], text: words });
      else if (!/^\s*\[[a-zA-Z]+:.*\]\s*$/.test(lines[i])) plain.push({ ms: -1, text: lines[i].replace(/^\s+|\s+$/g, "") });
    }
    if (timed.length) return timed.sort(function (a, b) { return a.ms - b.ms; });
    while (plain.length && !plain[0].text) plain.shift();
    while (plain.length && !plain[plain.length - 1].text) plain.pop();
    return plain;
  }
  function lineAt(sheet, ms) {
    var at = -1;
    for (var i = 0; i < sheet.length; i++) { if (sheet[i].ms < 0) return -1; if (sheet[i].ms <= ms) at = i; else break; }
    return at;
  }
  function visible(list, showExplicit) { return showExplicit ? list : list.filter(function (t) { return !t.explicit; }); }
  function shuffled(n, first, rnd) {
    var o = [], i;
    for (i = 0; i < n; i++) if (i !== first) o.push(i);
    for (i = o.length - 1; i > 0; i--) { var j = Math.floor((rnd || Math.random)() * (i + 1)), x = o[i]; o[i] = o[j]; o[j] = x; }
    o.unshift(first);
    return o;
  }

  // ---- the music analysis (the same idea as AudioBands.java, on WebAudio's analyser) ------------------

  function Bands() {
    var pk = [1e-4, 1e-4, 1e-4, 1e-4], e = [0, 0, 0, 0], lastBeat = -1e9, beats = 0, prevLow = null, fluxAvg = 0, fluxPeak = 1e-4;
    return function (freq, sampleRate, now, dt, out) {
      var n = freq.length, hz = sampleRate / 2 / n, b = 0, m = 0, h = 0, all = 0, nb = 0, flux = 0, first = !prevLow, i;
      if (!prevLow) prevLow = [];
      for (i = 1; i < n; i++) {
        var f = i * hz, v = freq[i] / 255;
        v = v * v;
        if (f < 160) { b += v; flux += Math.max(0, v - (prevLow[nb] || 0)); prevLow[nb] = v; nb++; } else if (f < 2000) m += v; else if (f < 12000) h += v * 1.6;
        all += v;
      }
      // beat: a bass onset (spectral flux under 160 Hz) well above its recent average, at most 4 a second, and only
      // when the bass stands out of the spectrum (hiss is flat)
      fluxPeak = Math.max(fluxPeak * (1 - dt / 8), flux);
      if (!first && flux > fluxAvg * 3 + 1e-3 && flux > fluxPeak * 0.2 && now - lastBeat > 250 && b / Math.max(1, nb) > 2 * all / (n - 1)) { lastBeat = now; beats++; }
      fluxAvg += (flux - fluxAvg) * Math.min(1, dt / 0.5);
      var raw = [b, m, h, all];
      for (i = 0; i < 4; i++) {
        pk[i] = Math.max(pk[i] * (1 - dt / 8), raw[i]);
        var lv = Math.sqrt(Math.min(1, raw[i] / Math.max(1e-4, pk[i])));
        e[i] += (lv - e[i]) * Math.min(1, dt / (lv > e[i] ? 0.015 : 0.18));
      }
      out.bass = e[0]; out.mid = e[1]; out.high = e[2]; out.level = e[3];
      out.beatAgeMs = now - lastBeat; out.beats = beats;
      return out;
    };
  }

  // ---- the engine: one per app, playing on while the UI is closed --------------------------------------

  function engine(opts) {
    var store = opts.store || null, doc = opts.document || root.document;
    var P = { shuffle: true, repeat: "all", explicit: true, palette: 0, scene: -1, quality: 0, autoDemo: true, pos: {} };
    try { var saved = store && JSON.parse(store.getItem(PREFS) || "null"); if (saved) for (var k in saved) if (Object.prototype.hasOwnProperty.call(saved, k)) P[k] = saved[k]; } catch (e) { /* defaults */ }
    function save() { try { if (store) store.setItem(PREFS, JSON.stringify(P)); } catch (e) { /* full or blocked */ } }
    var E = { songs: [], episodes: [], loading: true, offline: false, queue: [], order: [], at: -1, error: "", listeners: [], prefs: P, save: save, sheets: {} };
    var song = doc.createElement("audio"), plain = doc.createElement("audio");
    song.crossOrigin = "anonymous";
    song.preload = plain.preload = "auto";
    var ctx = null, an = null, freq = null, bands = Bands(), lastAn = 0;
    function audio() { var t = cur(); return t && t.kind === "episode" ? plain : song; }
    function emit() { for (var i = 0; i < E.listeners.length; i++) try { E.listeners[i](); } catch (e) { /* a closed UI */ } }
    function analyser() {
      if (an || !(root.AudioContext || root.webkitAudioContext)) return;
      try {
        ctx = new (root.AudioContext || root.webkitAudioContext)();
        var src = ctx.createMediaElementSource(song);
        an = ctx.createAnalyser();
        an.fftSize = 2048;
        an.smoothingTimeConstant = 0.2;
        src.connect(an);
        an.connect(ctx.destination);
        freq = new Uint8Array(an.frequencyBinCount);
      } catch (e) { an = null; }
    }
    function cur() { return E.at >= 0 && E.at < E.order.length ? E.queue[E.order[E.at]] : null; }
    function start() {
      var t = cur();
      if (!t) return;
      E.error = "";
      var a = audio(), other = a === song ? plain : song;
      other.pause();
      if (a === song) analyser();
      if (ctx && ctx.resume) try { ctx.resume(); } catch (e) { /* not yet allowed */ }
      a.src = t.url;
      var from = t.kind === "episode" ? Math.max(0, (P.pos[t.url] || 0) - 3) : 0;
      if (from > 0) { var seek = function () { a.removeEventListener("loadedmetadata", seek); try { a.currentTime = from; } catch (e) { /* not seekable */ } }; a.addEventListener("loadedmetadata", seek); }
      var pr = a.play();
      if (pr && pr.catch) pr.catch(function (e) { E.error = String(e && e.name || "error"); emit(); });
      emit();
    }
    var errorsInARow = 0;
    function onEnded() {
      var t = cur();
      if (t && t.kind === "episode") { delete P.pos[t.url]; save(); }
      if (P.repeat === "one") start(); else E.next(false);
    }
    function onError() {
      E.error = "media error";
      emit();
      if (++errorsInARow < Math.max(2, E.order.length)) setTimeout(function () { E.next(false); }, 1500); else errorsInARow = 0;
    }
    var lastSave = 0;
    function onTime() {
      var t = cur(), a = audio(), now = Date.now();
      errorsInARow = 0;
      if (t && t.kind === "episode" && now - lastSave > 15000 && a.currentTime > 10) { lastSave = now; P.pos[t.url] = Math.floor(a.currentTime); save(); }
    }
    for (var x = 0, els = [song, plain]; x < 2; x++) {
      els[x].addEventListener("ended", onEnded);
      els[x].addEventListener("error", onError);
      els[x].addEventListener("timeupdate", onTime);
      els[x].addEventListener("play", emit);
      els[x].addEventListener("pause", emit);
      els[x].addEventListener("waiting", emit);
      els[x].addEventListener("playing", emit);
    }

    E.on = function (fn) { E.listeners.push(fn); return function () { var i = E.listeners.indexOf(fn); if (i >= 0) E.listeners.splice(i, 1); }; };
    E.current = cur;
    E.playing = function () { var a = audio(); return !!cur() && !a.paused && !a.ended; };
    E.buffering = function () { var a = audio(); return !!cur() && a.readyState < 3 && !a.paused; };
    E.position = function () { return cur() ? audio().currentTime || 0 : 0; };
    E.duration = function () { var a = audio(), d = a.duration; return isFinite(d) && d > 0 ? d : (cur() && cur().dur) || 0; };
    E.play = function (list, index) {
      if (!list || !list.length) return;
      E.queue = list.slice();
      index = Math.max(0, Math.min(index | 0, list.length - 1));
      E.order = P.shuffle ? shuffled(list.length, index) : list.map(function (_, i) { return i; }).slice(index).concat(list.map(function (_, i) { return i; }).slice(0, index));
      E.at = 0;
      start();
    };
    E.next = function (user) {
      if (!E.order.length) return false;
      if (user === -1) {   // previous: back to the start first
        if (E.position() > 4) { E.seek(0); return true; }
        E.at = (E.at - 1 + E.order.length) % E.order.length;
        start();
        return true;
      }
      if (E.at + 1 >= E.order.length && P.repeat === "off" && !user) { audio().pause(); emit(); return false; }
      E.at = (E.at + 1) % E.order.length;
      start();
      return true;
    };
    E.prev = function () { return E.next(-1); };
    E.toggle = function () {
      var a = audio();
      if (!cur()) return;
      if (ctx && ctx.resume) try { ctx.resume(); } catch (e) { /* later */ }
      if (a.paused) { var pr = a.play(); if (pr && pr.catch) pr.catch(function () { /* the TV said no */ }); } else a.pause();
      emit();
    };
    E.pause = function () { audio().pause(); };
    /** Wake the audio graph without starting a paused song (back on Home after YouTube took the sound). */
    E.claimSession = function () {
      song.muted = false; plain.muted = false;
      try { song.volume = 1; plain.volume = 1; } catch (e) { /* some TVs freeze volume */ }
      if (cur() && audio() === song) analyser();
      if (ctx && ctx.resume) try { ctx.resume(); } catch (e) { /* not yet allowed */ }
    };
    /**
     * Keep the song audible: the &lt;audio&gt; can report playing while an AudioContext left suspended
     * (idle/screensaver) is silent, because the analyser reroutes the element through it.
     */
    E.ensureAudible = function () {
      E.claimSession();
      var a = audio();
      if (cur() && a.paused) {
        var pr = a.play();
        if (pr && pr.catch) pr.catch(function () { /* the TV said no */ });
      }
    };
    E.seek = function (s) { var a = audio(), d = E.duration(); try { a.currentTime = Math.max(0, d ? Math.min(s, d - 0.5) : s); } catch (e) { /* not seekable */ } emit(); };
    E.seekBy = function (s) { E.seek(E.position() + s); };
    E.stop = function () { song.pause(); plain.pause(); song.removeAttribute("src"); plain.removeAttribute("src"); E.queue = []; E.order = []; E.at = -1; emit(); };
    E.setShuffle = function (on) {
      P.shuffle = !!on;
      save();
      if (E.order.length) { var c = E.order[E.at]; E.order = P.shuffle ? shuffled(E.queue.length, c) : E.queue.map(function (_, i) { return i; }); E.at = P.shuffle ? 0 : c; }
      emit();
    };
    E.cycleRepeat = function () { P.repeat = P.repeat === "all" ? "one" : P.repeat === "one" ? "off" : "all"; save(); emit(); };
    E.upNext = function (max) {
      var out = [];
      for (var i = 1; i <= max && i < E.order.length; i++) {
        var k = E.at + i;
        if (k >= E.order.length) { if (P.repeat === "off") break; k %= E.order.length; }
        out.push(E.queue[E.order[k]]);
      }
      return out;
    };
    /** The music as it is heard now, for the fractals; false when only make-believe (no analyser, paused). */
    var cachedOut = { bass: 0, mid: 0, high: 0, level: 0, beatAgeMs: 1e9, beats: 0 }, cachedBars = null, lastBarsAt = 0;
    function sampleAnalyser(now) {
      if (!an) return false;
      if (lastAn && now - lastAn < 1000 / 24) return true;
      an.getByteFrequencyData(freq);
      var dt = lastAn ? Math.min(0.1, (now - lastAn) / 1000) : 0.016;
      lastAn = now;
      bands(freq, ctx.sampleRate, now, dt, cachedOut);
      cachedBars = null;
      return true;
    }
    E.bands = function (out) {
      var t = cur(), now = Date.now();
      if (!an || !t || t.kind === "episode" || song.paused) return false;
      if (!sampleAnalyser(now)) return false;
      if (out) { out.bass = cachedOut.bass; out.mid = cachedOut.mid; out.high = cachedOut.high; out.level = cachedOut.level; out.beatAgeMs = cachedOut.beatAgeMs; out.beats = cachedOut.beats; }
      return true;
    };
    /** 16 spectrum bars (0..1) for the panel; null without the analyser. */
    E.bars = function () {
      var now = Date.now();
      if (!an || song.paused) return null;
      sampleAnalyser(now);
      if (cachedBars && now - lastBarsAt < 1000 / 24) return cachedBars;
      var out = [], n = freq.length, hz = ctx.sampleRate / 2 / n;
      for (var b = 0; b < 16; b++) {
        var f0 = 40 * Math.pow(400, b / 16), f1 = 40 * Math.pow(400, (b + 1) / 16), m = 0;
        for (var i = Math.max(1, Math.floor(f0 / hz)); i <= Math.ceil(f1 / hz) && i < n; i++) m = Math.max(m, freq[i]);
        out.push(m / 255);
      }
      cachedBars = out;
      lastBarsAt = now;
      return out;
    };
    E.load = function (done) {
      var fetchText = opts.fetchText || function (u) { return root.fetch(u).then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.text(); }); };
      try {
        var cs = store && store.getItem(PREFS + ".songs"), ce = store && store.getItem(PREFS + ".stories");
        if (cs && !E.songs.length) E.songs = parseSongs(cs);
        if (ce && !E.episodes.length) E.episodes = parseFeed(ce);
      } catch (e) { /* no cache */ }
      E.loading = !E.songs.length;
      emit();
      fetchText(PLAYLIST).then(function (txt) {
        var s = parseSongs(txt);
        if (s.length) { E.songs = s; E.offline = false; try { store && store.setItem(PREFS + ".songs", txt); } catch (e) { /* full */ } }
      }).catch(function () { E.offline = true; }).then(function () {
        E.loading = false;
        emit();
        return fetchText(FEED).catch(function () { return fetchText(FEED_HOST); });
      }).then(function (xml) {
        var e = parseFeed(xml);
        if (e.length) { E.episodes = e; try { store && store.setItem(PREFS + ".stories", xml); } catch (er) { /* full */ } }
      }).catch(function () { /* the podcast is optional */ }).then(function () { emit(); if (done) done(); });
    };
    E.lyrics = function (t, then) {
      if (!t || !t.lyrics) return then([]);
      if (E.sheets[t.lyrics]) return then(E.sheets[t.lyrics]);
      root.fetch(t.lyrics).then(function (r) { return r.ok ? r.text() : ""; }).catch(function () { return ""; }).then(function (txt) {
        E.sheets[t.lyrics] = parseLyrics(txt);
        then(E.sheets[t.lyrics]);
      });
    };
    return E;
  }

  // ---- the UI --------------------------------------------------------------------------------------

  var T = {
    en: { songs: "Songs", queue: "Up next", demo: "Demo mode", settings: "Settings", explicit: "Explicit songs (E)", shown: "shown", hidden: "hidden", colours: "Fractal colours", random: "random", fractal: "Fractal", fractal_auto: "random (a new one each scene)", quality: "Picture quality", quality_auto: "auto, fits this TV (now %s)", auto_demo: "Demo mode after 1 minute idle", shuffle: "Shuffle", repeat: "Repeat", all: "all", one: "one", off: "off", on: "on", stop: "Stop and close the player", loading: "Loading Omarchy Radio…", offline: "radio.omarchy.org cannot be reached: showing the copy kept on this TV.", empty: "Nothing here yet.", by: "by %s", send: "Make one, send it: radio.omarchy.org", nothing: "Pick a song: OK plays it.", lyrics: "Lyrics", no_lyrics: "No lyrics for this one yet.", resume: "resume at %s", keys: "OK play · ▶❚❚ pause · ◀◀ ▶▶ · RED demo · BACK", play_all: "Play all", shuffle_all: "Shuffle all", about: "Songs from radio.omarchy.org: each one made by the person named on it and sent in as a pull request, streamed from the site. Podcast: Omarchy Stories. Fractals drawn live on this TV." },
    it: { songs: "Brani", queue: "Prossimi", demo: "Modalità demo", settings: "Impostazioni", explicit: "Brani espliciti (E)", shown: "visibili", hidden: "nascosti", colours: "Colori dei frattali", random: "casuali", fractal: "Frattale", fractal_auto: "casuale (uno nuovo a ogni scena)", quality: "Qualità dell'immagine", quality_auto: "auto, si adatta a questa TV (ora %s)", auto_demo: "Modalità demo dopo 1 minuto senza tasti", shuffle: "Casuale", repeat: "Ripeti", all: "tutti", one: "uno", off: "off", on: "on", stop: "Ferma e chiudi il player", loading: "Carico Omarchy Radio…", offline: "radio.omarchy.org non risponde: mostro la copia salvata su questa TV.", empty: "Ancora niente qui.", by: "di %s", send: "Fanne uno e mandalo: radio.omarchy.org", nothing: "Scegli un brano: OK lo fa partire.", lyrics: "Testo", no_lyrics: "Nessun testo per questo brano, per ora.", resume: "riprendi da %s", keys: "OK play · ▶❚❚ pausa · ◀◀ ▶▶ · ROSSO demo · BACK", play_all: "Riproduci tutto", shuffle_all: "Tutto in casuale", about: "Brani da radio.omarchy.org: ognuno fatto da chi lo firma e arrivato come pull request, in streaming dal sito. Podcast: Omarchy Stories. Frattali disegnati dal vivo su questa TV." },
    de: { songs: "Songs", queue: "Als Nächstes", demo: "Demo-Modus", settings: "Einstellungen", explicit: "Explizite Songs (E)", shown: "sichtbar", hidden: "ausgeblendet", colours: "Fraktal-Farben", random: "zufällig", fractal: "Fraktal", fractal_auto: "zufällig (jede Szene neu)", quality: "Bildqualität", quality_auto: "auto, passend zu diesem TV (jetzt %s)", auto_demo: "Demo-Modus nach 1 Minute ohne Taste", shuffle: "Zufall", repeat: "Wiederholen", all: "alle", one: "einen", off: "aus", on: "an", stop: "Stoppen und Player schließen", loading: "Omarchy Radio wird geladen…", offline: "radio.omarchy.org nicht erreichbar: die Kopie auf diesem TV wird gezeigt.", empty: "Hier ist noch nichts.", by: "von %s", send: "Mach einen, schick ihn: radio.omarchy.org", nothing: "Wähle einen Song: OK spielt ihn.", lyrics: "Songtext", no_lyrics: "Noch kein Songtext für diesen Song.", resume: "weiter bei %s", keys: "OK spielen · ▶❚❚ Pause · ◀◀ ▶▶ · ROT Demo · BACK", play_all: "Alle abspielen", shuffle_all: "Alle zufällig", about: "Songs von radio.omarchy.org: jeder von der Person, die darauf steht, als Pull Request eingereicht und von der Seite gestreamt. Podcast: Omarchy Stories. Fraktale live auf diesem TV gezeichnet." },
    fr: { songs: "Titres", queue: "À suivre", demo: "Mode démo", settings: "Réglages", explicit: "Titres explicites (E)", shown: "affichés", hidden: "masqués", colours: "Couleurs des fractales", random: "aléatoires", fractal: "Fractale", fractal_auto: "aléatoire (une nouvelle à chaque scène)", quality: "Qualité d'image", quality_auto: "auto, adaptée à cette TV (actuellement %s)", auto_demo: "Mode démo après 1 minute sans touche", shuffle: "Aléatoire", repeat: "Répéter", all: "tout", one: "un", off: "non", on: "oui", stop: "Arrêter et fermer le lecteur", loading: "Chargement d'Omarchy Radio…", offline: "radio.omarchy.org ne répond pas : la copie gardée sur cette TV est affichée.", empty: "Rien ici pour l'instant.", by: "par %s", send: "Fais-en un, envoie-le : radio.omarchy.org", nothing: "Choisis un titre : OK le lance.", lyrics: "Paroles", no_lyrics: "Pas encore de paroles pour celui-ci.", resume: "reprendre à %s", keys: "OK lire · ▶❚❚ pause · ◀◀ ▶▶ · ROUGE démo · BACK", play_all: "Tout lire", shuffle_all: "Tout en aléatoire", about: "Titres de radio.omarchy.org : chacun fait par la personne qui le signe, envoyé en pull request et lu en streaming depuis le site. Podcast : Omarchy Stories. Fractales dessinées en direct sur cette TV." },
    es: { songs: "Canciones", queue: "A continuación", demo: "Modo demo", settings: "Ajustes", explicit: "Canciones explícitas (E)", shown: "visibles", hidden: "ocultas", colours: "Colores de los fractales", random: "aleatorios", fractal: "Fractal", fractal_auto: "aleatorio (uno nuevo en cada escena)", quality: "Calidad de imagen", quality_auto: "auto, se adapta a esta TV (ahora %s)", auto_demo: "Modo demo tras 1 minuto sin teclas", shuffle: "Aleatorio", repeat: "Repetir", all: "todas", one: "una", off: "no", on: "sí", stop: "Parar y cerrar el reproductor", loading: "Cargando Omarchy Radio…", offline: "radio.omarchy.org no responde: se muestra la copia guardada en esta TV.", empty: "Todavía no hay nada aquí.", by: "de %s", send: "Haz una y envíala: radio.omarchy.org", nothing: "Elige una canción: OK la reproduce.", lyrics: "Letra", no_lyrics: "Aún no hay letra para esta.", resume: "seguir en %s", keys: "OK reproducir · ▶❚❚ pausa · ◀◀ ▶▶ · ROJO demo · BACK", play_all: "Reproducir todo", shuffle_all: "Todo aleatorio", about: "Canciones de radio.omarchy.org: cada una hecha por quien la firma, enviada como pull request y escuchada en streaming desde el sitio. Podcast: Omarchy Stories. Fractales dibujados en directo en esta TV." }
  };
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function clock(s) {
    s = Math.max(0, Math.floor(s || 0));
    var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
    return (h ? h + ":" + (m < 10 ? "0" : "") : "") + m + ":" + (x < 10 ? "0" : "") + x;
  }
  function bar(f, w) { var n = Math.max(0, Math.min(w, Math.round(f * w))), s = ""; for (var i = 0; i < w; i++) s += i < n ? "█" : "░"; return s; }

  function open(el, api) {
    var E = api.engine, P = E.prefs, th = api.theme || {}, doc = el.ownerDocument || document;
    var L = T[(api.lang || "en").slice(0, 2)] || T.en;
    function t(k, a) { var v = L[k] || T.en[k] || k; return a == null ? v : v.replace("%s", a); }
    var A = th.accent || "#7aa2f7", F = th.fg || "#a9b1d6", D = th.dim || "#565f89", B = th.bg || "#1a1b26", G = th.green || "#9ece6a", C = th.cyan || "#7dcfff", W = th.bright || "#c0caf5";
    var SRC = [t("songs"), "Omarchy Stories", t("queue"), t("demo"), t("settings")];
    var S = { src: 0, pane: "list", idx: [0, 0, 0, 0, 0], items: [], demo: false, saver: !!api.saver, lastKey: Date.now(), sheetFor: "", sheet: [] };
    var w = doc.createElement("div"), st = doc.createElement("style");
    w.className = "radio";
    st.textContent = ".radio{position:absolute;left:0;top:0;right:0;bottom:0;z-index:56;background:#000;color:" + F + ";font-family:CaskaydiaMono,monospace;font-size:1.32vw;line-height:1.35;overflow:hidden}" +
      ".radio canvas.fx{position:absolute;left:0;top:0;width:100%;height:100%}" +
      ".radio .dim{position:absolute;left:0;top:0;right:0;bottom:0;background:" + B + ";opacity:.42;-webkit-transition:opacity .6s;transition:opacity .6s}" +
      ".radio .ui{position:absolute;left:0;top:0;right:0;bottom:0;-webkit-transition:opacity .6s;transition:opacity .6s}" +
      ".radio .hd{position:absolute;left:2vw;right:2vw;top:2.4vh;height:8vh;white-space:nowrap}.radio .logo{color:" + A + ";font-size:2.6vw;letter-spacing:.35em;font-weight:bold}" +
      ".radio .st{position:absolute;right:0;top:.6vh;color:" + C + ";text-align:right;font-size:.85em}" +
      ".radio .bx{position:absolute;border:.14vw solid " + D + ";border-radius:.35vw;padding:1.6vh 1vw 1vh;background:" + B + "cc}" +
      ".radio .bx.on{border-color:" + A + ";box-shadow:0 0 1.4vw " + A + "33}.radio .lg{position:absolute;top:-1.05em;left:.8em;background:" + B + ";padding:0 .4em;color:" + D + "}.radio .bx.on .lg{color:" + A + "}" +
      ".radio .r{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding:.18em .5em;border-radius:.25em;height:1.35em}.radio .r .b{color:" + A + "}.radio .r .s{color:" + D + "}.radio .r .p{color:" + G + ";float:right}" +
      ".radio .r .e{display:inline-block;background:" + D + ";color:" + B + ";border-radius:.2em;padding:0 .3em;margin-left:.5em;font-size:.8em;line-height:1.2em}" +
      ".radio .r.sel{background:" + D + "55}.radio .bx.on .r.sel{background:" + A + ";color:" + B + "}.radio .bx.on .r.sel .b,.radio .bx.on .r.sel .s,.radio .bx.on .r.sel .p{color:" + B + "}" +
      ".radio .np h1{font-size:1.5em;margin:.3em 0 .1em;color:" + W + ";white-space:normal}.radio .np .by{color:" + A + "}.radio .np .m{color:" + D + "}" +
      ".radio .np .sp{height:7vh;margin:1.2vh 0;white-space:nowrap}.radio .np .sp i{display:inline-block;width:5.4%;margin-right:.5%;vertical-align:bottom;background:" + A + "}" +
      ".radio .np .bar{color:" + G + "}.radio .np .ly{margin-top:1.4vh;white-space:pre-wrap;color:" + D + ";max-height:30vh;overflow:hidden}.radio .np .ly b{color:" + W + ";font-weight:normal}" +
      ".radio .ft{position:absolute;left:2vw;right:2vw;bottom:2.4vh;color:" + D + ";white-space:nowrap;overflow:hidden}" +
      ".radio canvas.sc{position:absolute;left:0;right:0;bottom:6vh;width:100%;height:18vh;display:none}.radio .corner{position:absolute;right:2vw;top:3vh;color:" + W + ";text-shadow:0 0 .5vw #000;display:none;text-align:right}";
    w.innerHTML = '<canvas class="fx"></canvas><div class="dim"></div><div class="ui">' +
      '<div class="hd"><span class="logo">RADIOMARCHY</span><div class="st"></div></div>' +
      '<div class="bx p0" style="left:2vw;top:13.5vh;width:17vw;bottom:8vh"><span class="lg">radio</span><div class="rows"></div></div>' +
      '<div class="bx p1" style="left:22vw;top:13.5vh;width:43vw;bottom:8vh"><span class="lg"></span><div class="rows"></div></div>' +
      '<div class="bx p2 np" style="left:68vw;top:13.5vh;right:2vw;bottom:8vh"><span class="lg">now playing</span><div class="in"></div></div>' +
      '<div class="ft"></div></div><canvas class="sc"></canvas><div class="corner"></div>';
    w.insertBefore(st, w.firstChild);
    el.appendChild(w);
    function q(s) { return w.querySelector(s); }
    if (api.logoHtml) { var lg = q(".logo"); lg.innerHTML = api.logoHtml; lg.style.letterSpacing = "0"; }
    var FX = root.OmarchyFractals, fx = FX ? FX.create(q("canvas.fx"), {
      ramGb: (root.navigator && root.navigator.deviceMemory) || 0,
      bands: E.bands, palette: P.palette, quality: P.quality,
      theme: [A, th.accent2 || "#bb9af7", C, th.red || "#f7768e"]
    }) : null;
    if (fx) { if (P.scene >= 0) fx.pin(P.scene); fx.start(); }
    var off = E.on(function () { render(); });
    var ROWS = 20;

    function items() {
      var cur = E.current(), out = [], i;
      if (S.src === 1) {
        if (!E.episodes.length) out.push({ label: E.loading ? t("loading") : t("empty") });
        for (i = 0; i < E.episodes.length; i++) (function (k, e) {
          var saved = P.pos[e.url];
          out.push({ tr: e, num: /^\d{2,}/.test(e.title) ? "🎙" : ("0" + (E.episodes.length - k)).slice(-2), label: e.title, sub: (e.dur ? clock(e.dur) : "") + (e.date ? "  ·  " + e.date.slice(5, 16) : "") + (saved ? "  ·  " + t("resume", clock(saved)) : ""), act: function () { E.play(E.episodes, k); } });
        })(i, E.episodes[i]);
      } else if (S.src === 2) {
        if (cur) out.push({ tr: cur, num: "▶", label: cur.title, sub: cur.artist, act: E.toggle });
        var up = E.upNext(60);
        if (!up.length && !cur) out.push({ label: t("nothing") });
        for (i = 0; i < up.length; i++) (function (k, e) { out.push({ tr: e, num: (k < 9 ? " " : "") + (k + 1), label: e.title, sub: e.artist, act: function () { for (var j = 0; j <= k; j++) E.next(true); } }); })(i, up[i]);
      } else if (S.src === 3) {
        out.push({ num: "✦", label: t("demo"), sub: "RED", act: function () { setDemo(true); } });
      } else if (S.src === 4) {
        out.push({ num: "›", label: t("explicit"), sub: t(P.explicit ? "shown" : "hidden"), act: function () { P.explicit = !P.explicit; E.save(); } });
        out.push({ num: "›", label: t("colours"), sub: P.palette === 0 ? t("random") : ["", "Omarchy theme", "rainbow", "fire"][P.palette], act: function () { P.palette = (P.palette + 1) % 4; E.save(); if (fx) fx.setPalette(P.palette); } });
        out.push({ num: "›", label: t("quality"), sub: P.quality === 0 ? t("quality_auto", fx ? fx.quality() : "-") : ["", "ultra", "high", "medium", "low"][P.quality], act: function () { P.quality = (P.quality + 1) % 5; E.save(); if (fx) fx.setQuality(P.quality); } });
        out.push({ num: "›", label: t("fractal"), sub: P.scene < 0 ? t("fractal_auto") : FX.SCENES[P.scene], act: function () { P.scene = P.scene + 1 >= 5 ? -1 : P.scene + 1; E.save(); if (fx) fx.pin(P.scene); } });
        out.push({ num: "›", label: t("auto_demo"), sub: t(P.autoDemo ? "on" : "off"), act: function () { P.autoDemo = !P.autoDemo; E.save(); } });
        out.push({ num: "›", label: t("shuffle"), sub: t(P.shuffle ? "on" : "off"), act: function () { E.setShuffle(!P.shuffle); } });
        out.push({ num: "›", label: t("repeat"), sub: t(P.repeat), act: E.cycleRepeat });
        out.push({ num: "■", label: t("stop"), sub: "", act: E.stop });
        out.push({ label: "Radiomarchy " + (api.version || "") + " · " + t("about") });
      } else {
        var list = visible(E.songs, P.explicit);
        if (!list.length) out.push({ label: E.loading ? t("loading") : t("empty"), sub: E.offline ? t("offline") : "" });
        else {
          out.push({ num: "▶", label: t("play_all"), sub: list.length + " · radio.omarchy.org" + (P.shuffle ? " · 🔀" : ""), act: function () { E.play(list, P.shuffle ? Math.floor(Math.random() * list.length) : 0); } });
          out.push({ num: "🔀", label: t("shuffle_all"), sub: t("shuffle"), act: function () { if (!P.shuffle) E.setShuffle(true); E.play(list, Math.floor(Math.random() * list.length)); } });
        }
        for (i = 0; i < list.length; i++) (function (k, s) { out.push({ tr: s, num: ("0" + (k + 1)).slice(-2), label: s.title, sub: s.artist + (s.album ? "  ·  " + s.album : ""), act: function () { E.play(list, k); } }); })(i, list[i]);
      }
      return out;
    }
    function render() {
      if (!w.parentNode) return;
      S.items = items();
      var cur = E.current(), i, h = "";
      for (i = 0; i < SRC.length; i++) h += '<div class="r' + (i === S.src ? " sel" : "") + '">' + (i === S.src ? "▸ " : "  ") + esc(SRC[i]) + "</div>";
      q(".p0 .rows").innerHTML = h;
      q(".p0").className = "bx p0" + (S.pane === "src" ? " on" : "");
      q(".p1").className = "bx p1" + (S.pane === "list" ? " on" : "");
      var tracks = S.items.filter(function (x) { return !!x.tr; }).length;
      q(".p1 .lg").textContent = SRC[S.src] + (tracks ? " · " + tracks : "");
      var box = q(".p1"), probe = q(".p1 .rows .r");   // as many rows as the pane holds
      if (box && probe && probe.offsetHeight) ROWS = Math.max(5, Math.floor((box.clientHeight - probe.offsetTop - 8) / (probe.offsetHeight + 1)) - 1);
      var idx = Math.min(S.idx[S.src] || 0, Math.max(0, S.items.length - 1)), top = Math.max(0, Math.min(idx - Math.floor(ROWS / 3), S.items.length - ROWS));
      S.idx[S.src] = idx;
      h = "";
      for (i = top; i < Math.min(S.items.length, top + ROWS); i++) {
        var it = S.items[i], now = it.tr && cur && it.tr.url === cur.url;
        h += '<div class="r' + (i === idx ? " sel" : "") + '"><span class="b">' + esc(it.num || " ") + "</span> " + esc(it.label) + (it.tr && it.tr.explicit ? '<span class="e">E</span>' : "") +
          (now ? '<span class="p">' + (E.playing() ? "♫" : "❚❚") + "</span>" : "") + (it.sub ? ' <span class="s">· ' + esc(it.sub) + "</span>" : "") + "</div>";
      }
      q(".p1 .rows").innerHTML = h;
      var st = E.playing() ? "● on air" : cur ? "❚❚ paused" : "○ ready";
      q(".st").innerHTML = esc(st + "  ·  " + E.songs.length + " songs  ·  " + E.episodes.length + " episodes") + "<br>" + esc("radio.omarchy.org  ·  " + (E.offline ? "offline copy" : "every song a pull request"));
      var np = "";
      if (!cur) np = "<h1>" + esc(t("nothing")) + "</h1>";
      else {
        np = '<div class="m">' + esc(E.error ? "[ ! " + E.error + " ]" : E.buffering() ? "[ … BUFFERING ]" : E.playing() ? "[ ● ON AIR ]" : "[ ❚❚ PAUSED ]") + "</div>" +
          "<h1>" + esc(cur.title) + (cur.explicit ? ' <span class="m">E</span>' : "") + '</h1><div class="by">' + esc(cur.kind === "episode" ? cur.artist : t("by", cur.artist || "?")) + "</div>" +
          '<div class="m">' + esc(cur.album || (cur.kind === "episode" ? cur.date : "Omarchy Radio · community playlist")) + "</div>" +
          '<div class="sp"></div><div class="bar"></div><div class="m tm"></div><div class="ly"></div><div class="m">' + esc(String(cur.page || "").replace(/^https:\/\//, "")) + "</div>";
        if (S.sheetFor !== cur.url) {
          S.sheetFor = cur.url;
          S.sheet = [];
          E.lyrics(cur, function (sh) { if (S.sheetFor === cur.url) { S.sheet = sh; tick(); } });
        }
      }
      q(".np .in").innerHTML = np;
      q(".ft").textContent = (cur ? (E.playing() ? "♫ " : "❚❚ ") + (cur.artist ? cur.artist + " — " : "") + cur.title + "   ·   " : "") + t("keys") + "   ·   " + t("send");
      updateDemo();
      tick();
    }
    var spT = 0;
    function tick() {
      var cur = E.current();
      if (!cur || !w.parentNode) return;
      var pos = E.position(), d = E.duration(), b = q(".np .bar"), tm = q(".np .tm"), ly = q(".np .ly"), sp = q(".np .sp");
      if (b) b.textContent = bar(d ? pos / d : 0, 26);
      if (tm) tm.textContent = clock(pos) + " / " + (d ? clock(d) : "--:--") + (P.shuffle ? "  ·  shuffle" : "") + (P.repeat === "one" ? "  ·  repeat one" : "");
      if (sp) {
        var bars = E.bars(), h = "";
        for (var i = 0; i < 16; i++) h += '<i style="height:' + Math.round(100 * Math.max(0.03, bars ? bars[i] : 0.03)) + '%;opacity:' + (0.55 + 0.45 * i / 15).toFixed(2) + '"></i>';
        sp.innerHTML = h;
      }
      if (ly) {
        if (cur.kind === "episode") ly.textContent = cur.about || "";
        else if (!S.sheet.length) ly.textContent = t("no_lyrics");
        else {
          var at = lineAt(S.sheet, pos * 1000), from = at < 0 ? 0 : Math.max(0, at - 2), out = "";
          for (var k = from; k < Math.min(S.sheet.length, from + (at < 0 ? 40 : 9)); k++) out += (k === at ? "<b>" + esc(S.sheet[k].text) + "</b>" : esc(S.sheet[k].text)) + "\n";
          ly.innerHTML = out;
        }
      }
    }
    var tickT = setInterval(function () {
      tick();
      if (!S.demo && !S.saver && P.autoDemo && E.playing() && Date.now() - S.lastKey > 60000) setDemo(true);
    }, 250);
    spT = setInterval(function () { var sp = q(".np .sp"); if (sp && E.playing()) { var bars = E.bars(); if (bars) for (var i = 0; i < sp.children.length; i++) sp.children[i].style.height = Math.round(100 * Math.max(0.03, bars[i])) + "%"; } }, 42);

    // demo mode: the fractal on its own, a sine scroller of what is playing
    var sc = q("canvas.sc"), scx = sc.getContext("2d"), scX = 0, scLast = 0, scRaf = 0, scText = "", band = { bass: 0 };
    function updateDemo() {
      var cur = E.current();
      scText = "★  NOW PLAYING  ·  " + (cur ? (cur.kind === "episode" ? "OMARCHY STORIES  ·  " + cur.title : cur.title.toUpperCase() + "  BY  " + String(cur.artist).toUpperCase()) : "RADIOMARCHY  ·  OMARCHY RADIO ON YOUR TV") +
        "  ·  RADIO.OMARCHY.ORG  ·  EVERY SONG A PULL REQUEST FROM THE OMARCHY COMMUNITY  ·  FRACTALS: MANDELBROT  JULIA  PLASMA  TUNNEL  KALISET  ·  GREETZ TO EVERY DOTFILE TWEAKER OUT THERE  ★          ";
      q(".corner").textContent = "RADIOMARCHY  ·  " + (fx ? fx.scene() + " · " + fx.quality() : "") + (S.saver ? "" : "  ·  ◀ ▶ fractal · ▲ ▼ colours");
    }
    function scroller() {
      if (!S.demo) return;
      scRaf = root.requestAnimationFrame(scroller);
      var now = Date.now();
      if (scLast && now - scLast < 1000 / 24) return;
      var dt = scLast ? Math.min(0.1, (now - scLast) / 1000) : 0.016;
      scLast = now;
      var W2 = sc.clientWidth, H2 = sc.clientHeight;
      if (sc.width !== W2 || sc.height !== H2) { sc.width = W2; sc.height = H2; }
      if (!E.bands(band)) band.bass = 0.3 + 0.2 * Math.sin(now / 1400);
      scx.clearRect(0, 0, W2, H2);
      var size = H2 * 0.42;
      scx.font = "bold " + Math.round(size) + "px CaskaydiaMono, monospace";
      scx.shadowColor = "rgba(0,0,0,.75)";
      scx.shadowBlur = 8;
      var cw = scx.measureText("M").width, total = cw * scText.length;
      scX -= dt * cw * 6.5;
      if (scX < -total) scX += total;
      var base = H2 * 0.62, amp = H2 * (0.13 + 0.12 * band.bass), sec = now / 1000, first = Math.max(0, Math.floor(-scX / cw)) - 1;
      for (var k = first; k < first + W2 / cw + 3; k++) {
        var i = ((k % scText.length) + scText.length) % scText.length, lx = scX + k * cw;
        if (lx < -cw || lx > W2) continue;
        scx.fillStyle = (Math.sin(lx / W2 * 3.1 + sec) > 0) ? A : C;
        scx.fillText(scText.charAt(i), lx, base + Math.sin(lx / W2 * 6.3 + sec * 3.1) * amp);
      }
    }
    function setDemo(on) {
      S.demo = on;
      q(".ui").style.opacity = on ? "0" : "1";
      q(".dim").style.opacity = on ? "0" : ".42";
      sc.style.display = on ? "block" : "none";
      q(".corner").style.display = on ? "block" : "none";
      if (on) { updateDemo(); scLast = 0; scroller(); } else if (scRaf) { root.cancelAnimationFrame(scRaf); scRaf = 0; }
    }

    function key(k) {
      S.lastKey = Date.now();
      if (k === "play" || k === "pause" || k === "playpause") { if (!E.current()) { var l = visible(E.songs, P.explicit); if (l.length) E.play(l, P.shuffle ? Math.floor(Math.random() * l.length) : 0); } else if (k === "pause") E.pause(); else E.toggle(); return true; }
      if (k === "next" || k === "chup") { E.next(true); return true; }
      if (k === "prev" || k === "chdown") { E.prev(); return true; }
      if (k === "ff") { E.seekBy(15); return true; }
      if (k === "rew") { E.seekBy(-15); return true; }
      if (S.saver) { close(); return true; }
      if (S.demo) {
        if (k === "left" || k === "right") { if (fx) fx.next(k === "left" ? -1 : 1); setTimeout(updateDemo, 100); }
        else if (k === "up" || k === "down") { P.palette = (P.palette + (k === "up" ? 1 : 3)) % 4; E.save(); if (fx) fx.setPalette(P.palette); }
        else if (k === "ok") E.toggle();
        else setDemo(false);
        return true;
      }
      var it = S.items[S.idx[S.src] || 0];
      if (/^[1-5]$/.test(k)) { var s = +k - 1; if (s === 3) setDemo(true); else { S.src = s; S.pane = "list"; render(); } return true; }
      if (k === "red") { setDemo(true); return true; }
      if (k === "green") { E.setShuffle(!P.shuffle); return true; }
      if (k === "yellow") { E.cycleRepeat(); return true; }
      if (k === "blue") { P.explicit = !P.explicit; E.save(); render(); return true; }
      if (k === "back") { if (S.pane === "list") { S.pane = "src"; render(); } else close(); return true; }
      if (S.pane === "src") {
        if (k === "up") S.src = (S.src + SRC.length - 1) % SRC.length;
        else if (k === "down") S.src = (S.src + 1) % SRC.length;
        else if (k === "right" || k === "ok") { if (S.src === 3) { setDemo(true); return true; } S.pane = "list"; }
        render();
        return true;
      }
      var i = S.idx[S.src] || 0;
      if (k === "up") S.idx[S.src] = Math.max(0, i - 1);
      else if (k === "down") S.idx[S.src] = Math.min(S.items.length - 1, i + 1);
      else if (k === "pageup") S.idx[S.src] = Math.max(0, i - ROWS);
      else if (k === "pagedown") S.idx[S.src] = Math.min(S.items.length - 1, i + ROWS);
      else if (k === "left") S.pane = "src";
      else if (k === "ok" && it && it.act) it.act();
      render();
      return true;
    }
    function close() { destroy(); if (api.onExit) api.onExit(); }
    function startSaverMusic() {
      if (!S.saver) return;
      if (E.ensureAudible) E.ensureAudible();
      if (!E.current()) {
        var l = visible(E.songs, P.explicit);
        if (l.length) E.play(l, P.shuffle ? Math.floor(Math.random() * l.length) : 0);
      }
    }
    var offSaver = null;
    function destroy() {
      clearInterval(tickT);
      clearInterval(spT);
      if (scRaf) root.cancelAnimationFrame(scRaf);
      off();
      if (offSaver) offSaver();
      if (fx) fx.destroy();
      if (w.parentNode) w.parentNode.removeChild(w);
    }
    render();
    if (!E.songs.length) E.load();
    var cur0 = E.current();
    if (cur0 && cur0.kind === "episode") { S.src = 1; render(); }
    if (api.demo || api.saver) setDemo(true);
    if (S.saver) {
      startSaverMusic();
      offSaver = E.on(startSaverMusic);
    }
    return { key: key, destroy: destroy, demo: function () { return S.demo; }, _state: function () { return S; } };
  }

  /** Media keys on top of Cinemarchy's key names (webOS: 415 play, 19 pause, 417 ▶▶, 412 ◀◀, 427/428 CH). */
  function mediaKey(e) {
    var c = e.keyCode, k = e.key;
    if (c === 415 || k === "MediaPlay") return "play";
    if (c === 19 || k === "MediaPause") return "pause";
    if (c === 10252 || k === "MediaPlayPause") return "playpause";
    if (c === 417 || k === "MediaFastForward") return "ff";
    if (c === 412 || k === "MediaRewind") return "rew";
    if (k === "MediaTrackNext") return "next";
    if (k === "MediaTrackPrevious") return "prev";
    if (c === 413 || k === "MediaStop") return "pause";
    return "";
  }

  root.OmarchyRadio = {
    engine: engine, open: open, mediaKey: mediaKey,
    parseSongs: parseSongs, parseFeed: parseFeed, parseLyrics: parseLyrics, lineAt: lineAt, slug: slug, uniqueSlug: uniqueSlug, visible: visible,
    shuffled: shuffled, Bands: Bands, clock: clock, bar: bar, T: T
  };
})(typeof window !== "undefined" ? window : this);
