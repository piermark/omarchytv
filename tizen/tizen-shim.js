/* OmarchyTV Samsung Tizen shim: loaded before compat.js only in the Tizen .wgt (see build-wgt.sh).
 * Discovers native application IDs and registers only app-level remote keys. */
(function () {
  if (!window.tizen) return;
  window.OMARCHY_PLATFORM = "tizen";
  window.OMARCHY_TIZEN_IDS = {};
  var selfId = "OmarchyTV0.OmarchyTV";
  try { selfId = window.tizen.application.getCurrentApplication().appInfo.id || selfId; } catch (e) {}
  var preloaded = ["org.tizen.netflix-app", "org.tizen.primevideo", "org.tizen.apple.apple-music", "org.tizen.browser", "com.samsung.tv.aria-video", "com.samsung.tv.SmartThingsApp"];
  var excluded = [selfId, "OmKeyCtrl0.Keys", "adplatform.ADPlayer", "WykOS4t4F0.cmsWebApp", "25emanual3.emanual", "ZODRT12q2l.WebAccountExtension", "ByJlgt0XeD.CastSettingsApp", "tv6pbVMQFF.PrivacyChoices", "w2VwCK465D.OTApp", "J9fZJDMi1v.SecurityCenter"];
  var brands = {netflix:"netflix",primevideo:"prime",amazonprimevideo:"prime",youtube:"youtube",disneyplus:"disney",disney:"disney",spotify:"spotify",jellyfin:"jellyfin",plex:"plex",hbomax:"hbomax",max:"hbomax",appletv:"appletv",hulu:"hulu",peacocktv:"peacock",youtubetv:"youtubetv",xbox:"xbox",internet:"browser"};
  // Native icons can be missing or generic. These store IDs and images were verified on the TV.
  var iconStoreIds = {
    "org.tizen.netflix-app": "3201907018807",
    "org.tizen.primevideo": "3201910019365",
    "MCmYXNxgcu.DisneyPlus": "3201901017640",
    "LBUAQX1exg.Hulu": "3201601007625",
    "com.samsung.tv.aria-video": "3201807016597",
    "gzcc4LRFBF.Peacock": "3202006020991",
    "ZmmGjO6VKO.slingtv": "3201707014448",
    "9Ur5IzDKqV.TizenYouTube": "111299001912",
    "rJeHak5zRg.Spotify": "3201606009684",
    "PvWgqxV3Xa.YouTubeTV": "3201707014489",
    "com.samsung.tv.SmartThingsApp": "3202311033054",
    "org.tizen.browser": "3202010022079",
    "GHI3a0zMSx.XboxGamePass": "3202203026799",
    "5b8c3eb16b.BeamCTVDev": "3202301029760",
    "OGLLvqej7u.CrunchyrollWebApp": "3202302030097",
    "rTn6ClEurC.Jellyfin": "3202007021208"
  };
  var discovering = false;
  var checkingHelper = false;
  var helperInstalled = false;
  function ensureButtonHelper() {
    if (!helperInstalled || checkingHelper || document.hidden) return;
    checkingHelper = true;
    try {
      window.tizen.application.getAppsContext(function (contexts) {
        if (document.hidden) { checkingHelper = false; return; }
        if (contexts.some(function (c) { return c.appId === "OmKeyCtrl0.Keys"; })) { checkingHelper = false; return; }
        window.tizen.application.launch("OmKeyCtrl0.Keys", function () { checkingHelper = false; }, function (error) {
          checkingHelper = false;
          window.OMARCHY_BUTTON_HELPER_ERROR = error && error.name;
        });
      }, function () { checkingHelper = false; });
    } catch (e) { checkingHelper = false; }
  }
  function discover() {
    if (discovering) return;
    discovering = true;
    try {
      window.tizen.application.getAppsInfo(function (apps) {
        discovering = false;
        helperInstalled = apps.some(function (a) { return a && a.id === "OmKeyCtrl0.Keys"; });
        ensureButtonHelper();
        var seen = {}, rows = [];
        apps.forEach(function (a) {
          if (!a || !a.id || !a.name || seen[a.id] || excluded.indexOf(a.id) >= 0) return;
          if (preloaded.indexOf(a.id) < 0 && (a.show === false || !/^(?:[A-Za-z0-9]{10}\.[A-Za-z0-9_-]+|\d{9,13})$/.test(a.id) || /^\d{4}ePop\d+\./i.test(a.id) || /service|worker|preview|promotion|plugin|launcher|test|sdk|factory|daemon|dummy|agent|e-?manual|account|adplayer|privacy|security/i.test(a.name + " " + a.id))) return;
          seen[a.id] = true;
          var brand = brands[a.name.toLowerCase().replace(/[^a-z0-9]/g, "")];
          var id = brand && !seen["brand:" + brand] ? brand : a.id;
          seen["brand:" + brand] = true;
          var iconPaths = [];
          if (iconStoreIds[a.id]) iconPaths.push("/opt/share/webappservice/apps_icon/all/" + iconStoreIds[a.id] + "/304x304.png");
          if (typeof a.iconPath === "string" && a.iconPath && iconPaths.indexOf(a.iconPath) < 0) iconPaths.push(a.iconPath);
          rows.push({id:id, name:a.name, appId:a.id, tizenId:a.id, iconPath:iconPaths[0] || "", iconPaths:iconPaths, brand:brand || id});
          window.OMARCHY_TIZEN_IDS[id] = a.id;
        });
        if (!rows.length) return;
        window.OMARCHY_TIZEN_APPS = rows;
        window.dispatchEvent(new CustomEvent("omarchy-tizen-apps", {detail:rows}));
      }, function () { discovering = false; });
    } catch (e) { discovering = false; }
  }
  var inputPending = false;
  var inputDiscovery = false;
  function hdmi(source) {
    return source && source.type === "HDMI" && Number.isInteger(source.number) && source.number >= 1 && source.number <= 99;
  }
  function tv(source) {
    return source && source.type === "TV" && Number.isInteger(source.number) && source.number >= 0;
  }
  function sources(info) {
    return (Array.isArray(info.connected) ? info.connected : []).concat(Array.isArray(info.disconnected) ? info.disconnected : []);
  }
  function discoverInputs() {
    if (inputDiscovery || !window.tizen.systeminfo) return;
    inputDiscovery = true;
    try {
      window.tizen.systeminfo.getPropertyValue("VIDEOSOURCE", function (info) {
        inputDiscovery = false;
        var seen = {}, rows = [];
        sources(info).forEach(function (s) {
          if (!hdmi(s) || seen[s.number]) return;
          seen[s.number] = true;
          var name = "HDMI " + s.number;
          rows.push({id:"hdmi" + s.number, name:name, sub:name + (Array.isArray(info.connected) && info.connected.indexOf(s) >= 0 ? " · Connected" : " · No signal"), tizenInput:true, number:s.number});
        });
        rows.sort(function (a, b) { return a.number - b.number; });
        if (sources(info).some(tv)) rows.unshift({id:"tv",name:"TV",sub:"TV",tizenInput:true});
        window.OMARCHY_TIZEN_INPUTS = rows;
        window.dispatchEvent(new CustomEvent("omarchy-tizen-inputs", {detail:window.OMARCHY_TIZEN_INPUTS}));
      }, function () { inputDiscovery = false; });
    } catch (e) { inputDiscovery = false; }
  }
  window.OMARCHY_TIZEN_SWITCH_INPUT = function (id, success, failure) {
    function reject(e) { if (failure) failure(e); }
    if (inputPending) { reject(new Error("Another input change is in progress")); return; }
    if (id !== "tv" && !/^hdmi[1-9][0-9]?$/.test(id)) { reject(new Error("Choose a valid TV input")); return; }
    var selection = id === "tv" ? {type:"TV"} : {type:"HDMI",number:Number(id.slice(4))};
    var label = id === "tv" ? "TV" : "HDMI " + selection.number;
    inputPending = true;
    var finished = false, deadline = Date.now() + 8000;
    var timer = setTimeout(function () { finish(new Error("The TV did not confirm the input change")); }, 8000);
    function finish(error) {
      if (finished) return;
      finished = true; inputPending = false; clearTimeout(timer);
      if (error) reject(error); else if (success) success();
    }
    function active() { return !finished && Date.now() < deadline; }
    function viewer() {
      if (!active()) return;
      try {
        window.tizen.application.launch("org.tizen.tv-viewer", function () { finish(); }, function (e) {
          finish(new Error(("Switched to " + label + " but could not open the TV viewer: ") + ((e && (e.message || e.name)) || "launch failed")));
        });
      } catch (e) { finish(e); }
    }
    // Tizen 5.5 and older (2020 sets, issue #87): org.tizen.tv-viewer restores its own last source (Samsung TV
    // Plus) on launch and overrides the HDMI we just set. There, step aside instead: hiding OmarchyTV shows the
    // MAIN window, which already plays the confirmed HDMI source. Newer firmware keeps the viewer hand-off.
    function oldViewer() {
      try {
        var v = String(window.tizen.systeminfo.getCapability("http://tizen.org/feature/platform.version") || "");
        var major = parseInt(v, 10);
        return major > 0 && major < 6;
      } catch (e) { return false; }
    }
    function showSource() {
      try { window.tizen.application.getCurrentApplication().hide(); finish(); }
      catch (e) { viewer(); }
    }
    try {
      window.tizen.systeminfo.getPropertyValue("VIDEOSOURCE", function (info) {
        if (!active()) return;
        var source = sources(info).filter(function (s) { return selection.type === "TV" ? tv(s) : hdmi(s) && s.number === selection.number; })[0];
        if (!source) { finish(new Error("This " + label + " input is no longer available")); return; }
        if (deadline - Date.now() < 2000) { finish(new Error("Not enough time remains to change the TV input")); return; }
        // This firmware rejects a reconstructed source. Preserve the native signal/editName fields.
        try {
          window.tizen.tvwindow.setSource(source, function () {
            if (!active()) return;
            try {
              var current = window.tizen.tvwindow.getSource("MAIN");
              if (!current || current.type !== source.type || current.number !== source.number) {
                finish(new Error(label + " may have switched, but the TV could not confirm it")); return;
              }
              if (selection.type === "HDMI" && oldViewer()) showSource(); else viewer();
            } catch (e) { finish(new Error(label + " may have switched, but the TV could not confirm it")); }
          }, finish, "MAIN");
        } catch (e) { finish(e); }
      }, finish);
    } catch (e) { finish(e); }
  };
  discover();
  discoverInputs();
  document.addEventListener("visibilitychange", function () { if (!document.hidden) { discover(); discoverInputs(); } });
  try {
    var keys = ["MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaFastForward", "MediaRewind",
      "ChannelUp", "ChannelDown", "ColorF0Red", "ColorF1Green", "ColorF2Yellow", "ColorF3Blue",
      "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"];
    keys.forEach(function (k) { try { window.tizen.tvinputdevice.registerKey(k); } catch (e) {} });
  } catch (e) {}
})();

// Window title says Tizen instead of the shared webOS title.
document.addEventListener("DOMContentLoaded", function () { document.title = document.title.replace("(webOS)", "(Tizen)"); });
