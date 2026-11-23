/*
 * OmarchyTV's JS service on LG webOS (com.omarchytv.home.service), packed into the ipk by build-ipk.sh.
 * luna://com.omarchytv.home.service/listApps -> { returnValue: true, launchPoints: [...] }
 * or { returnValue: false, info: "not approved" | "invalid origin" | "unavailable" | "closed" | "no answer" | "error", errorText }.
 * The app calls it only when the page's own SSAP WebSocket fails (webOS 26 closes it with 1008 "invalid origin",
 * #173), see refreshTvApps in app/app.js. The TV's client key stays here, in a file (ssap-client.js).
 * Plain ES5, Node core modules only: older webOS ships Node 0.12.
 */
"use strict";

var path = require("path");
var Service = require("webos-service");
var ssap = require("./ssap-client.js");

var NAME = require("./package.json").name;
var service = new Service(NAME);

var home = process.env.HOME && process.env.HOME !== "/" ? process.env.HOME : null;
var store = ssap.fileKeyStore([
  home ? path.join(home, ".omarchytv-ssap-key.json") : null,
  path.join(__dirname, "client-key.json"),
  "/tmp/omarchytv-ssap-key.json"
].filter(function (p) { return !!p; }));

// The service would otherwise exit when idle while the TV's prompt is still on screen.
function keepAlive(id, ready) {
  var am = service.activityManager, released = false;
  function noop() {}
  try {
    if (am && typeof am.create === "function") {
      am.create(id, function (activity) {
        ready(function () {
          if (released) return; released = true;
          try { am.complete(activity, noop); } catch (e) {}
        });
      });
      return;
    }
  } catch (e) {}
  ready(noop);
}

var busy = null;   // one SSAP session at a time: a second call while the prompt is up joins the first
service.register("listApps", function (message) {
  var p = message.payload || {};
  var holdMs = typeof p.holdMs === "number" ? p.holdMs : 0;
  if (busy) { busy.push(message); return; }
  busy = [message];
  keepAlive("omarchytv-listApps", function (release) {
    ssap.listLaunchPoints(store, { holdMs: holdMs }, function (err, lps) {
      var waiting = busy; busy = null;
      var answer = err ? { returnValue: false, info: err.info || "error", errorText: String(err.message || err) }
                       : { returnValue: true, launchPoints: lps };
      for (var i = 0; i < waiting.length; i++) { try { waiting[i].respond(answer); } catch (e) {} }
      setTimeout(release, err ? 0 : Math.min(holdMs, 15000) + 500);
    });
  });
});

// Forget the TV's approval (the app's "Repair TV connection" clears its own key the same way).
service.register("forget", function (message) {
  store.clear();
  message.respond({ returnValue: true });
});
