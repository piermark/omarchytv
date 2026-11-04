// OmarchyTV language rules for LG webOS / Samsung Tizen (app.js) and node tests: which language to show, the
// menu order, native names and speech tags. Same languages as the Android launcher (LauncherConfig.LANGUAGES).
// MIT, Marco Porro.
(function (root) {
  "use strict";

  var LANGS = ["en", "it", "de", "fr", "es", "pt-PT", "pt-BR", "nl", "pl", "sv", "da", "nb", "fi", "cs", "hu", "ro",
               "el", "tr", "uk", "ru", "ar", "zh-CN", "ja"];
  var NAMES = { en: "English", it: "Italiano", de: "Deutsch" };   // the others come from i18n.js
  var SPEECH = { en: "en-US", it: "it-IT", de: "de-DE", fr: "fr-FR", es: "es-ES", "pt-PT": "pt-PT", "pt-BR": "pt-BR",
                 nl: "nl-NL", pl: "pl-PL", sv: "sv-SE", da: "da-DK", cs: "cs-CZ", ro: "ro-RO", el: "el-GR", ru: "ru-RU",
                 nb: "nb-NO", fi: "fi-FI", hu: "hu-HU", tr: "tr-TR", uk: "uk-UA", ar: "ar-SA", "zh-CN": "zh-CN", ja: "ja-JP" };

  /**
   * The language to show: the saved one when it has strings, else the TV's (navigator.language), else English.
   * "pt-BR" matches Brazil; any other "pt" is Portugal. Any Chinese is Simplified (zh-CN); "no"/"nn" is Bokmål.
   * Arabic is right-to-left text only: the layout is not mirrored.
   */
  function resolve(saved, nav, has) {
    if (saved && has(saved)) return saved;
    var n = String(nav || "en").replace("_", "-");
    var two = n.slice(0, 2).toLowerCase();
    if (two === "pt") return /^pt-br/i.test(n) && has("pt-BR") ? "pt-BR" : has("pt-PT") ? "pt-PT" : "en";
    if (two === "zh") return has("zh-CN") ? "zh-CN" : "en";   // Simplified for every Chinese locale for now
    if (two === "no" || two === "nn") return has("nb") ? "nb" : "en";
    return has(two) ? two : "en";
  }

  /** Next value in the language menu cycle: "" (auto) → en → it → … → ja → "". */
  function next(cur) {
    if (!cur) return LANGS[0];
    var i = LANGS.indexOf(cur);
    return i >= 0 && i + 1 < LANGS.length ? LANGS[i + 1] : "";
  }

  /** Speech recognition / TTS tag ("it" → "it-IT"). */
  function speechTag(lang) {
    return SPEECH[lang] || "en-US";
  }

  /** Language sent with Grok Bot questions: a plain BCP-47 tag ("it", "pt-BR"). */
  function askTag(lang) {
    return lang === "pt-PT" ? "pt" : (SPEECH[lang] ? lang : "en");
  }

  function name(lang, i18n) {
    return NAMES[lang] || (i18n && i18n[lang] && i18n[lang].name) || lang;
  }

  var api = { LANGS: LANGS, resolve: resolve, next: next, speechTag: speechTag, askTag: askTag, name: name };
  root.OmarchyLang = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : this);
