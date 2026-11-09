/*
 * OmarchyTV 2.15: the big moments of the TV's Pulse, full screen over the world.
 * - "LOOK UP": the ISS or a Starlink train is about to cross your sky (3 minutes before, until it has gone): where to
 *   look (a compass arrow and the direction), how high, and a countdown. Only at night, while the Sun lights it.
 * - Launch: from T-10 minutes, the rocket, the mission and a big countdown; at T-0 the overlay steps aside and Pulse's
 *   own rocket lifts off on your horizon.
 * Reads OmarchyPulseTV's events(); draws plain DOM in the theme's colours (pulse.js sets --accent / --fg on :root).
 * ES5. MIT, Marco Porro.
 */
(function (root, doc) {
  "use strict";
  var hub = root.__pulseTV;
  if (!hub || !hub.events) return;
  var css = doc.createElement("style");
  css.textContent =
    "#otvm{position:fixed;top:0;left:0;right:0;bottom:0;z-index:50;display:none;align-items:center;justify-content:center;pointer-events:none;" +
    "font-family:var(--font,monospace);color:var(--fg,#c0caf5);background:radial-gradient(ellipse at center,rgba(0,0,0,.25),rgba(0,0,0,.72))}" +
    "#otvm.on{display:flex;animation:otvmIn .8s ease-out}" +
    "@keyframes otvmIn{from{opacity:0;transform:scale(1.04)}to{opacity:1;transform:none}}" +
    "#otvm .box{text-align:center;padding:3vh 5vw;border:1px solid rgba(var(--accent-rgb,122,162,247),.6);border-radius:1.2vh;" +
    "background:rgba(var(--bg-rgb,0,0,0),.86);box-shadow:0 0 6vh rgba(var(--accent-rgb,122,162,247),.35)}" +
    "#otvm .tag{font-size:2.4vh;letter-spacing:.6em;color:var(--accent,#7aa2f7);margin-bottom:1.2vh}" +
    "#otvm .big{font-size:11vh;font-weight:700;letter-spacing:.06em;line-height:1.05;text-shadow:0 0 4vh rgba(var(--accent-rgb,122,162,247),.6)}" +
    "#otvm .sub{font-size:3.2vh;margin-top:1.4vh;opacity:.9}" +
    "#otvm .dir{display:block;width:13vh;height:13vh;margin:2.2vh auto 0}" +
    "#otvm .dir svg{width:100%;height:100%;overflow:visible}" +
    "#otvm .dir .ring{fill:none;stroke:rgba(var(--fg-rgb,192,202,245),.35);stroke-width:1.5}" +
    "#otvm .dir .n{fill:rgba(var(--fg-rgb,192,202,245),.75);font-size:13px;text-anchor:middle;font-family:inherit}" +
    "#otvm .dir .arrow{fill:var(--accent,#7aa2f7);stroke:var(--accent,#7aa2f7);stroke-width:2;stroke-linejoin:round;filter:drop-shadow(0 0 6px var(--accent,#7aa2f7))}" +
    "#otvm .cd{font-size:7vh;margin-top:1.6vh;font-variant-numeric:tabular-nums;color:var(--accent,#7aa2f7)}" +
    "#otvm.launch .big{font-size:16vh}";
  doc.head.appendChild(css);
  var el = doc.createElement("div");
  el.id = "otvm";
  el.innerHTML = '<div class="box"><div class="tag"></div><div class="big"></div><div class="sub"></div><div class="dir"><svg viewBox="-50 -50 100 100"><circle class="ring" r="40"/><text class="n" y="-43">N</text>' +
    '<g class="rot"><path class="arrow" d="M0,-34 L11,-12 L4,-14 L4,26 L-4,26 L-4,-14 L-11,-12 Z"/></g></svg></div><div class="cd"></div></div>';
  doc.body.appendChild(el);
  var q = function (s) { return el.querySelector(s); };
  var NAMES = ["north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"];
  function side(az) { return NAMES[Math.round((((az % 360) + 360) % 360) / 45) % 8]; }
  function mmss(s) { s = Math.max(0, Math.floor(s)); var m = Math.floor(s / 60), x = s % 60; return m + ":" + (x < 10 ? "0" : "") + x; }
  var shown = "";
  function tick() {
    var ev = hub.events(), e = null, i;
    for (i = 0; i < ev.length; i++) if (ev[i].kind === "launch") { e = ev[i]; break; }   // a launch wins
    if (!e) e = ev[0] || null;
    if (!e) { if (shown) { el.className = ""; shown = ""; } return; }
    if (e.kind === "launch") {
      el.className = "on launch";
      q(".tag").textContent = "LAUNCH";
      q(".big").textContent = "T-" + mmss(e.in);
      q(".sub").textContent = e.rocket + " · " + e.mission;
      q(".dir").style.display = "none";
      q(".cd").textContent = e.in < 60 ? "go for liftoff" : "";
    } else {
      el.className = "on look";
      q(".tag").textContent = e.phase === "now" ? "LOOK UP · NOW" : "LOOK UP";
      q(".big").textContent = e.kind === "iss" ? "ISS" : "STARLINK" + (e.n ? " ×" + e.n : "");
      q(".sub").textContent = "towards the " + side(e.az) + " · " + Math.round(e.el) + "° high";
      q(".dir").style.display = "";
      q(".dir .rot").setAttribute("transform", "rotate(" + Math.round(e.az) + ")");   // north up, like a map
      q(".cd").textContent = e.phase === "now" ? (e.left > 0 ? mmss(e.left) + " left" : "above you now") : "in " + mmss(e.in);
    }
    shown = e.kind;
  }
  setInterval(tick, 500);
  root.__pulseTVMoments = { tick: tick };
})(window, document);
